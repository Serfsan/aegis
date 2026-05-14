package com.greatsnowsea.item;

import com.greatsnowsea.worldgen.SeaChunkGenerator;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.ChunkGenerator;

import java.util.List;

public class BlueHoleCompassItem extends Item {

    private static final int SEARCH_RADIUS = 10000;
    private static final int UPDATE_INTERVAL = 20;

    public BlueHoleCompassItem(Properties properties) {
        super(properties);
    }

    @Override
    public void inventoryTick(ItemStack stack, Level level, Entity entity, int slotId, boolean isSelected) {
        if (level.isClientSide || !(level instanceof ServerLevel serverLevel)) return;
        if (!(entity instanceof Player player)) return;
        if (player.tickCount % UPDATE_INTERVAL != 0) return;

        ChunkGenerator generator = serverLevel.getChunkSource().getGenerator();
        if (!(generator instanceof SeaChunkGenerator seaGen)) return;

        BlockPos nearest = SeaChunkGenerator.findNearestMegaHole(
                seaGen.seed, player.getBlockX(), player.getBlockZ(), SEARCH_RADIUS);

        CompoundTag tag = new CompoundTag();
        if (nearest != null) {
            tag.putInt("TargetX", nearest.getX());
            tag.putInt("TargetZ", nearest.getZ());
        }
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        CustomData customData = stack.get(DataComponents.CUSTOM_DATA);
        if (customData != null) {
            CompoundTag tag = customData.copyTag();
            if (tag.contains("TargetX") && tag.contains("TargetZ")) {
                int tx = tag.getInt("TargetX");
                int tz = tag.getInt("TargetZ");
                tooltip.add(Component.literal("Points to: " + tx + ", " + tz)
                        .withStyle(ChatFormatting.AQUA));

                Player player = Minecraft.getInstance().player;
                if (player != null) {
                    double dx = tx - player.getX();
                    double dz = tz - player.getZ();
                    double dist = Math.sqrt(dx * dx + dz * dz);
                    String direction = getDirection(dx, dz);
                    tooltip.add(Component.literal(String.format("%.0f blocks %s", dist, direction))
                            .withStyle(ChatFormatting.GRAY));
                }
            }
        } else {
            tooltip.add(Component.literal("No blue hole detected nearby")
                    .withStyle(ChatFormatting.DARK_GRAY, ChatFormatting.ITALIC));
        }
    }

    private static String getDirection(double dx, double dz) {
        double angle = Math.toDegrees(Math.atan2(-dx, dz));
        if (angle < 0) angle += 360;

        if (angle < 22.5) return "S";
        if (angle < 67.5) return "SW";
        if (angle < 112.5) return "W";
        if (angle < 157.5) return "NW";
        if (angle < 202.5) return "N";
        if (angle < 247.5) return "NE";
        if (angle < 292.5) return "E";
        if (angle < 337.5) return "SE";
        return "S";
    }
}