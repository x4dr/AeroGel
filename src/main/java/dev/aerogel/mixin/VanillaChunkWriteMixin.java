package dev.aerogel.mixin;

import dev.aerogel.server.PromotionManager;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Flags the writes vanilla makes into a chunk <b>after</b> its generation reports complete, so
 * {@link PromotionManager} can replay them ahead of the caller's own deferred writes (#16).
 *
 * <p>Feature placement is not the problem: {@code WorldGenRegion} overrides {@code setBlock} with a 4-arg
 * signature and writes straight to the chunk, so it never reaches the write guard. This hook covers the
 * other post-generation step — cave-air cleanup and ore/lichen replacement — which does go through
 * {@code Level.setBlock} and would otherwise be replayed on top of a player's block.</p>
 *
 * @see VanillaBlockTickMixin
 */
@Mixin(LevelChunk.class)
public abstract class VanillaChunkWriteMixin {

    @Inject(method = "postProcessGeneration", at = @At("HEAD"))
    private void aerogel$enterPostProcess(CallbackInfo ci) {
        PromotionManager.pushVanillaWrite();
    }

    @Inject(method = "postProcessGeneration", at = @At("RETURN"))
    private void aerogel$exitPostProcess(CallbackInfo ci) {
        PromotionManager.popVanillaWrite();
    }
}
