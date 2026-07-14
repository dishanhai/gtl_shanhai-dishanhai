package com.dishanhai.gt_shanhai.client.gui.shop;

import com.dishanhai.gt_shanhai.client.gui.scaled.GuiRenderUtil;
import com.dishanhai.gt_shanhai.client.gui.scaled.ScaledScreen;

import dev.ftb.mods.ftbquests.client.ClientQuestFile;
import dev.ftb.mods.ftbquests.quest.Chapter;
import dev.ftb.mods.ftbquests.quest.Quest;
import dev.ftb.mods.ftbquests.quest.QuestObjectBase;

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
 * FTBQ 任务选择器（山海署名，客户端，自建）。列出 {@link ClientQuestFile} 当前已同步的全部任务
 * （章节名 + 任务标题），供商店「前置任务」选任务用。选中即回填 ID（十六进制字符串）并关闭；
 * 服务端购买校验直接读同一个任务的实时完成状态，本屏只负责「挑一个」，不做任何本地复制。
 * 整体结构照抄 {@link FtbqTableSelectScreen}，数据源换成全部任务。
 */
public class FtbqQuestSelectScreen extends ScaledScreen {

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

    private final Screen parent;
    private final Consumer<String> onPicked;
    private final List<Quest> all = new ArrayList<>();
    private List<Quest> filtered = new ArrayList<>();
    private EditBox searchBox;
    private int left, top, panelWidth, panelHeight;
    private int listY, listBottom;
    private int scroll;

    public FtbqQuestSelectScreen(Screen parent, Consumer<String> onPicked) {
        super(Component.literal("选择 FTBQ 任务"));
        this.parent = parent;
        this.onPicked = onPicked;
        this.targetWidth = TARGET_W;
        this.targetHeight = TARGET_H;
        this.useOffset = false;
        this.minScale = 0.1f;
        this.maxScale = Float.MAX_VALUE;
        ClientQuestFile file = ClientQuestFile.INSTANCE;
        if (file != null) file.forAllQuests(all::add);
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
        List<Quest> out = new ArrayList<>();
        for (Quest quest : all) {
            if (q.isEmpty() || questName(quest).toLowerCase(Locale.ROOT).contains(q)
                    || QuestObjectBase.getCodeString(quest).toLowerCase(Locale.ROOT).contains(q)) {
                out.add(quest);
            }
        }
        filtered = out;
        scroll = 0;
    }

    private static String questName(Quest quest) {
        String title = quest.getRawTitle();
        return title.isEmpty() ? ("#" + QuestObjectBase.getCodeString(quest)) : title;
    }

    private static String chapterName(Quest quest) {
        Chapter chapter = quest.getChapter();
        return chapter == null ? "" : chapter.getRawTitle();
    }

    private int visibleRows() {
        return Math.max(1, (listBottom - listY) / ROW_H);
    }

    @Override
    protected void renderScaledBackground(GuiGraphics g, int mx, int my, float pt) {
        g.fill(left, top, left + panelWidth, top + panelHeight, GOLD_DARK);
        g.fill(left + 1, top + 1, left + panelWidth - 1, top + panelHeight - 1, GOLD);
        g.fill(left + 2, top + 2, left + panelWidth - 2, top + panelHeight - 2, PANEL_BG);
        g.fill(left + 4, top + 36, left + panelWidth - 4, top + panelHeight - 4, PANEL_INNER);

        g.drawString(this.font, "§6选择 FTBQ 任务 §7(" + filtered.size() + ")", left + 10, top + 5, GOLD, true);
        drawBtn(g, left + panelWidth - 10 - 40, top + 3, 40, 14, "§c取消", mx, my);

        if (all.isEmpty()) {
            g.drawString(this.font, "§8未检测到 FTBQ 任务（未装 FTBQ / 尚未加载任务）", left + 10, listY + 4, GRAY, true);
            return;
        }

        int visible = visibleRows();
        int maxScroll = Math.max(0, filtered.size() - visible);
        if (scroll > maxScroll) scroll = maxScroll;
        for (int i = 0; i < visible; i++) {
            int idx = scroll + i;
            if (idx >= filtered.size()) break;
            Quest quest = filtered.get(idx);
            int ry = listY + i * ROW_H;
            boolean hover = GuiRenderUtil.isHovering(mx, my, left + 6, ry, panelWidth - 12, ROW_H - 1);
            if (hover) g.fill(left + 6, ry, left + panelWidth - 6, ry + ROW_H - 1, ROW_BG);
            String name = GuiRenderUtil.trimText(this.font, questName(quest), panelWidth - 24);
            g.drawString(this.font, "§f" + name, left + 10, ry + 1, WHITE, true);
            String chapter = chapterName(quest);
            g.drawString(this.font, "§8" + (chapter.isEmpty() ? "(无章节)" : chapter), left + 10, ry + 10, GRAY, true);
        }
        if (filtered.size() > visible) {
            g.drawString(this.font, "§8" + (scroll + 1) + "-" + Math.min(scroll + visible, filtered.size()) + "/" + filtered.size(),
                    left + 10, listBottom + 2, GRAY, true);
        }
    }

    @Override
    protected boolean universalMouseClicked(double mx, double my, int btn) {
        if (GuiRenderUtil.isHovering(mx, my, left + panelWidth - 10 - 40, top + 3, 40, 14)) {
            Minecraft.getInstance().setScreen(parent);
            return true;
        }
        int visible = visibleRows();
        for (int i = 0; i < visible; i++) {
            int idx = scroll + i;
            if (idx >= filtered.size()) break;
            int ry = listY + i * ROW_H;
            if (GuiRenderUtil.isHovering(mx, my, left + 6, ry, panelWidth - 12, ROW_H - 1)) {
                Quest quest = filtered.get(idx);
                onPicked.accept(QuestObjectBase.getCodeString(quest));
                Minecraft.getInstance().setScreen(parent);
                return true;
            }
        }
        return super.universalMouseClicked(mx, my, btn);
    }

    @Override
    protected boolean universalMouseScrolled(double mx, double my, double delta) {
        if (GuiRenderUtil.isHovering(mx, my, left, listY, panelWidth, listBottom - listY)) {
            int visible = visibleRows();
            int maxScroll = Math.max(0, filtered.size() - visible);
            scroll = Math.max(0, Math.min(maxScroll, scroll - (int) delta));
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
