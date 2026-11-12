package dev.dbzdoanything;

import com.dragonminez.common.config.FormConfig;
import com.dragonminez.common.config.RaceCharacterConfig;
import com.google.gson.JsonObject;
import dev.dbzdoanything.mixin.ConfigAccess;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

import static dev.dbzdoanything.RaceEditorScene.ACCENT;
import static dev.dbzdoanything.RaceEditorScene.MUTED;
import static dev.dbzdoanything.RaceEditorScene.TEXT;
import static dev.dbzdoanything.RaceEditorScene.WARNING;

final class PublishedRacePage {
    private enum View { ATTRIBUTES, GROUPS, FORMS, VALUE, OPTIONS, MANAGE, CONFIRM, CONFLICTS, CONFLICT }
    private record Row(String label, String help, Runnable action) {}

    private static final List<String> BASE = List.of("Appearance", "Class Stats", "Racial Ability", "Skill Prices", "Advanced");
    private static final List<String> FORM = List.of("Appearance", "Stat Bonuses", "Unlock Requirements",
            "Mastery", "Upkeep & Stacking", "Advanced");

    private final EditorScreen screen;
    private final RaceEditorScene scene;
    private final String raceId;
    private final PublishedRaceDraft draft = new PublishedRaceDraft();
    private EditorLibrary library;
    private JsonObject catalog;
    private EditorLibrary.Entry race;
    private EditorLibrary.Entry form;
    private String formKey;
    private String section = "";
    private String groupPath = "";
    private View view = View.ATTRIBUTES;
    private EditorField field;
    private EditBox input;
    private String pending = "";
    private String error = "";
    private String previewNote = "";
    private PublishedRaceDraft.Conflict conflict;
    private Runnable continuation;
    private List<Row> choices = List.of();
    private int width;
    private int height;
    private int right;
    private int inner;
    private int offset;
    private int sectionOffset;
    private int shown;
    private int total;
    private boolean stale;

    PublishedRacePage(EditorScreen screen, JsonObject catalog, EditorLibrary library, EditorLibrary.Entry entry) {
        this.screen = screen;
        this.scene = new RaceEditorScene(screen);
        raceId = entry.raceId();
        formKey = entry.race() ? null : entry.key();
        refresh(catalog, library);
    }

    String raceId() { return raceId; }
    EditorLibrary.Entry selection() { return form == null ? race : form; }
    boolean hasDraft() { return !draft.isEmpty(); }

    void refresh(JsonObject catalog, EditorLibrary library) {
        this.catalog = catalog;
        this.library = library;
        race = library.race(raceId);
        form = formKey == null ? null : library.find(formKey);
        if (form == null && formKey != null) {
            formKey = null;
            section = "";
        }
        stale = false;
        draft.refresh(catalog);
        field = null;
        conflict = null;
        error = "";
        if (!draft.conflicts(catalog).isEmpty()) setView(View.CONFLICTS);
        else if (view != View.ATTRIBUTES) setView(View.ATTRIBUTES);
        reloadPreview();
    }

    void markStale() {
        stale = true;
        continuation = null;
        if (view == View.CONFIRM) setView(View.ATTRIBUTES);
    }

    void clearDraft() {
        draft.clear();
        continuation = null;
    }

    void background(float delta) { scene.background(delta); }

    void init(int width, int height) {
        this.width = width;
        this.height = height;
        int panel = RaceEditorScene.panelWidth(width);
        right = width - panel;
        inner = panel - 16;
        input = null;
        shown = total = 0;
        screen.button(8, height - 27, 84, "Back", this::back);
        screen.button(width - 164, height - 27, 156, "Review Changes (" + draft.size() + ")", this::review)
                .active = hasDraft() && view != View.VALUE && view != View.CONFIRM && view != View.OPTIONS;
        screen.helpButton(16 + inner - 18, 45, "About", "The source supplies this race. Apply changes to publish an override in this world.");
        screen.helpButton(right + inner - 18, 45, heading(), viewHelp());
        if (view != View.CONFIRM && race != null) {
            screen.button(16, height - 102, inner, "Manage", () -> open(View.MANAGE));
            screen.button(16, height - 81, inner, "Refresh Catalog", screen::refreshPublishedCatalog);
            int center = width / 2;
            screen.button(center - 56, height - 54, 45, "Base", () -> select(null));
            var picker = screen.button(center - 9, height - 54, 65,
                    form == null ? "Forms" : form.label(), () -> open(View.GROUPS));
            picker.setTooltip(Tooltip.create(Component.literal("Choose a transformation")));
        }
        if (view == View.VALUE) {
            buildValue();
        } else if (view == View.CONFLICT) {
            buildConflict();
        } else {
            if (view != View.ATTRIBUTES || !section.isEmpty()) {
                String back = switch (view) {
                    case FORMS -> "Back to Groups";
                    case OPTIONS -> "Back to Value";
                    default -> "Back to Settings";
                };
                screen.button(right, 67, inner, back, this::back);
            }
            buildRows(rows(), view == View.ATTRIBUTES && section.isEmpty() ? 68 : 89, height - 62);
        }
    }

