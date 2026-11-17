package com.shanhai.item;

import com.shanhai.registry.ShanhaiRegistration;
import com.tterrag.registrate.util.entry.ItemEntry;
import com.tterrag.registrate.util.nullness.NonNullBiConsumer;
import net.minecraft.world.item.Item;

/**
 * 阶段 1 的 <b>18 个物品</b> + <b>B1a 批次的 131 个私货物品</b>，全部直接注册进 jar（不经 KubeJS）。
 *
 * <p>🔴 条数历史：18 → 149（2026-09-23 加入 B1a；B1a 原本是 132 条，
 * 减掉必须跳过的 {@code dishanhai:primordial_engine_core} 后为 131 条 —— 原因见下文 B1a 段注释）
 * → 150（{@code worldline_crystal_core}）→ 167（B1b/B1c 的 17 条 gt_shanhai: 物品）
 * → <b>186</b>（<b>2026-10-03 加入 18 个新粒子</b>，同日再加 <b>1 个「创造维度碎片」</b>，见文末两段）
 * → <b>189</b>（<b>2026-10-18 加入 3 个：高级世线晶核 / 奇迹世线晶核 / 物质模块批产模块-MK1</b>）。
 * → <b>284</b>（<b>2026-11-16 加入 95 件「现实/剧情物品」</b>：7 剧情 + 7 大类 + 65 {@code mc_*} + 16 {@code ex_*}，
 *   全部纯 {@link Item}、无行为；见桶 ⑭）。
 * → <b>298</b>（<b>2026-11-16 加入 14 件「底层技术 · 时序与改写」</b>：全是纯 {@link Item}、无行为；见桶 ⑮）。
 * 现有字段数 = {@code module("} 217 + {@code eventInput("} 65 + {@code crashBolt("} 16 = <b>298</b>
 * （另有 128 个 {@code reality_item_N} 壳与 {@code host_pattern_mover} 走独立形状，不计入 298）。
 * ⚠️ 旧版本写的是「可用 {@code Select-String -Pattern 'module\("'} 直接数出来」——
 * 实测 {@code module("} 只数得到 217（另有 65 + 16 走的不是 module），那条口径自 284 那时起就不准。
 *
 * <p>⛔ <b>已作废的旧段落描述（逐字留档，供对照）</b>：
 * <pre>
 *   <h2>🔴 2026-10-03 用户点单：本类<b>整体重排</b>（字段顺序 = 注册顺序；只动顺序，<b>id 一个字符都没改</b>）</h2>
 *   新序 = 6 段（段号即用户给的清单序号；<b>段 1 的"主机 + 27 台模块"不在本类</b> ——
 *   机器由 {@code ShanhaiMachines}/{@code ModuleRegistry} 注册）：
 *   <ol>
 *     <li>（<b>本类无</b>）段 1：主机 + 27 台原初模块 —— 在 {@code ShanhaiMachines}/{@code ModuleRegistry} 注册；</li>
 *     <li><b>段 2：17 个物质模块</b>（新档位 1→17，见 {@code PrimordialModuleMachine.MODULE_LEVELS}）；</li>
 *     <li><b>段 3：世线族</b>（6 个世线本体 + 世线晶核/太初世线之种 + 16 块世线板 + wem 1~4 + 世线残片 7 档 + 寰宇并行超限器）；</li>
 *     <li><b>段 4：粒子</b>（保持原相对顺序，不按能量重排）；</li>
 *     <li><b>段 5：材料 / 容器 / 其余物品</b>（原相对顺序）；</li>
 *     <li><b>段 6：调试与彩蛋</b>（置尾）。</li>
 *   </ol>
 *
 * </pre>
 * <p>⚠️ <b>注册顺序 ≠ 用户在 JEI 里看到的顺序</b>：JEI 的物品表是<b>从创造模式物品栏的 displayItems 建的</b>
 * （取证见 {@code ShanhaiCreativeModeTabs} 的类注释），所以"JEI 里的顺序"由
 * {@code ShanhaiCreativeModeTabs#fill()} 决定 —— 那一份也已在同一次改动里按同一套分组重排。
 * 本类的顺序是<b>注册表数字 id 顺序</b>（另一条独立的可见顺序；机器永远排在最后，
 * 理由见 {@code ShanhaiCreativeModeTabs#fill()} 开头的注释）。
 *
 * <h2>清单（规格 §5.4 + captain 裁定补入第 18 项）</h2>
 * <ul>
 *   <li><b>17 个物质模块</b>（等级 1..17）：物质模块 v1..v17、
 *       {@code reality_anchor_module}、{@code genesis_reality_modification_module}</li>
 *   <li><b>1 个原初引擎核心</b>：{@code primordial_engine_core}
 *       —— 规格 §5.4 的表里漏了它，captain 已裁定归本类</li>
 * </ul>
 *
 * <h2>🔴 id 用英文全名，不用拼音缩写（用户裁定）</h2>
 * 旧 id 是拼音首字母（{@code wzrm}/{@code wzjc}/…），用户要求改成英文名
 * （原话：「帮我把物品id改成其英文名，而不是拼音的首字母缩写，比如说嬗变物质模块id叫做
 * Transformation Material Module」）。构词口径：<b>修饰语 + {@code Material Module}</b>。
 *
 * <p><b>历史映射（仅供对照，规格/旧日志里还是旧 id）</b>：
 * <pre>
 *   wzrm   → introductory_material_module
 *   wzjc   → basic_material_module
 *   wzcz1  → material_deduction_module
 *   wzxc   → virtual_image_material_module
 *   wzsb   → transformation_material_module
 *   wzax   → dark_star_material_module
 *   wzcz2  → material_recombination_module
 *   wzqs   → imaginary_material_transition_remolding_module
 *   wzgl   → zeroing_material_module
 *   wzhy   → apex_material_module
 *   wzsw   → dimensional_ascension_material_module
 *   wzcx   → transfinite_material_module
 *   wzdf   → chaos_material_module
 *   wzyh   → eternal_material_module
 *   wzcz3  → material_creation_module
 *   create_mk → genesis_reality_modification_module
 *   （reality_anchor_module / primordial_engine_core 本来就是英文，未改）
 * </pre>
 *
 * <h2>命名</h2>
 * 全部在 {@code shanhai:} 命名空间下。
 * <b>禁止</b>为任何物品注册旧命名空间的兼容别名（规格 §5.3.1 裁定 a2 / 验收 A16）；
 * 同理<b>也不为旧 id 保留别名</b> —— 旧 id 只存在于历史记录里，不在注册表里。
 *
 * <h2>贴图的"四件套"必须成对（改名时的老坑）</h2>
 * 每个 id 在包里必须有 <b>4 件</b>且名字一致：
 * <ol>
 *   <li>{@code assets/shanhai/textures/item/<id>.png}</li>
 *   <li>{@code assets/shanhai/textures/item/<id>.png.mcmeta}（动画定义；<b>漏了 ⇒ 永远停在第 0 帧</b>）</li>
 *   <li>{@code assets/shanhai/models/item/<id>.json}（内部 {@code layer0} 也必须是同一个 id）</li>
 *   <li>{@code assets/shanhai/lang/zh_cn.json} 里的 {@code item.shanhai.<id>}（<b>漏了 ⇒ GUI 显示裸 key</b>）</li>
 * </ol>
 * 缺任何一件都<b>不报错</b>，只是外观/名字坏掉。18 组全是动画贴图。
 * 模型的静态 JSON 已随包提供，所以这里对每个物品都显式声明
 * {@code .model(NonNullBiConsumer.noop())} ——语义是「datagen 不必生成」。
 *
 * <h2>显示名</h2>
 * 中文来自 {@code assets/shanhai/lang/zh_cn.json}（键 {@code item.shanhai.<id>}），
 * 文本以 S1 私货 {@code 山海的物品注册.js} 的 {@code displayName} 为准（规格 §5.5）；
 * 英文来自 {@code assets/shanhai/lang/en_us.json}（同一组键，值为 id 的 Title Case）。
 * 这里不写 {@code .lang(...)}：那是 datagen 通道，本工程不跑 datagen，写了也不生效。
 */
public final class ShanhaiItems {

    // ==================================================================
    // 段 1b · 机器/方块物品（创造栏里它们跟主机排在最前；本类顺序与创造栏一致）
    // ==================================================================

    /** shanhai:worldline_cracking_hub（世线裂解枢纽）· 贴图 textures/block/multiblock/worldline_cracking_hub/overlay_front.png */
    public static final ItemEntry<Item> WORLDLINE_CRACKING_HUB = module("worldline_cracking_hub");

    /** shanhai:spacetime_wave_matrix（终焉创始现实修改矩阵）· 贴图 textures/block/multiblock/spacetime_wave_matrix/overlay_front.png */
    public static final ItemEntry<Item> SPACETIME_WAVE_MATRIX = module("spacetime_wave_matrix");

    /** shanhai:world_line_stripping_oscillation_generator（世线剥离震荡发生器）· 贴图 textures/block/multiblock/taixu_smelting_furnace/world_line_stripping_oscillation_generator/overlay_front.png */
    public static final ItemEntry<Item> WORLD_LINE_STRIPPING_OSCILLATION_GENERATOR = module("world_line_stripping_oscillation_generator");

    /** shanhai:big_tag_filter_stock_bus（山海特大号标签过滤库存总线）· 贴图 textures/block/casings/big_tag_filter_stock_bus_casing.png */
    public static final ItemEntry<Item> BIG_TAG_FILTER_STOCK_BUS = module("big_tag_filter_stock_bus");

    /** shanhai:maintenance_hatch（终焉聚合枢纽）· 贴图 textures/block/machine/part/cosmic_clean_gravity_maintenance_hatch.png */
    public static final ItemEntry<Item> MAINTENANCE_HATCH = module("maintenance_hatch");

    /** shanhai:nebula_siphon（天界星云零点虹吸枢纽）· 贴图 textures/block/multiblock/nebula_siphon/overlay_front.png */
    public static final ItemEntry<Item> NEBULA_SIPHON = module("nebula_siphon");

    /** shanhai:zero_photon_condenser（零点光子转换器）· 贴图 textures/block/multiblock/zero_photon_condenser/overlay_front.png */
    public static final ItemEntry<Item> ZERO_PHOTON_CONDENSER = module("zero_photon_condenser");

    /** shanhai:black_hole_containment（亚稳态黑洞遏制场）· 贴图 textures/block/multiblock/blackhole_unstable/overlay_front.png */
    public static final ItemEntry<Item> BLACK_HOLE_CONTAINMENT = module("black_hole_containment");

    /** shanhai:tianjie_navigation_tower（天界领航塔）· 贴图 textures/block/casing_transcendent.png · 🔴 原无贴图，复用同族 casing */
    public static final ItemEntry<Item> TIANJIE_NAVIGATION_TOWER = module("tianjie_navigation_tower");

    /** shanhai:ulv_zero_point_conversion（ULV零点转换器）· 贴图 (与同族共用) */
    public static final ItemEntry<Item> ULV_ZERO_POINT_CONVERSION = module("ulv_zero_point_conversion");

    /** shanhai:lv_zero_point_conversion（LV零点转换器）· 贴图 textures/block/machine/zero_point_conversion/overlay_front.png */
    public static final ItemEntry<Item> LV_ZERO_POINT_CONVERSION = module("lv_zero_point_conversion");

    /** shanhai:mv_zero_point_conversion（MV零点转换器）· 贴图 (与同族共用) */
    public static final ItemEntry<Item> MV_ZERO_POINT_CONVERSION = module("mv_zero_point_conversion");

    /** shanhai:ulv_photon_siphon（ULV光子虹吸炉）· 贴图 (与同族共用) */
    public static final ItemEntry<Item> ULV_PHOTON_SIPHON = module("ulv_photon_siphon");

    /** shanhai:lv_photon_siphon（LV光子虹吸炉）· 贴图 textures/block/machine/photon_siphon/overlay_front.png */
    public static final ItemEntry<Item> LV_PHOTON_SIPHON = module("lv_photon_siphon");

