package com.dishanhai.gt_shanhai.client.gui.shop;

import com.dishanhai.gt_shanhai.client.gui.scaled.GuiRenderUtil;
import com.dishanhai.gt_shanhai.client.gui.scaled.ScaledScreen;
import com.dishanhai.gt_shanhai.client.shop.ClientShopUiSettings;
import com.dishanhai.gt_shanhai.config.DShanhaiConfig;
import com.dishanhai.gt_shanhai.network.ShanhaiNetwork;
import com.dishanhai.gt_shanhai.network.ShopSettingsPacket;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;

/**
 * 商店设置（山海署名，客户端，自建）。仅编辑权玩家可见入口（见 {@link ShopScreen} 顶栏「商店设置」按钮）。
 *
 * <p>运行期可调的商店行为：「奖励抽取次数上限」「SDA 打包阈值」「AE 模式禁止注入」
 * 「将SDA直接注入磁盘仓室」（对应 {@link ShopSettingsPacket}/
 * config「shop.rewardRollCap」「shop.sdaPackThreshold」）；显示值直接读本地 {@link DShanhaiConfig}
 * （单机/联机同一 JVM 场景下与服务端权威值一致，仿 {@link CurrencyAtmScreen} 直接读
 * {@code CurrencyRateConfig} 那套既有做法），确认后发包让服务端写回并落盘（服务端才是权威，见
 * {@link ShopSettingsPacket}）。卡片大小和获取预览模式是客户端显示偏好，只写
 * {@link ClientShopUiSettings}，不进服务端包。</p>
 *
 * <p>标题和底栏按钮固定。中间选项区超出视口时可滚轮或拖右侧滚动条，输入框跟着内容走，
 * 滚出视口的输入框隐藏，避免点到底栏时还命中它们。</p>
 */
public class ShopSettingsScreen extends ScaledScreen {

    private static final int GOLD = -22016;
    private static final int GOLD_DARK = -7710208;
    private static final int PANEL_BG = -267382768;
    private static final int PANEL_INNER = -266724838;
    private static final int GRAY = -5592406;
    private static final int WHITE = -1;
    private static final int BTN_BG = -14935012;
    private static final int BTN_HOVER = -12303292;
    private static final int SCROLL_TRACK = -13684945;

    private static final int TARGET_W = 340;
    private static final int TARGET_H = 254;
    private static final int HEADER_H = 18;
    private static final int FOOTER_H = 26;
    private static final int SCROLLBAR_W = 4;

    /** 选项区内容坐标（相对视口顶，未滚动）。输入框和按钮都按这套排，滚动只改视觉 Y。 */
    private static final int ROW_ROLL_LABEL = 2;
    private static final int ROW_ROLL_BOX = 14;
    private static final int ROW_SDA_LABEL = 34;
    private static final int ROW_SDA_BOX = 46;
    private static final int ROW_WARN = 64;
    private static final int ROW_CARD_LABEL = 78;
    private static final int ROW_CARD_BOX = 90;
    private static final int ROW_PREVIEW = 112;
    private static final int ROW_PREVIEW_HINT = 130;
    private static final int ROW_AE = 144;
    private static final int ROW_DISK = 164;
    private static final int ROW_DISK_HINT = 182;
    private static final int CONTENT_H = 196;

    private final ShopScreen parent;
    private String rollCap;
    private String sdaThreshold;
    private String cardWidth;
    private String cardHeight;
    private boolean aeDeliverDisabled;
    private boolean sdaDirectDiskHatchInject;
    private boolean previewBar;
    private EditBox rollCapBox;
    private EditBox sdaThresholdBox;
    private EditBox cardWidthBox;
    private EditBox cardHeightBox;
    private int left, top, panelWidth, panelHeight;
    private int settingsScroll;
    private boolean scrollDrag;