    private List<Row> rows() {
        return switch (view) {
            case ATTRIBUTES -> attributes();
            case GROUPS -> library.groups().stream().filter(group -> group.race().equals(raceId))
                    .map(group -> row(group.label() + " / " + EditorLibrary.sourceName(group.source()),
                            () -> { groupPath = group.path(); open(View.FORMS); })).toList();
            case FORMS -> library.forms(groupPath).stream().map(entry -> row(entry.label()
                    + (entry.disabled() ? " [Disabled]" : ""), () -> select(entry))).toList();
            case MANAGE -> management();
            case OPTIONS -> choices;
            case CONFIRM -> List.of(
                    new Row("Review Draft First", "Review these edits, then reopen Manage to choose the action.", () -> {
                        continuation = null;
                        setView(View.ATTRIBUTES);
                        review();
                    }),
                    new Row("Discard Draft", "Discard the staged attribute edits and continue.", this::discardAndContinue),
                    row("Cancel", this::cancelConfirmation));
            case CONFLICTS -> draft.conflicts(catalog).stream().map(item ->
                    row(conflictLabel(item), () -> { conflict = item; open(View.CONFLICT); })).toList();
            default -> List.of();
        };
    }

    private List<Row> attributes() {
        var selected = selection();
        if (selected == null) return draft.conflicts(catalog).isEmpty() ? List.of()
                : List.of(row("Resolve Conflicts", () -> open(View.CONFLICTS)));
        var rows = new ArrayList<Row>();
        if (section.isEmpty()) {
            if (hasDraft() && !draft.conflicts(catalog).isEmpty()) rows.add(row("Resolve Conflicts", () -> open(View.CONFLICTS)));
            for (String name : form == null ? BASE : FORM) rows.add(new Row(name, sectionHelp(name), () -> {
                section = name;
                open(View.ATTRIBUTES);
            }));
            return rows;
        }
        if (form != null && section.equals("Advanced")) {
            rows.add(row("Race ID \u00b7 " + raceId, () -> screen.openEditorHelp("Race ID", raceId)));
            rows.add(row("Group ID \u00b7 " + groupId(form), () -> screen.openEditorHelp("Group ID", groupId(form))));
            rows.add(row("Form ID \u00b7 " + form.id(), () -> screen.openEditorHelp("Form ID", form.id())));
            rows.add(row("Unlock Skill ID \u00b7 " + skill(form), () -> screen.openEditorHelp("Unlock Skill ID", skill(form))));
        } else if (form == null && section.equals("Advanced")) {
            rows.add(row("Race ID \u00b7 " + raceId, () -> screen.openEditorHelp("Race ID", raceId)));
        }
        if (form != null && section.equals("Unlock Requirements")) {
            var price = priceForForm();
            if (price != null) rows.add(fieldRow(price));
            else rows.add(row("No TP Price Slot", () -> screen.openEditorHelp("TP Price",
                    "There is no price slot for this form's current skill and level. Available slots are listed under Base > Skill Prices.")));
        }
        EditorField.list(catalog, selected).stream().filter(item -> section.equals(category(item)))
                .forEach(item -> rows.add(fieldRow(item)));
        return rows;
    }

    private EditorField priceForForm() {
        var level = EditorField.resolve(catalog, new EditorField.Address(form.path(), List.of("forms", form.id(), "unlockOnSkillLevel")));
        if (level == null) return null;
        int slot = new java.math.BigDecimal(draft.display(level)).intValue() - 1;
        return slot < 0 ? null : EditorField.resolve(catalog, new EditorField.Address(race.path(),
                List.of("formSkillsCosts", skill(form), "prices", Integer.toString(slot))));
    }

