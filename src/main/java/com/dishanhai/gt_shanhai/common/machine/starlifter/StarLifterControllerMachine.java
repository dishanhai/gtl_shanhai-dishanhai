package com.dishanhai.gt_shanhai.common.machine.starlifter;

import com.dishanhai.gt_shanhai.GTDishanhaiRegistration;
import com.dishanhai.gt_shanhai.common.block.DShanhaiBlocks;
import com.gregtechceu.gtceu.api.GTValues;
import com.gregtechceu.gtceu.api.data.RotationState;
import com.gregtechceu.gtceu.api.machine.IMachineBlockEntity;
import com.gregtechceu.gtceu.api.machine.MultiblockMachineDefinition;
import com.gregtechceu.gtceu.api.machine.multiblock.MultiblockControllerMachine;
import com.gregtechceu.gtceu.api.pattern.MultiblockWorldSavedData;
import com.gregtechceu.gtceu.common.data.GTRecipeTypes;
import com.lowdragmc.lowdraglib.syncdata.annotation.Persisted;
import com.lowdragmc.lowdraglib.syncdata.field.ManagedFieldHolder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.BlockHitResult;

public final class StarLifterControllerMachine extends MultiblockControllerMachine {
    private static final ManagedFieldHolder FIELDS = new ManagedFieldHolder(
            StarLifterControllerMachine.class, MANAGED_FIELD_HOLDER);
    @Persisted private boolean showEnabled = true;
    @Persisted private long startTick = -1;
    private volatile BlockPos failedPosition;

    public StarLifterControllerMachine(IMachineBlockEntity holder) {
        super(holder);
    }

    @Override
    public ManagedFieldHolder getFieldHolder() {
        return FIELDS;
    }

    @Override
    public void onStructureFormed() {
        super.onStructureFormed();
        failedPosition = null;
        activate();
    }

    @Override
    public void onStructureInvalid() {
        super.onStructureInvalid();
        failedPosition = null;
        startTick = -1;
        deactivate();
    }

    /** GT already matched every other position when this dependency was indexed. */
    public void checkChangedPosition(ServerLevel level, BlockPos pos) {
        if (StarLifterStructure.matches(getPos(), getFrontFacing(), pos, level.getBlockState(pos))) return;
        onStructureInvalid();
        failedPosition = pos.immutable();
        var structures = MultiblockWorldSavedData.getOrCreate(level);
        structures.removeMapping(getMultiblockState());
        structures.addAsyncLogic(this);
    }

    @Override
    public void asyncCheckPattern(long periodId) {
        BlockPos failed = failedPosition;
        if (failed != null && getLevel() instanceof ServerLevel level) {
            var chunk = level.getChunkSource().getChunkForLighting(SectionPos.blockToSectionCoord(failed.getX()),
                    SectionPos.blockToSectionCoord(failed.getZ()));
            if (chunk == null || !StarLifterStructure.matches(getPos(), getFrontFacing(), failed,
                    chunk.getBlockState(failed))) return;
        }
        super.asyncCheckPattern(periodId);
    }

    @Override
    public void onPartUnload() {
        super.onPartUnload();
        deactivate();
    }

    @Override
    public void onUnload() {
        deactivate();
        super.onUnload();
    }

    @Override
    protected InteractionResult onSoftMalletClick(Player player, InteractionHand hand, Direction side, BlockHitResult hit) {
        if (!isRemote()) {
            showEnabled = !showEnabled;
            if (showEnabled) {
                activate();
            } else {
                startTick = -1;
                deactivate();
            }
            markDirty();
            player.displayClientMessage(Component.translatable("gt_shanhai.multiblock.star_lifter."
                    + (showEnabled ? "enabled" : "disabled")), true);
        }
        return InteractionResult.sidedSuccess(isRemote());
    }

    private void activate() {
        if (!(getLevel() instanceof ServerLevel level) || !showEnabled || !isFormed() || isInValid()
                || getMultiblockState().hasError()) return;
        if (startTick < 0) {
            startTick = level.getGameTime();
            markDirty();
        }
        var front = getFrontFacing();
        StarLifterWorld.activateController(level, getPos(), StarLifterStructure.center(getPos(), front),
                StarLifterStructure.heading(front), startTick);
    }

    private void deactivate() {
        if (getLevel() instanceof ServerLevel level) StarLifterWorld.deactivateController(level, getPos());
    }

    public static MultiblockMachineDefinition register() {
        return GTDishanhaiRegistration.REGISTRATE.multiblock(StarLifterStructure.ID, StarLifterControllerMachine::new)
                .rotationState(RotationState.NON_Y_AXIS)
                .allowExtendedFacing(false)
                .allowFlip(false)
                .tier(GTValues.UEV)
                .recipeTypes(GTRecipeTypes.DUMMY_RECIPES)
                .pattern(StarLifterStructure::pattern)
                .appearanceBlock(() -> DShanhaiBlocks.STAR_LIFTER_TERMINAL.get())
                .workableCasingRenderer(new ResourceLocation("gt_shanhai", "block/star_lifter/socket"),
                        new ResourceLocation("gtceu", "block/multiblock/fusion_reactor"))
                .tooltips(Component.translatable("gt_shanhai.multiblock.star_lifter.tooltip.0"),
                        Component.translatable("gt_shanhai.multiblock.star_lifter.tooltip.1"))
                .register();
    }
}
