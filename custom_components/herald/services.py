"""Actions, target resolution and shared media preparation."""

import asyncio
import base64
import json
import logging
import re
from hashlib import sha1, sha256
from pathlib import Path
from uuid import uuid4

import voluptuous as vol
from homeassistant.components import camera, media_source, tts
from homeassistant.core import SupportsResponse
from homeassistant.exceptions import ServiceValidationError
from homeassistant.helpers import config_validation as cv
from homeassistant.helpers import entity_registry as er
from homeassistant.helpers.aiohttp_client import async_get_clientsession
from homeassistant.helpers.network import get_url
from homeassistant.helpers.service import async_set_service_schema

from .client import SAFE_ERRORS, ApiError
from .const import DOMAIN, MAX_CLIP, MAX_IMAGE
from .preview import prepare_preview

KEY = vol.Match(r"^[A-Za-z0-9_-][A-Za-z0-9_.-]{0,63}\Z")
TARGET = {
    vol.Optional("device_id"): vol.All(cv.ensure_list, [str]),
    vol.Optional("entity_id"): vol.All(cv.ensure_list, [cv.entity_id]),
}
_LOGGER = logging.getLogger(__name__)
AUDIO = {
    "speakers": vol.All(cv.ensure_list, [cv.entity_id]),
    "request_id": KEY,
    "volume": vol.All(int, vol.Range(min=0, max=100)),
    "urgent": bool,
    "output": vol.In(["auto", "tv", "speaker"]),
}
VOICE = {
    **AUDIO,
    "tts": cv.entity_id,
    "language": str,
    "options": dict,
    "chime": vol.Any(bool, str),
    "duck": bool,
}
SPOKEN_FIELDS = {
    **VOICE,
    "announce": bool,
    "announce_text": str,
    "announce_timing": vol.In(["now", "with_voice"]),
}
LOOK_ID = vol.Match(r"^[a-z0-9_]{1,32}\Z")
FIELDS = {
    "show_card": {
        **SPOKEN_FIELDS,
        "look": str,
        "id": KEY,
        "category": str,
        "title": str,
        "message": str,
        "from": str,
        "buttons": [dict],
        "image": dict,
        "video": dict,
        "style": dict,
        "priority": str,
        "icon": str,
    },
    "clear_card": {"id": KEY, "category": str, "all": vol.In([True])},
    "save_look": {"id": LOOK_ID, "name": str, "style": dict},
    "delete_look": {"id": LOOK_ID},
    "set_chip": {
        "look": str,
        "position": str,
        "id": KEY,
        "text": str,
        "priority": vol.Any(str, int),
        "color": str,
        "icon": str,
        "expires": int,
    },
    "clear_chip": {"id": KEY},
    "announce": {
        **VOICE,
        "message": str,
        "media": str,
        "audio": str,
        "card": dict,
        "show": bool,
        "from": str,
    },
    "play_sound": {**AUDIO, "media": str, "path": str},
    "stop": {},
    "restore_settings": {"snapshot": str},
}


class Failure(Exception):
    def __init__(self, stage, error, status=0):
        self.reply = {"ok": False, "stage": stage, "error": error, "status": status}
        if status == 409 and error == "output not available":
            self.reply["terminal"] = True


def one(data, keys):
    selected = [k for k in keys if k in data]
    if len(selected) != 1:
        raise ServiceValidationError("Select exactly one source or selector")
    return selected[0]


def once(factory):
    task = None

    async def get():
        nonlocal task
        if task is None:
            task = asyncio.create_task(factory())
        return await task

    return get


async def request(box, stage, method, path, body=None, retry=False):
    try:
        try:
            return await box.request(method, path, body)
        except ApiError as exc:
            if not (retry and exc.lost):
                raise
            return await box.request(method, path, body)
    except ApiError as exc:
        raise Failure(stage, exc.error, exc.status) from None


async def cached(box, path):
    try:
        await request(box, "check", "GET", path)
    except Failure as exc:
        if exc.reply["status"] == 404:
            return False
        raise
    return True


def read_file(path, limit):
    with open(path, "rb") as file:
        data = file.read(limit + 1)
    if len(data) > limit:
        raise Failure("prepare", "media too large")
    return data


