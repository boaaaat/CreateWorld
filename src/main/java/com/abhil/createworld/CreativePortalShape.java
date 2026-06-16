package com.abhil.createworld;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Optional;

public final class CreativePortalShape {
    private static final int INNER_SIZE = 3;

    private final ServerLevel level;
    private final BlockPos northWestInside;

    private CreativePortalShape(ServerLevel level, BlockPos northWestInside) {
        this.level = level;
        this.northWestInside = northWestInside;
    }

    public static boolean tryCreate(ServerLevel level, BlockPos candidate) {
        Optional<CreativePortalShape> shape = find(level, candidate);
        shape.ifPresent(CreativePortalShape::placePortal);
        return shape.isPresent();
    }

    public static Optional<CreativePortalShape> find(ServerLevel level, BlockPos candidate) {
        for (int yOffset = -1; yOffset <= 1; yOffset++) {
            for (int relativeX = -1; relativeX <= INNER_SIZE; relativeX++) {
                for (int relativeZ = -1; relativeZ <= INNER_SIZE; relativeZ++) {
                    BlockPos northWestInside = new BlockPos(
                            candidate.getX() - relativeX,
                            candidate.getY() + yOffset,
                            candidate.getZ() - relativeZ);
                    CreativePortalShape shape = new CreativePortalShape(level, northWestInside);
                    if (shape.isValid()) {
                        return Optional.of(shape);
                    }
                }
            }
        }
        return Optional.empty();
    }

    private boolean isValid() {
        for (int x = -1; x <= INNER_SIZE; x++) {
            for (int z = -1; z <= INNER_SIZE; z++) {
                BlockPos pos = northWestInside.offset(x, 0, z);
                boolean frame = x == -1 || x == INNER_SIZE || z == -1 || z == INNER_SIZE;
                BlockState state = level.getBlockState(pos);

                if (frame) {
                    if (!state.is(CreateWorld.CREATIVE_PORTAL_FRAME.get())) {
                        return false;
                    }
                } else if (!canReplaceInterior(state)) {
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean canReplaceInterior(BlockState state) {
        return state.isAir() || state.is(CreateWorld.CREATIVE_PORTAL.get()) || state.canBeReplaced();
    }

    private void placePortal() {
        BlockState portal = CreateWorld.CREATIVE_PORTAL.get()
                .defaultBlockState()
                .setValue(CreativePortalBlock.AXIS, Direction.Axis.X);
        for (int x = 0; x < INNER_SIZE; x++) {
            for (int z = 0; z < INNER_SIZE; z++) {
                level.setBlock(northWestInside.offset(x, 0, z), portal, Block.UPDATE_ALL);
            }
        }
    }

    public static void buildReturnPortal(ServerLevel level, BlockPos northWestInside) {
        for (int x = -1; x <= INNER_SIZE; x++) {
            for (int z = -1; z <= INNER_SIZE; z++) {
                BlockPos pos = northWestInside.offset(x, 0, z);
                boolean frame = x == -1 || x == INNER_SIZE || z == -1 || z == INNER_SIZE;
                if (frame) {
                    level.setBlock(pos, CreateWorld.CREATIVE_PORTAL_FRAME.get().defaultBlockState(), Block.UPDATE_ALL);
                } else {
                    level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                }
            }
        }

        new CreativePortalShape(level, northWestInside).placePortal();
    }
}
