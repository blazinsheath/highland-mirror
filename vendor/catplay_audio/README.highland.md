# Highland AAC encoder patch

This directory preserves `carplay/catplay_audio` from cfraz89/catplay revision
`2615e14b69230d04205e8de126b6d64346787cc0`:
https://github.com/cfraz89/catplay/tree/2615e14b69230d04205e8de126b6d64346787cc0/carplay/catplay_audio

The upstream crate is already a dependency of Slipstream. Its encoder dispatcher advertised
AAC support but never constructed an AAC encoder, and `aac_encoder.rs` was empty. The Highland
requires AAC-LC 44.1 kHz stereo for music; device logs showed `Audio SETUP ... NotSupported`
before any audio SETUP request was sent.

Changes here add a raw AAC-LC encoder using the existing fdk-aac 0.8.0 dependency, connect the
encoder dispatcher, report the implemented codecs accurately, and test encoding/decoding through
that dispatcher. Raw access units (without ADTS headers) and 1024 samples per channel match
the CarPlay stream format. Encoder priming is completed before RTP starts, so each packet can
carry a complete AAC frame. No underlying CarPlay connection or encryption code is changed.

Other crate source is retained from upstream; `catplay_ring` remains pinned by the root lockfile.
The added encoder and tests are part of the Highland changes. The original project notices
remain with their original sources. The existing fdk-aac and libfdk-aac notices and licenses
continue to apply to those dependencies.
