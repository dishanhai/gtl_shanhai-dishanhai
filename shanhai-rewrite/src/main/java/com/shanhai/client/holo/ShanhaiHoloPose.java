package com.shanhai.client.holo;

import net.minecraft.util.Mth;

/**
 * 山海重构 · 全息位姿的<b>唯一落地点</b>（本地与远程<b>共用同一个函数</b>）。
 *
 * <h2>1. 它解决的是什么</h2>
 * 联机之后，"别人眼里的你那 5 块板画在哪"必须与"你自己看到的"是同一个式子算出来的。
 * 两份公式 = 两份口径 = 早晚漂开。⇒ 全链路只有<b>一个</b>地方把
 * 「眼睛位置 + 朝向 + 排布 + 距离」变成「中心 + 框偏航 + 5 块板」，就是这里。
 * 它自己一行几何都不算，全部转调 {@link ShanhaiHoloMenuLayout} 的三个纯函数
 * （{@code centerOf} / {@code frameYawOf} / {@code boardsOf}）—— 与本地
 * {@code ShanhaiHoloMenuState#advanceHolo} 第一帧那一支<b>逐字同一个式子</b>。
 *
 * <h2>2. 🔴 朝向必须是 {@code Mth.rotLerp}，不许用 {@code getViewVector(pt)}</h2>
 * {@code Entity.getViewVector(pt)} ⟶ {@code getViewYRot(pt)} ⟶ <b>{@code Mth.lerp}</b>（线性插值）；
 * 而本地那一支用的是 {@link Mth#rotLerp}（<b>走最短弧</b>）。
 * 两者在 359°→1° 这种跨越上<b>不一样</b>：线性插值会让全息从 -179° 那头绕过去（整块转半圈），
 * 最短弧只走 2°。⇒ 远程必须调 {@link #yawDeg}，不许自己写。
 * （本条有机器可判的负对照：把 {@link #yawDeg} 换成线性版本，位姿断言必须报红 —— 见
 * {@code temp/holo-verify} 的远程位姿断言。）
 *
 * <h2>3. 逐位一致的保证方式</h2>
 * "本地路径"与"远程路径"是<b>同一个函数</b>的两次调用，唯一不同的一行是
 * <b>从哪里取 eye 与 yaw</b>：
 * <table border="1">
 *   <tr><th>输入</th><th>本地</th><th>远程</th></tr>
 *   <tr><td>眼睛位置</td><td>{@code player.getEyePosition(pt)}</td>
 *       <td>{@code other.getEyePosition(pt)}</td></tr>
 *   <tr><td>偏航</td><td>{@code Mth.rotLerp(pt, player.yRotO, player.getYRot())}</td>
 *       <td>{@link #yawDeg}{@code (other.yRotO, other.getYRot(), pt)}</td></tr>
 * </table>
 * 而 {@code LocalPlayer} 与 {@code AbstractClientPlayer} 都继承自 {@code Player}
 * ⇒ 这两个数<b>来自同一个类</b>（同一个 {@code getEyePosition} 实现、同一个 {@code yRotO} 字段），
 * 不存在"本地一套实现、远程另一套"。
 *
 * <p>⚠️ {@code getEyePosition(pt)} 的 Y 是 {@code Mth.lerp(pt, yo, getY()) + getEyeHeight()}：
 * <b>眼高那一项不插值</b>。本地走的也是这个函数 ⇒ <b>两边偏差为 0</b>；
 * 潜行/游泳切换姿态的那一两帧眼高会瞬跳，那是原版行为，不是本功能引入的。
 */
public final class ShanhaiHoloPose {

    private ShanhaiHoloPose() {
    }

    /**
     * 一个"已经算好的"全息位姿 —— 渲染器要的全部输入。
     *
     * @param cx,cy,cz     全息中心（世界坐标）
     * @param frameYawDeg  框偏航（度）—— 环绕档恒 0；旧两档 = {@code 180 − 玩家偏航}
     * @param boards       5 块板的<b>局部</b>位姿（进入本体坐标系之后）
     */
    public record Landing(double cx, double cy, double cz, float frameYawDeg,
                          ShanhaiHoloMenuLayout.Board[] boards) {
    }

