package com.dishanhai.gt_shanhai.mixin;

import com.dishanhai.gt_shanhai.common.machine.primordial.PrimordialOmegaEngineMachine;
import com.dishanhai.gt_shanhai.common.machine.primordial.PrimordialOmegaEngineModuleBase;
import com.dishanhai.gt_shanhai.common.machine.part.RecipeTypePatternBufferPartMachine;
import com.gregtechceu.gtceu.api.capability.recipe.IRecipeCapabilityHolder;
import com.gregtechceu.gtceu.api.machine.feature.IRecipeLogicMachine;
import com.gregtechceu.gtceu.api.machine.feature.multiblock.IMultiController;
import com.gregtechceu.gtceu.api.machine.feature.multiblock.IMultiPart;
import com.gregtechceu.gtceu.api.recipe.GTRecipeType;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.TickTask;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;

import org.gtlcore.gtlcore.common.machine.multiblock.part.ae.MEPatternBufferPartMachine;
import org.gtlcore.gtlcore.api.machine.trait.IRecipeCapabilityMachine;
import org.gtlcore.gtlcore.api.machine.trait.MEPatternRecipeHandlePart;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import appeng.api.crafting.IPatternDetails;
import appeng.api.crafting.PatternDetailsHelper;
import appeng.api.stacks.AEItemKey;

/**
 * 自动样板配方类型匹配。
 * 样板变更时解码输出物品，匹配模块支持的配方类型，自动切换。
 */
@Mixin(value = MEPatternBufferPartMachine.class, remap = false)
public abstract class SuperPatternAutoMatchMixin {

    @Shadow(remap = false)
    protected abstract void onPatternChange(int slot);

    @Shadow(remap = false)
    protected abstract void removeSlotFromGTRecipeCache(int slot);

    @Inject(method = "onLoad", at = @At("TAIL"), remap = false)
    private void gtShanhai$refreshLoadedPatternCache(CallbackInfo ci) {
        MEPatternBufferPartMachine self = (MEPatternBufferPartMachine) (Object) this;
        if (!(self.getLevel() instanceof ServerLevel serverLevel)) return;

        serverLevel.getServer().tell(new TickTask(1, () -> this.gtShanhai$refreshLoadedPatternBuffer(self)));
        serverLevel.getServer().tell(new TickTask(20, () -> this.gtShanhai$refreshLoadedPatternBuffer(self)));
    }

    private void gtShanhai$refreshLoadedPatternBuffer(MEPatternBufferPartMachine self) {
        var inv = self.getPatternInventory();
        if (inv == null) return;
        for (int slot = 0; slot < inv.getSlots(); slot++) {
            ItemStack patternStack = inv.getStackInSlot(slot);
            if (!patternStack.isEmpty()) {
                this.gtShanhai$resetLoadedPatternSlot(self, slot);
                this.onPatternChange(slot);
            }
        }
        this.gtShanhai$refreshControllers(self);
    }

    private void gtShanhai$refreshControllers(MEPatternBufferPartMachine self) {
        for (IMultiController controller : self.getControllers()) {
            if (controller instanceof PrimordialOmegaEngineModuleBase) {
                continue;
            }
            if (controller instanceof IRecipeCapabilityMachine) {
                IRecipeCapabilityMachine machine = (IRecipeCapabilityMachine) controller;
                machine.upDate();
                this.gtShanhai$restorePatternMachineCache(machine);
            }
            if (controller instanceof IRecipeLogicMachine) {
                ((IRecipeLogicMachine) controller).getRecipeLogic().updateTickSubscription();
            }
        }
    }

    private void gtShanhai$restorePatternMachineCache(IRecipeCapabilityMachine machine) {
        java.util.List<MEPatternRecipeHandlePart> parts = machine.getMEPatternRecipeHandleParts();
        if (parts == null || parts.isEmpty()) return;
        for (MEPatternRecipeHandlePart part : parts) {
            if (part != null) {
                part.restoreMachineCache(machine::tryAddAndActiveRhp);
            }
        }
    }

