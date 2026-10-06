package com.shanhai.client.holo;

import com.shanhai.ShanhaiMod;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/**
 * 山海重构 · 悬浮全息菜单的<b>客户端运行期状态</b>（开关 / 朝向档 / 排布档 / 跟随插值）。
 *
 * <h2>1. 这个类是纯客户端的，且【不被 common 侧引用】</h2>
 * 它带 {@link OnlyIn}{@code (Dist.CLIENT)}，只被同包的 {@link ShanhaiHoloMenuClient} /
 * {@link ShanhaiHoloMenuRenderer} 引用，而那两者又只挂在 {@code value = Dist.CLIENT} 的
 * {@code @Mod.EventBusSubscriber} 上 ⇒ <b>专用服务端不会加载本类</b>（本工程硬约束）。
 *
 * <h2>2. 🔴 锚点是【玩家】，不是【摄像机】—— 这一条决定了第三人称能不能看</h2>
 * 第一人称下相机 ≈ 玩家眼睛，两者等价；但<b>第三人称时相机会退到玩家身后约 4 格</b>：
 * 若把全息锚在相机前方 2.8 格，它就会落在玩家背后 / 玩家身体里 —— 第三人称直接废掉。
 * 锚在 {@code player.getEyePosition(partialTick)} ⇒ 它始终浮在<b>玩家</b>前方的世界里，
 * 第三人称下是"你 + 你面前那面全息板"，第一人称下是"你面前那面全息板"，两种都成立。
 *
 * <h2>3. 朝向与位移都走【指数平滑】，这是需求④的实现机制</h2>
 * 用户硬要求④：「转视角时它会偏出视野/能看到侧面 —— 它必须有透视。
 * <b>如果它永远钉在屏幕中心不动，那就做成了 HUD，是错的。</b>」
 * <p>平滑插值给出的正是这个：
 * <pre>
 *   你快速扭头 → 全息还在朝【旧方向】 → 它偏出屏幕中心、你看到它的【侧面】（近大远小成立）
 *              → 约 0.16 秒后它才转过来正对你
 *   你站定不动 → 目标角不再变 ⇒ 它稳稳停在正前方
 * </pre>
 * ⇒ 它不是 HUD：它有世界坐标、会被方块遮挡（深度测试）、转头时不受屏幕约束。
 *
 * <h2>4. 平滑公式（纯算术，离线可验）</h2>
 * <pre>
 *   α = 1 − exp(−Δt / τ)          τ = 0 ⇒ α = 1（瞬时）
 *   θ ← θ + wrapDegrees(θ_target − θ) · α      （角度走最短弧，不会绕远路）
 *   位置同理，但 x/y/z 各自独立做，因为它们是向量而不是角
 * </pre>
 * {@link #smoothAngle} / {@link #smoothPos} 两个静态方法就是它，不碰任何 Minecraft 对象，
 * 可以在离线小程序里直接跑。
 *
 * <h2>5. 为什么在【渲染帧】里推进而不是在 ClientTick（20Hz）里</h2>
 * 20 Hz 推进 ⇒ 两次 tick 之间目标不变 ⇒ 全息会以 20 Hz 一跳一跳地动（肉眼可见）。
 * 渲染帧推进 + 用 {@code System.nanoTime()} 自己算 Δt ⇒ 每帧都连续。
 * 代价是引入了"上一帧时刻"这一个可变字段，因此本类的方法<b>只在渲染线程调用</b>（单线程，无并发）。
 */
@OnlyIn(Dist.CLIENT)
public final class ShanhaiHoloMenuState {

    /**
     * 朝向档。<b>默认 {@link #FIXED}（世界固定）</b>。
     *
     * <p>🔴 2026-10-06（用户实测第 5 条：「全息屏跟随我的视角我根本没法选」）：默认档从
     * {@link #FOLLOW} 改成 {@link #FIXED}。理由不是观感，而是<b>离线量出来的可选中性</b>
     * （读数在 {@code temp/holo-verify/src/FacingProbe.java}，原始输出见 {@code probe-*.txt}）：
     * <pre>
     *   桶形两档（竖列 / 横排）的板是【叠在玩家正前方】的，而全息中心 = 眼睛 + 视线前方 distance
     *   ⇒ 玩家扭头时整叠板跟着视线一起转，【相对角度被锁死】。实测（只左右扭头、不抬头低头）：
     *     竖列 + FOLLOW/SNAP : 命中第 3 格（未启用）100.0% —— 永远只能指到那一块点不动的板
     *     横排 + FOLLOW/SNAP : 第 1/3/5 格【一块都指不到】（命中率 0.0%），只到得了第 2/4 格
     *     横排 + FIXED       : 五块全部可指到（1/2/3/4/5 = 3.2/0.4/2.9/0.4/3.2%）
     *   ⇒ 这就是用户报的那一条：跟着视角转 ⇒ 选不中。{@link #FIXED} 是唯一修得掉它的档。
     * </pre>
     * ⚠️ <b>环绕档（默认排布）下这三档【完全无效】</b>：{@link #advance} 在环绕档提前 return
     * （框偏航恒 0，与玩家朝向无关）。实测三档的 {@code frameYaw} 与 {@code pos} 逐字相同
     * ⇒ 本行的默认值对环绕档没有任何影响，改动它不会弄坏环绕档。
     * <p>用户要的「命令可以强制跟随视角」照旧成立：{@code /shanhai menu facing follow} 一个字没动。
     */
    public enum Facing {
        /** 平滑转向玩家；时间常数 {@link ShanhaiHoloMenuTuning#YAW_TAU_FOLLOW_SEC}。 */
        FOLLOW,
        /** 立即转向玩家（时间常数 0）—— <b>对照组</b>：最接近"贴在屏幕上的 HUD"的观感。 */
        SNAP,
        /** 🔴 <b>默认</b>：世界固定朝向：开投影那一刻朝向被冻结，之后扭头它不再跟着转（能用准星逐块指到）。 */
        FIXED
    }