    private String category(EditorField item) {
        if (form == null && item.keys().get(0).equals("formSkillsCosts")) {
            return item.keys().size() == 4 && item.keys().get(2).equals("prices") ? "Skill Prices" : "Advanced";
        }
        if (item.key().equals("racialSkill")) return "Racial Ability";
        return EditorPresentation.section(item.path(), item.keys(), item.reason());
    }

    private Row fieldRow(EditorField item) {
        String state = item.reason() != null ? " [Locked]" : draft.staged(item) ? " [Edited]" : "";
        String label = item.label() + " \u00b7 " + display(draft.display(item)) + state;
        String help = "Saved value " + display(item.value().getAsString()) + ". " + item.help();
        return new Row(label, help, () -> edit(item));
    }

    private List<Row> management() {
        var current = selection();
        if (current == null) return List.of();
        var rows = new ArrayList<Row>();
        boolean verified = !current.source().equals("Unverified source");
        if (verified) rows.add(row(current.race() ? "Rename / Description" : "Rename Form", () -> manage(current, "name")));
        if (current.race()) {
            rows.add(row(current.disabled() ? "Restore Race" : "Delete Race",
                    () -> manage(current, current.disabled() ? "restoreRace" : "deleteRace")));
            rows.add(row("Clone Race", () -> manage(current, "cloneRace")));
        } else {
            if (EditorFields.canDisable(current.path(), current.document(), current.id(), current.source())) {
                rows.add(row(current.disabled() ? "Enable Form" : "Disable Form",
                        () -> manage(current, current.disabled() ? "enable" : "disable")));
            } else {
                rows.add(row("Availability [Locked]", () -> screen.openEditorHelp("Form Availability",
                        "The source manages this form's availability. Use Clone Form to make an editable copy.")));
            }
            rows.add(row("Clone Form", () -> manage(current, "cloneForm")));
        }
        if (verified || !current.race()) rows.add(row("New Transformation", () -> manage(current, "form")));
        return rows;
    }

    private void edit(EditorField item) {
        sectionOffset = offset;
        field = item;
        pending = draft.display(item);
        open(View.VALUE);
    }

    private void buildValue() {
        screen.button(right, 67, inner, "Back to Attributes", this::back);
        if (field.reason() != null) return;
        List<String> options = options(field);
        if (!field.appearancePreset()) {
            input = screen.box(right, 136, inner, field.label(), pending, 512);
            input.setResponder(value -> pending = value);
            screen.focusBuilder(input);
        }
        if (!options.isEmpty()) {
            screen.button(right, input == null ? 128 : 161, inner, "Choose", () -> {
                choices = options.stream().map(option -> row(optionLabel(option), () -> stage(option))).toList();
                open(View.OPTIONS);
            });
        }
        if (!field.appearancePreset()) screen.button(right, height - 103, inner, "Stage Value", () -> stage(pending));
        if (draft.staged(field)) screen.button(right, height - 82, inner, "Reset to Saved", () -> {
            draft.useSaved(field.address());
            finishValue();
        });
    }

    private List<String> options(EditorField item) {
        String key = item.key();
        if (item.appearancePreset()) return EditorScreen.appearanceValues(raceId, baseConfig(), key);
        if (item.value().isBoolean()) return List.of("true", "false");
        if (key.equals("auraType")) return BuilderAppearancePresets.TWO_D_AURAS;
        if (key.equals("auraType3D")) return BuilderAppearancePresets.THREE_D_AURAS;
        if (key.equals("formRequisiteType")) return List.of("all", "any");
        if (key.equals("racialSkill")) {
            var abilities = new ArrayList<String>();
            abilities.add("");
            catalog.getAsJsonArray("abilities").forEach(value -> abilities.add(value.getAsString()));
            return abilities;
        }
        if (key.equals("customModel")) {
            var models = new ArrayList<String>();
            models.add("");
            models.addAll(screen.modelOptions(baseConfig().getHasGender()));
            return models;
        }
        return List.of();
    }

