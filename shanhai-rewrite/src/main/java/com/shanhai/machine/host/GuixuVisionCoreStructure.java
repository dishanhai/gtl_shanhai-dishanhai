package com.shanhai.machine.host;

import com.gregtechceu.gtceu.api.machine.MultiblockMachineDefinition;
import com.gregtechceu.gtceu.api.machine.multiblock.PartAbility;
import com.gregtechceu.gtceu.api.pattern.BlockPattern;
import com.gregtechceu.gtceu.api.pattern.FactoryBlockPattern;
import com.gregtechceu.gtceu.api.pattern.Predicates;
import com.gregtechceu.gtceu.api.pattern.TraceabilityPredicate;
import com.shanhai.common.compat.GtlAddCompat;
import com.shanhai.machine.module.ModuleRegistry;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraftforge.registries.ForgeRegistries;

/**
 * 归墟视界枢核的<b>结构图案</b>：把 v5.1 的体素结构（{@code temp/host-shape/gen5.mjs} 生成）
 * 变成 GTCEu 的 {@link BlockPattern}。
 *
 * <h2>资源格式与位置（为什么是 {@code assets/gtladditions/…}）</h2>
 * 结构数据是 {@code .bin}（{@code GTLASB2} 二进制，<b>不是</b> {@code .nbt}），由
 * {@link GtlAddCompat#guixuVisionCorePattern()} 内部走 gtladditions 的
 * {@code StructureResourceLoader#loadFactoryPattern} 读出来。
 * <p>该 loader 的根路径是<b>写死的</b>（反编译实证：{@code ROOT_PATH = "assets/gtladditions/structures"}，
 * 再用 {@code StructureResourceLoader.class.getClassLoader().getResourceAsStream(...)} 取），
 * 它按<b>类路径</b>找资源、不区分是哪个 jar ⇒ 我们把自己的 {@code .bin} 放进
 * <b>本 mod jar 的同名路径</b>即可读到，<b>不需要</b>也不能改 gtladditions 的 jar。
 * 文件名带 {@code guixu_} 前缀，与 gtladditions 自带的 30 多个 {@code .bin} 不重名。
 *
 * <h2>符号表（必须与生成器 {@code gen5.mjs} 的 {@code BIN_SYMBOLS} 逐字对应）</h2>
 * <pre>
 *   A gtlcore:molecular_casing       B gtlcore:space_elevator_mechanical_casing
 *   C kubejs:hollow_casing           D kubejs:high_strength_concrete
 *   E gtceu:high_power_casing        F kubejs:containment_field_generator
 *   G gtlcore:power_module_4         H gtlcore:hyper_mechanical_casing
 *   I kubejs:restraint_device        J gtceu:fusion_glass
 *   N 已填平的模块槽（环带方块（第一候选）∪ 空气 ∪ 全部模块控制器方块）   ← .bin 里 24 口最外那层（含中心那 24 格）
 *   P 已补实的模块腔体（环带方块（第一候选）∪ <b>任意方块</b>）  ← .bin 里 24 口往里 12 层
 *                                                                （改前 9,506 格，2026-10-25 起 1,578 格改成 T/S ⇒ 现 7,928 格）
 *   T 模块外壳·青铜（青铜机械外壳（第一候选）∪ <b>任意方块</b>）  ← .bin 里 24 口腔体里的 1,074 格（用户自建外壳，见下）
 *   S 模块外壳·蒸汽（工业蒸汽机械方块（第一候选）∪ <b>任意方块</b>）← .bin 里 24 口腔体里的 504 格（用户自建外壳，见下）
 *   Q 已堵上的腔口（环带方块（第一候选）∪ <b>任意方块</b>）  ← 2026-10-25 上一轮加过、同轮已从 .bin 还原成空格；
 *                                                          本符号与谓词<b>保留</b>只为兼容旧资源，现行 .bin 里没有 Q
 *   M 模块槽（空气 ∪ 环带 ∪ 模块控制器）—— <b>已退役</b>，只留作读旧 .bin 的兼容
 *   ~ 控制器                          空格 = 任意（不参与匹配）
 * </pre>
 * <p>🔴 {@code N} / {@code P} <b>都不在</b> {@code gen5.mjs} 的 {@code BIN_SYMBOLS} 里：它们由
 * {@code temp/host-shape/patch-bin.mjs}（24 个 {@code M}→{@code N}，原地改 17 字节）与
 * {@code temp/host-shape/patch-bin-p.mjs}（24 口 d=1..12 的空格→{@code P}，重新序列化，9,506 格）改出来。
 * 语义 = 「这 24 口默认是实心的」。
 *
 * <p>🔴 {@code T} / {@code S} <b>同样不在</b> {@code gen5.mjs} 的 {@code BIN_SYMBOLS} 里，它们由
 * {@code temp/cavity-bin2/apply.mjs} 从 {@code P} 里挖出来（2026-10-25）：
 * 用户自己在那台主机的 <b>16 号腔口</b>里搭了一套模块外壳（<b>47 格青铜机械外壳 ＋ 21 格工业蒸汽机械方块</b>，
 * 世界坐标 x 1809..1815 / y 87..93 / z 261..272），要求"<b>把这套外壳写入多方块结构</b>"
 * ⇒ 把这 68 格换算成<b>槽本地坐标</b>，按朝向绕 Y 轴旋转后套到<b>全部 24 个模块位</b>，
 * 1,632 次认领去重后是 <b>1,578 格</b>（50 格被相邻槽重复认领，材质全是青铜、无冲突），
 * 分别写成 {@code T}（1,074 格）与 {@code S}（504 格）。Z 轴深度、形状与逐格读数见 {@code temp/cavity-bin2/报告.md}。
 *
 * <p>⚠️ {@code Q}（824 格腔口）在 2026-10-25 上一轮加进过 .bin，<b>同轮已还原</b>；
 * 本类的 {@code Q} 符号/谓词代码保留（读旧资源时不会退化成"空格=任意"），现行 .bin 里一格 Q 都没有。
 * <p>🔴 这一版方块表来自<b>协作方「山海」的替换</b>（他的注释：「結構符號保持原位，材料採用深藍機殼、
 * 黑色骨架及能量節點配色」），用户在此基础上又改了两处：
 * <ul>
 *   <li>{@code G}：{@code gtlcore:power_module_5}（太空电梯动力模块MK5）⇒ <b>{@code power_module_4}（MK4）</b></li>
 *   <li>{@code J}：{@code kubejs:molecular_coil}（分子线圈）⇒ <b>{@code gtceu:fusion_glass}（聚变玻璃）</b></li>
 * </ul>
 * <p>⚠️ 模块占位（黄/橙/绿混凝土）在 {@code .bin} 里已经被当成<b>空格</b>——模块本体是玩家自己摆的
 * 独立多方块（{@code forge_of_the_antichrist_module.bin}，13×7×7，其中只有 89 格要求特定方块），
 * 所以不管有没有装模块，这两种状态都必须收（见 {@link #filledModuleSlotPredicate()} /
 * {@link #filledCavityPredicate()}）。
 *
 * <h2>为什么不用 {@code MultiBlockStructure}</h2>
 * 全工程禁用：那是 {@code kotlin.Lazy} 共享可变单例，{@code where()} 原地改符号表，
 * 会污染 gtladditions 自己的 6 台机器（详见 {@link GtlAddCompat} 类注释）。
 */
