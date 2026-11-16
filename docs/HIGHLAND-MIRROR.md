# Highland Mirror (preview 3)

One APK for Samsung/Android phone mirroring to the CFMOTO U10 Pro Highland over wireless
CarPlay. It includes the customizable Slipstream launcher and an owner-paired shell helper.
**There is no separate Shizuku or helper app to install.** This architecture was explicitly
selected to recover the audio capture and automatic phone identity used in working Slipstream.

## Setup

1. Install the signed update over Highland Mirror preview 2 to retain setup and app shortcuts.
   **[Direct preview 3 APK](https://github.com/blazinsheath/slipstream-highland/releases/download/v0.1.0-highland-mirror-preview.3/Highland-Mirror-0.1.0-preview.3.apk)**.
   APK SHA-256: `c040b8bbe8873d499a8dff585a0ccdc0c62eeaa96aba9b17469331aabf18da2f`.
2. End the old Slipstream session. Connect the phone to Wi-Fi. Open Highland Mirror and choose
   **Set up built-in helper**. If necessary enable Samsung Developer options by tapping
   About phone > Software information > Build number seven times.
3. Open Developer options > Wireless debugging, enable it, and choose **Pair device with
   pairing code**. Keep the dialog open, pull down notifications and reply to Highland Mirror's
   notification with its six-digit code. The app discovers the local pairing port automatically.
   Return to the app and tap Start. If notifications are disabled or discovery is unavailable,
   **Settings & help > Pairing code > manual fallback** accepts the pairing port/code while
   Android's dialog stays visible in split screen. No phone Bluetooth address is typed.
4. Pairing keys stay in this app's private, non-backed-up storage. Pairing is saved, but Android
   may disable Wireless debugging after restarting or changing networks; enable it again when
   asked. The helper needs debugging only to start. PCM and lifetime control use authenticated
   local Unix sockets, so they do not depend on the ADB connection during vehicle Wi-Fi handover.
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

## Audio and connection

Preview 2's new device log reached RECORD, video and continuous audio RTP transmission, but
music stayed on the phone. Its native audio transport is unchanged from physically working
Slipstream Highland .6. The capture backend was different: ordinary AudioPlaybackCapture
can filter media and does not force phone output away from the local speaker.

Preview 3 shares the exact shell-UID PlaybackCapture source used in that working fork:
REMOTE_SUBMIX, shell attribution, PCM16 and the negotiated sample rate/channel count. The
helper is code inside this APK, launched with app_process after authorized same-device ADB
pairing. Both ends verify Unix peer credentials; a randomized abstract socket has no network
listener. Closing the owner connection releases capture and exits the helper.

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

Validation: [41 native + 16 Android tests passed](https://github.com/blazinsheath/slipstream-highland/actions/runs/43891547996).
Package/version and permission-boundary checks passed. All four arm64 native libraries and
APK offsets support 16-KB pages. Android apksigner independently verifies the final release
signature. These checks do not replace a physical pairing/audio/touch test.
Release source: `5d77c92011cde79b2782fe9b23a05aa0b0f12b8a`. The release also supplies the
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
