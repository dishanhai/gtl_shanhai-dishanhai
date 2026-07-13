package com.dishanhai.gt_shanhai.api;

import com.dishanhai.gt_shanhai.GTDishanhaiMod;
import com.dishanhai.gt_shanhai.api.CosmicItemRegistry;
import com.dishanhai.gt_shanhai.api.recipe.DShanhaiRecipeTypes;
import com.gregtechceu.gtceu.api.GTValues;
import com.gregtechceu.gtceu.api.machine.MultiblockMachineDefinition;
import com.gregtechceu.gtceu.api.registry.GTRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class DShanhaiJS {

    public static String getModId() {
        return GTDishanhaiMod.MOD_ID;
    }

    public static String getModName() {
        return GTDishanhaiMod.NAME;
    }

    public static int recipeTypeCount() {
        return 5;
    }

    /**
     * 将物品标记为宇宙渲染（Cosmic Effect）。
     * 物品在 GUI/世界中会显示黑色星云旋转边框。
     * <p>
     * 用法（KubeJS startup_scripts 中）:
     * <pre>
     * DShanhaiJS.makeCosmic("kubejs:my_item")
     * DShanhaiJS.makeCosmic("gtceu:infinity_ingot")
     * </pre>
     *
     * @param itemId 物品 ID，格式 "modid:item_name"
     */
    public static void makeCosmic(String itemId) {
        CosmicItemRegistry.markCosmic(itemId);
    }

    /**
     * 为物品设置动态显示名称。
     * 使用 & 格式化码在 displayName 中直接编写（推荐），此方法保留作为兼容。
     * <p>
     * KubeJS 推荐用法:
     * <pre>
     * event.create('my_item').displayName('&$ultimate-名字')
     * </pre>
     *
     * @param itemId 物品 ID，格式 "modid:item_name"
     * @param displayText 显示文本（含 & 格式化码）
     */
    public static void setDynamicName(String itemId, String displayText) {
        // 已废弃，仅保留兼容。displayName() + & 码是推荐方式。
    }

    /** 返回 GTValues.VA 电压值数组，替代 KubeJS 中重复的 Java.loadClass('GTValues') */
    public static int[] getVA() {
        return GTValues.VA;
    }

    // ===== 多方块材料统计 =====

    /**
     * 获取多方块机器的材料清单（格式化文本）。
     * 用法: DShanhaiJS.getMaterialText("gtceu:large_chemical_reactor")
     */
    public static String getMaterialText(String machineId) {
        var def = findMultiblock(machineId);
        if (def == null) return "未找到多方块机器: " + machineId;
        var materials = DShanhaiMaterialCounter.countMaterials(def);
        return DShanhaiMaterialCounter.formatMaterialList(machineId, materials);
    }

    /**
     * 获取多方块机器的材料 {物品ID: 数量} 映射表。
     * 用法: var m = DShanhaiJS.getMaterialMap("gtceu:electric_blast_furnace")
     *       for (var e : m.entrySet()) { console.log(e.key + " x" + e.value) }
     */
    public static Map<String, Integer> getMaterialMap(String machineId) {
        var def = findMultiblock(machineId);
        if (def == null) return new HashMap<>();
        var materials = DShanhaiMaterialCounter.countMaterials(def);
        Map<String, Integer> result = new HashMap<>();
        for (var m : materials) {
            result.put(m.itemId, m.count);
        }
        return result;
    }

    /**
     * 获取材料清单列表 [{itemId, displayName, count}...]。
     * 用法: var list = DShanhaiJS.getMaterialList("gtceu:assembly_line")
     *       list.forEach(e => console.log(e.count + "x " + e.displayName))
     */
    public static java.util.List<DShanhaiMaterialCounter.MaterialEntry> getMaterialList(String machineId) {
        var def = findMultiblock(machineId);
        if (def == null) return new java.util.ArrayList<>();
        return DShanhaiMaterialCounter.countMaterials(def);
    }

    /** 获取 FTB Quest 描述格式。 */
    public static String getFTBQ(String machineId) {
        var list = getMaterialList(machineId);
        return DShanhaiMaterialCounter.toFTBQ(list);
    }

    /** 获取材料清单为 [count x itemId, ...] 数组，方便 KJS 直接遍历。 */
    public static String[] getMaterialArray(String machineId) {
        var list = getMaterialList(machineId);
        String[] arr = new String[list.size()];
        for (int i = 0; i < list.size(); i++) {
            var m = list.get(i);
            arr[i] = m.count + "x " + m.itemId;
        }
        return arr;
    }

    // ===== 物品表按后缀/命名空间分桶 =====

    /**
     * 按物品 ID 路径后缀对全部已注册物品分桶，一次遍历 ForgeRegistries.ITEMS，按命名空间分组返回。
     * <p>
     * 用于替代 KubeJS 里"每个候选都对全物品表重新做一次 Ingredient.of(regex).getItemIds() 全量扫描"
     * 的 O(候选数 × 全物品数) 反模式——改成先调用本方法一次性建好索引，再按命名空间查表。
     * <p>
     * 用法（KubeJS server_scripts 中）:
     * <pre>
     * var buckets = Java.loadClass('com.dishanhai.gt_shanhai.api.DShanhaiJS')
     *     .groupItemIdsBySuffix(['_dust', '_ingot', '_crystal', '_gem']);
     * var candidates = buckets.get('gtceu'); // Java List&lt;String&gt; 或 null
     * if (candidates != null) candidates.forEach(function(id) { ... });
     * </pre>
     *
     * @param suffixes 物品路径后缀列表，如 ["_dust", "_ingot"]
     * @return {命名空间: [物品ID...]} 映射，只包含路径以任一后缀结尾的物品；suffixes 为空时返回空 map
     */
    public static Map<String, List<String>> groupItemIdsBySuffix(String[] suffixes) {
        Map<String, List<String>> result = new LinkedHashMap<>();
        if (suffixes == null || suffixes.length == 0) return result;
        for (ResourceLocation id : ForgeRegistries.ITEMS.getKeys()) {
            String path = id.getPath();
            boolean matched = false;
            for (String suffix : suffixes) {
                if (suffix != null && path.endsWith(suffix)) {
                    matched = true;
                    break;
                }
            }
            if (!matched) continue;
            result.computeIfAbsent(id.getNamespace(), k -> new ArrayList<>()).add(id.toString());
        }
        return result;
    }

    private static MultiblockMachineDefinition findMultiblock(String machineId) {
        var def = GTRegistries.MACHINES.get(new ResourceLocation(machineId));
        if (def == null) {
            // try gtceu: prefix
            def = GTRegistries.MACHINES.get(new ResourceLocation("gtceu", machineId));
        }
        if (def instanceof MultiblockMachineDefinition mdef) return mdef;
        return null;
    }
}

