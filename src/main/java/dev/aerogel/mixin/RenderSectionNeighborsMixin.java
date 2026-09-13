package dev.aerogel.mixin;

import dev.aerogel.client.ClientFeatures;
import dev.aerogel.client.ClientMeshStore;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.ChunkPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * AeroGel renders proxy terrain itself, so a proxy position has no client {@code LevelChunk}. Vanilla's
 * visibility graph assumes contiguous client chunks, and a proxy section breaks it two ways:
 *
 * <ul>
 *   <li>{@code RenderSection.hasAllNeighbors()} checks {@code doesChunkExistAt} for the four horizontal
 *       neighbours; a real chunk next to a proxy looks neighbour-less past 24 blocks, so it is parked in
 *       {@code chunksWaitingForNeighbors} and its rebuild is cancelled.</li>
 *   <li>A proxy section is never compiled, so its {@code getCompiled()} is {@code UNCOMPILED}, whose
 *       {@code facesCanSeeEachother} is false. {@code SectionOcclusionGraph.runUpdates}'s smart-cull step
 *       uses the current section's compiled visibility to decide whether to propagate, so the walk stops at
 *       the proxy and real chunks beyond it are never reached. A compiled empty section returns true, so
 *       report a proxy section as {@code EMPTY}.</li>
 * </ul>
 *
 * <p>Both gated on {@link ClientFeatures#neighbors}. Only positions AeroGel has a proxy mesh for are
 * affected; a genuinely unloaded neighbour is unchanged.</p>
 */
@Mixin(SectionRenderDispatcher.RenderSection.class)
public class RenderSectionNeighborsMixin {

    @Inject(method = "doesChunkExistAt", at = @At("RETURN"), cancellable = true)
    private void aerogel$proxyCountsAsChunk(BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        if (ClientFeatures.proxy && ClientFeatures.neighbors && !cir.getReturnValueZ() && isProxy(pos)) {
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "getCompiled", at = @At("RETURN"), cancellable = true)
    private void aerogel$proxyIsTransparent(CallbackInfoReturnable<SectionRenderDispatcher.CompiledSection> cir) {
        if (ClientFeatures.proxy && ClientFeatures.neighbors
            && cir.getReturnValue() == SectionRenderDispatcher.CompiledSection.UNCOMPILED) {
            BlockPos origin = ((SectionRenderDispatcher.RenderSection) (Object) this).getOrigin();
            if (isProxy(origin)) {
                cir.setReturnValue(SectionRenderDispatcher.CompiledSection.EMPTY);
            }
        }
    }

    private static boolean isProxy(BlockPos pos) {
        long chunk = ChunkPos.asLong(SectionPos.blockToSectionCoord(pos.getX()), SectionPos.blockToSectionCoord(pos.getZ()));
        return ClientMeshStore.isProxy(chunk);
    }
}
