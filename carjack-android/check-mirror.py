#!/usr/bin/env python3
"""Guard one APK: bundled owner-paired helper, ordinary manifest, no screen recordings."""
from pathlib import Path
import re
import xml.etree.ElementTree as ET

root = Path(__file__).resolve().parent
manifest = ET.parse(root / 'app/mirror/AndroidManifest.xml').getroot()
ns = '{http://schemas.android.com/apk/res/android}'
permissions = {item.get(ns + 'name') for item in manifest.findall('uses-permission')}
assert 'android.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION' in permissions
assert 'android.permission.RECORD_AUDIO' not in permissions  # Bundled shell captures output, never microphone.
assert not any(re.search(r'shizuku|INJECT_EVENTS|CAPTURE_AUDIO_OUTPUT|READ_LOGS|WRITE_SECURE_SETTINGS', p) for p in permissions)
services = {item.get(ns + 'name'): item for item in manifest.findall('application/service')}
assert services['.SlipstreamService'].get(ns + 'foregroundServiceType') == 'connectedDevice|mediaProjection'
assert services['.MirrorControlService'].get(ns + 'permission') == 'android.permission.BIND_ACCESSIBILITY_SERVICE'
control = ET.parse(root / 'app/mirror/res/xml/mirror_control.xml').getroot()
assert control.get(ns + 'canRetrieveWindowContent') == 'false'
assert control.get(ns + 'canPerformGestures') == 'true'
module = (root / 'gradle/highlandmirror/build.gradle.kts').read_text()
assert 'include("**/PlaybackCapture.kt")' in module and 'libs/shizuku' not in module
assert 'implementation(project(":launcher"))' in module
activities = {item.get(ns + 'name') for item in manifest.findall('application/activity')}
assert 'io.github.cfraz89.slipstream.launcher.HomeActivity' in activities
assert 'io.github.cfraz89.slipstream.launcher.AllAppsActivity' in activities
variant = (root / 'app/src-mirror/io/github/cfraz89/slipstream/Variant.kt').read_text()
assert '"record_screen_samples" -> "false"' in variant
for source in (root / 'app/src-mirror').rglob('*.kt'):
    assert not re.search(r'import (?:rikka|moe)\.shizuku|Runtime\.getRuntime', source.read_text()), source
helper = (root / 'app/src-mirror/io/github/cfraz89/slipstream/MirrorHelper.kt').read_text()
shell = (root / 'app/src-mirror/io/github/cfraz89/slipstream/MirrorShellMain.kt').read_text()
assert 'InetSocketAddress("127.0.0.1", port)' in helper
assert 'ServerSocket(port, 4, InetAddress.getByName("127.0.0.1"))' in shell
assert 'MirrorHelperLink.client(client, key)' in helper and 'MirrorHelperLink.server(client, key)' in shell
assert 'Process.myUid() != MirrorHelperProtocol.SHELL_UID' in shell
assert 'playback.stop(); exitProcess(0)' in shell
receivers = {item.get(ns + 'name'): item for item in manifest.findall('application/receiver')}
assert receivers['.MirrorPairingReceiver'].get(ns + 'exported') == 'false'
print('Mirror boundary checks passed')
