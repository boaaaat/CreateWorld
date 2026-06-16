package com.abhil.createworld;

import net.neoforged.neoforge.common.ModConfigSpec;

import java.util.List;

public final class CreateWorldConfig {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    public static final ModConfigSpec.IntValue CREATIVE_SPAWN_X = BUILDER
            .comment("X coordinate players arrive at in the creative testing dimension.")
            .defineInRange("creativeSpawnX", 0, -30_000_000, 30_000_000);

    public static final ModConfigSpec.IntValue CREATIVE_SPAWN_Y = BUILDER
            .comment("Y coordinate players arrive at in the creative testing dimension.")
            .defineInRange("creativeSpawnY", 80, -64, 320);

    public static final ModConfigSpec.IntValue CREATIVE_SPAWN_Z = BUILDER
            .comment("Z coordinate players arrive at in the creative testing dimension.")
            .defineInRange("creativeSpawnZ", 0, -30_000_000, 30_000_000);

    public static final ModConfigSpec.IntValue PORTAL_COOLDOWN_TICKS = BUILDER
            .comment("Delay before the same player can use a CreateWorld portal again.")
            .defineInRange("portalCooldownTicks", 100, 20, 20 * 60);

    public static final ModConfigSpec.IntValue PORTAL_WARMUP_TICKS = BUILDER
            .comment("How long a player must stand continuously in a CreateWorld portal before teleporting.")
            .defineInRange("portalWarmupTicks", 100, 20, 20 * 60);

    public static final ModConfigSpec.BooleanValue BLOCK_CREATIVE_STORAGE_INTERACTION = BUILDER
            .comment("Prevent players in the creative dimension from opening or using storage, fluid, and energy blocks. This is the safest default for mixed modpacks because many mods expose cross-dimensional storage through normal block interactions.")
            .define("blockCreativeStorageInteraction", true);

    public static final ModConfigSpec.BooleanValue BLOCK_CREATIVE_ITEM_PAYLOADS = BUILDER
            .comment("Prevent use of creative-side item stacks that carry stored contents, block entity data, entity data, loot-table containers, bundle contents, charged projectiles, or recipe unlock payloads.")
            .define("blockCreativeItemPayloads", true);

    public static final ModConfigSpec.BooleanValue BLOCK_CREATIVE_COMMANDS = BUILDER
            .comment("Prevent non-operator players from running commands while in the creative dimension, except /createworld return. This blocks command-based warps and mod command rewards.")
            .define("blockCreativeCommands", true);

    public static final ModConfigSpec.BooleanValue DELETE_CREATIVE_ITEM_DROPS = BUILDER
            .comment("Delete item entities created in the creative dimension instead of allowing them to remain in the world.")
            .define("deleteCreativeItemDrops", true);

    public static final ModConfigSpec.BooleanValue BLOCK_CREATIVE_PROGRESS = BUILDER
            .comment("Prevent XP, advancement criteria, and stat progress earned in the creative dimension from persisting into survival or triggering modded reward systems.")
            .define("blockCreativeProgress", true);

    public static final ModConfigSpec.BooleanValue BLOCK_RISKY_ID_PATTERNS = BUILDER
            .comment("Also block interactions with blocks/items/entities whose registry ids contain a known high-risk storage, teleport, grave, or network word.")
            .define("blockRiskyIdPatterns", true);

    public static final ModConfigSpec.ConfigValue<List<? extends String>> RISKY_ID_PATTERNS = BUILDER
            .comment("Lowercase registry-id fragments treated as risky in the creative dimension. Server packs can add mod-specific names here.")
            .defineListAllowEmpty("riskyIdPatterns", List.of(
                    "waystone",
                    "warp",
                    "teleport",
                    "teleporter",
                    "portal",
                    "ender_chest",
                    "enderchest",
                    "ender_tank",
                    "endertank",
                    "ender_storage",
                    "enderstorage",
                    "dimensional",
                    "quantum",
                    "network",
                    "terminal",
                    "grid",
                    "wireless",
                    "backpack",
                    "satchel",
                    "pouch",
                    "wallet",
                    "crate",
                    "drawer",
                    "tank",
                    "pipe",
                    "duct",
                    "cable",
                    "conduit",
                    "drive",
                    "disk",
                    "storage_bus",
                    "importer",
                    "exporter",
                    "collector",
                    "magnet",
                    "grave",
                    "corpse",
                    "tomb",
                    "mailbox",
                    "chunk_loader",
                    "chunkloader",
                    "entangled"), () -> "", value -> value instanceof String);

    static final ModConfigSpec SPEC = BUILDER.build();

    private CreateWorldConfig() {
    }
}
