// priority: 1
// =============================================================================
// [server_scripts]shanhai_paradigm_recipes.js
//   —— 把【深度化学扭曲仪】(gtceu:distort) 的配方整理成【原初范式简化】(gtceu:primordial_paradigm_simplification) 的简化副本
//
// 用户原话（2026-10-31，逐字）：
//   「把深度化学扭曲仪的配方都整理出来，我刚 kubejs export，有些重复的你自动去除一下。之后，
//    你把这些配方的催化剂都去除，然后都写入原初范式简化，编程电路按照我之前说的按照输入物品数来定」
//
// ── 数据来源（只读，未改动任何实例文件）──────────────────────────────────────
//   <实例>\local\kubejs\export\recipes\**  ＋  added_recipes\**  里【顶层 "type" == "gtceu:distort"】的 json
//   取证：recipes 151 条（活配方表）；added_recipes 138 条与其中 138 条【逐字节相同】(sha256 138/138)
//         ⇒ 那是导出布局把同一批配方列了两遍，不是 138 条重复配方 ⇒ 只算 151 条。
//
// ── 条数：151 → 去重丢弃 54 → 写 97 条 ────────────────────────────
//
// ── 口径①②③（都在 temp\paradigm\取证.md / 配方生成.md 里有读数）────────────────
//   ① 去重键 = 输入(物品＋流体，含数量)＋输出＋时长；
//      去催化剂【之前】按此键：151 条两两不同（重复组 = 0）；
//      去催化剂【之后】同一键有 54 条落到别人的键上 ⇒ 才是"重复"，只留一条：
//      优先留 gtceu:distort/portable_distort/*（便携扭曲那批，原配方带编程电路），
//      其次留 gtceu:distort/*（宿主原批），再其次 kubejs/cxhmz；
//      丢弃 thetornproductionline:distort/easier_*（产线撕裂那批，用机器方块当催化剂的便宜版）。
//      （🔴 电路号不进去重键：新电路号是按输入种类数自动算的，同形状必然同号 ⇒ 救不了重复）
//   ② 去掉所有催化剂：原配方里 chance == 0 的输入（= .notConsumable(...)，89 种 / 176 条目）整条【不写】；本文件里一条 .notConsumable 都没有。
//      去催化剂后仍有输入的配方 = 全部 97 条（没有一条因为"输入全被删空"而掉队）；
//      其中 7 条只剩流体输入（合法：机器照样能跑）。
//   ③ 编程电路号 = 去催化剂后的【输入物品种类数 ＋ 输入流体种类数】（电路自己不算）
//      撞号（同一号 + 两条配方的输入多重集有包含关系，机器分不出来）时：**同一号内按配方 id 字典序**，
//      第 1 条保持原号，其余依次 +1 偏移，**直到全局空号**（本版落到 18..32；GT 电路上限 32）。
//      本版偏移 15 条，明细见每条配方上方的注释。
//
// ── 条件（cleanroom / blastFurnaceTemp）──────────────────────────────────────
//   来源 = 导出 json 的顶层 recipeConditions ＋ data.ebf_temp（导出确实带条件字段）；
//   并回源 4 个脚本（gtceu.js / [server_scripts]dgy.js / 产线撕裂.js / 产线爆破.js）的
//   gtr.distort(...) / addPortableDistort(...) 调用逐条对拍：cleanroom 138/138、
//   blastFurnaceTemp 138/138、编程电路 138/138 全一致（另 13 条是 gtladditions 数据包配方，源在 jar 里）。
//   ⇒ .cleanroom(CleanroomType.X) / .blastFurnaceTemp(N) 照原样搬过来。
//
// ── 判据（进游戏后看这一行，可 grep）───────────────────────────────────────────
//   [SHANHAI-PARADIGM] 声明=97 条（去重丢弃 54、去催化剂 176、电路偏移 15） ok=97 failed=0
// =============================================================================

