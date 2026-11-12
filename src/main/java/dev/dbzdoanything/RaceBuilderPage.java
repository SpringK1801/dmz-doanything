package dev.dbzdoanything;

import com.dragonminez.client.gui.buttons.ColorSlider;
import com.dragonminez.common.config.ConfigManager;
import com.dragonminez.common.config.FormConfig;
import com.dragonminez.common.config.RaceCharacterConfig;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import dev.dbzdoanything.mixin.ConfigAccess;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;

import java.awt.Color;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Predicate;

import static dev.dbzdoanything.RaceEditorScene.ACCENT;
import static dev.dbzdoanything.RaceEditorScene.MUTED;
import static dev.dbzdoanything.RaceEditorScene.TEXT;
import static dev.dbzdoanything.RaceEditorScene.panel;
import static dev.dbzdoanything.RaceEditorScene.rgb;

final class RaceBuilderPage {
    private enum Section { OVERVIEW, APPEARANCE, STATS, ABILITY, FORMS, FORM, PRICES, ADVANCED }
    private record Row(String label, Runnable open) {}
    private record Setting(String label, String value, int limit, String help, Consumer<String> save,
                           Runnable reset, List<Row> choices, boolean color) {}
    private static final int ROW_HEIGHT = 20;
    private final EditorScreen screen;
    private final RaceEditorScene scene;
    private final Map<List<String>, String> appearance = new LinkedHashMap<>();
    private final Map<List<String>, String> classStats = new LinkedHashMap<>();
    private final List<Form> forms = new ArrayList<>();
    private final Map<RaceBuilderDraft.Slot, String> prices = new LinkedHashMap<>();
    private Map<String, JsonObject> docs;
    private EditorLibrary library;
    private List<String> modelOptions;
    private JsonObject catalog;
    private String templateId;
    private String name = "";
    private String id = "";
    private boolean customId;
    private String description = "";
    private String ability = "";
    private Section section = Section.OVERVIEW;
    private String classId = "";
    private String formSection = "";
    private Form currentForm;
    private Form previewForm;
    private String pickerTitle;
    private List<Row> pickerRows;
    private String query = "";
    private Setting setting;
    private EditBox field;
    private net.minecraft.client.gui.components.MultiLineEditBox descriptionBox;
    private String pending;
    private String confirmation;
    private Runnable confirmAction;
    private String error = "";
    private boolean invalidPreview;
    private int width, height, panelWidth, right, offset, total, visible;
    private float[] hsv;
    private ColorSlider saturation;
    private ColorSlider value;

    private static final class Form {
        final String source;
        final Map<List<String>, String> edits = new LinkedHashMap<>();
        String group;
        String skill;
        String level;
        String name;
        String id;
        boolean customId;

        Form(String source, String group, String skill, String level, String name, String id) {
            this.source = source;
            this.group = group;
            this.skill = skill;
            this.level = level;
            this.name = name;
            this.id = id;
        }
    }

    RaceBuilderPage(EditorScreen screen, JsonObject catalog, EditorLibrary library, EditorLibrary.Entry source) {
        this.screen = screen;
        scene = new RaceEditorScene(screen);
        templateId = source == null ? "human" : source.id();
        if (source != null) {
            name = source.label().substring(0, Math.min(59, source.label().length())) + " Copy";
            id = EditorScreen.suggestedId(name);
            description = Component.translatableWithFallback(EditorDraft.translation(source.path(), source.id()) + ".desc", "").getString();
            description = description.substring(0, Math.min(512, description.length()));
        }
        refresh(catalog, library);
    }

    void refresh(JsonObject catalog, EditorLibrary library) {
        this.catalog = catalog;
        this.library = library;
        modelOptions = screen.modelOptions();
        docs = new LinkedHashMap<>();
        catalog.getAsJsonObject("documents").entrySet().forEach(entry -> docs.put(entry.getKey(), entry.getValue().getAsJsonObject()));
        if (!docs.containsKey("races/" + templateId + "/character")) {
            section = Section.OVERVIEW;
            clearTransient();
            error = "Template unavailable. Choose an installed race.";
        } else {
            reloadPreview();
        }
    }

    void background(float delta) { scene.background(delta); }

