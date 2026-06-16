package com.abhil.createworld;

import com.mojang.brigadier.Command;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.util.TriState;
import net.neoforged.neoforge.event.CommandEvent;
import net.neoforged.neoforge.event.ItemStackedOnOtherEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.StatAwardEvent;
import net.neoforged.neoforge.event.brewing.PlayerBrewedPotionEvent;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.EntityMountEvent;
import net.neoforged.neoforge.event.entity.EntityTravelToDimensionEvent;
import net.neoforged.neoforge.event.entity.item.ItemTossEvent;
import net.neoforged.neoforge.event.entity.living.AnimalTameEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.living.LivingDropsEvent;
import net.neoforged.neoforge.event.entity.living.LivingExperienceDropEvent;
import net.neoforged.neoforge.event.entity.living.LivingEntityUseItemEvent;
import net.neoforged.neoforge.event.entity.player.AdvancementEvent;
import net.neoforged.neoforge.event.entity.player.AnvilRepairEvent;
import net.neoforged.neoforge.event.entity.player.AttackEntityEvent;
import net.neoforged.neoforge.event.entity.player.BonemealEvent;
import net.neoforged.neoforge.event.entity.player.ItemFishedEvent;
import net.neoforged.neoforge.event.entity.player.ItemEntityPickupEvent;
import net.neoforged.neoforge.event.entity.player.PlayerContainerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEnchantItemEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.entity.player.PlayerSetSpawnEvent;
import net.neoforged.neoforge.event.entity.player.PlayerXpEvent;
import net.neoforged.neoforge.event.entity.player.TradeWithVillagerEvent;
import net.neoforged.neoforge.event.entity.player.UseItemOnBlockEvent;
import net.neoforged.neoforge.event.level.BlockDropsEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
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
                                    return CreativeWorldTeleporter.exit(target, false) ? Command.SINGLE_SUCCESS : 0;
                                }))));
    }

    @SubscribeEvent
    public void onCommand(CommandEvent event) {
        var source = event.getParseResults().getContext().getSource();
        if (source.getEntity() instanceof ServerPlayer player
                && CreateWorldSecurity.blocksCommand(player, event.getParseResults().getReader().getString(), source.hasPermission(2))) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public void onTravelToDimension(EntityTravelToDimensionEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer)
                && (CreativeWorldTeleporter.isCreativeWorld(event.getEntity().level())
                        || event.getDimension().equals(CreativeWorldTeleporter.CREATIVE_LEVEL))) {
            event.setCanceled(true);
            return;
        }

        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }

        if (CreateWorldSecurity.isCreativeSessionPlayer(player)
                && !CreativeWorldTeleporter.isInternalTransfer(player)
                && !event.getDimension().equals(CreativeWorldTeleporter.CREATIVE_LEVEL)) {
            event.setCanceled(true);
            player.displayClientMessage(net.minecraft.network.chat.Component.translatable("message.createworld.blocked_dimension_travel"), false);
            return;
        }

        if (event.getDimension().equals(CreativeWorldTeleporter.CREATIVE_LEVEL)
                && !CreativeWorldTeleporter.canTravelToCreative(player)) {
            event.setCanceled(true);
            player.displayClientMessage(net.minecraft.network.chat.Component.translatable("message.createworld.unauthorized_entry"), false);
        }
    }

    @SubscribeEvent
    public void onChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || CreativeWorldTeleporter.isInternalTransfer(player)) {
            return;
        }

        if (CreativeWorldTeleporter.isCreativeWorld(player.level())) {
            CreativeWorldTeleporter.captureForcedCreativeEntry(player);
        } else if (CreativeWorldTeleporter.hasSession(player)) {
            CreativeWorldTeleporter.exit(player, false);
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
                CreativeWorldTeleporter.exit(player, false);
            }
        } else if (CreativeWorldTeleporter.isCreativeWorld(player.level())) {
            CreativeWorldTeleporter.rejectUnauthorizedCreativeAccess(player, true);
        }
    }

    @SubscribeEvent
    public void onLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            CreativeWorldTeleporter.wipeBeforeCreativePlayerSave(player);
        }
    }

    @SubscribeEvent
    public void onPlayerTick(PlayerTickEvent.Post event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            CreativeWorldTeleporter.tick(player);
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onPlayerChangeGameMode(PlayerEvent.PlayerChangeGameModeEvent event) {
        if (event.getEntity() instanceof ServerPlayer player
                && CreateWorldSecurity.isCreativeSessionPlayer(player)
                && event.getNewGameMode() != GameType.CREATIVE) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || !CreateWorldSecurity.isCreativeSessionPlayer(player)) {
            return;
        }

        if (CreateWorldSecurity.blocksBlockInteraction(event.getLevel(), event.getPos())
                || CreateWorldSecurity.blocksItemUse(event.getItemStack())) {
            event.setCancellationResult(InteractionResult.FAIL);
            event.setCanceled(true);
            player.displayClientMessage(net.minecraft.network.chat.Component.translatable("message.createworld.blocked_interaction"), true);
        }
    }

    @SubscribeEvent
    public void onRightClickItem(PlayerInteractEvent.RightClickItem event) {
        if (event.getEntity() instanceof ServerPlayer player
                && CreateWorldSecurity.isCreativeSessionPlayer(player)
                && CreateWorldSecurity.blocksItemUse(event.getItemStack())) {
            event.setCancellationResult(InteractionResult.FAIL);
            event.setCanceled(true);
            player.displayClientMessage(net.minecraft.network.chat.Component.translatable("message.createworld.blocked_interaction"), true);
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onUseItemStart(LivingEntityUseItemEvent.Start event) {
        if (event.getEntity() instanceof ServerPlayer player
                && CreateWorldSecurity.isCreativeSessionPlayer(player)
                && CreateWorldSecurity.blocksItemUse(event.getItem())) {
            event.setCanceled(true);
            event.setDuration(0);
            player.displayClientMessage(net.minecraft.network.chat.Component.translatable("message.createworld.blocked_interaction"), true);
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onUseItemTick(LivingEntityUseItemEvent.Tick event) {
        if (event.getEntity() instanceof ServerPlayer player
                && CreateWorldSecurity.isCreativeSessionPlayer(player)
                && CreateWorldSecurity.blocksItemUse(event.getItem())) {
            event.setCanceled(true);
            event.setDuration(0);
            player.displayClientMessage(net.minecraft.network.chat.Component.translatable("message.createworld.blocked_interaction"), true);
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onUseItemStop(LivingEntityUseItemEvent.Stop event) {
        if (event.getEntity() instanceof ServerPlayer player
                && CreateWorldSecurity.isCreativeSessionPlayer(player)
                && CreateWorldSecurity.blocksItemUse(event.getItem())) {
            event.setCanceled(true);
            event.setDuration(0);
            player.displayClientMessage(net.minecraft.network.chat.Component.translatable("message.createworld.blocked_interaction"), true);
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onUseItemOnBlock(UseItemOnBlockEvent event) {
        if (event.getPlayer() instanceof ServerPlayer player
                && CreateWorldSecurity.isCreativeSessionPlayer(player)
                && (CreateWorldSecurity.blocksBlockInteraction(event.getLevel(), event.getPos())
                        || CreateWorldSecurity.blocksItemUse(event.getItemStack()))) {
            event.cancelWithResult(ItemInteractionResult.FAIL);
            player.displayClientMessage(net.minecraft.network.chat.Component.translatable("message.createworld.blocked_interaction"), true);
        }
    }

    @SubscribeEvent
    public void onEntityInteract(PlayerInteractEvent.EntityInteract event) {
        if (event.getEntity() instanceof ServerPlayer player
                && CreateWorldSecurity.isCreativeSessionPlayer(player)
                && (CreateWorldSecurity.blocksEntityInteraction(event.getTarget())
                        || CreateWorldSecurity.blocksItemUse(event.getItemStack()))) {
            event.setCancellationResult(InteractionResult.FAIL);
            event.setCanceled(true);
            player.displayClientMessage(net.minecraft.network.chat.Component.translatable("message.createworld.blocked_interaction"), true);
        }
    }

    @SubscribeEvent
    public void onEntityInteractSpecific(PlayerInteractEvent.EntityInteractSpecific event) {
        if (event.getEntity() instanceof ServerPlayer player
                && CreateWorldSecurity.isCreativeSessionPlayer(player)
                && (CreateWorldSecurity.blocksEntityInteraction(event.getTarget())
                        || CreateWorldSecurity.blocksItemUse(event.getItemStack()))) {
            event.setCancellationResult(InteractionResult.FAIL);
            event.setCanceled(true);
            player.displayClientMessage(net.minecraft.network.chat.Component.translatable("message.createworld.blocked_interaction"), true);
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onAttackEntity(AttackEntityEvent event) {
        if (event.getEntity() instanceof ServerPlayer player
                && CreateWorldSecurity.isCreativeSessionPlayer(player)
                && (CreateWorldSecurity.blocksEntityInteraction(event.getTarget())
                        || CreateWorldSecurity.blocksItemUse(player.getMainHandItem()))) {
            event.setCanceled(true);
            player.displayClientMessage(net.minecraft.network.chat.Component.translatable("message.createworld.blocked_interaction"), true);
        }
    }

    @SubscribeEvent
    public void onBlockPlace(BlockEvent.EntityPlaceEvent event) {
        if (event.getEntity() instanceof ServerPlayer player
                && CreateWorldSecurity.isCreativeSessionPlayer(player)
                && CreateWorldSecurity.blocksBlockPlacement(event.getLevel(), event.getPos(), event.getPlacedBlock())) {
            event.setCanceled(true);
            player.displayClientMessage(net.minecraft.network.chat.Component.translatable("message.createworld.blocked_placement"), true);
        }
    }

    @SubscribeEvent
    public void onLeftClickBlock(PlayerInteractEvent.LeftClickBlock event) {
        if (event.getEntity() instanceof ServerPlayer player
                && CreateWorldSecurity.isCreativeSessionPlayer(player)
                && CreateWorldSecurity.blocksBlockInteraction(event.getLevel(), event.getPos())) {
            event.setCanceled(true);
            player.displayClientMessage(net.minecraft.network.chat.Component.translatable("message.createworld.blocked_interaction"), true);
        }
    }

    @SubscribeEvent
    public void onBlockBreak(BlockEvent.BreakEvent event) {
        if (event.getPlayer() instanceof ServerPlayer player
                && CreateWorldSecurity.isCreativeSessionPlayer(player)
                && CreateWorldSecurity.blocksBlockInteraction(player.level(), event.getPos())) {
            event.setCanceled(true);
            player.displayClientMessage(net.minecraft.network.chat.Component.translatable("message.createworld.blocked_interaction"), true);
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onBlockDrops(BlockDropsEvent event) {
        boolean creativeBoundary = CreativeWorldTeleporter.isCreativeWorld(event.getLevel())
                || event.getBreaker() instanceof ServerPlayer player && CreateWorldSecurity.isCreativeSessionPlayer(player);
        if (!creativeBoundary) {
            return;
        }

        if (CreateWorldConfig.BLOCK_CREATIVE_PROGRESS.get()) {
            event.setDroppedExperience(0);
        }
        if (CreateWorldConfig.DELETE_CREATIVE_ITEM_DROPS.get()) {
            event.getDrops().clear();
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public void onBlockToolModification(BlockEvent.BlockToolModificationEvent event) {
        if (event.getPlayer() instanceof ServerPlayer player
                && CreateWorldSecurity.isCreativeSessionPlayer(player)
                && (CreateWorldSecurity.blocksBlockInteraction(player.level(), event.getPos())
                        || CreateWorldSecurity.blocksItemUse(event.getHeldItemStack()))) {
            event.setCanceled(true);
            player.displayClientMessage(net.minecraft.network.chat.Component.translatable("message.createworld.blocked_interaction"), true);
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onItemStackedOnOther(ItemStackedOnOtherEvent event) {
        if (event.getPlayer() instanceof ServerPlayer player
                && CreateWorldSecurity.isCreativeSessionPlayer(player)
                && (CreateWorldSecurity.blocksItemUse(event.getCarriedItem())
                        || CreateWorldSecurity.blocksItemUse(event.getStackedOnItem()))) {
            event.setCanceled(true);
            player.displayClientMessage(net.minecraft.network.chat.Component.translatable("message.createworld.blocked_interaction"), true);
        }
    }

    @SubscribeEvent
    public void onContainerOpen(PlayerContainerEvent.Open event) {
        if (event.getEntity() instanceof ServerPlayer player
                && CreateWorldConfig.BLOCK_CREATIVE_STORAGE_INTERACTION.get()
                && CreateWorldSecurity.isCreativeSessionPlayer(player)
                && event.getContainer() != player.inventoryMenu) {
            player.closeContainer();
            player.displayClientMessage(net.minecraft.network.chat.Component.translatable("message.createworld.blocked_interaction"), true);
        }
    }

    @SubscribeEvent
    public void onItemToss(ItemTossEvent event) {
        if (event.getPlayer() instanceof ServerPlayer player
                && CreateWorldConfig.DELETE_CREATIVE_ITEM_DROPS.get()
                && com.abhil.createworld.CreateWorldSecurity.isCreativeSessionPlayer(player)) {
            event.getEntity().discard();
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public void onItemPickup(ItemEntityPickupEvent.Pre event) {
        if (event.getPlayer() instanceof ServerPlayer player && CreateWorldSecurity.isCreativeSessionPlayer(player)) {
            event.setCanPickup(TriState.FALSE);
        }
    }

    @SubscribeEvent
    public void onEntityJoinLevel(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide() || !CreativeWorldTeleporter.isCreativeWorld(event.getLevel())) {
            return;
        }

        if (event.getEntity() instanceof ServerPlayer) {
            return;
        }

        if (event.getEntity() instanceof ItemEntity && CreateWorldConfig.DELETE_CREATIVE_ITEM_DROPS.get()) {
            event.setCanceled(true);
        } else if (event.getEntity() instanceof ExperienceOrb && CreateWorldConfig.BLOCK_CREATIVE_PROGRESS.get()) {
            event.setCanceled(true);
        } else if (!event.loadedFromDisk()
                && CreateWorldConfig.BLOCK_CREATIVE_STORAGE_INTERACTION.get()
                && CreateWorldSecurity.blocksEntityInteraction(event.getEntity())) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onItemFished(ItemFishedEvent event) {
        if (CreateWorldConfig.BLOCK_CREATIVE_PROGRESS.get()
                && event.getEntity() instanceof ServerPlayer player
                && CreateWorldSecurity.isCreativeSessionPlayer(player)) {
            event.getDrops().clear();
            event.setCanceled(true);
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onBonemeal(BonemealEvent event) {
        if (blocksCreativeProgress(event.getPlayer())) {
            event.setSuccessful(false);
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onAnimalTame(AnimalTameEvent event) {
        if (blocksCreativeProgress(event.getTamer())) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onItemCrafted(PlayerEvent.ItemCraftedEvent event) {
        if (blocksCreativeProgress(event.getEntity())) {
            emptyEventStack(event.getCrafting());
            event.getInventory().clearContent();
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onItemSmelted(PlayerEvent.ItemSmeltedEvent event) {
        if (blocksCreativeProgress(event.getEntity())) {
            emptyEventStack(event.getSmelting());
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onPlayerBrewedPotion(PlayerBrewedPotionEvent event) {
        if (blocksCreativeProgress(event.getEntity())) {
            emptyEventStack(event.getStack());
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onPlayerEnchantItem(PlayerEnchantItemEvent event) {
        if (blocksCreativeProgress(event.getEntity())) {
            emptyEventStack(event.getEnchantedItem());
            try {
                event.getEnchantments().clear();
            } catch (UnsupportedOperationException ignored) {
                // Some callers may expose an immutable enchantment list; the event item is still emptied above.
            }
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onAnvilRepair(AnvilRepairEvent event) {
        if (blocksCreativeProgress(event.getEntity())) {
            emptyEventStack(event.getLeft());
            emptyEventStack(event.getRight());
            emptyEventStack(event.getOutput());
            event.setBreakChance(0.0F);
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onTradeWithVillager(TradeWithVillagerEvent event) {
        if (blocksCreativeProgress(event.getEntity())) {
            event.getMerchantOffer().setToOutOfStock();
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onPlayerSetSpawn(PlayerSetSpawnEvent event) {
        if (CreateWorldConfig.BLOCK_CREATIVE_PROGRESS.get()
                && event.getEntity() instanceof ServerPlayer player
                && CreateWorldSecurity.isCreativeSessionPlayer(player)) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onXpPickup(PlayerXpEvent.PickupXp event) {
        if (CreateWorldConfig.BLOCK_CREATIVE_PROGRESS.get()
                && event.getEntity() instanceof ServerPlayer player
                && CreateWorldSecurity.isCreativeSessionPlayer(player)) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onXpChange(PlayerXpEvent.XpChange event) {
        if (CreateWorldConfig.BLOCK_CREATIVE_PROGRESS.get()
                && event.getEntity() instanceof ServerPlayer player
                && CreateWorldSecurity.isCreativeSessionPlayer(player)) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onXpLevelChange(PlayerXpEvent.LevelChange event) {
        if (CreateWorldConfig.BLOCK_CREATIVE_PROGRESS.get()
                && event.getEntity() instanceof ServerPlayer player
                && CreateWorldSecurity.isCreativeSessionPlayer(player)) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onLivingExperienceDrop(LivingExperienceDropEvent event) {
        if (CreateWorldConfig.BLOCK_CREATIVE_PROGRESS.get()
                && (CreativeWorldTeleporter.isCreativeWorld(event.getEntity().level())
                        || event.getAttackingPlayer() instanceof ServerPlayer player && CreateWorldSecurity.isCreativeSessionPlayer(player))) {
            event.setDroppedExperience(0);
            event.setCanceled(true);
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onStatAward(StatAwardEvent event) {
        if (CreateWorldConfig.BLOCK_CREATIVE_PROGRESS.get()
                && event.getEntity() instanceof ServerPlayer player
                && CreateWorldSecurity.isCreativeSessionPlayer(player)) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onAdvancementProgress(AdvancementEvent.AdvancementProgressEvent event) {
        if (CreateWorldConfig.BLOCK_CREATIVE_PROGRESS.get()
                && event.getProgressType() == AdvancementEvent.AdvancementProgressEvent.ProgressType.GRANT
                && event.getEntity() instanceof ServerPlayer player
                && CreateWorldSecurity.isCreativeSessionPlayer(player)) {
            player.getAdvancements().revoke(event.getAdvancement(), event.getCriterionName());
        }
    }

    @SubscribeEvent
    public void onEntityMount(EntityMountEvent event) {
        if (event.isMounting()
                && event.getEntityMounting() instanceof ServerPlayer player
                && CreateWorldSecurity.isCreativeSessionPlayer(player)
                && event.getEntityBeingMounted() != null
                && CreateWorldSecurity.blocksEntityInteraction(event.getEntityBeingMounted())) {
            event.setCanceled(true);
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

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onLivingDeath(LivingDeathEvent event) {
        if (event.getEntity() instanceof ServerPlayer player && CreativeWorldTeleporter.isCreativeWorld(player.level())) {
            CreativeWorldTeleporter.handleCreativeDeath(player);
            event.setCanceled(true);
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onLivingDrops(LivingDropsEvent event) {
        if (CreativeWorldTeleporter.isCreativeWorld(event.getEntity().level())
                || event.getSource().getEntity() instanceof ServerPlayer player && CreateWorldSecurity.isCreativeSessionPlayer(player)) {
            event.getDrops().clear();
            event.setCanceled(true);
        }
    }

    private static boolean blocksCreativeProgress(Player player) {
        return player instanceof ServerPlayer serverPlayer
                && CreateWorldConfig.BLOCK_CREATIVE_PROGRESS.get()
                && CreateWorldSecurity.isCreativeSessionPlayer(serverPlayer);
    }

    private static void emptyEventStack(ItemStack stack) {
        if (!stack.isEmpty()) {
            stack.setCount(0);
        }
    }
}
