package dev.dbzdoanything.compat;

import com.google.gson.JsonObject;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Register adapters during common setup, before a world starts. */
public final class EditorCompatibility {
    public interface Adapter {
        String name();

        boolean owns(String documentPath, JsonObject effective);

        /** Return a reason to lock the field, or null to use the editor's native field rules. */
        default String lockReason(String documentPath, String field) { return null; }

        /** Opt in additional typed leaf fields whose live behavior the adapter has verified. */
        default boolean supportsField(String documentPath, String field) { return false; }

        /** Called before publication, including replay and rollback. */
        default void validate(String documentPath, JsonObject source, JsonObject candidate) {}

        /** Clear adapter-owned caches after native definitions have been installed. */
        default void refresh(Set<String> publishedPaths) {}

        /** Allow reversible disable only where the source does not recreate the form at runtime. */
        default boolean canDisable(String documentPath, String formId) { return false; }
    }

    private static final Map<String, Adapter> ADAPTERS = new LinkedHashMap<>();

    private EditorCompatibility() {}

    public static synchronized void register(String modId, Adapter adapter) {
        if (ADAPTERS.putIfAbsent(Objects.requireNonNull(modId), Objects.requireNonNull(adapter)) != null) {
            throw new IllegalArgumentException("Duplicate race editor adapter for " + modId);
        }
    }

    public static synchronized Adapter find(String path, JsonObject effective) {
        return ADAPTERS.values().stream().filter(adapter -> adapter.owns(path, effective)).findFirst().orElse(null);
    }

    public static synchronized Adapter bySource(String source) {
        return ADAPTERS.values().stream().filter(adapter -> adapter.name().equals(source)).findFirst().orElse(null);
    }

    public static synchronized void refresh(Set<String> paths) {
        var stable = Set.copyOf(paths);
        ADAPTERS.values().forEach(adapter -> adapter.refresh(stable));
    }
}
