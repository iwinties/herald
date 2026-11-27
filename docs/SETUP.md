# Install and pair Herald

Use an Android TV or Google TV device running Android 8.0 (API 26) or later, on a trusted LAN. The APK targets SDK 36. Download `herald-<version>.apk` and `herald-<version>.apk.sha256` from the [release page](https://github.com/iwinties/herald/releases) and check that `sha256sum herald-<version>.apk` prints the same digest, or run `tools/build.sh` in a checkout with Docker. **0.2.0 is a clean install.** Uninstall 0.1.0 first. Settings are not carried over. After installing 0.2.0, grant permissions, pair clients again and re-enter your settings.

### Install with ADB

Enable developer options and debugging on the box. If using **Wireless debugging → Pair device with pairing code**, run `adb pair <box>:<pairing-port>` and enter the code at the prompt. Then use the **connection** port shown on the Wireless debugging screen. For a USB connection, approve the computer on the TV and use the serial listed by `adb devices`.

If 0.1.0 is installed, select Herald's Uninstall action on the box or run `adb -s '<serial>' uninstall io.github.iwinties.herald` after connecting. Then proceed with the fresh install.

From the checkout on a computer with Android platform-tools, curl and Python 3:

```sh
tools/install-adb.sh 192.0.2.10
# Explicit connection port or USB serial and a downloaded release
tools/install-adb.sh '<box>:<connection-port>' --apk herald-0.2.0.apk
tools/install-adb.sh '<serial>' --apk herald-0.2.0.apk
```

The helper defaults to TCP 5555 for a bare host, installs out/herald.apk with replacement, and compares the live /v1/info version with the installed package. For a local serial it probes through a temporary port forward.

**Without platform-tools on the host.** `tools/adb.sh start` runs an adb server in the build image (port 5038, its key in `~/.config/herald-adb`, the checkout's `out/` mounted at `/apk`). Then use `tools/adb.sh` wherever the steps below say `adb`, with the APK path `/apk/herald-<version>.apk`, and `tools/adb.sh stop` when done. To confirm the installed APK is the release, compare the first field of `tools/adb.sh -s "$serial" shell 'sha256sum "$(pm path io.github.iwinties.herald | sed -n "s/^package://p")"'` with the release checksum.

**Manual installation.** Substitute your host and connection port. For USB, set serial to your connected serial, omit connect and use a temporary forward to check info.

```sh
serial=192.0.2.10:5555
pkg=io.github.iwinties.herald
adb -s "$serial" connect "$serial"
adb -s "$serial" install -r herald-0.2.0.apk
if [ "$(adb -s "$serial" shell getprop ro.build.version.sdk | tr -d '\r')" -ge 33 ]; then
  adb -s "$serial" shell pm grant "$pkg" android.permission.POST_NOTIFICATIONS
fi
adb -s "$serial" shell appops set "$pkg" SYSTEM_ALERT_WINDOW allow
adb -s "$serial" shell dumpsys deviceidle whitelist "+$pkg"
adb -s "$serial" shell am start-foreground-service -n "$pkg/.HeraldService"
curl -fsS http://192.0.2.10:7979/v1/info
```

The overlay permission allows cards and network-started pairing codes above other apps. The foreground service handles incoming requests. Android Doze can cut outgoing connections on a sleeping box even while incoming LAN requests work. The battery exemption allows MQTT and media fetches to continue. Check **Status** for both settings and test network behavior on your device.

### Pair an application

- **From the client.** Wake the TV, start pairing in the client (or POST /v1/pair/start with a client name), then enter the six digits from the overlay into the client. The finish call uses the same name.
- **From the TV.** Open **Herald → Pairing & clients → Pair a new client**. Send the displayed code and your client name to /v1/pair/finish. The code stays valid until it times out, also if you leave the screen.
- Codes last two minutes. Five wrong attempts or a cancellation starts a one-minute lockout. Store the returned bearer token privately. Revoke it from the client or remove the client on the TV. ADB pairing and Herald pairing are separate.

Use the [HTTP pairing commands](API.md#pairing-and-device-identity) or the [Home Assistant setup flow](HOME_ASSISTANT.md).

### TV controls

Open Herald from the launcher. Use the D-pad to choose a section, Left/Right to change choices and OK for text entry. Changes save immediately. BACK moves out through the current editor, list and sections.

| Section | Controls |
|---|---|
| Looks | Preview tiles, press OK for a real test card, hold OK to assign a category, create and edit custom looks |
| Categories | Pick a look, change Announce and individual style fields, Send test, reset, add or delete custom categories |
| Display | Card cap, local night times, chip look, placement and pixel shift, sticky cap and focus while playing |
| Sound & voice | TV and speaker volumes, night policy, chime, separate TV and box voice tests, chime test |
| Pairing & clients | Enable pairing, open a code or remove an existing client |
| MQTT | Broker connection, TLS, credentials, topics, discovery and commands |
| Compatibility | Second listener, port, sender allowlist and default category |
| Status | Active addresses and port, paired clients, last card and audio, sound, screen, MQTT, compatibility and permissions |
| About | Name and API port, device identity, address, versions, permissions, recent events |

Pickers wrap at the ends and move to the next legal step from custom API values. Volume and opacity step by five, times by 30 minutes and visible count by one. Duration includes until cleared, one-second steps below ten, five-second steps up to 60 and 30-second steps thereafter. The color pickers offer 12 colors; HTTP accepts any #RRGGBB.

### Ports and removal

The native listener defaults to TCP 7979; optional TvOverlay compatibility uses TCP 5001. Configure distinct ports in Herald. MQTT is outbound to your chosen broker (default TCP 1883). DNS-SD advertises the API port. ADB uses its own debugging port and can be disabled after setup.

Uninstalling removes Herald and its local settings and paired credentials. To remove the exemption first and uninstall:

```sh
adb -s "$serial" shell dumpsys deviceidle whitelist -io.github.iwinties.herald
adb -s "$serial" uninstall io.github.iwinties.herald
```

To revoke wireless debugging trust on the TV, open **Wireless debugging → Paired devices**, select the computer and choose **Forget**.

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