    /**
     * 排布档。默认 {@link #RING}。
     *
     * <p>🔴 2026-10-05（用户实测 A6）把默认档从 {@code COLUMN} 改成 {@code RING}：
     * <pre>
     *   用户原话：「这个曲率是不是反了啊，那个 y 轴就是反了，x 和 z 轴围绕玩家的那种感觉吧
     *            （就是一个圆围绕着玩家），然后就不需要跟随玩家的头转动了，
     *             但是玩家位置移动还是需要跟随的，这样玩家就可以选择了。」
     * </pre>
     * 三档的现实语义见 {@link ShanhaiHoloMenuLayout} 的『环绕档』与『全息本体的落地位姿』两节。
     */
    public enum Layout {
        /** 🆕 <b>默认</b>：五块板沿<b>水平面</b>绕玩家一圈（圆心=玩家），板法线指向玩家；不看玩家朝向。 */
        RING,
        /** 旧档（甲案对照）：上下叠成柱，板向上下外倾；整块全息随玩家偏航转。 */
        COLUMN,
        /** 旧档（甲案对照）：左右排成横排，板向左右外倾；整块全息随玩家偏航转。 */
        ROW
    }

    private static boolean enabled;
    /** 🔴 默认 {@link Facing#FIXED}（理由与实测读数见 {@link Facing} 的类注释）。 */
    private static Facing facing = Facing.FIXED;
    private static Layout layout = Layout.RING;

    // ---- 🆕 2026-10-06：二级面板（设置 / 命令）----
    //
    //   用户点单：「点开配置设置的也是全息的面版」「全息输入框，可以执行cmd的命令」。
    //   🔴 面板状态【放在这里】而不是放在面板类里：它是"投影此刻长什么样"的一部分
    //      —— 关投影时必须跟着一起收（见 setEnabled(null) 那一行），
    //      而被射线指到哪一行则是每 tick 刷新的读数。
    /** 当前面板（{@link ShanhaiHoloMenuPanel#MODE_NONE} = 显示五块菜单板）。 */
    private static int panelMode = ShanhaiHoloMenuPanel.MODE_NONE;

    /** 被射线指到的那一行 / 那一格（{@code -1} = 没指到）；只影响高亮。 */
    private static int panelHoverRow = -1;
    private static int panelHoverCell = -1;

    /** 运行期可调的距离（格）；初值 = {@link ShanhaiHoloMenuTuning#DISTANCE_BLOCKS}。 */
    private static float distance = ShanhaiHoloMenuTuning.DISTANCE_BLOCKS;

    // ---- 🆕 2026-10-06（用户实测第 ③ 条）：面板（设置 / 命令）的框偏航 ----
    //
    //   用户原话（逐字）：「你这个设置也是完全没改，怎么完全是跟随玩家的头转的，我完全没法选啊」
    //
    //   🔴 病根不在环绕档那五块板（它们的框偏航恒为 0，早就钉在世界方位上了 ——
    //      离线读数见 FacingProbe：ring 三档逐字相同、五块板都能指到），
    //      **而在【面板】**：修复前渲染器与输入层都用【实时玩家偏航】现算面板的位姿
    //      ⇒ 面板永远贴在你脸正前方、准星永远落在它的【横向正中】（格 2）
    //      ⇒ 最左/最右那两格（例如「平滑跟随」「世界固定」）永远指不到 = "完全没法选"；
    //      而「世界固定」这个按钮本身也在格 3 ⇒ 连点都点不到 ⇒ "完全没改"。
    //
    //   🔴 修法（只改"框的朝向"，不动任何几何）：面板与菜单共用【同一套朝向档】语义 ——
    //        FOLLOW 平滑跟随（时间常数 YAW_TAU_FOLLOW_SEC）
    //        SNAP   立即跟随（= 修复前的行为，保留成对照组）
    //        FIXED  🔴默认：**【打开面板那一刻】钉在世界方位上**，之后玩家扭头它不动
    //               ⇒ 准星会横着扫过整块面板 ⇒ 每一格都能指到（离线探针逐格验过）
    //   位置仍然【跟着玩家走】（锚点 = 眼睛 + 钉住那一刻的视线方向 × distance）——
    //   这正是用户 2026-10-05 定环绕档时说的「玩家位置移动还是需要跟随的」。
    /** 面板框用的偏航（度，原版口径：0 = +Z）。{@link Facing#FIXED} 下它是"打开那一刻"的值。 */
    private static float panelYawDeg;

