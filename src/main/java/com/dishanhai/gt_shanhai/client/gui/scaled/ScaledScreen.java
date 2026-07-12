package com.dishanhai.gt_shanhai.client.gui.scaled;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.NotNull;

/**
 * 虚拟分辨率缩放屏基类（移植自 kinetictweaks {@code ScaledScreen}）。
 *
 * <p>把逻辑画布固定为 {@link #targetWidth}×{@link #targetHeight}（默认 640×360），
 * 按窗口大小整体等比缩放，鼠标坐标自动换算回逻辑坐标。子类只需在
 * {@link #initScaled()} 里按逻辑坐标布局控件，在 {@link #renderScaledBackground}/
 * {@link #renderScaledForeground} 里绘制。</p>
 *
 * <p>纯原版 Screen 实现，无外部模组依赖。</p>
 */
public abstract class ScaledScreen extends Screen {
    protected int vWidth;
    protected int vHeight;
    protected float guiScale;
    protected int offsetX;
    protected int offsetY;
    protected float targetWidth;
    protected float targetHeight;
    protected float scaleMultiplier;
    protected float minScale;
    protected float maxScale;
    protected boolean useOffset;
    protected boolean renderRenderablesOnly;

    protected abstract void initScaled();

    protected ScaledScreen(Component title) {
        super(title);
        this.targetWidth = 640.0f;
        this.targetHeight = 360.0f;
        this.scaleMultiplier = 1.0f;
        this.minScale = 0.1f;
        this.maxScale = Float.MAX_VALUE;
        this.useOffset = false;
        this.renderRenderablesOnly = false;
    }

    @Override
    protected void init() {
        super.init();
        float scaleX = this.width / this.targetWidth;
        float scaleY = this.height / this.targetHeight;
        this.guiScale = Math.min(scaleX, scaleY) * this.scaleMultiplier;
        if (this.guiScale < this.minScale) {
            this.guiScale = this.minScale;
        }
        if (this.guiScale > this.maxScale) {
            this.guiScale = this.maxScale;
        }
        this.vWidth = (int) (this.width / this.guiScale);
        this.vHeight = (int) (this.height / this.guiScale);
        if (this.useOffset) {
            this.offsetX = (int) ((this.width - (this.targetWidth * this.guiScale)) / 2.0f);
            this.offsetY = (int) ((this.height - (this.targetHeight * this.guiScale)) / 2.0f);
        } else {
            this.offsetX = 0;
            this.offsetY = 0;
        }
        clearWidgets();
        initScaled();
    }

    @Override
    public void render(@NotNull GuiGraphics g, int mx, int my, float pt) {
        renderBackground(g);
        int smx = (int) ((mx - this.offsetX) / this.guiScale);
        int smy = (int) ((my - this.offsetY) / this.guiScale);
        g.pose().pushPose();
        g.pose().translate(this.offsetX, this.offsetY, 0.0f);
        g.pose().scale(this.guiScale, this.guiScale, 1.0f);
        try {
            renderScaledBackground(g, smx, smy, pt);
            if (this.renderRenderablesOnly) {
                for (Renderable r : this.renderables) {
                    r.render(g, smx, smy, pt);
                }
            } else {
                super.render(g, smx, smy, pt);
            }
            // RenderType.gui() 用的是 LEQUAL 深度测试，同 Z（都是 0）时谁赢由实际提交顺序这种脆弱的
            // 批次时机决定，光 flush() 治标不治本；vanilla 自己的 tooltip 渲染（GuiGraphics.renderTooltip）
            // 就是靠 pose.translate(0,0,400) 把内容顶到更高 Z 层，深度测试直接稳赢，与批次顺序无关——
            // 前景层（二级菜单遮罩/面板/描述等）照抄这个手法，才是根治背景网格物品图标+文字穿模的正解。
            g.flush();
            g.pose().pushPose();
            try {
                g.pose().translate(0.0f, 0.0f, 400.0f);
                renderScaledForeground(g, smx, smy, pt);
            } finally {
                g.pose().popPose();
            }
            g.pose().popPose(); // 弹出最外层 offsetX/offsetY/guiScale 变换——漏弹会让 renderTooltips 的坐标被二次缩放/偏移，飘到错误位置
            renderTooltips(g, smx, smy, mx, my);
        } catch (Throwable th) {
            g.pose().popPose();
            throw th;
        }
    }

    protected void renderScaledBackground(@NotNull GuiGraphics g, int mx, int my, float pt) {
    }

    protected void renderScaledForeground(@NotNull GuiGraphics g, int mx, int my, float pt) {
    }

    protected void renderTooltips(GuiGraphics g, int smx, int smy, int mx, int my) {
    }

    protected void renderScissorCorrectedList(ObjectSelectionList<?> list, GuiGraphics g, int mx, int my, float pt) {
        if (list == null || this.minecraft == null) {
            return;
        }
        GuiGraphics proxy = new GuiGraphics(this.minecraft, g.bufferSource()) {
            @Override
            public void enableScissor(int x, int y, int x2, int y2) {
                super.enableScissor(
                        (int) (x * ScaledScreen.this.guiScale) + ScaledScreen.this.offsetX,
                        (int) (y * ScaledScreen.this.guiScale) + ScaledScreen.this.offsetY,
                        (int) (x2 * ScaledScreen.this.guiScale) + ScaledScreen.this.offsetX,
                        (int) (y2 * ScaledScreen.this.guiScale) + ScaledScreen.this.offsetY);
            }
        };
        proxy.pose().translate(this.offsetX, this.offsetY, 0.0f);
        proxy.pose().scale(this.guiScale, this.guiScale, 1.0f);
        list.render(proxy, mx, my, pt);
    }

    @Override
    public boolean mouseClicked(double mx, double my, int btn) {
        double scaledX = (mx - this.offsetX) / this.guiScale;
        double scaledY = (my - this.offsetY) / this.guiScale;
        boolean handled = universalMouseClicked(scaledX, scaledY, btn);
        if (!handled) {
            setFocused(null);
        }
        return handled;
    }

    protected boolean universalMouseClicked(double mx, double my, int btn) {
        return super.mouseClicked(mx, my, btn);
    }

    @Override
    public boolean mouseReleased(double mx, double my, int btn) {
        return universalMouseReleased((mx - this.offsetX) / this.guiScale, (my - this.offsetY) / this.guiScale, btn);
    }

    protected boolean universalMouseReleased(double mx, double my, int btn) {
        return super.mouseReleased(mx, my, btn);
    }

    @Override
    public boolean mouseDragged(double mx, double my, int btn, double dx, double dy) {
        return universalMouseDragged((mx - this.offsetX) / this.guiScale, (my - this.offsetY) / this.guiScale,
                btn, dx / this.guiScale, dy / this.guiScale);
    }

    protected boolean universalMouseDragged(double mx, double my, int btn, double dx, double dy) {
        return super.mouseDragged(mx, my, btn, dx, dy);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double d) {
        return universalMouseScrolled((mx - this.offsetX) / this.guiScale, (my - this.offsetY) / this.guiScale, d);
    }

    protected boolean universalMouseScrolled(double mx, double my, double d) {
        return super.mouseScrolled(mx, my, d);
    }

    @Override
    public void mouseMoved(double mx, double my) {
        universalMouseMoved((mx - this.offsetX) / this.guiScale, (my - this.offsetY) / this.guiScale);
    }

    protected void universalMouseMoved(double mx, double my) {
        super.mouseMoved(mx, my);
    }
}
