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
//    · 新增 125 条【追加】进同一个文件。
//    ⇒ 条数（**本次现算**，不再写死）：工作台 25 条 + GT 机器 109 条 = 134 条。
//       其中 GT 那 109 条 = 本文件生成的 106 条 ＋ 老配方逐字保留的 3 条。
//       ⚠️ 工作台那部分 = 文件里硬编码的 6 条 ＋ 从 PF.txt 的「合成样板」现算生成的条数；
//          两者都在本脚本跑完前才算得出来 ⇒ 上面用了占位符，末尾会回填（并自检"占位符已全部替换"）。
//       ⚠️ 这里的总数是 `rows.json` 的**最终**条数（已含「土高炉 ⇒ 原始物质重组」那批 14 条副本）；
//          `样板清单.md` §1 的条数是在**加副本之前**算的（119 条）⇒ 两边数字不同是正常的，不是对不上。
//
// 源数据：PF.txt ← 由环境变量 SH_PF_SRC 指定的那个文件（本仓库不记录机器绝对路径）
//   SHA256 = B4CFFAB883BFF3C6309355E10041A6A1428321BAFD3C304B39E5A757471328E5
//   136670 B / 单行 NBT / 一个 minecraft:chest
//   ⚠️ 以上两行**每次生成时现读现算**（原先写死的是 2026-09-26 那版的值，早就对不上了）。
//
// =============================================================================
// §0 检查器自证（先说清"我凭什么信自己没看漏"）
// =============================================================================
//  · `ae2:processing_pattern` 逐条解析成功 = 133
//  · `in` 恒为 81 格 / `out` 恒为 27 格（AE2 定长数组，空槽是 `{}`）—— 133/133 条都对（格数为**现算**：取 rows 第一行的长度）
//  · 原文里的纯文本出现次数 = 119，逐条解析成功 = 119 ⇒ **两边相等，无静默漏条**
//  · 正面对照：解析出的 8 条旧配方与上一版文件头 §3 的记录【逐字一致】
//    ⇒ 说明解析器看的是同一批东西（这是"解析器自己是对的"的证据，不是自说自话）
//
// =============================================================================
// §1 元件对照（这次 vs 上次）—— 用户要的第一问
// =============================================================================
//   本次 Slot | 元件名（display.Name 逐字） | 本次条数 | 上次 | 差
//   ----------|---------------------------|---------|------|----
//   Slot 0    | 处理样板LV                | 16      | 2    | +14
//   Slot 1    | 处理样板ULV               | 36      | 1    | +35
//   Slot 2    | 处理样板-星门(MAX+16)     | 3       | 无   | 全新元件
//   Slot 3    | 合成样板                  | 24      | 5    | +19
//   Slot 4    | 处理样板MV                | 20      | 无   | 全新元件
//   Slot 5    | 处理样板HV                | 13      | 无   | 全新元件
//   Slot 6    | 处理样板EV                | 21      | 无   | 全新元件
//   ----------|---------------------------|---------|------|----
//   合计      |                           | 133     | 8    | +125
//
//  ⚠️ 「上次」那一列来自 `LAST_PF`（上一版 PF.txt 的历史留档 —— 旧源文件已被覆盖，算不出来）。
//     本次那一列与差值都是**现算**的。
//  ⚠️ 元件【槽位】：本次 {"处理样板LV":0,"处理样板ULV":1,"处理样板-星门(MAX+16)":2,"合成样板":3,"处理样板MV":4,"处理样板HV":5,"处理样板EV":6}；上次 {"处理样板ULV":0,"合成样板":1,"处理样板LV":2} ⇒ 槽位是否后移，按这两组数自己对（本文件不替用户下结论）。
//
// =============================================================================
// §2 中文名 → `gtceu:<id>` 映射（口径沿用上次：lang 里【唯一精确等于】那条）
// =============================================================================
//   纸上中文名     | gtceu id                            | 来源          | 条数（现算）
//   ---------------|-------------------------------------|---------------|-----
//   原初物质重组    | gtceu:primordial_matter_recombination | shanhai zh_cn | 24
//   原初奇点反演    | gtceu:primordial_singularity_inversion| shanhai zh_cn | 17
//   土高炉          | gtceu:primitive_blast_furnace         | gtceu zh_cn   | 14
//   世线电路板组装  | gtceu:wl_board_circuit_assembly       | shanhai zh_cn | 10
//   光子分离        | gtceu:photon_separation               | shanhai zh_cn | 8
//   物质锻造        | gtceu:matter_forging                  | shanhai zh_cn | 7
//   物质模块铸造    | gtceu:matter_module_casting           | shanhai zh_cn | 5
//   物质流凝结      | gtceu:matter_flow_condensation        | shanhai zh_cn | 5
//   原初因果编织    | gtceu:primordial_causal_weaving       | shanhai zh_cn | 3
//   量子化现实重构  | gtceu:spacetime_distortion            | shanhai zh_cn | 3
//   世线震荡收集    | gtceu:worldline_oscillation_collection| shanhai zh_cn | 2
//   光子虹吸        | gtceu:photon_siphon                   | shanhai zh_cn | 2
//   原初世线切割    | gtceu:worldline_cutting               | shanhai zh_cn | 2
//   星际物质吸取    | gtceu:interstellar_matter_absorption  | shanhai zh_cn | 2
//   世线采样        | gtceu:worldline_sampling              | shanhai zh_cn | 1
//   太虚熔炼        | gtceu:taixu_smelting                  | shanhai zh_cn | 1
//   引力波宏观干涉  | gtceu:gravitational_wave_production   | shanhai zh_cn | 1
//   渔场            | gtceu:fishing_ground                  | gtceu zh_cn   | 1
//   电路组装机      | gtceu:circuit_assembler               | gtceu zh_cn   | 1
//   ⇒ 纸上出现过的配方类型共 19 种，合计 109 条（= 全部带类型的样板）。
//
//  ✅ 已消除的唯一歧义：纸上写「世线电路板组装」，lang 里是「世线板电路组装」——
//     用户 2026-09-26 亲自裁决：「我写错了」⇒ 按 lang 的 `gtceu:wl_board_circuit_assembly` 落，
//     原先的 ⚠️ 待裁决标记已按用户裁决【移除】。
//
// =============================================================================
// §3 配方类型 id 的存在性核实（正面对照，不是猜）
// =============================================================================
//  · **shanhai 类型总数 = 46**（**现算**：读真源① `shanhai-rewrite\src\main\java\com\shanhai\common\recipe\ShanhaiRecipeTypes.java`
//      的【活代码】`.setMaxIOSize(...)` 条数，与同文件常量 `REAL_TYPE_COUNT = 46` 互为自证；
//      注释作废块里另有 24 条，已按"行首是注释符"剔除。）
//  · 上述类型的 id 存在性由加载期断言 G3 保证（CAP 的每个键都必须能追溯到真源①/②/③），
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
//     primordial_biological_core         = (1, 72, 3, 3)
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
//     primordial_deep_space_extraction   = (2, 108, 0, 8)
//     circuit_assembler                  = (6, 1, 1, 0)
//     primitive_blast_furnace            = (3, 3, 0, 0)
//     fishing_ground                     = (2, 24, 0, 0)
//
//  上表 = 【真源现读现算】的结果（2026-09-29 起），不再手抄：
//    真源① shanhai 类型 ⇒ shanhai-rewrite\src\main\java\com\shanhai\common\recipe\ShanhaiRecipeTypes.java
//        的【活代码】`.setMaxIOSize(a,b,c,d)`（行首是注释符的作废块会被剔除）。
//    真源② gtceu 原生类型 ⇒ recipe-convert\javap\GTRecipeTypes.txt（已部署 gtceu jar 的 javap -c 转储）。
//    真源③ 「别的 mod 注册在 gtceu 命名空间下」的类型 ⇒ **现读实例 mods**（2026-10-04 新增）：
//        扫 `mods\*.jar` 里常量池含 `setMaxIOSize` 的注册类 ⇒ 解包 + `javap -p -c` ⇒ 取 `register("id",…)` 后那个四元组。
//        脚本 kubejs\_generators\mod_caps.js；**自动定位，不需要人工维护快照**（无缓存 ⇒ 不会像真源②那样过期）。
//        本次真源③ 供数的类型见上面 §4 表里标注或不标注的全部键；其判定另有两条护栏：
//          ① 旁证：id 必须另有 lang 的 `gtceu.<id>` 键 或 游戏导出表目录，否则丢弃（防"回看取错字符串"凭空造类型）；
//          ② 交叉验证：真源③ 与真源② 对**同一个** `GTRecipeTypes.class` 必须逐条相等（本次 58 个类型 ✓）。
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
//    #7       | shanhai:pf/photon_2        | photon_separation  | 3      | 2 ❌    | 4 ✓
//    #11      | shanhai:pf/electron        | photon_separation  | 3      | 2 ❌    | 4 ✓
//    #16      | shanhai:pf/photon_rainbow  | photon_separation  | 3      | 2 ❌    | 4 ✓
//    #67      | shanhai:pf/up_quark        | photon_separation  | 3      | 2 ❌    | 4 ✓
//    #73      | shanhai:pf/up_quark_2      | photon_separation  | 3      | 2 ❌    | 4 ✓
//    #78      | shanhai:pf/down_quark      | photon_separation  | 3      | 2 ❌    | 4 ✓
//    #82      | shanhai:pf/down_quark_2    | photon_separation  | 3      | 2 ❌    | 4 ✓
//    #98      | shanhai:pf/muon            | photon_separation  | 3      | 2 ❌    | 4 ✓
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
//    （本次确实有 2 条：#20 / #122「产出 minecraft:paper」是真的出纸，那些保留）。
//  · 纸有三种：
//      ① 「配方类型：XXX」（也有裸写类型名的，如「光子虹吸」「电路组装机」）⇒ 决定用哪台机器
//      ② 「Ns」⇒ 决定耗时（×20 = tick）
//      ③ 备注（本次四种：物质模块是催化剂 87 条 / 力场发生器是催化剂 5 条 / 夸克释放催化剂作为催化剂 2 条 / 产出概率纸 2 条）
//         ⚠️ 上面每个数都是【行数】不是【纸数】：rows.json 里含「原初物质重组 ⇒ 土高炉」的副本行，
//            同一张纸会被算多行 ⇒ 例如「物质模块是催化剂」纸面只有 43 张、这里会显示 57 行。
//  · 🔴 纸写在 in 里，也可能写在 **out** 里 —— #11 / #98（2 条）的「产出概率纸」就在 out[3]。
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
//        `shanhai:pf/easy_world_fragment_miner_module`（PF 第 35 条：gate ⇒ consume）；`shanhai:pf/advanced_world_fragment_miner_module`（PF 第 70 条：gate ⇒ consume）；`shanhai:pf/circult_process_module_1`（PF 第 105 条：gate ⇒ consume）；`shanhai:pf/ultimate_world_fragment_miner_module`（PF 第 117 条：gate ⇒ consume）；`shanhai:pf/greenhouse_speed_cultivation_module`（PF 第 119 条：gate ⇒ consume）（5 条）
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
//           · `shanhai:pf/primordial_omega_engine` ⇐ PF 第 26 条｜纸：**无**催化剂纸 ⇒ **consume**（本条输入里本来就没有物质模块 ⇒ 落法无变化）
//           · `shanhai:pf/photon` ⇐ PF 第 9 条｜纸：有催化剂纸 ⇒ **gate**（保持门槛形态，两行不动）
//           · `shanhai:pf/first_light` ⇐ PF 第 2 条｜纸：**无**催化剂纸 ⇒ **consume**（本条输入里本来就没有物质模块 ⇒ 落法无变化）
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
//       （上面这个数是按本次 specs 的 flags **现算**的；纸写「物质模块是催化剂」的样板共 87 条：1 / 3 / 5 / 6 / 7 / 8 / 9 / 10 / 11 / 12 / 13 / 14 / 15 / 16 / 17 / 18 / 19 / 20 / 21 / 24 / 25 / 27 / 28 / 29 / 30 / 31 / 32 / 33 / 34 / 36 / 37 / 38 / 39 / 40 / 41 / 67 / 68 / 71 / 72 / 73 / 75 / 76 / 78 / 79 / 80 / 81 / 82 / 83 / 84 / 86 / 87 / 88 / 89 / 90 / 91 / 92 / 94 / 95 / 96 / 97 / 98 / 99 / 100 / 101 / 102 / 104 / 106 / 111 / 112 / 114 / 115 / 116 / 118 / 120 / 121 / 122 / 123 / 124 / 125 / 126 / 127 / 128 / 129 / 130 / 131 / 132 / 133）
//       本文件按"无门槛无催化剂"落，并在脚本里逐条注明。
//     📌 留档 —— 本版【取代】的两条旧口径（结论已被覆盖，只留证据链）：
//        · 2026-09-28（逐字）：「有些配方的物质模块的配置错了，目前，注意是目前只有制作物质模块和世线的配方才需要消耗物质模块」
//          ⇒ 当时把判据从"看纸"改成"看产出与配方类型"（`consumeModule = isModuleRecipe || (isWorldlineRecipe && !isShardFamilyRecipe)`）。
//          ⚠️ 那正是本版要改掉的：纸上一句话都没写的配方被【静默】判成不消耗（用户报的 no=98 就是这种）。
//        · 2026-10-01（逐字）：「那条配方的模块」⇒ 选「B. 等级门槛（不烧、但要挂）」；
//          「世线残片其余 6 档 ＋ 寰宇并行超限器」⇒ 选「A. 还没写，以后补」⇒ **不许动**。
//          ⇒ 当时给"世线族 ⇒ 消耗"再加一层"看产出是不是残片族"（SHARD_FAMILY，产出 ∈ 残片族 ⇒ 不消耗）。
//          ✅ 本版下该结论【仍然成立】（本次命中它的 2 条产出 ∈ 残片族的配方纸上都有催化剂纸 ⇒ 照样不消耗）；
//            但残片族已【不再参与判定】，只在产物注释里留一句"与 2026-10-01 结论也一致"。
//        · 2026-09-26（更早，逐字）：「以后物质模块是催化剂指的都是我们今天刚写好的机制」
//          ⇒ 当时口径 = 「纸上写 ⇒ 等级门槛」，并给 `primitive_blast_furnace` 单开一张 NO_GATE_TYPES 白名单。
//          本版回到"看纸"，但那**不是**回到这一版：本版有显式正向表 SHANHAI_TYPES 决定"不消耗时的形态"，
//          白名单式的反写逻辑【不再使用】。
//  ②「力场发生器是催化剂」—— #7 / #11 / #16 / #98 / #116（5 条） —— 🔴 2026-10-03 判据已由【写死 LV】改成【认整族】
//       现判据（两条**同时**满足）：① 纸写了这句话；② 物品 id ∈ 力场发生器族 = `/^(?:gtceu|gtlcore):[a-z0-9_]*_field_generator$/`
//         （命名空间锚死在 `gtceu:` / `gtlcore:` —— 裸后缀 `/field_generator$/` 会误吞
//          `kubejs:containment_field_generator` 与 `kubejs:spacetime_compression_field_generator`）
//       📌 族自证（正负对照，生成期现读导出注册表）：以 `field_generator` 结尾的 id 共 16 个；判据命中 14 个 [gtceu:ev_field_generator / gtceu:hv_field_generator / gtceu:iv_field_generator / gtceu:luv_field_generator / gtceu:lv_field_generator / gtceu:mv_field_generator / gtceu:opv_field_generator / gtceu:uev_field_generator / gtceu:uhv_field_generator / gtceu:uiv_field_generator / gtceu:uv_field_generator / gtceu:uxv_field_generator / gtceu:zpm_field_generator / gtlcore:max_field_generator]；被排除 2 个 [kubejs:containment_field_generator / kubejs:spacetime_compression_field_generator]
//       ✅ 因这条改动而改变的配方（**现算**，不是手抄）：`shanhai:pf/photon_2`（1x gtceu:lv_field_generator）；`shanhai:pf/electron`（1x gtceu:lv_field_generator）；`shanhai:pf/photon_rainbow`（1x gtceu:lv_field_generator）；`shanhai:pf/muon`（1x gtceu:hv_field_generator）；`shanhai:pf/cod`（64x gtceu:ev_field_generator）
//       📌 留档（被本版覆盖的旧口径，2026-09-30 我写下的原文**逐字**，一字未改）：
//          「这条规则在代码里是【硬编码 LV】的 —— 判据写死成 `it.id === 'gtceu:lv_field_generator'`。」
//          「本次 3 条命中的确实都是 LV，所以落法正确；但**换一台 MV/HV 力场发生器就会静默不生效**
//           （物品照常被消耗、且不报错）。旁证：PF 第 58 条用的是 `gtceu:mv_field_generator`，
//           它身上没有这张纸所以没暴露。⇒ 建议改成"凡 `*_field_generator` 且纸写了这句 ⇒ 催化剂"。」
//          「我没动它：那会改变 58 条吗？不会（它没这张纸）—— 但它属于「扩大规则覆盖面」，
//           按本轮硬要求②（改数值/口径要停下报）留给你裁决。」
//       ⇒ 用户 2026-10-03 拍板：修。上面那个"建议"已落地，但**没有**照它字面用裸 `*_field_generator`
//          （裸后缀会误吞那 2 个 kubejs 的同类 id），改用命名空间锚定 + 注册表现场自证。
//  ④「夸克释放催化剂作为催化剂」—— #73 / #78（2 条）（**本轮新增支持**）
//       纸面原文逐字：「夸克释放催化剂作为催化剂」（**末尾没有句号**）。
//       ⇒ 本文件按 `.notConsumable('<纸上那个数量>x shanhai:<上|下>_quark_emission_catalyst')` 落，
//          **数量照纸上一字未改**（本次两条都是 64）；本轮只改"消不消耗"，一个数字都没动。
//       🔴 本轮之前这句话【一个字都没被读到】⇒ 那 64 个按普通材料落进 itemInputs 被吃掉。
//          取证：把 PF 副本里这句话改名后重跑，specs 的**配方形状差异 = 0** ⇒ 反证"当前完全没读"。
//       ⚠️ 只对**纸上写了这句话**的配方生效 ⇒ 第 59 / 63 条（同样有 ×1 夸克释放催化剂，
//          但纸上没有这句话）保持原样：仍在 itemInputs 里被消耗。这个不对称**留给你裁决**。
//  ③「产出概率纸」—— #11 / #98（2 条），且写在该样板的 **out[3]**，紧邻 out[2] 的
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
//        ⇒ #11 / #98（2 条）的 recipeTier = LV(1)，实际概率随机器超频：
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
    },
    {
        id: 'shanhai:pf_crafting/primordial_anti_entropy_condensation_core',
        out: 'shanhai:primordial_anti_entropy_condensation_core',
        pattern: ['ABA', 'BCB', 'ABA'],
        keys: {
            A: 'gtceu:vacuum_freezer',
            B: 'thetornproductionline:celestial_secret_deducing_module_mv',
            C: 'shanhai:primordial_engine_core'
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
    }
]

