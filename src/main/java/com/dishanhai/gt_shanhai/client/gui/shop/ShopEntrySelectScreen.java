package com.dishanhai.gt_shanhai.client.gui.shop;

import com.dishanhai.gt_shanhai.client.gui.scaled.GuiRenderUtil;
import com.dishanhai.gt_shanhai.client.gui.scaled.ScaledScreen;
import com.dishanhai.gt_shanhai.client.shop.ClientShopCatalog;
import com.dishanhai.gt_shanhai.common.shop.ShopCatalogManifest;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * 商店商品选择器（山海署名，客户端，自建）。列出目录清单里的全部可见商品（名称 + 分类路径），
 * 供任务详情页「绑定商店商品」挑一个；选中即回填 {@code stableId} 并关闭。
 *
 * <p>数据源是登录时就同步好的轻量 stub（{@link ClientShopCatalog#stubs()}），不需要商品实体
 * 到货，所以打开即全量可选、可搜。结构照抄 {@link FtbqQuestSelectScreen}，数据源换成商品。</p>
 */
public class ShopEntrySelectScreen extends ScaledScreen {

    private static final int GOLD = -22016;
    private static final int GOLD_DARK = -7710208;
    private static final int PANEL_BG = -267382768;
    private static final int PANEL_INNER = -266724838;
    private static final int GRAY = -5592406;
    private static final int WHITE = -1;
    private static final int ROW_BG = -300476649;
    private static final int BTN_BG = -14935012;
    private static final int BTN_HOVER = -12303292;

    private static final int TARGET_W = 360;
    private static final int TARGET_H = 320;
    private static final int ROW_H = 20;
    private static final int SCROLLBAR_W = 4;
    /** 滚轮一格滚几行。商品动辄上千条，一次一行根本翻不动（见反馈），真正快速定位还是靠拖把手/搜索。 */
    private static final int SCROLL_STEP_ROWS = 3;

    private final Screen parent;
    private final Consumer<String> onPicked;
    private final List<ShopCatalogManifest.Stub> all = new ArrayList<>();
    private List<ShopCatalogManifest.Stub> filtered = new ArrayList<>();
    private EditBox searchBox;
    private int left, top, panelWidth, panelHeight;
    private int listY, listBottom;
    private int scroll;
    private boolean draggingScroll;

    public ShopEntrySelectScreen(Screen parent, Consumer<String> onPicked) {
        super(Component.literal("选择商店商品"));
        this.parent = parent;
        this.onPicked = onPicked;
        this.targetWidth = TARGET_W;
        this.targetHeight = TARGET_H;
        this.useOffset = false;
        this.minScale = 0.1f;
        this.maxScale = Float.MAX_VALUE;
        for (ShopCatalogManifest.Stub stub : ClientShopCatalog.stubs()) {
            // 隐藏商品不给选：它本来就不该在任何面向玩家的入口露出
            if (stub.hidden() || stub.stableId().isEmpty()) continue;
            all.add(stub);
        }
    }

    @Override
    protected void initScaled() {
        left = Math.max(6, (vWidth - TARGET_W) / 2);
        top = Math.max(8, (vHeight - TARGET_H) / 2);
        panelWidth = Math.min(TARGET_W, vWidth - 12);
        panelHeight = Math.min(TARGET_H, vHeight - 16);

        searchBox = new EditBox(this.font, left + 10, top + 20, panelWidth - 20, 14, Component.literal("搜索"));
        searchBox.setMaxLength(64);
        searchBox.setBordered(true);
        searchBox.setTextColor(0xFFFFFF);
        searchBox.setResponder(s -> rebuildFilter());
        searchBox.setFocused(true);
        setFocused(searchBox);
        addRenderableWidget(searchBox);

        listY = top + 40;
        listBottom = top + panelHeight - 24;
        rebuildFilter();
    }

    private void rebuildFilter() {
        String q = searchBox != null ? searchBox.getValue().trim().toLowerCase(Locale.ROOT) : "";
        List<ShopCatalogManifest.Stub> out = new ArrayList<>();
        for (ShopCatalogManifest.Stub stub : all) {
            if (q.isEmpty() || matches(stub, q)) out.add(stub);
        }
        filtered = out;
        scroll = 0;
    }

    /** 名称、分类路径、商品 ID 任一命中即算匹配（跟商店搜索框一个口径）。 */
    private static boolean matches(ShopCatalogManifest.Stub stub, String lowerQuery) {
        StringBuilder haystack = new StringBuilder(stub.displayName()).append(' ').append(categoryPath(stub)).append(' ');
        for (String goodsId : stub.goodsIds()) haystack.append(goodsId).append(' ');
        return haystack.toString().toLowerCase(Locale.ROOT).contains(lowerQuery);
    }

    private static String entryName(ShopCatalogManifest.Stub stub) {
        return stub.displayName().isEmpty() ? "(未命名商品)" : stub.displayName();
    }

    private static String categoryPath(ShopCatalogManifest.Stub stub) {
        StringBuilder sb = new StringBuilder(stub.top());
        if (!stub.sub().isEmpty()) sb.append('/').append(stub.sub());
        if (!stub.sub2().isEmpty()) sb.append('/').append(stub.sub2());
        if (!stub.sub3().isEmpty()) sb.append('/').append(stub.sub3());
        return sb.toString();
    }

    private int visibleRows() {
        return Math.max(1, (listBottom - listY) / ROW_H);
    }

    /** 最大滚动行数（0 = 内容没溢出，不需要滚）。 */
    private int maxScroll() {
        return Math.max(0, filtered.size() - visibleRows());
    }

    private int scrollbarX() {
        return left + panelWidth - 6 - SCROLLBAR_W;
    }

    /** 行可点击/绘制的宽度：给右侧滚动条让出位置，文字不压到轨道上。 */
    private int rowWidth() {
        return panelWidth - 16 - SCROLLBAR_W;
    }

    @Override
    protected void renderScaledBackground(GuiGraphics g, int mx, int my, float pt) {
        g.fill(left, top, left + panelWidth, top + panelHeight, GOLD_DARK);
        g.fill(left + 1, top + 1, left + panelWidth - 1, top + panelHeight - 1, GOLD);
        g.fill(left + 2, top + 2, left + panelWidth - 2, top + panelHeight - 2, PANEL_BG);
        g.fill(left + 4, top + 36, left + panelWidth - 4, top + panelHeight - 4, PANEL_INNER);

        g.drawString(this.font, "§6选择商店商品 §7(" + filtered.size() + ")", left + 10, top + 5, GOLD, true);
        drawBtn(g, left + panelWidth - 10 - 40, top + 3, 40, 14, "§c取消", mx, my);

        if (all.isEmpty()) {
            g.drawString(this.font, "§8商店目录尚未同步（重进世界或先打开一次商店）", left + 10, listY + 4, GRAY, true);
            return;
        }

        int visible = visibleRows();
        int maxScroll = maxScroll();
        if (scroll > maxScroll) scroll = maxScroll;
        int rowW = rowWidth();
        for (int i = 0; i < visible; i++) {
            int idx = scroll + i;
            if (idx >= filtered.size()) break;
            ShopCatalogManifest.Stub stub = filtered.get(idx);
            int ry = listY + i * ROW_H;
            boolean hover = GuiRenderUtil.isHovering(mx, my, left + 6, ry, rowW, ROW_H - 1);
            if (hover) g.fill(left + 6, ry, left + 6 + rowW, ry + ROW_H - 1, ROW_BG);
            String name = GuiRenderUtil.trimText(this.font, entryName(stub), rowW - 8);
            g.drawString(this.font, "§f" + name, left + 10, ry + 1, WHITE, true);
            String path = GuiRenderUtil.trimText(this.font, categoryPath(stub), rowW - 8);
            g.drawString(this.font, "§8" + path, left + 10, ry + 10, GRAY, true);
        }
        drawScrollbar(g, mx, my, visible, maxScroll);
        if (filtered.size() > visible) {
            g.drawString(this.font, "§8" + (scroll + 1) + "-" + Math.min(scroll + visible, filtered.size()) + "/" + filtered.size(),
                    left + 10, listBottom + 2, GRAY, true);
        }
    }

    /**
     * 列表右侧滚动条：轨道常驻（没溢出时也留个底色占位，位置固定不跳动），
     * 溢出时叠一段按内容比例算高度的把手，可点可拖。写法对齐商店主界面的网格滚动条。
     */
    private void drawScrollbar(GuiGraphics g, int mx, int my, int visible, int maxScroll) {
        int barX = scrollbarX();
        int trackH = listBottom - listY;
        g.fill(barX, listY, barX + SCROLLBAR_W, listBottom, PANEL_BG);
        if (maxScroll <= 0) return;
        int barH = Math.max(10, trackH * visible / filtered.size());
        int barY = listY + (trackH - barH) * scroll / maxScroll;
        boolean hv = draggingScroll || GuiRenderUtil.isHovering(mx, my, barX, barY, SCROLLBAR_W, barH);
        g.fill(barX, barY, barX + SCROLLBAR_W, barY + barH, hv ? WHITE : GOLD);
    }

    /** 按拖拽点 Y 反算 scroll（把手中心跟随鼠标），无溢出时归零。 */
    private void updateScrollFromDrag(double my) {
        int maxScroll = maxScroll();
        if (maxScroll <= 0) {
            scroll = 0;
            return;
        }
        int trackH = listBottom - listY;
        int barH = Math.max(10, trackH * visibleRows() / filtered.size());
        int usable = Math.max(1, trackH - barH);
        double rel = (my - listY - barH / 2.0) / usable;
        scroll = (int) Math.round(Math.max(0.0, Math.min(1.0, rel)) * maxScroll);
    }

    @Override
    protected boolean universalMouseClicked(double mx, double my, int btn) {
        if (GuiRenderUtil.isHovering(mx, my, left + panelWidth - 10 - 40, top + 3, 40, 14)) {
            Minecraft.getInstance().setScreen(parent);
            return true;
        }
        // 轨道整条都可点（不用精确点在把手上），点哪跳哪，随即进入拖拽态
        if (GuiRenderUtil.isHovering(mx, my, scrollbarX(), listY, SCROLLBAR_W, listBottom - listY)) {
            draggingScroll = true;
            updateScrollFromDrag(my);
            return true;
        }
        int visible = visibleRows();
        int rowW = rowWidth();
        for (int i = 0; i < visible; i++) {
            int idx = scroll + i;
            if (idx >= filtered.size()) break;
            int ry = listY + i * ROW_H;
            if (GuiRenderUtil.isHovering(mx, my, left + 6, ry, rowW, ROW_H - 1)) {
                onPicked.accept(filtered.get(idx).stableId());
                Minecraft.getInstance().setScreen(parent);
                return true;
            }
        }
        return super.universalMouseClicked(mx, my, btn);
    }

    @Override
    protected boolean universalMouseDragged(double mx, double my, int btn, double dx, double dy) {
        if (draggingScroll) {
            updateScrollFromDrag(my);
            return true;
        }
        return super.universalMouseDragged(mx, my, btn, dx, dy);
    }

    @Override
    protected boolean universalMouseReleased(double mx, double my, int btn) {
        if (draggingScroll) {
            draggingScroll = false;
            return true;
        }
        return super.universalMouseReleased(mx, my, btn);
    }

    @Override
    protected boolean universalMouseScrolled(double mx, double my, double delta) {
        if (GuiRenderUtil.isHovering(mx, my, left, listY, panelWidth, listBottom - listY)) {
            scroll = Math.max(0, Math.min(maxScroll(), scroll - (int) delta * SCROLL_STEP_ROWS));
            return true;
        }
        return super.universalMouseScrolled(mx, my, delta);
    }

    private void drawBtn(GuiGraphics g, int x, int y, int w, int h, String label, int mx, int my) {
        boolean hv = GuiRenderUtil.isHovering(mx, my, x, y, w, h);
        g.fill(x, y, x + w, y + h, GOLD_DARK);
        g.fill(x + 1, y + 1, x + w - 1, y + h - 1, hv ? BTN_HOVER : BTN_BG);
        g.drawCenteredString(this.font, label, x + w / 2, y + (h - 8) / 2, WHITE);
    }

    @Override
    public boolean isPauseScreen() { return false; }
}
