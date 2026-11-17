package com.shanhai.add.time.replay;

import java.util.List;

/**
 * 山海重构 · <b>倒流的"两段式"写回器</b>（🔴 零 Minecraft 依赖，别加 import）。
 *
 * <h2>1. 为什么必须两段（先 AIR、再目标态）</h2>
 * 一笔差分要表达的是"这个格子从 A 变到 B"，倒流要把它从 B 推回 A。直接写 A 有两个坑：
 * <ol>
 *   <li><b>方块实体不会被重新创建</b>：从"箱子"写回"箱子"，原版会认为方块没变
 *       （同 block+同 properties ⇒ 不重建 BE），我们随后 {@code load} 到的还是 B 那个旧实例；</li>
 *   <li><b>形状/邻块更新链会炸</b>：从 B 一步跳到 A 时，原版要现算新旧形状差集并发 6 个方向的
 *       {@code neighborChanged}；GT 多方块/红石/流体在这种"跨形态"更新下会连锁重算
 *       （设计 §4.1 的"避免邻块更新炸裂"）。</li>
 * </ol>
 * 所以顺序是：<b>① {@code setBlock(AIR, flag 3)} ② {@code setBlock(目标态, flag 2)}
 * ③ 有 NBT 就 {@code BlockEntity#load}</b>。
 *
 * <h2>2. 🔴 写成功不能看 {@code setBlock} 的返回值（2026-11 真机实测的"方块状态回不来"）</h2>
 * 原版 {@code Level#setBlock} 的返回值语义是"<b>世界变了吗</b>"，不是"<b>写进去了吗</b>"：
 * <pre>
 *   Level#setBlock  →  LevelChunk#setBlockState(pos, state, …)
 *                      ├─ 新旧同态 ⇒ return null ⇒ Level#setBlock 返回 false（世界本来就这样）
 *                      └─ 不同态   ⇒ return 旧态 ⇒ Level#setBlock 返回 true
 * </pre>
 * ⇒ 把 {@code setBlock} 的 {@code false} 当成"写失败"会同时错两件事：
 * <b>① 到了位的方块被记成 failed；② 真的没写进去的方块也可能一声不响</b>（当目标态恰好等于当前态时）。
 * 这就是"挖掉的方块一条都没回来、而 failed 几乎不涨"的形态。
 *
 * <p>因此本版起加<b>写后回读</b>这条硬判据（{@link #apply}）：
 * <pre>
 *   setTarget(from)
 *   读回该 pos 的当前状态索引 readback
 *   readback &lt; 0            ⇒ 读不到（自检假体/区块没加载）⇒ 退化为看返回值
 *   readback == fromState   ⇒ ✅ 真到位（{@code alreadyCorrect} 记录"本来就对"的笔数）
 *   readback != fromState   ⇒ ❌ 计 {@code failed}（并 {@code mismatch}），**不静默**
 * </pre>
 *
 * <h2>3. 为什么把"这个接口"放在纯 Java 里</h2>
 * 判据 ② 要求离线断言"调用序列 = 先 AIR 再目标态"，而且要有负对照（少了 AIR 那一段就红）。
 * 只要写回动作经过一个<b>替身</b>就能验证，所以真正的 world 写操作被隔离到
 * {@link Sink}（运行期实现 = {@code ShanhaiReplayEngine}，自检实现 = 记录调用序列的假体）。
 *
 * <h2>4. 失败方向</h2>
 * AIR 写失败 ⇒ <b>不写</b>目标态（否则会造出"方块在、方块实体不在了"的半截态），计 {@code failed}。
 */
public final class ShanhaiReplayApplier {

    /** 第一段：清空目标格用的 flag（UPDATE_NEIGHBORS|UPDATE_CLIENTS）。 */
    public static final int FLAG_AIR = 3;

    /** 第二段：写目标态用的 flag（UPDATE_CLIENTS only，不回灌邻块）。 */
    public static final int FLAG_TARGET = 2;

    /** 明细最多留几条（真机判读用；再多就是刷屏）。 */
    public static final int TRACE_LIMIT = 5;

