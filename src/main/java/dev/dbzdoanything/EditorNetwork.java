package dev.dbzdoanything;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

import java.io.IOException;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.UUID;

public final class EditorNetwork {
    private static final int CHUNK = 8_000;
    static final int REQUEST_LIMIT = 16_000;
    static final int REVIEW_RACE_BUILDER = 4;
    public static final int MAX_CHUNKS = 256;
    private static final int ACK_DELAY = 100;
    private static final int LOGIN_ACK_DELAY = 800;
    private static final HashMap<UUID, Pending> PENDING = new HashMap<>();
    private static final HashMap<UUID, Review> REVIEWS = new HashMap<>();
    private static final HashMap<UUID, Integer> LAST_REQUEST = new HashMap<>();
    private static SimpleChannel channel;

    private record Pending(long revision, int deadline, int retries) {}
    private record Ack(long revision) {}
    private record Request(int action, String text) {}
    private record Review(UUID token, int expires, RevisionStore.Revision revision) {}
    public record Update(long revision, int kind, int index, int count, String text) {}

    private EditorNetwork() {}

    static void register() {
        String version = protocol();
        channel = NetworkRegistry.ChannelBuilder
                .named(ResourceLocation.fromNamespaceAndPath(EditorMod.ID, "editor"))
                .networkProtocolVersion(() -> version)
                .clientAcceptedVersions(version::equals).serverAcceptedVersions(version::equals).simpleChannel();
        channel.messageBuilder(Update.class, 0, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(EditorNetwork::encode).decoder(EditorNetwork::decode)
                .consumerMainThread((message, context) -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                        () -> () -> EditorClient.receive(message))).add();
        channel.messageBuilder(Ack.class, 1, NetworkDirection.PLAY_TO_SERVER)
                .encoder((message, buf) -> buf.writeVarLong(message.revision()))
                .decoder(buf -> new Ack(buf.readVarLong()))
                .consumerMainThread((message, context) -> {
                    var player = context.get().getSender();
                    if (player != null) acceptAck(player, message.revision());
                }).add();
        channel.messageBuilder(Request.class, 2, NetworkDirection.PLAY_TO_SERVER)
                .encoder((request, buf) -> buf.writeVarInt(request.action()).writeUtf(request.text(), REQUEST_LIMIT))
                .decoder(buf -> new Request(buf.readVarInt(), buf.readUtf(REQUEST_LIMIT)))
                .consumerMainThread((request, context) -> handle(context.get().getSender(), request)).add();
    }

