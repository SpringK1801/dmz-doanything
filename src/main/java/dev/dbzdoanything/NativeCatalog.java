package dev.dbzdoanything;

import com.dragonminez.common.config.FormConfig;
import com.dragonminez.common.config.RaceCharacterConfig;
import com.dragonminez.common.config.RaceStatsConfig;
import com.google.gson.JsonObject;
import dev.dbzdoanything.mixin.ConfigAccess;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class NativeCatalog {
    record State(Map<String, RaceCharacterConfig> characters, Map<String, RaceStatsConfig> stats,
                 Map<String, Map<String, FormConfig>> forms, Map<String, FormConfig> stacks, List<String> races) {
        static State capture() {
            var groups = new LinkedHashMap<String, Map<String, FormConfig>>();
            ConfigAccess.editorForms().forEach((id, forms) -> groups.put(id, new LinkedHashMap<>(forms)));
            return new State(new LinkedHashMap<>(ConfigAccess.editorCharacters()),
                    new LinkedHashMap<>(ConfigAccess.editorStats()), groups,
                    new LinkedHashMap<>(ConfigAccess.editorStackForms()), new ArrayList<>(ConfigAccess.editorRaces()));
        }

        State copy() {
            var groups = new LinkedHashMap<String, Map<String, FormConfig>>();
            forms.forEach((race, values) -> groups.put(race, new LinkedHashMap<>(values)));
            return new State(new LinkedHashMap<>(characters), new LinkedHashMap<>(stats), groups,
                    new LinkedHashMap<>(stacks), new ArrayList<>(races));
        }

        Map<String, JsonObject> documents() {
            var gson = ConfigAccess.editorGson();
            var result = new LinkedHashMap<String, JsonObject>();
            characters.forEach((id, value) -> result.put("races/" + id + "/character", gson.toJsonTree(value).getAsJsonObject()));
            stats.forEach((id, value) -> result.put("races/" + id + "/stats", gson.toJsonTree(value).getAsJsonObject()));
            forms.forEach((race, groups) -> groups.forEach((group, value) ->
                    result.put("races/" + race + "/forms/" + group, gson.toJsonTree(value).getAsJsonObject())));
            stacks.forEach((group, value) -> result.put("forms/" + group, gson.toJsonTree(value).getAsJsonObject()));
            return result;
        }

        void publish() {
            replace(ConfigAccess.editorCharacters(), characters);
            replace(ConfigAccess.editorStats(), stats);
            replace(ConfigAccess.editorForms(), forms);
            replace(ConfigAccess.editorStackForms(), stacks);
            ConfigAccess.editorRaces().clear();
            ConfigAccess.editorRaces().addAll(races);
            ConfigAccess.editorInvalidate();
        }

        void update(String path, JsonObject document) {
            String[] parts = path.split("/", -1);
            var gson = ConfigAccess.editorGson();
            if (parts.length == 2 && parts[0].equals("forms")) {
                stacks.put(parts[1], gson.fromJson(document, FormConfig.class));
                return;
            }
            String race = parts[1];
            switch (parts[2]) {
                case "character" -> characters.put(race, gson.fromJson(document, RaceCharacterConfig.class));
                case "stats" -> stats.put(race, gson.fromJson(document, RaceStatsConfig.class));
                case "forms" -> forms.computeIfAbsent(race, ignored -> new LinkedHashMap<>())
                        .put(parts[3], gson.fromJson(document, FormConfig.class));
                default -> throw new IllegalArgumentException("Unsupported catalog path " + path);
            }
            if (!races.contains(race)) races.add(race);
        }
    }

    private NativeCatalog() {}

    private static <T> void replace(Map<String, T> target, Map<String, T> source) {
        target.clear();
        target.putAll(source);
    }
}
