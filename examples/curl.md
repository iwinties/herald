# A notification from a shell

Use Bash and curl. Run the [certificate bootstrap](../docs/API.md#herald-https-api-2) to set `box` and verify the public key `pin`, then use the [pairing example](../docs/API.md#pairing-and-device-identity) to obtain `HERALD_TOKEN`. Keep the credential in the client environment. Each command below targets one box.

```bash
box=https://192.0.2.10:7979
herald() {
  local rc
  curl -q --noproxy '*' --insecure --pinnedpubkey "sha256//${pin:?verified pin required}" "$@" || {
    rc=$?
    if [[ $rc == 90 ]]; then echo 'certificate mismatch' >&2; fi
    return "$rc"
  }
}
auth() { printf 'Authorization: Bearer %s\n' "$HERALD_TOKEN"; }
herald -fsS "$box/v1/info"

herald -fsS -H @<(auth) "$box/v1/cards" --json \
  '{"id":"update","title":"Update available","message":"Press OK to acknowledge","buttons":[{"id":"ack","label":"OK"}]}'

herald -fsS -H @<(auth) "$box/v1/cards/update"
herald -fsS -H @<(auth) -X DELETE "$box/v1/cards/update"

herald -fsS -H @<(auth) "$box/v1/announce" --json \
  '{"text":"Update ready","request_id":"update-1"}'
```

Reuse `update-1` if retrying that same announcement after losing a reply. Choose a fresh ID for the next announcement. See the [route reference](../docs/API.md) for response fields and error handling.
