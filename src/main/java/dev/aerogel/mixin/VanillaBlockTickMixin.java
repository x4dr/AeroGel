package dev.aerogel.mixin;

import dev.aerogel.server.PromotionManager;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Flags block-tick writes so {@link PromotionManager} replays them ahead of the caller's (#16).
 *
 * <p>The case that matters: a leaf placed by worldgen schedules a decay tick. That tick fires on a later
 * server tick — after the chunk has been promoted and the player's deferred writes replayed — and writes
 * the leaf state back, erasing the block the player put there. Vanilla ordering saves it because the player's
 * edit happens after the tick; our replay is not ordered against it.</p>
 *
 * @see VanillaChunkWriteMixin
 */
@Mixin(ServerLevel.class)
public abstract class VanillaBlockTickMixin {

    @Inject(method = "tickBlock", at = @At("HEAD"))
    private void aerogel$enterBlockTick(BlockPos pos, Block block, CallbackInfo ci) {
        PromotionManager.pushVanillaWrite();
    }

    @Inject(method = "tickBlock", at = @At("RETURN"))
    private void aerogel$exitBlockTick(BlockPos pos, Block block, CallbackInfo ci) {
        PromotionManager.popVanillaWrite();
    }
}