    private String optionLabel(String option) {
        if (field.appearancePreset()) return EditorScreen.choiceLabel(field.key(), option);
        if (option.isEmpty()) return field.key().equals("racialSkill") ? "None" : "Use Base Model";
        if (field.value().isBoolean()) return option.equals("true") ? "On" : "Off";
        return EditorPresentation.readable(option);
    }

    private void stage(String value) {
        try {
            if (field.appearancePreset() && !options(field).contains(value)) {
                throw new IllegalArgumentException("Choose an installed appearance preset.");
            }
            if (field.key().equals("customModel") && !options(field).contains(value)) {
                throw new IllegalArgumentException("Choose a model compatible with this race.");
            }
            draft.stage(field, value);
            finishValue();
        } catch (IllegalArgumentException | ArithmeticException invalid) {
            error = invalid instanceof ArithmeticException ? "Enter a whole number." : invalid.getMessage();
            screen.rebuild();
        }
    }

    private void finishValue() {
        field = null;
        setView(View.ATTRIBUTES);
        offset = sectionOffset;
        reloadPreview();
        screen.rebuild();
    }

    boolean enter() {
        if (view != View.VALUE || input == null || !input.isFocused()) return false;
        stage(pending);
        return true;
    }

    private void buildConflict() {
        screen.button(right, 67, inner, "Back to Conflicts", this::back);
        screen.button(right, height - 102, inner, "Use Saved Value", () -> resolve(false));
        screen.button(right, height - 81, inner, "Keep Draft Value", () -> resolve(true)).active = conflict.canKeepDraft();
    }

    private void resolve(boolean keep) {
        if (keep) draft.keepDraft(catalog, conflict.address());
        else draft.useSaved(conflict.address());
        reloadPreview();
        if (draft.conflicts(catalog).isEmpty()) {
            section = "";
            open(View.ATTRIBUTES);
        } else open(View.CONFLICTS);
    }

    private String conflictLabel(PublishedRaceDraft.Conflict item) {
        List<String> keys = item.address().keys();
        String owner = item.address().path().contains("/forms/") && keys.size() > 1
                ? EditorLibrary.name(item.address().path(), keys.get(1)) : "Base";
        return owner + " / " + EditorPresentation.fieldLabel(keys);
    }

    private void buildRows(List<Row> rows, int first, int bottom) {
        total = rows.size();
        shown = Math.max(1, (bottom - first) / 22);
        boolean paginate = total > shown;
        if (paginate) shown = Math.max(1, shown - 1);
        offset = Math.min(offset, Math.max(0, total - shown));
        for (int i = offset; i < Math.min(total, offset + shown); i++) {
            var row = rows.get(i);
            int y = first + (i - offset) * 22;
            var button = screen.button(right, y, row.help().isEmpty() ? inner : inner - 20, row.label(), row.action());
            button.setTooltip(Tooltip.create(Component.literal(row.label())));
            if (!row.help().isEmpty()) screen.helpButton(right + inner - 18, y, row.label(), row.help());
        }
        if (paginate) {
            int y = first + shown * 22;
            int half = (inner - 4) / 2;
            screen.button(right, y, half, "Previous", () -> scroll(-shown)).active = offset > 0;
            screen.button(right + half + 4, y, half, "Next", () -> scroll(shown)).active = offset + shown < total;
        }
    }

    boolean scroll(int amount) {
        int next = Math.min(Math.max(0, total - shown), Math.max(0, offset + amount));
        if (next == offset) return false;
        offset = next;
        screen.rebuild();
        return true;
    }

    private void select(EditorLibrary.Entry entry) {
        form = entry;
        formKey = entry == null ? null : entry.key();
        section = "";
        reloadPreview();
        open(View.ATTRIBUTES);
    }

    private void review() {
        if (stale) {
            screen.showPublishedStale();
        } else if (!draft.conflicts(catalog).isEmpty()) {
            open(View.CONFLICTS);
        } else if (hasDraft()) {
            screen.reviewPublished(draft.edits());
        }
    }

    private void manage(EditorLibrary.Entry entry, String action) {
        if (stale) {
            screen.showPublishedStale();
            return;
        }
        Runnable run = () -> screen.managePublished(entry.key(), action);
        if (!hasDraft()) run.run();
        else confirm(run);
    }

    private void confirm(Runnable action) {
        continuation = action;
        open(View.CONFIRM);
    }