public final class GuixuVisionCoreStructure {

    private GuixuVisionCoreStructure() {}

    /** 结构资源路径（相对 {@code StructureResourceLoader} 的根，即 {@code assets/gtladditions/structures/}）。 */
    public static final String RESOURCE_PATH = "multiblock/guixu_vision_core.bin";
    /** 结构资源里写死的 id（loader 会校验它，不一致直接抛）。 */
    public static final String RESOURCE_ID = "guixu_vision_core";

    /**
     * 外环桁架带的方块 id（= 符号 {@link #RING_BAND} 的本体）。
     *
     * <p>🔴 它是<b>唯一的仓室贴附点</b>：{@link #ringBandPredicate()} 在这里额外放行能源仓/动力仓，
     * 所以 {@link #SYMBOL_BLOCKS} 里的 A 行与本常量<b>必须一致</b>，否则"图案收、自检不收"。
     */
    public static final String RING_BAND_BLOCK = "gtlcore:molecular_casing";

    /** 外观方块（JEI 预览 / 破坏时用的外壳）：外环桁架带。 */
    public static final String APPEARANCE_BLOCK = RING_BAND_BLOCK;
    /**
     * 模块外壳的青铜方块 id（符号 {@link #MODULE_SHELL_BRONZE}）—— <b>2026-10-25 用户自己搭的那套外壳里的 47 格</b>。
     *
     * <p>世界扫描实证（{@code temp/cavity-fill/扫描-全量.tsv}，主机 {@code (1754,101,212)} / facing=east）：
     * {@code gtceu:bronze_machine_casing} 47 格，包围盒 x 1809..1815 / y 87..93 / z 261..272。
     */
    public static final String MODULE_SHELL_BRONZE_BLOCK = "gtceu:bronze_machine_casing";
    /**
     * 模块外壳的蒸汽方块 id（符号 {@link #MODULE_SHELL_STEAM}）—— <b>同一套外壳里的 21 格</b>。
     *
     * <p>世界扫描实证：{@code gtceu:industrial_steam_casing} 21 格，包围盒 x 1810..1814 / y 88..92 / z 272。
     */
    public static final String MODULE_SHELL_STEAM_BLOCK = "gtceu:industrial_steam_casing";
    /**
     * 符号 → 方块 id（A..J 与 {@code gen5.mjs} 的 {@code BIN_SYMBOLS} 一一对应；
     * {@code N} 是后加的"已填平的模块槽"，见类注释）。
     */
    public static final String[][] SYMBOL_BLOCKS = {
            {"A", RING_BAND_BLOCK},
            {"B", "gtlcore:space_elevator_mechanical_casing"},
            {"C", "kubejs:hollow_casing"},
            {"D", "kubejs:high_strength_concrete"},
            {"E", "gtceu:high_power_casing"},
            {"F", "kubejs:containment_field_generator"},
            // 用户在「山海换方块」的基础上改的一处：MK5 ⇒ MK4
            {"G", "gtlcore:power_module_4"},
            {"H", "gtlcore:hyper_mechanical_casing"},
            {"I", "kubejs:restraint_device"},
            // 用户在「山海换方块」的基础上改的第二处：分子线圈 ⇒ 聚变玻璃
            {"J", "gtceu:fusion_glass"},
            // 已填平的模块槽（.bin 里 24 格 'N'）：默认方块 = 环带方块。
            // 它的谓词不是"只认环带"，而是「环带(第一候选) ∪ 空气 ∪ 模块」——见 filledModuleSlotPredicate()。
            {"N", RING_BAND_BLOCK},
            // 已补实的模块腔体（.bin 里 9,506 格 'P'）：默认方块 = 环带方块（预览/一键搭建铺它）。
            // 谓词 = 「环带(第一候选) ∪ 任意方块」——见 filledCavityPredicate()。
            {"P", RING_BAND_BLOCK},
            // 已堵上的腔口（.bin 里 824 格 'Q'）：默认方块 = 环带方块（预览/一键搭建铺它）。
            // 谓词 = 「环带(第一候选) ∪ 任意方块」——见 filledCavityMouthPredicate()。
            // ⚠️ 2026-10-25 上一轮加过、同轮已从 .bin 还原：现行 .bin 里没有 Q，这条只为兼容旧资源。
            {"Q", RING_BAND_BLOCK},
            // 模块外壳·青铜（.bin 里 1,074 格 'T' = 24 个模块位 × 用户自建的青铜外壳格）：
            // 默认方块 = 青铜机械外壳（预览/一键搭建照他的外壳铺青铜）。
            // 谓词 = 「青铜机械外壳(第一候选) ∪ 任意方块」——见 moduleShellBronzePredicate()。
            {"T", MODULE_SHELL_BRONZE_BLOCK},
            // 模块外壳·蒸汽（.bin 里 504 格 'S' = 24 个模块位 × 用户自建的蒸汽外壳格）：
            // 默认方块 = 工业蒸汽机械方块。
            // 谓词 = 「工业蒸汽机械方块(第一候选) ∪ 任意方块」——见 moduleShellSteamPredicate()。
            {"S", MODULE_SHELL_STEAM_BLOCK},
    };

