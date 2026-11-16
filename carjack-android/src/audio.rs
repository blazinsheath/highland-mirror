//! Bounded Android PCM -> catplay's RTP producer. No audio is stored on disk.
use std::sync::{Arc, Mutex};
use std::sync::atomic::{AtomicU64, Ordering};
use catplay_carplay::audio::{AudioRecorder, AudioSinkBox};
use catplay_carplay::rtsp_frame::RtspResult;
use crate::session::Platform;

#[derive(Default)]
pub struct PlaybackAudio {
    sink: Mutex<Option<AudioSinkBox<i16>>>,
    chunks: AtomicU64,
}

impl PlaybackAudio {
    /// Android AudioRecord supplies little-endian, signed 16-bit interleaved PCM.
    pub fn push(&self, bytes: &[u8]) {
        let samples: Vec<i16> = bytes.chunks_exact(2)
            .map(|s| i16::from_le_bytes([s[0], s[1]])).collect();
        if let Some(sink) = self.sink.lock().unwrap().as_mut() {
            sink.write(&samples);
            let chunks = self.chunks.fetch_add(1, Ordering::Relaxed) + 1;
            if chunks == 1 || chunks % 500 == 0 {
                tracing::info!(chunks, samples=samples.len(), "Phone PCM delivered to CarPlay audio sender");
            }
        }
    }
}

pub struct PlaybackRecorder {
    pub audio: Arc<PlaybackAudio>,
    pub platform: Arc<dyn Platform>,
}

impl AudioRecorder for PlaybackRecorder {
    type Sample = i16;
    fn init(&mut self, sink: AudioSinkBox<i16>) -> RtspResult<()> {
        *self.audio.sink.lock().unwrap() = Some(sink);
        tracing::info!("CarPlay audio PCM producer initialized");
        Ok(())
    }
    fn start(&mut self) {} // Actual capture starts after RECORD, not during SETUP.
    fn stop(&mut self, _drain: bool) {
        self.audio.sink.lock().unwrap().take();
        self.platform.stop_audio();
    }
}

impl Drop for PlaybackRecorder {
    fn drop(&mut self) { self.stop(false); }
}

#[cfg(test)]
mod tests {
    use super::*;
    use catplay_carplay::audio::AudioSink;
    struct Sink(Arc<Mutex<Vec<i16>>>);
    impl AudioSink for Sink {
        type Sample = i16;
        fn writable(&mut self) -> usize { 100 }
        fn write(&mut self, samples: &[i16]) { self.0.lock().unwrap().extend_from_slice(samples); }
    }
    #[test]
    fn preserves_pcm_sign_endianness_and_interleaving() {
        let audio = PlaybackAudio::default();
        let captured = Arc::new(Mutex::new(Vec::new()));
        *audio.sink.lock().unwrap() = Some(Box::new(Sink(captured.clone())));
        audio.push(&[0, 128, 255, 127, 255, 255, 1, 0]);
        assert_eq!(*captured.lock().unwrap(), [-32768, 32767, -1, 1]);
        audio.sink.lock().unwrap().take();
        audio.push(&[0, 0]);
        assert_eq!(captured.lock().unwrap().len(), 4);
    }
}
