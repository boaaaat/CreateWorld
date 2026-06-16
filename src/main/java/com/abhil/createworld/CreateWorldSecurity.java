package com.abhil.createworld;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.capabilities.Capabilities;

public final class CreateWorldSecurity {
    public static final TagKey<Block> BLOCKED_CREATIVE_INTERACTION_BLOCKS = TagKey.create(
            Registries.BLOCK,
            id("blocked_creative_interaction"));
    public static final TagKey<Block> BLOCKED_CREATIVE_PLACEMENT_BLOCKS = TagKey.create(
            Registries.BLOCK,
            id("blocked_creative_placement"));
    public static final TagKey<Item> BLOCKED_CREATIVE_USE_ITEMS = TagKey.create(
            Registries.ITEM,
            id("blocked_creative_use"));
    public static final TagKey<EntityType<?>> BLOCKED_CREATIVE_INTERACTION_ENTITY_TYPES = TagKey.create(
            Registries.ENTITY_TYPE,
            id("blocked_creative_interaction"));

    public static boolean isCreativeSessionPlayer(Player player) {
        return !player.level().isClientSide()
                && CreativeWorldTeleporter.isCreativeWorld(player.level())
                && CreativeWorldTeleporter.hasSession(player);
    }

    public static boolean blocksCommand(Player player, String command, boolean hasOperatorPermission) {
        if (!CreateWorldConfig.BLOCK_CREATIVE_COMMANDS.get()
                || !isCreativeSessionPlayer(player)
                || hasOperatorPermission) {
            return false;
        }

        String normalized = command.startsWith("/") ? command.substring(1) : command;
        normalized = normalized.trim();
        return !normalized.equals("createworld return") && !normalized.equals("createworld:return");
    }

    public static boolean blocksBlockInteraction(Level level, BlockPos pos) {
        if (!CreativeWorldTeleporter.isCreativeWorld(level)) {
            return false;
        }

        BlockState state = level.getBlockState(pos);
        return state.is(BLOCKED_CREATIVE_INTERACTION_BLOCKS)
                || hasRiskyId(BuiltInRegistries.BLOCK.getKey(state.getBlock()))
                || hasConfiguredBlockCapability(level, pos);
    }

    public static boolean blocksBlockPlacement(LevelAccessor level, BlockPos pos, BlockState state) {
        return state.is(BLOCKED_CREATIVE_PLACEMENT_BLOCKS)
                || hasRiskyId(BuiltInRegistries.BLOCK.getKey(state.getBlock()))
                || level instanceof Level concreteLevel && hasConfiguredBlockCapability(concreteLevel, pos);
    }

    public static boolean blocksItemUse(ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }

        return stack.is(BLOCKED_CREATIVE_USE_ITEMS)
                || hasRiskyId(BuiltInRegistries.ITEM.getKey(stack.getItem()))
                || hasCreativePayloadComponent(stack)
                || hasConfiguredItemCapability(stack);
    }

    public static boolean blocksEntityInteraction(Entity entity) {
        return entity.getType().is(BLOCKED_CREATIVE_INTERACTION_ENTITY_TYPES)
                || hasRiskyId(BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()))
                || entity instanceof ItemEntity
                || hasConfiguredEntityCapability(entity);
    }

    public static boolean hasRiskyId(ResourceLocation id) {
        if (!CreateWorldConfig.BLOCK_RISKY_ID_PATTERNS.get() || id == null) {
            return false;
        }

        String value = id.toString().toLowerCase();
        for (String pattern : CreateWorldConfig.RISKY_ID_PATTERNS.get()) {
            if (!pattern.isBlank() && value.contains(pattern.toLowerCase())) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasConfiguredItemCapability(ItemStack stack) {
        if (!CreateWorldConfig.BLOCK_CREATIVE_STORAGE_INTERACTION.get()) {
            return false;
        }

        return stack.getCapability(Capabilities.ItemHandler.ITEM) != null
                || stack.getCapability(Capabilities.FluidHandler.ITEM) != null
                || stack.getCapability(Capabilities.EnergyStorage.ITEM) != null;
    }

    private static boolean hasCreativePayloadComponent(ItemStack stack) {
        if (!CreateWorldConfig.BLOCK_CREATIVE_ITEM_PAYLOADS.get()) {
            return false;
        }

        var container = stack.get(DataComponents.CONTAINER);
        if (container != null && container.nonEmptyStream().findAny().isPresent()) {
            return true;
        }

        var bundle = stack.get(DataComponents.BUNDLE_CONTENTS);
        if (bundle != null && !bundle.isEmpty()) {
            return true;
        }

        var chargedProjectiles = stack.get(DataComponents.CHARGED_PROJECTILES);
        if (chargedProjectiles != null && !chargedProjectiles.isEmpty()) {
            return true;
        }

        var blockEntityData = stack.get(DataComponents.BLOCK_ENTITY_DATA);
        if (blockEntityData != null && !blockEntityData.isEmpty()) {
            return true;
        }

        var entityData = stack.get(DataComponents.ENTITY_DATA);
        if (entityData != null && !entityData.isEmpty()) {
            return true;
        }

        var bucketEntityData = stack.get(DataComponents.BUCKET_ENTITY_DATA);
        if (bucketEntityData != null && !bucketEntityData.isEmpty()) {
            return true;
        }

        var customData = stack.get(DataComponents.CUSTOM_DATA);
        if (customData != null && !customData.isEmpty()) {
            return true;
        }

        return stack.has(DataComponents.CONTAINER_LOOT)
                || stack.has(DataComponents.RECIPES);
    }

    private static boolean hasConfiguredBlockCapability(Level level, BlockPos pos) {
        if (!CreateWorldConfig.BLOCK_CREATIVE_STORAGE_INTERACTION.get()) {
            return false;
        }

        if (level.getCapability(Capabilities.ItemHandler.BLOCK, pos, null) != null
                || level.getCapability(Capabilities.FluidHandler.BLOCK, pos, null) != null
                || level.getCapability(Capabilities.EnergyStorage.BLOCK, pos, null) != null) {
            return true;
        }

        for (Direction direction : Direction.values()) {
            if (level.getCapability(Capabilities.ItemHandler.BLOCK, pos, direction) != null
                    || level.getCapability(Capabilities.FluidHandler.BLOCK, pos, direction) != null
                    || level.getCapability(Capabilities.EnergyStorage.BLOCK, pos, direction) != null) {
                return true;
            }
        }

        return false;
    }

    private static boolean hasConfiguredEntityCapability(Entity entity) {
        if (!CreateWorldConfig.BLOCK_CREATIVE_STORAGE_INTERACTION.get()) {
            return false;
        }

        if (entity.getCapability(Capabilities.ItemHandler.ENTITY) != null
                || entity.getCapability(Capabilities.ItemHandler.ENTITY_AUTOMATION, null) != null
                || entity.getCapability(Capabilities.FluidHandler.ENTITY, null) != null
                || entity.getCapability(Capabilities.EnergyStorage.ENTITY, null) != null) {
            return true;
        }

        for (Direction direction : Direction.values()) {
            if (entity.getCapability(Capabilities.ItemHandler.ENTITY_AUTOMATION, direction) != null
                    || entity.getCapability(Capabilities.FluidHandler.ENTITY, direction) != null
                    || entity.getCapability(Capabilities.EnergyStorage.ENTITY, direction) != null) {
                return true;
            }
        }

        return false;
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(CreateWorld.MODID, path);
    }

    private CreateWorldSecurity() {
    }
}
