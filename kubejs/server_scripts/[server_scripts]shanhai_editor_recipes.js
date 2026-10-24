// priority: 1
// =============================================================================
// [server_scripts]shanhai_editor_recipes.js
//   —— 用户在【游戏内配方编辑器】里新建的 54 条配方，转成【永久配方】（KubeJS 版）
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
//   · 永久配方 54 条 = GT 机器配方 53 条 ＋ 原版配方 1 条（minecraft:crafting_shaped）
//   · 删除（event.remove）14 条 —— 单独一段，排在 add 段【之前】
//   · 原初奇点反演（primordial_singularity_inversion）8 条
//   · 光子分离（photon_separation）4 条
//   · 物质锻造（matter_forging）4 条
//   · 原初物质重组（primordial_matter_recombination）10 条
//   · 原初物质解构（primordial_matter_deconstruction）1 条
//   · 太虚熔炼（taixu_smelting）2 条
//   · 世线板电路组装（wl_board_circuit_assembly）3 条
//   · 原初因果编织（primordial_causal_weaving）10 条
//   · 原初世线切割（worldline_cutting）1 条
//   · 物质流凝结（matter_flow_condensation）2 条
//   · matter_module_casting（matter_module_casting）7 条
//   · 原版 minecraft:crafting_shaped（原版 minecraft:crafting_shaped）1 条
//   · worldline_sampling（worldline_sampling）1 条
//   · GT 槽位：物品输入 209（其中编程电路 45、催化剂 10、其余普通输入 154）  ※"催化剂"这一列的口径 = 一切 chance:0 的不消耗输入（含非电路的）
//   🔴 2026-10-18 手工改了两处电路（不来自同步）：原初奇点反演 第3条 补 circuit(6)、原初因果编织 第10条 电路 2→3 ⇒ 上面这两个数已含改动（物品输入 +1、电路 +1）。
//          流体输入 35、物品产出 59、流体产出 7、概率产出 1
//   · duration：全为 200（源里 recipe.duration）
//   · EUt：发射值 8 / 32 / 2048 / 8192 / 32768 / 131072 / 2147483647（档位 0,1,4,5,6,7,14 ⇒ ULV→8、LV→32、EV→2048、IV→8192、LuV→32768、ZPM→131072、MAX→2147483647；口径②：闸门与卡片显示同拍）
//   · 额外条件 35 条有（全部是module_level/cleanroom/gravity）、19 条没有（不许凭空加）
// ── 源覆盖文件的现状（🔴 2026-10-11 实测，不再是"仍然在"）──────────────────────
//   🔴 本区由 sync.mjs 每次同步时【重写】，不是手写的读数（上面的数字永远等于当下）。
//   活文件：<游戏实例>/config/shanhai/recipe_overrides.json
//     bytes 60576   entries 31   sha256 2316df4129fbbf6f0e05346b0bba0b7dda37e2b0318c56d198f4a60ac18e87bc
//     mtime 2026-10-18T04:25:47.473Z   schema_version 1
//   逐条 op 分布：add=22 set=2 remove=7
//   上面的读数是 sync.mjs 本次同步时读到的（不是手写快照）。
// ── 判据（进游戏后看这一行，可 grep）───────────────────────────────────────────
//   [SHANHAI-EDITOR-PERM] 游戏内编辑器新建配方 … ok=15 failed=0 cond_applied=9 cond_failed=0 ✅
// =============================================================================

