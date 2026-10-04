package com.shanhai.common.recipe;

import com.gregtechceu.gtceu.api.capability.recipe.IO;
import com.gregtechceu.gtceu.api.gui.GuiTextures;
import com.gregtechceu.gtceu.api.recipe.GTRecipeType;
import com.gregtechceu.gtceu.common.data.GTSoundEntries;
import com.gregtechceu.gtceu.common.data.GTRecipeTypes;
import com.lowdragmc.lowdraglib.gui.texture.ProgressTexture;
import com.shanhai.ShanhaiMod;

import net.minecraft.world.item.crafting.RecipeType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 本 mod【唯一】的配方类型注册点。
 *
 * <h2>这个类注册什么</h2>
 * 照旧私货 {@code DShanhaiRecipeTypes.java}（492 行）<b>逐条</b>抄回它自注册的全部类型：
 * <ul>
 *   <li><b>40 个「真类型」</b>（其中 3 条是 2026-09-22 先做的）；</li>
 *   <li><b>36 个「显示类型」</b> {@code gtceu:nine_industrial_mode_0} … {@code _35}
 *       —— 原版源码注释原文：「36 水浒传模式显示类型 — 仅用于 Jade 机器模式展示，翻译名在 zh_cn.json」。</li>
 * </ul>
 * 合计 <b>76 条</b>。
 *
 * <h2>🔴🔴 订正（2026-09-26，用户点单"40 条"）—— <b>这条是最新的，与下面 2026-09-23 那条相反，以本条为准</b></h2>
 * 用户 2026-09-26 交办：为了让「原初山海调试模块」在 JEI 里展示<b>全部山海自有配方类型</b>，
 * 点单 **"40 条真类型全部挂上"** ⇒ <b>下一条（2026-09-23 裁剪到 16 条）已被本条覆盖</b>：
 * <ul>
 *   <li>那 24 条真类型<b>已按 ⛔作废块原文逐字恢复注册</b>（声明区 + {@code init()} 末尾 24 段链）；</li>
 *   <li>{@link #REAL_TYPE_COUNT} <b>16 → 40</b>，{@link #countMissingReal()} 的数组同步补 24 项；</li>
 *   <li><b>36 条 GTNH 显示类型仍然不恢复</b>（用户 2026-09-22 原话「那个 GTNH 是我重制版不会添加的」）；</li>
 *   <li>下一条 2026-09-23 的<b>旧句原样保留</b>（本工程惯例），但<b>不再是当前口径</b>。</li>
 * </ul>
 *
 * <h2>🔴 订正（2026-09-23，用户裁决裁剪后）——上面那两句是【旧句，原样保留】</h2>
 * <p>⚠️ <b>本段已被上一段（2026-09-26）覆盖，仅作历史留档。</b>
 * 用户 2026-09-23 裁决：<b>只保留被 {@code ModuleRegistry} 那 24 台模块实际引用的类型</b>，
 * 其余全删。于是：
 * <ul>
 *   <li><b>36 个显示类型整组删除</b>（{@code nine_industrial_mode_0..35}）——
 *       理由：它们来自 <b>GTNH（{@code GTnotleisure}）</b>，用户 2026-09-22 明说
 *       「那个 GTNH 是我重制版不会添加的」；⇒ {@code NINE_INDUSTRIAL_MODES} 数组、
 *       它的 for 循环、{@code DISPLAY_TYPE_COUNT}、{@code countMissingDisplay()} <b>一并删除</b>，
 *       <b>不留"恒为 0 的空壳"</b>（幽灵概念会让后人以为"这东西本来就该有"）。</li>
 *   <li><b>24 个真类型删除</b>（40 − 16），因为它们<b>没有被任何模块引用</b>
 *       （取证：全 {@code src} 100 个文件扫描，除本文件自己的声明/fail-fast/日志外只有
 *       {@code ShanhaiRegistry} 的报错串提到过其中几个，{@code ModuleRegistry} 一次都没出现）。</li>
 * </ul>
 * ⇒ <b>最终只注册 16 个真类型</b>；{@code REAL_TYPE_COUNT = 16}；
 * 日志行相应变成「真类型 16 / 16」（<b>不再有"显示类型"那一段</b>）。
 * <p>⚠️ 被删的 24 个真类型的<b>原文（字段声明 + register 链）逐字保留在文件末尾的作废块里</b>，
 * 将来做新机器时要重新加回来 —— 见下面 &lt;h2&gt;作废块&lt;/h2&gt;。此前本工程<b>一条自定义类型都没有</b>（取证：全 {@code src} 扫
 * {@code GTRecipeTypes.register|RecipeTypes.register|new GTRecipeType} = <b>0 命中</b>；
 * 108 处 {@code RecipeType} 全是引用 GTCEu 原版类型或注释）。
 *
 * <h2>🔴 为什么必须挂 {@code GTRecipeType} 的泛型监听器，而不能直接调 {@code init()}</h2>
 * {@code GTRecipeTypes.register(name, category, proxyRecipes...)} 的字节码（{@code javap -p -c}）：
 * <pre>
 *    0: new           #29   // class com/gregtechceu/gtceu/api/recipe/GTRecipeType
 *    5: invokestatic  #137  // GTCEu.id:(Ljava/lang/String;)Lnet/minecraft/resources/ResourceLocation;
 *   10: invokespecial #140  // GTRecipeType."&lt;init&gt;":(ResourceLocation;String;[RecipeType;)V
 *   14: getstatic     #146  // 🔴 BuiltInRegistries.f_256990_   （RecipeType 注册表）
 *   22: invokestatic  #155  // GTRegistries.register:(Registry;ResourceLocation;Object;)Object
 *   26: getstatic     #158  // 🔴 BuiltInRegistries.f_256769_   （RecipeSerializer 注册表）
 *   40: invokestatic  #155  // GTRegistries.register:(…)
 *   44: getstatic     #165  // 🔴 GTRegistries.RECIPE_TYPES
 *   52: invokevirtual #168  // GTRegistry$RL.register:(Object;Object;)Object
 * </pre>
 * ⇒ 它<b>写三个注册表</b>，全是「冻结就抛」的那种。所以只能在 GTCEu 自己开的那道窗口里调。
 *
 * <h2>窗口在哪：GTCEu 主动 post 了 {@code RegisterEvent&lt;…, GTRecipeType&gt;}（与机器同款机制）</h2>
 * {@code com.gregtechceu.gtceu.common.data.GTRecipeTypes#init()} 的字节码（同一次 {@code javap -p -c}）：
 * <pre>
 *  107: invokestatic  #294  // ModLoader.get:()Lnet/minecraftforge/fml/ModLoader;
 *  110: new           #17   // class com/gregtechceu/gtceu/api/GTCEuAPI$RegisterEvent
 *  114: getstatic     #165  // GTRegistries.RECIPE_TYPES
 *  117: ldc           #29   // 🔴 class com/gregtechceu/gtceu/api/recipe/GTRecipeType   ← 泛型实参
 *  119: invokespecial #297  // GTCEuAPI$RegisterEvent."&lt;init&gt;":(GTRegistry;Class;)V
 *  122: invokevirtual #301  // ModLoader.postEvent:(Event;)V     ← 🔴 这就是给 addon 的窗口
 *  125: getstatic     #165  // GTRegistries.RECIPE_TYPES
 *  128: invokevirtual #304  // GTRegistry$RL.freeze:()V          ← 🔴 窗口在 freeze 之前
 * </pre>
 * ⇒ <b>{@code addGenericListener(GTRecipeType.class, …)} 必被命中</b>（filter 是
 * {@code getGenericType() == GTRecipeType.class} 身份比较，GTCEu 传的就是这个 class 字面量），
 * 且<b>此刻 {@code RECIPE_TYPES} 还没 freeze</b>（freeze 在第 128 条，postEvent 之后）。
 * <p>⚠️ 我第一遍读这段字节码时把方法截断了，看到尾部一堆序列化器注册就以为「没有 postEvent」——
 * 实际上 <b>{@code postEvent} 在偏移 122，后面还有 90 条指令</b>。
 * 按本项目「检查器自己必须先被证明是对的」那条记在这里：<b>截断窗口会造出假否定。</b>
 *
 * <h2>🔴 只注册、不挂机器</h2>
 * 本类只负责「把类型定义出来」。旧私货 48 台机器里只挂了其中一部分（另有 3 条全源码树零引用：
 *  {@code matter_aggregation} / {@code worldline_cutting} / {@code high_dimensional_fragment_cutting}）。
 * <b>谁挂什么在 {@code ModuleRegistry} / {@code ShanhaiMachines}，不在本文件。</b>
 * <p>🔴 <b>2026-09-29 订正（上面那句是说【旧私货】的源码树，别拿它当本工程的现状）</b>：
 *  本工程里这 3 条<b>都已被引用</b> —— 全部进了调试模块的 41 条大表
 *  （{@code ModuleRegistry#RECIPE_DEBUG_MODULE}）；其中 {@code worldline_cutting}
 *  又于 2026-09-29 被用户点单挂到<b>世线裂解枢纽</b>（{@code RECIPE_WORLDLINE_CRACKING_HUB}）。
 *
 * <h2>命名（用户 2026-09-22 亲定，不许改）</h2>
 * <ul>
 *   <li>{@code gtceu:matter_aggregation} —— <b>原初物质凝集</b></li>
 *   <li>{@code gtceu:worldline_cutting} —— <b>原初世线切割</b></li>
 *   <li>{@code gtceu:high_dimensional_fragment_cutting} —— <b>高维碎片裁切</b></li>
 * </ul>
 * 其余类型的中文名一律取<b>原版 lang 原文</b>（不是我们翻译的），写在
 * {@code assets/shanhai/lang/zh_cn.json}，键 = <b>{@code gtceu.<id>}</b>。
 * 键格式不是推的，是<b>GTCEu 自己的语言文件里的真值</b>：解出
 * {@code libs/gtceu-1.20.1-1.4.4.jar!assets/gtceu/lang/zh_cn.json} 后逐字命中
 * {@code "gtceu.macerator":"研磨机"} / {@code "gtceu.assembler":"组装机"} /
 * {@code "gtceu.electric_blast_furnace":"电力高炉"}；而 {@code "gtceu.recipe_type.macerator"} <b>不存在</b>。
 *
 * <h2>🔴 那 3 条"搬运键"（队长 2026-09-22 裁决）——<b>不是新译名，别误会</b></h2>
 * {@code worldline_probability_cracking} / {@code worldline_matter_recurrence} / {@code worldline_sampling}
 * 在原版 lang 里<b>只有</b> {@code gtceu.recipe_type.<id>}（没有 {@code gtceu.<id>}）。而 GTCEu 的
 * JEI 分类标题只读 {@code gtceu.<id>}（{@code GTRecipeTypeCategory#getTitle()} =
 * {@code ResourceLocation.toLanguageKey()}）⇒ 这 3 条在 JEI 里会显示裸键。
 * ⇒ 我们<b>把 {@code gtceu.recipe_type.<id>} 那条的值原样搬运</b>到 {@code gtceu.<id>}（**同值，不是新译名**），
 * 且<b>原版那条 {@code gtceu.recipe_type.<id>} 保留不删</b>（留档/兼容）。
 * <p>🔴 值分别是：<b>概率裂解</b> / <b>物质复现</b> / <b>世线采样</b> —— 三个都来自原版 lang 原文。
 * <p>另：36 条显示类型 {@code nine_industrial_mode_0..35} 原版<b>同样只有</b>
 * {@code gtceu.recipe_type.<id>}（{@code gtceu.<id>} 实测 36/36 全空），故照实际有的键写；
 * 这 2 条无中文名（lang 未收录）<b>我们没有加、也没有自译</b>：
 * {@code wl_board_circuit_assembly} / {@code wl_board_wafer_etching}。
 * <p>🔴 <b>2026-09-26 订正（上面这句旧话 <u>与事实不符</u>，旧句原样保留在上面）</b>：
 * 实测<b>我们工程的 {@code zh_cn.json} 里这两个键 <u>都有</u></b> ——
 * {@code "gtceu.wl_board_circuit_assembly":"世线板电路组装"}、
 * {@code "gtceu.wl_board_wafer_etching":"世线晶圆蚀刻"}（见该文件 61-62 行附近）。
 * 而<b>上游旧私货的 lang 里这两个键都没有</b> ⇒ 即这两个中文名是<b>本工程自译的</b>，
 * 不是"原版 lang 原文"。⇒ 两个后果，都<b>不</b>由本文件擅自处理：
 * <ol>
 *   <li>它们<b>在 JEI 里不会显示裸键</b>（有名字）；</li>
 *   <li>但"本文件所有中文名一律取原版 lang 原文、不自译"这条口径</li>在此 2 条上<b>不成立</b> ——
 *       要不要保留这两个自译名、要不要改这条口径，<b>需用户裁决</b>（本工程规矩：改口径要先拿到裁决）。</li>
 * </ol>
 *
 * <h2>🔴 与原版的【唯一偏离】——已由用户裁决「接受」，⛔ 不要"顺手补上"</h2>
 * <p>🔴 <b>唯一偏离 = 不抄 gtladditions 的 {@code GTLAddSoundEntries}（用户裁决）。</b>
 * 即：原版 37 条链尾的 {@code .setSound(GTLAddSoundEntries.INSTANCE.getFORGE_OF_THE_ANTICHRIST())}
 * 本类<b>不写</b>；而 3 条 {@code .setSound(GTSoundEntries.ARC)}
 * （{@code primordial_myriad_ascension_tier_1} / {@code _tier_2} / {@code primordial_stellar_reaction}）
 * <b>已于 2026-09-22 按队长裁决恢复抄写</b>（见实现里那三处 `setSound(GTSoundEntries.ARC)`）。
 * <p>不抄 gtladditions 那个，理由两条（都<b>只适用于 gtladditions 的 {@code GTLAddSoundEntries}</b>）：
 * <ol>
 *   <li><b>它是 gtladditions 的【非 API】符号</b>：{@code javap} 出来的 FQN 是
 *       {@code com.gtladd.gtladditions.common.modify.GTLAddSoundEntries}（{@code common.modify}，
 *       不是 {@code api}）—— 而本工程有一条明写的隔离墙：
 *       「{@code common} 侧唯一引用 gtladditions 非 API 的类是 {@code GtlAddCompat}」。</li>
 *   <li><b>它是一次 Registrate 注册，不是纯读字段</b>：{@code GTLAddSoundEntries#register(String,int)} 字节码
 *       {@code 0: GTLAddRegistration.Companion.getREGISTRATE() → 13: GTLAddRegistration.sound(ResourceLocation)
 *       → 17: SoundEntryBuilder.attenuationDistance(int) → 20: SoundEntryBuilder.build():SoundEntry}
 *       ⇒ 取这个字段会<b>触发 {@code <clinit>} 并当场 build 一个声音条目</b>。
 *       它写的是哪张注册表、那一刻是否已冻结，<b>没有任何证据</b> ——
 *       而本工程已经因「在错误的时机碰冻结注册表」炸过一次（见 {@code ShanhaiRegistry} 类注释）。</li>
 * </ol>
 * <p>⚠️ <b>上一条理由【不适用于】{@code GTSoundEntries.ARC}</b>：它是 GTCEu 自己的类（不是第三方非 API），
 * GTCEu 自家机器到处在用它 ⇒ 不存在"碰冻结注册表"的问题。所以这 3 条恢复抄写（队长 2026-09-22 裁决）。
 * <p>⚠️ 还有一处<b>已作废的旧口径</b>（留档，别重蹈）：本类 2026-09-22 一度把"省略 setSound"
 * 扩张到 {@code GTSoundEntries.ARC}，理由是"它同样会触发静态初始化 = 时机未验证"。
 * 队长裁定那是<b>过度保守</b>（理由只对 gtladditions 成立），已回退。
 * <p>⚠️ <b>除这一处外，其余全部链式调用、{@code category} 与 {@code setMaxIOSize} 实参逐字照抄原版。</b>
 *
 * <h2>🔴 裁决留档（2026-09-22，用户拍板；<b>照抄这句话，别把它改写成"我们的选择"</b>）</h2>
 * <pre>
 * ⚠️ 已知偏离（用户 2026-09-22 裁决：接受）：未抄 .setSound(GTLAddSoundEntries…)。理由两条（见上）。
 *    若将来要补：那 37 条链尾各加一行 + 让 GtlAddCompat 出薄封装。
 *    （3 条 GTSoundEntries.ARC 不算偏离 —— 已按裁决恢复抄写。）
 * ⚠️ en_us.json（用户 2026-09-22 裁决：不补）：英文环境下这些类型会显示裸键 gtceu.&lt;id&gt;。
 * </pre>
 * <b>写在这里的目的（原话）</b>：<b>防止将来有人看到"和原版不一致"就顺手补上</b> ——
 * 那正是<b>会崩的那一处</b>（理由第 2 条：取那个字段会触发一次时机未验证的 Registrate 注册）。
 * <p>🔴 <b>两条都不是"本工程的取舍"，是用户裁决；要改回去必须先拿到新的用户裁决，不许"顺手"。</b>
 */
public final class ShanhaiRecipeTypes {

    // ═══════════════════ 40 个真类型（字段顺序照原版 DShanhaiRecipeTypes.java:15-54）═══════════════════

    /** 原初发电协议 —— 原始真空零点能发生器。 */
    public static GTRecipeType PRIMORDIAL_POWER_GENERATOR;
    /** 原初恒星反应 —— 原初宇宙反应炉。 */
    public static GTRecipeType PRIMORDIAL_STELLAR_REACTION;
    /** 原初生物演化协议 —— 原初生物核心。 */
    public static GTRecipeType PRIMORDIAL_BIOLOGICAL_CORE;
    /** 原初物质重组 —— 原初物质重组核心。 */
    public static GTRecipeType PRIMORDIAL_MATTER_RECOMBINATION;
    /** 原初因果编织 —— 原初因果编织矩阵。 */
    public static GTRecipeType PRIMORDIAL_CAUSAL_WEAVING;
    /** 原初奇点反演 —— 原初奇点反演核心。 */
    public static GTRecipeType PRIMORDIAL_SINGULARITY_INVERSION;
    /** 太虚熔炼 —— 原初太虚宇宙锻炉。 */
    public static GTRecipeType TAIXU_SMELTING;
    /** 世线震荡收集 —— 原初分歧发生器。 */
    public static GTRecipeType WORLDLINE_OSCILLATION_COLLECTION;
    /** 星际物质吸取 —— 原初分歧发生器。 */
    public static GTRecipeType INTERSTELLAR_MATTER_ABSORPTION;
    /** 物质流凝结 —— 原初物质铸造机。 */
    public static GTRecipeType MATTER_FLOW_CONDENSATION;
    /** 原初能量吸取 —— 原初分歧发生器。 */
    public static GTRecipeType PRIMORDIAL_ENERGY_ABSORPTION;
    /** 光子分离 —— 原初物质铸造机。 */
    public static GTRecipeType PHOTON_SEPARATION;
    /** 物质模块铸造（{@code setHasResearchSlot(true)}；4 条带专属 .rtui 的类型之一）—— 原初物质铸造机。
     *  <p>🔴 2026-09-25 用户裁决：{@code setMaxIOSize} = <b>(17, 1, 4, 0)</b>（原 (15,6,6,6)），
     *  四个数 = 其专属模板 {@code assets/gtceu/ui/recipe_type/matter_module_casting.rtui}
     *  真正画出的槽位数，也等于现存 25 条该类型配方的实测最大值。详见 {@link #init()} 里那一大段注释。 */
    public static GTRecipeType MATTER_MODULE_CASTING;
    /** 物质锻造 —— 原初物质铸造机。 */
    public static GTRecipeType MATTER_FORGING;
    /** 原初物质解构 —— 原初混沌蜉蝣解构结晶炉（{@code primordial_chaotic_ephemeral_deconstruction_crystallization_furnace}）。
     *  <p>🔴 2026-09-26 用户点单（原话逐字）：「给原初混沌蜉蝣解构结晶炉添加一个新的配方种类，
     *  名字叫原初物质解构，1流体输入，1物品输入，同时，需要预留10流体输出和20物品输出」
     *  ⇒ {@code setMaxIOSize(1, 103, 1, 16)}（物品入 1／物品出 20／流体入 1／流体出 16）。
     *  ⚠️ 流体出 10 ⇒ **16**：2026-09-26 用户裁决「放宽上限到 16」（原话），
     *     起因是星门水晶浆液那条产线要吐 16 种流体。
     *  <p>四个数 = 专属模板 {@code assets/gtceu/ui/recipe_type/primordial_matter_deconstruction.rtui}
     *  （**190×114**；由 {@code black_hole_event_horizon_blast.rtui} 裁剪改写而来）真正画出的槽位数
     *  —— 见 {@link #init()} 里那段注释的取证。 */
    public static GTRecipeType PRIMORDIAL_MATTER_DECONSTRUCTION;
    /** 无中文名（lang 未收录，带专属 .rtui）—— 原初装配线模块 ＋ 永恒格雷工坊额外模块。
     *  <p>🔴 2026-09-26 订正：旧句"无中文名（lang 未收录）"<b>与事实不符</b>（原文保留于上行）——
     *  我们 {@code zh_cn.json} 里<b>有</b> {@code gtceu.wl_board_circuit_assembly = 世线板电路组装}；
     *  只是上游旧私货 lang 没有该键 ⇒ 它是<b>本工程自译</b>。详见类注释同名订正块。 */
    public static GTRecipeType WL_BOARD_CIRCUIT_ASSEMBLY;
    /** 无中文名（lang 未收录，带专属 .rtui）—— 原初装配线模块 ＋ 原初世线蚀刻核心。
     *  <p>🔴 2026-09-26 订正：同上 —— 我们 {@code zh_cn.json} 里有
     *  {@code gtceu.wl_board_wafer_etching = 世线晶圆蚀刻}，是<b>本工程自译</b>，不是"无中文名"。 */
    public static GTRecipeType WL_BOARD_WAFER_ETCHING;

    // ═══════════════════════════════════════════════════════════════════════════════════════════════
    // 🔴 2026-09-26 恢复：下面 24 条真类型（**用户点单："40 条"**）
    //
    // 由来：2026-09-23 用户裁决「裁剪配方类型注册」，把没被 24 台模块引用的 24 条真类型与
    // 36 条显示类型一起删掉（原文逐字留在本文件末尾的 ⛔作废块里，见 &lt;h2&gt;作废块&lt;/h2&gt;）。
    // 2026-09-26 用户点单 **"40 条真类型全部挂上"**（因为「原初山海调试模块」要在 JEI 里
    // 展示**全部山海自有配方类型**）⇒ 本块把这 24 条**按作废块原文逐字恢复**。
    //
    // 恢复口径（三条，全部照旧、不得改）：
    //   ① 链式调用 / category / setMaxIOSize 实参**逐字照抄**作废块原文；
    //   ② 24 段里凡原版链尾有 {@code .setSound(...)} 的，**照作废块原文**处理 ——
    //      即：3 条 {@code GTSoundEntries.ARC}（tier_1 / tier_2）保留（作废块原文里就带着），
    //      而 gtladditions 的 {@code GTLAddSoundEntries} 那 37 处**仍然不抄**（用户 2026-09-22 裁决）；
    //   ③ **36 条显示类型不恢复**（来自 GTNH，用户 2026-09-22 原话「那个 GTNH 是我重制版不会添加的」）。
    //
    // ⚠️ 本块 24 条目前**没有任何机器挂它们**（实测：ModuleRegistry 里对这 24 个字段的引用数 = 0）
    //   ⇒ 它们唯一的运行/展示载体是 {@code shanhai:primordial_debug_module}（第 25 台模块）。
    //
    // ⚠️ 注册顺序说明（如实交代）：24 段链**集中放在 init() 末尾**（fail-fast 之前），
    //   不再插回原版行号对应的"原位置"。理由：每条 register 互相独立，顺序不影响语义；
    //   集中放可把 24 处散点插入压成一处，降低"整段替换丢定义"那类事故的概率。
    //   每段仍保留 {@code // :NNN-NNN} 的**原版行号标记**，便于逐行回去对账。
    // ═══════════════════════════════════════════════════════════════════════════════════════════════

    /** 代理执行占位类型（{@code setMaxTooltips(1)}，无 slotOverlay）—— 代理执行机器。 */
    public static GTRecipeType PROXY_EXECUTION;

    /** 原初铸币工厂 —— 原版有该机器，<b>本工程已删除该机器</b>；类型照原版保留注册。 */
    public static GTRecipeType COIN_FORGE;

    /** 大明科技聚合类型 —— 大明工业机器。 */
    public static GTRecipeType NINE_INDUSTRIAL;

    /** 事件视界爆破（4 条带专属 .rtui 的类型之一）—— 黑洞收容机器。 */
    public static GTRecipeType BLACK_HOLE_EVENT_HORIZON_BLAST;

    /** 黑洞中子态素压缩 —— 黑洞收容机器。 */
    public static GTRecipeType BLACK_HOLE_NEUTRONIUM_COMPRESSOR;

    /** 黑洞引力压缩 —— 黑洞收容机器。 */
    public static GTRecipeType BLACK_HOLE_COMPRESSOR;

    /** 高维碎片裁切（用户拟名）—— <b>原版零挂载</b>。 */
    public static GTRecipeType HIGH_DIMENSIONAL_FRAGMENT_CUTTING;

    /** 原初世线切割（用户拟名）—— <b>原版零挂载</b>；🟢 2026-09-29 起挂到<b>世线裂解枢纽</b>。 */
    public static GTRecipeType WORLDLINE_CUTTING;

    /** 世线采样 —— 世线裂解枢纽。 */
    public static GTRecipeType WORLDLINE_SAMPLING;

    /** 世线物质重现 —— 世线裂解枢纽。 */
    public static GTRecipeType WORLDLINE_MATTER_RECURRENCE;

    /** 概率裂解 —— 世线裂解枢纽。 */
    public static GTRecipeType WORLDLINE_PROBABILITY_CRACKING;

    /** 光子虹吸（{@code "single"}，5 条 slotOverlay）—— 世线裂解枢纽 ＋ 零点光子转换器。 */
    public static GTRecipeType PHOTON_SIPHON;

    /** 零点转换（{@code "single"}）—— 世线裂解枢纽 ＋ 零点光子转换器。 */
    public static GTRecipeType ZERO_POINT_CONVERSION;

    /** 原初物质凝集（用户拟名；原版 lang 无此键）—— <b>原版零挂载</b>。 */
    public static GTRecipeType MATTER_AGGREGATION;

    /** 引力波广域广播 —— 引力波天线发射器。 */
    public static GTRecipeType GRAVITATIONAL_WAVE_CONSUMPTION;

    /** 宇宙修改·天界领航 —— 天界领航塔（⚠️此处原注释写"注册在它自己类里"，**实测不准确**：
     *  它在 {@code DShanhaiRecipeTypes.java:205} 注册；旧句原样保留，2026-09-26 订正）。 */
    public static GTRecipeType TIANJIE_NAVIGATION;

    /** 多维星穹零点聚合 —— 天界星云零点虹吸枢纽。 */
    public static GTRecipeType NEBULA_SIPHONING;

    /** 混沌合成（lang 原文值含 §k 混淆格式码）—— 引力波天线发射器 ＋ 创世之眼模块。 */
    public static GTRecipeType CHAOS_CRAFTING;

    /** 七十二变 —— 引力波天线发射器。 */
    public static GTRecipeType SEVENTY_TWO_CHANGES;

    /** 引力波宏观干涉 —— 引力波天线发射器。 */
    public static GTRecipeType GRAVITATIONAL_WAVE_PRODUCTION;

    /** 一级原初万象晋升 —— 原初万象衍生核心（我们未做该模块）。 */
    public static GTRecipeType PRIMORDIAL_MYRIAD_ASCENSION_TIER_1;

    /** 二级原初万象晋升 —— 原初万象衍生核心（我们未做该模块）。 */
    public static GTRecipeType PRIMORDIAL_MYRIAD_ASCENSION_TIER_2;

    /** 苦命鸳鸯 —— 终焉创始现实修改矩阵。 */
    public static GTRecipeType KU_MING_YUAN_YANG;

    /** 量子化现实重构 —— 终焉创始现实修改矩阵。 */
    public static GTRecipeType SPACETIME_DISTORTION;

    // ═══════════════════════════════════════════════════════════════════════════════════════════
    // 🆕 2026-09-30 新增的第 42／43 条（用户点单：「透镜再见」按【透镜 → 电路】搬进我们的两个新类型）
    //
    // 用户原话（逐字）：
    //   「你先给原初世线蚀刻核心添加一种全新的配方类型：原初激光蚀刻，并为其添加配方
    //     （模板按照光子晶阵蚀刻里面所有的配方，但是透镜全换成电路），同理，给原初量子扭曲矩阵
    //     添加一种全新的配方类型：原初蜂群铸造，并为其添加配方
    //     （模板按照纳米蜂群工厂里面所有的配方，但是透镜全换成电路），
    //     做完之后可以删除dgy中的透镜再见配方」
    //
    // 🔴 「模板」在这里是【运行期真实存在的两个上游类型】——`id` 与 `setMaxIOSize` 都不是估的：
    //   · 光子晶阵蚀刻 = {@code gtceu:photon_matrix_etch}
    //     出处：{@code gtladditions-3.2.8Custom-fix1.jar} →
    //     {@code assets/gtceu/lang/zh_cn.json} 的 {@code "gtceu.photon_matrix_etch": "光子晶阵蚀刻"}；
    //     注册字节码：{@code com.gtladd/gtladditions/common/recipe/GTLAddRecipesTypes.<clinit>}
    //     偏移 11-56（{@code javap -p -c}，§见 handoff\outbound\原初激光蚀刻与蜂群铸造.md）
    //     ⇒ {@code register("photon_matrix_etch","multiblock")} ＋ {@code setEUIO(IO.IN)}（偏移 27）
    //       ＋ {@code setMaxIOSize(3, 1, 1, 0)}（偏移 30-34）＋ {@code setMaxTooltips(4)}（偏移 37）
    //       ＋ {@code setProgressBar(PROGRESS_BAR_ARROW, LEFT_TO_RIGHT)}（偏移 47）。
    //   · 纳米蜂群工厂 = {@code gtceu:nano_forge}
    //     出处：{@code gtlcore-1.2.3.2.jar} → {@code assets/gtceu/lang/zh_cn.json} 的
    //     {@code "gtceu.nano_forge": "纳米蜂群工厂"}；
    //     注册字节码：{@code org.gtlcore.gtlcore.common.data.GTLRecipeTypes.<clinit>}
    //     偏移 3294-3344 ⇒ {@code register("nano_forge","multiblock")}
    //       ＋ {@code setMaxIOSize(6, 1, 3, 0)}（偏移 3307-3312）＋ {@code setEUIO(IO.IN)}（偏移 3318）
    //       ＋ {@code setProgressBar(PROGRESS_BAR_ARROW, LEFT_TO_RIGHT)}（偏移 3327）。
    //
    // 🔴 IO 上限的依据 = **模板类型自己的 setMaxIOSize 逐字照抄**（不是按配方用量猜的）：
    //   光子晶阵蚀刻的配方实际最多用 3 物品入 / 1 物品出 / 1 流体入 / 0 流体出 ⇒ (3,1,1,0) 正好贴合；
    //   纳米蜂群工厂实际最多 6 物品入 / 1 物品出 / 3 流体入 / 0 流体出 ⇒ (6,1,3,0) 正好贴合。
    //   ⇒ 新类型沿用同一组四元组，**不会有任何一条配方因槽位不足而写不进去**。
    //
    // ⚠️ 两处**故意**偏离模板（与本工程既有惯例一致，不是漏抄）：
    //   ① 链尾 {@code setSound} 一律省略（用户 2026-09-22 裁决：gtladditions 的 setSound 不恢复）；
    //   ② nano_forge 的 {@code addDataInfo(nano_forge_tier)} 省略 —— 那是给「1/2/3 阶纳米锻炉」
    //      三个方块显示"纳米锻炉等级：N"用的；新类型挂在原初模块上，那条 tooltip 对它不成立。
    //      配方里的 {@code nano_forge_tier} 数据字段**照抄保留**（见配方侧），只是不挂展示层。
    // ═══════════════════════════════════════════════════════════════════════════════════════════

    /** 🆕 原初激光蚀刻 —— 挂「原初世线蚀刻核心」；配方 = 光子晶阵蚀刻全部配方，透镜→电路。 */
    public static GTRecipeType PRIMORDIAL_LASER_ETCHING;

    /** 🆕 原初蜂群铸造 —— 挂「原初量子扭曲矩阵」；配方 = 纳米蜂群工厂全部配方，透镜→电路。 */
    public static GTRecipeType PRIMORDIAL_SWARM_CASTING;

    /**
     * 🆕 原初物质定型 —— 挂「原初临界加工模块」；
     * 配方 = 压模器 {@code gtceu:extruder} <b>1344</b> 条 ＋ 流体固化器
     * {@code gtceu:fluid_solidifier} <b>1113</b> 条（合计 <b>2457</b>），
     * <b>模头/模具 → 编程电路</b>（33 种 → 电路 0..32，实测用到 31 个、8 与 10 空着）。
     *
     * <p>🔴 <b>v3 · 2026-10-01 换源（本轮）</b>：v1/v2 把「压模器」错认成了
     * {@code gtceu:forming_press} —— 那个 id 的中文名其实是<b>冲压机床</b>，
     * 逐字取自 {@code assets/gtceu/lang/zh_cn.json}：{@code gtceu.extruder} = 「压模器」、
     * {@code gtceu.forming_press} = 「冲压机床」、{@code gtceu.fluid_solidifier} = 「流体固化器」。
     * ⇒ <b>真正的压模器 = {@code gtceu:extruder}</b>。换源后两台来源机器的配方<b>全取</b>
     * （{@code extruder} 1344 ＋ {@code fluid_solidifier} 1113 = <b>2457</b>），
     * <b>剔除 0 条</b> —— 这两台机器里本来就没有「产出模具/模头」的配方。
     *
     * <p>📜 <b>历史（v2 · 2026-10-01 用户拍板；口径已被 v3 取代，只作过程留档）</b>：
     * 用户原话「可以删除那制作19个模头的配方，这样电路有冗余」。
     * 那一版建立在「压模器 = 冲压机床」这个<b>错认</b>上：冲压机床原有 92 条，
     * 其中 <b>19 条是「产出 {@code *_extruder_mold}」的做模头配方</b>
     * （{@code gtceu:copy_shape_*_extruder_mold}）⇒ 92 → <b>73</b>；
     * 需要电路的模具/模头 33 → <b>15</b> 种 ⇒ 电路只用 <b>0..14</b>。
     * ⚠️ v3 换源之后这些数字<b>不再适用于本类型</b>（冲压机床的原类型照旧保留它自己那些配方），
     * <b>不要再引用 92 / 73 / 15 / 0..14</b>；本类型的现行口径以上面那段与
     * {@link #PRIMORDIAL_MATTER_FORMING_DECLARED_RECIPES} 为准。
     *
     * <p>🔴 <b>2026-10-01 用户点单：配方从【数据包】迁到【KubeJS】</b>。
     * 用户原话：「配方不是应该写在kjs里面吗，你新增一个kjs文件，用命名格式，来写原初物质定型的配方」。
     * 迁完之后的现状：
     * <ul>
     *   <li><b>配方正文</b>在 {@code kubejs\server_scripts\[server_scripts]shanhai_primordial_forming.js}
     *       （2457 条，由 {@code kubejs\_generators\gen_pf_kjs.js} 生成，发射器 = {@code _pf_kjs_emit.js}）；</li>
     *   <li><b>数据包那边已整目录搬走</b>（原先的
     *       {@code shanhai-rewrite\src\main\resources\data\shanhai\recipes\primordial_forming\}
     *       2457 个 json；留档在 {@code temp\pf-migrated-datapack-backup\primordial_forming\}）
     *       ⇒ 否则游戏里会出现两份重复配方；</li>
     *   <li><b>本类这一处仍然是必需的</b>：类型本身（{@code gtceu:primordial_matter_forming}）
     *       必须在这里注册，KJS 才有 {@code event.recipes.gtceu.primordial_matter_forming(...)} 可用。
     *       「只保留类型注册、不保留配方数据」正是本次迁移的口径。</li>
     * </ul>
     * <p>历史（迁到 KJS 之前那版的说法）：GTCEu 的配方本来就是数据包配方 ——
     * {@code GTRecipeTypes.register(...)} 同时在 {@code BuiltInRegistries.RECIPE_TYPE} /
     * {@code BuiltInRegistries.RECIPE_SERIALIZER} / {@code GTRegistries.RECIPE_TYPES} 三处登记同一个 id
     * （字节码：{@code GTRecipeTypes.register} 偏移 14/26/44），所以数据包与 KJS 两条路都能落进同一个桶。
     * 本类型的 KJS 绑定能生成，先例是 {@code primordial_laser_etching} / {@code primordial_swarm_casting}
     * （见 {@code kubejs\server_scripts\[server_scripts]shanhai_lens_goodbye.js}，
     * 它们用 {@code gtr[r.type](...)} 调的就是同一套自动生成的绑定）。
     */
    public static GTRecipeType PRIMORDIAL_MATTER_FORMING;

    /**
     * 🆕 2026-10-03 新增第 45 条：<b>原初山海调试</b>（{@code gtceu:primordial_debug}）。
     *
     * <p>用户点单（逐字）：
     * <pre>
     * 我还需要一条测试配方用来测试我们做的这个，你就新增一个配方种类叫原初山海调试，
     * 然后就给原初山海调试模块这个机器加，然后里面分别添加一个原石变成各种矿物
     * （每个配方都要加上不同的编程电路），然后其中添加各种条件，也添加一些 2-3 个的组合条件
     * </pre>
     * <p>补充（逐字）：「各种矿物你随便，反正测试用的，各种条件是都要上的，而且还需要上组合条件」。
     *
     * <p>🔴 <b>它没有上游原型</b>——不是照抄 {@code DShanhaiRecipeTypes.java} 的任何一条。
     * 所以 IO 上限是<b>按 27 条测试配方的真实需要</b>定的，不是抄来的：
     * 每条 = 2× 物品输入（1× 原石 ＋ 1× 编程电路 {@code .circuit(n)}）＋ 1× 物品输出
     * ⇒ 至少 (2, 1, 0, 0)；这里取 <b>(6, 6, 2, 2)</b> 留余量，
     * 流体侧本批 27 条一条都没用，留 2/2 是为了将来往这个类型上加条件实验时不必再改 Java。
     *
     * <p>挂载点 = {@code shanhai:primordial_debug_module}（原初山海调试模块，见
     * {@code ModuleRegistry#RECIPE_DEBUG_MODULE} 与 {@code buildDebugModuleRecipeTypes()}）。
     * <p>配方正文 = {@code kubejs\server_scripts\[server_scripts]shanhai_debug_test_recipes.js}（27 条，
     * 2026-10-03 本代理新建；分组 A 6 无条件 ／ B 7 单条件 ／ B2 2 超净间另两档 ／ C 6 两两组合 ／ D 6 三条件）。
     * <p>中文名（lang 键 = {@code gtceu.primordial_debug}，<b>不是</b> {@code gtceu.recipe_type.primordial_debug}）
     * 见 {@code assets/shanhai/lang/zh_cn.json} 与 {@code en_us.json}。
     */
    public static GTRecipeType PRIMORDIAL_DEBUG;

    /**
     * 🆕 两条新类型的【配方条数声明值】—— 只用于**由 id 可 grep 的证据行**与 KJS 侧对账。
     *
     * <p>🔴 为什么是常量而不是这里现算：本方法是**配方类型注册期**（{@code GTCEuAPI.RegisterEvent}），
     * 而配方是**之后**才从 datapack / KubeJS 载入的 ⇒ Java 在注册期**数不出**真实条数，
     * 硬要数就得挂一个很晚的监听器（收益为零、风险不小）。
     * <p>判据在 KJS 侧（{@code temp\lens-goodbye\shanhai_lens_goodbye.js}）：
     * {@code [SHANHAI-NEWTYPE] … ok=N failed=M declared=K}。**K 必须等于这里的常量**，
     * 不等就说明"某一侧改了而另一侧没跟上"。条数是**从 export 现算出来的**（不是估的），
     * 出处：{@code temp\lens-goodbye\plan.json} ← {@code build_plan.js} ← kubejs export 快照。
     *
     * <p>🆕 2026-09-30 第二次拍板后更新：用户原话「把那29条也分配进新配方」
     * ⇒ 257→<b>283</b>（光子晶阵蚀刻 257 ＋ 透镜再见里原本没有等价物的 26 条），
     *    25→<b>28</b>（纳米蜂群工厂 25 ＋ 那 3 条 nano 版）。
     */
    public static final int LASER_ETCH_DECLARED_RECIPES = 283;

    /** 见 {@link #PRIMORDIAL_MATTER_FORMING_DECLARED_RECIPES}。 */
    public static final int SWARM_CAST_DECLARED_RECIPES = 28;

    /**
     * 🆕 原初物质定型的【配方条数声明值】= <b>2460</b>
     * （v3 生成批 2457 ＋ 2026-10-03 手工追加 3，见下）。
     *
     * <p>出处 = <b>KJS 文件里的配方数据行数</b>：
     * {@code kubejs\server_scripts\[server_scripts]shanhai_primordial_forming.js}
     * 共 <b>2460</b> 行 = 生成的 <b>2457</b>（构成：压模器 {@code gtceu:extruder} <b>1344</b> ＋
     * 流体固化器 {@code gtceu:fluid_solidifier} <b>1113</b>，剔除 0 条；来源分布取自
     * {@code temp\pf-fix\manifest-v3.tsv} 的 2457 行）
     * ＋ <b>3 条手工追加</b>（2026-10-03 用户点单：方钠石 / 青金石 / 蓝金石 的【粉 → 板】，
     * 1:1、LV（EUt 32）、3s（60 tick）、编程电路 8；写在同一个 KJS 文件的 PF_ROWS 数组末尾，
     * 带醒目标注。为什么必须是同一个文件：下面的对账器只数【那一个文件】的行数）。
     *
     * <p>🔴 <b>离线对账器</b>（不需要跑 MC）：{@code node tools\check-pf-declared-vs-disk.mjs}
     * —— 它数 KJS 数据行数、再读本常量，两者必须相等；并另判一条「旧数据包里必须 0 个 json」。
     * 它就是「声明值又过时了 / 数据包没搬干净」这两件事的下一次自动报警。
     * <p>⚠️ <b>重跑生成器会冲掉那 3 条</b>：{@code gen_pf_kjs.js} 会把整个 KJS 文件按 2457 行重写
     * ⇒ 届时本常量（2460）与对账器、以及下面那个运行期探针会立刻报红 —— 不会静默。</p>
     * <p>📜 历史：v1 / v2 的声明值<b>都已过时</b>（v3 换源的原因见
     * {@link #PRIMORDIAL_MATTER_FORMING} 的字段注释），此处<b>不再写它们的数字</b>——
     * 写了就会有人照抄。
     * <p>判据在【运行期探针】{@link PrimordialFormingRecipeProbe}：
     * 它会现查配方表并打出 {@code [SHANHAI-PFORM] 原初物质定型 现查=… 期望=…}，
     * 与这里不等就是"某一侧改了而另一侧没跟上"。
     */
    public static final int PRIMORDIAL_MATTER_FORMING_DECLARED_RECIPES = 2460;

    /**
     * 真类型条数（不含 36 条显示类型）。fail-fast 用。
     *
     * <p>🔴 2026-09-26：<b>16 → 40</b>（用户点单"40 条"）；<b>同日再 40 → 41</b>
     * （用户点单新增「原初物质解构」{@code primordial_matter_deconstruction}）。
     * <b>2026-09-30：41 → 43</b>（用户点单新增「原初激光蚀刻」＋「原初蜂群铸造」两条）。
     * <b>2026-10-01：43 → 44</b>（用户点单新增「原初物质定型」{@code primordial_matter_forming}）。
     * <b>2026-10-03：44 → 45</b>（用户点单新增「原初山海调试」{@code primordial_debug}；见
     * {@link #PRIMORDIAL_DEBUG} 的字段注释。⚠️ 本轮任务书原写「41 → 42」，那是**过时口径**——
     * 41 已经在 2026-09-30 变成 43、2026-10-01 变成 44，本轮的真值是 44 → 45）。
     * 这个数字同时被 {@link #countMissingReal()} 与 {@code ShanhaiRegistry#verifyRecipeTypesRegistered}
     * 使用，<b>改类型数量必须同步改这里与那个数组</b>，否则 fail-fast 只查一部分、其余静默缺失。
     */
    public static final int REAL_TYPE_COUNT = 45;

    /**
     * 幂等闸门。与 {@code ShanhaiMachines.INITIALIZED} / {@code ModuleRegistry} 同款写法。
     *
     * <p>🔴 这里<b>不是</b>「可有可无的防御」：{@code GTRecipeTypes.register} 写的是
     * {@code ForgeRegistries.RECIPE_TYPES} 那类注册表，<b>同一个 id 注册两次会抛（键重复）</b>。
     * 而 GTCEu 的 {@code RegisterEvent} 在特定情况下可能被反复派发 ⇒ 必须自锁。
     */
    private static final AtomicBoolean INITIALIZED = new AtomicBoolean(false);

    // ═══════════════════════════════════════════════════════════════════════════════════════════
    // 🔴 2026-10-01 · 「全部山海配方类型」的【唯一真源】
    //
    // 用户原话（逐字）：
    //   「还有那个 jei 的物质模块槽显示，你要不然把列表改成【全部山海配方类型】吧，
    //     这样是不是更方便一些，以后不用补了」
    //
    // 🔴 起因（当天真出的 bug）：JEI 的物质模块催化剂展示槽原来是**手工白名单**——
    //   `installModuleCatalystSlotUi()` 里一张 `new GTRecipeType[]{…}` 数组，2026-09-30 手工列举 8 项，
    //   漏了 `worldline_cutting`（原初世线切割）⇒ 用户实测回报「这个配方没有显示这个」。
    //   **根因不是"漏写一个"，而是"存在一份可以漏写的手工清单"。**
    //
    // 🔴 本表就是那份清单的**替代品**，而且不是"又抄一份更长的清单"：
    //   它是**注册时自动登记的**——下面唯一的注册入口 {@link #register(String, String, RecipeType[])}
    //   在调用 `GTRecipeTypes.register(...)` 的**同一行**把返回值塞进来。
    //   ⇒ 以后新增一个配方类型 = 照旧写一句 `X = register("id", "multiblock")`，
    //     **本列表自动多一条，JEI 自动挂上，不需要改这个文件里的任何数组**。
    //   ⇒ 判据（可机器验）：`REGISTERED_TYPES` 与"实际注册成功的类型"恒等，且条数 == {@link #REAL_TYPE_COUNT}。
    // ═══════════════════════════════════════════════════════════════════════════════════════════

    /** 注册期自动登记的全部山海配方类型（顺序 = 注册顺序）。见上方长注释。 */
    private static final List<GTRecipeType> REGISTERED_TYPES = new ArrayList<>();

    private ShanhaiRecipeTypes() {}

    /**
     * 🔴 <b>【唯一】的配方类型注册入口</b> —— 注册 + 当场登记进 {@link #REGISTERED_TYPES}。
     *
     * <p>语义与 {@code GTRecipeTypes.register(name, category, proxyRecipes)} <b>逐字等价</b>
     * （参数原样转交、返回值原样返回），唯一区别是多做了自动登记。
     *
     * <p>⚠️ 新加配方类型时<b>必须</b>走这个入口（即：写 {@code X = register("id", "multiblock")}，
     * 不要再写 {@code GTRecipeTypes.register(…)}）——直接调 GTCEu 那个会绕过自动登记，
     * 结果是「JEI 展示槽漏挂」这类静默失败。该错误会被 {@link #assertTypeListsConsistent()} 当场抓住。
     */
    private static GTRecipeType register(String name, String category, RecipeType<?>... proxyRecipes) {
        final GTRecipeType type = GTRecipeTypes.register(name, category, proxyRecipes);
        REGISTERED_TYPES.add(type);
        return type;
    }

    /**
     * 全部 76 条的注册。<b>只允许</b>从 {@code GTCEuAPI.RegisterEvent<ResourceLocation, GTRecipeType>}
     * 的监听器里调（见类注释的窗口证据）；重复调用安全。
     *
     * <p>逐条对应原版行号已写在每组注释里；<b>唯一系统性偏离 = 去掉链尾的 {@code setSound}</b>（见类注释）。
     */
    public static void init() {
        if (!INITIALIZED.compareAndSet(false, true)) {
            ShanhaiMod.LOGGER.info("[SHANHAI-SPEC] ShanhaiRecipeTypes.init() 重复调用，已跳过（幂等）");
            return;
        }

        // ═══════════ 链式调用逐字照抄 DShanhaiRecipeTypes.java:236-490（唯一偏离：去掉链尾 setSound，见类注释）═══════════

        // DShanhaiRecipeTypes.java:60-69


        // :82-91
        PRIMORDIAL_POWER_GENERATOR = register("primordial_power_generator", "multiblock")
                .setMaxIOSize(2, 2, 2, 2)
                .setEUIO(IO.OUT)
                .setMaxTooltips(4)
                .setProgressBar(GuiTextures.PROGRESS_BAR_ARROW, ProgressTexture.FillDirection.LEFT_TO_RIGHT)
                .setSlotOverlay(false, false, true, GuiTextures.FLUID_SLOT)
                .setSlotOverlay(false, false, false, GuiTextures.DUST_OVERLAY)
                .setSlotOverlay(true, false, true, GuiTextures.FLUID_SLOT)
                .setSlotOverlay(true, false, false, GuiTextures.DUST_OVERLAY);



        // :111-117 —— 原版无 slotOverlay；链尾 setSound(ARC) 在 setOffsetVoltageText 之前（照抄原版顺序）
        PRIMORDIAL_STELLAR_REACTION = register("primordial_stellar_reaction", "multiblock")
                .setMaxIOSize(5, 3, 5, 3)
                .setEUIO(IO.IN)
                .setMaxTooltips(4)
                .setProgressBar(GuiTextures.PROGRESS_BAR_FUSION, ProgressTexture.FillDirection.LEFT_TO_RIGHT)
                .setSound(GTSoundEntries.ARC)
                .setOffsetVoltageText(true);

        // :119-128
        PRIMORDIAL_BIOLOGICAL_CORE = register("primordial_biological_core", "multiblock")
                .setMaxIOSize(6, 3, 3, 3)
                .setEUIO(IO.IN)
                .setMaxTooltips(4)
                .setProgressBar(GuiTextures.PROGRESS_BAR_ARROW, ProgressTexture.FillDirection.LEFT_TO_RIGHT)
                .setSlotOverlay(false, false, true, GuiTextures.FLUID_SLOT)
                .setSlotOverlay(false, false, false, GuiTextures.DUST_OVERLAY)
                .setSlotOverlay(true, false, true, GuiTextures.FLUID_SLOT)
                .setSlotOverlay(true, false, false, GuiTextures.DUST_OVERLAY);

        // :130-139
        PRIMORDIAL_MATTER_RECOMBINATION = register("primordial_matter_recombination", "multiblock")
                .setMaxIOSize(12, 3, 6, 3)
                .setEUIO(IO.IN)
                .setMaxTooltips(4)
                .setProgressBar(GuiTextures.PROGRESS_BAR_ARROW, ProgressTexture.FillDirection.LEFT_TO_RIGHT)
                .setSlotOverlay(false, false, true, GuiTextures.FLUID_SLOT)
                .setSlotOverlay(false, false, false, GuiTextures.DUST_OVERLAY)
                .setSlotOverlay(true, false, true, GuiTextures.FLUID_SLOT)
                .setSlotOverlay(true, false, false, GuiTextures.DUST_OVERLAY);

        // :141-150
        PRIMORDIAL_CAUSAL_WEAVING = register("primordial_causal_weaving", "multiblock")
                .setMaxIOSize(12, 3, 6, 3)
                .setEUIO(IO.IN)
                .setMaxTooltips(4)
                .setProgressBar(GuiTextures.PROGRESS_BAR_ARROW, ProgressTexture.FillDirection.LEFT_TO_RIGHT)
                .setSlotOverlay(false, false, true, GuiTextures.FLUID_SLOT)
                .setSlotOverlay(false, false, false, GuiTextures.DUST_OVERLAY)
                .setSlotOverlay(true, false, true, GuiTextures.FLUID_SLOT)
                .setSlotOverlay(true, false, false, GuiTextures.DUST_OVERLAY);

        // :152-161
        PRIMORDIAL_SINGULARITY_INVERSION = register("primordial_singularity_inversion", "multiblock")
                .setMaxIOSize(12, 3, 6, 3)
                .setEUIO(IO.IN)
                .setMaxTooltips(4)
                .setProgressBar(GuiTextures.PROGRESS_BAR_ARROW, ProgressTexture.FillDirection.LEFT_TO_RIGHT)
                .setSlotOverlay(false, false, true, GuiTextures.FLUID_SLOT)
                .setSlotOverlay(false, false, false, GuiTextures.DUST_OVERLAY)
                .setSlotOverlay(true, false, true, GuiTextures.FLUID_SLOT)
                .setSlotOverlay(true, false, false, GuiTextures.DUST_OVERLAY);







        // :225-234 —— 原版 overlay 顺序是 DUST,FLUID,DUST,FLUID（与其他条不同，照抄）
        // 🔴 2026-10-03 用户裁决：流体入 **1 → 2**。
        //    起因：PF 的 `shanhai:pf/taixu_dust` 实测 fluidIn = 2 ⇒ 超出原上限。
        //    本次只动【流体入】这一位；物品入 2 / 物品出 2 / 流体出 1 三个数与原版 overlay 那条
        //    `:225-234` 的照抄值**逐字不变**。
        TAIXU_SMELTING = register("taixu_smelting", "multiblock")
                .setMaxIOSize(2, 2, 2, 1)
                .setEUIO(IO.IN)
                .setMaxTooltips(4)
                .setProgressBar(GuiTextures.PROGRESS_BAR_ARROW, ProgressTexture.FillDirection.LEFT_TO_RIGHT)
                .setSlotOverlay(false, false, false, GuiTextures.DUST_OVERLAY)
                .setSlotOverlay(false, false, true, GuiTextures.FLUID_SLOT)
                .setSlotOverlay(true, false, false, GuiTextures.DUST_OVERLAY)
                .setSlotOverlay(true, false, true, GuiTextures.FLUID_SLOT);




        // :268-277
        // 🔴 2026-09-28 用户要求把「世线震荡收集」的 IO 放大到装配线那一套
        //    （任务书转述的用户原话：「扩大世线震荡收集的输入到装配线那样」）。
        //    ⇒ setMaxIOSize 由 (2, 2, 2, 2) 改成 (16, 1, 4, 0) —— 四元组整体照抄 gtceu 原生装配线。
        //    取证（2026-09-28 实测 javap -p -c）：
        //      libs\gtceu-1.20.1-1.4.4.jar!com/gregtechceu/gtceu/common/data/GTRecipeTypes.class
        //      偏移 3445-3449 = bipush 16 / iconst_1 / iconst_4 / iconst_0 → setMaxIOSize(IIII)。
        //    起因：用户新写的「世线震荡收集」样板（PF.txt no=68）要 9 个物品输入 + 1 个流体输入，
        //      旧上限 (2,2,2,2) 装不下（物品输入缺 7 格）。
        // 🔴 2026-09-29 用户订正：**只动输入**，输出还原成原来的 2 / 2
        //    ⇒ setMaxIOSize = **(16, 2, 4, 2)**：物品入 16、流体入 4 保持不变；物品出 1→2、流体出 0→2。
        //    与 2026-09-28 那版的差只有"输出"两个数（1→2、0→2），"输入"两个数一字未动。
        //    ⚠️ 只改这四个实参；下面 setEUIO / setMaxTooltips / setProgressBar / setSlotOverlay 一行没动。
        WORLDLINE_OSCILLATION_COLLECTION = register("worldline_oscillation_collection", "multiblock")
                .setMaxIOSize(16, 2, 4, 2)
                .setEUIO(IO.IN)
                .setMaxTooltips(4)
                .setProgressBar(GuiTextures.PROGRESS_BAR_ARROW, ProgressTexture.FillDirection.LEFT_TO_RIGHT)
                .setSlotOverlay(false, false, true, GuiTextures.FLUID_SLOT)
                .setSlotOverlay(false, false, false, GuiTextures.DUST_OVERLAY)
                .setSlotOverlay(true, false, true, GuiTextures.FLUID_SLOT)
                .setSlotOverlay(true, false, false, GuiTextures.DUST_OVERLAY);

        // :279-288
        INTERSTELLAR_MATTER_ABSORPTION = register("interstellar_matter_absorption", "multiblock")
                .setMaxIOSize(2, 2, 2, 2)
                .setEUIO(IO.IN)
                .setMaxTooltips(4)
                .setProgressBar(GuiTextures.PROGRESS_BAR_ARROW, ProgressTexture.FillDirection.LEFT_TO_RIGHT)
                .setSlotOverlay(false, false, true, GuiTextures.FLUID_SLOT)
                .setSlotOverlay(false, false, false, GuiTextures.DUST_OVERLAY)
                .setSlotOverlay(true, false, true, GuiTextures.FLUID_SLOT)
                .setSlotOverlay(true, false, false, GuiTextures.DUST_OVERLAY);

        // :290-298 —— 3 条 slotOverlay
        MATTER_FLOW_CONDENSATION = register("matter_flow_condensation", "multiblock")
                .setMaxIOSize(4, 2, 2, 2)
                .setEUIO(IO.IN)
                .setMaxTooltips(4)
                .setProgressBar(GuiTextures.PROGRESS_BAR_ARROW, ProgressTexture.FillDirection.LEFT_TO_RIGHT)
                .setSlotOverlay(false, false, true, GuiTextures.FLUID_SLOT)
                .setSlotOverlay(false, false, false, GuiTextures.DUST_OVERLAY)
                .setSlotOverlay(true, false, false, GuiTextures.DUST_OVERLAY);

        // :300-309
        PRIMORDIAL_ENERGY_ABSORPTION = register("primordial_energy_absorption", "multiblock")
                .setMaxIOSize(1, 2, 2, 2)
                .setEUIO(IO.IN)
                .setMaxTooltips(4)
                .setProgressBar(GuiTextures.PROGRESS_BAR_ARROW, ProgressTexture.FillDirection.LEFT_TO_RIGHT)
                .setSlotOverlay(false, false, true, GuiTextures.FLUID_SLOT)
                .setSlotOverlay(false, false, false, GuiTextures.DUST_OVERLAY)
                .setSlotOverlay(true, false, true, GuiTextures.FLUID_SLOT)
                .setSlotOverlay(true, false, false, GuiTextures.DUST_OVERLAY);

        // :311-320
        // 🔴 2026-09-26 用户裁决（原话逐字）：「photon_separation 的 setMaxIOSize(2, 4, 2, 2) ⇒ (4, 10, 2, 2)」
        //    —— 物品入 2 ⇒ 4、物品出 4 ⇒ 10，**流体那 2/2 不动**。
        //    起因：3 条星门配方（shanhai:pf/photon_2、shanhai:pf/electron、shanhai:pf/photon_rainbow）
        //    的物品输入要 3 格（1~2 个真物品 + notConsumable(力场发生器) + .circuit(1)），旧上限只有 2 ⇒ 溢出。
        //    放宽到 4 后这 3 条装得下（最坏 4 格，留 1 格余量）；物品出放宽到 10 是同一句裁决里的配套。
        //    ⚠️ 改的是【注册期上限】，本次【没有】改任何配方；四条实参位置 = (物品入, 物品出, 流体入, 流体出)。
        PHOTON_SEPARATION = register("photon_separation", "multiblock")
                .setMaxIOSize(4, 10, 2, 2)
                .setEUIO(IO.IN)
                .setMaxTooltips(4)
                .setProgressBar(GuiTextures.PROGRESS_BAR_ARROW, ProgressTexture.FillDirection.LEFT_TO_RIGHT)
                .setSlotOverlay(false, false, true, GuiTextures.FLUID_SLOT)
                .setSlotOverlay(false, false, false, GuiTextures.DUST_OVERLAY)
                .setSlotOverlay(true, false, true, GuiTextures.FLUID_SLOT)
                .setSlotOverlay(true, false, false, GuiTextures.DUST_OVERLAY);

        // :322-331 —— 有 setHasResearchSlot(true)；3 条 slotOverlay
        //
        // 🔴 2026-09-25 用户裁决改 IO。用户原话逐字：「可以都补一下，然后物质模块铸造就这样改，
        //    我们不需要那么多的流体输入和任意的流体输出」
        //    ⇒ setMaxIOSize 由 (15,6,6,6) 改成 (17,1,4,0)。
        //    四个数 = 该类型的专属界面模板真正画出来的槽位数
        //    （assets/gtceu/ui/recipe_type/matter_module_casting.rtui，154×80）：
        //        item_in  = 17  （id item_in_0 … item_in_16；其中 item_in_16 落在 x=130 那一格）
        //        item_out = 1   （模板里只有 item_out_0）
        //        fluid_in = 4   （fluid_in_0 … fluid_in_3，占 x=93 一整列）
        //        fluid_out= 0   （模板里【没有任何】fluid_out_* widget）
        //    数法不是眼看，是 GTCEu 自己的匹配规则：GTRecipeTypeUI 用正则
        //    "^<cap>_<io>_[0-9]+$" 找槽位，而 RecipeCapability.slotName(IO) = "%s_%s"
        //    （javap -c 实证）⇒ 只有前缀 item_in_/item_out_/fluid_in_/fluid_out_ 的 widget 算数。
        //    ⚠️ item_in_16 的 id 前缀是 item_in（不是 item_out），所以它在 UI 里被当成
        //       第 17 个【物品输入】槽，不是第二个输出槽。
        //    ⇒ 这四格同时就是【现存 25 条 matter_module_casting 配方的实测最大值】：
        //      local\kubejs\export\{recipes,added_recipes}\dishanhai\matter_module_casting\
        //      （各 25 条，两处一致）实测 max(itemIn)=17 / max(itemOut)=1 / max(fluidIn)=4 / max(fluidOut)=0
        //      ⇒ 改成 (17,1,4,0) 后 25 条**全部装得下，一条都不受影响**（离线逐条清点过）。
        //    📌 顺带：旧的 (15,6,6,6) 反而装不下 matter_module_casting_create_mk（17 个物品输入）
        //      —— 这是旧口径下就存在的既有问题，本次一并修好。
        //      （原版私货 DShanhaiRecipeTypes 里也是 (15,6,6,6)，javap 实证 ⇒ 不是我方引入。）
        MATTER_MODULE_CASTING = register("matter_module_casting", "multiblock")
                .setMaxIOSize(17, 1, 4, 0)
                .setEUIO(IO.IN)
                .setMaxTooltips(4)
                .setHasResearchSlot(true)
                .setProgressBar(GuiTextures.PROGRESS_BAR_ARROW, ProgressTexture.FillDirection.LEFT_TO_RIGHT)
                .setSlotOverlay(false, false, true, GuiTextures.FLUID_SLOT)
                .setSlotOverlay(false, false, false, GuiTextures.DUST_OVERLAY)
                .setSlotOverlay(true, false, false, GuiTextures.DUST_OVERLAY);

        // :333-341 —— 3 条 slotOverlay
        // 🔴 2026-09-26 用户点单（原话逐字）：「把物质铸造这一种类配方（输入，输出）修改为组装机这一种类」
        //    ⇒ 口径 = 把 setMaxIOSize 换成【组装机 gtceu:assembler】那一套：(4,2,2,2) → (9,1,1,0)。
        //    四个数的来源（javap 实证，本整合包【没有】改过它）：
        //      gtceu jar `GTRecipeTypes.<clinit>`：`ldc "assembler"` → `register(...)` 之后紧接着
        //      `bipush 9 / iconst_1 / iconst_1 / iconst_0 / invokevirtual setMaxIOSize:(IIII)`
        //      （偏移 268 → 285）；包内"既引用 ASSEMBLER_RECIPES 又调用 setMaxIOSize"的类只有
        //      gtlcore `RecipeModify` 一个，而它的 `init()` 对 ASSEMBLER_RECIPES 只调 `onRecipeBuild`
        //      （两次），**没有** setMaxIOSize ⇒ 组装机的有效规格就是原版那四个数。
        //    ⚠️ 用户同一句话里的「物质铸造」= 这条 matter_forging；**物质模块铸造**
        //      (matter_module_casting, 上面那段) 不在这条指令范围内，本次一个字都没动。
        //    ⚠️ 超限核对（交付报告里另有逐条清单）：现存 13 条 matter_forging 配方实测
        //      max(itemIn)=4 / max(itemOut)=1 / max(fluidIn)=2 / max(fluidOut)=1
        //      ⇒ 新规格下 **9 条超出流体限额**（6 条 fluidIn=2 > 1、4 条 fluidOut=1 > 0）。
        //      这是本改动的**已知代价**，已按要求上报，未擅自改配方、也未擅自放宽规格。
        // 🔴 2026-10-03 用户裁决（第二刀，同一句话的后续）：流体入 **1 → 2**。
        //    起因：PF 的 `shanhai:pf/gluon` / `shanhai:pf/gluon_2` 两条配方实测 fluidIn = 2，
        //      而上面 2026-09-26 那刀把流体入压到 1 ⇒ 生成器每轮打印 `slot overflows`
        //      的 3 条里占 2 条（第三类是 taixu_smelting）。本次只动【流体入】这一位，
        //      物品入 9 / 物品出 1 / 流体出 0 三个数与 2026-09-26 的裁决**逐字不变**。
        //    ⚠️ 上面 2026-09-26 那段留档【原文未删】—— 它是当时那刀的记录；
        //      其中"9 条超出流体限额（6 条 fluidIn=2 > 1、4 条 fluidOut=1 > 0）"里的
        //      **6 条 fluidIn 超限**已由本次放宽消掉，**4 条 fluidOut=1 > 0 仍然存在**（本轮不动流体出）。
        // 🔴 2026-10-03 用户裁决（第三刀，同一句话的后续）：新增【流体输出槽】—— 流体出 **0 → 1**。
        //    原话逐字：「给物质锻造开一个流体输出槽」
        //    起因：用户新写的 π 介子配方 `shanhai:pf/pion`（1x up_quark + 1x down_quark + 1x gluon
        //      ⇒ 1x shanhai:pion ＋ shanhai:zero_point_energy 4000 流体输出）实测
        //      `slots = {itemIn:4,itemOut:1,fluidIn:0,fluidOut:1}`，而流体出上限为 0 ⇒ **溢出**。
        //    ⇒ 目标 IO = **(9, 1, 2, 1)**；本次【只动第四位】，前三位与 2026-09-26/10-03 的裁决逐字不变。
        //    ⚠️ 上面 2026-09-26 / 2026-10-03 两段留档【原文未删】—— 它们是当时那两刀的记录；
        //      其中"4 条 fluidOut=1 > 0 仍然存在"里的 **fluidOut=1 超限已由本次放宽消掉**
        //      （现存 6 条 matter_forging 配方实测 max(fluidOut)=1 ⇒ 放宽后 0 条超出流体出限额）。
        //    ⚠️ 本类型【没有】专属 .rtui（实测名单：shanhai 侧 5 个 —— matter_module_casting /
        //      primordial_matter_deconstruction / wl_board_circuit_assembly / wl_board_wafer_etching /
        //      black_hole_event_horizon_blast；gtceu-1.20.1-1.4.4.jar 侧 4 个 —— assembly_line /
        //      forge_hammer / lathe / research_station。两处都没有 matter_forging）
        //      ⇒ 它走框架的通用版面。
        //      ⚠️「通用版面会在 fluidOut=1 时真画出一个流体输出格」本次【未实测】——
        //      这只是按框架行为的推断，已列入交付报告"残余不确定性"一节，未被当作已证事实。
        MATTER_FORGING = register("matter_forging", "multiblock")
                .setMaxIOSize(9, 1, 2, 1)
                .setEUIO(IO.IN)
                .setMaxTooltips(4)
                .setProgressBar(GuiTextures.PROGRESS_BAR_ARROW, ProgressTexture.FillDirection.LEFT_TO_RIGHT)
                .setSlotOverlay(false, false, true, GuiTextures.FLUID_SLOT)
                .setSlotOverlay(false, false, false, GuiTextures.DUST_OVERLAY)
                .setSlotOverlay(true, false, false, GuiTextures.DUST_OVERLAY);

        // ───── 2026-09-26 新增：原初物质解构（用户点单，第 41 条真类型）─────
        //
        // 🔴 写法逐字对齐【同一批】的 photon_separation（上方 :311-320）与 matter_forging（上方 :333-341）：
        //    同 category "multiblock"、同 setEUIO(IO.IN)、同 setMaxTooltips(4)、
        //    同 PROGRESS_BAR_ARROW + LEFT_TO_RIGHT、同 4 条 slotOverlay（FLUID-in / DUST-in / FLUID-out / DUST-out）、
        //    链尾同样**没有** setSound（用户 2026-09-22 裁决：gtladditions 的 setSound 不抄）。
        //
        // 🔴 setMaxIOSize(1, 103, 1, 16) 的四个数【不是估的】，是专属模板真正画出的槽位数。
        //    模板 = assets/gtceu/ui/recipe_type/primordial_matter_deconstruction.rtui（**190×114**）。
        //    ⚠️ 2026-09-26 第二次改（用户裁决「放宽上限到 16」）：fluid-out **10 ⇒ 16**。
        //       起因：`star_gate_crystal_slurry` 那条产线的输入有 **16 种流体**，10 格装不下。
        //       ⇒ .rtui 同步把 fluid_out 从 10 槽加到 16 槽（**8 列 × 2 行**），
        //          root.size 190×96 ⇒ **190×114**。两处必须同步，否则 JEI 画不满/画多余。
        //    取证方式两条（互相独立、结论一致）：
        //      ① 该文件由 black_hole_event_horizon_blast.rtui（75,413 B、root.size 226×256）
        //         裁剪改写而来 —— 范本里 item_out 的网格是 12 列 × 10 行、步长严格 18，
        //         我们保留 x=5..167 的 10 列 × y=37,55 的 2 行 = 20 格（item_out_0..19），
        //         流体出取 8 列 × y=73,91 的 2 行 = 16 格（fluid_out_0..15），
        //         另加 item_in_0 与【补造的】fluid_in_0 各 1 格。
        //      ② 用 NBT 解析器回读新文件，逐槽数 id 并对绝对坐标做断言（生成器自证，见交付报告）。
        //    ⚠️ 与 matter_module_casting 那次（改 setMaxIOSize 去贴合模板）同口径：
        //       模板画几个槽，上限就写几个。
        PRIMORDIAL_MATTER_DECONSTRUCTION = register("primordial_matter_deconstruction", "multiblock")
                .setMaxIOSize(1, 103, 1, 16)
                .setEUIO(IO.IN)
                .setMaxTooltips(4)
                .setProgressBar(GuiTextures.PROGRESS_BAR_ARROW, ProgressTexture.FillDirection.LEFT_TO_RIGHT)
                .setSlotOverlay(false, false, true, GuiTextures.FLUID_SLOT)
                .setSlotOverlay(false, false, false, GuiTextures.DUST_OVERLAY)
                .setSlotOverlay(true, false, true, GuiTextures.FLUID_SLOT)
                .setSlotOverlay(true, false, false, GuiTextures.DUST_OVERLAY);

        // :343-351 —— PROGRESS_BAR_CIRCUIT + CIRCUIT_OVERLAY
        WL_BOARD_CIRCUIT_ASSEMBLY = register("wl_board_circuit_assembly", "multiblock")
                .setMaxIOSize(9, 3, 6, 4)
                .setEUIO(IO.IN)
                .setMaxTooltips(4)
                .setProgressBar(GuiTextures.PROGRESS_BAR_CIRCUIT, ProgressTexture.FillDirection.LEFT_TO_RIGHT)
                .setSlotOverlay(false, false, true, GuiTextures.FLUID_SLOT)
                .setSlotOverlay(false, false, false, GuiTextures.CIRCUIT_OVERLAY)
                .setSlotOverlay(true, false, false, GuiTextures.CIRCUIT_OVERLAY);

        // :353-361 —— 3 条 overlay：FLUID,DUST,CIRCUIT（注意不是全 CIRCUIT）
        WL_BOARD_WAFER_ETCHING = register("wl_board_wafer_etching", "multiblock")
                .setMaxIOSize(6, 3, 4, 3)
                .setEUIO(IO.IN)
                .setMaxTooltips(4)
                .setProgressBar(GuiTextures.PROGRESS_BAR_CIRCUIT, ProgressTexture.FillDirection.LEFT_TO_RIGHT)
                .setSlotOverlay(false, false, true, GuiTextures.FLUID_SLOT)
                .setSlotOverlay(false, false, false, GuiTextures.DUST_OVERLAY)
                .setSlotOverlay(true, false, false, GuiTextures.CIRCUIT_OVERLAY);

        // ========== 世线裂解枢纽 ==========



        // ========== 原初世线切割核心 ==========






        // ========== 原初铸币工厂（原版有该机器，本工程已删；类型照原版保留）==========


        // ═══════════════════════════════════════════════════════════════════════════════════════
        // 🔴 2026-09-26 恢复的 24 段 register 链（用户点单"40 条"）
        //    原文 = 本文件 ⛔作废块（"【作废 · register 链原文】"）逐字，去掉行首注释符。
        //    每段保留 {@code // :NNN-NNN} 的【旧私货 DShanhaiRecipeTypes.java 原行号】便于对账。
        //    ⚠️ 集中放在这里（fail-fast 之前），不再插回原位置；理由见字段区那段长注释。
        // ═══════════════════════════════════════════════════════════════════════════════════════

        // :485-490 —— 无 slotOverlay，setMaxTooltips(1)
        PROXY_EXECUTION = register("proxy_execution", "multiblock")
                .setMaxIOSize(0, 0, 0, 0)
                .setEUIO(IO.IN)
                .setMaxTooltips(1)
                .setProgressBar(GuiTextures.PROGRESS_BAR_ARROW, ProgressTexture.FillDirection.LEFT_TO_RIGHT);

        // :474-483
        COIN_FORGE = register("coin_forge", "multiblock")
                .setMaxIOSize(9, 6, 6, 3)
                .setEUIO(IO.IN)
                .setMaxTooltips(4)
                .setProgressBar(GuiTextures.PROGRESS_BAR_ARROW, ProgressTexture.FillDirection.LEFT_TO_RIGHT)
                .setSlotOverlay(false, false, true, GuiTextures.FLUID_SLOT)
                .setSlotOverlay(false, false, false, GuiTextures.DUST_OVERLAY)
                .setSlotOverlay(true, false, true, GuiTextures.FLUID_SLOT)
                .setSlotOverlay(true, false, false, GuiTextures.DUST_OVERLAY);

        // :453-462
        NINE_INDUSTRIAL = register("nine_industrial", "multiblock")
                .setMaxIOSize(24, 24, 12, 12)
                .setEUIO(IO.IN)
                .setMaxTooltips(4)
                .setProgressBar(GuiTextures.PROGRESS_BAR_ARROW, ProgressTexture.FillDirection.LEFT_TO_RIGHT)
                .setSlotOverlay(false, false, true, GuiTextures.FLUID_SLOT)
                .setSlotOverlay(false, false, false, GuiTextures.DUST_OVERLAY)
                .setSlotOverlay(true, false, true, GuiTextures.FLUID_SLOT)
                .setSlotOverlay(true, false, false, GuiTextures.DUST_OVERLAY);

        // :442-451 —— PROGRESS_BAR_FUSION
        BLACK_HOLE_EVENT_HORIZON_BLAST = register("black_hole_event_horizon_blast", "multiblock")
                .setMaxIOSize(3, 9, 3, 6)
                .setEUIO(IO.IN)
                .setMaxTooltips(4)
                .setProgressBar(GuiTextures.PROGRESS_BAR_FUSION, ProgressTexture.FillDirection.LEFT_TO_RIGHT)
                .setSlotOverlay(false, false, true, GuiTextures.FLUID_SLOT)
                .setSlotOverlay(false, false, false, GuiTextures.DUST_OVERLAY)
                .setSlotOverlay(true, false, true, GuiTextures.FLUID_SLOT)
                .setSlotOverlay(true, false, false, GuiTextures.DUST_OVERLAY);

        // :431-440 —— PROGRESS_BAR_COMPRESS
        BLACK_HOLE_NEUTRONIUM_COMPRESSOR = register("black_hole_neutronium_compressor", "multiblock")
                .setMaxIOSize(9, 6, 6, 5)
                .setEUIO(IO.IN)
                .setMaxTooltips(4)
                .setProgressBar(GuiTextures.PROGRESS_BAR_COMPRESS, ProgressTexture.FillDirection.LEFT_TO_RIGHT)
                .setSlotOverlay(false, false, true, GuiTextures.FLUID_SLOT)
                .setSlotOverlay(false, false, false, GuiTextures.DUST_OVERLAY)
                .setSlotOverlay(true, false, true, GuiTextures.FLUID_SLOT)
                .setSlotOverlay(true, false, false, GuiTextures.DUST_OVERLAY);

        // :420-429 —— PROGRESS_BAR_COMPRESS
        BLACK_HOLE_COMPRESSOR = register("black_hole_compressor", "multiblock")
                .setMaxIOSize(9, 6, 6, 5)
                .setEUIO(IO.IN)
                .setMaxTooltips(4)
                .setProgressBar(GuiTextures.PROGRESS_BAR_COMPRESS, ProgressTexture.FillDirection.LEFT_TO_RIGHT)
                .setSlotOverlay(false, false, true, GuiTextures.FLUID_SLOT)
                .setSlotOverlay(false, false, false, GuiTextures.DUST_OVERLAY)
                .setSlotOverlay(true, false, true, GuiTextures.FLUID_SLOT)
                .setSlotOverlay(true, false, false, GuiTextures.DUST_OVERLAY);

        // :409-418
        HIGH_DIMENSIONAL_FRAGMENT_CUTTING = register("high_dimensional_fragment_cutting", "multiblock")
                .setMaxIOSize(4, 9, 2, 4)
                .setEUIO(IO.IN)
                .setMaxTooltips(4)
                .setProgressBar(GuiTextures.PROGRESS_BAR_ARROW, ProgressTexture.FillDirection.LEFT_TO_RIGHT)
                .setSlotOverlay(false, false, true, GuiTextures.FLUID_SLOT)
                .setSlotOverlay(false, false, false, GuiTextures.DUST_OVERLAY)
                .setSlotOverlay(true, false, true, GuiTextures.FLUID_SLOT)
                .setSlotOverlay(true, false, false, GuiTextures.DUST_OVERLAY);

        // :398-407
        WORLDLINE_CUTTING = register("worldline_cutting", "multiblock")
                .setMaxIOSize(6, 6, 4, 4)
                .setEUIO(IO.IN)
                .setMaxTooltips(4)
                .setProgressBar(GuiTextures.PROGRESS_BAR_ARROW, ProgressTexture.FillDirection.LEFT_TO_RIGHT)
                .setSlotOverlay(false, false, true, GuiTextures.FLUID_SLOT)
                .setSlotOverlay(false, false, false, GuiTextures.DUST_OVERLAY)
                .setSlotOverlay(true, false, true, GuiTextures.FLUID_SLOT)
                .setSlotOverlay(true, false, false, GuiTextures.DUST_OVERLAY);

        // :386-395
        WORLDLINE_SAMPLING = register("worldline_sampling", "multiblock")
                .setMaxIOSize(3, 12, 3, 6)
                .setEUIO(IO.IN)
                .setMaxTooltips(4)
                .setProgressBar(GuiTextures.PROGRESS_BAR_ARROW, ProgressTexture.FillDirection.LEFT_TO_RIGHT)
                .setSlotOverlay(false, false, true, GuiTextures.FLUID_SLOT)
                .setSlotOverlay(false, false, false, GuiTextures.DUST_OVERLAY)
                .setSlotOverlay(true, false, true, GuiTextures.FLUID_SLOT)
                .setSlotOverlay(true, false, false, GuiTextures.DUST_OVERLAY);

        // :375-384
        WORLDLINE_MATTER_RECURRENCE = register("worldline_matter_recurrence", "multiblock")
                .setMaxIOSize(9, 6, 6, 3)
                .setEUIO(IO.IN)
                .setMaxTooltips(4)
                .setProgressBar(GuiTextures.PROGRESS_BAR_ARROW, ProgressTexture.FillDirection.LEFT_TO_RIGHT)
                .setSlotOverlay(false, false, true, GuiTextures.FLUID_SLOT)
                .setSlotOverlay(false, false, false, GuiTextures.DUST_OVERLAY)
                .setSlotOverlay(true, false, true, GuiTextures.FLUID_SLOT)
                .setSlotOverlay(true, false, false, GuiTextures.DUST_OVERLAY);

        // :364-373
        WORLDLINE_PROBABILITY_CRACKING = register("worldline_probability_cracking", "multiblock")
                .setMaxIOSize(6, 9, 4, 4)
                .setEUIO(IO.IN)
                .setMaxTooltips(4)
                .setProgressBar(GuiTextures.PROGRESS_BAR_ARROW, ProgressTexture.FillDirection.LEFT_TO_RIGHT)
                .setSlotOverlay(false, false, true, GuiTextures.FLUID_SLOT)
                .setSlotOverlay(false, false, false, GuiTextures.DUST_OVERLAY)
                .setSlotOverlay(true, false, true, GuiTextures.FLUID_SLOT)
                .setSlotOverlay(true, false, false, GuiTextures.DUST_OVERLAY);

        // :256-266 —— category "single"；全 40 条里唯一 5 条 slotOverlay（原版原文第 261/262 行是两条相同的 DUST，照抄不合并）
        PHOTON_SIPHON = register("photon_siphon", "single")
                .setMaxIOSize(4, 2, 2, 2)
                .setEUIO(IO.IN)
                .setMaxTooltips(4)
                .setProgressBar(GuiTextures.PROGRESS_BAR_ARROW, ProgressTexture.FillDirection.LEFT_TO_RIGHT)
                .setSlotOverlay(false, false, false, GuiTextures.DUST_OVERLAY)
                .setSlotOverlay(false, false, false, GuiTextures.DUST_OVERLAY)
                .setSlotOverlay(true, false, false, GuiTextures.DUST_OVERLAY)
                .setSlotOverlay(false, false, true, GuiTextures.FLUID_SLOT)
                .setSlotOverlay(true, false, true, GuiTextures.FLUID_SLOT);

        // :245-254 —— category "single"
        ZERO_POINT_CONVERSION = register("zero_point_conversion", "single")
                .setMaxIOSize(2, 2, 2, 2)
                .setEUIO(IO.IN)
                .setMaxTooltips(4)
                .setProgressBar(GuiTextures.PROGRESS_BAR_ARROW, ProgressTexture.FillDirection.LEFT_TO_RIGHT)
                .setSlotOverlay(false, false, true, GuiTextures.FLUID_SLOT)
                .setSlotOverlay(false, false, false, GuiTextures.DUST_OVERLAY)
                .setSlotOverlay(true, false, false, GuiTextures.DUST_OVERLAY)
                .setSlotOverlay(true, false, true, GuiTextures.FLUID_SLOT);

        // :236-243 —— category "single"（用户 2026-09-22 亲定中文名：原初物质凝集）
        MATTER_AGGREGATION = register("matter_aggregation", "single")
                .setMaxIOSize(2, 2, 0, 0)
                .setEUIO(IO.IN)
                .setMaxTooltips(4)
                .setProgressBar(GuiTextures.PROGRESS_BAR_ARROW, ProgressTexture.FillDirection.LEFT_TO_RIGHT)
                .setSlotOverlay(false, false, false, GuiTextures.DUST_OVERLAY)
                .setSlotOverlay(true, false, false, GuiTextures.DUST_OVERLAY);

        // :216-223
        GRAVITATIONAL_WAVE_CONSUMPTION = register("gravitational_wave_consumption", "multiblock")
                .setMaxIOSize(1, 0, 1, 0)
                .setEUIO(IO.IN)
                .setMaxTooltips(4)
                .setProgressBar(GuiTextures.PROGRESS_BAR_ARROW, ProgressTexture.FillDirection.LEFT_TO_RIGHT)
                .setSlotOverlay(false, false, true, GuiTextures.FLUID_SLOT)
                .setSlotOverlay(false, false, false, GuiTextures.DUST_OVERLAY);

        // :205-214
        TIANJIE_NAVIGATION = register("tianjie_navigation", "multiblock")
                .setMaxIOSize(6, 3, 6, 3)
                .setEUIO(IO.IN)
                .setMaxTooltips(4)
                .setProgressBar(GuiTextures.PROGRESS_BAR_ARROW, ProgressTexture.FillDirection.LEFT_TO_RIGHT)
                .setSlotOverlay(false, false, true, GuiTextures.FLUID_SLOT)
                .setSlotOverlay(false, false, false, GuiTextures.DUST_OVERLAY)
                .setSlotOverlay(true, false, true, GuiTextures.FLUID_SLOT)
                .setSlotOverlay(true, false, false, GuiTextures.DUST_OVERLAY);

        // :194-203
        NEBULA_SIPHONING = register("nebula_siphoning", "multiblock")
                .setMaxIOSize(6, 3, 6, 3)
                .setEUIO(IO.IN)
                .setMaxTooltips(4)
                .setProgressBar(GuiTextures.PROGRESS_BAR_ARROW, ProgressTexture.FillDirection.LEFT_TO_RIGHT)
                .setSlotOverlay(false, false, true, GuiTextures.FLUID_SLOT)
                .setSlotOverlay(false, false, false, GuiTextures.DUST_OVERLAY)
                .setSlotOverlay(true, false, true, GuiTextures.FLUID_SLOT)
                .setSlotOverlay(true, false, false, GuiTextures.DUST_OVERLAY);

        // :183-192 —— 全 40 条里唯一的 EUIO = IO.BOTH
        CHAOS_CRAFTING = register("chaos_crafting", "multiblock")
                .setMaxIOSize(24, 24, 12, 12)
                .setEUIO(IO.BOTH)
                .setMaxTooltips(4)
                .setProgressBar(GuiTextures.PROGRESS_BAR_ARROW, ProgressTexture.FillDirection.LEFT_TO_RIGHT)
                .setSlotOverlay(false, false, true, GuiTextures.FLUID_SLOT)
                .setSlotOverlay(false, false, false, GuiTextures.DUST_OVERLAY)
                .setSlotOverlay(true, false, true, GuiTextures.FLUID_SLOT)
                .setSlotOverlay(true, false, false, GuiTextures.DUST_OVERLAY);

        // :174-181 —— category 是裸字符串 "single"
        SEVENTY_TWO_CHANGES = register("seventy_two_changes", "single")
                .setMaxIOSize(1, 1, 0, 0)
                .setEUIO(IO.IN)
                .setMaxTooltips(4)
                .setProgressBar(GuiTextures.PROGRESS_BAR_ARROW, ProgressTexture.FillDirection.LEFT_TO_RIGHT)
                .setSlotOverlay(false, false, false, GuiTextures.DUST_OVERLAY)
                .setSlotOverlay(true, false, false, GuiTextures.DUST_OVERLAY);

        // :163-172
        GRAVITATIONAL_WAVE_PRODUCTION = register("gravitational_wave_production", "multiblock")
                .setMaxIOSize(2, 2, 2, 2)
                .setEUIO(IO.IN)
                .setMaxTooltips(4)
                .setProgressBar(GuiTextures.PROGRESS_BAR_ARROW, ProgressTexture.FillDirection.LEFT_TO_RIGHT)
                .setSlotOverlay(false, false, true, GuiTextures.FLUID_SLOT)
                .setSlotOverlay(false, false, false, GuiTextures.DUST_OVERLAY)
                .setSlotOverlay(true, false, true, GuiTextures.FLUID_SLOT)
                .setSlotOverlay(true, false, false, GuiTextures.DUST_OVERLAY);

        // :102-109 —— 链尾 setSound(GTSoundEntries.ARC)：GTCEu 自己的 API，按队长 2026-09-22 裁决【保留抄写】
        PRIMORDIAL_MYRIAD_ASCENSION_TIER_1 = register("primordial_myriad_ascension_tier_1", "multiblock")
                .setMaxIOSize(4, 0, 4, 0)
                .setEUIO(IO.IN)
                .setMaxTooltips(4)
                .setProgressBar(GuiTextures.PROGRESS_BAR_ARROW, ProgressTexture.FillDirection.LEFT_TO_RIGHT)
                .setSlotOverlay(false, false, true, GuiTextures.FLUID_SLOT)
                .setSlotOverlay(false, false, false, GuiTextures.DUST_OVERLAY)
                .setSound(GTSoundEntries.ARC);

        // :93-100
        PRIMORDIAL_MYRIAD_ASCENSION_TIER_2 = register("primordial_myriad_ascension_tier_2", "multiblock")
                .setMaxIOSize(4, 0, 4, 0)
                .setEUIO(IO.IN)
                .setMaxTooltips(4)
                .setProgressBar(GuiTextures.PROGRESS_BAR_ARROW, ProgressTexture.FillDirection.LEFT_TO_RIGHT)
                .setSlotOverlay(false, false, true, GuiTextures.FLUID_SLOT)
                .setSlotOverlay(false, false, false, GuiTextures.DUST_OVERLAY)
                .setSound(GTSoundEntries.ARC);

        // :71-80
        KU_MING_YUAN_YANG = register("kmyy", "multiblock")
                .setMaxIOSize(2, 1, 0, 0)
                .setEUIO(IO.OUT)
                .setMaxTooltips(4)
                .setProgressBar(GuiTextures.PROGRESS_BAR_ARROW, ProgressTexture.FillDirection.LEFT_TO_RIGHT)
                .setSlotOverlay(false, false, true, GuiTextures.FLUID_SLOT)
                .setSlotOverlay(false, false, false, GuiTextures.DUST_OVERLAY)
                .setSlotOverlay(true, false, true, GuiTextures.FLUID_SLOT)
                .setSlotOverlay(true, false, false, GuiTextures.DUST_OVERLAY);

        // :60-69（作废块原文漏了行号标记，2026-09-26 按上游补上）
        SPACETIME_DISTORTION = register("spacetime_distortion", "multiblock")
                .setMaxIOSize(9, 6, 6, 5)
                .setEUIO(IO.IN)
                .setMaxTooltips(4)
                .setProgressBar(GuiTextures.PROGRESS_BAR_ARROW, ProgressTexture.FillDirection.LEFT_TO_RIGHT)
                .setSlotOverlay(false, false, true, GuiTextures.FLUID_SLOT)
                .setSlotOverlay(false, false, false, GuiTextures.DUST_OVERLAY)
                .setSlotOverlay(true, false, true, GuiTextures.FLUID_SLOT)
                .setSlotOverlay(true, false, false, GuiTextures.DUST_OVERLAY);

        // ═════════════ 2026-09-30 新增第 42／43 条（用户点单；见字段区那段长注释）═════════════
        // 逐字照抄模板类型的槽位规格（IO 上限的依据 = 模板自己的 setMaxIOSize，字节码见字段区注释）。
        // 唯一偏离 = 去掉链尾 setSound（用户 2026-09-22 裁决）+ 不挂 nano_forge 的 addDataInfo。

        // :模板 gtceu:photon_matrix_etch（gtladditions GTLAddRecipesTypes 偏移 11-53）→ setMaxIOSize(3, 1, 1, 0)
        PRIMORDIAL_LASER_ETCHING = register("primordial_laser_etching", "multiblock")
                .setMaxIOSize(3, 1, 1, 0)
                .setEUIO(IO.IN)
                .setMaxTooltips(4)
                .setProgressBar(GuiTextures.PROGRESS_BAR_ARROW, ProgressTexture.FillDirection.LEFT_TO_RIGHT);

        // :模板 gtceu:nano_forge（gtlcore GTLRecipeTypes 偏移 3294-3327）→ setMaxIOSize(6, 1, 3, 0)
        PRIMORDIAL_SWARM_CASTING = register("primordial_swarm_casting", "multiblock")
                .setMaxIOSize(6, 1, 3, 0)
                .setEUIO(IO.IN)
                .setMaxTooltips(4)
                .setProgressBar(GuiTextures.PROGRESS_BAR_ARROW, ProgressTexture.FillDirection.LEFT_TO_RIGHT);

        // ═════════════ 2026-10-01 新增第 44 条（用户点单；见字段区那段长注释）═════════════
        // 它同时装【两个】来源机器的配方 ⇒ IO 上限按【逐项取两个来源的最大值】定，不是抄某一个：
        //   冲压机床（旧误认成"压模器"的那台）gtceu:forming_press   setMaxIOSize(6, 1, 0, 0)（bytecode 偏移 1719-1723）
        //   流体固化器                         gtceu:fluid_solidifier setMaxIOSize(1, 1, 1, 0)（bytecode 偏移 1600-1603）
        //   ⇒ 逐项 max = (6, 1, 1, 0)
        //   ⚠️ 真正的压模器 gtceu:extruder 自己的 setMaxIOSize 本轮【未取证】（未证实，不许当成已知值）。
        // 并与【实际数据】独立核过（离线现算：node temp\pf-const-sync\measure.mjs）：
        //   2457 条里 物品入最多 2 / 物品出最多 1 / 流体入最多 1 / 流体出最多 0
        //   ⇒ (6, 1, 1, 0) 对全部 2457 条【够用】（物品入的 6 现在是富余，不再是"正好贴合"）。
        //   （v1/v2 的旧条数同值；那些口径已过时，此处不再引用具体数字。）
        // 进度条取 PROGRESS_BAR_ARROW（流体固化器用的那条；2457 条里 1113 条来自它）；
        // 旧注释说"压模器用的是 PROGRESS_BAR_COMPRESS"—— 那其实是【冲压机床】的属性，
        // 真正的压模器 gtceu:extruder 用哪条进度条本轮未复核（未证实）；
        // 一个类型只能有一条进度条，这里是有意识的选择，不是漏抄。
        // 🔴 刻意【不】照抄冲压机床 gtceu:forming_press 的 addCustomRecipeLogic(new FormingPressLogic())：
        //    字节码实证那个逻辑内部【硬编码】GTRecipeTypes.FORMING_PRESS_RECIPES（偏移 134），
        //    它服务的是"给命名模具改名"的 GUI 功能，不是这 2457 条里的任何一条 ⇒ 挂上去也不会生效。
        //    （v1/v2 把 FormingPressLogic 说成"压模器的逻辑"—— 它属于冲压机床，不是压模器。）
        //    ⚠️ gtceu:extruder 有没有自己的 addCustomRecipeLogic，本轮未取证（未证实）。
        PRIMORDIAL_MATTER_FORMING = register("primordial_matter_forming", "multiblock")
                .setMaxIOSize(6, 1, 1, 0)
                .setEUIO(IO.IN)
                .setMaxTooltips(4)
                .setProgressBar(GuiTextures.PROGRESS_BAR_ARROW, ProgressTexture.FillDirection.LEFT_TO_RIGHT);

        // ═════════════ 2026-10-03 新增第 45 条（用户点单「原初山海调试」；见字段区那段长注释）═════════════
        // 🔴 本条【无上游原型】⇒ IO 上限不是抄来的，是按 27 条测试配方的真实需要定的：
        //    每条 = 1× minecraft:cobblestone ＋ 1× 编程电路（KJS 的 .circuit(n)，落 chance=0 的电路输入）
        //         ＋ 1× 矿物输出
        //    ⇒ 至少 (2, 1, 0, 0)；取 (6, 6, 2, 2) 留余量，流体侧留 2/2 便于后续条件实验。
        //    进度条照同批新类型（第 42..44 条）的惯例取 PROGRESS_BAR_ARROW。
        PRIMORDIAL_DEBUG = register("primordial_debug", "multiblock")
                .setMaxIOSize(6, 6, 2, 2)
                .setEUIO(IO.IN)
                .setMaxTooltips(4)
                .setProgressBar(GuiTextures.PROGRESS_BAR_ARROW, ProgressTexture.FillDirection.LEFT_TO_RIGHT);

        // ───────── fail-fast（就地）：任何一条静默没生效，就在这里响亮地失败 ─────────
        int realMissing = countMissingReal();
        if (realMissing > 0) {
            throw new IllegalStateException("[SHANHAI] 配方类型注册不完整：真类型缺 " + realMissing + " / "
                    + REAL_TYPE_COUNT + "。抽查三句柄＝primordial_matter_recombination="
                    + PRIMORDIAL_MATTER_RECOMBINATION
                    + " / primordial_stellar_reaction=" + PRIMORDIAL_STELLAR_REACTION
                    + " / taixu_smelting=" + TAIXU_SMELTING);
        }

        // ───────── 🔴 2026-10-01 两条清单互钉（就地）：见 assertTypeListsConsistent() ─────────
        // 为什么放在这里：它必须在**全部注册之后**、且在 installModuleCatalystSlotUi() 之前，
        // 因为后者要拿 REGISTERED_TYPES 当"全部山海配方类型"的真源。
        assertTypeListsConsistent();

        ShanhaiMod.LOGGER.info("[SHANHAI-SPEC] 配方类型已注册：真类型 {} / {}"
                        // 🔴 2026-09-26 订正：这句原写「2026-09-23 裁剪后仅保留被 24 台模块引用的类型」——
                        //    用户 2026-09-26 点单"40 条"已把那 24 条恢复 ⇒ 旧话术不再是事实，故改写。
                        //    ⚠️ 那个「51」不是手写数字，是下面两个 {} 之外的常量；见 REAL_TYPE_COUNT=40 / 显示类型 0。
                        + "（2026-09-26 用户点单恢复：40 条真类型全部注册；"
                        + "逐条照抄原版 DShanhaiRecipeTypes.java；只注册、不挂机器；"
                        + "36 条 GTNH 显示类型仍不恢复 —— 用户 2026-09-22「那个 GTNH 是我重制版不会添加的」；"
                        + "链尾 gtladditions 的 setSound 按用户 2026-09-22 裁决省略）",
                REAL_TYPE_COUNT - realMissing, REAL_TYPE_COUNT);

        // ───────── 🆕 2026-09-30：两个新类型的【可 grep 证据行】─────────
        // 🔴 这条是任务书要求的证据行。**条数写的是"声明值"**，不是这里现算的 ——
        //    注册期配方还没加载（datapack/KubeJS 都晚于配方类型注册），Java 这边**数不出**真实条数。
        //    真正的条数判据在 KJS 侧（temp\lens-goodbye\shanhai_lens_goodbye.js 的
        //    `[SHANHAI-NEWTYPE] ... ok=... failed=... declared=...`）。
        //    ⇒ 这两个常量就是两边对账的锚：KJS 的 declared 必须等于它，不等就是某一侧改了而另一侧没跟上。
        ShanhaiMod.LOGGER.info("[SHANHAI-NEWTYPE] 原初激光蚀刻 = {}（{} 条配方，"
                        + "透镜→电路 映射表见 handoff\\outbound\\原初激光蚀刻与蜂群铸造.md §3；"
                        + "模板 = 光子晶阵蚀刻 gtceu:photon_matrix_etch；挂「原初世线蚀刻核心」）",
                PRIMORDIAL_LASER_ETCHING.registryName, LASER_ETCH_DECLARED_RECIPES);
        ShanhaiMod.LOGGER.info("[SHANHAI-NEWTYPE] 原初蜂群铸造 = {}（{} 条配方，"
                        + "透镜→电路 映射表见 handoff\\outbound\\原初激光蚀刻与蜂群铸造.md §3；"
                        + "模板 = 纳米蜂群工厂 gtceu:nano_forge；挂「原初量子扭曲矩阵」）",
                PRIMORDIAL_SWARM_CASTING.registryName, SWARM_CAST_DECLARED_RECIPES);
        ShanhaiMod.LOGGER.info("[SHANHAI-NEWTYPE] 原初物质定型 = {}（{} 条配方，"
                        + "模头/模具→电路 映射表见 handoff\\outbound\\原初物质定型.md §2；"
                        + "来源 = 生成批 2457（压模器 gtceu:extruder 1344 条 ＋ 流体固化器 gtceu:fluid_solidifier 1113 条，"
                        + "剔除 0 条；旧版把压模器误认成 gtceu:forming_press 冲压机床，已纠正）"
                        + " ＋ 2026-10-03 手工追加 3 条（方钠石/青金石/蓝金石 粉→板，电路 8）= 2460；"
                        + "模头/模具→电路 用 0..32（生成批用到 31 个，8 与 10 空）；"
                        + "挂「原初临界加工模块」；配方以 KubeJS 形式装"
                        + "（kubejs\\server_scripts\\[server_scripts]shanhai_primordial_forming.js））",
                PRIMORDIAL_MATTER_FORMING.registryName, PRIMORDIAL_MATTER_FORMING_DECLARED_RECIPES);

        // ───────── JEI 展示层：给"带等级门槛"的配方插一个物质模块催化剂槽（不碰任何配方数据）─────────
        installModuleCatalystSlotUi();
    }

    // ═══════════════════════════════════════════════════════════════════════════════════════════
    // 🔴 2026-09-30 新增：JEI 配方页的「物质模块催化剂展示槽」（纯展示层）
    //
    // 用户原始需求（逐字）：「我希望 jei 右键物质模块（显示用途），可以看到物质模块作为我们特殊的催化剂的配方也可以出现」
    //
    // 现状（前一轮方案研究实测）：41 条「形态甲·等级门槛」配方的模块【不在 recipe.getInputs() 里】，
    // 只挂在 recipe.conditions 的 ModuleLevelCondition 上 ⇒ JEI 的「用途」按 ingredient 反查，查不到它。
    // ⇒ 做法 = 给这些配方类型挂一个 uiBuilder，在 JEI 配方页插一个【CATALYST 角色】的展示槽，
    //    槽里放【该条配方条件里实际写的那个模块】（从 recipe.conditions 读，不硬编码）。
    //    机制链（JEI「用途」键同时查 INPUT 与 CATALYST）见 ModuleCatalystSlotUI 的类注释。
    //
    // 🔴 红线：只改展示层。不碰 GTRecipe.inputs / conditions / RecipeRunner / 配方生成。
    //    ⇒ cost = 0：形态甲"门槛不占输入槽"的卖点完整保留（模块机模块槽是 IO.NONE，
    //      对 RecipeRunner 不可见，所以门槛是模块机上唯一可行的形态，也不能改成 notConsumable）。
    //
    // 🔴 本轮【故意只挂 1 个类型】（竖切）：跑通一次之后再铺到全部 8 个类型。
    //    🟢 2026-09-30 状态更新：**已铺开**（下面数组里那 7 行注释放开了）。
    //       触发 = 用户原话「jei是成功了一半，有些配方可以显示物质模块，有些不可以」
    //       ⇒ "有些不能看"正是这里的竖切范围造成的，不是坏。
    //    ⛔ 以下这段竖切原话【保留不改】（本工程惯例：改判时旧文不删，只加注）。
    //    选 primordial_singularity_inversion（原初奇点反演）当竖切类型的理由：
    //      该类型共 8 条配方 = 6 条带门槛（3 个不同模块：入门物质模块 x3 / 基础物质模块 x1 / 物质推演模块 x2）
    //      + 2 条不带门槛（下-夸克释放催化剂 / 上-夸克释放催化剂）。
    //      ⇒ 一次进游戏就能同时验四件事：①槽出现了 ②槽里是该条配方真正要的那个模块（三个不同模块互不串）
    //        ③「用途」能查到 ④不带门槛的配方【没有】被凭空加槽。
    //    ⚠️ 若选一个"全部配方都带门槛"的类型（如 primordial_matter_recombination 19/19），
    //       第 ④ 件事在游戏里就验不了。这是刻意挑的。
    //
    // 🟢 铺开到全部 8 个类型 = 把下面数组里那 7 行注释放开，一行不用改别的。
    //    其余 7 个门槛类型：PRIMORDIAL_MATTER_RECOMBINATION / PHOTON_SEPARATION / SPACETIME_DISTORTION /
    //    WL_BOARD_CIRCUIT_ASSEMBLY / INTERSTELLAR_MATTER_ABSORPTION / MATTER_FLOW_CONDENSATION / PHOTON_SIPHON。
    //
    // 🔴 2026-10-01 状态更新（**覆盖上面「8 个类型 / 那张数组」的口径；旧文按要求原样保留**）：
    //    范围已从「手工白名单」改成 **【全部山海配方类型】**（用户原话：「你要不然把列表改成
    //    【全部山海配方类型】吧，这样是不是更方便一些，以后不用补了」）。
    //    ⇒ 真源 = 注册期自动登记的 {@link #REGISTERED_TYPES}；上面"把注释放开/改数组"那套操作**已作废**，
    //      不要再照它改。旧的 9 项手工数组原文保留在本方法末尾的「⛔ 旧口径留档」里。
    // ═══════════════════════════════════════════════════════════════════════════════════════════
    private static void installModuleCatalystSlotUi() {
        // 🔴 2026-10-01 改造：手工白名单 ⇒ 注册期自动收集的【唯一真源】（见字段区那段长注释）。
        //    范围 = **全部山海配方类型**。用户原话（逐字）：
        //      「还有那个 jei 的物质模块槽显示，你要不然把列表改成【全部山海配方类型】吧，
        //        这样是不是更方便一些，以后不用补了」
        //    🔴 安全性 = ModuleCatalystSlotUI.append 自己按 recipe.conditions 过滤：
        //    **没有 ModuleLevelCondition 的配方一个槽都不加**（离线实测读数见
        //    handoff\outbound\展示槽-全类型挂载.md §3）⇒「全挂」只等于「每个类型都有机会长槽」，
        //    不等于「每个配方页都会多东西」。
        final List<GTRecipeType> types = new ArrayList<>(REGISTERED_TYPES);
        int ok = 0;
        final StringBuilder detail = new StringBuilder();
        final StringBuilder notInstalled = new StringBuilder();
        for (GTRecipeType type : types) {
            if (type == null) {
                notInstalled.append("[null], ");
                continue;
            }
            if (com.shanhai.integration.jei.ModuleCatalystSlotUI.install(type)) {
                ok++;
                if (detail.length() > 0) {
                    detail.append(", ");
                }
                detail.append(type.registryName);
            } else {
                notInstalled.append(type.registryName).append(", ");
            }
        }
        ShanhaiMod.LOGGER.info("[SHANHAI-JEI] 模块催化剂展示槽已挂：{}/{} 个配方类型"
                        + "（2026-10-01 起 =【全部山海配方类型】；真源 = 注册期自动登记的 REGISTERED_TYPES，"
                        + "没有任何手工清单 ⇒ 以后新增配方类型不用改这里）；"
                        + "已挂 = [{}]；未挂 = [{}]（未挂必须是空表，否则说明 install 抛了）；"
                        + "配方数据一个字未动（只插展示层 CATALYST 槽，不占输入槽、不参与匹配）；"
                        + "⚠️ uiBuilder 是【每个类型各一份】的实例字段（GTRecipeType.recipeUI，javap 实证）"
                        + "⇒ 不存在「挂一个类型、别的类型也长出槽」的泄漏",
                ok, types.size(), detail, notInstalled);

        // ⛔ 旧口径留档（2026-09-30 竖切 → 铺开 8 个 → 2026-10-01 补第 9 个）——【原文逐字保留】。
        //    留它的理由：下面这份手工数组就是当天那个 bug 的现场（漏了 worldline_cutting），
        //    也是「为什么必须换成自动真源」的证据。本工程惯例：改判时旧文不删，只加注。
        //          final GTRecipeType[] types = {
        //                  PRIMORDIAL_SINGULARITY_INVERSION,
        //                  // 🔴 2026-09-30 铺开（用户 2026-09-30 原话：「jei是成功了一半，有些配方可以显示物质模块，有些不可以」）——
        //                  //    竖切那一版只挂了 1 个类型 ⇒ "有些能看、有些不能看"是这个范围造成的，不是坏。
        //                  //    现在把剩下的 7 个「带等级门槛的配方类型」全部放开（一个数字都没硬编码：
        //                  //    槽里的模块仍然逐条取自 recipe.conditions，不带门槛的配方仍然一个槽都不加）。
        //                  PRIMORDIAL_MATTER_RECOMBINATION,
        //                  PHOTON_SEPARATION,
        //                  SPACETIME_DISTORTION,
        //                  WL_BOARD_CIRCUIT_ASSEMBLY,
        //                  INTERSTELLAR_MATTER_ABSORPTION,
        //                  MATTER_FLOW_CONDENSATION,
        //                  PHOTON_SIPHON,
        //                  // 🔴 2026-10-01 补第 9 个（用户实测回报第 ⑤ 条的根因，逐字证据见下）。
        //                  //
        //                  // 用户原话（逐字）：「5：就是我要的是这个东西（如图5），而这个配方【没有显示这个】（如图6）」
        //                  //   · 图5 = 「原初物质重组」（`gtceu:primordial_matter_recombination` 1/4）——
        //                  //     鼠标正停在展示槽上，弹的提示是「催化剂：基础物质模块（不占输入槽·不消耗）」
        //                  //     （这一行**全工程只有** ModuleCatalystSlotUI.buildTooltip 产出）⇒ 那一页【有】槽；
        //                  //   · 图6 = 「原初世线切割」（`gtceu:worldline_cutting` 1/1）—— 右下角只有 JEI 自己的
        //                  //     书签/「+」两个按钮，没有我们的 18×18 槽 ⇒ 那一页【没有】槽。
        //                  // ⇒ 这两个类型此前都【不在】上面那张表里 —— 上面那行注释自称"铺开到全部带门槛类型"，
        //                  //   但那是**手工列举**，实际漏了 `worldline_cutting` 一个。
        //                  //
        //                  // 🔴 漏挂的判据（三条独立证据，不是推断）：
        //                  //   ① 代码：上面 8 项里没有 WORLDLINE_CUTTING；
        //                  //   ② 运行期日志（2026-10-01 14:15:17，`GTL山海9.10test\logs\latest.log`）：
        //                  //      `[SHANHAI-JEI] 模块催化剂展示槽已挂：8/8 … 已挂 = [gtceu:primordial_singularity_inversion,
        //                  //       gtceu:primordial_matter_recombination, gtceu:photon_separation, gtceu:spacetime_distortion,
        //                  //       gtceu:wl_board_circuit_assembly, gtceu:interstellar_matter_absorption,
        //                  //       gtceu:matter_flow_condensation, gtceu:photon_siphon]`
        //                  //      —— 列表里逐字没有 `gtceu:worldline_cutting`；
        //                  //   ③ 反查三个山海配方脚本里所有 `moduleLevelRequirement:` 并按类型归并 ⇒
        //                  //      **带门槛的类型共 9 个**，与 8 项数组做差，缺的正好是 `worldline_cutting`
        //                  //      （它那条配方 `shanhai:pf/thread_shard_1` 的门槛 = `1x shanhai:material_deduction_module`）。
        //                  //
        //                  // ⚠️ 安全性（就地核实过，不是假设）：`WORLDLINE_CUTTING` 在**本方法之前**的 `init()`
        //                  //    （本文件 :834）注册 ⇒ 走到这里字段必非 null，不会被下面 `if (type == null) continue;`
        //                  //    静默跳过（那正是本工程"悄悄不发生"型失败的高发点）。
        //                  //
        //                  // ✅ 改完的机器可验判据：重进游戏后那行日志应变成 `9/9`，且 `已挂 = [...]` 末尾多出
        //                  //    `gtceu:worldline_cutting`；同时会多出一条
        //                  //    `催化剂展示槽已挂上（配方页）：recipe=shanhai:pf/thread_shard_1 module=shanhai:material_deduction_module`
        //                  //    （日志按 recipe.id 去重，这一条此前从未出现过）。
        //                  WORLDLINE_CUTTING,
        //          };
    }

    // ═══════════════════════════════════════════════════════════════════════════════════════════
    // ⛔ 作废块（2026-09-23，用户裁决「裁剪配方类型注册」）
    //    🔴 2026-09-26 状态更新：**本块内容已被【恢复为活代码】**（用户点单"40 条"）——
    //       24 个字段声明在文件上方「2026-09-26 恢复」那一段，24 段 register 链在 init() 末尾。
    //       本块**原样保留作历史留档**（本工程惯例：改判时旧文不删）。
    //       ⇒ 读代码请以活代码为准；本块只是"当时为什么删、原文长什么样"的凭证。
    //    🔴 仍然有效、且**不许顺手改回来**的一条：**36 条显示类型不恢复**（GTNH 来源）。
    //    以下是被删掉的 **24 个真类型** 与 **36 个显示类型** 的【原文】，逐字保留，
    //    仅每行前置 "// " 使其成为注释（不能包在 /* */ 里：原文含 javadoc，注释不可嵌套）。
    //    将来做新机器时要重新加回来。
    //
    //    🔴 随之作废的【三个用户拟名】——名字的由来必须留档（否则将来没人知道是谁起的）：
    //        · gtceu:matter_aggregation              = 原初物质凝集   （用户 2026-09-22 拟名）
    //        · gtceu:worldline_cutting               = 原初世线切割   （用户 2026-09-22 拟名）
    //        · gtceu:high_dimensional_fragment_cutting = 高维碎片裁切 （用户 2026-09-22 拟名）
    //      这三个名字【不是】原版 lang 原文（原版 lang 里根本没有这三个键），是用户亲定的；
    //      类型本次被删 ⇒ 三个名字随之作废，但由来按规定留档在此。
    //      用户若反悔，重新加回类型 + 重新加回 lang 键即可（键值见 git 历史/交付报告）。
    //
    //    另：36 个显示类型 { nine_industrial_mode_0 .. _35 } 来自 GTNH（GTnotleisure），
    //        用户 2026-09-22 原话「那个 GTNH 是我重制版不会添加的」⇒ 整组删除，
    //        数组 NINE_INDUSTRIAL_MODES / for 循环 / DISPLAY_TYPE_COUNT / countMissingDisplay()
    //        一并删除，【不留恒为 0 的空壳】。
    //    ═══════════════════════════════════════════════════════════════════════════════════════
    // 【作废 · 字段声明原文】
    //     /** 代理执行占位类型（{@code setMaxTooltips(1)}，无 slotOverlay）—— 代理执行机器。 */
    //     public static GTRecipeType PROXY_EXECUTION;
    // 
    //     /** 原初铸币工厂 —— 原版有该机器，<b>本工程已删除该机器</b>；类型照原版保留注册。 */
    //     public static GTRecipeType COIN_FORGE;
    // 
    //     /** 大明科技聚合类型 —— 大明工业机器。 */
    //     public static GTRecipeType NINE_INDUSTRIAL;
    // 
    //     /** 事件视界爆破（4 条带专属 .rtui 的类型之一）—— 黑洞收容机器。 */
    //     public static GTRecipeType BLACK_HOLE_EVENT_HORIZON_BLAST;
    // 
    //     /** 黑洞中子态素压缩 —— 黑洞收容机器。 */
    //     public static GTRecipeType BLACK_HOLE_NEUTRONIUM_COMPRESSOR;
    // 
    //     /** 黑洞引力压缩 —— 黑洞收容机器。 */
    //     public static GTRecipeType BLACK_HOLE_COMPRESSOR;
    // 
    //     /** 高维碎片裁切（用户拟名）—— <b>原版零挂载</b>。 */
    //     public static GTRecipeType HIGH_DIMENSIONAL_FRAGMENT_CUTTING;
    // 
    //     /** 原初世线切割（用户拟名）—— <b>原版零挂载</b>。 */
    //     public static GTRecipeType WORLDLINE_CUTTING;
    // 
    //     /** 世线采样 —— 世线裂解枢纽。 */
    //     public static GTRecipeType WORLDLINE_SAMPLING;
    // 
    //     /** 世线物质重现 —— 世线裂解枢纽。 */
    //     public static GTRecipeType WORLDLINE_MATTER_RECURRENCE;
    // 
    //     /** 概率裂解 —— 世线裂解枢纽。 */
    //     public static GTRecipeType WORLDLINE_PROBABILITY_CRACKING;
    // 
    //     /** 光子虹吸（{@code "single"}，5 条 slotOverlay）—— 世线裂解枢纽 ＋ 零点光子转换器。 */
    //     public static GTRecipeType PHOTON_SIPHON;
    // 
    //     /** 零点转换（{@code "single"}）—— 世线裂解枢纽 ＋ 零点光子转换器。 */
    //     public static GTRecipeType ZERO_POINT_CONVERSION;
    // 
    //     /** 原初物质凝集（用户拟名；原版 lang 无此键）—— <b>原版零挂载</b>。 */
    //     public static GTRecipeType MATTER_AGGREGATION;
    // 
    //     /** 引力波广域广播 —— 引力波天线发射器。 */
    //     public static GTRecipeType GRAVITATIONAL_WAVE_CONSUMPTION;
    // 
    //     /** 宇宙修改·天界领航 —— 天界领航塔（注册在它自己类里）。 */
    //     public static GTRecipeType TIANJIE_NAVIGATION;
    // 
    //     /** 多维星穹零点聚合 —— 天界星云零点虹吸枢纽。 */
    //     public static GTRecipeType NEBULA_SIPHONING;
    // 
    //     /** 混沌合成（lang 原文值含 §k 混淆格式码）—— 引力波天线发射器 ＋ 创世之眼模块。 */
    //     public static GTRecipeType CHAOS_CRAFTING;
    // 
    //     /** 七十二变 —— 引力波天线发射器。 */
    //     public static GTRecipeType SEVENTY_TWO_CHANGES;
    // 
    //     /** 引力波宏观干涉 —— 引力波天线发射器。 */
    //     public static GTRecipeType GRAVITATIONAL_WAVE_PRODUCTION;
    // 
    //     /** 一级原初万象晋升 —— 原初万象衍生核心（我们未做该模块）。 */
    //     public static GTRecipeType PRIMORDIAL_MYRIAD_ASCENSION_TIER_1;
    // 
    //     /** 二级原初万象晋升 —— 原初万象衍生核心（我们未做该模块）。 */
    //     public static GTRecipeType PRIMORDIAL_MYRIAD_ASCENSION_TIER_2;
    // 
    //     /** 苦命鸳鸯 —— 终焉创始现实修改矩阵。 */
    //     public static GTRecipeType KU_MING_YUAN_YANG;
    // 
    //     /** 量子化现实重构 —— 终焉创始现实修改矩阵。 */
    //     public static GTRecipeType SPACETIME_DISTORTION;
    // 

    // 【作废 · register 链原文】（原 init() 里逐条照抄 DShanhaiRecipeTypes.java 的那 24 段）
    //         // :485-490 —— 无 slotOverlay，setMaxTooltips(1)
    //         PROXY_EXECUTION = GTRecipeTypes.register("proxy_execution", "multiblock")
    //                 .setMaxIOSize(0, 0, 0, 0)
    //                 .setEUIO(IO.IN)
    //                 .setMaxTooltips(1)
    //                 .setProgressBar(GuiTextures.PROGRESS_BAR_ARROW, ProgressTexture.FillDirection.LEFT_TO_RIGHT);
    // 
    //         // :474-483
    //         COIN_FORGE = GTRecipeTypes.register("coin_forge", "multiblock")
    //                 .setMaxIOSize(9, 6, 6, 3)
    //                 .setEUIO(IO.IN)
    //                 .setMaxTooltips(4)
    //                 .setProgressBar(GuiTextures.PROGRESS_BAR_ARROW, ProgressTexture.FillDirection.LEFT_TO_RIGHT)
    //                 .setSlotOverlay(false, false, true, GuiTextures.FLUID_SLOT)
    //                 .setSlotOverlay(false, false, false, GuiTextures.DUST_OVERLAY)
    //                 .setSlotOverlay(true, false, true, GuiTextures.FLUID_SLOT)
    //                 .setSlotOverlay(true, false, false, GuiTextures.DUST_OVERLAY);
    // 
    //         // :453-462
    //         NINE_INDUSTRIAL = GTRecipeTypes.register("nine_industrial", "multiblock")
    //                 .setMaxIOSize(24, 24, 12, 12)
    //                 .setEUIO(IO.IN)
    //                 .setMaxTooltips(4)
    //                 .setProgressBar(GuiTextures.PROGRESS_BAR_ARROW, ProgressTexture.FillDirection.LEFT_TO_RIGHT)
    //                 .setSlotOverlay(false, false, true, GuiTextures.FLUID_SLOT)
    //                 .setSlotOverlay(false, false, false, GuiTextures.DUST_OVERLAY)
    //                 .setSlotOverlay(true, false, true, GuiTextures.FLUID_SLOT)
    //                 .setSlotOverlay(true, false, false, GuiTextures.DUST_OVERLAY);
    // 
    //         // :442-451 —— PROGRESS_BAR_FUSION
    //         BLACK_HOLE_EVENT_HORIZON_BLAST = GTRecipeTypes.register("black_hole_event_horizon_blast", "multiblock")
    //                 .setMaxIOSize(3, 9, 3, 6)
    //                 .setEUIO(IO.IN)
    //                 .setMaxTooltips(4)
    //                 .setProgressBar(GuiTextures.PROGRESS_BAR_FUSION, ProgressTexture.FillDirection.LEFT_TO_RIGHT)
    //                 .setSlotOverlay(false, false, true, GuiTextures.FLUID_SLOT)
    //                 .setSlotOverlay(false, false, false, GuiTextures.DUST_OVERLAY)
    //                 .setSlotOverlay(true, false, true, GuiTextures.FLUID_SLOT)
    //                 .setSlotOverlay(true, false, false, GuiTextures.DUST_OVERLAY);
    // 
    //         // :431-440 —— PROGRESS_BAR_COMPRESS
    //         BLACK_HOLE_NEUTRONIUM_COMPRESSOR = GTRecipeTypes.register("black_hole_neutronium_compressor", "multiblock")
    //                 .setMaxIOSize(9, 6, 6, 5)
    //                 .setEUIO(IO.IN)
    //                 .setMaxTooltips(4)
    //                 .setProgressBar(GuiTextures.PROGRESS_BAR_COMPRESS, ProgressTexture.FillDirection.LEFT_TO_RIGHT)
    //                 .setSlotOverlay(false, false, true, GuiTextures.FLUID_SLOT)
    //                 .setSlotOverlay(false, false, false, GuiTextures.DUST_OVERLAY)
    //                 .setSlotOverlay(true, false, true, GuiTextures.FLUID_SLOT)
    //                 .setSlotOverlay(true, false, false, GuiTextures.DUST_OVERLAY);
    // 
    //         // :420-429 —— PROGRESS_BAR_COMPRESS
    //         BLACK_HOLE_COMPRESSOR = GTRecipeTypes.register("black_hole_compressor", "multiblock")
    //                 .setMaxIOSize(9, 6, 6, 5)
    //                 .setEUIO(IO.IN)
    //                 .setMaxTooltips(4)
    //                 .setProgressBar(GuiTextures.PROGRESS_BAR_COMPRESS, ProgressTexture.FillDirection.LEFT_TO_RIGHT)
    //                 .setSlotOverlay(false, false, true, GuiTextures.FLUID_SLOT)
    //                 .setSlotOverlay(false, false, false, GuiTextures.DUST_OVERLAY)
    //                 .setSlotOverlay(true, false, true, GuiTextures.FLUID_SLOT)
    //                 .setSlotOverlay(true, false, false, GuiTextures.DUST_OVERLAY);
    // 
    //         // :409-418
    //         HIGH_DIMENSIONAL_FRAGMENT_CUTTING = GTRecipeTypes.register("high_dimensional_fragment_cutting", "multiblock")
    //                 .setMaxIOSize(4, 9, 2, 4)
    //                 .setEUIO(IO.IN)
    //                 .setMaxTooltips(4)
    //                 .setProgressBar(GuiTextures.PROGRESS_BAR_ARROW, ProgressTexture.FillDirection.LEFT_TO_RIGHT)
    //                 .setSlotOverlay(false, false, true, GuiTextures.FLUID_SLOT)
    //                 .setSlotOverlay(false, false, false, GuiTextures.DUST_OVERLAY)
    //                 .setSlotOverlay(true, false, true, GuiTextures.FLUID_SLOT)
    //                 .setSlotOverlay(true, false, false, GuiTextures.DUST_OVERLAY);
    // 
    //         // :398-407
    //         WORLDLINE_CUTTING = GTRecipeTypes.register("worldline_cutting", "multiblock")
    //                 .setMaxIOSize(6, 6, 4, 4)
    //                 .setEUIO(IO.IN)
    //                 .setMaxTooltips(4)
    //                 .setProgressBar(GuiTextures.PROGRESS_BAR_ARROW, ProgressTexture.FillDirection.LEFT_TO_RIGHT)
    //                 .setSlotOverlay(false, false, true, GuiTextures.FLUID_SLOT)
    //                 .setSlotOverlay(false, false, false, GuiTextures.DUST_OVERLAY)
    //                 .setSlotOverlay(true, false, true, GuiTextures.FLUID_SLOT)
    //                 .setSlotOverlay(true, false, false, GuiTextures.DUST_OVERLAY);
    // 
    //         // :386-395
    //         WORLDLINE_SAMPLING = GTRecipeTypes.register("worldline_sampling", "multiblock")
    //                 .setMaxIOSize(3, 12, 3, 6)
    //                 .setEUIO(IO.IN)
    //                 .setMaxTooltips(4)
    //                 .setProgressBar(GuiTextures.PROGRESS_BAR_ARROW, ProgressTexture.FillDirection.LEFT_TO_RIGHT)
    //                 .setSlotOverlay(false, false, true, GuiTextures.FLUID_SLOT)
    //                 .setSlotOverlay(false, false, false, GuiTextures.DUST_OVERLAY)
    //                 .setSlotOverlay(true, false, true, GuiTextures.FLUID_SLOT)
    //                 .setSlotOverlay(true, false, false, GuiTextures.DUST_OVERLAY);
    // 
    //         // :375-384
    //         WORLDLINE_MATTER_RECURRENCE = GTRecipeTypes.register("worldline_matter_recurrence", "multiblock")
    //                 .setMaxIOSize(9, 6, 6, 3)
    //                 .setEUIO(IO.IN)
    //                 .setMaxTooltips(4)
    //                 .setProgressBar(GuiTextures.PROGRESS_BAR_ARROW, ProgressTexture.FillDirection.LEFT_TO_RIGHT)
    //                 .setSlotOverlay(false, false, true, GuiTextures.FLUID_SLOT)
    //                 .setSlotOverlay(false, false, false, GuiTextures.DUST_OVERLAY)
    //                 .setSlotOverlay(true, false, true, GuiTextures.FLUID_SLOT)
    //                 .setSlotOverlay(true, false, false, GuiTextures.DUST_OVERLAY);
    // 
    //         // :364-373
    //         WORLDLINE_PROBABILITY_CRACKING = GTRecipeTypes.register("worldline_probability_cracking", "multiblock")
    //                 .setMaxIOSize(6, 9, 4, 4)
    //                 .setEUIO(IO.IN)
    //                 .setMaxTooltips(4)
    //                 .setProgressBar(GuiTextures.PROGRESS_BAR_ARROW, ProgressTexture.FillDirection.LEFT_TO_RIGHT)
    //                 .setSlotOverlay(false, false, true, GuiTextures.FLUID_SLOT)
    //                 .setSlotOverlay(false, false, false, GuiTextures.DUST_OVERLAY)
    //                 .setSlotOverlay(true, false, true, GuiTextures.FLUID_SLOT)
    //                 .setSlotOverlay(true, false, false, GuiTextures.DUST_OVERLAY);
    // 
    //         // :256-266 —— category "single"；全 40 条里唯一 5 条 slotOverlay（原版原文第 261/262 行是两条相同的 DUST，照抄不合并）
    //         PHOTON_SIPHON = GTRecipeTypes.register("photon_siphon", "single")
    //                 .setMaxIOSize(4, 2, 2, 2)
    //                 .setEUIO(IO.IN)
    //                 .setMaxTooltips(4)
    //                 .setProgressBar(GuiTextures.PROGRESS_BAR_ARROW, ProgressTexture.FillDirection.LEFT_TO_RIGHT)
    //                 .setSlotOverlay(false, false, false, GuiTextures.DUST_OVERLAY)
    //                 .setSlotOverlay(false, false, false, GuiTextures.DUST_OVERLAY)
    //                 .setSlotOverlay(true, false, false, GuiTextures.DUST_OVERLAY)
    //                 .setSlotOverlay(false, false, true, GuiTextures.FLUID_SLOT)
    //                 .setSlotOverlay(true, false, true, GuiTextures.FLUID_SLOT);
    // 
    //         // :245-254 —— category "single"
    //         ZERO_POINT_CONVERSION = GTRecipeTypes.register("zero_point_conversion", "single")
    //                 .setMaxIOSize(2, 2, 2, 2)
    //                 .setEUIO(IO.IN)
    //                 .setMaxTooltips(4)
    //                 .setProgressBar(GuiTextures.PROGRESS_BAR_ARROW, ProgressTexture.FillDirection.LEFT_TO_RIGHT)
    //                 .setSlotOverlay(false, false, true, GuiTextures.FLUID_SLOT)
    //                 .setSlotOverlay(false, false, false, GuiTextures.DUST_OVERLAY)
    //                 .setSlotOverlay(true, false, false, GuiTextures.DUST_OVERLAY)
    //                 .setSlotOverlay(true, false, true, GuiTextures.FLUID_SLOT);
    // 
    //         // :236-243 —— category "single"（用户 2026-09-22 亲定中文名：原初物质凝集）
    //         MATTER_AGGREGATION = GTRecipeTypes.register("matter_aggregation", "single")
    //                 .setMaxIOSize(2, 2, 0, 0)
    //                 .setEUIO(IO.IN)
    //                 .setMaxTooltips(4)
    //                 .setProgressBar(GuiTextures.PROGRESS_BAR_ARROW, ProgressTexture.FillDirection.LEFT_TO_RIGHT)
    //                 .setSlotOverlay(false, false, false, GuiTextures.DUST_OVERLAY)
    //                 .setSlotOverlay(true, false, false, GuiTextures.DUST_OVERLAY);
    // 
    //         // :216-223
    //         GRAVITATIONAL_WAVE_CONSUMPTION = GTRecipeTypes.register("gravitational_wave_consumption", "multiblock")
    //                 .setMaxIOSize(1, 0, 1, 0)
    //                 .setEUIO(IO.IN)
    //                 .setMaxTooltips(4)
    //                 .setProgressBar(GuiTextures.PROGRESS_BAR_ARROW, ProgressTexture.FillDirection.LEFT_TO_RIGHT)
    //                 .setSlotOverlay(false, false, true, GuiTextures.FLUID_SLOT)
    //                 .setSlotOverlay(false, false, false, GuiTextures.DUST_OVERLAY);
    // 
    //         // :205-214
    //         TIANJIE_NAVIGATION = GTRecipeTypes.register("tianjie_navigation", "multiblock")
    //                 .setMaxIOSize(6, 3, 6, 3)
    //                 .setEUIO(IO.IN)
    //                 .setMaxTooltips(4)
    //                 .setProgressBar(GuiTextures.PROGRESS_BAR_ARROW, ProgressTexture.FillDirection.LEFT_TO_RIGHT)
    //                 .setSlotOverlay(false, false, true, GuiTextures.FLUID_SLOT)
    //                 .setSlotOverlay(false, false, false, GuiTextures.DUST_OVERLAY)
    //                 .setSlotOverlay(true, false, true, GuiTextures.FLUID_SLOT)
    //                 .setSlotOverlay(true, false, false, GuiTextures.DUST_OVERLAY);
    // 
    //         // :194-203
    //         NEBULA_SIPHONING = GTRecipeTypes.register("nebula_siphoning", "multiblock")
    //                 .setMaxIOSize(6, 3, 6, 3)
    //                 .setEUIO(IO.IN)
    //                 .setMaxTooltips(4)
    //                 .setProgressBar(GuiTextures.PROGRESS_BAR_ARROW, ProgressTexture.FillDirection.LEFT_TO_RIGHT)
    //                 .setSlotOverlay(false, false, true, GuiTextures.FLUID_SLOT)
    //                 .setSlotOverlay(false, false, false, GuiTextures.DUST_OVERLAY)
    //                 .setSlotOverlay(true, false, true, GuiTextures.FLUID_SLOT)
    //                 .setSlotOverlay(true, false, false, GuiTextures.DUST_OVERLAY);
    // 
    //         // :183-192 —— 全 40 条里唯一的 EUIO = IO.BOTH
    //         CHAOS_CRAFTING = GTRecipeTypes.register("chaos_crafting", "multiblock")
    //                 .setMaxIOSize(24, 24, 12, 12)
    //                 .setEUIO(IO.BOTH)
    //                 .setMaxTooltips(4)
    //                 .setProgressBar(GuiTextures.PROGRESS_BAR_ARROW, ProgressTexture.FillDirection.LEFT_TO_RIGHT)
    //                 .setSlotOverlay(false, false, true, GuiTextures.FLUID_SLOT)
    //                 .setSlotOverlay(false, false, false, GuiTextures.DUST_OVERLAY)
    //                 .setSlotOverlay(true, false, true, GuiTextures.FLUID_SLOT)
    //                 .setSlotOverlay(true, false, false, GuiTextures.DUST_OVERLAY);
    // 
    //         // :174-181 —— category 是裸字符串 "single"
    //         SEVENTY_TWO_CHANGES = GTRecipeTypes.register("seventy_two_changes", "single")
    //                 .setMaxIOSize(1, 1, 0, 0)
    //                 .setEUIO(IO.IN)
    //                 .setMaxTooltips(4)
    //                 .setProgressBar(GuiTextures.PROGRESS_BAR_ARROW, ProgressTexture.FillDirection.LEFT_TO_RIGHT)
    //                 .setSlotOverlay(false, false, false, GuiTextures.DUST_OVERLAY)
    //                 .setSlotOverlay(true, false, false, GuiTextures.DUST_OVERLAY);
    // 
    //         // :163-172
    //         GRAVITATIONAL_WAVE_PRODUCTION = GTRecipeTypes.register("gravitational_wave_production", "multiblock")
    //                 .setMaxIOSize(2, 2, 2, 2)
    //                 .setEUIO(IO.IN)
    //                 .setMaxTooltips(4)
    //                 .setProgressBar(GuiTextures.PROGRESS_BAR_ARROW, ProgressTexture.FillDirection.LEFT_TO_RIGHT)
    //                 .setSlotOverlay(false, false, true, GuiTextures.FLUID_SLOT)
    //                 .setSlotOverlay(false, false, false, GuiTextures.DUST_OVERLAY)
    //                 .setSlotOverlay(true, false, true, GuiTextures.FLUID_SLOT)
    //                 .setSlotOverlay(true, false, false, GuiTextures.DUST_OVERLAY);
    // 
    //         // :102-109 —— 同上
    //         PRIMORDIAL_MYRIAD_ASCENSION_TIER_1 = GTRecipeTypes.register("primordial_myriad_ascension_tier_1", "multiblock")
    //                 .setMaxIOSize(4, 0, 4, 0)
    //                 .setEUIO(IO.IN)
    //                 .setMaxTooltips(4)
    //                 .setProgressBar(GuiTextures.PROGRESS_BAR_ARROW, ProgressTexture.FillDirection.LEFT_TO_RIGHT)
    //                 .setSlotOverlay(false, false, true, GuiTextures.FLUID_SLOT)
    //                 .setSlotOverlay(false, false, false, GuiTextures.DUST_OVERLAY)
    //                 .setSound(GTSoundEntries.ARC);
    // 
    //         // :93-100 —— 链尾 setSound(GTSoundEntries.ARC)：GTCEu 自己的 API，按队长 2026-09-22 裁决【恢复抄写】
    //         PRIMORDIAL_MYRIAD_ASCENSION_TIER_2 = GTRecipeTypes.register("primordial_myriad_ascension_tier_2", "multiblock")
    //                 .setMaxIOSize(4, 0, 4, 0)
    //                 .setEUIO(IO.IN)
    //                 .setMaxTooltips(4)
    //                 .setProgressBar(GuiTextures.PROGRESS_BAR_ARROW, ProgressTexture.FillDirection.LEFT_TO_RIGHT)
    //                 .setSlotOverlay(false, false, true, GuiTextures.FLUID_SLOT)
    //                 .setSlotOverlay(false, false, false, GuiTextures.DUST_OVERLAY)
    //                 .setSound(GTSoundEntries.ARC);
    // 
    //         // :71-80
    //         KU_MING_YUAN_YANG = GTRecipeTypes.register("kmyy", "multiblock")
    //                 .setMaxIOSize(2, 1, 0, 0)
    //                 .setEUIO(IO.OUT)
    //                 .setMaxTooltips(4)
    //                 .setProgressBar(GuiTextures.PROGRESS_BAR_ARROW, ProgressTexture.FillDirection.LEFT_TO_RIGHT)
    //                 .setSlotOverlay(false, false, true, GuiTextures.FLUID_SLOT)
    //                 .setSlotOverlay(false, false, false, GuiTextures.DUST_OVERLAY)
    //                 .setSlotOverlay(true, false, true, GuiTextures.FLUID_SLOT)
    //                 .setSlotOverlay(true, false, false, GuiTextures.DUST_OVERLAY);
    // 
    //         SPACETIME_DISTORTION = GTRecipeTypes.register("spacetime_distortion", "multiblock")
    //                 .setMaxIOSize(9, 6, 6, 5)
    //                 .setEUIO(IO.IN)
    //                 .setMaxTooltips(4)
    //                 .setProgressBar(GuiTextures.PROGRESS_BAR_ARROW, ProgressTexture.FillDirection.LEFT_TO_RIGHT)
    //                 .setSlotOverlay(false, false, true, GuiTextures.FLUID_SLOT)
    //                 .setSlotOverlay(false, false, false, GuiTextures.DUST_OVERLAY)
    //                 .setSlotOverlay(true, false, true, GuiTextures.FLUID_SLOT)
    //                 .setSlotOverlay(true, false, false, GuiTextures.DUST_OVERLAY);
    // 

    /** CommonSetup 兜底用：{@link #REAL_TYPE_COUNT} 条是否全部拿到句柄。 */
    public static boolean allRegistered() {
        return countMissingReal() == 0;
    }

    /**
     * {@link #REAL_TYPE_COUNT} 条真类型里拿到 null 的条数。
     *
     * <p>🔴 <b>2026-09-26：16 → 40 → 41。</b>这个数组<b>必须</b>与
     * {@link #REAL_TYPE_COUNT} 的条数一致 —— 少写一条的后果是
     * {@code allRegistered()} 对它<b>静默不查</b>（"没报错"与"查过了且没问题"在日志上长得一模一样），
     * 本工程为此付过账。改类型数量时两处必须同步。
     */
    private static int countMissingReal() {
        GTRecipeType[] all = declaredRealTypes();
        int missing = 0;
        for (GTRecipeType t : all) {
            if (t == null) {
                missing++;
            }
        }
        return missing;
    }

    /**
     * 🔴 <b>【手工声明清单】</b>——{@link #countMissingReal()} 的判据表（"每条声明的字段都拿到了句柄吗"）。
     *
     * <p>⚠️ <b>它不是"全部山海配方类型"的真源</b>（那个是 {@link #REGISTERED_TYPES}，注册期自动登记）。
     * 两者分工：
     * <ul>
     *   <li>{@link #declaredRealTypes()} = <b>声明</b>：本文件里逐条写下的类型字段，用来查"句柄是不是 null"；</li>
     *   <li>{@link #REGISTERED_TYPES} = <b>实际注册</b>：注册入口 {@code register(...)} 当场登记的返回值。</li>
     * </ul>
     * <p>🔴 2026-10-01 起两者<b>互钉</b>（见 {@link #assertTypeListsConsistent()}）：
     * 一张表里有、另一张没有 ⇒ 当场 fail-fast，<b>不再有"只查一部分、其余静默缺失"的缝</b>。
     */
    private static GTRecipeType[] declaredRealTypes() {
        return new GTRecipeType[]{
                PRIMORDIAL_MATTER_RECOMBINATION, PRIMORDIAL_STELLAR_REACTION, PRIMORDIAL_POWER_GENERATOR,
                PRIMORDIAL_BIOLOGICAL_CORE, PRIMORDIAL_CAUSAL_WEAVING, PRIMORDIAL_SINGULARITY_INVERSION,
                WORLDLINE_OSCILLATION_COLLECTION, INTERSTELLAR_MATTER_ABSORPTION, PRIMORDIAL_ENERGY_ABSORPTION,
                MATTER_FLOW_CONDENSATION, PHOTON_SEPARATION, MATTER_MODULE_CASTING,
                MATTER_FORGING, WL_BOARD_CIRCUIT_ASSEMBLY, WL_BOARD_WAFER_ETCHING, TAIXU_SMELTING,
                // ───── 2026-09-26 恢复的 24 条（用户点单"40 条"）─────
                PROXY_EXECUTION, COIN_FORGE, NINE_INDUSTRIAL,
                BLACK_HOLE_EVENT_HORIZON_BLAST, BLACK_HOLE_NEUTRONIUM_COMPRESSOR,
                BLACK_HOLE_COMPRESSOR,
                HIGH_DIMENSIONAL_FRAGMENT_CUTTING, WORLDLINE_CUTTING, WORLDLINE_SAMPLING,
                WORLDLINE_MATTER_RECURRENCE, WORLDLINE_PROBABILITY_CRACKING,
                PHOTON_SIPHON, ZERO_POINT_CONVERSION, MATTER_AGGREGATION,
                GRAVITATIONAL_WAVE_CONSUMPTION, TIANJIE_NAVIGATION, NEBULA_SIPHONING,
                CHAOS_CRAFTING, SEVENTY_TWO_CHANGES, GRAVITATIONAL_WAVE_PRODUCTION,
                PRIMORDIAL_MYRIAD_ASCENSION_TIER_1, PRIMORDIAL_MYRIAD_ASCENSION_TIER_2,
                KU_MING_YUAN_YANG, SPACETIME_DISTORTION,
                // ───── 2026-09-26 新增的第 41 条（用户点单「原初物质解构」）─────
                PRIMORDIAL_MATTER_DECONSTRUCTION,
                // ───── 🆕 2026-09-30 新增的第 42／43 条（用户点单「原初激光蚀刻」＋「原初蜂群铸造」）─────
                PRIMORDIAL_LASER_ETCHING, PRIMORDIAL_SWARM_CASTING,
                // ───── 🆕 2026-10-01 新增的第 44 条（用户点单「原初物质定型」）─────
                PRIMORDIAL_MATTER_FORMING,
                // ───── 🆕 2026-10-03 新增的第 45 条（用户点单「原初山海调试」）─────
                PRIMORDIAL_DEBUG};
    }

    /**
     * 🔴 <b>2026-10-01 新增：两张清单互相钉死（就地 fail-fast）。</b>
     *
     * <p>判据三条，任一不成立就<b>当场抛</b>——因为它们的后果都是"静默少挂一个类型"，
     * 而"静默"正是本工程付过账的那类失败（当天用户报的 JEI 展示槽漏挂就是这个形态）：
     * <ol>
     *   <li>{@link #REGISTERED_TYPES} 条数 == {@link #REAL_TYPE_COUNT}；
     *       <b>不等 ⇒ 有人加了类型却没走 {@code register(...)} 入口</b>（JEI 会漏挂它）或走了入口却没同步常量。</li>
     *   <li>{@link #declaredRealTypes()} 条数 == {@link #REAL_TYPE_COUNT}（原有口径，保持）。</li>
     *   <li>两张表<b>逐条互为子集</b>（按对象身份比）：
     *       声明表里有而自动登记里没有 ⇒ <b>该类型绕过了 {@code register(...)}</b>；
     *       自动登记里有而声明表没有 ⇒ {@code countMissingReal()} 对它静默不查。</li>
     * </ol>
     * <p>⚠️ 本方法<b>只读</b>，不改变任何注册行为；它唯一的作用是"让漏挂变成开局即崩"。
     */
    private static void assertTypeListsConsistent() {
        final GTRecipeType[] declared = declaredRealTypes();
        final List<GTRecipeType> collected = REGISTERED_TYPES;

        if (collected.size() != REAL_TYPE_COUNT) {
            throw new IllegalStateException("[SHANHAI] 配方类型注册口径不一致（自动登记 vs 常量）："
                    + "REGISTERED_TYPES=" + collected.size() + "，REAL_TYPE_COUNT=" + REAL_TYPE_COUNT
                    + "。⇒ 新增类型必须走本文件的 register(name, category) 入口（它才会自动登记）；"
                    + "若确实新增了类型，请同步 REAL_TYPE_COUNT 与 declaredRealTypes()。"
                    + "已登记 = " + collected);
        }
        if (declared.length != REAL_TYPE_COUNT) {
            throw new IllegalStateException("[SHANHAI] 配方类型注册口径不一致（声明表 vs 常量）："
                    + "declaredRealTypes()=" + declared.length + "，REAL_TYPE_COUNT=" + REAL_TYPE_COUNT
                    + "。⇒ 改类型数量必须同时改这两处（否则 fail-fast 只查一部分、其余静默缺失）。");
        }

        final Set<GTRecipeType> declaredSet = Collections.newSetFromMap(new IdentityHashMap<>());
        Collections.addAll(declaredSet, declared);
        for (GTRecipeType t : declared) {
            if (t != null && !containsIdentity(collected, t)) {
                throw new IllegalStateException("[SHANHAI] 配方类型注册口径不一致：声明表里有 "
                        + t.registryName + "，但它不在 REGISTERED_TYPES 里 ⇒ 这个类型绕过了 register(...) 入口，"
                        + "后果 = JEI 的物质模块展示槽会漏挂它（2026-10-01 那个 bug 的同款）。");
            }
        }
        for (GTRecipeType t : collected) {
            if (t != null && !declaredSet.contains(t)) {
                throw new IllegalStateException("[SHANHAI] 配方类型注册口径不一致：REGISTERED_TYPES 里有 "
                        + t.registryName + "，但 declaredRealTypes() 里没有 ⇒ countMissingReal() 对它静默不查。");
            }
        }
    }

    /** 按对象身份（不是 equals）判断 list 里有没有这个类型。 */
    private static boolean containsIdentity(List<GTRecipeType> list, GTRecipeType type) {
        for (GTRecipeType t : list) {
            if (t == type) {
                return true;
            }
        }
        return false;
    }
}
