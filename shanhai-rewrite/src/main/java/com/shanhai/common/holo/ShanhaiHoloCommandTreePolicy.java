package com.shanhai.common.holo;

/**
 * 山海重构 · 全息命令框的<b>指令树判据（纯逻辑核）</b>。
 *
 * <h2>1. 这一小类要解决的那一件事</h2>
 * 全息命令输入框的候选表由客户端复用原版 {@code CommandSuggestions} 画出来
 * （{@code client.holo.ShanhaiHoloCommandScreen}），<b>它的数据源就是客户端手上那一份指令树</b>。
 * 而那份树是<b>服务端按玩家【真实】权限剪枝过</b>才发下来的
 * （{@code net.minecraft.commands.Commands#sendCommands} 里
 * {@code CommandHelper.mergeCommandNode(..., player.createCommandSourceStack(), ...)} 的
 * {@code canUse} 那一道门）⇒ <b>没有 OP 的玩家，客户端根本不知道
 * {@code /gamemode} {@code /give} 这些节点存在</b> ⇒ 原版 {@code CommandSuggestions} 自然补不出来。
 *
 * <h2>2. 门槛与权限为什么是【两件事】（用户点单的口径，别混）</h2>
 * <pre>
 *   门槛：身上必须有「创始现实修改模块」（原话：「只有拿着『创始现实修改模块』的人才能开这个菜单」）
 *   权限：全息输入框执行时以等级 {@link #TREE_PERMISSION_LEVEL}（4）跑
 *         （原话：「全息输入框，可以执行cmd的命令，拥有管理员权限」）
 * </pre>
 * 执行那条路已经这么做了（{@code ShanhaiHoloMenuNetwork#onCommandRequest} 里
 * {@code createCommandSourceStack().withPermission(COMMAND_PERMISSION_LEVEL)}）。
 * <b>本类管的是"发哪一份树给他看"，让候选表与他【真能执行】的集合一致</b>。
 *
 * <h2>3. 🔴 红线：等级 4 的树【只】发给拿着模块的人</h2>
 * 没有模块的玩家绝不能收到等级 4 的那一份 —— 那是权限信息泄漏，也与他实际能力不符
 * （他跑不了那些命令）。所以"不持有"这一支只有两种可能：
 * <pre>
 *   ① 我们【之前】给他发过全树 ⇒ 立刻用原版那条路还原成"按他真实权限剪枝"的那一份
 *   ② 从没发过 ⇒ 什么都不做（原版登录/重生/op/deop 已经发过正确的那一份了）
 * </pre>
 *
 * <h2>4. 为什么判据要单独抽成"纯逻辑核"</h2>
 * "该不该发、发哪一份"是这件事里<b>唯一能离线判定</b>的部分（发那一步要真服务端），
 * 所以它只 {@code import java.*}：自证用例（{@code temp\holo-cmdperm}）可以用
 * <b>空 classpath</b> 编译并运行它，带正对照、负对照与突变体。
 * 真的把包发出去那一步在 {@link ShanhaiHoloCommandTreeSync}。
 *
 * <h2>5. 判据三条（用一句话说清）</h2>
 * <pre>
 *   持有 + (客户端手上不是全树 | 有人可能覆盖过它)  ⇒ 发【完整】树（等级 4 源栈）
 *   不持有 + 客户端手上是全树                      ⇒ 发【按真实权限剪枝】的树（还原）
 *   其它                                           ⇒ 一个字节都不发
 * </pre>
 * "有人可能覆盖过它" = {@code forced}（登录/重生：原版刚发过一份剪枝的）
 * 或 权限等级变了（op/deop：原版也会重发一份）。
 */
public final class ShanhaiHoloCommandTreePolicy {

    /**
     * 发"完整树"时用的权限等级 —— 必须与 {@code ShanhaiHoloMenuNetwork#COMMAND_PERMISSION_LEVEL}
     * 一致（那边是<b>执行</b>时 {@code withPermission(4)}；这边是<b>发布候选</b>时同一个数）。
     * 两边同一个数 ⇒ "候选表 = 他真能执行的集合"。
     */
    public static final int TREE_PERMISSION_LEVEL = 4;