    /** shanhai:mv_photon_siphon（MV光子虹吸炉）· 贴图 (与同族共用) */
    public static final ItemEntry<Item> MV_PHOTON_SIPHON = module("mv_photon_siphon");


    // ==================================================================
    // 段 2 · 17 个物质模块（等级 1..17，字段顺序 = 新档位顺序）
    // ⚠️ 字段顺序必须与 PrimordialModuleMachine.MODULE_LEVELS 逐行同序；并行数值在 ShanhaiConcurrencyTables（按物品 id 存）。
    // ==================================================================

    /** 等级 1 · 入门物质模块。 */
    public static final ItemEntry<Item> INTRODUCTORY_MATERIAL_MODULE = module("introductory_material_module");

    /** 等级 2 · 基础物质模块。 */
    public static final ItemEntry<Item> BASIC_MATERIAL_MODULE = module("basic_material_module");

    /** 等级 3 · 物质推演模块（S1 贴图名 {@code wzmk1}）。 */
    public static final ItemEntry<Item> MATERIAL_DEDUCTION_MODULE = module("material_deduction_module");

    /** 等级 4 · 虚像物质模块。 */
    public static final ItemEntry<Item> VIRTUAL_IMAGE_MATERIAL_MODULE = module("virtual_image_material_module");

    /** 等级 5 · 物质重组模块（2026-10-03 由等级 7 改）。 */
    public static final ItemEntry<Item> MATERIAL_RECOMBINATION_MODULE = module("material_recombination_module");

    /** 等级 6 · 归零物质模块（2026-10-03 由等级 9 改）。 */
    public static final ItemEntry<Item> ZEROING_MATERIAL_MODULE = module("zeroing_material_module");

    /** 等级 7 · 暗星物质模块（2026-10-03 由等级 6 改）。 */
    public static final ItemEntry<Item> DARK_STAR_MATERIAL_MODULE = module("dark_star_material_module");

    /** 等级 8 · 虚数物质跃迁重塑模块。 */
    public static final ItemEntry<Item> IMAGINARY_MATERIAL_TRANSITION_REMOLDING_MODULE =
            module("imaginary_material_transition_remolding_module");

    /** 等级 9 · 嬗变物质模块（2026-10-03 由等级 5 改）。 */
    public static final ItemEntry<Item> TRANSFORMATION_MATERIAL_MODULE = module("transformation_material_module");

    /** 等级 10 · 升维物质模块（2026-10-03 由等级 11 改）。 */
    public static final ItemEntry<Item> DIMENSIONAL_ASCENSION_MATERIAL_MODULE =
            module("dimensional_ascension_material_module");

    /** 等级 11 · 巅峰物质模块（2026-10-03 由等级 10 改）。 */
    public static final ItemEntry<Item> APEX_MATERIAL_MODULE = module("apex_material_module");

    /** 等级 12 · 混沌物质模块（2026-10-03 由等级 13 改）。 */
    public static final ItemEntry<Item> CHAOS_MATERIAL_MODULE = module("chaos_material_module");

    /** 等级 13 · 超限物质模块（2026-10-03 由等级 12 改）。 */
    public static final ItemEntry<Item> TRANSFINITE_MATERIAL_MODULE = module("transfinite_material_module");

    /** 等级 14 · 永恒物质模块。 */
    public static final ItemEntry<Item> ETERNAL_MATERIAL_MODULE = module("eternal_material_module");

    /** 等级 15 · 物质创造模块（S1 贴图名 {@code wzmk3}）。 */
    public static final ItemEntry<Item> MATERIAL_CREATION_MODULE = module("material_creation_module");

    /** 等级 16 · 现实锚点模块（id 本来就是英文，未改）。 */
    public static final ItemEntry<Item> REALITY_ANCHOR_MODULE = module("reality_anchor_module");

    /** 等级 17 · 创始现实修改模块（S1 贴图名 {@code czmk}）。 */
    public static final ItemEntry<Item> GENESIS_REALITY_MODIFICATION_MODULE =
            module("genesis_reality_modification_module");


    // ==================================================================
    // 段 3 · 世线族（🔴 冻结，逐位未变）
    // ==================================================================

    /** dishanhai:dimensional_worldline_fragment（&$aurora-维度世线碎片）· 贴图 dimensional_worldline_fragment.png */
    public static final ItemEntry<Item> DIMENSIONAL_WORLDLINE_FRAGMENT = module("dimensional_worldline_fragment");

    /** dishanhai:worldline_residual_fragment（&$crimson-余振世线碎片）· 贴图 worldline_residual_fragment.png */
    public static final ItemEntry<Item> WORLDLINE_RESIDUAL_FRAGMENT = module("worldline_residual_fragment");

    /** dishanhai:worldline_divergent_core（&$magic-分歧世线凝核）· 贴图 worldline_divergent_core.png */
    public static final ItemEntry<Item> WORLDLINE_DIVERGENT_CORE = module("worldline_divergent_core");

    /** dishanhai:worldline_boundless_singularity（&$ultimateRainbow-无界世线奇点）· 贴图 worldline_boundless_singularity.png */
    public static final ItemEntry<Item> WORLDLINE_BOUNDLESS_SINGULARITY = module("worldline_boundless_singularity");

    /** dishanhai:worldline_imaginary_string（&$neon-虚数世线虚弦）· 贴图 worldline_imaginary_string.png */
    public static final ItemEntry<Item> WORLDLINE_IMAGINARY_STRING = module("worldline_imaginary_string");

    /** dishanhai:worldline_genesis_embryo（&$golden-始世线创世胚）· 贴图 worldline_genesis_embryo.png */
    public static final ItemEntry<Item> WORLDLINE_GENESIS_EMBRYO = module("worldline_genesis_embryo");

    /**
     * 🟢 2026-09-26 用户点单新增：<b>世线晶核 worldline_crystal_core</b>。
     *
     * <p>用途：给他新的两步世线链当【中间产物】——
     *   ① 世线采样（worldline_sampling）⇒ 出"未定样本"
     *   ② 世线震荡收集（worldline_oscillation_collection）⇒ 直接出 dimensional_worldline_fragment
     *   新模块「世线裂解枢纽」（worldline_cracking_hub）跑【世线采样】。
     *
     * <p>🔴 <b>贴图待用户裁定</b>（用户 2026-09-26 正在选）。模型 json 已预留槽位、
     *    layer0 指向本 id 自己（shanhai:item/worldline_crystal_core）——
     *    用户定了之后，只需把选中的那张 png 复制成
     *    assets/shanhai/textures/item/worldline_crystal_core.png 即可，<b>代码不用改</b>。
     *    ⚠️ 在那之前该物品会显示为缺贴图（不会崩）。
     */
    public static final ItemEntry<Item> WORLDLINE_CRYSTAL_CORE = module("worldline_crystal_core");

    /** 🟢 2026-10-18 用户点单新增：<b>高级世线晶核</b>（&$electric-高级世线晶核）· 图标随 jar 提供（textures/item/advanced_worldline_crystal_core.png）。 */
    public static final ItemEntry<Item> ADVANCED_WORLDLINE_CRYSTAL_CORE = module("advanced_worldline_crystal_core");

    /** 🟢 2026-10-18 用户点单新增：<b>奇迹世线晶核</b>（&$ultimateRainbow-奇迹世线晶核）· 图标随 jar 提供（textures/item/miracle_worldline_crystal_core.png）。 */
    public static final ItemEntry<Item> MIRACLE_WORLDLINE_CRYSTAL_CORE = module("miracle_worldline_crystal_core");

    /** dishanhai:primordial_worldline_seed（&$ultimate-太初世线之种）· 贴图 bagua_animated.png */
    public static final ItemEntry<Item> PRIMORDIAL_WORLDLINE_SEED = module("primordial_worldline_seed");

    /** dishanhai:wl_board_ulv（§7世线胚芽板）· 贴图 wl_board_ulv.png */
    public static final ItemEntry<Item> WL_BOARD_ULV = module("wl_board_ulv");

    /** dishanhai:wl_board_lv（§a世线分枝板）· 贴图 wl_board_lv.png */
    public static final ItemEntry<Item> WL_BOARD_LV = module("wl_board_lv");

    /** dishanhai:wl_board_mv（§9世线织络板）· 贴图 wl_board_mv.png */
    public static final ItemEntry<Item> WL_BOARD_MV = module("wl_board_mv");

    /** dishanhai:wl_board_hv（§6世线共鸣板）· 贴图 wl_board_hv.png */
    public static final ItemEntry<Item> WL_BOARD_HV = module("wl_board_hv");

    /** dishanhai:wl_board_ev（§6世线因果板）· 贴图 wl_board_ev.png */
    public static final ItemEntry<Item> WL_BOARD_EV = module("wl_board_ev");

    /** dishanhai:wl_board_iv（§5世线归零板）· 贴图 wl_board_iv.png */
    public static final ItemEntry<Item> WL_BOARD_IV = module("wl_board_iv");

    /** dishanhai:wl_board_luv（§5世线奇点板）· 贴图 wl_board_luv.png */
    public static final ItemEntry<Item> WL_BOARD_LUV = module("wl_board_luv");

    /** dishanhai:wl_board_zpm（§b世线跃迁板）· 贴图 wl_board_zpm.png */
    public static final ItemEntry<Item> WL_BOARD_ZPM = module("wl_board_zpm");

    /** dishanhai:wl_board_uv（§d世线创世板）· 贴图 wl_board_uv.png */
    public static final ItemEntry<Item> WL_BOARD_UV = module("wl_board_uv");

    /** dishanhai:wl_board_uhv（§4世线超验板）· 贴图 wl_board_uhv.png */
    public static final ItemEntry<Item> WL_BOARD_UHV = module("wl_board_uhv");

    /** dishanhai:wl_board_uev（§4世线管理裁决板）· 贴图 wl_board_uev.png */
    public static final ItemEntry<Item> WL_BOARD_UEV = module("wl_board_uev");

    /** dishanhai:wl_board_uiv（§1世线无相板）· 贴图 wl_board_uiv.png */
    public static final ItemEntry<Item> WL_BOARD_UIV = module("wl_board_uiv");

    /** dishanhai:wl_board_uxv（§8世线太初板）· 贴图 wl_board_uxv.png */
    public static final ItemEntry<Item> WL_BOARD_UXV = module("wl_board_uxv");

    /** dishanhai:wl_board_opv（§f世线永恒板）· 贴图 wl_board_opv.png */
    public static final ItemEntry<Item> WL_BOARD_OPV = module("wl_board_opv");

    /** dishanhai:wl_board_max（§6§l世线终焉板）· 贴图 wl_board_max.png */
    public static final ItemEntry<Item> WL_BOARD_MAX = module("wl_board_max");

    /** dishanhai:wl_board_eternal（§f§l世线永恒裁决板）· 贴图 wl_board_eternal.png */
    public static final ItemEntry<Item> WL_BOARD_ETERNAL = module("wl_board_eternal");

    /** dishanhai:wem_1（§7世线蚀刻矩阵·奇点 [ULV-HV]）· 贴图 wem_1.png */
    public static final ItemEntry<Item> WEM_1 = module("wem_1");

    /** dishanhai:wem_2（§5世线蚀刻矩阵·脉冲 [EV-ZPM]）· 贴图 wem_2.png */
    public static final ItemEntry<Item> WEM_2 = module("wem_2");

    /** dishanhai:wem_3（§4世线蚀刻矩阵·共振 [UV-UXV]）· 贴图 wem_3.png */
    public static final ItemEntry<Item> WEM_3 = module("wem_3");

    /** dishanhai:wem_4（§6§l世线蚀刻矩阵·超限 [MAX-ET]）· 贴图 wem_4.png */
    public static final ItemEntry<Item> WEM_4 = module("wem_4");

    /** dishanhai:thread_shard_1（&$gray-世线残片·初醒）· 贴图 thread_shard_1.png */
    public static final ItemEntry<Item> THREAD_SHARD_1 = module("thread_shard_1");

    /** dishanhai:thread_shard_2（&$green-世线残片·共鸣）· 贴图 thread_shard_2.png */
    public static final ItemEntry<Item> THREAD_SHARD_2 = module("thread_shard_2");