// -----------------------------------------------------------------------------
// 老 3 条 GT（①⑦⑧，与上一版【逐字相同】，本次未改动）+ 新增 106 条（条数现算）
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
        // 🧪 物质模块【不消耗·等级门槛·纸上有催化剂纸】：纸上（第 8 格）放着「物质模块是催化剂」 ⇒ 落 ModuleLevelCondition 等级门槛（不占输入槽）。｜⚠️ 本条是老配方（正文由 emitOldGt 硬编码）；2026-10-03 追加：它的模块落法改为按源样板（PF 第 9 条）的纸**现算**，不再写死。
        moduleLevelRequirement: '1x shanhai:basic_material_module',
        // ⚠️ 降级用：条件类不可用时退回催化剂形态（宁可比原来差，也不能让配方消失）
        moduleLevelFallbackCatalyst: '1x shanhai:basic_material_module',
        itemInputs: [],
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
    // ▶ PF.txt 第 1 条（本次新增）｜元件「处理样板LV」｜纸：类型「世线电路板组装」／耗时「60s」｜输出 1x shanhai:wl_board_lv
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
    // ▶ PF.txt 第 3 条（本次新增）｜元件「处理样板LV」｜纸：类型「原初物质重组」／耗时「60s」｜输出 1024x minecraft:obsidian
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
    // ▶ PF.txt 第 4 条（本次新增）｜元件「处理样板LV」｜纸：类型「物质模块铸造」／耗时「60s」｜输出 1x shanhai:basic_material_module
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
    // ▶ PF.txt 第 5 条（本次新增）｜元件「处理样板LV」｜纸：类型「物质流凝结」／耗时「15s」｜输出 
    // 🧪 物质模块【不消耗·等级门槛·纸上有催化剂纸】：纸上（第 6 格）放着「物质模块是催化剂」，且类型 matter_flow_condensation ∈ SHANHAI_TYPES（= 山海自己的机器） ⇒ 落 ModuleLevelCondition（要求模块等级 ≥ 1，等级取自 MODULE_LEVELS）。⚠️ 门槛【不占输入槽】。若本条应当"只是纯催化剂（.notConsumable）"，改 SHANHAI_TYPES 即可，一处生效。
    {
        id: 'shanhai:pf/matter_flow_condensation_5',
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
    // ▶ PF.txt 第 6 条（本次新增）｜元件「处理样板LV」｜纸：类型「原初物质重组」／耗时「10s」｜输出 
    // 🧪 物质模块【不消耗·等级门槛·纸上有催化剂纸】：纸上（第 1 格）放着「物质模块是催化剂」，且类型 primordial_matter_recombination ∈ SHANHAI_TYPES（= 山海自己的机器） ⇒ 落 ModuleLevelCondition（要求模块等级 ≥ 2，等级取自 MODULE_LEVELS）。⚠️ 门槛【不占输入槽】。若本条应当"只是纯催化剂（.notConsumable）"，改 SHANHAI_TYPES 即可，一处生效。
    {
        id: 'shanhai:pf/primordial_matter_recombination_6',
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
    // ▶ PF.txt 第 7 条（本次新增）｜元件「处理样板LV」｜纸：类型「光子分离」／耗时「3s」｜输出 2x shanhai:photon + 1x shanhai:photon_rainbow + 1x shanhai:unknown_particle
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
    // ▶ PF.txt 第 8 条（本次新增）｜元件「处理样板LV」｜纸：类型「原初物质重组」／耗时「3s」｜输出 
    // 🧪 物质模块【不消耗·等级门槛·纸上有催化剂纸】：纸上（第 7 格）放着「物质模块是催化剂」，且类型 primordial_matter_recombination ∈ SHANHAI_TYPES（= 山海自己的机器） ⇒ 落 ModuleLevelCondition（要求模块等级 ≥ 2，等级取自 MODULE_LEVELS）。⚠️ 门槛【不占输入槽】。若本条应当"只是纯催化剂（.notConsumable）"，改 SHANHAI_TYPES 即可，一处生效。
    {
        id: 'shanhai:pf/primordial_matter_recombination_8',
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
    // ▶ PF.txt 第 10 条（本次新增）｜元件「处理样板LV」｜纸：类型「原初奇点反演」／耗时「3s」｜输出 64x kubejs:ulv_universal_circuit
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
    // ▶ PF.txt 第 11 条（本次新增）｜元件「处理样板LV」｜纸：类型「光子分离」／耗时「3s」｜输出 1x shanhai:electron
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
    // ▶ PF.txt 第 12 条（本次新增）｜元件「处理样板LV」｜纸：类型「星际物质吸取」／耗时「30s」｜输出 1x gtlcore:treasures_crystal + 8x gtlcore:mining_crystal
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
    // ▶ PF.txt 第 13 条（本次新增）｜元件「处理样板LV」｜纸：类型「原初物质重组」／耗时「3s」｜输出 1x gtceu:exquisite_emerald_gem
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
    // ▶ PF.txt 第 14 条（本次新增）｜元件「处理样板LV」｜纸：类型「原初奇点反演」／耗时「3s」｜输出 1x thetornproductionline:celestial_secret_deducing_module_lv
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
    // ▶ PF.txt 第 15 条（本次新增）｜元件「处理样板LV」｜纸：类型「原初物质重组」／耗时「10s」｜输出 
    // 🧪 物质模块【不消耗·等级门槛·纸上有催化剂纸】：纸上（第 1 格）放着「物质模块是催化剂」，且类型 primordial_matter_recombination ∈ SHANHAI_TYPES（= 山海自己的机器） ⇒ 落 ModuleLevelCondition（要求模块等级 ≥ 2，等级取自 MODULE_LEVELS）。⚠️ 门槛【不占输入槽】。若本条应当"只是纯催化剂（.notConsumable）"，改 SHANHAI_TYPES 即可，一处生效。
    {
        id: 'shanhai:pf/primordial_matter_recombination_15',
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
    // ▶ PF.txt 第 16 条（本次新增）｜元件「处理样板LV」｜纸：类型「光子分离」／耗时「3s」｜输出 8x shanhai:photon_rainbow + 1x shanhai:unknown_particle
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
    // ▶ PF.txt 第 17 条（本次新增）｜元件「处理样板ULV」｜纸：类型「土高炉」／耗时「3s」｜输出 4x gtceu:brass_ingot
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
    // ▶ PF.txt 第 18 条（本次新增）｜元件「处理样板ULV」｜纸：类型「世线电路板组装」／耗时「3s」｜输出 8x shanhai:wl_board_ulv
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
    // ▶ PF.txt 第 20 条（本次新增）｜元件「处理样板ULV」｜纸：类型「土高炉」／耗时「3s」｜输出 2x minecraft:paper
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
    // ▶ PF.txt 第 21 条（本次新增）｜元件「处理样板ULV」｜纸：类型「土高炉」／耗时「3s」｜输出 3x gtceu:invar_ingot
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
    // ▶ PF.txt 第 22 条（本次新增）｜元件「处理样板ULV」｜纸：类型「物质模块铸造」／耗时「60s」｜输出 1x shanhai:introductory_material_module
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
    // ▶ PF.txt 第 23 条（本次新增）｜元件「处理样板ULV」｜纸：类型「物质流凝结」／耗时「15s」｜输出 
    {
        id: 'shanhai:pf/matter_flow_condensation_23',
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
    // ▶ PF.txt 第 24 条（本次新增）｜元件「处理样板ULV」｜纸：类型「土高炉」／耗时「3s」｜输出 2x gtceu:red_alloy_ingot
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
    // ▶ PF.txt 第 25 条（本次新增）｜元件「处理样板ULV」｜纸：类型「土高炉」／耗时「3s」｜输出 1x gtceu:compressed_fireclay
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
    // ▶ PF.txt 第 27 条（本次新增）｜元件「处理样板ULV」｜纸：类型「土高炉」／耗时「3s」｜输出 4x gtceu:tin_alloy_ingot
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
    // ▶ PF.txt 第 28 条（本次新增）｜元件「处理样板ULV」｜纸：类型「土高炉」／耗时「3s」｜输出 1x gtceu:annealed_copper_ingot
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
    // ▶ PF.txt 第 29 条（本次新增）｜元件「处理样板ULV」｜纸：类型「土高炉」／耗时「3s」｜输出 2x gtceu:steel_ingot
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
    // ▶ PF.txt 第 30 条（本次新增）｜元件「处理样板ULV」｜纸：类型「土高炉」／耗时「3s」｜输出 1x gtceu:conductive_alloy_ingot
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
    // ▶ PF.txt 第 31 条（本次新增）｜元件「处理样板ULV」｜纸：类型「原初物质重组」／耗时「3s」｜输出 3x minecraft:fire_charge
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
    // ▶ PF.txt 第 32 条（本次新增）｜元件「处理样板ULV」｜纸：类型「原初因果编织」／耗时「3s」｜输出 4x gtceu:sticky_resin
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
    // ▶ PF.txt 第 33 条（本次新增）｜元件「处理样板ULV」｜纸：类型「原初奇点反演」／耗时「3s」｜输出 8x thetornproductionline:celestial_secret_deducing_module_ulv
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
    // ▶ PF.txt 第 34 条（本次新增）｜元件「处理样板ULV」｜纸：类型「土高炉」／耗时「3s」｜输出 4x gtceu:bronze_ingot
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
    // ▶ PF.txt 第 35 条（本次新增）｜元件「处理样板ULV」｜纸：类型「原初奇点反演」／耗时「60s」｜输出 1x thetornproductionline:easy_world_fragment_miner_module
    // 🧪 物质模块【消耗·纸上无催化剂纸】：纸上（无）【没有】「物质模块是催化剂」 ⇒ 按 2026-10-03 判据（只看纸）模块留在 itemInputs 里被正常消耗，不挂催化剂、不设门槛。
    {
        id: 'shanhai:pf/easy_world_fragment_miner_module',
        type: 'primordial_singularity_inversion',
        circuit: 28,
        notConsumable: [],
        itemInputs: ['4x gtceu:ulv_fragment_world_collection_machine', '1x thetornproductionline:celestial_secret_deducing_module_lv', '1x shanhai:introductory_material_module'],
        inputFluids: [],
        itemOutputs: ['1x thetornproductionline:easy_world_fragment_miner_module'],
        outputFluids: [],
        chancedOutputs: [],
        duration: 1200,
        EUt: 8
    }
,
    // ▶ PF.txt 第 36 条（本次新增）｜元件「处理样板ULV」｜纸：类型「土高炉」／耗时「3s」｜输出 1x gtceu:glass_tube
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
    // ▶ PF.txt 第 37 条（本次新增）｜元件「处理样板ULV」｜纸：类型「土高炉」／耗时「3s」｜输出 2x gtceu:cupronickel_ingot
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
    // ▶ PF.txt 第 38 条（本次新增）｜元件「处理样板ULV」｜纸：类型「土高炉」／耗时「3s」｜输出 1x gtceu:pulsating_alloy_ingot
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
    // ▶ PF.txt 第 39 条（本次新增）｜元件「处理样板-星门(MAX+16)」｜纸：类型「量子化现实重构」／耗时「3s」｜输出 1x shanhai:test_item + 1x shanhai:test_dynamic_text + 1x shanhai:zwf
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
    // ▶ PF.txt 第 40 条（本次新增）｜元件「处理样板-星门(MAX+16)」｜纸：类型「量子化现实重构」／耗时「3s」｜输出 1x gtceu:creative_chest
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
    // ▶ PF.txt 第 41 条（本次新增）｜元件「处理样板-星门(MAX+16)」｜纸：类型「量子化现实重构」／耗时「3s」｜输出 1x shanhai:primordial_debug_module
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
    // ▶ PF.txt 第 66 条（本次新增）｜元件「处理样板MV」｜纸：类型「原初奇点反演」／耗时「3s」｜输出 1x shanhai:down_quark_emission_catalyst
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
    // ▶ PF.txt 第 67 条（本次新增）｜元件「处理样板MV」｜纸：类型「光子分离」／耗时「3s」｜输出 1x shanhai:up_quark + 1x shanhai:casing_empty_quark_emission_catalyst
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
    // ▶ PF.txt 第 68 条（本次新增）｜元件「处理样板MV」｜纸：类型「原初物质重组」／耗时「3s」｜输出 1x gtceu:glass_gem
    // 🧪 物质模块【不消耗·等级门槛·纸上有催化剂纸】：纸上（第 5 格）放着「物质模块是催化剂」，且类型 primordial_matter_recombination ∈ SHANHAI_TYPES（= 山海自己的机器） ⇒ 落 ModuleLevelCondition（要求模块等级 ≥ 3，等级取自 MODULE_LEVELS）。⚠️ 门槛【不占输入槽】。若本条应当"只是纯催化剂（.notConsumable）"，改 SHANHAI_TYPES 即可，一处生效。
    {
        id: 'shanhai:pf/glass_gem',
        type: 'primordial_matter_recombination',
        circuit: 30,
        notConsumable: [],
        moduleLevelRequirement: '1x shanhai:material_deduction_module',
        moduleLevelFallbackCatalyst: '1x shanhai:material_deduction_module',
        itemInputs: ['1x minecraft:glass'],
        inputFluids: [],
        itemOutputs: ['1x gtceu:glass_gem'],
        outputFluids: [],
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
    // ▶ PF.txt 第 70 条（本次新增）｜元件「处理样板MV」｜纸：类型「原初奇点反演」／耗时「60s」｜输出 1x thetornproductionline:advanced_world_fragment_miner_module
    // 🧪 物质模块【消耗·纸上无催化剂纸】：纸上（无）【没有】「物质模块是催化剂」 ⇒ 按 2026-10-03 判据（只看纸）模块留在 itemInputs 里被正常消耗，不挂催化剂、不设门槛。
    {
        id: 'shanhai:pf/advanced_world_fragment_miner_module',
        type: 'primordial_singularity_inversion',
        circuit: 28,
        notConsumable: [],
        itemInputs: ['16x gtceu:ulv_fragment_world_collection_machine', '1x thetornproductionline:celestial_secret_deducing_module_mv', '1x thetornproductionline:easy_world_fragment_miner_module', '1x shanhai:material_deduction_module'],
        inputFluids: [],
        itemOutputs: ['1x thetornproductionline:advanced_world_fragment_miner_module'],
        outputFluids: [],
        chancedOutputs: [],
        duration: 1200,
        EUt: 128
    }
,
    // ▶ PF.txt 第 71 条（本次新增）｜元件「处理样板MV」｜纸：类型「原初世线切割」／耗时「60s」｜输出 1x shanhai:thread_shard_1
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
    // ▶ PF.txt 第 72 条（本次新增）｜元件「处理样板MV」｜纸：类型「原初奇点反演」／耗时「3s」｜输出 1x thetornproductionline:celestial_secret_deducing_module_mv
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
    // ▶ PF.txt 第 73 条（本次新增）｜元件「处理样板MV」｜纸：类型「光子分离」／耗时「3s」｜输出 1x shanhai:up_quark
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
    // ▶ PF.txt 第 74 条（本次新增）｜元件「处理样板MV」｜纸：类型「世线采样」／耗时「30s」｜输出 1x shanhai:worldline_crystal_core
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
    // ▶ PF.txt 第 75 条（本次新增）｜元件「处理样板MV」｜纸：类型「星际物质吸取」／耗时「3s」｜输出 1x shanhai:cosmic_dust
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
    // ▶ PF.txt 第 77 条（本次新增）｜元件「处理样板MV」｜纸：类型「原初奇点反演」／耗时「3s」｜输出 1x shanhai:up_quark_emission_catalyst
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
    // ▶ PF.txt 第 78 条（本次新增）｜元件「处理样板MV」｜纸：类型「光子分离」／耗时「3s」｜输出 1x shanhai:down_quark
    // 🧪 物质模块【不消耗·等级门槛·纸上有催化剂纸】：纸上（第 4 格）放着「物质模块是催化剂」，且类型 photon_separation ∈ SHANHAI_TYPES（= 山海自己的机器） ⇒ 落 ModuleLevelCondition（要求模块等级 ≥ 3，等级取自 MODULE_LEVELS）。⚠️ 门槛【不占输入槽】。若本条应当"只是纯催化剂（.notConsumable）"，改 SHANHAI_TYPES 即可，一处生效。
    // [SHANHAI-DESC] 夸克释放催化剂作为催化剂 ⇒ 落 `.notConsumable(64x shanhai:down_quark_emission_catalyst)`（**不消耗**，数量照纸上一字未改）
    {
        id: 'shanhai:pf/down_quark',
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
    // ▶ PF.txt 第 79 条（本次新增）｜元件「处理样板MV」｜纸：类型「原初物质重组」／耗时「3s」｜输出 
    // 🧪 物质模块【不消耗·等级门槛·纸上有催化剂纸】：纸上（第 7 格）放着「物质模块是催化剂」，且类型 primordial_matter_recombination ∈ SHANHAI_TYPES（= 山海自己的机器） ⇒ 落 ModuleLevelCondition（要求模块等级 ≥ 3，等级取自 MODULE_LEVELS）。⚠️ 门槛【不占输入槽】。若本条应当"只是纯催化剂（.notConsumable）"，改 SHANHAI_TYPES 即可，一处生效。
    {
        id: 'shanhai:pf/primordial_matter_recombination_79',
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
    // ▶ PF.txt 第 80 条（本次新增）｜元件「处理样板MV」｜纸：类型「原初奇点反演」／耗时「3s」｜输出 64x kubejs:lv_universal_circuit
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
    // ▶ PF.txt 第 81 条（本次新增）｜元件「处理样板MV」｜纸：类型「世线电路板组装」／耗时「60s」｜输出 1x shanhai:wl_board_mv
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
    // ▶ PF.txt 第 82 条（本次新增）｜元件「处理样板MV」｜纸：类型「光子分离」／耗时「3s」｜输出 1x shanhai:down_quark + 1x shanhai:casing_empty_quark_emission_catalyst
    // 🧪 物质模块【不消耗·等级门槛·纸上有催化剂纸】：纸上（第 3 格）放着「物质模块是催化剂」，且类型 photon_separation ∈ SHANHAI_TYPES（= 山海自己的机器） ⇒ 落 ModuleLevelCondition（要求模块等级 ≥ 2，等级取自 MODULE_LEVELS）。⚠️ 门槛【不占输入槽】。若本条应当"只是纯催化剂（.notConsumable）"，改 SHANHAI_TYPES 即可，一处生效。
    {
        id: 'shanhai:pf/down_quark_2',
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
    // ▶ PF.txt 第 83 条（本次新增）｜元件「处理样板MV」｜纸：类型「原初物质重组」／耗时「3s」｜输出 1x gtceu:quantum_eye
    // 🧪 物质模块【不消耗·等级门槛·纸上有催化剂纸】：纸上（第 4 格）放着「物质模块是催化剂」，且类型 primordial_matter_recombination ∈ SHANHAI_TYPES（= 山海自己的机器） ⇒ 落 ModuleLevelCondition（要求模块等级 ≥ 3，等级取自 MODULE_LEVELS）。⚠️ 门槛【不占输入槽】。若本条应当"只是纯催化剂（.notConsumable）"，改 SHANHAI_TYPES 即可，一处生效。
    {
        id: 'shanhai:pf/quantum_eye',
        type: 'primordial_matter_recombination',
        circuit: 31,
        notConsumable: [],
        moduleLevelRequirement: '1x shanhai:material_deduction_module',
        moduleLevelFallbackCatalyst: '1x shanhai:material_deduction_module',
        itemInputs: ['4x minecraft:ender_eye'],
        inputFluids: ['gtceu:radon 1000'],
        itemOutputs: ['1x gtceu:quantum_eye'],
        outputFluids: [],
        chancedOutputs: [],
        duration: 60,
        EUt: 128
    }
,
    // ▶ PF.txt 第 84 条（本次新增）｜元件「处理样板MV」｜纸：类型「原初奇点反演」／耗时「30s」｜输出 1x shanhai:casing_empty_quark_emission_catalyst
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
    // ▶ PF.txt 第 85 条（本次新增）｜元件「处理样板MV」｜纸：类型「物质模块铸造」／耗时「60s」｜输出 1x shanhai:material_deduction_module
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
    // ▶ PF.txt 第 86 条（本次新增）｜元件「处理样板HV」｜纸：类型「原初奇点反演」／耗时「3s」｜输出 1x gtmadvancedhatch:adaptive_net_energy_input_hatch
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
    // ▶ PF.txt 第 87 条（本次新增）｜元件「处理样板HV」｜纸：类型「世线电路板组装」／耗时「60s」｜输出 1x shanhai:wem_1
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
    // ▶ PF.txt 第 88 条（本次新增）｜元件「处理样板HV」｜纸：类型「物质锻造」／耗时「10s」｜输出 1x shanhai:gluon
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
    // ▶ PF.txt 第 89 条（本次新增）｜元件「处理样板HV」｜纸：类型「世线电路板组装」／耗时「30s」｜输出 1x shanhai:wl_board_hv
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
    // ▶ PF.txt 第 90 条（本次新增）｜元件「处理样板HV」｜纸：类型「原初奇点反演」／耗时「3s」｜输出 64x kubejs:mv_universal_circuit
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
    // ▶ PF.txt 第 91 条（本次新增）｜元件「处理样板HV」｜纸：类型「原初物质重组」／耗时「3s」｜输出 
    // 🧪 物质模块【不消耗·等级门槛·纸上有催化剂纸】：纸上（第 7 格）放着「物质模块是催化剂」，且类型 primordial_matter_recombination ∈ SHANHAI_TYPES（= 山海自己的机器） ⇒ 落 ModuleLevelCondition（要求模块等级 ≥ 4，等级取自 MODULE_LEVELS）。⚠️ 门槛【不占输入槽】。若本条应当"只是纯催化剂（.notConsumable）"，改 SHANHAI_TYPES 即可，一处生效。
    {
        id: 'shanhai:pf/primordial_matter_recombination_91',
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
    // ▶ PF.txt 第 92 条（本次新增）｜元件「处理样板HV」｜纸：类型「物质锻造」／耗时「10s」｜输出 1x shanhai:gluon
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
    // ▶ PF.txt 第 93 条（本次新增）｜元件「处理样板HV」｜纸：类型「物质模块铸造」／耗时「60s」｜输出 1x shanhai:virtual_image_material_module
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
    // ▶ PF.txt 第 94 条（本次新增）｜元件「处理样板HV」｜纸：类型「太虚熔炼」／耗时「60s」｜输出 1x shanhai:taixu_dust
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
    // ▶ PF.txt 第 95 条（本次新增）｜元件「处理样板HV」｜纸：类型「原初奇点反演」／耗时「3s」｜输出 1x thetornproductionline:celestial_secret_deducing_module_hv
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
    // ▶ PF.txt 第 96 条（本次新增）｜元件「处理样板HV」｜纸：类型「原初因果编织」／耗时「3s」｜输出 1x shanhai:navigate_prism
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
    // ▶ PF.txt 第 97 条（本次新增）｜元件「处理样板HV」｜纸：类型「物质流凝结」／耗时「10s」｜输出 
    // 🧪 物质模块【不消耗·等级门槛·纸上有催化剂纸】：纸上（第 8 格）放着「物质模块是催化剂」，且类型 matter_flow_condensation ∈ SHANHAI_TYPES（= 山海自己的机器） ⇒ 落 ModuleLevelCondition（要求模块等级 ≥ 3，等级取自 MODULE_LEVELS）。⚠️ 门槛【不占输入槽】。若本条应当"只是纯催化剂（.notConsumable）"，改 SHANHAI_TYPES 即可，一处生效。
    {
        id: 'shanhai:pf/matter_flow_condensation_97',
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
    // ▶ PF.txt 第 98 条（本次新增）｜元件「处理样板HV」｜纸：类型「光子分离」／耗时「10s」｜输出 1x shanhai:muon
    // 🧪 物质模块【不消耗·等级门槛·纸上有催化剂纸】：纸上（第 3 格）放着「物质模块是催化剂」，且类型 photon_separation ∈ SHANHAI_TYPES（= 山海自己的机器） ⇒ 落 ModuleLevelCondition（要求模块等级 ≥ 3，等级取自 MODULE_LEVELS）。⚠️ 门槛【不占输入槽】。若本条应当"只是纯催化剂（.notConsumable）"，改 SHANHAI_TYPES 即可，一处生效。
    // 🔴 纸「中微子产出概率3%」⇒ shanhai:muon_neutrino 改成 chancedOutput(300, 100)。⚠️ 单位：本包 chance 是【万分比】，10000=100% ⇒ 3% = 300（不是 3000）。⚠️ 第二个 int 不是"加成上限"，是【每超频一级的加成量 tierChanceBoost】（字节码实证：GTRecipeBuilder.chancedOutput 把 iload_3 写进字段 tierChanceBoost）。100 = GTCEu 自己"5% 档副产"的标准值（本包 414 条实际配方 chance=500/boost=100）。用户 2026-09-26 裁决：吃加成 ⇒ 第二参不能是 0，本文件取 100。
    {
        id: 'shanhai:pf/muon',
        type: 'photon_separation',
        circuit: 3,
        notConsumable: ['1x gtceu:hv_field_generator'],
        moduleLevelRequirement: '1x shanhai:material_deduction_module',
        moduleLevelFallbackCatalyst: '1x shanhai:material_deduction_module',
        itemInputs: ['4x shanhai:photon_rainbow'],
        inputFluids: [],
        itemOutputs: ['1x shanhai:muon'],
        outputFluids: ['shanhai:zero_point_energy 4000'],
        chancedOutputs: [{ item: '1x shanhai:muon_neutrino', chance: 300, tierChanceBoost: 100 }],
        duration: 200,
        EUt: 512
    }
,
    // ▶ PF.txt 第 99 条（本次新增）｜元件「处理样板EV」｜纸：类型「物质流凝结」／耗时「10s」｜输出 
    // 🧪 物质模块【不消耗·等级门槛·纸上有催化剂纸】：纸上（第 6 格）放着「物质模块是催化剂」，且类型 matter_flow_condensation ∈ SHANHAI_TYPES（= 山海自己的机器） ⇒ 落 ModuleLevelCondition（要求模块等级 ≥ 4，等级取自 MODULE_LEVELS）。⚠️ 门槛【不占输入槽】。若本条应当"只是纯催化剂（.notConsumable）"，改 SHANHAI_TYPES 即可，一处生效。
    {
        id: 'shanhai:pf/matter_flow_condensation_99',
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
    // ▶ PF.txt 第 100 条（本次新增）｜元件「处理样板EV」｜纸：类型「物质锻造」／耗时「10s」｜输出 1x shanhai:proton
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
    // ▶ PF.txt 第 101 条（本次新增）｜元件「处理样板EV」｜纸：类型「原初奇点反演」／耗时「3s」｜输出 1x thetornproductionline:celestial_secret_deducing_module_hv
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
    // ▶ PF.txt 第 102 条（本次新增）｜元件「处理样板EV」｜纸：类型「物质锻造」／耗时「3s」｜输出 1x shanhai:electron_neutrino + 1x shanhai:neutron
    // 🧪 物质模块【不消耗·等级门槛·纸上有催化剂纸】：纸上（第 4 格）放着「物质模块是催化剂」，且类型 matter_forging ∈ SHANHAI_TYPES（= 山海自己的机器） ⇒ 落 ModuleLevelCondition（要求模块等级 ≥ 5，等级取自 MODULE_LEVELS）。⚠️ 门槛【不占输入槽】。若本条应当"只是纯催化剂（.notConsumable）"，改 SHANHAI_TYPES 即可，一处生效。
    {
        id: 'shanhai:pf/electron_neutrino',
        type: 'matter_forging',
        circuit: 6,
        notConsumable: [],
        moduleLevelRequirement: '1x shanhai:material_recombination_module',
        moduleLevelFallbackCatalyst: '1x shanhai:material_recombination_module',
        itemInputs: ['1x shanhai:electron', '1x shanhai:proton'],
        inputFluids: ['shanhai:zero_point_energy 4000'],
        itemOutputs: ['1x shanhai:electron_neutrino', '1x shanhai:neutron'],
        outputFluids: [],
        chancedOutputs: [],
        duration: 60,
        EUt: 2048
    }
,
    // ▶ PF.txt 第 103 条（本次新增）｜元件「处理样板EV」｜纸：类型「世线电路板组装」／耗时「3s」｜输出 1x shanhai:wl_board_lv
    // [SHANHAI-DESC] 电路批产模块MK1是催化剂 ⇒ 落 `.notConsumable(1x thetornproductionline:circult_process_module_1)`（**不消耗**，数量照纸上一字未改；物品 id = thetornproductionline:circult_process_module_1）
    {
        id: 'shanhai:pf/wl_board_lv_2',
        type: 'wl_board_circuit_assembly',
        circuit: 32,
        notConsumable: ['1x thetornproductionline:circult_process_module_1'],
        itemInputs: ['2x shanhai:wl_board_ulv'],
        inputFluids: [],
        itemOutputs: ['1x shanhai:wl_board_lv'],
        outputFluids: [],
        chancedOutputs: [],
        duration: 60,
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
    // ▶ PF.txt 第 105 条（本次新增）｜元件「处理样板EV」｜纸：类型「原初奇点反演」／耗时「60s」｜输出 1x thetornproductionline:circult_process_module_1
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
    // ▶ PF.txt 第 106 条（本次新增）｜元件「处理样板EV」｜纸：类型「物质锻造」／耗时「10s」｜输出 1x shanhai:neutron
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
    // ▶ PF.txt 第 107 条（本次新增）｜元件「处理样板EV」｜纸：类型「世线电路板组装」／耗时「3s」｜输出 1x shanhai:wl_board_ulv
    // [SHANHAI-DESC] 电路批产模块MK1是催化剂 ⇒ 落 `.notConsumable(1x thetornproductionline:circult_process_module_1)`（**不消耗**，数量照纸上一字未改；物品 id = thetornproductionline:circult_process_module_1）
    {
        id: 'shanhai:pf/wl_board_ulv_2',
        type: 'wl_board_circuit_assembly',
        circuit: 32,
        notConsumable: ['1x thetornproductionline:circult_process_module_1'],
        itemInputs: ['1x shanhai:first_light'],
        inputFluids: [],
        itemOutputs: ['1x shanhai:wl_board_ulv'],
        outputFluids: [],
        chancedOutputs: [],
        duration: 60,
        EUt: 2048
    }
,
    // ▶ PF.txt 第 108 条（本次新增）｜元件「处理样板EV」｜纸：类型「世线电路板组装」／耗时「3s」｜输出 1x shanhai:wl_board_mv
    // [SHANHAI-DESC] 电路批产模块MK1是催化剂 ⇒ 落 `.notConsumable(1x thetornproductionline:circult_process_module_1)`（**不消耗**，数量照纸上一字未改；物品 id = thetornproductionline:circult_process_module_1）
    {
        id: 'shanhai:pf/wl_board_mv_2',
        type: 'wl_board_circuit_assembly',
        circuit: 32,
        notConsumable: ['1x thetornproductionline:circult_process_module_1'],
        itemInputs: ['2x shanhai:wl_board_lv'],
        inputFluids: [],
        itemOutputs: ['1x shanhai:wl_board_mv'],
        outputFluids: [],
        chancedOutputs: [],
        duration: 60,
        EUt: 2048
    }
,
    // ▶ PF.txt 第 109 条（本次新增）｜元件「处理样板EV」｜纸：类型「世线电路板组装」／耗时「3s」｜输出 1x shanhai:wl_board_ev
    // [SHANHAI-DESC] 电路批产模块MK1是催化剂 ⇒ 落 `.notConsumable(1x thetornproductionline:circult_process_module_1)`（**不消耗**，数量照纸上一字未改；物品 id = thetornproductionline:circult_process_module_1）
    {
        id: 'shanhai:pf/wl_board_ev',
        type: 'wl_board_circuit_assembly',
        circuit: 32,
        notConsumable: ['1x thetornproductionline:circult_process_module_1'],
        itemInputs: ['2x shanhai:wl_board_hv'],
        inputFluids: [],
        itemOutputs: ['1x shanhai:wl_board_ev'],
        outputFluids: [],
        chancedOutputs: [],
        duration: 60,
        EUt: 2048
    }
,
    // ▶ PF.txt 第 110 条（本次新增）｜元件「处理样板EV」｜纸：类型「世线震荡收集」／耗时「60s」｜输出 1x shanhai:worldline_residual_fragment
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
    // ▶ PF.txt 第 111 条（本次新增）｜元件「处理样板EV」｜纸：类型「原初因果编织」／耗时「3s」｜输出 1x gtlcore:world_fragments_nether
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
    // ▶ PF.txt 第 112 条（本次新增）｜元件「处理样板EV」｜纸：类型「原初世线切割」／耗时「60s」｜输出 1x shanhai:thread_shard_2
    // 🧪 物质模块【不消耗·等级门槛·纸上有催化剂纸】：纸上（第 6 格）放着「物质模块是催化剂」，且类型 worldline_cutting ∈ SHANHAI_TYPES（= 山海自己的机器） ⇒ 落 ModuleLevelCondition（要求模块等级 ≥ 5，等级取自 MODULE_LEVELS）。⚠️ 门槛【不占输入槽】。若本条应当"只是纯催化剂（.notConsumable）"，改 SHANHAI_TYPES 即可，一处生效。
    // 🔴 ✅ 纸上写了「物质模块是催化剂」⇒ 按 2026-10-03 判据（只看纸）**不消耗**，落等级门槛；本条产出 ∈ 残片族（shanhai:thread_shard_2 =「世线的运用」） ⇒ 与 2026-10-01 那版判据（看产出）的结论【也一致】，两个口径不打架。
    {
        id: 'shanhai:pf/thread_shard_2',
        type: 'worldline_cutting',
        circuit: 1,
        notConsumable: [],
        moduleLevelRequirement: '1x shanhai:material_recombination_module',
        moduleLevelFallbackCatalyst: '1x shanhai:material_recombination_module',
        itemInputs: ['1x shanhai:worldline_residual_fragment', '16x shanhai:eta_meson', '4x shanhai:proton'],
        inputFluids: [],
        itemOutputs: ['1x shanhai:thread_shard_2'],
        outputFluids: [],
        chancedOutputs: [],
        duration: 1200,
        EUt: 2048
    }
,
    // ▶ PF.txt 第 113 条（本次新增）｜元件「处理样板EV」｜纸：类型「物质模块铸造」／耗时「60s」｜输出 1x shanhai:material_recombination_module
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
    // ▶ PF.txt 第 114 条（本次新增）｜元件「处理样板EV」｜纸：类型「世线电路板组装」／耗时「15s」｜输出 1x shanhai:wl_board_ev
    // 🧪 物质模块【不消耗·等级门槛·纸上有催化剂纸】：纸上（第 10 格）放着「物质模块是催化剂」，且类型 wl_board_circuit_assembly ∈ SHANHAI_TYPES（= 山海自己的机器） ⇒ 落 ModuleLevelCondition（要求模块等级 ≥ 4，等级取自 MODULE_LEVELS）。⚠️ 门槛【不占输入槽】。若本条应当"只是纯催化剂（.notConsumable）"，改 SHANHAI_TYPES 即可，一处生效。
    {
        id: 'shanhai:pf/wl_board_ev_2',
        type: 'wl_board_circuit_assembly',
        circuit: 1,
        notConsumable: [],
        moduleLevelRequirement: '1x shanhai:virtual_image_material_module',
        moduleLevelFallbackCatalyst: '1x shanhai:virtual_image_material_module',
        itemInputs: ['1x kubejs:ev_universal_circuit', '4x shanhai:proton', '16x shanhai:gluon', '1x shanhai:eta_meson', '4x shanhai:neutron'],
        inputFluids: ['shanhai:matter_fluid_advanced 2000'],
        itemOutputs: ['1x shanhai:wl_board_ev'],
        outputFluids: [],
        chancedOutputs: [],
        duration: 300,
        EUt: 2048
    }
,
    // ▶ PF.txt 第 115 条（本次新增）｜元件「处理样板EV」｜纸：类型「物质锻造」／耗时「10s」｜输出 1x shanhai:eta_meson
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
    // ▶ PF.txt 第 116 条（本次新增）｜元件「处理样板EV」｜纸：类型「渔场」／耗时「3s」｜输出 64x minecraft:cod + 64x minecraft:salmon + 64x minecraft:tropical_fish + 32x minecraft:pufferfish + 32x minecraft:experience_bottle + 4x minecraft:name_tag + 4x minecraft:nautilus_shell + 8x minecraft:lily_pad + 16x minecraft:leather + 64x minecraft:rotten_flesh + 32x minecraft:stick + 32x minecraft:string + 16x minecraft:bone + 2x gtceu:ancient_gold_coin + 64x minecraft:ink_sac + 1x kubejs:zero_point_module_fragments + 1x avaritia:neutron_nugget + 1x gtceu:damascus_steel_dust + 64x minecraft:kelp + 32x gtceu:meat_dust + 1x minecraft:heart_of_the_sea
    // 🧪 物质模块【不消耗·真催化剂·纸上有催化剂纸】：纸上（第 3 格）放着「物质模块是催化剂」，但类型 fishing_ground 不在 SHANHAI_TYPES 里（= 非山海的机器） ⇒ 落 .notConsumable(...) 真催化剂，不设等级门槛。
    // 🔴 该配方类型 `gtceu:fishing_ground` 不在 SHANHAI_TYPES 里（= 非山海的机器） ⇒ 按 2026-10-03 判据（纸上有「物质模块是催化剂」⇒ 不消耗、形态保持现状）落 `.notConsumable(...)` 真催化剂（不设等级门槛）。
    {
        id: 'shanhai:pf/cod',
        type: 'fishing_ground',
        circuit: 0,
        notConsumable: ['64x gtceu:ev_field_generator', '1x shanhai:material_recombination_module'],
        itemInputs: [],
        inputFluids: [],
        itemOutputs: ['64x minecraft:cod', '64x minecraft:salmon', '64x minecraft:tropical_fish', '32x minecraft:pufferfish', '32x minecraft:experience_bottle', '4x minecraft:name_tag', '4x minecraft:nautilus_shell', '8x minecraft:lily_pad', '16x minecraft:leather', '64x minecraft:rotten_flesh', '32x minecraft:stick', '32x minecraft:string', '16x minecraft:bone', '2x gtceu:ancient_gold_coin', '64x minecraft:ink_sac', '1x kubejs:zero_point_module_fragments', '1x avaritia:neutron_nugget', '1x gtceu:damascus_steel_dust', '64x minecraft:kelp', '32x gtceu:meat_dust', '1x minecraft:heart_of_the_sea'],
        outputFluids: [],
        chancedOutputs: [],
        duration: 60,
        EUt: 2048
    }
,
    // ▶ PF.txt 第 117 条（本次新增）｜元件「处理样板EV」｜纸：类型「原初奇点反演」／耗时「60s」｜输出 1x thetornproductionline:ultimate_world_fragment_miner_module
    // 🧪 物质模块【消耗·纸上无催化剂纸】：纸上（无）【没有】「物质模块是催化剂」 ⇒ 按 2026-10-03 判据（只看纸）模块留在 itemInputs 里被正常消耗，不挂催化剂、不设门槛。
    {
        id: 'shanhai:pf/ultimate_world_fragment_miner_module',
        type: 'primordial_singularity_inversion',
        circuit: 28,
        notConsumable: [],
        itemInputs: ['64x gtceu:large_fragment_world_collection_machine', '1x thetornproductionline:celestial_secret_deducing_module_ev', '2x thetornproductionline:advanced_world_fragment_miner_module', '1x shanhai:material_recombination_module', '1024x gtceu:titanium_drill_head'],
        inputFluids: [],
        itemOutputs: ['1x thetornproductionline:ultimate_world_fragment_miner_module'],
        outputFluids: [],
        chancedOutputs: [],
        duration: 1200,
        EUt: 2048
    }
,
    // ▶ PF.txt 第 118 条（本次新增）｜元件「处理样板EV」｜纸：类型「物质锻造」／耗时「10s」｜输出 1x shanhai:pion
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
    // ▶ PF.txt 第 119 条（本次新增）｜元件「处理样板EV」｜纸：类型「原初奇点反演」／耗时「60s」｜输出 1x thetornproductionline:greenhouse_speed_cultivation_module
    // 🧪 物质模块【消耗·纸上无催化剂纸】：纸上（无）【没有】「物质模块是催化剂」 ⇒ 按 2026-10-03 判据（只看纸）模块留在 itemInputs 里被正常消耗，不挂催化剂、不设门槛。
    {
        id: 'shanhai:pf/greenhouse_speed_cultivation_module',
        type: 'primordial_singularity_inversion',
        circuit: 28,
        notConsumable: [],
        itemInputs: ['4x shanhai:thread_shard_2', '256x gtceu:large_greenhouse', '4x shanhai:wl_board_ev', '1x shanhai:material_recombination_module'],
        inputFluids: [],
        itemOutputs: ['1x thetornproductionline:greenhouse_speed_cultivation_module'],
        outputFluids: [],
        chancedOutputs: [],
        duration: 1200,
        EUt: 2048
    }
,
    // ▶ PF.txt 第 120 条（本次新增）｜元件「处理样板ULV」｜纸：类型「原初物质重组」／耗时「3s」｜输出 4x gtceu:brass_ingot
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
    // ▶ PF.txt 第 121 条（本次新增）｜元件「处理样板ULV」｜纸：类型「原初物质重组」／耗时「3s」｜输出 2x gtceu:wrought_iron_ingot
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
    // ▶ PF.txt 第 122 条（本次新增）｜元件「处理样板ULV」｜纸：类型「原初物质重组」／耗时「3s」｜输出 2x minecraft:paper
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
    // ▶ PF.txt 第 123 条（本次新增）｜元件「处理样板ULV」｜纸：类型「原初物质重组」／耗时「3s」｜输出 3x gtceu:invar_ingot
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
    // ▶ PF.txt 第 124 条（本次新增）｜元件「处理样板ULV」｜纸：类型「原初物质重组」／耗时「3s」｜输出 2x gtceu:red_alloy_ingot
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
    // ▶ PF.txt 第 125 条（本次新增）｜元件「处理样板ULV」｜纸：类型「原初物质重组」／耗时「3s」｜输出 1x gtceu:compressed_fireclay
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
,
    // ▶ PF.txt 第 126 条（本次新增）｜元件「处理样板ULV」｜纸：类型「原初物质重组」／耗时「3s」｜输出 4x gtceu:tin_alloy_ingot
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
    // ▶ PF.txt 第 127 条（本次新增）｜元件「处理样板ULV」｜纸：类型「原初物质重组」／耗时「3s」｜输出 1x gtceu:annealed_copper_ingot
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
    // ▶ PF.txt 第 128 条（本次新增）｜元件「处理样板ULV」｜纸：类型「原初物质重组」／耗时「3s」｜输出 2x gtceu:steel_ingot
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
    // ▶ PF.txt 第 129 条（本次新增）｜元件「处理样板ULV」｜纸：类型「原初物质重组」／耗时「3s」｜输出 1x gtceu:conductive_alloy_ingot
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
    // ▶ PF.txt 第 130 条（本次新增）｜元件「处理样板ULV」｜纸：类型「原初物质重组」／耗时「3s」｜输出 4x gtceu:bronze_ingot
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
    // ▶ PF.txt 第 131 条（本次新增）｜元件「处理样板ULV」｜纸：类型「原初物质重组」／耗时「3s」｜输出 1x gtceu:glass_tube
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
    // ▶ PF.txt 第 132 条（本次新增）｜元件「处理样板ULV」｜纸：类型「原初物质重组」／耗时「3s」｜输出 2x gtceu:cupronickel_ingot
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
    // ▶ PF.txt 第 133 条（本次新增）｜元件「处理样板ULV」｜纸：类型「原初物质重组」／耗时「3s」｜输出 1x gtceu:pulsating_alloy_ingot
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
]

// -----------------------------------------------------------------------------
// 注册：工作台配方（老 ②③④⑤⑥ = **历史值 2026-09-26 的 5 条**：它们建文件时就在，不随 PF.txt 变化，故无法现算）
// -----------------------------------------------------------------------------
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
// 注册：GT 机器配方（老 3 条 + 新增 106 条 = 109 条）
// -----------------------------------------------------------------------------
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
//            // 🔴 概率产出（本次新增能力）：#11 / #98（2 条）的「产出概率纸」
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
// =============================================================================
// 🆕 2026-10-04 用户点单（逐字）：「帮我添加一条η介子产电的配方，1个η介子产电128A，MAX，
//                              同时生产16000mb真空零点能，耗时1秒，这是游戏不需要那么严谨」
//   · **128A MAX** = 128 × GTValues.V[GTValues.MAX] = 128 × 2147483648 = **274877906944**
//     ⇒ 与上面那条 1A 的同款写法：**负数 = 发电**
//   · **1 秒 = 20 tick**（GTCEu 的 duration 单位是 tick）
//   · 副产流体 = `shanhai:zero_point_energy` **16000 mB**
//     （类型 `primordial_power_generator` 的 IO 上限 = (2,2,2,2) ⇒ 1 物品入 ＋ 1 流体出，够）
//   ⚠️ 与上面同一条纪律：**一条失败不连坐**（独立 try/catch）；只在 _smoke-mixin 沙盒里跑。
// =============================================================================
ServerEvents.recipes(function (event) {
    var gtr = event.recipes.gtceu
    try {
        gtr['primordial_power_generator']('shanhai:zero_point_power_eta_meson')
            .itemInputs('1x shanhai:eta_meson')
            .duration(20)
            .EUt(-274877906944)
            .outputFluids('shanhai:zero_point_energy 16000')
        console.info('[SHANHAI-SPEC] eta_power register ok=1'
            + ' recipe=shanhai:zero_point_power_eta_meson'
            + ' input=shanhai:eta_meson 1x'
            + ' EUt=-274877906944 (128A MAX)'
            + ' outFluid=shanhai:zero_point_energy 16000mB'
            + ' duration=20t')
    } catch (e) {
        console.error('[SHANHAI-SPEC] eta_power FAILED: ' + e)
    }
})

// ═════════════════════════════════════════════════════════════════════════════
// 🆕 2026-10-04 用户点单（原话逐字）——两条新配方（本条由 temp\bio-recipes\gen_bio_recipes.mjs
//    从游戏导出表【逐条生成】，39 项 / 57 项，非手敲）：
//   「电压等级，耗时不变，输出不变，修改运行配方种类为原初生物演化协议，
//     同时删去其中的输入，只保留模块作为催化剂」
//   ＋「那个输出格子不够用，你需要扩大」（⇒ 见 ShanhaiRecipeTypes.java 里 PRIMORDIAL_BIOLOGICAL_CORE
//     的 setMaxIOSize 由 (6,3,3,3) 放宽到 (6,64,3,3)；3 格装不下 39/57 项物品出）
//
// 三处改动（只有这三处）：
//   ① 配方类型  gtceu:greenhouse / gtceu:biological_simulation  ⇒  primordial_biological_core
//      （中文名「原初生物演化协议」，机器 = 原初生物核心）
//   ② 删掉【全部流体输入】
//   ③ 只保留模块、且当催化剂 .notConsumable(...)
//   ✅ EUt / duration / 物品输出【逐字不变】（照导出表原样）
//
// 🔴 宿主脚本（[server_scripts]dgy.js L568-594 / L1116-1124）【一个字都没改】：
//    原配方 thetornproductionline:greenhouse_speed_cultivation_use /
//            thetornproductionline:high_speed_bio_data_use 仍然存在、仍然跑在原来的机器上。
//    本段是【新增】的两条 shanhai:primordial_bio/… 配方。
// ═════════════════════════════════════════════════════════════════════════════

// ─────────────────────────────────────────────────────────────────────────────
// 温室高速培养（温室）：thetornproductionline:greenhouse_speed_cultivation_use  ⇒  shanhai:primordial_bio/greenhouse_speed_cultivation_use
//   宿主原文（只读参考，【未改动】）：dgy.js L568-594
//   导出表（唯一权威）：local\kubejs\export\added_recipes\thetornproductionline/greenhouse/greenhouse_speed_cultivation_use.json
//   本片段【逐条生成】自该导出表：39 项物品出，chance/boost 原样照抄（boost 一律 0）。
//   改动点② 删掉全部流体输入：minecraft:water 16000（导出表 inputs.fluid 1 项 ⇒ 本配方 0 项）
//   改动点③ 只保留模块、且当催化剂：.notConsumable('thetornproductionline:greenhouse_speed_cultivation_module')
//   改动点① 配方类型：gtceu:greenhouse ⇒ primordial_biological_core（原初生物演化协议；机器 = 原初生物核心）
//   ✅ EUt / duration / 输出【逐字不变】：EUt=30 duration=200t 输出=39 项
var greenhouseOut = [
    ['64x minecraft:oak_log', 10000],
    ['64x gtceu:rubber_log', 10000],
    ['64x minecraft:birch_log', 10000],
    ['64x minecraft:jungle_log', 10000],
    ['64x minecraft:acacia_log', 10000],
    ['64x minecraft:dark_oak_log', 10000],
    ['64x minecraft:mangrove_log', 10000],
    ['64x minecraft:cherry_log', 10000],
    ['64x minecraft:spruce_log', 10000],
    ['2x minecraft:oak_sapling', 10000],
    ['2x gtceu:rubber_sapling', 10000],
    ['2x minecraft:birch_sapling', 10000],
    ['2x minecraft:jungle_sapling', 10000],
    ['2x minecraft:acacia_sapling', 10000],
    ['2x minecraft:dark_oak_sapling', 10000],
    ['2x minecraft:mangrove_propagule', 10000],
    ['2x minecraft:cherry_sapling', 10000],
    ['2x minecraft:spruce_sapling', 10000],
    ['64x gtceu:sticky_resin', 10000],
    ['64x minecraft:apple', 10000],
    ['64x gtceu:plant_ball', 10000],
    ['64x minecraft:pumpkin', 10000],
    ['64x minecraft:beetroot', 10000],
    ['64x minecraft:melon', 10000],
    ['64x minecraft:sweet_berries', 10000],
    ['64x minecraft:glow_berries', 10000],
    ['64x minecraft:wheat', 10000],
    ['64x minecraft:carrot', 10000],
    ['64x minecraft:sugar_cane', 10000],
    ['64x minecraft:kelp', 10000],
    ['64x minecraft:cactus', 10000],
    ['64x minecraft:potato', 10000],
    ['64x minecraft:cocoa_beans', 10000],
    ['64x minecraft:brown_mushroom', 10000],
    ['64x minecraft:red_mushroom', 10000],
    ['64x minecraft:nether_wart', 10000],
    ['64x minecraft:bamboo', 10000],
    ['64x minecraft:vine', 10000],
    ['64x minecraft:sea_pickle', 10000],
]

ServerEvents.recipes(function (event) {
    var gtr = event.recipes.gtceu
    var ok = 0
    var bad = 0
    var errList = ''
    try {
        var b1 = gtr['primordial_biological_core']('shanhai:primordial_bio/greenhouse_speed_cultivation_use')
            .notConsumable('thetornproductionline:greenhouse_speed_cultivation_module')
            .EUt(30)
            .duration(200)
        var j
        for (j = 0; j < greenhouseOut.length; j++) {
            var e1 = greenhouseOut[j]
            b1.chancedOutput(e1[0], e1[1], 0)
        }
        ok = ok + 1
    } catch (e) {
        bad = bad + 1
        errList = errList + 'shanhai:primordial_bio/greenhouse_speed_cultivation_use => ' + e
    }
    console.info('[SHANHAI-SPEC] primordial_bio greenhouse register ok=' + ok + ' failed=' + bad
        + ' id=shanhai:primordial_bio/greenhouse_speed_cultivation_use'
        + ' type=primordial_biological_core(原初生物演化协议)'
        + ' catalyst=thetornproductionline:greenhouse_speed_cultivation_module (notConsumable)'
        + ' fluidIn=0'
        + ' EUt=30 duration=200t'
        + ' itemOuts=39')
    if (bad > 0) {
        console.error('[SHANHAI-SPEC] primordial_bio greenhouse FAILED: ' + errList)
    }
})

// ─────────────────────────────────────────────────────────────────────────────
// 高速生物数据（生物模拟）：thetornproductionline:high_speed_bio_data_use  ⇒  shanhai:primordial_bio/high_speed_bio_data_use
//   宿主原文（只读参考，【未改动】）：dgy.js L1116-1124
//   导出表（唯一权威）：local\kubejs\export\added_recipes\thetornproductionline/biological_simulation/high_speed_bio_data_use.json
//   本片段【逐条生成】自该导出表：57 项物品出，chance/boost 原样照抄（boost 一律 0）。
//   改动点② 删掉全部流体输入：gtceu:biomass 16000 ＋ gtceu:biohmediumsterilized 1000（导出表 inputs.fluid 2 项 ⇒ 本配方 0 项）
//   改动点③ 只保留模块、且当催化剂：.notConsumable('thetornproductionline:high_speed_bio_data_module')
//   改动点① 配方类型：gtceu:biological_simulation ⇒ primordial_biological_core（原初生物演化协议；机器 = 原初生物核心）
//   ✅ EUt / duration / 输出【逐字不变】：EUt=491520 duration=20t 输出=57 项
var bioDataOut = [
    ['40x minecraft:beef', 7500],
    ['40x minecraft:leather', 2500],
    ['40x minecraft:mutton', 8000],
    ['40x minecraft:white_wool', 5000],
    ['40x minecraft:porkchop', 8000],
    ['40x minecraft:chicken', 7500],
    ['40x minecraft:feather', 4000],
    ['40x minecraft:egg', 1000],
    ['40x minecraft:rabbit', 7000],
    ['40x minecraft:rabbit_hide', 1000],
    ['40x minecraft:rabbit_foot', 500],
    ['40x minecraft:leather', 5000],
    ['40x minecraft:leather', 5000],
    ['40x minecraft:bamboo', 5000],
    ['40x minecraft:cod', 5000],
    ['40x minecraft:salmon', 5000],
    ['40x minecraft:string', 5000],
    ['40x minecraft:string', 7000],
    ['40x minecraft:spider_eye', 2000],
    ['40x minecraft:bone', 7500],
    ['40x minecraft:arrow', 6500],
    ['40x minecraft:rotten_flesh', 7500],
    ['40x minecraft:iron_ingot', 600],
    ['40x minecraft:carrot', 1500],
    ['40x minecraft:potato', 1500],
    ['40x minecraft:rotten_flesh', 7500],
    ['40x minecraft:iron_ingot', 600],
    ['40x minecraft:carrot', 1500],
    ['40x minecraft:potato', 1500],
    ['40x minecraft:rotten_flesh', 7500],
    ['40x minecraft:copper_ingot', 600],
    ['40x minecraft:rotten_flesh', 7500],
    ['40x minecraft:iron_ingot', 600],
    ['40x minecraft:carrot', 1500],
    ['40x minecraft:potato', 1500],
    ['40x minecraft:gunpowder', 8000],
    ['40x minecraft:slime_ball', 5000],
    ['40x minecraft:ender_pearl', 500],
    ['40x minecraft:stick', 5000],
    ['40x minecraft:gunpowder', 3500],
    ['40x minecraft:sugar', 3500],
    ['40x minecraft:glass_bottle', 3500],
    ['40x minecraft:redstone', 600],
    ['40x minecraft:glowstone_dust', 600],
    ['40x minecraft:spider_eye', 600],
    ['40x minecraft:emerald', 1000],
    ['40x minecraft:rotten_flesh', 7500],
    ['40x minecraft:gold_ingot', 600],
    ['40x minecraft:gold_nugget', 1000],
    ['40x minecraft:blaze_rod', 500],
    ['40x minecraft:gunpowder', 6000],
    ['40x minecraft:ghast_tear', 600],
    ['40x minecraft:bone', 7500],
    ['40x minecraft:coal', 6500],
    ['40x minecraft:wither_skeleton_skull', 500],
    ['1x minecraft:nether_star', 1500],
    ['1x minecraft:dragon_egg', 10000],
]

ServerEvents.recipes(function (event) {
    var gtr = event.recipes.gtceu
    var ok = 0
    var bad = 0
    var errList = ''
    try {
        var b1 = gtr['primordial_biological_core']('shanhai:primordial_bio/high_speed_bio_data_use')
            .notConsumable('thetornproductionline:high_speed_bio_data_module')
            .EUt(491520)
            .duration(20)
        var j
        for (j = 0; j < bioDataOut.length; j++) {
            var e1 = bioDataOut[j]
            b1.chancedOutput(e1[0], e1[1], 0)
        }
        ok = ok + 1
    } catch (e) {
        bad = bad + 1
        errList = errList + 'shanhai:primordial_bio/high_speed_bio_data_use => ' + e
    }
    console.info('[SHANHAI-SPEC] primordial_bio bioData register ok=' + ok + ' failed=' + bad
        + ' id=shanhai:primordial_bio/high_speed_bio_data_use'
        + ' type=primordial_biological_core(原初生物演化协议)'
        + ' catalyst=thetornproductionline:high_speed_bio_data_module (notConsumable)'
        + ' fluidIn=0'
        + ' EUt=491520 duration=20t'
        + ' itemOuts=57')
    if (bad > 0) {
        console.error('[SHANHAI-SPEC] primordial_bio bioData FAILED: ' + errList)
    }
})


// =============================================================================
// 🆕 2026-10-04 用户点单：原初深空汲取（16 条，每一种世界碎片一条）
// =============================================================================
// 用户原话（逐字）：
//   「为原初深空汲取核心新增配方类型：原初深空汲取，并为此配方种类新增配方：
//     终极世界碎片高速采集模块和世界碎片作为催化剂，可以获取对应世界碎片的矿物，岩石（注意新增），
//     流体，气体（注意新增），并设计合适的输出槽便于jei全部显示，这个工作我在dgy中做过，可以参考配方」
// 后续补充（逐字）：
//   「不需要新增物品，原版有对应的岩石或者气体就添加进配方，没有就不添加」
//   「相比于碎片世界采集，星核剥离是4倍配方，而我们的原初深空汲取在中间，是碎片世界采集产量的2倍」
//
// ── 一、配方类型 ─────────────────────────────────────────────────────────────
//   gtceu:primordial_deep_space_extraction（中文名「原初深空汲取」），
//   注册在 ShanhaiRecipeTypes.java（第 46 条真类型），挂载点 = 原初深空汲取核心
//   （shanhai:primordial_deep_space_extraction_core，第 29 台，与集气室/大型集气室/虚空聚流反应/虚空流体钻机同组）。
//   该类型的 IO 上限 = (2, 92, 0, 7)（物品入/物品出/流体入/流体出），
//   取值 = 下面 16 条配方【逐条现算】的最大占用（见每条日志行的 used= 字段）⇒ JEI 能把产出全部显示出来。
//   🔴 2026-10-04【订正】—— 上面那行是旧句，按本工程惯例【原样保留】，以本条为准：
//      本轮加回三项尾部产出后，四元组是 (2, 108, 0, 8)，真源 = ShanhaiRecipeTypes.java 里那句
//      `.setMaxIOSize(2, 108, 0, 8)`（它随「改用专属 .rtui」那一刀一起定下来的，不是本文件定的）。
//      ⇒ 本轮起 used= 的最大值 = 2/95/0/7（overworld 95 ≤ 108，流体出仍是 7 ≤ 8，fluid入 0 = 0）。
//      ⚠️ 本文件【一个字都没有碰 Java】，只是把三处读数改对：used= 的算法 +3、日志里的 cap= 串、
//         以及下面 §四 的订正块。
//
// ── 二、输入（用户口径：只留那两个催化剂）────────────────────────────────────
//   · thetornproductionline:ultimate_world_fragment_miner_module  ⇒ .notConsumable（终极世界碎片高速采集模块）
//   · gtlcore:world_fragments_<维度>                              ⇒ .notConsumable（对应的世界碎片）
//   ⚠️ notConsumable 照样占输入槽 ⇒ 物品入 = 2（不是 0）。
//   · 不传 circuit（dgy 那套用了 30/31/32 号电路，本次口径是「只留那两个催化剂」）。
//   · EUt / duration 照 dgy 那套的形态：EUt=8、duration=1t。
//
// ── 二·补、2026-10-04 用户点单第 2 条：三项尾部产出【照 dgy 原文加回】────────────
//   用户原话（逐字）：
//     「星核剥离4 倍是原版就有的，不需要你做，深空汲取需要有碎片自回和晶体复产」
//   ⇒ 每条配方末尾照 dgy 的 addFragmentRecipes() 原文补三项（**形式与数值都照抄**）：
//     · 碎片自回  1x <该条配方自己的那个碎片>   chance 5000（= 50%） tierBoost 0
//     · 采掘结晶  1x gtlcore:mining_crystal     chance 500（= 5%）  tierBoost 5
//     · 奇珍结晶  1x gtlcore:treasures_crystal  chance 500（= 5%）  tierBoost 5
//   ⚠️ chance 单位是【万分比】（10000 = 100%，本工程既有口径）。
//   ⚠️ 自回项的物品 = 【该条配方 R.fragment 那一个】，逐条对应，不是统一写死一个碎片。
//   ⚠️ 这三项各占 1 个【物品输出】格 ⇒ 物品出 = 原「矿物＋岩石」条数 + 3（见 used= 的算法）。
//   ⚠️ 用户同句里的「星核剥离 4 倍是原版就有的，不需要你做」= 裁决 ⇒ 下面 §四 ② 那条
//      「不按 1:2:4 反推去改数字」的口径【继续有效、不动】。
//
// ── 三、产出的四类，各自的【权威来源】与倍率（全部来自实例导出表，没猜）──────
//   矿物  ← added_recipes/thetornproductionline/fragment_world_collection/ultimate_world_fragment_*.json
//          （与 [server_scripts]dgy.js 的 allWorldFragmentData 逐条一致，实测 16/16 identical）  ×2
//   岩石  ← recipes/gtceu/fragment_world_collection/sky_block_digging_<组>_* 的岩石栏目               ×2
//          （组号↔碎片：1=主世界 2=下界 3=末地 4=反应堆 5=月球 6=火星 7=金星 8=水星 9=谷神星 10=木卫一
//            11=木卫三 12=冥王星 13=土卫二 14=土卫六 15=霜冻星 16=巴纳德C）
//   流体  ← 同「矿物」那份（dgy 的 fluid_outputs）                                                  ×2
//   气体  ← recipes/gtladditions/voidflux_reaction/*（虚空聚流反应）的 UXV 档                          ×2
//          维度↔气体由 recipes/gtceu/gas_collector/*.json 的 recipeConditions 唯一确定：
//            主世界→gtceu:air、下界→gtceu:nether_air、末地→gtceu:ender_air、巴纳德C→gtceu:barnarda_air
//          ⚠️ 气体在 GTCEu 里是【流体】（走流体输出槽），不是物品。
//          ⚠️ 其余 12 个维度【环境里没有任何对应的气体】⇒ 那 12 条就【不含气体】这一类，
//             不硬凑、不拿近似物顶替（用户 2026-10-04 口径）。
//
// ── 四、口径取舍（如实交代，别当成「忘了写」）────────────────────────────────
//   🔴 2026-10-04【订正】—— 下面 ① 那条口径【已被用户推翻】，旧句按惯例原样保留，以本条为准：
//     用户点单第 2 条原话见 §二·补。⇒ 本轮把 dgy 那三项【加回】16 条配方。
//     ⚠️ ① 的理由「碎片本来就不消耗，再自回没有意义」在本类型上【不成立】：dgy 原文的自回项
//        本来就是**额外产出**（输入 notConsumable 不消耗 ＋ 另吐 1 个碎片），语义是"复现/捡回"
//        而不是"补偿消耗"。用户是拿 dgy 当参照拍的这一刀，所以照抄它的形式与数值。
//     ① dgy 那套每条末尾还有「碎片自回 50% ＋ mining_crystal 5% ＋ treasures_crystal 5%」三项；
//      本次【不抄】—— 用户点名产出就是「矿物 岩石 流体 气体」四类，且两个输入都是 notConsumable
//      （碎片本来就不消耗，再自回没有意义）。
//   ② 用户同句里的「星核剥离 = 碎片世界采集 ×4」 **实测不成立**：星核剥离
//      gtceu:star_core_stripper 的逐项计数恒为 1x（导出表读数），而碎片世界采集是 8~64x
//      ⇒ 本文件只取【已成立的 ×2】，没有按 1:2:4 反推去改任何一个数字。
//
// ── 五、Rhino 纪律 ───────────────────────────────────────────────────────────
//   · 全局一律 var；无模板串 / 箭头函数 / 解构 / ?.
//   · 16 条各自独立 try/catch ⇒ 一条失败不连坐。
//   · 数据（产出表）与注册代码分开，便于逐条核对。
// =============================================================================

// 每条 = [世界碎片后缀, 催化剂模块, [[矿物...]], [[岩石...]], [[流体...]], [[气体...]]]
// ═══ PDS-OUTPUT-TABLE-BEGIN（生成区 · 幂等 · 手工勿改）═══
// ─────────────────────────────────────────────────────────────────────────────
// 🆕 2026-10-04 第二轮裁决：本表【整表重算】，数据源从 dgy 的 ultimate_* 换成
//    【实例导出表 · 碎片世界采集基线】= local\kubejs\export\recipes\gtceu\
//    fragment_world_collection\sky_block_digging_*.json（实测 238 条）。
//
// 用户逐字裁决（本轮口径，以此为准；上面 §一~§五 的旧句按本工程惯例原样保留、不再生效）：
//   ① 「不要按照电路一一对应，我要每个世界碎片只有一条配方，然后这一条配方的输出是其在
//       碎片世界采集的所有种类输出之和，再加上其对应的空气，因此只需要补我们现有的就可以，
//       0号和1号真的有」
//   ② 「所有固体全部相加」            ⇒ 本表 ores+rocks = 同 id 逐项相加（跨全部电路档）
//   ③ 「每个流体都只取钛钻头那一档」  ⇒ 本表 fluids = 该流体在 gtceu:titanium_drill_head
//       那一档的量（不是 max、不是求和、不含 kubejs:machine_casing_grinding_head 的
//       2147483647 哨兵档）」
//   ④ 「注意，主世界（可能还有其他世界碎片）有一些带火箭的配方（这些是获取其他世界碎片
//       才用的，我们不需要），不要把那些也算进去了」
//       ⇒ make_world_fragments_1..15 + make_damascus_steel_dust 共 16 条【一律排除】
//
// 落地规则：
//   · 范围 = 只取文件名前缀 sky_block_digging_ 的 238 条（判据：碎片＋电路，钻孔族另加钻头）。
//   · 固体 = 同 id 跨全部档相加，然后【×2】（我们 = 碎片世界采集的 2 倍），落 chance=10000。
//     依据（用户）：相加后的量若再乘原概率，实际到手会低于相加值 ⇒ 一律按【必得】落。
//   · 分类：ores = id 以 gtceu:raw_ / minecraft:raw_ 开头；rocks = 其余固体（岩石/岩粉/
//     泥土/植物/特殊掉落）。两类在注册端走同一条 chancedOutput 路径，只是给人看的分类。
//   · 液体 = 逐流体一条，取【钛钻头档】值 ×2；含 1000/16000/128000/1024000 四档的那一族里
//     恒取第 2 档。若某流体【没有钻头档】（全库仅主世界 minecraft:lava 一例，出处
//     sky_block_digging_special_1.json，chance 500=5%），则取它唯一那一档的原值 ×2。
//   · 空气 = 现状不动（主世界 gtceu:air 4096000 / 下界 gtceu:nether_air 4096000 /
//     末地 gtceu:ender_air 4096000 / 巴纳德C gtceu:barnarda_air 25600000），其余 12 世界不加。
//     ⚠️ 巴纳德C 的碎集基线里另有 gtceu:barnarda_air 16000@20%（special_7）⇒ 与空气同 id，
//        按「有空气的世界用现状值」取 25600000，碎集那份【不重复计入】。
//   · 尾部 3 项（自回 50% / mining_crystal 5% / treasures_crystal 5%）不写在本表，
//     由下面注册区逐条追加，各计 1 项、不按档数累加（与碎集基线里 10 个世界只有 2 项尾部
//     的差异如实保留：硬口径是「保持一项」，且现状已按 dgy 原文给 3 项）。
//   · EUt=8 / duration=1 / 输入 = 终极世界碎片高速采集模块 + 对应世界碎片（均 notConsumable）。
//   · 本表为【生成区】：口径不变时重复生成结果逐字节相同（幂等）；勿手工改表内数字，
//     要改口径请改生成器并重跑。
// ─────────────────────────────────────────────────────────────────────────────
var pdsRecipes = [
    {
        world: 'overworld',
        zh: '主世界',
        fragment: 'gtlcore:world_fragments_overworld',
        catalyst: 'thetornproductionline:ultimate_world_fragment_miner_module',
        // 固体·矿物 67 项（碎集基线 54 条 · 同 id 全部档相加 ×2 · 全落 10000）
        ores: [
            ['288x gtceu:raw_coal', 10000],
            ['256x gtceu:raw_chalcopyrite', 10000],
            ['256x gtceu:raw_oilsands', 10000],
            ['192x gtceu:raw_magnetite', 10000],
            ['160x gtceu:raw_apatite', 10000],
            ['160x gtceu:raw_tin', 10000],
            ['144x gtceu:raw_cassiterite', 10000],
            ['128x gtceu:raw_galena', 10000],
            ['128x gtceu:raw_goethite', 10000],
            ['128x gtceu:raw_graphite', 10000],
            ['128x gtceu:raw_kyanite', 10000],
            ['128x gtceu:raw_redstone', 10000],
            ['96x gtceu:raw_almandine', 10000],
            ['96x gtceu:raw_basaltic_mineral_sand', 10000],
            ['96x gtceu:raw_bentonite', 10000],
            ['96x gtceu:raw_cassiterite_sand', 10000],
            ['96x gtceu:raw_diamond', 10000],
            ['96x gtceu:raw_garnierite', 10000],
            ['96x gtceu:raw_glauconite_sand', 10000],
            ['96x gtceu:raw_grossular', 10000],
            ['96x gtceu:raw_lazurite', 10000],
            ['96x gtceu:raw_mica', 10000],
            ['96x gtceu:raw_red_garnet', 10000],
            ['96x gtceu:raw_rock_salt', 10000],
            ['96x gtceu:raw_ruby', 10000],
            ['96x gtceu:raw_silver', 10000],
            ['96x gtceu:raw_soapstone', 10000],
            ['96x gtceu:raw_tricalcium_phosphate', 10000],
            ['96x gtceu:raw_vanadium_magnetite', 10000],
            ['64x gtceu:raw_amethyst', 10000],
            ['64x gtceu:raw_asbestos', 10000],
            ['64x gtceu:raw_cobaltite', 10000],
            ['64x gtceu:raw_fullers_earth', 10000],
            ['64x gtceu:raw_garnet_sand', 10000],
            ['64x gtceu:raw_granitic_mineral_sand', 10000],
            ['64x gtceu:raw_lapis', 10000],
            ['64x gtceu:raw_lepidolite', 10000],
            ['64x gtceu:raw_nickel', 10000],
            ['64x gtceu:raw_olivine', 10000],
            ['64x gtceu:raw_pentlandite', 10000],
            ['64x gtceu:raw_pyrolusite', 10000],
            ['64x gtceu:raw_pyrope', 10000],
            ['64x gtceu:raw_salt', 10000],
            ['64x gtceu:raw_sapphire', 10000],
            ['64x gtceu:raw_sodalite', 10000],
            ['64x gtceu:raw_spessartine', 10000],
            ['64x gtceu:raw_talc', 10000],
            ['64x gtceu:raw_yellow_garnet', 10000],
            ['48x gtceu:raw_hematite', 10000],
            ['48x gtceu:raw_pyrite', 10000],
            ['48x gtceu:raw_yellow_limonite', 10000],
            ['48x gtceu:raw_zeolite', 10000],
            ['48x minecraft:raw_iron', 10000],
            ['32x gtceu:raw_calcite', 10000],
            ['32x gtceu:raw_cinnabar', 10000],
            ['32x gtceu:raw_diatomite', 10000],
            ['32x gtceu:raw_green_sapphire', 10000],
            ['32x gtceu:raw_gypsum', 10000],
            ['32x gtceu:raw_lead', 10000],
            ['32x gtceu:raw_malachite', 10000],
            ['32x gtceu:raw_opal', 10000],
            ['32x gtceu:raw_pollucite', 10000],
            ['32x gtceu:raw_realgar', 10000],
            ['32x gtceu:raw_spodumene', 10000],
            ['32x gtceu:raw_tantalite', 10000],
            ['32x minecraft:raw_copper', 10000],
            ['32x minecraft:raw_gold', 10000],
        ],
        // 固体·岩石与杂物 24 项（同上口径：同 id 全部档相加 ×2 · 全落 10000）
        rocks: [
            ['1408x gtceu:deepslate_dust', 10000],
            ['1408x gtceu:stone_dust', 10000],
            ['1408x minecraft:deepslate', 10000],
            ['1408x minecraft:stone', 10000],
            ['128x minecraft:clay_ball', 10000],
            ['32x minecraft:dirt', 10000],
            ['32x minecraft:gravel', 10000],
            ['32x minecraft:sand', 10000],
            ['16x minecraft:birch_sapling', 10000],
            ['16x minecraft:cherry_sapling', 10000],
            ['16x minecraft:jungle_sapling', 10000],
            ['16x minecraft:mangrove_propagule', 10000],
            ['16x minecraft:oak_sapling', 10000],
            ['16x minecraft:spruce_sapling', 10000],
            ['16x minecraft:string', 10000],
            ['16x minecraft:sugar_cane', 10000],
            ['8x gtceu:rubber_sapling', 10000],
            ['8x minecraft:leather', 10000],
            ['8x minecraft:soul_sand', 10000],
            ['4x minecraft:sculk_sensor', 10000],
            ['4x minecraft:sculk_shrieker', 10000],
            ['2x minecraft:honeycomb', 10000],
            ['2x minecraft:kelp', 10000],
            ['2x minecraft:totem_of_undying', 10000],
        ],
        // 液体 7 项（逐流体取钛钻头档 ×2）
        fluids: [
            'gtceu:natural_gas 56000',
            'gtceu:oil 96000',
            'gtceu:oil_heavy 64000',
            'gtceu:oil_light 96000',
            'gtceu:oil_medium 96000',
            'gtceu:salt_water 32000',
            'minecraft:lava 2000',   // 无钻头档 ⇒ 取该流体唯一档原值 ×2
        ],
        // 气体 1 项（空气按现状，不动）
        gases: [
            'gtceu:air 4096000',
        ],
    },
    {
        world: 'nether',
        zh: '下界',
        fragment: 'gtlcore:world_fragments_nether',
        catalyst: 'thetornproductionline:ultimate_world_fragment_miner_module',
        // 固体·矿物 37 项（碎集基线 23 条 · 同 id 全部档相加 ×2 · 全落 10000）
        ores: [
            ['192x gtceu:raw_nether_quartz', 10000],
            ['192x gtceu:raw_quartzite', 10000],
            ['152x gtceu:raw_beryllium', 10000],
            ['144x gtceu:raw_bastnasite', 10000],
            ['144x gtceu:raw_tetrahedrite', 10000],
            ['128x gtceu:raw_grossular', 10000],
            ['128x gtceu:raw_redstone', 10000],
            ['128x gtceu:raw_sulfur', 10000],
            ['128x gtceu:raw_wulfenite', 10000],
            ['104x gtceu:raw_emerald', 10000],
            ['96x gtceu:raw_blue_topaz', 10000],
            ['96x gtceu:raw_certus_quartz', 10000],
            ['96x gtceu:raw_goethite', 10000],
            ['96x gtceu:raw_pyrite', 10000],
            ['96x gtceu:raw_pyrolusite', 10000],
            ['96x gtceu:raw_ruby', 10000],
            ['72x gtceu:raw_diatomite', 10000],
            ['72x gtceu:raw_electrotine', 10000],
            ['72x gtceu:raw_saltpeter', 10000],
            ['72x minecraft:raw_copper', 10000],
            ['64x gtceu:raw_chalcocite', 10000],
            ['64x gtceu:raw_hematite', 10000],
            ['64x gtceu:raw_molybdenite', 10000],
            ['64x gtceu:raw_topaz', 10000],
            ['64x gtceu:raw_yellow_limonite', 10000],
            ['56x gtceu:raw_monazite', 10000],
            ['56x gtceu:raw_neodymium', 10000],
            ['40x gtceu:raw_alunite', 10000],
            ['40x gtceu:raw_stibnite', 10000],
            ['32x gtceu:raw_barite', 10000],
            ['32x gtceu:raw_bornite', 10000],
            ['32x gtceu:raw_cinnabar', 10000],
            ['32x gtceu:raw_molybdenum', 10000],
            ['32x gtceu:raw_powellite', 10000],
            ['32x gtceu:raw_sphalerite', 10000],
            ['32x gtceu:raw_tantalite', 10000],
            ['32x minecraft:raw_gold', 10000],
        ],
        // 固体·岩石与杂物 11 项（同上口径：同 id 全部档相加 ×2 · 全落 10000）
        rocks: [
            ['768x gtceu:basalt_dust', 10000],
            ['768x gtceu:netherrack_dust', 10000],
            ['768x minecraft:basalt', 10000],
            ['768x minecraft:netherrack', 10000],
            ['32x minecraft:soul_sand', 10000],
            ['32x minecraft:soul_soil', 10000],
            ['24x minecraft:nether_wart', 10000],
            ['16x minecraft:blaze_rod', 10000],
            ['16x minecraft:crimson_fungus', 10000],
            ['16x minecraft:warped_fungus', 10000],
            ['8x minecraft:ancient_debris', 10000],
        ],
        // 液体 2 项（逐流体取钛钻头档 ×2）
        fluids: [
            'gtceu:natural_gas 96000',
            'minecraft:lava 80000',
        ],
        // 气体 1 项（空气按现状，不动）
        gases: [
            'gtceu:nether_air 4096000',
        ],
    },
    {
        world: 'end',
        zh: '末地',
        fragment: 'gtlcore:world_fragments_end',
        catalyst: 'thetornproductionline:ultimate_world_fragment_miner_module',
        // 固体·矿物 17 项（碎集基线 7 条 · 同 id 全部档相加 ×2 · 全落 10000）
        ores: [
            ['192x gtceu:raw_naquadah', 10000],
            ['160x gtceu:raw_pitchblende', 10000],
            ['128x gtceu:raw_magnetite', 10000],
            ['128x gtceu:raw_scheelite', 10000],
            ['96x gtceu:raw_bauxite', 10000],
            ['96x gtceu:raw_bornite', 10000],
            ['96x gtceu:raw_ilmenite', 10000],
            ['96x gtceu:raw_tungstate', 10000],
            ['96x gtceu:raw_uraninite', 10000],
            ['96x gtceu:raw_vanadium_magnetite', 10000],
            ['64x gtceu:raw_aluminium', 10000],
            ['64x gtceu:raw_cooperite', 10000],
            ['64x gtceu:raw_platinum', 10000],
            ['64x gtceu:raw_plutonium', 10000],
            ['32x gtceu:raw_lithium', 10000],
            ['32x gtceu:raw_palladium', 10000],
            ['32x minecraft:raw_gold', 10000],
        ],
        // 固体·岩石与杂物 8 项（同上口径：同 id 全部档相加 ×2 · 全落 10000）
        rocks: [
            ['768x gtceu:endstone_dust', 10000],
            ['768x minecraft:end_stone', 10000],
            ['32x minecraft:chorus_fruit', 10000],
            ['16x minecraft:shulker_shell', 10000],
            ['2x minecraft:chorus_flower', 10000],
            ['2x minecraft:dragon_breath', 10000],
            ['2x minecraft:dragon_egg', 10000],
            ['2x minecraft:dragon_head', 10000],
        ],
        // 液体 0 项（逐流体取钛钻头档 ×2）
        fluids: [
        ],
        // 气体 1 项（空气按现状，不动）
        gases: [
            'gtceu:ender_air 4096000',
        ],
    },
    {
        world: 'reactor',
        zh: '反应堆',
        fragment: 'gtlcore:world_fragments_reactor',
        catalyst: 'thetornproductionline:ultimate_world_fragment_miner_module',
        // 固体·矿物 21 项（碎集基线 9 条 · 同 id 全部档相加 ×2 · 全落 10000）
        ores: [
            ['204x gtceu:raw_quartzite', 10000],
            ['152x gtceu:raw_beryllium', 10000],
            ['128x gtceu:raw_blue_topaz', 10000],
            ['128x gtceu:raw_stibnite', 10000],
            ['128x gtceu:raw_sulfur', 10000],
            ['128x gtceu:raw_topaz', 10000],
            ['128x gtceu:raw_wulfenite', 10000],
            ['128x minecraft:raw_copper', 10000],
            ['108x gtceu:raw_barite', 10000],
            ['104x gtceu:raw_emerald', 10000],
            ['104x gtceu:raw_nether_quartz', 10000],
            ['96x gtceu:raw_certus_quartz', 10000],
            ['96x gtceu:raw_pyrite', 10000],
            ['72x gtceu:raw_diatomite', 10000],
            ['72x gtceu:raw_electrotine', 10000],
            ['72x gtceu:raw_saltpeter', 10000],
            ['64x gtceu:raw_molybdenite', 10000],
            ['40x gtceu:raw_alunite', 10000],
            ['32x gtceu:raw_molybdenum', 10000],
            ['32x gtceu:raw_powellite', 10000],
            ['32x gtceu:raw_sphalerite', 10000],
        ],
        // 固体·岩石与杂物 11 项（同上口径：同 id 全部档相加 ×2 · 全落 10000）
        rocks: [
            ['1056x minecraft:diorite', 10000],
            ['1024x gtceu:diorite_dust', 10000],
            ['32x ae2:sky_stone_block', 10000],
            ['32x gtceu:marble', 10000],
            ['32x gtceu:red_granite', 10000],
            ['32x minecraft:andesite', 10000],
            ['32x minecraft:dirt', 10000],
            ['32x minecraft:granite', 10000],
            ['32x minecraft:suspicious_gravel', 10000],
            ['32x minecraft:suspicious_sand', 10000],
            ['2x ae2:mysterious_cube', 10000],
        ],
        // 液体 0 项（逐流体取钛钻头档 ×2）
        fluids: [
        ],
        // 气体 0 项（空气按现状，不动）
        gases: [
        ],
    },
    {
        world: 'moon',
        zh: '月球',
        fragment: 'gtlcore:world_fragments_moon',
        catalyst: 'thetornproductionline:ultimate_world_fragment_miner_module',
        // 固体·矿物 10 项（碎集基线 14 条 · 同 id 全部档相加 ×2 · 全落 10000）
        ores: [
            ['256x gtceu:raw_uraninite', 10000],
            ['144x gtceu:raw_bastnasite', 10000],
            ['128x gtceu:raw_thorium', 10000],
            ['96x gtceu:raw_bauxite', 10000],
            ['96x gtceu:raw_ilmenite', 10000],
            ['96x gtceu:raw_pitchblende', 10000],
            ['64x gtceu:raw_aluminium', 10000],
            ['56x gtceu:raw_monazite', 10000],
            ['56x gtceu:raw_neodymium', 10000],
            ['32x gtceu:raw_plutonium', 10000],
        ],
        // 固体·岩石与杂物 1 项（同上口径：同 id 全部档相加 ×2 · 全落 10000）
        rocks: [
            ['512x ad_astra:moon_stone', 10000],
        ],
        // 液体 2 项（逐流体取钛钻头档 ×2）
        fluids: [
            'gtceu:helium 96000',
            'gtceu:helium_3 57600',
        ],
        // 气体 0 项（空气按现状，不动）
        gases: [
        ],
    },
    {
        world: 'mars',
        zh: '火星',
        fragment: 'gtlcore:world_fragments_mars',
        catalyst: 'thetornproductionline:ultimate_world_fragment_miner_module',
        // 固体·矿物 10 项（碎集基线 8 条 · 同 id 全部档相加 ×2 · 全落 10000）
        ores: [
            ['128x gtceu:raw_scheelite', 10000],
            ['108x gtceu:raw_apatite', 10000],
            ['108x gtceu:raw_tricalcium_phosphate', 10000],
            ['96x gtceu:raw_bornite', 10000],
            ['96x gtceu:raw_tungstate', 10000],
            ['64x gtceu:raw_cooperite', 10000],
            ['64x gtceu:raw_platinum', 10000],
            ['32x gtceu:raw_lithium', 10000],
            ['32x gtceu:raw_palladium', 10000],
            ['32x gtceu:raw_pyrochlore', 10000],
        ],
        // 固体·岩石与杂物 1 项（同上口径：同 id 全部档相加 ×2 · 全落 10000）
        rocks: [
            ['384x ad_astra:mars_stone', 10000],
        ],
        // 液体 1 项（逐流体取钛钻头档 ×2）
        fluids: [
            'gtceu:radon 25600',
        ],
        // 气体 0 项（空气按现状，不动）
        gases: [
        ],
    },
    {
        world: 'venus',
        zh: '金星',
        fragment: 'gtlcore:world_fragments_venus',
        catalyst: 'thetornproductionline:ultimate_world_fragment_miner_module',
        // 固体·矿物 9 项（碎集基线 8 条 · 同 id 全部档相加 ×2 · 全落 10000）
        ores: [
            ['128x gtceu:raw_desh', 10000],
            ['128x gtceu:raw_magnesite', 10000],
            ['128x gtceu:raw_sulfur', 10000],
            ['100x gtceu:raw_olivine', 10000],
            ['96x gtceu:raw_pyrite', 10000],
            ['52x gtceu:raw_bentonite', 10000],
            ['52x gtceu:raw_glauconite_sand', 10000],
            ['52x gtceu:raw_magnetite', 10000],
            ['32x gtceu:raw_sphalerite', 10000],
        ],
        // 固体·岩石与杂物 1 项（同上口径：同 id 全部档相加 ×2 · 全落 10000）
        rocks: [
            ['384x ad_astra:venus_stone', 10000],
        ],
        // 液体 1 项（逐流体取钛钻头档 ×2）
        fluids: [
            'gtceu:sulfuric_acid 80000',
        ],
        // 气体 0 项（空气按现状，不动）
        gases: [
        ],
    },
    {
        world: 'mercury',
        zh: '水星',
        fragment: 'gtlcore:world_fragments_mercury',
        catalyst: 'thetornproductionline:ultimate_world_fragment_miner_module',
        // 固体·矿物 7 项（碎集基线 7 条 · 同 id 全部档相加 ×2 · 全落 10000）
        ores: [
            ['128x gtceu:raw_cobalt', 10000],
            ['96x gtceu:raw_garnierite', 10000],
            ['64x gtceu:raw_calorite', 10000],
            ['64x gtceu:raw_cobaltite', 10000],
            ['64x gtceu:raw_magnesite', 10000],
            ['64x gtceu:raw_nickel', 10000],
            ['32x gtceu:raw_pentlandite', 10000],
        ],
        // 固体·岩石与杂物 1 项（同上口径：同 id 全部档相加 ×2 · 全落 10000）
        rocks: [
            ['256x ad_astra:mercury_stone', 10000],
        ],
        // 液体 1 项（逐流体取钛钻头档 ×2）
        fluids: [
            'gtceu:deuterium 96000',
        ],
        // 气体 0 项（空气按现状，不动）
        gases: [
        ],
    },
    {
        world: 'ceres',
        zh: '谷神星',
        fragment: 'gtlcore:world_fragments_ceres',
        catalyst: 'thetornproductionline:ultimate_world_fragment_miner_module',
        // 固体·矿物 12 项（碎集基线 24 条 · 同 id 全部档相加 ×2 · 全落 10000）
        ores: [
            ['144x gtceu:raw_bastnasite', 10000],
            ['128x gtceu:raw_ostrum', 10000],
            ['128x gtceu:raw_quartzite', 10000],
            ['128x gtceu:raw_wulfenite', 10000],
            ['128x minecraft:raw_gold', 10000],
            ['96x gtceu:raw_certus_quartz', 10000],
            ['64x gtceu:raw_molybdenite', 10000],
            ['56x gtceu:raw_monazite', 10000],
            ['56x gtceu:raw_neodymium', 10000],
            ['32x gtceu:raw_barite', 10000],
            ['32x gtceu:raw_molybdenum', 10000],
            ['32x gtceu:raw_powellite', 10000],
        ],
        // 固体·岩石与杂物 1 项（同上口径：同 id 全部档相加 ×2 · 全落 10000）
        rocks: [
            ['512x kubejs:ceresstone', 10000],
        ],
        // 液体 4 项（逐流体取钛钻头档 ×2）
        fluids: [
            'gtceu:krypton 80000',
            'gtceu:neon 80000',
            'gtceu:radon 80000',
            'gtceu:xenon 80000',
        ],
        // 气体 0 项（空气按现状，不动）
        gases: [
        ],
    },
    {
        world: 'io',
        zh: '木卫一',
        fragment: 'gtlcore:world_fragments_io',
        catalyst: 'thetornproductionline:ultimate_world_fragment_miner_module',
        // 固体·矿物 12 项（碎集基线 9 条 · 同 id 全部档相加 ×2 · 全落 10000）
        ores: [
            ['192x gtceu:raw_naquadah', 10000],
            ['128x gtceu:raw_sulfur', 10000],
            ['128x gtceu:raw_trona', 10000],
            ['100x gtceu:raw_olivine', 10000],
            ['96x gtceu:raw_pyrite', 10000],
            ['64x gtceu:raw_celestine', 10000],
            ['64x gtceu:raw_cooperite', 10000],
            ['64x gtceu:raw_plutonium', 10000],
            ['52x gtceu:raw_bentonite', 10000],
            ['52x gtceu:raw_glauconite_sand', 10000],
            ['52x gtceu:raw_magnetite', 10000],
            ['32x gtceu:raw_sphalerite', 10000],
        ],
        // 固体·岩石与杂物 1 项（同上口径：同 id 全部档相加 ×2 · 全落 10000）
        rocks: [
            ['512x kubejs:iostone', 10000],
        ],
        // 液体 1 项（逐流体取钛钻头档 ×2）
        fluids: [
            'gtceu:coal_gas 96000',
        ],
        // 气体 0 项（空气按现状，不动）
        gases: [
        ],
    },
    {
        world: 'ganymede',
        zh: '木卫三',
        fragment: 'gtlcore:world_fragments_ganymede',
        catalyst: 'thetornproductionline:ultimate_world_fragment_miner_module',
        // 固体·矿物 15 项（碎集基线 10 条 · 同 id 全部档相加 ×2 · 全落 10000）
        ores: [
            ['128x gtceu:raw_blue_topaz', 10000],
            ['128x gtceu:raw_sulfur', 10000],
            ['128x gtceu:raw_topaz', 10000],
            ['104x gtceu:raw_nether_quartz', 10000],
            ['96x gtceu:raw_bauxite', 10000],
            ['96x gtceu:raw_ilmenite', 10000],
            ['96x gtceu:raw_pyrite', 10000],
            ['96x gtceu:raw_zircon', 10000],
            ['76x gtceu:raw_barite', 10000],
            ['76x gtceu:raw_quartzite', 10000],
            ['64x gtceu:raw_aluminium', 10000],
            ['64x gtceu:raw_grossular', 10000],
            ['48x gtceu:raw_pyrolusite', 10000],
            ['48x gtceu:raw_tantalite', 10000],
            ['32x gtceu:raw_sphalerite', 10000],
        ],
        // 固体·岩石与杂物 1 项（同上口径：同 id 全部档相加 ×2 · 全落 10000）
        rocks: [
            ['640x kubejs:ganymedestone', 10000],
        ],
        // 液体 1 项（逐流体取钛钻头档 ×2）
        fluids: [
            'gtceu:hydrochloric_acid 112000',
        ],
        // 气体 0 项（空气按现状，不动）
        gases: [
        ],
    },
    {
        world: 'pluto',
        zh: '冥王星',
        fragment: 'gtlcore:world_fragments_pluto',
        catalyst: 'thetornproductionline:ultimate_world_fragment_miner_module',
        // 固体·矿物 11 项（碎集基线 10 条 · 同 id 全部档相加 ×2 · 全落 10000）
        ores: [
            ['256x gtceu:raw_uraninite', 10000],
            ['192x gtceu:raw_naquadah', 10000],
            ['128x gtceu:raw_stibnite', 10000],
            ['128x gtceu:raw_thorium', 10000],
            ['128x minecraft:raw_copper', 10000],
            ['96x gtceu:raw_garnierite', 10000],
            ['96x gtceu:raw_pitchblende', 10000],
            ['96x gtceu:raw_plutonium', 10000],
            ['64x gtceu:raw_cobaltite', 10000],
            ['64x gtceu:raw_nickel', 10000],
            ['32x gtceu:raw_pentlandite', 10000],
        ],
        // 固体·岩石与杂物 1 项（同上口径：同 id 全部档相加 ×2 · 全落 10000）
        rocks: [
            ['640x kubejs:plutostone', 10000],
        ],
        // 液体 1 项（逐流体取钛钻头档 ×2）
        fluids: [
            'gtceu:nitric_acid 96000',
        ],
        // 气体 0 项（空气按现状，不动）
        gases: [
        ],
    },
    {
        world: 'enceladus',
        zh: '土卫二',
        fragment: 'gtlcore:world_fragments_enceladus',
        catalyst: 'thetornproductionline:ultimate_world_fragment_miner_module',
        // 固体·矿物 11 项（碎集基线 13 条 · 同 id 全部档相加 ×2 · 全落 10000）
        ores: [
            ['108x gtceu:raw_apatite', 10000],
            ['108x gtceu:raw_tricalcium_phosphate', 10000],
            ['100x gtceu:raw_olivine', 10000],
            ['96x gtceu:raw_bornite', 10000],
            ['64x gtceu:raw_cooperite', 10000],
            ['64x gtceu:raw_platinum', 10000],
            ['52x gtceu:raw_bentonite', 10000],
            ['52x gtceu:raw_glauconite_sand', 10000],
            ['52x gtceu:raw_magnetite', 10000],
            ['32x gtceu:raw_palladium', 10000],
            ['32x gtceu:raw_pyrochlore', 10000],
        ],
        // 固体·岩石与杂物 1 项（同上口径：同 id 全部档相加 ×2 · 全落 10000）
        rocks: [
            ['384x kubejs:enceladusstone', 10000],
        ],
        // 液体 2 项（逐流体取钛钻头档 ×2）
        fluids: [
            'gtceu:chlorine 134400',
            'gtceu:fluorine 102400',
        ],
        // 气体 0 项（空气按现状，不动）
        gases: [
        ],
    },
    {
        world: 'titan',
        zh: '土卫六',
        fragment: 'gtlcore:world_fragments_titan',
        catalyst: 'thetornproductionline:ultimate_world_fragment_miner_module',
        // 固体·矿物 13 项（碎集基线 19 条 · 同 id 全部档相加 ×2 · 全落 10000）
        ores: [
            ['128x gtceu:raw_desh', 10000],
            ['128x gtceu:raw_magnesite', 10000],
            ['128x gtceu:raw_uraninite', 10000],
            ['96x gtceu:raw_pitchblende', 10000],
            ['96x gtceu:raw_zircon', 10000],
            ['72x gtceu:raw_diatomite', 10000],
            ['72x gtceu:raw_electrotine', 10000],
            ['72x gtceu:raw_saltpeter', 10000],
            ['64x gtceu:raw_grossular', 10000],
            ['48x gtceu:raw_pyrolusite', 10000],
            ['48x gtceu:raw_tantalite', 10000],
            ['40x gtceu:raw_alunite', 10000],
            ['32x gtceu:raw_thorium', 10000],
        ],
        // 固体·岩石与杂物 1 项（同上口径：同 id 全部档相加 ×2 · 全落 10000）
        rocks: [
            ['512x kubejs:titanstone', 10000],
        ],
        // 液体 3 项（逐流体取钛钻头档 ×2）
        fluids: [
            'gtceu:benzene 51200',
            'gtceu:charcoal_byproducts 83200',
            'gtceu:methane 80000',
        ],
        // 气体 0 项（空气按现状，不动）
        gases: [
        ],
    },
    {
        world: 'glacio',
        zh: '霜冻星',
        fragment: 'gtlcore:world_fragments_glacio',
        catalyst: 'thetornproductionline:ultimate_world_fragment_miner_module',
        // 固体·矿物 28 项（碎集基线 10 条 · 同 id 全部档相加 ×2 · 全落 10000）
        ores: [
            ['144x gtceu:raw_bastnasite', 10000],
            ['128x gtceu:raw_cobalt', 10000],
            ['128x gtceu:raw_cooperite', 10000],
            ['128x gtceu:raw_ostrum', 10000],
            ['128x gtceu:raw_scheelite', 10000],
            ['128x gtceu:raw_sulfur', 10000],
            ['128x gtceu:raw_trona', 10000],
            ['128x gtceu:raw_wulfenite', 10000],
            ['128x minecraft:raw_gold', 10000],
            ['96x gtceu:raw_bornite', 10000],
            ['96x gtceu:raw_pyrite', 10000],
            ['96x gtceu:raw_tungstate', 10000],
            ['72x gtceu:raw_diatomite', 10000],
            ['72x gtceu:raw_electrotine', 10000],
            ['72x gtceu:raw_saltpeter', 10000],
            ['64x gtceu:raw_calorite', 10000],
            ['64x gtceu:raw_celestine', 10000],
            ['64x gtceu:raw_magnesite', 10000],
            ['64x gtceu:raw_molybdenite', 10000],
            ['64x gtceu:raw_platinum', 10000],
            ['56x gtceu:raw_monazite', 10000],
            ['56x gtceu:raw_neodymium', 10000],
            ['40x gtceu:raw_alunite', 10000],
            ['32x gtceu:raw_lithium', 10000],
            ['32x gtceu:raw_molybdenum', 10000],
            ['32x gtceu:raw_palladium', 10000],
            ['32x gtceu:raw_powellite', 10000],
            ['32x gtceu:raw_sphalerite', 10000],
        ],
        // 固体·岩石与杂物 3 项（同上口径：同 id 全部档相加 ×2 · 全落 10000）
        rocks: [
            ['1152x ad_astra:glacio_stone', 10000],
            ['2x ad_astra:ice_shard', 10000],
            ['2x kubejs:glacio_spirit', 10000],
        ],
        // 液体 0 项（逐流体取钛钻头档 ×2）
        fluids: [
        ],
        // 气体 0 项（空气按现状，不动）
        gases: [
        ],
    },
    {
        world: 'barnarda',
        zh: '巴纳德C',
        fragment: 'gtlcore:world_fragments_barnarda',
        catalyst: 'thetornproductionline:ultimate_world_fragment_miner_module',
        // 固体·矿物 18 项（碎集基线 13 条 · 同 id 全部档相加 ×2 · 全落 10000）
        ores: [
            ['192x gtceu:raw_naquadah', 10000],
            ['128x gtceu:raw_blue_topaz', 10000],
            ['128x gtceu:raw_quartzite', 10000],
            ['128x gtceu:raw_scheelite', 10000],
            ['128x gtceu:raw_sulfur', 10000],
            ['128x gtceu:raw_topaz', 10000],
            ['128x gtceu:raw_uraninite', 10000],
            ['108x gtceu:raw_apatite', 10000],
            ['108x gtceu:raw_tricalcium_phosphate', 10000],
            ['96x gtceu:raw_certus_quartz', 10000],
            ['96x gtceu:raw_plutonium', 10000],
            ['96x gtceu:raw_pyrite', 10000],
            ['96x gtceu:raw_thorium', 10000],
            ['96x gtceu:raw_tungstate', 10000],
            ['32x gtceu:raw_barite', 10000],
            ['32x gtceu:raw_lithium', 10000],
            ['32x gtceu:raw_pyrochlore', 10000],
            ['32x gtceu:raw_sphalerite', 10000],
        ],
        // 固体·岩石与杂物 3 项（同上口径：同 id 全部档相加 ×2 · 全落 10000）
        rocks: [
            ['896x minecraft:stone', 10000],
            ['2x kubejs:barnarda_leaves', 10000],
            ['2x kubejs:barnarda_log', 10000],
        ],
        // 液体 1 项（逐流体取钛钻头档 ×2）
        fluids: [
            'gtceu:unknowwater 19200',
        ],
        // 气体 1 项（空气按现状，不动）
        gases: [
            'gtceu:barnarda_air 25600000',
        ],
    },
]
// ═══ PDS-OUTPUT-TABLE-END ═══

// ═══════════════════════════════════════════════════════════════════════════════════
// 🆕 2026-10-04 三项尾部产出的【唯一真源】—— 数值与形式逐字取自 dgy 原文，不是估的
// ═══════════════════════════════════════════════════════════════════════════════════
// dgy 原文（[server_scripts]dgy.js 的 addFragmentRecipes()，本仓库副本
//   temp\reference\host-reference\[server_scripts]dgy.js:924-931 与实例那份逐字相同）：
//
//     // 碎片自回（50% 概率）
//     recipe.chancedOutput(`1x ${fragmentId}`, 5000, 0);
//
//     // 采掘结晶（5% 概率，tier boost 5）
//     recipe.chancedOutput('1x gtlcore:mining_crystal', 500, 5);
//
//     // 奇珍结晶（5% 概率）
//     recipe.chancedOutput('1x gtlcore:treasures_crystal', 500, 5);
//
// ⚠️ 唯一的形式偏离（如实交代，不是漏抄）：dgy 第 1 行用【模板串】`1x ${fragmentId}`，
//    而本文件手写区有硬纪律「无模板串」（§五；实测本手写区 backtick 命中 = 0）
//    ⇒ 落地写成 '1x ' + R.fragment。**物品与数量逐条对应关系完全一致**，只是拼串方式不同。
// ⚠️ chance 是【万分比】：10000 = 100% ⇒ 5000 = 50%、500 = 5%（与 dgy 原文同口径同数值）。
// ⚠️ tierBoost：自回 0（dgy 原文第 3 参）、两个结晶 5（dgy 原文第 3 参）。
// ⚠️ 这三项是【物品输出】⇒ 各占 1 格，共 +3（已计入下面 used= 的算法）。
// ⚠️ 两个晶体 id 已核实存在于实例导出注册表
//    <实例>\local\kubejs\export\registries\item.json（19257 个键，两个都是【精确键】命中）。
// ═══════════════════════════════════════════════════════════════════════════════════
var PDS_TAIL_OUT = 3
// 本类型的 IO 上限串（唯一真源 = ShanhaiRecipeTypes.java 的 .setMaxIOSize(2, 108, 0, 8)；
// 本文件【不碰 Java】，这里只是把日志里的读数与此对齐，避免日志报一个假的 cap）
var PDS_CAP = '2/108/0/8'

ServerEvents.recipes(function (event) {
    var gtr = event.recipes.gtceu
    var ok = 0
    var bad = 0
    var errList = ''
    var usedMax = [0, 0, 0, 0]
    for (var i = 0; i < pdsRecipes.length; i++) {
        var R = pdsRecipes[i]
        try {
            var b = gtr['primordial_deep_space_extraction']('shanhai:primordial_deep_space/' + R.world)
                .notConsumable(R.catalyst)
                .notConsumable(R.fragment)
                .EUt(8)
                .duration(1)
            var j
            for (j = 0; j < R.ores.length; j++) b.chancedOutput(R.ores[j][0], R.ores[j][1], 0)
            for (j = 0; j < R.rocks.length; j++) b.chancedOutput(R.rocks[j][0], R.rocks[j][1], 0)
            for (j = 0; j < R.fluids.length; j++) b.outputFluids(R.fluids[j])
            for (j = 0; j < R.gases.length; j++) b.outputFluids(R.gases[j])
            // 🆕 2026-10-04 三项尾部产出 —— 逐字照 dgy 的 addFragmentRecipes() 原文（见上面 §二·补）
            //   自回的物品 = 【本条配方自己那一个】R.fragment ⇒ 逐条对应，不是统一写死一个碎片。
            b.chancedOutput('1x ' + R.fragment, 5000, 0)
            b.chancedOutput('1x gtlcore:mining_crystal', 500, 5)
            b.chancedOutput('1x gtlcore:treasures_crystal', 500, 5)
            var used = [2, R.ores.length + R.rocks.length + PDS_TAIL_OUT, 0, R.fluids.length + R.gases.length]
            for (j = 0; j < 4; j++) if (used[j] > usedMax[j]) usedMax[j] = used[j]
            ok = ok + 1
            console.info('[SHANHAI-PDS] ' + R.world + '(' + R.zh + ') ok id=shanhai:primordial_deep_space/' + R.world
                + ' catalysts=' + R.catalyst + '+' + R.fragment
                + ' ores=' + R.ores.length + ' rocks=' + R.rocks.length
                + ' fluids=' + R.fluids.length + ' gases=' + R.gases.length
                + ' tail=' + R.fragment + '@5000' + ',gtlcore:mining_crystal@500'
                + ',gtlcore:treasures_crystal@500'
                + ' used=' + used.join('/') + ' cap=' + PDS_CAP)
        } catch (e) {
            bad = bad + 1
            errList = errList + ' | ' + R.world + '=>' + e
        }
    }
    console.info('[SHANHAI-PDS] 原初深空汲取 register ok=' + ok + ' failed=' + bad
        + ' type=primordial_deep_space_extraction(原初深空汲取)'
        + ' machine=shanhai:primordial_deep_space_extraction_core'
        + ' 峰值占用 used=' + usedMax.join('/') + ' cap=' + PDS_CAP)
    if (bad > 0) console.error('[SHANHAI-PDS] FAILED:' + errList)
})

// ═══ 手写区 结束 ═══
