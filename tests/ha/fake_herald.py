"""Loopback Herald transport and the shared native Home Assistant test harness."""

import asyncio
import re
import secrets
import shutil
import tempfile
import time
from contextlib import AsyncExitStack, asynccontextmanager
from copy import deepcopy
from pathlib import Path
from uuid import uuid4

from aiohttp import web

ROOT = Path(__file__).resolve().parents[2]


# The fixture's general category uses the glass look.
GENERAL_STYLE = {
    "placement": "top_right",
    "size": "medium",
    "text_size": "medium",
    "accent": "#4FC3F7",
    "background": "#1C202A",
    "opacity": 60,
    "icon": "info",
    "duration": 10,
    "stacking": "queue",
    "sound": "none",
    "sound_volume": 60,
    "video_sound": "off",
    "show_here": True,
    "over_playback": "show",
    "night": "normal",
    "focus": "auto",
    "animation": "slide",
    "layout": "card",
    "corner": "large",
    "border": "thin",
    "border_color": "#5A6272",
    "shadow": "soft",
    "glow": "none",
    "accent_style": "badge",
    "title_color": "#FFFFFF",
    "text_color": "#FFFFFF",
    "title_weight": "bold",
    "label": "none",
}


class FakeHerald:
    def __init__(self, name):
        self.name = name
        self.identity = str(uuid4())
        self.token = "SENTINEL-TOKEN"
        self.code = str(secrets.randbelow(1000000)).zfill(6)
        self.stream = secrets.token_hex(8)
        self.events = []
        self.wake = asyncio.Event()
        self.calls = []
        self.public_auth_seen = False
        self.next = {}
        self.drop_after_read = set()
        self.drops = set()
        self.delays = {}
        self.cards = {}
        self.chips = {}
        self.images = {}
        self.clips = {}
        self.jobs = {}
        self.announce_supported = True
        self.password = "SENTINEL-MQTT"
        self.settings = {
            "schema": 2,
            "looks": [],
            "revision": 1,
            "device": {"name": name, "port": 7979},
            "display": {
                "night_start": "22:00",
                "night_end": "07:00",
                "max_visible": 3,
                "chip_look": "glass",
            },
            "audio": {
                "speaker_at_night": "urgent",
                "volume_tv": 70,
                "volume_speaker": 50,
                "chime": True,
            },
            "mqtt": {"password_set": True},
            "categories": [
                {
                    "id": "general",
                    "name": "General",
                    "builtin": True,
                    "style": {},
                    "look": "glass",
                    "announce": False,
                }
            ],
        }
        self.flags = {
            "screen_on": True,
            "sound_playing": False,
            "overlay_allowed": True,
            "last_card": {},
        }
        self.pairing = False
        self.authenticated = True

    async def start(self):
        app = web.Application(client_max_size=4194305)
        app.router.add_route("*", "/v1/{tail:.*}", self.handle)
        self.runner = web.AppRunner(app, access_log=None, shutdown_timeout=0.1)
        await self.runner.setup()
        site = web.TCPSite(self.runner, "127.0.0.1", 0)
        await site.start()
        self.port = self.runner.addresses[0][1]
        return self

    def emit(self, kind, **fields):
        self.events.append(
            {
                "seq": len(self.events) + 1,
                "time": int(time.time() * 1000),
                "type": kind,
                **fields,
            }
        )
        self.wake.set()
        self.wake = asyncio.Event()

    def restart(self):
        self.stream = secrets.token_hex(8)
        self.events.clear()
        self.wake.set()
        self.wake = asyncio.Event()

    def seen(self, method, path):
        return [body for m, p, body in self.calls if m == method and p == path]

    async def handle(self, req):
        path = req.match_info["tail"]
        method = req.method

        def ok(**data):
            return web.json_response({"ok": True, **data})

        def fail(status, reason):
            return web.json_response({"ok": False, "error": reason}, status=status)

        group, sep, key = path.partition("/")
        limit = {
            ("POST", "cards"): 1572864,
            ("POST", "announce"): 3145728,
            ("PUT", "clips"): 4194304,
            ("PUT", "images"): 2097152,
        }.get((method, group), 65536)
        if (req.content_length or 0) > limit:
            return fail(413, "body too large")
        public = (method, path) in {
            ("GET", "info"),
            ("POST", "pair/start"),
            ("POST", "pair/finish"),
        }
        if public and "Authorization" in req.headers:
            self.public_auth_seen = True
        if not public:
            bearer, _, token = req.headers.get("Authorization", "").partition(" ")
            if (
                not self.authenticated
                or bearer.lower() != "bearer"
                or token != self.token
            ):
                return fail(401, "unauthorized")
        allowed = {
            "info": {"GET"},
            "pair/start": {"POST"},
            "pair/finish": {"POST"},
            "clients": {"GET"},
            "clients/self": {"DELETE"},
            "status": {"GET"},
            "events": {"GET"},
            "cards": {"GET", "POST"},
            "cards/clear": {"GET", "POST", "DELETE"},
            "chips": {"GET", "PUT"},
            "settings": {"GET", "PUT"},
            "categories": {"GET"},
            "looks": {"GET"},
            "announce": {"POST"},
            "stop": {"POST"},
        }.get(path)
        if allowed is None and sep and key and "/" not in key:
            allowed = {
                "images": {"GET", "PUT"},
                "clips": {"GET", "PUT"},
                "cards": {"GET", "DELETE"},
                "categories": {"DELETE", "PUT"},
                "looks": {"DELETE", "PUT"},
                "chips": {"DELETE", "PUT"},
            }.get(group)
        if not allowed:
            return fail(404, "unknown path")
        if method not in allowed:
            return fail(405, "method not allowed")
        if group in {"images", "clips"} and not re.fullmatch(
            r"[a-zA-Z0-9_-][a-zA-Z0-9_.-]{0,159}"
            if group == "clips"
            else r"[a-zA-Z0-9_-][a-zA-Z0-9_.-]{0,63}",
            key,
        ):
            return fail(400, "bad key")
        raw = await req.read()
        try:
            body = (
                await req.json()
                if raw and req.content_type == "application/json"
                else raw
            )
        except ValueError:
            return fail(400, "bad json")
        self.calls.append(
            (method, path, None if path.startswith("pair/") else deepcopy(body))
        )

        if (method, path) in self.drop_after_read:
            self.drop_after_read.remove((method, path))
            req.transport.close()
            return web.Response()
        if delay := self.delays.get((method, path)):
            await asyncio.sleep(delay)
        if preset := self.next.pop((method, path), None):
            return web.json_response(preset[1], status=preset[0])
        if path == "info" and method == "GET":
            return ok(
                app="herald",
                api=1,
                version="0.2.0",
                id=self.identity,
                name=self.name,
                model="Fixture",
                pairing=True,
            )
        if path == "pair/start" and method == "POST":
            self.pairing = True
            return ok(expires_in=120)
        if path == "pair/finish" and method == "POST":
            if not self.pairing:
                return fail(404, "no pairing")
            if body.get("code") != self.code:
                return fail(403, "wrong code")
            self.pairing = False
            self.authenticated = True
            return ok(id=self.identity, token=self.token)
        if method == "GET" and path == "status":
            return ok(
                id=self.identity,
                api=1,
                name=self.name,
                version="0.2.0",
                stream=self.stream,
                event_seq=len(self.events),
                quiet_seconds=4,
                battery_exempt=True,
                tts_ready=True,
                outputs=["tv", "speaker"],
                audio_busy=False,
                last_audio="played",
                temp_files=0,
                cards_visible=len(self.cards),
                cards_queued=0,
                chips=len(self.chips),
                clips=len(self.clips),
                images=len(self.images),
                settings_revision=self.settings["revision"],
                mqtt="off",
                compat="off",
                **self.flags,
            )
        if path == "events":
            after = int(req.query["after"])
            if after == len(self.events) and int(req.query["wait"]):
                try:
                    await asyncio.wait_for(self.wake.wait(), int(req.query["wait"]))
                except TimeoutError:
                    pass
            return ok(
                stream=self.stream,
                seq=len(self.events),
                gap=after < len(self.events) - 256,
                reset=after > len(self.events),
                events=self.events[max(after, len(self.events) - 256) :],
            )
        if path == "settings":
            if method == "PUT":
                for k, v in deepcopy(body).items():
                    if k == "categories":
                        builtins = {
                            r["id"] for r in self.settings[k] if r.get("builtin")
                        }
                        self.settings[k] = [
                            dict(r, builtin=r["id"] in builtins) for r in v
                        ]
                    elif isinstance(v, dict):
                        if k == "mqtt" and "password" in v:
                            self.password = v.pop("password") or self.password
                        self.settings.setdefault(k, {}).update(v)
                    else:
                        self.settings[k] = v
                self.settings["revision"] += 1
                self.emit("settings.changed", revision=self.settings["revision"])
            return ok(**self.settings)
        if group == "looks":
            builtins = ("classic", "glass", "minimal", "banner", "alert", "pill")
            rows = self.settings["looks"]
            if not sep:
                return ok(
                    looks=[
                        *[dict(id=k, name=k, style={}, builtin=True) for k in builtins],
                        *[dict(r, builtin=False) for r in rows],
                    ]
                )
            if key in builtins:
                return fail(409, "built-in look")
            old = next((r for r in rows if r["id"] == key), None)
            if old:
                rows.remove(old)
            if method == "DELETE":
                return ok() if old else fail(404, "unknown look")
            row = {"id": key, "name": body.get("name", key), "style": body["style"]}
            rows.append(row)
            return ok(look=dict(row, builtin=False))
        if path == "categories":
            return ok(
                categories=[
                    dict(r, effective={**GENERAL_STYLE, **r["style"]})
                    for r in self.settings["categories"]
                ]
            )
        if path.startswith("categories/"):
            rows = self.settings["categories"]
            key = path.split("/")[1]
            rows[:] = [row for row in rows if row["id"] != key]
            if method == "DELETE":
                return ok()
            row = {
                "id": key,
                "name": key,
                "builtin": False,
                "look": "glass",
                "announce": False,
                "style": {},
                **body,
            }
            rows.append(row)
            return ok(category=row)
        if path == "clients":
            return ok(
                clients=[{"client": "Home Assistant", "created": 10, "last_used": 20}]
            )
        if path == "clients/self" and method == "DELETE":
            self.authenticated = False
            return ok()
        if path.startswith(("images/", "clips/")):
            kind, key = path.split("/")
            cache = getattr(self, kind)
            if method == "PUT":
                cache[key] = body
            if key not in cache:
                return fail(404, "not cached")
            return ok(size=len(cache[key]))
        if path == "cards/clear" and method == "POST":
            keys = [
                k
                for k, v in self.cards.items()
                if body.get("all") or v["category"] == body.get("category")
            ]
            for key in keys:
                self.cards.pop(key)
            return ok(cleared=len(keys))
        if path.startswith("cards/"):
            key = path.split("/")[1]
            if method == "DELETE":
                return ok(found=self.cards.pop(key, None) is not None)
            if key not in self.cards:
                return fail(404, "unknown card")
            return ok(**self.cards[key], reason=None, button=None)
        if path == "cards":
            if method == "GET":
                return ok(cards=list(self.cards.values()))
            speech = body.get("announce")
            spoken = isinstance(speech, dict) and self.announce_supported
            rid = speech.get("request_id") if spoken else None
            key = body.get("id") or rid or uuid4().hex[:16]
            row = {
                "id": key,
                "category": body.get("category", "general"),
                "state": "showing",
                "priority": body.get("priority", "normal"),
                "remaining_s": 10,
            }
            self.cards[key] = row
            self.emit("card.shown", id=key, category=row["category"], ms=1)
            reply = dict(id=key, category=row["category"], state="showing", reason=None)
            if spoken:
                duplicate = rid in self.jobs
                job = self.jobs.setdefault(rid, str(uuid4()))
                reply["announce"] = dict(ok=True, job=job)
                if duplicate:
                    reply["announce"].update(duplicate=True, outcome="played")
                if speech.get("timing") != "now":
                    reply["state"] = "pending_voice"
            if (method, path) in self.drops:
                self.drops.remove((method, path))
                req.transport.close()
            return ok(**reply)
        if path == "chips":
            if method == "PUT":
                self.chips = {r["id"]: r for r in body["chips"]}
                return ok()
            return ok(chips=list(self.chips.values()))
        if path.startswith("chips/"):
            key = path.split("/")[1]
            if method == "DELETE":
                return ok(found=self.chips.pop(key, None) is not None)
            self.chips[key] = {
                "id": key,
                "text": "",
                "icon": "none",
                "color": "#ffffff",
                "look": None,
                "position": None,
                "priority": "normal",
                **{k: v for k, v in body.items() if k != "expires"},
                "expires_at": int(time.time()) + body["expires"]
                if body.get("expires")
                else None,
            }
            return ok()
        if path == "announce":
            if (
                not isinstance(body, dict)
                or sum(k in body for k in ("clip", "text", "audio", "url")) != 1
            ):
                return fail(400, "bad source")
            rid = body.get("request_id", uuid4().hex)
            dup = rid in self.jobs
            if not dup and "clip" in body and body["clip"] not in self.clips:
                return fail(404, "not cached")
            job = self.jobs.setdefault(rid, uuid4().hex[:16])
            if (method, path) in self.drops:
                self.drops.remove((method, path))
                req.transport.close()
            return ok(
                job=job, **({"duplicate": True, "outcome": "played"} if dup else {})
            )
        if path == "stop":
            return ok()
        return fail(404, "unknown path")


