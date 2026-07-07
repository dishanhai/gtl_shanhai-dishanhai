package com.dishanhai.gt_shanhai.api;

import com.gregtechceu.gtceu.api.capability.recipe.FluidRecipeCapability;
import com.gregtechceu.gtceu.api.capability.recipe.ItemRecipeCapability;
import com.gregtechceu.gtceu.api.capability.recipe.RecipeCapability;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import com.gregtechceu.gtceu.api.recipe.content.Content;
import com.gregtechceu.gtceu.api.recipe.ingredient.FluidIngredient;
import com.gregtechceu.gtceu.api.recipe.ingredient.SizedIngredient;
import com.gregtechceu.gtceu.api.registry.GTRegistries;
import com.gregtechceu.gtceu.common.item.IntCircuitBehaviour;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;

import com.lowdragmc.lowdraglib.side.fluid.FluidStack;

import java.util.*;
import java.util.regex.Pattern;

/**
 * 配方修改 KJS API — 支持正则匹配的批量配方剥离/替换/删除。
 * 通过 {@code global.ShanhaiRecipes} 在 KubeJS server_scripts 中调用。
 */
public class DShanhaiRecipeKJSAPI {

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger("ShanhaiKJS");

    // ============================
    //  批量剥离 — 正则匹配
    // ============================

    /**
     * 剥离配方类型中所有匹配正则的物品（输入+输出）。
     * @param recipeType 配方类型，如 "gtceu:assembler"
     * @param itemRegex  物品 ID 正则，如 "gtceu:.*glass_lens"
     * @return 受影响的配方数
     */
    public static int strip(String recipeType, String itemRegex) {
        return stripInternal(recipeType, itemRegex, false, false, "");
    }

    /**
     * 剥离配方类型中匹配正则的物品/流体。
     * @param recipeType 配方类型
     * @param itemRegex  物品/流体 ID 正则
     * @param isFluid    是否为流体
     * @return 受影响的配方数
     */
    public static int strip(String recipeType, String itemRegex, boolean isFluid) {
        return stripInternal(recipeType, itemRegex, isFluid, false, "");
    }

    /**
     * 剥离指定配方 ID 匹配正则的物品。
     * @param recipeType 配方类型
     * @param itemRegex  物品 ID 正则
     * @param recipeRegex 配方 ID 正则（只剥离匹配的配方）
     * @return 受影响的配方数
     */
    public static int stripMatching(String recipeType, String itemRegex, String recipeRegex) {
        return stripInternal(recipeType, itemRegex, false, false, recipeRegex);
    }

    /**
     * 剥离配方类型中匹配正则的流体（仅输入）。
     * @param recipeType 配方类型
     * @param fluidRegex 流体 ID 正则
     * @return 受影响的配方数
     */
    public static int stripFluidInput(String recipeType, String fluidRegex) {
        return stripInternal(recipeType, fluidRegex, true, true, "");
    }

    // ============================
    //  批量替换 — 正则匹配
    // ============================

    /**
     * 将配方中匹配正则的物品替换为另一个物品。
     * @param recipeType 配方类型
     * @param oldRegex   旧物品 ID 正则
     * @param newItem    新物品 ID，如 "gtceu:programmed_circuit"
     * @return 受影响的配方数
     */
    public static int replace(String recipeType, String oldRegex, String newItem) {
        return replaceInternal(recipeType, oldRegex, newItem, -1, "", false);
    }

    /**
     * 将匹配正则的物品替换为编程电路。
     * @param recipeType    配方类型
     * @param oldRegex      旧物品 ID 正则
     * @param circuitNumber 电路号 (0-32)
     * @return 受影响的配方数
     */
    public static int replaceWithCircuit(String recipeType, String oldRegex, int circuitNumber) {
        return replaceInternal(recipeType, oldRegex, "gtceu:programmed_circuit", circuitNumber, "", false);
    }

    /**
     * 在指定配方 ID 匹配的配方中替换物品。
     * @param recipeType  配方类型
     * @param oldRegex    旧物品 ID 正则
     * @param newItem     新物品 ID
     * @param recipeRegex 配方 ID 正则
     * @return 受影响的配方数
     */
    public static int replaceMatching(String recipeType, String oldRegex, String newItem, String recipeRegex) {
        return replaceInternal(recipeType, oldRegex, newItem, -1, recipeRegex, false);
    }

