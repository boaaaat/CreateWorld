package com.abhil.createworld;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.IItemHandlerModifiable;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class CreativeWorldTeleporter {
    public static final ResourceKey<Level> CREATIVE_LEVEL = ResourceKey.create(
            Registries.DIMENSION,
            ResourceLocation.fromNamespaceAndPath(CreateWorld.MODID, "creative_world"));

    private static final String SESSION_TAG = CreateWorld.MODID + "_creative_session";
    private static final String SNAPSHOT_TAG = "Snapshot";
    private static final String CAPABILITY_ITEMS_TAG = "CapabilityItems";
    private static final int VANILLA_PLAYER_ITEM_HANDLER_SLOTS = 41;

    private static final Map<UUID, Long> PORTAL_COOLDOWNS = new HashMap<>();
    private static final Map<UUID, PortalWait> PORTAL_WAITS = new HashMap<>();
    private static final Set<UUID> INTERNAL_TRANSFERS = new HashSet<>();

    public static void handlePortalContact(Player player, BlockPos portalPos) {
        if (!(player instanceof ServerPlayer serverPlayer)) {
            return;
        }

        ServerLevel level = serverPlayer.serverLevel();
        long now = level.getGameTime();
        long blockedUntil = PORTAL_COOLDOWNS.getOrDefault(serverPlayer.getUUID(), 0L);
        if (now < blockedUntil) {
            return;
        }

        UUID id = serverPlayer.getUUID();
        PortalWait wait = PORTAL_WAITS.get(id);
        if (wait == null || !wait.dimension.equals(level.dimension()) || now - wait.lastContactTick > 1) {
            wait = new PortalWait(level.dimension(), now);
            PORTAL_WAITS.put(id, wait);
            serverPlayer.displayClientMessage(Component.translatable("message.createworld.portal_warmup_start"), true);
        }

        wait.lastContactTick = now;
        int requiredTicks = CreateWorldConfig.PORTAL_WARMUP_TICKS.get();
        int elapsedTicks = (int) (now - wait.startedTick + 1);
        if (elapsedTicks < requiredTicks) {
            int remainingSeconds = (requiredTicks - elapsedTicks + 19) / 20;
            if (remainingSeconds != wait.lastShownSecond) {
                wait.lastShownSecond = remainingSeconds;
                serverPlayer.displayClientMessage(Component.translatable("message.createworld.portal_warmup", remainingSeconds), true);
            }
            return;
        }

        PORTAL_WAITS.remove(id);
        boolean moved = isCreativeWorld(serverPlayer.level()) ? exit(serverPlayer, false) : enter(serverPlayer, portalPos);
        if (moved) {
            PORTAL_COOLDOWNS.put(id, now + CreateWorldConfig.PORTAL_COOLDOWN_TICKS.get());
        }
    }

    public static boolean enter(ServerPlayer player, BlockPos sourcePortalPos) {
        if (hasSession(player)) {
            player.displayClientMessage(Component.translatable("message.createworld.already_in_session"), true);
            return false;
        }

        ServerLevel creativeLevel = player.server.getLevel(CREATIVE_LEVEL);
        if (creativeLevel == null) {
            player.displayClientMessage(Component.translatable("message.createworld.missing_dimension"), false);
            CreateWorld.LOGGER.warn("CreateWorld creative dimension {} is not loaded", CREATIVE_LEVEL.location());
            return false;
        }

        CompoundTag session = createSessionSnapshot(player, sourcePortalPos);
        try {
            prepareForCreativeEntry(player, session);
        } catch (IllegalStateException exception) {
            player.displayClientMessage(Component.literal(exception.getMessage()), false);
            return false;
        }

        player.getPersistentData().put(SESSION_TAG, session);
        prepareCreativeArrival(creativeLevel);

        BlockPos spawn = creativeSpawn();
        INTERNAL_TRANSFERS.add(player.getUUID());
        try {
            player.setGameMode(GameType.CREATIVE);
            boolean moved = player.teleportTo(creativeLevel, spawn.getX() + 0.5D, spawn.getY(), spawn.getZ() + 0.5D, Set.of(), player.getYRot(), player.getXRot());
            if (!moved) {
                restore(player);
                player.displayClientMessage(Component.translatable("message.createworld.teleport_failed"), false);
                return false;
            }
        } finally {
            INTERNAL_TRANSFERS.remove(player.getUUID());
        }

        player.displayClientMessage(Component.translatable("message.createworld.entered"), true);
        return true;
    }

    public static boolean exit(ServerPlayer player, boolean command) {
        if (!hasSession(player)) {
            if (command) {
                player.displayClientMessage(Component.translatable("message.createworld.no_session"), false);
            }
            return false;
        }

        CompoundTag session = getSession(player);
        ServerLevel returnLevel = player.server.getLevel(returnDimension(session));
        if (returnLevel == null) {
            player.displayClientMessage(Component.translatable("message.createworld.missing_return_dimension"), false);
            return false;
        }

        wipeCreativeCarriedItems(player);

        INTERNAL_TRANSFERS.add(player.getUUID());
        try {
            boolean moved = player.teleportTo(
                    returnLevel,
                    session.getDouble("ReturnX"),
                    session.getDouble("ReturnY"),
                    session.getDouble("ReturnZ"),
                    Set.of(),
                    session.getFloat("ReturnYRot"),
                    session.getFloat("ReturnXRot"));
            if (!moved) {
                player.displayClientMessage(Component.translatable("message.createworld.teleport_failed"), false);
                return false;
            }
        } finally {
            INTERNAL_TRANSFERS.remove(player.getUUID());
        }

        restore(player);
        player.displayClientMessage(Component.translatable("message.createworld.returned"), true);
        return true;
    }

    public static void captureForcedCreativeEntry(ServerPlayer player) {
        if (hasSession(player)) {
            return;
        }

        CompoundTag session = createSessionSnapshot(player, player.blockPosition());
        try {
            prepareForCreativeEntry(player, session);
            player.getPersistentData().put(SESSION_TAG, session);
            player.setGameMode(GameType.CREATIVE);
            player.displayClientMessage(Component.translatable("message.createworld.forced_session"), false);
        } catch (IllegalStateException exception) {
            player.displayClientMessage(Component.literal(exception.getMessage()), false);
        }
    }

    public static void tick(ServerPlayer player) {
        clearStalePortalWait(player);
        enforceCreativeSession(player);
    }

    private static void enforceCreativeSession(ServerPlayer player) {
        if (!hasSession(player)) {
            return;
        }

        if (isCreativeWorld(player.level())) {
            if (player.gameMode.getGameModeForPlayer() != GameType.CREATIVE) {
                player.setGameMode(GameType.CREATIVE);
            }
        } else if (!isInternalTransfer(player)) {
            restore(player);
        }
    }

    public static void restore(ServerPlayer player) {
        if (!hasSession(player)) {
            return;
        }

        CompoundTag session = getSession(player);
        CompoundTag snapshot = session.getCompound(SNAPSHOT_TAG);

        wipeCreativeCarriedItems(player);
        player.getInventory().load(snapshot.getList("Inventory", Tag.TAG_COMPOUND));
        player.getEnderChestInventory().fromTag(snapshot.getList("EnderItems", Tag.TAG_COMPOUND), player.registryAccess());
        player.getInventory().selected = snapshot.getInt("SelectedItemSlot");
        restoreCapabilityItems(player, session.getList(CAPABILITY_ITEMS_TAG, Tag.TAG_COMPOUND));

        if (snapshot.contains("XpP", Tag.TAG_FLOAT)) {
            player.experienceProgress = snapshot.getFloat("XpP");
            player.experienceLevel = snapshot.getInt("XpLevel");
            player.totalExperience = snapshot.getInt("XpTotal");
        }
        if (snapshot.contains("foodLevel", Tag.TAG_INT)) {
            player.getFoodData().readAdditionalSaveData(snapshot);
        }
        if (snapshot.contains("Health", Tag.TAG_FLOAT)) {
            player.setHealth(Math.min(snapshot.getFloat("Health"), player.getMaxHealth()));
        }
        if (snapshot.contains("AbsorptionAmount", Tag.TAG_FLOAT)) {
            player.setAbsorptionAmount(snapshot.getFloat("AbsorptionAmount"));
        }

        GameType previousMode = GameType.byName(session.getString("GameMode"), GameType.SURVIVAL);
        player.setGameMode(previousMode);

        player.getPersistentData().remove(SESSION_TAG);
        player.inventoryMenu.broadcastChanges();
        player.containerMenu.broadcastChanges();
    }

    public static void copySession(Player original, Player replacement) {
        if (original.getPersistentData().contains(SESSION_TAG, Tag.TAG_COMPOUND)) {
            replacement.getPersistentData().put(SESSION_TAG, original.getPersistentData().getCompound(SESSION_TAG).copy());
        }
    }

    public static void wipeCreativeCarriedItems(ServerPlayer player) {
        player.closeContainer();
        player.containerMenu.setCarried(ItemStack.EMPTY);
        player.inventoryMenu.setCarried(ItemStack.EMPTY);
        player.getInventory().clearContent();
        player.getEnderChestInventory().clearContent();
        wipeSnapshotCapabilityItems(player);
        player.getInventory().setChanged();
        player.getEnderChestInventory().setChanged();
        player.inventoryMenu.broadcastChanges();
        player.containerMenu.broadcastChanges();
    }

    public static boolean hasSession(Player player) {
        return player.getPersistentData().contains(SESSION_TAG, Tag.TAG_COMPOUND);
    }

    public static boolean isCreativeWorld(Level level) {
        return level.dimension().equals(CREATIVE_LEVEL);
    }

    public static boolean isInternalTransfer(Player player) {
        return INTERNAL_TRANSFERS.contains(player.getUUID());
    }

    private static void clearStalePortalWait(ServerPlayer player) {
        PortalWait wait = PORTAL_WAITS.get(player.getUUID());
        if (wait != null && player.serverLevel().getGameTime() - wait.lastContactTick > 1) {
            PORTAL_WAITS.remove(player.getUUID());
        }
    }

    private static CompoundTag createSessionSnapshot(ServerPlayer player, BlockPos sourcePortalPos) {
        CompoundTag session = new CompoundTag();
        CompoundTag snapshot = player.saveWithoutId(new CompoundTag());
        session.put(SNAPSHOT_TAG, snapshot);
        session.put(CAPABILITY_ITEMS_TAG, snapshotCapabilityItems(player));
        session.putString("ReturnDimension", player.level().dimension().location().toString());
        session.putDouble("ReturnX", player.getX());
        session.putDouble("ReturnY", player.getY());
        session.putDouble("ReturnZ", player.getZ());
        session.putFloat("ReturnYRot", player.getYRot());
        session.putFloat("ReturnXRot", player.getXRot());
        session.putInt("SourcePortalX", sourcePortalPos.getX());
        session.putInt("SourcePortalY", sourcePortalPos.getY());
        session.putInt("SourcePortalZ", sourcePortalPos.getZ());
        session.putString("GameMode", player.gameMode.getGameModeForPlayer().getName());
        return session;
    }

    private static void prepareForCreativeEntry(ServerPlayer player, CompoundTag session) {
        ListTag capabilityItems = session.getList(CAPABILITY_ITEMS_TAG, Tag.TAG_COMPOUND);
        if (capabilityItems.isEmpty() && hasNonVanillaCapabilityItems(player)) {
            throw new IllegalStateException("CreateWorld cannot safely clear a modded player inventory exposed by another mod.");
        }
        player.closeContainer();
        player.containerMenu.setCarried(ItemStack.EMPTY);
        player.inventoryMenu.setCarried(ItemStack.EMPTY);
        player.getInventory().clearContent();
        player.getEnderChestInventory().clearContent();
        wipeSnapshotCapabilityItems(player);
        player.getInventory().setChanged();
        player.getEnderChestInventory().setChanged();
        player.inventoryMenu.broadcastChanges();
        player.containerMenu.broadcastChanges();
    }

    private static ListTag snapshotCapabilityItems(ServerPlayer player) {
        ListTag list = new ListTag();
        IItemHandler handler = player.getCapability(Capabilities.ItemHandler.ENTITY);
        if (!(handler instanceof IItemHandlerModifiable modifiable) || !shouldSnapshotCapability(handler)) {
            return list;
        }

        for (int slot = 0; slot < handler.getSlots(); slot++) {
            ItemStack stack = handler.getStackInSlot(slot);
            if (!stack.isEmpty()) {
                CompoundTag slotTag = new CompoundTag();
                slotTag.putInt("Slot", slot);
                Tag saved = stack.saveOptional(player.registryAccess());
                if (saved instanceof CompoundTag savedStack) {
                    slotTag.put("Stack", savedStack);
                    list.add(slotTag);
                }
            }
            modifiable.setStackInSlot(slot, ItemStack.EMPTY);
        }
        return list;
    }

    private static void restoreCapabilityItems(ServerPlayer player, ListTag slots) {
        if (slots.isEmpty()) {
            return;
        }

        IItemHandler handler = player.getCapability(Capabilities.ItemHandler.ENTITY);
        if (!(handler instanceof IItemHandlerModifiable modifiable) || !shouldSnapshotCapability(handler)) {
            CreateWorld.LOGGER.warn("Could not restore {} modded capability item slots for {}", slots.size(), player.getGameProfile().getName());
            return;
        }

        for (int i = 0; i < slots.size(); i++) {
            CompoundTag slotTag = slots.getCompound(i);
            int slot = slotTag.getInt("Slot");
            if (slot >= 0 && slot < handler.getSlots()) {
                ItemStack stack = ItemStack.parseOptional(player.registryAccess(), slotTag.getCompound("Stack"));
                modifiable.setStackInSlot(slot, stack);
            }
        }
    }

    private static void wipeSnapshotCapabilityItems(ServerPlayer player) {
        IItemHandler handler = player.getCapability(Capabilities.ItemHandler.ENTITY);
        if (!(handler instanceof IItemHandlerModifiable modifiable) || !shouldSnapshotCapability(handler)) {
            return;
        }

        for (int slot = 0; slot < handler.getSlots(); slot++) {
            modifiable.setStackInSlot(slot, ItemStack.EMPTY);
        }
    }

    private static boolean hasNonVanillaCapabilityItems(ServerPlayer player) {
        IItemHandler handler = player.getCapability(Capabilities.ItemHandler.ENTITY);
        if (handler == null || handler instanceof IItemHandlerModifiable || !shouldSnapshotCapability(handler)) {
            return false;
        }

        for (int slot = 0; slot < handler.getSlots(); slot++) {
            if (!handler.getStackInSlot(slot).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private static boolean shouldSnapshotCapability(IItemHandler handler) {
        return handler.getSlots() != VANILLA_PLAYER_ITEM_HANDLER_SLOTS;
    }

    private static CompoundTag getSession(Player player) {
        return player.getPersistentData().getCompound(SESSION_TAG);
    }

    private static ResourceKey<Level> returnDimension(CompoundTag session) {
        return ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse(session.getString("ReturnDimension")));
    }

    private static BlockPos creativeSpawn() {
        return new BlockPos(
                CreateWorldConfig.CREATIVE_SPAWN_X.get(),
                CreateWorldConfig.CREATIVE_SPAWN_Y.get(),
                CreateWorldConfig.CREATIVE_SPAWN_Z.get());
    }

    private static void prepareCreativeArrival(ServerLevel level) {
        BlockPos spawn = creativeSpawn();
        BlockPos returnPortal = spawn.offset(-1, 0, 3);
        if (hasReturnPortal(level, returnPortal)) {
            return;
        }

        for (int x = -2; x <= 2; x++) {
            for (int z = -2; z <= 2; z++) {
                level.setBlock(spawn.offset(x, -1, z), net.minecraft.world.level.block.Blocks.SMOOTH_STONE.defaultBlockState(), net.minecraft.world.level.block.Block.UPDATE_ALL);
                level.setBlock(spawn.offset(x, 0, z), net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), net.minecraft.world.level.block.Block.UPDATE_ALL);
                level.setBlock(spawn.offset(x, 1, z), net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), net.minecraft.world.level.block.Block.UPDATE_ALL);
            }
        }

        CreativePortalShape.buildReturnPortal(level, returnPortal, net.minecraft.core.Direction.Axis.X);
    }

    private static boolean hasReturnPortal(ServerLevel level, BlockPos lowerLeftInside) {
        for (int x = 0; x < 2; x++) {
            for (int y = 0; y < 3; y++) {
                if (level.getBlockState(lowerLeftInside.offset(x, y, 0)).is(CreateWorld.CREATIVE_PORTAL.get())) {
                    return true;
                }
            }
        }
        return false;
    }

    private CreativeWorldTeleporter() {
    }

    private static final class PortalWait {
        private final ResourceKey<Level> dimension;
        private final long startedTick;
        private long lastContactTick;
        private int lastShownSecond = -1;

        private PortalWait(ResourceKey<Level> dimension, long startedTick) {
            this.dimension = dimension;
            this.startedTick = startedTick;
            this.lastContactTick = startedTick;
        }
    }
}
