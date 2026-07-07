package com.dishanhai.gt_shanhai.common.ae2.quantum;

import appeng.block.AEBaseBlock;
import appeng.block.AEBaseEntityBlock;
import appeng.me.cluster.MBCalculator;
import appeng.menu.MenuOpener;
import appeng.menu.locator.MenuLocators;
import appeng.menu.me.crafting.CraftingCPUMenu;
import appeng.util.InteractionUtil;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.phys.BlockHitResult;

public class QuantumCraftingUnitBlock extends AEBaseEntityBlock<QuantumCraftingBlockEntity> {

    public static final BooleanProperty FORMED = BooleanProperty.create("formed");
    public static final BooleanProperty POWERED = BooleanProperty.create("powered");
    public static final BooleanProperty MULTIBLOCKED = BooleanProperty.create("multiblocked");
    public static final IntegerProperty LIGHT_LEVEL = IntegerProperty.create("light_level", 0, 15);

    private final QuantumCraftingUnitType type;

    public QuantumCraftingUnitBlock(QuantumCraftingUnitType type) {
        super(getProps(type));
        this.type = type;
        registerDefaultState(defaultBlockState()
                .setValue(FORMED, false)
                .setValue(POWERED, false)
                .setValue(MULTIBLOCKED, false)
                .setValue(LIGHT_LEVEL, 0));
    }

    private static BlockBehaviour.Properties getProps(QuantumCraftingUnitType type) {
        BlockBehaviour.Properties props = type == QuantumCraftingUnitTypes.STRUCTURE
                ? AEBaseBlock.glassProps()
                : AEBaseBlock.metalProps();
        if (type == QuantumCraftingUnitTypes.COMPUTER_UNIT || type == QuantumCraftingUnitTypes.STRUCTURE) {
            props.lightLevel(state -> state.getValue(LIGHT_LEVEL));
            props.noOcclusion();
        }
        return props;
    }

    public QuantumCraftingUnitType getQuantumType() {
        return type;
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return getBlockEntityType().create(pos, state);
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        super.createBlockStateDefinition(builder);
        builder.add(new Property[]{POWERED, FORMED, MULTIBLOCKED, LIGHT_LEVEL});
    }

    @Override
    public BlockState updateShape(BlockState state, Direction facing, BlockState facingState, LevelAccessor level,
            BlockPos currentPos, BlockPos facingPos) {
        BlockEntity blockEntity = level.getBlockEntity(currentPos);
        if (blockEntity != null) {
            blockEntity.requestModelDataUpdate();
        }
        return super.updateShape(state, facing, facingState, level, currentPos, facingPos);
    }

    @Override
    public void neighborChanged(BlockState state, Level level, BlockPos pos, Block blockIn, BlockPos fromPos,
            boolean isMoving) {
        QuantumDiagnostics.hit("block.neighborChanged.raw",
                "pos=" + pos.toShortString() + " from=" + fromPos.toShortString()
                        + " client=" + level.isClientSide());
        if (level.isClientSide() || MBCalculator.isModificationInProgress()) {
            return;
        }
        QuantumCraftingBlockEntity blockEntity = getBlockEntity(level, pos);
        if (blockEntity != null) {
            blockEntity.updateMultiBlock(fromPos);
        }
    }

    @Override
    public void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean isMoving) {
        if (newState.getBlock() == state.getBlock()) {
            return;
        }
        QuantumCraftingBlockEntity blockEntity = getBlockEntity(level, pos);
        if (blockEntity != null) {
            blockEntity.breakCluster();
        }
        super.onRemove(state, level, pos, newState, isMoving);
    }

    @Override
    public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand,
            BlockHitResult hit) {
        QuantumCraftingBlockEntity blockEntity = getBlockEntity(level, pos);
        if (blockEntity != null && !InteractionUtil.isInAlternateUseMode(player) && blockEntity.isFormed()
                && blockEntity.isActive()) {
            if (!level.isClientSide()) {
                MenuOpener.open(CraftingCPUMenu.TYPE, player,
                        MenuLocators.forBlockEntity(blockEntity));
            }
            return InteractionResult.sidedSuccess(level.isClientSide());
        }
        return super.use(state, level, pos, player, hand, hit);
    }
}
