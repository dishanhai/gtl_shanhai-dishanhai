package com.dishanhai.gt_shanhai.common.machine.part.cloud;

import com.dishanhai.sh_better_ui.client.glass.GlassColors;
import com.dishanhai.sh_better_ui.client.glass.GlassSurface;
import com.dishanhai.sh_better_ui.client.gui.scaled.ButtonFeedback;
import com.dishanhai.sh_better_ui.client.gui.scaled.GuiMotion;
import com.dishanhai.sh_better_ui.client.gui.scaled.GuiRenderUtil;
import com.dishanhai.sh_better_ui.client.gui.scaled.PageTransition;
import com.dishanhai.sh_better_ui.compat.ldlib.GlassPanelTexture;
import com.lowdragmc.lowdraglib.gui.texture.IGuiTexture;
import com.lowdragmc.lowdraglib.gui.texture.TextTexture;
import com.lowdragmc.lowdraglib.gui.widget.ButtonWidget;
import com.lowdragmc.lowdraglib.gui.widget.ComponentPanelWidget;
import com.lowdragmc.lowdraglib.gui.widget.DraggableScrollableWidgetGroup;
import com.lowdragmc.lowdraglib.gui.widget.Widget;
import com.lowdragmc.lowdraglib.gui.widget.WidgetGroup;
import com.lowdragmc.lowdraglib.utils.Size;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** 山海：三個雲端頁面共用的呈現適配；業務狀態與動作仍由原入口提供。 */
public final class StellarCloudUiWidgets {
    private static final GlassColors COLORS = GlassColors.SMOKE;
    private static final String WAITING = "gt_shanhai.cloud.ui.waiting";

    private StellarCloudUiWidgets() {}

    /** 客戶端意圖按鈕：沿用玻璃動效與命中，動作透過 UiSync 取得伺服端回執。 */
    public static ButtonWidget intent(WidgetGroup root, int x, int y, int width, int height,
                                      String labelKey, Runnable clientAction,
                                      Supplier<StellarCloudUiContract.ControlState> control) {
        var button = new ActionButton(x, y, width, height, labelKey, () -> {}, () -> control.get().reasonKey(), ignored -> {});
        button.clientIntent = clientAction;
        root.addWidget(button);
        return button;
    }

    public static ButtonWidget action(WidgetGroup root, int x, int y, int width, int height,
                                      String fullLabelKey, Runnable serverAction, Supplier<String> disabledReason) {
        return action(root, x, y, width, height, fullLabelKey, serverAction, disabledReason, ignored -> {});
    }

    public static ButtonWidget action(WidgetGroup root, int x, int y, int width, int height,
                                      String fullLabelKey, Runnable serverAction, Supplier<String> disabledReason,
                                      Consumer<String> rejected) {
        var button = new ActionButton(x, y, width, height, fullLabelKey, serverAction, disabledReason, rejected);
        root.addWidget(button);
        return button;
    }

    public static ComponentPanelWidget text(WidgetGroup root, int x, int y, int width, int height, int color,
                                             Supplier<Component> value) {
        var text = new SyncedText(x, y, width, height, color, value);
        root.addWidget(text);
        return text;
    }

    public static IGuiTexture surface() {
        return (graphics, mouseX, mouseY, x, y, width, height) ->
                GlassSurface.smokeControl(graphics, (int) x, (int) y, width, height, 0f);
    }

    public static DraggableScrollableWidgetGroup viewport(int x, int y, int width, int height) {
        return new Viewport(x, y, width, height);
    }

    static boolean inside(int x, int y, int width, int height, double mouseX, double mouseY) {
        return mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height;
    }

    /** 不讀取客戶端 Session；伺服端每次執行時重新查詢原因，不能沿用畫面上的舊狀態。 */
    static String runServerAction(boolean remote, Supplier<String> disabledReason, Runnable action) {
        if (remote) return "";
        String reason = Objects.requireNonNullElse(disabledReason.get(), WAITING);
        if (reason.isEmpty()) action.run();
        return reason;
    }

