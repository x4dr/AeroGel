package dev.aerogel.client;

import dev.aerogel.common.MeshMath;
import dev.aerogel.common.ProxyChunkUtil;
import dev.aerogel.net.ClientSettingPayload;
import dev.aerogel.net.ClientSwitchPayload;
import dev.aerogel.net.MeshRequestPayload;
import java.util.Arrays;
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
    /** reconciliation request cap per second (the server front-queues these). */
    private static final int RECONCILE_REQUEST_CAP = 128;
    /** reconciliation scan radius cap (chunks); keeps the per-second scan bounded on huge draw radii. */
    private static final int RECONCILE_RADIUS_CAP = 48;
    /** reused nearest-first request buffer; no per-second allocation. */
    private static final long[] reconcileBuffer = new long[RECONCILE_REQUEST_CAP];
    private static ResourceKey<Level> currentDimension;
    private static int diagTicks;
    private static int reconcileTicks;
    /** center of the last reconciliation scan + whether it found anything missing (for the dormant early-out). */
    private static long reconcileCenter = Long.MIN_VALUE;
    private static boolean reconcileFound;
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
            reconcileCenter = Long.MIN_VALUE;
            reconcileFound = false;
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
        // Nearest-first (Chebyshev rings) so the chunk we are standing in is always in the first batch.
        // The whole scan is skipped while the store is empty, the last scan found nothing, the player has not
        // changed chunk and travel is off: that is the dormant vanilla case (the server pushes its own window
        // on login and chunk-cross), and re-running the O(r^2) scan every second would only allocate + call
        // isAirStub. A client that opted into travel always keeps reconciling.
        if (ClientFeatures.proxy && ClientFeatures.reconcile && ++reconcileTicks % 20 == 0) {
            ChunkPos center = player.chunkPosition();
            boolean moved = center.toLong() != reconcileCenter;
            boolean dormant = ClientMeshStore.size() == 0 && !reconcileFound && !moved && !ClientFeatures.travel;
            if (!dormant) {
                reconcileCenter = center.toLong();
                reconcileFound = false;
                int radius = Math.min(ProxyRenderer.drawRadius(), RECONCILE_RADIUS_CAP);
                int count = 0;
                for (int d = 0; d <= radius && count < RECONCILE_REQUEST_CAP; d++) {
                    for (int dx = -d; dx <= d && count < RECONCILE_REQUEST_CAP; dx++) {
                        boolean onXEdge = dx == -d || dx == d;
                        for (int dz = -d; dz <= d && count < RECONCILE_REQUEST_CAP; dz++) {
                            if (!onXEdge && dz != -d && dz != d) {
                                continue; // interior of the ring: already covered by a smaller ring
                            }
                            long pos = ChunkPos.asLong(center.x + dx, center.z + dz);
                            if (ClientMeshStore.has(pos)) {
                                continue;
                            }
                            // a real chunk that is actually drawn renders itself; if it is present but not
                            // rendered (uncompiled or culled) the proxy must cover it, so still request it
                            if (ProxyRenderer.isRendered(mc.level, pos)) {
                                continue;
                            }
                            reconcileBuffer[count++] = pos;
                        }
                    }
                }
                if (count > 0) {
                    reconcileFound = true;
                    PacketDistributor.sendToServer(new MeshRequestPayload(Arrays.copyOf(reconcileBuffer, count)));
                }
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
