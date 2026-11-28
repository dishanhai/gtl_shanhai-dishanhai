package com.dishanhai.gt_shanhai.mixin;

import com.dishanhai.gt_shanhai.GTDishanhaiMod;
import com.dishanhai.gt_shanhai.common.item.SuperDiskArrayInventory;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import mezz.jei.api.constants.VanillaTypes;
import mezz.jei.gui.overlay.elements.IElement;
import net.minecraft.world.item.Item;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Mixin(targets = "mezz.jei.gui.ingredients.IngredientFilter", remap = false)
public class JEISdaElementDedupMixin {
    @ModifyExpressionValue(method = "getElements", at = @At(value = "INVOKE",
            target = "Ljava/util/stream/Stream;toList()Ljava/util/List;"), remap = false)
    private List<IElement<?>> gtShanhai$dedupSdaElements(List<IElement<?>> original) {
        if (original.size() < 2) return original;

        // JEI stores this result until it invalidates the filter. GTL calls getElements from its per-frame group revision.
        Item disk = GTDishanhaiMod.SUPER_DISK_ARRAY.get();
        Set<String> seenSdaKeys = new HashSet<>();
        List<IElement<?>> deduped = null;
        for (int i = 0; i < original.size(); i++) {
            var element = original.get(i);
            String sdaKey = gtShanhai$getSdaKey(element, disk);
            if (sdaKey != null && !seenSdaKeys.add(sdaKey)) {
                if (deduped == null) deduped = new ArrayList<>(original.subList(0, i));
            } else if (deduped != null) {
                deduped.add(element);
            }
        }
        return deduped == null ? original : List.copyOf(deduped);
    }

    private static String gtShanhai$getSdaKey(IElement<?> element, Item disk) {
        var typed = element.getTypedIngredient().castToItemStackType();
        if (typed == null || typed.getBaseIngredient(VanillaTypes.ITEM_STACK) != disk) return null;
        return SuperDiskArrayInventory.getJeiSubtypeKey(typed.getIngredient().getTag());
    }
}
