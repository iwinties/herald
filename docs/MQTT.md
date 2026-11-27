# Discovery and MQTT

Herald advertises its active HTTP port via DNS-SD service type `_herald._tcp` (HA: `_herald._tcp.local.`). TXT records contain the stable device UUID in `id`, `api=1` and the app `version`. Use the UUID for identity. Android can rename the instance to resolve a conflict. Changing the device name or port updates registration.

Enable MQTT in the TV settings. Enter the broker host, port (default 1883), TLS preference, and credentials if required. TLS uses the platform trust store and hostname verification. The client uses a clean session, 30 s keepalive, automatic reconnect and client ID `herald-<first eight characters of id>`. On connect it renews subscriptions and publishes current availability and state. Historical events are skipped at reconnect.

### Box publications

Let `R` be `<base_topic>/<device UUID>`, where base_topic defaults to `herald`. All payloads use UTF-8.

| Topic | QoS | Retained | Payload |
|---|---:|---|---|
| R/availability | 1 | Yes | Plain `online` at connect, `offline` on disconnect or last will |
| R/state | 1 | Yes | JSON with cards_visible, cards_queued, last_card, screen_on, overlay_allowed, and sound_playing. Published on connection, then on a changed state at the 2 s sample or a refresh after 60 s since the last state publication. |
| R/quiet | 0 | Yes | Plain decimal quiet_seconds, in seconds. Published on every connection, then only for a changed value at least 30 s after the last successful quiet publication. Intermediate values coalesce. |
| R/event | 0 | No | One native event with seq, time, type and event-specific fields from the [HTTP event table](API.md#get-v1events). |
| R/button | 0 | No | `{"event_type":"button","card":"notice","button":"ack"}` or `{"event_type":"dismissed","card":"notice"}`. |
| R/result | 0 | No | `{"cmd":"card","ok":true,"error":null}` or e.g. `{"cmd":"chip","ok":false,"error":"bad chip"}`. A card result includes the native `announce` object when speech was attempted, including a nested refusal. Plain notify retains only `cmd`, `ok`, `error`. Result means command handling completed. Later display and playback outcomes use events. |

### Commands

Set `mqtt.commands` to false for reporting only. With commands on (default), Herald subscribes at QoS 1. Publish with retain off. Herald ignores deliveries marked retained. The broker grants command access independently of HTTP pairing. Restrict publish rights on the six command topics to trusted clients. Supply a stable request_id when retrying announcements.

| Topic | Payload | Byte limit |
|---|---|---:|
| R/cmd/card | Native card JSON with optional `look` and `announce`, e.g. `{"id":"notice","message":"Update ready"}` | 1572864 |
| R/cmd/clear | Exactly one selector, e.g. `{"id":"notice"}`, `{"category":"general"}` or `{"all":true}` | 65536 |
| R/cmd/chip | Chip fields plus id, e.g. `{"id":"status","text":"Ready"}`. Delete with `{"id":"status","delete":true}`; delete must be boolean when present. | 65536 |
| R/cmd/announce | Native announce object, e.g. `{"text":"Ready","request_id":"update-1"}` | 3145728 |
| R/cmd/stop | Any payload, including empty | 65536 |
| R/cmd/notify | Plain UTF-8 text, at most 1000 UTF-16 units. Creates a general card with the text as its message and uses the category look and announce default. | 1572864 |

Payload overflow reports `body too large`. Malformed UTF-8 or malformed JSON where required reports `bad json`. Overlength notify reports `message too long`. Native validation and admission errors use the same strings as HTTP. An unexpected command failure reports `command failed`.

### Home Assistant MQTT discovery

With discovery enabled, each retained QoS 1 config goes to `<discovery_prefix>/<component>/herald_<id>/<object>/config` (prefix defaults to `homeassistant`). The device advertises identifiers `["herald_<id>"]`, name, model, manufacturer Herald and sw_version. Each unique_id is `herald_<id>_<object>`.

| Component | Objects |
|---|---|
| notify | notify sends plain text to cmd/notify |
| binary_sensor | screen_on, sound_playing, overlay_allowed (diagnostic) |
| sensor | quiet_seconds (s) reads the plain `R/quiet` value with `{{ value }}`, last_card (latest card ID) |
| event | button (button and dismissed events) |

HTTP `/v1/status` still includes `quiet_seconds`.

Turning discovery off or changing the prefix clears the previous config topics with empty retained payloads before publishing new ones. The old prefix is remembered per broker endpoint for retry. On a sleeping device Doze can interrupt outgoing MQTT connections. See the battery exemption in [Setup](SETUP.md).
