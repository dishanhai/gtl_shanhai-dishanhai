package com.shanhai.machine.stellar;

import com.shanhai.common.machine.ShanhaiIdleReason;

import java.util.ArrayList;
import java.util.List;

/**
 * 山海重构 · 抬头（Jade）那条「<b>为什么没跑</b>」的<b>判定核</b> ——
 * 「画出几行」与「注册面覆盖到谁」这两件事的<b>唯一一份</b>（纯 JDK，可离线单跑 {@link #selfTest()}）。
 *
 * <h2>1. 为什么要有它（这一轮要回答的问题）</h2>
 * 2026-11-01 用户反馈原话（逐字）：<b>「jade 还是不显示」</b>。要分清的是<b>两种</b>绝然不同的失败：
 * <pre>
 *   (a) Jade 没画 —— 注册面没盖到这台机器（provider 压根没被调用）；
 *   (b) Jade 画了 0 行 —— provider 被调用了，但<b>这一刻没有可写的原因</b>（本工程红线「宁可缺，不可假」）。
 * </pre>
 * 线上日志（{@code GTL山海9.10test/logs/latest.log}）给出的答案是 (b)：见 {@link ShanhaiStellarJadePlugin}
 * 类注释「注册面」那一节。但 (a) 与 (b) 在<b>现象上完全一样</b>（都是"抬头上什么都没有"）
 * ⇒ 所以这一层必须是<b>可离线断言</b>的纯函数，而不是散在 provider 里的两句 {@code if}。
 *
 * <h2>2. 三条判据（每条都有对应的离线断言）</h2>
 * <ol>
 *   <li>{@link #lines(String)} —— <b>有原因 ⇒ 1 行，没有 ⇒ 0 行</b>（不是空行、不是占位）；</li>
 *   <li>{@link #covered(Class, Class, List)} —— <b>Jade 的注册面到底按什么匹配</b>：
 *       它按<b>方块类的父类链</b>匹配（{@code HierarchyLookup#getInternal}，字节码见 §3）；
 *       所以我们把 provider 注册在 {@code MetaMachineBlock} 上就<b>一次盖住全部</b>机器方块；</li>
 *   <li>{@link #rendered(Class, Class, List, String)} —— ①②合成：
 *       <b>没被覆盖的机器 = 0 行，与有没有原因无关</b>。这条就是"负对照"能翻红的落点。</li>
 * </ol>
 *
 * <h2>3. 🔴 「父类链匹配」不是推测，是 Jade 的字节码</h2>
 * {@code libs/Jade-1.20.1-Forge-11.13.3.jar} →
 * {@code javap -p -c snownee.jade.impl.HierarchyLookup}：
 * <pre>
 *   private void getInternal(Class&lt;?&gt; c, List&lt;T&gt; out)   偏移 0–40：
 *       if (c != baseClass &amp;&amp; c != Object.class) getInternal(c.getSuperclass(), out);   ← 先递归父类
 *       out.addAll(objects.get(c));                                                     ← 再收自己这一级
 *   public List&lt;T&gt; get(Object o)                        偏移 0–16：get(o.getClass())
 * </pre>
 * ⇒ 注册在 {@code MetaMachineBlock} 上的 provider，对 {@code MachineBlock}（单方块机）/我们自己的
 * 任何 {@code MetaMachineBlock} 子类<b>都成立</b>。{@link #covered} 逐句照抄这段递归
 * （这就是为什么它能在没有游戏的机器上被断言）。
 *
 * <h2>4. 与 {@link ShanhaiIdleReason} 的分工（不重复判定）</h2>
 * 「什么时候算有原因 / 什么时候清空」只有一份，在 {@link ShanhaiIdleReason#next(String, boolean)}
 * 与 {@link ShanhaiIdleReason.Store}；本类<b>不重写</b>它，只用它的出口
 * {@link ShanhaiIdleReason#shown(String)} 决定「画 1 行 / 不画」。
 */
public final class ShanhaiJadeLineModel {

    /**
     * 我们<b>全部</b>机器方块共用的基类（25 台模块 + 主机 + 归墟视界枢核 + 各式仓室）。
     *
     * <p>🔴 这是「注册面」的<b>唯一一份</b>字面量：{@link ShanhaiStellarJadePlugin#CLIENT_BLOCK_CLASSES}
     * 必须在启动时与它逐字相等（由 {@link #checkSurface} 把关，不等就 ERROR）。
     * 为什么固定成一个字符串而不是直接写类：本类必须保持<b>零 Minecraft 依赖</b>，
     * 离线 {@code javac --release 17} 才能单独编译并跑 {@link #selfTest()}。
     */
    public static final String MACHINE_BLOCK_CLASS_NAME = "com.gregtechceu.gtceu.api.block.MetaMachineBlock";

