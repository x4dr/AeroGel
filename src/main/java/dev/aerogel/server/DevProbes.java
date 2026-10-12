package dev.aerogel.server;

import java.util.List;
import java.util.ServiceLoader;
import net.minecraft.server.MinecraftServer;

/**
 * ServiceLoader front for {@link DevProbe}. The provider is contributed by the {@code dev} source set, so in
 * a released build {@link #PROBES} is empty and every call here is a loop over an empty list.
 */
public final class DevProbes {
    private static final List<DevProbe> PROBES =
        ServiceLoader.load(DevProbe.class, DevProbes.class.getClassLoader())
            .stream().map(ServiceLoader.Provider::get).toList();

    private DevProbes() {
    }

    public static void onServerStarted(MinecraftServer server) {
        for (DevProbe probe : PROBES) {
            probe.onServerStarted(server);
        }
    }

    public static void onServerTick(MinecraftServer server) {
        for (DevProbe probe : PROBES) {
            probe.onServerTick(server);
        }
    }
}
