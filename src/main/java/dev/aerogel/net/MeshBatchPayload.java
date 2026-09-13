package dev.aerogel.net;

import dev.aerogel.AeroGel;
import dev.aerogel.common.ProxyTopology;
import io.netty.handler.codec.DecoderException;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Server→client batch of proxy chunk meshes: the batch's detail tier (topology), N chunk positions, then
 * N * heightsPerChunk lattice heights (shorts), N packed RGB biome colours (varint, -1 = none) and N
 * representative block-state ids (varint, 0 = none). Written manually with StreamCodec.ofMember
 * (fixed-size longs, no collection helpers — one batch, flat arrays).
 *
 * <p>The tier travels with the batch so the client knows which grid to bake. Batches are grouped by tier
 * server-side (coarse far chunks and fine near chunks go out in separate packets), so a chunk's tier is a
 * property of the batch it arrived in.</p>
 */
public record MeshBatchPayload(byte topology, long[] chunkPos, short[] heights, int[] biomes, int[] blocks)
        implements CustomPacketPayload {
    public static final Type<MeshBatchPayload> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(AeroGel.MOD_ID, "mesh_batch"));

    public static final StreamCodec<RegistryFriendlyByteBuf, MeshBatchPayload> STREAM_CODEC = StreamCodec.ofMember(
        MeshBatchPayload::write, MeshBatchPayload::read);

    /** Upper bound on chunks per batch, so a malicious/buggy peer cannot force a huge allocation. */
    private static final int MAX_CHUNKS = 4096;

    public int heightsPerChunk() {
        return ProxyTopology.count(topology);
    }

    private static void write(MeshBatchPayload payload, RegistryFriendlyByteBuf buf) {
        buf.writeByte(payload.topology());
        buf.writeVarInt(payload.chunkPos().length);
        for (long pos : payload.chunkPos()) {
            buf.writeLong(pos);
        }
        for (short height : payload.heights()) {
            buf.writeShort(height);
        }
        for (int biome : payload.biomes()) {
            buf.writeVarInt(biome + 1); // +1 so the "no biome" sentinel (-1) is a single byte
        }
        for (int block : payload.blocks()) {
            buf.writeVarInt(block);
        }
    }

    private static MeshBatchPayload read(RegistryFriendlyByteBuf buf) {
        byte topology = buf.readByte();
        if (!ProxyTopology.valid(topology)) {
            // Reject before count*count(topology) is used to size anything. The network layer turns this
            // into a peer disconnect, which is preferable to an OOM or an out-of-range grid index later.
            throw new DecoderException("aerogel: invalid mesh topology " + topology);
        }
        int count = buf.readVarInt();
        if (count < 0 || count > MAX_CHUNKS) {
            throw new DecoderException("aerogel: mesh batch count " + count + " out of range [0, " + MAX_CHUNKS + "]");
        }
        long[] pos = new long[count];
        for (int i = 0; i < count; i++) {
            pos[i] = buf.readLong();
        }
        short[] heights = new short[count * ProxyTopology.count(topology)];
        for (int i = 0; i < heights.length; i++) {
            heights[i] = buf.readShort();
        }
        int[] biomes = new int[count];
        for (int i = 0; i < count; i++) {
            biomes[i] = buf.readVarInt() - 1;
        }
        int[] blocks = new int[count];
        for (int i = 0; i < count; i++) {
            blocks[i] = buf.readVarInt();
        }
        return new MeshBatchPayload(topology, pos, heights, biomes, blocks);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
