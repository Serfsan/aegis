package com.greatsnowsea.worldgen;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.NoiseColumn;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.WorldGenRegion;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.levelgen.structure.StructureManager;
import net.minecraft.world.level.levelgen.synth.SimplexNoise;

import java.util.concurrent.CompletableFuture;

public class SeaChunkGenerator extends ChunkGenerator {

    public static final MapCodec<SeaChunkGenerator> CODEC = RecordCodecBuilder.mapCodec(
            instance -> instance.group(
                    BiomeSource.CODEC.fieldOf("biome_source").forGetter(ChunkGenerator::getBiomeSource),
                    Codec.LONG.fieldOf("seed").orElse(0L).forGetter(g -> g.seed)
            ).apply(instance, instance.stable(SeaChunkGenerator::new))
    );

    // ── World Layout ─────────────────────────────────────────────────────
    //  Void:        Y=-64 → Y=-1    (open void below water)
    //  Water:       Y=0   → Y=49    (50 blocks, no solid bottom)
    //  Islands:     Y≈50  → Y≈70    (emerging from water)
    //  Air gap:     Y≈70  → Y≈160   (~90 blocks for ships)
    //  Float layer 1: center Y=180  (top ~190, bottom ~155)
    //  Air gap:     Y≈190 → Y≈310   (~120 blocks for airships)
    //  Float layer 2: center Y=330  (top ~340, bottom ~305)
    //  High sky:    Y≈340 → Y=447

    public static final int WATER_SURFACE_Y = 49;
    public static final int FLOAT_1_CENTER  = 180;
    public static final int FLOAT_2_CENTER  = 330;

    private final long seed;
    private final SimplexNoise surfaceMacro;
    private final SimplexNoise surfaceMeso;
    private final SimplexNoise surfaceMicro;
    private final SimplexNoise surfaceHeight;
    private final SimplexNoise float1Macro;
    private final SimplexNoise float1Detail;
    private final SimplexNoise float2Macro;
    private final SimplexNoise float2Detail;

    public SeaChunkGenerator(BiomeSource biomeSource, long seed) {
        super(biomeSource);
        this.seed = seed;
        this.surfaceMacro  = new SimplexNoise(RandomSource.create(seed + 100L));
        this.surfaceMeso   = new SimplexNoise(RandomSource.create(seed + 200L));
        this.surfaceMicro  = new SimplexNoise(RandomSource.create(seed + 300L));
        this.surfaceHeight = new SimplexNoise(RandomSource.create(seed + 400L));
        this.float1Macro   = new SimplexNoise(RandomSource.create(seed + 500L));
        this.float1Detail  = new SimplexNoise(RandomSource.create(seed + 600L));
        this.float2Macro   = new SimplexNoise(RandomSource.create(seed + 700L));
        this.float2Detail  = new SimplexNoise(RandomSource.create(seed + 800L));
    }

    // ── Codec ────────────────────────────────────────────────────────────

    @Override
    protected MapCodec<? extends ChunkGenerator> codec() {
        return CODEC;
    }

    @Override
    public int getSeaLevel() {
        return WATER_SURFACE_Y;
    }

    // ── Terrain Generation ───────────────────────────────────────────────