    /** {@code false} = 下一次 {@link #advance} 要把面板偏航重新钉在当前朝向上（开面板 / 刚点「世界固定」）。 */
    private static boolean panelYawPrimed;

    private static double px;
    private static double py;
    private static double pz;
    private static float yawDeg;

    /** 是否已经"落到过正确位置"。没有它，刚打开时全息会从 (0,0,0) 世界原点飞过来。 */
    private static boolean primed;

    /** 上一帧的纳秒时刻（Δ 用它算）。0 = 还没有上一帧。 */
    private static long lastNanos;

    private ShanhaiHoloMenuState() {}

    // ------------------------------------------------------------------ 开关 / 档位

    public static boolean enabled() {
        return enabled;
    }

    /** 开关。<b>打开的那一刻不做插值</b>（直接落到目标位姿），否则会看到它从远处飞过来。 */
    public static void setEnabled(boolean value) {
        if (value && !enabled) {
            primed = false;
            lastNanos = 0L;
        }
        if (!value) {
            // 🔴 关投影 ⇒ 面板一起收（面板是投影的一部分，它没有独立的开合）。
            //    这一条同时兜住"模块被拿走自动关"与"/shanhai menu off"两条路。
            closePanel();
        }
        enabled = value;
    }

    // ------------------------------------------------------------------ 🆕 面板

    /** 当前面板模式（{@link ShanhaiHoloMenuPanel#MODE_NONE} = 五块菜单板）。 */
    public static int panelMode() {
        return enabled ? panelMode : ShanhaiHoloMenuPanel.MODE_NONE;
    }

    /** 打开某个面板（{@code MODE_NONE} ⇒ 回到五块菜单板）。<b>不关投影</b>。 */
    public static void setPanelMode(int mode) {
        panelMode = mode;
        panelHoverRow = -1;
        panelHoverCell = -1;
        if (mode != ShanhaiHoloMenuPanel.MODE_NONE) {
            // 🔴 开面板那一拍：把面板的框偏航重新钉一次（见 panelYawDeg 的注释）。
            //    下一帧的 advance() 会用【那一刻】的玩家偏航落位 —— 于是"打开时正对着你、
            //    之后扭头它就不动了"。
            panelYawPrimed = false;
        }
        if (mode == ShanhaiHoloMenuPanel.MODE_NONE) {
            ShanhaiHoloMenuPanel.closePanel();
        }
    }

    /** 关掉面板（回到五块菜单板）—— 「返回」那一格与关投影都走它。 */
    public static void closePanel() {
        setPanelMode(ShanhaiHoloMenuPanel.MODE_NONE);
    }

    public static boolean panelOpen() {
        return panelMode() != ShanhaiHoloMenuPanel.MODE_NONE;
    }

    public static int panelHoverRow() {
        return enabled ? panelHoverRow : -1;
    }

    public static int panelHoverCell() {
        return enabled ? panelHoverCell : -1;
    }

    /** 每 tick 由输入层刷新（射线指到哪一行）；只影响高亮，不影响任何判定。 */
    public static void setPanelHover(int row, int cell) {
        panelHoverRow = row;
        panelHoverCell = cell;
    }

    /** 面板一行的读数（grep 用）。 */
    public static String panelDescribe() {
        return "panel=" + ShanhaiHoloMenuPanel.modeName(panelMode())
                + " hover_row=" + panelHoverRow + " hover_cell=" + panelHoverCell;
    }

    /** @return 切换之后的开关状态 */
    public static boolean toggle() {
        setEnabled(!enabled);
        return enabled;
    }

    public static Facing facing() {
        return facing;
    }

