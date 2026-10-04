// priority: 1
// =============================================================================
// [server_scripts]shanhai_recipes.js  —— 用户「游戏里编好的 AE 样板」落地为 KubeJS
//
// ✅ 2026-09-26 【终版 · FINAL】：本文件原先挂着的问题【三处全部落定】——
//    ① 新类型「原初物质解构」= `gtceu:primordial_matter_deconstruction`（用户裁决「就用这个」）；
//    ② `photon_separation` 的 setMaxIOSize 由 (2,4,2,2) **放宽到 (4,10,2,2)**（用户裁决：物品入 2→4、物品出 4→10、流体 2/2 不动）；
//    ③ 星门 3 条的 EUt 按「**MAX+8** = MAX 电压 + 4^8 A」= **2^47 = 140737488355328**（用户裁决；条数**现算**）。
//    ⇒ 后果：**2026-09-26 放宽后不再溢出** —— 原先那 3 条 SLOT-OVER 溢出**已消除**，
//       代际产物里**既没有 `slotOver` 字段、也没有 SLOT-OVER 告警代码**（整段已删）。
//    ✅ 2026-09-27：【已部署】到 GTL山海9.10test\kubejs\server_scripts\
//    ⚠️ 「原初物质解构」这个配方类型是**本版 shanhai 模组新增**的（jar 侧已注册 + 有专属 .rtui），
//       本文件没有也不需要有它的配方；它只是让 JEI/机器多出一个可用分类。
//
// 🔴 本次是【增量更新】（用户原话逐字：「这次添加配方就和上次一样啊，上次我还帮你理解了」
//    ／「每个元件都有新增的配方」）：
//    · 老那 8 条【原样保留、一个字不改】（它们的 spec 从上一版文件逐字搬过来）；
//    · 新增 110 条【追加】进同一个文件。
//    ⇒ 条数（**本次现算**，不再写死）：工作台 24 条 + GT 机器 95 条 = 119 条。
//       其中 GT 那 95 条 = 本文件生成的 92 条 ＋ 老配方逐字保留的 3 条。
//       ⚠️ 工作台那部分 = 文件里硬编码的 6 条 ＋ 从 PF.txt 的「合成样板」现算生成的条数；
//          两者都在本脚本跑完前才算得出来 ⇒ 上面用了占位符，末尾会回填（并自检"占位符已全部替换"）。
//       ⚠️ 这里的总数是 `rows.json` 的**最终**条数（已含「土高炉 ⇒ 原始物质重组」那批 14 条副本）；
//          `样板清单.md` §1 的条数是在**加副本之前**算的（104 条）⇒ 两边数字不同是正常的，不是对不上。
//
// 源数据：PF.txt ← 由环境变量 SH_PF_SRC 指定的那个文件（本仓库不记录机器绝对路径）
//   SHA256 = 66DF80F6F45DD99619829B5249338C44D2393511771B97EDFC54CAA3872BF413
//   118751 B / 单行 NBT / 一个 minecraft:chest
//   ⚠️ 以上两行**每次生成时现读现算**（原先写死的是 2026-09-26 那版的值，早就对不上了）。
//
// =============================================================================
// §0 检查器自证（先说清"我凭什么信自己没看漏"）
// =============================================================================
//  · `ae2:processing_pattern` 逐条解析成功 = 118
//  · `in` 恒为 81 格 / `out` 恒为 27 格（AE2 定长数组，空槽是 `{}`）—— 118/118 条都对（格数为**现算**：取 rows 第一行的长度）
//  · 原文里的纯文本出现次数 = 104，逐条解析成功 = 104 ⇒ **两边相等，无静默漏条**
//  · 正面对照：解析出的 8 条旧配方与上一版文件头 §3 的记录【逐字一致】
//    ⇒ 说明解析器看的是同一批东西（这是"解析器自己是对的"的证据，不是自说自话）
//
// =============================================================================
// §1 元件对照（这次 vs 上次）—— 用户要的第一问
// =============================================================================
//   本次 Slot | 元件名（display.Name 逐字） | 本次条数 | 上次 | 差
//   ----------|---------------------------|---------|------|----
//   Slot 0    | 处理样板ULV               | 35      | 1    | +34
//   Slot 1    | 处理样板LV                | 16      | 2    | +14
//   Slot 2    | 处理样板-星门(MAX+16)     | 3       | 无   | 全新元件
//   Slot 3    | 合成样板                  | 23      | 5    | +18
//   Slot 4    | 处理样板MV                | 17      | 无   | 全新元件
//   Slot 5    | 处理样板HV                | 13      | 无   | 全新元件
//   Slot 6    | 处理样板EV                | 11      | 无   | 全新元件
//   ----------|---------------------------|---------|------|----
//   合计      |                           | 118     | 8    | +110
//
//  ⚠️ 「上次」那一列来自 `LAST_PF`（上一版 PF.txt 的历史留档 —— 旧源文件已被覆盖，算不出来）。
//     本次那一列与差值都是**现算**的。
//  ⚠️ 元件【槽位】：本次 {"处理样板ULV":0,"处理样板LV":1,"处理样板-星门(MAX+16)":2,"合成样板":3,"处理样板MV":4,"处理样板HV":5,"处理样板EV":6}；上次 {"处理样板ULV":0,"合成样板":1,"处理样板LV":2} ⇒ 槽位是否后移，按这两组数自己对（本文件不替用户下结论）。
//
// =============================================================================
// §2 中文名 → `gtceu:<id>` 映射（口径沿用上次：lang 里【唯一精确等于】那条）
// =============================================================================
//   纸上中文名     | gtceu id                            | 来源          | 条数（现算）
//   ---------------|-------------------------------------|---------------|-----
//   原初物质重组    | gtceu:primordial_matter_recombination | shanhai zh_cn | 22
//   土高炉          | gtceu:primitive_blast_furnace         | gtceu zh_cn   | 14
//   原初奇点反演    | gtceu:primordial_singularity_inversion| shanhai zh_cn | 13
//   光子分离        | gtceu:photon_separation               | shanhai zh_cn | 8
//   物质锻造        | gtceu:matter_forging                  | shanhai zh_cn | 6
//   世线电路板组装  | gtceu:wl_board_circuit_assembly       | shanhai zh_cn | 5
//   物质模块铸造    | gtceu:matter_module_casting           | shanhai zh_cn | 5
//   物质流凝结      | gtceu:matter_flow_condensation        | shanhai zh_cn | 5
//   原初因果编织    | gtceu:primordial_causal_weaving       | shanhai zh_cn | 3
//   量子化现实重构  | gtceu:spacetime_distortion            | shanhai zh_cn | 3
//   世线震荡收集    | gtceu:worldline_oscillation_collection| shanhai zh_cn | 2
//   光子虹吸        | gtceu:photon_siphon                   | shanhai zh_cn | 2
//   星际物质吸取    | gtceu:interstellar_matter_absorption  | shanhai zh_cn | 2
//   世线采样        | gtceu:worldline_sampling              | shanhai zh_cn | 1
//   原初世线切割    | gtceu:worldline_cutting               | shanhai zh_cn | 1
//   太虚熔炼        | gtceu:taixu_smelting                  | shanhai zh_cn | 1
//   引力波宏观干涉  | gtceu:gravitational_wave_production   | shanhai zh_cn | 1
//   电路组装机      | gtceu:circuit_assembler               | gtceu zh_cn   | 1
//   ⇒ 纸上出现过的配方类型共 18 种，合计 95 条（= 全部带类型的样板）。
//
//  ✅ 已消除的唯一歧义：纸上写「世线电路板组装」，lang 里是「世线板电路组装」——
//     用户 2026-09-26 亲自裁决：「我写错了」⇒ 按 lang 的 `gtceu:wl_board_circuit_assembly` 落，
//     原先的 ⚠️ 待裁决标记已按用户裁决【移除】。
//
// =============================================================================
// §3 配方类型 id 的存在性核实（正面对照，不是猜）
// =============================================================================
//  · **shanhai 类型总数 = 45**（**现算**：读真源① `shanhai-rewrite\src\main\java\com\shanhai\common\recipe\ShanhaiRecipeTypes.java`
//      的【活代码】`.setMaxIOSize(...)` 条数，与同文件常量 `REAL_TYPE_COUNT = 45` 互为自证；
//      注释作废块里另有 24 条，已按"行首是注释符"剔除。）
//  · 上述类型的 id 存在性由加载期断言 G3 保证（CAP 的每个键都必须能追溯到真源①或真源②），
//      追不到就抛错、**拒绝写产物** —— 所以"存在性"不是靠这里手写一段话，是靠不通过就出不来。
//  · 历史抽检样本（2026-09-26 抽的那 8 个）本次**现查**：spacetime_distortion / primordial_singularity_inversion / matter_flow_condensation / matter_module_casting / photon_siphon / photon_separation / interstellar_matter_absorption / wl_board_circuit_assembly ⇒ **8/8 在**。
//  · gtceu:primitive_blast_furnace：游戏自己的导出表
//      `local\kubejs\export\recipes\gtceu\primitive_blast_furnace\` = **18 个配方文件**（**现算**：数该目录下的 `*.json`）
//      ⇒ 这个配方类型在本包里**真实存在且有配方**。
//  · gtceu:circuit_assembler：同目录下有 **90** 个配方文件（**现算**）。
//  · ⚠️ 反例（防"假否定"）：`local\kubejs\export\registries\item.json` 时间戳 = 2026-09-10 19:19，
//      **早于** shanhai-0.1.0.jar（2026-09-26 11:11）⇒ 那张导出表里 `gtceu:spacetime_distortion` 
//      之类的目录**查不到**。这【不是"不存在"】，是"导出表过时"。
//      ⇒ 所以 shanhai 类型一律改用【已部署 jar 的字节码/lang】取证，不看那张旧表。
//
// =============================================================================
// §4 槽位核对（用 jar 里【真的】setMaxIOSize 值，不是估的）
// =============================================================================
//  shanhai 类型：ShanhaiRecipeTypes.java 的 .setMaxIOSize(物品入,物品出,流体入,流体出)：
//     primordial_power_generator         = (2, 2, 2, 2)
//     primordial_stellar_reaction        = (5, 3, 5, 3)
//     primordial_biological_core         = (6, 3, 3, 3)
//     primordial_matter_recombination    = (12, 3, 6, 3)
//     primordial_causal_weaving          = (12, 3, 6, 3)
//     primordial_singularity_inversion   = (12, 3, 6, 3)
//     taixu_smelting                     = (2, 2, 2, 1)
//     worldline_oscillation_collection   = (16, 2, 4, 2)
//     interstellar_matter_absorption     = (2, 2, 2, 2)
//     matter_flow_condensation           = (4, 2, 2, 2)
//     primordial_energy_absorption       = (1, 2, 2, 2)
//     photon_separation                  = (4, 10, 2, 2)
//     matter_module_casting              = (17, 1, 4, 0)
//     matter_forging                     = (9, 1, 2, 1)
//     primordial_matter_deconstruction   = (1, 103, 1, 16)
//     wl_board_circuit_assembly          = (9, 3, 6, 4)
//     wl_board_wafer_etching             = (6, 3, 4, 3)
//     proxy_execution                    = (0, 0, 0, 0)
//     coin_forge                         = (9, 6, 6, 3)
//     nine_industrial                    = (24, 24, 12, 12)
//     black_hole_event_horizon_blast     = (3, 9, 3, 6)
//     black_hole_neutronium_compressor   = (9, 6, 6, 5)
//     black_hole_compressor              = (9, 6, 6, 5)
//     high_dimensional_fragment_cutting  = (4, 9, 2, 4)
//     worldline_cutting                  = (6, 6, 4, 4)
//     worldline_sampling                 = (3, 12, 3, 6)
//     worldline_matter_recurrence        = (9, 6, 6, 3)
//     worldline_probability_cracking     = (6, 9, 4, 4)
//     photon_siphon                      = (4, 2, 2, 2)
//     zero_point_conversion              = (2, 2, 2, 2)
//     matter_aggregation                 = (2, 2, 0, 0)
//     gravitational_wave_consumption     = (1, 0, 1, 0)
//     tianjie_navigation                 = (6, 3, 6, 3)
//     nebula_siphoning                   = (6, 3, 6, 3)
//     chaos_crafting                     = (24, 24, 12, 12)
//     seventy_two_changes                = (1, 1, 0, 0)
//     gravitational_wave_production      = (2, 2, 2, 2)
//     primordial_myriad_ascension_tier_1 = (4, 0, 4, 0)
//     primordial_myriad_ascension_tier_2 = (4, 0, 4, 0)
//     kmyy                               = (2, 1, 0, 0)
//     spacetime_distortion               = (9, 6, 6, 5)
//     primordial_laser_etching           = (3, 1, 1, 0)
//     primordial_swarm_casting           = (6, 1, 3, 0)
//     primordial_matter_forming          = (6, 1, 1, 0)
//     primordial_debug                   = (6, 6, 2, 2)
//     circuit_assembler                  = (6, 1, 1, 0)
//     primitive_blast_furnace            = (3, 3, 0, 0)
//
//  上表 = 【真源现读现算】的结果（2026-09-29 起），不再手抄：
//    真源① shanhai 类型 ⇒ shanhai-rewrite\src\main\java\com\shanhai\common\recipe\ShanhaiRecipeTypes.java
//        的【活代码】`.setMaxIOSize(a,b,c,d)`（行首是注释符的作废块会被剔除）。
//    真源② gtceu 原生类型 ⇒ recipe-convert\javap\GTRecipeTypes.txt（已部署 gtceu jar 的 javap -c 转储）。
//    ⇒ 键集合若与真源对不上，生成器**抛错并拒绝写产物**（双向断言：缺键 / 多键 / 用到的类型查不到）。
//  · gtceu:primitive_blast_furnace = (3, 3, 0, 0)
//      —— 出处：gtceu jar `GTRecipeTypes.class` 字节码偏移 3028-3032：
//         iconst_3 / iconst_3 / iconst_0 / iconst_0 → setMaxIOSize
//      ⚠️ 流体入=0 流体出=0 ⇒ 土高炉配方【不许带流体】，本文件 14 条土高炉确实一条流体都没有 ✓（条数**现算**：数 specs 里 type=primitive_blast_furnace 的条数）
//      ⚠️ 导出表里现有 18 条 primitive_blast_furnace 配方，其实测最大值是 (2,2,0,0)（没填满 3）——**历史值 2026-09-26**，需扫导出表 JSON 才能现算；文件数已现算，括号里的最大值请连日期一起引。
//  · gtceu:circuit_assembler = (6, 1, 1, 0)
//      —— 出处：同一份转储偏移 2308-2313：bipush 6 / iconst_1 / iconst_1 / iconst_0 → setMaxIOSize
//      ⚠️ 上面两条的数值结论已由本表（现读现算）给出，这里只留取证过程，不再复写一份数字。
//
//  🔴 三条占用规则（沿用上次口径，**没变**）：
//     · `.notConsumable(...)`【占】1 个物品输入槽（和普通输入一样算）
//     · `.circuit(N)`【占】1 个物品输入槽
//     · 物质模块【等级门槛】不占槽（它是准入判据，不是输入物）
//  ⇒ 每条新增配方都算过 slotIn / slotOut，逐条结果见 §7 的表。
//
//  🔴🔴 槽位核对【曾查出 8 条溢出】—— ✅ **本版已由用户裁决解决**：
//    （下表**按本次 specs 现算**，行号/条数都不写死）
//
//    PF.txt#  | 配方 id                    | 类型               | 物品入 | 旧 cap | 新 cap
//    ---------|----------------------------|--------------------|--------|--------|-------
//    #24      | shanhai:pf/electron        | photon_separation  | 3      | 2 ❌    | 4 ✓
//    #30      | shanhai:pf/photon_2        | photon_separation  | 3      | 2 ❌    | 4 ✓
//    #34      | shanhai:pf/photon_rainbow  | photon_separation  | 3      | 2 ❌    | 4 ✓
//    #65      | shanhai:pf/up_quark        | photon_separation  | 3      | 2 ❌    | 4 ✓
//    #67      | shanhai:pf/up_quark_2      | photon_separation  | 3      | 2 ❌    | 4 ✓
//    #68      | shanhai:pf/down_quark      | photon_separation  | 3      | 2 ❌    | 4 ✓
//    #70      | shanhai:pf/down_quark_2    | photon_separation  | 3      | 2 ❌    | 4 ✓
//    #92      | shanhai:pf/muon            | photon_separation  | 3      | 2 ❌    | 4 ✓
//
//    三条都是同一形状：1~2 个真物品 + 1 个 notConsumable(力场发生器) + 1 个 .circuit(1) = 3 格，
//    而旧 photon_separation 的 setMaxIOSize 是 (2,4,2,2) ⇒ 物品入只有 2 格。
//    🔴 用户 2026-09-26 裁决（逐字）：「photon_separation 的 setMaxIOSize(2, 4, 2, 2) ⇒ (4, 10, 2, 2)」
//       ⇒ 物品入 2 ⇒ **4**（3 格装得下，留 1 格余量）、物品出 4 ⇒ **10**；**流体 2/2 不动**。
//    ⚠️ 旧 cap (2,4,2,2) 是从【当时已部署的 jar】字节码读出来的真值：
//       mods\shanhai-0.1.0.jar!com/shanhai/common/recipe/ShanhaiRecipeTypes.class
//       photon_separation 段：iconst_2 / iconst_4 / iconst_2 / iconst_2 → setMaxIOSize
//    ✅ 新 cap (4,10,2,2) 是【本版 jar】的真值，出处 = javap -c 成品 jar 的 ShanhaiRecipeTypes.init()：
//       该调用点操作数 = iconst_4 / bipush 10 / iconst_2 / iconst_2（2026-09-26 实测）。
//    ⇒ 2026-09-26 放宽后不再溢出 ⇒ 这 3 条的 `slotOver` 标记与整段 SLOT-OVER 告警【已删除】。
//    ⚠️ 附注：若把物质模块从"等级门槛"降级回"催化剂"（§7① 的另一条路），这三条会变成 4 格 ——
//       正好用满 cap[0]=4，仍然装得下（旧 cap=2 时才是真的装不下）。
//
// =============================================================================
// §5 KubeJS / Rhino 写法纪律（与上一版完全一致，刻意只用最保守的写法）
// =============================================================================
//  · 全局只用 var；不用 let/const、不用箭头函数、不用模板字符串、不用 ?.、不用解构
//  · 不用 conditions（assembler 类配方设它会报错）
//  · 编程电路用 .circuit(数字)——照宿主现成写法（gtceu.js:3301-3303 / ae2.js:361-362）
//  · 「不消耗（催化剂）」用 .notConsumable(...)，照宿主 gtceu.js:935 / 3302 / 9955
//  · 每条配方各自包 try/catch —— 一条失败只让一条失败，不连坐
//  · 配方 id 全部显式给死（.id(...)），避免 /kubejs reload 时自动 id 变化导致旧配方残留
//
// =============================================================================
// §6 🔴 改名纸 = 注记，不是配方输入（沿用上次口径）
// =============================================================================
//  ⇒ 本文件【任何地方都不出现 minecraft:paper】—— 除非它是**没改名的真产物**
//    （本次确实有 2 条：#15 / #114「产出 minecraft:paper」是真的出纸，那些保留）。
//  · 纸有三种：
//      ① 「配方类型：XXX」（也有裸写类型名的，如「光子虹吸」「电路组装机」）⇒ 决定用哪台机器
//      ② 「Ns」⇒ 决定耗时（×20 = tick）
//      ③ 备注（本次四种：物质模块是催化剂 80 条 / 力场发生器是催化剂 4 条 / 夸克释放催化剂作为催化剂 2 条 / 电子中微子产出概率5% 1 条）
//         ⚠️ 上面每个数都是【行数】不是【纸数】：rows.json 里含「原初物质重组 ⇒ 土高炉」的副本行，
//            同一张纸会被算多行 ⇒ 例如「物质模块是催化剂」纸面只有 43 张、这里会显示 57 行。
//  · 🔴 纸写在 in 里，也可能写在 **out** 里 —— #24（1 条）的「电子中微子产出概率5%」就在 out[3]。
//    本文件把"带自定义名的纸"从 out 里剔除，只留真产物。
//
// =============================================================================
// §7 🔴 三处口径（**先报出来，没自己选**）：
// =============================================================================
//  ①物质模块怎么落 —— 🔴🔴 2026-10-03 用户点单（**本版唯一判据 = 只看纸**）
//     ✅ 用户 2026-10-03 原话（逐字）：
//        「你又写错了，这条配方物质模块要消耗，而不是催化剂，判断物质模块是否是催化剂仅凭借是否我放了那张纸」
//     ⇒ 判据 = 只看纸：纸上有没有那张 `name === '物质模块是催化剂'` 的 NOTE（≡ papers 里 kind='NOTE' 的那张）。
//        · 【有纸】⇒ 不消耗，且【形态保持现状】：
//             - 该配方类型 ∈ SHANHAI_TYPES（山海自己注册的类型）⇒ 落 ModuleLevelCondition 等级门槛（不占输入槽）
//             - 非山海类型（如土高炉 primitive_blast_furnace）⇒ 落 `.notConsumable(...)` 真催化剂
//        · 【无纸】⇒ 消耗：模块物品**进 itemInputs**（数量照纸上那个格子的数量，通常 1x），
//             既不挂门槛、也不进 notConsumable
//     📌 「制作物质模块的配方 ⇒ 消耗」这条口径【保留】（已核对，没退化）：
//        · 本次 PF.txt 里"产出含物质模块"的配方共 5 条，它们纸上【全都没有】催化剂纸 ⇒ 按"只看纸"天然落【消耗】，与那条口径一致。
//        · ⚠️ 反过来的情况（配方产出含物质模块【且】纸上有催化剂纸）本次 = 0 条 —— 生成器对这种情况**不自己选**：会打一条「🔴 口径打架」告警并请用户裁决，绝不静默。
//     🔴 本版相对上一版的落法变化（**现算**，判据 = 旧口径 `isModuleRecipe || (isWorldlineRecipe && !isShardFamilyRecipe)` vs 新口径 `!纸`）：
//        `shanhai:pf/circult_process_module_1`（PF 第 98 条：gate ⇒ consume）（1 条）
//        ⚠️ 只有上列这些条目的**配方数据**（itemInputs / notConsumable / moduleLevelRequirement）会变；
//           其余含模块的配方只是自报行（🧪）文案跟着新口径重写了，落法与数据一字未动。
//     🔴🔴 2026-10-03 追加（用户点单）：**老 3 条 GT 配方也走这条判据**（它们原先漏在外面）
//        【根因】rows.json 里 8 个 `old: true` 的行被分成 `oldRows`，而 `specs` 只由 `newRows` 构建 ⇒
//               `specFromRow()`（判据所在）**从没在这些行上跑过**；它们的正文由 `emitOldGt()` 用
//               **冻结文本**发出，老 ⑦（= PF 第 36 条 = `shanhai:pf/photon`）把
//               `moduleLevelRequirement: 1x 入门物质模块` 与 `itemInputs: []` 直接写死。
//        【修法】顶层新增 `LEGACY_GT_BIND`（老配方 id → 源样板行号，加载期逐条自证）+ `legacyGtRule()`，
//               与 `specFromRow()` **共用同一个 `moduleRuleOf()`** ⇒ 一条判据、两处落点。
//        【本次读数（现算）】
//           · `shanhai:pf/primordial_omega_engine` ⇐ PF 第 21 条｜纸：**无**催化剂纸 ⇒ **consume**（本条输入里本来就没有物质模块 ⇒ 落法无变化）
//           · `shanhai:pf/photon` ⇐ PF 第 36 条｜纸：**无**催化剂纸 ⇒ **consume**（itemInputs += 1x shanhai:basic_material_module；moduleLevelRequirement / moduleLevelFallbackCatalyst 两行【消失】）
//           · `shanhai:pf/first_light` ⇐ PF 第 27 条｜纸：**无**催化剂纸 ⇒ **consume**（本条输入里本来就没有物质模块 ⇒ 落法无变化）
//        ⚠️ 本条只改**模块落法**：产出 / 时长 / EUt / 流体 / `notConsumable` / circuit 一个字都没动。
//     🔴 「什么算物质模块」以【权威 17 项表】为准，**不是**正则匹配名字：
//        出处 = `com/shanhai/machine/module/PrimordialModuleMachine.MODULE_LEVELS`（17 项，等级 1..17），
//        与 lang 的 `shanhai.recipe.fail.module_level.unresolved`「…不在 17 个物质模块表里」同一口径。
//        ⚠️ 其中 6 个 id 并不以 `material_module` 结尾（material_deduction_module /
//           material_recombination_module / imaginary_material_transition_remolding_module /
//           material_creation_module / reality_anchor_module / genesis_reality_modification_module）
//           ⇒ 任何"按名字正则"的写法都会漏掉它们（本生成器 2026-09-28 之前就是这么漏的）。
//     · 已取证：`mods\shanhai-0.1.0.jar` 里 **存在** 
//       `com/shanhai/machine/module/ModuleLevelCondition.class` ⇒ 门槛机制可挂。
//     · 本文件的做法：门槛形态由 `SHANHAI_PF_MODULE_MODE` 一行可切（'gate' 默认 ／ 'catalyst' 全退催化剂）。
//       ⚠️ 降级通道【保留】（jar 没绑 / `typeof` 判不到类时自动退回催化剂，配方不会消失）。
//     · ⚠️ 另有 0 条样板有「物质模块是催化剂」这张纸却【没有物质模块物品】⇒ 门槛无从挂起，
//       （上面这个数是按本次 specs 的 flags **现算**的；纸写「物质模块是催化剂」的样板共 80 条：1 / 2 / 4 / 5 / 6 / 8 / 9 / 10 / 11 / 12 / 13 / 14 / 15 / 16 / 17 / 18 / 19 / 20 / 22 / 24 / 25 / 26 / 28 / 29 / 30 / 31 / 32 / 33 / 34 / 35 / 37 / 38 / 39 / 40 / 65 / 66 / 67 / 68 / 70 / 74 / 75 / 76 / 77 / 78 / 79 / 80 / 81 / 82 / 83 / 84 / 86 / 87 / 88 / 89 / 90 / 91 / 92 / 93 / 94 / 95 / 96 / 97 / 99 / 102 / 103 / 104 / 105 / 106 / 107 / 108 / 109 / 110 / 111 / 112 / 113 / 114 / 115 / 116 / 117 / 118）
//       本文件按"无门槛无催化剂"落，并在脚本里逐条注明。
//     📌 留档 —— 本版【取代】的两条旧口径（结论已被覆盖，只留证据链）：
//        · 2026-09-28（逐字）：「有些配方的物质模块的配置错了，目前，注意是目前只有制作物质模块和世线的配方才需要消耗物质模块」
//          ⇒ 当时把判据从"看纸"改成"看产出与配方类型"（`consumeModule = isModuleRecipe || (isWorldlineRecipe && !isShardFamilyRecipe)`）。
//          ⚠️ 那正是本版要改掉的：纸上一句话都没写的配方被【静默】判成不消耗（用户报的 no=98 就是这种）。
//        · 2026-10-01（逐字）：「那条配方的模块」⇒ 选「B. 等级门槛（不烧、但要挂）」；
//          「世线残片其余 6 档 ＋ 寰宇并行超限器」⇒ 选「A. 还没写，以后补」⇒ **不许动**。
//          ⇒ 当时给"世线族 ⇒ 消耗"再加一层"看产出是不是残片族"（SHARD_FAMILY，产出 ∈ 残片族 ⇒ 不消耗）。
//          ✅ 本版下该结论【仍然成立】（本次命中它的 1 条产出 ∈ 残片族的配方纸上都有催化剂纸 ⇒ 照样不消耗）；
//            但残片族已【不再参与判定】，只在产物注释里留一句"与 2026-10-01 结论也一致"。
//        · 2026-09-26（更早，逐字）：「以后物质模块是催化剂指的都是我们今天刚写好的机制」
//          ⇒ 当时口径 = 「纸上写 ⇒ 等级门槛」，并给 `primitive_blast_furnace` 单开一张 NO_GATE_TYPES 白名单。
//          本版回到"看纸"，但那**不是**回到这一版：本版有显式正向表 SHANHAI_TYPES 决定"不消耗时的形态"，
//          白名单式的反写逻辑【不再使用】。
//  ②「力场发生器是催化剂」—— #24 / #30 / #34 / #92（4 条） —— 🔴 2026-10-03 判据已由【写死 LV】改成【认整族】
//       现判据（两条**同时**满足）：① 纸写了这句话；② 物品 id ∈ 力场发生器族 = `/^(?:gtceu|gtlcore):[a-z0-9_]*_field_generator$/`
//         （命名空间锚死在 `gtceu:` / `gtlcore:` —— 裸后缀 `/field_generator$/` 会误吞
//          `kubejs:containment_field_generator` 与 `kubejs:spacetime_compression_field_generator`）
//       📌 族自证（正负对照，生成期现读导出注册表）：以 `field_generator` 结尾的 id 共 16 个；判据命中 14 个 [gtceu:ev_field_generator / gtceu:hv_field_generator / gtceu:iv_field_generator / gtceu:luv_field_generator / gtceu:lv_field_generator / gtceu:mv_field_generator / gtceu:opv_field_generator / gtceu:uev_field_generator / gtceu:uhv_field_generator / gtceu:uiv_field_generator / gtceu:uv_field_generator / gtceu:uxv_field_generator / gtceu:zpm_field_generator / gtlcore:max_field_generator]；被排除 2 个 [kubejs:containment_field_generator / kubejs:spacetime_compression_field_generator]
//       ✅ 因这条改动而改变的配方（**现算**，不是手抄）：`shanhai:pf/electron`（1x gtceu:lv_field_generator）；`shanhai:pf/photon_2`（1x gtceu:lv_field_generator）；`shanhai:pf/photon_rainbow`（1x gtceu:lv_field_generator）；`shanhai:pf/muon`（1x gtceu:hv_field_generator）
//       📌 留档（被本版覆盖的旧口径，2026-09-30 我写下的原文**逐字**，一字未改）：
//          「这条规则在代码里是【硬编码 LV】的 —— 判据写死成 `it.id === 'gtceu:lv_field_generator'`。」
//          「本次 3 条命中的确实都是 LV，所以落法正确；但**换一台 MV/HV 力场发生器就会静默不生效**
//           （物品照常被消耗、且不报错）。旁证：PF 第 58 条用的是 `gtceu:mv_field_generator`，
//           它身上没有这张纸所以没暴露。⇒ 建议改成"凡 `*_field_generator` 且纸写了这句 ⇒ 催化剂"。」
//          「我没动它：那会改变 58 条吗？不会（它没这张纸）—— 但它属于「扩大规则覆盖面」，
//           按本轮硬要求②（改数值/口径要停下报）留给你裁决。」
//       ⇒ 用户 2026-10-03 拍板：修。上面那个"建议"已落地，但**没有**照它字面用裸 `*_field_generator`
//          （裸后缀会误吞那 2 个 kubejs 的同类 id），改用命名空间锚定 + 注册表现场自证。
//  ④「夸克释放催化剂作为催化剂」—— #67 / #70（2 条）（**本轮新增支持**）
//       纸面原文逐字：「夸克释放催化剂作为催化剂」（**末尾没有句号**）。
//       ⇒ 本文件按 `.notConsumable('<纸上那个数量>x shanhai:<上|下>_quark_emission_catalyst')` 落，
//          **数量照纸上一字未改**（本次两条都是 64）；本轮只改"消不消耗"，一个数字都没动。
//       🔴 本轮之前这句话【一个字都没被读到】⇒ 那 64 个按普通材料落进 itemInputs 被吃掉。
//          取证：把 PF 副本里这句话改名后重跑，specs 的**配方形状差异 = 0** ⇒ 反证"当前完全没读"。
//       ⚠️ 只对**纸上写了这句话**的配方生效 ⇒ 第 59 / 63 条（同样有 ×1 夸克释放催化剂，
//          但纸上没有这句话）保持原样：仍在 itemInputs 里被消耗。这个不对称**留给你裁决**。
//  ③「电子中微子产出概率5%」—— #24（1 条），且写在该样板的 **out[3]**，紧邻 out[2] 的
//       `shanhai:electron_neutrino`。
//     ✅ 用户 2026-09-26 裁决（原话逐字）：「吃加成」
//     ⇒ 本文件落 `.chancedOutput('1x shanhai:electron_neutrino', 500, 100)`
//
//     🔴 参数 1：**单位是万分比**（`getMaxChancedValue()` 反读 = `sipush 10000`）
//        ⇒ **5% = 500**，不是 5000（5000 = 50%，会差 10 倍）。
//        取证：宿主 gtceu.js:6148/6212/6250 (2000,0)=20% ／ :8405 (1000,0)=10% ／
//              :6717 (200,20)=2% ／ ad.js:96 (5000,0)=50%；
//              且游戏导出表里 chance 的最大值就是 10000。
//
//     🔴 参数 2：**它不是"加成上限"，是【每超频一级的加成量 tierChanceBoost】**。
//        字节码实证 `GTRecipeBuilder.chancedOutput(ItemStack,int,int)`：
//          67: aload_0 / 68: iload_2 / 69: putfield chance:I
//          72: aload_0 / 73: iload_3 / 74: putfield tierChanceBoost:I     <-- 第三个参数进这里
//        ⚠️ `maxChance` 从 KubeJS 侧【设不了】，它保持默认 10000。
//
//     🔴 **100 这个数是从哪来的（不是猜的）**：GTCEu 自己的"5% 档副产"标准值。
//        反读游戏导出表 93,897 个文件、348,092 条 chanced 记录后，
//        `chance=500 / maxChance=10000 / tierChanceBoost=100` 这个三元组出现 **414 次**，
//        全部来自 GTCEu 的矿石副产线：`gtceu:macerator` 138 ／ `gtceu:integrated_ore_processor` 138 ／
//        `gtceu:space_ore_processor` 138。⇒ 这就是本包"5% 且带加成"的标准配法。
//        （同族还有 1400/850、200/20、50/5 —— 即 GTCEu 的 14% / 5% / 2% / 0.5% 副产阶梯。）
//
//     🔴 **加成到底怎么算**（`ChanceBoostFunction.OVERCLOCK` 反读，逐条对字节码）：
//          int tierDiff = machineTier - recipeTier;
//          if (tierDiff <= 0) return chance;          // 没超频 ⇒ 原始概率，吃不到加成
//          if (recipeTier == 0) tierDiff = tierDiff - 1;
//          return chance + tierChanceBoost * tierDiff;
//        ⇒ #24（1 条）的 recipeTier = LV(1)，实际概率随机器超频：
//            LV(1) → 5% ／ MV(2) → 6% ／ HV(3) → 7% ／ EV(5) → 9% ／ MAX(14) → 18%
//          （公式里没有封顶，但 `maxChance` = 10000 = 100% 就是天花板）
//
// =============================================================================
// §8 🔴 元件「处理样板-星门」的 EUt —— 算式 + 溢出判断
//    （2026-09-26 第二次改判：MAX+16 ⇒ **MAX+8**）
// =============================================================================
//  ✅ 用户最新裁决（原话逐字）：「溢出那就算了，改成 max+8=max,4^8A」
//     ⇒ 电压 = MAX 档，电流 = **4^8 A**。
//     ⚠️ 本段【覆盖】上一版「MAX+16=MAX，4^16A」的裁决；上一版的算式与结论
//        按本工程惯例【原样保留在 ⑥ 作留档】，但不再是当前口径。
//
//  ① V[MAX] 的真值 —— 【从字节码读的，不是从注释抄的】
//     `libs\gtceu-1.20.1-1.4.4.jar!com/gregtechceu/gtceu/api/GTValues.class`（javap -p -c）
//     该 class 的 sha256 = 7A7275B8018D78876D2F1AB3D6B14644D7D5145EDBC16D35C3B9D99146D33375
//     `<clinit>`：`bipush 14` → `ldc2_w // long 2147483648l` → `lastore` → `putstatic V:[J`
//     V 是 **15 项 long[]**，索引 0..14 = ULV..MAX（索引 13 = 536870912）；同文件 VN[14] = `"MAX"`
//     ⇒ **V[MAX] = 2147483648（= 2^31）**
//     ⚠️ 注意：**不是** Integer.MAX_VALUE(2147483647)，也**不是** 1.7 时代注释里那种写法 ——
//        这个数字如果照注释猜，整条算式的答案会差一位。
//
//  ② 4^8 的值
//     4^8 = (2^2)^8 = **2^16 = 65536**
//
//  ③ 算式与结果（精确整数运算，不是浮点）
//     EUt = V[MAX] × 4^8
//         = 2147483648 × 65536
//         = 2^31 × 2^16
//         = **2^47 = 140737488355328**
//
//  ④ 🔴 溢出判断：**【不溢出】✓**（这是本次改判的全部目的）
//        Long.MAX_VALUE = 2^63 − 1 = 9223372036854775807
//        2^47 ≈ 1.41e14 ⇒ 只有 Long.MAX 的 **1/65536**（余量 65536 倍）
//        ⇒ 2^47 是合法 long，**不存在回绕**。
//        `.EUt` 的签名已核实：`GTRecipeBuilder EUt(long)` —— 是 long，不是 int，2^47 装得下。
//
//  ⑤ ⇒ 本文件的落地：
//        **EUt = 140737488355328**（= V[MAX] × 4^8 = 2^47）
//        —— "电压 × 电流"这一次可以**整体**进 EUt，不再需要像上一版那样拆开。
//        🔴 事实提示（不是拦阻、也不是待裁决项）：2^47 EUt/t = **65536 倍 MAX 电压**。
//           配方的 EUt 是"每 tick 电压需求"，GT 侧要能找到供得起这个数的能源仓它才跑得起来。
//           这是数值/玩法层面的事；本条只负责"算式不溢出、写进去的值不违法"。
//        ⚠️ 这三条配方所在的**元件名**仍写作「处理样板-星门(MAX+16)」——
//           那是 AE2 样板自己的**显示名**（PF.txt 源数据原文），本次不要求改样板，
//           故文件里保持原样；本段只改这 3 条配方的 EUt 值。
//
//  ⑥ 📌 留档：**已作废的上一版口径**（2026-09-26 早先裁决「MAX+16=MAX，4^16A」）
//     4^16 = 2^32 = 4294967296
//     EUt = 2^31 × 2^32 = 2^63 = 9223372036854775808 = Long.MAX_VALUE **+ 1**
//     ⇒ **恰好越界 1** ⇒ Java long 二进制补码回绕成 Long.MIN_VALUE = −9223372036854775808（负数！）
//     ⇒ 上一版因此只能把 EUt 写成 V[MAX] = 2147483648（只放电压部分），安培不放进 EUt。
//     ⇒ 改成 4^8 后该约束消失，故本版把 2^47 整体写进 EUt。
//
//  · 「合成样板」的 6 条是工作台配方（event.shaped，有序），摆位 = in 下标 0..8 行优先 ——
//      （条数用占位符回填**现算**：SHAPED 数组在本脚本后半段才建出来；原先是写死的 5，而实际是 6。）
//      这是上次已由用户核对确认的口径（原话「这下对了」），本次**一个字没改**。
//
// =============================================================================
var SHANHAI_PF_TAG = '[SHANHAI-PF]'

