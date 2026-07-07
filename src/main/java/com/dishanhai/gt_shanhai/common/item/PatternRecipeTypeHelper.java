package com.dishanhai.gt_shanhai.common.item;

import appeng.api.crafting.IPatternDetails;
import appeng.api.crafting.PatternDetailsHelper;
import appeng.api.stacks.GenericStack;
import appeng.crafting.pattern.AEProcessingPattern;

import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import com.gregtechceu.gtceu.api.recipe.GTRecipeType;
import com.gregtechceu.gtceu.api.registry.GTRegistries;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

public final class PatternRecipeTypeHelper {

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger("gt_shanhai:recipe_type");

    public static final String TAG_RECIPE_TYPE = "gt_shanhai_recipe_type";

    private PatternRecipeTypeHelper() {
    }

    public static void writeRecipeType(ItemStack stack, GTRecipe recipe) {
        if (stack == null || stack.isEmpty() || recipe == null) return;
        writeRecipeType(stack, recipe.recipeType);
    }

    public static void writeRecipeType(ItemStack stack, GTRecipeType recipeType) {
        if (stack == null || stack.isEmpty() || recipeType == null || recipeType.registryName == null) return;
        stack.getOrCreateTag().putString(TAG_RECIPE_TYPE, recipeType.registryName.toString());
    }

    public static String readRecipeTypeId(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return "";
        CompoundTag tag = stack.getTag();
        if (tag == null || !tag.contains(TAG_RECIPE_TYPE, 8)) return "";
        return tag.getString(TAG_RECIPE_TYPE);
    }

    public static String ensureRecipeTypeId(ItemStack stack, Level level) {
        String existing = readRecipeTypeId(stack);
        if (stack == null || stack.isEmpty() || level == null) return existing;

        // 如果已有配方类型ID，先验证是否匹配虚拟配方
        if (!existing.isEmpty()) {
            GTRecipe recipe = inferRecipe(stack, level, existing);
            if (recipe != null && recipeMatchesTypeId(recipe, existing)) {
                return existing;  // 现有ID正确，直接返回
            }
            // 现有ID不匹配或无法推断配方，清空重新推断
        }

        // 推断配方并写入配方类型
        GTRecipe recipe = inferRecipe(stack, level, "");
        if (recipe == null || recipe.recipeType == null || recipe.recipeType.registryName == null) {
            return existing;  // 推断失败，保留现有值（可能为空）
        }

        writeRecipeType(stack, recipe);
        return recipe.recipeType.registryName.toString();
    }

    public static GTRecipe ensureRecipe(ItemStack stack, Level level) {
        if (stack == null || stack.isEmpty() || level == null) return null;
        
        String existing = readRecipeTypeId(stack);
        GTRecipe recipe = inferRecipe(stack, level, existing);
        if (recipe == null || recipe.recipeType == null || recipe.recipeType.registryName == null) {
            recipe = inferRecipe(stack, level, "");
        }
        if (recipe == null || recipe.recipeType == null || recipe.recipeType.registryName == null) return null;
        
        // 如果已有ID，验证是否匹配
        if (!existing.isEmpty() && recipeMatchesTypeId(recipe, existing)) {
            return recipe;  // 匹配，直接返回
        }
        
        // 不匹配或无ID，写入正确的配方类型
        writeRecipeType(stack, recipe);
        return recipe;
    }

    public static void writeRecipeTypeFromPattern(ItemStack stack, GenericStack[] inputs, GenericStack[] outputs) {
        if (stack == null || stack.isEmpty() || !readRecipeTypeId(stack).isEmpty()) return;
        GTRecipe recipe = VirtualPatternEncodingHelper.findMatchingRecipeForPattern(inputs, outputs);
        if (recipe != null) {
            writeRecipeType(stack, recipe);
        }
    }

    public static GTRecipeType readRecipeType(ItemStack stack) {
        return resolveRecipeType(readRecipeTypeId(stack));
    }

    public static GTRecipeType resolveRecipeType(String recipeTypeId) {
        if (recipeTypeId == null || recipeTypeId.isEmpty()) return null;
        try {
            return GTRegistries.RECIPE_TYPES.get(new ResourceLocation(recipeTypeId));
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    public static boolean recipeMatchesTypeId(GTRecipe recipe, String recipeTypeId) {
        if (recipeTypeId == null || recipeTypeId.isEmpty()) return true;
        if (recipe == null || recipe.recipeType == null || recipe.recipeType.registryName == null) return false;
        return recipeTypeId.equals(recipe.recipeType.registryName.toString());
    }

    private static GTRecipe inferRecipe(ItemStack stack, Level level) {
        return inferRecipe(stack, level, readRecipeTypeId(stack));
    }

    private static GTRecipe inferRecipe(ItemStack stack, Level level, String recipeTypeId) {
        try {
            IPatternDetails details = PatternDetailsHelper.decodePattern(stack, level);
            if (!(details instanceof AEProcessingPattern pattern)) {
                LOG.debug("[inferRecipe] decodePattern 非 AEProcessingPattern: {}",
                        details == null ? "null" : details.getClass().getName());
                return null;
            }
            GenericStack[] in = pattern.getSparseInputs();
            GenericStack[] out = pattern.getSparseOutputs();
            GTRecipe recipe = recipeTypeId == null || recipeTypeId.isEmpty()
                    ? VirtualPatternEncodingHelper.findMatchingRecipeForPattern(in, out)
                    : VirtualPatternEncodingHelper.findMatchingRecipeForPattern(in, out, recipeTypeId);
            LOG.debug("[inferRecipe] inputs={} outputs={} -> recipe={}",
                    describeStacks(in), describeStacks(out),
                    recipe == null ? "null"
                            : (recipe.recipeType == null || recipe.recipeType.registryName == null
                                    ? "无类型" : recipe.recipeType.registryName.toString()));
            return recipe;
        } catch (RuntimeException e) {
            LOG.warn("[inferRecipe] 解析异常", e);
            return null;
        }
    }

    private static String describeStacks(GenericStack[] stacks) {
        if (stacks == null) return "null";
        StringBuilder sb = new StringBuilder("[");
        for (GenericStack s : stacks) {
            if (s == null || s.what() == null) continue;
            sb.append(s.what()).append("x").append(s.amount()).append(", ");
        }
        return sb.append("]").toString();
    }
}
