package dev.aerogel.client;

import dev.aerogel.common.MeshMath;
import dev.aerogel.common.ProxyChunkUtil;
import dev.aerogel.net.ClientSettingPayload;
import dev.aerogel.net.ClientSwitchPayload;
import dev.aerogel.net.MeshRequestPayload;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Client-side game-bus handlers: proxy mesh rendering + the client half of the walking clamp (identical
 * math to the server clamp; heights come from the server stream since the client has no seed). Only
 * registered on the client dist.
 */
public final class ClientHandlers {
    private static final Logger LOGGER = LoggerFactory.getLogger("aerogel.client");
    private static ResourceKey<Level> currentDimension;
    private static int diagTicks;
    private static int reconcileTicks;
    private static boolean configLoaded;
    private static boolean syncedToServer;

    private ClientHandlers() {
    }

    /** apply a server-relayed client-only setting (draw radius). */
    public static void applySetting(ClientSettingPayload payload) {
        switch (payload.feature()) {
            case ClientSettingPayload.DRAW_RADIUS -> ProxyRenderer.setDrawRadius(payload.value());
            default -> {
            }
        }
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (!configLoaded) {
            configLoaded = true;
            ClientFeatures.load(); // restore persisted switches + draw radius once the CLIENT config is loaded
        }
        if (mc.level == null) {
            syncedToServer = false;
            if (currentDimension != null) {
                ClientMeshStore.clear();
                BiomeColors.clear();
                ProxyRenderer.clear();
                currentDimension = null;
            }
            return;
        }
        if (!mc.level.dimension().equals(currentDimension)) {
            ClientMeshStore.clear();
            BiomeColors.clear();
            ProxyRenderer.clear();
            currentDimension = mc.level.dimension();
        }
        LocalPlayer player = mc.player;
        if (player == null || player.isSpectator()) {
            return;
        }
        if (!syncedToServer) {
            syncedToServer = true;
            for (String feature : ClientSwitchPayload.FEATURES) {
                PacketDistributor.sendToServer(new ClientSwitchPayload(feature, ClientFeatures.get(feature)));
            }
        }
        // reconciliation: ask the server for meshes we are missing (heals join drops / reconnects / races).
        // Nearest-first so the chunk we are standing in is always in the first batch.
        if (ClientFeatures.proxy && ClientFeatures.reconcile && ++reconcileTicks % 20 == 0) {
            ChunkPos center = player.chunkPosition();
            Map<Long, Integer> missing = new HashMap<>();
            int radius = Math.min(ProxyRenderer.drawRadius(), 48);
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    int cx = center.x + dx;
                    int cz = center.z + dz;
                    long pos = ChunkPos.asLong(cx, cz);
                    if (ClientMeshStore.has(pos)) {
                        continue;
                    }
                    // a real chunk that is actually drawn renders itself; if it is present but not rendered
                    // (uncompiled or culled) the proxy must cover it, so still request the mesh
                    if (ProxyRenderer.isRendered(mc.level, pos)) {
                        continue;
                    }
                    missing.put(pos, dx * dx + dz * dz);
                }
            }
            if (!missing.isEmpty()) {
                List<Long> sorted = new ArrayList<>(missing.keySet());
                sorted.sort(Comparator.comparingInt(missing::get));
                if (sorted.size() > 128) {
                    sorted = sorted.subList(0, 128);
                }
                long[] array = new long[sorted.size()];
                for (int i = 0; i < array.length; i++) {
                    array[i] = sorted.get(i);
                }
                PacketDistributor.sendToServer(new MeshRequestPayload(array));
            }
        }
        long chunkPos = player.chunkPosition().toLong();
        ClientMeshStore.Mesh mesh = ClientMeshStore.get(chunkPos);
        if (++diagTicks % 100 == 0) {
            LevelChunk diagChunk = mc.level.getChunkSource().getChunk(player.chunkPosition().x, player.chunkPosition().z, ChunkStatus.FULL, false);
            LOGGER.info("proxy diag: store {}, chunk ({},{}), hasMesh {}, loaded {}, y {}",
                ClientMeshStore.size(), player.chunkPosition().x, player.chunkPosition().z, mesh != null,
                diagChunk == null ? "none" : ProxyChunkUtil.isAirStub(diagChunk) ? "stub" : "real",
                String.format("%.1f", player.getY()));
        }
        if (!ClientFeatures.proxy || !ClientFeatures.clamp || mesh == null) {
            return;
        }
        LevelChunk chunk = mc.level.getChunkSource().getChunk(ChunkPos.getX(chunkPos), ChunkPos.getZ(chunkPos), ChunkStatus.FULL, false);
        if (chunk != null && !ProxyChunkUtil.isAirStub(chunk)) {
            return; // real chunk loaded -> vanilla collision is authoritative
        }
        double surface = MeshMath.surfaceAt(chunkPos, mesh.topology(), mesh.grid(), player.getX(), player.getZ());
        if (player.getY() < surface) {
            player.setPos(player.getX(), surface, player.getZ());
            player.setDeltaMovement(player.getDeltaMovement().x, 0.0, player.getDeltaMovement().z);
            // no real blocks -> vanilla physics never sets onGround -> permanent falling pose + no jumping;
            // claiming ground contact here makes walking/jumping behave (server clamp catches drift)
            player.setOnGround(true);
        }
    }

    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        ClientPerf.onStage(event);
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) {
            return;
        }
        if (ClientFeatures.proxy) {
            ProxyRenderer.render(event);
        }
    }
}
