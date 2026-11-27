package co.tbrkr.herald;

import java.net.URI;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/** Structurally validated before consulting the id table or any live device state. */
final class AnnounceSpec {
    final String clip, text, url, output, requestId, chimeKey;
    final byte[] audio;
    final boolean chime, duck, urgent;
    final CardSpec card;
    private final Integer volume;
    final boolean spokenCard;

    AnnounceSpec(Map<String, Object> doc, Settings settings) {
        this(doc, settings, null);
    }

    static AnnounceSpec forCard(Map<String,Object> options, Settings settings, CardSpec card) {
        return new AnnounceSpec(options, settings, card);
    }

    private AnnounceSpec(Map<String,Object> doc, Settings settings, CardSpec owner) {
        spokenCard = owner != null;
        Object level = doc.get("volume");
        Object tone = doc.getOrDefault("chime", audioSettings(settings).get("chime"));
        Object ducks = doc.getOrDefault("duck", false), priority = doc.getOrDefault("urgent", false);
        if ((doc.containsKey("volume") && (!(level instanceof Number)
                || Math.rint(((Number) level).doubleValue()) != ((Number) level).doubleValue()))
                || !(tone instanceof Boolean || tone instanceof String)
                || !(ducks instanceof Boolean) || !(priority instanceof Boolean)
                || doc.containsKey("text") && !(doc.get("text") instanceof String)) throw CardSpec.bad("json");
        volume = level == null ? null : (int) Math.max(0, Math.min(100, ((Number) level).doubleValue()));
        duck = (boolean) ducks;
        urgent = (boolean) priority;
        int sources = 0;
        for (String source : new String[]{"clip","text","audio","url"}) if (doc.containsKey(source)) sources++;
        boolean derived = spokenCard && sources == 0;
        if (!derived && sources != 1) throw CardSpec.bad("source");
        Object route = doc.getOrDefault("output", "auto");
        if (!"auto".equals(route) && !"speaker".equals(route) && !"tv".equals(route)) throw CardSpec.bad("output");
        output = (String) route;
        if (doc.containsKey("clip") && (!(doc.get("clip") instanceof String)
                || !ClipStore.validKey((String) doc.get("clip")))) throw CardSpec.bad("key");
        clip = (String) doc.get("clip");
        if (tone instanceof String && (!((String) tone).startsWith("clip:")
                || !ClipStore.validKey(((String) tone).substring(5)))) throw CardSpec.bad("key");
        chimeKey = tone instanceof String ? ((String) tone).substring(5) : null;
        chime = !Boolean.FALSE.equals(tone);
        String trimmed = doc.containsKey("text") ? ((String) doc.get("text")).trim() : null;
        if ("".equals(trimmed)) throw new Api.Failure(400, "text is empty");
        audio = doc.containsKey("audio") ? decode(doc.get("audio")) : null;
        url = doc.containsKey("url") ? url(doc.get("url")) : null;
        CardSpec nested = doc.containsKey("card") ? card(doc.get("card"), settings) : null;
        if (doc.containsKey("request_id") && !CardSpec.key(doc.get("request_id"))) throw CardSpec.bad("request id");
        requestId = (String) doc.get("request_id");
        if (audio != null && audio.length > 2097152) throw new Api.Failure(413, "audio too large");
        text = derived ? SpokenCards.spokenText(owner.title, owner.message)
                : trimmed == null ? null : SpokenCards.cut(trimmed, 500);
        if (spokenCard && !owner.idGiven && requestId != null) owner = owner.withId(requestId);
        card = spokenCard ? owner.silent() : nested;
    }

    private AnnounceSpec(String sound, int volume) {
        spokenCard = false;
        this.volume = volume;
        clip = sound.equals("chime") ? null : sound.substring(5);
        chime = clip == null;
        output = "auto";
        text = url = requestId = chimeKey = null;
        audio = null;
        card = null;
        duck = urgent = false;
    }

    static AnnounceSpec cardSound(String sound, int volume) { return new AnnounceSpec(sound, volume); }

    private static byte[] decode(Object value) {
        if (value instanceof String) {
            try {
                byte[] data = Base64.getDecoder().decode(((String) value).replaceAll("\\s+", ""));
                if (data.length > 0) return data;
            } catch (IllegalArgumentException ignored) { }
        }
        throw CardSpec.bad("audio");
    }

    private static String url(Object value) {
        if (value instanceof String) {
            try {
                URI u = URI.create((String) value);
                if (u.getHost() != null && ("http".equalsIgnoreCase(u.getScheme())
                        || "https".equalsIgnoreCase(u.getScheme()))) return (String) value;
            } catch (IllegalArgumentException ignored) { }
        }
        throw CardSpec.bad("url");
    }

    private static CardSpec card(Object value, Settings settings) {
        try {
            Map<String,Object> fields = new LinkedHashMap<>(CardSpec.object(value, "card"));
            if (fields.containsKey("id")) throw CardSpec.bad("card");
            Map<String,Object> style = new LinkedHashMap<>(CardSpec.object(fields.getOrDefault("style", Json.fields()), "card"));
            style.put("sound", "none");
            fields.put("style", style);
            return new CardSpec(fields, settings);
        } catch (Api.Failure invalid) { throw CardSpec.bad("card"); }
    }

    static Map<String,Object> audioSettings(Settings settings) { return Settings.object(settings.document().get("audio"), "audio"); }

    String checkOutput(java.util.function.Predicate<String> cached, java.util.function.Predicate<String> available,
            boolean night, Map<String,Object> config, java.util.function.BooleanSupplier engineReady) {
        if (clip != null && !cached.test(clip) || chimeKey != null && !cached.test(chimeKey))
            throw new Api.Failure(404, "not cached");
        String route = output.equals("auto") ? (available.test("tv") ? "tv" : "speaker") : output;
        if (!available.test(route)) throw new Api.Failure(409, "output not available");
        checkNight(route, night, config);
        if (text != null && !engineReady.getAsBoolean()) throw new Api.Failure(503, "speech engine not ready");
        return route;
    }

    <T> T focus(java.util.function.Supplier<T> request) { return duck ? request.get() : null; }

    int volume(String actualOutput, Map<String,Object> config) {
        return volume != null ? volume : (int) config.get("volume_" + actualOutput);
    }

    void checkNight(String actualOutput, boolean night, Map<String,Object> config) {
        Object rule = config.get("speaker_at_night");
        if (night && actualOutput.equals("speaker") && ("never".equals(rule) || "urgent".equals(rule) && !urgent))
            throw new Api.Failure(409, "quiet hours");
    }

    void show(CardBoard board, int lengthMs) {
        if (card != null) board.post(card, lengthMs < 0 || card.sticky() ? 0 : (long) lengthMs + 3000);
    }

    void endCard(CardBoard board, String outcome) {
        if (card == null || spokenCard) return;
        synchronized (board) {
            if (!board.owns(card)) return;
            if ("stopped".equals(outcome)) board.clearOwned(card);
            if ("error".equals(outcome)) board.failed(card);
        }
    }

    String played() { return clip != null ? "played " + clip : text != null ? "said" : "played voice"; }
    String failure(String why) { return audio != null || url != null ? "error voice" : "error " + why; }
}
