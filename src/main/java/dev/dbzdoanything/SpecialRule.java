package dev.dbzdoanything;

import java.util.Arrays;

public enum SpecialRule {
    TP_GAIN("prohibit_tp_gain", "Prohibit TP Gain",
            "Blocks Training Point awards from gameplay. For example, combat and training stop earning TP. "
                    + "Existing TP stays spendable, and Attribute Points and admin adjustments still work."),
    FORM_UNLOCKS("prohibit_form_unlocking", "Prohibit Form Unlocking",
            "Blocks purchases, upgrades, and direct grants of transformation skills or forms through native gameplay. "
                    + "Existing forms are still usable. If you already know the skill, you can still qualify for a later form through "
                    + "mastery, level, or quest requirements. Scripted add-ons can have separate unlock paths."),
    STAT_SPENDING("prohibit_stat_point_spending", "Prohibit Stat Point Spending",
            "Stops the native Stats screen from spending Training Points or Attribute Points. For example, "
                    + "the + button beside Strength cannot spend either balance. Passive growth and admin adjustments still work."),
    KI_CHARGING("prohibit_manual_ki_charging", "Prohibit Manual Ki Charging",
            "Holding the charge key stops restoring Ki. Passive recovery, food, and other recovery sources still work.");

    final String id;
    final String label;
    final String help;

    SpecialRule(String id, String label, String help) {
        this.id = id;
        this.label = label;
        this.help = help;
    }

    static SpecialRule fromId(String id) {
        return Arrays.stream(values()).filter(rule -> rule.id.equals(id)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown Special Settings rule " + id));
    }

    static String state(boolean enabled) {
        return enabled ? "On" : "Off";
    }
}
