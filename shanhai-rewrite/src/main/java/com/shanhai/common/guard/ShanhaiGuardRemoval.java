package com.shanhai.common.guard;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.PrintStream;

/**
 * 山海 · 创始现实修改模块「三件套」的<b>★纯逻辑核★</b>（拦实体移除 ／ 攻击端抹除阶梯 ／ 死而无损）。
 *
 * <h2>为什么又开一个纯 JDK 类，而不是塞进 {@link ShanhaiGuardCore}</h2>
 * <ul>
 *   <li>{@code ShanhaiGuardCore} 已经有 2900 行、并且它的 {@code main} 里有一套<b>带编号的判据</b>
 *       （改它的读数会让"改前红 → 改后绿"这种自证失效）；</li>
 *   <li>本轮这几件事是<b>新功能</b>，判据可以独立成表 ⇒ 离线 {@code javac} 单跑、也能独立数条数；</li>
 *   <li><b>只 import {@code java.*}</b>（＋同包的 {@link ShanhaiGuardCore.Decision}，它也是纯 JDK）
 *       ⇒ 零 Forge、零 Minecraft，空 classpath 也能编译与单跑。</li>
 * </ul>
 *
 * <h2>本类回答三个问题（执行体分别在哪）</h2>
 * <pre>
 *   ① 这次 setRemoved(reason) 要不要拦下？        ⇒ 执行体 ShanhaiGuardEntityGuard ／ mixin
 *   ② 抹除阶梯走到第几级了、该播什么？            ⇒ 执行体 ShanhaiPurge
 *   ③ 死亡兜底要还原哪几项？                      ⇒ 执行体 ShanhaiDeathlessRestore
 * </pre>
 *
 * <h2>⚠️ 本轮删掉的一节（如实记录）</h2>
 * 原第 ④ 节「全息板『现实抹除』两拍确认」已<b>整节移除</b>（连同 {@code CONFIRM_WINDOW_NANOS} ／
 * {@code CONFIRM_PROMPT} ／ {@code CONFIRM_EXPIRED} ／ {@code isFirstClick} 与它的 6 条自检断言）——
 * 用户点单：那一格现在叫「清除实体」、<b>点一次直接执行</b>。两拍机制一个字都不留（不留死代码）。
 *
 * <h2>🔴 白名单不是"客气"，是防卡死</h2>
 * {@code Entity#setRemoved(RemovalReason)} 是<b>换维度 / 退服 / 区块卸载</b>的必经之路
 * （{@code ServerPlayer#changeDimension} → {@code removePlayerImmediately(CHANGED_DIMENSION)}；
 * {@code PlayerList#remove} → {@code removePlayerImmediately(UNLOADED_WITH_PLAYER)}），
 * 全拦会把玩家钉死在原地、退服直接卡死。所以白名单四条：
 * <pre>
 *   CHANGED_DIMENSION         换维度
 *   UNLOADED_TO_CHUNK         区块卸载（还会回来）
 *   UNLOADED_WITH_PLAYER      ＋ 玩家确实已断开（hasDisconnected / connection 为空）
 *   服务器正在关闭            （MinecraftServer#isRunning()=false 或 isStopped()=true）
 * </pre>
 * ⚠️ 口径修正（如实记录）：「服务器正在关闭」需求里写的是 {@code server.isStopping()}，
 * 但 <b>1.20.1（Forge 47.4.16）的 {@code MinecraftServer} 上没有这个方法</b>（javap 逐条查过：
 * 只有 {@code isRunning()} ／ {@code isStopped()} ／ {@code stopServer()}）⇒ 本类用后者两条的或值。
 */
public final class ShanhaiGuardRemoval {

    private ShanhaiGuardRemoval() {}

    // ═══════════════════════════════ ① 拦实体移除 ═══════════════════════════════

    /** 换维度（白名单）。 */
    public static final String R_CHANGED_DIMENSION = "CHANGED_DIMENSION";
    /** 区块卸载（白名单；人还会回来）。 */
    public static final String R_UNLOADED_TO_CHUNK = "UNLOADED_TO_CHUNK";
    /** 随玩家卸载（= 退服/登出；<b>只在玩家确实已断开时</b>才放行）。 */
    public static final String R_UNLOADED_WITH_PLAYER = "UNLOADED_WITH_PLAYER";
    /** 正常死亡/`/kill`（<b>不</b>在白名单：守卫的 die 拒绝已经拦在前面，走到这里是外力）。 */
    public static final String R_KILLED = "KILLED";
    /** 丢弃/删掉（<b>不</b>在白名单：这正是"玩家删不掉"要拦的那一条）。 */
    public static final String R_DISCARDED = "DISCARDED";