    /**
     * 外环桁架带 / 站体机壳的符号 —— <b>本机唯一的"仓室贴附点"</b>。
     *
     * <p>第二轮的<b>能量双仓</b>就挂在这个符号上（见 {@link #ringBandPredicate()}）：
     * 结构同时接受<b>能源仓</b>（{@code PartAbility.INPUT_ENERGY}）与
     * <b>动力仓</b>（{@code PartAbility.OUTPUT_ENERGY}）。
     */
    public static final char RING_BAND = 'A';

    /**
     * 模块槽符号（24 个）。<b>已退役</b>：现在的 {@code .bin} 用的是 {@link #MODULE_SLOT_FILLED}（{@code N}）。
     *
     * <p>保留它只为读<b>旧</b> {@code .bin}（没有重新生成资源包的老环境 / 老存档里带的那份）时
     * 不至于"符号表里没有 M ⇒ 图案里那 24 格退化成空格任意方块"。新代码路径不会走到它。
     */
    public static final char MODULE_SLOT = 'M';
    /**
     * 已填平的模块槽符号（24 个）—— 现在的 {@code .bin} 用的就是它。
     *
     * <p>语义：这一格<b>默认就是实心的</b>（环带方块），所以图案/预览/终端一键搭建默认把它铺平；
     * 玩家要装模块时，把那一格换成对应模块的控制器方块即可（谓词同样收）。
     * <p>为什么还要收空气：{@link #moduleSlotPredicate()} 时代搭出来的老主机，那 24 格是<b>空气</b>，
     * 不收空气它们会立刻不成型（用户 2026-10-24 明确要求"老主机照样能成型"）。
     */
    public static final char MODULE_SLOT_FILLED = 'N';
    /**
     * 已补实的模块腔体符号（9,506 格）—— 24 口腔<b>往里 12 层</b>（d=1..12）的空格全部改成了它。
     *
     * <p>语义：这些格子<b>默认就是实心的环带方块</b>，所以 JEI 预览 / 终端一键搭建会照着图案
     * 把这 12 层铺满；但玩家装了模块之后，这些格子里放的是<b>模块自己的机壳</b>
     * （{@code forge_of_the_antichrist_module.bin} 里的 B/E/D/F/G 等），不是环带方块，
     * 所以谓词必须**收任意方块**（见 {@link #filledCavityPredicate()}）。
     * <p>为什么还要收空气：这版图案之前搭出来的老主机，那 12 层是**空气**，不收空气它们会立刻不成型
     * （用户 2026-10-24 明确要求"老主机照样能成型"）——"任意方块"本身就包含空气，所以一条 any 全覆盖。
     *
     * <p>⚠️ 24 口的**最外那层**（d=0）没写 {@code P}：那层在 {@code .bin} 里是 {@code N} + 空格。
     * 其中 24 格（每口中心 1 格）是 {@code N}，2026-10-25 起它的第一候选改成环带方块
     * ⇒ 一键搭建 / 预览会把这 24 格也铺平。
     *
     * <p>⚠️ <b>2026-10-25 起本符号的格数已经变了</b>：原来这 24 口 12 层里是 <b>9,506 格 {@code P}</b>，
     * 其中 <b>1,578 格</b>（24 个模块位 × 用户自建外壳，去重后）已被改成
     * {@link #MODULE_SHELL_BRONZE}（{@code T}，1,074 格）与 {@link #MODULE_SHELL_STEAM}（{@code S}，504 格）
     * ⇒ 现行 {@code .bin} 里 {@code P} 只剩 <b>7,928 格</b>。谓词本身（{@link #filledCavityPredicate()}）未动。
     */
    public static final char MODULE_CAVITY_FILLED = 'P';
    /**
     * 已堵上的腔口符号（824 格）—— 24 口腔<b>最外那层（d=0）</b>里除了槽心 {@code N} 之外的全部空气格。
     *
     * <p>来源：{@code temp/cavity-bin/apply.mjs} 把 {@code .bin} 里这 824 格空格改成本符号
     * （调色板 385 → 436 条，只改 268 个网格索引，既有条目字节一格未动）。
     * 逐口格数：外环东/南 24、外环西/北 32、上下立柱 47 ⇒ {@code 8×24 + 8×32 + 8×47 = 824}
     * （读数为 0/0 双向相等，见 {@code temp/cavity-bin/报告.md}）。
     *
     * <p>语义 = 「这些洞口默认是实心的」，谓词见 {@link #filledCavityMouthPredicate()}。
     */
    public static final char CAVITY_MOUTH_FILLED = 'Q';
    /**
     * 模块外壳·青铜符号（{@code .bin} 里 <b>1,074 格</b>）—— 24 个模块位里，用户自己那套外壳中
     * {@code gtceu:bronze_machine_casing} 的对应格。
     *
     * <h2>用户原话（2026-10-25，最后确认的口径）</h2>
     * 「<b>你现在 /function shanhai_marker:a_module_shell 是对的，写入多方块结构</b>」——
     * 他说的"写入多方块结构"不是"拿环带方块把腔体填实"、也不是"打个橙色标记"，
     * 而是<b>把他自己在那台主机 16 号腔口里搭出来的那套外壳，按一模一样的形状写进结构图案</b>，
     * 让终端一键搭建 / JEI 预览在<b>每个模块位</b>都照这套外壳摆方块。
     *
     * <p>形状（槽本地坐标，{@code a}=横向 / {@code b}=竖直 / {@code c}=向腔里第几层）：
     * {@code c=1} 是一圈青铜边框（16 格）套一张 21 格的蒸汽"圆盘"前脸；{@code c=2..6} 是十字（每层 5 格）；
     * {@code c=7..12} 是正中的 1 根柱子（每层 1 格）。青铜合计 {@code 16 + 5×5 + 6 = 47} 格／槽位
     * （与扫描表的 47 格逐格对上），蒸汽 {@code 21} 格／槽位。
     *
     * <p>谓词见 {@link #moduleShellBronzePredicate()}。
     */
    public static final char MODULE_SHELL_BRONZE = 'T';
    /**
     * 模块外壳·蒸汽符号（{@code .bin} 里 <b>504 格</b>）—— 与 {@link #MODULE_SHELL_BRONZE} 同一套外壳里的
     * {@code gtceu:industrial_steam_casing} 格（21 格／槽位 × 24 = 504，互不重叠）。
     *
     * <p>谓词见 {@link #moduleShellSteamPredicate()}。
     */
    public static final char MODULE_SHELL_STEAM = 'S';
    /** 控制器符号（恰好 1 个）。 */
    public static final char CONTROLLER = '~';


