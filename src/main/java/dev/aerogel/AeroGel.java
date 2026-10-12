package dev.aerogel;

import dev.aerogel.client.ClientHandlers;
import dev.aerogel.client.ClientMeshStore;
import dev.aerogel.common.AeroGelConfig;
import dev.aerogel.net.ClientSettingPayload;
import dev.aerogel.net.ClientSwitchPayload;
import dev.aerogel.net.MeshBatchPayload;
import dev.aerogel.net.MeshRequestPayload;
import dev.aerogel.server.ProxyStreamer;
import dev.aerogel.server.ServerHandlers;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

@Mod(AeroGel.MOD_ID)
public final class AeroGel {
    public static final String MOD_ID = "aerogel";

    public AeroGel(IEventBus modEventBus, ModContainer modContainer) {
        modEventBus.register(this);
        modContainer.registerConfig(ModConfig.Type.COMMON, AeroGelConfig.SPEC);
        NeoForge.EVENT_BUS.register(ServerHandlers.class);
        if (FMLEnvironment.dist.isClient()) {
            modContainer.registerConfig(ModConfig.Type.CLIENT, dev.aerogel.client.AeroGelClientConfig.SPEC);
            modEventBus.register(dev.aerogel.client.ClientSetup.class);
            NeoForge.EVENT_BUS.register(ClientHandlers.class);
            NeoForge.EVENT_BUS.register(dev.aerogel.client.ClientPerf.class);
        }
    }

    /**
     * Cross-field config sanity (see {@link AeroGelConfig#validate()}), run before anything reads a config
     * value. It is deliberately not hung off {@code ModConfigEvent}: NeoForge fires that for the first
     * config while the others are still unloaded, and a value read at that point throws.
     */
    @SubscribeEvent
    void onRegisterPayloads(RegisterPayloadHandlersEvent event) {
        event.registrar("1").playToClient(MeshBatchPayload.TYPE, MeshBatchPayload.STREAM_CODEC,
            (payload, context) -> context.enqueueWork(() -> ClientMeshStore.handle(payload)));
        event.registrar("1").playToServer(MeshRequestPayload.TYPE, MeshRequestPayload.STREAM_CODEC,
            (payload, context) -> context.enqueueWork(() -> ProxyStreamer.onMeshRequest((ServerPlayer) context.player(), payload)));
        event.registrar("1").playBidirectional(ClientSwitchPayload.TYPE, ClientSwitchPayload.STREAM_CODEC,
            new net.neoforged.neoforge.network.handling.DirectionalPayloadHandler<>(
                (payload, context) -> context.enqueueWork(() -> dev.aerogel.client.ClientFeatures.set(payload.feature(), payload.on())),
                (payload, context) -> context.enqueueWork(() -> ServerHandlers.onClientSwitch((ServerPlayer) context.player(), payload))));
        event.registrar("1").playToClient(ClientSettingPayload.TYPE, ClientSettingPayload.STREAM_CODEC,
            (payload, context) -> context.enqueueWork(() -> dev.aerogel.client.ClientHandlers.applySetting(payload)));
    }
}
