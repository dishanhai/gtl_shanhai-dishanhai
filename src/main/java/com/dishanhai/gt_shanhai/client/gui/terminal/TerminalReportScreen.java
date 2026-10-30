package com.dishanhai.gt_shanhai.client.gui.terminal;

import com.dishanhai.gt_shanhai.client.gui.glass.*;
import com.dishanhai.gt_shanhai.client.gui.scaled.*;
import com.dishanhai.gt_shanhai.client.gui.shop.ShopGaussBlur;
import com.dishanhai.gt_shanhai.client.ShanhaiStructureHighlightClient;
import com.dishanhai.gt_shanhai.client.gui.shop.ShopUiSounds;
import com.dishanhai.gt_shanhai.network.ShanhaiStructureHighlightPacket;
import com.dishanhai.gt_shanhai.common.item.terminal.ShanhaiTerminalReport;
import com.dishanhai.gt_shanhai.common.recipe.editor.ShanhaiRecipeEditorAnimation;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.contents.TranslatableContents;
import com.dishanhai.gt_shanhai.common.machine.part.StellarPatternOrderInfo;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.ItemStack;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Collections;
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
    // 坐標列表超過 FOLD_LINES 行就折疊，按「展開」才顯示全文；「顯示坐標方框」把整組坐標在世界裡畫成方框。
    private static final int FOLD_LINES = 3, BTN_GAP = 4, BOX_SECONDS = 30, BOX_MAX = 4096;
    // 坐標按等寬欄對齊排成網格，只畫看得見的行；不再整段換行（上萬個坐標的換行計算會卡死）。
    private static final int COORD_PAD = 10;
    // 列表在畫面線程上分片載入：每幀最多花這麼久，其餘留給下一幀，載入期間顯示動畫。
    private static final long LOAD_BUDGET_NS = 6_000_000L;
    // 仓室卡片：兩欄網格，圖示格 20px、文字緊跟其後。
    private static final int CELL_COLS = 2, CELL_GAP = 4, CELL_ICON = 20, CELL_MIN_H = 26;

    private record Chip(String key, Component text, int category, int x, int y, int w, int h) {}
    private record Group(int category, int name, List<BlockPos> positions) {
        long key() { return ((long) category << 32) | name; }
    }
    private record Cell(ItemStack stack, Component text, int x, int y, int w, int h) {}
    private record Row(Group group, Component kind, List<FormattedCharSequence> detailLines, Component coordsHeader,
                       int coordCols, int coordCellW, int coordShown, List<Cell> cells, int gridH,
                       boolean foldable, boolean open, boolean boxable, int btnY, int y, int h) {
        Row at(int newY) {
            return new Row(group, kind, detailLines, coordsHeader, coordCols, coordCellW, coordShown, cells, gridH,
                    foldable, open, boxable, btnY, newY, h);
        }
    }

    private final ShanhaiTerminalReport report;
    private final List<Group> groups = new ArrayList<>();
    private final int[] categoryCounts;
    private final int distinctPositions;
    private final List<Chip> chips = new ArrayList<>();
    private final List<Row> rows = new ArrayList<>();
    private final Set<Long> expanded = new HashSet<>();
    private final Spring opening = new Spring(Spring.PANEL);
    private final Spring scroll = new Spring(Spring.SCROLL);
    private final SpringTable<String> selection = new SpringTable<>(Spring.HOVER);
    private final SpringTable<String> stagger = new SpringTable<>(Spring.STAGGER);
    private final GlassPanelTexture root = GlassPanelTexture.root(), display = GlassPanelTexture.display();
    private final ShanhaiRecipeEditorAnimation transition = new ShanhaiRecipeEditorAnimation();
    private TerminalGlassUi ui;
    private int filter = -1, stage, left, top, headerBottom, listHeight, contentHeight, scrollTarget;
    private List<Group> layoutGroups = List.of();
    private int layoutCursor, layoutY;
    private boolean warmed, draggingBar, loading;
    private Component notice = Component.empty();
    private long noticeUntil;
    private int restoreScroll = -1;

    public TerminalReportScreen(ShanhaiTerminalReport report) {
        super(Component.translatable(PREFIX + switch (report.type()) {
            case SCAN -> "title_scan";
            case MODULES -> "title_modules";
            case BUILD -> "title_build";
            case CRAFT -> "title_craft";
            case STELLAR_STUCK -> "title_stellar_stuck";
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
        // 合成與告警報告按條目計數；多個告警槽可以來自同一個座標。
        distinctPositions = isTextReport() ? report.lines().size() : distinct.size();
        opening.setTarget(1, now()); transition.reset(0, now());
    }

    private boolean isCraft() { return report.type() == ShanhaiTerminalReport.Type.CRAFT; }
    private boolean isStellarStuck() { return report.type() == ShanhaiTerminalReport.Type.STELLAR_STUCK; }
    private boolean isTextReport() { return isCraft() || isStellarStuck(); }
    private boolean hasWorldCoordinates() { return !isTextReport(); }
    public long reportId() { return report.id(); }

    /** 同一星律訂單狀態更新時，保留使用者已展開的紀錄與閱讀位置。 */
    public TerminalReportScreen refreshed(ShanhaiTerminalReport updated) {
        Set<String> openNames = new HashSet<>();
        for (Group group : groups) if (expanded.contains(group.key())) openNames.add(report.names().get(group.name()).getString());
        var next = new TerminalReportScreen(updated);
        for (Group group : next.groups) if (openNames.contains(updated.names().get(group.name()).getString())) next.expanded.add(group.key());
        next.filter = filter;
        if (filter <= -2 && next.stateCount(-filter - 2) == 0) next.filter = -1;
        next.restoreScroll = scrollTarget;
        next.opening.snap(1);
        return next;
    }
    private static Component tr(String key, Object... args) { return Component.translatable(PREFIX + key, args); }
    private int listX() { return left + 12; }
    private int listY() { return top + headerBottom; }
    private static String posText(BlockPos pos) { return pos.getX() + ", " + pos.getY() + ", " + pos.getZ(); }
    private Component targetLine() {
        return isStellarStuck() && !report.summary().getString().isEmpty()
                ? report.summary() : tr("target", posText(report.target()));
    }
    private boolean hasSeparateSummary() { return !isStellarStuck() && !report.summary().getString().isEmpty(); }

    // ---------- 佈局 ----------
    @Override protected void initScaled() {
        left = (vWidth - PANEL_W) / 2; top = (vHeight - PANEL_H) / 2;
        ui = new TerminalGlassUi(font);
        layoutHeader(); startLayout();
    }
    private Component counts() {
        int[] c = report.counts();
        if (isStellarStuck() && c.length >= 5) return tr("counts_history", c[0], c[1], c[4], c[3], c[2]);
        if (isStellarStuck()) return tr("counts_stellar_stuck", c.length > 0 ? c[0] : 0);
        if (report.type() == ShanhaiTerminalReport.Type.MODULES) return tr("counts_modules", c.length > 0 ? c[0] : 0);
        if (report.type() == ShanhaiTerminalReport.Type.BUILD) return tr("counts_build", c[0], c[1], c[2]);
        if (isCraft()) return tr("counts_craft", c[0], c[1], c[2], c[3]);
        return tr("counts_scan", c[0], c[1], c[2], c[3], c[4]);
    }
    private void layoutHeader() {
        int y = 10 + ui.height(getTitle(), PANEL_W - 66) + 4;
        y += ui.height(targetLine(), CONTENT_W) + 2;
        y += ui.height(counts(), CONTENT_W);
        if (hasSeparateSummary()) y += 2 + ui.height(report.summary(), CONTENT_W);
        y += GAP;
        chips.clear();
        int x = 0, rowH = 0;
        int chipCount = isStellarStuck() ? StellarPatternOrderInfo.State.values().length : report.categories().size();
        for (int i = -1; i < chipCount; i++) {
            int count = i < 0 ? distinctPositions : isStellarStuck() ? stateCount(i) : categoryCounts[i];
            if (i >= 0 && count == 0) continue;
            Component text;
            if (i < 0) text = tr("filter_all", distinctPositions);
            else if (isStellarStuck()) text = tr("order_state_" + StellarPatternOrderInfo.State.values()[i].name().toLowerCase(java.util.Locale.ROOT))
                    .copy().append(" (" + count + ")");
            else {
                int color = report.categories().get(i).color();
                text = report.categories().get(i).label().copy()
                        .withStyle(style -> style.withColor(color))
                        .append(Component.literal(" (" + categoryCounts[i] + ")").withStyle(ChatFormatting.WHITE));
            }
            int w = Math.min(CONTENT_W, font.width(text) + 16);
            int h = Math.max(1, font.split(text, Math.max(1, w - 12)).size()) * font.lineHeight + 10;
            if (x > 0 && x + w > CONTENT_W) { y += rowH + 4; x = 0; rowH = 0; }
            int category = isStellarStuck() && i >= 0 ? -i - 2 : i;
            chips.add(new Chip("chip." + category, text, category, x, y, w, h));
            x += w + 4; rowH = Math.max(rowH, h);
        }
        y += rowH + GAP;
        // 表頭佔一行高度，列表緊接其後。
        headerBottom = y + font.lineHeight + 6;
        listHeight = PANEL_H - FOOTER_H - headerBottom;
    }

    private int categoryState(int category) {
        if (report.categories().get(category).label().getContents() instanceof TranslatableContents contents) {
            for (var state : StellarPatternOrderInfo.State.values()) {
                if (contents.getKey().equals(PREFIX + "order_state_" + state.name().toLowerCase(java.util.Locale.ROOT))) return state.ordinal();
            }
        }
        return StellarPatternOrderInfo.State.UNTRACKED.ordinal();
    }

    private int stateCount(int state) {
        int count = 0;
        for (int category = 0; category < categoryCounts.length; category++) if (categoryState(category) == state) count += categoryCounts[category];
        return count;
    }

    private boolean matchesFilter(Group group) {
        return isStellarStuck() && filter <= -2 ? categoryState(group.category()) == -filter - 2
                : filter < 0 || group.category() == filter;
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
    private static int digits(int value) {
        int len = value < 0 ? 1 : 0;
        long v = Math.abs((long) value);
        do { len++; v /= 10; } while (v > 0);
        return len;
    }
    /** 重新開始載入列表：清空後由 pumpLayout 每幀分片補齊，畫面不會被一次性佈局卡住。 */
    private void startLayout() {
        rows.clear();
        layoutY = 0; layoutCursor = 0; contentHeight = 0;
        List<Group> shown = new ArrayList<>();
        for (Group group : groups) if (matchesFilter(group)) shown.add(group);
        layoutGroups = shown;
        loading = !shown.isEmpty();
        scrollTarget = 0; scroll.snap(0);
    }
    private void pumpLayout(long budgetNs) {
        if (!loading || ui == null) return;
        long start = System.nanoTime();
        do {
            if (layoutCursor >= layoutGroups.size()) break;
            Row row = buildRow(layoutGroups.get(layoutCursor++), layoutY);
            rows.add(row);
            layoutY += row.h() + 2;
        } while (System.nanoTime() - start < budgetNs);
        contentHeight = layoutY;
        if (layoutCursor >= layoutGroups.size()) {
            loading = false;
            if (restoreScroll >= 0) {
                scrollTarget = Math.min(restoreScroll, Math.max(0, contentHeight - listHeight));
                scroll.snap(scrollTarget); restoreScroll = -1;
            }
        }
    }
    private Row buildRow(Group group, int y) {
        var category = report.categories().get(group.category());
        net.minecraft.network.chat.MutableComponent kind = category.label().copy().withStyle(style -> style.withColor(category.color()));
        if (hasWorldCoordinates()) kind.append(Component.literal(" ×" + group.positions().size()).withStyle(ChatFormatting.WHITE));
        Component name = report.names().get(group.name());
        List<BlockPos> positions = group.positions();
        int cols = 1, cellW = COL_NAME_W, gridLines = 0;
        if (hasWorldCoordinates() && !positions.isEmpty()) {
            int maxLen = 0;
            for (BlockPos pos : positions)
                maxLen = Math.max(maxLen, digits(pos.getX()) + digits(pos.getY()) + digits(pos.getZ()) + 2);
            cellW = font.width("0".repeat(maxLen)) + COORD_PAD;
            cols = Math.max(1, COL_NAME_W / cellW);
            gridLines = (positions.size() + cols - 1) / cols;
        }
        boolean foldable = isStellarStuck() || gridLines > FOLD_LINES, open = expanded.contains(group.key());
        int shown = foldable && !open ? FOLD_LINES : gridLines;
        boolean boxable = hasWorldCoordinates() && !positions.isEmpty();
        Component detail = report.details().get(group.name());
        // 告警預設只畫摘要；展開該槽才物化完整文字並換行，避免一次處理全部長報錯。
        List<FormattedCharSequence> detailLines = isStellarStuck() && !open ? List.of()
                : detail.getString().isEmpty() ? List.of() : font.split(detail, COL_NAME_W);
        int belowH = (detailLines.size() + (gridLines > 0 ? 1 + shown : 0)) * font.lineHeight;
        List<Cell> cells = new ArrayList<>();
        int gridH = layoutCells(report.icons().get(group.name()), cells);
        if (cells.isEmpty()) gridH = ui.height(name, COL_NAME_W);
        int btnY = 4 + gridH + (belowH > 0 ? 3 : 0) + belowH + BTN_GAP;
        int h = Math.max(ui.height(kind, COL_KIND_W), btnY - 4 + (foldable || boxable ? font.lineHeight + 10 + BTN_GAP : 0)) + 8;
        return new Row(group, kind, detailLines, gridLines > 0 ? tr("coords", positions.size()) : Component.empty(),
                cols, cellW, gridLines > 0 ? shown : 0, cells, gridH, foldable, open, boxable, btnY, y, Math.max(20, h));
    }
    /** 折疊／展開只重算這一行，後面的行整體平移，不重新載入整個列表。 */
    private void relayoutRow(int index) {
        Row old = rows.get(index);
        Row fresh = buildRow(old.group(), old.y());
        int delta = fresh.h() - old.h();
        rows.set(index, fresh);
        for (int j = index + 1; j < rows.size(); j++) rows.set(j, rows.get(j).at(rows.get(j).y() + delta));
        layoutY += delta;
        contentHeight = layoutY;
        scrollTarget = Math.min(scrollTarget, Math.max(0, contentHeight - listHeight));
        if (scroll.value() > Math.max(0, contentHeight - listHeight)) scroll.snap(scrollTarget);
    }
    private void setFilter(int next) {
        if (next == filter) return;
        filter = next; scrollTarget = 0; scroll.snap(0);
        transition.advance(++stage, now());
        if (reduced()) transition.reset(stage, now());
        startLayout();
        stagger.clear();
        for (int i = 0; i < Math.min(STAGGER_ROWS, layoutGroups.size()); i++)
            stagger.start("row." + i, reduced() ? 1 : 0, 1, now(), Math.min(240L, i * 12L));
    }

    @Override public void tick() {
        super.tick();
        if (!warmed) { warmed = true; if (!reduced()) GlassBlur.warmUp(); }
    }

    // ---------- 繪製 ----------
    @Override protected void renderScaledBackground(GuiGraphics g, int mx, int my, float pt) {
        long at = now(); ShopGaussBlur.clear(); ShopGaussBlur.clearTopLayer();
        pumpLayout(LOAD_BUDGET_NS);
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
            Component target = targetLine();
            ui.wrap(g, target, left + 12, y, CONTENT_W, GlassPalette.TEXT_MUTED);
            y += ui.height(target, CONTENT_W) + 2;
            ui.wrap(g, counts(), left + 12, y, CONTENT_W, GlassPalette.TEXT);
            if (hasSeparateSummary())
                ui.wrap(g, report.summary(), left + 12, y + ui.height(counts(), CONTENT_W) + 2, CONTENT_W, GlassPalette.TEXT);
            for (Chip chip : chips) {
                ui.button(g, chip.key(), listX() + chip.x(), top + chip.y(), chip.w(), chip.h(), chip.text(), Math.max(1, chip.w() - 12), true, mx, my, null);
                float chosen = follow(selection, chip.key(), chip.category() == filter ? 1 : 0, at);
                if (chosen > 0.02f) g.renderOutline(listX() + chip.x(), top + chip.y(), chip.w(), chip.h(), GuiMotion.lerpColor(0x0069E8FF, GlassPalette.ACCENT, chosen));
            }
            int headY = top + headerBottom - font.lineHeight - 4;
            ui.wrap(g, tr("col_kind"), listX() + COL_KIND, headY, COL_KIND_W, GlassPalette.TEXT_DIM);
            ui.wrap(g, tr(isStellarStuck() ? "col_stellar_stuck" : isCraft() ? "col_block_craft" : "col_block"), listX() + COL_NAME, headY, COL_NAME_W, GlassPalette.TEXT_DIM);
            display.draw(g, mx, my, listX() - 3, listY() - 3, CONTENT_W + 6, listHeight + 6);
            drawRows(g, mx, my, at);
            drawScrollbar(g);
            drawFooter(g, at);
            Style hoveredLink = reportLinkAt(mx, my);
            if (hoveredLink != null) g.renderComponentHoverEffect(font, hoveredLink, mx, my);
            if (!reduced() && transition.moving(at))
                GlassBlur.requestPage(g, new GuiRect(listX(), listY(), CONTENT_W, listHeight), GlassMotion.pageSlide(transition.linear(at)));
        });
    }
    private void drawRows(GuiGraphics g, int mx, int my, long at) {
        if (rows.isEmpty()) {
            if (loading) drawSpinner(g, listX() + CONTENT_W / 2, listY() + listHeight / 2, 14, at);
            else ui.wrap(g, tr("empty"), listX() + 4, listY() + 4, CONTENT_W - 8, GlassPalette.TEXT_MUTED);
            return;
        }
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
                int rowIndex = i;
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
                    if (row.coordShown() > 0) {
                        ui.wrap(g, row.coordsHeader(), x + COL_NAME, lineY, COL_NAME_W, GlassPalette.TEXT_DIM);
                        lineY += font.lineHeight;
                        List<BlockPos> positions = row.group().positions();
                        for (int k = 0; k < row.coordShown(); k++, lineY += font.lineHeight) {
                            if (lineY + font.lineHeight < listY() || lineY > listY() + listHeight) continue;
                            for (int c = 0; c < row.coordCols(); c++) {
                                int index = k * row.coordCols() + c;
                                if (index >= positions.size()) break;
                                BlockPos pos = positions.get(index);
                                g.drawString(font, pos.getX() + "," + pos.getY() + "," + pos.getZ(),
                                        x + COL_NAME + c * row.coordCellW(), lineY, GlassPalette.TEXT_DIM, false);
                            }
                        }
                    }
                    int bx = x + COL_NAME;
                    if (row.foldable()) {
                        int w = btnW(expandLabel(row));
                        ui.button(g, "exp." + rowIndex, bx, rowY + row.btnY(), w, btnH(), expandLabel(row), Math.max(1, w - 12), true, mx, my, null);
                        bx += w + BTN_GAP;
                    }
                    if (row.boxable())
                        ui.button(g, "box." + rowIndex, bx, rowY + row.btnY(), btnW(boxLabel()), btnH(), boxLabel(), Math.max(1, btnW(boxLabel()) - 12), true, mx, my, null);
                    g.pose().popPose();
                });
            }
        } finally { g.disableScissor(); }
    }
    private Component expandLabel(Row row) {
        return tr(isStellarStuck() ? (row.open() ? "collapse_details" : "expand_details")
                : row.open() ? "collapse" : "expand");
    }
    private Component boxLabel() { return tr("show_boxes"); }
    private int btnW(Component label) { return font.width(label) + 16; }
    private int btnH() { return font.lineHeight + 10; }
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
    /** 載入動畫：一圈小方塊，亮點沿圓周旋轉並拖出漸暗的尾巴。 */
    private void drawSpinner(GuiGraphics g, int cx, int cy, int radius, long at) {
        if (reduced()) return;
        int dots = 8, head = (int) ((at / 90L) % dots);
        for (int i = 0; i < dots; i++) {
            double angle = Math.PI * 2 * i / dots;
            int px = cx + (int) Math.round(Math.cos(angle) * radius) - 1, py = cy + (int) Math.round(Math.sin(angle) * radius) - 1;
            int age = (head - i + dots) % dots;
            int alpha = 255 - age * (200 / dots);
            g.fill(px, py, px + 3, py + 3, (alpha << 24) | (GlassPalette.ACCENT & 0xFFFFFF));
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
        Component line = flashing ? notice : tr(isStellarStuck() ? "copy_hint_stellar_stuck" : isCraft() ? "copy_hint_craft" : "copy_hint");
        int textX = left + 12;
        if (loading) {
            drawSpinner(g, left + 18, y + 4, 5, at);
            textX += 20; line = tr("loading", layoutCursor, layoutGroups.size());
        }
        ui.wrap(g, line, textX, y, CONTENT_W - (textX - left - 12), flashing && !loading ? GlassPalette.OK : GlassPalette.TEXT_MUTED);
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
        Style link = reportLinkAt(mx, my);
        if (link != null && handleComponentClicked(link)) {
            ShopUiSounds.click();
            onClose();
            return true;
        }
        if (!hit(mx, my, listX(), listY(), CONTENT_W, listHeight)) return false;
        int scrollY = Math.round(scroll.value());
        for (int rowIndex = 0; rowIndex < rows.size(); rowIndex++) {
            Row row = rows.get(rowIndex);
            if (!hit(mx, my, listX(), listY() + row.y() - scrollY, CONTENT_W, row.h())) continue;
            int by = listY() + row.y() - scrollY + row.btnY(), bx = listX() + COL_NAME;
            if (row.foldable()) {
                int w = btnW(expandLabel(row));
                if (hit(mx, my, bx, by, w, btnH())) {
                    ui.feedback.press(("exp." + rowIndex).hashCode(), bx, by, w, btnH(), true, now());
                    ShopUiSounds.click();
                    if (!expanded.remove(row.group().key())) expanded.add(row.group().key());
                    relayoutRow(rowIndex); return true;
                }
                bx += w + BTN_GAP;
            }
            if (row.boxable() && hit(mx, my, bx, by, btnW(boxLabel()), btnH())) {
                ui.feedback.press(("box." + rowIndex).hashCode(), bx, by, btnW(boxLabel()), btnH(), true, now());
                ShopUiSounds.click(); showBoxes(row.group()); return true;
            }
            if (isTextReport()) {
                String text = report.names().get(row.group().name()).getString();
                String detail = report.details().get(row.group().name()).getString();
                Minecraft.getInstance().keyboardHandler.setClipboard(detail.isEmpty() ? text : text + "\n" + detail);
                notice = tr(isStellarStuck() ? "copied_stellar_stuck" : "copied_craft"); noticeUntil = now() + 2500L;
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
    /** 按繪製後的換行字形命中，避免把整個診斷列都當成複製按鈕。 */
    private Style reportLinkAt(double mx, double my) {
        if (!isStellarStuck()) return null;
        int targetY = top + 10 + ui.height(getTitle(), PANEL_W - 66) + 4;
        if (hit(mx, my, left + 12, targetY, CONTENT_W, ui.height(targetLine(), CONTENT_W))) {
            return linkInLines(font.split(targetLine(), CONTENT_W), mx - left - 12, my - targetY);
        }
        if (!hit(mx, my, listX(), listY(), CONTENT_W, listHeight)) return null;
        int x = listX() + COL_NAME;
        if (mx < x || mx >= x + COL_NAME_W) return null;
        for (Row row : rows) {
            int y = listY() + row.y() - Math.round(scroll.value()) + 4;
            if (my < y || my >= y + row.h()) continue;
            List<FormattedCharSequence> lines = font.split(report.names().get(row.group().name()), COL_NAME_W);
            Style style = linkInLines(lines, mx - x, my - y);
            if (style != null) return style;
            return linkInLines(row.detailLines(), mx - x, my - y - row.gridH() - 3);
        }
        return null;
    }

    private Style linkInLines(List<FormattedCharSequence> lines, double x, double y) {
        if (y < 0) return null;
        int index = (int) (y / font.lineHeight);
        if (index >= lines.size() || x >= font.width(lines.get(index))) return null;
        Style style = font.getSplitter().componentStyleAtWidth(lines.get(index), (int) x);
        return style != null && (style.getClickEvent() != null || style.getHoverEvent() != null) ? style : null;
    }
    /** 把整組坐標在世界裡畫成方框（沿用結構高亮），然後關閉界面讓玩家看到世界。 */
    private void showBoxes(Group group) {
        if (!hasWorldCoordinates()) return;
        var mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;
        int color = report.categories().get(group.category()).color();
        List<BlockPos> positions = group.positions();
        int shown = Math.min(positions.size(), BOX_MAX);
        List<ShanhaiStructureHighlightPacket.Marker> markers = new ArrayList<>(shown);
        for (int i = 0; i < shown; i++) markers.add(new ShanhaiStructureHighlightPacket.Marker(positions.get(i), color));
        ShanhaiStructureHighlightClient.highlightAsync(mc.level.dimension(),
                System.currentTimeMillis() + BOX_SECONDS * 1000L, Collections.unmodifiableList(markers));
        mc.player.displayClientMessage(shown < positions.size()
                ? tr("boxes_capped", shown, positions.size(), BOX_SECONDS)
                : tr("boxes_shown", shown, BOX_SECONDS), true);
        onClose();
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