    void init(int width, int height) {
        this.width = width;
        this.height = height;
        panelWidth = RaceEditorScene.panelWidth(width);
        right = width - panelWidth - 8;
        int inner = panelWidth - 16;
        int x = right + 8;
        int end = height - 61;
        total = visible = 0;
        field = null;
        descriptionBox = null;
        if (confirmation != null) {
            screen.button(width / 2 - 102, height / 2 + 31, 96, "Cancel", this::cancelTransient);
            screen.button(width / 2 + 6, height / 2 + 31, 96, "Confirm", confirmAction);
            return;
        }
        screen.button(8, height - 27, 84, "Back", this::back);
        screen.button(width - 129, height - 27, 121, "Review Changes", this::review)
                .active = setting == null && pickerRows == null;
        screen.helpButton(16 + inner - 18, 45, "About", "Name your race and write the description shown in character creation. The template supplies compatible starting appearance and class stats.");
        screen.helpButton(x + inner - 18, 45, heading(), sectionHelp());
        screen.button(16, 67, inner, name.isBlank() ? "+ Name" : "Edit Name", () ->
                edit("Race Name", name, 64, "The name shown in the native race chooser.", this::rename, null));
        screen.button(16, 89, inner, description.isBlank() ? "+ Description" : "Edit Description", () ->
                edit("Description", description, 512, "The description appears on the About card and in the native race chooser.",
                        next -> description = next.strip(), description.isEmpty() ? null : () -> description = ""));
        var template = screen.button(16, end - 39, inner, "Template \u00b7 " + templateName(), this::pickTemplate);
        template.setTooltip(Tooltip.create(Component.literal(templateName() + " \u00b7 " + templateExplanation())));
        screen.button(16, end - 18, inner, "Advanced", () -> go(Section.ADVANCED));
        if (!forms.isEmpty()) {
            int center = width / 2;
            screen.button(center - 53, height - 54, 18, "<", () -> cyclePreview(-1));
            screen.button(center - 33, height - 54, 66, previewForm == null ? "Base" : formName(previewForm), this::pickPreview);
            screen.button(center + 35, height - 54, 18, ">", () -> cyclePreview(1));
        }
        if (setting != null) {
            buildSetting(x, inner);
        } else if (pickerRows != null) {
            buildPicker(x, inner);
        } else {
            buildRows(rows(), x, section == Section.OVERVIEW ? 68 : 90, inner, end);
            if (section != Section.OVERVIEW) screen.button(x, 67, inner, section == Section.FORM && !formSection.isEmpty()
                    ? "\u2039 Transformation" : "\u2039 Builder", this::back);
        }
    }

    private List<Row> rows() {
        return switch (section) {
            case OVERVIEW -> List.of(
                    new Row("Appearance", () -> go(Section.APPEARANCE)),
                    new Row("Class Stats", () -> go(Section.STATS)),
                    new Row(ability.isEmpty() ? "+ Racial Ability" : "Edit Racial Ability", () -> go(Section.ABILITY)),
                    new Row((forms.isEmpty() ? "+ " : "") + "Transformations", () -> go(Section.FORMS)));
            case APPEARANCE -> settingRows(template().character(), appearance, RaceBuilderDraft::appearance);
            case STATS -> classRows();
            case ABILITY -> List.of(new Row(ability.isEmpty() ? "+ Choose Ability" : EditorPresentation.readable(ability), this::pickAbility),
                    new Row(ability.isEmpty() ? "No Racial Ability" : "Remove Ability", () -> { ability = ""; go(Section.OVERVIEW); }));
            case FORMS -> transformationRows();
            case FORM -> formRows(currentForm);
            case PRICES -> priceRows();
            case ADVANCED -> List.of(new Row("Stable ID \u00b7 " + (id.isBlank() ? "Suggested from name" : id),
                    () -> edit("Stable Race ID", id, 32, "This is the permanent save key. Use lowercase letters, digits, or underscores.",
                            next -> { id = next; customId = !next.isBlank(); if (!customId) id = EditorScreen.suggestedId(name); },
                            customId ? () -> { customId = false; id = EditorScreen.suggestedId(name); } : null)));
        };
    }

    private List<Row> classRows() {
        var stats = template().stats();
        if (classId.isBlank()) {
            return stats.getAsJsonObject("classes").keySet().stream().sorted().map(id ->
                    new Row(EditorPresentation.readable(id), () -> { classId = id; offset = 0; screen.rebuild(); })).toList();
        }
        return settingRows(stats, classStats, keys -> RaceBuilderDraft.stats(keys) && keys.get(1).equals(classId));
    }