    /** 白名单里的三个原因串（读数/报告用；不含"玩家已断开"、"服务器正在关闭"、"玩家已死"这三条状态判据）。 */
    public static String[] removalWhitelist() {
        return new String[] { R_CHANGED_DIMENSION, R_UNLOADED_TO_CHUNK, R_UNLOADED_WITH_PLAYER };
    }

    /**
     * 这次 {@code setRemoved(reason)} 是不是<b>正常离开</b>（必须放行）。
     *
     * <pre>
     *   服务器正在关闭（isRunning=false / isStopped=true）   ⇒ true（关服时一律放行，绝不阻碍进程退出）
     *   CHANGED_DIMENSION                                  ⇒ true（换维度）
     *   UNLOADED_TO_CHUNK                                   ⇒ true（区块卸载，还会回来）
     *   UNLOADED_WITH_PLAYER                                ⇒ true（🔴 见下面第 ② 条证据：必须无条件放行）
     *   DISCARDED ＋ 玩家已经死了（isDeadOrDying / health&lt;=0）⇒ true（🔴 见下面第 ③ 条证据：重生那条路走的就是它）
     *   其余（DISCARDED 且玩家活着 / KILLED / null / 没见过的串）⇒ false（拦）
     * </pre>
     *
     * <h4>🔴 为什么 UNLOADED_WITH_PLAYER 必须无条件放行（javap 证据，不是推测）</h4>
     * <pre>
     *   javap -c net.minecraft.server.players.PlayerList#remove
     *      0: ForgeEventFactory.firePlayerLoggedOut
     *     16: save(player)
     *     73: aload_2（= player.serverLevel()）
     *     75: getstatic  Entity$RemovalReason.UNLOADED_WITH_PLAYER
     *     78: ServerLevel.removePlayerImmediately(player, UNLOADED_WITH_PLAYER)
     *   javap -c net.minecraft.server.players.PlayerList#removeAll
     *      → 只是 for(players) remove(p)，**不先调 player.disconnect()**
     *   javap -c net.minecraft.server.network.ServerGamePacketListenerImpl#onDisconnect
     *     84: player.disconnect()   ← 先置 disconnected=true
     *     98: playerList.remove(player)   ← 之后才 remove
     * </pre>
     * ⇒ 正常退服（onDisconnect）那一刻 {@code hasDisconnected()} 已经是 true；
     * 但<b>关服</b>走的是 {@code removeAll()}，它<b>不</b>先调 {@code disconnect()}
     * ⇒ 若把"已断开"当必要条件，关服时每个受保护玩家都会拦在实体层、留一堆幽灵实体
     * ⇒ 必须无条件放行。（被滥用的兜底是 {@code ShanhaiGuardEvents#guardOne} 每 tick 的救回那一路。）
     *
     * <h4>🔴 为什么"玩家已经死了"要放行 DISCARDED（javap 证据）</h4>
     * <pre>
     *   javap -c net.minecraft.server.players.PlayerList#respawn
     *      16: getstatic  Entity$RemovalReason.DISCARDED
     *      19: ServerLevel.removePlayerImmediately(oldPlayer, DISCARDED)
     * </pre>
     * ⇒ 玩家点"重生"时，旧实体就是用 DISCARDED 摘掉的。不放行的话，
     * 一旦真的死了一次（守卫的 die 拒绝被绕过时就是死而无损的兜底场景），
     * 旧实体会永远留在世界里 —— 那比"被删掉"更糟。
     *
     * @param reasonName    {@code RemovalReason#name()}；{@code null} 表示没有原因
     * @param disconnected  玩家确实已断开（{@code ServerPlayer#hasDisconnected()} 或 connection 为空）
     * @param serverStopping 服务器正在关闭（{@code !isRunning() || isStopped()}）
     * @param playerDead    玩家已经进入死亡态（{@code isDeadOrDying()} 或 {@code health <= 0}）
     */
    public static boolean isNormalLeave(String reasonName, boolean disconnected, boolean serverStopping,
                                        boolean playerDead) {
        if (serverStopping) {
            return true;
        }
        final String r = reasonName == null ? "" : reasonName.trim();
        if (R_CHANGED_DIMENSION.equals(r) || R_UNLOADED_TO_CHUNK.equals(r)
                || R_UNLOADED_WITH_PLAYER.equals(r)) {
            return true;
        }
        return R_DISCARDED.equals(r) && playerDead;
    }

