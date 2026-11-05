package com.dishanhai.gt_shanhai.mixin;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.KeyCounter;
import com.dishanhai.gt_shanhai.integration.enhancedcore.EnhancedCoreCloudInputsAdapter;
import com.gtl.enhancedcore.common.cloud.CloudPatternBufferMachine;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import org.gtlcore.gtlcore.common.machine.multiblock.part.ae.MEPatternBufferPartMachine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

import java.util.Map;

/**
 * 山海：只調整雲端派單的輸入分帳，保留原本路由、樣板身份和交易驗證。
 * 在場材料進 intrinsicVirtual，原生 IvJob 持久化及取消流程不會把它退款成實料。
 */
@Pseudo
@Mixin(targets = "com.gtl.enhancedcore.common.recipe.iv.IvBuffers", remap = false)
public abstract class EnhancedCoreCloudInputsVirtualMixin {

    @WrapOperation(method = "push(Lorg/gtlcore/gtlcore/common/machine/multiblock/part/ae/MEPatternBufferPartMachine;Lappeng/api/crafting/IPatternDetails;[Lappeng/api/stacks/KeyCounter;)Z",
            at = @At(value = "INVOKE", target = "Lappeng/api/crafting/IPatternDetails;getInputs()[Lappeng/api/crafting/IPatternDetails$IInput;"),
            remap = false)
    private static IPatternDetails.IInput[] gtShanhai$consumedDispatchUnits(IPatternDetails receiver,
            Operation<IPatternDetails.IInput[]> original, MEPatternBufferPartMachine buffer,
            IPatternDetails details, KeyCounter[] holders) {
        IPatternDetails.IInput[] inputs = original.call(receiver);
        return buffer instanceof CloudPatternBufferMachine
                ? EnhancedCoreCloudInputsAdapter.withoutPresence(inputs) : inputs;
    }

    @ModifyExpressionValue(method = "push(Lorg/gtlcore/gtlcore/common/machine/multiblock/part/ae/MEPatternBufferPartMachine;Lappeng/api/crafting/IPatternDetails;[Lappeng/api/stacks/KeyCounter;)Z",
            at = @At(value = "INVOKE", target = "Lcom/gtl/enhancedcore/common/recipe/iv/IvPatternAdmission;circuits(Ljava/util/Map;Ljava/util/Map;Ljava/util/function/Predicate;)Ljava/util/Map;"),
            remap = false)
    private static Map<AEKey, Long> gtShanhai$rememberIntrinsicPresence(Map<AEKey, Long> intrinsic,
            MEPatternBufferPartMachine buffer, IPatternDetails details, KeyCounter[] holders) {
        return buffer instanceof CloudPatternBufferMachine
                ? EnhancedCoreCloudInputsAdapter.mergePresence(details, intrinsic) : intrinsic;
    }
}
