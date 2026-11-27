import asyncio

from homeassistant.components.media_player import MediaPlayerEntity
from homeassistant.components.media_player import MediaPlayerEntityFeature as Feature


class Player(MediaPlayerEntity):
    _attr_should_poll = False
    _attr_supported_features = (
        Feature.PLAY_MEDIA | Feature.VOLUME_SET | Feature.TURN_OFF
    )
    _attr_state = "idle"
    _attr_volume_level = 0.3

    def __init__(self, number):
        self._attr_name = f"Cast {number}"
        self._attr_unique_id = f"cast_{number}"
        self.calls = []
        self.fail = False

    def state_as(self, state):
        self._attr_state = state
        self.async_write_ha_state()

    async def async_set_volume_level(self, volume):
        self.calls.append(("volume_set", volume))
        self._attr_volume_level = volume
        self.async_write_ha_state()

    async def async_play_media(self, media_type, media_id, **kwargs):
        self.calls.append(("play_media", media_id))
        if self.fail:
            raise ValueError("fixture failure")
        self.state_as("playing")
        asyncio.get_running_loop().call_later(1, self.state_as, "idle")

    async def async_turn_off(self):
        self.calls.append(("turn_off",))
        self.state_as("off")


async def async_setup_entry(hass, entry, async_add_entities):
    players = [Player(i) for i in (1, 2)]
    hass.data["cast_fixture"] = players
    async_add_entities(players)
