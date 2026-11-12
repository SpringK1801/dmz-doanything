package dev.dbzdoanything;

import com.dragonminez.client.gui.character.util.ScaledScreen;
import com.dragonminez.client.util.TextureCounter;
import com.dragonminez.common.config.ConfigManager;
import com.dragonminez.common.config.RaceCharacterConfig;
import com.dragonminez.common.hair.HairManager;
import com.dragonminez.common.hair.HairPresets;
import com.dragonminez.common.stats.StatsCapability;
import com.dragonminez.common.stats.StatsProvider;
import com.dragonminez.common.stats.character.AppearanceValidator;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.mojang.authlib.GameProfile;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.client.gui.components.Tooltip;
import dev.dbzdoanything.EditorLibrary.Entry;

final class EditorScreen extends ScaledScreen {
    private static final ResourceLocation BUTTONS = ResourceLocation.fromNamespaceAndPath("dragonminez", "textures/gui/buttons/characterbuttons.png");
    private static final int LEFT = 176;
    private static final int FIRST_ROW = 102;
    private static final int ROW = 22;
    private static final List<String> RACE_SECTIONS = List.of("Overview", "Appearance", "Class Stats", "Abilities", "Advanced");
    private static final List<String> FORM_SECTIONS = List.of("Overview", "Appearance", "Stat Bonuses", "Unlock Requirements", "Mastery", "Upkeep & Stacking", "Advanced");

    private record Row(String title, String detail, Runnable select) {}
    private record Help(String title, String text) {}
    private enum Page { LIBRARY, ADDON, SHARED, GROUP, DELETED, ENTRY, SPECIAL }

    private JsonObject catalog;
    private RaceBuilderPage builder;
    private PublishedRacePage published;
    private String previousEntry;
    private int publishedReviewTicks;
    private net.minecraft.client.gui.components.events.GuiEventListener builderFocus;
    private EditorLibrary library;
    private long revision;
    private Page page = Page.LIBRARY;
    private String source = "";
    private String group = "";
    private Entry selected;
    private String section = "Overview";
    private String query = "";
    private String note = "";
    private String operation = "";
    private String reviewOperation = "";
    private String token;
    private String validation;
    private boolean technicalReview;
    private boolean stale;
    private boolean applying;
    private String nextEntry;
    private int browseOffset;
    private int fieldOffset;
    private int reviewOffset;
    private List<WorldEditor.ReviewRow> review = List.of();
    private List<Row> assetRows = List.of();
    private String assetCategory = "Models";
    private List<String> choices = List.of();
    private List<Row> wizardChoices = List.of();
    private Help help;
    private EditorField field;
    private final List<EditorDraft.FieldEdit> draft = new ArrayList<>();
    private final Map<String, EditorDraft.RuleEdit> ruleDraft = new LinkedHashMap<>();
    private EditBox input;
    private RemotePlayer preview;

    private String wizardName = "";
    private String wizardId = "";
    private boolean customId;
    private String wizardDescription = "";
    private String wizardRace = "";
    private String wizardGroup = "";
    private String wizardNewGroup = "";
    private String wizardSkill = "";
    private String wizardLevel = "";

    EditorScreen(long revision, JsonObject catalog) {
        super(Component.literal("DMZ Do Anything"));
        this.revision = revision;
        this.catalog = catalog;
        library = new EditorLibrary(catalog);
        if (catalog.has("conflict")) validation = catalog.get("conflict").getAsString();
    }

    void load(long revision, JsonObject catalog) {
        this.catalog = catalog;
        library = new EditorLibrary(catalog);
        boolean appliedPublished = applying && reviewOperation.equals("publishedEdit") && published != null;
        if (appliedPublished) published.clearDraft();
        if (published != null) published.refresh(catalog, library);
        publishedReviewTicks = 0;
        validation = catalog.has("conflict") ? catalog.get("conflict").getAsString() : null;
        note = applying ? "World revision applied." : "Library refreshed.";
        if (applying && reviewOperation.equals("edit")) {
            draft.clear();
            ruleDraft.clear();
        }
        Entry created = applying && nextEntry != null ? library.find(nextEntry) : null;
        if (applying && reviewOperation.equals("raceBuilder") && created != null) builder = null;
        else if (builder != null) builder.refresh(catalog, library);
        applying = false;
        this.revision = revision;
        stale = false;
        operation = "";
        token = null;
        help = null;
        field = null;
        if (created != null) {
            openEntry(created);
            return;
        }
        if (selected != null) {
            selected = library.find(selected.key());
            if (selected == null) page = Page.LIBRARY;
        }
        if (published == null && builder == null) loadPreview();
        rebuild();
    }

    void stale() {
        if (applying) return;
        publishedReviewTicks = 0;
        if (published != null) published.markStale();
        boolean pristine = token == null && draft.isEmpty() && ruleDraft.isEmpty() && operation.isEmpty() && help == null && builder == null && (published == null || !published.hasDraft());
        stale = true;
        token = null;
        validation = "A new world revision is active. Refresh before reviewing your staged changes.";
        rebuild();
        if (pristine) EditorNetwork.request(0, "");
    }

    void review(JsonObject response) {
        if (reviewOperation.equals("publishedEdit") && publishedReviewTicks == 0 && !applying) return;
        publishedReviewTicks = 0;
        if (response.has("error")) {
            applying = false;
            token = null;
            validation = response.get("error").getAsString();
            if (builder != null && validation.startsWith("This race draft is stale")) stale = true;
            if (published != null && (validation.toLowerCase(Locale.ROOT).contains("stale")
                    || validation.contains("source value changed") || validation.contains("Review this draft again"))) {
                stale = true;
                published.markStale();
            }
            help = null;
            reviewOffset = 0;
            rebuild();
            return;
        }
        validation = null;
        token = response.get("token").getAsString();
        review = new ArrayList<>();
        response.getAsJsonArray("lines").forEach(line ->
                review.add(RevisionStore.GSON.fromJson(line, WorldEditor.ReviewRow.class)));
        technicalReview = false;
        reviewOffset = 0;
        rebuild();
    }

