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

    // 编码时（Ae2GtmProcessingPatternMixin）已知确切来源 GTRecipe、零歧义写入的权威标记：
    // 一旦置位，ensureRecipeTypeId/ensureRecipe 永不再重新推断/覆盖——避免"同一输入输出恰好
    // 也能匹配到另一配方类型"（如材质自动生成的电炉配方与手写的反熵冷凝配方撞了同一输入输出）
    // 时，仅按类型域搜索的自愈确认对两边都能"确认成功"，被某次瞬时失配（配方被
    // DShanhaiRecipeModifierAPI 规则临时剥离/替换）触发误改判后一直来回横跳。
    private static final String TAG_RECIPE_TYPE_AUTHORITATIVE = "gt_shanhai_recipe_type_authoritative";
    private static final ThreadLocal<GTRecipe> AUTHORITATIVE_ENCODING_RECIPE = new ThreadLocal<>();
    private static final ThreadLocal<String> ENCODING_RECIPE_TYPE_ID = new ThreadLocal<>();

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

    /**
     * 编码时权威写入：仅供已经拿到确切来源 {@link GTRecipe}（玩家实际选中/上传的那一个，零歧义）
     * 的调用点使用，如 {@code Ae2GtmProcessingPatternMixin}。写入后该样板永久跳过自愈重推断。
     */
    public static void writeAuthoritativeRecipeType(ItemStack stack, GTRecipe recipe) {
        if (stack == null || stack.isEmpty() || recipe == null) return;
        writeRecipeType(stack, recipe);
        stack.getOrCreateTag().putBoolean(TAG_RECIPE_TYPE_AUTHORITATIVE, true);
    }

    private static boolean isAuthoritative(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        CompoundTag tag = stack.getTag();
        return tag != null && tag.getBoolean(TAG_RECIPE_TYPE_AUTHORITATIVE);
    }

    public static void pushAuthoritativeEncodingRecipe(GTRecipe recipe) {
        AUTHORITATIVE_ENCODING_RECIPE.set(recipe);
    }

    public static void popAuthoritativeEncodingRecipe() {
        AUTHORITATIVE_ENCODING_RECIPE.remove();
    }

    public static GTRecipe currentAuthoritativeEncodingRecipe() {
        return AUTHORITATIVE_ENCODING_RECIPE.get();
    }

    public static void pushEncodingRecipeType(String recipeTypeId) {
        if (recipeTypeId == null || recipeTypeId.isEmpty()) {
            ENCODING_RECIPE_TYPE_ID.remove();
        } else {
            ENCODING_RECIPE_TYPE_ID.set(recipeTypeId);
        }
    }

    public static void popEncodingRecipeType() {
        ENCODING_RECIPE_TYPE_ID.remove();
    }

    public static String currentEncodingRecipeTypeId() {
        String recipeTypeId = ENCODING_RECIPE_TYPE_ID.get();
        return recipeTypeId == null ? "" : recipeTypeId;
    }

    public static String readRecipeTypeId(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return "";
        CompoundTag tag = stack.getTag();
        if (tag == null) return "";

        if (tag.getBoolean(TAG_RECIPE_TYPE_AUTHORITATIVE) && tag.contains(TAG_RECIPE_TYPE, 8)) {
            String id = tag.getString(TAG_RECIPE_TYPE);
            if (!id.isEmpty()) return id;
        }

        // gtlcore 编码样板时用 PatternQuickUploadRecipeTypeResolver 写入的字段优先：它在编码那一刻
        // 就知道确切来源配方（或者按类型域扫描+遇歧义直接放弃，不去重写多个候选），比 gt_shanhai
        // 自己事后单靠物品堆叠反查全配方表要可靠得多——反查在"不同配方类型凑巧输入输出堆叠一样"
        // 时必然会猜错（如 0.144B液态铁+1铁粉，"分子解构"和"提取机"都能匹配），之前顺序反了，
        // gt_shanhai 自己猜错的字段优先显示，篡改了样板本该显示的正确配方类型。
        String gtlcoreId = readGtlcoreQuickUploadRecipeTypeId(tag);
        if (!gtlcoreId.isEmpty()) return gtlcoreId;

        if (tag.contains(TAG_RECIPE_TYPE, 8)) {
            String id = tag.getString(TAG_RECIPE_TYPE);
            if (!id.isEmpty()) return id;
        }
        return "";
    }

    // gtlcore 侧存在歧义（多个候选配方类型）时会直接不写或不去重写入多个值，因此这里仅当列表唯一去重后恰好一个元素时才采信，
    // 有歧义则视为无结果，与 gtlcore 自身 PatternQuickUploadRecipeTypeResolver 遇歧义放弃的语义保持一致。
    private static String readGtlcoreQuickUploadRecipeTypeId(CompoundTag tag) {
        if (!tag.contains(GTLCORE_TAG, 10)) return "";
        CompoundTag gtlcore = tag.getCompound(GTLCORE_TAG);
        if (!gtlcore.contains(GTLCORE_QUICK_UPLOAD_TAG, 9)) return "";
        net.minecraft.nbt.ListTag list = gtlcore.getList(GTLCORE_QUICK_UPLOAD_TAG, 8);
        String uniqueId = "";
        for (int i = 0; i < list.size(); i++) {
            String id = list.getString(i);
            if (id.isEmpty()) continue;
            if (uniqueId.isEmpty()) {
                uniqueId = id;
            } else if (!uniqueId.equals(id)) {
                return "";
            }
        }
        return uniqueId;
    }

    public static String ensureRecipeTypeId(ItemStack stack, Level level) {
        String existing = readRecipeTypeId(stack);
        if (stack == null || stack.isEmpty() || level == null) return existing;

        // 编码时权威写入：零歧义来源，永久信任，跳过下面一切重推断/覆盖判断。
        if (!existing.isEmpty() && isAuthoritative(stack)) {
            return existing;
        }

        // 如果已有配方类型ID，先验证是否匹配虚拟配方
        if (!existing.isEmpty()) {
            GTRecipe recipe = inferRecipe(stack, level, existing);
            if (recipe != null && recipeMatchesTypeId(recipe, existing)) {
                return existing;  // 现有ID正确，直接返回
            }
            // 现有ID对应的配方表如果还没加载好（常见于世界刚加载时），不能当作"这个样板
            // 换配方类型了"——下面的全局兜底搜索精度更低，容易把简单的 1进1出配方误配到
            // 不相关类型（如 electric_furnace）上，一旦写回 NBT 就永久覆盖掉正确值，
            // 只能靠玩家重新编码样板才能修复。只有确认该类型配方表已就绪、确实匹配不上时，
            // 才允许下面重新推断纠正（自愈已经写错的标记）。
            if (!isRecipeTypeLookupPopulated(existing)) {
                return existing;
            }
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

        // 编码时权威写入：零歧义来源，永久信任，只在权威类型域内解析，绝不回退全局重推断/覆盖。
        // 类型表暂未就绪时返回 null（调用方下次再试），而不是当作"类型变了"去改判。
        if (!existing.isEmpty() && isAuthoritative(stack)) {
            return inferRecipe(stack, level, existing);
        }

        GTRecipe recipe = inferRecipe(stack, level, existing);
        if (recipe != null && recipe.recipeType != null && recipe.recipeType.registryName != null
                && (existing.isEmpty() || recipeMatchesTypeId(recipe, existing))) {
            return recipe;  // 匹配，直接返回
        }

        // 同上：配方表还没就绪时不下结论，也不覆盖现有标记，等下次再验证。
        if (!existing.isEmpty() && !isRecipeTypeLookupPopulated(existing)) {
            return null;
        }

        // 不匹配或无ID，全局重新推断并写入正确的配方类型
        recipe = inferRecipe(stack, level, "");
        if (recipe == null || recipe.recipeType == null || recipe.recipeType.registryName == null) return null;

        writeRecipeType(stack, recipe);
        return recipe;
    }

    /**
     * 判断某配方类型自身的配方表是否已经加载就绪（是否至少有一条配方）。世界刚加载时
     * 部分自定义配方类型的 lookup 可能还没建好，此时任何"在该类型内找不到匹配"的结果
     * 都是不可信的，不能据此判定样板真的换了配方类型。
     */
    private static boolean isRecipeTypeLookupPopulated(String recipeTypeId) {
        GTRecipeType recipeType = resolveRecipeType(recipeTypeId);
        if (recipeType == null || recipeType.getLookup() == null || recipeType.getLookup().getLookup() == null) {
            return false;
        }
        return recipeType.getLookup().getLookup().getRecipes(true).iterator().hasNext();
    }

    /**
     * 只读解析样板对应的真实配方：与 {@link #ensureRecipe} 同源，但绝不写回 NBT 标记。
     * 用于非星律的通用样板总成（GTLCore/GTLAdd 超级样板总成等）——这些样板同时在向 AE
     * 网络提供样板服务，改动 NBT 会改变 AE 样板身份（AEItemKey 含 NBT），必须只读。
     */
    public static GTRecipe peekRecipe(ItemStack stack, Level level) {
        return peekRecipe(stack, level, (GenericStack[]) null);
    }

    public static GTRecipe peekRecipe(ItemStack stack, Level level, GenericStack[] availableCatalystInputs) {
        if (stack == null || stack.isEmpty() || level == null) return null;
        String existing = readRecipeTypeId(stack);
        return peekRecipe(stack, level, existing, availableCatalystInputs);
    }

    private static GTRecipe peekRecipe(ItemStack stack, Level level, String existing) {
        return peekRecipe(stack, level, existing, null);
    }

    private static GTRecipe peekRecipe(ItemStack stack, Level level, String existing,
            GenericStack[] availableCatalystInputs) {

        // 权威标记：零歧义来源，只在其类型域内解析，绝不回退全局搜索给出不同答案
        // （否则某次瞬时失配就会让只读预览也跟着抖一下，虽不写 NBT 但仍是不必要的不一致）。
        if (!existing.isEmpty() && isAuthoritative(stack)) {
            return inferRecipe(stack, level, existing, availableCatalystInputs);
        }

        GTRecipe recipe = inferRecipe(stack, level, existing, availableCatalystInputs);
        if (recipe == null || recipe.recipeType == null || recipe.recipeType.registryName == null) {
            recipe = inferRecipe(stack, level, "", availableCatalystInputs);
        }
        return recipe == null || recipe.recipeType == null || recipe.recipeType.registryName == null
                ? null : recipe;
    }

    /**
     * 只读解析样板对应的配方类型ID：与 {@link #peekRecipe} 同源，只读不写。供星律样板总成给
     * 自己正在向 AE 网络供货的槽位刷新本地过滤缓存用——这类槽位必须遵守和其它样板总成同样的
     * "AE2身份只读"纪律，不能每次刷新都可能悄悄改写 NBT（AEItemKey 含 NBT，改动即变身份）。
     * 推断失败（配方表暂未就绪等）时保留现有 NBT 值而非清空，避免过滤条件被临时空标签放行一切。
     */
    public static String peekRecipeTypeId(ItemStack stack, Level level) {
        String existing = readRecipeTypeId(stack);
        if (stack == null || stack.isEmpty() || level == null) return existing;
        if (!existing.isEmpty() && isAuthoritative(stack)) return existing;
        GTRecipe recipe = peekRecipe(stack, level, existing);
        String result = recipe == null || recipe.recipeType == null || recipe.recipeType.registryName == null
                ? existing : recipe.recipeType.registryName.toString();
        return result;
    }

    /** 直接从已解码处理样板推断 GT 配方，供通配符展开后的动态样板使用。 */
    public static GTRecipe findRecipe(IPatternDetails details) {
        if (!(details instanceof AEProcessingPattern pattern)) return null;
        return VirtualPatternEncodingHelper.findMatchingRecipeForPattern(
                pattern.getSparseInputs(), pattern.getSparseOutputs());
    }

    public static void writeRecipeTypeFromPattern(ItemStack stack, GenericStack[] inputs, GenericStack[] outputs) {
        GTRecipe authoritativeRecipe = currentAuthoritativeEncodingRecipe();
        if (authoritativeRecipe != null) {
            writeAuthoritativeRecipeType(stack, authoritativeRecipe);
            return;
        }
        String encodingRecipeTypeId = currentEncodingRecipeTypeId();
        GTRecipeType encodingRecipeType = resolveRecipeType(encodingRecipeTypeId);
        if (encodingRecipeType != null) {
            writeAuthoritativeRecipeType(stack, encodingRecipeType);
            return;
        }
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
        return inferRecipe(stack, level, recipeTypeId, null);
    }

    private static void writeAuthoritativeRecipeType(ItemStack stack, GTRecipeType recipeType) {
        if (stack == null || stack.isEmpty() || recipeType == null) return;
        writeRecipeType(stack, recipeType);
        stack.getOrCreateTag().putBoolean(TAG_RECIPE_TYPE_AUTHORITATIVE, true);
    }

    private static GTRecipe inferRecipe(ItemStack stack, Level level, String recipeTypeId,
            GenericStack[] availableCatalystInputs) {
        try {
            IPatternDetails details = PatternDetailsHelper.decodePattern(stack, level);
            if (!(details instanceof AEProcessingPattern pattern)) {
                LOG.debug("[inferRecipe] decodePattern not AEProcessingPattern: {}",
                        details == null ? "null" : details.getClass().getName());
                return null;
            }
            GenericStack[] in = pattern.getSparseInputs();
            GenericStack[] out = pattern.getSparseOutputs();
            return recipeTypeId == null || recipeTypeId.isEmpty()
                    ? VirtualPatternEncodingHelper.findMatchingRecipeForPattern(in, out, availableCatalystInputs)
                    : VirtualPatternEncodingHelper.findMatchingRecipeForPattern(
                            in, out, recipeTypeId, availableCatalystInputs);
        } catch (RuntimeException e) {
            LOG.warn("[inferRecipe] parse exception", e);
            return null;
        }
    }
}
