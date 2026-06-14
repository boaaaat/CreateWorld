package com.abhil.createworld;

import net.neoforged.neoforge.common.ModConfigSpec;

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

    static final ModConfigSpec SPEC = BUILDER.build();

    private CreateWorldConfig() {
    }
}