    @Override
    protected void init() {
        super.init();
        updateUiScale();
        int right = getUiWidth();
        int bottom = getUiHeight();
        if (help != null) {
            button(right - 105, bottom - 28, 92, "Close Help", () -> { help = null; reviewOffset = 0; rebuild(); });
            return;
        }
        if (validation != null || token != null) {
            if (publishedReviewTicks > 0) return;
            button(10, bottom - 28, 74, "Back", this::closeReview);
            if (token != null) {
                action(right - 121, bottom - 28, 90, "Apply Live",
                        "Publishes the reviewed changes to this world and sends the new revision to connected players.",
                        () -> { if (!applying) { applying = true; EditorNetwork.request(2, token); } });
                button(90, bottom - 28, 114, technicalReview ? "Friendly View" : "Technical Details",
                        () -> { technicalReview = !technicalReview; reviewOffset = 0; rebuild(); });
                helpButton(right - 32, 55, "Review Changes", "Check the effects on players and the before-and-after values. Apply Live publishes a new world revision.");
            }
            if (stale) button(right - 108, bottom - 28, 96, "Refresh", () -> EditorNetwork.request(0, ""));
            return;
        }
        if (builder != null) {
            builder.init(right, bottom);
            return;
        }
        if (!operation.isEmpty()) {
            if (operation.equals("assets")) {
                int x = 10;
                for (String category : List.of("Models", "Textures", "Skills", "Abilities")) {
                    action(x, 55, 82, category, sectionHelp(category), () -> { assetCategory = category; browseAssets(); });
                    x += 108;
                }
                button(10, bottom - 28, 74, "Back", () -> { operation = ""; rebuild(); });
                return;
            }
            if (operation.equals("pick") || operation.equals("wizardPick")) {
                button(10, bottom - 28, 74, "Back", () -> { operation = operation.equals("pick") ? "" : wizardAction; rebuild(); });
                helpButton(right - 32, 55, "Choose a value", operation.equals("pick") && field != null
                        ? field.help() : "Choose a named catalog entry. The editor fills in its stable ID.");
                return;
            }
            buildWizard(right, bottom);
            return;
        }

        if (published != null) {
            published.init(right, bottom);
            return;
        }

        button(10, 29, 92, "Addon Library", () -> navigate(Page.LIBRARY, "", "", null));
        helpButton(104, 29, "Addon Library", "Browse the sources confirmed by this server. Open an addon to see its races and shared transformations. Search across the whole library by name or ID.");
        if (page != Page.LIBRARY || !query.isBlank()) button(128, 29, 61, "Back", this::back);
        action(196, 29, 68, "Assets", "Browse loaded models, textures, skills, and abilities. Asset files are supplied by the installed mods and resource packs.", this::browseAssets);
        action(291, 29, 118, "Special Settings", "Manage this world's gameplay restrictions. Changes apply live after review.",
                () -> navigate(Page.SPECIAL, "", "", null));
        action(right - 120, 29, 88, "Refresh", "Reloads the server catalog. Staged changes still need to pass the server's stale-value checks.", () -> EditorNetwork.request(0, ""));
        action(right - 202, 55, 170, "Review Changes (" + (draft.size() + ruleDraft.size()) + ")", "Review all staged edits before applying them. Changes do not go live until you confirm.",
                this::reviewDraft);
        if (page == Page.SPECIAL) {
            buildSpecialSettings();
            return;
        }
        var search = box(10, 57, Math.max(100, right - 223), "Search the whole library", query, 80);
        search.setResponder(value -> { query = value; browseOffset = 0; });
        button(10, bottom - 28, 105, "New Race", () -> startWizard("race"));
        helpButton(117, bottom - 28, "New Race", "Creates a race in This World using the Human template. A display name is what players see. The stable ID is used in character saves.");
        action(143, bottom - 28, 98, "Revision History", "Restore an earlier world revision by number. Revision 0 is the starting catalog. Restoring publishes a new revision.", () -> startWizard("revert"));
        if (selected == null) {
            if (page != Page.LIBRARY) helpButton(right - 32, 80, heading(),
                    page == Page.DELETED ? "Deleted races are hidden from new character creation. Open a race and select Restore Race to make it available again."
                            : "Open an entry to view its settings. The smaller line shows its owner, location, or availability.");
            if (page == Page.GROUP || page == Page.SHARED) action(right - 171, bottom - 28, 140, "New Transformation",
                    "Create a form in a named group with a native transformation skill.", () -> startWizard("form"));
            return;
        }

        int y = FIRST_ROW - 4;
        for (String item : selected.race() ? RACE_SECTIONS : FORM_SECTIONS) {
            String name = item.equals(section) ? "\u203a " + item : item;
            button(10, y, LEFT - 38, name, () -> {
                section = item; field = null; fieldOffset = 0; query = ""; rebuild();
            });
            helpButton(LEFT - 26, y, item, sectionHelp(item));
            y += 24;
        }
        if (!query.isBlank()) return;
        if (section.equals("Overview")) {
            int x = LEFT + 9;
            if (selected.race()) {
                helpButton(right - 32, 140, "Transformation Groups", "These groups belong to this race. Each group shows the source that provides it, which can differ from the race's source.");
                String action = selected.disabled() ? "Restore Race" : "Delete Race";
                String op = selected.disabled() ? "restoreRace" : "deleteRace";
                action(x, bottom - 82, 102, action, sectionHelp(action),
                        () -> submit(op, selected.path(), selected.id(), "", "", "", List.of()));
                action(x + 130, bottom - 82, 145, "New Transformation", "Choose a destination group and unlock skill for this race.", () -> startWizard("form"));
            } else {
                String action = selected.disabled() ? "Enable Form" : "Disable Form";
                action(x, bottom - 82, 104, action, "Change whether players can use this form. Managed forms may require an editable clone.",
                        () -> submit(selected.disabled() ? "enable" : "disable", selected.path(), selected.id(), "", "", "", List.of()));
                action(x + 132, bottom - 82, 145, "New Transformation", "Create another form using this group as the destination.", () -> startWizard("form"));
            }
            action(right - 121, bottom - 28, 90, selected.race() ? "Clone Race" : "Clone Form", "Copy the selected entry into a new stable ID. The editor uses compatible destination assets and resets source-specific prerequisites for forms.",
                    () -> startWizard(selected.race() ? "cloneRace" : "cloneForm"));
            action(right - 250, bottom - 28, 101, "Display Name", "Change the label players see. The stable ID and existing saves keep their identity.", () -> startWizard("name"));
        } else {
            var fields = fields();
            int start = fieldStart();
            for (int i = fieldOffset, rowY = start; i < fields.size() && rowY + ROW <= rowLimit(); i++, rowY += ROW) {
                var setting = fields.get(i);
                helpButton(right - (section.equals("Appearance") && preview != null ? 118 : 24), rowY, setting.label(), setting.help());
            }
        }
        if (field != null) {
            helpButton(right - 31, bottom - 54, field.label(), field.help());
            if (field.reason() == null) {
                String current = draft.stream().filter(edit -> edit.path().equals(field.path()) && edit.field().equals(field.keys()))
                        .map(EditorDraft.FieldEdit::value).findFirst().orElse(field.value().getAsString());
                boolean picker = field.appearancePreset() || field.key().matches("customModel|racialSkill");
                input = box(LEFT + 9, bottom - 53, Math.max(70, right - LEFT - (picker ? 199 : 115)), field.label(), current, 512);
                input.setEditable(!field.appearancePreset());
                if (picker) button(right - 190, bottom - 54, 74, "Choose", this::pickValue);
                if (!field.appearancePreset()) button(right - 110, bottom - 54, 74, "Stage", () -> { stage(input.getValue()); rebuild(); });
            }
        }
    }

    private void reviewDraft() {
        if (stale) return;
        if (draft.isEmpty() && ruleDraft.isEmpty()) {
            validation = "Stage at least one setting to review.";
            rebuild();
        } else {
            send(new EditorDraft.Proposal(revision, "edit", "", "", "", "", "", 0, List.copyOf(draft), Map.copyOf(ruleDraft)));
        }
    }

    private void buildSpecialSettings() {
        helpButton(101, 124, "Progression", "Control Training Point awards, stat spending, and new form grants in this world.");
        helpButton(101, 243, "Ki", "Control how players recover Ki through manual charging.");
        for (var rule : SpecialRule.values()) {
            int y = rule == SpecialRule.KI_CHARGING ? 270 : 150 + 26 * rule.ordinal();
            boolean enabled = publishedRule(rule);
            var pending = ruleDraft.get(rule.id);
            boolean staged = pending == null ? enabled : pending.value().getAsBoolean();
            action(18, y, 289, rule.label + "  \u00b7  " + SpecialRule.state(staged) + (pending == null ? "" : " (staged)"),
                    rule.help, () -> {
                        if (enabled == !staged) ruleDraft.remove(rule.id);
                        else ruleDraft.put(rule.id, new EditorDraft.RuleEdit(new JsonPrimitive(enabled), new JsonPrimitive(!staged)));
                        rebuild();
                    });
        }
    }

    private boolean publishedRule(SpecialRule rule) {
        for (var entry : catalog.getAsJsonArray("specialRules")) if (entry.getAsString().equals(rule.id)) return true;
        return false;
    }