    /**
     * 建图案。**首次结构检查 / JEI 预览时才会被调用**（那时注册表早已冻结，方块句柄取得到）。
     *
     * @throws IllegalStateException 任一方块 id 在注册表里查不到（宁可加载期炸，也不要"图案恒不成立且无日志"）
     */
    public static BlockPattern createPattern(MultiblockMachineDefinition definition) {
        final FactoryBlockPattern pattern = GtlAddCompat.guixuVisionCorePattern();
        for (String[] entry : SYMBOL_BLOCKS) {
            final char symbol = entry[0].charAt(0);
            // M / N / P / Q / T / S 有各自的专用谓词（见下面几行 where），这里跳过；
            // 同一符号 where 两次虽然只是后写覆盖，但会让"符号表里写的是 blocks(...)"变成假话。
            if (symbol == MODULE_SLOT || symbol == MODULE_SLOT_FILLED
                    || symbol == MODULE_CAVITY_FILLED || symbol == CAVITY_MOUTH_FILLED
                    || symbol == MODULE_SHELL_BRONZE || symbol == MODULE_SHELL_STEAM) continue;
            // 'A' 是唯一的仓室贴附点：本体是外环桁架带，但允许被能源仓/动力仓替换。
            pattern.where(symbol, symbol == RING_BAND
                    ? ringBandPredicate()
                    : Predicates.blocks(requireBlock(entry[1])));
        }
        // M：旧 .bin 的模块槽（空气 ∪ 环带 ∪ 模块）—— 退役但保留，老资源照样能读
        pattern.where(MODULE_SLOT, moduleSlotPredicate());
        // N：现在的模块槽 = 已填平（环带(第一候选) ∪ 模块控制器 ∪ 空气）
        pattern.where(MODULE_SLOT_FILLED, filledModuleSlotPredicate());
        // P：模块腔体往里 12 层 = **留空**（空气(第一候选) ∪ 任意方块）—— 2026-10-25 用户要求不再填实
        pattern.where(MODULE_CAVITY_FILLED, filledCavityPredicate());
        // Q：模块腔口最外那层除槽心外的 824 格（旧口径遗留，现行 .bin 里没有 Q）
        pattern.where(CAVITY_MOUTH_FILLED, filledCavityMouthPredicate());
        // T：模块外壳·青铜 = 用户自建的青铜机械外壳（第一候选）∪ 任意方块
        pattern.where(MODULE_SHELL_BRONZE, moduleShellBronzePredicate());
        // S：模块外壳·蒸汽 = 用户自建的工业蒸汽机械方块（第一候选）∪ 任意方块
        pattern.where(MODULE_SHELL_STEAM, moduleShellSteamPredicate());
        pattern.where(CONTROLLER, Predicates.controller(Predicates.blocks(definition.getBlock())));
        return pattern.build();
    }

