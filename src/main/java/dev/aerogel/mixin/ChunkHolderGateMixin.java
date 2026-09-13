package dev.aerogel.mixin;

import dev.aerogel.server.ProxyChunkGate;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ChunkResult;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The own-task gate. {@code ChunkHolder.updateFutures} reacts to a ticket level ≥ FULL by building the
 * chunk's cached full/ticking/entity-ticking futures; those calls are what create the chunk's OWN generation
 * task (via {@code ChunkMap.prepareAccessibleChunk} → {@code getChunkRangeFuture} → the deep fan-out). For a
 * proxy position we return the unloaded sentinel instead, so the FULL future is never created and the chunk
 * never becomes accessible/ticking. Dependency generation ({@code applyStep}) is left untouched.
 *
 * <p>The classification probe is kicked off at HEAD (see {@link ProxyChunkGate#onUpdateFutures}); it runs on
 * the server thread but its callback is queued so it cannot re-enter {@code updateFutures}.</p>
 */
@Mixin(ChunkHolder.class)
public abstract class ChunkHolderGateMixin {

    @Inject(method = "updateFutures", at = @At("HEAD"))
    private void aerogel$classifyProxy(ChunkMap chunkMap, Executor executor, CallbackInfo ci) {
        ProxyChunkGate.onUpdateFutures((ChunkHolder) (Object) this, chunkMap);
    }

    @Redirect(
        method = "updateFutures",
        at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/level/ChunkMap;prepareAccessibleChunk(Lnet/minecraft/server/level/ChunkHolder;)Ljava/util/concurrent/CompletableFuture;")
    )
    private CompletableFuture<ChunkResult<LevelChunk>> aerogel$gateFull(ChunkMap chunkMap, ChunkHolder holder) {
        if (ProxyChunkGate.isGated(holder.getPos().toLong())) {
            return ChunkHolderAccessor.unloadedFuture();
        }
        return chunkMap.prepareAccessibleChunk(holder);
    }

    @Redirect(
        method = "updateFutures",
        at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/level/ChunkMap;prepareTickingChunk(Lnet/minecraft/server/level/ChunkHolder;)Ljava/util/concurrent/CompletableFuture;")
    )
    private CompletableFuture<ChunkResult<LevelChunk>> aerogel$gateTicking(ChunkMap chunkMap, ChunkHolder holder) {
        if (ProxyChunkGate.isGated(holder.getPos().toLong())) {
            return ChunkHolderAccessor.unloadedFuture();
        }
        return chunkMap.prepareTickingChunk(holder);
    }

    @Redirect(
        method = "updateFutures",
        at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/level/ChunkMap;prepareEntityTickingChunk(Lnet/minecraft/server/level/ChunkHolder;)Ljava/util/concurrent/CompletableFuture;")
    )
    private CompletableFuture<ChunkResult<LevelChunk>> aerogel$gateEntityTicking(ChunkMap chunkMap, ChunkHolder holder) {
        if (ProxyChunkGate.isGated(holder.getPos().toLong())) {
            return ChunkHolderAccessor.unloadedFuture();
        }
        return chunkMap.prepareEntityTickingChunk(holder);
    }
}