    private List<Row> transformationRows() {
        var rows = new ArrayList<Row>();
        rows.add(new Row("+ Transformation", () -> pick("Add Transformation", List.of(
                new Row("Create Transformation", () -> addForm(null)),
                new Row("Copy from Library", this::pickSourceForm)))));
        if (!forms.isEmpty()) rows.add(new Row("Skill Level Prices", () -> go(Section.PRICES)));
        for (var form : forms) {
            String label = formName(form) + (compatible(form) ? "" : " \u00b7 Check Model");
            rows.add(new Row(label, () -> { currentForm = previewForm = form; formSection = ""; go(Section.FORM); }));
        }
        return rows;
    }

    private List<Row> formRows(Form form) {
        if (!formSection.isEmpty()) {
            return settingRows(formTemplate(form), form.edits, keys -> RaceBuilderDraft.formField(keys)
                    && formSection.equals(EditorPresentation.section("races/builder/forms/draft",
                    List.of("forms", "draft", keys.get(0)), null)));
        }
        return List.of(
                new Row("Name \u00b7 " + formName(form), () -> edit("Transformation Name", form.name, 64, "The name in the native transformation menu.",
                        next -> { form.name = next; if (!form.customId) form.id = EditorScreen.suggestedId(next); }, null)),
                new Row("Group \u00b7 " + RaceBuilderDraft.groupName(form.group, form.skill), () -> pickGroup(form)),
                new Row("Unlock Skill \u00b7 " + EditorPresentation.skillName(form.skill), () -> pickSkill(form)),
                new Row("Skill Level \u00b7 " + form.level, () -> edit("Required Skill Level", form.level, 3,
                        "Choose a level from 1 to 128. Forms with the same skill and level share the same price.", next -> form.level = next, null)),
                new Row("Appearance" + (compatible(form) ? "" : " \u00b7 Check Model"), () -> openFormSection("Appearance")),
                new Row("Stat Bonuses", () -> openFormSection("Stat Bonuses")),
                new Row("Unlock Requirements", () -> openFormSection("Unlock Requirements")),
                new Row("Mastery", () -> openFormSection("Mastery")),
                new Row("Upkeep & Stacking", () -> openFormSection("Upkeep & Stacking")),
                new Row("Stable ID \u00b7 " + form.id, () -> edit("Stable Transformation ID", form.id, 32,
                        "This ID identifies the transformation within its group.", next -> {
                            form.id = next;
                            form.customId = true;
                        }, () -> { form.customId = false; form.id = EditorScreen.suggestedId(form.name); })),
                new Row("Remove Transformation", () -> confirm("Remove " + formName(form) + " from this draft?", () -> {
                    forms.remove(form);
                    if (previewForm == form) previewForm = null;
                    currentForm = null;
                    go(Section.FORMS);
                })));
    }

    private void openFormSection(String category) {
        formSection = category;
        offset = 0;
        screen.rebuild();
    }

    private List<Row> priceRows() {
        var rows = new ArrayList<Row>();
        forms.stream().map(this::slot).filter(Objects::nonNull).distinct().forEach(slot -> {
            String label = EditorPresentation.skillName(slot.skill()) + " Lv. " + slot.level();
            String price = prices.getOrDefault(slot, "10000");
            rows.add(new Row(label + " \u00b7 " + price + " TP", () -> edit(label + " TP Price", price, 10,
                    "The native skill purchase costs this many Training Points. Every form using this skill level shares the price.",
                    next -> prices.put(slot, next), () -> prices.remove(slot))));
        });
        return rows;
    }

    private List<Row> settingRows(JsonObject document, Map<List<String>, String> edits, Predicate<List<String>> allowed) {
        var rows = new ArrayList<Row>();
        collectSettings(document, List.of(), edits, allowed, rows);
        return rows;
    }

    private void collectSettings(JsonObject document, List<String> parents, Map<List<String>, String> edits,
                                 Predicate<List<String>> allowed, List<Row> rows) {
        for (var entry : document.entrySet()) {
            var keys = new ArrayList<>(parents);
            keys.add(entry.getKey());
            var path = List.copyOf(keys);
            if (entry.getValue().isJsonObject() && section == Section.STATS && parents.size() < 3) {
                collectSettings(entry.getValue().getAsJsonObject(), path, edits, allowed, rows);
            } else if (entry.getValue().isJsonPrimitive() && allowed.test(path)) {
                var original = entry.getValue().getAsJsonPrimitive();
                String current = edits.getOrDefault(path, original.getAsString());
                String label = EditorPresentation.fieldLabel(path);
                rows.add(new Row((edits.containsKey(path) ? "* " : "") + label + " \u00b7 " + display(current),
                        () -> editSetting(path, original, edits)));
            }
        }
    }

