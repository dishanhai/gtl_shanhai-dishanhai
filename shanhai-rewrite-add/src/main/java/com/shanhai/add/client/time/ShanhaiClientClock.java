package com.shanhai.add.client.time;

import com.mojang.logging.LogUtils;
import com.shanhai.add.time.ShanhaiClockNetwork;
import com.shanhai.add.time.ShanhaiClockRules;
import com.shanhai.add.time.ShanhaiWorldClock;

import net.minecraft.client.Minecraft;
import net.minecraft.client.Timer;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import org.slf4j.Logger;

/**
 * 山海重构 · <b>客户端时钟（只读镜像 + 钩子标志）</b>。
 *
 * <h2>1. 客户端到底要钉哪几处（普查结论，逐条对应本类的 {@code hook*} 字段）</h2>
 * <table border="1">
 *   <caption>客户端冻结点</caption>
 *   <tr><th>#</th><th>钉在哪</th><th>不钉会怎样</th><th>本类字段</th></tr>
 *   <tr><td>1</td><td>{@code LevelRenderer#tick()}（{@code ticks} 不增）</td>
 *       <td>雨/雪/末地传送门/云 还在动</td><td>{@link #hookLevelRendererTicks}</td></tr>
 *   <tr><td>2</td><td>{@code ClientLevel#tickTime()}（{@code gameTime/dayTime} 不增）</td>
 *       <td>太阳/月亮/天空亮度继续走（服务端冻了它自己还会 +1）</td><td>{@link #hookClientTickTime}</td></tr>
 *   <tr><td>3</td><td>{@code TextureManager#tick()}（贴图动画帧不推进）</td>
 *       <td><b>水面在流</b>、岩浆/火/传送门在动</td><td>{@link #hookTextureAnim}</td></tr>
 *   <tr><td>4</td><td>{@code ParticleEngine#tick()}</td><td>空中的粒子继续飘</td><td>{@link #hookParticles}</td></tr>
 *   <tr><td>5</td><td>{@code ClientLevel#tickNonPassenger(Entity)}（玩家除外）</td>
 *       <td>生物倒手/物品旋转等客户端动画继续</td><td>{@link #hookEntityTicks}</td></tr>
 *   <tr><td>6</td><td>{@code Level#tickBlockEntities()} + {@code ClientLevel#animateTick}</td>
 *       <td>机器/箱子的客户端动画继续、火把继续冒新粒子</td><td>{@link #hookBlockEntities}</td></tr>
 *   <tr><td>7</td><td>{@code Timer#advanceTime} 之后把 {@code partialTick} 钉成常量</td>
 *       <td>世界动画有 ≤1 tick 的锯齿抖动</td><td>{@link #hookPartialTick}（<b>本轮默认关</b>）</td></tr>
 *   <tr><td>8</td><td>Oculus {@code SystemTimeUniforms$Timer#beginFrame}</td>
 *       <td>光影包的水波/草动继续摇（墙钟驱动）</td><td>{@link #hookOculusTimer}</td></tr>
 * </table>
 *
 * <h2>2. 🔴 为什么 {@code partialTick} 默认不钉（与普查建议的偏差，必须写清）</h2>
 * {@code partialTick}（= 渲染插值 α）<b>同时</b>驱动三样东西：
 * <ol>
 *   <li>世界动画的亚 tick 分量（{@code ticks + partialTick}、{@code GameTime} uniform）；</li>
 *   <li>实体插值（{@code Mth.lerp(α, xo, x)}）；</li>
 *   <li><b>玩家自己的相机与视角</b>（{@code Entity#getEyePosition(α)}、{@code getViewYRot(α)}）。</li>
 * </ol>
 * 第 3 条是致命的：{@code yRot} 每帧变、{@code yRotO} 每 tick 变，
 * 视角 = {@code lerp(α, yRotO, yRot)} ⇒ <b>把 α 钉成任何常量，转头速度都会被乘上 α</b>：
 * <ul>
 *   <li>α = 0 ⇒ 鼠标转视角<b>完全不动</b>（每 tick 才刷新一次）—— 直接违反"玩家还能转头"；</li>
 *   <li>α = 0.5 ⇒ 转头速度减半；位置也会变成 20 Hz 定格。</li>
 * </ul>
 * 而用户的口径 ② 是<b>红线</b>："玩家的时间不受影响"。所以本轮：
 * <ul>
 *   <li>α <b>保持实时</b>（默认 {@link #pinPartialTick} = {@code false}）；</li>
 *   <li>原本要靠钉 α 消掉的"水面流动"改由 <b>贴图动画帧</b>（第 3 条）负责 —— 这才是水/岩浆/火
 *       真正的时间源（{@code SpriteContents$Ticker#tickAndUpload} 里是 <b>帧计数器 ++</b>，
 *       不是墙钟、也不是 α；见报告 ③ 的取证）；</li>
 *   <li>剩下的锯齿只有雨雪/末地传送门那点亚 tick 分量（幅度 ≤1/512 与 0.03/12），肉眼不可见。</li>
 * </ul>
 * 机制本身已经实现并可一键打开（{@code /shanhai_add clock pinpartial on}，用于对比验收）。
 *
 * <h2>3. 为什么还要读 {@link ShanhaiWorldClock#SERVER}</h2>
 * 单机（用户的实际玩法）里集成服务端与客户端在<b>同一个 JVM</b>，{@code SERVER} 直接可见。
 * 网络包万一没到（重连/丢包/首次进服），客户端就会"该冻没冻" —— 那是很难查的假象。
 * 所以单机下额外读一次 {@code SERVER} 作为兜底；<b>多人</b>下仍然只认服务端下发的镜像。
 */
