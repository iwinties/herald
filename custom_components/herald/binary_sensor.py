from homeassistant.components.binary_sensor import BinarySensorEntity
from homeassistant.const import EntityCategory

from .coordinator import BoxEntity


class Flag(BoxEntity, BinarySensorEntity):
    @property
    def is_on(self):
        return self.coordinator.data.get(self.key)


async def async_setup_entry(hass, entry, async_add_entities):
    entities = [
        Flag(entry.runtime_data, k)
        for k in ("screen_on", "sound_playing", "overlay_allowed")
    ]
    entities[-1]._attr_entity_category = EntityCategory.DIAGNOSTIC
    async_add_entities(entities)