    private void editSetting(List<String> path, JsonPrimitive original, Map<List<String>, String> edits) {
        String key = path.get(path.size() - 1);
        String label = EditorPresentation.fieldLabel(path);
        String help = EditorPresentation.fieldHelp(path, original.isNumber(), original.isBoolean(), null);
        String current = edits.getOrDefault(path, original.getAsString());
        Consumer<String> save = next -> {
            var checked = EditorFields.value(original, next, key);
            if (checked.equals(original)) edits.remove(path);
            else edits.put(path, checked.getAsString());
        };
        List<Row> choices = null;
        if (EditorFields.appearancePreset(key)) {
            choices = EditorScreen.appearanceValues("human", ConfigManager.getRaceCharacter("human"), key)
                    .stream().filter(candidate -> BuilderAppearancePresets.supports(key, Integer.parseInt(candidate)))
                    .map(candidate -> new Row(EditorScreen.choiceLabel(key, candidate), () -> chooseSetting(candidate))).toList();
        } else if (key.equals("auraType") || key.equals("auraType3D")) {
            choices = new ArrayList<>();
            if (section == Section.FORM) choices.add(new Row("Use Base Aura", () -> chooseSetting("")));
            for (String option : key.equals("auraType") ? BuilderAppearancePresets.TWO_D_AURAS : BuilderAppearancePresets.THREE_D_AURAS) {
                choices.add(new Row(EditorPresentation.readable(option), () -> chooseSetting(option)));
            }
        } else if (original.isBoolean()) {
            choices = List.of(new Row("On", () -> chooseSetting("true")), new Row("Off", () -> chooseSetting("false")));
        } else if (key.equals("customModel")) {
            choices = new ArrayList<>();
            choices.add(new Row("Use Base Model", () -> chooseSetting("")));
            for (String model : modelOptions) {
                if (modelCompatible(model)) choices.add(new Row(model, () -> chooseSetting(model)));
            }
        } else if (key.equals("formRequisiteType")) {
            choices = List.of(new Row("Require All", () -> chooseSetting("all")), new Row("Require Any", () -> chooseSetting("any")));
        }
        startSetting(new Setting(label, current, 512, help, save, () -> edits.remove(path), choices,
                key.endsWith("Color") || key.matches(".*Color[123]")));
    }

    private void edit(String label, String value, int limit, String help, Consumer<String> save, Runnable reset) {
        startSetting(new Setting(label, value, limit, help, save, reset, null, false));
    }

    private void startSetting(Setting next) {
        setting = next;
        pending = next.value();
        if (next.color()) {
            int rgb = rgb(pending, 0xFFFFFF);
            hsv = Color.RGBtoHSB(rgb >> 16 & 255, rgb >> 8 & 255, rgb & 255, null);
        }
        clearPicker();
        offset = 0;
        screen.rebuild();
    }

    private void buildSetting(int x, int inner) {
        screen.button(x, 67, inner - 20, "Cancel", this::cancelTransient);
        screen.helpButton(x + inner - 18, 67, setting.label(), setting.help());
        if (setting.choices() != null) {
            buildRows(setting.choices(), x, 104, inner, height - (setting.reset() == null ? 63 : 84));
        } else {
            if (setting.label().equals("Description")) {
                descriptionBox = screen.descriptionBox(x, 108, inner, Math.max(28, height - 203), pending,
                        next -> pending = next);
            } else {
                field = screen.box(x, 108, inner, setting.label(), pending, setting.limit());
                field.setResponder(next -> pending = next);
                screen.focusBuilder(field);
                if (setting.color()) buildColorSliders(x, inner);
            }
            screen.button(x, height - 82, inner, "Save", () -> chooseSetting(pending));
        }
        if (setting.reset() != null) {
            screen.button(x, height - 61, inner, setting.label().equals("Description") ? "Remove Description" : "Reset to Template",
                    () -> { setting.reset().run(); changed(); });
        }
    }

    private void buildColorSliders(int x, int inner) {
        if (height < 287) return;
        slider(x, 132, inner, 0, 360, "Hue", ColorSlider.Gradient.HUE);
        saturation = slider(x, 152, inner, 1, 100, "Saturation", ColorSlider.Gradient.SATURATION);
        value = slider(x, 172, inner, 2, 100, "Brightness", ColorSlider.Gradient.VALUE);
        updateColorSliders();
    }

