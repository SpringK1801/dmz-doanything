package dev.dbzdoanything;

import com.google.gson.JsonObject;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;

final class EditorLibrary {
    record Entry(String path, String id, String label, String source, JsonObject document, boolean disabled) {
        boolean race() { return path.endsWith("/character"); }
        boolean shared() { return path.startsWith("forms/"); }
        String raceId() { return shared() ? "" : path.split("/")[1]; }
        String key() { return path + "/" + id; }
    }

    record Group(String path, String label, String source, String race) {}

    private static final Comparator<Entry> BY_NAME = Comparator.comparing(Entry::label, String.CASE_INSENSITIVE_ORDER)
            .thenComparing(Entry::key);
    private final List<Entry> entries;
    private final List<Group> groups;

    EditorLibrary(JsonObject catalog) {
        var entries = new ArrayList<Entry>();
        var groups = new ArrayList<Group>();
        var sources = catalog.getAsJsonObject("sources");
        for (Map.Entry<String, com.google.gson.JsonElement> item : catalog.getAsJsonObject("documents").entrySet()) {
            String path = item.getKey();
            String source = sources.get(path).getAsString();
            var document = item.getValue().getAsJsonObject();
            if (path.endsWith("/character")) {
                String id = path.split("/")[1];
                entries.add(new Entry(path, id, name(path, id), source, document, contains(catalog, "retired", id)));
            } else if (document.has("forms")) {
                String[] parts = path.split("/");
                boolean shared = parts[0].equals("forms");
                String id = parts[parts.length - 1];
                String translation = shared ? "race.dragonminez.stack.group." + id : "race.dragonminez." + parts[1] + ".group." + id;
                groups.add(new Group(path, translated(translation, id), source, shared ? "" : parts[1]));
                for (String form : document.getAsJsonObject("forms").keySet()) {
                    entries.add(new Entry(path, form, name(path, form), source, document,
                            contains(catalog, "disabled", path + "/" + form)));
                }
            }
        }
        this.entries = entries.stream().sorted(BY_NAME).toList();
        this.groups = groups.stream().sorted(Comparator.comparing(Group::label, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(Group::path)).toList();
    }

    List<String> sources() {
        var available = new TreeSet<String>(String.CASE_INSENSITIVE_ORDER);
        entries.stream().filter(entry -> !entry.race() || !entry.disabled()).map(Entry::source).forEach(available::add);
        groups.stream().map(Group::source).forEach(available::add);
        var ordered = new ArrayList<String>();
        for (String name : List.of("Dragon Mine Z", "RacePlus", "DMZUltra", "This world", "Unverified source")) {
            if (available.remove(name)) ordered.add(name);
        }
        ordered.addAll(available);
        return ordered;
    }

    List<Entry> races() { return entries.stream().filter(Entry::race).toList(); }
    List<Entry> forms(String group) { return entries.stream().filter(entry -> entry.path().equals(group)).toList(); }
    List<Group> groups() { return groups; }
    Group group(String path) { return groups.stream().filter(group -> group.path().equals(path)).findFirst().orElse(null); }
    Entry find(String key) { return entries.stream().filter(entry -> entry.key().equals(key)).findFirst().orElse(null); }
    Entry race(String id) { return find("races/" + id + "/character/" + id); }

    List<Entry> search(String query) {
        String[] words = query.strip().toLowerCase(Locale.ROOT).split("\\s+");
        return entries.stream().filter(entry -> {
            String haystack = (entry.label() + " " + entry.key() + " " + location(entry)).toLowerCase(Locale.ROOT);
            for (String word : words) if (!haystack.contains(word)) return false;
            return true;
        }).toList();
    }

    String location(Entry entry) {
        if (entry.race()) return entry.disabled() ? "Deleted Races \u203a " + sourceName(entry.source()) : sourceName(entry.source());
        Group group = group(entry.path());
        if (entry.shared()) return sourceName(entry.source()) + " \u203a Shared Transformations \u203a " + group.label();
        Entry race = race(entry.raceId());
        return sourceName(race == null ? entry.source() : race.source()) + " \u203a "
                + (race == null ? EditorPresentation.readable(entry.raceId()) : race.label()) + " \u203a " + group.label()
                + (race != null && !race.source().equals(entry.source()) ? " \u00b7 " + sourceName(entry.source()) : "");
    }

    static String name(String path, String id) { return translated(EditorDraft.translation(path, id), id); }

    static String sourceName(String source) {
        return switch (source) {
            case "This world" -> "This World";
            case "Unverified source" -> "Unverified Sources";
            default -> source;
        };
    }

    private static String translated(String key, String id) {
        String name = Component.translatableWithFallback(key, id).getString();
        return name.equals(id) || name.equals(key) ? EditorPresentation.readable(id) : name;
    }

    private static boolean contains(JsonObject catalog, String key, String value) {
        for (var entry : catalog.getAsJsonArray(key)) if (entry.getAsString().equals(value)) return true;
        return false;
    }
}
