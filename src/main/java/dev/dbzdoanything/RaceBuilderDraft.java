package dev.dbzdoanything;

import com.dragonminez.common.config.ConfigManager;
import com.dragonminez.common.config.FormConfig;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import dev.dbzdoanything.mixin.ConfigAccess;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class RaceBuilderDraft {
    record Edit(List<String> field, String value) {}
    record Form(String group, String skill, String id, String name, int level, String source, List<Edit> edits) {}
    record Slot(String skill, int level) {}
    record Price(Slot slot, int tp) {}
    record Proposal(long revision, String template, String id, String name, String description, String ability,
                    List<Edit> appearance, List<Edit> stats, List<Form> forms, List<Price> prices) {}
    record Template(JsonObject character, JsonObject stats, boolean fallback) {}
    record Prepared(RevisionStore.Revision revision, List<WorldEditor.ReviewRow> details) {}

    private static final Set<String> SOURCE_REQUIREMENTS = Set.of("formRequisite", "unlockOnMastery",
            "shareMasteryWith", "incompatibleWith", "triggerItemCosts", "durationItemCosts", "levelRequisite",
            "sagaRequisite", "formCombo", "unlockOnItem");
    private static final String CHARACTER = "races/builder/character";
    private static final String STATS = "races/builder/stats";
    private static final String GROUP = "races/builder/forms/draft";

    private RaceBuilderDraft() {}

    static Template template(Map<String, JsonObject> docs, String id) {
        String prefix = "races/" + CatalogValidation.id(id) + "/";
        var human = required(docs, "races/human/character");
        var source = required(docs, prefix + "character");
        var character = human.deepCopy();
        // New IDs use the Human rig and texture family. Only defaults from a matching rig can carry over.
        boolean portable = source.get("useVanillaSkin").getAsBoolean()
                && Set.of("", "human").contains(source.get("customModel").getAsString())
                && source.get("hasGender").equals(human.get("hasGender"))
                && source.get("isLayered").equals(human.get("isLayered"))
                && (id.equals("human") || id.equals("saiyan") || source.get("customModel").getAsString().equals("human"));
        if (portable) {
            source.entrySet().forEach(entry -> {
                if (appearance(List.of(entry.getKey()))) character.add(entry.getKey(), entry.getValue().deepCopy());
            });
        }
        // Set the stock model key explicitly so a newly published ID resolves the same look as the preview.
        character.addProperty("customModel", "human");
        character.addProperty("useVanillaSkin", true);
        character.addProperty("racialSkill", "");
        character.addProperty("hasSaiyanTail", false);
        character.addProperty("configVersion", "2.2.1");
        character.add("formSkillsCosts", new JsonObject());
        return new Template(character, required(docs, prefix + "stats").deepCopy(), !portable);
    }

    static boolean appearance(List<String> keys) {
        return keys.size() == 1 && !keys.get(0).equals("racialSkill")
                && EditorFields.lock(CHARACTER, new JsonObject(), keys, "This world") == null;
    }

    static boolean stats(List<String> keys) {
        return keys.size() == 4 && EditorFields.lock(STATS, new JsonObject(), keys, "This world") == null;
    }

    static boolean formField(List<String> keys) {
        return keys.size() == 1 && !keys.get(0).equals("unlockOnSkillLevel")
                && EditorFields.lock(GROUP, new JsonObject(), List.of("forms", "draft", keys.get(0)), "This world") == null;
    }

    static JsonObject formTemplate(Map<String, JsonObject> docs, String source) {
        var config = ConfigAccess.editorGson();
        var form = config.toJsonTree(new FormConfig.FormData()).getAsJsonObject();
        if (source == null || source.isBlank()) return form;
        int split = source.lastIndexOf('/');
        if (split < 1) throw new IllegalArgumentException("Choose an installed transformation to copy.");
        String path = source.substring(0, split);
        CatalogValidation.checkPath(path);
        var group = required(docs, path).getAsJsonObject("forms");
        String id = CatalogValidation.id(source.substring(split + 1));
        if (group == null || !group.has(id)) throw new IllegalArgumentException("The source transformation is no longer loaded.");
        // Keep supported native settings. Source-specific unlocks and runtime extensions do not carry into a new race.
        group.getAsJsonObject(id).entrySet().forEach(entry -> {
            var keys = List.of(entry.getKey());
            if (!SOURCE_REQUIREMENTS.contains(entry.getKey()) && formField(keys) && entry.getValue().isJsonPrimitive()) {
                form.add(entry.getKey(), entry.getValue().deepCopy());
            }
        });
        return form;
    }

    static JsonPrimitive fieldValue(JsonObject document, Edit edit) {
        var before = EditorFields.node(document, edit.field());
        if (before == null || !before.isJsonPrimitive()) throw new IllegalArgumentException("This setting is unavailable in the template.");
        return EditorFields.value(before.getAsJsonPrimitive(), edit.value(), edit.field().get(edit.field().size() - 1));
    }

    static void applyEdits(JsonObject document, List<Edit> edits) {
        for (var edit : edits) EditorFields.set(document, edit.field(), fieldValue(document, edit));
    }

    static Prepared prepare(WorldEditor editor, Proposal proposal) {
        if (proposal == null || proposal.revision() != editor.revision().number()) {
            throw new IllegalArgumentException("This race draft is stale. Refresh the catalog and review it again.");
        }
        var docs = editor.documents();
        String id = CatalogValidation.id(proposal.id());
        String prefix = "races/" + id + "/";
        if (docs.containsKey(prefix + "character") || editor.revision().retiredRaces().contains(id)) {
            throw new IllegalArgumentException("Race ID already exists.");
        }
        String name = EditorDraft.label(proposal.name(), "", 64);
        if (name.isEmpty()) throw new IllegalArgumentException("Give the race a display name.");
        var template = template(docs, proposal.template());
        var character = template.character();
        var stats = template.stats();
        var changes = new LinkedHashMap<String, JsonObject>();
        var names = new LinkedHashMap<String, String>();
        var detail = new ArrayList<WorldEditor.ReviewRow>();
        String templateName = EditorPresentation.readable(proposal.template());
        detail.add(detail(prefix, "Race", name));
        detail.add(detail(prefix, "Template", templateName));
        detail.add(detail(prefix, "Appearance", template.fallback()
                ? "Human fallback. The template uses race-specific art." : "Compatible Human rig and template appearance defaults."));
        detail.add(detail(prefix, "Class stats", "Inherited from " + templateName));
        edit(character, proposal.appearance(), RaceBuilderDraft::appearance, detail, prefix);
        edit(stats, proposal.stats(), RaceBuilderDraft::stats, detail, prefix);
        String ability = EditorDraft.label(proposal.ability(), "", 64);
        character.addProperty("racialSkill", ability);
        if (!ability.isEmpty()) detail.add(detail(prefix, "Racial ability", EditorPresentation.readable(ability)));
        String description = EditorDraft.description(proposal.description());
        if (!description.isEmpty()) detail.add(detail(prefix, "Description", description));
        validateAppearance(character);
        changes.put(prefix + "character", character);
        changes.put(prefix + "stats", stats);
        names.put("race.dragonminez." + id, name);
        names.put("race.dragonminez." + id + ".desc", description);

        var forms = proposal.forms();
        var prices = proposal.prices();
        if (forms == null || forms.size() > 48 || prices == null || prices.size() > forms.size()) {
            throw new IllegalArgumentException("The builder supports up to 48 staged transformations.");
        }
        var usedSlots = new LinkedHashSet<Slot>();
        var skills = ConfigManager.getSkillsConfig().getFormSkills();
        for (var form : forms) {
            if (form == null || !skills.contains(form.skill()) || form.level() < 1 || form.level() > 128) {
                throw new IllegalArgumentException("Choose an installed race-form skill and a level from 1 through 128.");
            }
            String groupId = CatalogValidation.id(form.group());
            String formId = CatalogValidation.id(form.id());
            String path = prefix + "forms/" + groupId;
            var group = changes.computeIfAbsent(path, ignored -> newGroup(groupId, form.skill()));
            if (!group.get("formType").getAsString().equals(form.skill())) {
                throw new IllegalArgumentException("All transformations in " + groupId + " must use the same skill.");
            }
            var members = group.getAsJsonObject("forms");
            if (members.has(formId)) throw new IllegalArgumentException("Duplicate transformation ID " + groupId + "." + formId);
            var data = formTemplate(docs, form.source());
            String formName = EditorDraft.label(form.name(), "", 64);
            if (formName.isEmpty()) throw new IllegalArgumentException("Give each transformation a display name.");
            detail.add(detail(prefix, "Transformation", formName + " in " + groupName(groupId, form.skill())
                    + " \u00b7 " + EditorPresentation.skillName(form.skill()) + " " + form.level()));
            edit(data, form.edits(), RaceBuilderDraft::formField, detail, prefix);
            validateAura(data, "auraType", "auraType3D", true);
            data.addProperty("name", formId);
            data.addProperty("unlockOnSkillLevel", form.level());
            members.add(formId, data);
            usedSlots.add(new Slot(form.skill(), form.level()));
            names.put(EditorDraft.translation(path, formId), formName);
            names.put("race.dragonminez." + id + ".group." + groupId, groupName(groupId, form.skill()));
        }
        var bySlot = new LinkedHashMap<Slot, Integer>();
        for (var price : prices) {
            if (price == null || !usedSlots.contains(price.slot()) || price.tp() < 0 || price.tp() > 1_000_000_000
                    || bySlot.putIfAbsent(price.slot(), price.tp()) != null) {
                throw new IllegalArgumentException("Enter one valid TP price per skill level used by the draft forms.");
            }
        }
        if (!bySlot.keySet().equals(usedSlots)) throw new IllegalArgumentException("A transformation skill level is missing a TP price.");
        var costs = character.getAsJsonObject("formSkillsCosts");
        for (var price : bySlot.entrySet()) {
            var slot = price.getKey();
            if (!costs.has(slot.skill())) {
                var row = new JsonObject();
                row.addProperty("buyFromMaster", false);
                row.add("prices", new JsonArray());
                costs.add(slot.skill(), row);
            }
            var levels = costs.getAsJsonObject(slot.skill()).getAsJsonArray("prices");
            while (levels.size() < slot.level()) levels.add(10_000);
            levels.set(slot.level() - 1, new JsonPrimitive(price.getValue()));
            detail.add(detail(prefix, EditorPresentation.skillName(slot.skill()) + " level " + slot.level(), price.getValue() + " TP"));
        }
        return new Prepared(editor.edit(changes, names), List.copyOf(detail));
    }

    static void validateAura(JsonObject data, String aura, String aura3d, boolean allowDefault) {
        validateAura(data, aura, allowDefault, BuilderAppearancePresets.TWO_D_AURAS);
        validateAura(data, aura3d, allowDefault, BuilderAppearancePresets.THREE_D_AURAS);
    }

    private static void validateAura(JsonObject data, String key, boolean allowDefault, List<String> choices) {
        if (!data.has(key)) return;
        String style = data.get(key).getAsString();
        if (allowDefault && style.isBlank()) return;
        if (choices.stream().noneMatch(style::equalsIgnoreCase)) {
            throw new IllegalArgumentException("Unknown installed " + EditorPresentation.readable(key) + " " + style + ".");
        }
    }

    private static void validateAppearance(JsonObject character) {
        validateAura(character, "auraType", "auraType3D", true);
        for (var entry : character.entrySet()) {
            String key = entry.getKey();
            if (!appearance(List.of(key)) || !entry.getValue().isJsonPrimitive()) continue;
            fieldValue(character, new Edit(List.of(key), entry.getValue().getAsString()));
            if (EditorFields.appearancePreset(key) && !BuilderAppearancePresets.supports(key, entry.getValue().getAsInt())) {
                throw new IllegalArgumentException("Choose an installed Human preset for " + EditorPresentation.readable(key) + ".");
            }
        }
    }

    private static void edit(JsonObject document, List<Edit> edits, java.util.function.Predicate<List<String>> supported,
                             List<WorldEditor.ReviewRow> detail, String prefix) {
        if (edits == null || edits.size() > 100) throw new IllegalArgumentException("Too many builder settings.");
        var seen = new LinkedHashSet<List<String>>();
        for (var edit : edits) {
            if (edit == null || edit.field() == null || edit.value() == null || edit.field().stream().anyMatch(java.util.Objects::isNull)
                    || !supported.test(edit.field()) || !seen.add(edit.field())) {
                throw new IllegalArgumentException("Unknown or duplicate builder setting.");
            }
            var after = fieldValue(document, edit);
            String before = EditorFields.node(document, edit.field()).getAsString();
            EditorFields.set(document, edit.field(), after);
            detail.add(detail(prefix, EditorPresentation.fieldLabel(edit.field()),
                    display(before) + " \u2192 " + display(after.getAsString())));
        }
    }

    static String groupName(String group, String skill) {
        return group.equals(skill) ? EditorPresentation.skillName(skill) : EditorPresentation.readable(group);
    }

    private static String display(String value) { return value.isBlank() ? "Default" : value; }

    private static JsonObject newGroup(String id, String skill) {
        var group = new JsonObject();
        group.addProperty("configVersion", "2.2.1");
        group.addProperty("groupName", id);
        group.addProperty("formType", skill);
        group.add("forms", new JsonObject());
        return group;
    }

    private static WorldEditor.ReviewRow detail(String prefix, String label, String value) {
        return new WorldEditor.ReviewRow("builder", prefix + "character", List.of(), null, null, label + " \u00b7 " + value);
    }

    private static JsonObject required(Map<String, JsonObject> docs, String path) {
        var value = docs.get(path);
        if (value == null) throw new IllegalArgumentException("The catalog entry " + path + " is no longer loaded.");
        return value;
    }
}
