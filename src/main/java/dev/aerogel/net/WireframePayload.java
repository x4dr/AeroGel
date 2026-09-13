package dev.aerogel.net;

import dev.aerogel.AeroGel;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** toggles the client-side debug wireframe over proxy top quads. */
public record WireframePayload(boolean on) implements CustomPacketPayload {
    public static final Type<WireframePayload> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(AeroGel.MOD_ID, "wireframe"));

    public static final StreamCodec<RegistryFriendlyByteBuf, WireframePayload> STREAM_CODEC =
        StreamCodec.ofMember(WireframePayload::write, WireframePayload::read);

    private static void write(WireframePayload payload, RegistryFriendlyByteBuf buf) {
        buf.writeBoolean(payload.on());
    }

    private static WireframePayload read(RegistryFriendlyByteBuf buf) {
        return new WireframePayload(buf.readBoolean());
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
