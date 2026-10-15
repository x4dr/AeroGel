package dev.aerogel.mixin;

import dev.aerogel.server.PromotionManager;
import dev.aerogel.server.ProxyChunkGate;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * General block-write guard. Every runtime write (player edit, explosion, falling block, mod code) funnels
 * through {@code Level.setBlock}; worldgen does not (it goes through {@code WorldGenRegion}, guarded
 * separately), which is exactly the split we want. A write into a proxy stub is cancelled and remembered by
 * {@link PromotionManager}, the chunk is queued for real generation, and the write is replayed onto the
 * real terrain once it exists — so "write = promotion" without ever running full generation on this thread.
 * Returning true keeps the caller's client prediction intact (the block is not visibly reverted).
 */
@Mixin(Level.class)
public class LevelWriteGuardMixin {

    @Inject(
        method = "setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;I)Z",
        at = @At("HEAD"),
        cancellable = true
    )
    private void aerogel$deferWriteIntoStub(BlockPos pos, BlockState state, int flags, CallbackInfoReturnable<Boolean> cir) {
        if (!((Object) this instanceof ServerLevel serverLevel)) {
            return;
        }
        if (!ProxyChunkGate.mayHaveProxy(serverLevel)) {
            return; // dormant level: no proxy and not engaged -> vanilla write
        }
        if (PromotionManager.refuseStubWrite(serverLevel, pos)) {
            // server-owner mode: the write did not happen, so tell the caller so. Placing/piston/deployer
            // retry against real terrain; the chunk was promoted above, so the next attempt succeeds.
            cir.setReturnValue(false);
            return;
        }
        if (PromotionManager.tryDeferWrite(serverLevel, pos, state, flags)) {
            cir.setReturnValue(true);
        }
    }
}
