// priority: 1
// =============================================================================
// [server_scripts]shanhai_blackhole_recipes.js
//   黑洞提取模块 —— 一条配方：把「元素周期表」整张表一次吐出来
//   （109 个元素粉 × 64 ＋ 17 个桶对应的流体 × 64,000 mB）
//
// ⚠️ 本文件【只写了仓库】，没有复制进实例；没有构建、没有部署、没碰 git、没起游戏。
// =============================================================================
// §0 五条口径（配方类型 / 输入 / 耗电 / 时长 / 产出）
// =============================================================================
//   配方类型 : gtceu:vision_matter_decompression（中文「视界物质解压」）
//              · 命名空间是 「gtceu」，**不是** 「shanhai」：注册体走 GTCEu 自己的
//                GTRecipeTypes.register(...) ⇒ 导出表 registries/recipe_type.json 里那个 key
//                就是 gtceu:vision_matter_decompression（本文件生成时已核对存在）。
//              · 类型定义 = shanhai-rewrite\src\main\java\com\shanhai\common\recipe\ShanhaiRecipeTypes.java:1577-1578
//              · KJS 里写 「gtr.vision_matter_decompression(...)」（点号取键），先例 =
//                [server_scripts]shanhai_editor_recipes.js:252 「gtr.matter_forging('shanhai:new_recipe_1')」
//                （同一条 register() 通道注册的自定义类型，已实测可用）。
//   输入     : 1x shanhai:reality_item_2 —— **催化剂、不消耗** ⇒ .notConsumable('1x shanhai:reality_item_2')
//              ＋ 编程电路 1      ⇒ .circuit(1)（参数必须是【数字】，见 shanhai_debug_test_recipes.js:90）
//              ⚠️ 这两个都算【输入格】：物品入上限 6 ⇒ 占 2 格，余 4 格。
//   耗电     : 2147483648 EU/t（= GTValues.V[GTValues.MAX] = 2^31 = MAX 档 1A；正数 = 耗电）
//              出处：[server_scripts]shanhai_recipes.js:3140-3141「MAX = 第 14 档 = 2147483648」
//   时长     : 20 tick（= 1 秒；GTCEu 的 duration 单位就是 tick）
//   产出     : 109 个 *_dust，每个 64 个
//              ＋ 17 个 *_bucket → 对应流体，每个 64,000 mB
//
// =============================================================================
// §1 产出清单的唯一真源（不是我编的，也不是凭中文名猜的）
// =============================================================================
//   实例里那张 FTB 任务参考表：
//     config\ftbquests\quests\chapters\5D8BD23AA2113DC2.snbt:2276   title: "元素周期表"
//   · 该 chapter 共 **118 个 quest（= 表里 118 格，subtitle 就是原子序数 1..118）**
//   · 但格子里的物品是 **126 条**：118 格里 7 格带了不止一个物品
//       Z1  = hydrogen_bucket + deuterium_bucket + tritium_bucket（3 条）
//       Z2  = helium_bucket + helium_3_bucket
//       Z22 = titanium_dust + titanium_50_dust
//       Z29 = copper_dust + copper76_dust
//       Z70 = ytterbium_dust + ytterbium_178_bucket
//       Z92 = uranium_dust + uranium_235_dust
//       Z94 = plutonium_dust + plutonium_241_dust
//     ⇒ 118 + 8 = **126 条**，其中 **109 个 *_dust ＋ 17 个 *_bucket**（109+17=126 ✓）
//   · 118 − 13 = 105 个基础元素粉 ＋ 4 个同位素粉（titanium_50 / copper76 / uranium_235 /
//     plutonium_241）= **109**；13 个没有粉的格子全是气体/液体元素，表里用桶代表。
//   · 桶一律换成对应流体：gtceu:xxx_bucket ⇒ 流体 gtceu:xxx（17 条逐个核过 fluid.json）。
//   · 本文件里 126 条 id 每条都对着实例导出注册表核过存在性：
//       local\kubejs\export\registries\item.json   (20,180 个)
//       local\kubejs\export\registries\fluid.json  (2,261 个)
//     ⇒ ABSENT = **0**（离线自检 temp\blackhole\selfcheck.mjs 会重跑一遍，见 §4）。
//   ⚠️ 顺序按原子序数 Z 重排（snbt 里的物理顺序是"表布局"，跟 Z 不一致）；
//      集合与真源完全相同，一条不多、一条不少。
//
// =============================================================================
// §2 IO 上限（该类型的 setMaxIOSize）
// =============================================================================
//   ShanhaiRecipeTypes.java:1578  .setMaxIOSize(6, 144, 3, 32)
//   四元组顺序 =（物品入, 物品出, 流体入, 流体出），由 GTRecipeType#setMaxIOSize 字节码钉死：
//     iload_1 → IO.IN  + ItemRecipeCapability      ⇒ 物品入
//     iload_2 → IO.OUT + ItemRecipeCapability      ⇒ 物品出
//     iload_3 → IO.IN  + FluidRecipeCapability     ⇒ 流体入
//     iload_4 → IO.OUT + FluidRecipeCapability     ⇒ 流体出
//   本配方占用 vs 上限：
//     物品入 2 / 6 ；物品出 109 / 144（余 35 格）；流体入 0 / 3 ；流体出 17 / 32（余 15 格）
//   ⚠️ 上限只影响 JEI/GUI 画多少格，不影响配方能不能注册/跑（GTCEu 不按上限校验配方）。
//
// =============================================================================
// §3 写法出处（不是自造格式）
// =============================================================================
//   · '64x <item>'         ← 物品数量在【前】，带 x。先例 [server_scripts]shanhai_editor_recipes.js:407-412
//   · '<fluid> <amount>'   ← 流体 id 在【前】、数量在【后】，空格分隔（**与物品相反**）。
//                            先例 [server_scripts]shanhai_paradigm_recipes.js:225
//                            .outputFluids('gtceu:hydrogen 2800', 'minecraft:water 7200', ...)
//   · 逐条 itemOutputs / outputFluids 的循环写法 ← [server_scripts]shanhai_recipes.js:2819-2824
//   · 一条配方一个 try/catch（一条失败不连坐）+ console.info 回执 ← 同文件 :2751-2860 的既有纪律
//   · ⚠️ 本文件**没有**用模板串/箭头函数/解构/let/const（Rhino 限制；
//        手写区纪律「无模板串」见 [server_scripts]shanhai_recipes.js:4195），只用 var。
//
// =============================================================================
// §4 离线自检（跑法）
// =============================================================================
//   node temp\blackhole\selfcheck.mjs
//     ⇒ ① 126 个 id 逐个 FOUND/ABSENT（对着导出注册表）
//        ② 粉 109 / 桶 17，且本文件里 itemOutputs 条目 109、outputFluids 条目 17
//        ③ 占格 vs 上限（109/144、17/32）与余量
//        ④ 语法检查（node --check，V8 解析器；另加引号/括号配平 + Rhino 禁用语法扫描）
//        ⑤ 负对照（改错 1 个 id / 少 1 条输出 ⇒ 必须报红）
//
// =============================================================================
// §5 🔴 并行放大风险（只提醒，本文件不处理）
// =============================================================================
//   本配方的两个输入【都不消耗】（notConsumable 催化剂 + 编程电路，后者 「.circuit(n)」
//   在字节码里就是 「notConsumable(...)」）⇒ 那条"按输入量算并行"的边界对它不成立，
//   并行只能靠【输出空间】和【电】兜（依据与数字见交付报告 §④，本文件不改引擎）。
// =============================================================================