// 🔴 §7① 的切换点：'gate' = 物质模块等级门槛（默认）／ 'catalyst' = 老写法（不消耗催化剂）
//    ⚠️ 2026-10-03：它只影响【纸上写了催化剂纸】那些配方的"不消耗形态"，
//       "要不要消耗"由纸单独决定（无纸的条目根本不看这个开关）。
var SHANHAI_PF_MODULE_MODE = 'gate'

// 老山海 module_level 条件类是否已由 jar 侧注册并绑定（见 §7①）。
// ⚠️ 必须用 typeof 判：直接引用未绑定的标识符会 ReferenceError，而 typeof 不会抛。
var SHANHAI_HAS_MODULE_LEVEL_CONDITION = (typeof ModuleLevelCondition !== 'undefined')

// 解析老山海写法 "Nx <物品id>" -> [物品id, 数量]（无 Nx 时数量默认 1）。
// 逐句对齐 DShanhaiRecipeEngine.addOneCondition 的解析口径（照上一版搬）。
var shanhaiParseModuleLevel = function (text) {
    var s = String(text).trim()
    var count = 1
    var id = s
    var xi = s.indexOf('x')
    var c0 = s.length > 0 ? s.charCodeAt(0) : 0
    if (xi > 0 && c0 >= 48 && c0 <= 57) {
        count = parseInt(s.substring(0, xi), 10)
        if (isNaN(count) || count <= 0) { count = 1 }
        id = s.substring(xi + 1).trim()
    }
    return [id, count]
}

// 该走等级门槛吗？（模式 = gate  且  jar 侧条件类已绑定）
var shanhaiUseLevelGate = function (r) {
    if (SHANHAI_PF_MODULE_MODE !== 'gate') { return false }
    if (!r.moduleLevelRequirement) { return false }
    return SHANHAI_HAS_MODULE_LEVEL_CONDITION
}

// -----------------------------------------------------------------------------
// 老 ①..⑧ 里的 ②③④⑤⑥：工作台配方（有序 shaped）
// 🔴 与上一版【逐字相同】，本次未改动（用户口径：老 8 条保留不动；条数**现算**）
// 摆位 = PF.txt 该样板 in 数组下标 0..8 按【行优先】
// -----------------------------------------------------------------------------
var shanhaiPfShaped = [
    {
        id: 'shanhai:pf_crafting/primordial_divergence_generator',
        out: 'shanhai:primordial_divergence_generator',
        pattern: ['ABA', 'BCB', 'ABA'],
        keys: {
            A: 'gtceu:primitive_void_ore',
            B: 'gtceu:ulv_fragment_world_collection_machine',
            C: 'shanhai:primordial_engine_core'
        }
    },
    {
        id: 'shanhai:pf_crafting/wl_board_ulv',
        out: 'shanhai:wl_board_ulv',
        pattern: ['ABA', 'BCB', 'ABA'],
        keys: {
            A: 'gtceu:pulsating_alloy_ingot',
            B: 'gtceu:certus_quartz_gem',
            C: 'kubejs:ulv_universal_circuit'
        }
    },
    {
        id: 'shanhai:pf_crafting/primordial_engine_core',
        out: 'shanhai:primordial_engine_core',
        pattern: ['PRP', 'MTB', 'AGA'],
        keys: {
            P: 'gtceu:spacetime_tiny_fluid_pipe',
            R: 'gtlcore:primitive_fluid_regulator',
            M: 'thetornproductionline:celestial_secret_deducing_module_ulv',
            T: 'gtlcore:treasures_crystal',
            B: 'shanhai:wl_board_ulv',
            A: 'gtlcore:primitive_robot_arm',
            G: 'ae2:fluix_glass_cable'
        }
    },
    {
        id: 'shanhai:pf_crafting/introductory_material_module',
        out: 'shanhai:introductory_material_module',
        pattern: ['CRD', 'BTB', 'DPC'],
        keys: {
            C: 'gtlcore:mining_crystal',
            R: 'kubejs:reactor_core',
            D: 'minecraft:diamond',
            B: 'shanhai:wl_board_ulv',
            T: 'gtlcore:treasures_crystal',
            P: 'gtceu:spacetime_tiny_fluid_pipe'
        }
    },
    {
        id: 'shanhai:pf_crafting/primordial_matter_caster',
        out: 'shanhai:primordial_matter_caster',
        pattern: ['LSL', 'SKS', 'LSL'],
        keys: {
            L: 'thetornproductionline:celestial_secret_deducing_module_lv',
            S: 'ae2:molecular_assembler',
            K: 'shanhai:primordial_engine_core'
        }
    },
    {
        id: 'kubejs:industrial_steam_casing',
        out: 'gtceu:industrial_steam_casing',
        pattern: ['AAA', 'BCA', 'DEF'],
        keys: {
            A: 'gtceu:bronze_plate',
            B: '#forge:tools/hammers',
            C: 'gtceu:bronze_frame',
            D: 'gtceu:bronze_rotor',
            E: 'gtceu:bronze_gear',
            F: 'gtceu:bronze_rotor'
        }
    },
    {
        id: 'shanhai:pf_crafting/primordial_assembly_line_module',
        out: 'shanhai:primordial_assembly_line_module',
        pattern: ['ABA', 'CDC', 'EBE'],
        keys: {
            A: 'gtceu:lv_assembler',
            B: 'shanhai:wl_board_ulv',
            C: 'thetornproductionline:celestial_secret_deducing_module_ulv',
            D: 'shanhai:primordial_engine_core',
            E: 'gtceu:lv_circuit_assembler'
        }
    },
    {
        id: 'shanhai:pf_crafting/primordial_biological_core',
        out: 'shanhai:primordial_biological_core',
        pattern: ['ABA', 'CDE', 'FBF'],
        keys: {
            A: 'gtceu:slaughterhouse',
            B: 'gtceu:mv_world_accelerator',
            C: 'shanhai:wl_board_mv',
            D: 'shanhai:primordial_engine_core',
            E: 'thetornproductionline:celestial_secret_deducing_module_mv',
            F: 'gtceu:greenhouse'
        }
    },
    {
        id: 'shanhai:pf_crafting/primordial_world_fragments_collector',
        out: 'shanhai:primordial_world_fragments_collector',
        pattern: ['ABA', 'CDE', 'AFA'],
        keys: {
            A: 'gtceu:ulv_fragment_world_collection_machine',
            B: 'gtceu:steel_drill_head',
            C: 'shanhai:wl_board_lv',
            D: 'shanhai:primordial_engine_core',
            E: 'thetornproductionline:celestial_secret_deducing_module_lv',
            F: 'gtceu:primitive_void_ore'
        }
    },
    {
        id: 'shanhai:pf_crafting/primordial_singularity_inversion_core',
        out: 'shanhai:primordial_singularity_inversion_core',
        pattern: ['ABA', 'CDC', 'ABA'],
        keys: {
            A: 'gtlcore:treasures_crystal',
            B: 'gtceu:lv_field_generator',
            C: 'thetornproductionline:celestial_secret_deducing_module_lv',
            D: 'shanhai:primordial_engine_core'
        }
    },
    {
        id: 'shanhai:pf_crafting/primordial_chaotic_ephemeral_deconstruction_crystallization_furnace',
        out: 'shanhai:primordial_chaotic_ephemeral_deconstruction_crystallization_furnace',
        pattern: ['ABC', 'DED', 'FGH'],
        keys: {
            A: 'gtceu:lv_electrolyzer',
            B: 'gtceu:lv_centrifuge',
            C: 'gtceu:lv_sifter',
            D: 'shanhai:wl_board_lv',
            E: 'shanhai:primordial_engine_core',
            F: 'gtceu:lv_ore_washer',
            G: 'gtceu:lv_macerator',
            H: 'gtceu:lv_electromagnetic_separator'
        }
    },
    {
        id: 'shanhai:pf_crafting/primordial_critical_processing_module',
        out: 'shanhai:primordial_critical_processing_module',
        pattern: ['ABC', 'DED', 'FGH'],
        keys: {
            A: 'gtceu:lv_extruder',
            B: 'gtceu:lv_compressor',
            C: 'gtceu:lv_bender',
            D: 'shanhai:wl_board_lv',
            E: 'shanhai:primordial_engine_core',
            F: 'gtceu:lv_wiremill',
            G: 'gtceu:lv_forge_hammer',
            H: 'gtceu:lv_forming_press'
        }
    },
    {
        id: 'shanhai:pf_crafting/worldline_cracking_hub',
        out: 'shanhai:worldline_cracking_hub',
        pattern: ['ABA', 'CDE', 'FGH'],
        keys: {
            A: 'gtceu:mv_field_generator',
            B: 'shanhai:unknown_particle',
            C: 'shanhai:wl_board_mv',
            D: 'shanhai:primordial_engine_core',
            E: 'thetornproductionline:celestial_secret_deducing_module_mv',
            F: 'shanhai:up_quark',
            G: 'shanhai:basic_material_module',
            H: 'shanhai:down_quark'
        }
    },
    {
        id: 'shanhai:pf_crafting/primordial_void_induction_armature',
        out: 'shanhai:primordial_void_induction_armature',
        pattern: ['ABA', 'CDC', 'AEA'],
        keys: {
            A: 'gtceu:generator_array',
            B: 'shanhai:wl_board_lv',
            C: 'gtmthings:lv_wireless_energy_receive_cover',
            D: 'shanhai:primordial_engine_core',
            E: 'shanhai:electron_neutrino'
        }
    },
    {
        id: 'shanhai:pf_crafting/primordial_molecular_rift_core',
        out: 'shanhai:primordial_molecular_rift_core',
        pattern: ['ABC', 'DED', 'FGH'],
        keys: {
            A: 'gtceu:lv_chemical_reactor',
            B: 'gtceu:lv_fluid_regulator',
            C: 'gtceu:lv_distillery',
            D: 'shanhai:wl_board_lv',
            E: 'shanhai:primordial_engine_core',
            F: 'gtceu:lv_fermenter',
            G: 'gtceu:lv_electric_pump',
            H: 'gtceu:lv_fluid_heater'
        }
    },
    {
        id: 'shanhai:pf_crafting/taixu_smelting_furnace',
        out: 'shanhai:taixu_smelting_furnace',
        pattern: ['ABA', 'CDC', 'EBE'],
        keys: {
            A: 'gtceu:electric_blast_furnace',
            B: 'gtceu:lv_alloy_smelter',
            C: 'shanhai:wl_board_lv',
            D: 'shanhai:primordial_engine_core',
            E: 'gtceu:lv_electric_furnace'
        }
    },
    {
        id: 'shanhai:pf_crafting/gravity_hatch',
        out: 'gtceu:gravity_hatch',
        pattern: ['ABA', 'CDC', 'ABA'],
        keys: {
            A: 'shanhai:graviton',
            B: 'gtceu:auto_maintenance_hatch',
            C: 'shanhai:wem_1',
            D: 'shanhai:virtual_image_material_module'
        }
    },
    {
        id: 'shanhai:pf_crafting/primordial_multidimensional_implosion_core',
        out: 'shanhai:primordial_multidimensional_implosion_core',
        pattern: ['ABA', 'CDE', 'ABA'],
        keys: {
            A: 'gtceu:implosion_compressor',
            B: 'gtceu:hv_field_generator',
            C: 'kubejs:hv_universal_circuit',
            D: 'shanhai:primordial_engine_core',
            E: 'thetornproductionline:celestial_secret_deducing_module_hv'
        }
    },
    {
        id: 'shanhai:pf_crafting/primordial_matter_recombinator_core',
        out: 'shanhai:primordial_matter_recombinator_core',
        pattern: ['ABC', 'DEF', 'GHI'],
        keys: {
            A: 'gtceu:lv_emitter',
            B: 'gtceu:lv_robot_arm',
            C: 'gtceu:lv_sensor',
            D: 'shanhai:wl_board_ulv',
            E: 'shanhai:primordial_engine_core',
            F: 'thetornproductionline:celestial_secret_deducing_module_ulv',
            G: 'shanhai:first_light',
            H: 'gtceu:maintenance_hatch',
            I: 'shanhai:photon_rainbow'
        }
    },
    {
        id: 'shanhai:pf_crafting/primordial_causal_weaving_matrix',
        out: 'shanhai:primordial_causal_weaving_matrix',
        pattern: ['ABA', 'CDC', 'EFE'],
        keys: {
            A: 'thetornproductionline:celestial_secret_deducing_module_lv',
            B: 'shanhai:electron',
            C: 'kubejs:time_twister_wireless',
            D: 'shanhai:primordial_engine_core',
            E: 'shanhai:wl_board_lv',
            F: 'shanhai:electron_neutrino'
        }
    },
    {
        id: 'shanhai:pf_crafting/primordial_engraving_module',
        out: 'shanhai:primordial_engraving_module',
        pattern: ['ABA', 'CDC', 'ABA'],
        keys: {
            A: 'gtceu:lv_laser_engraver',
            B: 'gtceu:glass_lens',
            C: 'shanhai:wl_board_lv',
            D: 'shanhai:primordial_engine_core'
        }
    },
    {
        id: 'shanhai:pf_crafting/primordial_deep_space_extraction_core',
        out: 'shanhai:primordial_deep_space_extraction_core',
        pattern: ['ABC', 'DED', 'CBA'],
        keys: {
            A: 'gtceu:mv_fluid_drilling_rig',
            B: 'thetornproductionline:celestial_secret_deducing_module_mv',
            C: 'gtceu:mv_gas_collector',
            D: 'shanhai:wl_board_mv',
            E: 'shanhai:primordial_engine_core'
        }
    },
    {
        id: 'shanhai:pf_crafting/primordial_gravitational_interference_array',
        out: 'shanhai:primordial_gravitational_interference_array',
        pattern: ['ABA', 'CDC', 'ABA'],
        keys: {
            A: 'gtceu:hv_field_generator',
            B: 'shanhai:wem_1',
            C: 'thetornproductionline:celestial_secret_deducing_module_hv',
            D: 'shanhai:primordial_engine_core'
        }
    },
    {
        id: 'shanhai:pf_crafting/primordial_carbon_deconstruction_core',
        out: 'shanhai:primordial_carbon_deconstruction_core',
        pattern: ['ABC', 'DEF', 'GBH'],
        keys: {
            A: 'gtceu:distillation_tower',
            B: 'gtceu:hv_field_generator',
            C: 'gtceu:cracker',
            D: 'thetornproductionline:celestial_secret_deducing_module_hv',
            E: 'shanhai:primordial_engine_core',
            F: 'shanhai:wl_board_hv',
            G: 'gtceu:pyrolyse_oven',
            H: 'gtceu:desulfurizer'
        }
    }
]

