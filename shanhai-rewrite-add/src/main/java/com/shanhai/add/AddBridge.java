package com.shanhai.add;

import com.mojang.logging.LogUtils;
import com.shanhai.add.guard.ShanhaiPurge;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.server.ServerLifecycleHooks;
import org.slf4j.Logger;

/**
 * 山海扩展（{@code shanhai_add}）对本体接口 {@code com.shanhai.api.ShanhaiAdd} 的<b>唯一</b>实现。
 *
 * <p>装配点只有一个：{@link ShanhaiAdd#ShanhaiAdd()}（{@code @Mod} 构造器）里
 * {@code ShanhaiAddHolder.install(new AddBridge())}。本体永远不认这个类，
 * 它只通过 {@code com.shanhai.api.ShanhaiAddHolder.optional()} 拿到接口
 * ⇒ 只装本体时这里<b>一个类都不会被加载</b>（这正是拆分要保住的单向依赖，设计 §6.1 / §6.2）。
 *
 * <h2>为什么 {@code implements} 后面写全限定名</h2>
 * 本工程里 {@code ShanhaiAdd} 这个简单名已经被 {@code @Mod} 入口类占用了
 * （{@code com.shanhai.add.ShanhaiAdd}）。接口是 {@code com.shanhai.api.ShanhaiAdd}，
 * 两者只差包名。这里<b>刻意不 import</b> 接口，靠全限定名写 {@code implements}，
 * 于是文件里的 {@code ShanhaiAdd.MOD_ID} 依然稳定地指"本 mod 入口类"，
 * 读者也不会把两个同名类型看混。
 *
 * <h2>本阶段（3b）三个方法/能力位的真实行为</h2>
 * <pre>
 *   modVersion()       真值：ModList/ModContainer 里的版本（不是写死的字符串；取不到给 "unknown"）
 *   has("clock")       装配过时钟（本 jar 里真的有那份代码）⇒ true
 *   has("gravity")     重力的装配点类真的在本 jar 里、可解析 ⇒ true
 *   has("purge")       逐级抹除阶梯类在本 jar 里、可解析 ⇒ true（阶段 3b 搬进来之后）
 *   purgeAll(actor)    真的跑 L1→L6 阶梯，返回抹除数（&gt;= 0）；拿不到服务端 / 抛异常 ⇒ -1 让本体回退
 * </pre>
 * 🔴 {@link #has(String)} 的语义是"<b>这个功能真的能用</b>"，不是"这个键存在"。所以三个能力位都<b>不是</b>
 * 写死的 {@code true}，而是由 {@link ShanhaiAdd} 的构造器按实际装配结果灌进来的
 * （时钟 = {@code ShanhaiClockInit.init()} 没抛；重力/逐级抹除 = 对应的装配点类解析成功）。
 * 运行时更细的证据在日志里：{@code [SHANHAI-CLOCK] 世界时钟已装配} / {@code [SHANHAI-GRAVITY] bootstrap_done}
 * / {@code [SHANHAI-GUARD] add_command_registered …} + {@code purge_channel=L1|L3|L4|L5|L6}。
 */
public final class AddBridge implements com.shanhai.api.ShanhaiAdd {

    /** 版本号取不到时的占位（<b>不</b>回落成写死的版本串：活日志里印假数字是本工程的血账之一）。 */
    public static final String UNKNOWN_VERSION = "unknown";

    private static final Logger LOGGER = LogUtils.getLogger();

    /** 时钟是否装配成功（{@link ShanhaiAdd} 构造器写入；{@code volatile} 因为读可能在别的线程）。 */
    private volatile boolean clockReady;

    /** 重力装配点是否就位（同上）。 */
    private volatile boolean gravityReady;

    /** 逐级抹除阶梯类是否在本 jar 里、可解析（同上；阶段 3b 起为真）。 */
    private volatile boolean purgeReady;

    /** 由 {@link ShanhaiAdd} 在 {@code install} 之前写入（只写一次，之后只读）。 */
    void setClockReady(boolean value) {
        this.clockReady = value;
    }

    /** 由 {@link ShanhaiAdd} 在 {@code install} 之前写入（只写一次，之后只读）。 */
    void setGravityReady(boolean value) {
        this.gravityReady = value;
    }

    /** 由 {@link ShanhaiAdd} 在 {@code install} 之前写入（只写一次，之后只读）。 */
    void setPurgeReady(boolean value) {
        this.purgeReady = value;
    }

