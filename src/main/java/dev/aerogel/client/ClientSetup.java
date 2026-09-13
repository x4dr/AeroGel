package dev.aerogel.client;

import java.util.concurrent.CompletableFuture;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.RegisterClientReloadListenersEvent;
import net.neoforged.neoforge.client.event.RegisterSpriteSourceTypesEvent;

/** Client-only mod-bus registrations (registered from {@code AeroGel} only when the dist is client). */
public final class ClientSetup {
    private ClientSetup() {
    }

    @SubscribeEvent
    public static void onRegisterSpriteSourceTypes(RegisterSpriteSourceTypesEvent event) {
        LeafCompositeSource.register(event);
    }

    /**
     * Resource reloads (F3+T, resource-pack change) rebuild the block atlas, so every sprite the proxy
     * renderer cached is stale. Register a listener that clears the Java-side caches and forces the proxy
     * buffers to re-bake against the new atlas; the leaf composite atlas rebuilds itself.
     */
    @SubscribeEvent
    public static void onRegisterReloadListeners(RegisterClientReloadListenersEvent event) {
        event.registerReloadListener((barrier, resourceManager, prepareProfiler, applyProfiler,
                prepareExecutor, applyExecutor) -> CompletableFuture.completedFuture(null)
                    .thenCompose(barrier::wait)
                    .thenRunAsync(ProxyRenderer::clearSpriteCaches, applyExecutor));
    }
}