// -----------------------------------------------------------------------------
// 老 3 条 GT（①⑦⑧，与上一版【逐字相同】，本次未改动）+ 新增 92 条（条数现算）
// 每条上面第一行注释 = 溯源：PF.txt 里的序号 / 元件 / 纸
// -----------------------------------------------------------------------------
var shanhaiPfGt = [
    // ===== 老 ①（上一版逐字，未改）：处理样板ULV / 电路组装机 / 10s =====
    {
        id: 'shanhai:pf/primordial_omega_engine',
        type: 'circuit_assembler',
        // 老 ① 的 6 项 = circuit_assembler 的物品输入上限 setMaxIOSize(6,1,1,0)，正好用满
        notConsumable: [],
        circuit: 0,
        itemInputs: [
            '4x gtceu:dimensionally_transcendent_steam_oven',
            '4x gtceu:dimensionally_transcendent_dirt_forge',
            '1x shanhai:primordial_engine_core',
            '16x thetornproductionline:celestial_secret_deducing_module_ulv',
            '64x kubejs:precision_steam_mechanism',
            '16x gtceu:primitive_void_ore'
        ],
        inputFluids: ['gtceu:glue 16000'],
        itemOutputs: ['1x shanhai:primordial_omega_engine'],
        outputFluids: [],
        chancedOutputs: [],
        duration: 200,
        EUt: 8
    }
,
    // ===== 老 ⑦（上一版逐字；**仅**物质模块落法于 2026-10-03 按纸改为消耗）：处理样板LV / 光子虹吸 / 60s =====
    {
        id: 'shanhai:pf/photon',
        type: 'photon_siphon',
        // PF.txt 原文该格：programmed_circuit + tag:{Configuration:2}
        circuit: 2,
        // 🔴 用户 2026-09-28 原话：「光子虹吸的配方里面主世界碎片和物质模块都是不消耗的（作为催化剂）」
        //     ⇒ 主世界碎片【保留不消耗】（本条一律不动）；物质模块的落法见下面那行 🧪 自报
        notConsumable: ['1x gtlcore:world_fragments_overworld'],
        // 🧪 物质模块【消耗·纸上无催化剂纸】：纸上（无）【没有】「物质模块是催化剂」 ⇒ 按 2026-10-03 判据（只看纸）模块留在 itemInputs 里被正常消耗，不挂催化剂、不设门槛。｜⚠️ 本条是老配方（正文由 emitOldGt 硬编码）；2026-10-03 追加：它的模块落法改为按源样板（PF 第 36 条）的纸**现算**，不再写死。
        itemInputs: ['1x shanhai:basic_material_module'],
        inputFluids: [],
        itemOutputs: ['16x shanhai:photon'],
        outputFluids: ['shanhai:zero_point_energy 32000', 'shanhai:light 16000'],
        chancedOutputs: [],
        duration: 1200,
        EUt: 32
    }
,
    // ===== 老 ⑧（上一版逐字，未改）：处理样板LV / 光子虹吸 / 60s =====
    {
        id: 'shanhai:pf/first_light',
        type: 'photon_siphon',
        // PF.txt 原文该格：programmed_circuit + tag:{Configuration:1}
        circuit: 1,
        notConsumable: ['1x gtlcore:world_fragments_overworld'],
        itemInputs: [],
        inputFluids: [],
        itemOutputs: ['32x shanhai:first_light', '4x shanhai:photon'],
        outputFluids: ['shanhai:light 4000'],
        chancedOutputs: [],
        duration: 1200,
        EUt: 32
    }
,
    // ▶ PF.txt 第 1 条（本次新增）｜元件「处理样板ULV」｜纸：类型「原初奇点反演」／耗时「3s」｜输出 8x thetornproductionline:celestial_secret_deducing_module_ulv
    // 🧪 物质模块【不消耗·等级门槛·纸上有催化剂纸】：纸上（第 6 格）放着「物质模块是催化剂」，且类型 primordial_singularity_inversion ∈ SHANHAI_TYPES（= 山海自己的机器） ⇒ 落 ModuleLevelCondition（要求模块等级 ≥ 1，等级取自 MODULE_LEVELS）。⚠️ 门槛【不占输入槽】。若本条应当"只是纯催化剂（.notConsumable）"，改 SHANHAI_TYPES 即可，一处生效。
    {
        id: 'shanhai:pf/celestial_secret_deducing_module_ulv',
        type: 'primordial_singularity_inversion',
        circuit: 31,
        notConsumable: [],
        moduleLevelRequirement: '1x shanhai:introductory_material_module',
        moduleLevelFallbackCatalyst: '1x shanhai:introductory_material_module',
        itemInputs: ['1x kubejs:ulv_universal_circuit', '1x shanhai:electron_neutrino'],
        inputFluids: [],
        itemOutputs: ['8x thetornproductionline:celestial_secret_deducing_module_ulv'],
        outputFluids: [],
        chancedOutputs: [],
        duration: 60,
        EUt: 8
    }
,
    // ▶ PF.txt 第 2 条（本次新增）｜元件「处理样板ULV」｜纸：类型「土高炉」／耗时「3s」｜输出 4x gtceu:bronze_ingot
    // 🧪 物质模块【不消耗·真催化剂·纸上有催化剂纸】：纸上（第 5 格）放着「物质模块是催化剂」，但类型 primitive_blast_furnace 不在 SHANHAI_TYPES 里（= 非山海的机器） ⇒ 落 .notConsumable(...) 真催化剂，不设等级门槛。
    // 🔴 该配方类型 `gtceu:primitive_blast_furnace` 不在 SHANHAI_TYPES 里（= 非山海的机器） ⇒ 按 2026-10-03 判据（纸上有「物质模块是催化剂」⇒ 不消耗、形态保持现状）落 `.notConsumable(...)` 真催化剂（不设等级门槛）。
    // 🔴 🔴 土高炉配方：用户 2026-09-27 裁决「应该是没有电力要求的，就和原版的炼钢一样」
    //    ⇒ EUt 由元件默认的 8 改为 **0**（原版 primitive_blast_furnace 的 JSON 里根本没有 EUt 字段）。
    {
        id: 'shanhai:pf/bronze_ingot',
        type: 'primitive_blast_furnace',
        circuit: 0,
        notConsumable: ['1x shanhai:introductory_material_module'],
        itemInputs: ['1x minecraft:copper_ingot', '1x gtceu:tin_ingot'],
        inputFluids: [],
        itemOutputs: ['4x gtceu:bronze_ingot'],
        outputFluids: [],
        chancedOutputs: [],
        duration: 60,
        EUt: 0
    }
,
    // ▶ PF.txt 第 3 条（本次新增）｜元件「处理样板ULV」｜纸：类型「物质模块铸造」／耗时「60s」｜输出 1x shanhai:introductory_material_module
    {
        id: 'shanhai:pf/introductory_material_module',
        type: 'matter_module_casting',
        circuit: 0,
        notConsumable: [],
        itemInputs: ['4x shanhai:first_light', '1x shanhai:photon_rainbow', '1x shanhai:electron', '2x shanhai:wl_board_ulv'],
        inputFluids: ['shanhai:matter_fluid_entry 1000'],
        itemOutputs: ['1x shanhai:introductory_material_module'],
        outputFluids: [],
        chancedOutputs: [],
        duration: 1200,
        EUt: 8
    }
,
    // ▶ PF.txt 第 4 条（本次新增）｜元件「处理样板ULV」｜纸：类型「原初因果编织」／耗时「3s」｜输出 4x gtceu:sticky_resin
    // 🧪 物质模块【不消耗·等级门槛·纸上有催化剂纸】：纸上（第 5 格）放着「物质模块是催化剂」，且类型 primordial_causal_weaving ∈ SHANHAI_TYPES（= 山海自己的机器） ⇒ 落 ModuleLevelCondition（要求模块等级 ≥ 1，等级取自 MODULE_LEVELS）。⚠️ 门槛【不占输入槽】。若本条应当"只是纯催化剂（.notConsumable）"，改 SHANHAI_TYPES 即可，一处生效。
    {
        id: 'shanhai:pf/sticky_resin',
        type: 'primordial_causal_weaving',
        circuit: 25,
        notConsumable: [],
        moduleLevelRequirement: '1x shanhai:introductory_material_module',
        moduleLevelFallbackCatalyst: '1x shanhai:introductory_material_module',
        itemInputs: ['1x gtceu:rubber_sapling'],
        inputFluids: [],
        itemOutputs: ['4x gtceu:sticky_resin'],
        outputFluids: [],
        chancedOutputs: [],
        duration: 60,
        EUt: 8
    }
,
    // ▶ PF.txt 第 5 条（本次新增）｜元件「处理样板ULV」｜纸：类型「土高炉」／耗时「3s」｜输出 3x gtceu:invar_ingot
    // 🧪 物质模块【不消耗·真催化剂·纸上有催化剂纸】：纸上（第 8 格）放着「物质模块是催化剂」，但类型 primitive_blast_furnace 不在 SHANHAI_TYPES 里（= 非山海的机器） ⇒ 落 .notConsumable(...) 真催化剂，不设等级门槛。
    // 🔴 该配方类型 `gtceu:primitive_blast_furnace` 不在 SHANHAI_TYPES 里（= 非山海的机器） ⇒ 按 2026-10-03 判据（纸上有「物质模块是催化剂」⇒ 不消耗、形态保持现状）落 `.notConsumable(...)` 真催化剂（不设等级门槛）。
    // 🔴 🔴 土高炉配方：用户 2026-09-27 裁决「应该是没有电力要求的，就和原版的炼钢一样」
    //    ⇒ EUt 由元件默认的 8 改为 **0**（原版 primitive_blast_furnace 的 JSON 里根本没有 EUt 字段）。
    {
        id: 'shanhai:pf/invar_ingot',
        type: 'primitive_blast_furnace',
        circuit: 0,
        notConsumable: ['1x shanhai:introductory_material_module'],
        itemInputs: ['1x minecraft:iron_ingot', '1x gtceu:nickel_ingot'],
        inputFluids: [],
        itemOutputs: ['3x gtceu:invar_ingot'],
        outputFluids: [],
        chancedOutputs: [],
        duration: 60,
        EUt: 0
    }
,
    // ▶ PF.txt 第 6 条（本次新增）｜元件「处理样板ULV」｜纸：类型「土高炉」／耗时「3s」｜输出 2x gtceu:steel_ingot
    // 🧪 物质模块【不消耗·真催化剂·纸上有催化剂纸】：纸上（第 5 格）放着「物质模块是催化剂」，但类型 primitive_blast_furnace 不在 SHANHAI_TYPES 里（= 非山海的机器） ⇒ 落 .notConsumable(...) 真催化剂，不设等级门槛。
    // 🔴 该配方类型 `gtceu:primitive_blast_furnace` 不在 SHANHAI_TYPES 里（= 非山海的机器） ⇒ 按 2026-10-03 判据（纸上有「物质模块是催化剂」⇒ 不消耗、形态保持现状）落 `.notConsumable(...)` 真催化剂（不设等级门槛）。
    // 🔴 🔴 土高炉配方：用户 2026-09-27 裁决「应该是没有电力要求的，就和原版的炼钢一样」
    //    ⇒ EUt 由元件默认的 8 改为 **0**（原版 primitive_blast_furnace 的 JSON 里根本没有 EUt 字段）。
    {
        id: 'shanhai:pf/steel_ingot',
        type: 'primitive_blast_furnace',
        circuit: 0,
        notConsumable: ['1x shanhai:introductory_material_module'],
        itemInputs: ['1x minecraft:iron_ingot', '1x minecraft:coal'],
        inputFluids: [],
        itemOutputs: ['2x gtceu:steel_ingot'],
        outputFluids: [],
        chancedOutputs: [],
        duration: 60,
        EUt: 0
    }
,
    // ▶ PF.txt 第 7 条（本次新增）｜元件「处理样板ULV」｜纸：类型「物质流凝结」／耗时「15s」｜输出 
    {
        id: 'shanhai:pf/matter_flow_condensation_7',
        type: 'matter_flow_condensation',
        circuit: 0,
        notConsumable: [],
        itemInputs: ['1x gtceu:bronze_ingot', '1x gtceu:steel_ingot', '1x gtceu:red_alloy_ingot'],
        inputFluids: ['gtceu:steam 1000', 'gtceu:glue 10'],
        itemOutputs: [],
        outputFluids: ['shanhai:matter_fluid_entry 1000'],
        chancedOutputs: [],
        duration: 300,
        EUt: 8
    }
,
    // ▶ PF.txt 第 8 条（本次新增）｜元件「处理样板ULV」｜纸：类型「世线电路板组装」／耗时「3s」｜输出 8x shanhai:wl_board_ulv
    // 🧪 物质模块【不消耗·等级门槛·纸上有催化剂纸】：纸上（第 5 格）放着「物质模块是催化剂」，且类型 wl_board_circuit_assembly ∈ SHANHAI_TYPES（= 山海自己的机器） ⇒ 落 ModuleLevelCondition（要求模块等级 ≥ 1，等级取自 MODULE_LEVELS）。⚠️ 门槛【不占输入槽】。若本条应当"只是纯催化剂（.notConsumable）"，改 SHANHAI_TYPES 即可，一处生效。
    {
        id: 'shanhai:pf/wl_board_ulv',
        type: 'wl_board_circuit_assembly',
        circuit: 0,
        notConsumable: [],
        moduleLevelRequirement: '1x shanhai:introductory_material_module',
        moduleLevelFallbackCatalyst: '1x shanhai:introductory_material_module',
        itemInputs: ['1x kubejs:ulv_universal_circuit', '1x shanhai:photon_rainbow'],
        inputFluids: ['shanhai:matter_fluid_entry 1000'],
        itemOutputs: ['8x shanhai:wl_board_ulv'],
        outputFluids: [],
        chancedOutputs: [],
        duration: 60,
        EUt: 8
    }
,
    // ▶ PF.txt 第 9 条（本次新增）｜元件「处理样板ULV」｜纸：类型「土高炉」／耗时「3s」｜输出 2x gtceu:red_alloy_ingot
    // 🧪 物质模块【不消耗·真催化剂·纸上有催化剂纸】：纸上（第 8 格）放着「物质模块是催化剂」，但类型 primitive_blast_furnace 不在 SHANHAI_TYPES 里（= 非山海的机器） ⇒ 落 .notConsumable(...) 真催化剂，不设等级门槛。
    // 🔴 该配方类型 `gtceu:primitive_blast_furnace` 不在 SHANHAI_TYPES 里（= 非山海的机器） ⇒ 按 2026-10-03 判据（纸上有「物质模块是催化剂」⇒ 不消耗、形态保持现状）落 `.notConsumable(...)` 真催化剂（不设等级门槛）。
    // 🔴 🔴 土高炉配方：用户 2026-09-27 裁决「应该是没有电力要求的，就和原版的炼钢一样」
    //    ⇒ EUt 由元件默认的 8 改为 **0**（原版 primitive_blast_furnace 的 JSON 里根本没有 EUt 字段）。
    {
        id: 'shanhai:pf/red_alloy_ingot',
        type: 'primitive_blast_furnace',
        circuit: 0,
        notConsumable: ['1x shanhai:introductory_material_module'],
        itemInputs: ['1x minecraft:copper_ingot', '2x minecraft:redstone'],
        inputFluids: [],
        itemOutputs: ['2x gtceu:red_alloy_ingot'],
        outputFluids: [],
        chancedOutputs: [],
        duration: 60,
        EUt: 0
    }
,
    // ▶ PF.txt 第 10 条（本次新增）｜元件「处理样板ULV」｜纸：类型「土高炉」／耗时「3s」｜输出 1x gtceu:annealed_copper_ingot
    // 🧪 物质模块【不消耗·真催化剂·纸上有催化剂纸】：纸上（第 5 格）放着「物质模块是催化剂」，但类型 primitive_blast_furnace 不在 SHANHAI_TYPES 里（= 非山海的机器） ⇒ 落 .notConsumable(...) 真催化剂，不设等级门槛。
    // 🔴 该配方类型 `gtceu:primitive_blast_furnace` 不在 SHANHAI_TYPES 里（= 非山海的机器） ⇒ 按 2026-10-03 判据（纸上有「物质模块是催化剂」⇒ 不消耗、形态保持现状）落 `.notConsumable(...)` 真催化剂（不设等级门槛）。
    // 🔴 🔴 土高炉配方：用户 2026-09-27 裁决「应该是没有电力要求的，就和原版的炼钢一样」
    //    ⇒ EUt 由元件默认的 8 改为 **0**（原版 primitive_blast_furnace 的 JSON 里根本没有 EUt 字段）。
    {
        id: 'shanhai:pf/annealed_copper_ingot',
        type: 'primitive_blast_furnace',
        circuit: 0,
        notConsumable: ['1x shanhai:introductory_material_module'],
        itemInputs: ['1x minecraft:copper_ingot'],
        inputFluids: [],
        itemOutputs: ['1x gtceu:annealed_copper_ingot'],
        outputFluids: [],
        chancedOutputs: [],
        duration: 60,
        EUt: 0
    }
,
    // ▶ PF.txt 第 11 条（本次新增）｜元件「处理样板ULV」｜纸：类型「土高炉」／耗时「3s」｜输出 4x gtceu:tin_alloy_ingot
    // 🧪 物质模块【不消耗·真催化剂·纸上有催化剂纸】：纸上（第 8 格）放着「物质模块是催化剂」，但类型 primitive_blast_furnace 不在 SHANHAI_TYPES 里（= 非山海的机器） ⇒ 落 .notConsumable(...) 真催化剂，不设等级门槛。
    // 🔴 该配方类型 `gtceu:primitive_blast_furnace` 不在 SHANHAI_TYPES 里（= 非山海的机器） ⇒ 按 2026-10-03 判据（纸上有「物质模块是催化剂」⇒ 不消耗、形态保持现状）落 `.notConsumable(...)` 真催化剂（不设等级门槛）。
    // 🔴 🔴 土高炉配方：用户 2026-09-27 裁决「应该是没有电力要求的，就和原版的炼钢一样」
    //    ⇒ EUt 由元件默认的 8 改为 **0**（原版 primitive_blast_furnace 的 JSON 里根本没有 EUt 字段）。
    {
        id: 'shanhai:pf/tin_alloy_ingot',
        type: 'primitive_blast_furnace',
        circuit: 0,
        notConsumable: ['1x shanhai:introductory_material_module'],
        itemInputs: ['1x minecraft:iron_ingot', '1x gtceu:tin_ingot'],
        inputFluids: [],
        itemOutputs: ['4x gtceu:tin_alloy_ingot'],
        outputFluids: [],
        chancedOutputs: [],
        duration: 60,
        EUt: 0
    }
,
    // ▶ PF.txt 第 12 条（本次新增）｜元件「处理样板ULV」｜纸：类型「土高炉」／耗时「3s」｜输出 1x gtceu:pulsating_alloy_ingot
    // 🧪 物质模块【不消耗·真催化剂·纸上有催化剂纸】：纸上（第 8 格）放着「物质模块是催化剂」，但类型 primitive_blast_furnace 不在 SHANHAI_TYPES 里（= 非山海的机器） ⇒ 落 .notConsumable(...) 真催化剂，不设等级门槛。
    // 🔴 该配方类型 `gtceu:primitive_blast_furnace` 不在 SHANHAI_TYPES 里（= 非山海的机器） ⇒ 按 2026-10-03 判据（纸上有「物质模块是催化剂」⇒ 不消耗、形态保持现状）落 `.notConsumable(...)` 真催化剂（不设等级门槛）。
    // 🔴 🔴 土高炉配方：用户 2026-09-27 裁决「应该是没有电力要求的，就和原版的炼钢一样」
    //    ⇒ EUt 由元件默认的 8 改为 **0**（原版 primitive_blast_furnace 的 JSON 里根本没有 EUt 字段）。
    {
        id: 'shanhai:pf/pulsating_alloy_ingot',
        type: 'primitive_blast_furnace',
        circuit: 0,
        notConsumable: ['1x shanhai:introductory_material_module'],
        itemInputs: ['1x minecraft:iron_ingot', '1x minecraft:gunpowder'],
        inputFluids: [],
        itemOutputs: ['1x gtceu:pulsating_alloy_ingot'],
        outputFluids: [],
        chancedOutputs: [],
        duration: 60,
        EUt: 0
    }
,
    // ▶ PF.txt 第 13 条（本次新增）｜元件「处理样板ULV」｜纸：类型「土高炉」／耗时「3s」｜输出 2x gtceu:cupronickel_ingot
    // 🧪 物质模块【不消耗·真催化剂·纸上有催化剂纸】：纸上（第 8 格）放着「物质模块是催化剂」，但类型 primitive_blast_furnace 不在 SHANHAI_TYPES 里（= 非山海的机器） ⇒ 落 .notConsumable(...) 真催化剂，不设等级门槛。
    // 🔴 该配方类型 `gtceu:primitive_blast_furnace` 不在 SHANHAI_TYPES 里（= 非山海的机器） ⇒ 按 2026-10-03 判据（纸上有「物质模块是催化剂」⇒ 不消耗、形态保持现状）落 `.notConsumable(...)` 真催化剂（不设等级门槛）。
    // 🔴 🔴 土高炉配方：用户 2026-09-27 裁决「应该是没有电力要求的，就和原版的炼钢一样」
    //    ⇒ EUt 由元件默认的 8 改为 **0**（原版 primitive_blast_furnace 的 JSON 里根本没有 EUt 字段）。
    {
        id: 'shanhai:pf/cupronickel_ingot',
        type: 'primitive_blast_furnace',
        circuit: 0,
        notConsumable: ['1x shanhai:introductory_material_module'],
        itemInputs: ['1x minecraft:copper_ingot', '1x gtceu:nickel_ingot'],
        inputFluids: [],
        itemOutputs: ['2x gtceu:cupronickel_ingot'],
        outputFluids: [],
        chancedOutputs: [],
        duration: 60,
        EUt: 0
    }
,
    // ▶ PF.txt 第 14 条（本次新增）｜元件「处理样板ULV」｜纸：类型「土高炉」／耗时「3s」｜输出 1x gtceu:glass_tube
    // 🧪 物质模块【不消耗·真催化剂·纸上有催化剂纸】：纸上（第 8 格）放着「物质模块是催化剂」，但类型 primitive_blast_furnace 不在 SHANHAI_TYPES 里（= 非山海的机器） ⇒ 落 .notConsumable(...) 真催化剂，不设等级门槛。
    // 🔴 该配方类型 `gtceu:primitive_blast_furnace` 不在 SHANHAI_TYPES 里（= 非山海的机器） ⇒ 按 2026-10-03 判据（纸上有「物质模块是催化剂」⇒ 不消耗、形态保持现状）落 `.notConsumable(...)` 真催化剂（不设等级门槛）。
    // 🔴 🔴 土高炉配方：用户 2026-09-27 裁决「应该是没有电力要求的，就和原版的炼钢一样」
    //    ⇒ EUt 由元件默认的 8 改为 **0**（原版 primitive_blast_furnace 的 JSON 里根本没有 EUt 字段）。
    {
        id: 'shanhai:pf/glass_tube',
        type: 'primitive_blast_furnace',
        circuit: 0,
        notConsumable: ['1x shanhai:introductory_material_module'],
        itemInputs: ['1x minecraft:glass'],
        inputFluids: [],
        itemOutputs: ['1x gtceu:glass_tube'],
        outputFluids: [],
        chancedOutputs: [],
        duration: 60,
        EUt: 0
    }
,
    // ▶ PF.txt 第 15 条（本次新增）｜元件「处理样板ULV」｜纸：类型「土高炉」／耗时「3s」｜输出 2x minecraft:paper
    // 🧪 物质模块【不消耗·真催化剂·纸上有催化剂纸】：纸上（第 8 格）放着「物质模块是催化剂」，但类型 primitive_blast_furnace 不在 SHANHAI_TYPES 里（= 非山海的机器） ⇒ 落 .notConsumable(...) 真催化剂，不设等级门槛。
    // 🔴 该配方类型 `gtceu:primitive_blast_furnace` 不在 SHANHAI_TYPES 里（= 非山海的机器） ⇒ 按 2026-10-03 判据（纸上有「物质模块是催化剂」⇒ 不消耗、形态保持现状）落 `.notConsumable(...)` 真催化剂（不设等级门槛）。
    // 🔴 🔴 土高炉配方：用户 2026-09-27 裁决「应该是没有电力要求的，就和原版的炼钢一样」
    //    ⇒ EUt 由元件默认的 8 改为 **0**（原版 primitive_blast_furnace 的 JSON 里根本没有 EUt 字段）。
    {
        id: 'shanhai:pf/paper',
        type: 'primitive_blast_furnace',
        circuit: 0,
        notConsumable: ['1x shanhai:introductory_material_module'],
        itemInputs: ['1x minecraft:sugar_cane'],
        inputFluids: [],
        itemOutputs: ['2x minecraft:paper'],
        outputFluids: [],
        chancedOutputs: [],
        duration: 60,
        EUt: 0
    }
,
    // ▶ PF.txt 第 16 条（本次新增）｜元件「处理样板ULV」｜纸：类型「土高炉」／耗时「3s」｜输出 4x gtceu:brass_ingot
    // 🧪 物质模块【不消耗·真催化剂·纸上有催化剂纸】：纸上（第 8 格）放着「物质模块是催化剂」，但类型 primitive_blast_furnace 不在 SHANHAI_TYPES 里（= 非山海的机器） ⇒ 落 .notConsumable(...) 真催化剂，不设等级门槛。
    // 🔴 该配方类型 `gtceu:primitive_blast_furnace` 不在 SHANHAI_TYPES 里（= 非山海的机器） ⇒ 按 2026-10-03 判据（纸上有「物质模块是催化剂」⇒ 不消耗、形态保持现状）落 `.notConsumable(...)` 真催化剂（不设等级门槛）。
    // 🔴 🔴 土高炉配方：用户 2026-09-27 裁决「应该是没有电力要求的，就和原版的炼钢一样」
    //    ⇒ EUt 由元件默认的 8 改为 **0**（原版 primitive_blast_furnace 的 JSON 里根本没有 EUt 字段）。
    {
        id: 'shanhai:pf/brass_ingot',
        type: 'primitive_blast_furnace',
        circuit: 0,
        notConsumable: ['1x shanhai:introductory_material_module'],
        itemInputs: ['1x gtceu:zinc_ingot', '1x minecraft:copper_ingot'],
        inputFluids: [],
        itemOutputs: ['4x gtceu:brass_ingot'],
        outputFluids: [],
        chancedOutputs: [],
        duration: 60,
        EUt: 0
    }
,
    // ▶ PF.txt 第 17 条（本次新增）｜元件「处理样板ULV」｜纸：类型「土高炉」／耗时「3s」｜输出 1x gtceu:conductive_alloy_ingot
    // 🧪 物质模块【不消耗·真催化剂·纸上有催化剂纸】：纸上（第 8 格）放着「物质模块是催化剂」，但类型 primitive_blast_furnace 不在 SHANHAI_TYPES 里（= 非山海的机器） ⇒ 落 .notConsumable(...) 真催化剂，不设等级门槛。
    // 🔴 该配方类型 `gtceu:primitive_blast_furnace` 不在 SHANHAI_TYPES 里（= 非山海的机器） ⇒ 按 2026-10-03 判据（纸上有「物质模块是催化剂」⇒ 不消耗、形态保持现状）落 `.notConsumable(...)` 真催化剂（不设等级门槛）。
    // 🔴 🔴 土高炉配方：用户 2026-09-27 裁决「应该是没有电力要求的，就和原版的炼钢一样」
    //    ⇒ EUt 由元件默认的 8 改为 **0**（原版 primitive_blast_furnace 的 JSON 里根本没有 EUt 字段）。
    {
        id: 'shanhai:pf/conductive_alloy_ingot',
        type: 'primitive_blast_furnace',
        circuit: 0,
        notConsumable: ['1x shanhai:introductory_material_module'],
        itemInputs: ['1x gtceu:pulsating_alloy_ingot', '1x minecraft:redstone'],
        inputFluids: [],
        itemOutputs: ['1x gtceu:conductive_alloy_ingot'],
        outputFluids: [],
        chancedOutputs: [],
        duration: 60,
        EUt: 0
    }
,
    // ▶ PF.txt 第 18 条（本次新增）｜元件「处理样板ULV」｜纸：类型「原初物质重组」／耗时「3s」｜输出 3x minecraft:fire_charge
    // 🧪 物质模块【不消耗·等级门槛·纸上有催化剂纸】：纸上（第 8 格）放着「物质模块是催化剂」，且类型 primordial_matter_recombination ∈ SHANHAI_TYPES（= 山海自己的机器） ⇒ 落 ModuleLevelCondition（要求模块等级 ≥ 1，等级取自 MODULE_LEVELS）。⚠️ 门槛【不占输入槽】。若本条应当"只是纯催化剂（.notConsumable）"，改 SHANHAI_TYPES 即可，一处生效。
    {
        id: 'shanhai:pf/fire_charge',
        type: 'primordial_matter_recombination',
        circuit: 13,
        notConsumable: [],
        moduleLevelRequirement: '1x shanhai:introductory_material_module',
        moduleLevelFallbackCatalyst: '1x shanhai:introductory_material_module',
        itemInputs: ['1x minecraft:gunpowder', '1x gtceu:carbon_dust', '1x minecraft:blaze_powder'],
        inputFluids: [],
        itemOutputs: ['3x minecraft:fire_charge'],
        outputFluids: [],
        chancedOutputs: [],
        duration: 60,
        EUt: 8
    }
,
    // ▶ PF.txt 第 19 条（本次新增）｜元件「处理样板ULV」｜纸：类型「土高炉」／耗时「3s」｜输出 2x gtceu:wrought_iron_ingot
    // 🧪 物质模块【不消耗·真催化剂·纸上有催化剂纸】：纸上（第 8 格）放着「物质模块是催化剂」，但类型 primitive_blast_furnace 不在 SHANHAI_TYPES 里（= 非山海的机器） ⇒ 落 .notConsumable(...) 真催化剂，不设等级门槛。
    // 🔴 该配方类型 `gtceu:primitive_blast_furnace` 不在 SHANHAI_TYPES 里（= 非山海的机器） ⇒ 按 2026-10-03 判据（纸上有「物质模块是催化剂」⇒ 不消耗、形态保持现状）落 `.notConsumable(...)` 真催化剂（不设等级门槛）。
    // 🔴 🔴 土高炉配方：用户 2026-09-27 裁决「应该是没有电力要求的，就和原版的炼钢一样」
    //    ⇒ EUt 由元件默认的 8 改为 **0**（原版 primitive_blast_furnace 的 JSON 里根本没有 EUt 字段）。
    {
        id: 'shanhai:pf/wrought_iron_ingot',
        type: 'primitive_blast_furnace',
        circuit: 1,
        notConsumable: ['1x shanhai:introductory_material_module'],
        itemInputs: ['1x minecraft:iron_ingot'],
        inputFluids: [],
        itemOutputs: ['2x gtceu:wrought_iron_ingot'],
        outputFluids: [],
        chancedOutputs: [],
        duration: 60,
        EUt: 0
    }
,
    // ▶ PF.txt 第 20 条（本次新增）｜元件「处理样板ULV」｜纸：类型「土高炉」／耗时「3s」｜输出 1x gtceu:compressed_fireclay
    // 🧪 物质模块【不消耗·真催化剂·纸上有催化剂纸】：纸上（第 8 格）放着「物质模块是催化剂」，但类型 primitive_blast_furnace 不在 SHANHAI_TYPES 里（= 非山海的机器） ⇒ 落 .notConsumable(...) 真催化剂，不设等级门槛。
    // 🔴 该配方类型 `gtceu:primitive_blast_furnace` 不在 SHANHAI_TYPES 里（= 非山海的机器） ⇒ 按 2026-10-03 判据（纸上有「物质模块是催化剂」⇒ 不消耗、形态保持现状）落 `.notConsumable(...)` 真催化剂（不设等级门槛）。
    // 🔴 🔴 土高炉配方：用户 2026-09-27 裁决「应该是没有电力要求的，就和原版的炼钢一样」
    //    ⇒ EUt 由元件默认的 8 改为 **0**（原版 primitive_blast_furnace 的 JSON 里根本没有 EUt 字段）。
    {
        id: 'shanhai:pf/compressed_fireclay',
        type: 'primitive_blast_furnace',
        circuit: 0,
        notConsumable: ['1x shanhai:introductory_material_module'],
        itemInputs: ['1x minecraft:clay_ball', '1x minecraft:brick'],
        inputFluids: [],
        itemOutputs: ['1x gtceu:compressed_fireclay'],
        outputFluids: [],
        chancedOutputs: [],
        duration: 60,
        EUt: 0
    }
,
    // ▶ PF.txt 第 22 条（本次新增）｜元件「处理样板LV」｜纸：类型「原初奇点反演」／耗时「3s」｜输出 1x thetornproductionline:celestial_secret_deducing_module_lv
    // 🧪 物质模块【不消耗·等级门槛·纸上有催化剂纸】：纸上（第 6 格）放着「物质模块是催化剂」，且类型 primordial_singularity_inversion ∈ SHANHAI_TYPES（= 山海自己的机器） ⇒ 落 ModuleLevelCondition（要求模块等级 ≥ 1，等级取自 MODULE_LEVELS）。⚠️ 门槛【不占输入槽】。若本条应当"只是纯催化剂（.notConsumable）"，改 SHANHAI_TYPES 即可，一处生效。
    {
        id: 'shanhai:pf/celestial_secret_deducing_module_lv',
        type: 'primordial_singularity_inversion',
        circuit: 31,
        notConsumable: [],
        moduleLevelRequirement: '1x shanhai:introductory_material_module',
        moduleLevelFallbackCatalyst: '1x shanhai:introductory_material_module',
        itemInputs: ['1x kubejs:lv_universal_circuit', '1x shanhai:electron_neutrino', '4x gtceu:double_steel_plate'],
        inputFluids: [],
        itemOutputs: ['1x thetornproductionline:celestial_secret_deducing_module_lv'],
        outputFluids: [],
        chancedOutputs: [],
        duration: 60,
        EUt: 32
    }
,
    // ▶ PF.txt 第 23 条（本次新增）｜元件「处理样板LV」｜纸：类型「物质模块铸造」／耗时「60s」｜输出 1x shanhai:basic_material_module
    // 🧪 物质模块【消耗·纸上无催化剂纸】：纸上（无）【没有】「物质模块是催化剂」；另：本条【产出】里有物质模块（= 属于"制作物质模块"），与"只看纸"的结论【一致】，不冲突 ⇒ 按 2026-10-03 判据（只看纸）模块留在 itemInputs 里被正常消耗，不挂催化剂、不设门槛。
    {
        id: 'shanhai:pf/basic_material_module',
        type: 'matter_module_casting',
        circuit: 0,
        notConsumable: [],
        itemInputs: ['2x shanhai:wl_board_lv', '2x thetornproductionline:celestial_secret_deducing_module_lv', '1x shanhai:introductory_material_module', '16x shanhai:electron', '4x shanhai:photon_rainbow', '128x shanhai:first_light', '1x shanhai:electron_neutrino'],
        inputFluids: ['shanhai:matter_fluid_foundation 2000', 'shanhai:matter_fluid_entry 4000'],
        itemOutputs: ['1x shanhai:basic_material_module'],
        outputFluids: [],
        chancedOutputs: [],
        duration: 1200,
        EUt: 32
    }
,
    // ▶ PF.txt 第 24 条（本次新增）｜元件「处理样板LV」｜纸：类型「光子分离」／耗时「3s」｜输出 1x shanhai:electron
    // 🧪 物质模块【不消耗·等级门槛·纸上有催化剂纸】：纸上（第 5 格）放着「物质模块是催化剂」，且类型 photon_separation ∈ SHANHAI_TYPES（= 山海自己的机器） ⇒ 落 ModuleLevelCondition（要求模块等级 ≥ 1，等级取自 MODULE_LEVELS）。⚠️ 门槛【不占输入槽】。若本条应当"只是纯催化剂（.notConsumable）"，改 SHANHAI_TYPES 即可，一处生效。
    // 🔴 纸「电子中微子产出概率5%」⇒ shanhai:electron_neutrino 改成 chancedOutput(500, 100)。⚠️ 单位：本包 chance 是【万分比】，10000=100% ⇒ 5% = 500（不是 5000）。⚠️ 第二个 int 不是"加成上限"，是【每超频一级的加成量 tierChanceBoost】（字节码实证：GTRecipeBuilder.chancedOutput 把 iload_3 写进字段 tierChanceBoost）。100 = GTCEu 自己"5% 档副产"的标准值（本包 414 条实际配方 chance=500/boost=100）。用户 2026-09-26 裁决：吃加成 ⇒ 第二参不能是 0，本文件取 100。
    {
        id: 'shanhai:pf/electron',
        type: 'photon_separation',
        circuit: 1,
        notConsumable: ['1x gtceu:lv_field_generator'],
        moduleLevelRequirement: '1x shanhai:introductory_material_module',
        moduleLevelFallbackCatalyst: '1x shanhai:introductory_material_module',
        itemInputs: ['1x shanhai:photon_rainbow'],
        inputFluids: [],
        itemOutputs: ['1x shanhai:electron'],
        outputFluids: ['shanhai:zero_point_energy 1000'],
        chancedOutputs: [{ item: '1x shanhai:electron_neutrino', chance: 500, tierChanceBoost: 100 }],
        duration: 60,
        EUt: 32
    }
,
    // ▶ PF.txt 第 25 条（本次新增）｜元件「处理样板LV」｜纸：类型「原初奇点反演」／耗时「3s」｜输出 64x kubejs:ulv_universal_circuit
    // 🧪 物质模块【不消耗·等级门槛·纸上有催化剂纸】：纸上（第 6 格）放着「物质模块是催化剂」，且类型 primordial_singularity_inversion ∈ SHANHAI_TYPES（= 山海自己的机器） ⇒ 落 ModuleLevelCondition（要求模块等级 ≥ 1，等级取自 MODULE_LEVELS）。⚠️ 门槛【不占输入槽】。若本条应当"只是纯催化剂（.notConsumable）"，改 SHANHAI_TYPES 即可，一处生效。
    {
        id: 'shanhai:pf/ulv_universal_circuit',
        type: 'primordial_singularity_inversion',
        circuit: 32,
        notConsumable: [],
        moduleLevelRequirement: '1x shanhai:introductory_material_module',
        moduleLevelFallbackCatalyst: '1x shanhai:introductory_material_module',
        itemInputs: ['1x thetornproductionline:celestial_secret_deducing_module_lv', '1x shanhai:wl_board_lv'],
        inputFluids: [],
        itemOutputs: ['64x kubejs:ulv_universal_circuit'],
        outputFluids: [],
        chancedOutputs: [],
        duration: 60,
        EUt: 32
    }
,
    // ▶ PF.txt 第 26 条（本次新增）｜元件「处理样板LV」｜纸：类型「原初物质重组」／耗时「60s」｜输出 1024x minecraft:obsidian
    // 🧪 物质模块【不消耗·等级门槛·纸上有催化剂纸】：纸上（第 5 格）放着「物质模块是催化剂」，且类型 primordial_matter_recombination ∈ SHANHAI_TYPES（= 山海自己的机器） ⇒ 落 ModuleLevelCondition（要求模块等级 ≥ 2，等级取自 MODULE_LEVELS）。⚠️ 门槛【不占输入槽】。若本条应当"只是纯催化剂（.notConsumable）"，改 SHANHAI_TYPES 即可，一处生效。
    {
        id: 'shanhai:pf/obsidian',
        type: 'primordial_matter_recombination',
        circuit: 10,
        notConsumable: [],
        moduleLevelRequirement: '1x shanhai:basic_material_module',
        moduleLevelFallbackCatalyst: '1x shanhai:basic_material_module',
        itemInputs: [],
        inputFluids: ['minecraft:water 2147483647', 'minecraft:lava 1024000'],
        itemOutputs: ['1024x minecraft:obsidian'],
        outputFluids: ['gtceu:steam 2147483647'],
        chancedOutputs: [],
        duration: 1200,
        EUt: 32
    }
,
    // ▶ PF.txt 第 28 条（本次新增）｜元件「处理样板LV」｜纸：类型「原初物质重组」／耗时「10s」｜输出 
    // 🧪 物质模块【不消耗·等级门槛·纸上有催化剂纸】：纸上（第 1 格）放着「物质模块是催化剂」，且类型 primordial_matter_recombination ∈ SHANHAI_TYPES（= 山海自己的机器） ⇒ 落 ModuleLevelCondition（要求模块等级 ≥ 2，等级取自 MODULE_LEVELS）。⚠️ 门槛【不占输入槽】。若本条应当"只是纯催化剂（.notConsumable）"，改 SHANHAI_TYPES 即可，一处生效。
    {
        id: 'shanhai:pf/primordial_matter_recombination_28',
        type: 'primordial_matter_recombination',
        circuit: 12,
        notConsumable: [],
        moduleLevelRequirement: '1x shanhai:basic_material_module',
        moduleLevelFallbackCatalyst: '1x shanhai:basic_material_module',
        itemInputs: ['2x gtceu:carbon_dust'],
        inputFluids: ['gtceu:hydrogen 4000'],
        itemOutputs: [],
        outputFluids: ['gtceu:polyethylene 1000'],
        chancedOutputs: [],
        duration: 200,
        EUt: 32
    }
,
    // ▶ PF.txt 第 29 条（本次新增）｜元件「处理样板LV」｜纸：类型「星际物质吸取」／耗时「30s」｜输出 1x gtlcore:treasures_crystal + 8x gtlcore:mining_crystal
    // 🧪 物质模块【不消耗·等级门槛·纸上有催化剂纸】：纸上（第 1 格）放着「物质模块是催化剂」，且类型 interstellar_matter_absorption ∈ SHANHAI_TYPES（= 山海自己的机器） ⇒ 落 ModuleLevelCondition（要求模块等级 ≥ 2，等级取自 MODULE_LEVELS）。⚠️ 门槛【不占输入槽】。若本条应当"只是纯催化剂（.notConsumable）"，改 SHANHAI_TYPES 即可，一处生效。
    {
        id: 'shanhai:pf/treasures_crystal',
        type: 'interstellar_matter_absorption',
        circuit: 1,
        notConsumable: [],
        moduleLevelRequirement: '1x shanhai:basic_material_module',
        moduleLevelFallbackCatalyst: '1x shanhai:basic_material_module',
        itemInputs: [],
        inputFluids: [],
        itemOutputs: ['1x gtlcore:treasures_crystal', '8x gtlcore:mining_crystal'],
        outputFluids: [],
        chancedOutputs: [],
        duration: 600,
        EUt: 32
    }
,
    // ▶ PF.txt 第 30 条（本次新增）｜元件「处理样板LV」｜纸：类型「光子分离」／耗时「3s」｜输出 2x shanhai:photon + 1x shanhai:photon_rainbow + 1x shanhai:unknown_particle
    // 🧪 物质模块【不消耗·等级门槛·纸上有催化剂纸】：纸上（第 5 格）放着「物质模块是催化剂」，且类型 photon_separation ∈ SHANHAI_TYPES（= 山海自己的机器） ⇒ 落 ModuleLevelCondition（要求模块等级 ≥ 1，等级取自 MODULE_LEVELS）。⚠️ 门槛【不占输入槽】。若本条应当"只是纯催化剂（.notConsumable）"，改 SHANHAI_TYPES 即可，一处生效。
    {
        id: 'shanhai:pf/photon_2',
        type: 'photon_separation',
        circuit: 1,
        notConsumable: ['1x gtceu:lv_field_generator'],
        moduleLevelRequirement: '1x shanhai:introductory_material_module',
        moduleLevelFallbackCatalyst: '1x shanhai:introductory_material_module',
        itemInputs: ['16x shanhai:first_light'],
        inputFluids: [],
        itemOutputs: ['2x shanhai:photon', '1x shanhai:photon_rainbow', '1x shanhai:unknown_particle'],
        outputFluids: ['shanhai:light 2000'],
        chancedOutputs: [],
        duration: 60,
        EUt: 32
    }
,
    // ▶ PF.txt 第 31 条（本次新增）｜元件「处理样板LV」｜纸：类型「原初物质重组」／耗时「3s」｜输出 
    // 🧪 物质模块【不消耗·等级门槛·纸上有催化剂纸】：纸上（第 7 格）放着「物质模块是催化剂」，且类型 primordial_matter_recombination ∈ SHANHAI_TYPES（= 山海自己的机器） ⇒ 落 ModuleLevelCondition（要求模块等级 ≥ 2，等级取自 MODULE_LEVELS）。⚠️ 门槛【不占输入槽】。若本条应当"只是纯催化剂（.notConsumable）"，改 SHANHAI_TYPES 即可，一处生效。
    {
        id: 'shanhai:pf/primordial_matter_recombination_31',
        type: 'primordial_matter_recombination',
        circuit: 13,
        notConsumable: [],
        moduleLevelRequirement: '1x shanhai:basic_material_module',
        moduleLevelFallbackCatalyst: '1x shanhai:basic_material_module',
        itemInputs: ['17x gtceu:carbon_dust'],
        inputFluids: ['gtceu:hydrogen 30000', 'gtceu:oxygen 10000'],
        itemOutputs: [],
        outputFluids: ['gtceu:glue 5000'],
        chancedOutputs: [],
        duration: 60,
        EUt: 32
    }
,
    // ▶ PF.txt 第 32 条（本次新增）｜元件「处理样板LV」｜纸：类型「物质流凝结」／耗时「15s」｜输出 
    // 🧪 物质模块【不消耗·等级门槛·纸上有催化剂纸】：纸上（第 6 格）放着「物质模块是催化剂」，且类型 matter_flow_condensation ∈ SHANHAI_TYPES（= 山海自己的机器） ⇒ 落 ModuleLevelCondition（要求模块等级 ≥ 1，等级取自 MODULE_LEVELS）。⚠️ 门槛【不占输入槽】。若本条应当"只是纯催化剂（.notConsumable）"，改 SHANHAI_TYPES 即可，一处生效。
    {
        id: 'shanhai:pf/matter_flow_condensation_32',
        type: 'matter_flow_condensation',
        circuit: 0,
        notConsumable: [],
        moduleLevelRequirement: '1x shanhai:introductory_material_module',
        moduleLevelFallbackCatalyst: '1x shanhai:introductory_material_module',
        itemInputs: ['1x gtceu:conductive_alloy_ingot', '1x gtceu:annealed_copper_ingot', '1x gtceu:cupronickel_ingot'],
        inputFluids: ['gtceu:tin_alloy 100', 'gtceu:ender_pearl 100'],
        itemOutputs: [],
        outputFluids: ['shanhai:matter_fluid_foundation 1000'],
        chancedOutputs: [],
        duration: 300,
        EUt: 32
    }
,
    // ▶ PF.txt 第 33 条（本次新增）｜元件「处理样板LV」｜纸：类型「原初物质重组」／耗时「10s」｜输出 
    // 🧪 物质模块【不消耗·等级门槛·纸上有催化剂纸】：纸上（第 1 格）放着「物质模块是催化剂」，且类型 primordial_matter_recombination ∈ SHANHAI_TYPES（= 山海自己的机器） ⇒ 落 ModuleLevelCondition（要求模块等级 ≥ 2，等级取自 MODULE_LEVELS）。⚠️ 门槛【不占输入槽】。若本条应当"只是纯催化剂（.notConsumable）"，改 SHANHAI_TYPES 即可，一处生效。
    {
        id: 'shanhai:pf/primordial_matter_recombination_33',
        type: 'primordial_matter_recombination',
        circuit: 11,
        notConsumable: [],
        moduleLevelRequirement: '1x shanhai:basic_material_module',
        moduleLevelFallbackCatalyst: '1x shanhai:basic_material_module',
        itemInputs: ['5x gtceu:carbon_dust'],
        inputFluids: ['gtceu:hydrogen 8000'],
        itemOutputs: [],
        outputFluids: ['gtceu:rubber 1000'],
        chancedOutputs: [],
        duration: 200,
        EUt: 32
    }
,
    // ▶ PF.txt 第 34 条（本次新增）｜元件「处理样板LV」｜纸：类型「光子分离」／耗时「3s」｜输出 8x shanhai:photon_rainbow + 1x shanhai:unknown_particle
    // 🧪 物质模块【不消耗·等级门槛·纸上有催化剂纸】：纸上（第 5 格）放着「物质模块是催化剂」，且类型 photon_separation ∈ SHANHAI_TYPES（= 山海自己的机器） ⇒ 落 ModuleLevelCondition（要求模块等级 ≥ 1，等级取自 MODULE_LEVELS）。⚠️ 门槛【不占输入槽】。若本条应当"只是纯催化剂（.notConsumable）"，改 SHANHAI_TYPES 即可，一处生效。
    {
        id: 'shanhai:pf/photon_rainbow',
        type: 'photon_separation',
        circuit: 1,
        notConsumable: ['1x gtceu:lv_field_generator'],
        moduleLevelRequirement: '1x shanhai:introductory_material_module',
        moduleLevelFallbackCatalyst: '1x shanhai:introductory_material_module',
        itemInputs: ['4x shanhai:photon'],
        inputFluids: ['shanhai:light 4000'],
        itemOutputs: ['8x shanhai:photon_rainbow', '1x shanhai:unknown_particle'],
        outputFluids: [],
        chancedOutputs: [],
        duration: 60,
        EUt: 32
    }
,
    // ▶ PF.txt 第 35 条（本次新增）｜元件「处理样板LV」｜纸：类型「原初物质重组」／耗时「3s」｜输出 1x gtceu:exquisite_emerald_gem
    // 🧪 物质模块【不消耗·等级门槛·纸上有催化剂纸】：纸上（第 5 格）放着「物质模块是催化剂」，且类型 primordial_matter_recombination ∈ SHANHAI_TYPES（= 山海自己的机器） ⇒ 落 ModuleLevelCondition（要求模块等级 ≥ 2，等级取自 MODULE_LEVELS）。⚠️ 门槛【不占输入槽】。若本条应当"只是纯催化剂（.notConsumable）"，改 SHANHAI_TYPES 即可，一处生效。
    {
        id: 'shanhai:pf/exquisite_emerald_gem',
        type: 'primordial_matter_recombination',
        circuit: 15,
        notConsumable: [],
        moduleLevelRequirement: '1x shanhai:basic_material_module',
        moduleLevelFallbackCatalyst: '1x shanhai:basic_material_module',
        itemInputs: ['3x minecraft:emerald'],
        inputFluids: [],
        itemOutputs: ['1x gtceu:exquisite_emerald_gem'],
        outputFluids: [],
        chancedOutputs: [],
        duration: 60,
        EUt: 32
    }
,
    // ▶ PF.txt 第 37 条（本次新增）｜元件「处理样板LV」｜纸：类型「世线电路板组装」／耗时「60s」｜输出 1x shanhai:wl_board_lv
    // 🧪 物质模块【不消耗·等级门槛·纸上有催化剂纸】：纸上（第 3 格）放着「物质模块是催化剂」，且类型 wl_board_circuit_assembly ∈ SHANHAI_TYPES（= 山海自己的机器） ⇒ 落 ModuleLevelCondition（要求模块等级 ≥ 1，等级取自 MODULE_LEVELS）。⚠️ 门槛【不占输入槽】。若本条应当"只是纯催化剂（.notConsumable）"，改 SHANHAI_TYPES 即可，一处生效。
    {
        id: 'shanhai:pf/wl_board_lv',
        type: 'wl_board_circuit_assembly',
        circuit: 1,
        notConsumable: [],
        moduleLevelRequirement: '1x shanhai:introductory_material_module',
        moduleLevelFallbackCatalyst: '1x shanhai:introductory_material_module',
        itemInputs: ['1x kubejs:lv_universal_circuit', '4x shanhai:electron', '4x shanhai:first_light'],
        inputFluids: ['shanhai:zero_point_energy 1000', 'shanhai:matter_fluid_foundation 2000'],
        itemOutputs: ['1x shanhai:wl_board_lv'],
        outputFluids: [],
        chancedOutputs: [],
        duration: 1200,
        EUt: 32
    }
,
    // ▶ PF.txt 第 38 条（本次新增）｜元件「处理样板-星门(MAX+16)」｜纸：类型「量子化现实重构」／耗时「3s」｜输出 1x shanhai:test_item + 1x shanhai:test_dynamic_text + 1x shanhai:zwf
    // 🧪 物质模块【不消耗·等级门槛·纸上有催化剂纸】：纸上（第 5 格）放着「物质模块是催化剂」，且类型 spacetime_distortion ∈ SHANHAI_TYPES（= 山海自己的机器） ⇒ 落 ModuleLevelCondition（要求模块等级 ≥ 17，等级取自 MODULE_LEVELS）。⚠️ 门槛【不占输入槽】。若本条应当"只是纯催化剂（.notConsumable）"，改 SHANHAI_TYPES 即可，一处生效。
    // 🔴 元件「处理样板-星门」：用户最新裁决（原话逐字）「溢出那就算了，改成 max+8=max,4^8A」
    //    ⇒ **MAX+8 = MAX 电压 + 4^8 安培**。
    //    独立验算：V[MAX] = 2147483648（= 2^31，jar 字节码真值）；4^8 = 2^16 = 65536
    //    ⇒ EUt = 2^31 × 2^16 = **2^47 = 140737488355328**，只有 Long.MAX（2^63−1）的 1/65536 ⇒ **不溢出**。
    //    ⚠️ 上一版口径（4^16）算出 2^63 = Long.MAX+1 ⇒ 回绕成负数，那正是当时只写 V[MAX] 的原因；本版已解除。
    //    完整算式、字节码取证与作废留档见文件头 §8。
    {
        id: 'shanhai:pf/test_item',
        type: 'spacetime_distortion',
        circuit: 30,
        notConsumable: [],
        moduleLevelRequirement: '1x shanhai:genesis_reality_modification_module',
        moduleLevelFallbackCatalyst: '1x shanhai:genesis_reality_modification_module',
        itemInputs: ['1x minecraft:cobblestone'],
        inputFluids: [],
        itemOutputs: ['1x shanhai:test_item', '1x shanhai:test_dynamic_text', '1x shanhai:zwf'],
        outputFluids: [],
        chancedOutputs: [],
        duration: 60,
        EUt: 140737488355328
    }
,
    // ▶ PF.txt 第 39 条（本次新增）｜元件「处理样板-星门(MAX+16)」｜纸：类型「量子化现实重构」／耗时「3s」｜输出 1x gtceu:creative_chest
    // 🧪 物质模块【不消耗·等级门槛·纸上有催化剂纸】：纸上（第 5 格）放着「物质模块是催化剂」，且类型 spacetime_distortion ∈ SHANHAI_TYPES（= 山海自己的机器） ⇒ 落 ModuleLevelCondition（要求模块等级 ≥ 17，等级取自 MODULE_LEVELS）。⚠️ 门槛【不占输入槽】。若本条应当"只是纯催化剂（.notConsumable）"，改 SHANHAI_TYPES 即可，一处生效。
    // 🔴 元件「处理样板-星门」：用户最新裁决（原话逐字）「溢出那就算了，改成 max+8=max,4^8A」
    //    ⇒ **MAX+8 = MAX 电压 + 4^8 安培**。
    //    独立验算：V[MAX] = 2147483648（= 2^31，jar 字节码真值）；4^8 = 2^16 = 65536
    //    ⇒ EUt = 2^31 × 2^16 = **2^47 = 140737488355328**，只有 Long.MAX（2^63−1）的 1/65536 ⇒ **不溢出**。
    //    ⚠️ 上一版口径（4^16）算出 2^63 = Long.MAX+1 ⇒ 回绕成负数，那正是当时只写 V[MAX] 的原因；本版已解除。
    //    完整算式、字节码取证与作废留档见文件头 §8。
    {
        id: 'shanhai:pf/creative_chest',
        type: 'spacetime_distortion',
        circuit: 32,
        notConsumable: [],
        moduleLevelRequirement: '1x shanhai:genesis_reality_modification_module',
        moduleLevelFallbackCatalyst: '1x shanhai:genesis_reality_modification_module',
        itemInputs: ['1x minecraft:cobblestone'],
        inputFluids: [],
        itemOutputs: ['1x gtceu:creative_chest'],
        outputFluids: [],
        chancedOutputs: [],
        duration: 60,
        EUt: 140737488355328
    }
,
    // ▶ PF.txt 第 40 条（本次新增）｜元件「处理样板-星门(MAX+16)」｜纸：类型「量子化现实重构」／耗时「3s」｜输出 1x shanhai:primordial_debug_module
    // 🧪 物质模块【不消耗·等级门槛·纸上有催化剂纸】：纸上（第 5 格）放着「物质模块是催化剂」，且类型 spacetime_distortion ∈ SHANHAI_TYPES（= 山海自己的机器） ⇒ 落 ModuleLevelCondition（要求模块等级 ≥ 17，等级取自 MODULE_LEVELS）。⚠️ 门槛【不占输入槽】。若本条应当"只是纯催化剂（.notConsumable）"，改 SHANHAI_TYPES 即可，一处生效。
    // 🔴 元件「处理样板-星门」：用户最新裁决（原话逐字）「溢出那就算了，改成 max+8=max,4^8A」
    //    ⇒ **MAX+8 = MAX 电压 + 4^8 安培**。
    //    独立验算：V[MAX] = 2147483648（= 2^31，jar 字节码真值）；4^8 = 2^16 = 65536
    //    ⇒ EUt = 2^31 × 2^16 = **2^47 = 140737488355328**，只有 Long.MAX（2^63−1）的 1/65536 ⇒ **不溢出**。
    //    ⚠️ 上一版口径（4^16）算出 2^63 = Long.MAX+1 ⇒ 回绕成负数，那正是当时只写 V[MAX] 的原因；本版已解除。
    //    完整算式、字节码取证与作废留档见文件头 §8。
    {
        id: 'shanhai:pf/primordial_debug_module',
        type: 'spacetime_distortion',
        circuit: 31,
        notConsumable: [],
        moduleLevelRequirement: '1x shanhai:genesis_reality_modification_module',
        moduleLevelFallbackCatalyst: '1x shanhai:genesis_reality_modification_module',
        itemInputs: ['1x minecraft:cobblestone'],
        inputFluids: [],
        itemOutputs: ['1x shanhai:primordial_debug_module'],
        outputFluids: [],
        chancedOutputs: [],
        duration: 60,
        EUt: 140737488355328
    }
,
    // ▶ PF.txt 第 64 条（本次新增）｜元件「处理样板MV」｜纸：类型「原初奇点反演」／耗时「3s」｜输出 1x shanhai:down_quark_emission_catalyst
    {
        id: 'shanhai:pf/down_quark_emission_catalyst',
        type: 'primordial_singularity_inversion',
        circuit: 8,
        notConsumable: [],
        itemInputs: ['1x shanhai:casing_empty_quark_emission_catalyst', '1x gtceu:mixed_plant', '1x gtceu:separated_plant'],
        inputFluids: [],
        itemOutputs: ['1x shanhai:down_quark_emission_catalyst'],
        outputFluids: [],
        chancedOutputs: [],
        duration: 60,
        EUt: 128
    }
,
    // ▶ PF.txt 第 65 条（本次新增）｜元件「处理样板MV」｜纸：类型「光子分离」／耗时「3s」｜输出 1x shanhai:up_quark + 1x shanhai:casing_empty_quark_emission_catalyst
    // 🧪 物质模块【不消耗·等级门槛·纸上有催化剂纸】：纸上（第 3 格）放着「物质模块是催化剂」，且类型 photon_separation ∈ SHANHAI_TYPES（= 山海自己的机器） ⇒ 落 ModuleLevelCondition（要求模块等级 ≥ 2，等级取自 MODULE_LEVELS）。⚠️ 门槛【不占输入槽】。若本条应当"只是纯催化剂（.notConsumable）"，改 SHANHAI_TYPES 即可，一处生效。
    {
        id: 'shanhai:pf/up_quark',
        type: 'photon_separation',
        circuit: 2,
        notConsumable: [],
        moduleLevelRequirement: '1x shanhai:basic_material_module',
        moduleLevelFallbackCatalyst: '1x shanhai:basic_material_module',
        itemInputs: ['2x shanhai:photon_rainbow', '1x shanhai:up_quark_emission_catalyst'],
        inputFluids: [],
        itemOutputs: ['1x shanhai:up_quark', '1x shanhai:casing_empty_quark_emission_catalyst'],
        outputFluids: ['shanhai:zero_point_energy 2000'],
        chancedOutputs: [],
        duration: 60,
        EUt: 128
    }
,
    // ▶ PF.txt 第 66 条（本次新增）｜元件「处理样板MV」｜纸：类型「原初奇点反演」／耗时「30s」｜输出 1x shanhai:casing_empty_quark_emission_catalyst
    // 🧪 物质模块【不消耗·等级门槛·纸上有催化剂纸】：纸上（第 1 格）放着「物质模块是催化剂」，且类型 primordial_singularity_inversion ∈ SHANHAI_TYPES（= 山海自己的机器） ⇒ 落 ModuleLevelCondition（要求模块等级 ≥ 2，等级取自 MODULE_LEVELS）。⚠️ 门槛【不占输入槽】。若本条应当"只是纯催化剂（.notConsumable）"，改 SHANHAI_TYPES 即可，一处生效。
    {
        id: 'shanhai:pf/casing_empty_quark_emission_catalyst',
        type: 'primordial_singularity_inversion',
        circuit: 9,
        notConsumable: [],
        moduleLevelRequirement: '1x shanhai:basic_material_module',
        moduleLevelFallbackCatalyst: '1x shanhai:basic_material_module',
        itemInputs: ['1x gtceu:lv_sodium_battery', '16x shanhai:electron', '4x gtceu:mv_field_generator', '1x thetornproductionline:celestial_secret_deducing_module_hv'],
        inputFluids: ['shanhai:matter_fluid_basic 16000', 'shanhai:zero_point_energy 4000'],
        itemOutputs: ['1x shanhai:casing_empty_quark_emission_catalyst'],
        outputFluids: [],
        chancedOutputs: [],
        duration: 600,
        EUt: 128
    }
,
    // ▶ PF.txt 第 67 条（本次新增）｜元件「处理样板MV」｜纸：类型「光子分离」／耗时「3s」｜输出 1x shanhai:up_quark
    // 🧪 物质模块【不消耗·等级门槛·纸上有催化剂纸】：纸上（第 4 格）放着「物质模块是催化剂」，且类型 photon_separation ∈ SHANHAI_TYPES（= 山海自己的机器） ⇒ 落 ModuleLevelCondition（要求模块等级 ≥ 3，等级取自 MODULE_LEVELS）。⚠️ 门槛【不占输入槽】。若本条应当"只是纯催化剂（.notConsumable）"，改 SHANHAI_TYPES 即可，一处生效。
    // [SHANHAI-DESC] 夸克释放催化剂作为催化剂 ⇒ 落 `.notConsumable(64x shanhai:up_quark_emission_catalyst)`（**不消耗**，数量照纸上一字未改）
    {
        id: 'shanhai:pf/up_quark_2',
        type: 'photon_separation',
        circuit: 22,
        notConsumable: ['64x shanhai:up_quark_emission_catalyst'],
        moduleLevelRequirement: '1x shanhai:material_deduction_module',
        moduleLevelFallbackCatalyst: '1x shanhai:material_deduction_module',
        itemInputs: ['2x shanhai:photon_rainbow'],
        inputFluids: [],
        itemOutputs: ['1x shanhai:up_quark'],
        outputFluids: ['shanhai:zero_point_energy 2000'],
        chancedOutputs: [],
        duration: 60,
        EUt: 128
    }
,
    // ▶ PF.txt 第 68 条（本次新增）｜元件「处理样板MV」｜纸：类型「光子分离」／耗时「3s」｜输出 1x shanhai:down_quark + 1x shanhai:casing_empty_quark_emission_catalyst
    // 🧪 物质模块【不消耗·等级门槛·纸上有催化剂纸】：纸上（第 3 格）放着「物质模块是催化剂」，且类型 photon_separation ∈ SHANHAI_TYPES（= 山海自己的机器） ⇒ 落 ModuleLevelCondition（要求模块等级 ≥ 2，等级取自 MODULE_LEVELS）。⚠️ 门槛【不占输入槽】。若本条应当"只是纯催化剂（.notConsumable）"，改 SHANHAI_TYPES 即可，一处生效。
    {
        id: 'shanhai:pf/down_quark',
        type: 'photon_separation',
        circuit: 2,
        notConsumable: [],
        moduleLevelRequirement: '1x shanhai:basic_material_module',
        moduleLevelFallbackCatalyst: '1x shanhai:basic_material_module',
        itemInputs: ['2x shanhai:photon_rainbow', '1x shanhai:down_quark_emission_catalyst'],
        inputFluids: [],
        itemOutputs: ['1x shanhai:down_quark', '1x shanhai:casing_empty_quark_emission_catalyst'],
        outputFluids: ['shanhai:zero_point_energy 2000'],
        chancedOutputs: [],
        duration: 60,
        EUt: 128
    }
,
    // ▶ PF.txt 第 69 条（本次新增）｜元件「处理样板MV」｜纸：类型「世线震荡收集」／耗时「60s」｜输出 1x shanhai:dimensional_worldline_fragment
    // 🧪 物质模块【消耗·纸上无催化剂纸】：纸上（无）【没有】「物质模块是催化剂」；另：本条类型 ∈ 世线族（历史读数，现不参与判定） ⇒ 按 2026-10-03 判据（只看纸）模块留在 itemInputs 里被正常消耗，不挂催化剂、不设门槛。
    {
        id: 'shanhai:pf/dimensional_worldline_fragment',
        type: 'worldline_oscillation_collection',
        circuit: 0,
        notConsumable: [],
        itemInputs: ['4x shanhai:up_quark', '1x shanhai:worldline_crystal_core', '4x shanhai:down_quark', '1x shanhai:primordial_matter_caster', '1x shanhai:primordial_divergence_generator', '4x shanhai:wl_board_mv', '1x shanhai:primordial_critical_processing_module', '4x thetornproductionline:celestial_secret_deducing_module_mv', '1x shanhai:basic_material_module'],
        inputFluids: ['shanhai:matter_fluid_basic 16000'],
        itemOutputs: ['1x shanhai:dimensional_worldline_fragment'],
        outputFluids: [],
        chancedOutputs: [],
        duration: 1200,
        EUt: 128
    }
,
    // ▶ PF.txt 第 70 条（本次新增）｜元件「处理样板MV」｜纸：类型「光子分离」／耗时「3s」｜输出 1x shanhai:down_quark
    // 🧪 物质模块【不消耗·等级门槛·纸上有催化剂纸】：纸上（第 4 格）放着「物质模块是催化剂」，且类型 photon_separation ∈ SHANHAI_TYPES（= 山海自己的机器） ⇒ 落 ModuleLevelCondition（要求模块等级 ≥ 3，等级取自 MODULE_LEVELS）。⚠️ 门槛【不占输入槽】。若本条应当"只是纯催化剂（.notConsumable）"，改 SHANHAI_TYPES 即可，一处生效。
    // [SHANHAI-DESC] 夸克释放催化剂作为催化剂 ⇒ 落 `.notConsumable(64x shanhai:down_quark_emission_catalyst)`（**不消耗**，数量照纸上一字未改）
    {
        id: 'shanhai:pf/down_quark_2',
        type: 'photon_separation',
        circuit: 22,
        notConsumable: ['64x shanhai:down_quark_emission_catalyst'],
        moduleLevelRequirement: '1x shanhai:material_deduction_module',
        moduleLevelFallbackCatalyst: '1x shanhai:material_deduction_module',
        itemInputs: ['2x shanhai:photon_rainbow'],
        inputFluids: [],
        itemOutputs: ['1x shanhai:down_quark'],
        outputFluids: ['shanhai:zero_point_energy 2000'],
        chancedOutputs: [],
        duration: 60,
        EUt: 128
    }
,
    // ▶ PF.txt 第 71 条（本次新增）｜元件「处理样板MV」｜纸：类型「物质模块铸造」／耗时「60s」｜输出 1x shanhai:material_deduction_module
    // 🧪 物质模块【消耗·纸上无催化剂纸】：纸上（无）【没有】「物质模块是催化剂」；另：本条【产出】里有物质模块（= 属于"制作物质模块"），与"只看纸"的结论【一致】，不冲突 ⇒ 按 2026-10-03 判据（只看纸）模块留在 itemInputs 里被正常消耗，不挂催化剂、不设门槛。
    {
        id: 'shanhai:pf/material_deduction_module',
        type: 'matter_module_casting',
        circuit: 0,
        notConsumable: [],
        itemInputs: ['2x shanhai:wl_board_mv', '2x thetornproductionline:celestial_secret_deducing_module_mv', '1x shanhai:basic_material_module', '16x shanhai:up_quark', '16x shanhai:down_quark', '1x shanhai:dimensional_worldline_fragment'],
        inputFluids: ['shanhai:matter_fluid_basic 2000', 'shanhai:matter_fluid_foundation 4000', 'shanhai:matter_fluid_entry 8000'],
        itemOutputs: ['1x shanhai:material_deduction_module'],
        outputFluids: [],
        chancedOutputs: [],
        duration: 1200,
        EUt: 128
    }
,
    // ▶ PF.txt 第 72 条（本次新增）｜元件「处理样板MV」｜纸：类型「原初奇点反演」／耗时「3s」｜输出 1x shanhai:up_quark_emission_catalyst
    {
        id: 'shanhai:pf/up_quark_emission_catalyst',
        type: 'primordial_singularity_inversion',
        circuit: 8,
        notConsumable: [],
        itemInputs: ['1x shanhai:casing_empty_quark_emission_catalyst', '1x gtceu:processing_plant', '1x gtceu:assemble_plant'],
        inputFluids: [],
        itemOutputs: ['1x shanhai:up_quark_emission_catalyst'],
        outputFluids: [],
        chancedOutputs: [],
        duration: 60,
        EUt: 128
    }
,
    // ▶ PF.txt 第 73 条（本次新增）｜元件「处理样板MV」｜纸：类型「世线采样」／耗时「30s」｜输出 1x shanhai:worldline_crystal_core
    {
        id: 'shanhai:pf/worldline_crystal_core',
        type: 'worldline_sampling',
        circuit: 0,
        notConsumable: [],
        itemInputs: ['16x shanhai:unknown_particle', '64x shanhai:cosmic_dust', '4x gtlcore:treasures_crystal'],
        inputFluids: ['shanhai:zero_point_energy 1024000'],
        itemOutputs: ['1x shanhai:worldline_crystal_core'],
        outputFluids: [],
        chancedOutputs: [],
        duration: 600,
        EUt: 128
    }
,
    // ▶ PF.txt 第 74 条（本次新增）｜元件「处理样板MV」｜纸：类型「星际物质吸取」／耗时「3s」｜输出 1x shanhai:cosmic_dust
    // 🧪 物质模块【不消耗·等级门槛·纸上有催化剂纸】：纸上（第 3 格）放着「物质模块是催化剂」，且类型 interstellar_matter_absorption ∈ SHANHAI_TYPES（= 山海自己的机器） ⇒ 落 ModuleLevelCondition（要求模块等级 ≥ 2，等级取自 MODULE_LEVELS）。⚠️ 门槛【不占输入槽】。若本条应当"只是纯催化剂（.notConsumable）"，改 SHANHAI_TYPES 即可，一处生效。
    {
        id: 'shanhai:pf/cosmic_dust',
        type: 'interstellar_matter_absorption',
        circuit: 2,
        notConsumable: [],
        moduleLevelRequirement: '1x shanhai:basic_material_module',
        moduleLevelFallbackCatalyst: '1x shanhai:basic_material_module',
        itemInputs: [],
        inputFluids: [],
        itemOutputs: ['1x shanhai:cosmic_dust'],
        outputFluids: [],
        chancedOutputs: [],
        duration: 60,
        EUt: 128
    }
,
    // ▶ PF.txt 第 75 条（本次新增）｜元件「处理样板MV」｜纸：类型「原初奇点反演」／耗时「3s」｜输出 64x kubejs:lv_universal_circuit
    // 🧪 物质模块【不消耗·等级门槛·纸上有催化剂纸】：纸上（第 4 格）放着「物质模块是催化剂」，且类型 primordial_singularity_inversion ∈ SHANHAI_TYPES（= 山海自己的机器） ⇒ 落 ModuleLevelCondition（要求模块等级 ≥ 3，等级取自 MODULE_LEVELS）。⚠️ 门槛【不占输入槽】。若本条应当"只是纯催化剂（.notConsumable）"，改 SHANHAI_TYPES 即可，一处生效。
    {
        id: 'shanhai:pf/lv_universal_circuit',
        type: 'primordial_singularity_inversion',
        circuit: 32,
        notConsumable: [],
        moduleLevelRequirement: '1x shanhai:material_deduction_module',
        moduleLevelFallbackCatalyst: '1x shanhai:material_deduction_module',
        itemInputs: ['1x thetornproductionline:celestial_secret_deducing_module_mv', '1x shanhai:wl_board_mv'],
        inputFluids: [],
        itemOutputs: ['64x kubejs:lv_universal_circuit'],
        outputFluids: [],
        chancedOutputs: [],
        duration: 60,
        EUt: 128
    }
,
    // ▶ PF.txt 第 76 条（本次新增）｜元件「处理样板MV」｜纸：类型「物质流凝结」／耗时「15s」｜输出 
    // 🧪 物质模块【不消耗·等级门槛·纸上有催化剂纸】：纸上（第 6 格）放着「物质模块是催化剂」，且类型 matter_flow_condensation ∈ SHANHAI_TYPES（= 山海自己的机器） ⇒ 落 ModuleLevelCondition（要求模块等级 ≥ 2，等级取自 MODULE_LEVELS）。⚠️ 门槛【不占输入槽】。若本条应当"只是纯催化剂（.notConsumable）"，改 SHANHAI_TYPES 即可，一处生效。
    {
        id: 'shanhai:pf/matter_flow_condensation_76',
        type: 'matter_flow_condensation',
        circuit: 0,
        notConsumable: [],
        moduleLevelRequirement: '1x shanhai:basic_material_module',
        moduleLevelFallbackCatalyst: '1x shanhai:basic_material_module',
        itemInputs: ['4x shanhai:cosmic_dust', '1x gtceu:stainless_steel_ingot', '1x gtceu:silicon_ingot'],
        inputFluids: ['gtceu:polyethylene 200', 'gtceu:copper 1000'],
        itemOutputs: [],
        outputFluids: ['shanhai:matter_fluid_basic 1000'],
        chancedOutputs: [],
        duration: 300,
        EUt: 128
    }
,
    // ▶ PF.txt 第 77 条（本次新增）｜元件「处理样板MV」｜纸：类型「原初世线切割」／耗时「60s」｜输出 1x shanhai:thread_shard_1
    // 🧪 物质模块【不消耗·等级门槛·纸上有催化剂纸】：纸上（第 5 格）放着「物质模块是催化剂」，且类型 worldline_cutting ∈ SHANHAI_TYPES（= 山海自己的机器） ⇒ 落 ModuleLevelCondition（要求模块等级 ≥ 3，等级取自 MODULE_LEVELS）。⚠️ 门槛【不占输入槽】。若本条应当"只是纯催化剂（.notConsumable）"，改 SHANHAI_TYPES 即可，一处生效。
    // 🔴 ✅ 纸上写了「物质模块是催化剂」⇒ 按 2026-10-03 判据（只看纸）**不消耗**，落等级门槛；本条产出 ∈ 残片族（shanhai:thread_shard_1 =「世线的运用」） ⇒ 与 2026-10-01 那版判据（看产出）的结论【也一致】，两个口径不打架。
    {
        id: 'shanhai:pf/thread_shard_1',
        type: 'worldline_cutting',
        circuit: 1,
        notConsumable: [],
        moduleLevelRequirement: '1x shanhai:material_deduction_module',
        moduleLevelFallbackCatalyst: '1x shanhai:material_deduction_module',
        itemInputs: ['1x shanhai:dimensional_worldline_fragment', '4x shanhai:electron'],
        inputFluids: [],
        itemOutputs: ['1x shanhai:thread_shard_1'],
        outputFluids: [],
        chancedOutputs: [],
        duration: 1200,
        EUt: 128
    }
,
    // ▶ PF.txt 第 78 条（本次新增）｜元件「处理样板MV」｜纸：类型「世线电路板组装」／耗时「60s」｜输出 1x shanhai:wl_board_mv
    // 🧪 物质模块【不消耗·等级门槛·纸上有催化剂纸】：纸上（第 9 格）放着「物质模块是催化剂」，且类型 wl_board_circuit_assembly ∈ SHANHAI_TYPES（= 山海自己的机器） ⇒ 落 ModuleLevelCondition（要求模块等级 ≥ 2，等级取自 MODULE_LEVELS）。⚠️ 门槛【不占输入槽】。若本条应当"只是纯催化剂（.notConsumable）"，改 SHANHAI_TYPES 即可，一处生效。
    {
        id: 'shanhai:pf/wl_board_mv',
        type: 'wl_board_circuit_assembly',
        circuit: 1,
        notConsumable: [],
        moduleLevelRequirement: '1x shanhai:basic_material_module',
        moduleLevelFallbackCatalyst: '1x shanhai:basic_material_module',
        itemInputs: ['1x kubejs:mv_universal_circuit', '4x shanhai:electron', '1x shanhai:up_quark', '1x shanhai:down_quark'],
        inputFluids: ['shanhai:zero_point_energy 4000', 'shanhai:matter_fluid_basic 2000'],
        itemOutputs: ['1x shanhai:wl_board_mv'],
        outputFluids: [],
        chancedOutputs: [],
        duration: 1200,
        EUt: 128
    }
,
    // ▶ PF.txt 第 79 条（本次新增）｜元件「处理样板MV」｜纸：类型「原初奇点反演」／耗时「3s」｜输出 1x thetornproductionline:celestial_secret_deducing_module_mv
    // 🧪 物质模块【不消耗·等级门槛·纸上有催化剂纸】：纸上（第 2 格）放着「物质模块是催化剂」，且类型 primordial_singularity_inversion ∈ SHANHAI_TYPES（= 山海自己的机器） ⇒ 落 ModuleLevelCondition（要求模块等级 ≥ 3，等级取自 MODULE_LEVELS）。⚠️ 门槛【不占输入槽】。若本条应当"只是纯催化剂（.notConsumable）"，改 SHANHAI_TYPES 即可，一处生效。
    {
        id: 'shanhai:pf/celestial_secret_deducing_module_mv',
        type: 'primordial_singularity_inversion',
        circuit: 31,
        notConsumable: [],
        moduleLevelRequirement: '1x shanhai:material_deduction_module',
        moduleLevelFallbackCatalyst: '1x shanhai:material_deduction_module',
        itemInputs: ['1x kubejs:mv_universal_circuit', '2x shanhai:electron_neutrino', '4x gtceu:double_aluminium_plate'],
        inputFluids: [],
        itemOutputs: ['1x thetornproductionline:celestial_secret_deducing_module_mv'],
        outputFluids: [],
        chancedOutputs: [],
        duration: 60,
        EUt: 128
    }
,
    // ▶ PF.txt 第 80 条（本次新增）｜元件「处理样板MV」｜纸：类型「原初物质重组」／耗时「3s」｜输出 
    // 🧪 物质模块【不消耗·等级门槛·纸上有催化剂纸】：纸上（第 7 格）放着「物质模块是催化剂」，且类型 primordial_matter_recombination ∈ SHANHAI_TYPES（= 山海自己的机器） ⇒ 落 ModuleLevelCondition（要求模块等级 ≥ 3，等级取自 MODULE_LEVELS）。⚠️ 门槛【不占输入槽】。若本条应当"只是纯催化剂（.notConsumable）"，改 SHANHAI_TYPES 即可，一处生效。
    {
        id: 'shanhai:pf/primordial_matter_recombination_80',
        type: 'primordial_matter_recombination',
        circuit: 16,
        notConsumable: [],
        moduleLevelRequirement: '1x shanhai:material_deduction_module',
        moduleLevelFallbackCatalyst: '1x shanhai:material_deduction_module',
        itemInputs: ['2x gtceu:carbon_dust'],
        inputFluids: ['gtceu:nitrogen 2000', 'gtceu:hydrogen 8000'],
        itemOutputs: [],
        outputFluids: ['gtceu:dimethylhydrazine 1000'],
        chancedOutputs: [],
        duration: 60,
        EUt: 128
    }
,
    // ▶ PF.txt 第 81 条（本次新增）｜元件「处理样板HV」｜纸：类型「原初奇点反演」／耗时「3s」｜输出 1x gtmadvancedhatch:adaptive_net_energy_input_hatch
    // 🧪 物质模块【不消耗·等级门槛·纸上有催化剂纸】：纸上（第 5 格）放着「物质模块是催化剂」，且类型 primordial_singularity_inversion ∈ SHANHAI_TYPES（= 山海自己的机器） ⇒ 落 ModuleLevelCondition（要求模块等级 ≥ 4，等级取自 MODULE_LEVELS）。⚠️ 门槛【不占输入槽】。若本条应当"只是纯催化剂（.notConsumable）"，改 SHANHAI_TYPES 即可，一处生效。
    {
        id: 'shanhai:pf/adaptive_net_energy_input_hatch',
        type: 'primordial_singularity_inversion',
        circuit: 30,
        notConsumable: [],
        moduleLevelRequirement: '1x shanhai:virtual_image_material_module',
        moduleLevelFallbackCatalyst: '1x shanhai:virtual_image_material_module',
        itemInputs: ['1x gtceu:hv_energy_input_hatch', '1x shanhai:wl_board_hv'],
        inputFluids: ['shanhai:matter_fluid_virtual 1000'],
        itemOutputs: ['1x gtmadvancedhatch:adaptive_net_energy_input_hatch'],
        outputFluids: [],
        chancedOutputs: [],
        duration: 60,
        EUt: 512
    }
,
    // ▶ PF.txt 第 82 条（本次新增）｜元件「处理样板HV」｜纸：类型「世线电路板组装」／耗时「60s」｜输出 1x shanhai:wem_1
    // 🧪 物质模块【不消耗·等级门槛·纸上有催化剂纸】：纸上（第 5 格）放着「物质模块是催化剂」，且类型 wl_board_circuit_assembly ∈ SHANHAI_TYPES（= 山海自己的机器） ⇒ 落 ModuleLevelCondition（要求模块等级 ≥ 4，等级取自 MODULE_LEVELS）。⚠️ 门槛【不占输入槽】。若本条应当"只是纯催化剂（.notConsumable）"，改 SHANHAI_TYPES 即可，一处生效。
    {
        id: 'shanhai:pf/wem_1',
        type: 'wl_board_circuit_assembly',
        circuit: 2,
        notConsumable: [],
        moduleLevelRequirement: '1x shanhai:virtual_image_material_module',
        moduleLevelFallbackCatalyst: '1x shanhai:virtual_image_material_module',
        itemInputs: ['8x shanhai:wl_board_ulv', '4x shanhai:wl_board_lv', '2x shanhai:wl_board_mv', '1x shanhai:wl_board_hv', '16x shanhai:photon_rainbow', '1x shanhai:navigate_prism', '1x shanhai:dimensional_worldline_fragment'],
        inputFluids: [],
        itemOutputs: ['1x shanhai:wem_1'],
        outputFluids: [],
        chancedOutputs: [],
        duration: 1200,
        EUt: 512
    }
,
    // ▶ PF.txt 第 83 条（本次新增）｜元件「处理样板HV」｜纸：类型「世线电路板组装」／耗时「30s」｜输出 1x shanhai:wl_board_hv
    // 🧪 物质模块【不消耗·等级门槛·纸上有催化剂纸】：纸上（第 4 格）放着「物质模块是催化剂」，且类型 wl_board_circuit_assembly ∈ SHANHAI_TYPES（= 山海自己的机器） ⇒ 落 ModuleLevelCondition（要求模块等级 ≥ 3，等级取自 MODULE_LEVELS）。⚠️ 门槛【不占输入槽】。若本条应当"只是纯催化剂（.notConsumable）"，改 SHANHAI_TYPES 即可，一处生效。
    {
        id: 'shanhai:pf/wl_board_hv',
        type: 'wl_board_circuit_assembly',
        circuit: 1,
        notConsumable: [],
        moduleLevelRequirement: '1x shanhai:material_deduction_module',
        moduleLevelFallbackCatalyst: '1x shanhai:material_deduction_module',
        itemInputs: ['1x kubejs:hv_universal_circuit', '2x shanhai:muon', '2x shanhai:gluon'],
        inputFluids: ['shanhai:zero_point_energy 8000', 'shanhai:matter_fluid_virtual 2000'],
        itemOutputs: ['1x shanhai:wl_board_hv'],
        outputFluids: [],
        chancedOutputs: [],
        duration: 600,
        EUt: 512
    }
,
    // ▶ PF.txt 第 84 条（本次新增）｜元件「处理样板HV」｜纸：类型「原初物质重组」／耗时「3s」｜输出 
    // 🧪 物质模块【不消耗·等级门槛·纸上有催化剂纸】：纸上（第 7 格）放着「物质模块是催化剂」，且类型 primordial_matter_recombination ∈ SHANHAI_TYPES（= 山海自己的机器） ⇒ 落 ModuleLevelCondition（要求模块等级 ≥ 4，等级取自 MODULE_LEVELS）。⚠️ 门槛【不占输入槽】。若本条应当"只是纯催化剂（.notConsumable）"，改 SHANHAI_TYPES 即可，一处生效。
    {
        id: 'shanhai:pf/primordial_matter_recombination_84',
        type: 'primordial_matter_recombination',
        circuit: 17,
        notConsumable: [],
        moduleLevelRequirement: '1x shanhai:virtual_image_material_module',
        moduleLevelFallbackCatalyst: '1x shanhai:virtual_image_material_module',
        itemInputs: [],
        inputFluids: ['gtceu:oxygen 2000', 'gtceu:hydrogen 2000'],
        itemOutputs: [],
        outputFluids: ['gtceu:hydrogen_peroxide 1000'],
        chancedOutputs: [],
        duration: 60,
        EUt: 512
    }
,
    // ▶ PF.txt 第 85 条（本次新增）｜元件「处理样板HV」｜纸：类型「物质模块铸造」／耗时「60s」｜输出 1x shanhai:virtual_image_material_module
    // 🧪 物质模块【消耗·纸上无催化剂纸】：纸上（无）【没有】「物质模块是催化剂」；另：本条【产出】里有物质模块（= 属于"制作物质模块"），与"只看纸"的结论【一致】，不冲突 ⇒ 按 2026-10-03 判据（只看纸）模块留在 itemInputs 里被正常消耗，不挂催化剂、不设门槛。
    {
        id: 'shanhai:pf/virtual_image_material_module',
        type: 'matter_module_casting',
        circuit: 0,
        notConsumable: [],
        itemInputs: ['2x shanhai:wl_board_hv', '2x thetornproductionline:celestial_secret_deducing_module_hv', '1x shanhai:material_deduction_module', '16x shanhai:taixu_dust', '32x shanhai:muon', '4x shanhai:gluon', '4x shanhai:navigate_prism', '64x shanhai:first_light'],
        inputFluids: ['shanhai:matter_fluid_basic 4000', 'shanhai:matter_fluid_foundation 8000', 'shanhai:matter_fluid_entry 16000', 'shanhai:matter_fluid_virtual 2000'],
        itemOutputs: ['1x shanhai:virtual_image_material_module'],
        outputFluids: [],
        chancedOutputs: [],
        duration: 1200,
        EUt: 512
    }
,
    // ▶ PF.txt 第 86 条（本次新增）｜元件「处理样板HV」｜纸：类型「物质锻造」／耗时「10s」｜输出 1x shanhai:gluon
    // 🧪 物质模块【不消耗·等级门槛·纸上有催化剂纸】：纸上（第 4 格）放着「物质模块是催化剂」，且类型 matter_forging ∈ SHANHAI_TYPES（= 山海自己的机器） ⇒ 落 ModuleLevelCondition（要求模块等级 ≥ 3，等级取自 MODULE_LEVELS）。⚠️ 门槛【不占输入槽】。若本条应当"只是纯催化剂（.notConsumable）"，改 SHANHAI_TYPES 即可，一处生效。
    {
        id: 'shanhai:pf/gluon',
        type: 'matter_forging',
        circuit: 1,
        notConsumable: [],
        moduleLevelRequirement: '1x shanhai:material_deduction_module',
        moduleLevelFallbackCatalyst: '1x shanhai:material_deduction_module',
        itemInputs: ['1x shanhai:down_quark'],
        inputFluids: ['shanhai:zero_point_energy 12000', 'shanhai:light 4000'],
        itemOutputs: ['1x shanhai:gluon'],
        outputFluids: [],
        chancedOutputs: [],
        duration: 200,
        EUt: 512
    }
,
    // ▶ PF.txt 第 87 条（本次新增）｜元件「处理样板HV」｜纸：类型「物质锻造」／耗时「10s」｜输出 1x shanhai:gluon
    // 🧪 物质模块【不消耗·等级门槛·纸上有催化剂纸】：纸上（第 5 格）放着「物质模块是催化剂」，且类型 matter_forging ∈ SHANHAI_TYPES（= 山海自己的机器） ⇒ 落 ModuleLevelCondition（要求模块等级 ≥ 3，等级取自 MODULE_LEVELS）。⚠️ 门槛【不占输入槽】。若本条应当"只是纯催化剂（.notConsumable）"，改 SHANHAI_TYPES 即可，一处生效。
    {
        id: 'shanhai:pf/gluon_2',
        type: 'matter_forging',
        circuit: 1,
        notConsumable: [],
        moduleLevelRequirement: '1x shanhai:material_deduction_module',
        moduleLevelFallbackCatalyst: '1x shanhai:material_deduction_module',
        itemInputs: ['1x shanhai:up_quark'],
        inputFluids: ['shanhai:zero_point_energy 12000', 'shanhai:light 4000'],
        itemOutputs: ['1x shanhai:gluon'],
        outputFluids: [],
        chancedOutputs: [],
        duration: 200,
        EUt: 512
    }
,
    // ▶ PF.txt 第 88 条（本次新增）｜元件「处理样板HV」｜纸：类型「原初奇点反演」／耗时「3s」｜输出 64x kubejs:mv_universal_circuit
    // 🧪 物质模块【不消耗·等级门槛·纸上有催化剂纸】：纸上（第 6 格）放着「物质模块是催化剂」，且类型 primordial_singularity_inversion ∈ SHANHAI_TYPES（= 山海自己的机器） ⇒ 落 ModuleLevelCondition（要求模块等级 ≥ 4，等级取自 MODULE_LEVELS）。⚠️ 门槛【不占输入槽】。若本条应当"只是纯催化剂（.notConsumable）"，改 SHANHAI_TYPES 即可，一处生效。
    {
        id: 'shanhai:pf/mv_universal_circuit',
        type: 'primordial_singularity_inversion',
        circuit: 32,
        notConsumable: [],
        moduleLevelRequirement: '1x shanhai:virtual_image_material_module',
        moduleLevelFallbackCatalyst: '1x shanhai:virtual_image_material_module',
        itemInputs: ['1x thetornproductionline:celestial_secret_deducing_module_hv', '1x shanhai:wl_board_hv'],
        inputFluids: [],
        itemOutputs: ['64x kubejs:mv_universal_circuit'],
        outputFluids: [],
        chancedOutputs: [],
        duration: 60,
        EUt: 512
    }
,
    // ▶ PF.txt 第 89 条（本次新增）｜元件「处理样板HV」｜纸：类型「原初奇点反演」／耗时「3s」｜输出 1x thetornproductionline:celestial_secret_deducing_module_hv
    // 🧪 物质模块【不消耗·等级门槛·纸上有催化剂纸】：纸上（第 4 格）放着「物质模块是催化剂」，且类型 primordial_singularity_inversion ∈ SHANHAI_TYPES（= 山海自己的机器） ⇒ 落 ModuleLevelCondition（要求模块等级 ≥ 4，等级取自 MODULE_LEVELS）。⚠️ 门槛【不占输入槽】。若本条应当"只是纯催化剂（.notConsumable）"，改 SHANHAI_TYPES 即可，一处生效。
    {
        id: 'shanhai:pf/celestial_secret_deducing_module_hv',
        type: 'primordial_singularity_inversion',
        circuit: 31,
        notConsumable: [],
        moduleLevelRequirement: '1x shanhai:virtual_image_material_module',
        moduleLevelFallbackCatalyst: '1x shanhai:virtual_image_material_module',
        itemInputs: ['1x kubejs:hv_universal_circuit', '4x shanhai:electron_neutrino', '4x gtceu:double_stainless_steel_plate'],
        inputFluids: [],
        itemOutputs: ['1x thetornproductionline:celestial_secret_deducing_module_hv'],
        outputFluids: [],
        chancedOutputs: [],
        duration: 60,
        EUt: 512
    }
,
    // ▶ PF.txt 第 90 条（本次新增）｜元件「处理样板HV」｜纸：类型「太虚熔炼」／耗时「60s」｜输出 1x shanhai:taixu_dust
    // 🧪 物质模块【不消耗·等级门槛·纸上有催化剂纸】：纸上（第 7 格）放着「物质模块是催化剂」，且类型 taixu_smelting ∈ SHANHAI_TYPES（= 山海自己的机器） ⇒ 落 ModuleLevelCondition（要求模块等级 ≥ 3，等级取自 MODULE_LEVELS）。⚠️ 门槛【不占输入槽】。若本条应当"只是纯催化剂（.notConsumable）"，改 SHANHAI_TYPES 即可，一处生效。
    {
        id: 'shanhai:pf/taixu_dust',
        type: 'taixu_smelting',
        circuit: 0,
        notConsumable: [],
        moduleLevelRequirement: '1x shanhai:material_deduction_module',
        moduleLevelFallbackCatalyst: '1x shanhai:material_deduction_module',
        itemInputs: ['16x shanhai:cosmic_dust', '4x shanhai:gluon'],
        inputFluids: ['shanhai:zero_point_energy 32000', 'shanhai:matter_fluid_virtual 1000'],
        itemOutputs: ['1x shanhai:taixu_dust'],
        outputFluids: [],
        chancedOutputs: [],
        duration: 1200,
        EUt: 512
    }
,
    // ▶ PF.txt 第 91 条（本次新增）｜元件「处理样板HV」｜纸：类型「原初因果编织」／耗时「3s」｜输出 1x shanhai:navigate_prism
    // 🧪 物质模块【不消耗·等级门槛·纸上有催化剂纸】：纸上（第 9 格）放着「物质模块是催化剂」，且类型 primordial_causal_weaving ∈ SHANHAI_TYPES（= 山海自己的机器） ⇒ 落 ModuleLevelCondition（要求模块等级 ≥ 3，等级取自 MODULE_LEVELS）。⚠️ 门槛【不占输入槽】。若本条应当"只是纯催化剂（.notConsumable）"，改 SHANHAI_TYPES 即可，一处生效。
    {
        id: 'shanhai:pf/navigate_prism',
        type: 'primordial_causal_weaving',
        circuit: 1,
        notConsumable: [],
        moduleLevelRequirement: '1x shanhai:material_deduction_module',
        moduleLevelFallbackCatalyst: '1x shanhai:material_deduction_module',
        itemInputs: ['4x shanhai:first_light', '1x shanhai:muon', '1x gtceu:hv_emitter', '3x shanhai:unknown_particle', '1x gtceu:hv_sensor', '1x shanhai:gluon'],
        inputFluids: [],
        itemOutputs: ['1x shanhai:navigate_prism'],
        outputFluids: [],
        chancedOutputs: [],
        duration: 60,
        EUt: 512
    }
,
    // ▶ PF.txt 第 92 条（本次新增）｜元件「处理样板HV」｜纸：类型「光子分离」／耗时「10s」｜输出 1x shanhai:muon + 1x shanhai:muon_neutrino
    // 🧪 物质模块【不消耗·等级门槛·纸上有催化剂纸】：纸上（第 3 格）放着「物质模块是催化剂」，且类型 photon_separation ∈ SHANHAI_TYPES（= 山海自己的机器） ⇒ 落 ModuleLevelCondition（要求模块等级 ≥ 3，等级取自 MODULE_LEVELS）。⚠️ 门槛【不占输入槽】。若本条应当"只是纯催化剂（.notConsumable）"，改 SHANHAI_TYPES 即可，一处生效。
    {
        id: 'shanhai:pf/muon',
        type: 'photon_separation',
        circuit: 3,
        notConsumable: ['1x gtceu:hv_field_generator'],
        moduleLevelRequirement: '1x shanhai:material_deduction_module',
        moduleLevelFallbackCatalyst: '1x shanhai:material_deduction_module',
        itemInputs: ['4x shanhai:photon_rainbow'],
        inputFluids: [],
        itemOutputs: ['1x shanhai:muon', '1x shanhai:muon_neutrino'],
        outputFluids: ['shanhai:zero_point_energy 4000'],
        chancedOutputs: [],
        duration: 200,
        EUt: 512
    }
,
    // ▶ PF.txt 第 93 条（本次新增）｜元件「处理样板HV」｜纸：类型「物质流凝结」／耗时「10s」｜输出 
    // 🧪 物质模块【不消耗·等级门槛·纸上有催化剂纸】：纸上（第 8 格）放着「物质模块是催化剂」，且类型 matter_flow_condensation ∈ SHANHAI_TYPES（= 山海自己的机器） ⇒ 落 ModuleLevelCondition（要求模块等级 ≥ 3，等级取自 MODULE_LEVELS）。⚠️ 门槛【不占输入槽】。若本条应当"只是纯催化剂（.notConsumable）"，改 SHANHAI_TYPES 即可，一处生效。
    {
        id: 'shanhai:pf/matter_flow_condensation_93',
        type: 'matter_flow_condensation',
        circuit: 0,
        notConsumable: [],
        moduleLevelRequirement: '1x shanhai:material_deduction_module',
        moduleLevelFallbackCatalyst: '1x shanhai:material_deduction_module',
        itemInputs: ['1x gtceu:vibrant_alloy_ingot', '1x gtceu:nichrome_ingot', '1x gtceu:titanium_ingot'],
        inputFluids: ['gtceu:ender_eye 144', 'gtceu:polytetrafluoroethylene 144'],
        itemOutputs: [],
        outputFluids: ['shanhai:matter_fluid_virtual 1000'],
        chancedOutputs: [],
        duration: 200,
        EUt: 512
    }
,
    // ▶ PF.txt 第 94 条（本次新增）｜元件「处理样板EV」｜纸：类型「原初奇点反演」／耗时「3s」｜输出 1x thetornproductionline:celestial_secret_deducing_module_hv
    // 🧪 物质模块【不消耗·等级门槛·纸上有催化剂纸】：纸上（第 5 格）放着「物质模块是催化剂」，且类型 primordial_singularity_inversion ∈ SHANHAI_TYPES（= 山海自己的机器） ⇒ 落 ModuleLevelCondition（要求模块等级 ≥ 5，等级取自 MODULE_LEVELS）。⚠️ 门槛【不占输入槽】。若本条应当"只是纯催化剂（.notConsumable）"，改 SHANHAI_TYPES 即可，一处生效。
    {
        id: 'shanhai:pf/celestial_secret_deducing_module_hv_2',
        type: 'primordial_singularity_inversion',
        circuit: 31,
        notConsumable: [],
        moduleLevelRequirement: '1x shanhai:material_recombination_module',
        moduleLevelFallbackCatalyst: '1x shanhai:material_recombination_module',
        itemInputs: ['1x kubejs:ev_universal_circuit', '8x shanhai:electron_neutrino', '4x gtceu:double_titanium_plate'],
        inputFluids: [],
        itemOutputs: ['1x thetornproductionline:celestial_secret_deducing_module_hv'],
        outputFluids: [],
        chancedOutputs: [],
        duration: 60,
        EUt: 2048
    }
,
    // ▶ PF.txt 第 95 条（本次新增）｜元件「处理样板EV」｜纸：类型「物质锻造」／耗时「10s」｜输出 1x shanhai:proton
    // 🧪 物质模块【不消耗·等级门槛·纸上有催化剂纸】：纸上（第 5 格）放着「物质模块是催化剂」，且类型 matter_forging ∈ SHANHAI_TYPES（= 山海自己的机器） ⇒ 落 ModuleLevelCondition（要求模块等级 ≥ 4，等级取自 MODULE_LEVELS）。⚠️ 门槛【不占输入槽】。若本条应当"只是纯催化剂（.notConsumable）"，改 SHANHAI_TYPES 即可，一处生效。
    {
        id: 'shanhai:pf/proton',
        type: 'matter_forging',
        circuit: 2,
        notConsumable: [],
        moduleLevelRequirement: '1x shanhai:virtual_image_material_module',
        moduleLevelFallbackCatalyst: '1x shanhai:virtual_image_material_module',
        itemInputs: ['2x shanhai:up_quark', '1x shanhai:down_quark', '1x shanhai:gluon'],
        inputFluids: ['shanhai:zero_point_energy 16000'],
        itemOutputs: ['1x shanhai:proton'],
        outputFluids: [],
        chancedOutputs: [],
        duration: 200,
        EUt: 2048
    }
,
    // ▶ PF.txt 第 96 条（本次新增）｜元件「处理样板EV」｜纸：类型「物质流凝结」／耗时「10s」｜输出 
    // 🧪 物质模块【不消耗·等级门槛·纸上有催化剂纸】：纸上（第 6 格）放着「物质模块是催化剂」，且类型 matter_flow_condensation ∈ SHANHAI_TYPES（= 山海自己的机器） ⇒ 落 ModuleLevelCondition（要求模块等级 ≥ 4，等级取自 MODULE_LEVELS）。⚠️ 门槛【不占输入槽】。若本条应当"只是纯催化剂（.notConsumable）"，改 SHANHAI_TYPES 即可，一处生效。
    {
        id: 'shanhai:pf/matter_flow_condensation_96',
        type: 'matter_flow_condensation',
        circuit: 0,
        notConsumable: [],
        moduleLevelRequirement: '1x shanhai:virtual_image_material_module',
        moduleLevelFallbackCatalyst: '1x shanhai:virtual_image_material_module',
        itemInputs: ['1x gtceu:tungsten_steel_ingot', '1x gtceu:quantum_eye', '1x minecraft:nether_star'],
        inputFluids: ['gtceu:epoxy 144', 'gtceu:uranium_triplatinum 144'],
        itemOutputs: [],
        outputFluids: ['shanhai:matter_fluid_advanced 1000'],
        chancedOutputs: [],
        duration: 200,
        EUt: 2048
    }
,
    // ▶ PF.txt 第 97 条（本次新增）｜元件「处理样板EV」｜纸：类型「物质锻造」／耗时「10s」｜输出 1x shanhai:pion
    // 🧪 物质模块【不消耗·等级门槛·纸上有催化剂纸】：纸上（第 5 格）放着「物质模块是催化剂」，且类型 matter_forging ∈ SHANHAI_TYPES（= 山海自己的机器） ⇒ 落 ModuleLevelCondition（要求模块等级 ≥ 4，等级取自 MODULE_LEVELS）。⚠️ 门槛【不占输入槽】。若本条应当"只是纯催化剂（.notConsumable）"，改 SHANHAI_TYPES 即可，一处生效。
    {
        id: 'shanhai:pf/pion',
        type: 'matter_forging',
        circuit: 4,
        notConsumable: [],
        moduleLevelRequirement: '1x shanhai:virtual_image_material_module',
        moduleLevelFallbackCatalyst: '1x shanhai:virtual_image_material_module',
        itemInputs: ['1x shanhai:up_quark', '1x shanhai:down_quark', '1x shanhai:gluon'],
        inputFluids: [],
        itemOutputs: ['1x shanhai:pion'],
        outputFluids: ['shanhai:zero_point_energy 4000'],
        chancedOutputs: [],
        duration: 200,
        EUt: 2048
    }
,
    // ▶ PF.txt 第 98 条（本次新增）｜元件「处理样板EV」｜纸：类型「原初奇点反演」／耗时「60s」｜输出 1x thetornproductionline:circult_process_module_1
    // 🧪 物质模块【消耗·纸上无催化剂纸】：纸上（无）【没有】「物质模块是催化剂」 ⇒ 按 2026-10-03 判据（只看纸）模块留在 itemInputs 里被正常消耗，不挂催化剂、不设门槛。
    {
        id: 'shanhai:pf/circult_process_module_1',
        type: 'primordial_singularity_inversion',
        circuit: 29,
        notConsumable: [],
        itemInputs: ['1x shanhai:material_recombination_module', '4x shanhai:wem_1', '64x thetornproductionline:celestial_secret_deducing_module_ev', '1024x shanhai:electron_neutrino', '64x shanhai:wl_board_ev', '4x gtceu:ev_circuit_assembler'],
        inputFluids: ['shanhai:matter_fluid_advanced 256000'],
        itemOutputs: ['1x thetornproductionline:circult_process_module_1'],
        outputFluids: [],
        chancedOutputs: [],
        duration: 1200,
        EUt: 2048
    }
,
    // ▶ PF.txt 第 99 条（本次新增）｜元件「处理样板EV」｜纸：类型「物质锻造」／耗时「10s」｜输出 1x shanhai:eta_meson
    // 🧪 物质模块【不消耗·等级门槛·纸上有催化剂纸】：纸上（第 5 格）放着「物质模块是催化剂」，且类型 matter_forging ∈ SHANHAI_TYPES（= 山海自己的机器） ⇒ 落 ModuleLevelCondition（要求模块等级 ≥ 4，等级取自 MODULE_LEVELS）。⚠️ 门槛【不占输入槽】。若本条应当"只是纯催化剂（.notConsumable）"，改 SHANHAI_TYPES 即可，一处生效。
    {
        id: 'shanhai:pf/eta_meson',
        type: 'matter_forging',
        circuit: 5,
        notConsumable: [],
        moduleLevelRequirement: '1x shanhai:virtual_image_material_module',
        moduleLevelFallbackCatalyst: '1x shanhai:virtual_image_material_module',
        itemInputs: ['1x shanhai:up_quark', '1x shanhai:down_quark'],
        inputFluids: ['shanhai:zero_point_energy 32000'],
        itemOutputs: ['1x shanhai:eta_meson'],
        outputFluids: [],
        chancedOutputs: [],
        duration: 200,
        EUt: 2048
    }
,
    // ▶ PF.txt 第 100 条（本次新增）｜元件「处理样板EV」｜纸：类型「物质模块铸造」／耗时「60s」｜输出 1x shanhai:material_recombination_module
    // 🧪 物质模块【消耗·纸上无催化剂纸】：纸上（无）【没有】「物质模块是催化剂」；另：本条【产出】里有物质模块（= 属于"制作物质模块"），与"只看纸"的结论【一致】，不冲突 ⇒ 按 2026-10-03 判据（只看纸）模块留在 itemInputs 里被正常消耗，不挂催化剂、不设门槛。
    {
        id: 'shanhai:pf/material_recombination_module',
        type: 'matter_module_casting',
        circuit: 0,
        notConsumable: [],
        itemInputs: ['2x shanhai:wl_board_ev', '2x thetornproductionline:celestial_secret_deducing_module_ev', '1x shanhai:virtual_image_material_module', '1x shanhai:worldline_residual_fragment', '64x shanhai:neutron', '64x shanhai:proton', '8x shanhai:navigate_prism', '128x shanhai:first_light'],
        inputFluids: ['shanhai:matter_fluid_basic 8000', 'shanhai:matter_fluid_foundation 16000', 'shanhai:matter_fluid_advanced 2000', 'shanhai:matter_fluid_virtual 4000'],
        itemOutputs: ['1x shanhai:material_recombination_module'],
        outputFluids: [],
        chancedOutputs: [],
        duration: 1200,
        EUt: 2048
    }
,
    // ▶ PF.txt 第 101 条（本次新增）｜元件「处理样板EV」｜纸：类型「世线震荡收集」／耗时「60s」｜输出 1x shanhai:worldline_residual_fragment
    // 🧪 物质模块【消耗·纸上无催化剂纸】：纸上（无）【没有】「物质模块是催化剂」；另：本条类型 ∈ 世线族（历史读数，现不参与判定） ⇒ 按 2026-10-03 判据（只看纸）模块留在 itemInputs 里被正常消耗，不挂催化剂、不设门槛。
    {
        id: 'shanhai:pf/worldline_residual_fragment',
        type: 'worldline_oscillation_collection',
        circuit: 0,
        notConsumable: [],
        itemInputs: ['4x shanhai:wem_1', '4x shanhai:worldline_crystal_core', '16x thetornproductionline:celestial_secret_deducing_module_ev', '32x shanhai:pion', '32x shanhai:eta_meson', '1x shanhai:virtual_image_material_module', '1x shanhai:primordial_gravitational_interference_array', '1x gtceu:large_gas_collector', '1024x gtceu:ev_sensor', '1x gtceu:void_miner', '1024x gtceu:ev_emitter', '1x gtceu:large_greenhouse', '1x ae2:creative_energy_cell'],
        inputFluids: ['shanhai:zero_point_energy 666000', 'shanhai:matter_fluid_advanced 16000'],
        itemOutputs: ['1x shanhai:worldline_residual_fragment'],
        outputFluids: [],
        chancedOutputs: [],
        duration: 1200,
        EUt: 2048
    }
,
    // ▶ PF.txt 第 102 条（本次新增）｜元件「处理样板EV」｜纸：类型「原初因果编织」／耗时「3s」｜输出 1x gtlcore:world_fragments_nether
    // 🧪 物质模块【不消耗·等级门槛·纸上有催化剂纸】：纸上（第 6 格）放着「物质模块是催化剂」，且类型 primordial_causal_weaving ∈ SHANHAI_TYPES（= 山海自己的机器） ⇒ 落 ModuleLevelCondition（要求模块等级 ≥ 4，等级取自 MODULE_LEVELS）。⚠️ 门槛【不占输入槽】。若本条应当"只是纯催化剂（.notConsumable）"，改 SHANHAI_TYPES 即可，一处生效。
    {
        id: 'shanhai:pf/world_fragments_nether',
        type: 'primordial_causal_weaving',
        circuit: 24,
        notConsumable: [],
        moduleLevelRequirement: '1x shanhai:virtual_image_material_module',
        moduleLevelFallbackCatalyst: '1x shanhai:virtual_image_material_module',
        itemInputs: ['1x gtlcore:world_fragments_venus', '64x gtceu:netherrack_dust', '2x gtceu:data_stick'],
        inputFluids: ['gtceu:pcb_coolant 200', 'gtceu:nether_air 64000'],
        itemOutputs: ['1x gtlcore:world_fragments_nether'],
        outputFluids: [],
        chancedOutputs: [],
        duration: 60,
        EUt: 2048
    }
,
    // ▶ PF.txt 第 103 条（本次新增）｜元件「处理样板EV」｜纸：类型「物质锻造」／耗时「10s」｜输出 1x shanhai:neutron
    // 🧪 物质模块【不消耗·等级门槛·纸上有催化剂纸】：纸上（第 5 格）放着「物质模块是催化剂」，且类型 matter_forging ∈ SHANHAI_TYPES（= 山海自己的机器） ⇒ 落 ModuleLevelCondition（要求模块等级 ≥ 4，等级取自 MODULE_LEVELS）。⚠️ 门槛【不占输入槽】。若本条应当"只是纯催化剂（.notConsumable）"，改 SHANHAI_TYPES 即可，一处生效。
    {
        id: 'shanhai:pf/neutron',
        type: 'matter_forging',
        circuit: 3,
        notConsumable: [],
        moduleLevelRequirement: '1x shanhai:virtual_image_material_module',
        moduleLevelFallbackCatalyst: '1x shanhai:virtual_image_material_module',
        itemInputs: ['1x shanhai:up_quark', '2x shanhai:down_quark', '1x shanhai:gluon'],
        inputFluids: ['shanhai:zero_point_energy 16000'],
        itemOutputs: ['1x shanhai:neutron'],
        outputFluids: [],
        chancedOutputs: [],
        duration: 200,
        EUt: 2048
    }
,
    // ▶ PF.txt 第 104 条（本次新增）｜元件「处理样板EV」｜纸：类型「引力波宏观干涉」／耗时「60s」｜输出 1x shanhai:graviton
    // 🧪 物质模块【不消耗·等级门槛·纸上有催化剂纸】：纸上（第 8 格）放着「物质模块是催化剂」，且类型 gravitational_wave_production ∈ SHANHAI_TYPES（= 山海自己的机器） ⇒ 落 ModuleLevelCondition（要求模块等级 ≥ 4，等级取自 MODULE_LEVELS）。⚠️ 门槛【不占输入槽】。若本条应当"只是纯催化剂（.notConsumable）"，改 SHANHAI_TYPES 即可，一处生效。
    {
        id: 'shanhai:pf/graviton',
        type: 'gravitational_wave_production',
        circuit: 0,
        notConsumable: [],
        moduleLevelRequirement: '1x shanhai:virtual_image_material_module',
        moduleLevelFallbackCatalyst: '1x shanhai:virtual_image_material_module',
        itemInputs: ['4x shanhai:wem_1', '325x shanhai:gluon'],
        inputFluids: ['shanhai:light 7999000', 'shanhai:zero_point_energy 9999000'],
        itemOutputs: ['1x shanhai:graviton'],
        outputFluids: [],
        chancedOutputs: [],
        duration: 1200,
        EUt: 2048
    }
,
    // ▶ PF.txt 第 105 条（本次新增）｜元件「处理样板ULV」｜纸：类型「原初物质重组」／耗时「3s」｜输出 4x gtceu:bronze_ingot
    // 🧪 物质模块【不消耗·等级门槛·纸上有催化剂纸】：纸上（第 5 格）放着「物质模块是催化剂」，且类型 primordial_matter_recombination ∈ SHANHAI_TYPES（= 山海自己的机器） ⇒ 落 ModuleLevelCondition（要求模块等级 ≥ 1，等级取自 MODULE_LEVELS）。⚠️ 门槛【不占输入槽】。若本条应当"只是纯催化剂（.notConsumable）"，改 SHANHAI_TYPES 即可，一处生效。
    {
        id: 'shanhai:pf/bronze_ingot_pmr',
        type: 'primordial_matter_recombination',
        circuit: 31,
        notConsumable: [],
        moduleLevelRequirement: '1x shanhai:introductory_material_module',
        moduleLevelFallbackCatalyst: '1x shanhai:introductory_material_module',
        itemInputs: ['1x minecraft:copper_ingot', '1x gtceu:tin_ingot'],
        inputFluids: [],
        itemOutputs: ['4x gtceu:bronze_ingot'],
        outputFluids: [],
        chancedOutputs: [],
        duration: 60,
        EUt: 8
    }
,
    // ▶ PF.txt 第 106 条（本次新增）｜元件「处理样板ULV」｜纸：类型「原初物质重组」／耗时「3s」｜输出 3x gtceu:invar_ingot
    // 🧪 物质模块【不消耗·等级门槛·纸上有催化剂纸】：纸上（第 8 格）放着「物质模块是催化剂」，且类型 primordial_matter_recombination ∈ SHANHAI_TYPES（= 山海自己的机器） ⇒ 落 ModuleLevelCondition（要求模块等级 ≥ 1，等级取自 MODULE_LEVELS）。⚠️ 门槛【不占输入槽】。若本条应当"只是纯催化剂（.notConsumable）"，改 SHANHAI_TYPES 即可，一处生效。
    {
        id: 'shanhai:pf/invar_ingot_pmr',
        type: 'primordial_matter_recombination',
        circuit: 31,
        notConsumable: [],
        moduleLevelRequirement: '1x shanhai:introductory_material_module',
        moduleLevelFallbackCatalyst: '1x shanhai:introductory_material_module',
        itemInputs: ['1x minecraft:iron_ingot', '1x gtceu:nickel_ingot'],
        inputFluids: [],
        itemOutputs: ['3x gtceu:invar_ingot'],
        outputFluids: [],
        chancedOutputs: [],
        duration: 60,
        EUt: 8
    }
,
    // ▶ PF.txt 第 107 条（本次新增）｜元件「处理样板ULV」｜纸：类型「原初物质重组」／耗时「3s」｜输出 2x gtceu:steel_ingot
    // 🧪 物质模块【不消耗·等级门槛·纸上有催化剂纸】：纸上（第 5 格）放着「物质模块是催化剂」，且类型 primordial_matter_recombination ∈ SHANHAI_TYPES（= 山海自己的机器） ⇒ 落 ModuleLevelCondition（要求模块等级 ≥ 1，等级取自 MODULE_LEVELS）。⚠️ 门槛【不占输入槽】。若本条应当"只是纯催化剂（.notConsumable）"，改 SHANHAI_TYPES 即可，一处生效。
    {
        id: 'shanhai:pf/steel_ingot_pmr',
        type: 'primordial_matter_recombination',
        circuit: 31,
        notConsumable: [],
        moduleLevelRequirement: '1x shanhai:introductory_material_module',
        moduleLevelFallbackCatalyst: '1x shanhai:introductory_material_module',
        itemInputs: ['1x minecraft:iron_ingot', '1x minecraft:coal'],
        inputFluids: [],
        itemOutputs: ['2x gtceu:steel_ingot'],
        outputFluids: [],
        chancedOutputs: [],
        duration: 60,
        EUt: 8
    }
,
    // ▶ PF.txt 第 108 条（本次新增）｜元件「处理样板ULV」｜纸：类型「原初物质重组」／耗时「3s」｜输出 2x gtceu:red_alloy_ingot
    // 🧪 物质模块【不消耗·等级门槛·纸上有催化剂纸】：纸上（第 8 格）放着「物质模块是催化剂」，且类型 primordial_matter_recombination ∈ SHANHAI_TYPES（= 山海自己的机器） ⇒ 落 ModuleLevelCondition（要求模块等级 ≥ 1，等级取自 MODULE_LEVELS）。⚠️ 门槛【不占输入槽】。若本条应当"只是纯催化剂（.notConsumable）"，改 SHANHAI_TYPES 即可，一处生效。
    {
        id: 'shanhai:pf/red_alloy_ingot_pmr',
        type: 'primordial_matter_recombination',
        circuit: 31,
        notConsumable: [],
        moduleLevelRequirement: '1x shanhai:introductory_material_module',
        moduleLevelFallbackCatalyst: '1x shanhai:introductory_material_module',
        itemInputs: ['1x minecraft:copper_ingot', '2x minecraft:redstone'],
        inputFluids: [],
        itemOutputs: ['2x gtceu:red_alloy_ingot'],
        outputFluids: [],
        chancedOutputs: [],
        duration: 60,
        EUt: 8
    }
,
    // ▶ PF.txt 第 109 条（本次新增）｜元件「处理样板ULV」｜纸：类型「原初物质重组」／耗时「3s」｜输出 1x gtceu:annealed_copper_ingot
    // 🧪 物质模块【不消耗·等级门槛·纸上有催化剂纸】：纸上（第 5 格）放着「物质模块是催化剂」，且类型 primordial_matter_recombination ∈ SHANHAI_TYPES（= 山海自己的机器） ⇒ 落 ModuleLevelCondition（要求模块等级 ≥ 1，等级取自 MODULE_LEVELS）。⚠️ 门槛【不占输入槽】。若本条应当"只是纯催化剂（.notConsumable）"，改 SHANHAI_TYPES 即可，一处生效。
    {
        id: 'shanhai:pf/annealed_copper_ingot_pmr',
        type: 'primordial_matter_recombination',
        circuit: 31,
        notConsumable: [],
        moduleLevelRequirement: '1x shanhai:introductory_material_module',
        moduleLevelFallbackCatalyst: '1x shanhai:introductory_material_module',
        itemInputs: ['1x minecraft:copper_ingot'],
        inputFluids: [],
        itemOutputs: ['1x gtceu:annealed_copper_ingot'],
        outputFluids: [],
        chancedOutputs: [],
        duration: 60,
        EUt: 8
    }
,
    // ▶ PF.txt 第 110 条（本次新增）｜元件「处理样板ULV」｜纸：类型「原初物质重组」／耗时「3s」｜输出 4x gtceu:tin_alloy_ingot
    // 🧪 物质模块【不消耗·等级门槛·纸上有催化剂纸】：纸上（第 8 格）放着「物质模块是催化剂」，且类型 primordial_matter_recombination ∈ SHANHAI_TYPES（= 山海自己的机器） ⇒ 落 ModuleLevelCondition（要求模块等级 ≥ 1，等级取自 MODULE_LEVELS）。⚠️ 门槛【不占输入槽】。若本条应当"只是纯催化剂（.notConsumable）"，改 SHANHAI_TYPES 即可，一处生效。
    {
        id: 'shanhai:pf/tin_alloy_ingot_pmr',
        type: 'primordial_matter_recombination',
        circuit: 31,
        notConsumable: [],
        moduleLevelRequirement: '1x shanhai:introductory_material_module',
        moduleLevelFallbackCatalyst: '1x shanhai:introductory_material_module',
        itemInputs: ['1x minecraft:iron_ingot', '1x gtceu:tin_ingot'],
        inputFluids: [],
        itemOutputs: ['4x gtceu:tin_alloy_ingot'],
        outputFluids: [],
        chancedOutputs: [],
        duration: 60,
        EUt: 8
    }
,
    // ▶ PF.txt 第 111 条（本次新增）｜元件「处理样板ULV」｜纸：类型「原初物质重组」／耗时「3s」｜输出 1x gtceu:pulsating_alloy_ingot
    // 🧪 物质模块【不消耗·等级门槛·纸上有催化剂纸】：纸上（第 8 格）放着「物质模块是催化剂」，且类型 primordial_matter_recombination ∈ SHANHAI_TYPES（= 山海自己的机器） ⇒ 落 ModuleLevelCondition（要求模块等级 ≥ 1，等级取自 MODULE_LEVELS）。⚠️ 门槛【不占输入槽】。若本条应当"只是纯催化剂（.notConsumable）"，改 SHANHAI_TYPES 即可，一处生效。
    {
        id: 'shanhai:pf/pulsating_alloy_ingot_pmr',
        type: 'primordial_matter_recombination',
        circuit: 31,
        notConsumable: [],
        moduleLevelRequirement: '1x shanhai:introductory_material_module',
        moduleLevelFallbackCatalyst: '1x shanhai:introductory_material_module',
        itemInputs: ['1x minecraft:iron_ingot', '1x minecraft:gunpowder'],
        inputFluids: [],
        itemOutputs: ['1x gtceu:pulsating_alloy_ingot'],
        outputFluids: [],
        chancedOutputs: [],
        duration: 60,
        EUt: 8
    }
,
    // ▶ PF.txt 第 112 条（本次新增）｜元件「处理样板ULV」｜纸：类型「原初物质重组」／耗时「3s」｜输出 2x gtceu:cupronickel_ingot
    // 🧪 物质模块【不消耗·等级门槛·纸上有催化剂纸】：纸上（第 8 格）放着「物质模块是催化剂」，且类型 primordial_matter_recombination ∈ SHANHAI_TYPES（= 山海自己的机器） ⇒ 落 ModuleLevelCondition（要求模块等级 ≥ 1，等级取自 MODULE_LEVELS）。⚠️ 门槛【不占输入槽】。若本条应当"只是纯催化剂（.notConsumable）"，改 SHANHAI_TYPES 即可，一处生效。
    {
        id: 'shanhai:pf/cupronickel_ingot_pmr',
        type: 'primordial_matter_recombination',
        circuit: 31,
        notConsumable: [],
        moduleLevelRequirement: '1x shanhai:introductory_material_module',
        moduleLevelFallbackCatalyst: '1x shanhai:introductory_material_module',
        itemInputs: ['1x minecraft:copper_ingot', '1x gtceu:nickel_ingot'],
        inputFluids: [],
        itemOutputs: ['2x gtceu:cupronickel_ingot'],
        outputFluids: [],
        chancedOutputs: [],
        duration: 60,
        EUt: 8
    }
,
    // ▶ PF.txt 第 113 条（本次新增）｜元件「处理样板ULV」｜纸：类型「原初物质重组」／耗时「3s」｜输出 1x gtceu:glass_tube
    // 🧪 物质模块【不消耗·等级门槛·纸上有催化剂纸】：纸上（第 8 格）放着「物质模块是催化剂」，且类型 primordial_matter_recombination ∈ SHANHAI_TYPES（= 山海自己的机器） ⇒ 落 ModuleLevelCondition（要求模块等级 ≥ 1，等级取自 MODULE_LEVELS）。⚠️ 门槛【不占输入槽】。若本条应当"只是纯催化剂（.notConsumable）"，改 SHANHAI_TYPES 即可，一处生效。
    {
        id: 'shanhai:pf/glass_tube_pmr',
        type: 'primordial_matter_recombination',
        circuit: 31,
        notConsumable: [],
        moduleLevelRequirement: '1x shanhai:introductory_material_module',
        moduleLevelFallbackCatalyst: '1x shanhai:introductory_material_module',
        itemInputs: ['1x minecraft:glass'],
        inputFluids: [],
        itemOutputs: ['1x gtceu:glass_tube'],
        outputFluids: [],
        chancedOutputs: [],
        duration: 60,
        EUt: 8
    }
,
    // ▶ PF.txt 第 114 条（本次新增）｜元件「处理样板ULV」｜纸：类型「原初物质重组」／耗时「3s」｜输出 2x minecraft:paper
    // 🧪 物质模块【不消耗·等级门槛·纸上有催化剂纸】：纸上（第 8 格）放着「物质模块是催化剂」，且类型 primordial_matter_recombination ∈ SHANHAI_TYPES（= 山海自己的机器） ⇒ 落 ModuleLevelCondition（要求模块等级 ≥ 1，等级取自 MODULE_LEVELS）。⚠️ 门槛【不占输入槽】。若本条应当"只是纯催化剂（.notConsumable）"，改 SHANHAI_TYPES 即可，一处生效。
    {
        id: 'shanhai:pf/paper_pmr',
        type: 'primordial_matter_recombination',
        circuit: 31,
        notConsumable: [],
        moduleLevelRequirement: '1x shanhai:introductory_material_module',
        moduleLevelFallbackCatalyst: '1x shanhai:introductory_material_module',
        itemInputs: ['1x minecraft:sugar_cane'],
        inputFluids: [],
        itemOutputs: ['2x minecraft:paper'],
        outputFluids: [],
        chancedOutputs: [],
        duration: 60,
        EUt: 8
    }
,
    // ▶ PF.txt 第 115 条（本次新增）｜元件「处理样板ULV」｜纸：类型「原初物质重组」／耗时「3s」｜输出 4x gtceu:brass_ingot
    // 🧪 物质模块【不消耗·等级门槛·纸上有催化剂纸】：纸上（第 8 格）放着「物质模块是催化剂」，且类型 primordial_matter_recombination ∈ SHANHAI_TYPES（= 山海自己的机器） ⇒ 落 ModuleLevelCondition（要求模块等级 ≥ 1，等级取自 MODULE_LEVELS）。⚠️ 门槛【不占输入槽】。若本条应当"只是纯催化剂（.notConsumable）"，改 SHANHAI_TYPES 即可，一处生效。
    {
        id: 'shanhai:pf/brass_ingot_pmr',
        type: 'primordial_matter_recombination',
        circuit: 31,
        notConsumable: [],
        moduleLevelRequirement: '1x shanhai:introductory_material_module',
        moduleLevelFallbackCatalyst: '1x shanhai:introductory_material_module',
        itemInputs: ['1x gtceu:zinc_ingot', '1x minecraft:copper_ingot'],
        inputFluids: [],
        itemOutputs: ['4x gtceu:brass_ingot'],
        outputFluids: [],
        chancedOutputs: [],
        duration: 60,
        EUt: 8
    }
,
    // ▶ PF.txt 第 116 条（本次新增）｜元件「处理样板ULV」｜纸：类型「原初物质重组」／耗时「3s」｜输出 1x gtceu:conductive_alloy_ingot
    // 🧪 物质模块【不消耗·等级门槛·纸上有催化剂纸】：纸上（第 8 格）放着「物质模块是催化剂」，且类型 primordial_matter_recombination ∈ SHANHAI_TYPES（= 山海自己的机器） ⇒ 落 ModuleLevelCondition（要求模块等级 ≥ 1，等级取自 MODULE_LEVELS）。⚠️ 门槛【不占输入槽】。若本条应当"只是纯催化剂（.notConsumable）"，改 SHANHAI_TYPES 即可，一处生效。
    {
        id: 'shanhai:pf/conductive_alloy_ingot_pmr',
        type: 'primordial_matter_recombination',
        circuit: 31,
        notConsumable: [],
        moduleLevelRequirement: '1x shanhai:introductory_material_module',
        moduleLevelFallbackCatalyst: '1x shanhai:introductory_material_module',
        itemInputs: ['1x gtceu:pulsating_alloy_ingot', '1x minecraft:redstone'],
        inputFluids: [],
        itemOutputs: ['1x gtceu:conductive_alloy_ingot'],
        outputFluids: [],
        chancedOutputs: [],
        duration: 60,
        EUt: 8
    }
,
    // ▶ PF.txt 第 117 条（本次新增）｜元件「处理样板ULV」｜纸：类型「原初物质重组」／耗时「3s」｜输出 2x gtceu:wrought_iron_ingot
    // 🧪 物质模块【不消耗·等级门槛·纸上有催化剂纸】：纸上（第 8 格）放着「物质模块是催化剂」，且类型 primordial_matter_recombination ∈ SHANHAI_TYPES（= 山海自己的机器） ⇒ 落 ModuleLevelCondition（要求模块等级 ≥ 1，等级取自 MODULE_LEVELS）。⚠️ 门槛【不占输入槽】。若本条应当"只是纯催化剂（.notConsumable）"，改 SHANHAI_TYPES 即可，一处生效。
    {
        id: 'shanhai:pf/wrought_iron_ingot_pmr',
        type: 'primordial_matter_recombination',
        circuit: 30,
        notConsumable: [],
        moduleLevelRequirement: '1x shanhai:introductory_material_module',
        moduleLevelFallbackCatalyst: '1x shanhai:introductory_material_module',
        itemInputs: ['1x minecraft:iron_ingot'],
        inputFluids: [],
        itemOutputs: ['2x gtceu:wrought_iron_ingot'],
        outputFluids: [],
        chancedOutputs: [],
        duration: 60,
        EUt: 8
    }
,
    // ▶ PF.txt 第 118 条（本次新增）｜元件「处理样板ULV」｜纸：类型「原初物质重组」／耗时「3s」｜输出 1x gtceu:compressed_fireclay
    // 🧪 物质模块【不消耗·等级门槛·纸上有催化剂纸】：纸上（第 8 格）放着「物质模块是催化剂」，且类型 primordial_matter_recombination ∈ SHANHAI_TYPES（= 山海自己的机器） ⇒ 落 ModuleLevelCondition（要求模块等级 ≥ 1，等级取自 MODULE_LEVELS）。⚠️ 门槛【不占输入槽】。若本条应当"只是纯催化剂（.notConsumable）"，改 SHANHAI_TYPES 即可，一处生效。
    {
        id: 'shanhai:pf/compressed_fireclay_pmr',
        type: 'primordial_matter_recombination',
        circuit: 31,
        notConsumable: [],
        moduleLevelRequirement: '1x shanhai:introductory_material_module',
        moduleLevelFallbackCatalyst: '1x shanhai:introductory_material_module',
        itemInputs: ['1x minecraft:clay_ball', '1x minecraft:brick'],
        inputFluids: [],
        itemOutputs: ['1x gtceu:compressed_fireclay'],
        outputFluids: [],
        chancedOutputs: [],
        duration: 60,
        EUt: 8
    }
]