// ─────────────────────────────────────────────────────────────────────────────
// 产出 ①：109 个元素粉（每个 64 个）—— 顺序按原子序数 Z
//   '64x gtceu:<material>_dust' ；注释里的 中文名-符号 逐字抄自 snbt 该格的 title
// ─────────────────────────────────────────────────────────────────────────────
var SHANHAI_BH_DUST_OUT = [
    '64x gtceu:lithium_dust', // Z3 锂-Li
    '64x gtceu:beryllium_dust', // Z4 铍-Be
    '64x gtceu:boron_dust', // Z5 硼-B
    '64x gtceu:carbon_dust', // Z6 碳-C
    '64x gtceu:sodium_dust', // Z11 钠-Na
    '64x gtceu:magnesium_dust', // Z12 镁-Mg
    '64x gtceu:aluminium_dust', // Z13 铝-Al
    '64x gtceu:silicon_dust', // Z14 硅-Si
    '64x gtceu:phosphorus_dust', // Z15 磷-P
    '64x gtceu:sulfur_dust', // Z16 硫-S
    '64x gtceu:potassium_dust', // Z19 钾-K
    '64x gtceu:calcium_dust', // Z20 钙-Ca
    '64x gtceu:scandium_dust', // Z21 钪-Sc
    '64x gtceu:titanium_dust', // Z22 钛-Ti
    '64x gtceu:titanium_50_dust', // Z22 钛-Ti
    '64x gtceu:vanadium_dust', // Z23 钒-V
    '64x gtceu:chromium_dust', // Z24 铬-Cr
    '64x gtceu:manganese_dust', // Z25 锰-Mn
    '64x gtceu:iron_dust', // Z26 铁-Fe
    '64x gtceu:cobalt_dust', // Z27 钴-Co
    '64x gtceu:nickel_dust', // Z28 镍-Ni
    '64x gtceu:copper_dust', // Z29 铜-Cu
    '64x gtceu:copper76_dust', // Z29 铜-Cu
    '64x gtceu:zinc_dust', // Z30 锌-Zn
    '64x gtceu:gallium_dust', // Z31 镓-Ga
    '64x gtceu:germanium_dust', // Z32 锗-Ge
    '64x gtceu:arsenic_dust', // Z33 砷-As
    '64x gtceu:selenium_dust', // Z34 硒-Se
    '64x gtceu:rubidium_dust', // Z37 铷-Rb
    '64x gtceu:strontium_dust', // Z38 锶-Sr
    '64x gtceu:yttrium_dust', // Z39 钇-Y
    '64x gtceu:zirconium_dust', // Z40 锆-Zr
    '64x gtceu:niobium_dust', // Z41 铌-Nb
    '64x gtceu:molybdenum_dust', // Z42 钼-Mo
    '64x gtceu:technetium_dust', // Z43 锝-Tc
    '64x gtceu:ruthenium_dust', // Z44 钌-Ru
    '64x gtceu:rhodium_dust', // Z45 铑-Rh
    '64x gtceu:palladium_dust', // Z46 钯-Pd
    '64x gtceu:silver_dust', // Z47 银-Ag
    '64x gtceu:cadmium_dust', // Z48 镉-Cd
    '64x gtceu:indium_dust', // Z49 铟-In
    '64x gtceu:tin_dust', // Z50 锡-Sn
    '64x gtceu:antimony_dust', // Z51 锑-Sb
    '64x gtceu:tellurium_dust', // Z52 碲-Te
    '64x gtceu:iodine_dust', // Z53 碘-I
    '64x gtceu:caesium_dust', // Z55 铯-Cs
    '64x gtceu:barium_dust', // Z56 钡-Ba
    '64x gtceu:lanthanum_dust', // Z57 镧-La
    '64x gtceu:cerium_dust', // Z58 铈-Ce
    '64x gtceu:praseodymium_dust', // Z59 镨-Pr
    '64x gtceu:neodymium_dust', // Z60 钕-Nd
    '64x gtceu:promethium_dust', // Z61 钷-Pm
    '64x gtceu:samarium_dust', // Z62 钐-Sm
    '64x gtceu:europium_dust', // Z63 铕-Eu
    '64x gtceu:gadolinium_dust', // Z64 钆-Gd
    '64x gtceu:terbium_dust', // Z65 铽-Tb
    '64x gtceu:dysprosium_dust', // Z66 镝-Dy
    '64x gtceu:holmium_dust', // Z67 钬-Ho
    '64x gtceu:erbium_dust', // Z68 铒-Er
    '64x gtceu:thulium_dust', // Z69 铥-Tm
    '64x gtceu:ytterbium_dust', // Z70 镱-Yb
    '64x gtceu:lutetium_dust', // Z71 镥-Lu
    '64x gtceu:hafnium_dust', // Z72 铪-Hf
    '64x gtceu:tantalum_dust', // Z73 钽-Ta
    '64x gtceu:tungsten_dust', // Z74 钨-W
    '64x gtceu:rhenium_dust', // Z75 铼-Re
    '64x gtceu:osmium_dust', // Z76 锇-Os
    '64x gtceu:iridium_dust', // Z77 铱-Ir
    '64x gtceu:platinum_dust', // Z78 铂-Pt
    '64x gtceu:gold_dust', // Z79 金-Au
    '64x gtceu:thallium_dust', // Z81 铊-Tl
    '64x gtceu:lead_dust', // Z82 铅-Pb
    '64x gtceu:bismuth_dust', // Z83 铋-Bi
    '64x gtceu:polonium_dust', // Z84 钋-Po
    '64x gtceu:astatine_dust', // Z85 砹-At
    '64x gtceu:francium_dust', // Z87 钫-Fr
    '64x gtceu:radium_dust', // Z88 镭-Ra
    '64x gtceu:actinium_dust', // Z89 锕-Ac
    '64x gtceu:thorium_dust', // Z90 钍-Th
    '64x gtceu:protactinium_dust', // Z91 镤-Pa
    '64x gtceu:uranium_dust', // Z92 铀-U
    '64x gtceu:uranium_235_dust', // Z92 铀-U
    '64x gtceu:neptunium_dust', // Z93 镎-Np
    '64x gtceu:plutonium_dust', // Z94 钚-Pu
    '64x gtceu:plutonium_241_dust', // Z94 钚-Pu
    '64x gtceu:americium_dust', // Z95 镅-Am
    '64x gtceu:curium_dust', // Z96 锔-Cm
    '64x gtceu:berkelium_dust', // Z97 锫-Bk
    '64x gtceu:californium_dust', // Z98 锎-Cf
    '64x gtceu:einsteinium_dust', // Z99 锿-Es
    '64x gtceu:fermium_dust', // Z100 镄-Fm
    '64x gtceu:mendelevium_dust', // Z101 钔-Md
    '64x gtceu:nobelium_dust', // Z102 锘-No
    '64x gtceu:lawrencium_dust', // Z103 铹-Lr
    '64x gtceu:rutherfordium_dust', // Z104 𬬻-Rf
    '64x gtceu:dubnium_dust', // Z105 𬭊-Db
    '64x gtceu:seaborgium_dust', // Z106 𬭳-Sg
    '64x gtceu:bohrium_dust', // Z107 𬭛-Bh
    '64x gtceu:hassium_dust', // Z108 𬭶-Hs
    '64x gtceu:meitnerium_dust', // Z109 鿏-Mt
    '64x gtceu:darmstadtium_dust', // Z110 𫟼-Ds
    '64x gtceu:roentgenium_dust', // Z111 𬬭-Rg
    '64x gtceu:copernicium_dust', // Z112 鿔-Cn
    '64x gtceu:nihonium_dust', // Z113 鿭-Nh
    '64x gtceu:flerovium_dust', // Z114 𫓧-Fl
    '64x gtceu:moscovium_dust', // Z115 镆-Mc
    '64x gtceu:livermorium_dust', // Z116 𫟷-Lv
    '64x gtceu:tennessine_dust', // Z117 鿬-Ts
    '64x gtceu:oganesson_dust', // Z118 鿫-Og
]

