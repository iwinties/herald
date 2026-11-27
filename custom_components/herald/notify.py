from homeassistant.components.notify import NotifyEntity, NotifyEntityFeature
from homeassistant.exceptions import HomeAssistantError

from .client import ApiError
from .coordinator import BoxEntity


class CardNotify(BoxEntity, NotifyEntity):
    _attr_supported_features = NotifyEntityFeature.TITLE

    async def async_send_message(self, message, title=None):
        card = {"category": "general", "message": message}
        if title is not None:
            card["title"] = title
        try:
            await self.coordinator.request("POST", "cards", card)
        except ApiError as exc:
            raise HomeAssistantError(exc.error) from None


async def async_setup_entry(hass, entry, async_add_entities):
    async_add_entities([CardNotify(entry.runtime_data, "notify")])
