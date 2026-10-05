package com.dishanhai.gt_shanhai.common.machine.misc;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class FtbqAeSubmitterCompatibilitySourceTest {

    private static final Path SUBMITTER = Path.of(
            "src", "main", "java", "com", "dishanhai", "gt_shanhai",
            "common", "machine", "misc", "FtbqAeSubmitterMachine.java");
    private static final Path BUILD = Path.of("build.gradle");
    private static final Path UI_CONFIG = Path.of("gt_shanhai.mixin.json");
    private static final Path UI_BUTTON = Path.of(
            "src", "main", "java", "com", "dishanhai", "gt_shanhai",
            "mixin", "FtbViewQuestPanelSubmitterButtonMixin.java");
    private static final Path UI_SHOP = Path.of(
            "src", "main", "java", "com", "dishanhai", "gt_shanhai",
            "mixin", "FtbViewQuestPanelShopButtonMixin.java");
    private static final Path UI_EDIT = Path.of(
            "src", "main", "java", "com", "dishanhai", "gt_shanhai",
            "mixin", "FtbViewQuestPanelEditMenuMixin.java");

    @Test
    void ftbq422UsesServerPlayerClaimRewardContractAndBuildsAgainstUpdatedJar() throws IOException {
        String submitter = Files.readString(SUBMITTER);
        String build = Files.readString(BUILD);

        assertTrue(build.contains("ftb-quests-forge-2001.4.22.jar"),
                "編譯依賴必須與遊戲端 FTBQ 1.4.22 一致");
        assertTrue(submitter.contains("gtShanhai$resolveClaimReward(TeamData data, ServerPlayer player)"),
                "獎勵領取解析必須接收 1.4.22 所需的 ServerPlayer");
        assertTrue(submitter.contains("p[0] == ServerPlayer.class"),
                "必須優先解析 TeamData.claimReward(ServerPlayer, ...)");
        assertTrue(submitter.contains("p[2] == boolean.class"),
                "1.4.22 的 claimReward 第三參數必須是通知開關");
        assertTrue(submitter.contains("data.markRewardAsClaimed(player, reward"),
                "離線或兼容回退必須能直接以 UUID 標記獎勵已領取");
    }

    @Test
    void ftbq422UiInjectionPointsRemainRegistered() throws IOException {
        String config = Files.readString(UI_CONFIG);
        String button = Files.readString(UI_BUTTON);
        String shop = Files.readString(UI_SHOP);
        String edit = Files.readString(UI_EDIT);

        assertTrue(config.contains("\"FtbViewQuestPanelSubmitterButtonMixin\""));
        assertTrue(config.contains("\"FtbViewQuestPanelShopButtonMixin\""));
        assertTrue(config.contains("\"FtbViewQuestPanelEditMenuMixin\""));
        assertTrue(button.contains("@Inject(method = \"addWidgets\", at = @At(\"TAIL\"))"),
                "提交器入口必须继续挂在 1.4.22 仍存在的 ViewQuestPanel.addWidgets");
        assertTrue(shop.contains("ViewQuestPanel;buildPageIndices()V"),
                "商店跳转按钮必须继续挂在 1.4.22 的 buildPageIndices");
        assertTrue(edit.contains("BaseScreen;openContextMenu(Ljava/util/List;)"),
                "编辑菜单必须继续修改 1.4.22 的 BaseScreen.openContextMenu 参数");
    }
}
