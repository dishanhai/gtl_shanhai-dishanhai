package com.dishanhai.gt_shanhai.api;

import com.gregtechceu.gtceu.api.block.MetaMachineBlock;
import com.gregtechceu.gtceu.api.machine.MultiblockMachineDefinition;
import com.gregtechceu.gtceu.api.pattern.MultiblockShapeInfo;
import com.lowdragmc.lowdraglib.utils.BlockInfo;

import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.*;

/**
 * 多方块材料统计工具 — 从 MultiblockMachineDefinition 的图案中自动计算所需方块清单。
 * 同时为命令（/shanhai materials）和 KubeJS（DShanhaiJS.countMaterials）提供后端。
 */
public class DShanhaiMaterialCounter {

    public static class MaterialEntry {
        public final String itemId;
        public final String displayName;
        public final int count;

        MaterialEntry(String itemId, String displayName, int count) {
            this.itemId = itemId;
            this.displayName = displayName;
            this.count = count;
        }
    }

    /**
     * 统计一个多方块机器定义所需的全部结构方块材料。
     * 自动滤除仓室和跨变体重复位置（如线圈候选项）。
     */
    public static List<MaterialEntry> countMaterials(MultiblockMachineDefinition def) {
        Map<String, Integer> counter = new LinkedHashMap<>();
        Map<String, String> names = new HashMap<>();
        Set<String> seenPos = new HashSet<>(); // 跨变体位置去重

        List<MultiblockShapeInfo> shapes = def.getMatchingShapes();
        for (MultiblockShapeInfo shape : shapes) {
            BlockInfo[][][] blocks = shape.getBlocks();
            for (int z = 0; z < blocks.length; z++) {
                BlockInfo[][] layer = blocks[z];
                for (int y = 0; y < layer.length; y++) {
                    BlockInfo[] row = layer[y];
                    for (int x = 0; x < row.length; x++) {
                        String posKey = z + "," + y + "," + x;
                        if (!seenPos.add(posKey)) continue; // 已在上一个变体中计数

                        BlockInfo info = row[x];
                        if (info == null || info == BlockInfo.EMPTY) continue;
                        // 滤掉仓室（非多方块的单方块机器）
                        var state = info.getBlockState();
                        if (state != null && state.getBlock() instanceof MetaMachineBlock mb) {
                            if (!(mb.getDefinition() instanceof MultiblockMachineDefinition)) continue;
                        }
                        ItemStack stack = info.getItemStackForm();
                        if (stack == null || stack.isEmpty()) continue;
                        var id = ForgeRegistries.ITEMS.getKey(stack.getItem());
                        if (id == null) continue;
                        String key = id.toString();
                        if ("minecraft:air".equals(key)) continue;
                        counter.merge(key, 1, Integer::sum);
                        names.putIfAbsent(key, stack.getHoverName().getString());
                    }
                }
            }
        }

        List<MaterialEntry> result = new ArrayList<>();
        for (var entry : counter.entrySet()) {
            result.add(new MaterialEntry(entry.getKey(), names.get(entry.getKey()), entry.getValue()));
        }
        result.sort((a, b) -> Integer.compare(b.count, a.count));
        return result;
    }

    /** 彩色文本格式 */
    public static String formatMaterialList(String machineId, List<MaterialEntry> materials) {
        if (materials.isEmpty()) return "§c[山海] 未找到 " + machineId + " 的材料数据";
        var sb = new StringBuilder("§6===== §f").append(machineId).append(" §6材料清单 =====\n");
        int total = 0;
        for (var m : materials) {
            sb.append("§7").append(m.count).append("x §f").append(m.displayName)
                    .append(" §8(").append(m.itemId).append(")\n");
            total += m.count;
        }
        sb.append("§e总计: §f").append(total).append(" §e个方块, ").append(materials.size()).append(" §e种");
        return sb.toString();
    }

    /** FTB Quest 描述格式 */
    public static String toFTBQ(List<MaterialEntry> materials) {
        if (materials.isEmpty()) return "";
        var sb = new StringBuilder();
        for (var m : materials) {
            sb.append("&7").append(m.count).append("x &r").append(m.displayName).append("&r\\n");
        }
        return sb.toString();
    }

    /** Markdown 表格 + KJS 数组 + FTBQ 描述 */
    public static String toMarkdown(String machineId, List<MaterialEntry> materials) {
        if (materials.isEmpty()) return "未找到 " + machineId + " 的材料数据";
        var sb = new StringBuilder("## ").append(machineId).append("\n\n");
        sb.append("| 数量 | 物品 | ID |\n");
        sb.append("|-----|------|----|\n");
        int total = 0;
        for (var m : materials) {
            sb.append("| ").append(m.count).append(" | ").append(m.displayName)
                    .append(" | `").append(m.itemId).append("` |\n");
            total += m.count;
        }
        sb.append("\n**总计: ").append(total).append(" 个方块, ").append(materials.size()).append(" 种**\n\n");
        // KJS 可用数组
        sb.append("```js\n[");
        for (int i = 0; i < materials.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append("\"").append(materials.get(i).count).append("x ").append(materials.get(i).itemId).append("\"");
        }
        sb.append("]\n```\n\n");
        // FTBQ 描述
        sb.append("**FTBQ:**\n```\n").append(toFTBQ(materials)).append("```\n");
        return sb.toString();
    }

    /** 纯文本 */
    public static String toPlainText(String machineId, List<MaterialEntry> materials) {
        if (materials.isEmpty()) return "未找到 " + machineId + " 的材料数据";
        var sb = new StringBuilder("===== ").append(machineId).append(" 材料清单 =====\n");
        int total = 0;
        for (var m : materials) {
            sb.append(m.count).append("x ").append(m.displayName).append(" (").append(m.itemId).append(")\n");
            total += m.count;
        }
        sb.append("总计: ").append(total).append(" 个方块, ").append(materials.size()).append(" 种\n");
        return sb.toString();
    }
}
