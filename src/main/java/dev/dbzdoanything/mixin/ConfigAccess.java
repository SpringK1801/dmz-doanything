package dev.dbzdoanything.mixin;

import com.dragonminez.common.config.ConfigManager;
import com.dragonminez.common.config.FormConfig;
import com.dragonminez.common.config.RaceCharacterConfig;
import com.dragonminez.common.config.RaceStatsConfig;
import com.google.gson.Gson;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

import java.util.List;
import java.util.Map;

@Mixin(value = ConfigManager.class, remap = false)
public interface ConfigAccess {
    @Accessor("GSON")
    static Gson editorGson() { throw new AssertionError(); }

    @Accessor("RACE_CHARACTER")
    static Map<String, RaceCharacterConfig> editorCharacters() { throw new AssertionError(); }

    @Accessor("RACE_STATS")
    static Map<String, RaceStatsConfig> editorStats() { throw new AssertionError(); }

    @Accessor("RACE_FORMS")
    static Map<String, Map<String, FormConfig>> editorForms() { throw new AssertionError(); }

    @Accessor("STACK_FORMS")
    static Map<String, FormConfig> editorStackForms() { throw new AssertionError(); }

    @Accessor("LOADED_RACES")
    static List<String> editorRaces() { throw new AssertionError(); }

    @Invoker("invalidateSyncPayloads")
    static void editorInvalidate() { throw new AssertionError(); }
}