    /**
     * 换朝向档。<b>切到 {@link Facing#FIXED} 的那一刻把当前朝向冻住</b>
     * —— 这样"固定"的含义是"就停在这个方向"，而不是"突然跳到某个世界坐标角度"。
     *
     * <p>🆕 2026-10-06：这一条现在对<b>面板</b>是真的（{@link #panelYawDeg} 会被重新钉一次）；
     * 对菜单的旧两档（row/column）照旧是"从此不再更新"，对环绕档则完全无效（框偏航恒 0）。
     */
    public static void setFacing(Facing next) {
        facing = next == null ? Facing.FOLLOW : next;
        if (facing == Facing.FIXED) {
            // 点了「世界固定」⇒ 下一帧把面板钉在"点这一下的那一刻"的朝向上（看得见的手感）
            panelYawPrimed = false;
        }
    }

    public static Layout layout() {
        return layout;
    }

    public static void setLayout(Layout next) {
        layout = next == null ? Layout.RING : next;
    }

    public static void setLayoutRow(boolean row) {
        setLayout(row ? Layout.ROW : Layout.COLUMN);
    }

    public static boolean isRow() {
        return layout == Layout.ROW;
    }

    /** 环绕档（默认）—— 渲染器与 dump 都读它。 */
    public static boolean isRing() {
        return layout == Layout.RING;
    }

    /** 排布档的名字（{@code ring}/{@code row}/{@code column}）—— 与几何核同一套口径。 */
    public static String layoutName() {
        return layout == Layout.RING ? "ring" : (layout == Layout.ROW ? "row" : "column");
    }

    /**
     * 环绕档的<b>有效半径</b>（格）。
     * <p>环绕档下 {@code distance} 的含义与旧两档不同：旧档是"全息浮在玩家前方多远"，
     * 环绕档是"这一圈离玩家多远"（见 {@link ShanhaiHoloMenuTuning#RING_MIN_RADIUS_BLOCKS} 的下限）。
     */
    public static float ringRadius() {
        return ShanhaiHoloMenuLayout.ringRadius(distance);
    }

    public static float distance() {
        return distance;
    }

    /** 夹到 [0.8, 8.0] 格：太近会糊脸、太远看不清字。 */
    public static float setDistance(float next) {
        distance = Mth.clamp(next, 0.8f, 8.0f);
        return distance;
    }

    public static Vec3 position() {
        return new Vec3(px, py, pz);
    }

    /**
     * <b>框偏航</b>（度）—— 渲染器拿它做 {@code mulPose(Axis.YP)}。
     *
     * <p>⚠️ 它<b>不是</b>玩家偏航：环绕档恒为 0，旧两档是 {@code 180 − 玩家偏航}（并受 {@link Facing} 插值）。
     * 访问器故意不叫 {@code yaw()}，就是为了让"这两个不是一回事"在调用点看得见。
     */
    public static float frameYaw() {
        return yawDeg;
    }

    // ------------------------------------------------------------------ 🆕 面板的落地位姿

    /**
     * 🆕 2026-10-06（用户实测第 ③ 条）<b>面板（设置 / 命令）的框偏航</b>（度）。
     *
     * <p>与 {@link #frameYaw()} <b>不是一个东西</b>：那个是五块菜单板的框偏航（环绕档恒 0），
     * 这个是面板那一列的框偏航（= {@code 180 − 面板偏航}）。两个量分开存，是因为它们
     * <b>各自朝向档的语义不同</b>：菜单的环绕档恒不跟头，而面板要能"世界固定"。
     */
    public static float panelFrameYaw() {
        return ShanhaiHoloMenuPanel.frameYaw(panelYawDeg);
    }

    /**
     * 🆕 面板那一列的<b>锚点</b>（世界坐标）= 眼睛 + 面板偏航对应的视线方向 × 距离。
     *
     * <p>🔴 它是"面板画在哪"与"射线打在哪"的<b>唯一口径</b>：{@link ShanhaiHoloMenuRenderer}
     * （画）与 {@code ShanhaiHoloMenuInput}（指着谁 / 点了哪一格）三处都调这一个函数。
     * 修复前这三处各写了一遍 {@code lp.getYRot()}，于是"面板画的位置"与"你点的位置"
     * 会随着朝向档的改动而漂开 —— 本轮收口成一处。
     */
    public static double[] panelCenter(Vec3 eye) {
        return ShanhaiHoloMenuPanel.centerOf(eye.x, eye.y, eye.z, panelYawDeg, distance);
    }

    /** 面板框现在的偏航角（度）；{@link #panelCenter} 用的是同一个量（离线探针读它）。 */
    public static float panelYawDeg() {
        return panelYawDeg;
    }

