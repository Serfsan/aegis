package com.greatsnowsea.worldgen;

import com.greatsnowsea.block.ModBlocks;
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

public class SeaChunkGenerator extends ChunkGenerator {

    public static final MapCodec<SeaChunkGenerator> CODEC = RecordCodecBuilder.mapCodec(
            instance -> instance.group(
                    BiomeSource.CODEC.fieldOf("biome_source").forGetter(ChunkGenerator::getBiomeSource),
                    Codec.LONG.fieldOf("seed").orElse(0L).forGetter(g -> g.seed)
            ).apply(instance, instance.stable(SeaChunkGenerator::new))
    );

    public static final int SEAFLOOR_THICKNESS = 10;
    public static final int WATER_SURFACE_Y = 49;
    public static final int FLOAT_1_CENTER  = 180;
    public static final int FLOAT_2_CENTER  = 330;
    public static final int MAX_HEIGHT = 447;

    public static final int MEGA_CELL   = 256;
    public static final int MEGA_RADIUS = 75;

    private static final int ROOT_CELL = 200;
    private static final int FEEDER_MAX_LENGTH = 160;
    private static final int FEEDER_START_OFFSET = 55;
    private static final double SMALL_HOLE_THRESHOLD = 0.95;
    private static final double FLESH_CORE_RADIUS = 5.0;
    private static final double Y_WOBBLE_AMPLITUDE = 5.0;
    private static final int WOBBLE_SAMPLE_INTERVAL = 8;

