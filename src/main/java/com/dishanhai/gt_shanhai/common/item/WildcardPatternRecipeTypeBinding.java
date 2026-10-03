package com.dishanhai.gt_shanhai.common.item;

import appeng.api.crafting.IPatternDetails;
import appeng.crafting.pattern.AEProcessingPattern;

import com.gregtechceu.gtceu.api.machine.feature.IRecipeLogicMachine;
import com.gregtechceu.gtceu.api.machine.feature.multiblock.IMultiController;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import com.gregtechceu.gtceu.api.recipe.GTRecipeType;
import com.dishanhai.gt_shanhai.api.machine.SelectableRecipeTypeSetMachine;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.lang.reflect.Method;

public final class WildcardPatternRecipeTypeBinding {

    private WildcardPatternRecipeTypeBinding() {
    }

    public static ItemStack assign(ItemStack source, String recipeTypeId) {
        if (source == null) return ItemStack.EMPTY;
        ItemStack result = source.copy();
        if (result.isEmpty()) return result;

        if (recipeTypeId == null || recipeTypeId.isBlank()) {
            CompoundTag tag = result.getTag();
            if (tag != null) {
                tag.remove(PatternRecipeTypeHelper.TAG_RECIPE_TYPE);
                if (tag.isEmpty()) result.setTag(null);
            }
        } else {
            result.getOrCreateTag().putString(PatternRecipeTypeHelper.TAG_RECIPE_TYPE, recipeTypeId);
        }
        return result;
    }

    public static ItemStack clear(ItemStack source) {
        return assign(source, "");
    }

    public static GTRecipe findRecipe(IPatternDetails details, String recipeTypeId) {
        if (!(details instanceof AEProcessingPattern pattern)) return null;
        if (recipeTypeId == null || recipeTypeId.isBlank()) {
            return PatternRecipeTypeHelper.findRecipe(details);
        }
        return VirtualPatternEncodingHelper.findMatchingRecipeForPattern(
                pattern.getSparseInputs(), pattern.getSparseOutputs(), recipeTypeId);
    }

    public static List<GTRecipeType> collectHostRecipeTypes(Iterable<IMultiController> controllers) {
        if (controllers == null) return List.of();
        Map<ResourceLocation, GTRecipeType> types = new LinkedHashMap<>();
        for (IMultiController controller : controllers) {
            if (controller == null || !controller.isFormed()) continue;
            // 星律的控制器才是配方類型的擁有者；掛載模組與其他零件不屬於這個星律。
            collectMachineRecipeTypes(types, controller.self());
        }
        return new ArrayList<>(types.values());
    }

    private static void collectMachineRecipeTypes(Map<ResourceLocation, GTRecipeType> target,
            Object owner) {
        if (!(owner instanceof IRecipeLogicMachine machine)) return;
        addExpandedTypes(target, machine.getRecipeTypes());
        if (machine instanceof SelectableRecipeTypeSetMachine selectable) {
            addExpandedTypes(target, selectable.getAllSelectableRecipeTypes());
        }
        addNamedRecipeTypes(target, machine, "getRecipeTypeNameSet");
        addExpandedTypes(target, reflectedRecipeTypes(machine, "getAllSelectableRecipeTypes"));
        addExpandedTypes(target, reflectedRecipeType(machine, "getMultiRecipeType"));
        addExpandedTypes(target, machine.getRecipeType());
    }

    private static void addNamedRecipeTypes(Map<ResourceLocation, GTRecipeType> target,
            Object owner, String methodName) {
        try {
            Method method = owner.getClass().getMethod(methodName);
            Object result = method.invoke(owner);
            if (!(result instanceof Iterable<?> names)) return;
            for (Object name : names) {
                if (!(name instanceof String recipeTypeId) || recipeTypeId.isBlank()) continue;
                GTRecipeType type = PatternRecipeTypeHelper.resolveRecipeType(recipeTypeId);
                if (type != null) addExpandedTypes(target, type);
            }
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // 非原初主机没有这个方法，继续使用通用配方类型接口。
        }
    }

    private static void addExpandedTypes(Map<ResourceLocation, GTRecipeType> target,
            GTRecipeType... roots) {
        if (roots == null) return;
        ArrayDeque<GTRecipeType> pending = new ArrayDeque<>();
        Set<GTRecipeType> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        for (GTRecipeType root : roots) if (root != null) pending.add(root);
        while (!pending.isEmpty()) {
            GTRecipeType current = pending.removeFirst();
            if (!visited.add(current)) continue;
            GTRecipeType[] children = reflectedRecipeTypes(current, "getTypeList");
            boolean expanded = false;
            if (children != null) {
                for (GTRecipeType child : children) {
                    if (child != null && child != current) {
                        pending.addLast(child);
                        expanded = true;
                    }
                }
            }
            if (!expanded && current.registryName != null
                    && !PatternRecipeExecutionGuard.isAuxiliaryIORecipeTypeId(current.registryName)
                    && !"gt_shanhai:selectable_recipe_type_set".equals(current.registryName.toString())) {
                target.putIfAbsent(current.registryName, current);
            }
        }
    }

    private static GTRecipeType reflectedRecipeType(Object owner, String methodName) {
        try {
            Method method = owner.getClass().getMethod(methodName);
            Object result = method.invoke(owner);
            return result instanceof GTRecipeType type ? type : null;
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return null;
        }
    }

    private static GTRecipeType[] reflectedRecipeTypes(Object owner, String methodName) {
        try {
            Method method = owner.getClass().getMethod(methodName);
            Object result = method.invoke(owner);
            return result instanceof GTRecipeType[] types ? types : null;
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return null;
        }
    }
}
