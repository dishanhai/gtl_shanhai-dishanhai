package com.dishanhai.gt_shanhai.common.machine.starlifter;

import com.dishanhai.gt_shanhai.common.block.DShanhaiBlocks;
import com.gregtechceu.gtceu.api.machine.MultiblockMachineDefinition;
import com.gregtechceu.gtceu.api.pattern.BlockPattern;
import com.gregtechceu.gtceu.api.pattern.Predicates;
import com.gregtechceu.gtceu.api.pattern.util.RelativeDirection;
import com.gtladd.gtladditions.common.machine.multiblock.structure.StructureResourceLoader;
import it.unimi.dsi.fastutil.longs.LongConsumer;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

final class StarLifterStructure {
    static final String ID = "star_lifter";
    static final String RESOURCE = "multiblock/star_lifter.bin";
    private static final Direction REST_FRONT = Direction.EAST;

    private StarLifterStructure() {}

    static BlockPattern pattern(MultiblockMachineDefinition definition) {
        var builder = StructureResourceLoader.INSTANCE.loadFactoryPattern(RESOURCE, ID,
                RelativeDirection.FRONT, RelativeDirection.UP, RelativeDirection.RIGHT);
        var materials = StarLifterGeometry.Surface.values();
        for (int i = 0; i < materials.length; i++) {
            char symbol = (char) ('B' + i);
            builder.where(symbol, Predicates.blocks(expectedBlock(symbol)));
        }
        return builder.where('J', Predicates.blocks(expectedBlock('J')))
                .where('K', Predicates.blocks(expectedBlock('K')))
                .where('~', Predicates.controller(Predicates.blocks(definition.getBlock())))
                .where('.', Predicates.air())
                .where(' ', Predicates.any())
                .build();
    }

    static float heading(Direction front) {
        return Mth.wrapDegrees(REST_FRONT.toYRot() - front.toYRot());
    }

    static Vec3 center(BlockPos controller, Direction front) {
        var offset = Layout.CENTER.subtract(Layout.CONTROLLER);
        var right = front.getClockWise();
        BlockPos world = controller.offset(
                offset.getX() * front.getStepX() + offset.getZ() * right.getStepX(),
                offset.getY(),
                offset.getX() * front.getStepZ() + offset.getZ() * right.getStepZ());
        return Vec3.atCenterOf(world);
    }

    static boolean replaces(StarLifterInstallation installation, BlockPos pos) {
        if (!installation.isController() || !installation.bounds().contains(
                pos.getX() + .5, pos.getY() + .5, pos.getZ() + .5)) return false;
        return isMovable(symbolAt(installation.centerBlock(), Layout.CENTER, front(installation), pos));
    }

    static boolean matches(BlockPos controller, Direction front, BlockPos pos, BlockState state) {
        char symbol = symbolAt(controller, Layout.CONTROLLER, front, pos);
        return switch (symbol) {
            case ' ' -> true;
            case '.' -> state.isAir();
            default -> state.is(expectedBlock(symbol));
        };
    }

    static void forEachShellSection(StarLifterInstallation installation, LongConsumer visitor) {
        var sections = new LongOpenHashSet();
        var front = front(installation);
        var right = front.getClockWise();
        var center = installation.centerBlock();
        for (var run : Layout.SHELL_RUNS) {
            int x = center.getX() + (run.z - Layout.CENTER.getZ()) * right.getStepX();
            int z = center.getZ() + (run.z - Layout.CENTER.getZ()) * right.getStepZ();
            int y = SectionPos.blockToSectionCoord(center.getY() + run.y - Layout.CENTER.getY());
            int from = run.from - Layout.CENTER.getX();
            int to = run.to - Layout.CENTER.getX();
            if (front.getAxis() == Direction.Axis.X) {
                int first = SectionPos.blockToSectionCoord(x + from * front.getStepX());
                int last = SectionPos.blockToSectionCoord(x + to * front.getStepX());
                for (int sx = Math.min(first, last); sx <= Math.max(first, last); sx++) {
                    sections.add(SectionPos.asLong(sx, y, SectionPos.blockToSectionCoord(z)));
                }
            } else {
                int first = SectionPos.blockToSectionCoord(z + from * front.getStepZ());
                int last = SectionPos.blockToSectionCoord(z + to * front.getStepZ());
                for (int sz = Math.min(first, last); sz <= Math.max(first, last); sz++) {
                    sections.add(SectionPos.asLong(SectionPos.blockToSectionCoord(x), y, sz));
                }
            }
        }
        sections.forEach(visitor);
    }

