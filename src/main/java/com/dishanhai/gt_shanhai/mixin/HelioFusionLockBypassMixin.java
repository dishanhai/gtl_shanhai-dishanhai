package com.dishanhai.gt_shanhai.mixin;

import com.dishanhai.gt_shanhai.common.machine.part.DShanhaiMaintenanceHatchMachine;
import com.dishanhai.gt_shanhai.common.machine.part.ProgrammableHatchPartMachine;
import com.gregtechceu.gtceu.api.capability.recipe.IRecipeCapabilityHolder;
import com.gregtechceu.gtceu.api.machine.feature.IRecipeLogicMachine;
import com.gregtechceu.gtceu.api.machine.feature.multiblock.IMultiPart;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import com.gtladd.gtladditions.api.machine.logic.GTLAddMultipleWirelessRecipesLogic;
import com.gtladd.gtladditions.common.machine.multiblock.controller.ForgeOfTheAntichrist;
import com.gtladd.gtladditions.common.machine.multiblock.controller.module.HelioFusionExoticizer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Set;

/**
 * 聚变异化器配方锁定 Mixin
 * 无枢纽 → 原始锁定行为（安全）
 * 有枢纽 → 跳过锁定，每次自由搜索配方
 */
@Mixin(targets = "com.gtladd.gtladditions.common.machine.multiblock.controller.module.HelioFusionExoticizer$Companion$HelioFusionExoticizerLogic", remap = false)
public class HelioFusionLockBypassMixin {

    private static final Method CHECK_RECIPE;
    private static final Method GET_LOCK_RECIPE;
    private static final Method SET_LOCK;
    private static final Method SET_LOCK_RECIPE;
    static {
        Class<?> self = null;
        try {
            self = Class.forName("com.gtladd.gtladditions.common.machine.multiblock.controller.module.HelioFusionExoticizer$Companion$HelioFusionExoticizerLogic");
        } catch (Exception ignored) {}

        Method cr = null, glr = null, sl = null, slr = null;
        if (self != null) {
            try {
                Class<?> parent = self.getSuperclass();
                cr = parent.getDeclaredMethod("checkRecipe", GTRecipe.class);
                cr.setAccessible(true);
                glr = self.getMethod("getLockRecipe");
                sl = self.getMethod("setLock", boolean.class);
                slr = self.getMethod("setLockRecipe", GTRecipe.class);
            } catch (Exception ignored) {}
        }
        CHECK_RECIPE = cr;
        GET_LOCK_RECIPE = glr;
        SET_LOCK = sl;
        SET_LOCK_RECIPE = slr;
    }

    @Overwrite
    public Set<GTRecipe> lookupRecipeIterator() {
        if (hasHub()) {
            return searchWithoutLock();
        }
        return originalLockingSearch();
    }

    /** 枢纽安装 → 搜索但不锁定（每次自由切换配方） */
    private Set<GTRecipe> searchWithoutLock() {
        IRecipeLogicMachine machine = ((GTLAddMultipleWirelessRecipesLogic) (Object) this).getMachine();
        var lookup = ProgrammableHatchPartMachine.getEffectiveRecipeType(machine, machine.getRecipeType()).getLookup();
        if (lookup == null || CHECK_RECIPE == null) return Collections.emptySet();
        try {
            var result = lookup.find((IRecipeCapabilityHolder) machine, r -> {
                try { return (boolean) CHECK_RECIPE.invoke(this, r); }
                catch (Exception e) { return false; }
            });
            if (result != null) return Collections.singleton(result);
        } catch (Exception ignored) {}
        return Collections.emptySet();
    }

    /** 无枢纽 → 原始锁定行为：先检查锁定配方，无锁定则搜索并锁定 */
    private Set<GTRecipe> originalLockingSearch() {
        if (GET_LOCK_RECIPE == null || CHECK_RECIPE == null) return Collections.emptySet();
        try {
            GTRecipe locked = (GTRecipe) GET_LOCK_RECIPE.invoke(this);
            if (locked != null) {
                boolean valid = (boolean) CHECK_RECIPE.invoke(this, locked);
                if (valid) return Collections.singleton(locked);
                SET_LOCK.invoke(this, false);
                SET_LOCK_RECIPE.invoke(this, (GTRecipe) null);
                return Collections.emptySet();
            }
            // 搜索并锁定
            IRecipeLogicMachine machine = ((GTLAddMultipleWirelessRecipesLogic) (Object) this).getMachine();
            var lookup = ProgrammableHatchPartMachine.getEffectiveRecipeType(machine, machine.getRecipeType()).getLookup();
            if (lookup == null) return Collections.emptySet();
            var result = lookup.find((IRecipeCapabilityHolder) machine, r -> {
                try { return (boolean) CHECK_RECIPE.invoke(this, r); }
                catch (Exception e) { return false; }
            });
            if (result != null) {
                SET_LOCK.invoke(this, true);
                SET_LOCK_RECIPE.invoke(this, result);
                return Collections.singleton(result);
            }
        } catch (Exception ignored) {}
        return Collections.emptySet();
    }

    private boolean hasHub() {
        try {
            var machine = (HelioFusionExoticizer) ((GTLAddMultipleWirelessRecipesLogic) (Object) this).getMachine();
            Object rawHost = machine.getHost();
            if (rawHost instanceof ForgeOfTheAntichrist host) {
                for (IMultiPart part : host.getParts()) {
                    if (part instanceof DShanhaiMaintenanceHatchMachine) return true;
                }
            }
        } catch (Exception ignored) {}
        return false;
    }
}
