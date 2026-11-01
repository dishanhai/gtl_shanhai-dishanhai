package com.shanhai.machine;

import com.gregtechceu.gtceu.api.recipe.GTRecipeType;

import com.shanhai.common.thread.ShanhaiConcurrencyTables;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.contents.TranslatableContents;

/**
 * 山海重构 · <b>机器物品 tooltip 的统一追加器</b>（唯一一处，24 台模块 + 主机全部共用）。
 *
 * <h2>上游（gtladditions）是怎么做的 —— 这是抄来的机制，不是自造</h2>
 * 用户给的样例（图3「枢纽卫星工厂 MK-1」）里那一串属性行，<b>不是逐行硬编码的字符串</b>，
 * 而是 {@code GTLAddMultiBlockMachineBuilder} 上一组 {@code tooltipText*} 方法在注册链里拼出来的：
 * <pre>
 * [源码/字节码原文] 类 = com.gtladd.gtladditions.api.registry.GTLAddMultiBlockMachineBuilder
 *   :76  tooltipTextKey(Component... key)              → super.tooltips(key)
 *   :84  tooltipTextMaxParallels(Object parallel)      → super.tooltips(Component.translatable("gtceu.multiblock.max_parallel", parallel))
 *   :96  tooltipTextRecipeTypes(GTRecipeType... types) → super.tooltips(Component.translatable(
 *                                                            "gtceu.machine.available_recipe_map_" + types.length + ".tooltip", 各类型的 toLanguageKey()))
 *   :134 tooltipTextLaser()                            → super.tooltips(Component.translatable("gtceu.multiblock.laser.tooltip"))
 *   :150 tooltipTextMultiRecipes()                     → super.tooltips(Component.translatable("gtceu.machine.multiple_recipes.tooltip"))
 * </pre>
 * 而「由GTLAdditions添加」那一行来自 {@code .tooltipBuilder(GTLAddMachines.INSTANCE.getGTLAdd_ADD())}
 * （{@code GTLAddMachines.java:278} → {@code GTLAdd_ADD$lambda$0} 往 tooltip 里加
 * {@code gui.gtladditions.add}），最后那行 id 是 GTCEu 自己对机器物品加的。
 * <b>⇒ 机制 = 注册期把 {@code Component} 塞进 {@code MultiblockMachineBuilder.tooltips(...)}。</b>
 * 本类就是这个机制的等价物（同一个 {@code super.tooltips(...)} 入口，只是我们不用 gtladditions 的非 API 类）。
 *
 * <h2>🔴 为什么没有照抄 {@code gtceu.machine.available_recipe_map_N.tooltip}</h2>
 * 那个键<b>只到 N = 11</b>（gtceu 自带 1..4，gtlcore 补齐 5..11；实测见
 * {@code _evidence/recipe-types/_lang/gtlcore!!assets__gtceu__lang__zh_cn.json}）。
 * 而我们最长的一台（原初临界加工模块）挂了 <b>36</b> 条类型 ⇒ 用那个键会渲染成裸键。
 * 所以这里改用一条<b>与条数无关</b>的自己的键 {@link #KEY_RECIPE_TYPES}，分隔符也走 lang
 * （中英标点不同），其余措辞与图3 逐字对齐。
 *
 * <h2>🔴 配方类型名怎么取</h2>
 * {@code GTRecipeType.registryName.toLanguageKey()} —— 与上游同一个调用（
 * {@code GTLAddMultiBlockMachineBuilder.java:108} 原文 {@code registryName.m_214298_()}）。
 * Dev 环境下的名字就是 {@code toLanguageKey()}（证据：本工程
 * {@code build/createMcpToSrg/output.tsrg:87961} 逐字 {@code toLanguageKey ()Ljava/lang/String; m_214298_}）。
 *
 * <p>⚠️ 该键的覆盖情况<b>已离线核过</b>（全 24 台 + 主机用到的 106 条类型，脚本见交付报告）：
 * 中文 106/106 命中；英文 90/106 命中，缺的 16 条全部是<b>本工程自注册</b>的类型
 * （{@code gtceu.primordial_*} / {@code gtceu.taixu_smelting} / {@code gtceu.wl_board_*}），
 * 原因是「en_us.json 不补」是用户 2026-09-22 的既有裁决（见 {@code ShanhaiRecipeTypes} 类注释）。
 * <b>该裁决本轮未改动</b>，所以英文环境下这 16 条会显示裸键 —— 这是既有偏离，不是本次引入的。
 */