    private static String sectionHelp(String section) {
        return switch (section) {
            case "Overview" -> "Shows where this entry comes from, its current availability, and actions you can take. A race's transformation groups are listed here.";
            case "Appearance" -> "Controls presets, colors, and models. Starting presets affect new characters. Form appearance applies when the form is active.";
            case "Class Stats" -> "Starting values apply to new characters. Scaling changes the effect of existing and future stat allocations.";

            case "Stat Bonuses" -> "Multipliers scale stats during a transformation. A multiplier of 1.0 keeps the underlying value.";
            case "Unlock Requirements" -> "Set the skill level, character level, transformations, and completed saga quest required to unlock a form.";
            case "Mastery" -> "Set mastery gained from hits and time in form. Required mastery is listed with unlock requirements.";
            case "Upkeep & Stacking" -> "Control how much Ki, stamina, or health the transformation uses, and whether compatible forms can overlap.";
            case "Advanced" -> "Stable IDs and technical settings. Stable IDs are used by character saves. Locked fields show why the source controls them.";
            case "Delete Race" -> "Hides this race from new character creation. Current characters, including offline players, keep their race, appearance, forms, and progression. You can restore it later.";
            case "Restore Race" -> "Makes the race selectable again using its existing stable ID and settings.";
            case "Models" -> "Geometry supplies the shape and bones used by Dragon Mine Z's character renderer.";
            case "Textures" -> "Textures supply the appearance rendered on a model. These are loaded client resources.";
            case "Skills" -> "Skills include the transformation and stack skills that unlock groups and stages.";
            case "Abilities" -> "Review installed racial abilities and training prices. An addon may manage which races or skills can use these settings.";
            default -> "Browse entries in this section and open one to see its settings.";
        };
    }
    private String wizardAction;

    private void startWizard(String action) {
        if (action.equals("race") || action.equals("cloneRace")) {
            builder = new RaceBuilderPage(this, catalog, library, action.equals("cloneRace") ? selected : null);
            rebuild();
            return;
        }
        wizardAction = operation = action;
        wizardName = action.equals("name") ? selected.label() : "";
        wizardId = "";
        customId = false;
        wizardDescription = action.equals("name") && selected.race()
                ? Component.translatableWithFallback(EditorDraft.translation(selected.path(), selected.id()) + ".desc", "").getString() : "";
        var currentGroup = library.group(selected == null ? group : selected.race() ? "" : selected.path());
        wizardRace = selected != null && selected.race() ? selected.id()
                : currentGroup != null ? currentGroup.race() : page == Page.SHARED ? "" : "human";
        wizardGroup = currentGroup != null && canExtend(currentGroup) ? currentGroup.path() : "";
        wizardNewGroup = "";
        wizardSkill = defaultSkill(wizardRace);
        wizardLevel = "";
        rebuild();
    }

    private static boolean canExtend(EditorLibrary.Group candidate) {
        return !List.of("DMZUltra", "Unverified source").contains(candidate.source());
    }

    private boolean formWizard() { return wizardAction.equals("form") || wizardAction.equals("cloneForm"); }

    private void buildWizard(int right, int bottom) {
        int x = LEFT + 12;
        int width = right - x - 38;
        if (wizardAction.equals("revert")) {
            watch(box(x, 111, width, "Revision number", wizardId, 20), value -> wizardId = value);
        } else {
            EditBox stableId = wizardAction.equals("name") ? null : box(x, 141, width, "Stable ID", wizardId, 32);
            watch(box(x, 105, width, "Display name", wizardName, 64), value -> {
                wizardName = value;
                if (stableId != null && !customId) {
                    wizardId = suggestedId(value);
                    watch(stableId, ignored -> {});
                    stableId.setValue(wizardId);
                    watch(stableId, edited -> { wizardId = edited; customId = true; });
                }
            });
            if (stableId != null) watch(stableId, value -> { wizardId = value; customId = true; });
            helpButton(right - 32, 140, "Stable ID", "Use up to 32 lowercase letters, digits, and underscores, starting with a letter. Saved characters reference this ID.");
            if (formWizard()) {
                action(x, 172, width - 22, "Destination: " + destinationName(), "Choose a race or Shared Transformations.", this::chooseRace);
                var current = library.group(wizardGroup);
                action(x, 201, width - 22, current == null ? "Group: Create a new group" : "Group: " + current.label(),
                        "Choose an existing editable group or create a group with a new stable ID.", this::chooseGroup);
                if (current == null) {
                    watch(box(x, 238, width, "New group ID", wizardNewGroup, 32), value -> wizardNewGroup = value);
                    action(x, 263, width - 22, "Unlock skill: " + EditorPresentation.readable(wizardSkill),
                            "Select a skill recognized by Dragon Mine Z for this destination.", this::chooseSkill);
                }
                watch(box(x, current == null ? 290 : 238, width, "Unlock level \u00b7 blank for next level", wizardLevel, 3),
                        value -> wizardLevel = value);
            } else if (selected.race()) {
                watch(box(x, wizardAction.equals("name") ? 142 : 177, width, "Race description", wizardDescription, 512),
                        value -> wizardDescription = value);
            }
        }
        button(10, bottom - 28, 74, "Cancel", () -> { operation = ""; rebuild(); });
        action(right - 121, bottom - 28, 90, "Review", "Check the proposed changes with the server before applying them.", this::reviewWizard);
    }

    private String destinationName() {
        var race = library.race(wizardRace);
        return wizardRace.isEmpty() ? "Shared Transformations" : race == null ? EditorPresentation.readable(wizardRace)
                : race.label() + " \u00b7 " + EditorLibrary.sourceName(race.source());
    }

    private void chooseRace() {
        var rows = new ArrayList<Row>();
        rows.add(new Row("Shared Transformations", "Available across races where compatible", () -> setDestination("")));
        for (var race : library.races()) if (!race.disabled() && !race.source().equals("Unverified source")) {
            rows.add(new Row(race.label(), EditorLibrary.sourceName(race.source()), () -> setDestination(race.id())));
        }
        wizardPick(rows);
    }

    private void setDestination(String race) {
        if (!wizardRace.equals(race)) {
            wizardRace = race;
            wizardGroup = "";
            wizardSkill = defaultSkill(race);
        }
        endWizardPick();
    }

    private void chooseGroup() {
        var rows = new ArrayList<Row>();
        rows.add(new Row("Create a new group", "Choose the skill that unlocks its transformations", () -> {
            wizardGroup = ""; endWizardPick();
        }));
        for (var candidate : library.groups()) if (candidate.race().equals(wizardRace) && canExtend(candidate)) {
            rows.add(new Row(candidate.label(), EditorLibrary.sourceName(candidate.source()), () -> {
                wizardGroup = candidate.path(); endWizardPick();
            }));
        }
        wizardPick(rows);
    }

    List<String> skills(String race) {
        var values = new ArrayList<String>();
        catalog.getAsJsonArray(race.isEmpty() ? "stackSkills" : "formSkills").forEach(value -> values.add(value.getAsString()));
        return values;
    }

    String defaultSkill(String race) {
        var values = skills(race);
        String preferred = race.isEmpty() ? "kaioken" : "superforms";
        return values.contains(preferred) ? preferred : values.stream().findFirst().orElse("");
    }

    private void chooseSkill() {
        wizardPick(skills(wizardRace).stream().map(skill -> new Row(EditorPresentation.readable(skill), skill,
                () -> { wizardSkill = skill; endWizardPick(); })).toList());
    }

    private void wizardPick(List<Row> rows) {
        wizardChoices = rows;
        operation = "wizardPick";
        reviewOffset = 0;
        rebuild();
    }

    private void endWizardPick() {
        operation = wizardAction;
        rebuild();
    }

    static String suggestedId(String value) {
        String result = value.strip().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_").replaceAll("^[^a-z]+|_+$", "");
        return result.substring(0, Math.min(result.length(), 32));
    }

    private void reviewWizard() {
        if (stale) return;
        if (wizardAction.equals("revert")) {
            reviewOperation = "revert";
            EditorNetwork.request(3, wizardId);
        } else if (wizardAction.equals("name")) {
            submit("name", selected.path(), selected.id(), wizardName, wizardDescription, "", List.of());
        } else if (formWizard()) {
            int level;
            try {
                level = wizardLevel.isBlank() ? 0 : Integer.parseInt(wizardLevel);
                if (level < 0 || level > 128) throw new NumberFormatException();
            } catch (NumberFormatException invalid) {
                validation = "Enter an unlock level from 1 to 128 or leave it blank for the next level.";
                rebuild();
                return;
            }
            String prefix = wizardRace.isEmpty() ? "forms/" : "races/" + wizardRace + "/forms/";
            String path = wizardGroup.isEmpty() ? prefix + wizardNewGroup : wizardGroup;
            String original = wizardAction.equals("cloneForm") ? selected.key() : "";
            send(new EditorDraft.Proposal(revision, "form", path, wizardId, wizardName, original, wizardSkill, level, List.of()));
        }
    }

