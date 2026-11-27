import asyncio
import logging
import time
from datetime import timedelta

from homeassistant.exceptions import ConfigEntryAuthFailed
from homeassistant.helpers import device_registry as dr
from homeassistant.helpers.storage import Store
from homeassistant.helpers.update_coordinator import (
    CoordinatorEntity,
    DataUpdateCoordinator,
    UpdateFailed,
)
from homeassistant.util import dt

from .client import ApiError, HeraldClient
from .const import DOMAIN
from .services import update_snapshots


def writable(value, parent=None):
    if isinstance(value, dict):
        return {
            k: writable(v, k)
            for k, v in value.items()
            if k not in {"ok", "revision", "builtin", "password_set", "password"}
            and not (parent == "ntfy" and k in {"token", "token_set"})
        }
    if isinstance(value, list):
        return [writable(v) for v in value]
    return value


class Box(DataUpdateCoordinator):
    def __init__(self, hass, entry):
        super().__init__(
            hass,
            logging.getLogger(__name__),
            config_entry=entry,
            name=DOMAIN,
            update_interval=timedelta(seconds=30),
        )
        self.entry = entry
        self.client = HeraldClient(
            hass,
            entry.data["host"],
            entry.data["port"],
            entry.data["token"],
            entry.data["cert_sha256"],
        )
        self.store = Store(hass, 1, f"herald.settings.{entry.unique_id}")
        self.snapshots = []
        self.auth_failed = False
        self.event_task = None
        self.snapshot_lock = asyncio.Lock()

    async def request(self, *args, **kwargs):
        if self.auth_failed:
            raise ApiError(401, "unauthorized")
        try:
            return await self.client.request(*args, **kwargs)
        except ApiError as exc:
            if exc.requires_reauth and not self.auth_failed:
                self.auth_failed = True
                if self.event_task and self.event_task is not asyncio.current_task():
                    self.event_task.cancel()
                # HA handles ConfigEntryAuthFailed during setup. A loaded entry
                # can also lose auth in an action or long poll, outside refresh.
                if self.event_task is not None:
                    self.update_interval = None
                    self.entry.async_start_reauth(self.hass)
            raise

    async def _async_update_data(self):
        try:
            return await self.request("GET", "status")
        except ApiError as exc:
            if exc.requires_reauth and self.event_task is None:
                raise ConfigEntryAuthFailed("Pairing required") from None
            raise UpdateFailed(exc.error) from None

    async def snapshot(self):
        async with self.snapshot_lock:
            settings = writable(await self.request("GET", "settings"))
            if self.snapshots and self.snapshots[-1]["settings"] == settings:
                return
            match = next(
                (row for row in self.snapshots if row["settings"] == settings), None
            )
            row = match or {"id": dt.utcnow().isoformat(), "settings": settings}
            snapshots = ([old for old in self.snapshots if old is not match] + [row])[
                -3:
            ]
            await self.store.async_save(snapshots)
            self.snapshots = snapshots
            update_snapshots(self.hass)

    async def events(self):
        cursor, stream = self.data["event_seq"], self.data["stream"]
        delay = 5
        while not self.auth_failed:
            try:
                batch = await self.request("GET", "events", after=cursor, wait=25)
                if batch["stream"] != stream or batch["gap"] or batch["reset"]:
                    fresh = await self._async_update_data()
                    self.async_set_updated_data(fresh)
                    cursor, stream = fresh["event_seq"], fresh["stream"]
                else:
                    for event in batch["events"]:
                        await self.process_event(event)
                        cursor = event["seq"]
                    cursor = batch["seq"]
                delay = 5
            except ConfigEntryAuthFailed:
                return
            except (ApiError, UpdateFailed, KeyError, ValueError, OSError):
                if self.auth_failed:
                    return
                await asyncio.sleep(delay)
                delay = min(60, delay * 2)

    async def process_event(self, event):
        kind = event["type"]
        if kind == "settings.changed":
            await self.snapshot()
        data = dict(self.data)
        if kind.startswith("card."):
            data["last_card"] = {
                k: v for k, v in event.items() if k not in {"time", "seq"}
            }
        elif kind == "sound":
            data["sound_playing"] = event["playing"]
        elif kind == "screen":
            data["screen_on"] = event["on"]
        elif kind == "announce.ended":
            data["last_audio"] = event["result"]
        # Pushes update entities without postponing the independent 30-second poll.
        self.data = data
        self.async_update_listeners()
        button = kind == "card.button" or (
            kind == "card.ended" and event.get("reason") == "dismissed"
        )
        if button and time.time() * 1000 - event["time"] > 30000:
            return
        self.hass.bus.async_fire(
            "herald_event",
            {**event, "device_id": self.device_id, "entry_id": self.entry.entry_id},
        )

    def register_device(self):
        registry = dr.async_get(self.hass)
        device = registry.async_get_or_create(
            config_entry_id=self.entry.entry_id,
            identifiers={(DOMAIN, self.entry.unique_id)},
            name=self.entry.title,
            manufacturer="Herald",
        )
        self.device_id = device.id


class BoxEntity(CoordinatorEntity):
    _attr_has_entity_name = True

    def __init__(self, box, key):
        super().__init__(box)
        self.key = key
        self._attr_name = key.replace("_", " ").capitalize()
        self._attr_unique_id = f"{box.entry.unique_id}_{key}"
        self._attr_device_info = dr.DeviceInfo(
            identifiers={(DOMAIN, box.entry.unique_id)}
        )
