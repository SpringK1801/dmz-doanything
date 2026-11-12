package dev.dbzdoanything;

import com.dragonminez.common.config.ConfigManager;
import com.dragonminez.common.stats.character.AppearanceValidator;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import dev.dbzdoanything.compat.EditorCompatibility;
import net.minecraftforge.fml.ModList;
import java.util.List;
import java.util.Set;

final class EditorFields {
    private static final Set<String> PRESET_FIELDS = Set.of("defaultBodyType", "defaultHairType",
            "defaultEyesType", "defaultNoseType", "defaultMouthType", "defaultTattooType");
    private static final Set<String> FORM_STATS = Set.of("strMultiplier", "skpMultiplier", "defMultiplier",
            "vitMultiplier", "stmMultiplier", "pwrMultiplier", "eneMultiplier", "speedMultiplier", "attackSpeed",
            "energyDrain", "staminaDrain", "healthDrain", "staminaDrainMultiplier");
    private static final Set<String> FORM_PROGRESS = Set.of("unlockOnSkillLevel", "unlockOnMastery",
            "formRequisite", "formRequisiteType", "levelRequisite", "sagaRequisite", "masteryPerHitDealt",
            "masteryPerHitReceived", "passiveMasteryEveryFiveSeconds", "formStackable", "stackDrainMultiplier");
    private static final Set<String> FORM_APPEARANCE = Set.of("auraColor", "auraType", "auraType3D", "bodyColor1",
            "bodyColor2", "bodyColor3", "hairColor", "eye1Color", "eye2Color", "hasLightnings", "lightningColor",
            "hideTail", "furUsesHairColor", "tintColor", "tintIntensity", "customModel");
    private static final Set<String> CHARACTER_APPEARANCE = Set.of("defaultBodyType", "defaultHairType",
            "defaultEyesType", "defaultNoseType", "defaultMouthType", "defaultTattooType", "defaultBodyColor",
            "defaultBodyColor2", "defaultBodyColor3", "defaultHairColor", "defaultEye1Color",
            "defaultEye2Color", "defaultAuraColor", "auraType", "auraType3D");

    private EditorFields() {}

    static boolean appearancePreset(String field) {
        return PRESET_FIELDS.contains(field);
    }

    static boolean formAppearance(String field) {
        return FORM_APPEARANCE.contains(field);
    }

    static String source(String path, JsonObject effective, boolean editorOwned) {
        if (editorOwned) return "This world";
        String[] parts = path.split("/");
        var adapter = EditorCompatibility.find(path, effective);
        if (adapter != null) return adapter.name();
        if (parts[0].equals("races") && ConfigManager.getDefaultRaces().contains(parts[1])) return "Dragon Mine Z";
        if (path.equals("forms/kaioken") || path.equals("forms/ultimate")) return "Dragon Mine Z";
        if (ModList.get().isLoaded("raceplus") && path.matches("races/raceplus_(angel|feline)/.+")) return "RacePlus";
        return "Unverified source";
    }

    static String category(String path, List<String> fields) {
        if (fields.isEmpty()) return "Appearance";
        String leaf = fields.get(fields.size() - 1);
        if (FORM_PROGRESS.contains(leaf) || fields.get(0).equals("formSkillsCosts")) return "Progression";
        if (path.endsWith("/stats") || leaf.equals("racialSkill") || FORM_STATS.contains(leaf)) return "Stats and Abilities";
        return "Appearance";
    }

    static String lock(String path, JsonObject document, List<String> fields, String source) {
        if (source.equals("Unverified source")) return "Ownership is unverified. Clone this entry to edit it.";
        String top = fields.get(0);
        String leaf = fields.get(fields.size() - 1);
        var adapter = EditorCompatibility.bySource(source);
        if (adapter != null) {
            String field = String.join(".", fields);
            String reason = adapter.lockReason(path, field);
            if (reason != null) return reason;
            if (adapter.supportsField(path, field)) return null;
        }
        if (path.endsWith("/character")) {
            if (fields.size() == 1 && (CHARACTER_APPEARANCE.contains(top) || top.equals("racialSkill"))) {
                if (top.equals("racialSkill") && (source.equals("DMZUltra") || source.equals("RacePlus"))) {
                    return "This ability is part of the source addon's race mechanics.";
                }
                return null;
            }
            if (fields.size() == 4 && top.equals("formSkillsCosts") && fields.get(2).equals("prices")) return null;
            return switch (top) {
                case "configVersion" -> "Dragon Mine Z's config schema version is pinned for this editor.";
                case "hasGender", "useVanillaSkin", "customModel", "isLayered", "headBones", "hasSaiyanTail", "slimBodyTypes" ->
                        "This changes the rig or the meaning of saved appearance IDs. Clone into a compatible template.";
                case "defaultModelScaling" -> "Model dimensions affect player collision and equipment alignment.";
                case "formSkillsCosts" -> "Changing the skill structure can affect purchased levels. Existing price slots are editable.";
                default -> "This appearance structure is managed by the source config.";
            };
        } else if (path.endsWith("/stats")) {
            if (fields.size() >= 3 && top.equals("classes")
                    && (fields.get(2).equals("baseStats") || fields.get(2).equals("statScaling"))) return null;
        } else if (fields.size() == 3 && top.equals("forms")) {
            if (leaf.equals("name")) return "This ID is the saved key for unlocks and mastery. Use Name to edit its display text.";
            if (source.equals("DMZUltra") && (FORM_PROGRESS.contains(leaf) || FORM_APPEARANCE.contains(leaf))) {
                return "DMZUltra controls this form's requirements or appearance at runtime.";
            }
            if (FORM_STATS.contains(leaf) || FORM_PROGRESS.contains(leaf) || FORM_APPEARANCE.contains(leaf)) return null;
        }
        return "This field is managed by native or addon logic, or requires a save migration.";
    }