@OnlyIn(Dist.CLIENT)
public final class ShanhaiClientClock {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** 世界时钟是否处在冻结态（单机兜底读服务端那份）。 */
    public static boolean paused() {
        if (ShanhaiWorldClock.CLIENT.isPaused()) {
            return true;
        }
        Minecraft mc = Minecraft.getInstance();
        return mc != null && mc.hasSingleplayerServer() && ShanhaiWorldClock.SERVER.isPaused();
    }

    // ------------------------------------------------------------------ 钩子标志
    // 由各 mixin 的转发回调置位 ⇒ status/ack 能证明"这一处真的被调到了"，
    // 而不是"我写了 mixin 但没生效"（本工程血账：探针挂没挂上在日志上必须可判）。

    public static volatile boolean hookLevelRendererTicks;
    public static volatile boolean hookClientTickTime;
    public static volatile boolean hookTextureAnim;
    public static volatile boolean hookParticles;
    public static volatile boolean hookEntityTicks;
    public static volatile boolean hookBlockEntities;
    public static volatile boolean hookPartialTick;
    public static volatile boolean hookOculusTimer;
    /** ③ 实体插值基准对齐（{@code setOldPosAndRot}）真的执行过。 */
    public static volatile boolean hookPosePinned;
    /** ④ 连续渲染时钟（gtlcore {@code GlobalRenderClock}）真的被冻住过。 */
    public static volatile boolean hookSmoothTick;

    /** 是否把 {@code partialTick} 钉成常量（默认关，理由见类注释 §2）。 */
    private static volatile boolean pinPartialTick = Boolean.getBoolean("shanhai.clock.pinPartialTick");

    public static boolean isPinPartialTick() {
        return pinPartialTick;
    }

    public static void setPinPartialTick(boolean value) {
        pinPartialTick = value;
        LOGGER.info("[SHANHAI-CLOCK] 客户端 partialTick 钉住 = {}", value);
    }

    public static void togglePinPartialTick() {
        setPinPartialTick(!pinPartialTick);
    }

    // ------------------------------------------------------------------ 各处钩子的实现

