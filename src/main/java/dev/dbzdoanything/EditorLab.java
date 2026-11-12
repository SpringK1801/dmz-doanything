package dev.dbzdoanything;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.io.IOException;
import java.nio.file.Files;

@Mod.EventBusSubscriber(modid = EditorMod.ID, value = Dist.CLIENT)
final class EditorLab {
    private static final boolean ENABLED = Boolean.getBoolean("dbzdoanything.proof.lab");

    private EditorLab() {}

    @SubscribeEvent
    public static void tick(TickEvent.ClientTickEvent event) {
        if (!ENABLED || event.phase != TickEvent.Phase.END) return;
        var client = Minecraft.getInstance();
        var server = client.getCurrentServer();
        if (client.level == null
                || (!client.hasSingleplayerServer() && (server == null || !server.ip.matches("127\\.0\\.0\\.1:25569")))) return;
        if (client.hasSingleplayerServer() && client.player != null) {
            var open = client.gameDirectory.toPath().resolve("dbzdoanything-lab-open.txt");
            try {
                if (Files.deleteIfExists(open)) client.player.connection.sendCommand(EditorMod.COMMAND);
            } catch (IOException error) {
                EditorMod.LOGGER.error("Editor lab could not open the integrated editor", error);
            }
        }
        if (!(client.screen instanceof EditorScreen screen)) return;
        var path = client.gameDirectory.toPath().resolve("dbzdoanything-lab-text.txt");
        if (!Files.isRegularFile(path)) return;
        try {
            if (Files.size(path) <= 2048 && screen.getFocused() instanceof EditBox input) {
                input.setValue(Files.readString(path));
                EditorMod.LOGGER.info("Editor lab filled {}", input.getMessage().getString());
            }
            if (Files.size(path) <= 2048 && screen.getFocused() instanceof MultiLineEditBox input) {
                input.setValue(Files.readString(path));
                EditorMod.LOGGER.info("Editor lab filled race description");
            }
            Files.delete(path);
        } catch (IOException error) {
            EditorMod.LOGGER.error("Editor lab input failed", error);
        }
    }
}
