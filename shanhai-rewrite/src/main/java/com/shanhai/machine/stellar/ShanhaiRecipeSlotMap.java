package com.shanhai.machine.stellar;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;

/**
 * <b>【「配方 → 槽号」提示表】—— 纯逻辑核（零 Minecraft / GT 依赖 ⇒ 可离线 javac + 裸 JVM 跑探针）。</b>
 *
 * <h2>一、它解决的是哪一笔开销（字节码依据，不是估计）</h2>
 * 上游 {@code org.gtlcore.gtlcore.api.machine.trait.MEPatternRecipeHandlePart#handleRecipe}
 * （反编译 {@code temp\wildbug\dec\src-out6\...\MEPatternRecipeHandlePart.java}）有两条路：
 * <pre>
 *   :119   IntSet cachedSlots = this.recipes2SlotsMap.getValues(recipe);        ← 「配方 → 槽号」表
 *   :120-130  非空 ⇒ 【快路】只遍历这几个槽，任一接下就 return slot;
 *   :132-142  【兜底路】for (int slot : itemHandler.getActiveSlots()) { … }
 *              ⚠️ 兜底路第一步就是 if (!cachedSlots.contains(slot) &amp;&amp; …) ⇒ 快路试过的槽会被<b>跳过</b>
 *   :144   return -1;
 * </pre>
 * ⇒ 本机的活跃槽数上界是 1130 ＋ 展开槽（{@code shanhaiCandidateActiveSlots()}），
 * <b>表里没有的那条候选配方，每条都要扫全部活跃槽</b>；表里有，就<b>只问 1 格</b>。
 *
 * <h2>二、⚠️ 本类只是「提示」，正确性<b>不</b>依赖它（这是本刀能零风险落地的前提）</h2>
 * 三条保护，全在上游代码里，不是我们加的：
 * <ol>
 *   <li><b>提示错了 ⇒ 自动回退</b>：快路在表里那个槽上过不了（{@code &&} 短路返回 false）⇒
 *       代码继续走兜底路，把<b>其余全部活跃槽</b>问一遍 ⇒ 结论与"没有表"完全相同；</li>
 *   <li><b>提示的槽不是本机的槽 ⇒ 同样只是回退</b>：{@code getInternalSlotOrNull(怪槽号)} 拿不到槽，
 *       {@code meHandleRecipeInner} 不会接 ⇒ 快路失败 ⇒ 兜底路接手；</li>
 *   <li><b>快路命中 ⇒ 兜底路必然不会再选别的槽</b>（{@code :133} 那句 {@code cachedSlots.contains}
 *       把表里的槽从兜底路里剔掉，而命中意味着已经 return 了）。
 *       ⇒ 这也给了我们一条<b>可判死的日志判据</b>：见 {@link #noteClaim}。</li>
 * </ol>
 * ⇒ 唯一会变的东西是<b>槽的优先级</b>：表里那个槽会被<b>先</b>问。这与上游自己的行为同源 ——
 * {@code RecipeRunner} 在 {@code simulated==true && strategy.cacheToHandlePartMap} 时本来就会
 * {@code tryAddAndActiveMERhp} → {@code setLastRecipe2Slot}（{@code RecipeRunner.java:180/205/249/278}），
 * 也就是「认领成功过的槽，下次优先」。本类只是把这件事做得更早、更全。
 *
 * <h2>三、失效点（少清一个就可能"问错格"）</h2>
 * 机器侧的调用点（全部在 {@code StellarSampleAssemblerPartMachine} 里）：
 * <ul>
 *   <li>{@link #forgetSlot}：样板变化（{@code onPatternChange}）、展开槽被回收（分配器释放）；</li>
 *   <li>{@link #forgetRecipe}：某条配方被登记到新槽、旧归属作废；</li>
 *   <li>{@link #clear}：通配符样板内容变化 / 展开表重建 / 电路变化 / 读档 —— 全部可能的归属一起作废。</li>
 * </ul>
 *
 * <h2>四、可一键关</h2>
 * {@code SHANHAI_DISABLE_RECIPE_SLOTMAP=1}（{@code -D} 或环境变量，<b>每次现读</b>）⇒
 * {@link #disabled()} 为真 ⇒ {@link #noteClaim} 一律回 {@link Outcome#IGNORED}、
 * {@link #set} 一律不写 ⇒ <b>表恒空 ⇒ 引擎永远走兜底路（问全部槽）</b>。
 * 这条同时是<b>负对照探针</b>的入口。
 */
public final class ShanhaiRecipeSlotMap<T> {

