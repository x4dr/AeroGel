package dev.aerogel.net;

import dev.aerogel.AeroGel;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Client→server reconciliation: "I am missing meshes for these chunks" (join-window drops, reconnects,
 * anything). The server replies from its grid cache or queues the chunks for sampling. Cap per request so
 * a desynced client can't spam huge batches; it re-asks every second until satisfied.
 */
public record MeshRequestPayload(long[] chunkPos) implements CustomPacketPayload {
    public static final Type<MeshRequestPayload> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(AeroGel.MOD_ID, "mesh_request"));

    public static final StreamCodec<RegistryFriendlyByteBuf, MeshRequestPayload> STREAM_CODEC = StreamCodec.ofMember(
        MeshRequestPayload::write, MeshRequestPayload::read);

    private static void write(MeshRequestPayload payload, RegistryFriendlyByteBuf buf) {
        buf.writeVarInt(payload.chunkPos().length);
        for (long pos : payload.chunkPos()) {
            buf.writeLong(pos);
        }
    }

    private static MeshRequestPayload read(RegistryFriendlyByteBuf buf) {
        int count = buf.readVarInt();
        long[] pos = new long[count];
        for (int i = 0; i < count; i++) {
            pos[i] = buf.readLong();
        }
        return new MeshRequestPayload(pos);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
