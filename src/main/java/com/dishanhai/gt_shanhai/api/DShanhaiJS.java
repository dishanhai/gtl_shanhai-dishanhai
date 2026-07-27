package com.dishanhai.gt_shanhai.api;

import com.dishanhai.gt_shanhai.GTDishanhaiMod;
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
     * <p>
     * 原独立的星云边框渲染链路从未注册事件（休眠代码），已由 Halo 系统取代删除。
     * 本方法保留为 KubeJS 兼容入口，内部委托 {@link #makeHalo}：
     * 深空黑底暈 + 星云紫旋转星芒，视觉上近似原设计的"黑色星云旋转边框"。
     *
     * @param itemId 物品 ID，格式 "modid:item_name"
     * @deprecated 新脚本请直接使用 {@link #makeHalo(String, String, String, String)} 自定义样式
     */
    @Deprecated
    public static void makeCosmic(String itemId) {
        makeHalo(itemId, "halo+rays", "#050507", "#7B4FD8");
    }

    /**
     * 为物品添加 Avaritia 风格光环渲染（默认底暈样式 + 呼吸脉冲）。
     * GUI/地面/手持均生效，光环会溢出物品槽边界。
     * <p>
     * 用法（KubeJS startup_scripts 中）:
     * <pre>
     * DShanhaiJS.makeHalo("dishanhai:magmatter_coin", "#050507")
     * </pre>
     *
     * @param itemId       物品 ID，格式 "modid:item_name"
     * @param coreColorHex 底暈颜色 "#RRGGBB" / "#AARRGGBB"，"rainbow" 为彩虹循环
     */
    public static void makeHalo(String itemId, String coreColorHex) {
        makeHalo(itemId, "halo", coreColorHex, coreColorHex);
    }

    /**
     * 为物品添加指定样式的光环渲染。
     * <p>
     * 样式（可用 + 组合）: "halo" 底暈 / "ring" 冕环 / "eclipse" = halo+ring 日食 / "rays" 旋转星芒。
     * <pre>
     * DShanhaiJS.makeHalo("dishanhai:magmatter_coin", "eclipse", "#050507", "#FF7A1A")
     * DShanhaiJS.makeHalo("dishanhai:infinite_coin", "halo+rays", "#FFFFFF", "#FFFFFF")
     * </pre>
     *
     * @param itemId       物品 ID
     * @param styleSpec    样式串
     * @param coreColorHex 底暈颜色；"rainbow" 为彩虹循环
     * @param rimColorHex  冕环/星芒颜色；"rainbow" 为彩虹循环
     */
    public static void makeHalo(String itemId, String styleSpec, String coreColorHex, String rimColorHex) {
        makeHaloEx(itemId, styleSpec, coreColorHex, rimColorHex, 1.55D, true, 1400, 0.03D);
    }

    /**
     * 光环渲染完整参数版。
     *
     * @param itemId        物品 ID（仅适用平面物品模型 item/generated 类；方块物品与自定义渲染物品无效）
     * @param styleSpec     样式串，见 {@link #makeHalo(String, String, String, String)}
     * @param coreColorHex  底暈颜色；"rainbow" 为该通道彩虹循环（另一通道保持固定色）
     * @param rimColorHex   冕环/星芒颜色；"rainbow" 同上
     * @param scale         光环直径相对物品尺寸倍数（默认 1.55）
     * @param pulse         是否呼吸脉冲
     * @param pulsePeriodMs 脉冲周期毫秒（默认 1400；中子星风格可给 700）
     * @param rotateSpeed   星芒转速 转/秒，负数逆时针（默认 0.03）
     */
    public static void makeHaloEx(String itemId, String styleSpec, String coreColorHex, String rimColorHex,
                                  double scale, boolean pulse, int pulsePeriodMs, double rotateSpeed) {
        HaloItemRegistry.register(itemId, new HaloSettings(
                HaloSettings.parseStyle(styleSpec),
                HaloSettings.parseColor(coreColorHex),
                HaloSettings.parseColor(rimColorHex),
                (float) scale, pulse, pulsePeriodMs, (float) rotateSpeed,
                HaloSettings.isRainbow(coreColorHex), HaloSettings.isRainbow(rimColorHex)));
    }

    /**
     * 不稳定抖动：物品模型持续颤动，附带 RGB 色差残影（红/青错位描边）。
     * 可与光环叠加——对同一物品分别调用 makeHalo 与 makeShake 即可，参数按通道自动合并，顺序无关。
     * <p>
     * 模式: "quiver" 平滑高频颤抖 / "glitch" 故障式跳位（离散瞬跳+失稳尖峰，
     * 尖峰时位移放大、急促滚转、光环同步胀大）。
     * 抖动逐帧烘进模型 quad，背包/手持/地面/展示框/JEI 列表全部生效。
     *
     * @param itemId    物品 ID（仅平面物品模型，同 makeHalo 的限制）
     * @param mode      "quiver" / "glitch"
     * @param amplitude 抖动幅度，相对物品尺寸（建议 0.01~0.05；0.03 在 GUI 里约半像素）
     */
    public static void makeShake(String itemId, String mode, double amplitude) {
        int m = HaloSettings.parseShakeMode(mode);
        makeShakeEx(itemId, mode, amplitude, m == HaloSettings.SHAKE_GLITCH ? 110 : 90, amplitude);
    }

    /**
     * 不稳定抖动完整参数版。
     *
     * @param itemId    物品 ID
     * @param mode      "quiver" / "glitch"，见 {@link #makeShake(String, String, double)}
     * @param amplitude 抖动幅度，相对物品尺寸
     * @param periodMs  quiver=振动周期毫秒（默认 90，越小越急）；glitch=跳位保持间隔毫秒（默认 110）
     * @param chromaAmp RGB 色差残影错位幅度，相对物品尺寸；0 = 关闭残影
     */
    public static void makeShakeEx(String itemId, String mode, double amplitude, int periodMs, double chromaAmp) {
        HaloItemRegistry.register(itemId, HaloSettings.shakeOnly(
                HaloSettings.parseShakeMode(mode), (float) amplitude, periodMs, (float) chromaAmp));
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

