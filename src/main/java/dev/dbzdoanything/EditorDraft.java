package dev.dbzdoanything;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.dbzdoanything.RevisionStore.Revision;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

final class EditorDraft {
    record FieldEdit(String path, List<String> field, String expected, String value) {}
    record RuleEdit(JsonElement expected, JsonElement value) {}
    record Proposal(long revision, String operation, String target, String id, String name,
                    String description, String skill, int level, List<FieldEdit> fields, Map<String, RuleEdit> specialRules) {
        Proposal(long revision, String operation, String target, String id, String name,
                 String description, String skill, int level, List<FieldEdit> fields) {
            this(revision, operation, target, id, name, description, skill, level, fields, Map.of());
        }
    }

    private EditorDraft() {}

    static Revision prepare(WorldEditor editor, Proposal proposal) {
        if (proposal == null || proposal.revision() != editor.revision().number()) {
            throw new IllegalArgumentException("This draft is stale. Refresh the catalog.");
        }
        var docs = editor.documents();
        var changes = new LinkedHashMap<String, JsonObject>();
        var names = new LinkedHashMap<String, String>();
        var disabled = new LinkedHashSet<>(editor.revision().disabledForms());
        var retired = new LinkedHashSet<>(editor.revision().retiredRaces());
        var rules = new LinkedHashSet<>(editor.revision().specialRules());
        var proposedRules = proposal.specialRules();
        if (proposedRules != null && !proposedRules.isEmpty()) {
            if (!"edit".equals(proposal.operation()) || proposedRules.size() > SpecialRule.values().length) {
                throw new IllegalArgumentException("Invalid Special Settings changes.");
            }
            proposedRules.forEach((id, edit) -> {
                var rule = SpecialRule.fromId(id);
                if (edit == null || !isBoolean(edit.expected()) || !isBoolean(edit.value())) {
                    throw new IllegalArgumentException("Choose On or Off for " + rule.label + ".");
                }
                if (rules.contains(rule.id) != edit.expected().getAsBoolean()) {
                    throw new IllegalArgumentException(rule.label + " changed. Restage it using the current world setting.");
                }
                if (edit.value().getAsBoolean()) rules.add(rule.id);
                else rules.remove(rule.id);
            });
        }
        String path = proposal.target();
        switch (proposal.operation()) {
            case "edit" -> {
                if (proposal.fields() == null || proposal.fields().size() > 100) throw new IllegalArgumentException("Too many edits.");
                for (FieldEdit edit : proposal.fields()) {
                    var current = docs.get(edit.path());
                    if (current == null || edit.field() == null || edit.field().isEmpty() || edit.field().size() > 8) {
                        throw new IllegalArgumentException("Unknown document or field.");
                    }
                    String source = source(editor, edit.path(), current);
                    String reason = EditorFields.lock(edit.path(), current, edit.field(), source);
                    if (reason != null) throw new IllegalArgumentException(reason);
                    var original = EditorFields.node(current, edit.field());
                    if (original == null || !original.isJsonPrimitive()) throw new IllegalArgumentException("Unknown field value.");
                    if (!original.getAsString().equals(edit.expected())) {
                        throw new IllegalArgumentException("The source value changed. Refresh the catalog.");
                    }
                    var edited = changes.computeIfAbsent(edit.path(), ignored -> current.deepCopy());
                    String leaf = edit.field().get(edit.field().size() - 1);
                    var value = EditorFields.value(original.getAsJsonPrimitive(), edit.value(), leaf);
                    if (edit.path().endsWith("/character") && leaf.equals("defaultBodyType")) {
                        var character = new com.dragonminez.common.stats.character.Character();
                        character.setRace(edit.path().split("/")[1]);
                        if (value.getAsInt() < character.getMinBodyType()) {
                            throw new IllegalArgumentException("This body preset is below the race's native minimum.");
                        }
                    }
                    EditorFields.set(edited, edit.field(), value);
                }
            }
            case "race" -> {
                String race = CatalogValidation.id(proposal.id());
                String prefix = "races/" + race + "/";
                if (docs.containsKey(prefix + "character")) throw new IllegalArgumentException("Race ID already exists.");
                String source = path.isBlank() ? "races/human/character" : path;
                if (!source.endsWith("/character")) throw new IllegalArgumentException("Select a source race.");
                var character = required(docs, source).deepCopy();
                String origin = source.substring(0, source.length() - "character".length());
                // A new race has no race-specific texture folder. Vanilla skin and Human rig are always available.
                if (!character.get("useVanillaSkin").getAsBoolean()) {
                    character = required(docs, "races/human/character").deepCopy();
                }
                character.addProperty("racialSkill", "");
                character.addProperty("hasSaiyanTail", false);
                character.addProperty("configVersion", "2.2.1");
                changes.put(prefix + "character", character);
                changes.put(prefix + "stats", required(docs, origin + "stats").deepCopy());
                names.put("race.dragonminez." + race, label(proposal.name(), race, 64));
                names.put("race.dragonminez." + race + ".desc", description(proposal.description()));
            }
            case "form" -> createForm(editor, proposal, docs, changes, names);
            case "deleteRace", "restoreRace" -> {
                String race = proposal.id();
                if (race == null || !path.equals("races/" + race + "/character") || !docs.containsKey(path)) {
                    throw new IllegalArgumentException("Choose a loaded race.");
                }
                boolean changed = proposal.operation().equals("deleteRace") ? retired.add(race) : retired.remove(race);
                if (!changed) throw new IllegalArgumentException("The race already has this availability.");
            }
            case "disable", "enable" -> {
                var group = required(docs, path);
                String id = CatalogValidation.id(proposal.id());
                if (!group.getAsJsonObject("forms").has(id)) throw new IllegalArgumentException("Unknown form " + id);
                if (!EditorFields.canDisable(path, group, id, source(editor, path, group))) {
                    throw new IllegalArgumentException("The source can activate or recreate this form by a hardcoded path. Clone it to manage availability.");
                }
                if (proposal.operation().equals("disable")) disabled.add(path + "/" + id);
                else disabled.remove(path + "/" + id);
            }
            case "name" -> {
                var document = required(docs, path);
                if (source(editor, path, document).equals("Unverified source")) throw new IllegalArgumentException("Clone this entry to rename it.");
                if (!path.endsWith("/character")
                        && (!document.has("forms") || !document.getAsJsonObject("forms").has(CatalogValidation.id(proposal.id())))) {
                    throw new IllegalArgumentException("Select an existing race or form to rename.");
                }
                String key = translation(path, proposal.id());
                names.put(key, label(proposal.name(), proposal.id(), 64));
                if (path.endsWith("/character")) names.put(key + ".desc", description(proposal.description()));
            }
            default -> throw new IllegalArgumentException("Unknown editor operation.");
        }
        var draft = editor.edit(changes, names);
        return new Revision(draft.number(), draft.changes(), draft.names(), disabled, retired, rules);
    }

