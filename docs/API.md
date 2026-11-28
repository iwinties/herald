# Herald HTTPS API 2

Use the box's LAN address and configured port (default 7979). The native listener requires HTTPS (TLS 1.2 or 1.3). Routes keep the /v1 prefix. Only the optional TvOverlay-compatible listener uses plain HTTP. All paired clients have the same access. JSON bodies are UTF-8 objects. Image and clip uploads take raw bytes. Each connection serves one request.

Pair on a trusted network. Use Bash, OpenSSL, curl and jq. At first pairing, fetch the public identity without credentials and verify both reported digests against the certificate served on a second connection. Compare the displayed fingerprint with the TV before entering the code. Keep the verified public key pin alongside the token for future sessions. Keep shell tracing off.

```bash
set -eo pipefail
address=192.0.2.10:7979
box="https://$address"
compat=http://192.0.2.10:5001
info=$(curl -q --noproxy '*' --insecure -fsS "$box/v1/info")
peer=$(openssl s_client -connect "$address" </dev/null 2>/dev/null)
cert=$(openssl x509 -outform DER <<<"$peer" | sha256sum | cut -d' ' -f1)
pin=$(openssl x509 -pubkey -noout <<<"$peer" | openssl pkey -pubin -outform DER |
  openssl dgst -sha256 -binary | base64)
if ! jq -e --arg cert "$cert" --arg pin "$pin" \
    '.cert_sha256 == $cert and .pin_sha256 == $pin' <<<"$info" >/dev/null; then
  echo 'certificate mismatch' >&2
  exit 1
fi
upper=${cert^^}
printf 'Fingerprint %.4s %.4s %.4s %.4s\n' "$upper" "${upper:4}" "${upper:8}" "${upper:12}"
```

Use the verified pin for every subsequent native request. The helper reads the authorization header through a file descriptor.

```bash
herald() {
  local rc
  curl -q --noproxy '*' --insecure --pinnedpubkey "sha256//${pin:?verified pin required}" "$@" || {
    rc=$?
    if [[ $rc == 90 ]]; then echo 'certificate mismatch' >&2; fi
    return "$rc"
  }
}
auth() { printf 'Authorization: Bearer %s\n' "$HERALD_TOKEN"; }
```

## Shared checks, in order

1. Read the request line and headers (8192 bytes total). Malformed requests, duplicate Authorization headers and unsupported transfer framing return 400 `bad request`. Use Content-Length for uploads.
2. Check Content-Length before authentication or body parsing. The limit is 65536 bytes except for POST cards (1572864), POST announce (3145728), PUT clips (4194304) and PUT images (2097152). Overflow returns 413 `body too large`, even for an invalid key.
3. Authorize the client, except on the three public routes below. Missing or unknown credentials return 401 `unauthorized`. Match the Bearer scheme case-insensitively followed by one space. During shutdown authentication can return 503 `service stopped`. If refreshing the last-use timestamp fails it returns 500 `credential store failed`.
4. Resolve the route (404 `unknown path`) and method (405 `method not allowed`), then validate the request as described for that operation. A malformed JSON object returns 400 `bad json`. A short body can return 400 `bad request`. Errors use `{"ok":false,"error":"…"}` except for the separate compatibility listener. Success is HTTP 200. The two listeners share 16 connections.

Card, button, chip and request IDs and image keys match `^[A-Za-z0-9_-][A-Za-z0-9_.-]{0,63}$`. Clip keys use the same alphabet with up to 160 characters total. Category IDs match `^[a-z0-9_]{1,32}$`.

## Pairing and device identity

The box generates its UUID at first start. Discovery names can change. Pairing uses a six-digit on-screen code valid for 120 seconds. One code may be active at a time. Cancelling it or entering a fifth wrong code starts a 60-second lockout. Restarting the service discards the code. A successful pairing removes it and displays the client name for five seconds.

A network-started code appears in its own non-focusable overlay and binds to the trimmed client name. A code started under **Pairing & clients → Pair an app or script** takes its client name at finish. A TV-started code stays valid until it times out, also after you leave the pairing screen, and shows again when you come back. The code stays out of card history, events, backups and MQTT. Save the token returned at finish on the client. The box stores its SHA-256 digest, name and timestamps. Tokens contain 32 random bytes encoded as unpadded base64url. Up to 16 clients may pair; use the TV to remove a named client.

### `GET /v1/info`

| Request | Success | Route errors in check order |
|---|---|---|
| Public. No fields. | `ok`, `app` (`herald`), `version`, `api` (2), `tls` (true), `cert_sha256` (64 lowercase hex digits, SHA-256 of the DER certificate), `pin_sha256` (44-character standard base64 SHA-256 of its DER SubjectPublicKeyInfo), `id` (UUID), `name`, `model`, `pairing` (allowed). | Shared checks. |

```bash
herald -sS "$box/v1/info"
```

### `POST /v1/pair/start`

| Request | Success | Route errors in check order |
|---|---|---|
| Public. `client` is a trimmed string of 1–64 Unicode code points. | `{"ok":true,"expires_in":120}` | 400 `bad json`, 503 `service stopped`, 400 `bad client`, 403 `pairing disabled`, 409 `too many clients`, `screen off`, `overlay not allowed`, `pairing in progress`, 429 `try later`. Window creation can also return 409 `overlay not allowed`. |

