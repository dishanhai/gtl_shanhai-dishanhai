package com.dishanhai.gt_shanhai.client.gui;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ChangelogMarkdownSourceTest {

    private static String source(String relativePath) throws Exception {
        return Files.readString(Path.of("src/main/java/com/dishanhai/gt_shanhai", relativePath));
    }

    @Test
    void changelogConfigSelectsHighestSemanticVersionFromMarkdownFileName() throws Exception {
        String config = source("common/config/ChangelogConfig.java");
        assertTrue(config.contains("compareVersions"),
                "公告配置应按语义版本比较");
        assertTrue(config.contains("Files.walk"),
                "公告配置应递归读取 changelog 目录下的 Markdown 文件");
        assertTrue(config.contains("getLatest"),
                "公告配置应提供当前最高版本公告");
        assertTrue(config.contains("getHistory"),
                "公告配置应保留历史公告列表");
    }

    @Test
    void markdownUsesFrontMatterAndBodyAsDisplayedContent() throws Exception {
        String config = source("common/config/ChangelogConfig.java");
        assertTrue(config.contains("front matter"),
                "公告 Markdown 应有 front matter 解析说明");
        assertTrue(config.contains("parseMarkdown"),
                "公告配置应解析 Markdown 文件");
        assertTrue(config.contains("enabled"),
                "公告 Markdown 应支持 enabled 标记");
        assertTrue(config.contains("version"),
                "公告 Markdown 应支持 version 标记");
        assertTrue(config.contains("title"),
                "公告 Markdown 应支持 title 标记");
        assertTrue(config.contains("parseInline"),
                "公告 Markdown 应解析行内标记");
        assertTrue(config.contains("withBold"),
                "公告 Markdown 应支持粗体");
        assertTrue(config.contains("withItalic"),
                "公告 Markdown 应支持斜体");
    }

    @Test
    void markdownSupportsRepeatedLinkFrontMatterEntries() throws Exception {
        String config = source("common/config/ChangelogConfig.java");
        assertTrue(config.contains("ChangelogLink"),
                "公告配置应提供可点击链接数据");
        assertTrue(config.contains("link"),
                "公告 front matter 应支持 link 标记");
        assertTrue(config.contains("parseLink"),
                "公告配置应解析 Markdown 链接标记");
        assertTrue(config.contains("List<String> linkValues"),
                "公告配置应保留多个重复 link 标记");
    }

    @Test
    void screenSupportsOpeningAndCopyingChangelogLinks() throws Exception {
        String screen = source("client/gui/ChangelogScreen.java");
        assertTrue(screen.contains("mouseClicked"),
                "公告画面应处理链接点击");
        assertTrue(screen.contains("openUri"),
                "左键应支持使用系统浏览器打开链接");
        assertTrue(screen.contains("setClipboard"),
                "右键应支持复制链接地址");
        assertTrue(screen.contains("activateLink"),
                "左键点击链接应同时复制并打开链接");
        assertTrue(screen.contains("ChangelogLink"),
                "公告画面应渲染链接数据");
    }

    @Test
    void screenRestrictsLinkClicksToVisibleContentBeforeDispatchingButtons() throws Exception {
        String screen = source("client/gui/ChangelogScreen.java");
        int click = screen.indexOf("public boolean mouseClicked(");
        int scan = screen.indexOf("for (RenderLine line : lines)", click);
        String guard = screen.substring(click, scan);
        assertTrue(guard.contains("mouseY < contentTop")
                        && guard.contains("mouseY >= contentTop + contentHeight")
                        && guard.contains("return super.mouseClicked(mouseX, mouseY, button)"),
                "裁切區外的點擊必須交給按鈕，不能觸發隱藏連結");
    }

    @Test
    void screenProvidesHistoricalUpdatesButton() throws Exception {
        String screen = source("client/gui/ChangelogScreen.java");
        assertTrue(screen.contains("历史更新"),
                "全屏公告应提供历史更新按钮");
        assertTrue(screen.contains("ChangelogHistoryScreen"),
                "历史更新按钮应打开历史公告列表");
    }

    @Test
    void historyScreenIncludesLatestVersionAndUsesSortedAllDocuments() throws Exception {
        String history = source("client/gui/ChangelogHistoryScreen.java");
        assertTrue(history.contains("ChangelogConfig.getAll()"),
                "历史更新列表应包含当前最新公告");
        assertTrue(history.contains("documents"),
                "历史更新列表应使用完整公告集合");
    }

    @Test
    void screenSupportsSwitchingBetweenSimplifiedChineseAndEnglish() throws Exception {
        String config = source("common/config/ChangelogConfig.java");
        String screen = source("client/gui/ChangelogScreen.java");
        String changelog491 = Files.readString(Path.of("config/gt_shanhai/changelog/Update-4.9.1.md"));
        assertTrue(config.contains("ENGLISH_SECTION_MARKER"),
                "公告配置应支持英文段落分隔标记");
        assertTrue(config.contains("Language"),
                "公告配置应提供中英文语言枚举");
        assertTrue(screen.contains("toggleLanguage"),
                "公告画面应支持切换中英文显示");
        assertTrue(changelog491.contains("<!-- lang:en -->"),
                "Update-4.9.1.md 应包含英文版本内容以供切换");

        var latest = com.dishanhai.gt_shanhai.common.config.ChangelogConfig.parseMarkdownFile(
                Path.of("config/gt_shanhai/changelog/Update-4.9.1.md"));
        org.junit.jupiter.api.Assertions.assertNotNull(latest, "应能读取最新公告");
        org.junit.jupiter.api.Assertions.assertEquals("4.9.1", latest.version(), "最新公告版本应为 4.9.1");
        assertTrue(latest.hasEnglish(), "4.9.1 公告应解析出英文内容");
        org.junit.jupiter.api.Assertions.assertEquals(
                "gt_shanhai 4.9.1 更新公告",
                latest.titleFor(com.dishanhai.gt_shanhai.common.config.ChangelogConfig.Language.ZH_CN));
        org.junit.jupiter.api.Assertions.assertEquals(
                "gt_shanhai 4.9.1 Release Notes",
                latest.titleFor(com.dishanhai.gt_shanhai.common.config.ChangelogConfig.Language.EN_US));
        assertTrue(latest.linesFor(com.dishanhai.gt_shanhai.common.config.ChangelogConfig.Language.ZH_CN)
                        .stream().anyMatch(line -> line.text().contains("联机兼容")),
                "中文模式应包含简体中文标题");
        assertTrue(latest.linesFor(com.dishanhai.gt_shanhai.common.config.ChangelogConfig.Language.EN_US)
                        .stream().anyMatch(line -> line.text().contains("Multiplayer Compatibility")),
                "英文模式应包含英文标题");
    }
}
