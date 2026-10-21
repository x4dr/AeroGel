package dev.aerogel.common;

import java.util.Arrays;
import net.neoforged.neoforge.common.ModConfigSpec;
import org.apache.commons.lang3.tuple.Pair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Server/shared configuration ({@code config/aerogel-common.toml}). Every tunable that used to be a
 * compiled-in default plus a {@code /aerogel} command lives here so it survives a restart; the commands
 * write the new value back and call {@link ModConfigSpec#save()}.
 *
 * <p>The sections mirror the pipeline: {@code promotion} controls when a coarse proxy chunk is replaced
 * by real terrain (a cumulative linger counter whose threshold interpolates with distance), {@code rate}
 * and {@code load} fund and brake that generation, {@code adaptive} decides when the gate is enforced at
 * all (server backlog / tick time, plus a per-player opt-in), {@code stream} tunes sampling/streaming,
 * and {@code gate} is the virtual-proxy gate itself. See the generated TOML comments for each key.</p>
 */
public final class AeroGelConfig {
    private static final Logger LOGGER = LoggerFactory.getLogger("aerogel");
    public static final AeroGelConfig CFG;
    public static final ModConfigSpec SPEC;

    // promotion
    public final ModConfigSpec.BooleanValue promotionEnabled;
    public final ModConfigSpec.DoubleValue landingMargin;
    public final ModConfigSpec.BooleanValue refuseStubWrites;
    public final ModConfigSpec.IntValue maxRange;
    public final ModConfigSpec.IntValue lingerMinIdleSeconds;
    public final ModConfigSpec.IntValue lingerMinBusySeconds;
    public final ModConfigSpec.IntValue lingerMaxIdleSeconds;
    public final ModConfigSpec.IntValue lingerMaxBusySeconds;
    public final ModConfigSpec.IntValue maxInFlight;
    public final ModConfigSpec.DoubleValue maxGenFraction;
    public final ModConfigSpec.DoubleValue chunkCostMs;
    public final ModConfigSpec.DoubleValue burstMs;
    public final ModConfigSpec.DoubleValue hardGateMs;
    public final ModConfigSpec.DoubleValue replayBudgetMs;
    public final ModConfigSpec.DoubleValue idleTickMs;
    public final ModConfigSpec.DoubleValue busyTickMs;

    // adaptive
    public final ModConfigSpec.BooleanValue adaptiveEnabled;
    public final ModConfigSpec.DoubleValue adaptiveActivateTickMs;
    public final ModConfigSpec.DoubleValue adaptiveDeactivateTickMs;
    public final ModConfigSpec.IntValue adaptiveBacklogActivate;
    public final ModConfigSpec.IntValue adaptiveBacklogDeactivate;
    public final ModConfigSpec.IntValue adaptiveHoldSeconds;
    public final ModConfigSpec.IntValue adaptiveWindowSeconds;
    public final ModConfigSpec.BooleanValue adaptiveEngageOnTeleport;

    // stream
    public final ModConfigSpec.BooleanValue streamEnabled;
    public final ModConfigSpec.IntValue streamRadius;
    public final ModConfigSpec.ConfigValue<Integer> nearTier;
    public final ModConfigSpec.ConfigValue<Integer> farTier;
    public final ModConfigSpec.IntValue nearDistance;
    public final ModConfigSpec.BooleanValue biomeColors;
    public final ModConfigSpec.IntValue samplerThreads;
    public final ModConfigSpec.BooleanValue bypassMoveCheck;

    // gate
    public final ModConfigSpec.BooleanValue gateEnabled;
    public final ModConfigSpec.BooleanValue resendEnabled;

    static {
        Pair<AeroGelConfig, ModConfigSpec> pair = new ModConfigSpec.Builder().configure(AeroGelConfig::new);
        CFG = pair.getLeft();
        SPEC = pair.getRight();
    }

    private AeroGelConfig(ModConfigSpec.Builder b) {
        b.comment("Automatic promotion of proxy chunks to real terrain (see plans/proxy-chunk-state-machine.md).")
            .push("promotion");
        promotionEnabled = b.comment("Master switch for automatic promotion.")
            .define("enabled", true);
        landingMargin = b.comment("Touched/landing trigger: generate immediately when a descending player (or a Sable")
            .comment("physics body's collision section) is within this many blocks above the sampled proxy surface.")
            .defineInRange("landingMargin", 8.0, 0.0, 64.0);
        maxRange = b.comment("How far out (chunks) a proxy can be linger-promoted. -1 = auto: server view-distance + 2,")
            .comment("the radius at which vanilla actually generates FULL chunks.")
            .defineInRange("maxRange", -1, -1, 64);

        refuseStubWrites = b.comment("Server-owner opt-in: REFUSE every block write into a chunk that is not real")
            .comment("terrain yet, instead of deferring and replaying it. Placement simply fails and the actor")
            .comment("(player, piston, deployer) retries; the chunk is still promoted on the first attempt.")
            .comment("Off = defer (a build made in flight survives). On = nothing is ever lost or reordered,")
            .comment("at the cost of placements failing while the chunk generates (#16).")
            .define("refuseStubWrites", false);

        b.comment("Linger time by distance: a proxy chunk accrues one tick per tick a player is within maxRange,")
            .comment("and is promoted once the counter reaches t(d) = min + (d/maxRange)*(max - min), where d is the")
            .comment("Chebyshev distance to the nearest player. Both endpoints are interpolated between idle and busy")
            .comment("server load.")
            .push("linger");
        lingerMinIdleSeconds = b.comment("Minimum (distance 0) linger at idle load.")
            .defineInRange("minSecondsIdle", 10, 1, 600);
        lingerMinBusySeconds = b.comment("Minimum (distance 0) linger at heavy load.")
            .defineInRange("minSecondsBusy", 20, 1, 600);
        lingerMaxIdleSeconds = b.comment("Maximum (distance maxRange) linger at idle load.")
            .defineInRange("maxSecondsIdle", 30, 1, 600);
        lingerMaxBusySeconds = b.comment("Maximum (distance maxRange) linger at heavy load.")
            .defineInRange("maxSecondsBusy", 60, 1, 600);
        b.pop();

        b.comment("Generation-time budget for linger promotion. A token bucket refilled at maxGenFraction ms/ms is")
            .comment("charged, per start, chunkCostMs scaled by the share of an untouched neighbourhood's chunk-steps")
            .comment("that start makes vanilla generate, plus the measured finish cost. Touch/write/right-click/Sable")
            .comment("promotions bypass the budget.")
            .push("rate");
        maxInFlight = b.comment("Maximum promotion regenerations in flight at once (burst cap; applies to all).")
            .defineInRange("maxInFlight", 2, 1, 16);
        maxGenFraction = b.comment("Fraction of wall time the budget may fund (0.5 = at most half).")
            .defineInRange("maxGenFraction", 0.5, 0.05, 1.0);
        chunkCostMs = b.comment("Budget charged for promoting a chunk in untouched land (its 5x5 to FULL plus the")
            .comment("partial pyramid, 1387 chunk-steps; measured 65-180 ms server thread); advancing a generated")
            .comment("frontier is charged its share of those steps, e.g. ~1/3 beside a promoted chunk.")
            .defineInRange("chunkCostMs", 100.0, 1.0, 5000.0);
        burstMs = b.comment("Maximum banked budget (a burst of back-to-back promotions).")
            .defineInRange("burstMs", 200.0, 0.0, 10000.0);
        hardGateMs = b.comment("No ordinary linger promotion starts while smoothed tick time is at or above this (0 = off).")
            .defineInRange("hardGateMs", 60.0, 0.0, 1000.0);
        replayBudgetMs = b.comment("Wall time per tick (ms) for replaying writes deferred while a chunk was a proxy or still")
            .comment("generating; the rest carries to the next tick. Writes into a generating chunk are never dropped.")
            .defineInRange("replayBudgetMs", 1.5, 0.1, 50.0);
        b.pop();

        b.comment("How server load is measured for the load-adaptive linger endpoints.")
            .push("load");
        idleTickMs = b.comment("Smoothed tick time treated as idle (load factor 0).")
            .defineInRange("idleTickMs", 10.0, 1.0, 100.0);
        busyTickMs = b.comment("Smoothed tick time treated as fully loaded (load factor 1).")
            .defineInRange("busyTickMs", 45.0, 1.0, 200.0);
        b.pop();
        b.pop();

        b.comment("Adaptive enforcement. The server turns the gate on when it is struggling (chunk-task backlog or")
            .comment("high tick time) and off again once the backlog has drained and no linger counters remain, so it")
            .comment("is invisible when idle. When not enforcing, players may opt in per-player to proxy travel.")
            .push("adaptive");
        adaptiveEnabled = b.comment("Enable adaptive enforcement (off = the gate is always on, the legacy behaviour).")
            .define("enabled", true);
        adaptiveActivateTickMs = b.comment("Enforce when smoothed tick time reaches this many ms (20 TPS = 50 ms).")
            .defineInRange("activateTickMs", 35.0, 1.0, 200.0);
        adaptiveDeactivateTickMs = b.comment("Stop enforcing once tick time falls back to this (must be below the activate value).")
            .defineInRange("deactivateTickMs", 22.0, 1.0, 200.0);
        adaptiveBacklogActivate = b.comment("Enforce when the main-thread chunk-task backlog (max over all gated levels) reaches this many tasks.")
            .defineInRange("backlogActivate", 64, 1, 100000);
        adaptiveBacklogDeactivate = b.comment("Stop enforcing once the backlog falls back to this.")
            .defineInRange("backlogDeactivate", 16, 0, 100000);
        adaptiveHoldSeconds = b.comment("Keep enforcing at least this many seconds after the last activating sample.")
            .defineInRange("holdSeconds", 10, 0, 600);
        adaptiveWindowSeconds = b.comment("Decide on the max backlog and tick time over this many seconds, not one sample (0 = one sample).")
            .defineInRange("windowSeconds", 5, 0, 60);
        adaptiveEngageOnTeleport = b.comment("Enforce as soon as a player teleports (/tp, ender pearl, chorus fruit, ...) into land that may",
                "not be generated yet, before the arrival area is requested; counts as an activating sample, so holdSeconds",
                "applies and enforcement ends by itself (#14). Off = vanilla generates the arrival area in one go.")
            .define("engageOnTeleport", false);
        b.pop();

        b.comment("Proxy mesh sampling and streaming. Streaming is per-player: a player receives proxies only while")
            .comment("their client 'proxy' switch is on.")
            .push("stream");
        streamEnabled = b.comment("Sample/send proxy meshes (off = the gate still runs but nothing is streamed).")
            .define("enabled", true);
        streamRadius = b.comment("Proxy view radius in chunks (the client's render distance is the other cap;")
            .comment("the client keeps its mesh store out to a larger fixed radius so re-approaching does not hole).")
            .defineInRange("radius", 20, 4, 1000);
        nearTier = b.comment("Fine detail tier used within `nearDistance`: 5, 9, 25 or 81.")
            .defineInList("nearTier", 9, Arrays.asList(5, 9, 25, 81));
        farTier = b.comment("Coarse detail tier used beyond `nearDistance`: 5, 9, 25 or 81.")
            .defineInList("farTier", 5, Arrays.asList(5, 9, 25, 81));
        nearDistance = b.comment("Near-tier band in chunks.")
            .defineInRange("nearDistance", 32, 1, 1000);
        biomeColors = b.comment("Send biome tint on the wire (the block texture is always sent).")
            .define("biomeColors", true);
        samplerThreads = b.comment("Off-thread sampler worker count.")
            .defineInRange("threads", 1, 1, 64);
        bypassMoveCheck = b.comment("Exempt fast creative flight / elytra from vanilla's 'moved too quickly' rubber-band.\n"
                + "Off by default: on a server with untrusted players it is a speed-hack hole.")
            .define("bypassMoveCheck", false);
        b.pop();

        b.comment("The virtual-proxy chunk gate.")
            .push("gate");
        gateEnabled = b.comment("Gate a never-generated chunk's own FULL task (off = chunks generate vanilla).")
            .define("enabled", true);
        resendEnabled = b.comment("Resend the chunk packet when a proxy is promoted.")
            .define("resend", true);
        b.pop();
    }

    /**
     * Cross-field sanity pass, run whenever the config is loaded or reloaded. The spec range-checks each key
     * on its own; these are the relationships between keys that it cannot express. A violation is corrected
     * in memory and logged, so a hand-edited file can never leave the server in a degenerate state — e.g.
     * {@code idleTickMs == busyTickMs} would make the load interpolation a division by zero, which would
     * read as "fully loaded" and promote every proxy at once.
     *
     * <p>The {@code /aerogel} commands already normalise their own arguments; this covers the file.</p>
     */
    public static void validate() {
        AeroGelConfig cfg = CFG;
        if (cfg.busyTickMs.get() <= cfg.idleTickMs.get()) {
            double fixed = Math.min(cfg.idleTickMs.get() + 1.0, 200.0);
            LOGGER.warn("aerogel: busyTickMs ({}) must be above idleTickMs ({}); using {}",
                cfg.busyTickMs.get(), cfg.idleTickMs.get(), fixed);
            cfg.busyTickMs.set(fixed);
        }
        if (cfg.adaptiveDeactivateTickMs.get() >= cfg.adaptiveActivateTickMs.get()) {
            double fixed = Math.max(cfg.adaptiveActivateTickMs.get() - 1.0, 1.0);
            LOGGER.warn("aerogel: deactivateTickMs ({}) must be below activateTickMs ({}); using {}",
                cfg.adaptiveDeactivateTickMs.get(), cfg.adaptiveActivateTickMs.get(), fixed);
            cfg.adaptiveDeactivateTickMs.set(fixed);
        }
        if (cfg.adaptiveBacklogDeactivate.get() >= cfg.adaptiveBacklogActivate.get()) {
            int fixed = Math.max(cfg.adaptiveBacklogActivate.get() / 2, 0);
            LOGGER.warn("aerogel: backlogDeactivate ({}) must be below backlogActivate ({}); using {}",
                cfg.adaptiveBacklogDeactivate.get(), cfg.adaptiveBacklogActivate.get(), fixed);
            cfg.adaptiveBacklogDeactivate.set(fixed);
        }
        // linger endpoints must be non-decreasing, or the distance interpolation inverts.
        int minIdle = cfg.lingerMinIdleSeconds.get();
        int minBusy = Math.max(minIdle, cfg.lingerMinBusySeconds.get());
        int maxIdle = Math.max(minBusy, cfg.lingerMaxIdleSeconds.get());
        int maxBusy = Math.max(maxIdle, cfg.lingerMaxBusySeconds.get());
        if (minBusy != cfg.lingerMinBusySeconds.get() || maxIdle != cfg.lingerMaxIdleSeconds.get()
            || maxBusy != cfg.lingerMaxBusySeconds.get()) {
            LOGGER.warn("aerogel: linger endpoints must be non-decreasing ({} {} {} {}); clamping",
                minIdle, cfg.lingerMinBusySeconds.get(), cfg.lingerMaxIdleSeconds.get(), cfg.lingerMaxBusySeconds.get());
            cfg.lingerMinBusySeconds.set(minBusy);
            cfg.lingerMaxIdleSeconds.set(maxIdle);
            cfg.lingerMaxBusySeconds.set(maxBusy);
        }
    }
}
