package com.shanhai.client.ptype;

import appeng.menu.me.items.PatternEncodingTermMenu;
import com.shanhai.ShanhaiMod;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.util.List;

/**
 * 全部绘制：左侧边缘的「配方类型」胶囊 + 叠在界面上的选择面板。
 *
 * <h2>1. 画在哪一帧</h2>
 * 由 {@link PatternTypeSelectorEvents} 从 {@code ScreenEvent.Render.Post} 调进来。
 * 那个事件的发射点已 javap 实证：{@code ForgeHooksClient.drawScreenInternal} 里
 * {@code post(Render.Pre)} → {@code Screen.renderWithTooltip(...)} → {@code post(Render.Post)}
 * ⇒ 本面板画在 AE2 窗口<b>之上</b>、tooltip 之后，<b>不需要 mixin 进任何 render 方法</b>。
 * <p>坐标就是 GUI 缩放后的坐标（与 {@code ScreenEvent.Render} 的 mouseX/mouseY 同一套）。
 *
 * <h2>2. 胶囊上写什么（任务书要求「让玩家看得见当前类型」）</h2>
 * <table border="1">
 *   <caption>胶囊文本的四种情况</caption>
 *   <tr><th>情形</th><th>文本</th></tr>
 *   <tr><td>接口拿不到</td><td>{@code 本终端不支持}</td></tr>
 *   <tr><td>有本地选择</td><td>{@code 配方类型：<中文名>}（编码槽那张样板的 NBT 里<b>没有</b>这个 id 时末尾加 {@code *}）</td></tr>
 *   <tr><td>无本地选择、样板里写着</td><td>{@code 配方类型：<中文名>} —— <b>直接从样板 NBT 读出的真实值</b></td></tr>
 *   <tr><td>两者都没有</td><td>{@code 配方类型：未选}</td></tr>
 * </table>
 * 任务书要求「优先读样板里的真实值」：在<b>没有本地意图</b>时（重开终端、JEI 快速上传刚写过）
 * 严格按它执行。玩家刚选完还没再编码时显示本地选择，是为了「点完立刻有反馈」；
 * 此时若样板里是<b>别的</b>值就用 {@code *} 标出来，面板底部还会把「样板内：X」原样打出
 * （见报告 §4「待确认」第 1 条）。
 * <p>⚠️ 样板里那个列表可能是<b>多条</b>（gtlcore 写之前会做代理扩展
 * {@code expandProxiedRecipeTypeIds}），所以判星用集合包含、不用首元素比较。
 *
 * <h2>3. 风格</h2>
 * 深底（{@code 0xF00A0E12}）＋ 1px 细边（{@code 0xFF5A6E7C}）＋ 原版 8px 字体不上阴影，
 * 不做动画、不加贴图，跟着 AE2 走。
 */
@OnlyIn(Dist.CLIENT)
public final class PatternTypeSelectorRenderer {

    private static final String TEXT_TITLE = "选择配方类型";
    private static final String TEXT_CLOSE_HINT = "Esc 关闭";
    private static final String TEXT_SEARCH_PLACEHOLDER = "搜索：中文名 / 英文 id";
    private static final String TEXT_CLEAR = "✕ 清除（下次编码不写类型）";
    private static final String TEXT_PREFIX = "配方类型：";
    private static final String TEXT_NONE = "未选";
    private static final String TEXT_UNSUPPORTED = "本终端不支持";
    private static final String TEXT_EMPTY = "（无匹配）";
    private static final String TEXT_FAILED = "（配方类型读取失败，看日志 " + PatternTypeSupport.PREFIX + "）";

    private static boolean noAnchorLogged;

    private PatternTypeSelectorRenderer() {}

    // ------------------------------------------------------------------ 入口

