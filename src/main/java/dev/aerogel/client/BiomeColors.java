package dev.aerogel.client;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.registries.Registries;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Client-side biome tint. The server cannot compute this: {@code GrassColor}/{@code FoliageColor} read a
 * static pixel array that is only filled by the client's colormap reload listener, so on a dedicated server
 * {@code Biome.getGrassColor} returns 0 (black). The server therefore streams the biome registry id and the
 * client resolves the colour here.
 */
public final class BiomeColors {
    private static final Logger LOGGER = LoggerFactory.getLogger("aerogel.client");
    private static final int WATER = Block.getId(Blocks.WATER.defaultBlockState());
    private static final int GRASS = Block.getId(Blocks.GRASS_BLOCK.defaultBlockState());
    private static final Map<Integer, Biome> CACHE = new HashMap<>();
    private static final Set<Integer> WARNED_BLACK = new HashSet<>();

    private BiomeColors() {
    }

    /**
     * Tint for the chunk's representative block: biome water colour (water), grass colour (grass), foliage
     * colour (leaves), else white (sand/snow/stone textures are already coloured).
     */
    public static int tint(ClientLevel level, int biomeId, int blockId, int blockX, int blockZ) {
        Biome biome = biome(level, biomeId);
        if (biome == null) {
            return 0xFFFFFF;
        }
        int color;
        if (blockId == WATER) {
            color = biome.getWaterColor();
        } else if (blockId == GRASS) {
            color = biome.getGrassColor(blockX, blockZ);
        } else if (isLeaves(blockId)) {
            color = biome.getFoliageColor();
        } else {
            color = 0xFFFFFF;
        }
        return guard(color, biomeId, blockId);
    }

    /** the biome's grass colour, used as the solid underlay beneath leaf chunks (fills the cutout holes). */
    public static int grassTint(ClientLevel level, int biomeId, int blockX, int blockZ) {
        Biome biome = biome(level, biomeId);
        return biome == null ? 0xFFFFFF : guard(biome.getGrassColor(blockX, blockZ), biomeId, GRASS);
    }

    public static boolean isLeaves(int blockId) {
        return blockId > 0 && Block.stateById(blockId).is(BlockTags.LEAVES);
    }

    private static Biome biome(ClientLevel level, int biomeId) {
        if (biomeId < 0) {
            return null; // /aerogel switch colors off
        }
        return CACHE.computeIfAbsent(biomeId,
            id -> level.registryAccess().registryOrThrow(Registries.BIOME).byId(id));
    }

    private static int guard(int color, int biomeId, int blockId) {
        color &= 0xFFFFFF;
        if (color == 0 && WARNED_BLACK.add(biomeId * 31 + blockId)) {
            LOGGER.warn("biome id {} (block id {}) produced a black tint -> rendering the texture un-tinted",
                biomeId, blockId);
            return 0xFFFFFF;
        }
        return color;
    }

    public static void clear() {
        CACHE.clear();
        WARNED_BLACK.clear();
    }
}
