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
        assertTrue(encode.contains("return findSelfSynthesisTargets(networkNode.getGrid(), pattern, recipeTypeId);"),
                "没有星律目标时，快速上传必须落到自合成仓");
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
}
