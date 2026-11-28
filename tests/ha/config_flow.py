import io
import ipaddress
import json
import logging
import os
import sys

from contextlib import AsyncExitStack
from unittest.mock import patch

from fake_herald import FakeHerald, begin, run, step, until
from homeassistant import loader
from homeassistant.components.diagnostics import REDACTED
from homeassistant.helpers import issue_registry
from homeassistant.helpers.service_info.zeroconf import ZeroconfServiceInfo


def discovery(box, identity=None, api="2"):
    ip = ipaddress.ip_address("127.0.0.1")
    return ZeroconfServiceInfo(
        ip_address=ip,
        ip_addresses=[ip],
        port=box.port,
        hostname=box.name + ".local.",
        type="_herald._tcp.local.",
        name=box.name + "._herald._tcp.local.",
        properties={"id": identity or box.identity, "api": api},
    )


async def exercise(hass, a, b, root):
    from custom_components.herald.diagnostics import async_get_config_entry_diagnostics
    from custom_components.herald.repairs import async_create_fix_flow

    stream = io.StringIO()
    handler = logging.StreamHandler(stream)
    logging.getLogger().addHandler(handler)
    stack = AsyncExitStack()

    async def extra_box(**kwargs):
        box = await FakeHerald("Fixture", **kwargs).start()
        stack.push_async_callback(box.close)
        return box

    try:
        flow = await begin(hass, "127.0.0.1", a.port)
        assert flow["step_id"] == "code"
        fingerprint = " ".join(
            a.cert_sha256[i : i + 4].upper() for i in range(0, 16, 4)
        )
        assert flow["description_placeholders"]["fingerprint"] == fingerprint
        entry = (await step(hass, flow, {"code": a.code}))["result"]
        assert entry.version == 2 and entry.data["cert_sha256"] == a.cert_sha256
        assert entry.state.value == "loaded"
        print("PASS pairing pins the certificate from info and stores cert_sha256")
        assert entry.data["token"] == a.token and entry.unique_id == a.identity
        print("PASS manual host pairs with the code and stores the token")

        async def discover(box, identity=None, api="2"):
            return await hass.config_entries.flow.async_init(
                "herald",
                context={"source": "zeroconf"},
                data=discovery(box, identity, api),
            )

        b.token = "west-fixture"
        assert (await discover(b, api="1"))["reason"] == "invalid_discovery"
        assert (await discover(b, api=None))["reason"] == "invalid_discovery"
        print("PASS zeroconf needs TXT api 2")
        flow = await discover(b)
        assert flow["step_id"] == "confirm"
        assert flow["description_placeholders"] == {"name": b.name, "host": "127.0.0.1"}
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
        requests = b.request_count
        assert (await repair.async_step_init({}))["errors"]["base"] == "cannot_connect"
        assert b.request_count == requests and entry.data["port"] == a.port
        print(
            "PASS the address repair refuses another certificate without sending a request"
        )
        b = await extra_box(identity=a)
        repair = await async_create_fix_flow(
            hass, issue.issue_id, {**issue.data, "port": b.port}
        )
        repair.hass = hass
        b.next["GET", "info"] = (200, {"ok": True, "app": "herald", "id": "other"})
        assert (await repair.async_step_init({}))["errors"]["base"] == "wrong_device"
        assert entry.data["port"] == a.port and not b.public_auth_seen
        old = entry.runtime_data
        assert (await repair.async_step_init({}))["type"] == "create_entry"
        assert entry.data["port"] == b.port and entry.runtime_data is not old
        print(
            "PASS the repair fix re-checks the box id, then updates host and port and reloads"
        )
        assert b.cert_sha256 == a.cert_sha256
        print("PASS the address repair accepts the same certificate on a new port")
        a.identity = "third"
        base_info = {
            "ok": True,
            "app": "herald",
            "api": 2,
            "id": a.identity,
            "name": a.name,
        }
        for invalid in (
            {"api": 1, "cert_sha256": a.cert_sha256},
            {},
            {"cert_sha256": "z" * 64},
        ):
            starts = len(a.seen("POST", "pair/start"))
            a.next["GET", "info"] = (200, {**base_info, **invalid})
            assert (await begin(hass, "127.0.0.1", a.port))[
                "reason"
            ] == "unsupported_box"
            assert len(a.seen("POST", "pair/start")) == starts
        print("PASS pairing refuses api 1 boxes")
        requests = a.request_count
        a.next["GET", "info"] = (200, {**base_info, "cert_sha256": "00" * 32})
        flow = await begin(hass, "127.0.0.1", a.port)
        assert (
            flow["errors"]["base"] == "cannot_connect"
            and a.request_count == requests + 1
        )
        hass.config_entries.flow.async_abort(flow["flow_id"])
        flow = await begin(hass, "127.0.0.1", a.port)
        assert flow["step_id"] == "code"
        await a.rotate_certificate()
        requests = a.request_count
        result = await step(hass, flow, {"code": a.code})
        assert (
            result["errors"]["base"] == "cannot_connect" and a.request_count == requests
        )
        hass.config_entries.flow.async_abort(flow["flow_id"])
        print("PASS pairing sends the code only over the pinned connection")
        for message, expected in [
            ("screen off", "screen_off"),
            ("overlay not allowed", "overlay_required"),
            ("try later", "pairing_locked"),
            ("pairing disabled", "pairing_disabled"),
            ("too many clients", "too_many_clients"),
        ]:
            a.next["POST", "pair/start"] = (409, {"ok": False, "error": message})
            flow = await begin(hass, "127.0.0.1", a.port)
            assert flow["errors"]["base"] == expected
            hass.config_entries.flow.async_abort(flow["flow_id"])
        a.next["POST", "pair/start"] = (
            409,
            {"ok": False, "error": "pairing in progress"},
        )
        existing = await begin(hass, "127.0.0.1", a.port)
        assert (
            existing["step_id"] == "code"
            and existing["errors"]["base"] == "existing_code"
        )
        assert "fingerprint" in existing["description_placeholders"]
        hass.config_entries.flow.async_abort(existing["flow_id"])
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
        for during_setup in (False, True):
            old = entry.runtime_data
            if during_setup:
                await hass.config_entries.async_unload(entry.entry_id)
            await b.rotate_certificate()
            if during_setup:
                await hass.config_entries.async_setup(entry.entry_id)
            else:
                await old.async_refresh()
                assert old.auth_failed
                await old.async_refresh()
            flows = [
                f
                for f in hass.config_entries.flow.async_progress_by_handler("herald")
                if f["context"].get("entry_id") == entry.entry_id
            ]
            assert len(flows) == 1 and entry.data["cert_sha256"] != b.cert_sha256
            flow = await step(hass, flows[0], {})
            assert (await step(hass, flow, {"code": b.code}))[
                "reason"
            ] == "reauth_successful"
            await until(
                lambda: (
                    getattr(entry, "runtime_data", old) is not old
                    and entry.runtime_data.event_task is not None
                )
            )
            assert entry.data["cert_sha256"] == b.cert_sha256
        print("PASS a changed certificate starts reauth and re-pins")

        await migration_checks(hass, entry, b, await extra_box(plain=True))
        data = {
            "video": "rtsp://user:SENTINEL-PASS@h/x",
            "mqtt": {"password": "SENTINEL-MQTT", "UserName": "fixture-current-user", "enabled": True},
            "ntfy": {"topics": [{"Topic": "fixture-current-topic", "category": "general"}]},
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
        snapshot_settings = {
            "mqtt": {"username": "fixture-saved-user", "port": 1883},
            "ntfy": {"topics": [{"topic": "fixture-first-topic"}, {"topic": "fixture-second-topic"}]},
        }
        entry.runtime_data.snapshots.append({"id": "fixture-snapshot", "settings": snapshot_settings})
        report = await async_get_config_entry_diagnostics(hass, entry)
        diagnostic = json.dumps(report)
        for value in ("fixture-current-user", "fixture-saved-user", "fixture-current-topic",
                      "fixture-first-topic", "fixture-second-topic"):
            assert value not in diagnostic
        assert report["status"]["mqtt"] == {"password": REDACTED, "username": REDACTED, "enabled": True}
        assert report["status"]["ntfy"]["topics"] == [{"topic": REDACTED, "category": "general"}]
        saved = report["snapshots"][-1]["settings"]
        assert saved["ntfy"]["topics"] == [{"topic": REDACTED}, {"topic": REDACTED}]
        assert saved["mqtt"] == {"username": REDACTED, "port": 1883}
        original = entry.runtime_data.snapshots[-1]["settings"]
        assert original["ntfy"]["topics"][0]["topic"] == "fixture-first-topic"
        assert original["mqtt"]["username"] == "fixture-saved-user"
        print("PASS diagnostics redact MQTT usernames and ntfy topics in status and saved snapshots")
        assert "rtsp://h/x" in diagnostic and b.cert_sha256 in diagnostic
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
        from custom_components.herald import async_migrate_entry

        hass.config_entries.async_update_entry(settings, version=1)
        assert await async_migrate_entry(hass, settings)
        assert settings.version == 2 and settings.data == {}
        hass.config_entries.async_update_entry(settings, version=3)
        assert not await async_migrate_entry(hass, settings)
        hass.config_entries.async_update_entry(settings, version=2)
        print("PASS the Herald settings entry migrates to version 2 unchanged")
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
        await stack.aclose()


async def migration_checks(hass, entry, box, plain):
    from custom_components.herald import async_migrate_entry
    from custom_components.herald.client import HeraldClient

    manager = hass.config_entries
    await manager.async_unload(entry.entry_id)
    legacy = {k: v for k, v in entry.data.items() if k != "cert_sha256"}
    manager.async_update_entry(entry, version=1, data={**legacy, "port": plain.port})
    assert await async_migrate_entry(hass, entry)
    assert entry.version == 2 and entry.data["cert_sha256"] is None
    assert plain.request_count == 0
    await manager.async_setup(entry.entry_id)
    assert entry.state.value == "setup_retry" and entry.data["cert_sha256"] is None
    assert plain.request_count == 0
    print("PASS a plain-HTTP 0.2 box leaves the entry not ready")

    manager.async_update_entry(entry, data={**legacy, "cert_sha256": None})
    request = HeraldClient.request
    info_calls = []

    async def observe(client, method, path, *args, **kwargs):
        if path == "info":
            assert entry.data["cert_sha256"] is None and client.token is None
            info_calls.append(
                client.fingerprint.fingerprint.hex() if client.fingerprint else None
            )
        return await request(client, method, path, *args, **kwargs)

    # A claimed pin must survive a real handshake before HA saves it.
    box.next["GET", "info"] = (
        200,
        {
            "ok": True,
            "app": "herald",
            "api": 2,
            "id": entry.unique_id,
            "cert_sha256": "00" * 32,
        },
    )
    requests = box.request_count
    await manager.async_reload(entry.entry_id)
    assert entry.state.value == "setup_retry" and entry.data["cert_sha256"] is None
    assert box.request_count == requests + 1
    with patch.object(HeraldClient, "request", observe):
        await manager.async_reload(entry.entry_id)
    assert info_calls == [None, box.cert_sha256] and entry.state.value == "loaded"
    assert dict(entry.data) == {**legacy, "cert_sha256": box.cert_sha256}
    assert not box.public_auth_seen
    print(
        "PASS a migrated 0.2 entry confirms the pin on a second TLS request before saving it"
    )


if __name__ == "__main__":
    run(exercise)
    sys.stdout.flush()
    sys.stderr.flush()
    # CPython 3.14 segfaults in GC at interpreter shutdown after the HA fixtures stop.
    os._exit(0)
