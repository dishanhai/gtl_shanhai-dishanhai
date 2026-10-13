package com.dishanhai.gt_shanhai.mixin;

import com.gregtechceu.gtceu.api.machine.feature.multiblock.IMultiController;
import com.gregtechceu.gtceu.api.pattern.MultiblockState;
import com.gtladd.gtladditions.common.machine.multiblock.controller.ArcanicAstrograph;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.registries.ForgeRegistries;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 奥术星图的外壳位只放行一个物品输入仓。终焉聚合枢纽同时登记了输入、输出、
 * 维护和并行，结构检查会把它算进这个唯一名额。GTLCore 随后又因
 * {@code IParallelHatch} 提前返回，不登记枢纽上的配方仓。原有输入仓要么
 * 超限导致结构散掉，要么被枢纽顶掉后没有任何物品仓，配方就不再运行。
 * 枢纽在这台机器上不占用有数量上限的仓室名额，仍可由无上限的能力谓词放进结构。
 */
@Mixin(value = com.gregtechceu.gtceu.api.pattern.predicates.SimplePredicate.class, remap = false)
public class ArcanicAstrographHubSlotMixin {

    private static final ResourceLocation GT_SHANHAI$HUB =
            new ResourceLocation("gt_shanhai", "maintenance_hatch");

    @Inject(method = "testLimited", at = @At("HEAD"), cancellable = true, remap = false)
    private void gtShanhai$astrographHubSkipsLimitedSlots(MultiblockState blockWorldState,
                                                          CallbackInfoReturnable<Boolean> cir) {
        if (blockWorldState == null || blockWorldState.world == null || !gtShanhai$isHub(blockWorldState)) {
            return;
        }
        IMultiController controller = blockWorldState.getController();
        if (controller instanceof ArcanicAstrograph) {
            cir.setReturnValue(false);
        }
    }

    private static boolean gtShanhai$isHub(MultiblockState state) {
        BlockState blockState = state.getBlockState();
        if (blockState == null) {
            return false;
        }
        ResourceLocation id = ForgeRegistries.BLOCKS.getKey(blockState.getBlock());
        return GT_SHANHAI$HUB.equals(id);
    }
}