```bash
herald -sS -X POST "$box/v1/pair/start" --json '{"client":"Console"}'
```

### `POST /v1/pair/finish`

| Request | Success | Route errors in check order |
|---|---|---|
| Public. `client` as above, `code` as a six-character string (keep leading zeros). | `ok`, `token`, `id` (device UUID). | 400 `bad json`, 503 `service stopped`, 400 `bad client`, 410 `pairing expired` on first finish after expiry, 404 `no pairing` if absent, 409 `too many clients`, 403 `wrong code` for mismatched code or client. Persisting credentials or lockout can return 500 `credential store failed`. |

```bash
read -rs -p 'Code shown on TV: ' code; printf '\n'
HERALD_TOKEN=$(printf '%s' "$code" | jq -Rs '{client:"Console",code:.}' |
  herald -fsS "$box/v1/pair/finish" --json @- | jq -er .token)
unset code
```

### `GET /v1/clients`

| Request | Success | Route errors in check order |
|---|---|---|
| Paired client. | `clients` contains `client`, `created` and `last_used` (epoch seconds). Last use is persisted at most once per 60 seconds. | Shared checks. |

```bash
herald -sS -H @<(auth) "$box/v1/clients"
```

### `DELETE /v1/clients/self`

| Request | Success | Route errors in check order |
|---|---|---|
| Revoke the credential used for this request. | `{"ok":true}` | 500 `credential store failed`. |

```bash
herald -sS -H @<(auth) -X DELETE "$box/v1/clients/self"
```

### `GET /v1/status`

| Request | Success | Route errors in check order |
|---|---|---|
| No fields. | `ok`, `version`, `api`, `id`, `name` plus the fields below. | 500 `store failed` if pruning expired chips fails. |

```bash
herald -sS -H @<(auth) "$box/v1/status"
```

| Status field | Meaning |
|---|---|
| `api`, `tls_failures` | API version 2 and number of failed or timed-out native TLS handshakes since service start. |
| `stream`, `event_seq` | Random 16-hex service-start ID and latest event sequence. Use together as a cursor. |
| `screen_on`, `overlay_allowed`, `battery_exempt`, `tts_ready` | Screen interactive, permission to draw, exemption from battery optimization and speech engine readiness. |
| `outputs`, `audio_busy`, `last_audio` | Available `speaker` and/or `tv`, audio worker busy flag, and latest audio status string (initially `none`, e.g. `said`, `played <key>`, `played voice`, `error voice`). |
| `temp_files`, `clips`, `images`, `chips` | Temporary speech/voice files and counts of cached clips, cached pictures and unexpired chips. |
| `sound_playing`, `quiet_seconds` | Advisory external sound and whole seconds since the latest eligible true sample (initially since startup). |
| `cards_visible`, `cards_queued`, `last_card` | Showing count, queued plus waiting count, and latest card lifecycle summary with id, category and state. |
| `ntfy` | Worst subscription status: `unauthorized`, `error`, `connecting`, `connected`, or `off` when there are no active subscriptions. |
| `settings_revision`, `mqtt`, `compat` | Current revision, broker status (`off`, `connecting`, `connected`, `error`), compatibility listener (`off`, `listening`, `port in use`). |

Herald samples Android media activity every two seconds. Two consecutive eligible true samples set `sound_playing`; three false samples clear it. A platform read failure is skipped. Samples during Herald's own playback and the second following its last sound are ignored and preserve the pending streak. Audio jobs own the signal after preparation, card sounds share the worker, and each unmuted video counts from its first audio position advance. Use this signal for card eligibility and button focus.

### `GET /v1/events`

| Request | Success | Route errors in check order |
|---|---|---|
| `after` is a nonnegative decimal sequence, `wait` an integer 0–30 seconds. Both default to 0. | `ok`, `stream`, `events`, `seq`, `gap`, `reset`. | 400 `bad query` for invalid fields, values or overflow, 429 `too many waiters` (four simultaneous waiters). |

```bash
herald -sS -H @<(auth) "$box/v1/events?after=0&wait=25"
```

Each stream starts at sequence 1 and retains 256 events. Each event contains `seq`, `time` (epoch milliseconds), `type` and the fields below. Start at status.event_seq for new events only. Advance to the reply's `seq`. An `after` ahead of the current sequence gives an immediate empty batch with `reset:true`. A cursor older than the oldest retained event minus one gives `gap:true`. Refresh status on gap, reset or stream change.

| Type | Additional fields |
|---|---|
| `card.shown` | `id`, `category`, `ms` from acceptance to window addition |
| `card.updated` | `id` |
| `card.ended` | `id`, `reason` = expired, cleared, replaced, dismissed, button, capped, stopped or failed |
| `card.skipped` | `id`, `reason` = screen_off, hidden, night or over_playback (disabled is reserved) |
| `card.button` | `id`, `button` (selected button ID) |
| `card.media` | `id`, `kind` image or video, `state` shown (image), playing (first video frame) or failed, `ms` since acceptance. Video failure after rendering includes `after_first_frame:true`. |
| `announce.started` | `job` |
| `announce.ended` | `job`, `result` = played, error or stopped, `voice_at` = epoch milliseconds when voice playback started or null if it never started |
| `screen`, `sound` | `on` (screen) or `playing` (sound), boolean |
| `settings.changed` | `revision` |
| `client.paired`, `client.removed` | `client` name |

