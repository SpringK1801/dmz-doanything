package dev.dbzdoanything;

import java.util.List;
import java.util.Locale;
import java.util.Map;

final class EditorPresentation {
    private static final Map<String, String> NAMES = Map.ofEntries(
            Map.entry("STR", "Strength"), Map.entry("SKP", "Strike Power"), Map.entry("RES", "Defense"),
            Map.entry("VIT", "Vitality"), Map.entry("PWR", "Ki Power"), Map.entry("ENE", "Energy"),
            Map.entry("strMultiplier", "Strength Multiplier"), Map.entry("skpMultiplier", "Strike Power Multiplier"),
            Map.entry("defMultiplier", "Defense Multiplier"), Map.entry("vitMultiplier", "Vitality Multiplier"),
            Map.entry("stmMultiplier", "Stamina Multiplier"), Map.entry("pwrMultiplier", "Ki Power Multiplier"),
            Map.entry("eneMultiplier", "Energy Multiplier"), Map.entry("defaultHairType", "Starting Hair Preset"),
            Map.entry("defaultEyesType", "Starting Eye Style"), Map.entry("defaultBodyType", "Starting Body Variant"),
            Map.entry("defaultNoseType", "Starting Nose Style"), Map.entry("defaultMouthType", "Starting Mouth Style"),
            Map.entry("defaultTattooType", "Starting Tattoo Preset"), Map.entry("energyDrain", "Ki Upkeep"),
            Map.entry("staminaDrain", "Stamina Upkeep"), Map.entry("healthDrain", "Health Upkeep"),
            Map.entry("unlockOnSkillLevel", "Required Skill Level"), Map.entry("unlockOnMastery", "Required Mastery"),
            Map.entry("formRequisite", "Required Transformations"), Map.entry("formRequisiteType", "Requirement Matching"),
            Map.entry("levelRequisite", "Minimum Character Level"), Map.entry("sagaRequisite", "Required Saga Quest"),
            Map.entry("formStackable", "Allow Stacking"), Map.entry("racialSkill", "Racial Ability"),
            Map.entry("auraType", "2D Aura Style"), Map.entry("auraType3D", "3D Aura Style"),
            Map.entry("customModel", "Model Override"), Map.entry("passiveMasteryEveryFiveSeconds", "Mastery Every Five Seconds"));

    private EditorPresentation() {}

    static String readable(String key) {
        String named = NAMES.get(key);
        if (named != null) return named;
        String text = key.replaceAll("([a-z0-9])([A-Z])", "$1 $2").replace('_', ' ').replace('-', ' ').strip();
        if (text.isEmpty()) return key;
        return java.util.Arrays.stream(text.split(" +"))
                .map(word -> word.equalsIgnoreCase("ki") ? "Ki" : Character.toUpperCase(word.charAt(0)) + word.substring(1))
                .collect(java.util.stream.Collectors.joining(" "));
    }

    static String skillName(String id) {
        return net.minecraft.network.chat.Component.translatableWithFallback("skill.dragonminez." + id, readable(id)).getString();
    }

    static String fieldLabel(List<String> keys) {
        String key = keys.get(keys.size() - 1);
        if (keys.size() >= 4 && keys.get(0).equals("classes")) {
            String stat = key.replace("_scaling", "");
            return readable(keys.get(1)) + " \u00b7 " + readable(stat) + (keys.get(2).equals("baseStats") ? " Starting Value" : " Scaling");
        }
        if (keys.size() == 4 && keys.get(0).equals("formSkillsCosts") && keys.get(2).equals("prices")) {
            return readable(keys.get(1)) + " \u00b7 Level " + (Integer.parseInt(key) + 1) + " TP Cost";
        }
        return key.startsWith("default") && !NAMES.containsKey(key) ? "Starting " + readable(key.substring(7)) : readable(key);
    }