    /** 这一笔要不要整笔跳过（预检）。 */
    public enum Skip {
        /** 照常回退。 */
        NONE,
        /** 目标态会落进玩家身体里 ⇒ 整笔跳过（玩家的时间不受影响，设计 §10-② 推荐 A）。 */
        PLAYER_AABB
    }

    /**
     * 回退的落点（运行期 = {@code ShanhaiReplayEngine}；自检 = 记录调用序列的假体）。
     */
    public interface Sink {

        /**
         * 预检：这一笔能不能写。
         *
         * <p>🔴 必须在<b>写 AIR 之前</b>问：如果预检说跳过，就连 AIR 也不能写
         * （否则会先把方块删掉、再跳过目标态 ⇒ 世界多一个空洞）。
         *
         * @param dim         维度小整数
         * @param pos         {@code BlockPos#asLong()}
         * @param targetState 准备写回的状态索引（第一段之后的终态）
         */
        Skip precheck(int dim, long pos, int targetState);

        /**
         * 第一段：把目标格设为 AIR。
         *
         * <p>🔴 <b>只在目标格不是空气时才调</b>（{@link #isAir} 为 {@code false}）。
         * 原版 {@code Level#setBlock} 在"写进去的状态和现在相同"时返回 {@code false}
         * ⇒ 玩家挖掉的方块，目标格<b>本来就是空气</b> ⇒ 旧实现把这一笔判成"写失败"并跳过目标态，
         * 于是"连打破的方块都没回去"（用户原话）。
         *
         * @param flags 恒为 {@link #FLAG_AIR}
         * @return {@code false} = 写失败（区块没加载/被拦），调用方应当放弃这一笔
         */
        boolean setAir(int dim, long pos, int flags);

        /**
         * 目标格现在是不是空气（预判"第一段需不需要写"）。
         *
         * <p>不判它就会出现上面那条回归：任何"回到空气之前的状态"（= 挖掉/爆炸掉的方块）都回不来。
         */
        boolean isAir(int dim, long pos);

        /**
         * 第二段：写入目标态（并在有载荷时 {@code BlockEntity#load}）。
         *
         * <p>⚠️ 返回值语义是"世界变了吗"（见类注释 §2），<b>不能</b>单独当成写成功；
         * 权威判据是 {@link #stateIndexAt}。
         *
         * @param flags   恒为 {@link #FLAG_TARGET}
         * @param payload 方块实体载荷槽号，{@link ShanhaiReplayJournal#NO_PAYLOAD} = 没有
         * @return {@code false} = 没变（同态早退）或写失败 —— 两者只能靠回读分开
         */
        boolean setTarget(int dim, long pos, int state, int flags, int payload);

        /**
         * 写后回读：目标格<b>现在</b>的状态索引。
         *
         * @return {@code -1} = 读不到（区块没加载 / 自检假体不提供）⇒ 调用方退化为看返回值
         */
        default int stateIndexAt(int dim, long pos) {
            return -1;
        }

        /** 首几条明细的去处（运行期打日志，自检丢弃）。 */
        default void traceWrite(String line) {
            // 默认丢弃
        }

        /**
         * <b>提前一拍</b>为"下一拍要恢复的方块"发粒子（倒放顺序：粒子先、方块后出现）。
         *
         * <p>用户原话：「正常时序是方块破坏 → 发出粒子；但我们是倒放，<b>应该是先有粒子，方块再出现</b>」。
         * 默认空实现（自检假体只记录调用序）。
         *
         * @param state 下一拍要写回去的状态索引（粒子用它的 {@code BlockState}）
         */
        default void leadParticles(int dim, long pos, int state) {
            // 默认不做事
        }
    }