    /** 供离线自检使用：重置全部状态（不会在正常玩法路径上被调用）。 */
    public static void resetForTest() {
        enabled = false;
        facing = Facing.FIXED;
        layout = Layout.RING;
        distance = ShanhaiHoloMenuTuning.DISTANCE_BLOCKS;
        px = py = pz = 0.0;
        yawDeg = 0.0f;
        primed = false;
        lastNanos = 0L;
        // 🆕 面板框偏航（第 ③ 条）：一起复位，否则"复位之后第一次开面板"会用上一轮的方位
        panelYawDeg = 0.0f;
        panelYawPrimed = false;
        // 🆕 面板状态必须一起清：留在 MODE_SETTINGS 上会让"重置之后第一帧"画成一张设置面板
        panelMode = ShanhaiHoloMenuPanel.MODE_NONE;
        panelHoverRow = -1;
        panelHoverCell = -1;
        ShanhaiHoloMenuPanel.closePanel();
    }

    // ------------------------------------------------------------------ 每帧推进

    /**
     * 按当前帧的玩家/相机状态推进一次平滑，并更新全息的世界位姿。
     *
     * <p>调用方 = {@link ShanhaiHoloMenuRenderer}（渲染线程，每帧一次）。
     *
     * @param player    本地玩家；{@code null} 时直接返回（不改任何状态）
     * @param partialTick 当前帧的插值系数（来自 {@code RenderLevelStageEvent#getPartialTick}），
     *                    用它拿"帧级连续"的眼睛位置与视线偏航 —— 否则目标量本身会以 20 Hz 台阶跳
     */
    public static void advance(LocalPlayer player, float partialTick) {
        if (player == null) {
            return;
        }
        // ---- 目标位姿 ----
        // 偏航：rotLerp 是原版的角度插值（走最短弧）。yRotO 是上一 tick 的偏航，两者都是 public。
        final float targetYaw = Mth.rotLerp(partialTick, player.yRotO, player.getYRot());
        final Vec3 eye = player.getEyePosition(partialTick);
        // 🔴 中心与框偏航都走【几何核的同一个函数】（ShanhaiHoloMenuLayout#centerOf / #frameYawOf）
        //    —— 环绕档里"圆心 = 玩家本人、框偏航恒 0"这条语义只此一处，dump 出来的读数
        //    与渲染出来的位姿因此不可能漂。
        final double[] target = ShanhaiHoloMenuLayout.centerOf(
                layoutName(), eye.x, eye.y, eye.z, targetYaw, distance);
        final double tx = target[0];
        final double ty = target[1];
        final double tz = target[2];
        final float frameYawTarget = ShanhaiHoloMenuLayout.frameYawOf(layoutName(), targetYaw);

        // ---- 帧间隔 ----
        final long now = System.nanoTime();
        float dt = lastNanos == 0L ? 0.0f : (now - lastNanos) / 1.0e9f;
        lastNanos = now;
        if (dt < 0.0f) {
            dt = 0.0f;
        }
        if (dt > ShanhaiHoloMenuTuning.MAX_FRAME_DT_SEC) {
            dt = ShanhaiHoloMenuTuning.MAX_FRAME_DT_SEC;
        }

        // 🔴 2026-10-06（用户实测第 ③ 条）：面板的框偏航 —— 必须跑在下面那个 `if (!primed) return;`
        //    之前，否则"刚开投影的第一帧"面板偏航不会落位。
        //    口径与菜单的朝向档同一套（见 panelYawDeg 的注释）。
        if (panelOpen()) {
            final float live = Mth.wrapDegrees(targetYaw);
            if (!panelYawPrimed) {
                panelYawDeg = live;                 // 打开面板 / 刚点「世界固定」那一拍：当场钉住
                panelYawPrimed = true;
            } else {
                switch (facing) {
                    case FOLLOW -> panelYawDeg = smoothAngle(panelYawDeg, live,
                            ShanhaiHoloMenuTuning.YAW_TAU_FOLLOW_SEC, dt);
                    case SNAP -> panelYawDeg = live;
                    case FIXED -> {
                        // 刻意什么都不做：钉在"打开/点固定"那一刻 —— 玩家扭头时准星才能扫过整块面板
                    }
                }
            }
        }

        if (!primed) {
            // 第一帧：直接落位，绝不插值（否则从世界原点飞过来）
            px = tx;
            py = ty;
            pz = tz;
            yawDeg = ShanhaiHoloMenuLayout.frameYawOf(layoutName(), Mth.wrapDegrees(targetYaw));
            primed = true;
            return;
        }

        px = smoothPos(px, tx, ShanhaiHoloMenuTuning.POSITION_TAU_SEC, dt);
        py = smoothPos(py, ty, ShanhaiHoloMenuTuning.POSITION_TAU_SEC, dt);
        pz = smoothPos(pz, tz, ShanhaiHoloMenuTuning.POSITION_TAU_SEC, dt);

        // 🔴 环绕档：框偏航【恒为 0】，与 Facing 档无关 —— 玩家扭头时它一动不动
        //    （这正是用户要的"不需要跟随玩家的头转动"；他能靠扭头去看环上另外几块板）。
        if (isRing()) {
            yawDeg = frameYawTarget;
            return;
        }

        switch (facing) {
            case FOLLOW -> yawDeg = smoothAngle(yawDeg, frameYawTarget,
                    ShanhaiHoloMenuTuning.YAW_TAU_FOLLOW_SEC, dt);
            case SNAP -> yawDeg = Mth.wrapDegrees(frameYawTarget);
            case FIXED -> {
                // 刻意什么都不做：朝向冻结在切换那一刻（或打开那一刻）的方向。
            }
        }
    }