// -----------------------------------------------------------------------------
// 注册：工作台配方（老 ②③④⑤⑥ = **历史值 2026-09-26 的 5 条**：它们建文件时就在，不随 PF.txt 变化，故无法现算）
// -----------------------------------------------------------------------------
// -----------------------------------------------------------------------------
// -----------------------------------------------------------------------------
// 🔴 运行期探针：把【真实 id】打出来 —— 不再猜
//   教训：上一版用 recipeManager.byKey(id) 反查，报 present=false，而用户 JEI 里【配方还在】，
//   两者不可能同时对 ⇒ 【byKey 查不到 GTCEu 的配方】⇒ 那条判据【不可靠，已废】。
//   本版改成【遍历配方表】getRecipes().toArray()，按 getId() 里的关键词找 ⇒ 自洽、不依赖索引。
//   ⚠️ 刻意【不用 getResultItem()】—— 字节码实证它恒返回 ItemStack.EMPTY。
//   判读：water_lava=[none] ⇒ 那类配方确实不在表里；打出具体 id ⇒ 那就是【真实 id】。
//   同时报 totalRecipes 做 sanity：若为 0 ⇒ 是遍历入口不对，不是配方不在。
// -----------------------------------------------------------------------------
ServerEvents.loaded(function (event) {
    // 🔴 迟到删除：ServerEvents.recipes 期间删不掉（实测：那时 mod 的配方还没进表），
    //    改到 loaded 时直接改配方表。判据 = 紧随其后的探针（同一行日志体系）。
    // 🔴 用【子串】而不是精确 id —— 实测同一个"水+岩浆→黑曜石+蒸汽"存在【两条】老配方：
    //      cxhmz:chemical_reactor/water_lava_to_steam 与 cxhmz:large_chemical_reactor/water_lava_to_steam
    //    （探针实测：删掉第一条后第二条约仍在 ⇒ 这就是用户说"配方还在"的原因）
    var KEY = ['water_lava_to_steam', 'fire_charge_ch']
    // ⚠️ 绝不删自己新写的（shanhai: 前缀）
    var dropped = 0
    var keep = []
    var lerr = ''
    try {
        var all0 = event.server.recipeManager.getRecipes().toArray()
        for (var k0 = 0; k0 < all0.length; k0++) {
            var r0 = '?'
            try { r0 = String(all0[k0].getId()) } catch (e0) { r0 = '?' }
            var hit = false
            for (var k1 = 0; k1 < KEY.length; k1++) if (r0.indexOf(KEY[k1]) >= 0) hit = true
            if (r0.indexOf('shanhai:') === 0) hit = false
            if (hit) { dropped = dropped + 1; continue }
            keep.push(all0[k0])
        }
        if (dropped > 0) event.server.recipeManager.replaceRecipes(keep)
    } catch (e1) { lerr = ' (' + e1 + ')' }
    console.info(SHANHAI_PF_TAG + ' remove-late dropped=' + dropped + ' kept=' + keep.length + lerr)

    var arr = []
    var why = ''
    try { arr = event.server.recipeManager.getRecipes().toArray() } catch (e) { why = ' (' + e + ')' }
    var total = arr.length
    var wl = 'none'
    var fc = 'none'
    var ncx = 0
    var cxList = ''
    for (var i = 0; i < total; i++) {
        var rid = '?'
        try { rid = String(arr[i].getId()) } catch (e2) { rid = '?' }
        if (rid.indexOf('water_lava') >= 0) wl = rid
        if (rid.indexOf('fire_charge') >= 0) { if (fc === 'none') fc = rid; else if (fc.indexOf(rid) < 0) fc = fc + '|' + rid }
        if (rid.indexOf('cxhmz') === 0 || rid.indexOf('cxbp') === 0) { ncx = ncx + 1; if (cxList.length < 200) cxList = cxList + ' ' + rid }
    }
    console.info(SHANHAI_PF_TAG + ' probe totalRecipes=' + total + ' water_lava=[' + wl + '] fire_charge=[' + fc + '] cx-ns-count=' + ncx + why)
    if (ncx > 0) console.info(SHANHAI_PF_TAG + ' probe cx-ids' + cxList)
})


