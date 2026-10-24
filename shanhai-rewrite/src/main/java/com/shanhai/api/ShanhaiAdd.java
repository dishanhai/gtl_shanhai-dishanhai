package com.shanhai.api;

import net.minecraft.server.level.ServerPlayer;

/**
 * 山海扩展（{@code shanhai_add}）暴露给本体（{@code shanhai}）的<b>唯一</b>能力面。
 *
 * <h2>依赖方向（🔴 单向，不许反过来）</h2>
 * <pre>
 *   add  ──编译期依赖──▶  body   ：add 的 build.gradle 里 {@code compileOnly files('../shanhai-rewrite/build/libs/shanhai-<ver>.jar')}
 *   body ──✗ 不依赖 ──▶  add    ：本包（{@code com.shanhai.api}）一行都不许 import 扩展（{@code shanhai_add}）的任何类
 * </pre>
 * 反过来写的代价是<b>加载期</b>就炸，不是运行到那一行才炸：只装本体时第一次触碰 add 的类就是
 * {@code NoClassDefFoundError}；{@code @Mod.EventBusSubscriber} 类更是加载期直接失败。
 * 本仓有真实案例：{@code client\guard\ShanhaiGuardClientEvents} 类注释记的
 * {@code Attempted to load class net/minecraft/client/player/LocalPlayer for invalid dist DEDICATED_SERVER}。
 *
 * <h2>形态：本体定义接口 ＋ add 在自己 {@code @Mod} 构造器里注册实现</h2>
 * 为什么不用"先 {@code ModList.isLoaded} 再反射调 add 的类"：{@code isLoaded} 只能回答
 * "装没装"，回答不了"怎么调"；反射调 add 的静态方法一旦签名漂了，只会在运行期
 * {@code NoSuchMethodError}，而接口写错在 add 侧<b>编译期</b>就红（设计 §6.1）。
 *
 * <p>注册点唯一：{@link ShanhaiAddHolder#install(ShanhaiAdd)}，由扩展（{@code shanhai_add}）自己的
 * {@code @Mod} 入口构造器调用；本体只通过 {@link ShanhaiAddHolder#optional()} 取，
 * 取到 {@code null} = 没装 add（<b>不是</b>异常）。
 *
 * <h2>本类刻意保持"零 MC 依赖尽量少"</h2>
 * 只有 {@link #purgeAll(ServerPlayer)} 一个参数用到 MC 类型（{@code ServerPlayer}）。
 * 判定/回退那部分（"该走扩展还是走本体自己的 kill@e"）被单独放进
 * {@link PurgeRoute}，那一个类<b>零 MC、零 Forge</b>，可以用纯 JDK 跑值表验证。
 */
public interface ShanhaiAdd {

    /**
     * {@link #purgeAll(ServerPlayer)} 的哨兵：<b>本阶段没实现</b> ⇒ 调用方必须回退成本体的
     * {@code com.shanhai.common.guard.ShanhaiPurgeSimple}（设计 §3.3 / §3.4）。
     *
     * <p>为什么用返回码而不是抛异常：缺一个能力是<b>正常状态</b>（阶段 3 才把逐级抹除搬进来），
     * 让正常状态走 try/catch 会把"真的出错"和"还没做"混成一条日志。
     */
    int NOT_IMPLEMENTED = -1;

    /** {@link #has(String)} 的键：逐级抹除（扩展里的移除阶梯 L1→L6）。 */
    String FEATURE_PURGE = "purge";
    /** {@link #has(String)} 的键：世界时钟（暂停/倒流/加速）。 */
    String FEATURE_CLOCK = "clock";
    /** {@link #has(String)} 的键：重力调制。 */
    String FEATURE_GRAVITY = "gravity";

    /**
     * 扩展自己的 mod 版本号（真值来自 {@code ModList} / {@code ModContainer}，<b>不是</b>写死的字符串）。
     *
     * @return 版本串；取不到时返回一个<b>明确表示取不到</b>的占位（{@code "unknown"}），绝不回落成写死的版本号
     */
    String modVersion();

    /**
     * 这个功能<b>现在真的能用</b>吗（不是"这个 jar 里有这个类"）。
     *
     * <p>🔴 阶段 2 的实现（扩展里那个 {@code AddBridge}）对<b>任何</b> feature 都返回 {@code false}：
     * 功能本身还没搬进扩展。阶段 3/4 每搬完一件，才把对应键改成 {@code true}。
     *
     * @param feature 见 {@link #FEATURE_PURGE} 等常量；未知键一律 {@code false}
     */
    boolean has(String feature);

    /**
     * 🔴 <b>清除全服实体</b>（逐级版，扩展里的移除阶梯）—— 与本体 {@code /shanhai purge} 同一个入口。
     *
     * <p>安全默认必须与本体一致：<b>跳过所有玩家</b>（含执行者自己）。
     *
     * @param actor 播报收件人（可为 {@code null}：控制台执行时只进日志）
     * @return 真的抹掉了几只；<b>{@link #NOT_IMPLEMENTED}（-1）表示这一版没实现</b>
     *         ⇒ 调用方据此回退到 {@code ShanhaiPurgeSimple}，并且<b>不许</b>把 -1 当成"抹掉了 -1 只"
     */
    int purgeAll(ServerPlayer actor);
}
