package com.shanhai.common.thread;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 山海重构 · <b>并发相关数字的唯一真源</b>（世线残片 → 跨配方并行（线程），物质模块 → 并行上限）。
 *
 * <h2>🔴 为什么单独开一个「不依赖 Minecraft 的纯数据类」</h2>
 * 本工程有两处需要同一批数字，而它们跑在<b>两个不同的地方</b>：
 * <ol>
 *   <li><b>Java 侧</b>（{@code PrimordialModuleMachine} / {@code PrimordialModuleRecipeLogic}）——
 *       拿它算并行预算与候选配方条数；</li>
 *   <li><b>KubeJS 侧</b>（{@code kubejs/client_scripts/[client_scripts]shanhai_item_description.js}）——
 *       物品描述里要把这些数字写给玩家看。</li>
 * </ol>
 * 本机血账：<b>同一个数被两处各写一份，必然漂移</b>，而漂移的表现是"tooltip 说 3、机器跑 1"这种
 * 谁也看不出来的静默错误。⇒ 本类<b>刻意只 import {@code java.util.*}</b>，
 * 不碰任何 Minecraft / GTCEu / Forge / 本工程其他类（<b>连 {@code ShanhaiMod} 的日志都不引用</b> ——
 * 引用了它会顺带把那个 {@code @Mod} 类拉进解析链）。这样 KubeJS 的 {@code Java.loadClass}
 * 加载它<b>不会触发任何游戏注册表、也不会提前跑别人的静态初始化</b>，可以在脚本期安全读取。
 *
 * <h2>用户规格（逐字，本类就是它的实现）</h2>
 * <blockquote>
 *   「{@code thread_shard_1} 到 {@code thread_shard_7} 分别提供额外的 2^1 到 2^7 个跨配方并行（线程），
 *    {@code universal_parallel_overdriver} 提供 2^10 个跨配方并行（线程），<br>
 *    计算公式为 最终跨配方并行（线程）= 1 + 世线残片等级对应的跨配方并行（线程）数 × 世线残片个数」
 * </blockquote>
 * 并且他当场纠正过一次（决定性）：
 * <blockquote>「你的计算没有错，但是又是理解错了，我们那个槽位只是一个槽位，它只能放一种物品」</blockquote>
 * ⇒ 公式里的「个数」= <b>那一格里的堆叠数量</b>（把同一格塞满同种残片就翻倍）。
 * 本类只负责「一枚残片值多少」，乘数量那一步由 {@code PrimordialModuleMachine} 做
 * （它才拿得到 {@code ItemStack}）。
 *
 * <h2>本类的三个出口</h2>
 * <ol>
 *   <li>{@link #threadsForShardId(String)} —— 物品 id → 该残片提供的<b>额外</b>线程（非残片 = 0）；</li>
 *   <li>{@link #standardParallelTable()} / {@link #enhancedParallelTable()} —— 17 个物质模块的并行上限
 *       （原本写在 {@code PrimordialModuleMachine} 里，现搬到这里，仍只有这一份）；</li>
 *   <li>{@link #shardDescription(int)} / {@link #moduleDescription(int)} —— <b>给 KubeJS 直取的成品描述行</b>，
 *       让脚本侧一个数字都不用自己写。</li>
 * </ol>
 *
 * <h2>⚠️ 本类不做的事</h2>
 * 它<b>不</b>读槽、<b>不</b>算「1 + 额外 × 数量」、<b>不</b>碰任何机器实例 ——
 * 那些在 {@code PrimordialModuleMachine#getCrossRecipeThreads()} 一处完成（单一接缝）。
 */
public final class ShanhaiConcurrencyTables {

    private ShanhaiConcurrencyTables() {}

    // ═══════════════════════════════════════════════════════════════════════
    //  一、世线残片 → 额外跨配方并行（线程）
    // ═══════════════════════════════════════════════════════════════════════

    /** 空槽 / 非残片时的跨配方并行（线程）基础值 —— 用户公式里那个 {@code 1}。 */
    public static final int BASE_CROSS_RECIPE_THREADS = 1;

    /**
     * <b>寰宇并行超限器</b>的指数 = {@code 10}（⇒ 2^10 = 1024）。
     *
     * <p>用户原话：「{@code universal_parallel_overdriver} 提供 2^10 个跨配方并行（线程）」。
     * <b>它与 1..7 号残片不是同一个等差数列</b>（不是"8 号残片 = 2^8"），所以单列一个常量，
     * 不许在别处再写一遍 {@code 10}。
     */
    public static final int OVERDRIVER_EXPONENT = 10;

    /**
     * <b>世线残片表（8 条，唯一真源）</b>：物品 id → 指数 N（线程 = 2^N）。
     *
     * <p>键 = {@code ShanhaiItems} 里 {@code module("thread_shard_N")} 注册出来的全名
     * （{@code namespace:path}，与 {@code ForgeRegistries.ITEMS.getKey(...).toString()} 同形）。
     * <b>拼错一个字符的后果是静默的</b>（查不到就退回 0 额外线程），所以有
     * {@link #selfTest()} 在加载期做 fail-fast。
     */
    private static final Map<String, Integer> SHARD_EXPONENTS = new LinkedHashMap<>();

    /** 残片的<b>注册顺序</b>（= 描述书写的顺序，也是 KubeJS 取值的下标顺序）。 */
    private static final String[] SHARD_IDS;

    static {
        // 1..7 号：用户规格「thread_shard_1 到 thread_shard_7 分别提供额外的 2^1 到 2^7」
        for (int n = 1; n <= 7; n++) {
            SHARD_EXPONENTS.put("shanhai:thread_shard_" + n, n);
        }
        // 超限器：用户规格「universal_parallel_overdriver 提供 2^10」
        SHARD_EXPONENTS.put("shanhai:universal_parallel_overdriver", OVERDRIVER_EXPONENT);
        SHARD_IDS = SHARD_EXPONENTS.keySet().toArray(new String[0]);
    }

    /** 2 的 {@code n} 次方（{@code n ∈ [0,30]}；调用方保证范围，越界返回 0 而不是回绕成负数）。 */
    public static int twoPow(int n) {
        if (n < 0 || n > 30) {
            return 0;
        }
        return 1 << n;
    }

    /**
     * 该物品 id 提供的<b>额外</b>跨配方并行（线程）；<b>不是残片 ⇒ 0</b>。
     *
     * <p>⚠️ 返回的是<b>一枚</b>的值（{@code 2^N}），<b>没有</b>乘堆叠数量 ——
     * 乘数量需要 {@code ItemStack}，由 {@code PrimordialModuleMachine} 做。
     *
     * @param itemId 形如 {@code shanhai:thread_shard_3}；{@code null} / 空 / 未注册 ⇒ 0
     */
    public static int threadsForShardId(String itemId) {
        if (itemId == null) {
            return 0;
        }
        final Integer exponent = SHARD_EXPONENTS.get(itemId);
        return exponent == null ? 0 : twoPow(exponent);
    }

    /** 该物品 id 是不是世线残片（含超限器）。 */
    public static boolean isShardId(String itemId) {
        return itemId != null && SHARD_EXPONENTS.containsKey(itemId);
    }

    /** 残片条数（用户规格 = 7 + 1 = 8）。 */
    public static int shardCount() {
        return SHARD_IDS.length;
    }

    /** 第 {@code index} 个残片的物品 id（越界抛 {@link IndexOutOfBoundsException}，让 KJS 侧当场看得见）。 */
    public static String shardIdAt(int index) {
        return SHARD_IDS[index];
    }

    /** 第 {@code index} 个残片的指数 N。 */
    public static int shardExponentAt(int index) {
        return SHARD_EXPONENTS.get(SHARD_IDS[index]);
    }

    /** 第 {@code index} 个残片提供的额外线程（{@code 2^N}，<b>单枚</b>）。 */
    public static int shardThreadsAt(int index) {
        return twoPow(shardExponentAt(index));
    }

    /**
     * <b>最终跨配方并行（线程）</b> = {@code 1 + 单枚值 × 该格数量}（饱和到 {@code Integer.MAX_VALUE}）。
     *
     * <p>这是用户公式<b>唯一的一处实现</b>：Java 侧算线程数时调它，KubeJS 侧写描述时也调它，
     * 所以"描述里写的公式"与"机器真正算的公式"不可能对不上。
     *
     * @param perShardThreads 一枚残片的值（{@link #threadsForShardId(String)}；非残片传 0）
     * @param count           该格里的实际数量（空槽传 0；上限 64）
     */
    public static int finalThreads(int perShardThreads, int count) {
        if (perShardThreads <= 0 || count <= 0) {
            return BASE_CROSS_RECIPE_THREADS;
        }
        final long total = BASE_CROSS_RECIPE_THREADS + (long) perShardThreads * count;
        return total >= Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) total;
    }

    /** 「额外」那一部分 = {@code 最终 - 1}（即 {@code 单枚值 × 数量}）。 */
    public static int extraThreads(int perShardThreads, int count) {
        return finalThreads(perShardThreads, count) - BASE_CROSS_RECIPE_THREADS;
    }

    /**
     * <b>一行列出全部 8 种残片的单枚值</b>（机器 GUI 线程槽的悬浮说明用）。
     *
     * <p>结果形如 {@code 1号=2 / 2号=4 / … / 7号=128 / 超限器=1024}。
     * 名字不手写：{@code thread_shard_N} 取 N，"超限器"是唯一那个例外分支
     * ⇒ 将来加残片只要动 {@link #SHARD_EXPONENTS} 一处，这一行自动跟上。
     */
    public static String shardSummary() {
        final String prefix = "shanhai:thread_shard_";
        final StringBuilder sb = new StringBuilder();
        for (int i = 0; i < SHARD_IDS.length; i++) {
            if (i > 0) {
                sb.append(" / ");
            }
            final String id = SHARD_IDS[i];
            sb.append(id.startsWith(prefix) ? id.substring(prefix.length()) + "号" : "超限器")
                    .append('=').append(shardThreadsAt(i));
        }
        return sb.toString();
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  二、17 个物质模块 → 并行上限（两档，原有数据，从 PrimordialModuleMachine 搬来）
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * <b>表#2/#3</b>（上游 20 台的共用值）。键 = {@code ShanhaiItems} 的注册 id。
     *
     * <p>上游原表用拼音 id（{@code wzrm} / {@code wzjc} / …），这里的英文 id 取自本工程
     * {@code ShanhaiItems} 类注释里那份<b>官方改名对照表</b>。
     * <p>🔴 <b>正面对照</b>（与数值一起搬自原实现，未改一个字符）：把同一份对照表套到
     * {@link #enhancedParallelTable()} 上，与本工程 {@code PrimordialMatterRecombinatorCore}
     * 里那张老表 <b>17/17 逐项吻合</b> ⇒ 对照表本身是对的。
     *
     * <h2>🔴 2026-10-03 重排（用户拍板，与 {@code PrimordialModuleMachine.MODULE_LEVELS} 同批）</h2>
     * <b>两条同时发生</b>，都必须看清：
     * <ol>
     *   <li><b>插入顺序改成等级 1..17</b>（旧插入序 = 上游那张表的抄写顺序，<b>不是</b>等级序）
     *       —— 因为下面的 {@link #MODULE_ORDER} 就是取本表的插入序，而它的 javadoc 一直写着
     *       「等级 1..17」。改之前那句话是<b>不成立</b>的（旧插入序 = 1,2,3,5,7,8,9,11,12,14,15,17,16,6,4,10,13）。
     *       KubeJS 侧按 {@code moduleIdAt(i)} ↔ {@code moduleDescription(i)} <b>成对</b>取用，
     *       所以这一项不改变任何物品的显示内容。</li>
     *   <li><b>数值按新等级表重新落位</b>（用户「物理台阶」方案）：1-4 与 17 的数值<b>一个字节不改</b>；
     *       旧 5..16 那 12 个值各自<b>从小到大</b>重新分配到新 5..16 ⇒ 档位越高并行越大、曲线零回落。</li>
     * </ol>
     * ⛔ 旧值（作废，逐字留档，便于对照旧存档 / 旧报告）：按<b>旧等级</b> 5..16 =
     * 嬗变 2048 · 暗星 4096 · 重组 16384 · 虚数跃迁 65536 · 归零 524288 · 巅峰 1048576 ·
     * 升维 2097152 · 超限 268435456 · 混沌 536870912 · 永恒 2147483647 ·
     * 物质创造 4611686018427387903 · 现实锚点 6917529027641081855。
     */
    private static final Map<String, Long> STANDARD_PARALLEL = new LinkedHashMap<>();

    /** <b>表#1</b>（上游 3 台：物质重组核心 / 奇点反演核心 / 因果编织矩阵）。 */
    private static final Map<String, Long> ENHANCED_PARALLEL = new LinkedHashMap<>();

    static {
        // ───── 表#2/#3 ───── 上游出处：PrimordialEngravingModule:86-105
        //                       / PrimordialParallelProcessingModuleBase:54-65
        // 🔴 行序 = 等级 1..17（2026-10-03 起）；每行行尾的 `// 等级N` 是本次加的可核对标注。
        STANDARD_PARALLEL.put("shanhai:introductory_material_module", 128L);                     // 等级1  wzrm
        STANDARD_PARALLEL.put("shanhai:basic_material_module", 256L);                            // 等级2  wzjc
        STANDARD_PARALLEL.put("shanhai:material_deduction_module", 512L);                        // 等级3  wzcz1
        STANDARD_PARALLEL.put("shanhai:virtual_image_material_module", 1024L);                   // 等级4  wzxc
        STANDARD_PARALLEL.put("shanhai:material_recombination_module", 2048L);                   // 等级5  wzcz2
        STANDARD_PARALLEL.put("shanhai:zeroing_material_module", 4096L);                         // 等级6  wzgl
        STANDARD_PARALLEL.put("shanhai:dark_star_material_module", 16384L);                      // 等级7  wzax
        STANDARD_PARALLEL.put("shanhai:imaginary_material_transition_remolding_module", 65536L);  // 等级8  wzqs
        STANDARD_PARALLEL.put("shanhai:transformation_material_module", 524288L);                // 等级9  wzsb
        STANDARD_PARALLEL.put("shanhai:dimensional_ascension_material_module", 1048576L);        // 等级10 wzsw
        STANDARD_PARALLEL.put("shanhai:apex_material_module", 2097152L);                         // 等级11 wzhy
        STANDARD_PARALLEL.put("shanhai:chaos_material_module", 268435456L);                      // 等级12 wzdf
        STANDARD_PARALLEL.put("shanhai:transfinite_material_module", 536870912L);                // 等级13 wzcx
        STANDARD_PARALLEL.put("shanhai:eternal_material_module", 2147483647L);                   // 等级14 wzyh
        STANDARD_PARALLEL.put("shanhai:material_creation_module", 4611686018427387903L);         // 等级15 wzcz3
        STANDARD_PARALLEL.put("shanhai:reality_anchor_module", 6917529027641081855L);            // 等级16 reality_anchor_module
        STANDARD_PARALLEL.put("shanhai:genesis_reality_modification_module", Long.MAX_VALUE);    // 等级17 create_mk

        // ───── 表#1 ───── 上游出处：PrimordialMatterRecombinatorCore:86-105
        // 行序同样 = 等级 1..17（2026-10-03 起）。
        ENHANCED_PARALLEL.put("shanhai:introductory_material_module", 256L);                     // 等级1  wzrm
        ENHANCED_PARALLEL.put("shanhai:basic_material_module", 1024L);                           // 等级2  wzjc
        ENHANCED_PARALLEL.put("shanhai:material_deduction_module", 2048L);                       // 等级3  wzcz1
        ENHANCED_PARALLEL.put("shanhai:virtual_image_material_module", 1024L);                   // 等级4  wzxc（与基础模块同为 1024，非笔误）
        ENHANCED_PARALLEL.put("shanhai:material_recombination_module", 4096L);                   // 等级5  wzcz2
        ENHANCED_PARALLEL.put("shanhai:zeroing_material_module", 8192L);                         // 等级6  wzgl
        ENHANCED_PARALLEL.put("shanhai:dark_star_material_module", 16384L);                      // 等级7  wzax
        ENHANCED_PARALLEL.put("shanhai:imaginary_material_transition_remolding_module", 65536L);  // 等级8  wzqs
        ENHANCED_PARALLEL.put("shanhai:transformation_material_module", 524288L);                // 等级9  wzsb
        ENHANCED_PARALLEL.put("shanhai:dimensional_ascension_material_module", 1048576L);        // 等级10 wzsw
        ENHANCED_PARALLEL.put("shanhai:apex_material_module", 2097152L);                         // 等级11 wzhy
        ENHANCED_PARALLEL.put("shanhai:chaos_material_module", 268435456L);                      // 等级12 wzdf
        ENHANCED_PARALLEL.put("shanhai:transfinite_material_module", 536870912L);                // 等级13 wzcx
        ENHANCED_PARALLEL.put("shanhai:eternal_material_module", 2147483647L);                   // 等级14 wzyh
        ENHANCED_PARALLEL.put("shanhai:material_creation_module", 4611686018427387903L);         // 等级15 wzcz3
        ENHANCED_PARALLEL.put("shanhai:reality_anchor_module", 6917529027641081855L);            // 等级16 reality_anchor_module
        ENHANCED_PARALLEL.put("shanhai:genesis_reality_modification_module", Long.MAX_VALUE);    // 等级17 create_mk
    }

    /** 表#2/#3（只读视图；{@code PrimordialModuleMachine} 的 {@code ParallelTable.STANDARD} 用它）。 */
    public static Map<String, Long> standardParallelTable() {
        return Collections.unmodifiableMap(STANDARD_PARALLEL);
    }

    /** 表#1（只读视图；{@code ParallelTable.ENHANCED} 用它）。 */
    public static Map<String, Long> enhancedParallelTable() {
        return Collections.unmodifiableMap(ENHANCED_PARALLEL);
    }

    /** 物质模块条数（= 17）。 */
    public static int moduleCount() {
        return MODULE_ORDER.length;
    }

    /**
     * <b>17 个物质模块的 id 顺序</b>（等级 1..17）。
     *
     * <p>🔴 这里<b>不重新写一遍 id 表</b>：顺序直接取自 {@link #STANDARD_PARALLEL} 的插入序，
     * 而该插入序 = 上游那张表的顺序。{@code PrimordialModuleMachine} 另有严格的
     * 「两张表的键集合必须逐项相等且都在 17 个模块表里」自检，所以这张表不可能与它分叉。
     */
    private static final String[] MODULE_ORDER = STANDARD_PARALLEL.keySet().toArray(new String[0]);

    /** 第 {@code index} 个物质模块的注册 id。 */
    public static String moduleIdAt(int index) {
        return MODULE_ORDER[index];
    }

    /** 第 {@code index} 个物质模块在 {@code 表#2/#3} 里的并行上限。 */
    public static long moduleStandardParallelAt(int index) {
        return STANDARD_PARALLEL.getOrDefault(MODULE_ORDER[index], 0L);
    }

    /** 第 {@code index} 个物质模块在 {@code 表#1}（强化档）里的并行上限。 */
    public static long moduleEnhancedParallelAt(int index) {
        return ENHANCED_PARALLEL.getOrDefault(MODULE_ORDER[index], 0L);
    }

    /** 数字的显示文案：{@code Long.MAX_VALUE} 那一档按老山海口径画「无限」，其余千分位。 */
    public static String formatParallel(long value) {
        if (value >= Long.MAX_VALUE / 2) {
            return "无限";
        }
        return String.format(Locale.ROOT, "%,d", value);
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  三、给 KubeJS 的成品描述（脚本侧一个数字都不用自己写）
    // ═══════════════════════════════════════════════════════════════════════
    //
    //  形态：方法返回**单个字符串**，多行用 '\n' 分隔。KubeJS 侧 desc.split("\n") 即得多行。
    //  之所以不返回 List：脚本引擎跨语言传集合要额外处理，返回纯字符串 + split 是最不容易坏的形状。
    //
    //  颜色码口径（见 kubejs/client_scripts/[client_scripts]shanhai_item_description.js 的类注释）：
    //    · `&$golden-正文`  本工程自己的特殊渲染（ShanhaiTextParser）
    //    · `§7` / `§6` / `§b`  原版格式码
    //  🔴 同一行里**不许**把 § 与 &$ 混用（解析器判据：正文含 § 就整行交回原版渲染）。

    /**
     * <b>第 {@code index} 个世线残片的物品描述</b>（3 行，{@code \n} 分隔）。
     *
     * <p>内容 = 用户点单的两件事：①它提供多少跨配方并行（线程）②怎么用、怎么算。
     * 数字全部来自本类的表，<b>没有一个字面量是第二份真源</b>。
     */
    public static String shardDescription(int index) {
        final int exponent = shardExponentAt(index);
        final int threads = shardThreadsAt(index);
        return "&$golden-提供额外跨配方并行（线程）：" + threads
                + "\n§7放入模块的【跨配方并行（线程）槽】生效"
                + "\n§7最终跨配方并行（线程） = 1 + " + threads + " × 该格数量"
                + "\n§8（2^" + exponent + "；一格最多堆 64 个，放满 ⇒ 额外 "
                + formatParallel((long) threads * 64) + " 线程）";
    }

    /**
     * <b>第 {@code index} 个物质模块的物品描述</b>（2 行，{@code \n} 分隔）。
     *
     * <p>用户点单：「把提供的并行数补充在各等级物质模块的描述中」。
     * 因为本工程有<b>两张并行表</b>（21 台一般模块用 {@code 表#2/#3}，
     * 物质重组核心 / 奇点反演核心 / 因果编织矩阵这 3 台用 {@code 表#1}），
     * 所以两档都写出来 —— 只写一档会变成"描述与机器对不上"。
     */
    public static String moduleDescription(int index) {
        return "§7提供并行上限：§6" + formatParallel(moduleStandardParallelAt(index))
                + "\n§7强化档机器（物质重组核心 / 奇点反演核心 / 因果编织矩阵）：§6"
                + formatParallel(moduleEnhancedParallelAt(index));
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  四、加载期自检（fail-fast + 一条可 grep 的日志）
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * <b>世线残片表自检</b> —— 由 {@code PrimordialModuleMachine#assertParallelTablesConsistent()}
     * 在<b>机器注册期</b>调用（那已经是既有的加载期 fail-fast 入口，不新增任何加载钩子）。
     *
     * <p>判据四条：
     * <ol>
     *   <li>残片条数 = 8（7 张 + 超限器）；</li>
     *   <li>1..7 号残片的单枚值逐档 = {@code 2,4,8,16,32,64,128}；</li>
     *   <li>超限器 = {@code 1024}（{@code 2^10}），且它<b>不等于</b> {@code 2^8}（防"顺手写成 8 号"）；</li>
     *   <li><b>正面对照</b>：拿一组已知为真的取值过一遍 {@link #finalThreads(int, int)}
     *       （空槽 = 1、3 号 ×1 = 9、超限器 ×64 = 65537），并断言非残片恒为 1。</li>
     * </ol>
     * 任一不成立 ⇒ <b>加载期当场抛异常</b>（本项目反复记录过的最坏失败形态是"静默不对"）。
     *
     * @return 给调用方写日志用的一整行（<b>本类自己不写日志</b> —— 见类注释：不引用 {@code ShanhaiMod}）
     */
    public static String selfTest() {
        if (shardCount() != 8) {
            throw new IllegalStateException("[SHANHAI-THREAD] 世线残片表条数不对：" + shardCount() + "，应为 8");
        }
        for (int n = 1; n <= 7; n++) {
            final int got = threadsForShardId("shanhai:thread_shard_" + n);
            if (got != (1 << n)) {
                throw new IllegalStateException("[SHANHAI-THREAD] thread_shard_" + n + " 应为 "
                        + (1 << n) + "，实为 " + got);
            }
        }
        final int overdriver = threadsForShardId("shanhai:universal_parallel_overdriver");
        if (overdriver != 1024) {
            throw new IllegalStateException("[SHANHAI-THREAD] universal_parallel_overdriver 应为 1024，实为 " + overdriver);
        }
        if (overdriver == threadsForShardId("shanhai:thread_shard_8")) {
            // thread_shard_8 不存在 ⇒ 两者都该是 0/1024 之外；这一条只是防止将来有人把超限器写成"8 号"。
            throw new IllegalStateException("[SHANHAI-THREAD] 超限器与 8 号残片的判定撞了");
        }
        // ── 正面对照：已知为真的样本 ──
        assertFinal(0, 0, 1, "空槽（无残片）");
        assertFinal(0, 64, 1, "非残片且放满一叠（负面对照：不该加）");
        assertFinal(1, 0, 1, "1 号残片但数量 0");
        assertFinal(2, 1, 3, "1 号残片 ×1 ⇒ 1 + 2×1 = 3");
        assertFinal(8, 1, 9, "3 号残片 ×1 ⇒ 1 + 8×1 = 9");
        assertFinal(1024, 1, 1025, "超限器 ×1 ⇒ 1 + 1024×1 = 1025");
        assertFinal(1024, 64, 65537, "超限器 ×64 ⇒ 1 + 1024×64");
        assertFinal(128, 16, 2049, "7 号残片 ×16 ⇒ 1 + 128×16");
        // ── 两张并行表：键集合必须逐项相等（否则某一档机器会静默退回 64） ──
        if (STANDARD_PARALLEL.size() != ENHANCED_PARALLEL.size()) {
            throw new IllegalStateException("[SHANHAI-THREAD] 两张并行表条数不等："
                    + STANDARD_PARALLEL.size() + " / " + ENHANCED_PARALLEL.size());
        }
        if (!STANDARD_PARALLEL.keySet().equals(ENHANCED_PARALLEL.keySet())) {
            throw new IllegalStateException("[SHANHAI-THREAD] 两张并行表的键集合不一致");
        }
        return "[SHANHAI-THREAD] 世线残片表自检通过（正面对照 8 条）：残片 " + shardCount() + " 种，"
                + "1..7 号 = 2/4/8/16/32/64/128，超限器 = 1024（2^" + OVERDRIVER_EXPONENT + "）；"
                + "公式 = 1 + 单枚值 × 该格数量（空槽/非残片 ⇒ 1）；"
                + "物质模块并行表 " + STANDARD_PARALLEL.size() + " 条 ×2 档，键集合逐项一致。";
    }

    private static void assertFinal(int perShard, int count, int expected, String what) {
        final int got = finalThreads(perShard, count);
        if (got != expected) {
            throw new IllegalStateException("[SHANHAI-THREAD] 自检失败（" + what + "）：应为 "
                    + expected + "，实为 " + got
                    + "（单枚值=" + perShard + "，数量=" + count + "）");
        }
    }
}
