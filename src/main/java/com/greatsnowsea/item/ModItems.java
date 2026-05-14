package com.greatsnowsea.item;

import com.greatsnowsea.GreatSnowSeaMod;
import net.minecraft.world.item.Item;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public class ModItems {

    public static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(net.minecraft.core.registries.Registries.ITEM, GreatSnowSeaMod.MOD_ID);

    public static final DeferredHolder<Item, Item> BLUE_HOLE_COMPASS =
            ITEMS.register("blue_hole_compass", () -> new BlueHoleCompassItem(new Item.Properties()));

    public static void register(IEventBus modEventBus) {
        ITEMS.register(modEventBus);
    }
}