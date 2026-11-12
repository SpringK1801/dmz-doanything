package dev.dbzdoanything;

import com.dragonminez.client.model.DMZPlayerModel;
import com.dragonminez.common.config.FormConfig;
import com.dragonminez.common.config.RaceCharacterConfig;
import com.dragonminez.common.stats.StatsCapability;
import com.dragonminez.common.stats.StatsProvider;
import com.mojang.authlib.GameProfile;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.client.renderer.CubeMap;
import net.minecraft.client.renderer.PanoramaRenderer;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.world.scores.Team;
import org.joml.Quaternionf;
import software.bernie.geckolib.cache.GeckoLibCache;
import software.bernie.geckolib.core.animatable.GeoAnimatable;

import java.util.UUID;

final class RaceEditorScene {
    static final int ACCENT = 0xFF87D9DC;
    static final int TEXT = 0xFFF0F3F4;
    static final int MUTED = 0xFFB7C6CD;
    static final int WARNING = 0xFFFFB27F;
    private static final ResourceLocation ATLAS = ResourceLocation.fromNamespaceAndPath("dragonminez", "textures/gui/menu/menubig.png");
    private final PanoramaRenderer panorama = new PanoramaRenderer(new CubeMap(
            ResourceLocation.fromNamespaceAndPath("dragonminez", "textures/gui/background/human_panorama")));
    private final EditorScreen screen;
    private PreviewPlayer fighter;
    private String aura = "";
    private String auraType = "";

    RaceEditorScene(EditorScreen screen) {
        this.screen = screen;
    }

    private static final class PreviewPlayer extends RemotePlayer {
        private final Team team;
        private final ResourceLocation skin;

        PreviewPlayer(ClientLevel level, ResourceLocation skin) {
            super(level, new GameProfile(UUID.randomUUID(), "RacePreview"));
            var previewTeam = new Scoreboard().addPlayerTeam("race_preview");
            previewTeam.setNameTagVisibility(Team.Visibility.NEVER);
            team = previewTeam;
            this.skin = skin;
        }

        @Override public Team getTeam() { return team; }
        @Override public ResourceLocation getSkinTextureLocation() { return skin; }
    }

    void background(float delta) {
        panorama.render(delta, 0.86F);
    }

    void clear() {
        fighter = null;
        aura = "";
        auraType = "";
    }

    void show(String race, RaceCharacterConfig config, String group, String id, FormConfig.FormData form) {
        var client = Minecraft.getInstance();
        if (client.level == null || client.player == null) {
            clear();
            return;
        }
        if (fighter == null || fighter.level() != client.level) {
            fighter = new PreviewPlayer(client.level, client.player.getSkinTextureLocation());
        }
        StatsProvider.get(StatsCapability.INSTANCE, fighter).ifPresent(stats -> {
            var character = stats.getCharacter();
            EditorScreen.applyPreviewAppearance(character, race, config);
            ((PreviewFormAccess) (Object) character).editor$setPreviewForm(form);
            character.setActiveFormGroup(form == null ? "" : group);
            character.setActiveForm(form == null ? "" : id);
            stats.getStatus().setHasCreatedCharacter(true);
        });
        aura = form != null && !form.getAuraColor().isBlank() ? form.getAuraColor() : config.getDefaultAuraColor();
        String style = form != null && !form.getAuraType().isBlank() ? form.getAuraType() : config.getAuraType();
        String style3d = form != null && !form.getAuraType3D().isBlank() ? form.getAuraType3D() : config.getAuraType3D();
        auraType = EditorPresentation.readable(style);
        if (!style3d.isBlank() && !style3d.equalsIgnoreCase("userPreference") && !style3d.equalsIgnoreCase(style)) {
            auraType += " / " + EditorPresentation.readable(style3d);
        }
    }

    boolean hasGeometry() {
        if (!((Object) fighter instanceof GeoAnimatable)) return false;
        var model = new DMZPlayerModel<>("", "");
        var id = model.getModelResource((AbstractClientPlayer & GeoAnimatable) (Object) fighter);
        return GeckoLibCache.getBakedModels().containsKey(id)
                || com.dragonminez.client.model.ModelMorph.baked(id) != null;
    }

    static int panelWidth(int width) {
        return Math.max(103, Math.min(240, (width - 126) / 2));
    }

    void frame(GuiGraphics graphics, int width, int height, String title, String heading) {
        int panel = panelWidth(width);
        int right = width - panel - 8;
        graphics.fill(0, 0, width, 34, 0xCC111C21);
        graphics.fill(0, height - 30, width, height, 0xCC111C21);
        centered(graphics, title, width / 2, 13, width - 20, ACCENT);
        panel(graphics, 8, 44, panel, height - 106);
        panel(graphics, right, 44, panel, height - 106);
        screen.text(graphics, "About", 16, 52, panel - 38, ACCENT);
        screen.text(graphics, heading, right + 8, 52, panel - 38, ACCENT);
    }

    void fighter(GuiGraphics graphics, int width, int height) {
        int center = width / 2;
        int right = width - panelWidth(width) - 8;
        int stage = height - 83;
        int stageWidth = Math.min(113, Math.max(64, right - center) * 2 - 12);
        graphics.blit(ATLAS, center - stageWidth / 2, stage - 8, stageWidth, 16, 0, 215, 149, 21, 256, 256);
        if (fighter != null) {
            float angle = (Util.getMillis() % 11_000L) * (360f / 11_000L);
            fighter.setYRot(angle);
            fighter.yBodyRot = angle;
            fighter.yHeadRot = angle;
            fighter.yHeadRotO = angle;
            fighter.yBodyRotO = angle;
            int size = Math.max(27, Math.min(83, (stage - 53) / 2));
            InventoryScreen.renderEntityInInventory(graphics, center, stage - 5, size,
                    new Quaternionf().rotateZ((float) Math.PI), null, fighter);
        }
        graphics.fill(center - stageWidth / 2, stage + 4, center + stageWidth / 2, stage + 16, 0xDF162731);
        graphics.fill(center - stageWidth / 2 + 3, stage + 6, center - stageWidth / 2 + 11, stage + 14, 0xFF000000 | rgb(aura, 0xFFFFFF));
        screen.text(graphics, auraType.isBlank() ? "Default Aura" : auraType, center - stageWidth / 2 + 15, stage + 6, stageWidth - 17, TEXT);
    }

    static void panel(GuiGraphics graphics, int x, int y, int w, int h) {
        graphics.fill(x, y, x + w, y + h, 0xDE15242C);
        graphics.renderOutline(x, y, w, h, 0xFF5B9BA6);
        graphics.fill(x + 1, y + 16, x + w - 1, y + 17, 0x756596A0);
    }

    int wrapped(GuiGraphics graphics, String text, int x, int y, int width, int rows, int color) {
        var lines = screen.builderFont().split(Component.literal(text), width);
        int visible = Math.min(rows, lines.size());
        for (int row = 0; row < visible; row++) graphics.drawString(screen.builderFont(), lines.get(row), x, y + row * 11, color);
        return visible;
    }

    void centered(GuiGraphics graphics, String label, int center, int y, int width, int color) {
        var font = screen.builderFont();
        String clipped = font.plainSubstrByWidth(label, width);
        screen.text(graphics, clipped, center - font.width(clipped) / 2, y, width, color);
    }

    static int rgb(String color, int fallback) {
        return color != null && color.matches("#[a-fA-F0-9]{6}") ? Integer.parseInt(color.substring(1), 16) : fallback;
    }
}
