package dev.aerogel.server;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Headless check of #6 (flag file: &lt;serverDir&gt;/aerogel.writesafety), two server starts.
 * <p>Start 1: a fake player places stone into a fresh proxy — must be refused (item kept) and queue the chunk,
 * not start it; the pacer must then start it, and the same placement must work once the chunk is real (VERDICT
 * place). Then a non-player write into a second fresh proxy is deferred and the server halts at once, with the
 * promotion still queued; its position goes to {@code aerogel.writesafety.pos}.</p>
 * <p>Start 2 (pos file present): the written block must be on disk (VERDICT stop).</p>
 */
final class WriteSafetyProbe {
    private static final Logger LOGGER = LoggerFactory.getLogger("aerogel.writesafety");
    private static final int OFFSET = 5000;
    private static final int MAX_TICKS = 600;
    private static final int Y = 200;

    private static ServerLevel level;
    private static Path posFile;
    private static ChunkPos place;
    private static ChunkPos stop;
    private static FakePlayer player;
    private static ItemStack stack;
    private static String refusal;
    private static boolean refused;
    private static boolean checking;
    private static int ticks;
    private static int startsAtClick;

    private WriteSafetyProbe() {
    }

    static void start(MinecraftServer server) {
        level = server.overworld();
        posFile = server.getServerDirectory().resolve("aerogel.writesafety.pos");
        if (Files.exists(posFile)) {
            try {
                stop = new ChunkPos(Long.parseLong(Files.readString(posFile).trim()));
                Files.delete(posFile);
            } catch (IOException | NumberFormatException e) {
                LOGGER.error("write safety: VERDICT stop FAIL — unreadable {}", posFile, e);
                server.halt(false);
                return;
            }
            checking = true;
            return;
        }
        ChunkPos spawn = new ChunkPos(level.getSharedSpawnPos());
        for (int i = 0; i < 64 && stop == null; i++) {
            ChunkPos candidate = new ChunkPos(spawn.x + OFFSET + i * 32, spawn.z + OFFSET);
            if (level.getChunkSource().chunkMap.read(candidate).join().isEmpty()) {
                if (place == null) {
                    place = candidate;
                } else {
                    stop = candidate;
                }
            }
        }
        if (stop == null) {
            LOGGER.error("write safety: VERDICT place FAIL — no two fresh chunks near offset {}", OFFSET);
            server.halt(false);
            return;
        }
        for (ChunkPos c : new ChunkPos[] { place, stop }) {
            level.getChunkSource().updateChunkForced(c, true); // a ticket, not persisted forced-chunk data
            level.getChunk(c.x, c.z);
        }
        player = FakePlayerFactory.getMinecraft(level);
        LOGGER.info("write safety: proxies {} (place) and {} (stop) loaded", place, stop);
    }

    private static InteractionResult use(BlockPos at) {
        return use(at, Direction.UP);
    }

    private static InteractionResult use(BlockPos at, Direction face) {
        player.setItemInHand(InteractionHand.MAIN_HAND, stack);
        return player.gameMode.useItemOn(player, level, stack, InteractionHand.MAIN_HAND,
            new BlockHitResult(Vec3.atCenterOf(at), face, at, false));
    }

    /** queued for the pacer and not started: what a refused click does since player triggers queue. */
    private static boolean queuedNotStarted(ChunkPos c) {
        return PromotionManager.debugIsQueued(level, c.toLong()) && ProxyChunkGate.isProxy(level, c.toLong())
            && !ProxyChunkGate.isPromoting(level, c.toLong());
    }

    /**
     * The path a real client takes (it has no proxy chunks to click): the side of a block in a real chunk whose
     * face points into a proxy. The placement must be refused (item kept, neighbour queued), the click itself
     * not cancelled. Returns null when the east neighbour is not a fresh proxy (nothing to check).
     */
    private static String faceCheck() {
        ChunkPos east = new ChunkPos(place.x + 1, place.z);
        if (!ProxyChunkGate.isProxy(level, east.toLong()) || ProxyChunkGate.isPromoting(level, east.toLong())
            || PromotionManager.debugIsQueued(level, east.toLong())) {
            return null;
        }
        BlockPos edge = new BlockPos(place.getMaxBlockX(), Y, place.getMinBlockZ() + 8);
        level.setBlock(edge, Blocks.STONE.defaultBlockState(), 3); // place is real now: a direct write
        int before = stack.getCount();
        InteractionResult result = use(edge, Direction.EAST);
        boolean queued = queuedNotStarted(east);
        boolean ok = !result.consumesAction() && stack.getCount() == before && queued;
        return String.format("%s (result %s, items %d->%d, neighbour queued not started=%s)",
            ok ? "PASS" : "FAIL", result, before, stack.getCount(), queued);
    }