    private void navigate(Page page, String source, String group, Entry entry) {
        this.page = page;
        this.source = source;
        this.group = group;
        selected = entry;
        section = "Overview";
        query = "";
        note = "";
        browseOffset = fieldOffset = 0;
        field = null;
        loadPreview();
        rebuild();
    }

    private void back() {
        if (!query.isBlank()) { query = ""; browseOffset = 0; rebuild(); return; }
        switch (page) {
            case ENTRY -> {
                if (!selected.race()) navigate(Page.GROUP, source, selected.path(), null);
                else navigate(selected.disabled() ? Page.DELETED : Page.ADDON, selected.source(), "", null);
            }
            case GROUP -> {
                var current = library.group(group);
                var race = current == null ? null : library.race(current.race());
                if (race != null) openEntry(race);
                else navigate(Page.SHARED, source, "", null);
            }
            case SHARED -> navigate(Page.ADDON, source, "", null);
            default -> navigate(Page.LIBRARY, "", "", null);
        }
    }

    private void openEntry(Entry entry) {
        if (!entry.shared() && library.race(entry.raceId()) != null) {
            if (published == null) previousEntry = selected == null ? null : selected.key();
            published = new PublishedRacePage(this, catalog, library, entry);
            if (stale) published.markStale();
            preview = null;
            rebuild();
        } else {
            published = null;
            previousEntry = null;
            navigate(Page.ENTRY, entry.source(), entry.path(), entry);
        }
    }

    private List<Row> browseRows() {
        var rows = new ArrayList<Row>();
        if (!query.isBlank()) return library.search(query).stream()
                .map(entry -> entryRow(entry, library.location(entry))).toList();
        switch (page) {
            case LIBRARY -> {
                for (String owner : library.sources()) {
                    long races = library.races().stream().filter(race -> !race.disabled() && race.source().equals(owner)).count();
                    long shared = library.groups().stream().filter(group -> group.race().isEmpty() && group.source().equals(owner)).count();
                    rows.add(new Row(EditorLibrary.sourceName(owner), countLabel(races, "race") + " \u00b7 " + countLabel(shared, "shared group"),
                            () -> navigate(Page.ADDON, owner, "", null)));
                }
                long deleted = library.races().stream().filter(Entry::disabled).count();
                rows.add(new Row("Deleted Races", countLabel(deleted, "saved definition") + " available to restore",
                        () -> navigate(Page.DELETED, "", "", null)));
            }
            case ADDON -> {
                for (var race : library.races()) if (!race.disabled() && race.source().equals(source)) {
                    long count = library.groups().stream().filter(group -> group.race().equals(race.id())).count();
                    rows.add(entryRow(race, countLabel(count, "transformation group")));
                }
                for (var transformation : library.groups()) {
                    var race = library.race(transformation.race());
                    if (race != null && !race.source().equals(source) && transformation.source().equals(source)) {
                        rows.add(groupRow(transformation, race.label() + " \u00b7 " + EditorLibrary.sourceName(race.source())));
                    }
                }
                if (library.groups().stream().anyMatch(item -> item.race().isEmpty() && item.source().equals(source))) {
                    rows.add(new Row("Shared Transformations", "Forms that can be shared across races",
                            () -> navigate(Page.SHARED, source, "", null)));
                }
            }
            case SHARED -> library.groups().stream().filter(item -> item.race().isEmpty() && item.source().equals(source))
                    .forEach(item -> rows.add(groupRow(item, "Shared transformation group")));
            case GROUP -> library.forms(group).forEach(entry -> rows.add(entryRow(entry, entry.disabled() ? "Disabled" : "Transformation")));
            case DELETED -> library.races().stream().filter(Entry::disabled)
                    .forEach(entry -> rows.add(entryRow(entry, EditorLibrary.sourceName(entry.source()))));
            case ENTRY -> {
                if (selected.race() && section.equals("Overview")) library.groups().stream().filter(item -> item.race().equals(selected.id()))
                        .forEach(item -> rows.add(groupRow(item, EditorLibrary.sourceName(item.source()))));
            }
        }
        return rows;
    }

    private static String countLabel(long count, String singular) {
        return count + " " + singular + (count == 1 ? "" : "s");
    }

    private Row entryRow(Entry entry, String detail) {
        return new Row(entry.label(), detail, () -> openEntry(entry));
    }

    private Row groupRow(EditorLibrary.Group entry, String detail) {
        return new Row(entry.label(), detail, () -> navigate(Page.GROUP, entry.source(), entry.path(), null));
    }

    private void browseAssets() {
        operation = "assets";
        reviewOffset = 0;
        var rows = new ArrayList<Row>();
        if (assetCategory.equals("Skills") || assetCategory.equals("Abilities")) {
            var ids = new java.util.TreeSet<String>();
            for (String group : assetCategory.equals("Skills") ? List.of("skills", "formSkills", "stackSkills") : List.of("abilities")) {
                catalog.getAsJsonArray(group).forEach(value -> ids.add(value.getAsString()));
            }
            ids.forEach(id -> rows.add(new Row(EditorPresentation.readable(id), id, null)));
        } else if (minecraft != null) {
            boolean models = assetCategory.equals("Models");
            var resources = minecraft.getResourceManager();
            String folder = models ? "geo/entity/races" : "textures/entity/races";
            resources.listResources(folder, id -> id.getNamespace().equals("dragonminez")
                    && id.getPath().endsWith(models ? ".geo.json" : ".png")).keySet().stream().sorted().forEach(id -> {
                String path = id.getPath();
                String name = path.substring(path.lastIndexOf('/') + 1).replace(".geo.json", "").replace(".png", "");
                rows.add(new Row(EditorPresentation.readable(name), id.toString(), null));
            });
        }
        assetRows = rows;
        rebuild();
    }

    private void stage(String value) {
        var edit = new EditorDraft.FieldEdit(field.path(), field.keys(), field.value().getAsString(), value);
        draft.removeIf(prior -> prior.path().equals(edit.path()) && prior.field().equals(edit.field()));
        if (!edit.expected().equals(value)) draft.add(edit);
        note = "Staged " + field.label();
        if (selected.race() && field.key().startsWith("default")) loadPreview();
    }

    private void submit(String operation, String path, String id, String name, String description, String skill,
                        List<EditorDraft.FieldEdit> fields) {
        if (stale) return;
        send(new EditorDraft.Proposal(revision, operation, path, id, name, description, skill, 0, fields));
    }

    private void send(EditorDraft.Proposal proposal) {
        reviewOperation = proposal.operation();
        nextEntry = switch (reviewOperation) {
            case "race" -> "races/" + proposal.id() + "/character/" + proposal.id();
            case "form" -> proposal.target() + "/" + proposal.id();
            default -> null;
        };
        EditorNetwork.request(1, RevisionStore.GSON.toJson(proposal));
    }

    private List<EditorField> fields() {
        if (selected == null || section.equals("Overview")) return List.of();
        return EditorField.list(catalog, selected).stream().filter(item ->
                section.equals(EditorPresentation.section(item.path(), item.keys(), item.reason()))).toList();
    }