    /** dishanhai:thread_shard_3（&$water-世线残片·跃迁）· 贴图 thread_shard_3.png */
    public static final ItemEntry<Item> THREAD_SHARD_3 = module("thread_shard_3");

    /** dishanhai:thread_shard_4（&$magic-世线残片·超越）· 贴图 thread_shard_4.png */
    public static final ItemEntry<Item> THREAD_SHARD_4 = module("thread_shard_4");

    /** dishanhai:thread_shard_5（&$golden-世线残片·统合）· 贴图 thread_shard_5.png */
    public static final ItemEntry<Item> THREAD_SHARD_5 = module("thread_shard_5");

    /** dishanhai:thread_shard_6（&$ultimateRainbow-世线残片·归一）· 贴图 thread_shard_6.png */
    public static final ItemEntry<Item> THREAD_SHARD_6 = module("thread_shard_6");

    /** dishanhai:thread_shard_7（&$crimson-世线残片·裁决）· 贴图 wzcj.png */
    public static final ItemEntry<Item> THREAD_SHARD_7 = module("thread_shard_7");

    /** dishanhai:universal_parallel_overdriver（&$ultimateRainbow-寰宇并行超限器）· 贴图 universal_parallel_overdriver.png */
    public static final ItemEntry<Item> UNIVERSAL_PARALLEL_OVERDRIVER = module("universal_parallel_overdriver");


    // ==================================================================
    // 段 4 · 粒子（🔴 冻结；FIRST_LIGHT 已按 ⑥ 逐光 5 件移出）
    // ==================================================================

    /** dishanhai:photon（§e光§f子）· 贴图 photon.png */
    public static final ItemEntry<Item> PHOTON = module("photon");

    /** dishanhai:hydrogen_ion（氢离子）· 贴图 hydrogen_ion.png */
    public static final ItemEntry<Item> HYDROGEN_ION = module("hydrogen_ion");

    /** dishanhai:helium_ion（氦离子）· 贴图 helium_ion.png */
    public static final ItemEntry<Item> HELIUM_ION = module("helium_ion");

    /** dishanhai:graviton（引力子）· 贴图 graviton.png */
    public static final ItemEntry<Item> GRAVITON = module("graviton");

    /** dishanhai:up_quark（上(u)夸克）· 贴图 up_quark.png */
    public static final ItemEntry<Item> UP_QUARK = module("up_quark");

    /** dishanhai:down_quark（下(d)夸克）· 贴图 down_quark.png */
    public static final ItemEntry<Item> DOWN_QUARK = module("down_quark");

    /** dishanhai:charm_quark（粲(c)夸克）· 贴图 charm_quark.png */
    public static final ItemEntry<Item> CHARM_QUARK = module("charm_quark");

    /** dishanhai:strange_quark（奇(s)夸克）· 贴图 strange_quark.png */
    public static final ItemEntry<Item> STRANGE_QUARK = module("strange_quark");

    /** dishanhai:bottom_quark（底(d)夸克）· 贴图 bottom_quark.png */
    public static final ItemEntry<Item> BOTTOM_QUARK = module("bottom_quark");

    /** dishanhai:top_quark（顶(t)夸克）· 贴图 top_quark.png */
    public static final ItemEntry<Item> TOP_QUARK = module("top_quark");

    /** dishanhai:electron（电子）· 贴图 electron.png */
    public static final ItemEntry<Item> ELECTRON = module("electron");

    /** dishanhai:electron_neutrino（电子中微子）· 贴图 electron_neutrino.png */
    public static final ItemEntry<Item> ELECTRON_NEUTRINO = module("electron_neutrino");

    /** dishanhai:muon（μ子）· 贴图 muon.png */
    public static final ItemEntry<Item> MUON = module("muon");

    /** dishanhai:muon_neutrino（μ子中微子）· 贴图 muon_neutrino.png */
    public static final ItemEntry<Item> MUON_NEUTRINO = module("muon_neutrino");

    /** dishanhai:tau（τ子）· 贴图 tau.png */
    public static final ItemEntry<Item> TAU = module("tau");

    /** dishanhai:tau_neutrino（τ子中微子）· 贴图 tau_neutrino.png */
    public static final ItemEntry<Item> TAU_NEUTRINO = module("tau_neutrino");

    /** dishanhai:gluon（胶子）· 贴图 gluon.png */
    public static final ItemEntry<Item> GLUON = module("gluon");

    /** dishanhai:photon_rainbow（光子）· 贴图 photon_rainbow.png */
    public static final ItemEntry<Item> PHOTON_RAINBOW = module("photon_rainbow");

    /** dishanhai:z_boson（Z玻色子）· 贴图 z_boson.png */
    public static final ItemEntry<Item> Z_BOSON = module("z_boson");

    /** dishanhai:w_boson（W玻色子）· 贴图 w_boson.png */
    public static final ItemEntry<Item> W_BOSON = module("w_boson");

    /** dishanhai:higgs_boson（希格斯玻色子）· 贴图 higgs_boson.png */
    public static final ItemEntry<Item> HIGGS_BOSON = module("higgs_boson");

    /** dishanhai:proton（质子）· 贴图 proton.png */
    public static final ItemEntry<Item> PROTON = module("proton");

    /** dishanhai:neutron（中子）· 贴图 neutron.png */
    public static final ItemEntry<Item> NEUTRON = module("neutron");

    /** dishanhai:lambda_particle（λ粒子）· 贴图 lambda_particle.png */
    public static final ItemEntry<Item> LAMBDA_PARTICLE = module("lambda_particle");

    /** dishanhai:omega_particle（Ω粒子）· 贴图 omega_particle.png */
    public static final ItemEntry<Item> OMEGA_PARTICLE = module("omega_particle");

    /** dishanhai:pion（π介子）· 贴图 pion.png */
    public static final ItemEntry<Item> PION = module("pion");

    /** dishanhai:eta_meson（η介子）· 贴图 eta_meson.png */
    public static final ItemEntry<Item> ETA_MESON = module("eta_meson");

    /** dishanhai:unknown_particle（未知粒子）· 贴图 unknown_particle.png */
    public static final ItemEntry<Item> UNKNOWN_PARTICLE = module("unknown_particle");

    /** shanhai:positron（正电子）· lang 样式 {@code &$electric-}（电子族）。 */
    public static final ItemEntry<Item> POSITRON = module("positron");

    /** shanhai:antiproton（反质子）· lang 样式 {@code &$magic-}（质子族）。 */
    public static final ItemEntry<Item> ANTIPROTON = module("antiproton");

    /** shanhai:antihydrogen（反氢）· lang 样式 {@code &$water-}（氢离子族）。 */
    public static final ItemEntry<Item> ANTIHYDROGEN = module("antihydrogen");

    /** shanhai:positronium（正电子素）· lang 样式 {@code &$electric-}（电子族）。 */
    public static final ItemEntry<Item> POSITRONIUM = module("positronium");

    /** shanhai:muonium（μ子素）· lang 样式 {@code &$electric-}（μ子族）。 */
    public static final ItemEntry<Item> MUONIUM = module("muonium");

    /** shanhai:kaon（K 介子）· lang 样式 {@code &$aurora-}（介子族）。 */
    public static final ItemEntry<Item> KAON = module("kaon");

    /** shanhai:j_psi_meson（J/ψ 介子（粲偶素））· lang 样式 {@code &$aurora-}（介子族）。 */
    public static final ItemEntry<Item> J_PSI_MESON = module("j_psi_meson");

    /** shanhai:upsilon_meson（Υ 介子（底偶素））· lang 样式 {@code &$aurora-}（介子族）。 */
    public static final ItemEntry<Item> UPSILON_MESON = module("upsilon_meson");

    /** shanhai:sigma_baryon（Σ 超子）· lang 样式 {@code &$magic-}（重子族）。 */
    public static final ItemEntry<Item> SIGMA_BARYON = module("sigma_baryon");

    /** shanhai:xi_baryon（Ξ 超子）· lang 样式 {@code &$magic-}（重子族）。 */
    public static final ItemEntry<Item> XI_BARYON = module("xi_baryon");

    /** shanhai:lambda_c_baryon（Λc 重子）· lang 样式 {@code &$magic-}（重子族）。 */
    public static final ItemEntry<Item> LAMBDA_C_BARYON = module("lambda_c_baryon");

    /** shanhai:lambda_b_baryon（Λb 重子）· lang 样式 {@code &$magic-}（重子族）。 */
    public static final ItemEntry<Item> LAMBDA_B_BARYON = module("lambda_b_baryon");

    /** shanhai:tetraquark（四夸克态）· lang 样式 {@code &$crimson-}（夸克族）。 */
    public static final ItemEntry<Item> TETRAQUARK = module("tetraquark");

    /** shanhai:pentaquark（五夸克态）· lang 样式 {@code &$crimson-}（夸克族）。 */
    public static final ItemEntry<Item> PENTAQUARK = module("pentaquark");

    /** shanhai:strangelet（奇异物质）· lang 样式 {@code &$crimson-}（奇夸克族）。 */
    public static final ItemEntry<Item> STRANGELET = module("strangelet");

    /** shanhai:quark_gluon_plasma（夸克-胶子等离子体）· lang 样式 {@code &$crimson-}（夸克族）。 */
    public static final ItemEntry<Item> QUARK_GLUON_PLASMA = module("quark_gluon_plasma");

    /** shanhai:magnetic_monopole（磁单极子）· lang 样式 {@code &$aurora-}（玻色子族）。 */
    public static final ItemEntry<Item> MAGNETIC_MONOPOLE = module("magnetic_monopole");

    /** shanhai:axion（轴子）· lang 样式 {@code &$aurora-}（玻色子族）。 */
    public static final ItemEntry<Item> AXION = module("axion");


    // ==================================================================
    // 桶 ⑤ · 夸克催化剂（2026-10-03 用户点单）
    // ==================================================================

    /** dishanhai:up_quark_emission_catalyst（上-夸克释放催化剂）· 贴图 up_quark_emission_catalyst.png */
    public static final ItemEntry<Item> UP_QUARK_EMISSION_CATALYST = module("up_quark_emission_catalyst");

    /** dishanhai:down_quark_emission_catalyst（下-夸克释放催化剂）· 贴图 down_quark_emission_catalyst.png */
    public static final ItemEntry<Item> DOWN_QUARK_EMISSION_CATALYST = module("down_quark_emission_catalyst");

    /** dishanhai:strange_quark_emission_catalyst（奇-夸克释放催化剂）· 贴图 ange_quark_emission_catalyst.png */
    public static final ItemEntry<Item> STRANGE_QUARK_EMISSION_CATALYST = module("strange_quark_emission_catalyst");

    /** dishanhai:charm_quark_emission_catalyst（粲-夸克释放催化剂）· 贴图 charm_quark_emission_catalyst.png */
    public static final ItemEntry<Item> CHARM_QUARK_EMISSION_CATALYST = module("charm_quark_emission_catalyst");

    /** dishanhai:top_quark_emission_catalyst（顶-夸克释放催化剂）· 贴图 top_quark_emission_catalyst.png */
    public static final ItemEntry<Item> TOP_QUARK_EMISSION_CATALYST = module("top_quark_emission_catalyst");

    /** dishanhai:bottom_quark_emission_catalyst（底-夸克释放催化剂）· 贴图 bottom_quark_emission_catalyst.png */
    public static final ItemEntry<Item> BOTTOM_QUARK_EMISSION_CATALYST = module("bottom_quark_emission_catalyst");

    /** dishanhai:casing_empty_quark_emission_catalyst（空夸克释放催化剂外壳）· 贴图 casing_empty_quark_emission_catalyst.png */
    public static final ItemEntry<Item> CASING_EMPTY_QUARK_EMISSION_CATALYST = module("casing_empty_quark_emission_catalyst");

    /** dishanhai:misaligned_quark_emission_catalyst（非对齐夸克释放催化剂）· 贴图 misaligned_quark_emission_catalyst.png */
    public static final ItemEntry<Item> MISALIGNED_QUARK_EMISSION_CATALYST = module("misaligned_quark_emission_catalyst");