    public ShopSettingsScreen(ShopScreen parent) {
        super(Component.literal("商店设置"));
        this.parent = parent;
        this.targetWidth = TARGET_W;
        this.targetHeight = TARGET_H;
        this.useOffset = false;
        this.minScale = 0.1f;
        this.maxScale = Float.MAX_VALUE;
        this.rollCap = Long.toString(DShanhaiConfig.COMMON.shopRewardRollCap.get());
        this.sdaThreshold = Long.toString(DShanhaiConfig.COMMON.shopSdaPackThreshold.get());
        this.cardWidth = Integer.toString(ClientShopUiSettings.cardWidth());
        this.cardHeight = Integer.toString(ClientShopUiSettings.cardHeight());
        this.aeDeliverDisabled = DShanhaiConfig.COMMON.shopAeDeliverDisabled.get();
        this.sdaDirectDiskHatchInject = DShanhaiConfig.COMMON.shopSdaDirectDiskHatchInject.get();
        this.previewBar = ClientShopUiSettings.rewardPreviewBar();
    }

    @Override
    protected void initScaled() {
        left = Math.max(6, (vWidth - TARGET_W) / 2);
        top = Math.max(8, (vHeight - TARGET_H) / 2);
        panelWidth = Math.min(TARGET_W, vWidth - 12);
        panelHeight = Math.min(TARGET_H, vHeight - 16);
        settingsScroll = clampScroll(settingsScroll);

        int fieldW = fieldWidth();
        rollCapBox = new EditBox(this.font, left + 12, viewTop() + ROW_ROLL_BOX, fieldW, 14, Component.literal("奖励抽取次数上限"));
        rollCapBox.setValue(rollCap);
        rollCapBox.setMaxLength(19);
        rollCapBox.setBordered(true);
        rollCapBox.setTextColor(0xFFFFFF);
        rollCapBox.setFilter(s -> s.isEmpty() || s.matches("\\d+"));
        rollCapBox.setResponder(s -> rollCap = s);
        addRenderableWidget(rollCapBox);

        sdaThresholdBox = new EditBox(this.font, left + 12, viewTop() + ROW_SDA_BOX, fieldW, 14, Component.literal("SDA 打包阈值"));
        sdaThresholdBox.setValue(sdaThreshold);
        sdaThresholdBox.setMaxLength(19);
        sdaThresholdBox.setBordered(true);
        sdaThresholdBox.setTextColor(0xFFFFFF);
        sdaThresholdBox.setFilter(s -> s.isEmpty() || s.matches("\\d+"));
        sdaThresholdBox.setResponder(s -> sdaThreshold = s);
        addRenderableWidget(sdaThresholdBox);

        int boxW = Math.max(20, (fieldW - 6) / 2);
        cardWidthBox = new EditBox(this.font, left + 12, viewTop() + ROW_CARD_BOX, boxW, 14, Component.literal("商品卡片宽"));
        cardWidthBox.setValue(cardWidth);
        cardWidthBox.setMaxLength(3);
        cardWidthBox.setBordered(true);
        cardWidthBox.setTextColor(0xFFFFFF);
        cardWidthBox.setFilter(s -> s.isEmpty() || s.matches("\\d+"));
        cardWidthBox.setResponder(s -> cardWidth = s);
        addRenderableWidget(cardWidthBox);

        cardHeightBox = new EditBox(this.font, left + 18 + boxW, viewTop() + ROW_CARD_BOX, boxW, 14, Component.literal("商品卡片高"));
        cardHeightBox.setValue(cardHeight);
        cardHeightBox.setMaxLength(3);
        cardHeightBox.setBordered(true);
        cardHeightBox.setTextColor(0xFFFFFF);
        cardHeightBox.setFilter(s -> s.isEmpty() || s.matches("\\d+"));
        cardHeightBox.setResponder(s -> cardHeight = s);
        addRenderableWidget(cardHeightBox);
        layoutInputs();
    }

