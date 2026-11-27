# Show ntfy messages on your TV

Herald can show messages from up to four topics on one ntfy server. Receiving must be on in **Setup**. To subscribe, open **ntfy** after **MQTT** in the TV settings.

1. Set **Server** to your ntfy base URL. The default is `https://ntfy.sh`. You can include a path prefix. Leave off the trailing slash.
2. For a protected topic, enter a bearer token in **Token**. The TV shows whether a token is set. Use **Clear token** to remove it.
3. Choose **Add topic** and enter the exact topic name. Add a label for messages that arrive without a title. Choose a category and, optionally, a look. Turn on **Speak priority 5** for each topic you want to hear.
4. Set **Catch-up (seconds)** and switch **Enabled** on. **Status** shows the combined connection state. Use **Remove topic** to unsubscribe from one topic, or turn **Enabled** off to stop all streams.

## Set up through the API

Send a partial document to the paired, pinned HTTPS endpoint with `PUT /v1/settings`. This example uses a placeholder server and topic. Supplying `topics` replaces the entire list. Omitting it keeps the current list.

```json
{
  "ntfy": {
    "enabled": true,
    "server": "https://notify.example",
    "catch_up": 120,
    "topics": [
      {"topic": "deliveries", "label": "Delivery", "category": "general", "look": null, "speak": false}
    ]
  }
}
```

Topic names are unique, case-sensitive, and 1–64 ASCII letters, digits, underscores or hyphens. A label can use up to 40 UTF-16 code units. The category defaults to `general`. A null look uses the category look. An explicit look must exist in the resulting settings document. Deleting a custom look clears topic selections that used it.

With HTTPS, the server must present a certificate the TV trusts. Plain HTTP is available only for a numeric address in 10/8, 172.16/12, 192.168/16 or IPv6 fc00::/7. Server URLs exclude user information, query strings and fragments. A redirect ends the connection.

`ntfy.token` accepts a string of 1–256 characters without whitespace. An empty string keeps the current token. JSON null clears it. GET returns only `token_set`, which is ignored on PUT. Changing the canonical server clears the token unless the same patch supplies a new nonempty token. Scheme and host are case-insensitive, and the path prefix is part of the binding.

## Message mapping

| ntfy field | Herald card |
|---|---|
| `event`, `topic`, `id`, `time` | Accept a message for the exact subscribed topic with an alphanumeric ID of 1–64 characters and an integer timestamp. |
| `title` | Message title, then topic label, then “Notification”. |
| `message` | Plain text, including literal Markdown. |
| `priority` | 1–2 low, 3 or absent normal, 4–5 high. Priority 5 uses the reminder category when the topic category is general. |
| `tags` | First recognized tag in publisher order supplies the icon, otherwise no icon. |
| Category and look | Use the topic selection. Duration follows the effective category duration up to 30 seconds. A sticky category shows for 30 seconds. |
| Speech | Silent unless the topic opts in and priority is 5. Show the card immediately and request nonurgent speech without a chime. |
| Identity | `nt_` followed by the first 32 hexadecimal digits of SHA-256 over `canonical server\|topic\|message id`. |

| Tag | Icon | Tag | Icon |
|---|---|---|---|
| warning | ⚠️ | rotating_light | 🚨 |
| white_check_mark | ✅ | heavy_check_mark | ✔️ |
| x | ❌ | bell | 🔔 |
| door | 🚪 | house | 🏠 |
| package | 📦 | camera | 📷 |
| tv | 📺 | fire | 🔥 |
| droplet | 💧 | zap | ⚡ |
| information_source | ℹ️ | tada | 🎉 |
| +1 | 👍 | -1 | 👎 |
| loudspeaker | 📢 | calendar | 📅 |

## Catch-up and privacy

Catch-up defaults to 120 seconds and accepts 0–600. Zero requests only live events and disables the age filter. With a positive window Herald resumes from the last handled message ID when it is younger than the window. Otherwise it requests that many seconds of history. Expired messages, duplicates among the last 256 handled IDs, and messages older than the window are skipped. These skips advance the cursor. Keepalives and other topics leave it alone. Cursor history is separate for every server and topic.

On public ntfy.sh, anyone who knows an unreserved topic name may read or publish messages. Treat the name as a secret. Use a reserved topic with token-based access control or a self-hosted server for private notifications. A token on a plain HTTP connection travels unencrypted. Herald’s ntfy logs identify subscriptions by index and omit topic names, message text and tokens.

## Connection status

| Status | What to check |
|---|---|
| `off` | Enable ntfy and add at least one topic. |
| `connecting` | A stream is opening. Check connectivity and the server address if this persists. |
| `connected` | All configured streams are connected. If no card appears, check the exact topic and the message age. |
| `error` | Check connectivity, certificate trust, the path prefix and server availability. Enter a redirect’s final server URL directly. Transient failures retry with backoff. |
| `unauthorized` | A server returned 401 or 403. Correct the token or topic permissions and change the settings to reconnect. |

With multiple topics, authorization errors take precedence over other errors, then connecting, then connected. The same combined state is available in `GET /v1/status` under `ntfy`.