    // ==================================================================
    // 桶 ⑥ · 逐光 5 件（顺序铁律）（2026-10-03 用户点单）
    // ==================================================================

    /** dishanhai:first_light（初光）· 贴图 first_light.png */
    public static final ItemEntry<Item> FIRST_LIGHT = module("first_light");

    /** dishanhai:navigate_prism（导航棱镜）· 贴图 navigate_prism.png */
    public static final ItemEntry<Item> NAVIGATE_PRISM = module("navigate_prism");

    /** dishanhai:light_voyage（逐光启航）· 贴图 light_voyage.png */
    public static final ItemEntry<Item> LIGHT_VOYAGE = module("light_voyage");

    /** dishanhai:star_spark（星火意志）· 贴图 star_spark.png */
    public static final ItemEntry<Item> STAR_SPARK = module("star_spark");

    /** dishanhai:blue_son（蓝星之子）· 贴图 blue_son.png */
    public static final ItemEntry<Item> BLUE_SON = module("blue_son");


    // ==================================================================
    // 桶 ⑦ · 5 个结局（2026-10-03 用户点单）
    // ==================================================================

    /** dishanhai:collapse_tear（万物崩灭·大撕裂）· 贴图 collapse_tear.png */
    public static final ItemEntry<Item> COLLAPSE_TEAR = module("collapse_tear");

    /** dishanhai:big_tear（&$ultimate-逆向坍缩·大反冲）· 贴图 trar.png */
    public static final ItemEntry<Item> BIG_TEAR = module("big_tear");

    /** dishanhai:csj（&$ultimate-万态平衡·大冻结·创世纪）· 贴图 csj.png */
    public static final ItemEntry<Item> CSJ = module("csj");

    /** dishanhai:bridge_and_gate（桥与门）· 贴图 bridge_and_gate.png */
    public static final ItemEntry<Item> BRIDGE_AND_GATE = module("bridge_and_gate");

    /** dishanhai:gate_and_bridg（门与桥）· 贴图 gate_and_bridg.png */
    public static final ItemEntry<Item> GATE_AND_BRIDG = module("gate_and_bridg");


    // ==================================================================
    // 桶 ⑧ · 太虚与终末线（2026-10-03 用户点单）
    // ==================================================================

    /** dishanhai:taixu_crystal_core（&$electric-太虚晶核）· 贴图 taixu_crystal_core.png */
    public static final ItemEntry<Item> TAIXU_CRYSTAL_CORE = module("taixu_crystal_core");

    /** dishanhai:taixu_liquid_droplet（&$water-太虚液滴）· 贴图 taixu_liquid_droplet.png */
    public static final ItemEntry<Item> TAIXU_LIQUID_DROPLET = module("taixu_liquid_droplet");

    /** dishanhai:taixu_dust（&$body_silver-太虚尘埃）· 贴图 taixu_dust.png */
    public static final ItemEntry<Item> TAIXU_DUST = module("taixu_dust");

    /** dishanhai:beyond_taixu_thread（&$aurora-太虚之上的丝线）· 贴图 beyond_taixu_thread.png */
    public static final ItemEntry<Item> BEYOND_TAIXU_THREAD = module("beyond_taixu_thread");

    /** dishanhai:prologue_of_the_end（&$magic-终末之序章）· 贴图 prologue_of_the_end.png */
    public static final ItemEntry<Item> PROLOGUE_OF_THE_END = module("prologue_of_the_end");

    /** dishanhai:halo_end（&$ultimate-终末之环）· 贴图 halo_end.png */
    public static final ItemEntry<Item> HALO_END = module("halo_end");

    /** dishanhai:finality_certificate（&$ultimate-终末之证）· 贴图 finality_certificate.png */
    public static final ItemEntry<Item> FINALITY_CERTIFICATE = module("finality_certificate");

    /** dishanhai:ideal_ashes（&$golden-空想余烬）· 贴图 ideal_ashes.png */
    public static final ItemEntry<Item> IDEAL_ASHES = module("ideal_ashes");

    /** dishanhai:genesis_shard（创世碎片）· 贴图 genesis_shard.png */
    public static final ItemEntry<Item> GENESIS_SHARD = module("genesis_shard");

    /** dishanhai:central_finite_curve（&$magic-中央有限曲线）· 贴图 central_finite_curve.png */
    public static final ItemEntry<Item> CENTRAL_FINITE_CURVE = module("central_finite_curve");


    // ==================================================================
    // 桶 ⑩ · 彩蛋物品（2026-10-03 用户点单）
    // ==================================================================

    /** dishanhai:blue_alien（§9蓝色外星人）· 贴图 blue_alien.png */
    public static final ItemEntry<Item> BLUE_ALIEN = module("blue_alien");

    /** dishanhai:ku_ming_yuan_yang（§d苦命鸳鸯）· 贴图 ku_ming_yuan_yang.png */
    public static final ItemEntry<Item> KU_MING_YUAN_YANG = module("ku_ming_yuan_yang");

    /** dishanhai:long_zui（§6长醉）· 贴图 cz.png */
    public static final ItemEntry<Item> LONG_ZUI = module("long_zui");

    /** dishanhai:piggy（JS 函数计算，源码无字面量（函数内是"创始·猪咪"））· 贴图 piggy.png */
    public static final ItemEntry<Item> PIGGY = module("piggy");

    /** dishanhai:food（寰宇零食）· 贴图 food_byd.png */
    public static final ItemEntry<Item> FOOD = module("food");

    /** dishanhai:dishanhai（帝山海）· 贴图 dishanhai.png */
    public static final ItemEntry<Item> DISHANHAI = module("dishanhai");

    /** dishanhai:platinum_god_proof（§6铂系之神的证明）· 贴图 platinum_god_proof.png */
    public static final ItemEntry<Item> PLATINUM_GOD_PROOF = module("platinum_god_proof");

    /** dishanhai:wanxiang_core（§d七十二变·万象心法核心）· 贴图 wanxiang_core.png */
    public static final ItemEntry<Item> WANXIANG_CORE = module("wanxiang_core");

    /** shanhai:guide_book（山海的神人私货引索）· 贴图 textures/item/guide_book.png */
    public static final ItemEntry<Item> GUIDE_BOOK = module("guide_book");

    /** dishanhai:fishbig_shards（鱼大碎片）· 贴图 fishbig_shards.png */
    public static final ItemEntry<Item> FISHBIG_SHARDS = module("fishbig_shards");

    /** dishanhai:zwf（占位符）· 贴图 zwf.png */
    public static final ItemEntry<Item> ZWF = module("zwf");

    /** dishanhai:test_item（&$ultimate-测试物品）· 贴图 香草 barrier */
    public static final ItemEntry<Item> TEST_ITEM = module("test_item");

    /** dishanhai:test_dynamic_text（§e山海动态文本测试）· 贴图 hxsp.png */
    public static final ItemEntry<Item> TEST_DYNAMIC_TEXT = module("test_dynamic_text");


    // ==================================================================
    // 桶 ⑪ · 其余物品（放最后；顺序 = 改前的相对顺序）
    // ==================================================================

    /** 原初引擎核心（不在 §5.4 的 17 项表内，captain 裁定补入；id 本来就是英文，未改）。 */
    public static final ItemEntry<Item> PRIMORDIAL_ENGINE_CORE = module("primordial_engine_core");

    /** dishanhai:annihilation_core（湮灭核心）· 贴图 annihilation_core.png */
    public static final ItemEntry<Item> ANNIHILATION_CORE = module("annihilation_core");

    /** dishanhai:bhd_collapser（黑洞坍缩器）· 贴图 black_hole_collapser.png */
    public static final ItemEntry<Item> BHD_COLLAPSER = module("bhd_collapser");

    /** dishanhai:bhd_hyper_seed（超稳态黑洞种子）· 贴图 hyperstable_black_hole_seed.png */
    public static final ItemEntry<Item> BHD_HYPER_SEED = module("bhd_hyper_seed");

    /** dishanhai:cosmic_dust（宇宙尘埃）· 贴图 cosmic_dust.png */
    public static final ItemEntry<Item> COSMIC_DUST = module("cosmic_dust");

    /** dishanhai:cshx（§2原§1始§3恒§4星§k111）· 贴图 yshx.png */
    public static final ItemEntry<Item> CSHX = module("cshx");

    /** dishanhai:dark_energy_multiplier（暗能量倍增器）· 贴图 dark_energy_multiplier.png */
    public static final ItemEntry<Item> DARK_ENERGY_MULTIPLIER = module("dark_energy_multiplier");

    /** dishanhai:dimensional_frame（维度框架）· 贴图 dimensional_frame.png */
    public static final ItemEntry<Item> DIMENSIONAL_FRAME = module("dimensional_frame");

    /** dishanhai:dimensional_matrix（维度矩阵）· 贴图 dimensional_matrix.png */
    public static final ItemEntry<Item> DIMENSIONAL_MATRIX = module("dimensional_matrix");

    /** dishanhai:gravitational_lens（§5引力扭曲透镜）· 贴图 gravitational_lens.png */
    public static final ItemEntry<Item> GRAVITATIONAL_LENS = module("gravitational_lens");

    /** dishanhai:hxsp（恒星碎片）· 贴图 hxsp.png */
    public static final ItemEntry<Item> HXSP = module("hxsp");

    /** dishanhai:hyperdimensional_calibration_matrix（超维校准矩阵）· 贴图 hyperdimensional_calibration_matrix.png */
    public static final ItemEntry<Item> HYPERDIMENSIONAL_CALIBRATION_MATRIX = module("hyperdimensional_calibration_matrix");

    /** dishanhai:nova_catalyst（新星催化剂）· 贴图 nova_catalyst.png */
    public static final ItemEntry<Item> NOVA_CATALYST = module("nova_catalyst");

    /** dishanhai:primordial_parallel_particle（&$ultimate-太初并行子）· 贴图 parallel_particle_animated.png */
    public static final ItemEntry<Item> PRIMORDIAL_PARALLEL_PARTICLE = module("primordial_parallel_particle");

    /** dishanhai:reality_core（现实核心）· 贴图 reality_core.png */
    public static final ItemEntry<Item> REALITY_CORE = module("reality_core");

    /** dishanhai:singularity_ring（奇点环）· 贴图 singularity_ring.png */
    public static final ItemEntry<Item> SINGULARITY_RING = module("singularity_ring");

    /** dishanhai:soc（§9创§2始§3s§4o§8c§7晶§6圆）· 贴图 soc.png */
    public static final ItemEntry<Item> SOC = module("soc");

    /** dishanhai:artificial_neutron_star（§c人造中子星）· 贴图 artificial_neutron_star.png */
    public static final ItemEntry<Item> ARTIFICIAL_NEUTRON_STAR = module("artificial_neutron_star");

    /** dishanhai:cosmic_probe_mk（MK1—宇宙探测器）· 贴图 cosmic_probe_mk.png */
    public static final ItemEntry<Item> COSMIC_PROBE_MK = module("cosmic_probe_mk");

    /** dishanhai:god_forge_mod（&$ultimate-神锻恒星终焉模块）· 贴图 god_forge_mod.png */
    public static final ItemEntry<Item> GOD_FORGE_MOD = module("god_forge_mod");

    /** dishanhai:gravitational_antenna（§6引力波发生天线）· 贴图 gravitational_antenna.png */
    public static final ItemEntry<Item> GRAVITATIONAL_ANTENNA = module("gravitational_antenna");

    /** dishanhai:gravitational_medium（§d引力波介质）· 贴图 gravitational_medium.png */
    public static final ItemEntry<Item> GRAVITATIONAL_MEDIUM = module("gravitational_medium");

    /** dishanhai:gravitational_vibration_string（§5引力波振动弦）· 贴图 gravitational_vibration_string.png */
    public static final ItemEntry<Item> GRAVITATIONAL_VIBRATION_STRING = module("gravitational_vibration_string");

    /** dishanhai:judgment_limiter（&$crimson-裁决限制器）· 贴图 judgment_limiter.png */
    public static final ItemEntry<Item> JUDGMENT_LIMITER = module("judgment_limiter");