    @Override
    protected void renderScaledBackground(GuiGraphics g, int mx, int my, float pt) {
        settingsScroll = clampScroll(settingsScroll);
        layoutInputs();

        g.fill(left, top, left + panelWidth, top + panelHeight, GOLD_DARK);
        g.fill(left + 1, top + 1, left + panelWidth - 1, top + panelHeight - 1, GOLD);
        g.fill(left + 2, top + 2, left + panelWidth - 2, top + panelHeight - 2, PANEL_BG);
        g.fill(left + 5, top + 18, left + panelWidth - 5, top + panelHeight - 5, PANEL_INNER);

        g.drawString(this.font, "§6商店设置 §7(仅编辑权可见)", left + 10, top + 5, GOLD, true);
        drawBtn(g, left + 12, top + panelHeight - 22, 60, 14, "§c取消", mx, my);
        drawBtn(g, left + panelWidth - 12 - 70, top + panelHeight - 22, 70, 14, "§a确认保存", mx, my);

        enableScaledScissor(g, left + 6, viewTop(), left + panelWidth - 6, viewBottom());
    }

    @Override
    protected void renderScaledForeground(GuiGraphics g, int mx, int my, float pt) {
        // 输入框渲染会自己开关 scissor，背景里开的裁剪到这里已经失效，文字和按钮要重新裁。
        enableScaledScissor(g, left + 6, viewTop(), left + panelWidth - 6, viewBottom());
        int x = left + 12;
        int textW = fieldWidth();
        drawContentLine(g, "§7奖励抽取次数上限（自选/随机/全部/FTBQ 单次购买最多独立随机次数）：", x, ROW_ROLL_LABEL, textW);
        drawContentLine(g, "§7SDA 打包阈值（非 AE 模式下，购买总量≥此值打包成超级磁盘阵列而非塞背包）：", x, ROW_SDA_LABEL, textW);
        drawContentLine(g, "§c调太大可能让服务端主线程卡死/崩溃，自己权衡", x, ROW_WARN, textW);
        drawContentLine(g, "§7商品卡片大小（客户端显示偏好；默认 70×36，网格默认每行 10 格）：", x, ROW_CARD_LABEL, textW);

        int previewY = visualY(ROW_PREVIEW);
        if (rowVisible(previewY, 16)) {
            drawBtn(g, x, previewY, fieldWidth(), 16,
                    previewBar ? "§a获取预览模式: 条形（名称列表）" : "§a获取预览模式: 方格（图标格）",
                    mx, my);
        }
        drawContentLine(g, "§7条形下：奖励表右侧为概率，获得和花费右侧为数量", x, ROW_PREVIEW_HINT, textW);

        int aeY = visualY(ROW_AE);
        if (rowVisible(aeY, 16)) {
            drawBtn(g, x, aeY, fieldWidth(), 16,
                    aeDeliverDisabled ? "§aAE 禁止注入: 开（购买物品正常给到背包/SDA）"
                            : "§8AE 禁止注入: 关（原行为，能注入就注入 AE）",
                    mx, my);
        }
        int diskY = visualY(ROW_DISK);
        if (rowVisible(diskY, 16)) {
            drawBtn(g, x, diskY, fieldWidth(), 16,
                    sdaDirectDiskHatchInject ? "§a将SDA直接注入磁盘仓室: 开"
                            : "§8将SDA直接注入磁盘仓室: 关",
                    mx, my);
        }
        drawContentLine(g, "§7仅挂载同网 FTBQ AE提交器/商店终端可见的有内容 SDA；空 SDA 不挂载", x, ROW_DISK_HINT, textW);

        g.disableScissor();
        drawScrollbar(g, mx, my);
    }

