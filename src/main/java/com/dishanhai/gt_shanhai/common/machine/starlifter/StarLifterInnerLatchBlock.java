package com.dishanhai.gt_shanhai.common.machine.starlifter;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

public final class StarLifterInnerLatchBlock extends Block {
    public static final double HALF_WIDTH = 5.0 / 16;
    private static final VoxelShape LATCH = Block.box(3, 0, 3, 13, 16, 13);

    public StarLifterInnerLatchBlock(Properties properties) {
        super(properties.noOcclusion());
    }

    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return LATCH;
    }
}