    /** dishanhai:matter_singularity（&$ultimate-物质奇点）· 贴图 matter_singularity.png */
    public static final ItemEntry<Item> MATTER_SINGULARITY = module("matter_singularity");

    /** dishanhai:primordial_divergence_heart（&$crimson-原初分歧之心）· 贴图 primordial_divergence_heart.png */
    public static final ItemEntry<Item> PRIMORDIAL_DIVERGENCE_HEART = module("primordial_divergence_heart");

    /** dishanhai:strong_interaction_droplet（§b§l强相互作用水滴）· 贴图 strong_interaction_droplet.png */
    public static final ItemEntry<Item> STRONG_INTERACTION_DROPLET = module("strong_interaction_droplet");

    /** dishanhai:time_reversal_protocol（世线信标（+静态随机配色））· 贴图 time.png */
    public static final ItemEntry<Item> TIME_REVERSAL_PROTOCOL = module("time_reversal_protocol");

    /** dishanhai:universal_parallel_core（&$electric-寰宇并行核心）· 贴图 universal_parallel_core.png */
    public static final ItemEntry<Item> UNIVERSAL_PARALLEL_CORE = module("universal_parallel_core");

    /** shanhai:graviton_shard（引力子碎片）· 贴图 textures/item/graviton_shard.png */
    public static final ItemEntry<Item> GRAVITON_SHARD = module("graviton_shard");

    /** shanhai:world_fragments_creation（创造维度碎片）· 贴图 textures/item/world_fragments_creation.png */
    public static final ItemEntry<Item> WORLD_FRAGMENTS_CREATION = module("world_fragments_creation");

    /** 🟢 2026-10-18 用户点单新增：<b>物质模块批产模块-MK1</b>（&$golden-物质模块批产模块-MK1，本段末尾）· 图标随 jar 提供（textures/item/material_module_mass_production_module_mk1.png）。 */
    public static final ItemEntry<Item> MATERIAL_MODULE_MASS_PRODUCTION_MODULE_MK1 =
            module("material_module_mass_production_module_mk1");

    // ==================================================================
    // 桶 ⑫ · 🆕 2026-10-17「自己新增物品」的 128 个占位壳（用户点单）
    //    🔴 id = shanhai:reality_item_1 … _128（1 基，槽位号 = 后缀号）
    //    🔴 必须在【加载期】注册（1.20.1 注册表冻结后造不出新物品，也没有 DataComponents）
    //    ⇒ 运行期只改"长相"（显示名/图标/堆叠/稀有度/提示），改的是
    //       com.shanhai.common.reality.ShanhaiRealityItemTable 那张表，不是物品本身。
    //    ⚠️ 这一段【必须放在最后一个字段组】，让既有物品的数字 id 一个都不移位（存档安全）。
    //    ⚠️ 未填名字的槽位【不进创造栏】⇒ JEI 也搜不到（见 ShanhaiCreativeModeTabs#fill）。
    // ==================================================================

    /** 128 个现实物品壳（下标 0 基；对外 id 后缀 = 下标 + 1）。 */
    @SuppressWarnings("unchecked")
    public static final ItemEntry<RealityShellItem>[] REALITY_ITEMS = realityItems();

    // ==================================================================
    // 桶 ⑬ · 🆕 2026-11-06：主机样板搬运工具（用户点单，第一刀）
    //    🔴 为什么它【必须排在 128 个现实物品壳之后】（而不是跟着创造栏的桶 ⑪ 放）：
    //      本类上面 桶 ⑫ 的注释写明「这一段必须放在最后一个字段组，让既有物品的数字 id
    //      一个都不移位（存档安全）」。而字段顺序 = 注册顺序 = 注册表数字 id 顺序 ⇒
    //      任何插在 桶 ⑫【之前】的新字段都会把这 128 个壳的数字 id 整体 +1。
    //      为了守住那条纪律，本物品**追加在最后一个字段之后**：
    //      代价 = 「字段顺序 ≈ 创造栏顺序」在最后这一格上不再成立（创造栏里它仍在桶 ⑪），
    //      换来 = 既有物品的数字 id 一个都没动。这条取舍是刻意的。
    // ==================================================================

    /**
     * 🆕 <b>主机样板搬运工具</b>（{@code shanhai:host_pattern_mover}）· 堆叠 <b>1</b>。
     *
     * <p>行为（用户拍板）：Shift+右键主机 = 读取全部样板并入物品（已有则合并，<b>只读、不清源</b>）；
     * 右键主机 = 放置（第二刀）；Shift+右键空气 = 面板（第一刀只读查看）。
     *
     * <p>🔴 <b>为什么要单独一行、不走本类的 {@link #module(String)}</b>：
     * <ol>
     *   <li>可堆叠 1（{@code stacksTo(1)}）—— 里面装着最多 1.5 MiB 的样板 NBT，
     *       堆叠会让「哪一份是权威」变成歧义；</li>
     *   <li>需要真类 {@code HostPatternMoverItem}（覆写 {@code onItemUseFirst} / {@code use} /
     *       {@code appendHoverText}），不是裸 {@code Item}；</li>
     *   <li>⚠️ 属性必须在<b>注册那一刻</b>定（{@code Item.Properties} 是构造参数，注册表冻结后改不了）——
     *       所以 {@code stacksTo(1)} 写在工厂里，而不是事后 {@code stack.setCount}。</li>
     * </ol>
     * <p>图标：{@code textures/item/host_pattern_mover.png}＝<b>临时借用的占位</b>
     * （复制自 {@code wanxiang_core.png}）。最终图标由用户另行出预览，换同名 png 即可，<b>代码不用改</b>
     * （模型 json 的 {@code layer0} 已经指向 {@code shanhai:item/host_pattern_mover}）。
     */
    public static final ItemEntry<com.shanhai.common.patternmover.HostPatternMoverItem> HOST_PATTERN_MOVER =
            ShanhaiRegistration.REGISTRATE
                    .item("host_pattern_mover",
                            properties -> new com.shanhai.common.patternmover.HostPatternMoverItem(
                                    properties.stacksTo(1)))
                    .model(NonNullBiConsumer.noop())
                    .register();


    // ==================================================================
    // 桶 ⑭ · 🆕 2026-11-16「95 件现实/剧情物品」（用户点单）
    //    🔴 95 件 = 7 剧情 + 7 大类 + 65 MC 底层（mc_*）+ 16 运行时异常（ex_*）
    //    🔴 注册形状：7 + 7 + 16 = 30 件走 {@link #module(String)}（纯 Item）；
    //       65 件 mc_* 走 {@link #eventInput(String)}（自定义类 ShanhaiRealityInputItem）——
    //       **不**复用 128 个 {@code reality_item} 壳 —— 壳的图标只支持内置色块/借图，
    //       吃不下这 95 张自定义 png（取证见 temp/reality-items/报告.md §1.3）。
    //    🔴 位置纪律：必须【追加在 HOST_PATTERN_MOVER 之后】（= 最后一个字段组），
    //       否则 128 个壳的数字 id 会整体 +95（存档安全，见桶 ⑫ 与桶 ⑬ 的注释）。
    //    🔴 id 的字符集只能是 [a-z0-9/._-] —— 图标源文件名是中文的 14 件已在拷入时改成 ASCII id。
    //    🆕 阶段 1（已完成）：注册 + 图标 + 语言 + 创造栏，一个特殊行为都不写。
    //    🆕 阶段 2（2026-11-16）：只把 65 件 mc_* 的实现类换成 ShanhaiRealityInputItem
    //       （右键空气开登记面板）；id / 显示名（lang）/ 图标（模型 json）/ 创造栏位置
    //       （ShanhaiCreativeModeTabs#fill 里那 65 行 accept）**一个字节都没动**。
    //    🆕 阶段 3（2026-11-16）：只把 16 件 ex_* 的实现类换成 ShanhaiCrashBoltItem
    //       （三叉戟式蓄力投掷物，落地 3 秒后真崩）；id / 显示名 / 图标 / 创造栏位置 / 堆叠上限
    //       **一个字节都没动**。story_* / group_* 这 14 件仍是纯 Item。
    // ==================================================================


    // ---- A · 7 件剧情物品（story 段，按用户点名的顺序） ----
    /** shanhai:condensed_time（凝聚态时间）· 贴图 textures/item/condensed_time.png */
    public static final ItemEntry<Item> CONDENSED_TIME = module("condensed_time");
    /** shanhai:condensed_space（凝聚态空间）· 贴图 textures/item/condensed_space.png */
    public static final ItemEntry<Item> CONDENSED_SPACE = module("condensed_space");
    /** shanhai:schrodinger_conception（薛定谔的创想）· 贴图 textures/item/schrodinger_conception.png */
    public static final ItemEntry<Item> SCHRODINGER_CONCEPTION = module("schrodinger_conception");
    /** shanhai:newtons_tear（牛顿的眼泪）· 贴图 textures/item/newtons_tear.png */
    public static final ItemEntry<Item> NEWTONS_TEAR = module("newtons_tear");
    /** shanhai:newtons_coffin_lid（牛顿的棺材板）· 贴图 textures/item/newtons_coffin_lid.png */
    public static final ItemEntry<Item> NEWTONS_COFFIN_LID = module("newtons_coffin_lid");
    /** shanhai:turings_computer（图灵的电脑）· 贴图 textures/item/turings_computer.png */
    public static final ItemEntry<Item> TURINGS_COMPUTER = module("turings_computer");
    /** shanhai:dgy_code（dgy 的代码）· 贴图 textures/item/dgy_code.png */
    public static final ItemEntry<Item> DGY_CODE = module("dgy_code");

    // ---- B · 7 个大类（group 段，按用户点名的顺序） ----
    /** shanhai:entity_persistence_gate（实体存续判定闸门）· 贴图 textures/item/entity_persistence_gate.png */
    public static final ItemEntry<Item> ENTITY_PERSISTENCE_GATE = module("entity_persistence_gate");
    /** shanhai:interaction_dispatch_bus（交互动作分派总线）· 贴图 textures/item/interaction_dispatch_bus.png */
    public static final ItemEntry<Item> INTERACTION_DISPATCH_BUS = module("interaction_dispatch_bus");
    /** shanhai:region_block_state_matrix（区域方块状态改写矩阵）· 贴图 textures/item/region_block_state_matrix.png */
    public static final ItemEntry<Item> REGION_BLOCK_STATE_MATRIX = module("region_block_state_matrix");
    /** shanhai:world_process_orchestrator（世界进程编排装置）· 贴图 textures/item/world_process_orchestrator.png */
    public static final ItemEntry<Item> WORLD_PROCESS_ORCHESTRATOR = module("world_process_orchestrator");
    /** shanhai:temporal_pulse_medium（时序脉冲发生介质）· 贴图 textures/item/temporal_pulse_medium.png */
    public static final ItemEntry<Item> TEMPORAL_PULSE_MEDIUM = module("temporal_pulse_medium");
    /** shanhai:client_action_packet_relay（客户端动作包转译中继）· 贴图 textures/item/client_action_packet_relay.png */
    public static final ItemEntry<Item> CLIENT_ACTION_PACKET_RELAY = module("client_action_packet_relay");
    /** shanhai:world_state_sync_core（世界状态同步核心）· 贴图 textures/item/world_state_sync_core.png */
    public static final ItemEntry<Item> WORLD_STATE_SYNC_CORE = module("world_state_sync_core");

