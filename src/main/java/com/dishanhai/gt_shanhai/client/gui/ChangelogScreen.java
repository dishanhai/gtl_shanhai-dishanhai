package com.dishanhai.gt_shanhai.client.gui;

import com.dishanhai.gt_shanhai.common.config.ChangelogConfig;
import com.dishanhai.gt_shanhai.common.config.ChangelogConfig.ChangelogDocument;
import com.dishanhai.gt_shanhai.common.config.ChangelogConfig.LineKind;
import com.dishanhai.gt_shanhai.common.config.ChangelogConfig.MarkdownLine;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

/**
 * Markdown 更新公告全屏显示界面。
 */
public final class ChangelogScreen extends Screen {

    private static final int PANEL_BG = 0xF0100010;
    private static final int PANEL_BORDER_OUTER = 0x505000FF;
    private static final int PANEL_BORDER_INNER = 0x5028007F;
    private static final int TITLE_COLOR = 0xFFFFAA00;
    private static final int HEADING_COLOR = 0xFF55FFFF;
    private static final int BODY_COLOR = 0xFFDDDDDD;
    private static final int SCROLLBAR_TRACK = 0x40000000;
    private static final int SCROLLBAR_THUMB = 0xFF8080C0;
    private static final int PANEL_WIDTH = 520;
    private static final int PADDING = 18;
    private static final int LINE_HEIGHT = 12;
    private static final int TITLE_EXTRA_HEIGHT = 8;
    private static final int BUTTON_WIDTH = 128;
    private static final int BUTTON_HEIGHT = 20;
    private static final int BUTTON_GAP = 6;
    private static final int CONTENT_BUTTON_GAP = 10;
    private static final int SCROLLBAR_WIDTH = 4;

    private final Screen parent;
    private final ChangelogDocument document;
    private final List<RenderLine> lines = new ArrayList<>();
    private int panelLeft;
    private int panelTop;
    private int panelHeight;
    private int contentTop;
    private int contentHeight;
    private int totalContentHeight;
    private int scrollOffset;

    private ChangelogScreen(Screen parent, ChangelogDocument document) {
        super(Component.literal(document.title()));
        this.parent = parent;
        this.document = document;
    }

    public static void open(Screen parent) {
        ChangelogDocument latest = ChangelogConfig.getLatest();
        if (latest != null) {
            open(parent, latest);
        }
    }

    public static void open(Screen parent, ChangelogDocument document) {
        Minecraft.getInstance().setScreen(new ChangelogScreen(parent, document));
    }

    @Override
    protected void init() {
        buildLines();
        totalContentHeight = 0;
        for (RenderLine line : lines) {
            totalContentHeight += line.height();
        }

        int chromeHeight = PADDING * 2 + CONTENT_BUTTON_GAP + BUTTON_HEIGHT;
        int wanted = chromeHeight + totalContentHeight;
        int maxPanelHeight = Math.max(chromeHeight + LINE_HEIGHT * 4, this.height - 32);
        panelHeight = Math.min(wanted, maxPanelHeight);
        panelTop = (this.height - panelHeight) / 2;
        panelLeft = (this.width - PANEL_WIDTH) / 2;
        contentTop = panelTop + PADDING;
        contentHeight = panelHeight - chromeHeight;
        scrollOffset = Math.min(scrollOffset, maxScroll());

        int buttonCount = ChangelogConfig.getHistory().isEmpty() ? 2 : 3;
        int totalWidth = BUTTON_WIDTH * buttonCount + BUTTON_GAP * (buttonCount - 1);
        int buttonX = (this.width - totalWidth) / 2;
        int buttonY = panelTop + panelHeight - PADDING - BUTTON_HEIGHT;
        this.addRenderableWidget(Button.builder(Component.literal("确定"), button -> onClose())
                .bounds(buttonX, buttonY, BUTTON_WIDTH, BUTTON_HEIGHT).build());
        this.addRenderableWidget(Button.builder(Component.literal("不再提示"), button -> {
                    if (document == ChangelogConfig.getLatest()) {
                        ChangelogConfig.markSeen();
                    }
                    onClose();
                })
                .bounds(buttonX + BUTTON_WIDTH + BUTTON_GAP, buttonY, BUTTON_WIDTH, BUTTON_HEIGHT).build());
        if (buttonCount == 3) {
            this.addRenderableWidget(Button.builder(Component.literal("历史更新"),
                            button -> ChangelogHistoryScreen.open(this))
                    .bounds(buttonX + (BUTTON_WIDTH + BUTTON_GAP) * 2, buttonY,
                            BUTTON_WIDTH, BUTTON_HEIGHT).build());
        }
    }

