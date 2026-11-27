"""T11 physical HA check. The host driver supplies the two marker replies."""

import asyncio
import json
import logging
import re
import secrets
import sys
import tempfile
from contextlib import asynccontextmanager
from pathlib import Path
from unittest.mock import patch
from uuid import uuid4

from check_live_v02 import check, require
from fake_herald import action, begin, environment, step, until
from homeassistant.core import callback
from live_check import marker


class ClipObserver:
    """Use the box's existing session and record only clip keys and HTTP outcomes."""

    def __init__(self, session, operations):
        self.session = session
        self.operations = operations

    @asynccontextmanager
    async def request(self, method, url, **kwargs):
        async with self.session.request(method, url, **kwargs) as response:
            if url.path.startswith("/v1/clips/"):
                key = url.path.removeprefix("/v1/clips/")
                require(re.fullmatch(r"tts-[a-f0-9]{40}", key), "unexpected clip key")
                self.operations.append(
                    dict(method=method, key=key, status=response.status)
                )
            yield response


async def use_fixture_voice(hass):
    flow = await hass.config_entries.flow.async_init(
        "herald", context={"source": "user"}
    )
    settings = (await step(hass, flow, {"next_step_id": "speakers"}))["result"]
    mgr = hass.config_entries.options
    flow = await mgr.async_init(settings.entry_id)
    flow = await mgr.async_configure(flow["flow_id"], {"next_step_id": "voice"})
    tts = hass.data["speech_fixture"].entity_id
    await mgr.async_configure(flow["flow_id"], {"tts": tts})
    require(settings.options.get("default_tts") == tts, "default voice")


async def fresh_id(box, collection, template):
    occupied = {row["id"] for row in (await box.request("GET", collection))[collection]}
    while True:
        key = template.format(secrets.token_hex(2))
        if key not in occupied:
            return key


async def observe_call(hass, box, events, service, record, **fields):
    rid = uuid4().hex
    original = box.client.request

    async def observe(method, path, body=None, **params):
        if method == "POST" and path in ("announce", "cards"):
            speech = body["announce"] if path == "cards" else body
            require(speech.get("request_id") == rid, "outbound request id")
            record["request_id"] = speech["request_id"]
        return await original(method, path, body, **params)

    with patch.object(box.client, "request", observe):
        reply = (
            await action(
                hass, service, device_id=box.device_id, request_id=rid, **fields
            )
        )[box.device_id]
    require(
        reply.get("ok") is True and record.get("request_id") == rid, "service answer"
    )
    job = reply.get("job")
    require(isinstance(job, str) and re.fullmatch(r"[a-f0-9-]{36}", job), "box job id")
    record["job"] = job
    await until(lambda: job in events, 120)
    event = events[job]
    record.update(event_job=event["job"], result=event["result"])
    require(record["result"] == "played", "job did not play")


async def cleanup_answer(client, method, path, body=None):
    from custom_components.herald.client import ApiError

    try:
        answer = await client.request(method, path, body)
    except ApiError as exc:
        return {"ok": False, "status": exc.status, "error": exc.error}
    return {key: answer[key] for key in ("ok", "cleared") if key in answer}


async def exercise(hass, box, root, records, save):
    a, b = records
    first, second = a["detail"], b["detail"]
    events = {}

    @callback
    def receive(event):
        data = event.data
        if (
            data.get("device_id") == box.device_id
            and data.get("type") == "announce.ended"
        ):
            if data.get("result") in ("played", "stopped", "error"):
                events[data["job"]] = {"job": data["job"], "result": data["result"]}

    unsubscribe = hass.bus.async_listen("herald_event", receive)
    try:
        await use_fixture_voice(hass)
        phrase = "Herald speech check " + secrets.token_hex(5)
        card_id = await fresh_id(box, "cards", "t11-{}-voice")
        first["card"] = card_id
        save()
        try:
            observer = ClipObserver(box.client.session, first["clip_ops"])
            with patch.object(box.client, "session", observer):
                for _ in range(2):
                    call = {}
                    first["calls"].append(call)
                    await observe_call(
                        hass,
                        box,
                        events,
                        "show_card",
                        call,
                        id=card_id,
                        title=phrase,
                        announce=True,
                    )
        finally:
            await box.request("DELETE", "cards/" + card_id)
        operations = first["clip_ops"]
        require(
            [(op["method"], op["status"]) for op in operations]
            == [("GET", 404), ("PUT", 200), ("GET", 200)]
            and len({op["key"] for op in operations}) == 1,
            "clip operations",
        )
        require(len({c["job"] for c in first["calls"]}) == 2, "duplicate job")
        a["ok"] = True
        save()

        category = await fresh_id(box, "categories", "t11_{}")
        second["category"] = category
        save()
        try:
            await box.request("PUT", "categories/" + category, {"announce": False})
            await observe_call(
                hass,
                box,
                events,
                "announce",
                second["call"],
                message=phrase,
                show=True,
                card={
                    "title": "Herald title " + category,
                    "category": category,
                    "style": {"duration": 0},
                },
            )
            await marker(root, "v15b.ready", "v15b.seen")
            second["marker"] = "seen"
        finally:
            try:
                answer = await cleanup_answer(
                    box, "POST", "cards/clear", {"category": category}
                )
                second["category_clear"] = answer
                second["cleared"] = answer.get("cleared")
            finally:
                second["category_deleted"] = await cleanup_answer(
                    box, "DELETE", "categories/" + category
                )
    finally:
        unsubscribe()


async def main(host, outfile, root):
    records = [
        dict(
            key="V15/a",
            source="physical",
            ok=False,
            detail={"calls": [], "clip_ops": []},
        ),
        dict(key="V15/b", source="physical", ok=False, detail={"call": {}}),
    ]

    def save():
        # Keep a readable ownership record even while waiting for the operator.
        tmp = outfile.with_suffix(outfile.suffix + ".tmp")
        tmp.write_text("".join(json.dumps(r) + "\n" for r in records))
        tmp.replace(outfile)

    save()
    with tempfile.TemporaryDirectory() as tmp:
        async with environment(Path(tmp), port=18123) as hass:
            flow = await begin(hass, host, 7979)
            require(flow.get("step_id") == "code", "pairing form")
            code = await marker(root, "pair.ready", "code")
            flow = await step(hass, flow, {"code": code})
            require(flow.get("type") == "create_entry", "pairing")
            entry = flow["result"]
            from custom_components.herald.client import HeraldClient

            # Revocation also works if HA paired successfully but entry setup failed.
            owner = HeraldClient(hass, host, 7979, entry.data["token"])
            detail = records[1]["detail"]
            try:
                await exercise(hass, entry.runtime_data, root, records, save)
            finally:
                try:
                    detail["revoked"] = await cleanup_answer(
                        owner, "DELETE", "clients/self"
                    )
                finally:
                    save()
            records[1]["ok"] = True
            try:
                check(records)
            except ValueError:
                records[1]["ok"] = False
                raise
            finally:
                save()


if __name__ == "__main__":
    if len(sys.argv) != 4:
        raise SystemExit("usage: live_check_v02.py HOST OUTPUT SCRATCH")
    # Framework diagnostics may contain endpoints. Only the allowlisted evidence leaves this process.
    logging.disable(logging.CRITICAL)
    try:
        asyncio.run(main(sys.argv[1], Path(sys.argv[2]), Path(sys.argv[3])))
    except Exception as exc:
        raise SystemExit(f"live check failed ({type(exc).__name__})") from None
