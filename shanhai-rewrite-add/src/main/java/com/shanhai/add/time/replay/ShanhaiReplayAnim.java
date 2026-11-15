package com.shanhai.add.time.replay;

/**
 * 山海重构 · <b>回放期间的"倒放动画"口径</b>（🔴 零 Minecraft 依赖，别加 import）。
 *
 * <h2>1. 用户口径（2026-11-14 13:52 录像）</h2>
 * <blockquote>「目前这个<b>猪倒着移动是只在移动、没有它的那个原版的动画的倒放</b>，也加上」</blockquote>
 * ⇒ 位置已经在退了，但四条腿还在往前迈（其实冻结期间腿根本不动）——
 * 要的是<b>原版动画本身也倒着播</b>：走路摆动、idle 呼吸、耳朵/尾巴这些程序化动画。
 *
 * <h2>2. 动画是怎么被驱动的（1.20.1）</h2>
 * <ul>
 *   <li>{@code LivingEntity#walkAnimation}（{@code public final WalkAnimationState}）：
 *       {@code update(movement, partial)} 里 {@code speed += (movement-speed)*partial; position += speed}；
 *       腿的摆角 = {@code speed(partial)} / {@code position(partial)}；</li>
 *   <li>idle/呼吸/耳朵：{@code LivingEntity#tickCount}（= {@code ageInTicks}）+ partial；</li>
 *   <li>头：{@code yHeadRot} / {@code yHeadRotO} 的插值。</li>
 * </ul>
 * 冻结期间这些全部停住 ⇒ 观感是"整体平移"，不是倒放。
 *
 * <h2>3. 本类的口径</h2>
 * 回放期间对<b>我们驱动的实体</b>每客户端 tick 调一次 {@code walkAnimation.update(-speed, 1)}、
 * {@code tickCount--}，等价于把动画时钟倒着拨。
 * {@link #reverseSpeed(float)} 是纯函数（自检断言：0.4 ⇒ −0.4；负对照不取反 ⇒ 判红）。
 */
public final class ShanhaiReplayAnim {

    /** 倒放时的步幅系数（取原版一拍的走路量级；0.4 = 玩家走路 ≈ 0.4）。 */
    public static final float REVERSE_SPEED = -0.4F;

    /** 速度取反（0 保持 0：站着不动的实体不许被我们弄出抖动）。 */
    public static float reverseSpeed(float speed) {
        return speed == 0.0F ? 0.0F : -speed;
    }

    /** 一行判据（客户端每秒一行）。 */
    public static String line(String dir, long reversed) {
        return "anim_dir=" + dir + " anim_reversed_tick=" + reversed;
    }

    private ShanhaiReplayAnim() {
    }
}
