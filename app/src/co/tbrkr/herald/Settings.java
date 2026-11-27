package co.tbrkr.herald;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Consumer;

/** Mutations are validated on a candidate, then persisted and published under one lock. */
final class Settings {
    static final class Invalid extends RuntimeException {
        final String field;
        Invalid(String field) { super("bad settings"); this.field = field; }
    }

    private final Consumer<String> save;
    private final EventLog events;
    private Map<String, Object> groups;
    private Map<String, Category> categories = Category.defaults();
    private Map<String, Look> customLooks = new TreeMap<>();
    private final boolean discardedStored;
    private int revision;

    Settings(String name, String stored, Consumer<String> save, EventLog events) {
        this.save = save;
        this.events = events;
        if (name == null || name.isEmpty()) name = "Herald";
        if (name.codePointCount(0, name.length()) > 40) name = name.substring(0, name.offsetByCodePoints(0, 40));
        groups = Json.fields("device", Json.fields("name", name, "port", 7979),
                "pairing", Json.fields("allowed", true),
                "display", Json.fields("max_visible", 3, "night_start", "22:00", "night_end", "07:00",
                        "chip_look", "glass", "chip_placement", "top_left", "pixel_shift", true, "sticky_cap", 3600, "focus_while_playing", true),
                "audio", Json.fields("volume_tv", 70, "volume_speaker", 50, "speaker_at_night", "urgent", "chime", true),
                "mqtt", Json.fields("enabled", false, "host", "", "port", 1883, "tls", false, "username", "",
                        "password", "", "base_topic", "herald", "discovery", true, "discovery_prefix", "homeassistant", "commands", true),
                "compat", Json.fields("tvoverlay", Json.fields("enabled", false, "port", 5001,
                        "allowed", Collections.emptyList(), "category", "general")),
                "ntfy", Json.fields("enabled", false, "server", "https://ntfy.sh", "token", "", "token_server", "",
                        "catch_up", 120, "topics", Collections.emptyList()));
        Map<String, Object> doc = stored == null ? Collections.emptyMap() : Json.parse(stored);
        Object schema = doc.get("schema");
        discardedStored = stored != null && !(schema instanceof Number && ((Number) schema).doubleValue() == 2);
        if (stored == null || discardedStored) {
            apply(Collections.emptyMap(), true);
        } else {
            apply(doc, true);
            revision = integer(doc.get("revision"), "revision", 0, Integer.MAX_VALUE);
        }
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> object(Object value, String path) {
        if (!(value instanceof Map)) throw new Invalid(path);
        return (Map<String, Object>) value;
    }

    static int integer(Object value, String path, int min, int max) {
        if (!(value instanceof Number)) throw new Invalid(path);
        double n = ((Number) value).doubleValue();
        if (n != (int) n || n < min || n > max) throw new Invalid(path);
        return (int) n;
    }

    static String choice(Object value, String path, String choices) {
        if (!Arrays.asList(choices.split(" ")).contains(value)) throw new Invalid(path);
        return (String) value;
    }

    static void rejectUnknown(Map<String, Object> patch, Map<String, ?> known, String path) {
        for (String key : patch.keySet()) {
            if (!known.containsKey(key)) throw new Invalid(path.isEmpty() ? key : path + "." + key);
        }
    }

    boolean discardedStored() { return discardedStored; }

    synchronized Map<String, Object> mqttPrivate() { return new LinkedHashMap<>(object(groups.get("mqtt"), "mqtt")); }

    synchronized Map<String,Object> ntfyPrivate() { return new LinkedHashMap<>(object(groups.get("ntfy"), "ntfy")); }

    @SuppressWarnings("unchecked")
    static List<Map<String,Object>> topics(Map<String,Object> group) {
        return (List<Map<String,Object>>)group.get("topics");
    }

    synchronized int revision() { return revision; }
    synchronized int port() { return (int) object(groups.get("device"), "device").get("port"); }
    synchronized String name() { return (String) object(groups.get("device"), "device").get("name"); }
    synchronized boolean pairingAllowed() { return (boolean) object(groups.get("pairing"), "pairing").get("allowed"); }

    synchronized String categoryId(String id) { return categories.containsKey(id) ? id : "general"; }
    synchronized Map<String, Object> display() { return object(groups.get("display"), "display"); }

    synchronized Style resolve(String categoryId, Map<String, Object> requestStyle) {
        return resolve(categoryId, null, requestStyle);
    }

    synchronized Style resolve(String categoryId, String requestLook, Map<String, Object> requestStyle) {
        Category category = categories.get(categoryId(categoryId));
        Style style = category.style.merge(look(category.look).style, "look").merge(category.overrides, "style");
        if (requestLook != null) {
            Look look = look(requestLook);
            if (look == null) throw CardSpec.bad("look");
            style = style.merge(look.style, "look");
        }
        return requestStyle == null ? style : style.merge(requestStyle, "style");
    }

    synchronized Map<String, Object> effective(String categoryId) { return resolve(categoryId, null).fields; }

    synchronized boolean categoryAnnounce(String id) { return categories.get(categoryId(id)).announce; }

    synchronized Look look(String id) {
        Look builtin = Look.builtin(id);
        return builtin != null || id == null ? builtin : customLooks.get(id);
    }

    synchronized boolean hasLook(String id) { return look(id) != null; }

    synchronized Map<String, Object> chipVisual(String lookOrNull) {
        Look chosen = look(lookOrNull);
        if (chosen == null) chosen = look((String) display().get("chip_look"));
        Map<String, Object> result = new LinkedHashMap<>(Look.BASE);
        result.putAll(chosen.style);
        return result;
    }

    synchronized List<Map<String, Object>> looks() {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (String id : Look.BUILTIN_IDS) rows.add(Look.builtin(id).row());
        for (Look look : customLooks.values()) rows.add(look.row());
        return rows;
    }

    static void writableLookId(String key) {
        id(key, "id");
        if (Look.builtin(key) != null) throw new Api.Failure(409, "built-in look");
    }

    synchronized Map<String, Object> putLook(String key, Map<String, Object> patch) {
        writableLookId(key);
        Look updated = Look.validate(key, patch, "look");
        if (customLooks.size() == 32 && !customLooks.containsKey(key)) throw new Api.Failure(409, "too many looks");
        Map<String, Look> next = new TreeMap<>(customLooks);
        next.put(key, updated);
        commit(groups, categories, next);
        return updated.row();
    }

    synchronized void deleteLook(String key) {
        writableLookId(key);
        if (!customLooks.containsKey(key)) throw new Api.Failure(404, "unknown look");
        Map<String, Look> remaining = new TreeMap<>(customLooks);
        remaining.remove(key);
        Map<String, Category> entries = new LinkedHashMap<>(categories);
        entries.replaceAll((id, c) -> key.equals(c.look) ? c.merge(Json.fields("look", "glass"), "category", true) : c);
        Map<String, Object> next = new LinkedHashMap<>(groups);
        if (key.equals(display().get("chip_look"))) {
            Map<String, Object> display = new LinkedHashMap<>(display());
            display.put("chip_look", "glass");
            next.put("display", Collections.unmodifiableMap(display));
        }
        Map<String,Object> ntfy = ntfyPrivate();
        List<Map<String,Object>> topics = new ArrayList<>();
        for (Map<String,Object> row : topics(ntfy)) {
            if (key.equals(row.get("look"))) {
                row = new LinkedHashMap<>(row);
                row.put("look", null);
            }
            topics.add(Collections.unmodifiableMap(row));
        }
        ntfy.put("topics", Collections.unmodifiableList(topics));
        next.put("ntfy", Collections.unmodifiableMap(ntfy));
        commit(next, entries, remaining);
    }

    private static List<Map<String, Object>> rows(Map<String, Category> entries) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Category entry : entries.values()) result.add(entry.document());
        return result;
    }

    synchronized List<Map<String, Object>> categories() {
        List<Map<String, Object>> result = rows(categories);
        for (Map<String, Object> row : result) row.put("effective", effective((String) row.get("id")));
        return result;
    }

    private static List<Map<String, Object>> lookDocuments(Map<String, Look> looks) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Look look : looks.values()) result.add(look.document());
        return result;
    }

    private static Map<String, Object> document(int revision, Map<String, Object> groups, Map<String, Category> categories, Map<String, Look> looks) {
        Map<String, Object> result = Json.fields("schema", 2, "revision", revision);
        result.putAll(groups);
        result.put("categories", rows(categories));
        result.put("looks", lookDocuments(looks));
        return result;
    }

    synchronized Map<String, Object> document() {
        Map<String, Object> result = document(revision, groups, categories, customLooks);
        Map<String, Object> mqtt = new LinkedHashMap<>(object(result.get("mqtt"), "mqtt"));
        mqtt.put("password_set", !"".equals(mqtt.remove("password")));
        result.put("mqtt", mqtt);
        Map<String,Object> ntfy = ntfyPrivate();
        ntfy.put("token_set", !"".equals(ntfy.remove("token")));
        ntfy.remove("token_server");
        result.put("ntfy", ntfy);
        return result;
    }

    synchronized Map<String, Object> put(Map<String, Object> patch) {
        apply(patch, false);
        return document();
    }

    private void apply(Map<String, Object> patch, boolean restoring) {
        if (patch.containsKey("schema")) integer(patch.get("schema"), "schema", 2, 2);
        Map<String, Look> looks = patch.containsKey("looks") ? replaceLooks(patch.get("looks")) : customLooks;
        Map<String, Object> next = new LinkedHashMap<>();
        for (String key : groups.keySet()) {
            Map<String, Object> current = object(groups.get(key), key);
            Map<String, Object> edit = object(patch.getOrDefault(key, Collections.emptyMap()), key);
            next.put(key, mergeGroup(key, current, edit, next, patch, restoring));
        }
        Map<String, Category> entries = categories;
        if (patch.containsKey("categories")) entries = replaceCategories(patch.get("categories"));
        int index = 0;
        for (Category category : entries.values()) {
            if (Look.builtin(category.look) == null && !looks.containsKey(category.look)) throw new Invalid("categories." + index + ".look");
            index++;
        }
        int topicIndex = 0;
        for (Map<String,Object> topic : topics(object(next.get("ntfy"), "ntfy"))) {
            String look = (String)topic.get("look");
            if (look != null && Look.builtin(look) == null && !looks.containsKey(look))
                throw new Invalid("ntfy.topics." + topicIndex + ".look");
            topicIndex++;
        }
        String chipLook = (String) object(next.get("display"), "display").get("chip_look");
        if (Look.builtin(chipLook) == null && !looks.containsKey(chipLook)) throw new Invalid("display.chip_look");
        rejectUnknown(patch, document(revision, next, entries, looks), "");
        if (restoring) {
            groups = next;
            categories = entries;
            customLooks = looks;
        } else {
            commit(next, entries, looks);
        }
    }

    private Map<String, Object> mergeGroup(String path, Map<String, Object> current, Map<String, Object> patch,
            Map<String, Object> candidate, Map<String, Object> rootPatch, boolean restoring) {
        Map<String, Object> next = new LinkedHashMap<>();
        for (String key : current.keySet()) {
            String field = path + "." + key;
            Object old = current.get(key);
            Object v = patch.getOrDefault(key, old);
            if (field.equals("ntfy.token")) {
                boolean moved = !next.get("server").equals(current.get("server"));
                if (v == null) v = "";
                else if (!(v instanceof String)) throw new Invalid(field);
                else if (!patch.containsKey(key) || "".equals(v)) v = moved ? "" : old;
                else if (((String)v).length() > 256 || ((String)v).codePoints().anyMatch(c ->
                        Character.isWhitespace(c) || Character.isSpaceChar(c) || c == 0x85)) throw new Invalid(field);
                if (restoring && !next.get("server").equals(patch.get("token_server"))) v = "";
            } else if (field.equals("ntfy.token_server")) {
                if (!restoring && patch.containsKey(key)) throw new Invalid(field);
                v = "".equals(next.get("token")) ? "" : next.get("server");
            } else if (old instanceof Map) {
                Map<String, Object> nested = object(patch.getOrDefault(key, Collections.emptyMap()), field);
                v = mergeGroup(field, object(old, field), nested, candidate, rootPatch, restoring);
            } else {
                if (old instanceof String && !(v instanceof String) || old instanceof Boolean && !(v instanceof Boolean)) {
                    throw new Invalid(field);
                }
                switch (field) {
                    case "device.name":
                        String name = (String) v;
                        if (name.isEmpty() || name.codePointCount(0, name.length()) > 40) throw new Invalid(field);
                        break;
                    case "device.port": v = integer(v, field, 1024, 65535); break;
                    case "display.max_visible": v = integer(v, field, 1, 6); break;
                    case "display.chip_placement": v = choice(v, field, ChipSpec.POSITIONS); break;
                    case "display.night_start": case "display.night_end":
                        if (!((String) v).matches("([01][0-9]|2[0-3]):[0-5][0-9]")) throw new Invalid(field);
                        break;
                    case "display.sticky_cap": v = integer(v, field, 60, 86400); break;
                    case "audio.volume_tv": case "audio.volume_speaker": v = integer(v, field, 0, 100); break;
                    case "audio.speaker_at_night": v = choice(v, field, "urgent always never"); break;
                    case "ntfy.server": v = Ntfy.canonical((String)v); break;
                    case "ntfy.catch_up": v = integer(v, field, 0, 600); break;
                    case "ntfy.topics": v = validateTopics(v, rootPatch); break;
                    case "mqtt.port": v = integer(v, field, 1, 65535); break;
                    case "mqtt.password": if ("".equals(v)) v = old; break;
                    case "mqtt.base_topic":
                        if (!((String) v).matches("[A-Za-z0-9_-][A-Za-z0-9_/-]{0,63}")) throw new Invalid(field);
                        break;
                    case "compat.tvoverlay.port":
                        v = integer(v, field, 1024, 65535);
                        if (v.equals(object(candidate.get("device"), "device").get("port"))) throw new Invalid(field);
                        break;
                    case "compat.tvoverlay.allowed": v = allowed(v, field, (boolean) next.get("enabled")); break;
                    case "compat.tvoverlay.category":
                        id(v, field);
                        if (!willExist(v, rootPatch)) {
                            if (!old.equals(v) || !categories.containsKey(v)) throw new Invalid(field);
                            v = "general";
                        }
                        break;
                }
            }
            next.put(key, v);
        }
        Map<String, Object> writable = new LinkedHashMap<>(patch);
        if (path.equals("ntfy")) writable.remove("token_set");
        if (path.equals("mqtt")) writable.remove("password_set");
        rejectUnknown(writable, current, path);
        return Collections.unmodifiableMap(next);
    }

    private List<Map<String,Object>> validateTopics(Object value, Map<String,Object> rootPatch) {
        if (!(value instanceof List) || ((List<?>)value).size() > 4) throw new Invalid("ntfy.topics");
        List<Map<String,Object>> result = new ArrayList<>();
        Set<String> names = new HashSet<>();
        for (Object item : (List<?>)value) {
            String path = "ntfy.topics." + result.size();
            Map<String,Object> input = object(item, path);
            Map<String,Object> row = Json.fields("topic", "", "label", "", "category", "general", "look", null, "speak", false);
            rejectUnknown(input, row, path);
            row.putAll(input);
            Object name = row.get("topic"), label = row.get("label"), look = row.get("look");
            if (!(name instanceof String) || !((String)name).matches("[A-Za-z0-9_-]{1,64}") || !names.add((String)name))
                throw new Invalid(path + ".topic");
            if (!(label instanceof String) || ((String)label).length() > 40) throw new Invalid(path + ".label");
            if (!(row.get("speak") instanceof Boolean)) throw new Invalid(path + ".speak");
            if (look != null) id(look, path + ".look");
            String category = id(row.get("category"), path + ".category");
            if (!willExist(category, rootPatch)) {
                boolean retained = topics(ntfyPrivate()).stream().anyMatch(previous ->
                        name.equals(previous.get("topic")) && category.equals(previous.get("category")));
                if (!retained || !categories.containsKey(category)) throw new Invalid(path + ".category");
                row.put("category", "general");
            }
            result.add(Collections.unmodifiableMap(row));
        }
        return Collections.unmodifiableList(result);
    }

    private static List<String> allowed(Object value, String field, boolean enabled) {
        if (!(value instanceof List)) throw new Invalid(field);
        List<?> list = (List<?>) value;
        if (list.size() > 16 || enabled && list.isEmpty()) throw new Invalid(field);
        List<String> copy = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof String)) throw new Invalid(field);
            try { Cidr.parse((String) item); }
            catch (IllegalArgumentException e) { throw new Invalid(field); }
            copy.add((String) item);
        }
        return Collections.unmodifiableList(copy);
    }

    // Check references before reporting category-row errors, in document order.
    private boolean willExist(Object id, Map<String, Object> patch) {
        if (Category.isBuiltin(id)) return true;
        if (!patch.containsKey("categories")) return categories.containsKey(id);
        Object list = patch.get("categories");
        if (list instanceof List) {
            for (Object row : (List<?>) list) {
                if (row instanceof Map && id.equals(((Map<?, ?>) row).get("id"))) return true;
            }
        }
        return false;
    }

    private static String id(Object value, String path) {
        if (!(value instanceof String) || !((String) value).matches("[a-z0-9_]{1,32}")) throw new Invalid(path);
        return (String) value;
    }

    private static Map<String, Look> replaceLooks(Object value) {
        if (!(value instanceof List) || ((List<?>) value).size() > 32) throw new Invalid("looks");
        Map<String, Look> result = new TreeMap<>();
        for (Object item : (List<?>) value) {
            String path = "looks." + result.size();
            Map<String, Object> row = new LinkedHashMap<>(object(item, path));
            String key = id(row.remove("id"), path + ".id");
            if (Look.builtin(key) != null || result.containsKey(key)) throw new Invalid(path + ".id");
            result.put(key, Look.validate(key, row, path));
        }
        return result;
    }

    private static Map<String, Category> replaceCategories(Object value) {
        if (!(value instanceof List)) throw new Invalid("categories");
        Map<String, Category> entries = new LinkedHashMap<>();
        for (Object item : (List<?>) value) {
            String path = "categories." + entries.size();
            Map<String, Object> row = object(item, path);
            String id = id(row.get("id"), path + ".id");
            if (entries.containsKey(id)) throw new Invalid(path + ".id");
            entries.put(id, Category.base(id).merge(row, path, false));
        }
        for (Category builtin : Category.defaults().values()) entries.putIfAbsent(builtin.id, builtin);
        if (entries.size() > 32) throw new Invalid("categories");
        return entries;
    }

    synchronized Map<String, Object> putCategory(String key, Map<String, Object> patch) {
        String id = id(key, "id");
        if (!categories.containsKey(id) && categories.size() == 32) throw new Api.Failure(409, "too many categories");
        Category updated = categories.getOrDefault(id, Category.base(id)).merge(patch, "category", true);
        if (!hasLook(updated.look)) throw new Invalid("category.look");
        Map<String, Category> next = new LinkedHashMap<>(categories);
        next.put(id, updated);
        commit(groups, next, customLooks);
        return updated.document();
    }

    synchronized void deleteCategory(String key) {
        String id = id(key, "id");
        if (Category.isBuiltin(id)) throw new Api.Failure(409, "built-in category");
        if (!categories.containsKey(id)) throw new Api.Failure(404, "unknown category");
        Map<String, Category> next = new LinkedHashMap<>(categories);
        next.remove(id);
        apply(Json.fields("categories", rows(next)), false);
    }

    private void commit(Map<String, Object> next, Map<String, Category> entries, Map<String, Look> looks) {
        if (groups.equals(next) && rows(categories).equals(rows(entries))
                && lookDocuments(customLooks).equals(lookDocuments(looks))) return;
        int updated = Math.incrementExact(revision);
        save.accept(Json.stringify(document(updated, next, entries, looks)));
        groups = next;
        categories = entries;
        customLooks = looks;
        revision = updated;
        events.add("settings.changed", Json.fields("revision", revision));
    }
}
