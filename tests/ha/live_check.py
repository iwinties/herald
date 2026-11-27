#!/usr/bin/env python3
"""T12 integration readings. The operator's host driver owns both marker replies."""

import asyncio
import base64
import json
import re
import socket
import sys
from datetime import datetime, timedelta
from pathlib import Path
from uuid import uuid4

from fake_herald import ROOT, FakeHerald, action, begin, environment, pair, step, until
from homeassistant.core import callback


async def marker(root, ready, reply):
    answer = root / reply
    answer.unlink(missing_ok=True)
    (root / ready).touch()
    try:
        await until(
            lambda: (
                answer.is_file()
                and (
                    reply != "code"
                    or re.fullmatch(r"[0-9]{6}", answer.read_text().strip())
                )
            ),
            300,
        )
        return answer.read_text().strip() if reply == "code" else None
    finally:
        (root / ready).unlink(missing_ok=True)
        answer.unlink(missing_ok=True)


async def check(hass, real, fake, fixture, root, output):
    events = []

    @callback
    def receive(event):
        if event.data["device_id"] == real.device_id:
            events.append(event.data)

    remove = hass.bus.async_listen("herald_event", receive)
    calls = []
    original = real.client.request

    async def observe(method, path, body=None, **params):
        # Record route, source kind and outcome only. No headers, URLs or contents.
        kind = next(
            (
                k
                for k in ("text", "clip", "audio")
                if isinstance(body, dict) and k in body
            ),
            None,
        )
        try:
            reply = await original(method, path, body, **params)
        except Exception as exc:
            calls.append((method, path, kind, getattr(exc, "status", 0)))
            raise
        calls.append((method, path, kind, 200))
        return reply

    real.client.request = observe
    tts = hass.data["speech_fixture"].entity_id
    phrase = "Herald integration check " + uuid4().hex[:10]
    sound = root / "media" / "herald-test.wav"
    sound.write_bytes((ROOT / "custom_components/herald/chime.wav").read_bytes())

    async def send(name, **fields):
        result = (await action(hass, name, device_id=real.device_id, **fields))[
            real.device_id
        ]
        assert result["ok"]
        return result

    async def played(job):
        await until(
            lambda: any(
                e["type"] == "announce.ended"
                and e["job"] == job
                and e["result"] == "played"
                for e in events
            ),
            120,
        )

    async def play(name, **fields):
        reply = await send(name, **fields)
        await played(reply["job"])
        return reply

    def note(file):
        return base64.b64encode((root / file).read_bytes()).decode()

    async def c02():
        image = root / "media" / "pixel.png"
        image.write_bytes(
            base64.b64decode(
                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAIAAACQd1PeAAAADElEQVR4nGNgaPgPAAIDAYAkYfWXAAAAAElFTkSuQmCC"
            )
        )
        reply = await send(
            "show_card", title="Camera fixture", image={"path": str(image)}
        )
        await until(
            lambda: any(
                e["type"] == "card.media"
                and e["id"] == reply["id"]
                and e["kind"] == "image"
                and e["state"] == "shown"
                for e in events
            )
        )
        await send("clear_card", id=reply["id"])
        return "PNG on allowed path produced card.media image shown"

    async def c12():
        before = len(calls)
        for _ in range(2):
            await play("announce", message=phrase, tts=tts)
        sequence = [(m, s) for m, p, k, s in calls[before:] if p.startswith("clips/")]
        assert sequence == [("GET", 404), ("PUT", 200), ("GET", 200)]
        return "Two completed jobs, first GET 404 and PUT 200, second GET 200"

    async def c15():
        await hass.services.async_call(
            "herald_test_tts", "set_mode", {"mode": "fail"}, blocking=True
        )
        before = len(calls)
        try:
            await play("announce", message=phrase + " fallback", tts=tts)
            assert any(
                m == "GET" and p.startswith("clips/") and s == 404
                for m, p, k, s in calls[before:]
            )
            assert ("POST", "announce", "text", 200) in calls[before:]
        finally:
            await hass.services.async_call(
                "herald_test_tts", "set_mode", {"mode": "ok"}, blocking=True
            )
        return "Confirmed miss, text request and completed job"

    async def c16():
        before = len(calls)
        for file in ("note.m4a", "note.webm"):
            await play("announce", audio=note(file))
        assert calls[before:].count(("POST", "announce", "audio", 200)) == 2
        assert not any(
            m == "PUT" and p.startswith("clips/") for m, p, k, s in calls[before:]
        )
        return "AAC and Opus each completed once, zero clip uploads"

    async def c19():
        for target in ("tv", "speaker"):
            await play("play_sound", path=str(sound), output=target, urgent=True)
        return "Completed house sound on both requested outputs"

    async def c28():
        await play(
            "announce", audio=note("note.m4a"), volume=50, output="tv", chime=True
        )
        await play(
            "announce",
            message="Dashboard message",
            tts=tts,
            volume=50,
            output="tv",
            chime=True,
        )
        return "Dashboard audio and TTS message each completed"

    async def c32():
        fixture.next["POST", "cards"] = (500, {"ok": False, "error": "store failed"})
        res = await action(hass, "show_card", title="Partial delivery")
        good, bad = res[real.device_id], res[fake.device_id]
        assert good["ok"] and good["state"] == "showing" and good["stage"] == "request"
        assert not bad["ok"] and bad["status"] == 500 and bad["stage"] == "request"
        await send("clear_card", id=good["id"])
        return "Real card showing, fixture HTTP 500, both request stages"

    async def c37():
        settings = await real.request("GET", "settings")
        # Use almost the whole day so the host and TV need no shared timezone.
        now = datetime.now().astimezone()

        def hm(delta):
            return (now + timedelta(minutes=delta)).strftime("%H:%M")

        original_settings = {
            "display": {
                k: settings["display"][k] for k in ("night_start", "night_end")
            },
            "audio": {"speaker_at_night": settings["audio"]["speaker_at_night"]},
        }
        try:
            await real.request(
                "PUT",
                "settings",
                {
                    "display": {"night_start": hm(-2), "night_end": hm(-3)},
                    "audio": {"speaker_at_night": "urgent"},
                },
            )
            await play("play_sound", path=str(sound), output="speaker", urgent=True)
            reply = (
                await action(
                    hass,
                    "play_sound",
                    device_id=real.device_id,
                    path=str(sound),
                    output="speaker",
                )
            )[real.device_id]
            assert reply["status"] == 409 and reply["error"] == "quiet hours"
        finally:
            await real.request("PUT", "settings", original_settings)
        return "Urgent speaker job played, ordinary request refused during quiet hours"

    async def c42():
        old = real.data["stream"]
        start = len(events)
        await marker(root, "c42.ready", "c42.go")
        await until(lambda: real.data["stream"] != old, 120)
        # Observe a subsequent long poll across the new cursor as well.
        await asyncio.sleep(26)
        buttons = [e for e in events[start:] if e["type"] == "card.button"]
        assert len(buttons) == 1
        return f"Streams {old} to {real.data['stream']}, one button, zero replays"

    async def c43():
        fields = {"request_id": uuid4().hex, "message": "One repetition check"}
        first = await play("announce", **fields)
        second = await send("announce", **fields)
        assert second.get("duplicate") and second["job"] == first["job"]
        assert (
            sum(
                e["type"] == "announce.started" and e["job"] == first["job"]
                for e in events
            )
            == 1
        )
        return "Duplicate answered with original job, one start and played outcome"

    failed = False
    try:
        for key, probe in [
            ("C02", c02),
            ("C12", c12),
            ("C15", c15),
            ("C16", c16),
            ("C19", c19),
            ("C28", c28),
            ("C32", c32),
            ("C37", c37),
            ("C42", c42),
            ("C43", c43),
        ]:
            try:
                detail = await probe()
                ok = True
            except Exception as exc:
                ok = False
                failed = True
                detail = f"Check failed ({type(exc).__name__})"
            output.write(
                json.dumps(
                    {
                        "key": key + "/int",
                        "source": "physical",
                        "ok": ok,
                        "detail": detail,
                    }
                )
                + "\n"
            )
            output.flush()
    finally:
        real.client.request = original
        remove()
    return not failed


async def main(host, outfile, root):
    with socket.socket() as probe:
        probe.bind(("127.0.0.1", 18123))
    fixture = await FakeHerald("Herald fixture").start()
    try:
        async with environment(root) as hass:
            flow = await begin(hass, host, 7979)
            assert flow["step_id"] == "code"
            code = await marker(root, "pair.ready", "code")
            result = await step(hass, flow, {"code": code})
            assert result["type"] == "create_entry"
            real = result["result"].runtime_data
            fake = (await pair(hass, fixture)).runtime_data
            with outfile.open("w") as output:
                return await check(hass, real, fake, fixture, root, output)
    finally:
        await fixture.runner.cleanup()


if __name__ == "__main__":
    if len(sys.argv) != 4:
        raise SystemExit("Usage: live_check.py HOST OUTPUT SCRATCH")
    raise SystemExit(
        not asyncio.run(main(sys.argv[1], Path(sys.argv[2]), Path(sys.argv[3])))
    )
