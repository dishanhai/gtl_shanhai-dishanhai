// ═══════════════════════════════════════════════════════════════════════════════
// mod_caps.js —— 【真源③】「注册在 gtceu 命名空间、但不是 gtceu 本体注册的」配方类型
//                 ⇒ 从【注册它的那个 mod jar】的字节码里现读 setMaxIOSize
// ═══════════════════════════════════════════════════════════════════════════════
// 🔴 为什么必须有它（本条是事故根因，不是"锦上添花"）：
//   2026-10-04 用户同步失败，`gen_kjs.js` 的 CAP 守卫在 L615 拒绝写产物，原文：
//     「【用到的配方类型在两个真源里都查不到】1 个：gtceu:fishing_ground（纸上叫「渔场」）」
//   而 `gtceu:fishing_ground` **是合法的**：
//     · 游戏导出表 `local\kubejs\export\recipes\gtceu\fishing_ground\` 里有 8 条配方；
//     · 它由 **gtlcore** 注册（`gtlcore-1.2.3.2-fix3.jar!org/gtlcore/gtlcore/common/data/GTLRecipeTypes.class`
//       的 `<clinit>` 里 `register("fishing_ground", "multiblock", …)` + `setMaxIOSize(2,24,0,0)`），
//       中文名「渔场」写在同一个 jar 的 `assets/gtceu/lang/zh_cn.json`（键名仍是 `gtceu.*`）。
//   ⇒ 根因：CAP 守卫当时只有两个真源 —— ① shanhai 自己的 Java 活代码 ② gtceu 本体的 javap 快照。
//      **两者都只能看见"各自 mod 注册的类型"**，而本整合包把大量别的 mod 的类型注册在 `gtceu:` 命名空间下
//      （实测游戏导出表里 133 个类型目录，其中 100+ 个不在真源②里）
//      ⇒ 少这一源，"别人的类型"会被误判成笔误，**守卫自己把合法同步拦死**。
//
// ── 做法（照真源②的 javap 路子，但【自动定位】而不是人手挑类）───────────────
//   ① 扫 `mods\*.jar` 里每个 `.class`，找**常量池里含 `setMaxIOSize` 的那些类**
//      （实测 91 个 jar / 34,138 个 class / 命中 10 个类 / 耗时 **1.1 秒** ⇒ 完全不值得做磁盘缓存）；
//   ② 每个命中的类**只从它自己的那个 jar 里**解出来（放进 `<tmp>\j<N>\...` 并把该目录当 -cp），
//      跑 `javap -p -c` —— 用 -cp 按全限定名取，避免重名类串味；
//   ③ 用**与真源②完全相同的那一个解析器**（`parseJavapCaps`，本模块导出、gen_kjs.js 引用）
//      还原 `id ⇒ (a,b,c,d)`。
//
// ── 🔴 两条不许省的护栏（否则这个模块会【放宽】守卫，那是红线）───────────────
//   G-③a【旁证】不是每个字符串都可信。`register(name, cat)` 的两个字符串是**往前回看**取的，
//        遇到"id 来自字段/数组"的写法就可能把**无关字符串**误当 id（= 凭空造出一个不存在的类型名，
//        那会让笔误也被放行）。⇒ 本模块给出的 id 必须**另有一个独立来源旁证**才算数：
//        该 id 在某个 mod jar 的 `assets/*/lang/zh_cn.json` 里有 `gtceu.<id>` 键，**或**
//        在游戏导出表 `export\recipes\<ns>\<id>\` 里有目录。
//        ⚠️ 这一条**零代价**，不是"缩小覆盖面"：纸上写的类型名本来就是靠 lang 反查表变成 id 的
//        （`type_names.js` 只把"有中文名的 id"放进反查表）⇒ 没有 lang 名的 id **根本不可能从纸上进来**。
//        无法旁证的 id 会进 `unverified` 如实报出来，**不静默丢弃**。
//   G-③b【与真源②交叉验证】gtceu 本体自己的 `GTRecipeTypes.class` 也在扫描范围内
//        ⇒ 它与真源②的 javap 快照**读的是同一个类的同一段字节码**，两边必须**逐条相等**。
//        对不上 ⇒ 说明其中一个过期/解析器坏了 ⇒ 由 gen_kjs.js 抛错拒绝写产物（CAP 值不可信）。
//
// ── 怎么复现 / 审计 ──────────────────────────────────────────────────────────
//   · 正常生成：它就是 `node kubejs\_generators\gen_kjs.js` 的一部分（现读现算，无缓存、无快照）；
//   · 单独自检（打印每个命中类、逐条 id ⇒ 四元组、旁证情况）：
//       node kubejs\_generators\mod_caps.js --mods "<实例>\mods"
//   · 落一份**审计用**的原始 javap 转储（守卫不读它，纯粹给人看/存档）：
//       node kubejs\_generators\mod_caps.js --dump "<实例>\mods" recipe-convert\javap\mod_caps.audit.txt
//
// 本模块【不写任何游戏文件】、不动 jar、不构建 jar；只读 mods 目录 + 只跑 javap（反汇编，不执行任何代码）。
'use strict'
var fs = require('fs')
var path = require('path')
var os = require('os')
var zlib = require('zlib')
var crypto = require('crypto')
var cp = require('child_process')