    private ColorSlider slider(int x, int y, int width, int channel, int maximum, String title, ColorSlider.Gradient gradient) {
        var slider = new ColorSlider.Builder().position(x, y).size(width, 18).range(0, maximum)
                .value(Math.round(hsv[channel] * maximum)).message(Component.literal(title)).gradient(gradient)
                .onValueChange(next -> {
                    hsv[channel] = (float) next / maximum;
                    field.setValue(String.format(Locale.ROOT, "#%06X", Color.HSBtoRGB(hsv[0], hsv[1], hsv[2]) & 0xFFFFFF));
                    updateColorSliders();
                }).build();
        screen.addBuilderSlider(slider);
        return slider;
    }

    private void updateColorSliders() {
        saturation.setCurrentHue(hsv[0]);
        value.setCurrentHue(hsv[0]);
        value.setCurrentSaturation(hsv[1]);
    }

    private void chooseSetting(String next) {
        try {
            setting.save().accept(next);
            changed();
        } catch (IllegalArgumentException | ArithmeticException invalid) {
            error = invalid.getMessage();
            screen.rebuild();
        }
    }

    private void pickTemplate() {
        pick("Choose Template", library.races().stream().map(entry ->
                new Row(entry.label() + " \u00b7 " + EditorLibrary.sourceName(entry.source()), () -> switchTemplate(entry.id()))).toList());
    }

    private void switchTemplate(String next) {
        if (next.equals(templateId)) { cancelTransient(); return; }
        Runnable apply = () -> {
            templateId = next;
            appearance.clear();
            classStats.clear();
            section = Section.OVERVIEW;
            changed();
        };
        if (appearance.isEmpty() && classStats.isEmpty()) apply.run();
        else confirm("Switch templates and reset appearance and class stats? Your identity, ability, and transformations will stay in the draft.", apply);
    }

    private void pickAbility() {
        var rows = new ArrayList<Row>();
        rows.add(new Row("No Racial Ability", () -> { ability = ""; changed(); }));
        for (var candidate : catalog.getAsJsonArray("abilities")) {
            String selected = candidate.getAsString();
            rows.add(new Row(EditorPresentation.readable(selected), () -> { ability = selected; changed(); }));
        }
        pick("Installed Abilities", rows);
    }

    private void pickSourceForm() {
        var rows = library.search("").stream().filter(entry -> !entry.race() && !entry.disabled())
                .map(entry -> new Row(entry.label() + " \u00b7 " + library.location(entry), () -> addForm(entry))).toList();
        pick("Copy Transformation", rows);
    }

    private void addForm(EditorLibrary.Entry source) {
        if (forms.size() == 48) {
            error = "This draft already contains 48 transformations.";
            return;
        }
        String group = source == null || source.shared() ? "transformations" : source.path().split("/")[3];
        String skill = source == null || source.shared() ? screen.defaultSkill(templateId) : source.document().get("formType").getAsString();
        if (!screen.skills(templateId).contains(skill)) skill = screen.defaultSkill(templateId);
        for (var existing : forms) if (existing.group.equals(group)) { skill = existing.skill; break; }
        String display = source == null ? "Transformation " + (forms.size() + 1) : source.label();
        display = display.substring(0, Math.min(64, display.length()));
        String stable = EditorScreen.suggestedId(display);
        if (stable.isEmpty()) stable = "transformation";
        String unique = stable;
        int suffix = 2;
        while (hasFormId(group, unique)) {
            String suffixText = "_" + suffix++;
            unique = stable.substring(0, Math.min(stable.length(), 32 - suffixText.length())) + suffixText;
        }
        int nextLevel = Math.min(128, forms.stream().filter(form -> form.group.equals(group))
                .map(this::slot).filter(Objects::nonNull).mapToInt(RaceBuilderDraft.Slot::level).max().orElse(0) + 1);
        var form = new Form(source == null ? "" : source.key(), group, skill, Integer.toString(nextLevel), display, unique);
        forms.add(form);
        currentForm = previewForm = form;
        formSection = "";
        go(Section.FORM);
    }

    private boolean hasFormId(String group, String id) {
        return forms.stream().anyMatch(form -> form.group.equals(group) && form.id.equals(id));
    }

