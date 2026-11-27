package io.github.iwinties.herald;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

final class Api {
    static final class Failure extends RuntimeException {
        final int status;

        Failure(int status, String message) {
            super(message);
            this.status = status;
        }
    }

    private final RequestIds ids;
    private final CardBoard cards;
    private final ChipBoard chips;
    private final ImageStore images;
    private final Backend backend;
    private final Auth auth;
    private final EventLog events;
    interface PortChange {
        void afterReply(int revision, int port);
    }

    private final Settings settings;
    private final PortChange portChange;
    private final String id, model, version;

    Api(Backend backend, Auth auth, EventLog events, Settings settings, PortChange portChange, String id, String model, String version,
            CardBoard cards, ChipBoard chips, ImageStore images, RequestIds ids) {
        this.ids = ids;
        this.cards = cards;
        this.chips = chips;
        this.images = images;
        this.backend = backend;
        this.auth = auth;
        this.events = events;
        this.id = id;
        this.settings = settings;
        this.portChange = portChange;
        this.model = model;
        this.version = version;
    }

    void handle(InputStream in, OutputStream out) throws IOException {
        Map<String, Object> result;
        int status = 200;
        try {
            Http.Request req = Http.readHead(in);
            int queryAt = req.target.indexOf('?');
            String path = queryAt < 0 ? req.target : req.target.substring(0, queryAt);
            if (req.contentLength > limit(req.method, path)) throw new Failure(413, "body too large");
            boolean pairing = path.equals("/v1/pair/start") || path.equals("/v1/pair/finish");
            boolean open = pairing && req.method.equals("POST") || path.equals("/v1/info") && req.method.equals("GET");
            Auth.Client client = open ? null : auth.authorize(req.authorization);
            if (path.equals("/v1/settings") && req.method.equals("PUT")) {
                putSettings(body(req, in), out);
                return;
            }
            result = dispatch(req, in, path, queryAt, client);
        } catch (Http.BadRequest e) {
            status = 400;
            result = Json.fields("ok", false, "error", "bad request");
        } catch (Settings.Invalid e) {
            status = 400;
            result = Json.fields("ok", false, "error", "bad settings", "field", e.field);
        } catch (Failure e) {
            status = e.status;
            result = Json.fields("ok", false, "error", e.getMessage());
        }
        Http.write(out, status, Json.stringify(result));
    }

    private static Map<String, Object> settingsResponse(Map<String, Object> document) {
        Map<String, Object> response = Json.fields("ok", true);
        response.putAll(document);
        return response;
    }

    private void putSettings(Map<String, Object> patch, OutputStream out) throws IOException {
        Map<String, Object> doc;
        int before, after, revision;
        patch.remove("ok"); // GET response metadata, outside the settings document.
        synchronized (settings) {
            before = settings.port();
            doc = settings.put(patch);
            after = settings.port();
            revision = settings.revision();
        }
        try {
            Http.write(out, 200, Json.stringify(settingsResponse(doc)));
        } finally {
            if (before != after) portChange.afterReply(revision, after);
        }
    }

