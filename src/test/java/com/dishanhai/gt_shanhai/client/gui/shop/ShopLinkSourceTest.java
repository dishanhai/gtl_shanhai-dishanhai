package com.dishanhai.gt_shanhai.client.gui.shop;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static com.dishanhai.gt_shanhai.client.gui.shop.ShopButtonFeedbackSourceTest.extractBlock;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 跳转改存目标 stableId 的源码约束（山海署名）。 */
class ShopLinkSourceTest {

    private static String read(String path) throws Exception {
        return Files.readString(Path.of("src/main/java/com/dishanhai/gt_shanhai", path));
    }

    @Test
    void reloadMigratesLegacyAliasesBeforePublishing() throws Exception {
        String reload = extractBlock(read("common/shop/ShopConfig.java"), "public static synchronized void reload()");
        int migrate = reload.indexOf("migrateLinkTargets(parsedEntries)");
        assertTrue(migrate >= 0 && migrate < reload.indexOf("publish(parsedEntries)"), "先改写再发布，客户端拿到的就是新引用");
        assertTrue(reload.contains("needsStableIdMigration || linkRewrites > 0"), "改写过就沿用原有写回路径");
        assertTrue(extractBlock(read("common/shop/ShopConfig.java"), "private static int migrateLinkTargets(")
                .contains("ShopLinkMigration.rewrites("));
    }

    @Test
    void shopScreenResolvesJumpsByStableIdFirst() throws Exception {
        String shop = read("client/gui/shop/ShopScreen.java");
        assertTrue(!shop.contains("linkedEntryKey("), "三处跳转解析都改走 resolveLink");
        int n = shop.split("ClientShopCatalog\\.resolveLink\\(", -1).length - 1;
        assertTrue(n == 3, "预载、详情跳转列、右键菜单各一处，实际 " + n);
        String resolve = extractBlock(read("client/shop/ClientShopCatalog.java"), "public static long resolveLink(");
        assertTrue(resolve.indexOf("keyOfStableId(") < resolve.indexOf("linkedEntryKey("), "先按 stableId，再认旧称呼");
    }

    @Test
    void jumpLabelUsesTheTargetsNameAndWraps() throws Exception {
        String shop = read("client/gui/shop/ShopScreen.java");
        assertTrue(shop.contains("ShopLinkNames.label(linkTarget.getLinkKey(), linkTarget.goodsDisplayName())"),
                "跳转列显示目标的称呼，留空用商品名");
        assertTrue(!shop.contains("trimText(this.font, \"→ 跳转: \""), "跳转列换行，不截断");
        assertTrue(shop.contains("actionY += linkH + 2"), "后续内容按真实跳转行高排列");
    }

    @Test
    void selectScreenCanListHiddenEntriesButQuestBindingStillDoesNot() throws Exception {
        String sel = read("client/gui/shop/ShopEntrySelectScreen.java");
        assertTrue(sel.contains("public ShopEntrySelectScreen(Screen parent, boolean includeHidden, String excludeStableId,"));
        assertTrue(sel.contains("stub.hidden() && !includeHidden"), "只有显式要求才列隐藏商品");
        assertTrue(sel.contains("stub.stableId().equals(exclude)"), "编辑器里不列自己");
        String quest = read("client/gui/shop/ShopQuestLinkMenu.java");
        assertTrue(quest.contains("new ShopEntrySelectScreen(parent, stableId ->"), "任务绑定入口维持原行为");
    }

    @Test
    void escapeFromTargetPickerReturnsToTheDraft() throws Exception {
        String close = extractBlock(read("client/gui/shop/ShopEntrySelectScreen.java"), "public void onClose()");
        assertTrue(close.contains("setScreen(parent)"), "Esc 返回仍持有草稿的编辑器");
    }

    @Test
    void editorPicksTheTargetInsteadOfTypingAnAlias() throws Exception {
        String e = read("client/gui/shop/ShopEntryEditScreen.java");
        assertTrue(!e.contains("linkToBox"), "「跳转到」不再手打称呼");
        assertTrue(extractBlock(e, "private void openLinkTargetPicker(")
                .contains("new ShopEntrySelectScreen(this, true, selfStableId,"), "可挑隐藏商品，不列自己");
        assertTrue(extractBlock(e, "private void submit()").contains("linkToSend"), "旧式称呼保存时转为 stableId");
        assertTrue(e.contains("label(g, \"称呼\", y)"), "「别名」改称「称呼」");
        assertTrue(extractBlock(e, "private String linkKeyHint()").contains("draftGoodsName()"),
                "说明里写出留空时用的商品名");
    }
}