    private void pickValue() {
        var values = new ArrayList<String>();
        if (field.appearancePreset()) {
            values.addAll(appearanceValues());
        } else {
            values.add("");
        }
        if (field.key().equals("racialSkill")) {
            catalog.getAsJsonArray("abilities").forEach(value -> values.add(value.getAsString()));
        } else if (field.key().equals("customModel") && minecraft != null) {
            values.add("human");
            if (field.path().startsWith("races/")) {
                var character = ConfigManager.getRaceCharacter(field.path().split("/")[1]);
                var manager = minecraft.getResourceManager();
                String folder = "geo/entity/races/";
                manager.listResources("geo/entity/races",
                        id -> id.getNamespace().equals("dragonminez") && id.getPath().endsWith(".geo.json"))
                        .keySet().stream().sorted().forEach(id -> {
                            String model = id.getPath().substring(folder.length(), id.getPath().length() - ".geo.json".length());
                            if (character.getHasGender()) {
                                if (!model.endsWith("_male")) return;
                                model = model.substring(0, model.length() - "_male".length());
                                if (manager.getResource(ResourceLocation.fromNamespaceAndPath("dragonminez",
                                        folder + model + "_female.geo.json")).isEmpty()) return;
                            }
                            if (!values.contains(model)) values.add(model);
                        });
            }
        }
        choices = List.copyOf(values);
        reviewOffset = 0;
        operation = "pick";
        rebuild();
        if (choices.isEmpty()) note = "There are no loaded presets for this appearance.";
    }

    private List<String> appearanceValues() {
        return appearanceValues(selected.id(), ConfigManager.getRaceCharacter(selected.id()), field.key());
    }

    static List<String> appearanceValues(String race, RaceCharacterConfig config, String key) {
        String model = config.hasCustomModel() ? config.getCustomModel().toLowerCase(Locale.ROOT) : race;
        var character = new com.dragonminez.common.stats.character.Character();
        character.setRace(race);
        if (key.equals("defaultHairType")) {
            if (!HairManager.canUseHair(character)) return List.of();
            return HairPresets.ids().stream().sorted().map(String::valueOf).toList();
        }
        int min = 0;
        int max = switch (key) {
            case "defaultBodyType" -> TextureCounter.getMaxBodyTypes(model, "male");
            case "defaultEyesType" -> Math.max(1, TextureCounter.getMaxEyesTypes(model) + TextureCounter.getRaceEyeCount(race));
            case "defaultNoseType" -> Math.max(1, TextureCounter.getMaxNoseTypes(model));
            case "defaultMouthType" -> Math.max(1, TextureCounter.getMaxMouthTypes(model));
            case "defaultTattooType" -> Math.max(1, TextureCounter.getMaxTattooTypes(model));
            default -> throw new IllegalArgumentException("Unknown appearance field.");
        };
        if (key.equals("defaultBodyType")) {
            min = character.getMinBodyType();
            if (character.canHaveGender()) max = Math.min(max, TextureCounter.getMaxBodyTypes(model, "female"));
        }
        return java.util.stream.IntStream.rangeClosed(min, Math.min(max, AppearanceValidator.MAX_STYLE_INDEX))
                .mapToObj(Integer::toString).toList();
    }

    private String choiceLabel(String value) {
        if (!field.appearancePreset()) return value.isEmpty() ? "Use default / none" : value;
        return choiceLabel(field.key(), value);
    }

    static String choiceLabel(String key, String value) {
        return switch (key) {
            case "defaultBodyType" -> "Body variant " + value;
            case "defaultHairType" -> value.equals("0") ? "No hair" : "Hair preset " + value;
            case "defaultEyesType" -> "Eye style " + value;
            case "defaultNoseType" -> "Nose style " + value;
            case "defaultMouthType" -> "Mouth style " + value;
            case "defaultTattooType" -> value.equals("0") ? "No tattoo" : "Tattoo preset " + value;
            default -> value;
        };
    }

    private void renderPreview(GuiGraphics graphics, int x, int bottom) {
        if (preview == null) return;
        InventoryScreen.renderEntityInInventoryFollowsMouse(graphics, x, Math.min(205, bottom - 67), 37, 0, 0, preview);
    }

    private void loadPreview() {
        preview = null;
        if (selected == null || minecraft == null || minecraft.level == null) return;
        String race = selected.shared() ? "human" : selected.path().split("/")[1];
        var config = previewConfig(race);
        var entity = new RemotePlayer(minecraft.level, new GameProfile(UUID.randomUUID(), "DBZPreview"));
        StatsProvider.get(StatsCapability.INSTANCE, entity).ifPresent(stats -> {
            var character = stats.getCharacter();
            applyPreviewAppearance(character, race, config);
            stats.getStatus().setHasCreatedCharacter(true);
            if (!selected.race() && !selected.shared() && !selected.disabled()) character.setActiveForm(selected.path().split("/")[3], selected.id());
            if (selected.shared() && !selected.disabled()) character.setActiveStackForm(selected.path().split("/")[1], selected.id());
            preview = entity;
        });
    }

    static void applyPreviewAppearance(com.dragonminez.common.stats.character.Character character, String race,
                                       RaceCharacterConfig config) {
        character.setRace(race);
        character.setBodyType(config.getDefaultBodyType());
        character.setHairId(config.getDefaultHairType());
        character.setEyesType(config.getDefaultEyesType());
        character.setNoseType(config.getDefaultNoseType());
        character.setMouthType(config.getDefaultMouthType());
        character.setTattooType(config.getDefaultTattooType());
        character.setBodyColor(config.getDefaultBodyColor());
        character.setBodyColor2(config.getDefaultBodyColor2());
        character.setBodyColor3(config.getDefaultBodyColor3());
        character.setEye1Color(config.getDefaultEye1Color());
        character.setEye2Color(config.getDefaultEye2Color());
        character.setHairColor(config.getDefaultHairColor());
        character.setAuraColor(config.getDefaultAuraColor());
    }

