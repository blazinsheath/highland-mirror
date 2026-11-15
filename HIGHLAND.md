# Slipstream Highland development

## Starting point

- Upstream: https://github.com/cfraz89/carjack
- Baseline: `slipstream-v0.3.0`, commit `7415945d6ddba5867f7481a87df7e26d0ab05ca0`.
- Intended GitHub copy: `blazinsheath/slipstream-highland`.
- Development branch: `highland-dev`; keep upstream `main` as a reference.
- Target: 2027 CFMOTO U10 Pro Highland, Samsung Galaxy S25 Plus.
- Installed application ID: `io.github.blazinsheath.slipstream.highland`.
- Installed app name: **Slipstream Highland**; launcher shortcut: **Highland Launcher**.

The upstream download has displayed apps on this Highland and accepted touchscreen playback
controls. During that session, music played on the phone and the Highland refused the Bluetooth
audio connection. Ending the Slipstream session restored Bluetooth speaker audio. This copy
now compiles successfully; its behavior on the devices still needs testing.

## What this first change does

The full upstream project and licenses are retained. The generic Android app gets an independent
application ID, provider authorities, launcher task, and signing-key location so it can coexist
with the original download. Kotlin/JNI class namespaces remain unchanged to preserve the native
connection and Shizuku component bindings. Cargo builds use the checked-in lockfile.

No audio-forwarding implementation is included yet. This baseline is for confirming that our
own build preserves the working picture and touch connection before adding an audio stream.

## Build with GitHub Actions

After the fork exists, push this branch. The **Build Slipstream Highland** workflow builds using
upstream's containerized JDK/Android SDK/NDK/Rust toolchain and uploads a debug-signed APK with
its checksum. The workflow checks the installed package ID and APK signature before upload.
No signing secrets or Sentry credentials are needed for this development build.

In GitHub, open **Actions**, enable workflows for the fork if prompted, select the workflow,
then select **Run workflow** on `highland-dev` if a push has not already started it. Download
the `Slipstream-Highland-debug` artifact after a successful run and extract its APK.

The first workflow run passed on November 15, 2026:
https://github.com/blazinsheath/slipstream-highland/actions/runs/43768504623

It built commit `4276b665501fefa5c7efed30ad09500841aaf215` and verified the installed package ID
and APK signature. The `Slipstream-Highland-debug` artifact contains the baseline APK and its
SHA-256 checksum. A successful device test is still required before calling the connection
behavior verified; this build does not add speaker audio.

Debug signing is for initial testing. Fresh GitHub runners may generate different debug keys,
so later APKs may require uninstalling the earlier development copy. Before regular updates,
configure a persistent private signing key; never commit it or its passwords. Release signing
uses `HIGHLAND_KEYS` or `~/.config/slipstream-highland` and expects `release.jks` plus
`keystore.pass`. The release helper expects certificate subject `CN=Slipstream Highland`.

## Baseline device test

1. Leave the original Slipstream installed as a fallback, but end its session before opening
   Highland. Only one of the two apps should be running a session.
2. Install the Highland APK, start Shizuku, and grant the Highland copy Shizuku access separately.
3. Start a session while parked, with Bluetooth and Wi-Fi enabled. Let the app join the head
   unit's network; choose CarPlay on the Highland when requested.
4. Confirm the picture, touchscreen controls, and clean session end. Expect the existing audio
   limitation in this baseline.

## Audio milestones

1. **CarPlay speaker test:** add an opt-in, short, quiet test tone over the existing CarPlay
   session. Negotiate a format the Highland advertises and request the MainAudio resource.
   Keep the existing video path usable when the audio experiment is disabled or fails.
2. **Phone media:** capture supported Android playback, send it to the negotiated audio stream,
   and stop both capture and transmission cleanly on disconnect. Test the actual music apps;
   Android playback-capture policies may prevent capture from some sources.
3. **Navigation and reconnects:** test navigation prompts, volume, disconnect recovery,
   screen-off behavior, and restoration of normal Bluetooth audio after ending the session.
4. **Calls/microphone:** investigate separately; this is not implemented by basic media capture.
5. **Android Auto:** evaluate a genuine Android Auto receiver bridge separately from app
   projection. This fork currently projects Android apps through CarPlay.

Source entry points:

- `carjack-android/src/session.rs`: Android's native session and video startup.
- `libcarjack/src/airplay.rs`: screen-resource ownership, video stream, and accessory commands.
- `carjack-android/app/src-generic/.../DisplayService.kt`: privileged display/control helper.
- The pinned `cfraz89/catplay` revision in `Cargo.lock` is
  `2615e14b69230d04205e8de126b6d64346787cc0`. Its `AirPlayTransmitter::setup_audio` already
  accepts an audio recorder and exposes the advertised audio formats, so inspect and reuse
  that API before changing the underlying library.

## Attribution

Original carjack / Slipstream copyright and MIT/Apache-2.0 license files remain intact. This
is a development derivative, not an official CFMOTO, Samsung, Apple, or upstream release.
Dependencies and bundled assets retain their respective licenses.
