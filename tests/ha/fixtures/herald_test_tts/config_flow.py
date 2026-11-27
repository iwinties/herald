from homeassistant.config_entries import ConfigFlow


class FixtureFlow(ConfigFlow, domain="herald_test_tts"):
    VERSION = 1
