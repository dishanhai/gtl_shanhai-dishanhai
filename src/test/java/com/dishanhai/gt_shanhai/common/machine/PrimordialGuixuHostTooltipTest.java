package com.dishanhai.gt_shanhai.common.machine;

// Author: dishanhai
import com.dishanhai.gt_shanhai.config.DShanhaiConfig;
import com.electronwill.nightconfig.core.CommentedConfig;
import com.google.gson.JsonParser;
import com.gregtechceu.gtceu.api.machine.MachineDefinition;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 山海：保留各模組既有說明，並覆蓋預設清單及配置切換。 */
class PrimordialGuixuHostTooltipTest {
    private static final String FIRST = "gt_shanhai.machine.primordial_omega_engine.guixu_host_tooltip.1";
    private static final String SECOND = "gt_shanhai.machine.primordial_omega_engine.guixu_host_tooltip.2";
    private static final List<String> MACHINES = List.of(
            "PRIMORDIAL_WEIYANG_RECONSTRUCTION_MODULE", "PRIMORDIAL_SHAOGUANG_AGGREGATION_CORE",
            "PRIMORDIAL_ETERNAL_SMELTING_FURNACE", "PRIMORDIAL_TIANQIONG_ASSEMBLY_CORE",
            "PRIMORDIAL_UNIVERSE_SIMULATION_CORE", "PRIMORDIAL_QUANTUM_DISTORTION_MATRIX",
            "PRIMORDIAL_MULTIDIMENSIONAL_IMPLOSION_CORE", "PRIMORDIAL_SUPERCRITICAL_MATTER_GENERATION_CORE",
            "PRIMORDIAL_CAUSAL_WEAVING_MATRIX", "PRIMORDIAL_SINGULARITY_INVERSION_CORE");

    @BeforeEach void loadConfig() { DShanhaiConfig.COMMON_SPEC.setConfig(CommentedConfig.inMemory()); }
    @AfterEach void unloadConfig() { DShanhaiConfig.COMMON_SPEC.setConfig(null); }

    @Test void everyDefaultModuleCallsTheSharedTooltipFromItsExistingBuilder() throws Exception {
        String source = Files.readString(Path.of("src/main/java/com/dishanhai/gt_shanhai/common/machine/DShanhaiMachines.java"));
        for (String machine : MACHINES) {
            int start = source.indexOf(machine + ".setTooltipBuilder(");
            assertTrue(start >= 0, machine + " 缺少原有 tooltip");
            int end = source.indexOf("});", start);
            assertTrue(end > start, machine);
            assertTrue(source.substring(start, end).contains("appendGuixuHostTooltip(" + machine + ", tooltips)"), machine);
        }
    }

    @Test void defaultRestrictedItemsShowTwoLinesWithoutRemovingExistingText() throws Exception {
        for (String id : DShanhaiConfig.COMMON.primordialEngineBlockedModuleItemIds.getDefault()) {
            List<Component> lines = new ArrayList<>(List.of(Component.literal("原有說明")));
            append(MachineDefinition.createDefinition(new ResourceLocation(id)), lines);
            assertEquals(3, lines.size(), id);
            assertEquals("原有說明", lines.getFirst().getString());
            assertEquals(FIRST, ((TranslatableContents) lines.get(1).getContents()).getKey());
            assertEquals(SECOND, ((TranslatableContents) lines.get(2).getContents()).getKey());
        }
    }

    @Test void globalOverrideAndRemovingTheIdHideTheRestrictionText() throws Exception {
        MachineDefinition machine = MachineDefinition.createDefinition(new ResourceLocation(
                "gt_shanhai", "primordial_weiyang_reconstruction_module"));
        DShanhaiConfig.COMMON.primordialEngineAllowAnyModule.set(true);
        List<Component> lines = new ArrayList<>();
        append(machine, lines);
        assertTrue(lines.isEmpty());
        DShanhaiConfig.COMMON.primordialEngineAllowAnyModule.set(false);
        DShanhaiConfig.COMMON.primordialEngineBlockedModuleItemIds.set(List.of());
        append(machine, lines);
        assertTrue(lines.isEmpty());
    }

    @Test void chineseLinesKeepTheExactRequestedTextAndEnglishIsTranslated() throws Exception {
        Path lang = Path.of("src/main/resources/assets/gt_shanhai/lang");
        var zh = JsonParser.parseString(Files.readString(lang.resolve("zh_cn.json"))).getAsJsonObject();
        var en = JsonParser.parseString(Files.readString(lang.resolve("en_us.json"))).getAsJsonObject();
        assertEquals("此模块仅能安装至归墟视界枢核运行，或由归墟超维度链接模块链接的原始引擎主机运行",
                zh.get(FIRST).getAsString() + zh.get(SECOND).getAsString());
        assertFalse(en.get(FIRST).getAsString().isBlank());
        assertFalse(en.get(SECOND).getAsString().isBlank());
    }

    private static void append(MachineDefinition definition, List<Component> lines) throws Exception {
        Method method = DShanhaiMachines.class.getDeclaredMethod("appendGuixuHostTooltip", MachineDefinition.class, List.class);
        method.setAccessible(true);
        method.invoke(null, definition, lines);
    }
}
