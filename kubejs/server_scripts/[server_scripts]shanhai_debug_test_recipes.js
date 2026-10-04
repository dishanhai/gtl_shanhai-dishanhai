// priority: 1
// =============================================================================
// [server_scripts]shanhai_debug_test_recipes.js
//   原初山海调试（gtceu:primordial_debug）的 27 条【测试配方】
//
// 用户点单（逐字，2026-10-03）：
//   「我还需要一条测试配方用来测试我们做的这个，你就新增一个配方种类叫原初山海调试，
//     然后就给原初山海调试模块这个机器加，然后里面分别添加一个原石变成各种矿物
//     （每个配方都要加上不同的编程电路），然后其中添加各种条件，也添加一些 2-3 个的组合条件」
// 补充（逐字）：
//   「各种矿物你随便，反正测试用的，各种条件是都要上的，而且还需要上组合条件」
//
// =============================================================================
// §0 本文件是什么
//   · 27 条配方，每条 = 1× minecraft:cobblestone ＋ 1× 编程电路(.circuit(n)，n = 1..27 全局不重)
//     ⇒ 1× 某种矿物（27 种各不相同）。
//   · 分组（A/B/B2/C/D 五组，详见 §4 的 RECIPES 表，逐条可读）：
//       A  6 条：无条件                —— 基线【正对照】，这 6 条必须一直能做
//       B  7 条：7 种条件各一条        —— 超净间·中档 / 无重力 / 研究 / 维度 / 岩石粉碎 / 环境危害 / 强重力
//       B2 2 条：超净间另外两档        —— 低档 cleanroom / 高档 law_cleanroom
//       C  6 条：两两组合
//       D  6 条：三个一起（超净间＋无重力＋维度）—— 测 3 格上限
//   · 预期读数（2026-10-03 当时的机制状态）：A 组 6 条【能做】，
//     B/B2/C/D 共 21 条【做不了】—— 因为"额外挂载槽"的机制还没做完。
//     这不是脚本的毛病，是本任务的预期结果。等机制做完再复测。
//
// =============================================================================
// §1 为什么单开一个文件（不动既有脚本）
//   · [server_scripts]shanhai_recipes.js      = gen_kjs.js 的【生成器产物】⇒ 一字节不许碰。
//   · [server_scripts]shanhai_test_recipes.js = 40 条真类型的占位配方（另一件事）⇒ 不碰。
//   · 本文件是【新增】，目标位置原本不存在同名文件 ⇒ 无覆盖风险。
//   · 配方 id 前缀用 `shanhai:debug_test/...`，与上面两个文件的 id 命名法不重叠。
//
// =============================================================================
// §2 配方类型侧（落在 jar 里，本文件不负责注册）
//   · id = gtceu:primordial_debug，注册在
//     shanhai-rewrite\src\main\java\com\shanhai\common\recipe\ShanhaiRecipeTypes.java
//     （register("primordial_debug", "multiblock")，setMaxIOSize(6, 6, 2, 2)）。
//   · 挂载点 = shanhai:primordial_debug_module（原初山海调试模块），
//     见 com\shanhai\machine\module\ModuleRegistry.java#buildDebugModuleRecipeTypes()。
//   · 中文名 lang 键 = gtceu.primordial_debug（**不是** gtceu.recipe_type.primordial_debug）。
//   ⚠️ 本文件用 gtr[TYPE](...)（动态取键）而不是 gtr.primordial_debug(...)：
//      先例 = [server_scripts]shanhai_lens_goodbye.js 与 shanhai_test_recipes.js，
//      它们对 40 条真类型走的都是同一套"动态取键"路径，已实测可用。
//
// =============================================================================
// §3 条件写法从哪里来（**不是猜的**，两条来源都要说清）
//   ① KubeJS 侧的方法名 —— javap GTRecipeSchema$GTRecipeJS（gtceu-1.20.1-1.4.4.jar）实测有：
//        addCondition(RecipeCondition) / cleanroom(CleanroomType) / dimension(ResourceLocation[,boolean])
//        environmentalHazard(MedicalCondition[,boolean]) / researchWithoutRecipe(String[,ItemStack])
//      ⇒ 本文件统一使用【最通用】的那条：addCondition(new <条件类>(...))。
//   ② 条件类的构造器 —— 逐个 javap 抄下来的（不是凭记忆写的）：
//        com.gregtechceu.gtceu.common.recipe.condition.CleanroomCondition(CleanroomType)
//        com.gregtechceu.gtceu.common.recipe.condition.DimensionCondition(ResourceLocation)
//        com.gregtechceu.gtceu.common.recipe.condition.RockBreakerCondition()          ← 无参构造
//        com.gregtechceu.gtceu.common.recipe.condition.EnvironmentalHazardCondition(MedicalCondition)
//        com.gregtechceu.gtceu.common.recipe.condition.ResearchCondition(ResearchData)
//        org.gtlcore.gtlcore.common.recipe.condition.GravityCondition(boolean)
//            ← ⚠️ 是 **gtlcore** 加的类，不是 GTCEu 的；参数 true = 无重力 / false = 强重力。
//              布尔语义出处 = 宿主脚本现成先例 gtceu.js:8489 `.addCondition(new GravityCondition(true))`
//              （同一文件里 8504/8511 行是 (false)），以及导出快照里
//              {"type":"gravity","data":{"gravity":true,...}} 的实测形态。
//   ③ 超净间 3 档的取法（**必须带参数**，所以不能只写 `.cleanroom()` 无参）：
//        低档 = CleanroomType.CLEANROOM              （名字 "cleanroom"）
//        中档 = CleanroomType.STERILE_CLEANROOM      （名字 "sterile_cleanroom"）
//        高档 = GTLCleanroomType.LAW_CLEANROOM       （名字 "law_cleanroom"，**gtlcore 加的**）
//      ⚠️ javap 实测：CleanroomType 里**只有** CLEANROOM 与 STERILE_CLEANROOM 两个静态字段，
//         law_cleanroom 在 org.gtlcore.gtlcore.api.machine.multiblock.GTLCleanroomType。
//         ⇒ 写 `.cleanroom(...)` 时三档要分别传三个不同的对象。
//      ⚠️ 「低/中/高」的排序口径 = 严格程度（cleanroom < sterile_cleanroom < law_cleanroom），
//         依据是导出快照里这三个名字的实际分布（cxbp/incubator 用 sterile、formation 用 law、
//         普通装配用 cleanroom），**不是**随意命名。
//   ④ 类怎么拿到 —— 全部走 Java.loadClass(全限定名)，**不用裸全局名**：
//        · 裸名只有 registerBindings 里显式绑过的才一定有（gtceu 的 CleanroomType / GTMedicalConditions、
//          gtlcore 的 GTLCleanroomType / GravityCondition —— 逐个 javap GregTechKubeJSPlugin 与
//          GTLKubejsPlugin 的 registerBindings 抄下来的）。
//        · RockBreakerCondition / EnvironmentalHazardCondition / ResearchCondition / ResearchData
//          **不在这两份绑定表里** ⇒ 裸名不一定解析得到；Java.loadClass 是确定的。
//        （本工程自己的取证：ClassFilter.isAllowed0 在本整合包是"默认放行、按黑名单拒绝"，
//          旁证 = 早期日志里的 `Loaded Java class 'com.shanhai.common.recipe.ShanhaiRecipeStats'`
//          出现在本工程还没有任何 KubeJS 插件的时候。见 ShanhaiKubeJSPlugin 类注释 §3。）
//
// =============================================================================
// §4 27 条配方表（这张表就是"每组几条、每条什么条件"的唯一真源，日志逐条回显同一张表）
//   字段：c=电路号 out=产出矿物 g=组 conds=该条挂的条件（空数组 = 无条件）
//
// =============================================================================
// §5 Rhino 约束（KubeJS = Rhino，不是 Node）
//   · 全局/局部一律 var；不用 let/const、不用模板字符串、不用 ?.、不用解构、不用箭头函数。
//   · circuit 必须是【数字】（.circuit(1) 可以，.circuit('1') 不行）。
//   · 每条配方包在 try/catch 里 —— 某一条失败只让那一条失败，不连坐其它 26 条、也不连坐别的脚本。
// =============================================================================

