package dev.aerogel.proxy;

import dev.aerogel.common.ProxyTopology;
import java.util.Arrays;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Owns one sampling thread. The main server thread only enqueues chunk positions (with the detail tier
 * the caller wants) and drains finished grids, so density evaluation never lands on the tick budget. The
 * worker owns its OWN {@link RandomState}, {@link PointSurfaceSampler} and {@link BiomeTint}: the router's
 * interpolator/cache wrappers are stateful, so a second thread sharing the main-thread state would race.
 * Same seed + settings => identical heights.
 *
 * <p>The tier travels with each task (so one worker can serve a coarse far chunk and a fine near chunk in
 * the same run); the result is stamped with the tier actually used, so a caller can drop a stale result
 * if its desired tier changed while the sample was in flight.</p>
 */
public final class OffThreadSampler implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger("aerogel.sampler");
    private static final short SEA_LEVEL = 63;

    public record Result(long chunkPos, byte topology, short[] grid, int biome, int block, long nanos) {
    }

    private record Task(long pos, byte topology) {
    }

    private final PointSurfaceSampler sampler;
    private final BiomeTint tint;
    private final int seaLevel;
    private final Thread thread;
    private final LinkedBlockingQueue<Task> normal = new LinkedBlockingQueue<>();
    private final ConcurrentLinkedQueue<Task> urgent = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<Result> done = new ConcurrentLinkedQueue<>();
    private volatile boolean running = true;
    private final AtomicLong sampled = new AtomicLong();
    private final AtomicLong sampledNanos = new AtomicLong();

    public OffThreadSampler(RandomState state, NoiseGeneratorSettings settings, BiomeSource biomeSource,
            net.minecraft.core.Registry<net.minecraft.world.level.biome.Biome> biomeRegistry) {
        this.sampler = new PointSurfaceSampler(state, settings);
        this.seaLevel = settings.seaLevel();
        // the biome probe always runs: it supplies the representative block (texture) and the biome id (the
        // client computes the tint, since the colormaps only exist client-side). The streamer decides whether
        // to send the biome id at all (the /aerogel switch colors toggle).
        this.tint = biomeSource != null && biomeRegistry != null
            ? new BiomeTint(biomeSource, biomeRegistry, state.sampler(), seaLevel)
            : null;
        this.thread = new Thread(this::loop, "aerogel-sampler");
        this.thread.setDaemon(true);
        this.thread.start();
    }

    /** submit a chunk at the requested detail tier. */
    public void submit(long chunkPos, byte topology, boolean urgentFlag) {
        Task task = new Task(chunkPos, topology);
        if (urgentFlag) {
            urgent.add(task);
        } else {
            normal.offer(task);
        }
    }

    public Result poll() {
        return done.poll();
    }

    public int backlog() {
        return normal.size() + urgent.size() + done.size();
    }

    public long sampledCount() {
        return sampled.get();
    }

    /** total density-evaluation wall time this worker has spent since construction. */
    public long sampledNanos() {
        return sampledNanos.get();
    }

    public double avgMillis() {
        long c = sampled.get();
        return c == 0 ? 0.0 : sampledNanos.get() / 1e6 / c;
    }

    @Override
    public void close() {
        running = false;
        thread.interrupt();
    }

    private void loop() {
        while (running) {
            Task task = urgent.poll();
            if (task == null) {
                try {
                    task = normal.poll(100, TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            if (task == null) {
                continue;
            }
            byte topo = task.topology();
            long pos = task.pos();
            long t0 = System.nanoTime();
            short[] grid;
            int biome = -1;
            int block = 0;
            try {
                grid = sampler.sample(ChunkPos.getX(pos), ChunkPos.getZ(pos), topo);
                if (tint != null) {
                    int side = ProxyTopology.side(topo);
                    int center = (side / 2) * side + (side / 2);
                    BiomeTint.Probe probe = tint.probe(ChunkPos.getX(pos), ChunkPos.getZ(pos), grid[center]);
                    biome = probe.biomeId();
                    block = probe.blockId();
                }
            } catch (Throwable t) {
                LOGGER.error("sampling failed for chunk ({},{}); substituting sea level",
                    ChunkPos.getX(pos), ChunkPos.getZ(pos), t);
                grid = new short[ProxyTopology.count(topo)];
                Arrays.fill(grid, SEA_LEVEL);
            }
            long dt = System.nanoTime() - t0;
            sampled.incrementAndGet();
            sampledNanos.addAndGet(dt);
            done.add(new Result(pos, topo, grid, biome, block, dt));
        }
    }
}