    private RaceCharacterConfig previewConfig(String race) {
        var config = ConfigManager.getRaceCharacter(race);
        if (!selected.race()) return config;
        var document = selected.document().deepCopy();
        for (var edit : draft) {
            if (!edit.path().equals(selected.path()) || edit.field().size() != 1) continue;
            String key = edit.field().get(0);
            if (!key.startsWith("default")) continue;
            try {
                document.add(key, EditorFields.value(document.getAsJsonPrimitive(key), edit.value(), key));
            } catch (IllegalArgumentException | ArithmeticException invalid) {
                // The server reports the invalid value during review. Keep the loaded look in the preview.
            }
        }
        return RevisionStore.GSON.fromJson(document, RaceCharacterConfig.class);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
        if (showBuilder() || showPublished()) {
            if (showBuilder()) builder.background(delta);
            else published.background(delta);
            beginUiScale(graphics);
            if (showBuilder()) builder.render(graphics);
            else published.render(graphics);
            super.render(graphics, (int) toUiX(mouseX), (int) toUiY(mouseY), delta);
            endUiScale(graphics);
            return;
        }
        renderBackground(graphics);
        beginUiScale(graphics);
        int right = getUiWidth();
        int bottom = getUiHeight();
        int x = contentX();
        graphics.fill(4, 5, right - 4, bottom - 4, 0xF019202E);
        graphics.renderOutline(4, 5, right - 8, bottom - 9, 0xFF6087A1);
        text(graphics, "DMZ Do Anything", 10, 12, 180, 0xFF8FE6D5);
        text(graphics, "World revision " + revision, right - 115, 12, 108, 0xFFCDD9EA);
        graphics.fill(x - 4, 79, right - 8, bottom - 70, 0xFF101724);
        if (x > 20) graphics.fill(7, 79, LEFT - 4, bottom - 70, 0xFF111724);

        if (help != null) {
            heading(graphics, help.title(), x, right);
            drawTextLines(graphics, List.of(help.text()), x, right - x - 18, bottom);
        } else if (validation != null) {
            heading(graphics, "Review Changes \u00b7 Action Required", x, right);
            drawTextLines(graphics, List.of(validation), x, right - x - 18, bottom);
        } else if (token != null) {
            heading(graphics, technicalReview ? "Review Changes \u00b7 Technical Details" : "Review Changes", x, right);
            drawTextLines(graphics, reviewText(), x, right - x - 18, bottom);
        } else if (operation.equals("assets")) {
            heading(graphics, "Assets \u203a " + assetCategory, x, right);
            drawRows(graphics, assetRows, reviewOffset, FIRST_ROW, x, right - x - 16, rowLimit());
        } else if (operation.equals("wizardPick")) {
            heading(graphics, "Choose a destination or unlock skill", x, right);
            drawRows(graphics, wizardChoices, reviewOffset, FIRST_ROW, x, right - x - 16, rowLimit());
        } else if (operation.equals("pick")) {
            heading(graphics, "Choose " + field.label(), x, right);
            drawRows(graphics, choices.stream().map(value -> new Row(choiceLabel(value), "", null)).toList(),
                    reviewOffset, FIRST_ROW, x, right - x - 16, rowLimit());
        } else if (!operation.isEmpty()) {
            renderWizard(graphics, right);
        } else if (page == Page.SPECIAL) {
            heading(graphics, "Special Settings", x, right);
            text(graphics, "World-wide rules. Review staged changes to apply them live.", x, 104, right - x - 18, 0xFFB1C7DB);
            text(graphics, "Progression", x, 130, 82, 0xFF8FE6D5);
            text(graphics, "Ki", x, 249, 82, 0xFF8FE6D5);
        } else {
            heading(graphics, heading(), x, right);
            if (selected == null || !query.isBlank() || section.equals("Overview")) {
                if (selected != null && query.isBlank()) {
                    text(graphics, selected.disabled() ? (selected.race() ? "Deleted \u00b7 existing characters keep this race" : "Disabled")
                            : "Available", x, 104, right - x - 102, selected.disabled() ? 0xFFFFCF87 : 0xFF8FE6D5);
                    text(graphics, "Source: " + EditorLibrary.sourceName(selected.source()), x, 122, right - x - 105, 0xFFB1C7DB);
                    if (selected.race()) {
                        text(graphics, "Transformation Groups", x, 145, right - x - 110, 0xFF8FE6D5);
                    } else {
                        var definition = library.group(selected.path());
                        if (definition != null) text(graphics, "Unlock skill: " + EditorPresentation.readable(
                                selected.document().get("formType").getAsString()), x, 143, right - x - 100, 0xFFB1C7DB);
                    }
                    renderPreview(graphics, right - 57, bottom);
                }
                int margin = selected != null && query.isBlank() ? 106 : 0;
                var rows = browseRows();
                if (rows.isEmpty() && (selected == null || selected.race() || !query.isBlank())) {
                    text(graphics, "No entries to show.", x, browseStart(), right - x - 16, 0xFFB1C7DB);
                }
                drawRows(graphics, rows, browseOffset, browseStart(), x, right - x - 16 - margin, rowLimit());
            } else {
                int margin = section.equals("Appearance") && preview != null ? 96 : 0;
                var rows = fields().stream().map(item -> {
                    String staged = draft.stream().filter(edit -> edit.path().equals(item.path()) && edit.field().equals(item.keys()))
                            .map(EditorDraft.FieldEdit::value).findFirst().orElse(null);
                    String value = staged == null ? item.value().getAsString() : staged + " (staged)";
                    String detail = section.equals("Advanced") ? String.join(".", item.keys())
                    : item.reason() != null ? "Read-only"
                            : EditorPresentation.newCharacters(item.keys()) ? "Applies to new characters" : "Applies live";
                    return new Row(item.label() + "  \u00b7  " + (value.isEmpty() ? "Default" : value), detail, null);
                }).toList();
                if (section.equals("Advanced")) {
                    text(graphics, "Stable ID: " + selected.id(), x, 102, right - x - 16, 0xFFB1C7DB);
                    text(graphics, selected.path(), x, 118, right - x - 16, 0xFFB1C7DB);
                }
                drawRows(graphics, rows, fieldOffset, fieldStart(), x, right - x - 34 - margin, rowLimit());
                if (margin > 0) renderPreview(graphics, right - 55, bottom);
            }
        }
        if (!note.isBlank() && help == null && token == null && validation == null && operation.isEmpty()) {
            int statusY = selected != null && query.isBlank() && section.equals("Overview") ? bottom - 45 : bottom - 66;
            text(graphics, note, x, statusY, right - x - 17, 0xFFFFCF87);
        }
        super.render(graphics, (int) toUiX(mouseX), (int) toUiY(mouseY), delta);
        endUiScale(graphics);
    }

    private int contentX() {
        return help != null || token != null || validation != null || selected == null || operation.equals("assets")
                || operation.equals("wizardPick") ? 18 : LEFT + 10;
    }

    private String heading() {
        if (!query.isBlank()) return "Search Results";
        return switch (page) {
            case LIBRARY -> "Addon Library";
            case ADDON -> EditorLibrary.sourceName(source);
            case DELETED -> "Deleted Races";
            case SHARED -> EditorLibrary.sourceName(source) + " \u203a Shared Transformations";
            case GROUP -> library.group(group) == null ? "Transformation Group" : library.group(group).label();
            case ENTRY -> selected.label() + " \u203a " + section;
            case SPECIAL -> "Special Settings";
        };
    }

    private void renderWizard(GuiGraphics graphics, int right) {
        String title = switch (wizardAction) {
            case "form" -> "New Transformation";
            case "cloneForm" -> "Clone Transformation \u00b7 " + selected.label();
            case "name" -> "Change Display Name";
            default -> "Restore a World Revision";
        };
        heading(graphics, title, LEFT + 12, right);
        int x = LEFT + 12;
        if (wizardAction.equals("revert")) return;
        if (!wizardAction.equals("name")) {
            text(graphics, "Display Name", x, 94, right - x - 16, 0xFFB1C7DB);
            text(graphics, "Stable ID", x, 130, right - x - 16, 0xFFB1C7DB);
        }
    }

    private List<String> reviewText() {
        var lines = new ArrayList<String>();
        if (technicalReview) {
            for (var row : review) lines.add(row.kind() + "  " + row.path() + (row.keys().isEmpty() ? "" : "." + String.join(".", row.keys()))
                    + "  " + value(row.before()) + " \u2192 " + value(row.after()) + " " + row.detail());
            return lines;
        }
        for (var row : review) if (row.kind().equals("player")) lines.add("Affected player \u00b7 " + row.detail());
        var builderSummary = review.stream().filter(row -> row.kind().equals("builder")).toList();
        if (!builderSummary.isEmpty()) {
            builderSummary.forEach(row -> lines.add(row.detail()));
            return lines;
        }
        var grouped = new java.util.LinkedHashMap<String, List<String>>();
        for (var row : review) {
            if (row.kind().equals("player")) continue;
            var keys = row.keys();
            String path = row.path();
            String title = reviewTitle(row);
            String line = switch (row.kind()) {
                case "specialRule" -> SpecialRule.fromId(keys.get(0)).label + " \u00b7 "
                        + SpecialRule.state(row.before().getAsBoolean()) + " \u2192 " + SpecialRule.state(row.after().getAsBoolean());
                case "deleteRace" -> "Delete " + title + " from new character creation. Existing characters keep their race, appearance, forms, and progression.";
                case "restoreRace" -> "Restore " + title + " to new character creation using the same race ID and settings.";
                case "disableForm" -> "Available \u2192 Disabled";
                case "enableForm" -> "Disabled \u2192 Available";
                case "add" -> keys.isEmpty() ? "Add definition" : "Add " + EditorPresentation.fieldLabel(keys)
                        + (row.after() == null ? "" : " \u00b7 " + value(row.after()));
                case "remove" -> keys.isEmpty() ? "Remove definition" : "Remove " + EditorPresentation.fieldLabel(keys)
                        + (row.before() == null ? "" : " \u00b7 " + value(row.before()));
                case "name" -> "Display name \u00b7 " + value(row.before()) + " \u2192 " + value(row.after());
                case "sourceName" -> "Restore the source display name";
                default -> (keys.isEmpty() ? title : EditorPresentation.fieldLabel(keys)) + " \u00b7 " + value(row.before()) + " \u2192 " + value(row.after());
            };
            grouped.computeIfAbsent(title, ignored -> new ArrayList<>()).add(line);
        }
        grouped.forEach((title, changes) -> { lines.add(title); changes.forEach(line -> lines.add("  " + line)); });
        return lines;
    }