    /** 老签名（没有"玩家已死"这个读数）：等价于 {@code playerDead = false}。 */
    public static boolean isNormalLeave(String reasonName, boolean disconnected, boolean serverStopping) {
        return isNormalLeave(reasonName, disconnected, serverStopping, false);
    }

    /**
     * 🔴 <b>这一拍 setRemoved 要不要拦下</b>（{@code yes} = 拦下，调用方 {@code ci.cancel()}）。
     *
     * <pre>
     *   不是服务端玩家（生物/掉落物/箭矢/客户端 LocalPlayer） ⇒ 不拦（一个字节都不改）
     *   不是受保护玩家（没模块也没登记过）                   ⇒ 不拦
     *   正常离开（白名单那几条）                             ⇒ 不拦
     *   其余（活着的玩家被 DISCARDED / KILLED / null / 没见过的）⇒ 拦
     * </pre>
     */
    public static ShanhaiGuardCore.Decision refuseRemoval(boolean isServerPlayer, boolean guarded,
                                                          String reasonName, boolean disconnected,
                                                          boolean serverStopping, boolean playerDead) {
        if (!isServerPlayer) {
            return ShanhaiGuardCore.Decision.of(false, "不是服务端玩家 ⇒ 不拦（一个字节都不改）");
        }
        if (!guarded) {
            return ShanhaiGuardCore.Decision.of(false, "不是受保护玩家 ⇒ 不拦");
        }
        if (isNormalLeave(reasonName, disconnected, serverStopping, playerDead)) {
            return ShanhaiGuardCore.Decision.of(false,
                    "正常离开（reason=" + reasonName + " disconnected=" + disconnected
                            + " server_stopping=" + serverStopping + " player_dead=" + playerDead
                            + "）⇒ 放行（否则换维度/退服/重生卡死）");
        }
        return ShanhaiGuardCore.Decision.of(true,
                "🔴 受保护玩家被 setRemoved(reason=" + reasonName + ") ⇒ 拦下（不移除）");
    }

    /** 老签名（{@code playerDead = false}）。 */
    public static ShanhaiGuardCore.Decision refuseRemoval(boolean isServerPlayer, boolean guarded,
                                                          String reasonName, boolean disconnected,
                                                          boolean serverStopping) {
        return refuseRemoval(isServerPlayer, guarded, reasonName, disconnected, serverStopping, false);
    }

    // ═══════════════════════════════ ③ 抹除阶梯（L1..L6） ═══════════════════════════════

    /** L1：{@code entity.discard()}。 */
    public static final String CH_L1 = "L1";
    /** L3：{@code entity.setRemoved(DISCARDED)}（final 方法，绕过被重写的 remove）。 */
    public static final String CH_L3 = "L3";
    /** L4：给所有玩家发 {@code ClientboundRemoveEntitiesPacket}。 */
    public static final String CH_L4 = "L4";
    /** L5：反射把实体从 {@code ServerLevel} 实体管理器的 id/uuid 两本账里摘掉。 */
    public static final String CH_L5 = "L5";
    /** L6：隔离（极远坐标 ＋ noAi ＋ 隐身 ＋ 每 tick 重申 L3）。 */
    public static final String CH_L6 = "L6";

    /** 全部通道名（自检与日志口径都用它）。 */
    public static String[] purgeChannels() {
        return new String[] { CH_L1, CH_L3, CH_L4, CH_L5, CH_L6 };
    }

    /** 同一只被重申多少次之后进隔离（需求口径：**超过 20 次**）。 */
    public static final int ISOLATE_AFTER_REASSERTS = 20;

    /** 累计重申次数要不要进隔离（严格大于 20）。 */
    public static boolean shouldIsolate(int reassertCount) {
        return reassertCount > ISOLATE_AFTER_REASSERTS;
    }

    /** 🔴 安全默认：所有玩家（含自己）与 {@code EntityType.PLAYER} 一律跳过。 */
    public static boolean skipsPlayer(boolean isPlayer, boolean isPlayerType) {
        return isPlayer || isPlayerType;
    }

