package com.shanhai.add.client.hud;

import com.shanhai.add.time.anchor.ShanhaiAnchorBarFrame;

/**
 * 山海重构 · 锚点条的<b>客户端快照缓存</b>（🔴 纯逻辑核：零 Minecraft 依赖，便于离线判据）。
 *
 * <h2>1. 它只做三件事</h2>
 * <pre>
 *   apply(frame)  网络线程：收到 S→C 消息就整帧覆盖（volatile 写，不加锁）
 *   clear()       退出世界/断线：清成"什么都没有"
 *   stateOf(...)  渲染线程每帧调一次：把帧算成 {@link ShanhaiAnchorBarState}
 * </pre>
 *
 * <h2>2. 🔴 为什么整帧覆盖，而不是逐字段合并</h2>
 * 一条帧就是服务端那一刻的<b>完整</b>状态。合并语义（"这个字段没变就留着"）在丢包/乱序时
 * 会拼出一个"服务端从来没存在过"的组合（新游标 + 旧窗口）⇒ 条的分母与分子来自两次不同的倒流。
 * SOC（S2C 单方向、同一条连接）上乱序不会发生，但"重连时上一条是旧世界的"完全会 —— 见 §3。
 *
 * <h2>3. 🔴 退出世界必须清（真机问题，判据 ④）</h2>
 * 不清的话，回到标题界面/换存档时缓存里还留着上一个世界的帧：
 * <pre>
 *   标题界面：mc.level == null，但 SOURCE 照样会被 HUD 取到 ⇒ 屏幕上挂着一条 60 秒的条
 *   换存档  ：新世界的服务端这一局没设过锚点 ⇒ 一条帧都不会发 ⇒ 那条旧帧<b>永远</b>留在屏幕上
 * </pre>
 * 两道闸，缺一不可：
 * <ol>
 *   <li>{@link #stateOf(boolean)} 的 {@code levelPresent == false} ⇒ 直接 {@code NONE}（标题界面这一帧就不画）；</li>
 *   <li>{@link #clear()} 由 {@code ClientPlayerNetworkEvent.LoggingOut} 调（缓存本身也清空，
 *       否则进新世界的那一刻又会把旧帧翻出来）。</li>
 * </ol>
 * 判据 ④ 的负对照就打在第 ① 条上：把那个 {@code !levelPresent} 判断去掉 ⇒
 * "标题界面必须 visible=false" 那一行必须红。
 *
 * <h2>4. 线程</h2>
 * 写只发生在网络线程（{@code volatile} 赋值），读只发生在渲染线程。
 * 帧是不可变 record ⇒ 读侧拿到的永远是一个自洽的整体，不需要复制、不需要锁。
 */
public final class ShanhaiAnchorBarCache {

    /** 最近收到的帧；{@code null} = 还没收到过 / 已经被清掉 / 不在世界里。 */
    private static volatile ShanhaiAnchorBarFrame latest;

    /** 累计收到多少帧（日志/判据用；只增不减）。 */
    private static volatile int received;

    /** 累计清空多少次（退出世界那一路；判据 ④ 用）。 */
    private static volatile int cleared;

    private ShanhaiAnchorBarCache() {
    }

    // ------------------------------------------------------------------ 写

    /** 网络线程：整帧覆盖。{@code null} 等同于 {@link #clear()}（坏包不许把旧帧留在屏上）。 */
    public static void apply(ShanhaiAnchorBarFrame frame) {
        latest = frame;
        if (frame != null) {
            ++received;
        }
    }

    /** 退出世界/断线：把缓存清成"没有"。 */
    public static void clear() {
        latest = null;
        ++cleared;
    }

    // ------------------------------------------------------------------ 读

    /** 当前缓存的帧（{@code null} = 没有）。只读，给判据/日志用。 */
    public static ShanhaiAnchorBarFrame latest() {
        return latest;
    }

    public static boolean isEmpty() {
        return latest == null;
    }

    public static int receivedCount() {
        return received;
    }

    public static int clearedCount() {
        return cleared;
    }

    /**
     * 渲染线程每帧调一次：把缓存算成 HUD 要的那条状态。
     *
     * @param levelPresent 客户端此刻<b>在不在地图里</b>（{@code Minecraft.getInstance().level != null}）。
     *                     为 {@code false}（标题界面/加载中/断线）⇒ 一律 {@link ShanhaiAnchorBarState#none()}。
     *                     🔴 判据 ④ 的负对照点：删掉这个判断，标题界面就会挂着上一个世界的条。
     * @return 永远非 {@code null}（没有帧 / 不在世界里 ⇒ {@code none()}）
     */
    public static ShanhaiAnchorBarState stateOf(boolean levelPresent) {
        final ShanhaiAnchorBarFrame frame = latest;      // volatile 读一次，避免读到半截
        if (!levelPresent || frame == null) {
            return ShanhaiAnchorBarState.none();
        }
        return ShanhaiAnchorBarState.compute(true,
                frame.anchorTick(),
                frame.nowTick(),
                frame.windowTicks(),
                frame.replayActive(),
                frame.replayFromTick(),
                frame.replayToTick(),
                frame.replayCursorTick());
    }

    /** 一行诊断（日志用；不参与判定）。 */
    public static String summary() {
        final ShanhaiAnchorBarFrame frame = latest;
        return "received=" + received + " cleared=" + cleared
                + " cached=" + (frame == null ? "none" : frame.toLine());
    }
}