    /** 由事件层每帧调用一次。 */
    public static void render(Screen screen, GuiGraphics graphics, int mouseX, int mouseY) {
        PatternEncodingTermMenu menu = PatternTypeSupport.menuOf(screen);
        if (menu == null) {
            // 不是样板编码终端 ⇒ 本功能完全不存在（连胶囊都不画）
            return;
        }
        if (!PatternTypeSupport.hasGuiAnchor(screen)) {
            if (!noAnchorLogged) {
                noAnchorLogged = true;
                ShanhaiMod.LOGGER.warn("{} 屏幕 {} 不是 AE2 系界面（无 getGuiLeft/getGuiTop）⇒ 不画选择器",
                        PatternTypeSupport.PREFIX, screen.getClass().getName());
            }
            return;
        }

        Font font = Minecraft.getInstance().font;
        boolean supported = PatternTypeSupport.supports(menu);
        ResourceLocation local = supported ? PatternTypeSelectorState.selection(menu) : null;
        PatternTypeSelectorState.Written written = supported
                ? PatternTypeSelectorState.written(menu)
                : PatternTypeSelectorState.Written.NONE;

        drawCapsule(graphics, font, screen, supported, local, written, mouseX, mouseY);

        if (supported && PatternTypeSelectorState.isOpenFor(menu)) {
            drawPanel(graphics, font, screen, menu, local, written, mouseX, mouseY);
        }
    }

    // ------------------------------------------------------------------ 胶囊

    private static void drawCapsule(GuiGraphics graphics, Font font, Screen screen, boolean supported,
                                    ResourceLocation local, PatternTypeSelectorState.Written written,
                                    int mouseX, int mouseY) {
        PatternTypeSelectorLayout.Placement placement = PatternTypeSelectorLayout.capsulePlacement(
                screen, PatternTypeSupport.guiLeft(screen), PatternTypeSupport.guiTop(screen));
        PatternTypeSelectorLayout.Rect rect = placement.rect();

        int border = supported
                ? PatternTypeSelectorLayout.COLOR_CAPSULE_BORDER
                : PatternTypeSelectorLayout.COLOR_CAPSULE_BORDER_UNSUPPORTED;
        int background = supported
                ? PatternTypeSelectorLayout.COLOR_CAPSULE_BG
                : PatternTypeSelectorLayout.COLOR_CAPSULE_BG_UNSUPPORTED;
        int textColor = supported
                ? PatternTypeSelectorLayout.COLOR_CAPSULE_TEXT
                : PatternTypeSelectorLayout.COLOR_CAPSULE_TEXT_UNSUPPORTED;

        graphics.fill(rect.x(), rect.y(), rect.right(), rect.bottom(), background);
        if (supported && rect.contains(mouseX, mouseY)) {
            graphics.fill(rect.x() + 1, rect.y() + 1, rect.right() - 1, rect.bottom() - 1,
                    PatternTypeSelectorLayout.COLOR_CAPSULE_HOVER);
        }
        outline(graphics, rect, border);

        String text = capsuleText(local, written, supported);
        int textX = rect.x() + 4;
        int textY = rect.y() + (rect.h() - 8) / 2;
        graphics.drawString(font, trim(font, text, rect.w() - 8), textX, textY, textColor, false);
    }

    /** 胶囊文本（口径见类注释 §2）。 */
    static String capsuleText(ResourceLocation local, PatternTypeSelectorState.Written written, boolean supported) {
        if (!supported) {
            return TEXT_UNSUPPORTED;
        }
        ResourceLocation shown = local != null ? local : written.first();
        if (shown == null) {
            return TEXT_PREFIX + TEXT_NONE;
        }
        String name = RecipeTypeCatalog.nameOf(shown);
        // 只有「样板里确实没有当前选择」才打星：gtlcore 写的是代理扩展后的集合，
        // 拿 first 直接比会把「同族多条」误判成旧值（javap 实证 expandProxiedRecipeTypeIds）。
        boolean stale = local != null && !written.empty() && !written.contains(local);
        return TEXT_PREFIX + name + (stale ? "*" : "");
    }

    // ------------------------------------------------------------------ 面板

