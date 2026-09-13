package dev.aerogel.mixin;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** exposes the exact set of sections the renderer draws this frame (proxy hide/show). */
@Mixin(LevelRenderer.class)
public interface LevelRendererAccessor {
    @Accessor("visibleSections")
    ObjectArrayList<SectionRenderDispatcher.RenderSection> aerogel$visibleSections();
}
