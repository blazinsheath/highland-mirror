//! Media audio transport with an explicit RTP clock anchor and session-scoped cleanup.
use std::{net::SocketAddr, sync::{Arc, Mutex}, time::{Duration, Instant}};
use catplay_carplay::{
    audio::{AudioRecorder, AudioRecorderBox, AudioSink, AudioSinkBox, AudioStreamBasicDescription, codec::{AudioEncoderFactory, runtime::AudioEncoderDispatch}},
    carplay_tx::{AirPlayRtspClient, AirPlayTransmitterBootstrapStreams},
    cipher::AirPlayStreamEncryption,
    clock::{MediaClockBox, MediaClockProxy, NtpU64},
    msg::{Setup, StreamDescriptionAudio, StreamDescriptionResponse, StreamType, TeardownPayload},
    rtp::{RTP_BUFFER_PAD, RTP_PACKET_MAX, record::RtpSessionTx},
    rtsp_frame::{HttpHeader, HttpStatus, RtspMethod, RtspRequest},
};
use catplay_tokio::UdpHelper;
use eyre::{Result, eyre};
use tokio::{net::UdpSocket, sync::Notify};
use tokio_util::task::AbortOnDropHandle;
use crate::audio::AudioConfig;

pub type RtpAudioSender = UdpHelper<RTP_PACKET_MAX, RTP_BUFFER_PAD, crate::audio_rtp::CarPlayAudioSession>;

#[derive(Clone)]
pub struct AudioTransport {
    pub client: AirPlayRtspClient,
    bind: SocketAddr,
    peer: SocketAddr,
    cipher: AirPlayStreamEncryption,
    clock: MediaClockProxy,
}

impl AudioTransport {
    /// Query gain on the existing control session. Never change an unknown gain or
    /// raise a normal setting; only a reported mute receives a quiet -20 dB value.
    pub async fn probe_receiver_volume(&self) {
        let client = self.client.with_timeout(Duration::from_secs(1));
        let mut query = RtspRequest::with_payload(RtspMethod::GetParameter, "/", &b"volume\r\n"[..]);
        query.set_header(HttpHeader::ContentType, "text/parameters");
        let response = match client.request(query).await {
            Ok(response) => response,
            Err(error) => { tracing::warn!(?error, "Receiver volume query unavailable; leaving gain unchanged"); return; }
        };
        if response.status != HttpStatus::Ok {
            tracing::info!(status=?response.status, "Receiver does not accept volume query; leaving gain unchanged");
            return;
        }
        let volume_db = std::str::from_utf8(&response.payload).ok().and_then(parse_volume_db);
        tracing::info!(?volume_db, "Receiver audio volume query result");
        if !volume_db.is_some_and(|db| db <= -100.0) { return; }
        let mut unmute = RtspRequest::with_payload(RtspMethod::SetParameter, "/", &b"volume: -20.000000\r\n"[..]);
        unmute.set_header(HttpHeader::ContentType, "text/parameters");
        match client.request(unmute).await {
            Ok(response) => tracing::info!(status=?response.status, requested_db=-20, "Receiver reported mute; sent quiet unmute request"),
            Err(error) => tracing::warn!(?error, "Receiver unmute request failed"),
        }
    }

    pub fn from_streams(streams: &AirPlayTransmitterBootstrapStreams) -> Result<Self> {
        Ok(Self { client: streams.client.clone().ok_or_else(|| eyre!("Missing RTSP client"))?,
            bind: streams.bind_ip, peer: streams.peer_ip, cipher: streams.cipher,
            clock: streams.media_clock.clone().ok_or_else(|| eyre!("Missing media clock"))? })
    }

    pub async fn setup(&self, config: AudioConfig, recorder: AudioRecorderBox<i16>) -> Result<MediaAudioLink> {
        let latency = Duration::from_millis(200);
        let mut bind = self.bind;
        bind.set_port(0);
        let control = UdpSocket::bind(bind).await?;
        let stream_id = u64::from_be_bytes(uuid::Uuid::new_v4().as_bytes()[..8].try_into().unwrap());
        let mut description = StreamDescriptionAudio::new(stream_id, latency, config.stream_type,
            config.format, config.audio_type, None, Some(control.local_addr()?.port()), false, false, false);
        description.spf = Some(1024);
        let setup = Setup::new(&[description.into()]);
        let response = self.client.setup(setup).await?;
        let Some(StreamDescriptionResponse::Audio(response)) = response.streams.first() else {
            return Err(eyre!("Head unit returned no audio endpoint"));
        };
        if response.stream_type != config.stream_type || response.data_port == 0 {
            return Err(eyre!("Head unit returned an invalid media endpoint"));
        }
        let mut data_peer = self.peer;
        data_peer.set_port(response.data_port);
        let control_port = response.control_port.filter(|p| *p != 0)
            .ok_or_else(|| eyre!("Head unit returned no audio control port"))?;
        let mut control_peer = self.peer;
        control_peer.set_port(control_port);
        control.connect(control_peer).await?;
        let timeline = Arc::new(AudioTimeline::default());
        let recorder = Box::new(AnchoredRecorder { inner: recorder, timeline: timeline.clone(), channels: config.channels as usize });
        let rtp = start_rtp_sender(data_peer, self.cipher, response.stream_connection_id, config, recorder)?;
        let clock = self.clock.boxed();
        let rate = config.sample_rate;
        let sync = AbortOnDropHandle::new(tokio::spawn(async move {
            if let Err(error) = publish_clock(control, clock, rate, latency, timeline).await {
                tracing::warn!(%error, "Audio clock sender stopped");
            }
        }));
        tracing::info!(?config, data_port=response.data_port, control_port, "Media audio transport opened with CarPlay stream payload and playback marker");
        Ok(MediaAudioLink { _rtp: rtp, _sync: sync,
            client: self.client.clone(), stream_type: config.stream_type })
    }
}