ServerEvents.recipes(function (event) {
    var gtr = event.recipes.gtceu
    var PERM_OK = 0
    var PERM_BAD = 0
    var PERM_RM_OK = 0
    var PERM_RM_BAD = 0
    var PERM_COND_OK = 0
    var PERM_COND_BAD = 0
    var PERM_ERRS = ''
    var GravityCondition = null
    var CleanroomCondition = null
    var CleanroomType = null
    var GTLCleanroomType = null
    try {
        GravityCondition = Java.loadClass('org.gtlcore.gtlcore.common.recipe.condition.GravityCondition')
        CleanroomCondition = Java.loadClass('com.gregtechceu.gtceu.common.recipe.condition.CleanroomCondition')
        CleanroomType = Java.loadClass('com.gregtechceu.gtceu.api.machine.multiblock.CleanroomType')
        GTLCleanroomType = Java.loadClass('org.gtlcore.gtlcore.api.machine.multiblock.GTLCleanroomType')
    } catch (errPermCondCls) {
        console.error('[SHANHAI-EDITOR-PERM] 条件类解析失败（挂条件的配方会整条不注册）：' + errPermCondCls)
    }

    // ── 删除（编辑器 op=remove / op=disable）：14 条 ──────────────────────
    // @perm rm index=1 op=remove id=cxhmz:extractor/extractor_machine_blastfurnace_to_resources type=gtceu:extractor
    //     🔴 谓词只用 id：{output:…} 对 GT 配方恒 false（血账）
    try {
        event.remove({ id: 'cxhmz:extractor/extractor_machine_blastfurnace_to_resources' })
        PERM_RM_OK = PERM_RM_OK + 1
    } catch (errRm1) {
        PERM_RM_BAD = PERM_RM_BAD + 1
        PERM_ERRS = PERM_ERRS + '|remove cxhmz:extractor/extractor_machine_blastfurnace_to_resources <= ' + errRm1 + ' '
    }

    // @perm rm index=2 op=remove id=thetornproductionline:assembler/fission_reactor_module type=gtceu:assembler
    //     🔴 谓词只用 id：{output:…} 对 GT 配方恒 false（血账）
    try {
        event.remove({ id: 'thetornproductionline:assembler/fission_reactor_module' })
        PERM_RM_OK = PERM_RM_OK + 1
    } catch (errRm2) {
        PERM_RM_BAD = PERM_RM_BAD + 1
        PERM_ERRS = PERM_ERRS + '|remove thetornproductionline:assembler/fission_reactor_module <= ' + errRm2 + ' '
    }

    // @perm rm index=3 op=remove id=thetornproductionline:large_chemical_reactor/easier_paracetamol_dust type=gtceu:large_chemical_reactor
    //     🔴 谓词只用 id：{output:…} 对 GT 配方恒 false（血账）
    try {
        event.remove({ id: 'thetornproductionline:large_chemical_reactor/easier_paracetamol_dust' })
        PERM_RM_OK = PERM_RM_OK + 1
    } catch (errRm3) {
        PERM_RM_BAD = PERM_RM_BAD + 1
        PERM_ERRS = PERM_ERRS + '|remove thetornproductionline:large_chemical_reactor/easier_paracetamol_dust <= ' + errRm3 + ' '
    }

    // @perm rm index=4 op=remove id=thetornproductionline:large_chemical_reactor/easier_rad_away_dust type=gtceu:large_chemical_reactor
    //     🔴 谓词只用 id：{output:…} 对 GT 配方恒 false（血账）
    try {
        event.remove({ id: 'thetornproductionline:large_chemical_reactor/easier_rad_away_dust' })
        PERM_RM_OK = PERM_RM_OK + 1
    } catch (errRm4) {
        PERM_RM_BAD = PERM_RM_BAD + 1
        PERM_ERRS = PERM_ERRS + '|remove thetornproductionline:large_chemical_reactor/easier_rad_away_dust <= ' + errRm4 + ' '
    }

    // @perm rm index=5 op=remove id=fastblasting:ad_astra/blasting/calorite_ingot_from_blasting_raw_calorite type=minecraft:blasting
    //     🔴 谓词只用 id：{output:…} 对 GT 配方恒 false（血账）
    try {
        event.remove({ id: 'fastblasting:ad_astra/blasting/calorite_ingot_from_blasting_raw_calorite' })
        PERM_RM_OK = PERM_RM_OK + 1
    } catch (errRm5) {
        PERM_RM_BAD = PERM_RM_BAD + 1
        PERM_ERRS = PERM_ERRS + '|remove fastblasting:ad_astra/blasting/calorite_ingot_from_blasting_raw_calorite <= ' + errRm5 + ' '
    }

    // @perm rm index=6 op=remove id=fastsmelting:ad_astra/smelting/calorite_ingot_from_smelting_raw_calorite type=minecraft:smelting
    //     🔴 谓词只用 id：{output:…} 对 GT 配方恒 false（血账）
    try {
        event.remove({ id: 'fastsmelting:ad_astra/smelting/calorite_ingot_from_smelting_raw_calorite' })
        PERM_RM_OK = PERM_RM_OK + 1
    } catch (errRm6) {
        PERM_RM_BAD = PERM_RM_BAD + 1
        PERM_ERRS = PERM_ERRS + '|remove fastsmelting:ad_astra/smelting/calorite_ingot_from_smelting_raw_calorite <= ' + errRm6 + ' '
    }

    // @perm rm index=7 op=remove id=thetornproductionline:large_chemical_reactor/faster_fission_reactor_1 type=gtceu:large_chemical_reactor
    //     🔴 谓词只用 id：{output:…} 对 GT 配方恒 false（血账）
    try {
        event.remove({ id: 'thetornproductionline:large_chemical_reactor/faster_fission_reactor_1' })
        PERM_RM_OK = PERM_RM_OK + 1
    } catch (errRm7) {
        PERM_RM_BAD = PERM_RM_BAD + 1
        PERM_ERRS = PERM_ERRS + '|remove thetornproductionline:large_chemical_reactor/faster_fission_reactor_1 <= ' + errRm7 + ' '
    }

    // @perm rm index=8 op=remove id=thetornproductionline:large_chemical_reactor/faster_fission_reactor_2 type=gtceu:large_chemical_reactor
    //     🔴 谓词只用 id：{output:…} 对 GT 配方恒 false（血账）
    try {
        event.remove({ id: 'thetornproductionline:large_chemical_reactor/faster_fission_reactor_2' })
        PERM_RM_OK = PERM_RM_OK + 1
    } catch (errRm8) {
        PERM_RM_BAD = PERM_RM_BAD + 1
        PERM_ERRS = PERM_ERRS + '|remove thetornproductionline:large_chemical_reactor/faster_fission_reactor_2 <= ' + errRm8 + ' '
    }

    // @perm rm index=9 op=remove id=shanhai:spacetime_distortion/pf/primordial_debug_module type=gtceu:spacetime_distortion
    //     🔴 谓词只用 id：{output:…} 对 GT 配方恒 false（血账）
    try {
        event.remove({ id: 'shanhai:spacetime_distortion/pf/primordial_debug_module' })
        PERM_RM_OK = PERM_RM_OK + 1
    } catch (errRm9) {
        PERM_RM_BAD = PERM_RM_BAD + 1
        PERM_ERRS = PERM_ERRS + '|remove shanhai:spacetime_distortion/pf/primordial_debug_module <= ' + errRm9 + ' '
    }

    // @perm rm index=10 op=remove id=shanhai:spacetime_distortion/pf/test_item type=gtceu:spacetime_distortion
    //     🔴 谓词只用 id：{output:…} 对 GT 配方恒 false（血账）
    try {
        event.remove({ id: 'shanhai:spacetime_distortion/pf/test_item' })
        PERM_RM_OK = PERM_RM_OK + 1
    } catch (errRm10) {
        PERM_RM_BAD = PERM_RM_BAD + 1
        PERM_ERRS = PERM_ERRS + '|remove shanhai:spacetime_distortion/pf/test_item <= ' + errRm10 + ' '
    }

    // @perm rm index=11 op=remove id=thetornproductionline:assembler/neutron_activator_module type=gtceu:assembler
    //     🔴 谓词只用 id：{output:…} 对 GT 配方恒 false（血账）
    try {
        event.remove({ id: 'thetornproductionline:assembler/neutron_activator_module' })
        PERM_RM_OK = PERM_RM_OK + 1
    } catch (errRm11) {
        PERM_RM_BAD = PERM_RM_BAD + 1
        PERM_ERRS = PERM_ERRS + '|remove thetornproductionline:assembler/neutron_activator_module <= ' + errRm11 + ' '
    }

    // @perm rm index=12 op=remove id=cxbp:lightning_processor/ice_shard type=gtceu:lightning_processor
    //     🔴 谓词只用 id：{output:…} 对 GT 配方恒 false（血账）
    try {
        event.remove({ id: 'cxbp:lightning_processor/ice_shard' })
        PERM_RM_OK = PERM_RM_OK + 1
    } catch (errRm12) {
        PERM_RM_BAD = PERM_RM_BAD + 1
        PERM_ERRS = PERM_ERRS + '|remove cxbp:lightning_processor/ice_shard <= ' + errRm12 + ' '
    }

    // @perm rm index=13 op=remove id=cxbp:large_chemical_reactor/special_ceramics_dust_clay_ch type=gtceu:large_chemical_reactor
    //     🔴 谓词只用 id：{output:…} 对 GT 配方恒 false（血账）
    try {
        event.remove({ id: 'cxbp:large_chemical_reactor/special_ceramics_dust_clay_ch' })
        PERM_RM_OK = PERM_RM_OK + 1
    } catch (errRm13) {
        PERM_RM_BAD = PERM_RM_BAD + 1
        PERM_ERRS = PERM_ERRS + '|remove cxbp:large_chemical_reactor/special_ceramics_dust_clay_ch <= ' + errRm13 + ' '
    }

    // @perm rm index=14 op=remove id=cxbp:large_chemical_reactor/special_ceramics_dust_dirt_ch type=gtceu:large_chemical_reactor
    //     🔴 谓词只用 id：{output:…} 对 GT 配方恒 false（血账）
    try {
        event.remove({ id: 'cxbp:large_chemical_reactor/special_ceramics_dust_dirt_ch' })
        PERM_RM_OK = PERM_RM_OK + 1
    } catch (errRm14) {
        PERM_RM_BAD = PERM_RM_BAD + 1
        PERM_ERRS = PERM_ERRS + '|remove cxbp:large_chemical_reactor/special_ceramics_dust_dirt_ch <= ' + errRm14 + ' '
    }

    // ── [01/54] 原初奇点反演 第 1 条（本类型共 2 条）
    //    运行期 id = shanhai:primordial_singularity_inversion/new_recipe_1
    // @perm index=1 id=shanhai:primordial_singularity_inversion/new_recipe_1 type=gtceu:primordial_singularity_inversion typePath=primordial_singularity_inversion kjsId=shanhai:new_recipe_1 seq=1 conditions=0
    try {
        var b1 = gtr.primordial_singularity_inversion('shanhai:new_recipe_1')
            .itemInputs('1x shanhai:up_quark_emission_catalyst')
            .circuit(3)
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

    // ── [02/54] 原初奇点反演 第 2 条（本类型共 2 条）
    //    运行期 id = shanhai:primordial_singularity_inversion/new_recipe_2
    // @perm index=2 id=shanhai:primordial_singularity_inversion/new_recipe_2 type=gtceu:primordial_singularity_inversion typePath=primordial_singularity_inversion kjsId=shanhai:new_recipe_2 seq=2 conditions=0
    try {
        var b2 = gtr.primordial_singularity_inversion('shanhai:new_recipe_2')
            .circuit(3)
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

    // ── [03/54] 光子分离 第 1 条（本类型共 2 条）
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
            .addCondition(new ModuleLevelCondition('shanhai:material_recombination_module', 5))
        PERM_OK = PERM_OK + 1
        // 额外条件 1 条（物质模块等级 shanhai:material_recombination_module level=5）
        // 🔴 走组件通道 .addCondition(new <条件类>…)（本工程先例：shanhai_recipes.js:2807）；绝不 recipe.set('recipeConditions', …)
        PERM_COND_OK = PERM_COND_OK + 1
    } catch (err3) {
        PERM_BAD = PERM_BAD + 1
        if (PERM_ERRS.length < 2000) { PERM_ERRS = PERM_ERRS + '|shanhai:photon_separation/new_recipe_1 <= ' + err3 + ' ' }
    }

    // ── [04/54] 光子分离 第 2 条（本类型共 2 条）
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
            .addCondition(new ModuleLevelCondition('shanhai:material_recombination_module', 5))
        PERM_OK = PERM_OK + 1
        // 额外条件 1 条（物质模块等级 shanhai:material_recombination_module level=5）
        // 🔴 走组件通道 .addCondition(new <条件类>…)（本工程先例：shanhai_recipes.js:2807）；绝不 recipe.set('recipeConditions', …)
        PERM_COND_OK = PERM_COND_OK + 1
    } catch (err4) {
        PERM_BAD = PERM_BAD + 1
        if (PERM_ERRS.length < 2000) { PERM_ERRS = PERM_ERRS + '|shanhai:photon_separation/new_recipe_2 <= ' + err4 + ' ' }
    }

    // ── [05/54] 物质锻造 第 1 条（本类型共 2 条）
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
            .addCondition(new ModuleLevelCondition('shanhai:material_recombination_module', 5))
        PERM_OK = PERM_OK + 1
        // 额外条件 1 条（物质模块等级 shanhai:material_recombination_module level=5）
        // 🔴 走组件通道 .addCondition(new <条件类>…)（本工程先例：shanhai_recipes.js:2807）；绝不 recipe.set('recipeConditions', …)
        PERM_COND_OK = PERM_COND_OK + 1
    } catch (err5) {
        PERM_BAD = PERM_BAD + 1
        if (PERM_ERRS.length < 2000) { PERM_ERRS = PERM_ERRS + '|shanhai:matter_forging/new_recipe_1 <= ' + err5 + ' ' }
    }

    // ── [06/54] 物质锻造 第 2 条（本类型共 2 条）
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
            .addCondition(new ModuleLevelCondition('shanhai:material_recombination_module', 5))
        PERM_OK = PERM_OK + 1
        // 额外条件 1 条（物质模块等级 shanhai:material_recombination_module level=5）
        // 🔴 走组件通道 .addCondition(new <条件类>…)（本工程先例：shanhai_recipes.js:2807）；绝不 recipe.set('recipeConditions', …)
        PERM_COND_OK = PERM_COND_OK + 1
    } catch (err6) {
        PERM_BAD = PERM_BAD + 1
        if (PERM_ERRS.length < 2000) { PERM_ERRS = PERM_ERRS + '|shanhai:matter_forging/new_recipe_2 <= ' + err6 + ' ' }
    }

    // ── [07/54] 原初物质重组 第 1 条（本类型共 1 条）
    //    运行期 id = shanhai:primordial_matter_recombination/new_recipe_1
    // @perm index=7 id=shanhai:primordial_matter_recombination/new_recipe_1 type=gtceu:primordial_matter_recombination typePath=primordial_matter_recombination kjsId=shanhai:new_recipe_1 seq=1 conditions=1
    try {
        var b7 = gtr.primordial_matter_recombination('shanhai:new_recipe_1')
            .itemInputs('1x gtceu:cooperite_dust')
            .circuit(1)
            .itemOutputs('16x gtceu:platinum_group_sludge_dust')
            .duration(200)
            .EUt(2048)
            .addData('euTier', 4)
            .addCondition(new ModuleLevelCondition('shanhai:virtual_image_material_module', 4))
        PERM_OK = PERM_OK + 1
        // 额外条件 1 条（物质模块等级 shanhai:virtual_image_material_module level=4）
        //   2026-10-16：把用户在编辑器里设的那条条件**固化进脚本** —— 它原来靠实例的
        //   覆盖层（config\shanhai\recipe_overrides.json 的 op=set + base_fp 指纹）生效；
        //   电路号一改，配方 JSON 就变 ⇒ 指纹失配 ⇒ 那条会静默不生效。固化后不再依赖指纹。
        //   原文取自那条覆盖记录的 fields.conditions；时长 200 本来就一致（见上面的 .duration(200)）。
        // 🔴 走组件通道 .addCondition(new <条件类>…)（本工程先例：shanhai_recipes.js:2807）；绝不 recipe.set('recipeConditions', …)
        PERM_COND_OK = PERM_COND_OK + 1
    } catch (err7) {
        PERM_BAD = PERM_BAD + 1
        if (PERM_ERRS.length < 2000) { PERM_ERRS = PERM_ERRS + '|shanhai:primordial_matter_recombination/new_recipe_1 <= ' + err7 + ' ' }
    }

    // ── [08/54] 原初物质解构 第 1 条（本类型共 1 条）
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

    // ── [09/54] 太虚熔炼 第 1 条（本类型共 1 条）
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
            .addCondition(new ModuleLevelCondition('shanhai:material_recombination_module', 5))
        PERM_OK = PERM_OK + 1
        // 额外条件 1 条（物质模块等级 shanhai:material_recombination_module level=5）
        // 🔴 走组件通道 .addCondition(new <条件类>…)（本工程先例：shanhai_recipes.js:2807）；绝不 recipe.set('recipeConditions', …)
        PERM_COND_OK = PERM_COND_OK + 1
    } catch (err9) {
        PERM_BAD = PERM_BAD + 1
        if (PERM_ERRS.length < 2000) { PERM_ERRS = PERM_ERRS + '|shanhai:taixu_smelting/new_recipe_1 <= ' + err9 + ' ' }
    }

    // ── [10/54] 世线板电路组装 第 1 条（本类型共 2 条）
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

    // ── [11/54] 原初因果编织 第 1 条（本类型共 2 条）
    //    运行期 id = shanhai:primordial_causal_weaving/new_recipe_1
    // @perm index=11 id=shanhai:primordial_causal_weaving/new_recipe_1 type=gtceu:primordial_causal_weaving typePath=primordial_causal_weaving kjsId=shanhai:new_recipe_1 seq=1 conditions=0
    try {
        var b11 = gtr.primordial_causal_weaving('shanhai:new_recipe_1')
            .itemInputs('1x shanhai:material_recombination_module')
            .itemInputs('999999999x gtceu:rhodium_dust')
            .itemInputs('999999999x gtceu:ruthenium_dust')
            .itemInputs('999999999x gtceu:iridium_dust')
            .itemInputs('999999999x gtceu:osmium_dust')
            .itemInputs('999999999x gtceu:platinum_dust')
            .itemInputs('999999999x gtceu:palladium_dust')
            .circuit(7)
            .itemOutputs('1x shanhai:platinum_god_proof')
            .duration(200)
            .EUt(2048)
            .addData('euTier', 4)
        PERM_OK = PERM_OK + 1
    } catch (err11) {
        PERM_BAD = PERM_BAD + 1
        if (PERM_ERRS.length < 2000) { PERM_ERRS = PERM_ERRS + '|shanhai:primordial_causal_weaving/new_recipe_1 <= ' + err11 + ' ' }
    }

    // ── [12/54] 原初世线切割 第 1 条（本类型共 1 条）
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
            .addCondition(new ModuleLevelCondition('shanhai:material_recombination_module', 5))
        PERM_OK = PERM_OK + 1
        // 额外条件 1 条（物质模块等级 shanhai:material_recombination_module level=5）
        // 🔴 走组件通道 .addCondition(new <条件类>…)（本工程先例：shanhai_recipes.js:2807）；绝不 recipe.set('recipeConditions', …)
        PERM_COND_OK = PERM_COND_OK + 1
    } catch (err12) {
        PERM_BAD = PERM_BAD + 1
        if (PERM_ERRS.length < 2000) { PERM_ERRS = PERM_ERRS + '|shanhai:worldline_cutting/new_recipe_1 <= ' + err12 + ' ' }
    }

    // ── [13/54] 原初因果编织 第 2 条（本类型共 2 条）
    //    运行期 id = shanhai:primordial_causal_weaving/new_recipe_2
    // @perm index=13 id=shanhai:primordial_causal_weaving/new_recipe_2 type=gtceu:primordial_causal_weaving typePath=primordial_causal_weaving kjsId=shanhai:new_recipe_2 seq=2 conditions=0
    try {
        var b13 = gtr.primordial_causal_weaving('shanhai:new_recipe_2')
            .circuit(3)
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

    // ── [14/54] 物质流凝结 第 1 条（本类型共 1 条）
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
            .addCondition(new ModuleLevelCondition('shanhai:material_recombination_module', 5))
        PERM_OK = PERM_OK + 1
        // 额外条件 1 条（物质模块等级 shanhai:material_recombination_module level=5）
        // 🔴 走组件通道 .addCondition(new <条件类>…)（本工程先例：shanhai_recipes.js:2807）；绝不 recipe.set('recipeConditions', …)
        PERM_COND_OK = PERM_COND_OK + 1
    } catch (err14) {
        PERM_BAD = PERM_BAD + 1
        if (PERM_ERRS.length < 2000) { PERM_ERRS = PERM_ERRS + '|shanhai:matter_flow_condensation/new_recipe_1 <= ' + err14 + ' ' }
    }

    // ── [15/54] 世线板电路组装 第 2 条（本类型共 2 条）
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
            .addCondition(new ModuleLevelCondition('shanhai:material_recombination_module', 5))
        PERM_OK = PERM_OK + 1
        // 额外条件 1 条（物质模块等级 shanhai:material_recombination_module level=5）
        // 🔴 走组件通道 .addCondition(new <条件类>…)（本工程先例：shanhai_recipes.js:2807）；绝不 recipe.set('recipeConditions', …)
        PERM_COND_OK = PERM_COND_OK + 1
    } catch (err15) {
        PERM_BAD = PERM_BAD + 1
        if (PERM_ERRS.length < 2000) { PERM_ERRS = PERM_ERRS + '|shanhai:wl_board_circuit_assembly/new_recipe_2 <= ' + err15 + ' ' }
    }

    // ── [16/54] 物质锻造 第 3 条（本类型共 3 条）
    //    运行期 id = shanhai:matter_forging/new_recipe_3
    // @perm index=16 id=shanhai:matter_forging/new_recipe_3 type=gtceu:matter_forging typePath=matter_forging kjsId=shanhai:new_recipe_3 seq=3 conditions=1
    try {
        var b16 = gtr.matter_forging('shanhai:new_recipe_3')
            .itemInputs('1024x shanhai:helium_ion')
            .itemInputs('32767x shanhai:hydrogen_ion')
            .itemInputs('64x shanhai:graviton')
            .itemInputs('114514x shanhai:cosmic_dust')
            .itemInputs('64000x shanhai:eta_meson')
            .itemInputs('36863x shanhai:electron')
            .circuit(9)
            .inputFluids('shanhai:matter_fluid_zero 64000')
            .inputFluids('shanhai:zero_point_energy 99999999')
            .itemOutputs('1x shanhai:matter_singularity')
            .duration(200)
            .EUt(8192)
            .addData('euTier', 5)
            .addCondition(new ModuleLevelCondition('shanhai:material_recombination_module', 5))
        PERM_OK = PERM_OK + 1
        // 额外条件 1 条（物质模块等级 shanhai:material_recombination_module level=5）
        // 🔴 走组件通道 .addCondition(new <条件类>…)（本工程先例：shanhai_recipes.js:2807）；绝不 recipe.set('recipeConditions', …)
        PERM_COND_OK = PERM_COND_OK + 1
    } catch (err16) {
        PERM_BAD = PERM_BAD + 1
        if (PERM_ERRS.length < 2000) { PERM_ERRS = PERM_ERRS + '|shanhai:matter_forging/new_recipe_3 <= ' + err16 + ' ' }
    }

    // ── [17/54] 原初物质重组 第 2 条（本类型共 7 条）
    //    运行期 id = shanhai:primordial_matter_recombination/new_recipe_2
    // @perm index=17 id=shanhai:primordial_matter_recombination/new_recipe_2 type=gtceu:primordial_matter_recombination typePath=primordial_matter_recombination kjsId=shanhai:new_recipe_2 seq=2 conditions=1
    try {
        var b17 = gtr.primordial_matter_recombination('shanhai:new_recipe_2')
            .itemInputs('4x shanhai:hydrogen_ion')
            .itemInputs('1x shanhai:helium_ion')
            .itemInputs('16x shanhai:cosmic_dust')
            .itemInputs('1x shanhai:taixu_liquid_droplet')
            .itemInputs('4x shanhai:taixu_dust')
            .circuit(5)
            .itemOutputs('1x shanhai:hxsp')
            .duration(200)
            .EUt(8192)
            .addData('euTier', 5)
            .addCondition(new ModuleLevelCondition('shanhai:material_recombination_module', 5))
        PERM_OK = PERM_OK + 1
        // 额外条件 1 条（物质模块等级 shanhai:material_recombination_module level=5）
        // 🔴 走组件通道 .addCondition(new <条件类>…)（本工程先例：shanhai_recipes.js:2807）；绝不 recipe.set('recipeConditions', …)
        PERM_COND_OK = PERM_COND_OK + 1
    } catch (err17) {
        PERM_BAD = PERM_BAD + 1
        if (PERM_ERRS.length < 2000) { PERM_ERRS = PERM_ERRS + '|shanhai:primordial_matter_recombination/new_recipe_2 <= ' + err17 + ' ' }
    }

    // ── [18/54] matter_module_casting 第 1 条（本类型共 1 条）
    //    运行期 id = shanhai:matter_module_casting/new_recipe_1
    // @perm index=18 id=shanhai:matter_module_casting/new_recipe_1 type=gtceu:matter_module_casting typePath=matter_module_casting kjsId=shanhai:new_recipe_1 seq=1 conditions=2
    try {
        var b18 = gtr.matter_module_casting('shanhai:new_recipe_1')
            .itemInputs('2x shanhai:material_recombination_module')
            .itemInputs('1x shanhai:matter_singularity')
            .itemInputs('64x shanhai:navigate_prism')
            .itemInputs('2042x shanhai:hxsp')
            .itemInputs('4x shanhai:worldline_residual_fragment')
            .itemInputs('16x shanhai:wl_board_iv')
            .itemInputs('16x thetornproductionline:celestial_secret_deducing_module_iv')
            .itemInputs('666x shanhai:primordial_divergence_heart')
            .itemInputs('64x shanhai:wanxiang_core')
            .inputFluids('shanhai:matter_fluid_basic 16000')
            .inputFluids('shanhai:matter_fluid_virtual 8000')
            .inputFluids('shanhai:matter_fluid_advanced 4000')
            .inputFluids('shanhai:matter_fluid_zero 2000')
            .itemOutputs('1x shanhai:zeroing_material_module')
            .duration(200)
            .EUt(8192)
            .addData('euTier', 5)
            .addCondition(new CleanroomCondition(CleanroomType.CLEANROOM))
            .addCondition(new GravityCondition(false))
        PERM_OK = PERM_OK + 1
        // 额外条件 2 条（cleanroom / gravity）
        // 🔴 走组件通道 .addCondition(new <条件类>…)（本工程先例：shanhai_recipes.js:2807）；绝不 recipe.set('recipeConditions', …)
        PERM_COND_OK = PERM_COND_OK + 1
    } catch (err18) {
        PERM_BAD = PERM_BAD + 1
        if (PERM_ERRS.length < 2000) { PERM_ERRS = PERM_ERRS + '|shanhai:matter_module_casting/new_recipe_1 <= ' + err18 + ' ' }
    }

    // ── [19/54] 原初因果编织 第 3 条（本类型共 8 条）
    //    运行期 id = shanhai:primordial_causal_weaving/new_recipe_3
    // @perm index=19 id=shanhai:primordial_causal_weaving/new_recipe_3 type=gtceu:primordial_causal_weaving typePath=primordial_causal_weaving kjsId=shanhai:new_recipe_3 seq=3 conditions=1
    try {
        var b19 = gtr.primordial_causal_weaving('shanhai:new_recipe_3')
            .itemInputs('1x gtceu:cold_ice_freezer')
            .itemInputs('1x gtceu:blaze_blast_furnace')
            .itemInputs('1x gtceu:assembly_line')
            .itemInputs('1x gtceu:large_solidifier')
            .itemInputs('1x gtceu:large_cutter')
            .itemInputs('1x gtceu:large_extruder')
            .itemInputs('1x gtceu:chemical_energy_devourer')
            .itemInputs('1x gtceu:gas_mega_turbine')
            .itemInputs('1x gtceu:large_sifting_funnel')
            .itemInputs('1x gtceu:large_circuit_assembler')
            .itemInputs('1x gtceu:fission_reactor')
            .circuit(12)
            .inputFluids('shanhai:matter_fluid_zero 64000')
            .itemOutputs('1x shanhai:wanxiang_core')
            .duration(200)
            .EUt(8192)
            .addData('euTier', 5)
            .addCondition(new ModuleLevelCondition('shanhai:material_recombination_module', 5))
        PERM_OK = PERM_OK + 1
        // 额外条件 1 条（物质模块等级 shanhai:material_recombination_module level=5）
        // 🔴 走组件通道 .addCondition(new <条件类>…)（本工程先例：shanhai_recipes.js:2807）；绝不 recipe.set('recipeConditions', …)
        PERM_COND_OK = PERM_COND_OK + 1
    } catch (err19) {
        PERM_BAD = PERM_BAD + 1
        if (PERM_ERRS.length < 2000) { PERM_ERRS = PERM_ERRS + '|shanhai:primordial_causal_weaving/new_recipe_3 <= ' + err19 + ' ' }
    }

    // ── [20/54] 原初因果编织 第 4 条（本类型共 8 条）
    //    运行期 id = shanhai:primordial_causal_weaving/new_recipe_4
    // @perm index=20 id=shanhai:primordial_causal_weaving/new_recipe_4 type=gtceu:primordial_causal_weaving typePath=primordial_causal_weaving kjsId=shanhai:new_recipe_4 seq=4 conditions=0
    try {
        var b20 = gtr.primordial_causal_weaving('shanhai:new_recipe_4')
            .circuit(2)
            .itemInputs('4x shanhai:navigate_prism')
            .itemInputs('1x gtceu:space_elevator')
            .itemOutputs('1x shanhai:light_voyage')
            .duration(200)
            .EUt(8)
            .addData('euTier', 0)
        PERM_OK = PERM_OK + 1
    } catch (err20) {
        PERM_BAD = PERM_BAD + 1
        if (PERM_ERRS.length < 2000) { PERM_ERRS = PERM_ERRS + '|shanhai:primordial_causal_weaving/new_recipe_4 <= ' + err20 + ' ' }
    }

    // ── [21/54] 原初因果编织 第 5 条（本类型共 8 条）
    //    运行期 id = shanhai:primordial_causal_weaving/new_recipe_5
    // @perm index=21 id=shanhai:primordial_causal_weaving/new_recipe_5 type=gtceu:primordial_causal_weaving typePath=primordial_causal_weaving kjsId=shanhai:new_recipe_5 seq=5 conditions=0
    try {
        var b21 = gtr.primordial_causal_weaving('shanhai:new_recipe_5')
            .circuit(2)
            .itemInputs('4x shanhai:light_voyage')
            .itemInputs('1x gtceu:uev_compressed_fusion_reactor')
            .itemOutputs('1x shanhai:star_spark')
            .duration(200)
            .EUt(8)
            .addData('euTier', 0)
        PERM_OK = PERM_OK + 1
    } catch (err21) {
        PERM_BAD = PERM_BAD + 1
        if (PERM_ERRS.length < 2000) { PERM_ERRS = PERM_ERRS + '|shanhai:primordial_causal_weaving/new_recipe_5 <= ' + err21 + ' ' }
    }

    // ── [22/54] 原初因果编织 第 6 条（本类型共 8 条）
    //    运行期 id = shanhai:primordial_causal_weaving/new_recipe_6
    // @perm index=22 id=shanhai:primordial_causal_weaving/new_recipe_6 type=gtceu:primordial_causal_weaving typePath=primordial_causal_weaving kjsId=shanhai:new_recipe_6 seq=6 conditions=0
    try {
        var b22 = gtr.primordial_causal_weaving('shanhai:new_recipe_6')
            .circuit(2)
            .itemInputs('1x gtceu:qft')
            .itemInputs('4x shanhai:star_spark')
            .itemOutputs('1x shanhai:blue_son')
            .duration(200)
            .EUt(8)
            .addData('euTier', 0)
        PERM_OK = PERM_OK + 1
    } catch (err22) {
        PERM_BAD = PERM_BAD + 1
        if (PERM_ERRS.length < 2000) { PERM_ERRS = PERM_ERRS + '|shanhai:primordial_causal_weaving/new_recipe_6 <= ' + err22 + ' ' }
    }

    // ── [23/54] 原初奇点反演 第 3 条（本类型共 3 条）
    //    运行期 id = shanhai:primordial_singularity_inversion/new_recipe_3
    // @perm index=23 id=shanhai:primordial_singularity_inversion/new_recipe_3 type=gtceu:primordial_singularity_inversion typePath=primordial_singularity_inversion kjsId=shanhai:new_recipe_3 seq=3 conditions=1
    try {
        var b23 = gtr.primordial_singularity_inversion('shanhai:new_recipe_3')
            .circuit(6)
            .itemInputs('4x gtceu:fission_reactor')
            .itemInputs('128x gtlcore:cooler')
            .itemInputs('128x gtlcore:fission_reactor_casing')
            .itemInputs('128x gtlcore:fission_fuel_assembly')
            .itemInputs('4x shanhai:wanxiang_core')
            .itemInputs('1x shanhai:matter_singularity')
            .itemOutputs('1x thetornproductionline:fission_reactor_module')
            .duration(200)
            .EUt(8192)
            .addData('euTier', 5)
            .addCondition(new ModuleLevelCondition('shanhai:zeroing_material_module', 6))
        PERM_OK = PERM_OK + 1
        // 额外条件 1 条（物质模块等级 shanhai:zeroing_material_module level=6）
        // 🔴 走组件通道 .addCondition(new <条件类>…)（本工程先例：shanhai_recipes.js:2807）；绝不 recipe.set('recipeConditions', …)
        PERM_COND_OK = PERM_COND_OK + 1
    } catch (err23) {
        PERM_BAD = PERM_BAD + 1
        if (PERM_ERRS.length < 2000) { PERM_ERRS = PERM_ERRS + '|shanhai:primordial_singularity_inversion/new_recipe_3 <= ' + err23 + ' ' }
    }

    // ── [24/54] 原初物质重组 第 3 条（本类型共 7 条）
    //    运行期 id = shanhai:primordial_matter_recombination/new_recipe_5
    // @perm index=24 id=shanhai:primordial_matter_recombination/new_recipe_5 type=gtceu:primordial_matter_recombination typePath=primordial_matter_recombination kjsId=shanhai:new_recipe_5 seq=3 conditions=1
    try {
        var b24 = gtr.primordial_matter_recombination('shanhai:new_recipe_5')
            .circuit(7)
            .itemInputs('5x gtceu:potassium_dust')
            .itemInputs('5x gtceu:iodine_dust')
            .itemInputs('21x gtceu:iron_dust')
            .itemInputs('124x gtceu:carbon_dust')
            .inputFluids('gtceu:nitrogen 69000')
            .inputFluids('gtceu:hydrogen 115000')
            .inputFluids('gtceu:oxygen 50000')
            .itemOutputs('1x gtceu:rad_away_dust')
            .duration(200)
            .EUt(8192)
            .addData('euTier', 5)
            .addCondition(new ModuleLevelCondition('shanhai:zeroing_material_module', 6))
        PERM_OK = PERM_OK + 1
        // 额外条件 1 条（物质模块等级 shanhai:zeroing_material_module level=6）
        // 🔴 走组件通道 .addCondition(new <条件类>…)（本工程先例：shanhai_recipes.js:2807）；绝不 recipe.set('recipeConditions', …)
        PERM_COND_OK = PERM_COND_OK + 1
    } catch (err24) {
        PERM_BAD = PERM_BAD + 1
        if (PERM_ERRS.length < 2000) { PERM_ERRS = PERM_ERRS + '|shanhai:primordial_matter_recombination/new_recipe_5 <= ' + err24 + ' ' }
    }

    // ── [25/54] 原初物质重组 第 4 条（本类型共 7 条）
    //    运行期 id = shanhai:primordial_matter_recombination/new_recipe_4
    // @perm index=25 id=shanhai:primordial_matter_recombination/new_recipe_4 type=gtceu:primordial_matter_recombination typePath=primordial_matter_recombination kjsId=shanhai:new_recipe_4 seq=4 conditions=1
    try {
        var b25 = gtr.primordial_matter_recombination('shanhai:new_recipe_4')
            .itemInputs('8x gtceu:carbon_dust')
            .circuit(4)
            .inputFluids('gtceu:hydrogen 9000')
            .inputFluids('gtceu:nitrogen 1000')
            .inputFluids('gtceu:oxygen 2000')
            .itemOutputs('1x gtceu:paracetamol_dust')
            .duration(200)
            .EUt(8192)
            .addData('euTier', 5)
            .addCondition(new ModuleLevelCondition('shanhai:zeroing_material_module', 6))
        PERM_OK = PERM_OK + 1
        // 额外条件 1 条（物质模块等级 shanhai:zeroing_material_module level=6）
        // 🔴 走组件通道 .addCondition(new <条件类>…)（本工程先例：shanhai_recipes.js:2807）；绝不 recipe.set('recipeConditions', …)
        PERM_COND_OK = PERM_COND_OK + 1
    } catch (err25) {
        PERM_BAD = PERM_BAD + 1
        if (PERM_ERRS.length < 2000) { PERM_ERRS = PERM_ERRS + '|shanhai:primordial_matter_recombination/new_recipe_4 <= ' + err25 + ' ' }
    }

    // ── [26/54] 原初物质重组 第 5 条（本类型共 7 条）
    //    运行期 id = shanhai:primordial_matter_recombination/new_recipe_6
    // @perm index=26 id=shanhai:primordial_matter_recombination/new_recipe_6 type=gtceu:primordial_matter_recombination typePath=primordial_matter_recombination kjsId=shanhai:new_recipe_6 seq=5 conditions=1
    try {
        var b26 = gtr.primordial_matter_recombination('shanhai:new_recipe_6')
            .circuit(2)
            .itemInputs('1x gtceu:sphalerite_dust')
            .itemInputs('1x gtceu:galena_dust')
            .itemOutputs('1x gtceu:indium_dust')
            .duration(200)
            .EUt(8192)
            .addData('euTier', 5)
            .addCondition(new ModuleLevelCondition('shanhai:material_recombination_module', 5))
        PERM_OK = PERM_OK + 1
        // 额外条件 1 条（物质模块等级 shanhai:material_recombination_module level=5）
        // 🔴 走组件通道 .addCondition(new <条件类>…)（本工程先例：shanhai_recipes.js:2807）；绝不 recipe.set('recipeConditions', …)
        PERM_COND_OK = PERM_COND_OK + 1
    } catch (err26) {
        PERM_BAD = PERM_BAD + 1
        if (PERM_ERRS.length < 2000) { PERM_ERRS = PERM_ERRS + '|shanhai:primordial_matter_recombination/new_recipe_6 <= ' + err26 + ' ' }
    }

    // ── [27/54] 原初物质重组 第 6 条（本类型共 7 条）
    //    运行期 id = shanhai:primordial_matter_recombination/new_recipe_7
    // @perm index=27 id=shanhai:primordial_matter_recombination/new_recipe_7 type=gtceu:primordial_matter_recombination typePath=primordial_matter_recombination kjsId=shanhai:new_recipe_7 seq=6 conditions=1
    try {
        var b27 = gtr.primordial_matter_recombination('shanhai:new_recipe_7')
            .itemInputs('3x gtceu:naquadah_dust')
            .circuit(1)
            .itemOutputs('1x gtceu:enriched_naquadah_dust')
            .itemOutputs('1x gtceu:trinium_dust')
            .itemOutputs('1x gtceu:naquadria_dust')
            .duration(200)
            .EUt(32768)
            .addData('euTier', 6)
            .addCondition(new ModuleLevelCondition('shanhai:zeroing_material_module', 6))
        PERM_OK = PERM_OK + 1
        // 额外条件 1 条（物质模块等级 shanhai:zeroing_material_module level=6）
        // 🔴 走组件通道 .addCondition(new <条件类>…)（本工程先例：shanhai_recipes.js:2807）；绝不 recipe.set('recipeConditions', …)
        PERM_COND_OK = PERM_COND_OK + 1
    } catch (err27) {
        PERM_BAD = PERM_BAD + 1
        if (PERM_ERRS.length < 2000) { PERM_ERRS = PERM_ERRS + '|shanhai:primordial_matter_recombination/new_recipe_7 <= ' + err27 + ' ' }
    }

    // ── [28/54] 原初物质重组 第 7 条（本类型共 7 条）
    //    运行期 id = shanhai:primordial_matter_recombination/new_recipe_8
    // @perm index=28 id=shanhai:primordial_matter_recombination/new_recipe_8 type=gtceu:primordial_matter_recombination typePath=primordial_matter_recombination kjsId=shanhai:new_recipe_8 seq=7 conditions=1
    try {
        var b28 = gtr.primordial_matter_recombination('shanhai:new_recipe_8')
            .circuit(1)
            .inputFluids('gtceu:europium 16')
            .itemOutputs('1x gtceu:raw_crystal_chip')
            .duration(200)
            .EUt(32768)
            .addData('euTier', 6)
            .addCondition(new ModuleLevelCondition('shanhai:zeroing_material_module', 6))
        PERM_OK = PERM_OK + 1
        // 额外条件 1 条（物质模块等级 shanhai:zeroing_material_module level=6）
        // 🔴 走组件通道 .addCondition(new <条件类>…)（本工程先例：shanhai_recipes.js:2807）；绝不 recipe.set('recipeConditions', …)
        PERM_COND_OK = PERM_COND_OK + 1
    } catch (err28) {
        PERM_BAD = PERM_BAD + 1
        if (PERM_ERRS.length < 2000) { PERM_ERRS = PERM_ERRS + '|shanhai:primordial_matter_recombination/new_recipe_8 <= ' + err28 + ' ' }
    }

    // ── [29/54] undefined 第 1 条（本类型共 1 条）
    //    运行期 id = shanhai:crafting/new_recipe_1
    // @perm index=29 kind=vanilla id=shanhai:crafting/new_recipe_1 type=minecraft:crafting vanillaType=minecraft:crafting_shaped conditions=0
    try {
        var b29 = event.shaped('shanhai:primordial_cosmic_reactor', ['abc', 'bdb', 'cba'], { a: 'shanhai:material_recombination_module', b: 'gtceu:luv_fusion_reactor', c: 'shanhai:zeroing_material_module', d: 'shanhai:primordial_engine_core' }).id('shanhai:crafting/new_recipe_1')
        PERM_OK = PERM_OK + 1
    } catch (err29) {
        PERM_BAD = PERM_BAD + 1
        if (PERM_ERRS.length < 2000) { PERM_ERRS = PERM_ERRS + '|shanhai:crafting/new_recipe_1 <= ' + err29 + ' ' }
    }

    // ── [30/54] 原初因果编织 第 7 条（本类型共 8 条）
    //    运行期 id = shanhai:primordial_causal_weaving/new_recipe_7
    // @perm index=30 id=shanhai:primordial_causal_weaving/new_recipe_7 type=gtceu:primordial_causal_weaving typePath=primordial_causal_weaving kjsId=shanhai:new_recipe_7 seq=7 conditions=0
    try {
        var b30 = gtr.primordial_causal_weaving('shanhai:new_recipe_7')
            .circuit(3)
            .notConsumable('1x thetornproductionline:fission_reactor_module')
            .itemInputs('15x gtceu:plutonium_dust')
            .itemInputs('90x gtceu:uranium_dust')
            .itemOutputs('16x kubejs:nuclear_waste')
            .duration(200)
            .EUt(8192)
            .addData('euTier', 5)
        PERM_OK = PERM_OK + 1
    } catch (err30) {
        PERM_BAD = PERM_BAD + 1
        if (PERM_ERRS.length < 2000) { PERM_ERRS = PERM_ERRS + '|shanhai:primordial_causal_weaving/new_recipe_7 <= ' + err30 + ' ' }
    }

    // ── [31/54] 原初因果编织 第 8 条（本类型共 8 条）
    //    运行期 id = shanhai:primordial_causal_weaving/new_recipe_8
    // @perm index=31 id=shanhai:primordial_causal_weaving/new_recipe_8 type=gtceu:primordial_causal_weaving typePath=primordial_causal_weaving kjsId=shanhai:new_recipe_8 seq=8 conditions=0
    try {
        var b31 = gtr.primordial_causal_weaving('shanhai:new_recipe_8')
            .circuit(2)
            .notConsumable('1x thetornproductionline:fission_reactor_module')
            .itemInputs('15x gtceu:thorium_dust')
            .itemOutputs('2x kubejs:nuclear_waste')
            .itemOutputs('1x gtceu:uranium_dust')
            .duration(200)
            .EUt(8192)
            .addData('euTier', 5)
        PERM_OK = PERM_OK + 1
    } catch (err31) {
        PERM_BAD = PERM_BAD + 1
        if (PERM_ERRS.length < 2000) { PERM_ERRS = PERM_ERRS + '|shanhai:primordial_causal_weaving/new_recipe_8 <= ' + err31 + ' ' }
    }

    // ── [32/54] 物质流凝结 第 2 条（本类型共 2 条）
    //    运行期 id = shanhai:matter_flow_condensation/new_recipe_2
    // @perm index=32 id=shanhai:matter_flow_condensation/new_recipe_2 type=gtceu:matter_flow_condensation typePath=matter_flow_condensation kjsId=shanhai:new_recipe_2 seq=2 conditions=1
    try {
        var b32 = gtr.matter_flow_condensation('shanhai:new_recipe_2')
            .itemInputs('1x gtceu:uranium_rhodium_dinaquadide_ingot')
            .itemInputs('1x gtceu:indium_tin_barium_titanium_cuprate_ingot')
            .itemInputs('1x gtceu:naquadria_ingot')
            .inputFluids('gtceu:duranium 144')
            .inputFluids('gtceu:europium 144')
            .outputFluids('shanhai:matter_fluid_darkstar 1000')
            .duration(200)
            .EUt(32768)
            .addData('euTier', 6)
            .addCondition(new ModuleLevelCondition('shanhai:zeroing_material_module', 6))
        PERM_OK = PERM_OK + 1
        // 额外条件 1 条（物质模块等级 shanhai:zeroing_material_module level=6）
        // 🔴 走组件通道 .addCondition(new <条件类>…)（本工程先例：shanhai_recipes.js:2807）；绝不 recipe.set('recipeConditions', …)
        PERM_COND_OK = PERM_COND_OK + 1
    } catch (err32) {
        PERM_BAD = PERM_BAD + 1
        if (PERM_ERRS.length < 2000) { PERM_ERRS = PERM_ERRS + '|shanhai:matter_flow_condensation/new_recipe_2 <= ' + err32 + ' ' }
    }

    // ── [33/54] 原初奇点反演 第 4 条（本类型共 8 条）
    //    运行期 id = shanhai:primordial_singularity_inversion/new_recipe_4
    // @perm index=33 id=shanhai:primordial_singularity_inversion/new_recipe_4 type=gtceu:primordial_singularity_inversion typePath=primordial_singularity_inversion kjsId=shanhai:new_recipe_4 seq=4 conditions=1
    try {
        var b33 = gtr.primordial_singularity_inversion('shanhai:new_recipe_4')
            .circuit(5)
            .itemInputs('1x shanhai:wl_board_ev')
            .itemInputs('64x ae2:fluix_glass_cable')
            .itemInputs('16x gtlcore:treasures_crystal')
            .itemInputs('1x thetornproductionline:celestial_secret_deducing_module_ev')
            .inputFluids('shanhai:zero_point_energy 666666')
            .itemOutputs('64x shanhai:primordial_engine_core')
            .duration(200)
            .EUt(8192)
            .addData('euTier', 5)
            .addCondition(new ModuleLevelCondition('shanhai:material_recombination_module', 5))
        PERM_OK = PERM_OK + 1
        // 额外条件 1 条（物质模块等级 shanhai:material_recombination_module level=5）
        // 🔴 走组件通道 .addCondition(new <条件类>…)（本工程先例：shanhai_recipes.js:2807）；绝不 recipe.set('recipeConditions', …)
        PERM_COND_OK = PERM_COND_OK + 1
    } catch (err33) {
        PERM_BAD = PERM_BAD + 1
        if (PERM_ERRS.length < 2000) { PERM_ERRS = PERM_ERRS + '|shanhai:primordial_singularity_inversion/new_recipe_4 <= ' + err33 + ' ' }
    }

    // ── [34/54] 光子分离 第 3 条（本类型共 4 条）
    //    运行期 id = shanhai:photon_separation/new_recipe_3
    // @perm index=34 id=shanhai:photon_separation/new_recipe_3 type=gtceu:photon_separation typePath=photon_separation kjsId=shanhai:new_recipe_3 seq=3 conditions=1
    try {
        var b34 = gtr.photon_separation('shanhai:new_recipe_3')
            .itemInputs('64x shanhai:photon_rainbow')
            .circuit(4)
            .itemInputs('16x shanhai:eta_meson')
            .itemOutputs('1x shanhai:tau')
            .chancedOutput('1x shanhai:tau_neutrino', 100, 0)
            .outputFluids('shanhai:zero_point_energy 123000')
            .duration(200)
            .EUt(32768)
            .addData('euTier', 6)
            .addCondition(new ModuleLevelCondition('shanhai:zeroing_material_module', 6))
        PERM_OK = PERM_OK + 1
        // 额外条件 1 条（物质模块等级 shanhai:zeroing_material_module level=6）
        // 🔴 走组件通道 .addCondition(new <条件类>…)（本工程先例：shanhai_recipes.js:2807）；绝不 recipe.set('recipeConditions', …)
        PERM_COND_OK = PERM_COND_OK + 1
    } catch (err34) {
        PERM_BAD = PERM_BAD + 1
        if (PERM_ERRS.length < 2000) { PERM_ERRS = PERM_ERRS + '|shanhai:photon_separation/new_recipe_3 <= ' + err34 + ' ' }
    }

    // ── [35/54] 光子分离 第 4 条（本类型共 4 条）
    //    运行期 id = shanhai:photon_separation/new_recipe_4
    // @perm index=35 id=shanhai:photon_separation/new_recipe_4 type=gtceu:photon_separation typePath=photon_separation kjsId=shanhai:new_recipe_4 seq=4 conditions=1
    try {
        var b35 = gtr.photon_separation('shanhai:new_recipe_4')
            .itemInputs('1024x shanhai:photon_rainbow')
            .itemInputs('1x shanhai:matter_singularity')
            .circuit(4)
            .itemInputs('666x shanhai:eta_meson')
            .itemOutputs('32x shanhai:higgs_boson')
            .outputFluids('shanhai:zero_point_energy 1024000')
            .duration(200)
            .EUt(32768)
            .addData('euTier', 6)
            .addCondition(new ModuleLevelCondition('shanhai:zeroing_material_module', 6))
        PERM_OK = PERM_OK + 1
        // 额外条件 1 条（物质模块等级 shanhai:zeroing_material_module level=6）
        // 🔴 走组件通道 .addCondition(new <条件类>…)（本工程先例：shanhai_recipes.js:2807）；绝不 recipe.set('recipeConditions', …)
        PERM_COND_OK = PERM_COND_OK + 1
    } catch (err35) {
        PERM_BAD = PERM_BAD + 1
        if (PERM_ERRS.length < 2000) { PERM_ERRS = PERM_ERRS + '|shanhai:photon_separation/new_recipe_4 <= ' + err35 + ' ' }
    }

    // ── [36/54] 物质锻造 第 4 条（本类型共 4 条）
    //    运行期 id = shanhai:matter_forging/new_recipe_4
    // @perm index=36 id=shanhai:matter_forging/new_recipe_4 type=gtceu:matter_forging typePath=matter_forging kjsId=shanhai:new_recipe_4 seq=4 conditions=1
    try {
        var b36 = gtr.matter_forging('shanhai:new_recipe_4')
            .itemInputs('2x shanhai:charm_quark')
            .itemInputs('4x shanhai:gluon')
            .itemInputs('64x shanhai:eta_meson')
            .circuit(10)
            .itemOutputs('1x shanhai:j_psi_meson')
            .duration(200)
            .EUt(32768)
            .addData('euTier', 6)
            .addCondition(new ModuleLevelCondition('shanhai:zeroing_material_module', 6))
        PERM_OK = PERM_OK + 1
        // 额外条件 1 条（物质模块等级 shanhai:zeroing_material_module level=6）
        // 🔴 走组件通道 .addCondition(new <条件类>…)（本工程先例：shanhai_recipes.js:2807）；绝不 recipe.set('recipeConditions', …)
        PERM_COND_OK = PERM_COND_OK + 1
    } catch (err36) {
        PERM_BAD = PERM_BAD + 1
        if (PERM_ERRS.length < 2000) { PERM_ERRS = PERM_ERRS + '|shanhai:matter_forging/new_recipe_4 <= ' + err36 + ' ' }
    }

    // ── [37/54] 原初奇点反演 第 5 条（本类型共 8 条）
    //    运行期 id = shanhai:primordial_singularity_inversion/new_recipe_5
    // @perm index=37 id=shanhai:primordial_singularity_inversion/new_recipe_5 type=gtceu:primordial_singularity_inversion typePath=primordial_singularity_inversion kjsId=shanhai:new_recipe_5 seq=5 conditions=1
    try {
        var b37 = gtr.primordial_singularity_inversion('shanhai:new_recipe_5')
            .circuit(4)
            .itemInputs('325x shanhai:tau')
            .itemInputs('64x shanhai:j_psi_meson')
            .itemInputs('1x gtceu:luv_compressed_fusion_reactor')
            .inputFluids('shanhai:matter_fluid_darkstar 64000')
            .itemOutputs('1x shanhai:nova_catalyst')
            .duration(200)
            .EUt(32768)
            .addData('euTier', 6)
            .addCondition(new ModuleLevelCondition('shanhai:zeroing_material_module', 6))
        PERM_OK = PERM_OK + 1
        // 额外条件 1 条（物质模块等级 shanhai:zeroing_material_module level=6）
        // 🔴 走组件通道 .addCondition(new <条件类>…)（本工程先例：shanhai_recipes.js:2807）；绝不 recipe.set('recipeConditions', …)
        PERM_COND_OK = PERM_COND_OK + 1
    } catch (err37) {
        PERM_BAD = PERM_BAD + 1
        if (PERM_ERRS.length < 2000) { PERM_ERRS = PERM_ERRS + '|shanhai:primordial_singularity_inversion/new_recipe_5 <= ' + err37 + ' ' }
    }

    // ── [38/54] 原初奇点反演 第 6 条（本类型共 8 条）
    //    运行期 id = shanhai:primordial_singularity_inversion/new_recipe_6
    // @perm index=38 id=shanhai:primordial_singularity_inversion/new_recipe_6 type=gtceu:primordial_singularity_inversion typePath=primordial_singularity_inversion kjsId=shanhai:new_recipe_6 seq=6 conditions=0
    try {
        var b38 = gtr.primordial_singularity_inversion('shanhai:new_recipe_6')
            .circuit(10)
            .itemInputs('64x shanhai:dark_star_material_module')
            .itemInputs('32767x shanhai:tau')
            .itemInputs('32767x shanhai:higgs_boson')
            .itemInputs('123456x shanhai:eta_meson')
            .itemInputs('666x shanhai:wem_1')
            .itemInputs('1024x shanhai:wl_board_luv')
            .itemInputs('64x shanhai:nova_catalyst')
            .inputFluids('shanhai:matter_fluid_darkstar 1024000')
            .inputFluids('shanhai:matter_fluid_zero 2048000')
            .inputFluids('shanhai:matter_fluid_advanced 4096000')
            .itemOutputs('1x shanhai:material_module_mass_production_module_mk1')
            .duration(200)
            .EUt(32768)
            .addData('euTier', 6)
        PERM_OK = PERM_OK + 1
    } catch (err38) {
        PERM_BAD = PERM_BAD + 1
        if (PERM_ERRS.length < 2000) { PERM_ERRS = PERM_ERRS + '|shanhai:primordial_singularity_inversion/new_recipe_6 <= ' + err38 + ' ' }
    }

    // ── [39/54] 原初因果编织 第 9 条（本类型共 10 条）
    //    运行期 id = shanhai:primordial_causal_weaving/new_recipe_9
    // @perm index=39 id=shanhai:primordial_causal_weaving/new_recipe_9 type=gtceu:primordial_causal_weaving typePath=primordial_causal_weaving kjsId=shanhai:new_recipe_9 seq=9 conditions=1
    try {
        var b39 = gtr.primordial_causal_weaving('shanhai:new_recipe_9')
            .circuit(4)
            .itemInputs('1x shanhai:bhd_collapser')
            .itemInputs('666000x shanhai:hxsp')
            .itemInputs('325999x shanhai:graviton')
            .itemInputs('444444x shanhai:j_psi_meson')
            .itemOutputs('1x shanhai:bhd_hyper_seed')
            .duration(200)
            .EUt(131072)
            .addData('euTier', 7)
            .addCondition(new ModuleLevelCondition('shanhai:dark_star_material_module', 7))
        PERM_OK = PERM_OK + 1
        // 额外条件 1 条（物质模块等级 shanhai:dark_star_material_module level=7）
        // 🔴 走组件通道 .addCondition(new <条件类>…)（本工程先例：shanhai_recipes.js:2807）；绝不 recipe.set('recipeConditions', …)
        PERM_COND_OK = PERM_COND_OK + 1
    } catch (err39) {
        PERM_BAD = PERM_BAD + 1
        if (PERM_ERRS.length < 2000) { PERM_ERRS = PERM_ERRS + '|shanhai:primordial_causal_weaving/new_recipe_9 <= ' + err39 + ' ' }
    }

    // ── [40/54] 原初奇点反演 第 7 条（本类型共 8 条）
    //    运行期 id = shanhai:primordial_singularity_inversion/new_recipe_7
    // @perm index=40 id=shanhai:primordial_singularity_inversion/new_recipe_7 type=gtceu:primordial_singularity_inversion typePath=primordial_singularity_inversion kjsId=shanhai:new_recipe_7 seq=7 conditions=1
    try {
        var b40 = gtr.primordial_singularity_inversion('shanhai:new_recipe_7')
            .circuit(1)
            .itemInputs('16x shanhai:nova_catalyst')
            .itemOutputs('1x shanhai:bhd_collapser')
            .duration(200)
            .EUt(131072)
            .addData('euTier', 7)
            .addCondition(new ModuleLevelCondition('shanhai:dark_star_material_module', 7))
        PERM_OK = PERM_OK + 1
        // 额外条件 1 条（物质模块等级 shanhai:dark_star_material_module level=7）
        // 🔴 走组件通道 .addCondition(new <条件类>…)（本工程先例：shanhai_recipes.js:2807）；绝不 recipe.set('recipeConditions', …)
        PERM_COND_OK = PERM_COND_OK + 1
    } catch (err40) {
        PERM_BAD = PERM_BAD + 1
        if (PERM_ERRS.length < 2000) { PERM_ERRS = PERM_ERRS + '|shanhai:primordial_singularity_inversion/new_recipe_7 <= ' + err40 + ' ' }
    }

    // ── [41/54] 原初因果编织 第 10 条（本类型共 10 条）
    //    运行期 id = shanhai:primordial_causal_weaving/new_recipe_10
    // @perm index=41 id=shanhai:primordial_causal_weaving/new_recipe_10 type=gtceu:primordial_causal_weaving typePath=primordial_causal_weaving kjsId=shanhai:new_recipe_10 seq=10 conditions=1
    try {
        var b41 = gtr.primordial_causal_weaving('shanhai:new_recipe_10')
            .circuit(3)
            .itemInputs('1024x shanhai:hxsp')
            .itemInputs('16x shanhai:nova_catalyst')
            .itemInputs('4444x shanhai:graviton')
            .itemOutputs('1x shanhai:artificial_neutron_star')
            .duration(200)
            .EUt(32768)
            .addData('euTier', 6)
            .addCondition(new ModuleLevelCondition('shanhai:zeroing_material_module', 6))
        PERM_OK = PERM_OK + 1
        // 额外条件 1 条（物质模块等级 shanhai:zeroing_material_module level=6）
        // 🔴 走组件通道 .addCondition(new <条件类>…)（本工程先例：shanhai_recipes.js:2807）；绝不 recipe.set('recipeConditions', …)
        PERM_COND_OK = PERM_COND_OK + 1
    } catch (err41) {
        PERM_BAD = PERM_BAD + 1
        if (PERM_ERRS.length < 2000) { PERM_ERRS = PERM_ERRS + '|shanhai:primordial_causal_weaving/new_recipe_10 <= ' + err41 + ' ' }
    }

    // ── [42/54] matter_module_casting 第 2 条（本类型共 7 条）
    //    运行期 id = shanhai:matter_module_casting/new_recipe_2
    // @perm index=42 id=shanhai:matter_module_casting/new_recipe_2 type=gtceu:matter_module_casting typePath=matter_module_casting kjsId=shanhai:new_recipe_2 seq=2 conditions=2
    try {
        var b42 = gtr.matter_module_casting('shanhai:new_recipe_2')
            .itemInputs('4x shanhai:zeroing_material_module')
            .itemInputs('64x shanhai:wl_board_luv')
            .itemInputs('16x shanhai:artificial_neutron_star')
            .itemInputs('64x thetornproductionline:celestial_secret_deducing_module_luv')
            .inputFluids('shanhai:matter_fluid_darkstar 4000')
            .inputFluids('shanhai:matter_fluid_zero 8000')
            .inputFluids('shanhai:matter_fluid_advanced 16000')
            .inputFluids('shanhai:matter_fluid_virtual 64000')
            .itemOutputs('1x shanhai:dark_star_material_module')
            .duration(200)
            .EUt(32768)
            .addData('euTier', 6)
            .addCondition(new CleanroomCondition(CleanroomType.CLEANROOM))
            .addCondition(new GravityCondition(false))
        PERM_OK = PERM_OK + 1
        // 额外条件 2 条（cleanroom / gravity）
        // 🔴 走组件通道 .addCondition(new <条件类>…)（本工程先例：shanhai_recipes.js:2807）；绝不 recipe.set('recipeConditions', …)
        PERM_COND_OK = PERM_COND_OK + 1
    } catch (err42) {
        PERM_BAD = PERM_BAD + 1
        if (PERM_ERRS.length < 2000) { PERM_ERRS = PERM_ERRS + '|shanhai:matter_module_casting/new_recipe_2 <= ' + err42 + ' ' }
    }

    // ── [43/54] worldline_sampling 第 1 条（本类型共 1 条）
    //    运行期 id = shanhai:worldline_sampling/new_recipe_1
    // @perm index=43 id=shanhai:worldline_sampling/new_recipe_1 type=gtceu:worldline_sampling typePath=worldline_sampling kjsId=shanhai:new_recipe_1 seq=1 conditions=1
    try {
        var b43 = gtr.worldline_sampling('shanhai:new_recipe_1')
            .itemInputs('1x shanhai:bhd_hyper_seed')
            .itemInputs('1024x shanhai:taixu_crystal_core')
            .itemInputs('32767x shanhai:primordial_divergence_heart')
            .inputFluids('shanhai:matter_fluid_transition 99000')
            .itemOutputs('1x shanhai:advanced_worldline_crystal_core')
            .duration(200)
            .EUt(131072)
            .addData('euTier', 7)
            .addCondition(new ModuleLevelCondition('shanhai:dark_star_material_module', 7))
        PERM_OK = PERM_OK + 1
        // 额外条件 1 条（物质模块等级 shanhai:dark_star_material_module level=7）
        // 🔴 走组件通道 .addCondition(new <条件类>…)（本工程先例：shanhai_recipes.js:2807）；绝不 recipe.set('recipeConditions', …)
        PERM_COND_OK = PERM_COND_OK + 1
    } catch (err43) {
        PERM_BAD = PERM_BAD + 1
        if (PERM_ERRS.length < 2000) { PERM_ERRS = PERM_ERRS + '|shanhai:worldline_sampling/new_recipe_1 <= ' + err43 + ' ' }
    }

    // ── [44/54] 太虚熔炼 第 2 条（本类型共 2 条）
    //    运行期 id = shanhai:taixu_smelting/new_recipe_2
    // @perm index=44 id=shanhai:taixu_smelting/new_recipe_2 type=gtceu:taixu_smelting typePath=taixu_smelting kjsId=shanhai:new_recipe_2 seq=2 conditions=2
    try {
        var b44 = gtr.taixu_smelting('shanhai:new_recipe_2')
            .itemInputs('16x shanhai:taixu_liquid_droplet')
            .itemInputs('1x shanhai:artificial_neutron_star')
            .inputFluids('shanhai:matter_fluid_transition 1000')
            .itemOutputs('1x shanhai:taixu_crystal_core')
            .duration(200)
            .EUt(131072)
            .addData('euTier', 7)
            .addCondition(new ModuleLevelCondition('shanhai:dark_star_material_module', 7))
            .addCondition(new GravityCondition(false))
        PERM_OK = PERM_OK + 1
        // 额外条件 2 条（物质模块等级 shanhai:dark_star_material_module level=7 / gravity）
        // 🔴 走组件通道 .addCondition(new <条件类>…)（本工程先例：shanhai_recipes.js:2807）；绝不 recipe.set('recipeConditions', …)
        PERM_COND_OK = PERM_COND_OK + 1
    } catch (err44) {
        PERM_BAD = PERM_BAD + 1
        if (PERM_ERRS.length < 2000) { PERM_ERRS = PERM_ERRS + '|shanhai:taixu_smelting/new_recipe_2 <= ' + err44 + ' ' }
    }

    // ── [45/54] 世线板电路组装 第 3 条（本类型共 3 条）
    //    运行期 id = shanhai:wl_board_circuit_assembly/new_recipe_1
    // @perm index=45 id=shanhai:wl_board_circuit_assembly/new_recipe_1 type=gtceu:wl_board_circuit_assembly typePath=wl_board_circuit_assembly kjsId=shanhai:new_recipe_1 seq=3 conditions=1
    try {
        var b45 = gtr.wl_board_circuit_assembly('shanhai:new_recipe_1')
            .circuit(1)
            .itemInputs('4x kubejs:luv_universal_circuit')
            .itemInputs('64x shanhai:higgs_boson')
            .itemInputs('1024x shanhai:eta_meson')
            .itemInputs('16x shanhai:tau')
            .itemInputs('16x shanhai:j_psi_meson')
            .inputFluids('shanhai:matter_fluid_darkstar 16000')
            .itemOutputs('1x shanhai:wl_board_luv')
            .duration(200)
            .EUt(32768)
            .addData('euTier', 6)
            .addCondition(new ModuleLevelCondition('shanhai:zeroing_material_module', 6))
        PERM_OK = PERM_OK + 1
        // 额外条件 1 条（物质模块等级 shanhai:zeroing_material_module level=6）
        // 🔴 走组件通道 .addCondition(new <条件类>…)（本工程先例：shanhai_recipes.js:2807）；绝不 recipe.set('recipeConditions', …)
        PERM_COND_OK = PERM_COND_OK + 1
    } catch (err45) {
        PERM_BAD = PERM_BAD + 1
        if (PERM_ERRS.length < 2000) { PERM_ERRS = PERM_ERRS + '|shanhai:wl_board_circuit_assembly/new_recipe_1 <= ' + err45 + ' ' }
    }

    // ── [46/54] matter_module_casting 第 3 条（本类型共 7 条）
    //    运行期 id = shanhai:matter_module_casting/new_recipe_3
    // @perm index=46 id=shanhai:matter_module_casting/new_recipe_3 type=gtceu:matter_module_casting typePath=matter_module_casting kjsId=shanhai:new_recipe_3 seq=3 conditions=0
    try {
        var b46 = gtr.matter_module_casting('shanhai:new_recipe_3')
            .notConsumable('1x shanhai:material_module_mass_production_module_mk1')
            .circuit(32)
            .itemInputs('1x shanhai:first_light')
            .itemOutputs('1x shanhai:introductory_material_module')
            .duration(200)
            .EUt(8)
            .addData('euTier', 0)
        PERM_OK = PERM_OK + 1
    } catch (err46) {
        PERM_BAD = PERM_BAD + 1
        if (PERM_ERRS.length < 2000) { PERM_ERRS = PERM_ERRS + '|shanhai:matter_module_casting/new_recipe_3 <= ' + err46 + ' ' }
    }

    // ── [47/54] matter_module_casting 第 4 条（本类型共 7 条）
    //    运行期 id = shanhai:matter_module_casting/new_recipe_4
    // @perm index=47 id=shanhai:matter_module_casting/new_recipe_4 type=gtceu:matter_module_casting typePath=matter_module_casting kjsId=shanhai:new_recipe_4 seq=4 conditions=0
    try {
        var b47 = gtr.matter_module_casting('shanhai:new_recipe_4')
            .notConsumable('1x shanhai:material_module_mass_production_module_mk1')
            .circuit(32)
            .itemInputs('1x shanhai:introductory_material_module')
            .itemInputs('1x shanhai:first_light')
            .itemOutputs('1x shanhai:basic_material_module')
            .duration(200)
            .EUt(8)
            .addData('euTier', 0)
        PERM_OK = PERM_OK + 1
    } catch (err47) {
        PERM_BAD = PERM_BAD + 1
        if (PERM_ERRS.length < 2000) { PERM_ERRS = PERM_ERRS + '|shanhai:matter_module_casting/new_recipe_4 <= ' + err47 + ' ' }
    }

    // ── [48/54] matter_module_casting 第 5 条（本类型共 7 条）
    //    运行期 id = shanhai:matter_module_casting/new_recipe_5
    // @perm index=48 id=shanhai:matter_module_casting/new_recipe_5 type=gtceu:matter_module_casting typePath=matter_module_casting kjsId=shanhai:new_recipe_5 seq=5 conditions=0
    try {
        var b48 = gtr.matter_module_casting('shanhai:new_recipe_5')
            .notConsumable('1x shanhai:material_module_mass_production_module_mk1')
            .circuit(32)
            .itemInputs('1x shanhai:basic_material_module')
            .itemInputs('2x shanhai:first_light')
            .itemOutputs('1x shanhai:material_deduction_module')
            .duration(200)
            .EUt(8)
            .addData('euTier', 0)
        PERM_OK = PERM_OK + 1
    } catch (err48) {
        PERM_BAD = PERM_BAD + 1
        if (PERM_ERRS.length < 2000) { PERM_ERRS = PERM_ERRS + '|shanhai:matter_module_casting/new_recipe_5 <= ' + err48 + ' ' }
    }

    // ── [49/54] matter_module_casting 第 6 条（本类型共 7 条）
    //    运行期 id = shanhai:matter_module_casting/new_recipe_6
    // @perm index=49 id=shanhai:matter_module_casting/new_recipe_6 type=gtceu:matter_module_casting typePath=matter_module_casting kjsId=shanhai:new_recipe_6 seq=6 conditions=0
    try {
        var b49 = gtr.matter_module_casting('shanhai:new_recipe_6')
            .notConsumable('1x shanhai:material_module_mass_production_module_mk1')
            .circuit(32)
            .itemInputs('1x shanhai:material_deduction_module')
            .itemInputs('1x shanhai:navigate_prism')
            .itemOutputs('1x shanhai:virtual_image_material_module')
            .duration(200)
            .EUt(8)
            .addData('euTier', 0)
        PERM_OK = PERM_OK + 1
    } catch (err49) {
        PERM_BAD = PERM_BAD + 1
        if (PERM_ERRS.length < 2000) { PERM_ERRS = PERM_ERRS + '|shanhai:matter_module_casting/new_recipe_6 <= ' + err49 + ' ' }
    }

    // ── [50/54] matter_module_casting 第 7 条（本类型共 7 条）
    //    运行期 id = shanhai:matter_module_casting/new_recipe_7
    // @perm index=50 id=shanhai:matter_module_casting/new_recipe_7 type=gtceu:matter_module_casting typePath=matter_module_casting kjsId=shanhai:new_recipe_7 seq=7 conditions=0
    try {
        var b50 = gtr.matter_module_casting('shanhai:new_recipe_7')
            .notConsumable('1x shanhai:material_module_mass_production_module_mk1')
            .circuit(32)
            .itemInputs('1x shanhai:virtual_image_material_module')
            .itemInputs('2x shanhai:navigate_prism')
            .itemOutputs('1x shanhai:material_recombination_module')
            .duration(200)
            .EUt(8)
            .addData('euTier', 0)
        PERM_OK = PERM_OK + 1
    } catch (err50) {
        PERM_BAD = PERM_BAD + 1
        if (PERM_ERRS.length < 2000) { PERM_ERRS = PERM_ERRS + '|shanhai:matter_module_casting/new_recipe_7 <= ' + err50 + ' ' }
    }

    // ── [51/54] 原初奇点反演 第 8 条（本类型共 8 条）
    //    运行期 id = shanhai:primordial_singularity_inversion/new_recipe_8
    // @perm index=51 id=shanhai:primordial_singularity_inversion/new_recipe_8 type=gtceu:primordial_singularity_inversion typePath=primordial_singularity_inversion kjsId=shanhai:new_recipe_8 seq=8 conditions=0
    try {
        var b51 = gtr.primordial_singularity_inversion('shanhai:new_recipe_8')
            .circuit(4)
            .itemInputs('16x gtceu:neutron_activator')
            .itemInputs('64x gtlcore:process_machine_casing')
            .itemInputs('64x kubejs:speeding_pipe')
            .itemInputs('1x shanhai:dark_star_material_module')
            .itemOutputs('1x thetornproductionline:neutron_activator_module')
            .duration(200)
            .EUt(32768)
            .addData('euTier', 6)
        PERM_OK = PERM_OK + 1
    } catch (err51) {
        PERM_BAD = PERM_BAD + 1
        if (PERM_ERRS.length < 2000) { PERM_ERRS = PERM_ERRS + '|shanhai:primordial_singularity_inversion/new_recipe_8 <= ' + err51 + ' ' }
    }

    // ── [52/54] 原初物质重组 第 8 条（本类型共 10 条）
    //    运行期 id = shanhai:primordial_matter_recombination/new_recipe_3
    // @perm index=52 id=shanhai:primordial_matter_recombination/new_recipe_3 type=gtceu:primordial_matter_recombination typePath=primordial_matter_recombination kjsId=shanhai:new_recipe_3 seq=8 conditions=1
    try {
        var b52 = gtr.primordial_matter_recombination('shanhai:new_recipe_3')
            .circuit(1)
            .inputFluids('minecraft:water 81000')
            .itemOutputs('1x ad_astra:ice_shard')
            .duration(200)
            .EUt(8192)
            .addData('euTier', 5)
            .addCondition(new ModuleLevelCondition('shanhai:zeroing_material_module', 6))
        PERM_OK = PERM_OK + 1
        // 额外条件 1 条（物质模块等级 shanhai:zeroing_material_module level=6）
        // 🔴 走组件通道 .addCondition(new <条件类>…)（本工程先例：shanhai_recipes.js:2807）；绝不 recipe.set('recipeConditions', …)
        PERM_COND_OK = PERM_COND_OK + 1
    } catch (err52) {
        PERM_BAD = PERM_BAD + 1
        if (PERM_ERRS.length < 2000) { PERM_ERRS = PERM_ERRS + '|shanhai:primordial_matter_recombination/new_recipe_3 <= ' + err52 + ' ' }
    }

    // ── [53/54] 原初物质重组 第 9 条（本类型共 10 条）
    //    运行期 id = shanhai:primordial_matter_recombination/new_recipe_9
    // @perm index=53 id=shanhai:primordial_matter_recombination/new_recipe_9 type=gtceu:primordial_matter_recombination typePath=primordial_matter_recombination kjsId=shanhai:new_recipe_9 seq=9 conditions=1
    try {
        var b53 = gtr.primordial_matter_recombination('shanhai:new_recipe_9')
            .circuit(2)
            .itemInputs('1x gtceu:clay_dust')
            .inputFluids('minecraft:water 1000')
            .itemOutputs('1x gtceu:special_ceramics_dust')
            .duration(200)
            .EUt(8192)
            .addData('euTier', 5)
            .addCondition(new ModuleLevelCondition('shanhai:zeroing_material_module', 6))
        PERM_OK = PERM_OK + 1
        // 额外条件 1 条（物质模块等级 shanhai:zeroing_material_module level=6）
        // 🔴 走组件通道 .addCondition(new <条件类>…)（本工程先例：shanhai_recipes.js:2807）；绝不 recipe.set('recipeConditions', …)
        PERM_COND_OK = PERM_COND_OK + 1
    } catch (err53) {
        PERM_BAD = PERM_BAD + 1
        if (PERM_ERRS.length < 2000) { PERM_ERRS = PERM_ERRS + '|shanhai:primordial_matter_recombination/new_recipe_9 <= ' + err53 + ' ' }
    }

    // ── [54/54] 原初物质重组 第 10 条（本类型共 10 条）
    //    运行期 id = shanhai:primordial_matter_recombination/new_recipe_10
    // @perm index=54 id=shanhai:primordial_matter_recombination/new_recipe_10 type=gtceu:primordial_matter_recombination typePath=primordial_matter_recombination kjsId=shanhai:new_recipe_10 seq=10 conditions=1
    try {
        var b54 = gtr.primordial_matter_recombination('shanhai:new_recipe_10')
            .circuit(2)
            .itemInputs('720x minecraft:nether_wart')
            .itemInputs('48x gtceu:carbon_dust')
            .itemOutputs('1x gtceu:germanium_dust')
            .duration(200)
            .EUt(32768)
            .addData('euTier', 6)
            .addCondition(new ModuleLevelCondition('shanhai:dark_star_material_module', 7))
        PERM_OK = PERM_OK + 1
        // 额外条件 1 条（物质模块等级 shanhai:dark_star_material_module level=7）
        // 🔴 走组件通道 .addCondition(new <条件类>…)（本工程先例：shanhai_recipes.js:2807）；绝不 recipe.set('recipeConditions', …)
        PERM_COND_OK = PERM_COND_OK + 1
    } catch (err54) {
        PERM_BAD = PERM_BAD + 1
        if (PERM_ERRS.length < 2000) { PERM_ERRS = PERM_ERRS + '|shanhai:primordial_matter_recombination/new_recipe_10 <= ' + err54 + ' ' }
    }

    // 一条汇总行（与 [server_scripts]shanhai_recipes.js 的 ok/failed 口径一致）
    console.info('[SHANHAI-EDITOR-PERM] 游戏内编辑器配方 = ok=' + PERM_OK + ' failed=' + PERM_BAD
        + ' rm_ok=' + PERM_RM_OK + ' rm_failed=' + PERM_RM_BAD
        + ' cond_applied=' + PERM_COND_OK + ' cond_failed=' + PERM_COND_BAD
        + ' declared=54 表长=54 rm_declared=14'
        + (PERM_OK === 54 && PERM_BAD === 0 && PERM_RM_OK === 14 && PERM_RM_BAD === 0 && PERM_COND_BAD === 0 ? ' ✅' : ' 🔴 对不上'))
    if (PERM_ERRS.length > 0) {
        console.error('[SHANHAI-EDITOR-PERM] 失败明细：' + PERM_ERRS)
    }
})