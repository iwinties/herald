package io.github.iwinties.herald;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;
import java.util.function.Predicate;

final class ChipBoard {
    private Map<String, ChipSpec> chips = new LinkedHashMap<>();
    private final File file;
    private final LongSupplier clock;

    private final Predicate<String> knownLook;

    ChipBoard(File file, LongSupplier clock, Predicate<String> knownLook) {
        this.knownLook = knownLook;
        this.file = file;
        this.clock = clock;
        if (file.isFile()) {
            try {
                String json = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
                chips = parse(Json.parse(json), true);
            } catch (IOException e) { throw storeFailed(); }
        }
    }

    private static Api.Failure storeFailed() { return new Api.Failure(500, "store failed"); }

    static void checkId(String id) { if (!CardSpec.key(id)) throw CardSpec.bad("id"); }

    private Map<String, ChipSpec> live() {
        Map<String, ChipSpec> result = new LinkedHashMap<>(chips);
        long now = clock.getAsLong() / 1000;
        result.values().removeIf(c -> c.expiresAt != 0 && c.expiresAt <= now);
        return result;
    }

    private Map<String, ChipSpec> parse(Map<String, Object> doc, boolean stored) {
        Object rows = doc.get("chips");
        if (!(rows instanceof List)) throw CardSpec.bad("chip");
        Map<String, ChipSpec> result = new LinkedHashMap<>();
        for (Object item : (List<?>) rows) {
            Map<String, Object> row = CardSpec.object(item, "chip");
            Object id = row.get("id");
            if (!CardSpec.key(id) || result.containsKey(id)) throw CardSpec.bad("chip");
            result.put((String) id, ChipSpec.parse((String) id, row, clock.getAsLong() / 1000, stored, knownLook));
        }
        return result;
    }

    private void save(Map<String, ChipSpec> next) {
        if (next.size() > 12) throw new Api.Failure(409, "too many chips");
        List<Map<String, Object>> rows = new ArrayList<>();
        for (ChipSpec chip : next.values()) rows.add(chip.row());
        File tmp = new File(file.getParentFile(), ".chips.tmp");
        try {
            Files.write(tmp.toPath(), Json.stringify(Json.fields("chips", rows)).getBytes(StandardCharsets.UTF_8));
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) { throw storeFailed(); }
        finally { tmp.delete(); }
        chips = next;
    }

    synchronized void put(String id, Map<String, Object> doc) {
        checkId(id);
        ChipSpec chip = ChipSpec.parse(id, doc, clock.getAsLong() / 1000, false, knownLook);
        Map<String, ChipSpec> next = live();
        next.remove(id);
        next.put(id, chip);
        save(next);
    }

    synchronized void replace(Map<String, Object> doc) { save(parse(doc, false)); }

    synchronized boolean delete(String id) {
        checkId(id);
        Map<String, ChipSpec> next = live();
        boolean found = next.remove(id) != null;
        if (next.size() != chips.size()) save(next);
        return found;
    }

    synchronized List<Map<String, Object>> rows() {
        Map<String, ChipSpec> next = live();
        if (next.size() != chips.size()) save(next);
        List<ChipSpec> sorted = new ArrayList<>(next.values());
        Collections.reverse(sorted);
        sorted.sort(Comparator.comparing(c -> !c.priority.equals("high")));
        List<Map<String, Object>> rows = new ArrayList<>();
        for (ChipSpec chip : sorted) rows.add(chip.row());
        return rows;
    }
}
