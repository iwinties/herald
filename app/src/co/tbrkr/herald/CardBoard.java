package co.tbrkr.herald;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;

final class CardBoard {
    interface Display {
        void changed();
        void loadImage(CardSpec spec);
    }

    static final class Entry {
        final CardSpec spec;
        final long accepted, order;
        String state, reason;
        Style appearance;
        long deadline, position, minimumDurationMs;
        boolean attached, expanded;
        String button;

        Entry(CardSpec spec, long accepted, long order, int stickyCap) {
            this.spec = spec;
            this.accepted = accepted;
            this.order = order;
            this.position = order;
            this.appearance = spec.style;
            expanded = "fullscreen".equals(spec.style.fields.get("placement"));
            deadline = spec.sticky() ? accepted + stickyCap * 1000L : Long.MAX_VALUE;
        }
        Entry(Entry e) {
            spec = e.spec;
            accepted = e.accepted;
            order = e.order;
            state = e.state;
            reason = e.reason;
            appearance = e.appearance;
            deadline = e.deadline;
            position = e.position;
            minimumDurationMs = e.minimumDurationMs;
            attached = e.attached;
            expanded = e.expanded;
            button = e.button;
        }

        Map<String, Object> answer() {
            return Json.fields("ok", true, "id", spec.id, "category", spec.category, "state", state, "reason", reason);
        }
    }

    private static final Comparator<Entry> WEAKEST = Comparator.comparingInt((Entry e) -> e.spec.rank())
            .thenComparingLong(e -> e.order);
    private static final Comparator<Entry> NEXT = Comparator.comparingInt((Entry e) -> -e.spec.rank())
            .thenComparingLong(e -> e.order);

    private final Map<String, Entry> active = new LinkedHashMap<>();
    private final Settings settings;
    private final EventLog events;
    private final Backend backend;
    private final Display display;
    private final LongSupplier clock;
    private long serial, generation;
    private static final class Reservation {
        final CardSpec spec;
        final long generation;
        Reservation(CardSpec spec, long generation) { this.spec = spec; this.generation = generation; }
    }
    private final Map<String, Reservation> pending = new LinkedHashMap<>();
    private long lastBackMs = -300;
    private boolean interactive;
    private Map<String, Object> last;

    CardBoard(Settings settings, EventLog events, Backend backend, Display display, LongSupplier clock) {
        this.settings = settings;
        this.events = events;
        this.backend = backend;
        this.interactive = backend.screenOn();
        this.display = display;
        this.clock = clock;
    }

    private int setting(String key) { return (int) settings.display().get(key); }