    /**
     * 将匹配正则的流体替换为另一个流体。
     * @param recipeType  配方类型
     * @param oldRegex    旧流体 ID 正则
     * @param newFluid    新流体 ID
     * @return 受影响的配方数
     */
    public static int replaceFluid(String recipeType, String oldRegex, String newFluid) {
        return replaceInternal(recipeType, oldRegex, newFluid, -1, "", true);
    }

    // ============================
    //  批量删除配方 — 正则匹配
    // ============================

    /**
     * 删除配方 ID 匹配正则的配方。
     * @param recipeType  配方类型
     * @param recipeRegex 配方 ID 正则
     * @return 删除的配方数
     */
    public static int deleteMatching(String recipeType, String recipeRegex) {
        var type = GTRegistries.RECIPE_TYPES.get(new ResourceLocation(recipeType));
        if (type == null) { LOG.warn("[KJS] 配方类型未找到: {}", recipeType); return 0; }
        var lookup = type.getLookup();
        if (lookup == null) return 0;
        var branch = lookup.getLookup();
        if (branch == null) return 0;

        Pattern p = Pattern.compile(recipeRegex);
        List<GTRecipe> all = new ArrayList<>();
        branch.getRecipes(true).forEach(r -> { if (r != null) all.add(r); });

        List<GTRecipe> keep = new ArrayList<>();
        int deleted = 0;
        for (GTRecipe r : all) {
            String rid = r.getId() != null ? r.getId().toString() : "";
            if (p.matcher(rid).find()) {
                deleted++;
            } else {
                keep.add(r);
            }
        }

        if (deleted > 0) {
            lookup.removeAllRecipes();
            for (GTRecipe r : keep) lookup.addRecipe(r);
            DShanhaiRecipeModifierAPI.saveStripRules();
            com.dishanhai.gt_shanhai.network.RecipeSyncPacket.syncToAll();
        }
        LOG.info("[KJS] 删除完成: {} 正则=[{}] → 删除 {} 个配方", recipeType, recipeRegex, deleted);
        return deleted;
    }

    /**
     * 删除配方类型中产出匹配正则的所有配方。
     * @param recipeType  配方类型
     * @param outputRegex 产出物品 ID 正则
     * @return 删除的配方数
     */
    public static int deleteByOutput(String recipeType, String outputRegex) {
        var type = GTRegistries.RECIPE_TYPES.get(new ResourceLocation(recipeType));
        if (type == null) { LOG.warn("[KJS] 配方类型未找到: {}", recipeType); return 0; }
        var lookup = type.getLookup();
        if (lookup == null) return 0;
        var branch = lookup.getLookup();
        if (branch == null) return 0;

        Pattern p = Pattern.compile(outputRegex);
        List<GTRecipe> all = new ArrayList<>();
        branch.getRecipes(true).forEach(r -> { if (r != null) all.add(r); });

        List<GTRecipe> keep = new ArrayList<>();
        int deleted = 0;
        for (GTRecipe r : all) {
            boolean match = false;
            if (r.outputs != null) {
                for (List<Content> list : r.outputs.values()) {
                    if (list == null) continue;
                    for (Content c : list) {
                        if (matchesContentRegex(c, false, p)) { match = true; break; }
                    }
                    if (match) break;
                }
            }
            if (match) { deleted++; } else { keep.add(r); }
        }

        if (deleted > 0) {
            lookup.removeAllRecipes();
            for (GTRecipe r : keep) lookup.addRecipe(r);
            DShanhaiRecipeModifierAPI.saveStripRules();
            com.dishanhai.gt_shanhai.network.RecipeSyncPacket.syncToAll();
        }
        LOG.info("[KJS] 按产出删除: {} 正则=[{}] → 删除 {} 个配方", recipeType, outputRegex, deleted);
        return deleted;
    }

    // ============================
    //  实用查询
    // ============================