    /**
     * 扩展自己的 mod 版本号。
     *
     * <p>取法与本体 {@code ShanhaiRecipeStats#modVersion} 同一套
     * （{@code ModList.get().getModContainerById(...).map(c -> c.getModInfo().getVersion().toString())}）：
     * 版本串的唯一真源是 {@code META-INF/mods.toml} 里 {@code ${mod_version}} 展开后的值，
     * 代码里不出现任何硬编码版本。
     *
     * @return 版本串（例如 {@code 0.1.0}）；取不到时 {@link #UNKNOWN_VERSION}
     */
    @Override
    public String modVersion() {
        try {
            final ModList mods = ModList.get();
            if (mods == null) {
                return UNKNOWN_VERSION;
            }
            final String raw = mods.getModContainerById(ShanhaiAdd.MOD_ID)
                    .map(container -> container.getModInfo().getVersion().toString())
                    .orElse(UNKNOWN_VERSION);
            return raw == null || raw.isEmpty() ? UNKNOWN_VERSION : raw;
        } catch (Throwable t) {
            // 版本号是"日志里的读数列"，取不到绝不许影响命令/启动
            return UNKNOWN_VERSION;
        }
    }

    /**
     * 某个功能现在真的能用吗。
     *
     * <p>阶段 3b：{@link #FEATURE_CLOCK} / {@link #FEATURE_GRAVITY} / {@link #FEATURE_PURGE} 一律由<b>实际装配结果</b>
     * 决定（{@link ShanhaiAdd} 构造器写入能力位），未知键一律 {@code false}。
     *
     * @param feature 见 {@link #FEATURE_PURGE} / {@link #FEATURE_CLOCK} / {@link #FEATURE_GRAVITY}
     */
    @Override
    public boolean has(String feature) {
        if (feature == null) {
            return false;
        }
        if (FEATURE_CLOCK.equals(feature)) {
            return clockReady;
        }
        if (FEATURE_GRAVITY.equals(feature)) {
            return gravityReady;
        }
        if (FEATURE_PURGE.equals(feature)) {
            // 阶段 3b 起：逐级阶梯（L1→L6）就在本 jar 里，且装配时已确认可解析
            return purgeReady;
        }
        // 未知键：不许谎报
        return false;
    }

    /**
     * 🔴 <b>逐级抹除</b>（{@code ShanhaiPurge} 阶梯 L1→L6）—— 阶段 3b 起<b>真的执行</b>。
     *
     * <p>本体 {@code /shanhai purge} 拿到 {@code >= 0} 的返回值就<b>不再回退</b> {@code kill @e}；
     * 拿到 {@link #NOT_IMPLEMENTED}（{@code -1}）才回退（{@code com.shanhai.api.PurgeRoute} 的五条分支）。
     * 所以这里只有<b>两种</b>合法返回：
     * <pre>
     *   &gt;= 0  真的执行完了（0 也是有效结果 = 这一轮没有可抹的实体）
     *   -1     这一版接不了 / 服务端拿不到 / 内部抛异常 ⇒ 让本体去 kill @e（绝不许"返回 0 假装做完了"）
     * </pre>
     *
     * <p>🔴 <b>dryRun 语义</b>：接口这一版<b>没有</b> dryRun 参数（阶段 2 定的签名，本阶段不改）。
     * {@code /shanhai purge count} 那种"只数不抹"一律由本体自己遍历（{@code PurgeRoute} 的
     * {@code dry_run} 分支<b>根本不调本方法</b>）⇒ 这里被调用 = 一定可以真删。
     *
     * @param actor 播报收件人（可为 {@code null}：控制台执行时只进日志，服务端从
     *              {@link ServerLifecycleHooks#getCurrentServer()} 取）
     * @return 真的抹掉了几只；接不了/出错时 {@link #NOT_IMPLEMENTED}
     */
    @Override
    public int purgeAll(ServerPlayer actor) {
        try {
            final MinecraftServer server = actor != null
                    ? actor.getServer()
                    : ServerLifecycleHooks.getCurrentServer();
            if (server == null) {
                // 拿不到服务端 ⇒ 让本体回退（它自己会 warn）。绝不返回 0 假装"一只都没有"
                LOGGER.warn("[SHANHAI-ADD] purge_via=ladder skipped reason=no_server actor={}",
                        actor == null ? "console" : actor.getName().getString());
                return NOT_IMPLEMENTED;
            }
            final ShanhaiPurge.Result result = ShanhaiPurge.purgeAll(server, actor, false);
            return result.removed;
        } catch (Throwable t) {
            // 扩展坏了不许把本体那条命令弄失败（"装了扩展反而更弱"是最不该出现的行为）
            LOGGER.error("[SHANHAI-ADD] purge_via=ladder threw ⇒ 本体回退 kill@e（这一次的抹除数以本体为准）", t);
            return NOT_IMPLEMENTED;
        }
    }
}