    static void tick(MinecraftServer server) {
        if (level == null) {
            return;
        }
        ticks++;
        if (checking) {
            checkStop(server);
            return;
        }
        BlockPos at = new BlockPos(place.getMinBlockX() + 8, Y, place.getMinBlockZ() + 8);
        if (refusal == null) {
            if (!ProxyChunkGate.isProxy(level, place.toLong()) || !ProxyChunkGate.hasHolder(level, place.toLong())) {
                if (ticks > MAX_TICKS) {
                    fail(server, "chunk never became a loaded proxy");
                }
                return;
            }
            stack = new ItemStack(Items.STONE, 4);
            startsAtClick = PromotionManager.debugLingerStarts();
            InteractionResult result = use(at);
            boolean queued = queuedNotStarted(place);
            refused = !result.consumesAction() && stack.getCount() == 4 && queued;
            refusal = String.format("result %s, items %d/4, queued not started=%s", result, stack.getCount(), queued);
            LOGGER.info("write safety: placement into proxy {} -> {}", place, refusal);
            return;
        }
        LevelChunk chunk = level.getChunkSource().getChunkNow(place.x, place.z);
        if (chunk == null || ProxyChunkGate.isPromoting(level, place.toLong())) {
            if (ticks > MAX_TICKS) {
                fail(server, "place chunk not real after " + ticks + " ticks; " + refusal);
            }
            return;
        }
        InteractionResult retry = use(at);
        boolean placed = retry.consumesAction() && level.getBlockState(at).is(Blocks.STONE) && stack.getCount() == 3;
        int paced = PromotionManager.debugLingerStarts() - startsAtClick; // no player online: only the queue starts
        LOGGER.info("write safety: VERDICT place {} — into proxy: {}; pacer starts {}; after promotion: result {}, block {}, items {}/4",
            refused && placed && paced >= 1 ? "PASS" : "FAIL", refusal, paced, retry, level.getBlockState(at).getBlock(),
            stack.getCount());
        String face = faceCheck();
        LOGGER.info("write safety: VERDICT face {}", face == null ? "SKIP — east neighbour of " + place + " is not a fresh proxy" : face);
        haltWithPendingWrite(server);
    }

    /** a non-player write into the second proxy, then stop at once: the write is pending at shutdown. */
    private static void haltWithPendingWrite(MinecraftServer server) {
        BlockPos at = new BlockPos(stop.getMinBlockX() + 8, Y, stop.getMinBlockZ() + 8);
        if (!ProxyChunkGate.hasHolder(level, stop.toLong())) {
            fail(server, "stop chunk has no holder");
            return;
        }
        boolean deferred = level.setBlock(at, Blocks.GOLD_BLOCK.defaultBlockState(), 3);
        boolean inFlight = PromotionManager.debugIsQueued(level, stop.toLong()) || ProxyChunkGate.isPromoting(level, stop.toLong());
        try {
            Files.writeString(posFile, Long.toString(stop.toLong()));
        } catch (IOException e) {
            fail(server, "cannot write " + posFile);
            return;
        }
        LOGGER.info("write safety: write into proxy {} deferred={}, promotion queued or in flight={}; halting now", stop, deferred, inFlight);
        level.getChunkSource().updateChunkForced(place, false);
        level = null;
        server.halt(false);
    }

    private static void checkStop(MinecraftServer server) {
        BlockPos at = new BlockPos(stop.getMinBlockX() + 8, Y, stop.getMinBlockZ() + 8);
        ChunkAccess chunk = level.getChunk(stop.x, stop.z, ChunkStatus.FULL, true);
        if (!(chunk instanceof LevelChunk) || chunk instanceof net.minecraft.world.level.chunk.EmptyLevelChunk) {
            if (ticks > 200) {
                String disk = level.getChunkSource().chunkMap.read(stop).join().map(t -> t.getString("Status")).orElse("absent");
                LOGGER.error("write safety: VERDICT stop FAIL — chunk {} never loaded real (on disk: {})", stop, disk);
                level = null;
                server.halt(false);
            }
            return; // the gate may serve the placeholder for a tick while it reads the disk
        }
        boolean present = chunk.getBlockState(at).is(Blocks.GOLD_BLOCK);
        LOGGER.info("write safety: VERDICT stop {} — after restart chunk {} block at {} is {}",
            present ? "PASS" : "FAIL", stop, at, chunk.getBlockState(at).getBlock());
        level = null;
        server.halt(false);
    }

    private static void fail(MinecraftServer server, String why) {
        LOGGER.error("write safety: VERDICT place FAIL — {}", why);
        level = null;
        server.halt(false);
    }
}
