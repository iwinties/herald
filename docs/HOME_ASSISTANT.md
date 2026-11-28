# Herald for Home Assistant

After pairing, try the [everyday examples](../examples/home-assistant.md) for a doorbell camera, a dinner announcement, a recorded voice note and a garage status chip.

### What the integration provides

| In Home Assistant | What you can do |
|---|---|
| TV cards and status chips | Send a plain notification with the box’s notify entity, show a snapshot or live camera, display a remote-selectable button, or keep a status chip on screen until cleared. Save and delete custom Looks. |
| Announcements and sounds | Speak text, play a recording or a sound, or show and speak a card together. Target individual TVs and Cast speakers. Set a shared TTS voice and per-speaker volume and quiet hours. |
| Events and device status | Trigger an automation from a remote button press or dismissal. See screen state, audio activity, overlay permission, quiet seconds, last card and last audio for each box. |
| Settings and recovery | Stop box playback and its queue, restore one of the last three distinct settings snapshots, or re-pair a box when its credentials change. |

For dashboard use, the [one-tap announcement button](../examples/home-assistant.md#call-everyone-to-dinner-from-a-dashboard) works with the standard Home Assistant dashboard. The integration also accepts [recorded voice notes](../examples/home-assistant.md#play-a-recorded-voice-note). A microphone recorder is a separate frontend component.

### Install

Install Herald on both the TV and Home Assistant. Each TV box and the HA integration must run the same Herald version. This integration requires Home Assistant 2026.10 or newer.

1. [Install Herald on the TV and enable receiving](SETUP.md#sideload-the-tv-app).
2. **In Home Assistant**, choose the instructions for your version below. Install the integration version that matches the TV APK, then restart Home Assistant.
3. [Pair the box and send the first card](#add-a-box). You can [tell us which TV worked or where you got stuck](https://github.com/iwinties/herald/issues/1).

**HA 2026.11 and newer — Marketplace.** [Open Herald in your Marketplace](https://my.home-assistant.io/redirect/hacs_repository/?owner=iwinties&repository=herald&category=integration), or go to **Settings → Marketplace → Add from link**, enter `https://github.com/iwinties/herald`, and select **Add**. Connect GitHub if prompted. If asked for the type, select **Integration**. Open **Herald** and choose **Install**.

**HA 2026.10 — HACS.** In HACS, open the menu (⋮) → **Custom repositories**, enter the same repository URL, and choose **Integration**. Open **Herald** and select **Download**.

**Manual installation.** Copy the `custom_components/herald` folder from the matching release tag into `<config>/custom_components/`. The manifest should be at `<config>/custom_components/herald/manifest.json`. Restart Home Assistant.

[Upgrade instructions for existing installations](../CHANGELOG.md#054).

### Add a box

1. On the TV, open **Herald → Setup** and check that it shows a receiving address.
2. In **Settings → Devices & services**, select the discovered TV by name and address. For manual setup, choose **Add integration → Herald → Add a Herald box** and enter the host and port separately.
3. Wake the TV and continue. Compare the fingerprint on both screens, then enter the six-digit code. Home Assistant saves the pairing for future calls.

A certificate change or revoked token stops authenticated requests and starts reauthentication. Compare the fingerprint and enter a fresh code to resume.

If discovery sees the known UUID at a different address or port, open the repair issue. Home Assistant verifies the stored certificate and checks the UUID before moving the paired entry.

Home Assistant's `rest_command` example uses `verify_ssl: false` and cannot pin a self-signed certificate. A different device on the network could receive the token. Use the Herald integration for certificate pinning.

Each box has a notify entity for plain cards, binary sensors screen_on, sound_playing and overlay_allowed (diagnostic), sensors quiet_seconds, last_card and last_audio, and a button event entity. For a plain notification:

```yaml
action: notify.send_message
target:
  entity_id: notify.example_box
data:
  title: Update
  message: Ready to install
```

### Configure Cast speakers

Choose **Add integration → Herald → Herald settings** to add the shared settings entry. Open **Configure → Cast speakers** and select media players supporting both play_media and volume_set. Set each announcement volume (0–100, default 50), local night hours (22:00–07:00 by default) and night policy (urgent, always or never). Use Configure on that entry to edit these settings.

Set a reachable **internal Home Assistant URL** under **Settings → System → Network**. Each speaker fetches a signed audio URL valid for 300 seconds. A missing internal URL yields `stage: prepare`, `error: no internal url`. Announcements use a chime, 200 ms pause and voice with a 150 ms fade-in. HA uses ffmpeg and caches up to 50 files under its storage directory. Sounds play without the chime.

`announce`, `play_sound` and `show_card` with `announce: true` follow the same target rules. Calls with **no** targets go to every loaded box and every configured speaker. Choose TVs by their Herald notify entity (`entity_id`) or device (`device_id`), and Cast speakers by `speakers`. Selecting any target limits delivery to those selections. Empty lists are invalid. A named, unconfigured speaker uses volume 50. Other Herald actions default to all loaded boxes.

A speaker already playing skips a nonurgent request. During night hours, `urgent` requires urgent=true, `never` silences all calls, and `always` permits playback subject to the playing check. One request runs per speaker with up to five waiting. The speaker temporarily uses its configured volume and restores the observed volume (or last known volume) after playback, then turns off again if it was off. If no volume was observable it restores the off state without guessing. Playback waits at most clip length + 15 s, capped at 120 s. Responses can report skipped playing, quiet hours, busy or duplicate. Show/card, box output and box volume apply to boxes only.

### Motion and tickers

Open **Style** in **Show card** or **Save Look** to choose motion and screen edge margin. Omit a field to inherit it. For a full-width, message-only ticker use the On Air Look and leave the title empty. [Ticker and camera examples](../examples/home-assistant.md#run-a-full-width-ticker-along-the-bottom).

### Actions

[Show a live porch camera with Expand and Dismiss when the doorbell rings](../examples/home-assistant.md#show-the-porch-camera-when-the-doorbell-rings).

The following are independent action examples. Replace the sample device and entity identifiers with yours. For announcements and sounds generate one request_id per logical event and reuse the same ID on retry. The example IDs illustrate one event each.

```yaml
action: herald.show_card
data:
  device_id: ["<box-device-id>"]
  id: update
  title: Update available
  message: Acknowledge this update or dismiss it for now.
  style:
    duration: 30
    button_shape: pill
    button_spacing: roomy
    button_style: outline
    button_focus_background: "#536878"
    button_focus_text_color: "#F3F6F8"
  buttons:
    - id: ack
      label: Acknowledge
      action: event
      style: {background: "#245C46", text_color: "#FFFFFF"}
    - id: later
      label: Later
      action: dismiss
```

Pass a named `look` and override individual fields with `style`. For stills, use image.camera (HA snapshot), image.media, image.path (allowlisted), image.url, image.data or image.key. HA pushes local image bytes (up to 2 MiB) under a content-derived key after checking the cache. For video choose video.url or video.camera; the latter must supply a stream source. Both support muted, tcp and timeout. Set `style.size` to change the displayed card width. To change the encoded resolution or bandwidth, choose a different source URL or camera entity if your camera offers one.

For a dated still while video connects, set `video.preview_camera` to a cached snapshot camera such as a ring-mqtt snapshot entity. The entity must publish a numeric `timestamp` in Unix seconds. The TV fetches a recent still from HA in parallel with playback and shows its time in the TV’s time zone. For ring-mqtt this is when the bridge last updated the snapshot. Missing, unavailable or older-than-two-minute snapshots leave the live card usable. The TV must be able to reach HA’s internal URL. [Porch example](../examples/home-assistant.md#show-the-porch-camera-when-the-doorbell-rings).

To show and speak the same card, set `announce: true`. Omit it to let each box use its category default, or set it to false to silence speech even for a speaking category. Only explicit true enables Cast playback or HA voice preparation for this action.

```yaml
action: herald.show_card
data:
  device_id: ["<box-device-id>"]
  speakers: [media_player.example_speaker]
  look: glass
  title: Update ready
  message: Press OK to continue
  announce: true
  announce_timing: with_voice
  chime: false
  request_id: notice-1
response_variable: delivery
```

HA speaks the title followed by the message, or uses `announce_text` as a replacement. Blank speech (including a media-only card without an override) fails with `text is empty` before sending. The same normalized text feeds TTS, the clip key, the speaker and the box fallback. Set `announce_timing: now` to post immediately. Box options include `volume` (0–100), `output`, `urgent`, `duck` and `chime`. Cast uses its configured per-speaker volume and HA chime. Setting `chime: false` disables the chime for both. If a box receives the request but its reply is lost, HA retries once with the same request ID.

For a spoken card, a successful box row contains the speech job (and duplicate/outcome on a replay), plus the HA stage, status and error fields. A nested audio refusal is reported as a per-box request error even if the card was displayed. A box that omits the speech answer returns `stage: request`, `status: 200`, `error: unsupported`. Match the job to `announce.ended` for the playback result.

```yaml
action: herald.clear_card
data: {id: update}
```

Choose exactly one of id, category or all: true.

```yaml
action: herald.set_chip
data: {id: progress, text: Ready, icon: 'emoji:✅', look: pill, position: bottom_right, priority: high, expires: 60}
```

Chip `look` and `position` override the box display defaults. Icons accept built-in names, `emoji:<text>` and `image:<cached-key>`.

```yaml
action: herald.clear_chip
data: {id: progress}
```

```yaml
action: herald.save_look
data: {id: notice, name: Notice, style: {corner: large, border: thin}}
```

```yaml
action: herald.delete_look
data: {id: notice}
```

These actions use box targets. Supply a custom ID matching `^[a-z0-9_]{1,32}$`. Save requires a visual `style` object and replaces the complete look. Built-in IDs yield `built-in look`.

```yaml
action: herald.announce
data:
  device_id: ["<box-device-id>"]
  speakers: [media_player.example_speaker]
  message: Update ready
  request_id: update-1
  show: true
  from: Monitor
  urgent: false
response_variable: delivery
```

Choose one of `message`, `media` or base64 `audio`. With `show: true` and a message, HA creates a card in category `announcement` with that message and optional `from`, then merges your `card` over those defaults. For example, a supplied `card.message` changes the display while the outer message supplies the speech. Box playback accepts `volume`, `output`, `chime`, `urgent` and `duck`.

For message speech, explicit `tts` uses only the call's language and options. If it is omitted, **Herald settings → Configure → Default voice** supplies the entity, language and options. Call language wins and call options override matching saved keys. Clear the saved entity to remove the voice settings. With neither entity specified, boxes receive text and speakers use HA's default TTS provider.

HA checks each box for a clip keyed by engine, language, sorted options and message. Cache hits reuse the clip. HA generates speech once for misses or speakers and uploads to missing boxes. A generation failure falls back to box speech for misses. Check, upload and send failures are per-box errors. Media is cached by sanitized source name and digest. Inline audio plays once. [Choosing a voice and configuring Piper](VOICE.md)

```yaml
action: herald.play_sound
data:
  media: media-source://media_source/local/tone.wav
  speakers: [media_player.example_speaker]
  request_id: tone-1
```

Choose media or an allowlisted path, up to 4 MiB. Boxes check the sound clip key and upload only on a confirmed miss. Box volume, output and urgent are supported. Speaker playback uses the configured speaker volume.

Both audio actions generate a request ID if omitted. A lost box reply is retried once with the original ID. Speakers persist each ID immediately before play_media and retain it through uncertain delivery for 600 seconds, including across HA restarts. Without a response variable, a call raises an error if every selected target fails and logs any partial failures. Calls that request response data receive individual target results. Optional response data is keyed by box registry ID or speaker entity ID. Box rows contain ok, stage (prepare, check, upload or request), status and error plus response fields. A box's 409 output not available includes terminal: true.

```yaml
action: herald.stop
data: {device_id: ["<box-device-id>"]}
```

Stops the box's current job and drains its queue.

```yaml
action: herald.restore_settings
data: {device_id: ["<box-device-id>"]}
```

Select a snapshot by its ISO timestamp in the optional snapshot field, or omit it to use the newest. HA fetches settings on setup and settings.changed. It keeps the last three distinct snapshots per box, moving a repeated snapshot to newest with its original ID. Restore omits read-only fields and preserves the stored broker password. Diagnostics redact credentials and strip URL user info, queries and fragments.

### Events

HA polls status every 30 seconds and long-polls events with wait=25. It starts at the latest sequence, backs off 5–60 seconds on failure, and refreshes on a gap, reset or changed stream. Each `herald_event` includes `device_id` (HA registry ID), `entry_id`, native `seq`, `time` (epoch ms), `type` and that type's fields. A card.button includes id and button. A dismissed card.ended includes id and reason. The button entity maps these to event types button and dismissed with a card attribute, plus button when selected. Button/dismiss events over 30 s old update last_card without firing the action. [Event fields](API.md#get-v1events)

```yaml
triggers:
  - trigger: event
    event_type: herald_event
    event_data: {type: card.button, id: update, button: ack}
actions:
  - action: herald.clear_chip
    data:
      device_id: ["{{ trigger.event.data.device_id }}"]
      id: progress
```