    private static Direction front(StarLifterInstallation installation) {
        return switch ((int) installation.heading()) {
            case 90 -> Direction.NORTH;
            case -90 -> Direction.SOUTH;
            case -180 -> Direction.WEST;
            default -> REST_FRONT;
        };
    }

    private static char symbolAt(BlockPos origin, BlockPos anchor, Direction front, BlockPos pos) {
        int dx = pos.getX() - origin.getX();
        int dz = pos.getZ() - origin.getZ();
        var right = front.getClockWise();
        int x = anchor.getX() + dx * front.getStepX() + dz * front.getStepZ();
        int y = anchor.getY() + pos.getY() - origin.getY();
        int z = anchor.getZ() + dx * right.getStepX() + dz * right.getStepZ();
        if (z < 0 || z >= Layout.AISLES.length || y < 0 || y >= Layout.AISLES[z].length) return ' ';
        String row = Layout.AISLES[z][y];
        return x >= 0 && x < row.length() ? row.charAt(x) : ' ';
    }

    private static boolean isMovable(char symbol) {
        return symbol >= 'B' && symbol <= 'I';
    }

    private static Block expectedBlock(char symbol) {
        if (symbol == 'J') return DShanhaiBlocks.STAR_LIFTER_TERMINAL.get();
        if (symbol == 'K') return DShanhaiBlocks.STAR_LIFTER_INTERFACE.get();
        return DShanhaiBlocks.STAR_LIFTER_SHELL.get(StarLifterGeometry.Surface.values()[symbol - 'B']).get();
    }

    private record Run(int z, int y, int from, int to) {}

    private static final class Layout {
        private static final String[][] AISLES = StructureResourceLoader.INSTANCE.loadAisles$gtladditions(RESOURCE, ID);
        private static final BlockPos CONTROLLER;
        private static final BlockPos CENTER;
        private static final List<Run> SHELL_RUNS;

        static {
            BlockPos controller = null;
            int minX = Integer.MAX_VALUE, minY = minX, minZ = minX;
            int maxX = Integer.MIN_VALUE, maxY = maxX, maxZ = maxX;
            var runs = new ArrayList<Run>();
            for (int z = 0; z < AISLES.length; z++) {
                for (int y = 0; y < AISLES[z].length; y++) {
                    String row = AISLES[z][y];
                    for (int i = 0; i < row.length();) {
                        if (!isMovable(row.charAt(i))) {
                            i++;
                            continue;
                        }
                        int from = i++;
                        while (i < row.length() && isMovable(row.charAt(i))) i++;
                        runs.add(new Run(z, y, from, i - 1));
                    }
                    int x = row.indexOf('~');
                    if (x >= 0) controller = new BlockPos(x, y, z);
                    int star = row.indexOf('I');
                    if (star < 0) continue;
                    minX = Math.min(minX, star);
                    maxX = Math.max(maxX, row.lastIndexOf('I'));
                    minY = Math.min(minY, y);
                    maxY = Math.max(maxY, y);
                    minZ = Math.min(minZ, z);
                    maxZ = Math.max(maxZ, z);
                }
            }
            if (controller == null || minX > maxX) {
                throw new IllegalStateException("Star Lifter structure is missing the controller or photosphere");
            }
            CONTROLLER = controller;
            CENTER = new BlockPos((minX + maxX) / 2, (minY + maxY) / 2, (minZ + maxZ) / 2);
            SHELL_RUNS = List.copyOf(runs);
        }
    }
}