## Cards and cached pictures

### `POST /v1/cards`

| Request | Success | Route errors in check order |
|---|---|---|
| Card object described below. At least one nonblank `title` or `message`, or an `image` or `video`. | `ok`, `id`, effective `category`, `state` (showing, queued, waiting, skipped, ended or pending_voice), `reason` (null or lifecycle reason). | 400 `bad json`, `bad id`, `bad category`, `empty card`, `bad image`, `bad video`, `bad buttons`, `bad priority`, `bad look`, `bad style`, then the spoken-card checks below when applicable. Board refusals are 409 `overlay not allowed`, 429 `too many cards`. |

```bash
herald -sS -H @<(auth) -X POST "$box/v1/cards" --json '{"id":"notice","message":"Update ready"}'
```

### `GET /v1/cards`

| Request | Success | Route errors in check order |
|---|---|---|
| No fields. | `cards` array of active cards with `id`, `category`, `state`, `priority`, `remaining_s` (null while queued or waiting). | Shared checks. |

```bash
herald -sS -H @<(auth) "$box/v1/cards"
```

### `GET /v1/cards/<id>`

| Request | Success | Route errors in check order |
|---|---|---|
| Path ID. | `ok`, `id`, `category`, `state`, `reason`, `button` (null until selected). Includes `ended` and `skipped` history for 600 seconds, up to 64 recent records. | 404 `unknown card`. |

```bash
herald -sS -H @<(auth) "$box/v1/cards/notice"
```

### `DELETE /v1/cards/<id>`

| Request | Success | Route errors in check order |
|---|---|---|
| Path ID. Safe to repeat. | `ok`, `found` is true if an active card or pending reservation was removed. | Shared checks. |

```bash
herald -sS -H @<(auth) -X DELETE "$box/v1/cards/notice"
```

### `POST /v1/cards/clear`

| Request | Success | Route errors in check order |
|---|---|---|
| Select `category` or `all:true`. Clears matching active cards (including queued and waiting) and pending reservations. | `ok`, `cleared` counts distinct IDs across both. | 400 `bad json` for missing, invalid or conflicting selector. |

```bash
herald -sS -H @<(auth) -X POST "$box/v1/cards/clear" --json '{"category":"general"}'
```

### `GET /v1/images/<key>`

| Request | Success | Route errors in check order |
|---|---|---|
| Path image key. | `ok`, `size` in bytes. Metadata only. | 400 `bad key`, 404 `not cached`. |

```bash
herald -sS -H @<(auth) "$box/v1/images/badge"
```

### `PUT /v1/images/<key>`

| Request | Success | Route errors in check order |
|---|---|---|
| Raw bitmap file, up to 2097152 bytes. The 64-key cache evicts the least recently used picture. | `ok`, `size` in bytes. | 413 `body too large` before 400 `bad key`, `empty body`, `bad image`, then 500 `store failed`. |

```bash
herald -sS -H @<(auth) -X PUT "$box/v1/images/badge" --data-binary @badge.png
```

