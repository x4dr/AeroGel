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
}
