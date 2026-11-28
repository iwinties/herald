import voluptuous as vol
from homeassistant import config_entries
from homeassistant.components.media_player import MediaPlayerEntityFeature as Feature
from homeassistant.core import callback
from homeassistant.helpers import config_validation as cv
from homeassistant.helpers import issue_registry as ir
from homeassistant.helpers import selector

from .client import ApiError, HeraldClient, valid_pin
from .const import DOMAIN, PORT, SPEAKERS


def form_error(exc):
    return {
        "screen off": "screen_off",
        "overlay not allowed": "overlay_required",
        "try later": "pairing_locked",
        "pairing disabled": "pairing_disabled",
        "too many clients": "too_many_clients",
        "wrong code": "wrong_code",
        "pairing expired": "expired",
        "no pairing": "expired",
    }.get(exc.error, "cannot_connect")


class HeraldFlow(config_entries.ConfigFlow, domain=DOMAIN):
    VERSION = 2

    @staticmethod
    @callback
    def async_get_options_flow(config_entry):
        return SpeakerOptions()

    async def async_step_user(self, user_input=None):
        return self.async_show_menu(step_id="user", menu_options=["box", "speakers"])

    async def async_step_speakers(self, user_input=None):
        await self.async_set_unique_id(SPEAKERS)
        self._abort_if_unique_id_configured()
        return self.async_create_entry(title="Herald settings", data={})

    async def async_step_box(self, user_input=None):
        errors = {}
        if user_input is not None:
            self.address = dict(user_input)
            try:
                return await self.begin_pair()
            except ApiError as exc:
                errors["base"] = form_error(exc)
        return self.async_show_form(
            step_id="box",
            data_schema=vol.Schema(
                {vol.Required("host"): str, vol.Required("port", default=PORT): cv.port}
            ),
            errors=errors,
        )

    async def async_step_zeroconf(self, discovery_info):
        self.address = {"host": discovery_info.host, "port": discovery_info.port}
        identity = discovery_info.properties.get("id")
        if (
            not identity
            or identity == SPEAKERS
            or discovery_info.properties.get("api") != "2"
        ):
            return self.async_abort(reason="invalid_discovery")
        await self.async_set_unique_id(identity)
        if entry := self.hass.config_entries.async_entry_for_domain_unique_id(
            DOMAIN, identity
        ):
            if any(entry.data[k] != v for k, v in self.address.items()):
                ir.async_create_issue(
                    self.hass,
                    DOMAIN,
                    f"address_{entry.entry_id}",
                    is_fixable=True,
                    severity=ir.IssueSeverity.WARNING,
                    translation_key="new_address",
                    translation_placeholders={
                        "name": entry.title,
                        **{k: str(v) for k, v in self.address.items()},
                    },
                    data={"entry_id": entry.entry_id, **self.address},
                )
            return self.async_abort(reason="already_configured")
        self.pair_title(discovery_info.name.removesuffix("." + discovery_info.type))
        return await self.async_step_confirm()

    def pair_title(self, name):
        self.context["title_placeholders"] = {
            "name": name,
            "host": self.address["host"],
        }

    async def async_step_confirm(self, user_input=None):
        errors = {}
        if user_input is not None:
            try:
                return await self.begin_pair()
            except ApiError as exc:
                errors["base"] = form_error(exc)
        return self.async_show_form(
            step_id="confirm",
            data_schema=vol.Schema({}),
            errors=errors,
            description_placeholders=self.context["title_placeholders"],
        )

    async def async_step_reauth(self, entry_data):
        entry = self._get_reauth_entry()
        await self.async_set_unique_id(entry.unique_id)
        self.address = {k: entry_data[k] for k in ("host", "port")}
        self.pair_title(entry.title)
        return await self.async_step_confirm()

    async def begin_pair(self):
        self.client = HeraldClient(self.hass, **self.address)
        info = await self.client.request("GET", "info")
        identity = info.get("id")
        if (
            info.get("app") != DOMAIN
            or not identity
            or identity == SPEAKERS
            or self.unique_id
            and self.unique_id != identity
        ):
            return self.async_abort(reason="wrong_device")
        self.pin = info.get("cert_sha256")
        if info.get("api") != 2 or not valid_pin(self.pin):
            return self.async_abort(reason="unsupported_box")
        self.client = HeraldClient(self.hass, **self.address, pin=self.pin)
        await self.async_set_unique_id(identity)
        if self.source != config_entries.SOURCE_REAUTH:
            self._abort_if_unique_id_configured()
        self.box_name = info["name"]
        try:
            await self.client.request(
                "POST", "pair/start", {"client": "Home Assistant"}
            )
        except ApiError as exc:
            if (exc.status, exc.error) != (409, "pairing in progress"):
                raise
            return await self.async_step_code(existing=True)
        return await self.async_step_code()

    async def async_step_code(self, user_input=None, *, existing=False):
        errors = {"base": "existing_code"} if existing else {}
        if user_input is not None:
            try:
                reply = await self.client.request(
                    "POST", "pair/finish", {"client": "Home Assistant", **user_input}
                )
                if reply.get("id") != self.unique_id:
                    return self.async_abort(reason="wrong_device")
                data = {
                    **self.address,
                    "device_id": self.unique_id,
                    "token": reply["token"],
                    "cert_sha256": self.pin,
                }
                if self.source == config_entries.SOURCE_REAUTH:
                    return self.async_update_reload_and_abort(
                        self._get_reauth_entry(), data_updates=data
                    )
                return self.async_create_entry(title=self.box_name, data=data)
            except ApiError as exc:
                errors["base"] = form_error(exc)
        return self.async_show_form(
            step_id="code",
            description_placeholders={
                "fingerprint": " ".join(
                    self.pin[i : i + 4].upper() for i in range(0, 16, 4)
                )
            },
            data_schema=vol.Schema(
                {vol.Required("code"): selector.TextSelector({"type": "password"})}
            ),
            errors=errors,
        )


