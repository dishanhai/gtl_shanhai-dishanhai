package com.dishanhai.gt_shanhai.common.machine.part;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class SelfSynthesisChamberSourceTest {

    @Test
    void quickUploadFallsBackToSelfSynthesisWithoutTreatingItAsStellar() throws IOException {
        String encode = Files.readString(Path.of("src", "main", "java", "com", "dishanhai", "gt_shanhai",
                "common", "item", "JeiPatternQuickEncodeService.java"));
        assertTrue(!encode.contains("return findSelfSynthesisTargets(networkNode.getGrid(), pattern, recipeTypeId);"),
                "没有星律目标时，普通快速上传必须回物品栏，不能落到自合成仓");
        assertTrue(encode.contains("uploadToSelfSynthesis"),
                "多方块结构页仍只上传到自合成仓");
        assertTrue(encode.contains("noStellarTarget || sdaPatterns.size() < MIN_SDA_FALLBACK_PATTERNS"),
                "没有星律目标时，样板必须回物品栏，不能打成 SDA");
        assertTrue(encode.contains("!isSelfSynthesisTarget(target)"),
                "自合成仓不能走星律槽位写入");
    }

    @Test
    void eaepUploadMapsSelfSynthesisRecipeType() throws IOException {
        String mixin = Files.readString(Path.of("src", "main", "java", "com", "dishanhai", "gt_shanhai",
                "mixin", "EaepRequestProvidersListRecipeTypesMixin.java"));
        String bridge = Files.readString(Path.of("src", "main", "java", "com", "dishanhai", "gt_shanhai",
                "common", "compat", "eaep", "EaepProviderRecipeTypeBridge.java"));
        assertTrue(mixin.contains("EaepProviderRecipeTypeBridge.collectProviderRecipeTypeIds(container)"),
                "EAEP 列表仍要收集 provider 配方类型");
        assertTrue(mixin.contains("SelfSynthesisUpload.mapRecipeTypes"),
                "自合成仓必须把上传样板的配方类型映射进 EAEP 列表");
        assertTrue(bridge.contains("SelfSynthesisChamberPartMachine"),
                "配方类型桥必须识别自合成仓");
        assertTrue(bridge.contains("SelfSynthesisUpload.DISPLAY_NAME"),
                "EAEP 显示名必须映射为多方块");
    }

    @Test
    void dispatchCancelsTheCraftInsteadOfInjectingOutputs() throws IOException {
        String machine = Files.readString(Path.of("src", "main", "java", "com", "dishanhai", "gt_shanhai",
                "common", "machine", "part", "SelfSynthesisChamberPartMachine.java"));
        String mixin = Files.readString(Path.of("src", "main", "java", "com", "dishanhai", "gt_shanhai",
                "mixin", "SelfSynthesisCraftCancelMixin.java"));
        String config = Files.readString(Path.of("src", "main", "resources", "gt_shanhai.mixin.json"));
        assertTrue(!machine.contains("details.getOutputs()"),
                "自合成仓不能把样板产物注回网络");
        assertTrue(machine.contains("SelfSynthesisCraftCancel.mark()"),
                "发配成功后必须标记取消");
        assertTrue(mixin.contains("((CraftingCpuLogic) (Object) this).cancel()"),
                "发配后的 mixin 必须取消合成任务");
        assertTrue(config.contains("\"SelfSynthesisCraftCancelMixin\""));
    }

    @Test
    void jadeReportsDispatchAndPatternSlots() throws IOException {
        String plugin = Files.readString(Path.of("src", "main", "java", "com", "dishanhai", "gt_shanhai",
                "integration", "jade", "DShanhaiJadePlugin.java"));
        String provider = Files.readString(Path.of("src", "main", "java", "com", "dishanhai", "gt_shanhai",
                "integration", "jade", "provider", "SelfSynthesisChamberInfoProvider.java"));
        assertTrue(plugin.contains("SelfSynthesisChamberInfoProvider.INSTANCE"),
                "Jade 必须注册自合成仓信息");
        assertTrue(provider.contains("machine.isBusy()"),
                "Jade 必须显示发配退回状态");
        assertTrue(provider.contains("getTerminalPatternInventory()"),
                "Jade 必须统计样板槽");
        assertTrue(provider.contains("SelfSynthesisUpload.DISPLAY_NAME"),
                "Jade 分组名必须是多方块");
    }
}