    /**
     * 'A' 的谓词 = {@link #RING_BAND_BLOCK}（外环桁架带）∪ <b>能源仓</b> ∪ <b>动力仓</b>
     * ∪ <b>物品/流体输入输出仓</b> ∪ <b>样板总成白名单</b>。
     *
     * <p>为什么要两种电仓（用户交办原话「结构同时收能源仓与动力仓」）：
     * <ul>
     *   <li><b>能源仓</b>（{@code INPUT_ENERGY}）—— 机器吃电的那一侧（本机做配方用）；</li>
     *   <li><b>动力仓</b>（{@code OUTPUT_ENERGY}）—— 机器往外送电的那一侧
     *       （阶段 3 的「视界能流抽取」要用；本阶段先让结构收得下、装得上）。</li>
     * </ul>
     *
     * <h2>🆕 2026-10-25（T2）：放行样板总成 / 任意输入输出仓</h2>
     * 用户原话（逐字）：「现在归墟视界枢核<b>不能放样板总成</b>，只能放动力仓和能源仓，
     * 你让它可以放<b>样板总成</b>（或者任意输入输出）」。
     *
     * <p>两条路，缺一不可：
     * <ol>
     *   <li><b>按能力放行</b>（{@code PartAbility}）：物入 / 物出 / 流入 / 流出 四种 —— 与
     *       <b>基类主机（原始终焉引擎）的同一条放行清单逐条相同</b>
     *       （{@code PrimordialOmegaEngineStructure} 第 66-69 行）。⇒ 所有声明了这四种能力的
     *       标准仓室（1x/4x/9x 流体仓、各类物品总线、ME 仓…）自动收得下，不需要列 id。</li>
     *   <li><b>按方块 id 放行</b>（{@link #EXTRA_PART_BLOCK_IDS}）：样板总成<b>不声明</b>上面那四种能力
     *       （它们靠 ME 网络供料，{@code PartAbility} 里没有它们的名字）⇒ 只能按 id 放。
     *       清单见那个常量，逐条写了"为什么它在里面"。</li>
     * </ol>
     *
     * <p>⚠️ <b>为什么不放开到"任意 {@code IMultiPart}"</b>：{@code TraceabilityPredicate} 的判据是
     * 「(方块) 或 (声明了某个 {@code PartAbility})」，<b>没有</b>"这个方块是不是某个机器的 part"这种谓词；
     * 真要做只能自建 {@code BlockPredicate} 并在成形时对每个格子做机器实例查询 —— 那会在
     * <b>每次结构检查</b>（每次拆装/覆盖层变更都会跑）里加 1000+ 次方块实体查询，
     * 而且失败时的表现是"结构不成型、没有任何提示"。⇒ 本次按<b>能力 + 显式白名单</b>放行，
     * 白名单缺哪个就报一行日志并补一个 id（见 {@link #EXTRA_PART_BLOCK_IDS} 的解析日志），不静默。
     *
     * <p>⚠️ 白名单 id 解析是<b>宽松</b>的（缺了跳过 + 打一行 WARN），与 {@link #requireBlock} 的
     * "缺了就炸"不同：这里缺一个 id 的后果是"那种仓放不上去"（局部功能缺失），
     * 而这里炸掉的后果是<b>整台机器加载失败</b>；用日志把前者变成可见的即可。
     */
    public static TraceabilityPredicate ringBandPredicate() {
        TraceabilityPredicate predicate = Predicates.blocks(requireBlock(RING_BAND_BLOCK))
                .or(Predicates.abilities(PartAbility.INPUT_ENERGY).setPreviewCount(1))
                .or(Predicates.abilities(PartAbility.OUTPUT_ENERGY).setPreviewCount(1))
                // 🆕 2026-10-25：与基类主机同一条"任意输入输出仓"清单（物入 / 物出 / 流入 / 流出）
                .or(Predicates.abilities(PartAbility.IMPORT_ITEMS).setPreviewCount(1))
                .or(Predicates.abilities(PartAbility.EXPORT_ITEMS).setPreviewCount(1))
                .or(Predicates.abilities(PartAbility.IMPORT_FLUIDS).setPreviewCount(1))
                .or(Predicates.abilities(PartAbility.EXPORT_FLUIDS).setPreviewCount(1));
        // 🆕 2026-10-25：样板总成白名单（不声明上面那四种能力 ⇒ 只能按 id 放）
        // 🔴 这一行是 T2 的**实机判死点**：每条 id 逐个报"在不在方块注册表里"，
        //    一条 `=缺失` 就说明那种仓放不上去（只影响它自己，不影响成型/其它仓）。
        final StringBuilder detail = new StringBuilder();
        int hitCount = 0;
        int missCount = 0;
        for (String id : EXTRA_PART_BLOCK_IDS) {
            final Block block = blockOrNull(id);
            detail.append(id).append('=').append(block != null ? "存在" : "缺失").append("; ");
            if (block == null) {
                missCount++;
                continue;
            }
            predicate = predicate.or(Predicates.blocks(block));
            hitCount++;
        }
        com.shanhai.ShanhaiMod.LOGGER.info("[SHANHAI-HOST] 外环带放行清单：能力=能源仓/动力仓/物入/物出/流入/流出；"
                        + "白名单 {}/{} 存在（缺失 {} 条）；逐条 id→注册表：{}",
                hitCount, EXTRA_PART_BLOCK_IDS.length, missCount, detail.toString().trim());
        return predicate;
    }

    /**
     * 除"按能力"之外的<b>样板总成方块白名单</b>（2026-10-25 T2 用户点单）。
     *
     * <pre>
     *   gtceu:me_pattern_buffer                 GT 的 ME 样板总成（机器名 me_pattern_buffer）
     *   gtladditions:me_super_pattern_buffer    gtladditions 的超级样板总成
     *   gtceu:me_wildcard_pattern_buffer        通配符样板总成 —— 🔴 名字空间是 <b>gtceu</b>
     *   shanhai:stellar_sample_assembler        本工程的星律样板总成（ShanhaiStellarMachines 注册）
     * </pre>
     *
     * <p>🔴 <b>2026-10-25 收尾订正（第三条）</b>：原写 {@code gtlcore:me_wildcard_pattern_buffer}，
     * <b>那是错的</b>。真值是 <b>{@code gtceu:me_wildcard_pattern_buffer}</b> —— 这台机器由 gtlcore
     * 注册、但 <b>id 写在自己的（gtceu）命名空间下</b>；本仓
     * {@code ShanhaiWildcardMachines} 的类注释记的就是这条（"宿主 {@code gtceu:me_wildcard_pattern_buffer}"），
     * 既有部署态也是它。
     *
     * <p>怎么核的：另外三条按用户给的 id 原样落；第四条查了本包
     * {@code ShanhaiStellarMachines#STELLAR_SAMPLE_ASSEMBLER_ID = "stellar_sample_assembler"}。
     * 运行期每条都用 {@link #blockOrNull}（{@code ForgeRegistries.BLOCKS} 现查）过一遍，
     * <b>逐个 id 打出"存在/缺失"</b>（见上面那行日志）—— 所以"某条 id 写错了"不会变成静默。
     */
    public static final String[] EXTRA_PART_BLOCK_IDS = {
            "gtceu:me_pattern_buffer",
            "gtladditions:me_super_pattern_buffer",
            "gtceu:me_wildcard_pattern_buffer",
            "shanhai:stellar_sample_assembler",
    };

    /** 按 id 取方块；<b>取不到返回 {@code null}</b>（宽松版，只给白名单用）。 */
    private static Block blockOrNull(String id) {
        final ResourceLocation rl = ResourceLocation.tryParse(id);
        return rl == null ? null : ForgeRegistries.BLOCKS.getValue(rl);
    }

    /** 外观方块句柄（注册期惰性取）。 */
    public static Block appearanceBlock() {
        return requireBlock(APPEARANCE_BLOCK);
    }

