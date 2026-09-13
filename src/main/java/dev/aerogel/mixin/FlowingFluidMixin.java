package dev.aerogel.mixin;

import dev.aerogel.server.ProxyChunkGate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.material.FluidState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Fluid spread is the one world-tick thing that would write into an ungenerated proxy. {@code spreadTo} is
 * the single choke point ALL fluid spread goes through — cancel it when the target chunk is a proxy. Real
 * chunks are unaffected. (Worldgen writes go through {@code WorldGenRegion}, not this path, and are left
 * vanilla so a promoted chunk's features can write into proxy neighbours.)
 */
@Mixin(FlowingFluid.class)
public class FlowingFluidMixin {

    @Inject(
        method = "spreadTo(Lnet/minecraft/world/level/LevelAccessor;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/core/Direction;Lnet/minecraft/world/level/material/FluidState;)V",
        at = @At("HEAD"),
        cancellable = true
    )
    private void aerogel$noSpreadIntoProxies(LevelAccessor level, BlockPos pos, BlockState blockState, Direction direction, FluidState fluidState, CallbackInfo ci) {
        if (level instanceof ServerLevel && ProxyChunkGate.isProxy(ChunkPos.asLong(pos))) {
            ci.cancel();
        }
    }
}