class SpeakerOptions(config_entries.OptionsFlow):
    async def async_step_init(self, user_input=None):
        if self.config_entry.unique_id != SPEAKERS:
            return self.async_abort(reason="no_options")

        return self.async_show_menu(step_id="init", menu_options=["speakers", "voice"])

    async def async_step_voice(self, user_input=None):
        old = self.config_entry.options
        keys = {
            "tts": "default_tts",
            "language": "default_language",
            "options": "default_tts_options",
        }
        if user_input is not None:
            updated = {k: v for k, v in old.items() if k not in keys.values()}
            if user_input.get("tts"):
                updated.update(
                    {key: user_input[k] for k, key in keys.items() if k in user_input}
                )
            return self.async_create_entry(data=updated)
        schema = vol.Schema(
            {
                vol.Optional("tts"): selector.EntitySelector({"domain": "tts"}),
                vol.Optional("language"): str,
                vol.Optional("options"): selector.ObjectSelector(),
            }
        )
        return self.async_show_form(
            step_id="voice",
            data_schema=self.add_suggested_values_to_schema(
                schema, {k: old[key] for k, key in keys.items() if key in old}
            ),
        )

    async def async_step_speakers(self, user_input=None):
        features = Feature.PLAY_MEDIA | Feature.VOLUME_SET
        eligible = [
            s.entity_id
            for s in self.hass.states.async_all("media_player")
            if s.attributes.get("supported_features", 0) & features == features
        ]
        if user_input is not None and all(
            e in eligible for e in user_input["speakers"]
        ):
            self.selected = list(dict.fromkeys(user_input["speakers"]))
            return await self.async_step_volumes()
        errors = {"base": "unsupported_speaker"} if user_input is not None else {}
        picker = selector.EntitySelector(
            {"multiple": True, "domain": "media_player", "include_entities": eligible}
        )
        return self.async_show_form(
            step_id="speakers",
            data_schema=vol.Schema(
                {
                    vol.Required(
                        "speakers",
                        default=self.config_entry.options.get("speakers", []),
                    ): picker
                }
            ),
            errors=errors,
        )

    async def async_step_volumes(self, user_input=None):
        if user_input is not None:
            self.volumes = {k: int(v) for k, v in user_input.items()}
            return await self.async_step_night()
        old = self.config_entry.options.get("volumes", {})
        return self.async_show_form(
            step_id="volumes",
            data_schema=vol.Schema(
                {
                    vol.Required(
                        entity, default=old.get(entity, 50)
                    ): selector.NumberSelector({"min": 0, "max": 100, "step": 1})
                    for entity in self.selected
                }
            ),
        )

    async def async_step_night(self, user_input=None):
        if user_input is not None:
            return self.async_create_entry(
                data={
                    **self.config_entry.options,
                    "speakers": self.selected,
                    "volumes": self.volumes,
                    **user_input,
                }
            )
        old = self.config_entry.options
        return self.async_show_form(
            step_id="night",
            data_schema=vol.Schema(
                {
                    vol.Required(
                        "night_start", default=old.get("night_start", "22:00")
                    ): selector.TimeSelector(),
                    vol.Required(
                        "night_end", default=old.get("night_end", "07:00")
                    ): selector.TimeSelector(),
                    vol.Required(
                        "speaker_at_night",
                        default=old.get("speaker_at_night", "urgent"),
                    ): vol.In(["urgent", "always", "never"]),
                }
            ),
        )
