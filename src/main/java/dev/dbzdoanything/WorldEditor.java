package dev.dbzdoanything;

import com.dragonminez.common.config.ConfigManager;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.dbzdoanything.RevisionStore.Change;
import dev.dbzdoanything.RevisionStore.Revision;
import dev.dbzdoanything.compat.EditorCompatibility;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;

public final class WorldEditor {
    private static volatile WorldEditor current;

    private final MinecraftServer server;
    private final RevisionStore store;
    private NativeCatalog.State baseline;
    private NativeCatalog.State published;
    private Map<String, JsonObject> expected;
    private volatile Revision revision;
    private String conflict;

    private WorldEditor(MinecraftServer server) throws IOException {
        this.server = server;
        this.store = new RevisionStore(server.getWorldPath(LevelResource.ROOT).resolve("dbzdoanything"));
        baseline = NativeCatalog.State.capture();
        revision = store.load();
        published = candidate(revision);
        published.publish();
        EditorCompatibility.refresh(revision.changes().keySet());
        expected = published.documents();
        EditorMod.LOGGER.info("DMZ Do Anything loaded world revision {}", revision.number());
    }

    static void start(MinecraftServer server) {
        try {
            current = new WorldEditor(server);
        } catch (IOException | RuntimeException e) {
            throw new IllegalStateException("Could not safely replay the world's race and form edits.", e);
        }
    }

    static void stop(MinecraftServer server) {
        var editor = current;
        if (editor != null && editor.server == server) {
            current = null;
            editor.baseline.publish();
        }
    }

    static WorldEditor get(MinecraftServer server) {
        var editor = current;
        if (editor == null || editor.server != server || !server.isSameThread()) {
            throw new IllegalStateException("The editor requires a running world on the server thread.");
        }
        return editor;
    }

    public static boolean isRetired(String race) {
        var editor = current;
        return editor != null && race != null && editor.revision.retiredRaces().contains(race.toLowerCase(java.util.Locale.ROOT));
    }

    static boolean restricts(ServerPlayer player, SpecialRule rule) {
        var editor = current;
        return editor != null && player.server == editor.server && editor.revision.specialRules().contains(rule.id);
    }

    public static boolean overrides(String path) {
        var editor = current;
        return editor != null && editor.revision.changes().containsKey(path);
    }

    Revision revision() {
        return revision;
    }

    String conflict() { return conflict; }

    Map<String, JsonObject> documents() {
        return candidate(revision, false).documents();
    }

    void synchronize(ServerPlayer player) {
        synchronize(player, false);
    }

    void synchronize(ServerPlayer player, boolean joining) {
        ConfigManager.sendConfigSync(player);
        EditorNetwork.sendRevision(player, revision, joining);
    }

    private NativeCatalog.State candidate(Revision edit) {
        return candidate(edit, true);
    }

    private NativeCatalog.State candidate(Revision edit, boolean hideDisabled) {
        var result = baseline.copy();
        var originals = baseline.documents();
        edit.changes().forEach((path, change) -> {
            CatalogValidation.checkPath(path);
            var merged = merge(change.baseline(), change.value(), originals.get(path), path);
            if (merged == null || !merged.isJsonObject()) throw new IllegalArgumentException("Missing document " + path);
            var adapter = EditorCompatibility.find(path, merged.getAsJsonObject());
            if (adapter != null && hideDisabled) adapter.validate(path, originals.get(path), merged.getAsJsonObject());
            result.update(path, merged.getAsJsonObject());
        });
        if (hideDisabled) {
            if (!edit.retiredRaces().isEmpty() && result.races().stream().allMatch(edit.retiredRaces()::contains)) {
                throw new IllegalArgumentException("Keep at least one race available for new characters.");
            }
            CatalogValidation.check(result, edit);
            edit.disabledForms().forEach(id -> {
                int split = id.lastIndexOf('/');
                String path = id.substring(0, split);
                var group = result.documents().get(path);
                if (group == null) throw new IllegalArgumentException("Unknown disabled form group " + path);
                group.getAsJsonObject("forms").remove(id.substring(split + 1));
                result.update(path, group);
            });
        }
        return result;
    }

