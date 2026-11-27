# Herald privacy policy

Herald shows cards and plays announcements on your TV. You can use it without an account. Herald has no advertising, analytics or developer-operated service that receives your notifications.

## What others on the network can see

While receiving is on, devices on your network can discover the TV. A device that can reach Herald can read its name, identifier, model, version, pairing availability and public certificate fingerprints before pairing. LAN discovery advertises the service and port. The HTTPS endpoint `GET /v1/info` supplies the identity details without a code or token.

Other data goes only to clients you pair and to the Home Assistant, MQTT, ntfy and media servers you configure. Paired clients can send cards and announcements, read status and settings, and receive events such as a button press. The native API uses HTTPS. Compare the certificate fingerprint on the TV when pairing a client.

If you enable MQTT, your broker can receive identifiers, state and events. If you subscribe to ntfy, the server receives the topic names you request and any access token you configure. A server you use for pictures, audio or video receives the media request and sees the TV’s connection. These servers may be outside your network. Their operators control access and retention. Transport depends on your choices, including MQTT with or without TLS, private-address HTTP for ntfy and cleartext media URLs. The optional TvOverlay-compatible input also uses plain HTTP with a sender allowlist.

## What stays on the TV and how to remove it

Herald keeps the TV’s identity, settings, paired client names and token hashes on the device. Optional features also keep broker credentials, topic subscriptions and their access token, recent subscription positions and cached media. These let the TV recognize clients, reconnect to services and reuse media.

Choose **Setup → Stop receiving** to stop the receiver. To revoke a client, remove it under **Pairing & clients**. To erase Herald’s stored settings, credentials and cached files, clear its app data in Android settings or uninstall it. Manage copies already held by clients, Home Assistant or your servers separately, including retained MQTT messages.

The same policy is linked from **Privacy** in Setup and About. For questions, contact the maintainer through [Herald issues](https://github.com/iwinties/herald/issues). Issues are public. Leave private messages and credentials out of reports.
