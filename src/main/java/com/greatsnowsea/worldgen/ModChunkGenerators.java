package com.greatsnowsea.worldgen;

import com.greatsnowsea.GreatSnowSeaMod;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public class ModChunkGenerators {

    public static final DeferredRegister<MapCodec<? extends ChunkGenerator>> CHUNK_GENERATORS =
            DeferredRegister.create(Registries.CHUNK_GENERATOR, GreatSnowSeaMod.MOD_ID);

    public static final DeferredHolder<MapCodec<? extends ChunkGenerator>, MapCodec<SeaChunkGenerator>> SEA =
            CHUNK_GENERATORS.register("sea", () -> SeaChunkGenerator.CODEC);

    public static final DeferredHolder<MapCodec<? extends ChunkGenerator>, MapCodec<NetherSeaChunkGenerator>> NETHER_SEA =
            CHUNK_GENERATORS.register("nether_sea", () -> NetherSeaChunkGenerator.CODEC);

    public static void register(IEventBus modEventBus) {
        CHUNK_GENERATORS.register(modEventBus);
    }
}
