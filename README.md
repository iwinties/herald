# Herald

Herald shows notifications over the current app on Android TV and Google TV. A card can display text, a still image or live video and accept a response from the remote. Status chips stay visible between notifications. Announcements use the TV output or the box speaker. A single request can show a card and speak its title and message. Choose one of six built-in looks, copy a custom look or set a category default on the TV. Home Assistant can prepare a shared voice for boxes and Cast speakers.

### Get started

1. Install the APK from the [latest release](https://github.com/iwinties/herald/releases/latest). Open **Herald → Setup** with the remote, allow cards over other apps, try a test card and choose **Start receiving**. [TV setup and device support](docs/SETUP.md)
2. Compare the certificate fingerprint on the TV, then enter the short-lived pairing code in your client. [First curl requests](examples/curl.md)
3. Open Herald on the TV to choose appearance, sound and night behavior per category. [Card styles](docs/STYLES.md)

| Connect with | Guide |
|---|---|
| Local HTTPS clients | [Routes, bounds and event stream](docs/API.md) |
| ntfy subscribers | [Topics, credentials and per-topic speech](docs/NTFY.md) |
| MQTT clients or HA MQTT discovery | [Topics and broker setup](docs/MQTT.md) |
| Home Assistant, including Cast speakers | [Install with HACS, integration and actions](docs/HOME_ASSISTANT.md) · [REST command examples](examples/ha-rest-command.yaml) |
| Box voice, Piper and other Home Assistant voices | [Voice setup and caching](docs/VOICE.md) |
| TvOverlay senders and migrating Android TV notifications | [Compatibility](docs/COMPAT.md) |

### Build and test

Run `tools/build.sh` with Docker to build the SDK image, run the JVM tests and package and sign `out/herald.apk`. Dependencies are pinned in `tools/deps.lock`; the APK build uses the prepared image offline. The default Docker command is `sudo docker`. Use `DOCKER=docker` for a user with Docker access. Set `HERALD_KEYS` to a private directory for your signing identity; the default is `~/.config/herald`. Updates require the same signing identity. Run `tests/ha/run.sh` for the offline Home Assistant suite.

Maintainers follow [docs/RELEASING.md](docs/RELEASING.md): `tools/release.sh build` at a clean commit to create a versioned APK, its checksum, a separately signed Play AAB and a local release manifest in out/. Keep those files together. After checking that candidate, `publish` tags the recorded commit and attaches the same APK and checksum to a GitHub release; `verify` downloads the asset and checks its hash, certificate and remote tag. Publishing uses Git and the authenticated GitHub CLI. Pushes run CI. Installation on a device is manual.

[0.4.0 upgrade notes](CHANGELOG.md#040) · [Privacy policy](docs/PRIVACY.md) · [Apache License 2.0](LICENSE)
