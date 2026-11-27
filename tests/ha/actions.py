import json
import os
import sys
from types import SimpleNamespace
from unittest.mock import AsyncMock, patch

from fake_herald import action, pair, run, until
from homeassistant.exceptions import ServiceValidationError


async def exercise(hass, a, b, root):
    x, y = [(await pair(hass, f)).runtime_data for f in (a, b)]

    async def east(service, **kwargs):
        return (await action(hass, service, device_id=x.device_id, **kwargs))[
            x.device_id
        ]

    def last(fake, path="announce"):
        return fake.seen("POST", path)[-1]

    fields = dict(
        id="full",
        category="general",
        title="title",
        message="message",
        image={"url": "https://fixture/image"},
        video={
            "url": "rtsp://fixture/path",
            "muted": True,
            "tcp": False,
            "timeout": 10,
        },
        icon="none",
        buttons=[{"id": "ok", "label": "OK"}],
        priority="high",
        style={"duration": 3},
    )
    fields["from"] = "test"
    assert (await east("show_card", **fields))["ok"] and last(a, "cards") == fields
    print("PASS show_card sends every field")
    with patch(
        "homeassistant.components.camera.async_get_image",
        new=AsyncMock(return_value=SimpleNamespace(content=b"image")),
    ):
        for _ in range(2):
            assert (await east("show_card", image={"camera": "camera.test"}))["ok"]
    key = last(a, "cards")["image"]["key"]
    assert a.images[key] == b"image" and len(a.seen("PUT", "images/" + key)) == 1
    print("PASS show_card pushes a camera image once by its hash")
    assert (await east("show_card", image={"path": "/outside-herald-test"}))[
        "error"
    ] == "path not allowed"
    print("PASS show_card refuses a path outside allowlist_external_dirs")
    with patch(
        "homeassistant.components.camera.async_get_stream_source",
        new=AsyncMock(return_value="rtsp://fixture/camera"),
    ):
        assert (
            await east("show_card", video={"camera": "camera.test", "muted": False})
        )["ok"]
    assert last(a, "cards")["video"] == {"url": "rtsp://fixture/camera", "muted": False}
    print("PASS show_card video from a camera uses its stream source")
    assert (await east("clear_card", id="full"))["found"]
    assert (await east("clear_card", category="general"))["cleared"] == 3
    assert (await east("clear_card", all=True))["cleared"] == 0
    print("PASS clear_card by id, category and all")
    assert (await east("set_chip", id="door", text="Open"))["ok"] and a.chips["door"][
        "text"
    ] == "Open"
    assert (await east("clear_chip", id="door"))["found"] and not a.chips
    print("PASS set_chip and clear_chip")
    speech = hass.data["speech_fixture"]
    args = {"message": "Fixture phrase", "tts": speech.entity_id}

    async def announce(**kw):
        result = await action(hass, "announce", **kw)
        return result

    res = await announce(**args)
    assert all(v["ok"] for v in res.values()), res
    key = last(a)["clip"]
    path = "clips/" + key
    assert speech.calls == 1 and a.clips[key] == b.clips[key]
    await hass.services.async_call("tts", "clear_cache", {}, blocking=True)
    b.clips.clear()
    res = await announce(**args)
    assert all(v["ok"] for v in res.values()) and speech.calls == 2
    assert len(a.seen("GET", path)) == len(b.seen("GET", path)) == 2
    assert len(a.seen("PUT", path)) == 1 and len(b.seen("PUT", path)) == 2
    await announce(**args)
    assert speech.calls == 2
    print(
        "PASS announce checks every box first, plays cached clips directly and generates once only for misses"
    )
    assert (await east("announce", message="Local"))["ok"] and last(a)[
        "text"
    ] == "Local"
    print("PASS announce without tts uses text")
    await hass.services.async_call(
        "herald_test_tts", "set_mode", {"mode": "fail"}, blocking=True
    )
    b.clips.clear()
    res = await announce(**args)
    assert (
        last(a)["clip"] == key
        and last(b)["text"] == args["message"]
        and all(v["ok"] for v in res.values())
    )
    print("PASS a tts failure sends text only to boxes that lack the clip")
    await hass.services.async_call(
        "herald_test_tts", "set_mode", {"mode": "ok"}, blocking=True
    )
    before = len(b.seen("POST", "announce"))
    b.next["PUT", path] = (500, {"ok": False, "error": "store failed"})
    res = await announce(**args)
    assert (
        res[y.device_id]["stage"] == "upload"
        and res[x.device_id]["ok"]
        and len(b.seen("POST", "announce")) == before
    )
    print("PASS a failed upload is reported per box and not converted to text")
    puts = sum(m == "PUT" for f in (a, b) for m, p, _ in f.calls)
    before = [len(f.seen("POST", "announce")) for f in (a, b)]
    await announce(audio="d m9pY2U=")
    assert all(
        last(f)["audio"] == "dm9pY2U=" and len(f.seen("POST", "announce")) == n + 1
        for f, n in zip((a, b), before)
    )
    assert puts == sum(m == "PUT" for f in (a, b) for m, p, _ in f.calls)
    print("PASS announce forwards a voice note once per box without a clip")
    options = dict(volume=27, output="tv", chime=False, urgent=True, duck=True)
    await east("announce", message="Options", **options)
    assert all(last(a)[k] == v for k, v in options.items())
    print("PASS announce passes volume, output, chime, urgent and duck")
    a.drops.add(("POST", "announce"))
    before = len(a.seen("POST", "announce"))
    result = await east("announce", message="Retry")
    posts = a.seen("POST", "announce")[before:]
    assert (
        result["duplicate"]
        and len(posts) == 2
        and posts[0]["request_id"] == posts[1]["request_id"]
    )
    print("PASS announce retries a lost reply once with the same request_id")
    a.drops.add(("POST", "announce"))
    await announce(message="House retry", request_id="house")
    assert all(
        p["request_id"] == "house" for p in a.seen("POST", "announce")[-2:] + [last(b)]
    )
    print("PASS a caller-supplied request_id is used for every retry")
    await east("announce", message="Hello", show=True, **{"from": "House"})
    assert last(a)["card"] == {
        "category": "announcement",
        "message": "Hello",
        "from": "House",
    }
    print("PASS announce with show builds a card")
    file = root / "media" / "bell.wav"
    file.write_bytes(b"sound")
    sound = dict(
        path=str(file), request_id="door", volume=43, output="speaker", urgent=True
    )
    await action(hass, "play_sound", **sound)
    skey = last(a)["clip"]
    b.clips.pop(skey)
    await action(hass, "play_sound", **sound)
    assert (
        len(a.seen("PUT", "clips/" + skey)) == 1
        and len(b.seen("PUT", "clips/" + skey)) == 2
    )
    assert last(b) == {
        "request_id": "door",
        "volume": 43,
        "output": "speaker",
        "urgent": True,
        "chime": False,
        "clip": skey,
    }
    print(
        "PASS play_sound checks every box first, uploads only to misses, and passes output, urgent, volume and request_id"
    )
    jobs = [dict(f.jobs) for f in (a, b)]
    assert all(
        v["duplicate"] for v in (await action(hass, "play_sound", **sound)).values()
    )
    assert jobs == [f.jobs for f in (a, b)]
    print("PASS a repeated play_sound call with the same request_id plays once per box")
    assert (await east("stop"))["ok"] and len(a.seen("POST", "stop")) == 1
    print("PASS stop")
    first = x.snapshots[0]["id"]
    a.settings["device"]["name"] = "Reset B"
    a.emit("settings.changed", revision=2)
    await until(lambda: len(x.snapshots) == 2)
    assert (await east("restore_settings", snapshot=first))["ok"]
    assert a.settings["device"]["name"] == "East" and a.password == "SENTINEL-MQTT"
    assert not any(
        k in json.dumps(a.seen("PUT", "settings")[-1])
        for k in ("password", "builtin", "revision")
    )
    print(
        "PASS restore_settings restores a chosen snapshot: A, then reset B, then restore A, keeping the MQTT password"
    )
    result = await action(hass, "show_card", title="Broadcast")
    assert set(result) == {x.device_id, y.device_id} and all(
        last(f, "cards")["title"] == "Broadcast" for f in (a, b)
    )
    print("PASS an action without device_id goes to every box")
    for ids in ([], ["unknown"], [x.device_id, "unknown"]):
        try:
            await action(hass, "stop", device_id=ids)
        except ServiceValidationError:
            continue
        raise AssertionError("invalid targets accepted")
    print("PASS an explicit empty or unknown device is refused")
    b.next["POST", "announce"] = (409, {"ok": False, "error": "output not available"})
    res = await announce(message="partial")
    assert (
        res[x.device_id]["ok"]
        and res[y.device_id]["stage"] == "request"
        and res[y.device_id]["terminal"]
    )
    print(
        "PASS return_response answers per device and one failing box does not fail the other"
    )
    await east("set_chip", id="door", text="open")
    for collection, fields in {
        "cards": "id category state priority remaining_s",
        "chips": "id text icon color priority look position expires_at",
        "categories": "id name builtin look announce style effective",
        "clients": "client created last_used",
    }.items():
        data = await x.request("GET", collection)
        assert data["ok"] and set(data[collection][0]) == set(fields.split())
    print("PASS collection reads parse the documented example bodies")

    before = [len(f.seen("POST", "cards")) for f in (a, b)]
    result = await action(
        hass, "show_card", announce=True, title="Door", message="Open"
    )
    spoken = last(a, "cards")["announce"]
    assert spoken["text"] == "Door. Open" and spoken == last(b, "cards")["announce"]
    for box, fake, count in zip((x, y), (a, b), before):
        assert len(fake.seen("POST", "cards")) == count + 1
        assert result[box.device_id]["job"] == fake.jobs[spoken["request_id"]]
    print(
        "PASS show_card with announce sends one spoken card per box with the title then the message"
    )

    opts = {"volume": 7, "output": "tv", "chime": False, "urgent": True, "duck": True}
    await east(
        "show_card",
        title="Title",
        announce=True,
        announce_text=" Override ",
        announce_timing="now",
        look="banner",
        tts=speech.entity_id,
        language="en",
        options={},
        request_id="spoken-wire",
        **opts,
    )
    body = last(a, "cards")
    assert body == {
        "title": "Title",
        "look": "banner",
        "announce": {
            **opts,
            "timing": "now",
            "request_id": "spoken-wire",
            "clip": body["announce"]["clip"],
        },
    }
    assert body["announce"]["clip"] in a.clips
    print(
        "PASS show_card announce text, chime, timing and look are passed through and HA-only fields are stripped"
    )

    vectors = [
        (
            "Garage door open",
            "Open for 20 minutes after dark.",
            "Garage door open. Open for 20 minutes after dark.",
        ),
        ("Open!", "Check now", "Open! Check now"),
        ("  Front door  ", "\tunlocked \n", "Front door. unlocked"),
        ("", "Only message", "Only message"),
        ("Only title", "   ", "Only title"),
        ("a" * 121, "Rest", "a" * 120 + ". Rest"),
        ("\u00a0Hi\u00a0", "", "\u00a0Hi\u00a0"),
        ("", "b" * 499 + "😀", "b" * 499),
        ("T", "c" * 1001, "T. " + "c" * 497),
    ]
    for title, message, expected in vectors:
        assert (await east("show_card", announce=True, title=title, message=message))[
            "ok"
        ]
        assert last(a, "cards")["announce"]["text"] == expected
    for override, expected in [
        ("   padded\n", "padded"),
        ("b" * 499 + "😀", "b" * 499),
    ]:
        await east("show_card", announce=True, title="Title", announce_text=override)
        assert last(a, "cards")["announce"]["text"] == expected
    print(
        "PASS spoken text matches the box vectors and an explicit override is trimmed and cut the same way"
    )

    with patch.object(
        hass.data["herald"]["speakers"], "play", new_callable=AsyncMock
    ) as play:
        for toggle in ({}, {"announce": False}):
            await action(
                hass, "show_card", title="Quiet", tts=speech.entity_id, **toggle
            )
            assert all(last(f, "cards") == {"title": "Quiet", **toggle} for f in (a, b))
        play.assert_not_awaited()
    print(
        "PASS show_card without announce omits the key, false sends false, and neither plays on speakers"
    )

    def writes():
        return [
            (m, p)
            for f in (a, b)
            for m, p, _ in f.calls
            if p not in {"events", "status", "settings"}
        ]

    before = writes()
    for fields in (
        {"title": "Title", "announce_text": "  "},
        {"image": {"key": "photo"}},
    ):
        try:
            await action(
                hass, "show_card", announce=True, tts=speech.entity_id, **fields
            )
        except ServiceValidationError as exc:
            assert str(exc) == "text is empty"
        else:
            raise AssertionError("empty speech accepted")
    assert writes() == before
    print(
        "PASS a blank announce text or a media-only spoken card is refused as text is empty before any request"
    )

    fields = dict(announce=True, title="Retry upload", tts=speech.entity_id)
    await east("show_card", **fields)
    path = "clips/" + last(a, "cards")["announce"]["clip"]
    for method, stage in (("GET", "check"), ("PUT", "upload")):
        before = len(b.seen("POST", "cards"))
        b.next[method, path] = (500, {"ok": False, "error": "store failed"})
        result = await action(hass, "show_card", **fields)
        assert result[x.device_id]["ok"] and result[y.device_id]["stage"] == stage
        assert (
            result[y.device_id]["error"] == "store failed"
            and len(b.seen("POST", "cards")) == before
        )
    print(
        "PASS a failed clip check or upload reports that box's error and posts nothing to it"
    )

    a.drops.add(("POST", "cards"))
    before = len(a.seen("POST", "cards"))
    reply = await east("show_card", announce=True, title="Lost reply")
    attempts = a.seen("POST", "cards")[before:]
    assert len(attempts) == 2 and attempts[0] == attempts[1] and reply["duplicate"]
    assert reply["job"] == a.jobs[attempts[0]["announce"]["request_id"]]
    a.next["POST", "cards"] = (409, {"ok": False, "error": "too many cards"})
    assert (await east("show_card", announce=True, title="Full"))[
        "error"
    ] == "too many cards"
    print(
        "PASS a lost spoken card reply is retried once with the same body and request id, and too many cards reaches the caller"
    )

    card = {
        "title": "Custom",
        "from": "Card",
        "category": "alarm",
        "style": {"duration": 0},
    }
    await east("announce", message="Added", show=True, card=card, **{"from": "Outer"})
    assert last(a)["card"] == {**card, "message": "Added"}
    print("PASS announce show keeps the given card fields and adds the message")

    from fake_herald import step

    flow = await hass.config_entries.flow.async_init(
        "herald", context={"source": "user"}
    )
    entry = (await step(hass, flow, {"next_step_id": "speakers"}))["result"]
    hass.config_entries.async_update_entry(
        entry,
        options={
            "default_tts": speech.entity_id,
            "default_language": "fr",
            "default_tts_options": {"speed": 1, "tone": "low"},
        },
    )
    with (
        patch(
            "homeassistant.components.tts.generate_media_source_id",
            return_value="fixture",
        ) as gen,
        patch(
            "homeassistant.components.tts.async_get_media_source_audio",
            new=AsyncMock(return_value=("wav", b"speech")),
        ),
    ):
        await east("announce", message="One")
        gen.assert_called_with(
            hass, "One", speech.entity_id, "fr", {"speed": 1, "tone": "low"}, False
        )
        await east(
            "show_card",
            announce=True,
            title="Two",
            language="en",
            options={"tone": "high"},
        )
        gen.assert_called_with(
            hass, "Two", speech.entity_id, "en", {"speed": 1, "tone": "high"}, False
        )
        await east("announce", message="Three", tts="tts.other")
        gen.assert_called_with(hass, "Three", "tts.other", None, None, False)
    print(
        "PASS calls without tts use the default voice, explicit language and options win"
    )

    for fields in (
        {"name": "My look", "style": {"opacity": 50}},
        {"style": {"corner": "small"}},
    ):
        reply = await east("save_look", id="custom", **fields)
        assert reply["look"] == {
            "id": "custom",
            "name": fields.get("name", "custom"),
            "builtin": False,
            "style": fields["style"],
        }
    assert (await east("delete_look", id="custom"))["ok"] and not a.settings["looks"]
    for service, fields in (("save_look", {"style": {}}), ("delete_look", {})):
        reply = await east(service, id="glass", **fields)
        assert reply["status"] == 409 and reply["error"] == "built-in look"
        before = writes()
        try:
            await east(service, id="bad/id", **fields)
        except Exception as exc:
            import voluptuous as vol

            assert isinstance(exc, (vol.Invalid, ServiceValidationError))
        else:
            raise AssertionError("invalid look id accepted")
        assert writes() == before
    print(
        "PASS save_look creates and replaces, delete_look removes, and a built-in refusal keeps its error"
    )
    for icon in ("image:picture", "emoji:🔔"):
        assert (
            await east("set_chip", id="chip", look="pill", position="top", icon=icon)
        )["ok"]
        assert a.seen("PUT", "chips/chip")[-1] == {
            "look": "pill",
            "position": "top",
            "icon": icon,
        }
    print("PASS set_chip passes look, position and picture or emoji icons")
    b.announce_supported = False
    reply = await action(hass, "show_card", announce=True, title="Older box")
    assert (
        reply[x.device_id]["ok"]
        and not reply[y.device_id]["ok"]
        and reply[y.device_id]["error"] == "unsupported"
    )
    print(
        "PASS a spoken show_card to a box without announce support is reported unsupported"
    )

    a.settings["ntfy"] = {"token": a.token, "token_set": True, "topics": []}
    await x.snapshot()
    assert x.snapshots[-1]["settings"]["ntfy"] == {"topics": []}
    stored = await x.store.async_load()
    assert stored[-1]["settings"]["ntfy"] == {"topics": []}
    print("PASS settings snapshots drop the ntfy token")

    from custom_components.herald.client import ApiError, HeraldClient

    bootstrap = HeraldClient(hass, "127.0.0.1", b.port, token=b.token)
    await bootstrap.request("GET", "info")
    assert not b.public_auth_seen
    requests = b.request_count
    for method, path in (("GET", "status"), ("POST", "pair/start")):
        try:
            await bootstrap.request(method, path)
        except ApiError:
            pass
        else:
            raise AssertionError("unpinned request accepted")
    assert b.request_count == requests
    await b.rotate_certificate()
    requests = b.request_count
    for method, path in (
        ("GET", "status"),
        ("GET", "events"),
        ("POST", "cards"),
        ("PUT", "settings"),
        ("DELETE", "clients/self"),
    ):
        try:
            await y.client.request(method, path)
        except ApiError as exc:
            assert exc.status == 0 and exc.error == "certificate changed"
        else:
            raise AssertionError("rotated certificate accepted")
    assert b.request_count == requests
    print("PASS every request after pairing uses the pinned certificate")


if __name__ == "__main__":
    run(exercise)
    sys.stdout.flush()
    sys.stderr.flush()
    # CPython 3.14 segfaults in GC at interpreter shutdown after the HA fixtures stop.
    os._exit(0)
