from homeassistant.config_entries import ConfigFlow


class FixtureFlow(ConfigFlow, domain="fake_cast"):
    VERSION = 1
