package dev.aerogel.client;

import net.neoforged.neoforge.common.ModConfigSpec;
import org.apache.commons.lang3.tuple.Pair;

/**
 * Client-only configuration ({@code config/aerogel-client.toml}). Holds the bisect switches and the proxy
 * draw-radius override so the client restores its state on launch; {@code /aerogel switch} and
 * {@code /aerogel detail draw} write back and save. Only registered on the physical client.
 */
public final class AeroGelClientConfig {
    public static final AeroGelClientConfig CFG;
    public static final ModConfigSpec SPEC;

    /** master: this client renders/uses proxies at all (draw + clamp + reconcile + neighbour override). */
    public final ModConfigSpec.BooleanValue proxy;
    /** per-player request: "I want to travel without generating chunks" (use the linger gate). */
    public final ModConfigSpec.BooleanValue travel;
    public final ModConfigSpec.BooleanValue voidQuad;
    public final ModConfigSpec.BooleanValue anim;
    public final ModConfigSpec.BooleanValue sync;
    public final ModConfigSpec.BooleanValue store;
    public final ModConfigSpec.BooleanValue clamp;
    public final ModConfigSpec.BooleanValue reconcile;
    public final ModConfigSpec.BooleanValue wireframe;
    public final ModConfigSpec.BooleanValue neighbors;
    public final ModConfigSpec.IntValue drawRadius;

    static {
        Pair<AeroGelClientConfig, ModConfigSpec> pair =
            new ModConfigSpec.Builder().configure(AeroGelClientConfig::new);
        CFG = pair.getLeft();
        SPEC = pair.getRight();
    }

    private AeroGelClientConfig(ModConfigSpec.Builder b) {
        b.comment("Client-side proxy behaviour. These mirror /aerogel switch <feature> and persist across restarts.")
            .push("switches");
        proxy = b.comment("Master: use proxies at all (draw, walking clamp, reconciliation, neighbour override).")
            .define("proxy", true);
        travel = b.comment("Request proxy travel from the server: don't generate chunks until you linger/touch.")
            .define("travel", false);
        voidQuad = b.comment("The single flat void-filler quad.")
            .define("void", true);
        anim = b.comment("The rise/sink draw-matrix animation.")
            .define("anim", false);
        sync = b.comment("The periodic sync: wall re-bakes, real-chunk takeover, chunk uploads.")
            .define("sync", true);
        store = b.comment("Store incoming mesh payloads (off = the client ignores meshes).")
            .define("store", true);
        clamp = b.comment("The client-side walking clamp.")
            .define("clamp", true);
        reconcile = b.comment("The per-second missing-mesh reconciliation requests.")
            .define("reconcile", true);
        wireframe = b.comment("Debug wireframe over the proxy top quads (toggled via the shared client-switch payload).")
            .define("wireframe", false);
        neighbors = b.comment("Count a proxy neighbour as an existing chunk for vanilla's render visibility graph.")
            .define("neighbors", true);
        b.pop();
        drawRadius = b.comment("Proxy draw radius in chunks. 0 = use the vanilla render-distance option.")
            .defineInRange("drawRadius", 0, 0, 1000);
    }
}
