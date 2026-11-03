package com.shanhai.common.machine;

/**
 * <b>「配方类型下标」越界夹紧器 —— 纯算术，无任何 Minecraft / GTCEu 依赖。</b>
 *
 * <h2>1. 为什么需要它（2026-10-31 实机崩服）</h2>
 * GTCEu 的 {@code WorkableMultiblockMachine#getRecipeType()} 是一句裸的数组取值，
 * {@code javap -c} 逐字（{@code gtceu-1.20.1-1.4.4.jar}）：
 * <pre>
 *   public com.gregtechceu.gtceu.api.recipe.GTRecipeType getRecipeType();
 *     0: aload_0
 *     1: getfield  #64   // Field recipeTypes:[Lcom/gregtechceu/gtceu/api/recipe/GTRecipeType;
 *     4: aload_0
 *     5: getfield  #66   // Field activeRecipeType:I
 *     8: aaload           &lt;-- ★ 没有任何上界判断
 *     9: areturn
 * </pre>
 * 其中：
 * <ul>
 *   <li>{@code private final GTRecipeType[] recipeTypes} —— <b>不落盘</b>，构造期由
 *       {@code getDefinition().getRecipeTypes()} 一次性给值（{@code <init>} 偏移 21–23 是
 *       {@code activeRecipeType = 0}）⇒ <b>它的长度只由注册表决定</b>；</li>
 *   <li>{@code private int activeRecipeType} —— 字段上有
 *       {@code com.lowdragmc.lowdraglib.syncdata.annotation.Persisted}
 *       （{@code javap -v} 的 {@code RuntimeVisibleAnnotations} 逐字）⇒ <b>会写进存档</b>。</li>
 * </ul>
 * ⇒ 只要有一次改动把某台机器的类型数组<b>改短</b>（本次：9 → 6），老存档里持久化下来的
 * {@code activeRecipeType = 8} 就会在新的 6 长数组上越界：
 * <pre>
 *   java.lang.ArrayIndexOutOfBoundsException: Index 8 out of bounds for length 6
 *     at ...WorkableMultiblockMachine.getRecipeType(WorkableMultiblockMachine.java:288)
 *     at ...MutableRecipesLogic.lookupRecipeSet(MutableRecipesLogic.kt:287)
 *     ...
 *     at ...RecipeLogic.serverTick
 * </pre>
 * 上游 {@code getActiveRecipeType()} / {@code setActiveRecipeType(int)} 都是 public，
 * 但后者是<b>裸 putfield、不做校验</b>（{@code javap -c} 偏移 0–5：{@code aload_0 / iload_1 /
 * putfield / return}）⇒ 夹紧只能由我们自己做。
 *
 * <h2>2. 语义</h2>
 * <ul>
 *   <li>夹紧区间 = {@code [0, length - 1]}（下标是数组下标，不存在"0 起/1 起"的口径问题）；</li>
 *   <li>负数一律夹到 {@code 0}（老存档/损坏数据的另一种形态）；</li>
 *   <li>数组为空（{@code length <= 0}）返回 {@link #NO_VALID_INDEX}，<b>绝不抛 AIOOBE</b>
 *       —— 调用方按"拿不到有效下标"处理（抛带说明的 ISE 或直接返回 null）；</li>
 *   <li>对界内的值<b>原样返回</b>（幂等），所以它可以放在热路径上反复调用。</li>
 * </ul>
 *
 * <h2>3. 本类没有副作用</h2>
 * 它是纯函数集合，<b>不读字段、不写字段、不打日志</b>（写回由机器基类调
 * {@code setActiveRecipeType(...)} 完成）。因此可以直接被离线判据
 * （{@code temp\module-retype\}）原样编译、原样断言。
 */
public final class RecipeTypeIndexGuard {

    /**
     * 数组为空时"没有有效下标"的返回值。
     *
     * <p>刻意用 {@code -1} 而不是 {@code 0}：{@code 0} 会被下游当成一个真下标去取值，
     * 于是空数组照样炸；{@code -1} 是一个"显然不合法"的哨兵值，调用方能一眼看出"没拿到下标"。
     */
    public static final int NO_VALID_INDEX = -1;

    private RecipeTypeIndexGuard() {
        // 纯工具类
    }

    /**
     * 把（可能来自存档的）下标夹进 {@code [0, length - 1]}。
     *
     * @param rawIndex 原始下标（可能是持久化下来的越界值，例如长度 6 时的 8）
     * @param length   目标数组长度（{@code recipeTypes.length}）
     * @return 界内下标；{@code length <= 0} 时返回 {@link #NO_VALID_INDEX}
     */
    public static int clampIndex(int rawIndex, int length) {
        if (length <= 0) {
            return NO_VALID_INDEX;
        }
        // ★ 夹紧就在这一行：下界 0、上界 length-1，一次 Math.max/Math.min 双保险。
        return Math.max(0, Math.min(rawIndex, length - 1));
    }

    /**
     * 这个下标在长度为 {@code length} 的数组上会不会越界。
     *
     * @return {@code true} = 直接拿去 {@code array[rawIndex]} 会抛 AIOOBE（或读到非法位置）
     */
    public static boolean isOutOfRange(int rawIndex, int length) {
        if (length <= 0) {
            return true;
        }
        return rawIndex < 0 || rawIndex >= length;
    }

    /**
     * 安全取值：{@code array[clampIndex(rawIndex, array.length)]}。
     *
     * <p>数组为 {@code null} 或空 ⇒ 返回 {@code null}（<b>不抛</b>）。
     */
    public static <T> T at(T[] array, int rawIndex) {
        if (array == null || array.length == 0) {
            return null;
        }
        return array[clampIndex(rawIndex, array.length)];
    }
}
