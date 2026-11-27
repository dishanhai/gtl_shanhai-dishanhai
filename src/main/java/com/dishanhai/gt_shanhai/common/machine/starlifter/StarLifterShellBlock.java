package com.dishanhai.gt_shanhai.common.machine.starlifter;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/** An installed shell stays in the GT pattern while the animated mesh supplies collision. */
public final class StarLifterShellBlock extends Block {
    public StarLifterShellBlock(Properties properties) {
        super(properties.dynamicShape().noOcclusion());
    }

    @Override
    public VoxelShape getCollisionShape(BlockState state, BlockGetter world, BlockPos pos, CollisionContext context) {
        return isReplaced(world, pos) ? Shapes.empty() : Shapes.block();
    }

    @Override
    public VoxelShape getShape(BlockState state, BlockGetter world, BlockPos pos, CollisionContext context) {
        return getCollisionShape(state, world, pos, context);
    }

    private static boolean isReplaced(BlockGetter world, BlockPos pos) {
        Level level = world instanceof Level direct ? direct : world instanceof LevelChunk chunk ? chunk.getLevel() : null;
        return level != null && StarLifterWorld.isReplaced(level, pos);
    }

    @Override
    public boolean skipRendering(BlockState state, BlockState adjacent, Direction face) {
        return adjacent.getBlock() instanceof StarLifterShellBlock || super.skipRendering(state, adjacent, face);
    }
}