fn parse_volume_db(parameters: &str) -> Option<f32> {
    parameters.lines().find_map(|line| {
        let (name, value) = line.split_once(':')?;
        if !name.trim().eq_ignore_ascii_case("volume") { return None; }
        value.trim().parse::<f32>().ok().filter(|db| db.is_finite() && (-144.0..=0.0).contains(db))
    })
}

/// CarPlay uses its negotiated stream type in the RTP payload field (102 for AAC music).
pub fn start_rtp_sender(peer: SocketAddr, cipher: AirPlayStreamEncryption, stream_id: u64,
    config: AudioConfig, recorder: AudioRecorderBox<i16>) -> Result<RtpAudioSender> {
    let output = AudioStreamBasicDescription::try_from(config.format).map_err(|_| eyre!("Unsupported audio format"))?;
    let input = AudioStreamBasicDescription::fill_pcm(config.sample_rate, 16, 16, config.channels as u8, false);
    let encoder = AudioEncoderDispatch::new(input, output)?;
    let mut session = RtpSessionTx::new(cipher, stream_id, config.stream_type, encoder, recorder, false)?;
    session.start_recording();
    let (sender, _, _) = UdpHelper::connect(peer, crate::audio_rtp::CarPlayAudioSession::new(session))?;
    Ok(sender)
}

pub struct MediaAudioLink {
    _rtp: RtpAudioSender,
    _sync: AbortOnDropHandle<()>,
    client: AirPlayRtspClient,
    stream_type: StreamType,
}

impl Drop for MediaAudioLink {
    fn drop(&mut self) {
        let client = self.client.clone().with_timeout(Duration::from_millis(500));
        let stream_type = self.stream_type;
        tokio::spawn(async move { let _ = client.teardown(TeardownPayload::new(&[stream_type])).await; });
    }
}

/// Apple RTCP sync: the NTP instant maps to the RTP position less the playback latency.
pub fn sync_packet(first: bool, position: u32, latency_frames: u32, timestamp: NtpU64) -> [u8; 20] {
    let mut packet = [0; 20];
    packet[..4].copy_from_slice(&[if first { 0x90 } else { 0x80 }, 0xd4, 0, 4]);
    packet[4..8].copy_from_slice(&position.wrapping_sub(latency_frames).to_be_bytes());
    packet[8..16].copy_from_slice(&timestamp.0.to_be_bytes());
    packet[16..20].copy_from_slice(&position.to_be_bytes());
    packet
}

#[derive(Default)]
struct AudioTimeline {
    state: Mutex<(u64, Option<Instant>)>,
    ready: Notify,
}

struct AnchoredRecorder { inner: AudioRecorderBox<i16>, timeline: Arc<AudioTimeline>, channels: usize }
struct AnchoredSink { inner: AudioSinkBox<i16>, timeline: Arc<AudioTimeline>, channels: usize }
impl AudioRecorder for AnchoredRecorder {
    type Sample = i16;
    fn init(&mut self, inner: AudioSinkBox<i16>) -> catplay_carplay::rtsp_frame::RtspResult<()> {
        self.inner.init(Box::new(AnchoredSink { inner, timeline: self.timeline.clone(), channels: self.channels }))
    }
    fn start(&mut self) { self.inner.start(); }
    fn stop(&mut self, drain: bool) { self.inner.stop(drain); }
}
impl AudioSink for AnchoredSink {
    type Sample = i16;
    fn writable(&mut self) -> usize { self.inner.writable() }
    fn write(&mut self, samples: &[i16]) {
        self.inner.write(samples);
        let mut state = self.timeline.state.lock().unwrap();
        state.0 += (samples.len() / self.channels) as u64;
        state.1 = Some(Instant::now());
        drop(state);
        self.timeline.ready.notify_one();
    }
}