    private void gtShanhai$resetLoadedPatternSlot(MEPatternBufferPartMachine self, int slot) {
        Object[] internalInventory = (Object[]) self.getInternalInventory();
        if (internalInventory == null || slot < 0 || slot >= internalInventory.length) return;
        Object internalSlot = internalInventory[slot];
        if (internalSlot == null) return;

        Object cacheManager = this.gtShanhai$invokeNoArg(internalSlot, "getCacheManager");
        if (cacheManager != null) {
            this.gtShanhai$invokeNoArg(cacheManager, "clearAllCaches");
        }
        Object callback = this.gtShanhai$invokeNoArg(internalSlot, "getOnContentsChanged");
        if (callback instanceof Runnable) {
            ((Runnable) callback).run();
        }
    }

    private Object gtShanhai$invokeNoArg(Object target, String methodName) {
        Class<?> type = target.getClass();
        while (type != null) {
            try {
                java.lang.reflect.Method method = type.getDeclaredMethod(methodName);
                method.setAccessible(true);
                return method.invoke(target);
            } catch (NoSuchMethodException ignored) {
                type = type.getSuperclass();
            } catch (Throwable ignored) {
                return null;
            }
        }
        return null;
    }

    @Inject(method = "onPatternChange", at = @At("TAIL"))
    private void gtShanhai$autoMatchRecipeType(int slot, CallbackInfo ci) {
        MEPatternBufferPartMachine self = (MEPatternBufferPartMachine) (Object) this;
        if (self instanceof RecipeTypePatternBufferPartMachine) return;
        var level = self.getLevel();
        if (level == null) return;

        // 获取样板
        var inv = self.getPatternInventory();
        if (inv == null || slot >= inv.getSlots()) return;
        ItemStack patternStack = inv.getStackInSlot(slot);
        if (patternStack.isEmpty()) return;

        // 解码样板
        IPatternDetails details = PatternDetailsHelper.decodePattern(patternStack, level);
        if (details == null) return;
        var outputs = details.getOutputs();
        if (outputs == null || outputs.length == 0) return;

        // 收集输出物品 ID
        java.util.Set<String> outputItemIds = new java.util.HashSet<>();
        for (var gs : outputs) {
            if (gs != null && gs.what() instanceof AEItemKey ik) {
                ResourceLocation rid = ForgeRegistries.ITEMS.getKey(ik.getItem());
                if (rid != null) outputItemIds.add(rid.toString());
            }
        }
        if (outputItemIds.isEmpty()) return;

        // 找到匹配的模块和配方类型
        for (IMultiController ctrl : self.getControllers()) {
            if (!(ctrl instanceof PrimordialOmegaEngineMachine host)) continue;
            for (IMultiPart part : host.getParts()) {
                if (!(part instanceof PrimordialOmegaEngineModuleBase mod)) continue;
                var types = mod.getDefinition().getRecipeTypes();
                if (types == null) continue;
                for (GTRecipeType rt : types) {
                    if (rt == null) continue;
                    // 查该配方类型是否有匹配输出的配方
                    rt.getLookup().getLookup().getRecipes(true).forEach(r -> {
                        if (r == null) return;
                        var itemOutputs = r.outputs.get(
                                com.gregtechceu.gtceu.api.capability.recipe.ItemRecipeCapability.CAP);
                        if (itemOutputs == null) return;
                        for (var c : itemOutputs) {
                            if (c.content instanceof com.gregtechceu.gtceu.api.recipe.ingredient.SizedIngredient si) {
                                var stacks = si.getItems();
                                if (stacks.length > 0) {
                                    var rid = ForgeRegistries.ITEMS.getKey(stacks[0].getItem());
                                    if (rid != null && outputItemIds.contains(rid.toString())) {
                                        // 切换到匹配的配方类型
                                        var logic = mod.getRecipeLogic();
                                        if (logic != null) {
                                            GTRecipeType[] ts = mod.getDefinition().getRecipeTypes();
                                            for (int i = 0; i < ts.length; i++) {
                                                if (ts[i] == rt) {
                                                    ((IRecipeLogicMachine) mod).setActiveRecipeType(i);
                                                    return;
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    });
                }
            }
        }
    }
}
