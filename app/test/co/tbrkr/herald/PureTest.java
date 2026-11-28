package co.tbrkr.herald;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManagerFactory;
import java.security.SecureRandom;
import java.security.MessageDigest;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.List;
import java.util.Map;

/**
 * JVM tests for the pure parts of the Herald app (Http, Json, Api, Auth, EventLog, Backend, ClipStore,
 * Chime). No test framework: each case prints one PASS line; a failed check throws, so the
 * JVM exits with status 1. PLAN-20261125-herald-v1.
 */
public final class PureTest {
    static final String OK = "{\"ok\":true}";

    static File testRoot;
    public static void main(String[] args) throws Exception {
        testRoot = Files.createTempDirectory("herald-fixtures").toFile();
        try { runTests(); } finally { deleteTree(testRoot); }
    }

    private static void setupTests() {
        for (int mask = 0; mask < 8; mask++) {
            boolean id = (mask & 1) != 0, auth = (mask & 2) != 0, settings = (mask & 4) != 0;
            eq(mask != 0, Setup.resolveReceiving(null, id, auth, settings), "legacy install");
            for (boolean stored : new boolean[]{false, true})
                eq(stored, Setup.resolveReceiving(stored, id, auth, settings), "explicit choice");
        }
        pass("setup: an absent receiving flag is true when device_id, auth or settings exist (each alone) and false on a fresh install");
        eq(Setup.Target.PACKAGE_OVERLAY, Setup.overlayTarget(true, true, true), "package URI");
        eq(Setup.Target.OVERLAY, Setup.overlayTarget(false, true, true), "plain settings");
        eq(Setup.Target.APP_DETAILS, Setup.overlayTarget(false, false, true), "app details");
        eq(Setup.Target.ADB, Setup.overlayTarget(false, false, false), "no settings activity");
        pass("setup: overlay row falls back from the package URI to the plain action, to app details, to the ADB text");
        eq(Setup.Target.BATTERY, Setup.batteryTarget(true), "battery settings");
        eq(Setup.Target.ADB, Setup.batteryTarget(false), "battery fallback");
        pass("setup: battery row falls back to the ADB text when the settings screen does not resolve");
        eq("Allow overlay access first", Setup.testCardStatus(false), "missing overlay");
        eq("Ready", Setup.testCardStatus(true), "overlay granted");
        pass("setup: test card row needs the overlay permission");
        for (String section : SettingsModel.SECTIONS) {
            eq(section.equals("Setup") || section.equals("About"), Setup.available(section, false), section);
            check(Setup.available(section, true), section);
        }
        pass("setup: with receiving off only Setup and About are available");
        check(Setup.PRIVACY.contains("https://github.com/iwinties/herald/blob/main/docs/PRIVACY.md"), "policy");
        check(Setup.PRIVACY.contains("https://github.com/iwinties/herald/issues"), "contact");
        pass("setup: the privacy text names the public policy URL and the contact");
        for (boolean before : new boolean[]{false, true}) {
            eq(before, Setup.afterStart(before, true), "refused");
            check(Setup.afterStart(before, false), "accepted");
        }
        pass("setup: a refused start keeps the saved choice from before the request (Off stays Off, On stays On)");
    }

    static void runTests() throws Exception {
        tlsTests();
        setupTests();
        httpNoBody();
        pass("http reads a request with no body");
        httpBodyInPieces();
        pass("http reads a body that arrives in pieces");
        httpHeadLimit();
        pass("http rejects a header block over 8192 bytes");
        httpBadRequestLine();
        pass("http rejects a malformed request line");
        httpBadContentLength();
        pass("http rejects a bad content length");
        httpWrite();
        pass("http writes the response bytes exactly");
        jsonReads();
        pass("json reads strings with escapes, numbers and booleans");
        File root = Files.createTempDirectory("herald-test").toFile();
        try {
            storeKeys(new File(root, "keys"));
            pass("store accepts good keys and rejects bad ones");
            storePutSize(new File(root, "put"));
            pass("store put then size");
            storeReplace(new File(root, "replace"));
            pass("store put leaves no temporary file and replaces a clip");
            storePrune(new File(root, "prune"));
            pass("store prunes the oldest clips beyond the cap");
            storeTouch(new File(root, "touch"));
            pass("store touch keeps a played clip");
        } finally {
            deleteTree(root);
        }
        short[] chime = Chime.pcm(22050);
        chimeLength(chime);
        pass("chime has the documented length");
        chimePeak(chime);
        pass("chime peaks at a quarter of full scale and starts at zero");
        chimeEnd(chime);
        pass("chime ends near silence");
        jsonNested();
        pass("json reads nested objects and arrays up to depth 16");
        jsonBroken();
        pass("json rejects depth 17, trailing text and broken input");
        jsonRoundTrip();
        pass("json writes maps and lists that read back equal");
        apiInfo();
        pass("api answers info without a token");
        apiUnauthorized();
        pass("api answers 401 without or with an unknown token");
        apiOversize();
        pass("api answers 413 from Content-Length before reading the body or the token");
        apiRoutes();
        pass("api answers 404 and 405");
        apiHeaders();
        pass("api keeps one authorization header and refuses a duplicate");
        authStart();
        pass("auth pairing start shows a code and refuses a second pairing");
        authStartErrors();
        pass("auth pairing start answers 403 when disabled, 409 when the screen is off and 409 at 16 clients");
        authIssue();
        pass("auth pairing finish issues a token that authorizes and stores only its hash");
        authLockout();
        pass("auth five wrong codes cancel the pairing and lock start for 60 s");
        authExpired();
        pass("auth an expired pairing answers 410");
        authRevoke();
        pass("auth delete self revokes the token");
        authName();
        pass("auth finish refuses a client name other than the one shown");
        authCapacity();
        pass("auth the 16-client limit holds at issuance on both pairing paths");
        authTv();
        pass("auth a TV-started pairing is refused while a code is active or the lockout runs");
        authRace();
        pass("auth two simultaneous finishes issue at most one token");
        authRestart();
        pass("auth an active code does not survive a restart");
        eventsPoll();
        pass("events long-poll returns newer events at once and waits otherwise");
        eventsGap();
        pass("events report a gap when after is older than the buffer");
        eventsWaiters();
        pass("events refuse a fifth waiter with 429");
        eventsReset();
        pass("events answer reset when after is beyond the latest seq");
        eventsTime();
        pass("events stamp time in epoch milliseconds");
        statusFields();
        pass("status lists every documented field");
        settingsDefaults();
        pass("settings defaults hold the eight built-in categories with the documented styles");
        settingsPrecedence();
        pass("settings precedence is request over category over built-in");
        settingsMerge();
        pass("settings merge objects by key and replace the category list, restoring missing built-ins");
        settingsBounds();
        pass("settings reject each out-of-bound field with its dotted path");
        settingsStyle();
        pass("settings accept background colors, xlarge text and opacity from 0 to 100 in steps of 5");
        settingsRevision();
        pass("settings revision increments on every change and not on a no-op");
        settingsPassword();
        pass("settings hide the mqtt password, report password_set and keep it when a PUT leaves it empty");
        categoryRoutes();
        pass("categories create, restyle, delete custom and refuse deleting a built-in with 409");
        categoryLimit();
        pass("categories cap at 32");
        settingsRoundTrip();
        pass("settings round-trip through json unchanged");
        cidr();
        pass("cidr parses IPv4 and IPv6 addresses and ranges and rejects /33 and /129");
        cardFields();
        pass("cards validate fields in the documented order");
        cardText();
        pass("cards cut title 120 and message 1000 surrogate-safe");
        cardCategory();
        pass("cards unknown category falls back to general");
        cardUpdate();
        pass("cards same id updates in place and restarts the timer");
        cardStack();
        pass("cards replace, queue and stack per category");
        cardCap();
        pass("cards cap visible at max_visible, ending the lowest-priority oldest");
        cardCriticalCap();
        pass("cards all-critical overflow queues the new critical card first");
        cardQueueBound();
        pass("cards queue at most 32 and wait at most 16, then answer 429");
        cardCritical();
        pass("cards critical ignores show_here, over_playback and night hide");
        cardNight();
        pass("cards night dim lowers opacity and steps size and text down; hide skips");
        cardScreen();
        pass("cards screen off skips timed cards and keeps until-cleared cards waiting");
        cardSticky();
        pass("cards until-cleared cards end at sticky_cap with reason capped");
        cardClear();
        pass("cards clear by id, by category and all, idempotent");
        cardHistory();
        pass("cards ended cards are kept 600 s and at most 64");
        cardLatency();
        pass("cards card.shown carries ms since acceptance");
        chipFields();
        pass("chips validate (empty text needs an icon), cap at 12, expire, delete expired, and order high priority first");
        chipBulk();
        pass("chips delete only explicitly and the whole set is replaced with PUT /v1/chips");
        imageCache();
        pass("images validate keys and cap at 64 by last use");
        imageSample();
        pass("images decode within 1920 px and 2073600 pixels");
        imageRoutes();
        pass("images answer PUT and GET with the documented shapes and errors");
        videoFields();
        pass("video accepts rtsp, http and https, refuses rtsps and other schemes, and bounds timeout 3-60");
        buttonFields();
        pass("buttons accept at most 3 with ids, labels at most 24 and the three actions");
        buttonDefault();
        pass("buttons default to the event action");
        buttonFocus();
        pass("focus goes to the newest buttoned card only, and never with focus never");
        buttonEvent();
        pass("button event ends the card with reason button and keeps it for GET");
        buttonExpand();
        pass("expand restarts the timer at max(remaining, 60 s); back then ends it as dismissed");
        videoTimeout();
        pass("video without a first frame by timeout releases the player, reports failed and keeps the card");
        videoEnded();
        pass("video error after the first frame shows Live view ended and keeps the card");
        videoLate();
        pass("video callbacks from a released player are ignored");
        videoSound();
        pass("video sound follows the category video_sound unless the request sets muted");
        buttonBack();
        pass("back counts one dismissal per press across both paths");
        buttonPaused();
        pass("focus while playing false keeps a buttoned card unfocusable until the sound stops");
        announceSource();
        pass("announce needs exactly one source");
        announceOrder();
        pass("announce validates in the documented order");
        announceVolume();
        pass("announce volume defaults by output and clamps 0-100");
        announceNight();
        pass("announce night rule refuses the speaker with 409 quiet hours unless urgent or always");
        announceErrors();
        pass("announce passes on 404, 409, 429, 503 and 500 from the backend");
        announceText();
        pass("announce text trims and cuts to 500");
        announceAudio();
        pass("announce audio decodes base64 with whitespace up to 2097152 bytes and answers 413 beyond");
        announceCard();
        pass("announce card shows at play start and lasts at least audio length plus 3 s");
        announceDuck();
        pass("announce asks for ducking focus only when duck is true");
        announceRepeat();
        pass("announce request_id repeats within 600 s return the original job without playing");
        announceRace();
        pass("announce request ids are reserved atomically so two simultaneous duplicates play once");
        announceCapacity();
        pass("announce request ids survive a restart and a full table of 256 unexpired ids answers 429");
        announceLast();
        pass("announce last_audio reports the TV Speaker states");
        announceSound();
        pass("announce speak-and-show ignores the nested card's sound");
        announceChime();
        pass("announce chime defaults to the audio.chime setting");
        announceLifetime();
        pass("announce an until-cleared or unknown-length speak-and-show card follows its own duration");
        announcePrecedence();
        pass("announce a known request_id is answered before the output, cache, engine, capacity and table checks");
        announceRecovery();
        pass("announce at start-up a queued record is released and a playing one becomes interrupted and is never replayed");
        announceStore();
        pass("announce a request_id storage failure admits no work");
        announceClips();
        pass("clips keep the 1.1 key rule, size and cap");
        announceStop();
        pass("stop ends the current job, its card and the queue");
        soundTransitions();
        pass("sound turns on after 2 true samples and off after 3 false");
        soundOwnJob();
        pass("sound ignores samples during own playback and 1 s after");
        soundQuietSeconds();
        pass("sound quiet seconds count from the last true sample");
        soundPlayers();
        pass("sound tracks audible players as a set and starts the grace period when the last one stops, in either order");
        mqttTests();
        ntfyTests();
        compatTests();
        int frames = chime.length;
        eq(1270L, AudioTiming.remainingMs(5000, frames, 22050, 200, 5000), "whole chime and pause");
        eq(150L, AudioTiming.remainingMs(5000, frames, 22050, 200, 6120), "account for elapsed setup and release");
        eq(0L, AudioTiming.remainingMs(5000, frames, 22050, 200, 6270), "voice due");
        eq(0L, AudioTiming.remainingMs(5000, frames, 22050, 200, 6300), "no negative wait");
        pass("audio voice starts the chime length plus PAUSE_MS after the chime starts");
        Settings settings = new Fake().settings;
        Map<String,Object> video = Json.fields("url", "rtsp://example.invalid/live");
        Map<String,Object> card = Json.fields("video", video);
        eq(true, new CardSpec(card, settings).video.get("tcp"), "default RTP over TCP");
        video.put("tcp", false);
        eq(false, new CardSpec(card, settings).video.get("tcp"), "explicit UDP first");
        pass("video tcp defaults to true and an explicit false is kept");
        builtinLooks();
        pass("looks: the nine built-in looks resolve with the documented values");
        lookPrecedence();
        pass("looks: precedence is base, category look, category overrides, request look, request style, top-level icon");
        lookLifecycle();
        pass("looks: custom looks are saved, replaced at capacity, listed, used and deleted, with categories and the chip look falling back to glass");
        lookErrors();
        pass("looks: built-in looks are read-only and bad look writes, a missing style and unknown looks are refused with the documented errors");
        visualFields();
        pass("styles: the new visual fields and size full validate");
        iconValues();
        pass("icons: built-in names, emoji and image icons validate on cards and chips");
        schemaLoad();
        pass("settings: nothing stored or a stored document without schema 2 gives the defaults at revision 0 with no write or event, and MQTT starts from the accepted settings");
        schemaPut();
        pass("settings: PUT accepts schema 2 or none atomically, refuses other schemas and dangling look references, never repairs a replaced look list, and keeps the compat category rule");
        mixReset();
        pass("categories: effective styles follow the Mix decision, stored overrides start empty, every built-in category is silent, and reset clears every override");
        chipLooks();
        pass("chips: look and position validate on requests, stale stored looks draw with the display look, and they survive a restart");
        for (int code = -1; code <= 350; code++) {
            boolean belongs = java.util.Arrays.asList(4, 19, 20, 21, 22, 23, 66, 160).contains(code);
            eq(belongs, KeyPolicy.isCardKey(code), "key " + code);
        }
        pass("keys: only D-pad, OK, enter, numeric enter and BACK belong to a focused card");
        int soft = Math.round(.008f * 1080), offset = Math.round(.004f * 1080), glow = Math.round(.006f * 1080);
        eq("[0, 0, 0, 0]", java.util.Arrays.toString(CardFrame.insets(0, 0, 0)), "classic");
        eq("[9, 5, 9, 13]", java.util.Arrays.toString(CardFrame.insets(soft, offset, 0)), "soft");
        eq("[6, 6, 6, 6]", java.util.Arrays.toString(CardFrame.insets(0, 0, glow)), "glow");
        eq("[9, 6, 9, 13]", java.util.Arrays.toString(CardFrame.insets(soft, offset, glow)), "both");
        pass("cards: decoration insets are zero without effects and follow the shadow offset and the glow");
        int[][] sizes = {{600, 100}, {500, 120}, {400, 80}};
        int[] shadow = CardFrame.insets(soft, offset, 0);
        int[][] edges = {shadow, shadow, shadow};
        boolean[] widths = new boolean[3];
        eq("[[1253, 27, 1871, 145], [1353, 143, 1871, 281], [1453, 279, 1871, 377]]",
                java.util.Arrays.deepToString(CardFrame.place("top_right", 1920, 1080, sizes, widths, 16, edges)), "top right");
        eq("[[651, 369, 1269, 487], [701, 485, 1219, 623], [751, 621, 1169, 719]]",
                java.util.Arrays.deepToString(CardFrame.place("center", 1920, 1080, sizes, widths, 16, edges)), "center");
        eq("[[0, 963, 1920, 1061]]", java.util.Arrays.deepToString(CardFrame.place("bottom_left", 1920, 1080,
                new int[][]{{1920, 80}}, new boolean[]{true}, 16, new int[][]{shadow})), "full width strip");
        pass("cards: content is placed first, full-width axes have no margin, and stacks keep their gaps and center as a whole");
        chipGroups();
        pass("chips: rows group by position, at most five per group plus +N");
        chipPulses();
        pass("chips: the pulse fires once on creation as high, promotion and a change while high, never for hidden or normal chips, and other updates do not restart it");
        Fake lookFixture = new Fake();
        lookFixture.settings.putLook("empty", Json.fields("style", Json.fields()));
        Map<String, Object> visual = lookFixture.settings.chipVisual("empty");
        eq(Look.BASE, visual, "empty custom chip look");
        eq(null, ChipLayout.glowColor(visual, "#FEDCBA"), "no steady glow");
        eq("#FEDCBA", ChipLayout.glowColor(Json.fields("glow", "accent"), "#FEDCBA"), "chip accent");
        eq("#FF6B6B", ChipLayout.glowColor(lookFixture.settings.chipVisual("alert"), "#FEDCBA"), "resolved border");
        eq("[12, 2]", Arrays.toString(ChipLayout.room(300, 40)), "pulse room per side");
        pass("chips: sparse chip looks resolve over the base visual defaults, glow colors come from the chip or the border, and the scale room covers both axes");
        spokenTruth();
        pass("spoken cards: the announce truth table uses presence (absent inherits, false silent, true defaults, object overrides, null and other values refused)");
        spokenVectors();
        pass("spoken cards: the spoken text vectors match (cuts, trim, join, 500-unit bound)");
        spokenTiming();
        pass("spoken cards: with_voice answers pending_voice and shows the card at voice start, now posts it at once, silent requests keep the plain card answer");
        spokenDuplicates();
        pass("spoken cards: a repeated request_id returns the original card answer and job without effects, also concurrently and with a changed payload");
        spokenConflict();
        pass("spoken cards: a request id used by an audio-only announcement is a conflict, and announce with a spoken card id is a duplicate");
        spokenRefusal();
        pass("spoken cards: a refused announcement posts the card once, is not journaled, and a retry updates the same card");
        spokenDecision();
        pass("spoken cards: a board refusal or a journal failure leaves no effects, and a prepared now decision is applied as journaled");
        spokenReservations();
        pass("spoken cards: reservations are superseded, kept by a refused post, cleared once per id by delete and clear, and dropped by stop");
        spokenLifecycle();
        pass("spoken cards: one display attempt per job, a failure before the voice shows a current card once with its normal lifetime, no error finalizer, and stop deletes only the card it owns");
        spokenLockOrder();
        pass("spoken cards: a sounding card attaching while a now card is admitted lets both finish (one lock order)");
        spokenRoutes();
        pass("spoken cards: MQTT card, MQTT notify and compat keep their envelopes and report the announcement only for MQTT card");
        spokenErrors();
        pass("spoken cards: structural errors come before any effect, a restart releases queued jobs, and announce still refuses a card id");
        eq("596 072", PairingText.groups("596072"), "groups");
        eq("012 345", PairingText.groups("012345"), "leading zero");
        eq("2:00", PairingText.remaining(120), "start");
        eq("1:05", PairingText.remaining(65), "countdown");
        eq("0:00", PairingText.remaining(0), "expiry");
        pairingLayout(1920, 1080, new int[]{634,350,653,380}, 558, 49, 65,
                new int[]{785,843,901,972,1030,1088});
        pairingLayout(1280, 720, new int[]{423,234,435,253}, 374, 32, 43,
                new int[]{526,564,602,648,686,724});
        pass("pairing window: six digits in two groups, an m:ss countdown and the pixel layout at 1920x1080 and 1280x720");
        eq(Arrays.asList("Setup", "Pairing & clients", "Looks", "Categories", "Display", "Sound & voice",
                "Integrations", "Status", "About"), SettingsModel.SECTIONS, "sections");
        eq(Arrays.asList("Version  0.2", "Address  host:7979", "Address  [::1]:7979",
                "API  TLS unavailable", "Paired clients  2 · One, Two", "Last card  —", "Last audio  played chime",
                "Sound playing  false", "Screen on  true", "MQTT  off", "ntfy  —", "Compatibility  listening",
                "Overlay permission  true", "Battery exemption  —", "Receiving  On"),
                SettingsModel.statusRows(Json.fields("version", "0.2", "api_status", "TLS unavailable", "last_audio", "played chime",
                        "sound_playing", false, "screen_on", true, "mqtt", "off", "compat", "listening",
                        "overlay_allowed", true), Arrays.asList("One", "Two"), Arrays.asList("host:7979", "[::1]:7979"), true), "status");
        eq(Arrays.asList("MQTT", "ntfy", "TvOverlay"), SettingsModel.INTEGRATIONS, "integration destinations");
        eq("Off", SettingsModel.fieldValue("enabled", false), "boolean");
        eq("3 s", SettingsModel.fieldValue("duration", 3), "duration unit");
        eq("Top left", SettingsModel.fieldValue("chip_placement", "top_left"), "placement");
        eq("my_Look", SettingsModel.fieldValue("chip_look", "my_Look"), "literal look id");
        eq(6, SettingsModel.statusRows(Json.fields(), List.of(), List.of(), false).size(), "compact status");
        pass("settings app: the sections and the Status rows, with — for unknown values");
        Settings tileSettings = new Fake().settings;
        tileSettings.putLook("kids", Json.fields("name", "Kids", "style", Json.fields()));
        tileSettings.putCategory("media", Json.fields("look", "kids"));
        Map<Look,List<String>> tiles = SettingsModel.lookTiles(tileSettings);
        List<String> tileIds = new ArrayList<>();
        for (Look look : tiles.keySet()) tileIds.add(look.id);
        eq(Arrays.asList("classic", "glass", "minimal", "banner", "alert", "pill", "paper-note", "night-shift", "on-air", "kids"), tileIds, "tile order");
        eq(Arrays.asList("Doorbell", "Camera"), tiles.get(tileSettings.look("classic")), "classic uses");
        eq(Arrays.asList("Media"), tiles.get(tileSettings.look("kids")), "custom uses");
        eq(Arrays.asList(), tiles.get(tileSettings.look("pill")), "unused look");
        pass("settings app: look tiles list the built-ins then custom looks with the categories using each");
        Set<String> taken = new HashSet<>();
        eq("kids", SettingsModel.newLookId("Kids!", taken), "punctuation");
        eq("night_mode", SettingsModel.newLookId("Night Mode", taken), "space");
        eq("look", SettingsModel.newLookId("!!!", taken), "empty stem");
        String longName = "abcdefghijklmnopqrstuvwxyz0123456789abcd";
        eq(longName.substring(0,32), SettingsModel.newLookId(longName, taken), "32 character stem");
        taken.add(longName.substring(0,32));
        eq(longName.substring(0,30) + "_2", SettingsModel.newLookId(longName, taken), "first suffix");
        for (int n = 2; n <= 9; n++) taken.add(longName.substring(0,30) + "_" + n);
        eq(longName.substring(0,29) + "_10", SettingsModel.newLookId(longName, taken), "two digit suffix");
        pass("settings app: a new look id is normalized, cut to 32 characters, and suffixed within 32 characters");
        mqttCadence();
        pass("mqtt: state on real changes and the 60 s refresh, quiet on connect and then at most every 30 s with coalescing");
        mqttQuietDiscovery();
        pass("mqtt: discovery reads quiet_seconds from the quiet topic");
    }

    private static void pairingLayout(int sw, int sh, int[] body, int y, int w, int h, int[] xs) {
        PairingText.Layout layout = PairingText.layout(sw, sh);
        eq(Arrays.toString(body), Arrays.toString(layout.body), "pairing body");
        eq(xs.length, layout.boxes.length, "pairing box count");
        for (int i = 0; i < xs.length; i++)
            eq(Arrays.toString(new int[]{xs[i], y, w, h}), Arrays.toString(layout.boxes[i]), "pairing box " + i);
    }

    static Map<String,Object> speech(String request, String timing) {
        return Json.fields("request_id", request, "timing", timing);
    }

    static Map<String,Object> announcement(Map<String,Object> answer) {
        return Settings.object(answer.get("announce"), "announce");
    }

    static void spokenTruth() {
        Fake f = new Fake();
        check(!f.card("id", "plain").containsKey("announce"), "built-in defaults silent");
        f.settings.putCategory("general", Json.fields("announce", true));
        f.card("id", "inherited");
        check(f.accepted.spokenCard && f.accepted.text.equals("fixture"), "absent inherits");
        check(!f.card("id", "disabled", "announce", false).containsKey("announce"), "false overrides");
        f.settings.putCategory("general", Json.fields("announce", false));
        f.card("announce", true);
        eq("fixture", f.accepted.text, "true derives text");
        f.card("announce", Json.fields("text", "override", "volume", 23, "duck", true,
                "output", "speaker", "urgent", true, "chime", false));
        eq("override", f.accepted.text, "explicit speech");
        check(f.accepted.duck && f.accepted.urgent && !f.accepted.chime, "audio options");
        eq(23, f.accepted.volume("speaker", Json.fields()), "volume");
        eq("speaker", f.accepted.output, "output");
        for (Object invalid : new Object[]{null, 0, "false", List.of(), Json.fields("timing", "later"),
                Json.fields("url", "forbidden"), Json.fields("timing", null)})
            failure(() -> f.card("announce", invalid), 400, "bad announce");
        eq(3, f.admissions, "only requested speech queued");
    }

    static void spokenVectors() {
        String[][] vectors = {
                {"Garage door open", "Open for 20 minutes after dark.", "Garage door open. Open for 20 minutes after dark."},
                {"Open!", "Check now", "Open! Check now"}, {"  Front door  ", "\tunlocked \n", "Front door. unlocked"},
                {"", "Only message", "Only message"}, {"Only title", "   ", "Only title"},
                {"a".repeat(121), "Rest", "a".repeat(120) + ". Rest"},
                {"\u00a0Hi\u00a0", "", "\u00a0Hi\u00a0"},
                {"", "b".repeat(499) + "😀", "b".repeat(499)}, {"T", "c".repeat(1001), "T. " + "c".repeat(497)}};
        for (String[] v : vectors) eq(v[2], SpokenCards.spokenText(v[0], v[1]), "spoken vector");
        Fake f = new Fake();
        failure(() -> f.card("title", "", "image", Json.fields("key", "media"), "announce", true), 400, "text is empty");
        failure(() -> f.card("announce", Json.fields("text", "   ")), 400, "text is empty");
    }

