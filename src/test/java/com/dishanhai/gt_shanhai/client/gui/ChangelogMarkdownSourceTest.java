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
    void screenProvidesHistoricalUpdatesButton() throws Exception {
        String screen = source("client/gui/ChangelogScreen.java");
        assertTrue(screen.contains("历史更新"),
                "全屏公告应提供历史更新按钮");
        assertTrue(screen.contains("ChangelogHistoryScreen"),
                "历史更新按钮应打开历史公告列表");
    }
}
