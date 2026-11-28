# Everyday Herald automations

[Install and pair a box](../docs/HOME_ASSISTANT.md#add-a-box) first. Replace the sample entity IDs with yours. Use the Herald notification entity for your TV wherever you see `notify.living_room_tv`. Leaving TV and speaker targets empty sends an announcement to **all connected TVs and configured speakers**.

## Run a full-width ticker along the bottom

Send a message without a title to use the whole ticker. On Air supplies the placement, zero edge margin and overflow scrolling. This example overrides just the exit.

```yaml
action: herald.show_card
data:
  entity_id: notify.living_room_tv
  look: on-air
  message: >-
    Tonight at the planetarium, the winter sky tour begins at seven. Doors open early
    for the telescope demonstration. Meet in the main hall afterward for the observing schedule.
  style:
    duration: 30
    ticker_speed: slow
    transition_out: fade
```

## Show the porch camera when the doorbell rings

When someone rings, the TV shows the porch over the current program. Choose **Expand** beneath the picture. The camera fills the screen, and **Minimize** stays selected in a small row over the footage. Press OK again to put the playing video back in its corner. **Back** closes the card, and **Dismiss** closes it directly from the corner. The stream is muted so the program audio can continue. [Watch the illustrated delivery demo](../docs/images/porch-delivery.mp4).

```yaml
alias: Show the live porch camera when the doorbell rings
triggers:
  - trigger: state
    entity_id: binary_sensor.doorbell
    from: "off"
    to: "on"
actions:
  - action: herald.show_card
    data:
      entity_id: notify.living_room_tv
      id: porch_doorbell
      category: doorbell
      title: Doorbell
      message: Someone is at the front porch.
      look: glass
      style:
        duration: 30
        size: large
        transition_expand: morph
        transition_minimize: morph_fade
      video:
        camera: camera.front_porch
        preview_camera: camera.front_porch_snapshot
        muted: true
      buttons:
        - {id: expand, label: Expand, action: expand}
        - {id: dismiss, label: Dismiss, action: dismiss}
      announce: false
mode: restart
```

Replace the doorbell sensor, camera and TV with your entities. The optional `preview_camera` is a cached snapshot camera that supplies a Unix-seconds `timestamp` attribute. A recent still appears with its time until the first live frame. Omit this line if your source has no timestamp. Here, `size: large` makes the corner card 44% of the screen width. The camera must supply a stream URL the TV can reach. For a snapshot-only camera, use `image: {camera: camera.front_porch}` in place of the `video` block and remove the Expand action. The docs demo plays an illustrated delivery through Herald’s video player.

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
    entity_id: notify.living_room_tv
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
  entity_id: notify.living_room_tv
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
          entity_id: notify.living_room_tv
          id: garage
          text: Garage open
          position: bottom_right
          expires: 0
    else:
      - action: herald.clear_chip
        data:
          entity_id: notify.living_room_tv
          id: garage
mode: queued
```

## Mark a reminder done from the TV remote

Create a Toggle helper named **Laundry collected** and use its entity ID below. When the dryer finishes, have its automation reset the helper with `input_boolean.turn_off` and send this card:

```yaml
action: herald.show_card
data:
  entity_id: notify.living_room_tv
  id: laundry
  category: reminder
  look: paper-note
  title: The dryer is done
  message: Have you collected the laundry?
  buttons:
    - {id: done, label: Mark done, action: event}
    - {id: later, label: Later, action: dismiss}
```

Paper Note supplies the warm card and outlined actions. **Later** simply closes this card.

Add this automation to handle the reply. Select the **Button** event entity on the same Herald TV and replace the sample helper ID. Only **Mark done** on this reminder turns on the helper.

```yaml
alias: Mark laundry collected from the TV
triggers:
  - trigger: state
    entity_id: event.living_room_tv_button
    not_to: [unavailable, unknown]
conditions:
  - condition: template
    value_template: >-
      {{ trigger.to_state.attributes.get('event_type') == 'button'
         and trigger.to_state.attributes.get('card') == 'laundry'
         and trigger.to_state.attributes.get('button') == 'done' }}
actions:
  - action: input_boolean.turn_on
    target:
      entity_id: input_boolean.laundry_collected
mode: single
```