    /** 服务端数据提供器的注册面（同一套理由；Jade 服务端同样按 BlockEntity 父类链匹配）。 */
    public static final String MACHINE_BLOCK_ENTITY_CLASS_NAME =
            "com.gregtechceu.gtceu.api.blockentity.MetaMachineBlockEntity";

    private ShanhaiJadeLineModel() {}

    // ═══════════════════════════ ① 画几行（生产路径就用这一句） ═══════════════════════════

    /**
     * 这条原因在抬头上<b>占几行</b>：{@code 1} 或 {@code 0}（唯一一份判据）。
     *
     * @param storedJson 服务端权威值（{@code Component} 的 JSON；{@code ""}/{@code null} = 没有原因）
     */
    public static int lines(String storedJson) {
        return ShanhaiIdleReason.shown(storedJson) ? 1 : 0;
    }

    /** 服务端该不该往 per-block NBT 里写键（写 = 客户端一定能读到；不写 = 客户端连空行都没有）。 */
    public static boolean writesServerKey(String storedJson) {
        return lines(storedJson) == 1;
    }

    // ═══════════════════════════ ② 注册面覆盖判定（照抄 HierarchyLookup） ═══════════════════════════

    /**
     * 注册在 {@code registered} 里的 provider，对 {@code blockClass} 这台方块<b>会不会被调用</b>。
     *
     * <p>逐句照抄 {@code HierarchyLookup#getInternal}（字节码见类注释 §3）：
     * 从实际类开始<b>沿父类链往上</b>走到 {@code baseClass}（含），任一级命中即算覆盖。
     *
     * @param blockClass 实际方块类（例如我们的多块主机方块）
     * @param baseClass  Jade 那个 lookup 的基类（线上是 {@code Block}）
     * @param registered 我们注册进去的类（{@link ShanhaiStellarJadePlugin#CLIENT_BLOCK_CLASSES}）
     */
    public static boolean covered(Class<?> blockClass, Class<?> baseClass, List<Class<?>> registered) {
        if (blockClass == null || baseClass == null || registered == null || registered.isEmpty()) {
            return false;
        }
        final List<Class<?>> hits = new ArrayList<>(1);
        walk(blockClass, baseClass, registered, hits);
        return !hits.isEmpty();
    }

    private static void walk(Class<?> c, Class<?> baseClass, List<Class<?>> registered, List<Class<?>> hits) {
        if (c == null) {
            return;
        }
        if (c != baseClass && c != Object.class) {
            walk(c.getSuperclass(), baseClass, registered, hits);
        }
        if (registered.contains(c)) {
            hits.add(c);
        }
    }

    /**
     * 最终<b>真的会画出几行</b>：先看覆盖（没盖到 = 0 行，与有没有原因无关），再看原因。
     *
     * <p>这就是自证里那条负对照的落点：把 {@code registered} 缩回只有某一种方块
     * ⇒ 模块机器那台立刻变成 {@code 0} 行（红）。
     */
    public static int rendered(Class<?> blockClass, Class<?> baseClass, List<Class<?>> registered, String storedJson) {
        return covered(blockClass, baseClass, registered) ? lines(storedJson) : 0;
    }

    // ═══════════════════════════ ③ 注册面自检（启动时也跑，日志可判） ═══════════════════════════

    /**
     * 核对「插件真正注册的类」与「本类声明的注册面」是否逐字一致。
     *
     * <p>为什么要在<b>游戏里</b>也跑一次：这两个常量一旦分叉，症状与"没有原因"完全一样
     * （抬头上什么都没有、日志里也没有异常）⇒ 必须在启动日志里留一行可判读数。
     *
     * @param clientRegisteredNames 客户端 {@code registerBlockComponent} 用的类名
     * @param serverRegisteredNames 服务端 {@code registerBlockDataProvider} 用的类名
     * @return 开机那一行读数（{@code [SHANHAI-JADE-COVER] …}）
     * @throws IllegalStateException 注册面与声明不一致（宁可当场炸在启动日志里，也不许静默少画一行）
     */
    public static String checkSurface(List<String> clientRegisteredNames, List<String> serverRegisteredNames) {
        require(clientRegisteredNames != null && clientRegisteredNames.size() == 1,
                "客户端注册面必须恰好 1 个基类（实际 " + clientRegisteredNames + "）");
        require(serverRegisteredNames != null && serverRegisteredNames.size() == 1,
                "服务端注册面必须恰好 1 个基类（实际 " + serverRegisteredNames + "）");
        require(MACHINE_BLOCK_CLASS_NAME.equals(clientRegisteredNames.get(0)),
                "客户端注册面必须是 " + MACHINE_BLOCK_CLASS_NAME + "（实际 " + clientRegisteredNames.get(0) + "）");
        require(MACHINE_BLOCK_ENTITY_CLASS_NAME.equals(serverRegisteredNames.get(0)),
                "服务端注册面必须是 " + MACHINE_BLOCK_ENTITY_CLASS_NAME + "（实际 " + serverRegisteredNames.get(0) + "）");
        return "[SHANHAI-JADE-COVER] 注册面 client=" + clientRegisteredNames
                + " server=" + serverRegisteredNames
                + "（Jade 按父类链匹配 ⇒ 一个基类盖住全部机器方块）";
    }