    private void discardAndContinue() {
        var action = continuation;
        clearDraft();
        section = "";
        setView(View.ATTRIBUTES);
        reloadPreview();
        if (action != null) action.run();
    }

    private void cancelConfirmation() {
        continuation = null;
        open(View.ATTRIBUTES);
    }

    void back() {
        switch (view) {
            case VALUE -> {
                setView(View.ATTRIBUTES);
                offset = sectionOffset;
                screen.rebuild();
            }
            case OPTIONS -> open(View.VALUE);
            case CONFLICT -> open(View.CONFLICTS);
            case FORMS -> open(View.GROUPS);
            case CONFIRM -> cancelConfirmation();
            case GROUPS, MANAGE, CONFLICTS -> open(View.ATTRIBUTES);
            case ATTRIBUTES -> {
                if (!section.isEmpty()) {
                    section = "";
                    open(View.ATTRIBUTES);
                } else if (hasDraft()) confirm(screen::closePublished);
                else screen.closePublished();
            }
        }
    }

    private void open(View next) {
        setView(next);
        screen.rebuild();
    }

    private void setView(View next) {
        view = next;
        offset = 0;
        error = "";
    }

    private RaceCharacterConfig baseConfig() {
        return ConfigAccess.editorGson().fromJson(draft.overlay(catalog, race.path()), RaceCharacterConfig.class);
    }

    private void reloadPreview() {
        previewNote = "";
        if (race == null) {
            scene.clear();
            return;
        }
        var config = baseConfig();
        if (form == null) {
            scene.show(raceId, config, "", "", null);
            return;
        }
        var document = draft.overlay(catalog, form.path());
        var data = ConfigAccess.editorGson().fromJson(document.getAsJsonObject("forms").get(form.id()), FormConfig.FormData.class);
        var model = EditorField.resolve(catalog, new EditorField.Address(form.path(), List.of("forms", form.id(), "customModel")));
        boolean stagedModel = model != null && draft.staged(model);
        try {
            scene.show(raceId, config, groupId(form), form.id(), data);
            if ((!stagedModel || data.getCustomModel().isBlank() || screen.modelOptions(config.getHasGender()).contains(data.getCustomModel()))
                    && scene.hasGeometry()) return;
        } catch (IllegalArgumentException | IllegalStateException invalid) {
            // Keep the editor available for corrections when the selected look cannot be resolved.
        }
        scene.show(raceId, config, "", "", null);
        previewNote = "Form preview unavailable. Showing Base.";
    }

    void render(GuiGraphics graphics) {
        scene.frame(graphics, width, height, "Race and Transformation Editor", heading());
        scene.fighter(graphics, width, height);
        if (!previewNote.isEmpty()) scene.centered(graphics, previewNote, width / 2, 34, width - 20, WARNING);
        drawAbout(graphics);
        if (view == View.VALUE) drawValue(graphics);
        if (view == View.CONFLICT) drawConflict(graphics);
        if (view == View.CONFLICTS && total == 0) screen.text(graphics, "No conflicts to resolve.", right, 94, inner, TEXT);
        if ((view == View.GROUPS || view == View.FORMS || view == View.OPTIONS) && total == 0) {
            screen.text(graphics, "No entries available.", right, 94, inner, MUTED);
        }
        if (view == View.ATTRIBUTES && total == 0) screen.text(graphics, "No settings in this section.", right, 94, inner, MUTED);
        if (!error.isEmpty()) scene.centered(graphics, error, width / 2, height - 40, width - 20, WARNING);
    }

    private void drawAbout(GuiGraphics graphics) {
        if (race == null) {
            screen.text(graphics, "Race no longer available", 16, 70, inner, WARNING);
            return;
        }
        screen.text(graphics, race.label(), 16, 70, inner, ACCENT);
        screen.text(graphics, EditorLibrary.sourceName(race.source()), 16, 84, inner, MUTED);
        int y = 102;
        if (race.disabled()) {
            screen.text(graphics, "Deleted from character selection", 16, y, inner, WARNING);
            y += 15;
        }
        if (form != null) {
            screen.text(graphics, form.label() + (form.disabled() ? " [Disabled]" : ""), 16, y, inner,
                    form.disabled() ? WARNING : TEXT);
            var group = library.group(form.path());
            screen.text(graphics, group.label() + " / " + EditorLibrary.sourceName(group.source()), 16, y + 14, inner, MUTED);
            y += 31;
        }
        String description = Component.translatableWithFallback(EditorDraft.translation(race.path(), race.id()) + ".desc", "").getString();
        if (!description.isBlank()) scene.wrapped(graphics, description, 16, y, inner, Math.max(0, (height - 107 - y) / 11), TEXT);
    }

