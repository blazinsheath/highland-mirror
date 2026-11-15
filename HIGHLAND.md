# Slipstream Highland

Fork of cfraz89/carjack (Slipstream 0.3.0), for a 2027 CFMOTO U10 Pro Highland and Samsung Galaxy S25 Plus.
The owner confirmed that the Highland baseline APK connects and displays apps on the CFMOTO.
Upstream sources, MIT/Apache-2.0 licenses, and the original main branch remain available.
The Android application ID is io.github.blazinsheath.slipstream.highland.

## Version 0.3.0-highland.2: CarPlay speaker audio

- Claims the CarPlay MainAudio resource in addition to the working screen resource.
- Opens an advertised main media output, preferring stereo PCM at 48 kHz or 44.1 kHz;
  accepts advertised AAC music formats through the existing catplay encoder when needed.
- Uses Shizuku's shell process to capture phone output (REMOTE_SUBMIX). No microphone or
  audio files are used. PCM is forwarded through JNI to the encrypted CarPlay RTP stream.
- Starts capture only after audio SETUP and RECORD succeed, and closes capture when the session
  ends. A session token prevents an old disconnect from stopping a new session's capture.
- Adds Settings > Audio: Highland speakers (default), Phone speakers (audio forwarding off),
  or Speaker test tone (a quiet two-second 440 Hz tone on the next connection).
- Retains the video-only path if the head unit does not advertise a supported audio format.
- Calls, the vehicle microphone, and genuine Android Auto projection are not implemented.

Build and native tests can verify compilation, advertised format selection and PCM handling.
Playback through the physical Highland speakers still requires the owner's device test.
Protected media and app/firmware capture policies may prevent some sources from being forwarded.
The REMOTE_SUBMIX approach follows Android's framework API and the approach documented by scrcpy:
https://github.com/Genymobile/scrcpy/blob/master/doc/audio.md

## Install and test

1. Install the new APK. These development APKs use runner-generated debug keys; if Android says
   the update conflicts with the installed package, uninstall the earlier Highland copy first.
   Uninstalling clears that copy's settings. The original upstream Slipstream can stay installed.
2. Start Shizuku and authorize Slipstream Highland. Restart Shizuku after replacing the APK
   so the updated helper (version 44) is loaded.
3. In Highland settings choose Audio > Highland speakers. End any other Slipstream session.
4. While parked, connect as before with Bluetooth and Wi-Fi enabled and select CarPlay on the
   CFMOTO. Play music, then adjust the Highland's media volume while it is playing.
5. If silent, select Audio > Speaker test tone, end the session, and reconnect. A short quiet
   tone should play on the vehicle. Switch back to Highland speakers and reconnect for music.
6. If the tone works but music does not, use Send logs after trying the music app. If the tone
   is also silent, Send logs captures the advertised formats and audio setup error for diagnosis.
7. Confirm that ending the session restores normal phone/Bluetooth playback and that reconnecting
   starts a fresh audio stream. Settings changes take effect on the next session.

## Build

The highland-dev branch's Build Slipstream Highland workflow builds the arm64 debug APK with
upstream's containerized JDK/SDK/NDK/Rust toolchain. It also runs native tests, verifies package
identity and signature, and uploads the APK plus its SHA-256 checksum. The source lockfile pins
catplay revision 2615e14b69230d04205e8de126b6d64346787cc0.

Development releases are published from the verified workflow artifact using a temporary GitHub
Actions publishing job, then that job's workflow file is removed. Never commit private signing
keys. Persistent release signing uses HIGHLAND_KEYS containing release.jks and keystore.pass.

This is a development derivative, not an official CFMOTO, Samsung, Apple, or upstream release.
