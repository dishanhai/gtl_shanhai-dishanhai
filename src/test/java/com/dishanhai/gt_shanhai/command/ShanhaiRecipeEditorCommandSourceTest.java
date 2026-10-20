package com.dishanhai.gt_shanhai.command;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 複查報告 P1-05：命令入口、參數型別與自檢輸出。
 */
class ShanhaiRecipeEditorCommandSourceTest {

    private static String read(String path) throws Exception {
        return Files.readString(Path.of(path));
    }

    @Test
    void commandIsMountedUnderShanhaiAndAcceptsNamespacedTypes() throws Exception {
        String commands = read(
                "src/main/java/com/dishanhai/gt_shanhai/command/DShanhaiCommands.java");
        String editor = read(
                "src/main/java/com/dishanhai/gt_shanhai/command/ShanhaiRecipeEditorCommand.java");

        assertTrue(commands.contains("Commands.literal(\"山海\")"));
        assertTrue(commands.contains("ShanhaiRecipeEditorCommand.create()"));
        // 根級 /配方編輯 已移除，避免入口與規格不符
        assertFalse(commands.contains(".then(ShanhaiRecipeEditorCommand.create())\n                .then(gtQueryCommand())"),
                "配方編輯不應再掛在 /shanhai 根層");

        // word() 的 unquoted 字元集不含 ':'，gtceu:assembler 會解析失敗
        assertFalse(editor.contains("StringArgumentType.word()"),
                "type 參數不可用 word()");
        assertTrue(editor.contains("Commands.argument(\"type\", StringArgumentType.string())"));
    }

    @Test
    void rollbackTakesTypeAndSelfcheckReportsStage() throws Exception {
        String editor = read(
                "src/main/java/com/dishanhai/gt_shanhai/command/ShanhaiRecipeEditorCommand.java");
        String ops = read(
                "src/main/java/com/dishanhai/gt_shanhai/common/recipe/editor/ShanhaiRecipeEditorOps.java");

        assertTrue(editor.contains("rollback(type, recipeId, source.getServer())"),
                "回滾必須帶類型，避免跨類型同名 path 被亂猜");
        assertTrue(ops.contains("rollback(String recipeTypeId, String recipeId, MinecraftServer server)"));
        assertTrue(editor.contains("failureStage"),
                "自檢必須輸出失敗階段");
        assertTrue(editor.contains("tableMismatch"),
                "自檢必須覆蓋雙表一致性");
        assertTrue(editor.contains("snapshotEntries"),
                "自檢必須輸出快照條目數");
    }
}
