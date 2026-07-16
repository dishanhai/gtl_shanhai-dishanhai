package com.dishanhai.gt_shanhai.mixin;

import com.dishanhai.gt_shanhai.common.item.SuperDiskArrayInventory;
import mezz.jei.gui.overlay.elements.IElement;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Mixin(targets = "mezz.jei.gui.ingredients.IngredientFilter", remap = false)
public class JEISdaElementDedupMixin {
    @Inject(method = "getElements", at = @At("RETURN"), cancellable = true, remap = false)
    private void gtShanhai$dedupSdaElements(CallbackInfoReturnable<List<IElement<?>>> cir) {
        List<IElement<?>> original = cir.getReturnValue();
        if (original == null || original.size() < 2) return;

        Set<String> seenSdaKeys = new HashSet<>();
        List<IElement<?>> deduped = new ArrayList<>(original.size());
        boolean changed = false;
        for (IElement<?> element : original) {
            String sdaKey = gtShanhai$getSdaKey(element);
            if (sdaKey != null && !seenSdaKeys.add(sdaKey)) {
                changed = true;
                continue;
            }
            deduped.add(element);
        }
        if (changed) cir.setReturnValue(deduped);
    }

    private static String gtShanhai$getSdaKey(IElement<?> element) {
        if (element == null || element.getTypedIngredient() == null) return null;
        Optional<ItemStack> stackOpt = element.getTypedIngredient().getItemStack();
        if (stackOpt.isEmpty()) return null;

        ItemStack stack = stackOpt.get();
        if (stack.isEmpty()) return null;
        var itemId = BuiltInRegistries.ITEM.getKey(stack.getItem());
        if (itemId == null || !"gt_shanhai:super_disk_array".equals(itemId.toString())) return null;

        var tag = stack.getTag();
        return SuperDiskArrayInventory.getJeiSubtypeKey(tag);
    }
}
