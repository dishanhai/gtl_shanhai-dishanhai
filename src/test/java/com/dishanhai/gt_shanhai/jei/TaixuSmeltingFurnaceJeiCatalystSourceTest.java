package com.dishanhai.gt_shanhai.jei;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class TaixuSmeltingFurnaceJeiCatalystSourceTest {

    private static final Path JEI_PLUGIN = Path.of("src", "main", "java", "com", "dishanhai",
            "gt_shanhai", "jei", "ShanhaiJEIPlugin.java");

    @Test
    void taixuFurnaceIsVanillaSmeltingCatalystLikeHelioflareForge() throws IOException {
        String source = Files.readString(JEI_PLUGIN);

        assertTrue(source.contains("void registerRecipeCatalysts(IRecipeCatalystRegistration registration)"),
                "太虚必须走 JEI catalyst 注册链路，而不是只声明 GTCEu FURNACE_RECIPES");
        assertTrue(source.contains("DShanhaiMachines.TAIXU_SMELTING_FURNACE.asStack()"),
                "JEI 原版烧炼分类必须显示太虚方块本身");
        assertTrue(source.contains("RecipeTypes.SMELTING"),
                "普通熔炉配方属于 JEI 原版 RecipeTypes.SMELTING 分类");
        assertTrue(source.contains("LDLib.isReiLoaded() || LDLib.isEmiLoaded()"),
                "保持与恒星烈焰能量煅炉同样的 JEI/REI/EMI 互斥保护");
    }
}
