//! Optional CarPlay media output. The platform supplies signed, interleaved PCM samples.
use catplay_carplay::audio::AudioRecorderBox;
use catplay_carplay::msg::{AudioFormat, AudioType, InfoMessageResponse, StreamType};

#[derive(Clone, Copy, Debug, PartialEq)]
pub struct AudioConfig {
    pub stream_type: StreamType,
    pub audio_type: AudioType,
    pub format: AudioFormat,
    pub sample_rate: u32,
    pub channels: u32,
}

/// Choose only a format the accessory advertises for playback, never its microphone formats.
pub fn media_config(info: &InfoMessageResponse) -> Option<AudioConfig> {
    // Keep the advertised stream and audio type paired with the format. Some receivers only
    // offer compressed music on MainHighAudio, or label their PCM stream "compatibility".
    for wanted_type in [Some(AudioType::Media), None, Some(AudioType::Compatibility), Some(AudioType::Default)] {
        for (format, sample_rate, channels) in [
            (AudioFormat::PCM_48000_STEREO, 48000, 2),
            (AudioFormat::PCM_44100_STEREO, 44100, 2),
            (AudioFormat::AAC_LC_48000_STEREO, 48000, 2),
            (AudioFormat::AAC_LC_44100_STEREO, 44100, 2),
            (AudioFormat::PCM_48000_MONO, 48000, 1),
            (AudioFormat::PCM_44100_MONO, 44100, 1),
            (AudioFormat::PCM_24000_STEREO, 24000, 2),
            (AudioFormat::PCM_24000_MONO, 24000, 1),
        ] {
            if let Some(advertised) = info.audio_formats.iter().find(|f| {
                matches!(f.stream_type, StreamType::MainAudio | StreamType::MainHighAudio)
                    && f.audio_type == wanted_type
                    && f.audio_output_formats.is_some_and(|supported| supported.contains(format))
            }) {
                return Some(AudioConfig { stream_type: advertised.stream_type,
                    audio_type: wanted_type.unwrap_or(AudioType::Media), format, sample_rate, channels });
            }
        }
    }
    None
}

/// A declared low-latency default output, kept separate from media and call formats.
pub fn compatibility_config(info: &InfoMessageResponse) -> Option<AudioConfig> {
    for (format, sample_rate) in [(AudioFormat::OPUS_24000_MONO, 24000), (AudioFormat::OPUS_16000_MONO, 16000)] {
        if info.audio_formats.iter().any(|f| f.stream_type == StreamType::MainAudio
            && f.audio_type == Some(AudioType::Default)
            && f.audio_output_formats.is_some_and(|formats| formats.contains(format))) {
            return Some(AudioConfig { stream_type: StreamType::MainAudio, audio_type: AudioType::Default,
                format, sample_rate, channels: 1 });
        }
    }
    None
}

pub struct AudioOutput {
    pub config: AudioConfig,
    pub recorder: AudioRecorderBox<i16>,
    /// Called only after audio SETUP and RECORD succeed, so a failed setup cannot mute the phone.
    pub ready: Box<dyn FnOnce() + Send>,
}

#[cfg(test)]
mod tests {
    use super::*;
    use catplay_carplay::msg::AudioFormatStruct;

    #[test]
    fn compatibility_uses_only_advertised_default_output_and_roundtrips_opus() {
        use catplay_carplay::audio::{AudioStreamBasicDescription,
            codec::{AudioEncoder, AudioEncoderFactory, AudioDecoder, AudioDecoderFactory,
                runtime::{AudioEncoderDispatch, AudioDecoderDispatch}}};
        let mut info = InfoMessageResponse::default();
        let mut format = AudioFormatStruct { stream_type: StreamType::MainAudio,
            audio_type: Some(AudioType::Telephony), audio_output_formats: Some(AudioFormat::OPUS_24000_MONO),
            ..Default::default() };
        info.audio_formats.push(format.clone());
        assert!(compatibility_config(&info).is_none(), "Calls must never be repurposed as music");
        format.audio_type = Some(AudioType::Default);
        format.audio_output_formats = None;
        format.audio_input_formats = Some(AudioFormat::OPUS_24000_MONO);
        info.audio_formats.push(format.clone());
        assert!(compatibility_config(&info).is_none(), "A microphone is not a speaker output");
        format.audio_output_formats = Some(AudioFormat::OPUS_16000_MONO | AudioFormat::OPUS_24000_MONO);
        info.audio_formats.push(format);
        let config = compatibility_config(&info).unwrap();
        assert_eq!((config.sample_rate, config.channels), (24000, 1));
        let output = AudioStreamBasicDescription::try_from(config.format).unwrap();
        let input = AudioStreamBasicDescription::fill_pcm(24000, 16, 16, 1, false);
        let mut encoder = AudioEncoderDispatch::new(input, output).unwrap();
        let mut decoder = AudioDecoderDispatch::new(output).unwrap();
        let frames = output.frames_per_packet as usize;
        let mut energy = 0u64;
        for packet in 0..20 {
            let samples: Vec<i16> = (0..frames).map(|frame|
                (4000.0 * ((packet * frames + frame) as f64 / 24000.0 * std::f64::consts::TAU * 440.0).sin()) as i16).collect();
            let mut bytes = [0u8; 1464];
            let (written, consumed) = encoder.encode(&samples, &mut bytes).unwrap();
            assert_eq!(consumed, frames);
            let mut decoded = vec![0i16; frames];
            let (decoded_count, used) = decoder.decode(&bytes[..written], &mut decoded).unwrap();
            assert_eq!((decoded_count, used), (frames, written));
            energy += decoded.iter().map(|s| (*s as i64).unsigned_abs()).sum::<u64>();
        }
        assert!(energy > 100000, "Compatibility packets must decode to audible samples");
    }

