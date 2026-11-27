from homeassistant.components.sensor import SensorEntity

from .coordinator import BoxEntity


class Reading(BoxEntity, SensorEntity):
    @property
    def native_value(self):
        value = self.coordinator.data.get(self.key)
        return (
            value.get("id") or value.get("result") if isinstance(value, dict) else value
        )

    @property
    def extra_state_attributes(self):
        value = self.coordinator.data.get(self.key)
        return value if isinstance(value, dict) else {}


async def async_setup_entry(hass, entry, async_add_entities):
    async_add_entities(
        Reading(entry.runtime_data, k)
        for k in ("quiet_seconds", "last_card", "last_audio")
    )