    void validate(Revision edit) { candidate(edit); }

    java.util.List<String> affected(Revision edit) {
        if (!affectsGameplay(edit)) return java.util.List.of();
        var next = candidate(edit);
        var affected = new java.util.ArrayList<String>();
        for (var player : server.getPlayerList().getPlayers()) {
            var losses = PlayerRefresh.forecast(player, next);
            if (!losses.isEmpty()) affected.add(player.getScoreboardName() + " returns to base from " + String.join(", ", losses));
        }
        return affected;
    }

    record ReviewRow(String kind, String path, java.util.List<String> keys, JsonElement before, JsonElement after, String detail) {}

    java.util.List<ReviewRow> review(Revision edit) {
        var before = documents();
        var after = candidate(edit, false).documents();
        var lines = new java.util.ArrayList<ReviewRow>();
        for (var rule : SpecialRule.values()) {
            boolean wasEnabled = revision.specialRules().contains(rule.id);
            boolean enabled = edit.specialRules().contains(rule.id);
            if (wasEnabled != enabled) lines.add(new ReviewRow("specialRule", "specialRules", java.util.List.of(rule.id),
                    new com.google.gson.JsonPrimitive(wasEnabled), new com.google.gson.JsonPrimitive(enabled), ""));
        }
        after.forEach((path, document) -> diff(lines, path, java.util.List.of(), before.get(path), document));
        edit.names().forEach((key, name) -> {
            var previous = revision.names().get(key);
            if (!Objects.equals(name, previous)) lines.add(new ReviewRow("name", key, java.util.List.of(),
                    previous == null ? null : new com.google.gson.JsonPrimitive(previous), new com.google.gson.JsonPrimitive(name), ""));
        });
        revision.names().keySet().stream().filter(key -> !edit.names().containsKey(key))
                .forEach(key -> lines.add(action("sourceName", key)));
        edit.disabledForms().stream().filter(id -> !revision.disabledForms().contains(id))
                .forEach(id -> lines.add(action("disableForm", id)));
        revision.disabledForms().stream().filter(id -> !edit.disabledForms().contains(id))
                .forEach(id -> lines.add(action("enableForm", id)));
        edit.retiredRaces().stream().filter(id -> !revision.retiredRaces().contains(id))
                .forEach(id -> lines.add(action("deleteRace", id)));
        revision.retiredRaces().stream().filter(id -> !edit.retiredRaces().contains(id))
                .forEach(id -> lines.add(action("restoreRace", id)));
        if (lines.isEmpty()) throw new IllegalArgumentException("There are no changes to apply.");
        lines.addAll(0, affected(edit).stream().map(player -> new ReviewRow("player", "", java.util.List.of(), null, null, player)).toList());
        return lines;
    }

    private static ReviewRow action(String kind, String path) {
        return new ReviewRow(kind, path, java.util.List.of(), null, null, "");
    }

    private static void diff(java.util.List<ReviewRow> lines, String path, java.util.List<String> field, JsonElement before, JsonElement after) {
        if (Objects.equals(before, after)) return;
        if (before == null) {
            lines.add(new ReviewRow("add", path, field, null, after.isJsonPrimitive() ? after : null, ""));
        } else if (after == null) {
            lines.add(new ReviewRow("remove", path, field, before.isJsonPrimitive() ? before : null, null, ""));
        } else if (before instanceof JsonObject left && after instanceof JsonObject right) {
            var keys = new LinkedHashSet<>(left.keySet());
            keys.addAll(right.keySet());
            for (String key : keys) diff(lines, path, append(field, key), left.get(key), right.get(key));
        } else if (before instanceof com.google.gson.JsonArray left && after instanceof com.google.gson.JsonArray right) {
            for (int i = 0; i < Math.max(left.size(), right.size()); i++) {
                diff(lines, path, append(field, Integer.toString(i)),
                        i < left.size() ? left.get(i) : null, i < right.size() ? right.get(i) : null);
            }
        } else {
            lines.add(new ReviewRow("value", path, field, before, after, ""));
        }
    }