    @Override
    public CompletableFuture<ChunkAccess> fillFromNoise(
            Blender blender,
            RandomState random,
            StructureManager structureManager,
            ChunkAccess chunk) {

        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int chunkX = chunk.getPos().getMinBlockX();
        int chunkZ = chunk.getPos().getMinBlockZ();

        for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
                int wx = chunkX + lx;
                int wz = chunkZ + lz;

                // 1) Surface islands rising from the water
                placeSurfaceIsland(chunk, pos, wx, wz);

                // 2) Floating island layer 1
                placeFloatingIsland(chunk, pos, wx, wz,
                        FLOAT_1_CENTER, float1Macro, float1Detail, 140.0);

                // 3) Floating island layer 2
                placeFloatingIsland(chunk, pos, wx, wz,
                        FLOAT_2_CENTER, float2Macro, float2Detail, 100.0);

                // 4) Water fill — only where no solid block was placed
                for (int y = 0; y <= WATER_SURFACE_Y; y++) {
                    pos.set(wx, y, wz);
                    if (chunk.getBlockState(pos).isAir()) {
                        chunk.setBlockState(pos, Blocks.WATER.defaultBlockState(), false);
                    }
                }
            }
        }

        return CompletableFuture.completedFuture(chunk);
    }

    // ── Surface Islands ──────────────────────────────────────────────────

    private void placeSurfaceIsland(ChunkAccess chunk, BlockPos.MutableBlockPos pos,
                                    int wx, int wz) {
        // Three octaves of 2D noise control island placement & shape
        double macro = surfaceMacro.getValue(wx / 200.0, wz / 200.0);
        double meso  = surfaceMeso.getValue(wx / 80.0, wz / 80.0);
        double micro = surfaceMicro.getValue(wx / 30.0, wz / 30.0);

        double density = macro * 0.6 + meso * 0.3 + micro * 0.1;

        // Threshold controls how much of the surface is land (~15-20%)
        double threshold = 0.35;
        if (density <= threshold) return;

        double strength = (density - threshold) / (1.0 - threshold);
        double hVar = surfaceHeight.getValue(wx / 60.0, wz / 60.0);

        // Island top: just above water to ~20 blocks above
        int topY = (int) (WATER_SURFACE_Y + 2 + strength * 18 + hVar * 5);

        // Island bottom: extends below water into the void (stalactite-like)
        int bottomY = (int) (WATER_SURFACE_Y - 5 - strength * 30);
        bottomY = Math.max(chunk.getMinBuildHeight(), bottomY);

        for (int y = bottomY; y <= topY; y++) {
            pos.set(wx, y, wz);
            BlockState state = pickSurfaceBlock(y, topY, bottomY);
            chunk.setBlockState(pos, state, false);
        }
    }

    private BlockState pickSurfaceBlock(int y, int topY, int bottomY) {
        if (y == topY) {
            // Sandy beaches near water level, grass higher up
            return (topY <= WATER_SURFACE_Y + 3)
                    ? Blocks.SAND.defaultBlockState()
                    : Blocks.GRASS_BLOCK.defaultBlockState();
        } else if (y > topY - 4) {
            return Blocks.DIRT.defaultBlockState();
        } else if (y > bottomY + 8) {
            return Blocks.STONE.defaultBlockState();
        } else {
            return Blocks.DEEPSLATE.defaultBlockState();
        }
    }

    // ── Floating Islands ─────────────────────────────────────────────────

    private void placeFloatingIsland(ChunkAccess chunk, BlockPos.MutableBlockPos pos,
                                     int wx, int wz, int centerY,
                                     SimplexNoise macroNoise, SimplexNoise detailNoise,
                                     double scale) {
        double macro  = macroNoise.getValue(wx / scale, wz / scale);
        double detail = detailNoise.getValue(wx / (scale * 0.4), wz / (scale * 0.4));

        double density = macro * 0.7 + detail * 0.3;

        // Floating islands are sparser than surface islands
        double threshold = 0.4;
        if (density <= threshold) return;

        double strength = (density - threshold) / (1.0 - threshold);

        // Top: gentle dome. Bottom: long stalactite taper.
        int topHalf    = (int) (3 + strength * 8);
        int bottomHalf = (int) (6 + strength * 18);

        int topY    = centerY + topHalf;
        int bottomY = centerY - bottomHalf;

        for (int y = bottomY; y <= topY; y++) {
            double distNorm;
            double taperFactor;

            if (y >= centerY) {
                // Top half — gentle parabolic dome
                distNorm = (double) (y - centerY) / topHalf;
                taperFactor = 1.0 - distNorm * distNorm;
            } else {
                // Bottom half — fast stalactite taper
                distNorm = (double) (centerY - y) / bottomHalf;
                taperFactor = Math.pow(1.0 - distNorm, 0.5);
            }

            // Skip if too thin (creates natural edge irregularity)
            double effectiveStrength = strength * taperFactor;
            if (effectiveStrength < 0.05) continue;

            pos.set(wx, y, wz);
            BlockState state = pickFloatingBlock(y, centerY, distNorm);
            chunk.setBlockState(pos, state, false);
        }
    }

    private BlockState pickFloatingBlock(int y, int centerY, double distNorm) {
        if (y == centerY + (int) (3 + 0.5 * 8)) {
            // Very top — grass
            return Blocks.GRASS_BLOCK.defaultBlockState();
        } else if (y > centerY) {
            return Blocks.DIRT.defaultBlockState();
        } else if (y > centerY - 4) {
            return Blocks.STONE.defaultBlockState();
        } else {
            return (distNorm > 0.7)
                    ? Blocks.STONE.defaultBlockState()
                    : Blocks.DEEPSLATE.defaultBlockState();
        }
    }

    // ── Height Queries ───────────────────────────────────────────────────

    @Override
    public int getBaseHeight(int x, int z, Heightmap.Types heightmap,
                             LevelHeightAccessor level, RandomState random) {
        // Surface islands
        double sm = surfaceMacro.getValue(x / 200.0, z / 200.0);
        double sn = surfaceMeso.getValue(x / 80.0, z / 80.0);
        double sc = surfaceMicro.getValue(x / 30.0, z / 30.0);
        double sd = sm * 0.6 + sn * 0.3 + sc * 0.1;

        if (sd > 0.35) {
            double ss = (sd - 0.35) / 0.65;
            double hv = surfaceHeight.getValue(x / 60.0, z / 60.0);
            return (int) (WATER_SURFACE_Y + 2 + ss * 18 + hv * 5);
        }

        // Floating islands (check top-down)
        int result = checkFloatHeight(x, z, FLOAT_2_CENTER, float2Macro, float2Detail, 100.0);
        if (result > 0) return result;

        result = checkFloatHeight(x, z, FLOAT_1_CENTER, float1Macro, float1Detail, 140.0);
        if (result > 0) return result;

        return WATER_SURFACE_Y;
    }

    private int checkFloatHeight(int x, int z, int centerY,
                                 SimplexNoise macroN, SimplexNoise detailN, double scale) {
        double m = macroN.getValue(x / scale, z / scale);
        double d = detailN.getValue(x / (scale * 0.4), z / (scale * 0.4));
        double fd = m * 0.7 + d * 0.3;

        if (fd > 0.4) {
            double fs = (fd - 0.4) / 0.6;
            int topHalf = (int) (3 + fs * 8);
            return centerY + topHalf;
        }
        return -1;
    }

    @Override
    public NoiseColumn getBaseColumn(int x, int z,
                                     LevelHeightAccessor level, RandomState random) {
        int h = getBaseHeight(x, z, Heightmap.Types.WORLD_SURFACE_WG, level, random);
        int min = level.getMinBuildHeight();
        BlockState[] states = new BlockState[Math.max(1, h - min)];
        for (int i = 0; i < states.length; i++) {
            states[i] = Blocks.STONE.defaultBlockState();
        }
        return new NoiseColumn(min, states);
    }

    // ── Carving & Surface ────────────────────────────────────────────────

    @Override
    public void applyCarvers(WorldGenRegion level, long seed, RandomState random,
                             BiomeManager biomeManager, StructureManager structureManager,
                             ChunkAccess chunk, GenerationStep.Carving step) {
        // No caves — solid islands with void between them
    }

    @Override
    public void buildSurface(WorldGenLevel level, StructureManager structureManager,
                             RandomState random, ChunkAccess chunk) {
        // Surface already handled directly in fillFromNoise
    }

    @Override
    public void spawnOriginalMobs(WorldGenRegion level) {
        // No special initial spawning
    }
}