ServerEvents.recipes(function (event) {
    var ok = 0
    // 🔴 2026-09-27 接进聊天栏横幅（scope=shanhai_pf）
    //    ⚠️ 变量名必须【不叫 Stats】—— Rhino 里 `Stats` 会回落到原版 net.minecraft.stats.Stats，
    //       实测报错：Java class "net.minecraft.stats.Stats" has no … "reportSummary"。
    //    ⚠️ 声明必须在【每个 ServerEvents.recipes 回调内部各来一次】—— var 是函数作用域，跨回调不共享。
    var ShanhaiStats = null
    try { ShanhaiStats = Java.loadClass('com.shanhai.common.recipe.ShanhaiRecipeStats') } catch (eS) { ShanhaiStats = null }
    if (ShanhaiStats) ShanhaiStats.reset()   // 本批自己清一次（Java 侧是全局静态累加器）
    var bad = 0
    var errList = ''
    var i

    for (i = 0; i < shanhaiPfShaped.length; i++) {
        var r = shanhaiPfShaped[i]
        try {
            event.shaped(r.out, r.pattern, r.keys).id(r.id)
            ok = ok + 1
            if (ShanhaiStats) ShanhaiStats.addResult(true)
        } catch (e) {
            bad = bad + 1
            if (ShanhaiStats) ShanhaiStats.addResult(false)
            if (errList.length < 1200) { errList = errList + r.id + ' => ' + e + ' | ' }
        }
    }

    console.info(SHANHAI_PF_TAG + ' crafting(shaped) ok=' + ok + ' failed=' + bad
        + ' total=' + shanhaiPfShaped.length)
    if (bad > 0) {
        console.error(SHANHAI_PF_TAG + ' crafting FAILED list: ' + errList)
    }
})

