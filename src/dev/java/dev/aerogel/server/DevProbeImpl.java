package dev.aerogel.server;

import java.nio.file.Files;
import java.nio.file.Path;
import net.minecraft.server.MinecraftServer;

/**
 * The headless probes, armed by flag files in the server directory. Dev-only: this class lives in the
 * {@code dev} source set and is never packaged into the released jar (see {@link DevProbe}).
 *
 * <p>Flags (all optional, all created next to {@code server.properties} in the game directory):
 * {@code aerogel.probe} (gate smoke test), {@code aerogel.promote} (promotion + deferred-write + push-up),
 * {@code aerogel.promotestress} (25-chunk promotion burst), {@code aerogel.writesafety} (#6: player placement
 * refused + pending write survives a stop; two starts), {@code aerogel.sablebody} (#9: Sable bodies over proxies),
 * {@code aerogel.flight} (fast-flight benchmark matrix).
 * Each one deletes its own flag before a normal run.</p>
 */
public final class DevProbeImpl implements DevProbe {
    @Override
    public void onServerStarted(MinecraftServer server) {
        Path dir = server.getServerDirectory();
        if (Files.exists(dir.resolve("aerogel.probe"))
            || Files.exists(dir.resolve("aerogel.promote"))
            || Files.exists(dir.resolve("aerogel.promotestress"))
            || Files.exists(dir.resolve("aerogel.writesafety"))
            || Files.exists(dir.resolve("aerogel.sablebody"))) {
            AdaptiveMode.debugAlwaysOn(); // headless probes have no players to opt in
        }
        if (Files.exists(dir.resolve("aerogel.writesafety"))) {
            server.execute(() -> WriteSafetyProbe.start(server));
        }
        if (Files.exists(dir.resolve("aerogel.probe"))) {
            server.execute(() -> new GenerationProbe(server.overworld()).run());
        }
        if (Files.exists(dir.resolve("aerogel.promote"))) {
            server.execute(() -> PromotionProbe.start(server));
        }
        if (Files.exists(dir.resolve("aerogel.promotestress"))) {
            server.execute(() -> PromotionStressProbe.start(server));
        }
        if (Files.exists(dir.resolve("aerogel.sablebody"))) {
            server.execute(() -> SableBodyProbe.start(server));
        }
        // the flight harness keeps the REAL adaptive path (no debugAlwaysOn) and waits for a player
        if (Files.exists(dir.resolve("aerogel.flight"))) {
            FlightProbe.start(server);
        }
    }

    @Override
    public void onServerTick(MinecraftServer server) {
        FlightProbe.tick(server); // move the pilot first, so the streamer sees the new position
        PromotionProbe.tick(server);
        GenerationProbe.tick(server);
        PromotionStressProbe.tick(server);
        WriteSafetyProbe.tick(server);
        SableBodyProbe.tick(server);
    }
}