ServerEvents.recipes(function (event) {
    var gtr = event.recipes.gtceu
    var TYPE = 'primordial_debug'

    // ─────────────────────────── §A 类句柄（一次解析，27 条复用） ───────────────────────────
    var CleanroomCondition = null
    var DimensionCondition = null
    var RockBreakerCondition = null
    var EnvironmentalHazardCondition = null
    var ResearchCondition = null
    var ResearchData = null
    var ResearchEntry = null
    var GravityCondition = null
    var ResourceLocation = null
    var CleanroomType = null
    var GTLCT = null
    var MedicalConditions = null
    var ResearchManager = null

    var clsError = ''
    try {
        CleanroomCondition = Java.loadClass('com.gregtechceu.gtceu.common.recipe.condition.CleanroomCondition')
        DimensionCondition = Java.loadClass('com.gregtechceu.gtceu.common.recipe.condition.DimensionCondition')
        RockBreakerCondition = Java.loadClass('com.gregtechceu.gtceu.common.recipe.condition.RockBreakerCondition')
        EnvironmentalHazardCondition = Java.loadClass('com.gregtechceu.gtceu.common.recipe.condition.EnvironmentalHazardCondition')
        ResearchCondition = Java.loadClass('com.gregtechceu.gtceu.common.recipe.condition.ResearchCondition')
        ResearchData = Java.loadClass('com.gregtechceu.gtceu.api.recipe.ResearchData')
        ResearchEntry = Java.loadClass('com.gregtechceu.gtceu.api.recipe.ResearchData$ResearchEntry')
        GravityCondition = Java.loadClass('org.gtlcore.gtlcore.common.recipe.condition.GravityCondition')
        ResourceLocation = Java.loadClass('net.minecraft.resources.ResourceLocation')
        CleanroomType = Java.loadClass('com.gregtechceu.gtceu.api.machine.multiblock.CleanroomType')
        GTLCT = Java.loadClass('org.gtlcore.gtlcore.api.machine.multiblock.GTLCleanroomType')
        MedicalConditions = Java.loadClass('com.gregtechceu.gtceu.common.data.GTMedicalConditions')
        ResearchManager = Java.loadClass('com.gregtechceu.gtceu.utils.ResearchManager')
    } catch (e0) {
        clsError = '' + e0
    }

    // 三条超净间类型（判据 = javap 抄下来的静态字段名，不是猜的）
    var CR_LOW = null
    var CR_MID = null
    var CR_HIGH = null
    if (clsError.length === 0) {
        try {
            CR_LOW = CleanroomType.CLEANROOM
            CR_MID = CleanroomType.STERILE_CLEANROOM
            CR_HIGH = GTLCT.LAW_CLEANROOM
        } catch (e1) {
            clsError = 'cleanroom 类型取值失败: ' + e1
        }
    }

    // ─────────────────────── §B 条件工厂（每次调用都造【新实例】） ───────────────────────
    // 刻意不做成"27 条共用一个实例"：RecipeCondition 是可变的（isReverse / data 等），
    // 跨条复用实例在"某条被反相"之类的情况下会互相污染。一次一条、一个新的，最稳。
    function condCleanroom(t) {
        return new CleanroomCondition(t)
    }
    function condZeroG() {
        return new GravityCondition(true)
    }
    function condStrongG() {
        return new GravityCondition(false)
    }
    function condDimension(id) {
        return new DimensionCondition(new ResourceLocation(id))
    }
    function condRockBreaker() {
        return new RockBreakerCondition()
    }
    function condHazard() {
        return new EnvironmentalHazardCondition(MedicalConditions.CARBON_MONOXIDE_POISONING)
    }
    function condResearch() {
        // 研究条件 = ResearchCondition(ResearchData)；dataItem 取 GTCEu 自己的默认扫描物品
        // （不硬编码具体的"数据球"，避免这一条因为物品改版而悄悄失效）。
        var rd = new ResearchData()
        rd.add(new ResearchEntry('shanhai:debug_test_primordial_debug', ResearchManager.getDefaultScannerItem()))
        return new ResearchCondition(rd)
    }

    // 27 条的表。mk = 零参函数数组，调用一次产一个条件实例。
    var RECIPES = [
        // ─────────── A 组：无条件（6 条）—— 基线【正对照】，必须能做 ───────────
        { c: 1, g: 'A', out: 'minecraft:coal', desc: '无条件', mk: [] },
        { c: 2, g: 'A', out: 'minecraft:iron_ingot', desc: '无条件', mk: [] },
        { c: 3, g: 'A', out: 'minecraft:gold_ingot', desc: '无条件', mk: [] },
        { c: 4, g: 'A', out: 'minecraft:diamond', desc: '无条件', mk: [] },
        { c: 5, g: 'A', out: 'minecraft:emerald', desc: '无条件', mk: [] },
        { c: 6, g: 'A', out: 'minecraft:redstone', desc: '无条件', mk: [] },

        // ─────────── B 组：7 种条件各一条（7 条） ───────────
        { c: 7, g: 'B', out: 'minecraft:lapis_lazuli', desc: '超净间·中档(sterile_cleanroom)', mk: [function () { return condCleanroom(CR_MID) }] },
        { c: 8, g: 'B', out: 'minecraft:quartz', desc: '无重力(GravityCondition true)', mk: [condZeroG] },
        { c: 9, g: 'B', out: 'minecraft:copper_ingot', desc: '研究(ResearchCondition)', mk: [condResearch] },
        { c: 10, g: 'B', out: 'minecraft:amethyst_shard', desc: '维度(ad_astra:mars)', mk: [function () { return condDimension('ad_astra:mars') }] },
        { c: 11, g: 'B', out: 'gtceu:tin_ingot', desc: '岩石粉碎(RockBreakerCondition)', mk: [condRockBreaker] },
        { c: 12, g: 'B', out: 'gtceu:lead_ingot', desc: '环境危害(carbon_monoxide_poisoning)', mk: [condHazard] },
        { c: 13, g: 'B', out: 'gtceu:nickel_ingot', desc: '强重力(GravityCondition false)', mk: [condStrongG] },

        // ─────────── B2 组：超净间另外两档（2 条）—— 把 3 档分开测 ───────────
        { c: 14, g: 'B2', out: 'gtceu:silver_ingot', desc: '超净间·低档(cleanroom)', mk: [function () { return condCleanroom(CR_LOW) }] },
        { c: 15, g: 'B2', out: 'gtceu:zinc_ingot', desc: '超净间·高档(law_cleanroom)', mk: [function () { return condCleanroom(CR_HIGH) }] },

        // ─────────── C 组：两两组合（6 条） ───────────
        { c: 16, g: 'C', out: 'gtceu:aluminium_ingot', desc: '超净间·中档 + 无重力', mk: [function () { return condCleanroom(CR_MID) }, condZeroG] },
        { c: 17, g: 'C', out: 'gtceu:tungsten_ingot', desc: '超净间·中档 + 维度(mars)', mk: [function () { return condCleanroom(CR_MID) }, function () { return condDimension('ad_astra:mars') }] },
        { c: 18, g: 'C', out: 'gtceu:titanium_ingot', desc: '无重力 + 研究', mk: [condZeroG, condResearch] },
        { c: 19, g: 'C', out: 'gtceu:platinum_ingot', desc: '无重力 + 维度(mars)', mk: [condZeroG, function () { return condDimension('ad_astra:mars') }] },
        { c: 20, g: 'C', out: 'gtceu:uranium_ingot', desc: '强重力 + 环境危害(carbon_monoxide_poisoning)', mk: [condStrongG, condHazard] },
        { c: 21, g: 'C', out: 'gtceu:lithium_dust', desc: '岩石粉碎 + 维度(mars)', mk: [condRockBreaker, function () { return condDimension('ad_astra:mars') }] },

        // ─────────── D 组：三个一起（6 条）—— 测 3 格上限：超净间 + 无重力 + 维度 ───────────
        { c: 22, g: 'D', out: 'gtceu:sulfur_dust', desc: '超净间·中档 + 无重力 + 维度(mars)', mk: [function () { return condCleanroom(CR_MID) }, condZeroG, function () { return condDimension('ad_astra:mars') }] },
        { c: 23, g: 'D', out: 'gtceu:carbon_dust', desc: '超净间·低档 + 无重力 + 维度(mars)', mk: [function () { return condCleanroom(CR_LOW) }, condZeroG, function () { return condDimension('ad_astra:mars') }] },
        { c: 24, g: 'D', out: 'gtceu:silicon_dust', desc: '超净间·高档 + 无重力 + 维度(mars)', mk: [function () { return condCleanroom(CR_HIGH) }, condZeroG, function () { return condDimension('ad_astra:mars') }] },
        { c: 25, g: 'D', out: 'gtceu:graphite_dust', desc: '超净间·中档 + 无重力 + 维度(the_nether)', mk: [function () { return condCleanroom(CR_MID) }, condZeroG, function () { return condDimension('minecraft:the_nether') }] },
        { c: 26, g: 'D', out: 'gtceu:ruby_gem', desc: '超净间·中档 + 无重力 + 维度(kubejs:void)', mk: [function () { return condCleanroom(CR_MID) }, condZeroG, function () { return condDimension('kubejs:void') }] },
        { c: 27, g: 'D', out: 'gtceu:sapphire_gem', desc: '超净间·低档 + 无重力 + 维度(the_end)', mk: [function () { return condCleanroom(CR_LOW) }, condZeroG, function () { return condDimension('minecraft:the_end') }] }
    ]

    // ─────────────────────────── §C 逐条注册 + 逐条回显 ───────────────────────────
    var ok = 0
    var bad = 0
    var errList = ''
    var i
    var j
    var r
    var b
    var cs
    var rid
    var condNames

    for (i = 0; i < RECIPES.length; i++) {
        r = RECIPES[i]
        rid = 'shanhai:debug_test/' + r.g.toLowerCase() + '_c' + r.c
        // condNames 直接就是表里写的那句人话（组合条件的"＋"已在 desc 里写全），不在这里二次拼装。
        condNames = r.desc

        try {
            if (clsError.length > 0) {
                throw new Error('条件类解析失败，本条拒绝注册（避免"条件静默丢失"）：' + clsError)
            }
            b = gtr[TYPE](rid)
                .itemInputs('1x minecraft:cobblestone')
                .itemOutputs('1x ' + r.out)
                .circuit(r.c)
                .duration(20)
                .EUt(30)
            cs = r.mk
            for (j = 0; j < cs.length; j++) {
                b = b.addCondition(cs[j]())
            }
            ok = ok + 1
            // 逐条判据行（27 条各一行，机器可 grep；把"分组 / 电路号 / 条件 / 产出 / id"一次打全）
            console.info('[SHANHAI-DEBUGTEST] n=' + (i + 1) + '/' + RECIPES.length
                + ' group=' + r.g + ' circuit=' + r.c
                + ' cond=' + condNames + ' condCount=' + r.mk.length
                + ' in=1x minecraft:cobblestone out=1x ' + r.out
                + ' id=' + rid + ' => ok')
        } catch (e) {
            bad = bad + 1
            if (errList.length < 2000) {
                errList = errList + rid + ' [c' + r.c + ' ' + condNames + '] => ' + e + ' | '
            }
            console.error('[SHANHAI-DEBUGTEST] n=' + (i + 1) + '/' + RECIPES.length
                + ' group=' + r.g + ' circuit=' + r.c + ' cond=' + condNames
                + ' id=' + rid + ' => FAILED: ' + e)
        }
    }

    // 汇总行（预期：ok=27 failed=0 total=27）
    console.info('[SHANHAI-DEBUGTEST] 原初山海调试 测试配方汇总: type=gtceu:' + TYPE
        + ' ok=' + ok + ' failed=' + bad + ' total=' + RECIPES.length)
    if (bad > 0) {
        console.error('[SHANHAI-DEBUGTEST] 失败清单: ' + errList)
    }
    if (clsError.length > 0) {
        console.error('[SHANHAI-DEBUGTEST] 条件类解析错误（整批受影响）: ' + clsError)
    }
})
