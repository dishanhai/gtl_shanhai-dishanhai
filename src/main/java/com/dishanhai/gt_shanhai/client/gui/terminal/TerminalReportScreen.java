package com.dishanhai.gt_shanhai.client.gui.terminal;

import com.dishanhai.gt_shanhai.client.gui.glass.*;
import com.dishanhai.gt_shanhai.client.gui.scaled.*;
import com.dishanhai.gt_shanhai.client.gui.shop.ShopGaussBlur;
import com.dishanhai.gt_shanhai.client.gui.shop.ShopUiSounds;
import com.dishanhai.gt_shanhai.common.item.terminal.ShanhaiTerminalReport;
import com.dishanhai.gt_shanhai.common.recipe.editor.ShanhaiRecipeEditorAnimation;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.ItemStack;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import static com.dishanhai.gt_shanhai.client.gui.terminal.TerminalGlassUi.*;

/**
 * 山海：終端完整報告。聊天欄只放摘要，這裡按類別篩選並完整列出全部條目；文字一律換行、不截斷。
 * 同類別、同目標名稱的位置合併成一組：「類別 ⇒ 具體方塊」，每個仓室畫成帶圖示的卡片（同終端設定的等級格），下方列出該組全部坐標。
 */
public final class TerminalReportScreen extends ScaledScreen {
    private static final String PREFIX = "gui.gt_shanhai.ultimate_terminal.report.";
    private static final int PANEL_W = 480, PANEL_H = 340, CONTENT_W = 456, GAP = 6, FOOTER_H = 40;
    private static final int TARGET_W = 700, TARGET_H = 460, BAR_W = 8;
    // 欄線：色條 / 類別 / 目標方塊與坐標；表頭與每一組共用同一組常量。
    private static final int COL_KIND = 8, COL_KIND_W = 108, COL_NAME = 124, COL_NAME_W = CONTENT_W - COL_NAME - 4;
    private static final int STAGGER_ROWS = 24;
    // 仓室卡片：兩欄網格，圖示格 20px、文字緊跟其後。
    private static final int CELL_COLS = 2, CELL_GAP = 4, CELL_ICON = 20, CELL_MIN_H = 26;

    private record Chip(String key, Component text, int category, int x, int y, int w, int h) {}
    private record Group(int category, int name, List<BlockPos> positions) {}
    private record Cell(ItemStack stack, Component text, int x, int y, int w, int h) {}
    // 坐標列表在佈局時只換行一次並快取；大型施工報告的單組坐標可達上萬字，逐幀重新換行會卡。
    private record Row(Group group, Component kind, List<FormattedCharSequence> detailLines,
                       List<FormattedCharSequence> coordLines, List<Cell> cells, int gridH, int y, int h) {}

    private final ShanhaiTerminalReport report;
    private final List<Group> groups = new ArrayList<>();
    private final int[] categoryCounts;
    private final int distinctPositions;
    private final List<Chip> chips = new ArrayList<>();
    private final List<Row> rows = new ArrayList<>();
    private final Spring opening = new Spring(Spring.PANEL);
    private final Spring scroll = new Spring(Spring.SCROLL);
    private final SpringTable<String> selection = new SpringTable<>(Spring.HOVER);
    private final SpringTable<String> stagger = new SpringTable<>(Spring.STAGGER);
    private final GlassPanelTexture root = GlassPanelTexture.root(), display = GlassPanelTexture.display();
    private final ShanhaiRecipeEditorAnimation transition = new ShanhaiRecipeEditorAnimation();
    private TerminalGlassUi ui;
    private int filter = -1, stage, left, top, headerBottom, listHeight, contentHeight, scrollTarget;
    private boolean warmed, draggingBar;
    private Component notice = Component.empty();
    private long noticeUntil;