public final class MachineTooltips {

    /** 每台机器一句中文简介的键前缀：{@code shanhai.tooltip.desc.<注册路径>}。 */
    public static final String DESC_KEY_PREFIX = "shanhai.tooltip.desc.";

    /** 属性行模板（措辞照图3 的「最大并行数: 2147483647」）。 */
    public static final String KEY_MAX_PARALLEL = "shanhai.tooltip.max_parallel";
    /** 属性行模板 —— 用户 2026-09-25 亲定措辞「跨配方线程数: 1」。 */
    public static final String KEY_CROSS_RECIPE_THREADS = "shanhai.tooltip.cross_recipe_threads";
    /** 属性行模板 —— 照图3 的「可用配方类型: 车床, 卷板机, …」，但<b>不限条数</b>。 */
    public static final String KEY_RECIPE_TYPES = "shanhai.tooltip.recipe_types";
    /** 列表分隔符（中英标点不同，所以也走 lang）。 */
    public static final String KEY_LIST_SEPARATOR = "shanhai.tooltip.list_separator";
    /**
     * <b>「工作台合成（AE 合成样板）」</b> —— 原初造物编译矩阵的<b>显示名</b>。
     *
     * <h2>🔴 为什么它只是一个 lang 键，而不是一个 {@code GTRecipeType}</h2>
     * 那台机器内部<b>真的</b>挂着 {@code GTRecipeTypes.ASSEMBLER_RECIPES}
     * （{@code ModuleRegistry.RECIPE_CREATION_COMPILATION_MATRIX}），<b>这不是笔误、也不许删</b>：
     * 它是防 {@code getRecipeTypes()} 返回空数组时下游越界用的（P1 结论）。
     * 但它真正执行的活儿是 <b>AE 合成样板（工作台合成）</b> ⇒ 显示「组装机」是<b>误导</b>
     * （用户 2026-10-31 实机原话：「把这个组装机改了，改成工作台，不然有歧义」）。
     * ⇒ 内部类型一个字节不动，<b>只把显示那一行换成这条键</b>。
     */
    public static final String KEY_AE_CRAFTING = "shanhai.tooltip.recipe_types.ae_crafting";
    /**
     * <b>模块用</b>：静态物品 tooltip 画不出"槽里现在放的是哪个物质模块"，
     * 所以只画<b>基础值</b>并如实标注（用户 2026-09-25 选的「候选 A」第 1 行）。
     */
    public static final String KEY_MAX_PARALLEL_BASE = "shanhai.tooltip.max_parallel.base";
    /** <b>候选 A 第 2 行</b>：单起一行说明「谁能让它涨」（照图2 的形态）。 */
    public static final String KEY_MAX_PARALLEL_RAISE = "shanhai.tooltip.max_parallel.raise";
    /**
     * <b>【2026-09-28 起不再使用】候选 C 第 2 行的 lang 键。</b>
     *
     * <pre>
     *   zh_cn : 「线程倍率槽可提高跨配方线程数 §8（本阶段尚未生效）」
     *   en_us : "The thread multiplier slot can raise cross-recipe threads §8(not active yet)"
     * </pre>
     * 🔴 <b>为什么停用</b>：世线残片本轮真的接上了（{@code fromModule} 第 ⑤ 行改由
     * {@link #crossRecipeThreadsRaiseLine()} 现算）。那条 lang 文案里
     * 「（本阶段尚未生效）」现在<b>是假话</b>，本项目禁止"活的假数据"。
     * <p>⚠️ <b>遗留</b>：{@code src/main/resources/.../lang/*.json} 里的那两条文案<b>本轮没有改</b>
     * （不在本次授权的写入范围内）⇒ 它们目前是<b>无人引用的死键</b>。
     * 详见交付报告"未做到/待办"一节。
     */
    public static final String KEY_CROSS_RECIPE_THREADS_RAISE = "shanhai.tooltip.cross_recipe_threads.raise";

