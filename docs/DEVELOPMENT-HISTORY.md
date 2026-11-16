> Historical implementation notes. Current instructions and confirmed Rave playback are in README.md. Some older test-status statements below are superseded.

# Highland Mirror

One APK for Samsung/Android phone mirroring to the CFMOTO U10 Pro Highland over wireless
CarPlay. It includes the customizable Highland home page and an owner-paired shell helper.
**Everything is included in one APK.** The built-in helper supplies vehicle audio, automatic
phone identification and vehicle touch after the owner authorizes pairing.

[Download the signed preview 8 APK](https://github.com/blazinsheath/slipstream-highland/releases/download/v0.1.0-highland-mirror-preview.8/Highland-Mirror-0.1.0-preview.8.apk).

## Preview 8 reconnect fix

The preview-7 diagnostic log showed that after Stop, automatic retries targeted TONE-T60Q
instead of CFMOTO. Some headphones advertise iAP too. Automatic selection now prefers paired
CFMOTO vehicles, excludes headphones/headsets from fallback candidates, and remembers the
vehicle that completed CarPlay setup. An explicit vehicle choice still takes priority.

Bluetooth's return to ON now triggers a fresh connection attempt and connected-device check.
Watch, Retry and session-failure recovery share one reset budget, replenished only by a session
that reached streaming. A manual toggle consumes the budget too. Late helper results no longer
replace progress with repeated toggle instructions. Logs include reset command phases and
observed adapter restoration; a command timeout alone does not prove Bluetooth stayed off.
Bluetooth receivers remain registered until session cleanup completes, without the service
receiver-leak warning seen in the supplied log.

Install over the existing app, connect, tap Stop, then Start and approve screen sharing again.
Leave the vehicle on for this test. The update's physical reconnect behavior is not yet verified.
Working audio, input, launcher and mirroring code are retained. Recovery can briefly disconnect
other Bluetooth accessories. No Netflix playback changes are included.

## Preview 7 improvements

First-time Start opens a three-step guide: enable phone developer options, enable Wireless
debugging, then enter the pairing code from the notification. Each return to the app checks
progress; saved pairing is retained. Settings opens the relevant system page, with a Wireless
debugging row highlight where the phone supports it. Android still requires the owner to
approve debugging; the app cannot enable it silently during installation.

Reconnection now restores the Bluetooth reset missing from the all-in-one version. A known
bonded vehicle can be retried even when Android missed its connection event. Recovery briefly
switches the phone's Bluetooth off and on, so watches/headsets may also briefly disconnect.
Commands are fixed, authenticated and bounded; even an off-state timeout attempts to restore
Bluetooth. Stop waits for this restoration before the helper exits. Only one dial can run;
stalled dials time out and old sockets are closed. Late native completion cannot clean up a
new session. The updated reconnect path still needs a physical vehicle test.

App/help/release branding is Highland Mirror. Required third-party notices remain under
Open-source licenses. Internal source namespaces and repository URLs retain compatibility.

## Setup

1. Install the signed update over the current Highland Mirror app to retain pairing, setup
   and shortcuts. **Already paired? Tap Start.** Keep the existing
   pairing and port settings. Enable Wireless debugging again only if Android has turned it off.
2. **First-time setup only:** end any other phone-mirroring session and connect the phone to Wi-Fi.
   Open Highland Mirror and tap **Start** for one-time setup, or open
   **Settings & help > Set up phone connection**. If necessary enable Samsung Developer options by tapping
   About phone > Software information > Build number seven times.
3. Open Developer options > Wireless debugging, enable it, and choose **Pair device with
   pairing code**. Keep the dialog open, pull down notifications and reply to Highland Mirror's
   notification with its six-digit code. The app discovers the local pairing port automatically.
   Pairing success is reported separately; return to the app and tap Start. If notifications are disabled,
   **Settings & help > Pairing code · manual fallback** accepts the pairing port/code while
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
6. Vehicle touch, swipes and Back/Home connect automatically through the same built-in helper.
   There is no Accessibility permission or Allow restricted settings step in normal setup.
   Existing Accessibility touch access remains an optional fallback under Settings & help.
   The app does not automatically grant or enable Accessibility, alter other apps' permissions,
   read Accessibility window content, or unlock the phone. Touch is accepted only while the
   phone projection is attached; disconnect/orientation changes cancel held touches and keys.
7. Keep the phone unlocked and its screen on. The current MediaProjection backend stops when
   the phone locks. Physical-display-off operation is not implemented or verified. The home page
   uses landscape; other apps follow their phone orientation. Open apps change the phone too.

## Automatic touch and simpler use in preview 6

The owner confirmed preview 5 connects and plays audio. Android then blocked the optional
Accessibility service until the owner allowed restricted settings; the owner reported that
step worked. Preview 6 removes that extra setup from the normal path, rather than attempting
to grant a restricted permission during APK installation.

The authorized shell helper now accepts a fixed input protocol on a separately authenticated
loopback connection. It uses the same InputManagerGlobal injection APIs as the original
original input service, targeting the phone's default display. Phone-image coordinate mapping
is retained. Touch/key messages are bounded, validated and acknowledged; gesture/key state
is released on cancellation, connection loss or rotation. Back includes both down and up,
and vehicle Home returns to the customizable launcher. No input coordinates or text are saved.

The main app keeps Start/Stop, Home and Highland speakers/Phone. Setup, logs, pairing and
optional Accessibility fallback are under Settings & help. Android still owns first-time
Wireless debugging pairing, nearby-device approval, screen-sharing approval and network
prompts. The app cannot grant those sensitive approvals during installation.

The working audio capture, launch-readiness handshake and native CarPlay media transport
are preserved. Automatic helper input still needs a new physical S25 Plus/Highland test.

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
the earlier working build. The capture backend was different: ordinary AudioPlaybackCapture
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
stereo startup/ownership sequence remains the previously verified transport. The owner confirmed
preview 5 connects and plays audio on the S25 Plus/Highland; preview 6 preserves that path.

Protected Netflix frames, cellular-call audio, microphone capture and bidirectional hands-free
calling are not implemented. A helper does not promise protected-video playback.

## Build, dependencies and signing

Build with `gradle :highlandmirror:testDebugUnitTest :highlandmirror:assembleDebug` using the
repository's Android toolchain container. Only selected common transport files and the known
PlaybackCapture implementation are shared. The connection helper is bundled in the APK.
The manifest requests no system capture, input-injection, log-reading or secure-settings
permission. Accessibility remains gesture-only, with window retrieval disabled.

New pairing dependencies are pinned: LibADB Android 3.1.1 (Apache-2.0 option), Conscrypt Android
2.5.3, Bouncy Castle 1.81, and transitive SPAKE2 Android 2.2.1 (LGPL-3.0). License texts,
source URLs and replacement/rebuild instructions are bundled under Settings & help >
Open-source licenses and in app/mirror/assets/licenses. Upstream source/history/notices remain.

Preview 8 passed **95 automated tests**: 41 native media tests and 54 Android tests, including
vehicle selection with headphones present, remembered/explicit targets, missing cached device
details, manual toggles and reset budgets, plus existing input, audio, pairing and setup tests. Package/version,
permission boundaries and Android APK Signature Scheme v2 were verified. Native ELF segments
and APK library offsets retain 16 KB alignment. Preview 5 was physically confirmed for connection
and audio. Automatic control and reconnect recovery still need a phone/vehicle test.

Source: `a86eeb861aef4bd855a6cf0abec3a4dc68cd2a1f`.
[Build and test run](https://github.com/blazinsheath/slipstream-highland/actions/runs/43940986440).
APK SHA-256: `e830b8c889d3e0eea4f90423f879ad02bd30b7f64dea09e479eaea1b57166d6b`.

Official updates retain release certificate SHA-256:
`d730db78546f3d88015cf8ef7630d3fc81f49a0f7bd4c53e87348c98c2f6cee9`.
The private key is not included in GitHub or build jobs. Android apksigner independently
checks the final published signature. Temporary publisher workflows are removed after release.

References: [MediaProjection and lock termination](https://developer.android.com/media/grow/media-projection#resource_recovery),
[REMOTE_SUBMIX](https://developer.android.com/reference/kotlin/android/media/MediaRecorder.AudioSource#REMOTE_SUBMIX),
[LibADB Android](https://github.com/MuntashirAkon/libadb-android/tree/3.1.1).

## Netflix research (checked 2026-11-16)

No verified phone-only Netflix fix was found for the present CarPlay capture path. Netflix's
[current mirroring guide](https://help.netflix.com/en/node/49) mentions Samsung Smart View and
DeX for supported displays, while its [black-screen guidance](https://help.netflix.com/en/node/11634)
warns that Android mirroring features/apps may not be supported. Success through Smart View
to a TV does not establish support for this CarPlay MediaProjection path. Input permissions
or automatic touch do not change the protected video surface.

The [supported-browser requirements](https://help.netflix.com/en/node/30081) do not list
Android Chrome as a supported desktop-web playback target. A desktop user-agent/WebView
switch was not verified as a solution and is not advertised or added as a Netflix mode.

A software candidate remains to be tested: Mozilla [verified Netflix playback in Firefox Android
desktop mode](https://bugzilla.mozilla.org/show_bug.cgi?id=1915097#c17) on a Pixel 5 running
Android 14. That confirms browser playback on that configuration, not capture on the S25 Plus
or transmission through Highland Mirror. A phone/vehicle test is pending. If it succeeds,
[GeckoView](https://mozilla.github.io/geckoview/) provides an embeddable engine for one APK.
No embedded browser or Netflix playback change is included in this update.

Android documents that secure windows are blanked on non-secure virtual displays and reserves
secure capture permission for system components. The new pairing/reconnect code does not change
that capture path: [Android display restrictions](https://developer.android.com/reference/android/hardware/display/DisplayManager#VIRTUAL_DISPLAY_FLAG_SECURE).