    private void pickGroup(Form form) {
        var rows = new ArrayList<Row>();
        rows.add(new Row("+ New Group", () -> edit("New Transformation Group", "", 32, "Forms in the same group share their unlock skill.",
                next -> setGroup(form, CatalogValidation.id(EditorScreen.suggestedId(next))), null)));
        forms.stream().map(item -> item.group).distinct().sorted().forEach(group ->
                rows.add(new Row(EditorPresentation.readable(group), () -> { setGroup(form, group); changed(); })));
        pick("Transformation Group", rows);
    }

    private void setGroup(Form form, String group) {
        form.group = group;
        for (var other : forms) if (other != form && other.group.equals(group)) { form.skill = other.skill; break; }
    }

    private void pickSkill(Form form) {
        pick("Transformation Skill", screen.skills(templateId).stream().sorted()
                .map(skill -> new Row(EditorPresentation.skillName(skill), () -> {
                    for (var other : forms) if (other.group.equals(form.group)) other.skill = skill;
                    changed();
                })).toList());
    }

    private void pickPreview() {
        var rows = new ArrayList<Row>();
        rows.add(new Row("Base", () -> { previewForm = null; changed(); }));
        forms.forEach(form -> rows.add(new Row(formName(form), () -> { previewForm = form; changed(); })));
        pick("Preview", rows);
    }

    private void cyclePreview(int step) {
        int next = Math.floorMod(forms.indexOf(previewForm) + 1 + step, forms.size() + 1);
        previewForm = next == 0 ? null : forms.get(next - 1);
        reloadPreview();
        screen.rebuild();
    }

    private void pick(String title, List<Row> rows) {
        pickerTitle = title;
        pickerRows = rows;
        query = "";
        setting = null;
        offset = 0;
        screen.rebuild();
    }

    private void buildPicker(int x, int inner) {
        screen.button(x, 67, inner, "Cancel", this::cancelTransient);
        var search = screen.box(x, 91, inner, "Search " + pickerTitle, query, 64);
        search.setResponder(next -> { query = next; offset = 0; screen.rebuild(); });
        screen.focusBuilder(search);
        var words = query.toLowerCase(Locale.ROOT).strip().split("\\s+");
        var matches = pickerRows.stream().filter(row -> {
            String label = row.label().toLowerCase(Locale.ROOT);
            return java.util.Arrays.stream(words).allMatch(label::contains);
        }).toList();
        buildRows(matches, x, 113, inner, height - 61);
    }

    private void buildRows(List<Row> rows, int x, int top, int inner, int bottom) {
        total = rows.size();
        visible = Math.max(1, (bottom - top) / ROW_HEIGHT);
        boolean scroll = total > visible;
        if (scroll) visible = Math.max(1, visible - 1);
        offset = Math.min(offset, Math.max(0, total - visible));
        for (int index = 0; index < visible && index + offset < total; index++) {
            var row = rows.get(index + offset);
            var widget = screen.button(x, top + index * ROW_HEIGHT, inner, row.label(), row.open());
            widget.setTooltip(Tooltip.create(Component.literal(row.label())));
        }
        if (scroll) {
            int y = top + visible * ROW_HEIGHT;
            screen.button(x, y, inner / 2 - 2, "\u2039 Previous", () -> scroll(-visible));
            screen.button(x + inner / 2 + 2, y, inner / 2 - 2, "Next \u203a", () -> scroll(visible));
        }
    }

    boolean scroll(double x, double y, double scroll) {
        if (descriptionBox != null && descriptionBox.isMouseOver(x, y)) {
            return descriptionBox.mouseScrolled(x, y, scroll);
        }
        return scroll(scroll > 0 ? -3 : 3);
    }

    private boolean scroll(int amount) {
        if (total <= visible || confirmation != null) return false;
        offset = Math.min(total - visible, Math.max(0, offset + amount));
        screen.rebuild();
        return true;
    }

    private void rename(String next) {
        name = next.strip();
        if (!customId) id = EditorScreen.suggestedId(name);
    }

    private void go(Section next) {
        section = next;
        classId = "";
        offset = 0;
        changed();
    }

    private void changed() {
        clearTransient();
        reloadPreview();
        screen.rebuild();
    }

    private void clearPicker() {
        pickerTitle = null;
        pickerRows = null;
        query = "";
    }

    private void clearTransient() {
        clearPicker();
        setting = null;
        confirmation = null;
        confirmAction = null;
        error = "";
    }

    private void cancelTransient() {
        clearTransient();
        screen.rebuild();
    }