// -----------------------------------------------------------------------------
// 注册：GT 机器配方（老 3 条 + 新增 92 条 = 95 条）
// -----------------------------------------------------------------------------
// -----------------------------------------------------------------------------
// 🔴 移除【被移植替代的老配方】
//   用户 2026-09-26 逐字原话：「我在PF.txt中纸写了，移植的配方是原本产线撕裂或者dgy的配方，
//     由于要结合山海，所以我更新了其中的一些配方，但是老配方还在文件里面，因此需要删除」
//   他写在样板里的纸面原文：「注意，此配方为产线撕裂/dgy移植，添加此配方之后需要移除原本的配方」
//   ① minecraft:obsidian —— gtceu:chemical_reactor：水 2147483647mB + 岩浆 1024000mB ⇒ 蒸汽 + 黑曜石×1024
//   ② minecraft:fire_charge —— gtceu:large_chemical_reactor：火药 + 碳粉 + 烈焰粉 ⇒ 火焰弹×3
//   ⚠️ 用户明确【不删】：gtceu:mixer 产出 fire_charge、以及原版合成台那两条。
//   🔴🔴 2026-09-27 探针实证（遍历 55,947 条配方表得到的真实 id）：
//      真实格式 = <命名空间>:<配方类型路径>/<路径>  —— 不是 <ns>:<路径>！
//        真 id = cxhmz:chemical_reactor/water_lava_to_steam
//        真 id = cxbp:large_chemical_reactor/fire_charge_ch
//      我从导出路径猜的 cxhmz:water_lava_to_steam 【少了一整段类型路径】⇒ 永远不匹配 ✗
//      ⚠️ 正则 { id: /...$/ } 本轮实测【也没生效】⇒ 只能用【精确 id】。
//      ⚠️ 探针同时确认【不该删的两条在表里】：gtceu:mixer/fire_charge 与 minecraft:fire_charge（原版合成台）。
//   🔴 2026-09-27 字节码实证：GTRecipe.getResultItem() 恒返回 ItemStack.EMPTY（javap -c 只有 getstatic ItemStack.f_41583_; areturn），
//      而 KubeJS 的 OutputFilter.test() 只有一句 RecipeKJS.hasOutput(match) ⇒ 【{output:...} 对 GT 配方永远不可能匹配】！
//      ⇒ 所以第一版的 {type,output} 谓词【注定无效】（这也是"删不掉"的机械根因）。
//      ⇒ 正确写法是【按 id 删】：GTRecipe implements 原版 Recipe<Container>（javap 类声明），有 id 字段与 getId()。
//      ⇒ 这里用【正则 id】而不是硬猜命名空间（导出路径是 added_recipes/cxhmz/chemical_reactor/water_lava_to_steam.json，
//        命名空间那一段我无法从路径 100% 反推）。
//      🔴 2026-09-30 【事实更正】老配方的来源**不是 mod jar**（旧注释说"来自 mod jar（ns=cxhmz/cxbp）"，那是错的）。
//         现查（在已部署实例的 kubejs\server_scripts 里逐个文件 grep）真源是【宿主的 KJS 脚本】：
//           · `cxhmz:water_lava_to_steam`  ⇐ [server_scripts]dgy.js:2604
//              `event.recipes.gtceu.chemical_reactor('cxhmz:water_lava_to_steam')`
//           · `cxbp:fire_charge_ch`        ⇐ [server_scripts]产线爆破.js:471
//              `grtr.large_chemical_reactor("cxbp:fire_charge_ch")`
//         ⇒ 运行期 id = `<ns>:<类型路径>/<路径>`（dgy.js 里写的 `cxhmz:water_lava_to_steam`
//           在表里会变成 `cxhmz:chemical_reactor/water_lava_to_steam`）。
//         ⚠️ 这是 KJS 脚本写的配方，但**不影响 event.remove**：我们删的是【配方表里的条目】，
//            不分来源；而且本删除跑在 ServerEvents.loaded ⇒ 一定在全部 ServerEvents.recipes
//            （KJS 注册配方的唯一时机）之后 ⇒ 不会被"后加的脚本又加回来"。
//   ✅ 幂等：重复执行时返回值变 0，不报错。
// -----------------------------------------------------------------------------
ServerEvents.recipes(function (event) {
    // 🔴 2026-09-27 收尾：这里原本有 4 条 event.remove —— 【已删除】，因为实测【全部无效】。
    //    ① {type,output} 两条：字节码证明 GTRecipe.getResultItem() 恒返回 ItemStack.EMPTY，
    //       而 KubeJS 的 OutputFilter.test() 只有一句 RecipeKJS.hasOutput(match)
    //       ⇒ 【{output:...} 对 GT 配方永远不可能匹配】。
    //    ② {id} 精确 / {id:/正则/} 两条：时机太早 —— ServerEvents.recipes 期间 mod 的配方
    //       还没进配方表（实测：此刻移除后，18 秒后的探针仍能看到它）。
    //    ✅ 真正生效的删除已挪到本文件末尾的 ServerEvents.loaded 里（直接改配方表）。
    //    ✅ 那处的判据是自洽的：remove-late 与紧随其后的 probe 用同一套遍历。
    console.info(SHANHAI_PF_TAG + ' remove-old 已停用（本块 4 条实测无效，见下方 ServerEvents.loaded）')
})


