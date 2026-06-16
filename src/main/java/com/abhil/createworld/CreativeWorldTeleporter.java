package com.abhil.createworld;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.ReadOnlyScoreInfo;
import net.minecraft.world.scores.ScoreAccess;
import net.minecraft.world.scores.ScoreHolder;
import net.minecraft.world.scores.Scoreboard;
import net.neoforged.neoforge.attachment.AttachmentHolder;
import net.neoforged.neoforge.attachment.AttachmentSync;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.IItemHandlerModifiable;
import net.neoforged.neoforge.items.wrapper.PlayerInvWrapper;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public final class CreativeWorldTeleporter {
    public static final ResourceKey<Level> CREATIVE_LEVEL = ResourceKey.create(
            Registries.DIMENSION,
            ResourceLocation.fromNamespaceAndPath(CreateWorld.MODID, "creative_world"));

    private static final String SESSION_TAG = CreateWorld.MODID + "_creative_session";
    private static final String SNAPSHOT_TAG = "Snapshot";
    private static final String CAPABILITY_ITEMS_TAG = "CapabilityItems";
    private static final String SCOREBOARD_SCORES_TAG = "ScoreboardScores";
    private static final String SCOREBOARD_TEAM_TAG = "ScoreboardTeam";
    private static final String CARRIED_STACK_TAG = "CarriedStack";
    private static final String ROOT_VEHICLE_TAG = "RootVehicle";
    private static final String PASSENGERS_TAG = "Passengers";
    private static final Field ATTACHMENTS_FIELD = findAttachmentsField();

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

        settleOpenMenuBeforeSnapshot(player);
        CompoundTag session = createSessionSnapshot(player, sourcePortalPos);
        player.getPersistentData().put(SESSION_TAG, session);
        try {
            prepareForCreativeEntry(player, session);
        } catch (IllegalStateException exception) {
            player.getPersistentData().remove(SESSION_TAG);
            player.displayClientMessage(Component.literal(exception.getMessage()), false);
            return false;
        }

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
        if (hasSession(player) || isInternalTransfer(player)) {
            return;
        }

        rejectUnauthorizedCreativeAccess(player, false);
    }

    public static void tick(ServerPlayer player) {
        clearStalePortalWait(player);
        enforceCreativeSession(player);
    }

    private static void enforceCreativeSession(ServerPlayer player) {
        if (!hasSession(player)) {
            if (isCreativeWorld(player.level()) && !isInternalTransfer(player)) {
                rejectUnauthorizedCreativeAccess(player, false);
            }
            return;
        }

        if (isCreativeWorld(player.level())) {
            if (player.gameMode.getGameModeForPlayer() != GameType.CREATIVE) {
                player.setGameMode(GameType.CREATIVE);
            }
            wipeSnapshotCapabilityItems(player);
        } else if (!isInternalTransfer(player)) {
            exit(player, false);
        }
    }

    public static void restore(ServerPlayer player) {
        if (!hasSession(player)) {
            return;
        }

        CompoundTag session = getSession(player);
        CompoundTag sessionCopy = session.copy();
        CompoundTag snapshot = sessionCopy.getCompound(SNAPSHOT_TAG).copy();

        wipeCreativeCarriedItems(player);
        clearTransientPersistentStateBeforeSnapshotLoad(player);
        player.load(snapshot);
        restoreCapabilityItems(player, sessionCopy.getList(CAPABILITY_ITEMS_TAG, Tag.TAG_COMPOUND));
        restoreCarriedStack(player, sessionCopy);

        GameType previousMode = GameType.byName(sessionCopy.getString("GameMode"), GameType.SURVIVAL);
        player.setGameMode(previousMode);
        restoreAbilities(player, snapshot);
        syncRecipeBook(player);
        if (sessionCopy.contains(SCOREBOARD_SCORES_TAG, Tag.TAG_LIST)) {
            restoreScoreboardScores(player, sessionCopy.getList(SCOREBOARD_SCORES_TAG, Tag.TAG_COMPOUND));
        }
        restoreScoreboardTeam(player, sessionCopy);

        player.getPersistentData().remove(SESSION_TAG);
        AttachmentSync.syncInitialPlayerAttachments(player);
        player.inventoryMenu.broadcastChanges();
        player.containerMenu.broadcastChanges();
    }

    public static void copySession(Player original, Player replacement) {
        if (original.getPersistentData().contains(SESSION_TAG, Tag.TAG_COMPOUND)) {
            replacement.getPersistentData().put(SESSION_TAG, original.getPersistentData().getCompound(SESSION_TAG).copy());
        }
    }

    public static void wipeBeforeCreativePlayerSave(ServerPlayer player) {
        if (hasSession(player) && isCreativeWorld(player.level())) {
            wipeCreativeCarriedItems(player);
        }
    }

    public static void handleCreativeDeath(ServerPlayer player) {
        if (!isCreativeWorld(player.level())) {
            return;
        }

        wipeCreativeCarriedItems(player);
        clearCreativeSessionState(player);
        player.setHealth(player.getMaxHealth());

        BlockPos spawn = creativeSpawn();
        INTERNAL_TRANSFERS.add(player.getUUID());
        try {
            player.teleportTo(player.serverLevel(), spawn.getX() + 0.5D, spawn.getY(), spawn.getZ() + 0.5D, Set.of(), player.getYRot(), player.getXRot());
        } finally {
            INTERNAL_TRANSFERS.remove(player.getUUID());
        }
    }

    public static void rejectUnauthorizedCreativeAccess(ServerPlayer player, boolean wipeInventory) {
        if (!isCreativeWorld(player.level())) {
            return;
        }

        if (wipeInventory) {
            wipeCreativeCarriedItems(player);
        } else {
            player.closeContainer();
            player.stopRiding();
        }

        ServerLevel targetLevel = player.server.overworld();
        BlockPos spawn = player.adjustSpawnLocation(targetLevel, targetLevel.getSharedSpawnPos());
        INTERNAL_TRANSFERS.add(player.getUUID());
        try {
            player.setGameMode(player.server.getDefaultGameType());
            player.teleportTo(targetLevel, spawn.getX() + 0.5D, spawn.getY(), spawn.getZ() + 0.5D, Set.of(), targetLevel.getSharedSpawnAngle(), 0.0F);
        } finally {
            INTERNAL_TRANSFERS.remove(player.getUUID());
        }

        player.displayClientMessage(Component.translatable("message.createworld.unauthorized_entry"), false);
    }

    public static void wipeCreativeCarriedItems(ServerPlayer player) {
        player.closeContainer();
        player.stopRiding();
        player.containerMenu.setCarried(ItemStack.EMPTY);
        player.inventoryMenu.setCarried(ItemStack.EMPTY);
        player.getInventory().clearContent();
        player.getEnderChestInventory().clearContent();
        wipeSnapshotCapabilityItems(player);
        clearCreativeSessionState(player);
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

    public static boolean canTravelToCreative(Player player) {
        return isInternalTransfer(player) || hasSession(player);
    }

    private static void clearStalePortalWait(ServerPlayer player) {
        PortalWait wait = PORTAL_WAITS.get(player.getUUID());
        if (wait != null && player.serverLevel().getGameTime() - wait.lastContactTick > 1) {
            PORTAL_WAITS.remove(player.getUUID());
        }
    }

    private static CompoundTag createSessionSnapshot(ServerPlayer player, BlockPos sourcePortalPos) {
        disconnectEntityLinks(player);
        CompoundTag session = new CompoundTag();
        CompoundTag snapshot = player.saveWithoutId(new CompoundTag());
        snapshot.remove(ROOT_VEHICLE_TAG);
        snapshot.remove(PASSENGERS_TAG);
        session.put(SNAPSHOT_TAG, snapshot);
        session.put(CAPABILITY_ITEMS_TAG, snapshotCapabilityItems(player));
        session.put(SCOREBOARD_SCORES_TAG, snapshotScoreboardScores(player));
        saveScoreboardTeam(player, session);
        saveCarriedStack(player, session);
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

    private static void settleOpenMenuBeforeSnapshot(ServerPlayer player) {
        disconnectEntityLinks(player);
        if (player.containerMenu != player.inventoryMenu || !player.containerMenu.getCarried().isEmpty()) {
            player.closeContainer();
            player.getInventory().setChanged();
            player.inventoryMenu.broadcastChanges();
            player.containerMenu.broadcastChanges();
        }
    }

    private static void saveCarriedStack(ServerPlayer player, CompoundTag session) {
        ItemStack carried = player.containerMenu.getCarried();
        if (carried.isEmpty()) {
            return;
        }

        Tag saved = carried.saveOptional(player.registryAccess());
        if (saved instanceof CompoundTag savedStack) {
            session.put(CARRIED_STACK_TAG, savedStack);
        }
    }

    private static void restoreCarriedStack(ServerPlayer player, CompoundTag session) {
        ItemStack carried = session.contains(CARRIED_STACK_TAG, Tag.TAG_COMPOUND)
                ? ItemStack.parseOptional(player.registryAccess(), session.getCompound(CARRIED_STACK_TAG))
                : ItemStack.EMPTY;
        player.inventoryMenu.setCarried(carried);
        if (player.containerMenu != player.inventoryMenu) {
            player.containerMenu.setCarried(carried.copy());
        }
    }

    private static void clearCreativeSessionState(ServerPlayer player) {
        disconnectEntityLinks(player);
        player.removeAllEffects();
        player.setAbsorptionAmount(0.0F);
        player.clearFire();
        player.setAirSupply(player.getMaxAirSupply());
        player.fallDistance = 0.0F;
        player.setDeltaMovement(0.0D, 0.0D, 0.0D);
        player.resetCurrentImpulseContext();
    }

    private static void restoreAbilities(ServerPlayer player, CompoundTag snapshot) {
        player.getAbilities().loadSaveData(snapshot);
        var movementSpeed = player.getAttribute(Attributes.MOVEMENT_SPEED);
        if (movementSpeed != null) {
            movementSpeed.setBaseValue(player.getAbilities().getWalkingSpeed());
        }
        player.onUpdateAbilities();
    }

    private static void syncRecipeBook(ServerPlayer player) {
        player.getRecipeBook().sendInitialRecipeBook(player);
    }

    private static void clearTransientPersistentStateBeforeSnapshotLoad(ServerPlayer player) {
        clearCompound(player.getPersistentData());
        player.getTags().clear();
        player.setCustomName(null);
        player.setCustomNameVisible(false);
        clearCompound(player.getShoulderEntityLeft());
        clearCompound(player.getShoulderEntityRight());
        player.setLastDeathLocation(Optional.empty());
        player.resetCurrentImpulseContext();
        clearDataAttachments(player);
    }

    private static void disconnectEntityLinks(ServerPlayer player) {
        player.ejectPassengers();
        player.stopRiding();
    }

    private static void clearCompound(CompoundTag tag) {
        for (String key : Set.copyOf(tag.getAllKeys())) {
            tag.remove(key);
        }
    }

    private static void clearDataAttachments(ServerPlayer player) {
        if (ATTACHMENTS_FIELD == null) {
            return;
        }

        try {
            Object attachments = ATTACHMENTS_FIELD.get(player);
            if (attachments instanceof Map<?, ?> map) {
                map.clear();
            } else if (attachments != null) {
                ATTACHMENTS_FIELD.set(player, null);
            }
        } catch (IllegalAccessException | RuntimeException exception) {
            CreateWorld.LOGGER.warn("Could not clear creative-session data attachments for {}", player.getGameProfile().getName(), exception);
        }
    }

    private static Field findAttachmentsField() {
        try {
            Field field = AttachmentHolder.class.getDeclaredField("attachments");
            field.setAccessible(true);
            return field;
        } catch (NoSuchFieldException | RuntimeException exception) {
            CreateWorld.LOGGER.warn("Could not access NeoForge attachment storage; creative-session attachment cleanup is limited", exception);
            return null;
        }
    }

    private static void prepareForCreativeEntry(ServerPlayer player, CompoundTag session) {
        if (hasUnsupportedNonVanillaItemHandler(player)) {
            throw new IllegalStateException("CreateWorld cannot safely clear a read-only modded player inventory exposed by another mod.");
        }
        player.closeContainer();
        player.containerMenu.setCarried(ItemStack.EMPTY);
        player.inventoryMenu.setCarried(ItemStack.EMPTY);
        player.getInventory().clearContent();
        player.getEnderChestInventory().clearContent();
        wipeSnapshotCapabilityItems(player);
        clearCreativeSessionState(player);
        player.getInventory().setChanged();
        player.getEnderChestInventory().setChanged();
        player.inventoryMenu.broadcastChanges();
        player.containerMenu.broadcastChanges();
    }

    private static ListTag snapshotCapabilityItems(ServerPlayer player) {
        ListTag list = new ListTag();
        IItemHandler handler = player.getCapability(Capabilities.ItemHandler.ENTITY);
        if (!(handler instanceof IItemHandlerModifiable) || !shouldSnapshotCapability(player, handler)) {
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
        }
        return list;
    }

    private static ListTag snapshotScoreboardScores(ServerPlayer player) {
        ListTag list = new ListTag();
        Scoreboard scoreboard = player.getScoreboard();
        ScoreHolder holder = ScoreHolder.forNameOnly(player.getScoreboardName());

        for (Objective objective : scoreboard.getObjectives()) {
            ReadOnlyScoreInfo score = scoreboard.getPlayerScoreInfo(holder, objective);
            if (score != null) {
                CompoundTag tag = new CompoundTag();
                tag.putString("Objective", objective.getName());
                tag.putInt("Value", score.value());
                tag.putBoolean("Locked", score.isLocked());
                list.add(tag);
            }
        }

        return list;
    }

    private static void restoreScoreboardScores(ServerPlayer player, ListTag savedScores) {
        Scoreboard scoreboard = player.getScoreboard();
        ScoreHolder holder = ScoreHolder.forNameOnly(player.getScoreboardName());
        Set<String> restoredObjectives = new HashSet<>();

        for (int index = 0; index < savedScores.size(); index++) {
            CompoundTag tag = savedScores.getCompound(index);
            Objective objective = scoreboard.getObjective(tag.getString("Objective"));
            if (objective == null) {
                continue;
            }

            ScoreAccess score = scoreboard.getOrCreatePlayerScore(holder, objective, true);
            score.set(tag.getInt("Value"));
            if (tag.getBoolean("Locked")) {
                score.lock();
            } else {
                score.unlock();
            }
            restoredObjectives.add(objective.getName());
        }

        for (Objective objective : scoreboard.getObjectives()) {
            if (!restoredObjectives.contains(objective.getName())
                    && scoreboard.getPlayerScoreInfo(holder, objective) != null) {
                scoreboard.resetSinglePlayerScore(holder, objective);
            }
        }
    }

    private static void saveScoreboardTeam(ServerPlayer player, CompoundTag session) {
        var team = player.getScoreboard().getPlayersTeam(player.getScoreboardName());
        if (team != null) {
            session.putString(SCOREBOARD_TEAM_TAG, team.getName());
        }
    }

    private static void restoreScoreboardTeam(ServerPlayer player, CompoundTag session) {
        Scoreboard scoreboard = player.getScoreboard();
        String playerName = player.getScoreboardName();
        scoreboard.removePlayerFromTeam(playerName);

        if (!session.contains(SCOREBOARD_TEAM_TAG, Tag.TAG_STRING)) {
            return;
        }

        var savedTeam = scoreboard.getPlayerTeam(session.getString(SCOREBOARD_TEAM_TAG));
        if (savedTeam != null) {
            scoreboard.addPlayerToTeam(playerName, savedTeam);
        }
    }

    private static void restoreCapabilityItems(ServerPlayer player, ListTag slots) {
        if (slots.isEmpty()) {
            return;
        }

        IItemHandler handler = player.getCapability(Capabilities.ItemHandler.ENTITY);
        if (!(handler instanceof IItemHandlerModifiable modifiable) || !shouldSnapshotCapability(player, handler)) {
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
        if (!(handler instanceof IItemHandlerModifiable modifiable) || !shouldSnapshotCapability(player, handler)) {
            return;
        }

        for (int slot = 0; slot < handler.getSlots(); slot++) {
            modifiable.setStackInSlot(slot, ItemStack.EMPTY);
        }
    }

    private static boolean hasUnsupportedNonVanillaItemHandler(ServerPlayer player) {
        IItemHandler handler = player.getCapability(Capabilities.ItemHandler.ENTITY);
        if (handler != null
                && !(handler instanceof IItemHandlerModifiable)
                && shouldSnapshotCapability(player, handler)
                && hasNonVanillaItems(player, handler)) {
            return true;
        }

        return !hasSupportedNonVanillaCombinedItemHandler(player) && hasNonVanillaAutomationItemHandler(player);
    }

    private static boolean hasSupportedNonVanillaCombinedItemHandler(ServerPlayer player) {
        IItemHandler handler = player.getCapability(Capabilities.ItemHandler.ENTITY);
        return handler instanceof IItemHandlerModifiable && shouldSnapshotCapability(player, handler);
    }

    private static boolean hasNonVanillaAutomationItemHandler(ServerPlayer player) {
        IItemHandler handler = player.getCapability(Capabilities.ItemHandler.ENTITY_AUTOMATION, null);
        if (handler != null && shouldSnapshotCapability(player, handler) && hasNonVanillaItems(player, handler)) {
            return true;
        }

        for (Direction direction : Direction.values()) {
            handler = player.getCapability(Capabilities.ItemHandler.ENTITY_AUTOMATION, direction);
            if (handler != null && shouldSnapshotCapability(player, handler) && hasNonVanillaItems(player, handler)) {
                return true;
            }
        }

        return false;
    }

    private static boolean shouldSnapshotCapability(ServerPlayer player, IItemHandler handler) {
        return !isVanillaInventoryMirror(player, handler);
    }

    private static boolean hasNonVanillaItems(ServerPlayer player, IItemHandler handler) {
        List<ItemStack> remainingVanillaStacks = new ArrayList<>();
        var inventory = player.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (!stack.isEmpty()) {
                remainingVanillaStacks.add(stack.copy());
            }
        }

        for (int slot = 0; slot < handler.getSlots(); slot++) {
            ItemStack stack = handler.getStackInSlot(slot);
            if (stack.isEmpty()) {
                continue;
            }

            if (!consumeMatchingVanillaStack(remainingVanillaStacks, stack)) {
                return true;
            }
        }

        return false;
    }

    private static boolean consumeMatchingVanillaStack(List<ItemStack> vanillaStacks, ItemStack stack) {
        for (int index = 0; index < vanillaStacks.size(); index++) {
            ItemStack vanillaStack = vanillaStacks.get(index);
            if (ItemStack.matches(vanillaStack, stack) && vanillaStack.getCount() >= stack.getCount()) {
                vanillaStack.shrink(stack.getCount());
                if (vanillaStack.isEmpty()) {
                    vanillaStacks.remove(index);
                }
                return true;
            }
        }

        return false;
    }

    private static boolean isVanillaInventoryMirror(ServerPlayer player, IItemHandler handler) {
        if (handler instanceof PlayerInvWrapper) {
            return true;
        }

        var inventory = player.getInventory();
        if (handler.getSlots() != inventory.getContainerSize()) {
            return false;
        }

        for (int slot = 0; slot < handler.getSlots(); slot++) {
            if (!ItemStack.matches(handler.getStackInSlot(slot), inventory.getItem(slot))) {
                return false;
            }
        }

        return true;
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

        for (int x = -3; x <= 3; x++) {
            for (int z = -2; z <= 7; z++) {
                level.setBlock(spawn.offset(x, -1, z), net.minecraft.world.level.block.Blocks.SMOOTH_STONE.defaultBlockState(), net.minecraft.world.level.block.Block.UPDATE_ALL);
                level.setBlock(spawn.offset(x, 0, z), net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), net.minecraft.world.level.block.Block.UPDATE_ALL);
                level.setBlock(spawn.offset(x, 1, z), net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), net.minecraft.world.level.block.Block.UPDATE_ALL);
                level.setBlock(spawn.offset(x, 2, z), net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), net.minecraft.world.level.block.Block.UPDATE_ALL);
            }
        }

        CreativePortalShape.buildReturnPortal(level, returnPortal);
    }

    private static boolean hasReturnPortal(ServerLevel level, BlockPos northWestInside) {
        for (int x = 0; x < 3; x++) {
            for (int z = 0; z < 3; z++) {
                if (level.getBlockState(northWestInside.offset(x, 0, z)).is(CreateWorld.CREATIVE_PORTAL.get())) {
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