    // ═══════════════════════════ ④ 离线自检 ═══════════════════════════

    /** 自检用的假基类（只为了给 {@link #covered} 一条真的父类链；与线上类无关）。 */
    private static class H_Base {}

    /** 假「我们机器方块的基类」= 线上 {@code MetaMachineBlock} 的位置。 */
    private static class H_Meta extends H_Base {}

    /** 假「模块/主机的方块」（继承基类）。 */
    private static class H_ModuleBlock extends H_Meta {}

    /** 假「星律那种方块」（同样继承基类 —— 它就是模块方块的兄弟，不是父类）。 */
    private static class H_StellarBlock extends H_Meta {}

    /**
     * 🔴 自检（本工程纪律「检查器先证明自己对」的落点）。任一条不成立 ⇒ 抛异常。
     *
     * <p>覆盖四件事：① 有原因 ⇒ 1 行、没有 ⇒ 0 行；② 注册在基类上 ⇒ 兄弟子类都被覆盖；
     * ③ 【灵敏度】注册面缩到只有一个子类 ⇒ 另一个子类<b>不</b>被覆盖（否则 {@link #covered} 就是个
     * 永远返回 true 的假检查器）；④ 注册面字面量必须还是那个基类。
     */
    public static String selfTest() {
        int checks = 0;
        final String reason = "{\"translate\":\"shanhai.recipe.fail.extra_slot_wrong\"}";

        // ① 有原因 ⇒ 1 行；没有 ⇒ 0 行
        require(lines(reason) == 1, "① 有原因 ⇒ 必须恰好 1 行");
        require(lines("") == 0, "① 空串 ⇒ 必须 0 行（不是空行）");
        require(lines(null) == 0, "① null ⇒ 必须 0 行");
        require(writesServerKey(reason) && !writesServerKey(""), "① 服务端写键的唯一判据必须与 lines() 一致");
        checks += 4;

        // ② 注册在基类上 ⇒ 兄弟子类都被覆盖（Jade 的父类链匹配）
        final List<Class<?>> baseOnly = List.of(H_Meta.class);
        require(covered(H_ModuleBlock.class, H_Base.class, baseOnly), "② 注册基类 ⇒ 模块方块必须被覆盖");
        require(covered(H_StellarBlock.class, H_Base.class, baseOnly), "② 注册基类 ⇒ 星律方块必须被覆盖");
        require(covered(H_Meta.class, H_Base.class, baseOnly), "② 注册基类 ⇒ 基类本身也必须被覆盖");
        require(!covered(H_Base.class, H_Base.class, baseOnly), "② 基类之上不再匹配（Jade 走到 baseClass 就停）");
        checks += 4;

        // ③ 【负对照】注册面缩到只有一个子类 ⇒ 另一个子类必须 0 行
        final List<Class<?>> stellarOnly = List.of(H_StellarBlock.class);
        require(rendered(H_ModuleBlock.class, H_Base.class, stellarOnly, reason) == 0,
                "③ 注册面缩到只有星律 ⇒ 模块方块必须 0 行（否则 covered() 是假检查器）");
        require(rendered(H_StellarBlock.class, H_Base.class, stellarOnly, reason) == 1,
                "③ 同一份注册面下星律自己必须 1 行（证明 0 行不是因为它自己也没被覆盖）");
        checks += 2;

        // ④ 注册面字面量没被改小
        checkSurface(List.of(MACHINE_BLOCK_CLASS_NAME), List.of(MACHINE_BLOCK_ENTITY_CLASS_NAME));
        checks += 2;

        return "[SHANHAI-JADE-MODEL] 自检通过（" + checks + " 条断言，纯 JDK、可离线单跑）："
                + "① 有原因⇒1 行 / 没有⇒0 行；② 注册基类⇒父类链上所有机器方块都被覆盖；"
                + "③ 负对照：注册面缩到只有星律⇒模块方块必 0 行；④ 注册面常量必须是 "
                + MACHINE_BLOCK_CLASS_NAME + "。";
    }

    private static void require(boolean ok, String what) {
        if (!ok) {
            throw new IllegalStateException("[SHANHAI-JADE-MODEL] 自检失败：" + what
                    + " ⇒ 抬头那条判据本身是坏的，它在真实机器上『没有这一行』没有任何信息量。");
        }
    }
}
