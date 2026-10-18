package com.shanhai.add.time;

import java.lang.reflect.Method;

/**
 * 山海重构 · <b>暂停期间「AE 终端里物品进出玩家背包」的拦截核 + 挂载自证</b>
 * （🔴 纯 JDK，零 Minecraft / 零 AE2 依赖，别加 import）。
 *
 * <h2>1. 为什么 AE 必须单独再挂一层</h2>
 * 原版容器点击的服务端唯一漏斗是
 * {@code ServerboundContainerClickPacket → ServerGamePacketListenerImpl#handleContainerClick → AbstractContainerMenu#clicked}
 * （上一版已拦，见 {@code ShanhaiContainerPacketClockMixin}）。但 AE2 15.4.10 的 ME 终端
 * <b>根本不发那个包</b>，它用自己的一对包进服务端：
 * <pre>
 *   appeng.core.sync.packets.MEInteractionPacket
 *     public void serverPacketData(ServerPlayer)                  ← javap 实例
 *       → (containerMenu instanceof IMEInteractionHandler)
 *       → IMEInteractionHandler.handleInteraction(long, InventoryAction)
 *       → MEStorageMenu.handleInteraction(long, InventoryAction)  → handleNetworkInteraction(...)
 *
 *   appeng.core.sync.packets.InventoryActionPacket
 *     public void serverPacketData(ServerPlayer)                  ← javap 实例
 *       → AEBaseMenu.doAction(ServerPlayer, InventoryAction, int, long)
 * </pre>
 *
 * <h2>2. 🔴 取证更正：**取/放走的是 {@code MEInteractionPacket}，不是 {@code InventoryActionPacket}**</h2>
 * <b>javap 原文</b>（{@code libs\appliedenergistics2-forge-15.4.10.jar}）：
 * <ul>
 *   <li>{@code InventoryActionPacket#serverPacketData} 体内只有两跳：
 *       {@code AEBaseMenu.setFilter(int, ItemStack)}（仅当 action==SET_FILTER）与
 *       {@code AEBaseMenu.doAction(...)}（其余）—— <b>它不调 {@code handleInteraction}</b>；</li>
 *   <li>{@code MEStorageMenu#handleInteraction} 的<b>唯一</b>调用者（全 jar 扫常量池）是
 *       {@code MEInteractionPacket} 与客户端 {@code MEStorageScreen}。</li>
 * </ul>
 * 而客户端 {@code MEStorageScreen.slotClicked} 里所有"点网络格子"的分支（拿起/单放/整放/Shift 搬整叠/
 * 拖区域/自动合成）<b>全部</b>调 {@code MEStorageMenu.handleInteraction(serial, action)}
 * （客户端侧该方法的第一句就是 {@code if (isClientSide()) sendToServer(new MEInteractionPacket(...))}）。
 * ⇒ 只挂在 {@code InventoryActionPacket} 上会<b>照样拿得出来</b>（这正是"ae 还是能拿出东西来"没被上一版拦住的原因）。
 * ⇒ 所以本工程的注入点有<b>三个</b>（见 {@code shanhai.ae2.mixin.json}），任一条生效即可拦：
 * <ol>
 *   <li>{@code MEInteractionPacket#serverPacketData} —— 真正的主钩（取/放都在这里）；</li>
 *   <li>{@code InventoryActionPacket#serverPacketData} —— 另一条 action 包（配置槽/合成格/填充手持物）；</li>
 *   <li>{@code MEStorageMenu#handleInteraction} —— 纵深：不经过上面两个包也会过这个 public 入口；
 *       ⚠️ 它<b>只在能定位到服务端玩家时</b>才取消（客户端那一份故意放行，否则玩家的黄字提示会消失，
 *       见 {@code ShanhaiClockHooks#denyAeMenuInteraction} 方法头）。</li>
 * </ol>
 *
 * <h2>3. 判定（整个 AE 拦截只有这一格真值）</h2>
 * <b>暂停 且 当前菜单不是玩家自己的 {@code InventoryMenu} ⇒ 拒</b>；取与放<b>对称</b>。
 * 判定的实现就在 {@link ShanhaiClockRules#denyContainerPacket} —— 与"原版容器点击包"那一条
 * <b>同一份代码</b>，没有第二套口径（本工程最怕的就是两个地方各判一次、极性写反）。
 *
 * <p>🔴 刻意<b>不看方向</b>：上一版的病正是"取"和"放"走了两条不同路径（一条拦到、一条没拦到）。
 * 这里把方向做成 {@link Flow} 只用于日志/值表，<b>绝不参与判定</b>。
 *
 * <h2>4. 为什么把判定剥到这个"纯 JDK"类里</h2>
 * 本工程的硬纪律："不许构建、不许进游戏"的那一轮，唯一能自证的东西就是能在裸 JDK 上跑的值表。
 * {@link #main} 用 {@code javac --release 17} + {@code java} 直接打「暂停 × 取/放」的真值表并断言
 * （含负对照：<b>未暂停时必须放行</b>、本人背包放行），游戏里跑的是<b>同一份字节码</b>。
 *
 * <h2>5. armed 自证（为什么必须有）</h2>
 * AE2 是<b>第三方 mod</b> ⇒ 三个混入全部 {@code require = 0} + 配置 {@code "required": false}
 * ⇒ 上游改名/改签名时代价只是"少一层拦截"，而<b>不是把整合包启动打崩</b>。
 * 但 {@code require = 0} 的注入失败<b>完全静默</b>：「没数据」与「没挂上」在日志上长得一模一样。
 * 所以 {@link #armedLine()} 用反射数目标类里有没有我们注入的 {@code shanhai$…} 方法
 * （Mixin 会把它改名成 {@code handler$<随机串>$shanhai$…}，所以判据是 {@code contains} 片段），
 * 由 {@code ShanhaiClockInit#onServerStarted} 打一行 {@code [SHANHAI-CLOCK] ae_hooks_armed …}。
 *
 * <p>⚠️ 反射只用 {@code Class.forName(name, false, loader)}：<b>不初始化</b>目标类
 * （初始化会提前触发 AE2 的静态块 = "改了行为"）。
 */