    /** 世界动画时钟（{@code LevelRenderer#tick}）：冻结时整段跳过。 */
    public static boolean freezeLevelRendererTick() {
        boolean p = paused();
        if (p) {
            hookLevelRendererTicks = true;
        }
        return p;
    }

    /** 客户端世界时间（{@code ClientLevel#tickTime}）：冻结时整段跳过。 */
    public static boolean freezeClientTickTime() {
        boolean p = paused();
        if (p) {
            hookClientTickTime = true;
        }
        return p;
    }

    /** 贴图动画（水/岩浆/火/传送门）：冻结时不推进帧。 */
    public static boolean freezeTextureAnimation() {
        boolean p = paused();
        if (p) {
            hookTextureAnim = true;
        }
        return p;
    }

    /** 粒子：冻结时整段跳过 ⇒ 已经飘着的粒子停在原地（不是停止生成）。 */
    public static boolean freezeParticles() {
        boolean p = paused();
        if (p) {
            hookParticles = true;
        }
        return p;
    }

    /** 客户端方块实体 tick：冻结时跳过。 */
    public static boolean freezeBlockEntities() {
        boolean p = paused();
        if (p) {
            hookBlockEntities = true;
        }
        return p;
    }

    /** 客户端环境粒子生成（{@code ClientLevel#animateTick}）：冻结时不生成。 */
    public static boolean freezeAnimateTick() {
        boolean p = paused();
        if (p) {
            hookBlockEntities = true;
        }
        return p;
    }

    /**
     * 单个实体是否该被冻结。
     *
     * <p>🔴 <b>玩家豁免</b>：只有"本地玩家自己"放行（服务端权威位置、移动、视角都在他自己那条链上）。
     * 多人下别的玩家也是 {@code Player}，但他们属于"世界"，照样冻结。
     */
    public static boolean freezeEntity(Object entity) {
        if (!paused()) {
            return false;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc != null && entity == mc.player) {
            return false;
        }
        hookEntityTicks = true;
        return true;
    }

    /**
     * ③ <b>把被冻结实体的"上一 tick 位置/朝向"对齐到当前值</b>（缺陷「空中实体上下鬼畜」的修法）。
     *
     * <h2>为什么必须是这一句</h2>
     * {@code ClientLevel#tickNonPassenger}（{@code ClientLevel.java:267-281}）的第一句就是
     * {@code p_104640_.setOldPosAndRot();}（{@code :268}）。我们把整个方法 HEAD 取消 ⇒
     * 那句<b>永远不跑</b> ⇒ {@code xo/yo/zo}（以及 {@code xOld/yOld/zOld}、{@code xRotO/yRotO}）
     * 停在"暂停前最后一次移动之前"的值上；而渲染插值
     * {@code Entity#getPosition(α) = lerp(α, yo, getY())}（{@code Entity.java:1528-1533}）里的 α
     * <b>照常</b>每 tick 从 0 扫到 1 ⇒ 渲染位置在"旧位置 ↔ 新位置"之间来回锯齿。
     * 幅度 = 一 tick 的位移（下落的实体那一刻可能 0.3~1.0 格）⇒ 用户看到的正是
     * "空中的实体会上下鬼畜，但是不会真的下落"（逻辑位置冻结、渲染位置在旧/新之间抖）。
     *
     * <p>对齐之后 {@code lerp(α, y, y) == y} 对任何 α 恒成立 ⇒ 渲染位置恒等于逻辑位置。
     * 🔴 <b>完全不碰 partialTick</b> ⇒ 玩家自己的相机/视角插值一点没变（红线）。
     *
     * @param entity {@code ClientLevel#tickNonPassenger} 的形参
     */
    public static void pinFrozenEntityPose(Object entity) {
        if (!(entity instanceof Entity e)) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (!ShanhaiClockRules.pinEntityPose(paused(), mc != null && e == mc.player)) {
            return;
        }
        // 取证（缺陷 ③ 的"改前现象"就是这里的 before）：pin 之前的 |Δy|。
        // 暂停首帧它会是非零（= 旧实现的锯齿幅度），之后每一帧都应为 0。
        double before = Math.abs(e.getY() - e.yOld);
        if (before > probeMaxDyBeforePin) {
            probeMaxDyBeforePin = before;
        }
        // 这句就是原版 tickNonPassenger 的第一句（public final，见 Entity.java:1391-1403）：
        // 同时写 xo/yo/zo + xOld/yOld/zOld + xRotO/yRotO。
        e.setOldPosAndRot();
        hookPosePinned = true;
        ++pinnedEntityCount;
        probe(e);
    }

