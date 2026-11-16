# Slipstream Highland

Fork of cfraz89/carjack (Slipstream 0.3.0), for a 2027 CFMOTO U10 Pro Highland and Samsung Galaxy S25 Plus.
The owner confirmed that the Highland baseline APK connects and displays apps on the CFMOTO.
Upstream sources, MIT/Apache-2.0 licenses, and the original main branch remain available.
The Android application ID is io.github.blazinsheath.slipstream.highland.

## Version 0.3.0-highland.5: Bluetooth audio with CarPlay picture

The new Bluetooth speakers option projects only the picture. It disables CarPlay audio SETUP,
test-tone generation and REMOTE_SUBMIX capture, and explicitly leaves the MainAudio resource
with the accessory. This differs from .4's Phone speakers option, which left a silent CarPlay
audio stream open and retained its audio resource. Bluetooth is the default on a fresh install;
existing installations retain their saved selection. Select it before starting a fresh session.

After connecting the picture, select CFMOTO in Samsung's Media output and play ordinary music.
The app does not force an audio route or bypass a head unit that refuses simultaneous A2DP and
CarPlay. Whether this Highland supports the combination still requires the owner's vehicle test.
Switching between Bluetooth and CarPlay audio requires ending the session and reconnecting;
the app displays that requirement. Changes among the three CarPlay audio sources remain live.
The resource test checks that picture-only mode assigns MainAudio to the accessory while
retaining the phone's screen ownership. Screen-only teardown omits the unopened audio stream.

### CarPlay audio playback boundary

The .4 device log confirms successful live source changes, REMOTE_SUBMIX capture and sustained
audio transmission. The owner still hears neither music nor the generated tone on the Highland.
The reported playback sample position remains zero. Bluetooth audio works outside the session;
native iPhone CarPlay playback has not been tested on this vehicle.