    /** 裁切與命中使用同一範圍，子控件不能把面板命中區延伸到背包。 */
    private static final class Viewport extends DraggableScrollableWidgetGroup {
        Viewport(int x, int y, int width, int height) {
            super(x, y, width, height);
            setDraggable(false);
            setScrollable(true);
            setUseScissor(true);
            setYScrollBarWidth(4);
            setYBarStyle(IGuiTexture.EMPTY, (graphics, mouseX, mouseY, xx, yy, w, h) ->
                    graphics.fill((int) xx, (int) yy, (int) xx + w, (int) yy + h, COLORS.ACCENT));
        }

        @Override public boolean isMouseOverElement(double mouseX, double mouseY) {
            return inside(getPosition().x, getPosition().y, getSize().width, getSize().height, mouseX, mouseY);
        }

        @Override public Widget getHoverElement(double mouseX, double mouseY) {
            return isMouseOverElement(mouseX, mouseY) ? super.getHoverElement(mouseX, mouseY) : null;
        }

        @Override @OnlyIn(Dist.CLIENT)
        public boolean mouseClicked(double mouseX, double mouseY, int button) {
            if (!isMouseOverElement(mouseX, mouseY)) return false;
            super.mouseClicked(mouseX, mouseY, button);
            return true;
        }

        @Override @OnlyIn(Dist.CLIENT)
        public boolean mouseWheelMove(double mouseX, double mouseY, double delta) {
            if (!isMouseOverElement(mouseX, mouseY)) return false;
            super.mouseWheelMove(mouseX, mouseY, delta);
            return true;
        }
    }

    private static final class ActionButton extends ButtonWidget {
        private static final int REASON_UPDATE = 80;
        private final String labelKey;
        private final Supplier<String> disabledReason;
        private final TextTexture label;
        private final ButtonFeedback feedback = new ButtonFeedback();
        private String reason = WAITING;
        private Runnable clientIntent;

        ActionButton(int x, int y, int width, int height, String labelKey,
                     Runnable serverAction, Supplier<String> disabledReason, Consumer<String> rejected) {
            super(x, y, width, height, click -> {
                if (click.button == 0) {
                    String reason = runServerAction(click.isRemote, disabledReason, serverAction);
                    if (!reason.isEmpty()) rejected.accept(reason);
                }
            });
            this.labelKey = labelKey;
            this.disabledReason = disabledReason;
            label = new TextTexture(labelKey, COLORS.TEXT).setWidth(Math.max(1, width - 8))
                    .setType(TextTexture.TextType.ROLL).setDropShadow(false);
            setButtonTexture(GlassPanelTexture.smokeButtonOverlay());
        }

        private String currentReason() { return Objects.requireNonNullElse(disabledReason.get(), WAITING); }
        private String displayedReason() { return clientIntent == null ? reason : currentReason(); }

        @Override public void writeInitialData(FriendlyByteBuf buffer) {
            super.writeInitialData(buffer);
            reason = clientIntent == null ? currentReason() : WAITING;
            buffer.writeUtf(reason);
        }

        @Override public void readInitialData(FriendlyByteBuf buffer) {
            super.readInitialData(buffer);
            reason = buffer.readUtf();
        }

        @Override public void detectAndSendChanges() {
            super.detectAndSendChanges();
            if (clientIntent != null) return;
            String current = currentReason();
            if (!reason.equals(current)) {
                reason = current;
                writeUpdateInfo(REASON_UPDATE, buffer -> buffer.writeUtf(current));
            }
        }

        @Override public void readUpdateInfo(int id, FriendlyByteBuf buffer) {
            if (id == REASON_UPDATE) reason = buffer.readUtf();
            else super.readUpdateInfo(id, buffer);
        }

        @Override @OnlyIn(Dist.CLIENT)
        public boolean mouseClicked(double mouseX, double mouseY, int button) {
            if (!isMouseOverElement(mouseX, mouseY)) return false;
            boolean enabled = displayedReason().isEmpty();
            if (button == 0) {
                feedback.press(0, getPosition().x, getPosition().y, getSize().width, getSize().height,
                        enabled, PageTransition.nowMs());
                if (enabled) {
                    if (clientIntent != null) { clientIntent.run(); return true; }
                    return super.mouseClicked(mouseX, mouseY, button);
                }
            }
            // 停用與非左鍵仍佔住命中區，不能落到下層樣板或背包槽位。
            return true;
        }