    static boolean canDisable(String path, JsonObject document, String form, String source) {
        if (source.equals("Unverified source")) return false;
        var adapter = EditorCompatibility.find(path, document);
        if (adapter != null) return adapter.canDisable(path, form);
        return !path.equals("races/frostdemon/forms/evolutionforms")
                && !path.equals("races/human/forms/androidforms")
                && !path.equals("races/saiyan/forms/oozaru")
                && !Set.of("falseform", "godritual").contains(document.get("formType").getAsString());
    }

    static JsonPrimitive value(JsonPrimitive before, String input, String leaf) {
        if (before.isBoolean()) {
            if (!input.equals("true") && !input.equals("false")) throw new IllegalArgumentException("Use true or false.");
            return new JsonPrimitive(Boolean.parseBoolean(input));
        }
        if (before.isNumber()) {
            java.math.BigDecimal number;
            try {
                number = new java.math.BigDecimal(input);
            } catch (NumberFormatException error) {
                throw new IllegalArgumentException("Enter a valid number.");
            }
            if (appearancePreset(leaf)) {
                int id;
                try {
                    id = number.intValueExact();
                } catch (ArithmeticException error) {
                    throw new IllegalArgumentException("Choose a whole appearance preset ID.");
                }
                int validated = leaf.equals("defaultHairType") ? AppearanceValidator.hairId(id, -1)
                        : AppearanceValidator.styleIndex(id, -1);
                if (validated < 0) throw new IllegalArgumentException("Unknown native appearance preset.");
                return new JsonPrimitive(validated);
            }
            if (!Double.isFinite(number.doubleValue()) || number.abs().compareTo(java.math.BigDecimal.valueOf(1_000_000_000)) > 0) {
                throw new IllegalArgumentException("Number out of range.");
            }
            if (leaf.endsWith("Type") || leaf.equals("unlockOnSkillLevel") || leaf.equals("levelRequisite")
                    || leaf.matches("[0-9]+") || Set.of("STR", "SKP", "RES", "VIT", "PWR", "ENE").contains(leaf)) {
                number.intValueExact();
            }
            if (number.signum() < 0 && !(leaf.matches("[0-9]+") && number.intValueExact() == -1)) {
                throw new IllegalArgumentException("This value cannot be negative.");
            }
            if ((leaf.equals("tintIntensity") && number.doubleValue() > 1)
                    || (leaf.equals("unlockOnMastery") && number.doubleValue() > 100)
                    || (leaf.endsWith("Type") && number.doubleValue() > 255)) {
                throw new IllegalArgumentException("This value is outside the native range.");
            }
            return new JsonPrimitive(number);
        }
        if (leaf.endsWith("Color") || leaf.matches(".*Color[123]")) {
            if (!input.isEmpty() && !input.matches("#[0-9a-fA-F]{6}")) throw new IllegalArgumentException("Use a hex color such as #51E8D9.");
        }
        if (leaf.equals("customModel") && !input.isEmpty() && !input.matches("[a-z0-9_]{1,64}")) {
            throw new IllegalArgumentException("Enter a loaded Dragon Mine Z model ID.");
        }
        if (leaf.equals("formRequisiteType") && !input.equalsIgnoreCase("all") && !input.equalsIgnoreCase("any")) {
            throw new IllegalArgumentException("Choose all or any for form prerequisites.");
        }
        if (input.length() > 512 || input.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Text is too long or contains control characters.");
        }
        return new JsonPrimitive(input);
    }

    static JsonElement node(JsonObject root, List<String> fields) {
        JsonElement value = root;
        for (String field : fields) {
            if (value == null || value.isJsonNull()) return null;
            if (value.isJsonObject()) value = value.getAsJsonObject().get(field);
            else if (value.isJsonArray() && field.matches("[0-9]{1,3}")) {
                int index = Integer.parseInt(field);
                var array = value.getAsJsonArray();
                value = index < array.size() ? array.get(index) : null;
            } else return null;
        }
        return value;
    }

    static void set(JsonObject document, List<String> fields, JsonElement value) {
        var parent = node(document, fields.subList(0, fields.size() - 1));
        String key = fields.get(fields.size() - 1);
        if (parent.isJsonObject()) parent.getAsJsonObject().add(key, value);
        else parent.getAsJsonArray().set(Integer.parseInt(key), value);
    }
}