ServerEvents.recipes(function (event) {
    var gtr = event.recipes.gtceu
    var ok = 0
    // 🔴 同上：本回调内【重新声明】一次（var 不跨回调共享）
    var ShanhaiStats = null
    try { ShanhaiStats = Java.loadClass('com.shanhai.common.recipe.ShanhaiRecipeStats') } catch (eS) { ShanhaiStats = null }
    var bad = 0
    var errList = ''
    var i
    var j

    for (i = 0; i < shanhaiPfGt.length; i++) {
        var r = shanhaiPfGt[i]
        try {
            // ⚠️ 类型 id 直接当方法名用：gtr['photon_siphon'](...) —— 等价于 gtr.photon_siphon(...)
            var b = gtr[r.type](r.id)

            var useLevelGate = shanhaiUseLevelGate(r)

            // 🔴 顺序照宿主脚本：先 .notConsumable(...)，再 .circuit(...)
            //    先例：gtceu.js:3302-3303 / gtceu.js:9955-9957 / gtceu.js:935
            for (j = 0; j < r.notConsumable.length; j++) {
                b = b.notConsumable(r.notConsumable[j])
            }
            // 🔴 物质模块：门槛可用 ⇒ 不写催化剂；否则退回催化剂形态（配方不会消失）
            if (!useLevelGate && r.moduleLevelFallbackCatalyst) {
                b = b.notConsumable(r.moduleLevelFallbackCatalyst)
            }

            // 🔴 编程电路：.circuit(数字)，参数必须是数字（Rhino 下传字符串会报错）
            if (r.circuit > 0) {
                b = b.circuit(r.circuit)
            }

            // 🔴 物质模块等级门槛（= 老山海 module_level 配方条件）
            //    写法照宿主现成先例：gtceu.js:8489 .addCondition(new GravityCondition(true))
            if (useLevelGate) {
                var mlp = shanhaiParseModuleLevel(r.moduleLevelRequirement)
                b = b.addCondition(new ModuleLevelCondition(mlp[0], mlp[1]))
            }

            for (j = 0; j < r.itemInputs.length; j++) {
                b = b.itemInputs(r.itemInputs[j])
            }
            for (j = 0; j < r.inputFluids.length; j++) {
                b = b.inputFluids(r.inputFluids[j])
            }
            for (j = 0; j < r.itemOutputs.length; j++) {
                b = b.itemOutputs(r.itemOutputs[j])
            }
            for (j = 0; j < r.outputFluids.length; j++) {
                b = b.outputFluids(r.outputFluids[j])
            }
//            // 🔴 概率产出（本次新增能力）：#24（1 条）的「电子中微子产出概率5%」
            // ⚠️ 第二个 int 是【每超频一级的加成量 tierChanceBoost】，不是"上限"：
            //    字节码实证 GTRecipeBuilder.chancedOutput(ItemStack,int,int)：
            //      67: aload_0 / 68: iload_2 / 69: putfield chance:I
            //      72: aload_0 / 73: iload_3 / 74: putfield tierChanceBoost:I   <-- 第三个参数
            if (r.chancedOutputs) {
                for (j = 0; j < r.chancedOutputs.length; j++) {
                    var co = r.chancedOutputs[j]
                    b = b.chancedOutput(co.item, co.chance, co.tierChanceBoost)
                }
            }

            b.duration(r.duration).EUt(r.EUt)
            ok = ok + 1
            if (ShanhaiStats) ShanhaiStats.addResult(true)
        } catch (e) {
            bad = bad + 1
            if (ShanhaiStats) ShanhaiStats.addResult(false)
            if (errList.length < 1200) {
                errList = errList + r.id + ' [' + r.type + '] => ' + e + ' | '
            }
        }
    }

    console.info(SHANHAI_PF_TAG + ' gt_machine ok=' + ok + ' failed=' + bad
        + ' total=' + shanhaiPfGt.length)
    console.info(SHANHAI_PF_TAG + ' module-mode=' + SHANHAI_PF_MODULE_MODE
        + ' module-level-condition available=' + SHANHAI_HAS_MODULE_LEVEL_CONDITION)
    if (bad > 0) {

        console.error(SHANHAI_PF_TAG + ' gt_machine FAILED list: ' + errList)
    }
    // 🔴 打机器可判的那一行：本批 PF 配方的 total/success/failed
    var hasShanhaiStats = ShanhaiStats
    if (hasShanhaiStats) {
        try { ShanhaiStats.reportSummary('shanhai_pf') } catch (eR) { console.error(SHANHAI_PF_TAG + ' reportSummary FAILED: ' + eR) }
    } else {
        console.error(SHANHAI_PF_TAG + ' ShanhaiRecipeStats 类不可用 ⇒ 本批不上报 ')
    }

    // 逐条回执：证明 builder 收下了什么（方便和 PF.txt 对账）
    for (i = 0; i < shanhaiPfGt.length; i++) {
        var s = shanhaiPfGt[i]
        var gateOn = shanhaiUseLevelGate(s)
        console.info(SHANHAI_PF_TAG + ' spec id=' + s.id
            + ' type=' + s.type
            + ' circuit=' + s.circuit
            + ' in=' + s.itemInputs.length + 'item'
            + ' nc=' + s.notConsumable.length + 'cat'
            + ' slots=' + (s.itemInputs.length + s.notConsumable.length + (s.circuit > 0 ? 1 : 0)
                + (!gateOn && s.moduleLevelFallbackCatalyst ? 1 : 0))
            + ' gate=' + (gateOn ? s.moduleLevelRequirement : '-')
            + ' fin=' + s.inputFluids.length
            + ' out=' + s.itemOutputs.length + 'item'
            + ' fout=' + s.outputFluids.length
            + ' chanced=' + (s.chancedOutputs ? s.chancedOutputs.length : 0)
            + ' EUt=' + s.EUt
            + ' duration=' + s.duration + 't')
    }

    // 🔴 2026-09-26：槽位溢出自检【已移除】—— photon_separation 放宽到 (4, 10, 2, 2) 之后
    //    没有任何配方超出上限（详见文件头 §4）。原先这里会扫 shanhaiPfGt[i].slotOver
    //    并打 [SHANHAI-PF] SLOT-OVER 告警；那个字段与整段告警代码一并删掉了。
    //    ⚠️ 若将来又出现"配方要的槽位 > 该类型 setMaxIOSize"的情形，需要【重新引入】这类检查，
    //       不要以为本文件还带着它。
})

