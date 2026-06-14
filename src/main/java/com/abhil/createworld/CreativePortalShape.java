package com.abhil.createworld;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Optional;

public final class CreativePortalShape {
    private static final int INNER_WIDTH = 2;
    private static final int INNER_HEIGHT = 3;

    private final ServerLevel level;
    private final BlockPos lowerLeftInside;
    private final Direction right;
    private final Direction.Axis axis;

    private CreativePortalShape(ServerLevel level, BlockPos lowerLeftInside, Direction right, Direction.Axis axis) {
        this.level = level;
        this.lowerLeftInside = lowerLeftInside;
        this.right = right;
        this.axis = axis;
    }

    public static boolean tryCreate(ServerLevel level, BlockPos inside) {
        Optional<CreativePortalShape> shape = find(level, inside);
        shape.ifPresent(CreativePortalShape::placePortal);
        return shape.isPresent();
    }

    public static Optional<CreativePortalShape> find(ServerLevel level, BlockPos inside) {
        return find(level, inside, Direction.EAST, Direction.Axis.X)
                .or(() -> find(level, inside, Direction.SOUTH, Direction.Axis.Z));
    }

    private static Optional<CreativePortalShape> find(ServerLevel level, BlockPos inside, Direction right, Direction.Axis axis) {
        for (int down = 0; down < INNER_HEIGHT; down++) {
            for (int left = 0; left < INNER_WIDTH; left++) {
                BlockPos lowerLeft = inside.relative(right, -left).below(down);
                CreativePortalShape shape = new CreativePortalShape(level, lowerLeft, right, axis);
                if (shape.isValid()) {
                    return Optional.of(shape);
                }
            }
        }
        return Optional.empty();
    }

    private boolean isValid() {
        for (int x = -1; x <= INNER_WIDTH; x++) {
            for (int y = -1; y <= INNER_HEIGHT; y++) {
                BlockPos pos = lowerLeftInside.relative(right, x).above(y);
                boolean frame = x == -1 || x == INNER_WIDTH || y == -1 || y == INNER_HEIGHT;
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
        BlockState portal = CreateWorld.CREATIVE_PORTAL.get().defaultBlockState().setValue(CreativePortalBlock.AXIS, axis);
        for (int x = 0; x < INNER_WIDTH; x++) {
            for (int y = 0; y < INNER_HEIGHT; y++) {
                level.setBlock(lowerLeftInside.relative(right, x).above(y), portal, Block.UPDATE_ALL);
            }
        }
    }

    public static void buildReturnPortal(ServerLevel level, BlockPos lowerLeftInside, Direction.Axis axis) {
        Direction right = axis == Direction.Axis.X ? Direction.EAST : Direction.SOUTH;

        for (int x = -1; x <= INNER_WIDTH; x++) {
            for (int y = -1; y <= INNER_HEIGHT; y++) {
                BlockPos pos = lowerLeftInside.relative(right, x).above(y);
                boolean frame = x == -1 || x == INNER_WIDTH || y == -1 || y == INNER_HEIGHT;
                if (frame) {
                    level.setBlock(pos, CreateWorld.CREATIVE_PORTAL_FRAME.get().defaultBlockState(), Block.UPDATE_ALL);
                } else {
                    level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                }
            }
        }

        new CreativePortalShape(level, lowerLeftInside, right, axis).placePortal();
    }
}
