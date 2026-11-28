"""Remove URL credentials before applying HA's key-based redaction."""

import re

from homeassistant.components.diagnostics import async_redact_data

PRIVATE = {"authorization", "token", "code", "password", "username", "topic"}
URL = re.compile(r"([A-Za-z][A-Za-z0-9+.-]*://)([^/\s?#]*)([^\s?#]*)(?:[?#][^\s]*)?")


def sanitize(value):
    if isinstance(value, str):
        return URL.sub(lambda m: m[1] + m[2].rsplit("@", 1)[-1] + m[3], value)
    if isinstance(value, (list, tuple)):
        return [sanitize(item) for item in value]
    if hasattr(value, "items"):
        return {
            k.lower() if k.lower() in PRIVATE else k: sanitize(v)
            for k, v in value.items()
        }
    return value


async def async_get_config_entry_diagnostics(hass, entry):
    data = {"entry": dict(entry.data), "options": dict(entry.options)}
    if entry.unique_id != "speakers" and hasattr(entry, "runtime_data"):
        data.update(
            status=entry.runtime_data.data, snapshots=entry.runtime_data.snapshots
        )
    return async_redact_data(sanitize(data), PRIVATE)
