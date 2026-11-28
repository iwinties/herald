from homeassistant.exceptions import ConfigEntryAuthFailed, ConfigEntryNotReady
from homeassistant.helpers import config_validation as cv

from .client import ApiError, HeraldClient, valid_pin
from .const import DOMAIN, PLATFORMS, SPEAKERS
from .coordinator import Box
from .services import register, update_snapshots
from .speakers import Speakers


CONFIG_SCHEMA = cv.config_entry_only_config_schema(DOMAIN)


async def async_setup(hass, config):
    hass.data[DOMAIN] = {"boxes": {}, "speakers": Speakers(hass)}
    register(hass)
    return True


async def async_migrate_entry(hass, entry):
    if entry.version == 1:
        data = dict(entry.data)
        if "host" in data:
            data["cert_sha256"] = None
        hass.config_entries.async_update_entry(entry, data=data, version=2)
    return entry.version == 2


async def async_setup_entry(hass, entry):
    if entry.unique_id == SPEAKERS:
        entry.runtime_data = hass.data[DOMAIN]["speakers"]
        return True
    if entry.data.get("cert_sha256") is None:
        address = {k: entry.data[k] for k in ("host", "port")}
        try:
            info = await HeraldClient(hass, **address).request("GET", "info")
            pin = info.get("cert_sha256")
            if (
                info.get("app") != DOMAIN
                or info.get("api") != 2
                or not entry.unique_id
                or info.get("id") != entry.unique_id
                or not valid_pin(pin)
            ):
                raise ConfigEntryNotReady("Cannot confirm box identity")
            await HeraldClient(hass, **address, pin=pin).request("GET", "info")
        except ApiError as exc:
            raise ConfigEntryNotReady(exc.error) from None
        hass.config_entries.async_update_entry(
            entry, data={**entry.data, "cert_sha256": pin}
        )
    box = Box(hass, entry)
    await box.async_config_entry_first_refresh()
    box.snapshots = await box.store.async_load() or []
    try:
        await box.snapshot()
    except ApiError as exc:
        if exc.requires_reauth:
            raise ConfigEntryAuthFailed("Pairing required") from None
        raise ConfigEntryNotReady(exc.error) from None
    box.register_device()
    entry.runtime_data = box
    await hass.config_entries.async_forward_entry_setups(entry, PLATFORMS)
    hass.data[DOMAIN]["boxes"][box.device_id] = box
    update_snapshots(hass)
    box.event_task = entry.async_create_background_task(
        hass, box.events(), "Herald events"
    )
    return True


async def async_unload_entry(hass, entry):
    if entry.unique_id == SPEAKERS:
        return True
    if await hass.config_entries.async_unload_platforms(entry, PLATFORMS):
        box = entry.runtime_data
        if box.event_task:
            box.event_task.cancel()
        hass.data[DOMAIN]["boxes"].pop(box.device_id, None)
        update_snapshots(hass)
        return True
    return False
