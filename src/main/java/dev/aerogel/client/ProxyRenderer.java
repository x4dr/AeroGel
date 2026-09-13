package dev.aerogel.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexFormat;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.util.ArrayDeque;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import org.joml.Matrix4f;
import dev.aerogel.common.ProxyChunkUtil;
import dev.aerogel.common.ProxyTopology;
import dev.aerogel.mixin.LevelRendererAccessor;
import dev.aerogel.mixin.ViewAreaInvoker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.ViewArea;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Renders streamed proxy meshes as a custom quad layer, separate from the vanilla chunk pipeline (the
 * meshes are NOT chunks, never request chunks, never feed Distant Horizons).
 *
 * <p><b>One static VertexBuffer per chunk</b>, uploaded once when the mesh arrives and only re-uploaded
 * when its geometry actually changes (grid update, or a neighbour gains/loses a proxy so its seam walls
 * change). The "coming up from the bottom" effect is a per-chunk vertical translation applied to the
 * draw matrix — the geometry is never touched, so a moving mesh costs nothing extra and cannot affect
 * collision (which reads {@link ClientMeshStore}'s settled grid). This replaced the old single giant
 * buffer that was re-emitted every frame while any morph played (the 2026-09-12 fast-flight hitch).</p>
 *
 * <p>Geometry: slanted top quads over the 4x4 lattice cells; walls down to bedrock on chunk borders that
 * are NOT adjacent to a proxy-rendered chunk (i.e. against real terrain, unloaded chunks, or a neighbour
 * whose mesh has not arrived yet). The proxy surface is diagonal while real MC terrain is stepwise, so
 * without these walls the boundary shows triangle gaps into the void. Walls are offset slightly into the
 * chunk so they sit in front of the real chunk's own exposed side face (avoids coplanar z-fighting).
 * Interior proxy↔proxy seams stay wall-free and continuous (shared lattice heights). Flat colors: gray
 * terrain, darker blue-gray water.</p>
 */
public final class ProxyRenderer {
    private static final Logger LOGGER = LoggerFactory.getLogger("aerogel.render");
    /** how often wall masks / real-chunk takeover are re-checked (ms); uploads still happen immediately. */
    private static final long STATUS_INTERVAL_MS = 250;
    /**
     * First-time upload budget per sync. A full-window fill arrives as one large burst; creating hundreds of
     * GL buffers in a single frame stalled the driver (~112 ms for 625, 2026-09-12). We now stop once the
     * upload loop has spent this much wall time (plus a hard count cap as a safety), so the burst fills as
     * fast as the machine allows and the remainder spills to later frames.
     */
    private static final long UPLOAD_BUDGET_NANOS = 2_000_000L;
    private static final int MAX_UPLOADS_PER_SYNC = 512;
    /** reuse freed chunk buffers instead of create/delete churn (fast flight rotates chunks constantly). */
    private static final int MAX_BUFFER_POOL = 1024;
    /**
     * The void filler is ONE quad at y=0, sized to the view and centred under the camera by its draw
     * matrix — no per-chunk loop, no rebuild, no radius cap. (It used to be a per-chunk flat mesh out to
     * 2x render distance, re-emitted every 500 ms; at render distance 32 that was a 129x129 field with a
     * getChunkNow each — the join hitch and the reason for the radius cap.)
     */
    private static final int VOID_MIN_SIZE = 1024;
    private static final int MIN_Y = -64;
    private static final int SEA_LEVEL = 63;
    /** water proxy top sits this far below sea level (real surface is at seaLevel-0.125) so it does not
     *  z-fight the real water surface. */
    private static final float WATER_SINK = 0.15f;
    /**
     * Proxy draw radius (chunks). Default 0 = the vanilla render-distance option; a larger value lets the
     * proxy draw its coarse far band beyond the vanilla chunk range ({@code /aerogel detail draw <n>}).
     */
    private static volatile int configDrawRadius = 0;
    /** how many chunks of streamed meshes the client keeps in memory beyond the draw radius. */
    private static final int STORE_RADIUS = 256;
    /** how far below its final position a freshly arrived chunk starts (blocks). */
    private static final float RISE_DEPTH = 24.0f;
    /** how far a proxy drops before it is removed (blocks). */
    private static final float SINK_DEPTH = 96.0f;
    /** rise/sink duration (ms). */
    private static final long MORPH_MS = 450L;
    /** wall offset into the chunk, so seam walls sit in front of a real neighbour's own side faces */
    private static final float SEAM_EPS = 0.02f;
    private static final int WALL_WEST = 1;
    private static final int WALL_EAST = 2;
    private static final int WALL_NORTH = 4;
    private static final int WALL_SOUTH = 8;

    /**
     * Per-chunk GPU geometry plus its animation state. The geometry is uploaded once; the rise/sink is
     * just a vertical draw offset derived from these timestamps, so there is nothing to process per frame
     * beyond the draw itself. State is set once (on upload / on real-chunk takeover), never polled.
     */
    private static final class ChunkGpu {
        VertexBuffer buffer;
        /** the source grid array this buffer was baked from (reference identity -> detect updates). */
        short[] grid;
        /** source biome colour (-1 = none), for change detection. */
        int biome;
        /** source representative block-state id, for change detection / the texture. */
        int block;
        /** the tier the source grid was sampled at. */
        byte sourceTier;
        /** the grid actually baked (same array as {@link #grid}). */
        short[] renderGrid;
        /** bitmask of which seam walls are baked in (see WALL_*). */
        int wallMask;
        /** when the rise started (ms); 0 until first drawn. */
        long riseStart;
        /** when the sink started (ms); 0 while not being removed. */
        long removeStart;
    }

    private static final Long2ObjectOpenHashMap<ChunkGpu> gpu = new Long2ObjectOpenHashMap<>();
    private static final ArrayDeque<VertexBuffer> bufferPool = new ArrayDeque<>();
    /** block state -> top-face sprite (found once from the baked model, then reused). */
    private static final Map<BlockState, TextureAtlasSprite> spriteCache = new IdentityHashMap<>();
    private static TextureAtlasSprite waterSprite;
    /** default water tint when a land chunk has a sub-sea-level cell (vanilla overworld default). */
    private static final int WATER_TINT = 0x3F76E4;
    private static boolean uploadBacklog;
    private static long lastStatusCheck;
    /** the player's chunk at the last sync; a change forces a sync so newly-in-range meshes upload at once. */
    private static long lastCenter = Long.MIN_VALUE;
    private static final Matrix4f scratch = new Matrix4f();

    /** void filler: ONE quad at y=0, centred under the camera at draw time. */
    private static VertexBuffer voidQuad;
    private static int voidQuadSize;

    /** debug wireframe over the sampled lattice (light green), off by default; `/aerogel switch wireframe`. */
    private static VertexBuffer edgeBuffer;
    private static int edgeSegments;
    private static volatile boolean wireframe;
    private static boolean lastWireframe;

    // ---- client perf instrumentation (always on; one summary line per window, warns only on hitches) ----
    private static final long HITCH_MS = 50;
    private static final long PAUSE_MS = 1000;
    private static final int PERF_FRAMES = 200;

    private static long lastFrameNanos;
    private static int perfFrames;
    private static long perfFrameNanos;
    private static long perfRenderNanos;
    private static int perfUploads;
    private static long perfUploadNanos;
    private static int perfSyncs;
    private static long perfSyncNanos;
    private static int perfVoidBuilds;
    private static long perfVoidBuildNanos;
    private static int perfDraws;
    private static long perfAnimSum;
    private static int perfAnimFrames;
    private static long perfHandleNanos;
    private static int perfHandleChunks;
    private static long perfWorstFrameNanos;
    private static long lastGcCount = -1L;
    private static long lastGcTime = -1L;
    private static long lastUploadNanos;
    private static int lastUploadChunks;
    private static long lastSyncNanos;
    private static long lastVoidBuildNanos;
    private static int lastActiveAnims;

    private ProxyRenderer() {
    }

    public static void setWireframe(boolean on) {
        wireframe = on;
        AeroGelClientConfig.CFG.wireframe.set(on);
        AeroGelClientConfig.SPEC.save();
    }

    public static boolean isWireframe() {
        return wireframe;
    }

    public static int drawRadiusSetting() {
        return configDrawRadius;
    }

    /** 0 = use the vanilla render-distance option. */
    public static void setDrawRadius(int chunks) {
        configDrawRadius = Math.max(0, chunks);
        AeroGelClientConfig.CFG.drawRadius.set(configDrawRadius);
        AeroGelClientConfig.SPEC.save();
    }

    /** resolve a block state's top-face sprite (falling back to its particle icon), cached. */
    private static TextureAtlasSprite spriteFor(int blockId) {
        BlockState state = blockId > 0 ? Block.stateById(blockId) : Blocks.GRASS_BLOCK.defaultBlockState();
        return spriteCache.computeIfAbsent(state, s -> {
            Minecraft mc = Minecraft.getInstance();
            BakedModel model = mc.getModelManager().getBlockModelShaper().getBlockModel(s);
            List<BakedQuad> quads = model.getQuads(s, Direction.UP, RandomSource.create(42L));
            TextureAtlasSprite sprite = quads.isEmpty() ? model.getParticleIcon() : quads.get(0).getSprite();
            if (sprite == null) {
                sprite = mc.getModelManager().getMissingModel().getParticleIcon();
            }
            // diagnostics for the "black mesh" reports: a missing sprite samples black
            if (sprite == null) {
                LOGGER.warn("mesh texture: {} resolved to a null sprite -> will render black", s);
            } else if (sprite.contents().name().getPath().contains("missingno")) {
                LOGGER.warn("mesh texture: {} resolved to the MISSING sprite ({}) -> will render black",
                    s, sprite.contents().name());
            } else {
                LOGGER.info("mesh texture: {} -> {}", s, sprite.contents().name());
            }
            // leaves: use the opaque composite (grass in the cutout holes) so no underlay is needed
            if (sprite != null && BiomeColors.isLeaves(blockId)) {
                ResourceLocation composite = LeafCompositeSource.compositeId(sprite.contents().name());
                TextureAtlasSprite opaque = mc.getTextureAtlas(TextureAtlas.LOCATION_BLOCKS).apply(composite);
                if (opaque != null && composite.equals(opaque.contents().name())) {
                    return opaque;
                }
            }
            return sprite;
        });
    }

    private static TextureAtlasSprite waterSprite() {
        if (waterSprite == null) {
            waterSprite = spriteFor(Block.getId(Blocks.WATER.defaultBlockState()));
        }
        return waterSprite;
    }

    /** drop every GPU buffer instantly (dimension change / disconnect); no sink animation. */
    public static void clear() {
        for (ChunkGpu g : gpu.values()) {
            close(g);
        }
        gpu.clear();
        for (VertexBuffer pooled : bufferPool) {
            pooled.close();
        }
        bufferPool.clear();
        uploadBacklog = false;
        if (voidQuad != null) {
            voidQuad.close();
            voidQuad = null;
            voidQuadSize = 0;
        }
        if (edgeBuffer != null) {
            edgeBuffer.close();
            edgeBuffer = null;
            edgeSegments = 0;
        }
        lastStatusCheck = 0L;
    }

    public static void render(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) {
            return;
        }
        // frame-interval / hitch accounting happens before any work so a stall in the PREVIOUS frame is
        // attributed to what that frame did (sync, uploads, void, payload handling, morph count).
        long frameNanos = System.nanoTime();
        long dt = lastFrameNanos == 0L ? 0L : frameNanos - lastFrameNanos;
        long handleNanos = ClientMeshStore.consumeHandleNanos();
        int handleChunks = ClientMeshStore.consumeHandleChunks();
        perfHandleNanos += handleNanos;
        perfHandleChunks += handleChunks;
        if (dt > 0L && dt < PAUSE_MS * 1_000_000L) {
            perfFrameNanos += dt;
            perfFrames++;
            if (dt > perfWorstFrameNanos) {
                perfWorstFrameNanos = dt;
            }
            if (dt >= HITCH_MS * 1_000_000L) {
                LOGGER.warn("render hitch: {} ms frame | prev sync {} ms ({} uploads), void {} ms, morphs {}, store-handle {} ms/{} chunks",
                    ms(dt), ms(lastSyncNanos), lastUploadChunks, ms(lastVoidBuildNanos), lastActiveAnims,
                    ms(handleNanos), handleChunks);
            }
        }
        lastFrameNanos = frameNanos;
        lastSyncNanos = 0L;
        lastUploadNanos = 0L;
        lastUploadChunks = 0;
        lastVoidBuildNanos = 0L;
        long renderStart = frameNanos;
        try {
            Minecraft mc = Minecraft.getInstance();
            ClientLevel level = mc.level;
            if (level == null || mc.player == null) {
                return;
            }
            long now = System.currentTimeMillis();
            ChunkPos center = mc.player.chunkPosition();
            // uploads must be immediate so a fresh mesh rises on arrival; the periodic pass just catches
            // real-chunk takeovers and neighbour wall changes. A chunk-cross also forces a sync: meshes that
            // scroll into the draw radius were stored earlier and would otherwise wait up to STATUS_INTERVAL_MS
            // (the transient "missing rows" perpendicular to travel).
            boolean statusDue = now - lastStatusCheck >= STATUS_INTERVAL_MS;
            boolean moved = center.toLong() != lastCenter;
            lastCenter = center.toLong();
            if (ClientFeatures.sync
                && (ClientMeshStore.isDirty() || statusDue || moved || uploadBacklog || wireframe != lastWireframe)) {
                ClientMeshStore.consumeDirty();
                lastStatusCheck = now;
                long t0 = System.nanoTime();
                syncChunks(level, center, drawRadius(), now);
                lastSyncNanos = System.nanoTime() - t0;
                perfSyncs++;
                perfSyncNanos += lastSyncNanos;
                if (wireframe) {
                    rebuildEdges(level, center, drawRadius());
                } else if (edgeBuffer != null) {
                    edgeBuffer.close();
                    edgeBuffer = null;
                    edgeSegments = 0;
                }
                lastWireframe = wireframe;
            }
            if (voidQuad == null) {
                long t0 = System.nanoTime();
                ensureVoidQuad(drawRadius());
                lastVoidBuildNanos = System.nanoTime() - t0;
                perfVoidBuilds++;
                perfVoidBuildNanos += lastVoidBuildNanos;
            }
            if (gpu.isEmpty() && voidQuad == null) {
                return;
            }
            // NOTE: at this stage NeoForge passes a null PoseStack -> getPoseStack() is an IDENTITY stack
            // (1.21.1 renders terrain camera-relative via the CHUNK_OFFSET uniform). The camera ROTATION lives
            // in getModelViewMatrix(); we append the camera translation so world-space verts land correctly:
            // view = R * (v - cam).
            var camPos = event.getCamera().getPosition();
            Matrix4f base = new Matrix4f(event.getModelViewMatrix())
                .translate((float) -camPos.x, (float) -camPos.y, (float) -camPos.z);
            RenderSystem.setShader(GameRenderer::getPositionColorShader);
            RenderSystem.disableCull();
            RenderSystem.disableBlend();
            if (ClientFeatures.voidQuad && voidQuad != null) {
                scratch.set(base);
                scratch.translate((float) camPos.x, 0.0f, (float) camPos.z);
                voidQuad.bind();
                voidQuad.drawWithShader(scratch, event.getProjectionMatrix(), GameRenderer.getPositionColorShader());
            }
            // proxy chunks are textured: bind the block atlas and use the position_tex_color shader
            RenderSystem.setShaderTexture(0, TextureAtlas.LOCATION_BLOCKS);
            // proxy terrain is lit by sky light 15 only (no block light): multiply the baked biome tint by the
            // current day/night sky brightness. ClientLevel.getSkyDarken is the brightness factor (1 day,
            // ~0.2 night). Applied per frame so it tracks the sun.
            float sky = Math.clamp(level.getSkyDarken(1.0f), 0.2f, 1.0f);
            RenderSystem.setShaderColor(sky, sky, sky, 1.0f);
            int activeAnims = 0;
            for (Long2ObjectMap.Entry<ChunkGpu> entry : gpu.long2ObjectEntrySet()) {
                ChunkGpu g = entry.getValue();
                if (g.buffer == null) {
                    continue;
                }
                float off = ClientFeatures.anim ? offset(g, now) : 0.0f;
                if (off != 0.0f) {
                    activeAnims++;
                }
                scratch.set(base);
                if (off != 0.0f) {
                    scratch.translate(0.0f, off, 0.0f);
                }
                g.buffer.bind();
                g.buffer.drawWithShader(scratch, event.getProjectionMatrix(), GameRenderer.getPositionTexColorShader());
                perfDraws++;
            }
            RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);
            lastActiveAnims = activeAnims;
            perfAnimSum += activeAnims;
            if (activeAnims > 0) {
                perfAnimFrames++;
            }
            if (wireframe && edgeBuffer != null && edgeSegments > 0) {
                RenderSystem.lineWidth(2.0f);
                edgeBuffer.bind();
                edgeBuffer.drawWithShader(base, event.getProjectionMatrix(), GameRenderer.getPositionColorShader());
            }
            VertexBuffer.unbind();
            RenderSystem.enableCull();
            RenderSystem.enableBlend();
        } finally {
            perfRenderNanos += System.nanoTime() - renderStart;
            if (perfFrames >= PERF_FRAMES) {
                logPerf();
            }
        }
    }

    private static void logPerf() {
        double avgFrame = perfFrameNanos / 1e6 / perfFrames;
        long gcCount = 0L;
        long gcTime = 0L;
        for (GarbageCollectorMXBean bean : ManagementFactory.getGarbageCollectorMXBeans()) {
            if (bean.getCollectionCount() > 0) {
                gcCount += bean.getCollectionCount();
            }
            if (bean.getCollectionTime() > 0) {
                gcTime += bean.getCollectionTime();
            }
        }
        long gcDelta = lastGcCount < 0L ? 0L : gcCount - lastGcCount;
        long gcMsDelta = lastGcTime < 0L ? 0L : gcTime - lastGcTime;
        lastGcCount = gcCount;
        lastGcTime = gcTime;
        LOGGER.info("render perf: {} ms/frame ({} fps, worst {} ms), render() {} ms avg | syncs {}/{} frames ({} ms avg), {} uploads ({} ms avg) | {} draws/frame | void quad {} builds ({} ms avg) | morphs {} avg, {}% frames | handle {} ms/{} chunks | gc {}/{} ms | store {}",
            fmt(avgFrame), fmt(avgFrame > 0 ? 1000.0 / avgFrame : 0), fmt(perfWorstFrameNanos / 1e6),
            fmt(perfRenderNanos / 1e6 / perfFrames),
            perfSyncs, perfFrames, fmt(perfSyncs > 0 ? perfSyncNanos / 1e6 / perfSyncs : 0),
            perfUploads, fmt(perfUploads > 0 ? perfUploadNanos / 1e6 / perfUploads : 0),
            perfDraws / Math.max(1, perfFrames),
            perfVoidBuilds, fmt(perfVoidBuilds > 0 ? perfVoidBuildNanos / 1e6 / perfVoidBuilds : 0),
            fmt(perfAnimSum / (double) perfFrames), perfAnimFrames * 100 / Math.max(1, perfFrames),
            fmt(perfHandleNanos / 1e6), perfHandleChunks, gcDelta, gcMsDelta, ClientMeshStore.size());
        perfFrames = 0;
        perfFrameNanos = 0L;
        perfRenderNanos = 0L;
        perfUploads = 0;
        perfUploadNanos = 0L;
        perfSyncs = 0;
        perfSyncNanos = 0L;
        perfVoidBuilds = 0;
        perfVoidBuildNanos = 0L;
        perfDraws = 0;
        perfAnimSum = 0L;
        perfAnimFrames = 0;
        perfHandleNanos = 0L;
        perfHandleChunks = 0;
        perfWorstFrameNanos = 0L;
    }

    /**
     * Brings the GPU buffers in line with the mesh store: upload new/changed chunks, re-bake seam walls
     * when a neighbour's proxy status flips, sink proxies taken over by real chunks, drop dead buffers.
     */
    private static void syncChunks(ClientLevel level, ChunkPos center, int radius, long now) {
        // Keep meshes well beyond the draw radius. The server streams its own (possibly larger) radius and
        // will NOT resend a chunk it thinks is sent, so pruning at the draw radius leaves permanent holes
        // around the player ("chunks generate ahead but not around me"). GPU buffers are still freed at the
        // draw radius below; this only keeps the cheap height grids for when the chunk scrolls back into view.
        rebuildVisibleChunks();
        ClientMeshStore.prune(center, Math.max(radius + 2, STORE_RADIUS));
        uploadBacklog = false;
        long uploadStart = System.nanoTime();
        int uploads = 0;
        // a real chunk only covers the vanilla render distance; beyond it the client may still hold the
        // chunk in its cache (so getChunkNow != null) while drawing nothing, so the proxy must stay visible
        int renderDistance = Minecraft.getInstance().options.renderDistance().get();
        var git = gpu.long2ObjectEntrySet().iterator();
        while (git.hasNext()) {
            Long2ObjectMap.Entry<ChunkGpu> e = git.next();
            if (dist(center, e.getLongKey()) > radius + 2) {
                close(e.getValue());
                git.remove();
            }
        }
        var it = ClientMeshStore.meshes().long2ObjectEntrySet().iterator();
        while (it.hasNext()) {
            Long2ObjectMap.Entry<ClientMeshStore.Mesh> e = it.next();
            long pos = e.getLongKey();
            if (dist(center, pos) > radius) {
                continue;
            }
            ClientMeshStore.Mesh mesh = e.getValue();
            ChunkGpu g = gpu.get(pos);
            if (isRendered(level, pos) && dist(center, pos) <= renderDistance) {
                // a real chunk took over -> sink/hide the proxy, but KEEP the mesh in the store: the real
                // chunk only covers the client render distance, so at the border of a generated patch it
                // leaves view again and the proxy must be drawable. The server will not resend a chunk it
                // already sent, so dropping the mesh here turns that border into a permanent void.
                if (g == null) {
                    continue; // nothing uploaded; the mesh stays for when the real chunk leaves view
                }
                if (g.removeStart == 0L) {
                    g.removeStart = now;
                }
                if (progress(g.removeStart, now) >= 1.0f) {
                    close(g);
                    gpu.remove(pos);
                }
                continue;
            }
            if (g != null && g.removeStart != 0L) {
                // the real chunk left view again before the sink finished -> bring the proxy back
                g.removeStart = 0L;
                g.riseStart = now;
            }
            if (g == null) {
                if (overUploadBudget(uploads, uploadStart)) {
                    uploadBacklog = true;
                    continue;
                }
                g = new ChunkGpu();
                g.riseStart = now;
                gpu.put(pos, g);
                uploadChunk(level, pos, mesh, wallMask(level, pos), g);
                uploads++;
            } else {
                int mask = wallMask(level, pos);
                if (g.grid != mesh.grid() || g.biome != mesh.biome() || g.block != mesh.block() || g.wallMask != mask) {
                    if (overUploadBudget(uploads, uploadStart)) {
                        uploadBacklog = true;
                        continue;
                    }
                    uploadChunk(level, pos, mesh, mask, g);
                    uploads++;
                }
            }
        }
        // buffers whose mesh vanished from the store (prune / dimension clear): sink then drop
        git = gpu.long2ObjectEntrySet().iterator();
        while (git.hasNext()) {
            Long2ObjectMap.Entry<ChunkGpu> e = git.next();
            if (ClientMeshStore.has(e.getLongKey())) {
                continue;
            }
            ChunkGpu g = e.getValue();
            if (g.removeStart == 0L) {
                g.removeStart = now;
            }
            if (progress(g.removeStart, now) >= 1.0f) {
                close(g);
                git.remove();
            }
        }
    }

    /** true once this sync's first-time uploads have used their time/count budget (at least one always runs). */
    private static boolean overUploadBudget(int uploads, long start) {
        return uploads >= MAX_UPLOADS_PER_SYNC
            || (uploads > 0 && System.nanoTime() - start >= UPLOAD_BUDGET_NANOS);
    }

    /** the vertical draw offset for a chunk: 0 when settled, negative while rising or sinking. */
    private static float offset(ChunkGpu g, long now) {
        if (g.removeStart != 0L) {
            return -SINK_DEPTH * smooth(progress(g.removeStart, now));
        }
        if (g.riseStart != 0L) {
            float t = progress(g.riseStart, now);
            if (t < 1.0f) {
                return -RISE_DEPTH * (1.0f - smooth(t));
            }
        }
        return 0.0f;
    }

    private static float progress(long start, long now) {
        return Math.min(1.0f, (now - start) / (float) MORPH_MS);
    }

    private static float smooth(float t) {
        return t * t * (3.0f - 2.0f * t);
    }

    private static void uploadChunk(ClientLevel level, long pos, ClientMeshStore.Mesh mesh, int wallMask, ChunkGpu g) {
        long t0 = System.nanoTime();
        short[] renderGrid = mesh.grid();
        TextureAtlasSprite sprite = spriteFor(mesh.block());
        int bx = (ChunkPos.getX(pos) << 4) + 8;
        int bz = (ChunkPos.getZ(pos) << 4) + 8;
        int tint = BiomeColors.tint(level, mesh.biome(), mesh.block(), bx, bz);
        BufferBuilder builder = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
        emitChunk(builder, pos, mesh.topology(), renderGrid, wallMask, tint, sprite);
        MeshData data = builder.buildOrThrow();
        VertexBuffer target = g.buffer;
        if (target == null) {
            target = bufferPool.poll();
            if (target == null) {
                target = new VertexBuffer(VertexBuffer.Usage.STATIC);
            }
        }
        target.bind();
        target.upload(data);
        VertexBuffer.unbind();
        g.buffer = target;
        g.grid = mesh.grid();
        g.biome = mesh.biome();
        g.block = mesh.block();
        g.sourceTier = mesh.topology();
        g.renderGrid = renderGrid;
        g.wallMask = wallMask;
        lastUploadNanos = System.nanoTime() - t0;
        perfUploads++;
        perfUploadNanos += lastUploadNanos;
        lastUploadChunks++;
    }

    /**
     * Build the single void-filler quad once (rebuilt only if the view size changes). It is centred at
     * the origin and translated under the camera by its draw matrix, so it never needs rebuilding as the
     * player moves.
     */
    private static void ensureVoidQuad(int renderDistance) {
        int size = Math.max(renderDistance * 32, VOID_MIN_SIZE);
        if (voidQuad != null && voidQuadSize == size) {
            return;
        }
        float h = size * 0.5f;
        float r = 0.30f;
        float g = 0.32f;
        float b = 0.36f;
        BufferBuilder builder = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        builder.addVertex(-h, 0.0f, -h).setColor(r, g, b, 1.0f);
        builder.addVertex(-h, 0.0f, h).setColor(r, g, b, 1.0f);
        builder.addVertex(h, 0.0f, h).setColor(r, g, b, 1.0f);
        builder.addVertex(h, 0.0f, -h).setColor(r, g, b, 1.0f);
        MeshData data = builder.buildOrThrow();
        VertexBuffer target = voidQuad;
        if (target == null) {
            target = new VertexBuffer(VertexBuffer.Usage.STATIC);
        }
        target.bind();
        target.upload(data);
        VertexBuffer.unbind();
        voidQuad = target;
        voidQuadSize = size;
    }

    /** debug wireframe: one DEBUG_LINES buffer over every visible proxy chunk's lattice. */
    private static void rebuildEdges(ClientLevel level, ChunkPos center, int radius) {
        BufferBuilder builder = Tesselator.getInstance().begin(VertexFormat.Mode.DEBUG_LINES, DefaultVertexFormat.POSITION_COLOR);
        int emitted = 0;
        for (Long2ObjectMap.Entry<ChunkGpu> e : gpu.long2ObjectEntrySet()) {
            long pos = e.getLongKey();
            ChunkGpu g = e.getValue();
            short[] grid = g.grid;
            if (grid == null || dist(center, pos) > radius) {
                continue;
            }
            emitEdges(builder, pos, g.sourceTier, grid);
            emitted++;
        }
        if (emitted == 0) {
            if (edgeBuffer != null) {
                edgeBuffer.close();
            }
            edgeBuffer = null;
            edgeSegments = 0;
            return;
        }
        MeshData data = builder.buildOrThrow();
        VertexBuffer target = edgeBuffer;
        if (target == null) {
            target = new VertexBuffer(VertexBuffer.Usage.STATIC);
        }
        target.bind();
        target.upload(data);
        VertexBuffer.unbind();
        edgeBuffer = target;
        edgeSegments = data.drawState().vertexCount() / 2;
    }

    /** release a chunk's buffer back to the pool (or close it if the pool is full). */
    private static void close(ChunkGpu g) {
        if (g.buffer != null) {
            if (bufferPool.size() < MAX_BUFFER_POOL) {
                bufferPool.add(g.buffer);
            } else {
                g.buffer.close();
            }
            g.buffer = null;
        }
    }

    /** the radius the proxy layer draws/uploads: max(vanilla option, configured override). */
    static int drawRadius() {
        return Math.max(Minecraft.getInstance().options.renderDistance().get(), configDrawRadius);
    }

    private static int dist(ChunkPos center, long pos) {
        return Math.max(Math.abs(ChunkPos.getX(pos) - center.x), Math.abs(ChunkPos.getZ(pos) - center.z));
    }

    private static boolean isReal(ClientLevel level, long pos) {
        LevelChunk chunk = level.getChunkSource().getChunkNow(ChunkPos.getX(pos), ChunkPos.getZ(pos));
        return chunk != null && !ProxyChunkUtil.isAirStub(chunk);
    }

    /**
     * True when a real client chunk is actually compiled by the vanilla renderer. The proxy must not hide
     * merely because the chunk arrived: between arrival and the async section compile there is no drawn
     * terrain, so hiding on arrival leaves a gap at the real/proxy boundary.
     */
    /**
     * True when the vanilla renderer is actually drawing this chunk this frame. A chunk can be real and
     * compiled yet still be culled (frustum or occlusion), so the only accurate signal is the renderer's
     * drawn-section list. When this is false the proxy must cover the chunk, or the boundary loses a chunk.
     */
    static boolean isRendered(ClientLevel level, long pos) {
        return isReal(level, pos) && visibleChunks.contains(pos);
    }

    /** chunk positions with at least one section in the renderer's drawn set; rebuilt each sync. */
    private static final LongOpenHashSet visibleChunks = new LongOpenHashSet();

    private static void rebuildVisibleChunks() {
        visibleChunks.clear();
        var visible = ((LevelRendererAccessor) Minecraft.getInstance().levelRenderer).aerogel$visibleSections();
        if (visible == null) {
            return;
        }
        for (SectionRenderDispatcher.RenderSection section : visible) {
            BlockPos origin = section.getOrigin();
            visibleChunks.add(ChunkPos.asLong(SectionPos.blockToSectionCoord(origin.getX()),
                SectionPos.blockToSectionCoord(origin.getZ())));
        }
    }

    /** bitmask of borders needing a wall: a border is walled unless the neighbour is itself a rendered proxy. */
    private static int wallMask(ClientLevel level, long pos) {
        int cx = ChunkPos.getX(pos);
        int cz = ChunkPos.getZ(pos);
        int mask = 0;
        if (!renderedAsProxy(cx - 1, cz)) {
            mask |= WALL_WEST;
        }
        if (!renderedAsProxy(cx + 1, cz)) {
            mask |= WALL_EAST;
        }
        if (!renderedAsProxy(cx, cz - 1)) {
            mask |= WALL_NORTH;
        }
        if (!renderedAsProxy(cx, cz + 1)) {
            mask |= WALL_SOUTH;
        }
        return mask;
    }

    /**
     * True when the neighbour chunk is itself drawn as proxy (we have its mesh): its top is continuous with
     * ours across the shared lattice border, so no seam wall is needed. Real chunks, unloaded chunks and
     * missing meshes all return false -> the wall is drawn.
     *
     * <p>Keyed off the mesh store, not the client {@code LevelChunk}: when the server skips sending stub
     * chunks there is no client chunk at proxy positions at all.</p>
     */
    private static boolean renderedAsProxy(int cx, int cz) {
        return ClientMeshStore.has(ChunkPos.asLong(cx, cz));
    }

    /**
     * Build one chunk's geometry at the exact tier the server sampled it (so the wireframe and the textures
     * always describe the same points). FIVE is a centre fan; NINE/25/81 are square grids.
     */
    private static void emitChunk(BufferBuilder buf, long pos, byte tier, short[] grid, int wallMask,
            int color, TextureAtlasSprite sprite) {
        int x0 = ChunkPos.getX(pos) << 4;
        int z0 = ChunkPos.getZ(pos) << 4;
        if (tier == ProxyTopology.FIVE) {
            emitTopFan(buf, x0, z0, grid, color, sprite);
        } else {
            emitTopGrid(buf, x0, z0, ProxyTopology.side(tier), ProxyTopology.spacing(tier), grid, color, sprite);
        }
        // walls on every border NOT shared with a proxy-rendered chunk: real terrain, unloaded chunks, and
        // not-yet-streamed neighbours all get closed down to bedrock (the diagonal proxy top would
        // otherwise leave gaps against stepwise real terrain). Offset inward so the wall is in front of a
        // real neighbour's own exposed side face (coplanar -> z-fighting).
        boolean west = (wallMask & WALL_WEST) != 0;
        boolean east = (wallMask & WALL_EAST) != 0;
        boolean north = (wallMask & WALL_NORTH) != 0;
        boolean south = (wallMask & WALL_SOUTH) != 0;
        if (tier == ProxyTopology.FIVE) {
            if (west) {
                wallZ(buf, x0 + SEAM_EPS, z0, z0 + 16, grid[0], grid[1], 0.6f, sprite);
            }
            if (east) {
                wallZ(buf, x0 + 16 - SEAM_EPS, z0, z0 + 16, grid[2], grid[3], 0.6f, sprite);
            }
            if (north) {
                wallX(buf, x0, x0 + 16, z0 + SEAM_EPS, grid[0], grid[2], 0.8f, sprite);
            }
            if (south) {
                wallX(buf, x0, x0 + 16, z0 + 16 - SEAM_EPS, grid[1], grid[3], 0.8f, sprite);
            }
            return;
        }
        int side = ProxyTopology.side(tier);
        int spacing = ProxyTopology.spacing(tier);
        if (west) {
            for (int k = 0; k < side - 1; k++) {
                wallZ(buf, x0 + SEAM_EPS, z0 + k * spacing, z0 + (k + 1) * spacing,
                    grid[k], grid[k + 1], 0.6f, sprite);
            }
        }
        if (east) {
            int row = (side - 1) * side;
            for (int k = 0; k < side - 1; k++) {
                wallZ(buf, x0 + 16 - SEAM_EPS, z0 + k * spacing, z0 + (k + 1) * spacing,
                    grid[row + k], grid[row + k + 1], 0.6f, sprite);
            }
        }
        if (north) {
            for (int i = 0; i < side - 1; i++) {
                wallX(buf, x0 + i * spacing, x0 + (i + 1) * spacing, z0 + SEAM_EPS,
                    grid[i * side], grid[(i + 1) * side], 0.8f, sprite);
            }
        }
        if (south) {
            for (int i = 0; i < side - 1; i++) {
                wallX(buf, x0 + i * spacing, x0 + (i + 1) * spacing, z0 + 16 - SEAM_EPS,
                    grid[i * side + side - 1], grid[(i + 1) * side + side - 1], 0.8f, sprite);
            }
        }
    }

    /** FIVE: four triangles fanning from the centre, one sprite stretched over the 16x16 chunk. */
    private static void emitTopFan(BufferBuilder buf, int x0, int z0, short[] grid, int color,
            TextureAtlasSprite sprite) {
        fanLayer(buf, x0, z0, grid, color, sprite);
    }

    private static void fanLayer(BufferBuilder buf, int x0, int z0, short[] grid, int color,
            TextureAtlasSprite sprite) {
        float tr = 1.0f;
        float tg = 1.0f;
        float tb = 1.0f;
        if (color >= 0) {
            tr = ((color >> 16) & 0xFF) / 255.0f;
            tg = ((color >> 8) & 0xFF) / 255.0f;
            tb = (color & 0xFF) / 255.0f;
        }
        TextureAtlasSprite water = waterSprite();
        float wr = ((WATER_TINT >> 16) & 0xFF) / 255.0f;
        float wg = ((WATER_TINT >> 8) & 0xFF) / 255.0f;
        float wb = (WATER_TINT & 0xFF) / 255.0f;
        int[][] tris = { { 0, 2, 4 }, { 2, 3, 4 }, { 3, 1, 4 }, { 1, 0, 4 } };
        for (int[] t : tris) {
            boolean isWater = Math.min(Math.min(grid[t[0]], grid[t[1]]), grid[t[2]]) <= SEA_LEVEL;
            float bright = isWater ? 1.0f : triBright(grid, t);
            float r = (isWater ? wr : tr) * bright;
            float g = (isWater ? wg : tg) * bright;
            float b = (isWater ? wb : tb) * bright;
            TextureAtlasSprite s = isWater ? water : sprite;
            float u0 = s.getU0();
            float u1 = s.getU1();
            float v0 = s.getV0();
            float v1 = s.getV1();
            for (int idx : t) {
                fanVertex(buf, x0, z0, grid, idx, u0, u1, v0, v1, r, g, b);
            }
            // degenerate 4th vertex: the buffer is QUADS, so a triangle is A,B,C,C
            fanVertex(buf, x0, z0, grid, t[2], u0, u1, v0, v1, r, g, b);
        }
    }

    private static void fanVertex(BufferBuilder buf, int x0, int z0, short[] grid, int idx,
            float u0, float u1, float v0, float v1, float r, float g, float b) {
        float lx = ProxyTopology.localX(ProxyTopology.FIVE, idx);
        float lz = ProxyTopology.localZ(ProxyTopology.FIVE, idx);
        buf.addVertex(x0 + lx, waterY(grid[idx]), z0 + lz)
            .setUv(u0 + (lx / 16.0f) * (u1 - u0), v0 + (lz / 16.0f) * (v1 - v0))
            .setColor(r, g, b, 1.0f);
    }

    /**
     * Per-vertex water sink: only vertices sitting at sea level drop, so a flat ocean lowers as a whole
     * while a shoreline cell keeps its land-side vertices at the true terrain height and still meets the
     * non-water neighbour. The old code sank every vertex of a water-classified cell, which notched the
     * shore.
     */
    private static float waterY(float h) {
        return h - (h <= SEA_LEVEL ? WATER_SINK : 0.0f);
    }

    /** per-triangle slope shading for the FIVE fan (normal from the three lattice points). */
    private static float triBright(short[] grid, int[] t) {
        float ax = ProxyTopology.localX(ProxyTopology.FIVE, t[1]) - ProxyTopology.localX(ProxyTopology.FIVE, t[0]);
        float ay = grid[t[1]] - grid[t[0]];
        float az = ProxyTopology.localZ(ProxyTopology.FIVE, t[1]) - ProxyTopology.localZ(ProxyTopology.FIVE, t[0]);
        float bx = ProxyTopology.localX(ProxyTopology.FIVE, t[2]) - ProxyTopology.localX(ProxyTopology.FIVE, t[0]);
        float by = grid[t[2]] - grid[t[0]];
        float bz = ProxyTopology.localZ(ProxyTopology.FIVE, t[2]) - ProxyTopology.localZ(ProxyTopology.FIVE, t[0]);
        float nx = ay * bz - az * by;
        float ny = az * bx - ax * bz;
        float nz = ax * by - ay * bx;
        if (ny < 0.0f) {
            nx = -nx;
            ny = -ny;
            nz = -nz;
        }
        float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (len == 0.0f) {
            return 1.0f;
        }
        float dot = (nx * 0.35f + ny * 0.85f + nz * 0.25f) / len;
        return Mth.clamp(0.62f + 0.38f * dot, 0.55f, 1.0f);
    }

    /**
     * Slanted textured quads over the grid: the chunk's representative block top sprite, tinted by the
     * biome colour, with slope shading. Water cells use the water sprite + default water tint.
     */
    private static void emitTopGrid(BufferBuilder buf, int x0, int z0, int side, int spacing, short[] grid,
            int color, TextureAtlasSprite sprite) {
        gridLayer(buf, x0, z0, side, spacing, grid, color, sprite);
    }

    private static void gridLayer(BufferBuilder buf, int x0, int z0, int side, int spacing, short[] grid,
            int color, TextureAtlasSprite sprite) {
        float tr = 1.0f;
        float tg = 1.0f;
        float tb = 1.0f;
        if (color >= 0) {
            tr = ((color >> 16) & 0xFF) / 255.0f;
            tg = ((color >> 8) & 0xFF) / 255.0f;
            tb = (color & 0xFF) / 255.0f;
        }
        TextureAtlasSprite water = waterSprite();
        float wr = ((WATER_TINT >> 16) & 0xFF) / 255.0f;
        float wg = ((WATER_TINT >> 8) & 0xFF) / 255.0f;
        float wb = (WATER_TINT & 0xFF) / 255.0f;
        for (int i = 0; i < side - 1; i++) {
            for (int k = 0; k < side - 1; k++) {
                float h00 = grid[i * side + k];
                float h10 = grid[(i + 1) * side + k];
                float h11 = grid[(i + 1) * side + k + 1];
                float h01 = grid[i * side + k + 1];
                boolean isWater = Math.min(Math.min(h00, h10), Math.min(h01, h11)) <= SEA_LEVEL;
                float bright = isWater ? 1.0f : slopeBright(h00, h10, h01, h11, spacing);
                float r = (isWater ? wr : tr) * bright;
                float g = (isWater ? wg : tg) * bright;
                float b = (isWater ? wb : tb) * bright;
                TextureAtlasSprite s = isWater ? water : sprite;
                topQuad(buf, x0 + i * spacing, z0 + k * spacing, spacing,
                    waterY(h00), waterY(h10), waterY(h11), waterY(h01), s, r, g, b);
            }
        }
    }

    private static void topQuad(BufferBuilder buf, float x0, float z0, float w,
            float h00, float h10, float h11, float h01, TextureAtlasSprite s, float r, float g, float b) {
        float u0 = s.getU0();
        float u1 = s.getU1();
        float v0 = s.getV0();
        float v1 = s.getV1();
        buf.addVertex(x0, h00, z0).setUv(u0, v0).setColor(r, g, b, 1.0f);
        buf.addVertex(x0, h01, z0 + w).setUv(u0, v1).setColor(r, g, b, 1.0f);
        buf.addVertex(x0 + w, h11, z0 + w).setUv(u1, v1).setColor(r, g, b, 1.0f);
        buf.addVertex(x0 + w, h10, z0).setUv(u1, v0).setColor(r, g, b, 1.0f);
    }

    private static float slopeBright(float h00, float h10, float h01, float h11, float width) {
        float dhx = (h10 + h11 - h00 - h01) / (2.0f * width);
        float dhz = (h01 + h11 - h00 - h10) / (2.0f * width);
        float nLen = (float) Math.sqrt(dhx * dhx + 1.0 + dhz * dhz);
        float dot = (-dhx * 0.35f + 0.85f - dhz * 0.25f) / nLen;
        return Mth.clamp(0.62f + 0.38f * dot, 0.55f, 1.0f);
    }

    /**
     * Debug wireframe: the <b>sampled</b> points at the tier the server actually sent (so
     * {@code /aerogel detail near five} shows the five-point lattice, not the upsampled render grid).
     * Light green, built into a DEBUG_LINES buffer.
     */
    private static void emitEdges(BufferBuilder edges, long pos, byte tier, short[] grid) {
        int x0 = ChunkPos.getX(pos) << 4;
        int z0 = ChunkPos.getZ(pos) << 4;
        float lift = 0.03f;
        if (tier == ProxyTopology.FIVE) {
            edge(edges, x0, grid[0] + lift, z0, x0 + 16, grid[2] + lift, z0);
            edge(edges, x0 + 16, grid[2] + lift, z0, x0 + 16, grid[3] + lift, z0 + 16);
            edge(edges, x0 + 16, grid[3] + lift, z0 + 16, x0, grid[1] + lift, z0 + 16);
            edge(edges, x0, grid[1] + lift, z0 + 16, x0, grid[0] + lift, z0);
            edge(edges, x0 + 8, grid[4] + lift, z0 + 8, x0, grid[0] + lift, z0);
            edge(edges, x0 + 8, grid[4] + lift, z0 + 8, x0 + 16, grid[2] + lift, z0);
            edge(edges, x0 + 8, grid[4] + lift, z0 + 8, x0 + 16, grid[3] + lift, z0 + 16);
            edge(edges, x0 + 8, grid[4] + lift, z0 + 8, x0, grid[1] + lift, z0 + 16);
            return;
        }
        int side = ProxyTopology.side(tier);
        int spacing = ProxyTopology.spacing(tier);
        for (int i = 0; i < side; i++) {
            for (int k = 0; k < side - 1; k++) {
                edge(edges, x0 + i * spacing, grid[i * side + k] + lift, z0 + k * spacing,
                    x0 + i * spacing, grid[i * side + k + 1] + lift, z0 + (k + 1) * spacing);
            }
        }
        for (int k = 0; k < side; k++) {
            for (int i = 0; i < side - 1; i++) {
                edge(edges, x0 + i * spacing, grid[i * side + k] + lift, z0 + k * spacing,
                    x0 + (i + 1) * spacing, grid[(i + 1) * side + k] + lift, z0 + k * spacing);
            }
        }
    }

    private static void edge(BufferBuilder b, float x1, float y1, float z1, float x2, float y2, float z2) {
        b.addVertex(x1, y1, z1).setColor(0.4f, 1.0f, 0.4f, 1.0f);
        b.addVertex(x2, y2, z2).setColor(0.4f, 1.0f, 0.4f, 1.0f);
    }

    /** wall at fixed x, spanning z from zA to zB, top edge from height hA (at zA) to hB (at zB) */
    private static void wallZ(BufferBuilder buf, float x, int zA, int zB, float hA, float hB, float bright,
            TextureAtlasSprite s) {
        float r;
        float g;
        float b;
        if (Math.min(hA, hB) <= SEA_LEVEL) {
            r = 0.22f * bright;
            g = 0.31f * bright;
            b = 0.38f * bright;
        } else {
            r = 0.45f * bright;
            g = 0.45f * bright;
            b = 0.47f * bright;
        }
        float u0 = s.getU0();
        float u1 = s.getU1();
        float v0 = s.getV0();
        float v1 = s.getV1();
        buf.addVertex(x, hA, zA).setUv(u0, v0).setColor(r, g, b, 1.0f);
        buf.addVertex(x, hB, zB).setUv(u1, v0).setColor(r, g, b, 1.0f);
        buf.addVertex(x, MIN_Y, zB).setUv(u1, v1).setColor(r, g, b, 1.0f);
        buf.addVertex(x, MIN_Y, zA).setUv(u0, v1).setColor(r, g, b, 1.0f);
    }

    /** wall at fixed z, spanning x from xA to xB, top edge from height hA (at xA) to hB (at xB) */
    private static void wallX(BufferBuilder buf, int xA, int xB, float z, float hA, float hB, float bright,
            TextureAtlasSprite s) {
        float r;
        float g;
        float b;
        if (Math.min(hA, hB) <= SEA_LEVEL) {
            r = 0.22f * bright;
            g = 0.31f * bright;
            b = 0.38f * bright;
        } else {
            r = 0.45f * bright;
            g = 0.45f * bright;
            b = 0.47f * bright;
        }
        float u0 = s.getU0();
        float u1 = s.getU1();
        float v0 = s.getV0();
        float v1 = s.getV1();
        buf.addVertex(xA, hA, z).setUv(u0, v0).setColor(r, g, b, 1.0f);
        buf.addVertex(xB, hB, z).setUv(u1, v0).setColor(r, g, b, 1.0f);
        buf.addVertex(xB, MIN_Y, z).setUv(u1, v1).setColor(r, g, b, 1.0f);
        buf.addVertex(xA, MIN_Y, z).setUv(u0, v1).setColor(r, g, b, 1.0f);
    }

    private static String ms(long nanos) {
        return fmt(nanos / 1e6);
    }

    private static String fmt(double v) {
        return String.format("%.2f", v);
    }
}
