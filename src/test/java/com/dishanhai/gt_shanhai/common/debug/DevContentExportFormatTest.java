package com.dishanhai.gt_shanhai.common.debug;

import net.minecraft.resources.ResourceLocation;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 開發者匯出的純格式層：分組、A-Z 排序、欄位順序與 Markdown 轉義。
 */
class DevContentExportFormatTest {

    @Test
    void contentsAreGroupedByNamespaceAndSortedById() {
        Map<String, List<DevContentExport.ItemRow>> items = new LinkedHashMap<>();
        items.put("gtceu", rows(
                new DevContentExport.ItemRow(id("gtceu:zinc_ingot"), "鋅錠", List.of("forge:ingots/zinc")),
                new DevContentExport.ItemRow(id("gtceu:iron_ingot"), "鐵錠",
                        List.of("forge:ingots/iron", "c:ingots/iron", "extra:third"))));
        items.put("kubejs", rows(
                new DevContentExport.ItemRow(id("kubejs:b_item"), "乙", List.of()),
                new DevContentExport.ItemRow(id("kubejs:a_item"), "甲", List.of("kubejs:tag"))));

        String markdown = DevContentExport.renderContents(items, Map.of());

        assertTrue(markdown.contains("### gtceu（2）"));
        assertTrue(markdown.contains("### kubejs（2）"));
        assertTrue(markdown.indexOf("### gtceu") < markdown.indexOf("### kubejs"), "分組需按模組排序");
        assertTrue(markdown.indexOf("gtceu:iron_ingot") < markdown.indexOf("gtceu:zinc_ingot"),
                "組內需按 ID 字母排序");
        assertTrue(markdown.indexOf("kubejs:a_item") < markdown.indexOf("kubejs:b_item"),
                "組內需按 ID 字母排序");
    }

    @Test
    void itemTableKeepsChineseNameEnglishIdAndFirstTwoTags() {
        Map<String, List<DevContentExport.ItemRow>> items = new LinkedHashMap<>();
        items.put("gtceu", rows(new DevContentExport.ItemRow(id("gtceu:iron_ingot"), "鐵錠",
                List.of("forge:ingots/iron", "c:ingots/iron", "extra:third"))));

        String markdown = DevContentExport.renderContents(items, Map.of());

        assertTrue(markdown.contains("| 中文名 | 英文 ID | 标签 1 | 标签 2 |"));
        assertTrue(markdown.contains("| 鐵錠 | gtceu:iron_ingot | forge:ingots/iron | c:ingots/iron |"));
        assertTrue(markdown.contains("物品 1 项，流体 0 项"));
    }

    @Test
    void recipeTableKeepsIdMachineTypeAndFirstTwoInputsOutputs() {
        Map<String, List<DevContentExport.RecipeRow>> groups = new LinkedHashMap<>();
        groups.put("gtceu", rows(
                new DevContentExport.RecipeRow(id("gtceu:assembler/b"),
                        "gtceu:assembler",
                        List.of("1x gtceu:iron_ingot", "2x gtceu:tin_ingot", "3x gtceu:extra"),
                        List.of("1x gtceu:motor")),
                new DevContentExport.RecipeRow(id("gtceu:assembler/a"),
                        "gtceu:assembler",
                        List.of("1x gtceu:copper_ingot"),
                        List.of("1x gtceu:pump", "2x gtceu:wire"))));

        String markdown = DevContentExport.renderRecipes(groups, 2);

        assertTrue(markdown.contains("### gtceu（2）"));
        assertTrue(markdown.contains("| 配方 ID | 机器类型 | 输入 1 | 输入 2 | 输出 1 | 输出 2 |"));
        assertTrue(markdown.indexOf("gtceu:assembler/a") < markdown.indexOf("gtceu:assembler/b"),
                "組內需按配方 ID 字母排序");
        assertTrue(markdown.contains(
                "| gtceu:assembler/a | gtceu:assembler | 1x gtceu:copper_ingot |  | 1x gtceu:pump | 2x gtceu:wire |"));
        assertTrue(markdown.contains("共 2 个配方"));
    }

    @Test
    void pipeCharactersAreEscapedSoColumnsStayIntact() {
        Map<String, List<DevContentExport.ItemRow>> items = new LinkedHashMap<>();
        items.put("gtceu", rows(new DevContentExport.ItemRow(id("gtceu:odd"), "甲|乙", List.of())));

        String markdown = DevContentExport.renderContents(items, Map.of());

        assertTrue(markdown.contains("| 甲\\|乙 | gtceu:odd |  |  |"));
        String dataLine = markdown.lines()
                .filter(line -> line.startsWith("| 甲"))
                .findFirst()
                .orElseThrow();
        assertTrue(dataLine.contains("\\|"), "資料內的管線應轉義成 \\|");
        long separators = dataLine.replace("\\|", "").chars().filter(ch -> ch == '|').count();
        assertEquals(5, separators, "移除轉義管線後應剩 5 個欄位分隔線，維持 4 欄");
    }

    private static ResourceLocation id(String value) {
        return new ResourceLocation(value);
    }

    @SafeVarargs
    private static <T> List<T> rows(T... values) {
        List<T> list = new ArrayList<>();
        for (T value : values) list.add(value);
        return list;
    }
}