    private Map<String, Object> dispatch(Http.Request req, InputStream in, String path, int queryAt,
            Auth.Client client) throws IOException {
        switch (path) {
            case "/v1/info":
                method(req, "GET");
                return Json.fields("ok", true, "app", "herald", "version", version, "api", 1,
                        "id", id, "name", settings.name(), "model", model, "pairing", backend.pairingAllowed());
            case "/v1/pair/start":
                method(req, "POST");
                auth.startNetwork(body(req, in).get("client"));
                return Json.fields("ok", true, "expires_in", Auth.LIFETIME);
            case "/v1/pair/finish":
                method(req, "POST");
                Map<String, Object> fields = body(req, in);
                String token = auth.finish(fields.get("client"), fields.get("code"));
                return Json.fields("ok", true, "token", token, "id", id);
            case "/v1/clients":
                method(req, "GET");
                return Json.fields("ok", true, "clients", auth.clients());
            case "/v1/clients/self":
                method(req, "DELETE");
                auth.remove(client);
                return Json.fields("ok", true);
            case "/v1/status":
                method(req, "GET");
                Map<String, Object> status = Json.fields("ok", true, "version", version, "api", 1,
                        "id", id, "name", settings.name(), "stream", events.stream, "event_seq", events.latest(),
                        "screen_on", backend.screenOn(), "overlay_allowed", backend.overlayAllowed(),
                        "sound_playing", backend.soundPlaying(), "quiet_seconds", 0, "settings_revision", settings.revision(),
                        "mqtt", "off", "compat", "off");
                status.putAll(backend.statusFields());
                status.putAll(cards.status());
                status.put("chips", chips.rows().size());
                status.put("images", images.count());
                status.put("event_seq", events.latest());
                return status;
            case "/v1/announce":
                method(req, "POST");
                return backend.announce(new AnnounceSpec(body(req, in), settings));
            case "/v1/stop":
                method(req, "POST");
                backend.stop();
                return Json.fields("ok", true);
            case "/v1/cards":
                if (req.method.equals("POST")) {
                    Map<String,Object> doc = body(req, in);
                    return SpokenCards.post(new CardSpec(doc, settings), doc.containsKey("announce"), doc.get("announce"),
                            settings, backend, cards, ids);
                }
                method(req, "GET");
                return Json.fields("ok", true, "cards", cards.rows());
            case "/v1/cards/clear":
                if (req.method.equals("POST"))
                    return Json.fields("ok", true, "cleared", cards.clear(body(req, in)));
                return cardItem(req, "clear");
            case "/v1/chips":
                if (req.method.equals("GET")) return Json.fields("ok", true, "chips", chips.rows());
                method(req, "PUT");
                chips.replace(body(req, in));
                return Json.fields("ok", true);
            case "/v1/settings":
                method(req, "GET");
                return settingsResponse(settings.document());
            case "/v1/looks":
                method(req, "GET");
                return Json.fields("ok", true, "looks", settings.looks());
            case "/v1/categories":
                method(req, "GET");
                return Json.fields("ok", true, "categories", settings.categories());
            case "/v1/events":
                method(req, "GET");
                return poll(queryAt < 0 ? "" : req.target.substring(queryAt + 1));
            default:
                if (path.matches("/v1/cards/[^/]+")) return cardItem(req, path.substring("/v1/cards/".length()));
                if (path.startsWith("/v1/clips/") && path.indexOf('/', 10) < 0) {
                    String key = path.substring(10);
                    if (!req.method.equals("GET")) method(req, "PUT");
                    if (!ClipStore.validKey(key)) throw new Failure(400, "bad key");
                    long size;
                    if (req.method.equals("GET")) {
                        size = backend.clipSize(key);
                        if (size < 0) throw new Failure(404, "not cached");
                    } else {
                        byte[] data = Http.readBody(in, (int) req.contentLength);
                        size = data.length;
                        if (size == 0) throw new Failure(400, "empty body");
                        if (!backend.putClip(key, data)) throw new Failure(500, "store failed");
                    }
                    return Json.fields("ok", true, "size", size);
                }
                if (path.matches("/v1/images/[^/]+")) {
                    String key = path.substring("/v1/images/".length());
                    if (req.method.equals("GET")) return Json.fields("ok", true, "size", images.size(key));
                    method(req, "PUT");
                    ImageStore.checkKey(key);
                    byte[] bytes = Http.readBody(in, (int) req.contentLength);
                    images.put(key, bytes);
                    return Json.fields("ok", true, "size", bytes.length);
                }
                if (path.matches("/v1/chips/[^/]+")) {
                    String key = path.substring("/v1/chips/".length());
                    if (req.method.equals("DELETE")) return Json.fields("ok", true, "found", chips.delete(key));
                    method(req, "PUT");
                    ChipBoard.checkId(key);
                    chips.put(key, body(req, in));
                    return Json.fields("ok", true);
                }
                if (path.startsWith("/v1/looks/") && path.indexOf('/', "/v1/looks/".length()) < 0) {
                    String key = path.substring("/v1/looks/".length());
                    if (req.method.equals("PUT")) {
                        Settings.writableLookId(key);
                        return Json.fields("ok", true, "look", settings.putLook(key, body(req, in)));
                    }
                    method(req, "DELETE");
                    settings.deleteLook(key);
                    return Json.fields("ok", true);
                }
                if (path.matches("/v1/categories/[^/]+")) {
                    String key = path.substring("/v1/categories/".length());
                    if (req.method.equals("PUT")) {
                        return Json.fields("ok", true, "category", settings.putCategory(key, body(req, in)));
                    }
                    method(req, "DELETE");
                    settings.deleteCategory(key);
                    return Json.fields("ok", true);
                }
                throw new Failure(404, "unknown path");
        }
    }

    private Map<String, Object> cardItem(Http.Request req, String id) {
        if (req.method.equals("GET")) return cards.detail(id);
        method(req, "DELETE");
        return Json.fields("ok", true, "found", cards.delete(id));
    }

    private Map<String, Object> poll(String query) {
        long after = 0, seconds = 0;
        if (!query.isEmpty()) {
            for (String field : query.split("&", -1)) {
                String[] pair = field.split("=", 2);
                if (pair.length != 2 || !pair[1].matches("[0-9]+")) throw new Failure(400, "bad query");
                long value;
                try {
                    value = Long.parseLong(pair[1]);
                } catch (NumberFormatException e) {
                    throw new Failure(400, "bad query");
                }
                if (pair[0].equals("after")) after = value;
                else if (pair[0].equals("wait") && value <= 30) seconds = value;
                else throw new Failure(400, "bad query");
            }
        }
        return events.await(after, seconds * 1000);
    }

    static Map<String, Object> body(Http.Request req, InputStream in) throws IOException {
        byte[] data = Http.readBody(in, (int) req.contentLength);
        try {
            return Json.parse(StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(data)).toString());
        } catch (CharacterCodingException | IllegalArgumentException e) {
            throw new Failure(400, "bad json");
        }
    }

    private static void method(Http.Request req, String expected) {
        if (!req.method.equals(expected)) throw new Failure(405, "method not allowed");
    }

    private static int limit(String method, String path) {
        if (method.equals("POST")) {
            if (path.equals("/v1/cards")) return 1572864;
            if (path.equals("/v1/announce")) return 3145728;
        }
        if (method.equals("PUT")) {
            if (path.matches("/v1/clips/[^/]+")) return 4194304;
            if (path.matches("/v1/images/[^/]+")) return 2097152;
        }
        return 65536;
    }
}
