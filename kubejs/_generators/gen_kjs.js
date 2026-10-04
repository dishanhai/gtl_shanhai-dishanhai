// Generate shanhai_pf_recipes.NEW.js  (old recipes kept verbatim + new ones from PF.txt)
// Discipline: every spec is derived from rows.json (parsed from PF.txt), nothing hand-typed.
'use strict'
var fs = require('fs')
var path = require('path')

// ═══════════════════════════════════════════════════════════════════════════════
// 🔴 路径来源纪律（上传前清理）：本仓库里【不写任何机器绝对路径】。
//    · 仓库【内】的路径 ⇒ 按【脚本自身位置】(__dirname) 推 —— 不依赖"从哪个目录运行"，
//      所以【故意不用 process.cwd()】；
//    · 仓库【外】的路径（PF.txt / 游戏实例）⇒ 从环境变量读；缺了就【响亮抛错并退出】，
//      绝不退化成空目录 / 错路径再继续生成（那会产出"看起来很成功"的错产物）。
// ═══════════════════════════════════════════════════════════════════════════════
var REPO = path.join(__dirname, '..', '..')          // kubejs\_generators → 仓库根
function envPath(name, what, example) {
    var v = process.env[name]
    if (v === undefined || String(v).trim() === '') {
        throw new Error('🔴 缺少环境变量 ' + name + '（' + what + '）\n'
            + '   ⇒ 请先设置它，例如（PowerShell）：$env:' + name + " = '" + example + "'\n"
            + '   ⇒ 本脚本【拒绝】在缺少它的前提下继续运行：那会拿错路径、静默产出错产物。')
    }
    return String(v).trim()
}
var INSTANCE = envPath('SH_INSTANCE', '游戏实例的【根目录】（其下有 mods\\ 与 local\\kubejs\\export\\）',
    'D:\\Minecraft\\versions\\<你的实例目录名>')
var PF_SRC = envPath('SH_PF_SRC', 'PF.txt（AE2 样板导出的 NBT 文本）的绝对路径',
    'D:\\path\\to\\PF.txt')
// ═══════════════════════════════════════════════════════════════════════════════
var BASE = path.join(REPO, 'recipe-convert') + path.sep
var rows = JSON.parse(fs.readFileSync(BASE + 'rows.json', 'utf8'))

// ═══════════════════════════════════════════════════════════════════════════════
// 🔴 2026-09-29 过期防线 —— 【引用方】自检：我读的中间产物是不是旧的？
// ═══════════════════════════════════════════════════════════════════════════════
//   事故形态（2026-09-29 真实发生过一次，是另一个子代理踩的）：PF.txt 换了一版，
//   但只重跑了后半段 ⇒ 产物是「新 PF.txt 的皮、旧中间产物的肉」，而没有任何一处会响。
//   ⇒ 所以本脚本读进来第一件事就是比对源头指纹，不一致就**直接抛错，拒绝写产物**。
//   ⚠️ rows.json 在磁盘上是【裸数组】（形状不能改 —— 12 处以上消费者按裸数组解析），
//      它的源头声明在 `recipe-convert\_provenance.json`，且登记里绑定了 rows.json 自己的 sha256。
var PROV = require('./provenance.js')
PROV.check(BASE + 'rows.json', 'gen_kjs.js(读 rows.json)')

// 现算"游戏导出表里某类型有几个配方文件"——原来这里是写死的 18 / 90（会过期）。
// 读不到就返回 null，由调用处如实写"读不到"，绝不编一个数字。
var EXPORT_RECIPES = path.join(INSTANCE, 'local', 'kubejs', 'export', 'recipes', 'gtceu') + path.sep
function exportRecipeFileCount(typeId) {
    try { return fs.readdirSync(EXPORT_RECIPES + typeId).filter(function (f) { return /\.json$/.test(f) }).length }
    catch (e) { return null }
}
function exportCountText(typeId) {
    var n = exportRecipeFileCount(typeId)
    return n === null ? '？' : String(n)
}
// 🔴 2026-09-29：电压真值（V[MAX] / MAX+8 星门 EUt / 元件名⇒档位）改为引用【唯一真源】模块，
//    不再在本文件里写死 8 / 32 / 128 / 140737488355328 这些数字。
//    gen_manifest.js 引的是**同一个**模块 ⇒ 两边不可能再各写一份、再各错一份（旧 gen_manifest.js
//    的本地电压表把 MAX 写成 2147483647，与这里的 2147483648 不一致，就是这么来的）。
//    模块自带自检（VN 长度 / VN[14]==='MAX' / 每档 = 上一档 ×4 / 星门 = 2^47 / 4^16 口径恰好越界）。
var GT_VOLTAGE = require('./gt_voltage.js')

// ═══════════════════════════════════════════════════════════════════════════════
// IO caps —— 🔴 2026-09-29 重做：CAP 表【不再写死】，改为【双真源 · 现读现算 · 双向硬拦】
// ═══════════════════════════════════════════════════════════════════════════════
// 为什么推翻了"写死一张表"这个做法（本次实测到的两个真错，不是我猜的）：
//   ① 【缺键】`primordial_matter_recombination` 不在旧表里（旧表 12 个键，Java 活代码 41 个）
//      ⇒ 它回落到兜底 [99,99,99,99] ⇒ **19 条「原初物质重组」配方的槽位自检形同虚设**。
//      实测口径：rows.json 里该类型 19 条（逐条列在 temp\cap-audit\analyze.out.txt）。
//   ② 【过期值】`worldline_oscillation_collection` 旧表写 [16,1,4,0]，而 Java 活代码
//      在 2026-09-29 已改成 (16,2,4,2)（只动输出两个数：物品出 1→2、流体出 0→2）
//      ⇒ **写死的副本比源码旧了一天**，而自检照旧用旧值、不会响。
//   ⇒ 结论：写死的副本一定会过期，加了断言也只是"过期时响一声"。真值只能有一个。
//
// ── 真源①（shanhai 自己注册的类型，41 个）──────────────────────────────────────
//   文件：shanhai-rewrite\src\main\java\com\shanhai\common\recipe\ShanhaiRecipeTypes.java
//   取法：只取【活代码】里的 `.setMaxIOSize(a,b,c,d)`（行首去空白后不是 `//`、`*`、`/*`）。
//   ⚠️ 该文件里【有大量注释掉的作废块】（约 L1027-1268）—— 实测活 41 条 / 死 24 条；
//      若用"全文正则"抓会连死块一起抓，那正是"抄了作废值"的老事故形态。
//   自证两条（都在下面代码里）：①活代码调用条数必须 == **同文件现读的** `REAL_TYPE_COUNT` 常量；
//                              ②每个 id 只许出现一次（重复即抛）。
//
// ── 真源②（gtceu 原生类型）───────────────────────────────────────────────────
//   文件：recipe-convert\javap\GTRecipeTypes.txt = 已部署 gtceu jar 的 `javap -p -c` 转储。
//   取法：按 `register("id","category")` 之后紧邻的 4 个 push 操作数还原（iconst_*/bipush/sipush）。
//   ⚠️ **这个转储是 UTF-16LE（带 BOM）** —— 按 utf8 读会【静默匹配到 0 条且不报错】。
//      本次实测就踩了：第一版解析器按 utf8 读，结果"解析出 0 个类型"而没有任何异常。
//      ⇒ 读之前必须先判 BOM（见 readTextSmart）。
//   自证（正面对照，证明解析器不是自说自话）：
//      circuit_assembler = [6,1,1,0] ／ primitive_blast_furnace = [3,3,0,0]（人工从字节码核对过）
//      assembler         = [9,1,1,0]  ← 与 ShanhaiRecipeTypes.java L560 注释里**手抄**的
//                                        组装机规格独立吻合（一个是注释、一个是另一份字节码）。
//   ⚠️ **为什么 gtceu 那两个不能从真源①拿**：它们是 gtceu 自己注册的，shanhai 的源文件里
//      根本没有它们 ⇒ 真源②是必需的，不是冗余。
//   ⚠️ 真源②是**快照**（09-26 的转储），会落后于真源①（活源码）。实测：真源②只有 40 个
//      shanhai 类型、且没有 `primordial_matter_deconstruction`（那是第 41 个）
//      ⇒ **绝不能拿真源②当 shanhai 类型的真值**，它只用来给 gtceu 原生类型供数。
//
// ── CAP = 真源①(全部 41) ∪ 真源②(只取 TYPE_ID 实际用到的 gtceu 原生) ──────────
//   历史留档（现在只是注释，不再是数据）：photon_separation 由用户 2026-09-26 裁决从
//   (2,4,2,2) 放宽到 **(4,10,2,2)**（物品入 2→4、物品出 4→10、流体 2/2 不动）；
//   出处是 javap -c 的 iconst_4 / bipush 10 / iconst_2 / iconst_2。这条例外已经被
//   写进 Java 源码本身 ⇒ 现读现算自然拿到，**不需要**在这里再留一份副本。
var ROOT = REPO + path.sep      // 仓库根（末尾带分隔符 —— 下面按 ROOT + 'xxx' 拼接）
var JAVA_TYPES = ROOT + 'shanhai-rewrite\\src\\main\\java\\com\\shanhai\\common\\recipe\\ShanhaiRecipeTypes.java'
var GT_JAVAP = ROOT + 'recipe-convert\\javap\\GTRecipeTypes.txt'

/** 按 BOM 判编码读取（这两个 javap 转储是 UTF-16LE；按 utf8 读会静默出 0 条）。 */
function readTextSmart(p) {
    var buf = fs.readFileSync(p)
    if (buf.length >= 2 && buf[0] === 0xFF && buf[1] === 0xFE) return buf.slice(2).toString('utf16le')
    if (buf.length >= 3 && buf[0] === 0xEF && buf[1] === 0xBB && buf[2] === 0xBF) return buf.slice(3).toString('utf8')
    return buf.toString('utf8')
}
function isCommentLine(line) {
    var t = line.replace(/^\s+/, '')
    return t.indexOf('//') === 0 || t.indexOf('*') === 0 || t.indexOf('/*') === 0
}

