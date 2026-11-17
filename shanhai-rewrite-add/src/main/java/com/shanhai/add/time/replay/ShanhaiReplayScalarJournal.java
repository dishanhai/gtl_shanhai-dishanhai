package com.shanhai.add.time.replay;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 山海重构 · <b>世界级标量时间线（第 3 层）</b>（🔴 零 Minecraft 依赖，别加 import）。
 *
 * <h2>1. 记什么</h2>
 * 每个外层 tick 读一遍"世界级标量"，<b>变了才记</b>一条：
 * <table border="1">
 *   <caption>内置 key（{@link Keys}）</caption>
 *   <tr><th>key</th><th>来源</th><th>能不能写回</th></tr>
 *   <tr><td>{@code day_time}</td><td>{@code ServerLevel#getDayTime()}</td><td>✅ {@code setDayTime}</td></tr>
 *   <tr><td>{@code raining} / {@code thundering}</td><td>{@code Level#isRaining()/isThundering()}</td>
 *       <td>✅ {@code setWeatherParameters}</td></tr>
 *   <tr><td>{@code rain_level} / {@code thunder_level}</td>
 *       <td>0..1 的<b>插值量</b>（没有直接设值的公开 API）</td>
 *       <td>❌ 记录器<b>不</b>采这两个（写不回去的插值量只会在报告里制造假的 UNSUPPORTED）</td></tr>
 *   <tr><td>{@code savedata:gamerules}</td><td>整服游戏规则<b>整份快照</b>（{@code GameRules#createTag()}）</td>
 *       <td>✅ {@code assignFrom(new GameRules(Dynamic(NbtOps,tag)))}</td></tr>
 *   <tr><td>{@code savedata:scoreboard}</td><td>计分板<b>整份快照</b>（脏监听触发）</td>
 *       <td>✅ 清空 objectives/teams 后再 load</td></tr>
 * </table>
 *
 * <h2>2. 维度口径</h2>
 * 时间/天气是<b>每维度</b>的 ⇒ {@code dim >= 0}；游戏规则/计分板是<b>整服</b>的 ⇒ {@code dim = -1}。
 *
 * <h2>3. 为什么值是字符串</h2>
 * 同一层要装 {@code long}（时间）、{@code float}（雨强）、{@code boolean}（在下雨）、
 * {@code String}（游戏规则）⇒ 统一成"可逆的文本"最省事，而且日志里能直接读。
 * 只有"变了"才记，量级是每维每秒几条。
 */
public final class ShanhaiReplayScalarJournal {

    /** 整服（不分维度）的标量用这个 dim。 */
    public static final int DIM_SERVER = -1;

    /**
     * 内置 key 的名字表 + 游戏规则的动态 key 分配。
     *
     * <p>纯逻辑：名字 ↔ 小整数，日志/自检/status 都按名字比对（改名字等于改对外契约）。
     */
    public static final class Keys {

        public static final int DAY_TIME = 0;
        public static final int RAIN_LEVEL = 1;
        public static final int THUNDER_LEVEL = 2;
        public static final int RAINING = 3;
        public static final int THUNDERING = 4;

        private static final List<String> NAMES = new ArrayList<>(List.of(
                "day_time", "rain_level", "thunder_level", "raining", "thundering"));

        private static final String RULE_PREFIX = "rule:";

        private static final Map<String, Integer> IDS = new HashMap<>();

        static {
            for (int i = 0; i < NAMES.size(); i++) {
                IDS.put(NAMES.get(i), i);
            }
        }

        private Keys() {
        }

        /** 内置 key 的条数（自检用它断言"名字表没被顺手改小"）。 */
        public static int builtinCount() {
            return NAMES.size();
        }

        /** 名字 → id（内置或已分配的游戏规则）。 */
        public static synchronized int idOf(String name) {
            final Integer hit = IDS.get(name);
            if (hit != null) {
                return hit.intValue();
            }
            final int id = NAMES.size();
            NAMES.add(name);
            IDS.put(name, id);
            return id;
        }

        /** 游戏规则名 → id（自动带 {@code rule:} 前缀，和维度标量分开）。 */
        public static int ruleId(String ruleName) {
            return idOf(RULE_PREFIX + ruleName);
        }

        /** id → 名字；越界返回 {@code "unknown:<id>"}（调用方必须容忍）。 */
        public static synchronized String nameOf(int id) {
            if (id < 0 || id >= NAMES.size()) {
                return "unknown:" + id;
            }
            return NAMES.get(id);
        }

        /** 这条 key 是不是游戏规则（只读观测）。 */
        public static boolean isRule(int id) {
            return nameOf(id).startsWith(RULE_PREFIX);
        }

        /** 清掉动态分配（换世界时；内置 key 永远在）。 */
        public static synchronized void reset() {
            while (NAMES.size() > 5) {
                IDS.remove(NAMES.remove(NAMES.size() - 1));
            }
        }
    }

    /**
     * 一条标量变化。
     *
     * @param tick   世界 tick
     * @param dim    维度（{@link #DIM_SERVER} = 整服）
     * @param keyId  {@link Keys} 的 id
     * @param before 变化前的值（= 回退要写回的值）
     * @param after  变化后的值
     */
    public record Scalar(int tick, int dim, int keyId, String before, String after) {

        public String keyName() {
            return Keys.nameOf(this.keyId);
        }

        public String toText() {
            return "tick=" + this.tick + " dim=" + this.dim + " key=" + keyName()
                    + " before=" + this.before + " after=" + this.after;
        }

        @Override
        public String toString() {
            return toText();
        }
    }

    private final ShanhaiReplayTimeRing<Scalar> ring;

    public ShanhaiReplayScalarJournal(int capacity) {
        this.ring = new ShanhaiReplayTimeRing<>(capacity);
    }

    /** 变了才记；同 tick 同 dim 同 key 合并成"最早 before + 最新 after"。 */
    public boolean record(int tick, int dim, int keyId, String before, String after) {
        if (before == null || after == null || before.equals(after)) {
            return false;
        }
        // key = dim 与 keyId 拼起来（两者都是小整数 ⇒ 不会撞）
        final long key = ((long) dim << 32) | (keyId & 0xFFFFFFFFL);
        final Scalar scalar = new Scalar(tick, dim, keyId, before, after);
        return this.ring.add(tick, key, scalar,
                (older, newer) -> older.dim() == newer.dim() && older.keyId() == newer.keyId()
                        ? new Scalar(newer.tick(), newer.dim(), newer.keyId(), older.before(), newer.after())
                        : null,
                ShanhaiReplayConst.MAX_PER_TICK);
    }

    public int size() {
        return this.ring.size();
    }

    public boolean isEmpty() {
        return this.ring.isEmpty();
    }

    public int newestTick() {
        return this.ring.newestTick();
    }

    public int oldestTick() {
        return this.ring.oldestTick();
    }

    public long droppedOldest() {
        return this.ring.droppedOldest();
    }

    public long overflow() {
        return this.ring.overflow();
    }

    public List<Scalar> snapshotNewestFirst() {
        return this.ring.snapshotNewestFirst();
    }

    public List<Scalar> takeNewestTick() {
        return this.ring.takeNewestTick();
    }

    public void clear() {
        this.ring.clear();
    }

    public String summary() {
        return this.ring.summary();
    }

    // ------------------------------------------------------------------ 回退

    /** 写回结果。 */
    public enum Outcome {
        /** 写回去了。 */
        WRITTEN,
        /** 只读观测（游戏规则）⇒ 矩阵里点名。 */
        UNSUPPORTED,
        /** 有入口但写失败。 */
        FAILED,
        /**
         * 这一条<b>不需要写</b>：目标值已经等于世界里的值，或这个维度这一 tick 已经写过。
         *
         * <p>🔴 与 {@link #FAILED} 严格分开：它计进 {@code skipped}，<b>不</b>计进 {@code failed}。
         * 天气写回的节流阀（{@code ShanhaiReplayWeatherGate}）就返回这个 —— 真机实测里
         * "一拍 45 条标量"如果每条都调 {@code setWeatherParameters}，客户端看到的就是下雨鬼畜。
         */
        SKIPPED
    }

    /** 回退落点（运行期 = {@code setDayTime}/{@code setWeatherParameters}；自检 = 假体）。 */
    public interface Sink {

        Outcome write(int dim, int keyId, String value);
    }

    /**
     * @param written      写回条数
     * @param unsupported  只读观测、没法写回的条数
     * @param failed       写失败条数
     * @param skipped      节流掉、<b>不需要写</b>的条数（{@link Outcome#SKIPPED}）
     */
    public record Result(int written, int unsupported, int failed, int skipped) {

        public static Result empty() {
            return new Result(0, 0, 0, 0);
        }
    }

    /** 按 LIFO（新→旧）把标量写回去。 */
    public static Result apply(List<Scalar> newestFirst, Sink sink) {
        if (newestFirst == null || newestFirst.isEmpty() || sink == null) {
            return Result.empty();
        }
        int written = 0;
        int unsupported = 0;
        int failed = 0;
        int skipped = 0;
        for (Scalar s : newestFirst) {
            switch (sink.write(s.dim(), s.keyId(), s.before())) {
                case WRITTEN -> ++written;
                case UNSUPPORTED -> ++unsupported;
                case SKIPPED -> ++skipped;
                default -> ++failed;
            }
        }
        return new Result(written, unsupported, failed, skipped);
    }
}