    static String section(String path, List<String> keys, String lock) {
        String key = keys.get(keys.size() - 1);
        String category = EditorFields.category(path, keys);
        if (path.endsWith("/stats")) return keys.size() >= 4 && keys.get(0).equals("classes")
                && List.of("baseStats", "statScaling").contains(keys.get(2)) ? "Class Stats" : "Advanced";
        if (path.endsWith("/character")) {
            if (key.equals("racialSkill") || keys.get(0).equals("formSkillsCosts")) return "Abilities";
            return key.startsWith("default") && keys.size() == 1 && !key.equals("defaultModelScaling")
                    || List.of("auraType", "auraType3D").contains(key) ? "Appearance" : "Advanced";
        }
        if (key.toLowerCase(Locale.ROOT).contains("drain") || key.equals("formStackable")) return "Upkeep & Stacking";
        if (key.startsWith("mastery") || key.equals("passiveMasteryEveryFiveSeconds")) return "Mastery";
        if (category.equals("Progression")) return "Unlock Requirements";
        if (category.equals("Stats and Abilities")) return "Stat Bonuses";
        return keys.size() == 3 && (lock == null || EditorFields.formAppearance(key)) ? "Appearance" : "Advanced";
    }

    static boolean newCharacters(List<String> keys) {
        return keys.get(keys.size() - 1).startsWith("default") || keys.contains("baseStats");
    }

    static String fieldHelp(List<String> keys, boolean number, boolean toggle, String lock) {
        String key = keys.get(keys.size() - 1);
        if (lock != null) return lock;
        if (keys.contains("baseStats")) return "The initial value for this class. Enter a nonnegative whole number. Existing allocations stay intact.";
        if (keys.contains("statScaling")) return "Controls how strongly this class benefits from the stat. Enter a nonnegative multiplier.";
        if (keys.contains("prices")) return "Training cost in TP for this skill level. Enter a whole number or -1 for a slot unavailable for normal purchase.";
        if (EditorFields.appearancePreset(key)) return "Choose a loaded appearance preset for newly created characters. The editor preview shows your staged choice.";
        if (key.endsWith("Multiplier") || key.equals("attackSpeed")) return "Scales this value while the form is active. Enter a nonnegative multiplier. For example, 1.25 is 125% of the underlying value.";
        if (key.endsWith("Color") || key.matches(".*Color[123]")) return "Enter a color in #RRGGBB format, such as #51E8D9. A blank value uses native fallback behavior.";
        return switch (key) {
            case "racialSkill" -> "Choose an installed racial ability. The race uses it as soon as this revision is applied.";
            case "customModel" -> "Choose compatible geometry from the installed mods. Clear it to use the normal model.";
            case "auraType", "auraType3D" -> "Controls which aura style Dragon Mine Z renders. Use an installed aura style identifier.";
            case "energyDrain", "staminaDrain", "healthDrain" -> "Controls how much of this resource the native upkeep system drains while transformed. Enter a nonnegative value. Zero removes this upkeep.";
            case "unlockOnSkillLevel" -> "The minimum level of the transformation group's skill. Use a whole number supported by its installed skill or training price slots.";
            case "unlockOnMastery" -> "Required mastery of the prerequisite transformations. Enter a value from 0 to 100.";
            case "formRequisite" -> "List prerequisite forms as group.form, separated by commas. Leave blank to remove the form requirement.";
            case "formRequisiteType" -> "Use all to require every listed form, or any to require at least one.";
            case "levelRequisite" -> "Minimum character level. Enter a whole number. Zero removes the level requirement.";
            case "sagaRequisite" -> "Require a completed quest using saga_id:quest_number. Use an existing saga quest, or leave blank.";
            case "masteryPerHitDealt", "masteryPerHitReceived" -> "Amount of mastery gained per qualifying hit. Enter a nonnegative value.";
            case "passiveMasteryEveryFiveSeconds" -> "Amount of passive mastery gained every five seconds while using the form. Enter a nonnegative value.";
            case "formStackable" -> "Allows this form to overlap with a compatible transformation. Choose true or false.";
            case "tintIntensity" -> "Strength of the transformation tint. Enter a value from 0 to 1.";
            default -> toggle ? "Controls " + readable(key).toLowerCase(Locale.ROOT) + ". Use true or false."
                    : number ? "Sets " + readable(key).toLowerCase(Locale.ROOT) + ". Enter a nonnegative number."
                    : "Sets " + readable(key).toLowerCase(Locale.ROOT) + " for this definition.";
        };
    }
}
