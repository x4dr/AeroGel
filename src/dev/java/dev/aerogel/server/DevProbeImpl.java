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
 * {@code aerogel.drain} (the disengage model: paced drain + the queue/in-flight disengage veto),
 * {@code aerogel.stationary} (sampling is one burst, then nothing while a player stands still),
 * {@code aerogel.independence} (gating and proxy rendering are independent axes),
 * {@code aerogel.vanillaclient} (#15: a client without AeroGel gets a paced, not uncapped, square),
 * {@code aerogel.teleport} (#14: engage on teleport into fresh land),
 * {@code aerogel.rate} (the linger drain's rate, the refill's calm-threshold bypass, and the pacer's brake and
 * starvation floor under an injected load),
 * {@code aerogel.footprint} (what one promotion generates: its 5x5 at FULL plus the partial pyramid around it),
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
            || Files.exists(dir.resolve("aerogel.vanillaclient"))
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
        // these four keep the REAL adaptive path (no debugAlwaysOn): they observe enforcement and the zones
        if (Files.exists(dir.resolve("aerogel.drain"))) {
            server.execute(() -> DrainProbe.start(server));
        }
        if (Files.exists(dir.resolve("aerogel.inflight"))) {
            server.execute(() -> InFlightProbe.start(server));
        }
        if (Files.exists(dir.resolve("aerogel.stationary"))) {
            server.execute(() -> StationaryProbe.start(server));
        }
        if (Files.exists(dir.resolve("aerogel.independence"))) {
            server.execute(() -> IndependenceProbe.start(server));
        }
        if (Files.exists(dir.resolve("aerogel.vanillaclient"))) {
            server.execute(() -> VanillaClientProbe.start(server));
        }
        // #14 needs the real adaptive path too: it waits until the server idles, then teleports a FakePlayer
        if (Files.exists(dir.resolve("aerogel.teleport"))) {
            server.execute(() -> TeleportProbe.start(server));
        }
        // the drain rate keeps the REAL adaptive path: it measures the refill's calm bypass and the pacer
        if (Files.exists(dir.resolve("aerogel.rate"))) {
            server.execute(() -> RateProbe.start(server));
        }
        if (Files.exists(dir.resolve("aerogel.footprint"))) {
            server.execute(() -> FootprintProbe.start(server));
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
        DrainProbe.tick(server);
        InFlightProbe.tick(server);
        StationaryProbe.tick(server);
        IndependenceProbe.tick(server);
        VanillaClientProbe.tick(server);
        TeleportProbe.tick(server);
        RateProbe.tick(server);
        FootprintProbe.tick(server);
    }

    @Override
    public void onServerTickPre(MinecraftServer server) {
        RateProbe.tickPre(); // inside the tick-time measurement, which is the point of it
    }
}