    // ------------------------------------------------------------------ ③ 探针（限流采样）

    /** 本次暂停累计被对齐过多少次（≈ 冻结实体 tick 次数）。 */
    private static long pinnedEntityCount;
    /** 本次暂停里"对齐之前"观测到的最大 |Δy| —— 即旧实现的锯齿幅度（取证用）。 */
    private static double probeMaxDyBeforePin;
    /** 上一次打探针日志的墙钟。 */
    private static long probeLastLogMs;
    /** 探针盯着的那一个实体（弱引用，换世界/恢复即失效）。 */
    private static java.lang.ref.WeakReference<Entity> probeEntity = new java.lang.ref.WeakReference<>(null);
    /** 上次打日志时那个探针实体的 y（用来证明"逻辑坐标 N 秒不动"）。 */
    private static double probeLastY = Double.NaN;

    /**
     * 限流采样（≤1 行/秒）。
     *
     * <p>判据行（可 grep）：
     * <pre>
     * [SHANHAI-CLOCK] clock_entity_probe pinned=… max_dy_before_pin=… sample=&lt;type&gt; y=… dy_1s=… dy_now=…
     * </pre>
     * <ul>
     *   <li>{@code max_dy_before_pin} —— 暂停首帧的 |y 与 yOld 之差|，<b>非 0</b> 才证明"抖"的成因是插值基准；
     *       （改了以后它只会在首帧出现一次，之后不再增长）</li>
     *   <li>{@code dy_now = |y - yOld|} —— pin 之后应恒为 {@code 0.0}；</li>
     *   <li>{@code dy_1s} —— 同一个实体每秒的位移，应恒为 {@code 0.0}（逻辑坐标不动）。</li>
     * </ul>
     */
    private static void probe(Entity e) {
        long now = System.currentTimeMillis();
        if (now - probeLastLogMs < 1_000L) {
            return;
        }
        probeLastLogMs = now;
        Entity sample = probeEntity.get();
        if (sample == null || sample.isRemoved()) {
            sample = e;
            probeEntity = new java.lang.ref.WeakReference<>(e);
            probeLastY = Double.NaN;
        }
        double y = sample.getY();
        double dy1s = Double.isNaN(probeLastY) ? 0.0D : Math.abs(y - probeLastY);
        probeLastY = y;
        LOGGER.info("[SHANHAI-CLOCK] clock_entity_probe pinned={} max_dy_before_pin={} sample={} y={} dy_1s={} dy_now={}",
                pinnedEntityCount,
                String.format(java.util.Locale.ROOT, "%.4f", probeMaxDyBeforePin),
                net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(sample.getType()),
                String.format(java.util.Locale.ROOT, "%.4f", y),
                String.format(java.util.Locale.ROOT, "%.4f", dy1s),
                String.format(java.util.Locale.ROOT, "%.4f", Math.abs(sample.getY() - sample.yOld)));
    }

