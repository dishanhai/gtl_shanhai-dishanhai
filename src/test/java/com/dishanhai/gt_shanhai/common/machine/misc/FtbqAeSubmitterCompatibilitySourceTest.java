package com.dishanhai.gt_shanhai.common.machine.misc;

import com.dishanhai.gt_shanhai.testutil.MixinConfigs;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

class FtbqAeSubmitterCompatibilitySourceTest {

    private static final Path SUBMITTER = Path.of(
            "src", "main", "java", "com", "dishanhai", "gt_shanhai",
            "common", "machine", "misc", "FtbqAeSubmitterMachine.java");
    private static final Path BUILD = Path.of("build.gradle");
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
    void ftbq422MarksClaimWithoutDeliveringTwiceAndBuildsAgainstUpdatedJar() throws IOException {
        String submitter = Files.readString(SUBMITTER);
        String build = Files.readString(BUILD);

        assertTrue(build.contains("ftb-quests-forge-2001.4.22.jar"),
                "編譯依賴必須與遊戲端 FTBQ 1.4.22 一致");
        assertTrue(submitter.contains("data.markRewardAsClaimed(rewardPlayerId, reward"),
                "AE 回网只能标记领取，不能再次调用会直接发奖的 claimReward");
        assertFalse(submitter.contains("method.invoke(data"), "禁止回退到会重复发奖的反射路径");
        assertTrue(submitter.contains("FtbqPendingRewards"), "未交付的奖励必须随机器存档");

    }

    @Test
    void pendingRewardsSurviveMachineDropWithoutCreativeCloning() throws IOException {
        String submitter = Files.readString(SUBMITTER);
        assertTrue(submitter.contains("IUIMachine, IDropSaveMachine"));
        assertTrue(submitter.contains("public boolean savePickClone()"));
        assertTrue(submitter.contains("key.toTag()"));
        assertTrue(submitter.contains("AEItemKey.fromTag"));
        assertTrue(Files.isRegularFile(Path.of("src/main/resources/data/gt_shanhai/loot_tables/blocks/ftbq_ae_submitter.json")),
                "显式掉落表让 GTCEu 执行 IDropSaveMachine，避免被缺失掉落表的回退 Mixin 截断");
    }

    @Test
    void ftbq422UiInjectionPointsRemainRegistered() throws IOException {
        String config = MixinConfigs.readAll();
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