    private MachineTooltips() {}

    /**
     * <b>模块的物品 tooltip（24 台统一）</b>——用户 2026-09-25 亲自选定的「<b>候选 A + 候选 C</b>」：
     * <pre>
     *   &lt;描述行&gt;
     *   最大并行数: 64（基础值）                              ← 候选 A 第 1 行
     *   在物质模块槽放入物质模块可提高最大并行数，每 16 个翻一倍   ← 候选 A 第 2 行（"谁能提高它"）
     *   跨配方线程数: 1                                      ← 候选 C 第 1 行
     *   线程倍率槽可提高跨配方线程数（本阶段尚未生效）           ← 候选 C 第 2 行
     *   可用配方类型: 车床，卷板机，…
     * </pre>
     * 参考原型 = 用户给的图2：「允许使用 天球分歧引擎，提高线程上限」+「线程倍率为2000」
     * ⇒ <b>「谁能提高它」单起一行，当前值一行</b>，而不是干巴巴一个数字。
     *
     * <h2>🔴 两个数字都是真的（2026-09-25 路线 ① 之后）</h2>
     * <ul>
     *   <li><b>基础值 64</b>：{@code PrimordialModuleMachine.DEFAULT_PARALLEL}，
     *       24 台现在<b>真的</b>用这个值跑并行（并行槽已从核心上移到基类）。</li>
     *   <li><b>跨配方线程数 1</b>：用户原话「现在模块是没有跨配方线程，后续我会加，
     *       你现在可以写跨配方线程数为 1」⇒ 是<b>用户指定的显示值</b>；
     *       第 2 行明写「尚未生效」，<b>没有编造一个不存在的数字</b>。</li>
     * </ul>
     * <p>⚠️ 本方法【只给模块用】。主机的并行是固定值（{@code Integer.MAX_VALUE}），
     * 也没有"放物质模块能提高并行"这回事 ⇒ 主机走 {@link #forMachine}，
     * 否则那两行"谁能提高它"在主机上就是假话。
     *
     * @param path               注册路径（= {@code block.shanhai.<path>} 的 path 部分）
     * @param baseParallel       基础并行（真实值 = {@code PrimordialModuleMachine.DEFAULT_PARALLEL}）
     * @param crossRecipeThreads 跨配方线程数当前值（真实值 = {@code CROSS_RECIPE_THREADS}）
     * @param recipeTypes        该模块挂的配方类型（取中文名走 {@code gtceu.<path>}）
     */
    public static Component[] forModule(String path,
                                        String baseParallel,
                                        String crossRecipeThreads,
                                        GTRecipeType[] recipeTypes) {
        return forModule(path, baseParallel, crossRecipeThreads, recipeTypes, null);
    }

