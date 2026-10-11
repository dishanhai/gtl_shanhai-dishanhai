// priority: 1
// =============================================================================
// [server_scripts]shanhai_editor_recipes.js
//   —— 用户在【游戏内配方编辑器】里新建的 15 条配方，转成【永久配方】（KubeJS 版）
//
// 用户原话（逐字）：
//   「你先帮我把这15条配方写入山海的永久配方」
//
// ── 数据来源（只读，未改动）────────────────────────────────────────────────────
//   <实例>\config\shanhai\recipe_overrides.json
//     sha256 2e005b1f4b92b450c6b4aed6cc2593108faa73e6e1a6ba98b56eb033578e62be
//     bytes  33796   entries 15
//     schema_version 1   generated_by shanhai-0.1.0
//     owned_by shanhai-editor
//
// ── 生成方式（可重跑；同一份 intent.json 重跑 ⇒ 逐字节相同）─────────────────────
//   node temp\perm-recipes\extract.mjs   # 源 → intent.json（只读源）
//   node temp\perm-recipes\gen.mjs       # intent.json → 本文件
//   node temp\perm-recipes\check.mjs     # 自检：源 ↔ intent ↔ 本文件 ＋ 负对照
//
// ── 🔴 三条口径（都有出处，不是"照经验写的"）──────────────────────────────────
//   ① id：类型函数要的是【剥掉类型路径】的 id。
//      GTRecipeJS.id(ResourceLocation) 里是：
//          idWithoutType = <传进来的那个 id 原样>
//          id            = new ResourceLocation(ns, "<类型路径>/" + idWithoutType.getPath())
//      ⇒ gtr.matter_forging('shanhai:new_recipe_1') 的运行期 id = shanhai:matter_forging/new_recipe_1 ✓
//      ⇒ 若传 'shanhai:matter_forging/new_recipe_1' 会得到 shanhai:matter_forging/matter_forging/new_recipe_1 ✗
//      （字节码取证：shanhai_recipe_overrides.js:990-1019 shanhaiOvrStripTypePath）
//   ② data.euTier ＋ EUt：🔴【2026-10-11 改口径 —— EUt 本身也按用户选的档位对齐】。
//      背景（两步，都是实测）：
//        (a) GTLCore 的 GTRecipeJSMixin 注在 GTRecipeJS.EUt(long) 里，会写
//              addData("euTier", GTUtil.getTierByVoltage(|eu|))
//            ⇒ 源里 EUt=8 ⇒ 这个键被推成 0（ULV）。所以本文件在 .EUt(…) 之后紧跟一句
//              .addData('euTier', <源里的值>)，把用户选的档位【显式】写回去（用户选的档位在前台，
//              闸门读的就是这个键：GTRecipeMixin.getEuTier() → data.getInt("euTier")）。
//        (b) 🔴 但写回这个键【只解决闸门，不解决显示】：卡片右上角那个电压词是
//              ShanhaiRecipeEditorWorkspace.tierOf(EUt) 算的（＝「≤ EUt 的最大那一档」），
//              它只看 EUt —— 所以 EUt=8 时面板仍显示 ULV ⇒ 用户报的"新增的配方电压不对"就是这个。
//      ⇒ 本轮把 EUt 也换成"能落进用户选的那一档"的值：V[t]（该档名义电压）。
//        取它的理由：① getTierByVoltage 判 t 档是 eut ∈ (V[t-1], V[t]]，② 面板 tierOf 判 t 档是
//        eut ∈ [V[t], V[t+1]) ⇒ 两个函数的交集【只有 V[t] 一个点】（详见 lib.mjs §2）。
//        用"最小 EUt"（V[t-1]+1）会让面板显示低一档（LV 的 9 → ULV、IV 的 2049 → EV）✗。
//      ⚠️ 代价（照实写）：这几条的耗电从 8 EU/t 涨到各自档位电压（LV 32 / EV 2048 / IV 8192 /
//        MAX 2147483647）—— 这是"面板显示你选的那一档"的必然代价（用户已拍板要显示对）。
//        源文件 recipe_overrides.json 里 EUt 仍是 8（本工程一个字节都没改它）。
//   ③ 额外条件：只走 Java 绑定 ShanhaiConditions.apply(recipe, JsonElement)，
//      与 kubejs\server_scripts\shanhai_recipe_overrides.js:1099 用的是同一个入口。
//      🔴 绝不 recipe.set('recipeConditions', …)：KubeJS 那个 component 写出来的形状是
//         {"type":k,"data":{…}}，而 GT 的加载器（RecipeCondition.CODEC，KeyDispatchCodec）要平铺的
//         {"type":k,…字段…} ⇒ 形状不对称 ⇒ 整条配方 json 解析失败（不是"条件没生效"，是配方没了）。
//
// ── 本批覆盖范围（15 条，全部 op=add）─────────────────────────────────
//   · 原初奇点反演（primordial_singularity_inversion）2 条
//   · 光子分离（photon_separation）2 条
//   · 物质锻造（matter_forging）2 条
//   · 原初物质重组（primordial_matter_recombination）1 条
//   · 原初物质解构（primordial_matter_deconstruction）1 条
//   · 太虚熔炼（taixu_smelting）1 条
//   · 世线板电路组装（wl_board_circuit_assembly）2 条
//   · 原初因果编织（primordial_causal_weaving）2 条
//   · 原初世线切割（worldline_cutting）1 条
//   · 物质流凝结（matter_flow_condensation）1 条
//   · 槽位：物品输入 52（其中编程电路 12、催化剂 3、其余普通输入 37）
//          流体输入 5、物品产出 19、流体产出 4
//   · duration 全为 200（源里 recipe.duration，未改）
//   · EUt：源里全为 8（tickInputs.eu.content ＝ 编辑器建配方时的默认值）
//          ⇒ 发射值按用户选的档位对齐（源里档位 1,4,5,14 ⇒ EV→2048、IV→8192、LV→32、MAX→2147483647）
//          ⚠️ 发射值 ≠ 源值 是【本轮的刻意改动】（口径②）；id / duration / 槽位 / 条件一个都没动。
//   · 额外条件 9 条有（全部是物质模块等级 module_level）、6 条没有（不许凭空加）
//
// ── 源覆盖文件的现状（🔴 2026-10-11 实测，不再是"仍然在"）──────────────────────
//   源 recipe_overrides.json（实例 configshanhai）里那 15 条 op=add 的 entry
//   【已经不在了】：活文件现在 603 B、entries=0、mtime 2026-10-10 21:47:20。
//   ⇒ 旧版这里写的"它们会再建一遍同名配方，请你去编辑器里删掉"这条待办【已经不需要做】。
//   ⇒ 抽取时那一份（33796 B / sha256 2e005b1f…）逐字节存档在
//      tempperm-recipessource_snapshot.json，check.mjs 的 ① 判据跑的就是它。
//   （本工程只读实例；这一条是读数，不是我们改的。）
//
// ── 判据（进游戏后看这一行，可 grep）───────────────────────────────────────────
//   [SHANHAI-EDITOR-PERM] 游戏内编辑器新建配方 … ok=15 failed=0 cond_applied=9 cond_failed=0 ✅
// =============================================================================

