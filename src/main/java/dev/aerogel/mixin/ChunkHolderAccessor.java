package dev.aerogel.mixin;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ChunkResult;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Lets us force a {@code ChunkHolder} to rebuild its cached ticking/entity-ticking futures. Those are
 * (re)created in {@code updateFutures} only when the ticket level crosses a full-status boundary, so after
 * we re-generate a chunk in place they still point at the old stub — meaning the chunk would be skipped by
 * block ticking, natural spawning and block-change broadcasts. Setting {@code oldTicketLevel} high and
 * re-running {@code updateFutures} rebuilds them from the (now real) status futures.
 *
 * <p>The cached futures MUST be reset to the unloaded sentinel first: {@code updateFutures}'s
 * ENTITY_TICKING promotion branch throws {@code IllegalStateException} if {@code entityTickingChunkFuture}
 * is not the sentinel (vanilla relies on the demote branch having reset it). Skipping this left the holder
 * half-demoted and crashed the next vanilla {@code DistanceManager.runAllUpdates}.</p>
 */
@Mixin(ChunkHolder.class)
public interface ChunkHolderAccessor {

    /** the exact sentinel object {@code updateFutures} compares against by identity. */
    @Accessor("UNLOADED_LEVEL_CHUNK_FUTURE")
    static CompletableFuture<ChunkResult<LevelChunk>> unloadedFuture() {
        throw new AssertionError();
    }

    @Mutable
    @Accessor("oldTicketLevel")
    void setOldTicketLevel(int level);

    @Mutable
    @Accessor("fullChunkFuture")
    void setFullChunkFuture(CompletableFuture<ChunkResult<LevelChunk>> future);

    @Mutable
    @Accessor("tickingChunkFuture")
    void setTickingChunkFuture(CompletableFuture<ChunkResult<LevelChunk>> future);

    @Mutable
    @Accessor("entityTickingChunkFuture")
    void setEntityTickingChunkFuture(CompletableFuture<ChunkResult<LevelChunk>> future);

    @Invoker("updateFutures")
    void invokeUpdateFutures(ChunkMap chunkMap, Executor executor);
}
