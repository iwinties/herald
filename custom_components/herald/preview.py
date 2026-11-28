"""Short-lived access to an already timestamped camera snapshot."""
import time
from datetime import timedelta

from aiohttp import web
from homeassistant.auth.permissions.const import POLICY_READ
from homeassistant.components import camera
from homeassistant.components.http import HomeAssistantView
from homeassistant.components.http.auth import async_sign_path
from homeassistant.components.http.const import KEY_HASS_USER
from homeassistant.core import valid_entity_id
from homeassistant.exceptions import HomeAssistantError, ServiceValidationError
from homeassistant.helpers.http import KEY_HASS
from homeassistant.helpers.network import NoURLAvailableError, get_url

from .const import MAX_IMAGE


def snapshot_timestamp(hass, entity_id):
    state = hass.states.get(entity_id)
    if state is None or state.state in ("unknown", "unavailable"):
        return None
    stamp = state.attributes.get("timestamp")
    now = int(time.time())
    if type(stamp) is int and now - 120 <= stamp <= now + 2:
        return stamp
    return None


async def prepare_preview(hass, card, user_id):
    """Build the TV request without waiting for snapshot bytes."""
    video = card.get("video")
    if not isinstance(video, dict) or "preview_camera" not in video:
        return card
    video = dict(video)
    entity = video.pop("preview_camera")
    if not isinstance(entity, str) or not valid_entity_id(entity) or not entity.startswith("camera."):
        raise ServiceValidationError("Select a snapshot camera for the video preview")
    if "preview" in video or "image" in card:
        raise ServiceValidationError("Select one still image for the video card")
    card = {**card, "video": video}
    stamp = snapshot_timestamp(hass, entity)
    if stamp is None:
        return card
    if user_id:
        user = await hass.auth.async_get_user(user_id)
        if user is None or not user.permissions.check_entity(entity, POLICY_READ):
            return card
    try:
        base = get_url(hass, allow_external=False, allow_cloud=False)
    except NoURLAvailableError:
        return card
    path = f"/api/herald/preview/{entity}/{stamp}"
    signed = async_sign_path(hass, path, timedelta(seconds=30), use_content_user=True)
    video["preview"] = {"url": base + signed, "timestamp": stamp}
    return card


class PreviewView(HomeAssistantView):
    url = "/api/herald/preview/{entity_id}/{stamp}"
    name = "api:herald:preview"

    async def get(self, request, entity_id, stamp):
        hass = request.app[KEY_HASS]
        if not valid_entity_id(entity_id) or not entity_id.startswith("camera."):
            raise web.HTTPNotFound()
        if not request[KEY_HASS_USER].permissions.check_entity(entity_id, POLICY_READ):
            raise web.HTTPForbidden()
        timestamp = snapshot_timestamp(hass, entity_id)
        if timestamp is None or str(timestamp) != stamp:
            raise web.HTTPNotFound()
        try:
            image = await camera.async_get_image(hass, entity_id, timeout=4)
        except (HomeAssistantError, TimeoutError):
            raise web.HTTPNotFound() from None
        if snapshot_timestamp(hass, entity_id) != timestamp or not 0 < len(image.content) <= MAX_IMAGE:
            raise web.HTTPNotFound()
        return web.Response(body=image.content, content_type="application/octet-stream",
                            headers={"Cache-Control": "no-store"})
