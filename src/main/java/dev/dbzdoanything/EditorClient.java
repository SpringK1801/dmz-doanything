package dev.dbzdoanything;

import com.dragonminez.client.gui.character.CharacterCustomizationScreen;
import com.dragonminez.client.gui.character.RaceSelectionScreen;
import com.dragonminez.client.gui.character.SkillsMenuScreen;
import com.dragonminez.common.stats.StatsCapability;
import com.dragonminez.common.stats.StatsProvider;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

@Mod.EventBusSubscriber(modid = EditorMod.ID, value = Dist.CLIENT)
public final class EditorClient {
    private static final Map<Integer, Transfer> TRANSFERS = new HashMap<>();
    private static Map<String, String> names = Map.of();
    private static Set<String> retired = Set.of();
    private static Set<String> overrides = Set.of();
    private static long revision = -1;

    private record Transfer(long revision, int count, StringBuilder data, int next) {}

    private EditorClient() {}

    static void receive(EditorNetwork.Update update) {
        if (update.count() < 1 || update.count() > EditorNetwork.MAX_CHUNKS || update.kind() < 0 || update.kind() > 2) return;
        if (update.index() == 0) TRANSFERS.put(update.kind(), new Transfer(update.revision(), update.count(), new StringBuilder(), 0));
        var transfer = TRANSFERS.get(update.kind());
        if (transfer == null || transfer.revision() != update.revision() || transfer.count() != update.count()
                || transfer.next() != update.index()) return;
        transfer.data().append(update.text());
        if (update.index() + 1 < update.count()) {
            TRANSFERS.put(update.kind(), new Transfer(transfer.revision(), transfer.count(), transfer.data(), transfer.next() + 1));
            return;
        }
        TRANSFERS.remove(update.kind());
        var data = JsonParser.parseString(transfer.data().toString()).getAsJsonObject();
        var minecraft = Minecraft.getInstance();
        if (update.kind() == 0) {
            var translations = new HashMap<String, String>();
            data.getAsJsonObject("names").entrySet().forEach(entry -> translations.put(entry.getKey(), entry.getValue().getAsString()));
            names = Map.copyOf(translations);
            var retiredIds = new java.util.HashSet<String>();
            data.getAsJsonArray("retired").forEach(id -> retiredIds.add(id.getAsString()));
            retired = Set.copyOf(retiredIds);
            var paths = new java.util.HashSet<String>();
            data.getAsJsonArray("overrides").forEach(path -> paths.add(path.getAsString()));
            overrides = Set.copyOf(paths);
            if (revision >= 0 && revision != update.revision()) {
                if (minecraft.screen instanceof RaceSelectionScreen && minecraft.player != null) {
                    StatsProvider.get(StatsCapability.INSTANCE, minecraft.player).ifPresent(stats ->
                            minecraft.setScreen(new RaceSelectionScreen(stats.getCharacter())));
                } else if (minecraft.screen instanceof SkillsMenuScreen) {
                    minecraft.setScreen(new SkillsMenuScreen());
                } else if (minecraft.screen instanceof CharacterCustomizationScreen screen) {
                    if (minecraft.player != null) {
                        StatsProvider.get(StatsCapability.INSTANCE, minecraft.player).ifPresent(stats -> {
                            var character = stats.getCharacter();
                            if (!stats.getStatus().isHasCreatedCharacter() && retired(character.getRaceName())) {
                                minecraft.setScreen(new RaceSelectionScreen(character));
                            } else screen.resize(minecraft, screen.width, screen.height);
                        });
                    }
                } else if (minecraft.screen instanceof EditorScreen screen) {
                    screen.stale();
                }
            }
            revision = update.revision();
            EditorNetwork.acknowledge(update.revision());
        } else if (update.kind() == 1) {
            if (minecraft.screen instanceof EditorScreen screen) screen.load(update.revision(), data);
            else minecraft.setScreen(new EditorScreen(update.revision(), data));
        } else {
            if (minecraft.screen instanceof EditorScreen screen) screen.review(data);
        }
    }

    public static String name(String key) { return names.get(key); }
    public static boolean retired(String race) { return retired.contains(race); }
    public static boolean overrides(String path) { return overrides.contains(path); }

    @SubscribeEvent
    public static void logout(ClientPlayerNetworkEvent.LoggingOut event) {
        TRANSFERS.clear();
        names = Map.of();
        retired = Set.of();
        overrides = Set.of();
        revision = -1;
    }
}
