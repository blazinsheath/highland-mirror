//! Exercise the production AAC -> RTP -> UDP path against an independent receiving socket.
use std::sync::{Arc, Mutex};
use std::time::Duration;
use catplay_carplay::{
    audio::{AudioRecorder, AudioSinkBox, AudioStreamBasicDescription,
        codec::{AudioDecoder, AudioDecoderFactory, runtime::AudioDecoderDispatch}},
    cipher::AirPlayStreamEncryption,
    msg::StreamType,
    rtp::{AsRtpPacket, RtpChaChaDecoder},
    rtsp_frame::RtspResult,
};
use tokio::net::UdpSocket;

#[derive(Clone, Default)]
struct Capture(Arc<Mutex<Option<AudioSinkBox<i16>>>>);
impl AudioRecorder for Capture {
    type Sample = i16;
    fn init(&mut self, sink: AudioSinkBox<i16>) -> RtspResult<()> {
        *self.0.lock().unwrap() = Some(sink);
        Ok(())
    }
    fn start(&mut self) {}
    fn stop(&mut self, _: bool) {}
}

#[tokio::test(flavor = "current_thread")]
async fn aac_audio_reaches_udp_peer_and_decrypts_to_sound() {
    check_audio_udp("127.0.0.1:0").await;
}

#[tokio::test(flavor = "current_thread")]
async fn aac_audio_reaches_ipv6_peer_and_decrypts_to_sound() {
    check_audio_udp("[::1]:0").await;
}

async fn check_audio_udp(bind: &str) {
    let receiver = UdpSocket::bind(bind).await.unwrap();
    let shared_secret = [0x42; 32];
    let stream_id = 13907476528139505886;
    let aac = AudioStreamBasicDescription::fill_aac_lc(44100, 2);
    let capture = Capture::default();
    let config = libcarjack::audio::AudioConfig { stream_type: StreamType::MainHighAudio,
        audio_type: catplay_carplay::msg::AudioType::Media,
        format: catplay_carplay::msg::AudioFormat::AAC_LC_44100_STEREO, sample_rate: 44100, channels: 2 };
    let _sender = libcarjack::audio_transport::start_rtp_sender(receiver.local_addr().unwrap(),
        AirPlayStreamEncryption::ChaCha { shared_secret }, stream_id, config, Box::new(capture.clone())).unwrap();
    // Queue exactly ten frames, safely below the production ring capacity.
    let samples: Vec<i16> = (0..10240).flat_map(|frame| {
        let value = (4000.0 * (frame as f64 / 44100.0 * std::f64::consts::TAU * 440.0).sin()) as i16;
        [value, value]
    }).collect();
    capture.0.lock().unwrap().as_mut().unwrap().write(&samples);
    let mut cipher = RtpChaChaDecoder::new(shared_secret, stream_id, true);
    let mut decoder = AudioDecoderDispatch::new(aac).unwrap();
    let mut energy = 0u64;
    for sequence in 0..10 {
        let mut datagram = [0u8; 1500];
        let (size, _) = tokio::time::timeout(Duration::from_secs(2), receiver.recv_from(&mut datagram))
            .await.expect("The production sender must actually emit UDP audio").unwrap();
        let packet = cipher.decode_rtp_payload(&mut datagram[..size]).expect("Receiver must authenticate the sender's packet");
        assert_eq!(packet.header().sequence_number, sequence);
        assert_eq!(packet.header().timestamp, sequence as u32 * 1024);
        assert_eq!(packet.header().payload_type, 102, "CarPlay music uses the negotiated stream payload type");
        assert_eq!(packet.header().marker, sequence == 0, "Only the first packet marks the playback boundary");
        let mut decoded = [0i16; 2048];
        let (written, consumed) = decoder.decode(packet.payload(), &mut decoded).unwrap();
        assert_eq!(consumed, packet.payload().len());
        assert_eq!(written, decoded.len());
        energy += decoded.iter().map(|&s| (s as i64).unsigned_abs()).sum::<u64>();
    }
    assert!(energy > 1_000_000, "The received AAC packets must contain audible samples");
}
