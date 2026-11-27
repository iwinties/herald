"""Serial Cast playback with durable admission before play_media."""

import asyncio
import io
import re
import time
import wave
from collections import Counter, defaultdict
from datetime import timedelta
from hashlib import sha256
from pathlib import Path

from aiohttp import web
from homeassistant.components.ffmpeg import get_ffmpeg_manager
from homeassistant.components.http import HomeAssistantView
from homeassistant.components.http.auth import async_sign_path
from homeassistant.core import callback
from homeassistant.helpers.event import async_track_state_change_event
from homeassistant.helpers.http import KEY_HASS
from homeassistant.helpers.network import get_url
from homeassistant.helpers.storage import Store
from homeassistant.util import dt

from .const import DOMAIN


class SpeakerView(HomeAssistantView):
    url = "/api/herald/speaker/{key}.wav"
    name = "api:herald:speaker"

    async def get(self, request, key):
        if not re.fullmatch("[a-f0-9]{64}", key):
            raise web.HTTPNotFound()
        directory = request.app[KEY_HASS].data[DOMAIN]["speakers"].directory
        return web.FileResponse(directory / (key + ".wav"))


async def render(binary, voice):
    chime = Path(__file__).with_name("chime.wav")
    filters = (
        "[0:a]apad=pad_dur=0.2[a];[1:a]aresample=22050,"
        "aformat=channel_layouts=mono,afade=t=in:d=0.15[b];[a][b]concat=n=2:v=0:a=1"
    )
    proc = await asyncio.create_subprocess_exec(
        binary,
        "-nostdin",
        "-v",
        "error",
        "-i",
        str(chime),
        "-protocol_whitelist",
        "pipe",
        "-i",
        "pipe:0",
        "-filter_complex",
        filters,
        "-ac",
        "1",
        "-ar",
        "22050",
        "-f",
        "wav",
        "pipe:1",
        stdin=asyncio.subprocess.PIPE,
        stdout=asyncio.subprocess.PIPE,
        stderr=asyncio.subprocess.DEVNULL,
    )
    try:
        data, _ = await asyncio.wait_for(proc.communicate(voice), 30)
    finally:
        if proc.returncode is None:
            proc.kill()
            await proc.wait()
    if proc.returncode or not data:
        raise ValueError("audio conversion failed")
    # ffmpeg writes unknown RIFF lengths to a pipe. Finalize the header for Cast.
    with wave.open(io.BytesIO(data)) as wav:
        frames = wav.readframes(wav.getnframes())
    output = io.BytesIO()
    with wave.open(output, "wb") as wav:
        wav.setparams((1, 2, 22050, 0, "NONE", "not compressed"))
        wav.writeframes(frames)
    return output.getvalue()


