// ============================================================================
// 「原初物质定型」KJS 发射器（共享模块）
//
// 被两处 require：
//   ① kubejs\_generators\gen_pf_kjs.js      —— 从【数据包里那 2457 个 json】转出 KJS（离线可跑）
//   ② kubejs\_generators\gen_pf_forming.js  —— 从 kubejs export 生成 json 之后，顺手用同一份
//                                              代码路径产出同一个 KJS
//   ⇒ 两条路【共用这一个发射器】，所以不可能出现"生成器产的 KJS"和"转换器产的 KJS"不一致。
//
// 🔴 本模块是【纯函数】：obj(配方 json) → row/文本。不读文件、不写文件、不碰路径。
// ============================================================================
'use strict'

const TYPE = 'gtceu:primordial_matter_forming'   // 配方类型（Java 侧注册，本模块不注册）
const SHORT = 'primordial_matter_forming'         // KJS 里的方法名：gtr[SHORT](...)
const DECLARED = 2457                             // Java 侧锚点 ShanhaiRecipeTypes.PRIMORDIAL_MATTER_FORMING_DECLARED_RECIPES
const NS = 'shanhai'                              // 配方 id 命名空间

// ── 需要显式报错（而不是静默出错）的字段形状 ────────────────────────────────
function fail(name, msg) { throw new Error('🔴 [' + name + '] ' + msg) }

// ── 单个物品槽 → KJS 片段 ───────────────────────────────────────────────────
// 口径（全部由字节码/实测锚定，见 temp\handoff 报告 §2）：
//   · chance=10000（默认）        → .itemInputs('Nx item' / 'Nx #tag')
//   · chance=0 且 ingredient 是电路 → .circuit(N)
//   · chance=0 且是普通物品        → .notConsumable('Nx item')
function itemTok(name, slot, where) {
    const c = slot.content
    if (!c || c.type !== 'gtceu:sized') fail(name, where + ' content.type 不是 gtceu:sized：' + JSON.stringify(c))
    if (slot.maxChance !== 10000) fail(name, where + ' maxChance 不是 10000：' + slot.maxChance)
    if (slot.tierChanceBoost !== 0) fail(name, where + ' tierChanceBoost 不是 0：' + slot.tierChanceBoost)
    const ing = c.ingredient
    if (!ing) fail(name, where + ' 缺 ingredient')
    if (ing.type === 'gtceu:circuit') {
        if (slot.chance !== 0) fail(name, where + ' 电路槽 chance 不是 0：' + slot.chance)
        if (c.count !== 1) fail(name, where + ' 电路槽 count 不是 1：' + c.count)
        return { kind: 'circuit', circuit: ing.configuration }
    }
    if (ing.item) {
        return { kind: slot.chance === 0 ? 'nc' : 'in', text: c.count + 'x ' + ing.item }
    }
    if (ing.tag) {
        if (slot.chance !== 10000) fail(name, where + ' 标签物品槽 chance 不是 10000：' + slot.chance)
        return { kind: 'in', text: c.count + 'x #' + ing.tag }
    }
    fail(name, where + ' ingredient 既不是 item / tag / circuit：' + JSON.stringify(ing))
}

// ── 单个流体槽 → KJS 片段 ───────────────────────────────────────────────────
// 口径：content = {amount, value:[{fluid}|{tag}]}；流体【总是】输入（2457 条里流体出=0）
function fluidTok(name, slot, where) {
    if (slot.chance !== 10000) fail(name, where + ' 流体槽 chance 不是 10000：' + slot.chance)
    if (slot.maxChance !== 10000) fail(name, where + ' 流体槽 maxChance 不是 10000：' + slot.maxChance)
    if (slot.tierChanceBoost !== 0) fail(name, where + ' 流体槽 tierChanceBoost 不是 0：' + slot.tierChanceBoost)
    const c = slot.content
    if (!c || typeof c.amount !== 'number' || !Array.isArray(c.value)) {
        fail(name, where + ' 流体 content 形状不认识：' + JSON.stringify(c))
    }
    if (c.value.length !== 1) fail(name, where + ' 流体 value 不是 1 个：' + JSON.stringify(c.value))
    const v = c.value[0]
    if (v.fluid) return v.fluid + ' ' + c.amount          // → KubeJS 字符串 'id amount'（实测可用）
    if (v.tag) return '#' + v.tag + ' ' + c.amount        // → 走 PF_FLUID() 的 Java 侧构造分支
    fail(name, where + ' 流体 value 既不是 fluid 也不是 tag：' + JSON.stringify(v))
}

