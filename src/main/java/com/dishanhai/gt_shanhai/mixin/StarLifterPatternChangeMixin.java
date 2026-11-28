package com.dishanhai.gt_shanhai.mixin;

import com.dishanhai.gt_shanhai.common.machine.starlifter.StarLifterControllerMachine;
import com.gregtechceu.gtceu.api.pattern.MultiblockState;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = MultiblockState.class, remap = false)
public abstract class StarLifterPatternChangeMixin {
    @Shadow @Final public Level world;
    @Shadow @Final public BlockPos controllerPos;

    @Inject(method = "onBlockStateChanged", at = @At("HEAD"), cancellable = true)
    private void shanhai$checkStarLifterCell(BlockPos pos, BlockState state, CallbackInfo callback) {
        if (pos.equals(controllerPos) || !(world instanceof ServerLevel level)) return;
        var structure = (MultiblockState) (Object) this;
        if (structure.getController() instanceof StarLifterControllerMachine machine && machine.isFormed()
                && machine.checkChangedPosition(level, pos)) {
            callback.cancel();
        }
    }
}
