package com.greatsnowsea.block;

import com.greatsnowsea.GreatSnowSeaMod;
import com.greatsnowsea.item.ModItems;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredRegister;

public class ModBlocks {

    public static final DeferredRegister.Blocks BLOCKS =
            DeferredRegister.createBlocks(GreatSnowSeaMod.MOD_ID);

    public static final DeferredBlock<Block> YIRD_WOOD = BLOCKS.register("yird_wood",
            () -> new Block(Block.Properties.of()
                    .destroyTime(2.0f)
                    .explosionResistance(2.0f)));

    public static final DeferredBlock<Block> STRIPPED_YIRD_WOOD = BLOCKS.register("stripped_yird_wood",
            () -> new Block(Block.Properties.of()
                    .destroyTime(2.0f)
                    .explosionResistance(2.0f)));

    public static final DeferredBlock<Block> YIRD_FLESH = BLOCKS.register("yird_flesh",
            () -> new Block(Block.Properties.of()
                    .destroyTime(1.0f)
                    .explosionResistance(1.0f)));

    public static void register(IEventBus modEventBus) {
        BLOCKS.register(modEventBus);

        ModItems.ITEMS.register("yird_wood",
                () -> new BlockItem(YIRD_WOOD.get(), new Item.Properties()));
        ModItems.ITEMS.register("stripped_yird_wood",
                () -> new BlockItem(STRIPPED_YIRD_WOOD.get(), new Item.Properties()));
        ModItems.ITEMS.register("yird_flesh",
                () -> new BlockItem(YIRD_FLESH.get(), new Item.Properties()));
    }
}