// ── 配方 json → 一行数据表 row ──────────────────────────────────────────────
function jsonToRow(name, j) {
    if (j.type !== TYPE) fail(name, '配方 type 不是 ' + TYPE + '：' + j.type)

    // 顶层键必须是已知集合（多一个就报错，防止上游加了字段而这里静默丢掉）
    const knownTop = ['type', 'duration', 'data', 'inputs', 'outputs', 'tickInputs', 'tickOutputs',
        'inputChanceLogics', 'outputChanceLogics', 'tickInputChanceLogics', 'tickOutputChanceLogics',
        'recipeConditions']
    for (const k of Object.keys(j)) if (knownTop.indexOf(k) < 0) fail(name, '出现未登记的顶层键：' + k)

    const row = { n: name }

    // ── 物品输入：保持 json 里的原始顺序 ──────────────────────────────────
    const itemIn = (j.inputs && j.inputs.item) || []
    const nc = [], ins = []
    // 🔴 用 -1 表示"没有电路"、0 表示"电路号 0"。
    //   2026-10-01 第一次自证抓到：gtceu__solidify_anvil 用的就是【电路号 0】，
    //   原先写成 `if (circuit)` 会把 0 当假的 ⇒ 那一条的电路槽被静默丢掉（2457 里 1 条）。
    //   ⇒ 现在用 circuit >= 0 判"有没有"，用值本身（含 0）当电路号。
    let circuit = -1
    // 顺序闸门：本批 2457 条的 inputs.item 顺序模式【只有】6 种（现算）：
    //   'IC'(1320) 'C'(1108) 'NI'(24) 'I'(3) ''(1) 'N'(1)
    //   ⇒ 「不消耗 → 普通 → 电路」这个发射顺序能逐条复现它们全部。
    //   一旦出现别的模式（如 'CI'），这里【响亮报错】，绝不静默改序。
    let seenNormal = false
    for (let i = 0; i < itemIn.length; i++) {
        const t = itemTok(name, itemIn[i], 'inputs.item[' + i + ']')
        if (t.kind === 'circuit') {
            if (circuit >= 0) fail(name, '一条配方里出现两个电路槽')
            circuit = t.circuit
        } else if (t.kind === 'nc') {
            if (seenNormal || circuit >= 0) fail(name, "顺序模式不在白名单内（'N' 出现在 'I'/'C' 之后）")
            nc.push(t.text)
        } else {
            if (circuit >= 0) fail(name, "顺序模式不在白名单内（'I' 出现在 'C' 之后）")
            seenNormal = true
            ins.push(t.text)
        }
    }
    if (nc.length) row.nc = nc          // [N] 不消耗（催化剂）
    if (ins.length) row.a = ins         // [I] 普通物品输入
    if (circuit >= 0) row.c = circuit   // [C] 编程电路号（0..32；0 也要发）

    // ── 流体输入 ─────────────────────────────────────────────────────────
    const fluidIn = (j.inputs && j.inputs.fluid) || []
    if (fluidIn.length) {
        row.f = []
        for (let i = 0; i < fluidIn.length; i++) row.f.push(fluidTok(name, fluidIn[i], 'inputs.fluid[' + i + ']'))
    }

    // ── 物品输出 ─────────────────────────────────────────────────────────
    const itemOut = (j.outputs && j.outputs.item) || []
    if (!itemOut.length) fail(name, '没有物品输出')
    row.o = []
    for (let i = 0; i < itemOut.length; i++) {
        const s = itemOut[i]
        if (s.chance !== 10000) fail(name, 'outputs.item[' + i + '] chance 不是 10000：' + s.chance)
        const c = s.content
        if (!c || c.type !== 'gtceu:sized' || !c.ingredient || !c.ingredient.item) {
            fail(name, 'outputs.item[' + i + '] 形状不认识：' + JSON.stringify(s))
        }
        row.o.push(c.count + 'x ' + c.ingredient.item)
    }

    // ── 其它能力槽：本批必须为空（空=不发射；非空=报错，不静默丢） ────────
    for (const grp of ['outputs', 'tickOutputs']) {
        const caps = Object.keys(j[grp] || {})
        for (const cap of caps) if (cap !== 'item') fail(name, grp + ' 出现未登记的 capability：' + cap)
    }
    for (const grp of ['inputs', 'outputs', 'tickInputs', 'tickOutputs']) {
        const caps = Object.keys(j[grp] || {})
        const allow = grp === 'inputs' ? ['item', 'fluid'] : (grp === 'outputs' ? ['item'] : ['eu'])
        for (const cap of caps) if (allow.indexOf(cap) < 0) fail(name, grp + ' 出现未登记的 capability：' + cap)
    }
    for (const lk of ['inputChanceLogics', 'outputChanceLogics', 'tickInputChanceLogics', 'tickOutputChanceLogics']) {
        if (j[lk] && Object.keys(j[lk]).length) fail(name, lk + ' 非空：' + JSON.stringify(j[lk]))
    }
    const tickOut = j.tickOutputs || {}
    if (Object.keys(tickOut).length) fail(name, 'tickOutputs 非空：' + JSON.stringify(tickOut))

    // ── EU / duration ────────────────────────────────────────────────────
    const euArr = (j.tickInputs && j.tickInputs.eu) || []
    if (euArr.length !== 1) fail(name, 'tickInputs.eu 不是恰好 1 条：' + euArr.length)
    if (euArr[0].chance !== 10000 || euArr[0].maxChance !== 10000 || euArr[0].tierChanceBoost !== 0) {
        fail(name, 'tickInputs.eu 的 chance 字段不对：' + JSON.stringify(euArr[0]))
    }
    const eu = euArr[0].content
    if (typeof eu !== 'number' || eu <= 0) fail(name, '.EUt() 只接正数，本条 EU=' + eu)
    row.e = eu
    // 🔴 data.euTier 由 GTLCore 的 GTRecipeJSMixin 在 EUt(long) 里自动写：
    //    addData("euTier", GTUtil.getTierByVoltage(|eu|))
    //    ⇒ 这里只【核对】它等于那个公式，不额外发射 addData（否则会与 mixin 打架）
    if (!j.data || typeof j.data.euTier !== 'number') fail(name, '缺 data.euTier')
    for (const k of Object.keys(j.data)) if (k !== 'euTier') fail(name, 'data 出现未登记的键：' + k)
    const expect = getTierByVoltage(eu)
    if (expect !== j.data.euTier) fail(name, 'euTier 与 getTierByVoltage(EU) 不一致：json=' + j.data.euTier + ' 公式=' + expect)

    if (typeof j.duration !== 'number' || j.duration <= 0) fail(name, 'duration 非法：' + j.duration)
    row.d = j.duration

    // ── 条件：本批只有 1 条，且只有 cleanroom ────────────────────────────
    if (j.recipeConditions !== undefined) {
        if (!Array.isArray(j.recipeConditions) || j.recipeConditions.length !== 1) {
            fail(name, 'recipeConditions 不是恰好 1 条：' + JSON.stringify(j.recipeConditions))
        }
        const c = j.recipeConditions[0]
        if (c.type !== 'cleanroom' || !c.data || c.data.cleanroom !== 'cleanroom') {
            fail(name, 'recipeCondition 不是 cleanroom/cleanroom：' + JSON.stringify(c))
        }
        row.r = 1
    }
    return row
}

