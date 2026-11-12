package dev.dbzdoanything;

import com.dragonminez.common.config.ConfigManager;
import com.dragonminez.common.config.FormConfig;
import com.dragonminez.common.quest.QuestRegistry;
import com.dragonminez.common.racial.RacialRegistry;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraftforge.fml.ModList;

import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import java.nio.file.Files;

final class CatalogValidation {
    private CatalogValidation() {}

    static String id(String value) {
        if (value == null || !value.matches("[a-z][a-z0-9_]{0,31}")) {
            throw new IllegalArgumentException("IDs must start with a lowercase letter and use up to 32 letters, digits, or underscores.");
        }
        return value;
    }

    static void checkPath(String path) {
        String token = "[a-z][a-z0-9_]{0,31}";
        if (!path.matches("races/" + token + "/(character|stats|forms/" + token + ")")
                && !path.matches("forms/" + token)) {
            throw new IllegalArgumentException("Invalid native document ID " + path);
        }
    }

    static boolean installedModel(String path, String model, Map<String, JsonObject> documents) {
        var character = path.startsWith("races/") ? documents.get("races/" + path.split("/")[1] + "/character") : null;
        return installedModel(model, character == null ? null : character.get("hasGender").getAsBoolean());
    }

    private static boolean installedModel(String model, Boolean gender) {
        if (model == null || model.isBlank() || model.equals("human")) return true;
        if (gender == null || !model.matches("[a-z0-9_]{1,64}")) return false;
        return gender ? hasModel(model + "_male") && hasModel(model + "_female") : hasModel(model);
    }

    private static boolean hasModel(String model) {
        String file = "geo/entity/races/" + model + ".geo.json";
        return ModList.get().getModFiles().stream().anyMatch(mod ->
                Files.isRegularFile(mod.getFile().findResource("assets", "dragonminez", file)));
    }

    static void check(NativeCatalog.State state, RevisionStore.Revision revision) {
        for (String path : revision.changes().keySet()) {
            String[] parts = path.split("/");
            finite(revision.changes().get(path).value(), path, 0);
            if (parts[0].equals("forms")) {
                var group = state.stacks().get(parts[1]);
                group(group, parts[1], state, null, revision.changes().get(path).baseline());
                continue;
            }
            String race = parts[1];
            var character = state.characters().get(race);
            var stats = state.stats().get(race);
            if (character == null || stats == null || stats.getClasses().isEmpty()) {
                throw new IllegalArgumentException("Race " + race + " requires character and class stats.");
            }
            if (parts[2].equals("character")) {
                String ability = character.getRacialSkill();
                var original = revision.changes().get(path).baseline();
                var oldAbility = original == null ? null : original.get("racialSkill");
                if (ability != null && !ability.isBlank() && RacialRegistry.get(ability).isEmpty()
                        && (oldAbility == null || !ability.equals(oldAbility.getAsString()))) {
                    throw new IllegalArgumentException("Unknown installed racial ability " + ability);
                }
                for (String skill : character.getFormSkills()) {
                    Integer[] prices = character.getFormSkillTpCosts(skill);
                    if (prices.length > 128 || Arrays.stream(prices).anyMatch(price -> price == null || price < -1)) {
                        throw new IllegalArgumentException("Invalid prices for " + skill);
                    }
                }
                var baseline = revision.changes().get(path).baseline();
                if (baseline != null) {
                    var previous = dev.dbzdoanything.mixin.ConfigAccess.editorGson()
                            .fromJson(baseline, com.dragonminez.common.config.RaceCharacterConfig.class);
                    for (String skill : previous.getFormSkills()) {
                        if (character.getFormSkillTpCosts(skill).length < previous.getFormSkillTpCosts(skill).length) {
                            throw new IllegalArgumentException("Reducing price slots would discard purchased " + skill + " levels.");
                        }
                    }
                }
            }
            if (parts[2].equals("forms")) {
                group(state.forms().get(race).get(parts[3]), parts[3], state, race, revision.changes().get(path).baseline());
            }
        }
    }

