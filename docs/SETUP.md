# Install and pair Herald

Herald supports Android TV and Google TV running Android 8.0 or later. Use a trusted local network. Until Herald is available on Google Play, install the TV app from its signed GitHub release.

### Sideload the TV app

1. On the [official release page](https://github.com/iwinties/herald/releases/latest), open **Assets** and choose `herald-<version>.apk`. On the TV you can use a browser that supports downloads. Alternatively, download the APK on a computer, copy it to a USB drive, and open it with a file manager on a TV that supports USB storage.
2. Open the APK. If Android asks for permission, choose **Settings** and allow the browser or file manager you used under **Install unknown apps**. Some TVs call this **Unknown sources**. Return to the APK and open it again.
3. Select **Install** (or **Update** if Herald is already installed), then **Open**. You can turn off the installer’s permission once Herald is installed.
4. Follow [Set up with the remote](#set-up-with-the-remote), then [pair Home Assistant](HOME_ASSISTANT.md#add-a-box) or connect another sender.

Prefer a computer and Android platform-tools? Follow [Install with ADB](#install-with-adb). To verify a downloaded APK on a computer, download the matching `.apk.sha256` from the same release and run `sha256sum -c herald-<version>.apk.sha256` in the download folder, substituting the version number. [Home Assistant installation and pairing](HOME_ASSISTANT.md#install)

### Set up with the remote

[Upgrade instructions for existing installations](../CHANGELOG.md#053).

Install the APK and open **Herald → Setup** on the TV.

1. Select **Show cards over other apps** and allow Herald to display overlays. If the TV has no suitable settings screen, the row shows the ADB command.
2. Select **Test card**. Press Back to dismiss it or wait eight seconds.
3. Select **Start receiving**. The Setup screen shows the TV address. Receiving stays on across reboots and updates until you select **Stop receiving**.

Under **More options → Battery (optional)**, you can exempt Herald from battery optimization if MQTT or media fetches need to work while the TV sleeps. **Privacy** explains what Herald stores and how to remove it.

A fresh install starts with receiving off. Herald does not request notification permission. Android 13 and later show the foreground service in the system’s running-apps list. Older TVs may list its minimum-importance notice in the notifications panel.

### Pair an application

- **From the client.** Wake the TV, start pairing in the client (or POST /v1/pair/start with a client name), then enter the six digits from the overlay into the client. The finish call uses the same name.
- **From the TV.** Open **Herald → Pairing & clients → Pair an app or script**. Send the displayed code and your client name to /v1/pair/finish. The code stays valid until it times out, also if you leave the screen.
- Codes last two minutes. Five wrong attempts or a cancellation starts a one-minute lockout. Store the returned bearer token privately. Revoke it from the client or remove the client on the TV. ADB pairing and Herald pairing are separate.

Use the [HTTPS pairing commands](API.md#pairing-and-device-identity) or the [Home Assistant setup flow](HOME_ASSISTANT.md).

### TV controls

Open Herald from the launcher. Use the D-pad to choose a section, Left/Right to change choices and OK for text entry. Changes save immediately. BACK moves out through the current editor, list and sections.

Each screen remembers its own **More options** choice on this TV. Open **Integrations** for MQTT, ntfy and TvOverlay. Back returns to the integration you selected in the list.

| Section | Main controls | More options |
|---|---|---|
| Setup | Overlay access, test card, receiving, privacy | Battery exemption |
| Pairing & clients | Pair a client, compare the fingerprint, manage paired clients | Allow pairing (also visible directly if disabled) |
| Looks | Card thumbnails, OK for a full-size eight-second preview, hold OK to assign a category | Create or edit a custom look. Its editor has separate fine styling and button colors |
| Categories | Choose a category, assign a look, set speech, preview buttons or send a test | Add a category from the list. In an editor, fine-tune appearance, reset or remove a custom category |
| Display | Visible cards, night hours, chip look and position | Pixel shift, sticky duration, button focus during playback |
| Sound & voice | TV volume, box speaker volume, chime | Night policy and voice or chime tests |
| Integrations | MQTT broker and credentials, ntfy server and topics, TvOverlay listener and allowed senders | Each integration has its own disclosure for broker options, catch-up, or listener port and category |
| Status | Addresses, API, clients, latest card and audio | Playback, screen, connection and permission details |
| About | Version, privacy and receiver name | API port, identity, addresses and diagnostics |

Pickers wrap at the ends and move to the next legal step from custom API values. Volume and opacity step by five, times by 30 minutes and visible count by one. Duration includes until cleared, one-second steps below ten, five-second steps up to 60 and 30-second steps thereafter. The color pickers offer 12 colors; HTTP accepts any #RRGGBB.

### Ports and removal

The native listener defaults to TCP 7979; optional TvOverlay compatibility uses TCP 5001. Configure distinct ports in Herald. MQTT is outbound to your chosen broker (default TCP 1883). DNS-SD advertises the API port. ADB uses its own debugging port and can be disabled after setup.

Uninstalling removes Herald and its local settings and paired credentials. To remove the exemption first and uninstall:

```sh
adb -s "$serial" shell dumpsys deviceidle whitelist -co.tbrkr.herald
adb -s "$serial" uninstall co.tbrkr.herald
```

To revoke wireless debugging trust on the TV, open **Wireless debugging → Paired devices**, select the computer and choose **Forget**.

### Install with ADB

Enable developer options and debugging on the box. If using **Wireless debugging → Pair device with pairing code**, run `adb pair <box>:<pairing-port>` and enter the code at the prompt. Then use the **connection** port shown on the Wireless debugging screen. For a USB connection, approve the computer on the TV and use the serial listed by `adb devices`.

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
version=0.5.3
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
adb -s "$serial" install -r herald-0.5.3.apk
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
  jq -e '.app == "herald" and .api == 2 and .version == "0.5.3"'
```

For USB, use your connected serial and omit `adb connect`. Before the API guide’s bootstrap, run `adb -s "$serial" forward tcp:0 tcp:7979` and use `127.0.0.1:<returned-port>` as the address. After the pinned check, remove the forward with `adb -s "$serial" forward --remove tcp:<returned-port>`, substituting the actual port.