    private String reviewTitle(WorldEditor.ReviewRow row) {
        if (row.kind().equals("specialRule")) return "Special Settings";
        String path = row.path();
        var keys = row.keys();
        if (reviewOperation.equals("publishedEdit") && published != null
                && path.startsWith("races/" + published.raceId() + "/")
                && (path.endsWith("/character") || path.endsWith("/stats"))) return "Base";
        if (row.kind().endsWith("Race")) {
            var race = library.race(path);
            return race == null ? EditorPresentation.readable(path) : race.label() + " \u00b7 " + EditorLibrary.sourceName(race.source());
        }
        if (row.kind().endsWith("Form")) {
            var form = library.find(path);
            int split = path.lastIndexOf('/');
            return form != null ? form.label() + " \u00b7 " + library.location(form)
                    : EditorLibrary.name(path.substring(0, split), path.substring(split + 1));
        }
        if (keys.size() >= 2 && keys.get(0).equals("forms")) {
            var form = library.find(path + "/" + keys.get(1));
            return form != null ? form.label() + " \u00b7 " + library.location(form) : EditorLibrary.name(path, keys.get(1));
        }
        if (path.startsWith("races/")) {
            String race = path.split("/")[1];
            var entry = library.race(race);
            return entry != null ? entry.label() + " \u00b7 " + EditorLibrary.sourceName(entry.source()) : EditorLibrary.name("races/" + race + "/character", race);
        }
        if (path.startsWith("forms/")) return EditorPresentation.readable(path.substring("forms/".length()));
        return path.startsWith("race.dragonminez.") ? Component.translatableWithFallback(path, "Display Text").getString() : "World Changes";
    }

    private static String value(JsonElement element) {
        if (element == null || element.isJsonNull()) return "Default";
        String value = element.isJsonPrimitive() ? element.getAsString() : element.toString();
        return value.isEmpty() ? "Default" : value;
    }

    private void drawTextLines(GuiGraphics graphics, List<String> lines, int x, int width, int bottom) {
        var wrapped = wrap(lines, width);
        int visible = Math.max(1, (bottom - FIRST_ROW - 50) / 14);
        for (int i = 0; i < visible && reviewOffset + i < wrapped.size(); i++) {
            graphics.drawString(font, wrapped.get(reviewOffset + i), x, FIRST_ROW + i * 14, 0xFFE6E4D8, true);
        }
    }

    private List<net.minecraft.util.FormattedCharSequence> wrap(List<String> lines, int width) {
        var wrapped = new ArrayList<net.minecraft.util.FormattedCharSequence>();
        for (String line : lines) wrapped.addAll(font.split(txt(line), width));
        return wrapped;
    }

    private void drawRows(GuiGraphics graphics, List<Row> rows, int offset, int start, int x, int width, int end) {
        for (int i = offset, y = start; i < rows.size() && y + ROW <= end; i++, y += ROW) {
            Row row = rows.get(i);
            graphics.fill(x - 3, y - 1, x + width + 2, y + ROW - 3, (i & 1) == 0 ? 0xFF1B2637 : 0xFF172131);
            text(graphics, row.title(), x, y + 1, width, 0xFFE6E4D8);
            if (!row.detail().isEmpty()) text(graphics, row.detail(), x, y + 11, width, 0xFF9FB8D0);
        }
    }

    private void heading(GuiGraphics graphics, String title, int x, int right) {
        text(graphics, title, x, 84, right - x - 20, 0xFF8FE6D5);
    }

    void text(GuiGraphics graphics, String value, int x, int y, int size, int color) {
        graphics.drawString(font, txt(font.plainSubstrByWidth(value, Math.max(0, size))), x, y, color);
    }

    private int browseStart() { return selected != null && selected.race() && query.isBlank() ? 160 : FIRST_ROW; }
    private int fieldStart() { return section.equals("Advanced") ? 133 : FIRST_ROW; }
    private int rowLimit() { return getUiHeight() - (operation.isEmpty() && selected != null && section.equals("Overview") && query.isBlank() ? 87 : 72); }

    private int rowAt(double y, int start, int offset) {
        double position = toUiY(y);
        return position >= start && position < rowLimit() ? offset + (int) ((position - start) / ROW) : -1;
    }

    @Override
    public boolean mouseClicked(double x, double y, int button) {
        if (super.mouseClicked(x, y, button)) {
            // A click can replace its own button. Restore focus to the input created by the new view.
            if ((showBuilder() || showPublished()) && !children().contains(getFocused())) setFocused(builderFocus);
            return true;
        }
        if (showBuilder() || showPublished()) return false;
        if (button != 0 || help != null || validation != null || token != null || toUiX(x) < contentX()) return false;
        if (operation.equals("pick")) {
            int index = rowAt(y, FIRST_ROW, reviewOffset);
            if (index >= 0 && index < choices.size()) {
                operation = "";
                stage(choices.get(index));
                rebuild();
                return true;
            }
        } else if (operation.equals("wizardPick")) {
            int index = rowAt(y, FIRST_ROW, reviewOffset);
            if (index >= 0 && index < wizardChoices.size()) { wizardChoices.get(index).select().run(); return true; }
        } else if (operation.isEmpty()) {
            if (selected == null || !query.isBlank() || section.equals("Overview")) {
                var rows = browseRows();
                int index = rowAt(y, browseStart(), browseOffset);
                if (index >= 0 && index < rows.size()) { rows.get(index).select().run(); return true; }
            } else {
                var fields = fields();
                int index = rowAt(y, fieldStart(), fieldOffset);
                if (index >= 0 && index < fields.size()) {
                    field = fields.get(index);
                    note = field.reason() != null ? "Read-only. Select ? for the explanation." : EditorPresentation.newCharacters(field.keys())
                            ? "Applies to new characters." : "Applies live.";
                    rebuild();
                    return true;
                }
            }
        }
        return false;
    }

    @Override
    public boolean mouseScrolled(double x, double y, double amount) {
        int delta = amount > 0 ? -3 : 3;
        if (showBuilder()) return builder.scroll(toUiX(x), toUiY(y), amount);
        if (showPublished()) return published.scroll(delta);
        if (help != null || validation != null || token != null) {
            var lines = help != null ? List.of(help.text()) : validation != null ? List.of(validation) : reviewText();
            int visible = Math.max(1, (getUiHeight() - FIRST_ROW - 50) / 14);
            reviewOffset = offset(reviewOffset, delta, wrap(lines, getUiWidth() - contentX() - 18).size(), visible);
        } else if (operation.equals("assets") || operation.equals("pick") || operation.equals("wizardPick")) {
            int total = operation.equals("assets") ? assetRows.size() : operation.equals("pick") ? choices.size() : wizardChoices.size();
            reviewOffset = offset(reviewOffset, delta, total, Math.max(1, (rowLimit() - FIRST_ROW) / ROW));
        } else if (operation.isEmpty()) {
            if (selected == null || !query.isBlank() || section.equals("Overview")) {
                browseOffset = offset(browseOffset, delta, browseRows().size(), Math.max(1, (rowLimit() - browseStart()) / ROW));
            } else {
                fieldOffset = offset(fieldOffset, delta, fields().size(), Math.max(1, (rowLimit() - fieldStart()) / ROW));
                rebuild();
            }
        }
        return true;
    }

    private static int offset(int current, int delta, int total, int visible) {
        return Math.min(Math.max(0, total - visible), Math.max(0, current + delta));
    }

