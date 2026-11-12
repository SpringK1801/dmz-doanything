package dev.dbzdoanything;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.util.ArrayList;
import java.util.List;

record EditorField(String path, List<String> keys, JsonPrimitive value, String reason) {
    record Address(String path, List<String> keys) {}

    Address address() { return new Address(path, keys); }
    String label() { return EditorPresentation.fieldLabel(keys); }
    String key() { return keys.get(keys.size() - 1); }
    boolean appearancePreset() { return path.endsWith("/character") && EditorFields.appearancePreset(key()); }

    String help() {
        String body = EditorPresentation.fieldHelp(keys, value.isNumber(), value.isBoolean(), reason);
        if (reason != null) return "Read-only. " + body;
        return (EditorPresentation.newCharacters(keys) ? "Applies to new characters. " : "Applies live. ") + body;
    }

    static List<EditorField> list(JsonObject catalog, EditorLibrary.Entry entry) {
        var fields = new ArrayList<EditorField>();
        if (entry.race()) {
            flatten(fields, catalog, entry.path(), entry.document(), entry.document(), List.of());
            String path = entry.path().replace("/character", "/stats");
            var document = catalog.getAsJsonObject("documents").getAsJsonObject(path);
            if (document != null) flatten(fields, catalog, path, document, document, List.of());
        } else {
            var form = entry.document().getAsJsonObject("forms").getAsJsonObject(entry.id());
            if (form != null) flatten(fields, catalog, entry.path(), entry.document(), form, List.of("forms", entry.id()));
        }
        return fields;
    }

    static EditorField resolve(JsonObject catalog, Address address) {
        var document = catalog.getAsJsonObject("documents").getAsJsonObject(address.path());
        var value = document == null ? null : EditorFields.node(document, address.keys());
        return value == null || !value.isJsonPrimitive() ? null
                : field(catalog, address.path(), document, address.keys(), value.getAsJsonPrimitive());
    }

    private static void flatten(List<EditorField> fields, JsonObject catalog, String path, JsonObject document,
                                JsonElement value, List<String> keys) {
        if (value.isJsonObject()) {
            value.getAsJsonObject().entrySet().forEach(entry ->
                    flatten(fields, catalog, path, document, entry.getValue(), append(keys, entry.getKey())));
        } else if (value.isJsonArray()) {
            for (int index = 0; index < value.getAsJsonArray().size(); index++) {
                flatten(fields, catalog, path, document, value.getAsJsonArray().get(index), append(keys, Integer.toString(index)));
            }
        } else if (value.isJsonPrimitive()) {
            fields.add(field(catalog, path, document, keys, value.getAsJsonPrimitive()));
        }
    }

    private static EditorField field(JsonObject catalog, String path, JsonObject document, List<String> keys, JsonPrimitive value) {
        String owner = catalog.getAsJsonObject("sources").get(path).getAsString();
        return new EditorField(path, keys, value, EditorFields.lock(path, document, keys, owner));
    }

    private static List<String> append(List<String> keys, String child) {
        var copy = new ArrayList<>(keys);
        copy.add(child);
        return List.copyOf(copy);
    }
}
