package dev.aerogel.client;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.RegisterSpriteSourceTypesEvent;

/** Client-only mod-bus registrations (registered from {@code AeroGel} only when the dist is client). */
public final class ClientSetup {
    private ClientSetup() {
    }

    @SubscribeEvent
    public static void onRegisterSpriteSourceTypes(RegisterSpriteSourceTypesEvent event) {
        LeafCompositeSource.register(event);
    }
}