    private static java.util.List<String> append(java.util.List<String> parent, String leaf) {
        var keys = new java.util.ArrayList<>(parent);
        keys.add(leaf);
        return keys;
    }

    void commit(Revision edit) throws IOException {
        detectSourceChanges();
        if (conflict != null) throw new IllegalStateException("Resolve the native source conflict first. " + conflict);
        if (edit.number() != revision.number() + 1) throw new IllegalArgumentException("The draft is stale. Reopen it.");
        var next = candidate(edit);
        var previous = published;
        boolean gameplay = affectsGameplay(edit);
        try {
            if (gameplay) {
                next.publish();
                EditorCompatibility.refresh(edit.changes().keySet());
            }
            store.commit(edit);
        } catch (IOException | RuntimeException error) {
            if (gameplay) published.publish();
            throw error;
        }
        revision = edit;
        if (gameplay) {
            published = next;
            expected = next.documents();
        }
        for (var player : server.getPlayerList().getPlayers()) {
            synchronize(player);
        }
        if (gameplay) {
            for (var player : server.getPlayerList().getPlayers()) PlayerRefresh.reconcile(player, previous);
        }
        EditorMod.LOGGER.info("DMZ Do Anything committed world revision {}", edit.number());
    }

    private boolean affectsGameplay(Revision edit) {
        return !revision.changes().equals(edit.changes()) || !revision.disabledForms().equals(edit.disabledForms());
    }

    void detectSourceChanges() {
        var actual = NativeCatalog.State.capture().documents();
        if (actual.equals(expected)) return;
        var before = baseline;
        try {
            var updates = baseline.copy();
            var upstream = baseline.documents();
            for (var entry : actual.entrySet()) {
                String path = entry.getKey();
                JsonObject wanted = expected.get(path);
                if (Objects.equals(wanted, entry.getValue())) continue;
                var value = merge(wanted, entry.getValue(), upstream.get(path), path);
                if (value == null || !value.isJsonObject()) throw new IllegalArgumentException("Source conflict at " + path);
                updates.update(path, value.getAsJsonObject());
            }
            for (String path : expected.keySet()) {
                if (!actual.containsKey(path)) throw new IllegalArgumentException("A loaded definition disappeared at " + path);
            }
            baseline = updates;
            var next = candidate(revision);
            next.publish();
            published = next;
            expected = next.documents();
            conflict = null;
            EditorNetwork.invalidateReviews();
        } catch (RuntimeException error) {
            baseline = before;
            conflict = error.getMessage();
            EditorNetwork.invalidateReviews();
            EditorMod.LOGGER.error("An external native config write conflicts with world revision {}", revision.number(), error);
        }
    }

    Revision edit(Map<String, JsonObject> documents, Map<String, String> names) {
        var changes = new LinkedHashMap<>(revision.changes());
        var origins = baseline.documents();
        documents.forEach((path, content) -> {
            var original = origins.get(path);
            if (Objects.equals(original, content)) changes.remove(path);
            else changes.put(path, new Change(original, content));
        });
        var translations = new LinkedHashMap<>(revision.names());
        translations.putAll(names);
        return new Revision(revision.number() + 1, changes, translations, revision.disabledForms(), revision.retiredRaces(),
                revision.specialRules());
    }