    @Override
    protected boolean universalMouseClicked(double mx, double my, int btn) {
        if (GuiRenderUtil.isHovering(mx, my, left + 12, top + panelHeight - 22, 60, 14)) {
            Minecraft.getInstance().setScreen(parent);
            return true;
        }
        if (GuiRenderUtil.isHovering(mx, my, left + panelWidth - 12 - 70, top + panelHeight - 22, 70, 14)) {
            submit();
            return true;
        }
        if (scrollbarClicked(mx, my)) return true;
        if (my >= viewTop() && my < viewBottom()) {
            int contentMy = (int) my + settingsScroll - viewTop();
            int x = left + 12;
            int w = fieldWidth();
            if (hitContent(mx, contentMy, x, ROW_PREVIEW, w, 16)) {
                previewBar = !previewBar;
                return true;
            }
            if (hitContent(mx, contentMy, x, ROW_AE, w, 16)) {
                aeDeliverDisabled = !aeDeliverDisabled;
                return true;
            }
            if (hitContent(mx, contentMy, x, ROW_DISK, w, 16)) {
                sdaDirectDiskHatchInject = !sdaDirectDiskHatchInject;
                return true;
            }
        }
        return super.universalMouseClicked(mx, my, btn);
    }

    @Override
    protected boolean universalMouseDragged(double mx, double my, int btn, double dx, double dy) {
        if (scrollDrag) {
            updateScrollFromDrag(my);
            layoutInputs();
            return true;
        }
        return super.universalMouseDragged(mx, my, btn, dx, dy);
    }

    @Override
    protected boolean universalMouseReleased(double mx, double my, int btn) {
        scrollDrag = false;
        return super.universalMouseReleased(mx, my, btn);
    }

    @Override
    protected boolean universalMouseScrolled(double mx, double my, double d) {
        if (scrollMax() > 0 && GuiRenderUtil.isHovering(mx, my, left, top, panelWidth, panelHeight)) {
            int delta = (int) d;
            if (delta == 0) delta = d > 0 ? 1 : -1;
            settingsScroll = clampScroll(settingsScroll - delta * 18);
            layoutInputs();
            return true;
        }
        return super.universalMouseScrolled(mx, my, d);
    }

    private void submit() {
        long roll;
        try { roll = rollCap == null || rollCap.isEmpty() ? 1L : Long.parseLong(rollCap); }
        catch (NumberFormatException e) { roll = 1L; }
        long sda;
        try { sda = sdaThreshold == null || sdaThreshold.isEmpty() ? 1L : Long.parseLong(sdaThreshold); }
        catch (NumberFormatException e) { sda = 1L; }
        int width;
        try { width = cardWidth == null || cardWidth.isEmpty() ? ClientShopUiSettings.DEFAULT_CARD_WIDTH : Integer.parseInt(cardWidth); }
        catch (NumberFormatException e) { width = ClientShopUiSettings.DEFAULT_CARD_WIDTH; }
        int height;
        try { height = cardHeight == null || cardHeight.isEmpty() ? ClientShopUiSettings.DEFAULT_CARD_HEIGHT : Integer.parseInt(cardHeight); }
        catch (NumberFormatException e) { height = ClientShopUiSettings.DEFAULT_CARD_HEIGHT; }
        ClientShopUiSettings.setCardSize(width, height);
        ClientShopUiSettings.setRewardPreviewBar(previewBar);
        ShanhaiNetwork.CHANNEL.sendToServer(new ShopSettingsPacket(Math.max(1L, roll), Math.max(1L, sda),
                aeDeliverDisabled, sdaDirectDiskHatchInject));
        Minecraft.getInstance().setScreen(parent);
    }

    private int viewTop() { return top + HEADER_H; }

    private int viewBottom() { return top + panelHeight - FOOTER_H; }

    private int viewH() { return Math.max(1, viewBottom() - viewTop()); }

    private int fieldWidth() { return Math.max(40, panelWidth - 28); }

    private int scrollMax() { return Math.max(0, CONTENT_H - viewH()); }

    private int clampScroll(int value) { return Math.max(0, Math.min(scrollMax(), value)); }

    private int visualY(int contentY) { return viewTop() + contentY - settingsScroll; }

    private boolean rowVisible(int visualTop, int h) {
        return visualTop + h > viewTop() && visualTop < viewBottom();
    }

    private static boolean hitContent(double mx, int contentMy, int x, int contentY, int w, int h) {
        return GuiRenderUtil.isHovering(mx, contentMy, x, contentY, w, h);
    }