    public final long seed;
    private final SimplexNoise surfaceMacro;
    private final SimplexNoise surfaceMeso;
    private final SimplexNoise surfaceMicro;
    private final SimplexNoise surfaceHeight;
    private final SimplexNoise float1Macro;
    private final SimplexNoise float1Detail;
    private final SimplexNoise float2Macro;
    private final SimplexNoise float2Detail;
    private final SimplexNoise floorThickness;
    private final SimplexNoise floorHole;
    private final SimplexNoise megaHoleShape;
    private final SimplexNoise rootWobble;
    private final SimplexNoise islandShape;

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
        this.floorThickness = new SimplexNoise(RandomSource.create(seed + 900L));
        this.floorHole      = new SimplexNoise(RandomSource.create(seed + 950L));
        this.megaHoleShape  = new SimplexNoise(RandomSource.create(seed + 975L));
        this.rootWobble     = new SimplexNoise(RandomSource.create(seed + 985L));
        this.islandShape    = new SimplexNoise(RandomSource.create(seed + 990L));
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
                placeSeafloor(chunk, pos, chunkX + lx, chunkZ + lz);
            }
        }

        placeRootsForChunk(chunk, pos, chunkX, chunkZ, minBuildHeight);

        for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
                int wx = chunkX + lx;
                int wz = chunkZ + lz;
                placeSurfaceIsland(chunk, pos, wx, wz);
                placeFloatingIsland(chunk, pos, wx, wz,
                        FLOAT_1_CENTER, float1Macro, float1Detail, 140.0);
                placeFloatingIsland(chunk, pos, wx, wz,
                        FLOAT_2_CENTER, float2Macro, float2Detail, 100.0);
            }
        }

        for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
                placeRootIsland(chunk, pos, chunkX + lx, chunkZ + lz);
            }
        }

        for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
                int wx = chunkX + lx;
                int wz = chunkZ + lz;
                for (int y = minBuildHeight; y <= WATER_SURFACE_Y; y++) {
                    pos.set(wx, y, wz);
                    if (chunk.getBlockState(pos).isAir()) {
                        chunk.setBlockState(pos, Blocks.WATER.defaultBlockState(), false);
                    }
                }
            }
        }

        return CompletableFuture.completedFuture(chunk);
    }

    // ── Seafloor ─────────────────────────────────────────────────────────

    private void placeSeafloor(ChunkAccess chunk, BlockPos.MutableBlockPos pos,
                               int wx, int wz) {
        int minBuildHeight = chunk.getMinBuildHeight();

        if (isMegaHole(wx, wz)) return;

        double holeNoise = floorHole.getValue(wx / 30.0, wz / 30.0);
        if (holeNoise > SMALL_HOLE_THRESHOLD) return;

        pos.set(wx, minBuildHeight, wz);
        chunk.setBlockState(pos, Blocks.BEDROCK.defaultBlockState(), false);

        double thicknessNoise = floorThickness.getValue(wx / 60.0, wz / 60.0);
        int thickness = (int) (1 + (thicknessNoise + 1.0) * 0.5 * SEAFLOOR_THICKNESS);
        thickness = Math.max(1, Math.min(SEAFLOOR_THICKNESS, thickness));

        int floorTop = minBuildHeight + thickness;
        for (int y = minBuildHeight + 1; y <= floorTop; y++) {
            pos.set(wx, y, wz);
            chunk.setBlockState(pos, Blocks.STONE.defaultBlockState(), false);
        }
    }

    // ── Giant Roots ──────────────────────────────────────────────────────

    private void placeRootsForChunk(ChunkAccess chunk, BlockPos.MutableBlockPos pos,
                                    int chunkX, int chunkZ, int minBuildHeight) {
        int cellMinX = Math.floorDiv(chunkX, ROOT_CELL) - 1;
        int cellMinZ = Math.floorDiv(chunkZ, ROOT_CELL) - 1;
        int cellMaxX = Math.floorDiv(chunkX + 15, ROOT_CELL) + 1;
        int cellMaxZ = Math.floorDiv(chunkZ + 15, ROOT_CELL) + 1;

        int feederCellRange = 1 + FEEDER_MAX_LENGTH / ROOT_CELL + 1;

        for (int cx = cellMinX - feederCellRange; cx <= cellMaxX + feederCellRange; cx++) {
            for (int cz = cellMinZ - feederCellRange; cz <= cellMaxZ + feederCellRange; cz++) {
                RandomSource rng = RandomSource.create(
                        seed ^ ((long) cx * 754819321L + (long) cz * 4294967291L));
                if (rng.nextInt(6) != 0) continue;

                int centerX = cx * ROOT_CELL + rng.nextInt(ROOT_CELL);
                int centerZ = cz * ROOT_CELL + rng.nextInt(ROOT_CELL);
                double baseRadius = 11.0 + rng.nextDouble() * 4.0;

                double wobbleX = rootWobble.getValue(centerX / 60.0, centerZ / 60.0) * 12.0;
                double wobbleZ = rootWobble.getValue(centerX / 60.0 + 100.0, centerZ / 60.0 + 100.0) * 12.0;

                double trunkX = centerX + wobbleX;
                double trunkZ = centerZ + wobbleZ;

                int floorY = getSeafloorTop(centerX, centerZ, minBuildHeight);

                // Pre-compute y-wobble for this trunk (sampled every 8 blocks, interpolated)
                int topY = MAX_HEIGHT;
                int totalHeight = topY - floorY;
                int heightRange = totalHeight + 1;
                double[] wobbleXArr = buildInterpolatedWobble(heightRange, floorY, trunkX, true);
                double[] wobbleZArr = buildInterpolatedWobble(heightRange, floorY, trunkZ, false);

                // AABB for trunk
                double maxFlare = baseRadius * 2.0;
                double trunkMaxR = maxFlare + Y_WOBBLE_AMPLITUDE;
                double trunkLoX = trunkX - trunkMaxR;
                double trunkHiX = trunkX + trunkMaxR;
                double trunkLoZ = trunkZ - trunkMaxR;
                double trunkHiZ = trunkZ + trunkMaxR;

                int loX = Math.max(chunkX, (int) Math.floor(trunkLoX));
                int hiX = Math.min(chunkX + 15, (int) Math.ceil(trunkHiX));
                int loZ = Math.max(chunkZ, (int) Math.floor(trunkLoZ));
                int hiZ = Math.min(chunkZ + 15, (int) Math.ceil(trunkHiZ));

                for (int lx = loX; lx <= hiX; lx++) {
                    for (int lz = loZ; lz <= hiZ; lz++) {
                        placeTrunkCached(chunk, pos, lx, lz, trunkX, trunkZ,
                                baseRadius, floorY, totalHeight, wobbleXArr, wobbleZArr);
                    }
                }

                int feederCount = 1 + rng.nextInt(5);
                for (int r = 0; r < feederCount; r++) {
                    double angle = rng.nextDouble() * Math.PI * 2.0;
                    double length = 80.0 + rng.nextDouble() * (FEEDER_MAX_LENGTH - 80.0);
                    double rootRadius = 5.0 + rng.nextDouble() * 5.0;

                    placeFeederRootChunk(chunk, pos, chunkX, chunkZ,
                            trunkX, trunkZ, floorY,
                            angle, length, rootRadius, r);
                }
            }
        }
    }

    private double[] buildInterpolatedWobble(int heightRange, int floorY, double coord, boolean xAxis) {
        int numSamples = (heightRange + WOBBLE_SAMPLE_INTERVAL - 1) / WOBBLE_SAMPLE_INTERVAL + 1;
        double[] samples = new double[numSamples];
        for (int s = 0; s < numSamples; s++) {
            int y = floorY + s * WOBBLE_SAMPLE_INTERVAL;
            double input2 = xAxis ? coord / 40.0 : coord / 40.0 + 50.0;
            samples[s] = rootWobble.getValue(y / 30.0, input2) * Y_WOBBLE_AMPLITUDE;
        }

        double[] result = new double[heightRange];
        for (int i = 0; i < heightRange; i++) {
            int s = i / WOBBLE_SAMPLE_INTERVAL;
            int nextS = Math.min(s + 1, numSamples - 1);
            double frac = (double) (i % WOBBLE_SAMPLE_INTERVAL) / WOBBLE_SAMPLE_INTERVAL;
            result[i] = samples[s] + (samples[nextS] - samples[s]) * frac;
        }
        return result;
    }

    private void placeTrunkCached(ChunkAccess chunk, BlockPos.MutableBlockPos pos,
                                  int wx, int wz,
                                  double trunkX, double trunkZ, double baseRadius,
                                  int floorY, int totalHeight,
                                  double[] wobbleXArr, double[] wobbleZArr) {
        for (int i = 0; i < wobbleXArr.length; i++) {
            int y = floorY + i;
            double heightFrac = (double) i / totalHeight;

            double flareRadius = baseRadius;
            if (heightFrac < 0.15) {
                double flareFrac = heightFrac / 0.15;
                flareRadius = baseRadius * (2.0 - flareFrac);
            } else {
                flareRadius = baseRadius * (1.0 - (heightFrac - 0.15) * 0.35);
            }
            flareRadius = Math.max(baseRadius * 0.5, flareRadius);

            double ddx = wx - (trunkX + wobbleXArr[i]);
            double ddz = wz - (trunkZ + wobbleZArr[i]);
            double distSq = ddx * ddx + ddz * ddz;

            if (distSq < flareRadius * flareRadius) {
                pos.set(wx, y, wz);
                chunk.setBlockState(pos, pickRootBlock(Math.sqrt(distSq), flareRadius), false);
            }
        }
    }

    private void placeFeederRootChunk(ChunkAccess chunk, BlockPos.MutableBlockPos pos,
                                      int chunkX, int chunkZ,
                                      double trunkX, double trunkZ, int floorY,
                                      double angle, double length, double rootRadius,
                                      int rootIndex) {
        int startY = floorY + FEEDER_START_OFFSET;

        double cosA = Math.cos(angle);
        double sinA = Math.sin(angle);
        double cosP = Math.cos(angle + Math.PI * 0.5);
        double sinP = Math.sin(angle + Math.PI * 0.5);

        double noiseOff = rootIndex * 137.5;

        double endX = trunkX + cosA * length;
        double endZ = trunkZ + sinA * length;
        double margin = Y_WOBBLE_AMPLITUDE + rootRadius + 10.0;
        double feederLoX = Math.min(trunkX, endX) - margin;
        double feederHiX = Math.max(trunkX, endX) + margin;
        double feederLoZ = Math.min(trunkZ, endZ) - margin;
        double feederHiZ = Math.max(trunkZ, endZ) + margin;

        int loX = Math.max(chunkX, (int) Math.floor(feederLoX));
        int hiX = Math.min(chunkX + 15, (int) Math.ceil(feederHiX));
        int loZ = Math.max(chunkZ, (int) Math.floor(feederLoZ));
        int hiZ = Math.min(chunkZ + 15, (int) Math.ceil(feederHiZ));

        if (loX > hiX || loZ > hiZ) return;

        double stepSize = 0.5;
        int steps = (int) Math.ceil(length / stepSize);

        for (int lx = loX; lx <= hiX; lx++) {
            for (int lz = loZ; lz <= hiZ; lz++) {
                placeFeederRootColumn(chunk, pos, lx, lz,
                        trunkX, trunkZ, floorY, startY,
                        cosA, sinA, cosP, sinP, noiseOff,
                        length, rootRadius, rootIndex, stepSize, steps);
            }
        }
    }

    private void placeFeederRootColumn(ChunkAccess chunk, BlockPos.MutableBlockPos pos,
                                       int wx, int wz,
                                       double trunkX, double trunkZ, int floorY, int startY,
                                       double cosA, double sinA, double cosP, double sinP,
                                       double noiseOff,
                                       double length, double rootRadius, int rootIndex,
                                       double stepSize, int steps) {
        for (int i = 0; i <= steps; i++) {
            double dist = i * stepSize;
            double t = dist / length;

            double wobbleOff = rootWobble.getValue(
                    dist * 0.025 + noiseOff, rootIndex * 7.0) * 10.0;

            double px = trunkX + cosA * dist + cosP * wobbleOff;
            double pz = trunkZ + sinA * dist + sinP * wobbleOff;

            double py = startY + (floorY - startY) * t * t;

            double ddx = wx - px;
            double ddz = wz - pz;
            double horizDistSq = ddx * ddx + ddz * ddz;

            double taper = 1.0 - t * 0.5;
            double r = rootRadius * taper;

            if (horizDistSq >= r * r) continue;

            int yCenter = (int) Math.round(py);
            int halfH = (int) Math.ceil(r);
            for (int dy = -halfH; dy <= halfH; dy++) {
                int y = yCenter + dy;
                if (y < floorY || y > startY + halfH) continue;
                double yFrac = (double) dy / (halfH + 1);
                double xzLimit = r * (1.0 - yFrac * yFrac);
                if (horizDistSq < xzLimit * xzLimit) {
                    pos.set(wx, y, wz);
                    chunk.setBlockState(pos, pickRootBlock(Math.sqrt(horizDistSq), xzLimit), false);
                }
            }
        }
    }

    private int getSeafloorTop(int wx, int wz, int minBuildHeight) {
        if (isMegaHole(wx, wz)) return minBuildHeight;
        double holeNoise = floorHole.getValue(wx / 30.0, wz / 30.0);
        if (holeNoise > SMALL_HOLE_THRESHOLD) return minBuildHeight;
        double thicknessNoise = floorThickness.getValue(wx / 60.0, wz / 60.0);
        int thickness = (int) (1 + (thicknessNoise + 1.0) * 0.5 * SEAFLOOR_THICKNESS);
        return minBuildHeight + Math.max(1, Math.min(SEAFLOOR_THICKNESS, thickness));
    }

    private BlockState pickRootBlock(double dist, double radius) {
        double fleshRadius = Math.min(FLESH_CORE_RADIUS, radius * 0.35);
        if (dist < fleshRadius) {
            return ModBlocks.YIRD_FLESH.get().defaultBlockState();
        }
        if (dist > radius * 0.7) {
            return ModBlocks.YIRD_WOOD.get().defaultBlockState();
        }
        return ModBlocks.STRIPPED_YIRD_WOOD.get().defaultBlockState();
    }

    // ── Root Island ──────────────────────────────────────────────────────

    private void placeRootIsland(ChunkAccess chunk, BlockPos.MutableBlockPos pos,
                                 int wx, int wz) {
        int cellX = Math.floorDiv(wx, ROOT_CELL);
        int cellZ = Math.floorDiv(wz, ROOT_CELL);

        for (int cx = cellX - 1; cx <= cellX + 1; cx++) {
            for (int cz = cellZ - 1; cz <= cellZ + 1; cz++) {
                RandomSource rng = RandomSource.create(
                        seed ^ ((long) cx * 754819321L + (long) cz * 4294967291L));
                if (rng.nextInt(6) != 0) continue;

                int centerX = cx * ROOT_CELL + rng.nextInt(ROOT_CELL);
                int centerZ = cz * ROOT_CELL + rng.nextInt(ROOT_CELL);
                double baseRadius = 11.0 + rng.nextDouble() * 4.0;
                double islandBase = 25.0 + rng.nextDouble() * 15.0;

                double wobbleX = rootWobble.getValue(centerX / 60.0, centerZ / 60.0) * 12.0;
                double wobbleZ = rootWobble.getValue(centerX / 60.0 + 100.0, centerZ / 60.0 + 100.0) * 12.0;

                double dx = wx - (centerX + wobbleX);
                double dz = wz - (centerZ + wobbleZ);
                double horizDist = Math.sqrt(dx * dx + dz * dz);

                double coastNoise = islandShape.getValue(
                        (wx + centerX) / 40.0, (wz + centerZ) / 40.0) * 8.0;
                double coastNoise2 = islandShape.getValue(
                        (wx - centerX) / 20.0 + 50.0, (wz - centerZ) / 20.0 + 50.0) * 4.0;

                double effectiveOuter = islandBase + coastNoise + coastNoise2;

                if (horizDist > effectiveOuter) continue;

                int ringBottom = WATER_SURFACE_Y - 12;
                int ringTop = WATER_SURFACE_Y + 5;

                for (int y = ringBottom; y <= ringTop; y++) {
                    double yDist = (y - WATER_SURFACE_Y);
                    double yTaper;
                    if (yDist >= 0) {
                        yTaper = 1.0 - (yDist / (ringTop - WATER_SURFACE_Y)) * 0.6;
                    } else {
                        yTaper = 1.0 + (yDist / (WATER_SURFACE_Y - ringBottom)) * 0.5;
                    }
                    double ringRadius = effectiveOuter * yTaper;

                    if (horizDist < ringRadius) {
                        pos.set(wx, y, wz);
                        BlockState existing = chunk.getBlockState(pos);
                        if (!existing.isAir() && existing.getBlock() != Blocks.WATER) continue;

                        boolean nearRoot = horizDist < baseRadius + 3;
                        boolean nearEdge = horizDist > effectiveOuter - 5;
                        chunk.setBlockState(pos, pickIslandBlock(y, nearRoot, nearEdge), false);
                    }
                }
            }
        }
    }

    private BlockState pickIslandBlock(int y, boolean nearRoot, boolean nearEdge) {
        if (y >= WATER_SURFACE_Y + 2) {
            return nearRoot ? Blocks.GRASS_BLOCK.defaultBlockState()
                    : nearEdge ? Blocks.SAND.defaultBlockState()
                    : Blocks.GRASS_BLOCK.defaultBlockState();
        } else if (y >= WATER_SURFACE_Y) {
            return nearRoot ? Blocks.GRASS_BLOCK.defaultBlockState()
                    : Blocks.SAND.defaultBlockState();
        } else if (y >= WATER_SURFACE_Y - 2) {
            return Blocks.DIRT.defaultBlockState();
        } else if (y >= WATER_SURFACE_Y - 5) {
            return Blocks.STONE.defaultBlockState();
        } else {
            return Blocks.DEEPSLATE.defaultBlockState();
        }
    }

    // ── Mega-Holes ───────────────────────────────────────────────────────

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

    public static BlockPos findNearestMegaHole(long generatorSeed, int fromX, int fromZ, int searchRadius) {
        int playerCellX = Math.floorDiv(fromX, MEGA_CELL);
        int playerCellZ = Math.floorDiv(fromZ, MEGA_CELL);
        int cellRange = searchRadius / MEGA_CELL + 2;

        BlockPos nearest = null;
        double nearestDistSq = Double.MAX_VALUE;

        for (int cx = playerCellX - cellRange; cx <= playerCellX + cellRange; cx++) {
            for (int cz = playerCellZ - cellRange; cz <= playerCellZ + cellRange; cz++) {
                RandomSource rng = RandomSource.create(generatorSeed ^ ((long) cx * 341873128712L + (long) cz * 132897987541L));
                if (rng.nextInt(256) != 0) continue;

                int centerX = cx * MEGA_CELL + rng.nextInt(MEGA_CELL);
                int centerZ = cz * MEGA_CELL + rng.nextInt(MEGA_CELL);

                double dx = fromX - centerX;
                double dz = fromZ - centerZ;
                double distSq = dx * dx + dz * dz;

                if (distSq < nearestDistSq && distSq < (long) searchRadius * searchRadius) {
                    nearestDistSq = distSq;
                    nearest = new BlockPos(centerX, WATER_SURFACE_Y, centerZ);
                }
            }
        }
        return nearest;
    }

    // ── Surface Islands ──────────────────────────────────────────────────

    private void placeSurfaceIsland(ChunkAccess chunk, BlockPos.MutableBlockPos pos,
                                    int wx, int wz) {
        double macro = surfaceMacro.getValue(wx / 200.0, wz / 200.0);
        double meso  = surfaceMeso.getValue(wx / 80.0, wz / 80.0);
        double micro = surfaceMicro.getValue(wx / 30.0, wz / 30.0);

        double density = macro * 0.6 + meso * 0.3 + micro * 0.1;

        double threshold = 0.35;
        if (density <= threshold) return;

        double strength = (density - threshold) / (1.0 - threshold);
        double hVar = surfaceHeight.getValue(wx / 60.0, wz / 60.0);

        int topY = (int) (WATER_SURFACE_Y + 2 + strength * 18 + hVar * 5);

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

        double threshold = 0.4;
        if (density <= threshold) return;

        double strength = (density - threshold) / (1.0 - threshold);

        int topHalf    = (int) (3 + strength * 8);
        int bottomHalf = (int) (6 + strength * 18);

        int topY    = centerY + topHalf;
        int bottomY = centerY - bottomHalf;

        for (int y = bottomY; y <= topY; y++) {
            double distNorm;
            double taperFactor;

            if (y >= centerY) {
                distNorm = (double) (y - centerY) / topHalf;
                taperFactor = 1.0 - distNorm * distNorm;
            } else {
                distNorm = (double) (centerY - y) / bottomHalf;
                taperFactor = Math.pow(1.0 - distNorm, 0.5);
            }

            double effectiveStrength = strength * taperFactor;
            if (effectiveStrength < 0.05) continue;

            pos.set(wx, y, wz);
            BlockState state = pickFloatingBlock(y, centerY, distNorm);
            chunk.setBlockState(pos, state, false);
        }
    }

    private BlockState pickFloatingBlock(int y, int centerY, double distNorm) {
        if (y == centerY + (int) (3 + 0.5 * 8)) {
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
        double sm = surfaceMacro.getValue(x / 200.0, z / 200.0);
        double sn = surfaceMeso.getValue(x / 80.0, z / 80.0);
        double sc = surfaceMicro.getValue(x / 30.0, z / 30.0);
        double sd = sm * 0.6 + sn * 0.3 + sc * 0.1;

        if (sd > 0.35) {
            double ss = (sd - 0.35) / 0.65;
            double hv = surfaceHeight.getValue(x / 60.0, z / 60.0);
            return (int) (WATER_SURFACE_Y + 2 + ss * 18 + hv * 5);
        }

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
        return 384;
    }

    @Override
    public int getMinY() {
        return -64;
    }

    @Override
    public void spawnOriginalMobs(WorldGenRegion level) {
    }

    @Override
    public void addDebugScreenInfo(java.util.List<String> info,
                                   RandomState random, BlockPos pos) {
    }
}
