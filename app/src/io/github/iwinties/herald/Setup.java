package io.github.iwinties.herald;

/** Setup decisions shared by the TV controls and the startup paths. */
final class Setup {
    static final String PRIVACY = "Herald has no accounts, analytics or ads. Your settings, paired client token "
            + "hashes, device identity, subscriptions and cached media are stored on this TV. "
            + "While receiving is on, devices on your network can discover this TV and, before pairing, "
            + "read its name, identifier, model, version, pairing availability and public certificate fingerprints. "
            + "Other data goes only to clients you pair and to the Home Assistant, MQTT, ntfy and media "
            + "servers you configure. "
            + "Remove a client in Pairing & clients to revoke access, or uninstall Herald to delete its data.\n\n"
            + "https://github.com/iwinties/herald/blob/main/docs/PRIVACY.md\n\n"
            + "Contact: https://github.com/iwinties/herald/issues";
    static final String OVERLAY_ADB = "This TV has no setting for it. From a computer: adb shell appops set "
            + "io.github.iwinties.herald SYSTEM_ALERT_WINDOW allow";
    static final String BATTERY_ADB = "This TV has no setting for it. From a computer: adb shell dumpsys "
            + "deviceidle whitelist +io.github.iwinties.herald";

    enum Target { PACKAGE_OVERLAY, OVERLAY, APP_DETAILS, BATTERY, ADB }

    static Target overlayTarget(boolean withPackage, boolean plain, boolean details) {
        if (withPackage) return Target.PACKAGE_OVERLAY;
        if (plain) return Target.OVERLAY;
        return details ? Target.APP_DETAILS : Target.ADB;
    }

    static Target batteryTarget(boolean resolves) { return resolves ? Target.BATTERY : Target.ADB; }
    static String grantStatus(boolean granted) { return granted ? "Allowed" : "Missing"; }
    static String testCardStatus(boolean overlay) { return overlay ? "Ready" : "Needs row 1"; }

    static boolean resolveReceiving(Boolean stored, boolean hasDeviceId, boolean hasAuth, boolean hasSettings) {
        return stored != null ? stored : hasDeviceId || hasAuth || hasSettings;
    }

    static boolean afterStart(boolean before, boolean refused) { return !refused || before; }

    static boolean available(String section, boolean receiving) {
        return receiving || "Setup".equals(section) || "About".equals(section);
    }

    static String receivingStatus(boolean on, boolean starting, boolean error) {
        if (starting) return "Starting…";
        if (error) return "Could not start";
        return on ? "On" : "Off";
    }
}
