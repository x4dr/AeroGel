package dev.aerogel.client;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.serialization.MapCodec;
import java.util.Map;
import java.util.Optional;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.atlas.SpriteResourceLoader;
import net.minecraft.client.renderer.texture.atlas.SpriteSource;
import net.minecraft.client.renderer.texture.atlas.SpriteSourceType;
import net.minecraft.client.resources.metadata.animation.FrameSize;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.ResourceMetadata;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.client.event.RegisterSpriteSourceTypesEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Adds an opaque variant of every {@code *_leaves} block texture to the block atlas: transparent pixels
 * are filled with the grass texture, so the proxy can draw a single layer with no underlay and no
 * see-through holes. Registered as a sprite source by {@code ClientSetup}; see
 * {@code assets/aerogel/atlases/blocks.json}.
 */
@OnlyIn(Dist.CLIENT)
public final class LeafCompositeSource implements SpriteSource {
    private static final Logger LOGGER = LoggerFactory.getLogger("aerogel.texture");
    private static final ResourceLocation GRASS = ResourceLocation.withDefaultNamespace("block/grass_block_top");
    private static final ResourceLocation TYPE_ID = ResourceLocation.fromNamespaceAndPath("aerogel", "leaf_composite");
    public static final MapCodec<LeafCompositeSource> CODEC = MapCodec.unit(new LeafCompositeSource());
    private static SpriteSourceType type;

    public static void register(RegisterSpriteSourceTypesEvent event) {
        type = new SpriteSourceType(CODEC);
        event.register(TYPE_ID, type);
    }

    /** the atlas sprite id holding the opaque composite of a leaf sprite (see {@link #run}). */
    public static ResourceLocation compositeId(ResourceLocation leafSprite) {
        return ResourceLocation.fromNamespaceAndPath("aerogel",
            "leaf_composite/" + leafSprite.getNamespace() + "/" + leafSprite.getPath());
    }

    @Override
    public void run(ResourceManager resourceManager, SpriteSource.Output output) {
        Optional<Resource> grass = resourceManager.getResource(TEXTURE_ID_CONVERTER.idToFile(GRASS));
        if (grass.isEmpty()) {
            LOGGER.warn("leaf composite: missing {}", GRASS);
            return;
        }
        int count = 0;
        for (Map.Entry<ResourceLocation, Resource> e : TEXTURE_ID_CONVERTER.listMatchingResources(resourceManager).entrySet()) {
            if (!e.getKey().getPath().endsWith("_leaves.png")) {
                continue;
            }
            ResourceLocation leafId = TEXTURE_ID_CONVERTER.fileToId(e.getKey());
            ResourceLocation compositeId = compositeId(leafId);
            Resource leafResource = e.getValue();
            Resource grassResource = grass.get();
            output.add(compositeId, loader -> build(loader, compositeId, leafId, leafResource, grassResource));
            count++;
        }
        LOGGER.info("leaf composite: registered {} opaque leaf sprites", count);
    }

    private static SpriteContents build(SpriteResourceLoader loader, ResourceLocation compositeId,
            ResourceLocation leafId, Resource leafResource, Resource grassResource) {
        SpriteContents leaf = loader.loadSprite(leafId, leafResource);
        SpriteContents grass = loader.loadSprite(GRASS, grassResource);
        if (leaf == null || grass == null) {
            return null;
        }
        NativeImage out = composite(leaf, grass);
        return new SpriteContents(compositeId, new FrameSize(out.getWidth(), out.getHeight()), out,
            ResourceMetadata.EMPTY);
    }

    /** leaf pixels win; transparent ones are replaced with the (scaled) grass pixel underneath. */
    private static NativeImage composite(SpriteContents leaf, SpriteContents grass) {
        int w = leaf.width();
        int h = leaf.height();
        NativeImage leafImage = leaf.getOriginalImage();
        NativeImage grassImage = grass.getOriginalImage();
        int gw = grass.width();
        int gh = grass.height();
        NativeImage out = new NativeImage(w, h, false);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int leafPixel = leafImage.getPixelRGBA(x, y);
                if (((leafPixel >>> 24) & 0xFF) >= 26) {
                    out.setPixelRGBA(x, y, leafPixel);
                } else {
                    out.setPixelRGBA(x, y, grassImage.getPixelRGBA(x * gw / w, y * gh / h));
                }
            }
        }
        return out;
    }

    @Override
    public SpriteSourceType type() {
        return type;
    }
}
