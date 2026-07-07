package com.dishanhai.gt_shanhai.mixin;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;

import com.gregtechceu.gtceu.api.gui.widget.PatternPreviewWidget;
import com.gregtechceu.gtceu.api.pattern.predicates.SimplePredicate;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.List;

/**
 * 在 JEI 多方块预览中过滤终焉聚合枢纽。
 * 在 onPosSelected TAIL 清除 predicates 的候选列表中的枢纽。
 */
@Mixin(value = PatternPreviewWidget.class, remap = false)
public class PatternPreviewFilterMixin {

    private static final ResourceLocation HUB_ID =
            new ResourceLocation("gt_shanhai", "maintenance_hatch");

    @Shadow
    @Final
    private List<SimplePredicate> predicates;

    @Inject(method = "onPosSelected", at = @At("TAIL"))
    private void filterHubFromPredicates(CallbackInfo ci) {
        for (var pred : predicates) {
            if (pred == null) continue;
            var items = pred.getCandidates();
            if (items == null || items.isEmpty()) continue;
            var filtered = new ArrayList<ItemStack>();
            for (var stack : items) {
                if (stack.isEmpty()) continue;
                var id = ForgeRegistries.ITEMS.getKey(stack.getItem());
                if (HUB_ID.equals(id)) continue;
                filtered.add(stack);
            }
            if (!filtered.isEmpty() && filtered.size() < items.size()) {
                items.clear();
                items.addAll(filtered);
            }
        }
    }
}