    static void spokenTiming() {
        Fake f = new Fake();
        Map<String,Object> pending = f.card("id", "voice", "style", Json.fields("sound", "chime"), "announce", true);
        eq("pending_voice", pending.get("state"), "pending");
        eq(null, pending.get("reason"), "pending reason");
        eq(0, f.cards.rows().size(), "no early card");
        f.spokenJobs.get(0).show(f.cards, 12000);
        state(f, "voice", "showing", null);
        eq("none", f.cards.showing().get(0).spec.style.fields.get("sound"), "no second chime");
        eq(f.now + 15000, f.cards.showing().get(0).deadline, "voice length plus margin");
        Map<String,Object> immediate = f.card("id", "now", "category", "camera", "announce", Json.fields("timing", "now"));
        eq("showing", immediate.get("state"), "immediate");
        f.cards.delete("now");
        f.spokenJobs.get(1).show(f.cards, 100);
        check(!f.cards.delete("now"), "now job never reposts");
        eq(java.util.Set.of("ok", "id", "category", "state", "reason"), f.card("announce", false).keySet(), "plain keys");
    }

    static void spokenDuplicates() throws Exception {
        Fake f = new Fake();
        CountDownLatch go = new CountDownLatch(1);
        java.util.concurrent.Callable<Map<String,Object>> post = () -> {
            check(go.await(5, TimeUnit.SECONDS), "concurrent start");
            return f.card("announce", speech("once", "now"));
        };
        FutureTask<Map<String,Object>> a = new FutureTask<>(post), b = new FutureTask<>(post);
        daemon(a); daemon(b); go.countDown();
        Map<String,Object> first = a.get(5, TimeUnit.SECONDS), second = b.get(5, TimeUnit.SECONDS);
        check(announcement(first).containsKey("duplicate") != announcement(second).containsKey("duplicate"), "one duplicate");
        eq(announcement(first).get("job"), announcement(second).get("job"), "same job");
        f.attach();
        long before = f.events.latest();
        Map<String,Object> repeat = f.card("id", "other", "category", "media", "title", "changed", "announce", speech("once", "with_voice"));
        eq("once", repeat.get("id"), "original id");
        eq("general", repeat.get("category"), "original category");
        eq("showing", repeat.get("state"), "original decision");
        eq("queued", announcement(repeat).get("outcome"), "current job state");
        eq(before, f.events.latest(), "no update");
        eq(1, f.admissions, "one job"); eq(1, f.cards.rows().size(), "one card");
        eq("fixture", f.cards.showing().get(0).spec.title, "original title kept");
    }

    static Thread daemon(Runnable work) {
        Thread thread = new Thread(work);
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    static void spokenConflict() {
        Fake f = new Fake();
        f.announce(new AnnounceSpec(Json.fields("text", "audio only", "request_id", "audio"), f.settings));
        failure(() -> f.card("announce", speech("audio", "now")), 409, "request id conflict");
        eq(0, f.cards.rows().size(), "conflict has no card");
        Map<String,Object> answer = f.card("announce", speech("card", "with_voice"));
        Map<String,Object> repeat = f.announce(new AnnounceSpec(Json.fields("text", "direct", "request_id", "card"), f.settings));
        eq(Json.fields("ok", true, "job", announcement(answer).get("job"), "duplicate", true, "outcome", "queued"),
                repeat, "ordinary duplicate shape");
        eq(2, f.admissions, "only originals queued");
    }

    static void spokenRefusal() throws Exception {
        Fake f = new Fake();
        f.quiet = true;
        Map<String,Object> answer = f.card("announce", speech("stable", "with_voice"));
        eq("stable", answer.get("id"), "unsupplied id uses request id");
        eq(Json.fields("ok", false, "status", 409, "error", "quiet hours"), announcement(answer), "audio refusal");
        eq(null, f.requestIds.find("stable"), "refusal not saved");
        eq("showing", answer.get("state"), "silent fallback posted");
        f.quiet = false;
        f.card("title", "retry", "announce", speech("stable", "now"));
        eq(1, f.cards.rows().size(), "retry updates one card");
        eq("retry", f.cards.showing().get(0).spec.title, "updated spec");
        eq("given", f.card("id", "given", "announce", speech("separate", "now")).get("id"), "supplied id wins");

        List<Map<String,Object>> rows = new ArrayList<>();
        for (int i = 0; i < 256; i++) rows.add(Json.fields("id", "r" + i, "job", "j" + i, "at", f.now, "state", "played"));
        Files.writeString(new File(f.directory, "request-ids.json").toPath(), Json.stringify(rows));
        f.restart();
        Map<String,Object> full = f.card("announce", speech("extra", "now"));
        eq(Json.fields("ok", false, "status", 429, "error", "too many request ids"), announcement(full), "full journal");
        eq("showing", full.get("state"), "fallback uses prepared state");
        eq(null, f.requestIds.find("extra"), "no 257th record");
    }

    static void spokenDecision() throws Exception {
        Fake cap = new Fake();
        cap.post("visible");
        for (int i = 0; i < 32; i++) cap.post("queued" + i);
        failure(() -> cap.card("announce", speech("overflow", "now")), 429, "too many cards");
        eq(null, cap.requestIds.find("overflow"), "no overflow record");
        eq(0, cap.admissions, "no overflow job");
        eq(33, cap.cards.rows().size(), "board intact");

        Fake store = new Fake();
        store.post("expired", "style", Json.fields("duration", 3));
        store.now += 3001;
        check(new File(store.directory, "request-ids.json.tmp").mkdir(), "block atomic store");
        failure(() -> store.card("announce", speech("new", "now")), 500, "store failed");
        eq(0, store.cards.showing().size(), "only expiry happened");
        state(store, "expired", "ended", "expired");
        eq(null, store.requestIds.find("new"), "no failed record");
        eq(0, store.admissions, "no failed job");
        check(!store.cards.delete("new"), "no leaked reservation");

        Fake frozen = new Fake();
        frozen.checking = () -> { frozen.screen = false; frozen.overlay = false; frozen.now += 20000; };
        long at = frozen.now;
        Map<String,Object> accepted = frozen.card("style", Json.fields("duration", 3), "announce", speech("frozen", "now"));
        eq("showing", accepted.get("state"), "prepared answer");
        eq(at + 3000, frozen.cards.showing().get(0).deadline, "deadline at prepare");
        eq("frozen", frozen.cards.showing().get(0).spec.id, "applied despite permission and screen changes");
        Map<String,Object> duplicate = frozen.card("announce", speech("frozen", "now"));
        eq("showing", duplicate.get("state"), "stored decision");
        eq(1, frozen.admissions, "one frozen job");

        Fake promotion = new Fake();
        promotion.settings.put(at("display.max_visible", 3));
        Map<String,Object> replace = Json.fields("stacking", "replace");
        promotion.post("X", "priority", "critical", "style", replace);
        promotion.post("Y", "style", replace);
        state(promotion, "Y", "queued", null);
        Map<String,Object> promoted = promotion.card("id", "X", "style", replace, "announce", speech("promotion", "now"));
        eq("ended", promoted.get("state"), "post-promotion state");
        eq("replaced", promoted.get("reason"), "post-promotion reason");
        eq(1, promotion.cards.showing().size(), "one visible before tick");
        eq("Y", promotion.cards.showing().get(0).spec.id, "promoted before tick");
        Map<String,Object> saved = promotion.card("announce", speech("promotion", "now"));
        eq("ended", saved.get("state"), "duplicate includes promotion");
        eq("replaced", saved.get("reason"), "duplicate includes reason");
    }

    static void spokenReservations() {
        Fake f = new Fake();
        f.card("id", "shared", "announce", true);
        f.card("id", "shared", "title", "newer", "announce", true);
        f.spokenJobs.get(0).show(f.cards, 100);
        eq(0, f.cards.showing().size(), "old generation inert");
        f.overlay = false;
        failure(() -> f.post("shared"), 409, "overlay not allowed");
        f.overlay = true;
        f.spokenJobs.get(1).show(f.cards, 100);
        eq("newer", f.cards.showing().get(0).spec.title, "refused post preserved newer reservation");
        f.card("id", "pending", "announce", true);
        check(f.cards.delete("pending") && !f.cards.delete("pending"), "pending-only delete");
        f.card("id", "shared", "announce", true);
        f.card("id", "other", "category", "media", "announce", true);
        eq(1, f.cards.clear(Json.fields("category", "general")), "category counts overlapping id once");
        f.post("both");
        f.card("id", "both", "announce", true);
        eq(2, f.cards.clear(Json.fields("all", true)), "all counts two distinct ids");
        Fake stop = new Fake();
        eq("pending_voice", stop.card("id", "stopping", "announce", true).get("state"), "pending before stop");
        stop.stop();
        check(!stop.cards.delete("stopping"), "stop dropped reservation");
        Fake update = new Fake();
        update.card("id", "same", "announce", true);
        update.post("same");
        update.spokenJobs.get(0).show(update.cards, 1);
        check(!update.cards.owns(update.spokenJobs.get(0).owned), "successful ordinary post superseded reservation");
    }

    static void spokenLifecycle() {
        Fake early = new Fake();
        early.card("id", "early", "style", Json.fields("duration", 3), "announce", true);
        early.failPreparation = true;
        early.playSpoken(0, 20000, false);
        eq(early.now + 3000, early.cards.showing().get(0).deadline, "normal fallback lifetime");
        early.attach();
        long cursor = early.events.latest();
        early.spokenJobs.get(0).end(early.cards, "error");
        early.accepted.endCard(early.cards, "error");
        state(early, "early", "showing", null);
        eq(cursor, early.events.latest(), "no second display or failure finalizer");

        Fake start = new Fake();
        start.card("id", "start", "announce", Json.fields("chime", false));
        start.playSpoken(0, 20000, true);
        eq(start.now + 23000, start.cards.showing().get(0).deadline, "no-chime attempted before start failure");
        eq(0L, start.since(0).stream().filter(row -> "card.updated".equals(((Map<?,?>)row).get("type"))).count(), "no retry");
        Fake denied = new Fake();
        denied.card("id", "denied", "announce", true);
        denied.overlay = false;
        denied.spokenJobs.get(0).show(denied.cards, 10000);
        denied.overlay = true;
        denied.spokenJobs.get(0).end(denied.cards, "error");
        check(!denied.cards.delete("denied"), "display denial consumes without throwing into playback");

        Fake stopped = new Fake();
        stopped.card("id", "now", "announce", Json.fields("timing", "now"));
        stopped.stop();
        state(stopped, "now", "ended", "stopped");
        stopped.card("id", "again", "announce", Json.fields("timing", "now"));
        stopped.post("again");
        stopped.stop();
        state(stopped, "again", "showing", null);

        Fake legacy = new Fake();
        AnnounceSpec direct = new AnnounceSpec(Json.fields("text", "direct", "card", Json.fields("title", "direct")), legacy.settings);
        direct.show(legacy.cards, 500);
        legacy.card("id", direct.card.id, "announce", true);
        direct.endCard(legacy.cards, "stopped");
        legacy.spokenJobs.get(0).show(legacy.cards, 10);
        check(legacy.cards.owns(legacy.spokenJobs.get(0).owned), "ordinary stop preserves a newer spoken reservation");

        Fake race = new Fake();
        race.card("id", "same", "announce", true);
        SpokenCards.JobCard a = race.spokenJobs.get(0);
        a.show(race.cards, 100);
        race.card("id", "same", "title", "B", "announce", true);
        SpokenCards.JobCard b = race.spokenJobs.get(1);
        a.end(race.cards, "stopped");
        eq(0, race.cards.showing().size(), "A removed");
        b.show(race.cards, 100);
        check(race.cards.owns(b.owned), "B reservation survived deferred stop");
        race.attach();
        cursor = race.events.latest();
        b.show(race.cards, 100);
        eq(cursor, race.events.latest(), "B shown only once");
        race.card("id", "cleared", "announce", true);
        race.cards.clear(Json.fields("all", true));
        race.spokenJobs.get(2).end(race.cards, "error");
        check(!race.cards.delete("cleared"), "no stale fallback after clear");
    }

    static void spokenLockOrder() throws Exception {
        Fake f = new Fake();
        f.post("sound", "style", Json.fields("sound", "chime"));
        CountDownLatch attached = new CountDownLatch(1), allowSound = new CountDownLatch(1);
        f.attaching = () -> {
            check(Thread.holdsLock(f.cards), "attach owns board");
            attached.countDown();
            try { check(allowSound.await(5, TimeUnit.SECONDS), "sound released"); }
            catch (InterruptedException e) { throw new AssertionError(e); }
        };
        FutureTask<Void> a = new FutureTask<>(() -> { f.attach(); return null; });
        daemon(a);
        check(attached.await(5, TimeUnit.SECONDS), "attaching");
        FutureTask<Map<String,Object>> b = new FutureTask<>(() -> f.card("id", "now", "category", "camera",
                "announce", Json.fields("timing", "now")));
        Thread poster = daemon(b);
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        try {
            while (poster.getState() != Thread.State.BLOCKED && System.nanoTime() < end) Thread.yield();
            eq(Thread.State.BLOCKED, poster.getState(), "now post waits for board held by attachment");
        } finally { allowSound.countDown(); }
        a.get(5, TimeUnit.SECONDS);
        eq("showing", b.get(5, TimeUnit.SECONDS).get("state"), "now post completed");
        eq(1, f.sounds, "sound admitted"); eq(1, f.admissions, "spoken job queued");
    }

    static void spokenRoutes() throws Exception {
        Fake f = new Fake();
        f.settings.putCategory("general", Json.fields("announce", true));
        Map<String,Object> wire = ok(f.route("POST", "cards", Json.fields("title", "network", "announce", true)));
        eq(java.util.Set.of("ok", "id", "category", "state", "reason", "announce"), wire.keySet(), "HTTP spoken envelope");
        eq(java.util.Set.of("ok", "job"), announcement(wire).keySet(), "accepted speech keys");
        Map<String,Object> cmd = MqttTopics.run("card", bytes("{\"title\":\"mqtt\",\"announce\":{ \"chime\":false }}"),
                f, f.settings, f.cards, f.chips, f.requestIds);
        eq(java.util.Set.of("cmd", "ok", "error", "announce"), cmd.keySet(), "MQTT spoken keys");
        eq(java.util.Set.of("ok", "job"), announcement(cmd).keySet(), "MQTT speech");
        eq(Json.fields("cmd", "notify", "ok", true, "error", null), MqttTopics.run("notify", bytes("notification"),
                f, f.settings, f.cards, f.chips, f.requestIds), "notify keys");
        eq("notification", f.accepted.text, "notify spoke category default");
        eq(Json.fields("cmd", "card", "ok", true, "error", null), MqttTopics.run("card",
                bytes("{\"title\":\"silent\",\"announce\":false}"), f, f.settings, f.cards, f.chips, f.requestIds), "silent MQTT");
        MqttTopics topics = new MqttTopics("root", "id", true, () -> f.now);
        eq(null, topics.commandName(topics.root + "cmd/card", true), "retained refused");
        f.settings.put(at("compat.tvoverlay", Json.fields("enabled", true, "allowed", List.of("192.0.2.0/28"))));
        Compat compat = new Compat(f.settings, f.cards, f.chips, () -> f.now, f, f.requestIds);
        compatPost(compat, "/notify", Json.fields("title", "legacy", "announce", false), 200, "Notification received");
        eq("legacy", f.accepted.text, "compat inherits even if sender included false");
        for (String command : List.of("notify", "card"))
            failure(() -> MqttTopics.run(command, new byte[1572865], f, f.settings, f.cards, f.chips, f.requestIds),
                    413, "body too large");
        compatReply(compat, "192.0.2.1", stream("POST /notify HTTP/1.1\r\nContent-Length: 1572865\r\n\r\n"),
                413, "body too large");
        eq(413, run(f.api, stream("POST /v1/cards HTTP/1.1\r\nContent-Length: 1572865\r\n\r\n")).code, "HTTP envelope");
    }

    static void spokenErrors() {
        Fake f = new Fake();
        failure(() -> f.card("title", "", "announce", null), 400, "empty card");
        failure(() -> f.card("announce", Json.fields("timing", "invalid", "text", 1)), 400, "bad announce");
        failure(() -> f.card("announce", Json.fields("text", "hi", "clip", "clip")), 400, "bad source");
        failure(() -> f.card("announce", Json.fields("audio", "")), 400, "bad audio");
        failure(() -> f.card("title", "", "image", Json.fields("key", "media"),
                "announce", Json.fields("request_id", ".")), 400, "bad request id");
        eq(0, f.admissions, "invalid structures never queued");
        eq(0, f.cards.clear(Json.fields("all", true)), "no card or reservation");
        failure(() -> new AnnounceSpec(Json.fields("text", "test", "card", Json.fields("id", "x", "title", "x")), f.settings),
                400, "bad card");
        f.card("announce", speech("queued", "with_voice"));
        Map<String,Object> playing = f.card("announce", speech("playing", "with_voice"));
        String job = (String)announcement(playing).get("job");
        f.requestIds.state("playing", job, "playing");
        f.restart();
        eq(0, f.cards.clear(Json.fields("all", true)), "restart lost reservations");
        eq(null, f.requestIds.find("queued"), "queued journal dropped");
        Map<String,Object> replay = f.card("announce", speech("playing", "now"));
        eq(Json.fields("ok", true, "job", job, "outcome", "interrupted", "duplicate", true),
                announcement(replay), "interrupted duplicate");
        eq("pending_voice", replay.get("state"), "original card answer after restart");
        check(!announcement(f.card("announce", speech("queued", "now"))).containsKey("duplicate"), "queued readmitted");
    }

    static void chipGroups() {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            rows.add(Json.fields("id", "left" + i, "position", i == 0 ? null : "top_left"));
            if (i < 2) rows.add(Json.fields("id", "right" + i, "position", "bottom_right"));
        }
        Map<String, ChipLayout.Group> groups = ChipLayout.groups(rows, "top_left");
        eq(List.of("top_left", "bottom_right"), new ArrayList<>(groups.keySet()), "position order");
        ChipLayout.Group left = groups.get("top_left");
        eq(5, left.visible.size(), "left visible");
        for (int i = 0; i < 5; i++) eq("left" + i, left.visible.get(i).get("id"), "row order");
        eq(2, left.hidden, "left overflow");
        eq(2, groups.get("bottom_right").visible.size(), "right visible");
        eq(0, groups.get("bottom_right").hidden, "right overflow");
    }

    static void chipPulses() {
        Fake f = new Fake();
        ChipPulse pulses = new ChipPulse();
        Runnable refresh = () -> pulses.update(ChipLayout.groups(f.chips.rows(), "top_left"), f.now);
        Map<String, Object> high = Json.fields("text", "open", "priority", "high", "expires", 3);
        f.chips.put("a", high);
        refresh.run();
        eq(f.now, pulses.start("a"), "created high");
        long first = f.now++;
        f.chips.put("b", Json.fields("text", "normal"));
        f.settings.putLook("custom", Json.fields("style", Json.fields()));
        refresh.run();
        eq(first, pulses.start("a"), "unrelated row and settings revision");
        eq(-1L, pulses.start("b"), "normal");
        f.chips.put("b", high);
        refresh.run();
        eq(f.now, pulses.start("b"), "promoted");
        for (String field : List.of("text", "icon", "color")) {
            high.put(field, field.equals("text") ? "closed" : field.equals("icon") ? "info" : "#123456");
            f.now++;
            f.chips.put("b", high);
            refresh.run();
            eq(f.now, pulses.start("b"), "high changed " + field);
        }
        long changed = f.now++;
        refresh.run();
        eq(changed, pulses.start("b"), "unchanged refresh");
        f.chips.delete("b");
        refresh.run();
        eq(-1L, pulses.start("b"), "forgot removed");
        f.chips.put("b", high);
        refresh.run();
        eq(f.now, pulses.start("b"), "recreated");
        for (int i = 0; i < 4; i++) f.chips.put("other" + i, high);
        refresh.run();
        eq(-1L, pulses.start("a"), "sixth high hidden");
        f.chips.delete("other3");
        refresh.run();
        eq(f.now, pulses.start("a"), "high newly visible");
        f.now += 4000;
        refresh.run();
        eq(-1L, pulses.start("a"), "forgot expired");
    }

    static void builtinLooks() throws IOException {
        Fake f = new Fake();
        List<Map<String, Object>> expected = List.of(
                Json.fields("layout", "card", "corner", "small", "border", "none", "border_color", "#FFFFFF",
                        "shadow", "soft", "glow", "none", "accent_style", "bar",
                        "background", "#000000", "opacity", 90, "title_color", "#FFFFFF",
                        "text_color", "#FFFFFF", "title_weight", "bold", "label", "none"),
                Json.fields("layout", "card", "corner", "large", "border", "thin", "border_color", "#5A6272",
                        "shadow", "soft", "glow", "none", "accent_style", "badge",
                        "background", "#1C202A", "opacity", 60, "title_color", "#FFFFFF",
                        "text_color", "#FFFFFF", "title_weight", "bold", "label", "none"),
                Json.fields("layout", "text", "corner", "none", "border", "none", "border_color", "#FFFFFF",
                        "shadow", "none", "glow", "none", "accent_style", "dot",
                        "background", "#000000", "opacity", 0, "title_color", "#FFFFFF",
                        "text_color", "#FFFFFF", "title_weight", "bold", "label", "none"),
                Json.fields("layout", "strip", "placement", "top", "size", "full", "corner", "none",
                        "border", "none", "border_color", "#FFFFFF", "shadow", "soft",
                        "glow", "none", "accent_style", "underline", "background", "#0C1018",
                        "opacity", 80, "title_color", "#FFFFFF", "text_color", "#FFFFFF",
                        "title_weight", "bold", "label", "none"),
                Json.fields("layout", "card", "placement", "center", "size", "large", "text_size", "large",
                        "corner", "medium", "border", "thick", "border_color", "#FF6B6B",
                        "shadow", "strong", "glow", "border", "accent_style", "badge",
                        "background", "#781218", "opacity", 85, "title_color", "#FFFFFF",
                        "text_color", "#FFE3E3", "title_weight", "bold", "label", "category"),
                Json.fields("layout", "pill", "placement", "top", "size", "medium", "corner", "pill",
                        "border", "thin", "border_color", "#3A404C", "shadow", "soft",
                        "glow", "none", "accent_style", "badge", "background", "#181C24",
                        "opacity", 80, "title_color", "#FFFFFF", "text_color", "#FFFFFF",
                        "title_weight", "bold", "label", "none"));
        String[] ids = "classic glass minimal banner alert pill paper-note night-shift on-air".split(" ");
        List<?> rows = (List<?>) ok(f.route("GET", "looks", Json.fields())).get("looks");
        eq(ids.length, rows.size(), "nine looks");
        for (int i = 0; i < ids.length; i++) {
            Map<String, Object> row = Settings.object(rows.get(i), "look");
            eq(ids[i], row.get("id"), "built-in order");
            eq(true, row.get("builtin"), "read-only");
            Map<String,Object> overrides = Settings.object(row.get("style"), "style");
            if (i < expected.size()) eq(Json.parse(Json.stringify(expected.get(i))), overrides, "exact original override map");
            else eq(List.of("outline", "raised", "underline").get(i - expected.size()), overrides.get("button_style"), "new Look finish");
            Map<String, Object> fields = Json.parse(Json.stringify(f.settings.resolve("general", ids[i], null).fields));
            overrides.forEach((key, value) -> eq(value, fields.get(key), key));
        }
        f.settings.putLook("night_shift", at("style.button_focus_background", "#FFFFFF"));
        f.settings.putCategory("reminder", Json.fields("look", "night_shift"));
        f.settings.put(at("ntfy.topics", List.of(Json.fields("topic", "porch", "look", "night-shift"))));
        f.restart();
        eq("#FFFFFF", f.settings.effective("reminder").get("button_focus_background"), "existing custom palette survives restart");
        eq("raised", f.settings.resolve("reminder", "night-shift", null).fields.get("button_style"), "built-in uses separate id");
        eq("solid", f.settings.resolve("reminder", "night-shift", Json.fields("button_style", "solid")).fields.get("button_style"), "request overrides Look finish");
    }

    static void lookPrecedence() {
        Settings s = new Fake().settings;
        s.putCategory("alarm", Json.fields("style", Json.fields("corner", "pill", "opacity", 20, "accent", "#ABCDEF")));
        Map<String, Object> doc = Json.fields("category", "alarm", "title", "Alarm", "look", "classic",
                "style", Json.fields("icon", "bell"), "icon", "emoji:🔔");
        Map<String, Object> fields = new CardSpec(doc, s).style.fields;
        Map<String, Object> expected = Json.fields("layout", "card", "placement", "center", "size", "large",
                "text_size", "large", "background", "#000000", "opacity", 90, "corner", "small", "border", "none",
                "glow", "none", "label", "none", "accent_style", "bar", "icon", "emoji:🔔", "accent", "#ABCDEF");
        expected.forEach((key, value) -> eq(value, fields.get(key), key));
        eq(20, s.effective("alarm").get("opacity"), "request did not change category");
        doc.put("style", Json.fields("background", "#123456"));
        eq("#123456", new CardSpec(doc, s).style.fields.get("background"), "request style beats request look");
    }

