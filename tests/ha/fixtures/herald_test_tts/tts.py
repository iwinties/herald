import io
import wave

import voluptuous as vol
from homeassistant.components.tts import TextToSpeechEntity


class Speech(TextToSpeechEntity):
    _attr_name = "Herald test"
    _attr_unique_id = "herald_test"
    _attr_default_language = "en"
    _attr_supported_languages = ["en"]
    _attr_supported_options = []

    async def async_get_tts_audio(self, message, language, options):
        self.calls += 1
        if self.fail:
            raise ValueError("fixture failure")
        with io.BytesIO() as buf:
            with wave.open(buf, "wb") as wav:
                wav.setparams((1, 2, 22050, 0, "NONE", "not compressed"))
                wav.writeframes(b"\x00\x10" * 22050)
            return "wav", buf.getvalue()


async def async_setup_entry(hass, entry, async_add_entities):
    speech = Speech()
    speech.calls = 0
    speech.fail = False
    hass.data["speech_fixture"] = speech

    async def mode(call):
        await hass.services.async_call("tts", "clear_cache", {}, blocking=True)
        speech.fail = call.data["mode"] == "fail"

    hass.services.async_register(
        "herald_test_tts",
        "set_mode",
        mode,
        schema=vol.Schema({vol.Required("mode"): vol.In(["ok", "fail"])}),
    )
    async_add_entities([speech])