    #[test]
    fn chooses_advertised_media_playback_and_prefers_stereo() {
        let mut info = InfoMessageResponse::default();
        info.audio_formats.push(AudioFormatStruct {
            stream_type: StreamType::MainAudio,
            audio_type: Some(AudioType::Media),
            audio_output_formats: Some(AudioFormat::PCM_44100_STEREO | AudioFormat::PCM_48000_MONO),
            ..Default::default()
        });
        let config = media_config(&info).unwrap();
        assert_eq!((config.sample_rate, config.channels), (44100, 2));
    }

    #[test]
    fn microphone_and_telephony_capabilities_are_not_media_outputs() {
        let mut info = InfoMessageResponse::default();
        info.audio_formats.push(AudioFormatStruct {
            stream_type: StreamType::MainAudio,
            audio_type: Some(AudioType::Media),
            audio_input_formats: Some(AudioFormat::PCM_48000_STEREO),
            ..Default::default()
        });
        info.audio_formats.push(AudioFormatStruct {
            stream_type: StreamType::MainAudio,
            audio_type: Some(AudioType::Telephony),
            audio_output_formats: Some(AudioFormat::PCM_48000_STEREO),
            ..Default::default()
        });
        assert!(media_config(&info).is_none());
    }

    #[test]
    fn preserves_compressed_music_stream_type() {
        let mut info = InfoMessageResponse::default();
        info.audio_formats.push(AudioFormatStruct {
            stream_type: StreamType::MainHighAudio,
            audio_type: Some(AudioType::Media),
            audio_output_formats: Some(AudioFormat::AAC_LC_48000_STEREO),
            ..Default::default()
        });
        let config = media_config(&info).unwrap();
        assert_eq!(config.stream_type, StreamType::MainHighAudio);
        assert_eq!(config.format, AudioFormat::AAC_LC_48000_STEREO);
    }

    #[test]
    fn highland_aac_only_music_format_constructs_a_real_encoder() {
        use catplay_carplay::audio::{AudioStreamBasicDescription, codec::{AudioEncoderFactory, runtime::AudioEncoderDispatch}};
        let mut info = InfoMessageResponse::default();
        info.audio_formats.push(AudioFormatStruct {
            stream_type: StreamType::MainAudio,
            audio_type: Some(AudioType::Default),
            audio_output_formats: Some(AudioFormat::OPUS_16000_MONO | AudioFormat::OPUS_24000_MONO),
            ..Default::default()
        });
        info.audio_formats.push(AudioFormatStruct {
            stream_type: StreamType::MainHighAudio,
            audio_type: Some(AudioType::Media),
            audio_output_formats: Some(AudioFormat::AAC_LC_44100_STEREO),
            ..Default::default()
        });
        let config = media_config(&info).unwrap();
        assert_eq!((config.sample_rate, config.channels), (44100, 2));
        assert_eq!(config.stream_type, StreamType::MainHighAudio);
        let output = AudioStreamBasicDescription::try_from(config.format).unwrap();
        let input = AudioStreamBasicDescription::fill_pcm(config.sample_rate, 16, 16, config.channels as u8, false);
        assert!(AudioEncoderDispatch::new(input, output).is_ok(), "The Highland's actual music format must have an encoder");
    }
}