    static void lookLifecycle() throws IOException {
        Fake f = new Fake();
        for (int i = 30; i >= 0; i--) ok(f.route("PUT", "looks/x" + i, Json.fields("style", Json.fields())));
        ok(f.route("PUT", "looks/custom", Json.fields("style", Json.fields("size", "full"))));
        Map<String, Object> patch = Json.fields("name", "Den", "style", Json.fields("border", "thick"));
        Map<String, Object> expected = Json.fields("id", "custom", "name", "Den", "style", patch.get("style"), "builtin", false);
        eq(Json.fields("ok", true, "look", expected), ok(f.route("PUT", "looks/custom", patch)), "replace at capacity");
        ok(f.route("PUT", "categories/general", Json.fields("look", "custom")));
        ok(f.route("PUT", "settings", at("display.chip_look", "custom")));
        f.restart();
        List<?> looks = (List<?>) ok(f.route("GET", "looks", Json.fields())).get("looks");
        eq(32 + Look.BUILTIN_IDS.size(), looks.size(), "capacity");
        eq(expected, looks.get(Look.BUILTIN_IDS.size()), "custom rows sorted after built-ins");
        eq("x10", Settings.object(looks.get(Look.BUILTIN_IDS.size() + 3), "look").get("id"), "lexical order");
        eq("thick", f.settings.effective("general").get("border"), "category uses saved look");
        eq("medium", f.settings.chipVisual(null).get("size"), "replaced style fell back to base");
        eq("thick", f.settings.chipVisual(null).get("border"), "display uses saved look");
        int revision = f.settings.revision();
        long seq = f.events.latest();
        eq(Json.fields("ok", true), ok(f.route("DELETE", "looks/custom", Json.fields())), "delete");
        eq(revision + 1, f.settings.revision(), "atomic repair");
        eq(seq + 1, f.events.latest(), "one repair event");
        f.restart();
        eq("glass", f.settings.categories().get(0).get("look"), "saved category fallback");
        eq("glass", f.settings.display().get("chip_look"), "saved display fallback");
        check(!f.settings.hasLook("custom"), "look removed");
    }

    static void lookField(Fake f, String key, Map<String, Object> patch, String field) throws IOException {
        Reply reply = f.route("PUT", "looks/" + key, patch);
        error(reply, 400, "bad settings");
        eq(field, Json.parse(reply.body).get("field"), "look field");
    }

    static void lookErrors() throws IOException {
        Fake f = new Fake();
        Map<String, Object> empty = Json.fields("style", Json.fields());
        String boundary = "a".repeat(32);
        ok(f.route("PUT", "looks/" + boundary, empty));
        eq(boundary, f.settings.look(boundary).name, "name defaults to id");
        eq(Look.BASE, f.settings.chipVisual(boundary), "empty override");
        lookField(f, boundary + "a", Json.fields(), "id");
        lookField(f, "BAD", Json.fields(), "id");
        for (String id : Look.BUILTIN_IDS) {
            error(f.route("PUT", "looks/" + id, Json.fields()), 409, "built-in look");
            error(f.route("DELETE", "looks/" + id, Json.fields()), 409, "built-in look");
        }
        error(f.route("GET", "looks/foo", empty), 405, "method not allowed");
        error(f.route("PUT", "looks", empty), 405, "method not allowed");
        error(f.route("DELETE", "looks/missing", empty), 404, "unknown look");
        Reply invalid = f.route("DELETE", "looks/.bad", empty);
        error(invalid, 400, "bad settings");
        eq("id", Json.parse(invalid.body).get("field"), "bad delete id");
        lookField(f, "foo", Json.fields("other", true, "name", false), "look.other");
        lookField(f, "foo", Json.fields("name", ""), "look.name");
        lookField(f, "foo", Json.fields("name", "😀".repeat(41)), "look.name");
        ok(f.route("PUT", "looks/foo", Json.fields("name", "😀".repeat(40), "style", Json.fields())));
        lookField(f, "foo", Json.fields(), "look.style");
        lookField(f, "foo", Json.fields("style", null), "look.style");
        lookField(f, "foo", Json.fields("style", List.of()), "look.style");
        lookField(f, "foo", at("style.accent", "#ABCDEF"), "look.style.accent");
        lookField(f, "foo", at("style.corner", "round"), "look.style.corner");
        for (int i = 0; i < 30; i++) f.settings.putLook("cap" + i, empty);
        lookField(f, "extra", Json.fields(), "look.style");
        error(f.route("PUT", "looks/extra", empty), 409, "too many looks");
        error(f.route("POST", "cards", Json.fields("title", "Hi", "look", "absent", "style", false)), 400, "bad look");
        error(f.route("POST", "cards", Json.fields("title", "Hi", "look", "absent", "priority", false)), 400, "bad priority");
        error(f.route("PUT", "chips/new", Json.fields("text", "Hi", "look", "absent")), 400, "bad look");
        Settings failing = new Settings("TV", f.settingsJson, doc -> { throw new Api.Failure(500, "settings store failed"); }, f.events);
        Map<String, Object> before = failing.document();
        failure(() -> failing.deleteLook("foo"), 500, "settings store failed");
        failure(() -> failing.putLook("foo", empty), 500, "settings store failed");
        eq(before, failing.document(), "failed saves preserve full document");
    }

    static void visualFields() {
        Settings s = new Fake().settings;
        String[][] values = {{"layout","card text strip pill"}, {"corner","none small medium large pill"},
                {"border","none thin medium thick"}, {"border_color","#aBcDeF"}, {"shadow","none soft strong"},
                {"glow","none accent border"}, {"accent_style","bar badge dot underline none"},
                {"title_color","#123456"}, {"text_color","#0123AB"}, {"title_weight","bold normal"},
                {"button_style","solid outline underline raised"}, {"button_shape","square rounded pill"}, {"button_spacing","compact normal roomy"},
                {"button_background","#654321"}, {"button_text_color","#13579A"},
                {"button_focus_background","#ABCDEF"}, {"button_focus_text_color","#010203"},
                {"label","none category"}, {"size","full"}};
        for (String[] row : values) {
            for (String value : row[1].split(" ")) eq(value, s.resolve("general", Json.fields(row[0], value)).fields.get(row[0]), row[0]);
            try {
                s.resolve("general", Json.fields(row[0], "invalid"));
                throw new AssertionError("accepted invalid " + row[0]);
            } catch (Settings.Invalid invalid) { eq("style." + row[0], invalid.field, "field"); }
        }
    }

    static void iconValues() {
        Fake f = new Fake();
        for (String icon : ("none info bell door camera alarm warning home media megaphone person package tv timer check "
                + "emoji:🚪 emoji:😀😀😀😀😀😀😀😀 image:-image.1").split(" ")) {
            eq(icon, new CardSpec(Json.fields("title", "Hi", "icon", icon), f.settings).style.fields.get("icon"), "card icon");
            f.chips.put("chip", Json.fields("text", "Hi", "icon", icon));
            eq(icon, f.chips.rows().get(0).get("icon"), "chip icon");
        }
        for (String icon : List.of("emoji:", "emoji:😀".concat("a".repeat(8)), "emoji:a b", "emoji:a\u00a0",
                "emoji:a\u0007", "image:", "image:.bad", "image:" + "x".repeat(65), "unknown")) {
            failure(() -> new CardSpec(Json.fields("title", "Hi", "icon", icon), f.settings), 400, "bad style");
            failure(() -> f.chips.put("chip", Json.fields("icon", icon)), 400, "bad chip");
        }
    }

    static void schemaLoad() {
        Fake f = new Fake();
        int[] saves = {0};
        java.util.function.Consumer<String> count = doc -> saves[0]++;
        Map<String, Object> defaults = f.settings.document();
        String old = "{\"schema\":1,\"revision\":7,\"categories\":[{\"id\":\"general\","
                + "\"style\":{\"background\":\"#ABCDEF\"}}],\"mqtt\":{\"enabled\":true,\"password\":\"fixture-only\"}}";
        for (String stored : Arrays.asList(null, old, "{\"schema\":3}", "{}")) {
            Settings s = new Settings("Living Room", stored, count, f.events);
            eq(defaults, s.document(), "clean defaults");
            eq(0, s.revision(), "initial revision");
            eq(stored != null, s.discardedStored(), "discard flag");
            Map<String, Object> mqtt = s.mqttPrivate();
            eq(false, mqtt.get("enabled"), "MQTT disabled");
            check("".equals(mqtt.get("password")) && !mqtt.containsKey("password_set"), "empty private MQTT");
        }
        String synthetic = java.util.UUID.randomUUID().toString();
        f.settings.put(at("mqtt.password", synthetic));
        long seq = f.events.latest();
        Settings s = new Settings("Living Room", f.settingsJson, count, f.events);
        check(!s.discardedStored(), "accepted schema");
        eq(1, s.revision(), "loaded revision");
        Map<String, Object> privateMqtt = s.mqttPrivate();
        check(synthetic.equals(privateMqtt.get("password")) && !privateMqtt.containsKey("password_set"), "accepted private MQTT");
        privateMqtt.clear();
        check(synthetic.equals(s.mqttPrivate().get("password")), "defensive copy");
        Map<String, Object> publicMqtt = Settings.object(s.document().get("mqtt"), "mqtt");
        check(!publicMqtt.containsKey("password") && Boolean.TRUE.equals(publicMqtt.get("password_set")), "redacted");
        eq(0, saves[0], "no load writes");
        eq(1L, seq, "only explicit change emitted");
        eq(seq, f.events.latest(), "accepted load silent");
        try {
            new Settings("TV", "{\"schema\":2,\"revision\":1,\"display\":{\"chip_look\":\"missing\"}}", count, f.events);
            throw new AssertionError("invalid schema 2 loaded");
        } catch (Settings.Invalid invalid) { eq("display.chip_look", invalid.field, "invalid stored reference"); }
    }

    static void schemaPut() throws IOException {
        Fake f = new Fake();
        String token = f.issue("settings");
        Map<String, Object> look = Json.fields("id", "custom", "style", Json.fields("opacity", 5));
        Map<String, Object> cat = Json.fields("id", "demo", "look", "custom", "style", Json.fields("size", "small"));
        ok(f.settingsRequest("PUT", "/v1/settings", token, Json.fields("looks", List.of(look), "categories", List.of(cat),
                "display", Json.fields("chip_look", "custom"), "compat", Json.fields("tvoverlay", Json.fields("category", "demo")))));
        Map<String, Object> before = f.settings.document();
        String saved = f.settingsJson;
        long seq = f.events.latest();
        Map<String, Object> fetched = ok(f.settingsRequest("GET", "/v1/settings", token, Json.fields()));
        eq(2.0, fetched.get("schema"), "GET schema");
        ok(f.settingsRequest("PUT", "/v1/settings", token, fetched));
        ok(f.settingsRequest("PUT", "/v1/settings", token, Json.fields()));
        for (Object schema : Arrays.asList(1, 3, 2.5, "2", null))
            badSettings(f, token, Json.fields("schema", schema, "device", Json.fields("name", "Changed")), "schema");
        badSettings(f, token, Json.fields("looks", List.of()), "categories.0.look");
        badSettings(f, token, Json.fields("looks", List.of(), "categories", List.of()), "display.chip_look");
        badSettings(f, token, Json.fields("categories", List.of(cat, Json.fields("id", "c", "look", "missing"))), "categories.1.look");
        badSettings(f, token, Json.fields("categories", List.of(cat, cat)), "categories.1.id");
        badSettings(f, token, Json.fields("looks", List.of(look, look)), "looks.1.id");
        List<Map<String, Object>> tooMany = new ArrayList<>();
        for (int i = 0; i < 33; i++) tooMany.add(Json.fields("id", "x" + i, "style", Json.fields()));
        badSettings(f, token, Json.fields("looks", tooMany), "looks");
        badSettings(f, token, Json.fields("categories", tooMany), "categories");
        eq(before, f.settings.document(), "no candidate changes published");
        check(saved.equals(f.settingsJson), "no candidate persisted");
        eq(seq, f.events.latest(), "no candidate events");
        f.settings.put(at("device.name", "Updated"));
        eq(before.get("looks"), f.settings.document().get("looks"), "omitted looks unchanged");
        eq(before.get("categories"), f.settings.document().get("categories"), "omitted categories unchanged");
        f.settings.put(Json.fields("categories", List.of()));
        Map<String, Object> compat = Settings.object(f.settings.document().get("compat"), "compat");
        eq("general", Settings.object(compat.get("tvoverlay"), "tv").get("category"), "compat fallback");
    }

    static void mixReset() throws IOException {
        Fake f = new Fake();
        String[] mix = "glass classic classic alert pill glass glass glass".split(" ");
        List<?> rows = (List<?>) ok(f.route("GET", "categories", Json.fields())).get("categories");
        for (int i = 0; i < rows.size(); i++) {
            Map<String, Object> row = Settings.object(rows.get(i), "category");
            eq(mix[i], row.get("look"), "Mix");
            eq(false, row.get("announce"), "silent default");
            eq(Json.fields(), row.get("style"), "no overrides");
            eq(35, Settings.object(row.get("effective"), "effective").size(), "full style");
        }
        f.settings.putCategory("extra", Json.fields());
        check(!f.settings.categoryAnnounce("extra"), "custom defaults silent");
        eq("glass", f.settings.categories().get(8).get("look"), "custom default look");
        f.settings.putCategory("alarm", Json.fields("look", "pill", "announce", true, "style", Json.fields("size", "small")));
        f.settings.putCategory("alarm", Json.fields("style", Json.fields("duration", 30)));
        check(f.settings.categoryAnnounce("alarm"), "category opt-in");
        Map<String, Object> reset = Json.fields("look", "alert", "announce", false, "effective", false,
                "style", Json.fields("size", null, "duration", null));
        long seq = f.events.latest();
        ok(f.route("PUT", "categories/alarm", reset));
        eq(seq + 1, f.events.latest(), "one reset");
        Map<String, Object> alarm = f.settings.categories().get(3);
        eq("alert", alarm.get("look"), "default look restored");
        eq(false, alarm.get("announce"), "default announce restored");
        eq(Json.fields(), alarm.get("style"), "all overrides cleared");
        eq("large", f.settings.effective("alarm").get("size"), "inherited again");
        check(!f.settingsJson.contains("\"effective\""), "effective never stored");
    }

    static void chipLooks() throws IOException {
        Fake f = new Fake();
        f.settings.putLook("mine", at("style.background", "#ABCDEF"));
        f.settings.put(at("display.chip_look", "minimal"));
        for (String pos : "top_left top top_right bottom_left bottom bottom_right".split(" "))
            ok(f.route("PUT", "chips/placed", Json.fields("text", "Chip", "position", pos)));
        error(f.route("PUT", "chips/placed", Json.fields("text", "Chip", "position", "center")), 400, "bad chip");
        ok(f.route("PUT", "chips/placed", Json.fields("text", "Chip", "position", null, "look", null)));
        eq(null, f.chips.rows().get(0).get("look"), "inherit look");
        eq(null, f.chips.rows().get(0).get("position"), "inherit position");
        Map<String, Object> custom = Json.fields("id", "custom", "text", "Keep me", "position", "bottom",
                "look", "mine", "expires", 360, "priority", "high");
        ok(f.route("PUT", "chips", Json.fields("chips", List.of(custom, Json.fields("id", "second", "icon", "bell")))));
        List<Map<String, Object>> before = f.chips.rows();
        eq("custom", before.get(0).get("id"), "priority first");
        eq("bottom", before.get(0).get("position"), "position kept");
        eq("mine", before.get(0).get("look"), "name kept");
        eq("#ABCDEF", f.settings.chipVisual("mine").get("background"), "named look");
        f.settings.deleteLook("mine");
        error(f.route("PUT", "chips", Json.fields("chips", List.of(custom))), 400, "bad look");
        f.restart();
        eq(before, f.chips.rows(), "stale row, content, expiry and order preserved");
        eq(f.settings.chipVisual("minimal"), f.settings.chipVisual("mine"), "display fallback");
        f.settings.putLook("mine", at("style.corner", "large"));
        eq("large", f.settings.chipVisual((String) f.chips.rows().get(0).get("look")).get("corner"), "reattaches");
    }

    private static class Trace implements MqttTopics.Wire<RuntimeException> {
        final List<String> subscriptions = new ArrayList<>();
        final Map<String,String> retained = new LinkedHashMap<>();
        public void subscribe(String topic, int qos) {
            eq(1, qos, "command QoS");
            subscriptions.add(topic);
        }
        public void publish(String topic, String payload, int qos, boolean retain) {
            eq(topic.endsWith("/quiet") ? 0 : 1, qos, "retained QoS for " + topic);
            check(retain, "retained publication");
            retained.put(topic, payload);
        }
    }

    private static void mqttCadence() {
        long[] now = {0};
        MqttTopics topics = new MqttTopics("herald", "device", false, () -> now[0]);
        String state = topics.root + "state", quiet = topics.root + "quiet";
        String idle = "{\"sound_playing\":false}", playing = "{\"sound_playing\":true}";
        class TimedTrace extends Trace {
            boolean failQuiet;
            @Override public void publish(String topic, String payload, int qos, boolean retain) {
                if (topic.equals(quiet)) {
                    if (failQuiet) throw new IllegalStateException("quiet publish failed");
                    now[0] += 5; // Distinguish send time from completion time.
                }
                super.publish(topic, payload, qos, retain);
            }
            void expect(Map<String,String> sent) {
                eq(sent, retained, "publications at " + now[0]);
                retained.clear();
            }
        }
        TimedTrace trace = new TimedTrace();
        long initial = 3_000_000_000L;
        Map<String,String> initialTopics = Map.of(topics.root + "availability", "online", state, idle,
                quiet, "3000000000");
        topics.connected(trace, idle, initial);
        trace.expect(initialTopics);
        now[0] = 2000;
        topics.sample(trace, idle, initial + 2);
        trace.expect(Map.of());
        now[0] = 4000;
        topics.sample(trace, playing, 0);
        trace.expect(Map.of(state, playing));
        now[0] = 6000;
        topics.sample(trace, idle, 2);
        trace.expect(Map.of(state, idle));
        now[0] = 30_004;
        topics.sample(trace, idle, 26);
        trace.expect(Map.of());
        now[0] = 30_005;
        topics.sample(trace, idle, 27);
        trace.expect(Map.of(quiet, "27"));
        now[0] = 65_999;
        topics.sample(trace, idle, 27);
        trace.expect(Map.of());
        now[0] = 66_000;
        topics.sample(trace, idle, 27);
        trace.expect(Map.of(state, idle));

        trace.failQuiet = true;
        try {
            topics.sample(trace, idle, initial);
            throw new AssertionError("expected quiet publish failure");
        } catch (IllegalStateException expected) {
            eq("quiet publish failed", expected.getMessage(), "wire failure");
        }
        trace.failQuiet = false;
        topics.sample(trace, idle, initial);
        trace.expect(Map.of(quiet, "3000000000"));
        now[0] = 70_000;
        topics.connected(trace, idle, initial);
        trace.expect(initialTopics);
        now[0] = 100_004;
        topics.sample(trace, idle, 29);
        trace.expect(Map.of());
        now[0] = 100_005;
        topics.sample(trace, idle, 30);
        trace.expect(Map.of(quiet, "30"));
    }

    private static void mqttQuietDiscovery() {
        MqttTopics topics = new MqttTopics("house/custom", "device", true, () -> 0L);
        Map<String,String> configs = topics.configs("ha", "TV", "Box", "1");
        Map<String,Object> config = Json.parse(configs.get("ha/sensor/herald_device/quiet_seconds/config"));
        eq("house/custom/device/quiet", config.get("state_topic"), "quiet discovery topic");
        eq("{{ value }}", config.get("value_template"), "plain numeric payload");
        eq("s", config.get("unit_of_measurement"), "seconds");
    }

    private static void command(Fake f, String cmd, String text) {
        MqttTopics.run(cmd, text.getBytes(StandardCharsets.UTF_8), f, f.settings, f.cards, f.chips, f.requestIds);
    }

    private static void ntfyTests() throws Exception {
        Fake f = new Fake();
        String auth = f.issue("ntfy settings fixture");
        Settings s = f.settings;
        Map<String,Object> defaults = Json.fields("enabled", false, "server", "https://ntfy.sh",
                "catch_up", 120, "topics", List.of(), "token_set", false);
        eq(defaults, s.document().get("ntfy"), "ntfy defaults");
        Map<String,Object> a = Json.fields("topic", "a"), b = Json.fields("topic", "b"),
                c = Json.fields("topic", "c"), d = Json.fields("topic", "d");
        s.put(at("ntfy.topics", List.of(a, b, c, d)));
        badSettings(f, auth, at("ntfy.topics", List.of(a, b, c, d, a)), "ntfy.topics");
        badSettings(f, auth, at("ntfy.topics", List.of(a, a)), "ntfy.topics.1.topic");
        for (int n : new int[]{-1, 601}) badSettings(f, auth, at("ntfy.catch_up", n), "ntfy.catch_up");
        for (int n : new int[]{0, 600}) s.put(at("ntfy.catch_up", n));
        for (String name : List.of("", "a/b", "a".repeat(65)))
            badSettings(f, auth, at("ntfy.topics", List.of(Json.fields("topic", name))), "ntfy.topics.0.topic");
        badSettings(f, auth, at("ntfy.topics", List.of(Json.fields("topic", "a", "label", "😀".repeat(21)))), "ntfy.topics.0.label");
        Map<String,Object> custom = Json.fields("id", "local", "name", "Local", "style", Json.fields());
        a.put("look", "local");
        badSettings(f, auth, at("ntfy.topics", List.of(a)), "ntfy.topics.0.look");
        b.put("look", "local");
        c.put("look", "glass");
        s.put(Json.fields("looks", List.of(custom), "ntfy", Json.fields("topics", List.of(a,b,c))));
        int revision = s.revision();
        badSettings(f, auth, Json.fields("looks", List.of()), "ntfy.topics.0.look");
        eq(revision, s.revision(), "atomic rejection");
        eq("local", Settings.topics(s.ntfyPrivate()).get(0).get("look"), "retained reference");
        ok(f.request("DELETE", "/v1/looks/local", bearer(auth), ""));
        eq(revision + 1, s.revision(), "single delete revision");
        for (int i = 0; i < 3; i++) eq(i == 2 ? "glass" : null, Settings.topics(s.ntfyPrivate()).get(i).get("look"), "delete references");
        pass("ntfy: settings defaults, four-topic limit, unique names, catch_up bounds and topic look references (a missing look is rejected, deleting a look clears it to null)");

        String secret = java.util.UUID.randomUUID().toString();
        s.put(at("ntfy.token", secret));
        Map<String,Object> pub = Settings.object(ok(f.request("GET", "/v1/settings", bearer(auth), "")).get("ntfy"), "ntfy");
        check(Boolean.TRUE.equals(pub.get("token_set")) && !pub.containsKey("token") && !pub.containsKey("token_server")
                && !Json.stringify(pub).contains(secret), "private fields hidden");
        s.put(at("ntfy", Json.fields("token", "", "token_set", false)));
        f.restart();
        s = f.settings;
        check(secret.equals(s.ntfyPrivate().get("token")), "private value restored");
        s.put(at("ntfy.token", null));
        check("".equals(s.ntfyPrivate().get("token")), "clear");
        for (Object invalid : List.of(2, "two words", "a".repeat(257)))
            badSettings(f, auth, at("ntfy.token", invalid), "ntfy.token");
        pass("ntfy: the token is write-only: token_set on GET, empty keeps, null clears, and the private value survives a reload");

        String server = "https://notify.example";
        s.put(at("ntfy", Json.fields("server", server, "token", secret)));
        s.put(at("ntfy.server", "HTTPS://NOTIFY.EXAMPLE"));
        check(secret.equals(s.ntfyPrivate().get("token")), "canonical address keeps credential");
        s.put(at("ntfy", Json.fields("server", "https://other.example", "token", "")));
        check("".equals(s.ntfyPrivate().get("token")) && "".equals(s.ntfyPrivate().get("token_server")), "changed server clears both");
        s.put(at("ntfy", Json.fields("server", server, "token", secret)));
        check(secret.equals(s.ntfyPrivate().get("token")) && server.equals(s.ntfyPrivate().get("token_server")), "new binding");
        s.put(at("ntfy.server", "https://other.example"));
        f.restart();
        check("".equals(f.settings.ntfyPrivate().get("token")), "no credential follows a restore");
        pass("ntfy: changing the server clears the token unless a new token comes with it");

        Map<String,Object> old = Json.parse(f.settingsJson);
        old.remove("ntfy");
        Settings legacy = new Settings("TV", Json.stringify(old), json -> { throw new AssertionError("unexpected save"); }, f.events);
        eq(defaults, legacy.document().get("ntfy"), "schema 2 without group");
        pass("ntfy: a stored 0.2 settings document without ntfy loads with defaults");

        for (String good : List.of(server, "https://[::1]:8443/path", "http://10.0.0.1", "http://172.16.0.0",
                "http://172.31.255.255", "http://192.168.1.2:8080/base", "http://[fc00::1]", "http://[fdff::1]/base"))
            check(Ntfy.serverAllowed(good), "valid server");
        for (String bad : List.of("http://notify.example", "http://127.0.0.1", "http://172.32.0.0", "http://[fe80::1]",
                "http://010.1.1.1", "https://u@example.org", server + "/p/", server + "?", server + "#", "ftp://example.org", "//example.org"))
            check(!Ntfy.serverAllowed(bad), "rejected server");
        eq("https://notify.example/Prefix", Ntfy.canonical("HTTPS://Notify.Example/Prefix"), "canonical");
        pass("ntfy: server rule accepts https hosts and private http IP literals and refuses the rest");

        Map<String,Object> topic = Json.fields("topic", "events", "label", "Updates", "category", "general", "speak", false, "look", null);
        Map<String,Object> event = Json.fields("topic", "events", "event", "message", "id", "ev01", "time", 1000,
                "title", "A title", "message", "**plain text**");
        Settings mapping = legacy;
        java.util.function.Supplier<Map<String,Object>> card = () -> Ntfy.card(server, topic, event, 120, 1000, List.of(), mapping);
        for (int n = 0; n <= 5; n++) {
            if (n > 0) event.put("priority", n);
            eq(List.of("normal", "low", "low", "normal", "high", "high").get(n), card.get().get("priority"), "priority");
            eq(n == 5 ? "reminder" : "general", card.get().get("category"), "category");
        }
        topic.put("category", "alarm");
        eq("alarm", card.get().get("category"), "explicit category");
        eq(30, new CardSpec(card.get(), mapping).style.fields.get("duration"), "sticky duration capped");
        topic.put("category", "general");
        pass("ntfy: priority maps 1-2 low, 3 normal, 4 high, 5 high reminder");

        event.put("tags", List.of("unknown", "warning", "bell"));
        eq("emoji:⚠️", new CardSpec(card.get(), mapping).style.fields.get("icon"), "first recognized");
        event.put("tags", List.of("other"));
        eq("none", new CardSpec(card.get(), mapping).style.fields.get("icon"), "unknown icon");
        pass("ntfy: the first recognized tag sets an emoji: icon that CardSpec accepts, an unknown tag gives none");

        eq("A title", card.get().get("title"), "publisher title");
        eq("**plain text**", card.get().get("message"), "literal message");
        event.remove("title");
        eq("Updates", card.get().get("title"), "label fallback");
        topic.put("label", "");
        eq("Notification", card.get().get("title"), "default title");
        pass("ntfy: title falls back to the topic label, then Notification");

        event.put("expires", 999);
        eq(null, card.get(), "expired");
        event.remove("expires");
        eq(null, Ntfy.card(server, topic, event, 120, 1000, List.of("ev01"), mapping), "seen");
        pass("ntfy: expired and repeated messages are skipped");

        event.put("time", 1);
        check(Ntfy.card(server, topic, event, 0, 1000, List.of(), mapping) != null, "live has no age test");
        eq("", Ntfy.since(0, "ev01", 1, 1000), "live query omitted");
        pass("ntfy: catch_up 0 subscribes live without an age check");

        eq(null, card.get(), "too old");
        event.put("time", 880);
        check(card.get() != null, "inclusive window");
        pass("ntfy: with catch_up set, messages older than catch_up are skipped");

        eq(false, card.get().get("announce"), "silent by default");
        topic.put("speak", true);
        eq(Json.fields("timing", "now", "urgent", false, "chime", false), card.get().get("announce"), "opted in");
        event.put("priority", 4);
        eq(false, card.get().get("announce"), "lower priority silent");
        pass("ntfy: priority 5 speaks only when the topic opts in");

        eq("nt_ac5ae1acd97a02b183c3d181e3cbe276", card.get().get("id"), "hash tuple");
        pass("ntfy: card ids are nt_ plus 32 hex of the server, topic and id");

        eq("ev01", Ntfy.since(120, "ev01", 881, 1000), "recent cursor");
        eq("120s", Ntfy.since(120, "ev01", 880, 1000), "old cursor");
        eq("120s", Ntfy.since(120, "", 999, 1000), "no cursor");
        pass("ntfy: since uses the stored id inside catch_up and seconds outside it");

        String key = Ntfy.stateKey(server, "events");
        check(key.matches("[0-9a-f]{64}") && !key.equals(Ntfy.stateKey(server, "other"))
                && !key.equals(Ntfy.stateKey("https://other.example", "events")), "separate state");
        eq(key, Ntfy.stateKey("HTTPS://NOTIFY.EXAMPLE", "events"), "canonical state");
        pass("ntfy: state keys differ per server and topic");

        Ntfy.Cursor cursor = new Ntfy.Cursor("{}");
        for (int i = 0; i <= 256; i++) {
            event.put("id", "id" + i);
            cursor = new Ntfy.Cursor(cursor.advance("events", event));
        }
        eq(256, cursor.recent.size(), "persistent bounded history");
        check(!cursor.recent.contains("id0") && cursor.recent.contains("id1"), "oldest evicted");
        for (String type : List.of("open", "keepalive", "message")) {
            event.put("event", type);
            event.put("id", "ignored");
            eq(null, cursor.advance(type.equals("message") ? "another" : "events", event), "ignored event");
            eq("id256", cursor.id, "unchanged cursor");
        }
        for (Object bad : List.of("id/1", "", 5)) {
            event.put("id", bad);
            check(!Ntfy.handles("events", event), "invalid id");
        }
        event.put("id", "valid");
        event.put("time", 12.5);
        check(!Ntfy.handles("events", event), "fractional time");
        pass("ntfy: open and keepalive lines and other topics leave the cursor alone");

        for (int i = 0; i < 6; i++) {
            long base = List.of(5,10,20,40,60,60).get(i) * 1000L;
            eq(base, Ntfy.backoff(i, 0, 0), "retry base");
            eq(base * 12 / 10, Ntfy.backoff(i, 0, 1), "jitter bound");
        }
        for (int code : new int[]{401,403}) {
            eq(-1L, Ntfy.backoff(0, code, 0), "stop retrying");
            eq("unauthorized", Ntfy.httpState(code), "auth status");
        }
        eq(60000L, Ntfy.backoff(0, 429, 1), "rate limit");
        pass("ntfy: backoff runs 5 10 20 40 60 s with jitter and stops on 401 or 403");

        for (int code : new int[]{300,301,302,307,308,399}) {
            eq("error", Ntfy.httpState(code), "redirect state");
            eq(5000L, Ntfy.backoff(0, code, 0), "redirect retry");
        }
        pass("ntfy: a 3xx reply is an error with backoff");

        InputStream lines = new ByteArrayInputStream(bytes("a".repeat(16384) + "\n" + "b".repeat(16385) + "\nok\n"));
        eq(16384, Ntfy.readLine(lines).length(), "limit");
        eq("", Ntfy.readLine(lines), "over limit");
        eq("ok", Ntfy.readLine(lines), "next line");
        eq(null, Ntfy.readLine(lines), "EOF");
        eq("off", Ntfy.worst(List.of()), "no topics");
        eq("connecting", Ntfy.worst(List.of("connected", "connecting")), "waiting");
        eq("error", Ntfy.worst(List.of("error", "connecting")), "error dominates");
        eq("unauthorized", Ntfy.worst(List.of("connected", "unauthorized", "error")), "auth dominates");
        pass("ntfy: lines over 16384 bytes are dropped and status reports the worst topic state");

        String privateName = "topic-" + java.util.UUID.randomUUID(), privateBody = "body-" + java.util.UUID.randomUUID();
        String detail = secret + "/" + privateName + "/" + privateBody;
        String logged = Ntfy.logLine(3, "error", 403, new IOException(detail));
        eq("ntfy 3 error http 403 IOException", logged, "fixed fields");
        for (String privateValue : List.of(secret, privateName, privateBody, detail))
            check(!logged.contains(privateValue), "private text absent");
        eq("ntfy 1 error", Ntfy.logLine(1, detail, 0, null), "unknown event hidden");
        pass("ntfy: log lines carry the topic index and event but never the token, topic name, message text or exception message");
    }


