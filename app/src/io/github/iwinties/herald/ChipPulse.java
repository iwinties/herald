package io.github.iwinties.herald;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/** Remember only visible high chips. Losing either property ends that pulse. */
final class ChipPulse {
    private Map<String, Map<String, Object>> previous = new HashMap<>();
    private final Map<String, Long> starts = new HashMap<>();

    void update(Map<String, ChipLayout.Group> groups, long now) {
        Map<String, Map<String, Object>> next = new HashMap<>();
        for (ChipLayout.Group group : groups.values()) {
            for (Map<String, Object> row : group.visible) {
                if (!"high".equals(row.get("priority"))) continue;
                String id = (String) row.get("id");
                next.put(id, row);
                Map<String, Object> old = previous.get(id);
                if (old == null || !Objects.equals(old.get("text"), row.get("text"))
                        || !Objects.equals(old.get("icon"), row.get("icon"))
                        || !Objects.equals(old.get("color"), row.get("color"))) starts.put(id, now);
            }
        }
        starts.keySet().retainAll(next.keySet());
        previous = next;
    }

    long start(String id) { return starts.getOrDefault(id, -1L); }
}