Version .5 restores the negotiated CarPlay stream type in the RTP payload field (102 for this
Highland's AAC music), replacing .4's generic AirPlay payload 96. It also sets the playback-start
marker on the first successfully sent RTP packet. Later packets keep the marker clear. These
headers match the CarPlay sender implementation used for comparison:
https://github.com/shaonialife/cardeck/blob/main/app/src/main/java/io/github/shaonialife/cardeck/audio/AudioSender.kt
This is an interoperability correction to test, not proof that the vehicle speakers work.

The UDP integration test authenticates and decodes the real sender's marked first packet and
subsequent unmarked packets, checking stream type, sequence, timestamps and audible PCM. A
separate test ensures a zero-packet UDP send does not consume the start marker. The control
socket now logs a bounded count/type of replies and requested retransmission ranges, if any,
to distinguish incoming receiver activity from local transmission alone.

Netflix's protected video can appear black while its interface is visible. The app does not
implement protected-content capture; use ordinary music or unprotected video for audio testing.
https://help.netflix.com/en/node/11634

## Version 0.3.0-highland.4: audio transport and live controls

The .3 device log confirms that the Highland accepted AAC SETUP, supplied media/control ports,
and started the phone's test-tone producer. Its reported playback sample position stayed at zero.
A separate automated test confirmed that the .3 sender emits decryptable AAC UDP packets.
That narrows the unresolved problem to vehicle-side packet acceptance/playback; it does not
prove the vehicle is receiving or rendering those packets.

Version .4 separates the RTP audio payload number (96) from the RTSP media stream type (102),
declares 1024 AAC samples per packet, and sends RTCP playback clock anchors on the negotiated
control socket. Anchors use the actual PCM frame counter and capture instant, so changing
audio sources does not leave a wall-clock timeline running ahead of the samples. Tests receive,
authenticate and decode the actual sender's UDP packets, and validate the clock anchor on a
separate receiving UDP socket. Protocol references:
https://openairplay.github.io/airplay-spec/audio/rtp_streams.html
https://github.com/mikebrady/shairport-sync/blob/master/rtp.c

Audio choices now apply during an active audio connection. Selecting the test option again
replays its 30-second tone sequence without reconnecting. A unique capture token per audio
source prevents a stopped capture thread from closing the next one. Phone-speaker mode keeps
the vehicle audio clock alive with silence while returning phone playback to its normal output.
On session end, native code requests full RTSP TEARDOWN before the service drops Wi-Fi; a
bounded fallback still permits shutdown if the vehicle does not acknowledge it.

Physical Highland sound and reconnect behavior remain unverified until the owner's next test.
Neither .3's accepted SETUP nor the automated receiver test alone proves speaker playback.

## Version 0.3.0-highland.3: AAC encoder correction

Device testing of .2 preserved picture and touch but produced no audio. The owner's log confirms
that this Highland advertises AAC-LC 44.1 kHz stereo on MainHighAudio for music. The previous
build selected that format, but catplay's encoder dispatcher had AAC construction commented out,
so audio setup returned NotSupported locally before the head unit received any audio request.

Version .3 patches only the audio codec crate, retaining the pinned CarPlay connection code.
It implements raw AAC-LC encoding with the already-present fdk-aac dependency. Tests exercise
the real encoder/decoder dispatcher at 44.1 and 48 kHz, check both decoded channels carry audio,
and reproduce the Highland's advertised AAC-only media capabilities. The speaker test now
repeats quiet two-second bursts for 30 seconds to allow time to adjust vehicle volume.
Physical speaker playback still requires another vehicle test; this fixes the confirmed local
NotSupported failure, without claiming the complete vehicle audio path has been verified.

## CarPlay speaker audio behavior

- Claims the CarPlay MainAudio resource in addition to the working screen resource.
- Opens an advertised main media output, preferring stereo PCM at 48 kHz or 44.1 kHz;
  accepts advertised AAC music formats through the existing catplay encoder when needed.
- Uses Shizuku's shell process to capture phone output (REMOTE_SUBMIX). No microphone or
  audio files are used. PCM is forwarded through JNI to the encrypted CarPlay RTP stream.
- Starts capture only after audio SETUP and RECORD succeed, and closes capture when the session
  ends. A session token prevents an old disconnect from stopping a new session's capture.
- Adds Settings > Audio: Bluetooth speakers (default for a fresh install), Highland speakers
  (CarPlay), Phone speakers, or Speaker test tone. Bluetooth mode requires a fresh connection;
  changes between the other sources apply live to an already-open CarPlay audio stream.
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
3. In Highland settings choose Audio > Bluetooth speakers. End any other Slipstream session.
4. While parked, connect as before with Bluetooth and Wi-Fi enabled and select CarPlay on the
   CFMOTO. Open Samsung's Media output, select CFMOTO and play music. Adjust the Highland volume.
   If CFMOTO cannot connect as an audio device while CarPlay is active, this firmware may prevent
   the combination; Bluetooth-only playback outside a CarPlay session remains available.
5. To test the separate CarPlay audio path, choose Highland speakers (CarPlay), end the session
   and reconnect. Select Speaker test tone during this connection. Tone bursts repeat for
   30 seconds; selecting it again replays them. Switch back to Highland speakers (CarPlay) for
   music. These changes between CarPlay sources apply live.
6. If the tone works but music does not, use Send logs after trying the music app. If the tone
   is also silent, Send logs captures the advertised formats and audio setup error for diagnosis.
7. Confirm that ending the session restores normal phone/Bluetooth playback and that reconnecting
   starts a fresh audio stream. Audio settings apply live after the media stream has opened.

## Build

The highland-dev branch's Build Slipstream Highland workflow builds the arm64 debug APK with
upstream's containerized JDK/SDK/NDK/Rust toolchain. It also runs native tests, verifies package
identity and signature, and uploads the APK plus its SHA-256 checksum. The source lockfile pins
catplay revision 2615e14b69230d04205e8de126b6d64346787cc0.

Development releases are published from the verified workflow artifact using a temporary GitHub
Actions publishing job, then that job's workflow file is removed. Never commit private signing
keys. Persistent release signing uses HIGHLAND_KEYS containing release.jks and keystore.pass.

This is a development derivative, not an official CFMOTO, Samsung, Apple, or upstream release.