    static void mqttTests() {
        Fake f = new Fake();
        MqttTopics topics = new MqttTopics("herald", "device", true, () -> f.now);
        String commands = topics.root + "cmd/";
        for (String name : List.of("card", "clear", "chip", "announce", "stop", "notify"))
            eq(name, topics.commandName(commands + name, false), "subscribed command " + name);
        eq(null, topics.commandName(commands + "unknown", false), "no such command");
        command(f, "card", "{\"id\":\"test\",\"message\":\"hello\"}");
        eq(1, f.cards.rows().size(), "posted");
        command(f, "clear", "{\"all\":true}");
        eq(0, f.cards.rows().size(), "cleared");
        command(f, "chip", "{\"id\":\"test\",\"text\":\"hello\"}");
        eq(1, f.chips.rows().size(), "chip stored");
        command(f, "announce", "{\"text\":\"hello\"}");
        eq(1, f.admissions, "announced");
        command(f, "stop", "any payload");
        check(!f.jobs.busy(), "stopped");
        command(f, "notify", "hello");
        eq(1, f.cards.rows().size(), "notified");
        pass("mqtt topics map each command to its API call");

        MqttTopics disabled = new MqttTopics("herald", "device", false, () -> f.now);
        Trace off = new Trace();
        disabled.connected(off, "{}", 0);
        check(off.subscriptions.isEmpty(), "commands off leaves no subscriptions");
        for (String name : MqttTopics.COMMANDS) eq(null, disabled.commandName(commands+name, false), "commands off");
        pass("mqtt commands are ignored when commands is off");

        Trace subscribe = new Trace();
        topics.connected(subscribe, "{}", 0);
        for (String topic : subscribe.subscriptions) {
            eq(null, topics.commandName(topic, true), "retained replay " + topic);
            check(topics.commandName(topic, false) != null, "fresh delivery");
        }
        for (String name : MqttTopics.COMMANDS) {
            int limit = name.equals("announce") ? 3145728 : name.equals("card") || name.equals("notify") ? 1572864 : 65536;
            failure(() -> MqttTopics.run(name, new byte[limit+1], f, f.settings, f.cards, f.chips, f.requestIds), 413, "body too large");
            // Exactly the route limit gets to its normal parser.
            if (name.equals("stop")) MqttTopics.run(name, new byte[limit], f, f.settings, f.cards, f.chips, f.requestIds);
            else failure(() -> MqttTopics.run(name, new byte[limit], f, f.settings, f.cards, f.chips, f.requestIds),
                    400, name.equals("notify") ? "message too long" : "bad json");
        }
        failure(() -> MqttTopics.run("notify", new byte[]{(byte)0xff}, f, f.settings, f.cards, f.chips, f.requestIds), 400, "bad json");
        pass("mqtt retained commands (also a retained replay on subscribe) are ignored and payloads keep the HTTP limits");

        for (int i = 0; i < 2; i++) {
            Trace session = new Trace();
            String state = "{\"cards_visible\":" + i + "}";
            topics.connected(session, state, i);
            eq(subscribe.subscriptions, session.subscriptions, "subscriptions on every connect");
            eq(6, session.subscriptions.size(), "one per command");
            eq(Map.of(topics.root+"availability","online",topics.root+"state",state, topics.root+"quiet",String.valueOf(i)), session.retained, "republished state");
        }
        pass("mqtt reconnect restores subscriptions, availability and state");

        String deviceId = "herald_device";
        Map<String,String> configs = topics.configs("homeassistant", "TV", "Box", "1");
        eq(7, configs.size(), "all discovery configs");
        for (String entity : List.of("notify/notify","binary_sensor/screen_on","binary_sensor/sound_playing",
                "binary_sensor/overlay_allowed","sensor/quiet_seconds","sensor/last_card","event/button")) {
            String[] ids = entity.split("/");
            Map<String,Object> config = Json.parse(configs.get("homeassistant/"+ids[0]+"/"+deviceId+"/"+ids[1]+"/config"));
            eq(deviceId+"_"+ids[1], config.get("unique_id"), "stable HA entity");
            eq(Map.of("identifiers",List.of(deviceId),"manufacturer","Herald","model","Box","name","TV","sw_version","1"),
                    config.get("device"), "HA device");
            eq(topics.root+"availability", config.get("availability_topic"), "availability");
            if (ids[1].equals("button")) eq(List.of("button","dismissed"),config.get("event_types"),"event types");
            if (ids[1].equals("overlay_allowed")) eq("diagnostic",config.get("entity_category"),"diagnostic");
        }
        eq(Json.fields("event_type","button","card","a","button","b"),
                MqttTopics.button(Json.fields("type","card.button","id","a","button","b")), "button payload");
        eq(Json.fields("event_type","dismissed","card","a"),
                MqttTopics.button(Json.fields("type","card.ended","reason","dismissed","id","a")), "dismiss payload");
        eq(null,MqttTopics.button(Json.fields("type","card.ended","reason","cleared")), "no synthetic dismissal");
        pass("mqtt discovery payloads hold the documented entities and device");

        MqttTopics moved = new MqttTopics("house/custom", "device", true, () -> f.now);
        String base = "house/custom/device/";
        Trace trace = new Trace();
        moved.connected(trace, "{}", 0);
        for (String cmd : MqttTopics.COMMANDS) check(trace.subscriptions.contains(base+"cmd/"+cmd), "custom subscription");
        for (String key : trace.retained.keySet()) check(key.startsWith(base), "custom publication");
        for (Map.Entry<String,String> item : moved.configs("new/prefix","TV","Box","1").entrySet()) {
            check(item.getKey().startsWith("new/prefix/"), "discovery prefix");
            Map<String,Object> info = Json.parse(item.getValue());
            for (String field : List.of("command_topic","availability_topic","state_topic"))
                if (info.containsKey(field)) check(((String)info.get(field)).startsWith(base), "discovery data topic");
        }
        for (String next : new String[]{"new/prefix", null}) {
            Trace clear = new Trace();
            moved.clearOld(clear, "homeassistant", next);
            eq(configs.keySet(), clear.retained.keySet(), "old config topics");
            check(clear.retained.values().stream().allMatch(String::isEmpty), "retained tombstones");
        }
        pass("mqtt base topic and discovery prefix are applied to every topic, and old discovery is cleared on change");

        Fake g = new Fake();
        command(g,"notify","{\"text\":\"literal\"}");
        CardSpec spec = g.cards.showing().get(0).spec;
        eq("{\"text\":\"literal\"}", spec.message,"literal UTF-8 text");
        eq("",spec.title,"no title");
        eq("general",spec.category,"general card");
        command(g,"notify","x".repeat(1000));
        failure(() -> command(g,"notify","x".repeat(1001)),400,"message too long");
        command(g,"clear",Json.stringify(Json.fields("id",spec.id)));
        eq(1,g.cards.rows().size(),"delete only selected card");
        command(g,"clear","{\"category\":\"general\"}");
        eq(0,g.cards.rows().size(),"category cleared");
        command(g,"clear","{\"all\":true}");
        failure(() -> command(g,"clear","{\"all\":true,\"id\":\"a\"}"),400,"bad json");
        command(g,"chip","{\"id\":\"x\",\"text\":\"one\",\"delete\":false}");
        failure(() -> command(g,"chip","{\"id\":\"x\",\"delete\":\"true\"}"),400,"bad json");
        eq(1,g.chips.rows().size(),"only literal true deletes");
        command(g,"chip","{\"id\":\"x\",\"delete\":true}");
        eq(0,g.chips.rows().size(),"chip deleted");
        pass("mqtt notify takes plain text, clear takes id, category or all, and chip delete needs delete true");

        eq(Map.of("id","uuid","api","2","version","1.2.3"),MqttTopics.txt("uuid","1.2.3"),"NSD TXT");
        pass("discovery txt holds id, api and version");
    }

