# A notification from a shell

Use Bash and curl. Set `box` and obtain `HERALD_TOKEN` using the [pairing example](../docs/API.md#pairing-and-device-identity). Keep the credential in the client environment. Each command below targets one box.

```bash
box=http://192.0.2.10:7979
auth() { printf 'Authorization: Bearer %s\n' "$HERALD_TOKEN"; }
curl -fsS "$box/v1/info"

curl -fsS -H @<(auth) "$box/v1/cards" --json \
  '{"id":"update","title":"Update available","message":"Press OK to acknowledge","buttons":[{"id":"ack","label":"OK"}]}'

curl -fsS -H @<(auth) "$box/v1/cards/update"
curl -fsS -H @<(auth) -X DELETE "$box/v1/cards/update"

curl -fsS -H @<(auth) "$box/v1/announce" --json \
  '{"text":"Update ready","request_id":"update-1"}'
```

Reuse `update-1` if retrying that same announcement after losing a reply. Choose a fresh ID for the next announcement. See the [route reference](../docs/API.md) for response fields and error handling.
