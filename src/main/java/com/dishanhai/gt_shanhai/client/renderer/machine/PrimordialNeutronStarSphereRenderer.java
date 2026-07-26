package com.dishanhai.gt_shanhai.client.renderer.machine;

import net.minecraft.core.Direction;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;

import com.gtladd.gtladditions.client.RenderMode;
import com.gtladd.gtladditions.client.render.machine.antichrist.AntichristDeferredRenderer;
import com.gtladd.gtladditions.client.render.machine.antichrist.AntichristRenderProfile;

/**
 * 中子星渲染：不自绘球体，直接复用伪神之煅炉（gtladditions:forge_of_the_antichrist）的星体管线。
 * <p>
 * 入队 {@link AntichristDeferredRenderer} 后，由伪神锻自己的延迟批次在 AFTER_TRANSLUCENT_BLOCKS
 * 阶段统一绘制——三层球壳、星体着色器、Oculus 光影兼容全部沿用，不需要我们再维护一套。
 * beamAlpha 传 0，跳过伪神锻的天顶光柱；本机只要那颗球。
 */
final class PrimordialNeutronStarSphereRenderer {

    /** 中子星冷白偏蓝色调。 */
    private static final float COLOR_R = 0.72f;
    private static final float COLOR_G = 0.86f;
    private static final float COLOR_B = 1.00f;

    /** 与伪神锻同一基准半径，叠加轻微呼吸脉动。 */
    private static final float BASE_RADIUS = AntichristRenderProfile.BASE_STAR_RADIUS;
    private static final float PULSE_AMPLITUDE = 0.035f;
    private static final float PULSE_PERIOD_TICKS = 18.0f;

    /** 不借用伪神锻的天顶光柱。 */
    private static final float NO_BEAM = 0.0f;

    private PrimordialNeutronStarSphereRenderer() {}

    /**
     * @param continuousTick 必须是连续时钟（{@code RenderUtil.getSmoothTick}）。
     *                       球体常驻可见，任何在工作状态翻转时归零的时钟都会被直接渲染成一次姿态突跳：
     *                       {@code AntichristStarRenderer} 把 tick 当累积角度用（{@code base + tick*mult % 360000}），
     *                       三层球壳无插值无状态，时钟一跳就是整颗球猛地翻一下。
     */
    static void enqueue(BlockEntity blockEntity, Direction facing, float continuousTick, boolean isWorking) {
        Vec3 starPos = PrimordialSphereAnchor.center(facing);

        AntichristRenderProfile profile = new AntichristRenderProfile(
                continuousTick,
                isWorking,
                facing,
                RenderMode.NORMAL,
                starPos,
                COLOR_R, COLOR_G, COLOR_B,
                pulseRadius(continuousTick),
                NO_BEAM);

        AntichristDeferredRenderer.INSTANCE.enqueue(blockEntity, profile);
    }

    /**
     * 呼吸脉动不按 isWorking 开关：那会让半径在停机瞬间从 13*(1±0.035) 硬切回 13.0。
     * 星体渲染器本身并不读 profile.isWorking，工作与否的反馈交给轨道环转速去表达。
     */
    private static float pulseRadius(float continuousTick) {
        float pulse = (float) Math.sin(continuousTick / PULSE_PERIOD_TICKS);
        return BASE_RADIUS * (1.0f + PULSE_AMPLITUDE * pulse);
    }
}
