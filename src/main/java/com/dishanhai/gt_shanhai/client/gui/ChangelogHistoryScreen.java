package com.dishanhai.gt_shanhai.client.gui;

import com.dishanhai.gt_shanhai.common.config.ChangelogConfig;
import com.dishanhai.gt_shanhai.common.config.ChangelogConfig.ChangelogDocument;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * 历史更新列表；点击任一版本可回看对应 Markdown 公告。
 */
public final class ChangelogHistoryScreen extends Screen {

    private final Screen parent;
    private final List<ChangelogDocument> documents;

    private ChangelogHistoryScreen(Screen parent) {
        super(Component.literal("历史更新"));
        this.parent = parent;
        this.documents = ChangelogConfig.getAll();
    }

    public static void open(Screen parent) {
        net.minecraft.client.Minecraft.getInstance().setScreen(new ChangelogHistoryScreen(parent));
    }

    @Override
    protected void init() {
        int buttonWidth = Math.min(520, this.width - 40);
        int buttonX = (this.width - buttonWidth) / 2;
        int y = 42;
        for (ChangelogDocument document : this.documents) {
            if (y + 20 > this.height - 34) {
                break;
            }
            this.addRenderableWidget(Button.builder(
                            Component.literal(document.version() + "  " + document.title()),
                            button -> ChangelogScreen.open(this, document))
                    .bounds(buttonX, y, buttonWidth, 20)
                    .build());
            y += 24;
        }
        this.addRenderableWidget(Button.builder(Component.literal("返回"), button -> onClose())
                .bounds(buttonX, this.height - 28, buttonWidth, 20)
                .build());
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        graphics.drawCenteredString(font, title, width / 2, 18, 0xFFFFAA00);
        if (documents.isEmpty()) {
            graphics.drawCenteredString(font, Component.literal("暂无历史更新"), width / 2, 46, 0xFFDDDDDD);
        }
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