public final class ShanhaiAeGate {

    /**
     * 离线入口的前缀。
     *
     * <p>⚠️ <b>游戏内</b>那两行判据日志（{@code ae_hooks_armed} / {@code ae_blocked}）刻意沿用
     * {@code [SHANHAI-CLOCK]} 前缀 —— 时钟相关的全部判据行只用那一个 grep 口径，多一个前缀会让人漏捞。
     * 本常量只用于离线自检（{@code main}）的输出。
     */
    public static final String PREFIX = "[SHANHAI-AEGATE]";

    /** 一次点击的方向。<b>只进日志/值表，不参与判定</b>（见类注释 §3）。 */
    public enum Flow {
        /** 从网络往玩家背包拿（光标空着点网络行）。 */
        TAKE("取出"),
        /** 从玩家背包往网络里放（光标拿着物品点网络行）。 */
        PUT("放入");

        /** 给玩家/日志看的中文名。 */
        public final String cn;

        Flow(String cn) {
            this.cn = cn;
        }
    }

    /** 三个注入目标的类名（写死成字符串：类不在时也要能"报出来"，而不是启动崩）。 */
    public static final String C_ME_INTERACTION_PACKET = "appeng.core.sync.packets.MEInteractionPacket";
    public static final String C_INVENTORY_ACTION_PACKET = "appeng.core.sync.packets.InventoryActionPacket";
    public static final String C_ME_STORAGE_MENU = "appeng.menu.me.common.MEStorageMenu";

    /** 我们的 handler 名字片段（Mixin 会加 {@code handler$xxx$} 前缀 ⇒ 只能用 contains）。 */
    private static final String MARKER = "shanhai$";

    /**
     * 每个目标类里**必须**至少有 1 个方法同时含 {@link #MARKER} 与它自己的 handler 片段。
     *
     * <p>每一行 = <b>{目标类, 目标方法（只用于显示）, 我们的 handler 名字片段}</b>。
     * ⚠️ 匹配用的是<b>我们的 handler 名</b>，不是目标方法名：目标方法（{@code serverPacketData} /
     * {@code handleInteraction}）是 AE2 自己的方法，<b>本来就在类里</b> ⇒ 数它永远为真 = 假阳性。
     * 只有我们写在 {@code @Inject} 里的那个方法会被 Mixin 改名成 {@code handler$<随机串>$<原名>}
     * 并加进目标类 ⇒ 它的名字才是"到底混入没有"的判据。
     */
    private static final String[][] CHECKS = {
            {C_ME_INTERACTION_PACKET, "serverPacketData", "denyAeInteractionPacket"},
            {C_INVENTORY_ACTION_PACKET, "serverPacketData", "denyAeInventoryActionPacket"},
            {C_ME_STORAGE_MENU, "handleInteraction", "denyAeMenuInteraction"},
    };

    private ShanhaiAeGate() {
    }

    // ═════════════════════════════ 判定 ═════════════════════════════

    /**
     * 暂停期间是否拒绝这一笔「AE 终端 ↔ 玩家背包」的物品转移。
     *
     * <p>🔴 <b>刻意不看 {@code flow}</b>：取与放必须对称（见类注释 §3）。
     *
     * @param paused           世界时钟是否暂停
     * @param ownInventoryMenu 当前菜单是不是玩家自己的 {@code InventoryMenu}
     * @param flow             方向（只用于值表/日志；不影响返回值）
     */
    public static boolean deny(boolean paused, boolean ownInventoryMenu, Flow flow) {
        return deny(paused, ownInventoryMenu);
    }

