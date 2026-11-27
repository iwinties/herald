package io.github.iwinties.herald;

import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

final class SpokenCards {
    private static final Set<String> OPTIONS = new HashSet<>(Arrays.asList("text", "clip", "audio",
            "chime", "volume", "output", "urgent", "duck", "request_id", "timing"));

    static String cut(String value, int limit) {
        if (value.length() <= limit) return value;
        return value.substring(0, Character.isHighSurrogate(value.charAt(limit - 1)) ? limit - 1 : limit);
    }

    static String spokenText(String title, String message) {
        title = cut(title, 120).trim();
        message = cut(message, 1000).trim();
        if (title.isEmpty() && message.isEmpty()) throw new Api.Failure(400, "text is empty");
        if (title.isEmpty()) return cut(message, 500);
        if (message.isEmpty()) return title;
        String separator = ".!?".indexOf(title.charAt(title.length() - 1)) >= 0 ? " " : ". ";
        return cut(title + separator + message, 500);
    }

    static Map<String,Object> post(CardSpec card, boolean announcePresent, Object announce, Settings settings,
            Backend backend, CardBoard board, RequestIds ids) {
        if (!announcePresent) announce = settings.categoryAnnounce(card.category);
        if (Boolean.FALSE.equals(announce)) return board.post(card);
        Map<String,Object> fields = Boolean.TRUE.equals(announce) ? Json.fields() : CardSpec.object(announce, "announce");
        Object timing = fields.getOrDefault("timing", "with_voice");
        boolean now = "now".equals(timing);
        if (!OPTIONS.containsAll(fields.keySet()) || !now && !"with_voice".equals(timing)) throw CardSpec.bad("announce");
        AnnounceSpec spec = AnnounceSpec.forCard(fields, settings, card);
        CardSpec owned = spec.card;
        String id = spec.requestId;
        synchronized (board) {
            synchronized (backend.admissionLock()) {
                Map<String,Object> row = id == null ? null : ids.find(id);
                if (row != null) {
                    if (!row.containsKey("card")) throw new Api.Failure(409, "request id conflict");
                    Map<String,Object> answer = Json.fields("ok", true);
                    answer.putAll(RequestIds.cardFields(Settings.object(row.get("card"), "card")));
                    answer.put("announce", Json.fields("ok", true, "job", row.get("job"),
                            "duplicate", true, "outcome", row.get("state")));
                    return answer;
                }
                CardBoard.Transition decision = now ? board.prepare(owned, 0) : null;
                boolean full = id != null && ids.full();
                Object checked;
                try {
                    checked = backend.checkSpeech(spec, full);
                } catch (Api.Failure refusal) {
                    Map<String,Object> answer;
                    if (now) {
                        board.apply(decision);
                        answer = new LinkedHashMap<>(decision.answer);
                    } else answer = board.post(owned);
                    answer.put("announce", Json.fields("ok", false, "status", refusal.status, "error", refusal.getMessage()));
                    return answer;
                }
                Map<String,Object> answer = now ? new LinkedHashMap<>(decision.answer)
                        : Json.fields("ok", true, "id", owned.id, "category", owned.category, "state", "pending_voice", "reason", null);
                String job = UUID.randomUUID().toString();
                if (id != null) ids.put(id, job, answer);
                long generation = -1;
                if (now) board.apply(decision);
                else generation = board.reserve(owned);
                backend.queueSpeech(checked, job, owned, generation);
                answer.put("announce", Json.fields("ok", true, "job", job));
                return answer;
            }
        }
    }

    // A job owns a specific spec independently of whether it may still attempt display.
    static final class JobCard {
        final CardSpec owned;
        final long generation;
        private boolean attempted;

        JobCard(CardSpec owned, long generation) {
            this.owned = owned;
            this.generation = generation;
        }

        void show(CardBoard board, int lengthMs) {
            synchronized (board) {
                if (generation < 0 || attempted) return;
                attempted = true;
                board.consume(owned.id, generation, lengthMs < 0 || owned.sticky() ? 0 : (long)lengthMs + 3000);
            }
        }

        void end(CardBoard board, String outcome) {
            synchronized (board) {
                if ("stopped".equals(outcome)) {
                    board.removeOwned(owned, generation);
                } else {
                    if ("error".equals(outcome)) show(board, -1);
                    if (!attempted && generation >= 0) board.release(owned.id, generation);
                }
                attempted = true;
            }
        }
    }
}