/** 真源①：ShanhaiRecipeTypes.java 的【活代码】setMaxIOSize + REAL_TYPE_COUNT。 */
function parseShanhaiCaps(p) {
    var lines = fs.readFileSync(p, 'utf8').split(/\r?\n/)   // 源文件是 UTF-8（不是转储）
    var live = [], dead = 0, realTypeCount = null, dup = []
    // 🔴 2026-10-03 修（正则回归）：本行原来是写死的 /GTRecipeTypes\.register\(\s*"([a-z0-9_]+)"\s*,/。
    //    ShanhaiRecipeTypes.java 于 2026-10-03 11:08 把注册入口换成了**本类自己的包装方法**
    //    （L550 `private static GTRecipeType register(String name, String category, RecipeType<?>... proxyRecipes)`，
    //      L551 内部才转交 `GTRecipeTypes.register(name, category, proxyRecipes)`），
    //    注册点写法因此从 `X = GTRecipeTypes.register("id", "multiblock")` 变成 `X = register("id", "multiblock")`
    //    ⇒ 写死类名的那条正则**静默失配 ⇒ 0 条** ⇒ G1 抛「活代码 register 条数 = 0 ≠ setMaxIOSize 条数 = 45」。
    //    ⇒ 新判据**同时**认两种形态：① 本类包装 register("id", …)；② 原始 GTRecipeTypes.register("id", …)。
    //      两种形态必须都认 —— 否则谁把写法改回原形态，这里又会静默失配一次（同一类 bug 复发）。
    //    ⚠️ 为什么放宽前缀不会误伤包装方法自己的那两行（照 L550/L551 实文写的，不是猜的）：
    //      · L550 定义 `register(String name, …)` —— 括号后紧跟的是标识符 `String`，不是 `"` ⇒ 不匹配；
    //      · L551 转交 `GTRecipeTypes.register(name, category, …)` —— 同样括号后无引号 ⇒ 不匹配；
    //      · 注释作废块（L1446+）里的 `// … GTRecipeTypes.register("…", …)` —— 由 isCommentLine 挡掉，不进 regs。
    //      ⇒ 判据「register( 紧跟一个带引号的 id」只可能落在**调用点**上。
    var regRe = /(?:GTRecipeTypes\.)?\bregister\(\s*"([a-z0-9_]+)"\s*,/
    var maxRe = /\.setMaxIOSize\(\s*(-?\d+)\s*,\s*(-?\d+)\s*,\s*(-?\d+)\s*,\s*(-?\d+)\s*\)/
    var lastReg = null, regs = 0
    var caps = {}, where = {}
    for (var i = 0; i < lines.length; i++) {
        var t = lines[i].replace(/^\s+/, '')
        var comment = isCommentLine(lines[i])
        if (!comment) {
            var mrc = /public\s+static\s+final\s+int\s+REAL_TYPE_COUNT\s*=\s*(\d+)/.exec(t)
            if (mrc) realTypeCount = parseInt(mrc[1], 10)
            var mr = regRe.exec(t)
            if (mr) { lastReg = mr[1]; regs++ }
        }
        var mm = maxRe.exec(t)
        if (!mm) continue
        if (comment) { dead++; continue }
        live.push({ line: i + 1, id: lastReg })
        var tuple = [parseInt(mm[1], 10), parseInt(mm[2], 10), parseInt(mm[3], 10), parseInt(mm[4], 10)]
        if (caps[lastReg]) dup.push(lastReg)
        caps[lastReg] = tuple
        where[lastReg] = i + 1
    }
    return { caps: caps, where: where, liveCount: live.length, deadCount: dead, dup: dup, realTypeCount: realTypeCount, regs: regs }
}

/** 真源②：gtceu 的 javap -c 转储 ⇒ id ⇒ (a,b,c,d)。 */
function parseJavapCaps(p) {
    var lines = readTextSmart(p).split(/\r?\n/)
    var caps = {}, problems = []
    for (var i = 0; i < lines.length; i++) {
        var t = lines[i].replace(/^\s+/, '')
        if (!/register:\(Ljava\/lang\/String;Ljava\/lang\/String;/.test(t)) continue
        var strs = []
        // 🔴 2026-09-30：回看窗口 **6 → 25 行**。原来 6 行会漏掉【参数离 register 较远】的那一条：
        //    实测 `electric_furnace`（中文名「电炉」）的两条 String 在 register 之前 7、8 行
        //    （它中间还夹着 `anewarray RecipeType` + `iconst_0` + `getstatic RecipeType.f_44108_` + `aastore`
        //      这四行，把两条 String 顶出了 6 行窗口）⇒ 真实原生类型数 57 而不是 58。
        //    后果不是"少一个数字"，而是 **「电炉」这个名字反查不到** ⇒ 纸上写它会被误判成笔误。
        //    ⇒ 为什么放大是安全的：register 的两个 String 参数**必然**紧邻在它的数组构造之前，
        //      因此"从 register 往前最近的 2 条 String"永远是它自己的参数，不可能取到上一条注册的。
        //      多留窗口只会救回被顶出去的那一条，不会改变已有的任何一条。
        for (var k = i - 1; k >= Math.max(0, i - 25) && strs.length < 2; k--) {
            var ms = /\/\/ String ([A-Za-z0-9_]+)\s*$/.exec(lines[k].replace(/^\s+/, ''))
            if (ms) strs.push(ms[1])
        }
        if (strs.length < 2) { problems.push('L' + (i + 1) + ': register 前找不到两条 String'); continue }
        var id = strs[1]
        var pushes = [], found = false
        for (var k2 = i + 1; k2 < Math.min(lines.length, i + 20); k2++) {
            var s = lines[k2].replace(/^\s+/, '')
            var mo = /^\d+:\s+(\S+)\s*(.*)$/.exec(s)
            if (!mo) continue
            var op = mo[1], rest = mo[2]
            if (/setMaxIOSize:\(IIII\)/.test(s)) { found = true; break }
            if (/invokevirtual|invokestatic|getstatic|putstatic|anewarray|^new$|checkcast/.test(op)) continue
            // 🔴 `v` 必须【每轮显式清空】。写 `var v` 是不够的（var 是函数作用域、不会重置）：
            //    实测这个漏写法让 `bipush 6` 那一位继承了上一轮的 0 ⇒ circuit_assembler 被算成
            //    [0,1,1,0] 而不是 [6,1,1,0]（下限被低估 ⇒ 自检会漏报溢出）。
            //    是下面的【正面对照断言】当场抓到的，不是靠人看。
            var v = undefined
            if (/^iconst_m1$/.test(op)) v = -1
            else { var m1 = /^iconst_([0-5])$/.exec(op); if (m1) v = parseInt(m1[1], 10) }
            if (v === undefined) { var m2 = /^bipush\s+(-?\d+)$/.exec(op + ' ' + rest); if (m2) v = parseInt(m2[1], 10) }
            if (v === undefined) { var m3 = /^sipush\s+(-?\d+)$/.exec(op + ' ' + rest); if (m3) v = parseInt(m3[1], 10) }
            if (v === undefined) { problems.push('L' + (k2 + 1) + ' ' + id + ': 认不出的操作数 `' + s.trim() + '`'); break }
            pushes.push(v)
        }
        if (!found) { problems.push('L' + (i + 1) + ' ' + id + ': 之后找不到 setMaxIOSize'); continue }
        if (pushes.length !== 4) { problems.push(id + ': 操作数个数 = ' + pushes.length + '（不是 4）'); continue }
        caps[id] = pushes
    }
    return { caps: caps, problems: problems }
}

var SH_JAVA = parseShanhaiCaps(JAVA_TYPES)
var GT_BYTECODE = parseJavapCaps(GT_JAVAP)

// ── 硬拦 G1：真源①的【解析完整性】─────────────────────────────────────────────
// 检的是"解析器自己有没有坏"，不是"人有没有记得同步"（后者已被现读现算消灭）。
var _g1 = []
if (SH_JAVA.realTypeCount === null) _g1.push('读不到 `REAL_TYPE_COUNT` 常量')
else if (SH_JAVA.liveCount !== SH_JAVA.realTypeCount) _g1.push('活代码 setMaxIOSize 条数 = ' + SH_JAVA.liveCount
    + '，但同文件的 REAL_TYPE_COUNT = ' + SH_JAVA.realTypeCount)
if (SH_JAVA.regs !== SH_JAVA.liveCount) _g1.push('活代码 register 条数 = ' + SH_JAVA.regs + ' ≠ setMaxIOSize 条数 = ' + SH_JAVA.liveCount)
if (SH_JAVA.dup.length) _g1.push('重复注册的 id：' + SH_JAVA.dup.join(' / '))
if (SH_JAVA.deadCount === 0) _g1.push('注释块里一条 setMaxIOSize 都没有（L1027-1268 的作废块不见了？解析口径可能已经失效）')
if (_g1.length) {
    throw new Error('[PF] 🔴 真源①（ShanhaiRecipeTypes.java 活代码）解析自检失败，拒绝写产物：\n    · '
        + _g1.join('\n    · ')
        + '\n    文件：' + JAVA_TYPES
        + '\n    ⇒ 修法：确认该文件仍是【活代码 + 注释作废块】的结构，且活代码里每条 register 后恰好一条 setMaxIOSize。')
}
// ── 硬拦 G0：两个解析器的【正面对照】─────────────────────────────────────────
// 🔴 这是"证明检查器自己是对的"那一步，不是可选的打印。**人工独立核对过**的已知答案：
//   · gtceu `circuit_assembler` = (6,1,1,0)、`primitive_blast_furnace` = (3,3,0,0)
//     —— 直接读 GTRecipeTypes.txt 的 2308/3028 一带字节码得到（见本次交付报告）。
//   · gtceu `assembler` = (9,1,1,0)
//     —— 与 ShanhaiRecipeTypes.java L558-563 注释里**手抄**的组装机规格独立吻合：
//        一边是注释、一边是另一份字节码，两边不共享来源 ⇒ 能互相印证。
//   · shanhai `photon_separation` = (4,10,2,2)
//     —— 用户 2026-09-26 裁决原文就把这四个数写出来了；它必须能从源码里读出来。
//   ⇒ 任一条不符就抛：说明解析器坏了、或真值被人改过而这里没跟上。
var _ctrls = [
    ['真源②', GT_BYTECODE.caps, 'circuit_assembler', [6, 1, 1, 0]],
    ['真源②', GT_BYTECODE.caps, 'primitive_blast_furnace', [3, 3, 0, 0]],
    ['真源②', GT_BYTECODE.caps, 'assembler', [9, 1, 1, 0]],
    // 🔴 2026-09-30 补：这条正是"回看 6 行会漏掉"的那一条。它的四个数直接读 GTRecipeTypes.txt：
    //    偏移 93/94/95/96 = iconst_1 / iconst_1 / iconst_0 / iconst_0 → setMaxIOSize ⇒ (1,1,0,0)。
    //    加进对照表的意义：把回看窗口改回 6 行，这里会立刻 ❌（而不是静默少一个类型）。
    ['真源②', GT_BYTECODE.caps, 'electric_furnace', [1, 1, 0, 0]],
    ['真源①', SH_JAVA.caps, 'photon_separation', [4, 10, 2, 2]],
    ['真源①', SH_JAVA.caps, 'worldline_oscillation_collection', [16, 2, 4, 2]]
]
var _g0 = []
for (var _c = 0; _c < _ctrls.length; _c++) {
    var _csrc = _ctrls[_c][0], _cmap = _ctrls[_c][1], _cid = _ctrls[_c][2], _want = _ctrls[_c][3]
    var _got = _cmap[_cid]
    if (!_got) { _g0.push(_csrc + ' 里读不到 `' + _cid + '`（期望 ' + JSON.stringify(_want) + '）'); continue }
    if (_got.join(',') !== _want.join(',')) _g0.push(_csrc + ' `' + _cid + '` 读出来 = ' + JSON.stringify(_got)
        + '，人工核对过的真值 = ' + JSON.stringify(_want))
}
if (_g0.length) {
    throw new Error('[PF] 🔴 上限解析器【正面对照】失败，拒绝写产物（说明解析器坏了，或真值被改过而这里没跟上）：\n    · '
        + _g0.join('\n    · ')
        + '\n    ⇒ 注意：`worldline_oscillation_collection` 的 (16,2,4,2) 是 2026-09-29 的用户裁决；'
        + '\n       若这次改动是有意的，请【同时】更新本节 _ctrls 里的期望值，不要只改源码。\n')
}
// 🔴 2026-10-03 补：把 G1 那条判据的两个数【在通过时也打出来】。
//   原样只在失败时于 throw 的消息里出现（'活代码 register 条数 = N ≠ setMaxIOSize 条数 = M'），
//   于是"改好了"这件事在 stdout 上没有读数可查 —— 而 2026-10-03 的正则回归恰恰就是这两个数失配。
//   ⇒ 通过时也报数，回归下次一跑就能从 stdout 直接看见（不需要去看代码或去数）。
//   ⚠️ 只加打印，不改判据：判据仍是下面 _g1 里的 `SH_JAVA.regs !== SH_JAVA.liveCount`。
console.log('[PF] 真源① ShanhaiRecipeTypes.java：活代码 setMaxIOSize = ' + SH_JAVA.liveCount
    + ' 条（= REAL_TYPE_COUNT ' + SH_JAVA.realTypeCount + '）／注释作废块里 ' + SH_JAVA.deadCount + ' 条（已剔除）'
    + '／活代码 register 条数 = ' + SH_JAVA.regs
    + (SH_JAVA.regs === SH_JAVA.liveCount ? ' ⇒ = setMaxIOSize 条数 ' + SH_JAVA.liveCount + ' ✓' : ' 🔴 ≠ setMaxIOSize 条数 ' + SH_JAVA.liveCount))
console.log('[PF] 真源② GTRecipeTypes.txt（javap 转储）：解析出 ' + Object.keys(GT_BYTECODE.caps).length
    + ' 个 gtceu 原生类型（正面对照 circuit_assembler=' + JSON.stringify(GT_BYTECODE.caps['circuit_assembler'])
    + ' primitive_blast_furnace=' + JSON.stringify(GT_BYTECODE.caps['primitive_blast_furnace']) + '）')
var TYPE_ID = {
    '电路组装机': 'circuit_assembler',
    '土高炉': 'primitive_blast_furnace',
    '量子化现实重构': 'spacetime_distortion',
    '原初奇点反演': 'primordial_singularity_inversion',
    '物质流凝结': 'matter_flow_condensation',
    '物质模块铸造': 'matter_module_casting',
    '光子虹吸': 'photon_siphon',
    '光子分离': 'photon_separation',
    // 🔴 2026-09-26 补：TYPE_ID 原本【漏了】这个键，导致 5 行（no=20/41/43/48/51）被误判成解析不出类型而跳过。
    //    GT id 由 SNAP-1 导出证实为 gtceu:primordial_matter_recombination。
    '原初物质重组': 'primordial_matter_recombination',
    '星际物质吸取': 'interstellar_matter_absorption',
    // ✅ 用户 2026-09-26 亲自确认：纸上「世线电路板组装」是【笔误】⇒ 按 lang 的
    //    `gtceu.wl_board_circuit_assembly`（世线板电路组装）落。原 ⚠️ 标记已按用户裁决移除。
    '世线电路板组装': 'wl_board_circuit_assembly',
    // 🔴 2026-09-28 补两个键。此前它们【不在表里】⇒ specFromRow 返回 null ⇒ 整条被 SKIPPED ⇒ **配方根本不存在**。
    //    这不是"我推断的"，是跑 gen_kjs.js 实测到的：改动前那一次的 SKIPPED 名单里就有 no=64 / no=68
    //    （"因解析不出配方类型而【跳过】的行 = 14 条"）。
    //    gt id 出处：recipe-convert\lang\shanhai_zh_cn.json 的
    //      `"gtceu.worldline_oscillation_collection": "世线震荡收集"`（L54，与纸上【逐字相等】）
    //      `"gtceu.worldline_sampling": "世线采样"`（L84，与纸上【逐字相等】）
    //    注册出处：ShanhaiRecipeTypes.java L447 / L721 各自的 GTRecipeTypes.register(...)。
    '世线震荡收集': 'worldline_oscillation_collection',
    '世线采样': 'worldline_sampling'
}

// ═══════════════════════════════════════════════════════════════════════════════
// 🔴🔴 2026-09-30 语言文件【自动反查】—— 让上面的手写表【降级成别名/覆盖表】
// ═══════════════════════════════════════════════════════════════════════════════
// 用户 2026-09-30 原话（逐字）：「就用语言文件里面那样做吧，正好可以检查我写错字」
//   ⇒ 两件事一起做：① 写新类型不用再找人手动加键 ② **写错的时候要告诉他**
//
// 为什么必须做（不是"锦上添花"，是已经连续扎了两刀）：
//   · 2026-09-28：`世线震荡收集` / `世线采样` 两个键漏了 ⇒ 2 条配方从游戏里消失；
//   · 2026-09-30：用户在 PF.txt 里写了「原初世线切割」—— 这个名字**语言文件里本来就有**
//     （`gtceu.worldline_cutting` @ shanhai-0.1.0.jar），只是上面那张手写表没加键
//     ⇒ 本条配方【从游戏里消失】，而用户那边看到的是"配方没生效"。
//     实测证据：`recipe-convert\rows.json` 第 67 条（元件「处理样板MV」、产出 1x shanhai:thread_shard_1）
//     ⇒ 改动前那次运行会以"纸上写了配方类型、而 TYPE_ID 表里没有这个键"抛错、拒绝写产物。
//
// 三档行为（第 2、3 档就是他要的"检查我写错字"）：
//   ① **反查命中**（= 语言文件里有这个中文名）⇒ 正常跑（静默）；
//   ② **只有手写别名表认得**（= 他写的是历史笔误，或手写覆盖）⇒ 正常跑，但打一行提示告诉他真名；
//   ③ **两处都查不到** ⇒ 抛错、拒绝写产物，并且【给出最近三个候选】——
//      候选按**中文字符**的编辑距离算，且**全部取自反查表** ⇒ 给出来的名字一定是能用的。
//
// 反查表怎么建（细节、逐条 jar 出处与自证都在 kubejs\_generators\type_names.js 的文件头）：
//   · 中文名：【现读 jar】，不读任何转抄件。`gtceu.*` 的键分散在**多个** jar 里，实测：
//       gtceu-1.20.1-1.4.4.jar         !assets/gtceu/lang/zh_cn.json      60 个单段 gtceu.* 键
//       gtlcore-1.2.3.2.jar            !assets/gtceu/lang/zh_cn.json      84 个
//       gtladditions-3.2.8Custom-fix1.jar !assets/gtceu/lang/zh_cn.json    24 个
//       shanhai-0.1.0.jar              !assets/shanhai/lang/zh_cn.json    41 个（键名仍是 gtceu.*）
//     ⚠️ 只读 gtceu 本体那一个 jar 会漏掉另外三个 —— 那正是"抄一份"必犯的错。
//   · id 集合（三源并集，**不另立一套**）：
//       ① ShanhaiRecipeTypes.java 活代码的 41 条 register（本文件上面已经现读出来了）
//       ② recipe-convert\javap\GTRecipeTypes.txt（gtceu 本体 javap 转储）
//       ③ 游戏自己导出的 `local\kubejs\export\recipes\` 下的**类型目录名**
//          （⇒ 这一源覆盖了"别的 mod 注册在 `gtceu:` 命名空间下"的类型：实测多出 100+ 个，
//            没有它，写「聚合装置」这种**正确**名字会被误判成笔误）
//   · **受限**：只在 id 集合上建表 ⇒ 实测 0 个撞名。
//     （不限的话，全量 gtceu.* 有 1838 个中文名、其中 115 个撞多键——「销毁模式」「物品」「输入」
//       「输出」这些 GUI/提示串也在里面 ⇒ 那样建表会给出错误的候选。）
//   · **构建一次、全进程复用**：实测 90 个 jar 全扫一遍 **0.25 秒**（只解 lang 条目）
//     ⇒ 绝不"每行配方重扫 jar"。所以这里【故意不做磁盘缓存】：0.25 秒不值得引入"缓存过期"这种新故障。
var MODS_DIR = path.join(INSTANCE, 'mods')
var EXPORT_RECIPES_ROOT = path.join(INSTANCE, 'local', 'kubejs', 'export', 'recipes')
var TN = require('./type_names.js')
// 手写表的【快照】：注入反查结果之后 TYPE_ID 会变大，判"第 2 档"必须用注入前那 13 个键。
var TYPE_ID_ALIAS = {}
for (var _ak in TYPE_ID) if (Object.prototype.hasOwnProperty.call(TYPE_ID, _ak)) TYPE_ID_ALIAS[_ak] = TYPE_ID[_ak]
var _tnIds1 = [], _tnIds2 = []
for (var _ik1 in SH_JAVA.caps) if (Object.prototype.hasOwnProperty.call(SH_JAVA.caps, _ik1)) _tnIds1.push(_ik1)
for (var _ik2 in GT_BYTECODE.caps) if (Object.prototype.hasOwnProperty.call(GT_BYTECODE.caps, _ik2)) _tnIds2.push(_ik2)
var TN_RES = TN.buildIndex({
    modsDir: MODS_DIR,
    exportRecipesDir: EXPORT_RECIPES_ROOT,
    idSets: { '①ShanhaiRecipeTypes.java': _tnIds1, '②GTRecipeTypes.txt(javap)': _tnIds2 }
})
var TYPE_REVERSE = TN_RES.table

// ── 硬拦 G6：反查器【自己的】正/负面对照 ────────────────────────────────────────
// 🔴 这是本工程吃过 7 次亏的那条规矩：检查器自己的假设错了 ⇒ 它的结论全错（PASS 也不可信）。
//   所以：先证明它认得**已知为真**的名字，再信任它报的"查不到"。
var _g6 = []
var _g6pos = [['电路组装机', 'circuit_assembler'], ['土高炉', 'primitive_blast_furnace'],
    ['光子分离', 'photon_separation'], ['原初世线切割', 'worldline_cutting']]
for (var _p6 = 0; _p6 < _g6pos.length; _p6++) {
    var _got6 = TYPE_REVERSE[_g6pos[_p6][0]]
    if (_got6 !== _g6pos[_p6][1]) _g6.push('正面对照「' + _g6pos[_p6][0] + '」应 ⇒ ' + _g6pos[_p6][1] + '，实测 ⇒ ' + JSON.stringify(_got6 || null))
}
var _g6neg = ['销毁模式', '物品', '无']
for (var _n6 = 0; _n6 < _g6neg.length; _n6++) if (TYPE_REVERSE[_g6neg[_n6]]) _g6.push('负面对照「' + _g6neg[_n6] + '」**不该**出现在反查表里，实测 ⇒ ' + TYPE_REVERSE[_g6neg[_n6]])
if (TN_RES.meta.tableSize === 0) _g6.push('反查表是【空的】—— 多半是 mods 目录读不到（' + MODS_DIR + '）；**这会静默把每个类型名都判成笔误**')
if (TN_RES.collisions.length) _g6.push('反查表里有 ' + TN_RES.collisions.length + ' 个中文名撞多个 id（无法唯一反查）：'
    + TN_RES.collisions.map(function (c) { return '「' + c.name + '」⇒ ' + c.ids.join(',') }).join(' ／ '))
if (_g6.length) {
    throw new Error('[PF] 🔴 语言文件反查器【自检失败】，拒绝写产物（否则每个类型名都可能被误判成笔误）：\n    · '
        + _g6.join('\n    · ')
        + '\n    ⇒ 反查器：' + ROOT + 'kubejs\\_generators\\type_names.js'
        + '\n    ⇒ 它现读：' + MODS_DIR + '（jar 内的 assets/*/lang/zh_cn.json）＋ ' + EXPORT_RECIPES_ROOT)
}
console.info('[PF] ✅ G6 语言文件反查表 = ' + TN_RES.meta.tableSize + ' 个中文名（撞名 ' + TN_RES.collisions.length
    + ' ／ id 并集 ' + TN_RES.meta.idUnion + ' 个、其中 ' + TN_RES.meta.idWithLangName + ' 个有中文名）'
    + '；现读 ' + TN_RES.meta.jarCount + ' 个 jar 的 ' + TN_RES.meta.langEntryCount + ' 份 zh_cn，耗时 ' + TN_RES.meta.seconds + ' 秒')
console.info('[PF] ✅ G6 正面对照 4/4（电路组装机⇒circuit_assembler ／ 土高炉⇒primitive_blast_furnace ／'
    + ' 光子分离⇒photon_separation ／ 原初世线切割⇒worldline_cutting）；负面对照 3/3（「销毁模式」「物品」「无」都不在表里）')
console.info('[PF] ✅ G6 反查表的 lang 逐条出处：' + TN_RES.meta.sources.map(function (s) { return s.jar + '!' + s.entry + '=' + s.gtceuKeys + ' 键' }).join('　'))
if (TN_RES.meta.skipped.length) {
    console.info('[PF] ⚠️ G6 读不了的 jar/lang（如实列出，不静默）：'
        + TN_RES.meta.skipped.map(function (s) { return (s.jar || '') + (s.entry ? '!' + s.entry : '') + '（' + s.why + '）' }).join('　'))
}
// 反查命中的名字【补进 TYPE_ID】—— 手写表优先，绝不被覆盖。
//   ⚠️ 这样后面所有判据（specFromRow ／ _cellsNeedingEut ／ CAP 的 _usedGtIds ／ 产物 §2 映射表）
//      **一行都不用改**就自动走新口径 ⇒ 本次改动对"配方数据"是**零影响**（下面第三节 A/B 用机器判据证明）。
var _tnInjected = 0
for (var _rn in TYPE_REVERSE) if (Object.prototype.hasOwnProperty.call(TYPE_REVERSE, _rn)) {
    if (!Object.prototype.hasOwnProperty.call(TYPE_ID, _rn)) { TYPE_ID[_rn] = TYPE_REVERSE[_rn]; _tnInjected++ }
}
console.info('[PF] ✅ G6 已把语言文件里的 ' + _tnInjected + ' 个中文名补进类型判定（手写的 ' + Object.keys(TYPE_ID_ALIAS).length
    + ' 个键优先，一个都没被覆盖）⇒ 纸上写这些名字都能认出来，不必再找人加键')
var _tnNoName = _tnIds1.filter(function (id) { return !TN_RES.nameById[id] })
if (_tnNoName.length) {
    console.info('[PF] ⚠️ 这 ' + _tnNoName.length + ' 个 shanhai 真类型在语言文件里【没有中文名】⇒ 纸上没法写它们'
        + '（不是本次错误，是待补的 lang 键）：' + _tnNoName.join(' ／ '))
}
/** 第 3 档用：在反查表里找最接近的 3 个候选（编辑距离按中文字符算）。 */
function tnCandidates(name) { return TN_RES.topCandidates(name, 3) }

// ═══════════════════════════════════════════════════════════════════════════════
// 🔴🔴 2026-09-30 硬断言：纸上写的配方类型名 ⇄ TYPE_ID 表【双向差集】
// ═══════════════════════════════════════════════════════════════════════════════
// 为什么必须做成断言（不许只靠日志）：
//   缺键 ⇒ `specFromRow` 返回 null ⇒ **整条样板被跳过 ⇒ 游戏里根本没有这条配方**。
//   2026-09-28 真出过一次（`世线震荡收集` / `世线采样` 两个键漏了）；同族还有 `原初物质重组`、
//   `CELL_EUT` 的 `处理样板MV`（后者是部署后运行期炸了 8 条）。当时就说好要做成生成期硬断言。
//
// 判据必须分成【三类】，因为"跳过"这个词混着两种完全不同的东西：
//   ① `cell=合成样板` 且纸上没写类型 ⇒ 走 `event.shaped` **工作台**路径 ⇒ **正常分流**，不是丢条；
//   ② 纸上写了类型名、而【手写别名表】与【语言文件反查表】**两处都查不到** ⇒ **真·丢配方**
//      ⇒ 本断言【抛错、拒绝写产物】，并给出最近三个候选（2026-09-30 起）；
//      ⚠️ 2026-09-30 之前这里只查手写表 ⇒ 写过「原初世线切割」（lang 里本来就有）也会被判成缺键。
//   ③ 🔴 本次【新发现】的第三条通道（连 skip 日志都进不去，比②更隐蔽）：
//      纸上写的类型名**没有被识别成"类型纸"**（写错一个字 / 不在 lang 里 / 忘了写「配方类型：」前缀）
//      ⇒ 该行解析出来 `type = null` ⇒ 它会被当成"没有类型的行"和①混在一起，
//      而工作台那条路要求 `cell === '合成样板'` ⇒ 它若在【处理样板XX】里
//      ⇒ 既不是 GT 配方、也不是工作台配方 ⇒ **静默消失**，而旧日志还会替它声明"不是丢条"。
//      ⇒ 所以③：`type` 为空 且 元件不是「合成样板」⇒ 同样抛错。
//
// ⚠️ 老配方（rows[].old，= 产物里【逐字搬过去】的那 8 条）不参与本断言：
//    它们的文本是硬编码在产物里的，缺键不会让它们消失 ⇒ 拿它报"丢配方"会是【假警报】。
//    （它们若真出问题，由 gen_manifest.js 的 `OLD_EXPECTED = 8` 断言兜。）
function tdBrief(list) {
    var a = []
    for (var i = 0; i < (list || []).length; i++) {
        var d = list[i].d
        if (!d) continue
        if (d.kind === 'FLUID') a.push(d.amount + 'mB ' + d.id)
        else if (d.kind === 'ITEM') a.push((d.count || 1) + 'x ' + d.id)
        else a.push(String(d.kind))
    }
    return a.length ? a.join(' ＋ ') : '（空）'
}
var TD_PAPER_ONLY = []        // 第 3 档：两处都查不到 ⇒ 真·丢配方
var TD_SHAPED = []            // ① 合成样板分流（正常）
var TD_NO_TYPE_BAD_CELL = []  // ③ 没类型、元件又不是「合成样板」⇒ 静默消失
var TD_TABLE_ONLY = []        // 手写别名表里有、纸上没用到（只是冗余，报一下）
var TD_OLD_EXEMPT = []        // 老配方里万一有缺键（不致命，但要说出来）
var TD_ALIAS_HITS = {}        // 🆕 第 2 档：只有手写别名表认得的中文名 ⇒ 名字 → 命中条数
var TD_REVERSE_HITS = {}      // 🆕 第 1 档：语言文件反查命中的中文名 → 命中条数
var _tdPaperTypes = {}
for (var _td = 0; _td < rows.length; _td++) {
    var _TR = rows[_td]
    if (_TR.type) {
        _tdPaperTypes[_TR.type] = 1
        if (TYPE_REVERSE[_TR.type]) TD_REVERSE_HITS[_TR.type] = (TD_REVERSE_HITS[_TR.type] || 0) + 1
        else if (TYPE_ID_ALIAS[_TR.type]) TD_ALIAS_HITS[_TR.type] = (TD_ALIAS_HITS[_TR.type] || 0) + 1
    }
    if (_TR.old) { if (_TR.type && !TYPE_ID[_TR.type]) TD_OLD_EXEMPT.push(_TR); continue }
    if (_TR.type) { if (!TYPE_ID[_TR.type]) TD_PAPER_ONLY.push(_TR) }
    else { if (_TR.cell === '合成样板') TD_SHAPED.push(_TR); else TD_NO_TYPE_BAD_CELL.push(_TR) }
}
// ⚠️ 口径与改动前一致：只看【手写的 13 键】有没有被本次 PF.txt 用到
//   （若拿注入后的 200+ 个键来算，这一行会变成"190 个没用到"的噪音，看不出原本要看的东西）
for (var _tdk in TYPE_ID_ALIAS) if (Object.prototype.hasOwnProperty.call(TYPE_ID_ALIAS, _tdk) && !_tdPaperTypes[_tdk]) TD_TABLE_ONLY.push(_tdk)
var _tdShapedCells = {}
for (var _tds = 0; _tds < TD_SHAPED.length; _tds++) _tdShapedCells[TD_SHAPED[_tds].cell] = (_tdShapedCells[TD_SHAPED[_tds].cell] || 0) + 1

// ── 日志【拆成两行】（上一次两类混在一行，差点把真事故盖过去）────────────────────
// ⚠️ 这两行必须在【写产物之前】打，否则抛错时人只看到异常、看不到计数。
console.info('[PF] ✅ 合成样板分流 ' + TD_SHAPED.length + ' 条（走工作台路径，正常）'
    + '　元件分布 = ' + JSON.stringify(_tdShapedCells))
console.info('[PF] 🔴 真·跳过 ' + TD_PAPER_ONLY.length + ' 条（两处都查不到 ⇒ 配方会消失）')
if (TD_TABLE_ONLY.length) {
    console.info('[PF] ℹ️ 手写别名表里有、本次 PF.txt 没用到 = ' + TD_TABLE_ONLY.length + ' 个（只是冗余，不是事故）：'
        + TD_TABLE_ONLY.map(function (k) { return '「' + k + '」⇒ ' + TYPE_ID_ALIAS[k] }).join(' ／ '))
} else {
    console.info('[PF] ℹ️ 手写别名表里有、本次 PF.txt 没用到 = 0 个（' + Object.keys(TYPE_ID_ALIAS).length + ' 个键全部有用到）')
}
if (TD_OLD_EXEMPT.length) console.info('[PF] ⚠️ 老配方里出现缺键 ' + TD_OLD_EXEMPT.length + ' 条（它们在产物里是逐字搬的，不影响配方存在性）')

// ═══════════════════════════════════════════════════════════════════════════════
// 🆕 第 1 / 2 档：认得出来 ⇒ 正常跑；第 2 档额外**告诉用户真名是什么**
// ═══════════════════════════════════════════════════════════════════════════════
// 🔴 这就是用户要的「正好可以检查我写错字」：他写的是历史笔误时，不报错（配方照落），
//    但**当场**打印一行"语言文件里的真名是 X" ⇒ 他下次就能写对的那个。
var _tnT1 = Object.keys(TD_REVERSE_HITS)
console.info('[PF] ✅ 第 1 档（语言文件反查命中）' + _tnT1.length + ' 种类型名、共 '
    + _tnT1.reduce(function (a, k) { return a + TD_REVERSE_HITS[k] }, 0) + ' 条'
    + (_tnT1.length ? '：' + _tnT1.map(function (k) { return '「' + k + '」⇒ ' + TYPE_REVERSE[k] }).join(' ／ ') : ''))
var _tnT2 = Object.keys(TD_ALIAS_HITS)
if (_tnT2.length) {
    for (var _t2 = 0; _t2 < _tnT2.length; _t2++) {
        var _nm2 = _tnT2[_t2], _id2 = TYPE_ID_ALIAS[_nm2]
        var _realNames = TN_RES.nameById[_id2]
        var _real2 = (_realNames && _realNames.length) ? _realNames[0] : null
        console.info('[PF] ⚠️ 你写的是「' + _nm2 + '」，语言文件里的真名是'
            + (_real2 ? '「' + _real2 + '」' : '（没有中文名，该 id = gtceu:' + _id2 + '）')
            + '—— 这条按别名处理了（本次 ' + TD_ALIAS_HITS[_nm2] + ' 条配方；手写别名表兜住的，配方不会丢）')
    }
} else {
    console.info('[PF] ℹ️ 第 2 档（只有手写别名表认得的历史笔误）= 0 个（本次纸上没有笔误）')
}
/** 第 3 档的"你是不是想说"+ 候选（候选全部来自反查表 ⇒ 给出来的名字一定是对的）。 */
function tnWrongNameBlock(name) {
    var cand = tnCandidates(name)
    if (!cand.length) return '\n        ⇒ 反查表为空（见上面 G6 的报错），本次给不出候选。'
    return '\n        ⇒ 你是不是想说：' + cand.map(function (c, i) {
        return ['①', '②', '③'][i] + ' ' + c.name + '（差 ' + c.d + ' 个字）'
    }).join('　') + ' ？'
}

if (TD_PAPER_ONLY.length) {
    var _tdL = []
    for (var _tdp = 0; _tdp < TD_PAPER_ONLY.length; _tdp++) {
        var _R2 = TD_PAPER_ONLY[_tdp]
        _tdL.push('    · 纸上写了「' + _R2.type + '」（PF.txt 第 ' + _R2.no + ' 条，产出 ' + tdBrief(_R2.outs) + '）'
            + '—— 我在语言文件里没见过这个名字。' + tnWrongNameBlock(_R2.type)
            + '\n        （元件「' + _R2.cell + '」；本条输入：' + tdBrief(_R2.real) + '）')
    }
    throw new Error('[PF] 🔴 这两处都查不到这个中文类型名（= 这些配方会【从游戏里消失】），拒绝写产物：\n'
        + _tdL.join('\n')
        + '\n    ⇒ 两处 = ① 手写别名/覆盖表（' + Object.keys(TYPE_ID_ALIAS).length + ' 个键）'
        + ' ② 语言文件反查表（' + TN_RES.meta.tableSize + ' 个中文名，现读 ' + TN_RES.meta.jarCount + ' 个 jar）'
        + '\n    ⇒ 修法：照上面给的候选把 PF.txt 那张纸改名即可（候选都取自语言文件 ⇒ 一定是对的）。'
        + '\n    ⇒ 若那个名字**确实**是新类型、只是语言文件还没加键，那要补的是 mod 的 lang（不是这里的表）。'
        + '\n    ⇒ 绝不允许"跳过继续"：2026-09-28 就是这么让 2 条样板静默消失的。')
}
// ⚠️ 这一档是【连名字都丢了】的情形：纸上那张纸既没被认成类型纸、也没有「配方类型：」前缀
//    ⇒ `rows[].type` 是 null ⇒ 反查器看不到名字。这时拿 in 里那些"纸"的名字去猜。
if (TD_NO_TYPE_BAD_CELL.length) {
    var _tdL2 = []
    var _knownNote = { '物质模块是催化剂': 1, '力场发生器是催化剂': 1, '夸克释放催化剂作为催化剂': 1 }
    for (var _tdq = 0; _tdq < TD_NO_TYPE_BAD_CELL.length; _tdq++) {
        var _R3 = TD_NO_TYPE_BAD_CELL[_tdq]
        var _noteNames = (_R3.notes || []).map(function (x) { return x.desc.name })
        var _guess = ''
        for (var _gn = 0; _gn < _noteNames.length; _gn++) {
            var _nm3 = _noteNames[_gn]
            if (_knownNote[_nm3]) continue
            if (/^[0-9]+s$/.test(_nm3)) continue
            var _c3 = tnCandidates(_nm3)
            if (_c3.length && _c3[0].d <= 3) { _guess = '    · 纸上写了「' + _nm3 + '」（PF.txt 第 ' + _R3.no + ' 条，产出 ' + tdBrief(_R3.outs) + '）'
                + '\n      —— 这张纸没有「配方类型：」前缀，我在语言文件里也没见过这个名字。' + tnWrongNameBlock(_nm3); break }
        }
        _tdL2.push('    · PF 第 ' + _R3.no + ' 条｜元件「' + _R3.cell + '」｜**纸上没有可识别的配方类型纸**'
            + '\n        输入：' + tdBrief(_R3.real) + '\n        产出：' + tdBrief(_R3.outs)
            + '\n        该行 in 里的纸：' + (_noteNames.length ? _noteNames.map(function (x) { return '「' + x + '」' }).join(' ') : '（一张都没有）')
            + (_guess ? '\n' + _guess : ''))
    }
    throw new Error('[PF] 🔴 这些行既没有 GT 配方类型、元件又不是「合成样板」⇒ 既不会生成 GT 配方、也不会生成工作台配方 = 【静默消失】，拒绝写产物：\n'
        + _tdL2.join('\n')
        + '\n    ⇒ 最可能的原因：纸上类型名写错了一个字 / 没写「配方类型：」前缀 / 该名字两处都查不到'
        + '\n      （类型纸的识别规则在 gen_manifest.js 的 classifyPaper：①「配方类型：X」前缀 ②裸名精确等于 lang 里的名字）。'
        + '\n    ⇒ 修法：照上面的候选把 PF.txt 那张纸补上「配方类型：」前缀并写对名字。')
}
// ═══════════════════════════════════════════════════════════════════════════════
// CAP 合成 + 硬拦 G2/G3/G4 —— 必须放在 TYPE_ID 之后（判据要用它）
// ═══════════════════════════════════════════════════════════════════════════════
// CAP = 真源①(全部) ∪ 真源②(只取【本次纸上真正用到】的 gtceu 原生)
//
// 🔴 2026-09-30 口径修正（**这是"只扩判定源"必须配套的一处**，不修就会每次误报）：
//    原来这里是 `for (var _k in TYPE_ID) …` —— 遍历整张 TYPE_ID。
//    那时 TYPE_ID 恰好只有 13 个键、且**每一个都被本次 PF.txt 用到**（TD_TABLE_ONLY = 0），
//    所以"遍历表"与"遍历纸上用到的"这两种口径**恰好等价**，看不出区别。
//    但 2026-09-30 起 TYPE_ID 里多了 189 个「语言文件认得、本次没用到」的名字
//    ⇒ 照旧遍历会把 103 个【CAP 里没有上限数据】的 id 拖进 G2/G3
//    ⇒ 每一次生成都会以"IO 上限（CAP）硬拦失败"收场（实测撞到过，原始输出见交付报告）。
//    ⇒ 正确的口径 = **G2/G3/G4 的本意**：只为"本次纸上真正用到的类型"要上限数据。
//      这一条对现有配方是**零影响**：纸上用到的 14 个名字对应的 14 个 id 只有
//      `circuit_assembler` / `primitive_blast_furnace` 两个要靠真源② ⇒ CAP 仍是 43 个键（= 改动前的值）。
var _usedGtIds = {}
for (var _rc0 = 0; _rc0 < rows.length; _rc0++) {
    var _rt0 = rows[_rc0].type
    if (_rt0 && TYPE_ID[_rt0]) _usedGtIds[TYPE_ID[_rt0]] = _rt0
}

var CAP = {}, CAP_ORIGIN = {}
for (var _id1 in SH_JAVA.caps) if (Object.prototype.hasOwnProperty.call(SH_JAVA.caps, _id1)) {
    CAP[_id1] = SH_JAVA.caps[_id1]
    CAP_ORIGIN[_id1] = '①ShanhaiRecipeTypes.java L' + SH_JAVA.where[_id1]
}
var _nativeIds = []
for (var _id2 in _usedGtIds) if (Object.prototype.hasOwnProperty.call(_usedGtIds, _id2)) {
    if (CAP[_id2]) continue                       // 已在真源①里 ⇒ 不用真源②
    if (!GT_BYTECODE.caps[_id2]) continue         // 真源②也没有 ⇒ 交给 G3 报错
    _nativeIds.push(_id2)
}
// ⚠️ **必须先排序、再往 CAP 里插**：`CAP` 的键顺序决定产物文件头 §4 表的行序。
//    上面遍历的是 `_usedGtIds`（2026-09-30 起按 rows 的行序插入）⇒ 不排的话键序会随 PF.txt
//    的配方顺序变 ⇒ 明明配方数据一个字没改、产物字节却会变（给"到底改没改"的判断添噪音）。
//    🔴 这里踩过一次：把 `_nativeIds.sort()` 写在插入【之后】是**无效的** —— 对象键序在插入那一刻
//       就定死了，排数组不会回头重排对象。是被"A/B 应当逐字节相等"这条判据当场抓出来的。
_nativeIds.sort()
for (var _id2b = 0; _id2b < _nativeIds.length; _id2b++) {
    CAP[_nativeIds[_id2b]] = GT_BYTECODE.caps[_nativeIds[_id2b]]
    CAP_ORIGIN[_nativeIds[_id2b]] = '②GTRecipeTypes.txt(javap)'
}

var _g = []
// G2【类型存在但表里没有】—— 这正是 2026-09-26/09-28 那次"整条配方消失/自检空转"的病根
var _missing = []
for (var _id3 in _usedGtIds) if (Object.prototype.hasOwnProperty.call(_usedGtIds, _id3)) {
    if (!CAP[_id3]) _missing.push('gtceu:' + _id3 + '（纸上叫「' + _usedGtIds[_id3] + '」）')
}
if (_missing.length) _g.push('【类型存在但 CAP 表里没有】' + _missing.length + ' 个：\n        - ' + _missing.join('\n        - ')
    + '\n        ⇒ 这些配方会回落到兜底上限 ⇒ 槽位自检对它们形同虚设。')
// G3【用到的类型必须真的注册过】—— 两个真源都查不到 ⇒ 该 id 很可能是笔误
var _unknown = []
for (var _id4 in _usedGtIds) if (Object.prototype.hasOwnProperty.call(_usedGtIds, _id4)) {
    if (!SH_JAVA.caps[_id4] && !GT_BYTECODE.caps[_id4]) _unknown.push('gtceu:' + _id4 + '（纸上叫「' + _usedGtIds[_id4] + '」）')
}
if (_unknown.length) _g.push('【用到的配方类型在两个真源里都查不到】' + _unknown.length + ' 个：\n        - ' + _unknown.join('\n        - ')
    + '\n        ⇒ 要么 id 写错了，要么该类型没有被注册。')
// G4【表里有但类型已不存在】—— 反向差集：CAP 的每个键都必须能追溯到真源①或真源②
var _orphan = []
for (var _id5 in CAP) if (Object.prototype.hasOwnProperty.call(CAP, _id5)) {
    if (!SH_JAVA.caps[_id5] && !GT_BYTECODE.caps[_id5]) _orphan.push(_id5)
}
if (_orphan.length) _g.push('【CAP 表里有但类型已不存在】' + _orphan.length + ' 个：' + _orphan.join(' / '))
if (_g.length) {
    throw new Error('[PF] 🔴 IO 上限（CAP）硬拦失败，拒绝写产物：\n    · ' + _g.join('\n    · ')
        + '\n    真源①：' + JAVA_TYPES
        + '\n    真源②：' + GT_JAVAP
        + '\n    ⇒ 真值只认这两个文件里的活代码/字节码；不要手工往 CAP 里塞数字（它现在完全由真源现算）。')
}
console.log('[PF] CAP 现算 = ' + Object.keys(CAP).length + ' 个键（真源① ' + Object.keys(SH_JAVA.caps).length
    + ' 个 + 真源② gtceu 原生 ' + _nativeIds.length + ' 个 = ' + _nativeIds.join(' / ') + '）')
console.log('[PF] 🔴 双向差集（与真源）: [类型存在但表里没有] = ' + _missing.length
    + '  [表里有但类型已不存在] = ' + _orphan.length + '  [两真源都查不到] = ' + _unknown.length)

var UNRESOLVED_TYPE = {}
// 用户 2026-09-26 **最新**裁决（逐字）：「溢出那就算了，改成 max+8=max,4^8A」
//   ⇒ 电压 = MAX 档，电流 = **4^8 A**。（本条覆盖早先那句「MAX+16=MAX，4^16A」的裁决。）
// 🔴 V[MAX] 从【已部署 gtceu jar 字节码】读出来的真值 = 2147483648（= 2^31），
//    不是 Integer.MAX_VALUE(2147483647)，也不是从注释抄的。
//    GTValues.<clinit>：`bipush 14` → `ldc2_w // long 2147483648l` → `lastore` → `putstatic V:[J`
//    （V 是 15 项 long[]，索引 0..14 = ULV..MAX；VN[14] = "MAX"）
// 🔴 算式：EUt = V[MAX] × 4^8 = 2^31 × 2^16 = **2^47 = 140737488355328**
//    只有 Long.MAX_VALUE(2^63−1) 的 1/65536 ⇒ **不溢出**，可以整体写进 `.EUt(long)`。
// ⚠️ 旧口径留档：4^16 ⇒ 2^31 × 2^32 = 2^63 = Long.MAX+1 ⇒ 越界 1 ⇒ 回绕成负数（详见输出文件头 §8⑥）。
// ⚠️ 「处理样板-星门(MAX+16)」这个键名是 **PF.txt 里 AE2 样板的显示名**，本次不改样板，故原样保留。
// 🔴 2026-09-29：上面这些数字现在【只住在】kubejs\_generators\gt_voltage.js 一个地方，
//    本文件引用它、不再复写（真值出处与自检都在那个文件里，含 javap 复现命令）。
//    ⇒ 本文件里从此**不存在** 8 / 32 / 128 / 140737488355328 这些字面量。
//    ⚠️ 元件名 ⇒ 档位的映射也住在那个模块（CELL_TIER）—— 若 PF.txt 换了元件名，
//       改那一处即可，两个生成器同时生效。
// 🔴 2026-09-26 补：用户新加了「处理样板MV」箱子，CELL_EUT 里【没有这个键】=> EUt(undefined)
//    => 运行期 8 条配方全报 `Can't find method GTRecipeJS.EUt(Undefined)`。
//    电压按 GT 电压表递进（ULV=8 / LV=32 / MV=128 / HV=512），与原有两个值完全吻合 ⇒ 现已补全。
// 🔴 2026-09-29：CELL_EUT 改为**按 rows 里实际出现的元件现算**（不再手抄一份键表），
//    并且【推不出档位就当场抛错、拒绝写产物】—— 不让 `EUt(undefined)` 再活到游戏里。
var CELL_EUT = {}, CELL_EUT_FLAG = {}, EUT_UNRESOLVED_CELLS = {}
var _cellsNeedingEut = {}
for (var _rc = 0; _rc < rows.length; _rc++) {
    // 没有配方类型的中文名 = 工作台的「合成样板」⇒ 走 event.shaped，不需要 EUt
    if (TYPE_ID[rows[_rc].type]) _cellsNeedingEut[rows[_rc].cell] = 1
}
var _cellNames = Object.keys(_cellsNeedingEut).sort()
for (var _ci2 = 0; _ci2 < _cellNames.length; _ci2++) {
    var _cn = _cellNames[_ci2]
    var _eut = GT_VOLTAGE.cellEUt(_cn)
    if (_eut === undefined) { EUT_UNRESOLVED_CELLS[_cn] = 1; continue }
    CELL_EUT[_cn] = _eut
    if (GT_VOLTAGE.isStargateCell(_cn)) CELL_EUT_FLAG[_cn] = true
}
var MAX_AMP_OVERFLOW = false
if (Object.keys(EUT_UNRESOLVED_CELLS).length) {
    throw new Error('[PF] 🔴 有元件推不出 EUt（= gt_voltage.js 的 CELL_TIER 里缺这些键）：'
        + Object.keys(EUT_UNRESOLVED_CELLS).join(' ／ ')
        + '\n    ⇒ 拒绝写出产物（避免把 EUt(undefined) 带进游戏，那会让这些配方在加载期全报错）。'
        + '\n    ⇒ 修法：在 kubejs\\_generators\\gt_voltage.js 的 CELL_TIER 里给这个元件名补上档位。')
}
console.log('[PF] CELL_EUT（现算 = gt_voltage.cellEUt(元件名)）= ' + JSON.stringify(CELL_EUT))

// ---------------------------------------------------------------- 类型归属两张显式表
// 🔴 2026-09-28 新增。这两张表用来回答「这个配方类型是不是山海自己注册的」与「是不是世线族」。
//    刻意做成**显式正向表**，不再沿用反着写的 `NO_GATE_TYPES` 白名单（那个表只列了土高炉一个，
//    "不是山海的机器"这件事当时是靠"漏掉即默认"表达的，加一台新的原生机器就会静默变错）。
//
// 🔴🔴 2026-10-03 用户点单后，**两张表的剩余作用（已按新规则重核，只读分析，未改判定用途）**：
//    · SHANHAI_TYPES —— 【仍在生效】，但作用缩小成"**不消耗时落哪种形态**"：
//        纸上【有】催化剂纸时：在本表里 ⇒ 等级门槛 ModuleLevelCondition；不在 ⇒ .notConsumable 真催化剂。
//        ⚠️ 它**不再**决定"要不要消耗"—— 那由纸（`catModule`）单独决定。
//        它另有一个【加载期护栏】用途（G5）：纸上用到的 shanhai 类型漏进本表 ⇒ 直接拒绝写产物。
//    · WORLDLINE_TYPES —— 【对产物零影响】：2026-10-03 起不再参与任何判定，只剩诊断读数
//        （进 specs.json 的 `isWorldlineRecipe` 与产物注释），保留是为了"历史判据可追溯"。
//        ⇒ 改它【不会】改变任何一条配方的落法；改 SHANHAI_TYPES 仍会（无纸条目除外）。
//    · SHARD_FAMILY（残片族）—— 同上：2026-10-01 那版判据的读数，本轮起只作诊断（见其定义处）。
//
//  · SHANHAI_TYPES —— 出处：shanhai-rewrite\src\main\java\com\shanhai\common\recipe\ShanhaiRecipeTypes.java
//      里 **全部 41 条** `GTRecipeTypes.register("…", "…")`（已用 grep 逐条抄下来，未凭记忆写）。
//      gtceu 原生的（primitive_blast_furnace / assembler / circuit_assembler …）**不在这张表里**。
//  · WORLDLINE_TYPES —— 世线族。以 **gt id** 判定（不用中文名当唯一判据）。
//      ⚠️ 2026-09-29 用户第二次裁决后**只剩 4 个 `worldline_*`**
//        （`wl_board_*` 两个于 2026-09-28 拿掉；`worldline_sampling` 于 2026-09-29 拿掉，见下方定义处）。
//      4 个 id 全部在 ShanhaiRecipeTypes.java 里有 register 调用：
//        L447 worldline_oscillation_collection ／ L710 worldline_cutting
//        L732 worldline_matter_recurrence ／ L743 worldline_probability_cracking
//      （行号是 2026-09-28 那次改动【后】的；历次改动都只动 setMaxIOSize，没动注册本身。
//        ⚠️ 2026-09-29 拿掉 `worldline_sampling` **没有**改注册，只把它从本表移出。）
var SHANHAI_TYPES = {
    primordial_power_generator: 1, primordial_stellar_reaction: 1, primordial_biological_core: 1,
    primordial_matter_recombination: 1, primordial_causal_weaving: 1, primordial_singularity_inversion: 1,
    taixu_smelting: 1, worldline_oscillation_collection: 1, interstellar_matter_absorption: 1,
    matter_flow_condensation: 1, primordial_energy_absorption: 1, photon_separation: 1,
    matter_module_casting: 1, matter_forging: 1, primordial_matter_deconstruction: 1,
    wl_board_circuit_assembly: 1, wl_board_wafer_etching: 1, proxy_execution: 1,
    coin_forge: 1, nine_industrial: 1, black_hole_event_horizon_blast: 1,
    black_hole_neutronium_compressor: 1, black_hole_compressor: 1, high_dimensional_fragment_cutting: 1,
    worldline_cutting: 1, worldline_sampling: 1, worldline_matter_recurrence: 1,
    worldline_probability_cracking: 1, photon_siphon: 1, zero_point_conversion: 1,
    matter_aggregation: 1, gravitational_wave_consumption: 1, tianjie_navigation: 1,
    nebula_siphoning: 1, chaos_crafting: 1, seventy_two_changes: 1,
    gravitational_wave_production: 1, primordial_myriad_ascension_tier_1: 1,
    primordial_myriad_ascension_tier_2: 1, kmyy: 1, spacetime_distortion: 1,
    // 🆕 2026-10-03 补第 45 条「原初山海调试」primordial_debug
    //   （用户点单；注册在 ShanhaiRecipeTypes.java 的 register("primordial_debug","multiblock")）。
    //   ⇒ 它是"山海自己注册的类型"，按本表口径必须在这里有一条；漏了就落 .notConsumable 真催化剂。
    //   ⚠️ 它【不】进 WORLDLINE_TYPES（不是世线族）。
    primordial_debug: 1
}
// ── 世线族（gt id）—— 🔴 2026-10-03 起【不再决定】物质模块要不要消耗，只作历史诊断读数 ─────
// 🔴🔴 2026-10-03 状态说明（用户点单：判据改成"只看纸"）：
//    本表【当前对产物零影响】—— 它不再出现在 `consumeModule` 的判据里，
//    只写进 specs.json 的 `isWorldlineRecipe` 字段、以及产物注释里的一句"历史读数"。
//    保留它的理由：① 用户历次裁决（下面三段）的证据链不能丢；② 将来若要回到"按类型"的判据，
//    这张表是现成的、已核过真源的正向表。⇒ 删/改本表【不会】改变任何配方落法。
// 🔴🔴 2026-09-28 用户裁决（第一条）：**`wl_board_circuit_assembly` 与 `wl_board_wafer_etching`
//    不算"世线族"** ⇒ 已从本表【拿掉】。
//    ⇒ 后果：PF.txt 里那 3 条「世线板电路组装」配方（no=34 出 8× shanhai:wl_board_ulv ／
//      no=43 出 1× shanhai:wl_board_lv ／ no=60 出 1× shanhai:wl_board_mv）**保持"不消耗"**，
//      它们的物质模块落现有机制（这两个类型都在 SHANHAI_TYPES 里 ⇒ **等级门槛 ModuleLevelCondition**）。
//      纸上本来就写着「物质模块是催化剂」，与落法一致（⇒ 在 2026-10-03 判据下这个结论【不变】，
//      而且现在的原因更直接：纸上有催化剂纸）。
//    ⚠️ 本表只保留真正以 `worldline_` 开头的 4 个，且**采样已移出**。`wl_board_*` 虽然也注册在
//      ShanhaiRecipeTypes.java 里（L596 / L606），但同样**不参与"应当消耗"的判定**。
// 🔴🔴 2026-09-29 用户裁决（第二条）：**`worldline_sampling`（世线采样）也不算"世线族"** ⇒ 已从本表拿掉。
//    用户原话（逐字）：
//      「世线采样是合成世线晶核的，是一个准备工作，不是真正的制作世线，所以不需要消耗物质模块」
//    ⇒ 后果（2026-09-29 全表逐行模拟，原始输出见 temp\wl-sample-audit\audit.out.txt）：
//      · PF.txt 第 64 条（元件「处理样板MV」／类型「世线采样」／产出 1x shanhai:worldline_crystal_core）
//        的模块决策由 consume 变为 gate（worldline_sampling 仍在 SHANHAI_TYPES 里 ⇒ 落等级门槛）。
//      · **但该样板的输入里本来就没有物质模块**（输入 = 16x shanhai:unknown_particle ＋
//        64x shanhai:cosmic_dust ＋ 4x gtlcore:treasures_crystal，流体 shanhai:zero_point_energy 1024000）
//        ⇒ 落地到配方里**一个字都没变**（不写门槛、不写催化剂、itemInputs 原样）。
//      · 全部 82 行逐行模拟后，**没有任何其它配方**的落法发生变化（含模块输入的 25 条全部不变）。
//        本条改动是"把口径摆正"，对当前 PF.txt 的产物是**零配方差异**（只有文件头注释会变）。
//    ⚠️ 仍需 TYPE_ID 里的「世线采样 ⇒ worldline_sampling」映射（否则整条会被 SKIPPED ⇒ 配方消失）。
//      `CAP` 表里的 worldline_sampling = (3,12,3,6) 同样保留（槽位自检用）。
var WORLDLINE_TYPES = {
    worldline_oscillation_collection: 1, worldline_cutting: 1,
    worldline_matter_recurrence: 1, worldline_probability_cracking: 1
}
// ── 世线【残片族】（= 「世线的运用」）—— 🔴 2026-10-03 起【不再决定】物质模块要不要消耗 ────
// 🔴🔴 2026-10-03 状态说明：本表第二轮起也变成**历史诊断读数**（写进 specs.json 的推导、
//    以及产物注释里那句"与 2026-10-01 结论也一致"）。判定消不消耗的唯一依据是纸。
//    实测自证：本次唯一命中本表的配方（thread_shard_1，PF 第 77 条）纸上【有】催化剂纸
//    ⇒ 新判据给出"不消耗"，与旧判据结论一致 ⇒ 本表当前"改了也不影响产物"。
// 🔴🔴 2026-10-01 用户拍板（**采用"判据 B"**）：把消费判据从"只看配方类型"改成
//    **「看【产出】是不是『残片族』」**。原话（逐字）：
//      · 「那条配方的模块」⇒ 选 **「B. 等级门槛（不烧、但要挂）」**
//      · 「世线残片其余 6 档 ＋ 寰宇并行超限器」⇒ 选 **「A. 还没写，以后补」** ⇒ **不许动**
//
//   🔑 为什么必须是"看产出"而不是"看类型"（这就是用户报的那个 bug 的根因）：
//      `worldline_cutting`（原初世线切割）∈ WORLDLINE_TYPES ⇒ 被规则②当成"世线族"**消耗**，
//      但它产出的 `thread_shard_1`（世线残片·初醒）属于**「世线的运用」**，不是「世线本体」。
//      用户 2026-09-26 亲自定过这个区分：
//        「世线残片 7 档（初醒→裁决）是【世线的运用】，恰当使用世线、运用世线的能力，
//          可以让机器获得跨配方并行」＋「世线本体 = 世线碎片·核心那一族」
//      ⇒ **⇒ 【世线的运用】≠【世线本体】⇒ 类型级判据天然分不开这两者 ⇒ 必须看产出。**
//
//   ⚠️ 真源 = **不新造清单**：这张表逐字对应
//      `shanhai-rewrite\src\main\java\com\shanhai\common\thread\ShanhaiConcurrencyTables.java`
//      里 `SHARD_EXPONENTS` 的 **8 个键**（`static{}` 块：`thread_shard_1..7` ＋ 超限器）。
//      那个类自带加载期 `selfTest()` fail-fast（拼错一个字符就炸），是工程里的唯一真源。
//      ⚠️ 超限器（`universal_parallel_overdriver`，2^10）**不是 8 号残片**，但它在同一个
//         `SHARD_EXPONENTS` 表里、同属"残片族/运用类"⇒ 一并收录（当前 PF.txt 里它没有配方，
//         所以对本次产物 **零影响**；收录它只是为了与真源逐字对齐、将来不漏）。
//
//   📐 判据落点（⚠️ 下面是 **2026-10-01 那版**的优先级，已被 2026-10-03"只看纸"取代，只作留档）：
//      ⓐ 产出含物质模块（制作物质模块）  ⇒ **消耗**
//      ⓑ′ 产出 ∈ 残片族（世线的运用）    ⇒ **不消耗**（落等级门槛）   ← 2026-10-01 新增，**这条优先**
//      ⓑ 类型 ∈ 世线族（世线本体线）      ⇒ **消耗**                  ← 原规则②，保留
//      ⓒ 其他山海类型                     ⇒ 等级门槛
//      ⓓ 非山海类型                       ⇒ 真催化剂
//      ⇒ ⚠️ ⓑ′ 会与 ⓑ 重叠（`worldline_cutting` 同时命中两条），**ⓑ′ 先判 ⇒ ⓑ′ 赢**。
//        证据自证：世线震荡收集（产出 **维度世线碎片** = 世线本体）不命中 ⓑ′ ⇒ **仍消耗** ✓
//                  （⇒ 用户 2026-10-01 复述："世线本体 = 世线碎片·核心那一族" ⇒ 它该消耗 ✓）
//      🔴🔴 2026-10-03 起：上面这套【全部失效】，判据只剩一条 —— 纸上有没有「物质模块是催化剂」。
//        本表不再参与决策（见本段开头的状态说明）。
var SHARD_FAMILY = {
    'shanhai:thread_shard_1': 1,
    'shanhai:thread_shard_2': 1,
    'shanhai:thread_shard_3': 1,
    'shanhai:thread_shard_4': 1,
    'shanhai:thread_shard_5': 1,
    'shanhai:thread_shard_6': 1,
    'shanhai:thread_shard_7': 1,
    'shanhai:universal_parallel_overdriver': 1
}
// ── 物质模块的【权威 17 项表】 ───────────────────────────────────────────────
// 🔴 2026-09-28 新增。**逐字抄自**
//    shanhai-rewrite\src\main\java\com\shanhai\machine\module\PrimordialModuleMachine.java
//    的 `MODULE_LEVELS`（L637-653，17 项，顺序 = 等级 1..17）。
//    该表自称"全工程唯一的『物品 id → 等级』映射"，且 lang 的
//    `shanhai.recipe.fail.module_level.unresolved` = "…（配方里的模块 id 不在 17 个物质模块表里）"
//    也以它为准 ⇒ 这就是"什么算物质模块"的唯一判据。
//
// 🔴🔴 2026-10-03 同步（用户「物理台阶」重排）：Java 侧 `MODULE_LEVELS` 把**等级 5..16 的对应关系**
//    改了（1-4 与 17 固定不动）⇒ 本表的 8 个值跟着改（transformation 5→9、dark_star 6→7、
//    material_recombination 7→5、zeroing 9→6、apex 10→11、dimensional_ascension 11→10、
//    transfinite 12→13、chaos 13→12）。**键集合一个都没动**（"什么算物质模块"的判据不变）。
//    ⚠️ 本表的**数字只用于生成注释文本**（`要求模块等级 ≥ N`，见下面 `lvl` 的两处用处），
//       真正生效的门槛由 Java 侧 `MODULE_LEVELS` 现算 —— 所以本表必须与它同步，否则注释会说谎。
//    ⚠️ 本次同步**不改变任何产物字节**：现存的 `moduleLevelRequirement` 只用到
//       introductory(Lv.1) / basic(Lv.2) / material_deduction(Lv.3) / virtual_image(Lv.4) /
//       genesis(Lv.17) 这 5 个 id，全部落在"固定不动"的档位上（改后重跑 sha256 逐字节相同）。
//
// 🔴🔴 为什么必须换成这张表：**旧代码用的是 `/material_module$/` 正则，它漏了 17 项里的 6 个**
//    （不以 "material_module" 结尾的：material_deduction_module / material_recombination_module /
//      imaginary_material_transition_remolding_module / material_creation_module /
//      reality_anchor_module / genesis_reality_modification_module）。
//    ⇒ 后果①（输入侧）：样板 PF.txt no=1/2/3 的输入里有 `shanhai:genesis_reality_modification_module`
//       （= 物质模块 #17，等级 17），旧代码**认不出它是物质模块** ⇒ 它留在 itemInputs 里被当普通材料
//       **消耗掉**，而且还打了一条假告警"该样板里【没有】任何物质模块物品"。
//    ⇒ 后果②（产出侧）：样板 no=62 的产出是 `shanhai:material_deduction_module`（= 物质模块 #3）
//       ⇒ 属于用户说的"制作物质模块"，应当**消耗**输入里的模块；旧正则认不出 ⇒ 会落成"不消耗"。
var MATERIAL_MODULES = {
    'shanhai:introductory_material_module': 1,
    'shanhai:basic_material_module': 2,
    'shanhai:material_deduction_module': 3,
    'shanhai:virtual_image_material_module': 4,
    // 2026-10-03 起下面 8 个数字 = 新档位（与 Java MODULE_LEVELS 同步）
    'shanhai:material_recombination_module': 5,
    'shanhai:zeroing_material_module': 6,
    'shanhai:dark_star_material_module': 7,
    'shanhai:imaginary_material_transition_remolding_module': 8,
    'shanhai:transformation_material_module': 9,
    'shanhai:dimensional_ascension_material_module': 10,
    'shanhai:apex_material_module': 11,
    'shanhai:chaos_material_module': 12,
    'shanhai:transfinite_material_module': 13,
    'shanhai:eternal_material_module': 14,
    'shanhai:material_creation_module': 15,
    'shanhai:reality_anchor_module': 16,
    'shanhai:genesis_reality_modification_module': 17
}
// ── 夸克释放催化剂的【显式 7 项表】 ──────────────────────────────────────────
// 🔴 2026-09-30 新增（本轮「描述逐种核实」的落地项）。
//    纸面原文（**逐字**，末尾【没有】句号）：「夸克释放催化剂作为催化剂」（2 张，PF 第 60 / 71 条）。
//    ⇒ 在本轮之前，这句话【一个字都没被流水线读到】：生成器里既没有它的分支、也没把它列进
//      §1 的 `_knownNote`，而 paper 又不是 TYPE/TIME ⇒ 它退化成一张**纯注记**就被丢掉了，
//      于是那两个 `64x shanhai:<上|下>_quark_emission_catalyst` 按普通材料落进了 `itemInputs`
//      ⇒ **每条配方白烧 64 个**（实测证据：temp\desc-apply\cases\noQuarkPaper vs base 的
//      形状差异 = 0 ⇒ 反证"纸读没读都一样"，即当前完全没读）。
//
// 🔴 为什么是【显式表】而不是正则：本表【必须】把 `shanhai:casing_empty_quark_emission_catalyst`
//    （空夸克释放催化剂外壳）排除掉 —— 它的 id **以 `_quark_emission_catalyst` 结尾**，
//    任何 `/quark_emission_catalyst$/` 都会把它误吞。而它是个**会被消耗的中间产物**
//    （PF 第 58/61 条是它的产出、第 59/63 条是它的产出），不是催化剂。
//    ⇒ 这正是本文件 §7① 踩过的同一个坑（`/material_module$/` 漏 6 项）的同型教训：
//      **"按名字正则"在命名不规整的 id 空间里一定出错，只能用显式正向表。**
//
// 取证：这 7 个 id **全部**在【游戏导出的真实配方表】里出现过（现查，不是凭记忆）：
//    up=3 处 / down=3 处 / top / bottom / charm / strange / misaligned 各 1 处（共 11 处）
//    ⇒ 它们确实是注册过的真物品，不会被 `.notConsumable(...)` 变成"不存在的物品"。
//    lang 中文名（recipe-convert\lang\shanhai_zh_cn.json 现读）：
//      上-夸克释放催化剂 / 下-夸克释放催化剂 / 顶-夸克释放催化剂 / 底-夸克释放催化剂 /
//      粲-夸克释放催化剂 / 奇-夸克释放催化剂 / 非对齐夸克释放催化剂
var QUARK_EMISSION_CATALYSTS = {
    'shanhai:up_quark_emission_catalyst': 1,
    'shanhai:down_quark_emission_catalyst': 1,
    'shanhai:top_quark_emission_catalyst': 1,
    'shanhai:bottom_quark_emission_catalyst': 1,
    'shanhai:charm_quark_emission_catalyst': 1,
    'shanhai:strange_quark_emission_catalyst': 1,
    'shanhai:misaligned_quark_emission_catalyst': 1
}
/** 纸面原文常量：出现这两处才能被静默改名抓出来（见 §7④）。 */
var PAPER_QUARK_CATALYST = '夸克释放催化剂作为催化剂'
/** 🔴 2026-10-03：物质模块的**唯一判据纸**的纸面原文（kind = 'NOTE'）。
 *  用户点单原文：「判断物质模块是否是催化剂仅凭借是否我放了那张纸」
 *  ⇒ 全脚本只在这里写一次这个字符串，判据 / 自报行 / 文件头计数都引用它，避免改名时漏改一处。 */
var PAPER_MODULE_CATALYST = '物质模块是催化剂'
/** "Nx <id>" / "<id>" ⇒ id。产物里的 itemInputs / notConsumable 都是这个形态。 */
function idOfSlotText(s) {
    var t = String(s).trim()
    var m = /^\d+x\s+(.+)$/.exec(t)
    return m ? m[1] : t
}
// ═══════════════════════════════════════════════════════════════════════════════
// 🔴🔴 2026-10-03 追加（用户点单）：**判据的唯一实现**——「只看纸」。
//    ⚠️ 为什么把它抽成函数：本轮之前，这条判据只活在 `specFromRow()` 里，
//       而 `emitOldGt()` 那 3 条"老配方"（PF 第 21 / 27 / 36 条）走的是**另一条路**
//       （硬编码字符串文本，见 emitOldGt 处）⇒ 它们**根本吃不到判据**
//       ⇒ `shanhai:pf/photon`（= PF 第 36 条，纸上没有催化剂纸）一直挂着
//       `moduleLevelRequirement = 1x 入门物质模块`，而按 2026-10-03 的规则它**应当被消耗**。
//    ⇒ 现在两条路**共用这一个函数**（一个判据、两处落点），再也不会出现"某条配方漏在判据之外"。
//    ⚠️ 自证：`moduleRuleSelfTest()`（下面）在加载期拿一条**已知有纸**的行验证本函数报 true，
//       拿一条**已知无纸**的行验证它报 false —— 判据自己先证明自己认得出来。
// ═══════════════════════════════════════════════════════════════════════════════
/**
 * 物质模块怎么落 —— **只看纸**（用户 2026-10-03 原话逐字：
 * 「判断物质模块是否是催化剂仅凭借是否我放了那张纸」）。
 * @param {object} R        rows.json 里的**整行对象**（判据读它的 `notes`，即 papers 里 kind='NOTE' 的那批）
 * @param {string} typeId   gt 类型 id（决定"不消耗"时落门槛还是落真催化剂）
 * @returns {{catModule:boolean, slot:(number|null), decision:string, why:string}}
 *          decision: 'consume'（纸上无催化剂纸 ⇒ 模块进 itemInputs 被消耗）
 *                  | 'gate'    （纸上有 ⇒ 山海自己的机器 ⇒ ModuleLevelCondition 等级门槛）
 *                  | 'catalyst'（纸上有 ⇒ 非山海机器 ⇒ .notConsumable 真催化剂）
 */
function moduleRuleOf(R, typeId) {
    var catModule = false, slot = null
    var notes = (R && R.notes) || []
    for (var i = 0; i < notes.length; i++) {
        if (notes[i].desc.name === PAPER_MODULE_CATALYST) { catModule = true; slot = notes[i].slot }
    }
    var decision = catModule ? (SHANHAI_TYPES[typeId] ? 'gate' : 'catalyst') : 'consume'
    var why = catModule
        ? ('纸上（第 ' + slot + ' 格）放着「' + PAPER_MODULE_CATALYST + '」')
        : ('纸上（无）【没有】「' + PAPER_MODULE_CATALYST + '」')
    return { catModule: catModule, slot: slot, decision: decision, why: why }
}
/** 某一行输入里全部【物质模块】的落点文本（形如 '1x shanhai:basic_material_module'），按纸上顺序，数量照纸。 */
function moduleSlotsOfRow(R) {
    var out = []
    for (var i = 0; i < ((R && R.real) || []).length; i++) {
        var d = R.real[i].d
        if (MATERIAL_MODULES[d.id]) out.push((d.count || 1) + 'x ' + d.id)
    }
    return out
}
// ── 判据自证：先证明它认得出来，再让任何一条配方去用它 ──────────────────────────
// ⚠️ 用的是【真正在读的那份 rows】里的样本（不是编造的对象）：
//   · 正向：找一条 notes 里【写着】催化剂纸的 ⇒ 必须报 true；
//   · 负向：找一条【没写】的 ⇒ 必须报 false。
//   任一条不成立 ⇒ 抛错拒绝写产物（判据坏了却继续生成，比不生成危险得多）。
function moduleRuleSelfTest() {
    var pos = null, neg = null
    for (var i = 0; i < rows.length; i++) {
        var has = moduleRuleOf(rows[i], 'zzz_probe').catModule
        if (has && !pos) pos = rows[i]
        if (!has && !neg) neg = rows[i]
    }
    if (!pos) throw new Error('[PF] 🔴 物质模块判据自证失败：整份 rows.json 里找不到任何一条写着「'
        + PAPER_MODULE_CATALYST + '」的样板 ⇒ 判据不可能工作（先查 parse_pf/gen_manifest 是否把 NOTE 读丢了）。')
    if (!neg) throw new Error('[PF] 🔴 物质模块判据自证失败：整份 rows.json 里**每一条**都写着「'
        + PAPER_MODULE_CATALYST + '」⇒ 判据恒为 true，等于没有判据。')
    var rp = moduleRuleOf(pos, 'primordial_singularity_inversion')   // 山海类型 ⇒ 期望 gate
    var rn = moduleRuleOf(neg, 'primordial_singularity_inversion')   // 无纸 ⇒ 期望 consume
    if (rp.decision !== 'gate') throw new Error('[PF] 🔴 判据自证失败：有纸的山海类型配方应当落 gate，实得 ' + rp.decision)
    if (rn.decision !== 'consume') throw new Error('[PF] 🔴 判据自证失败：无纸的配方应当落 consume，实得 ' + rn.decision)
    var rc = moduleRuleOf(pos, 'primitive_blast_furnace')            // 非山海类型 + 有纸 ⇒ 期望 catalyst
    if (rc.decision !== 'catalyst') throw new Error('[PF] 🔴 判据自证失败：有纸的非山海类型配方应当落 catalyst，实得 ' + rc.decision)
    console.info('[PF] ✅ 物质模块判据自证（只看纸）：有纸样本 #' + pos.no + ' ⇒ gate/catalyst ✓；无纸样本 #' + neg.no + ' ⇒ consume ✓')
}
moduleRuleSelfTest()
/** 某条 spec 的产出里有哪些落在【残片族】（供告警/注记行点名，只回 id，不编中文名）。 */
function shardFamilyNames(outs) {
    var r = []
    for (var i = 0; i < (outs || []).length; i++) {
        var d = outs[i].d
        if (d && d.kind === 'ITEM' && d.id && SHARD_FAMILY[d.id]) r.push(d.id)
    }
    return r.join(' / ') || '（无）'
}
// ═══════════════════════════════════════════════════════════════════════════════
// 🔴 2026-10-03 「力场发生器是催化剂」—— 判据由【写死 LV】改成【认整族】＋ 族自证
// ═══════════════════════════════════════════════════════════════════════════════
// 修的是什么：本文件原来把判据写死成 `it.id === 'gtceu:lv_field_generator'`（旧 L993）
//   ⇒ 纸上写了「力场发生器是催化剂」、元件却用 **MV/HV/…** 时**静默失效**：那个物品照常
//     落进 `itemInputs` **被消耗**，且不报任何错。
//   受害配方 = `shanhai:pf/muon`（PF.txt 第 83 条，元件「处理样板HV」，用 `gtceu:hv_field_generator`）。
//
// 现判据（两条**同时**满足才算命中）：
//   ① 纸写了「力场发生器是催化剂」（= 本段上方的 `catField`）；
//   ② 物品 id ∈【力场发生器族】= `isFieldGeneratorFamily(it.id)`。
//
// 🔴 族**不得**用裸后缀 `/field_generator$/` 判 —— 游戏导出的注册表里有 2 个**不属于**力场发生器、
//    却同样以该后缀结尾的 id，裸后缀会把它们**误吞**（它们会被错误地变成"不消耗的催化剂"）：
//      · `kubejs:containment_field_generator`              （遏制场发生器）
//      · `kubejs:spacetime_compression_field_generator`    （时空压缩场发生器）
//    ⇒ 本判据把**命名空间锚死**在 `gtceu:` / `gtlcore:` 两处。
//
// 族内容（2026-10-03 现查导出 `local\kubejs\export\registries\item.json`：以 `field_generator`
//   结尾的 id 共 **16** 个，其中属族 **14** 个、被排除 **2** 个）：
//     gtceu:lv / mv / hv / ev / iv / luv / zpm / uv / uhv / uev / uiv / opv / uxv _field_generator（13 个电压档）
//     gtlcore:max_field_generator                                                                  （第 14 个）
//     （排除：上面那 2 个 `kubejs:` 的）
//
// ⚠️ 为什么用"命名空间锚死的正则"、而不是手抄一张 14 键的正向表：
//    本文件 §§7①/§7④ 那两次的真正病根是**按名字正则在命名不规整的空间里判**
//    （`/material_module$/` 漏 6 项；`/quark_emission_catalyst$/` 会误吞
//     `shanhai:quark_emission_catalyst`）。本族的 id 空间**是规整的**（`gtceu:<档位>_field_generator`），
//    那个病根在这里不适用；而**手抄表**的病根恰好就是【本 defect 本身】—— GT 再加一档电压时
//    表不会跟着长 ⇒ 同一处静默失效原样复发。⇒ 取两者之长：**正则做判据（不会漏档）＋ 注册表
//    现场自证（不会误吞、也不会漏档）**。自证见下。
var FIELD_GENERATOR_FAMILY_RE = /^(?:gtceu|gtlcore):[a-z0-9_]*_field_generator$/
/** 官方族判据：物品 id 是否属于【力场发生器族】（见上：命名空间锚死，挡掉 2 个 kubejs 同名物）。 */
function isFieldGeneratorFamily(id) {
    return typeof id === 'string' && FIELD_GENERATOR_FAMILY_RE.test(id)
}
// ── 族自证（正负对照都在里面）───────────────────────────────────────────────────
//   正面：导出注册表里 14 个 gtceu/gtlcore 的 `*_field_generator` 必须被判据【全部接受】；
//   负面：同一张表里 2 个 `kubejs:*_field_generator` 必须被【全部拒绝】。
//   任一不符 ⇒ 抛错拒绝写产物（判据与真实注册表脱节了，绝不能静默继续）。
//   ⚠️ 读不到导出表（新实例 / 还没 /kubejs export）时**不抛错**，只响亮告警并如实写"本轮无读数" ——
//      口径与上面的 `exportRecipeFileCount()` 一致（读不到就报"读不到"，绝不编一个数字）。
var FIELD_FAMILY_EVIDENCE = (function () {
    var _p = path.join(INSTANCE, 'local', 'kubejs', 'export', 'registries', 'item.json')
    var _raw
    try { _raw = fs.readFileSync(_p, 'utf8') } catch (e) {
        return { ok: null, why: '读不到 `' + _p + '`（' + (e && e.code ? e.code : e) + '）' }
    }
    var _seen = {}, _m = _raw.match(/"([a-z0-9_]+:[a-z0-9_\/]*field_generator)"/g) || []
    for (var _i = 0; _i < _m.length; _i++) _seen[_m[_i].slice(1, -1)] = 1
    var _all = []
    for (var _k in _seen) if (Object.prototype.hasOwnProperty.call(_seen, _k)) _all.push(_k)
    _all.sort()
    var _in = [], _out = []
    for (var _j = 0; _j < _all.length; _j++) { if (isFieldGeneratorFamily(_all[_j])) _in.push(_all[_j]); else _out.push(_all[_j]) }
    return { ok: true, path: _p, all: _all, inFam: _in, outFam: _out }
})()
if (FIELD_FAMILY_EVIDENCE.ok === true) {
    var _ffBad = []
    if (FIELD_FAMILY_EVIDENCE.all.length !== FIELD_FAMILY_EVIDENCE.inFam.length + FIELD_FAMILY_EVIDENCE.outFam.length) {
        _ffBad.push('分类不守恒（族内 ' + FIELD_FAMILY_EVIDENCE.inFam.length + ' + 族外 '
            + FIELD_FAMILY_EVIDENCE.outFam.length + ' ≠ 总数 ' + FIELD_FAMILY_EVIDENCE.all.length + '）')
    }
    if (FIELD_FAMILY_EVIDENCE.inFam.length !== 14) {
        _ffBad.push('族内命中 = ' + FIELD_FAMILY_EVIDENCE.inFam.length
            + '，期望 14（13 个 gtceu 电压档 ＋ gtlcore:max_field_generator）')
    }
    if (FIELD_FAMILY_EVIDENCE.outFam.length !== 2) {
        _ffBad.push('族外 = ' + FIELD_FAMILY_EVIDENCE.outFam.length
            + '，期望 2（kubejs:containment_field_generator / kubejs:spacetime_compression_field_generator）')
    }
    for (var _ffb1 = 0; _ffb1 < FIELD_FAMILY_EVIDENCE.outFam.length; _ffb1++) {
        var _ffid = FIELD_FAMILY_EVIDENCE.outFam[_ffb1]
        if (!/^kubejs:/.test(_ffid)) _ffBad.push('被排除的 `' + _ffid
            + '` 不在 kubejs 命名空间 ⇒ 判据可能把一个真的力场发生器族成员误排除了（那会让它静默被消耗）')
    }
    for (var _ffb2 = 0; _ffb2 < FIELD_FAMILY_EVIDENCE.inFam.length; _ffb2++) {
        var _ffid2 = FIELD_FAMILY_EVIDENCE.inFam[_ffb2]
        if (!/^(gtceu|gtlcore):/.test(_ffid2)) _ffBad.push('族内出现了非 gtceu/gtlcore 的 `' + _ffid2 + '`')
    }
    if (_ffBad.length) {
        throw new Error('[PF] 🔴 力场发生器【族自证】失败，拒绝写产物：\n    · ' + _ffBad.join('\n    · ')
            + '\n    ⇒ 判据 = `' + FIELD_GENERATOR_FAMILY_RE + '`，取证文件 = ' + FIELD_FAMILY_EVIDENCE.path
            + '\n    ⇒ 要么判据写错了（会静默误吞/漏档），要么游戏真的加了族成员 —— 两种情况都必须先看清再继续。')
    }
    console.info('[PF] ✅ 力场发生器族自证（正负对照）：导出注册表里以 `field_generator` 结尾的 id = '
        + FIELD_FAMILY_EVIDENCE.all.length + ' 个；判据命中 = ' + FIELD_FAMILY_EVIDENCE.inFam.length
        + ' 个 → ' + FIELD_FAMILY_EVIDENCE.inFam.join(' / '))
    console.info('[PF]    ↳ 负对照：被【排除】= ' + FIELD_FAMILY_EVIDENCE.outFam.length + ' 个 → '
        + FIELD_FAMILY_EVIDENCE.outFam.join(' / ') + '（这 2 个不是力场发生器，裸后缀 `/field_generator$/` 会误吞它们）')
} else {
    console.warn('[PF] ⚠️ 力场发生器族【本轮无自证读数】：' + FIELD_FAMILY_EVIDENCE.why
        + ' ⇒ 判据 `' + FIELD_GENERATOR_FAMILY_RE + '` 仍按命名空间锚定生效，'
        + '但"命中几个 / 排除几个"这次没有读数（不编数字）。')
}
// ═══════════════════════════════════════════════════════════════════════════════
// 🔴 2026-09-30 硬拦【G5·模块决策的静默兜底】—— 与 CAP 缺键同型的病，同一个修法
// ═══════════════════════════════════════════════════════════════════════════════
// 缺键时的行为（逐个说清，这正是"兜底是继续但结果错、还是跳过"那一问）：
//   · `TYPE_ID` 缺键        ⇒ specFromRow 返回 null ⇒ **跳过**（配方消失）——已在顶部改成硬断言；
//   · `SHANHAI_TYPES` 缺键  ⇒ 纸上有催化剂纸时 moduleDecision 落 'catalyst' ⇒ **继续，但配方内容变了**
//       （物质模块从"等级门槛"变成 `.notConsumable(...)` 真催化剂）；
//       ⚠️ 2026-10-03 起它**只影响"不消耗时的形态"**，不影响"要不要消耗"（那由纸单独决定）；
//   · `WORLDLINE_TYPES` 缺键 ⇒ 2026-10-03 起【对产物零影响】：它只剩诊断读数
//       （specs.json 的 isWorldlineRecipe 与产物注释），不再决定消不消耗；
//   · `MATERIAL_MODULES` 缺键 ⇒ 那个物品被当普通材料**消耗掉** ⇒ **继续，但内容变了**。
// 本条只拦最容易漏的那种：**一个类型明明由 shanhai 自己注册（真源①有），却没被写进 SHANHAI_TYPES**
//   ⇒ 它会被静默当成"不是山海的机器"落 catalyst。CAP 那次就是这么漏的
//   （旧表 12 个键 vs Java 活代码 41 个 ⇒ 缺 31 个键而无人知晓）。
//   ⚠️ 判据用【真源①（ShanhaiRecipeTypes.java 活代码）】而不是产物里的 CAP 表：
//      CAP = 真源① ∪ 真源②(gtceu 原生)，拿它当判据会把 `circuit_assembler` /
//      `primitive_blast_furnace` 两个 gtceu 原生也误判成"shanhai 注册过"（这是我在扫描脚本里
//      实际犯过一次的口径错误，被自己的原始输出当场抓下）。
//   ⚠️ gtceu 原生类型落在本表之外是**正确**的：按 2026-10-03 判据，纸上有催化剂纸时，
//      非山海的机器 ⇒ 真催化剂（无纸时一律消耗，与类型无关）。
var _g5 = []
// ⚠️ 口径同上（2026-09-30）：只看【本次纸上真正用到】的类型 ⇒ 与改动前逐字等价
//    （改动前 TYPE_ID 的 13 个键全部被用到 ⇒ "遍历表"与"遍历用到的"是同一个集合）。
//    这样新加一个 shanhai 类型时，只有【真在纸上用它】那一刻才会被本断言拦下 —— 那正是要拦的时刻。
for (var _g5k in _usedGtIds) if (Object.prototype.hasOwnProperty.call(_usedGtIds, _g5k)) {
    if (SH_JAVA.caps[_g5k] && !SHANHAI_TYPES[_g5k]) _g5.push('gtceu:' + _g5k + '（纸上叫「' + _usedGtIds[_g5k] + '」）')
}
if (_g5.length) {
    throw new Error('[PF] 🔴 这些配方类型由 shanhai 自己注册（真源① ShanhaiRecipeTypes.java 活代码里有），'
        + '却没有写进 SHANHAI_TYPES ⇒ 它们的物质模块会被【静默】当成"非山海机器"落 .notConsumable 真催化剂，'
        + '拒绝写产物：\n        - ' + _g5.join('\n        - ')
        + '\n    ⇒ 修法：把这个 id 补进 gen_kjs.js 的 SHANHAI_TYPES（它是"全部 shanhai 注册类型"的正向表）。'
        + '\n    ⇒ 若它确实不该落"等级门槛"，那要改的是本断言的判据，不是偷偷让兜底生效。')
}
var _g5Sh = 0, _g5Gt = 0, _g5GtNames = []
for (var _g5c in _usedGtIds) if (Object.prototype.hasOwnProperty.call(_usedGtIds, _g5c)) {
    if (SHANHAI_TYPES[_g5c]) _g5Sh++; else { _g5Gt++; _g5GtNames.push('gtceu:' + _g5c) }
}
_g5GtNames.sort()
console.info('[PF] ✅ G5 模块决策表自证：本次纸上用到 ' + Object.keys(_usedGtIds).length + ' 个类型 id，其中落"山海机器"（在 SHANHAI_TYPES 里）的 '
    + _g5Sh + ' 个、落"非山海机器"的 ' + _g5Gt + ' 个（' + _g5GtNames.join(' / ') + '）；'
    + 'shanhai 自己注册（真源①，共 ' + Object.keys(SH_JAVA.caps).length + ' 个）而没写进 SHANHAI_TYPES 的 = '
    + _g5.length + ' 个（不为 0 就抛错，见上）'
    + '　｜　类型判定表本身 = ' + Object.keys(TYPE_ID).length + ' 个中文名（手写别名 ' + Object.keys(TYPE_ID_ALIAS).length
    + ' ＋ 语言文件反查新增 ' + _tnInjected + '）')
// 未知类型的物质模块决策【一次一报】，不刷屏
var UNKNOWN_MODULE_TYPES = {}

// ---------------------------------------------------------------- spec builder
// 🔴 用户 2026-09-26 裁定（甲）：解析不出配方类型的行【跳过并报告】，不再抛错中断整轮生成
var SKIPPED = []
function specFromRow(R) {
    var typeId = TYPE_ID[R.type]
    if (!typeId) { SKIPPED.push({ no: R.no, cell: R.cell, srcType: R.type, outs: (R.outs||[]).length }); return null }
    var s = { r: R, type: typeId, srcTypeName: R.type }

    // 输入分类
    // 🔴 2026-10-03：catModule **就是本轮的判据**（不再只是一个注记）⇒ 连"纸在第几格"一起记下来，
    //    让产物的自报行能指出"按哪张纸判的"（`papers[i].slot`，PF.txt 里 in 的下标）。
    //    ⚠️ 2026-10-03 追加：判据本体已抽到 `moduleRuleOf()`（全脚本唯一实现，emitOldGt 也用同一个）
    //       ⇒ 这里只是**调用**它，不再自己写一份判断（"一条规则两处各写一遍"正是上一轮漏掉 pf/photon 的成因）。
    var _mr = moduleRuleOf(R, typeId)
    var catModule = _mr.catModule, catModuleSlot = _mr.slot
    var catField = null, catQuark = null
    for (var i = 0; i < R.notes.length; i++) {
        var n = R.notes[i].desc.name
        if (n === '力场发生器是催化剂') catField = true
        // 🔴 2026-09-30 新增：纸面原文「夸克释放催化剂作为催化剂」（逐字，末尾无句号）
        if (n === PAPER_QUARK_CATALYST) catQuark = true
    }
    var items = [], mods = []
    for (var a = 0; a < R.real.length; a++) {
        var id = R.real[a].d.id, cnt = R.real[a].d.count || 1
        // 🔴 判据 = 权威 17 项表 MATERIAL_MODULES（**不再是 `/material_module$/` 正则**，那个漏 6 个）
        if (MATERIAL_MODULES[id]) { mods.push({ id: id, cnt: cnt }); items.push({ id: id, cnt: cnt, isModule: true }) }
        else items.push({ id: id, cnt: cnt })
    }
    var itemInputs = [], notConsumable = [], moduleGate = null, moduleFallback = null, flags = []
    /** 本条实际落成"催化剂"的夸克释放催化剂（供 [SHANHAI-DESC] 证据行与产物注释用），元素形如 '64x shanhai:...' */
    var quarkLanded = []
    /** 🔴 2026-10-03：本条实际落成"不消耗"催化剂（`.notConsumable`）的**力场发生器**，
     *  元素形如 '1x gtceu:hv_field_generator'。供"这条族判据到底改了哪几条配方"**现算**用 —— */
    var fieldLanded = []

    // ═══════════════════════════════════════════════════════════════════════════
    // 🔴🔴 2026-10-03 用户点单（原话逐字，**本次唯一的口径**）：
    //   「你又写错了，这条配方物质模块要消耗，而不是催化剂，判断物质模块是否是催化剂仅凭借是否我放了那张纸」
    //   ⇒ 判据 = 【只看纸】，只看纸上有没有那张 `name === '物质模块是催化剂'` 的 NOTE（kind = 'NOTE'）：
    //        · 有纸 ⇒ 【不消耗】，且【形态保持现状】：
    //             - 该配方类型 ∈ SHANHAI_TYPES（山海自己注册的类型）⇒ 落 ModuleLevelCondition 等级门槛（不占输入槽）
    //             - 非山海类型（如土高炉 primitive_blast_furnace）⇒ 落 `.notConsumable(...)` 真催化剂
    //        · 无纸 ⇒ 【消耗】：模块物品**进 itemInputs**（数量照纸上那个格子的数量，通常 1x），
    //             既不挂门槛、也不进 notConsumable
    //   ⚠️ 本条规则【取代】原来的 `consumeModule = isModuleRecipe || (isWorldlineRecipe && !isShardFamilyRecipe)`
    //      —— 那套是"按产出 / 按类型推断"，会把"纸上一句话都没写"的配方【静默】判成不消耗
    //      （用户报的正是这条：no=98 纸上只有「60s / 原初奇点反演」两张纸，模块却被落了门槛）。
    //   ⚠️ isModuleRecipe / isWorldlineRecipe / isShardFamilyRecipe 三个读数【保留】，但
    //      **它们不再决定"要不要消耗"**，只用于诊断与自报：
    //        · isModuleRecipe ⇒ 用于核对"制作物质模块的配方 ⇒ 消耗"这条口径没退化
    //          （说明见 §7①：实测本次 5 条制作物质模块的配方纸上【全都没有】催化剂纸 ⇒ 与"只看纸"天然一致）；
    //        · isWorldlineRecipe / isShardFamilyRecipe ⇒ 只进 specs.json 与产物注释（历史判据留档、可追溯）。
    //   ⚠️ 降级通道【保留】：moduleGate / moduleFallback ⇒ 产物里的 moduleLevelRequirement /
    //      moduleLevelFallbackCatalyst。jar 没绑 / typeof 判不到类时自动退回催化剂形态（配方不会消失）。
    //   ⚠️ 未知类型（不在 SHANHAI_TYPES 里）在有纸时走 `.notConsumable` 真催化剂，
    //      并在控制台报警（UNKNOWN_MODULE_TYPES），不静默。
    // ═══════════════════════════════════════════════════════════════════════════
    var isModuleRecipe = false
    for (var pm = 0; pm < R.outs.length; pm++) {
        var od = R.outs[pm].d
        // 🔴 同上：用权威 17 项表，不用正则（no=62 的产物 material_deduction_module 就不是 material_module 结尾）
        if (od && od.kind === 'ITEM' && od.id && MATERIAL_MODULES[od.id]) isModuleRecipe = true
    }
    var isWorldlineRecipe = !!WORLDLINE_TYPES[typeId]
    // 🔴 2026-10-01（用户拍板"判据 B"）留下的**历史读数** —— ⚠️ 2026-10-03 起它【不再决定】消不消耗，
    //    只进 specs.json 与产物注释（留档、可追溯；"当年为什么这么改"的证据不能丢）。
    //    当时的理由：`worldline_cutting` 这个类型同时命中"世线族"，只看类型分不开"本体"与"运用"。
    var isShardFamilyRecipe = false
    for (var ps = 0; ps < R.outs.length; ps++) {
        var sd = R.outs[ps].d
        if (sd && sd.kind === 'ITEM' && sd.id && SHARD_FAMILY[sd.id]) isShardFamilyRecipe = true
    }
    // 🔴🔴 2026-10-03：判据 = 只看纸（`catModule` = 纸上有没有「物质模块是催化剂」）。
    //    ⚠️ 这一行就是本轮的**全部**决策逻辑 —— 三个 is* 读数只进诊断，不参与这里（见上方长注释）。
    //    ⚠️ 2026-10-03 追加：改成直接取 `moduleRuleOf()` 的结论（唯一实现），本条不再自己算。
    var consumeModule = (_mr.decision === 'consume')
    var moduleDecision = _mr.decision
    // 🔴 2026-10-03：把【被取代的旧判据】（`isModuleRecipe || (isWorldlineRecipe && !isShardFamilyRecipe)`）
    //    的结论也算一遍，纯粹为了**现算"本版到底改了哪几条"**（文件头 §7① 要与它对齐，不手抄）。
    //    ⚠️ 它不参与任何落法；`moduleLandedChanged` = 新旧判据结论不同的那几条。
    var moduleDecisionLegacy = (isModuleRecipe || (isWorldlineRecipe && !isShardFamilyRecipe))
        ? 'consume' : (SHANHAI_TYPES[typeId] ? 'gate' : 'catalyst')
    // 🔴 口径核对（不是决策）：用户要求保留「制作物质模块的配方 ⇒ 消耗」，并"核一遍别让它退化"。
    //    新判据下它与"只看纸"天然一致（那类配方纸上本来就没有催化剂纸）；
    //    万一哪天有人在制作物质模块的配方上放了催化剂纸 ⇒ 两个口径打架 ⇒ 必须【响】，不能静默按纸走。
    if (catModule && isModuleRecipe) {
        flags.push('🔴 口径打架：本条的【产出】里有物质模块（= 用户口径里的"制作物质模块"，应当【消耗】），'
            + '但纸上（第 ' + catModuleSlot + ' 格）写着「物质模块是催化剂」⇒ 按 2026-10-03 判据【只看纸】本条落"不消耗"，'
            + '与"制作物质模块 ⇒ 消耗"那条口径相反 ⇒ **请用户裁决**（生成器不自己选）。')
    }
    var modSeen = 0

    for (var b = 0; b < items.length; b++) {
        var it = items[b]
        if (it.isModule) {
            modSeen = modSeen + 1
            if (moduleDecision === 'consume') {
                // ✅ 无纸 ⇒ 应当消耗：模块留在 itemInputs 里（数量照纸），照常被消耗掉
                itemInputs.push(it.cnt + 'x ' + it.id)
                // 🔴 不变式：consumeModule = !catModule ⇒ 走到这个分支【必然】纸上没有催化剂纸。
                //    这是一条**加载期自检**，不是分支逻辑：万一有人把上面那行判据改坏 ⇒ 这里必须响，
                //    绝不能像 2026-09-28 那版一样"纸写着催化剂、却静默按消费走"（那正是用户报的错的一半）。
                if (catModule) throw new Error('[PF] 🔴 内部不变式被破坏（PF.txt 第 ' + R.no + ' 条 / ' + R.type + '）：'
                    + '本分支（消费）本应只在"纸上无「物质模块是催化剂」"时进入，但本条纸上（第 ' + catModuleSlot + ' 格）【写着】这张纸。'
                    + '\n    ⇒ consumeModule / moduleDecision 的判据被改坏了（2026-10-03 口径 = 只看纸）⇒ 拒绝写产物。')
                continue
            }
            if (moduleDecision === 'gate') {
                if (moduleGate === null) { moduleGate = it.cnt + 'x ' + it.id; moduleFallback = it.cnt + 'x ' + it.id }
                else flags.push('该样板有【多个】物质模块输入（' + moduleGate + ' 与 ' + it.cnt + 'x ' + it.id + '）⇒ 等级门槛只取了第一个。')
                // 🔴 2026-10-03 改文案：不消耗的【原因】现在是"纸上有催化剂纸"，残片族不再是判据 ⇒
                //    这一行只补一句"与 2026-10-01 那版判据的结论也一致"，不再说"判据是因残片族生效的"。
                //    ⚠️ 仍然只在【真的命中残片族】时才打 —— 否则纸写催化剂、产出与非残片族的几十条
                //       会各多出一句 "产出 ∈ 残片族（（无））" 的假话（2026-10-01 我自己真犯过这个错）。
                if (catModule && isShardFamilyRecipe) flags.push('✅ 纸上写了「物质模块是催化剂」⇒ 按 2026-10-03 判据（只看纸）**不消耗**，落等级门槛；'
                    + '本条产出 ∈ 残片族（' + shardFamilyNames(R.outs) + ' =「世线的运用」）'
                    + ' ⇒ 与 2026-10-01 那版判据（看产出）的结论【也一致】，两个口径不打架。')
                continue   // 从 itemInputs 里移走（不能两处都写）
            }
            // moduleDecision === 'catalyst'（非山海的机器）⇒ 真催化剂，不设等级门槛
            notConsumable.push(it.cnt + 'x ' + it.id)
            continue
        }
        // 🔴 2026-10-03 修（defect A）：判据由【写死 LV】改成【认整族】—— 见段首那段长注释。
        //    原来写 `it.id === 'gtceu:lv_field_generator'` ⇒ 纸写了这句、元件却是 MV/HV/…
        //    时**静默失效**（物品照常进 itemInputs 被消耗，且不报错）。
        //    受害配方 = `shanhai:pf/muon`（PF.txt 第 83 条，`gtceu:hv_field_generator`）。
        if (catField && isFieldGeneratorFamily(it.id)) { notConsumable.push(it.cnt + 'x ' + it.id); fieldLanded.push(it.cnt + 'x ' + it.id); continue }
        // 🔴 2026-09-30：纸面原文「夸克释放催化剂作为催化剂」⇒ 该物品落 .notConsumable（不消耗）。
        //    ⚠️ 数量【保留纸上写的那个数】（本次两条都是 64）—— 本轮只改"消不消耗"，
        //       **一个数字都没动**（改数量/概率/门槛等级属于用户的数值领地，见交付报告）。
        //    ⚠️ 只有【纸上写了这句话的那条配方】才生效 ⇒ 第 59/63 条（同样有 ×1 夸克释放催化剂，
        //       但纸上【没有】这句话）保持原样：仍在 itemInputs 里被消耗。
        if (catQuark && QUARK_EMISSION_CATALYSTS[it.id]) {
            notConsumable.push(it.cnt + 'x ' + it.id)
            quarkLanded.push(it.cnt + 'x ' + it.id)
            continue
        }
        itemInputs.push(it.cnt + 'x ' + it.id)
    }
    if (catModule && modSeen === 0) {
        flags.push('纸写「物质模块是催化剂」，但该样板里【没有】任何物质模块物品 ⇒ 催化剂/门槛无从挂起，本条按"无物质模块约束"落。')
    }
    if (moduleDecision === 'catalyst') {
        if (modSeen > 0) UNKNOWN_MODULE_TYPES[typeId] = 1
        flags.push('该配方类型 `gtceu:' + typeId + '` 不在 SHANHAI_TYPES 里（= 非山海的机器）'
            + ' ⇒ 按 2026-10-03 判据（纸上有「物质模块是催化剂」⇒ 不消耗、形态保持现状）'
            + '落 `.notConsumable(...)` 真催化剂（不设等级门槛）。')
    }
    if (catField) {
        // 🔴 2026-10-03：反查判据必须与上面挂载时**同一套**（族判据），否则 HV 那条会继续被
        //    误报成"该样板里【没有】力场发生器物品"—— 那是**假话**（实测它就在输入里，被消耗掉了）。
        //    同时把"/field_generator/ 只测整串文本"换成"先取 id、再走族判据"，与挂载侧逐字同源。
        var hasF = false
        for (var c = 0; c < notConsumable.length; c++) if (isFieldGeneratorFamily(idOfSlotText(notConsumable[c]))) hasF = true
        if (!hasF) flags.push('纸写「力场发生器是催化剂」，但该样板里【没有】力场发生器物品'
            + '（族判据 = `' + FIELD_GENERATOR_FAMILY_RE + '`）⇒ 催化剂无从挂起，本条按"无催化剂"落。')
    }
    // 🔴 2026-09-30：同型的"纸写了、但物品不在"守卫 —— 不静默。
    //    两种情形都要报：①纸上写了这句话、输入里一个夸克释放催化剂都没有；
    //                    ②有夸克释放催化剂、但它不在 QUARK_EMISSION_CATALYSTS 里（很可能是新加的夸克味）。
    if (catQuark) {
        var hasQ = false, otherQ = []
        for (var cq = 0; cq < items.length; cq++) {
            if (/_quark_emission_catalyst$/.test(items[cq].id)) {
                if (QUARK_EMISSION_CATALYSTS[items[cq].id]) hasQ = true
                else otherQ.push(items[cq].id)
            }
        }
        if (!hasQ) {
            flags.push('纸写「' + PAPER_QUARK_CATALYST + '」，但该样板里【没有】任何 QUARK_EMISSION_CATALYSTS（7 项表）里的物品'
                + ' ⇒ 催化剂无从挂起，本条按"无催化剂"落、该物品照常被消耗。'
                + (otherQ.length ? ' ⚠️ 但输入里有名字像夸克的物品 ' + otherQ.join(' / ')
                    + ' ⇒ 它可能是一种【新夸克味】，请把它补进 gen_kjs.js 的 QUARK_EMISSION_CATALYSTS（显式正向表）。' : ''))
        }
    }
    /** 本轮"描述落地"的可 grep 证据行（用户要求：每条落地的描述 ⇒ 一行 [SHANHAI-DESC]）。 */
    var descNote = null
    if (catQuark) {
        descNote = '[SHANHAI-DESC] ' + PAPER_QUARK_CATALYST + ' ⇒ ' + (quarkLanded.length
            ? '落 `.notConsumable(' + quarkLanded.join(' / ') + ')`（**不消耗**，数量照纸上一字未改）'
            : '⚠️ 本条**没有**可挂的夸克释放催化剂 ⇒ 落成"无催化剂"，详见上方 🔴 告警')
    }

    // 🧪 每条含物质模块的配方【自报落法】—— 用户要求"能一眼纠正"（2026-09-28）。
    //    🔴 2026-10-03 改文案（用户点单）：要能看出**它是按哪张纸判的** ⇒ 每行都写明
    //       「纸上（第 N 格）有／没有『物质模块是催化剂』」，并把它当成本条落法的【原因】写出来。
    var moduleNote = null
    if (modSeen > 0) {
        if (moduleDecision === 'consume') {
            var why = '纸上（' + (catModuleSlot === null ? '无' : '第 ' + catModuleSlot + ' 格') + '）'
                + (catModule ? '有' : '【没有】') + '「' + PAPER_MODULE_CATALYST + '」'
            var extra = isModuleRecipe
                ? '；另：本条【产出】里有物质模块（= 属于"制作物质模块"），与"只看纸"的结论【一致】，不冲突'
                : (isWorldlineRecipe ? '；另：本条类型 ∈ 世线族（历史读数，现不参与判定）' : '')
            moduleNote = '🧪 物质模块【消耗·纸上无催化剂纸】：' + why + extra
                + ' ⇒ 按 2026-10-03 判据（只看纸）模块留在 itemInputs 里被正常消耗，不挂催化剂、不设门槛。'
        } else if (moduleDecision === 'gate') {
            // ⚠️ 等级直接从**同一张** MATERIAL_MODULES 里读（值就是等级 1..17）—— 不另抄一份表
            var lvl = moduleGate ? MATERIAL_MODULES[idOfSlotText(moduleGate)] : null
            moduleNote = '🧪 物质模块【不消耗·等级门槛·纸上有催化剂纸】：纸上（第 ' + catModuleSlot + ' 格）放着「' + PAPER_MODULE_CATALYST + '」'
                + '，且类型 ' + typeId + ' ∈ SHANHAI_TYPES（= 山海自己的机器）'
                + ' ⇒ 落 ModuleLevelCondition（要求模块等级 ≥ ' + (lvl === undefined || lvl === null ? '?' : lvl) + '，等级取自 MODULE_LEVELS）。'
                + '⚠️ 门槛【不占输入槽】。若本条应当"只是纯催化剂（.notConsumable）"，改 SHANHAI_TYPES 即可，一处生效。'
        } else {
            moduleNote = '🧪 物质模块【不消耗·真催化剂·纸上有催化剂纸】：纸上（第 ' + catModuleSlot + ' 格）放着「' + PAPER_MODULE_CATALYST + '」'
                + '，但类型 ' + typeId + ' 不在 SHANHAI_TYPES 里（= 非山海的机器）'
                + ' ⇒ 落 .notConsumable(...) 真催化剂，不设等级门槛。'
        }
    }

    var inFluids = [], outFluids = [], outItems = [], chanced = []
    for (var d = 0; d < R.fluids.length; d++) inFluids.push(R.fluids[d].d.id + ' ' + R.fluids[d].d.amount)
    for (var e = 0; e < R.outs.length; e++) {
        var o = R.outs[e].d
        if (o.kind === 'FLUID') { outFluids.push(o.id + ' ' + o.amount); continue }
        // 纸「电子中微子产出概率5%」写在 out 里、紧邻产出 shanhai:electron_neutrino 的那一格（下标每次现算）
        var isChanced = false
        for (var f = 0; f < (R.outNotes || []).length; f++) {
            if (/电子中微子产出概率5%/.test(R.outNotes[f].desc.name) && o.id === 'shanhai:electron_neutrino') isChanced = true
        }
        if (isChanced) { chanced.push({ item: (o.count || 1) + 'x ' + o.id, chance: 500, tierChanceBoost: 100 }); flags.push('纸「电子中微子产出概率5%」⇒ ' + o.id + ' 改成 chancedOutput(500, 100)。'
            + '⚠️ 单位：本包 chance 是【万分比】，10000=100% ⇒ 5% = 500（不是 5000）。'
            + '⚠️ 第二个 int 不是"加成上限"，是【每超频一级的加成量 tierChanceBoost】'
            + '（字节码实证：GTRecipeBuilder.chancedOutput 把 iload_3 写进字段 tierChanceBoost）。'
            + '100 = GTCEu 自己"5% 档副产"的标准值（本包 414 条实际配方 chance=500/boost=100）。'
            + '用户 2026-09-26 裁决：吃加成 ⇒ 第二参不能是 0，本文件取 100。') ; continue }
        outItems.push((o.count || 1) + 'x ' + o.id)
    }

    var circuit = 0
    if (R.circuits.length) {
        circuit = R.circuits[0].n
        if (R.circuits.length > 1) flags.push('该样板有 ' + R.circuits.length + ' 张编程电路，只取了第一张 Configuration=' + circuit)
    }

    var dur = 0
    if (R.time) dur = parseInt(R.time, 10) * 20
    else flags.push('纸上【没有】耗时纸 ⇒ duration 无法确定。')

    var EUt = CELL_EUT[R.cell]
    // 🔴 2026-09-27 用户报的 bug（原话逐字）：「有个bug，土高炉那些配方应该是没有电力要求的，就和原版的炼钢一样」
    //    取证：原版 `gtceu:primitive_blast_furnace` 的配方 JSON【根本没有 EUt 字段】
    //      （样本 `export/recipes/gtceu/primitive_blast_furnace/steel_from_charcoal_block.json`
    //        = {"type":"gtceu:primitive_blast_furnace","duration":16,"inputs":{…}} —— 无 EUt；
    //        原版该类 18 条配方里 EUt 字段【一条都没有】）⇒ 表默认 0 ⇒ 就是"不用电"。
    //    🔴 判据刻意用【类型】而不是 id 列表 ⇒ 用户以后再加土高炉配方也自动对（硬编码 id 会漏）。
    //    ⚠️ 只改 `primitive_blast_furnace`；`primordial_matter_recombination`（_pmr 那批）是【山海的电机】，
    //       不动（用户在裁决中）。
    if (typeId === 'primitive_blast_furnace') {
        if (EUt !== 0) {
            flags.push('🔴 土高炉配方：用户 2026-09-27 裁决「应该是没有电力要求的，就和原版的炼钢一样」\n'
                + '⇒ EUt 由元件默认的 ' + EUt + ' 改为 **0**（原版 primitive_blast_furnace 的 JSON 里根本没有 EUt 字段）。')
        }
        EUt = 0
    }
    if (CELL_EUT_FLAG[R.cell]) flags.push('元件「处理样板-星门」：用户最新裁决（原话逐字）「溢出那就算了，改成 max+8=max,4^8A」\n'
        + '⇒ **MAX+8 = MAX 电压 + 4^8 安培**。\n'
        + '独立验算：V[MAX] = 2147483648（= 2^31，jar 字节码真值）；4^8 = 2^16 = 65536\n'
        + '⇒ EUt = 2^31 × 2^16 = **2^47 = 140737488355328**，只有 Long.MAX（2^63−1）的 1/65536 ⇒ **不溢出**。\n'
        + '⚠️ 上一版口径（4^16）算出 2^63 = Long.MAX+1 ⇒ 回绕成负数，那正是当时只写 V[MAX] 的原因；本版已解除。\n'
        + '完整算式、字节码取证与作废留档见文件头 §8。')
    if (UNRESOLVED_TYPE[R.type]) flags.push('配方类型中文名「' + R.type + '」在 lang 里【没有精确命中】，暂用近似候选 `gtceu:' + typeId + '`（lang 实为「世线板电路组装」），待用户裁决。')

    // 槽位核对（用真源现算的 setMaxIOSize）
    // 🔴 2026-09-29：这里原先写 `CAP[typeId] || [99, 99, 99, 99]` —— 那个兜底值就是
    //    "19 条原初物质重组配方的自检形同虚设"的成因（缺键 ⇒ 上限 99 ⇒ 永不报超）。
    //    G2 已在加载期保证"用到的类型一定有上限"，所以这里**不再兜底，直接抛**：
    //    兜底值只能让检查器假装在工作，而"检查器假装在工作"比"没有检查器"更危险。
    var cap = CAP[typeId]
    if (!cap) throw new Error('[PF] 🔴 内部错误：配方类型 ' + typeId + '（PF.txt 第 ' + R.no + ' 条）没有 IO 上限。'
        + '加载期的 G2 断言本应 already 拦下 ⇒ 说明有人绕过/改坏了 CAP 合成逻辑，拒绝写产物。')
    var slotIn = itemInputs.length + notConsumable.length + (circuit > 0 ? 1 : 0)
    var slotOut = outItems.length + chanced.length
    var over = (slotIn > cap[0]) || (slotOut > cap[1]) || (inFluids.length > cap[2]) || (outFluids.length > cap[3])

    return {
        row: R, type: typeId, srcTypeName: R.type, itemInputs: itemInputs, notConsumable: notConsumable,
        moduleGate: moduleGate, moduleFallback: moduleFallback, circuit: circuit,
        // 🔴 2026-09-28 诊断字段（只进 specs.json 做对账，**不进 KJS 产物**）：
        //    moduleDecision = 'consume' | 'gate' | 'catalyst' ⇒ 这条配方的物质模块怎么落
        hasModuleInput: modSeen > 0, moduleDecision: moduleDecision, moduleNote: moduleNote,
        // 🔴 2026-10-03 新增（同样只进 specs.json）：判据的**输入与旧结论**，供对账/负面对照用
        //    · hasModuleCatalystPaper / moduleCatalystPaperSlot = 那张纸在不在、在第几格（本轮唯一判据）
        //    · moduleDecisionLegacy = 被取代的旧判据（产出/类型推断）会给出什么
        //    · moduleLandedChanged = 新旧判据结论不同 ⇒ **本版相对上一版落法真的变了的**就是这些条
        hasModuleCatalystPaper: !!catModule, moduleCatalystPaperSlot: catModuleSlot,
        moduleDecisionLegacy: moduleDecisionLegacy,
        moduleLandedChanged: moduleDecisionLegacy !== moduleDecision,
        // 🔴 2026-09-30「描述落地」证据（只进 specs.json 与产物注释，不进配方数据）：
        quarkCatalystLanded: quarkLanded, descNote: descNote,
        // 🔴 2026-10-03「力场发生器族判据」证据（只进 specs.json 与产物注释，不进配方数据）：
        fieldGeneratorLanded: fieldLanded,
        isModuleRecipe: isModuleRecipe, isWorldlineRecipe: isWorldlineRecipe,
        // 🔴 2026-10-03 补：`isShardFamilyRecipe` 原本只在 specFromRow 内部用（不返回）
        //    ⇒ 文件头那句"本次命中它的 N 条"读 specs[i].isShardFamilyRecipe 全是 undefined ⇒ **打印出一个假 0**
        //    （实测：真正的条数是 1，`shanhai:pf/thread_shard_1`）。本轮把它挂出来，供文件头与对账脚本现算。
        isShardFamilyRecipe: isShardFamilyRecipe,
        inputFluids: inFluids, itemOutputs: outItems, outputFluids: outFluids, chancedOutputs: chanced,
        duration: dur, EUt: EUt, flags: flags,
        slots: { itemIn: slotIn, itemOut: slotOut, fluidIn: inFluids.length, fluidOut: outFluids.length, cap: cap, over: over }
    }
}

// ---------------------------------------------------------------- split old / new
var newRows = [], oldRows = []
for (var i = 0; i < rows.length; i++) { if (rows[i].old) oldRows.push(rows[i]); else newRows.push(rows[i]) }
// ═══════════════════════════════════════════════════════════════════════════════
// 🔴🔴 全文件【唯一允许写死】的一组数字：上一版 PF.txt（2026-09-26 那版）的历史留档。
//    旧源文件早已被用户覆盖 ⇒ **算不出来**，只能留档；所以它不是"过期数字"，是"史料"。
//    凡本文件/产物里出现"上次 N 条"字样，一律**从这里派生**，不许再另写一个字面量。
//      · totalPatterns —— 上一版 PF.txt 解析出的样板总数（38）
//      · byCell        —— 上一版各元件的条数（合计 = 上一版那 8 条旧配方）
//      · byCellSlot    —— 上一版元件的槽位（用于说明"槽位后移"）
//    ⚠️ 与 gen_manifest.js 的 LAST / lastSlot 是同一份历史（那边是样板清单，这边是产物文件头）。
// ═══════════════════════════════════════════════════════════════════════════════
var LAST_PF = {
    totalPatterns: 38,
    byCell: { '处理样板ULV': 1, '合成样板': 5, '处理样板LV': 2 },
    byCellSlot: { '处理样板ULV': 0, '合成样板': 1, '处理样板LV': 2 }
}
var LAST_LEGACY = 0
for (var _lk2 in LAST_PF.byCell) LAST_LEGACY += LAST_PF.byCell[_lk2]     // = 8（派生）
if (oldRows.length !== LAST_LEGACY) throw new Error('旧配方条数变了：期望 ' + LAST_LEGACY + '（= 上一版 PF.txt 的历史留档），实际 ' + oldRows.length)
// ⚠️ 原为 if (newRows.length !== 30) throw ... —— 用户 2026-09-26 裁定：降级成【打印警告】
//    理由：用户会持续往 PF.txt 加样板，硬断言会把每次同步都拦死；但期望值仍要打印出来做对照。
//    🔴 2026-09-29：期望值不再写死 —— 由上面那份历史派生：上次总数 − 旧配方数。
var EXPECTED_NEW = LAST_PF.totalPatterns - LAST_LEGACY
if (newRows.length !== EXPECTED_NEW) {
  console.info('[PF] 条数变化: ' + EXPECTED_NEW + '(= 上次总数 ' + LAST_PF.totalPatterns + ' − 旧配方 ' + LAST_LEGACY + ') -> ' + newRows.length + '  (old=' + oldRows.length + ', 合计=' + (oldRows.length + newRows.length) + ')')
} else {
  console.info('[PF] 新增条数 = ' + newRows.length + ' (与期望一致)')
}

// ---------------------------------------------------------------- recipe ids
var used = { 'shanhai:pf/primordial_omega_engine': 1, 'shanhai:pf/photon': 1, 'shanhai:pf/first_light': 1 }
function mkId(spec) {
    var base = null
    for (var k = 0; k < spec.itemOutputs.length; k++) {
        var m = /^(\d+)x ([a-z0-9_]+):([a-z0-9_\/]+)$/.exec(spec.itemOutputs[k])
        if (m) { base = m[3]; break }
    }
    if (!base) base = spec.type + '_' + spec.row.no
    // 🔴 2026-09-27：pmr 副本（土的工炉⇒原初物质重组那批）加 _pmr 后缀，一眼能认出是副本；
    //   其余一律不加（用户口径：老配方 id 不动）。撞车时下面 while 再补 _2。
    var id = 'shanhai:pf/' + base + ((spec.row && spec.row.copiedFrom) ? '_pmr' : '')
    var n = 2
    while (used[id]) { id = 'shanhai:pf/' + base + '_' + n; n++ }
    used[id] = 1
    return id
}
var specs = []
for (var wi = 0; wi < newRows.length; wi++) { var sp = specFromRow(newRows[wi]); if (!sp) continue; sp.id = mkId(sp); specs.push(sp) }

// ═══════════════════════════════════════════════════════════════════════════════
// 🔴🔴 2026-09-30 交叉核对：加载期【预测】的跳过数 vs 生成期【实际】跳过数
// ═══════════════════════════════════════════════════════════════════════════════
// 为什么要有这一步：加载期那条断言判的是"TYPE_ID 表里认不认识这些中文名"，
//   而真正决定配方丢不丢的是 `specFromRow` 实际有没有返回 null。
//   两条路径的来源不同（一条看类型表，一条看 spec 构建过程）⇒ 两边都用同一个数说话才算自证。
//   ⚠️ 必须放在【写产物之前】—— 顶部写 `fs.writeFileSync(TARGET, …)` 之前。
//      原来这段报告在文件末尾（写完产物之后），那时就算发现不对，产物也已经落盘了。
var _skipWbX = [], _skipRealX = []
for (var _sk = 0; _sk < SKIPPED.length; _sk++) (SKIPPED[_sk].srcType ? _skipRealX : _skipWbX).push(SKIPPED[_sk])
console.info('[PF] ✅ 合成样板分流 ' + _skipWbX.length + ' 条（走工作台路径，正常）　＝ 加载期预测 ' + TD_SHAPED.length + ' 条'
    + (_skipWbX.length === TD_SHAPED.length ? '　✓ 两条路径一致' : '　🔴 不一致'))
if (_skipRealX.length) {
    // 加载期断言本应已经拦下 ⇒ 走到这里说明有人绕过/改坏了那条断言
    throw new Error('[PF] 🔴 内部错误：仍有 ' + _skipRealX.length + ' 条"有类型却生成不出 spec"的行，'
        + '但加载期断言没有拦住 ⇒ 说明断言被绕过或改坏了，拒绝写产物：\n'
        + _skipRealX.map(function (s) { return '    · PF 第 ' + s.no + ' 条｜元件「' + s.cell + '」｜类型「' + s.srcType + '」' }).join('\n'))
}
if (_skipWbX.length !== TD_SHAPED.length) {
    throw new Error('[PF] 🔴 内部错误：加载期预测的分流 ' + TD_SHAPED.length + ' 条 ≠ 实际跳过 ' + _skipWbX.length
        + ' 条 ⇒ 两条判据不一致，说明其中一条坏了，拒绝写产物。')
}

// ─────────────────────────────────────────────────────────────────────────────
// 🔴 2026-09-27 用户逐字点单：「给铁锭变成锻铁锭的那两个配方都加上编程电路，
//    其中土高炉1号电路，原初物质重组改为30号电路」
//    🔴 判据刻意用【id】精确匹配 ⇒ 【绝不写成"类型级"规则】
//      （类型级会波及同族：土高炉另外 13 条、pmr 另外 13 条）
//      · 土高炉 14 条现在全是 circuit 0 ⇒ 改完只有 wrought_iron_ingot 是 1，其余 13 条仍必须 0
//      · pmr 14 条现在全是 circuit 31（来自 RE_TYPE_COPIES）⇒ 改完只有 _pmr 那条是 30，其余 13 条仍必须 31
//    ⚠️ 放在 mkId 之后 —— 因为判据用的是最终 id（_pmr 后缀由 mkId 按 copiedFrom 加）。
// ─────────────────────────────────────────────────────────────────────────────
var CIRCUIT_BY_ID = {
    'shanhai:pf/wrought_iron_ingot': 1,
    'shanhai:pf/wrought_iron_ingot_pmr': 30
}
var circuitPatchLog = []
for (var cp = 0; cp < specs.length; cp++) {
    var cid = specs[cp].id
    if (Object.prototype.hasOwnProperty.call(CIRCUIT_BY_ID, cid)) {
        circuitPatchLog.push(cid + ' circuit ' + specs[cp].circuit + ' -> ' + CIRCUIT_BY_ID[cid])
        specs[cp].circuit = CIRCUIT_BY_ID[cid]
    }
}
console.log('[PF-CIRCUIT] 按 id 覆写编程电路 ' + circuitPatchLog.length + ' 条： ' + circuitPatchLog.join(' | '))

// ═══════════════════════════════════════════════════════════════════════════════
// 🔴 2026-09-29：文件头里那些「本次 N 条 / 上次 M 条 / 79 条 / SHA256 = …」原来**全是写死的**
//    （2026-09-27 那次运行的实测值）⇒ 早就过期，产物里印的是假数字。
//    现在一律【现算】：条数来自 rows / specs，源文件摘要来自 PF.txt 本体。
// ═══════════════════════════════════════════════════════════════════════════════
var crypto = require('crypto')
// PF_SRC 已在文件顶部由环境变量 SH_PF_SRC 解析（见顶部「路径来源纪律」块）—— 此处不再声明。
// parsed.json 是【可选】依赖（由 parse_pf.js 产出，与 rows.json 同一轮）：
//   只用来印两条外部证据（原文出现次数、元件槽位）；读不到就如实说明读不到，绝不编数字。
var PARSED = null
try { PARSED = JSON.parse(fs.readFileSync(BASE + 'parsed.json', 'utf8')) } catch (e) { PARSED = null }
// 🔴 引用方自检②：parsed.json 也必须对应当前 PF.txt（它是可选依赖，但**读到就必须是新的**）。
if (PARSED) PROV.check(BASE + 'parsed.json', 'gen_kjs.js(读 parsed.json)')
var PFC = (function () {
    var perCell = {}, perCellOld = {}, perType = {}, perTypeOld = {}
    var wb = 0, gt = 0, skipped = 0, lenOK = 0, gtOld = 0, wbOld = 0, copied = 0
    for (var i = 0; i < rows.length; i++) {
        var R = rows[i]
        if (R.inLen === 81 && R.outLen === 27) lenOK++
        if (R.copiedFrom !== undefined) copied++
        perCell[R.cell] = (perCell[R.cell] || 0) + 1
        if (R.old) perCellOld[R.cell] = (perCellOld[R.cell] || 0) + 1
        if (!R.type) { wb++; if (R.old) wbOld++; continue }    // 无配方类型 ⇒ 工作台（合成样板）
        if (!TYPE_ID[R.type]) { skipped++; continue }          // 解析不出类型 ⇒ 会被跳过
        gt++
        if (R.old) gtOld++
        perType[R.type] = (perType[R.type] || 0) + 1
        if (R.old) perTypeOld[R.type] = (perTypeOld[R.type] || 0) + 1
    }
    return {
        total: rows.length, wb: wb, gt: gt, skipped: skipped, gtOld: gtOld, wbOld: wbOld, copied: copied,
        oldN: oldRows.length, newN: newRows.length, lenOK: lenOK,
        perCell: perCell, perCellOld: perCellOld, perType: perType,
        cellOrder: Object.keys(perCell),
        gtSpecs: specs.length,
        // 🔴 2026-09-29：星门条数原来在文件头写死"星门 3 条"⇒ 改为现算（判据与落地 EUt 时同一个函数）。
        stargate: (function () { var n = 0; for (var i = 0; i < rows.length; i++) if (rows[i].type && GT_VOLTAGE.isStargateCell(rows[i].cell)) n++; return n })(),
        // 🔴 2026-09-29：AE2 定长格数（原来在下面 §0 写死成 81 / 27）改为现算 —— 取第一行的长度。
        inLen: rows.length ? rows[0].inLen : 0,
        outLen: rows.length ? rows[0].outLen : 0,
        srcSha: crypto.createHash('sha256').update(fs.readFileSync(PF_SRC)).digest('hex').toUpperCase(),
        srcSize: fs.statSync(PF_SRC).size
    }
})()
// 自检：specs 只从 **newRows** 生成（老 ①⑦⑧ 是逐字搬的文本，不进 specs）
// ⇒ 判据必须是 specs.length === (带类型行数 − 带类型的旧行数)，不是「带类型行数」。
//    ⚠️ 2026-09-29 第一版这里写错了（拿 65 去比 62），当场被自己拦下 —— 保留这条记录，
//       因为它正是本项目"检查器自己的假设错了"的又一例。
var PFC_GT_NEW = PFC.gt - PFC.gtOld
if (PFC.gtSpecs !== PFC_GT_NEW) {
    throw new Error('[PF] 🔴 静默丢条：应生成 ' + PFC_GT_NEW + ' 条 GT spec（带类型 ' + PFC.gt + ' − 带类型的旧配方 ' + PFC.gtOld
        + '），实际 ' + PFC.gtSpecs + ' 条（差 ' + (PFC_GT_NEW - PFC.gtSpecs) + '）')
}
// 表头对齐用：CJK 按 2 列宽算
function dispWidth(s) { var n = 0; for (var i = 0; i < s.length; i++) n += (s.charCodeAt(i) >= 0x2E80 ? 2 : 1); return n }
function padDisp(s, n) { var d = n - dispWidth(s); return s + (d > 0 ? new Array(d + 1).join(' ') : '') }
// 🔴 2026-09-29：产物里凡写"#NN 那几条"的地方，一律改成**现算行号**（行号随 PF.txt 变，写死必然过期）。
//    例：原先写死的 #31/#33/#36，在 2026-09-29 这次 PF.txt 里实际是 #45/#47/#51。
function noteRowNos(noteName) {
    var a = []
    for (var i = 0; i < rows.length; i++) {
        for (var j = 0; j < rows[i].notes.length; j++) if (rows[i].notes[j].desc.name === noteName) { a.push(rows[i].no); break }
    }
    return a
}
function outNoteRowNos(re) {
    var a = []
    for (var i = 0; i < rows.length; i++) {
        for (var j = 0; j < rows[i].outNotes.length; j++) if (re.test(rows[i].outNotes[j].desc.name)) { a.push(rows[i].no); break }
    }
    return a
}
function nosText(a) { return a.length ? a.map(function (x) { return '#' + x }).join(' / ') + '（' + a.length + ' 条）' : '（本次一条都没有）' }
/** 同 nosText，但元素是【已经写好的整段文字】（不是行号）—— 用于"本版改了哪几条"这种现算清单。 */
function nosText2(a) { return a.length ? a.join('；') + '（' + a.length + ' 条）' : '（本次一条都没有 —— 新旧判据结论完全一致）' }
var NOS_FIELD = noteRowNos('力场发生器是催化剂')
var NOS_CHANCED = outNoteRowNos(/电子中微子产出概率5%/)
var NOS_CATALYST_PAPER = noteRowNos(PAPER_MODULE_CATALYST)
// 🔴 2026-09-30 新增：纸面原文「夸克释放催化剂作为催化剂」（逐字，末尾无句号）
var NOS_QUARK_CATALYST = noteRowNos(PAPER_QUARK_CATALYST)
// 真正出纸的样板（产物理就是 minecraft:paper，不是"改名纸当注记"）—— 原先产物里写死了 "#25"
var NOS_REAL_PAPER = []
for (var _rp = 0; _rp < rows.length; _rp++) {
    for (var _rq = 0; _rq < rows[_rp].outs.length; _rq++) {
        if (rows[_rp].outs[_rq].d.id === 'minecraft:paper') { NOS_REAL_PAPER.push(rows[_rp].no); break }
    }
}
// 「电子中微子产出概率5%」那张纸在 out 里的下标（写死的 out[3] 也要现算）
var CHANCED_OUT_SLOT = null
for (var _cs = 0; _cs < rows.length && CHANCED_OUT_SLOT === null; _cs++) {
    for (var _cq = 0; _cq < rows[_cs].outNotes.length; _cq++) {
        if (/电子中微子产出概率5%/.test(rows[_cs].outNotes[_cq].desc.name)) { CHANCED_OUT_SLOT = rows[_cs].outNotes[_cq].slot; break }
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// 🔴🔴 2026-10-03 追加（用户点单）：老 3 条 GT 配方的【源样板绑定 + 模块落法现算】
// ═══════════════════════════════════════════════════════════════════════════════
// 【根因】为什么 `shanhai:pf/photon` 没被「只看纸」覆盖（逐行查实，不是猜）：
//   ① rows.json 里 8 个 `old: true` 的行被 L1340 分成 `oldRows`，而 `specs` **只由 `newRows` 构建**
//      （L1386 `for (wi < newRows.length)`）⇒ **`specFromRow()`（判据所在）从没在这些行上跑过**；
//   ② 它们的配方由 `emitOldGt()` 用**冻结的字符串文本**发出：老 ⑦ 那条（= PF 第 36 条 = `shanhai:pf/photon`）
//      把 `moduleLevelRequirement: '1x shanhai:introductory_material_module'` 与 `itemInputs: []` 直接写死
//      ⇒ 纸上的信息**根本进不到这条配方**；
//   ③ `var used = {…'shanhai:pf/photon':1…}`（L1369）只是**占 id**，不代表"这三条是孤儿"。
// 【修法】把"源样板绑定 + 判据"提到**顶层**（这样文件头也能现算它），emitOldGt 只负责排版：
//   `LEGACY_GT_BIND` 显式绑定 id → 样板行号，加载期逐条自证（行存在 / old=true / circuit 与产出对得上）。
var LEGACY_GT_BIND = {
    // id → 它抄自哪个样板行（行号**现查 rows.json**，不凭记忆）
    'shanhai:pf/primordial_omega_engine': { no: 21, circuit: 0, product: 'shanhai:primordial_omega_engine', typeId: 'circuit_assembler' },
    'shanhai:pf/photon': { no: 36, circuit: 2, product: 'shanhai:photon', typeId: 'photon_siphon' },
    'shanhai:pf/first_light': { no: 27, circuit: 1, product: 'shanhai:first_light', typeId: 'photon_siphon' }
}
/** 按绑定取源样板行，并逐条自证 —— 对不上就抛（绝不静默换一条配方，也绝不静默丢掉模块）。 */
function legacyGtRow(id) {
    var b = LEGACY_GT_BIND[id]
    if (!b) throw new Error('[PF] 🔴 内部错误：老配方 ' + id + ' 没有绑定源样板行 ⇒ 它的模块落法无法现算。')
    var R = null
    for (var i = 0; i < rows.length; i++) if (rows[i].no === b.no) { R = rows[i]; break }
    if (!R) throw new Error('[PF] 🔴 老配方 ' + id + ' 绑定的 PF 第 ' + b.no + ' 条在 rows.json 里【不存在】'
        + ' ⇒ 绑定过期了（PF.txt 重新导出后行号可能漂移）⇒ 拒绝写产物。')
    if (!R.old) throw new Error('[PF] 🔴 老配方 ' + id + ' 绑定的 PF 第 ' + b.no + ' 条【不是】old 行 ⇒ 绑错行了。')
    var circ = (R.circuits && R.circuits.length) ? R.circuits[0].n : 0
    if (circ !== b.circuit) throw new Error('[PF] 🔴 老配方 ' + id + ' 的 circuit 绑定不符：正文写死 ' + b.circuit
        + '，而 PF 第 ' + b.no + ' 条纸上是 ' + circ + ' ⇒ 拒绝写产物（说明绑错了行）。')
    var hasProduct = false
    for (var j = 0; j < R.outs.length; j++) if (R.outs[j].d.id === b.product) hasProduct = true
    if (!hasProduct) throw new Error('[PF] 🔴 老配方 ' + id + ' 的产出绑定不符：PF 第 ' + b.no + ' 条的产出里【没有】'
        + b.product + ' ⇒ 绑错了行。')
    return R
}
/**
 * 按「只看纸」现算一条老配方的模块落法（与 specFromRow **共用** `moduleRuleOf`，判据只有一份）。
 * @returns {{id:string,no:number,decision:string,catModule:boolean,slot:(number|null),
 *            gateLines:string[], itemInputsPrefix:string[], note:string}}
 *   · decision='consume' ⇒ `gateLines` 为空（**门槛行与降级行一起消失**）、`itemInputsPrefix` = 模块文本（可能为空）
 *   · decision='gate'    ⇒ `gateLines` = 门槛行 + 降级行、`itemInputsPrefix` = 空（itemInputs 保持原样）
 */
function legacyGtRule(id) {
    var b = LEGACY_GT_BIND[id]
    var R = legacyGtRow(id)
    var rule = moduleRuleOf(R, b.typeId)
    var slots = moduleSlotsOfRow(R)
    var spec = '按 2026-10-03 判据（只看纸）模块留在 itemInputs 里被正常消耗，不挂催化剂、不设门槛。'
    if (rule.decision !== 'consume') spec = '落 ModuleLevelCondition 等级门槛（不占输入槽）。'
    var note = '// 🧪 物质模块【' + (rule.decision === 'consume' ? '消耗·纸上无催化剂纸' : '不消耗·等级门槛·纸上有催化剂纸') + '】：'
        + rule.why + ' ⇒ ' + spec
        + '｜⚠️ 本条是老配方（正文由 emitOldGt 硬编码）；2026-10-03 追加：它的模块落法改为按源样板'
        + '（PF 第 ' + R.no + ' 条）的纸**现算**，不再写死。'
    if (rule.decision === 'consume') {
        return { id: id, no: R.no, decision: rule.decision, catModule: rule.catModule, slot: rule.slot, gateLines: [], itemInputsPrefix: slots, note: note }
    }
    // 纸上有催化剂纸 ⇒ 不消耗；形态沿用 2026-09-29 起的既有形态（门槛 + 条件类不可用时的降级催化剂）
    if (!slots.length) throw new Error('[PF] 🔴 老配方 ' + id + '（PF 第 ' + R.no + ' 条）纸上有「'
        + PAPER_MODULE_CATALYST + '」却【没有物质模块物品】⇒ 门槛无从挂起 ⇒ 拒绝写产物，请用户裁决。')
    return {
        id: id, no: R.no, decision: rule.decision, catModule: rule.catModule, slot: rule.slot,
        gateLines: [
            '    moduleLevelRequirement: \'' + slots[0] + '\',',
            '    // ⚠️ 降级用：条件类不可用时退回催化剂形态（宁可比原来差，也不能让配方消失）',
            '    moduleLevelFallbackCatalyst: \'' + slots[0] + '\','
        ],
        itemInputsPrefix: [], note: note
    }
}
/** 老 3 条的固定顺序（报告/文件头都用它遍历 —— 不依赖 `for…in` 的顺序）。 */
var LEGACY_GT_ORDER = ['shanhai:pf/primordial_omega_engine', 'shanhai:pf/photon', 'shanhai:pf/first_light']
/** 老 3 条的模块落法读数（顶层算一次，文件头与控制台都用它 —— 不两处各算一遍）。 */
var LEGACY_GT_RULES = {}
for (var _lgi = 0; _lgi < LEGACY_GT_ORDER.length; _lgi++) LEGACY_GT_RULES[LEGACY_GT_ORDER[_lgi]] = legacyGtRule(LEGACY_GT_ORDER[_lgi])
// ⚠️ 老 ①（电路组装机）与老 ⑧（first_light）的源样板上【本来就没有】物质模块，正文是冻结文本、
//    里面没有模块落法段 ⇒ 一旦将来给这两条补上模块（或行号漂移绑错行），这里必须【响】：
//    静默丢掉一个模块 = 让玩家白烧一个模块而不报错。
for (var _lgj = 0; _lgj < LEGACY_GT_ORDER.length; _lgj++) {
    var _lg2 = LEGACY_GT_ORDER[_lgj]
    if (_lg2 === 'shanhai:pf/photon') continue
    var _rlg = legacyGtRow(_lg2), _slg = moduleSlotsOfRow(_rlg)
    if (_slg.length) throw new Error('[PF] 🔴 老配方 ' + _lg2 + '（PF 第 ' + _rlg.no + ' 条）的源样板现在有物质模块输入（'
        + _slg.join(' / ') + '），但它的正文是冻结文本、没有模块落法段 ⇒ 拒绝写产物：'
        + '请让它也走 legacyGtRule() 的排版（即像老 ⑦ 那样把模块段落接出来）。')
}
console.info('[PF] ✅ 老 3 条 GT 配方的模块落法（按源样板的纸现算）：'
    + LEGACY_GT_ORDER.map(function (k) {
        var r = LEGACY_GT_RULES[k]
        return k + ' ⇐ PF#' + r.no + ' ⇒ ' + r.decision + (r.itemInputsPrefix.length ? '（itemInputs +' + r.itemInputsPrefix.join('/') + '）' : '')
    }).join('　｜　'))

// ---------------------------------------------------------------- emit KJS
var L = []
function w(s) { L.push(s === undefined ? '' : s) }
// 🔴 2026-09-29：文件头要印「工作台 N 条」，但 SHAPED 数组要到本脚本后半段才建出来
//    ⇒ 先用占位符，末尾回填（回填后还会自检"一个占位符都不剩"）。
//    ⚠️ 为什么不用"先写死 18"：那正是本文件原来 5 处假数字的病根（PF.txt 一变就错）。
var PH_SHAPED = '\u0001PH:SHAPED_COUNT\u0001'
var PH_SHAPED_HARD = '\u0001PH:SHAPED_HARD\u0001'
var PH_TOTAL = '\u0001PH:TOTAL\u0001'

w('// priority: 1')
w('// =============================================================================')
w('// [server_scripts]shanhai_recipes.js  —— 用户「游戏里编好的 AE 样板」落地为 KubeJS')
w('//')
w('// ✅ 2026-09-26 【终版 · FINAL】：本文件原先挂着的问题【三处全部落定】——')
w('//    ① 新类型「原初物质解构」= `gtceu:primordial_matter_deconstruction`（用户裁决「就用这个」）；')
w('//    ② `photon_separation` 的 setMaxIOSize 由 (2,4,2,2) **放宽到 (4,10,2,2)**（用户裁决：物品入 2→4、物品出 4→10、流体 2/2 不动）；')
w('//    ③ 星门 ' + PFC.stargate + ' 条的 EUt 按「**MAX+8** = MAX 电压 + 4^8 A」= **2^47 = 140737488355328**（用户裁决；条数**现算**）。')
w('//    ⇒ 后果：**2026-09-26 放宽后不再溢出** —— 原先那 3 条 SLOT-OVER 溢出**已消除**，')
w('//       代际产物里**既没有 `slotOver` 字段、也没有 SLOT-OVER 告警代码**（整段已删）。')
w('//    ✅ 2026-09-27：【已部署】到 GTL山海9.10test\\kubejs\\server_scripts\\')
w('//    ⚠️ 「原初物质解构」这个配方类型是**本版 shanhai 模组新增**的（jar 侧已注册 + 有专属 .rtui），')
w('//       本文件没有也不需要有它的配方；它只是让 JEI/机器多出一个可用分类。')
w('//')
w('// 🔴 本次是【增量更新】（用户原话逐字：「这次添加配方就和上次一样啊，上次我还帮你理解了」')
w('//    ／「每个元件都有新增的配方」）：')
w('//    · 老那 ' + PFC.oldN + ' 条【原样保留、一个字不改】（它们的 spec 从上一版文件逐字搬过来）；')
w('//    · 新增 ' + PFC.newN + ' 条【追加】进同一个文件。')
w('//    ⇒ 条数（**本次现算**，不再写死）：工作台 ' + PH_SHAPED + ' 条 + GT 机器 ' + PFC.gt + ' 条 = ' + PH_TOTAL + ' 条。')
w('//       其中 GT 那 ' + PFC.gt + ' 条 = 本文件生成的 ' + PFC.gtSpecs + ' 条 ＋ 老配方逐字保留的 ' + PFC.gtOld + ' 条。')
w('//       ⚠️ 工作台那部分 = 文件里硬编码的 ' + PH_SHAPED_HARD + ' 条 ＋ 从 PF.txt 的「合成样板」现算生成的条数；')
w('//          两者都在本脚本跑完前才算得出来 ⇒ 上面用了占位符，末尾会回填（并自检"占位符已全部替换"）。')
if (PFC.copied) {
    w('//       ⚠️ 这里的总数是 `rows.json` 的**最终**条数（已含「土高炉 ⇒ 原始物质重组」那批 '
        + PFC.copied + ' 条副本）；')
    w('//          `样板清单.md` §1 的条数是在**加副本之前**算的（' + (PFC.total - PFC.copied) + ' 条）⇒ 两边数字不同是正常的，不是对不上。')
}
w('//')
w('// 源数据：PF.txt ← 由环境变量 SH_PF_SRC 指定的那个文件（本仓库不记录机器绝对路径）')
w('//   SHA256 = ' + PFC.srcSha)
w('//   ' + PFC.srcSize + ' B / 单行 NBT / 一个 minecraft:chest')
w('//   ⚠️ 以上两行**每次生成时现读现算**（原先写死的是 2026-09-26 那版的值，早就对不上了）。')
w('//')
w('// =============================================================================')
w('// §0 检查器自证（先说清"我凭什么信自己没看漏"）')
w('// =============================================================================')
w('//  · `ae2:processing_pattern` 逐条解析成功 = ' + PFC.total)
w('//  · `in` 恒为 ' + PFC.inLen + ' 格 / `out` 恒为 ' + PFC.outLen + ' 格（AE2 定长数组，空槽是 `{}`）—— ' + PFC.lenOK + '/' + PFC.total + ' 条都对（格数为**现算**：取 rows 第一行的长度）')
if (PARSED && typeof PARSED.rawCount === 'number') {
    w('//  · 原文里的纯文本出现次数 = ' + PARSED.rawCount + '，逐条解析成功 = ' + PARSED.parsedCount + ' ⇒ '
        + (PARSED.rawCount === PARSED.parsedCount ? '**两边相等，无静默漏条**' : '🔴 **不等，解析有漏！**'))
} else {
    w('//  · ⚠️ 读不到 `recipe-convert\\parsed.json` ⇒ **本条无法自证**（rawCount 由 parse_pf.js 算）。')
    w('//     ⇒ 要这一行证据就按顺序跑：parse_pf.js → gen_manifest.js → gen_kjs.js。')
}
w('//  · 正面对照：解析出的 ' + PFC.oldN + ' 条旧配方与上一版文件头 §3 的记录【逐字一致】')
// 🔴 2026-09-29：上面这行原来在产物里【重复出现了两次】（同一句话印两遍，复制粘贴留下的）。
//    已删掉重复的那一行 —— 属于"只改说明文案"，不影响可执行代码。
w('//    ⇒ 说明解析器看的是同一批东西（这是"解析器自己是对的"的证据，不是自说自话）')
w('//')
w('// =============================================================================')
w('// §1 元件对照（这次 vs 上次）—— 用户要的第一问')
w('// =============================================================================')
w('//   本次 Slot | 元件名（display.Name 逐字） | 本次条数 | 上次 | 差')
w('//   ----------|---------------------------|---------|------|----')
// 🔴 2026-09-29：本表原来 5 行**全是写死的**（2026-09-27 的值）⇒ 改成按 rows 现算。
//    "上次"那一列只可能来自 LAST_PF（历史留档），"本次条数"与"差"全部现算。
//    ⚠️ 槽位优先从 parsed.json 的 cells 读（可选依赖：读不到就只印元件名，不编一个槽位）。
var _cellSlotNow = {}
if (PARSED && PARSED.cells) for (var _cj = 0; _cj < PARSED.cells.length; _cj++) _cellSlotNow[PARSED.cells[_cj].name] = PARSED.cells[_cj].slot
var _cellOrder2 = PFC.cellOrder.slice().sort(function (a, b) {
    var sa = _cellSlotNow[a], sb = _cellSlotNow[b]
    if (sa === undefined) sa = 999
    if (sb === undefined) sb = 999
    return sa - sb
})
var _sumNow = 0, _sumLast = 0
for (var _ck = 0; _ck < _cellOrder2.length; _ck++) {
    var _cn3 = _cellOrder2[_ck], _nNow = PFC.perCell[_cn3] || 0
    var _nLast = LAST_PF.byCell[_cn3]
    _sumNow += _nNow
    if (_nLast !== undefined) _sumLast += _nLast
    var _slotTxt = _cellSlotNow[_cn3] === undefined ? '  ?  ' : ('Slot ' + _cellSlotNow[_cn3])
    w('//   ' + padDisp(_slotTxt, 10) + '| ' + padDisp(_cn3, 26) + '| ' + padDisp(String(_nNow), 8) + '| '
        + padDisp(_nLast === undefined ? '无' : String(_nLast), 5) + '| '
        + (_nLast === undefined ? '全新元件' : (_nNow - _nLast >= 0 ? '+' : '') + (_nNow - _nLast)))
}
w('//   ----------|---------------------------|---------|------|----')
w('//   ' + padDisp('合计', 10) + '| ' + padDisp('', 26) + '| ' + padDisp(String(_sumNow), 8) + '| '
    + padDisp(String(_sumLast), 5) + '| ' + (_sumNow - _sumLast >= 0 ? '+' : '') + (_sumNow - _sumLast))
w('//')
w('//  ⚠️ 「上次」那一列来自 `LAST_PF`（上一版 PF.txt 的历史留档 —— 旧源文件已被覆盖，算不出来）。')
w('//     本次那一列与差值都是**现算**的。')
w('//  ⚠️ 元件【槽位】：本次 ' + JSON.stringify(_cellSlotNow) + '；上次 '
    + JSON.stringify(LAST_PF.byCellSlot)
    + ' ⇒ 槽位是否后移，按这两组数自己对（本文件不替用户下结论）。')
w('//')
w('// =============================================================================')
w('// §2 中文名 → `gtceu:<id>` 映射（口径沿用上次：lang 里【唯一精确等于】那条）')
w('// =============================================================================')
w('//   纸上中文名     | gtceu id                            | 来源          | 条数（现算）')
w('//   ---------------|-------------------------------------|---------------|-----')
// 🔴 2026-09-29：本表原来 10 行连**条数**都是写死的 ⇒ 改成按 rows 现算；
//    "来源"也不再手写，而是按 SHANHAI_TYPES 判（山海的类型 ⇒ shanhai zh_cn，否则 gtceu zh_cn）。
var _typeRows = Object.keys(PFC.perType)
_typeRows.sort(function (a, b) { if (PFC.perType[b] !== PFC.perType[a]) return PFC.perType[b] - PFC.perType[a]; return a < b ? -1 : 1 })
for (var _ti = 0; _ti < _typeRows.length; _ti++) {
    var _tn2 = _typeRows[_ti], _tid2 = TYPE_ID[_tn2]
    if (!_tid2) continue
    var _src2 = SHANHAI_TYPES[_tid2] ? 'shanhai zh_cn' : 'gtceu zh_cn'
    w('//   ' + padDisp(_tn2, 16) + '| ' + padDisp('gtceu:' + _tid2, 38) + '| ' + padDisp(_src2, 14) + '| ' + PFC.perType[_tn2])
}
w('//   ⇒ 纸上出现过的配方类型共 ' + _typeRows.length + ' 种，合计 ' + PFC.gt + ' 条（= 全部带类型的样板）。')
w('//')
w('//  ✅ 已消除的唯一歧义：纸上写「世线电路板组装」，lang 里是「世线板电路组装」——')
w('//     用户 2026-09-26 亲自裁决：「我写错了」⇒ 按 lang 的 `gtceu:wl_board_circuit_assembly` 落，')
w('//     原先的 ⚠️ 待裁决标记已按用户裁决【移除】。')
w('//')
w('// =============================================================================')
w('// §3 配方类型 id 的存在性核实（正面对照，不是猜）')
w('// =============================================================================')
// 🔴 2026-09-29：本节原先写死「**8 个 shanhai 类型** … ⇒ **8/8 在**」。那是 2026-09-26 的旧文案：
//    那 8 个只是当时【抽检的 8 个样本】，**不是 shanhai 类型的总数**；实际早已是 41 个。
//    ⇒ 现已改为【现算】：①类型总数取真源①活代码条数；②抽检样本逐个现查并给 N/N。
//    坏处说清：写死的那个「8」会让人以为"shanhai 只有 8 个类型"，而这正是过期文案的典型危害。
var _probeIds = ['spacetime_distortion', 'primordial_singularity_inversion', 'matter_flow_condensation',
    'matter_module_casting', 'photon_siphon', 'photon_separation',
    'interstellar_matter_absorption', 'wl_board_circuit_assembly']
var _probeHit = 0, _probeMiss = []
for (var _pi = 0; _pi < _probeIds.length; _pi++) {
    if (SH_JAVA.caps[_probeIds[_pi]]) _probeHit++
    else _probeMiss.push(_probeIds[_pi])
}
w('//  · **shanhai 类型总数 = ' + SH_JAVA.liveCount + '**（**现算**：读真源① '
    + '`shanhai-rewrite\\src\\main\\java\\com\\shanhai\\common\\recipe\\ShanhaiRecipeTypes.java`')
w('//      的【活代码】`.setMaxIOSize(...)` 条数，与同文件常量 `REAL_TYPE_COUNT = ' + SH_JAVA.realTypeCount + '` 互为自证；')
w('//      注释作废块里另有 ' + SH_JAVA.deadCount + ' 条，已按"行首是注释符"剔除。）')
w('//  · 上述类型的 id 存在性由加载期断言 G3 保证（CAP 的每个键都必须能追溯到真源①或真源②），')
w('//      追不到就抛错、**拒绝写产物** —— 所以"存在性"不是靠这里手写一段话，是靠不通过就出不来。')
w('//  · 历史抽检样本（2026-09-26 抽的那 8 个）本次**现查**：'
    + _probeIds.join(' / ') + ' ⇒ **' + _probeHit + '/' + _probeIds.length + ' 在**'
    + (_probeMiss.length ? '（❌ 缺失：' + _probeMiss.join(', ') + '）' : '。'))
w('//  · gtceu:primitive_blast_furnace：游戏自己的导出表')
w('//      `local\\kubejs\\export\\recipes\\gtceu\\primitive_blast_furnace\\` = **' + exportCountText('primitive_blast_furnace') + ' 个配方文件**'
    + '（**现算**：数该目录下的 `*.json`）')
w('//      ⇒ 这个配方类型在本包里**真实存在且有配方**。')
w('//  · gtceu:circuit_assembler：同目录下有 **' + exportCountText('circuit_assembler') + '** 个配方文件（**现算**）。')
w('//  · ⚠️ 反例（防"假否定"）：`local\\kubejs\\export\\registries\\item.json` 时间戳 = 2026-09-10 19:19，')
w('//      **早于** shanhai-0.1.0.jar（2026-09-26 11:11）⇒ 那张导出表里 `gtceu:spacetime_distortion` ')
w('//      之类的目录**查不到**。这【不是"不存在"】，是"导出表过时"。')
w('//      ⇒ 所以 shanhai 类型一律改用【已部署 jar 的字节码/lang】取证，不看那张旧表。')
w('//')
w('// =============================================================================')
w('// §4 槽位核对（用 jar 里【真的】setMaxIOSize 值，不是估的）')
w('// =============================================================================')
w('//  shanhai 类型：ShanhaiRecipeTypes.java 的 .setMaxIOSize(物品入,物品出,流体入,流体出)：')
// 🔴 列宽【现算】，不再写死 34。写死 34 会把长 id **截断**成假 id：
//    实测 `primordial_myriad_ascension_tier_1`（37 字）被印成 `primordial_myriad_ascension_tie`
//    ⇒ 表里出现一个"看起来像真的、其实不存在"的类型名。列宽取【最长 id + 1】即可根治。
var _capW = 0
for (var _wk = 0; _wk < Object.keys(CAP).length; _wk++) _capW = Math.max(_capW, Object.keys(CAP)[_wk].length)
_capW = Math.max(_capW + 1, 20)
for (var q = 0; q < Object.keys(CAP).length; q++) {
    var tn = Object.keys(CAP)[q]
    var _pad = tn
    while (_pad.length < _capW) _pad += ' '
    w('//     ' + _pad + '= (' + CAP[tn].join(', ') + ')')
}
w('//')
w('//  上表 = 【真源现读现算】的结果（2026-09-29 起），不再手抄：')
w('//    真源① shanhai 类型 ⇒ shanhai-rewrite\\src\\main\\java\\com\\shanhai\\common\\recipe\\ShanhaiRecipeTypes.java')
w('//        的【活代码】`.setMaxIOSize(a,b,c,d)`（行首是注释符的作废块会被剔除）。')
w('//    真源② gtceu 原生类型 ⇒ recipe-convert\\javap\\GTRecipeTypes.txt（已部署 gtceu jar 的 javap -c 转储）。')
w('//    ⇒ 键集合若与真源对不上，生成器**抛错并拒绝写产物**（双向断言：缺键 / 多键 / 用到的类型查不到）。')
w('//  · gtceu:primitive_blast_furnace = (3, 3, 0, 0)')
w('//      —— 出处：gtceu jar `GTRecipeTypes.class` 字节码偏移 3028-3032：')
w('//         iconst_3 / iconst_3 / iconst_0 / iconst_0 → setMaxIOSize')
// 🔴 2026-09-29：下面两句原来写死"本文件 14 条土高炉"和"18 条现存 … 配方的实测最大值"。
//    前半句【能现算】⇒ 改成数 specs 里 primitive_blast_furnace 的条数；后半句的 (2,2,0,0)
//    要扫导出表 JSON 才能算（代价大、且导出表本身是快照）⇒ 按纪律显式标注为历史值。
var _pbfSpecs = 0
for (var _pb = 0; _pb < specs.length; _pb++) if (specs[_pb].type === 'primitive_blast_furnace') _pbfSpecs++
w('//      ⚠️ 流体入=0 流体出=0 ⇒ 土高炉配方【不许带流体】，本文件 ' + _pbfSpecs + ' 条土高炉确实一条流体都没有 ✓'
    + '（条数**现算**：数 specs 里 type=primitive_blast_furnace 的条数）')
w('//      ⚠️ 导出表里现有 ' + exportCountText('primitive_blast_furnace') + ' 条 primitive_blast_furnace 配方，'
    + '其实测最大值是 (2,2,0,0)（没填满 3）——**历史值 2026-09-26**，'
    + '需扫导出表 JSON 才能现算；文件数已现算，括号里的最大值请连日期一起引。')
w('//  · gtceu:circuit_assembler = (6, 1, 1, 0)')
w('//      —— 出处：同一份转储偏移 2308-2313：bipush 6 / iconst_1 / iconst_1 / iconst_0 → setMaxIOSize')
w('//      ⚠️ 上面两条的数值结论已由本表（现读现算）给出，这里只留取证过程，不再复写一份数字。')
w('//')
w('//  🔴 三条占用规则（沿用上次口径，**没变**）：')
w('//     · `.notConsumable(...)`【占】1 个物品输入槽（和普通输入一样算）')
w('//     · `.circuit(N)`【占】1 个物品输入槽')
w('//     · 物质模块【等级门槛】不占槽（它是准入判据，不是输入物）')
w('//  ⇒ 每条新增配方都算过 slotIn / slotOut，逐条结果见 §7 的表。')
w('//')
w('//  🔴🔴 槽位核对【曾查出 ' + (function () { var n = 0; for (var i = 0; i < specs.length; i++) if (specs[i].type === 'photon_separation' && specs[i].slots.itemIn > 2) n++; return n })() + ' 条溢出】—— ✅ **本版已由用户裁决解决**：')
w('//    （下表**按本次 specs 现算**，行号/条数都不写死）')
w('//')
w('//    PF.txt#  | 配方 id                    | 类型               | 物品入 | 旧 cap | 新 cap')
w('//    ---------|----------------------------|--------------------|--------|--------|-------')
for (var _sp1 = 0; _sp1 < specs.length; _sp1++) {
    var _spS = specs[_sp1]
    if (_spS.type !== 'photon_separation') continue
    var _inNow = _spS.slots.itemIn, _capNow = _spS.slots.cap[0]
    w('//    ' + padDisp('#' + _spS.row.no, 9) + '| ' + padDisp(_spS.id, 27) + '| ' + padDisp('photon_separation', 19) + '| '
        + padDisp(String(_inNow), 7) + '| ' + padDisp(_inNow > 2 ? '2 ❌' : '2 ✓', 7) + '| '
        + _capNow + (_inNow <= _capNow ? ' ✓' : ' ❌'))
}
w('//')
w('//    三条都是同一形状：1~2 个真物品 + 1 个 notConsumable(力场发生器) + 1 个 .circuit(1) = 3 格，')
w('//    而旧 photon_separation 的 setMaxIOSize 是 (2,4,2,2) ⇒ 物品入只有 2 格。')
w('//    🔴 用户 2026-09-26 裁决（逐字）：「photon_separation 的 setMaxIOSize(2, 4, 2, 2) ⇒ (4, 10, 2, 2)」')
w('//       ⇒ 物品入 2 ⇒ **4**（3 格装得下，留 1 格余量）、物品出 4 ⇒ **10**；**流体 2/2 不动**。')
w('//    ⚠️ 旧 cap (2,4,2,2) 是从【当时已部署的 jar】字节码读出来的真值：')
w('//       mods\\shanhai-0.1.0.jar!com/shanhai/common/recipe/ShanhaiRecipeTypes.class')
w('//       photon_separation 段：iconst_2 / iconst_4 / iconst_2 / iconst_2 → setMaxIOSize')
w('//    ✅ 新 cap (4,10,2,2) 是【本版 jar】的真值，出处 = javap -c 成品 jar 的 ShanhaiRecipeTypes.init()：')
w('//       该调用点操作数 = iconst_4 / bipush 10 / iconst_2 / iconst_2（2026-09-26 实测）。')
w('//    ⇒ 2026-09-26 放宽后不再溢出 ⇒ 这 3 条的 `slotOver` 标记与整段 SLOT-OVER 告警【已删除】。')
w('//    ⚠️ 附注：若把物质模块从"等级门槛"降级回"催化剂"（§7① 的另一条路），这三条会变成 4 格 ——')
w('//       正好用满 cap[0]=4，仍然装得下（旧 cap=2 时才是真的装不下）。')
w('//')
w('// =============================================================================')
w('// §5 KubeJS / Rhino 写法纪律（与上一版完全一致，刻意只用最保守的写法）')
w('// =============================================================================')
w('//  · 全局只用 var；不用 let/const、不用箭头函数、不用模板字符串、不用 ?.、不用解构')
w('//  · 不用 conditions（assembler 类配方设它会报错）')
w('//  · 编程电路用 .circuit(数字)——照宿主现成写法（gtceu.js:3301-3303 / ae2.js:361-362）')
w('//  · 「不消耗（催化剂）」用 .notConsumable(...)，照宿主 gtceu.js:935 / 3302 / 9955')
w('//  · 每条配方各自包 try/catch —— 一条失败只让一条失败，不连坐')
w('//  · 配方 id 全部显式给死（.id(...)），避免 /kubejs reload 时自动 id 变化导致旧配方残留')
w('//')
w('// =============================================================================')
w('// §6 🔴 改名纸 = 注记，不是配方输入（沿用上次口径）')
w('// =============================================================================')
w('//  ⇒ 本文件【任何地方都不出现 minecraft:paper】—— 除非它是**没改名的真产物**')
w('//    （本次确实有 ' + NOS_REAL_PAPER.length + ' 条：' + (NOS_REAL_PAPER.map(function (x) { return '#' + x }).join(' / ') || '无')
    + '「产出 minecraft:paper」是真的出纸，那些保留）。')
w('//  · 纸有三种：')
w('//      ① 「配方类型：XXX」（也有裸写类型名的，如「光子虹吸」「电路组装机」）⇒ 决定用哪台机器')
w('//      ② 「Ns」⇒ 决定耗时（×20 = tick）')
w('//      ③ 备注（本次四种：物质模块是催化剂 ' + NOS_CATALYST_PAPER.length + ' 条 / 力场发生器是催化剂 ' + NOS_FIELD.length + ' 条 / 夸克释放催化剂作为催化剂 ' + NOS_QUARK_CATALYST.length + ' 条 / 电子中微子产出概率5% ' + NOS_CHANCED.length + ' 条）')
w('//         ⚠️ 上面每个数都是【行数】不是【纸数】：rows.json 里含「原初物质重组 ⇒ 土高炉」的副本行，')
w('//            同一张纸会被算多行 ⇒ 例如「物质模块是催化剂」纸面只有 43 张、这里会显示 57 行。')
w('//  · 🔴 纸写在 in 里，也可能写在 **out** 里 —— ' + nosText(NOS_CHANCED)
    + '的「电子中微子产出概率5%」就在 out[' + CHANCED_OUT_SLOT + ']。')
w('//    本文件把"带自定义名的纸"从 out 里剔除，只留真产物。')
w('//')
w('// =============================================================================')
w('// §7 🔴 三处口径（**先报出来，没自己选**）：')
w('// =============================================================================')
w('//  ①物质模块怎么落 —— 🔴🔴 2026-10-03 用户点单（**本版唯一判据 = 只看纸**）')
w('//     ✅ 用户 2026-10-03 原话（逐字）：')
w('//        「你又写错了，这条配方物质模块要消耗，而不是催化剂，判断物质模块是否是催化剂仅凭借是否我放了那张纸」')
w('//     ⇒ 判据 = 只看纸：纸上有没有那张 `name === \'' + PAPER_MODULE_CATALYST + '\'` 的 NOTE（\u2261 papers 里 kind=\'NOTE\' 的那张）。')
w('//        · 【有纸】⇒ 不消耗，且【形态保持现状】：')
w('//             - 该配方类型 ∈ SHANHAI_TYPES（山海自己注册的类型）⇒ 落 ModuleLevelCondition 等级门槛（不占输入槽）')
w('//             - 非山海类型（如土高炉 primitive_blast_furnace）⇒ 落 `.notConsumable(...)` 真催化剂')
w('//        · 【无纸】⇒ 消耗：模块物品**进 itemInputs**（数量照纸上那个格子的数量，通常 1x），')
w('//             既不挂门槛、也不进 notConsumable')
w('//     📌 「制作物质模块的配方 ⇒ 消耗」这条口径【保留】（已核对，没退化）：')
w('//        · 本次 PF.txt 里"产出含物质模块"的配方共 ' + (function () { var n = 0; for (var i = 0; i < specs.length; i++) if (specs[i].isModuleRecipe) n++; return n })() + ' 条，'
    + '它们纸上【全都没有】催化剂纸 ⇒ 按"只看纸"天然落【消耗】，与那条口径一致。')
w('//        · ⚠️ 反过来的情况（配方产出含物质模块【且】纸上有催化剂纸）本次 = '
    + (function () { var n = 0; for (var i = 0; i < specs.length; i++) if (specs[i].isModuleRecipe && specs[i].hasModuleCatalystPaper) n++; return n })()
    + ' 条 —— 生成器对这种情况**不自己选**：会打一条「🔴 口径打架」告警并请用户裁决，绝不静默。')
w('//     🔴 本版相对上一版的落法变化（**现算**，判据 = 旧口径 `isModuleRecipe || (isWorldlineRecipe && !isShardFamilyRecipe)`'
    + ' vs 新口径 `!纸`）：')
w('//        ' + nosText2((function () {
        var a = []
        for (var i = 0; i < specs.length; i++) if (specs[i].hasModuleInput && specs[i].moduleLandedChanged) {
            a.push('`' + specs[i].id + '`（PF 第 ' + specs[i].row.no + ' 条：' + specs[i].moduleDecisionLegacy + ' ⇒ ' + specs[i].moduleDecision + '）')
        }
        return a
    })()))
w('//        ⚠️ 只有上列这些条目的**配方数据**（itemInputs / notConsumable / moduleLevelRequirement）会变；')
w('//           其余含模块的配方只是自报行（🧪）文案跟着新口径重写了，落法与数据一字未动。')
w('//     🔴🔴 2026-10-03 追加（用户点单）：**老 3 条 GT 配方也走这条判据**（它们原先漏在外面）')
w('//        【根因】rows.json 里 8 个 `old: true` 的行被分成 `oldRows`，而 `specs` 只由 `newRows` 构建 ⇒')
w('//               `specFromRow()`（判据所在）**从没在这些行上跑过**；它们的正文由 `emitOldGt()` 用')
w('//               **冻结文本**发出，老 ⑦（= PF 第 36 条 = `shanhai:pf/photon`）把')
w('//               `moduleLevelRequirement: 1x 入门物质模块` 与 `itemInputs: []` 直接写死。')
w('//        【修法】顶层新增 `LEGACY_GT_BIND`（老配方 id → 源样板行号，加载期逐条自证）+ `legacyGtRule()`，')
w('//               与 `specFromRow()` **共用同一个 `moduleRuleOf()`** ⇒ 一条判据、两处落点。')
w('//        【本次读数（现算）】')
for (var _lgn = 0; _lgn < LEGACY_GT_ORDER.length; _lgn++) {
    var _lr = LEGACY_GT_RULES[LEGACY_GT_ORDER[_lgn]]
    var _lrd = _lr.itemInputsPrefix.length
        ? ('itemInputs += ' + _lr.itemInputsPrefix.join(' + ') + '；moduleLevelRequirement / moduleLevelFallbackCatalyst 两行【消失】')
        : '本条输入里本来就没有物质模块 ⇒ 落法无变化'
    w('//           · `' + _lr.id + '` ⇐ PF 第 ' + _lr.no + ' 条｜纸：' + (_lr.catModule ? '有催化剂纸' : '**无**催化剂纸')
        + ' ⇒ **' + _lr.decision + '**（' + (_lr.decision === 'consume' ? _lrd : '保持门槛形态，两行不动') + '）')
}
w('//        ⚠️ 本条只改**模块落法**：产出 / 时长 / EUt / 流体 / `notConsumable` / circuit 一个字都没动。')
w('//     🔴 「什么算物质模块」以【权威 17 项表】为准，**不是**正则匹配名字：')
w('//        出处 = `com/shanhai/machine/module/PrimordialModuleMachine.MODULE_LEVELS`（17 项，等级 1..17），')
w('//        与 lang 的 `shanhai.recipe.fail.module_level.unresolved`「…不在 17 个物质模块表里」同一口径。')
w('//        ⚠️ 其中 6 个 id 并不以 `material_module` 结尾（material_deduction_module /')
w('//           material_recombination_module / imaginary_material_transition_remolding_module /')
w('//           material_creation_module / reality_anchor_module / genesis_reality_modification_module）')
w('//           ⇒ 任何"按名字正则"的写法都会漏掉它们（本生成器 2026-09-28 之前就是这么漏的）。')
w('//     · 已取证：`mods\\shanhai-0.1.0.jar` 里 **存在** ')
w('//       `com/shanhai/machine/module/ModuleLevelCondition.class` ⇒ 门槛机制可挂。')
w('//     · 本文件的做法：门槛形态由 `SHANHAI_PF_MODULE_MODE` 一行可切（\'gate\' 默认 ／ \'catalyst\' 全退催化剂）。')
w('//       ⚠️ 降级通道【保留】（jar 没绑 / `typeof` 判不到类时自动退回催化剂，配方不会消失）。')
w('//     · ⚠️ 另有 ' + (function () { var n = 0; for (var i = 0; i < specs.length; i++) for (var j = 0; j < specs[i].flags.length; j++) if (specs[i].flags[j].indexOf('但该样板里【没有】任何物质模块物品') >= 0) { n++; break } return n })()
    + ' 条样板有「' + PAPER_MODULE_CATALYST + '」这张纸却【没有物质模块物品】⇒ 门槛无从挂起，')
w('//       （上面这个数是按本次 specs 的 flags **现算**的；纸写「' + PAPER_MODULE_CATALYST + '」的样板共 '
    + NOS_CATALYST_PAPER.length + ' 条：' + (NOS_CATALYST_PAPER.join(' / ') || '无') + '）')
w('//       本文件按"无门槛无催化剂"落，并在脚本里逐条注明。')
w('//     📌 留档 —— 本版【取代】的两条旧口径（结论已被覆盖，只留证据链）：')
w('//        · 2026-09-28（逐字）：「有些配方的物质模块的配置错了，目前，注意是目前只有制作物质模块和世线的配方才需要消耗物质模块」')
w('//          ⇒ 当时把判据从"看纸"改成"看产出与配方类型"（`consumeModule = isModuleRecipe || (isWorldlineRecipe && !isShardFamilyRecipe)`）。')
w('//          ⚠️ 那正是本版要改掉的：纸上一句话都没写的配方被【静默】判成不消耗（用户报的 no=98 就是这种）。')
w('//        · 2026-10-01（逐字）：「那条配方的模块」⇒ 选「B. 等级门槛（不烧、但要挂）」；')
w('//          「世线残片其余 6 档 ＋ 寰宇并行超限器」⇒ 选「A. 还没写，以后补」⇒ **不许动**。')
w('//          ⇒ 当时给"世线族 ⇒ 消耗"再加一层"看产出是不是残片族"（SHARD_FAMILY，产出 ∈ 残片族 ⇒ 不消耗）。')
w('//          ✅ 本版下该结论【仍然成立】（本次命中它的 ' + (function () { var n = 0; for (var i = 0; i < specs.length; i++) if (specs[i].hasModuleInput && specs[i].isShardFamilyRecipe) n++; return n })()
    + ' 条产出 ∈ 残片族的配方纸上都有催化剂纸 ⇒ 照样不消耗）；')
w('//            但残片族已【不再参与判定】，只在产物注释里留一句"与 2026-10-01 结论也一致"。')
w('//        · 2026-09-26（更早，逐字）：「以后物质模块是催化剂指的都是我们今天刚写好的机制」')
w('//          ⇒ 当时口径 = 「纸上写 ⇒ 等级门槛」，并给 `primitive_blast_furnace` 单开一张 NO_GATE_TYPES 白名单。')
w('//          本版回到"看纸"，但那**不是**回到这一版：本版有显式正向表 SHANHAI_TYPES 决定"不消耗时的形态"，')
w('//          白名单式的反写逻辑【不再使用】。')
w('//  ②「力场发生器是催化剂」—— ' + nosText(NOS_FIELD)
    + ' —— 🔴 2026-10-03 判据已由【写死 LV】改成【认整族】')
w('//       现判据（两条**同时**满足）：① 纸写了这句话；② 物品 id ∈ 力场发生器族 = `'
    + FIELD_GENERATOR_FAMILY_RE + '`')
w('//         （命名空间锚死在 `gtceu:` / `gtlcore:` —— 裸后缀 `/field_generator$/` 会误吞')
w('//          `kubejs:containment_field_generator` 与 `kubejs:spacetime_compression_field_generator`）')
w('//       📌 族自证（正负对照，生成期现读导出注册表）：' + (FIELD_FAMILY_EVIDENCE.ok === true
    ? '以 `field_generator` 结尾的 id 共 ' + FIELD_FAMILY_EVIDENCE.all.length + ' 个；判据命中 '
        + FIELD_FAMILY_EVIDENCE.inFam.length + ' 个 [' + FIELD_FAMILY_EVIDENCE.inFam.join(' / ') + ']；'
        + '被排除 ' + FIELD_FAMILY_EVIDENCE.outFam.length + ' 个 [' + FIELD_FAMILY_EVIDENCE.outFam.join(' / ') + ']'
    : '⚠️ 本轮无自证读数：' + FIELD_FAMILY_EVIDENCE.why))
w('//       ✅ 因这条改动而改变的配方（**现算**，不是手抄）：' + (function () {
        var a = []
        for (var i = 0; i < specs.length; i++) if (specs[i].fieldGeneratorLanded && specs[i].fieldGeneratorLanded.length) {
            a.push('`' + specs[i].id + '`（' + specs[i].fieldGeneratorLanded.join(' / ') + '）')
        }
        return a.length ? a.join('；') : '（无）'
    })())
w('//       📌 留档（被本版覆盖的旧口径，2026-09-30 我写下的原文**逐字**，一字未改）：')
w('//          「这条规则在代码里是【硬编码 LV】的 —— 判据写死成 `it.id === \'gtceu:lv_field_generator\'`。」')
w('//          「本次 3 条命中的确实都是 LV，所以落法正确；但**换一台 MV/HV 力场发生器就会静默不生效**')
w('//           （物品照常被消耗、且不报错）。旁证：PF 第 58 条用的是 `gtceu:mv_field_generator`，')
w('//           它身上没有这张纸所以没暴露。⇒ 建议改成"凡 `*_field_generator` 且纸写了这句 ⇒ 催化剂"。」')
w('//          「我没动它：那会改变 58 条吗？不会（它没这张纸）—— 但它属于「扩大规则覆盖面」，')
w('//           按本轮硬要求②（改数值/口径要停下报）留给你裁决。」')
w('//       ⇒ 用户 2026-10-03 拍板：修。上面那个"建议"已落地，但**没有**照它字面用裸 `*_field_generator`')
w('//          （裸后缀会误吞那 2 个 kubejs 的同类 id），改用命名空间锚定 + 注册表现场自证。')
w('//  ④「' + PAPER_QUARK_CATALYST + '」—— ' + nosText(NOS_QUARK_CATALYST) + '（**本轮新增支持**）')
w('//       纸面原文逐字：「' + PAPER_QUARK_CATALYST + '」（**末尾没有句号**）。')
w('//       ⇒ 本文件按 `.notConsumable(\'<纸上那个数量>x shanhai:<上|下>_quark_emission_catalyst\')` 落，')
w('//          **数量照纸上一字未改**（本次两条都是 64）；本轮只改"消不消耗"，一个数字都没动。')
w('//       🔴 本轮之前这句话【一个字都没被读到】⇒ 那 64 个按普通材料落进 itemInputs 被吃掉。')
w('//          取证：把 PF 副本里这句话改名后重跑，specs 的**配方形状差异 = 0** ⇒ 反证"当前完全没读"。')
w('//       ⚠️ 只对**纸上写了这句话**的配方生效 ⇒ 第 59 / 63 条（同样有 ×1 夸克释放催化剂，')
w('//          但纸上没有这句话）保持原样：仍在 itemInputs 里被消耗。这个不对称**留给你裁决**。')
w('//  ③「电子中微子产出概率5%」—— ' + nosText(NOS_CHANCED) + '，且写在该样板的 **out[' + CHANCED_OUT_SLOT + ']**，紧邻 out['
    + (CHANCED_OUT_SLOT === null ? '?' : CHANCED_OUT_SLOT - 1) + '] 的')
w('//       `shanhai:electron_neutrino`。')
w('//     ✅ 用户 2026-09-26 裁决（原话逐字）：「吃加成」')
w('//     ⇒ 本文件落 `.chancedOutput(\'1x shanhai:electron_neutrino\', 500, 100)`')
w('//')
w('//     🔴 参数 1：**单位是万分比**（`getMaxChancedValue()` 反读 = `sipush 10000`）')
w('//        ⇒ **5% = 500**，不是 5000（5000 = 50%，会差 10 倍）。')
w('//        取证：宿主 gtceu.js:6148/6212/6250 (2000,0)=20% ／ :8405 (1000,0)=10% ／')
w('//              :6717 (200,20)=2% ／ ad.js:96 (5000,0)=50%；')
w('//              且游戏导出表里 chance 的最大值就是 10000。')
w('//')
w('//     🔴 参数 2：**它不是"加成上限"，是【每超频一级的加成量 tierChanceBoost】**。')
w('//        字节码实证 `GTRecipeBuilder.chancedOutput(ItemStack,int,int)`：')
w('//          67: aload_0 / 68: iload_2 / 69: putfield chance:I')
w('//          72: aload_0 / 73: iload_3 / 74: putfield tierChanceBoost:I     <-- 第三个参数进这里')
w('//        ⚠️ `maxChance` 从 KubeJS 侧【设不了】，它保持默认 10000。')
w('//')
w('//     🔴 **100 这个数是从哪来的（不是猜的）**：GTCEu 自己的"5% 档副产"标准值。')
w('//        反读游戏导出表 93,897 个文件、348,092 条 chanced 记录后，')
w('//        `chance=500 / maxChance=10000 / tierChanceBoost=100` 这个三元组出现 **414 次**，')
w('//        全部来自 GTCEu 的矿石副产线：`gtceu:macerator` 138 ／ `gtceu:integrated_ore_processor` 138 ／')
w('//        `gtceu:space_ore_processor` 138。⇒ 这就是本包"5% 且带加成"的标准配法。')
w('//        （同族还有 1400/850、200/20、50/5 —— 即 GTCEu 的 14% / 5% / 2% / 0.5% 副产阶梯。）')
w('//')
w('//     🔴 **加成到底怎么算**（`ChanceBoostFunction.OVERCLOCK` 反读，逐条对字节码）：')
w('//          int tierDiff = machineTier - recipeTier;')
w('//          if (tierDiff <= 0) return chance;          // 没超频 ⇒ 原始概率，吃不到加成')
w('//          if (recipeTier == 0) tierDiff = tierDiff - 1;')
w('//          return chance + tierChanceBoost * tierDiff;')
w('//        ⇒ ' + nosText(NOS_CHANCED) + '的 recipeTier = LV(1)，实际概率随机器超频：')
w('//            LV(1) → 5% ／ MV(2) → 6% ／ HV(3) → 7% ／ EV(5) → 9% ／ MAX(14) → 18%')
w('//          （公式里没有封顶，但 `maxChance` = 10000 = 100% 就是天花板）')
w('//')
w('// =============================================================================')
w('// §8 🔴 元件「处理样板-星门」的 EUt —— 算式 + 溢出判断')
w('//    （2026-09-26 第二次改判：MAX+16 ⇒ **MAX+8**）')
w('// =============================================================================')
w('//  ✅ 用户最新裁决（原话逐字）：「溢出那就算了，改成 max+8=max,4^8A」')
w('//     ⇒ 电压 = MAX 档，电流 = **4^8 A**。')
w('//     ⚠️ 本段【覆盖】上一版「MAX+16=MAX，4^16A」的裁决；上一版的算式与结论')
w('//        按本工程惯例【原样保留在 ⑥ 作留档】，但不再是当前口径。')
w('//')
w('//  ① V[MAX] 的真值 —— 【从字节码读的，不是从注释抄的】')
w('//     `libs\\gtceu-1.20.1-1.4.4.jar!com/gregtechceu/gtceu/api/GTValues.class`（javap -p -c）')
w('//     该 class 的 sha256 = 7A7275B8018D78876D2F1AB3D6B14644D7D5145EDBC16D35C3B9D99146D33375')
w('//     `<clinit>`：`bipush 14` → `ldc2_w // long 2147483648l` → `lastore` → `putstatic V:[J`')
w('//     V 是 **15 项 long[]**，索引 0..14 = ULV..MAX（索引 13 = 536870912）；同文件 VN[14] = `"MAX"`')
w('//     ⇒ **V[MAX] = 2147483648（= 2^31）**')
w('//     ⚠️ 注意：**不是** Integer.MAX_VALUE(2147483647)，也**不是** 1.7 时代注释里那种写法 ——')
w('//        这个数字如果照注释猜，整条算式的答案会差一位。')
w('//')
w('//  ② 4^8 的值')
w('//     4^8 = (2^2)^8 = **2^16 = 65536**')
w('//')
w('//  ③ 算式与结果（精确整数运算，不是浮点）')
w('//     EUt = V[MAX] × 4^8')
w('//         = 2147483648 × 65536')
w('//         = 2^31 × 2^16')
w('//         = **2^47 = 140737488355328**')
w('//')
w('//  ④ 🔴 溢出判断：**【不溢出】✓**（这是本次改判的全部目的）')
w('//        Long.MAX_VALUE = 2^63 − 1 = 9223372036854775807')
w('//        2^47 ≈ 1.41e14 ⇒ 只有 Long.MAX 的 **1/65536**（余量 65536 倍）')
w('//        ⇒ 2^47 是合法 long，**不存在回绕**。')
w('//        `.EUt` 的签名已核实：`GTRecipeBuilder EUt(long)` —— 是 long，不是 int，2^47 装得下。')
w('//')
w('//  ⑤ ⇒ 本文件的落地：')
w('//        **EUt = 140737488355328**（= V[MAX] × 4^8 = 2^47）')
w('//        —— "电压 × 电流"这一次可以**整体**进 EUt，不再需要像上一版那样拆开。')
w('//        🔴 事实提示（不是拦阻、也不是待裁决项）：2^47 EUt/t = **65536 倍 MAX 电压**。')
w('//           配方的 EUt 是"每 tick 电压需求"，GT 侧要能找到供得起这个数的能源仓它才跑得起来。')
w('//           这是数值/玩法层面的事；本条只负责"算式不溢出、写进去的值不违法"。')
w('//        ⚠️ 这三条配方所在的**元件名**仍写作「处理样板-星门(MAX+16)」——')
w('//           那是 AE2 样板自己的**显示名**（PF.txt 源数据原文），本次不要求改样板，')
w('//           故文件里保持原样；本段只改这 3 条配方的 EUt 值。')
w('//')
w('//  ⑥ 📌 留档：**已作废的上一版口径**（2026-09-26 早先裁决「MAX+16=MAX，4^16A」）')
w('//     4^16 = 2^32 = 4294967296')
w('//     EUt = 2^31 × 2^32 = 2^63 = 9223372036854775808 = Long.MAX_VALUE **+ 1**')
w('//     ⇒ **恰好越界 1** ⇒ Java long 二进制补码回绕成 Long.MIN_VALUE = −9223372036854775808（负数！）')
w('//     ⇒ 上一版因此只能把 EUt 写成 V[MAX] = 2147483648（只放电压部分），安培不放进 EUt。')
w('//     ⇒ 改成 4^8 后该约束消失，故本版把 2^47 整体写进 EUt。')
w('//')
w('//  · 「合成样板」的 ' + PH_SHAPED_HARD + ' 条是工作台配方（event.shaped，有序），摆位 = in 下标 0..8 行优先 ——')
w('//      （条数用占位符回填**现算**：SHAPED 数组在本脚本后半段才建出来；原先是写死的 5，而实际是 6。）')
w('//      这是上次已由用户核对确认的口径（原话「这下对了」），本次**一个字没改**。')
w('//')
w('// =============================================================================')

w('var SHANHAI_PF_TAG = \'[SHANHAI-PF]\'')
w('')
w('// 🔴 §7① 的切换点：\'gate\' = 物质模块等级门槛（默认）／ \'catalyst\' = 老写法（不消耗催化剂）')
w('//    ⚠️ 2026-10-03：它只影响【纸上写了催化剂纸】那些配方的"不消耗形态"，')
w('//       "要不要消耗"由纸单独决定（无纸的条目根本不看这个开关）。')
w('var SHANHAI_PF_MODULE_MODE = \'gate\'')
w('')
w('// 老山海 module_level 条件类是否已由 jar 侧注册并绑定（见 §7①）。')
w('// ⚠️ 必须用 typeof 判：直接引用未绑定的标识符会 ReferenceError，而 typeof 不会抛。')
w('var SHANHAI_HAS_MODULE_LEVEL_CONDITION = (typeof ModuleLevelCondition !== \'undefined\')')
w('')
w('// 解析老山海写法 "Nx <物品id>" -> [物品id, 数量]（无 Nx 时数量默认 1）。')
w('// 逐句对齐 DShanhaiRecipeEngine.addOneCondition 的解析口径（照上一版搬）。')
w('var shanhaiParseModuleLevel = function (text) {')
w('    var s = String(text).trim()')
w('    var count = 1')
w('    var id = s')
w('    var xi = s.indexOf(\'x\')')
w('    var c0 = s.length > 0 ? s.charCodeAt(0) : 0')
w('    if (xi > 0 && c0 >= 48 && c0 <= 57) {')
w('        count = parseInt(s.substring(0, xi), 10)')
w('        if (isNaN(count) || count <= 0) { count = 1 }')
w('        id = s.substring(xi + 1).trim()')
w('    }')
w('    return [id, count]')
w('}')
w('')
w('// 该走等级门槛吗？（模式 = gate  且  jar 侧条件类已绑定）')
w('var shanhaiUseLevelGate = function (r) {')
w('    if (SHANHAI_PF_MODULE_MODE !== \'gate\') { return false }')
w('    if (!r.moduleLevelRequirement) { return false }')
w('    return SHANHAI_HAS_MODULE_LEVEL_CONDITION')
w('}')
w('')

// ---- shaped (5, verbatim from previous version) ----
w('// -----------------------------------------------------------------------------')
w('// 老 ①..⑧ 里的 ②③④⑤⑥：工作台配方（有序 shaped）')
w('// 🔴 与上一版【逐字相同】，本次未改动（用户口径：老 ' + PFC.oldN + ' 条保留不动；条数**现算**）')
w('// 摆位 = PF.txt 该样板 in 数组下标 0..8 按【行优先】')
w('// -----------------------------------------------------------------------------')
w('var shanhaiPfShaped = [')
var SHAPED = [
    { id: 'shanhai:pf_crafting/primordial_divergence_generator', out: 'shanhai:primordial_divergence_generator', pattern: ['ABA', 'BCB', 'ABA'], keys: { A: 'gtceu:primitive_void_ore', B: 'gtceu:ulv_fragment_world_collection_machine', C: 'shanhai:primordial_engine_core' } },
    { id: 'shanhai:pf_crafting/wl_board_ulv', out: 'shanhai:wl_board_ulv', pattern: ['ABA', 'BCB', 'ABA'], keys: { A: 'gtceu:pulsating_alloy_ingot', B: 'gtceu:certus_quartz_gem', C: 'kubejs:ulv_universal_circuit' } },
    { id: 'shanhai:pf_crafting/primordial_engine_core', out: 'shanhai:primordial_engine_core', pattern: ['PRP', 'MTB', 'AGA'], keys: { P: 'gtceu:spacetime_tiny_fluid_pipe', R: 'gtlcore:primitive_fluid_regulator', M: 'thetornproductionline:celestial_secret_deducing_module_ulv', T: 'gtlcore:treasures_crystal', B: 'shanhai:wl_board_ulv', A: 'gtlcore:primitive_robot_arm', G: 'ae2:fluix_glass_cable' } },
    { id: 'shanhai:pf_crafting/introductory_material_module', out: 'shanhai:introductory_material_module', pattern: ['CRD', 'BTB', 'DPC'], keys: { C: 'gtlcore:mining_crystal', R: 'kubejs:reactor_core', D: 'minecraft:diamond', B: 'shanhai:wl_board_ulv', T: 'gtlcore:treasures_crystal', P: 'gtceu:spacetime_tiny_fluid_pipe' } },
    { id: 'shanhai:pf_crafting/primordial_matter_caster', out: 'shanhai:primordial_matter_caster', pattern: ['LSL', 'SKS', 'LSL'], keys: { L: 'thetornproductionline:celestial_secret_deducing_module_lv', S: 'ae2:molecular_assembler', K: 'shanhai:primordial_engine_core' } },
    // 🔴 2026-09-27 用户裁定「补回去」：工业蒸汽机械方块的工作台配方。
    //    背景：用户报「JEI 里没显示」⇒ 查明它在 09-10 的 export 快照里存在（id = minecraft:kjs/gtceu_industrial_steam_casing，
    //    即 KubeJS 给【无显式 id 配方】自动生成的形态），但【产生它的代码在现存的任何文件里都搜不到】
    //    （kubejs 全目录 / 全部 90 个 mod jar 的 data/**/recipes / kubejs\data / 存档 datapacks 全 0 命中；
    //     GTL原版 09-24 快照也没有 ⇒ 不是原版自带）。⇒ 问用户后他选「补回去」。
    //    ✅ 形状与 key 全部【照抄 09-10 快照原文】，未重新设计：
    //       pattern ["AAA","BCA","DEF"]，A=bronze_plate B=#forge:tools/hammers C=bronze_frame
    //       D=bronze_rotor E=bronze_gear F=bronze_rotor
    //    ✅ tag 已核实有效：export\tags\minecraft\item\forge\tools\hammers.json = 26 项（含 gtceu:bronze_hammer）
    //    ⚠️ KJS 里 tag 必须写前缀 '#' ⇒ '#forge:tools/hammers'。
    { id: 'kubejs:industrial_steam_casing', out: 'gtceu:industrial_steam_casing', pattern: ['AAA', 'BCA', 'DEF'], keys: { A: 'gtceu:bronze_plate', B: '#forge:tools/hammers', C: 'gtceu:bronze_frame', D: 'gtceu:bronze_rotor', E: 'gtceu:bronze_gear', F: 'gtceu:bronze_rotor' } }
]
// 🔴 2026-09-29：硬编码条数**现算**。原来自报里写死 5，但数组里其实是 **6** 条
//    （第 6 条是 `kubejs:industrial_steam_casing`，2026-09-27 用户裁决"补回去"的那条）
//    ⇒ 那句自报一直是错的。从此以本变量为准。
var SHAPED_HARD_N = SHAPED.length
// ══════════ 从 rows.json 的合成样板【全量生成】工作台配方（2026-09-27 用户点单）══════════
//   起因：用户问「你是不是没更新工作台的配方」—— 他问对了。原来 SHAPED 是手写的几条，
//   【完全不读 PF.txt】，所以 PF.txt 里的合成样板只发了 5 条，新增的
//   shanhai:worldline_cracking_hub 一条都没进游戏。
//   ✅ 判据：cell === '合成样板' 且 type 为空（没有 GT 配方类型 = 纯工作台合成）且非 old。
//   ✅ 形状：rows.json 的 real[].slot 是 0..8 的【行优先格位】（parse_pf 保留了格位），
//      按 slot 还原 3×3；空格 = 空格 ' '。输出是 1 格 ⇒ 用 event.shaped（与老那几条同口径）。
//   ✅ 查重：与硬编码那些条按【产出物品 id】比对，重复的不再生成。
//   ✅ 老那几条【保留不动】（用户口径"老 8 条保留不动"），新的【追加】在后面。
//   ⚠️ 本段原来在注释里写「手写的 5 条」，实际是 **6** 条（见 SHAPED_HARD_N）⇒ 已一律改成现算。
var SHAPED_DUP = {}
for (var q0 = 0; q0 < SHAPED.length; q0++) SHAPED_DUP[SHAPED[q0].out] = 1
var SHAPED_SKIP = []
var SHAPED_KEYS_LETTERS = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ'
for (var q1 = 0; q1 < rows.length; q1++) {
    var RQ = rows[q1]
    if (RQ.cell !== '合成样板') continue
    if (RQ.type) continue
    if (RQ.old) continue
    var OQ = RQ.outs || []
    if (OQ.length !== 1) { SHAPED_SKIP.push('no=' + RQ.no + ' 产出不是 1 种'); continue }
    var od = OQ[0].d || OQ[0]
    if (!od.id) { SHAPED_SKIP.push('no=' + RQ.no + ' 产出无 id'); continue }
    if (SHAPED_DUP[od.id]) { SHAPED_SKIP.push('no=' + RQ.no + ' ' + od.id + ' 与硬编码重复'); continue }
    var g9 = [' ', ' ', ' ', ' ', ' ', ' ', ' ', ' ', ' ']
    var filled = 0
    for (var q2 = 0; q2 < RQ.real.length; q2++) {
        var itq = RQ.real[q2]
        var dq = itq.d || itq
        var sl = itq.slot
        if (sl === undefined || sl === null || sl < 0 || sl > 8) continue
        if (!dq.id) continue
        g9[sl] = dq.id
        filled = filled + 1
    }
    if (filled === 0) { SHAPED_SKIP.push('no=' + RQ.no + ' ' + od.id + ' 9 格全空'); continue }
    var keysQ = {}
    var mapQ = {}
    var liQ = 0
    var patQ = ['', '', '']
    for (var r9 = 0; r9 < 3; r9++) {
        for (var c9 = 0; c9 < 3; c9++) {
            var v9 = g9[r9 * 3 + c9]
            if (v9 === ' ') { patQ[r9] = patQ[r9] + ' '; continue }
            if (!mapQ[v9]) { if (liQ >= 26) { mapQ[v9] = '?'; } else { mapQ[v9] = SHAPED_KEYS_LETTERS.charAt(liQ); liQ = liQ + 1 } }
            if (mapQ[v9] === '?') { patQ[r9] = patQ[r9] + ' '; continue }
            keysQ[mapQ[v9]] = v9
            patQ[r9] = patQ[r9] + mapQ[v9]
        }
    }
    var outQ = ((od.count || 1) > 1 ? (od.count + 'x ') : '') + od.id
    SHAPED.push({ id: 'shanhai:pf_crafting/' + od.id.split(':')[1], out: outQ, pattern: patQ, keys: keysQ })
    SHAPED_DUP[od.id] = 1
}
console.log('[PF-SHAPED] 硬编码 ' + SHAPED_HARD_N + ' 条 + 由 PF.txt 生成 ' + (SHAPED.length - SHAPED_HARD_N) + ' 条 ⇒ 合计 ' + SHAPED.length + ' 条')
if (SHAPED_SKIP.length) for (var q3 = 0; q3 < SHAPED_SKIP.length; q3++) console.log('[PF-SHAPED] 跳过 ' + SHAPED_SKIP[q3])

for (var s1 = 0; s1 < SHAPED.length; s1++) {
    var S1 = SHAPED[s1]
    w('    {')
    w('        id: \'' + S1.id + '\',')
    w('        out: \'' + S1.out + '\',')
    w('        pattern: [\'' + S1.pattern.join('\', \'') + '\'],')
    w('        keys: {')
    var kk = Object.keys(S1.keys)
    for (var s2 = 0; s2 < kk.length; s2++) w('            ' + kk[s2] + ': \'' + S1.keys[kk[s2]] + '\'' + (s2 < kk.length - 1 ? ',' : ''))
    w('        }')
    w('    }' + (s1 < SHAPED.length - 1 ? ',' : ''))
}
w(']')
w('')

// ---- GT specs ----
w('// -----------------------------------------------------------------------------')
w('// 老 ' + PFC.gtOld + ' 条 GT（①⑦⑧，与上一版【逐字相同】，本次未改动）+ 新增 ' + (PFC.gt - PFC.gtOld) + ' 条（条数现算）')
w('// 每条上面第一行注释 = 溯源：PF.txt 里的序号 / 元件 / 纸')
w('// -----------------------------------------------------------------------------')
w('var shanhaiPfGt = [')
var FIRST = true
function emit(spec, isOld, rawText) {
    if (!FIRST) w(',')
    FIRST = false
    w('    ' + rawText[0])
    for (var i = 1; i < rawText.length; i++) w('    ' + rawText[i])
}

// 老 3 条：逐字搬上一版（内容见 [server_scripts]shanhai_recipes.js 的对应段）
var OLDSPEC = [
    {
        lines: [
            '// ===== 老 ①（上一版逐字，未改）：处理样板ULV / 电路组装机 / 10s =====',
            '{'
        ],
        raw: null
    }
]
// 手工拼老 3 条（照抄上一版文本）—— ⚠️ 模块落法不再写死，见上方 `LEGACY_GT_RULES` 段（含根因说明）
function emitOldGt() {
    var _r36 = LEGACY_GT_RULES['shanhai:pf/photon']
    function _j(a) { return a.map(function (x) { return '\'' + x + '\'' }).join(', ') }
    var o1 = [
        '{',
        '    id: \'shanhai:pf/primordial_omega_engine\',',
        '    type: \'circuit_assembler\',',
        '    // 老 ① 的 6 项 = circuit_assembler 的物品输入上限 setMaxIOSize(6,1,1,0)，正好用满',
        '    notConsumable: [],',
        '    circuit: 0,',
        '    itemInputs: [',
        '        \'4x gtceu:dimensionally_transcendent_steam_oven\',',
        '        \'4x gtceu:dimensionally_transcendent_dirt_forge\',',
        '        \'1x shanhai:primordial_engine_core\',',
        '        \'16x thetornproductionline:celestial_secret_deducing_module_ulv\',',
        '        \'64x kubejs:precision_steam_mechanism\',',
        '        \'16x gtceu:primitive_void_ore\'',
        '    ],',
        '    inputFluids: [\'gtceu:glue 16000\'],',
        '    itemOutputs: [\'1x shanhai:primordial_omega_engine\'],',
        '    outputFluids: [],',
        '    chancedOutputs: [],',
        '    duration: 200,',
        '    EUt: 8',
        '}'
    ]
    // 🔴 2026-10-03 追加：本条的模块落法**不再写死**——由 `_r36`（= 按 PF 第 36 条的纸现算，见顶层 LEGACY_GT_RULES）给出。
    //    · 纸上无催化剂纸 ⇒ `_r36.gateLines` 为空（下面两行门槛/降级【不出现】）、`itemInputs` 里出现模块；
    //    · 若将来用户给第 36 条补上那张纸 ⇒ `_r36.gateLines` 自动变回原来的两行、`itemInputs` 变回空。
    //    ⚠️ 其余行（产出/时长/EUt/流体/notConsumable/circuit）与本轮之前**逐字相同**。
    var o2 = [
        '{',
        '    id: \'shanhai:pf/photon\',',
        '    type: \'photon_siphon\',',
        '    // PF.txt 原文该格：programmed_circuit + tag:{Configuration:2}',
        '    circuit: 2,',
        '    // 🔴 用户 2026-09-28 原话：「光子虹吸的配方里面主世界碎片和物质模块都是不消耗的（作为催化剂）」',
        '    //     ⇒ 主世界碎片【保留不消耗】（本条一律不动）；物质模块的落法见下面那行 🧪 自报',
        '    notConsumable: [\'1x gtlcore:world_fragments_overworld\'],'
    ].concat(['    ' + _r36.note]).concat(_r36.gateLines, [
        '    itemInputs: [' + _j(_r36.itemInputsPrefix) + '],',
        '    inputFluids: [],',
        '    itemOutputs: [\'16x shanhai:photon\'],',
        '    outputFluids: [\'shanhai:zero_point_energy 32000\', \'shanhai:light 16000\'],',
        '    chancedOutputs: [],',
        '    duration: 1200,',
        '    EUt: 32',
        '}'
    ])
    var o3 = [
        '{',
        '    id: \'shanhai:pf/first_light\',',
        '    type: \'photon_siphon\',',
        '    // PF.txt 原文该格：programmed_circuit + tag:{Configuration:1}',
        '    circuit: 1,',
        '    notConsumable: [\'1x gtlcore:world_fragments_overworld\'],',
        '    itemInputs: [],',
        '    inputFluids: [],',
        '    itemOutputs: [\'32x shanhai:first_light\', \'4x shanhai:photon\'],',
        '    outputFluids: [\'shanhai:light 4000\'],',
        '    chancedOutputs: [],',
        '    duration: 1200,',
        '    EUt: 32',
        '}'
    ]
    var blocks = [
        { note: ['// ===== 老 ①（上一版逐字，未改）：处理样板ULV / 电路组装机 / 10s ====='], body: o1 },
        // 🔴 2026-10-03：老 ⑦ 的"上一版逐字，未改"已经【不再成立】—— 它的物质模块落法按纸改成了消耗
        //    （只改了模块落法；产出/时长/EUt/流体/notConsumable/circuit 仍是上一版逐字）。
        //    ⚠️ 一行注释说假话比没有注释更坏（本项目的老毛病）⇒ 这里如实改写这一行。
        { note: ['// ===== 老 ⑦（上一版逐字；**仅**物质模块落法于 2026-10-03 按纸改为消耗）：处理样板LV / 光子虹吸 / 60s ====='], body: o2 },
        { note: ['// ===== 老 ⑧（上一版逐字，未改）：处理样板LV / 光子虹吸 / 60s ====='], body: o3 }
    ]
    for (var b = 0; b < blocks.length; b++) {
        if (b > 0) w(',')
        for (var n = 0; n < blocks[b].note.length; n++) w('    ' + blocks[b].note[n])
        for (var l = 0; l < blocks[b].body.length; l++) w('    ' + blocks[b].body[l])
    }
}
emitOldGt()

// 新 30 条
for (var x = 0; x < specs.length; x++) {
    var s = specs[x]
    var R = s.row
    w(',')
    w('    // ▶ PF.txt 第 ' + R.no + ' 条（本次新增）｜元件「' + R.cell + '」｜纸：类型「' + s.srcTypeName + '」'
        + (R.time ? '／耗时「' + R.time + '」' : '') + '｜输出 ' + s.itemOutputs.join(' + '))
    // 🧪 2026-09-28 起每条含物质模块的配方【自报落法】，让用户能一眼看出对不对并一眼纠正
    //    🔴 2026-10-03：文案已改成"按哪张纸判的"（纸在第几格 / 有还是没有）
    if (s.moduleNote) w('    // ' + s.moduleNote)
    // 🔴 2026-09-30：本轮"描述落地"的可 grep 证据行（用户明确要求每条落地的描述打一行）
    if (s.descNote) w('    // ' + s.descNote)
    if (s.flags.length) for (var f2 = 0; f2 < s.flags.length; f2++) {
        // 一条 flag 可以是多行（用 \n 分隔）：首行带 🔴，续行用对齐缩进，与原手写注释同形。
        var flines = String(s.flags[f2]).split(String.fromCharCode(10))
        for (var f3 = 0; f3 < flines.length; f3++) w((f3 === 0 ? '    // 🔴 ' : '    //    ') + flines[f3])
    }
    // 🔴 2026-09-26：槽位溢出机制已【整段移除】（见文件头 §4）。
    //    历史上这里会按 s.slots.over 打 4 行"请用户裁决"注释并写 slotOver: true；
    //    photon_separation 放宽到 (4,10,2,2) 后没有任何配方溢出 ⇒ 该分支与那个字段一起删掉。
    //    ⚠️ 生成器【仍然】在下面算 s.slots.over 并在控制台报告（构建期自检，不进产物）。
    w('    {')
    w('        id: \'' + s.id + '\',')
    w('        type: \'' + s.type + '\',')
    if (s.circuit > 0) w('        circuit: ' + s.circuit + ',')
    else w('        circuit: 0,')
    w('        notConsumable: [' + s.notConsumable.map(function (v) { return '\'' + v + '\'' }).join(', ') + '],')
    if (s.moduleGate) {
        w('        moduleLevelRequirement: \'' + s.moduleGate + '\',')
        w('        moduleLevelFallbackCatalyst: \'' + s.moduleFallback + '\',')
    }
    w('        itemInputs: [' + s.itemInputs.map(function (v) { return '\'' + v + '\'' }).join(', ') + '],')
    w('        inputFluids: [' + s.inputFluids.map(function (v) { return '\'' + v + '\'' }).join(', ') + '],')
    w('        itemOutputs: [' + s.itemOutputs.map(function (v) { return '\'' + v + '\'' }).join(', ') + '],')
    w('        outputFluids: [' + s.outputFluids.map(function (v) { return '\'' + v + '\'' }).join(', ') + '],')
    w('        chancedOutputs: [' + s.chancedOutputs.map(function (c) { return '{ item: \'' + c.item + '\', chance: ' + c.chance + ', tierChanceBoost: ' + c.tierChanceBoost + ' }' }).join(', ') + '],')
    w('        duration: ' + s.duration + ',')
    w('        EUt: ' + s.EUt)
    w('    }')
}
w(']')
w('')

// ---- registration ----
w('// -----------------------------------------------------------------------------')
w('// 注册：工作台配方（老 ②③④⑤⑥ = **历史值 2026-09-26 的 5 条**：'
    + '它们建文件时就在，不随 PF.txt 变化，故无法现算）')
w('// -----------------------------------------------------------------------------')
// ---- 🔴 运行期反查：那两条老配方【到底还在不在】(用户 2026-09-27 要求可判定的证据) ----
//    放在 ServerEvents.loaded ⇒ 配方表已完全定型，能真读到结果。
w('// -----------------------------------------------------------------------------')
w('// -----------------------------------------------------------------------------')
w('// 🔴 运行期探针：把【真实 id】打出来 —— 不再猜')
w('//   教训：上一版用 recipeManager.byKey(id) 反查，报 present=false，而用户 JEI 里【配方还在】，')
w('//   两者不可能同时对 ⇒ 【byKey 查不到 GTCEu 的配方】⇒ 那条判据【不可靠，已废】。')
w('//   本版改成【遍历配方表】getRecipes().toArray()，按 getId() 里的关键词找 ⇒ 自洽、不依赖索引。')
w('//   ⚠️ 刻意【不用 getResultItem()】—— 字节码实证它恒返回 ItemStack.EMPTY。')
w('//   判读：water_lava=[none] ⇒ 那类配方确实不在表里；打出具体 id ⇒ 那就是【真实 id】。')
w('//   同时报 totalRecipes 做 sanity：若为 0 ⇒ 是遍历入口不对，不是配方不在。')
w('// -----------------------------------------------------------------------------')
w('ServerEvents.loaded(function (event) {')
w("    // 🔴 迟到删除：ServerEvents.recipes 期间删不掉（实测：那时 mod 的配方还没进表），")
w("    //    改到 loaded 时直接改配方表。判据 = 紧随其后的探针（同一行日志体系）。")
w("    // 🔴 用【子串】而不是精确 id —— 实测同一个\"水+岩浆→黑曜石+蒸汽\"存在【两条】老配方：")
w("    //      cxhmz:chemical_reactor/water_lava_to_steam 与 cxhmz:large_chemical_reactor/water_lava_to_steam")
w("    //    （探针实测：删掉第一条后第二条约仍在 ⇒ 这就是用户说\"配方还在\"的原因）")
w("    var KEY = ['water_lava_to_steam', 'fire_charge_ch']")
w("    // ⚠️ 绝不删自己新写的（shanhai: 前缀）")
w("    var dropped = 0")
w("    var keep = []")
w("    var lerr = ''")
w("    try {")
w("        var all0 = event.server.recipeManager.getRecipes().toArray()")
w("        for (var k0 = 0; k0 < all0.length; k0++) {")
w("            var r0 = '?'")
w("            try { r0 = String(all0[k0].getId()) } catch (e0) { r0 = '?' }")
w("            var hit = false")
w("            for (var k1 = 0; k1 < KEY.length; k1++) if (r0.indexOf(KEY[k1]) >= 0) hit = true")
w("            if (r0.indexOf('shanhai:') === 0) hit = false")
w("            if (hit) { dropped = dropped + 1; continue }")
w("            keep.push(all0[k0])")
w("        }")
w("        if (dropped > 0) event.server.recipeManager.replaceRecipes(keep)")
w("    } catch (e1) { lerr = ' (' + e1 + ')' }")
w("    console.info(SHANHAI_PF_TAG + ' remove-late dropped=' + dropped + ' kept=' + keep.length + lerr)")
w("")
w('    var arr = []')
w('    var why = \'\'')
w('    try { arr = event.server.recipeManager.getRecipes().toArray() } catch (e) { why = \' (\' + e + \')\' }')
w('    var total = arr.length')
w('    var wl = \'none\'')
w('    var fc = \'none\'')
w('    var ncx = 0')
w('    var cxList = \'\'')
w('    for (var i = 0; i < total; i++) {')
w('        var rid = \'?\'')
w('        try { rid = String(arr[i].getId()) } catch (e2) { rid = \'?\' }')
w('        if (rid.indexOf(\'water_lava\') >= 0) wl = rid')
w('        if (rid.indexOf(\'fire_charge\') >= 0) { if (fc === \'none\') fc = rid; else if (fc.indexOf(rid) < 0) fc = fc + \'|\' + rid }')
w('        if (rid.indexOf(\'cxhmz\') === 0 || rid.indexOf(\'cxbp\') === 0) { ncx = ncx + 1; if (cxList.length < 200) cxList = cxList + \' \' + rid }')
w('    }')
w('    console.info(SHANHAI_PF_TAG + \' probe totalRecipes=\' + total + \' water_lava=[\' + wl + \'] fire_charge=[\' + fc + \'] cx-ns-count=\' + ncx + why)')
w('    if (ncx > 0) console.info(SHANHAI_PF_TAG + \' probe cx-ids\' + cxList)')
w('})')
w('')
w('')
w('ServerEvents.recipes(function (event) {')
w('    var ok = 0')
w('    // 🔴 2026-09-27 接进聊天栏横幅（scope=shanhai_pf）')
w('    //    ⚠️ 变量名必须【不叫 Stats】—— Rhino 里 `Stats` 会回落到原版 net.minecraft.stats.Stats，')
w('    //       实测报错：Java class "net.minecraft.stats.Stats" has no … "reportSummary"。')
w('    //    ⚠️ 声明必须在【每个 ServerEvents.recipes 回调内部各来一次】—— var 是函数作用域，跨回调不共享。')
w('    var ShanhaiStats = null')
w('    try { ShanhaiStats = Java.loadClass(\'com.shanhai.common.recipe.ShanhaiRecipeStats\') } catch (eS) { ShanhaiStats = null }')
w('    if (ShanhaiStats) ShanhaiStats.reset()   // 本批自己清一次（Java 侧是全局静态累加器）')
w('    var bad = 0')
w('    var errList = \'\'')
w('    var i')
w('')
w('    for (i = 0; i < shanhaiPfShaped.length; i++) {')
w('        var r = shanhaiPfShaped[i]')
w('        try {')
w('            event.shaped(r.out, r.pattern, r.keys).id(r.id)')
w('            ok = ok + 1')
w('            if (ShanhaiStats) ShanhaiStats.addResult(true)')
w('        } catch (e) {')
w('            bad = bad + 1')
w('            if (ShanhaiStats) ShanhaiStats.addResult(false)')
w('            if (errList.length < 1200) { errList = errList + r.id + \' => \' + e + \' | \' }')
w('        }')
w('    }')
w('')
w('    console.info(SHANHAI_PF_TAG + \' crafting(shaped) ok=\' + ok + \' failed=\' + bad')
w('        + \' total=\' + shanhaiPfShaped.length)')
w('    if (bad > 0) {')
w('        console.error(SHANHAI_PF_TAG + \' crafting FAILED list: \' + errList)')
w('    }')
w('})')
w('')
w('// -----------------------------------------------------------------------------')
w('// 注册：GT 机器配方（老 ' + PFC.gtOld + ' 条 + 新增 ' + (PFC.gt - PFC.gtOld) + ' 条 = ' + PFC.gt + ' 条）')
w('// -----------------------------------------------------------------------------')
// ---- 🔴 移除被移植替代的老配方（用户 2026-09-26 在 PF.txt 的纸上点名）----
// 🟢 第二版：第一版只记了「调用成功」，用户实测【没删掉】。
//    本版把 event.remove 的【返回值（删了几条）】打出来 —— 这是决定性的运行期判据：
//      · 返回 0  ⇒ 谓词没匹配上（写错 / 配方根本不在表里）
//      · 返回 >0 而配方仍在 ⇒ 是【执行顺序】问题（别人后加）
//    并同时按【显式 id】再删一次做交叉验证。
w('// -----------------------------------------------------------------------------')
w('// 🔴 移除【被移植替代的老配方】')
w('//   用户 2026-09-26 逐字原话：「我在PF.txt中纸写了，移植的配方是原本产线撕裂或者dgy的配方，')
w('//     由于要结合山海，所以我更新了其中的一些配方，但是老配方还在文件里面，因此需要删除」')
w('//   他写在样板里的纸面原文：「注意，此配方为产线撕裂/dgy移植，添加此配方之后需要移除原本的配方」')
w('//   ① minecraft:obsidian —— gtceu:chemical_reactor：水 2147483647mB + 岩浆 1024000mB ⇒ 蒸汽 + 黑曜石×1024')
w('//   ② minecraft:fire_charge —— gtceu:large_chemical_reactor：火药 + 碳粉 + 烈焰粉 ⇒ 火焰弹×3')
w('//   ⚠️ 用户明确【不删】：gtceu:mixer 产出 fire_charge、以及原版合成台那两条。')
w('//   🔴🔴 2026-09-27 探针实证（遍历 55,947 条配方表得到的真实 id）：')
w('//      真实格式 = <命名空间>:<配方类型路径>/<路径>  —— 不是 <ns>:<路径>！')
w('//        真 id = cxhmz:chemical_reactor/water_lava_to_steam')
w('//        真 id = cxbp:large_chemical_reactor/fire_charge_ch')
w('//      我从导出路径猜的 cxhmz:water_lava_to_steam 【少了一整段类型路径】⇒ 永远不匹配 ✗')
w('//      ⚠️ 正则 { id: /...$/ } 本轮实测【也没生效】⇒ 只能用【精确 id】。')
w('//      ⚠️ 探针同时确认【不该删的两条在表里】：gtceu:mixer/fire_charge 与 minecraft:fire_charge（原版合成台）。')
w('//   🔴 2026-09-27 字节码实证：GTRecipe.getResultItem() 恒返回 ItemStack.EMPTY（javap -c 只有 getstatic ItemStack.f_41583_; areturn），')
w('//      而 KubeJS 的 OutputFilter.test() 只有一句 RecipeKJS.hasOutput(match) ⇒ 【{output:...} 对 GT 配方永远不可能匹配】！')
w('//      ⇒ 所以第一版的 {type,output} 谓词【注定无效】（这也是"删不掉"的机械根因）。')
w('//      ⇒ 正确写法是【按 id 删】：GTRecipe implements 原版 Recipe<Container>（javap 类声明），有 id 字段与 getId()。')
w('//      ⇒ 这里用【正则 id】而不是硬猜命名空间（导出路径是 added_recipes/cxhmz/chemical_reactor/water_lava_to_steam.json，')
w('//        命名空间那一段我无法从路径 100% 反推）。')
w('//      🔴 2026-09-30 【事实更正】老配方的来源**不是 mod jar**（旧注释说"来自 mod jar（ns=cxhmz/cxbp）"，那是错的）。')
w('//         现查（在已部署实例的 kubejs\\server_scripts 里逐个文件 grep）真源是【宿主的 KJS 脚本】：')
w('//           · `cxhmz:water_lava_to_steam`  ⇐ [server_scripts]dgy.js:2604')
w('//              `event.recipes.gtceu.chemical_reactor(\'cxhmz:water_lava_to_steam\')`')
w('//           · `cxbp:fire_charge_ch`        ⇐ [server_scripts]产线爆破.js:471')
w('//              `grtr.large_chemical_reactor("cxbp:fire_charge_ch")`')
w('//         ⇒ 运行期 id = `<ns>:<类型路径>/<路径>`（dgy.js 里写的 `cxhmz:water_lava_to_steam`')
w('//           在表里会变成 `cxhmz:chemical_reactor/water_lava_to_steam`）。')
w('//         ⚠️ 这是 KJS 脚本写的配方，但**不影响 event.remove**：我们删的是【配方表里的条目】，')
w('//            不分来源；而且本删除跑在 ServerEvents.loaded ⇒ 一定在全部 ServerEvents.recipes')
w('//            （KJS 注册配方的唯一时机）之后 ⇒ 不会被"后加的脚本又加回来"。')
w('//   ✅ 幂等：重复执行时返回值变 0，不报错。')
w('// -----------------------------------------------------------------------------')
w('ServerEvents.recipes(function (event) {')
w('    // 🔴 2026-09-27 收尾：这里原本有 4 条 event.remove —— 【已删除】，因为实测【全部无效】。')
w('    //    ① {type,output} 两条：字节码证明 GTRecipe.getResultItem() 恒返回 ItemStack.EMPTY，')
w('    //       而 KubeJS 的 OutputFilter.test() 只有一句 RecipeKJS.hasOutput(match)')
w('    //       ⇒ 【{output:...} 对 GT 配方永远不可能匹配】。')
w('    //    ② {id} 精确 / {id:/正则/} 两条：时机太早 —— ServerEvents.recipes 期间 mod 的配方')
w('    //       还没进配方表（实测：此刻移除后，18 秒后的探针仍能看到它）。')
w('    //    ✅ 真正生效的删除已挪到本文件末尾的 ServerEvents.loaded 里（直接改配方表）。')
w('    //    ✅ 那处的判据是自洽的：remove-late 与紧随其后的 probe 用同一套遍历。')
w('    console.info(SHANHAI_PF_TAG + \' remove-old 已停用（本块 4 条实测无效，见下方 ServerEvents.loaded）\')')
w('})')
w('')
w('')
w('ServerEvents.recipes(function (event) {')
w('    var gtr = event.recipes.gtceu')
w('    var ok = 0')
w('    // 🔴 同上：本回调内【重新声明】一次（var 不跨回调共享）')
w('    var ShanhaiStats = null')
w('    try { ShanhaiStats = Java.loadClass(\'com.shanhai.common.recipe.ShanhaiRecipeStats\') } catch (eS) { ShanhaiStats = null }')
w('    var bad = 0')
w('    var errList = \'\'')
w('    var i')
w('    var j')
w('')
w('    for (i = 0; i < shanhaiPfGt.length; i++) {')
w('        var r = shanhaiPfGt[i]')
w('        try {')
w('            // ⚠️ 类型 id 直接当方法名用：gtr[\'photon_siphon\'](...) —— 等价于 gtr.photon_siphon(...)')
w('            var b = gtr[r.type](r.id)')
w('')
w('            var useLevelGate = shanhaiUseLevelGate(r)')
w('')
w('            // 🔴 顺序照宿主脚本：先 .notConsumable(...)，再 .circuit(...)')
w('            //    先例：gtceu.js:3302-3303 / gtceu.js:9955-9957 / gtceu.js:935')
w('            for (j = 0; j < r.notConsumable.length; j++) {')
w('                b = b.notConsumable(r.notConsumable[j])')
w('            }')
w('            // 🔴 物质模块：门槛可用 ⇒ 不写催化剂；否则退回催化剂形态（配方不会消失）')
w('            if (!useLevelGate && r.moduleLevelFallbackCatalyst) {')
w('                b = b.notConsumable(r.moduleLevelFallbackCatalyst)')
w('            }')
w('')
w('            // 🔴 编程电路：.circuit(数字)，参数必须是数字（Rhino 下传字符串会报错）')
w('            if (r.circuit > 0) {')
w('                b = b.circuit(r.circuit)')
w('            }')
w('')
w('            // 🔴 物质模块等级门槛（= 老山海 module_level 配方条件）')
w('            //    写法照宿主现成先例：gtceu.js:8489 .addCondition(new GravityCondition(true))')
w('            if (useLevelGate) {')
w('                var mlp = shanhaiParseModuleLevel(r.moduleLevelRequirement)')
w('                b = b.addCondition(new ModuleLevelCondition(mlp[0], mlp[1]))')
w('            }')
w('')
w('            for (j = 0; j < r.itemInputs.length; j++) {')
w('                b = b.itemInputs(r.itemInputs[j])')
w('            }')
w('            for (j = 0; j < r.inputFluids.length; j++) {')
w('                b = b.inputFluids(r.inputFluids[j])')
w('            }')
w('            for (j = 0; j < r.itemOutputs.length; j++) {')
w('                b = b.itemOutputs(r.itemOutputs[j])')
w('            }')
w('            for (j = 0; j < r.outputFluids.length; j++) {')
w('                b = b.outputFluids(r.outputFluids[j])')
w('            }')
w('//            // 🔴 概率产出（本次新增能力）：' + nosText(NOS_CHANCED) + '的「电子中微子产出概率5%」')
w('            // ⚠️ 第二个 int 是【每超频一级的加成量 tierChanceBoost】，不是"上限"：')
w('            //    字节码实证 GTRecipeBuilder.chancedOutput(ItemStack,int,int)：')
w('            //      67: aload_0 / 68: iload_2 / 69: putfield chance:I')
w('            //      72: aload_0 / 73: iload_3 / 74: putfield tierChanceBoost:I   <-- 第三个参数')
w('            if (r.chancedOutputs) {')
w('                for (j = 0; j < r.chancedOutputs.length; j++) {')
w('                    var co = r.chancedOutputs[j]')
w('                    b = b.chancedOutput(co.item, co.chance, co.tierChanceBoost)')
w('                }')
w('            }')
w('')
w('            b.duration(r.duration).EUt(r.EUt)')
w('            ok = ok + 1')
w('            if (ShanhaiStats) ShanhaiStats.addResult(true)')
w('        } catch (e) {')
w('            bad = bad + 1')
w('            if (ShanhaiStats) ShanhaiStats.addResult(false)')
w('            if (errList.length < 1200) {')
w('                errList = errList + r.id + \' [\' + r.type + \'] => \' + e + \' | \'')
w('            }')
w('        }')
w('    }')
w('')
w('    console.info(SHANHAI_PF_TAG + \' gt_machine ok=\' + ok + \' failed=\' + bad')
w('        + \' total=\' + shanhaiPfGt.length)')
w('    console.info(SHANHAI_PF_TAG + \' module-mode=\' + SHANHAI_PF_MODULE_MODE')
w('        + \' module-level-condition available=\' + SHANHAI_HAS_MODULE_LEVEL_CONDITION)')
w('    if (bad > 0) {')
w('')
w('        console.error(SHANHAI_PF_TAG + \' gt_machine FAILED list: \' + errList)')
w('    }')
w('    // 🔴 打机器可判的那一行：本批 PF 配方的 total/success/failed')
w('    var hasShanhaiStats = ShanhaiStats')
w('    if (hasShanhaiStats) {')
w('        try { ShanhaiStats.reportSummary(\'shanhai_pf\') } catch (eR) { console.error(SHANHAI_PF_TAG + \' reportSummary FAILED: \' + eR) }')
w('    } else {')
w('        console.error(SHANHAI_PF_TAG + \' ShanhaiRecipeStats 类不可用 ⇒ 本批不上报 \')')
w('    }')
w('')
w('    // 逐条回执：证明 builder 收下了什么（方便和 PF.txt 对账）')
w('    for (i = 0; i < shanhaiPfGt.length; i++) {')
w('        var s = shanhaiPfGt[i]')
w('        var gateOn = shanhaiUseLevelGate(s)')
w('        console.info(SHANHAI_PF_TAG + \' spec id=\' + s.id')
w('            + \' type=\' + s.type')
w('            + \' circuit=\' + s.circuit')
w('            + \' in=\' + s.itemInputs.length + \'item\'')
w('            + \' nc=\' + s.notConsumable.length + \'cat\'')
w('            + \' slots=\' + (s.itemInputs.length + s.notConsumable.length + (s.circuit > 0 ? 1 : 0)')
w('                + (!gateOn && s.moduleLevelFallbackCatalyst ? 1 : 0))')
w('            + \' gate=\' + (gateOn ? s.moduleLevelRequirement : \'-\')')
w('            + \' fin=\' + s.inputFluids.length')
w('            + \' out=\' + s.itemOutputs.length + \'item\'')
w('            + \' fout=\' + s.outputFluids.length')
w('            + \' chanced=\' + (s.chancedOutputs ? s.chancedOutputs.length : 0)')
w('            + \' EUt=\' + s.EUt')
w('            + \' duration=\' + s.duration + \'t\')')
w('    }')
w('')
w('    // 🔴 2026-09-26：槽位溢出自检【已移除】—— photon_separation 放宽到 (4, 10, 2, 2) 之后')
w('    //    没有任何配方超出上限（详见文件头 §4）。原先这里会扫 shanhaiPfGt[i].slotOver')
w('    //    并打 [SHANHAI-PF] SLOT-OVER 告警；那个字段与整段告警代码一并删掉了。')
w('    //    ⚠️ 若将来又出现"配方要的槽位 > 该类型 setMaxIOSize"的情形，需要【重新引入】这类检查，')
w('    //       不要以为本文件还带着它。')
w('})')

// 🔴 2026-09-29：回填文件头里的占位符（工作台条数 / 硬编码条数 / 总数）—— 到这里 SHAPED 已经建好了。
var SHAPED_GEN_N = SHAPED.length - SHAPED_HARD_N
var _phFill = [
    [PH_SHAPED, String(SHAPED.length)],
    [PH_SHAPED_HARD, String(SHAPED_HARD_N)],
    [PH_TOTAL, String(SHAPED.length + PFC.gt)]
]
for (var _pf = 0; _pf < _phFill.length; _pf++) {
    for (var _pl = 0; _pl < L.length; _pl++) L[_pl] = L[_pl].split(_phFill[_pf][0]).join(_phFill[_pf][1])
}
// 自检：一个占位符都不许剩（否则产物里会出现 \u0001 控制字符）
for (var _pl2 = 0; _pl2 < L.length; _pl2++) {
    if (L[_pl2].indexOf('\u0001') >= 0) throw new Error('[PF] 🔴 占位符没回填干净，产物第 ' + (_pl2 + 1) + ' 行：' + JSON.stringify(L[_pl2]))
}
console.log('[PF] 条数回填：工作台 ' + SHAPED.length + ' 条（硬编码 ' + SHAPED_HARD_N + ' + PF 生成 ' + SHAPED_GEN_N + '）'
    + ' + GT ' + PFC.gt + ' 条 = 合计 ' + (SHAPED.length + PFC.gt) + ' 条')

// 🔴 2026-09-27 用户点单第 1 条：产物改名 [server_scripts]shanhai_recipes.js（带 [server_scripts] 前缀，照实例命名）。
//    产物 = 【生成部分】+【手写区】：手写区在文件末尾，生成器原样保留。
//    ⚠️ 仍然【不落地到用户实例】—— 交付给队长，由队长决定什么时候放进 kubejs\。
var OUT_DIR = path.join(REPO, 'kubejs', 'server_scripts') + path.sep
// 🔴 2026-09-27 用户点单第 3/4 条：产物 = 【生成部分】+【手写区】（zero_point_power 并进来后住这里）。
const M_BEGIN = "// ═══ 手写区 开始（生成器不会动这一段）═══"
const M_END   = "// ═══ 手写区 结束 ═══"
const TARGET = OUT_DIR + '[server_scripts]shanhai_recipes.js'
let HANDS = ['global.SHANHAI_RECIPES_HAND = {}']
let handNote = '(首次生成：写入默认空手写区)'
if (fs.existsSync(TARGET)) {
    const prev = fs.readFileSync(TARGET, 'utf8').split(/\r?\n/)
    const a = prev.findIndex(l => l.indexOf(M_BEGIN) >= 0)
    const b = prev.findIndex(l => l.indexOf(M_END) >= 0)
    if (a >= 0 && b > a) { HANDS = prev.slice(a + 1, b); handNote = '(保留自旧产物：' + HANDS.length + ' 行)' }
    else { handNote = '(旧产物无手写区标记 => 写入默认；原文件已备份到 .no-handreg-bak)'; fs.writeFileSync(TARGET + '.no-handreg-bak', prev.join('\r\n'), 'utf8') }
}
fs.writeFileSync(TARGET, L.join('\r\n') + '\r\n\r\n' + M_BEGIN + '\r\n' + HANDS.join('\r\n') + '\r\n' + M_END + '\r\n', 'utf8')
console.log('  手写区 ' + handNote)
// 报告被跳过的行
// 🔴 2026-09-29：原先把两种完全不同的行混在一起报（都叫"跳过"），会让人以为丢了配方：
//    ① 工作台行（`srcType=null`，即「合成样板」）—— **正常**，它们走 event.shaped；
//    ② 真有类型、但 TYPE_ID 里没有映射 —— **这才是要处理的**（该配方根本不会生成）。
// 🔴 2026-09-30：这段报告【已上移】到 `specs` 构建之后、【写产物之前】（见上面那个"交叉核对"块），
//    并且已经升级成硬断言（缺键 ⇒ 抛错、拒绝写产物）。放在这里太晚了 —— 那时产物已经落盘。
//    两行日志的形态仍是用户要的那种：`✅ 合成样板分流 N 条（走工作台路径，正常）` ＋
//    `🔴 真·跳过 M 条（缺类型键 ⇒ 配方会消失）`，只是打得更早。
console.log('written -> ' + TARGET)

// ---------------------------------------------------------------- report
console.log('new specs = ' + specs.length)
// ── 槽位自检（构建期；**不进产物**）────────────────────────────────────────────
// 🔴 2026-09-29：这里补上【逐槽点名】。原先只印 `used=(..) cap=(..)`，看不出**是哪一格**超的，
//    也没说明"这台机器是哪个元件/哪条样板"，报出来也没法给用户裁决 ⇒ 现在四格分别判，
//    并带上元件名与超出的那一格的名字。
var SLOT_KIND = ['物品输入', '物品输出', '流体输入', '流体输出']
var over = []
for (var o = 0; o < specs.length; o++) if (specs[o].slots.over) over.push(specs[o])
console.log('slot overflows = ' + over.length + '   （判据：配方实际占用的格数 > 该类型 setMaxIOSize 的对应位；'
    + '上限来自真源现算，见文件头 CAP 说明）')
for (var o2 = 0; o2 < over.length; o2++) {
    var sp2 = over[o2]
    var usedArr = [sp2.slots.itemIn, sp2.slots.itemOut, sp2.slots.fluidIn, sp2.slots.fluidOut]
    var bad = []
    for (var bo = 0; bo < 4; bo++) if (usedArr[bo] > sp2.slots.cap[bo]) bad.push(SLOT_KIND[bo] + ' ' + usedArr[bo] + ' > ' + sp2.slots.cap[bo])
    console.log('  🔴 PF.txt 第 ' + sp2.row.no + ' 条 | 元件「' + sp2.row.cell + '」| 类型 ' + sp2.type + ' | 产出 ' + sp2.itemOutputs.join(' + '))
    console.log('       id=' + sp2.id + '   超出：' + bad.join('；') + '   （四格 used=' + usedArr.join('/') + ' cap=' + sp2.slots.cap.join('/') + '）')
}
console.log('--- spec summary (id / type / slots used vs cap) ---')
for (var o3 = 0; o3 < specs.length; o3++) {
    var sp3 = specs[o3]
    console.log('  #' + String(sp3.row.no).padStart(2) + ' ' + sp3.id + '  ' + sp3.type
        + '  slots(' + sp3.slots.itemIn + '/' + sp3.slots.cap[0] + ',' + sp3.slots.itemOut + '/' + sp3.slots.cap[1]
        + ',' + sp3.slots.fluidIn + '/' + sp3.slots.cap[2] + ',' + sp3.slots.fluidOut + '/' + sp3.slots.cap[3] + ')'
        + (sp3.flags.length ? '   FLAGS=' + sp3.flags.length : ''))
}
console.log('--- 物质模块决策表（🔴 2026-10-03 判据 = 只看纸：纸上有「' + PAPER_MODULE_CATALYST + '」⇒ 不消耗，没有 ⇒ 消耗）---')
console.log('  口径：consume = 纸上【没有】催化剂纸 ⇒ 模块进 itemInputs 被消耗; gate = 纸上有 ⇒ 山海类型 ⇒ 等级门槛(不消耗); catalyst = 纸上有 ⇒ 非山海类型 ⇒ notConsumable 真催化剂(不消耗)')
console.log('  ⚠️ isModuleRecipe / isWorldlineRecipe / 产出∈残片族 三个读数【不再参与判定】，只作诊断与口径核对')
console.log('  ⚠️ "本版落法变了"的判据 = moduleDecisionLegacy(被取代的旧判据) ≠ moduleDecision(新判据)')
for (var o4 = 0; o4 < specs.length; o4++) {
    var sp4 = specs[o4]
    if (!sp4.hasModuleInput) continue
    var mods4 = []
    for (var o5 = 0; o5 < sp4.itemInputs.length; o5++) if (MATERIAL_MODULES[idOfSlotText(sp4.itemInputs[o5])]) mods4.push(sp4.itemInputs[o5] + '(消耗)')
    for (var o6 = 0; o6 < sp4.notConsumable.length; o6++) if (MATERIAL_MODULES[idOfSlotText(sp4.notConsumable[o6])]) mods4.push(sp4.notConsumable[o6] + '(催化剂)')
    if (sp4.moduleGate) mods4.push(sp4.moduleGate + '(门槛)')
    var shardHit = []
    for (var o8 = 0; o8 < sp4.row.outs.length; o8++) { var od8 = sp4.row.outs[o8].d; if (od8 && od8.kind === 'ITEM' && SHARD_FAMILY[od8.id]) shardHit.push(od8.id) }
    console.log('  #' + String(sp4.row.no).padStart(2) + '  ' + sp4.type + '  ⇒ ' + sp4.moduleDecision
        + '  | 纸=' + (sp4.hasModuleCatalystPaper ? '有(第' + sp4.moduleCatalystPaperSlot + '格)' : '无')
        + '  | ' + mods4.join(' , ')
        + '  | isModuleRecipe=' + sp4.isModuleRecipe + ' isWorldline=' + sp4.isWorldlineRecipe
        + ' 产出∈残片族=' + (shardHit.length ? 'YES(' + shardHit.join('+') + ')' : 'no')
        + (sp4.moduleLandedChanged ? '   ⟵ 🔴 落法相对上一版变了（旧判据=' + sp4.moduleDecisionLegacy + '）' : ''))
}
console.log('--- 本版落法变化的配方（新旧判据结论不同的，完整 id）---')
var _chgList = []
for (var oc = 0; oc < specs.length; oc++) if (specs[oc].hasModuleInput && specs[oc].moduleLandedChanged) _chgList.push(specs[oc].id + '(#' + specs[oc].row.no + ' ' + specs[oc].moduleDecisionLegacy + '⇒' + specs[oc].moduleDecision + ')')
console.log(_chgList.length ? '  ' + _chgList.join('\n  ') : '  (无)')
console.log('--- 口径核对：产出含物质模块（= 制作物质模块）的配方 ⇒ 应当【消耗】，纸上应当【没有】催化剂纸 ---')
var _mrOk = 0, _mrBad = []
for (var om = 0; om < specs.length; om++) {
    if (!specs[om].isModuleRecipe) continue
    if (!specs[om].hasModuleCatalystPaper && specs[om].moduleDecision === 'consume') _mrOk++
    else _mrBad.push(specs[om].id + '(纸=' + specs[om].hasModuleCatalystPaper + ' 决策=' + specs[om].moduleDecision + ')')
}
console.log('  一致 = ' + _mrOk + ' 条；打架 = ' + _mrBad.length + ' 条' + (_mrBad.length ? '：' + _mrBad.join(' / ') : ''))
console.log('--- 非山海类型且带物质模块输入的类型（有纸时走 catalyst 真催化剂，不设等级门槛）---')
var uKeys = Object.keys(UNKNOWN_MODULE_TYPES)
if (uKeys.length) for (var o7 = 0; o7 < uKeys.length; o7++) console.log('  ⚠️ ' + uKeys[o7] + ' 不在 SHANHAI_TYPES 里 ⇒ 该类型的物质模块按"真催化剂"落（不设等级门槛）')
else console.log('  (无)')

// ═══════════════════════════════════════════════════════════════════════════════
// 🔴 [SHANHAI-DESC] —— 本轮「描述逐种核实 + 落地」的可 grep 证据行
//    用户要求：**每条落地的描述 ⇒ 打一行** `[SHANHAI-DESC] <原文> ⇒ <落成了什么>`。
//    本段只「读」specs 并把已有结论打成一行，不改任何配方数据。
//    ⚠️ 判据自洽：这些字符串都是从**纸面原文**来的常量，不是我复述的。
// ═══════════════════════════════════════════════════════════════════════════════
console.log('--- [SHANHAI-DESC] 描述落地证据（逐条）---')
// ⚠️ 这两个计数【刻意分开】—— 曾经把"有这张纸"当成"落成了"，于是 guard 用例里
//    "已落地 2 行 / 没挂上 2 行" 同时出现（自相矛盾）。判据必须是 quarkCatalystLanded 非空。
var _descSeen = 0, _descLanded = 0
for (var o8 = 0; o8 < specs.length; o8++) {
    if (!specs[o8].descNote) continue
    _descSeen++
    if (specs[o8].quarkCatalystLanded && specs[o8].quarkCatalystLanded.length) _descLanded++
    console.log('  ' + specs[o8].descNote + '   ｜ PF 第 ' + specs[o8].row.no + ' 条 ｜ id=' + specs[o8].id)
}
if (!_descSeen) console.log('  （本次没有任何"需要落地"的描述）')
// 反面对照：纸上写了、但本条没挂上催化剂的（应当为 0；不为 0 就是静默漏了）
var _descMissed = 0
for (var o9 = 0; o9 < specs.length; o9++) {
    var sp9 = specs[o9]
    var hasPaper = false
    for (var pa = 0; pa < sp9.row.notes.length; pa++) if (sp9.row.notes[pa].desc.name === PAPER_QUARK_CATALYST) hasPaper = true
    if (hasPaper && (!sp9.quarkCatalystLanded || !sp9.quarkCatalystLanded.length)) _descMissed++
}
console.log('  [SHANHAI-DESC] 「' + PAPER_QUARK_CATALYST + '」纸共 ' + NOS_QUARK_CATALYST.length + ' 行'
    + '（' + nosText(NOS_QUARK_CATALYST) + '）；其中有这张纸的 = ' + _descSeen
    + ' 行，【确实落成不消耗催化剂】= ' + _descLanded
    + ' 行，【纸在但没挂上】= ' + _descMissed + ' 行（不为 0 就是静默漏，必须解释）')

// 🔴 2026-09-29 过期防线：specs.json 形状【保持裸数组】不变（消费者按裸数组解析），
//    它的源头声明走同目录 `_provenance.json`（见 provenance.js 顶部说明）。
var SPECS_JSON = BASE + 'specs.json'
fs.writeFileSync(SPECS_JSON, JSON.stringify(specs, null, 1), 'utf8')
var _sRec = PROV.record(SPECS_JSON, 'kubejs\\_generators\\gen_kjs.js', PF_SRC)
console.log('[PROV] specs.json       自证: srcSha256=' + _sRec.srcSha256 + ' artifactSha256=' + _sRec.artifactSha256 + ' bytes=' + _sRec.artifactBytes)
// 🔴 产物 [server_scripts]shanhai_recipes.js 也登记一份（它同样"对应某一版 PF.txt"，
//    它是【交付物】而不是中间产物，所以只登记、不加机器可解析标记行——加了会改变产物字节）。
var _aRec = PROV.record(TARGET, 'kubejs\\_generators\\gen_kjs.js', PF_SRC)
console.log('[PROV] 产物 自证: srcSha256=' + _aRec.srcSha256 + ' artifactSha256=' + _aRec.artifactSha256 + ' bytes=' + _aRec.artifactBytes)