    // ------------------------------------------------------------------ 纯算术核

    /**
     * <b>指数平滑</b>一个标量（位置分量用）。
     *
     * @param tau 时间常数（秒）。{@code ≤ 0} ⇒ 瞬时贴合；{@code Δt ≤ 0} ⇒ 原样返回
     * @return 逼近目标之后的值（永远不越过目标：{@code α ∈ (0,1]}）
     */
    public static double smoothPos(double current, double target, float tau, float dt) {
        if (dt <= 0.0f) {
            return current;
        }
        if (tau <= 0.0f) {
            return target;
        }
        final double alpha = 1.0 - Math.exp(-dt / tau);
        return current + (target - current) * alpha;
    }

    /**
     * <b>指数平滑</b>一个角度（度），<b>走最短弧</b>。
     *
     * <p>反例（为什么不能直接对两个数做线性插值）：当前 {@code 350°}、目标 {@code 10°}，
     * 直接插值会走 350→10 的<b>长弧</b>（−340° 那条），全息会猛地倒转一大圈。
     * {@code wrapDegrees(target − current) = +20°} ⇒ 走 20° 那条短弧 ✓
     */
    public static float smoothAngle(float current, float target, float tau, float dt) {
        if (dt <= 0.0f) {
            return current;
        }
        final float delta = Mth.wrapDegrees(target - current);
        if (tau <= 0.0f) {
            return Mth.wrapDegrees(current + delta);
        }
        final float alpha = (float) (1.0 - Math.exp(-dt / tau));
        return Mth.wrapDegrees(current + delta * alpha);
    }