    /**
     * 列出配方类型中所有配方 ID。
     * @param recipeType 配方类型
     * @return 配方 ID 数组
     */
    public static String[] listRecipeIds(String recipeType) {
        var type = GTRegistries.RECIPE_TYPES.get(new ResourceLocation(recipeType));
        if (type == null) return new String[0];
        var lookup = type.getLookup();
        if (lookup == null) return new String[0];
        var branch = lookup.getLookup();
        if (branch == null) return new String[0];

        List<String> ids = new ArrayList<>();
        branch.getRecipes(true).forEach(r -> {
            if (r != null && r.getId() != null) ids.add(r.getId().toString());
        });
        return ids.toArray(new String[0]);
    }

    /**
     * 列出配方类型中所有配方 ID 匹配正则的配方。
     * @param recipeType 配方类型
     * @param regex      配方 ID 正则
     * @return 匹配的配方 ID 数组
     */
    public static String[] findRecipeIds(String recipeType, String regex) {
        var type = GTRegistries.RECIPE_TYPES.get(new ResourceLocation(recipeType));
        if (type == null) return new String[0];
        var lookup = type.getLookup();
        if (lookup == null) return new String[0];
        var branch = lookup.getLookup();
        if (branch == null) return new String[0];

        Pattern p = Pattern.compile(regex);
        List<String> matched = new ArrayList<>();
        branch.getRecipes(true).forEach(r -> {
            if (r == null || r.getId() == null) return;
            String rid = r.getId().toString();
            if (p.matcher(rid).find()) matched.add(rid);
        });
        return matched.toArray(new String[0]);
    }

    /**
     * 列出配方类型中消耗/产出匹配正则的所有输入物品。
     * @param recipeType 配方类型
     * @param itemRegex  物品 ID 正则
     * @param inputSide  true=输入侧, false=输出侧
     * @return 各配方中匹配的物品 ID（去重）
     */
    public static String[] findItems(String recipeType, String itemRegex, boolean inputSide) {
        var type = GTRegistries.RECIPE_TYPES.get(new ResourceLocation(recipeType));
        if (type == null) return new String[0];
        var lookup = type.getLookup();
        if (lookup == null) return new String[0];
        var branch = lookup.getLookup();
        if (branch == null) return new String[0];

        Pattern p = Pattern.compile(itemRegex);
        Set<String> found = new LinkedHashSet<>();
        branch.getRecipes(true).forEach(r -> {
            if (r == null) return;
            @SuppressWarnings("unchecked")
            Map<RecipeCapability<?>, List<Content>>[] maps = inputSide
                ? new Map[]{r.inputs, r.tickInputs}
                : new Map[]{r.outputs, r.tickOutputs};
            for (Map<RecipeCapability<?>, List<Content>> map : maps) {
                if (map == null) continue;
                for (List<Content> list : map.values()) {
                    if (list == null) continue;
                    for (Content c : list) {
                        String id = getContentId(c, false);
                        if (!id.isEmpty() && p.matcher(id).find()) found.add(id);
                        String fid = getContentId(c, true);
                        if (!fid.isEmpty() && p.matcher(fid).find()) found.add(fid);
                    }
                }
            }
        });
        return found.toArray(new String[0]);
    }

    // ============================
    //  持久化管理
    // ============================

    /** 保存当前规则到文件 */
    public static void save() {
        DShanhaiRecipeModifierAPI.saveStripRules();
        DShanhaiRecipeModifierAPI.saveReplaceRules();
    }

    /** 从文件重载规则 */
    public static void reload() {
        DShanhaiRecipeModifierAPI.reloadStripRules();
    }

    /** 清除所有规则 */
    public static void clearAll() {
        DShanhaiRecipeModifierAPI.clearAll();
    }

    // ============================
    //  内部实现
    // ============================

