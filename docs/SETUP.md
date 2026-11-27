# Install and pair Herald

Use an Android TV or Google TV device running Android 8.0 (API 26) or later, on a trusted LAN. The APK targets SDK 36. Download `herald-<version>.apk` and `herald-<version>.apk.sha256` from the [release page](https://github.com/iwinties/herald/releases) and check that `sha256sum herald-<version>.apk` prints the same digest, or run `tools/build.sh` in a checkout with Docker.

### Set up with the remote

If an earlier Herald version is installed, uninstall it in **TV Settings → Apps → Herald** first. Version 0.4.0 uses a new Android package. You will need to configure it and pair your clients again.

Install the APK and open **Herald → Setup** on the TV.

1. Select **Show cards over other apps** and allow Herald to display overlays. If the TV has no suitable settings screen, the row shows the ADB command.
2. **Battery (optional)** opens the battery optimization settings. Exempt Herald if MQTT or media fetches need to keep working while the TV sleeps.
3. Select **Test card** to show “Herald test” above the app. Press Back to dismiss it or wait eight seconds. This works before you enable receiving.
4. Select **Start receiving** to accept requests. The choice survives reboots and updates. **Stop receiving** turns it off. While receiving is off, Setup and About remain available. If startup fails, retry here.
5. **Privacy** describes what Herald stores, how to remove it and where to find the policy. You can also open it from About.

A fresh install starts with receiving off. Herald does not request notification permission. Android 13 and later show the foreground service in the system’s running-apps list. Older TVs may list its minimum-importance notice in the notifications panel.

### Install with ADB

Enable developer options and debugging on the box. If using **Wireless debugging → Pair device with pairing code**, run `adb pair <box>:<pairing-port>` and enter the code at the prompt. Then use the **connection** port shown on the Wireless debugging screen. For a USB connection, approve the computer on the TV and use the serial listed by `adb devices`.

When upgrading from an earlier release, uninstall the old app before installing 0.4.0. The [upgrade notes](../CHANGELOG.md#040) include the ADB uninstall command.

From a computer with Android platform-tools (`adb` on PATH) and either `aapt2` or `sha256sum`:

```sh
tools/install-adb.sh 192.0.2.10
tools/install-adb.sh '<box>:<connection-port>' --apk 'herald-<version>.apk'
```

The helper defaults to TCP 5555 for a bare host and to `out/herald.apk`. It installs or updates the package and checks the package path and installed version against the APK. When `aapt2` is unavailable, it checks that the installed APK matches the source checksum before reporting its version. An ordinary install preserves grants and your saved receiving choice.

For full ADB setup, add `--start-receiving`. This also requires curl, OpenSSL, Python 3, sha256sum, base64 and timeout. It grants overlay access and the battery exemption, opens Setup, requests receiving and checks the running version over pinned HTTPS. For a USB serial it uses a temporary port forward. If you changed Herald's native API port, pass that port separately from the ADB connection port.

```sh
tools/install-adb.sh '<serial>' --start-receiving
tools/install-adb.sh '<box>:<connection-port>' --start-receiving --port 8080
```

**Without platform-tools on the host.** Place the release APK and its checksum file in the checkout’s `out/` directory. With the Docker image from `tools/build.sh` available, run these Bash commands, substituting your connection address and release version. For wireless debugging, use `tools/adb.sh pair '<box>:<pairing-port>'` after starting the container if the TV asks for pairing.

```bash
set -eo pipefail
serial=192.0.2.10:5555
version=0.4.0
apk="herald-$version.apk"
(cd out && sha256sum -c "$apk.sha256")
tools/adb.sh start
trap 'tools/adb.sh stop' EXIT
tools/adb.sh connect "$serial"
tools/adb.sh -s "$serial" install -r "/apk/$apk"
installed=$(tools/adb.sh -s "$serial" shell pm path co.tbrkr.herald | tr -d '\r')
[[ $installed == package:/* ]]
device_hash=$(tools/adb.sh -s "$serial" shell sha256sum "${installed#package:}")
release_hash=$(sha256sum <"out/$apk")
[[ ${device_hash%% *} == "${release_hash%% *}" ]] || { echo "Installed APK differs" >&2; exit 1; }
tools/adb.sh stop
trap - EXIT
```

The container sees the host’s `out/herald-<version>.apk` as `/apk/herald-<version>.apk`. After the checksum matches, open Herald and follow Setup on the TV.

**Manual installation and start.** With platform-tools on the host, connect to the box, install the verified APK and enable receiving as follows. With container ADB, keep the container running for these steps, substitute `tools/adb.sh` for `adb` and use the APK path under `/apk/`. Run `tools/adb.sh stop` when finished.

```bash
set -eo pipefail
serial=192.0.2.10:5555
adb connect "$serial"
adb -s "$serial" install -r herald-0.4.0.apk
adb -s "$serial" shell appops set co.tbrkr.herald SYSTEM_ALERT_WINDOW allow
adb -s "$serial" shell dumpsys deviceidle whitelist +co.tbrkr.herald
adb -s "$serial" shell am start -W -n co.tbrkr.herald/.SettingsActivity
result=$(adb -s "$serial" shell am broadcast -a co.tbrkr.herald.action.ENABLE_RECEIVING \
  -n co.tbrkr.herald/.ShellReceiver)
printf '%s\n' "$result"
[[ $result =~ result=-1([^0-9]|$) ]] || { echo "Start refused" >&2; exit 1; }
```

After `result=-1`, verify that the listener actually started. In the [API guide](API.md), run the first Bash block with the native address and port, and compare its fingerprint with **Pairing & clients** on the TV. In the same shell, check the running version with the verified pin:

```bash
curl -q --noproxy '*' --insecure --pinnedpubkey "sha256//${pin:?verified pin required}" -fsS "$box/v1/info" |
  jq -e '.app == "herald" and .api == 2 and .version == "0.4.0"'
```

For USB, use your connected serial and omit `adb connect`. Before the API guide’s bootstrap, run `adb -s "$serial" forward tcp:0 tcp:7979` and use `127.0.0.1:<returned-port>` as the address. After the pinned check, remove the forward with `adb -s "$serial" forward --remove tcp:<returned-port>`, substituting the actual port.

### Pair an application

- **From the client.** Wake the TV, start pairing in the client (or POST /v1/pair/start with a client name), then enter the six digits from the overlay into the client. The finish call uses the same name.
- **From the TV.** Open **Herald → Pairing & clients → Pair a new client**. Send the displayed code and your client name to /v1/pair/finish. The code stays valid until it times out, also if you leave the screen.
- Codes last two minutes. Five wrong attempts or a cancellation starts a one-minute lockout. Store the returned bearer token privately. Revoke it from the client or remove the client on the TV. ADB pairing and Herald pairing are separate.

Use the [HTTPS pairing commands](API.md#pairing-and-device-identity) or the [Home Assistant setup flow](HOME_ASSISTANT.md).

### TV controls

Open Herald from the launcher. Use the D-pad to choose a section, Left/Right to change choices and OK for text entry. Changes save immediately. BACK moves out through the current editor, list and sections.

| Section | Controls |
|---|---|
| Setup | Overlay access, optional battery exemption, test card, receiving and privacy |
| Looks | Preview tiles, press OK for a real test card, hold OK to assign a category, create and edit custom looks |
| Categories | Pick a look, change Announce and individual style fields, Send test, reset, add or delete custom categories |
| Display | Card cap, local night times, chip look, placement and pixel shift, sticky cap and focus while playing |
| Sound & voice | TV and speaker volumes, night policy, chime, separate TV and box voice tests, chime test |
| Pairing & clients | Enable pairing, open a code or remove an existing client |
| MQTT | Broker connection, TLS, credentials, topics, discovery and commands |
| ntfy | Server, token, topics, catch-up and per-topic speech ([ntfy setup](NTFY.md)) |
| Compatibility | Second listener, port, sender allowlist and default category |
| Status | Active addresses and port, paired clients, last card and audio, sound, screen, MQTT, compatibility and permissions |
| About | Name and API port, device identity, address, versions, permissions, recent events |

Pickers wrap at the ends and move to the next legal step from custom API values. Volume and opacity step by five, times by 30 minutes and visible count by one. Duration includes until cleared, one-second steps below ten, five-second steps up to 60 and 30-second steps thereafter. The color pickers offer 12 colors; HTTP accepts any #RRGGBB.

### Ports and removal

The native listener defaults to TCP 7979; optional TvOverlay compatibility uses TCP 5001. Configure distinct ports in Herald. MQTT is outbound to your chosen broker (default TCP 1883). DNS-SD advertises the API port. ADB uses its own debugging port and can be disabled after setup.

Uninstalling removes Herald and its local settings and paired credentials. To remove the exemption first and uninstall:

```sh
adb -s "$serial" shell dumpsys deviceidle whitelist -co.tbrkr.herald
adb -s "$serial" uninstall co.tbrkr.herald
```

To revoke wireless debugging trust on the TV, open **Wireless debugging → Paired devices**, select the computer and choose **Forget**.

### 0.4.0 support matrix

| Target | Coverage | Status |
|---|---|---|
| Android TV emulator | Fresh install, HTTPS, pairing, receiving across restarts, ntfy delivery and bundle-derived APK | Passed: 12 checks on the 0.4.0 build (emulator 37.3.10, android-36 TV image revision 4) |
| Physical Android TV / Google TV | Removal of the old package, fresh setup, grants and pinned pairing | Passed on an onn 4K Pro (Android 14): the 0.3.0 package removed, 0.4.0 installed and set up, TLS 1.2 and 1.3 with matching digests, pinned pairing (2026-11-27) |
| Home Assistant | Matching integration, removal of the old box entry and pairing the new installation | Offline suite passed (79 tests on the 0.4.0 build); live: old box entry deleted, the new installation paired with the fingerprint check, a card and a spoken announcement passed on Home Assistant 2026.10.1 |

### 0.3.0 support matrix

| Target | Coverage | Status |
|---|---|---|
| Android TV emulator | HTTPS, pairing, receiving across restarts, ntfy delivery and bundle-derived APK | Passed: 12 checks on the 0.3.0 build (emulator 37.3.10, android-36 TV image revision 4) |
| Physical Android TV / Google TV | Upgrade, remote setup, grants, pairing and delivery | Partial: on an onn 4K Pro (Android 14) a fresh install, remote setup and grants, TLS 1.2 and 1.3 with matching digests, pinned pairing, Stop and Start receiving, a reboot and ntfy delivery with speech passed on the 0.3.0 build (2026-11-27); the upgrade over 0.2 is checked separately |
| Home Assistant | Matching integration, certificate pinning and 0.2 entry migration | Offline suite passed (79 tests on the 0.3.0 build); a live pairing with the fingerprint check, a card and a spoken announcement passed on Home Assistant 2026.10.1 |

### 0.2.0 support matrix

| Target | Coverage | Status |
|---|---|---|
| Android 14, onn 4K Pro | Clean installation and physical display, remote and audio checks | Partial: 13 of 18 checks passed on the 0.2.0 build (2026-11-26); 5 skipped: TV-route audio from the TV app, two live Home Assistant checks, regressions and cleanup |
| Android TV 16 | Emulator UI and unattended runner | Passed: 12 UI checks and 6 runner checks on the 0.2.0 build in the emulator |
| Home Assistant | Paired box, spoken card, clip reuse and announcement with a card | Offline suite passed (67 tests on the 0.2.0 build); the live check was skipped |
| Cast speakers | Offline integration tests | Offline tests pass; not yet tested on a real speaker |

### 0.1.0 support matrix

| Target | Planned coverage | Status |
|---|---|---|
| Android 14, onn 4K Pro | Physical device check | Passed: 46 checks on the 0.1.0 build (2026-11-26) |
| Android TV 16 | Emulator only | Passed: six checks on the 0.1.0 build in the emulator |
| Non-zero system-bar insets | Not device-verified | Not verified: the onn and the emulator both report zero insets |
| Cast speakers | Offline tests only, not yet tested on a real speaker | Offline tests pass; not yet tested on a real speaker |