async fn publish_clock(socket: UdpSocket, clock: MediaClockBox, rate: u32, latency: Duration, timeline: Arc<AudioTimeline>) -> Result<()> {
    let latency_frames = (latency.as_nanos() * rate as u128 / 1_000_000_000) as u32;
    loop {
        let notified = timeline.ready.notified();
        if timeline.state.lock().unwrap().1.is_some() { break; }
        notified.await;
    }
    let mut timer = tokio::time::interval(Duration::from_millis(500));
    let mut count = 0u64;
    let mut received = 0u64;
    let mut response = [0u8; RTP_PACKET_MAX];
    loop {
        tokio::select! {
            _ = timer.tick() => {
                let (frames, when) = *timeline.state.lock().unwrap();
                let position = frames as u32;
                let Some(when) = when else { continue };
                socket.send(&sync_packet(count == 0, position, latency_frames, clock.encode_local(when))).await?;
                count += 1;
                if count == 1 || count % 10 == 0 { tracing::info!(count, position, "Audio playback clock anchor sent"); }
            }
            result = socket.recv(&mut response) => {
                let bytes = result?;
                received += 1;
                // Retain only bounded control-header diagnostics, never captured sound or keys.
                if received <= 5 || received % 100 == 0 {
                    let packet_type = response.get(1).copied().filter(|_| bytes >= 2);
                    let resend = if bytes >= 8 && packet_type == Some(0xd5) {
                        Some((u16::from_be_bytes([response[4], response[5]]),
                            u16::from_be_bytes([response[6], response[7]])))
                    } else { None };
                    tracing::info!(received, bytes, ?packet_type, ?resend, "Head unit audio control reply");
                }
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn receiver_gain_parser_rejects_unknown_invalid_and_nonfinite_values() {
        assert_eq!(parse_volume_db("volume: -144.000000\r\n"), Some(-144.0));
        assert_eq!(parse_volume_db("other: 1\r\nvolume: -12.0\r\n"), Some(-12.0));
        assert_eq!(parse_volume_db("Volume: 0\r\n"), Some(0.0));
        for invalid in ["", "unsupported", "volume: NaN", "volume: inf", "volume: 20", "volume: -145"] {
            assert_eq!(parse_volume_db(invalid), None, "{invalid}");
        }
    }
    #[test]
    fn clock_anchor_encodes_network_order_latency_and_wrap() {
        let first = sync_packet(true, 0, 8820, NtpU64(0x123456789abcdef0));
        assert_eq!(&first[..4], &[0x90, 0xd4, 0, 4]);
        assert_eq!(u32::from_be_bytes(first[4..8].try_into().unwrap()), 0u32.wrapping_sub(8820));
        assert_eq!(&first[8..16], &0x123456789abcdef0u64.to_be_bytes());
        assert_eq!(&first[16..20], &[0; 4]);
        assert_eq!(sync_packet(false, 44100, 8820, NtpU64(0))[0], 0x80);
    }

    struct TestClock(Instant);
    impl catplay_carplay::clock::MediaClock for TestClock {
        fn is_synchronized(&self) -> bool { true }
        fn encode_local(&self, at: Instant) -> NtpU64 { NtpU64::from_monotonic_nanos(at.duration_since(self.0).as_nanos() as i128) }
        fn decode_local(&self, at: NtpU64) -> Instant { self.0 + Duration::from_nanos(at.as_nanos() as u64) }
        fn decode_remote(&self, at: NtpU64) -> Option<Instant> { Some(self.decode_local(at)) }
        fn encode_remote(&self, at: Instant) -> Option<NtpU64> { Some(self.encode_local(at)) }
    }

    #[tokio::test(flavor = "current_thread")]
    async fn playback_anchor_reaches_the_negotiated_control_socket() {
        let receiver = UdpSocket::bind("127.0.0.1:0").await.unwrap();
        let sender = UdpSocket::bind("127.0.0.1:0").await.unwrap();
        sender.connect(receiver.local_addr().unwrap()).await.unwrap();
        let timeline = Arc::new(AudioTimeline::default());
        let origin = Instant::now();
        *timeline.state.lock().unwrap() = (441, Some(origin + Duration::from_millis(10)));
        let _task = AbortOnDropHandle::new(tokio::spawn(publish_clock(sender, Box::new(TestClock(origin)),
            44100, Duration::from_millis(200), timeline)));
        let mut packet = [0u8; 64];
        let (length, _) = tokio::time::timeout(Duration::from_secs(1), receiver.recv_from(&mut packet)).await.unwrap().unwrap();
        assert_eq!(length, 20);
        assert_eq!(&packet[..4], &[0x90, 0xd4, 0, 4]);
        assert_eq!(u32::from_be_bytes(packet[16..20].try_into().unwrap()), 441);
        assert_eq!(u32::from_be_bytes(packet[4..8].try_into().unwrap()), 441u32.wrapping_sub(8820));
        let timestamp = u64::from_be_bytes(packet[8..16].try_into().unwrap());
        assert_eq!(timestamp, NtpU64::from_monotonic_nanos(10_000_000).0);
    }
}