    /** 回退位：{@code 1/true/yes/on} ⇒ 本表整体不生效（行为回到「每条候选扫全部活跃槽」）。 */
    public static final String DISABLE_ENV = "SHANHAI_DISABLE_RECIPE_SLOTMAP";

    /** 「表里没有这条配方」的哨兵值（= 上游 {@code handleRecipe} 的 {@code iconst_m1} 同款）。 */
    public static final int NO_SLOT = -1;

    /** 一次「某个槽接下了这条配方」的判定结果（三种都会落到各自的日志行上）。 */
    public enum Outcome {
        /** 表里本来就有、而且正是这个槽 ⇒ 上游走的是【快路】只问了这一格。 */
        HIT,
        /** 表里没有这条配方 ⇒ 这一次是【兜底路】扫出来的（已登记，下次就快了）。 */
        NEW,
        /** 表里记的是<b>别的</b>槽 ⇒ 快路失败、兜底路跑过 ⇒ 已经就地改正成这一次的槽。 */
        MISS_CORRECTED,
        /** 关闭 / 配方为空 ⇒ 什么都没做。 */
        IGNORED
    }

    /** 配方 → 槽号。 */
    private final Map<T, Integer> recipe2Slot = new HashMap<>();

    /**
     * 槽号 → 配方。
     *
     * <p>为什么要反向表：失效点是「<b>某一格</b>变了」（样板换了 / 展开槽被回收），
     * 那时手里只有槽号，没有配方 ⇒ 没有反向表就只能整表清，等于放弃优化。
     * 一槽最多一条（本机口径「一个池只管一条配方」，见 {@code ShanhaiSlotTypeGateMixin} 类注释）。
     */
    private final Map<Integer, T> slot2Recipe = new HashMap<>();

    private long setCount;
    private long hitCount;
    private long newCount;
    private long missCount;
    private long forgetCount;
    private long resetCount;
    private long droppedByReset;

