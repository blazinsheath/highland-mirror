
use crate::{
    AudioCodec, AudioStreamBasicDescription,
    codec::{AudioEncoder, AudioEncoderFactory},
};
use fdk_aac::enc::{AudioObjectType, BitRate, ChannelMode, Encoder, EncoderParams, Transport};

#[derive(thiserror::Error, Debug, Clone)]
pub enum AacEncodeError {
    #[error("AAC encoder: {0}")]
    Encoder(String),
    #[error("AAC encoder requires matching signed 16-bit interleaved PCM and AAC-LC formats")]
    UnsupportedFormat,
    #[error("AAC encoder did not produce a complete frame fitting the RTP payload")]
    IncompleteFrame,
}

pub struct AacEncoder {
    encoder: Encoder,
    input: AudioStreamBasicDescription,
    output: AudioStreamBasicDescription,
    samples_per_frame: usize,
    encoded: Vec<u8>,
}

impl AudioEncoderFactory for AacEncoder {
    type Error = AacEncodeError;

    fn new(input: AudioStreamBasicDescription, output: AudioStreamBasicDescription) -> Result<Self, Self::Error> {
        if input.format != AudioCodec::LinearPcm || input.bits_per_channel != 16
            || input.format_flags.contains(crate::AudioFormatFlags::IS_FLOAT)
            || input.format_flags.contains(crate::AudioFormatFlags::IS_NON_INTERLEAVED)
            || output.format != AudioCodec::Mpeg4Aac
            || input.sample_rate != output.sample_rate
            || input.channels_per_frame != output.channels_per_frame
            || !matches!(output.channels_per_frame, 1 | 2)
            || !matches!(output.sample_rate, 44100 | 48000)
            || output.frames_per_packet != 1024
        {
            return Err(AacEncodeError::UnsupportedFormat);
        }
        let encoder = Encoder::new(EncoderParams {
            bit_rate: BitRate::Cbr(if output.channels_per_frame == 2 { 128000 } else { 64000 }),
            sample_rate: output.sample_rate,
            transport: Transport::Raw,
            channels: if output.channels_per_frame == 2 { ChannelMode::Stereo } else { ChannelMode::Mono },
            audio_object_type: AudioObjectType::Mpeg4LowComplexity,
        }).map_err(|error| AacEncodeError::Encoder(error.to_string()))?;
        let info = encoder.info().map_err(|error| AacEncodeError::Encoder(error.to_string()))?;
        if info.frameLength != output.frames_per_packet {
            return Err(AacEncodeError::UnsupportedFormat);
        }
        let samples_per_frame = info.frameLength as usize * output.channels_per_frame as usize;
        let mut encoded = vec![0; info.maxOutBufBytes as usize];
        // FDK buffers its first input frames. Finish priming before the RTP recorder starts:
        // that recorder expects one nonempty access unit for every complete PCM input frame.
        let silence = vec![0; samples_per_frame];
        let mut primed = false;
        for _ in 0..8 {
            let result = encoder.encode(&silence, &mut encoded)
                .map_err(|error| AacEncodeError::Encoder(error.to_string()))?;
            if result.input_consumed != samples_per_frame {
                return Err(AacEncodeError::IncompleteFrame);
            }
            if result.output_size > 0 { primed = true; break; }
        }
        if !primed { return Err(AacEncodeError::IncompleteFrame); }
        Ok(Self { encoder, input, output, samples_per_frame, encoded })
    }
}

impl AudioEncoder for AacEncoder {
    type Error = AacEncodeError;
    type Sample = i16;

    fn input_type(&self) -> AudioStreamBasicDescription { self.input }
    fn output_type(&self) -> AudioStreamBasicDescription { self.output }

    fn encode(&mut self, samples: &[i16], output: &mut [u8]) -> Result<(usize, usize), Self::Error> {
        if samples.len() != self.samples_per_frame { return Err(AacEncodeError::IncompleteFrame); }
        let result = self.encoder.encode(samples, &mut self.encoded)
            .map_err(|error| AacEncodeError::Encoder(error.to_string()))?;
        if result.input_consumed != samples.len() || result.output_size == 0 || result.output_size > output.len() {
            return Err(AacEncodeError::IncompleteFrame);
        }
        output[..result.output_size].copy_from_slice(&self.encoded[..result.output_size]);
        Ok((result.output_size, result.input_consumed))
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::codec::{AudioDecoder, AudioDecoderFactory, runtime::{AudioDecoderDispatch, AudioEncoderDispatch}};

    #[test]
    fn runtime_dispatch_encodes_raw_aac_and_decodes_audible_stereo_at_both_music_rates() {
        for rate in [44100, 48000] {
            let pcm = AudioStreamBasicDescription::fill_pcm(rate, 16, 16, 2, false);
            let aac = AudioStreamBasicDescription::fill_aac_lc(rate, 2);
            let mut encoder = AudioEncoderDispatch::new(pcm, aac).expect("AAC encoding must be implemented");
            let mut decoder = AudioDecoderDispatch::new(aac).unwrap();
            // CarPlay's packet limit, minus the RTP header, AEAD tag and nonce.
            let mut payload = [0u8; 1464];
            let mut decoded = [0i16; 2048];
            let mut energy = [0u64; 2];
            for packet in 0..40 {
                let mut samples = Vec::with_capacity(2048);
                for frame in 0..1024 {
                    let t = (packet * 1024 + frame) as f64 / rate as f64;
                    samples.push((5000.0 * (t * std::f64::consts::TAU * 440.0).sin()) as i16);
                    samples.push((3000.0 * (t * std::f64::consts::TAU * 660.0).sin()) as i16);
                }
                let (bytes, consumed) = encoder.encode(&samples, &mut payload).unwrap();
                assert_eq!(consumed, 2048);
                assert!(bytes > 0 && bytes <= payload.len());
                assert_ne!(&payload[..2], &[0xff, 0xf1], "CarPlay expects raw AAC, without ADTS");
                let (written, used) = decoder.decode(&payload[..bytes], &mut decoded).unwrap();
                assert_eq!(used, bytes);
                assert_eq!(written, 2048);
                for pair in decoded[..written].chunks_exact(2) {
                    energy[0] += (pair[0] as i64).unsigned_abs();
                    energy[1] += (pair[1] as i64).unsigned_abs();
                }
            }
            assert!(energy[0] > 1_000_000 && energy[1] > 1_000_000, "Both decoded channels must carry sound");
        }
    }

    #[test]
    fn rejects_mismatched_rates_and_unimplemented_eld() {
        let pcm = AudioStreamBasicDescription::fill_pcm(48000, 16, 16, 2, false);
        assert!(AacEncoder::new(pcm, AudioStreamBasicDescription::fill_aac_lc(44100, 2)).is_err());
        assert!(!AudioEncoderDispatch::has_runtime_support(AudioCodec::Mpeg4AacEld));
    }
}
