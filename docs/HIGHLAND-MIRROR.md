# Highland Mirror (preview 5)

One APK for Samsung/Android phone mirroring to the CFMOTO U10 Pro Highland over wireless
CarPlay. It includes the customizable Slipstream launcher and an owner-paired shell helper.
**There is no separate Shizuku or helper app to install.** This architecture was explicitly
selected to recover the audio capture and automatic phone identity used in working Slipstream.

## Setup

1. Install the signed update over the current Highland Mirror app to retain pairing, setup
   and shortcuts. **[Direct preview 5 APK](https://github.com/blazinsheath/slipstream-highland/releases/download/v0.1.0-highland-mirror-preview.5/Highland-Mirror-0.1.0-preview.5.apk)**.
   APK SHA-256: `f1139b545d18aab01949561ea364af780fe1442c3dc0188d0b3b9326689ffe91`.
   **Already paired? Tap Start. Do not uninstall or clear app data.** Enable Wireless debugging
   if Android turned it off. A new code is only needed for missing or rejected pairing.
2. **First-time setup only:** end the old Slipstream session and connect the phone to Wi-Fi.
   Open Highland Mirror and choose
   **Set up built-in helper**. If necessary enable Samsung Developer options by tapping
   About phone > Software information > Build number seven times.
3. Open Developer options > Wireless debugging, enable it, and choose **Pair device with
   pairing code**. Keep the dialog open, pull down notifications and reply to Highland Mirror's
   notification with its six-digit code. The app discovers the local pairing port automatically.
   Pairing success is reported separately; return to the app and tap Start. If notifications are disabled,
   **Settings & help > Pairing code > manual fallback** accepts the pairing port/code while
   Android's dialog stays visible in split screen. No phone Bluetooth address is typed.
4. Pairing keys stay in this app's private, non-backed-up storage. Pairing is saved, but Android
   may disable Wireless debugging after restarting or changing networks; enable it again when
   asked. The helper needs debugging only to start. PCM and lifetime control use authenticated
   loopback TCP sockets with per-launch mutual HMAC authentication, so they do not depend on
   the ADB connection during vehicle Wi-Fi handover. The listener binds only 127.0.0.1.
5. Tap Start, approve Android screen sharing and accept the vehicle Wi-Fi request. A sole paired
   CFMOTO is selected automatically. The vehicle's supplied Wi-Fi password takes priority;
   CFMOTO networks with no supplied credential use 12345678. The phone's actual Bluetooth
   identity is queried by the helper before the vehicle connection begins.
6. Enable vehicle touch control in Accessibility to operate supported phone apps and use
   Back/Home on the Highland. It replays one-finger taps/swipes; swipes dispatch on release.
   It does not retrieve accessibility window content or unlock the phone. Sideloaded-app
   restrictions may require App info > menu > Allow restricted settings first.
7. Keep the phone unlocked and its screen on. The current MediaProjection backend stops when
   the phone locks. Physical-display-off operation is not implemented or verified. The home page
   uses landscape; other apps follow their phone orientation. Open apps change the phone too.

## Helper readiness correction in preview 5

The new preview-4 log confirms three attempts passed identity loading and the debugging
connection, then failed at LAUNCH while Kotlin readBytes was draining the ADB stream. The
trace reaches libadb 3.1.1 AdbStream.read, which can throw IOException for a closed stream.
Startup never reached LINK, so the error was not another pairing-code problem.

The app now reads only a fixed marker printed by the actual helper after it binds its
loopback listener. It does not drain to EOF. The temporary parent command remains alive
while the helper starts and detaches; the launch stream is retained until the app verifies
the authenticated HELLO and phone identity. Only then are the launch stream and ADB connection
closed. A closed stream before readiness remains a real startup failure; exceptions are not
silently treated as success. Bounded startup output is sanitized into diagnostics, and the
helper redirects inherited stdout/stderr after sending readiness so a closed launch pipe
cannot break later playback. Concurrent incoming helper connections are bounded.

Android AudioRecord also resolves its default audio session through Context.getDeviceId().
The standalone helper now supplies a base with DEVICE_ID_DEFAULT instead of passing a null
base into the capture wrapper. Shell attribution and the proven capture implementation are
unchanged.

The log pinpoints the app-side early abort but does not prove the previous helper had already
started successfully. The readiness change addresses both that false-failure path and early
parent-termination timing. A fresh hardware test remains required.

## Pairing-loop correction in preview 4

The owner confirmed this app was already listed under Android's paired devices. Preview 3
incorrectly sent every helper startup failure back to the pairing guide and combined pairing
and startup under one error message. A failed connection discovery, helper launch or local
connection must not request another pairing code.

Preview 4 preserves saved pairing on these failures, gives stage-specific recovery, and offers
Retry or a fallback debugging connection port from the main Wireless debugging screen. That
connection port differs from the temporary pairing dialog's port. It tries the current TLS
port property where accessible, saved ports and then NSD discovery automatically.

Preview 3 also attempted an app-to-shell abstract Unix connection; stock Android policy does
not grant that cross-domain connection. The helper now uses mutually authenticated loopback
TCP. Only a peer proving the per-launch secret receives phone identity or audio. No listener
is reachable from the vehicle network. There is no additional app or permission to install.

The owner's new ZIP had a preview-3 info header but only older preview-2 detailed logs: logging
was initialized only after the CarPlay service started, hiding setup failures. Logging now
starts with the app, and setup stage/error classes plus bounded sanitized stack traces are
saved before a vehicle session exists. A new code is requested only for missing/rejected
pairing or an explicit Pair again action. The precise preview-3 failing step was not present
in that ZIP; hardware verification of the corrections is still required.

## Audio and connection

Preview 2's new device log reached RECORD, video and continuous audio RTP transmission, but
music stayed on the phone. Its native audio transport is unchanged from physically working
Slipstream Highland .6. The capture backend was different: ordinary AudioPlaybackCapture
can filter media and does not force phone output away from the local speaker.

Preview 3 shares the exact shell-UID PlaybackCapture source used in that working fork:
REMOTE_SUBMIX, shell attribution, PCM16 and the negotiated sample rate/channel count. The
helper is code inside this APK, launched with app_process after authorized same-device ADB
pairing. The listener binds only 127.0.0.1, with mutual challenge/response authentication using
an unpredictable per-launch secret. The secret and pairing PIN are never persisted or logged.
Closing the owner connection releases capture and exits the helper.

**Highland speakers / Phone** switches live without ending CarPlay. Phone closes the output
capture and feeds silence to keep the vehicle stream alive. Numeric PCM peak levels are logged
for diagnosis; audio samples, mirrored screenshots and video are never saved. The native AAC
stereo startup/ownership sequence remains the previously verified transport. New helper/audio
behavior still requires a physical S25 Plus/Highland test.

Protected Netflix frames, cellular-call audio, microphone capture and bidirectional hands-free
calling are not implemented. A helper does not promise protected-video playback.

## Build, dependencies and signing

Build with `gradle :highlandmirror:testDebugUnitTest :highlandmirror:assembleDebug` using the
repository's Android toolchain container. Only selected common transport files and the known
PlaybackCapture implementation are shared. No Shizuku manager/provider classes are included.
The manifest requests no system capture, input-injection, log-reading or secure-settings
permission. Accessibility remains gesture-only, with window retrieval disabled.

New pairing dependencies are pinned: LibADB Android 3.1.1 (Apache-2.0 option), Conscrypt Android
2.5.3, Bouncy Castle 1.81, and transitive SPAKE2 Android 2.2.1 (LGPL-3.0). License texts,
source URLs and replacement/rebuild instructions are bundled under Settings & help >
Open-source licenses and in app/mirror/assets/licenses. Upstream source/history/notices remain.

Validation: [41 native + 31 Android tests passed](https://github.com/blazinsheath/slipstream-highland/actions/runs/43915476974),
including six new readiness/closed-stream/bounded-output/redaction regressions. Existing
loopback authentication, exact PCM and pairing-recovery tests passed. Package/version and
permission checks passed; all four arm64 libraries and APK offsets support 16-KB pages.
Android apksigner independently verifies the final persistent release signature. Physical
phone/vehicle startup, audio and touch still require confirmation.
Release source: `ef8a920af010b613dc2cf4b40167e54002ebdc13`. The release also supplies the
unmodified LGPL SPAKE2 source and exact native submodule for replacement/rebuilding.

Official updates retain release certificate SHA-256:
`d730db78546f3d88015cf8ef7630d3fc81f49a0f7bd4c53e87348c98c2f6cee9`.
The private key is not included in GitHub or build jobs. Android apksigner independently
checks the final published signature. Temporary publisher workflows are removed after release.

References: [MediaProjection and lock termination](https://developer.android.com/media/grow/media-projection#resource_recovery),
[REMOTE_SUBMIX](https://developer.android.com/reference/kotlin/android/media/MediaRecorder.AudioSource#REMOTE_SUBMIX),
[LibADB Android](https://github.com/MuntashirAkon/libadb-android/tree/3.1.1).

## Netflix research

The owner reports Netflix works to a TV through Samsung Smart View. That is consistent with a protected TV-display link, not ordinary MediaProjection capture. [Android secure windows](https://developer.android.com/reference/android/view/WindowManager.LayoutParams#FLAG_SECURE) restrict screen sharing of protected content. [Netflix's supported connection guide](https://help.netflix.com/en/node/49) distinguishes casting, mirroring and video cables.

No verified phone-only Netflix fix was found for this stock Samsung/CarPlay capture path. A separate device that runs Netflix itself is a possible alternative. [Ottocast's i3 page](https://www.ottocast.com/products/ottoaibox-i3) lists a **Ford/Hyundai & more** variant for factory wireless CarPlay and says Netflix runs on the box. CFMOTO is not on its published compatibility list; Highland support is **unconfirmed** and needs manufacturer confirmation. A box requiring wired CarPlay does not match the reported wireless-only setup. No vendor message or adapter purchase was made.