    /**
     * 模块槽谓词 = <b>空气 ∪ 全部模块控制器方块</b>。
     *
     * <p>为什么含空气：本阶段要让"空槽的主机"也能成型（进游戏的 30 秒判据就是"放下去能不能成型"）。
     * 插上模块之后那一格变成模块控制器方块 ⇒ 同样合法；模块本体（7×7×13）在图案里是空格（任意），
     * 不参与匹配。
     *
     * <p>模块方块表为空 ⇒ 直接抛（与 {@code ShanhaiMachines#moduleSlotPredicate()} 同一条纪律：
     * 绝不放行成"谓词只认空气"那种静默失败）。
     *
     * <p>🔴 <b>{@code M} 与 {@code N}（{@link #filledModuleSlotPredicate()}）从此语义不同</b>：
     * 本方法的<b>第一候选仍是 {@code Predicates.air()}</b>，而 {@code N} 的第一候选已改为环带方块
     * （2026-10-25 用户改口径：那 24 格也要提前堵上）。这条退役路径<b>故意不动</b>：
     * 它只用来读旧 {@code .bin}，改了只会让老资源的行为跟着变。要改 {@code N} 请改 {@code N}。
     */
    public static TraceabilityPredicate moduleSlotPredicate() {
        final java.util.List<Block> moduleBlocks = ModuleRegistry.allModuleBlocks();
        if (moduleBlocks.isEmpty()) {
            throw new IllegalStateException("[SHANHAI-HOST] 模块方块表为空，拒绝生成 M 谓词："
                    + "那会让装了模块的主机结构恒不成型，且不抛异常、不打日志。"
                    + "请确认 ModuleRegistry.init() 先跑过。");
        }
        // 空气 ∪ 模块控制器 ∪ 环带方块
        //   空气      —— 经典搭法（模块槽留空，玩家自己往里放模块控制器）
        //   环带方块  —— 用户 2026-10-24 要的「把 24 格填成环带同款」，表面不留 1 格小孔
        //   模块控制器 —— 装了模块的主机
        // 🔴 三者都要收：只收「空气 ∪ 模块」会让填平的新主机不成型；
        //    只收「环带 ∪ 模块」会让已经搭好的老主机（那 24 格是空气）立刻不成型。
        TraceabilityPredicate predicate = Predicates.air()
                .or(Predicates.blocks(requireBlock(RING_BAND_BLOCK)));
        for (Block block : moduleBlocks) {
            predicate = predicate.or(Predicates.blocks(block));
        }
        return predicate;
    }

    /**
     * 'N'（模块槽，24 口最外那层的中心 24 格）谓词 = <b>环带方块（第一候选）∪ 空气 ∪ 全部模块控制器方块</b>。
     *
     * <ul>
     *   <li><b>环带方块</b>（{@link #RING_BAND_BLOCK}，<b>第一候选</b>）—— 2026-10-25 用户改的口径：
     *       这 24 格要<b>提前用方块堵上</b>（用户原话：「把那些给模块留的位置提前用方块堵上…
     *       就是加入主机的多方块的结构」）⇒ 终端一键搭建 / JEI 预览到这里<b>铺环带方块</b>，
     *       结构表面不再出现"给模块留的 24 个洞"；</li>
     *   <li><b>空气</b> —— {@link #MODULE_SLOT} 时代搭出来的老主机，那 24 格是<b>空气</b>，
     *       不收空气它们会立刻不成型（用户 2026-10-24 明确要求"老主机照样能成型"）；</li>
     *   <li><b>模块控制器方块</b> —— 装了模块的主机（模块自己的控制器就落在这 24 格上）。</li>
     * </ul>
     *
     * <p>🔴 <b>三者都要收</b>，缺一即坏（这就是"为什么不能只收两种"的账）：
     * <ul>
     *   <li>只收「环带 ∪ 模块」 ⇒ 老主机（那 24 格是空气）立刻不成型；</li>
     *   <li>只收「空气 ∪ 模块」（= 空气第一候选） ⇒ 一键搭建 / 预览在这 24 格<b>不放方块</b>，
     *       玩家看到的还是"给模块留的空洞"—— 正是 2026-10-25 用户报的那个问题；</li>
     *   <li>只收「空气 ∪ 环带」 ⇒ 装了模块的主机恒不成型（模块控制器不在候选里）。</li>
     * </ul>
     *
     * <p>🔴 <b>这是 2026-10-25 用户在 10-24 口径上的改主意</b>。10-24 的原话是
     * 「24 格 ＋ 腔口 7×7 补实，但是<b>最外面一层不要填</b>」⇒ 那时本方法的第一候选是
     * {@code Predicates.air()}（一键搭建到腔口中心<b>不放方块</b>）；现在他改成
     * "最外层那 24 格也要提前堵上"。<b>没变</b>的是：三种状态（空气 / 环带 / 模块控制器）都仍然收，
     * 所以老主机与装了模块的主机都照样成型。
     *
     * <p>⚠️ 候选顺序只影响"搭建 / 预览放什么"，<b>不影响能不能成型</b>：
     * 成型判定走 {@code TraceabilityPredicate#test}，它是
     * {@code common.stream().anyMatch(...)}（gtceu 1.20.1-1.4.4 字节码实证：
     * {@code TraceabilityPredicate#test} 偏移 57–77 为 {@code Stream.anyMatch}，候选顺序无关）。
     * 而"预览 / 一键搭建放什么"看的是候选表顺序：{@link TraceabilityPredicate#or} 的字节码是
     * {@code new TraceabilityPredicate(this)} + {@code common.addAll(other.common)}（插入序、ArrayList）。
     *
     * <p>🔴 <b>与 {@link #moduleSlotPredicate()}（{@code M}，已退役）从此语义不同</b>：
     * {@code M} 的<b>第一候选仍是空气</b>（它只用来读旧 {@code .bin}，行为故意保持原样不动），
     * {@code N} 的第一候选是环带方块。不要再把两条当"行为等价"看 —— 分成两条就是为了
     * 以后改一条不会顺手改坏另一条。
     */
    public static TraceabilityPredicate filledModuleSlotPredicate() {
        final java.util.List<Block> moduleBlocks = ModuleRegistry.allModuleBlocks();
        if (moduleBlocks.isEmpty()) {
            throw new IllegalStateException("[SHANHAI-HOST] 模块方块表为空，拒绝生成 N 谓词："
                    + "那会让装了模块的主机结构恒不成型，且不抛异常、不打日志。"
                    + "请确认 ModuleRegistry.init() 先跑过。");
        }
        // 🔴 第一候选 = 环带方块（2026-10-25 口径：一键搭建 / 预览把这 24 格也堵上，表面不留孔）
        // 三者都要收，缺一即坏（逐条见方法 javadoc）：
        //   空气      —— 老主机（M 时代搭的）那 24 格就是空气，不收 ⇒ 老主机立刻不成型
        //   环带方块  —— 堵平后的新主机 / 玩家手动填平（第一候选 ⇒ 预览与一键搭建铺它）
        //   模块控制器 —— 装了模块的主机（模块自己的控制器就坐在这 24 格上）
        TraceabilityPredicate predicate = Predicates.blocks(requireBlock(RING_BAND_BLOCK))
                .or(Predicates.air());
        for (Block block : moduleBlocks) {
            predicate = predicate.or(Predicates.blocks(block));
        }
        return predicate;
    }