    private static void drawPanel(GuiGraphics graphics, Font font, Screen screen,
                                  PatternEncodingTermMenu menu, ResourceLocation local,
                                  PatternTypeSelectorState.Written written,
                                  int mouseX, int mouseY) {
        PatternTypeSelectorState.PanelState panel = PatternTypeSelectorState.panel();
        PatternTypeSelectorLayout.Rect rect = PatternTypeSelectorLayout.panel(screen);

        // 半透明遮罩：把后面的 AE2 界面压暗一档，暗示这是模态层
        graphics.fill(0, 0, screen.width, screen.height, 0x60000000);

        graphics.fill(rect.x(), rect.y(), rect.right(), rect.bottom(), PatternTypeSelectorLayout.COLOR_PANEL_BG);
        outline(graphics, rect, PatternTypeSelectorLayout.COLOR_PANEL_BORDER);
        outline(graphics, new PatternTypeSelectorLayout.Rect(rect.x() + 1, rect.y() + 1, rect.w() - 2, rect.h() - 2),
                PatternTypeSelectorLayout.COLOR_PANEL_BORDER_SOFT);

        // ---- 标题行
        PatternTypeSelectorLayout.Rect title = PatternTypeSelectorLayout.title(rect);
        graphics.drawString(font, TEXT_TITLE, title.x(), title.y() + 1,
                PatternTypeSelectorLayout.COLOR_TITLE_TEXT, false);
        graphics.drawString(font, TEXT_CLOSE_HINT, title.right() - font.width(TEXT_CLOSE_HINT), title.y() + 1,
                PatternTypeSelectorLayout.COLOR_DIM_TEXT, false);

        // ---- 搜索框
        PatternTypeSelectorLayout.Rect search = PatternTypeSelectorLayout.searchBox(rect);
        graphics.fill(search.x(), search.y(), search.right(), search.bottom(),
                PatternTypeSelectorLayout.COLOR_SEARCH_BG);
        outline(graphics, search, panel.searchFocused
                ? PatternTypeSelectorLayout.COLOR_SEARCH_BORDER_FOCUS
                : PatternTypeSelectorLayout.COLOR_SEARCH_BORDER);
        String shown = panel.query.isEmpty() ? TEXT_SEARCH_PLACEHOLDER : panel.query;
        int shownColor = panel.query.isEmpty()
                ? PatternTypeSelectorLayout.COLOR_DIM_TEXT
                : PatternTypeSelectorLayout.COLOR_BODY_TEXT;
        String trimmedShown = trim(font, shown, search.w() - 8);
        graphics.drawString(font, trimmedShown, search.x() + 3, search.y() + 3, shownColor, false);
        if (panel.searchFocused && (System.currentTimeMillis() / 500L) % 2L == 0L) {
            int caretX = search.x() + 3 + font.width(trimmedShown) + 1;
            if (caretX < search.right() - 3) {
                graphics.fill(caretX, search.y() + 2, caretX + 1, search.bottom() - 2,
                        PatternTypeSelectorLayout.COLOR_CARET);
            }
        }

        // ---- 「清除」行
        PatternTypeSelectorLayout.Rect clear = PatternTypeSelectorLayout.clearRow(rect);
        if (clear.contains(mouseX, mouseY)) {
            graphics.fill(clear.x(), clear.y(), clear.right(), clear.bottom(),
                    PatternTypeSelectorLayout.COLOR_ROW_HOVER);
        }
        graphics.drawString(font, trim(font, TEXT_CLEAR, clear.w() - 4), clear.x() + 2, clear.y() + 2,
                PatternTypeSelectorLayout.COLOR_CLEAR_TEXT, false);

        // ---- 列表
        PatternTypeSelectorLayout.Rect list = PatternTypeSelectorLayout.listArea(rect);
        List<RecipeTypeCatalog.Entry> entries = panel.filtered();

        graphics.enableScissor(list.x(), list.y(), list.right(), list.bottom());
        int visible = PatternTypeSelectorLayout.visibleRows(list);
        if (entries.isEmpty()) {
            String message = RecipeTypeCatalog.all().isEmpty() ? TEXT_FAILED : TEXT_EMPTY;
            graphics.drawString(font, message, list.x() + 2, list.y() + 2,
                    PatternTypeSelectorLayout.COLOR_DIM_TEXT, false);
        } else {
            for (int i = 0; i < visible; i++) {
                int index = panel.scroll + i;
                if (index < 0 || index >= entries.size()) {
                    break;
                }
                RecipeTypeCatalog.Entry entry = entries.get(index);
                PatternTypeSelectorLayout.Rect row = PatternTypeSelectorLayout.row(list, i);
                boolean hovered = row.contains(mouseX, mouseY);
                boolean isSelected = local != null && local.equals(entry.id());
                if (isSelected) {
                    graphics.fill(row.x(), row.y(), row.right(), row.bottom(),
                            PatternTypeSelectorLayout.COLOR_ROW_SELECTED);
                    graphics.fill(row.x(), row.y(), row.x() + 2, row.bottom(),
                            PatternTypeSelectorLayout.COLOR_SCROLL_THUMB);
                } else if (hovered) {
                    graphics.fill(row.x(), row.y(), row.right(), row.bottom(),
                            PatternTypeSelectorLayout.COLOR_ROW_HOVER);
                }
                int nameColor = isSelected
                        ? PatternTypeSelectorLayout.COLOR_TITLE_TEXT
                        : PatternTypeSelectorLayout.COLOR_BODY_TEXT;
                String idText = entry.id().toString();
                int idWidth = font.width(idText);
                int nameRoom = row.w() - 6 - (idWidth + 6);
                String nameText = trim(font, entry.name(), Math.max(20, nameRoom));
                graphics.drawString(font, nameText, row.x() + 4, row.y() + 1, nameColor, false);
                if (idWidth + 6 < row.w() / 2) {
                    graphics.drawString(font, idText, row.right() - 3 - idWidth, row.y() + 1,
                            PatternTypeSelectorLayout.COLOR_DIM_TEXT, false);
                }
            }
        }
        graphics.disableScissor();

        // ---- 滚动条（指示器；操作方式是滚轮 / PageUp / PageDown）
        if (entries.size() > visible) {
            PatternTypeSelectorLayout.Rect track = PatternTypeSelectorLayout.scrollTrack(list);
            graphics.fill(track.x(), track.y(), track.right(), track.bottom(),
                    PatternTypeSelectorLayout.COLOR_SCROLL_TRACK);
            int thumbHeight = Math.max(8, track.h() * visible / entries.size());
            int maxScroll = Math.max(1, entries.size() - visible);
            int thumbY = track.y() + (track.h() - thumbHeight) * panel.scroll / maxScroll;
            boolean thumbHovered = track.contains(mouseX, mouseY);
            graphics.fill(track.x(), thumbY, track.right(), thumbY + thumbHeight,
                    thumbHovered
                            ? PatternTypeSelectorLayout.COLOR_SCROLL_THUMB_HOVER
                            : PatternTypeSelectorLayout.COLOR_SCROLL_THUMB);
        }

        // ---- 底部统计（左：条数；右：本地选择 + 样板里的真实值）
        PatternTypeSelectorLayout.Rect footer = PatternTypeSelectorLayout.footer(rect);
        String counts = "共 " + RecipeTypeCatalog.all().size() + " · 匹配 " + entries.size();
        graphics.drawString(font, counts, footer.x(), footer.y(),
                PatternTypeSelectorLayout.COLOR_DIM_TEXT, false);

        String realText = "样板内：" + (written.empty()
                ? "无"
                : RecipeTypeCatalog.nameOf(written.first()) + (written.count() > 1 ? " 等" + written.count() + "项" : ""));
        String localText = "已选：" + (local == null ? "无" : RecipeTypeCatalog.nameOf(local));
        String rightText = localText + "  " + realText;
        String trimmedRight = trim(font, rightText, Math.max(0, footer.w() - font.width(counts) - 8));
        if (!trimmedRight.isEmpty()) {
            graphics.drawString(font, trimmedRight, footer.right() - font.width(trimmedRight), footer.y(),
                    PatternTypeSelectorLayout.COLOR_DIM_TEXT, false);
        }
    }

