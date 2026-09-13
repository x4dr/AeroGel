package dev.aerogel.mixin;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.ViewArea;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** exposes the renderer's view area and the exact set of sections it draws this frame. */
@Mixin(LevelRenderer.class)
public interface LevelRendererAccessor {
    @Accessor("viewArea")
    ViewArea aerogel$viewArea();

    @Accessor("visibleSections")
    ObjectArrayList<SectionRenderDispatcher.RenderSection> aerogel$visibleSections();
}
