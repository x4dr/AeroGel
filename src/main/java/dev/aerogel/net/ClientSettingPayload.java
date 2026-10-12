package dev.aerogel.net;

import dev.aerogel.AeroGel;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Server→client integer setting relay, so client-only render settings (draw radius) are reachable from
 * {@code /aerogel} without JVM arguments. The server does not track the value.
 */
public record ClientSettingPayload(String feature, int value) implements CustomPacketPayload {
    public static final String DRAW_RADIUS = "drawRadius";
    /** one-shot: log a proxy-layer diagnostic block on the client. */
    public static final String DIAG = "diag";

    public static final Type<ClientSettingPayload> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(AeroGel.MOD_ID, "client_setting"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ClientSettingPayload> STREAM_CODEC =
        StreamCodec.ofMember(ClientSettingPayload::write, ClientSettingPayload::read);

    private static void write(ClientSettingPayload payload, RegistryFriendlyByteBuf buf) {
        buf.writeUtf(payload.feature());
        buf.writeVarInt(payload.value());
    }

    private static ClientSettingPayload read(RegistryFriendlyByteBuf buf) {
        return new ClientSettingPayload(buf.readUtf(), buf.readVarInt());
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