    private static int stripInternal(String recipeType, String regex, boolean isFluid, boolean inputOnly, String recipeRegex) {
        var type = GTRegistries.RECIPE_TYPES.get(new ResourceLocation(recipeType));
        if (type == null) { LOG.warn("[KJS] 配方类型未找到: {}", recipeType); return 0; }
        var lookup = type.getLookup();
        if (lookup == null) return 0;
        var branch = lookup.getLookup();
        if (branch == null) return 0;

        Pattern itemPat = Pattern.compile(regex);
        Pattern recipePat = recipeRegex.isEmpty() ? null : Pattern.compile(recipeRegex);

        List<GTRecipe> all = new ArrayList<>();
        branch.getRecipes(true).forEach(r -> { if (r != null) all.add(r); });

        int affected = 0;
        List<GTRecipe> result = new ArrayList<>();
        for (GTRecipe recipe : all) {
            String rid = recipe.getId() != null ? recipe.getId().toString() : "";
            if (recipePat != null && !recipePat.matcher(rid).find()) {
                result.add(recipe);
                continue;
            }
            GTRecipe copy = recipe.copy();
            boolean modified = false;
            if (inputOnly) {
                modified |= stripMap(copy.inputs, isFluid, itemPat);
                modified |= stripMap(copy.tickInputs, isFluid, itemPat);
            } else {
                modified |= stripMap(copy.inputs, isFluid, itemPat);
                modified |= stripMap(copy.outputs, isFluid, itemPat);
                modified |= stripMap(copy.tickInputs, isFluid, itemPat);
                modified |= stripMap(copy.tickOutputs, isFluid, itemPat);
            }
            result.add(copy);
            if (modified) affected++;
        }

        if (affected > 0) {
            lookup.removeAllRecipes();
            for (GTRecipe r : result) lookup.addRecipe(r);
            DShanhaiRecipeModifierAPI.addStripRule(recipeType,
                new DShanhaiRecipeModifierAPI.StripEntry(regex, !inputOnly, isFluid, recipeRegex));
            com.dishanhai.gt_shanhai.network.RecipeSyncPacket.syncToAll();
        }
        LOG.info("[KJS] 剥离完成: {} 正则=[{}] fluid={} → {} 个配方", recipeType, regex, isFluid, affected);
        return affected;
    }

    private static boolean stripMap(Map<RecipeCapability<?>, List<Content>> map, boolean isFluid, Pattern p) {
        RecipeCapability<?> cap = isFluid ? FluidRecipeCapability.CAP : ItemRecipeCapability.CAP;
        var contents = map.get(cap);
        if (contents == null) return false;
        List<Content> mutable;
        if (contents instanceof com.google.common.collect.ImmutableCollection) {
            mutable = new ArrayList<>(contents);
            map.put(cap, mutable);
        } else {
            mutable = contents;
        }
        int before = mutable.size();
        mutable.removeIf(c -> matchesContentRegex(c, isFluid, p));
        return mutable.size() != before;
    }

    private static int replaceInternal(String recipeType, String oldRegex, String newItem,
                                        int circuitNumber, String recipeRegex, boolean isFluid) {
        var type = GTRegistries.RECIPE_TYPES.get(new ResourceLocation(recipeType));
        if (type == null) { LOG.warn("[KJS] 配方类型未找到: {}", recipeType); return 0; }
        var lookup = type.getLookup();
        if (lookup == null) return 0;
        var branch = lookup.getLookup();
        if (branch == null) return 0;

        Pattern oldPat = Pattern.compile(oldRegex);
        Pattern recipePat = recipeRegex.isEmpty() ? null : Pattern.compile(recipeRegex);

        List<GTRecipe> all = new ArrayList<>();
        branch.getRecipes(true).forEach(r -> { if (r != null) all.add(r); });

        int affected = 0;
        List<GTRecipe> result = new ArrayList<>();
        for (GTRecipe recipe : all) {
            String rid = recipe.getId() != null ? recipe.getId().toString() : "";
            if (recipePat != null && !recipePat.matcher(rid).find()) {
                result.add(recipe);
                continue;
            }
            GTRecipe copy = recipe.copy();
            boolean modified = false;
            modified |= replaceInMap(copy.inputs,     oldPat, newItem, isFluid, circuitNumber);
            modified |= replaceInMap(copy.outputs,    oldPat, newItem, isFluid, circuitNumber);
            modified |= replaceInMap(copy.tickInputs,  oldPat, newItem, isFluid, circuitNumber);
            modified |= replaceInMap(copy.tickOutputs, oldPat, newItem, isFluid, circuitNumber);
            result.add(copy);
            if (modified) affected++;
        }

        if (affected > 0) {
            lookup.removeAllRecipes();
            for (GTRecipe r : result) lookup.addRecipe(r);
            DShanhaiRecipeModifierAPI.addReplaceRule(recipeType,
                new DShanhaiRecipeModifierAPI.ReplaceEntry(oldRegex, newItem, isFluid, isFluid, recipeRegex, 0, circuitNumber));
            com.dishanhai.gt_shanhai.network.RecipeSyncPacket.syncToAll();
        }
        LOG.info("[KJS] 替换完成: {} 正则=[{}] → [{}] circuit={} → {} 个配方",
                recipeType, oldRegex, newItem, circuitNumber, affected);
        return affected;
    }

