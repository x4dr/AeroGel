package dev.aerogel.proxy;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.core.Registry;
import net.minecraft.tags.BiomeTags;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * One biome probe per chunk: maps the climate/biome at the chunk centre to a representative surface block
 * (whose top texture the client draws) and the biome's registry id (the client computes the tint, because
 * the grass/foliage/water colormaps are only loaded on the client — {@code Biome.getGrassColor} returns
 * black on a dedicated server).
 *
 * <p>The biome -> block choice is cached (biomes are few and stable across a session). Thread-confined:
 * the {@link Climate.Sampler} is stateful, so each sampler worker owns its own instance and cache. A
 * static diagnostic map records what was seen for {@code /aerogel biomes}.</p>
 */
public final class BiomeTint {
    private static final Map<Biome, String> DIAG = new ConcurrentHashMap<>();

    private final BiomeSource biomeSource;
    private final Registry<Biome> biomeRegistry;
    private final Climate.Sampler sampler;
    private final int seaLevel;
    private final Map<Biome, BlockState> blockCache = new IdentityHashMap<>();

    public BiomeTint(BiomeSource biomeSource, Registry<Biome> biomeRegistry, Climate.Sampler sampler,
            int seaLevel) {
        this.biomeSource = biomeSource;
        this.biomeRegistry = biomeRegistry;
        this.sampler = sampler;
        this.seaLevel = seaLevel;
    }

    /** the biome registry id and the representative block-state id; the client turns these into a texture + tint. */
    public record Probe(int biomeId, int blockId) {
    }

    /** probe the surface biome at the chunk centre; {@code surfaceY} is the sampled centre height. */
    public Probe probe(int chunkX, int chunkZ, int surfaceY) {
        int bx = (chunkX << 4) + 8;
        int bz = (chunkZ << 4) + 8;
        Holder<Biome> holder = biomeSource.getNoiseBiome(
            QuartPos.fromBlock(bx), QuartPos.fromBlock(surfaceY), QuartPos.fromBlock(bz), sampler);
        Biome biome = holder.value();
        boolean water = surfaceY <= seaLevel;
        BlockState state = water
            ? Blocks.WATER.defaultBlockState()
            : blockCache.computeIfAbsent(biome, b -> blockFor(holder, b));
        int biomeId = biomeRegistry.getId(biome);
        DIAG.put(biome, describe(holder, state, biome.getBaseTemperature()));
        return new Probe(biomeId, Block.getId(state));
    }

    private static String describe(Holder<Biome> holder, BlockState state, float temp) {
        String key = holder.unwrapKey().map(k -> k.location().toString()).orElse("?");
        return key + " -> block=" + state.getBlock() + " temp=" + String.format("%.2f", temp);
    }

    /** diagnostic dump of every biome sampled so far (for {@code /aerogel biomes}). */
    public static String dump() {
        if (DIAG.isEmpty()) {
            return "AeroGel: no biomes sampled yet";
        }
        StringBuilder sb = new StringBuilder("AeroGel biome cache (" + DIAG.size() + "):");
        DIAG.entrySet().stream()
            .sorted(Map.Entry.comparingByValue())
            .forEach(e -> sb.append('\n').append(e.getValue()));
        return sb.toString();
    }

    /**
     * Representative surface block for a biome (its top texture is what the client draws, tinted by the
     * biome's grass colour for grass). Ordered most-specific first; unknown/modded biomes fall back to
     * grass. Vanilla tag membership: snowy (temp &lt; 0.15) → snow; badlands → red sand; beach/desert →
     * sand; jungle/forest/taiga → the matching leaves; windswept hills and mountain peaks → stone; ocean/
     * river → water; everything else → grass.
     */
    private static BlockState blockFor(Holder<Biome> holder, Biome biome) {
        float temp = biome.getBaseTemperature();
        if (temp < 0.15f) {
            return Blocks.SNOW_BLOCK.defaultBlockState();
        }
        if (holder.is(BiomeTags.IS_BADLANDS)) {
            return Blocks.RED_SAND.defaultBlockState();
        }
        if (holder.is(BiomeTags.IS_BEACH)) {
            return Blocks.SAND.defaultBlockState();
        }
        if (temp >= 1.9f) { // desert (2.0); savanna is 1.2 and stays grass
            return Blocks.SAND.defaultBlockState();
        }
        if (holder.is(BiomeTags.IS_JUNGLE)) {
            return Blocks.JUNGLE_LEAVES.defaultBlockState();
        }
        if (holder.is(BiomeTags.IS_FOREST)) {
            return Blocks.OAK_LEAVES.defaultBlockState();
        }
        if (holder.is(BiomeTags.IS_TAIGA)) {
            return Blocks.SPRUCE_LEAVES.defaultBlockState();
        }
        if (holder.is(BiomeTags.IS_HILL) || holder.is(BiomeTags.IS_MOUNTAIN)) {
            return Blocks.STONE.defaultBlockState();
        }
        // NOTE: never WATER here. The representative block is the *land* material; the client draws water
        // per cell from the height (cells at/below sea level), so an ocean chunk's above-sea islands/mountains
        // do not render as water. Oceans use sand as their land fallback.
        if (holder.is(BiomeTags.IS_OCEAN) || holder.is(BiomeTags.IS_RIVER)) {
            return Blocks.SAND.defaultBlockState();
        }
        return Blocks.GRASS_BLOCK.defaultBlockState();
    }
}
