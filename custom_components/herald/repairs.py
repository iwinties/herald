import voluptuous as vol
from homeassistant.components.repairs import RepairsFlow

from .client import ApiError, HeraldClient


class AddressRepair(RepairsFlow):
    def __init__(self, data):
        self.address_data = data

    async def async_step_init(self, user_input=None):
        errors = {}
        if user_input is not None:
            entry = self.hass.config_entries.async_get_entry(
                self.address_data["entry_id"]
            )
            if entry is None:
                return self.async_abort(reason="unknown_entry")
            address = {k: self.address_data[k] for k in ("host", "port")}
            try:
                info = await HeraldClient(self.hass, **address).request("GET", "info")
                if info.get("id") == entry.unique_id and info.get("app") == "herald":
                    self.hass.config_entries.async_update_entry(
                        entry, data={**entry.data, **address}
                    )
                    await self.hass.config_entries.async_reload(entry.entry_id)
                    return self.async_create_entry(data={})
                errors["base"] = "wrong_device"
            except ApiError:
                errors["base"] = "cannot_connect"
        return self.async_show_form(
            step_id="init", data_schema=vol.Schema({}), errors=errors
        )


async def async_create_fix_flow(hass, issue_id, data):
    return AddressRepair(data)