| Card field | Values and behavior |
|---|---|
| `id`, `category` | ID defaults to random 16 hex. Category defaults to `general`; unknown valid category IDs also resolve to `general`. |
| `title`, `message`, `from` | Strings truncated without splitting a surrogate pair at 120, 1000 and 40 UTF-16 code units. Sender displays as “From …”. |
| `image` | Exactly one of `key`, `url` (HTTP/S, 2097152 bytes, 5 s) or `data` (base64, 1048576 decoded bytes). Fetch at acceptance even if waiting. A cache miss, failed fetch or undecodable inline picture leaves the card up and emits image failed. No fetch retry. |
| `video` | Required `url` (RTSP, HTTP/S progressive, HLS or DASH); optional booleans `tcp` (force RTP over TCP, default true. Set false to try UDP first), `muted` (defaults according to style.video_sound), and `timeout` (integer 3–60 seconds, default 12). No RTSPS or SmoothStreaming. |
| `buttons` | Up to three objects with unique key `id`, string `label` (1–24 UTF-16 units) and `action` (`event` default, `expand`, `dismiss`). Optional `style` accepts `background`, `text_color`, `focus_background` and `focus_text_color` as `#RRGGBB`. Omitted colors inherit from the card. An `expand` action becomes **Minimize** in fullscreen. Selecting it again returns the card to its compact placement and restores the supplied label. |
| `priority` | low, normal (default), high, critical |
| `look` | Optional existing look ID. Omit to keep the category appearance. An unknown ID, null or a non-string returns 400 `bad look`. |
| `announce` | Boolean or speech options. See [Spoken cards](#spoken-cards). |
| `style`, `icon` | Partial style object. Top-level icon takes precedence over style.icon. [Style fields, defaults and admission](STYLES.md). |

**Style object fields**

Defaults below are the category base before looks and overrides.

| Field | Values and effect | Default |
|---|---|---|
| placement | top_left, top, top_right, left, center, right, bottom_left, bottom, bottom_right, fullscreen | top_right |
| size | small (24% screen width), medium (32%), large (44%), full (screen width). Fullscreen uses the screen bounds. | medium |
| text_size | small, medium, large, xlarge. Title/body sizes as % of height are 2.4/1.8, 3.0/2.2, 3.8/2.8 and 4.6/3.4. | medium |
| accent | #RRGGBB accent bar. On side and corner cards the bar faces the screen center. Other placements use the left edge. | Category accent |
| background | #RRGGBB background color | #000000 |
| opacity | Background opacity, 0–100 in steps of 5. At 0 the content remains visible. | 90 |
| icon | none, info, bell, door, camera, alarm, warning, home, media, megaphone, person, package, tv, timer, check, `emoji:<text>` (1–8 Unicode code points, no whitespace, space characters or ISO controls), `image:<key>` (cached image key). A missing picture draws no icon. | Category icon |
| duration | Integer seconds 3–3600, or 0 until cleared or capped | Category duration |
| stacking | replace, queue, stack | Category stacking |
| sound | none, chime, clip:<key>. Plays through the announcement queue when the card appears, subject to night rules. Speak-and-show suppresses this sound. | none |
| sound_volume | Integer 0–100 | 60 |
| video_sound | on, off. An explicit video.muted boolean overrides it. Audible video mixes without requesting audio focus. | off |
| show_here | true, false | true |
| over_playback | show, skip | show |
| night | normal, dim, hide | normal |
| focus | auto, never | auto |
| animation | slide, fade, none. Transitions last 250 ms. | slide |
| layout | card, text, strip, pill | card |
| corner | none, small, medium, large, pill | small |
| border | none, thin, medium, thick | none |
| border_color | #RRGGBB | #FFFFFF |
| shadow | none, soft, strong | none |
| glow | none, accent, border | none |
| accent_style | bar, badge, dot, underline, none | bar |
| title_color, text_color | #RRGGBB for title and body respectively | #FFFFFF |
| title_weight | bold, normal | bold |
| button_style | solid, outline, underline, raised | solid |
| button_shape | square, rounded, pill | rounded |
| button_spacing | compact, normal, roomy | normal |
| button_background, button_text_color | #RRGGBB fill and label for an unselected action | #303A46, #DCE3EA |
| button_focus_background, button_focus_text_color | #RRGGBB fill and label for the action selected with the remote | #526579, #F3F6F8 |
| label | none, category (uppercase category name) | none |

Posting an existing ID replaces its whole spec. A showing card that remains eligible in its category keeps its position, restarts its timer and emits `card.updated`. Otherwise it re-enters admission as a new arrival without an ended event for the old spec. A failure to add the window after admission ends the card as `failed`.

Pictures decode within 1920 px on the longest side and 2073600 pixels, then scale to at most 1280 px. The still or icon placeholder remains until the first frame. Video timeout starts with visible playback. Early error, end or timeout shows “Live view unavailable”. After the first frame, error or end restores the still with “Live view ended”. The card keeps its timer. An image loaded behind active video reports shown when uncovered.

## Spoken cards

The entire card envelope, including inline base64 audio, is limited to **1,572,864 bytes** on HTTP, MQTT and the compatibility card path. Upload reusable audio to the clip cache and send its key when the encoded card would exceed the limit.

| Request `announce` | Behavior |
|---|---|
| Omitted | Use the category's `announce`. All built-ins start at false. |
| `false` | Post an ordinary card, even in a speaking category. Its configured card sound still applies. |
| `true` | Speak the card with default playback settings. |
| Object, including `{}` | Speak. Supply at most one of `text`, `clip`, `audio`. The playback fields `chime`, `volume`, `output`, `urgent`, `duck` and `request_id` follow the announcement table. `timing` is `with_voice` (default) or `now`. |
| Other value (including `null`), unknown option or invalid timing | 400 `bad announce`. |

For derived speech, cut the title to 120 and the message to 1000 UTF-16 units, then trim characters through U+0020 at either end of each. Use the one nonempty part, or join both with a space after a final period, exclamation mark or question mark, and with a period and space otherwise. Trim explicit `text` the same way and use it in place of both parts. Cut the result to 500 UTF-16 units. Every cut preserves surrogate pairs. A media-only card needs an explicit speech source. Missing derived speech or blank explicit text returns 400 `text is empty`.

Quoted inputs below use JSON string notation to make whitespace visible.

| Title | Message | Spoken text |
|---|---|---|
| `Garage door open` | `Open for 20 minutes after dark.` | `Garage door open. Open for 20 minutes after dark.` |
| `Open!` | `Check now` | `Open! Check now` |
| `"  Front door  "` | `"\tunlocked \n"` | `Front door. unlocked` |
| (empty) | `Only message` | `Only message` |
| `Only title` | `"   "` | `Only title` |
| 121 × `a` | `Rest` | 120 × `a` + `. Rest` |
| U+00A0 + `Hi` + U+00A0 | (empty) | U+00A0 + `Hi` + U+00A0 (both U+00A0 kept) |
| (empty) | 499 × `b` + `😀` | 499 × `b` |
| `T` | 1001 × `c` | `T. ` + 497 × `c` |

```bash
herald -sS -H @<(auth) "$box/v1/cards" --json \
  '{"title":"Update ready","message":"Press OK","announce":{"request_id":"notice-voice-1","chime":false}}'
```

| Admission | HTTP 200 card reply and display |
|---|---|
| Ordinary card | `ok,id,category,state,reason`. No `announce` member. |
| Speech accepted | Adds `"announce":{"ok":true,"job":"<uuid>"}`. With `now`, the board posts immediately and returns that decision. With `with_voice`, the original state is `pending_voice` and reason is null. Display is attempted at voice start, after any chime. A timed card with known voice length gets at least that length plus three seconds. |
| Speech refused | The board posts the card silently. Its reply includes e.g. `"announce":{"ok":false,"status":409,"error":"quiet hours"}`. A board refusal returns the board's HTTP error instead. Speech attempts suppress the card's own sound, including on fallback. |

Card validation runs first, followed by the speech wrapper, announcement member checks, then derived text. For valid requests, duplicate lookup precedes live checks. With `now`, board admission precedes the audio checks. Audio refusals, in order, are 404 `not cached`, 409 `output not available` or `quiet hours`, 503 `speech engine not ready`, 429 `too many request ids` or `busy`. A failure saving the request ID returns 500 `store failed` before posting or queuing.

**Retrying.** A supplied card ID wins. Otherwise a spoken card with a request ID uses it as the card ID. A repeat of an accepted spoken request returns the original card fields and `announce` with the original job, `duplicate:true` and the current `outcome`. It creates no second job or display. A request ID previously used by plain `/v1/announce` returns 409 `request id conflict` on a spoken card. The reverse order yields an ordinary announcement duplicate. A speech refusal leaves the ID available for a retry.

**Pending reservations.** A deferred card waits under its ID until its job consumes the reservation once. A newer reservation or a successful post of that ID supersedes it. A refused post preserves it. Delete or clear can cancel display before voice starts. An audio failure before display triggers one silent display attempt with the normal lifetime if the reservation is still current. A later audio error leaves a displayed card up. Stopping the job removes only its own reservation or card. On restart reservations disappear, queued request IDs are released and playing records become interrupted. To determine whether audio played, match the job to `announce.ended` and inspect `result` and `voice_at`. Pending reservations do not appear in the active-card list. Clearing a reservation cancels the pending display. Use `/v1/stop` to stop audio.

## Chips

### `PUT /v1/chips/<id>`

| Request | Success | Route errors in check order |
|---|---|---|
| Path ID and chip fields below. Upsert the whole chip. | `{"ok":true}` | 400 `bad id`, `bad json`, `bad chip` or `bad look`, 409 `too many chips`, 500 `store failed`. |

```bash
herald -sS -H @<(auth) -X PUT "$box/v1/chips/status" --json '{"text":"Ready","color":"#FFFFFF"}'
```

### `GET /v1/chips`

| Request | Success | Route errors in check order |
|---|---|---|
| No fields. | `chips` array with `id`, `text`, `icon`, `color`, `priority`, `look`, `position`, `expires_at` (epoch seconds or null). | 500 `store failed` if expiry cleanup fails. |

```bash
herald -sS -H @<(auth) "$box/v1/chips"
```

### `PUT /v1/chips`

| Request | Success | Route errors in check order |
|---|---|---|
| `chips` array with an `id` and fields in each row. Replaces the entire set atomically. | `{"ok":true}` | 400 `bad json`, `bad chip` (includes invalid or duplicate IDs), `bad look`, 409 `too many chips`, 500 `store failed`. |

```bash
herald -sS -H @<(auth) -X PUT "$box/v1/chips" --json '{"chips":[{"id":"status","text":"Ready"}]}'
```

### `DELETE /v1/chips/<id>`

| Request | Success | Route errors in check order |
|---|---|---|
| Path ID. Safe to repeat. | `ok`, `found` boolean. | 400 `bad id`, 500 `store failed`. |

```bash
herald -sS -H @<(auth) -X DELETE "$box/v1/chips/status"
```

A chip has `text` (0–40 UTF-16 units, default empty), `icon` (same built-in, emoji and cached picture forms as cards, default `none`), `color` (#RRGGBB, default #FFFFFF), `priority` (normal by default, or high), and integer `expires` (0–604800 seconds, default 0 = never). Empty text requires a visible icon. Deletes are explicit. Up to 12 chips persist across restarts. While the screen is on, high priority then newest appear first. Each position shows up to five chips plus a count of any others there. A visible high-priority chip pulses on appearance, promotion, or a text, icon or color change while high. At night the row uses 60% opacity. Pixel shift moves it up to 6 px every 120 seconds.

A missing or null `look` inherits `display.chip_look`. A missing or null `position` inherits `display.chip_placement`. Valid positions are top_left, top, top_right, bottom_left, bottom and bottom_right. An unknown look string gives 400 `bad look`. A non-string look or invalid position gives 400 `bad chip`. Deleting a chip's named look preserves the stored name, ID and expiry. The chip draws with the display look until its named look exists again.

### Collection response examples

These examples show one entry per collection.

**GET /v1/clients**
```json
{"ok": true, "clients": [{"client": "Home Assistant", "created": 10, "last_used": 20}]}
```

**GET /v1/cards**
```json
{"ok": true, "cards": [{"id": "notice", "category": "general", "state": "showing", "priority": "normal", "remaining_s": 10}]}
```

**GET /v1/chips**
```json
{"ok": true, "chips": [{"id": "status", "text": "Ready", "icon": "none", "color": "#FFFFFF", "priority": "normal", "look": null, "position": null, "expires_at": null}]}
```

## Audio

### `GET /v1/clips/<key>`

| Request | Success | Route errors in check order |
|---|---|---|
| Path clip key. | `ok`, `size` in bytes. Metadata only. | 400 `bad key`, 404 `not cached`. |

```bash
herald -sS -H @<(auth) "$box/v1/clips/tone"
```

### `PUT /v1/clips/<key>`

| Request | Success | Route errors in check order |
|---|---|---|
| Raw audio file, 1–4194304 bytes. Up to 200 cached clips. | `ok`, `size` in bytes. | 413 `body too large` before 400 `bad key`, `empty body`, then 500 `store failed`. |

```bash
herald -sS -H @<(auth) -X PUT "$box/v1/clips/tone" --data-binary @tone.wav
```

### `POST /v1/announce`

| Request | Success | Route errors in check order |
|---|---|---|
| Exactly one source plus optional playback fields below. | `ok`, `job` (UUID). Repeat returns original job, `duplicate:true` and `outcome`. | 400 `bad json`, `bad source`, `bad output`, `bad key`, `text is empty`, `bad audio`, `bad url`, `bad card`, `bad request id`, 413 `audio too large`. After id lookup: 404 `not cached`, 409 `output not available`, `quiet hours`, 503 `speech engine not ready`, 429 `too many request ids`, `busy`, 500 `store failed`. Loading unavailable id history can also return `store failed` before admission. |

```bash
herald -sS -H @<(auth) -X POST "$box/v1/announce" --json '{"text":"Update ready","request_id":"update-1"}'
```

### `POST /v1/stop`

| Request | Success | Route errors in check order |
|---|---|---|
| No fields. Stop current playback, drain waiting jobs, clear their cards. | `{"ok":true}` | Shared checks. |

```bash
herald -sS -H @<(auth) -X POST "$box/v1/stop"
```

| Announcement field | Meaning |
|---|---|
| `text`, `clip`, `audio`, `url` | Choose one. Local TTS trims text and truncates to 500 UTF-16 units. A clip must be cached. Inline audio is nonempty base64, whitespace ignored, up to 2097152 decoded bytes. The box fetches an HTTP/S URL once, up to 4194304 bytes within 10 s. Inline and fetched audio are temporary. |
| `output`, `volume` | `auto` (HDMI TV first, then box speaker), `tv` or `speaker`. Integer volume clamps to 0–100. Default comes from the resolved output. |
| `chime`, `urgent`, `duck` | Chime is boolean or `clip:<key>`, defaults to audio.chime. Boolean urgent and duck default false. Night restrictions apply to the box speaker. Duck requests transient may-duck audio focus before chime and voice. Refused focus still permits playback. |
| `request_id` | Optional key shared across retries of one logical announcement. Holds the outcome for 600 s. |
| `card` | Native card object without id. Its sound is suppressed. It appears at voice start after the chime, for at least its own duration or known audio length + 3 s, whichever is longer. Zero stays until cleared or capped. A refused card does not interrupt the audio. Stop clears the job's card; audio failure ends it as failed. |

The queue holds one playing job and five waiting. Chime and voice share a 60 s playback deadline. Job timing is measured from acceptance (unreached stages record zero). `announce.started` signals pickup; playback state is recorded after preparation before focus and speaker wake.

Up to 256 unexpired request IDs can be held in the journal. Structural validation precedes duplicate lookup. Known IDs return queued, playing, played, error, stopped or interrupted even if the output has since disappeared. A fresh ID is saved before enqueueing. After restart an unstarted queued record is released, while a playing record becomes interrupted. An unreadable history prevents new id-bearing admissions. A failed terminal write keeps the best known outcome in memory and is retried on lookup or write.

## Looks

A look contains visual overrides. The built-in maps and the list of writable visual keys are in [Card styles](STYLES.md). All three routes require pairing. Other methods return 405 `method not allowed`.

### `GET /v1/looks`

| Request | Success |
|---|---|
| No body. | `{"ok":true,"looks":[…]}`. Each row has `id,name,builtin,style`. Order is classic, glass, minimal, banner, alert, pill, paper-note, night-shift, on-air, then custom looks sorted by ID. |

```bash
herald -sS -H @<(auth) "$box/v1/looks"
```

### `PUT /v1/looks/<id>`

| Request | Success | Route errors in order |
|---|---|---|
| Create or replace. ID matches `^[a-z0-9_]{1,32}$`. Optional `name` (1–40 Unicode code points, defaults to ID). Required `style` object, possibly empty. | `{"ok":true,"look":{…}}`, with `builtin:false` and the supplied overrides. Replacing an existing look is allowed at capacity. | 400 `bad settings` field `id`, 409 `built-in look`, 400 `bad json` (body), 400 `bad settings` field `look.<unknown key>`, `look.name`, `look.style` or `look.style.<key>`, 409 `too many looks` for a 33rd custom look, 500 `settings store failed`. |

```bash
herald -sS -H @<(auth) -X PUT "$box/v1/looks/notice" --json '{"name":"Notice","style":{"border":"thin"}}'
```

### `DELETE /v1/looks/<id>`

| Request | Success | Route errors in order |
|---|---|---|
| Custom look ID. | `{"ok":true}`. Categories using it and a matching `display.chip_look` switch to glass in the same revision. | 400 `bad settings` field `id`, 409 `built-in look`, 404 `unknown look`, 500 `settings store failed`. |

```bash
herald -sS -H @<(auth) -X DELETE "$box/v1/looks/notice"
```

## Settings and categories

The schema 2 `ntfy` group configures topic subscriptions. Read `ntfy` in `GET /v1/status` for the current combined connection state. A stored schema 2 document without the group loads its defaults. See [ntfy setup and message mapping](NTFY.md).

### `GET /v1/settings`

| Request | Success | Route errors in check order |
|---|---|---|
| No fields. | `ok` plus the schema 2 document described below. `mqtt.password_set` and `ntfy.token_set` report whether their respective credentials exist. | Shared checks. |

```bash
herald -sS -H @<(auth) "$box/v1/settings"
```

### `PUT /v1/settings`

| Request | Success | Route errors in check order |
|---|---|---|
| Partial document. Objects merge by key. Supplied `looks`, `categories` and `ntfy.topics` each replace their whole list. | Same as GET after saving. A changed port takes effect after the reply, then discovery registers again. | 400 `bad json`, 400 `bad settings` with a dotted `field` identifying the validation failure, 500 `settings store failed`. |

```bash
herald -sS -H @<(auth) -X PUT "$box/v1/settings" --json '{"display":{"max_visible":3}}'
```

### `GET /v1/categories`

| Request | Success | Route errors in check order |
|---|---|---|
| No fields. | `categories` array of `{id,name,builtin,look,announce,style,effective}`. `style` contains sparse overrides. `effective` contains all 28 resolved fields with no request overrides. | Shared checks. |

```bash
herald -sS -H @<(auth) "$box/v1/categories"
```

For example, inspect the saved overrides beside the current result on a fresh installation:

```bash
herald -sS -H @<(auth) "$box/v1/categories" |
  jq '.categories[] | select(.id == "general") | {look, announce, style, corner: .effective.corner}'
# {"look":"glass","announce":false,"style":{},"corner":"large"}
```

### `PUT /v1/categories/<id>`

| Request | Success | Route errors in check order |
|---|---|---|
| Optional `name`, `look`, boolean `announce`, partial `style`. New custom categories use general's base, glass and `announce:false`. Each style key merges individually. Set a key to null to remove its override. | `ok`, `category` (stored row, without `effective`). | 400 `bad json`, 400 `bad settings` (invalid id), 409 `too many categories` for a new 33rd row, 400 `bad settings` (invalid category field), 500 `settings store failed`. |

```bash
herald -sS -H @<(auth) -X PUT "$box/v1/categories/updates" --json '{"name":"Updates","style":{"duration":20}}'
```

### `DELETE /v1/categories/<id>`

| Request | Success | Route errors in check order |
|---|---|---|
| Remove a custom category. If selected for compatibility, falls back to general in the same revision. | `{"ok":true}` | 400 `bad settings` (invalid id), 409 `built-in category`, 404 `unknown category`, 500 `settings store failed`. |

```bash
herald -sS -H @<(auth) -X DELETE "$box/v1/categories/updates"
```

A stored document with a missing or unsupported schema (including schema 1) is discarded on load. Herald uses defaults at revision 0, with no write or event until a change is saved. A valid stored schema 2 document loads at its saved revision. Invalid fields in a stored schema 2 document fail validation.

On PUT, omit `schema` or supply a number equal to 2. Values such as 1, 3, 2.5, `"2"` or null give 400 `bad settings` with field `schema`. Omitted groups and lists retain their values. For a replacement list, supply the desired sparse category rows and custom looks (each `{id,name,style}`, name optional). Use omission to leave a style field inherited in a replacement row. Null removes a style key only in a single-category PUT. Duplicate IDs report `looks.N.id` or `categories.N.id`. Built-in look IDs in the replacement list also fail. Limits are 32 custom looks and 32 total categories after missing built-ins are reinserted.

Herald validates the candidate look table first. A single PUT can create a look and assign it. Dangling references in submitted or retained rows fail at `categories.N.look` (zero-based candidate row), `display.chip_look` or `ntfy.topics.N.look`. Only the single-look DELETE automatically repairs those references. Validation and storage are atomic. Category `effective` is ignored on input and never stored. The TV's **Reset to default** restores the category's default look and announce setting and removes all its stored style overrides in one edit.

A changed document increments `revision` and emits one settings.changed event. A no-op keeps the revision. Restore may send a GET document back. Revision, built-in flags and password_set are read-only. Missing built-ins return with default styles in a replacement list. Omitting the selected custom compatibility category also restores `general`.

| Group | Fields, defaults and bounds |
|---|---|
| Root | `schema` 2, `looks` (custom only, default `[]`), `revision` integer, `categories` up to 32 unique IDs including built-ins, and groups below. |
| `device` | `name` 1–40 Unicode code points (initially Android device name or model), `port` 1024–65535 (7979). |
| `pairing` | `allowed` true. |
| `display` | `max_visible` 1–6 (3), `night_start` and `night_end` HH:MM local (22:00, 07:00), `chip_look` must name an existing look (default `glass`), `chip_placement` top_left (default), top, top_right, bottom_left, bottom or bottom_right. `pixel_shift` true, `sticky_cap` 60–86400 s (3600), `focus_while_playing` true. |
| `audio` | `volume_tv` 70, `volume_speaker` 50 (both 0–100), `chime` true. `speaker_at_night` urgent (urgent requests only), always or never. |
| `mqtt` | `enabled` false, `host` empty, `port` 1–65535 (1883), `tls` false, `username` empty, `password` write-only (empty or absent keeps it), `base_topic` herald (matches `[A-Za-z0-9_-][A-Za-z0-9_/-]{0,63}`), `discovery` true, `discovery_prefix` homeassistant, `commands` true. |
| `ntfy` | `enabled` false, `server` https://ntfy.sh, `catch_up` 0–600 seconds (120), `topics` up to four unique rows (default `[]`). Each row has `topic`, `label` (empty), `category` (general), `look` (null or an existing ID) and `speak` (false). |
| `ntfy.token` | Write-only, 1–256 characters without whitespace. Null clears, empty or absent keeps it on the same server. Changing the canonical server clears it unless the same PUT supplies a replacement. GET returns `token_set`, ignored on write. |
| `compat.tvoverlay` | `enabled` false, `port` 1024–65535 (5001, must differ from device.port), `allowed` empty list of up to 16 numeric IPv4/IPv6 addresses or CIDRs (at least one when enabled), `category` general (must exist). |

## TvOverlay-compatible listener

On this listener the numeric peer address is checked before any headers (403 `sender not allowed`), followed by the HTTP head (400 `bad request`), Content-Length (413 `body too large`), path (404 `unknown path`), then method (405 `method not allowed`). Both notify routes allow 1572864 bytes and recheck the sender after reading JSON. Other routes allow 65536. Every reply uses `success` (boolean) and `message` (string). See all field mappings in [Compatibility](COMPAT.md).

### `POST /notify`

| Request | Success | Route errors in check order |
|---|---|---|
| TvOverlay card fields including id, title, message, source, image, largeIcon, video, corner and duration. | `{"success":true,"message":"Notification received"}` | 400 `bad json`, then peer recheck, then 400 for mapping (`bad image`, `bad style` for corner) or native card validation/admission error. |

```bash
curl -sS -X POST "$compat/notify" --json '{"title":"Update ready","corner":"top_end"}'
```

### `POST /notify_fixed`

| Request | Success | Route errors in check order |
|---|---|---|
| `id`, `visible`, `expiration`, `message`, `iconColor`, `icon`. | `{"success":true,"message":"Notification received"}` for upsert or idempotent delete. | 400 `bad json`, then peer recheck, 400 `bad id`, `bad chip` (visible, expiration or chip fields), `too many chips`, `store failed`. |

```bash
curl -sS -X POST "$compat/notify_fixed" --json '{"id":"status","message":"Ready"}'
```

### `POST /set/overlay`

| Request | Success | Route errors in check order |
|---|---|---|
| Any bounded payload. Contents ignored. | `{"success":true,"message":"ignored by Herald"}` | Listener checks only. |

```bash
curl -sS -X POST "$compat/set/overlay" --json '{}'
```

### `POST /set/notifications`

| Request | Success | Route errors in check order |
|---|---|---|
| Any bounded payload. Contents ignored. | `{"success":true,"message":"ignored by Herald"}` | Listener checks only. |

```bash
curl -sS -X POST "$compat/set/notifications" --json '{}'
```

### `POST /set/settings`

| Request | Success | Route errors in check order |
|---|---|---|
| Any bounded payload. Contents ignored. | `{"success":true,"message":"ignored by Herald"}` | Listener checks only. |

```bash
curl -sS -X POST "$compat/set/settings" --json '{}'
```

### `POST /set/mqtt`

| Request | Success | Route errors in check order |
|---|---|---|
| Any bounded payload. Contents ignored. | `{"success":true,"message":"ignored by Herald"}` | Listener checks only. |

```bash
curl -sS -X POST "$compat/set/mqtt" --json '{}'
```