// ─────────────────────────────────────────────────────────────────────────────
// 产出 ②：17 个桶 → 流体（每个 64,000 mB）—— 顺序按原子序数 Z
//   '<fluid> <amount>'（流体 id 在前）；来源桶 gtceu:xxx_bucket 写在注释里
// ─────────────────────────────────────────────────────────────────────────────
var SHANHAI_BH_FLUID_OUT = [
    'gtceu:hydrogen 64000', // Z1 氢-H  ← gtceu:hydrogen_bucket
    'gtceu:deuterium 64000', // Z1 氢-H  ← gtceu:deuterium_bucket
    'gtceu:tritium 64000', // Z1 氢-H  ← gtceu:tritium_bucket
    'gtceu:helium 64000', // Z2 氦-He  ← gtceu:helium_bucket
    'gtceu:helium_3 64000', // Z2 氦-He  ← gtceu:helium_3_bucket
    'gtceu:nitrogen 64000', // Z7 氮-N  ← gtceu:nitrogen_bucket
    'gtceu:oxygen 64000', // Z8 氧-O  ← gtceu:oxygen_bucket
    'gtceu:fluorine 64000', // Z9 氟-F  ← gtceu:fluorine_bucket
    'gtceu:neon 64000', // Z10 氖-Ne  ← gtceu:neon_bucket
    'gtceu:chlorine 64000', // Z17 氯-Cl  ← gtceu:chlorine_bucket
    'gtceu:argon 64000', // Z18 氩-Ar  ← gtceu:argon_bucket
    'gtceu:bromine 64000', // Z35 溴-Br  ← gtceu:bromine_bucket
    'gtceu:krypton 64000', // Z36 氪-Kr  ← gtceu:krypton_bucket
    'gtceu:xenon 64000', // Z54 氙-Xe  ← gtceu:xenon_bucket
    'gtceu:ytterbium_178 64000', // Z70 镱-Yb  ← gtceu:ytterbium_178_bucket
    'gtceu:mercury 64000', // Z80 汞-Hg  ← gtceu:mercury_bucket
    'gtceu:radon 64000', // Z86 氡-Rn  ← gtceu:radon_bucket
]