    // ---- C · 65 件 MC 底层（mc_* 段，id-plan.json 原序） ----
    /** shanhai:mc_livingattackevent（LivingAttackEvent）· 贴图 textures/item/mc_livingattackevent.png */
    public static final ItemEntry<com.shanhai.common.reality.ShanhaiRealityInputItem> MC_LIVINGATTACKEVENT = eventInput("mc_livingattackevent");
    /** shanhai:mc_livinghurtevent（LivingHurtEvent）· 贴图 textures/item/mc_livinghurtevent.png */
    public static final ItemEntry<com.shanhai.common.reality.ShanhaiRealityInputItem> MC_LIVINGHURTEVENT = eventInput("mc_livinghurtevent");
    /** shanhai:mc_livingdeathevent（LivingDeathEvent）· 贴图 textures/item/mc_livingdeathevent.png */
    public static final ItemEntry<com.shanhai.common.reality.ShanhaiRealityInputItem> MC_LIVINGDEATHEVENT = eventInput("mc_livingdeathevent");
    /** shanhai:mc_livingdropsevent（LivingDropsEvent）· 贴图 textures/item/mc_livingdropsevent.png */
    public static final ItemEntry<com.shanhai.common.reality.ShanhaiRealityInputItem> MC_LIVINGDROPSEVENT = eventInput("mc_livingdropsevent");
    /** shanhai:mc_criticalhitevent（CriticalHitEvent）· 贴图 textures/item/mc_criticalhitevent.png */
    public static final ItemEntry<com.shanhai.common.reality.ShanhaiRealityInputItem> MC_CRITICALHITEVENT = eventInput("mc_criticalhitevent");
    /** shanhai:mc_playerinteractevent_rightclickitem（PlayerInteractEvent.RightClickItem）· 贴图 textures/item/mc_playerinteractevent_rightclickitem.png */
    public static final ItemEntry<com.shanhai.common.reality.ShanhaiRealityInputItem> MC_PLAYERINTERACTEVENT_RIGHTCLICKITEM = eventInput("mc_playerinteractevent_rightclickitem");
    /** shanhai:mc_playerinteractevent_leftclickblock（PlayerInteractEvent.LeftClickBlock）· 贴图 textures/item/mc_playerinteractevent_leftclickblock.png */
    public static final ItemEntry<com.shanhai.common.reality.ShanhaiRealityInputItem> MC_PLAYERINTERACTEVENT_LEFTCLICKBLOCK = eventInput("mc_playerinteractevent_leftclickblock");
    /** shanhai:mc_itemtossevent（ItemTossEvent）· 贴图 textures/item/mc_itemtossevent.png */
    public static final ItemEntry<com.shanhai.common.reality.ShanhaiRealityInputItem> MC_ITEMTOSSEVENT = eventInput("mc_itemtossevent");
    /** shanhai:mc_entityitempickupevent（EntityItemPickupEvent）· 贴图 textures/item/mc_entityitempickupevent.png */
    public static final ItemEntry<com.shanhai.common.reality.ShanhaiRealityInputItem> MC_ENTITYITEMPICKUPEVENT = eventInput("mc_entityitempickupevent");
    /** shanhai:mc_anvilupdateevent（AnvilUpdateEvent）· 贴图 textures/item/mc_anvilupdateevent.png */
    public static final ItemEntry<com.shanhai.common.reality.ShanhaiRealityInputItem> MC_ANVILUPDATEEVENT = eventInput("mc_anvilupdateevent");
    /** shanhai:mc_blockevent_breakevent（BlockEvent.BreakEvent）· 贴图 textures/item/mc_blockevent_breakevent.png */
    public static final ItemEntry<com.shanhai.common.reality.ShanhaiRealityInputItem> MC_BLOCKEVENT_BREAKEVENT = eventInput("mc_blockevent_breakevent");
    /** shanhai:mc_blockevent_entityplaceevent（BlockEvent.EntityPlaceEvent）· 贴图 textures/item/mc_blockevent_entityplaceevent.png */
    public static final ItemEntry<com.shanhai.common.reality.ShanhaiRealityInputItem> MC_BLOCKEVENT_ENTITYPLACEEVENT = eventInput("mc_blockevent_entityplaceevent");
    /** shanhai:mc_blockevent_neighbornotifyevent（BlockEvent.NeighborNotifyEvent）· 贴图 textures/item/mc_blockevent_neighbornotifyevent.png */
    public static final ItemEntry<com.shanhai.common.reality.ShanhaiRealityInputItem> MC_BLOCKEVENT_NEIGHBORNOTIFYEVENT = eventInput("mc_blockevent_neighbornotifyevent");
    /** shanhai:mc_explosionevent_start（ExplosionEvent.Start）· 贴图 textures/item/mc_explosionevent_start.png */
    public static final ItemEntry<com.shanhai.common.reality.ShanhaiRealityInputItem> MC_EXPLOSIONEVENT_START = eventInput("mc_explosionevent_start");
    /** shanhai:mc_chunkevent_load（ChunkEvent.Load）· 贴图 textures/item/mc_chunkevent_load.png */
    public static final ItemEntry<com.shanhai.common.reality.ShanhaiRealityInputItem> MC_CHUNKEVENT_LOAD = eventInput("mc_chunkevent_load");
    /** shanhai:mc_entityjoinlevelevent（EntityJoinLevelEvent）· 贴图 textures/item/mc_entityjoinlevelevent.png */
    public static final ItemEntry<com.shanhai.common.reality.ShanhaiRealityInputItem> MC_ENTITYJOINLEVELEVENT = eventInput("mc_entityjoinlevelevent");
    /** shanhai:mc_entitytraveltodimensionevent（EntityTravelToDimensionEvent）· 贴图 textures/item/mc_entitytraveltodimensionevent.png */
    public static final ItemEntry<com.shanhai.common.reality.ShanhaiRealityInputItem> MC_ENTITYTRAVELTODIMENSIONEVENT = eventInput("mc_entitytraveltodimensionevent");
    /** shanhai:mc_entitystruckbylightningevent（EntityStruckByLightningEvent）· 贴图 textures/item/mc_entitystruckbylightningevent.png */
    public static final ItemEntry<com.shanhai.common.reality.ShanhaiRealityInputItem> MC_ENTITYSTRUCKBYLIGHTNINGEVENT = eventInput("mc_entitystruckbylightningevent");
    /** shanhai:mc_serverchatevent（ServerChatEvent）· 贴图 textures/item/mc_serverchatevent.png */
    public static final ItemEntry<com.shanhai.common.reality.ShanhaiRealityInputItem> MC_SERVERCHATEVENT = eventInput("mc_serverchatevent");
    /** shanhai:mc_registercommandsevent（RegisterCommandsEvent）· 贴图 textures/item/mc_registercommandsevent.png */
    public static final ItemEntry<com.shanhai.common.reality.ShanhaiRealityInputItem> MC_REGISTERCOMMANDSEVENT = eventInput("mc_registercommandsevent");
    /** shanhai:mc_tickevent_servertickevent（TickEvent.ServerTickEvent）· 贴图 textures/item/mc_tickevent_servertickevent.png */
    public static final ItemEntry<com.shanhai.common.reality.ShanhaiRealityInputItem> MC_TICKEVENT_SERVERTICKEVENT = eventInput("mc_tickevent_servertickevent");
    /** shanhai:mc_tickevent_leveltickevent（TickEvent.LevelTickEvent）· 贴图 textures/item/mc_tickevent_leveltickevent.png */
    public static final ItemEntry<com.shanhai.common.reality.ShanhaiRealityInputItem> MC_TICKEVENT_LEVELTICKEVENT = eventInput("mc_tickevent_leveltickevent");
    /** shanhai:mc_tickevent_playertickevent（TickEvent.PlayerTickEvent）· 贴图 textures/item/mc_tickevent_playertickevent.png */
    public static final ItemEntry<com.shanhai.common.reality.ShanhaiRealityInputItem> MC_TICKEVENT_PLAYERTICKEVENT = eventInput("mc_tickevent_playertickevent");
    /** shanhai:mc_tickevent_clienttickevent（TickEvent.ClientTickEvent）· 贴图 textures/item/mc_tickevent_clienttickevent.png */
    public static final ItemEntry<com.shanhai.common.reality.ShanhaiRealityInputItem> MC_TICKEVENT_CLIENTTICKEVENT = eventInput("mc_tickevent_clienttickevent");
    /** shanhai:mc_tickevent_rendertickevent（TickEvent.RenderTickEvent）· 贴图 textures/item/mc_tickevent_rendertickevent.png */
    public static final ItemEntry<com.shanhai.common.reality.ShanhaiRealityInputItem> MC_TICKEVENT_RENDERTICKEVENT = eventInput("mc_tickevent_rendertickevent");
    /** shanhai:mc_serverboundplayeractionpacket（ServerboundPlayerActionPacket）· 贴图 textures/item/mc_serverboundplayeractionpacket.png */
    public static final ItemEntry<com.shanhai.common.reality.ShanhaiRealityInputItem> MC_SERVERBOUNDPLAYERACTIONPACKET = eventInput("mc_serverboundplayeractionpacket");
    /** shanhai:mc_serverbounduseitempacket（ServerboundUseItemPacket）· 贴图 textures/item/mc_serverbounduseitempacket.png */
    public static final ItemEntry<com.shanhai.common.reality.ShanhaiRealityInputItem> MC_SERVERBOUNDUSEITEMPACKET = eventInput("mc_serverbounduseitempacket");
    /** shanhai:mc_serverboundcontainerclickpacket（ServerboundContainerClickPacket）· 贴图 textures/item/mc_serverboundcontainerclickpacket.png */
    public static final ItemEntry<com.shanhai.common.reality.ShanhaiRealityInputItem> MC_SERVERBOUNDCONTAINERCLICKPACKET = eventInput("mc_serverboundcontainerclickpacket");
    /** shanhai:mc_serverboundchatcommandpacket（ServerboundChatCommandPacket）· 贴图 textures/item/mc_serverboundchatcommandpacket.png */
    public static final ItemEntry<com.shanhai.common.reality.ShanhaiRealityInputItem> MC_SERVERBOUNDCHATCOMMANDPACKET = eventInput("mc_serverboundchatcommandpacket");
    /** shanhai:mc_serverboundcustompayloadpacket（ServerboundCustomPayloadPacket）· 贴图 textures/item/mc_serverboundcustompayloadpacket.png */
    public static final ItemEntry<com.shanhai.common.reality.ShanhaiRealityInputItem> MC_SERVERBOUNDCUSTOMPAYLOADPACKET = eventInput("mc_serverboundcustompayloadpacket");
    /** shanhai:mc_clientboundblockupdatepacket（ClientboundBlockUpdatePacket）· 贴图 textures/item/mc_clientboundblockupdatepacket.png */
    public static final ItemEntry<com.shanhai.common.reality.ShanhaiRealityInputItem> MC_CLIENTBOUNDBLOCKUPDATEPACKET = eventInput("mc_clientboundblockupdatepacket");
    /** shanhai:mc_clientboundsethealthpacket（ClientboundSetHealthPacket）· 贴图 textures/item/mc_clientboundsethealthpacket.png */
    public static final ItemEntry<com.shanhai.common.reality.ShanhaiRealityInputItem> MC_CLIENTBOUNDSETHEALTHPACKET = eventInput("mc_clientboundsethealthpacket");
    /** shanhai:mc_clientboundplayercombatkillpacket（ClientboundPlayerCombatKillPacket）· 贴图 textures/item/mc_clientboundplayercombatkillpacket.png */
    public static final ItemEntry<com.shanhai.common.reality.ShanhaiRealityInputItem> MC_CLIENTBOUNDPLAYERCOMBATKILLPACKET = eventInput("mc_clientboundplayercombatkillpacket");
    /** shanhai:mc_clientboundsoundpacket（ClientboundSoundPacket）· 贴图 textures/item/mc_clientboundsoundpacket.png */
    public static final ItemEntry<com.shanhai.common.reality.ShanhaiRealityInputItem> MC_CLIENTBOUNDSOUNDPACKET = eventInput("mc_clientboundsoundpacket");
    /** shanhai:mc_clientboundlevelparticlespacket（ClientboundLevelParticlesPacket）· 贴图 textures/item/mc_clientboundlevelparticlespacket.png */
    public static final ItemEntry<com.shanhai.common.reality.ShanhaiRealityInputItem> MC_CLIENTBOUNDLEVELPARTICLESPACKET = eventInput("mc_clientboundlevelparticlespacket");
    /** shanhai:mc_minecraftserver_runserver（MinecraftServer.runServer）· 贴图 textures/item/mc_minecraftserver_runserver.png */
    public static final ItemEntry<com.shanhai.common.reality.ShanhaiRealityInputItem> MC_MINECRAFTSERVER_RUNSERVER = eventInput("mc_minecraftserver_runserver");
    /** shanhai:mc_levelaccessor_scheduletick（LevelAccessor.scheduleTick）· 贴图 textures/item/mc_levelaccessor_scheduletick.png */
    public static final ItemEntry<com.shanhai.common.reality.ShanhaiRealityInputItem> MC_LEVELACCESSOR_SCHEDULETICK = eventInput("mc_levelaccessor_scheduletick");
    /** shanhai:mc_serverlevel_tickchunk（ServerLevel.tickChunk）· 贴图 textures/item/mc_serverlevel_tickchunk.png */
    public static final ItemEntry<com.shanhai.common.reality.ShanhaiRealityInputItem> MC_SERVERLEVEL_TICKCHUNK = eventInput("mc_serverlevel_tickchunk");
    /** shanhai:mc_level_neighborchanged（Level.neighborChanged）· 贴图 textures/item/mc_level_neighborchanged.png */
    public static final ItemEntry<com.shanhai.common.reality.ShanhaiRealityInputItem> MC_LEVEL_NEIGHBORCHANGED = eventInput("mc_level_neighborchanged");
    /** shanhai:mc_serverlevel_setdaytime（ServerLevel.setDayTime）· 贴图 textures/item/mc_serverlevel_setdaytime.png */
    public static final ItemEntry<com.shanhai.common.reality.ShanhaiRealityInputItem> MC_SERVERLEVEL_SETDAYTIME = eventInput("mc_serverlevel_setdaytime");
    /** shanhai:mc_chunklevel（ChunkLevel）· 贴图 textures/item/mc_chunklevel.png */
    public static final ItemEntry<com.shanhai.common.reality.ShanhaiRealityInputItem> MC_CHUNKLEVEL = eventInput("mc_chunklevel");
    /** shanhai:mc_compoundtag（CompoundTag）· 贴图 textures/item/mc_compoundtag.png */
    public static final ItemEntry<com.shanhai.common.reality.ShanhaiRealityInputItem> MC_COMPOUNDTAG = eventInput("mc_compoundtag");
    /** shanhai:mc_registry（Registry）· 贴图 textures/item/mc_registry.png */
    public static final ItemEntry<com.shanhai.common.reality.ShanhaiRealityInputItem> MC_REGISTRY = eventInput("mc_registry");
    /** shanhai:mc_palettedcontainer（PalettedContainer）· 贴图 textures/item/mc_palettedcontainer.png */
    public static final ItemEntry<com.shanhai.common.reality.ShanhaiRealityInputItem> MC_PALETTEDCONTAINER = eventInput("mc_palettedcontainer");
    /** shanhai:mc_packrepository（PackRepository）· 贴图 textures/item/mc_packrepository.png */
    public static final ItemEntry<com.shanhai.common.reality.ShanhaiRealityInputItem> MC_PACKREPOSITORY = eventInput("mc_packrepository");
    /** shanhai:mc_recipemanager（RecipeManager）· 贴图 textures/item/mc_recipemanager.png */
    public static final ItemEntry<com.shanhai.common.reality.ShanhaiRealityInputItem> MC_RECIPEMANAGER = eventInput("mc_recipemanager");
    /** shanhai:mc_lootdatamanager（LootDataManager）· 贴图 textures/item/mc_lootdatamanager.png */
    public static final ItemEntry<com.shanhai.common.reality.ShanhaiRealityInputItem> MC_LOOTDATAMANAGER = eventInput("mc_lootdatamanager");
    /** shanhai:mc_tagkey（TagKey）· 贴图 textures/item/mc_tagkey.png */
    public static final ItemEntry<com.shanhai.common.reality.ShanhaiRealityInputItem> MC_TAGKEY = eventInput("mc_tagkey");
    /** shanhai:mc_serveradvancementmanager（ServerAdvancementManager）· 贴图 textures/item/mc_serveradvancementmanager.png */
    public static final ItemEntry<com.shanhai.common.reality.ShanhaiRealityInputItem> MC_SERVERADVANCEMENTMANAGER = eventInput("mc_serveradvancementmanager");
    /** shanhai:mc_gamerules（GameRules）· 贴图 textures/item/mc_gamerules.png */
    public static final ItemEntry<com.shanhai.common.reality.ShanhaiRealityInputItem> MC_GAMERULES = eventInput("mc_gamerules");
    /** shanhai:mc_serverfunctionmanager（ServerFunctionManager）· 贴图 textures/item/mc_serverfunctionmanager.png */
    public static final ItemEntry<com.shanhai.common.reality.ShanhaiRealityInputItem> MC_SERVERFUNCTIONMANAGER = eventInput("mc_serverfunctionmanager");
    /** shanhai:mc_livingentity_travel（LivingEntity.travel）· 贴图 textures/item/mc_livingentity_travel.png */
    public static final ItemEntry<com.shanhai.common.reality.ShanhaiRealityInputItem> MC_LIVINGENTITY_TRAVEL = eventInput("mc_livingentity_travel");
    /** shanhai:mc_aabb（AABB）· 贴图 textures/item/mc_aabb.png */
    public static final ItemEntry<com.shanhai.common.reality.ShanhaiRealityInputItem> MC_AABB = eventInput("mc_aabb");
    /** shanhai:mc_pathnavigation（PathNavigation）· 贴图 textures/item/mc_pathnavigation.png */
    public static final ItemEntry<com.shanhai.common.reality.ShanhaiRealityInputItem> MC_PATHNAVIGATION = eventInput("mc_pathnavigation");
    /** shanhai:mc_levellightengine（LevelLightEngine）· 贴图 textures/item/mc_levellightengine.png */
    public static final ItemEntry<com.shanhai.common.reality.ShanhaiRealityInputItem> MC_LEVELLIGHTENGINE = eventInput("mc_levellightengine");
    /** shanhai:mc_flowingfluid_spread（FlowingFluid.spread）· 贴图 textures/item/mc_flowingfluid_spread.png */
    public static final ItemEntry<com.shanhai.common.reality.ShanhaiRealityInputItem> MC_FLOWINGFLUID_SPREAD = eventInput("mc_flowingfluid_spread");
    /** shanhai:mc_dimensiontype_getteleportationscale（DimensionType.getTeleportationScale）· 贴图 textures/item/mc_dimensiontype_getteleportationscale.png */
    public static final ItemEntry<com.shanhai.common.reality.ShanhaiRealityInputItem> MC_DIMENSIONTYPE_GETTELEPORTATIONSCALE = eventInput("mc_dimensiontype_getteleportationscale");
    /** shanhai:mc_structurestart（StructureStart）· 贴图 textures/item/mc_structurestart.png */
    public static final ItemEntry<com.shanhai.common.reality.ShanhaiRealityInputItem> MC_STRUCTURESTART = eventInput("mc_structurestart");
    /** shanhai:mc_worldoptions_seed（WorldOptions.seed）· 贴图 textures/item/mc_worldoptions_seed.png */
    public static final ItemEntry<com.shanhai.common.reality.ShanhaiRealityInputItem> MC_WORLDOPTIONS_SEED = eventInput("mc_worldoptions_seed");
    /** shanhai:mc_packetutils_ensurerunningonsamethread（PacketUtils.ensureRunningOnSameThread）· 贴图 textures/item/mc_packetutils_ensurerunningonsamethread.png */
    public static final ItemEntry<com.shanhai.common.reality.ShanhaiRealityInputItem> MC_PACKETUTILS_ENSURERUNNINGONSAMETHREAD = eventInput("mc_packetutils_ensurerunningonsamethread");
    /** shanhai:mc_blockableeventloop_issamethread（BlockableEventLoop.isSameThread）· 贴图 textures/item/mc_blockableeventloop_issamethread.png */
    public static final ItemEntry<com.shanhai.common.reality.ShanhaiRealityInputItem> MC_BLOCKABLEEVENTLOOP_ISSAMETHREAD = eventInput("mc_blockableeventloop_issamethread");
    /** shanhai:mc_abstractcontainermenu（AbstractContainerMenu）· 贴图 textures/item/mc_abstractcontainermenu.png */
    public static final ItemEntry<com.shanhai.common.reality.ShanhaiRealityInputItem> MC_ABSTRACTCONTAINERMENU = eventInput("mc_abstractcontainermenu");
    /** shanhai:mc_playerlist_respawn（PlayerList.respawn）· 贴图 textures/item/mc_playerlist_respawn.png */
    public static final ItemEntry<com.shanhai.common.reality.ShanhaiRealityInputItem> MC_PLAYERLIST_RESPAWN = eventInput("mc_playerlist_respawn");
    /** shanhai:mc_serverstatscounter（ServerStatsCounter）· 贴图 textures/item/mc_serverstatscounter.png */
    public static final ItemEntry<com.shanhai.common.reality.ShanhaiRealityInputItem> MC_SERVERSTATSCOUNTER = eventInput("mc_serverstatscounter");
    /** shanhai:mc_playerenderchestcontainer（PlayerEnderChestContainer）· 贴图 textures/item/mc_playerenderchestcontainer.png */
    public static final ItemEntry<com.shanhai.common.reality.ShanhaiRealityInputItem> MC_PLAYERENDERCHESTCONTAINER = eventInput("mc_playerenderchestcontainer");

