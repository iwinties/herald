"""Herald's v1 API. Only documented errors cross the transport boundary."""

import asyncio
import re

import aiohttp
from homeassistant.helpers.aiohttp_client import async_get_clientsession
from yarl import URL

SAFE_ERRORS = {
    "audio too large",
    "bad announce",
    "bad audio",
    "bad buttons",
    "bad card",
    "bad category",
    "bad chip",
    "bad clear",
    "bad client",
    "bad id",
    "bad image",
    "bad json",
    "bad key",
    "bad look",
    "bad output",
    "bad priority",
    "bad request id",
    "bad request",
    "bad settings",
    "bad source",
    "bad style",
    "bad url",
    "bad video",
    "body too large",
    "built-in category",
    "built-in look",
    "busy",
    "certificate changed",
    "empty card",
    "method not allowed",
    "no pairing",
    "not cached",
    "output not available",
    "overlay not allowed",
    "pairing disabled",
    "pairing expired",
    "pairing in progress",
    "queue full",
    "quiet hours",
    "request id conflict",
    "screen off",
    "settings store failed",
    "speech engine not ready",
    "store failed",
    "text is empty",
    "too many cards",
    "too many chips",
    "too many clients",
    "too many looks",
    "too many request ids",
    "too many waiters",
    "try later",
    "unauthorized",
    "unknown card",
    "unknown category",
    "unknown look",
    "unknown path",
    "wrong code",
}


class ApiError(Exception):
    def __init__(self, status=0, error="cannot connect", lost=False):
        super().__init__(error)
        self.status = status
        self.error = error
        self.lost = lost

    @property
    def requires_reauth(self):
        return self.status == 401 or (
            self.status == 0 and self.error == "certificate changed"
        )


def valid_pin(value):
    return (
        isinstance(value, str) and re.fullmatch(r"[0-9a-fA-F]{64}", value) is not None
    )


class AnnouncementPayload(aiohttp.payload.JsonPayload):
    sent = False

    async def write_with_length(self, writer, content_length):
        await super().write_with_length(writer, content_length)
        self.sent = True


class HeraldClient:
    def __init__(self, hass, host, port, token=None, pin=None):
        if not re.fullmatch(r"[a-zA-Z0-9:.%_-]+", host):
            raise ApiError()
        self.base = URL.build(scheme="https", host=host, port=port)
        self.session = async_get_clientsession(hass)
        if pin is not None and not valid_pin(pin):
            raise ApiError()
        self.fingerprint = (
            aiohttp.Fingerprint(bytes.fromhex(pin)) if pin is not None else None
        )
        self.token = token

    async def request(self, method, path, body=None, **params):
        if self.fingerprint is None and (
            method != "GET" or path != "info" or body is not None or params
        ):
            raise ApiError()
        headers = (
            {"Authorization": f"Bearer {self.token}"}
            if self.fingerprint and self.token
            else {}
        )
        kwargs = {"data": body} if isinstance(body, bytes) else {"json": body}
        announcement = None
        if method == "POST" and (
            path == "announce"
            or path == "cards"
            and isinstance(body, dict)
            and "announce" in body
        ):
            announcement = AnnouncementPayload(body)
            kwargs = {"data": announcement}
        deadline = 35 if path == "events" else 10
        try:
            async with self.session.request(
                method,
                self.base / "v1" / path,
                params=params,
                headers=headers,
                ssl=self.fingerprint or False,
                allow_redirects=False,
                timeout=aiohttp.ClientTimeout(total=deadline),
                **kwargs,
            ) as response:
                try:
                    data = await response.json(content_type=None)
                except ValueError:
                    data = {}
                if not isinstance(data, dict) or not (
                    200 <= response.status < 300 and data.get("ok") is True
                ):
                    error = data.get("error") if isinstance(data, dict) else None
                    raise ApiError(
                        response.status,
                        error
                        if isinstance(error, str) and error in SAFE_ERRORS
                        else "request failed",
                    )
                return data
        except aiohttp.ServerFingerprintMismatch:
            raise ApiError(0, "certificate changed") from None
        except aiohttp.ConnectionTimeoutError:
            raise ApiError() from None
        except (
            aiohttp.ServerDisconnectedError,
            aiohttp.ClientConnectionResetError,
            aiohttp.ClientPayloadError,
            asyncio.TimeoutError,
        ):
            raise ApiError(
                lost=announcement is not None and announcement.sent
            ) from None
        except (ValueError, aiohttp.ClientError, OSError):
            raise ApiError() from None
