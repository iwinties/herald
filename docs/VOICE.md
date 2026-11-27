# Choosing a voice

## Use the box voice

Herald can speak with Android's installed text-to-speech engine without any Home Assistant voice setup. Open **Sound & voice** on the TV and select **Voice test (TV)** for HDMI or **Voice test (box speaker)** for the built-in speaker. If the selected route is absent, the test reports `output not available`. Ordinary announcements default to `auto`, which selects TV when available and otherwise the box speaker. Night hours can restrict the box speaker.

To hear a card, send `announce: true` with a title or message. A category can also enable announcements for its own cards. To use one voice across multiple boxes and Cast speakers, configure a TTS entity in Home Assistant.

## Set up local speech with Piper

**Home Assistant OS.** Install and start the Piper app (add-on) under **Settings → Apps**. Open the app configuration to choose a voice for your language. Under **Settings → Devices & services**, configure the discovered Piper service through **Wyoming Protocol**. Locate the TTS entity that this creates. [Home Assistant local voice setup](https://www.home-assistant.io/voice_control/voice_remote_local_assistant/)

**Docker.** Run the Piper Wyoming server on a host reachable by Home Assistant. This example persists downloaded voices in a Docker volume and selects a US English model.

```sh
docker run -d --name wyoming-piper --restart unless-stopped \
  -p 10200:10200 -v piper-data:/data \
  rhasspy/wyoming-piper --voice en_US-lessac-medium
```

In **Settings → Devices & services → Add integration → Wyoming Protocol**, enter the Docker host's LAN address and port `10200`. Use the resulting entity ID, for example `tts.piper`. Choose a different supported voice in the Piper app or via the container's `--voice` argument. Try a short phrase with the TTS entity before saving it as Herald's default voice. [Piper container and voices](https://github.com/OHF-Voice/wyoming-piper#docker-image) · [Wyoming integration](https://www.home-assistant.io/integrations/wyoming/)

## Set Herald's default voice

Add **Herald → Herald settings** once in **Devices & services**. On that shared entry choose **Configure → Default voice**. Select the TTS entity and optionally enter its language and engine-specific options. Saving with the entity blank clears the three saved voice fields. Speaker settings have their own menu.

| Call | How Herald selects speech |
|---|---|
| Explicit `tts` | Use that entity with the call's own language and options. |
| No `tts`, saved default voice | Use the saved entity. The call can replace the saved language and override individual option keys. |
| Neither engine specified | Boxes speak locally. Targeted Cast speakers use Home Assistant's default TTS provider. |

This selection applies to `herald.announce` with a message and `herald.show_card` with explicit `announce: true`. Media, inline audio and `play_sound` use their supplied audio. To try an explicit voice on a single box:

```yaml
action: herald.announce
data:
  device_id: ["<box-device-id>"]
  message: Your update is ready.
  tts: tts.piper
```

Other HA TTS engines work through their own `tts.*` entities. Select the entity and use the languages, voices and options that engine supports. A cloud engine sends speech input to its provider.

## Caching and fallback

HA derives a clip key from the TTS entity ID, language, sorted options and spoken text. It checks every targeted box before generating audio. Cache hits play the stored clip. If a box needs a clip or a speaker needs audio, HA generates it at most once for the call, then uploads to each missing box. A box holds up to 200 clips. Repeating a phrase with the same voice settings can reuse the clip even with a fresh request ID.

If generation fails, boxes with a cache hit still use it and boxes with a miss receive text for Android speech. A failed cache check or upload is reported for that box. Cast needs generated audio and a reachable internal HA URL. The box voice fallback applies only to boxes. For playback timing, target selection and per-target errors, see [Home Assistant actions](HOME_ASSISTANT.md#actions).