    /**
     * ④ 冻结 gtlcore 的<b>连续渲染时钟</b>（{@code GlobalRenderClock#getSmoothTick()}，
     * 墙钟驱动：{@code elapsedNanos / 5.0E7}）。
     *
     * <p>取证与链路见 {@code mixin\gtlcore\ShanhaiGtlcoreSmoothTickClockMixin} 类注释。
     * <b>冻法 = 返回锁住的那个值</b>，不是返回 0 —— 返回 0 会让动画从时间原点重来（视觉上"咔"一下）。
     *
     * <p>本方法同时被 {@code AbstractRingRenderer#render} 复用一次（第二道保险：万一 gtlcore 那条
     * mixin 因版本变化静默失效，我们的机器环仍然冻得住）。
     *
     * @param live 本帧真实值
     * @return 暂停期间恒为"进入暂停那一刻的值"
     */
    public static float freezeSmoothTick(float live) {
        if (!paused()) {
            frozenSmoothTick = Float.NaN;   // 恢复 ⇒ 立刻解冻（下一帧重新取真实值）
            return live;
        }
        float v = ShanhaiClockRules.smoothTick(true, frozenSmoothTick, live);
        if (Float.isNaN(frozenSmoothTick)) {
            frozenSmoothTick = live;        // 第一帧锁住
            LOGGER.info("[SHANHAI-CLOCK] clock_smoothtick frozen_at={}", frozenSmoothTick);
        }
        hookSmoothTick = true;
        return v;
    }

    /** 暂停期间锁住的连续渲染时钟（{@code NaN} = 未锁）。 */
    private static float frozenSmoothTick = Float.NaN;

    /**
     * ① 客户端丢物品是否拒绝（{@code LocalPlayer#drop(boolean)} 的 HEAD）。
     *
     * <p>为什么客户端也要拦：{@code LocalPlayer#drop(boolean)}（{@code LocalPlayer.java:279-285}）
     * <b>先在本地</b> {@code removeFromSelected} 再发包 ⇒ 只拦服务端的话界面会先少一个、
     * 服务端拒绝后也不回滚（等下一次容器同步才跳回来）。
     */
    public static boolean denyLocalDrop() {
        return ShanhaiClockRules.denyItemDrop(paused());
    }

    /**
     * {@code Timer#advanceTime} 返回之后调用：把插值 α 钉成常量。
     *
     * <p>⚠️ <b>默认不钉</b>（见类注释 §2）。只有 {@link #setPinPartialTick} 打开时才写字段。
     *
     * @param timer {@code (Object) this}，运行期就是 {@link Timer}
     */
    public static void onTimerAdvance(Object timer) {
        if (!pinPartialTick || !paused()) {
            return;
        }
        if (!(timer instanceof Timer t)) {
            return;
        }
        // Timer#partialTick 是 public 字段（1.20.1 Timer.java:8）⇒ 普通类直接写，不需要反射/@Shadow。
        t.partialTick = 0.0F;
        hookPartialTick = true;
    }

    /** Oculus 的 {@code beginFrame}：返回 true 表示"冻结，别推进帧时钟"。 */
    public static boolean shouldCancelOculusBeginFrame() {
        boolean p = paused();
        if (p && !hookOculusTimer) {
            hookOculusTimer = true;
            LOGGER.info("[SHANHAI-CLOCK] Oculus/Sodium 时间 uniform 钩子已生效（frameTimeCounter 冻结）");
        }
        return p;
    }

    // ------------------------------------------------------------------ ack（回给服务端）

    /** ack 心跳计数 */
    private static int ackCooldown;

    /**
     * 每个客户端 tick 调一次（挂在 {@code LevelRenderer#tick} 的注入里）。
     *
     * <p>职责：① 推进镜像 tick（暂停时不增）；② 每 20 tick 回一次 ack，
     * 让服务端的 {@code /shanhai_add clock status} 能打出<b>可验证</b>的"哪几处真的钉住了"。
     */
    public static void onClientTick() {
        ShanhaiWorldClock.CLIENT.clientTick();
        syncSound();
        syncTransition();
        if (--ackCooldown > 0) {
            return;
        }
        ackCooldown = 20;
        if (paused()) {
            ShanhaiClockNetwork.sendAckFromClient(ackMask(), ShanhaiWorldClock.CLIENT.getEpoch());
        }
    }