// ═══════════════════════════════════════════════════════════════════════════════
// 1. 最小 ZIP 读取器（与 type_names.js 同源同纪律：本工程没有 node_modules，故意不引依赖）
// ═══════════════════════════════════════════════════════════════════════════════
var EOCD_SIG = 0x06054b50, CEN_SIG = 0x02014b50, LOC_SIG = 0x04034b50

/** 打开一个 zip，返回 { list():[names], read(name):Buffer, close() }。 */
function openZip(file) {
    var fd = fs.openSync(file, 'r')
    var size = fs.fstatSync(fd).size
    var tailLen = Math.min(size, 22 + 65535)
    var tail = Buffer.alloc(tailLen)
    fs.readSync(fd, tail, 0, tailLen, size - tailLen)
    var eocd = -1
    for (var i = tailLen - 22; i >= 0; i--) { if (tail.readUInt32LE(i) === EOCD_SIG) { eocd = i; break } }
    if (eocd < 0) { fs.closeSync(fd); throw new Error('不是 zip（找不到中央目录结尾记录 EOCD）：' + file) }
    var count = tail.readUInt16LE(eocd + 10)
    var cdSize = tail.readUInt32LE(eocd + 12)
    var cdOff = tail.readUInt32LE(eocd + 16)
    if (cdOff === 0xFFFFFFFF || cdSize === 0xFFFFFFFF) { fs.closeSync(fd); throw new Error('这个 zip 用了 zip64（暂不支持）：' + file) }
    var cd = Buffer.alloc(cdSize)
    fs.readSync(fd, cd, 0, cdSize, cdOff)
    var entries = {}, order = [], p = 0
    for (var n = 0; n < count && p + 46 <= cdSize; n++) {
        if (cd.readUInt32LE(p) !== CEN_SIG) break
        var method = cd.readUInt16LE(p + 10)
        var compSize = cd.readUInt32LE(p + 20)
        var nameLen = cd.readUInt16LE(p + 28)
        var extraLen = cd.readUInt16LE(p + 30)
        var commentLen = cd.readUInt16LE(p + 32)
        var locOff = cd.readUInt32LE(p + 42)
        var name = cd.toString('utf8', p + 46, p + 46 + nameLen)
        entries[name] = { method: method, compSize: compSize, locOff: locOff }
        order.push(name)
        p += 46 + nameLen + extraLen + commentLen
    }
    return {
        file: file,
        list: function () { return order },
        read: function (nm) {
            var e = entries[nm]
            if (!e) throw new Error('zip 里没有这个条目：' + nm + ' @ ' + file)
            var lh = Buffer.alloc(30)
            fs.readSync(fd, lh, 0, 30, e.locOff)
            if (lh.readUInt32LE(0) !== LOC_SIG) throw new Error('本地文件头签名不对：' + nm + ' @ ' + file)
            var lNameLen = lh.readUInt16LE(26), lExtraLen = lh.readUInt16LE(28)
            var dataOff = e.locOff + 30 + lNameLen + lExtraLen
            var raw = Buffer.alloc(e.compSize)
            fs.readSync(fd, raw, 0, e.compSize, dataOff)
            if (e.method === 0) return raw
            if (e.method === 8) return zlib.inflateRawSync(raw)
            throw new Error('不支持的压缩方式 ' + e.method + '：' + nm + ' @ ' + file)
        },
        close: function () { try { fs.closeSync(fd) } catch (e) { } }
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// 2. javap 定位（缺了就【响亮抛错】，绝不静默退化成"没有真源③"）
// ═══════════════════════════════════════════════════════════════════════════════
// 为什么要找这么多地方：真源③ 是**现读现算**的，javap 是它的硬依赖。
// 静默退化 = 又回到"CAP 守卫只有两个真源"那个 bug 上 ⇒ 所以宁可直接失败。
function findJavap() {
    var tried = []
    var cands = []
    if (process.env.SH_JAVAP) { cands.push(process.env.SH_JAVAP); tried.push('环境变量 SH_JAVAP=' + process.env.SH_JAVAP) }
    if (process.env.JAVA_HOME) { cands.push(path.join(process.env.JAVA_HOME, 'bin', 'javap.exe')); tried.push('JAVA_HOME\\bin\\javap.exe') }
    cands.push(path.join(process.env.USERPROFILE || '', '.jdks'))   // 特殊：目录，下面展开
    tried.push('%USERPROFILE%\\.jdks\\*\\bin\\javap.exe')
    var progFiles = [process.env['ProgramFiles'] || 'C:\\Program Files', process.env['ProgramFiles(x86)'] || '']
    for (var p = 0; p < progFiles.length; p++) {
        if (!progFiles[p]) continue
        var ad = path.join(progFiles[p], 'Eclipse Adoptium')
        try {
            fs.readdirSync(ad).forEach(function (d) { cands.push(path.join(ad, d, 'bin', 'javap.exe')) })
            tried.push(ad + '\\*\\bin\\javap.exe')
        } catch (e) { }
    }
    // 上面 push 进去的 .jdks 目录：展开成它下面的每个 JDK
    var jdks = path.join(process.env.USERPROFILE || '', '.jdks')
    try { fs.readdirSync(jdks).forEach(function (d) { cands.push(path.join(jdks, d, 'bin', 'javap.exe')) }) } catch (e) { }
    for (var i = 0; i < cands.length; i++) {
        try { if (cands[i] && fs.statSync(cands[i]).isFile()) return { path: cands[i], tries: tried, how: '文件探测' } } catch (e) { }
    }
    // 最后才轮到 PATH（用 where 而不是硬拼 —— PATH 上可能有 .cmd 之类的壳）
    try {
        var r = cp.spawnSync('where', ['javap'], { encoding: 'utf8' })
        if (r.status === 0 && r.stdout) {
            var lines = r.stdout.split(/\r?\n/).filter(function (x) { return x.trim() })
            if (lines.length) return { path: lines[0].trim(), tries: tried.concat(['PATH(where javap)']), how: 'PATH' }
        }
    } catch (e) { }
    throw new Error('[PF] 🔴 找不到 `javap`（真源③ 需要它把注册类的 `setMaxIOSize` 从字节码里读出来）。\n'
        + '    已找过：' + tried.join(' ／ ') + '\n'
        + '    ⇒ 修法（任选）：① 设 `$env:SH_JAVAP="<JDK>\\bin\\javap.exe"`；② 设 `$env:JAVA_HOME` 指向一个 JDK；'
        + '③ 把 `<JDK>\\bin` 加进 PATH。\n'
        + '    ⇒ 为什么不能"找不到就算了"：那样 CAP 守卫会退回"只有两个真源"，'
        + '把别的 mod 注册在 gtceu 命名空间下的**合法类型**（如 `gtceu:fishing_ground`）误判成笔误 ⇒ 又一次同步失败。')
}

// ═══════════════════════════════════════════════════════════════════════════════
// 3. javap 转储解析器 —— 【真源②与真源③共用这一个】
// ═══════════════════════════════════════════════════════════════════════════════
// 纪律：一个解析器、两个消费者。把它复制一份给真源③用，等于让"两边对不上"这件事
//      在没人看得见的地方发生（本工程吃过 7 次"检查器自己的假设错了"的亏）。
/**
 * @param {string} text  `javap -p -c` 的转储文本（**单个类**的；不要把多个类拼在一起传进来，
 *                       否则"往前回看"会跨过类边界取到别的类的字符串）
 * @returns {{caps:Object, problems:Array<string>}}
 */
function parseJavapCaps(text) {
    var lines = String(text).split(/\r?\n/)
    var caps = {}, problems = [], noCaps = []
    for (var i = 0; i < lines.length; i++) {
        var t = lines[i].replace(/^\s+/, '')
        if (!/register:\(Ljava\/lang\/String;Ljava\/lang\/String;/.test(t)) continue
        var strs = []
        // 🔴 回看窗口 6 → 25 行的历史（照抄真源②的注释，同一个坑）：
        //    `electric_furnace`（中文名「电炉」）的两条 String 在 register 之前 7、8 行
        //    （中间还夹着 `anewarray RecipeType` + `iconst_0` + `getstatic RecipeType.f_44108_` + `aastore`），
        //    6 行窗口会把它顶出去 ⇒「电炉」反查不到 ⇒ 纸上写它会被误判成笔误。
        //    放大是安全的：register 的两个 String 参数必然紧邻在它的数组构造之前
        //    ⇒「往前最近的 2 条 String」永远是它自己的参数。
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
            var v = undefined
            if (/^iconst_m1$/.test(op)) v = -1
            else { var m1 = /^iconst_([0-5])$/.exec(op); if (m1) v = parseInt(m1[1], 10) }
            if (v === undefined) { var m2 = /^bipush\s+(-?\d+)$/.exec(op + ' ' + rest); if (m2) v = parseInt(m2[1], 10) }
            if (v === undefined) { var m3 = /^sipush\s+(-?\d+)$/.exec(op + ' ' + rest); if (m3) v = parseInt(m3[1], 10) }
            if (v === undefined) { problems.push('L' + (k2 + 1) + ' ' + id + ': 认不出的操作数 `' + s.trim() + '`'); break }
            pushes.push(v)
        }
        if (!found) { problems.push('L' + (i + 1) + ' ' + id + ': 之后找不到 setMaxIOSize'); noCaps.push(id); continue }
        if (pushes.length !== 4) { problems.push(id + ': 操作数个数 = ' + pushes.length + '（不是 4）'); continue }
        caps[id] = pushes
    }
    return { caps: caps, problems: problems, noCaps: noCaps }
}

// ═══════════════════════════════════════════════════════════════════════════════
// 4. 旁证（G-③a）：lang 键 ∪ 游戏导出表目录
// ═══════════════════════════════════════════════════════════════════════════════
var LANG_ENTRY_RE = /^assets\/([^/]+)\/lang\/zh_cn\.json$/
var TYPE_KEY_RE = /^gtceu\.([a-z0-9_]{1,64})$/
/** 从 mods 里现读「有 `gtceu.<id>` 中文名的 id 集合」。 */
function readLangIds(modsDir, jars) {
    var ids = {}, jarsHit = []
    for (var j = 0; j < jars.length; j++) {
        var z
        try { z = openZip(path.join(modsDir, jars[j])) } catch (e) { continue }
        var names = z.list(), got = 0
        for (var i = 0; i < names.length; i++) {
            if (!LANG_ENTRY_RE.test(names[i])) continue
            var obj
            try {
                var raw = z.read(names[i]).toString('utf8')
                if (raw.charCodeAt(0) === 0xFEFF) raw = raw.slice(1)
                obj = JSON.parse(raw)
            } catch (e) { continue }
            var ks = Object.keys(obj)
            for (var k = 0; k < ks.length; k++) { var m = TYPE_KEY_RE.exec(ks[k]); if (m) { ids[m[1]] = 1; got++ } }
        }
        z.close()
        if (got) jarsHit.push(jars[j] + '(' + got + ')')
    }
    return { ids: ids, jarsHit: jarsHit }
}
/** 从游戏导出目录 `export\recipes\**` 现收「类型目录名」（同 type_names.scanExportTypeDirs 口径）。 */
function readExportTypeDirs(exportRecipesRoot, maxDepth) {
    var out = {}
    var depth = maxDepth === undefined ? 3 : maxDepth
    function walk(dir, d) {
        if (d > depth) return
        var ents
        try { ents = fs.readdirSync(dir, { withFileTypes: true }) } catch (e) { return }
        for (var i = 0; i < ents.length; i++) {
            if (!ents[i].isDirectory()) continue
            var nm = ents[i].name
            if (/^[a-z0-9_]{1,64}$/.test(nm)) out[nm] = 1
            walk(path.join(dir, nm), d + 1)
        }
    }
    if (exportRecipesRoot) walk(exportRecipesRoot, 1)
    return out
}

// ═══════════════════════════════════════════════════════════════════════════════
// 5. 主入口
// ═══════════════════════════════════════════════════════════════════════════════
/**
 * @param {Object} o
 *   o.modsDir            = 实例的 mods 目录（必备）
 *   o.exportRecipesRoot  = 游戏导出配方根（可选；用于旁证 G-③a）
 *   o.log                = 可选日志函数
 *   o.keepTmp            = 调试用：保留临时解包目录
 *   o.onDump             = 可选：(jarName, className, javapText) => void，用于 --dump 模式
 * @returns {{caps:Object, origin:Object, meta:Object}}
 */
function build(o) {
    var t0 = Date.now()
    var log = o.log || function () { }
    var modsDir = o.modsDir
    if (!modsDir) throw new Error('[PF] 🔴 mod_caps.build 缺 modsDir')
    var jars
    try { jars = fs.readdirSync(modsDir).filter(function (f) { return /\.jar$/i.test(f) }).sort() }
    catch (e) {
        throw new Error('[PF] 🔴 读不了 mods 目录：' + modsDir + '（' + e.message + '）\n'
            + '    ⇒ 真源③ 是【现读实例 mods】的，读不到就不能假装它有 ⇒ 拒绝继续。')
    }

    var jv = findJavap()
    var javaVer = ''
    try { var vr = cp.spawnSync(jv.path, ['-version'], { encoding: 'utf8' }); javaVer = String(vr.stderr || vr.stdout || '').split(/\r?\n/)[0].trim() } catch (e) { }
    var javapVersion = 'javap @ ' + jv.path + (javaVer ? ' (' + javaVer + ')' : '')

    // ── ① 扫类：常量池里含 `setMaxIOSize` 的 .class（实测 91 jar / 34,138 class / 命中 10 / 1.1 秒）
    var NEEDLE = Buffer.from('setMaxIOSize', 'utf8')
    var matched = []          // {jar, entry}
    var classScanned = 0, jarScanned = 0
    var unreadableJars = []
    for (var j = 0; j < jars.length; j++) {
        var z
        try { z = openZip(path.join(modsDir, jars[j])) } catch (e) { unreadableJars.push(jars[j] + '（' + e.message + '）'); continue }
        jarScanned++
        var names = z.list()
        for (var i = 0; i < names.length; i++) {
            if (!/\.class$/.test(names[i])) continue
            classScanned++
            var buf
            try { buf = z.read(names[i]) } catch (e) { continue }
            if (buf.indexOf(NEEDLE) >= 0) matched.push({ jar: jars[j], entry: names[i] })
        }
        z.close()
    }
    var scanSeconds = Math.round((Date.now() - t0)) / 1000

    // ── ② 逐个解包 + javap（每个类只从它自己的 jar 里解出来，-cp 指向该目录 ⇒ 不会串味）
    var tmpRoot = fs.mkdtempSync(path.join(os.tmpdir(), 'shanhai-modcaps-'))
    var caps = {}, origin = {}, fromJar = {}
    var conflicts = [], perClass = [], javapProblems = [], noCapsAt = {}
    try {
        for (var m = 0; m < matched.length; m++) {
            var jarName = matched[m].jar, entry = matched[m].entry
            var fqcn = entry.replace(/\.class$/, '').replace(/\//g, '.')
            var dir = path.join(tmpRoot, 'j' + m)
            var outFile = path.join(dir, entry.replace(/\//g, path.sep))
            try {
                fs.mkdirSync(path.dirname(outFile), { recursive: true })
                var zz = openZip(path.join(modsDir, jarName))
                fs.writeFileSync(outFile, zz.read(entry))
                zz.close()
            } catch (e) {
                javapProblems.push('解不出 ' + jarName + '!' + entry + '：' + e.message)
                continue
            }
            var r = cp.spawnSync(jv.path, ['-p', '-c', '-cp', dir, fqcn], { encoding: 'utf8', maxBuffer: 256 * 1024 * 1024 })
            if (r.status !== 0 || !r.stdout) {
                javapProblems.push('javap 失败 ' + jarName + '!' + fqcn + '（status=' + r.status + '）：' + String(r.stderr || '').split(/\r?\n/)[0])
                continue
            }
            if (o.onDump) o.onDump(jarName, fqcn, r.stdout)
            var parsed = parseJavapCaps(r.stdout)
            var ids = Object.keys(parsed.caps)
            perClass.push({ jar: jarName, cls: fqcn, ids: ids.length })
            for (var q = 0; q < parsed.problems.length; q++) javapProblems.push(jarName + '!' + fqcn + ' :: ' + parsed.problems[q])
            // 「确实注册了、但那个类**没有** setMaxIOSize」——如实记下来（如 gtladditions 的
            // `transmutation_block_conversion`：它只 setXEIVisible(false)，category = "dummy"）。
            // 这类 id 读不到上限 ⇒ 仍然进不了 CAP（不许编一个数）；但错误信息里要能说清
            // "它不是你打错字，是它真的没上限数据"，否则下次又是一场误判。
            for (var q2 = 0; q2 < parsed.noCaps.length; q2++) noCapsAt[parsed.noCaps[q2]] = jarName + '!' + fqcn
            for (var k3 = 0; k3 < ids.length; k3++) {
                var id = ids[k3], tuple = parsed.caps[id]
                if (caps[id]) {
                    if (caps[id].join(',') !== tuple.join(',')) {
                        conflicts.push({ id: id, a: { v: caps[id], at: fromJar[id] }, b: { v: tuple, at: jarName + '!' + fqcn } })
                    }
                    continue
                }
                caps[id] = tuple
                origin[id] = jarName + '!' + fqcn
                fromJar[id] = jarName + '!' + fqcn
            }
        }
    } finally {
        if (!o.keepTmp) { try { fs.rmSync(tmpRoot, { recursive: true, force: true }) } catch (e) { } }
    }

    // ── ③ 旁证 G-③a：id 必须另有独立来源（lang 键 或 游戏导出表目录）才算数
    var lang = readLangIds(modsDir, jars)
    var expDirs = o.exportRecipesRoot ? readExportTypeDirs(o.exportRecipesRoot) : {}
    var unverified = []
    var kept = {}, keptOrigin = {}
    var idsAll = Object.keys(caps)
    for (var u = 0; u < idsAll.length; u++) {
        var idu = idsAll[u]
        if (lang.ids[idu] || expDirs[idu]) { kept[idu] = caps[idu]; keptOrigin[idu] = origin[idu] }
        else unverified.push(idu + ' @ ' + origin[idu])
    }

    var meta = {
        seconds: Math.round((Date.now() - t0)) / 1000,
        scanSeconds: scanSeconds,
        javapVersion: javapVersion,
        javapPath: jv.path,
        javapHow: jv.how,
        modsDir: modsDir,
        jarCount: jars.length,
        jarScanned: jarScanned,
        classScanned: classScanned,
        matchedClasses: perClass,
        unreadableJars: unreadableJars,
        javapProblems: javapProblems,
        conflicts: conflicts,
        capsAll: idsAll.length,
        capsKept: Object.keys(kept).length,
        unverified: unverified,
        // 「注册了但读不到 setMaxIOSize」的 id ⇒ 它们**不是**"不存在"，是"没有上限数据"。
        // 守卫仍然拒绝用它们（不编数字），但报错时要能区分这两件事。
        noCaps: Object.keys(noCapsAt).sort().filter(function (id) { return !caps[id] }).map(function (id) { return id + ' @ ' + noCapsAt[id] }),
        langIds: Object.keys(lang.ids).length,
        langJars: lang.jarsHit,
        exportDirs: Object.keys(expDirs).length
    }
    return { caps: kept, capsRaw: caps, origin: keptOrigin, meta: meta }
}

module.exports = {
    openZip: openZip,
    parseJavapCaps: parseJavapCaps,
    findJavap: findJavap,
    readLangIds: readLangIds,
    readExportTypeDirs: readExportTypeDirs,
    build: build
}

// ───────────────────────────────────────────────────────── 自检 / 审计（正面 + 负面）
// 只有被当作主模块直接运行时才跑；被 require 时不做任何事。
// 用法：node mod_caps.js --mods "<实例>\mods" [--export "<实例>\local\kubejs\export\recipes"]
//       node mod_caps.js --dump "<实例>\mods" <输出文件>
if (require.main === module) {
    var argv = process.argv.slice(2)
    var modsArg = null, exportArg = null, dumpTo = null
    for (var ai = 0; ai < argv.length; ai++) {
        if (argv[ai] === '--mods') modsArg = argv[++ai]
        else if (argv[ai] === '--export') exportArg = argv[++ai]
        else if (argv[ai] === '--dump') { modsArg = argv[++ai]; dumpTo = argv[++ai] }
    }
    if (!modsArg) { console.log('用法：node mod_caps.js --mods "<实例>\\mods" [--export "<实例>\\local\\kubejs\\export\\recipes"]'); process.exit(2) }
    if (dumpTo) {
        var chunks = []
        var res = build({
            modsDir: modsArg, exportRecipesRoot: exportArg, log: console.log,
            onDump: function (jar, cls, txt) { chunks.push('===== ' + jar + '!' + cls + ' =====\n' + txt) }
        })
        fs.mkdirSync(path.dirname(dumpTo), { recursive: true })
        fs.writeFileSync(dumpTo, chunks.join('\n'), 'utf8')
        console.log('审计转储已写出 -> ' + dumpTo + '（' + chunks.length + ' 个类；守卫【不读】它，纯存档）')
        console.log('caps = ' + Object.keys(res.caps).length + ' 个 id')
        process.exit(res.caps['fishing_ground'] ? 0 : 1)
    }
    var t0 = Date.now()
    var R = build({ modsDir: modsArg, exportRecipesRoot: exportArg, log: console.log })
    console.log('=== mod_caps.js 自检（先证它自己对，再信它报的结论）===')
    console.log('javap = ' + R.meta.javapVersion + '（' + R.meta.javapHow + '）')
    console.log('扫 ' + R.meta.jarScanned + '/' + R.meta.jarCount + ' 个 jar、' + R.meta.classScanned + ' 个 class'
        + ' ⇒ 含 setMaxIOSize 的类 ' + R.meta.matchedClasses.length + ' 个（扫描 ' + R.meta.scanSeconds + ' 秒）')
    for (var mc = 0; mc < R.meta.matchedClasses.length; mc++) {
        var e = R.meta.matchedClasses[mc]
        console.log('   · ' + e.jar + '!' + e.cls + ' ⇒ ' + e.ids + ' 个类型')
    }
    console.log('旁证：lang 有 gtceu.<id> 的 id = ' + R.meta.langIds + ' ／ 游戏导出表类型目录 = ' + R.meta.exportDirs)
    console.log('解析出 id = ' + R.meta.capsAll + ' ⇒ 旁证通过保留 ' + R.meta.capsKept + '（丢弃 ' + R.meta.unverified.length + '）')
    var fail = 0
    // 正面：人工从字节码独立核对过的已知答案（fishing_ground 的那四个数直接读
    //   gtlcore jar `GTLRecipeTypes.class` <clinit> 偏移 2229-2234 = iconst_2 / bipush 24 / iconst_0 / iconst_0）
    var pos = [['fishing_ground', [2, 24, 0, 0]]]
    for (var pi = 0; pi < pos.length; pi++) {
        var gotP = R.caps[pos[pi][0]]
        var okP = gotP && gotP.join(',') === pos[pi][1].join(',')
        if (!okP) fail++
        console.log('  ' + (okP ? '✅' : '❌') + ' 正面对照 ' + pos[pi][0] + ' ⇒ 期望 ' + JSON.stringify(pos[pi][1]) + ' 实测 ' + JSON.stringify(gotP || null))
    }
    // 负面：真不存在的类型名**必须**读不到（否则说明这个解析器在凭空造 id）
    var neg = ['zzz_definitely_not_a_type', 'not_a_real_gt_type_at_all']
    for (var ni = 0; ni < neg.length; ni++) {
        var okN = !R.caps[neg[ni]]
        if (!okN) fail++
        console.log('  ' + (okN ? '✅' : '❌') + ' 负面对照 ' + neg[ni] + ' ⇒ 期望查不到 实测 ' + JSON.stringify(R.caps[neg[ni]] || null))
    }
    if (R.meta.unverified.length) console.log('  ℹ️ 无旁证已丢弃（不静默）：' + R.meta.unverified.join(' ／ '))
    if (R.meta.noCaps.length) console.log('  ℹ️ 「注册了但那段没有 setMaxIOSize」的 id（读不到上限，**不是不存在**）：' + R.meta.noCaps.join(' ／ '))
    if (R.meta.conflicts.length) { console.log('  🔴 跨类冲突 ' + R.meta.conflicts.length + ' 个：' + JSON.stringify(R.meta.conflicts)); fail++ }
    if (R.meta.javapProblems.length) console.log('  ⚠️ javap 解析问题 ' + R.meta.javapProblems.length + ' 条：' + R.meta.javapProblems.slice(0, 5).join(' ／ '))
    console.log('正/负对照失败数 = ' + fail + '　总耗时 ' + R.meta.seconds + ' 秒')
    process.exit(fail ? 1 : 0)
}
