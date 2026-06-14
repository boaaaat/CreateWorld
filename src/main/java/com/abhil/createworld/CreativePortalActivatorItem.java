package com.abhil.createworld;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;

public final class CreativePortalActivatorItem extends Item {
    public CreativePortalActivatorItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Level level = context.getLevel();
        BlockPos clicked = context.getClickedPos();
        BlockPos inside = clicked.relative(context.getClickedFace());

        if (level.isClientSide()) {
            return InteractionResult.SUCCESS;
        }

        if (CreativePortalShape.tryCreate((ServerLevel) level, inside)) {
            return InteractionResult.CONSUME;
        }

        if (context.getPlayer() != null) {
            context.getPlayer().displayClientMessage(Component.translatable("message.createworld.invalid_portal"), true);
        }
        return InteractionResult.FAIL;
    }
}