    // ------------------------------------------------------------------ ⑤ 暂停⇄恢复的收尾（自愈）

    /** 上一次看到的暂停态（{@code null} = 还没初始化）。 */
    private static Boolean lastPaused;

    /**
     * 暂停态<b>跳变</b>时的一次性收尾。
     *
     * <p>要做的事只有两件：
     * <ol>
     *   <li>清掉这次暂停期间攒的"锁住值"与探针计数（下次暂停从干净状态开始）；</li>
     *   <li><b>补一次藏环</b>（{@code reassertAllHidden}）—— 这是缺陷 ⑤ 的自愈路径：
     *       硬网曾经把客户端的藏环写入拦掉，而 {@code GtlAddClientCompat} 的台账是
     *       <b>先登记快照、后写 AIR</b>（{@code GtlAddClientCompat.java:380-387}）⇒
     *       被拦的那一轮会留下"台账非空但方块还是真方块"的状态，而下一帧因为
     *       "台账非空且扫全了"走 O(1) 短路（{@code :326-331}）⇒ <b>永远不会自己补上</b>。
     *       所以每次状态跳变都补扫一遍（成本 = 台账格数，暂停只发生几次，可忽略）。</li>
     * </ol>
     */
    private static void syncTransition() {
        boolean now = paused();
        if (lastPaused != null && lastPaused == now) {
            return;
        }
        boolean first = lastPaused == null;
        lastPaused = now;

        // 跳变 ⇒ 锁住值/探针一律重来
        frozenSmoothTick = Float.NaN;
        probeEntity = new java.lang.ref.WeakReference<>(null);
        probeLastY = Double.NaN;
        probeMaxDyBeforePin = 0.0D;
        pinnedEntityCount = 0L;
        probeLastLogMs = 0L;
        if (first) {
            return;
        }
        LOGGER.info("[SHANHAI-CLOCK] clock_transition paused={}", now);
        healRingHides();
    }

