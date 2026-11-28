# Changelog

## 0.5.0

- Preview a Look at its actual screen position. Press OK on its thumbnail for a full-size preview, then press OK or Back to return. Each settings screen remembers its own **More options** choice. MQTT, ntfy and TvOverlay are together under **Integrations**. The new megaphone icon identifies Herald on the TV and in Home Assistant.
- Live camera cards show video at full brightness and clip it inside the rounded frame. The border and shadow stay visible. Video cards receive pointer input inside their window. Touches outside a corner window can reach the underlying app.
- Customize action buttons with square, rounded or pill shapes, compact to roomy spacing, and separate fill and text colors for normal and focused buttons. Try them with **Preview buttons**. [Button styling](docs/STYLES.md#buttons-and-playback)
- Home Assistant can guide you through a box address change using its saved certificate and identity. The integration includes Marketplace validation and updated installation and feedback links.
- **Upgrading from 0.4.0:** Install the APK over the existing app on each TV. Update Herald in HACS or Marketplace and restart Home Assistant. Both settings and pairings carry over. For older installations, follow the [package migration notes](#040).

## 0.4.0

- The Android package is now `co.tbrkr.herald`. Android requires a clean install. On each TV, uninstall the old Herald in **Settings → Apps**, or run `adb -s '<serial>' uninstall io.github.iwinties.herald`. Install 0.4.0, open **Herald → Setup** and enable receiving again.
- The new installation has a new device ID and certificate. Settings and pairings do not carry over. Update Herald to 0.4.0 in HACS, restart Home Assistant, then delete the old Herald box entry and add the box again. [Pair a box](docs/HOME_ASSISTANT.md#add-a-box)

## 0.3.0

- **Upgrading from 0.2.0:** Update every box in place first, then update the Herald integration in HACS and restart Home Assistant. A 0.2 integration cannot reach a 0.3 box. The 0.3 integration pins each upgraded box’s certificate on first contact and reuses its existing pairing.
- The native API is HTTPS-only. Scripts must verify the box fingerprint and pin the public key on pairing and subsequent requests. [Pairing and device identity](docs/API.md#pairing-and-device-identity)
- Fresh installs start with receiving off. Open **Setup** with the remote, grant overlay access, try a test card and choose **Start receiving**. A previously used 0.2 box keeps receiving on after upgrade. Herald no longer requests notification permission.
- Subscribe to up to four ntfy topics on one server without Home Assistant. Cards are silent by default. Each topic can opt in to speaking priority-5 messages. [ntfy setup](docs/NTFY.md)
- The Play-ready TV build produces a signed Android App Bundle alongside the GitHub APK and includes a named TV banner. [Privacy policy](docs/PRIVACY.md)

## 0.2.0

- Cards can announce their title and message, use custom speech, and appear immediately or at voice start. Request IDs support safe spoken-card retries.
- Six built-in looks, custom named looks, per-category appearance and speech defaults, new style controls, emoji and cached picture icons. Chips support looks, separate positions and high-priority pulses.
- The TV app adds look previews, real test cards, category tweaks, Status and voice tests. The pairing overlay shows grouped digits and a countdown. Focused cards leave media and volume keys to Android.
- Home Assistant supports spoken cards on boxes and Cast speakers, custom look actions and a default TTS voice. MQTT publishes state on change or refresh and reports quiet time on a separate retained topic.
- Settings use schema 2. Installation is clean and requires removing 0.1.0 first. See the [0.2.0 support matrix](docs/SETUP.md#020-support-matrix) for verification status.
- First public release. The Home Assistant integration installs through HACS as a custom repository ([install](docs/HOME_ASSISTANT.md#install)); maintainers release with [docs/RELEASING.md](docs/RELEASING.md).

## 0.1.0

- Local Android TV service for styled cards, images, live RTSP/HLS/DASH video, remote-control buttons and persistent status chips.
- Speech, clips and one-shot audio with output selection, quiet hours and request IDs for retries.
- Voice playback is prepared before the chime and scheduled for 200 ms after its end. Job timestamps record when chime and voice playback start.
- RTSP video uses RTP over TCP by default, including TvOverlay-compatible notifications. Native cards can set `video.tcp` to false to try UDP first.
- Paired HTTP API, long-poll events, DNS-SD, optional MQTT commands and HA discovery, and an allowlisted TvOverlay compatibility listener.
- Home Assistant integration with native actions, settings snapshots, Cast announcements and sounds.

### Support matrix

| Target | Planned coverage | Status |
|---|---|---|
| Android 14, onn 4K Pro | Physical device check | Passed: 46 checks on the 0.1.0 build (2026-11-26) |
| Android TV 16 | Emulator only | Passed: six checks on the 0.1.0 build in the emulator |
| Non-zero system-bar insets | Not device-verified | Not verified: the onn and the emulator both report zero insets |
| Cast speakers | Offline tests only, not yet tested on a real speaker | Offline tests pass; not yet tested on a real speaker |
