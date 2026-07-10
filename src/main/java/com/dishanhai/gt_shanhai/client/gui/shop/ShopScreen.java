package com.dishanhai.gt_shanhai.client.gui.shop;

import com.dishanhai.gt_shanhai.client.gui.scaled.AdvancedSearchUtil;
import com.dishanhai.gt_shanhai.client.gui.scaled.GuiRenderUtil;
import com.dishanhai.gt_shanhai.client.gui.scaled.ScaledScreen;
import com.dishanhai.gt_shanhai.client.shop.ClientWalletAccount;
import com.dishanhai.gt_shanhai.common.shop.ShopConfig;
import com.dishanhai.gt_shanhai.common.shop.ShopEntry;
import com.dishanhai.gt_shanhai.common.shop.ShopPurchase;
import com.dishanhai.gt_shanhai.common.item.WalletItem;
import com.dishanhai.gt_shanhai.network.ShanhaiNetwork;
import com.dishanhai.gt_shanhai.network.ShopActionPacket;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 山海商店界面（ScaledScreen 版，仿 kineticaddons「钱包商店」观感）。
 *
 * <p>布局引擎完全对齐 kineticaddons {@code CurrencyWalletShopScreen}：逻辑画布
 * {@link #TARGET_W}×{@link #TARGET_H}，但面板本身<b>动态铺满窗口</b>
 * （{@code panelWidth = max(700, vWidth-8)}），所有坐标相对 {@link #left}/{@link #top}
 * 计算，商品格随宽度横向排满（最多 10 列）。不再用固定小画布 + 居中黑边，
 * 以此消除顶栏空白、网格靠左、标题偏位等对齐问题。</p>
 *
 * <p>顶栏：买/卖页签（左）+ 居中标题 + 充值全部/关闭（右）。搜索框仿 KE 放在
 * 右侧详情列正上方。左侧深色网格商品；右侧详情面板（品名/单价/数量滑块/预计/确认）。</p>
 *
 * <p>纯客户端界面：余额读手持钱包 NBT（客户端有副本），一切交易发
 * {@link ShopActionPacket} 给服务端结算，服务端改动通过 Forge 自动同步回手持物品。</p>
 */
public class ShopScreen extends ScaledScreen {

    // 逻辑画布（仿 KE 900×430；面板实际按窗口动态铺满，此值仅决定缩放基准）
    private static final int TARGET_W = 900;
    private static final int TARGET_H = 430;

    // ===== 布局常量（对齐 KE）=====
    private static final int TOP_BAR_H = 18;       // 顶栏按钮高
    private static final int DETAIL_W = 158;       // 右侧详情列宽（KE = 158）
    private static final int CELL_W = 70;          // 商品格宽（KE）
    private static final int CELL_H = 36;           // 商品格高（KE）
    private static final int GRID_GAP = 1;          // 格间距（KE = 1）
    private static final int GRID_MAX_COLS = 10;    // 最大列数（KE）
    private static final int SEARCH_W = 150;        // 搜索框宽（KE）
    private static final int TAB_H = 14;            // 分类页签高

    private static final int BUY_TAB_W = 58;
    private static final int SELL_TAB_W = 58;
    private static final int RECHARGE_W = 64;
    private static final int CLOSE_W = 60;

    // 配色（取自 kineticaddons CurrencyWalletShopScreen）
    private static final int GOLD = -22016;
    private static final int GOLD_DARK = -7710208;
    private static final int PANEL_BG = -267382768;
    private static final int PANEL_INNER = -266724838;
    private static final int BOX_BG = -300674028;
    private static final int ROW_BG = -300476649;
    private static final int ROW_HOVER = -299555547;
    private static final int SELECT_BG = -299421158;
    private static final int GREEN = -11141291;
    private static final int GREEN_DARK = -13661393;
    private static final int RED = -43691;
    private static final int RED_DARK = -7655633;
    private static final int DEEP_RED = -8775913;
    private static final int CYAN = -11141121;
    private static final int GRAY = -5592406;
    private static final int WHITE = -1;
    private static final int NUMBER_BAR_BORDER = -15967888;
    private static final int NUMBER_BAR_BG = -14935012;
    private static final int NUMBER_BAR_TEXT = -1;
    private static final int BUTTON_BG = -14935012;
    private static final int BUTTON_HOVER = -12303292;

    private enum Mode { BUY, SELL }

    private Mode mode = Mode.BUY;
    private String category;
    private ShopEntry selected;
    private long amount = 1L;      // 购买/出售次数，支持 Long.MAX
    private EditBox searchBox;
    private EditBox amountBox;      // AE 风格数量输入框
    // AE 模式：交付优先注入绑定的 AE 网络。静态=跨界面实例保留（重开钱包不重置），仅本机会话级
    private static boolean aeMode = false;
    private int scroll = 0; // 网格滚动像素
    private final boolean canEdit; // 服务端下发的编辑权（OP 或白名单）：决定新增/编辑按钮显隐

    // ===== 动态面板尺寸（每次 initScaled 重算）=====
    private int left, top, panelWidth, panelHeight;

    // 计算态
    private List<String> categories = new ArrayList<>();
    private List<ShopEntry> visibleEntries = new ArrayList<>();

    public ShopScreen() {
        this(false);
    }

    public ShopScreen(boolean canEdit) {
        super(Component.literal("山海商店"));
        this.canEdit = canEdit;
        this.targetWidth = TARGET_W;
        this.targetHeight = TARGET_H;
        this.useOffset = false; // KE 风格：不居中黑边，面板动态铺满
        this.scaleMultiplier = 1.0f;
        // minScale 必须 <1（KE 默认 0.1）。若卡成 1.0，高 GUI Scale/小窗口下逻辑宽 width<900 时
        // guiScale 无法缩小 → vWidth=width<900 却 panelWidth≥700，面板右缘超出 vWidth 造成溢出。
        this.minScale = 0.1f;
        // maxScale 不封顶（KE 默认 Float.MAX_VALUE）。封顶会让高分宽屏下 guiScale 被压小、
        // vWidth 虚高、panelWidth 撑出屏幕右侧。两个边界都须放开，才满足 vWidth×guiScale≡width。
        this.maxScale = Float.MAX_VALUE;
    }

    // ============ 动态布局坐标（全部相对 left/top、panelWidth/panelHeight）============

    private int titleX() { return left + panelWidth / 2; }

    private int balanceY() { return top + 30; }
    private int balanceHeight() { return 18; }

    /** 分类页签行 Y。 */
    private int tabsY() { return balanceY() + balanceHeight() + 4; }

    /** 网格/详情内容区顶部 Y。 */
    private int contentTop() { return tabsY() + TAB_H + 6; }

    /** 内容区可见高度（撑到面板底部留边）。 */
    private int contentHeight() { return Math.max(60, (top + panelHeight - 8) - contentTop()); }

    private int listLeft() { return left + 5; }

    /** 网格宽度：铺满到详情列左侧，封顶 10 列。 */
    private int listWidth() {
        int maxGridW = GRID_MAX_COLS * (CELL_W + GRID_GAP) + 2;
        int available = (left + panelWidth - 2 - DETAIL_W - 6) - listLeft();
        return Math.min(maxGridW, Math.max(CELL_W + 2, available));
    }

    private int gridColumns() {
        int usable = Math.max(1, listWidth() - 2);
        return Math.max(1, Math.min(GRID_MAX_COLS, (usable + GRID_GAP) / (CELL_W + GRID_GAP)));
    }

    private int detailX() { return listLeft() + listWidth() + 6; }
    private int detailContentX() { return detailX() + 8; }

    // 搜索框仿 KE 放右侧详情列正上方（顶栏不再塞它）
    private int searchBoxX() { return detailX() + 4; }
    private int searchBoxY() { return tabsY() + 1; }

    private int buyTabX() { return left + 14; }
    private int sellTabX() { return left + 14 + BUY_TAB_W + 2; }
    // 「货币中心」按钮：出售页签右侧，始终可见（打开 ATM 子页）
    private static final int CURRENCY_BTN_W = 64;
    private int currencyBtnX() { return sellTabX() + SELL_TAB_W + 6; }
    // 「新增商品」按钮：货币中心右侧，仅编辑权玩家可见
    private static final int ADD_BTN_W = 64;
    private int addBtnX() { return currencyBtnX() + CURRENCY_BTN_W + 4; }

    /** 供 CurrencyAtmScreen 读取当前 AE 模式（同包访问）。 */
    static boolean isAeMode() { return aeMode; }
    private int closeBtnX() { return left + panelWidth - 8 - CLOSE_W; }
    private int rechargeBtnX() { return closeBtnX() - 4 - RECHARGE_W; }
    // 「AE模式」切换：充值按钮左侧
    private static final int AE_BTN_W = 58;
    private int aeBtnX() { return rechargeBtnX() - 4 - AE_BTN_W; }

    // ===== 网格坐标单一真源（渲染/点击/tooltip 三处共用，杜绝错位）=====
    private static final int ROW_STRIDE = CELL_H + GRID_GAP;
    private static final int COL_STRIDE = CELL_W + GRID_GAP;

    private int cellX(int col) { return listLeft() + col * COL_STRIDE; }
    private int cellY(int row) { return contentTop() + row * ROW_STRIDE - scroll; }

    /** 最大滚动像素（内容总高 - 可见高，下限 0）。 */
    private int maxGridScroll() {
        int cols = gridColumns();
        int rows = (visibleEntries.size() + cols - 1) / cols;
        return Math.max(0, rows * ROW_STRIDE - contentHeight());
    }

    /** 在缩放坐标系下开启 scissor：enableScissor 吃物理像素，须手动换算（KE 做法）。 */
    private void enableGridScissor(GuiGraphics g, int x, int y, int x2, int y2) {
        g.enableScissor(
                (int) (x * guiScale) + offsetX, (int) (y * guiScale) + offsetY,
                (int) (x2 * guiScale) + offsetX, (int) (y2 * guiScale) + offsetY);
    }

    @Override
    protected void initScaled() {
        // 动态面板铺满可用逻辑区（KE 原式）。maxScale=Float.MAX_VALUE 保证 vWidth×guiScale≡width，
        // 面板 panelWidth=vWidth-8 铺满恰好不溢出——这是 KE 的缩放不变式，勿再封顶 maxScale。
        left = 4;
        top = 8;
        panelWidth = Math.max(700, vWidth - 8);
        panelHeight = Math.max(360, vHeight - 16);

        categories = ShopConfig.getCategories();
        if (category == null || !categories.contains(category)) {
            category = categories.isEmpty() ? ShopEntry.DEFAULT_CATEGORY : categories.get(0);
        }

        // 搜索框（右侧详情列正上方）
        String prev = searchBox != null ? searchBox.getValue() : "";
        searchBox = new EditBox(this.font, searchBoxX(), searchBoxY(), SEARCH_W, 12, Component.literal("搜索"));
        searchBox.setValue(prev);
        searchBox.setResponder(s -> { scroll = 0; recomputeVisible(); });
        searchBox.setBordered(true);
        searchBox.setHint(Component.literal("搜索名称/ID"));
        searchBox.setTextColor(0xFFFFFF);
        addRenderableWidget(searchBox);

        // 数量输入框（AE 风格，详情区数量位；仅选中商品时可见）
        amountBox = new EditBox(this.font, detailX() + 8, contentTop() + 66, DETAIL_W - 16, 12, Component.literal("数量"));
        amountBox.setValue(Long.toString(Math.max(1L, amount)));
        amountBox.setBordered(true);
        amountBox.setTextColor(0xFFFFFF);
        amountBox.setMaxLength(19); // Long.MAX 有 19 位
        amountBox.setFilter(s -> s.isEmpty() || s.matches("\\d+")); // 只允许数字
        amountBox.setResponder(s -> {
            long v;
            try {
                v = s.isEmpty() ? 1L : Long.parseLong(s);
            } catch (NumberFormatException ex) {
                v = Long.MAX_VALUE; // 超过 Long.MAX 位数 → 封顶
            }
            amount = Math.max(1L, v);
        });
        amountBox.setVisible(selected != null);
        addRenderableWidget(amountBox);

        recomputeVisible();
    }

    /** 同步数量到文本框（步进按钮改 amount 后调用，避免触发 responder 递归）。 */
    private void syncAmountBox() {
        if (amountBox != null) {
            amountBox.setValue(Long.toString(amount));
        }
    }

    private void recomputeVisible() {
        String q = searchBox != null ? searchBox.getValue() : "";
        List<ShopEntry> src = (q == null || q.isBlank())
                ? ShopConfig.getEntriesOf(category)
                : ShopConfig.getEntries();
        visibleEntries = new ArrayList<>();
        for (ShopEntry e : src) {
            if (!e.isValid()) continue;
            if (q != null && !q.isBlank()) {
                String hay = e.goodsDisplayName() + " " + e.getGoodsId();
                if (!AdvancedSearchUtil.match(hay, q)) continue;
            }
            visibleEntries.add(e);
        }
    }

    // ============ 渲染 ============

    @Override
    protected void renderScaledBackground(GuiGraphics g, int mx, int my, float pt) {
        // 数量框仅在选中商品时可见（本方法在 super.render 画控件之前执行，故此处同步安全）
        if (amountBox != null) amountBox.setVisible(selected != null);
        // 整体面板（KE 风格 4 层嵌套：金边→金→暗背景→内层）
        g.fill(left, top, left + panelWidth, top + panelHeight, GOLD_DARK);
        g.fill(left + 1, top + 1, left + panelWidth - 1, top + panelHeight - 1, GOLD);
        g.fill(left + 2, top + 2, left + panelWidth - 2, top + panelHeight - 2, PANEL_BG);
        g.fill(left + 6, top + TOP_BAR_H + 6, left + panelWidth - 6, top + panelHeight - 6, PANEL_INNER);

        // 顶部买/卖页签
        drawTab(g, buyTabX(), top + 6, BUY_TAB_W, TOP_BAR_H, "购买", mode == Mode.BUY, mx, my);
        drawTab(g, sellTabX(), top + 6, SELL_TAB_W, TOP_BAR_H, "出售", mode == Mode.SELL, mx, my);
        // 货币中心（始终可见）
        drawButton(g, currencyBtnX(), top + 6, CURRENCY_BTN_W, TOP_BAR_H, "§6货币中心", mx, my);
        // 新增商品（仅编辑权玩家）
        if (canEdit) {
            drawButton(g, addBtnX(), top + 6, ADD_BTN_W, TOP_BAR_H, "§a新增商品", mx, my);
        }

        // 顶栏右侧：AE模式 → 充值全部 → 关闭
        drawButton(g, aeBtnX(), top + 6, AE_BTN_W, TOP_BAR_H, aeMode ? "§aAE模式:开" : "§8AE模式:关", mx, my);
        drawButton(g, rechargeBtnX(), top + 6, RECHARGE_W, TOP_BAR_H, "§b充值全部", mx, my);
        drawButton(g, closeBtnX(), top + 6, CLOSE_W, TOP_BAR_H, "§c关闭", mx, my);

        // 标题（居中于整个面板，KE 做法）
        g.drawCenteredString(this.font, "§6钱包商店", titleX(), top + 9, GOLD);

        // 余额行（KE 货币栏：图标 + 紧凑数字 横排）
        int by = balanceY();
        int balW = panelWidth - 28;
        renderBox(g, left + 14, by, balW, balanceHeight(), GOLD_DARK, BOX_BG);
        drawCurrencyBar(g, left + 14 + 6, by, balW - 12, mx, my);

        // 分类页签行
        int ty = tabsY();
        int tx = left + 14;
        int tabsRight = detailX() - 8; // 不越过搜索框列
        for (String cat : categories) {
            int tw = Math.max(30, this.font.width(cat) + 10);
            if (tx + tw > tabsRight) break; // 简单截断
            drawTab(g, tx, ty, tw, TAB_H, cat, cat.equals(category), mx, my);
            tx += tw + 3;
        }

        // 左侧网格背景
        int gx = listLeft(), gy = contentTop(), gw = listWidth(), gh = contentHeight();
        g.fill(gx, gy, gx + gw, gy + gh, BOX_BG);
        drawGrid(g, mx, my);

        // 右侧详情面板
        drawDetail(g, mx, my);
    }

    private void drawGrid(GuiGraphics g, int mx, int my) {
        int cols = gridColumns();
        int gx = listLeft(), gy = contentTop(), gw = listWidth(), gh = contentHeight();

        // 像素级平滑滚动：内容整体按 -scroll 平移，用 scissor 裁到网格矩形内。
        enableGridScissor(g, gx, gy, gx + gw, gy + gh);
        for (int idx = 0; idx < visibleEntries.size(); idx++) {
            int col = idx % cols;
            int row = idx / cols;
            int cy = cellY(row);
            if (cy + CELL_H <= gy || cy >= gy + gh) continue; // 只画与网格相交的 cell
            drawCell(g, visibleEntries.get(idx), cellX(col), cy, mx, my);
        }
        g.disableScissor();

        // 滚动条（按像素比例）
        int maxScroll = maxGridScroll();
        if (maxScroll > 0) {
            int rows = (visibleEntries.size() + cols - 1) / cols;
            int contentH = rows * ROW_STRIDE;
            int barX = gx + Math.min(gw - 4, cols * COL_STRIDE - GRID_GAP) + 1;
            int barH = Math.max(10, gh * gh / contentH);
            int barY = gy + (gh - barH) * scroll / maxScroll;
            g.fill(barX, gy, barX + 3, gy + gh, NUMBER_BAR_BG);
            g.fill(barX, barY, barX + 3, barY + barH, GOLD);
        }
    }

    /** KE 风格横向格：左棋盘物品槽 + 右侧名称/状态 + 底部价格数字条 + 货币小图标。 */
    private void drawCell(GuiGraphics g, ShopEntry entry, int cx, int cy, int mx, int my) {
        boolean sel = selected == entry;
        boolean hover = GuiRenderUtil.isHovering(mx, my, cx, cy, CELL_W, CELL_H);
        int border = sel ? GOLD : GREEN_DARK;
        int fill = sel ? SELECT_BG : (hover ? ROW_HOVER : ROW_BG);
        renderBox(g, cx, cy, CELL_W, CELL_H, border, fill);
        if (sel) renderSelectionOutline(g, cx, cy, CELL_W, CELL_H);

        // 棋盘物品槽（20x20）
        int slotX = cx + 3, slotY = cy + 3;
        renderItemCheckerSlot(g, slotX, slotY);
        ItemStack stack = entry.makeGoodsStack();
        g.renderItem(stack, slotX + 2, slotY + 2);
        g.renderItemDecorations(this.font, stack, slotX + 2, slotY + 2);

        // 商品名（右上，截断）
        int textX = slotX + 23;
        int rightX = cx + CELL_W - 3;
        String name = GuiRenderUtil.trimText(this.font, entry.goodsDisplayName(), rightX - textX);
        g.drawString(this.font, name, textX, cy + 4, WHITE, true);

        // 底部价格数字条 + 货币图标
        String price = String.valueOf(entry.getPrice());
        int numX = cx + 2;
        int numY = cy + CELL_H - 13;
        int numW = Math.min(48, Math.max(22, this.font.width(price) + 8));
        renderNumberBar(g, numX, numY, numW);
        g.drawString(this.font, price, numX + 4, numY + 2, NUMBER_BAR_TEXT, false);
        // 货币小图标（价格条右侧，0.75x）
        renderCurrencyItem(g, entry.makePriceStack(), numX + numW + 2, numY - 2);
    }

    // ============ KE 风格原子绘制单元 ============

    /** 带边框的填充盒（外 1px border + 内 fill）。 */
    private static void renderBox(GuiGraphics g, int x, int y, int w, int h, int border, int fill) {
        g.fill(x, y, x + w, y + h, border);
        g.fill(x + 1, y + 1, x + w - 1, y + h - 1, fill);
    }

    /** 20x20 淡色棋盘物品槽（仿 KE renderItemCheckerSlot）。 */
    private static void renderItemCheckerSlot(GuiGraphics g, int x, int y) {
        g.fill(x - 1, y - 1, x + 21, y + 21, -7697782);
        g.fill(x, y, x + 20, y + 20, -1644826);
        for (int yy = 0; yy < 20; yy += 5) {
            for (int xx = 0; xx < 20; xx += 5) {
                int color = (((xx / 5) + (yy / 5)) & 1) == 0 ? -723724 : -2302756;
                g.fill(x + xx, y + yy, Math.min(x + xx + 5, x + 20), Math.min(y + yy + 5, y + 20), color);
            }
        }
        // 立体边
        g.fill(x, y, x + 20, y + 1, -1);
        g.fill(x, y + 19, x + 20, y + 20, -6645094);
        g.fill(x, y, x + 1, y + 20, -1);
        g.fill(x + 19, y, x + 20, y + 20, -6645094);
    }

    /** 价格数字条底框。 */
    private static void renderNumberBar(GuiGraphics g, int x, int y, int w) {
        g.fill(x, y, x + w, y + 11, NUMBER_BAR_BORDER);
        g.fill(x + 1, y + 1, x + w - 1, y + 10, NUMBER_BAR_BG);
    }

    /** 货币小图标（0.75x 缩放）。 */
    private void renderCurrencyItem(GuiGraphics g, ItemStack stack, int x, int y) {
        g.pose().pushPose();
        g.pose().translate(x, y, 0);
        g.pose().scale(0.75f, 0.75f, 1f);
        g.renderItem(stack, 0, 0);
        g.pose().popPose();
    }

    /** KE 风格按钮：外框 + 内填充（hover 变色）+ 居中文字。 */
    private void renderButton(GuiGraphics g, int x, int y, int w, int h, boolean hover, String text, int border, int textColor) {
        g.fill(x, y, x + w, y + h, border);
        g.fill(x + 1, y + 1, x + w - 1, y + h - 1, hover ? BUTTON_HOVER : BUTTON_BG);
        g.drawCenteredString(this.font, text, x + w / 2, y + (h - 8) / 2, textColor);
    }

    /** 选中格的青色四边描边。 */
    private static void renderSelectionOutline(GuiGraphics g, int x, int y, int w, int h) {
        g.fill(x - 1, y - 1, x + w + 1, y, CYAN);
        g.fill(x - 1, y + h, x + w + 1, y + h + 1, CYAN);
        g.fill(x - 1, y, x, y + h, CYAN);
        g.fill(x + w, y, x + w + 1, y + h, CYAN);
    }

    /** KE 风格紧凑数字：1K+/1M+/1B+/1T+/1Qa+/1Qi+。 */
    private static String formatCompact(long value) {
        long v = Math.max(0L, value);
        if (v >= 1_000_000_000_000_000_000L) return (v / 1_000_000_000_000_000_000L) + "Qi+";
        if (v >= 1_000_000_000_000_000L) return (v / 1_000_000_000_000_000L) + "Qa+";
        if (v >= 1_000_000_000_000L) return (v / 1_000_000_000_000L) + "T+";
        if (v >= 1_000_000_000L) return (v / 1_000_000_000L) + "B+";
        if (v >= 1_000_000L) return (v / 1_000_000L) + "M+";
        if (v >= 1_000L) return (v / 1_000L) + "K+";
        return String.valueOf(v);
    }

    /** 步进按钮上的数字（1000 → "1k" 省空间）。 */
    private static String compactStep(long step) {
        return step >= 1000 ? (step / 1000) + "k" : String.valueOf(step);
    }

    /** BigInteger 紧凑显示：能装进 long 走 formatCompact；否则用科学计数近似。 */
    private static String formatBig(java.math.BigInteger v) {
        if (v.signum() < 0) return "0";
        if (v.bitLength() < 63) return formatCompact(v.longValue());
        String s = v.toString();
        return s.charAt(0) + "." + s.substring(1, Math.min(3, s.length())) + "e" + (s.length() - 1);
    }

    private void drawDetail(GuiGraphics g, int mx, int my) {
        int dx = detailX();
        int dy = contentTop();
        int dh = contentHeight();
        // 面板背景（KE 风格描边盒）
        renderBox(g, dx, dy, DETAIL_W, dh, GOLD_DARK, BOX_BG);

        if (selected == null) {
            g.drawCenteredString(this.font, "§7← 请选择商品", dx + DETAIL_W / 2, dy + 18, GRAY);
            return;
        }

        int cx = dx + 8;
        // 棋盘物品槽 + 图标
        renderItemCheckerSlot(g, cx, dy + 10);
        g.renderItem(selected.makeGoodsStack(), cx + 2, dy + 12);
        g.renderItemDecorations(this.font, selected.makeGoodsStack(), cx + 2, dy + 12);
        // 名称
        int textX = cx + 24;
        g.drawString(this.font, GuiRenderUtil.trimText(this.font, selected.goodsDisplayName(), dx + DETAIL_W - 6 - textX),
                textX, dy + 10, WHITE, true);
        g.drawString(this.font, "§7每份 §f" + selected.getGoodsCount() + " §7个", textX, dy + 24, GRAY, true);

        // 单价（青）
        g.drawString(this.font, "§7单价: §e" + selected.getPrice() + " "
                + ShopPurchase.coinName(selected.getCurrencyId()), cx, dy + 42, CYAN, true);

        // 数量标签 + AE 风格步进按钮（文本框 amountBox 由 initScaled 定位、super.render 绘制）
        g.drawString(this.font, (mode == Mode.BUY ? "§7购买次数（可输入）:" : "§7出售次数（可输入）:"), cx, dy + 56, WHITE, true);
        // 两排步进：+1 +10 +100 +1000 / -1 -10 -100 -1000
        int stepY1 = dy + 82, stepY2 = dy + 96;
        long[] steps = {1, 10, 100, 1000};
        int bw = (DETAIL_W - 16 - 9) / 4; // 4 按钮 + 3 间隙
        for (int i = 0; i < 4; i++) {
            int bx = cx + i * (bw + 3);
            drawButton(g, bx, stepY1, bw, 12, "§a+" + compactStep(steps[i]), mx, my);
            drawButton(g, bx, stepY2, bw, 12, "§c-" + compactStep(steps[i]), mx, my);
        }

        // 预计（BigInteger 防溢出，紧凑显示）
        java.math.BigInteger amt = java.math.BigInteger.valueOf(amount);
        java.math.BigInteger coin = java.math.BigInteger.valueOf(selected.getPrice()).multiply(amt);
        java.math.BigInteger total = java.math.BigInteger.valueOf(selected.getGoodsCount()).multiply(amt);
        int py = dy + 114;
        g.drawString(this.font, "§7交易次数: §f" + formatBig(amt), cx, py, WHITE, true);
        if (mode == Mode.BUY) {
            g.drawString(this.font, "§7预计消耗: §e" + formatBig(coin) + " §7" + ShopPurchase.coinName(selected.getCurrencyId()), cx, py + 12, CYAN, true);
            g.drawString(this.font, "§7预计获得: §e" + formatBig(total) + " §7个", cx, py + 24, GREEN, true);
        } else {
            g.drawString(this.font, "§7预计消耗: §e" + formatBig(total) + " §7个商品", cx, py + 12, CYAN, true);
            g.drawString(this.font, "§7预计获得: §e" + formatBig(coin) + " §7" + ShopPurchase.coinName(selected.getCurrencyId()), cx, py + 24, GREEN, true);
            int held = ShopPurchase.countItem(Minecraft.getInstance().player, selected.getGoodsItem());
            g.drawString(this.font, "§7背包持有: " + (held > 0 ? "§a" : "§c") + held, cx, py + 36, held > 0 ? GREEN : DEEP_RED, true);
        }

        // 确认按钮（KE 风格 border+fill，颜色随可交易性）
        int btnY = dy + dh - 24;
        boolean canTrade = mode == Mode.BUY
                ? ClientWalletAccount.getCurrency(selected.getCurrencyId()).compareTo(java.math.BigInteger.valueOf(selected.getPrice())) >= 0
                : ShopPurchase.countItem(Minecraft.getInstance().player, selected.getGoodsItem()) >= selected.getGoodsCount();
        boolean btnHover = hit(mx, my, cx, btnY, DETAIL_W - 16, 20);
        renderButton(g, cx, btnY, DETAIL_W - 16, 20, btnHover,
                mode == Mode.BUY ? "§a确认购买" : "§e确认出售",
                canTrade ? GREEN_DARK : DEEP_RED, canTrade ? (mode == Mode.BUY ? GREEN : GOLD) : DEEP_RED);

        // 编辑/删除按钮（编辑权玩家）
        if (canEdit) {
            boolean delHover = hit(mx, my, cx, btnY - 22, DETAIL_W - 16, 18);
            renderButton(g, cx, btnY - 22, DETAIL_W - 16, 18, delHover, "§c删除此商品", RED_DARK, RED);
            boolean editHover = hit(mx, my, cx, btnY - 44, DETAIL_W - 16, 18);
            renderButton(g, cx, btnY - 44, DETAIL_W - 16, 18, editHover, "§b编辑条目", GOLD_DARK, CYAN);
        }
    }

    // ============ 交互 ============

    @Override
    protected boolean universalMouseClicked(double mx, double my, int btn) {
        // 顶部买/卖页签
        if (hit(mx, my, buyTabX(), top + 6, BUY_TAB_W, TOP_BAR_H)) { mode = Mode.BUY; selected = null; return true; }
        if (hit(mx, my, sellTabX(), top + 6, SELL_TAB_W, TOP_BAR_H)) { mode = Mode.SELL; selected = null; return true; }

        // 关闭
        if (hit(mx, my, closeBtnX(), top + 6, CLOSE_W, TOP_BAR_H)) {
            onClose();
            return true;
        }
        // AE 模式切换
        if (hit(mx, my, aeBtnX(), top + 6, AE_BTN_W, TOP_BAR_H)) {
            aeMode = !aeMode;
            return true;
        }
        // 充值全部
        if (hit(mx, my, rechargeBtnX(), top + 6, RECHARGE_W, TOP_BAR_H)) {
            send(ShopActionPacket.Action.DEPOSIT, null, 1L);
            return true;
        }
        // 货币中心（打开 ATM 子页）
        if (hit(mx, my, currencyBtnX(), top + 6, CURRENCY_BTN_W, TOP_BAR_H)) {
            Minecraft.getInstance().setScreen(new CurrencyAtmScreen(this));
            return true;
        }
        // 新增商品（仅编辑权）
        if (canEdit && hit(mx, my, addBtnX(), top + 6, ADD_BTN_W, TOP_BAR_H)) {
            ShopEntryEditor.openNew(this);
            return true;
        }

        // 分类页签
        int ty = tabsY();
        int tx = left + 14;
        int tabsRight = detailX() - 8;
        for (String cat : categories) {
            int tw = Math.max(30, this.font.width(cat) + 10);
            if (tx + tw > tabsRight) break;
            if (hit(mx, my, tx, ty, tw, TAB_H)) {
                category = cat; selected = null; scroll = 0; recomputeVisible();
                return true;
            }
            tx += tw + 3;
        }

        // 网格格子（像素滚动：用统一 cellX/cellY，且只命中网格矩形内的部分）
        int cols = gridColumns();
        int gy = contentTop(), gh = contentHeight();
        for (int idx = 0; idx < visibleEntries.size(); idx++) {
            int col = idx % cols, row = idx / cols;
            int cy = cellY(row);
            if (cy + CELL_H <= gy || cy >= gy + gh) continue; // 网格外不响应
            if (my < gy || my > gy + gh) continue;            // 点击落在网格外亦不响应
            if (hit(mx, my, cellX(col), cy, CELL_W, CELL_H)) {
                selected = visibleEntries.get(idx);
                return true;
            }
        }

        // 详情面板按钮
        if (selected != null) {
            int dx = detailX();
            int cx = dx + 8;
            int dy = contentTop();
            int dh = contentHeight();
            int btnW = DETAIL_W - 16;
            int btnY = dy + dh - 24;
            // 确认购买/出售
            if (hit(mx, my, cx, btnY, btnW, 20)) {
                send(mode == Mode.BUY ? ShopActionPacket.Action.BUY : ShopActionPacket.Action.SELL, selected, amount);
                return true;
            }
            // 删除（编辑权）
            if (canEdit && hit(mx, my, cx, btnY - 22, btnW, 18)) {
                send(ShopActionPacket.Action.DELETE, selected, 1L);
                selected = null;
                return true;
            }
            // 编辑条目（编辑权）
            if (canEdit && hit(mx, my, cx, btnY - 44, btnW, 18)) {
                ShopEntryEditor.openEdit(this, selected);
                return true;
            }
            // AE 风格步进按钮（两排 +1/+10/+100/+1000 与 -1/-10/-100/-1000）
            long[] steps = {1, 10, 100, 1000};
            int bw = (DETAIL_W - 16 - 9) / 4;
            for (int i = 0; i < 4; i++) {
                int bx = cx + i * (bw + 3);
                if (hit(mx, my, bx, dy + 82, bw, 12)) { amount = addClamp(amount, steps[i]); syncAmountBox(); return true; }
                if (hit(mx, my, bx, dy + 96, bw, 12)) { amount = addClamp(amount, -steps[i]); syncAmountBox(); return true; }
            }
        }

        return super.universalMouseClicked(mx, my, btn);
    }

    /** 数量加减夹取到 [1, Long.MAX]，防加法溢出。 */
    private static long addClamp(long a, long delta) {
        if (delta > 0 && a > Long.MAX_VALUE - delta) return Long.MAX_VALUE;
        return Math.max(1L, a + delta);
    }

    @Override
    protected boolean universalMouseScrolled(double mx, double my, double d) {
        int gx = listLeft(), gy = contentTop(), gh = contentHeight();
        if (GuiRenderUtil.isHovering(mx, my, gx, gy, listWidth(), gh)) {
            int maxScroll = maxGridScroll();
            scroll = Math.max(0, Math.min(maxScroll, scroll - (int) d * ROW_STRIDE));
            return true;
        }
        return super.universalMouseScrolled(mx, my, d);
    }

    // ============ 工具 ============

    private void send(ShopActionPacket.Action action, ShopEntry entry, long times) {
        ResourceLocation gid = entry != null ? entry.getGoodsId() : null;
        String cat = entry != null ? entry.getCategory() : "";
        // 仅购买带 aeMode（AE 模式优先注入网络）
        boolean ae = action == ShopActionPacket.Action.BUY && aeMode;
        ShanhaiNetwork.CHANNEL.sendToServer(new ShopActionPacket(action, gid, cat, times, ae));
    }

    private void drawTab(GuiGraphics g, int x, int y, int w, int h, String label, boolean active, int mx, int my) {
        boolean hover = GuiRenderUtil.isHovering(mx, my, x, y, w, h);
        int border = active ? GOLD : (hover ? GOLD_DARK : NUMBER_BAR_BORDER);
        renderBox(g, x, y, w, h, border, active ? ROW_BG : BOX_BG);
        int col = active ? GOLD : (hover ? WHITE : GRAY);
        g.drawCenteredString(this.font, label, x + w / 2, y + (h - 8) / 2, col);
    }

    private void drawButton(GuiGraphics g, int x, int y, int w, int h, String label, int mx, int my) {
        boolean hover = GuiRenderUtil.isHovering(mx, my, x, y, w, h);
        renderBox(g, x, y, w, h, hover ? GOLD : GOLD_DARK, hover ? BUTTON_HOVER : BUTTON_BG);
        g.drawCenteredString(this.font, label, x + w / 2, y + (h - 8) / 2, WHITE);
    }

    private boolean hit(double mx, double my, int x, int y, int w, int h) {
        return GuiRenderUtil.isHovering(mx, my, x, y, w, h);
    }

    /** KE 货币栏：「余额」标签 + 每种货币一格（物品图标 + 紧凑数字）。悬停显示货币全名。 */
    private void drawCurrencyBar(GuiGraphics g, int x, int y, int width, int mx, int my) {
        // 「余额」标签
        String label = "余额:";
        g.drawString(this.font, label, x, y + 4, CYAN, true);
        int cellStart = x + this.font.width(label) + 8;

        Map<ResourceLocation, java.math.BigInteger> all = ClientWalletAccount.getAll();
        if (all.isEmpty()) {
            g.drawString(this.font, "§80（点右侧「充值全部」存入背包货币）", cellStart, y + 4, GRAY, true);
            return;
        }
        // 按余额降序排，每格固定宽
        java.util.List<Map.Entry<ResourceLocation, java.math.BigInteger>> list = new java.util.ArrayList<>(all.entrySet());
        list.sort((a, b) -> b.getValue().compareTo(a.getValue()));

        int cellW = 66;
        int maxCells = Math.max(1, (width - (cellStart - x)) / cellW);
        for (int i = 0; i < list.size() && i < maxCells; i++) {
            Map.Entry<ResourceLocation, java.math.BigInteger> e = list.get(i);
            int cx = cellStart + i * cellW;
            ItemStack coin = currencyStack(e.getKey());
            // 图标（居中于 16px）
            g.renderItem(coin, cx, y - 1);
            // 紧凑数字
            g.drawString(this.font, formatBig(e.getValue()), cx + 18, y + 4, GOLD, true);
        }
        if (list.size() > maxCells) {
            g.drawString(this.font, "§7+" + (list.size() - maxCells), cellStart + maxCells * cellW - 4, y + 4, GRAY, true);
        }
    }

    /** 由货币 ID 构造展示用 ItemStack。 */
    private static ItemStack currencyStack(ResourceLocation id) {
        var item = net.minecraftforge.registries.ForgeRegistries.ITEMS.getValue(id);
        return item != null ? new ItemStack(item) : ItemStack.EMPTY;
    }

    /** 悬停货币格返回其全名（供 tooltip）。坐标须与 drawCurrencyBar 完全一致。 */
    private ResourceLocation hoveredCurrency(int smx, int smy) {
        int x = left + 14 + 6;
        int by = balanceY();
        String label = "余额:";
        int cellStart = x + this.font.width(label) + 8;
        Map<ResourceLocation, java.math.BigInteger> all = ClientWalletAccount.getAll();
        java.util.List<Map.Entry<ResourceLocation, java.math.BigInteger>> list = new java.util.ArrayList<>(all.entrySet());
        list.sort((a, b) -> b.getValue().compareTo(a.getValue()));
        int cellW = 66;
        for (int i = 0; i < list.size(); i++) {
            int cx = cellStart + i * cellW;
            if (GuiRenderUtil.isHovering(smx, smy, cx, by, cellW - 4, balanceHeight())) {
                return list.get(i).getKey();
            }
        }
        return null;
    }

    /** 客户端手持钱包（主手优先）。 */
    private ItemStack wallet() {
        var p = Minecraft.getInstance().player;
        if (p == null) return ItemStack.EMPTY;
        if (p.getMainHandItem().getItem() instanceof WalletItem) return p.getMainHandItem();
        if (p.getOffhandItem().getItem() instanceof WalletItem) return p.getOffhandItem();
        return ItemStack.EMPTY;
    }

    private boolean isOp() {
        var p = Minecraft.getInstance().player;
        return p != null && p.hasPermissions(2);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    protected void renderTooltips(GuiGraphics g, int smx, int smy, int mx, int my) {
        // 悬停货币栏：显示货币全名 + 精确余额
        ResourceLocation cur = hoveredCurrency(smx, smy);
        if (cur != null) {
            java.math.BigInteger bal = ClientWalletAccount.getCurrency(cur);
            List<Component> lines = new ArrayList<>();
            lines.add(Component.literal("§6" + ShopPurchase.coinName(cur)));
            lines.add(Component.literal("§7余额: §f" + bal.toString()));
            g.renderComponentTooltip(this.font, lines, mx, my);
            return;
        }
        // 悬停商品格显示 tooltip（用统一 cellX/cellY，仅网格矩形内响应）
        int cols = gridColumns();
        int gy = contentTop(), gh = contentHeight();
        if (smy < gy || smy > gy + gh) return; // 网格外不弹 tooltip
        for (int idx = 0; idx < visibleEntries.size(); idx++) {
            int col = idx % cols, row = idx / cols;
            int cy = cellY(row);
            if (cy + CELL_H <= gy || cy >= gy + gh) continue;
            if (GuiRenderUtil.isHovering(smx, smy, cellX(col), cy, CELL_W, CELL_H)) {
                ShopEntry e = visibleEntries.get(idx);
                List<Component> lines = new ArrayList<>();
                lines.add(Component.literal("§f" + e.goodsDisplayName()));
                lines.add(Component.literal("§7每份 " + e.getGoodsCount() + " 个"));
                lines.add(Component.literal("§7单价 §e" + e.getPrice() + " " + ShopPurchase.coinName(e.getCurrencyId())));
                g.renderComponentTooltip(this.font, lines, mx, my);
                break;
            }
        }
    }
}
