import asyncio
import io
import os
import struct
import sys
import wave
from datetime import datetime, timezone
from pathlib import Path
from unittest.mock import patch

from aiohttp import ClientSession
from fake_herald import action, pair, run, step


async def exercise(hass, a, b, root, restart):

    boxes = [(await pair(hass, f)).runtime_data for f in (a, b)]
    players = hass.data["cast_fixture"]
    ids = [p.entity_id for p in players]
    flow = await hass.config_entries.flow.async_init(
        "herald", context={"source": "user"}
    )
    flow = await step(hass, flow, {"next_step_id": "speakers"})
    entry = flow["result"]
    mgr = hass.config_entries.options
    flow = await mgr.async_init(entry.entry_id)
    assert flow["step_id"] == "init"
    flow = await mgr.async_configure(flow["flow_id"], {"next_step_id": "speakers"})
    flow = await mgr.async_configure(flow["flow_id"], {"speakers": ids})
    assert flow["step_id"] == "volumes"
    flow = await mgr.async_configure(flow["flow_id"], {e: 44 for e in ids})
    assert flow["step_id"] == "night"
    await mgr.async_configure(
        flow["flow_id"],
        {
            "night_start": "22:00:00",
            "night_end": "07:00:00",
            "speaker_at_night": "always",
        },
    )
    p, q = players
    tts = hass.data["speech_fixture"].entity_id

    async def announce(**fields):
        return await action(
            hass, "announce", message="Fixture voice", tts=tts, **fields
        )

    async def first(**fields):
        return (await announce(speakers=[ids[0]], **fields))[ids[0]]

    assert (await first())["ok"]
    url = next(c[1] for c in p.calls if c[0] == "play_media")
    async with ClientSession() as session:
        async with session.get(url) as response:
            assert response.status == 200
            clip = await response.read()
        async with session.get(url.split("?")[0]) as response:
            assert response.status == 401
    with wave.open(io.BytesIO(clip)) as wav:
        assert wav.getframerate() == 22050 and wav.getnchannels() == 1
        pcm = wav.readframes(wav.getnframes())
    with wave.open(
        str(Path(__file__).resolve().parents[2] / "custom_components/herald/chime.wav")
    ) as wav:
        chime = wav.readframes(wav.getnframes())
    assert pcm.startswith(chime + b"\0" * (4410 * 2))
    voice = pcm[len(chime) + 4410 * 2 :]
    # HA may pad the synthesized second when encoding its media-source response.
    samples = [v[0] for v in struct.iter_unpack("<h", voice)]
    assert 22050 <= len(samples) <= 24500
    early = max(map(abs, samples[:440]))
    middle = max(map(abs, samples[5000:15000]))
    assert samples[0] == 0 and middle > 1000 and early < middle / 2
    print(
        "PASS speakers get the chime and the message as one clip served by Herald's view"
    )
    assert p.calls[0] == ("volume_set", 0.44) and p.calls[-1] == ("volume_set", 0.3)
    print(
        "PASS a speaker's volume is set to its announce volume and restored afterwards"
    )
    p.state_as("playing")
    assert (await first())["skipped"] == "playing" and (await first(urgent=True))["ok"]
    print("PASS a playing speaker is skipped unless the announcement is urgent")
    hass.config_entries.async_update_entry(
        entry, options={**entry.options, "speaker_at_night": "urgent"}
    )
    with patch(
        "custom_components.herald.speakers.dt.now",
        return_value=datetime(2026, 11, 26, 23, tzinfo=timezone.utc),
    ):
        assert (await first())["skipped"] == "quiet hours" and (
            await first(urgent=True)
        )["ok"]
    hass.config_entries.async_update_entry(
        entry, options={**entry.options, "speaker_at_night": "always"}
    )
    print("PASS the night rule keeps speakers quiet unless urgent")
    assert set(await announce()) == {box.device_id for box in boxes} | set(ids)
    assert set(await announce(device_id=boxes[1].device_id, speakers=[ids[1]])) == {
        boxes[1].device_id,
        ids[1],
    }
    print(
        "PASS a call without targets goes to every box and every configured speaker; a call naming targets uses only those"
    )
    await first(request_id="unique")
    n = len(p.calls)
    assert (await first(request_id="unique"))["duplicate"]
    assert len(p.calls) == n
    hass = await restart()
    p, q = hass.data["cast_fixture"]
    assert (await first(request_id="unique"))["duplicate"] and not p.calls
    print(
        "PASS a repeated request_id never plays twice on a speaker, also after a restart"
    )
    p.state_as("off")
    assert (await first())["ok"] and p.calls[-1] == ("turn_off",) and p.state == "off"
    print("PASS a speaker that was off is turned off again afterwards")
    p.fail = True
    res = await announce()
    assert not res[p.entity_id]["ok"] and all(
        res[k]["ok"] for k in [q.entity_id, *(b.device_id for b in boxes)]
    )
    p.fail = False
    print("PASS a failing speaker does not fail the boxes or other speakers")
    file = Path(__file__).resolve().parents[2] / "custom_components/herald/chime.wav"
    sound = root / "media" / "bell.wav"
    sound.write_bytes(file.read_bytes())
    assert (await action(hass, "play_sound", speakers=[p.entity_id], path=str(sound)))[
        p.entity_id
    ]["ok"]
    url = [c[1] for c in p.calls if c[0] == "play_media"][-1]
    async with ClientSession() as session:
        async with session.get(url) as response:
            assert (
                response.status == 200 and await response.read() == sound.read_bytes()
            )
    print("PASS play_sound casts to speakers without the chime")
    n = len(p.calls)
    p.state_as("idle")
    res = await asyncio.gather(*(first() for _ in range(7)))
    assert sum(v.get("skipped") == "busy" for v in res) == 1
    assert [c[0] for c in p.calls[n:]] == [
        "volume_set",
        "play_media",
        "volume_set",
    ] * 6, (
        [c[0] for c in p.calls[n:]],
        [(v.get("stage"), v.get("error"), v.get("skipped")) for v in res],
    )
    print("PASS announcements to one speaker run one at a time with at most 5 waiting")

    entry = next(
        e
        for e in hass.config_entries.async_entries("herald")
        if e.unique_id == "speakers"
    )
    hass.config_entries.async_update_entry(
        entry,
        options={
            **entry.options,
            "volumes": dict(zip(ids, (34, 62))),
            "default_tts": tts,
        },
    )

    async def card(**fields):
        return await action(
            hass, "show_card", title="Cast card", announce=True, **fields
        )

    for player in (p, q):
        player.state_as("idle")
        player.calls.clear()
    result = await card()
    for player, level in ((p, 0.34), (q, 0.62)):
        assert result[player.entity_id]["ok"] and ("volume_set", level) in player.calls
        assert any(call[0] == "play_media" for call in player.calls)
    print("PASS a spoken show_card plays on the configured speakers at their volumes")
    result = await card(chime=False)
    assert result[p.entity_id]["ok"]
    url = [c[1] for c in p.calls if c[0] == "play_media"][-1]
    key = a.seen("POST", "cards")[-1]["announce"]["clip"]
    async with ClientSession() as session, session.get(url) as response:
        assert response.status == 200 and await response.read() == a.clips[key]
    print("PASS chime false also drops the chime on speakers")
    box_ids = [box.device_id for box in boxes]
    for targets, expected in [
        ({"speakers": [ids[1]]}, {ids[1]}),
        ({"device_id": box_ids[0]}, {box_ids[0]}),
        ({"device_id": box_ids[1], "speakers": [ids[0]]}, {box_ids[1], ids[0]}),
        ({}, {*box_ids, *ids}),
    ]:
        counts = [len(f.seen("POST", "cards")) for f in (a, b)] + [
            len(player.calls) for player in (p, q)
        ]
        result = await card(**targets)
        assert set(result) == expected and all(r["ok"] for r in result.values())
        after = [len(f.seen("POST", "cards")) for f in (a, b)] + [
            len(player.calls) for player in (p, q)
        ]
        assert {
            k for k, old, new in zip(box_ids + ids, counts, after) if new > old
        } == expected
    print(
        "PASS speaker-only, box-only, mixed and untargeted spoken show_cards reach exactly their targets"
    )


if __name__ == "__main__":
    run(exercise, cast=True)
    sys.stdout.flush()
    sys.stderr.flush()
    # CPython 3.14 segfaults in GC at interpreter shutdown after the HA fixtures stop.
    os._exit(0)
