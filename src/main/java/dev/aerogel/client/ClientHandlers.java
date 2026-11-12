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
import net.minecraft.world.phys.AABB;
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
    /**
     * Standing height used when the client has no chunk at the player and no proxy mesh for it yet: a missing
     * chunk is a proxy chunk, so the player must not fall into land the client has no data for. It is a
     * placeholder only; the first mesh from the server replaces it (see {@link #onClientTick}).
     */
    private static final double MISSING_CHUNK_FLOOR_Y = 64.0;
    /** cap for the one-shot lift out of terrain when the chunk under the player arrives. */
    private static final int MAX_LIFT_STEPS = 384;
    /** true while the chunk under the player was not real, so the arrival can lift the player once. */
    private static boolean chunkWasNotReal;
    private static ResourceKey<Level> currentDimension;
    private static int diagTicks;
    private static int reconcileTicks;
    /** center of the last reconciliation scan + whether it found anything missing (for the dormant early-out). */
    private static long reconcileCenter = Long.MIN_VALUE;
    private static boolean reconcileFound;
    private static boolean configLoaded;
    private static boolean syncedToServer;
    private static boolean serverHasAeroGel;

    private ClientHandlers() {
    }

    /** apply a server-relayed client-only setting (draw radius, one-shot diagnostics). */
    public static void applySetting(ClientSettingPayload payload) {
        switch (payload.feature()) {
            case ClientSettingPayload.DRAW_RADIUS -> ProxyRenderer.setDrawRadius(payload.value());
            case ClientSettingPayload.DIAG -> {
                Minecraft mc = Minecraft.getInstance();
                if (mc.level != null && mc.player != null) {
                    LOGGER.info(ProxyRenderer.diagnostic(mc.level, mc.player.chunkPosition()));
                }
            }
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
            chunkWasNotReal = false;
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
            chunkWasNotReal = false;
        }
        LocalPlayer player = mc.player;
        if (player == null || player.isSpectator()) {
            return;
        }
        if (!syncedToServer) {
            syncedToServer = true;
            // #7: a server without AeroGel has no channels; checked once per join, then nothing is ever sent
            serverHasAeroGel = mc.getConnection() != null && mc.getConnection().hasChannel(ClientSwitchPayload.TYPE);
            if (!serverHasAeroGel) {
                LOGGER.info("server has no AeroGel: proxy layer idle for this session");
            } else {
                for (String feature : ClientSwitchPayload.FEATURES) {
                    PacketDistributor.sendToServer(new ClientSwitchPayload(feature, ClientFeatures.get(feature)));
                }
            }
            // one line per join, so a bisect switch left off is visible without reading the config file. `proxy`
            // is the master for the proxy layer: off means no meshes and no reconciliation. The clamp is its own
            // switch and keeps working without it, on a flat placeholder when there is no mesh.
            LOGGER.info("client switches: proxy {} travel {} clamp {} (proxy and travel both need to be on to see proxies while the server is calm)",
                ClientFeatures.proxy, ClientFeatures.travel, ClientFeatures.clamp);
            if (!ClientFeatures.proxy) {
                LOGGER.warn("proxy switch is OFF: no meshes, no reconciliation; /aerogel switch proxy true (the clamp keeps working)");
            }
        }
        // reconciliation: ask the server for meshes we are missing (heals join drops / reconnects / races).
        // Nearest-first (Chebyshev rings) so the chunk we are standing in is always in the first batch.
        // The whole scan is skipped while the store is empty, the last scan found nothing, the player has not
        // changed chunk and travel is off: that is the dormant vanilla case (the server pushes its own window
        // on login and chunk-cross), and re-running the O(r^2) scan every second would only allocate + call
        // isAirStub. A client that opted into travel always keeps reconciling.
        if (serverHasAeroGel && ClientFeatures.proxy && ClientFeatures.reconcile && ++reconcileTicks % 20 == 0) {
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
                            // only ask for a proxy where the client has no real terrain to be covered by; the
                            // covering test is not "is it drawn" (unknowable under Sodium) because the layer
                            // is drawn first and the terrain paints over it
                            if (ProxyRenderer.hasRealTerrain(mc.level, pos)) {
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
            LOGGER.info("proxy diag: store {}, chunk ({},{}), hasMesh {}, loaded {}, visible {}, y {}",
                ClientMeshStore.size(), player.chunkPosition().x, player.chunkPosition().z, mesh != null,
                diagChunk == null ? "none" : ProxyChunkUtil.isAirStub(diagChunk) ? "stub" : "real",
                ProxyRenderer.visibleSignal(), String.format("%.1f", player.getY()));
        }
        if (!ClientFeatures.clamp) {
            return; // the clamp is its own switch: no mesh, no proxy stream and no proxy=true are needed
        }
        LevelChunk chunk = mc.level.getChunkSource().getChunk(ChunkPos.getX(chunkPos), ChunkPos.getZ(chunkPos), ChunkStatus.FULL, false);
        boolean realHere = chunk != null && !ProxyChunkUtil.isAirStub(chunk);
        if (realHere) {
            // the chunk arrived. If it arrived around the player (the placeholder plane, or a stale mesh
            // surface, was below the real terrain) the client is inside blocks and vanilla collision will
            // never lift it out: do that once, here, with or without a mesh.
            if (chunkWasNotReal) {
                chunkWasNotReal = false;
                liftOutOfTerrain(player);
            }
            return; // real chunk: vanilla collision is authoritative from here on
        }
        chunkWasNotReal = true;
        // Not real on this client: a missing chunk, an air stub, or a proxy. In all three cases vanilla has no
        // collision here (a missing chunk is not even ticked by LocalPlayer), so the proxy surface IS the
        // floor. Use the server's mesh when it has arrived; until then stand on a flat plane, so a stalled
        // server (seconds) cannot drop the player into land the client has no data for. This is the same rule
        // the server net applies, but the client is the authority here because the server never sends the
        // chunk at all (it is gated), and it is the client that knows which chunks it has.
        double surface = mesh != null
            ? MeshMath.surfaceAt(chunkPos, mesh.topology(), mesh.grid(), player.getX(), player.getZ())
            : MISSING_CHUNK_FLOOR_Y;
        if (player.getY() < surface) {
            player.setPos(player.getX(), surface, player.getZ());
            player.setDeltaMovement(player.getDeltaMovement().x, 0.0, player.getDeltaMovement().z);
            // no real blocks -> vanilla physics never sets onGround -> permanent falling pose + no jumping;
            // claiming ground contact here makes walking/jumping behave (the movement checks are bypassed, so
            // the server takes this y as authoritative)
            player.setOnGround(true);
        }
    }

    /**
     * The client half of the server's promotion push-out: lift the player to the first y where its box is free.
     * Used once per chunk arrival, so it cannot become a per-tick correction, and it needs no mesh data.
     */
    private static void liftOutOfTerrain(LocalPlayer player) {
        AABB box = player.getBoundingBox();
        if (player.level().noCollision(player, box)) {
            return; // arrived in free space, which is the usual case at altitude
        }
        double from = player.getY();
        double y = from;
        int steps = 0;
        while (!player.level().noCollision(player, box) && steps++ < MAX_LIFT_STEPS) {
            y += 1.0;
            box = box.move(0.0, 1.0, 0.0);
        }
        player.setPos(player.getX(), y, player.getZ());
        player.setDeltaMovement(player.getDeltaMovement().x, 0.0, player.getDeltaMovement().z);
        player.setOnGround(true);
        LOGGER.info("lifted out of terrain on chunk arrival: y {} -> {}",
            String.format("%.1f", from), String.format("%.1f", y));
    }

    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        ClientPerf.onStage(event);
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_SKY) {
            return;
        }
        if (ClientFeatures.proxy) {
            ProxyRenderer.render(event);
        }
    }
}