// GTUtil.getTierByVoltage(long) 的【等价实现】（字节码逐句翻译，见 temp\handoff 报告 §2.4）：
//   v > 2147483647        → 14
//   v <= V[0] (=8)        → 0
//   否则                  → (62 - numberOfLeadingZeros(v-1)) >> 1
function getTierByVoltage(v) {
    if (v > 2147483647) return 14
    if (v <= 8) return 0
    const x = v - 1
    const nlz = 64 - x.toString(2).length
    return (62 - nlz) >> 1
}

// ── row → 一行 JS 字面量（单引号，照既有 KJS 风格） ─────────────────────────
function q(s) { return "'" + s + "'" }
function arr(a) { return '[' + a.map(q).join(',') + ']' }
function rowToJs(row) {
    const parts = ['n:' + q(row.n)]
    if (row.nc) parts.push('nc:' + arr(row.nc))
    if (row.a) parts.push('a:' + arr(row.a))
    if (row.c !== undefined) parts.push('c:' + row.c)
    if (row.f) parts.push('f:' + arr(row.f))
    parts.push('o:' + arr(row.o))
    parts.push('d:' + row.d)
    parts.push('e:' + row.e)
    if (row.r) parts.push('r:1')
    return '{' + parts.join(',') + '},'
}

// ── 整份文件 ────────────────────────────────────────────────────────────────
const HEAD_FIXED = `// priority: 2
// =============================================================================
// [server_scripts]shanhai_primordial_forming.js
//   —— 「原初物质定型」gtceu:primordial_matter_forming 的配方（KubeJS 版）
//
// 用户原话（逐字）：
//   「等一下，我突然想起个事，配方不是应该写在kjs里面吗，你新增一个kjs文件，
//     用命名格式，来写原初物质定型的配方，然后你先撤回这次，迁移完让我验证一次」
//
// 🔴 本文件 = 原先【装在 jar 的数据包里】那批配方的【唯一来源】。
//    数据包那边（shanhai-rewrite\\src\\main\\resources\\data\\shanhai\\recipes\\primordial_forming\\）
//    已整目录搬走 ⇒ 游戏里不会出现两份。
//
// ── 数据来源（不是手抄）──────────────────────────────────────────────────────
//   本文件由 kubejs\\_generators\\gen_pf_kjs.js 生成；发射逻辑在
//   kubejs\\_generators\\_pf_kjs_emit.js（与 gen_pf_forming.js 共用同一份）。
//   源 json 的 sha256 与 temp\\pf-fix\\manifest-v3.json 逐条对过（2457/2457 一致）。
//
// ── 每条配方的 id ───────────────────────────────────────────────────────────
//   gtr.primordial_matter_forming('shanhai:<json 文件名去掉 .json>')
//   json 文件名是稳定的（由源配方 id 派生），所以 id 稳定且唯一，不用序号、不用随机数。
//   ⚠️ 运行期 id 形如 shanhai:primordial_matter_forming/<名字>（KJS/GT 会再拼上类型路径，
//      见 [server_scripts]shanhai_recipes.js:2028）。本条【未在游戏里实测】。
//
// ── 槽位记法（数据表）──────────────────────────────────────────────────────
//   n  = 配方名（id 后缀）        o  = 物品输出 'Nx item'
//   nc = 不消耗(notConsumable)    d  = duration
//   a  = 物品输入 'Nx item'/'Nx #tag'  e = EU/t（tickInputs.eu）
//   c  = 编程电路号（0/缺省=没有）      r  = 1 表示要洁净室
//   f  = 流体输入 'id 数量' / '#标签 数量'
//   ⇒ 发射顺序【固定】为 nc → a → c；本批 2457 条的 inputs.item 顺序模式只有
//     'IC' 'C' 'NI' 'I' 'N' '' 六种，这个顺序能逐条复现（生成期已逐条断言）。
//
// ── 两个必须走特殊通道的东西（都是实测逼出来的）────────────────────────────
//   ① 【标签流体】：KubeJS 的字符串写法只认 'id 数量'，不认 '#tag 数量'
//      （FluidIngredientJS.of 的字节码只有三条分支；见 shanhai_lens_goodbye.js 文件头）。
//      ⇒ 走 Java 侧 LensCircuitFluidTags.tagFluid（单一方法、无重载）。
//   ② 【data.euTier】：不在这里写。GTLCore 的 GTRecipeJSMixin 已经把
//      addData("euTier", GTUtil.getTierByVoltage(|EU|)) 注进 EUt(long) 了。
//      生成期已逐条核对：2457/2457 条 json 的 euTier 都等于这个公式 ⇒ 不需要补写。
//
// ── 校验 ───────────────────────────────────────────────────────────────────
//   进游戏后看日志这一行（与 Java 侧 [SHANHAI-NEWTYPE] 的 2457 对账）：
//     [SHANHAI-NEWTYPE] 原初物质定型 ... ok=2457 failed=0 declared=2457
// =============================================================================

// ---- 标签流体：走 Java 侧构造（纯 Java，无 JSON 字符串拼接）----
var PF_TAG_FLUID = null
var PF_TAG_FLUID_MISSING = false
function PF_FLUID(s) {
    if (s.charAt(0) !== '#') return s          // 'id 数量' ⇒ 直接给 KubeJS（实测可用）
    var sp = s.indexOf(' ')
    var tag = s.substring(1, sp)
    var amount = s.substring(sp + 1)
    if (PF_TAG_FLUID_MISSING) throw new Error('标签流体构造器不可用，本条无法建：' + tag)
    try {
        if (PF_TAG_FLUID === null) {
            PF_TAG_FLUID = Java.loadClass('com.shanhai.common.recipe.LensCircuitFluidTags')
        }
        return PF_TAG_FLUID.tagFluid(tag, amount)
    } catch (e) {
        PF_TAG_FLUID_MISSING = true
        throw new Error('标签流体构造失败（jar 里没有 LensCircuitFluidTags？）：' + tag + ' 原因: ' + e)
    }
}

// ---- 洁净室：本批只有 1 条用得上，取不到就让那一条计入 failed ----
var PF_CR = null
function PF_CLEANROOM() {
    if (PF_CR !== null) return PF_CR
    try { PF_CR = CleanroomType.CLEANROOM }
    catch (e1) { PF_CR = Java.loadClass('com.gregtechceu.gtceu.api.machine.multiblock.CleanroomType').CLEANROOM }
    return PF_CR
}

ServerEvents.recipes(function (event) {
    var gtr = event.recipes.gtceu
    var k
    var i
    var ok = 0
    var bad = 0
    var errs = ''
    var DECLARED = ${DECLARED}

    var PF_ROWS = [
`