    /**
     * 同 {@link #forModule(String, String, String, GTRecipeType[])}，但允许<b>只改显示</b>：
     *
     * @param recipeTypesLineOverride 非 {@code null} ⇒ <b>按翻译键</b> {@link #KEY_RECIPE_TYPES}
     *                                找到那一行并<b>整行替换</b>成它；{@code null} ⇒ 与旧行为逐字相同。
     *                                <br>🔴 注意这里<b>不动</b> {@code recipeTypes} 入参：那是机器真正挂的类型数组，
     *                                动它会让 {@code getRecipeTypes()} 变形（P1 结论禁止）。
     */
    public static Component[] forModule(String path,
                                        String baseParallel,
                                        String crossRecipeThreads,
                                        GTRecipeType[] recipeTypes,
                                        Component recipeTypesLineOverride) {
        final Component[] lines = new Component[] {
                // ① 它是什么（一句话）—— 任务 B 的「描述行」
                Component.translatable(DESC_KEY_PREFIX + path),
                // ② 最大并行数（基础值）—— 候选 A 第 1 行
                Component.translatable(KEY_MAX_PARALLEL_BASE, baseParallel),
                // ③ 谁能提高它 —— 候选 A 第 2 行
                Component.translatable(KEY_MAX_PARALLEL_RAISE),
                // ④ 跨配方线程数（当前值）—— 候选 C 第 1 行
                Component.translatable(KEY_CROSS_RECIPE_THREADS, crossRecipeThreads),
                // ⑤ 谁能提高它 —— 候选 C 第 2 行（🔴 2026-09-28：改由 Java 现算，见方法注释）
                crossRecipeThreadsRaiseLine(),
                // ⑥ 可用配方类型（中文名清单）
                Component.translatable(KEY_RECIPE_TYPES, recipeTypesList(recipeTypes)),
        };
        final Component[] replaced = replaceLineByKey(lines, KEY_RECIPE_TYPES, recipeTypesLineOverride);
        // 保险丝：万一哪天 GTCEu/gtlcore 的 available_recipe_map_N 那组也被塞进本数组（见下方方法注释）。
        return replaceLineByKeyPrefix(replaced, "gtceu.machine.available_recipe_map_", recipeTypesLineOverride);
    }

    /**
     * 同 {@link #replaceLineByKey}，但按<b>翻译键前缀</b>匹配 —— 用来兜住 GTCEu/gtlcore 那一组
     * {@code gtceu.machine.available_recipe_map_1..11.tooltip}（它们的中文也是「可用配方类型：%s」，
     * 只差冒号是全角）。今天这台机器<b>没有</b>那条（我们的 tooltip 数组只有 6 行，实测行序见
     * {@link #forModule}），所以这里是<b>零命中的保险丝</b>：真出现时也按同一个显示名替换掉，
     * 不会再冒出一行「组装机」。
     */
    public static Component[] replaceLineByKeyPrefix(Component[] lines, String keyPrefix, Component replacement) {
        if (lines == null || keyPrefix == null || replacement == null) {
            return lines;
        }
        for (int i = 0; i < lines.length; i++) {
            final Component line = lines[i];
            if (line != null && line.getContents() instanceof TranslatableContents contents
                    && contents.getKey() != null && contents.getKey().startsWith(keyPrefix)) {
                lines[i] = replacement;
            }
        }
        return lines;
    }

    /**
     * <b>按翻译键替换数组里的一行</b>（找不到就原样返回，绝不按下标删/改）。
     *
     * <p>同族先例：{@code PrimordialModuleMachine#shanhai$replaceUpstreamMachineModeLine} 与
     * {@code PrimordialCreationCompilationMatrix#addDisplayText}（都是"按 key 找行"，不是按行号）。
     * 这里之所以<b>替换</b>而不是删除：{@link #KEY_RECIPE_TYPES} 的模板「可用配方类型: %s」是
     * 用户 2026-09-25 定的措辞，只有<b>值</b>需要变。
     *
     * @param lines       原数组（就地改）
     * @param key         要替换的那一行的<b>最外层</b>翻译键
     * @param replacement 新行；{@code null} ⇒ 什么都不做
     * @return 同一个数组对象（便于直接 return）
     */
    public static Component[] replaceLineByKey(Component[] lines, String key, Component replacement) {
        if (lines == null || key == null || replacement == null) {
            return lines;
        }
        for (int i = 0; i < lines.length; i++) {
            final Component line = lines[i];
            if (line != null && line.getContents() instanceof TranslatableContents contents
                    && key.equals(contents.getKey())) {
                lines[i] = replacement;
            }
        }
        return lines;
    }

    /**
     * <b>「可用配方类型: 工作台合成（AE 合成样板）」</b> —— 原初造物编译矩阵专用那一行。
     *
     * <p>模板仍用 {@link #KEY_RECIPE_TYPES}（措辞不变），只把值换成 {@link #KEY_AE_CRAFTING}。
     */
    public static Component aeCraftingRecipeTypesLine() {
        return Component.translatable(KEY_RECIPE_TYPES, Component.translatable(KEY_AE_CRAFTING));
    }