    /**
     * 播报那一句（左下角）。<b>同一 tick 会由执行体合并</b>。
     *
     * <pre>
     *   L1      ⇒ 已抹除 &lt;名字&gt;
     *   L3 / L4 ⇒ 已抹除 &lt;名字&gt;（强制）
     *   L5      ⇒ 已抹除 &lt;名字&gt;（底层）
     *   L6      ⇒ 已隔离 &lt;名字&gt;
     * </pre>
     */
    public static String announceRemoved(String name, String channel) {
        final String n = (name == null || name.isEmpty()) ? "?" : name;
        if (CH_L1.equals(channel)) {
            return "已抹除 " + n;
        }
        if (CH_L3.equals(channel) || CH_L4.equals(channel)) {
            return "已抹除 " + n + "（强制）";
        }
        if (CH_L5.equals(channel)) {
            return "已抹除 " + n + "（底层）";
        }
        if (CH_L6.equals(channel)) {
            return "已隔离 " + n;
        }
        return "已抹除 " + n;   // 认不出的通道串：不吞掉事实，按最普通的那一句报
    }

    /** 多只合并：「已抹除 &lt;名字&gt; 等 N 只」。{@code total <= 1} ⇒ 原样返回单只那一句。 */
    public static String mergeAnnounce(String firstName, int total) {
        if (total <= 1) {
            return firstName;
        }
        final String first = firstName == null ? "" : firstName;
        final int cut = first.indexOf('（');
        final String head = cut > 0 ? first.substring(0, cut) : first;
        return head + " 等 " + total + " 只";
    }

    /** 执行前那一行：「抹除开始：范围内 X 只」。 */
    public static String announceStart(int scanned) {
        return "抹除开始：范围内 " + scanned + " 只";
    }

    /** 执行后那一行：「已抹除 N 只，跳过玩家 M 个」。 */
    public static String announceEnd(int removed, int skippedPlayers) {
        return "已抹除 " + removed + " 只，跳过玩家 " + skippedPlayers + " 个";
    }

    /** 日志一行：{@code purge_channel=L1 ...}（需求点名要能分辨来源）。 */
    public static String purgeLogLine(String channel, String name, int entityId, boolean verified) {
        return "purge_channel=" + channel + " entity=" + name + " id=" + entityId
                + " verified=" + verified;
    }

    // ═══════════════════════════════ ④ 死而无损 ═══════════════════════════════

    /** 死而无损还原的五项（顺序 = 报告里写的那五项）。 */
    public static String[] deathlessParts() {
        return new String[] { "背包＋副手＋盔甲", "经验等级与进度", "位置与朝向", "饥饿与饱和度", "药水效果" };
    }

    /** 一行「死而无损：已还原 <哪几项>」（只列真还原了的）。 */
    public static String deathlessLogLine(boolean inventory, boolean xp, boolean position,
                                          boolean hunger, boolean effects) {
        final StringBuilder sb = new StringBuilder("死而无损：已还原 ");
        final String[] names = deathlessParts();
        final boolean[] flags = { inventory, xp, position, hunger, effects };
        boolean first = true;
        for (int i = 0; i < names.length; i++) {
            if (!flags[i]) {
                continue;
            }
            if (!first) {
                sb.append(" / ");
            }
            first = false;
            sb.append(names[i]);
        }
        if (first) {
            sb.append("（一项都没还原）");
        }
        return sb.toString();
    }

    // ═══════════════════════════════ 自检（同文件 main） ═══════════════════════════════

    private static final PrintStream OUT = utf8Stdout();

    private static PrintStream utf8Stdout() {
        try {
            return new PrintStream(new FileOutputStream(FileDescriptor.out), true, "UTF-8");
        } catch (Throwable t) {
            return System.out;
        }
    }

    private static int checks = 0;
    private static int reds = 0;

    private static void expect(String group, String name, boolean expected, boolean actual, String reading) {
        checks++;
        final boolean ok = (expected == actual);
        if (!ok) {
            reds++;
        }
        OUT.println(String.format("%-4s | %-4s | %-46s | 期望=%-5s 实测=%-5s | %s",
                ok ? "PASS" : "RED", group, name, expected, actual, reading));
    }

    private static void note(String s) {
        OUT.println("       · " + s);
    }