        @Override @OnlyIn(Dist.CLIENT)
        public boolean mouseReleased(double mouseX, double mouseY, int button) {
            if (button == 0 && feedback.release(PageTransition.nowMs())) return true;
            return isMouseOverElement(mouseX, mouseY);
        }

        @Override @OnlyIn(Dist.CLIENT)
        public void drawInBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
            long now = PageTransition.nowMs();
            feedback.pointer(mouseX, mouseY, now);
            feedback.tick(now);
            boolean enabled = displayedReason().isEmpty();
            float hover = feedback.hover(0, enabled && isMouseOverElement(mouseX, mouseY), now);
            var visual = feedback.visual(0, 0, enabled, now, false);
            int x = getPosition().x, y = getPosition().y, width = getSize().width, height = getSize().height;
            GuiRenderUtil.withButtonMotion(graphics, x, y, width, height, visual.scale(), visual.shakeX(), () -> {
                drawBackgroundTexture(graphics, mouseX, mouseY);
                int fill = enabled ? GuiMotion.lerpColor(COLORS.BUTTON_FILL, COLORS.BUTTON_FILL_HOVER, hover)
                        : COLORS.DISPLAY_FILL;
                graphics.fill(x + 1, y + 1, x + width - 1, y + height - 1, ButtonFeedback.shade(fill, visual));
                graphics.renderOutline(x, y, width, height, enabled
                        ? ButtonFeedback.tint(GuiMotion.lerpColor(COLORS.BUTTON_EDGE, COLORS.ACCENT, hover), visual)
                        : COLORS.DISPLAY_EDGE);
                label.setColor(enabled ? COLORS.TEXT : COLORS.TEXT_OFF);
                label.draw(graphics, mouseX, mouseY, x + 4, y, Math.max(1, width - 8), height);
            });
        }

        @Override @OnlyIn(Dist.CLIENT)
        public void drawInForeground(GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
            String displayed = displayedReason();
            setHoverTooltips(Component.translatable(displayed.isEmpty() ? labelKey : displayed));
            super.drawInForeground(graphics, mouseX, mouseY, partialTicks);
        }
    }

    /** 使用 ComponentPanel 同步翻譯元件，固定尺寸並裁切長字，不擴大命中區。 */
    private static final class SyncedText extends ComponentPanelWidget {
        private final int width, height;
        private final TextTexture texture;
        private String rendered = "";

        SyncedText(int x, int y, int width, int height, int color, Supplier<Component> value) {
            super(x, y, lines -> lines.add(value.get()));
            this.width = width;
            this.height = height;
            texture = new TextTexture("", color).setWidth(width).setType(TextTexture.TextType.ROLL).setDropShadow(false);
            setMaxWidthLimit(width);
            setSize(new Size(width, height));
        }

        @Override @OnlyIn(Dist.CLIENT)
        public void updateComponentTextSize() {
            if (width > 0) setSize(new Size(width, height));
        }

        @Override @OnlyIn(Dist.CLIENT)
        public void drawInBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
            String value = lastText.isEmpty() ? "" : lastText.getFirst().getString();
            if (!rendered.equals(value)) { rendered = value; texture.updateText(value); }
            int x = getPosition().x, y = getPosition().y;
            var matrix = graphics.pose().last().pose();
            graphics.enableScissor((int) Math.floor(x * matrix.m00() + matrix.m30()),
                    (int) Math.floor(y * matrix.m11() + matrix.m31()),
                    (int) Math.ceil((x + width) * matrix.m00() + matrix.m30()),
                    (int) Math.ceil((y + height) * matrix.m11() + matrix.m31()));
            try { texture.draw(graphics, mouseX, mouseY, x, y, width, height); }
            finally { graphics.disableScissor(); }
        }

        @Override @OnlyIn(Dist.CLIENT)
        public void drawInForeground(GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
            setHoverTooltips(Minecraft.getInstance().font.width(rendered) > width ? lastText : List.of());
            super.drawInForeground(graphics, mouseX, mouseY, partialTicks);
        }

        @Override public boolean mouseClicked(double mouseX, double mouseY, int button) { return false; }
    }
}
