package dev.aerogel.server;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.EmptyLevelChunk;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Headless smoke test of {@link PromotionManager} (flag file: &lt;serverDir&gt;/aerogel.promote). Loads a fresh
 * proxy chunk, writes a block into it (must be cancelled + deferred, and the chunk queued for the pacer rather
 * than started), lets the manager promote the chunk, then verifies the write was replayed onto the real
 * terrain. Once the promotion is generating it also checks that a main-thread read returns the placeholder at
 * once instead of blocking (#13), and writes 6144 blocks into the chunk, which must all land, replayed over
 * more than one tick (#5). Self-halting.
 *
 * <p><b>#16 write-order witness (#19).</b> Two caller writes (diamond) are deferred behind the bulk batch, and
 * on the first tick the chunk is real, while both are still pending, the probe drives one write down each of
 * the two vanilla paths that used to erase them: {@code LevelChunk.postProcessGeneration} over glass at
 * {@link #witnessPost} and a leaf block tick ({@code ServerLevel.tickBlock}) over leaves at
 * {@link #witnessLeaf}. Each must be deferred (the chunk's pending count grows), and the diamond must
 * still be there at the end. Replaying {@code caller} before {@code vanilla}, or one merged FIFO, lets the
 * vanilla write land last and fails the probe; a chunk with no vegetation can no longer pass it by luck.</p>
 */
final class PromotionProbe {
    private static final Logger LOGGER = LoggerFactory.getLogger("aerogel.promoteprobe");
    private static final int OFFSET = 3000;
    /**
     * How long the probe will wait for the writes to land before declaring a failure.
     *
     * <p><b>If this starts failing, investigate the delay — do not raise it.</b> This is a performance mod:
     * a write that takes longer than expected is the finding, and pulling the bound back is how it surfaces.
     * Raising a timeout to accommodate a suspected delay is the last resort, not the first, and it converts a
     * real slowdown into a silent one. The earlier note on this issue estimated ~10.7 blocks/tick against the
     * 1.5 ms/tick replay budget, i.e. ~575 ticks for the 6144 bulk writes; the bound is deliberately far below
     * that, because if that estimate were right the replay would itself be the thing worth reporting.</p>
     */
    private static final int MAX_TICKS = 100;
    /** in-flight writes: 16x16 columns x 24 layers, above one tick's replay budget. */
    private static final int BULK_Y = 101;
    private static final int BULK_LAYERS = 24;
    private static final int BULK = 16 * 16 * BULK_LAYERS;

    // ---- LATE BATCH (#16) --------------------------------------------------------------------------------
    // A second, smaller batch written at LATE_TICK, once vanilla's own post-generation / block-tick writes
    // have already been queued, to exercise the caller-after-vanilla order. Kept well clear of BULK_Y so the
    // two counts stay independent -- an earlier revision used y=101 and silently overwrote two gold layers,
    // which looked like 512 lost gold blocks rather than an overlapping band.
    private static final int LATE_TICK = 20;
    private static final int LATE_Y = 95;
    private static final int LATE_LAYERS = 2;
    private static final int LATE = 16 * 16 * LATE_LAYERS;
    private static boolean lateWritten;

    // ---- WRITE-ORDER WITNESS (#19) -------------------------------------------------------------------------
    // y=90 is clear of every band above; both positions get their terrain written straight into the chunk, the
    // way worldgen places it, so what vanilla writes back is known.
    private static final int WITNESS_Y = 90;
    private static BlockPos witnessPost;
    private static BlockPos witnessLeaf;
    private static boolean raced;
    private static String race = "never raced (chunk not real within the bound)";
    private static boolean raceOk;

    private static ServerLevel level;
    private static ChunkPos probe;
    private static BlockPos writePos;
    private static int ticks;
    private static boolean written;
    private static boolean queuedAtWrite;
    private static boolean bulkWritten;
    private static String inFlightRead = "not checked";
    private static boolean inFlightOk;
    private static int bulkFirstTick = -1;

    private PromotionProbe() {
    }

    static void start(MinecraftServer server) {
        level = server.overworld();
        ChunkPos spawn = new ChunkPos(level.getSharedSpawnPos());
        // a previous run persists the promoted chunk, so scan along +x for a chunk that is not on disk yet,
        // keeping the probe repeatable without wiping the world (asked from disk directly: the first read of an
        // on-disk chunk may be the placeholder while the async disk check runs)
        for (int i = 0; i < 64 && probe == null; i++) {
            ChunkPos candidate = new ChunkPos(spawn.x + OFFSET + i * 32, spawn.z - OFFSET - 4000);
            if (level.getChunkSource().chunkMap.read(candidate).join().isEmpty()) {
                level.setChunkForced(candidate.x, candidate.z, true);
                level.getChunk(candidate.x, candidate.z);
                probe = candidate;
            }
        }
        if (probe == null) {
            LOGGER.error("promote probe: no fresh proxy found near offset {}", OFFSET);
            server.halt(false);
            return;
        }
        writePos = new BlockPos(probe.getMinBlockX() + 8, 100, probe.getMinBlockZ() + 8);
        witnessPost = new BlockPos(probe.getMinBlockX() + 2, WITNESS_Y, probe.getMinBlockZ() + 2);
        witnessLeaf = new BlockPos(probe.getMinBlockX() + 12, WITNESS_Y, probe.getMinBlockZ() + 12);
        LOGGER.info("promote probe: chunk {} loaded as proxy (no FULL future)", probe);
    }

    /** the write, once the forced ticket has reached the holder (as it has under a player who writes). */
    private static void write() {
        // the write must be intercepted (cancelled + remembered) because the chunk is a proxy
        boolean deferred = level.setBlock(writePos, Blocks.STONE.defaultBlockState(), 3);
        LevelChunk after = level.getChunkSource().getChunkNow(probe.x, probe.z);
        // a write is a player trigger: it queues the chunk for the pacer instead of starting it here
        queuedAtWrite = PromotionManager.debugIsQueued(level, probe.toLong()) && !ProxyChunkGate.isPromoting(level, probe.toLong());
        LOGGER.info("promote probe: write into proxy -> deferred={}, still proxy={}, queued not started={}",
            deferred, after == null, queuedAtWrite);
        written = true;
    }

    /** once the pacer started the promotion: the in-flight read and the bulk writes into the generating chunk. */
    private static void bulk() {
        bulkWritten = true;
        // #13: a read of the generating chunk must not managedBlock on its FULL future
        long t0 = System.nanoTime();
        ChunkAccess read = level.getChunk(probe.x, probe.z, ChunkStatus.FULL, true);
        double ms = (System.nanoTime() - t0) / 1_000_000.0;
        ChunkAccess peek = level.getChunkSource().getChunk(probe.x, probe.z, ChunkStatus.FULL, false);
        inFlightOk = read instanceof EmptyLevelChunk && peek == null && ms < 5.0;
        inFlightRead = String.format("placeholder=%s in %.3f ms, peek null=%s", read instanceof EmptyLevelChunk, ms, peek == null);
        // #5: more than the cap into the generating chunk; none may be dropped
        for (int y = BULK_Y; y < BULK_Y + BULK_LAYERS; y++) {
            for (int dx = 0; dx < 16; dx++) {
                for (int dz = 0; dz < 16; dz++) {
                    level.setBlock(new BlockPos(probe.getMinBlockX() + dx, y, probe.getMinBlockZ() + dz),
                        Blocks.GOLD_BLOCK.defaultBlockState(), 3);
                }
            }
        }
        LOGGER.info("promote probe: in-flight read {}; {} writes into the generating chunk", inFlightRead, BULK);
        // queued after the bulk, so both are still pending on the tick the chunk turns real
        level.setBlock(witnessPost, Blocks.DIAMOND_BLOCK.defaultBlockState(), 3);
        level.setBlock(witnessLeaf, Blocks.DIAMOND_BLOCK.defaultBlockState(), 3);
    }

    /**
     * #19: with the caller's diamonds still pending, drive one write down each vanilla path into the now-real
     * chunk. Both must be deferred; whether they then land before the diamonds is what the verdict checks.
     */
    private static void race(LevelChunk chunk) {
        raced = true;
        boolean pendingCaller = !level.getBlockState(witnessPost).is(Blocks.DIAMOND_BLOCK)
            && !level.getBlockState(witnessLeaf).is(Blocks.DIAMOND_BLOCK);
        long pos = probe.toLong();
        int p0 = PromotionManager.debugDeferredWrites(level, pos);
        // postProcessGeneration: mark the glass for post-processing and run the real method. It has not run for
        // this chunk yet (that happens when it starts ticking), so vanilla's own post-processing entries go too:
        // the count grows by at least one, not exactly one.
        chunk.setBlockState(witnessPost, Blocks.GLASS.defaultBlockState(), false);
        ChunkAccess.getOrCreateOffsetList(chunk.getPostProcessing(), chunk.getSectionIndex(WITNESS_Y))
            .add(ProtoChunk.packOffsetCoordinates(witnessPost));
        chunk.postProcessGeneration();
        int p1 = PromotionManager.debugDeferredWrites(level, pos);
        // the leaf decay tick: ServerLevel.tickBlock is what the scheduled tick calls; LeavesBlock.tick writes back
        chunk.setBlockState(witnessLeaf, Blocks.OAK_LEAVES.defaultBlockState(), false);
        String tickErr = "";
        try {
            Method tickBlock = ServerLevel.class.getDeclaredMethod("tickBlock", BlockPos.class, Block.class);
            tickBlock.setAccessible(true);
            tickBlock.invoke(level, witnessLeaf, Blocks.OAK_LEAVES);
        } catch (ReflectiveOperationException e) {
            tickErr = " (tickBlock failed: " + e + ")";
        }
        int p2 = PromotionManager.debugDeferredWrites(level, pos);
        raceOk = pendingCaller && p1 > p0 && p2 == p1 + 1;
        race = String.format("caller diamonds pending=%s; postProcessGeneration over %s at %s deferred %d->%d; "
                + "leaf tick over %s at %s deferred %d->%d%s", pendingCaller, level.getBlockState(witnessPost).getBlock(),
            witnessPost.toShortString(), p0, p1, level.getBlockState(witnessLeaf).getBlock(),
            witnessLeaf.toShortString(), p1, p2, tickErr);
        LOGGER.info("promote probe: witness raced at tick {}: {}", ticks, race);
    }

    private static int bulkPresent() {
        int present = 0;
        BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
        for (int y = BULK_Y; y < BULK_Y + BULK_LAYERS; y++) {
            for (int dx = 0; dx < 16; dx++) {
                for (int dz = 0; dz < 16; dz++) {
                    if (level.getBlockState(at.set(probe.getMinBlockX() + dx, y, probe.getMinBlockZ() + dz))
                        .is(Blocks.GOLD_BLOCK)) {
                        present++;
                    }
                }
            }
        }
        return present;
    }

    /**
     * #16: sample what actually occupies a few positions whose deferred write did not survive. Air means the
     * write was never applied; anything else means something was written over it, which is a different bug.
     */
    private static String missingSummary(int present) {
        if (present == BULK) {
            return "none";
        }
        List<String> samples = new ArrayList<>();
        BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
        for (int y = BULK_Y; y < BULK_Y + BULK_LAYERS && samples.size() < 8; y++) {
            for (int dx = 0; dx < 16 && samples.size() < 8; dx++) {
                for (int dz = 0; dz < 16; dz++) {
                    BlockState s = level.getBlockState(at.set(probe.getMinBlockX() + dx, y, probe.getMinBlockZ() + dz));
                    if (!s.is(Blocks.GOLD_BLOCK)) {
                        samples.add("y" + y + "=" + s.getBlock());
                        break;
                    }
                }
            }
        }
        return samples.toString();
    }

    /** what occupies the late band's missing positions; air means never applied, else overwritten. */
    private static String lateMissingSummary(int present) {
        if (present == LATE) {
            return "none";
        }
        List<String> samples = new ArrayList<>();
        BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
        for (int y = LATE_Y; y < LATE_Y + LATE_LAYERS && samples.size() < 8; y++) {
            for (int dx = 0; dx < 16 && samples.size() < 8; dx++) {
                for (int dz = 0; dz < 16; dz++) {
                    BlockState s = level.getBlockState(at.set(probe.getMinBlockX() + dx, y, probe.getMinBlockZ() + dz));
                    if (!s.is(Blocks.EMERALD_BLOCK)) {
                        samples.add("y" + y + "=" + s.getBlock());
                        break;
                    }
                }
            }
        }
        return samples.toString();
    }

    /** count the late batch: emerald in the LATE band. */
    private static int latePresent() {        int present = 0;
        BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
        for (int y = LATE_Y; y < LATE_Y + LATE_LAYERS; y++) {
            for (int dx = 0; dx < 16; dx++) {
                for (int dz = 0; dz < 16; dz++) {
                    if (level.getBlockState(at.set(probe.getMinBlockX() + dx, y, probe.getMinBlockZ() + dz))
                        .is(Blocks.EMERALD_BLOCK)) {
                        present++;
                    }
                }
            }
        }
        return present;
    }

    static void tick(MinecraftServer server) {
        if (level == null || probe == null) {
            return;
        }
        if (!written && ProxyChunkGate.hasHolder(level, probe.toLong())) {
            write();
        } else if (written && !bulkWritten && ProxyChunkGate.isPromoting(level, probe.toLong())) {
            bulk();
        }
        ++ticks;
        if (!lateWritten && ticks == LATE_TICK) {
            for (int y = LATE_Y; y < LATE_Y + LATE_LAYERS; y++) {
                for (int dx = 0; dx < 16; dx++) {
                    for (int dz = 0; dz < 16; dz++) {
                        level.setBlock(new BlockPos(probe.getMinBlockX() + dx, y, probe.getMinBlockZ() + dz),
                            Blocks.EMERALD_BLOCK.defaultBlockState(), 3);
                    }
                }
            }
            lateWritten = true;
        }
        LevelChunk chunk = level.getChunkSource().getChunkNow(probe.x, probe.z);
        if (written && !raced && chunk != null) {
            race(chunk);
        }
        int present = chunk == null ? 0 : bulkPresent();
        if (present > 0 && bulkFirstTick < 0) {
            bulkFirstTick = ticks;
        }
        if (chunk != null && (present == BULK && latePresent() == LATE && witnessLanded() || ticks > MAX_TICKS)) {
            BlockState at = level.getBlockState(writePos);
            boolean replayed = at.is(Blocks.STONE);
            int spread = present > 0 ? ticks - bulkFirstTick + 1 : 0;
            boolean realAfter = !(level.getChunk(probe.x, probe.z) instanceof EmptyLevelChunk);
            int late = latePresent();
            int pending = PromotionManager.debugDeferredWrites(level, probe.toLong());
            LOGGER.info("promote probe: late batch (caller AFTER vanilla) {}/{}", late, LATE);
            String post = level.getBlockState(witnessPost).getBlock().toString();
            String leaf = level.getBlockState(witnessLeaf).getBlock().toString();
            boolean witness = raceOk && witnessLanded();
            LOGGER.info("promote probe: witness (#19) {} — caller's write after the race: postProcess pos {}, leaf-tick pos {}; {}",
                witness ? "PASS" : "FAIL", post, leaf, race);
            boolean pass = queuedAtWrite && replayed && inFlightOk && present == BULK && late == LATE && spread > 1 && realAfter && witness;
            LOGGER.info("promote probe: VERDICT {} — queued at write={}, chunk {} real after {} ticks, deferred write replayed={} (block {}); "
                    + "in-flight read {}; {}/{} in-flight writes landed over {} tick(s); real read after={}; "
                    + "missing: {}; late missing: {}; deferred still pending={}",
                pass ? "PASS" : "FAIL", queuedAtWrite, probe, ticks, replayed, at.getBlock(), inFlightRead, present, BULK, spread,
                realAfter, missingSummary(present), lateMissingSummary(late), pending);
            testPushUp();
            finish(server);
        } else if (ticks > MAX_TICKS) {
            LOGGER.error("promote probe: VERDICT FAIL — chunk {} still a proxy after {} ticks", probe, ticks);
            finish(server);
        }
    }

    private static boolean witnessLanded() {
        return level.getBlockState(witnessPost).is(Blocks.DIAMOND_BLOCK)
            && level.getBlockState(witnessLeaf).is(Blocks.DIAMOND_BLOCK);
    }

    /** place a zombie inside a block and check the push-up frees it. */
    private static void testPushUp() {
        BlockPos at = new BlockPos(probe.getMinBlockX() + 8, 200, probe.getMinBlockZ() + 8);
        net.minecraft.world.entity.monster.Zombie zombie = net.minecraft.world.entity.EntityType.ZOMBIE.create(level);
        if (zombie == null) {
            return;
        }
        zombie.setPos(at.getX() + 0.5, at.getY(), at.getZ() + 0.5);
        zombie.setNoGravity(true);
        level.addFreshEntity(zombie);
        level.setBlockAndUpdate(at, Blocks.STONE.defaultBlockState());
        double before = zombie.getY();
        ProxyChunkGate.pushOutOfTerrain(level, probe);
        LOGGER.info("promote probe: push-up test — y {} -> {}, free={}",
            before, zombie.getY(), level.noCollision(zombie, zombie.getBoundingBox()));
    }

    private static void finish(MinecraftServer server) {
        if (level != null && probe != null) {
            level.setChunkForced(probe.x, probe.z, false);
        }
        level = null;
        probe = null;
        server.halt(false);
    }
}
