package dev.aerogel.mixin;

import dev.aerogel.server.ProxyChunkGate;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The central read intercept. Every blocking "give me the chunk" path — {@code Level.getBlockState},
 * {@code Level.getChunkForCollisions}, {@code Entity.setPosRaw}'s ensure-loaded, dependency lookups — funnels
 * through {@code ServerChunkCache.getChunk}, which would otherwise {@code managedBlock} on a generation
 * future that a proxy never builds. For a proxy position we hand back a cached read-only placeholder; a
 * promoted/known-real position falls through to vanilla (which loads/generates it forward). Only a request
 * that would have to GENERATE terrain is deferred: a chunk on disk at FULL is loaded (the first read of an
 * unclassified position gets the placeholder while an async disk read classifies it), a chunk already in
 * memory at the requested status is returned, {@code requireChunk=false} gets {@code null} (never a
 * placeholder), and structure lookups of a not-generated chunk get the placeholder too. A promoted chunk
 * whose FULL future is still generating is answered the same way until it is ready. We deliberately
 * do NOT route proxies through {@code getChunkFutureMainThread}: that would add a {@code TicketType.UNKNOWN}
 * ticket and create a task.
 *
 * <p>Dispatch is on {@code self.getLevel()} so a nether/end read never consults the overworld gate. A cheap
 * per-level {@code mayHaveProxy} bail runs first, so a dormant level costs one volatile read instead of the
 * full classification chain (which includes a {@code getChunkNow} lookup).</p>
 */
@Mixin(ServerChunkCache.class)
public class ServerChunkCachePlaceholderMixin {

    @Inject(method = "getChunk", at = @At("HEAD"), cancellable = true)
    private void aerogel$placeholder(int x, int z, ChunkStatus status, boolean requireChunk,
        CallbackInfoReturnable<ChunkAccess> cir) {
        ServerChunkCache self = (ServerChunkCache) (Object) this;
        ServerLevel level = (ServerLevel) self.getLevel();
        // off-thread callers are offloaded to the main thread by vanilla and re-enter this method there
        if (!level.getServer().isSameThread()) {
            return;
        }
        if (!ProxyChunkGate.mayHaveProxy(level)) {
            return; // dormant level: no proxy and not engaged -> vanilla
        }
        // structure lookups (/locate, maps, location advancements every second per player) take the same path:
        // vanilla blocked seconds behind worldgen for them during flight. Maric 2026-10-13: land only flown
        // past is not discovered, so its structures stay empty until it is generated.
        long pos = ChunkPos.asLong(x, z);
        // a promotion still generating is read like a proxy: vanilla would managedBlock on its FULL future
        boolean promoting = ProxyChunkGate.isPromoting(level, pos);
        if (!promoting && !ProxyChunkGate.isProxy(level, pos)) {
            return; // real / promoted / disengaged: vanilla loads or generates it
        }
        if (ProxyChunkGate.isAvailable(level, pos, status)) {
            return; // already in memory at that status: a lookup, not generation
        }
        if (!requireChunk) {
            cir.setReturnValue(null); // a peek: vanilla's answer for a not-loaded chunk, never a placeholder
            return;
        }
        if (!promoting) {
            ProxyChunkGate.probeDisk(level, x, z); // async; FULL on disk reads real from the next tick on
        }
        cir.setReturnValue(ProxyChunkGate.placeholder(level, x, z));
    }
}