    /** 补一次"藏环"（只碰客户端方块，服务端看不见）。 */
    private static void healRingHides() {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc != null && mc.level != null) {
                com.shanhai.client.compat.GtlAddClientCompat.reassertAllHidden(mc.level);
            }
        } catch (Throwable t) {
            LOGGER.warn("[SHANHAI-CLOCK] 藏环补扫失败（环可能仍显示为真方块）", t);
        }
    }

    // ------------------------------------------------------------------ ③ 声音也停

    /** 上一次应用的"声音是否暂停"（true = 我们让它停住了）。 */
    private static boolean soundPaused;

    /**
     * 冻结 ⇔ {@code SoundManager#pause()}，恢复 ⇔ {@code SoundManager#resume()}。
     *
     * <p>为什么用这一条：{@code SoundEngine#pause()}（{@code SoundEngine.java:452}）会对<b>所有已开着的
     * OpenAL channel</b> 调 {@code Channel.pause()} ⇒ <b>已经在播的声音（环境音/音乐/雨声）真的停住</b>，
     * 而不只是"不再产生新声音"。{@code resume()}（{@code :461}）对 {@code Channel.unpause()}。
     *
     * <p>⚠️ <b>接受的代价</b>（用户已知）：这是<b>全局</b>暂停 ⇒ UI 点击音、聊天提示音、玩家脚步声
     * 也会一起闷掉。如果以后要"只停环境音、保留 UI 音"，得按 {@code SoundSource} 过滤，成本高得多。
     *
     * <p>调用时机：只在<b>状态跳变</b>时调；另外每 20 个客户端 tick 重压一次（幂等），
     * 防止别的代码（其他 mod、窗口失焦）把声音引擎 resume 回去而状态没跟上。
     */
    private static void syncSound() {
        boolean want = paused();
        if (want == soundPaused) {
            if (want && (++soundReassertCounter % 20) == 0) {
                reassertSound(true);
            }
            return;
        }
        soundPaused = want;
        reassertSound(want);
        LOGGER.info("[SHANHAI-CLOCK] clock_sound paused={}", want);
    }

    private static int soundReassertCounter;

    private static void reassertSound(boolean pause) {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc == null) {
                return;
            }
            if (pause) {
                mc.getSoundManager().pause();
            } else {
                mc.getSoundManager().resume();
            }
        } catch (Throwable t) {
            LOGGER.warn("[SHANHAI-CLOCK] clock_sound 应用失败（pause={}）", pause, t);
        }
    }

    /** 收到服务端下发的状态：打一行日志（每换一次 epoch 一行，不刷屏）。 */
    public static void onStateApplied(ShanhaiWorldClock.Snapshot snapshot) {
        if (snapshot != null && snapshot.epoch() != lastLoggedEpoch) {
            lastLoggedEpoch = snapshot.epoch();
            LOGGER.info("[SHANHAI-CLOCK] 客户端时钟状态 = {}", snapshot.toLine());
        }
    }

    private static long lastLoggedEpoch = Long.MIN_VALUE;

    /** 位掩码：哪些客户端时钟确认被钩住（服务端 status 用）。 */
    public static int ackMask() {
        int mask = 0;
        if (hookLevelRendererTicks) {
            mask |= 1;
        }
        if (hookClientTickTime) {
            mask |= 1 << 1;
        }
        if (hookTextureAnim) {
            mask |= 1 << 2;
        }
        if (hookParticles) {
            mask |= 1 << 3;
        }
        if (hookEntityTicks) {
            mask |= 1 << 4;
        }
        if (hookBlockEntities) {
            mask |= 1 << 5;
        }
        if (hookPartialTick) {
            mask |= 1 << 6;
        }
        if (hookOculusTimer) {
            mask |= 1 << 7;
        }
        if (soundPaused) {
            mask |= 1 << 8;
        }
        if (hookPosePinned) {
            mask |= 1 << 9;
        }
        if (hookSmoothTick) {
            mask |= 1 << 10;
        }
        return mask;
    }

    /** 人类可读的 mask（status 直接印这一行）。 */
    public static String ackText(int mask) {
        if (mask == 0) {
            return "client-hooks=<none>";
        }
        return "client-hooks="
                + "ticks:" + bit(mask, 0)
                + ",tickTime:" + bit(mask, 1)
                + ",texture:" + bit(mask, 2)
                + ",particles:" + bit(mask, 3)
                + ",entities:" + bit(mask, 4)
                + ",blockEntities:" + bit(mask, 5)
                + ",partialTick:" + bit(mask, 6)
                + ",oculus:" + bit(mask, 7)
                + ",posePinned:" + bit(mask, 9)
                + ",smoothTick:" + bit(mask, 10);
    }

    private static String bit(int mask, int i) {
        return (mask & (1 << i)) != 0 ? "已钉住" : "未触发";
    }

    /** 换世界/断线时清空（保证 status 不会读到上一局的标志），并把声音引擎恢复。 */
    public static void reset() {
        if (soundPaused) {
            soundPaused = false;
            reassertSound(false);
            LOGGER.info("[SHANHAI-CLOCK] clock_sound paused=false（换世界/断线收尾）");
        }
        hookLevelRendererTicks = false;
        hookClientTickTime = false;
        hookTextureAnim = false;
        hookParticles = false;
        hookEntityTicks = false;
        hookBlockEntities = false;
        hookPartialTick = false;
        hookOculusTimer = false;
        hookPosePinned = false;
        hookSmoothTick = false;
        frozenSmoothTick = Float.NaN;
        lastPaused = null;
        probeEntity = new java.lang.ref.WeakReference<>(null);
        probeLastY = Double.NaN;
        probeMaxDyBeforePin = 0.0D;
        pinnedEntityCount = 0L;
        probeLastLogMs = 0L;
    }

    private ShanhaiClientClock() {
    }
}
