package io.github.iwinties.herald;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.LongSupplier;

/** Pairing, credential lookups and their durable writes are serialized on this instance. */
final class Auth {
    static final int LIFETIME = 120;
    private final Backend backend;
    private final EventLog events;
    private final SecureRandom random;
    private final LongSupplier clock;
    private final List<Client> clients = new ArrayList<>();
    private String code, boundName;
    private long expires, lockedUntil;
    private boolean expired, closed;
    private int wrong;
    private String pairedName;
    private long confirmationUntil;

    static final class Client {
        private final byte[] hash;
        final String name;
        final long created;
        private long lastUsed;

        Client(byte[] hash, String name, long created, long lastUsed) {
            this.hash = hash;
            this.name = name;
            this.created = created;
            this.lastUsed = lastUsed;
        }

        Map<String, Object> fields() {
            return Json.fields("client", name, "created", created, "last_used", lastUsed);
        }
    }

    Auth(Backend backend, EventLog events, LongSupplier clock, SecureRandom random) {
        this.backend = backend;
        this.events = events;
        this.clock = clock;
        this.random = random;
    }

    static Auth fromJson(String json, Backend backend, EventLog events, LongSupplier clock, SecureRandom random) {
        Auth auth = new Auth(backend, events, clock, random);
        if (json == null) return auth;
        Map<String, Object> saved = Json.parse(json);
        auth.lockedUntil = ((Number) saved.get("locked_until")).longValue();
        for (Object item : (List<?>) saved.get("clients")) {
            Map<?, ?> row = (Map<?, ?>) item;
            String hex = (String) row.get("hash");
            if (!hex.matches("[0-9a-f]{64}") || auth.clients.size() == 16)
                throw new IllegalArgumentException("invalid credential store");
            byte[] hash = new byte[32];
            for (int i = 0; i < 32; i++) hash[i] = (byte) Integer.parseInt(hex.substring(2 * i, 2 * i + 2), 16);
            auth.clients.add(new Client(hash, clientName(row.get("client")),
                    ((Number) row.get("created")).longValue(), ((Number) row.get("last_used")).longValue()));
        }
        return auth;
    }

    synchronized String toJson() {
        List<Object> rows = new ArrayList<>();
        for (Client client : clients) {
            Map<String, Object> row = client.fields();
            StringBuilder hex = new StringBuilder(64);
            for (byte b : client.hash) {
                hex.append(Character.forDigit((b & 255) >>> 4, 16)).append(Character.forDigit(b & 15, 16));
            }
            row.put("hash", hex.toString());
            rows.add(row);
        }
        return Json.stringify(Json.fields("clients", rows, "locked_until", lockedUntil));
    }

    private static String clientName(Object input) {
        String name = input instanceof String ? ((String) input).trim() : "";
        if (name.isEmpty() || name.codePointCount(0, name.length()) > 64) throw new Api.Failure(400, "bad client");
        return name;
    }

    synchronized void startNetwork(Object client) {
        start(clientName(client));
    }

    synchronized String startTv() {
        start(null);
        return code; // Only for the settings activity in this process.
    }

    private void start(String name) {
        checkOpen();
        expire();
        if (!backend.pairingAllowed()) throw new Api.Failure(403, "pairing disabled");
        checkCapacity();
        if (name != null && !backend.screenOn()) throw new Api.Failure(409, "screen off");
        if (name != null && !backend.overlayAllowed()) throw new Api.Failure(409, "overlay not allowed");
        if (code != null) throw new Api.Failure(409, "pairing in progress");
        if (clock.getAsLong() < lockedUntil) throw new Api.Failure(429, "try later");
        String next = String.format(Locale.ROOT, "%06d", random.nextInt(1000000));
        if (name != null && !backend.showPairingCode(name, next, LIFETIME))
            throw new Api.Failure(409, "overlay not allowed");
        pairedName = null;
        code = next;
        boundName = name;
        expires = clock.getAsLong() + LIFETIME * 1000L;
        wrong = 0;
        expired = false;
    }

    synchronized String finish(Object client, Object submitted) {
        checkOpen();
        String name = clientName(client);
        expire();
        if (expired) {
            expired = false;
            throw new Api.Failure(410, "pairing expired");
        }
        if (code == null) throw new Api.Failure(404, "no pairing");
        checkCapacity();
        if (!code.equals(submitted) || boundName != null && !boundName.equals(name)) {
            if (++wrong >= 5) cancel();
            throw new Api.Failure(403, "wrong code");
        }
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        long seconds = clock.getAsLong() / 1000;
        Client entry = new Client(hash(token), name, seconds, seconds);
        clients.add(entry);
        try {
            backend.persistAuth(toJson());
        } catch (RuntimeException e) {
            clients.remove(entry);
            throw e;
        }
        clear();
        events.add("client.paired", Json.fields("client", name));
        pairedName = name;
        confirmationUntil = clock.getAsLong() + 5000;
        backend.showPaired(name);
        return token;
    }

    private void checkCapacity() {
        if (clients.size() >= 16) throw new Api.Failure(409, "too many clients");
    }

    private void checkOpen() {
        if (closed) throw new Api.Failure(503, "service stopped");
    }

    private void clear() {
        if (boundName != null) backend.hidePairingCode();
        code = boundName = null;
    }

    synchronized void expire() {
        if (code != null && clock.getAsLong() >= expires) {
            clear();
            expired = true;
        }
    }

    // Local settings UI only. Never include a code in the public client/status projections.
    synchronized String tvCode() {
        expire();
        return boundName == null ? code : null;
    }

    synchronized String pairedName() {
        return clock.getAsLong() < confirmationUntil ? pairedName : null;
    }

    synchronized List<Client> localClients() { return new ArrayList<>(clients); }

    synchronized void cancel() {
        expire();
        if (code == null) return;
        clear();
        lockedUntil = clock.getAsLong() + 60000;
        backend.persistAuth(toJson());
    }

    synchronized void close() {
        closed = true;
        clear();
    }

    private static byte[] hash(String token) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
    }

    synchronized Client authorize(String header) {
        checkOpen();
        if (header != null && header.regionMatches(true, 0, "Bearer ", 0, 7)) {
            byte[] supplied = hash(header.substring(7));
            for (Client entry : clients) {
                if (MessageDigest.isEqual(entry.hash, supplied)) {
                    long now = clock.getAsLong() / 1000;
                    if (now - entry.lastUsed >= 60) {
                        long previous = entry.lastUsed;
                        entry.lastUsed = now;
                        try {
                            backend.persistAuth(toJson());
                        } catch (RuntimeException e) {
                            entry.lastUsed = previous;
                            throw e;
                        }
                    }
                    return entry;
                }
            }
        }
        throw new Api.Failure(401, "unauthorized");
    }

    synchronized List<Map<String, Object>> clients() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Client entry : clients) result.add(entry.fields());
        return result;
    }

    synchronized void remove(Client entry) {
        checkOpen();
        int index = clients.indexOf(entry);
        if (index < 0) return;
        clients.remove(index);
        try {
            backend.persistAuth(toJson());
        } catch (RuntimeException e) {
            clients.add(index, entry);
            throw e;
        }
        events.add("client.removed", Json.fields("client", entry.name));
    }
}
