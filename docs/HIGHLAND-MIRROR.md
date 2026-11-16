# Highland Mirror (preview 2)

Android screen mirroring to the CFMOTO U10 Pro Highland over wireless CarPlay, with Slipstream's customizable app home page. No Shizuku, ADB, Wireless debugging, root or helper APK. The separate application ID `io.github.blazinsheath.highlandmirror` allows the working Slipstream Highland app to stay installed.

**[Direct APK download: 0.1.0-preview.2](https://github.com/blazinsheath/slipstream-highland/releases/download/v0.1.0-highland-mirror-preview.2/Highland-Mirror-0.1.0-preview.2.apk)** · [Build and tests](https://github.com/blazinsheath/slipstream-highland/actions/runs/43879269981)

APK SHA-256: `625ade2db8d91cab6489f5ced23cf2b23aeee62b1fe31d4a7b097f9376e83ec5`.

## Install and connect

1. **One-time transition:** uninstall Highland Mirror preview 1, then install preview 2. The first preview used a temporary debug key that was not retained. Preview 2 starts a persistent signing identity so subsequent updates can keep phone setup and launcher shortcuts. Slipstream Highland is a different app and does not need to be uninstalled.
2. End the old Slipstream session, open Highland Mirror and tap **Start**. Nearby-device/audio permissions and Bluetooth/Wi-Fi enable prompts are handled in that flow. A sole paired CFMOTO is selected automatically; **Settings & help > Choose paired vehicle** handles other cases.
3. Phone identity is reused once saved. The app attempts a normal Bluetooth-address read; where Android returns its protected placeholder, a one-time guided dialog asks for the phone's Bluetooth address from Samsung Settings > About phone > Status information. **Find address** opens Settings and **Paste copied address** avoids retyping. This remaining Android restriction prevents a fully automatic first run on all phones.
4. Approve Android screen sharing, then accept the vehicle's Wi-Fi request when offered. The familiar landscape **Highland Home** opens automatically. Tap an empty slot to add an app; use its shortcut menu to change it. The home page can also be opened before connecting to arrange shortcuts.
5. Enable **vehicle touch control** in Accessibility to tap apps and use Back/Home from the Highland. Review its disclosure first. It replays one-finger taps/swipes on the phone, with swipes dispatched on release. It does not retrieve accessibility window content, read screen text or unlock the phone. If Android blocks a sideloaded app's accessibility toggle, review App info > menu > Allow restricted settings before returning to Accessibility. Viewing works without touch permission.
6. Keep the phone unlocked. This app mirrors the actual phone display: opening an app changes the phone too. The launcher uses landscape; other apps follow their phone orientation. Locking or revoking sharing ends capture; a new activation requires fresh Android approval.

**Start/Stop**, **Highland Home**, audio output and log sharing are on the main screen. Setup details and optional launcher music/notification access are under **Settings & help**. Vehicle Home returns to the Highland launcher when touch control is enabled.

## Connection correction in preview 2

The first vehicle log showed approved phone capture, a 1080x2340 capture-size callback and an encoded video keyframe. The CarPlay control socket then aborted before RECORD and media-audio startup, explaining why the picture stayed black and audio remained on the phone. It also showed Android's Wi-Fi MAC placeholder being sent to the head unit. A CarPlay-style launcher is not required to transport the phone picture.

This build recovers the actual joined-network MAC from a recognizable modified EUI-64 IPv6 link-local address when direct API access is unavailable. The conversion matches the real address in the earlier working Slipstream log. It does not guess privacy/random interface IDs. This corrects the observed identity mismatch; **the black-screen/audio result still needs a physical vehicle test**.

The paired vehicle's Wi-Fi password is used automatically. If it supplies no password, CFMOTO network names use **12345678**. Custom advertised credentials take priority. A Wi-Fi MAC override is available under advanced settings only for troubleshooting. Reconnection now dials iAP when the unprivileged app cannot cycle Bluetooth. Status distinguishes negotiated video configuration from RECORD; logs include the first producer frame and retain the last session result.

## Audio, privacy and limits

- **Highland speakers / Phone** switch live on the same CarPlay media connection. Phone stops capture and feeds silence so the stream can stay alive. The verified media startup sequence and AAC stereo transport are retained.
- Audio uses Android's user-approved playback capture. Apps can prohibit capture and local phone playback may remain audible. Microphone capture, cellular call audio and bidirectional hands-free calling are not implemented.
- Protected Netflix video is not supported in this preview. No Netflix fix or DRM workaround is included.
- Android owns screen-sharing approval, Wi-Fi approval and pairing. The app cannot silently read all hardware identifiers, force the primary Wi-Fi connection or cycle Bluetooth as the old privileged helper could.
- No mirrored screenshots, video samples or audio are saved. Log sharing happens only on request and can contain device/network identifiers; review before sharing publicly.

## Build and signing

The app reuses the open-source native transport and launcher; upstream source, history and license notices remain applicable and retained. Run `gradle :highlandmirror:testDebugUnitTest :highlandmirror:assembleDebug` in the existing Android toolchain container. The module shares only selected transport sources, `app/src-mirror`, and the launcher/UI libraries. No Shizuku/Sentry/helper variant is included.

Validation: 41 native media tests and 12 Android geometry/network-identity tests passed. Boundary checks verify the ordinary permission manifest and launcher declarations. Android's `apksigner` independently verifies the published APK after applying the persistent release signature to the exact verified CI artifact; ZIP entries and native-library offsets are unchanged. Signing private keys are never uploaded to GitHub or included in a job. Temporary build material and publisher workflows are removed after release.

Persistent release certificate SHA-256: `d730db78546f3d88015cf8ef7630d3fc81f49a0f7bd4c53e87348c98c2f6cee9`.

References: [MediaProjection](https://developer.android.com/media/grow/media-projection), [playback capture](https://developer.android.com/media/platform/av-capture), [Bluetooth identifier restriction](https://developer.android.com/reference/android/bluetooth/BluetoothAdapter#getAddress()), [EUI-64 conversion](https://www.rfc-editor.org/rfc/rfc4291.html), [APK signature scheme v2](https://source.android.com/docs/security/features/apksigning/v2).

## Netflix research

The owner reports Netflix works to a TV through Samsung Smart View. That is consistent with a protected TV-display link, not ordinary MediaProjection capture. [Android secure windows](https://developer.android.com/reference/android/view/WindowManager.LayoutParams#FLAG_SECURE) restrict screen sharing of protected content. [Netflix's supported connection guide](https://help.netflix.com/en/node/49) distinguishes casting, mirroring and video cables.

No verified phone-only Netflix fix was found for this stock Samsung/CarPlay capture path. A separate device that runs Netflix itself is a possible alternative. [Ottocast's i3 page](https://www.ottocast.com/products/ottoaibox-i3) lists a **Ford/Hyundai & more** variant for factory wireless CarPlay and says Netflix runs on the box. CFMOTO is not on its published compatibility list; Highland support is **unconfirmed** and needs manufacturer confirmation. A box requiring wired CarPlay does not match the reported wireless-only setup. No vendor message or adapter purchase was made.
