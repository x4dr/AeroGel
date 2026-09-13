package dev.aerogel.mixin;

import net.minecraft.client.renderer.ViewArea;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** exposes the protected {@code ViewArea.getRenderSectionAt} for the proxy renderer's compiled check. */
@Mixin(ViewArea.class)
public interface ViewAreaInvoker {
    @Invoker("getRenderSectionAt")
    SectionRenderDispatcher.RenderSection aerogel$getRenderSectionAt(BlockPos pos);
}