ServerEvents.recipes(function (event) {
    var gtr = event.recipes.gtceu
    var CleanroomType = null
    var GTLCleanroomType = null
    try {
        CleanroomType = Java.loadClass('com.gregtechceu.gtceu.api.machine.multiblock.CleanroomType')
        GTLCleanroomType = Java.loadClass('org.gtlcore.gtlcore.api.machine.multiblock.GTLCleanroomType')
    } catch (errParaCls) {
        console.error('[SHANHAI-PARADIGM] 条件类解析失败（带 cleanroom 的配方会整条不注册）：' + errParaCls)
    }
    var PARA_OK = 0
    var PARA_BAD = 0
    var PARA_ERRS = ''

    // ── [1/97] 源 cxbp:distort/easier_potassium_ethylxanthate_dust_di  (EUt=30720 dur=256 ebf=800)
    //    新 id = shanhai:primordial_paradigm_simplification/paradigm/easier_potassium_ethylxanthate_dust_di   电路 5   输入种类 物品3+流体2
    try {
        var b1 = gtr.primordial_paradigm_simplification('shanhai:paradigm/easier_potassium_ethylxanthate_dust_di')
        .itemInputs('3x gtceu:carbon_dust', '2x gtceu:sulfur_dust', '1x gtceu:potassium_dust')
        .circuit(5)
        .inputFluids('gtceu:hydrogen 5000', 'gtceu:oxygen 1000')
        .itemOutputs('12x gtceu:potassium_ethylxanthate_dust')
        .duration(256)
        .EUt(30720)
        .blastFurnaceTemp(800)
        PARA_OK = PARA_OK + 1
    } catch (errPara1) {
        PARA_BAD = PARA_BAD + 1
        if (PARA_ERRS.length < 2000) { PARA_ERRS = PARA_ERRS + '|shanhai:paradigm/easier_potassium_ethylxanthate_dust_di <= ' + errPara1 + ' ' }
    }

    // ── [2/97] 源 cxbp:distort/easier_sodium_ethylxanthate_dust_di  (EUt=30720 dur=256 ebf=800)
    //    新 id = shanhai:primordial_paradigm_simplification/paradigm/easier_sodium_ethylxanthate_dust_di   电路 5   输入种类 物品3+流体2
    try {
        var b2 = gtr.primordial_paradigm_simplification('shanhai:paradigm/easier_sodium_ethylxanthate_dust_di')
        .itemInputs('3x gtceu:carbon_dust', '2x gtceu:sulfur_dust', '1x gtceu:sodium_dust')
        .circuit(5)
        .inputFluids('gtceu:hydrogen 5000', 'gtceu:oxygen 1000')
        .itemOutputs('12x gtceu:sodium_ethylxanthate_dust')
        .duration(256)
        .EUt(30720)
        .blastFurnaceTemp(800)
        PARA_OK = PARA_OK + 1
    } catch (errPara2) {
        PARA_BAD = PARA_BAD + 1
        if (PARA_ERRS.length < 2000) { PARA_ERRS = PARA_ERRS + '|shanhai:paradigm/easier_sodium_ethylxanthate_dust_di <= ' + errPara2 + ' ' }
    }

    // ── [3/97] 源 cxbp:distort/tcetieseaweedextract  (EUt=31457280 dur=2400 ebf=16800)
    //    新 id = shanhai:primordial_paradigm_simplification/paradigm/cxbp__tcetieseaweedextract   电路 10   输入种类 物品7+流体3
    try {
        var b3 = gtr.primordial_paradigm_simplification('shanhai:paradigm/cxbp__tcetieseaweedextract')
        .itemInputs('256x minecraft:kelp', '128x gtceu:alien_algae_dust', '64x gtceu:energium_dust', '64x gtceu:mithril_dust', '64x gtceu:salt_dust', '64x gtceu:meat_dust', '64x gtceu:agar_dust')
        .circuit(10)
        .inputFluids('gtceu:unknowwater 16000', 'gtceu:phthalic_acid 16000', 'gtceu:methane 50000')
        .itemOutputs('256x kubejs:tcetieseaweedextract')
        .duration(2400)
        .EUt(31457280)
        .blastFurnaceTemp(16800)
        .cleanroom(CleanroomType.CLEANROOM)
        PARA_OK = PARA_OK + 1
    } catch (errPara3) {
        PARA_BAD = PARA_BAD + 1
        if (PARA_ERRS.length < 2000) { PARA_ERRS = PARA_ERRS + '|shanhai:paradigm/cxbp__tcetieseaweedextract <= ' + errPara3 + ' ' }
    }

    // ── [4/97] 源 cxhmz:distort/actinium_solution  (EUt=1638400 dur=1 ebf=14400)
    //    新 id = shanhai:primordial_paradigm_simplification/paradigm/actinium_solution   电路 5   输入种类 物品4+流体1
    try {
        var b4 = gtr.primordial_paradigm_simplification('shanhai:paradigm/actinium_solution')
        .itemInputs('64x gtceu:actinium_trinium_hydroxides_dust', '2x gtceu:fullerene_dust', '32x gtceu:radium_dust', '64x gtceu:sodium_hydroxide_dust')
        .circuit(5)
        .inputFluids('minecraft:water 160000')
        .outputFluids('gtceu:actinium_radium_hydroxide_solution 2000')
        .duration(1)
        .EUt(1638400)
        .blastFurnaceTemp(14400)
        PARA_OK = PARA_OK + 1
    } catch (errPara4) {
        PARA_BAD = PARA_BAD + 1
        if (PARA_ERRS.length < 2000) { PARA_ERRS = PARA_ERRS + '|shanhai:paradigm/actinium_solution <= ' + errPara4 + ' ' }
    }

    // ── [5/97] 源 gtceu:distort/cosmic_superconductor  (EUt=2013265920 dur=4000 ebf=36000)
    //    新 id = shanhai:primordial_paradigm_simplification/paradigm/cosmic_superconductor   电路 17   输入种类 物品8+流体9
    try {
        var b5 = gtr.primordial_paradigm_simplification('shanhai:paradigm/cosmic_superconductor')
        .itemInputs('1x gtceu:phosphorus_dust', '18x gtceu:sulfur_dust', '6x gtceu:sodium_dust', '1x gtceu:thallium_dust', '6x gtceu:rhenium_chloride_dust', '5x gtceu:hassium_chloride_dust', '36x gtceu:atinium_hydride_dust', '14x gtceu:charged_caesium_cerium_cobalt_indium_dust')
        .circuit(17)
        .inputFluids('gtceu:chlorine 34000', 'gtceu:fluorine 3000', 'gtceu:ethylene 12000', 'gtceu:oxygen 46000', 'gtceu:hydrogen 102000', 'gtceu:uu_matter 2000', 'gtceu:quark_gluon_plasma 17000', 'gtceu:benzene 3000', 'gtceu:acetone 6000')
        .outputFluids('gtceu:cosmic_superconductor 10000')
        .duration(4000)
        .EUt(2013265920)
        .blastFurnaceTemp(36000)
        .cleanroom(GTLCleanroomType.LAW_CLEANROOM)
        PARA_OK = PARA_OK + 1
    } catch (errPara5) {
        PARA_BAD = PARA_BAD + 1
        if (PARA_ERRS.length < 2000) { PARA_ERRS = PARA_ERRS + '|shanhai:paradigm/cosmic_superconductor <= ' + errPara5 + ' ' }
    }

    // ── [6/97] 源 gtceu:distort/euv_photoresist  (EUt=503316480 dur=1280 ebf=21600)
    //    新 id = shanhai:primordial_paradigm_simplification/paradigm/euv_photoresist   电路 13   输入种类 物品6+流体7
    try {
        var b6 = gtr.primordial_paradigm_simplification('shanhai:paradigm/euv_photoresist')
        .itemInputs('30x gtceu:rhenium_dust', '56x gtceu:lithium_dust', '40x gtceu:selenium_dust', '50x gtceu:activated_carbon_dust', '60x gtceu:rutile_dust', '55x gtceu:quicklime_dust')
        .circuit(13)
        .inputFluids('gtceu:ethane 12000', 'gtceu:chlorine 75000', 'gtceu:photoresist 8000', 'gtceu:hydrogen 4700', 'gtceu:oxygen 89000', 'gtceu:nitrogen 40000', 'gtceu:butane 57000')
        .outputFluids('gtceu:euv_photoresist 21600')
        .duration(1280)
        .EUt(503316480)
        .blastFurnaceTemp(21600)
        .cleanroom(CleanroomType.CLEANROOM)
        PARA_OK = PARA_OK + 1
    } catch (errPara6) {
        PARA_BAD = PARA_BAD + 1
        if (PARA_ERRS.length < 2000) { PARA_ERRS = PARA_ERRS + '|shanhai:paradigm/euv_photoresist <= ' + errPara6 + ' ' }
    }

    // ── [7/97] 源 gtceu:distort/hexanitrohexaaxaisowurtzitane_dust  (EUt=125829120 dur=5120 ebf=21600)
    //    新 id = shanhai:primordial_paradigm_simplification/paradigm/hexanitrohexaaxaisowurtzitane_dust   电路 16   输入种类 物品7+流体9
    try {
        var b7 = gtr.primordial_paradigm_simplification('shanhai:paradigm/hexanitrohexaaxaisowurtzitane_dust')
        .itemInputs('191x gtceu:silica_gel_dust', '76x gtceu:succinic_acid_dust', '144x gtceu:activated_carbon_dust', '216x gtceu:sodium_dust', '47x gtceu:boron_trioxide_dust', '39x gtceu:potassium_carbonate_dust', '101x gtceu:barium_chloride_dust')
        .circuit(16)
        .inputFluids('gtceu:hydrogen 470000', 'gtceu:hydrofluoric_acid 12000', 'gtceu:methanol 62000', 'gtceu:nitric_acid 15000', 'gtceu:ammonia 39000', 'gtceu:glyoxal 47000', 'gtceu:oxygen_plasma 11000', 'gtceu:acetic_anhydride 9000', 'gtceu:nitrogen_plasma 7000')
        .itemOutputs('288x gtceu:hexanitrohexaaxaisowurtzitane_dust')
        .duration(5120)
        .EUt(125829120)
        .blastFurnaceTemp(21600)
        .cleanroom(CleanroomType.CLEANROOM)
        PARA_OK = PARA_OK + 1
    } catch (errPara7) {
        PARA_BAD = PARA_BAD + 1
        if (PARA_ERRS.length < 2000) { PARA_ERRS = PARA_ERRS + '|shanhai:paradigm/hexanitrohexaaxaisowurtzitane_dust <= ' + errPara7 + ' ' }
    }

    // ── [8/97] 源 gtceu:distort/photopolymer  (EUt=2013265920 dur=2560 ebf=21600)
    //    新 id = shanhai:primordial_paradigm_simplification/paradigm/photopolymer   电路 14   输入种类 物品8+流体6
    try {
        var b8 = gtr.primordial_paradigm_simplification('shanhai:paradigm/photopolymer')
        .itemInputs('768x gtceu:carbon_dust', '50x gtceu:rutile_dust', '70x gtceu:succinic_acid_dust', '32x gtceu:ice_dust', '20x gtceu:silver_dust', '25x gtceu:quicklime_dust', '40x gtceu:boron_dust', '120x gtceu:sodium_dust')
        .circuit(14)
        .inputFluids('gtceu:benzene 40000', 'gtceu:bromine 25000', 'gtceu:oxygen 21600', 'gtceu:chlorine 5600', 'gtceu:propene 16000', 'gtceu:butene 80000')
        .outputFluids('gtceu:photopolymer 16000')
        .duration(2560)
        .EUt(2013265920)
        .blastFurnaceTemp(21600)
        .cleanroom(CleanroomType.CLEANROOM)
        PARA_OK = PARA_OK + 1
    } catch (errPara8) {
        PARA_BAD = PARA_BAD + 1
        if (PARA_ERRS.length < 2000) { PARA_ERRS = PARA_ERRS + '|shanhai:paradigm/photopolymer <= ' + errPara8 + ' ' }
    }

    // ── [9/97] 源 gtceu:distort/photoresist  (EUt=7864320 dur=960 ebf=16200)
    //    新 id = shanhai:primordial_paradigm_simplification/paradigm/photoresist   电路 9   输入种类 物品3+流体6
    try {
        var b9 = gtr.primordial_paradigm_simplification('shanhai:paradigm/photoresist')
        .itemInputs('91x gtceu:rutile_dust', '60x gtceu:carbon_dust', '42x gtceu:sodium_hydroxide_dust')
        .circuit(9)
        .inputFluids('gtceu:sulfuric_acid 80000', 'gtceu:ethanol 7000', 'gtceu:chlorine 81000', 'gtceu:propene 15000', 'gtceu:benzene 39000', 'gtceu:ethylene 47000')
        .outputFluids('gtceu:photoresist 16000')
        .duration(960)
        .EUt(7864320)
        .blastFurnaceTemp(16200)
        .cleanroom(CleanroomType.CLEANROOM)
        PARA_OK = PARA_OK + 1
    } catch (errPara9) {
        PARA_BAD = PARA_BAD + 1
        if (PARA_ERRS.length < 2000) { PARA_ERRS = PARA_ERRS + '|shanhai:paradigm/photoresist <= ' + errPara9 + ' ' }
    }

    // ── [10/97] 源 gtceu:distort/platinum_dust  (EUt=491520 dur=240 ebf=9000)
    //    新 id = shanhai:primordial_paradigm_simplification/paradigm/platinum_dust   电路 5   输入种类 物品2+流体3
    try {
        var b10 = gtr.primordial_paradigm_simplification('shanhai:paradigm/platinum_dust')
        .itemInputs('576x gtceu:platinum_group_sludge_dust', '16x gtceu:sulfur_dust')
        .circuit(5)
        .inputFluids('gtceu:hydrogen 72000', 'gtceu:oxygen 128000', 'gtceu:chlorine 14400')
        .itemOutputs('64x gtceu:platinum_dust', '64x gtceu:palladium_dust', '60x gtceu:ruthenium_dust', '32x gtceu:iridium_dust', '48x gtceu:rhodium_dust', '32x gtceu:osmium_dust')
        .outputFluids('gtceu:hydrogen 2800', 'minecraft:water 7200', 'gtceu:chlorine 6400')
        .duration(240)
        .EUt(491520)
        .blastFurnaceTemp(9000)
        PARA_OK = PARA_OK + 1
    } catch (errPara10) {
        PARA_BAD = PARA_BAD + 1
        if (PARA_ERRS.length < 2000) { PARA_ERRS = PARA_ERRS + '|shanhai:paradigm/platinum_dust <= ' + errPara10 + ' ' }
    }

    // ── [11/97] 源 gtceu:distort/polyetheretherketone  (EUt=31457280 dur=1640 ebf=14400)
    //    新 id = shanhai:primordial_paradigm_simplification/paradigm/polyetheretherketone   电路 6   输入种类 物品1+流体5
    try {
        var b11 = gtr.primordial_paradigm_simplification('shanhai:paradigm/polyetheretherketone')
        .itemInputs('16x gtceu:sodium_dust')
        .circuit(6)
        .inputFluids('gtceu:chlorine 48000', 'gtceu:benzene 16000', 'gtceu:oxygen 60000', 'gtceu:propene 8000', 'gtceu:nitric_acid 8000')
        .itemOutputs('32x gtceu:sodium_fluoride_dust')
        .outputFluids('gtceu:polyetheretherketone 20736', 'minecraft:water 8000', 'gtceu:carbon_dioxide 8000')
        .duration(1640)
        .EUt(31457280)
        .blastFurnaceTemp(14400)
        .cleanroom(CleanroomType.CLEANROOM)
        PARA_OK = PARA_OK + 1
    } catch (errPara11) {
        PARA_BAD = PARA_BAD + 1
        if (PARA_ERRS.length < 2000) { PARA_ERRS = PARA_ERRS + '|shanhai:paradigm/polyetheretherketone <= ' + errPara11 + ' ' }
    }

    // ── [12/97] 源 gtceu:distort/polyimide  (EUt=8388608 dur=200 ebf=14400)
    //    新 id = shanhai:primordial_paradigm_simplification/paradigm/polyimide   电路 3   输入种类 物品1+流体2
    try {
        var b12 = gtr.primordial_paradigm_simplification('shanhai:paradigm/polyimide')
        .itemInputs('486x gtceu:carbon_dust')
        .circuit(3)
        .inputFluids('gtceu:hydrogen 256000', 'gtceu:nitrogen_dioxide 64000')
        .outputFluids('gtceu:polyimide 21000', 'gtceu:hydrogen 12800', 'gtceu:nitrogen 6400')
        .duration(200)
        .EUt(8388608)
        .blastFurnaceTemp(14400)
        .cleanroom(CleanroomType.CLEANROOM)
        PARA_OK = PARA_OK + 1
    } catch (errPara12) {
        PARA_BAD = PARA_BAD + 1
        if (PARA_ERRS.length < 2000) { PARA_ERRS = PARA_ERRS + '|shanhai:paradigm/polyimide <= ' + errPara12 + ' ' }
    }

    // ── [13/97] 源 gtceu:distort/polyvinyl_butyral  (EUt=491520 dur=240 ebf=6000)
    //    新 id = shanhai:primordial_paradigm_simplification/paradigm/polyvinyl_butyral   电路 7   输入种类 物品2+流体5
    try {
        var b13 = gtr.primordial_paradigm_simplification('shanhai:paradigm/polyvinyl_butyral')
        .itemInputs('96x gtceu:carbon_dust', '4x gtceu:rutile_dust')
        .circuit(7)
        .inputFluids('gtceu:hydrogen 52000', 'gtceu:oxygen 32000', 'gtceu:chlorine 4000', 'gtceu:propene 1000', 'gtceu:ethylene 10000')
        .outputFluids('gtceu:polyvinyl_butyral 36864')
        .duration(240)
        .EUt(491520)
        .blastFurnaceTemp(6000)
        PARA_OK = PARA_OK + 1
    } catch (errPara13) {
        PARA_BAD = PARA_BAD + 1
        if (PARA_ERRS.length < 2000) { PARA_ERRS = PARA_ERRS + '|shanhai:paradigm/polyvinyl_butyral <= ' + errPara13 + ' ' }
    }

    // ── [14/97] 源 gtceu:distort/portable_distort/acidic_enriched_naquadah_solution  (EUt=491520 dur=20 ebf=800)
    //    新 id = shanhai:primordial_paradigm_simplification/paradigm/portable_distort/acidic_enriched_naquadah_solution   电路 2   输入种类 物品1+流体1
    try {
        var b14 = gtr.primordial_paradigm_simplification('shanhai:paradigm/portable_distort/acidic_enriched_naquadah_solution')
        .itemInputs('1x gtceu:naquadria_dust')
        .circuit(2)
        .inputFluids('gtceu:sulfuric_acid 2000')
        .outputFluids('gtceu:acidic_enriched_naquadah_solution 3000')
        .duration(20)
        .EUt(491520)
        .blastFurnaceTemp(800)
        PARA_OK = PARA_OK + 1
    } catch (errPara14) {
        PARA_BAD = PARA_BAD + 1
        if (PARA_ERRS.length < 2000) { PARA_ERRS = PARA_ERRS + '|shanhai:paradigm/portable_distort/acidic_enriched_naquadah_solution <= ' + errPara14 + ' ' }
    }

    // ── [15/97] 源 gtceu:distort/portable_distort/acidic_naquadria_solution  (EUt=491520 dur=20 ebf=800)
    //    新 id = shanhai:primordial_paradigm_simplification/paradigm/portable_distort/acidic_naquadria_solution   电路 2   输入种类 物品1+流体1
    try {
        var b15 = gtr.primordial_paradigm_simplification('shanhai:paradigm/portable_distort/acidic_naquadria_solution')
        .itemInputs('1x gtceu:enriched_naquadah_dust')
        .circuit(2)
        .inputFluids('gtceu:sulfuric_acid 2000')
        .outputFluids('gtceu:acidic_naquadria_solution 3000')
        .duration(20)
        .EUt(491520)
        .blastFurnaceTemp(800)
        PARA_OK = PARA_OK + 1
    } catch (errPara15) {
        PARA_BAD = PARA_BAD + 1
        if (PARA_ERRS.length < 2000) { PARA_ERRS = PARA_ERRS + '|shanhai:paradigm/portable_distort/acidic_naquadria_solution <= ' + errPara15 + ' ' }
    }

    // ── [16/97] 源 gtceu:distort/portable_distort/aerographene  (EUt=491520 dur=200 ebf=800)
    //    新 id = shanhai:primordial_paradigm_simplification/paradigm/portable_distort/aerographene   电路 9   输入种类 物品1+流体8
    try {
        var b16 = gtr.primordial_paradigm_simplification('shanhai:paradigm/portable_distort/aerographene')
        .itemInputs('2x gtceu:sugar_gem')
        .circuit(9)
        .inputFluids('gtceu:oxygen 4000', 'gtceu:chlorine 3000', 'gtceu:propene 1000', 'minecraft:water 1000', 'gtceu:benzene 1000', 'gtceu:acetone 1000', 'gtceu:methanol 1000', 'gtceu:carbon_dioxide 1000')
        .itemOutputs('1x kubejs:aerographene')
        .duration(200)
        .EUt(491520)
        .blastFurnaceTemp(800)
        PARA_OK = PARA_OK + 1
    } catch (errPara16) {
        PARA_BAD = PARA_BAD + 1
        if (PARA_ERRS.length < 2000) { PARA_ERRS = PARA_ERRS + '|shanhai:paradigm/portable_distort/aerographene <= ' + errPara16 + ' ' }
    }

    // ── [17/97] 源 gtceu:distort/portable_distort/agar_dust  (EUt=1966080 dur=1 ebf=800)
    //    新 id = shanhai:primordial_paradigm_simplification/paradigm/portable_distort/agar_dust   电路 6   输入种类 物品4+流体2
    try {
        var b17 = gtr.primordial_paradigm_simplification('shanhai:paradigm/portable_distort/agar_dust')
        .itemInputs('256x gtceu:meat_dust', '256x minecraft:bone', '64x gtceu:sulfur_dust', '16x gtceu:phosphorus_dust')
        .circuit(6)
        .inputFluids('gtceu:distilled_water 128000', 'gtceu:oxygen 128000')
        .itemOutputs('384x gtceu:agar_dust')
        .duration(1)
        .EUt(1966080)
        .blastFurnaceTemp(800)
        .cleanroom(CleanroomType.STERILE_CLEANROOM)
        PARA_OK = PARA_OK + 1
    } catch (errPara17) {
        PARA_BAD = PARA_BAD + 1
        if (PARA_ERRS.length < 2000) { PARA_ERRS = PARA_ERRS + '|shanhai:paradigm/portable_distort/agar_dust <= ' + errPara17 + ' ' }
    }

    // ── [18/97] 源 gtceu:distort/portable_distort/azafullerene  (EUt=491520 dur=200 ebf=800)
    //    新 id = shanhai:primordial_paradigm_simplification/paradigm/portable_distort/azafullerene   电路 3   输入种类 物品1+流体2
    try {
        var b18 = gtr.primordial_paradigm_simplification('shanhai:paradigm/portable_distort/azafullerene')
        .itemInputs('1x gtceu:fullerene_dust')
        .circuit(3)
        .inputFluids('gtceu:nitrogen 12000', 'gtceu:hydrogen 8000')
        .outputFluids('gtceu:azafullerene 1000')
        .duration(200)
        .EUt(491520)
        .blastFurnaceTemp(800)
        PARA_OK = PARA_OK + 1
    } catch (errPara18) {
        PARA_BAD = PARA_BAD + 1
        if (PARA_ERRS.length < 2000) { PARA_ERRS = PARA_ERRS + '|shanhai:paradigm/portable_distort/azafullerene <= ' + errPara18 + ' ' }
    }

    // ── [19/97] 源 gtceu:distort/portable_distort/bedrock_dust_loop  (EUt=31457280 dur=96000)
    //    新 id = shanhai:primordial_paradigm_simplification/paradigm/portable_distort/bedrock_dust_loop   电路 8   输入种类 物品3+流体5
    try {
        var b19 = gtr.primordial_paradigm_simplification('shanhai:paradigm/portable_distort/bedrock_dust_loop')
        .itemInputs('64x kubejs:reinforced_echo_shard', '64x gtceu:neutronium_buzz_saw_blade', '256x gtceu:double_neutronium_plate')
        .circuit(8)
        .inputFluids('gtceu:rhodium 36864', 'gtceu:hastelloy_x 36864', 'gtceu:hsss 36864', 'gtceu:hsse 36864', 'gtceu:mana 1024000')
        .itemOutputs('32512x gtceu:bedrock_dust')
        .duration(96000)
        .EUt(31457280)
        PARA_OK = PARA_OK + 1
    } catch (errPara19) {
        PARA_BAD = PARA_BAD + 1
        if (PARA_ERRS.length < 2000) { PARA_ERRS = PARA_ERRS + '|shanhai:paradigm/portable_distort/bedrock_dust_loop <= ' + errPara19 + ' ' }
    }

    // ── [20/97] 源 gtceu:distort/portable_distort/bedrock_gas  (EUt=1966080 dur=2560 ebf=800)
    //    新 id = shanhai:primordial_paradigm_simplification/paradigm/portable_distort/bedrock_gas   电路 4   输入种类 物品2+流体2
    try {
        var b20 = gtr.primordial_paradigm_simplification('shanhai:paradigm/portable_distort/bedrock_gas')
        .itemInputs('72x gtceu:bedrock_dust', '63x gtceu:naquadah_dust')
        .circuit(4)
        .inputFluids('gtceu:distilled_water 72000', 'gtceu:xenon 7200')
        .itemOutputs('4x gtceu:naquadria_dust', '4x gtceu:enriched_naquadah_dust')
        .outputFluids('gtceu:bedrock_gas 36000')
        .duration(2560)
        .EUt(1966080)
        .blastFurnaceTemp(800)
        .cleanroom(GTLCleanroomType.LAW_CLEANROOM)
        PARA_OK = PARA_OK + 1
    } catch (errPara20) {
        PARA_BAD = PARA_BAD + 1
        if (PARA_ERRS.length < 2000) { PARA_ERRS = PARA_ERRS + '|shanhai:paradigm/portable_distort/bedrock_gas <= ' + errPara20 + ' ' }
    }

    // ── [21/97] 源 gtceu:distort/portable_distort/biological_cells  (EUt=491520 dur=400 ebf=800)
    //    新 id = shanhai:primordial_paradigm_simplification/paradigm/portable_distort/biological_cells   电路 9   输入种类 物品7+流体2
    try {
        var b21 = gtr.primordial_paradigm_simplification('shanhai:paradigm/portable_distort/biological_cells')
        .itemInputs('256x gtceu:stem_cells', '64x gtceu:meat_dust', '64x gtceu:salt_dust', '64x gtceu:calcium_dust', '64x gtceu:agar_dust', '4x kubejs:tcetieseaweedextract', '2x gtceu:enriched_naquadah_dust')
        .circuit(9)
        .inputFluids('gtceu:mutagen 10000', 'gtceu:tritanium 144')
        .itemOutputs('64x kubejs:biological_cells')
        .duration(400)
        .EUt(491520)
        .blastFurnaceTemp(800)
        .cleanroom(CleanroomType.STERILE_CLEANROOM)
        PARA_OK = PARA_OK + 1
    } catch (errPara21) {
        PARA_BAD = PARA_BAD + 1
        if (PARA_ERRS.length < 2000) { PARA_ERRS = PARA_ERRS + '|shanhai:paradigm/portable_distort/biological_cells <= ' + errPara21 + ' ' }
    }

    // ── [22/97] 源 gtceu:distort/portable_distort/biology_process  (EUt=503316480 dur=1 ebf=800)
    //    新 id = shanhai:primordial_paradigm_simplification/paradigm/portable_distort/biology_process   电路 3   输入种类 物品3+流体0
    try {
        var b22 = gtr.primordial_paradigm_simplification('shanhai:paradigm/portable_distort/biology_process')
        .itemInputs('256x gtceu:stem_cells', '256x gtceu:agar_dust', '256x kubejs:tcetieseaweedextract')
        .circuit(3)
        .itemOutputs('8192x kubejs:biological_cells')
        .outputFluids('gtceu:mutated_living_solder 73728', 'gtceu:biohmediumsterilized 1024000', 'gtceu:sterilized_growth_medium 1024000')
        .duration(1)
        .EUt(503316480)
        .blastFurnaceTemp(800)
        .cleanroom(CleanroomType.STERILE_CLEANROOM)
        PARA_OK = PARA_OK + 1
    } catch (errPara22) {
        PARA_BAD = PARA_BAD + 1
        if (PARA_ERRS.length < 2000) { PARA_ERRS = PARA_ERRS + '|shanhai:paradigm/portable_distort/biology_process <= ' + errPara22 + ' ' }
    }

    // ── [23/97] 源 gtceu:distort/portable_distort/composite_1  (EUt=125829120 dur=1 ebf=800)
    //    新 id = shanhai:primordial_paradigm_simplification/paradigm/portable_distort/composite_1   电路 7   输入种类 物品3+流体4
    try {
        var b23 = gtr.primordial_paradigm_simplification('shanhai:paradigm/portable_distort/composite_1')
        .itemInputs('1440x gtceu:carbon_dust', '36x gtceu:sulfur_dust', '36x gtceu:silicon_dust')
        .circuit(7)
        .inputFluids('gtceu:hydrogen 1908000', 'gtceu:oxygen 144000', 'gtceu:fluorine 144000', 'gtceu:chlorine 36000')
        .outputFluids('gtceu:polyvinyl_chloride 36000', 'gtceu:polytetrafluoroethylene 36000', 'gtceu:silicone_rubber 36000', 'gtceu:polyphenylene_sulfide 36000', 'gtceu:styrene_butadiene_rubber 36000', 'gtceu:polyvinyl_butyral 36000')
        .duration(1)
        .EUt(125829120)
        .blastFurnaceTemp(800)
        .cleanroom(GTLCleanroomType.LAW_CLEANROOM)
        PARA_OK = PARA_OK + 1
    } catch (errPara23) {
        PARA_BAD = PARA_BAD + 1
        if (PARA_ERRS.length < 2000) { PARA_ERRS = PARA_ERRS + '|shanhai:paradigm/portable_distort/composite_1 <= ' + errPara23 + ' ' }
    }

    // ── [24/97] 源 gtceu:distort/portable_distort/composite_2  (EUt=503316480 dur=1 ebf=800)
    //    新 id = shanhai:primordial_paradigm_simplification/paradigm/portable_distort/composite_2   电路 5   输入种类 物品1+流体4
    try {
        var b24 = gtr.primordial_paradigm_simplification('shanhai:paradigm/portable_distort/composite_2')
        .itemInputs('1440x gtceu:carbon_dust')
        .circuit(5)
        .inputFluids('gtceu:hydrogen 440000', 'gtceu:oxygen 88000', 'gtceu:nitrogen 40000', 'gtceu:chlorine 8000')
        .outputFluids('gtceu:epoxy 8000', 'gtceu:polyetheretherketone 8000', 'gtceu:polybenzimidazole 8000', 'gtceu:polyimide 8000')
        .duration(1)
        .EUt(503316480)
        .blastFurnaceTemp(800)
        .cleanroom(GTLCleanroomType.LAW_CLEANROOM)
        PARA_OK = PARA_OK + 1
    } catch (errPara24) {
        PARA_BAD = PARA_BAD + 1
        if (PARA_ERRS.length < 2000) { PARA_ERRS = PARA_ERRS + '|shanhai:paradigm/portable_distort/composite_2 <= ' + errPara24 + ' ' }
    }

    // ── [25/97] 源 gtceu:distort/portable_distort/composite_3  (EUt=2013265920 dur=1 ebf=800)
    //    新 id = shanhai:primordial_paradigm_simplification/paradigm/portable_distort/composite_3   电路 15   输入种类 物品8+流体7
    try {
        var b25 = gtr.primordial_paradigm_simplification('shanhai:paradigm/portable_distort/composite_3')
        .itemInputs('5828x gtceu:carbon_dust', '64x gtceu:iodine_dust', '45x gtceu:tin_dust', '36x gtceu:silicon_dust', '5x gtceu:palladium_dust', '5x gtceu:nickel_dust', '5x gtceu:iron_dust', '2x gtceu:calcium_dust')
        .circuit(15)
        .inputFluids('gtceu:oxygen 2078000', 'gtceu:hydrogen 1269000', 'gtceu:chlorine 982000', 'gtceu:nitrogen 123000', 'gtceu:fluorine 96000', 'gtceu:methane 60000', 'gtceu:bromine 60000')
        .itemOutputs('64x gtceu:unfolded_fullerene_dust')
        .outputFluids('gtceu:cycloparaphenylene 32000', 'gtceu:polyurethaneresin 45000', 'gtceu:liquidcrystalkevlar 45000', 'gtceu:hydrobromic_acid 60000', 'gtceu:fluorine 4800', 'gtceu:chlorine 3200')
        .duration(1)
        .EUt(2013265920)
        .blastFurnaceTemp(800)
        .cleanroom(GTLCleanroomType.LAW_CLEANROOM)
        PARA_OK = PARA_OK + 1
    } catch (errPara25) {
        PARA_BAD = PARA_BAD + 1
        if (PARA_ERRS.length < 2000) { PARA_ERRS = PARA_ERRS + '|shanhai:paradigm/portable_distort/composite_3 <= ' + errPara25 + ' ' }
    }

    // ── [26/97] 源 gtceu:distort/portable_distort/cosmic_superconductor  (EUt=125829120 dur=16000 ebf=800)
    //    新 id = shanhai:primordial_paradigm_simplification/paradigm/portable_distort/cosmic_superconductor   电路 16   输入种类 物品7+流体9
    try {
        var b26 = gtr.primordial_paradigm_simplification('shanhai:paradigm/portable_distort/cosmic_superconductor')
        .itemInputs('18x gtceu:sulfur_dust', '6x gtceu:sodium_dust', '1x gtceu:thallium_dust', '6x gtceu:rhenium_chloride_dust', '5x gtceu:hassium_chloride_dust', '36x gtceu:atinium_hydride_dust', '14x gtceu:charged_caesium_cerium_cobalt_indium_dust')
        .circuit(16)
        .inputFluids('gtceu:chlorine 34000', 'gtceu:fluorine 3000', 'gtceu:ethylene 12000', 'gtceu:oxygen 46000', 'gtceu:hydrogen 102000', 'gtceu:uu_matter 2000', 'gtceu:quark_gluon_plasma 17000', 'gtceu:benzene 3000', 'gtceu:acetone 6000')
        .outputFluids('gtceu:cosmic_superconductor 10000')
        .duration(16000)
        .EUt(125829120)
        .blastFurnaceTemp(800)
        .cleanroom(GTLCleanroomType.LAW_CLEANROOM)
        PARA_OK = PARA_OK + 1
    } catch (errPara26) {
        PARA_BAD = PARA_BAD + 1
        if (PARA_ERRS.length < 2000) { PARA_ERRS = PARA_ERRS + '|shanhai:paradigm/portable_distort/cosmic_superconductor <= ' + errPara26 + ' ' }
    }

    // ── [27/97] 源 gtceu:distort/portable_distort/cycloparaphenylene  (EUt=1966080 dur=200 ebf=800)
    //    新 id = shanhai:primordial_paradigm_simplification/paradigm/portable_distort/cycloparaphenylene   电路 6   输入种类 物品2+流体4
    try {
        var b27 = gtr.primordial_paradigm_simplification('shanhai:paradigm/portable_distort/cycloparaphenylene')
        .itemInputs('1920x gtceu:carbon_dust', '64x gtceu:iodine_dust')
        .circuit(6)
        .inputFluids('gtceu:hydrogen 640000', 'gtceu:oxygen 96000', 'gtceu:chlorine 96000', 'gtceu:fluorine 96000')
        .outputFluids('gtceu:cycloparaphenylene 32000', 'gtceu:fluorine 4800', 'gtceu:chlorine 3200')
        .duration(200)
        .EUt(1966080)
        .blastFurnaceTemp(800)
        .cleanroom(CleanroomType.CLEANROOM)
        PARA_OK = PARA_OK + 1
    } catch (errPara27) {
        PARA_BAD = PARA_BAD + 1
        if (PARA_ERRS.length < 2000) { PARA_ERRS = PARA_ERRS + '|shanhai:paradigm/portable_distort/cycloparaphenylene <= ' + errPara27 + ' ' }
    }

    // ── [28/97] 源 gtceu:distort/portable_distort/epoxy  (EUt=122880 dur=24 ebf=800)
    //    新 id = shanhai:primordial_paradigm_simplification/paradigm/portable_distort/epoxy   电路 6   输入种类 物品2+流体4
    try {
        var b28 = gtr.primordial_paradigm_simplification('shanhai:paradigm/portable_distort/epoxy')
        .itemInputs('1x gtceu:phosphorus_dust', '16x gtceu:salt_dust')
        .circuit(6)
        .inputFluids('minecraft:water 21500', 'gtceu:oxygen 10500', 'gtceu:propene 4000', 'gtceu:benzene 6000')
        .itemOutputs('86x gtceu:sodium_hydroxide_dust')
        .outputFluids('gtceu:epoxy 4000', 'gtceu:hydrogen 16000', 'gtceu:hydrochloric_acid 4000')
        .duration(24)
        .EUt(122880)
        .blastFurnaceTemp(800)
        PARA_OK = PARA_OK + 1
    } catch (errPara28) {
        PARA_BAD = PARA_BAD + 1
        if (PARA_ERRS.length < 2000) { PARA_ERRS = PARA_ERRS + '|shanhai:paradigm/portable_distort/epoxy <= ' + errPara28 + ' ' }
    }

    // ── [29/97] 源 gtceu:distort/portable_distort/euv_photoresist  (EUt=31457280 dur=5120 ebf=800)
    //    新 id = shanhai:primordial_paradigm_simplification/paradigm/portable_distort/euv_photoresist   电路 原号 13 ⇒ 偏移到 29（撞号）   输入种类 物品6+流体7
    try {
        var b29 = gtr.primordial_paradigm_simplification('shanhai:paradigm/portable_distort/euv_photoresist')
        .itemInputs('30x gtceu:rhenium_dust', '56x gtceu:lithium_dust', '40x gtceu:selenium_dust', '50x gtceu:activated_carbon_dust', '60x gtceu:rutile_dust', '55x gtceu:quicklime_dust')
        .circuit(29)
        .inputFluids('gtceu:ethane 12000', 'gtceu:chlorine 75000', 'gtceu:photoresist 8000', 'gtceu:hydrogen 4700', 'gtceu:oxygen 89000', 'gtceu:nitrogen 40000', 'gtceu:butane 57000')
        .outputFluids('gtceu:euv_photoresist 21600')
        .duration(5120)
        .EUt(31457280)
        .blastFurnaceTemp(800)
        .cleanroom(CleanroomType.CLEANROOM)
        PARA_OK = PARA_OK + 1
    } catch (errPara29) {
        PARA_BAD = PARA_BAD + 1
        if (PARA_ERRS.length < 2000) { PARA_ERRS = PARA_ERRS + '|shanhai:paradigm/portable_distort/euv_photoresist <= ' + errPara29 + ' ' }
    }

    // ── [30/97] 源 gtceu:distort/portable_distort/fullerene_dust  (EUt=503316480 dur=128 ebf=21600)
    //    新 id = shanhai:primordial_paradigm_simplification/paradigm/portable_distort/fullerene_dust   电路 1   输入种类 物品1+流体0
    try {
        var b30 = gtr.primordial_paradigm_simplification('shanhai:paradigm/portable_distort/fullerene_dust')
        .itemInputs('60x gtceu:carbon_dust')
        .circuit(1)
        .itemOutputs('60x gtceu:fullerene_dust')
        .duration(128)
        .EUt(503316480)
        .blastFurnaceTemp(21600)
        PARA_OK = PARA_OK + 1
    } catch (errPara30) {
        PARA_BAD = PARA_BAD + 1
        if (PARA_ERRS.length < 2000) { PARA_ERRS = PARA_ERRS + '|shanhai:paradigm/portable_distort/fullerene_dust <= ' + errPara30 + ' ' }
    }

    // ── [31/97] 源 gtceu:distort/portable_distort/fullerene_polymer_matrix_pulp  (EUt=31457280 dur=3600 ebf=800)
    //    新 id = shanhai:primordial_paradigm_simplification/paradigm/portable_distort/fullerene_polymer_matrix_pulp   电路 13   输入种类 物品4+流体9
    try {
        var b31 = gtr.primordial_paradigm_simplification('shanhai:paradigm/portable_distort/fullerene_polymer_matrix_pulp')
        .itemInputs('32x gtceu:carbon_dust', '16x gtceu:fullerene_dust', '8x gtceu:palladium_dust', '8x gtceu:iron_dust')
        .circuit(13)
        .inputFluids('gtceu:hydrogen 176000', 'gtceu:chlorine 88000', 'gtceu:oxygen 48000', 'gtceu:benzene 23000', 'gtceu:cyclopentadiene 16000', 'gtceu:ethylene 15000', 'gtceu:nitrogen 8000', 'gtceu:propene 8000', 'gtceu:acetic_acid 8000')
        .itemOutputs('16x gtceu:fullerene_polymer_matrix_pulp_dust')
        .duration(3600)
        .EUt(31457280)
        .blastFurnaceTemp(800)
        .cleanroom(CleanroomType.CLEANROOM)
        PARA_OK = PARA_OK + 1
    } catch (errPara31) {
        PARA_BAD = PARA_BAD + 1
        if (PARA_ERRS.length < 2000) { PARA_ERRS = PARA_ERRS + '|shanhai:paradigm/portable_distort/fullerene_polymer_matrix_pulp <= ' + errPara31 + ' ' }
    }

    // ── [32/97] 源 gtceu:distort/portable_distort/grade_13_purified_water  (EUt=31457280 dur=600 ebf=800)
    //    新 id = shanhai:primordial_paradigm_simplification/paradigm/portable_distort/grade_13_purified_water   电路 2   输入种类 物品0+流体2
    try {
        var b32 = gtr.primordial_paradigm_simplification('shanhai:paradigm/portable_distort/grade_13_purified_water')
        .circuit(2)
        .inputFluids('gtceu:grade_8_purified_water 1000', 'gtceu:liquid_oxygen 1000')
        .outputFluids('gtceu:grade_13_purified_water 728')
        .duration(600)
        .EUt(31457280)
        .blastFurnaceTemp(800)
        PARA_OK = PARA_OK + 1
    } catch (errPara32) {
        PARA_BAD = PARA_BAD + 1
        if (PARA_ERRS.length < 2000) { PARA_ERRS = PARA_ERRS + '|shanhai:paradigm/portable_distort/grade_13_purified_water <= ' + errPara32 + ' ' }
    }

    // ── [33/97] 源 gtceu:distort/portable_distort/grade_16_purified_water  (EUt=31457280 dur=600 ebf=800)
    //    新 id = shanhai:primordial_paradigm_simplification/paradigm/portable_distort/grade_16_purified_water   电路 4   输入种类 物品0+流体4
    try {
        var b33 = gtr.primordial_paradigm_simplification('shanhai:paradigm/portable_distort/grade_16_purified_water')
        .circuit(4)
        .inputFluids('gtceu:enderium_plasma 1000', 'gtceu:grade_13_purified_water 10000', 'gtceu:echoite_plasma 1000', 'gtceu:mithril_plasma 1000')
        .itemOutputs('61x gtceu:tiny_enderium_dust', '62x gtceu:tiny_echoite_dust', '60x gtceu:tiny_mithril_dust')
        .outputFluids('gtceu:grade_16_purified_water 10000')
        .duration(600)
        .EUt(31457280)
        .blastFurnaceTemp(800)
        PARA_OK = PARA_OK + 1
    } catch (errPara33) {
        PARA_BAD = PARA_BAD + 1
        if (PARA_ERRS.length < 2000) { PARA_ERRS = PARA_ERRS + '|shanhai:paradigm/portable_distort/grade_16_purified_water <= ' + errPara33 + ' ' }
    }

    // ── [34/97] 源 gtceu:distort/portable_distort/grade_5_purified_water  (EUt=491520 dur=100 ebf=800)
    //    新 id = shanhai:primordial_paradigm_simplification/paradigm/portable_distort/grade_5_purified_water   电路 2   输入种类 物品0+流体2
    try {
        var b34 = gtr.primordial_paradigm_simplification('shanhai:paradigm/portable_distort/grade_5_purified_water')
        .circuit(2)
        .inputFluids('minecraft:water 1000', 'gtceu:nitrogen 128000')
        .outputFluids('gtceu:grade_5_purified_water 685')
        .duration(100)
        .EUt(491520)
        .blastFurnaceTemp(800)
        PARA_OK = PARA_OK + 1
    } catch (errPara34) {
        PARA_BAD = PARA_BAD + 1
        if (PARA_ERRS.length < 2000) { PARA_ERRS = PARA_ERRS + '|shanhai:paradigm/portable_distort/grade_5_purified_water <= ' + errPara34 + ' ' }
    }

    // ── [35/97] 源 gtceu:distort/portable_distort/grade_8_purified_water  (EUt=491520 dur=60 ebf=800)
    //    新 id = shanhai:primordial_paradigm_simplification/paradigm/portable_distort/grade_8_purified_water   电路 1   输入种类 物品0+流体1
    try {
        var b35 = gtr.primordial_paradigm_simplification('shanhai:paradigm/portable_distort/grade_8_purified_water')
        .circuit(1)
        .inputFluids('gtceu:grade_5_purified_water 1000')
        .outputFluids('gtceu:grade_8_purified_water 810')
        .duration(60)
        .EUt(491520)
        .blastFurnaceTemp(800)
        PARA_OK = PARA_OK + 1
    } catch (errPara35) {
        PARA_BAD = PARA_BAD + 1
        if (PARA_ERRS.length < 2000) { PARA_ERRS = PARA_ERRS + '|shanhai:paradigm/portable_distort/grade_8_purified_water <= ' + errPara35 + ' ' }
    }

    // ── [36/97] 源 gtceu:distort/portable_distort/hexanitrohexaaxaisowurtzitane_dust  (EUt=1966080 dur=20480 ebf=800)
    //    新 id = shanhai:primordial_paradigm_simplification/paradigm/portable_distort/hexanitrohexaaxaisowurtzitane_dust   电路 原号 16 ⇒ 偏移到 31（撞号）   输入种类 物品7+流体9
    try {
        var b36 = gtr.primordial_paradigm_simplification('shanhai:paradigm/portable_distort/hexanitrohexaaxaisowurtzitane_dust')
        .itemInputs('191x gtceu:silica_gel_dust', '76x gtceu:succinic_acid_dust', '144x gtceu:activated_carbon_dust', '216x gtceu:sodium_dust', '47x gtceu:boron_trioxide_dust', '39x gtceu:potassium_carbonate_dust', '101x gtceu:barium_chloride_dust')
        .circuit(31)
        .inputFluids('gtceu:hydrogen 470000', 'gtceu:hydrofluoric_acid 12000', 'gtceu:methanol 62000', 'gtceu:nitric_acid 15000', 'gtceu:ammonia 39000', 'gtceu:glyoxal 47000', 'gtceu:oxygen_plasma 11000', 'gtceu:acetic_anhydride 9000', 'gtceu:nitrogen_plasma 7000')
        .itemOutputs('288x gtceu:hexanitrohexaaxaisowurtzitane_dust')
        .duration(20480)
        .EUt(1966080)
        .blastFurnaceTemp(800)
        .cleanroom(CleanroomType.CLEANROOM)
        PARA_OK = PARA_OK + 1
    } catch (errPara36) {
        PARA_BAD = PARA_BAD + 1
        if (PARA_ERRS.length < 2000) { PARA_ERRS = PARA_ERRS + '|shanhai:paradigm/portable_distort/hexanitrohexaaxaisowurtzitane_dust <= ' + errPara36 + ' ' }
    }

    // ── [37/97] 源 gtceu:distort/portable_distort/hexanitrohexaaxaisowurtzitane_dust_twisted  (EUt=2013265920 dur=256 ebf=800)
    //    新 id = shanhai:primordial_paradigm_simplification/paradigm/portable_distort/hexanitrohexaaxaisowurtzitane_dust_twisted   电路 4   输入种类 物品1+流体3
    try {
        var b37 = gtr.primordial_paradigm_simplification('shanhai:paradigm/portable_distort/hexanitrohexaaxaisowurtzitane_dust_twisted')
        .itemInputs('6x gtceu:carbon_dust')
        .circuit(4)
        .inputFluids('gtceu:hydrogen 6000', 'gtceu:nitrogen 12000', 'gtceu:oxygen 12000')
        .itemOutputs('36x gtceu:hexanitrohexaaxaisowurtzitane_dust')
        .duration(256)
        .EUt(2013265920)
        .blastFurnaceTemp(800)
        .cleanroom(GTLCleanroomType.LAW_CLEANROOM)
        PARA_OK = PARA_OK + 1
    } catch (errPara37) {
        PARA_BAD = PARA_BAD + 1
        if (PARA_ERRS.length < 2000) { PARA_ERRS = PARA_ERRS + '|shanhai:paradigm/portable_distort/hexanitrohexaaxaisowurtzitane_dust_twisted <= ' + errPara37 + ' ' }
    }

    // ── [38/97] 源 gtceu:distort/portable_distort/liquid_starlight  (EUt=7864320 dur=256 ebf=800)
    //    新 id = shanhai:primordial_paradigm_simplification/paradigm/portable_distort/liquid_starlight   电路 3   输入种类 物品0+流体3
    try {
        var b38 = gtr.primordial_paradigm_simplification('shanhai:paradigm/portable_distort/liquid_starlight')
        .circuit(3)
        .inputFluids('minecraft:water 100000', 'gtceu:starlight 10000', 'gtceu:mana 10000')
        .outputFluids('gtceu:liquid_starlight 10000')
        .duration(256)
        .EUt(7864320)
        .blastFurnaceTemp(800)
        .cleanroom(GTLCleanroomType.LAW_CLEANROOM)
        PARA_OK = PARA_OK + 1
    } catch (errPara38) {
        PARA_BAD = PARA_BAD + 1
        if (PARA_ERRS.length < 2000) { PARA_ERRS = PARA_ERRS + '|shanhai:paradigm/portable_distort/liquid_starlight <= ' + errPara38 + ' ' }
    }

    // ── [39/97] 源 gtceu:distort/portable_distort/liquidcrystalkevlar  (EUt=1966080 dur=400 ebf=800)
    //    新 id = shanhai:primordial_paradigm_simplification/paradigm/portable_distort/liquidcrystalkevlar   电路 6   输入种类 物品2+流体4
    try {
        var b39 = gtr.primordial_paradigm_simplification('shanhai:paradigm/portable_distort/liquidcrystalkevlar')
        .itemInputs('64x gtceu:carbon_dust', '2x gtceu:calcium_dust')
        .circuit(6)
        .inputFluids('gtceu:hydrogen 100000', 'gtceu:chlorine 16000', 'gtceu:oxygen 18000', 'gtceu:nitrogen 18000')
        .outputFluids('gtceu:liquidcrystalkevlar 45000')
        .duration(400)
        .EUt(1966080)
        .blastFurnaceTemp(800)
        .cleanroom(CleanroomType.CLEANROOM)
        PARA_OK = PARA_OK + 1
    } catch (errPara39) {
        PARA_BAD = PARA_BAD + 1
        if (PARA_ERRS.length < 2000) { PARA_ERRS = PARA_ERRS + '|shanhai:paradigm/portable_distort/liquidcrystalkevlar <= ' + errPara39 + ' ' }
    }

    // ── [40/97] 源 gtceu:distort/portable_distort/mutagen  (EUt=491520 dur=200 ebf=800)
    //    新 id = shanhai:primordial_paradigm_simplification/paradigm/portable_distort/mutagen   电路 3   输入种类 物品2+流体1
    try {
        var b40 = gtr.primordial_paradigm_simplification('shanhai:paradigm/portable_distort/mutagen')
        .itemInputs('256x gtceu:bio_chaff', '1x gtceu:naquadria_dust')
        .circuit(3)
        .inputFluids('gtceu:distilled_water 10000')
        .outputFluids('gtceu:mutagen 10000')
        .duration(200)
        .EUt(491520)
        .blastFurnaceTemp(800)
        .cleanroom(CleanroomType.STERILE_CLEANROOM)
        PARA_OK = PARA_OK + 1
    } catch (errPara40) {
        PARA_BAD = PARA_BAD + 1
        if (PARA_ERRS.length < 2000) { PARA_ERRS = PARA_ERRS + '|shanhai:paradigm/portable_distort/mutagen <= ' + errPara40 + ' ' }
    }

    // ── [41/97] 源 gtceu:distort/portable_distort/naquadah_contain_rare_earth_dust  (EUt=31457280 dur=80 ebf=800)
    //    新 id = shanhai:primordial_paradigm_simplification/paradigm/portable_distort/naquadah_contain_rare_earth_dust   电路 13   输入种类 物品8+流体5
    try {
        var b41 = gtr.primordial_paradigm_simplification('shanhai:paradigm/portable_distort/naquadah_contain_rare_earth_dust')
        .itemInputs('12x #forge:ores/enriched_naquadah', '12x gtceu:sulfur_dust', '9x gtceu:quicklime_dust', '9x gtceu:potash_dust', '6x gtceu:rare_earth_dust', '6x gtceu:carbon_dust', '1x gtceu:alunite_dust', '140x #minecraft:logs')
        .circuit(13)
        .inputFluids('gtceu:steam 140000', 'minecraft:water 15000', 'gtceu:naphtha 14000', 'gtceu:sulfuric_acid 4000', 'gtceu:hydrogen 2000')
        .itemOutputs('1x gtceu:naquadah_contain_rare_earth_dust')
        .duration(80)
        .EUt(31457280)
        .blastFurnaceTemp(800)
        PARA_OK = PARA_OK + 1
    } catch (errPara41) {
        PARA_BAD = PARA_BAD + 1
        if (PARA_ERRS.length < 2000) { PARA_ERRS = PARA_ERRS + '|shanhai:paradigm/portable_distort/naquadah_contain_rare_earth_dust <= ' + errPara41 + ' ' }
    }

    // ── [42/97] 源 gtceu:distort/portable_distort/naquadah_processing  (EUt=3638400 dur=1 ebf=14400)
    //    新 id = shanhai:primordial_paradigm_simplification/paradigm/portable_distort/naquadah_processing   电路 6   输入种类 物品3+流体3
    try {
        var b42 = gtr.primordial_paradigm_simplification('shanhai:paradigm/portable_distort/naquadah_processing')
        .itemInputs('320x gtceu:naquadah_ore', '320x gtceu:enriched_naquadah_ore', '128x gtceu:gold_dust')
        .circuit(6)
        .inputFluids('gtceu:sulfuric_acid 64000', 'gtceu:nitric_acid 128000', 'gtceu:hydrochloric_acid 96000')
        .itemOutputs('16x gtceu:adamantine_dust', '64x gtceu:naquadah_dust', '32x gtceu:enriched_naquadah_dust', '8x gtceu:naquadria_dust')
        .duration(1)
        .EUt(3638400)
        .blastFurnaceTemp(14400)
        PARA_OK = PARA_OK + 1
    } catch (errPara42) {
        PARA_BAD = PARA_BAD + 1
        if (PARA_ERRS.length < 2000) { PARA_ERRS = PARA_ERRS + '|shanhai:paradigm/portable_distort/naquadah_processing <= ' + errPara42 + ' ' }
    }

    // ── [43/97] 源 gtceu:distort/portable_distort/naquadria_dust  (EUt=1966080 dur=360 ebf=800)
    //    新 id = shanhai:primordial_paradigm_simplification/paradigm/portable_distort/naquadria_dust   电路 8   输入种类 物品2+流体6
    try {
        var b43 = gtr.primordial_paradigm_simplification('shanhai:paradigm/portable_distort/naquadria_dust')
        .itemInputs('128x gtceu:naquadah_dust', '16x gtceu:caesium_dust')
        .circuit(8)
        .inputFluids('gtceu:fluorine 32000', 'gtceu:fluoroantimonic_acid 64000', 'gtceu:sulfuric_acid 12000', 'gtceu:radon 8000', 'gtceu:nitrogen_dioxide 4000', 'gtceu:xenon 4000')
        .itemOutputs('64x gtceu:naquadria_dust', '64x gtceu:trinium_dust', '256x gtceu:antimony_trifluoride_dust')
        .outputFluids('gtceu:hydrofluoric_acid 272000', 'gtceu:radon_trioxide 8000', 'gtceu:xenon_trioxide 4000', 'gtceu:caesium_fluoride 16000')
        .duration(360)
        .EUt(1966080)
        .blastFurnaceTemp(800)
        .cleanroom(CleanroomType.CLEANROOM)
        PARA_OK = PARA_OK + 1
    } catch (errPara43) {
        PARA_BAD = PARA_BAD + 1
        if (PARA_ERRS.length < 2000) { PARA_ERRS = PARA_ERRS + '|shanhai:paradigm/portable_distort/naquadria_dust <= ' + errPara43 + ' ' }
    }

    // ── [44/97] 源 gtceu:distort/portable_distort/photopolymer  (EUt=125829120 dur=10240 ebf=800)
    //    新 id = shanhai:primordial_paradigm_simplification/paradigm/portable_distort/photopolymer   电路 14   输入种类 物品7+流体7
    try {
        var b44 = gtr.primordial_paradigm_simplification('shanhai:paradigm/portable_distort/photopolymer')
        .itemInputs('768x gtceu:carbon_dust', '50x gtceu:rutile_dust', '70x gtceu:succinic_acid_dust', '32x gtceu:ice_dust', '20x gtceu:silver_dust', '25x gtceu:quicklime_dust', '40x gtceu:boron_dust')
        .circuit(14)
        .inputFluids('gtceu:benzene 40000', 'gtceu:bromine 25000', 'gtceu:oxygen 21600', 'gtceu:chlorine 5600', 'gtceu:propene 16000', 'gtceu:butene 80000', 'gtceu:sodium 17280')
        .outputFluids('gtceu:photopolymer 16000')
        .duration(10240)
        .EUt(125829120)
        .blastFurnaceTemp(800)
        .cleanroom(CleanroomType.CLEANROOM)
        PARA_OK = PARA_OK + 1
    } catch (errPara44) {
        PARA_BAD = PARA_BAD + 1
        if (PARA_ERRS.length < 2000) { PARA_ERRS = PARA_ERRS + '|shanhai:paradigm/portable_distort/photopolymer <= ' + errPara44 + ' ' }
    }

    // ── [45/97] 源 gtceu:distort/portable_distort/photoresist  (EUt=1966080 dur=1920 ebf=800)
    //    新 id = shanhai:primordial_paradigm_simplification/paradigm/portable_distort/photoresist   电路 原号 9 ⇒ 偏移到 25（撞号）   输入种类 物品3+流体6
    try {
        var b45 = gtr.primordial_paradigm_simplification('shanhai:paradigm/portable_distort/photoresist')
        .itemInputs('91x gtceu:rutile_dust', '60x gtceu:carbon_dust', '42x gtceu:sodium_hydroxide_dust')
        .circuit(25)
        .inputFluids('gtceu:sulfuric_acid 80000', 'gtceu:ethanol 7000', 'gtceu:chlorine 81000', 'gtceu:propene 15000', 'gtceu:benzene 39000', 'gtceu:ethylene 47000')
        .outputFluids('gtceu:photoresist 16000')
        .duration(1920)
        .EUt(1966080)
        .blastFurnaceTemp(800)
        .cleanroom(CleanroomType.CLEANROOM)
        PARA_OK = PARA_OK + 1
    } catch (errPara45) {
        PARA_BAD = PARA_BAD + 1
        if (PARA_ERRS.length < 2000) { PARA_ERRS = PARA_ERRS + '|shanhai:paradigm/portable_distort/photoresist <= ' + errPara45 + ' ' }
    }

    // ── [46/97] 源 gtceu:distort/portable_distort/platinum_dust  (EUt=491520 dur=20 ebf=800)
    //    新 id = shanhai:primordial_paradigm_simplification/paradigm/portable_distort/platinum_dust   电路 原号 5 ⇒ 偏移到 20（撞号）   输入种类 物品2+流体3
    try {
        var b46 = gtr.primordial_paradigm_simplification('shanhai:paradigm/portable_distort/platinum_dust')
        .itemInputs('576x gtceu:platinum_group_sludge_dust', '16x gtceu:sulfur_dust')
        .circuit(20)
        .inputFluids('gtceu:hydrogen 72000', 'gtceu:oxygen 128000', 'gtceu:chlorine 14400')
        .itemOutputs('64x gtceu:platinum_dust', '64x gtceu:palladium_dust', '60x gtceu:ruthenium_dust', '32x gtceu:iridium_dust', '48x gtceu:rhodium_dust', '32x gtceu:osmium_dust')
        .outputFluids('gtceu:hydrogen 2800', 'minecraft:water 7200', 'gtceu:chlorine 6400')
        .duration(20)
        .EUt(491520)
        .blastFurnaceTemp(800)
        PARA_OK = PARA_OK + 1
    } catch (errPara46) {
        PARA_BAD = PARA_BAD + 1
        if (PARA_ERRS.length < 2000) { PARA_ERRS = PARA_ERRS + '|shanhai:paradigm/portable_distort/platinum_dust <= ' + errPara46 + ' ' }
    }

    // ── [47/97] 源 gtceu:distort/portable_distort/polybenzimidazole  (EUt=1966080 dur=200 ebf=800)
    //    新 id = shanhai:primordial_paradigm_simplification/paradigm/portable_distort/polybenzimidazole   电路 8   输入种类 物品3+流体5
    try {
        var b47 = gtr.primordial_paradigm_simplification('shanhai:paradigm/portable_distort/polybenzimidazole')
        .itemInputs('1152x gtceu:carbon_dust', '16x gtceu:copper_dust', '144x gtceu:zinc_dust')
        .circuit(8)
        .inputFluids('gtceu:chlorobenzene 28800', 'gtceu:sulfuric_acid 14400', 'gtceu:hydrogen 316800', 'gtceu:nitrogen 57600', 'gtceu:oxygen 201600')
        .outputFluids('gtceu:polybenzimidazole 21600')
        .duration(200)
        .EUt(1966080)
        .blastFurnaceTemp(800)
        .cleanroom(CleanroomType.CLEANROOM)
        PARA_OK = PARA_OK + 1
    } catch (errPara47) {
        PARA_BAD = PARA_BAD + 1
        if (PARA_ERRS.length < 2000) { PARA_ERRS = PARA_ERRS + '|shanhai:paradigm/portable_distort/polybenzimidazole <= ' + errPara47 + ' ' }
    }

    // ── [48/97] 源 gtceu:distort/portable_distort/polycaprolactam  (EUt=7680 dur=256 ebf=800)
    //    新 id = shanhai:primordial_paradigm_simplification/paradigm/portable_distort/polycaprolactam   电路 4   输入种类 物品1+流体3
    try {
        var b48 = gtr.primordial_paradigm_simplification('shanhai:paradigm/portable_distort/polycaprolactam')
        .itemInputs('6x gtceu:carbon_dust')
        .circuit(4)
        .inputFluids('gtceu:hydrogen 11000', 'gtceu:oxygen 1000', 'gtceu:nitrogen 1000')
        .outputFluids('gtceu:polycaprolactam 2736')
        .duration(256)
        .EUt(7680)
        .blastFurnaceTemp(800)
        .cleanroom(CleanroomType.CLEANROOM)
        PARA_OK = PARA_OK + 1
    } catch (errPara48) {
        PARA_BAD = PARA_BAD + 1
        if (PARA_ERRS.length < 2000) { PARA_ERRS = PARA_ERRS + '|shanhai:paradigm/portable_distort/polycaprolactam <= ' + errPara48 + ' ' }
    }

    // ── [49/97] 源 gtceu:distort/portable_distort/polycyclic_aromatic_mixture  (EUt=491520 dur=200 ebf=800)
    //    新 id = shanhai:primordial_paradigm_simplification/paradigm/portable_distort/polycyclic_aromatic_mixture   电路 7   输入种类 物品1+流体6
    try {
        var b49 = gtr.primordial_paradigm_simplification('shanhai:paradigm/portable_distort/polycyclic_aromatic_mixture')
        .itemInputs('12x gtceu:carbon_dust')
        .circuit(7)
        .inputFluids('gtceu:naphthalene 32000', 'gtceu:hydrogen 12000', 'gtceu:chlorine 6000', 'gtceu:methanol 4000', 'minecraft:water 1000', 'gtceu:propene 1000')
        .itemOutputs('90x gtceu:polycyclic_aromatic_mixture_dust')
        .duration(200)
        .EUt(491520)
        .blastFurnaceTemp(800)
        PARA_OK = PARA_OK + 1
    } catch (errPara49) {
        PARA_BAD = PARA_BAD + 1
        if (PARA_ERRS.length < 2000) { PARA_ERRS = PARA_ERRS + '|shanhai:paradigm/portable_distort/polycyclic_aromatic_mixture <= ' + errPara49 + ' ' }
    }

    // ── [50/97] 源 gtceu:distort/portable_distort/polyetheretherketone  (EUt=1966080 dur=6560 ebf=800)
    //    新 id = shanhai:primordial_paradigm_simplification/paradigm/portable_distort/polyetheretherketone   电路 原号 6 ⇒ 偏移到 21（撞号）   输入种类 物品1+流体5
    try {
        var b50 = gtr.primordial_paradigm_simplification('shanhai:paradigm/portable_distort/polyetheretherketone')
        .itemInputs('16x gtceu:sodium_dust')
        .circuit(21)
        .inputFluids('gtceu:chlorine 48000', 'gtceu:benzene 16000', 'gtceu:oxygen 60000', 'gtceu:propene 8000', 'gtceu:nitric_acid 8000')
        .itemOutputs('32x gtceu:sodium_fluoride_dust')
        .outputFluids('gtceu:polyetheretherketone 20736', 'minecraft:water 8000', 'gtceu:carbon_dioxide 8000')
        .duration(6560)
        .EUt(1966080)
        .blastFurnaceTemp(800)
        .cleanroom(CleanroomType.CLEANROOM)
        PARA_OK = PARA_OK + 1
    } catch (errPara50) {
        PARA_BAD = PARA_BAD + 1
        if (PARA_ERRS.length < 2000) { PARA_ERRS = PARA_ERRS + '|shanhai:paradigm/portable_distort/polyetheretherketone <= ' + errPara50 + ' ' }
    }

    // ── [51/97] 源 gtceu:distort/portable_distort/polyimide  (EUt=1966080 dur=400 ebf=800)
    //    新 id = shanhai:primordial_paradigm_simplification/paradigm/portable_distort/polyimide   电路 原号 3 ⇒ 偏移到 18（撞号）   输入种类 物品1+流体2
    try {
        var b51 = gtr.primordial_paradigm_simplification('shanhai:paradigm/portable_distort/polyimide')
        .itemInputs('486x gtceu:carbon_dust')
        .circuit(18)
        .inputFluids('gtceu:hydrogen 256000', 'gtceu:nitrogen_dioxide 64000')
        .outputFluids('gtceu:polyimide 21000', 'gtceu:hydrogen 12800', 'gtceu:nitrogen 6400')
        .duration(400)
        .EUt(1966080)
        .blastFurnaceTemp(800)
        .cleanroom(CleanroomType.CLEANROOM)
        PARA_OK = PARA_OK + 1
    } catch (errPara51) {
        PARA_BAD = PARA_BAD + 1
        if (PARA_ERRS.length < 2000) { PARA_ERRS = PARA_ERRS + '|shanhai:paradigm/portable_distort/polyimide <= ' + errPara51 + ' ' }
    }

    // ── [52/97] 源 gtceu:distort/portable_distort/polyphenylene_sulfide  (EUt=491520 dur=12 ebf=800)
    //    新 id = shanhai:primordial_paradigm_simplification/paradigm/portable_distort/polyphenylene_sulfide   电路 2   输入种类 物品1+流体1
    try {
        var b52 = gtr.primordial_paradigm_simplification('shanhai:paradigm/portable_distort/polyphenylene_sulfide')
        .itemInputs('16x gtceu:sulfur_dust')
        .circuit(2)
        .inputFluids('gtceu:benzene 16000')
        .outputFluids('gtceu:polyphenylene_sulfide 24000', 'gtceu:hydrogen 32000')
        .duration(12)
        .EUt(491520)
        .blastFurnaceTemp(800)
        .cleanroom(CleanroomType.CLEANROOM)
        PARA_OK = PARA_OK + 1
    } catch (errPara52) {
        PARA_BAD = PARA_BAD + 1
        if (PARA_ERRS.length < 2000) { PARA_ERRS = PARA_ERRS + '|shanhai:paradigm/portable_distort/polyphenylene_sulfide <= ' + errPara52 + ' ' }
    }

    // ── [53/97] 源 gtceu:distort/portable_distort/polytetrafluoroethylene  (EUt=122880 dur=24 ebf=800)
    //    新 id = shanhai:primordial_paradigm_simplification/paradigm/portable_distort/polytetrafluoroethylene   电路 3   输入种类 物品0+流体3
    try {
        var b53 = gtr.primordial_paradigm_simplification('shanhai:paradigm/portable_distort/polytetrafluoroethylene')
        .circuit(3)
        .inputFluids('gtceu:oxygen 5000', 'gtceu:methane 1440', 'gtceu:fluorine 2880')
        .outputFluids('gtceu:polytetrafluoroethylene 1080', 'gtceu:hydrogen 5760')
        .duration(24)
        .EUt(122880)
        .blastFurnaceTemp(800)
        PARA_OK = PARA_OK + 1
    } catch (errPara53) {
        PARA_BAD = PARA_BAD + 1
        if (PARA_ERRS.length < 2000) { PARA_ERRS = PARA_ERRS + '|shanhai:paradigm/portable_distort/polytetrafluoroethylene <= ' + errPara53 + ' ' }
    }

    // ── [54/97] 源 gtceu:distort/portable_distort/polyurethaneresin  (EUt=1966080 dur=270 ebf=800)
    //    新 id = shanhai:primordial_paradigm_simplification/paradigm/portable_distort/polyurethaneresin   电路 10   输入种类 物品6+流体4
    try {
        var b54 = gtr.primordial_paradigm_simplification('shanhai:paradigm/portable_distort/polyurethaneresin')
        .itemInputs('45x gtceu:tin_dust', '64x gtceu:carbon_dust', '5x gtceu:nickel_dust', '5x gtceu:palladium_dust', '5x gtceu:iron_dust', '36x gtceu:silicon_dust')
        .circuit(10)
        .inputFluids('gtceu:oxygen 1964000', 'gtceu:hydrogen 529000', 'gtceu:chlorine 870000', 'gtceu:nitrogen 45000')
        .outputFluids('gtceu:polyurethaneresin 45000')
        .duration(270)
        .EUt(1966080)
        .blastFurnaceTemp(800)
        .cleanroom(CleanroomType.CLEANROOM)
        PARA_OK = PARA_OK + 1
    } catch (errPara54) {
        PARA_BAD = PARA_BAD + 1
        if (PARA_ERRS.length < 2000) { PARA_ERRS = PARA_ERRS + '|shanhai:paradigm/portable_distort/polyurethaneresin <= ' + errPara54 + ' ' }
    }

    // ── [55/97] 源 gtceu:distort/portable_distort/polyvinyl_butyral  (EUt=491520 dur=240 ebf=800)
    //    新 id = shanhai:primordial_paradigm_simplification/paradigm/portable_distort/polyvinyl_butyral   电路 原号 7 ⇒ 偏移到 23（撞号）   输入种类 物品2+流体5
    try {
        var b55 = gtr.primordial_paradigm_simplification('shanhai:paradigm/portable_distort/polyvinyl_butyral')
        .itemInputs('96x gtceu:carbon_dust', '4x gtceu:rutile_dust')
        .circuit(23)
        .inputFluids('gtceu:hydrogen 52000', 'gtceu:oxygen 32000', 'gtceu:chlorine 4000', 'gtceu:propene 16000', 'gtceu:ethylene 10000')
        .outputFluids('gtceu:polyvinyl_butyral 36864')
        .duration(240)
        .EUt(491520)
        .blastFurnaceTemp(800)
        PARA_OK = PARA_OK + 1
    } catch (errPara55) {
        PARA_BAD = PARA_BAD + 1
        if (PARA_ERRS.length < 2000) { PARA_ERRS = PARA_ERRS + '|shanhai:paradigm/portable_distort/polyvinyl_butyral <= ' + errPara55 + ' ' }
    }

    // ── [56/97] 源 gtceu:distort/portable_distort/polyvinyl_chloride  (EUt=7680 dur=16 ebf=800)
    //    新 id = shanhai:primordial_paradigm_simplification/paradigm/portable_distort/polyvinyl_chloride   电路 3   输入种类 物品0+流体3
    try {
        var b56 = gtr.primordial_paradigm_simplification('shanhai:paradigm/portable_distort/polyvinyl_chloride')
        .circuit(3)
        .inputFluids('gtceu:oxygen 10000', 'gtceu:chlorine 1440', 'gtceu:ethylene 1440')
        .outputFluids('gtceu:polyvinyl_chloride 2160', 'gtceu:hydrogen 1440')
        .duration(16)
        .EUt(7680)
        .blastFurnaceTemp(800)
        PARA_OK = PARA_OK + 1
    } catch (errPara56) {
        PARA_BAD = PARA_BAD + 1
        if (PARA_ERRS.length < 2000) { PARA_ERRS = PARA_ERRS + '|shanhai:paradigm/portable_distort/polyvinyl_chloride <= ' + errPara56 + ' ' }
    }

    // ── [57/97] 源 gtceu:distort/portable_distort/rare_earth_dust_monazite  (EUt=1966080 dur=1 ebf=800)
    //    新 id = shanhai:primordial_paradigm_simplification/paradigm/portable_distort/rare_earth_dust_monazite   电路 6   输入种类 物品3+流体3
    try {
        var b57 = gtr.primordial_paradigm_simplification('shanhai:paradigm/portable_distort/rare_earth_dust_monazite')
        .itemInputs('64x gtceu:monazite_dust', '288x gtceu:cerium_rich_mixture_powder_dust', '360x gtceu:samarium_refined_powder_dust')
        .circuit(6)
        .inputFluids('gtceu:nitric_acid 120000', 'gtceu:chlorine 160000', 'gtceu:acetone 160000')
        .itemOutputs('1440x gtceu:rare_earth_dust', '512x gtceu:uranium_235_dust', '480x gtceu:samarium_dust', '384x gtceu:phosphorus_dust')
        .outputFluids('gtceu:hydrogen 96000', 'gtceu:diluted_hydrochloric_acid 96000', 'gtceu:nitrogen 90000')
        .duration(1)
        .EUt(1966080)
        .blastFurnaceTemp(800)
        .cleanroom(CleanroomType.CLEANROOM)
        PARA_OK = PARA_OK + 1
    } catch (errPara57) {
        PARA_BAD = PARA_BAD + 1
        if (PARA_ERRS.length < 2000) { PARA_ERRS = PARA_ERRS + '|shanhai:paradigm/portable_distort/rare_earth_dust_monazite <= ' + errPara57 + ' ' }
    }

    // ── [58/97] 源 gtceu:distort/portable_distort/rhenium_dust  (EUt=1966080 dur=1 ebf=800)
    //    新 id = shanhai:primordial_paradigm_simplification/paradigm/portable_distort/rhenium_dust   电路 5   输入种类 物品2+流体3
    try {
        var b58 = gtr.primordial_paradigm_simplification('shanhai:paradigm/portable_distort/rhenium_dust')
        .itemInputs('48x gtceu:molybdenite_dust', '3x gtceu:iron_dust')
        .circuit(5)
        .inputFluids('gtceu:hydrogen 112000', 'gtceu:oxygen 68000', 'gtceu:chlorine 12000')
        .itemOutputs('48x gtceu:rhenium_dust', '16x gtceu:gold_dust', '16x gtceu:molybdenum_dust')
        .outputFluids('gtceu:hydrogen_sulfide 48000')
        .duration(1)
        .EUt(1966080)
        .blastFurnaceTemp(800)
        .cleanroom(CleanroomType.CLEANROOM)
        PARA_OK = PARA_OK + 1
    } catch (errPara58) {
        PARA_BAD = PARA_BAD + 1
        if (PARA_ERRS.length < 2000) { PARA_ERRS = PARA_ERRS + '|shanhai:paradigm/portable_distort/rhenium_dust <= ' + errPara58 + ' ' }
    }

    // ── [59/97] 源 gtceu:distort/portable_distort/silicone_rubber  (EUt=30720 dur=32 ebf=800)
    //    新 id = shanhai:primordial_paradigm_simplification/paradigm/portable_distort/silicone_rubber   电路 4   输入种类 物品2+流体2
    try {
        var b59 = gtr.primordial_paradigm_simplification('shanhai:paradigm/portable_distort/silicone_rubber')
        .itemInputs('3x gtceu:silicon_dust', '1x gtceu:sulfur_dust')
        .circuit(4)
        .inputFluids('minecraft:water 2000', 'gtceu:methane 4000')
        .outputFluids('gtceu:silicone_rubber 1296', 'gtceu:hydrogen 4000')
        .duration(32)
        .EUt(30720)
        .blastFurnaceTemp(800)
        PARA_OK = PARA_OK + 1
    } catch (errPara59) {
        PARA_BAD = PARA_BAD + 1
        if (PARA_ERRS.length < 2000) { PARA_ERRS = PARA_ERRS + '|shanhai:paradigm/portable_distort/silicone_rubber <= ' + errPara59 + ' ' }
    }

    // ── [60/97] 源 gtceu:distort/portable_distort/stem_cells  (EUt=1966080 dur=60 ebf=800)
    //    新 id = shanhai:primordial_paradigm_simplification/paradigm/portable_distort/stem_cells   电路 12   输入种类 物品8+流体4
    try {
        var b60 = gtr.primordial_paradigm_simplification('shanhai:paradigm/portable_distort/stem_cells')
        .itemInputs('1x gtceu:tiny_naquadah_dust', '1x gtceu:osmiridium_dust', '1x gtceu:salt_dust', '1x gtceu:calcium_dust', '4x gtceu:meat_dust', '4x gtceu:bio_chaff', '2x minecraft:bone', '1x kubejs:glacio_spirit')
        .circuit(12)
        .inputFluids('gtceu:phosphoric_acid 1000', 'minecraft:water 3000', 'gtceu:distilled_water 2000', 'gtceu:biomass 1000')
        .itemOutputs('64x gtceu:stem_cells', '1x gtceu:phosphorus_dust')
        .duration(60)
        .EUt(1966080)
        .blastFurnaceTemp(800)
        .cleanroom(CleanroomType.STERILE_CLEANROOM)
        PARA_OK = PARA_OK + 1
        // ⚠️ 源里的 kubejs:glacio_spirit 是概率输入（chance=8000/10000）⇒ 这里按 100% 写成普通输入
    } catch (errPara60) {
        PARA_BAD = PARA_BAD + 1
        if (PARA_ERRS.length < 2000) { PARA_ERRS = PARA_ERRS + '|shanhai:paradigm/portable_distort/stem_cells <= ' + errPara60 + ' ' }
    }

    // ── [61/97] 源 gtceu:distort/portable_distort/styrene_butadiene_rubber  (EUt=122880 dur=12 ebf=800)
    //    新 id = shanhai:primordial_paradigm_simplification/paradigm/portable_distort/styrene_butadiene_rubber   电路 5   输入种类 物品1+流体4
    try {
        var b61 = gtr.primordial_paradigm_simplification('shanhai:paradigm/portable_distort/styrene_butadiene_rubber')
        .itemInputs('5x gtceu:sulfur_dust')
        .circuit(5)
        .inputFluids('gtceu:butadiene 3000', 'gtceu:benzene 1000', 'gtceu:ethylene 1000', 'gtceu:oxygen 15000')
        .outputFluids('gtceu:styrene_butadiene_rubber 6480', 'gtceu:hydrogen 2000')
        .duration(12)
        .EUt(122880)
        .blastFurnaceTemp(800)
        PARA_OK = PARA_OK + 1
    } catch (errPara61) {
        PARA_BAD = PARA_BAD + 1
        if (PARA_ERRS.length < 2000) { PARA_ERRS = PARA_ERRS + '|shanhai:paradigm/portable_distort/styrene_butadiene_rubber <= ' + errPara61 + ' ' }
    }

    // ── [62/97] 源 gtceu:distort/portable_distort/super_mutated_living_solder  (EUt=7864320 dur=1200 ebf=800)
    //    新 id = shanhai:primordial_paradigm_simplification/paradigm/portable_distort/super_mutated_living_solder   电路 11   输入种类 物品5+流体6
    try {
        var b62 = gtr.primordial_paradigm_simplification('shanhai:paradigm/portable_distort/super_mutated_living_solder')
        .itemInputs('1152x kubejs:draconium_dust', '1152x ae2:sky_dust', '144x gtceu:nether_star_dust', '432x gtceu:coal_ore', '288x minecraft:wheat_seeds')
        .circuit(11)
        .inputFluids('gtceu:biomass 295200', 'gtceu:milk 180000', 'gtceu:sterilized_growth_medium 115200', 'gtceu:mutated_living_solder 180000', 'gtceu:distilled_water 18000', 'gtceu:carbon_dioxide 18000')
        .outputFluids('gtceu:super_mutated_living_solder 180000')
        .duration(1200)
        .EUt(7864320)
        .blastFurnaceTemp(800)
        PARA_OK = PARA_OK + 1
    } catch (errPara62) {
        PARA_BAD = PARA_BAD + 1
        if (PARA_ERRS.length < 2000) { PARA_ERRS = PARA_ERRS + '|shanhai:paradigm/portable_distort/super_mutated_living_solder <= ' + errPara62 + ' ' }
    }

    // ── [63/97] 源 gtceu:distort/portable_distort/tcetieseaweedextract  (EUt=1966080 dur=1920 ebf=800)
    //    新 id = shanhai:primordial_paradigm_simplification/paradigm/portable_distort/tcetieseaweedextract   电路 11   输入种类 物品7+流体4
    try {
        var b63 = gtr.primordial_paradigm_simplification('shanhai:paradigm/portable_distort/tcetieseaweedextract')
        .itemInputs('450x gtceu:salt_dust', '450x gtceu:agar_dust', '450x gtceu:meat_dust', '228x minecraft:kelp', '90x gtceu:alien_algae_dust', '36x gtceu:energium_dust', '4x gtceu:mithril_dust')
        .circuit(11)
        .inputFluids('gtceu:unknowwater 112000', 'gtceu:methane 225000', 'gtceu:naphthalene 90000', 'gtceu:oxygen 180000')
        .itemOutputs('256x kubejs:tcetieseaweedextract')
        .duration(1920)
        .EUt(1966080)
        .blastFurnaceTemp(800)
        .cleanroom(CleanroomType.STERILE_CLEANROOM)
        PARA_OK = PARA_OK + 1
    } catch (errPara63) {
        PARA_BAD = PARA_BAD + 1
        if (PARA_ERRS.length < 2000) { PARA_ERRS = PARA_ERRS + '|shanhai:paradigm/portable_distort/tcetieseaweedextract <= ' + errPara63 + ' ' }
    }

    // ── [64/97] 源 gtceu:distort/portable_distort/thallium_dust  (EUt=491520 dur=200 ebf=800)
    //    新 id = shanhai:primordial_paradigm_simplification/paradigm/portable_distort/thallium_dust   电路 8   输入种类 物品5+流体3
    try {
        var b64 = gtr.primordial_paradigm_simplification('shanhai:paradigm/portable_distort/thallium_dust')
        .itemInputs('28x gtceu:rubber_log', '11x gtceu:grossular_ore', '6x gtceu:sulfur_dust', '3x gtceu:carbon_dust', '2x gtceu:potassium_dust')
        .circuit(8)
        .inputFluids('gtceu:steam 28000', 'gtceu:naphtha 2800', 'gtceu:ethanol 2000')
        .itemOutputs('4x gtceu:thallium_dust', '32x gtceu:calcium_dust', '32x gtceu:aluminium_dust', '16x gtceu:tungsten_dust')
        .duration(200)
        .EUt(491520)
        .blastFurnaceTemp(800)
        PARA_OK = PARA_OK + 1
    } catch (errPara64) {
        PARA_BAD = PARA_BAD + 1
        if (PARA_ERRS.length < 2000) { PARA_ERRS = PARA_ERRS + '|shanhai:paradigm/portable_distort/thallium_dust <= ' + errPara64 + ' ' }
    }

    // ── [65/97] 源 gtceu:distort/portable_distort/trinium_compound  (EUt=1966080 dur=2000 ebf=800)
    //    新 id = shanhai:primordial_paradigm_simplification/paradigm/portable_distort/trinium_compound   电路 8   输入种类 物品4+流体4
    try {
        var b65 = gtr.primordial_paradigm_simplification('shanhai:paradigm/portable_distort/trinium_compound')
        .itemInputs('360x gtceu:trinium_compound_dust', '512x gtceu:sodium_hydroxide_dust', '16x gtceu:fullerene_dust', '16x gtceu:carbon_nanotubes_dust')
        .circuit(8)
        .inputFluids('gtceu:nitric_acid 300000', 'gtceu:hydrogen_peroxide 12000', 'gtceu:sulfur_dioxide 64000', 'gtceu:chlorine 64000')
        .itemOutputs('360x gtceu:trinium_dust', '360x gtceu:actinium_dust', '320x gtceu:selenium_dust', '320x gtceu:astatine_dust', '64x gtceu:salt_dust')
        .outputFluids('gtceu:residual_triniite_solution 128000', 'gtceu:actinium_radium_nitrate_solution 320000')
        .duration(2000)
        .EUt(1966080)
        .blastFurnaceTemp(800)
        .cleanroom(CleanroomType.CLEANROOM)
        PARA_OK = PARA_OK + 1
    } catch (errPara65) {
        PARA_BAD = PARA_BAD + 1
        if (PARA_ERRS.length < 2000) { PARA_ERRS = PARA_ERRS + '|shanhai:paradigm/portable_distort/trinium_compound <= ' + errPara65 + ' ' }
    }

    // ── [66/97] 源 gtceu:distort/portable_distort/unfolded_fullerene_dust  (EUt=1966080 dur=3200 ebf=800)
    //    新 id = shanhai:primordial_paradigm_simplification/paradigm/portable_distort/unfolded_fullerene_dust   电路 4   输入种类 物品1+流体3
    try {
        var b66 = gtr.primordial_paradigm_simplification('shanhai:paradigm/portable_distort/unfolded_fullerene_dust')
        .itemInputs('3780x gtceu:carbon_dust')
        .circuit(4)
        .inputFluids('gtceu:methane 60000', 'gtceu:bromine 60000', 'gtceu:nitrogen 60000')
        .itemOutputs('64x gtceu:unfolded_fullerene_dust')
        .outputFluids('gtceu:hydrobromic_acid 60000')
        .duration(3200)
        .EUt(1966080)
        .blastFurnaceTemp(800)
        .cleanroom(CleanroomType.CLEANROOM)
        PARA_OK = PARA_OK + 1
    } catch (errPara66) {
        PARA_BAD = PARA_BAD + 1
        if (PARA_ERRS.length < 2000) { PARA_ERRS = PARA_ERRS + '|shanhai:paradigm/portable_distort/unfolded_fullerene_dust <= ' + errPara66 + ' ' }
    }

    // ── [67/97] 源 gtceu:distort/portable_distort/viscoelastic_polyurethane_foam  (EUt=491520 dur=200 ebf=800)
    //    新 id = shanhai:primordial_paradigm_simplification/paradigm/portable_distort/viscoelastic_polyurethane_foam   电路 9   输入种类 物品2+流体7
    try {
        var b67 = gtr.primordial_paradigm_simplification('shanhai:paradigm/portable_distort/viscoelastic_polyurethane_foam')
        .itemInputs('1x gtceu:calcium_dust', '3x gtceu:carbon_dust')
        .circuit(9)
        .inputFluids('gtceu:oxygen 11000', 'minecraft:water 5000', 'gtceu:ethylene 5000', 'gtceu:air 2000', 'gtceu:nitric_acid 2000', 'gtceu:hydrogen 1000', 'gtceu:toluene 1000')
        .outputFluids('gtceu:viscoelastic_polyurethane_foam 4000')
        .duration(200)
        .EUt(491520)
        .blastFurnaceTemp(800)
        PARA_OK = PARA_OK + 1
    } catch (errPara67) {
        PARA_BAD = PARA_BAD + 1
        if (PARA_ERRS.length < 2000) { PARA_ERRS = PARA_ERRS + '|shanhai:paradigm/portable_distort/viscoelastic_polyurethane_foam <= ' + errPara67 + ' ' }
    }

    // ── [68/97] 源 gtceu:distort/portable_distort/zirconium_dust  (EUt=1966080 dur=1280 ebf=800)
    //    新 id = shanhai:primordial_paradigm_simplification/paradigm/portable_distort/zirconium_dust   电路 6   输入种类 物品2+流体4
    try {
        var b68 = gtr.primordial_paradigm_simplification('shanhai:paradigm/portable_distort/zirconium_dust')
        .itemInputs('1152x gtceu:zircon_dust', '64x gtceu:potassium_dust')
        .circuit(6)
        .inputFluids('gtceu:chlorine 512000', 'gtceu:hydrogen 256000', 'gtceu:hydrogen_peroxide 128000', 'gtceu:sulfur_trioxide 64000')
        .itemOutputs('64x gtceu:zirconium_dust', '48x gtceu:hafnium_dust', '448x gtceu:potassium_sulfate_dust')
        .outputFluids('gtceu:hydrochloric_acid 512000')
        .duration(1280)
        .EUt(1966080)
        .blastFurnaceTemp(800)
        .cleanroom(CleanroomType.CLEANROOM)
        PARA_OK = PARA_OK + 1
    } catch (errPara68) {
        PARA_BAD = PARA_BAD + 1
        if (PARA_ERRS.length < 2000) { PARA_ERRS = PARA_ERRS + '|shanhai:paradigm/portable_distort/zirconium_dust <= ' + errPara68 + ' ' }
    }

    // ── [69/97] 源 gtceu:distort/portable_distort/zylon_dust  (EUt=1966080 dur=640 ebf=800)
    //    新 id = shanhai:primordial_paradigm_simplification/paradigm/portable_distort/zylon_dust   电路 10   输入种类 物品2+流体8
    try {
        var b69 = gtr.primordial_paradigm_simplification('shanhai:paradigm/portable_distort/zylon_dust')
        .itemInputs('1762x gtceu:sulfur_dust', '41x gtceu:sodium_dust')
        .circuit(10)
        .inputFluids('gtceu:bromine 15200', 'gtceu:toluene 432000', 'gtceu:hydrogen 412000', 'gtceu:ethane 6000', 'gtceu:propene 50000', 'gtceu:nitric_acid 67000', 'gtceu:oxygen 40000', 'gtceu:benzene 70000')
        .itemOutputs('115x gtceu:zylon_dust')
        .duration(640)
        .EUt(1966080)
        .blastFurnaceTemp(800)
        .cleanroom(CleanroomType.CLEANROOM)
        PARA_OK = PARA_OK + 1
    } catch (errPara69) {
        PARA_BAD = PARA_BAD + 1
        if (PARA_ERRS.length < 2000) { PARA_ERRS = PARA_ERRS + '|shanhai:paradigm/portable_distort/zylon_dust <= ' + errPara69 + ' ' }
    }

    // ── [70/97] 源 gtceu:distort/zirconium_dust  (EUt=7864320 dur=640 ebf=16200)
    //    新 id = shanhai:primordial_paradigm_simplification/paradigm/zirconium_dust   电路 原号 6 ⇒ 偏移到 22（撞号）   输入种类 物品2+流体4
    try {
        var b70 = gtr.primordial_paradigm_simplification('shanhai:paradigm/zirconium_dust')
        .itemInputs('1152x gtceu:zircon_dust', '64x gtceu:potassium_dust')
        .circuit(22)
        .inputFluids('gtceu:chlorine 512000', 'gtceu:hydrogen 256000', 'gtceu:hydrogen_peroxide 128000', 'gtceu:sulfur_trioxide 64000')
        .itemOutputs('64x gtceu:zirconium_dust', '48x gtceu:hafnium_dust', '448x gtceu:potassium_sulfate_dust')
        .outputFluids('gtceu:hydrochloric_acid 512000')
        .duration(640)
        .EUt(7864320)
        .blastFurnaceTemp(16200)
        .cleanroom(CleanroomType.CLEANROOM)
        PARA_OK = PARA_OK + 1
    } catch (errPara70) {
        PARA_BAD = PARA_BAD + 1
        if (PARA_ERRS.length < 2000) { PARA_ERRS = PARA_ERRS + '|shanhai:paradigm/zirconium_dust <= ' + errPara70 + ' ' }
    }

    // ── [71/97] 源 gtceu:distort/zylon_dust  (EUt=125829120 dur=320 ebf=18900)
    //    新 id = shanhai:primordial_paradigm_simplification/paradigm/zylon_dust   电路 原号 10 ⇒ 偏移到 28（撞号）   输入种类 物品2+流体8
    try {
        var b71 = gtr.primordial_paradigm_simplification('shanhai:paradigm/zylon_dust')
        .itemInputs('1762x gtceu:sulfur_dust', '41x gtceu:sodium_dust')
        .circuit(28)
        .inputFluids('gtceu:bromine 15200', 'gtceu:toluene 432000', 'gtceu:hydrogen 412000', 'gtceu:ethane 6000', 'gtceu:propene 50000', 'gtceu:nitric_acid 67000', 'gtceu:oxygen 40000', 'gtceu:benzene 70000')
        .itemOutputs('115x gtceu:zylon_dust')
        .duration(320)
        .EUt(125829120)
        .blastFurnaceTemp(18900)
        .cleanroom(CleanroomType.CLEANROOM)
        PARA_OK = PARA_OK + 1
    } catch (errPara71) {
        PARA_BAD = PARA_BAD + 1
        if (PARA_ERRS.length < 2000) { PARA_ERRS = PARA_ERRS + '|shanhai:paradigm/zylon_dust <= ' + errPara71 + ' ' }
    }

    // ── [72/97] 源 gtladditions:distort/agar_dust  (EUt=1966080 dur=1 ebf=16000)
    //    新 id = shanhai:primordial_paradigm_simplification/paradigm/agar_dust   电路 6   输入种类 物品4+流体2
    try {
        var b72 = gtr.primordial_paradigm_simplification('shanhai:paradigm/agar_dust')
        .itemInputs('256x #forge:dusts/meat', '256x minecraft:bone', '64x #forge:dusts/sulfur', '16x #forge:dusts/phosphorus')
        .circuit(6)
        .inputFluids('gtceu:distilled_water 128000', 'gtceu:oxygen 128000')
        .itemOutputs('384x gtceu:agar_dust')
        .duration(1)
        .EUt(1966080)
        .blastFurnaceTemp(16000)
        .cleanroom(CleanroomType.STERILE_CLEANROOM)
        PARA_OK = PARA_OK + 1
    } catch (errPara72) {
        PARA_BAD = PARA_BAD + 1
        if (PARA_ERRS.length < 2000) { PARA_ERRS = PARA_ERRS + '|shanhai:paradigm/agar_dust <= ' + errPara72 + ' ' }
    }

    // ── [73/97] 源 gtladditions:distort/bedrock_gas  (EUt=125829120 dur=1 ebf=18000)
    //    新 id = shanhai:primordial_paradigm_simplification/paradigm/bedrock_gas   电路 4   输入种类 物品2+流体2
    try {
        var b73 = gtr.primordial_paradigm_simplification('shanhai:paradigm/bedrock_gas')
        .itemInputs('72x #forge:dusts/bedrock', '63x #forge:dusts/naquadah')
        .circuit(4)
        .inputFluids('gtceu:distilled_water 72000', 'gtceu:xenon 7200')
        .itemOutputs('4x gtceu:enriched_naquadah_dust', '4x gtceu:naquadria_dust')
        .outputFluids('gtceu:bedrock_gas 36000')
        .duration(1)
        .EUt(125829120)
        .blastFurnaceTemp(18000)
        .cleanroom(GTLCleanroomType.LAW_CLEANROOM)
        PARA_OK = PARA_OK + 1
    } catch (errPara73) {
        PARA_BAD = PARA_BAD + 1
        if (PARA_ERRS.length < 2000) { PARA_ERRS = PARA_ERRS + '|shanhai:paradigm/bedrock_gas <= ' + errPara73 + ' ' }
    }

    // ── [74/97] 源 gtladditions:distort/biology_process  (EUt=503316480 dur=1 ebf=48000)
    //    新 id = shanhai:primordial_paradigm_simplification/paradigm/biology_process   电路 3   输入种类 物品3+流体0
    try {
        var b74 = gtr.primordial_paradigm_simplification('shanhai:paradigm/biology_process')
        .itemInputs('256x gtceu:stem_cells', '256x kubejs:tcetieseaweedextract', '256x #forge:dusts/agar')
        .circuit(3)
        .itemOutputs('8192x kubejs:biological_cells')
        .outputFluids('gtceu:mutated_living_solder 73728', 'gtceu:biohmediumsterilized 1024000', 'gtceu:sterilized_growth_medium 1024000')
        .duration(1)
        .EUt(503316480)
        .blastFurnaceTemp(48000)
        .cleanroom(CleanroomType.STERILE_CLEANROOM)
        PARA_OK = PARA_OK + 1
    } catch (errPara74) {
        PARA_BAD = PARA_BAD + 1
        if (PARA_ERRS.length < 2000) { PARA_ERRS = PARA_ERRS + '|shanhai:paradigm/biology_process <= ' + errPara74 + ' ' }
    }

    // ── [75/97] 源 gtladditions:distort/composite_1  (EUt=125829120 dur=1 ebf=18000)
    //    新 id = shanhai:primordial_paradigm_simplification/paradigm/composite_1   电路 7   输入种类 物品3+流体4
    try {
        var b75 = gtr.primordial_paradigm_simplification('shanhai:paradigm/composite_1')
        .itemInputs('1440x #forge:dusts/carbon', '36x #forge:dusts/sulfur', '36x #forge:dusts/silicon')
        .circuit(7)
        .inputFluids('gtceu:hydrogen 1908000', 'gtceu:oxygen 144000', 'gtceu:fluorine 144000', 'gtceu:chlorine 36000')
        .outputFluids('gtceu:polyvinyl_chloride 36000', 'gtceu:polytetrafluoroethylene 36000', 'gtceu:silicone_rubber 36000', 'gtceu:polyphenylene_sulfide 36000', 'gtceu:styrene_butadiene_rubber 36000', 'gtceu:polyvinyl_butyral 36000')
        .duration(1)
        .EUt(125829120)
        .blastFurnaceTemp(18000)
        .cleanroom(GTLCleanroomType.LAW_CLEANROOM)
        PARA_OK = PARA_OK + 1
    } catch (errPara75) {
        PARA_BAD = PARA_BAD + 1
        if (PARA_ERRS.length < 2000) { PARA_ERRS = PARA_ERRS + '|shanhai:paradigm/composite_1 <= ' + errPara75 + ' ' }
    }

    // ── [76/97] 源 gtladditions:distort/composite_2  (EUt=503316480 dur=1 ebf=21600)
    //    新 id = shanhai:primordial_paradigm_simplification/paradigm/composite_2   电路 5   输入种类 物品1+流体4
    try {
        var b76 = gtr.primordial_paradigm_simplification('shanhai:paradigm/composite_2')
        .itemInputs('1440x #forge:dusts/carbon')
        .circuit(5)
        .inputFluids('gtceu:hydrogen 440000', 'gtceu:oxygen 88000', 'gtceu:nitrogen 40000', 'gtceu:chlorine 8000')
        .outputFluids('gtceu:epoxy 8000', 'gtceu:polyetheretherketone 8000', 'gtceu:polybenzimidazole 8000', 'gtceu:polyimide 8000')
        .duration(1)
        .EUt(503316480)
        .blastFurnaceTemp(21600)
        .cleanroom(GTLCleanroomType.LAW_CLEANROOM)
        PARA_OK = PARA_OK + 1
    } catch (errPara76) {
        PARA_BAD = PARA_BAD + 1
        if (PARA_ERRS.length < 2000) { PARA_ERRS = PARA_ERRS + '|shanhai:paradigm/composite_2 <= ' + errPara76 + ' ' }
    }

    // ── [77/97] 源 gtladditions:distort/composite_3  (EUt=2013265920 dur=1 ebf=36000)
    //    新 id = shanhai:primordial_paradigm_simplification/paradigm/composite_3   电路 15   输入种类 物品8+流体7
    try {
        var b77 = gtr.primordial_paradigm_simplification('shanhai:paradigm/composite_3')
        .itemInputs('5828x #forge:dusts/carbon', '64x #forge:dusts/iodine', '45x #forge:dusts/tin', '36x #forge:dusts/silicon', '5x #forge:dusts/palladium', '5x #forge:dusts/nickel', '5x #forge:dusts/iron', '2x #forge:dusts/calcium')
        .circuit(15)
        .inputFluids('gtceu:oxygen 2078000', 'gtceu:hydrogen 1269000', 'gtceu:chlorine 982000', 'gtceu:nitrogen 123000', 'gtceu:fluorine 96000', 'gtceu:methane 60000', 'gtceu:bromine 60000')
        .itemOutputs('64x gtceu:unfolded_fullerene_dust')
        .outputFluids('gtceu:cycloparaphenylene 32000', 'gtceu:polyurethaneresin 45000', 'gtceu:liquidcrystalkevlar 45000', 'gtceu:hydrobromic_acid 60000', 'gtceu:fluorine 4800', 'gtceu:chlorine 3200')
        .duration(1)
        .EUt(2013265920)
        .blastFurnaceTemp(36000)
        .cleanroom(GTLCleanroomType.LAW_CLEANROOM)
        PARA_OK = PARA_OK + 1
    } catch (errPara77) {
        PARA_BAD = PARA_BAD + 1
        if (PARA_ERRS.length < 2000) { PARA_ERRS = PARA_ERRS + '|shanhai:paradigm/composite_3 <= ' + errPara77 + ' ' }
    }

    // ── [78/97] 源 gtladditions:distort/germanium_dust  (EUt=491520 dur=1 ebf=13000)
    //    新 id = shanhai:primordial_paradigm_simplification/paradigm/germanium_dust   电路 5   输入种类 物品3+流体2
    try {
        var b78 = gtr.primordial_paradigm_simplification('shanhai:paradigm/germanium_dust')
        .itemInputs('144x #forge:dusts/sulfur', '18500x minecraft:nether_wart', '1536x #forge:dusts/ash')
        .circuit(5)
        .inputFluids('gtceu:oxygen 768', 'minecraft:water 512000')
        .itemOutputs('32x gtceu:germanium_dust')
        .duration(1)
        .EUt(491520)
        .blastFurnaceTemp(13000)
        .cleanroom(CleanroomType.CLEANROOM)
        PARA_OK = PARA_OK + 1
    } catch (errPara78) {
        PARA_BAD = PARA_BAD + 1
        if (PARA_ERRS.length < 2000) { PARA_ERRS = PARA_ERRS + '|shanhai:paradigm/germanium_dust <= ' + errPara78 + ' ' }
    }

    // ── [79/97] 源 gtladditions:distort/iodine_dust  (EUt=30720 dur=1 ebf=7100)
    //    新 id = shanhai:primordial_paradigm_simplification/paradigm/iodine_dust   电路 3   输入种类 物品1+流体2
    try {
        var b79 = gtr.primordial_paradigm_simplification('shanhai:paradigm/iodine_dust')
        .itemInputs('4096x minecraft:kelp')
        .circuit(3)
        .inputFluids('gtceu:chlorine 64000', 'minecraft:water 640000')
        .itemOutputs('64x gtceu:iodine_dust', '64x gtceu:potassium_dust')
        .outputFluids('gtceu:chlorine 64000')
        .duration(1)
        .EUt(30720)
        .blastFurnaceTemp(7100)
        PARA_OK = PARA_OK + 1
    } catch (errPara79) {
        PARA_BAD = PARA_BAD + 1
        if (PARA_ERRS.length < 2000) { PARA_ERRS = PARA_ERRS + '|shanhai:paradigm/iodine_dust <= ' + errPara79 + ' ' }
    }

    // ── [80/97] 源 gtladditions:distort/polycyclic_aromatic_mixture_dust  (EUt=30720 dur=1 ebf=20000)
    //    新 id = shanhai:primordial_paradigm_simplification/paradigm/polycyclic_aromatic_mixture_dust   电路 9   输入种类 物品3+流体6
    try {
        var b80 = gtr.primordial_paradigm_simplification('shanhai:paradigm/polycyclic_aromatic_mixture_dust')
        .itemInputs('16x #forge:dusts/magnesium', '144x #forge:dusts/carbon', '120x #forge:dusts/quicklime')
        .circuit(9)
        .inputFluids('gtceu:oxygen 8000', 'gtceu:chlorine 68000', 'gtceu:methanol 16000', 'gtceu:naphthalene 12000', 'gtceu:propene 4000', 'minecraft:water 24000')
        .itemOutputs('12x gtceu:polycyclic_aromatic_mixture_dust')
        .outputFluids('gtceu:hydrogen 4000', 'gtceu:acetone 4000')
        .duration(1)
        .EUt(30720)
        .blastFurnaceTemp(20000)
        .cleanroom(CleanroomType.STERILE_CLEANROOM)
        PARA_OK = PARA_OK + 1
    } catch (errPara80) {
        PARA_BAD = PARA_BAD + 1
        if (PARA_ERRS.length < 2000) { PARA_ERRS = PARA_ERRS + '|shanhai:paradigm/polycyclic_aromatic_mixture_dust <= ' + errPara80 + ' ' }
    }

    // ── [81/97] 源 gtladditions:distort/rare_earth_dust_monazite  (EUt=1966080 dur=1 ebf=12000)
    //    新 id = shanhai:primordial_paradigm_simplification/paradigm/rare_earth_dust_monazite   电路 6   输入种类 物品3+流体3
    try {
        var b81 = gtr.primordial_paradigm_simplification('shanhai:paradigm/rare_earth_dust_monazite')
        .itemInputs('64x #forge:dusts/monazite', '288x #forge:dusts/cerium_rich_mixture_powder', '360x #forge:dusts/samarium_refined_powder')
        .circuit(6)
        .inputFluids('gtceu:nitric_acid 120000', 'gtceu:chlorine 160000', 'gtceu:acetone 160000')
        .itemOutputs('1440x gtceu:rare_earth_dust', '512x gtceu:uranium_235_dust', '480x gtceu:samarium_dust', '384x gtceu:phosphorus_dust')
        .outputFluids('gtceu:hydrogen 96000', 'gtceu:diluted_hydrochloric_acid 96000', 'gtceu:nitrogen 90000')
        .duration(1)
        .EUt(1966080)
        .blastFurnaceTemp(12000)
        .cleanroom(CleanroomType.CLEANROOM)
        PARA_OK = PARA_OK + 1
    } catch (errPara81) {
        PARA_BAD = PARA_BAD + 1
        if (PARA_ERRS.length < 2000) { PARA_ERRS = PARA_ERRS + '|shanhai:paradigm/rare_earth_dust_monazite <= ' + errPara81 + ' ' }
    }

    // ── [82/97] 源 gtladditions:distort/rhenium_dust  (EUt=31457280 dur=1 ebf=14400)
    //    新 id = shanhai:primordial_paradigm_simplification/paradigm/rhenium_dust   电路 5   输入种类 物品2+流体3
    try {
        var b82 = gtr.primordial_paradigm_simplification('shanhai:paradigm/rhenium_dust')
        .itemInputs('48x #forge:dusts/molybdenite', '3x #forge:dusts/iron')
        .circuit(5)
        .inputFluids('gtceu:hydrogen 112000', 'gtceu:oxygen 68000', 'gtceu:chlorine 12000')
        .itemOutputs('48x gtceu:rhenium_dust', '16x gtceu:gold_dust', '16x gtceu:molybdenum_dust')
        .outputFluids('gtceu:hydrogen_sulfide 48000')
        .duration(1)
        .EUt(31457280)
        .blastFurnaceTemp(14400)
        .cleanroom(CleanroomType.STERILE_CLEANROOM)
        PARA_OK = PARA_OK + 1
    } catch (errPara82) {
        PARA_BAD = PARA_BAD + 1
        if (PARA_ERRS.length < 2000) { PARA_ERRS = PARA_ERRS + '|shanhai:paradigm/rhenium_dust <= ' + errPara82 + ' ' }
    }

    // ── [83/97] 源 gtladditions:distort/tcetieseaweedextract  (EUt=31457280 dur=1 ebf=18000)
    //    新 id = shanhai:primordial_paradigm_simplification/paradigm/gtladditions__tcetieseaweedextract   电路 11   输入种类 物品7+流体4
    try {
        var b83 = gtr.primordial_paradigm_simplification('shanhai:paradigm/gtladditions__tcetieseaweedextract')
        .itemInputs('450x #forge:dusts/salt', '450x #forge:dusts/agar', '450x #forge:dusts/meat', '228x minecraft:kelp', '90x #forge:dusts/alien_algae', '36x gtceu:energium_dust', '4x #forge:dusts/mithril')
        .circuit(11)
        .inputFluids('gtceu:unknowwater 112000', 'gtceu:methane 225000', 'gtceu:naphthalene 90000', 'gtceu:oxygen 180000')
        .itemOutputs('256x kubejs:tcetieseaweedextract')
        .duration(1)
        .EUt(31457280)
        .blastFurnaceTemp(18000)
        .cleanroom(GTLCleanroomType.LAW_CLEANROOM)
        PARA_OK = PARA_OK + 1
    } catch (errPara83) {
        PARA_BAD = PARA_BAD + 1
        if (PARA_ERRS.length < 2000) { PARA_ERRS = PARA_ERRS + '|shanhai:paradigm/gtladditions__tcetieseaweedextract <= ' + errPara83 + ' ' }
    }

    // ── [84/97] 源 gtladditions:distort/trinium_compound  (EUt=125829120 dur=1 ebf=18000)
    //    新 id = shanhai:primordial_paradigm_simplification/paradigm/trinium_compound   电路 8   输入种类 物品4+流体4
    try {
        var b84 = gtr.primordial_paradigm_simplification('shanhai:paradigm/trinium_compound')
        .itemInputs('360x #forge:dusts/trinium_compound', '512x #forge:dusts/sodium_hydroxide', '16x #forge:dusts/fullerene', '16x #forge:dusts/carbon_nanotubes')
        .circuit(8)
        .inputFluids('gtceu:nitric_acid 300000', 'gtceu:hydrogen_peroxide 12000', 'gtceu:sulfur_dioxide 64000', 'gtceu:chlorine 64000')
        .itemOutputs('360x gtceu:trinium_dust', '360x gtceu:actinium_dust', '320x gtceu:selenium_dust', '320x gtceu:astatine_dust', '64x gtceu:salt_dust')
        .outputFluids('gtceu:residual_triniite_solution 128000', 'gtceu:actinium_radium_nitrate_solution 320000')
        .duration(1)
        .EUt(125829120)
        .blastFurnaceTemp(18000)
        .cleanroom(GTLCleanroomType.LAW_CLEANROOM)
        PARA_OK = PARA_OK + 1
    } catch (errPara84) {
        PARA_BAD = PARA_BAD + 1
        if (PARA_ERRS.length < 2000) { PARA_ERRS = PARA_ERRS + '|shanhai:paradigm/trinium_compound <= ' + errPara84 + ' ' }
    }

    // ── [85/97] 源 kubejs:distort/biological_cells  (EUt=491520 dur=400 ebf=16200)
    //    新 id = shanhai:primordial_paradigm_simplification/paradigm/biological_cells   电路 9   输入种类 物品8+流体1
    try {
        var b85 = gtr.primordial_paradigm_simplification('shanhai:paradigm/biological_cells')
        .itemInputs('256x gtceu:stem_cells', '64x gtceu:meat_dust', '64x gtceu:salt_dust', '64x gtceu:calcium_dust', '64x gtceu:agar_dust', '4x kubejs:tcetieseaweedextract', '2x gtceu:enriched_naquadah_dust', '1x gtceu:tritanium_dust')
        .circuit(9)
        .inputFluids('gtceu:mutagen 10000')
        .itemOutputs('64x kubejs:biological_cells')
        .duration(400)
        .EUt(491520)
        .blastFurnaceTemp(16200)
        .cleanroom(CleanroomType.STERILE_CLEANROOM)
        PARA_OK = PARA_OK + 1
    } catch (errPara85) {
        PARA_BAD = PARA_BAD + 1
        if (PARA_ERRS.length < 2000) { PARA_ERRS = PARA_ERRS + '|shanhai:paradigm/biological_cells <= ' + errPara85 + ' ' }
    }

    // ── [86/97] 源 thetornproductionline:distort/easier_aerographene  (EUt=491520 dur=10 ebf=800)
    //    新 id = shanhai:primordial_paradigm_simplification/paradigm/easier_aerographene   电路 原号 9 ⇒ 偏移到 26（撞号）   输入种类 物品1+流体8
    try {
        var b86 = gtr.primordial_paradigm_simplification('shanhai:paradigm/easier_aerographene')
        .itemInputs('2x gtceu:sugar_gem')
        .circuit(26)
        .inputFluids('gtceu:oxygen 4000', 'gtceu:chlorine 3000', 'gtceu:propene 1000', 'minecraft:water 1000', 'gtceu:benzene 1000', 'gtceu:acetone 1000', 'gtceu:methanol 1000', 'gtceu:carbon_dioxide 1000')
        .itemOutputs('1x kubejs:aerographene')
        .duration(10)
        .EUt(491520)
        .blastFurnaceTemp(800)
        PARA_OK = PARA_OK + 1
    } catch (errPara86) {
        PARA_BAD = PARA_BAD + 1
        if (PARA_ERRS.length < 2000) { PARA_ERRS = PARA_ERRS + '|shanhai:paradigm/easier_aerographene <= ' + errPara86 + ' ' }
    }

    // ── [87/97] 源 thetornproductionline:distort/easier_azafullerene  (EUt=491520 dur=10 ebf=800)
    //    新 id = shanhai:primordial_paradigm_simplification/paradigm/easier_azafullerene   电路 原号 3 ⇒ 偏移到 19（撞号）   输入种类 物品1+流体2
    try {
        var b87 = gtr.primordial_paradigm_simplification('shanhai:paradigm/easier_azafullerene')
        .itemInputs('1x gtceu:fullerene_dust')
        .circuit(19)
        .inputFluids('gtceu:nitrogen 12000', 'gtceu:hydrogen 8000')
        .outputFluids('gtceu:azafullerene 1000')
        .duration(10)
        .EUt(491520)
        .blastFurnaceTemp(800)
        PARA_OK = PARA_OK + 1
    } catch (errPara87) {
        PARA_BAD = PARA_BAD + 1
        if (PARA_ERRS.length < 2000) { PARA_ERRS = PARA_ERRS + '|shanhai:paradigm/easier_azafullerene <= ' + errPara87 + ' ' }
    }

    // ── [88/97] 源 thetornproductionline:distort/easier_cosmic_superconductor  (EUt=125829120 dur=16000 ebf=800)
    //    新 id = shanhai:primordial_paradigm_simplification/paradigm/easier_cosmic_superconductor   电路 原号 17 ⇒ 偏移到 32（撞号）   输入种类 物品8+流体9
    try {
        var b88 = gtr.primordial_paradigm_simplification('shanhai:paradigm/easier_cosmic_superconductor')
        .itemInputs('1x gtceu:phosphorus_dust', '18x gtceu:sulfur_dust', '6x gtceu:sodium_dust', '1x gtceu:thallium_dust', '6x gtceu:rhenium_chloride_dust', '5x gtceu:hassium_chloride_dust', '36x gtceu:atinium_hydride_dust', '14x gtceu:charged_caesium_cerium_cobalt_indium_dust')
        .circuit(32)
        .inputFluids('gtceu:chlorine 34000', 'gtceu:fluorine 3000', 'gtceu:ethylene 12000', 'gtceu:oxygen 46000', 'gtceu:hydrogen 102000', 'gtceu:uu_matter 2000', 'gtceu:quark_gluon_plasma 17000', 'gtceu:benzene 3000', 'gtceu:acetone 6000')
        .outputFluids('gtceu:cosmic_superconductor 10000')
        .duration(16000)
        .EUt(125829120)
        .blastFurnaceTemp(800)
        .cleanroom(CleanroomType.STERILE_CLEANROOM)
        PARA_OK = PARA_OK + 1
    } catch (errPara88) {
        PARA_BAD = PARA_BAD + 1
        if (PARA_ERRS.length < 2000) { PARA_ERRS = PARA_ERRS + '|shanhai:paradigm/easier_cosmic_superconductor <= ' + errPara88 + ' ' }
    }

    // ── [89/97] 源 thetornproductionline:distort/easier_dragon_blood  (EUt=125829120 dur=300 ebf=800)
    //    新 id = shanhai:primordial_paradigm_simplification/paradigm/easier_dragon_blood   电路 8   输入种类 物品3+流体5
    try {
        var b89 = gtr.primordial_paradigm_simplification('shanhai:paradigm/easier_dragon_blood')
        .itemInputs('80x gtceu:naquadria_dust', '64x minecraft:dragon_egg', '320x gtceu:stem_cells')
        .circuit(8)
        .inputFluids('gtceu:mana 384000', 'gtceu:sterilized_growth_medium 320000', 'gtceu:xpjuice 82000', 'gtceu:biohmediumsterilized 64000', 'gtceu:mutagen 50000')
        .outputFluids('gtceu:dragon_blood 319680')
        .duration(300)
        .EUt(125829120)
        .blastFurnaceTemp(800)
        .cleanroom(CleanroomType.STERILE_CLEANROOM)
        PARA_OK = PARA_OK + 1
    } catch (errPara89) {
        PARA_BAD = PARA_BAD + 1
        if (PARA_ERRS.length < 2000) { PARA_ERRS = PARA_ERRS + '|shanhai:paradigm/easier_dragon_blood <= ' + errPara89 + ' ' }
    }

    // ── [90/97] 源 thetornproductionline:distort/easier_germanium_dust  (EUt=1966080 dur=2000 ebf=800)
    //    新 id = shanhai:primordial_paradigm_simplification/paradigm/easier_germanium_dust   电路 5   输入种类 物品2+流体3
    try {
        var b90 = gtr.primordial_paradigm_simplification('shanhai:paradigm/easier_germanium_dust')
        .itemInputs('3x gtceu:molybdenite_dust', '1x gtceu:sodium_dust')
        .circuit(5)
        .inputFluids('gtceu:oxygen 7250', 'gtceu:ammonia 3000', 'gtceu:iron_iii_chloride 250')
        .itemOutputs('1x gtceu:gold_dust', '4x gtceu:molybdenum_trioxide_dust')
        .outputFluids('gtceu:ammonium_perrhenate 3000')
        .duration(2000)
        .EUt(1966080)
        .blastFurnaceTemp(800)
        PARA_OK = PARA_OK + 1
    } catch (errPara90) {
        PARA_BAD = PARA_BAD + 1
        if (PARA_ERRS.length < 2000) { PARA_ERRS = PARA_ERRS + '|shanhai:paradigm/easier_germanium_dust <= ' + errPara90 + ' ' }
    }

    // ── [91/97] 源 thetornproductionline:distort/easier_grade_13_purified_water  (EUt=31457280 dur=600 ebf=800)
    //    新 id = shanhai:primordial_paradigm_simplification/paradigm/easier_grade_13_purified_water   电路 3   输入种类 物品1+流体2
    try {
        var b91 = gtr.primordial_paradigm_simplification('shanhai:paradigm/easier_grade_13_purified_water')
        .itemInputs('1x minecraft:blaze_powder')
        .circuit(3)
        .inputFluids('gtceu:grade_8_purified_water 1000', 'gtceu:liquid_oxygen 1000')
        .outputFluids('gtceu:grade_13_purified_water 728')
        .duration(600)
        .EUt(31457280)
        .blastFurnaceTemp(800)
        PARA_OK = PARA_OK + 1
    } catch (errPara91) {
        PARA_BAD = PARA_BAD + 1
        if (PARA_ERRS.length < 2000) { PARA_ERRS = PARA_ERRS + '|shanhai:paradigm/easier_grade_13_purified_water <= ' + errPara91 + ' ' }
    }

    // ── [92/97] 源 thetornproductionline:distort/easier_photopolymer  (EUt=125829120 dur=10240 ebf=800)
    //    新 id = shanhai:primordial_paradigm_simplification/paradigm/easier_photopolymer   电路 原号 14 ⇒ 偏移到 30（撞号）   输入种类 物品8+流体6
    try {
        var b92 = gtr.primordial_paradigm_simplification('shanhai:paradigm/easier_photopolymer')
        .itemInputs('768x gtceu:carbon_dust', '50x gtceu:rutile_dust', '70x gtceu:succinic_acid_dust', '32x gtceu:ice_dust', '20x gtceu:silver_dust', '25x gtceu:quicklime_dust', '40x gtceu:boron_dust', '120x gtceu:sodium_dust')
        .circuit(30)
        .inputFluids('gtceu:benzene 40000', 'gtceu:bromine 25000', 'gtceu:oxygen 21600', 'gtceu:chlorine 5600', 'gtceu:propene 16000', 'gtceu:butene 80000')
        .outputFluids('gtceu:photopolymer 16000')
        .duration(10240)
        .EUt(125829120)
        .blastFurnaceTemp(800)
        .cleanroom(CleanroomType.CLEANROOM)
        PARA_OK = PARA_OK + 1
    } catch (errPara92) {
        PARA_BAD = PARA_BAD + 1
        if (PARA_ERRS.length < 2000) { PARA_ERRS = PARA_ERRS + '|shanhai:paradigm/easier_photopolymer <= ' + errPara92 + ' ' }
    }

    // ── [93/97] 源 thetornproductionline:distort/easier_taranium_dust  (EUt=31457280 dur=2000 ebf=800)
    //    新 id = shanhai:primordial_paradigm_simplification/paradigm/easier_taranium_dust   电路 9   输入种类 物品2+流体7
    try {
        var b93 = gtr.primordial_paradigm_simplification('shanhai:paradigm/easier_taranium_dust')
        .itemInputs('960x gtceu:stone_dust', '1x gtceu:activated_carbon_dust')
        .circuit(9)
        .inputFluids('gtceu:oxygen 40000', 'gtceu:fluorine 40000', 'gtceu:distilled_water 20000', 'gtceu:hydrogen 3000', 'gtceu:bedrock_gas 2000', 'gtceu:aqua_regia 2000', 'gtceu:helium_3 2000')
        .itemOutputs('1x gtceu:taranium_dust')
        .duration(2000)
        .EUt(31457280)
        .blastFurnaceTemp(800)
        PARA_OK = PARA_OK + 1
    } catch (errPara93) {
        PARA_BAD = PARA_BAD + 1
        if (PARA_ERRS.length < 2000) { PARA_ERRS = PARA_ERRS + '|shanhai:paradigm/easier_taranium_dust <= ' + errPara93 + ' ' }
    }

    // ── [94/97] 源 thetornproductionline:distort/easier_thallium_dust  (EUt=491520 dur=1000 ebf=800)
    //    新 id = shanhai:primordial_paradigm_simplification/paradigm/easier_thallium_dust   电路 8   输入种类 物品5+流体3
    try {
        var b94 = gtr.primordial_paradigm_simplification('shanhai:paradigm/easier_thallium_dust')
        .itemInputs('28x #minecraft:logs', '11x #forge:ores/grossular', '6x gtceu:sulfur_dust', '3x gtceu:carbon_dust', '2x gtceu:potassium_dust')
        .circuit(8)
        .inputFluids('gtceu:steam 28000', 'gtceu:naphtha 2800', 'gtceu:ethanol 2000')
        .itemOutputs('4x gtceu:thallium_dust', '32x gtceu:calcium_dust', '32x gtceu:aluminium_dust', '16x gtceu:tungsten_dust')
        .duration(1000)
        .EUt(491520)
        .blastFurnaceTemp(800)
        PARA_OK = PARA_OK + 1
    } catch (errPara94) {
        PARA_BAD = PARA_BAD + 1
        if (PARA_ERRS.length < 2000) { PARA_ERRS = PARA_ERRS + '|shanhai:paradigm/easier_thallium_dust <= ' + errPara94 + ' ' }
    }

    // ── [95/97] 源 thetornproductionline:distort/easier_trinium_compound  (EUt=31457280 dur=2400 ebf=800)
    //    新 id = shanhai:primordial_paradigm_simplification/paradigm/easier_trinium_compound   电路 原号 8 ⇒ 偏移到 24（撞号）   输入种类 物品4+流体4
    try {
        var b95 = gtr.primordial_paradigm_simplification('shanhai:paradigm/easier_trinium_compound')
        .itemInputs('360x gtceu:trinium_compound_dust', '512x gtceu:sodium_hydroxide_dust', '16x gtceu:fullerene_dust', '16x gtceu:carbon_nanotubes_dust')
        .circuit(24)
        .inputFluids('gtceu:nitric_acid 300000', 'gtceu:hydrogen_peroxide 12000', 'gtceu:sulfur_dioxide 64000', 'gtceu:chlorine 64000')
        .itemOutputs('360x gtceu:trinium_dust', '360x gtceu:actinium_dust', '320x gtceu:selenium_dust', '320x gtceu:astatine_dust', '64x gtceu:salt_dust')
        .outputFluids('gtceu:residual_triniite_solution 128000', 'gtceu:actinium_radium_nitrate_solution 320000')
        .duration(2400)
        .EUt(31457280)
        .blastFurnaceTemp(800)
        .cleanroom(CleanroomType.STERILE_CLEANROOM)
        PARA_OK = PARA_OK + 1
    } catch (errPara95) {
        PARA_BAD = PARA_BAD + 1
        if (PARA_ERRS.length < 2000) { PARA_ERRS = PARA_ERRS + '|shanhai:paradigm/easier_trinium_compound <= ' + errPara95 + ' ' }
    }

    // ── [96/97] 源 thetornproductionline:distort/easier_viscoelastic_polyurethane_foam  (EUt=491520 dur=10 ebf=800)
    //    新 id = shanhai:primordial_paradigm_simplification/paradigm/easier_viscoelastic_polyurethane_foam   电路 原号 9 ⇒ 偏移到 27（撞号）   输入种类 物品2+流体7
    try {
        var b96 = gtr.primordial_paradigm_simplification('shanhai:paradigm/easier_viscoelastic_polyurethane_foam')
        .itemInputs('1x gtceu:calcium_dust', '3x gtceu:carbon_dust')
        .circuit(27)
        .inputFluids('gtceu:oxygen 11000', 'minecraft:water 5000', 'gtceu:ethylene 5000', 'gtceu:air 2000', 'gtceu:nitric_acid 2000', 'gtceu:hydrogen 1000', 'gtceu:toluene 1000')
        .outputFluids('gtceu:viscoelastic_polyurethane_foam 4000')
        .duration(10)
        .EUt(491520)
        .blastFurnaceTemp(800)
        PARA_OK = PARA_OK + 1
    } catch (errPara96) {
        PARA_BAD = PARA_BAD + 1
        if (PARA_ERRS.length < 2000) { PARA_ERRS = PARA_ERRS + '|shanhai:paradigm/easier_viscoelastic_polyurethane_foam <= ' + errPara96 + ' ' }
    }

    // ── [97/97] 源 thetornproductionline:distort/easier_zeolite_sieving_pellets_dust  (EUt=491520 dur=150 ebf=800)
    //    新 id = shanhai:primordial_paradigm_simplification/paradigm/easier_zeolite_sieving_pellets_dust   电路 10   输入种类 物品6+流体4
    try {
        var b97 = gtr.primordial_paradigm_simplification('shanhai:paradigm/easier_zeolite_sieving_pellets_dust')
        .itemInputs('23x gtceu:borax_dust', '3x gtceu:silicon_dioxide_dust', '3x gtceu:lithium_dust', '3x gtceu:aluminium_dust', '4x gtceu:sodium_dust', '1x gtceu:zeolite_dust')
        .circuit(10)
        .inputFluids('gtceu:hydrogen 26000', 'gtceu:fluorine 12000', 'minecraft:water 8000', 'gtceu:chlorine 5000')
        .itemOutputs('1x gtceu:zeolite_sieving_pellets_dust', '2x gtceu:lithium_fluoride_dust', '8x gtceu:salt_dust')
        .outputFluids('gtceu:diborane 2000')
        .duration(150)
        .EUt(491520)
        .blastFurnaceTemp(800)
        PARA_OK = PARA_OK + 1
    } catch (errPara97) {
        PARA_BAD = PARA_BAD + 1
        if (PARA_ERRS.length < 2000) { PARA_ERRS = PARA_ERRS + '|shanhai:paradigm/easier_zeolite_sieving_pellets_dust <= ' + errPara97 + ' ' }
    }

    console.info('[SHANHAI-PARADIGM] 声明=97 条（去重丢弃 54、去催化剂 176、电路偏移 15） ok=' + PARA_OK + ' failed=' + PARA_BAD + (PARA_OK === 97 && PARA_BAD === 0 ? ' ✅' : ' 🔴 对不上'))
    if (PARA_ERRS.length > 0) { console.error('[SHANHAI-PARADIGM] 失败明细：' + PARA_ERRS) }
})
