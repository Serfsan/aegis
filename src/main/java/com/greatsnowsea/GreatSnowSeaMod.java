package com.greatsnowsea;

import com.greatsnowsea.item.ModItems;
import com.greatsnowsea.worldgen.ModChunkGenerators;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;

@Mod(GreatSnowSeaMod.MOD_ID)
public class GreatSnowSeaMod {
    public static final String MOD_ID = "greatsnowsea";

    public GreatSnowSeaMod(IEventBus modEventBus) {
        ModItems.register(modEventBus);
        ModChunkGenerators.register(modEventBus);
    }
}