    // ---- D · 16 件运行时异常（ex_* 段，id-plan.json 原序） ----
    //   🆕 阶段 3（2026-11-16）：这 16 件从纯 Item 换成 ShanhaiCrashBoltItem
    //      （按住右键蓄力 → 松手扔出 → 落地 3 秒后按"它对应的那个异常"真崩）。
    //      🔴 只换实现类：id / 显示名（lang）/ 图标 / 创造栏位置 / 堆叠上限 一个字节都没动。
    //      🔴 第二个参数 = 异常序号（0..15），与 com.shanhai.common.crash.ShanhaiCrashBoltTable
    //         的 ROWS **逐行对齐**；改这里的数字必须同步改那张表（stage3-audit.mjs 会核行序）。
    /** shanhai:ex_nullpointerexception（NullPointerException）· 贴图 textures/item/ex_nullpointerexception.png */
    public static final ItemEntry<com.shanhai.common.crash.ShanhaiCrashBoltItem> EX_NULLPOINTEREXCEPTION = crashBolt("ex_nullpointerexception", 0);
    /** shanhai:ex_noclassdeffounderror（NoClassDefFoundError）· 贴图 textures/item/ex_noclassdeffounderror.png */
    public static final ItemEntry<com.shanhai.common.crash.ShanhaiCrashBoltItem> EX_NOCLASSDEFFOUNDERROR = crashBolt("ex_noclassdeffounderror", 1);
    /** shanhai:ex_exceptionininitializererror（ExceptionInInitializerError）· 贴图 textures/item/ex_exceptionininitializererror.png */
    public static final ItemEntry<com.shanhai.common.crash.ShanhaiCrashBoltItem> EX_EXCEPTIONININITIALIZERERROR = crashBolt("ex_exceptionininitializererror", 2);
    /** shanhai:ex_ioexception（IOException）· 贴图 textures/item/ex_ioexception.png */
    public static final ItemEntry<com.shanhai.common.crash.ShanhaiCrashBoltItem> EX_IOEXCEPTION = crashBolt("ex_ioexception", 3);
    /** shanhai:ex_classnotfoundexception（ClassNotFoundException）· 贴图 textures/item/ex_classnotfoundexception.png */
    public static final ItemEntry<com.shanhai.common.crash.ShanhaiCrashBoltItem> EX_CLASSNOTFOUNDEXCEPTION = crashBolt("ex_classnotfoundexception", 4);
    /** shanhai:ex_sqlexception（SQLException）· 贴图 textures/item/ex_sqlexception.png */
    public static final ItemEntry<com.shanhai.common.crash.ShanhaiCrashBoltItem> EX_SQLEXCEPTION = crashBolt("ex_sqlexception", 5);
    /** shanhai:ex_nosuchelementexception（NoSuchElementException）· 贴图 textures/item/ex_nosuchelementexception.png */
    public static final ItemEntry<com.shanhai.common.crash.ShanhaiCrashBoltItem> EX_NOSUCHELEMENTEXCEPTION = crashBolt("ex_nosuchelementexception", 6);
    /** shanhai:ex_illegalargumentexception（IllegalArgumentException）· 贴图 textures/item/ex_illegalargumentexception.png */
    public static final ItemEntry<com.shanhai.common.crash.ShanhaiCrashBoltItem> EX_ILLEGALARGUMENTEXCEPTION = crashBolt("ex_illegalargumentexception", 7);
    /** shanhai:ex_illegalstateexception（IllegalStateException）· 贴图 textures/item/ex_illegalstateexception.png */
    public static final ItemEntry<com.shanhai.common.crash.ShanhaiCrashBoltItem> EX_ILLEGALSTATEEXCEPTION = crashBolt("ex_illegalstateexception", 8);
    /** shanhai:ex_unsupportedoperationexception（UnsupportedOperationException）· 贴图 textures/item/ex_unsupportedoperationexception.png */
    public static final ItemEntry<com.shanhai.common.crash.ShanhaiCrashBoltItem> EX_UNSUPPORTEDOPERATIONEXCEPTION = crashBolt("ex_unsupportedoperationexception", 9);
    /** shanhai:ex_midiunavailableexception（MidiUnavailableException）· 贴图 textures/item/ex_midiunavailableexception.png */
    public static final ItemEntry<com.shanhai.common.crash.ShanhaiCrashBoltItem> EX_MIDIUNAVAILABLEEXCEPTION = crashBolt("ex_midiunavailableexception", 10);
    /** shanhai:ex_concurrentmodificationexception（ConcurrentModificationException）· 贴图 textures/item/ex_concurrentmodificationexception.png */
    public static final ItemEntry<com.shanhai.common.crash.ShanhaiCrashBoltItem> EX_CONCURRENTMODIFICATIONEXCEPTION = crashBolt("ex_concurrentmodificationexception", 11);
    /** shanhai:ex_indexoutofboundsexception（IndexOutOfBoundsException）· 贴图 textures/item/ex_indexoutofboundsexception.png */
    public static final ItemEntry<com.shanhai.common.crash.ShanhaiCrashBoltItem> EX_INDEXOUTOFBOUNDSEXCEPTION = crashBolt("ex_indexoutofboundsexception", 12);
    /** shanhai:ex_nosuchmethodexception（NoSuchMethodException）· 贴图 textures/item/ex_nosuchmethodexception.png */
    public static final ItemEntry<com.shanhai.common.crash.ShanhaiCrashBoltItem> EX_NOSUCHMETHODEXCEPTION = crashBolt("ex_nosuchmethodexception", 13);
    /** shanhai:ex_classcastexception（ClassCastException）· 贴图 textures/item/ex_classcastexception.png */
    public static final ItemEntry<com.shanhai.common.crash.ShanhaiCrashBoltItem> EX_CLASSCASTEXCEPTION = crashBolt("ex_classcastexception", 14);
    /** shanhai:ex_outofmemoryerror（OutOfMemoryError）· 贴图 textures/item/ex_outofmemoryerror.png */
    public static final ItemEntry<com.shanhai.common.crash.ShanhaiCrashBoltItem> EX_OUTOFMEMORYERROR = crashBolt("ex_outofmemoryerror", 15);

