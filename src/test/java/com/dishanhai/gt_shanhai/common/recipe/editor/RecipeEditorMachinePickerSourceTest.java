package com.dishanhai.gt_shanhai.common.recipe.editor;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 配方修改器与样板生成器共用同一套「选 GT 机器」逻辑（MachinePicker）。 */
class RecipeEditorMachinePickerSourceTest {

    private static final Path JAVA = Path.of("src", "main", "java", "com", "dishanhai", "gt_shanhai");

    private static String read(Path relative) throws Exception {
        return Files.readString(JAVA.resolve(relative));
    }

    @Test
    void machinePickerIsTheSingleSourceOfTheMachineChoiceLogic() throws Exception {
        String picker = read(Path.of("client", "gui", "generator", "MachinePicker.java"));
        assertAll(
                // 只列 GT 机器，走商店自建的限制模式选取器
                () -> assertTrue(picker.contains("item instanceof MetaMachineItem")),
                () -> assertTrue(picker.contains("new MultiPickerScreen(parent, machineIds(),")),
                // 替换这一格并预选当前机器；清空后确认交回 EMPTY，取消不回调
                () -> assertTrue(picker.contains("picker.replacing();")),
                () -> assertTrue(picker.contains("picker.preselect(current.copyWithCount(1), 1)")),
                () -> assertTrue(picker.contains("picker.setAfterConfirm(() -> chosen.accept(result[0]));")),
                () -> assertTrue(picker.contains("ItemStack[] result = {ItemStack.EMPTY};"))
        );
    }

    @Test
    void editorSlotOpensThePickerOnLeftClickAndKeepsDragAndRightClick() throws Exception {
        String slot = read(Path.of("common", "recipe", "editor", "ShanhaiRecipeMachineSlotWidget.java"));
        assertAll(
                () -> assertTrue(slot.contains("if (button == 0) {")),
                () -> assertTrue(slot.contains("MachinePicker.open(")),
                // 确认结果走与 JEI 拖入相同的本地设置 + 服务端同步
                () -> assertTrue(slot.contains("changed.accept(copy);")),
                () -> assertTrue(slot.contains("writeClientAction(ACT_SET, buffer -> buffer.writeItem(copy));")),
                () -> assertTrue(slot.contains("writeClientAction(ACT_CLEAR, buffer -> {});")),
                // JEI 拖入与右键清除保持
                () -> assertTrue(slot.contains("implements IGhostIngredientTarget")),
                () -> assertTrue(slot.contains("if (button == 1) {")),
                // 客户端的选取器只在点击时才碰到，不放进字段或静态初始化，避免服务端加载
                () -> assertFalse(slot.contains("MultiPickerScreen"))
        );
    }

    @Test
    void generatorMachineSlotReusesTheSamePicker() throws Exception {
        String ghost = read(Path.of("client", "gui", "generator", "GeneratorGhostSlot.java"));
        assertAll(
                () -> assertTrue(ghost.contains("MachinePicker.create(screen,current,")),
                () -> assertFalse(ghost.contains("BuiltInRegistries"))
        );
    }
}
