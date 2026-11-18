package com.dishanhai.gt_shanhai.common.time.replay;

import java.lang.reflect.Field;

/**
 * 山海重构 · <b>倒流收尾后短时间内「强制走绝对包」的一次性开关</b>（🔴 零 Minecraft 依赖，别加 import）。
 *
 * <h2>1. 它要修的是哪一条（取证见 {@code temp/replay-finalpos/取证-记账.md}）</h2>
 * {@code ServerEntity#sendChanges}（SRG {@code m_8533_}）里那条"已发送位置记账"
 * {@code positionCodec.base}（字段 SRG {@code f_214995_}）<b>只在真的发出位置包之后</b>才前移
 * （字节码 <b>偏移 1019</b> 的 {@code if (bl4) positionCodec.setBase(vec3)}）。
 * 而冻结期我们的替代 tick 调的是 {@code getChunkSource().tick(haveTime, <b>false</b>)} ⇒
 * {@code tickChunks()} 被跳过 ⇒ {@code sendChanges} 一次都不跑（取证 F9）⇒
 * <b>base 停在"解冻前"的坐标</b>。
 *
 * <p>同时，我们收尾补发的 {@code ClientboundTeleportEntityPacket} 会被客户端
 * {@code handleTeleportEntity → syncPacketPositionCodec} 把<b>客户端</b>的 base 按到回放末坐标
 * （取证 F7）—— 服务端那个 base 一个字节都没动（取证 F8）。
 * ⇒ 解冻后服务端第一发<b>相对</b>包按 {@code 客户端base + (P末 − P解冻前)} 解码，
 * 客户端落到 {@code 2·P末 − P解冻前}，<b>等于多叠一次倒流位移</b>（观测 1.7~2.4 格/次 × 三步插值
 * ≈ 总 5.1~7.2 格；单轴 &lt; 8 格 ⇒ 偏偏走相对包 ⇒ 所以只跳一部分）。
 *
 * <h2>2. 修法（方案 A）：把这 250 ms 里的 {@code teleportDelay} 抬到 401</h2>
 * 判定链（取证 F4）：<pre>
 *   偏移 587-646  任一轴编码越界（= 单轴 ≥ 8 格）
 *   偏移 648-681  ‖ teleportDelay &gt; 400 ‖ wasRiding ‖ wasOnGround != entity.onGround()
 *        ⇒ 命中 838 的【绝对分支】new ClientboundTeleportEntityPacket(entity) ⇒ 客户端 base 对齐
 *        ⇒ 命中 1019 的 positionCodec.setBase ⇒ <b>服务端 base 自愈</b>
 * </pre>
 * 抬 {@code teleportDelay} 是<b>最小切口</b>：它只在 838 的绝对分支里被清回 0，
 * 所以抬一次 = 发一次绝对包，<b>自己会收回</b>，不需要我们回写。
 *
 * <h2>3. 🔴 为什么字段是「反射写」而不是 {@code @Shadow}</h2>
 * 本工程铁律（{@code ShanhaiSetBlockWatchMixin} 类注释 §2 的<b>构建产物实测</b>）：
 * <b>mixin 类体内的 MC 成员引用在 refmap 里没有条目</b>（{@code @Shadow} 字段/方法同样没有），
 * 而运行期类名是 SRG ⇒ 拿开发名 {@code teleportDelay} 去 SRG 的 {@code f_8522_} 上找，<b>找不到</b>。
 * <p>所以按本工程既有写法：<b>混入类只当转发壳</b>（型参 + {@code (Object) this}），
 * 反射在这里做，<b>候选名同时含 mojmap 与 SRG</b>：{@code teleportDelay} / {@code f_8522_}。
 * 反射还顺带绕开了 refmap —— 换映射表也不会静默失效。
 *
 * <h2>4. 线程与开销</h2>
 * 只有服务端主线程调 {@link #active()} / {@link #forceTeleportDelay} / {@link #noteSent}
 * （{@code sendChanges} 的唯一调用点 {@code ChunkMap#tick()} 在主线程）。
 * 未 arm 时每次 {@code sendChanges} 的代价 = <b>一次 volatile 读 + 一次 long 比较</b>；
 * arm 期间多一次 {@code Field.setInt}（Field 只查一次并缓存）。
 *
 * <p>⚠️ 本类<b>不参与任何业务判定</b>；它坏了最多是"没修好"，<b>绝不许影响原版发包</b>
 * （{@link #forceTeleportDelay} 自己吞异常，混入类里再套一层）。
 */
