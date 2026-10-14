package top.everblue.oj.community.interaction;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Result returned to HTTP adapters after an idempotent interaction action. */
public record InteractionResult(boolean changed, boolean active, Map<String, Integer> statistics) {
    public InteractionResult {
        statistics = Collections.unmodifiableMap(new LinkedHashMap<>(statistics == null ? Map.of() : statistics));
    }

    public int statistic(String name) {
        return statistics.getOrDefault(name, 0);
    }
}