    /**
     * <b>候选 C 第 2 行：谁能让跨配方线程数涨</b>（2026-09-28 起由 Java 现算，不再走 lang 键）。
     *
     * <h2>为什么不在 lang 里写死</h2>
     * 这条文案要列出 8 种世线残片各自的单枚值 —— 那就是<b>第二份真源</b>，
     * 而且它是"会过期的那一种"（用户改数值时没人会想起来改 lang）。
     * ⇒ 数字统一取 {@link ShanhaiConcurrencyTables#shardSummary()}，
     * 与机器真正算线程用的是<b>同一张表</b>，不可能对不上。
     *
     * <p>⚠️ <b>已知代价</b>：本行<b>不再随语言切换</b>（永远是中文）。
     * 这是本轮写入范围限制下的取舍（lang 文件不在授权范围内）。
     * 本工程已有同类既有偏离（en_us 缺 16 条配方类型键 ⇒ 英文环境显示裸键），量级相当。
     */
    private static Component crossRecipeThreadsRaiseLine() {
        return Component.literal("§7线程槽放入【世线残片】可提高跨配方线程数（单枚：§f"
                + ShanhaiConcurrencyTables.shardSummary() + "§7）");
    }

    /**
     * <b>通用形态</b>（目前只有主机用）：描述行 → 最大并行数 → 跨配方线程数 → 可用配方类型。
     *
     * <p>调用点：{@code ShanhaiMachines#init}。<b>新增机器不许再写第二份。</b>
     *
     * @param path               注册路径（= {@code block.shanhai.<path>} 的 path 部分）
     * @param maxParallel        最大并行数的<b>显示值</b>（真实值；不要传占位数字）
     * @param crossRecipeThreads 跨配方线程数的<b>显示值</b>
     * @param recipeTypes        该机器挂的配方类型（取中文名走 {@code gtceu.<path>}）
     */
    public static Component[] forMachine(String path,
                                         Component maxParallel,
                                         Component crossRecipeThreads,
                                         GTRecipeType[] recipeTypes) {
        return new Component[] {
                Component.translatable(DESC_KEY_PREFIX + path),
                Component.translatable(KEY_MAX_PARALLEL, maxParallel),
                Component.translatable(KEY_CROSS_RECIPE_THREADS, crossRecipeThreads),
                Component.translatable(KEY_RECIPE_TYPES, recipeTypesList(recipeTypes)),
        };
    }

    /**
     * 把配方类型数组拼成「车床，卷板机，…」这样的一串（<b>条数无关</b>）。
     *
     * <p>每一项用 {@code gtceu.<path>} 查名字，与 GTCEu 自己的 JEI 分类标题同一个键。
     * <p>⚠️ 防御：{@code null} 项直接跳过而不是抛 NPE —— 这条链跑在机器注册期，
     * 一个 null 会让整个游戏在加载期炸掉；而"某台机器少列一条类型"只是显示层的轻微降级。
     * <b>宁缺毋滥：显示可以退化，加载不可以。</b>（真正该炸的 null 由
     * {@code ModuleRegistry#register} 的 fail-fast 与 {@code ShanhaiRecipeTypes} 的自检负责。）
     */
    public static MutableComponent recipeTypesList(GTRecipeType[] recipeTypes) {
        MutableComponent joined = Component.empty();
        boolean first = true;
        if (recipeTypes != null) {
            for (GTRecipeType type : recipeTypes) {
                if (type == null || type.registryName == null) {
                    continue;
                }
                if (!first) {
                    joined.append(Component.translatable(KEY_LIST_SEPARATOR));
                }
                joined.append(Component.translatable(recipeTypeNameKey(type)));
                first = false;
            }
        }
        return joined;
    }

    /** 配方类型的名字键：{@code gtceu:<path>} → {@code gtceu.<path>}（= 上游同款调用）。 */
    public static String recipeTypeNameKey(GTRecipeType type) {
        return type.registryName.toLanguageKey();
    }
}
