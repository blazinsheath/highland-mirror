# Highland Mirror

Mirror your Android phone to a CFMOTO U10 Pro Highland over wireless CarPlay, with vehicle speakers, touchscreen control and a customizable app home page.

Tested by the owner with a Samsung Galaxy S25 Plus and CFMOTO U10 Pro Highland. Compatibility with other phones and vehicles is not yet verified.

## ☕ Buy me a coffee

Enjoying Highland Mirror? If this project helps you enjoy your Highland a little more, consider buying me a coffee! Your support helps cover testing and continued development. Completely optional—and always appreciated.

[☕ Buy me a coffee via Venmo](https://venmo.com/code?user_id=2661297892098048001&created=1794921813)

## Download and update

[Download preview 8 APK](https://github.com/blazinsheath/highland-mirror/releases/download/v0.1.0-highland-mirror-preview.8/Highland-Mirror-0.1.0-preview.8.apk) · [All releases](https://github.com/blazinsheath/highland-mirror/releases)

Download the APK directly; no ZIP extraction is needed. Install it over your current Highland Mirror app to preserve pairing and shortcuts. This is a preview release.

This repository is public: anyone can view the source and download release APKs.

## First-time setup

1. Install the APK and open Highland Mirror. Approve Nearby devices and notification permissions when requested.
2. Connect your phone to Wi-Fi. Tap Start to follow the setup guide.
3. If needed, enable Developer options: Settings > About phone > Software information > tap Build number seven times.
4. Open Developer options > Wireless debugging and turn it on. Choose Pair device with pairing code.
5. Keep that dialog open, pull down notifications and enter its six-digit code in Highland Mirror's pairing notification. Return to the app when pairing succeeds. Pairing is saved.
6. Tap Start, approve Android screen sharing, and accept the vehicle Wi-Fi connection request. Vehicle selection and phone identification are automatic. CFMOTO's supplied Wi-Fi password is used; the default fallback is 12345678.
7. Keep your phone unlocked with its screen on. The built-in helper handles vehicle audio and touch; no separate helper app is required.

## Everyday use

- Enable Bluetooth and Wi-Fi. If Android turned Wireless debugging off, enable it again before starting.
- Tap Start and approve screen sharing. Highland Home opens on the vehicle display.
- Use Highland Home to add and arrange app shortcuts.
- Choose Highland speakers for vehicle audio or Phone for phone audio. You can switch during the session without disconnecting.
- Tap Stop when finished. Approve screen sharing again on the next start.

## Netflix with Rave

The owner confirmed actual Netflix movie video and sound on the Highland when playback runs inside Rave and Highland Mirror mirrors it.

1. Install Rave - Watch Party by Rave Inc. from Google Play: https://play.google.com/store/apps/details?id=com.wemesh.android
2. Sign in to Rave and Netflix, and start playback inside Rave. Use a private room if you do not want others joining.
3. Connect Highland Mirror, select Highland speakers, then return to Rave and play full screen while parked.

Rave remains a separate app and needs your Netflix subscription. It is not bundled, affiliated with Highland Mirror or guaranteed to keep working after service updates. The native Netflix app's protected movie video may appear black. Firefox desktop mode failed with a session error on the tested phone.

## Troubleshooting

- Pairing saved but helper did not start: tap Retry start. Do not enter another pairing code unless pairing was rejected or removed. The connection port on Wireless debugging's main screen differs from the temporary pairing-code port.
- Waiting for head unit: leave the vehicle on and use Retry vehicle connection. In Settings & help, choose the paired CFMOTO explicitly if automatic selection is wrong. Preview 8 remembers the successful vehicle and limits repeated Bluetooth resets; Stop/Start recovery still needs further vehicle testing.
- No audio: choose Highland speakers, turn up the vehicle's media volume and confirm the source app is playing. Bluetooth music may be unavailable while CarPlay is active.
- No touch: start through the guided helper setup. Accessibility under Touch fallback is optional. If Android blocks that fallback, open App info > three-dot menu > Allow restricted settings, then enable the accessibility service yourself.
- Need support: Settings & help > Share diagnostic log. Review logs before posting them. Do not post pairing codes or credentials.

## Limits

The phone must remain unlocked with its screen on. Locking stops screen sharing. Cellular-call audio and two-way hands-free calling are not implemented. Compatibility and reconnect behavior may vary by head unit.

## Source, build and licenses

Source derives from the project's existing development branch, preserving upstream history and required notices. See LICENSE-APACHE, LICENSE-MIT and carjack-android/app/mirror/assets/licenses/NOTICE.txt.

The Android build workflow runs native transport tests and Android unit tests, then builds the highlandmirror module. Preview 8 passed 95 automated tests. CI debug artifacts are for development; the release APK uses the persistent release certificate so existing installations can update.

Release APK SHA-256:
e830b8c889d3e0eea4f90423f879ad02bd30b7f64dea09e479eaea1b57166d6b

Release certificate SHA-256:
d730db78546f3d88015cf8ef7630d3fc81f49a0f7bd4c53e87348c98c2f6cee9