const TAIL = `    ]

    for (k = 0; k < PF_ROWS.length; k++) {
        var r = PF_ROWS[k]
        try {
            var b = gtr.primordial_matter_forming('shanhai:' + r.n)
            if (r.nc) { for (i = 0; i < r.nc.length; i++) b = b.notConsumable(r.nc[i]) }
            if (r.a) { for (i = 0; i < r.a.length; i++) b = b.itemInputs(r.a[i]) }
            // 🔴 判据是 !== undefined，不是真假：电路号 0 是合法值（本批有 1 条用它）
            if (r.c !== undefined) { b = b.circuit(r.c) }
            if (r.f) { for (i = 0; i < r.f.length; i++) b = b.inputFluids(PF_FLUID(r.f[i])) }
            if (r.o) { for (i = 0; i < r.o.length; i++) b = b.itemOutputs(r.o[i]) }
            if (r.r) { b = b.cleanroom(PF_CLEANROOM()) }
            b.duration(r.d).EUt(r.e)
            ok = ok + 1
        } catch (e) {
            bad = bad + 1
            if (errs.length < 2000) errs = errs + '|' + r.n + ' => ' + e
        }
    }

    console.info('[SHANHAI-NEWTYPE] 原初物质定型 = gtceu:primordial_matter_forming ok=' + ok
        + ' failed=' + bad + ' declared=' + DECLARED + ' 表长=' + PF_ROWS.length
        + (ok + bad === PF_ROWS.length && PF_ROWS.length === DECLARED ? ' ✅' : ' 🔴 对不上'))
    if (bad > 0) console.error('[SHANHAI-NEWTYPE] 原初物质定型 失败明细：' + errs)
})
`

function emitFile(rows, declared) {
    const D = (declared === undefined ? DECLARED : declared)
    if (rows.length !== D) {
        throw new Error('🔴 行数 ' + rows.length + ' ≠ 声明值 ' + D + '（拒绝写产物）')
    }
    const seen = new Set()
    for (const r of rows) {
        if (seen.has(r.n)) throw new Error('🔴 配方名重复：' + r.n)
        seen.add(r.n)
    }
    const body = rows.map(rowToJs).join('\n')
    return HEAD_FIXED.replace('${DECLARED}', String(D)) + body + '\n' + TAIL
}

module.exports = { TYPE, SHORT, DECLARED, NS, jsonToRow, getTierByVoltage, rowToJs, emitFile }