ServerEvents.recipes(function (event) {
    var gtr = event.recipes.gtceu
    var PERM_OK = 0
    var PERM_BAD = 0
    var PERM_COND_OK = 0
    var PERM_COND_BAD = 0
    var PERM_ERRS = ''

    // ── [01/15] 原初奇点反演 第 1 条（本类型共 2 条）
    //    运行期 id = shanhai:primordial_singularity_inversion/new_recipe_1
    // @perm index=1 id=shanhai:primordial_singularity_inversion/new_recipe_1 type=gtceu:primordial_singularity_inversion typePath=primordial_singularity_inversion kjsId=shanhai:new_recipe_1 seq=1 conditions=0
    try {
        var b1 = gtr.primordial_singularity_inversion('shanhai:new_recipe_1')
            .itemInputs('1x shanhai:up_quark_emission_catalyst')
            .circuit(8)
            .itemInputs('1x gtceu:fission_reactor')
            .itemInputs('1x gtceu:heat_exchanger')
            .itemOutputs('1x shanhai:strange_quark_emission_catalyst')
            .duration(200)
            .EUt(8192)
            .addData('euTier', 5)
        PERM_OK = PERM_OK + 1
    } catch (err1) {
        PERM_BAD = PERM_BAD + 1
        if (PERM_ERRS.length < 2000) { PERM_ERRS = PERM_ERRS + '|shanhai:primordial_singularity_inversion/new_recipe_1 <= ' + err1 + ' ' }
    }

    // ── [02/15] 原初奇点反演 第 2 条（本类型共 2 条）
    //    运行期 id = shanhai:primordial_singularity_inversion/new_recipe_2
    // @perm index=2 id=shanhai:primordial_singularity_inversion/new_recipe_2 type=gtceu:primordial_singularity_inversion typePath=primordial_singularity_inversion kjsId=shanhai:new_recipe_2 seq=2 conditions=0
    try {
        var b2 = gtr.primordial_singularity_inversion('shanhai:new_recipe_2')
            .circuit(8)
            .itemInputs('1x gtceu:blaze_blast_furnace')
            .itemInputs('1x gtceu:assembly_line')
            .itemInputs('1x shanhai:casing_empty_quark_emission_catalyst')
            .itemOutputs('1x shanhai:charm_quark_emission_catalyst')
            .duration(200)
            .EUt(8192)
            .addData('euTier', 5)
        PERM_OK = PERM_OK + 1
    } catch (err2) {
        PERM_BAD = PERM_BAD + 1
        if (PERM_ERRS.length < 2000) { PERM_ERRS = PERM_ERRS + '|shanhai:primordial_singularity_inversion/new_recipe_2 <= ' + err2 + ' ' }
    }

    // ── [03/15] 光子分离 第 1 条（本类型共 2 条）
    //    运行期 id = shanhai:photon_separation/new_recipe_1
    // @perm index=3 id=shanhai:photon_separation/new_recipe_1 type=gtceu:photon_separation typePath=photon_separation kjsId=shanhai:new_recipe_1 seq=1 conditions=1
    try {
        var b3 = gtr.photon_separation('shanhai:new_recipe_1')
            .notConsumable('64x shanhai:strange_quark_emission_catalyst')
            .circuit(22)
            .itemInputs('4x shanhai:photon_rainbow')
            .itemOutputs('1x shanhai:strange_quark')
            .outputFluids('shanhai:zero_point_energy 4000')
            .duration(200)
            .EUt(8192)
            .addData('euTier', 5)
        PERM_OK = PERM_OK + 1
        // 额外条件 1 条（物质模块等级 shanhai:material_recombination_module level=5）
        // 🔴 只走 Java 绑定（与 shanhai_recipe_overrides.js 同一个入口）；绝不 recipe.set('recipeConditions', …)
        var e3 = ShanhaiConditions.apply(b3, JsonIO.parseRaw('[{"type":"module_level","module_id":"shanhai:material_recombination_module","level":5}]'))
        if (e3 !== '') {
            PERM_COND_BAD = PERM_COND_BAD + 1
            PERM_ERRS = PERM_ERRS + '|cond shanhai:photon_separation/new_recipe_1 <= ' + e3 + ' '
        } else {
            PERM_COND_OK = PERM_COND_OK + 1
        }
    } catch (err3) {
        PERM_BAD = PERM_BAD + 1
        if (PERM_ERRS.length < 2000) { PERM_ERRS = PERM_ERRS + '|shanhai:photon_separation/new_recipe_1 <= ' + err3 + ' ' }
    }

    // ── [04/15] 光子分离 第 2 条（本类型共 2 条）
    //    运行期 id = shanhai:photon_separation/new_recipe_2
    // @perm index=4 id=shanhai:photon_separation/new_recipe_2 type=gtceu:photon_separation typePath=photon_separation kjsId=shanhai:new_recipe_2 seq=2 conditions=1
    try {
        var b4 = gtr.photon_separation('shanhai:new_recipe_2')
            .itemInputs('4x shanhai:photon_rainbow')
            .notConsumable('64x shanhai:charm_quark_emission_catalyst')
            .circuit(22)
            .itemOutputs('1x shanhai:charm_quark')
            .outputFluids('shanhai:zero_point_energy 4000')
            .duration(200)
            .EUt(8192)
            .addData('euTier', 5)
        PERM_OK = PERM_OK + 1
        // 额外条件 1 条（物质模块等级 shanhai:material_recombination_module level=5）
        // 🔴 只走 Java 绑定（与 shanhai_recipe_overrides.js 同一个入口）；绝不 recipe.set('recipeConditions', …)
        var e4 = ShanhaiConditions.apply(b4, JsonIO.parseRaw('[{"type":"module_level","module_id":"shanhai:material_recombination_module","level":5}]'))
        if (e4 !== '') {
            PERM_COND_BAD = PERM_COND_BAD + 1
            PERM_ERRS = PERM_ERRS + '|cond shanhai:photon_separation/new_recipe_2 <= ' + e4 + ' '
        } else {
            PERM_COND_OK = PERM_COND_OK + 1
        }
    } catch (err4) {
        PERM_BAD = PERM_BAD + 1
        if (PERM_ERRS.length < 2000) { PERM_ERRS = PERM_ERRS + '|shanhai:photon_separation/new_recipe_2 <= ' + err4 + ' ' }
    }

    // ── [05/15] 物质锻造 第 1 条（本类型共 2 条）
    //    运行期 id = shanhai:matter_forging/new_recipe_1
    // @perm index=5 id=shanhai:matter_forging/new_recipe_1 type=gtceu:matter_forging typePath=matter_forging kjsId=shanhai:new_recipe_1 seq=1 conditions=1
    try {
        var b5 = gtr.matter_forging('shanhai:new_recipe_1')
            .itemInputs('1x shanhai:proton')
            .circuit(7)
            .itemOutputs('1x shanhai:hydrogen_ion')
            .duration(200)
            .EUt(8192)
            .addData('euTier', 5)
        PERM_OK = PERM_OK + 1
        // 额外条件 1 条（物质模块等级 shanhai:material_recombination_module level=5）
        // 🔴 只走 Java 绑定（与 shanhai_recipe_overrides.js 同一个入口）；绝不 recipe.set('recipeConditions', …)
        var e5 = ShanhaiConditions.apply(b5, JsonIO.parseRaw('[{"type":"module_level","module_id":"shanhai:material_recombination_module","level":5}]'))
        if (e5 !== '') {
            PERM_COND_BAD = PERM_COND_BAD + 1
            PERM_ERRS = PERM_ERRS + '|cond shanhai:matter_forging/new_recipe_1 <= ' + e5 + ' '
        } else {
            PERM_COND_OK = PERM_COND_OK + 1
        }
    } catch (err5) {
        PERM_BAD = PERM_BAD + 1
        if (PERM_ERRS.length < 2000) { PERM_ERRS = PERM_ERRS + '|shanhai:matter_forging/new_recipe_1 <= ' + err5 + ' ' }
    }

    // ── [06/15] 物质锻造 第 2 条（本类型共 2 条）
    //    运行期 id = shanhai:matter_forging/new_recipe_2
    // @perm index=6 id=shanhai:matter_forging/new_recipe_2 type=gtceu:matter_forging typePath=matter_forging kjsId=shanhai:new_recipe_2 seq=2 conditions=1
    try {
        var b6 = gtr.matter_forging('shanhai:new_recipe_2')
            .itemInputs('2x shanhai:proton')
            .itemInputs('2x shanhai:neutron')
            .circuit(8)
            .itemOutputs('1x shanhai:helium_ion')
            .outputFluids('shanhai:zero_point_energy 10000')
            .duration(200)
            .EUt(8192)
            .addData('euTier', 5)
        PERM_OK = PERM_OK + 1
        // 额外条件 1 条（物质模块等级 shanhai:material_recombination_module level=5）
        // 🔴 只走 Java 绑定（与 shanhai_recipe_overrides.js 同一个入口）；绝不 recipe.set('recipeConditions', …)
        var e6 = ShanhaiConditions.apply(b6, JsonIO.parseRaw('[{"type":"module_level","module_id":"shanhai:material_recombination_module","level":5}]'))
        if (e6 !== '') {
            PERM_COND_BAD = PERM_COND_BAD + 1
            PERM_ERRS = PERM_ERRS + '|cond shanhai:matter_forging/new_recipe_2 <= ' + e6 + ' '
        } else {
            PERM_COND_OK = PERM_COND_OK + 1
        }
    } catch (err6) {
        PERM_BAD = PERM_BAD + 1
        if (PERM_ERRS.length < 2000) { PERM_ERRS = PERM_ERRS + '|shanhai:matter_forging/new_recipe_2 <= ' + err6 + ' ' }
    }

    // ── [07/15] 原初物质重组 第 1 条（本类型共 1 条）
    //    运行期 id = shanhai:primordial_matter_recombination/new_recipe_1
    // @perm index=7 id=shanhai:primordial_matter_recombination/new_recipe_1 type=gtceu:primordial_matter_recombination typePath=primordial_matter_recombination kjsId=shanhai:new_recipe_1 seq=1 conditions=1
    try {
        var b7 = gtr.primordial_matter_recombination('shanhai:new_recipe_1')
            .itemInputs('1x gtceu:cooperite_dust')
            .circuit(30)
            .itemOutputs('16x gtceu:platinum_group_sludge_dust')
            .duration(200)
            .EUt(2048)
            .addData('euTier', 4)
        PERM_OK = PERM_OK + 1
        // 额外条件 1 条（物质模块等级 shanhai:material_recombination_module level=5）
        // 🔴 只走 Java 绑定（与 shanhai_recipe_overrides.js 同一个入口）；绝不 recipe.set('recipeConditions', …)
        var e7 = ShanhaiConditions.apply(b7, JsonIO.parseRaw('[{"type":"module_level","module_id":"shanhai:material_recombination_module","level":5}]'))
        if (e7 !== '') {
            PERM_COND_BAD = PERM_COND_BAD + 1
            PERM_ERRS = PERM_ERRS + '|cond shanhai:primordial_matter_recombination/new_recipe_1 <= ' + e7 + ' '
        } else {
            PERM_COND_OK = PERM_COND_OK + 1
        }
    } catch (err7) {
        PERM_BAD = PERM_BAD + 1
        if (PERM_ERRS.length < 2000) { PERM_ERRS = PERM_ERRS + '|shanhai:primordial_matter_recombination/new_recipe_1 <= ' + err7 + ' ' }
    }

    // ── [08/15] 原初物质解构 第 1 条（本类型共 1 条）
    //    运行期 id = shanhai:primordial_matter_deconstruction/new_recipe_2
    // @perm index=8 id=shanhai:primordial_matter_deconstruction/new_recipe_2 type=gtceu:primordial_matter_deconstruction typePath=primordial_matter_deconstruction kjsId=shanhai:new_recipe_2 seq=1 conditions=0
    try {
        var b8 = gtr.primordial_matter_deconstruction('shanhai:new_recipe_2')
            .itemInputs('576x gtceu:platinum_group_sludge_dust')
            .itemOutputs('64x gtceu:platinum_dust')
            .itemOutputs('64x gtceu:palladium_dust')
            .itemOutputs('60x gtceu:ruthenium_dust')
            .itemOutputs('32x gtceu:iridium_dust')
            .itemOutputs('48x gtceu:rhodium_dust')
            .itemOutputs('32x gtceu:osmium_dust')
            .duration(200)
            .EUt(32)
            .addData('euTier', 1)
        PERM_OK = PERM_OK + 1
    } catch (err8) {
        PERM_BAD = PERM_BAD + 1
        if (PERM_ERRS.length < 2000) { PERM_ERRS = PERM_ERRS + '|shanhai:primordial_matter_deconstruction/new_recipe_2 <= ' + err8 + ' ' }
    }

    // ── [09/15] 太虚熔炼 第 1 条（本类型共 1 条）
    //    运行期 id = shanhai:taixu_smelting/new_recipe_1
    // @perm index=9 id=shanhai:taixu_smelting/new_recipe_1 type=gtceu:taixu_smelting typePath=taixu_smelting kjsId=shanhai:new_recipe_1 seq=1 conditions=1
    try {
        var b9 = gtr.taixu_smelting('shanhai:new_recipe_1')
            .itemInputs('4x shanhai:taixu_dust')
            .itemInputs('16x shanhai:helium_ion')
            .inputFluids('shanhai:zero_point_energy 256000')
            .inputFluids('shanhai:matter_fluid_zero 16000')
            .itemOutputs('1x shanhai:taixu_liquid_droplet')
            .duration(200)
            .EUt(8192)
            .addData('euTier', 5)
        PERM_OK = PERM_OK + 1
        // 额外条件 1 条（物质模块等级 shanhai:material_recombination_module level=5）
        // 🔴 只走 Java 绑定（与 shanhai_recipe_overrides.js 同一个入口）；绝不 recipe.set('recipeConditions', …)
        var e9 = ShanhaiConditions.apply(b9, JsonIO.parseRaw('[{"type":"module_level","module_id":"shanhai:material_recombination_module","level":5}]'))
        if (e9 !== '') {
            PERM_COND_BAD = PERM_COND_BAD + 1
            PERM_ERRS = PERM_ERRS + '|cond shanhai:taixu_smelting/new_recipe_1 <= ' + e9 + ' '
        } else {
            PERM_COND_OK = PERM_COND_OK + 1
        }
    } catch (err9) {
        PERM_BAD = PERM_BAD + 1
        if (PERM_ERRS.length < 2000) { PERM_ERRS = PERM_ERRS + '|shanhai:taixu_smelting/new_recipe_1 <= ' + err9 + ' ' }
    }

    // ── [10/15] 世线板电路组装 第 1 条（本类型共 2 条）
    //    运行期 id = shanhai:wl_board_circuit_assembly/new_recipe_3
    // @perm index=10 id=shanhai:wl_board_circuit_assembly/new_recipe_3 type=gtceu:wl_board_circuit_assembly typePath=wl_board_circuit_assembly kjsId=shanhai:new_recipe_3 seq=1 conditions=0
    try {
        var b10 = gtr.wl_board_circuit_assembly('shanhai:new_recipe_3')
            .itemInputs('2x shanhai:wl_board_mv')
            .notConsumable('1x thetornproductionline:circult_process_module_1')
            .circuit(32)
            .itemOutputs('1x shanhai:wl_board_hv')
            .duration(200)
            .EUt(2048)
            .addData('euTier', 4)
        PERM_OK = PERM_OK + 1
    } catch (err10) {
        PERM_BAD = PERM_BAD + 1
        if (PERM_ERRS.length < 2000) { PERM_ERRS = PERM_ERRS + '|shanhai:wl_board_circuit_assembly/new_recipe_3 <= ' + err10 + ' ' }
    }

    // ── [11/15] 原初因果编织 第 1 条（本类型共 2 条）
    //    运行期 id = shanhai:primordial_causal_weaving/new_recipe_1
    // @perm index=11 id=shanhai:primordial_causal_weaving/new_recipe_1 type=gtceu:primordial_causal_weaving typePath=primordial_causal_weaving kjsId=shanhai:new_recipe_1 seq=1 conditions=0
    try {
        var b11 = gtr.primordial_causal_weaving('shanhai:new_recipe_1')
            .itemInputs('1x shanhai:material_recombination_module')
            .itemInputs('1048576x gtceu:rhodium_dust')
            .itemInputs('1048576x gtceu:ruthenium_dust')
            .itemInputs('1048576x gtceu:iridium_dust')
            .itemInputs('1048576x gtceu:osmium_dust')
            .itemInputs('1048576x gtceu:platinum_dust')
            .itemInputs('1048576x gtceu:palladium_dust')
            .circuit(32)
            .itemOutputs('1x shanhai:platinum_god_proof')
            .duration(200)
            .EUt(2048)
            .addData('euTier', 4)
        PERM_OK = PERM_OK + 1
    } catch (err11) {
        PERM_BAD = PERM_BAD + 1
        if (PERM_ERRS.length < 2000) { PERM_ERRS = PERM_ERRS + '|shanhai:primordial_causal_weaving/new_recipe_1 <= ' + err11 + ' ' }
    }

    // ── [12/15] 原初世线切割 第 1 条（本类型共 1 条）
    //    运行期 id = shanhai:worldline_cutting/new_recipe_1
    // @perm index=12 id=shanhai:worldline_cutting/new_recipe_1 type=gtceu:worldline_cutting typePath=worldline_cutting kjsId=shanhai:new_recipe_1 seq=1 conditions=1
    try {
        var b12 = gtr.worldline_cutting('shanhai:new_recipe_1')
            .itemInputs('1x shanhai:worldline_residual_fragment')
            .itemInputs('16x shanhai:hydrogen_ion')
            .circuit(32)
            .itemOutputs('16x shanhai:primordial_divergence_heart')
            .duration(200)
            .EUt(8192)
            .addData('euTier', 5)
        PERM_OK = PERM_OK + 1
        // 额外条件 1 条（物质模块等级 shanhai:material_recombination_module level=5）
        // 🔴 只走 Java 绑定（与 shanhai_recipe_overrides.js 同一个入口）；绝不 recipe.set('recipeConditions', …)
        var e12 = ShanhaiConditions.apply(b12, JsonIO.parseRaw('[{"type":"module_level","module_id":"shanhai:material_recombination_module","level":5}]'))
        if (e12 !== '') {
            PERM_COND_BAD = PERM_COND_BAD + 1
            PERM_ERRS = PERM_ERRS + '|cond shanhai:worldline_cutting/new_recipe_1 <= ' + e12 + ' '
        } else {
            PERM_COND_OK = PERM_COND_OK + 1
        }
    } catch (err12) {
        PERM_BAD = PERM_BAD + 1
        if (PERM_ERRS.length < 2000) { PERM_ERRS = PERM_ERRS + '|shanhai:worldline_cutting/new_recipe_1 <= ' + err12 + ' ' }
    }

    // ── [13/15] 原初因果编织 第 2 条（本类型共 2 条）
    //    运行期 id = shanhai:primordial_causal_weaving/new_recipe_2
    // @perm index=13 id=shanhai:primordial_causal_weaving/new_recipe_2 type=gtceu:primordial_causal_weaving typePath=primordial_causal_weaving kjsId=shanhai:new_recipe_2 seq=2 conditions=0
    try {
        var b13 = gtr.primordial_causal_weaving('shanhai:new_recipe_2')
            .circuit(11)
            .itemInputs('1x gtceu:door_of_create')
            .itemInputs('1191x gtlcore:dimension_connection_casing')
            .itemInputs('78x kubejs:dimension_creation_casing')
            .itemOutputs('1x shanhai:world_fragments_creation')
            .duration(200)
            .EUt(2147483647)
            .addData('euTier', 14)
        PERM_OK = PERM_OK + 1
    } catch (err13) {
        PERM_BAD = PERM_BAD + 1
        if (PERM_ERRS.length < 2000) { PERM_ERRS = PERM_ERRS + '|shanhai:primordial_causal_weaving/new_recipe_2 <= ' + err13 + ' ' }
    }

    // ── [14/15] 物质流凝结 第 1 条（本类型共 1 条）
    //    运行期 id = shanhai:matter_flow_condensation/new_recipe_1
    // @perm index=14 id=shanhai:matter_flow_condensation/new_recipe_1 type=gtceu:matter_flow_condensation typePath=matter_flow_condensation kjsId=shanhai:new_recipe_1 seq=1 conditions=1
    try {
        var b14 = gtr.matter_flow_condensation('shanhai:new_recipe_1')
            .itemInputs('1x gtceu:quantum_star')
            .itemInputs('1x gtceu:ruridit_ingot')
            .itemInputs('1x ad_astra:ostrum_ingot')
            .inputFluids('gtceu:styrene_butadiene_rubber 144')
            .inputFluids('gtceu:polybenzimidazole 144')
            .outputFluids('shanhai:matter_fluid_zero 1000')
            .duration(200)
            .EUt(8192)
            .addData('euTier', 5)
        PERM_OK = PERM_OK + 1
        // 额外条件 1 条（物质模块等级 shanhai:material_recombination_module level=5）
        // 🔴 只走 Java 绑定（与 shanhai_recipe_overrides.js 同一个入口）；绝不 recipe.set('recipeConditions', …)
        var e14 = ShanhaiConditions.apply(b14, JsonIO.parseRaw('[{"type":"module_level","module_id":"shanhai:material_recombination_module","level":5}]'))
        if (e14 !== '') {
            PERM_COND_BAD = PERM_COND_BAD + 1
            PERM_ERRS = PERM_ERRS + '|cond shanhai:matter_flow_condensation/new_recipe_1 <= ' + e14 + ' '
        } else {
            PERM_COND_OK = PERM_COND_OK + 1
        }
    } catch (err14) {
        PERM_BAD = PERM_BAD + 1
        if (PERM_ERRS.length < 2000) { PERM_ERRS = PERM_ERRS + '|shanhai:matter_flow_condensation/new_recipe_1 <= ' + err14 + ' ' }
    }

    // ── [15/15] 世线板电路组装 第 2 条（本类型共 2 条）
    //    运行期 id = shanhai:wl_board_circuit_assembly/new_recipe_2
    // @perm index=15 id=shanhai:wl_board_circuit_assembly/new_recipe_2 type=gtceu:wl_board_circuit_assembly typePath=wl_board_circuit_assembly kjsId=shanhai:new_recipe_2 seq=2 conditions=1
    try {
        var b15 = gtr.wl_board_circuit_assembly('shanhai:new_recipe_2')
            .itemInputs('1x kubejs:iv_universal_circuit')
            .itemInputs('2x shanhai:helium_ion')
            .itemInputs('16x shanhai:hydrogen_ion')
            .itemInputs('1x shanhai:charm_quark')
            .itemInputs('1x shanhai:strange_quark')
            .itemInputs('4x shanhai:eta_meson')
            .circuit(1)
            .inputFluids('shanhai:matter_fluid_zero 2000')
            .itemOutputs('1x shanhai:wl_board_iv')
            .duration(200)
            .EUt(8192)
            .addData('euTier', 5)
        PERM_OK = PERM_OK + 1
        // 额外条件 1 条（物质模块等级 shanhai:material_recombination_module level=5）
        // 🔴 只走 Java 绑定（与 shanhai_recipe_overrides.js 同一个入口）；绝不 recipe.set('recipeConditions', …)
        var e15 = ShanhaiConditions.apply(b15, JsonIO.parseRaw('[{"type":"module_level","module_id":"shanhai:material_recombination_module","level":5}]'))
        if (e15 !== '') {
            PERM_COND_BAD = PERM_COND_BAD + 1
            PERM_ERRS = PERM_ERRS + '|cond shanhai:wl_board_circuit_assembly/new_recipe_2 <= ' + e15 + ' '
        } else {
            PERM_COND_OK = PERM_COND_OK + 1
        }
    } catch (err15) {
        PERM_BAD = PERM_BAD + 1
        if (PERM_ERRS.length < 2000) { PERM_ERRS = PERM_ERRS + '|shanhai:wl_board_circuit_assembly/new_recipe_2 <= ' + err15 + ' ' }
    }

    // 一条汇总行（与 [server_scripts]shanhai_recipes.js 的 ok/failed 口径一致）
    console.info('[SHANHAI-EDITOR-PERM] 游戏内编辑器新建配方 = ok=' + PERM_OK + ' failed=' + PERM_BAD
        + ' cond_applied=' + PERM_COND_OK + ' cond_failed=' + PERM_COND_BAD
        + ' declared=15 表长=15'
        + (PERM_OK === 15 && PERM_BAD === 0 && PERM_COND_BAD === 0 ? ' ✅' : ' 🔴 对不上'))
    if (PERM_ERRS.length > 0) {
        console.error('[SHANHAI-EDITOR-PERM] 失败明细：' + PERM_ERRS)
    }
})
