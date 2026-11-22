package com.shanhai.common.droplet;

import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * 水滴的<b>线段命中判定</b>——抽成独立类，只有一个原因：<b>能被离线探针直接跑</b>。
 *
 * <h2>为什么值得单独一个类</h2>
 * 「不穿透」这条规格在 2026-11-24 之后变得关键：速度可以调到
 * {@code /shanhai droplet speed 200} ⇒ <b>10 格/tick</b>。如果命中判定退化成
 * "看终点在不在目标盒里"，一颗 0.5 格宽的蝙蝠会被整只跳过去。
 * 而这个错误在实机里极难复现（要正好有生物挡在高速航线上），只能靠探针守。
 *
 * <p>所以真正的判定放在这里（{@link AABB#clip(Vec3, Vec3)}——原版 slab 射线裁剪，
 * 返回<b>进入点</b>，线段与盒相交就一定命中），{@link ShanhaiDropletEntity#tryHit}
 * 直接调它；探针也调它 ⇒ 测的是同一份代码，不是复制品。
 *
 * <h2>为什么还要 {@code contains(from)}</h2>
 * 起点已经在盒内时 {@code clip} 返回 empty（原版的语义是"求射线与盒的交点，起点在内部没有进入点"），
 * 所以补一条"已经在里面也算命中"——否则贴脸时会漏判。
 */
public final class ShanhaiDropletSweep {

    private ShanhaiDropletSweep() {}

    /**
     * 这一 tick 的位移线段 {@code from → to} 是否碰到这个盒。
     *
     * @param box  目标包围盒（调用方已按 {@code HIT_PAD} 膨胀）
     * @param from 线段起点（本 tick 位移前的位置）
     * @param to   线段终点（本 tick 位移后的位置）
     */
    public static boolean segmentHitsBox(AABB box, Vec3 from, Vec3 to) {
        return box.clip(from, to).isPresent() || box.contains(from);
    }

    /**
     * <b>反例</b>：只看终点在不在盒里（旧写法 / 负对照专用的错误实现）。
     *
     * <p>留在正式代码里是有意的：探针用它当负对照，证明"10 格/tick 时只看终点会漏判"。
     * <b>生产路径不许调用它</b>。
     */
    public static boolean endpointOnlyHitsBox(AABB box, Vec3 from, Vec3 to) {
        return box.contains(to);
    }
}