public final class ShanhaiReplayForceTeleport {

    /**
     * 强制绝对包用的 {@code teleportDelay} 值。
     *
     * <p>判据是 {@code teleportDelay > 400}（取证 F4 偏移 657 的 {@code sipush 400}；字节码精确值见
     * 取证 F4 的"三个 threshold"）⇒ 取 <b>401</b>，比阈值大 1。
     * 抬完由原版绝对分支自己清回 0（偏移 838-846），我们<b>不回写</b>。
     */
    private static final int FORCE_DELAY = 401;

    /** {@code teleportDelay} 的候选字段名：开发名（parchment/mojmap）在前，生产 SRG 名在后。 */
    private static final String[] DELAY_FIELD_NAMES = {"teleportDelay", "f_8522_"};

    /** 这一段目标类的名字（只给日志/自证用，不参与逻辑）。 */
    private static final String TARGET = "net.minecraft.server.level.ServerEntity";

    /**
     * 截止时刻：{@code System.nanoTime() / 1_000_000 + millis}。
     *
     * <p>{@code 0} = 从未 arm。用 {@code 0} 当哨兵而不是"总是比较大小"，是因为
     * {@code nanoTime()} 的原点任意、<b>可能为负</b> ⇒ 只比较大小会在未 arm 时误判成"生效中"。
     */
    private static volatile long until;

    /** {@link #noteSent()} 的计数（口径见该方法注释）。 */
    private static volatile int sent;

    /** 反射查到的字段（只查一次；{@code null} + {@link #lookupDone} ⇒ 查不到，之后每次直接返回）。 */
    private static volatile Field delayField;

    /** 字段查找是否已经做过（区分"还没查"与"查了没有"）。 */
    private static volatile boolean lookupDone;

    /** 字段查不到的原因（{@code ""} = 查到了）；给自证行读。 */
    private static volatile String lookupFailure = "";

    // ------------------------------------------------------------------ 开关

    /**
     * 打开或延长这个开关。
     *
     * <p>语义：<b>从现在起 {@code millis} 毫秒内</b>，每个进 {@code sendChanges} 的 {@code ServerEntity}
     * 都把 {@code teleportDelay} 抬到 {@link #FORCE_DELAY 401} ⇒ 这一轮走绝对包。
     *
     * <p>🔴 <b>必须在 {@code setMode(LIVE)} 之后调</b>：冻结期 {@code sendChanges} 根本不跑
     * （{@code tickChunks} 被跳过，取证 F9），提前 arm 只是把窗口烧在冻结期里。
     *
     * <p>重复 arm = <b>从现在重新计时</b>（不是累加）；同时把 {@link #sent()} 归零，
     * 好让"这一轮到底抬了几次"读得干净。
     *
     * @param millis 窗口长度（毫秒）；{@code <= 0} 等价于 {@link #reset()}
     */
    public static void arm(long millis) {
        if (millis <= 0L) {
            reset();
            return;
        }
        sent = 0;
        until = System.nanoTime() / 1_000_000L + millis;
    }

    /**
     * 现在还在窗口内吗（= 截止时刻之前）。
     *
     * <p>这是 {@code sendChanges} 每个实体每几 tick 都会走的那条路 ⇒
     * 未 arm 时必须只有<b>一次 volatile 读 + 一次比较</b>的开销。
     *
     * @return {@code true} ⇒ 该抬 {@code teleportDelay}
     */
    public static boolean active() {
        final long end = until;
        if (end == 0L) {
            return false;
        }
        return System.nanoTime() / 1_000_000L < end;
    }

    // ------------------------------------------------------------------ 读数

    /**
     * 记一次"抬过 {@code teleportDelay}"。
     *
     * <p>🔴 <b>口径 = 被抬的次数，不是最终发出的绝对包数</b>：抬完原版还要过
     * {@code tickCount % updateInterval == 0 || hasImpulse || 数据脏} 与
     * {@code tickCount > 0 || AbstractArrow} 两道闸（取证 F3/F4），而且乘客分支不走这条。
     * 所以 {@link #sent()} 是上界，判读时别把它当成"客户端真的收到了几个绝对包"。
     */
    public static void noteSent() {
        ++sent;
    }

