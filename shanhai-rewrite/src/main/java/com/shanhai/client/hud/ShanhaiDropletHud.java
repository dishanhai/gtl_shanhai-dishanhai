package com.shanhai.client.hud;

import com.mojang.blaze3d.vertex.VertexConsumer;
import com.shanhai.ShanhaiMod;
import com.shanhai.client.render.droplet.DropletLayers;
import com.shanhai.client.render.droplet.ShanhaiDropletOwnership;
import com.shanhai.client.render.droplet.ShanhaiDropletWorldFx;
import com.shanhai.common.droplet.ShanhaiDropletEntity;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.event.RenderGuiOverlayEvent;
import net.minecraftforge.client.gui.overlay.VanillaGuiOverlay;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import org.joml.Matrix4f;

import java.util.List;
import java.util.Locale;

/**
 * 强相互作用水滴的 <b>HUD</b>（只在拥有者自己屏幕上、且只在他名下存在水滴时显示）+ <b>准星拉线</b>。
 *
 * <h2>一、挂点沿用本工程的既有先例</h2>
 * {@code @OnlyIn(Dist.CLIENT)} + {@code @Mod.EventBusSubscriber(value=Dist.CLIENT, bus=FORGE)}
 * 自注册 + {@code RenderGuiOverlayEvent.Post}，并按
 * {@code overlay.id()} 回调（不自己算 y）—— 与 {@code shanhai-rewrite-add} 的
 * {@code ShanhaiAnchorBarHud}（挂在 {@code EXPERIENCE_BAR} 上）是同一套写法，
 * 这里挂在 {@code CROSSHAIR} 上：**信息跟着准星走**，且不受血条/经验条隐藏（创造模式）影响。
 *
 * <h2>二、显示条件（两条，AND）</h2>
 * <ol>
 *   <li>{@code Minecraft.getInstance().options.hideGui == false}（F1 隐藏 HUD 时不画）；</li>
 *   <li>{@link ShanhaiDropletOwnership#ownedInLevel()} <b>非空</b>
 *       —— "只在他名下存在水滴时显示"就是这一条；空的时候整块 HUD 与全部拉线<b>一个像素都不画</b>。</li>
 * </ol>
 *
 * <h2>三、坐标怎么来的</h2>
 * 世界 → 屏幕<b>不重推矩阵</b>：{@link ShanhaiDropletWorldFx} 在
 * {@code RenderLevelStageEvent} 里把原版给的 {@code 投影矩阵 × 视图矩阵} 捕获下来，
 * 这里调 {@link ShanhaiDropletWorldFx#projectToGui} 做一次 {@code clip = P·V·(p−cam)} + 透视除法。
 * 与 {@code ShanhaiHoloMenuRenderer#ensureVisMvp} / {@code ShanhaiHoloSurroundRenderer}
 * 的 {@code MVP.set(event.getProjectionMatrix()).mul(VIEW)} 是<b>同一条路</b>;
 * 差别只是他们把结果留在世界层直接用，这里要多走一步 NDC→GUI 像素。
 */