// ─────────────────────────────────────────────────────────────────────────────
// 注册（一条配方 = 一个 try/catch；失败只报这一条，不连坐别的脚本）
// ─────────────────────────────────────────────────────────────────────────────
ServerEvents.recipes(function (event) {
    var gtr = event.recipes.gtceu
    var TAG = '[SHANHAI-SPEC] blackhole_extract'
    var ITEM_OUT = SHANHAI_BH_DUST_OUT
    var FLUID_OUT = SHANHAI_BH_FLUID_OUT
    var j
    var cur = '(还没开始)'
    try {
        // 🔴 先写 id（类型函数要的是【剥掉类型路径】的 id）：
        //    传 'shanhai:blackhole_element_extract' ⇒ 运行期 id
        //    = shanhai:vision_matter_decompression/blackhole_element_extract
        //    （口径出处 [server_scripts]shanhai_editor_recipes.js:22-27）
        var b = gtr.vision_matter_decompression('shanhai:blackhole_element_extract')
            .notConsumable('1x shanhai:reality_item_2')
            .circuit(1)
            .duration(20)
            .EUt(2147483648)
        for (j = 0; j < ITEM_OUT.length; j++) {
            cur = ITEM_OUT[j]
            b = b.itemOutputs(ITEM_OUT[j])
        }
        for (j = 0; j < FLUID_OUT.length; j++) {
            cur = FLUID_OUT[j]
            b = b.outputFluids(FLUID_OUT[j])
        }
        console.info(TAG + ' register ok=1'
            + ' type=gtceu:vision_matter_decompression'
            + ' id=shanhai:blackhole_element_extract'
            + ' input=1x shanhai:reality_item_2(notConsumable)+circuit(1)'
            + ' EUt=2147483648 (MAX 1A)'
            + ' duration=20t'
            + ' itemOut=' + ITEM_OUT.length + 'x64'
            + ' fluidOut=' + FLUID_OUT.length + 'x64000mB')
    } catch (e) {
        console.error(TAG + ' FAILED at entry [' + cur + ']: ' + e)
    }
})

