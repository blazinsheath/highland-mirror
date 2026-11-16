# Slipstream Highland

Fork of cfraz89/carjack (Slipstream 0.3.0), for a 2027 CFMOTO U10 Pro Highland and Samsung Galaxy S25 Plus.
The owner confirmed that the Highland baseline APK connects and displays apps on the CFMOTO.
Upstream sources, MIT/Apache-2.0 licenses, and the original main branch remain available.
The Android application ID is io.github.blazinsheath.slipstream.highland.

## Version 0.3.0-highland.7: read-only cellular-call support check

The owner confirmed that both .6 test tones and both normal/compatibility music routes play
through the Highland speakers. The new log shows the AAC playback sample counter advancing
(for example from 58368 to millions of frames). The receiver returned NotImplemented to the
volume query, so the app did not issue an unmute request. This points to the active-session
startup change as the effective correction. The .6 release remains the verified media baseline.

The outgoing cellular call returned to the phone. Media capture is not a bidirectional call
bridge: the current app does not open a CarPlay telephony stream or inject the vehicle microphone
into the cellular uplink. Android exposes system call redirection APIs, but support depends on
the device audio system. Shizuku runs as shell, whose AOSP manifest requests CALL_AUDIO_INTERCEPTION;
Samsung's actual capability still needs checking on this phone.

Version .7 adds only an asynchronous, read-only Shizuku diagnostic. It logs whether the call
downlink/uplink API methods exist and asks IAudioService.isPstnCallAudioInterceptable(). It does
not open call capture, create an injection track, place calls or change audio routing. All .6
media negotiation, video, capture and codec paths are retained. Helper version 45 ensures the
new diagnostic runs in the updated process. After starting .7 and playing music, export logs;
another call is not required for this capability check. A true capability result is a prerequisite
for testing the cellular bridge, not proof that calls already work.

Primary references:
https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/media/java/android/media/AudioManager.java
https://github.com/aosp-mirror/platform_frameworks_base/blob/main/packages/Shell/AndroidManifest.xml

Netflix's black video is consistent with protected surfaces being blanked on the app's
non-secure virtual display. The capture-to-encoder path is not a protected DRM output path;
changing the CarPlay audio stream does not provide one. Netflix says Android screen mirroring
may be unsupported. No supported Netflix-video fix is included or promised in this build.
https://developer.android.com/reference/android/hardware/display/DisplayManager#VIRTUAL_DISPLAY_FLAG_SECURE
https://help.netflix.com/en/node/11634

## Version 0.3.0-highland.6: start audio on the active CarPlay session

The owner's fresh .5 test used Speaker test tone from connection start. The log confirms
MainAudio ownership, accepted AAC SETUP, payload 102 with the first-packet marker, and continuous
RTP/clock publication. The later Highland speakers selection started phone capture successfully.
The vehicle was silent and its reported playback sample position stayed zero. There were no
incoming audio-control replies in the log. None of those local send logs proves packet receipt.

Version .6 sends RECORD and the first video frame before opening audio, reasserts MainAudio
ownership on that active session, then negotiates audio in a separate task so video is not
blocked by audio SETUP. The audio task retains its stream guard until the video session ends.
Capture/tone generation starts only after audio setup succeeds. This tests startup ordering;
physical Highland playback is not established by software checks.

The active RTSP connection also gets a one-second receiver-volume query. An unsupported,
missing or invalid result leaves gain unchanged. Only an explicitly reported mute (at most
-100 dB) sends a quiet -20 dB unmute request. Response status and parsed gain are logged. The
pinned catplay_carplay package is vendored with one additional request-forwarding method;
pairing, video, encryption and packet framing remain the upstream implementations. See its
README.highland.md for provenance. Volume protocol reference:
https://openairplay.github.io/airplay-spec/audio/volume_control.html

Tests now receive/decrypt/decode the production AAC sender over both IPv4 and IPv6 UDP, because
the Highland uses IPv6 link-local transport. This verifies the software socket path, not the
vehicle or Android network. Gain-parser tests reject malformed/nonfinite/out-of-range values.

CarPlay speakers is again the default on fresh installs; saved settings are retained. The .5
Bluetooth test showed the head unit terminating its Bluetooth link after CarPlay began, even
with no CarPlay audio stream. Bluetooth was not a working simultaneous audio route on this
vehicle. Select Speaker test tone before a fresh .6 connection, then Highland speakers (CarPlay)
for music during the same connection. If silent, export a new log after that test.

The same APK also offers Highland speakers (compatibility) and Speaker test tone (compatibility).
These choose only an advertised MainAudio/default Opus output, preferring 24 kHz mono and then
16 kHz mono. Microphone-only and telephony formats are excluded. The runtime encoder and decoder
are tested with actual tone samples at the selected rate. The compatibility path uses upstream's
low-latency audio setup rather than the custom AAC MainHighAudio transport. Its narrower mono
sound is a fallback to test, not an established fix. Changing between normal, compatibility and
Bluetooth routes requires a fresh connection; switching tone/music within one route stays live.
Teardown uses the actually selected stream type. The Kotlin/JNI audio-profile parameter is updated
together in this APK (0=Bluetooth/picture only, 1=normal media, 2=compatibility).

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
- Adds Settings > Audio: Bluetooth speakers, Highland speakers (CarPlay, default on a fresh .6
  install), Phone speakers, or Speaker test tone. Bluetooth mode requires a fresh connection;
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
   so the updated helper (version 45) is loaded.
3. In Highland settings choose Audio > Speaker test tone. End any other Slipstream session.
4. While parked, connect as before with Bluetooth and Wi-Fi enabled and select CarPlay on the
   CFMOTO. Adjust its media volume and listen for 30 seconds. Selecting Speaker test tone again
   replays the sequence. This connection uses CarPlay audio; normal Bluetooth media is not used.
5. Select Highland speakers (CarPlay) during the same connection and play ordinary music.
   These changes between CarPlay sources apply live. Bluetooth mode is retained for other
   configurations but did not work alongside projection on the owner's Highland.
6. If the tone works but music does not, use Send logs after trying the music app. If the tone
   is silent, end the session, choose Speaker test tone (compatibility), and start a fresh
   connection. If that works, choose Highland speakers (compatibility) for music in the same
   session. Export a log after both routes if silent; it includes route, gain and setup status.
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
