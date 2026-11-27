import os
import sys
import time

from fake_herald import pair, run, until


async def exercise(hass, a, b, root):
    # Old events must already be behind the first cursor when the entity loads.
    a.emit("card.button", id="historical", button="go")
    box = (await pair(hass, a)).runtime_data
    await pair(hass, b)
    observed = []
    from homeassistant.core import callback

    @callback
    def receive(event):
        if event.data["device_id"] == box.device_id:
            observed.append(event.data)

    hass.bus.async_listen("herald_event", receive)

    def state(domain, suffix):
        return hass.states.get(f"{domain}.east_{suffix}")

    a.emit("sound", playing=True)
    a.emit("screen", on=False)
    await until(
        lambda: (
            state("binary_sensor", "sound_playing").state == "on"
            and state("binary_sensor", "screen_on").state == "off"
        )
    )
    print("PASS events update sound_playing and screen_on at once")
    a.emit("card.button", id="new", button="ok")
    await until(
        lambda: state("event", "button").attributes.get("event_type") == "button"
    )
    assert state("event", "button").attributes["card"] == "new"
    assert [e["id"] for e in observed if e["type"] == "card.button"] == ["new"]
    print("PASS a button event fires herald_event and changes the event entity's state")
    for flag in ("gap", "reset"):
        before = len(a.seen("GET", "status"))
        a.next["GET", "events"] = (
            200,
            {
                "ok": True,
                "stream": a.stream,
                "gap": flag == "gap",
                "reset": flag == "reset",
                "seq": 0,
                "events": [],
            },
        )
        a.emit("screen", on=True)
        await until(lambda: len(a.seen("GET", "status")) > before)
    print("PASS a gap or reset refreshes status")
    before = len(a.seen("GET", "status"))
    a.restart()
    await until(lambda: box.data["stream"] == a.stream)
    assert len(a.seen("GET", "status")) > before
    a.emit("card.button", id="after-restart", button="yes")
    await until(
        lambda: state("event", "button").attributes.get("card") == "after-restart"
    )
    print("PASS a new stream id resets the cursor")
    for kind, fields in [
        ("card.button", {"button": "x"}),
        ("card.ended", {"reason": "dismissed"}),
    ]:
        a.emit(kind, id="stale-" + kind, time=int(time.time() * 1000) - 31000, **fields)
        await until(lambda: box.data["last_card"].get("id") == "stale-" + kind)
    assert state("event", "button").attributes["card"] == "after-restart"
    assert all(not e.get("id", "").startswith("stale") for e in observed)
    print("PASS stale button events are dropped in every batch")
    a.next["GET", "events"] = (500, {"ok": False})
    a.emit("screen", on=True)
    await until(lambda: ("GET", "events") not in a.next)
    start = time.monotonic()
    a.emit("card.button", id="recovered", button="y")
    await until(lambda: state("event", "button").attributes.get("card") == "recovered")
    assert time.monotonic() - start >= 4.8
    print("PASS a lost connection backs off and recovers")
    for i in range(4):
        a.settings["device"]["name"] = str(i)
        a.emit("settings.changed", revision=i + 2)
        await until(lambda: box.snapshots[-1]["settings"]["device"]["name"] == str(i))
    old = [s["id"] for s in box.snapshots]
    a.settings["revision"] += 1
    a.emit("settings.changed", revision=20)
    await until(lambda: any(e.get("revision") == 20 for e in observed))
    assert (
        len(old) == 3
        and [s["id"] for s in box.snapshots] == old
        and await box.store.async_load() == box.snapshots
    )
    print(
        "PASS settings.changed stores a snapshot, keeps three and skips identical ones"
    )
    await hass.services.async_call(
        "notify",
        "send_message",
        {"entity_id": "notify.east_notify", "title": "Header", "message": "Body"},
        blocking=True,
    )
    assert a.seen("POST", "cards")[-1] == {
        "title": "Header",
        "message": "Body",
        "category": "general",
    }
    print("PASS the notify entity shows a general card with its title")


if __name__ == "__main__":
    run(exercise)
    sys.stdout.flush()
    sys.stderr.flush()
    # CPython 3.14 segfaults in GC at interpreter shutdown after the HA fixtures stop.
    os._exit(0)
