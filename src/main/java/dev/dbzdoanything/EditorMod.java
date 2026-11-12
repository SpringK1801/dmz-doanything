package dev.dbzdoanything;

import com.dragonminez.server.commands.DMZPermissions;
import com.google.gson.JsonObject;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.logging.LogUtils;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;

import java.io.IOException;
import java.util.Map;

@Mod(EditorMod.ID)
public final class EditorMod {
    public static final String ID = "dbzdoanything";
    static final String COMMAND = "dmzdoanything";
    static final Logger LOGGER = LogUtils.getLogger();
    private static final boolean PROOF = Boolean.getBoolean("dbzdoanything.proof.lab");

    public EditorMod() {
        EditorNetwork.register();
        net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext.get().getModEventBus().addListener(this::setup);
        var events = MinecraftForge.EVENT_BUS;
        SpecialGameplay.register(events);
        events.addListener(EventPriority.LOWEST, this::start);
        events.addListener(this::stop);
        events.addListener(this::commands);
        events.addListener(EventPriority.LOWEST, this::login);
        events.addListener(this::logout);
        events.addListener(this::tick);
    }

    private void setup(net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent event) {
        event.enqueueWork(dev.dbzdoanything.compat.UltraCompatibility::registerIfSupported);
    }

    private void start(ServerStartedEvent event) {
        WorldEditor.start(event.getServer());
    }

    private void stop(ServerStoppedEvent event) {
        WorldEditor.stop(event.getServer());
        EditorNetwork.clear();
    }

    private void login(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            var editor = WorldEditor.get(player.server);
            editor.synchronize(player, true);
            if (editor.revision().number() > 0) PlayerRefresh.reconcile(player, null);
        }
    }

    private void logout(PlayerEvent.PlayerLoggedOutEvent event) {
        EditorNetwork.forget(event.getEntity().getUUID());
    }

    private void tick(TickEvent.ServerTickEvent event) {
        if (event.phase == TickEvent.Phase.END) EditorNetwork.tick(event.getServer());
    }

    static boolean canEdit(CommandSourceStack source) {
        return DMZPermissions.hasPermission(source, DMZPermissions.ADMIN);
    }

    private void commands(RegisterCommandsEvent event) {
        var root = Commands.literal(COMMAND).requires(EditorMod::canEdit)
                .executes(context -> {
                    var source = context.getSource();
                    var player = source.getPlayerOrException();
                    if (!canEdit(source)) {
                        source.sendFailure(Component.literal("DMZ Do Anything requires administrator permission."));
                        return 0;
                    }
                    EditorNetwork.open(player);
                    return 1;
                });
        if (PROOF) {
            root.then(Commands.literal("proof").then(Commands.argument("race", StringArgumentType.word())
                    .executes(context -> {
                        try {
                            var editor = WorldEditor.get(context.getSource().getServer());
                            String race = CatalogValidation.id(StringArgumentType.getString(context, "race"));
                            proof(editor, race);
                            context.getSource().sendSuccess(() -> Component.literal("Published " + race), true);
                            return 1;
                        } catch (RuntimeException | IOException error) {
                            context.getSource().sendFailure(Component.literal(error.getMessage()));
                            LOGGER.error("Live compatibility proof failed", error);
                            return 0;
                        }
                    })));
            root.then(Commands.literal("inspect").then(Commands.argument("player", net.minecraft.commands.arguments.EntityArgument.player())
                    .executes(context -> {
                        var player = net.minecraft.commands.arguments.EntityArgument.getPlayer(context, "player");
                        com.dragonminez.common.stats.StatsProvider.get(com.dragonminez.common.stats.StatsCapability.INSTANCE, player)
                                .ifPresent(data -> {
                                    var form = data.getCharacter().getActiveFormData();
                                    LOGGER.info("Editor proof {} race={} form={} pwr={} drain={} energy={} mastery={}",
                                            player.getScoreboardName(), data.getCharacter().getRaceName(),
                                            form == null ? "" : form.getName(), data.getTotalMultiplier("PWR"),
                                            form == null ? 0.0 : form.getEnergyDrain(), data.getResources().getCurrentEnergy(),
                                            form == null ? 0.0 : data.getCharacter().getFormMasteries()
                                                    .getMastery(data.getCharacter().getActiveFormGroup(), form.getName()));
                                });
                        return 1;
                    })));
        }
        event.getDispatcher().register(root);
    }

    private static void proof(WorldEditor editor, String race) throws IOException {
        var docs = editor.documents();
        String path = "races/" + race;
        if (docs.containsKey(path + "/character")) throw new IllegalArgumentException("Race ID is already loaded.");
        var character = docs.get("races/human/character").deepCopy();
        character.addProperty("racialSkill", "");
        character.addProperty("customModel", "human");
        character.addProperty("useVanillaSkin", true);
        var costs = new JsonObject();
        var skill = new JsonObject();
        var prices = new com.google.gson.JsonArray();
        prices.add(1);
        skill.add("prices", prices);
        skill.addProperty("buyFromMaster", false);
        costs.add("superforms", skill);
        character.add("formSkillsCosts", costs);

        var form = new JsonObject();
        form.addProperty("name", "surge");
        form.addProperty("unlockOnSkillLevel", 1);
        form.addProperty("pwrMultiplier", 1.5);
        form.addProperty("energyDrain", 0.15);
        form.addProperty("auraColor", "#51E8D9");
        var stages = new JsonObject();
        stages.add("surge", form);
        var group = new JsonObject();
        group.addProperty("configVersion", "2.2.1");
        group.addProperty("groupName", "proof");
        group.addProperty("formType", "superforms");
        group.add("forms", stages);
        editor.commit(editor.edit(Map.of(path + "/character", character, path + "/stats", docs.get("races/human/stats"),
                        path + "/forms/proof", group),
                Map.of("race.dragonminez." + race, "Live Proof", "race.dragonminez." + race + ".desc",
                        "Published during this world session.", "race.dragonminez." + race + ".form.proof.surge", "Live Surge")));
    }
}
