package com.dishanhai.gt_shanhai.common.item;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

class VirtualCraftingPlanMarkerSourceTest {

    private static final Path COMMON_MIXIN = Path.of("src/main/java/com/dishanhai/gt_shanhai/mixin",
            "CraftingPlanVirtualMarkerMixins.java");
    private static final Path CLIENT_MIXIN = Path.of("src/main/java/com/dishanhai/gt_shanhai/mixin",
            "CraftConfirmTableRendererVirtualMarkerMixin.java");
    private static final Path MARKER_ACCESS = Path.of("src/main/java/com/dishanhai/gt_shanhai/common/item",
            "CraftingPlanVirtualMarkerAccess.java");
    private static final Path MIXIN_CONFIG = Path.of("src/main/resources/gt_shanhai.mixin.json");
    private static final Path ZH_CN = Path.of("src/main/resources/assets/gt_shanhai/lang/zh_cn.json");

    @Test
    void craftingPlanSynchronizesPresenceMarkerFromServer() throws IOException {
        assertTrue(Files.exists(COMMON_MIXIN), "需要服务端计划标记和网络同步 Mixin");
        String source = Files.readString(COMMON_MIXIN);

        assertTrue(source.contains("VirtualPatternEncodingHelper.collectPresenceRequirements(job)"),
                "虚拟标识必须来自计划中的 PresenceInput，不能按物品 ID 猜测");
        assertTrue(source.contains("requirements.containsKey(entry.getWhat())"));
        assertTrue(source.contains("buffer.writeBoolean(gtShanhai$virtualPresence)"),
                "服务端必须把虚拟标识随计划条目发送给客户端");
        assertTrue(source.contains("buffer.readBoolean()"),
                "客户端必须读取对应虚拟标识");
    }

    @Test
    void craftingPlanRendersVisibleMarkerAndTooltip() throws IOException {
        assertTrue(Files.exists(CLIENT_MIXIN), "需要客户端合成计划渲染 Mixin");
        String source = Files.readString(CLIENT_MIXIN);
        String config = Files.readString(MIXIN_CONFIG);
        String lang = Files.readString(ZH_CN);

        assertTrue(source.contains("method = \"getEntryDescription\""),
                "计划表格中必须直接显示虚拟标识");
        assertTrue(source.contains("method = \"getEntryTooltip\""),
                "悬浮说明必须解释虚拟物品不会消耗");
        assertTrue(source.contains("ChatFormatting.LIGHT_PURPLE"));
        assertTrue(config.contains("CraftingPlanVirtualMarkerMixins$Entry"));
        assertTrue(config.contains("CraftingPlanVirtualMarkerMixins$Summary"));
        assertTrue(config.contains("CraftConfirmTableRendererVirtualMarkerMixin"));
        assertTrue(lang.contains("gui.gt_shanhai.crafting_plan.virtual_presence"));
        assertTrue(lang.contains("gui.gt_shanhai.crafting_plan.virtual_presence.detail"));
    }

    @Test
    void craftingPlanMarkerAccessStaysOutsideManagedMixinPackage() throws IOException {
        assertTrue(Files.exists(MARKER_ACCESS),
                "跨 Mixin 通信接口必须放在普通包，避免 Mixin 类加载器拒绝直接引用");
        String commonSource = Files.readString(COMMON_MIXIN);
        String clientSource = Files.readString(CLIENT_MIXIN);

        assertTrue(commonSource.contains("implements CraftingPlanVirtualMarkerAccess"));
        assertTrue(clientSource.contains("instanceof CraftingPlanVirtualMarkerAccess access"));
        assertFalse(commonSource.contains("interface EntryAccess"));
        assertFalse(clientSource.contains("CraftingPlanVirtualMarkerMixins.EntryAccess"),
                "客户端不能直接加载受 gt_shanhai.mixin.json 管理的 Mixin 包类型");
    }
}