    /**
     * 一批写回的结果。
     *
     * @param written        真到位的笔数（回读 == 目标态；读不到时退化为返回值 true）
     * @param failed         没到位的笔数（回读 != 目标态，或 AIR 段失败）
     * @param skipped        预检要求跳过的笔数（玩家身体占位）
     * @param alreadyAir     目标格本来就是空气、跳过第一段的笔数（挖掉的方块靠它才回得来）
     * @param alreadyCorrect 第一段跳过 + 第二段"同态早退"但<b>回读已等于目标态</b>的笔数
     * @param mismatch       回读 != 目标态 ⇒ 真没写进去（{@link #failed} 的子集，单列便于判读）
     * @param verified       做过回读校验的笔数（{@code 0} = 落点不提供回读）
     */
    public record Result(int written, int failed, int skipped, int alreadyAir,
                         int alreadyCorrect, int mismatch, int verified) {

        public static Result empty() {
            return new Result(0, 0, 0, 0, 0, 0, 0);
        }
    }

    /**
     * 按给定顺序（调用方保证是 <b>LIFO</b>，即 {@code ShanhaiReplayJournal#takeNewestTick()} 的返回）
     * 逐条回退。
     *
     * @param newestFirst 一批差分（新→旧）
     * @param sink        落点
     */
    public static Result apply(List<ShanhaiReplayJournal.Change> newestFirst, Sink sink) {
        if (newestFirst == null || newestFirst.isEmpty() || sink == null) {
            return Result.empty();
        }
        int written = 0;
        int failed = 0;
        int skipped = 0;
        int alreadyAir = 0;
        int alreadyCorrect = 0;
        int mismatch = 0;
        int verified = 0;
        for (ShanhaiReplayJournal.Change c : newestFirst) {
            if (sink.precheck(c.dim(), c.pos(), c.fromState()) != Skip.NONE) {
                ++skipped;
                continue;
            }
            boolean airSkipped = false;
            // ① 先 AIR —— 目标格已经是空气就跳过（同态写回一律返回 false，见 Sink#setAir 的注释）
            if (sink.isAir(c.dim(), c.pos())) {
                ++alreadyAir;
                airSkipped = true;
            } else if (!sink.setAir(c.dim(), c.pos(), FLAG_AIR)) {
                ++failed;
                trace(sink, c, "air_failed", false, -1, airSkipped);
                continue;
            }
            // ② 再目标态（+ 方块实体 NBT）
            final boolean wrote = sink.setTarget(c.dim(), c.pos(), c.fromState(), FLAG_TARGET, c.payload());
            // ③ 🔴 写后回读：这才是有没有到位的硬判据
            final int readback = sink.stateIndexAt(c.dim(), c.pos());
            if (readback < 0) {
                // 落点不给回读 ⇒ 只能信返回值（这条分支必须能被判读，见 done 行 verified=0）
                if (wrote) {
                    ++written;
                } else {
                    ++failed;
                }
                trace(sink, c, wrote ? "no_readback_wrote" : "no_readback_unchanged",
                        wrote, readback, airSkipped);
                continue;
            }
            ++verified;
            if (readback == c.fromState()) {
                ++written;
                if (!wrote) {
                    ++alreadyCorrect;   // 同态早退：世界本来就是对的，不算失败
                }
                trace(sink, c, wrote ? "wrote" : "already_correct", wrote, readback, airSkipped);
            } else {
                ++failed;
                ++mismatch;             // 静默失败的唯一可见形态：回读 != 目标态
                trace(sink, c, "mismatch", wrote, readback, airSkipped);
            }
        }
        return new Result(written, failed, skipped, alreadyAir, alreadyCorrect, mismatch, verified);
    }

    /** 前 {@link #TRACE_LIMIT} 条走 {@link Sink#traceWrite}；运行期由引擎负责只打前几条。 */
    private static void trace(Sink sink, ShanhaiReplayJournal.Change c, String verdict,
                              boolean setTargetReturn, int readback, boolean airSkipped) {
        sink.traceWrite("dim=" + c.dim() + " pos=" + c.pos()
                + " from=" + c.fromState() + " after=" + c.afterState()
                + " air_skipped=" + (airSkipped ? 1 : 0)
                + " setTarget=" + setTargetReturn
                + " readback=" + readback
                + " payload=" + c.payload()
                + " verdict=" + verdict);
    }

    private ShanhaiReplayApplier() {
    }
}