    /**
     * 'P'（模块腔体，24 口 × 往里 12 层，现存 7,928 格）谓词 =
     * <b>空气（第一候选）∪ 任意方块</b>。
     *
     * <h2>🔴 2026-10-25 口径反转：腔体**不再填实**</h2>
     * 用户原话：「<b>我终端搭建的这个填实的东西你没去掉啊</b>」「<b>我说不要填实，不要填实</b>」。
     * 所以<b>第一候选从环带方块改成空气</b> —— 终端/JEI 一键搭建时这 12 层<b>留空</b>，
     * 只由 {@link #MODULE_SHELL_BRONZE}（{@code T}）/ {@link #MODULE_SHELL_STEAM}（{@code S}）
     * 摆出他那台模块的外壳（68 格）。
     *
     * <p>候选表顺序的机制（决定"搭出来是什么"，别再搞反）：候选来自
     * {@code TraceabilityPredicate#common}，{@code or(…)} 是 {@code common.addAll(other.common)}
     * （ArrayList、插入序），{@code sort()} 只排 {@code limited} 不动 {@code common}
     * ⇒ <b>写在最前面的那个就是一键搭建会铺的方块</b>；{@code Predicates.any()} 不带候选
     * （supplier 为 null），不会抢第一候选。
     *
     * <h2>为什么 {@code any()} 必须留</h2>
     * 这三种主机都得照样成型：
     * <ol>
     *   <li><b>装了模块的主机</b> —— 那 12 层里是模块自己的机壳（{@code forge_of_the_antichrist_module.bin}
     *       的 B/E/D/F/G），不是空气也不是环带方块；</li>
     *   <li><b>老主机</b> —— 这 12 层是空气（{@code air} 直接满足，不需要额外列）；</li>
     *   <li><b>已经用旧口径铺实过的主机</b>（就是用户此刻存档里那台，环带方块）——
     *       收 {@code any()} 之后<b>不会当场散架</b>，他只需自己把多余的方块挖掉，
     *       而不是被迫重搭整台。</li>
     * </ol>
     *
     * <p>⚠️ {@code any()} 让这一格<b>不校验任何东西</b>——这是<b>有意的</b>：图案在这里只负责
     * "留空 / 认任何东西"，不负责"必须是某方块"。（结构能不能成型由其它符号保证。）
     */
    public static TraceabilityPredicate filledCavityPredicate() {
        // 🔴 第一候选 = 空气（一键搭建**不再填实**）；any() 兜住已铺的环带方块 / 模块机壳 / 老主机空气
        return Predicates.air()
                .or(Predicates.any());
    }

    /**
     * 'Q'（已堵上的腔口，24 口最外那层 d=0 里除槽心外的 <b>824 格</b>）谓词 =
     * <b>环带方块（第一候选）∪ 任意方块</b>。
     *
     * <h2>用户原话（2026-10-25）</h2>
     * 「<b>我让你把模块的洞堵上</b>」「<b>就是加入主机的多方块的结构</b>」——
     * 他要的是"洞里不再是个空腔"，而不是"换来一个更小的洞"：{@link #filledModuleSlotPredicate()}
     * （{@code N}）只堵得住每口正中那 1 格，洞口周围那 824 格仍然是空的，所以单独加这一条。
     *
     * <h2>为什么必须收 {@code any()}（任意方块，含空气）</h2>
     * 🔴 <b>模块自己就占 d=0 这一层</b>：{@code forge_of_the_antichrist_module.bin} 是 13×7×7、
     * 89 格实心，它的 {@code aisle12}（21 格小盘）正好落在<b>主机 d=0 那层</b>。
     * 所以这条谓词如果只认环带方块，<b>装了模块的主机就恒不成型</b>；
     * 另外老主机（{@code .bin} 改之前搭好的）这 824 格是<b>空气</b>、
     * 玩家手动装饰时可能放任何方块 —— {@code any()} 一条全兜住
     * （{@code ANY} 本身就接受空气，所以不必再单列 {@code Predicates.air()}）。
     *
     * <h2>为什么第一候选是环带方块</h2>
     * 决定"JEI 预览 / 终端一键搭建铺什么"的是候选表顺序（{@code TraceabilityPredicate#common}，
     * {@code or(…)} = {@code common.addAll(other.common)}，ArrayList 插入序；{@code sort()} 只排
     * {@code limited}、不动 {@code common}）。{@code Predicates.any()} 自己不带候选
     * （supplier 为 null），不会抢第一候选 —— 所以把 {@link #RING_BAND_BLOCK} 写在前面，
     * 预览与一键搭建就会<b>把洞口铺成实心</b>，正是用户要的"堵上"。
     * 成型判定走 {@code TraceabilityPredicate#test}（{@code common.stream().anyMatch}），候选顺序无关。
     *
     * <p>🔴 与 {@link #filledCavityPredicate()}（{@code P}）是<b>两条独立的方法</b>，代码形状相同但
     * <b>故意不复用</b>：{@code P} 管往里 12 层，本方法管最外那层，以后改一层不会顺手改坏另一层。
     *
     * <p>⚠️ 与 {@code P} 一样，{@code any()} 让这批格子<b>不再校验任何东西</b> —— 这是<b>有意的</b>：
     * 图案在这里只负责"默认摆成实心"，不负责"必须是某方块"。
     */
    public static TraceabilityPredicate filledCavityMouthPredicate() {
        // 🔴 第一候选 = 环带方块（预览 / 一键搭建默认铺它）；any() 兜住模块机壳 / 老主机的空气 / 玩家装饰块
        return Predicates.blocks(requireBlock(RING_BAND_BLOCK))
                .or(Predicates.any());
    }