    Revision revert(long number) throws IOException {
        if (number >= revision.number()) throw new IllegalArgumentException("Choose an earlier committed revision.");
        var old = store.read(number);
        var changes = new LinkedHashMap<>(old.changes());
        var retired = new LinkedHashSet<>(old.retiredRaces());
        var disabled = new LinkedHashSet<>(old.disabledForms());
        var translations = new LinkedHashMap<>(old.names());
        revision.changes().forEach((path, change) -> {
            if (change.baseline() != null || changes.containsKey(path)) return;
            changes.put(path, change);
            if (path.endsWith("/character")) retired.add(path.split("/")[1]);
            if (path.startsWith("forms/") || path.contains("/forms/")) {
                change.value().getAsJsonObject("forms").keySet().forEach(id -> disabled.add(path + "/" + id));
            }
        });
        var before = candidate(revision, false).documents();
        var after = candidate(new Revision(revision.number() + 1, changes, translations, disabled, retired, old.specialRules()), false).documents();
        var origins = baseline.documents();
        before.forEach((path, document) -> {
            var desired = after.get(path);
            if (desired == null) return;
            var original = origins.get(path);
            if (path.startsWith("forms/") || path.contains("/forms/")) {
                var stages = document.getAsJsonObject("forms");
                var restored = desired.getAsJsonObject("forms");
                stages.entrySet().forEach(entry -> {
                    if (restored.has(entry.getKey())) return;
                    restored.add(entry.getKey(), entry.getValue());
                    disabled.add(path + "/" + entry.getKey());
                    changes.put(path, new Change(original, desired));
                });
            }
            if (!path.endsWith("/character")) return;
            var prices = document.getAsJsonObject("formSkillsCosts");
            if (prices == null) return;
            var restored = desired.getAsJsonObject("formSkillsCosts");
            if (restored == null) {
                restored = new JsonObject();
                desired.add("formSkillsCosts", restored);
            }
            for (var entry : prices.entrySet()) {
                var prior = entry.getValue().getAsJsonObject().getAsJsonArray("prices");
                var row = restored.getAsJsonObject(entry.getKey());
                if (row == null) {
                    restored.add(entry.getKey(), entry.getValue());
                    changes.put(path, new Change(original, desired));
                    continue;
                }
                var values = row.getAsJsonArray("prices");
                if (values.size() >= prior.size()) continue;
                while (values.size() < prior.size()) values.add(prior.get(values.size()));
                changes.put(path, new Change(original, desired));
            }
        });
        return new Revision(revision.number() + 1, changes, translations, disabled, retired, old.specialRules());
    }

    public static void nativeReload(String path) {
        var editor = current;
        if (editor == null) return;
        if (!editor.server.isSameThread()) {
            throw new IllegalStateException("Dragon Mine Z race reload must run on the server thread while the editor is active.");
        }
        var loaded = NativeCatalog.State.capture();
        var before = editor.baseline;
        try {
            if (path == null) {
                editor.baseline = loaded;
            } else if (path.startsWith("races/") || path.startsWith("forms/")) {
                var document = loaded.documents().get(path);
                if (document != null) {
                    editor.baseline = before.copy();
                    editor.baseline.update(path, document);
                }
            } else {
                return;
            }
            var next = editor.candidate(editor.revision);
            next.publish();
            EditorCompatibility.refresh(editor.revision.changes().keySet());
            editor.published = next;
            editor.expected = next.documents();
            editor.conflict = null;
            EditorNetwork.invalidateReviews();
        } catch (RuntimeException conflict) {
            editor.baseline = before;
            editor.published.publish();
            EditorNetwork.invalidateReviews();
            editor.conflict = conflict.getMessage();
            EditorMod.LOGGER.error("Native reload conflicts with world revision {}", editor.revision.number(), conflict);
        }
    }

    private static JsonElement merge(JsonElement base, JsonElement edited, JsonElement upstream, String path) {
        if (Objects.equals(base, edited)) return upstream;
        if (Objects.equals(base, upstream) || Objects.equals(edited, upstream)) return edited;
        if (base instanceof JsonObject previous && edited instanceof JsonObject desired && upstream instanceof JsonObject actual) {
            var keys = new LinkedHashSet<>(actual.keySet());
            keys.addAll(previous.keySet());
            keys.addAll(desired.keySet());
            var result = new JsonObject();
            for (String key : keys) {
                var value = merge(previous.get(key), desired.get(key), actual.get(key), path + "." + key);
                if (value != null) result.add(key, value);
            }
            return result;
        }
        throw new IllegalArgumentException("Source conflict at " + path);
    }
}
