package com.dishanhai.gt_shanhai.mixin;

import com.gregtechceu.gtceu.api.machine.feature.multiblock.IMultiPart;
import com.gregtechceu.gtceu.api.machine.multiblock.MultiblockControllerMachine;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

/**
 * {@code onStructureFormed} 先把部件放進 {@code parts} 再逐個 {@code addedToController}，
 * {@code partPositions} 要等迴圈結束才寫。這段時間 {@code getParts()} 看見長度不一致，
 * 會 {@code parts.clear()} 再按舊座標重建，外層 {@code Iterator.next()} 隨即
 * {@code ConcurrentModificationException}。JEI 預覽和遊戲內成型都死在這裡。
 *
 * <p>客戶端同步是另一條路：{@code parts} 還是空的、座標已經到了，仍要走原本的重建。
 */
@Mixin(value = MultiblockControllerMachine.class, remap = false)
public abstract class StructureFormGetPartsMixin {

    @Shadow(remap = false)
    protected boolean isFormed;

    @Shadow(remap = false)
    private List<IMultiPart> parts;

    @Shadow(remap = false)
    private BlockPos[] partPositions;

    @Inject(method = "getParts", at = @At("HEAD"), cancellable = true, remap = false)
    private void shanhai$keepLivePartsWhileForming(CallbackInfoReturnable<List<IMultiPart>> cir) {
        if (!isFormed || partPositions == null || parts == null || parts.isEmpty()) {
            return;
        }
        if (partPositions.length != parts.size()) {
            cir.setReturnValue(parts);
        }
    }
}