    public TerminalReportScreen(ShanhaiTerminalReport report) {
        super(Component.translatable(PREFIX + switch (report.type()) {
            case SCAN -> "title_scan";
            case MODULES -> "title_modules";
            case BUILD -> "title_build";
            case CRAFT -> "title_craft";
        }));
        this.report = report;
        targetWidth = TARGET_W; targetHeight = TARGET_H; renderRenderablesOnly = true;
        categoryCounts = new int[report.categories().size()];
        Set<BlockPos> distinct = new HashSet<>();
        Map<Long, Group> grouped = new LinkedHashMap<>();
        for (var line : report.lines()) {
            categoryCounts[line.category()]++;
            distinct.add(line.pos());
            grouped.computeIfAbsent(((long) line.category() << 32) | line.name(),
                    key -> new Group(line.category(), line.name(), new ArrayList<>())).positions().add(line.pos());
        }
        groups.addAll(grouped.values());
        // 同類別排在一起；類別內保持掃描出現順序。
        groups.sort((a, b) -> Integer.compare(a.category(), b.category()));
        // 合成報告的條目沒有世界坐標，「全部」直接計條目數。
        distinctPositions = isCraft() ? report.lines().size() : distinct.size();
        opening.setTarget(1, now()); transition.reset(0, now());
    }

    private boolean isCraft() { return report.type() == ShanhaiTerminalReport.Type.CRAFT; }
    private static Component tr(String key, Object... args) { return Component.translatable(PREFIX + key, args); }
    private int listX() { return left + 12; }
    private int listY() { return top + headerBottom; }
    private static String posText(BlockPos pos) { return pos.getX() + ", " + pos.getY() + ", " + pos.getZ(); }