    private static String protocol() {
        var mod = ModList.get().getModContainerById(EditorMod.ID).orElseThrow().getModInfo();
        var file = mod.getOwningFile().getFile().getFilePath();
        String version = mod.getVersion().toString();
        if (Files.isDirectory(file)) return version + "-dev";
        try {
            var digest = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file));
            return version + "-" + HexFormat.of().formatHex(digest);
        } catch (IOException | NoSuchAlgorithmException error) {
            throw new IllegalStateException("Could not identify the editor build for the multiplayer handshake.", error);
        }
    }

    static void open(ServerPlayer player) {
        var editor = WorldEditor.get(player.server);
        editor.detectSourceChanges();
        var catalog = new JsonObject();
        var documents = new JsonObject();
        var sources = new JsonObject();
        editor.documents().forEach((path, document) -> {
            documents.add(path, document);
            sources.addProperty(path, EditorDraft.source(editor, path, document));
        });
        catalog.add("documents", documents);
        catalog.add("sources", sources);
        catalog.add("disabled", RevisionStore.GSON.toJsonTree(editor.revision().disabledForms()));
        catalog.add("retired", RevisionStore.GSON.toJsonTree(editor.revision().retiredRaces()));
        catalog.add("specialRules", RevisionStore.GSON.toJsonTree(editor.revision().specialRules()));
        var skills = com.dragonminez.common.config.ConfigManager.getSkillsConfig();
        catalog.add("skills", RevisionStore.GSON.toJsonTree(skills.getSkills().keySet().stream().sorted().toList()));
        catalog.add("formSkills", RevisionStore.GSON.toJsonTree(skills.getFormSkills()));
        catalog.add("stackSkills", RevisionStore.GSON.toJsonTree(skills.getStackSkills()));
        catalog.add("abilities", RevisionStore.GSON.toJsonTree(dev.dbzdoanything.mixin.RacialAccess.editorAbilities()
                .keySet().stream().sorted().toList()));
        if (editor.conflict() != null) catalog.addProperty("conflict", editor.conflict());
        send(player, editor.revision().number(), 1, RevisionStore.GSON.toJson(catalog));
    }

    static void sendRevision(ServerPlayer player, RevisionStore.Revision revision, boolean joining) {
        var meta = new JsonObject();
        meta.add("names", RevisionStore.GSON.toJsonTree(revision.names()));
        meta.add("retired", RevisionStore.GSON.toJsonTree(revision.retiredRaces()));
        var overrides = new JsonArray();
        revision.changes().keySet().forEach(overrides::add);
        meta.add("overrides", overrides);
        var previous = PENDING.get(player.getUUID());
        int deadline = player.server.getTickCount() + (joining ? LOGIN_ACK_DELAY : ACK_DELAY);
        // A commit can reach a new client while it still processes the initial native config batch.
        if (previous != null) deadline = Math.max(deadline, previous.deadline());
        PENDING.put(player.getUUID(), new Pending(revision.number(), deadline, 0));
        send(player, revision.number(), 0, RevisionStore.GSON.toJson(meta));
    }

    static void acceptAck(ServerPlayer player, long revision) {
        var pending = PENDING.get(player.getUUID());
        if (pending == null || pending.revision() != revision) return;
        PENDING.remove(player.getUUID());
        if (Boolean.getBoolean("dbzdoanything.proof.lab")) {
            EditorMod.LOGGER.info("{} received revision {}", player.getScoreboardName(), revision);
        }
    }

    static void tick(MinecraftServer server) {
        if (PENDING.isEmpty()) return;
        int now = server.getTickCount();
        for (var player : server.getPlayerList().getPlayers()) {
            var pending = PENDING.get(player.getUUID());
            if (pending == null || pending.deadline() > now) continue;
            if (pending.retries() >= 2) {
                PENDING.remove(player.getUUID());
                player.connection.disconnect(net.minecraft.network.chat.Component.literal("Could not synchronize the live race configuration. Reconnect to retry."));
            } else {
                WorldEditor.get(server).synchronize(player);
                PENDING.put(player.getUUID(), new Pending(pending.revision(), now + ACK_DELAY, pending.retries() + 1));
            }
        }
    }

    private static void handle(ServerPlayer player, Request request) {
        if (player == null) return;
        if (!EditorMod.canEdit(player.createCommandSourceStack())) {
            EditorMod.LOGGER.warn("{} attempted an unauthorized editor action", player.getScoreboardName());
            return;
        }
        UUID id = player.getUUID();
        int now = player.server.getTickCount();
        Integer last = LAST_REQUEST.get(id);
        if (last != null && now - last < 3) return;
        LAST_REQUEST.put(id, now);
        var editor = WorldEditor.get(player.server);
        try {
            if (request.action() == 0) {
                open(player);
            } else if (request.action() == 1 || request.action() == 3 || request.action() == REVIEW_RACE_BUILDER) {
                REVIEWS.remove(id);
                editor.detectSourceChanges();
                if (editor.conflict() != null) throw new IllegalStateException(editor.conflict());
                RaceBuilderDraft.Prepared builder = request.action() == REVIEW_RACE_BUILDER
                        ? RaceBuilderDraft.prepare(editor, RevisionStore.GSON.fromJson(request.text(), RaceBuilderDraft.Proposal.class))
                        : null;
                var next = switch (request.action()) {
                    case 1 -> EditorDraft.prepare(editor, RevisionStore.GSON.fromJson(request.text(), EditorDraft.Proposal.class));
                    case REVIEW_RACE_BUILDER -> builder.revision();
                    default -> editor.revert(Long.parseLong(request.text()));
                };
                editor.validate(next);
                var reply = new JsonObject();
                var token = UUID.randomUUID();
                reply.addProperty("token", token.toString());
                var lines = new java.util.ArrayList<>(editor.review(next));
                if (builder != null) lines.addAll(builder.details());
                reply.add("lines", RevisionStore.GSON.toJsonTree(lines));
                REVIEWS.put(id, new Review(token, now + 2400, next));
                send(player, editor.revision().number(), 2, RevisionStore.GSON.toJson(reply));
            } else if (request.action() == 2) {
                var review = REVIEWS.remove(id);
                if (review == null || now > review.expires() || !review.token().toString().equals(request.text())) {
                    throw new IllegalArgumentException("Review this draft again before applying.");
                }
                editor.commit(review.revision());
                open(player);
            }
        } catch (RuntimeException | java.io.IOException error) {
            var reply = new JsonObject();
            reply.addProperty("error", error.getMessage() == null ? "Could not validate this request." : error.getMessage());
            send(player, editor.revision().number(), 2, RevisionStore.GSON.toJson(reply));
        }
    }

    static void forget(UUID player) {
        PENDING.remove(player);
        REVIEWS.remove(player);
        LAST_REQUEST.remove(player);
    }

    static void clear() {
        PENDING.clear();
        REVIEWS.clear();
        LAST_REQUEST.clear();
    }

    static void invalidateReviews() { REVIEWS.clear(); }

    static void acknowledge(long revision) {
        channel.sendToServer(new Ack(revision));
    }

    static void request(int action, String text) {
        channel.sendToServer(new Request(action, text));
    }

    private static void send(ServerPlayer player, long revision, int kind, String text) {
        int count = Math.max(1, (text.length() + CHUNK - 1) / CHUNK);
        if (count > MAX_CHUNKS) throw new IllegalArgumentException("Editor catalog exceeds the transfer limit.");
        for (int index = 0; index < count; index++) {
            int start = index * CHUNK;
            int end = Math.min(text.length(), start + CHUNK);
            // JSON uses ASCII escapes for unpaired surrogates. Keep UTF-16 pairs in one frame.
            if (start > 0 && Character.isLowSurrogate(text.charAt(start))) start--;
            if (end < text.length() && Character.isLowSurrogate(text.charAt(end))) end--;
            var message = new Update(revision, kind, index, count, text.substring(start, end));
            channel.send(PacketDistributor.PLAYER.with(() -> player), message);
        }
    }

    private static void encode(Update packet, FriendlyByteBuf buf) {
        buf.writeVarLong(packet.revision());
        buf.writeVarInt(packet.kind());
        buf.writeVarInt(packet.index());
        buf.writeVarInt(packet.count());
        buf.writeUtf(packet.text(), CHUNK + 1);
    }

    private static Update decode(FriendlyByteBuf buf) {
        return new Update(buf.readVarLong(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readUtf(CHUNK + 1));
    }
}