    /** 本次要做什么。 */
    public enum Action {
        /** 什么都不发（绝大多数时候）。 */
        NOTHING,
        /** 发完整指令树：以等级 {@link #TREE_PERMISSION_LEVEL} 的源栈铺出来（候选表里会出现 OP 指令）。 */
        SEND_FULL_TREE,
        /** 发按【他真实权限】剪枝的树：走原版 {@code Commands#sendCommands} 那条路（还原）。 */
        SEND_ACTUAL_PERMISSION_TREE
    }

    /**
     * 观察到的玩家状态快照。<b>玩家不在 / 连接断了 ⇒ {@link #absent()}</b>：
     * 这一支永远不许产生"发全树"（没人收，也不能记成"已经发过"）。
     */
    public static final class Carrier {

        /** 玩家在不在（还有没有可用的连接）。 */
        public final boolean present;

        /** 身上有没有那块模块（= {@code ShanhaiHoloMenuNetwork#moduleInInventory(player)}）。 */
        public final boolean moduleCarried;

        /** 玩家【真实】的权限等级（与 {@code ServerPlayer#getPermissionLevel()} 同一个来源）。 */
        public final int permissionLevel;

        private Carrier(boolean present, boolean moduleCarried, int permissionLevel) {
            this.present = present;
            this.moduleCarried = moduleCarried;
            this.permissionLevel = permissionLevel;
        }

        /** 空玩家 / 已断线。 */
        public static Carrier absent() {
            return new Carrier(false, false, 0);
        }

        /** 一个在线的玩家：模块在不在 + 他的真实权限等级。 */
        public static Carrier of(boolean moduleCarried, int permissionLevel) {
            return new Carrier(true, moduleCarried, permissionLevel);
        }

        @Override
        public String toString() {
            return "Carrier{present=" + present + ", module=" + moduleCarried
                    + ", perm=" + permissionLevel + "}";
        }
    }

    /**
     * 我们【以为】这个玩家的客户端手上是哪一份树（每个玩家一份，跟着 UUID 走）。
     * <p>它是"我们最后发出去的那一份"，不是"客户端一定收到了那一份" ——
     * 所以原版可能覆盖它的时候（登录、重生、op/deop）必须用 {@code forced} 或者
     * 权限等级变化把它推翻重发，见 {@link #decide}。
     */
    public static final class State {

        /** true = 我们最后发的是【完整树】；false = 剪枝树（或者没发过）。 */
        public boolean fullTreeSent;

        /** 上一次见到的权限等级（用于发现 op/deop）。 */
        public int lastPermissionLevel = Integer.MIN_VALUE;

        /** 有没有观测过（全新状态第一次进来要当作"权限变了"）。 */
        public boolean known;

        public static State fresh() {
            return new State();
        }

        @Override
        public String toString() {
            return "State{fullTreeSent=" + fullTreeSent + ", known=" + known
                    + ", lastPerm=" + lastPermissionLevel + "}";
        }
    }

    /**
     * 判据本体（纯函数 + 就地更新 {@code state}）。
     *
     * @param state   该玩家的记忆（<b>null ⇒ 什么都不发</b>：记不住就不放行，fail-closed）
     * @param carrier 这一次观测到的状态（null / 不在线 ⇒ 什么都不发）
     * @param forced  有人可能刚覆盖过客户端的树（登录 / 重生）；true ⇒ 即使我们已经发过全树，也再发一次
     * @return 该做什么（{@link Action}）
     */
    public static Action decide(State state, Carrier carrier, boolean forced) {
        if (state == null) {
            return Action.NOTHING;              // 记不住 ⇒ 不放行
        }
        if (carrier == null || !carrier.present) {
            return Action.NOTHING;              // 空玩家 / 断线：没人收，也绝不能记成"已发全树"
        }
        final boolean permChanged = !state.known || state.lastPermissionLevel != carrier.permissionLevel;
        state.lastPermissionLevel = carrier.permissionLevel;
        state.known = true;
        final boolean mustResend = forced || permChanged;

        if (carrier.moduleCarried) {
            if (!state.fullTreeSent || mustResend) {
                state.fullTreeSent = true;
                return Action.SEND_FULL_TREE;
            }
            return Action.NOTHING;              // 已经是对的那一份 ⇒ 一个字节都不重发（10 tick 扫描不刷屏）
        }
        if (state.fullTreeSent) {
            state.fullTreeSent = false;
            return Action.SEND_ACTUAL_PERMISSION_TREE;   // 刚丢掉模块 ⇒ 立刻还原成他真实权限那一份
        }
        return Action.NOTHING;
    }

    private ShanhaiHoloCommandTreePolicy() {}
}