    private void confirm(String message, Runnable action) {
        confirmation = message;
        confirmAction = action;
        screen.rebuild();
    }

    void back() {
        if (confirmation != null || setting != null || pickerRows != null) cancelTransient();
        else if (section == Section.STATS && !classId.isBlank()) { classId = ""; offset = 0; screen.rebuild(); }
        else if (section == Section.FORM && !formSection.isEmpty()) openFormSection("");
        else if (section == Section.FORM || section == Section.PRICES) go(Section.FORMS);
        else if (section != Section.OVERVIEW) go(Section.OVERVIEW);
        else confirm("Discard this race draft and all of its unpublished transformations?", screen::closeBuilder);
    }

    boolean enter() {
        if (field == null || !field.isFocused()) return false;
        chooseSetting(pending);
        return true;
    }

    private void review() {
        try {
            if (name.isBlank()) throw new IllegalArgumentException("Add a race name before reviewing.");
            CatalogValidation.id(id);
            var proposals = new ArrayList<RaceBuilderDraft.Form>();
            var slots = new LinkedHashMap<RaceBuilderDraft.Slot, RaceBuilderDraft.Price>();
            for (var form : forms) {
                var slot = slot(form);
                if (slot == null) throw new IllegalArgumentException("Set a valid skill level for " + formName(form) + ".");
                int price;
                try {
                    price = Integer.parseInt(prices.getOrDefault(slot, "10000"));
                } catch (NumberFormatException invalid) {
                    throw new IllegalArgumentException("Enter a whole number for each skill level's TP price.");
                }
                slots.putIfAbsent(slot, new RaceBuilderDraft.Price(slot, price));
                proposals.add(new RaceBuilderDraft.Form(form.group, form.skill, form.id, form.name, slot.level(), form.source, edits(form.edits)));
            }
            screen.reviewBuilder(new RaceBuilderDraft.Proposal(screen.builderRevision(), templateId, id, name, description, ability,
                    edits(appearance), edits(classStats), proposals, List.copyOf(slots.values())));
        } catch (IllegalArgumentException invalid) {
            error = invalid.getMessage();
            screen.rebuild();
        }
    }

    private RaceBuilderDraft.Slot slot(Form form) {
        try {
            int level = Integer.parseInt(form.level);
            return level >= 1 && level <= 128 ? new RaceBuilderDraft.Slot(form.skill, level) : null;
        } catch (NumberFormatException invalid) {
            return null;
        }
    }

    private static List<RaceBuilderDraft.Edit> edits(Map<List<String>, String> values) {
        return values.entrySet().stream().map(entry -> new RaceBuilderDraft.Edit(entry.getKey(), entry.getValue())).toList();
    }

    private RaceBuilderDraft.Template template() { return RaceBuilderDraft.template(docs, templateId); }

    private String templateName() {
        var entry = library.race(templateId);
        return entry == null ? EditorPresentation.readable(templateId) : entry.label();
    }

    private JsonObject formTemplate(Form form) {
        if (!form.source.isBlank() && library.find(form.source) == null) return RaceBuilderDraft.formTemplate(docs, "");
        return RaceBuilderDraft.formTemplate(docs, form.source);
    }

    private boolean compatible(Form form) {
        try {
            if (!form.source.isBlank() && library.find(form.source) == null) return false;
            var base = formTemplate(form);
            String model = form.edits.getOrDefault(List.of("customModel"), base.get("customModel").getAsString());
            return modelCompatible(model);
        } catch (IllegalArgumentException missingSource) {
            return false;
        }
    }

    private boolean modelCompatible(String model) {
        return CatalogValidation.installedModel("races/human/forms/draft", model, docs)
                && (model.isBlank() || model.equals("human") || modelOptions.contains(model));
    }