    /**
     * <b>帧级朝向</b>（度）：上一 tick 的偏航 → 这一 tick 的偏航，按 {@code partialTick} 插值。
     *
     * <p>🔴 最短弧（{@link Mth#rotLerp}）。<b>不许</b>换成 {@code Mth.lerp}，
     * 也<b>不许</b>用 {@code getViewVector(pt)} 反推（那两条都是线性插值）。
     */
    public static float yawDeg(float yRotO, float yRot, float partialTick) {
        return Mth.rotLerp(partialTick, yRotO, yRot);
    }

    /**
     * 落地位姿：{@code 眼睛 + 朝向 + 排布 + 距离} ⇒ {@code 中心 + 框偏航 + 5 块板}。
     *
     * <p>🔴 三行分别对应 {@code ShanhaiHoloMenuState#advanceHolo} 里落地那一支的
     * {@code centerOf}(用<b>未归一化</b>的 yaw) 与 {@code frameYawOf}(用<b>归一化后</b>的 yaw)
     * —— 两者对不上的话，`row`/`column` 两档下"本地看到的框"与"远程看到的框"会差 360° 的整数倍
     * （旋转相同、数值不同 ⇒ 逐位断言会红）。口径<b>只此一处</b>。
     */
    public static Landing landing(String layoutName, float distance,
                                  double eyeX, double eyeY, double eyeZ, float playerYawDeg) {
        final double[] c = ShanhaiHoloMenuLayout.centerOf(layoutName, eyeX, eyeY, eyeZ, playerYawDeg, distance);
        final float frameYaw = ShanhaiHoloMenuLayout.frameYawOf(layoutName, Mth.wrapDegrees(playerYawDeg));
        final ShanhaiHoloMenuLayout.Board[] boards = ShanhaiHoloMenuLayout.boardsOf(layoutName, distance);
        return new Landing(c[0], c[1], c[2], frameYaw, boards);
    }

    // ================================================================== 每帧顶点容量（纯算术，离线可验）

    /**
     * 每帧那个 {@code BufferBuilder} 容量的<b>下限</b>（顶点数，不是字节）——
     * 就是联机之前一直在用的 {@code 1<<14 = 16384}。
     *
     * <p>⚠️ {@code BufferBuilder(int capacity)} 内部是 {@code MemoryTracker.create(capacity * 6)}
     * ⇒ {@code 1<<14} 对应 <b>98,304 字节（96 KiB）</b>。
     * 唯一一份：{@code ShanhaiHoloMenuRenderer.PER_FRAME_BUFFER_CAPACITY} 引用本常量。
     */
    public static final int BUFFER_CAPACITY_FLOOR = 1 << 14;

    /** 每个"人"预留多少顶点。{@code 1<<13 = 8192} 是实测每人 ≈2500 顶点的 <b>3.2 倍余量</b>。 */
    public static final int BUFFER_CAPACITY_PER_PLAYER = 1 << 13;

    /**
     * 🔴 <b>这一帧要画 {@code players} 个人，{@code BufferBuilder} 该开多大</b>（设计 §4.3/§4.4 的账）：
     * <pre>
     *   每人 ≈ 2500 顶点（5 块板 1080 + 环绕 1469）＋本地那一份 ≈ 2600
     *   1 人 ≈  5101 ＜ 16384 ✓
     *   3 人 ≈ 10101 ＜ 16384 ✓（改动前容量也是 1&lt;&lt;14，所以 3 人才是分水岭）
     *   8 人 ≈ 22601 ＞ 16384 ✗ ⇒ 会走 ensureCapacity 那条"+2 MB realloc"，每帧一次
     * </pre>
     * 取 2 的幂<span>向上取整</span>（不震荡），且<b>下限仍是原来那个 1&lt;&lt;14</b>
     * ⇒ 独狼玩的时候一个字节都不多花（与改动前逐位相同）。
     */
    public static int bufferCapacityFor(int players) {
        final int need = Math.max(BUFFER_CAPACITY_FLOOR, players * BUFFER_CAPACITY_PER_PLAYER);
        return Integer.highestOneBit(need - 1) << 1;
    }