    /**
     * 判定本体 —— <b>直接复用原版容器点击包那一条规则</b>（{@link ShanhaiClockRules#denyContainerPacket}），
     * 保证"原版容器 / AE 终端"两处不会各写一套极性。
     *
     * @param paused           世界时钟是否暂停
     * @param ownInventoryMenu 当前菜单是不是玩家自己的 {@code InventoryMenu}
     */
    public static boolean deny(boolean paused, boolean ownInventoryMenu) {
        return ShanhaiClockRules.denyContainerPacket(paused, ownInventoryMenu);
    }

    // ═════════════════════════════ 值表 ═════════════════════════════

    /** 值表的一行。 */
    private static final class Case {

        final String scene;
        final boolean paused;
        final boolean own;
        final Flow flow;
        final boolean want;
        final String reading;

        Case(String scene, boolean paused, boolean own, Flow flow, boolean want, String reading) {
            this.scene = scene;
            this.paused = paused;
            this.own = own;
            this.flow = flow;
            this.want = want;
            this.reading = reading;
        }

        boolean got() {
            return deny(this.paused, this.own, this.flow);
        }

        boolean pass() {
            return got() == this.want;
        }
    }

    private static Case[] cases() {
        return new Case[]{
                // ── 暂停 × 取 / 放（本次要修的两格）──────────────────────────────
                new Case("暂停 · 从网络取出", true, false, Flow.TAKE, true,
                        "拦：用户实测「ae还是能拿出东西来」走的就是这一格（MEInteractionPacket）"),
                new Case("暂停 · 放回网络", true, false, Flow.PUT, true,
                        "拦：与取出对称；上一版看起来只能取不能放，是因为「拿起」那一步先被原版点击拦住了"),
                // ── 负对照：未暂停必须放行 ────────────────────────────────────────
                new Case("未暂停 · 从网络取出", false, false, Flow.TAKE, false,
                        "负对照：未暂停必须放行（否则暂停功能就把正常游戏玩坏了）"),
                new Case("未暂停 · 放回网络", false, false, Flow.PUT, false,
                        "负对照：未暂停必须放行"),
                // ── 负对照：玩家自己的背包（用户点名要保住"整理"）────────────────
                new Case("暂停 · 自己背包整理", true, true, Flow.TAKE, false,
                        "负对照：本人 InventoryMenu 一律放行（整理背包照常）"),
                new Case("暂停 · 自己背包丢出", true, true, Flow.PUT, false,
                        "负对照：own=true 一格不放行（丢物品由 Player#drop 那条链单独拦）"),
                new Case("未暂停 · 自己背包", false, true, Flow.TAKE, false,
                        "负对照：未暂停 + 本人背包"),
        };
    }

    /** 值表（人读版，直接可贴进报告）。 */
    public static String table() {
        final StringBuilder sb = new StringBuilder();
        sb.append("# AE 终端拦截值表 —— 判定核 = ").append(ShanhaiAeGate.class.getName()).append('\n');
        sb.append("# 判定 = ShanhaiClockRules.denyContainerPacket(paused, ownInventoryMenu) —— 与「原版容器点击包」同一份代码\n");
        sb.append("# 方向(Flow) 只进日志，不参与判定 ⇒ 取/放必然对称\n");
        sb.append(String.format("%-26s | %-6s | %-5s | %-4s | %-6s | %-4s | %s%n",
                "场景", "暂停", "own", "方向", "期望", "实际", "判读"));
        for (Case c : cases()) {
            sb.append(String.format("%-26s | %-6s | %-5s | %-4s | %-6s | %-4s | %s%n",
                    c.scene,
                    c.paused ? "是" : "否",
                    c.own ? "是" : "否",
                    c.flow.cn,
                    c.want ? "拦" : "放行",
                    c.got() ? "拦" : "放行",
                    c.reading));
        }
        return sb.toString();
    }

    /**
     * 自检：逐行比对"判定"与"期望" + 断言"取/放对称"。
     *
     * <p>对称性单独断言的理由：上一版的缺陷形态就是<b>不对称</b>（取能过、放不能过）。
     * 就算某天有人给 {@link ShanhaiClockRules#denyContainerPacket} 加了方向参数，这一条也会立刻变红。
     */
    public static String selfTest() {
        int pass = 0;
        int fail = 0;
        final StringBuilder bad = new StringBuilder();
        for (Case c : cases()) {
            if (c.pass()) {
                pass++;
            } else {
                fail++;
                bad.append("  FAIL ").append(c.scene)
                        .append(" paused=").append(c.paused)
                        .append(" own=").append(c.own)
                        .append(" flow=").append(c.flow)
                        .append(" -> got=").append(c.got())
                        .append(" want=").append(c.want).append('\n');
            }
        }
        int symFail = 0;
        for (boolean paused : new boolean[]{false, true}) {
            for (boolean own : new boolean[]{false, true}) {
                if (deny(paused, own, Flow.TAKE) != deny(paused, own, Flow.PUT)) {
                    symFail++;
                    bad.append("  FAIL symmetry paused=").append(paused).append(" own=").append(own).append('\n');
                }
            }
        }
        return "SELFTEST PASS=" + pass + " FAIL=" + fail + " sym=" + (symFail == 0 ? "PASS" : "FAIL(" + symFail + ")")
                + (fail == 0 && symFail == 0 ? " (全部一致：取/放对称，未暂停全放行)" : "\n" + bad);
    }