@OnlyIn(Dist.CLIENT)
@Mod.EventBusSubscriber(modid = ShanhaiMod.MOD_ID, value = Dist.CLIENT,
        bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class ShanhaiDropletHud {

    private static final String PREFIX = "[SHANHAI-DROPLET-HUD]";

    /** 同时最多列几条（名下水滴可以很多，HUD 不是清单）。 */
    private static final int MAX_ROWS = 3;

    private static final int COLOR_HEADER = 0xFF7FE8FF;
    private static final int COLOR_COORD = 0xFFB6CCE4;
    private static final int COLOR_TARGET = 0xFFFFC46B;
    private static final int COLOR_NO_TARGET = 0xFF8A97A8;
    private static final int COLOR_SHADOW = 0xB0000000;

    /** 拉线颜色（青色半透明）与宽度。 */
    private static final int LINE_COLOR = 0x9A8FE8FF;
    private static final float LINE_WIDTH = 1.2F;

    /** 复用的排序槽（按距离取最近的 3 个；不 new 集合）。 */
    private static final ShanhaiDropletEntity[] NEAREST = new ShanhaiDropletEntity[MAX_ROWS];
    private static final double[] NEAREST_DIST = new double[MAX_ROWS];

    /** 投影结果暂存：{@code [0]=guiX, [1]=guiY, [2]=可见}。 */
    private static final float[] PROJ = new float[3];

    private static boolean loggedFirstDraw;

    private ShanhaiDropletHud() {}

    @SubscribeEvent
    public static void onRenderGuiOverlayPost(RenderGuiOverlayEvent.Post event) {
        final Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.player == null || mc.level == null || mc.options == null) {
            return;
        }
        if (mc.options.hideGui) {
            return;
        }
        if (!VanillaGuiOverlay.CROSSHAIR.type().id().equals(event.getOverlay().id())) {
            return;
        }
        final List<ShanhaiDropletEntity> owned = ShanhaiDropletOwnership.ownedInLevel();
        if (owned.isEmpty()) {
            return;
        }
        final ClientLevel level = mc.level;
        // ① 按距离挑最近的几条（插入排序；MAX_ROWS 只有 3 ⇒ 常数开销，零分配）
        int rows = 0;
        for (int i = 0; i < owned.size(); i++) {
            final ShanhaiDropletEntity droplet = owned.get(i);
            if (!droplet.isAlive() || droplet.level() != level) {
                continue;
            }
            final double dist = Math.sqrt(droplet.distanceToSqr(mc.player));
            if (rows < MAX_ROWS) {
                NEAREST[rows] = droplet;
                NEAREST_DIST[rows] = dist;
                rows++;
            } else {
                int worst = 0;
                for (int k = 1; k < MAX_ROWS; k++) {
                    if (NEAREST_DIST[k] > NEAREST_DIST[worst]) {
                        worst = k;
                    }
                }
                if (dist < NEAREST_DIST[worst]) {
                    NEAREST[worst] = droplet;
                    NEAREST_DIST[worst] = dist;
                }
            }
        }
        if (rows == 0) {
            return;
        }
        // 简单选择排序（3 条）
        for (int i = 0; i < rows; i++) {
            int best = i;
            for (int k = i + 1; k < rows; k++) {
                if (NEAREST_DIST[k] < NEAREST_DIST[best]) {
                    best = k;
                }
            }
            if (best != i) {
                final ShanhaiDropletEntity td = NEAREST[i];
                NEAREST[i] = NEAREST[best];
                NEAREST[best] = td;
                final double dd = NEAREST_DIST[i];
                NEAREST_DIST[i] = NEAREST_DIST[best];
                NEAREST_DIST[best] = dd;
            }
        }

        final GuiGraphics graphics = event.getGuiGraphics();
        final Font font = mc.font;
        final int guiW = event.getWindow() == null ? graphics.guiWidth() : event.getWindow().getGuiScaledWidth();
        final int guiH = event.getWindow() == null ? graphics.guiHeight() : event.getWindow().getGuiScaledHeight();

        // ② 文字块（左上角；避开准星/快捷栏/经验条）
        int x = 6;
        int y = 6;
        graphics.drawString(font, Component.literal("强相互作用水滴 ×" + owned.size()), x, y, COLOR_HEADER, true);
        y += font.lineHeight + 1;
        for (int i = 0; i < rows; i++) {
            final ShanhaiDropletEntity droplet = NEAREST[i];
            graphics.drawString(font, "坐标  "
                            + String.format(Locale.ROOT, "X %.1f   Y %.1f   Z %.1f", droplet.getX(), droplet.getY(), droplet.getZ()),
                    x, y, COLOR_COORD, true);
            y += font.lineHeight;
            graphics.drawString(font, String.format(Locale.ROOT, "距离  %.1f 格", NEAREST_DIST[i]),
                    x, y, COLOR_COORD, true);
            y += font.lineHeight;
            final String target = droplet.hasTarget() && !droplet.targetName().isEmpty()
                    ? droplet.targetName() : "无目标";
            graphics.drawString(font, "目标  " + target, x, y,
                    target.equals("无目标") ? COLOR_NO_TARGET : COLOR_TARGET, true);
            y += font.lineHeight + 3;
        }

        // ③ 准星拉线（屏幕中心 → 水滴投影点），关深度
        drawCrosshairLines(graphics, mc, guiW, guiH, rows);

        if (!loggedFirstDraw) {
            loggedFirstDraw = true;
            ShanhaiMod.LOGGER.info("{} hud_draw owned={} rows={} gui={}x{}",
                    PREFIX, Integer.valueOf(owned.size()), Integer.valueOf(rows),
                    Integer.valueOf(guiW), Integer.valueOf(guiH));
        }
    }

    /**
     * 从准星（屏幕中心）到每个水滴的<b>屏幕投影点</b>画一条细线。
     *
     * <p>投影见 {@link ShanhaiDropletWorldFx#projectToGui}（复用 {@code RenderLevelStageEvent}
     * 捕获的 {@code P·V}）；本方法只负责"把两点连成一条 1.2 px 的四边形"。
     * 投影点在相机后方（{@code 可见=0}）时<b>整条不画</b> —— 那会把线画到屏幕对称的另一侧，
     * 看起来像水滴在背后，是明确的错误信息。
     */
    private static void drawCrosshairLines(GuiGraphics graphics, Minecraft mc, int guiW, int guiH, int rows) {
        if (!ShanhaiDropletWorldFx.projectionReady()) {
            return;
        }
        final float cx = guiW * 0.5F;
        final float cy = guiH * 0.5F;
        final MultiBufferSource.BufferSource buffers = graphics.bufferSource();
        final Matrix4f m = graphics.pose().last().pose();
        final VertexConsumer vc = buffers.getBuffer(DropletLayers.gui());
        int drawn = 0;
        for (int i = 0; i < rows; i++) {
            final ShanhaiDropletEntity droplet = NEAREST[i];
            ShanhaiDropletWorldFx.projectToGui(droplet.getX(), droplet.getY() + 0.0D, droplet.getZ(),
                    guiW, guiH, PROJ);
            if (PROJ[2] < 0.5F) {
                continue;
            }
            line(vc, m, cx, cy, PROJ[0], PROJ[1], LINE_WIDTH, LINE_COLOR);
            drawn++;
        }
        if (drawn > 0) {
            buffers.endBatch(DropletLayers.gui());
        }
    }

    /** 两点之间的一条四边形（GUI 空间，z=0；端点各外扩半宽以免出现斜接缺口）。 */
    private static void line(VertexConsumer vc, Matrix4f m, float x0, float y0, float x1, float y1,
                             float width, int argb) {
        float dx = x1 - x0;
        float dy = y1 - y0;
        final float len = (float) Math.sqrt(dx * dx + dy * dy);
        if (len < 1.0E-3F) {
            return;
        }
        dx /= len;
        dy /= len;
        final float nx = -dy * width * 0.5F;
        final float ny = dx * width * 0.5F;
        final float ax = x0 - dx * 0.5F;
        final float ay = y0 - dy * 0.5F;
        final float bx = x1 + dx * 0.5F;
        final float by = y1 + dy * 0.5F;
        final float a = ((argb >>> 24) & 0xFF) / 255.0F;
        final float r = ((argb >>> 16) & 0xFF) / 255.0F;
        final float g = ((argb >>> 8) & 0xFF) / 255.0F;
        final float b = (argb & 0xFF) / 255.0F;
        vc.vertex(m, ax + nx, ay + ny, 0.0F).color(r, g, b, a).endVertex();
        vc.vertex(m, bx + nx, by + ny, 0.0F).color(r, g, b, a).endVertex();
        vc.vertex(m, bx - nx, by - ny, 0.0F).color(r, g, b, a).endVertex();
        vc.vertex(m, ax - nx, ay - ny, 0.0F).color(r, g, b, a).endVertex();
    }
}