    /** 自上次 {@link #arm(long)} 起被抬过 {@code teleportDelay} 的次数（口径见 {@link #noteSent()}）。 */
    public static int sent() {
        return sent;
    }

    /** 关掉窗口并把读数归零（换局/重进世界时不该留着上一轮的计数）。 */
    public static void reset() {
        until = 0L;
        sent = 0;
    }

    /** 字段查不到的原因（{@code ""} = 没问题）；只想读一次日志/自证行时用，不参与判定。 */
    public static String lookupFailure() {
        return lookupFailure;
    }

    // ------------------------------------------------------------------ 真正动手的那一下

    /**
     * 把这只 {@code ServerEntity} 的 {@code teleportDelay} 抬到 {@link #FORCE_DELAY}。
     *
     * <p>调用方（混入类）只传 {@code (Object) this} ⇒ <b>混入类体内零 MC 成员引用</b>（本工程铁律）。
     *
     * <p>⚠️ 全程吞异常：抬不动就"没修好"，<b>绝不许</b>把服务端的发包路径搞崩。
     *
     * @param serverEntity {@code ServerEntity} 实例（用 {@code Object} 接，免得把 MC 类型写进签名）
     */
    public static void forceTeleportDelay(Object serverEntity) {
        if (serverEntity == null) {
            return;
        }
        final Field field = delayField(serverEntity);
        if (field == null) {
            return;
        }
        try {
            field.setInt(serverEntity, FORCE_DELAY);
        } catch (Throwable ignored) {
            // 抬不动 ⇒ 这一轮照原版走（少修一次，不比原版更坏）
        }
    }

    /**
     * 查一次 {@code teleportDelay} 字段并缓存。
     *
     * <p>候选名含开发名与 SRG 名两种（{@link #DELAY_FIELD_NAMES}），<b>命中哪个都收</b> ——
     * 本工程两种运行环境都真实存在（开发 = parchment，生产 = reobf 后的 SRG）。
     *
     * <p>🔴 目标类<b>先从实例自己的 {@code getClass()} 拿</b>，拿不到才退回 {@link #TARGET} 类名字面量：
     * 实例一定已被加载，不依赖"调用方的类加载器能不能看见 MC"这层假设。
     *
     * @param probe 一只真实实例（只为拿 {@code Class}；{@code null} ⇒ 只能走类名字面量）
     * @return 字段；查不到返回 {@code null}（原因记进 {@link #lookupFailure()}）
     */
    private static Field delayField(Object probe) {
        final Field cached = delayField;
        if (cached != null) {
            return cached;
        }
        if (lookupDone) {
            return null;
        }
        synchronized (ShanhaiReplayForceTeleport.class) {
            if (delayField != null) {
                return delayField;
            }
            if (lookupDone) {
                return null;
            }
            final StringBuilder tried = new StringBuilder();
            try {
                for (String name : DELAY_FIELD_NAMES) {
                    if (tried.length() > 0) {
                        tried.append(',');
                    }
                    tried.append(name);
                    final Field f = declared(probe, name);
                    if (f != null) {
                        f.setAccessible(true);
                        delayField = f;
                        lookupFailure = "";
                        return f;
                    }
                }
                lookupFailure = "no_field tried=" + tried;
            } catch (Throwable t) {
                lookupFailure = "lookup_failed err=" + t.getClass().getSimpleName();
            } finally {
                lookupDone = true;
            }
        }
        return null;
    }

    /**
     * 在一个类上找字段：<b>实例的类优先</b>，没有实例才用类名字面量。
     *
     * @param probe 实例（可 {@code null}）
     * @param name  候选字段名
     * @return 字段；找不到返回 {@code null}
     */
    private static Field declared(Object probe, String name) {
        if (probe != null) {
            try {
                return probe.getClass().getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                // 这个候选名不对（或类不对）⇒ 交给调用方试下一个
                return null;
            }
        }
        try {
            return Class.forName(TARGET).getDeclaredField(name);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private ShanhaiReplayForceTeleport() {
    }
}
