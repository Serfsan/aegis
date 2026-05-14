package com.greatsnowsea.worldgen;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.NoiseColumn;
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
import net.minecraft.world.level.levelgen.synth.SimplexNoise;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.StructureManager;

import java.util.concurrent.CompletableFuture;

public class NetherSeaChunkGenerator extends ChunkGenerator {

    public static final MapCodec<NetherSeaChunkGenerator> CODEC = RecordCodecBuilder.mapCodec(
            instance -> instance.group(
                    BiomeSource.CODEC.fieldOf("biome_source").forGetter(ChunkGenerator::getBiomeSource),
                    Codec.LONG.fieldOf("seed").orElse(0L).forGetter(g -> g.seed)
            ).apply(instance, instance.stable(NetherSeaChunkGenerator::new))
    );

    public static final int SEAFLOOR_THICKNESS = 10;
    public static final int WATER_SURFACE_Y = 64;

    public static final int MEGA_CELL   = SeaChunkGenerator.MEGA_CELL;
    public static final int MEGA_RADIUS = SeaChunkGenerator.MEGA_RADIUS;

    private static final double SMALL_HOLE_THRESHOLD = 0.95;

    private static final int GEN_DEPTH = 256;
    private static final int MIN_Y = 0;
    private static final int TOP_BEDROCK_Y = 127;
    private static final int CEIL_THICKNESS = 3;

    public final long seed;
    private final SimplexNoise floorThickness;
    private final SimplexNoise floorHole;
    private final SimplexNoise megaHoleShape;

    public NetherSeaChunkGenerator(BiomeSource biomeSource, long seed) {
        super(biomeSource);
        this.seed = seed;
        this.floorThickness = new SimplexNoise(RandomSource.create(seed + 900L));
        this.floorHole      = new SimplexNoise(RandomSource.create(seed + 950L));
        this.megaHoleShape  = new SimplexNoise(RandomSource.create(seed + 975L));
    }

    @Override
    protected MapCodec<? extends ChunkGenerator> codec() {
        return CODEC;
    }

    @Override
    public int getSeaLevel() {
        return WATER_SURFACE_Y;
    }

    @Override
    public CompletableFuture<ChunkAccess> fillFromNoise(
            Blender blender,
            RandomState random,
            StructureManager structureManager,
            ChunkAccess chunk) {

        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int chunkX = chunk.getPos().getMinBlockX();
        int chunkZ = chunk.getPos().getMinBlockZ();
        int minBuildHeight = chunk.getMinBuildHeight();

        for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
                int wx = chunkX + lx;
                int wz = chunkZ + lz;

                boolean isHole = isMegaHole(wx, wz);
                if (!isHole) {
                    double holeNoise = floorHole.getValue(wx / 30.0, wz / 30.0);
                    isHole = holeNoise > SMALL_HOLE_THRESHOLD;
                }

                pos.set(wx, minBuildHeight, wz);
                chunk.setBlockState(pos, Blocks.BEDROCK.defaultBlockState(), false);

                for (int y = TOP_BEDROCK_Y - CEIL_THICKNESS + 1; y <= TOP_BEDROCK_Y; y++) {
                    pos.set(wx, y, wz);
                    chunk.setBlockState(pos, Blocks.BEDROCK.defaultBlockState(), false);
                }

                if (isHole) continue;

                double thicknessNoise = floorThickness.getValue(wx / 60.0, wz / 60.0);
                int thickness = (int) (1 + (thicknessNoise + 1.0) * 0.5 * SEAFLOOR_THICKNESS);
                thickness = Math.max(1, Math.min(SEAFLOOR_THICKNESS, thickness));

                int floorTop = minBuildHeight + thickness;
                for (int y = minBuildHeight + 1; y <= floorTop; y++) {
                    pos.set(wx, y, wz);
                    chunk.setBlockState(pos, Blocks.DEEPSLATE.defaultBlockState(), false);
                }

                int ceilBottom = TOP_BEDROCK_Y - CEIL_THICKNESS - 3;
                for (int y = ceilBottom; y <= TOP_BEDROCK_Y - CEIL_THICKNESS; y++) {
                    pos.set(wx, y, wz);
                    chunk.setBlockState(pos, Blocks.DEEPSLATE.defaultBlockState(), false);
                }

                for (int y = floorTop + 1; y < ceilBottom; y++) {
                    pos.set(wx, y, wz);
                    if (chunk.getBlockState(pos).isAir()) {
                        chunk.setBlockState(pos, Blocks.WATER.defaultBlockState(), false);
                    }
                }
            }
        }

        return CompletableFuture.completedFuture(chunk);
    }

    // ── Mega-Holes (synchronized with overworld) ─────────────────────────

    private boolean isMegaHole(int wx, int wz) {
        int minCellX = Math.floorDiv(wx - MEGA_RADIUS, MEGA_CELL);
        int minCellZ = Math.floorDiv(wz - MEGA_RADIUS, MEGA_CELL);
        int maxCellX = Math.floorDiv(wx + MEGA_RADIUS, MEGA_CELL);
        int maxCellZ = Math.floorDiv(wz + MEGA_RADIUS, MEGA_CELL);

        for (int cx = minCellX; cx <= maxCellX; cx++) {
            for (int cz = minCellZ; cz <= maxCellZ; cz++) {
                RandomSource rng = RandomSource.create(seed ^ ((long) cx * 341873128712L + (long) cz * 132897987541L));
                if (rng.nextInt(256) != 0) continue;

                int centerX = cx * MEGA_CELL + rng.nextInt(MEGA_CELL);
                int centerZ = cz * MEGA_CELL + rng.nextInt(MEGA_CELL);

                double dx = wx - centerX;
                double dz = wz - centerZ;
                double warp = megaHoleShape.getValue(wx / 40.0, wz / 40.0) * 15.0;
                double distSq = (dx + warp) * (dx + warp) + (dz + warp * 0.7) * (dz + warp * 0.7);

                if (distSq < (double) MEGA_RADIUS * MEGA_RADIUS) return true;
            }
        }
        return false;
    }

    // ── Height Queries ───────────────────────────────────────────────────

    @Override
    public int getBaseHeight(int x, int z, Heightmap.Types heightmap,
                             LevelHeightAccessor level, RandomState random) {
        if (isMegaHole(x, z)) return MIN_Y;
        double holeNoise = floorHole.getValue(x / 30.0, z / 30.0);
        if (holeNoise > SMALL_HOLE_THRESHOLD) return MIN_Y;
        return WATER_SURFACE_Y;
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

    @Override
    public void applyCarvers(WorldGenRegion level, long seed, RandomState random,
                             BiomeManager biomeManager, StructureManager structureManager,
                             ChunkAccess chunk, GenerationStep.Carving step) {
    }

    @Override
    public void buildSurface(WorldGenRegion level, StructureManager structureManager,
                             RandomState random, ChunkAccess chunk) {
    }

    @Override
    public int getGenDepth() {
        return GEN_DEPTH;
    }

    @Override
    public int getMinY() {
        return MIN_Y;
    }

    @Override
    public void spawnOriginalMobs(WorldGenRegion level) {
    }

    @Override
    public void addDebugScreenInfo(java.util.List<String> info,
                                   RandomState random, BlockPos pos) {
    }
}
