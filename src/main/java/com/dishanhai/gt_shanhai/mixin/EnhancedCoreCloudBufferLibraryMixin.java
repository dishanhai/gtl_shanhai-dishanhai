package com.dishanhai.gt_shanhai.mixin;

import appeng.api.crafting.IPatternDetails;
import com.dishanhai.gt_shanhai.common.item.SelectedRecipePattern;
import com.dishanhai.gt_shanhai.common.item.PatternEncodePreferences;
import com.dishanhai.gt_shanhai.integration.enhancedcore.EnhancedCoreCloudLibraryCompat;
import com.gtl.enhancedcore.common.cloud.CloudPatternBufferMachine;
import com.gtl.enhancedcore.common.cloud.CloudPatternMatching;
import com.gtl.enhancedcore.common.cloud.store.CloudPatternStore;
import java.util.*;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** 山海：所有子發布重用母原件 lease；撤銷與重載後由權威 lease 重新計算。 */
@Pseudo
@Mixin(targets = "com.gtl.enhancedcore.common.cloud.CloudPatternBufferMachine", remap = false)
public class EnhancedCoreCloudBufferLibraryMixin {
    @Shadow @Final private Set<UUID> validatedLeases;
    @Shadow private List<IPatternDetails> publishedSnapshot;
    @Shadow private int matched;
    @Shadow protected boolean keepByProduct;
    @Unique private List<Object> gtShanhai$publicationKey = List.of();
    @Unique private List<IPatternDetails> gtShanhai$children = List.of();
    @Inject(method = "getAvailablePatterns", at = @At("RETURN"), cancellable = true, require = 0)
    private void gtShanhai$publishChildren(CallbackInfoReturnable<List<IPatternDetails>> cir) {
        var buffer = (CloudPatternBufferMachine)(Object)this;
        if (!buffer.cloudReady(true) || !buffer.iv$getState().accepting) return;
        var node = buffer.cloudNode();
        if (node == null) return;
        List<Object> key = List.of(node.id(), node.generation(), buffer.iv$getState().identity,
                buffer.cloudStore().library(buffer.cloudLibraryId()).revision(), buffer.cloudMode(), keepByProduct,
                buffer.cloudAllocationEpoch(), Set.copyOf(validatedLeases), EnhancedCoreCloudLibraryCompat.recipeRevision(),
                PatternEncodePreferences.writeVirtualProvider());
        if (!key.equals(gtShanhai$publicationKey)) {
            gtShanhai$children = List.copyOf(EnhancedCoreCloudLibraryCompat.publications(buffer, validatedLeases));
            gtShanhai$publicationKey = key;
        }
        var children = gtShanhai$children;
        var all = new ArrayList<>(cir.getReturnValue());
        all.removeIf(pattern -> {
            var tag = pattern.getDefinition().getTag();
            return tag != null && tag.getCompound(com.gtl.enhancedcore.common.recipe.iv.IvBuffers.ROUTE_KEY)
                    .contains(EnhancedCoreCloudLibraryCompat.CHILD_RECIPE);
        });
        all.addAll(children); publishedSnapshot = List.copyOf(all); matched = all.size();
        cir.setReturnValue(publishedSnapshot);
    }

    @Inject(method = "getSlotIndexForPattern", at = @At("HEAD"), cancellable = true, require = 0)
    private void gtShanhai$childSlot(IPatternDetails pattern, CallbackInfoReturnable<Integer> cir) {
        var tag = pattern.getDefinition().getTag(); if (tag == null) return;
        var route = tag.getCompound(com.gtl.enhancedcore.common.recipe.iv.IvBuffers.ROUTE_KEY);
        var buffer = (CloudPatternBufferMachine)(Object)this;
        if (route.contains(EnhancedCoreCloudLibraryCompat.CHILD_RECIPE) && buffer.cloudRouteValid(route))
            cir.setReturnValue(route.getInt("slot"));
    }

    @Inject(method = "graphOperationsPerPattern", at = @At("HEAD"), cancellable = true, require = 0)
    private void gtShanhai$childOperations(IPatternDetails pattern, CallbackInfoReturnable<Long> cir) {
        var tag = pattern.getDefinition().getTag(); if (tag == null) return;
        var route = tag.getCompound(com.gtl.enhancedcore.common.recipe.iv.IvBuffers.ROUTE_KEY);
        if (!route.contains(EnhancedCoreCloudLibraryCompat.CHILD_RECIPE)) return;
        var buffer = (CloudPatternBufferMachine)(Object)this;
        var recipe = SelectedRecipePattern.recipeOf(pattern.getDefinition().toStack(), buffer.getLevel(),
                buffer.cloudMode() == null ? "" : buffer.cloudMode().toString());
        if (recipe == null || !buffer.cloudRouteValid(route)) { cir.setReturnValue(0L); return; }
        var entry = buffer.entry(route.getInt("slot"));
        if (entry == null) { cir.setReturnValue(0L); return; }
        var child = EnhancedCoreCloudLibraryCompat.child(entry.original(), buffer.getLevel(), recipe.id.toString());
        var probe = child == null ? null : EnhancedCoreCloudLibraryCompat.childProbe(buffer, entry, child, keepByProduct);
        var machine = com.gtl.enhancedcore.common.recipe.iv.IvBuffers.controller(buffer);
        if (probe == null || machine == null || !CloudPatternMatching.supported(recipe, machine)) { cir.setReturnValue(0L); return; }
        cir.setReturnValue(com.gtl.enhancedcore.common.recipe.iv.IvRecipeInputs.patternOperations(
                recipe, probe.inputs(), probe.virtual(), probe.outputs()));
    }

    @Inject(method = "cloudRealPattern", at = @At("HEAD"), cancellable = true, require = 0)
    private void gtShanhai$exactChild(int slot, ItemStack original, CallbackInfoReturnable<IPatternDetails> cir) {
        if (!EnhancedCoreCloudLibraryCompat.mother(original)) return;
        String id = EnhancedCoreCloudLibraryCompat.dispatchRecipe();
        var buffer = (CloudPatternBufferMachine)(Object)this;
        if (id != null) {
            var child = EnhancedCoreCloudLibraryCompat.child(original, buffer.getLevel(), id);
            cir.setReturnValue(child == null ? null : EnhancedCoreCloudLibraryCompat.routed(buffer, slot, original, child));
            return;
        }
        for (var child : EnhancedCoreCloudLibraryCompat.children(original, buffer.getLevel())) {
            // Only Enhancedcore's UI bookkeeping receives a representative; provider publication expands all children.
            if ((id == null && Objects.equals(buffer.cloudMode(), child.recipe().recipeType.registryName))
                    || child.recipe().id.toString().equals(id)) {
                cir.setReturnValue(EnhancedCoreCloudLibraryCompat.routed(buffer, slot, original, child)); return;
            }
        }
        cir.setReturnValue(null);
    }

    @Inject(method = "cloudProbe", at = @At("HEAD"), cancellable = true, require = 0)
    private void gtShanhai$motherProbe(CloudPatternStore.Entry entry, CallbackInfoReturnable<CloudPatternMatching.Probe> cir) {
        if (EnhancedCoreCloudLibraryCompat.mother(entry.original()))
            cir.setReturnValue(EnhancedCoreCloudLibraryCompat.probe((CloudPatternBufferMachine)(Object)this, entry, keepByProduct));
    }
}