    /**
     * <b>离线自检</b>（{@code /shanhai menu selftest} 调它）。返回 {@code null} = 全过。
     *
     * <p>11 条判据，逐条都用"有唯一正确答案"的输入，其中 <b>2 条是负对照</b>
     * （故意构造会失败的那种输入）—— 没有负对照，"全绿"可能只是判据本身从来不响
     * （本工程血规矩：「凡我自己写的检查脚本报出的结果，采信前必须先证明脚本自己是对的」）。
     * <pre>
     *   ① τ ≤ 0 ⇒ 瞬时贴合
     *   ② Δt ≤ 0 ⇒ 原样不动
     *   ③ 一个 τ 之后恰好走完 1−1/e = 63.2%
     *   ④ 角度走最短弧（350° → 10° 走 +20°，不倒转 340°）
     *   ⑤ 180° 反向仍落在 ±180
     *   ⑥ 弧面半径自洽（五块板到弧圆心的距离 == 弧半径；两档各一遍）
     *   ⑦ 【负对照】把校验半径挪 0.5 ⇒ ⑥ 必须报出偏差（实测 0.0944，正对照是 0.0）
     *   ⑧ 横排左右阅读顺序：index 0 必须在左（x &lt; 0）—— 抓的是"漏一个负号就把菜单画反"这类缺陷
     *   ⑨ 列排上下顺序：index 0 必须在上（y &gt; 0）
     *   ⑩ 中间那块必须正对玩家（rotX = rotY = z = 0）
     *   ⑪ 【负对照】两端那两块必须真的向外倾（|rotX| ≥ 1°）—— 否则 ⑩ 的"正对"是没意义的
     * </pre>
     */
    public static String selfTest() {
        final StringBuilder bad = new StringBuilder();
        // ① τ ≤ 0 ⇒ 瞬时
        if (smoothPos(0.0, 10.0, 0.0f, 0.1f) != 10.0) {
            bad.append("tau<=0 未瞬时;");
        }
        // ② Δt ≤ 0 ⇒ 原样（不许动）
        if (smoothPos(3.0, 10.0, 0.1f, 0.0f) != 3.0) {
            bad.append("dt<=0 动了;");
        }
        // ③ 一个 τ 之后应当走完约 1−1/e = 63.2%（容差 1e-6）
        final double one = smoothPos(0.0, 1.0, 0.25f, 0.25f);
        if (Math.abs(one - (1.0 - Math.exp(-1.0))) > 1e-6) {
            bad.append("tau 一步位移错=").append(one).append(';');
        }
        // ④ 角度走最短弧：350 → 10 应当是【加】20 的方向，不是减 340
        final float ang = smoothAngle(350.0f, 10.0f, 0.0f, 1.0f);
        if (Math.abs(Mth.wrapDegrees(ang - 10.0f)) > 1e-4f) {
            bad.append("最短弧错=").append(ang).append(';');
        }
        // ⑤ 负对照：把目标换成 180 度外，最短弧应当【两边都合法】= ±180，实现必须仍落在 180 上
        final float opposite = smoothAngle(0.0f, 180.0f, 0.0f, 1.0f);
        if (Math.abs(Math.abs(Mth.wrapDegrees(opposite)) - 180.0f) > 1e-3f) {
            bad.append("180 反向错=").append(opposite).append(';');
        }
        // ⑥ 弧面几何：五块板到弧圆心的距离必须恒等于弧半径（两档各验一遍）
        final float arcErr = ShanhaiHoloMenuLayout.arcError();
        if (!(arcErr < 1.0e-4f)) {
            bad.append("弧面半径偏差=").append(arcErr).append(';');
        }
        // ⑦ 负对照：故意让"解算半径"与"校验半径"差 0.5 ⇒ 判据必须报出一个【远大于】⑥ 的偏差
        //    （证明⑥不是恒绿的；见 ShanhaiHoloMenuLayout#arcError(float,float) 的注释）
        //    🔴 阈值是【实测出来的】，不是拍的：2026-10-04 离线跑出来
        //       arc_error_self = 0.0 ／ arc_error_negative_ctrl = 0.09437609。
        //       我第一版写的是"应当 ≈0.5"（想当然），离线一跑当场证伪 ——
        //       把假定半径挪 0.5，测出的半径偏差并不是 0.5（弧上的点并不随假定圆心线性移动）。
        //       现值 = 0.0944 是正对照 0.0 的【无穷倍】，判别力足够，所以阈值取 0.05。
        final float bogusErr = ShanhaiHoloMenuLayout.arcError(
                ShanhaiHoloMenuTuning.ARC_RADIUS, ShanhaiHoloMenuTuning.ARC_RADIUS + 0.5f);
        if (!(bogusErr > 0.05f)) {
            bad.append("负对照不响=").append(bogusErr).append(';');
        }
        // ⑧ 横排【左右阅读顺序】：index 0（配方查询）必须落在玩家的左手边 = 本体坐标系 x < 0。
        //    🔴 这是 2026-10-04 离线读数当场抓出来的真实缺陷的回归防线：
        //       第一版横排漏了一个负号，dump 打出来 index=0 的 x 是 +0.8398 ——
        //       而本坐标系 +X = 玩家右手边 ⇒ 五块板的阅读顺序整个左右颠倒。
        //       它【不会崩、不会报错】，只会让人看到一份反着的菜单 —— 所以必须有这条断言。
        final float rowX0 = ShanhaiHoloMenuLayout.of(0, true).x();
        final float rowX4 = ShanhaiHoloMenuLayout.of(4, true).x();
        if (!(rowX0 < -0.1f)) {
            bad.append("横排 index0 不在左边 x0=").append(rowX0).append(';');
        }
        if (!(rowX4 > 0.1f)) {
            bad.append("横排 index4 不在右边 x4=").append(rowX4).append(';');
        }
        if (!(rowX0 < rowX4)) {
            bad.append("横排左右顺序反了;");
        }
        // ⑨ 列排【上下顺序】：index 0（配方查询）必须在最上面 = y > 0
        final float colY0 = ShanhaiHoloMenuLayout.of(0, false).y();
        final float colY4 = ShanhaiHoloMenuLayout.of(4, false).y();
        if (!(colY0 > 0.1f)) {
            bad.append("列排 index0 不在上面 y0=").append(colY0).append(';');
        }
        if (!(colY4 < -0.1f)) {
            bad.append("列排 index4 不在下面 y4=").append(colY4).append(';');
        }
        // ⑩ 中间那块必须【正对玩家】：自转角为 0 且 z 为 0
        final ShanhaiHoloMenuLayout.Board mid = ShanhaiHoloMenuLayout.of(2, false);
        if (Math.abs(mid.rotX()) > 1.0e-4f || Math.abs(mid.rotY()) > 1.0e-4f || Math.abs(mid.z()) > 1.0e-4f) {
            bad.append("中间板没正对 rotX=").append(mid.rotX()).append(" rotY=").append(mid.rotY())
                    .append(" z=").append(mid.z()).append(';');
        }
        // ⑪ 负对照（针对 ⑩）：两端那两块【必须】有倾角 —— 否则"正对"就是从没生效过
        final ShanhaiHoloMenuLayout.Board top = ShanhaiHoloMenuLayout.of(0, false);
        if (Math.abs(top.rotX()) < 1.0f) {
            bad.append("两端板没向外倾 rotX=").append(top.rotX()).append(';');
        }

        // ============================================================= 环绕档（A6 / 2026-10-05）
        // ⑫ 五块板到【玩家】的距离必须都等于环绕半径（半径下限夹具：1.5 是故意压到下限以下的）
        final float rMin = ShanhaiHoloMenuTuning.RING_MIN_RADIUS_BLOCKS;
        final float rEff = ShanhaiHoloMenuLayout.ringRadius(1.5f);
        if (!(rEff >= rMin - 1.0e-6f)) {
            bad.append("环绕半径没被夹到下限 r=").append(rEff).append(" min=").append(rMin).append(';');
        }
        float worstR = 0.0f;
        final ShanhaiHoloMenuLayout.Board[] ring = ShanhaiHoloMenuLayout.ringAll(rEff);
        for (ShanhaiHoloMenuLayout.Board b : ring) {
            final double d = Math.sqrt((double) b.x() * b.x() + (double) b.z() * b.z());
            worstR = Math.max(worstR, (float) Math.abs(d - rEff));
        }
        if (!(worstR < 1.0e-4f)) {
            bad.append("环绕半径偏差=").append(worstR).append(';');
        }
        // ⑬ 每块板的法线必须【指向圆心（玩家）】= 相邻关系：法线 ∝ −(板心 − 玩家)
        float worstDot = 1.0f;
        for (ShanhaiHoloMenuLayout.Board b : ring) {
            final float yaw = ShanhaiHoloMenuLayout.ringBoardWorldYaw(b.index());
            final double yawRad = Math.toRadians(yaw);
            final double nx = -Math.sin(yawRad);
            final double nz = Math.cos(yawRad);
            final double cx = -b.x();
            final double cz = -b.z();
            final double len = Math.sqrt(cx * cx + cz * cz);
            final double dot = (nx * cx + nz * cz) / len;
            worstDot = Math.min(worstDot, (float) dot);
        }
        if (!(worstDot > 0.999f)) {
            bad.append("环绕板法线没指向玩家 dot=").append(worstDot).append(';');
        }
        // ⑭ 【负对照】相邻两块的弦长必须 ≥ 一块板的宽度，否则板会互相插进去
        final float chord = 2.0f * rEff * (float) Math.sin(Math.toRadians(ShanhaiHoloMenuTuning.RING_STEP_DEG * 0.5f));
        if (!(chord >= 2.0f * ShanhaiHoloMenuTuning.BOARD_HALF_WIDTH_BLOCKS - 1.0e-4f)) {
            bad.append("环绕档板会互相插进去 chord=").append(chord).append(';');
        }
        // ⑮🔴 两条【用户点单的】机器判据（都在几何核里，dump 的 JSON 打的是同一个函数）：
        //     转头 ⇒ 板的世界偏航与坐标都不变；走一步 ⇒ 每块板整块平移 1 格。
        //     负对照：旧档 row/column 的 yaw_invariance 必须是 false（否则这条判据是恒绿的）。
        if (!ShanhaiHoloMenuLayout.poseSignature("ring", 10.0, 64.0, -3.0, 0.0f, distance)
                .equals(ShanhaiHoloMenuLayout.poseSignature("ring", 10.0, 64.0, -3.0, 37.0f, distance))) {
            bad.append("环绕档跟着头转了;");
        }
        if (!ShanhaiHoloMenuLayout.posFollowOk("ring", 10.0, 64.0, -3.0, 123.0f, distance)) {
            bad.append("环绕档没跟着人走;");
        }
        for (String legacy : new String[]{"row", "column"}) {
            if (ShanhaiHoloMenuLayout.poseSignature(legacy, 10.0, 64.0, -3.0, 0.0f, distance)
                    .equals(ShanhaiHoloMenuLayout.poseSignature(legacy, 10.0, 64.0, -3.0, 37.0f, distance))) {
                bad.append("负对照没响(").append(legacy).append(" 也转头不变);");
            }
        }
        return bad.length() == 0 ? null : bad.toString();
    }

    /** 一行机器可判的读数（日志与聊天都发这一行）。 */
    public static String describe() {
        return "enabled=" + enabled
                + " facing=" + facing
                + " layout=" + layoutName()
                + " distance=" + distance
                + (isRing() ? " ring_radius=" + ringRadius() : "")
                + " yaw=" + yawDeg
                + " pos=" + String.format(java.util.Locale.ROOT, "%.3f/%.3f/%.3f", px, py, pz);
    }

    /** 自检 + 读数打进日志（机器可判；无头专服看不到，但客户端日志有）。 */
    public static void logSelfTest() {
        final String bad = selfTest();
        if (bad == null) {
            ShanhaiMod.LOGGER.info("[SHANHAI-HOLO] selftest ok cases=17 (含 4 条负对照；"
                    + "环绕档 yaw_invariance / pos_follow 都在内)");
        } else {
            ShanhaiMod.LOGGER.error("[SHANHAI-HOLO] selftest FAILED: " + bad);
        }
    }
}
