package co.tbrkr.herald;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;

/** The optional TvOverlay protocol. Only this listener accepts allowlisted, unpaired senders. */
final class Compat {
    private static final int WEEK = 604800;
    private final Settings settings;
    private final Backend backend;
    private final RequestIds ids;
    private final CardBoard cards;
    private final ChipBoard chips;
    private final LongSupplier clock;

    Compat(Settings settings, CardBoard cards, ChipBoard chips, LongSupplier clock, Backend backend, RequestIds ids) {
        this.backend = backend;
        this.ids = ids;
        this.settings = settings;
        this.cards = cards;
        this.chips = chips;
        this.clock = clock;
    }

    static Map<String,Object> config(Map<String,Object> document) {
        return Settings.object(Settings.object(document.get("compat"), "compat").get("tvoverlay"), "tvoverlay");
    }

    private Map<String,Object> authorize(String peer, int port) {
        Map<String,Object> cfg = config(settings.document());
        if ((boolean)cfg.get("enabled") && ((Number)cfg.get("port")).intValue() == port) {
            for (Object range : (List<?>)cfg.get("allowed")) {
                if (Cidr.parse((String)range).matches(peer)) return cfg;
            }
        }
        throw new Api.Failure(403, "sender not allowed");
    }

    void handle(String peer, int port, InputStream in, OutputStream out) throws IOException {
        int status = 200;
        String message;
        try {
            authorize(peer, port);
            Http.Request request = Http.readHead(in);
            int query = request.target.indexOf('?');
            String path = query < 0 ? request.target : request.target.substring(0, query);
            boolean card = path.equals("/notify");
            boolean fixed = path.equals("/notify_fixed");
            if (request.contentLength > (card || fixed ? 1572864 : 65536))
                throw new Api.Failure(413, "body too large");
            if (!card && !fixed && !path.equals("/set/overlay") && !path.equals("/set/notifications")
                    && !path.equals("/set/settings") && !path.equals("/set/mqtt"))
                throw new Api.Failure(404, "unknown path");
            if (!request.method.equals("POST")) throw new Api.Failure(405, "method not allowed");
            if (card || fixed) {
                Map<String,Object> doc = Api.body(request, in);
                // A sender may have lost permission while uploading.
                Map<String,Object> cfg = authorize(peer, port);
                try {
                    if (card) SpokenCards.post(card(doc, (String)cfg.get("category")), false, null,
                            settings, backend, cards, ids);
                    else fixed(doc);
                } catch (Api.Failure failure) {
                    throw new Api.Failure(400, failure.getMessage());
                }
                message = "Notification received";
            } else {
                Http.readBody(in, (int)request.contentLength);
                message = "ignored by Herald";
            }
        } catch (Http.BadRequest invalid) {
            status = 400;
            message = "bad request";
        } catch (Api.Failure failure) {
            status = failure.status;
            message = failure.getMessage();
        }
        Http.write(out, status, Json.stringify(Json.fields("success", status == 200, "message", message)));
    }

    private static void copy(Map<String,Object> src, String key, Map<String,Object> dest, String target) {
        if (src.containsKey(key)) dest.put(target, src.get(key));
    }

    private CardSpec card(Map<String,Object> src, String category) {
        Map<String,Object> doc = Json.fields("category", category);
        for (String key : new String[]{"id", "title", "message"}) copy(src, key, doc, key);
        copy(src, "source", doc, "from");
        for (String key : new String[]{"image", "largeIcon"}) {
            if (!src.containsKey(key)) continue;
            Object image = src.get(key);
            if (!(image instanceof String)) throw CardSpec.bad("image");
            String s = (String)image;
            if (s.isEmpty() || s.startsWith("mdi:")) continue;
            boolean url = s.regionMatches(true, 0, "http://", 0, 7) || s.regionMatches(true, 0, "https://", 0, 8);
            doc.put("image", Json.fields(url ? "url" : "data", s));
            break;
        }
        if (src.containsKey("video")) doc.put("video", Json.fields("url", src.get("video")));
        Map<String,Object> style = Json.fields();
        copy(src, "duration", style, "duration");
        if (src.containsKey("corner")) {
            Object corner = src.get("corner");
            String placement;
            if ("top_start".equals(corner)) placement = "top_left";
            else if ("top_end".equals(corner)) placement = "top_right";
            else if ("bottom_start".equals(corner)) placement = "bottom_left";
            else if ("bottom_end".equals(corner)) placement = "bottom_right";
            else throw CardSpec.bad("style");
            style.put("placement", placement);
        }
        doc.put("style", style);
        return new CardSpec(doc, settings);
    }

    private void fixed(Map<String,Object> src) {
        if (!CardSpec.key(src.get("id"))) throw CardSpec.bad("id");
        String id = (String)src.get("id");
        Object visible = src.getOrDefault("visible", true);
        if (!(visible instanceof Boolean)) throw CardSpec.bad("chip");
        if (!(boolean)visible) {
            chips.delete(id);
            return;
        }
        int ttl = expiration(src.getOrDefault("expiration", 0), clock.getAsLong() / 1000);
        if (ttl < 0) {
            chips.delete(id);
            return;
        }
        Map<String,Object> doc = Json.fields("expires", ttl);
        copy(src, "message", doc, "text");
        copy(src, "iconColor", doc, "color");
        copy(src, "icon", doc, "icon");
        chips.put(id, doc);
    }

    // Negative means delete. Saturate as we parse so even a long duration cannot overflow.
    private static int expiration(Object value, long now) {
        if (value instanceof Number) {
            double n = ((Number)value).doubleValue();
            if (n != Math.rint(n)) throw CardSpec.bad("chip");
            if (n >= 1000000000) {
                if (n <= now) return -1;
                n -= now;
            }
            return n < 0 ? -1 : (int)Math.min(WEEK, n);
        }
        if (!(value instanceof String)) throw CardSpec.bad("chip");
        String s = (String)value;
        if (s.isEmpty()) throw CardSpec.bad("chip");
        int[] scales = {365 * 86400, WEEK, 86400, 3600, 60, 1};
        int previous = -1, total = 0;
        for (int i = 0; i < s.length();) {
            int start = i, count = 0;
            while (i < s.length() && s.charAt(i) >= '0' && s.charAt(i) <= '9') {
                count = Math.min(WEEK, count * 10 + s.charAt(i++) - '0');
            }
            if (i == start || i == s.length()) throw CardSpec.bad("chip");
            int unit = "ywdhms".indexOf(s.charAt(i++));
            if (unit <= previous) throw CardSpec.bad("chip");
            total = (int)Math.min(WEEK, total + (long)count * scales[unit]);
            previous = unit;
        }
        return total;
    }
}