    // ---- E · 14 件「底层技术 · 时序与改写」（2026-11-16 用户点单，阶段 1 只做注册/图标/语言/创造栏） ----
    //   🔴 顺序 = temp/design-sheet/data-story.js 第 9 类（cat: '底层技术 · 时序与改写（实现层）'）的 14 条原序。
    //   🔴 位置纪律：追加在 EX_OUTOFMEMORYERROR 之后（= 本类最后一个字段组，仍在所有既有物品之后），
    //      否则 128 个现实物品壳 + 95 件的数字 id 会整体 +14（存档安全，见桶 ⑫/⑬/⑭ 的注释）。
    //   🔴 注册形状：14 件全是纯 Item（无行为），走 module(String)，与 7 剧情 / 7 大类同形。
    //   🔴 图标：textures/item/<id>.png 为 64×1536 的 24 帧竖条动画（4 列 × 6 行），
    //      配 <id>.png.mcmeta = {"animation":{"frametime":2,"interpolate":true}}；模型 json 随包提供。
    /** shanhai:temporal_sequence_breaker（时序闸断器）· 贴图 textures/item/temporal_sequence_breaker.png */
    public static final ItemEntry<Item> TEMPORAL_SEQUENCE_BREAKER = module("temporal_sequence_breaker");
    /** shanhai:differential_trace_register（差分留痕寄存器）· 贴图 textures/item/differential_trace_register.png */
    public static final ItemEntry<Item> DIFFERENTIAL_TRACE_REGISTER = module("differential_trace_register");
    /** shanhai:negentropy_reverse_drive_core（逆熵反向驱动核）· 贴图 textures/item/negentropy_reverse_drive_core.png */
    public static final ItemEntry<Item> NEGENTROPY_REVERSE_DRIVE_CORE = module("negentropy_reverse_drive_core");
    /** shanhai:epoch_origin_calibrator（时刻原点定标器）· 贴图 textures/item/epoch_origin_calibrator.png */
    public static final ItemEntry<Item> EPOCH_ORIGIN_CALIBRATOR = module("epoch_origin_calibrator");
    /** shanhai:delayed_choice_compensator（延迟选择补偿器）· 贴图 textures/item/delayed_choice_compensator.png */
    public static final ItemEntry<Item> DELAYED_CHOICE_COMPENSATOR = module("delayed_choice_compensator");
    /** shanhai:phase_lock_loop（相位锁定回路）· 贴图 textures/item/phase_lock_loop.png */
    public static final ItemEntry<Item> PHASE_LOCK_LOOP = module("phase_lock_loop");
    /** shanhai:inertial_frame_reanchor（惯性系重锚）· 贴图 textures/item/inertial_frame_reanchor.png */
    public static final ItemEntry<Item> INERTIAL_FRAME_REANCHOR = module("inertial_frame_reanchor");
    /** shanhai:noether_conservation_reconciler（诺特守恒对账器）· 贴图 textures/item/noether_conservation_reconciler.png */
    public static final ItemEntry<Item> NOETHER_CONSERVATION_RECONCILER = module("noether_conservation_reconciler");
    /** shanhai:external_save_write_terminal（域外存档写入终端）· 贴图 textures/item/external_save_write_terminal.png */
    public static final ItemEntry<Item> EXTERNAL_SAVE_WRITE_TERMINAL = module("external_save_write_terminal");
    /** shanhai:anomaly_data_detector（异常数据检测器）· 贴图 textures/item/anomaly_data_detector.png */
    public static final ItemEntry<Item> ANOMALY_DATA_DETECTOR = module("anomaly_data_detector");
    /** shanhai:just_in_time_recompile_injector（即时重编译注入器）· 贴图 textures/item/just_in_time_recompile_injector.png */
    public static final ItemEntry<Item> JUST_IN_TIME_RECOMPILE_INJECTOR = module("just_in_time_recompile_injector");
    /** shanhai:state_deviation_calibration_matrix（状态偏差校准矩阵）· 贴图 textures/item/state_deviation_calibration_matrix.png */
    public static final ItemEntry<Item> STATE_DEVIATION_CALIBRATION_MATRIX = module("state_deviation_calibration_matrix");
    /** shanhai:mutation_provenance_chain（变更溯源链路）· 贴图 textures/item/mutation_provenance_chain.png */
    public static final ItemEntry<Item> MUTATION_PROVENANCE_CHAIN = module("mutation_provenance_chain");
    /** shanhai:probability_field_sampler（概率场采样器）· 贴图 textures/item/probability_field_sampler.png */
    public static final ItemEntry<Item> PROBABILITY_FIELD_SAMPLER = module("probability_field_sampler");

    /** 建 128 个壳：每个都是普通 {@link Item} + 静态模型（模型 json 见 resources/models/item）。 */
    private static ItemEntry<RealityShellItem>[] realityItems() {
        final int n = com.shanhai.common.reality.ShanhaiRealityItemTable.SLOT_COUNT;
        final ItemEntry<RealityShellItem>[] out = (ItemEntry<RealityShellItem>[]) new ItemEntry<?>[n];
        for (int i = 0; i < n; i++) {
            final int slot = i;
            out[i] = ShanhaiRegistration.REGISTRATE
                    .item("reality_item_" + (i + 1), props -> new RealityShellItem(slot, props))
                    .model(NonNullBiConsumer.noop())
                    .register();
        }
        return out;
    }

    /** 第 {@code index} 号（0 基）现实物品的注册句柄。 */
    public static ItemEntry<RealityShellItem> realityItem(int index) {
        final ItemEntry<RealityShellItem>[] all = REALITY_ITEMS;
        return index >= 0 && index < all.length ? all[index] : null;
    }

    /** 第 {@code index} 号（0 基）现实物品本体；越界 = {@code null}。 */
    public static Item realityItemValue(int index) {
        final ItemEntry<RealityShellItem> e = realityItem(index);
        return e == null ? null : e.get();
    }

    public static void init() {
        // 静态字段初始化即注册；本方法只为「显式触达类」而存在，不要在别处再塞逻辑。
    }

    /** 全部 149 个物品共用的注册形状：普通 {@link Item} + 静态模型 + 静态贴图。 */
    private static ItemEntry<Item> module(String id) {
        return ShanhaiRegistration.REGISTRATE
                .item(id, Item::new)
                .model(NonNullBiConsumer.noop())
                .register();
    }

    /**
     * 🆕 2026-11-16（阶段 2）：<b>65 件 {@code mc_*} 事件物品</b>的注册形状 —— 自定义类
     * {@code ShanhaiRealityInputItem}（右键空气开「目标 / 要干什么」登记面板）。
     *
     * <p>与 {@link #module(String)} <b>逐字节等价</b>，只换了实现类：
     * 同一个 {@code id}、同一个 {@code .model(noop())}（模型 json 已随包提供）、
     * <b>不写</b> {@code stacksTo(…)}（保持默认 64，与阶段 1 一致）。
     *
     * <p>写法照工程既有的「自定义 Item 子类」先例
     * （{@code HOST_PATTERN_MOVER} 那一条 {@code properties -> new HostPatternMoverItem(...)}），
     * 只是把 65 行重复收成一个方法 —— {@link #module(String)} 也是同一种收法。
     *
     * <p>⚠️ 注册顺序不变 ⇒ 65 个数字 id 一个都不移位（它们仍在 {@code HOST_PATTERN_MOVER} 之后）。
     */
    private static ItemEntry<com.shanhai.common.reality.ShanhaiRealityInputItem> eventInput(String id) {
        return ShanhaiRegistration.REGISTRATE
                .item(id, properties -> new com.shanhai.common.reality.ShanhaiRealityInputItem(id, properties))
                .model(NonNullBiConsumer.noop())
                .register();
    }

    /**
     * 🆕 2026-11-16（阶段 3）：<b>16 件 {@code ex_*} 运行时异常投掷物</b>的注册形状 —— 自定义类
     * {@code ShanhaiCrashBoltItem}（按住右键蓄力、松手扔出，落地 3 秒后按"它对应的那个异常"真崩）。
     *
     * <p>与 {@link #module(String)} <b>逐字节等价</b>，只换了实现类：
     * 同一个 {@code id}、同一个 {@code .model(noop())}（模型 json 已随包提供）、
     * <b>不写</b> {@code stacksTo(…)}（保持默认 64，与阶段 1/2 一致）。
     *
     * @param id    注册 id 的 path（{@code ex_nullpointerexception}）
     * @param index 异常序号（0..15）—— 必须与
     *              {@code com.shanhai.common.crash.ShanhaiCrashBoltTable} 的行序逐行对齐
     */
    private static ItemEntry<com.shanhai.common.crash.ShanhaiCrashBoltItem> crashBolt(String id, int index) {
        return ShanhaiRegistration.REGISTRATE
                .item(id, properties -> new com.shanhai.common.crash.ShanhaiCrashBoltItem(index, properties))
                .model(NonNullBiConsumer.noop())
                .register();
    }

    private ShanhaiItems() {}
}
