# Migrating existing notifications

For TvOverlay senders, open **Integrations → TvOverlay** on the TV. Add the sender's numeric IPv4/IPv6 address or CIDR and enable the separate listener (default port 5001). Limit this list to trusted senders. Choose **More options** to change the default category or port. If the port is occupied, choose a different port and update the sender. The native API stays on its configured port and uses pairing. [TvOverlay REST field reference](https://github.com/gugutab/TvOverlay#rest-api)

### POST /notify

The mapped card uses the configured category's look, sparse style overrides and announce default. Mapped corner and duration override that style for this card. A compatibility payload cannot override `announce`. The success reply keeps the compatibility shape even if speech is refused and the card posts silently. A speaking category needs a title or message to derive speech from a media card.

| Field | Status | Herald behavior |
|---|---|---|
| id | Applied | Card ID, generated if omitted. Repeat to update. |
| title | Applied | Card title |
| message | Applied | Card message |
| source | Applied | Card from |
| image | Applied | HTTP/S image URL or base64. Empty and mdi: candidates are skipped. |
| video | Applied | Video URL, RTSP, HLS, DASH or progressive HTTP/S. RTSP uses RTP over TCP. Category video_sound determines audio. |
| largeIcon | Applied | Same picture formats, used when image is absent, empty or mdi:. |
| smallIcon | Ignored | Use a native built-in style icon. |
| smallIconColor | Ignored | No separate small-icon tint. |
| corner | Applied | top_start, top_end, bottom_start, bottom_end map to top_left, top_right, bottom_left, bottom_right respectively. Other values give bad style. |
| duration | Applied | Native duration in seconds, 3–3600 or 0 until cleared/capped. |
| SmoothStreaming video | Unsupported | No SmoothStreaming module. |
| MDI pictures | Unsupported | Native built-in icons are available through styles. |

Example live-camera payload (replace the video placeholder with your own supported URL):

```json
{"id":"preview","source":"Monitor","title":"Activity","message":"Live view","video":"<video-url>","corner":"top_end","duration":30}
```

Herald submits card preview in the configured compatibility category, labels it “From Monitor”, and puts it at top right for 30 seconds. The placeholder stays up until the first video frame. Video sound follows the category setting, off by default. A stream failure leaves the card visible until its timer ends.

### POST /notify_fixed

| Field | Status | Herald behavior |
|---|---|---|
| id | Applied | Required chip ID. |
| visible | Applied | Boolean false deletes immediately. True upserts unless expiration is due. |
| icon | Applied | Native built-in name, `emoji:<text>` or `image:<cached-key>`. URL, base64 and MDI icon values give bad chip. |
| message | Applied | Chip text. Empty requires an icon other than none. |
| messageColor | Ignored | Standard chip text color. |
| iconColor | Applied | Chip color, #RRGGBB. |
| borderColor | Ignored | Standard chip border. |
| backgroundColor | Ignored | Standard chip background. |
| shape | Ignored | Uses the native pill. |
| expiration | Applied | Integer ≥1000000000 is epoch seconds. Smaller integers are relative seconds. Ordered duration strings may contain y, w, d, h, m, s (365-day years, 7-day weeks). Cap 604800 s. Absent/zero means never, negative seconds or a due epoch deletes, malformed values give bad chip. |

### Settings routes

These POSTs acknowledge bounded payloads with `ignored by Herald`. Configure actual behavior in Herald's settings.

| Route | Field | Status | Effect or native equivalent |
|---|---|---|---|
| /set/overlay | clockOverlayVisibility | Unsupported | No clock overlay |
| /set/overlay | overlayVisibility | Unsupported | No screen-wide dim background |
| /set/overlay | hotCorner | Ignored | Per-category placement |
| /set/notifications | displayNotifications | Ignored | Per-category show_here |
| /set/notifications | displayFixedNotifications | Ignored | Manage individual chips |
| /set/notifications | notificationLayoutName | Unsupported | No TvOverlay presets |
| /set/notifications | notificationDuration | Ignored | Per-category duration |
| /set/notifications | fixedNotificationsVisibility | Ignored | Native chip styling |
| /set/settings | deviceName | Ignored | device.name |
| /set/settings | remotePort | Ignored | device.port or compat.tvoverlay.port |
| /set/settings | displayDebug | Ignored | About |
| /set/settings | pixelShift | Ignored | display.pixel_shift |
| /set/mqtt | displayMqttStatusChange | Ignored | Broker status in diagnostics |
| /set/mqtt | mqttConfig | Ignored | Configure broker in Herald |
| /set/mqtt | mqttConfig.broker | Ignored | mqtt.host |
| /set/mqtt | mqttConfig.port | Ignored | mqtt.port |
| /set/mqtt | mqttConfig.user | Ignored | mqtt.username |
| /set/mqtt | mqttConfig.password | Ignored | mqtt.password |

### Notifications for Android TV

Migrate these options to a native Herald card or HA show_card action. Herald does not implement that app's wire protocol. [Home Assistant integration reference](https://www.home-assistant.io/integrations/nfandroidtv/)

| Option | Herald equivalent |
|---|---|
| position | style.placement, e.g. top_right |
| duration | style.duration in seconds |
| fontsize | style.text_size |
| transparency | style.opacity is percent **opaque**. Subtract transparency percent from 100 and round to a multiple of 5. |
| background color | style.background, #RRGGBB |
| icon | Use a built-in `style.icon`, `emoji:<text>` or `image:<cached-key>`. |
| image | Card image (url, data or cached key); HA also accepts supported media and camera sources. |
| interactive | Interactive mode is not supported. Native cards can define explicit remote-control buttons. |