async def fixture_entry(hass, domain):
    from types import MappingProxyType

    from homeassistant.config_entries import ConfigEntry

    entry = ConfigEntry(
        domain=domain,
        version=1,
        minor_version=1,
        source="user",
        unique_id=domain,
        title=domain,
        data={},
        options={},
        subentries_data=(),
        discovery_keys=MappingProxyType({}),
    )
    await hass.config_entries.async_add(entry)
    assert entry.state.value == "loaded", (domain, entry.state)
    return entry


@asynccontextmanager
async def environment(root, *, cast=False, port=18123):
    from homeassistant import bootstrap, loader
    from homeassistant.core import HomeAssistant

    directory = root / "ha"
    media = root / "media"
    media.mkdir(exist_ok=True)
    target = directory / "custom_components"
    target.mkdir(parents=True, exist_ok=True)
    shutil.copytree(
        ROOT / "custom_components/herald", target / "herald", dirs_exist_ok=True
    )
    fixtures = ["herald_test_tts"] + (["fake_cast"] if cast else [])
    for component in fixtures:
        shutil.copytree(
            Path(__file__).parent / "fixtures" / component,
            target / component,
            dirs_exist_ok=True,
        )
    hass = HomeAssistant(str(directory))
    hass.config.skip_pip = True
    loader.async_setup(hass)
    config = {
        "homeassistant": {
            "time_zone": "UTC",
            "internal_url": f"http://127.0.0.1:{port}",
            "allowlist_external_dirs": [str(media)],
            "media_dirs": {"local": str(media)},
        },
        "http": {"server_host": "127.0.0.1", "server_port": port},
        "media_source": {},
        "herald": {},
    }
    try:
        assert await bootstrap.async_from_config_dict(config, hass) is hass
        await hass.async_start()
        for component in fixtures:
            if not hass.config_entries.async_entries(component):
                await fixture_entry(hass, component)
        yield hass
    finally:
        await hass.async_stop()