    // ---------- 佈局 ----------
    @Override protected void initScaled() {
        left = (vWidth - PANEL_W) / 2; top = (vHeight - PANEL_H) / 2;
        ui = new TerminalGlassUi(font);
        layoutHeader(); layoutRows();
    }
    private Component counts() {
        int[] c = report.counts();
        if (report.type() == ShanhaiTerminalReport.Type.MODULES) return tr("counts_modules", c.length > 0 ? c[0] : 0);
        if (report.type() == ShanhaiTerminalReport.Type.BUILD) return tr("counts_build", c[0], c[1], c[2]);
        if (isCraft()) return tr("counts_craft", c[0], c[1], c[2], c[3]);
        return tr("counts_scan", c[0], c[1], c[2], c[3], c[4]);
    }
    private void layoutHeader() {
        int y = 10 + ui.height(getTitle(), PANEL_W - 66) + 4;
        y += ui.height(tr("target", posText(report.target())), CONTENT_W) + 2;
        y += ui.height(counts(), CONTENT_W);
        if (!report.summary().getString().isEmpty()) y += 2 + ui.height(report.summary(), CONTENT_W);
        y += GAP;
        chips.clear();
        int x = 0, rowH = 0;
        for (int i = -1; i < report.categories().size(); i++) {
            if (i >= 0 && categoryCounts[i] == 0) continue;
            Component text;
            if (i < 0) text = tr("filter_all", distinctPositions);
            else {
                int color = report.categories().get(i).color();
                text = report.categories().get(i).label().copy()
                        .withStyle(style -> style.withColor(color))
                        .append(Component.literal(" (" + categoryCounts[i] + ")").withStyle(ChatFormatting.WHITE));
            }
            int w = Math.min(CONTENT_W, font.width(text) + 16);
            int h = Math.max(1, font.split(text, Math.max(1, w - 12)).size()) * font.lineHeight + 10;
            if (x > 0 && x + w > CONTENT_W) { y += rowH + 4; x = 0; rowH = 0; }
            chips.add(new Chip("chip." + i, text, i, x, y, w, h));
            x += w + 4; rowH = Math.max(rowH, h);
        }
        y += rowH + GAP;
        // 表頭佔一行高度，列表緊接其後。
        headerBottom = y + font.lineHeight + 6;
        listHeight = PANEL_H - FOOTER_H - headerBottom;
    }
    private Component coordsText(List<BlockPos> positions) {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < positions.size(); i++) {
            if (i > 0) text.append("   ");
            BlockPos pos = positions.get(i);
            text.append(pos.getX()).append(',').append(pos.getY()).append(',').append(pos.getZ());
        }
        return tr("coords", positions.size()).copy().append(Component.literal(text.toString()));
    }
    /** 把一組方塊排成兩欄卡片網格（相對名稱欄原點），回傳網格總高；文字換行、卡片隨之長高。 */
    private int layoutCells(List<net.minecraft.world.item.Item> items, List<Cell> out) {
        int cellW = (COL_NAME_W - CELL_GAP * (CELL_COLS - 1)) / CELL_COLS;
        int textW = cellW - CELL_ICON - 10;
        int y = 0;
        for (int i = 0; i < items.size(); i += CELL_COLS) {
            int rowH = CELL_MIN_H;
            int end = Math.min(items.size(), i + CELL_COLS);
            for (int j = i; j < end; j++) rowH = Math.max(rowH, ui.height(new ItemStack(items.get(j)).getHoverName(), textW) + 10);
            for (int j = i; j < end; j++) {
                ItemStack stack = new ItemStack(items.get(j));
                out.add(new Cell(stack, stack.getHoverName(), (j - i) * (cellW + CELL_GAP), y, cellW, rowH));
            }
            y += rowH + CELL_GAP;
        }
        return Math.max(0, y - CELL_GAP);
    }
    private void layoutRows() {
        rows.clear();
        int y = 0;
        for (Group group : groups) {
            if (filter >= 0 && group.category() != filter) continue;
            var category = report.categories().get(group.category());
            net.minecraft.network.chat.MutableComponent kind = category.label().copy().withStyle(style -> style.withColor(category.color()));
            if (!isCraft()) kind.append(Component.literal(" ×" + group.positions().size()).withStyle(ChatFormatting.WHITE));
            Component name = report.names().get(group.name());
            List<FormattedCharSequence> coordLines = isCraft() ? List.of() : font.split(coordsText(group.positions()), COL_NAME_W);
            Component detail = report.details().get(group.name());
            List<FormattedCharSequence> detailLines = detail.getString().isEmpty() ? List.of() : font.split(detail, COL_NAME_W);
            int belowH = (detailLines.size() + coordLines.size()) * font.lineHeight;
            List<Cell> cells = new ArrayList<>();
            int gridH = layoutCells(report.icons().get(group.name()), cells);
            if (cells.isEmpty()) gridH = ui.height(name, COL_NAME_W);
            int h = Math.max(ui.height(kind, COL_KIND_W), gridH + (belowH > 0 ? 3 : 0) + belowH) + 8;
            rows.add(new Row(group, kind, detailLines, coordLines, cells, gridH, y, Math.max(20, h)));
            y += Math.max(20, h) + 2;
        }
        contentHeight = y;
        scrollTarget = Math.min(scrollTarget, Math.max(0, contentHeight - listHeight));
        if (scroll.value() > Math.max(0, contentHeight - listHeight)) scroll.snap(scrollTarget);
    }
    private void setFilter(int next) {
        if (next == filter) return;
        filter = next; scrollTarget = 0; scroll.snap(0);
        transition.advance(++stage, now());
        if (reduced()) transition.reset(stage, now());
        layoutRows();
        stagger.clear();
        for (int i = 0; i < Math.min(STAGGER_ROWS, rows.size()); i++)
            stagger.start("row." + i, reduced() ? 1 : 0, 1, now(), Math.min(240L, i * 12L));
    }

    @Override public void tick() {
        super.tick();
        if (!warmed) { warmed = true; if (!reduced()) GlassBlur.warmUp(); }
    }

    // ---------- 繪製 ----------
    @Override protected void renderScaledBackground(GuiGraphics g, int mx, int my, float pt) {
        long at = now(); ShopGaussBlur.clear(); ShopGaussBlur.clearTopLayer();
        float open = spring(opening, 1, at);
        spring(scroll, scrollTarget, at);
        ui.frame(mx, my, at); selection.sweep(at);
        transition.advance(stage, at);
        if (reduced()) transition.reset(stage, at);
        GuiRenderUtil.withPanelMotion(g, left + PANEL_W / 2f, top + PANEL_H / 2f, open, () -> {
            root.draw(g, mx, my, left, top, PANEL_W, PANEL_H);
            int y = top + 10;
            ui.wrap(g, getTitle(), left + 12, y, PANEL_W - 66, GlassPalette.TEXT_HEAD);
            ui.button(g, "close", left + PANEL_W - 40, top + 8, 28, 22, Component.literal("×"), 16, true, mx, my, null);
            y += ui.height(getTitle(), PANEL_W - 66) + 4;
            Component target = tr("target", posText(report.target()));
            ui.wrap(g, target, left + 12, y, CONTENT_W, GlassPalette.TEXT_MUTED);
            y += ui.height(target, CONTENT_W) + 2;
            ui.wrap(g, counts(), left + 12, y, CONTENT_W, GlassPalette.TEXT);
            if (!report.summary().getString().isEmpty())
                ui.wrap(g, report.summary(), left + 12, y + ui.height(counts(), CONTENT_W) + 2, CONTENT_W, GlassPalette.TEXT);
            for (Chip chip : chips) {
                ui.button(g, chip.key(), listX() + chip.x(), top + chip.y(), chip.w(), chip.h(), chip.text(), Math.max(1, chip.w() - 12), true, mx, my, null);
                float chosen = follow(selection, chip.key(), chip.category() == filter ? 1 : 0, at);
                if (chosen > 0.02f) g.renderOutline(listX() + chip.x(), top + chip.y(), chip.w(), chip.h(), GuiMotion.lerpColor(0x0069E8FF, GlassPalette.ACCENT, chosen));
            }
            int headY = top + headerBottom - font.lineHeight - 4;
            ui.wrap(g, tr("col_kind"), listX() + COL_KIND, headY, COL_KIND_W, GlassPalette.TEXT_DIM);
            ui.wrap(g, tr(isCraft() ? "col_block_craft" : "col_block"), listX() + COL_NAME, headY, COL_NAME_W, GlassPalette.TEXT_DIM);
            display.draw(g, mx, my, listX() - 3, listY() - 3, CONTENT_W + 6, listHeight + 6);
            drawRows(g, mx, my, at);
            drawScrollbar(g);
            drawFooter(g, at);
            if (!reduced() && transition.moving(at))
                GlassBlur.requestPage(g, new GuiRect(listX(), listY(), CONTENT_W, listHeight), GlassMotion.pageSlide(transition.linear(at)));
        });
    }
    private void drawRows(GuiGraphics g, int mx, int my, long at) {
        if (rows.isEmpty()) { ui.wrap(g, tr("empty"), listX() + 4, listY() + 4, CONTENT_W - 8, GlassPalette.TEXT_MUTED); return; }
        TerminalGlassUi.scissor(g, listX(), listY(), CONTENT_W, listHeight);
        try {
            int scrollY = Math.round(scroll.value());
            for (int i = 0; i < rows.size(); i++) {
                Row row = rows.get(i);
                int y = listY() + row.y() - scrollY;
                if (y + row.h() < listY()) continue;
                if (y > listY() + listHeight) break;
                int color = report.categories().get(row.group().category()).color();
                int x = listX();
                float appear = reduced() || i >= STAGGER_ROWS ? 1 : stagger.valueOr("row." + i, 1, at);
                boolean over = hit(mx, my, x, y, CONTENT_W, row.h()) && hit(mx, my, listX(), listY(), CONTENT_W, listHeight);
                float hot = follow(selection, "hot." + i, over ? 1 : 0, at);
                int rowY = y;
                GuiRenderUtil.withPanelMotion(g, x + CONTENT_W / 2f, y + row.h() / 2f, appear, () -> {
                    g.pose().pushPose(); g.pose().translate(0, (1 - appear) * 8, 0);
                    g.fill(x, rowY, x + CONTENT_W, rowY + row.h(), GuiMotion.lerpColor(GlassPalette.ROW_FILL, GlassPalette.ROW_FILL_HOVER, hot));
                    g.renderOutline(x, rowY, CONTENT_W, row.h(), GuiMotion.lerpColor(GlassPalette.ROW_EDGE, GlassPalette.ACCENT, hot));
                    g.fill(x + 1, rowY + 1, x + 4, rowY + row.h() - 1, 0xFF000000 | color);
                    ui.wrap(g, row.kind(), x + COL_KIND, rowY + 4, COL_KIND_W, GlassPalette.TEXT);
                    if (row.cells().isEmpty())
                        ui.wrap(g, report.names().get(row.group().name()), x + COL_NAME, rowY + 4, COL_NAME_W, GlassPalette.TEXT);
                    else drawCells(g, row, x + COL_NAME, rowY + 4, mx, my);
                    int lineY = rowY + 4 + row.gridH() + 3;
                    for (FormattedCharSequence line : row.detailLines()) {
                        if (lineY + font.lineHeight >= listY() && lineY <= listY() + listHeight)
                            g.drawString(font, line, x + COL_NAME, lineY, GlassPalette.TEXT_MUTED, false);
                        lineY += font.lineHeight;
                    }
                    for (FormattedCharSequence line : row.coordLines()) {
                        if (lineY + font.lineHeight >= listY() && lineY <= listY() + listHeight)
                            g.drawString(font, line, x + COL_NAME, lineY, GlassPalette.TEXT_DIM, false);
                        lineY += font.lineHeight;
                    }
                    g.pose().popPose();
                });
            }
        } finally { g.disableScissor(); }
    }
    private void drawCells(GuiGraphics g, Row row, int originX, int originY, int mx, int my) {
        boolean inList = hit(mx, my, listX(), listY(), CONTENT_W, listHeight);
        for (Cell cell : row.cells()) {
            int x = originX + cell.x(), y = originY + cell.y();
            if (y + cell.h() < listY() || y > listY() + listHeight) continue;
            boolean over = inList && hit(mx, my, x, y, cell.w(), cell.h());
            g.fill(x, y, x + cell.w(), y + cell.h(), GlassPalette.DISPLAY_FILL);
            g.renderOutline(x, y, cell.w(), cell.h(), over ? GlassPalette.ACCENT : GlassPalette.ROW_EDGE);
            GlassCell.draw(g, x + 3, y + 3, CELL_ICON, GlassPalette.ROW_EDGE, 0, over);
            g.renderItem(cell.stack(), x + 5, y + 5);
            ui.wrap(g, cell.text(), x + CELL_ICON + 7, y + 5, cell.w() - CELL_ICON - 10, GlassPalette.TEXT);
        }
    }
    private int thumbHeight() { return Math.max(14, listHeight * listHeight / Math.max(1, contentHeight)); }
    private void drawScrollbar(GuiGraphics g) {
        if (contentHeight <= listHeight) return;
        int thumb = thumbHeight();
        int thumbY = listY() + Math.round((listHeight - thumb) * scroll.value() / Math.max(1, contentHeight - listHeight));
        g.fill(listX() + CONTENT_W + 2, listY(), listX() + CONTENT_W + 4, listY() + listHeight, GlassPalette.DISPLAY_EDGE);
        g.fill(listX() + CONTENT_W + 2, thumbY, listX() + CONTENT_W + 4, thumbY + thumb, draggingBar ? GlassPalette.TEXT : GlassPalette.ACCENT);
    }
    private void drawFooter(GuiGraphics g, long at) {
        int y = top + PANEL_H - FOOTER_H + 6;
        boolean flashing = at < noticeUntil;
        Component line = flashing ? notice : tr(isCraft() ? "copy_hint_craft" : "copy_hint");
        ui.wrap(g, line, left + 12, y, CONTENT_W, flashing ? GlassPalette.OK : GlassPalette.TEXT_MUTED);
        if (report.omitted() > 0)
            ui.wrap(g, tr("omitted", report.omitted()).copy().withStyle(ChatFormatting.YELLOW), left + 12,
                    y + ui.height(line, CONTENT_W) + 2, CONTENT_W, GlassPalette.TEXT_MUTED);
    }

    // ---------- 輸入 ----------
    @Override protected boolean universalMouseClicked(double mx, double my, int button) {
        if (hit(mx, my, left + PANEL_W - 40, top + 8, 28, 22)) { onClose(); return true; }
        if (button != 0) return false;
        for (Chip chip : chips) {
            if (!hit(mx, my, listX() + chip.x(), top + chip.y(), chip.w(), chip.h())) continue;
            ui.feedback.press(chip.key().hashCode(), listX() + chip.x(), top + chip.y(), chip.w(), chip.h(), true, now());
            ShopUiSounds.click(); setFilter(chip.category()); return true;
        }
        if (contentHeight > listHeight && hit(mx, my, listX() + CONTENT_W, listY(), BAR_W, listHeight)) {
            draggingBar = true; dragTo(my); return true;
        }
        if (!hit(mx, my, listX(), listY(), CONTENT_W, listHeight)) return false;
        int scrollY = Math.round(scroll.value());
        for (Row row : rows) {
            if (!hit(mx, my, listX(), listY() + row.y() - scrollY, CONTENT_W, row.h())) continue;
            if (isCraft()) {
                String text = report.names().get(row.group().name()).getString();
                String detail = report.details().get(row.group().name()).getString();
                Minecraft.getInstance().keyboardHandler.setClipboard(detail.isEmpty() ? text : text + "\n" + detail);
                notice = tr("copied_craft"); noticeUntil = now() + 2500L;
                ShopUiSounds.click(); return true;
            }
            StringBuilder coords = new StringBuilder();
            for (BlockPos pos : row.group().positions()) {
                if (coords.length() > 0) coords.append('\n');
                coords.append(pos.getX()).append(' ').append(pos.getY()).append(' ').append(pos.getZ());
            }
            Minecraft.getInstance().keyboardHandler.setClipboard(coords.toString());
            notice = tr("copied", row.group().positions().size()); noticeUntil = now() + 2500L;
            ShopUiSounds.click(); return true;
        }
        return false;
    }
    private void dragTo(double my) {
        int range = Math.max(1, listHeight - thumbHeight());
        double ratio = Math.max(0, Math.min(1, (my - listY() - thumbHeight() / 2.0) / range));
        scrollTarget = (int) Math.round(ratio * Math.max(0, contentHeight - listHeight));
        scroll.snap(scrollTarget);
    }
    @Override protected boolean universalMouseDragged(double mx, double my, int button, double dx, double dy) {
        if (!draggingBar) return super.universalMouseDragged(mx, my, button, dx, dy);
        dragTo(my); return true;
    }
    @Override protected boolean universalMouseReleased(double mx, double my, int button) {
        draggingBar = false; ui.feedback.release(now()); return super.universalMouseReleased(mx, my, button);
    }
    @Override protected boolean universalMouseScrolled(double mx, double my, double amount) {
        if (!hit(mx, my, listX(), listY(), CONTENT_W + BAR_W, listHeight)) return false;
        scrollTarget = Math.max(0, Math.min(Math.max(0, contentHeight - listHeight), scrollTarget - (int) (amount * 36)));
        return true;
    }
    @Override public boolean isPauseScreen() { return false; }
    @Override public void removed() {
        ui.feedback.cancel(now()); ShopGaussBlur.clear(); ShopGaussBlur.clearTopLayer(); super.removed();
    }
}
