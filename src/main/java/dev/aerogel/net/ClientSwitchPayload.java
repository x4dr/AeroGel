package dev.aerogel.net;

import dev.aerogel.AeroGel;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Server→client debug switch: enable/disable one part of the client mod so a hitch can be bisected.
 * The feature list lives here (common) so the server command can validate without loading client classes.
 */
public record ClientSwitchPayload(String feature, boolean on) implements CustomPacketPayload {
    public static final String[] FEATURES = {
        "proxy", "travel", "void", "anim", "sync", "store", "clamp", "reconcile", "wireframe", "neighbors"
    };

    public static final Type<ClientSwitchPayload> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(AeroGel.MOD_ID, "client_switch"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ClientSwitchPayload> STREAM_CODEC =
        StreamCodec.ofMember(ClientSwitchPayload::write, ClientSwitchPayload::read);

    private static void write(ClientSwitchPayload payload, RegistryFriendlyByteBuf buf) {
        buf.writeUtf(payload.feature());
        buf.writeBoolean(payload.on());
    }

    private static ClientSwitchPayload read(RegistryFriendlyByteBuf buf) {
        return new ClientSwitchPayload(buf.readUtf(), buf.readBoolean());
    }

    public static boolean isFeature(String name) {
        for (String feature : FEATURES) {
            if (feature.equals(name)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The client-side default for a feature. The server seeds its bare-toggle mirror from this; a mismatch
     * makes the first {@code /aerogel switch <feature>} send the already-current state (e.g. wireframe and
     * anim default OFF, so assuming ON turned the first toggle into a no-op).
     */
    public static boolean defaultOn(String feature) {
        return switch (feature) {
            case "anim", "wireframe", "travel" -> false;
            default -> true;
        };
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
