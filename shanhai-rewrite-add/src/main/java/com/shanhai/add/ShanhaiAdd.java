package com.shanhai.add;

import com.mojang.logging.LogUtils;
import com.shanhai.add.client.AddClockHook;
import com.shanhai.add.config.ShanhaiAddConfig;
import com.shanhai.add.time.ShanhaiClockInit;
import com.shanhai.api.ShanhaiAddHolder;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.loading.FMLEnvironment;
import org.slf4j.Logger;

/**
 * 山海：扩展（{@code shanhai_add}）的 mod 入口。
 *
 * <p>本类是<b>唯一的跨 mod 装配点</b>（拆分设计 §6.1）：
 * <pre>
 *   ① 注册自己的配置      config/shanhai_add-common.toml（[gravity] multiplier）
 *   ② 装配世界时钟        com.shanhai.add.time.ShanhaiClockInit#init（原存在于本体的装配点）
 *   ③ 声明重力装配点      com.shanhai.add.gravity.ShanhaiGravityBootstrap（@Mod.EventBusSubscriber，事件驱动）
 *   ③′ 声明逐级抹除装配点  com.shanhai.add.guard.ShanhaiPurge ＋ ShanhaiGuardSuppression
 *                         （@Mod.EventBusSubscriber 事件驱动 ＋ /shanhai_add purge|suppress 命令）
 *   ④ 装时钟渲染接缝      com.shanhai.add.client.AddClockHook → 本体的 com.shanhai.api.ShanhaiClockHook（仅客户端）
 *   ⑤ 装桥                com.shanhai.api.ShanhaiAddHolder.install(new AddBridge())
 * </pre>
 * 阶段 3a（时钟 + 重力）与 3b（免死额外手段：setRemoved/setHealth/die 的 mixin ＋ 逐级抹除 ＋ 压制表）之后，
 * 本 mod 已经<b>真的</b>能回答 {@code has("clock") / has("gravity") / has("purge")} = {@code true}；
 * 本体的 {@code /shanhai purge} 走 {@code purgeAll} 时不再回退 {@code kill @e}。
 *
 * <h2>约定 1：构造器必须是无参的（真启动换来的）</h2>
 * Forge 1.20.1 的 {@code FMLModContainer.constructMod()} 走 {@code getDeclaredConstructor()}（<b>无参</b>），
 * <b>不支持</b>把 {@code IEventBus} 注入 {@code @Mod} 构造器（那是 <b>NeoForge</b> 的能力）。
 * 写成 {@code ShanhaiAdd(IEventBus)} 会在 mod 构造期抛
 * {@code NoSuchMethodException: com.shanhai.add.ShanhaiAdd.<init>()}。
 * 依据：本体 {@code com.shanhai.ShanhaiMod} 类注释「约定 1」（t7 首次启动实测）。
 *
 * <h2>约定 2：这里每挂一批都打一行自证（本工程通用口径）</h2>
 * 光看代码判断不了"到底挂上了没有"，所以 {@code clock_hook=} / {@code gravity=} 都进同一行开机自证；
 * 装配失败<b>只让那一项退化成不可用</b>，绝不让 mod 构造抛异常（构造期抛 = 整个整合包进不去）。
 *
 * <h2>🔴 依赖方向不许反过来</h2>
 * 本工程可以 import {@code com.shanhai.*}（本体的类）；<b>本体一行都不许 import {@code com.shanhai.add.*}</b>
 * —— 否则只装本体时第一次触碰就会 {@code NoClassDefFoundError}
 * （证据：本体 {@code client\guard\ShanhaiGuardClientEvents.java:19-28} 记的那次真实崩溃）。
 *
 * <h2>日志标记（机器可判）</h2>
 * <pre>
 *   [SHANHAI-ADD] loaded                                         —— 本 mod 有没有被加载
 *   [SHANHAI-ADD] bridge installed version=&lt;ver&gt; clock_hook=&lt;b&gt; gravity=&lt;b&gt; purge=&lt;b&gt;
 *   [SHANHAI-ADD] config registered …                            —— 配置文件名/节名（迁移与拆分判据）
 *   [SHANHAI-CLOCK] …                                            —— 时钟装配（前缀沿用，历史判据不失效）
 *   [SHANHAI-GRAVITY] bootstrap_done …                           —— 重力真装好了的唯一运行时证据
 *   [SHANHAI-GUARD] add_command_registered … ／ purge_channel=…   —— 逐级抹除挂上了的唯一运行时证据
 * </pre>
 */