    @Override
    public void onClose() {
        if (publishedReviewTicks > 0 || applying) return;
        if (help != null || validation != null || token != null || !operation.isEmpty()) {
            if (help != null) help = null;
            else if (validation != null || token != null) { closeReview(); return; }
            else if (operation.equals("wizardPick")) operation = wizardAction;
            else operation = "";
            rebuild();
        } else if (builder != null) builder.back();
        else if (published != null) published.back();
        else if (page != Page.LIBRARY || !query.isBlank()) back();
        else super.onClose();
    }

    @Override
    public boolean keyPressed(int key, int scanCode, int modifiers) {
        if (key == org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER || key == org.lwjgl.glfw.GLFW.GLFW_KEY_KP_ENTER) {
            if (showBuilder() && builder.enter()) return true;
            if (showPublished() && published.enter()) return true;
        }
        return super.keyPressed(key, scanCode, modifiers);
    }

    private boolean showBuilder() {
        return builder != null && help == null && validation == null && token == null;
    }

    private boolean showPublished() {
        return published != null && builder == null && operation.isEmpty()
                && help == null && validation == null && token == null;
    }

    private void closeReview() {
        if (applying) return;
        validation = null;
        token = null;
        rebuild();
    }

    @Override
    public void tick() {
        super.tick();
        if (publishedReviewTicks > 0 && --publishedReviewTicks == 0) {
            validation = "The server did not return a review. Refresh the catalog and try again.";
            stale = true;
            if (published != null) published.markStale();
            rebuild();
        }
    }

    void closePublished() {
        published = null;
        selected = previousEntry == null ? null : library.find(previousEntry);
        previousEntry = null;
        if (page == Page.ENTRY && selected == null) page = Page.LIBRARY;
        loadPreview();
        rebuild();
    }

    void refreshPublishedCatalog() {
        EditorNetwork.request(0, "");
    }

    void showPublishedStale() {
        stale = true;
        validation = "Refresh the catalog before reviewing. Your staged edits will stay open.";
        rebuild();
    }

    void reviewPublished(List<EditorDraft.FieldEdit> edits) {
        if (stale) {
            showPublishedStale();
            return;
        }
        if (edits.size() > 100) {
            validation = "Review up to 100 attribute edits at a time.";
            rebuild();
            return;
        }
        var proposal = new EditorDraft.Proposal(revision, "edit", "", "", "", "", "", 0, edits);
        String payload = RevisionStore.GSON.toJson(proposal);
        if (payload.length() > EditorNetwork.REQUEST_LIMIT) {
            validation = "The draft exceeds the server request limit. Reduce staged edits and review again.";
            rebuild();
            return;
        }
        reviewOperation = "publishedEdit";
        nextEntry = null;
        publishedReviewTicks = 200;
        validation = "Waiting for the server to review staged attributes.";
        reviewOffset = 0;
        EditorNetwork.request(1, payload);
        rebuild();
    }

    void managePublished(String key, String action) {
        if (stale) { showPublishedStale(); return; }
        var entry = library.find(key);
        if (entry == null) {
            validation = "That entry is no longer available.";
            rebuild();
            return;
        }
        selected = entry;
        if (List.of("deleteRace", "restoreRace", "enable", "disable").contains(action)) {
            submit(action, entry.path(), entry.id(), "", "", "", List.of());
        } else startWizard(action);
    }

    void openEditorHelp(String title, String explanation) {
        help = new Help(title, explanation);
        reviewOffset = 0;
        rebuild();
    }

    long builderRevision() { return revision; }

    net.minecraft.client.gui.Font builderFont() { return font; }

    void focusBuilder(net.minecraft.client.gui.components.events.GuiEventListener input) {
        builderFocus = input;
        setFocused(input);
    }

    net.minecraft.client.gui.components.MultiLineEditBox descriptionBox(int x, int y, int width, int height, String text,
                                                                          Consumer<String> update) {
        var input = addRenderableWidget(new net.minecraft.client.gui.components.MultiLineEditBox(font, x, y, width, height,
                txt("Describe this race"), txt("Race description")));
        input.setCharacterLimit(512);
        input.setValue(text);
        input.setValueListener(update);
        focusBuilder(input);
        return input;
    }

    void addBuilderSlider(com.dragonminez.client.gui.buttons.ColorSlider slider) {
        addRenderableWidget(slider);
    }

    List<String> modelOptions() { return modelOptions(true); }

    List<String> modelOptions(boolean gendered) {
        if (minecraft == null) return List.of("human");
        var manager = minecraft.getResourceManager();
        String folder = "geo/entity/races/";
        var models = new ArrayList<String>();
        models.add("human");
        String suffix = gendered ? "_male.geo.json" : ".geo.json";
        manager.listResources("geo/entity/races", id -> id.getNamespace().equals("dragonminez")
                && id.getPath().endsWith(suffix)).keySet().stream().sorted().forEach(id -> {
            String model = id.getPath().substring(folder.length(), id.getPath().length() - suffix.length());
            if (!model.matches("[a-z0-9_]{1,64}") || models.contains(model)) return;
            if (!gendered || manager.getResource(ResourceLocation.fromNamespaceAndPath("dragonminez",
                    folder + model + "_female.geo.json")).isPresent()) models.add(model);
        });
        return models;
    }

    void closeBuilder() {
        builder = null;
        rebuild();
    }

    void reviewBuilder(RaceBuilderDraft.Proposal proposal) {
        if (stale) {
            validation = "Refresh the catalog before reviewing. Your draft will stay open.";
            rebuild();
            return;
        }
        String payload = RevisionStore.GSON.toJson(proposal);
        if (payload.length() > EditorNetwork.REQUEST_LIMIT) {
            throw new IllegalArgumentException("This draft exceeds the server request limit. Reduce staged fields or forms.");
        }
        reviewOperation = "raceBuilder";
        nextEntry = "races/" + proposal.id() + "/character/" + proposal.id();
        EditorNetwork.request(EditorNetwork.REVIEW_RACE_BUILDER, payload);
    }

    void rebuild() {
        builderFocus = null;
        setFocused(null);
        clearWidgets();
        init();
    }

    EditBox box(int x, int y, int width, String hint, String value, int max) {
        var box = new EditBox(font, x, y, width, 18, Component.literal(hint));
        box.setHint(Component.literal(hint));
        box.setMaxLength(max);
        box.setValue(value);
        return addRenderableWidget(box);
    }

    private void watch(EditBox box, Consumer<String> responder) { box.setResponder(responder); }

    private void action(int x, int y, int width, String text, String help, Runnable action) {
        button(x, y, width, text, action);
        helpButton(x + width + 2, y, text, help);
    }

    void helpButton(int x, int y, String title, String explanation) {
        var button = button(x, y, 18, "?", () -> openEditorHelp(title, explanation));
        int period = explanation.indexOf(". ");
        String shortText = period < 0 ? explanation : explanation.substring(0, period + 1);
        button.setTooltip(Tooltip.create(Component.literal(shortText)));
    }

    Button button(int x, int y, int width, String text, Runnable action) {
        return addRenderableWidget(new LibraryButton(x, y, width, text, action));
    }

    private final class LibraryButton extends Button {
        LibraryButton(int x, int y, int width, String label, Runnable action) {
            super(x, y, width, 20, txt(label), ignored -> action.run(), DEFAULT_NARRATION);
        }

        @Override
        protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
            int x = getX();
            int y = getY();
            int width = getWidth();
            int textureY = active && isHoveredOrFocused() ? 48 : 28;
            graphics.blit(BUTTONS, x, y, 4, 20, 0, textureY, 4, 20, 256, 256);
            graphics.blit(BUTTONS, x + 4, y, width - 8, 20, 4, textureY, 66, 20, 256, 256);
            graphics.blit(BUTTONS, x + width - 4, y, 4, 20, 70, textureY, 4, 20, 256, 256);
            String label = font.plainSubstrByWidth(getMessage().getString(), width - 6);
            graphics.drawCenteredString(font, txt(label), x + width / 2, y + 6, active ? 0xFFFFFF : 0xA0A0A0);
        }
    }

    @Override public boolean isPauseScreen() { return false; }
}
