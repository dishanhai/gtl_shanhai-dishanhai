package com.dishanhai.gt_shanhai.client.gui.shop;

import com.dishanhai.gt_shanhai.client.gui.scaled.GuiRenderUtil;
import com.dishanhai.gt_shanhai.client.gui.scaled.ScaledScreen;
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
 * <p>运行期可调的商店行为：「奖励抽取次数上限」「SDA 打包阈值」两项（对应 {@link ShopSettingsPacket}/
 * config「shop.rewardRollCap」「shop.sdaPackThreshold」）；显示值直接读本地 {@link DShanhaiConfig}
 * （单机/联机同一 JVM 场景下与服务端权威值一致，仿 {@link CurrencyAtmScreen} 直接读
 * {@code CurrencyRateConfig} 那套既有做法），确认后发包让服务端写回并落盘（服务端才是权威，见
 * {@link ShopSettingsPacket}）。</p>
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

    private static final int TARGET_W = 340;
    private static final int TARGET_H = 190;

    private final ShopScreen parent;
    private String rollCap;
    private String sdaThreshold;
    private boolean aeDeliverDisabled;
    private EditBox rollCapBox;
    private EditBox sdaThresholdBox;
    private int left, top, panelWidth, panelHeight;

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
        this.aeDeliverDisabled = DShanhaiConfig.COMMON.shopAeDeliverDisabled.get();
    }

    @Override
    protected void initScaled() {
        left = Math.max(6, (vWidth - TARGET_W) / 2);
        top = Math.max(8, (vHeight - TARGET_H) / 2);
        panelWidth = Math.min(TARGET_W, vWidth - 12);
        panelHeight = Math.min(TARGET_H, vHeight - 16);

        rollCapBox = new EditBox(this.font, left + 12, top + 30, panelWidth - 24, 14, Component.literal("奖励抽取次数上限"));
        rollCapBox.setValue(rollCap);
        rollCapBox.setMaxLength(19);
        rollCapBox.setBordered(true);
        rollCapBox.setTextColor(0xFFFFFF);
        rollCapBox.setFilter(s -> s.isEmpty() || s.matches("\\d+"));
        rollCapBox.setResponder(s -> rollCap = s);
        addRenderableWidget(rollCapBox);

        sdaThresholdBox = new EditBox(this.font, left + 12, top + 68, panelWidth - 24, 14, Component.literal("SDA 打包阈值"));
        sdaThresholdBox.setValue(sdaThreshold);
        sdaThresholdBox.setMaxLength(19);
        sdaThresholdBox.setBordered(true);
        sdaThresholdBox.setTextColor(0xFFFFFF);
        sdaThresholdBox.setFilter(s -> s.isEmpty() || s.matches("\\d+"));
        sdaThresholdBox.setResponder(s -> sdaThreshold = s);
        addRenderableWidget(sdaThresholdBox);
    }

    @Override
    protected void renderScaledBackground(GuiGraphics g, int mx, int my, float pt) {
        g.fill(left, top, left + panelWidth, top + panelHeight, GOLD_DARK);
        g.fill(left + 1, top + 1, left + panelWidth - 1, top + panelHeight - 1, GOLD);
        g.fill(left + 2, top + 2, left + panelWidth - 2, top + panelHeight - 2, PANEL_BG);
        g.fill(left + 5, top + 18, left + panelWidth - 5, top + panelHeight - 5, PANEL_INNER);

        g.drawString(this.font, "§6商店设置 §7(仅编辑权可见)", left + 10, top + 5, GOLD, true);
        g.drawString(this.font, "§7奖励抽取次数上限（自选/随机/全部/FTBQ 单次购买最多独立随机次数）：",
                left + 12, top + 20, WHITE, true);
        g.drawString(this.font, "§7SDA 打包阈值（非 AE 模式下，购买总量≥此值打包成超级磁盘阵列而非塞背包）：",
                left + 12, top + 58, WHITE, true);
        g.drawString(this.font, "§c调太大可能让服务端主线程卡死/崩溃，自己权衡", left + 12, top + 86, GRAY, true);

        // AE 禁止注入：开启后 AE 模式只拉取材料付款/检索库存，购买/兑换得到的物品一律正常交付（进背包/SDA），不再注入 AE
        drawBtn(g, left + 12, top + 100, panelWidth - 24, 16,
                aeDeliverDisabled ? "§aAE 禁止注入: 开（购买物品正常给到背包/SDA）"
                        : "§8AE 禁止注入: 关（原行为，能注入就注入 AE）",
                mx, my);

        drawBtn(g, left + 12, top + panelHeight - 22, 60, 14, "§c取消", mx, my);
        drawBtn(g, left + panelWidth - 12 - 70, top + panelHeight - 22, 70, 14, "§a确认保存", mx, my);
    }

    @Override
    protected boolean universalMouseClicked(double mx, double my, int btn) {
        if (GuiRenderUtil.isHovering(mx, my, left + 12, top + 100, panelWidth - 24, 16)) {
            aeDeliverDisabled = !aeDeliverDisabled;
            return true;
        }
        if (GuiRenderUtil.isHovering(mx, my, left + 12, top + panelHeight - 22, 60, 14)) {
            Minecraft.getInstance().setScreen(parent);
            return true;
        }
        if (GuiRenderUtil.isHovering(mx, my, left + panelWidth - 12 - 70, top + panelHeight - 22, 70, 14)) {
            submit();
            return true;
        }
        return super.universalMouseClicked(mx, my, btn);
    }

    private void submit() {
        long roll;
        try { roll = rollCap == null || rollCap.isEmpty() ? 1L : Long.parseLong(rollCap); }
        catch (NumberFormatException e) { roll = 1L; }
        long sda;
        try { sda = sdaThreshold == null || sdaThreshold.isEmpty() ? 1L : Long.parseLong(sdaThreshold); }
        catch (NumberFormatException e) { sda = 1L; }
        ShanhaiNetwork.CHANNEL.sendToServer(new ShopSettingsPacket(Math.max(1L, roll), Math.max(1L, sda), aeDeliverDisabled));
        Minecraft.getInstance().setScreen(parent);
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
