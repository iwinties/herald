# Changelog

## 0.5.4

- **Camera previews.** Show a recent timestamped snapshot while live video connects. Home Assistant can use a cached camera with `video.preview_camera`. The first live frame replaces the still. [Set up a doorbell camera](examples/home-assistant.md#show-the-porch-camera-when-the-doorbell-rings).
- **Camera controls.** Actions fit their labels beneath a corner video. Expand fills the screen with the playing camera and keeps Minimize selected in a small floating row. Back closes the card at either size. [Watch the updated demo](docs/images/porch-delivery.mp4).
- **Card motion.** Choose entrance, exit, attention, expand and minimize effects for a Look or category, or override them on one card. Options include directional slides, fades, scale effects and instant transitions.
- **On Air ticker.** The built-in banner runs flush along the bottom. Long messages scroll at your chosen speed, and short messages stay still. Omit the title for a message-only ticker. Other cards can set their own screen-edge margin. [See motion and ticker settings](docs/STYLES.md#card-motion-and-the-on-air-ticker).
- **Upgrade from 0.4.0 or later.** Install the signed APK over the existing TV app, update Herald to 0.5.4 in HACS, and restart Home Assistant. Existing settings and pairings carry over. Earlier versions need the [package migration](#040).

## 0.5.3

- **Expand and Minimize** switch between the camera card and full screen while the video keeps playing. Back dismisses the card at either size. [Watch the porch delivery demo](docs/images/porch-delivery.mp4) or [use the matching Home Assistant automation](examples/home-assistant.md#show-the-porch-camera-when-the-doorbell-rings).
- **Paper Note**, **Night Shift** and **On Air** join the built-in Looks. Buttons now use a muted blue-gray selection with a visible focus outline. Choose Solid, Outline, Underline or Raised with any button shape. [Browse the Looks and buttons](docs/STYLES.md#named-looks).
- Classic gains a soft drop shadow. Status chips keep their chosen colors and have more space around the icon and label.
- **Upgrade from 0.4.0 or later.** Install the signed APK over the existing TV app, update the Herald integration to 0.5.3 in HACS or Marketplace, then restart Home Assistant. Existing pairings and settings carry over. For earlier versions, follow the [package migration](#040).

## 0.5.2

- Setup shows the TV address and the next pairing step. Home Assistant identifies discovered TVs by name and address, explains pairing refusals and accepts a code started on the TV after you compare the fingerprint.
- Pick TVs by notification entity or device in Home Assistant. Actions offer clearer labels, common choices and feedback when delivery fails.
- On TV, color settings show swatches. Removing a paired client, deleting a Look or category, and resetting a category now ask for confirmation.
- Try the [remote-button reminder](examples/home-assistant.md#mark-a-reminder-done-from-the-tv-remote) to let someone mark laundry collected without leaving the TV.
- **Upgrade from 0.4.0 or later:** Install the signed APK over the existing TV app, update Herald to the same version in HACS or Marketplace, then restart Home Assistant. Your settings and pairings carry over. For an earlier TV installation, follow the [package migration](#040).

## 0.5.1

- Cards and status chips cast a soft shadow that follows their rounded corners. The existing Soft and Strong settings control its depth, and the shadow leaves translucent cards and camera video clear.
- Interactive cards show the remote’s Back button as a small keycap beside “to close.”
- Try four [Home Assistant examples](examples/home-assistant.md) for a doorbell camera, a dinner announcement, recorded voice playback and a garage status chip.
- **Upgrading from 0.5.0:** Install the APK over Herald on each TV, update the integration in HACS or Marketplace, then restart Home Assistant. Existing settings and pairings carry over.

## 0.5.0

- Preview a Look at its actual screen position. Press OK on its thumbnail for a full-size preview, then press OK or Back to return. Each settings screen remembers its own **More options** choice. MQTT, ntfy and TvOverlay are together under **Integrations**. The new megaphone icon identifies Herald on the TV and in Home Assistant.
- Live camera cards show video at full brightness and clip it inside the rounded frame. The border and shadow stay visible. Video cards receive pointer input inside their window. Touches outside a corner window can reach the underlying app.
- Customize action buttons with square, rounded or pill shapes, compact to roomy spacing, and separate fill and text colors for normal and focused buttons. Try them with **Preview buttons**. [Button styling](docs/STYLES.md#buttons-and-playback)
- Home Assistant can guide you through a box address change using its saved certificate and identity.
- **Upgrading from 0.4.0:** Install the APK over the existing app on each TV. Update Herald in HACS or Marketplace and restart Home Assistant. Both settings and pairings carry over. For older installations, follow the [package migration notes](#040).

## 0.4.0

- The Android package is now `co.tbrkr.herald`. Android requires a clean install. On each TV, uninstall the old Herald in **Settings → Apps**, or run `adb -s '<serial>' uninstall io.github.iwinties.herald`. Install 0.4.0, open **Herald → Setup** and enable receiving again.
- The new installation has a new device ID and certificate. Settings and pairings do not carry over. Update Herald to 0.4.0 in HACS, restart Home Assistant, then delete the old Herald box entry and add the box again. [Pair a box](docs/HOME_ASSISTANT.md#add-a-box)

## 0.3.0

- **Upgrading from 0.2.0:** Update every box in place first, then update the Herald integration in HACS and restart Home Assistant. A 0.2 integration cannot reach a 0.3 box. The 0.3 integration pins each upgraded box’s certificate on first contact and reuses its existing pairing.
- The native API is HTTPS-only. Scripts must verify the box fingerprint and pin the public key on pairing and subsequent requests. [Pairing and device identity](docs/API.md#pairing-and-device-identity)
- Fresh installs start with receiving off. Open **Setup** with the remote, grant overlay access, try a test card and choose **Start receiving**. A previously used 0.2 box keeps receiving on after upgrade. Herald no longer requests notification permission.
- Subscribe to up to four ntfy topics on one server without Home Assistant. Cards are silent by default. Each topic can opt in to speaking priority-5 messages. [ntfy setup](docs/NTFY.md)
- Herald has a named banner in the TV launcher. [Privacy policy](docs/PRIVACY.md)

## 0.2.0

- Cards can announce their title and message, use custom speech, and appear immediately or at voice start. Request IDs support safe spoken-card retries.
- Six built-in looks, custom named looks, per-category appearance and speech defaults, new style controls, emoji and cached picture icons. Chips support looks, separate positions and high-priority pulses.
- The TV app adds look previews, real test cards, category tweaks, Status and voice tests. The pairing overlay shows grouped digits and a countdown. Focused cards leave media and volume keys to Android.
- Home Assistant supports spoken cards on boxes and Cast speakers, custom look actions and a default TTS voice. MQTT publishes state on change or refresh and reports quiet time on a separate retained topic.
- Settings use schema 2. Installation is clean and requires removing 0.1.0 first.
- First public release. The Home Assistant integration installs as a [HACS custom repository](docs/HOME_ASSISTANT.md#install).

## 0.1.0

- Local Android TV service for styled cards, images, live RTSP/HLS/DASH video, remote-control buttons and persistent status chips.
- Speech, clips and one-shot audio with output selection, quiet hours and request IDs for retries.
- Voice playback is prepared before the chime and scheduled for 200 ms after its end. Job timestamps record when chime and voice playback start.
- RTSP video uses RTP over TCP by default, including TvOverlay-compatible notifications. Native cards can set `video.tcp` to false to try UDP first.
- Paired HTTP API, long-poll events, DNS-SD, optional MQTT commands and HA discovery, and an allowlisted TvOverlay compatibility listener.
- Home Assistant integration with native actions, settings snapshots, Cast announcements and sounds.
