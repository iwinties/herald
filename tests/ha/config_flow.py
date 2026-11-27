import io
import ipaddress
import json
import logging
import os
import sys

from fake_herald import begin, pair, run, step, until
from homeassistant import loader
from homeassistant.helpers import issue_registry
from homeassistant.helpers.service_info.zeroconf import ZeroconfServiceInfo


def discovery(box, identity=None):
    ip = ipaddress.ip_address("127.0.0.1")
    return ZeroconfServiceInfo(
        ip_address=ip,
        ip_addresses=[ip],
        port=box.port,
        hostname=box.name + ".local.",
        type="_herald._tcp.local.",
        name=box.name + "._herald._tcp.local.",
        properties={"id": identity or box.identity},
    )


async def exercise(hass, a, b, root):
    from custom_components.herald.diagnostics import async_get_config_entry_diagnostics
    from custom_components.herald.repairs import async_create_fix_flow

    stream = io.StringIO()
    handler = logging.StreamHandler(stream)
    logging.getLogger().addHandler(handler)
    try:
        entry = await pair(hass, a)
        assert entry.data["token"] == a.token and entry.unique_id == a.identity
        print("PASS manual host pairs with the code and stores the token")

        async def discover(box, identity=None):
            return await hass.config_entries.flow.async_init(
                "herald", context={"source": "zeroconf"}, data=discovery(box, identity)
            )

        b.token = "west-fixture"
        flow = await discover(b)
        assert flow["step_id"] == "confirm"
        flow = await step(hass, flow, {})
        assert flow["step_id"] == "code"
        flow = await step(hass, flow, {"code": b.code})
        assert flow["result"].unique_id == b.identity
        print("PASS zeroconf discovery confirms then pairs")
        assert (await loader.async_get_integration(hass, "herald")).name == "Herald"
        assert "herald" in await loader.async_get_config_flows(hass)
        print("PASS the add-integration flow lists Herald")
        await hass.config_entries.async_unload(flow["result"].entry_id)
        counts = len(b.calls)
        assert (await discover(b, a.identity))["reason"] == "already_configured"
        issue = issue_registry.async_get(hass).async_get_issue(
            "herald", "address_" + entry.entry_id
        )
        assert (
            issue.is_fixable
            and issue.data["port"] == b.port
            and entry.data["port"] == a.port
        )
        assert len(b.calls) == counts
        print(
            "PASS a known box at a new address raises a repair issue and keeps the stored address"
        )
        repair = await async_create_fix_flow(hass, issue.issue_id, issue.data)
        repair.hass = hass
        assert (await repair.async_step_init({}))["errors"]["base"] == "wrong_device"
        assert (
            entry.data["port"] == a.port
            and sum(p == "info" for _, p, _ in b.calls[counts:]) == 1
        )
        assert not b.public_auth_seen
        b.identity = a.identity
        b.token = a.token
        old = entry.runtime_data
        assert (await repair.async_step_init({}))["type"] == "create_entry"
        assert entry.data["port"] == b.port and entry.runtime_data is not old
        print(
            "PASS the repair fix re-checks the box id, then updates host and port and reloads"
        )
        a.identity = "third"
        for message, expected in [
            ("screen off", "screen_off"),
            ("pairing disabled", "pairing_disabled"),
            ("too many clients", "too_many_clients"),
        ]:
            a.next["POST", "pair/start"] = (409, {"ok": False, "error": message})
            flow = await begin(hass, "127.0.0.1", a.port)
            assert flow["errors"]["base"] == expected
            hass.config_entries.flow.async_abort(flow["flow_id"])
        flow = await begin(hass, "127.0.0.1", a.port)
        assert (await step(hass, flow, {"code": "invalid"}))["errors"][
            "base"
        ] == "wrong_code"
        a.next["POST", "pair/finish"] = (410, {"ok": False, "error": "pairing expired"})
        assert (await step(hass, flow, {"code": "invalid"}))["errors"][
            "base"
        ] == "expired"
        hass.config_entries.flow.async_abort(flow["flow_id"])
        flow = await begin(hass, "127.0.0.1", 1)
        assert flow["errors"]["base"] == "cannot_connect"
        print("PASS errors map to form errors")
        old = entry.runtime_data
        b.next["GET", "status"] = (401, {"ok": False, "error": "unauthorized"})
        await old.async_refresh()
        await until(lambda: old.event_task.done())
        assert old.auth_failed
        flows = hass.config_entries.flow.async_progress_by_handler("herald")
        flow = next(f for f in flows if f["context"].get("entry_id") == entry.entry_id)
        flow = await step(hass, flow, {})
        flow = await step(hass, flow, {"code": b.code})
        assert flow["reason"] == "reauth_successful"
        await until(
            lambda: (
                getattr(entry, "runtime_data", old) is not old
                and entry.runtime_data.event_task is not None
            )
        )
        print("PASS a 401 stops polling, re-pairs the same box and reloads")
        data = {
            "video": "rtsp://user:SENTINEL-PASS@h/x",
            "mqtt": {"password": "SENTINEL-MQTT"},
        }
        entry.runtime_data.data.update(data)
        b.next["POST", "cards"] = (400, {"ok": False, "error": data["video"]})
        from fake_herald import action

        reply = await action(
            hass,
            "show_card",
            device_id=entry.runtime_data.device_id,
            title="test",
            video={"url": data["video"]},
        )
        from custom_components.herald.client import ApiError

        b.next["GET", "status"] = (
            500,
            {"ok": False, "error": data["video"] + b.code + b.token + b.password},
        )
        try:
            await entry.runtime_data.request("GET", "status")
        except ApiError as exc:
            public_error = str(exc)
        else:
            raise AssertionError("expected a transport exception")
        diagnostic = json.dumps(await async_get_config_entry_diagnostics(hass, entry))
        assert "rtsp://h/x" in diagnostic
        for sentinel in (a.token, "SENTINEL-MQTT", "SENTINEL-PASS", b.code):
            assert (
                sentinel
                not in diagnostic + stream.getvalue() + str(reply) + public_error
            )
        print(
            "PASS diagnostics redact the token, the MQTT password and url credentials (sentinels)"
        )

        flow = await hass.config_entries.flow.async_init(
            "herald", context={"source": "user"}
        )
        settings = (await step(hass, flow, {"next_step_id": "speakers"}))["result"]
        assert settings.unique_id == "speakers" and settings.title == "Herald settings"
        manager = hass.config_entries.options

        async def branch(name):
            menu = await manager.async_init(settings.entry_id)
            assert set(menu["menu_options"]) == {"speakers", "voice"}
            return await manager.async_configure(
                menu["flow_id"], {"next_step_id": name}
            )

        async def voice(fields):
            flow = await branch("voice")
            return await manager.async_configure(flow["flow_id"], fields)

        values = {
            "tts": hass.data["speech_fixture"].entity_id,
            "language": "en",
            "options": {"speed": 2},
        }
        await voice(values)
        saved_voice = dict(settings.options)
        assert saved_voice == {
            "default_tts": values["tts"],
            "default_language": "en",
            "default_tts_options": {"speed": 2},
        }
        print(
            "PASS the options flow stores the default voice (tts entity, language, options)"
        )
        await voice({})
        cleared_without_speakers = dict(settings.options)
        await voice(values)
        flow = await branch("speakers")
        flow = await manager.async_configure(flow["flow_id"], {"speakers": []})
        flow = await manager.async_configure(flow["flow_id"], {})
        night = {
            "night_start": "22:00:00",
            "night_end": "07:00:00",
            "speaker_at_night": "never",
        }
        await manager.async_configure(flow["flow_id"], night)
        saved = dict(settings.options)
        assert saved == {**saved_voice, "speakers": [], "volumes": {}, **night}
        await voice({**values, "language": "fr"})
        assert dict(settings.options) == {**saved, "default_language": "fr"}
        print(
            "PASS the speaker and voice branches keep each other's options in both orders"
        )
        await voice({})
        assert cleared_without_speakers == {} and dict(settings.options) == {
            "speakers": [],
            "volumes": {},
            **night,
        }
        print("PASS the default voice can be cleared without configuring speakers")
    finally:
        logging.getLogger().removeHandler(handler)


if __name__ == "__main__":
    run(exercise)
    sys.stdout.flush()
    sys.stderr.flush()
    # CPython 3.14 segfaults in GC at interpreter shutdown after the HA fixtures stop.
    os._exit(0)
