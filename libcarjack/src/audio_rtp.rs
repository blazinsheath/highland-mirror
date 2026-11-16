//! CarPlay RTP framing around the pinned catplay codec/encryption implementation.
use std::{cell::Cell, net::SocketAddr};
use catplay_carplay::{
    audio::codec::runtime::AudioEncoderDispatch,
    rtp::record::RtpSessionTx,
    rtsp_frame::{RtspError, RtspResult},
};
use catplay_tokio::{UdpSession, UdpSocketPeer};
use catplay_util::EventSleeper;

type EncoderSession = RtpSessionTx<AudioEncoderDispatch>;

#[derive(EventSleeper)]
pub struct CarPlayAudioSession {
    #[sleep]
    inner: EncoderSession,
    first: Cell<bool>,
}

impl CarPlayAudioSession {
    pub fn new(inner: EncoderSession) -> Self { Self { inner, first: Cell::new(true) } }
}

// Modern CarPlay authenticates timestamp + SSRC (bytes 4..12), so setting the marker
// on the already-sealed header preserves the authentication tag and nonce. The inner
// session uses the negotiated CarPlay stream type as its seven-bit payload type.
struct MarkFirst<'a> {
    peer: &'a dyn UdpSocketPeer<CarPlayAudioSession>,
    first: &'a Cell<bool>,
}

impl UdpSocketPeer<EncoderSession> for MarkFirst<'_> {
    fn send(&self, data: &[u8]) -> RtspResult<()> {
        if !self.first.get() { return self.peer.send(data); }
        let marked = mark_start(data)?;
        self.peer.send(&marked)?;
        self.first.set(false);
        tracing::info!(payload_type=marked[1] & 0x7f, "First CarPlay audio packet sent with playback marker");
        Ok(())
    }

    fn send_multiple(&self, data: &[&[u8]]) -> RtspResult<usize> {
        if !self.first.get() || data.is_empty() { return self.peer.send_multiple(data); }
        let marked = mark_start(data[0])?;
        let mut batch = Vec::with_capacity(data.len());
        batch.push(marked.as_slice());
        batch.extend_from_slice(&data[1..]);
        let sent = self.peer.send_multiple(&batch)?;
        if sent > 0 {
            self.first.set(false);
            tracing::info!(payload_type=marked[1] & 0x7f, "First CarPlay audio packet sent with playback marker");
        }
        Ok(sent)
    }
}

fn mark_start(data: &[u8]) -> RtspResult<Vec<u8>> {
    if data.len() < 12 { return Err(RtspError::PayloadTooShort(data.len(), 12)); }
    let mut marked = data.to_vec();
    marked[1] |= 0x80;
    Ok(marked)
}

impl UdpSession for CarPlayAudioSession {
    type Error = RtspError;
    fn on_datagram(&mut self, data: &mut [u8], peer: SocketAddr, sink: &dyn UdpSocketPeer<Self>) -> RtspResult<()> {
        self.inner.on_datagram(data, peer, &MarkFirst { peer: sink, first: &self.first })
    }
    fn on_connect(&mut self, peer: SocketAddr, sink: &dyn UdpSocketPeer<Self>) -> RtspResult<()> {
        self.inner.on_connect(peer, &MarkFirst { peer: sink, first: &self.first })
    }
    fn on_eof(&mut self, error: Option<RtspError>) { self.inner.on_eof(error); }
    fn reconcile(&mut self, peer: Option<&dyn UdpSocketPeer<Self>>) -> RtspResult<()> {
        match peer {
            Some(peer) => self.inner.reconcile(Some(&MarkFirst { peer, first: &self.first })),
            None => self.inner.reconcile(None),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::cell::RefCell;

    #[derive(Default)]
    struct Peer { packets: RefCell<Vec<Vec<u8>>>, accept: Cell<usize> }
    impl UdpSocketPeer<CarPlayAudioSession> for Peer {
        fn send(&self, data: &[u8]) -> RtspResult<()> { self.packets.borrow_mut().push(data.to_vec()); Ok(()) }
        fn send_multiple(&self, data: &[&[u8]]) -> RtspResult<usize> {
            let count = data.len().min(self.accept.get());
            self.packets.borrow_mut().extend(data[..count].iter().map(|p| p.to_vec()));
            Ok(count)
        }
    }

    #[test]
    fn marker_is_retained_until_first_success_and_only_changes_header_bit() {
        let peer = Peer::default();
        let first = Cell::new(true);
        let adapter = MarkFirst { peer: &peer, first: &first };
        let mut original = [0x42; 64]; original[0] = 0x80; original[1] = 102;
        assert_eq!(adapter.send_multiple(&[&original]).unwrap(), 0);
        assert!(first.get(), "An unsent packet cannot consume the playback marker");
        peer.accept.set(2);
        assert_eq!(adapter.send_multiple(&[&original, &original]).unwrap(), 2);
        adapter.send(&original).unwrap();
        let packets = peer.packets.borrow();
        let mut expected = original; expected[1] |= 0x80;
        assert_eq!(packets[0], expected);
        assert_eq!(packets[1], original);
        assert_eq!(packets[2], original);
    }
}
