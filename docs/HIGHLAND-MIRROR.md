# Highland Mirror (preview)

A separate Android APK that mirrors the phone's **actual screen** over the CFMOTO's wireless CarPlay connection, without Shizuku, ADB, Wireless debugging, root or a helper APK. Its new Android application ID, `io.github.blazinsheath.highlandmirror`, lets it coexist with the working Slipstream Highland.

This is a new public-API Android frontend over the existing open-source CarPlay transport, not an independent rewrite of the protocol. The upstream copyright and license files remain applicable and included in this repository. The working Highland branch and releases are preserved.

## Install and connect

1. Install the **Highland Mirror** APK (Android 14+, ARM64). End the old Slipstream session before using it.
2. Turn on the phone's Bluetooth and Wi-Fi. Enter the **phone's Bluetooth address** from Samsung Settings > About phone > Status information. This is private configuration saved on the phone; Android does not expose the real address to ordinary apps, and this head unit needs it during CarPlay availability negotiation.
3. Pair through Android Bluetooth settings and use **Choose paired head unit** to select the CFMOTO. Allow Nearby devices. Audio permission is needed to copy eligible media playback; this does not open the microphone.
4. Optionally enable **Highland Mirror touch control** in Android Accessibility settings after reviewing its disclosure. It replays vehicle taps and one-finger swipes on the phone while projection is attached, with Back/Home support. It does not retrieve window content, inspect text or unlock the phone. Swipes replay on finger release; multi-touch and arbitrary keyboard injection are not implemented.
5. Tap **Start mirroring** and approve Android screen sharing. Stay in the app while the vehicle connects, accept Android's Wi-Fi connection prompt, then open the phone's Home screen or another app. Rotate the phone for a landscape picture; aspect ratio is retained.
6. Keep the phone unlocked. Locking or revoking screen sharing stops the service; a fresh Start needs fresh system approval. Stop lets native CarPlay teardown run before dropping Wi-Fi. No token is cached or reused for a new projection.

## Audio and limitations

- Uses the verified CarPlay media transport: first video/RECORD and active-session MainAudio claim, then AAC stereo. This keeps the audio startup fix that worked on the owner's Highland.
- **CarPlay speakers** copies eligible media with Android `AudioPlaybackCaptureConfiguration`. **Phone only** stops capture and sends silence to keep the CarPlay stream alive. Switching does not require reconnecting. Public playback capture copies audio; local phone playback may remain audible.
- Apps may prohibit audio capture. Cellular call audio and microphone return are not supported. No claim of hands-free calling is made.
- Protected video such as Netflix can remain black. No DRM workaround is included.
- Uses Android's approved `WifiNetworkSpecifier` connection. It cannot silently cycle Bluetooth, force the primary Wi-Fi network, or read the interface's real MAC like the old privileged helper. Leave the Wi-Fi MAC override blank initially; if the head unit rejects Wi-Fi setup, the optional override must match the phone's connected-network MAC. Never hardcode device addresses in source.
- **Not yet tested on the physical Highland.** Compilation/tests cannot establish that its Wi-Fi handover accepts an unprivileged client. Keep the working Slipstream APK available during testing.
- No mirrored screenshots, video samples or audio are saved. **Share diagnostic log** exports private app logs with connection details only at the user's request. Logs can contain device/network identifiers; review before sharing publicly.

## Build

Use the existing Android toolchain container and run `gradle :highlandmirror:testDebugUnitTest :highlandmirror:assembleDebug`. The module selects only shared transport/encoder sources plus `app/src-mirror`. It has no Shizuku/Sentry/launcher dependency or provider. Geometry tests cover portrait/landscape touch mapping, letterbox rejection, malformed coordinates and MAC validation. CI checks manifest/dependency separation and the APK's package/version/signature.

Android references: [MediaProjection](https://developer.android.com/media/grow/media-projection), [playback capture](https://developer.android.com/media/platform/av-capture), [nearby Wi-Fi permission](https://developer.android.com/develop/connectivity/wifi/wifi-permissions).
