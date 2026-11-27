from homeassistant.exceptions import ConfigEntryAuthFailed, ConfigEntryNotReady

from .client import ApiError
from .const import DOMAIN, PLATFORMS, SPEAKERS
from .coordinator import Box
from .services import register, update_snapshots
from .speakers import Speakers


async def async_setup(hass, config):
    hass.data[DOMAIN] = {"boxes": {}, "speakers": Speakers(hass)}
    register(hass)
    return True


async def async_setup_entry(hass, entry):
    if entry.unique_id == SPEAKERS:
        entry.runtime_data = hass.data[DOMAIN]["speakers"]
        return True
    box = Box(hass, entry)
    await box.async_config_entry_first_refresh()
    box.snapshots = await box.store.async_load() or []
    try:
        await box.snapshot()
    except ApiError as exc:
        if exc.status == 401:
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
