# Everyday Herald automations

[Install and pair a box](../docs/HOME_ASSISTANT.md#add-a-box) first. Replace the sample entity IDs with yours. For each `<box-device-id>`, select your TV in the Herald action's device picker in the visual editor. Switch to YAML to see the device registry ID you can reuse. Leaving both `device_id` and `speakers` out of an announcement sends it to **all** paired, loaded boxes and configured speakers.

## Show the porch camera when the doorbell rings

Paste this into a new automation's YAML editor. When the doorbell sensor turns on, Herald shows a still image over the current TV app for 20 seconds. For live video, replace `image:` with `video:` and keep the same `camera:` line. Live video requires a camera that provides a stream the TV can reach and play.

```yaml
alias: Show the porch when someone rings
triggers:
  - trigger: state
    entity_id: binary_sensor.doorbell
    from: "off"
    to: "on"
actions:
  - action: herald.show_card
    data:
      device_id: ["<box-device-id>"]
      id: porch_doorbell
      category: doorbell
      title: Someone is at the door
      image:
        camera: camera.front_porch
      look: glass
      style:
        duration: 20
      announce: false
mode: restart
```

## Call everyone to dinner from a dashboard

In your dashboard editor, add a **Manual** card and paste this YAML. Tapping the button shows and speaks the message on the selected TV and speaks it on the kitchen Cast speaker. For TV only, remove the `speakers` line. [Configure Cast speakers](../docs/HOME_ASSISTANT.md#configure-cast-speakers) · [Home Assistant button card](https://www.home-assistant.io/dashboards/button/)

```yaml
type: button
name: Dinner is ready
icon: mdi:silverware-fork-knife
tap_action:
  action: perform-action
  perform_action: herald.show_card
  data:
    device_id: ["<box-device-id>"]
    speakers: [media_player.kitchen]
    title: Dinner is ready
    message: Come to the table.
    announce: true
```

## Play a recorded voice note

Save a short recording as `dinner.wav` under **Media → My media**. Try this call in **Developer tools → Actions** in YAML mode. The TV plays your original voice. To have Herald read text aloud instead, replace the `media` line with `message: Dinner is ready.`

```yaml
action: herald.announce
data:
  device_id: ["<box-device-id>"]
  media: media-source://media_source/local/dinner.wav
  chime: false
```

**Can I record directly from my dashboard?** Herald can play recorded audio. Microphone capture requires a separate dashboard frontend with microphone permission. That frontend can call `herald.announce` with `audio` containing the base64-encoded recording (up to 2 MiB decoded, without a data URL prefix). Send exactly one of `message`, `media` or `audio`. The built-in dashboard button above sends a preset message.

## Keep a garage status chip visible until the door closes

Paste this into a new automation's YAML editor. The same chip ID lets the close event remove exactly the indicator that the open event created. The garage contact in this example reports `on` when open and `off` when closed.

```yaml
alias: Show garage status on the TV
triggers:
  - trigger: state
    entity_id: binary_sensor.garage_door
    to: "on"
    id: opened
  - trigger: state
    entity_id: binary_sensor.garage_door
    to: "off"
    id: closed
actions:
  - if:
      - condition: trigger
        id: opened
    then:
      - action: herald.set_chip
        data:
          device_id: ["<box-device-id>"]
          id: garage
          text: Garage open
          position: bottom_right
          expires: 0
    else:
      - action: herald.clear_chip
        data:
          device_id: ["<box-device-id>"]
          id: garage
mode: queued
```

For an interactive reminder, see the [card with remote buttons](../docs/HOME_ASSISTANT.md#actions) and the [automation that handles a button press](../docs/HOME_ASSISTANT.md#events).
