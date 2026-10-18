// ES3 / Rhino compliance scanner + syntax-check harness.
// 🔴 为什么要它：`node --check` 只查【语法】，而本工程的坑是【Node 认、Rhino 不认】
//    —— 箭头函数 / let / const / 模板串在 Node 24 里全是合法语法 ⇒ node --check 一律 exit 0。
//    ⇒ 所以第三个状态（"故意插语法错"）必须用【真语法错】做对照，而 Rhino 限制要单独扫。
'use strict'
var fs = require('fs')
var path = require('path')

// ═══════════════════════════════════════════════════════════════════════════════
// 🔴 路径来源纪律（上传前清理）：本仓库里【不写任何机器绝对路径】。
//    · 仓库【内】的路径 ⇒ 按【脚本自身位置】(__dirname) 推（不用 process.cwd()）；
//    · 仓库【外】的路径（游戏实例）⇒ 从环境变量读；缺了就【响亮抛错并退出】。
// ═══════════════════════════════════════════════════════════════════════════════
// 仓库根 = 从本脚本逐级上溯，第一个含 .git 的目录（2026-10-18：recipe-convert\ 等搬进 temp\，别再靠相对深度数层数）
function findRepoRoot(start) {
    var d = start
    for (;;) {
        if (fs.existsSync(path.join(d, '.git'))) return d
        var up = path.dirname(d)
        if (up === d) throw new Error('findRepoRoot: 未找到含 .git 的仓库根，起始路径 = ' + start)
        d = up
    }
}
var REPO = findRepoRoot(__dirname)                   // 上溯到仓库根
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

function stripCommentsAndStrings(src) {
    var out = ''
    var i = 0, n = src.length
    var state = 'code'
    while (i < n) {
        var c = src[i], d = src[i + 1]
        if (state === 'code') {
            if (c === '/' && d === '/') { state = 'line'; i += 2; out += '  '; continue }
            if (c === '/' && d === '*') { state = 'block'; i += 2; out += '  '; continue }
            if (c === '\'') { state = 'sq'; i++; out += ' '; continue }
            if (c === '"') { state = 'dq'; i++; out += ' '; continue }
            if (c === '`') { state = 'bt'; i++; out += ' '; continue }
            out += c; i++; continue
        }
        if (state === 'line') { if (c === '\n') { state = 'code'; out += '\n' } else { out += ' ' } i++; continue }
        if (state === 'block') { if (c === '*' && d === '/') { state = 'code'; i += 2; out += '  ' } else { out += (c === '\n' ? '\n' : ' '); i++ } continue }
        if (state === 'sq') { if (c === '\\') { i += 2; out += '  '; continue } if (c === '\'') { state = 'code' } out += ' '; i++; continue }
        if (state === 'dq') { if (c === '\\') { i += 2; out += '  '; continue } if (c === '"') { state = 'code' } out += ' '; i++; continue }
        if (state === 'bt') { if (c === '\\') { i += 2; out += '  '; continue } if (c === '`') { state = 'code' } out += (c === '\n' ? '\n' : ' '); i++; continue }
    }
    return out
}

var RULES = [
    { id: 'arrow-fn', re: /=>/g, why: '箭头函数 —— Rhino 不支持' },
    { id: 'let', re: /\blet\s+[A-Za-z_$]/g, why: '`let` —— Rhino 全局作用域不支持' },
    { id: 'const', re: /\bconst\s+[A-Za-z_$]/g, why: '`const` —— Rhino 全局作用域不支持' },
    { id: 'template-raw', re: /`/g, why: '模板字符串 —— 不支持' },
    { id: 'optional-chain', re: /\?\./g, why: '可选链 ?. —— 不支持' },
    { id: 'spread', re: /\.\.\./g, why: '展开/剩余 —— 不支持' },
    { id: 'class', re: /\bclass\s+[A-Za-z_$]/g, why: 'class 语法 —— 不支持' },
    { id: 'async', re: /\basync\s+/g, why: 'async —— 不支持' },
    { id: 'await', re: /\bawait\s+/g, why: 'await —— 不支持' },
    { id: 'for-of', re: /\bof\s+[A-Za-z_$]/g, why: 'for...of —— 不支持（且此处极易误报，需人工看）' },
    { id: 'destructure-obj', re: /\bvar\s*\{/g, why: '对象解构声明 —— 不支持' },
    { id: 'destructure-arr', re: /\bvar\s*\[/g, why: '数组解构声明 —— 不支持' },
    { id: 'default-param', re: /function\s*\([^)]*=[^)]*\)/g, why: '默认参数 —— 可能不支持' }
]

function scan(name, file) {
    var src = fs.readFileSync(file, 'utf8')
    var code = stripCommentsAndStrings(src)
    var lines = code.split(/\r?\n/)
    var hits = []
    for (var r = 0; r < RULES.length; r++) {
        var rule = RULES[r]
        for (var l = 0; l < lines.length; l++) {
            rule.re.lastIndex = 0
            if (rule.re.test(lines[l])) hits.push({ rule: rule.id, why: rule.why, line: l + 1, text: lines[l].trim().substring(0, 110) })
        }
    }
    console.log('=== ' + name + ' ===')
    console.log('   file: ' + file)
    console.log('   lines=' + lines.length + '   ES3/Rhino 违规命中=' + hits.length)
    for (var h = 0; h < hits.length; h++) console.log('     [' + hits[h].rule + '] L' + hits[h].line + ' :: ' + hits[h].text + '   <- ' + hits[h].why)
    return hits.length
}

var B = path.join(REPO, 'temp', 'recipe-convert') + path.sep
var V = path.join(INSTANCE, 'kubejs', 'server_scripts') + path.sep
var n1 = scan('NEW DRAFT', B + 'shanhai_pf_recipes.NEW.js')
var n2 = scan('OLD LIVE FILE', V + 'shanhai_pf_recipes.js')

// positive control: a file we KNOW has violations
var ctrl = 'var a = () => 1\nconst b = `x`\nlet c = { ...d }\n'
fs.writeFileSync(B + '_rhino_control.js', ctrl, 'utf8')
var n3 = scan('POSITIVE CONTROL (故意含 ES6，应报 >0)', B + '_rhino_control.js')
console.log('')
console.log('--- 自证 ---')
console.log('  正面对照（已知含违规）命中 = ' + n3 + '  ⇒ ' + (n3 > 0 ? '扫描器【会】报警 ✓' : '扫描器坏了 ❌'))
console.log('  NEW 草稿 = ' + n1 + ' / OLD 线上文件 = ' + n2)
