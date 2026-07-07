package com.dishanhai.gt_shanhai.mixin;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import com.gregtechceu.gtceu.api.pattern.predicates.SimplePredicate;

import net.minecraftforge.registries.ForgeRegistries;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.List;

/**
 * 在 SimplePredicate.getCandidates() 源头过滤枢纽方块。
 * 一个 mixin 即可覆盖 PatternError 和 SinglePredicateError 两个路径，
 * 无需分别处理两个错误类。
 */
@Mixin(value = SimplePredicate.class, remap = false)
public class SimplePredicateFilterMixin {

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger("gt_shanhai:detect");
    private static final ResourceLocation HUB_ID =
            new ResourceLocation("gt_shanhai", "maintenance_hatch");

    @Inject(method = "getCandidates", at = @At("RETURN"), remap = false, cancellable = true)
    private void filterHubFromCandidates(CallbackInfoReturnable<List<ItemStack>> cir) {
        try {
            var candidates = cir.getReturnValue();
            if (candidates == null || candidates.isEmpty()) return;

            int originalSize = candidates.size();
            var filtered = new ArrayList<ItemStack>();
            for (var stack : candidates) {
                if (stack.isEmpty()) { filtered.add(stack); continue; }
                var id = ForgeRegistries.ITEMS.getKey(stack.getItem());
                if (HUB_ID.equals(id)) continue;
                filtered.add(stack);
            }
            // 全被过滤时保留原始列表（保证检测工具不崩）
            if (filtered.isEmpty() || filtered.size() == originalSize) return;
            cir.setReturnValue(filtered);
        } catch (Exception e) {
            // 静默降级
        }
    }
}