    private void buildLines() {
        lines.clear();
        int textWidth = PANEL_WIDTH - PADDING * 2 - SCROLLBAR_WIDTH - 2;
        if (!document.title().isBlank()) {
            lines.add(new RenderLine(Component.literal(document.title()), TITLE_COLOR, true));
            lines.add(new RenderLine(Component.empty(), BODY_COLOR, false));
        }
        for (MarkdownLine sourceLine : document.lines()) {
            if (sourceLine.kind() == LineKind.BLANK) {
                lines.add(new RenderLine(Component.empty(), BODY_COLOR, false));
                continue;
            }
            Component component = sourceLine.asComponent();
            List<FormattedCharSequence> wrapped = font.split(component, textWidth);
            if (wrapped.isEmpty()) {
                lines.add(new RenderLine(Component.empty(), BODY_COLOR, false));
            } else {
                for (FormattedCharSequence sequence : wrapped) {
                    lines.add(new RenderLine(sequence, sourceLine.kind() == LineKind.HEADING
                            ? HEADING_COLOR : BODY_COLOR, sourceLine.kind() == LineKind.HEADING));
                }
            }
        }
    }

    private int maxScroll() {
        return Math.max(0, totalContentHeight - contentHeight);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        int right = panelLeft + PANEL_WIDTH;
        int bottom = panelTop + panelHeight;
        graphics.fill(panelLeft, panelTop, right, bottom, PANEL_BG);
        graphics.fill(panelLeft, panelTop - 1, right, panelTop, PANEL_BORDER_OUTER);
        graphics.fill(panelLeft, bottom, right, bottom + 1, PANEL_BORDER_OUTER);
        graphics.fill(panelLeft - 1, panelTop, panelLeft, bottom, PANEL_BORDER_OUTER);
        graphics.fill(right, panelTop, right + 1, bottom, PANEL_BORDER_OUTER);
        graphics.fill(panelLeft + 1, panelTop + 1, right - 1, panelTop + 2, PANEL_BORDER_INNER);
        graphics.fill(panelLeft + 1, bottom - 2, right - 1, bottom - 1, PANEL_BORDER_INNER);
        renderContent(graphics);
        renderScrollbar(graphics, right);
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    private void renderContent(GuiGraphics graphics) {
        int contentBottom = contentTop + contentHeight;
        graphics.enableScissor(panelLeft + 1, contentTop, panelLeft + PANEL_WIDTH - 1, contentBottom);
        int centerX = panelLeft + (PANEL_WIDTH - SCROLLBAR_WIDTH - 2) / 2;
        int y = contentTop - scrollOffset;
        for (RenderLine line : lines) {
            if (y + line.height() >= contentTop && y <= contentBottom && !line.empty()) {
                if (line.heading()) {
                    graphics.pose().pushPose();
                    graphics.pose().translate(centerX, y, 0.0D);
                    graphics.pose().scale(1.15F, 1.15F, 1.0F);
                    graphics.drawCenteredString(font, line.text(), 0, 0, line.color());
                    graphics.pose().popPose();
                } else {
                    graphics.drawCenteredString(font, line.text(), centerX, y, line.color());
                }
            }
            y += line.height();
        }
        graphics.disableScissor();
    }

    private void renderScrollbar(GuiGraphics graphics, int panelRight) {
        int max = maxScroll();
        if (max <= 0) {
            return;
        }
        int left = panelRight - PADDING / 2 - SCROLLBAR_WIDTH;
        int top = contentTop;
        int height = contentHeight;
        int thumbHeight = Math.max(16, height * height / Math.max(1, totalContentHeight));
        int thumbTop = top + (height - thumbHeight) * scrollOffset / max;
        graphics.fill(left, top, left + SCROLLBAR_WIDTH, top + height, SCROLLBAR_TRACK);
        graphics.fill(left, thumbTop, left + SCROLLBAR_WIDTH, thumbTop + thumbHeight, SCROLLBAR_THUMB);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        scrollBy((int) (-delta * LINE_HEIGHT * 3));
        return maxScroll() > 0 || super.mouseScrolled(mouseX, mouseY, delta);
    }

    private void scrollBy(int amount) {
        scrollOffset = Math.max(0, Math.min(maxScroll(), scrollOffset + amount));
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        switch (keyCode) {
            case GLFW.GLFW_KEY_DOWN -> scrollBy(LINE_HEIGHT);
            case GLFW.GLFW_KEY_UP -> scrollBy(-LINE_HEIGHT);
            case GLFW.GLFW_KEY_PAGE_DOWN -> scrollBy(contentHeight);
            case GLFW.GLFW_KEY_PAGE_UP -> scrollBy(-contentHeight);
            case GLFW.GLFW_KEY_HOME -> scrollOffset = 0;
            case GLFW.GLFW_KEY_END -> scrollOffset = maxScroll();
            default -> {
                return super.keyPressed(keyCode, scanCode, modifiers);
            }
        }
        return true;
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private record RenderLine(FormattedCharSequence text, int color, boolean heading, boolean empty) {

        RenderLine(Component text, int color, boolean heading) {
            this(text.getVisualOrderText(), color, heading, text.getString().isEmpty());
        }

        RenderLine(FormattedCharSequence text, int color, boolean heading) {
            this(text, color, heading, false);
        }

        int height() {
            return LINE_HEIGHT + (heading ? TITLE_EXTRA_HEIGHT : 0);
        }
    }
}
