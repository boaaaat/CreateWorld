package com.abhil.createworld;

import com.mojang.brigadier.Command;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.living.LivingDropsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

public final class CreateWorldEvents {
    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal(CreateWorld.MODID)
                .then(Commands.literal("return")
                        .requires(source -> source.getEntity() instanceof ServerPlayer)
                        .executes(context -> {
                            ServerPlayer player = context.getSource().getPlayerOrException();
                            return CreativeWorldTeleporter.exit(player, true) ? Command.SINGLE_SUCCESS : 0;
                        }))
                .then(Commands.literal("restore")
                        .requires(source -> source.hasPermission(2))
                        .then(Commands.argument("player", EntityArgument.player())
                                .executes(context -> {
                                    ServerPlayer target = EntityArgument.getPlayer(context, "player");
                                    CreativeWorldTeleporter.restore(target);
                                    return Command.SINGLE_SUCCESS;
                                }))));
    }

    @SubscribeEvent
    public void onChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || CreativeWorldTeleporter.isInternalTransfer(player)) {
            return;
        }

        if (CreativeWorldTeleporter.isCreativeWorld(player.level())) {
            CreativeWorldTeleporter.captureForcedCreativeEntry(player);
        } else if (CreativeWorldTeleporter.hasSession(player)) {
            CreativeWorldTeleporter.restore(player);
        }
    }

    @SubscribeEvent
    public void onLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }

        if (CreativeWorldTeleporter.hasSession(player)) {
            if (CreativeWorldTeleporter.isCreativeWorld(player.level())) {
                CreativeWorldTeleporter.wipeCreativeCarriedItems(player);
            } else {
                CreativeWorldTeleporter.restore(player);
            }
        } else if (CreativeWorldTeleporter.isCreativeWorld(player.level())) {
            CreativeWorldTeleporter.captureForcedCreativeEntry(player);
        }
    }

    @SubscribeEvent
    public void onPlayerTick(PlayerTickEvent.Post event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            CreativeWorldTeleporter.tick(player);
        }
    }

    @SubscribeEvent
    public void onClone(PlayerEvent.Clone event) {
        CreativeWorldTeleporter.copySession(event.getOriginal(), event.getEntity());
    }

    @SubscribeEvent
    public void onRespawn(PlayerEvent.PlayerRespawnEvent event) {
        if (event.getEntity() instanceof ServerPlayer player && CreativeWorldTeleporter.hasSession(player)) {
            CreativeWorldTeleporter.wipeCreativeCarriedItems(player);
            CreativeWorldTeleporter.tick(player);
        }
    }

    @SubscribeEvent
    public void onLivingDrops(LivingDropsEvent event) {
        if (event.getEntity() instanceof ServerPlayer player && CreativeWorldTeleporter.isCreativeWorld(player.level())) {
            event.getDrops().clear();
            event.setCanceled(true);
        }
    }
}