    // ================================================================== 逐位指纹（给断言用）
    /**
     * 一个位姿的<b>逐位指纹</b>：中心 3 个 double + 框偏航 1 个 float + 每块板 (x,y,z,rotY,rotX,scale)
     * 全部以<b>原始位模式</b>（{@code doubleToRawLongBits} / {@code floatToRawIntBits}）写成十六进制。
     *
     * <p>🔴 刻意<b>不做任何格式化精度截断</b>（不是 {@code %.6f}）：这条判据问的是"逐位相等"，
     * 格式化会把 1 ulp 的差别藏起来。<b>格式里也没有平台相关的 {@code Locale}</b>。
     */
    public static String bitSignature(double cx, double cy, double cz, float frameYawDeg,
                                      ShanhaiHoloMenuLayout.Board[] boards) {
        final StringBuilder sb = new StringBuilder(256);
        sb.append(Long.toHexString(Double.doubleToRawLongBits(cx))).append(',')
                .append(Long.toHexString(Double.doubleToRawLongBits(cy))).append(',')
                .append(Long.toHexString(Double.doubleToRawLongBits(cz))).append('|')
                .append(Integer.toHexString(Float.floatToRawIntBits(frameYawDeg))).append('|');
        if (boards != null) {
            for (int i = 0; i < boards.length; i++) {
                final ShanhaiHoloMenuLayout.Board b = boards[i];
                sb.append(i).append(':')
                        .append(Integer.toHexString(Float.floatToRawIntBits(b.x()))).append(',')
                        .append(Integer.toHexString(Float.floatToRawIntBits(b.y()))).append(',')
                        .append(Integer.toHexString(Float.floatToRawIntBits(b.z()))).append(',')
                        .append(Integer.toHexString(Float.floatToRawIntBits(b.rotY()))).append(',')
                        .append(Integer.toHexString(Float.floatToRawIntBits(b.rotX()))).append(',')
                        .append(Integer.toHexString(Float.floatToRawIntBits(b.scale()))).append(';');
            }
        }
        return sb.toString();
    }

    /** 上面那个指纹的"人看得懂"版本（只在日志里用，不参与判据）。 */
    public static String describe(double cx, double cy, double cz, float frameYawDeg) {
        return String.format(java.util.Locale.ROOT, "center=(%.6f,%.6f,%.6f) frameYaw=%.6f",
                cx, cy, cz, frameYawDeg);
    }

    /** 远程那一侧的"取数"—— 判据问的就是它（渲染器调的是同一个函数）。 */
    public static Landing landingForRemote(String layoutName, float distance,
                                           double eyeX, double eyeY, double eyeZ,
                                           float yRotO, float yRot, float partialTick) {
        return landing(layoutName, distance, eyeX, eyeY, eyeZ, yawDeg(yRotO, yRot, partialTick));
    }

    /**
     * 🔴 <b>只给负对照用</b>：把 {@link #yawDeg} 换成<b>线性插值</b>（= {@code Mth.lerp}，
     * 也正是 {@code getViewVector(pt)} 走的那条路）。
     *
     * <p>它<b>不在任何生产路径上</b>：存在的唯一理由是让"位姿逐位相等"这条断言有一条<b>活着的负对照</b>
     * —— 断言必须能因为"用了线性插值"而报红，否则那条断言恒绿、等于没写。
     */
    public static Landing landingForNegativeControl(String layoutName, float distance,
                                                    double eyeX, double eyeY, double eyeZ,
                                                    float yRotO, float yRot, float partialTick) {
        return landing(layoutName, distance, eyeX, eyeY, eyeZ, Mth.lerp(partialTick, yRotO, yRot));
    }
}