class Speakers:
    def __init__(self, hass):
        self.hass = hass
        self.directory = Path(hass.config.path(".storage", "herald_speaker_cache"))
        self.store = Store(hass, 1, "herald.speaker_requests")
        self.records = None
        self.last_volume = {}
        self.store_lock = asyncio.Lock()
        self.cache_lock = asyncio.Lock()
        self.locks = defaultdict(asyncio.Lock)
        self.count = Counter()
        hass.http.register_view(SpeakerView())

    @property
    def options(self):
        return next(
            (
                entry.options
                for entry in self.hass.config_entries.async_loaded_entries(DOMAIN)
                if entry.unique_id == "speakers"
            ),
            {},
        )

    def cache(self, key, data):
        self.directory.mkdir(parents=True, exist_ok=True)
        path = self.directory / (key + ".wav")
        if not path.exists():
            tmp = path.with_suffix(".tmp")
            tmp.write_bytes(data)
            tmp.replace(path)
        path.touch()
        for old in sorted(
            self.directory.glob("*.wav"), key=lambda p: p.stat().st_mtime
        )[:-50]:
            old.unlink()

    async def prepare(self, voice, chime):
        if not self.hass.config.internal_url:
            raise ValueError("no internal url")
        base = get_url(self.hass, allow_external=False, allow_cloud=False)
        data = (
            await render(get_ffmpeg_manager(self.hass).binary, voice)
            if chime
            else voice
        )
        key = sha256(data).hexdigest()
        async with self.cache_lock:
            await self.hass.async_add_executor_job(self.cache, key, data)
        path = f"/api/herald/speaker/{key}.wav"
        try:
            with wave.open(io.BytesIO(data)) as wav:
                duration = wav.getnframes() / wav.getframerate()
        except (wave.Error, EOFError):
            proc = await asyncio.create_subprocess_exec(
                "ffprobe",
                "-v",
                "quiet",
                "-protocol_whitelist",
                "file",
                "-show_entries",
                "format=duration",
                "-of",
                "csv=p=0",
                str(self.directory / (key + ".wav")),
                stdout=asyncio.subprocess.PIPE,
                stderr=asyncio.subprocess.DEVNULL,
            )
            try:
                raw, _ = await asyncio.wait_for(proc.communicate(), 10)
                duration = float(raw)
            finally:
                if proc.returncode is None:
                    proc.kill()
                    await proc.wait()
        return base, path, min(duration + 15, 120)

    def quiet(self, urgent):
        options = self.options
        now = dt.now().strftime("%H:%M")
        start, end = (
            options.get(k, v)[:5]
            for k, v in (("night_start", "22:00"), ("night_end", "07:00"))
        )
        night = start <= now < end if start <= end else now >= start or now < end
        policy = options.get("speaker_at_night", "urgent")
        return night and (policy == "never" or policy == "urgent" and not urgent)

    async def recorded(self, entity, rid, *, reserve=False):
        async with self.store_lock:
            if self.records is None:
                self.records = await self.store.async_load() or {}
            now = time.time()
            entries = {
                e: {k: v for k, v in rows.items() if v > now}
                for e, rows in self.records.items()
            }
            rows = entries.setdefault(entity, {})
            if rid in rows:
                return True
            if reserve:
                rows[rid] = now + 600
                entries = {e: r for e, r in entries.items() if r}
                await self.store.async_save(entries)
                self.records = entries
            return False

    async def play(self, entity, rid, urgent, prepare):
        def skipped(reason):
            return {"ok": True, "stage": "request", "skipped": reason}

        if self.count[entity] >= 6:
            if self.records and self.records.get(entity, {}).get(rid, 0) > time.time():
                return {**skipped("duplicate"), "duplicate": True}
            return skipped("busy")
        self.count[entity] += 1
        stage = "prepare"
        try:
            async with self.locks[entity]:
                if await self.recorded(entity, rid):
                    return {**skipped("duplicate"), "duplicate": True}
                state = self.hass.states.get(entity)
                if state is None or state.state in ("unknown", "unavailable"):
                    raise ValueError("speaker unavailable")
                if state.state == "playing" and not urgent:
                    return skipped("playing")
                if self.quiet(urgent):
                    return skipped("quiet hours")
                base, path, deadline = await prepare()
                url = base + async_sign_path(
                    self.hass, path, timedelta(seconds=300), use_content_user=True
                )
                stage = "request"
                await self.play_one(entity, rid, url, deadline)
                return {"ok": True, "stage": stage}
        except Exception as exc:
            error = (
                str(exc)
                if type(exc) is ValueError
                and exc.args in [("no internal url",), ("speaker unavailable",)]
                else "speaker failed"
            )
            return {"ok": False, "stage": stage, "error": error}
        finally:
            self.count[entity] -= 1

    async def play_one(self, entity, rid, url, deadline):
        state = self.hass.states.get(entity)
        old_volume = state.attributes.get("volume_level", self.last_volume.get(entity))
        if old_volume is not None:
            self.last_volume[entity] = old_volume
        volume = self.options.get("volumes", {}).get(entity, 50)

        async def call(name, **data):
            await self.hass.services.async_call(
                "media_player", name, {"entity_id": entity, **data}, blocking=True
            )

        finished = asyncio.Event()
        playing = state.state == "playing"

        @callback
        def changed(event):
            nonlocal playing
            current = event.data["new_state"]
            if current and current.state == "playing":
                playing = True
            elif playing:
                finished.set()

        try:
            await call("volume_set", volume_level=volume / 100)
            await self.recorded(entity, rid, reserve=True)
            remove = async_track_state_change_event(self.hass, [entity], changed)
            try:
                async with asyncio.timeout(deadline):
                    await call(
                        "play_media", media_content_id=url, media_content_type="music"
                    )
                    await finished.wait()
            finally:
                remove()
        finally:
            try:
                if old_volume is not None:
                    await call("volume_set", volume_level=old_volume)
            finally:
                if state.state == "off":
                    await call("turn_off")