    private static void group(FormConfig group, String key, NativeCatalog.State state, String race, JsonObject original) {
        if (group == null || !key.equals(group.getGroupName()) || group.getForms() == null) {
            throw new IllegalArgumentException("Mismatched form group " + key);
        }
        String skill = group.getFormType();
        var config = ConfigManager.getSkillsConfig();
        boolean recognized = (race == null ? config.getStackSkills() : config.getFormSkills()).contains(skill);
        if (!recognized && original == null) {
            throw new IllegalArgumentException("Unavailable form skill " + skill + " for " + key);
        }
        var prior = original == null ? null : dev.dbzdoanything.mixin.ConfigAccess.editorGson().fromJson(original, FormConfig.class);
        for (var entry : group.getForms().entrySet()) {
            String form = id(entry.getKey());
            var data = entry.getValue();
            int level = data.getUnlockOnSkillLevel();
            if (!form.equals(data.getName()) || level < 0) {
                throw new IllegalArgumentException("Invalid form ID or unlock level in " + key + "." + form);
            }
            var previous = prior == null ? null : prior.getForm(form);
            String model = data.getCustomModel();
            if ((previous == null || !Objects.equals(previous.getCustomModel(), model))
                    && !installedModel(model, race == null ? null : state.characters().get(race).getHasGender())) {
                throw new IllegalArgumentException("No compatible installed geometry for " + key + "." + form + " model " + model);
            }
            String story = data.getSagaRequisite();
            if (!story.isBlank() && (previous == null || !previous.getSagaRequisite().equals(story))) {
                var saga = QuestRegistry.getSaga(data.getSagaRequisiteId());
                if (saga == null || data.getSagaRequisiteQuest() < 0 || saga.getQuestById(data.getSagaRequisiteQuest()) == null) {
                    throw new IllegalArgumentException("Unknown saga quest " + story + " for " + key + "." + form + ". Use saga_id:quest_number.");
                }
            }
            boolean changedLevel = previous == null || previous.getUnlockOnSkillLevel() != level;
            if (changedLevel && race != null && level > state.characters().get(race).getFormSkillTpCosts(skill).length) {
                throw new IllegalArgumentException("Missing " + skill + " price slot " + level);
            }
            if (changedLevel && race == null && (config.getSkillCosts(skill) == null
                    || level > config.getSkillCosts(skill).getCosts().size())) {
                throw new IllegalArgumentException("Stack form level exceeds the installed " + skill + " skill.");
            }
            if (previous != null && previous.getFormRequisite().equals(data.getFormRequisite())) continue;
            var groups = race == null ? state.stacks() : state.forms().get(race);
            for (String requirement : data.getFormRequisite().split(",")) {
                if (requirement.isBlank()) continue;
                String[] link = requirement.strip().split("\\.", -1);
                if (link.length != 2 || !groups.containsKey(link[0]) || groups.get(link[0]).getForm(link[1]) == null
                        || reaches(groups, link[0], link[1], key + "." + form, new java.util.HashSet<>())) {
                    throw new IllegalArgumentException("Invalid prerequisite " + requirement + " for " + key + "." + form);
                }
            }
        }
    }

    private static boolean reaches(Map<String, FormConfig> groups, String group, String form, String target, java.util.Set<String> visited) {
        String id = group + "." + form;
        if (id.equals(target)) return true;
        if (!visited.add(id)) return false;
        var config = groups.get(group);
        var stage = config == null ? null : config.getForm(form);
        if (stage == null) return false;
        for (String reference : stage.getFormRequisite().split(",")) {
            String[] link = reference.strip().split("\\.", 2);
            if (link.length == 2 && reaches(groups, link[0], link[1], target, visited)) return true;
        }
        return false;
    }

    private static void finite(JsonElement value, String path, int depth) {
        if (depth > 20 || value == null) throw new IllegalArgumentException("Invalid structure at " + path);
        if (value.isJsonObject()) {
            value.getAsJsonObject().entrySet().forEach(entry -> finite(entry.getValue(), path + "." + entry.getKey(), depth + 1));
        } else if (value.isJsonArray()) {
            value.getAsJsonArray().forEach(entry -> finite(entry, path, depth + 1));
        } else if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber()
                && !Double.isFinite(value.getAsDouble())) {
            throw new IllegalArgumentException("Nonfinite number at " + path);
        }
    }
}
