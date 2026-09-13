package dev.aerogel.client;

/**
 * Runtime switches for bisecting client-side hitches via {@code /aerogel switch <feature> [on|off]}.
 * The persisted state lives in {@link AeroGelClientConfig}; {@link #load()} restores it on startup and
 * every {@link #set} writes back so it survives a restart.
 */
public final class ClientFeatures {
    /** master: use proxies at all (draw, walking clamp, reconciliation, neighbour override). */
    public static volatile boolean proxy = true;
    /** per-player request: "I want to travel without generating chunks" (use the linger gate). */
    public static volatile boolean travel = false;
    /** the single flat void-filler quad. */
    public static volatile boolean voidQuad = true;
    /** the rise/sink draw-matrix animation; off by default so proxies pop in with no morph. */
    public static volatile boolean anim = false;
    /** the periodic sync: wall re-bakes, real-chunk takeover, chunk uploads. */
    public static volatile boolean sync = true;
    /** storing incoming mesh payloads (off = the client ignores meshes). */
    public static volatile boolean store = true;
    /** the client-side walking clamp. */
    public static volatile boolean clamp = true;
    /** the per-second missing-mesh reconciliation requests. */
    public static volatile boolean reconcile = true;
    /** count a proxy neighbour as an existing chunk for vanilla's {@code RenderSection.hasAllNeighbors}. */
    public static volatile boolean neighbors = true;

    private ClientFeatures() {
    }

    /** restore every switch (and the renderer's own wireframe/draw radius) from the CLIENT config. */
    public static void load() {
        AeroGelClientConfig cfg = AeroGelClientConfig.CFG;
        proxy = cfg.proxy.get();
        travel = cfg.travel.get();
        voidQuad = cfg.voidQuad.get();
        anim = cfg.anim.get();
        sync = cfg.sync.get();
        store = cfg.store.get();
        clamp = cfg.clamp.get();
        reconcile = cfg.reconcile.get();
        neighbors = cfg.neighbors.get();
        ProxyRenderer.setWireframe(cfg.wireframe.get());
        ProxyRenderer.setDrawRadius(cfg.drawRadius.get());
    }

    /** apply a feature switch; returns false if the name is unknown. Persists the new value. */
    public static boolean set(String feature, boolean on) {
        AeroGelClientConfig cfg = AeroGelClientConfig.CFG;
        switch (feature) {
            case "proxy" -> {
                proxy = on;
                cfg.proxy.set(on);
            }
            case "travel" -> {
                travel = on;
                cfg.travel.set(on);
            }
            case "void" -> {
                voidQuad = on;
                cfg.voidQuad.set(on);
            }
            case "anim" -> {
                anim = on;
                cfg.anim.set(on);
            }
            case "sync" -> {
                sync = on;
                cfg.sync.set(on);
            }
            case "store" -> {
                store = on;
                cfg.store.set(on);
            }
            case "clamp" -> {
                clamp = on;
                cfg.clamp.set(on);
            }
            case "reconcile" -> {
                reconcile = on;
                cfg.reconcile.set(on);
            }
            case "neighbors" -> {
                neighbors = on;
                cfg.neighbors.set(on);
            }
            case "wireframe" -> ProxyRenderer.setWireframe(on);
            default -> {
                return false;
            }
        }
        AeroGelClientConfig.SPEC.save();
        return true;
    }

    /** current state of a feature (false if unknown). */
    public static boolean get(String feature) {
        return switch (feature) {
            case "proxy" -> proxy;
            case "travel" -> travel;
            case "void" -> voidQuad;
            case "anim" -> anim;
            case "sync" -> sync;
            case "store" -> store;
            case "clamp" -> clamp;
            case "reconcile" -> reconcile;
            case "neighbors" -> neighbors;
            case "wireframe" -> ProxyRenderer.isWireframe();
            default -> false;
        };
    }
}