    // ------------------------------------------------------------------ 工具

    /** 1px 描边。 */
    private static void outline(GuiGraphics graphics, PatternTypeSelectorLayout.Rect rect, int color) {
        graphics.fill(rect.x(), rect.y(), rect.right(), rect.y() + 1, color);
        graphics.fill(rect.x(), rect.bottom() - 1, rect.right(), rect.bottom(), color);
        graphics.fill(rect.x(), rect.y() + 1, rect.x() + 1, rect.bottom() - 1, color);
        graphics.fill(rect.right() - 1, rect.y() + 1, rect.right(), rect.bottom() - 1, color);
    }

    /** 按像素宽截断并补省略号。 */
    static String trim(Font font, String text, int maxWidth) {
        if (text == null || text.isEmpty() || maxWidth <= 0) {
            return "";
        }
        if (font.width(text) <= maxWidth) {
            return text;
        }
        String ellipsis = "…";
        int ellipsisWidth = font.width(ellipsis);
        StringBuilder builder = new StringBuilder(text.length());
        int width = 0;
        for (int i = 0; i < text.length(); i++) {
            String ch = String.valueOf(text.charAt(i));
            int charWidth = font.width(ch);
            if (width + charWidth + ellipsisWidth > maxWidth) {
                break;
            }
            builder.append(ch);
            width += charWidth;
        }
        return builder.append(ellipsis).toString();
    }
}
