package com.shanhai.client.ptype;

import appeng.menu.me.items.PatternEncodingTermMenu;
import com.shanhai.ShanhaiMod;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.lwjgl.glfw.GLFW;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * 本功能<b>唯一的入口</b>：一个 {@code @Mod.EventBusSubscriber(..., value = Dist.CLIENT, bus = FORGE)}。
 *
 * <h2>1. 为什么这样挂 = 专用服务端一行 client 类都不会加载</h2>
 * Forge 的自动订阅只会在 {@code Dist} 匹配时注册这个类；专用服务端上
 * {@code value = Dist.CLIENT} 的类<b>根本不会被加载</b>（本工程同款先例：
 * {@code client/event/HaloEndTooltipHandler}、{@code client/guard/ShanhaiGuardClientEvents}，
 * 后者的类注释就是为这件事写的）。⇒ <b>不需要改 {@code ShanhaiMod} 一行代码</b>，
 * 也不会有 dist 混用把专服搞崩的风险。本包其它类只被这个类引用 ⇒ 同样不会上专服。
 *
 * <h2>2. 五个事件各自负责什么（全部是 Forge 的 {@code ScreenEvent}）</h2>
 * <table border="1">
 *   <caption>事件分工</caption>
 *   <tr><th>事件</th><th>干什么</th><th>吃掉（cancel）的时机</th></tr>
 *   <tr><td>{@code Render.Post}</td><td>画胶囊；面板开着时再画面板</td><td>不 cancel（只画）</td></tr>
 *   <tr><td>{@code MouseButtonPressed.Pre}</td><td>胶囊开面板 / 面板内点选 / 点空白关面板</td>
 *       <td>面板开着时<b>全部</b>；面板关着时只吃「点中胶囊」那一下</td></tr>
 *   <tr><td>{@code MouseScrolled.Pre}</td><td>面板列表翻动</td><td>指针在面板内时</td></tr>
 *   <tr><td>{@code KeyPressed.Pre}</td><td>Esc 关、退格删字、回车选中第一条、PageUp/PageDown/↑↓ 翻页</td>
 *       <td>面板开着时<b>全部</b>（含回车/退格，避免漏给 AE2 的搜索框与快捷键）</td></tr>
 *   <tr><td>{@code CharacterTyped.Pre}</td><td>搜索框输入（含中文；中文靠输入法提交的字符走这条路）</td>
 *       <td>面板开着时<b>全部</b></td></tr>
 *   <tr><td>{@code Init.Post}</td><td>屏幕重 init（改窗口大小等）⇒ 面板一律关掉，坐标不复活</td><td>—</td></tr>
 * </table>
 *
 * <h2>3. 「不许穿透到 AE2」是怎么做到的</h2>
 * 这几个 {@code .Pre} 事件都是 {@code @Cancelable}，且 Forge 的调用点（javap 实证
 * {@code ForgeHooksClient.onScreenMouseClickedPre/onScreenMouseScrollPre/onScreenKeyPressedPre/
 * onScreenCharTypedPre}，返回 boolean）在<b>交给 {@code Screen} 本体处理之前</b>就问一句
 * 「要不要吞掉」⇒ 我们 cancel 之后 AE2 的 {@code mouseClicked/mouseScrolled/keyPressed} 一次都不会跑。
 *
 * <h2>4. 日志（无头服/客户端日志可断言）</h2>
 * 统一前缀 {@link PatternTypeSupport#PREFIX}：挂载自证、配方类型枚举条数、每次下发/重下发、
 * 读样板失败、能力探测失败 —— 每条都在对应动作里打一次，不在渲染循环里刷屏。
 */
@OnlyIn(Dist.CLIENT)
@Mod.EventBusSubscriber(modid = ShanhaiMod.MOD_ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class PatternTypeSelectorEvents {

    /** 搜索词长度上限。 */
    private static final int MAX_QUERY_LEN = 48;
    /** 滚一格滚轮翻几行。 */
    private static final int SCROLL_LINES_PER_NOTCH = 3;

    /** 已经打过「本终端不支持」的屏幕类（按类去重，不刷屏）。 */
    private static final Set<Class<?>> UNSUPPORTED_LOGGED =
            Collections.newSetFromMap(new WeakHashMap<>());

    static {
        // 类加载 = Forge 注册了订阅者。这一行是「挂上了没有」在日志上的唯一判据
        // （@Mod.EventBusSubscriber 成功时本工程其它类也都是「一行都没有」，见 ShanhaiMod 的注释）。
        ShanhaiMod.LOGGER.info("{} 选择器已挂上 FORGE 客户端事件总线（Render.Post/MouseButtonPressed.Pre/"
                + "MouseScrolled.Pre/KeyPressed.Pre/CharacterTyped.Pre/Init.Post；零 mixin、零反射）",
                PatternTypeSupport.PREFIX);
    }

    private PatternTypeSelectorEvents() {}

    // ------------------------------------------------------------------ 绘制

    @SubscribeEvent
    public static void onRenderPost(ScreenEvent.Render.Post event) {
        Screen screen = event.getScreen();
        PatternEncodingTermMenu menu = PatternTypeSupport.menuOf(screen);
        if (menu == null) {
            return;
        }
        noteUnsupportedOnce(screen, menu);

        // 粘性重下发：先看编码槽有没有换过样板（内部 250ms 限频，关掉开关见 STICKY_REAPPLY）
        PatternTypeSelectorState.tickSticky(menu);

        PatternTypeSelectorRenderer.render(screen, event.getGuiGraphics(), event.getMouseX(), event.getMouseY());
    }

    @SubscribeEvent
    public static void onInitPost(ScreenEvent.Init.Post event) {
        // 窗口尺寸变了 / 换界面了：面板与坐标全部作废
        PatternTypeSelectorState.resetOnScreenInit();
        // 切语言/资源重载后名字必须重新解析（这里只在语言码真的变了才动，不是每次开界面都重建）
        invalidateNamesIfLanguageChanged();
    }

    private static String lastLanguageCode;

    private static void invalidateNamesIfLanguageChanged() {
        try {
            String code = net.minecraft.client.Minecraft.getInstance().getLanguageManager().getSelected();
            if (lastLanguageCode == null) {
                lastLanguageCode = code;
                return;
            }
            if (!lastLanguageCode.equals(code)) {
                lastLanguageCode = code;
                RecipeTypeCatalog.invalidateNames();
                ShanhaiMod.LOGGER.info("{} 语言切换为 {} ⇒ 配方类型名字缓存已作废，下次读取时重建",
                        PatternTypeSupport.PREFIX, code);
            }
        } catch (Throwable ignored) {
            // 拿不到语言管理器就什么都不做（名字最多停在旧语言，不影响功能）
        }
    }

    // ------------------------------------------------------------------ 鼠标

    @SubscribeEvent
    public static void onMouseButtonPressed(ScreenEvent.MouseButtonPressed.Pre event) {
        Screen screen = event.getScreen();
        PatternEncodingTermMenu menu = PatternTypeSupport.menuOf(screen);
        if (menu == null || !PatternTypeSupport.hasGuiAnchor(screen)) {
            return;
        }
        if (handleMousePressed(screen, menu, event.getMouseX(), event.getMouseY(), event.getButton())) {
            event.setCanceled(true);
        }
    }

    /** @return {@code true} = 这一下归我们（调用方负责 cancel） */
    private static boolean handleMousePressed(Screen screen, PatternEncodingTermMenu menu,
                                             double mouseX, double mouseY, int button) {
        boolean supported = PatternTypeSupport.supports(menu);
        if (!PatternTypeSelectorState.isOpenFor(menu)) {
            // 面板关着：只认「左键点中胶囊」，其它一概不管（胶囊以外完全不影响 AE2）
            if (!supported || button != GLFW.GLFW_MOUSE_BUTTON_LEFT) {
                return false;
            }
            PatternTypeSelectorLayout.Rect capsule = PatternTypeSelectorLayout.capsulePlacement(
                    screen, PatternTypeSupport.guiLeft(screen), PatternTypeSupport.guiTop(screen)).rect();
            if (!capsule.contains(mouseX, mouseY)) {
                return false;
            }
            PatternTypeSelectorState.openFor(menu);
            ShanhaiMod.LOGGER.info("{} 打开选择面板（menu={}）",
                    PatternTypeSupport.PREFIX, menu.getClass().getSimpleName());
            return true;
        }

        // 面板开着：界面进入模态，所有点击都归我们
        PatternTypeSelectorLayout.Rect panelRect = PatternTypeSelectorLayout.panel(screen);
        PatternTypeSelectorState.PanelState panel = PatternTypeSelectorState.panel();
        if (!panelRect.contains(mouseX, mouseY)) {
            // 点面板外 = 关闭（这一下也吃掉，避免顺手点到 AE2 的槽位把东西拿起来）
            PatternTypeSelectorState.close();
            return true;
        }

        PatternTypeSelectorLayout.Rect search = PatternTypeSelectorLayout.searchBox(panelRect);
        PatternTypeSelectorLayout.Rect clear = PatternTypeSelectorLayout.clearRow(panelRect);
        PatternTypeSelectorLayout.Rect list = PatternTypeSelectorLayout.listArea(panelRect);

        if (search.contains(mouseX, mouseY)) {
            panel.searchFocused = true;
            return true;
        }
        panel.searchFocused = false;

        if (clear.contains(mouseX, mouseY)) {
            commitSelection(menu, null);
            return true;
        }

        int rowIndex = PatternTypeSelectorLayout.rowAt(list, mouseX, mouseY);
        if (rowIndex >= 0) {
            List<RecipeTypeCatalog.Entry> entries = panel.filtered();
            int index = panel.scroll + rowIndex;
            if (index >= 0 && index < entries.size()) {
                commitSelection(menu, entries.get(index).id());
            }
            return true;
        }
        // 面板空白处：吃掉，不做任何事
        return true;
    }

    @SubscribeEvent
    public static void onMouseScrolled(ScreenEvent.MouseScrolled.Pre event) {
        Screen screen = event.getScreen();
        PatternEncodingTermMenu menu = PatternTypeSupport.menuOf(screen);
        if (menu == null || !PatternTypeSelectorState.isOpenFor(menu)) {
            return;
        }
        PatternTypeSelectorLayout.Rect panelRect = PatternTypeSelectorLayout.panel(screen);
        if (!panelRect.contains(event.getMouseX(), event.getMouseY())) {
            // 面板外滚动不拦（AE2 自己还要滚物品列表）
            return;
        }
        scrollBy(screen, event.getScrollDelta() > 0 ? -SCROLL_LINES_PER_NOTCH : SCROLL_LINES_PER_NOTCH);
        event.setCanceled(true);
    }

    // ------------------------------------------------------------------ 键盘

    @SubscribeEvent
    public static void onKeyPressed(ScreenEvent.KeyPressed.Pre event) {
        Screen screen = event.getScreen();
        PatternEncodingTermMenu menu = PatternTypeSupport.menuOf(screen);
        if (menu == null || !PatternTypeSelectorState.isOpenFor(menu)) {
            return;
        }
        PatternTypeSelectorState.PanelState panel = PatternTypeSelectorState.panel();
        int key = event.getKeyCode();

        if (key == GLFW.GLFW_KEY_ESCAPE) {
            PatternTypeSelectorState.close();
        } else if (key == GLFW.GLFW_KEY_BACKSPACE && !panel.query.isEmpty()) {
            int codePoint = panel.query.codePointBefore(panel.query.length());
            panel.query = panel.query.substring(0, panel.query.length() - Character.charCount(codePoint));
            panel.filtered();
        } else if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) {
            List<RecipeTypeCatalog.Entry> entries = panel.filtered();
            if (!entries.isEmpty()) {
                commitSelection(menu, entries.get(0).id());
            }
        } else if (key == GLFW.GLFW_KEY_PAGE_UP) {
            scrollBy(screen, -PatternTypeSelectorLayout.visibleRows(
                    PatternTypeSelectorLayout.listArea(PatternTypeSelectorLayout.panel(screen))));
        } else if (key == GLFW.GLFW_KEY_PAGE_DOWN) {
            scrollBy(screen, PatternTypeSelectorLayout.visibleRows(
                    PatternTypeSelectorLayout.listArea(PatternTypeSelectorLayout.panel(screen))));
        } else if (key == GLFW.GLFW_KEY_UP) {
            scrollBy(screen, -1);
        } else if (key == GLFW.GLFW_KEY_DOWN) {
            scrollBy(screen, 1);
        }
        // 面板开着时所有按键一律吃掉：AE2 的 E / 快捷键 / 搜索框都不该被这层模态界面漏过去
        event.setCanceled(true);
    }

    @SubscribeEvent
    public static void onCharTyped(ScreenEvent.CharacterTyped.Pre event) {
        Screen screen = event.getScreen();
        PatternEncodingTermMenu menu = PatternTypeSupport.menuOf(screen);
        if (menu == null || !PatternTypeSelectorState.isOpenFor(menu)) {
            return;
        }
        PatternTypeSelectorState.PanelState panel = PatternTypeSelectorState.panel();
        char codePoint = event.getCodePoint();
        if (panel.searchFocused && codePoint >= 32 && codePoint != 127 && panel.query.length() < MAX_QUERY_LEN) {
            panel.query = panel.query + codePoint;
            panel.filtered();
        }
        // 一律吃掉：否则字符会灌进 AE2 自己的搜索框
        event.setCanceled(true);
    }

    // ------------------------------------------------------------------ 内部

    /** 选中 / 清除：先下发，成功了才认（界面上不许出现「没生效却显示已选」）。 */
    private static void commitSelection(PatternEncodingTermMenu menu, ResourceLocation id) {
        boolean applied = PatternTypeSupport.apply(menu, id);
        if (applied) {
            PatternTypeSelectorState.setSelection(menu, id);
            ShanhaiMod.LOGGER.info("{} 已选择 recipeType={}", PatternTypeSupport.PREFIX,
                    id == null ? "null（清除）" : id);
        } else {
            ShanhaiMod.LOGGER.error("{} 选择未生效：apply 返回 false（recipeType={}，menu={}）—— 界面不记录这次选择",
                    PatternTypeSupport.PREFIX, id, menu.getClass().getName());
        }
        PatternTypeSelectorState.close();
    }

    private static void scrollBy(Screen screen, int lines) {
        PatternTypeSelectorState.PanelState panel = PatternTypeSelectorState.panel();
        List<RecipeTypeCatalog.Entry> entries = panel.filtered();
        int visible = PatternTypeSelectorLayout.visibleRows(
                PatternTypeSelectorLayout.listArea(PatternTypeSelectorLayout.panel(screen)));
        int max = Math.max(0, entries.size() - visible);
        panel.scroll = PatternTypeSelectorLayout.clamp(panel.scroll + lines, 0, max);
    }

    /** 「这台终端的菜单没被 gtlcore 改过」按屏幕类各打一条 warn（不每帧刷）。 */
    private static void noteUnsupportedOnce(Screen screen, PatternEncodingTermMenu menu) {
        if (PatternTypeSupport.supports(menu)) {
            return;
        }
        if (UNSUPPORTED_LOGGED.add(screen.getClass())) {
            ShanhaiMod.LOGGER.warn("{} 本终端不支持：menu={} 未实现 PatterEncodingTermMenuModify"
                            + "（gtlcore_present={}）⇒ 胶囊显示「本终端不支持」且不响应点击",
                    PatternTypeSupport.PREFIX, menu.getClass().getName(), PatternTypeSupport.gtlcorePresent());
        }
    }
}
