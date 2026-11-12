package dev.dbzdoanything;

import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

final class PublishedRaceDraft {
    private record Change(JsonPrimitive expected, String value) {}

    record Conflict(EditorField.Address address, EditorField field, String draft, boolean canKeepDraft) {}

    private final Map<EditorField.Address, Change> changes = new LinkedHashMap<>();

    int size() { return changes.size(); }
    boolean isEmpty() { return changes.isEmpty(); }
    void clear() { changes.clear(); }

    String display(EditorField field) {
        var change = changes.get(field.address());
        return change == null ? field.value().getAsString() : change.value();
    }

    boolean staged(EditorField field) { return changes.containsKey(field.address()); }

    void stage(EditorField field, String input) {
        if (field.reason() != null) throw new IllegalArgumentException(field.reason());
        var value = EditorFields.value(field.value(), input, field.key());
        if (value.equals(field.value())) changes.remove(field.address());
        else changes.put(field.address(), new Change(field.value(), value.getAsString()));
    }

    void refresh(JsonObject catalog) {
        changes.entrySet().removeIf(item -> {
            var field = EditorField.resolve(catalog, item.getKey());
            return canKeep(field, item.getValue()) && parsed(field, item.getValue()).equals(field.value());
        });
    }

    List<Conflict> conflicts(JsonObject catalog) {
        return changes.entrySet().stream().map(item -> {
            var field = EditorField.resolve(catalog, item.getKey());
            var change = item.getValue();
            boolean canKeep = canKeep(field, change);
            if (canKeep && field.value().getAsString().equals(change.expected().getAsString())) return null;
            return new Conflict(item.getKey(), field, change.value(), canKeep);
        }).filter(Objects::nonNull).toList();
    }

    void useSaved(EditorField.Address address) {
        changes.remove(address);
    }

    void keepDraft(JsonObject catalog, EditorField.Address address) {
        var change = changes.get(address);
        var field = EditorField.resolve(catalog, address);
        if (change == null || !canKeep(field, change)) throw new IllegalArgumentException("This edit can no longer be applied.");
        stage(field, change.value());
    }

    List<EditorDraft.FieldEdit> edits() {
        return changes.entrySet().stream().map(item -> new EditorDraft.FieldEdit(item.getKey().path(), item.getKey().keys(),
                item.getValue().expected().getAsString(), item.getValue().value())).toList();
    }

    JsonObject overlay(JsonObject catalog, String path) {
        var document = catalog.getAsJsonObject("documents").getAsJsonObject(path);
        if (document == null) return null;
        var result = document.deepCopy();
        changes.forEach((address, change) -> {
            if (!address.path().equals(path)) return;
            var field = EditorField.resolve(catalog, address);
            if (canKeep(field, change) && field.value().getAsString().equals(change.expected().getAsString())) {
                EditorFields.set(result, address.keys(), parsed(field, change));
            }
        });
        return result;
    }

    private static boolean canKeep(EditorField field, Change change) {
        if (field == null || field.reason() != null
                || field.value().isNumber() != change.expected().isNumber()
                || field.value().isBoolean() != change.expected().isBoolean()) return false;
        try {
            parsed(field, change);
            return true;
        } catch (IllegalArgumentException | ArithmeticException invalid) {
            return false;
        }
    }

    private static JsonPrimitive parsed(EditorField field, Change change) {
        return EditorFields.value(field.value(), change.value(), field.key());
    }
}
