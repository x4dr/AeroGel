package dev.aerogel.server;

import net.minecraft.server.MinecraftServer;

/**
 * Dev-only hook for the headless probes. Implementations live in the {@code dev} source set, which is on the
 * classpath of a development run but is <b>never packaged into the published jar</b>: a released build contains
 * no probe code at all and pays only one empty {@link DevProbes} iteration per tick.
 *
 * <p>There is deliberately no runtime JVM flag: the probe code either exists in the build or it does not.</p>
 */
public interface DevProbe {
    /** called once after {@link ProxyStreamer#init}, on the server thread. */
    void onServerStarted(MinecraftServer server);

    /** called once per server tick, before the streamer and promotion ticks. */
    void onServerTick(MinecraftServer server);

    /**
     * Called at the very start of the server tick, <b>inside</b> the window the smoothed tick time is measured
     * over — {@code MinecraftServer.tickServer} takes its start timestamp, fires {@code ServerTickEvent.Pre} and
     * only then tallies {@code smoothedTickTimeMillis}. Work done here is therefore load the adaptive layer
     * actually sees; work done in {@link #onServerTick} is not, because that runs after the tally.
     */
    void onServerTickPre(MinecraftServer server);
}