    private void drawValue(GuiGraphics graphics) {
        screen.text(graphics, "Saved  " + display(field.value().getAsString()), right, 94, inner, MUTED);
        if (draft.staged(field)) screen.text(graphics, "Staged  " + display(draft.display(field)), right, 108, inner, ACCENT);
        if (field.reason() != null) {
            scene.wrapped(graphics, field.reason(), right, 124, inner, Math.max(1, (height - 188) / 11), WARNING);
        }
    }

    private void drawConflict(GuiGraphics graphics) {
        var item = conflict.field();
        int rows = Math.max(1, (height - 216) / 22);
        String saved = item == null ? "Field removed" : display(item.value().getAsString());
        int used = scene.wrapped(graphics, "Saved  " + saved, right, 95, inner, rows, TEXT);
        used += scene.wrapped(graphics, "Draft  " + display(conflict.draft()), right, 102 + used * 11, inner, rows, ACCENT);
        if (!conflict.canKeepDraft()) {
            String reason = item != null && item.reason() != null ? item.reason() : "This value can no longer be reapplied.";
            scene.wrapped(graphics, reason, right, 110 + used * 11, inner, Math.max(1, rows), WARNING);
        }
    }

    private String heading() {
        return switch (view) {
            case ATTRIBUTES -> section.isEmpty() ? form == null ? "Base Settings" : form.label() : section;
            case GROUPS -> "Transformation Groups";
            case FORMS -> library.group(groupPath).label();
            case VALUE, OPTIONS -> field.label();
            case MANAGE -> form == null ? "Manage Race" : "Manage Transformation";
            case CONFIRM -> "Staged Changes";
            case CONFLICTS -> "Resolve Conflicts";
            case CONFLICT -> conflictLabel(conflict);
        };
    }

    private String viewHelp() {
        return switch (view) {
            case ATTRIBUTES -> section.isEmpty() ? "Choose a category to see its values. Edits are staged until you review and apply them."
                    : sectionHelp(section);
            case GROUPS, FORMS -> "Choose a race transformation to show its attributes and preview. Disabled forms stay disabled when you edit them.";
            case VALUE, OPTIONS -> field.help();
            case MANAGE -> "Each management action has its own review. Resolve staged attributes first.";
            case CONFIRM -> "Review and apply the draft, discard it, or cancel and return to editing.";
            case CONFLICTS -> "The saved value changed while you were editing. Choose which value to keep, then review the draft again.";
            case CONFLICT -> "Saved  " + (conflict.field() == null ? "Field removed" : display(conflict.field().value().getAsString()))
                    + "\nDraft  " + display(conflict.draft());
        };
    }

    private static String sectionHelp(String section) {
        return switch (section) {
            case "Appearance" -> "Shows saved cosmetics or form overrides. Valid staged changes appear on the fighter.";
            case "Class Stats" -> "Starting stats apply to new characters. Scaling affects the class during play.";
            case "Racial Ability" -> "Choose an installed ability if the source allows editing it.";
            case "Skill Prices" -> "A TP price belongs to a skill level. Every form using that slot shares the same price.";
            case "Unlock Requirements" -> "Sets access to the transformation. Its TP price is shared with forms using the same skill and level.";
            case "Mastery" -> "Controls supported mastery gain. Existing mastery is stored on each player.";
            case "Upkeep & Stacking" -> "Controls supported Ki, stamina, health, and stacking settings while transformed.";
            case "Advanced" -> "View stable IDs and settings managed by Dragon Mine Z or the source addon.";
            default -> "Sets the stat multipliers while this transformation is active.";
        };
    }

    private static Row row(String title, Runnable action) { return new Row(title, "", action); }
    private static String groupId(EditorLibrary.Entry entry) { return entry.path().substring(entry.path().lastIndexOf('/') + 1); }
    private static String skill(EditorLibrary.Entry entry) { return entry.document().get("formType").getAsString(); }
    private static String display(String value) { return value.isEmpty() ? "Default" : value; }
}
