package com.dishanhai.gt_shanhai.common.item.terminal;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import com.gregtechceu.gtceu.api.machine.feature.multiblock.IMultiController;
import com.gregtechceu.gtceu.api.pattern.BlockPattern;
import com.gregtechceu.gtceu.api.pattern.TraceabilityPredicate;
import com.gregtechceu.gtceu.api.pattern.util.RelativeDirection;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

public final class ShanhaiStructurePatternAdapter {

    public record Slot(BlockPos pos, int layer, TraceabilityPredicate predicate) {}

    private record PatternShape(
            TraceabilityPredicate[][][] blockMatches,
            RelativeDirection[] structureDir,
            int[][] aisleRepetitions,
            int[] centerOffset) {}

    private ShanhaiStructurePatternAdapter() {}

    public static List<Slot> map(IMultiController controller, int repeatCount, boolean mirrored) {
        PatternShape shape = readShape(controller.getPattern());
        BlockPos center = controller.self().getPos();
        Direction facing = controller.self().getFrontFacing();
        Direction upwards = controller.self().getUpwardsFacing();
        List<Slot> slots = new ArrayList<>();
        int z = -shape.centerOffset()[4];

        for (int aisle = 0; aisle < shape.blockMatches().length; aisle++) {
            int min = shape.aisleRepetitions()[aisle][0];
            int max = shape.aisleRepetitions()[aisle][1];
            int repeats = min == max ? min : Math.max(min, Math.min(max, repeatCount));
            for (int repeat = 0; repeat < repeats; repeat++) {
                int y = -shape.centerOffset()[1];
                for (int row = 0; row < shape.blockMatches()[aisle].length; row++, y++) {
                    int x = -shape.centerOffset()[0];
                    for (int column = 0; column < shape.blockMatches()[aisle][row].length; column++, x++) {
                        TraceabilityPredicate predicate = shape.blockMatches()[aisle][row][column];
                        if (predicate == null || predicate.isAny()) continue;
                        BlockPos offset = setActualRelativeOffset(
                                x, y, z, facing, upwards, mirrored, shape.structureDir());
                        slots.add(new Slot(center.offset(offset), z, predicate));
                    }
                }
                z++;
            }
        }
        return slots;
    }

    private static PatternShape readShape(BlockPattern pattern) {
        try {
            Field blockMatches = BlockPattern.class.getDeclaredField("blockMatches");
            Field structureDir = BlockPattern.class.getDeclaredField("structureDir");
            Field aisleRepetitions = BlockPattern.class.getDeclaredField("aisleRepetitions");
            Field centerOffset = BlockPattern.class.getDeclaredField("centerOffset");
            blockMatches.setAccessible(true);
            structureDir.setAccessible(true);
            aisleRepetitions.setAccessible(true);
            centerOffset.setAccessible(true);
            return new PatternShape(
                    (TraceabilityPredicate[][][]) blockMatches.get(pattern),
                    (RelativeDirection[]) structureDir.get(pattern),
                    (int[][]) aisleRepetitions.get(pattern),
                    (int[]) centerOffset.get(pattern));
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("无法读取 GTCEu 多方块结构定义", e);
        }
    }

    private static BlockPos setActualRelativeOffset(int x, int y, int z, Direction facing,
                                                     Direction upwardsFacing, boolean mirrored,
                                                     RelativeDirection[] structureDir) {
        int[] input = { x, y, z };
        int[] output = new int[3];
        if (facing == Direction.UP || facing == Direction.DOWN) {
            Direction origin = facing == Direction.DOWN ? upwardsFacing : upwardsFacing.getOpposite();
            for (int i = 0; i < 3; i++) applyDirection(output, i, input[i], structureDir[i].getActualFacing(origin));
            int xOffset = upwardsFacing.getStepX();
            int zOffset = upwardsFacing.getStepZ();
            if (xOffset == 0) {
                int oldZ = output[2];
                output[2] = zOffset > 0 ? output[1] : -output[1];
                output[1] = zOffset > 0 ? -oldZ : oldZ;
            } else {
                int oldX = output[0];
                output[0] = xOffset > 0 ? output[1] : -output[1];
                output[1] = xOffset > 0 ? -oldX : oldX;
            }
            if (mirrored) {
                if (upwardsFacing == Direction.NORTH || upwardsFacing == Direction.SOUTH) output[0] = -output[0];
                else output[2] = -output[2];
            }
        } else {
            for (int i = 0; i < 3; i++) applyDirection(output, i, input[i], structureDir[i].getActualFacing(facing));
            if (upwardsFacing == Direction.WEST || upwardsFacing == Direction.EAST) {
                Direction side = upwardsFacing == Direction.EAST
                        ? facing.getClockWise() : facing.getClockWise().getOpposite();
                int xOffset = side.getStepX();
                int zOffset = side.getStepZ();
                if (xOffset == 0) {
                    int oldZ = output[2];
                    output[2] = zOffset > 0 ? -output[1] : output[1];
                    output[1] = zOffset > 0 ? oldZ : -oldZ;
                } else {
                    int oldX = output[0];
                    output[0] = xOffset > 0 ? -output[1] : output[1];
                    output[1] = xOffset > 0 ? oldX : -oldX;
                }
            } else if (upwardsFacing == Direction.SOUTH) {
                output[1] = -output[1];
                if (facing.getStepX() == 0) output[0] = -output[0];
                else output[2] = -output[2];
            }
            if (mirrored) {
                if (upwardsFacing == Direction.NORTH || upwardsFacing == Direction.SOUTH) {
                    if (facing == Direction.NORTH || facing == Direction.SOUTH) output[0] = -output[0];
                    else output[2] = -output[2];
                } else {
                    output[1] = -output[1];
                }
            }
        }
        return new BlockPos(output[0], output[1], output[2]);
    }

    private static void applyDirection(int[] output, int index, int value, Direction direction) {
        switch (direction) {
            case UP -> output[1] = value;
            case DOWN -> output[1] = -value;
            case WEST -> output[0] = -value;
            case EAST -> output[0] = value;
            case NORTH -> output[2] = -value;
            case SOUTH -> output[2] = value;
        }
    }
}