    // ═════════════════════════════ armed 自证 ═════════════════════════════

    /**
     * 「三个 AE 混入到底挂上了没有」的机器实证（日志可 grep {@code ae_hooks_armed}）。
     *
     * <p>输出形如：
     * <pre>
     * ae_hooks_armed MEInteractionPacket.serverPacketData=true(found=1/want>=1)
     *   InventoryActionPacket.serverPacketData=true(found=1/want>=1)
     *   MEStorageMenu.handleInteraction=true(found=1/want>=1)
     * </pre>
     * 三种 =false 的含义可分开：
     * <ul>
     *   <li>{@code found=0/want>=1,CLASSNOTFOUND} ⇒ 类根本没加载（AE2 不在 / 类被改名）；</li>
     *   <li>{@code found=0/want>=1} ⇒ 类在，但我们那个 handler 一个都没有 ⇒ 注入全失败；</li>
     *   <li>{@code found=1/want>=1} ⇒ 挂上了。</li>
     * </ul>
     */
    public static String armedLine() {
        final StringBuilder sb = new StringBuilder("ae_hooks_armed");
        for (String[] chk : CHECKS) {
            final String className = chk[0];
            final String targetMethod = chk[1];
            final String handlerToken = chk[2];
            final Class<?> c = load(className);
            int found = 0;
            if (c != null) {
                try {
                    for (Method m : c.getDeclaredMethods()) {
                        if (m.getName().contains(MARKER) && m.getName().contains(handlerToken)) {
                            found++;
                        }
                    }
                } catch (Throwable ignored) {
                    // 反射被挡 ⇒ 当作没挂上（宁可报红的）
                }
            }
            sb.append(' ').append(simpleName(className)).append('.').append(targetMethod)
                    .append('=').append(found >= 1)
                    .append("(found=").append(found).append("/want>=1")
                    .append(c == null ? ",CLASSNOTFOUND" : "").append(')');
        }
        return sb.toString();
    }

    /** {@link #armedLine()} 是否全 true（给别处判读用；不参与任何业务判定）。 */
    public static boolean armedAll() {
        for (String[] chk : CHECKS) {
            final Class<?> c = load(chk[0]);
            if (c == null) {
                return false;
            }
            boolean hit = false;
            try {
                for (Method m : c.getDeclaredMethods()) {
                    if (m.getName().contains(MARKER) && m.getName().contains(chk[2])) {
                        hit = true;
                        break;
                    }
                }
            } catch (Throwable ignored) {
                return false;
            }
            if (!hit) {
                return false;
            }
        }
        return true;
    }

    /** @return 目标类；{@code null} = 没加载到（AE2 缺席 / 类名变了），绝不抛。 */
    private static Class<?> load(String className) {
        try {
            // false = 只加载、不初始化（不提前触发 AE2 的静态块）
            return Class.forName(className, false, ShanhaiAeGate.class.getClassLoader());
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String simpleName(String className) {
        final int i = className.lastIndexOf('.');
        return i < 0 ? className : className.substring(i + 1);
    }

    // ═════════════════════════════ 离线入口 ═════════════════════════════

    /**
     * 纯 JDK 入口：{@code java -cp <classes> com.shanhai.add.time.ShanhaiAeGate}。
     *
     * <p>打印值表 + 自检结论 + armed 行（<b>游戏外应当看到 CLASSNOTFOUND</b> —— 这是对的，
     * 它恰好证明这条自证行不会在"没挂上"的时候谎报 true）。自检不过时退出码 = 1。
     */
    public static void main(String[] args) {
        System.out.print(table());
        final String st = selfTest();
        System.out.println(st);
        System.out.println("# 游戏外跑这一行必然不是 true，属正常（说明 armed 行不会谎报）：");
        System.out.println(PREFIX + " " + armedLine() + " armedAll=" + armedAll());
        if (st.contains("FAIL=0") && st.contains("sym=PASS")) {
            System.out.println("RESULT=OK");
        } else {
            System.out.println("RESULT=BAD");
            System.exit(1);
        }
    }
}
