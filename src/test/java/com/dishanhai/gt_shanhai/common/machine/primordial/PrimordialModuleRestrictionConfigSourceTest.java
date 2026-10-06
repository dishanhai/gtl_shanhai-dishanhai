package com.dishanhai.gt_shanhai.common.machine.primordial;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class PrimordialModuleRestrictionConfigSourceTest {

    private static final Path CONFIG = Path.of("src", "main", "java", "com", "dishanhai",
            "gt_shanhai", "config", "DShanhaiConfig.java");
    private static final Path MOD = Path.of("src", "main", "java", "com", "dishanhai",
            "gt_shanhai", "GTDishanhaiMod.java");
    private static final Path CONFIG_SCREEN = Path.of("src", "main", "java", "com", "dishanhai",
            "gt_shanhai", "client", "config", "DShanhaiConfigScreen.java");
    private static final Path LOGIC = Path.of("src", "main", "java", "com", "dishanhai",
            "gt_shanhai", "common", "machine", "primordial", "PrimordialModuleRecipeLogic.java");

    @Test
    void unrestrictedModeLivesInDShanhaiConfigAndIsDisabledByDefault() throws IOException {
        String config = Files.readString(CONFIG);
        String mod = Files.readString(MOD);
        String screen = Files.readString(CONFIG_SCREEN);

        assertTrue(config.contains("unrestrictedMode"),
                "无限制模式开关必须注册在已存在的 DShanhaiConfig");
        assertTrue(config.contains("define(\"unrestrictedMode\", false)"),
                "原初模块无限制模式必须默认关闭");
        assertTrue(!mod.contains("PrimordialModuleConfig"),
                "不应额外注册第二个配置类");
        assertTrue(!mod.contains("primordial-module.toml"),
                "不应额外生成 primordial-module.toml");
        assertTrue(screen.contains("cfg.primordialModuleUnrestrictedMode.get()"),
                "无限制模式必须显示在 Cloth Config 界面");
        assertTrue(screen.contains("cfg.primordialModuleUnrestrictedMode::set"),
                "Cloth Config 必须能保存无限制模式开关");
    }

    @Test
    void normalLogicDoesNotUseResearchAsAnExtraMountGate() throws IOException {
        String logic = Files.readString(LOGIC);

        assertTrue(logic.contains("DShanhaiConfig.COMMON.primordialModuleUnrestrictedMode.get()"),
                "原初逻辑必须读取独立限制配置");
        assertTrue(logic.contains("isRequirementIgnored"),
                "忽略条件必须走集中策略，而不是散落硬编码");
        assertTrue(logic.contains("shanhai$requirementOf(condition)"),
                "研究条件必须经过统一条件解析策略");
    }

    @Test
    void heatGateUsesTheCandidateRecipeTypeInsteadOfTheSelectionWrapper() throws IOException {
        String logic = Files.readString(LOGIC);

        assertTrue(logic.contains("GTRecipeType type = recipe.recipeType;"),
                "热力限制必须按当前候选配方类型判断，不能读取 selectable_recipe_type_set 包装类型");
    }
}
