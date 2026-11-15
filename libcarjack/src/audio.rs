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
}