    private void layoutInputs() {
        int x = left + 12;
        int w = fieldWidth();
        int boxW = Math.max(20, (w - 6) / 2);
        place(rollCapBox, x, ROW_ROLL_BOX, w);
        place(sdaThresholdBox, x, ROW_SDA_BOX, w);
        place(cardWidthBox, x, ROW_CARD_BOX, boxW);
        place(cardHeightBox, x + boxW + 6, ROW_CARD_BOX, boxW);
    }

    /** 输入框用视觉坐标接收点击。滚出中间视口就隐藏，底栏和标题不会误触。 */
    private void place(EditBox box, int x, int contentY, int w) {
        if (box == null) return;
        int y = visualY(contentY);
        box.setX(x);
        box.setY(y);
        box.setWidth(w);
        // 整框都在视口内才显示。露一半时输入框会清掉外层 scissor，画出标题和底栏。
        box.visible = y >= viewTop() && y + box.getHeight() <= viewBottom();
    }

    private void drawContentLine(GuiGraphics g, String text, int x, int contentY, int maxW) {
        int y = visualY(contentY);
        if (!rowVisible(y, 10)) return;
        g.drawString(this.font, GuiRenderUtil.trimText(this.font, text, maxW), x, y, WHITE, true);
    }

    private void drawScrollbar(GuiGraphics g, int mx, int my) {
        int trackX = left + panelWidth - 10;
        int trackY = viewTop();
        int trackH = viewH();
        g.fill(trackX, trackY, trackX + SCROLLBAR_W, trackY + trackH, SCROLL_TRACK);
        int max = scrollMax();
        if (max <= 0) return;
        int contentH = trackH + max;
        int thumbH = Math.max(10, trackH * trackH / Math.max(1, contentH));
        int thumbY = trackY + (trackH - thumbH) * settingsScroll / Math.max(1, max);
        boolean hv = scrollDrag || GuiRenderUtil.isHovering(mx, my, trackX, thumbY, SCROLLBAR_W, thumbH);
        g.fill(trackX, thumbY, trackX + SCROLLBAR_W, thumbY + thumbH, hv ? GOLD : GOLD_DARK);
    }

    private boolean scrollbarClicked(double mx, double my) {
        int trackX = left + panelWidth - 10;
        if (scrollMax() <= 0 || mx < trackX || mx > trackX + SCROLLBAR_W || my < viewTop() || my > viewBottom()) return false;
        scrollDrag = true;
        updateScrollFromDrag(my);
        layoutInputs();
        return true;
    }

    private void updateScrollFromDrag(double my) {
        int max = scrollMax();
        if (max <= 0) { settingsScroll = 0; return; }
        int trackH = viewH();
        int contentH = trackH + max;
        int thumbH = Math.max(10, trackH * trackH / Math.max(1, contentH));
        double usable = Math.max(1, trackH - thumbH);
        double rel = (my - viewTop() - thumbH / 2.0) / usable;
        settingsScroll = (int) Math.round(Math.max(0.0, Math.min(1.0, rel)) * max);
    }

    private void enableScaledScissor(GuiGraphics g, int x, int y, int x2, int y2) {
        g.enableScissor(
                (int) (x * guiScale) + offsetX, (int) (y * guiScale) + offsetY,
                (int) (x2 * guiScale) + offsetX, (int) (y2 * guiScale) + offsetY);
    }

    private void drawBtn(GuiGraphics g, int x, int y, int w, int h, String label, int mx, int my) {
        boolean hv = GuiRenderUtil.isHovering(mx, my, x, y, w, h);
        g.fill(x, y, x + w, y + h, GOLD_DARK);
        g.fill(x + 1, y + 1, x + w - 1, y + h - 1, hv ? BTN_HOVER : BTN_BG);
        g.drawCenteredString(this.font, GuiRenderUtil.trimText(this.font, label, w - 6), x + w / 2, y + (h - 8) / 2, WHITE);
    }

    @Override
    public boolean isPauseScreen() { return false; }
}
