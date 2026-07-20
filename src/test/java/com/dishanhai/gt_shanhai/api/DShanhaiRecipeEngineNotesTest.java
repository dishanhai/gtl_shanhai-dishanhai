package com.dishanhai.gt_shanhai.api;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DShanhaiRecipeEngineNotesTest {

    @Test
    void plainStringKeepsSingleLineNote() {
        assertEquals(List.of("消耗气态奇迹"),
                DShanhaiRecipeEngine.parseNoteLines("消耗气态奇迹"));
    }

    @Test
    void listKeepsLegacyOneEntryPerLineBehavior() {
        assertEquals(List.of("消耗气态奇迹", "全原初模块100x产出"),
                DShanhaiRecipeEngine.parseNoteLines(List.of("消耗气态奇迹", "全原初模块100x产出")));
    }

    @Test
    void bracketedStringUsesTopLevelGroupsAsLines() {
        assertEquals(List.of("消耗气态奇迹", "全原初模块100x产出,必须安装在同一主机"),
                DShanhaiRecipeEngine.parseNoteLines(
                        "[ '消耗气态奇迹',], ['全原初模块100x产出,必须安装在同一主机']"));
    }

    @Test
    void bracketedStringJoinsMultipleItemsInOneGroupIntoOneLine() {
        assertEquals(List.of("消耗气态奇迹", "全原初模块100x产出,必须安装在同一主机"),
                DShanhaiRecipeEngine.parseNoteLines(
                        "[ '消耗气态奇迹',], ['全原初模块100x产出','必须安装在同一主机']"));
    }
}