    static void compatReply(Compat compat, String peer, InputStream request, int code, String message) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        compat.handle(peer, 5001, request, out);
        Reply reply = parseResponse(out);
        eq(code, reply.code, "compat status");
        eq(Json.fields("success", code == 200, "message", message), Json.parse(reply.body), "compat response");
    }

    static void compatPost(Compat compat, String path, Map<String,Object> doc, int code, String message) throws IOException {
        String body = Json.stringify(doc);
        compatReply(compat, "192.0.2.1", stream("POST " + path + " HTTP/1.1\r\nContent-Length: "
                + bytes(body).length + "\r\n\r\n" + body), code, message);
    }

    static void compatTests() throws Exception {
        Fake f = new Fake();
        String received = "Notification received";
        String fixed = "/notify_fixed";
        f.settings.putCategory("compat_test", Json.fields("style", Json.fields("video_sound", "on")));
        f.settings.put(at("compat.tvoverlay", Json.fields("enabled", true,
                "allowed", List.of("192.0.2.0/28","2001:db8::/64"), "category", "compat_test")));
        Compat compat = new Compat(f.settings, f.cards, f.chips, () -> f.now, f, f.requestIds);
        for (String peer : List.of("192.0.2.16", "2001:db8:0:1::1")) {
            Counting unread = new Counting(bytes("POST /notify HTTP/1.1\r\nContent-Length: 9999999\r\n\r\n"));
            compatReply(compat, peer, unread, 403, "sender not allowed");
            eq(0, unread.calls, "denial before even the first byte");
        }
        for (String peer : List.of("192.0.2.15", "2001:db8::ffff"))
            compatReply(compat, peer, stream("POST /set/overlay HTTP/1.1\r\n\r\n"), 200, "ignored by Herald");
        pass("compat refuses a sender not on the list before reading the body, for IPv4 and IPv6 ranges");

        String[][] corners = {{"top_start","top_left"}, {"top_end","top_right"},
                {"bottom_start","bottom_left"}, {"bottom_end","bottom_right"}};
        for (String[] corner : corners) {
            compatPost(compat, "/notify", Json.fields("id","door","title","Visitor","message","At the door",
                    "source","Bell","image","https://example.invalid/a","largeIcon","invalid!",
                    "video","rtsp://example.invalid/v","corner",corner[0],"duration",8), 200, received);
            CardSpec card = f.cards.showing().get(0).spec;
            eq("door", card.id, "card id");
            eq("Visitor", card.title, "card title");
            eq("At the door", card.message, "card message");
            eq("Bell", card.from, "source");
            eq("compat_test", card.category, "configured category");
            eq(corner[1], card.style.fields.get("placement"), "mapped corner");
            eq(8, card.style.fields.get("duration"), "duration");
            eq("https://example.invalid/a", card.image.get("url"), "preferred image");
            eq("rtsp://example.invalid/v", card.video.get("url"), "video URL");
            eq(true, card.video.get("tcp"), "compat RTSP uses TCP");
            check(!card.video.containsKey("muted") && !MediaState.muted(card), "category controls video audio");
            f.attach();
        }
        check(Json.stringify(f.since(0)).contains("card.updated"), "shared board emits updates");
        pass("compat maps notify fields, corners and duration to a card, leaving video muted to the category");

        compatPost(compat, "/notify", Json.fields("id","door","message","Hi","image","mdi:bell",
                "smallIcon","https://example.invalid/ignored"), 200, received);
        check(f.cards.showing().get(0).spec.image == null, "ignored icons create no image");
        compatPost(compat, "/notify", Json.fields("id","door","image","mdi:bell","largeIcon","AQI="), 200, received);
        check(Arrays.equals(new byte[]{1,2}, (byte[])f.cards.showing().get(0).spec.image.get("data")), "base64 fallback");
        pass("compat ignores mdi icons and smallIcon");

        compatPost(compat, fixed, Json.fields("id","bell","message","","icon","bell","iconColor","#AABBCC"), 200, received);
        eq(Json.fields("id","bell","text","","icon","bell","color","#AABBCC","priority","normal","expires_at",null,"look",null,"position",null),
                f.chips.rows().get(0), "empty text with an icon");
        compatPost(compat, fixed, Json.fields("id","bell","message",""), 400, "bad chip");
        compatPost(compat, fixed, Json.fields("id","bell","visible","false"), 400, "bad chip");
        eq(1, f.chips.rows().size(), "invalid requests preserve chip");
        compatPost(compat, fixed, Json.fields("id","bell","visible",false), 200, received);
        eq(0, f.chips.rows().size(), "explicit delete");
        pass("compat maps notify_fixed to a chip, empty message with an icon, and visible false deletes it");

        long now = f.now / 1000;
        Object[][] times = {{now + 61,61}, {999999999,604800}, {59,59}, {"1h2m3s",3723},
                {"0y0w1d1s",86401}, {"1y",604800}, {"999999999999999999999s",604800}, {0,0}};
        for (Object[] pair : times) {
            compatPost(compat, fixed, Json.fields("id","bell","message","Hi","expiration",pair[0]), 200, received);
            eq(pair[1].equals(0) ? null : Long.valueOf(now + (int)pair[1]), f.chips.rows().get(0).get("expires_at"), "expiry");
        }
        for (Object invalid : List.of("", "2s1m", "1s2s", "1", "1w!", 1.5, false))
            compatPost(compat, fixed, Json.fields("id","bell","message","Hi","expiration",invalid), 400, "bad chip");
        pass("compat parses expiration as epoch, seconds and duration string, capped at 604800");

        for (long past : new long[]{1000000000L, now - 1, now, -1}) {
            compatPost(compat, fixed, Json.fields("id","bell","message","Hi"), 200, received);
            compatPost(compat, fixed, Json.fields("id","bell","expiration",past), 200, received);
            eq(0, f.chips.rows().size(), "past expiration deletes");
        }
        pass("compat deletes a chip whose expiration is already past");

        for (String path : List.of("/notify", fixed, "/set/settings", "/unknown")) {
            int limit = path.startsWith("/notify") ? 1572864 : 65536;
            String start = "POST " + path + " HTTP/1.1\r\nContent-Length: ";
            String head = start + (limit+1) + "\r\n\r\n";
            Counting oversized = new Counting(bytes(head + "!"));
            compatReply(compat, "192.0.2.1", oversized, 413, "body too large");
            eq(head.length(), oversized.pos, "413 consumes only headers");
            String content = path.equals(fixed) ? "{\"id\":\"bell\",\"visible\":false}" : "{\"message\":\"ok\"}";
            String full = start + limit + "\r\n\r\n" + content + " ".repeat(limit - content.length());
            boolean unknown = path.equals("/unknown");
            compatReply(compat, "192.0.2.1", stream(full), unknown ? 404 : 200,
                    unknown ? "unknown path" : path.startsWith("/set/") ? "ignored by Herald" : received);
        }
        pass("compat keeps the byte limits per route");

        for (String path : List.of("overlay", "notifications", "settings", "mqtt"))
            compatReply(compat, "192.0.2.1", stream("POST /set/" + path + " HTTP/1.1\r\nContent-Length: 1\r\n\r\n!"),
                    200, "ignored by Herald");
        compatReply(compat, "192.0.2.1", stream("GET /notify HTTP/1.1\r\n\r\n"), 405, "method not allowed");
        pass("compat answers the set routes with success and ignored");

        for (String path : List.of("/set/other","/v1/status","/"))
            compatPost(compat, path, Json.fields(), 404, "unknown path");
        pass("compat answers unknown paths with 404 in TvOverlay shape");
    }

    static void soundTransitions() {
        SoundDecision s = new SoundDecision(0);
        check(!s.playing(), "starts off");
        check(!s.sample(true, 0) && !s.sample(false, 2000), "an interrupted positive run is discarded");
        check(!s.sample(true, 4000) && s.sample(true, 6000) && s.playing(), "second consecutive true turns on");
        check(!s.sample(false, 8000) && !s.sample(false, 10000) && !s.sample(true, 12000), "still playing");
        check(!s.sample(false, 14000) && !s.sample(false, 16000), "two negatives after the reset");
        check(s.sample(false, 18000) && !s.playing() && !s.sample(false, 20000), "one falling transition");
    }

    static void soundOwnJob() {
        SoundDecision s = new SoundDecision(0);
        s.sample(true, 1000);
        s.ownJob(true, 1100);
        check(!s.sample(true, 2000) && !s.sample(false, 4000), "own samples ignored");
        s.ownJob(false, 4500);
        s.ownJob(false, 5000);
        check(!s.sample(true, 5499) && !s.playing(), "grace excludes true");
        check(s.sample(true, 5500) && s.playing(), "first eligible sample resumes the prior streak");
        s.ownJob(true, 6000);
        for (int time = 6000; time <= 10000; time += 2000)
            check(!s.sample(false, time) && s.playing(), "own samples cannot turn off an active signal");
    }

    static void soundQuietSeconds() {
        SoundDecision s = new SoundDecision(8000);
        eq(3L, s.quietSeconds(11999), "initial quiet uses service age in whole seconds");
        s.sample(true, 12000);
        eq(0L, s.quietSeconds(12999), "first positive resets quiet before turning on");
        s.sample(true, 14000);
        s.sample(true, 16000);
        s.sample(false, 18000);
        s.ownJob(true, 18100);
        s.sample(true, 20000);
        s.ownJob(false, 20100);
        s.sample(true, 21000);
        eq(5L, s.quietSeconds(21000), "ignored positives leave the last eligible true intact");
    }

    static void soundPlayers() {
        for (long first : new long[] {7, 8}) {
            long last = first == 7 ? 8 : 7;
            SoundDecision s = new SoundDecision(0);
            s.sample(true, 0);
            s.ownVideoAudio(7, true, 100);
            s.ownVideoAudio(8, true, 200);
            s.ownVideoAudio(last, true, 300);
            s.ownVideoAudio(first, false, 500);
            s.ownVideoAudio(first, false, 1000);
            check(!s.sample(true, 2000), "remaining generation owns audio");
            s.ownVideoAudio(last, false, 2100);
            s.ownVideoAudio(last, false, 2800);
            check(!s.sample(true, 3099) && s.sample(true, 3100), "only the last valid stop starts grace");
        }
        for (boolean jobLast : new boolean[] {false, true}) {
            SoundDecision s = new SoundDecision(0);
            s.sample(true, 0);
            s.ownJob(true, 100);
            s.ownVideoAudio(7, true, 200);
            if (jobLast) s.ownVideoAudio(7, false, 300);
            else s.ownJob(false, 300);
            check(!s.sample(true, 2000), "overlapping job and player suppress samples");
            if (jobLast) s.ownJob(false, 3000);
            else s.ownVideoAudio(7, false, 3000);
            check(!s.sample(true, 3999) && s.sample(true, 4000), "last source starts grace");
        }
    }

    // ---- http ----

    static void httpNoBody() throws IOException {
        ByteArrayInputStream in = stream("GET /status HTTP/1.1\r\nHost: 192.168.30.90:5002\r\nAccept: */*\r\n\r\nNEXT");
        Http.Request r = Http.readHead(in);
        eq("GET", r.method, "method");
        eq("/status", r.target, "target");
        eq("HTTP/1.1", r.version, "version");
        eq(0L, r.contentLength, "no Content-Length is 0");
        eq(4, in.available(), "nothing after the empty line is read");
        r = Http.readHead(stream("POST /stop HTTP/1.0\ncontent-length: 0\n\n"));
        eq("POST", r.method, "bare LF lines");
        eq(0L, r.contentLength, "lower-case header name");
    }

    static void httpBodyInPieces() throws IOException {
        Pieces in = new Pieces(bytes("PUT /clip/a HTTP/1.1\r\nContent-Length: 10\r\n\r\n0123456789"), 3);
        Http.Request r = Http.readHead(in);
        eq(10L, r.contentLength, "Content-Length");
        eq("0123456789", new String(Http.readBody(in, 10), StandardCharsets.UTF_8), "body");
        check(in.calls > 4, "the body came in several reads");
        Pieces shortBody = new Pieces(bytes("PUT /clip/a HTTP/1.1\r\nContent-Length: 10\r\n\r\n0123"), 2);
        Http.Request s = Http.readHead(shortBody);
        badRequest(() -> Http.readBody(shortBody, (int) s.contentLength), "a body that ends early");
    }

    static void httpHeadLimit() throws IOException {
        String prefix = "GET /status HTTP/1.1\r\nX-Pad: ";
        String suffix = "\r\n\r\n";
        int pad = Http.MAX_HEAD - prefix.length() - suffix.length();
        String exact = prefix + "p".repeat(pad) + suffix;
        eq(8192, exact.length(), "test head length");
        eq("GET", Http.readHead(stream(exact)).method, "a header block of exactly 8192 bytes is read");
        Counting over = new Counting(bytes(prefix + "p".repeat(pad + 1) + suffix + "more"));
        badRequest(() -> Http.readHead(over), "a header block of 8193 bytes");
        eq(8192L, over.bytes, "no more than 8192 bytes are read");
        badRequest(() -> Http.readHead(stream("GET /status HTTP/1.1\r\nHost: x\r\n")), "a stream that ends inside the head");
    }

    static void httpBadRequestLine() {
        for (String line : new String[] {"GET /status", "GET  /status HTTP/1.1", "GET /a b HTTP/1.1",
                " GET /status HTTP/1.1", "GET /status HTTP/1.1 ", "", "GET"}) {
            badRequest(() -> Http.readHead(stream(line + "\r\n\r\n")), "request line [" + line + "]");
        }
        for (String header : new String[] {"no colon here", ": empty name", "Bad Name: x", " folded: x"}) {
            badRequest(() -> Http.readHead(stream("GET /status HTTP/1.1\r\n" + header + "\r\n\r\n")),
                    "header line [" + header + "]");
        }
    }

    static void httpBadContentLength() throws IOException {
        for (String v : new String[] {"-1", "abc", "1.5", "", "+5", "5 5", "0x10", "1e3", "5,5"}) {
            badRequest(() -> Http.readHead(stream("PUT /clip/a HTTP/1.1\r\nContent-Length: " + v + "\r\n\r\n")),
                    "Content-Length [" + v + "]");
        }
        badRequest(() -> Http.readHead(stream("PUT /clip/a HTTP/1.1\r\nContent-Length: 1\r\nContent-Length: 2\r\n\r\n")),
                "two different Content-Length headers");
        eq(7L, Http.readHead(stream("PUT /clip/a HTTP/1.1\r\nContent-Length: 7\r\nContent-Length: 7\r\n\r\n")).contentLength,
                "the same Content-Length twice");
        eq(42L, Http.readHead(stream("PUT /clip/a HTTP/1.1\r\nContent-Length:  42 \r\n\r\n")).contentLength, "spaces around");
        eq(7L, Http.readHead(stream("PUT /clip/a HTTP/1.1\r\nContent-Length: 007\r\n\r\n")).contentLength, "leading zeros");
        eq(Long.MAX_VALUE, Http.readHead(stream("PUT /clip/a HTTP/1.1\r\nContent-Length: 99999999999999999999\r\n\r\n")).contentLength,
                "a huge length stays huge");
    }

    static void httpWrite() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Http.write(out, 200, OK);
        eq("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: 11\r\nConnection: close\r\n\r\n{\"ok\":true}",
                out.toString(StandardCharsets.ISO_8859_1), "200 response");
        out.reset();
        Http.write(out, 404, "{\"e\":\"\u00e9\"}");
        byte[] want = concat(bytes("HTTP/1.1 404 Not Found\r\nContent-Type: application/json\r\nContent-Length: 10\r\nConnection: close\r\n\r\n"),
                "{\"e\":\"\u00e9\"}".getBytes(StandardCharsets.UTF_8));
        check(Arrays.equals(want, out.toByteArray()), "404 response with a UTF-8 body counts bytes");
        for (int code : new int[] {400, 403, 405, 409, 413, 429, 500, 503}) {
            out.reset();
            Http.write(out, code, OK);
            check(out.toString(StandardCharsets.ISO_8859_1).startsWith("HTTP/1.1 " + code + " " + Http.reason(code) + "\r\n"),
                    "status line " + code);
            check(!Http.reason(code).equals("Status"), "reason phrase for " + code);
        }
    }

    // ---- json ----

    static void jsonReads() {
        Map<String, Object> m = Json.parse(" {\n \"s\" : \"q\\\" b\\\\ s\\/ \\b\\f\\n\\r\\t \\u00e9\\ud83d\\ude00\","
                + "\"n\":-1.5e2, \"i\":0, \"f\":0.25, \"e\":1E+2, \"t\":true, \"no\":false, \"z\":null, \"empty\":\"\" } ");
        eq("q\" b\\ s/ \b\f\n\r\t \u00e9\ud83d\ude00", m.get("s"), "escapes");
        eq(-150.0, m.get("n"), "negative exponent number");
        eq(0.0, m.get("i"), "zero");
        eq(0.25, m.get("f"), "fraction");
        eq(100.0, m.get("e"), "exponent with sign");
        eq(Boolean.TRUE, m.get("t"), "true");
        eq(Boolean.FALSE, m.get("no"), "false");
        check(m.containsKey("z") && m.get("z") == null, "null");
        eq("", m.get("empty"), "empty string");
        eq(9, m.size(), "all keys");
        eq(0, Json.parse("{}").size(), "empty object");
        eq("\"a\\\"b\\\\c\\n\\t\\u0001\\u00e9\"", Json.quote("a\"b\\c\n\t\u0001\u00e9"), "quote");
        String tricky = "line\nnext \"quoted\" back\\slash \u0000 \u001f caf\u00e9 \ud83d\ude00 /";
        eq(tricky, Json.parse("{\"k\":" + Json.quote(tricky) + "}").get("k"), "quote then parse");
    }

    static void jsonBroken() {
        for (String s : new String[] {"{\"a\":" + "[".repeat(16) + "]".repeat(16) + "}", "[1]", "{\"a\":1} x", "{\"a\":1}{}", "{\"a\":1,}",
                "{\"a\"}", "{a:1}", "{\"a\":tru}", "{\"a\":\"\\x\"}", "{\"a\":\"\\u12\"}", "{\"a\":\"\\u12g4\"}",
                "{\"a\":01}", "{\"a\":1.}", "{\"a\":-}", "{\"a\":.5}", "{\"a\":1e}", "\"a\"", "", "   ",
                "{\"a\":\"open", "{\"a\":\"ctrl\u0001\"}", "{\"a\" 1}", "{,}", "{\"a\":1 \"b\":2}", "nul",
                "{\"a\":nul}", "{\"a\":}", "{", "{\"a\":1", "{'a':1}", "{\"a\":+1}"}) {
            try {
                Json.parse(s);
            } catch (IllegalArgumentException e) {
                continue;
            }
            throw new AssertionError("json accepted [" + s + "]");
        }
        try {
            Json.parse(null);
            throw new AssertionError("json accepted null");
        } catch (IllegalArgumentException e) {
            // expected
        }
    }

    // ---- store ----

    static void storeKeys(File dir) throws IOException {
        ClipStore s = new ClipStore(dir, 10);
        for (String k : new String[] {"a", "A-z_9", "abc.wav", "_x", "-x", "a..", "x".repeat(160),
                "5b3c1e8f0c6a4d2e9f7b_en-us_8a1b2c3d4e_tts.piper.wav"}) {
            check(ClipStore.validKey(k), "good key " + k);
            eq(-1L, s.size(k), "size of an absent clip");
        }
        String[] bad = {"", "x".repeat(161), ".a", "a/b", "..", "a b", ".", "a\\b", "a%2Fb", "\u00e9", "a\n", null};
        for (String k : bad) {
            check(!ClipStore.validKey(k), "bad key " + k);
            rejects(() -> s.size(k), "size " + k);
            rejects(() -> s.touch(k), "touch " + k);
            rejects(() -> s.file(k), "file " + k);
            rejects(() -> {
                try {
                    s.put(k, new byte[] {1});
                } catch (IOException e) {
                    throw new AssertionError(e);
                }
            }, "put " + k);
        }
        check(!dir.exists() || dir.list().length == 0, "nothing was written for bad keys");
    }

    static void storePutSize(File dir) throws IOException {
        ClipStore s = new ClipStore(dir, 10);
        eq(0, s.count(), "empty store");
        s.put("k.wav", new byte[] {1, 2, 3, 4, 5});
        eq(5L, s.size("k.wav"), "size after put");
        eq(-1L, s.size("other"), "size of another key");
        eq(new File(dir, "k.wav"), s.file("k.wav"), "file");
        check(Arrays.equals(new byte[] {1, 2, 3, 4, 5}, Files.readAllBytes(s.file("k.wav").toPath())), "content");
        eq(1, s.count(), "count");
    }

    static void storeReplace(File dir) throws IOException {
        ClipStore s = new ClipStore(dir, 10);
        s.put("k", new byte[] {1, 2, 3, 4, 5});
        s.put("k", new byte[] {9, 8, 7});
        eq(3L, s.size("k"), "size after replacing");
        check(Arrays.equals(new byte[] {9, 8, 7}, Files.readAllBytes(s.file("k").toPath())), "replaced content");
        eq(List.of("k"), sorted(dir.list()), "only the clip is left in the directory");
        eq(1, s.count(), "count");
    }

    static void storePrune(File dir) throws IOException {
        ClipStore s = new ClipStore(dir, 3);
        long now = System.currentTimeMillis();
        s.put("a", new byte[] {1});
        s.put("b", new byte[] {1});
        s.put("c", new byte[] {1});
        age(s, "a", now - 3000000);
        age(s, "b", now - 2000000);
        age(s, "c", now - 1000000);
        File stale = new File(dir, "x" + ClipStore.TMP + "1");
        File fresh = new File(dir, "y" + ClipStore.TMP + "2");
        Files.write(stale.toPath(), new byte[] {1});
        Files.write(fresh.toPath(), new byte[] {1});
        check(stale.setLastModified(now - 120000), "age the stale temporary file");
        eq(3, s.count(), "temporary files are not clips");
        s.put("d", new byte[] {1});
        eq(List.of("b", "c", "d", fresh.getName()), sorted(dir.list()), "the oldest clip and the stale temporary file are gone");
        eq(3, s.count(), "count is the cap");
        age(s, "d", now - 4000000);
        s.put("e", new byte[] {1});
        eq(List.of("b", "c", "e", fresh.getName()), sorted(dir.list()), "age decides, not the order of puts");
        age(s, "e", now - 5000000);
        s.put("e", new byte[] {2});
        eq(List.of("b", "c", "e", fresh.getName()), sorted(dir.list()), "replacing a clip keeps the others");
        eq(3, s.count(), "count after replacing");
    }

    static void storeTouch(File dir) throws IOException {
        ClipStore s = new ClipStore(dir, 3);
        long now = System.currentTimeMillis();
        s.put("a", new byte[] {1});
        s.put("b", new byte[] {1});
        s.put("c", new byte[] {1});
        age(s, "a", now - 3000000);
        age(s, "b", now - 2000000);
        age(s, "c", now - 1000000);
        s.touch("a");
        check(s.file("a").lastModified() >= now - 1000, "touch sets last-modified to now");
        s.put("d", new byte[] {1});
        eq(List.of("a", "c", "d"), sorted(dir.list()), "the played clip stays, the oldest other goes");
        s.touch("missing");
        eq(-1L, s.size("missing"), "touching an absent clip creates nothing");
    }

    // ---- chime ----

    static void chimeLength(short[] pcm) {
        eq((int) (22050 * 0.32) + (int) (22050 * 0.75), pcm.length, "samples");
        eq((int) (44100 * 0.32) + (int) (44100 * 0.75), Chime.pcm(44100).length, "samples at 44100");
    }

    static void chimePeak(short[] pcm) {
        int peak = 0;
        for (short v : pcm) peak = Math.max(peak, Math.abs(v));
        check(peak >= 0.24 * 32767 && peak <= 0.26 * 32767, "peak " + peak);
        eq((short) 0, pcm[0], "first sample");
        eq((short) 0, pcm[(int) (22050 * 0.32)], "first sample of the second note");
    }

    static void chimeEnd(short[] pcm) {
        int lastSample = Math.abs(pcm[pcm.length - 1]);
        check(lastSample < 0.02 * 32767, "last sample " + lastSample);
    }

    static TlsIdentity fixtureIdentity;

    // Serve exactly one connection. Surface exceptions from the server thread to the test.
    static byte[] exchange(ServerSocket server, Socket client, Fake f, String request) throws Exception {
        FutureTask<Void> served = new FutureTask<>(() -> {
            try (Socket socket = server.accept()) {
                socket.setSoTimeout(3000);
                if (socket instanceof SSLSocket) {
                    if (TlsIdentity.handshake((SSLSocket) socket, f.failures))
                        f.api.handle(socket.getInputStream(), socket.getOutputStream());
                } else {
                    Compat compat = new Compat(f.settings, f.cards, f.chips, () -> f.now, f, f.requestIds);
                    compat.handle(socket.getInetAddress().getHostAddress(), socket.getLocalPort(),
                            socket.getInputStream(), socket.getOutputStream());
                }
            }
            return null;
        });
        new Thread(served, "fixture-listener").start();
        try (Socket c = client) {
            c.setSoTimeout(5000);
            c.getOutputStream().write(bytes(request + "\r\n\r\n"));
            return c.getInputStream().readAllBytes();
        } finally {
            served.get(5, TimeUnit.SECONDS);
        }
    }

    static Map<String,Object> tlsGet(SSLContext client, ServerSocket server, Fake f, String request) throws Exception {
        Socket socket = client.getSocketFactory().createSocket(server.getInetAddress(), server.getLocalPort());
        ByteArrayOutputStream reply = new ByteArrayOutputStream();
        reply.write(exchange(server, socket, f, request));
        return ok(parseResponse(reply));
    }

    static void tlsTests() throws Exception {
        char[] password = "herald-test".toCharArray();
        KeyStore store = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(new File(System.getProperty("herald.tls.fixture")).toPath())) {
            store.load(in, password);
        }
        fixtureIdentity = new TlsIdentity(store, "test", password);
        Certificate cert = store.getCertificate("test");
        KeyStore trust = KeyStore.getInstance("PKCS12");
        trust.load(null, null);
        trust.setCertificateEntry("peer", cert);
        TrustManagerFactory managers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        managers.init(trust);
        SSLContext client = SSLContext.getInstance("TLS");
        client.init(null, managers.getTrustManagers(), null);
        InetAddress addr = InetAddress.getByName("127.0.0.1");
        Fake f = new Fake();
        try (SSLServerSocket server = (SSLServerSocket) fixtureIdentity.sslContext().getServerSocketFactory()
                    .createServerSocket(0, 1, addr);
                ServerSocket compat = new ServerSocket(0, 1, addr)) {
            TlsIdentity.configure(server);
            Map<String,Object> info = tlsGet(client, server, f, "GET /v1/info HTTP/1.1");
            eq(2.0, info.get("api"), "api");
            eq(true, info.get("tls"), "tls");
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            String hex = String.format("%064x", new java.math.BigInteger(1, sha.digest(cert.getEncoded())));
            eq(hex, info.get("cert_sha256"), "cert digest");
            eq(fixtureIdentity.pin(), info.get("pin_sha256"), "info pin");
            pass("tls: info over TLS reports api 2, tls true, the fixture fingerprint and pin");
            eq(Base64.getEncoder().encodeToString(sha.digest(cert.getPublicKey().getEncoded())),
                    info.get("pin_sha256"), "SPKI digest");
            pass("tls: pin_sha256 is the base64 SHA-256 of the public key");

            byte[] rejected = exchange(server, new Socket(addr, server.getLocalPort()), f, "GET /v1/info HTTP/1.1");
            check(!new String(rejected, StandardCharsets.ISO_8859_1).contains("HTTP/"), "plaintext rejected");
            pass("tls: a plain HTTP request on the TLS port gets no HTTP reply");
            f.settings.put(at("compat.tvoverlay", Json.fields("enabled", true,
                    "port", compat.getLocalPort(), "allowed", List.of("127.0.0.1"))));
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            out.write(exchange(compat, new Socket(addr, compat.getLocalPort()), f, "POST /set/overlay HTTP/1.1"));
            eq(true, Json.parse(parseResponse(out).body).get("success"), "plain compat");
            pass("tls: a plain compat request still works while the native port is TLS");
            eq("AB12 CD34 EF56 7890", TlsIdentity.grouped("ab12cd34ef567890" + hex.substring(16)), "TV groups");
            pass("tls: the fingerprint shows as four groups of four upper-case hex digits");
            Map<String,Object> status = tlsGet(client, server, f, "GET /v1/status HTTP/1.1\r\n"
                    + bearer(f.issue("TLS fixture")).trim());
            eq(2.0, status.get("api"), "status api");
            eq(1.0, status.get("tls_failures"), "closed handshake counted once");
            pass("tls: a failed handshake closes the socket and counts tls_failures");
            eq("2", MqttTopics.txt("box", "fixture").get("api"), "TXT version");
            pass("tls: DNS-SD TXT advertises api 2");
        }
    }

    static final class Fake implements Backend {
        final AtomicInteger failures = new AtomicInteger();
        public String certSha256() { return fixtureIdentity.fingerprint(); }
        public String pinSha256() { return fixtureIdentity.pin(); }
        public int tlsFailures() { return failures.get(); }
        boolean enabled = true, screen = true, overlay = true, show = true;
        long now = 1800000000123L;
        String saved, displayed, shownName, paired;
        final SecureRandom random = new SecureRandom();
        EventLog events;
        Auth auth;
        Api api;
        String settingsJson;
        Settings settings;
        int reboundPort;
        final AudioQueue<AnnounceSpec> jobs = new AudioQueue<>();
        RequestIds requestIds;
        ClipStore clips;
        String lastAudio = "none";
        boolean hasOutput = true, ready = true;
        AnnounceSpec accepted;
        int admissions;
        final Object admission = new Object();
        final List<SpokenCards.JobCard> spokenJobs = new ArrayList<>();
        Runnable checking = () -> {}, attaching = () -> {};
        boolean quiet, failPreparation;

        Map<String,Object> card(Object... fields) {
            Map<String,Object> doc = Json.fields("title", "fixture");
            doc.putAll(Json.fields(fields));
            return SpokenCards.post(new CardSpec(doc, settings), doc.containsKey("announce"), doc.get("announce"),
                    settings, this, cards, requestIds);
        }

        public Object admissionLock() { return admission; }
        public Object checkSpeech(AnnounceSpec spec, boolean full) {
            check(Thread.holdsLock(cards) && Thread.holdsLock(admission), "board then admission held");
            checking.run();
            spec.checkOutput(key -> clipSize(key) >= 0, out -> hasOutput, false, AnnounceSpec.audioSettings(settings), () -> ready);
            if (quiet) throw new Api.Failure(409, "quiet hours");
            if (full) throw new Api.Failure(429, "too many request ids");
            if (jobs.full()) throw new Api.Failure(429, "busy");
            return spec;
        }
        public void queueSpeech(Object checked, String id, CardSpec owned, long generation) {
            check(Thread.holdsLock(cards) && Thread.holdsLock(admission), "apply under both locks");
            accepted = (AnnounceSpec)checked;
            jobs.add(accepted);
            spokenJobs.add(new SpokenCards.JobCard(owned, generation));
            admissions++;
        }

        void playSpoken(int i, int duration, boolean startFails) {
            SpokenCards.JobCard job = spokenJobs.get(i);
            AnnounceSpec playing = jobs.start();
            if (!failPreparation && (!playing.chime || !startFails)) job.show(cards, duration);
            job.end(cards, failPreparation || startFails ? "error" : "played");
            jobs.finished();
        }
        final File directory = new File(testRoot, java.util.UUID.randomUUID().toString());
        CardBoard cards;
        ChipBoard chips;
        ImageStore images;
        boolean playing;
        final List<CardSpec> imageAttempts = new ArrayList<>();
        int sounds;
        String token;

        Map<String,Object> post(String id, Object... patch) {
            Map<String,Object> doc = Json.fields("id", id, "message", "fixture");
            doc.putAll(Json.fields(patch));
            return cards.post(new CardSpec(doc, settings));
        }

        void attach() {
            for (CardBoard.Entry e : cards.showing()) cards.attached(e.spec, now);
        }

        Reply route(String method, String resource, Map<String,Object> doc) throws IOException {
            if (token == null) token = issue("test");
            return settingsRequest(method, "/v1/" + resource, token, doc);
        }

        List<?> since(long cursor) { return (List<?>) events.await(cursor, 0).get("events"); }

        Reply settingsRequest(String method, String path, String token, Map<String, Object> patch) throws IOException {
            return request(method, path, bearer(token), Json.stringify(patch));
        }

        Fake() { restart(); }

        void restart() {
            spokenJobs.clear();
            jobs.stop(job -> {});
            jobs.finished();
            events = new EventLog(() -> now, random);
            auth = Auth.fromJson(saved, this, events, () -> now, random);
            settings = new Settings("Living Room", settingsJson, json -> settingsJson = json, events);
            directory.mkdirs();
            requestIds = new RequestIds(new File(directory, "request-ids.json"), () -> now);
            clips = new ClipStore(new File(directory, "clips"), 200);
            chips = new ChipBoard(new File(directory, "chips.json"), () -> now, settings::hasLook);
            images = new ImageStore(new File(directory, "images"), data -> {
                try { return javax.imageio.ImageIO.read(new ByteArrayInputStream(data)) != null; }
                catch (IOException invalid) { return false; }
            });
            cards = new CardBoard(settings, events, this, new CardBoard.Display() {
                public void changed() {}
                public void loadImage(CardSpec spec) {
                    imageAttempts.add(spec);
                    try {
                        images.load(spec, url -> { throw new IOException("fixture offline"); }, bytes -> {
                            try { return javax.imageio.ImageIO.read(new ByteArrayInputStream(bytes)); }
                            catch (IOException invalid) { return null; }
                        });
                    } catch (IOException | Api.Failure e) { cards.media(spec, "failed"); }
                }
            }, () -> now);
            api = new Api(this, auth, events, settings, (revision, port) -> reboundPort = port,
                    "device", "model", "0.1.0", cards, chips, images, requestIds);
        }

        Reply request(String method, String path, String headers, String body) throws IOException {
            return run(api, stream(method + " " + path + " HTTP/1.1\r\n" + headers
                    + "Content-Length: " + bytes(body).length + "\r\n\r\n" + body));
        }

        Reply start(String client) throws IOException {
            return request("POST", "/v1/pair/start", "", Json.stringify(Json.fields("client", client)));
        }

        Reply finish(String client, String code) throws IOException {
            return request("POST", "/v1/pair/finish", "", Json.stringify(Json.fields("client", client, "code", code)));
        }

        String issue(String name) throws IOException {
            eq(200, start(name).code, "start");
            return (String) ok(finish(name, displayed)).get("token");
        }

        public Map<String, Object> statusFields() {
            return Json.fields("battery_exempt", true, "tts_ready", false, "outputs", List.of("speaker"),
                    "audio_busy", jobs.busy(), "last_audio", lastAudio, "temp_files", 0, "clips", clips.count());
        }
        public boolean soundPlaying() { return playing; }
        final List<String> videoAudio = new ArrayList<>();
        public void ownVideoAudio(long generation, boolean playing) { videoAudio.add(generation + ":" + playing); }
        public void testChime(int volume) { announce(AnnounceSpec.cardSound("chime", volume)); }
        public void playCardSound(String sound, int volume) {
            attaching.run();
            synchronized (admission) { sounds++; }
        }
        public boolean screenOn() { return screen; }
        public boolean overlayAllowed() { return overlay; }
        public boolean pairingAllowed() { return enabled && settings.pairingAllowed(); }
        public boolean showPairingCode(String client, String code, int seconds) {
            if (!show) return false;
            eq(120, seconds, "overlay lifetime");
            displayed = code;
            shownName = client;
            return true;
        }
        public void hidePairingCode() { displayed = null; }
        public void showPaired(String name) { paired = name; }
        public void persistAuth(String json) { saved = json; }
        public long clipSize(String key) { return clips.size(key); }
        public boolean putClip(String key, byte[] body) {
            try { clips.put(key, body); return true; } catch (IOException e) { return false; }
        }
        public Map<String,Object> announce(AnnounceSpec spec) {
            return requestIds.admit(spec.requestId, () -> spec.checkOutput(key -> clipSize(key) >= 0,
                    out -> hasOutput, false, AnnounceSpec.audioSettings(settings), () -> ready), jobs::full, id -> {
                        accepted = spec;
                        jobs.add(spec);
                        admissions++;
                    });
        }
        public void stop() {
            for (SpokenCards.JobCard job : spokenJobs) job.end(cards, "stopped");
            spokenJobs.clear();
            jobs.stop(job -> job.endCard(cards, "stopped"));
            jobs.finished();
        }
    }

    static Map<String, Object> ok(Reply r) {
        eq(200, r.code, "success");
        return Json.parse(r.body);
    }

    static void failure(Runnable action, int status, String message) {
        try {
            action.run();
        } catch (Api.Failure e) {
            eq(status, e.status, "failure status");
            eq(message, e.getMessage(), "failure reason");
            return;
        }
        throw new AssertionError("expected " + status);
    }

    static String bearer(String token) { return "Authorization: Bearer " + token + "\r\n"; }

    static void jsonNested() {
        String value = "{\"k\":" + "[".repeat(15) + "true" + "]".repeat(15) + "}";
        Object leaf = Json.parse(value).get("k");
        for (int i = 0; i < 15; i++) leaf = ((List<?>) leaf).get(0);
        eq(true, leaf, "depth 16");
        eq(value, Json.stringify(Json.parse(value)), "depth 16 serialization");
    }

    static void jsonRoundTrip() {
        Map<String, Object> original = Json.fields("nested", List.of(Json.fields("quote\"", "x\\", "n", 2.5)),
                "array", Arrays.asList(false, null, List.of()));
        eq(original, Json.parse(Json.stringify(original)), "nested round trip");
        Object list = original.get("array");
        eq(list, Json.parseValue(Json.stringify(list)), "array root round trip");
    }

    static void apiInfo() throws IOException {
        Fake f = new Fake();
        eq(Json.fields("ok", true, "app", "herald", "version", "0.1.0", "api", 2.0,
                "tls", true, "cert_sha256", f.certSha256(), "pin_sha256", f.pinSha256(),
                "id", "device", "name", "Living Room", "model", "model", "pairing", true),
                ok(f.request("GET", "/v1/info", "", "")), "public info");
    }

    static void apiUnauthorized() throws IOException {
        Fake f = new Fake();
        for (String header : List.of("", bearer("unknown"))) {
            for (String path : List.of("/v1/status", "/missing", "/v1/pair/start"))
                error(f.request("GET", path, header, ""), 401, "unauthorized");
        }
    }

    static void apiOversize() throws IOException {
        Fake f = new Fake();
        for (String[] route : new String[][] {{"GET /v1/status","65536"}, {"POST /v1/cards","1572864"},
                {"POST /v1/announce","3145728"}, {"PUT /v1/clips/k","4194304"}, {"PUT /v1/images/k","2097152"}}) {
            int limit = Integer.parseInt(route[1]);
            String prefix = route[0] + " HTTP/1.1\r\nContent-Length: ";
            String head = prefix + (limit + 1) + "\r\n\r\n";
            Counting wire = new Counting(bytes(head + "unread"));
            error(run(f.api, wire), 413, "body too large");
            eq((long) head.length(), wire.bytes, "rejected at end of headers");
            error(run(f.api, stream(prefix + limit + "\r\n\r\n")), 401, "unauthorized");
        }
    }

    static void apiRoutes() throws IOException {
        Fake f = new Fake();
        String auth = bearer(f.issue("test"));
        error(f.request("GET", "/v1/missing", auth, ""), 404, "unknown path");
        error(f.request("POST", "/v1/status", auth, "{"), 405, "method not allowed");
        error(f.request("POST", "/say", auth, "{}"), 404, "unknown path");
    }

    static void apiHeaders() throws IOException {
        Fake f = new Fake();
        String token = f.issue("test");
        String header = "aUthORization: bEaReR " + token + "\r\n";
        ok(f.request("GET", "/v1/status", header, ""));
        error(f.request("GET", "/v1/info", "Authorization:\r\n" + header, ""), 400, "bad request");
        error(f.request("GET", "/v1/status", bearer(token) + header, ""), 400, "bad request");
        error(f.request("GET", "/v1/status", bearer(" " + token), ""), 401, "unauthorized");
        error(run(f.api, stream("GET /v1/info invalid\r\n\r\n")), 400, "bad request");
        error(run(f.api, stream("GET /v1/info HTTP/1.1\r\nInvalid@Name: value\r\n\r\n")), 400, "bad request");
    }

    static void authStart() throws IOException {
        Fake f = new Fake();
        eq(Json.fields("ok", true, "expires_in", 120.0), ok(f.start(" phone ")), "start reply");
        check(f.displayed.matches("[0-9]{6}"), "six digit code");
        eq("phone", f.shownName, "bound trimmed name");
        error(f.start("other"), 409, "pairing in progress");
    }

    static void authStartErrors() throws IOException {
        Fake f = new Fake();
        f.enabled = false;
        f.screen = false;
        error(f.request("POST", "/v1/pair/start", "", "{"), 400, "bad json");
        error(f.start("  "), 400, "bad client");
        error(f.start("test"), 403, "pairing disabled");
        f.enabled = true;
        error(f.start("test"), 409, "screen off");
        f.screen = true;
        f.overlay = false;
        error(f.start("test"), 409, "overlay not allowed");
        f.overlay = true;
        f.show = false;
        error(f.start("test"), 409, "overlay not allowed");
        f.show = true;
        for (int i = 0; i < 16; i++) f.issue("client" + i);
        f.screen = false;
        error(f.start("test"), 409, "too many clients");
    }

    static void authIssue() throws Exception {
        Fake f = new Fake();
        String token = f.issue("test");
        check(Base64.getUrlDecoder().decode(token).length == 32 && token.matches("[A-Za-z0-9_-]{43}"), "token format");
        check(!f.saved.contains(token), "no raw credential persisted");
        StringBuilder digest = new StringBuilder();
        for (byte b : MessageDigest.getInstance("SHA-256").digest(bytes(token))) digest.append(String.format("%02x", b));
        check(f.saved.contains(digest), "stored digest");
        eq(null, f.displayed, "overlay hidden");
        eq("test", f.paired, "pairing confirmation");
        String state = f.saved;
        f.restart();
        long created = f.now / 1000;
        f.now += 59000;
        ok(f.request("GET", "/v1/status", bearer(token), ""));
        check(state.equals(f.saved), "last used writes throttled");
        f.now += 1000;
        Object clients = ok(f.request("GET", "/v1/clients", bearer(token), "")).get("clients");
        eq(List.of(Json.fields("client", "test", "created", (double) created, "last_used", (double) created + 60)),
                clients, "client metadata");
    }

    static void lock(Fake f) throws IOException {
        ok(f.start("phone"));
        for (int i = 0; i < 5; i++) error(f.finish("phone", "invalid"), 403, "wrong code");
    }

    static void authLockout() throws IOException {
        Fake f = new Fake();
        lock(f);
        eq(null, f.displayed, "cancel hides overlay");
        error(f.finish("phone", "invalid"), 404, "no pairing");
        f.now += 59999;
        error(f.start("phone"), 429, "try later");
        f.now++;
        ok(f.start("phone"));
    }

    static void authExpired() throws IOException {
        Fake f = new Fake();
        ok(f.start("phone"));
        f.now += 120000;
        error(f.finish("phone", f.displayed), 410, "pairing expired");
        eq(null, f.displayed, "expired overlay removed");
    }

    static void authRevoke() throws IOException {
        Fake f = new Fake();
        String header = bearer(f.issue("phone"));
        ok(f.request("DELETE", "/v1/clients/self", header, ""));
        error(f.request("GET", "/v1/status", header, ""), 401, "unauthorized");
        eq(2L, f.events.latest(), "pair and remove events");
        f.restart();
        error(f.request("GET", "/v1/status", header, ""), 401, "unauthorized");
    }

    static void authName() throws IOException {
        Fake f = new Fake();
        ok(f.start("phone"));
        error(f.finish("other", f.displayed), 403, "wrong code");
        ok(f.finish("phone", f.displayed));
    }
    @SuppressWarnings("unchecked")
    static void authCapacity() throws Exception {
        for (boolean tv : new boolean[]{false, true}) {
            Fake f = new Fake();
            String token = f.issue("first");
            for (int i = 1; i < 15; i++) f.issue("client" + i);
            String code;
            if (tv) code = f.auth.startTv();
            else {
                ok(f.start("last"));
                code = f.displayed;
            }
            // Seed the boundary after start. Normal serialization prevents a second real
            // pairing from filling that slot while this code is active.
            java.lang.reflect.Field field = Auth.class.getDeclaredField("clients");
            field.setAccessible(true);
            List<Auth.Client> clients = (List<Auth.Client>) field.get(f.auth);
            Auth.Client extra = f.auth.authorize("Bearer " + token);
            clients.add(extra);
            error(f.finish("last", code), 409, "too many clients");
            clients.remove(15);
            ok(f.finish("last", code));
            eq(16, f.auth.clients().size(), "last available slot");
            failure(f.auth::startTv, 409, "too many clients");
        }
    }

    static void authTv() throws IOException {
        Fake f = new Fake();
        ok(f.start("phone"));
        failure(f.auth::startTv, 409, "pairing in progress");
        for (int i = 0; i < 5; i++) error(f.finish("phone", "invalid"), 403, "wrong code");
        failure(f.auth::startTv, 429, "try later");
        f.now += 60000;
        f.screen = f.overlay = false;
        String code = f.auth.startTv();
        check(code.matches("[0-9]{6}") && f.displayed == null, "local code only");
        check(code.equals(f.auth.tvCode()), "settings can display local code");
        ok(f.finish("tablet", code));
        eq(null, f.auth.tvCode(), "local code cleared");
        eq("tablet", f.auth.pairedName(), "settings confirmation");
        f.now += 5000;
        eq(null, f.auth.pairedName(), "confirmation ends");
    }

    static void authRace() throws Exception {
        Fake f = new Fake();
        String code = f.auth.startTv();
        CountDownLatch go = new CountDownLatch(1);
        List<FutureTask<Reply>> results = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            FutureTask<Reply> task = new FutureTask<>(() -> {
                check(go.await(200, TimeUnit.MILLISECONDS), "start race");
                return f.finish("phone", code);
            });
            results.add(task);
            new Thread(task).start();
        }
        go.countDown();
        List<Integer> status = new ArrayList<>();
        for (FutureTask<Reply> task : results) status.add(task.get(200, TimeUnit.MILLISECONDS).code);
        status.sort(null);
        eq(List.of(200, 404), status, "single issuance");
        eq(1, f.auth.clients().size(), "one stored credential");
    }

    static void authRestart() throws IOException {
        Fake f = new Fake();
        f.issue("existing");
        ok(f.start("phone"));
        String code = f.displayed, stream = f.events.stream;
        f.restart();
        error(f.finish("phone", code), 404, "no pairing");
        check(!stream.equals(f.events.stream) && f.events.stream.matches("[a-f0-9]{16}"), "new stream");
    }

    static void waitUntilParked(Thread thread) {
        long end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(150);
        while (thread.getState() != Thread.State.TIMED_WAITING && System.nanoTime() < end) Thread.yield();
        eq(Thread.State.TIMED_WAITING, thread.getState(), "event waiter parked");
    }

    static void eventsPoll() throws Exception {
        Fake f = new Fake();
        f.events.add("screen", Json.fields("on", false));
        Map<String, Object> ready = f.events.await(0, 150);
        eq(1, ((List<?>) ready.get("events")).size(), "immediate backlog");
        FutureTask<Map<String, Object>> task = new FutureTask<>(() -> f.events.await(1, 180));
        Thread thread = new Thread(task);
        thread.start();
        waitUntilParked(thread);
        f.events.add("screen", Json.fields("on", true));
        Map<String, Object> result = task.get(200, TimeUnit.MILLISECONDS);
        eq(2L, result.get("seq"), "woken by new event");
        eq(1, ((List<?>) result.get("events")).size(), "only newer events");
        eq(List.of(), f.events.await(2, 5).get("events"), "timeout returns empty");
    }

    static void eventsGap() {
        Fake f = new Fake();
        for (int i = 0; i < 257; i++) f.events.add("screen", Json.fields("on", true));
        Map<String, Object> oldest = f.events.await(0, 0);
        eq(true, oldest.get("gap"), "cursor too old");
        List<?> events = (List<?>) oldest.get("events");
        eq(256, events.size(), "ring capacity");
        eq(2L, ((Map<?,?>) events.get(0)).get("seq"), "oldest retained");
        eq(false, f.events.await(1, 0).get("gap"), "cursor at oldest minus one");
    }

    static void eventsWaiters() throws Exception {
        Fake f = new Fake();
        String header = bearer(f.issue("client"));
        List<FutureTask<Map<String,Object>>> tasks = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            FutureTask<Map<String,Object>> task = new FutureTask<>(() -> f.events.await(1, 190));
            tasks.add(task);
            Thread thread = new Thread(task);
            thread.start();
            waitUntilParked(thread);
        }
        error(f.request("GET", "/v1/events?after=1&wait=1", header, ""), 429, "too many waiters");
        f.events.add("screen", Json.fields("on", true));
        for (FutureTask<Map<String,Object>> task : tasks)
            eq(2L, task.get(200, TimeUnit.MILLISECONDS).get("seq"), "waiter got event");
        f.events.await(2, 2);
    }

    static void eventsReset() throws IOException {
        Fake f = new Fake();
        String header = bearer(f.issue("client"));
        Map<String, Object> result = ok(f.request("GET", "/v1/events?after=2&wait=0", header, ""));
        eq(Json.fields("ok", true, "stream", f.events.stream, "seq", 1.0, "events", List.of(),
                "gap", false, "reset", true), result, "cursor beyond latest");
    }

    static void eventsTime() {
        Fake f = new Fake();
        f.events.add("screen", Json.fields("on", true));
        List<?> events = (List<?>) f.events.await(0, 0).get("events");
        eq(Json.fields("time", f.now, "type", "screen", "on", true, "seq", 1L), events.get(0), "event wire fields");
    }

    static void statusFields() throws IOException {
        Fake f = new Fake();
        Map<String, Object> status = ok(f.request("GET", "/v1/status", bearer(f.issue("client")), ""));
        String keys = "ok version api tls_failures id name stream screen_on overlay_allowed battery_exempt tts_ready outputs "
                + "audio_busy last_audio temp_files sound_playing quiet_seconds cards_visible cards_queued last_card "
                + "chips clips images settings_revision mqtt ntfy compat event_seq";
        eq(java.util.Set.of(keys.split(" ")), status.keySet(), "all documented keys");
        eq(1.0, status.get("event_seq"), "current cursor");
        eq(f.events.stream, status.get("stream"), "same stream as events");
    }

    static void settingsDefaults() {
        Settings s = new Fake().settings;
        String[] ids = "general doorbell camera alarm media announcement reminder system".split(" ");
        List<Map<String, Object>> expected = List.of(
                Json.fields("placement", "top_right", "size", "medium", "text_size", "medium",
                        "accent", "#4FC3F7", "background", "#1C202A", "opacity", 60,
                        "icon", "info", "duration", 10, "stacking", "queue", "sound", "none",
                        "sound_volume", 60, "video_sound", "off", "show_here", true,
                        "over_playback", "show", "night", "normal", "focus", "auto",
                        "animation", "slide", "layout", "card", "corner", "large",
                        "border", "thin", "border_color", "#5A6272", "shadow", "soft",
                        "glow", "none", "accent_style", "badge", "title_color", "#FFFFFF",
                        "text_color", "#FFFFFF", "title_weight", "bold", "label", "none"),
                Json.fields("placement", "top_right", "size", "medium", "text_size", "medium",
                        "accent", "#FFB74D", "background", "#000000", "opacity", 90,
                        "icon", "door", "duration", 30, "stacking", "replace",
                        "sound", "none", "sound_volume", 60, "video_sound", "off",
                        "show_here", true, "over_playback", "show", "night", "normal",
                        "focus", "auto", "animation", "slide", "layout", "card",
                        "corner", "small", "border", "none", "border_color", "#FFFFFF",
                        "shadow", "soft", "glow", "none", "accent_style", "bar",
                        "title_color", "#FFFFFF", "text_color", "#FFFFFF",
                        "title_weight", "bold", "label", "none"),
                Json.fields("placement", "top_right", "size", "medium", "text_size", "medium",
                        "accent", "#81C784", "background", "#000000", "opacity", 90,
                        "icon", "camera", "duration", 30, "stacking", "replace",
                        "sound", "none", "sound_volume", 60, "video_sound", "off",
                        "show_here", true, "over_playback", "show", "night", "normal",
                        "focus", "auto", "animation", "slide", "layout", "card",
                        "corner", "small", "border", "none", "border_color", "#FFFFFF",
                        "shadow", "soft", "glow", "none", "accent_style", "bar",
                        "title_color", "#FFFFFF", "text_color", "#FFFFFF",
                        "title_weight", "bold", "label", "none"),
                Json.fields("placement", "center", "size", "large", "text_size", "large",
                        "accent", "#E57373", "background", "#781218", "opacity", 85,
                        "icon", "alarm", "duration", 0, "stacking", "replace",
                        "sound", "none", "sound_volume", 60, "video_sound", "off",
                        "show_here", true, "over_playback", "show", "night", "normal",
                        "focus", "auto", "animation", "slide", "layout", "card",
                        "corner", "medium", "border", "thick", "border_color", "#FF6B6B",
                        "shadow", "strong", "glow", "border", "accent_style", "badge",
                        "title_color", "#FFFFFF", "text_color", "#FFE3E3",
                        "title_weight", "bold", "label", "category"),
                Json.fields("placement", "top", "size", "medium", "text_size", "medium", "accent", "#BA68C8",
                        "background", "#181C24", "opacity", 80, "icon", "media",
                        "duration", 12, "stacking", "queue", "sound", "none",
                        "sound_volume", 60, "video_sound", "off", "show_here", true,
                        "over_playback", "show", "night", "normal", "focus", "auto",
                        "animation", "slide", "layout", "pill", "corner", "pill",
                        "border", "thin", "border_color", "#3A404C", "shadow", "soft",
                        "glow", "none", "accent_style", "badge", "title_color", "#FFFFFF",
                        "text_color", "#FFFFFF", "title_weight", "bold", "label", "none"),
                Json.fields("placement", "top_right", "size", "medium", "text_size", "medium",
                        "accent", "#FFF176", "background", "#1C202A", "opacity", 60,
                        "icon", "megaphone", "duration", 15, "stacking", "queue",
                        "sound", "none", "sound_volume", 60, "video_sound", "off",
                        "show_here", true, "over_playback", "show", "night", "normal",
                        "focus", "auto", "animation", "slide", "layout", "card",
                        "corner", "large", "border", "thin", "border_color", "#5A6272",
                        "shadow", "soft", "glow", "none", "accent_style", "badge",
                        "title_color", "#FFFFFF", "text_color", "#FFFFFF",
                        "title_weight", "bold", "label", "none"),
                Json.fields("placement", "top_right", "size", "medium", "text_size", "medium",
                        "accent", "#90A4AE", "background", "#1C202A", "opacity", 60,
                        "icon", "bell", "duration", 12, "stacking", "queue", "sound", "none",
                        "sound_volume", 60, "video_sound", "off", "show_here", true,
                        "over_playback", "show", "night", "dim", "focus", "auto",
                        "animation", "slide", "layout", "card", "corner", "large",
                        "border", "thin", "border_color", "#5A6272", "shadow", "soft",
                        "glow", "none", "accent_style", "badge", "title_color", "#FFFFFF",
                        "text_color", "#FFFFFF", "title_weight", "bold", "label", "none"),
                Json.fields("placement", "top_right", "size", "medium", "text_size", "medium",
                        "accent", "#B0BEC5", "background", "#1C202A", "opacity", 60,
                        "icon", "tv", "duration", 30, "stacking", "replace", "sound", "none",
                        "sound_volume", 60, "video_sound", "off", "show_here", true,
                        "over_playback", "show", "night", "normal", "focus", "auto",
                        "animation", "slide", "layout", "card", "corner", "large",
                        "border", "thin", "border_color", "#5A6272", "shadow", "soft",
                        "glow", "none", "accent_style", "badge", "title_color", "#FFFFFF",
                        "text_color", "#FFFFFF", "title_weight", "bold", "label", "none"));
        eq(8, s.categories().size(), "eight defaults");
        for (int i = 0; i < ids.length; i++) {
            Map<String, Object> row = s.categories().get(i);
            eq(ids[i], row.get("id"), "category id");
            eq(Character.toUpperCase(ids[i].charAt(0)) + ids[i].substring(1), row.get("name"), "name");
            eq(true, row.get("builtin"), "built-in flag");
            expected.get(i).putAll(Json.fields("button_style", "solid", "button_shape", "rounded", "button_spacing", "normal",
                    "button_background", "#303A46", "button_text_color", "#DCE3EA",
                    "button_focus_background", "#526579", "button_focus_text_color", "#F3F6F8"));
            eq(expected.get(i), row.get("effective"), "resolved " + ids[i]);
            eq(Json.fields(), row.get("style"), "sparse " + ids[i]);
        }
    }

    static void settingsPrecedence() {
        Settings s = new Fake().settings;
        s.putCategory("alarm", Json.fields("style", Json.fields("text_size", "xlarge", "duration", 15)));
        Style style = s.resolve("alarm", Json.fields("duration", 3));
        eq(3, style.fields.get("duration"), "request");
        eq("xlarge", style.fields.get("text_size"), "category");
        eq("large", style.fields.get("size"), "built-in");
        eq(15, s.resolve("alarm", null).fields.get("duration"), "request does not mutate");
        s.putCategory("general", Json.fields("style", Json.fields("icon", "package")));
        eq("package", s.resolve("missing", null).fields.get("icon"), "unknown uses current general");
    }

    static void settingsMerge() {
        Settings s = new Fake().settings;
        s.put(Json.fields("device", Json.fields("port", 7980), "display", Json.fields("pixel_shift", false)));
        s.putCategory("alarm", Json.fields("style", Json.fields("duration", 60)));
        s.putCategory("garage", Json.fields("name", "Garage"));
        s.put(Json.fields("device", Json.fields("name", "Study"), "categories", List.of(Json.fields(
                "id", "camera", "builtin", false, "style", Json.fields("opacity", 75)))));
        eq(7980, s.port(), "sibling preserved");
        eq("Study", s.name(), "name updated");
        eq(false, Settings.object(s.document().get("display"), "display").get("pixel_shift"), "section preserved");
        eq(8, s.categories().size(), "list replaced");
        eq(75, s.resolve("camera", null).fields.get("opacity"), "provided style");
        eq(0, s.resolve("alarm", null).fields.get("duration"), "missing built-in reset");
        eq(true, s.categories().get(0).get("builtin"), "read-only flag ignored");
    }

    static Map<String, Object> at(String path, Object value) {
        String[] keys = path.split("\\.");
        for (int i = keys.length - 1; i >= 0; i--) value = Json.fields(keys[i], value);
        return Settings.object(value, path);
    }

    static void badSettings(Fake f, String token, Map<String, Object> patch, String path) throws IOException {
        Reply reply = f.settingsRequest("PUT", "/v1/settings", token, patch);
        error(reply, 400, "bad settings");
        eq(path, Json.parse(reply.body).get("field"), "first bad field");
    }

    static void settingsBounds() throws IOException {
        Fake f = new Fake();
        String token = f.issue("client");
        Object[][] cases = {
                {"device.port", 1023}, {"device.name", ""}, {"pairing.allowed", "true"},
                {"display.max_visible", 7}, {"display.sticky_cap", 59}, {"display.night_start", "24:00"},
                {"display.focus_while_playing", "yes"}, {"audio.volume_tv", 101}, {"mqtt.base_topic", "a/#"},
                {"mqtt.base_topic", "/base"}, {"mqtt.port", 0}, {"compat.tvoverlay.port", 7979},
                {"compat.tvoverlay.allowed", List.of("10.0.0.0/33")}, {"compat.tvoverlay.category", "nope"}
        };
        for (Object[] c : cases) badSettings(f, token, at((String) c[0], c[1]), (String) c[0]);
        badSettings(f, token, at("compat.tvoverlay.enabled", true), "compat.tvoverlay.allowed");
        Object[][] styles = {{"accent","red"}, {"background","#12345"}, {"opacity",37}, {"opacity",105},
                {"text_size","huge"}, {"video_sound","loud"}, {"duration",3601}, {"surprise",false}};
        for (Object[] c : styles) {
            Map<String, Object> row = Json.fields("id", "general", "style", Json.fields(c[0], c[1]));
            badSettings(f, token, Json.fields("categories", List.of(row)), "categories.0.style." + c[0]);
        }
        badSettings(f, token, Json.fields("categories", 0, "mqtt", false, "device", Json.fields("port", 1, "name", "")),
                "device.name");
        badSettings(f, token, Json.fields("categories", false, "compat", Json.fields("tvoverlay", Json.fields("category", "bad"))),
                "compat.tvoverlay.category");
        eq(0, f.settings.revision(), "no invalid edit published");
        eq(null, f.settingsJson, "no invalid edit persisted");
    }

    static void settingsStyle() {
        Settings s = new Fake().settings;
        for (int percent = 0; percent <= 100; percent += 5) {
            Map<String, Object> patch = Json.fields("background", "#aBc123", "opacity", percent, "text_size", "xlarge",
                    "video_sound", "on");
            s.putCategory("doorbell", Json.fields("style", patch));
            Map<String, Object> fields = s.resolve("doorbell", null).fields;
            for (String key : patch.keySet()) eq(patch.get(key), fields.get(key), key);
        }
    }

    static void settingsRevision() throws IOException {
        Fake f = new Fake();
        String token = f.issue("client");
        long seq = f.events.latest();
        Map<String, Object> patch = Json.fields("device", Json.fields("port", 7990, "name", "Den"), "revision", 500);
        String body = Json.stringify(patch);
        ByteArrayOutputStream output = new ByteArrayOutputStream() {
            @Override public void flush() {
                eq(0, f.reboundPort, "listener still on original port at flush");
            }
        };
        f.api.handle(stream("PUT /v1/settings HTTP/1.1\r\n" + bearer(token) + "Content-Length: " + bytes(body).length
                + "\r\n\r\n" + body), output);
        String response = output.toString("UTF-8");
        check(response.startsWith("HTTP/1.1 200 OK"), "port change answered");
        eq(1.0, Json.parse(response.substring(response.indexOf("\r\n\r\n") + 4)).get("revision"), "first edit");
        eq(7990, f.reboundPort, "new listener port");
        eq("Den", ok(f.request("GET", "/v1/info", "", "")).get("name"), "info reads new name");
        eq(1.0, ok(f.settingsRequest("PUT", "/v1/settings", token, patch)).get("revision"), "no-op");
        eq(seq + 1, f.events.latest(), "no no-op event");
        Settings failing = new Settings("Den", f.settingsJson, json -> { throw new Api.Failure(500, "settings store failed"); }, f.events);
        failure(() -> failing.put(at("device.port", 8000)), 500, "settings store failed");
        eq(1, failing.revision(), "failed write preserves revision");
        eq(7990, failing.port(), "failed write preserves port");
        eq(seq + 1, f.events.latest(), "failed write emits no event");
        f.settings.putCategory("garage", Json.fields("name", "Garage"));
        eq(2.0, ok(f.request("GET", "/v1/status", bearer(token), "")).get("settings_revision"), "status revision");
    }

    static void settingsPassword() throws IOException {
        Fake f = new Fake();
        String token = f.issue("client");
        String value = java.util.UUID.randomUUID().toString();
        Reply put = f.settingsRequest("PUT", "/v1/settings", token, at("mqtt.password", value));
        eq(200, put.code, "password update");
        check(!put.body.contains(value), "PUT redacted");
        f.settings.put(at("mqtt.password", ""));
        f.settings.put(at("mqtt.password_set", false));
        eq(1, f.settings.revision(), "empty and read-only updates ignored");
        f.restart();
        Map<String, Object> mqtt = Settings.object(ok(f.request("GET", "/v1/settings", bearer(token), "")).get("mqtt"), "mqtt");
        eq(true, mqtt.get("password_set"), "password survives restart");
        check(!mqtt.containsKey("password") && !Json.stringify(mqtt).contains(value), "GET redacted");
        check(value.equals(Settings.object(Json.parse(f.settingsJson).get("mqtt"), "mqtt").get("password")), "stored value kept");
        check(!Json.stringify(f.events.await(0, 0)).contains(value), "events redacted");
    }

    static void categoryRoutes() throws IOException {
        Fake f = new Fake();
        String token = f.issue("client");
        String path = "/v1/categories/garage";
        for (boolean delete : new boolean[]{true, false}) {
            Map<String, Object> row = Json.fields("name", "Garage", "style", Json.fields("background", "#123456"));
            Map<String, Object> saved = Settings.object(ok(f.settingsRequest("PUT", path, token, row)).get("category"), "category");
            eq(false, saved.get("builtin"), "custom");
            ok(f.settingsRequest("PUT", path, token, at("style.opacity", 35)));
            eq("#123456", f.settings.resolve("garage", null).fields.get("background"), "partial style merge");
            eq(35, f.settings.resolve("garage", null).fields.get("opacity"), "restyle");
            ok(f.settingsRequest("PUT", "/v1/settings", token, at("compat.tvoverlay.category", "garage")));
            long seq = f.events.latest();
            int rev = f.settings.revision();
            if (delete) ok(f.request("DELETE", path, bearer(token), ""));
            else ok(f.settingsRequest("PUT", "/v1/settings", token, Json.fields("categories", List.of())));
            Map<String, Object> doc = ok(f.request("GET", "/v1/settings", bearer(token), ""));
            Map<String, Object> compat = Settings.object(doc.get("compat"), "compat");
            eq("general", Settings.object(compat.get("tvoverlay"), "tvoverlay").get("category"), "compat fallback");
            eq(rev + 1, f.settings.revision(), "one revision");
            List<?> list = (List<?>) f.events.await(seq, 0).get("events");
            eq(1, list.size(), "one event");
            Map<String, Object> event = Settings.object(list.get(0), "event");
            eq("settings.changed", event.get("type"), "event type");
            eq(rev + 1, event.get("revision"), "event revision");
            eq(8, ((List<?>) ok(f.request("GET", "/v1/categories", bearer(token), "")).get("categories")).size(), "custom removed");
        }
        error(f.request("DELETE", path, bearer(token), ""), 404, "unknown category");
        error(f.request("DELETE", "/v1/categories/alarm", bearer(token), ""), 409, "built-in category");
    }

    static void categoryLimit() throws IOException {
        Fake f = new Fake();
        String token = f.issue("client");
        for (int i = 0; i < 24; i++) ok(f.settingsRequest("PUT", "/v1/categories/c" + i, token, Json.fields("name", "Chips")));
        ok(f.settingsRequest("PUT", "/v1/categories/c0", token, Json.fields("name", "Updated")));
        error(f.settingsRequest("PUT", "/v1/categories/extra", token, Json.fields("name", "Extra")), 409, "too many categories");
        eq(32, f.settings.categories().size(), "32 including built-ins");
    }

    static void settingsRoundTrip() {
        Fake f = new Fake();
        f.settings.put(Json.fields("compat", Json.fields("tvoverlay", Json.fields("enabled", true, "allowed", List.of("::1"),
                "category", "custom")), "categories", List.of(Json.fields("id", "custom", "style", Json.fields("opacity", 0)))));
        Map<String, Object> expected = f.settings.document();
        f.restart();
        eq(expected, f.settings.document(), "restored full document");
        eq(expected, f.settings.put(Json.parse(Json.stringify(expected))), "JSON read-back PUT is a no-op");
        eq(0L, f.events.latest(), "restore and no-op are silent");
    }

    static void cidr() {
        check(Cidr.parse("192.0.2.129/25").matches("192.0.2.255"), "IPv4 partial byte");
        check(!Cidr.parse("192.0.2.129/25").matches("192.0.2.127"), "IPv4 outside prefix");
        check(!Cidr.parse("192.0.2.1").matches("192.0.2.2"), "IPv4 literal is exact");
        check(Cidr.parse("2001:db8:abcd::1/49").matches("2001:db8:abcd:7fff::abcd"), "IPv6 partial byte");
        check(!Cidr.parse("2001:db8:abcd::1/49").matches("2001:db8:abcd:8000::"), "IPv6 outside prefix");
        check(Cidr.parse("::ffff:192.0.2.1").matches("0:0:0:0:0:ffff:c000:201"), "embedded IPv4");
        check(!Cidr.parse("::/0").matches("127.0.0.1"), "different families");
        for (String bad : List.of("10.0.0.0/33", "::1/129", "localhost", "127.1", "1::2::3", "fe80::1%lo",
                "1:2:3:4:5:6:7:8::", "1.2.3.256", "[::1]", "::1/", "1.2.3.4/-1")) {
            rejects(() -> Cidr.parse(bad), "invalid numeric address or range");
        }
    }

    static void cardFields() throws Exception {
        Fake f = new Fake();
        f.token = f.issue("test");
        Map<String,Object> doc = Json.fields("title", false, "id", ".", "category", "BAD", "image", false,
                "video", false, "buttons", false, "priority", false, "icon", "unknown");
        f.overlay = false;
        for (String field : List.of("title","id","category","image","video","buttons","priority","icon")) {
            String error = field.equals("icon") ? "bad style" : field.equals("title") ? "bad json" : "bad " + field;
            error(f.route("POST","cards",doc), 400, error);
            doc.remove(field);
            if (field.equals("id")) doc.put("message","x");
        }
        error(f.route("POST","cards",doc), 409, "overlay not allowed");
        for (String field : List.of("title","message","from"))
            error(f.route("POST","cards", Json.fields(field, 5)),400,"bad json");
        error(f.route("POST","cards",Json.fields("title","  ","message"," ","buttons",false)),400,"empty card");
        error(f.route("POST","cards",Json.fields("video",Json.fields("url","rtsps://fixture.invalid/v"))),400,"bad video");
        Map<String,Object> b = Json.fields("id","action","label","x");
        for (Object invalid : List.of(List.of(b,b), List.of(Json.fields("id","action","label","")),
                List.of(Json.fields("id","action","label","a".repeat(23) + "😀")), List.of(Json.fields("id","action","label",1))))
            error(f.route("POST","cards",Json.fields("title","x","buttons",invalid)),400,"bad buttons");
        CardSpec spec = new CardSpec(Json.fields("video",Json.fields("url","rtsp://fixture.invalid/v"),"icon","bell",
                "style",Json.fields("icon","home"),"buttons",List.of(b)),f.settings);
        eq("bell", spec.style.fields.get("icon"),"top-level icon wins");
        eq("event",spec.buttons.get(0).get("action"),"default action");
        Map<String,Object> colors = Json.fields("background", "#abC123", "text_color", "#012345",
                "focus_background", "#fedcba", "focus_text_color", "#102030");
        Map<String,Object> action = Json.fields("id", "action", "label", "Go", "style", colors);
        f.settings.putLook("actions", at("style.button_shape", "pill"));
        f.settings.putCategory("general", Json.fields("look", "actions", "style", Json.fields("button_spacing", "roomy")));
        CardSpec styled = new CardSpec(Json.fields("title", "Test", "buttons", List.of(action),
                "style", Json.fields("button_background", "#112233")), f.settings);
        eq(colors, styled.buttons.get(0).get("style"), "action palette");
        eq("pill", styled.style.fields.get("button_shape"), "custom look shape");
        eq("roomy", styled.style.fields.get("button_spacing"), "category spacing");
        eq("#112233", styled.style.fields.get("button_background"), "request fill");
        Settings restored = new Settings("TV", f.settingsJson, doc2 -> {}, f.events);
        eq("pill", restored.resolve("general", null).fields.get("button_shape"), "saved look after restart");
        for (Object invalid : List.of("red", Json.fields("background", "red"), Json.fields("icon", "#010203"),
                Json.fields("focus_background", null))) {
            action.put("style", invalid);
            error(f.route("POST","cards",Json.fields("title","Test","buttons",List.of(action))),400,"bad buttons");
        }
        check(!spec.video.containsKey("muted") && spec.video.get("timeout").equals(12),"video defaults");
        check(spec.id.matches("[a-f0-9]{16}"),"generated id");
    }

    static void cardText() {
        Fake f = new Fake();
        CardSpec s = new CardSpec(Json.fields("title","a".repeat(119)+"😀","message","b".repeat(999)+"😀",
                "from","c".repeat(39)+"😀"),f.settings);
        check(s.title.length()==119 && s.message.length()==999 && s.from.length()==39,"safe text limits");
        s = new CardSpec(Json.fields("title","a".repeat(118)+"😀x","message","b".repeat(1001),
                "from","c".repeat(41)),f.settings);
        check(s.title.endsWith("😀") && s.title.length()==120 && s.message.length()==1000 && s.from.length()==40,
                "inclusive UTF-16 bounds");
    }

    static void cardCategory() {
        Fake f = new Fake();
        eq("general", f.post("a","category","unknown").get("category"), "resolved category");
    }

    static void state(Fake f, String id, String state, String reason) {
        Map<String,Object> row = f.cards.detail(id);
        eq(state,row.get("state"),"card state");
        eq(reason,row.get("reason"),"card reason");
    }

    static void cardUpdate() {
        Fake f = new Fake();
        f.post("a","priority","high","style",Json.fields("duration",3));
        f.attach();
        long cursor = f.events.latest();
        f.now += 2200;
        f.post("a");
        eq("card.updated", ((Map<?,?>) f.since(cursor).get(0)).get("type"),"updated event");
        eq(1, f.since(cursor).size(), "no extra lifecycle event");
        f.now += 1000;
        Map<String,Object> row = f.cards.rows().get(0);
        eq(9L,row.get("remaining_s"),"new timer");
        eq("normal",row.get("priority"),"defaults replace old spec");
        f.post("other","category","media");
        f.post("first","category","media");
        f.post("a","category","media");
        state(f,"a","queued",null);
        f.post("first","category","media");
        f.cards.delete("other");
        state(f,"a","showing",null);
        state(f,"first","queued",null);
        f.screen = false;
        f.cards.screen(false);
        f.post("wait","category","alarm");
        cursor = f.events.latest();
        f.post("wait");
        state(f,"wait","skipped","screen_off");
        eq(1, f.since(cursor).size(),"waiting update readmitted without end");
    }

    static void cardStack() {
        Fake f = new Fake();
        f.post("a","category","alarm","priority","critical");
        f.post("b","category","alarm");
        state(f,"b","queued",null);
        f.post("c","category","alarm","priority","critical");
        state(f,"a","ended","replaced");
        state(f,"c","showing",null);
        f.post("d","style",Json.fields("stacking","stack"));
        f.post("e","style",Json.fields("stacking","stack"));
        eq(3,f.cards.showing().size(),"stack alongside");
        f.post("later");
        state(f,"later","queued",null);
        f.cards.delete("d");
        state(f,"later","queued",null);
        f.cards.delete("e");
        state(f,"later","showing",null);
        f.cards.delete("c");
        state(f,"b","showing",null);
    }

    static void stack(Fake f,String id,String priority) {
        f.post(id,"priority",priority,"style",Json.fields("stacking","stack"));
    }

    static void cardCap() {
        Fake f = new Fake();
        stack(f,"one","low"); stack(f,"two","low"); stack(f,"three","high"); stack(f,"four","normal");
        state(f,"one","ended","replaced");
        eq(3,f.cards.showing().size(),"visible cap");
    }

    static void cardCriticalCap() {
        Fake f = new Fake();
        f.settings.put(at("display.max_visible",1));
        stack(f,"a","critical"); stack(f,"normal","normal"); stack(f,"b","critical");
        state(f,"a","showing",null);
        state(f,"b","queued",null);
        f.cards.delete("a");
        state(f,"b","showing",null);
        state(f,"normal","queued",null);
        f.post("replace","priority","critical","style",Json.fields("stacking","replace"));
        state(f,"b","ended","replaced");
        state(f,"replace","showing",null);
    }

    static void cardQueueBound() {
        Fake f = new Fake();
        f.post("shown");
        for (int i=0; i<32; i++) f.post("q"+i);
        failure(() -> f.post("overflow"),429,"too many cards");
        f.screen=false; f.cards.screen(false);
        for (int i=0; i<16; i++) f.post("w"+i,"category","alarm");
        failure(() -> f.post("overflow","category","alarm"),429,"too many cards");
        eq(16,f.cards.status().get("cards_queued"),"waiting counted");
        for (Map<String,Object> row : f.cards.rows()) eq(null,row.get("remaining_s"),"waiting remaining");
    }

    static void midnight(Fake f) {
        f.now = java.time.LocalDate.of(2026,1,1).atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
    }

    static void cardCritical() {
        Fake f = new Fake(); midnight(f); f.playing=true;
        f.post("urgent","priority","critical","style",Json.fields("show_here",false,"over_playback","skip","night","hide"));
        state(f,"urgent","showing",null);
    }

    static void cardNight() {
        Fake f = new Fake(); midnight(f);
        f.post("dim","style",Json.fields("night","dim","opacity",90,"size","large","text_size","xlarge"));
        Map<String,Object> s = f.cards.showing().get(0).appearance.fields;
        eq(55,s.get("opacity"),"opacity rounded to five");
        eq("medium",s.get("size"),"size down"); eq("large",s.get("text_size"),"text down");
        f.post("hide","style",Json.fields("night","hide")); state(f,"hide","skipped","night");
    }

    static void cardScreen() {
        Fake f = new Fake();
        f.post("timer","category","camera");
        f.post("old","style",Json.fields("duration",0));
        for (int i=0; i<16; i++) f.post("q"+i,"style",Json.fields("duration",0),"priority",i==15?"high":"normal");
        f.screen=false; f.cards.screen(false);
        state(f,"timer","ended","expired"); state(f,"old","ended","capped");
        f.post("late"); state(f,"late","skipped","screen_off");
        eq(16,f.cards.status().get("cards_queued"),"all sticky wait");
        f.screen=true; f.cards.screen(true);
        state(f,"q15","showing",null);
        state(f,"q0","queued",null);
    }

    static void cardSticky() {
        Fake f = new Fake();
        f.settings.put(at("display.sticky_cap",60));
        f.screen=false;
        f.post("a","category","alarm");
        f.now+=45000;
        f.screen=true; f.cards.screen(true);
        eq(15L,f.cards.rows().get(0).get("remaining_s"),"wall time while waiting");
        f.now+=15000;
        state(f,"a","ended","capped");
    }

    static void cardClear() throws Exception {
        Fake f = new Fake();
        f.post("c1"); f.post("c2"); f.post("other","category","alarm");
        String path="cards/clear";
        for (Map<String,Object> patch : List.of(Json.fields(),Json.fields("all",false),Json.fields("category","general","all",true)))
            error(f.route("POST",path,patch),400,"bad json");
        eq(true,ok(f.route("DELETE","cards/c1",Json.fields())).get("found"),"id cleared");
        eq(false,ok(f.route("DELETE","cards/c1",Json.fields())).get("found"),"idempotent");
        eq(1.0,ok(f.route("POST",path,Json.fields("category","general"))).get("cleared"),"category cleared");
        eq(1.0,ok(f.route("POST",path,Json.fields("all",true))).get("cleared"),"all cleared");
        eq(0.0,ok(f.route("POST",path,Json.fields("all",true))).get("cleared"),"empty");
    }

    static void cardHistory() {
        Fake f = new Fake(); midnight(f); f.playing=true; f.screen=false;
        Map<String,Object> style = Json.fields("show_here",false,"night","hide","over_playback","skip");
        f.post("s","style",style); state(f,"s","skipped","screen_off");
        f.screen=true;
        f.post("s1","style",style); state(f,"s1","skipped","hidden");
        style.put("show_here",true);
        f.post("s2","style",style); state(f,"s2","skipped","night");
        style.put("night","normal");
        f.post("s3","style",style); state(f,"s3","skipped","over_playback");
        eq(Json.fields("id","s3","category","general","state","skipped"),f.cards.status().get("last_card"),"last skip");
        for (int i=0; i<61; i++) { f.post("a"+i); f.cards.delete("a"+i); }
        failure(() -> f.cards.detail("s"),404,"unknown card"); state(f,"s1","skipped","hidden");
        eq(Json.fields("id","a60","category","general","state","ended"),f.cards.status().get("last_card"),"last end");
        f.now+=599999; state(f,"a60","ended","cleared");
        f.now++; failure(() -> f.cards.detail("a60"),404,"unknown card");
        failure(() -> f.cards.detail("s3"),404,"unknown card");
    }

    static void cardLatency() {
        Fake f = new Fake(); long cursor = f.events.latest();
        f.post("a","style",Json.fields("sound","chime","sound_volume",35));
        eq(0,f.since(cursor).size(),"no shown before attachment");
        f.now+=37; f.attach(); f.attach();
        List<?> events=f.since(cursor);
        eq(1,events.size(),"one shown");
        Map<?,?> event=(Map<?,?>) events.get(0);
        eq("card.shown",event.get("type"),"type"); eq(37L,event.get("ms"),"latency");
        eq("general",event.get("category"),"category"); eq(1,f.sounds,"sound once");
        eq(Json.fields("id","a","category","general","state","showing"),f.cards.status().get("last_card"),"last shown");
    }

    static void chipFields() throws Exception {
        Fake f = new Fake();
        String id = f.issue("settings");
        for (String value : List.of("top_left","top","top_right","bottom_left","bottom","bottom_right"))
            ok(f.settingsRequest("PUT","/v1/settings",id,at("display.chip_placement",value)));
        badSettings(f,id,at("display.chip_placement","left"),"display.chip_placement");
        error(f.route("PUT","chips/.bad",Json.fields("text",false)),400,"bad id");
        error(f.route("PUT","chips/x",Json.fields("text","","icon","none")),400,"bad chip");
        ok(f.route("PUT","chips/x",Json.fields("text","x")));
        eq(Json.fields("id","x","text","x","color","#FFFFFF","icon","none","priority","normal","expires_at",null,"look",null,"position",null),
                f.chips.rows().get(0),"minimal chip defaults");
        for (int i=1; i<12; i++) f.chips.put("n"+i,Json.fields("icon","alarm","priority",i==5?"high":"normal","expires",1));
        eq("n5",f.chips.rows().get(0).get("id"),"priority before recency");
        eq("n11",f.chips.rows().get(1).get("id"),"newest first");
        for (Map<String,Object> invalid : List.of(Json.fields("text","a".repeat(39)+"😀"),
                Json.fields("icon","unknown"),Json.fields("text","x","color","#fff"),
                Json.fields("text","x","expires",604801),Json.fields("text","x","priority","critical")))
            error(f.route("PUT","chips/overflow",invalid),400,"bad chip");
        error(f.route("PUT","chips/overflow",Json.fields("text","x")),409,"too many chips");
        f.now += 1000;
        eq(1,f.chips.rows().size(),"expired removed");
        check(!Files.readString(new File(f.directory,"chips.json").toPath()).contains("n11"),"expiration persisted");
        eq(false,ok(f.route("DELETE","chips/n11",Json.fields())).get("found"),"delete expired");
        f.restart();
        eq("x",f.chips.rows().get(0).get("id"),"survives restart");
    }

    static void chipBulk() throws Exception {
        Fake f = new Fake();
        f.chips.put("keep",Json.fields("text","one"));
        error(f.route("PUT","chips/keep",Json.fields("text","")),400,"bad chip");
        Map<String,Object> chip = Json.fields("id","new","icon","bell");
        for (List<?> rows : List.of(List.of(chip,chip),List.of(chip,Json.fields("id",".")),List.of(chip,Json.fields("id","bad")))) {
            error(f.route("PUT","chips",Json.fields("chips",rows)),400,"bad chip");
            eq("keep",f.chips.rows().get(0).get("id"),"failed bulk preserved old set");
        }
        List<Map<String,Object>> many = new ArrayList<>();
        for (int i=0; i<13; i++) many.add(Json.fields("id","a"+i,"text","x"));
        error(f.route("PUT","chips",Json.fields("chips",many)),409,"too many chips");
        ok(f.route("PUT","chips",Json.fields("chips",List.of(chip))));
        eq(List.of(f.chips.rows().get(0)),ok(f.route("GET","chips",Json.fields())).get("chips"),"collection shape");
        eq("new",f.chips.rows().get(0).get("id"),"replaced");
        File path = new File(f.directory,"chips.json");
        check(path.delete() && path.mkdir(),"inject save failure");
        error(f.route("DELETE","chips/new",Json.fields()),500,"store failed");
        eq("new",f.chips.rows().get(0).get("id"),"failed delete preserved");
        error(f.route("PUT","chips",Json.fields("chips",List.of())),500,"store failed");
        eq(1,f.chips.rows().size(),"failed replace preserved");
        check(path.delete(),"remove injected failure");
        eq(true,ok(f.route("DELETE","chips/new",Json.fields())).get("found"),"explicit delete");
    }

    static byte[] bitmap() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(2,2,java.awt.image.BufferedImage.TYPE_INT_RGB),"png",out);
        return out.toByteArray();
    }

    static void imageCache() throws IOException {
        Fake f = new Fake();
        byte[] data = bitmap();
        for (String bad : List.of(".x","a/b","x".repeat(65),""))
            failure(() -> f.images.put(bad,data),400,"bad key");
        for (int i=0;i<64;i++) f.images.put("k.tmp-"+i,data);
        f.images.size("k.tmp-0");
        f.images.put("_final",data);
        eq(64,f.images.count(),"cache cap");
        failure(() -> f.images.size("k.tmp-1"),404,"not cached");
        check(Arrays.equals(data,f.images.read("k.tmp-0")),"get refreshes LRU");
        f.restart(); eq(64,f.images.count(),"persisted cache");
    }

    static void imageSample() {
        for (int[] bounds : new int[][]{{1920,1080,1},{1921,1,2},{1920,1081,2},{3841,2160,4},
                {1,Integer.MAX_VALUE,2097152},{0,1,0}}) {
            if (bounds[2]==0) failure(() -> ImageStore.sampleSize(bounds[0],bounds[1]),400,"bad image");
            else eq(bounds[2],ImageStore.sampleSize(bounds[0],bounds[1]),"decode sample");
        }
    }

    static Reply rawImage(Fake f,String id,byte[] bytes) throws IOException {
        byte[] head = bytes("PUT /v1/images/"+id+" HTTP/1.1\r\n"+bearer(f.token)+"Content-Length: "+bytes.length+"\r\n\r\n");
        return run(f.api,new ByteArrayInputStream(concat(head,bytes)));
    }

    static CardSpec videoSpec(Fake f, Map<String, Object> video) {
        return new CardSpec(Json.fields("id", "cam", "category", "camera", "video", video), f.settings);
    }

    static void videoFields() {
        Fake f = new Fake();
        for (String scheme : List.of("rtsp","http","https")) {
            for (int seconds : List.of(3,60)) {
                Map<String,Object> video = Json.fields("url",scheme + "://fixture.invalid/live","timeout",seconds,"tcp",true);
                eq(video, videoSpec(f, video).video, "accepted video");
            }
        }
        for (String scheme : List.of("rtsps","ftp","file"))
            failure(() -> videoSpec(f, Json.fields("url", scheme + "://fixture.invalid/live")),400,"bad video");
        for (Object seconds : List.of(2,61,3.5,"12"))
            failure(() -> videoSpec(f, Json.fields("url","http://fixture.invalid","timeout",seconds)),400,"bad video");
    }

    static void buttonFields() {
        Fake f = new Fake();
        List<Object> buttons = new ArrayList<>();
        for (String action : List.of("event","expand","dismiss"))
            buttons.add(Json.fields("id",action,"label","a".repeat(24),"action",action));
        f.post("a","buttons",buttons);
        buttons.add(Json.fields("id","four","label","four"));
        failure(() -> f.post("a","buttons",buttons),400,"bad buttons");
        for (Map<String,Object> b : List.of(Json.fields("label","x"),Json.fields("id","x","label","x".repeat(25)),
                Json.fields("id","x","label","x","action","open")))
            failure(() -> f.post("a","buttons",List.of(b)),400,"bad buttons");
    }

    static CardSpec buttonCard(Fake f, String id, String action, Object... styleFields) {
        Map<String,Object> button = Json.fields("id","go","label","Go");
        if (action != null) button.put("action",action);
        Map<String,Object> style = Json.fields("stacking","stack");
        style.putAll(Json.fields(styleFields));
        CardSpec spec = new CardSpec(Json.fields("id",id,"title","test","style",style,"buttons",List.of(button)),f.settings);
        f.cards.post(spec);
        return spec;
    }

    static void buttonDefault() {
        eq("event",buttonCard(new Fake(),"a",null).buttons.get(0).get("action"),"default");
    }

    static void buttonFocus() {
        Fake f = new Fake();
        f.settings.put(Json.fields("display",Json.fields("max_visible",4)));
        CardSpec a = buttonCard(f,"a",null);
        CardSpec b = buttonCard(f,"b",null);
        buttonCard(f,"c",null,"focus","never");
        f.post("plain","style",Json.fields("stacking","stack"));
        eq(b,f.cards.focusOwner(),"newest eligible");
        f.cards.press(a,0);
        state(f,"a","showing",null);
        f.cards.delete("b");
        eq(a,f.cards.focusOwner(),"older regains focus");
        f.cards.delete("a");
        eq(null,f.cards.focusOwner(),"never remains passive");
    }

    static void buttonEvent() throws IOException {
        Fake f = new Fake();
        CardSpec card = buttonCard(f,"a",null);
        long start = f.events.latest();
        f.cards.press(card,0);
        f.cards.press(card,0);
        List<?> events = f.since(start);
        eq(2,events.size(),"one button and one end");
        Map<?,?> first = (Map<?,?>) events.get(0);
        eq("card.button",first.get("type"),"button first");
        eq("go",first.get("button"),"button id");
        eq("card.ended",((Map<?,?>)events.get(1)).get("type"),"then ended");
        f.now += 599999;
        Map<?,?> detail = ok(f.route("GET","cards/a",Json.fields()));
        eq("button",detail.get("reason"),"reason");
        eq("go",detail.get("button"),"remembered choice");
        f.now++;
        failure(() -> f.cards.detail("a"),404,"unknown card");
        f.cards.press(buttonCard(f,"b","dismiss"),0);
        state(f,"b","ended","dismissed");
    }

    static void buttonExpand() {
        Fake f = new Fake();
        for (int duration : List.of(3,80,0)) {
            CardSpec card = buttonCard(f,"a","expand","duration",duration);
            CardBoard.Entry e = f.cards.showing().get(0);
            long deadline = e.deadline;
            f.now += 1000;
            f.cards.press(card,0);
            check(e.expanded && f.cards.focusOwner() == card,"fullscreen and focused");
            eq(Math.max(deadline,f.now+60000),e.deadline,"expanded timer");
            long expandedDeadline = e.deadline;
            f.now += 1000;
            f.cards.press(card,0);
            check(!e.expanded && f.cards.focusOwner() == card,"minimized and still focused");
            eq(expandedDeadline,e.deadline,"minimize preserves timer");
            f.cards.press(card,0);
            check(e.expanded,"can expand again");
            f.cards.back(card,f.now);
            state(f,"a","ended","dismissed");
        }
        CardSpec initialFull = buttonCard(f, "full", "expand", "placement", "fullscreen");
        CardBoard.Entry initial = f.cards.showing().get(0);
        check(initial.expanded, "fullscreen request starts expanded");
        f.cards.press(initialFull, 0);
        check(!initial.expanded && f.cards.focusOwner() == initialFull, "fullscreen request can minimize");
    }

    static final class Playback implements MediaState.Output {
        final Fake f;
        final CardSpec spec;
        final MediaState state;
        final long token;
        int releases;
        String caption = "";
        boolean playing;
        Playback(Fake f, boolean muted) {
            this.f = f;
            spec = videoSpec(f,Json.fields("url","rtsp://fixture.invalid/live","muted",muted));
            f.cards.post(spec);
            state = new MediaState(spec,f,this);
            token = state.generation;
        }
        public void show(boolean live, String text) { playing = live; caption = text; }
        public void releasePlayer() { releases++; }
        public void receipt(String value, boolean after) { f.cards.videoMedia(spec,value,after); }
    }

    static void videoTimeout() {
        Fake f = new Fake();
        Playback p = new Playback(f,false);
        long start = f.events.latest(), deadline = f.cards.showing().get(0).deadline;
        p.state.audioAdvancing(p.token);
        p.state.timeout(p.token);
        p.state.firstFrame(p.token);
        eq(1,p.releases,"released");
        check(!p.playing && "Live view unavailable".equals(p.caption),"still with unavailable caption");
        eq(deadline,f.cards.showing().get(0).deadline,"card and duration kept");
        eq(1,f.since(start).size(),"one failure");
        Map<?,?> event = (Map<?,?>) f.since(start).get(0);
        eq("failed",event.get("state"),"failure");
        check(!event.containsKey("after_first_frame"),"no after-frame flag");
        eq(List.of(p.token+":true",p.token+":false"),f.videoAudio,"audio balanced");
    }

    static void videoEnded() {
        Fake f = new Fake();
        Playback p = new Playback(f,false);
        long cursor = f.events.latest();
        f.now += 47;
        p.state.firstFrame(p.token);
        p.state.firstFrame(p.token);
        p.state.timeout(p.token);
        check(p.playing && p.releases == 0,"playing despite old timeout");
        p.state.failed(p.token);  // Both onPlayerError and STATE_ENDED take this transition.
        check(!p.playing && p.releases == 1,"still restored and decoder released");
        eq("Live view ended",p.caption,"ended caption");
        List<?> events = f.since(cursor);
        eq(2,events.size(),"one playing then failed");
        Map<?,?> first = (Map<?,?>) events.get(0), last = (Map<?,?>) events.get(1);
        eq("video",first.get("kind"),"kind"); eq("playing",first.get("state"),"first frame");
        eq(47L,first.get("ms"),"time since acceptance");
        eq("failed",last.get("state"),"failed"); eq(true,last.get("after_first_frame"),"after frame");
        state(f,"cam","showing",null);
    }

    static void videoLate() {
        Fake f = new Fake();
        Playback old = new Playback(f,false);
        old.state.audioAdvancing(old.token);
        old.state.release();
        Playback fresh = new Playback(f,false);
        fresh.state.audioAdvancing(fresh.token);
        fresh.state.audioAdvancing(fresh.token);
        long start = f.events.latest();
        for (Playback p : List.of(old,fresh)) {
            p.state.firstFrame(old.token);
            p.state.timeout(old.token);
            p.state.audioAdvancing(old.token);
            p.state.failed(old.token);
        }
        old.state.release();
        eq(1,old.releases,"old release once");
        eq(0,fresh.releases,"new decoder untouched");
        eq(0,f.since(start).size(),"no stale receipts");
        fresh.state.failed(fresh.token);
        eq(List.of(old.token+":true",old.token+":false",fresh.token+":true",fresh.token+":false"),f.videoAudio,"balanced distinct owners");
    }

    static void videoSound() {
        Fake f = new Fake();
        for (String sound : List.of("on","off")) {
            f.settings.putCategory("camera",Json.fields("style",Json.fields("video_sound",sound)));
            Map<String,Object> video = Json.fields("url","rtsp://fixture.invalid/live");
            eq("off".equals(sound),MediaState.muted(videoSpec(f,video)),"effective category");
            for (boolean muted : List.of(true,false)) {
                video.put("muted",muted);
                eq(muted,MediaState.muted(videoSpec(f,video)),"explicit override");
            }
        }
        Playback p = new Playback(f,true);
        p.state.audioAdvancing(p.token);
        p.state.release();
        check(f.videoAudio.isEmpty(),"muted owns no audio");
    }

    static void buttonBack() {
        Fake f = new Fake();
        CardSpec a = buttonCard(f,"old",null), b = buttonCard(f,"new",null);
        f.cards.back(b,1000);
        f.cards.back(a,1299);
        eq(a,f.cards.focusOwner(),"second path ignored after focus transfers");
        f.cards.back(a,1300);
        state(f,"old","ended","dismissed"); state(f,"new","ended","dismissed");
    }

    static void buttonPaused() {
        Fake f = new Fake();
        CardSpec card = buttonCard(f,"a",null);
        f.playing = true;
        eq(card,f.cards.focusOwner(),"default while playing");
        f.settings.put(Json.fields("display",Json.fields("focus_while_playing",false)));
        check(f.cards.pauseToUseButtons() && f.cards.focusOwner() == null,"restricted");
        f.cards.press(card,0);
        f.playing = false;
        eq(card,f.cards.focusOwner(),"eligible as soon as quiet");
    }

    static void imageRoutes() throws Exception {
        Fake f = new Fake(); f.token=f.issue("test");
        error(run(f.api,stream("PUT /v1/images/.bad HTTP/1.1\r\nContent-Length: 2097153\r\n\r\n")),413,"body too large");
        error(rawImage(f,".bad",new byte[0]),400,"bad key");
        error(rawImage(f,"valid",new byte[0]),400,"empty body");
        error(rawImage(f,"valid",new byte[]{1}),400,"bad image");
        error(f.route("GET","images/.bad",Json.fields()),400,"bad key");
        error(f.route("GET","images/valid",Json.fields()),404,"not cached");
        byte[] png=bitmap();
        Map<String,Object> shape=Json.fields("ok",true,"size",(double) png.length);
        eq(shape,ok(rawImage(f,"valid",png)),"put shape");
        eq(shape,ok(f.route("GET","images/valid",Json.fields())),"get shape");
        File dir=new File(f.directory,"images");
        deleteTree(dir); check(dir.createNewFile(),"inject cache write failure");
        error(rawImage(f,"another",png),500,"store failed");
        for (Map<String,Object> image : List.of(Json.fields("key",".bad"),Json.fields("url","file:///fixture"),
                Json.fields("data","!"),Json.fields("data",Base64.getEncoder().encodeToString(new byte[1048577])),
                Json.fields("key","valid","url","http://fixture.invalid")))
            error(f.route("POST","cards",Json.fields("image",image)),400,"bad image");
        for (Map<String,Object> image : List.of(Json.fields("key","missing"),Json.fields("data","AQ=="))) {
            long cursor=f.events.latest();
            eq("showing",ok(f.route("POST","cards",Json.fields("id","media","image",image))).get("state"),"valid reference admitted");
            List<?> received = f.since(cursor);
            Map<?,?> event = (Map<?,?>) received.get(received.size() - 1);
            eq("card.media",event.get("type"),"async media result");
            eq("image",event.get("kind"),"image result"); eq("failed",event.get("state"),"failed image");
        }
        f.screen=false; f.cards.screen(false);
        long cursor=f.events.latest();
        int attempts=f.imageAttempts.size();
        eq("waiting",ok(f.route("POST","cards",Json.fields("category","alarm","id","wait","image",
                Json.fields("url","https://fixture.invalid/image")))).get("state"),"URL card waits");
        eq(attempts+1,f.imageAttempts.size(),"fetch scheduled at acceptance");
        eq("failed",((Map<?,?>) f.since(cursor).get(0)).get("state"),"fetch fails while waiting");
        f.screen=true; f.cards.screen(true);
        eq(attempts+1,f.imageAttempts.size(),"no retry on wake");
        CardSpec spec=f.cards.showing().get(0).spec;
        f.overlay=false; f.cards.failed(spec);
        state(f,spec.id,"ended","failed");
        error(f.route("POST","cards",Json.fields("title","fixture")),409,"overlay not allowed");
    }

    static AnnounceSpec spec(Fake f, Object... fields) {
        return new AnnounceSpec(Json.fields(fields), f.settings);
    }

    static void announceSource() {
        Fake f = new Fake();
        failure(() -> spec(f), 400, "bad source");
        failure(() -> spec(f, "audio", null, "url", null), 400, "bad source");
        check(spec(f, "url", "https://example.invalid/a").url != null, "URL source");
    }

    static void announceOrder() {
        Fake f = new Fake();
        failure(() -> spec(f, "volume", "loud"), 400, "bad json");
        failure(() -> spec(f, "output", "other"), 400, "bad source");
        failure(() -> spec(f, "clip", "/", "output", "other"), 400, "bad output");
        failure(() -> spec(f, "text", " ", "chime", "clip:/"), 400, "bad key");
        failure(() -> spec(f, "text", " ", "card", null), 400, "text is empty");
        failure(() -> spec(f, "audio", "!abc", "card", null), 400, "bad audio");
        failure(() -> spec(f, "url", "file:///bad", "card", null), 400, "bad url");
        failure(() -> spec(f, "clip", "missing", "card", Json.fields("id", "caller"), "request_id", "/"), 400, "bad card");
        String big = Base64.getEncoder().encodeToString(new byte[2097153]);
        failure(() -> spec(f, "audio", big, "request_id", "/"), 400, "bad request id");
    }

    static void announceVolume() {
        Fake f = new Fake();
        Map<String,Object> config = AnnounceSpec.audioSettings(f.settings);
        eq(70, spec(f,"text","a").volume("tv",config), "TV default");
        eq(50, spec(f,"text","a").volume("speaker",config), "speaker default");
        eq(0, spec(f,"text","a","volume",-2).volume("tv",config), "clamped low");
        eq(100, spec(f,"text","a","volume",101).volume("tv",config), "clamped high");
    }

    static void announceNight() {
        Fake f = new Fake();
        Map<String,Object> config = new LinkedHashMap<>(AnnounceSpec.audioSettings(f.settings));
        AnnounceSpec ordinary = spec(f,"text","a"), urgent = spec(f,"text","a","urgent",true);
        failure(() -> ordinary.checkNight("speaker",true,config), 409, "quiet hours");
        ordinary.checkNight("speaker",false,config);
        ordinary.checkNight("tv",true,config);
        urgent.checkNight("speaker",true,config);
        config.put("speaker_at_night","never");
        failure(() -> urgent.checkNight("speaker",true,config), 409, "quiet hours");
        config.put("speaker_at_night","always");
        ordinary.checkNight("speaker",true,config);
    }

    static void announceErrors() throws Exception {
        Fake f = new Fake();
        Map<String,Object> voice = Json.fields("text","a","request_id","retry");
        f.hasOutput = f.ready = false;
        error(f.route("POST","announce",Json.fields("clip","missing")),404,"not cached");
        error(f.route("POST","announce",voice),409,"output not available");
        f.hasOutput = true;
        error(f.route("POST","announce",voice),503,"speech engine not ready");
        f.ready = true;
        for (int i=0; i<6; i++) ok(f.route("POST","announce",Json.fields("text","a")));
        error(f.route("POST","announce",voice),429,"busy");
        ok(f.route("POST","stop",Json.fields()));
        File tmp = new File(f.directory,"request-ids.json.tmp");
        check(tmp.mkdir(), "block store");
        error(f.route("POST","announce",voice),500,"store failed");
        check(!ok(f.route("POST","announce",voice)).containsKey("duplicate"), "rejections never reserve");
    }

    static void announceText() {
        Fake f = new Fake();
        eq("word", spec(f,"text"," word ").text,"trim");
        eq("x".repeat(500), spec(f,"text","x".repeat(501)).text,"cut");
        eq(499, spec(f,"text","x".repeat(499) + "😀").text.length(),"intact code point");
    }

    static void announceAudio() {
        Fake f = new Fake();
        String exact = Base64.getEncoder().encodeToString(new byte[2097152]);
        eq(2097152,spec(f,"audio",exact.substring(0,8) + " \r\n" + exact.substring(8)).audio.length,"limit inclusive");
        failure(() -> spec(f,"audio",Base64.getEncoder().encodeToString(new byte[2097153])),413,"audio too large");
        failure(() -> spec(f,"audio"," "),400,"bad audio");
    }

    static AnnounceSpec withCard(Fake f, Object... style) {
        return spec(f,"text","a","card",Json.fields("message","shown","style",Json.fields(style)));
    }

    static void announceCard() {
        Fake f = new Fake();
        AnnounceSpec job = withCard(f,"duration",3);
        f.announce(job);
        check(f.cards.rows().isEmpty(), "card hidden on admission");
        f.jobs.start().show(f.cards, 4040);
        f.now += 7039;
        state(f,job.card.id,"showing",null);
        f.now++;
        state(f,job.card.id,"ended","expired");
        AnnounceSpec failed = withCard(f,"duration",10);
        failed.show(f.cards,100);
        failed.endCard(f.cards,"error");
        state(f,failed.card.id,"ended","failed");
    }

    static void announceDuck() {
        Fake f = new Fake();
        eq(null, spec(f,"text","a").focus(() -> { throw new AssertionError(); }),"no focus by default");
        eq("asked",spec(f,"text","a","duck",true).focus(() -> "asked"),"requests focus");
    }

    static Map<String,Object> admit(Fake f, String id) {
        return f.requestIds.admit(id, () -> {}, () -> false, job -> f.admissions++);
    }

    static void announceRepeat() {
        Fake f = new Fake();
        Map<String,Object> initial = admit(f,"retry");
        String job = (String) initial.get("job");
        f.requestIds.state("retry",job,"played");
        f.now += 599999;
        Map<String,Object> repeat = admit(f,"retry");
        eq(job,repeat.get("job"),"original job");
        eq("played",repeat.get("outcome"),"original outcome");
        eq(true,repeat.get("duplicate"),"repeat");
        eq(1,f.admissions,"once");
        f.now++;
        check(!admit(f,"retry").containsKey("duplicate"),"expires at 600 seconds");
    }

    static void announceRace() throws Exception {
        Fake f = new Fake();
        CountDownLatch go = new CountDownLatch(1);
        java.util.concurrent.Callable<Map<String,Object>> send = () -> { go.await(); return admit(f,"race"); };
        FutureTask<Map<String,Object>> one = new FutureTask<>(send), two = new FutureTask<>(send);
        new Thread(one).start();
        new Thread(two).start();
        go.countDown();
        Map<String,Object> a = one.get(5,TimeUnit.SECONDS), b = two.get(5,TimeUnit.SECONDS);
        eq(a.get("job"),b.get("job"),"one reservation");
        check(a.containsKey("duplicate") != b.containsKey("duplicate"),"one duplicate");
        eq(1,f.admissions,"one job");
    }

    static void announceCapacity() {
        Fake f = new Fake();
        for (int i=0; i<256; i++) {
            String id = "id"+i, job = (String) admit(f,id).get("job");
            f.requestIds.state(id,job,"played");
        }
        f.restart();
        failure(() -> admit(f,"full"),429,"too many request ids");
        eq("played",admit(f,"id255").get("outcome"),"persisted outcome");
        admit(f,null);
        f.now += 600000;
        admit(f,"full");
    }

    static void announceLast() throws Exception {
        Fake f = new Fake();
        eq("none",ok(f.route("GET","status",Json.fields())).get("last_audio"),"initial");
        AnnounceSpec clip = spec(f,"clip","sample");
        for (String value : new String[]{"playing sample", clip.played(), spec(f,"text","a").played(),
                spec(f,"audio","YQ==").played(), spec(f,"audio","YQ==").failure("play"),clip.failure("stopped")}) {
            f.lastAudio = value;
            eq(value,ok(f.route("GET","status",Json.fields())).get("last_audio"),"status passthrough");
        }
        eq("played sample",clip.played(),"clip outcome");
        eq("said",spec(f,"text","a").played(),"speech outcome");
        eq("played voice",spec(f,"audio","YQ==").played(),"voice outcome");
        eq("error voice",spec(f,"audio","YQ==").failure("stopped"),"voice error");
    }

    static void announceSound() {
        Fake f = new Fake();
        withCard(f,"sound","invalid but ignored").show(f.cards,1000);
        f.attach();
        eq(0,f.sounds,"no recursive sound");
        check(AnnounceSpec.cardSound("chime",40).chime,"internal chime");
        AnnounceSpec internal = AnnounceSpec.cardSound("clip:note",40);
        eq("note",internal.clip,"internal clip");
        check(!internal.chime,"no extra chime");
    }

    static void announceChime() {
        Fake f = new Fake();
        check(spec(f,"text","a").chime,"default on");
        f.settings.put(Json.fields("audio",Json.fields("chime",false)));
        check(!spec(f,"text","a").chime,"settings default off");
        eq("tone",spec(f,"text","a","chime","clip:tone").chimeKey,"explicit cached chime");
    }

    static void announceLifetime() {
        Fake f = new Fake();
        AnnounceSpec sticky = withCard(f,"duration",0);
        sticky.show(f.cards,1000);
        sticky.endCard(f.cards,"played");
        f.now += 60000;
        state(f,sticky.card.id,"showing",null);
        f.cards.delete(sticky.card.id);
        AnnounceSpec timed = withCard(f,"duration",7);
        timed.show(f.cards,-1);
        f.now += 6999;
        state(f,timed.card.id,"showing",null);
        f.now++;
        state(f,timed.card.id,"ended","expired");
    }

    static void announcePrecedence() throws Exception {
        Fake f = new Fake();
        Map<String,Object> doc = Json.fields("text","a","request_id","one");
        String job = (String) ok(f.route("POST","announce",doc)).get("job");
        for (int i=1; i<256; i++) admit(f,"id"+i);
        while (!f.jobs.full()) f.jobs.add(spec(f,"text","b"));
        f.hasOutput = f.ready = false;
        for (String source : List.of("clip","text"))
            eq(job,ok(f.route("POST","announce",Json.fields(source,"missing","request_id","one"))).get("job"),"known id first");
        f.hasOutput = f.ready = true;
        error(f.route("POST","announce",Json.fields("text","a","request_id","new")),429,"too many request ids");
    }

    static void announceRecovery() {
        Fake f = new Fake();
        String queued = (String) admit(f,"queued").get("job"), playing = (String) admit(f,"playing").get("job");
        f.requestIds.state("playing",playing,"playing");
        eq("playing",admit(f,"playing").get("outcome"),"in progress");
        f.restart();
        check(!queued.equals(admit(f,"queued").get("job")),"queued released");
        Map<String,Object> duplicate = admit(f,"playing");
        eq(playing,duplicate.get("job"),"same interrupted job");
        eq("interrupted",duplicate.get("outcome"),"interrupted on restart");
        f.restart();
        eq("interrupted",admit(f,"playing").get("outcome"),"durable recovery");
        eq(3,f.admissions,"no replay");
    }

    static void announceStore() throws Exception {
        Fake f = new Fake();
        File journal = new File(f.directory,"request-ids.json");
        check(journal.mkdir(), "rename must fail");
        failure(() -> admit(f,"new"),500,"store failed");
        eq(0,f.admissions,"no admission");
        check(!new File(f.directory,"request-ids.json.tmp").exists(), "temporary journal removed");
        check(journal.delete(),"repair destination");
        Map<String,Object> reply = f.requestIds.admit("new", () -> {}, () -> false, job -> {
            try { check(Files.readString(journal.toPath()).contains(job), "committed before enqueue"); }
            catch (IOException e) { throw new AssertionError(e); }
        });
        check(!reply.containsKey("duplicate"),"failed reservation rolled back");
    }

    static void announceClips() throws Exception {
        Fake f = new Fake();
        String token = bearer(f.issue("fixture"));
        String path = "/v1/clips/" + "a".repeat(154) + ".tmp-1";
        error(f.request("GET",path+"x",token,""),400,"bad key");
        error(f.request("GET",path,token,""),404,"not cached");
        error(f.request("PUT",path,token,""),400,"empty body");
        eq(4.0,ok(f.request("PUT",path,token,"data")).get("size"),"put size");
        eq(4.0,ok(f.request("GET",path,token,"")).get("size"),"get size");
        eq(1,f.clips.count(),"valid key with old temporary marker counts");
        for (int i=0; i<200; i++) f.clips.put("clip"+i,new byte[]{1});
        eq(200,f.clips.count(),"200 clip cap");
    }

    static void announceStop() throws Exception {
        Fake f = new Fake();
        AnnounceSpec current = withCard(f,"duration",0);
        f.announce(current);
        f.jobs.start().show(f.cards,0);
        f.announce(spec(f,"text","queued"));
        ok(f.route("POST","stop",Json.fields()));
        state(f,current.card.id,"ended","cleared");
        check(!f.jobs.busy() && f.jobs.start() == null,"queue drained");
    }

    static final class Reply {
        int code;
        String body;
    }

    /** Runs Api.handle and checks the response's form: status line, three headers, a JSON body. */
    static Reply run(Api api, InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        api.handle(in, out);
        Reply r = parseResponse(out);
        Map<String, Object> json = Json.parse(r.body);
        eq(r.code == 200, json.get("ok"), "ok matches the status");
        if (r.code != 200) check(json.get("error") instanceof String, "an error string");
        return r;
    }

    static Reply parseResponse(ByteArrayOutputStream out) {
        byte[] all = out.toByteArray();
        String text = new String(all, StandardCharsets.ISO_8859_1);
        int split = text.indexOf("\r\n\r\n");
        check(split > 0, "a response head");
        String[] head = text.substring(0, split).split("\r\n");
        byte[] body = Arrays.copyOfRange(all, split + 4, all.length);
        Map<String, String> headers = new LinkedHashMap<>();
        for (int i = 1; i < head.length; i++) {
            int colon = head[i].indexOf(": ");
            headers.put(head[i].substring(0, colon), head[i].substring(colon + 2));
        }
        Reply r = new Reply();
        String[] status = head[0].split(" ", 3);
        eq("HTTP/1.1", status[0], "status line");
        r.code = Integer.parseInt(status[1]);
        eq(Http.reason(r.code), status[2], "reason phrase");
        eq("application/json", headers.get("Content-Type"), "Content-Type");
        eq(String.valueOf(body.length), headers.get("Content-Length"), "Content-Length");
        eq("close", headers.get("Connection"), "Connection");
        r.body = new String(body, StandardCharsets.UTF_8);
        return r;
    }

    static void error(Reply r, int code, String error) {
        eq(code, r.code, "error status");
        eq(error, Json.parse(r.body).get("error"), "error message");
    }

    /** Counts what is read. */
    static class Counting extends InputStream {
        final byte[] data;
        int pos;
        long bytes;
        int calls;

        Counting(byte[] data) {
            this.data = data;
        }

        @Override public int read() {
            calls++;
            if (pos >= data.length) return -1;
            bytes++;
            return data[pos++] & 0xff;
        }

        @Override public int read(byte[] b, int off, int len) {
            calls++;
            if (len == 0) return 0;
            if (pos >= data.length) return -1;
            int n = Math.min(len, Math.min(chunk(), data.length - pos));
            System.arraycopy(data, pos, b, off, n);
            pos += n;
            bytes += n;
            return n;
        }

        int chunk() {
            return Integer.MAX_VALUE;
        }
    }

    /** Delivers at most size bytes per read, as a slow network does. */
    static final class Pieces extends Counting {
        final int size;

        Pieces(byte[] data, int size) {
            super(data);
            this.size = size;
        }

        @Override int chunk() {
            return size;
        }
    }

    interface Action {
        void run() throws IOException;
    }

    static void badRequest(Action a, String what) {
        try {
            a.run();
        } catch (Http.BadRequest e) {
            return;
        } catch (IOException e) {
            throw new AssertionError(what + ": wrong exception " + e);
        }
        throw new AssertionError(what + ": accepted");
    }

    static void rejects(Runnable r, String what) {
        try {
            r.run();
        } catch (IllegalArgumentException e) {
            return;
        }
        throw new AssertionError(what + ": accepted");
    }

    static void age(ClipStore s, String key, long when) {
        check(s.file(key).setLastModified(when), "set last-modified of " + key);
    }

    static List<String> sorted(String[] names) {
        List<String> l = new ArrayList<>(Arrays.asList(names));
        l.sort(null);
        return l;
    }

    static void deleteTree(File f) {
        File[] children = f.listFiles();
        if (children != null) for (File c : children) deleteTree(c);
        f.delete();
    }

    static ByteArrayInputStream stream(String s) {
        return new ByteArrayInputStream(bytes(s));
    }

    static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    static byte[] concat(byte[] a, byte[] b) {
        byte[] all = Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, all, a.length, b.length);
        return all;
    }

    static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError(what);
    }

    static void eq(Object want, Object got, String what) {
        if (want == null ? got != null : !want.equals(got)) {
            throw new AssertionError(what + ": want [" + want + "] got [" + got + "]");
        }
    }

    static void pass(String name) {
        System.out.println("PASS " + name);
    }
}