// ═══ 手写区 开始（生成器不会动这一段）═══
// ─── 手写区内容来源（2026-09-27 用户点单第 4 条）─────────────────────────
//   1) 原 shanhai_zero_point_power.js 全文（原始真空零点能发生器的发电配方）—— 已并进本文件手写区
//   2) 以后你直接加在这里的任意 KJS 代码 —— 重跑生成器不会被覆盖

// =============================================================================
// shanhai_zero_point_power.js  --  原始真空零点能发生器的【发电配方】
//
// 用户交办原话（逐字，2026-09-24）：
//   「给原始真空零点能发生器添加一个发电配方，每输入 1000mb 真空零点能
//     可以输出 max 1a×1s 的电量」
//
// 🔴 本文件是【独立新增】，不修改任何既有脚本（尤其没动 shanhai_test_recipes.js）。
// =============================================================================
// §1 「原始真空零点能发生器」到底是哪个 id —— 认定过程（全部是读取，不是猜）
// =============================================================================
//   ① 中文名反查：shanhai-rewrite\src\main\resources\assets\shanhai\lang\zh_cn.json:28
//        "block.shanhai.primordial_void_induction_armature": "原始真空零点能发生器"
//      ⇒ 逐字命中，就是它。
//      ⚠️ 排除项：lv/mv/ulv_zero_point_conversion 的中文名是「LV/MV/ULV零点转换器」
//         （zh_cn.json:201-203），【不是】「原始真空零点能发生器」⇒ 与本次交办无关。
//   ② 它在不在我们注册的 24 台模块里：
//        ModuleRegistry.java:641-648
//          new ModuleSpec("PRIMORDIAL_VOID_INDUCTION_ARMATURE",
//                         "primordial_void_induction_armature", ..., 
//                         () -> RECIPE_VOID_INDUCTION_ARMATURE, false)
//      ⇒ 在。工厂是 StandardPrimordialModule，结构图案与其余 23 台同源。
//   ③ 🔴 它挂的配方类型（决定本脚本用哪个 gtr.<type>）：
//        ModuleRegistry.java:341-344
//          /** 原始真空零点能发生器 —— 上游 :277（全 24 台里最短的一张，只有 1 条）。 */
//          public static final GTRecipeType[] RECIPE_VOID_INDUCTION_ARMATURE = {
//                  ShanhaiRecipeTypes.PRIMORDIAL_POWER_GENERATOR,
//          };
//      ⇒ 唯一一个类型 = primordial_power_generator（原初发电协议）。
//      ⇒ 与本任务书给的候选一致，且【只有一个】，没有任何歧义。
//   ④ 该类型的槽位 / EUIO（ShanhaiRecipeTypes.java:217-225）：
//          GTRecipeTypes.register("primordial_power_generator", "multiblock")
//              .setMaxIOSize(2, 2, 2, 2)     // 物品入2 物品出2 流体入2 流体出2
//              .setEUIO(IO.OUT)              // 🔴 发电型 ⇒ 产出 EU
//      ⇒ 本配方只用 1 个流体输入槽，远在 2 个槽位之内。
// =============================================================================
// §2 输入流体 id —— 真空零点能
// =============================================================================
//   ShanhaiFluids.java:162-164
//     /** 流体 `zero_point_energy`（真空零点能）· ... 桶 `shanhai:zero_point_energy_bucket` */
//     public static final FluidEntry<ForgeFlowingFluid.Flowing> ZERO_POINT_ENERGY =
//             fluid("zero_point_energy", "zero_point_energy", "zero_point_energy_flow", "真空零点能");
//   zh_cn.json:308-310  "fluid.shanhai.zero_point_energy": "真空零点能"（fluid / block / fluid_type 三条）
//   ⇒ 流体的注册 id = **shanhai:zero_point_energy**，中文名逐字 = 真空零点能。
// =============================================================================
// §3 MAX 档电压 = 2147483648 = 2^31 —— 查证过程（不许猜，逐条给证据）
// =============================================================================
//   ① MAX 是第几档：
//        javap -p -v -cp gtceu-1.20.1-1.4.4.jar com.gregtechceu.gtceu.api.GTValues
//          → 按 ConstantValue 属性逐个读出：
//            ULV=0 LV=1 MV=2 HV=3 EV=4 IV=5 LuV=6 ZPM=7 UV=8 UHV=9 UEV=10 UIV=11 UXV=12 OpV=13
//            **MAX=14**   MAX_TRUE=30
//   ② 第 14 档的电压（同一次 javap 的 <clinit> 逐指令）：
//            V 是 long[15]（bipush 15 / newarray long），下标 0..14 依次 lastore：
//              0:8  1:32  2:128  3:512  4:2048  5:8192  6:32768  7:131072  8:524288
//              9:2097152  10:8388608  11:33554432  12:134217728  13:536870912
//              **14: 2147483648l（ldc2_w #270）**   ← = 2^31，逐指令原文
//        ⇒ **GTValues.V[GTValues.MAX] = 2147483648**（1 安培）。这就是「MAX 1A」的电压。
//   ③ 🔴 「输出」⇒ EUt 必须写【负数】（发电型）：
//        GTRecipeBuilder.EUt(long) 的字节码（com.gregtechceu.gtceu.data.recipe.builder.GTRecipeBuilder）：
//            lload_1 / lconst_0 / lcmp / ifle  →  if (eu > 0) inputEU(eu)
//            ...                              →  else if (eu < 0) outputEU(**lneg** eu)
//        🔴 注意那条 **lneg**（long 取负）：-(-2147483648L) = 2147483648L，
//           **在 long 里完全合法、不会溢出**（int 才会溢出）。
//           ⇒ 2147483648 是"1A 输出"能取的**最大值上界**，正好卡在安全线上。
//           宿主先例：gtceu.js:14215-14219 的 plasma_generator 就是 .EUt(-GTValues.V[GTValues.EV])。
//   ④ 「1s」= **20 tick**（GTCEu 的 duration 单位是 tick，20 tick = 1 秒；
//        见宿主 gtceu.js 里随处可见的 .duration(20)）。
//        ⇒ EUt = -2147483648，duration = 20
//          ⇒ 一个配方周期共输出 2147483648 × 20 = 42949672960 EU，
//            即"1000mB 真空零点能 ⇒ MAX 档 1 安培持续 1 秒的电量"。
//   ⚠️ 本脚本【不】直接写 GTValues.V[GTValues.MAX] 取数（虽然宿主能用它），
//      而是在下面 **运行时把字面量与 GTValues 交叉核对一遍**：两边不一致就在日志里响亮报错。
//      理由：本工程的红线是「检查器先证明自己对」——让日志本身证明这个常数没抄错。
// =============================================================================
// §4 KubeJS / Rhino 写法纪律（照 shanhai_test_recipes.js 的保守写法）
// =============================================================================
//   · 全局一律 var；不用 let/const / 模板串 / ?. / 解构 / 箭头函数
//   · 配方写在 ServerEvents.recipes 回调内的 myRecipes 数组里
//   · 只有 1 条配方也照样包 try/catch —— 一条失败不连坐
//   · 不传 circuit（Rhino 下必须是数字，且它占 1 个物品输入槽，本配方用不到）
//   · 描述/剧情文本【不在本文件】—— 本文件只有配方（本工程约定：配方与描述分文件，
//     避免一个语法错把另一个连坐清空）
// ❗ 本文件只在 _smoke-mixin 沙盒里跑；部署由队长另行安排。
// =============================================================================

var myRecipes = [
    {
        id: 'shanhai:zero_point_power_max_1a',
        type: 'primordial_power_generator',
        fluid: 'shanhai:zero_point_energy',
        mb: 1000,
        durationTicks: 20,
        // 负数 = 发电（= -GTValues.V[GTValues.MAX]，运行期会在 loaded 回调里交叉核对）
        euPerTick: -2147483648
    }
]

// -----------------------------------------------------------------------------
// ① 注册配方
// -----------------------------------------------------------------------------
ServerEvents.recipes(function (event) {
    var gtr = event.recipes.gtceu

    var ok = 0
    var bad = 0
    var errList = ''
    var i

    for (i = 0; i < myRecipes.length; i++) {
        var r = myRecipes[i]
        try {
            gtr[r.type](r.id)
                .inputFluids(r.fluid + ' ' + r.mb)
                .duration(r.durationTicks)
                .EUt(r.euPerTick)
            ok = ok + 1
        } catch (e) {
            bad = bad + 1
            if (errList.length < 1000) {
                errList = errList + r.id + ' => ' + e + ' | '
            }
        }
    }

    // 🔴 [SHANHAI-SPEC] 回执 —— 证明【builder 接受了这条配方】。
    //    ⚠️ 这一条只证明"GTCEu recipe builder 没抛异常"，【不】等于"RecipeManager 收下了"；
    //       后者由下面的 loaded 回调（真去配方表里回读）与 KubeJS 的 `Added N recipes` 一起证。
    console.info('[SHANHAI-SPEC] zero_point_power register ok=' + ok + ' failed=' + bad
        + ' total=' + myRecipes.length)
    console.info('[SHANHAI-SPEC] zero_point_power spec'
        + ' machine=shanhai:primordial_void_induction_armature'
        + ' gtr_type=' + myRecipes[0].type
        + ' recipe_id=' + myRecipes[0].id
        + ' input=' + myRecipes[0].fluid + ' ' + myRecipes[0].mb + 'mB'
        + ' EUt=' + myRecipes[0].euPerTick
        + ' duration=' + myRecipes[0].durationTicks + 't')
    if (bad > 0) {
        console.error('[SHANHAI-SPEC] zero_point_power FAILED list: ' + errList)
    }

    // 🔴 交叉核对：我把 2147483648 写成字面量，这里现场问 GTValues 要真值。
    //    GTValues 是 KubeJS 直接可用的全局（宿主 misc.js:79 / gtceu.js:14219 都在用）。
    //    两边不一致 ⇒ 打 error（响亮），不静默。
    try {
        var maxTier = GTValues.MAX
        var vMax = GTValues.V[maxTier]
        var want = -myRecipes[0].euPerTick
        if (vMax == want) {
            console.info('[SHANHAI-SPEC] zero_point_power gtvalues_check MATCH'
                + ' GTValues.MAX=' + maxTier + ' GTValues.V[MAX]=' + vMax
                + ' literal_abs=' + want)
        } else {
            console.error('[SHANHAI-SPEC] zero_point_power gtvalues_check MISMATCH'
                + ' GTValues.MAX=' + maxTier + ' GTValues.V[MAX]=' + vMax
                + ' literal_abs=' + want)
        }
    } catch (e3) {
        console.error('[SHANHAI-SPEC] zero_point_power gtvalues_check THREW: ' + e3)
    }
})

// -----------------------------------------------------------------------------
// ② 配方加载完成后【真去配方表里回读】—— 证明这条配方真的注册进去了
// -----------------------------------------------------------------------------
//  用的是 GTCEu 自己的 GTRecipeType.getProxyRecipes()（Map<RecipeType,List<GTRecipe>>）
//  + GTRecipe 的【public 字段】（duration / tickOutputs）与【public 内部类字段】
//    （Content.content）。
//  🔴 刻意只用【模组类】的 public 字段，一行方法调用都不发（除了集合的 iterator/get/size）——
//     专用服务端跑的是 SRG 名，脚本里一旦写原版/官方方法名（getRecipeManager / toString 之类）
//     就可能直接 NoSuchMethod。public 字段不受重映射影响。
//  🔴 判据不靠"猜 id 字符串"，而是靠【内容】：本类型的配方里，
//     tickOutputs 的 EU == 2147483648 且 duration == 20 的那条，只可能是我们这条。
//  全部包在 try/catch 里：回读失败只打一行 error，绝不影响 ① 的配方注册。
ServerEvents.loaded(function (event) {
    var targetEu = -myRecipes[0].euPerTick          // 2147483648
    var targetDuration = myRecipes[0].durationTicks // 20
    try {
        var Types = Java.loadClass('com.shanhai.common.recipe.ShanhaiRecipeTypes')
        var rt = Types.PRIMORDIAL_POWER_GENERATOR
        var proxies = rt.getProxyRecipes()

        var total = 0
        var hitCount = 0
        var idList = ''
        var hitDetail = ''
        var bucketCount = proxies.size()

        var it = proxies.values().iterator()
        while (it.hasNext()) {
            var list = it.next()
            var k
            for (k = 0; k < list.size(); k++) {
                var rec = list.get(k)
                total = total + 1

                // 配方 id（单独 try：万一 ResourceLocation.toString 在 SRG 下不可调用，
                // 也只丢这一小段，不影响下面的 EU/duration 判据）
                try {
                    idList = idList + '[' + ('' + rec.id) + ']'
                } catch (eId) {
                    idList = idList + '[id_unprintable:' + eId + ']'
                }

                // 读这条配方【每 tick 产出】的 EU（EUio=OUT ⇒ 产出值在 tickOutputs 里）
                var eu = -1
                var outs = rec.tickOutputs
                var oit = outs.entrySet().iterator()
                while (oit.hasNext()) {
                    var en = oit.next()
                    var lst = en.getValue()
                    var j
                    for (j = 0; j < lst.size(); j++) {
                        var v = 1 * lst.get(j).content
                        if (v > eu) {
                            eu = v
                        }
                    }
                }

                if (eu == targetEu) {
                    hitCount = hitCount + 1
                    hitDetail = hitDetail + ' {duration=' + rec.duration
                        + ' tickOutputEU=' + eu
                        + ' duration_ok=' + (rec.duration == targetDuration) + '}'
                }
            }
        }

        console.info('[SHANHAI-SPEC] zero_point_power readback total_in_type=' + total
            + ' buckets=' + bucketCount
            + ' eu_match_count=' + hitCount
            + ' target_eu=' + targetEu
            + ' target_duration=' + targetDuration
            + hitDetail)
        console.info('[SHANHAI-SPEC] zero_point_power readback ids_in_type=' + idList)
    } catch (e) {
        console.error('[SHANHAI-SPEC] zero_point_power readback THREW: ' + e)
    }
})
// ═══ 手写区 结束 ═══