    private static boolean replaceInMap(Map<RecipeCapability<?>, List<Content>> map,
                                         Pattern oldPat, String newItem, boolean isFluid, int circuitNumber) {
        RecipeCapability<?> cap = isFluid ? FluidRecipeCapability.CAP : ItemRecipeCapability.CAP;
        var contents = map.get(cap);
        if (contents == null) return false;
        boolean changed = false;
        for (Content c : contents) {
            if (isFluid) {
                if (c.content instanceof FluidIngredient fi) {
                    var stacks = fi.getStacks();
                    if (stacks.length > 0) {
                        String id = ForgeRegistries.FLUIDS.getKey(stacks[0].getFluid()).toString();
                        if (oldPat.matcher(id).find()) {
                            var fluid = ForgeRegistries.FLUIDS.getValue(new ResourceLocation(newItem));
                            if (fluid != null) {
                                fi.stacks[0] = FluidStack.create(fluid, fi.getAmount(), fi.getNbt());
                                changed = true;
                            }
                        }
                    }
                }
            } else {
                if (c.content instanceof SizedIngredient si) {
                    var stacks = si.getItems();
                    if (stacks.length > 0) {
                        String id = ForgeRegistries.ITEMS.getKey(stacks[0].getItem()).toString();
                        if (oldPat.matcher(id).find()) {
                            ItemStack newStack;
                            if (circuitNumber >= 0) {
                                newStack = IntCircuitBehaviour.stack(circuitNumber);
                                newStack.setCount(Math.max(1, stacks[0].getCount()));
                            } else {
                                var item = ForgeRegistries.ITEMS.getValue(new ResourceLocation(newItem));
                                if (item == null) continue;
                                newStack = new ItemStack(item, stacks[0].getCount());
                            }
                            try { c.content = SizedIngredient.create(newStack); changed = true; }
                            catch (Exception ignored) {}
                        }
                    }
                }
                if (c.content instanceof ItemStack is) {
                    String id = ForgeRegistries.ITEMS.getKey(is.getItem()).toString();
                    if (oldPat.matcher(id).find()) {
                        ItemStack newStack;
                        if (circuitNumber >= 0) {
                            newStack = IntCircuitBehaviour.stack(circuitNumber);
                            newStack.setCount(Math.max(1, is.getCount()));
                        } else {
                            var item = ForgeRegistries.ITEMS.getValue(new ResourceLocation(newItem));
                            if (item == null) continue;
                            newStack = new ItemStack(item, is.getCount());
                        }
                        c.content = newStack;
                        changed = true;
                    }
                }
            }
        }
        return changed;
    }

    private static boolean matchesContentRegex(Content c, boolean isFluid, Pattern p) {
        String id = getContentId(c, isFluid);
        return !id.isEmpty() && p.matcher(id).find();
    }

    private static String getContentId(Content c, boolean isFluid) {
        if (c == null || c.content == null) return "";
        if (isFluid) {
            if (c.content instanceof FluidIngredient fi) {
                var stacks = fi.getStacks();
                if (stacks.length > 0) return ForgeRegistries.FLUIDS.getKey(stacks[0].getFluid()).toString();
            }
            if (c.content instanceof FluidStack fs) {
                return ForgeRegistries.FLUIDS.getKey(fs.getFluid()).toString();
            }
        } else {
            if (c.content instanceof SizedIngredient si) {
                var items = si.getItems();
                if (items.length > 0) return ForgeRegistries.ITEMS.getKey(items[0].getItem()).toString();
            }
            if (c.content instanceof ItemStack is) {
                return ForgeRegistries.ITEMS.getKey(is.getItem()).toString();
            }
        }
        return "";
    }
}