    /** 回退位是否打开（每次现读，不缓存 ⇒ 运行期也能改；与类型闸 / 预检闸同风格）。 */
    public static boolean disabled() {
        try {
            if (isTruthy(System.getProperty(DISABLE_ENV))) {
                return true;
            }
            return isTruthy(System.getenv(DISABLE_ENV));
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean isTruthy(String raw) {
        if (raw == null) {
            return false;
        }
        final String v = raw.trim();
        return v.equals("1") || v.equalsIgnoreCase("true") || v.equalsIgnoreCase("yes") || v.equalsIgnoreCase("on");
    }

    /**
     * 登记「这条配方归这个槽」。
     *
     * @return {@code true} = 真的改动了（新条目，或把旧归属改写成了新槽）
     */
    public boolean set(@Nullable T recipe, int slot) {
        if (recipe == null || slot < 0 || disabled()) {
            return false;
        }
        final Integer prev = this.recipe2Slot.put(recipe, Integer.valueOf(slot));
        if (prev != null && prev.intValue() == slot) {
            // 同一条归属重复登记 ⇒ 不动反向表（幂等）
            return false;
        }
        if (prev != null) {
            final T oldSlotRecipe = this.slot2Recipe.get(prev);
            if (oldSlotRecipe == recipe) {
                this.slot2Recipe.remove(prev);
            }
        }
        final T stolen = this.slot2Recipe.put(Integer.valueOf(slot), recipe);
        if (stolen != null && stolen != recipe) {
            // 一格只留一条：被挤掉的那条映射也要摘掉（否则「配方 → 槽」会指向已被别人占用的格）
            final Integer stale = this.recipe2Slot.get(stolen);
            if (stale != null && stale.intValue() == slot) {
                this.recipe2Slot.remove(stolen);
            }
        }
        this.setCount++;
        return true;
    }

    /** 这条配方记的是哪个槽（{@link #NO_SLOT} = 表里没有 / 已关闭）。 */
    public int slotOf(@Nullable T recipe) {
        if (recipe == null || disabled()) {
            return NO_SLOT;
        }
        final Integer slot = this.recipe2Slot.get(recipe);
        return slot == null ? NO_SLOT : slot.intValue();
    }

    /** 这一格记的是哪条配方（{@code null} = 没记）。 */
    public @Nullable T recipeOf(int slot) {
        return this.slot2Recipe.get(Integer.valueOf(slot));
    }

    /**
     * <b>唯一判定点</b>（探针实跑的就是这一个方法）：某个槽刚刚以「接下了」为结论被问完。
     *
     * @param recipe    被问的那条配方
     * @param slot      接下的那个槽号（必须 <b>≥ 0</b>；{@code -1} = 谁都没接，调用方不该来问）
     * @return 见 {@link Outcome}；{@link Outcome#MISS_CORRECTED} 的区分意义见类注释 §二.3
     */
    public @NotNull Outcome noteClaim(@Nullable T recipe, int slot) {
        if (recipe == null || slot < 0 || disabled()) {
            return Outcome.IGNORED;
        }
        final int known = slotOf(recipe);
        if (known == NO_SLOT) {
            this.newCount++;
            set(recipe, slot);
            return Outcome.NEW;
        }
        if (known == slot) {
            this.hitCount++;
            return Outcome.HIT;
        }
        // 表里记的是别的槽 ⇒ 快路在那个槽上没接住，兜底路扫到了这里。
        this.missCount++;
        set(recipe, slot);
        return Outcome.MISS_CORRECTED;
    }

    /** 某格失效（样板换 / 展开槽被回收 / 电路变）⇒ 摘掉这一格名下的那条归属。 */
    public boolean forgetSlot(int slot) {
        final T recipe = this.slot2Recipe.remove(Integer.valueOf(slot));
        if (recipe == null) {
            return false;
        }
        final Integer cur = this.recipe2Slot.get(recipe);
        if (cur != null && cur.intValue() == slot) {
            this.recipe2Slot.remove(recipe);
        }
        this.forgetCount++;
        return true;
    }

    /** 某条配方失效（被登记到别的槽 / 样板内容变了）。 */
    public boolean forgetRecipe(@Nullable T recipe) {
        if (recipe == null) {
            return false;
        }
        final Integer slot = this.recipe2Slot.remove(recipe);
        if (slot == null) {
            return false;
        }
        final T back = this.slot2Recipe.get(slot);
        if (back == recipe) {
            this.slot2Recipe.remove(slot);
        }
        this.forgetCount++;
        return true;
    }

    /** 整表作废（通配符样板内容变化 / 展开表重建 / 读档）。返回清掉了几条。 */
    public int clear() {
        final int n = this.recipe2Slot.size();
        this.recipe2Slot.clear();
        this.slot2Recipe.clear();
        if (n > 0) {
            this.resetCount++;
            this.droppedByReset += n;
        }
        return n;
    }

    public int size() {
        return this.recipe2Slot.size();
    }

    public boolean isEmpty() {
        return this.recipe2Slot.isEmpty();
    }

    /** 只读遍历（{@code (recipe, slot)}），供机器把表重新推给上游用。 */
    public void forEach(@NotNull java.util.function.BiConsumer<T, Integer> consumer) {
        for (Map.Entry<T, Integer> e : this.recipe2Slot.entrySet()) {
            consumer.accept(e.getKey(), e.getValue());
        }
    }

    public long setCount() {
        return this.setCount;
    }

    public long hitCount() {
        return this.hitCount;
    }

    public long newCount() {
        return this.newCount;
    }

    public long missCount() {
        return this.missCount;
    }

    public long forgetCount() {
        return this.forgetCount;
    }

    public long resetCount() {
        return this.resetCount;
    }

    public long droppedByReset() {
        return this.droppedByReset;
    }

    /** 一行读数（日志 / 自证用）：{@code size=3 hits=… misses=… maps={1132=gtceu:…}}。 */
    public @NotNull String describe(@NotNull java.util.function.Function<T, String> idOf) {
        final StringBuilder sb = new StringBuilder(128);
        sb.append("size=").append(this.recipe2Slot.size())
                .append(" sets=").append(this.setCount)
                .append(" hits=").append(this.hitCount)
                .append(" news=").append(this.newCount)
                .append(" misses=").append(this.missCount)
                .append(" forgets=").append(this.forgetCount)
                .append(" resets=").append(this.resetCount)
                .append(" env=").append(DISABLE_ENV).append(disabled() ? "=1(SLOTMAP_OFF)" : "=unset/off");
        if (!this.slot2Recipe.isEmpty()) {
            sb.append(" maps={");
            boolean first = true;
            for (Map.Entry<Integer, T> e : this.slot2Recipe.entrySet()) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                sb.append(e.getKey()).append('=').append(safeId(idOf, e.getValue()));
            }
            sb.append('}');
        }
        return sb.toString();
    }

    private static <T> String safeId(@NotNull java.util.function.Function<T, String> idOf, @Nullable T recipe) {
        if (recipe == null) {
            return "null";
        }
        try {
            final String id = idOf.apply(recipe);
            return id == null ? "?" : id;
        } catch (Throwable t) {
            return "!";
        }
    }
}
