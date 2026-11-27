# Changelog

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
