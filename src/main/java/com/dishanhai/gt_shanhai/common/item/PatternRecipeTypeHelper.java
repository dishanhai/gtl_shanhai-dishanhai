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

    // gtlcore 自身编码样板时写入的兜底字段：gtlcore:{patternQuickUploadRecipeTypes:["gtceu:xxx", ...]}
    private static final String GTLCORE_TAG = "gtlcore";
    private static final String GTLCORE_QUICK_UPLOAD_TAG = "patternQuickUploadRecipeTypes";

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
        if (tag == null) return "";
        if (tag.contains(TAG_RECIPE_TYPE, 8)) {
            String id = tag.getString(TAG_RECIPE_TYPE);
            if (!id.isEmpty()) return id;
        }
        return readGtlcoreQuickUploadRecipeTypeId(tag);
    }

    // gt_shanhai 自身字段缺失时，回退读取 gtlcore 编码样板时写入的配方类型列表（更健全，覆盖 gt_shanhai mixin 未拦截到的编码路径）。
    // gtlcore 侧存在歧义（多个候选配方类型）时会直接不写或不去重写入多个值，因此这里仅当列表唯一去重后恰好一个元素时才采信，
    // 有歧义则视为无结果，与 gtlcore 自身 PatternQuickUploadRecipeTypeResolver 遇歧义放弃的语义保持一致。
    private static String readGtlcoreQuickUploadRecipeTypeId(CompoundTag tag) {
        if (!tag.contains(GTLCORE_TAG, 10)) return "";
        CompoundTag gtlcore = tag.getCompound(GTLCORE_TAG);
        if (!gtlcore.contains(GTLCORE_QUICK_UPLOAD_TAG, 9)) return "";
        net.minecraft.nbt.ListTag list = gtlcore.getList(GTLCORE_QUICK_UPLOAD_TAG, 8);
        java.util.LinkedHashSet<String> ids = new java.util.LinkedHashSet<>();
        for (int i = 0; i < list.size(); i++) {
            String id = list.getString(i);
            if (!id.isEmpty()) ids.add(id);
        }
        return ids.size() == 1 ? ids.iterator().next() : "";
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

    /**
     * 只读解析样板对应的真实配方：与 {@link #ensureRecipe} 同源，但绝不写回 NBT 标记。
     * 用于非星律的通用样板总成（GTLCore/GTLAdd 超级样板总成等）——这些样板同时在向 AE
     * 网络提供样板服务，改动 NBT 会改变 AE 样板身份（AEItemKey 含 NBT），必须只读。
     */
    public static GTRecipe peekRecipe(ItemStack stack, Level level) {
        if (stack == null || stack.isEmpty() || level == null) return null;
        String existing = readRecipeTypeId(stack);
        GTRecipe recipe = inferRecipe(stack, level, existing);
        if (recipe == null || recipe.recipeType == null || recipe.recipeType.registryName == null) {
            recipe = inferRecipe(stack, level, "");
        }
        if (recipe == null || recipe.recipeType == null || recipe.recipeType.registryName == null) return null;
        return recipe;
    }

    /** 直接从已解码处理样板推断 GT 配方，供通配符展开后的动态样板使用。 */
    public static GTRecipe findRecipe(IPatternDetails details) {
        if (!(details instanceof AEProcessingPattern pattern)) return null;
        return VirtualPatternEncodingHelper.findMatchingRecipeForPattern(
                pattern.getSparseInputs(), pattern.getSparseOutputs());
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
            return recipeTypeId == null || recipeTypeId.isEmpty()
                    ? VirtualPatternEncodingHelper.findMatchingRecipeForPattern(in, out)
                    : VirtualPatternEncodingHelper.findMatchingRecipeForPattern(in, out, recipeTypeId);
        } catch (RuntimeException e) {
            LOG.warn("[inferRecipe] 解析异常", e);
            return null;
        }
    }
}