    /**
     * {@code T}（模块外壳·青铜，{@code .bin} 里 <b>1,074 格</b>）谓词 =
     * <b>青铜机械外壳（第一候选）∪ 任意方块</b>。
     *
     * <h2>用户原话（2026-10-25，他亲口定的最后口径）</h2>
     * 「<b>你现在 /function shanhai_marker:a_module_shell 是对的，写入多方块结构</b>」
     * <p>背景：他在主机（{@code shanhai:guixu_vision_core}，控制器世界坐标 {@code (1754,101,212)}、
     * {@code facing=east}）的 <b>16 号腔口</b>（槽心 {@code (1812,90,273)}）里<b>自己搭了一套模块外壳</b>
     * = <b>47 格 {@code gtceu:bronze_machine_casing} ＋ 21 格 {@code gtceu:industrial_steam_casing}</b>。
     * 他要的不是"用环带方块把这 12 层腔体填实"（那是已有的 {@code P}），而是
     * "<b>把他的外壳照原样写进图案</b>"，让终端一键搭建在每个模块位都摆出这套外壳。
     *
     * <h2>为什么第一候选就是他那两种方块</h2>
     * 决定"JEI 预览 / 终端一键搭建铺什么"的是候选表顺序（{@code TraceabilityPredicate#common}：
     * {@code or(…)} = {@code common.addAll(other.common)}，ArrayList 插入序；{@code sort()} 只排
     * {@code limited}、不动 {@code common}；而 {@code Predicates.any()} 的 supplier 是 null，
     * 自己不带候选、不会抢第一候选）。所以把
     * {@link #MODULE_SHELL_BRONZE_BLOCK} 写在前面，一键搭建就会<b>照他的外壳铺青铜</b>；
     * 成型判定走 {@code TraceabilityPredicate#test}（{@code common.stream().anyMatch}），候选顺序无关。
     *
     * <h2>🔴 为什么 {@code any()} 必须留（去掉它就坏了）</h2>
     * 这 1,074 格在真实世界里<b>不一定是青铜</b>，三种情况都要收：
     * <ol>
     *   <li><b>已经搭好的老主机</b>：这 12 层（{@code d=1..12}）里放的是环带方块（{@code gtlcore:molecular_casing}）
     *       或者<b>空气</b>——本图案之前搭出来的主机就是那样，只认青铜会让它们<b>立刻不成型</b>
     *       （用户 2026-10-24 明确要求"老主机照样能成型"）；</li>
     *   <li><b>装了模块的主机</b>：模块本体（{@code forge_of_the_antichrist_module.bin}）自己就占这 12 层，
     *       用的是模块自己的机壳（{@code gtceu}/{@code kubejs} 的多种方块），不是青铜；</li>
     *   <li><b>玩家手动装饰 / 换材料</b>：{@code any()} 与 {@code .bin} 里"空格 = 不参与匹配"的语义一致。</li>
     * </ol>
     * <p>⚠️ 与 {@link #filledCavityPredicate()}（{@code P}）一样，{@code any()} 让这批格子
     * <b>不再校验任何东西</b> —— 这是<b>有意的</b>：图案在这里只负责"默认照他的外壳摆"，不负责"必须是青铜"。
     *
     * <p>🔴 与 {@link #moduleShellSteamPredicate()}（{@code S}）是两条独立方法，<b>故意不复用</b>：
     * 两种材料各改各的，以后换方块不会顺手改坏另一种。
     */
    public static TraceabilityPredicate moduleShellBronzePredicate() {
        // 🔴 第一候选 = 用户外壳里的青铜机械外壳（预览 / 一键搭建照它铺）；any() 兜住环带方块 / 空气 / 模块机壳
        return Predicates.blocks(requireBlock(MODULE_SHELL_BRONZE_BLOCK))
                .or(Predicates.any());
    }

    /**
     * {@code S}（模块外壳·蒸汽，{@code .bin} 里 <b>504 格</b> = 21 格/槽位 × 24）谓词 =
     * <b>工业蒸汽机械方块（第一候选）∪ 任意方块</b>。
     *
     * <p>这 21 格／槽位是他那套外壳的"前脸圆盘"（槽本地坐标 {@code c=1} 那一层的 5×5 圆盘，
     * 四角空缺；见 {@link #MODULE_SHELL_BRONZE} 的形状说明）。由于 24 个槽位的蒸汽格**互不重叠**
     * （重叠的 50 格全是青铜），{@code 21 × 24 = 504} 一格不少。
     *
     * <h2>为什么第一候选是他那两种方块 / 为什么必须留 {@code any()}</h2>
     * 与 {@link #moduleShellBronzePredicate()} 同一条账（用户原话、候选表顺序、三种必须收的现实情况），
     * 理由是<b>逐个格子一样</b>的，所以不重复抄一遍：这里只强调——
     * 🔴 他现在摆的这个腔口（16 号）在世界里<b>已经被他摆成</b>工业蒸汽机械方块；
     * 如果去掉 {@code any()} 只认环带方块，<b>16 号腔口那台现成的主机会立刻不成型</b>
     * （那 21 格里放的就是蒸汽方块，不是环带方块）。
     */
    public static TraceabilityPredicate moduleShellSteamPredicate() {
        // 🔴 第一候选 = 用户外壳里的工业蒸汽机械方块；any() 兜住环带方块 / 空气 / 模块机壳 / 老主机
        return Predicates.blocks(requireBlock(MODULE_SHELL_STEAM_BLOCK))
                .or(Predicates.any());
    }

    /** 按 id 取方块；取不到就抛（带 id），绝不返回 null。 */
    private static Block requireBlock(String id) {
        final ResourceLocation rl = ResourceLocation.tryParse(id);
        if (rl == null) {
            throw new IllegalStateException("[SHANHAI-HOST] 方块 id 不合法：" + id);
        }
        final Block block = ForgeRegistries.BLOCKS.getValue(rl);
        if (block == null) {
            throw new IllegalStateException("[SHANHAI-HOST] 注册表里没有方块 " + id
                    + " —— 结构图案的符号表与造型调色板不一致，或该 mod 缺失。");
        }
        return block;
    }
}