async def step(hass, flow, inputs):
    return await hass.config_entries.flow.async_configure(flow["flow_id"], inputs)


async def begin(hass, host, port):
    flow = await hass.config_entries.flow.async_init(
        "herald", context={"source": "user"}
    )
    flow = await step(hass, flow, {"next_step_id": "box"})
    return await step(hass, flow, {"host": host, "port": port})


async def pair(hass, box):
    flow = await begin(hass, "127.0.0.1", box.port)
    assert flow.get("step_id") == "code", flow.get("errors")
    flow = await step(hass, flow, {"code": box.code})
    assert flow["type"] == "create_entry"
    entry = flow["result"]
    assert entry.state.value == "loaded", entry.state
    return entry


async def action(hass, service, **data):
    return await hass.services.async_call(
        "herald", service, data, blocking=True, return_response=True
    )


async def until(condition, timeout=10):
    async with asyncio.timeout(timeout):
        while not condition():
            await asyncio.sleep(0.02)


def run(exercise, *, cast=False):
    async def main(root):
        boxes = [await FakeHerald(n).start() for n in ("East", "West")]
        try:
            async with AsyncExitStack() as stack:

                async def start_hass():
                    return await stack.enter_async_context(environment(root, cast=cast))

                hass = await start_hass()
                if cast:

                    async def restart():
                        await stack.aclose()
                        return await start_hass()

                    await exercise(hass, *boxes, root, restart)
                else:
                    await exercise(hass, *boxes, root)
        finally:
            for box in boxes:
                await box.runner.cleanup()

    from unittest.mock import patch

    from aiohttp import ThreadedResolver

    class LoopbackResolver(ThreadedResolver):
        real_close = ThreadedResolver.close

    # --network none has loopback only and cannot join a multicast group.
    with (
        patch(
            "homeassistant.helpers.aiohttp_client._async_make_resolver",
            lambda hass: LoopbackResolver(),
        ),
        tempfile.TemporaryDirectory() as tmp,
    ):
        asyncio.run(main(Path(tmp)))
