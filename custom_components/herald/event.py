from homeassistant.components.event import EventEntity
from homeassistant.core import callback

from .coordinator import BoxEntity


class Button(BoxEntity, EventEntity):
    _attr_event_types = ["button", "dismissed"]

    async def async_added_to_hass(self):
        await super().async_added_to_hass()
        self.async_on_remove(self.hass.bus.async_listen("herald_event", self.handle))

    @callback
    def handle(self, event):
        data = event.data
        if data["entry_id"] != self.coordinator.entry.entry_id:
            return
        if data["type"] == "card.button":
            self._trigger_event(
                "button", {"card": data["id"], "button": data["button"]}
            )
        elif data["type"] == "card.ended" and data.get("reason") == "dismissed":
            self._trigger_event("dismissed", {"card": data["id"]})
        else:
            return
        self.async_write_ha_state()


async def async_setup_entry(hass, entry, async_add_entities):
    async_add_entities([Button(entry.runtime_data, "button")])