// ─────────────────────────────────────────────────────────────────────────────
// 配方 ②：简易黑洞引擎模块（催化剂）⇒ 按 E = mc² 发电
//   · 类型 = 视界能流抽取（**原样**，一行 Java 都没改；它的 EUIO 仍是 IN）
//   · 发电量 = MAX+8 档 1A = 140,737,488,355,328 EU/t（2^47 = GTValues.VEX[22]）
//   · 耗水 = 32 mB / 20 tick
//       E = 2^47 × 20 = 2.8147e15 J（按 1 EU = 1 J）
//       m = E / c² = 2.8147e15 / 8.9876e16 = 0.03132 kg = 31.3 g
//       1000 mB 水 = 1 m³ = 1000 kg ⇒ 1 g = 1 mB ⇒ 31.3 mB，向上取整 32
//   · 🔴 负 EUt = 发电（口径出处 [server_scripts]shanhai_recipes.js:2983-2992 与 :3153）
//     GTCEu 分支（javap 实读本机 libs\gtceu-1.20.1-1.4.4.jar 的 GTRecipeBuilder#EUt）：
//        eu > 0 ⇒ tickInput.remove(EU) 后 inputEU(eu)      （耗电）
//        eu < 0 ⇒ tickOutput.remove(EU) 后 outputEU(-eu)   （发电）
//   ⚠️ 本节只改【配方】。用户 2026-11-13 拍板「保持原样」⇒ 类型 EUIO 仍是 IN、
//      没有新机器、ModuleRegistry 的 SPECS 没动、没有任何 getCurrentParallel() 被覆写。
//
//   ⚠️ 水量写成【具名常量】而不是散落的字面量（原规格：「把 32 写成带公式注释的具名常量，方便一行改」）：
//      E = EUt × duration = 140,737,488,355,328 EU/t × 20 t = 2,814,749,767,106,560 J（GT 里 1 EU = 1 J）
//      c = 299,792,458 m/s ⇒ c² = 8.98755178736818e16 m²/s²
//      m = E / c² = 0.0313183 kg = 31.3183 g   （上方 31.3 g 是约数，精确值 31.3183 g）
//      MC 里 1 mB 水 ≡ 1 g（1000 mB = 1 kg）⇒ **向上取整 = 32 mB**
// ─────────────────────────────────────────────────────────────────────────────
var SHANHAI_BH_ENGINE_WATER_MB = 32 // = ceil(2^47 × 20 J ÷ c²) = ceil(31.3183 g) = 32（要改水量只改这一行）

ServerEvents.recipes(function (event) {
    var gtr = event.recipes.gtceu
    var TAG = '[SHANHAI-SPEC] blackhole_engine'
    try {
        gtr.vision_energy_extraction('shanhai:blackhole_engine_power')
            .notConsumable('1x shanhai:reality_item_3')
            .circuit(32)
            .inputFluids('minecraft:water ' + SHANHAI_BH_ENGINE_WATER_MB)
            .duration(20)
            .EUt(-140737488355328)
        console.info(TAG + ' register ok=1'
            + ' type=gtceu:vision_energy_extraction'
            + ' id=shanhai:blackhole_engine_power'
            + ' input=1x shanhai:reality_item_3(notConsumable)+circuit(32)+water 32mB'
            + ' EUt=-140737488355328 (MAX+8 1A, generate)'
            + ' duration=20t')
    } catch (e) {
        console.error(TAG + ' FAILED: ' + e)
    }
})