    private static boolean isBoolean(JsonElement value) {
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean();
    }

    private static void createForm(WorldEditor editor, Proposal proposal, Map<String, JsonObject> docs,
                                   Map<String, JsonObject> changes, Map<String, String> names) {
        String path = proposal.target();
        CatalogValidation.checkPath(path);
        if (!(path.startsWith("forms/") || path.contains("/forms/"))) throw new IllegalArgumentException("Choose a form group.");
        String id = CatalogValidation.id(proposal.id());
        JsonObject group = docs.get(path);
        String racePath = path.startsWith("races/") ? path.substring(0, path.indexOf("/forms/")) : null;
        String groupId = path.substring(path.lastIndexOf('/') + 1);
        if (group == null) {
            if (racePath != null && source(editor, racePath + "/character", required(docs, racePath + "/character")).equals("Unverified source")) {
                throw new IllegalArgumentException("Clone the race before adding a group.");
            }
            group = new JsonObject();
            group.addProperty("configVersion", "2.2.1");
            group.addProperty("groupName", groupId);
            group.addProperty("formType", CatalogValidation.id(proposal.skill()));
            group.add("forms", new JsonObject());
            String key = racePath == null ? "race.dragonminez.stack.group." + groupId
                    : "race.dragonminez." + racePath.substring("races/".length()) + ".group." + groupId;
            names.put(key, groupId);
        } else {
            String source = source(editor, path, group);
            if (source.equals("Unverified source") || source.equals("DMZUltra")) {
                throw new IllegalArgumentException("Create a separate group to extend this source.");
            }
            group = group.deepCopy();
        }
        var forms = group.getAsJsonObject("forms");
        if (forms.has(id)) throw new IllegalArgumentException("Form ID already exists in this group.");
        int level = proposal.level() > 0 ? proposal.level() : forms.entrySet().stream()
                .mapToInt(entry -> entry.getValue().getAsJsonObject().get("unlockOnSkillLevel").getAsInt()).max().orElse(0) + 1;
        if (level > 128) throw new IllegalArgumentException("Form skill level must be at most 128.");
        String skill = group.get("formType").getAsString();
        if (racePath != null) {
            String characterPath = racePath + "/character";
            var character = required(docs, characterPath).deepCopy();
            var costs = character.getAsJsonObject("formSkillsCosts");
            if (costs == null) {
                costs = new JsonObject();
                character.add("formSkillsCosts", costs);
            }
            var row = costs.getAsJsonObject(skill);
            if (row == null) {
                row = new JsonObject();
                row.addProperty("buyFromMaster", false);
                row.add("prices", new JsonArray());
                costs.add(skill, row);
            }
            var prices = row.getAsJsonArray("prices");
            if (prices.size() < level) {
                if (source(editor, characterPath, character).equals("DMZUltra")) {
                    throw new IllegalArgumentException("DMZUltra owns this skill's price slots. Use an available slot.");
                }
                while (prices.size() < level) prices.add(10_000);
                changes.put(characterPath, character);
            }
        }
        JsonObject form = new JsonObject();
        if (proposal.description() != null && !proposal.description().isBlank()) {
            int split = proposal.description().lastIndexOf('/');
            if (split <= 0) throw new IllegalArgumentException("Invalid source form.");
            var oldGroup = required(docs, proposal.description().substring(0, split));
            var oldForm = oldGroup.getAsJsonObject("forms").getAsJsonObject(proposal.description().substring(split + 1));
            if (oldForm == null) throw new IllegalArgumentException("Unknown source form.");
            form = oldForm.deepCopy();
            for (String key : List.of("formRequisite", "unlockOnMastery", "shareMasteryWith", "incompatibleWith",
                    "unlockOnItem", "levelRequisite", "sagaRequisite")) form.remove(key);
            var model = form.get("customModel");
            if (model != null && model.isJsonPrimitive() && !CatalogValidation.installedModel(path, model.getAsString(), docs)) {
                form.addProperty("customModel", "");
            }
        }
        form.addProperty("name", id);
        form.addProperty("unlockOnSkillLevel", level);
        forms.add(id, form);
        changes.put(path, group);
        names.put(translation(path, id), label(proposal.name(), id, 64));
    }

    static String translation(String path, String form) {
        String[] parts = path.split("/");
        if (parts[0].equals("forms")) return "race.dragonminez.stack.form." + parts[1] + "." + form;
        String race = "race.dragonminez." + parts[1];
        return parts.length == 4 ? race + ".form." + parts[3] + "." + form : race;
    }

    static String source(WorldEditor editor, String path, JsonObject document) {
        var existing = editor.revision().changes().get(path);
        return EditorFields.source(path, document, existing != null && existing.baseline() == null);
    }

    private static JsonObject required(Map<String, JsonObject> docs, String path) {
        var value = docs.get(path);
        if (value == null) throw new IllegalArgumentException("Unknown catalog entry " + path);
        return value;
    }

    static String description(String value) {
        if (value == null) return "";
        if (value.length() > 512 || value.chars().anyMatch(c -> c != '\n' && Character.isISOControl(c))) {
            throw new IllegalArgumentException("Use up to 512 characters of plain text for the description.");
        }
        return value.strip();
    }

    static String label(String value, String fallback, int limit) {
        if (value == null || value.isBlank()) return fallback;
        if (value.length() > limit || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Invalid display text.");
        }
        return value.strip();
    }
}