    static boolean night(Map<String, Object> config, long now) {
        LocalTime time = Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).toLocalTime();
        LocalTime start = LocalTime.parse((String) config.get("night_start"));
        LocalTime end = LocalTime.parse((String) config.get("night_end"));
        return start.isAfter(end) ? !time.isBefore(start) || time.isBefore(end)
                : !time.isBefore(start) && time.isBefore(end);
    }

    private String eligibility(Entry e, boolean screenOn) {
        if (!screenOn) return e.spec.sticky() ? "waiting" : "screen_off";
        Map<String, Object> s = e.spec.style.fields;
        boolean night = night(settings.display(), clock.getAsLong());
        if (e.spec.rank() < 3) {
            if (Boolean.FALSE.equals(s.get("show_here"))) return "hidden";
            if (night && "hide".equals(s.get("night"))) return "night";
            if ("skip".equals(s.get("over_playback")) && backend.soundPlaying()) return "over_playback";
        }
        e.appearance = e.spec.style;
        if (night && "dim".equals(s.get("night"))) {
            int opacity = 5 * Math.round((int) s.get("opacity") * .6f / 5);
            e.appearance = e.spec.style.merge(Json.fields("opacity", opacity, "size", smaller(s.get("size")),
                    "text_size", smaller(s.get("text_size"))), "style");
        }
        return "showing";
    }

    private static String smaller(Object value) {
        if ("xlarge".equals(value)) return "large";
        if ("large".equals(value)) return "medium";
        return "small";
    }

    private void event(Entry e, String type, Map<String, Object> fields) {
        fields.put("id", e.spec.id);
        events.add("card." + type, fields);
        last = Json.fields("id", e.spec.id, "category", e.spec.category, "state", e.state);
    }

    private void finish(Entry e, String state, String reason) {
        finish(e, state, reason, clock.getAsLong() + 600000);
    }

    private void finish(Entry e, String state, String reason, long until) {
        active.remove(e.spec.id);
        e.state = state;
        e.reason = reason;
        e.deadline = until;
        // History carries only public detail, never a retained spec or image payload.
        remember(e);
        event(e, state, Json.fields("reason", reason));
        display.changed();
    }

    private static final class Detail {
        final long until;
        final Map<String, Object> row;
        Detail(Entry e) { until = e.deadline; row = e.answer(); row.put("button", e.button); }
    }
    private final Map<String, Detail> details = new LinkedHashMap<>();
    private void remember(Entry e) {
        details.remove(e.spec.id);
        details.put(e.spec.id, new Detail(e));
        if (details.size() > 64) details.remove(details.keySet().iterator().next());
    }

    private List<Entry> in(String state) {
        return in(active, state);
    }
    private static List<Entry> in(Map<String,Entry> entries, String state) {
        List<Entry> result = new ArrayList<>();
        for (Entry e : entries.values()) if (state.equals(e.state)) result.add(e);
        return result;
    }

    synchronized List<Entry> showing() { return in("showing"); }
    synchronized boolean owns(CardSpec spec) {
        Entry e = active.get(spec.id);
        return e != null && e.spec == spec;
    }

    static final class Transition {
        final Map<String,Object> answer;
        private final List<Runnable> changes;
        private final long order;
        private Transition(long order, Map<String,Object> answer, List<Runnable> changes) {
            this.order = order;
            this.answer = java.util.Collections.unmodifiableMap(answer);
            this.changes = java.util.Collections.unmodifiableList(new ArrayList<>(changes));
        }
    }

    // Unchanged entries are shared read-only. All writes, including promotion of an
    // existing entry, target private copies until the board applies the ordered changes.
    private final class Decision {
        final Map<String,Entry> view = new LinkedHashMap<>(active);
        final List<Runnable> changes = new ArrayList<>();
        final Map<String,Entry> finished = new LinkedHashMap<>();

        void end(Entry e, String state, String reason) {
            Entry ended = new Entry(e);
            ended.state = state;
            ended.reason = reason;
            long until = clock.getAsLong() + 600000;
            view.remove(e.spec.id);
            finished.put(e.spec.id, ended);
            changes.add(() -> finish(e, state, reason, until));
        }

        void admit(Entry e, Entry old, boolean screenOn) {
            boolean reconsider = e == old;
            if (reconsider) e = new Entry(old);
            String state = eligibility(e, screenOn);
            List<Entry> victims = new ArrayList<>();
            List<Entry> visible = in(view, "showing");
            visible.remove(old);
            if ("showing".equals(state)) {
                Object mode = e.spec.style.fields.get("stacking");
                for (Entry v : visible) {
                    if (!v.spec.category.equals(e.spec.category)) continue;
                    if ("queue".equals(mode) || "replace".equals(mode) && v.spec.rank() == 3 && e.spec.rank() < 3) {
                        state = "queued";
                        break;
                    }
                    if ("replace".equals(mode)) victims.add(v);
                }
                if ("showing".equals(state)) {
                    visible.removeAll(victims);
                    visible.sort(WEAKEST);
                    while (visible.size() >= setting("max_visible")) {
                        Entry weakest = visible.remove(0);
                        if (weakest.spec.rank() == 3) { state = "queued"; break; }
                        victims.add(weakest);
                    }
                }
            }
            if ("queued".equals(state) || "waiting".equals(state)) {
                List<Entry> peers = in(view, state);
                peers.remove(old);
                if (peers.size() >= ("waiting".equals(state) ? 16 : 32)) throw new Api.Failure(429, "too many cards");
                victims.clear();
            }
            boolean update = !reconsider && old != null && "showing".equals(old.state)
                    && "showing".equals(state) && e.spec.category.equals(old.spec.category);
            if (reconsider && "queued".equals(state) && state.equals(old.state)) return;
            String id = e.spec.id;
            view.remove(id);
            changes.add(() -> { active.remove(id); details.remove(id); });
            for (Entry v : victims) end(v, "ended", "replaced");
            if (!("showing".equals(state) || "queued".equals(state) || "waiting".equals(state))) {
                end(e, "skipped", state);
                return;
            }
            e.state = state;
            if ("showing".equals(state)) {
                if (!e.spec.sticky()) e.deadline = clock.getAsLong() + Math.max(e.minimumDurationMs,
                        1000L * (int)e.spec.style.fields.get("duration"));
                if (update) {
                    e.position = old.position;
                    e.attached = old.attached;
                }
            }
            Entry admitted = e;
            view.put(id, admitted);
            changes.add(() -> {
                active.put(id, admitted);
                if (update) event(admitted, "updated", Json.fields());
                display.changed();
            });
        }

        void promoteQueued() {
            if (!interactive) return;
            List<Entry> queued = in(view, "queued");
            queued.sort(NEXT);
            for (Entry e : queued) {
                if (in(view, "showing").size() >= setting("max_visible")) break;
                admit(e, e, true);
            }
        }
    }

    Map<String, Object> post(CardSpec spec) { return post(spec, 0); }

    synchronized Map<String, Object> post(CardSpec spec, long minimumDurationMs) {
        Transition transition = prepare(spec, minimumDurationMs);
        apply(transition);
        return new LinkedHashMap<>(transition.answer);
    }

    synchronized Transition prepare(CardSpec spec, long minimumDurationMs) {
        tick();
        if (!backend.overlayAllowed()) throw new Api.Failure(409, "overlay not allowed");
        Entry old = active.get(spec.id);
        Entry e = new Entry(spec, clock.getAsLong(), serial + 1, setting("sticky_cap"));
        e.minimumDurationMs = minimumDurationMs;
        Decision decision = new Decision();
        decision.changes.add(() -> { serial = e.order; pending.remove(spec.id); });
        decision.admit(e, old, interactive);
        if (spec.image != null && decision.view.containsKey(spec.id))
            decision.changes.add(() -> display.loadImage(spec));
        if (old != null && "showing".equals(old.state)) decision.promoteQueued();
        Entry result = decision.view.get(spec.id);
        if (result == null) result = decision.finished.get(spec.id);
        return new Transition(e.order, result.answer(), decision.changes);
    }

    synchronized void apply(Transition transition) {
        if (serial >= transition.order) return; // Already applied under the prepare/apply monitor.
        transition.changes.forEach(Runnable::run);
    }

    synchronized long reserve(CardSpec spec) {
        pending.put(spec.id, new Reservation(spec, ++generation));
        return generation;
    }
    synchronized void consume(String id, long gen, long minimumMs) {
        Reservation r = pending.get(id);
        if (r == null || r.generation != gen) return;
        pending.remove(id);
        try { post(r.spec, minimumMs); } catch (Api.Failure refused) { /* speech continues */ }
    }
    synchronized void release(String id, long gen) {
        Reservation r = pending.get(id);
        if (r != null && r.generation == gen) pending.remove(id);
    }
    synchronized void removeOwned(CardSpec owned, long gen) {
        release(owned.id, gen);
        if (!owns(owned)) return;
        finish(active.get(owned.id), "ended", "stopped");
        promote();
    }

    synchronized void attached(CardSpec spec, long addedAt) {
        Entry e = active.get(spec.id);
        if (!owns(spec) || !"showing".equals(e.state) || e.attached) return;
        e.attached = true;
        event(e, "shown", Json.fields("category", spec.category, "ms", Math.max(0, addedAt - e.accepted)));
        String sound = (String) spec.style.fields.get("sound");
        if (!sound.equals("none")) backend.playCardSound(sound, (int) spec.style.fields.get("sound_volume"));
    }

    synchronized void failed(CardSpec spec) {
        if (!owns(spec)) return;
        finish(active.get(spec.id), "ended", "failed");
        promote();
    }

    synchronized void media(CardSpec spec, String state) {
        if (owns(spec)) events.add("card.media", Json.fields("id", spec.id, "kind", "image", "state", state,
                "ms", Math.max(0, clock.getAsLong() - active.get(spec.id).accepted)));
    }

    synchronized long videoMedia(CardSpec spec, String state, boolean afterFirstFrame) {
        Entry e = active.get(spec.id);
        if (e == null || e.spec != spec || !"showing".equals(e.state)) return -1;
        long ms = Math.max(0, clock.getAsLong() - e.accepted);
        Map<String, Object> fields = Json.fields("id", spec.id, "kind", "video", "state", state, "ms", ms);
        if (afterFirstFrame) fields.put("after_first_frame", true);
        events.add("card.media", fields);
        return ms;
    }

    boolean pauseToUseButtons() {
        return Boolean.FALSE.equals(settings.display().get("focus_while_playing")) && backend.soundPlaying();
    }

    synchronized CardSpec focusOwner() {
        if (!interactive || pauseToUseButtons()) return null;
        Entry newest = null;
        for (Entry e : active.values()) {
            if ("showing".equals(e.state) && !e.spec.buttons.isEmpty() && "auto".equals(e.spec.style.fields.get("focus"))
                    && (newest == null || e.order > newest.order)) newest = e;
        }
        return newest == null ? null : newest.spec;
    }

    synchronized void press(CardSpec spec, int index) {
        tick();
        if (focusOwner() != spec || index < 0 || index >= spec.buttons.size()) return;
        Entry e = active.get(spec.id);
        Map<String, Object> button = spec.buttons.get(index);
        switch ((String) button.get("action")) {
            case "expand":
                e.expanded = !e.expanded;
                if (e.expanded && !spec.sticky()) e.deadline = Math.max(e.deadline, clock.getAsLong() + 60000);
                display.changed();
                return;
            case "event":
                e.button = (String) button.get("id");
                events.add("card.button", Json.fields("id", spec.id, "button", e.button));
                finish(e, "ended", "button");
                break;
            case "dismiss":
                finish(e, "ended", "dismissed");
                break;
        }
        promote();
    }

    synchronized void back(CardSpec spec, long uptimeMs) {
        tick();
        if (focusOwner() != spec || uptimeMs - lastBackMs < 300) return;
        lastBackMs = uptimeMs;
        finish(active.get(spec.id), "ended", "dismissed");
        promote();
    }

    private void promote() {
        if (!interactive) return;
        if (in("queued").isEmpty()) return;
        Decision decision = new Decision();
        decision.promoteQueued();
        decision.changes.forEach(Runnable::run);
    }

    synchronized void screen(boolean on) {
        if (interactive == on) return;
        interactive = on;
        expire();
        if (on) {
            List<Entry> waiting = in("waiting");
            waiting.sort(NEXT);
            for (Entry e : waiting) {
                Decision decision = new Decision();
                decision.admit(e, e, true);
                decision.changes.forEach(Runnable::run);
            }
            promote();
        } else {
            for (Entry e : new ArrayList<>(active.values())) {
                if (!e.spec.sticky()) finish(e, "ended", "expired");
                else {
                    e.state = "waiting";
                    e.attached = false;
                    e.position = ++serial;
                }
            }
            List<Entry> wait = in("waiting");
            wait.sort(WEAKEST);
            for (int i = 0; i < wait.size() - 16; i++) finish(wait.get(i), "ended", "capped");
        }
        display.changed();
    }

    private void expire() {
        long now = clock.getAsLong();
        for (Entry e : new ArrayList<>(active.values())) {
            if (now >= e.deadline) finish(e, "ended", e.spec.sticky() ? "capped" : "expired");
        }
        details.values().removeIf(d -> now >= d.until);
    }

    synchronized void tick() {
        // A request may reach the service before delivery of the screen broadcast.
        screen(backend.screenOn());
        expire();
        promote();
    }

    // Ordinary /announce cards keep their existing stopped outcome (cleared), but their
    // stop only owns the active spec. A separate spoken reservation may share its id.
    synchronized void clearOwned(CardSpec spec) {
        if (!owns(spec)) return;
        tick();
        if (!owns(spec)) return;
        finish(active.get(spec.id), "ended", "cleared");
        promote();
    }

    synchronized boolean delete(String id) {
        tick();
        Entry e = active.get(id);
        boolean reserved = pending.remove(id) != null;
        if (e == null) return reserved;
        finish(e, "ended", "cleared");
        promote();
        return true;
    }

    synchronized int clear(Map<String, Object> doc) {
        boolean category = doc.containsKey("category");
        if (category == doc.containsKey("all") || !(category ? doc.get("category") instanceof String
                && ((String) doc.get("category")).matches("[a-z0-9_]{1,32}") : Boolean.TRUE.equals(doc.get("all"))))
            throw CardSpec.bad("json");
        tick();
        java.util.Set<String> cleared = new java.util.HashSet<>();
        pending.values().removeIf(r -> {
            if (category && !r.spec.category.equals(doc.get("category"))) return false;
            cleared.add(r.spec.id);
            return true;
        });
        for (Entry e : new ArrayList<>(active.values())) {
            if (!category || e.spec.category.equals(doc.get("category"))) {
                finish(e, "ended", "cleared");
                cleared.add(e.spec.id);
            }
        }
        promote();
        return cleared.size();
    }

    synchronized Map<String, Object> detail(String id) {
        tick();
        Entry e = active.get(id);
        if (e == null) {
            Detail d = details.get(id);
            if (d == null) throw new Api.Failure(404, "unknown card");
            return d.row;
        }
        Map<String, Object> row = e.answer();
        row.put("button", e.button);
        return row;
    }

    synchronized List<Map<String, Object>> rows() {
        tick();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Entry e : active.values()) rows.add(Json.fields("id", e.spec.id, "category", e.spec.category,
                "state", e.state, "priority", e.spec.priority, "remaining_s", "showing".equals(e.state)
                        ? Math.max(0, e.deadline - clock.getAsLong() + 999) / 1000 : null));
        return rows;
    }

    synchronized Map<String, Object> status() {
        tick();
        int visible = showing().size();
        return Json.fields("cards_visible", visible, "cards_queued", active.size() - visible, "last_card", last);
    }
}