@Mod(ShanhaiAdd.MOD_ID)
public final class ShanhaiAdd {

    /**
     * 本 mod 的 modId。必须与 {@code META-INF/mods.toml} 的 {@code modId} 逐字一致，
     * 否则 Forge 会在加载期报 {@code mods.toml} 与 {@code @Mod} 不匹配。
     */
    public static final String MOD_ID = "shanhai_add";

    /** 与本体一致的取法：{@code com.mojang.logging.LogUtils}（日志前缀走 log4j 的 mod 名）。 */
    private static final Logger LOGGER = LogUtils.getLogger();

    public ShanhaiAdd() {
        LOGGER.info("[SHANHAI-ADD] loaded");

        registerConfig();

        // 时钟（指令树 + 网络通道 + 「冻结期间禁止改世界」的事件层）
        final boolean clock = initClock();
        // 重力（装配点是事件订阅的 ShanhaiGravityBootstrap；这里只确认它在本 jar 里、可解析）
        final boolean gravity = declareGravity();
        // 逐级抹除（阶梯在 add\guard\ShanhaiPurge；这里同样只确认类可解析 + 阶梯通道表完整）
        final boolean purge = declarePurge();
        // 本体渲染器（机器环/恒星特效）的时钟闸门
        final boolean clockHook = installClockHook();

        // 桥（本体唯一认的接口实现）
        installBridge(clock, gravity, purge, clockHook);
    }