async def media_bytes(hass, data, limit):
    if "path" in data:
        path = Path(data["path"])
        if not await hass.async_add_executor_job(hass.config.is_allowed_path, path):
            raise Failure("prepare", "path not allowed")
        return await hass.async_add_executor_job(read_file, path, limit)
    if "camera" in data:
        content = (await camera.async_get_image(hass, data["camera"])).content
    elif "audio" in data:
        encoded = "".join(data["audio"].split())
        if len(encoded) > ((limit + 2) // 3) * 4:
            raise Failure("prepare", "media too large")
        content = base64.b64decode(encoded, validate=True)
    else:
        item = await media_source.async_resolve_media(hass, data["media"], None)
        if item.path:
            return await hass.async_add_executor_job(read_file, item.path, limit)
        url = item.url
        if url.startswith("/"):
            url = get_url(hass, allow_external=False) + url
        async with async_get_clientsession(hass).get(
            url, timeout=10, allow_redirects=False
        ) as reply:
            if reply.status != 200:
                raise Failure("prepare", "media unavailable")
            content = bytearray()
            async for chunk in reply.content.iter_chunked(65536):
                content.extend(chunk)
                if len(content) > limit:
                    raise Failure("prepare", "media too large")
    if not content or len(content) > limit:
        raise Failure("prepare", "invalid media size")
    return bytes(content)


async def card_action(hass, box, data):
    card = dict(data)
    if "image" in card:
        image = card["image"]
        source = one(image, ("camera", "media", "path", "url", "key", "data"))
        if source in ("camera", "media", "path"):
            content = await media_bytes(hass, image, MAX_IMAGE)
            key = "img-" + sha256(content).hexdigest()[:20]
            path = "images/" + key
            if not await cached(box, path):
                await request(box, "upload", "PUT", path, content)
            card["image"] = {"key": key}
    if "video" in card:
        video = dict(card["video"])
        if one(video, ("url", "camera")) == "camera":
            video["url"] = await camera.async_get_stream_source(
                hass, video.pop("camera")
            )
            if not video["url"]:
                raise Failure("prepare", "no stream source")
        card["video"] = video
    return await request(
        box, "request", "POST", "cards", card, retry="announce" in card
    )


async def simple(hass, box, name, data):
    if name == "show_card":
        card = {k: v for k, v in data.items() if k not in SPOKEN_FIELDS}
        if "announce" in data:
            card["announce"] = False
        return await card_action(hass, box, card)
    if name in ("save_look", "delete_look"):
        fields = dict(data)
        path = "looks/" + fields.pop("id")
        if name == "delete_look":
            return await request(box, "request", "DELETE", path)
        return await request(box, "request", "PUT", path, fields)
    if name == "clear_card":
        if one(data, ("id", "all", "category")) == "id":
            return await request(box, "request", "DELETE", "cards/" + data["id"])
        return await request(box, "request", "POST", "cards/clear", data)
    if name == "stop":
        return await request(box, "request", "POST", "stop", {})
    if name == "restore_settings":
        rows = [
            s
            for s in box.snapshots
            if "snapshot" not in data or s["id"] == data["snapshot"]
        ]
        if not rows:
            raise Failure("prepare", "unknown snapshot")
        return await request(box, "request", "PUT", "settings", rows[-1]["settings"])
    data = dict(data)
    path = "chips/" + data.pop("id")
    if name == "clear_chip":
        return await request(box, "request", "DELETE", path)
    return await request(box, "request", "PUT", path, data)


async def guarded(job):
    try:
        reply = await job
        return {**reply, "stage": "request", "status": 200, "error": None}
    except Failure as exc:
        return exc.reply
    except Exception:
        return Failure("prepare", "preparation failed").reply


JAVA_SPACE = "".join(map(chr, range(0x21)))


def cut(text, units):
    raw = text.encode("utf-16-le", "surrogatepass")[: units * 2]
    if raw and 0xD800 <= int.from_bytes(raw[-2:], "little") <= 0xDBFF:
        raw = raw[:-2]
    return raw.decode("utf-16-le", "surrogatepass")


def spoken_text(data):
    if "announce_text" in data:
        text = data["announce_text"].strip(JAVA_SPACE)
    else:
        title = cut(data.get("title", ""), 120).strip(JAVA_SPACE)
        message = cut(data.get("message", ""), 1000).strip(JAVA_SPACE)
        if title and message:
            text = title + (" " if title.endswith((".", "!", "?")) else ". ") + message
        else:
            text = title or message
    if not text:
        raise ServiceValidationError("text is empty")
    return cut(text, 500)


async def prepare_speech(hass, source, data, boxes, *, chime):
    speakers = hass.data[DOMAIN]["speakers"]
    defaults = speakers.options
    if source == "message" and "tts" not in data and defaults.get("default_tts"):
        data = {**data, "tts": defaults["default_tts"]}
        if "language" not in data and "default_language" in defaults:
            data["language"] = defaults["default_language"]
        if "default_tts_options" in defaults:
            data["options"] = {
                **defaults["default_tts_options"],
                **data.get("options", {}),
            }

    async def generate():
        if source != "message":
            return await media_bytes(
                hass, data, MAX_IMAGE if source == "audio" else MAX_CLIP
            )
        tts_id = tts.generate_media_source_id(
            hass,
            data["message"],
            data.get("tts"),
            data.get("language"),
            data.get("options"),
            False,
        )
        _, content = await tts.async_get_media_source_audio(hass, tts_id)
        if not content or len(content) > MAX_CLIP:
            raise Failure("prepare", "invalid speech size")
        return content

    content = once(generate)
    path = None
    if source == "message" and "tts" in data:
        material = "|".join(
            [
                data["tts"],
                data.get("language", ""),
                json.dumps(data.get("options", {}), sort_keys=True),
                data["message"],
            ]
        )
        key = "tts-" + sha1(material.encode()).hexdigest()
        path = "clips/" + key
    elif source in ("media", "path"):
        try:
            blob = await content()
        except Exception:
            raise Failure("prepare", "media unavailable") from None
        label = re.sub(r"[^a-zA-Z0-9_-]+", "-", Path(data[source]).stem)[:80] or "media"
        key = f"sound-{label}-{sha256(blob).hexdigest()[:12]}"
        path = "clips/" + key
    checks = {}
    if path:
        checks = dict(
            zip(
                boxes,
                await asyncio.gather(
                    *(cached(b, path) for b in boxes.values()), return_exceptions=True
                ),
                strict=True,
            )
        )

    async def payload_for(target, box):
        if path:
            hit = checks[target]
            if isinstance(hit, Exception):
                raise hit
            payload = {"clip": key}
            if not hit:
                try:
                    blob = await content()
                except Exception:
                    if source != "message":
                        raise
                    payload = {"text": data["message"]}
                else:
                    await request(box, "upload", "PUT", path, blob)
        elif source == "audio":
            payload = {"audio": base64.b64encode(await content()).decode()}
        else:
            payload = {"text": data["message"]}
        return payload

    async def cast_clip():
        if not hass.config.internal_url:
            raise ValueError("no internal url")
        return await speakers.prepare(await content(), chime)

    payloads = await asyncio.gather(
        *(payload_for(k, b) for k, b in boxes.items()), return_exceptions=True
    )
    return dict(zip(boxes, payloads, strict=True)), once(cast_clip)


async def audio(hass, name, data, boxes, speaker_ids):
    spoken = name == "show_card"
    if spoken:
        card = {k: v for k, v in data.items() if k not in SPOKEN_FIELDS}
        data = {**data, "message": spoken_text(data)}
        source = "message"
    else:
        source = one(
            data,
            ("media", "path")
            if name == "play_sound"
            else ("message", "media", "audio"),
        )
    rid = data.get("request_id") or uuid4().hex
    fields = {
        k: data[k]
        for k in ("volume", "output", "urgent", "duck", "chime", "card")
        if k in data
    }
    fields["request_id"] = rid
    if name == "play_sound":
        fields["chime"] = False
    elif data.get("show") and "message" in data:
        fields["card"] = {
            "category": "announcement",
            "message": data["message"],
            **({"from": data["from"]} if "from" in data else {}),
            **data.get("card", {}),
        }

    if spoken and "announce_timing" in data:
        fields["timing"] = data["announce_timing"]
    try:
        payloads, clip = await prepare_speech(
            hass, source, data, boxes, chime=fields.get("chime") is not False
        )
    except Failure as exc:
        return {k: exc.reply for k in [*boxes, *speaker_ids]}

    async def send(target, box):
        payload = payloads[target]
        if isinstance(payload, Exception):
            raise payload
        speech = {**fields, **payload}
        if spoken:
            reply = await card_action(hass, box, {**card, "announce": speech})
            answer = reply.get("announce")
            if not isinstance(answer, dict):
                raise Failure("request", "unsupported", 200)
            if answer.get("ok") is not True:
                reason = answer.get("error")
                raise Failure(
                    "request",
                    reason
                    if isinstance(reason, str) and reason in SAFE_ERRORS
                    else "request failed",
                    answer.get("status", 200),
                )
            return answer
        return await request(box, "request", "POST", "announce", speech, retry=True)

    jobs = [guarded(send(k, b)) for k, b in boxes.items()]
    speakers = hass.data[DOMAIN]["speakers"]
    jobs += [
        speakers.play(k, rid, data.get("urgent", False), clip) for k in speaker_ids
    ]
    return dict(zip([*boxes, *speaker_ids], await asyncio.gather(*jobs), strict=True))


def update_snapshots(hass):
    ids = sorted(
        {s["id"] for box in hass.data[DOMAIN]["boxes"].values() for s in box.snapshots},
        reverse=True,
    )
    async_set_service_schema(
        hass,
        DOMAIN,
        "restore_settings",
        {
            "name": "Restore settings",
            "description": "Apply a saved settings snapshot to the selected TVs.",
            "fields": {
                "entity_id": {
                    "name": "TV notification entities",
                    "selector": {
                        "entity": {
                            "domain": "notify",
                            "integration": DOMAIN,
                            "multiple": True,
                        }
                    },
                },
                "snapshot": {
                    "name": "Snapshot",
                    "selector": {"select": {"options": ids}},
                },
                "device_id": {
                    "name": "TVs",
                    "selector": {"device": {"integration": DOMAIN, "multiple": True}},
                },
            },
        },
    )


def register(hass):
    async def handle(call):
        data = dict(call.data)
        available = hass.data[DOMAIN]["boxes"]
        options = hass.data[DOMAIN]["speakers"].options
        if call.service == "show_card":
            data = await prepare_preview(hass, data, call.context.user_id)
        elif call.service == "announce" and isinstance(data.get("card"), dict):
            data["card"] = await prepare_preview(hass, data["card"], call.context.user_id)
        ids = data.pop("device_id", None)
        if ids is not None and (not ids or any(i not in available for i in ids)):
            raise ServiceValidationError("Select a loaded Herald box")
        notify_ids = data.pop("entity_id", None)
        if notify_ids is not None:
            if not notify_ids:
                raise ServiceValidationError("Select a Herald notification entity")
            registry = er.async_get(hass)
            selected = list(ids or [])
            for entity_id in notify_ids:
                entry = registry.async_get(entity_id)
                if (
                    entry is None
                    or entry.domain != "notify"
                    or entry.platform != DOMAIN
                    or entry.device_id not in available
                ):
                    raise ServiceValidationError(
                        "Select a notification entity from a connected Herald TV"
                    )
                selected.append(entry.device_id)
            ids = list(dict.fromkeys(selected))
        entities = data.pop("speakers", None)
        if entities is not None and (
            not entities
            or any(
                not e.startswith("media_player.") or hass.states.get(e) is None
                for e in entities
            )
        ):
            raise ServiceValidationError("Select a media player")
        boxes = (
            available.copy()
            if ids is None and entities is None
            else {i: available[i] for i in ids or []}
        )
        if call.service in ("announce", "play_sound") or (
            call.service == "show_card" and data.get("announce")
        ):
            selected = (
                options.get("speakers", [])
                if ids is None and entities is None
                else entities or []
            )
            result = await audio(
                hass, call.service, data, boxes, list(dict.fromkeys(selected))
            )
        else:
            jobs = [
                guarded(simple(hass, b, call.service, data)) for b in boxes.values()
            ]
            result = dict(zip(boxes, await asyncio.gather(*jobs), strict=True))
        if call.return_response:
            return result
        if not result:
            raise ServiceValidationError(
                "No connected Herald TVs or speakers were selected"
            )
        failures = []
        for target, reply in result.items():
            if reply.get("ok") is False:
                name = boxes[target].entry.title if target in boxes else target
                failures.append(f"{name} ({reply['error']})")
        if failures:
            detail = ", ".join(failures)
            if len(failures) == len(result):
                raise ServiceValidationError(
                    f"Herald could not complete {call.service} on {detail}"
                )
            _LOGGER.warning("Herald %s failed on %s", call.service, detail)

    for name, fields in FIELDS.items():
        schema = dict(TARGET)
        for key, validator in fields.items():
            field = (
                vol.Required(key)
                if (
                    key == "id"
                    and name in ("set_chip", "clear_chip", "save_look", "delete_look")
                )
                or (key == "style" and name == "save_look")
                else vol.Optional(key)
            )
            schema[field] = validator
        hass.services.async_register(
            DOMAIN,
            name,
            handle,
            schema=vol.Schema(schema),
            supports_response=SupportsResponse.OPTIONAL,
        )
