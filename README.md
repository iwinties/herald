# Herald

Herald shows notifications over the app you’re watching on Android TV or Google TV. See who rang the doorbell, answer a reminder with the remote, or call everyone to dinner on TVs and Cast speakers.

![A Herald doorbell card over a TV scene with View camera and Dismiss buttons](docs/images/porch-card.png)

### Get started

1. **Install the TV app.** Download the APK from the [latest release](https://github.com/iwinties/herald/releases/latest) and follow the [sideloading instructions](docs/SETUP.md#sideload-the-tv-app). You can also [install with ADB](docs/SETUP.md#install-with-adb).
2. **Start receiving.** In **Herald → Setup**, allow cards over other apps, try **Test card**, then select **Start receiving**.
3. **Connect a sender.** For Home Assistant, [install the integration and pair your TV](docs/HOME_ASSISTANT.md#install). To send a message from your phone, [subscribe the TV to an ntfy topic](docs/NTFY.md). For your own scripts, start with the [first curl requests](examples/curl.md). When pairing, compare the fingerprint on the TV before entering the code.

| Connect with | Guide |
|---|---|
| Local HTTPS clients | [Routes, bounds and event stream](docs/API.md) |
| ntfy subscribers | [Topics, credentials and per-topic speech](docs/NTFY.md) |
| MQTT clients or HA MQTT discovery | [Topics and broker setup](docs/MQTT.md) |
| Home Assistant, including Cast speakers | [Install and pair](docs/HOME_ASSISTANT.md#install) · [Integration features](docs/HOME_ASSISTANT.md#what-the-integration-provides) · [Dashboard and automation examples](examples/home-assistant.md) · [REST commands](examples/ha-rest-command.yaml) |
| Box voice, Piper and other Home Assistant voices | [Voice setup and caching](docs/VOICE.md) |
| TvOverlay senders and migrating Android TV notifications | [Compatibility](docs/COMPAT.md) |

Tried Herald? [Tell us which TV you used and how far you got](https://github.com/iwinties/herald/issues/1). You can also [report a bug or request a feature](https://github.com/iwinties/herald/issues/new/choose).

[0.5.2 changes and upgrade notes](CHANGELOG.md#052) · [Build from source](CONTRIBUTING.md) · [Privacy policy](docs/PRIVACY.md) · [Apache License 2.0](LICENSE)
