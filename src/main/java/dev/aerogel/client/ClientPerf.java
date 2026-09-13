package dev.aerogel.client;

import java.util.IdentityHashMap;
import java.util.Map;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Always-on client stall probe, independent of the proxy features. Measures the client tick and, per frame,
 * a coarse breakdown of the vanilla render stages, so a hitch can be attributed (tick vs render phase) even
 * with {@code /aerogel switch render off}.
 */
public final class ClientPerf {
    private static final Logger LOGGER = LoggerFactory.getLogger("aerogel.perf");
    private static final long STALL_NANOS = 40L * 1_000_000L;

    private static long tickStartNanos;
    private static long lastTickNanos;

    private static RenderLevelStageEvent.Stage prevStage;
    private static long prevStageNanos;
    private static long frameStartNanos;
    private static final Map<RenderLevelStageEvent.Stage, Long> stageAccum = new IdentityHashMap<>();

    private ClientPerf() {
    }

    @SubscribeEvent
    public static void onTickPre(ClientTickEvent.Pre event) {
        tickStartNanos = System.nanoTime();
    }

    @SubscribeEvent
    public static void onTickPost(ClientTickEvent.Post event) {
        long dt = System.nanoTime() - tickStartNanos;
        lastTickNanos = dt;
        if (dt >= STALL_NANOS) {
            LOGGER.warn("client tick stall: {} ms (mesh store {})", ms(dt), ClientMeshStore.size());
        }
    }

    /**
     * Fires for every render stage. The gap between two stages is attributed to the stage that just ran;
     * a new frame starts at {@code AFTER_SKY}. If the frame stalled, the slowest phases are logged.
     */
    public static void onStage(RenderLevelStageEvent event) {
        long now = System.nanoTime();
        RenderLevelStageEvent.Stage stage = event.getStage();
        if (stage == RenderLevelStageEvent.Stage.AFTER_SKY && prevStage != null) {
            stageAccum.merge(prevStage, now - prevStageNanos, Long::sum);
            long frameNanos = now - frameStartNanos;
            if (frameNanos >= STALL_NANOS && frameNanos < 1_000_000_000L) {
                logStages(frameNanos);
            }
            stageAccum.clear();
            frameStartNanos = now;
        } else if (prevStage != null) {
            stageAccum.merge(prevStage, now - prevStageNanos, Long::sum);
        } else {
            frameStartNanos = now;
        }
        prevStage = stage;
        prevStageNanos = now;
    }

    private static void logStages(long frameNanos) {
        StringBuilder sb = new StringBuilder();
        stageAccum.entrySet().stream()
            .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
            .limit(4)
            .forEach(e -> sb.append(e.getKey()).append('=').append(ms(e.getValue())).append("ms "));
        LOGGER.warn("client frame stall: {} ms (last tick {} ms) | {}", ms(frameNanos), ms(lastTickNanos), sb.toString().trim());
    }

    private static String ms(long nanos) {
        return String.format("%.2f", nanos / 1e6);
    }
}