    /** ① 自己的配置：{@code config/shanhai_add-common.toml}。 */
    private static void registerConfig() {
        try {
            ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, ShanhaiAddConfig.COMMON_SPEC);
            LOGGER.info("[SHANHAI-ADD] config registered type=COMMON file=config/{} section=[gravity] key={}",
                    ShanhaiAddConfig.FILE_NAME, "multiplier");
        } catch (Throwable t) {
            // 配置注册失败 ⇒ 重力倍率退化成"只在内存里生效"（指令仍可用），不影响启动
            LOGGER.error("[SHANHAI-ADD] config registered=false ⇒ 重力倍率不会落盘", t);
        }
    }

    /**
     * ② 世界时钟装配（原本体 {@code ShanhaiMod} 构造器里的那一步）。
     *
     * @return {@code true} = 装配调用没抛（真值同时看 {@code [SHANHAI-CLOCK] 世界时钟已装配} /
     *         {@code 指令已注册} 两行）
     */
    private static boolean initClock() {
        try {
            ShanhaiClockInit.init();
            return true;
        } catch (Throwable t) {
            LOGGER.error("[SHANHAI-ADD] clock wiring failed err={}（时钟不可用，其余照常）", t.toString(), t);
            return false;
        }
    }

    /**
     * ③ 重力装配点自证。
     *
     * <p>{@code ShanhaiGravityBootstrap} 是 {@code @Mod.EventBusSubscriber(modid = ShanhaiAdd.MOD_ID,
     * bus = MOD)}，<b>不需要</b>在这里显式调 —— Forge 扫描本 jar 时会按 modid 把它的
     * {@code onCommonSetup} 挂上（modid 没改对就会被<b>静默跳过</b>，这正是设计 §10.2 风险 1）。
     * 所以这里做的是可判的一步：确认那个类真的在本 jar 里、且能被解析。
     * 🔴 它只证明"装配点存在"，"真的跑起来了"的唯一证据是启动日志里的
     * {@code [SHANHAI-GRAVITY] bootstrap_done channel_ready=true …}。
     *
     * @return {@code true} = 装配点类存在且可解析
     */
    private static boolean declareGravity() {
        try {
            Class<?> bootstrap = Class.forName(
                    "com.shanhai.add.gravity.ShanhaiGravityBootstrap", false, ShanhaiAdd.class.getClassLoader());
            LOGGER.info("[SHANHAI-ADD] gravity wiring declared class={}（真值看本次启动的 [SHANHAI-GRAVITY] bootstrap_done）",
                    bootstrap.getSimpleName());
            return true;
        } catch (Throwable t) {
            LOGGER.error("[SHANHAI-ADD] gravity wiring declared=false err={}（重力不可用，其余照常）",
                    t.toString(), t);
            return false;
        }
    }

    /**
     * ③′ 逐级抹除装配点自证（阶段 3b）。
     *
     * <p>阶梯类 {@code com.shanhai.add.guard.ShanhaiPurge} 与压制表 {@code ShanhaiGuardSuppression}
     * 都是从本体搬过来的<b>静态工具类</b>，没有 {@code init()} 可调；它们的运行期入口是
     * {@code @Mod.EventBusSubscriber(modid = ShanhaiAdd.MOD_ID)}（攻击端反击 ＋ 每 tick 重申/压制）
     * 与命令（{@code /shanhai_add purge|suppress}）。
     * 所以这里做两件可判的事：① 类真的在本 jar 里、能解析；② 阶梯通道表就是本体的那一套（L1/L3/L4/L5/L6）。
     * 🔴 真正的运行时证据是日志里的 {@code purge_channel=…} 与
     * {@code [SHANHAI-GUARD] add_command_registered …}，不是这一行。
     *
     * @return {@code true} = 阶梯类可解析且通道表 ≥ 5 条
     */
    private static boolean declarePurge() {
        try {
            final Class<?> purge = Class.forName(
                    "com.shanhai.add.guard.ShanhaiPurge", false, ShanhaiAdd.class.getClassLoader());
            final String[] channels = com.shanhai.common.guard.ShanhaiGuardRemoval.purgeChannels();
            final boolean tableOk = channels != null && channels.length >= 5;
            LOGGER.info("[SHANHAI-ADD] purge wiring declared class={} channels={} table_ok={}"
                            + "（真值看 /shanhai_add purge 那一拍的 purge_channel= 日志）",
                    purge.getSimpleName(), channels == null ? "?" : String.join("/", channels), tableOk);
            return tableOk;
        } catch (Throwable t) {
            LOGGER.error("[SHANHAI-ADD] purge wiring declared=false err={}（逐级抹除不可用，本体自动回退 kill@e）",
                    t.toString(), t);
            return false;
        }
    }

    /**
     * ④ 本体渲染器的时钟闸门（{@code com.shanhai.api.ShanhaiClockHook}）。
     *
     * <p>🔴 <b>只在客户端装</b>：实现类引用 {@code net.minecraft.client.*}（经
     * {@code ShanhaiClientClock}），专用服务端上加载它就会 {@code NoClassDefFoundError}。
     * 常量池里的这条引用在服务端<b>永远不解析</b>（dist 判定为假 ⇒ 该指令不执行），
     * 与本体 {@code ShanhaiMod} 注册客户端配置那段的写法逐字同款。
     *
     * @return {@code true} = 装上了（服务端恒 {@code false}：那里根本没有渲染器要用它）
     */
    private static boolean installClockHook() {
        if (FMLEnvironment.dist != Dist.CLIENT) {
            return false;
        }
        try {
            return AddClockHook.install();
        } catch (Throwable t) {
            // 装不上 ⇒ 本体渲染器退回"原值透传"（= 与没有时钟时一样），不崩
            LOGGER.error("[SHANHAI-ADD] clock_hook installed=false err={}（机器环/恒星特效不再随暂停冻结）",
                    t.toString(), t);
            return false;
        }
    }

    /**
     * ⑤ 把 {@link AddBridge} 装进本体的桥。
     *
     * <p>为什么整段包 try/catch：装配失败只该让"扩展能力不可用"（本体自动回退 {@code kill @e}），
     * <b>不该让 mod 构造失败</b> —— 构造期抛异常会让整个整合包进不去，代价远大于收益。
     *
     * <p>⚠️ 本方法里 {@code AddBridge} 是<b>本 mod 自己的类</b>，不是 body 的类，
     * 所以这里 new 它不存在"只装 body 时加载不到 add 的类"那个风险
     * （那个风险的方向恰好相反：body 不许碰 add 的类）。
     */
    private static void installBridge(boolean clock, boolean gravity, boolean purge, boolean clockHook) {
        try {
            final AddBridge bridge = new AddBridge();
            // 能力位先落，再 install：本体一旦拿到实现，has() 就必须已经是真值
            bridge.setClockReady(clock);
            bridge.setGravityReady(gravity);
            bridge.setPurgeReady(purge);
            ShanhaiAddHolder.install(bridge);
            LOGGER.info("[SHANHAI-ADD] bridge installed version={} clock_hook={} gravity={} purge={} modid={}",
                    bridge.modVersion(), clockHook, gravity, purge, MOD_ID);
        } catch (Throwable t) {
            // 本体那一侧每次 purge 会打 purge_via=simple reason=add_missing ⇒ 这条失败在日志里是可判的
            LOGGER.error("[SHANHAI-ADD] bridge installed=false ⇒ 本体这次只能走 kill@e（扩展能力全部不可用）", t);
        }
    }
}
