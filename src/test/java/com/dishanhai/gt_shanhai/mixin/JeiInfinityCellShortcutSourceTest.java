package com.dishanhai.gt_shanhai.mixin;

import com.dishanhai.gt_shanhai.testutil.MixinConfigs;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 守卫：JEI「Ctrl+Shift+左键取 EAE 无限盘」的注入点与组合键判定。
 *
 * <p>背景：JEI 全局输入路由顺序是
 * {@code EditInputHandler → CheatInputHandler → 物品栏 → 书签栏 → FocusInputHandler}，
 * 自带的 {@code getCheatItemStack()}（Shift+左键）在按住 Ctrl 时依然成立，会先吞掉点击。
 * 若把拦截点放回 {@code FocusInputHandler}，容器界面里就永远拿不到无限盘。</p>
 *
 * <p>另外 Forge 构造 {@code UserInput} 时把鼠标事件的 modifiers 写死为 0，
 * 组合键只能读 {@code Screen.hasControlDown()}，不能再走 {@code getModifiers()}。</p>
 */
class JeiInfinityCellShortcutSourceTest {

    private static final Path MIXIN_DIR = Path.of("src", "main", "kotlin", "com", "dishanhai",
            "gt_shanhai", "mixin");
    private static final Path CLIENT_DIR = Path.of("src", "main", "kotlin", "com", "dishanhai",
            "gt_shanhai", "client");

    @Test
    void containerShortcutInterceptsCheatInputHandlerBeforeJeiCheatGive() throws IOException {
        String mixin = read(MIXIN_DIR.resolve("JEICopyShortcutMixin.kt"));

        assertTrue(mixin.contains("CheatInputHandler::class"),
                "必须注入 CheatInputHandler，否则 JEI 自带 Shift+左键会先吞掉组合键");
        assertFalse(mixin.contains("FocusInputHandler::class"),
                "不要再挂到 FocusInputHandler：它在路由链里排在被吞掉之后");
        assertTrue(mixin.contains("@Inject(method = [\"handleUserInput\"], at = [At(\"HEAD\")], cancellable = true"),
                "必须在 HEAD 处抢先拦截并允许取消");
    }

    @Test
    void recipeShortcutStaysOnRecipeSlotHandlerAndReadsLayoutViaAccessor() throws IOException {
        String mixin = read(MIXIN_DIR.resolve("JEIRecipeGuiInfinityCellMixin.kt"));
        String accessor = read(MIXIN_DIR.resolve("JeiRecipeLayoutHandlerAccessor.kt"));

        assertTrue(mixin.contains("RecipeLayoutWithButtons\\$RecipeLayoutUserInputHandler"),
                "RecipesGui 有独立输入路由，配方槽必须单独注入");
        assertTrue(accessor.contains("@Accessor(\"recipeLayout\", remap = false)"),
                "私有 record 内部类的字段只能通过 Accessor 读取");
        assertTrue(mixin.contains("JeiRecipeLayoutHandlerAccessor"),
                "配方槽 mixin 必须用 Accessor 而不是 @Shadow");
    }

    @Test
    void shortcutCheckUsesLiveModifierStateAndLeftButtonOnly() throws IOException {
        String helper = read(CLIENT_DIR.resolve("JeiCopyShortcutHelper.kt"));
        int start = helper.indexOf("fun isInfinityCellShortcut");
        int end = helper.indexOf("fun copyAction");
        assertTrue(start > 0 && end > start, "必须保留 isInfinityCellShortcut 判定函数");
        String body = helper.substring(start, end);

        assertTrue(body.contains("Screen.hasControlDown()") && body.contains("Screen.hasShiftDown()"),
                "组合键必须读实时按键状态");
        assertFalse(body.contains("input.modifiers") || body.contains("getModifiers"),
                "鼠标事件的 modifiers 恒为 0，不能用来判定 Ctrl/Shift");
        assertTrue(body.contains("InputConstants.Type.MOUSE") && body.contains("key.value != 0"),
                "只认鼠标左键，避免 Ctrl+Shift+中键误触发");
    }

    @Test
    void mixinsAndAccessorsAreRegisteredInJeiConfig() {
        String config = MixinConfigs.readAll();

        assertTrue(config.contains("\"JEICopyShortcutMixin\""));
        assertTrue(config.contains("\"JEIRecipeGuiInfinityCellMixin\""));
        assertTrue(config.contains("\"JeiCheatInputHandlerAccessor\""),
                "CheatInputHandler 字段访问器必须注册");
        assertTrue(config.contains("\"JeiRecipeLayoutHandlerAccessor\""),
                "配方槽字段访问器必须注册");
    }

    @Test
    void javaCounterpartsWereReplacedNotDuplicated() {
        Path javaMixinDir = Path.of("src", "main", "java", "com", "dishanhai", "gt_shanhai", "mixin");
        Path javaClientDir = Path.of("src", "main", "java", "com", "dishanhai", "gt_shanhai", "client");

        assertFalse(Files.exists(javaMixinDir.resolve("JEICopyShortcutMixin.java")),
                "同名 Java mixin 必须删除，否则同一 FQCN 会编译冲突");
        assertFalse(Files.exists(javaMixinDir.resolve("JEIRecipeGuiInfinityCellMixin.java")));
        assertFalse(Files.exists(javaClientDir.resolve("JeiCopyShortcutHelper.java")));
    }

    private static String read(Path path) throws IOException {
        assertTrue(Files.exists(path), "缺少文件: " + path);
        return Files.readString(path);
    }
}