    /**
     * 离线自检：{@code javac -d <out> ShanhaiGuardCore.java ShanhaiGuardRemoval.java} 后
     * {@code java -cp <out> com.shanhai.common.guard.ShanhaiGuardRemoval}。
     *
     * <p>本自检证明的是<b>判据</b>；「mixin 真的挂上了吗」「抹除真的到客户端了吗」只能进游戏验。
     */
    public static void main(String[] args) {
        OUT.println("=== ShanhaiGuardRemoval 离线自检 ===");

        // ── ① 白名单 ──
        expect("①白名单", "CHANGED_DIMENSION 放行", false,
                !isNormalLeave(R_CHANGED_DIMENSION, false, false), "换维度不许拦");
        expect("①白名单", "UNLOADED_TO_CHUNK 放行", false,
                !isNormalLeave(R_UNLOADED_TO_CHUNK, false, false), "区块卸载不许拦");
        expect("①白名单", "UNLOADED_WITH_PLAYER ＋ 已断开 放行", false,
                !isNormalLeave(R_UNLOADED_WITH_PLAYER, true, false), "退服不许拦");
        // 🔴 关服走的是 PlayerList#removeAll（javap：不先调 disconnect()）⇒ 这一条必须放行，否则关服留幽灵实体
        expect("①白名单", "UNLOADED_WITH_PLAYER ＋ 没断开 也放行", false,
                !isNormalLeave(R_UNLOADED_WITH_PLAYER, false, false), "关服 removeAll 不调 disconnect ⇒ 必须放行");
        // 🔴 PlayerList#respawn 用的是 DISCARDED（javap）⇒ 玩家已死时必须放行，否则重生摘不掉旧实体
        expect("①白名单", "DISCARDED ＋ 玩家已死 放行", false,
                !isNormalLeave(R_DISCARDED, false, false, true), "重生那条路走的就是 DISCARDED");
        expect("①白名单", "DISCARDED ＋ 玩家活着 拦", true,
                !isNormalLeave(R_DISCARDED, false, false, false), "活人被 DISCARDED = 有人要删掉他");
        expect("①白名单", "服务器关闭中 放行", false,
                !isNormalLeave(R_DISCARDED, false, true, false), "关服时任何 reason 都放行");
        expect("①白名单", "KILLED 拦", true,
                !isNormalLeave(R_KILLED, false, false, false), "外力 kill（活着）⇒ 拦");
        expect("①白名单", "reason=null 拦", true, !isNormalLeave(null, false, false), "没原因 ⇒ 拦");
        expect("①白名单", "没见过的 reason 拦", true,
                !isNormalLeave("SOMETHING_NEW", false, false), "不猜 ⇒ 拦");
        // 负对照：把白名单写成"空表"（什么都不放行）⇒ 上面那几条放行断言必须全红
        int wouldRed = 0;
        for (String r : new String[] { R_CHANGED_DIMENSION, R_UNLOADED_TO_CHUNK, R_UNLOADED_WITH_PLAYER }) {
            if (isNormalLeaveMutantNever(r, false, false)) {
                wouldRed++;
            }
        }
        expect("①负对照", "白名单写成【永不放行】时放行断言会红", true, wouldRed == 0,
                "突变体放行数=" + wouldRed + "（必须 0 ⇒ 证明上面几条不是恒绿）");

        // ── ① 拦不拦的完整判据 ──
        expect("①判据", "非玩家实体不拦", false,
                refuseRemoval(false, true, R_DISCARDED, false, false).yes, "生物/掉落物一个字节都不改");
        expect("①判据", "非受保护玩家不拦", false,
                refuseRemoval(true, false, R_DISCARDED, false, false).yes, "没模块也没登记过 ⇒ 放行");
        expect("①判据", "受保护玩家被 DISCARDED ⇒ 拦", true,
                refuseRemoval(true, true, R_DISCARDED, false, false).yes, "这就是「玩家删不掉」");
        expect("①判据", "受保护玩家换维度 ⇒ 不拦", false,
                refuseRemoval(true, true, R_CHANGED_DIMENSION, false, false).yes, "否则换维度卡死");
        expect("①判据", "受保护玩家退服 ⇒ 不拦", false,
                refuseRemoval(true, true, R_UNLOADED_WITH_PLAYER, true, false).yes, "否则退服卡死");
        expect("①判据", "受保护玩家已死 ＋ DISCARDED ⇒ 不拦", false,
                refuseRemoval(true, true, R_DISCARDED, false, false, true).yes, "否则重生卡死");
        note("拦下时原因串：" + refuseRemoval(true, true, R_DISCARDED, false, false).reason);

        // ── ③ 抹除阶梯与播报 ──
        expect("③通道", "通道表恰好 5 个", true, purgeChannels().length == 5, "L1/L3/L4/L5/L6");
        expect("③播报", "L1 ⇒ 已抹除 名字", true,
                "已抹除 僵尸".equals(announceRemoved("僵尸", CH_L1)), announceRemoved("僵尸", CH_L1));
        expect("③播报", "L3 ⇒ 已抹除 名字（强制）", true,
                "已抹除 僵尸（强制）".equals(announceRemoved("僵尸", CH_L3)), announceRemoved("僵尸", CH_L3));
        expect("③播报", "L4 ⇒ 已抹除 名字（强制）", true,
                "已抹除 僵尸（强制）".equals(announceRemoved("僵尸", CH_L4)), announceRemoved("僵尸", CH_L4));
        expect("③播报", "L5 ⇒ 已抹除 名字（底层）", true,
                "已抹除 僵尸（底层）".equals(announceRemoved("僵尸", CH_L5)), announceRemoved("僵尸", CH_L5));
        expect("③播报", "L6 ⇒ 已隔离 名字", true,
                "已隔离 僵尸".equals(announceRemoved("僵尸", CH_L6)), announceRemoved("僵尸", CH_L6));
        expect("③播报", "多只合并", true,
                "已抹除 僵尸 等 3 只".equals(mergeAnnounce("已抹除 僵尸", 3)),
                mergeAnnounce("已抹除 僵尸", 3));
        expect("③播报", "多只合并不吃掉带括号的后缀", true,
                "已抹除 僵尸 等 3 只".equals(mergeAnnounce("已抹除 僵尸（强制）", 3)),
                mergeAnnounce("已抹除 僵尸（强制）", 3));
        expect("③播报", "单只不合并", true,
                "已抹除 僵尸".equals(mergeAnnounce("已抹除 僵尸", 1)), mergeAnnounce("已抹除 僵尸", 1));
        expect("③播报", "开始行", true,
                "抹除开始：范围内 12 只".equals(announceStart(12)), announceStart(12));
        expect("③播报", "结束行", true,
                "已抹除 7 只，跳过玩家 2 个".equals(announceEnd(7, 2)), announceEnd(7, 2));
        expect("③安全", "玩家一律跳过（isPlayer）", true, skipsPlayer(true, false), "含自己");
        expect("③安全", "玩家类型一律跳过（isPlayerType）", true, skipsPlayer(false, true), "EntityType.PLAYER");
        expect("③安全", "普通生物不跳过", false, skipsPlayer(false, false), "僵尸要处理");
        expect("③隔离", "重申 20 次不隔离", false, shouldIsolate(20), "口径是「超过 20 次」");
        expect("③隔离", "重申 21 次隔离", true, shouldIsolate(21), "第 21 次起隔离");
        expect("③日志", "日志带 purge_channel", true,
                purgeLogLine(CH_L5, "僵尸", 123, true).contains("purge_channel=L5"),
                purgeLogLine(CH_L5, "僵尸", 123, true));

        // ── ④ 死而无损 ──
        expect("④死而无损", "五项全还原时列出五项", true,
                deathlessLogLine(true, true, true, true, true).contains("药水效果"),
                deathlessLogLine(true, true, true, true, true));
        expect("④死而无损", "只还原两项时只列两项", true,
                "死而无损：已还原 背包＋副手＋盔甲 / 位置与朝向".equals(
                        deathlessLogLine(true, false, true, false, false)),
                deathlessLogLine(true, false, true, false, false));
        expect("④死而无损", "一项都没还原时明确写出来", true,
                deathlessLogLine(false, false, false, false, false).contains("一项都没还原"),
                deathlessLogLine(false, false, false, false, false));

        // ⚠️ 本轮删掉了原来那 6 条「面板二次确认（两拍）」断言（含 1 条突变体负对照）——
        //    用户点单：全息板第 1 格点一次直接执行 ⇒ 两拍机制整个移除，判据一起删（不留死代码）。
        //    ⇒ 本文件的条数因此从 43 降到 37（实测读数；不把数字凑回去）。

        OUT.println("---------------------------------------------------------------");
        OUT.println("合计 " + checks + " 条，红 " + reds + " 条 ⇒ " + (reds == 0 ? "全绿 PASS" : "有红 FAIL"));
        if (reds != 0) {
            System.exit(1);
        }
    }

    /** 【故意写坏】白名单永远不放行（负对照：证明上面那几条"放行"断言不是恒绿）。 */
    private static boolean isNormalLeaveMutantNever(String reasonName, boolean disconnected,
                                                    boolean serverStopping) {
        return false;
    }
}