    private void reloadPreview() {
        var minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.player == null) return;
        try {
            updatePreview();
        } catch (IllegalArgumentException | ArithmeticException invalid) {
            error = invalid.getMessage();
        }
    }

    private void updatePreview() {
        var look = template().character();
        RaceBuilderDraft.applyEdits(look, edits(appearance));
        var config = ConfigAccess.editorGson().fromJson(look, RaceCharacterConfig.class);
        invalidPreview = previewForm != null && !compatible(previewForm);
        FormConfig.FormData data = null;
        if (previewForm != null && !invalidPreview) {
            var form = formTemplate(previewForm);
            RaceBuilderDraft.applyEdits(form, edits(previewForm.edits));
            data = ConfigAccess.editorGson().fromJson(form, FormConfig.FormData.class);
            data.setName(previewForm.id);
        }
        scene.show("human", config, previewForm == null ? "" : previewForm.group,
                previewForm == null || previewForm.id.isBlank() ? "draft" : previewForm.id, data);
    }

    void render(GuiGraphics graphics) {
        int center = width / 2;
        scene.frame(graphics, width, height, "Race Builder", heading());
        drawDescription(graphics, height - 104);
        scene.fighter(graphics, width, height);
        if (invalidPreview) scene.centered(graphics, "Incompatible form model \u00b7 showing Base", center, 34, width - 20, 0xFFFFB27F);
        if (!error.isEmpty()) scene.centered(graphics, error, center, height - 41, width - 20, 0xFFFFB27F);
        if (pickerRows != null && total == 0) {
            screen.text(graphics, "No matching entries.", right + 8, 119, panelWidth - 16, MUTED);
        }
        if (setting != null && setting.choices() == null) {
            screen.text(graphics, setting.label(), right + 8, 96, panelWidth - 16, TEXT);
            if (setting.color()) {
                graphics.fill(right + 8, height - 103, right + panelWidth - 8, height - 87, 0xFF000000 | rgb(pending, 0xFFFFFF));
            }
        }
        if (confirmation != null) {
            graphics.fill(0, 0, width, height, 0xB0000000);
            int dialog = Math.min(340, width - 28);
            panel(graphics, center - dialog / 2, height / 2 - 41, dialog, 100);
            scene.wrapped(graphics, confirmation, center - dialog / 2 + 12, height / 2 - 24, dialog - 24, 5, TEXT);
        }
    }

    private void drawDescription(GuiGraphics graphics, int limit) {
        int inner = panelWidth - 16;
        screen.text(graphics, name.isBlank() ? "Untitled Race" : name, 16, 113, inner, ACCENT);
        String look = templateExplanation();
        int rows = Math.max(0, (limit - 128) / 11);
        if (description.isBlank()) {
            scene.wrapped(graphics, look, 16, 128, inner, rows, MUTED);
        } else {
            var lines = screen.builderFont().split(Component.literal(description), inner);
            int used = scene.wrapped(graphics, description, 16, 128, inner, Math.max(0, rows - 2), TEXT);
            if (lines.size() > used) screen.text(graphics, "\u2026", 16, 128 + 11 * used, inner, MUTED);
            else scene.wrapped(graphics, look, 16, 132 + 11 * used, inner, Math.max(0, rows - used), MUTED);
        }
    }

    private String templateExplanation() {
        try {
            return template().fallback() ? "Human appearance. The template uses race-specific art. Class stats carry over."
                    : "Compatible template appearance and class stats.";
        } catch (IllegalArgumentException missing) {
            return "Template unavailable. Choose an installed race.";
        }
    }

    private String heading() {
        if (setting != null) return setting.label();
        if (pickerTitle != null) return pickerTitle;
        return switch (section) {
            case OVERVIEW -> "Build Your Race";
            case STATS -> classId.isBlank() ? "Class Stats" : EditorPresentation.readable(classId) + " Stats";
            case ABILITY -> "Racial Ability";
            case FORMS -> "Transformations";
            case FORM -> formSection.isEmpty() ? formName(currentForm) : formSection;
            case PRICES -> "Skill Level Prices";
            default -> EditorPresentation.readable(section.name().toLowerCase(Locale.ROOT));
        };
    }

    private String sectionHelp() {
        if (setting != null) return setting.help();
        return switch (section) {
            case OVERVIEW -> "Open any section to customize the draft. Review Changes asks the server to validate the whole race.";
            case APPEARANCE -> "These are the starting choices for new characters. The fighter shows the selected appearance.";
            case STATS -> "Choose a class to set its starting attributes and how they scale.";
            case ABILITY -> "Choose an ability already registered by Dragon Mine Z or an installed addon.";
            case FORMS, FORM -> "Draft a new transformation or copy supported settings from one in the library. Switch between the base race and each draft form below the fighter.";
            case PRICES -> "Forms that require the same skill and level share one TP price. Intermediate levels without a form cost 10,000 TP.";
            case ADVANCED -> "The stable ID becomes the save key when the race is published.";
        };
    }

    private static String formName(Form form) { return form.name.isBlank() ? "Untitled Transformation" : form.name; }

    private static String display(String value) { return value.isBlank() ? "Default" : value; }

}
