// priority: -1000
// =============================================================================
// shanhai_recipe_overrides.js   --   Shanhai recipe-override overlay (KubeJS)
// -----------------------------------------------------------------------------
// PURPOSE
//   The in-game recipe editor writes its edits to
//       config/shanhai/recipe_overrides.json
//   This script is the ONLY thing that turns that file back into live recipes on
//   the next boot.  Without it the file is a dead note.
//
// WHY  priority: -1000
//   KubeJS sorts server scripts in DESCENDING priority order:
//       ScriptFile.compareTo(ScriptFile o)
//         = Integer.compare(o.info.getPriority(), this.info.getPriority())
//   i.e. the LARGER number runs FIRST.  The lowest priority that already exists
//   in this pack is 0 (ad.js / avaritia.js / fix.js / laserio.js /
//   [server_scripts]产线撕裂.js / [server_scripts]产线爆破.js / 无线懒狗版2.js /
//   超级AE包.js), so a NEGATIVE value is the only way to be the last writer.
//   Being the last writer is mandatory: KubeJS merges recipe sets as
//   putAll(original) then putAll(added) - "added wins" is only true for the last
//   script that touches a given recipe id.
//
// PATH BASE (verified at bytecode level - do NOT "fix" this to kubejs/-relative)
//   JsonIO.read('config/shanhai/recipe_overrides.json')
//     -> BuiltinKubeJSPlugin.registerTypeWrappers binds java.nio.file.Path
//        to UtilsJS.getPath(Object)      (bootstrap method #46)
//     -> UtilsJS.getPath(Object):  KubeJS.getGameDirectory().resolve(str)
//     -> KubeJS.verifyFilePath(Path): refuses anything not under gameDirectory
//   => the string is GAME-DIRECTORY relative.  Not kubejs/-relative, not
//      JVM-working-directory relative.  Absolute paths outside gameDir are
//      rejected (UtilsJS.getPath swallows the exception and returns null).
//
//   Plain Java file IO is NOT an option: BuiltinKubeJSPlugin.registerClasses
//   puts  ClassFilter.deny("java.io")  and  ClassFilter.deny("java.nio")
//   (only java.io.Closeable / Serializable and java.nio.ByteOrder are allowed),
//   so Java.loadClass('java.nio.file.Paths') is refused.  JsonIO is the only
//   exposed reader/writer.  JsonIO.write(Path,JsonObject) exists (it does
//   Files.newBufferedWriter and does NOT mkdirs), but this overlay only READS.
//
// LOUDNESS CONTRACT
//   Every entry is classified into exactly one of
//       applied | stale | missing | skipped
//   and a single machine-readable summary line is printed:
//       [SHANHAI-EDIT] overlay source=<path> schema=1 entries=N applied=.. \
//                      stale=.. missing=.. skipped=..
//   invariant: entries == applied + stale + missing + skipped
//   Nothing is ever applied silently: a fingerprint mismatch does NOT apply.
//
// NOTE ON base_fp ALGORITHM
//   `base_fp` is a fingerprint of what the target recipe looked like when the
//   editor saved the edit - a change detector, NOT a cryptographic digest.
//   It is NOT sha256: KubeJS's ClassFilter blocks java.security (ClassFilter
//   allows java.util / java.math / net.minecraft / dev.latvian.mods.kubejs and
//   denies java.lang-, java.io-, java.nio- except an explicit allow-list), so no
//   MessageDigest is reachable; and JsonIO.getJsonHashString is MD5-base36 over a
//   JSON walk whose object key order is NOT sorted (it iterates
//   JsonObject.entrySet() directly), so it is not stable enough to be a contract.
//   This overlay therefore uses a self-contained hash: FNV-1a 32 bit, run twice
//   with two different offset bases and printed as 16 hex digits, tagged
//   `fnv1a32x2:`.  All arithmetic is kept <= 2^53 so no double rounding occurs.
//   WARNING (a real bug that this comment exists to prevent): a *64-bit* FNV
//   implemented with ordinary JS doubles silently CORRUPTS the low bits
//   (observed: three different recipes hashed to the same `...b3b02000`), which
//   would make `stale` fire as `applied`.  Do not "simplify" this back to a
//   64-bit accumulator.
// =============================================================================

var SHANHAI_OVR_PATH = 'config/shanhai/recipe_overrides.json';
var SHANHAI_OVR_SUPPORTED_SCHEMA = 1;
var SHANHAI_OVR_TAG = '[SHANHAI-EDIT]';

function shanhaiOvrInfo(msg) {
    console.info(SHANHAI_OVR_TAG + ' ' + msg);
}

function shanhaiOvrError(msg) {
    console.error(SHANHAI_OVR_TAG + ' ' + msg);
}

// --- WARN 通道 + 每局每 id 只报一次 -------------------------------------------
// 🔴 2026-10-05（用户实测反馈）：「因为 kjs 又报错了」—— 用户把那条 FP_STUB 的 ERROR 横幅
//    读成"重启不丢没做成"，而实际上同一拍就打出了 APPLIED（值没丢，见下面的 FT_STUB 语义）。
//    FP_STUB 是【如实交代】而不是【失败】：它的正确级别是 WARN。
//    KubeJS 把 console.error 记成 ERROR（日志里带 '!' 前缀），用户在游戏里看到的横幅也是它。
// ⇒ 本文件的规则：
//      ERROR 只留给"用户必须知道且必须动手"的事（OVERLAY DISABLED / MISSING / STALE /
//      NEEDS_RESAVE / ACCOUNTING MISMATCH）；
//      单纯的"降级但已套用"一律走 WARN，且【同一个 id 每局只打一行】，末尾用汇总行报总数。
function shanhaiOvrWarn(msg) {
    console.warn(SHANHAI_OVR_TAG + ' ' + msg);
}

/** 本局已经报过 WARN 的 id（key = uid + '|' + 判据）。 */
var SHANHAI_OVR_WARNED = {};

/**
 * @return true = 这条是第一次报（调用方据此决定打不打那一行）
 */
function shanhaiOvrWarnOnce(key) {
    if (SHANHAI_OVR_WARNED[key] === true) {
        return false;
    }
    SHANHAI_OVR_WARNED[key] = true;
    return true;
}

/** 本局 FP_STUB 的累计条数（汇总行要用）。 */
var SHANHAI_OVR_STUB_COUNT = 0;
/** 本局因"同 id 已报过"而被压掉的 FP_STUB 行数。 */
var SHANHAI_OVR_STUB_SUPPRESSED = 0;

// --- FNV-1a 32 bit, 16-bit split so every product stays exactly representable --
function shanhaiOvrFnv32(str, base) {
    var h = base >>> 0;
    var i;
    for (i = 0; i < str.length; i++) {
        h = (h ^ str.charCodeAt(i)) >>> 0;
        var lo = h & 0xffff;
        var hi = (h >>> 16) & 0xffff;
        h = ((lo * 16777619) + (((hi * 16777619) & 0xffff) << 16)) >>> 0;
    }
    return h >>> 0;
}

function shanhaiOvrPad8(n) {
    var s = (n >>> 0).toString(16);
    while (s.length < 8) { s = '0' + s; }
    return s;
}

function shanhaiOvrHash(str) {
    return shanhaiOvrPad8(shanhaiOvrFnv32(str, 0x811c9dc5)) +
           shanhaiOvrPad8(shanhaiOvrFnv32(str, 0x01000193));
}

// --- content fingerprint (v3.sorted) ------------------------------------------
// 🔴 2026-10-05（用户实测 B4）：「重启游戏之后 kjs 报错消失，但是时长重置了」
//    时序：同一 JVM 内编辑【运行期生效】（所以"重进"时时长还在）；
//    重启后只剩这份覆盖层重放 ⇒ 指纹对不上 ⇒ 判 STALE、不套用 ⇒ 时长回退。
//    根因：v2 的指纹 = JsonIO.toString(recipe.json) 的【原样】串，而那份 JSON 的对象键顺序
//    【跨开机不稳】。实测样本 gtceu:assembler/cover_steel_wire_gt_octal_rubber（同时吃 item
//    与 fluid 两种输入能力）：`"inputs":{"fluid":…,"item":…}` 与 `{"item":…,"fluid":…}` 都出现过。
//    ⇒ 一个"根本没被改过"的配方，两次开机算出两个不同的指纹。
//
//    修法：取指纹前先把 JSON【规范化】（递归按字典序排对象键、数组保持原序）再序列化，再走原来的截断。
//    🔴 两侧必须逐字节一致 ⇒ 这里【不自己实现】，直接调 Java 侧同一份代码：
//       ShanhaiFingerprint 由本模组的 KubeJS 插件绑定（ShanhaiKubeJSPlugin#registerBindings）。
//       老版那种"Java 侧逐字复刻脚本"的做法，一致性靠人盯；现在靠【同一段代码】，构造上必然一致。
//
//    ⚠️ 拿不到那个绑定时【不猜】：直接返回 null，调用方判 SKIPPED（宁可整条不套用，
//       也绝不写/比一个"看着像"的串 —— 那会把用户的编辑静默丢掉）。
function shanhaiOvrFingerprint(recipe) {
    var j = null;
    try { j = recipe.json; } catch (e0) { j = null; }
    if (j === null || j === undefined) {
        return null;
    }
    try {
        var fp = ShanhaiFingerprint.fingerprint(j);
        if (fp !== null && fp !== undefined && String(fp).length > 0) {
            return String(fp);
        }
        shanhaiOvrError('fingerprint() 返回空 ⇒ 这条不给指纹（这次不套用）');
    } catch (e1) {
        shanhaiOvrError('FINGERPRINT BINDING FAILED (fail-closed): ' + e1);
    }
    return null;
}

// --- 旧算法（只用于迁移判定；不再用于产出）--------------------------------------
function shanhaiOvrFingerprintV2(recipe) {
    var j = null;
    try { j = recipe.json; } catch (e0) { j = null; }
    if (j !== null && j !== undefined) {
        try {
            var s = JsonIO.toString(j);
            if (s !== null && s !== undefined && String(s).length > 0) { return 'v2.json:' + String(s); }
        } catch (e1) { }
    }
    return null;
}

// --- 【已废弃】原始内容串（留着只为读旧的日志时对得上）--------------------------
// `base_fp` is an EXACT string compare, NOT a hash.  Rationale, all measured:
//   * String(recipe) (RecipeJS.toString()) is only "<id>[<type>]" - it carries NO
//     recipe content, so hashing it can never detect a changed duration.
//   * An in-Rhino FNV-1a was NOT reproducible outside Rhino: the SAME input string
//     hashed to 9e436d0330aa5c71 under Node but f1fc187523305963 in game, and the
//     collision groups were identical across two unrelated hash implementations.
//     A digest that cannot be reproduced cannot be trusted as a change detector.
//   * RecipeFunction extends dev.latvian.mods.rhino.NativeJavaObject and exposes the
//     public field RecipeJS.json, so the recipe's real Gson JSON IS reachable.
// ⚠️ 2026-10-05：v2 里那句 "truncated to a fixed, deterministic window" 是错的 ——
//    窗口是确定的，但【窗口里的内容不是】：对象键顺序跨开机不稳（见上面 v3 段）。
//    下面这两个函数已【不再被调用】，只作为 v2 口径的书面记录留着。
function shanhaiOvrContentV2Unused(recipe) {
    var j = null;
    try { j = recipe.json; } catch (e0) { j = null; }
    if (j !== null && j !== undefined) {
        try {
            var s = JsonIO.toString(j);
            if (s !== null && s !== undefined && String(s).length > 0) {
                return { src: 'json', s: String(s) };
            }
        } catch (e1) { }
    }
    try { return { src: 'fallback-tostring', s: String(recipe) }; } catch (e2) { }
    return { src: 'none', s: '' };
}

// ⚠️ 这个 v2 版本的函数【已不再被任何地方调用】（v3 版在上面）。留着是为了让
//    "旧指纹是怎么算的"在源码里可查 —— 迁移排查时要对着它读老日志。
function shanhaiOvrFingerprintV2Unused(recipe) {
    var c = shanhaiOvrContentV2Unused(recipe);
    var s = c.s;
    if (s.length > 400) { s = s.substring(0, 220) + '~~' + s.substring(s.length - 180); }
    return 'v2.' + c.src + ':' + s;
}

// --- 指纹版本判定（优先走 Java 绑定；绑定不可用时退到字符串前缀判断）-------------
// ⚠️ 这个常量必须与 ShanhaiRecipeFingerprint#FP_PREFIX_V3 一致 —— 它只是绑定失败时的兜底，
//    正常路径上问的是 Java 侧（shanhaiOvrFpPrefix）。
var SHANHAI_OVR_FP_PREFIX = 'v3.sorted:';

function shanhaiOvrFpPrefix() {
    try {
        var p = ShanhaiFingerprint.currentVersionPrefix();
        if (p !== null && p !== undefined && String(p).length > 0) { return String(p); }
    } catch (e) { }
    return SHANHAI_OVR_FP_PREFIX;
}

function shanhaiOvrIsCurrentFpVersion(fp) {
    if (fp === null || fp === undefined || String(fp).length === 0) { return false; }
    try {
        var r = ShanhaiFingerprint.isCurrentVersion(String(fp));
        if (r === true || String(r) === 'true') { return true; }
        if (r === false || String(r) === 'false') { return false; }
    } catch (e) { }
    return String(fp).indexOf(SHANHAI_OVR_FP_PREFIX) === 0;
}

function shanhaiOvrFpVersion(fp) {
    try {
        var v = ShanhaiFingerprint.versionOf(fp === null || fp === undefined ? '' : String(fp));
        if (v !== null && v !== undefined) { return String(v); }
    } catch (e) { }
    var s = (fp === null || fp === undefined) ? '' : String(fp);
    if (s.indexOf(SHANHAI_OVR_FP_PREFIX) === 0) { return 'v3.sorted'; }
    if (s.indexOf('v2.') === 0) { return 'v2'; }
    return s.length === 0 ? '(empty)' : '?';
}

// --- 【2026-10-05 新增】KubeJS 自己加的那些配方（overlay 的唯一可达通道）---------
// =============================================================================
// 🔴 这一节修的是「重启不丢」那条线的主症：MISSING op=… id=shanhai:… -> no such recipe id
//
// 现场（用户实例 logs\latest.log 原文，逐字）：
//   [0510…11:43:19] [Server thread/ERROR] [KubeJS Server/]: shanhai_recipe_overrides.js#77:
//     [SHANHAI-EDIT] e-editor-261005-033906 MISSING op=set id=shanhai:zero_point_conversion/test/zero_point_conversion
//     type=gtceu:zero_point_conversion -> no such recipe id in the recipe manager
//
// 取证（本工程无头专服四次运行的读数，原始行留在 temp\smoke-rig\server\logs\kubejs\server.log）：
//   [IDPROBE] A forEachRecipe_total=51000
//   [IDPROBE] A countRecipes_all=51000
//   [IDPROBE] A findRecipeIds_total=51000 shanhai_hits=0 control_hits=2
//   [IDPROBE] B addedRecipes class=java.util.concurrent.ConcurrentLinkedQueue size=12950 shanhai_hits=4154
//   [IDPROBE] B originalRecipes class=java.util.HashMap size=52110 shanhai_hits=0
//   [IDPROBE] B sample=dishanhai:assembler/raw_to_exquisite_raw_emerald[gtceu:assembler]
//
// ⇒ 结论有三条，每条都由上面那几行直接读出：
//   ① forEachRecipe / countRecipes / findRecipeIds 三者【是同一个集合】（都 51000），
//      而那个集合里【一条 shanhai: 都没有】（正对照 control_hits=2 证明枚举器本身是好的）；
//   ② 这个集合 = 开事件那一刻【已经从数据包读进来】的配方（originalRecipes 52110 含它）；
//   ③ 同一个事件里【别的脚本刚加的】配方全在 `event.addedRecipes` 这个队列里
//      （12950 条、其中 4154 条 shanhai:）。它给出来的串就是 `<真实id>[<类型>]`，
//      而那个真实 id 正是 GTCEu 的 GTRecipeJS.id() 拼出来的那个
//      （declared『shanhai:test/x』⇒ 真实『shanhai:<类型路径>/test/x』）。
//
// 🔴 所以：**这从来不是一个"id 拼错"的问题**。编辑器写进覆盖文件的
//    `recipe.getId()` 就是真 id（实例日志 workspace_recipe_loaded 那一行印的就是它，
//    与 KubeJS 导出目录 local/kubejs/export/recipes/shanhai/zero_point_conversion/test/
//    zero_point_conversion.json 逐字一致）。是【查的时机/通道】不对 —— 拿一个
//    看不见任何 shanhai: 配方的集合去查 shanhai: 的 id，无论 id 写得多对都必然 MISSING。
//
// 🔴 修法 = 查表时【两条通道都查】：先查 addedRecipes（本轮新加），再退回 countRecipes
//    （数据包配方）。两个通道给出来的都是同一个 id 口径（真实 recipe id），所以不需要
//    任何"拼 id"的代码 —— 这一节里【没有一处字符串拼接】。
// =============================================================================
function shanhaiOvrAddedIndex(event) {
    var idx = { map: {}, size: 0, parsed: 0 };
    var q = null;
    try {
        q = event.addedRecipes;
    } catch (e0) {
        q = null;
    }
    if (q === null || q === undefined) {
        // 🔴 2026-10-05：降 ERROR → WARN（降级但本轮照跑数据包通道）；那些因此变成 MISSING 的
        //    KubeJS 侧条目会各自打一条 MISSING（ERROR），不至于静默。
        shanhaiOvrWarn('addedRecipes 不可读（KubeJS 版本变了？）⇒ 本轮退回旧口径（数据包通道）');
        return idx;
    }
    try {
        var it = q.iterator();
        while (it.hasNext()) {
            idx.size++;
            var rj = it.next();
            var s;
            try {
                s = String(rj);                 // "<id>[<type>]"
            } catch (e1) {
                continue;
            }
            var b = s.lastIndexOf('[');
            if (b <= 0) {
                continue;
            }
            var id = s.substring(0, b);
            idx.map[id] = rj;
            idx.parsed++;
        }
    } catch (e2) {
        // 🔴 2026-10-05：降 ERROR → WARN（同上：真正的损失由逐条的 MISSING 交代）
        shanhaiOvrWarn('addedRecipes 迭代失败: ' + e2);
    }
    return idx;
}

// --- 【2026-10-05 新增】按 id 取"要改的那些 RecipeJS"：两条通道合成一个出口 --------
//   addedRecipes 里命中的那条优先（KubeJS 自己加的配方只可能在这里）；
//   否则退回 forEachRecipe（数据包配方）。
//   ⚠️ 返回的是**原始 RecipeJS 对象本身** —— `recipe.set(name, v)` 改的就是它，
//      与旧代码对数据包配方做的是同一件事，所以两条通道的行为一致。
function shanhaiOvrTargets(event, added, id) {
    var out = [];
    var rj = added.map[id];
    if (rj !== undefined) {
        out.push(rj);
        return out;
    }
    try {
        event.forEachRecipe({ id: id }, function (r) { out.push(r); });
    } catch (e) {
        // 🔴 2026-10-05：降 ERROR → WARN。理由：这一步失败之后，这条 entry 会走到
        //    `actualFp === null` 那一支并打出自己的 MISSING（ERROR）—— 「他的编辑没套用」这件事
        //    由那一条 ERROR 负责交代。这里再来一条 ERROR 就是同一件事报两遍，
        //    而"每多一个自己发的 ERROR 就多一个他被吓到的理由"。
        shanhaiOvrWarn('forEachRecipe 失败 id=' + id + ': ' + e);
    }
    return out;
}

// --- 【2026-10-05 新增】B 组：额外条件（recipeConditions）------------------------
// =============================================================================
// 🔴 用户点单（逐字）：
//   「还有一些配方需要额外条件（例如超净间，或者我们的物质模块等级），你可以添加一个加号，
//     然后可以通过这个加号来新增额外条件，同时，对于已有的条件，你应该也需要在面版中列出，
//     并且右键可以编辑」
//
// 编辑器把条件写进 config/shanhai/recipe_overrides.json 的 entry.fields.conditions，
// 形状 = GT 自己的【平铺 dispatch】形状（与 recipe.json 里 recipeConditions 的每一项同形）：
//     [{"type":"cleanroom","cleanroom":"law_cleanroom"},
//      {"type":"module_level","module_id":"shanhai:…","level":3}]
//
// 🔴 为什么这一步交给 Java 绑定 ShanhaiConditions（而不是这里 recipe.set(...)）：
//   ① KubeJS 那个 RecipeComponent 的 write() 产出 {"type":k,"data":{…}}，而 GT 的加载器
//      走 RecipeCondition.CODEC（KeyDispatchCodec）要的是平铺形状 ⇒ 形状不对称 ⇒ 配方 json 解析失败；
//   ② KubeJS 那个 component 的 read() 调的是 RecipeCondition.deserialize()，而
//      ModuleLevelCondition 没 override 它 ⇒ module_id/level 会被读成默认值（门槛变成"永远不过"）；
//   ③ 还有一道：RecipeJS.serialize() 会把标了 write 的键按 component 的形状覆盖回去，
//      所以光把 json 写对不够，还得把那个键的 write 标志压掉。
//   三条的字节码取证见 com/shanhai/common/recipe/editor/ShanhaiRecipeConditionReplay.java 的类注释。
//
// 🔴 fail-closed：校验不过 ⇒ 【整条 entry 不套用】（连其它字段也不写），并打一条 ERROR。
//    绝不"能吃几条吃几条" —— 半套用的条件会让机器按一个用户没写过的门槛去拦配方。
// =============================================================================
function shanhaiOvrConditionsOf(fields) {
    if (fields === null || fields === undefined) {
        return null;
    }
    // ⚠️ 用【方括号】取（与下面 set 循环里 fields[names[j]] 同一口径）：
    //    fields 是 KubeJS 的 MapJS（Java 支撑的 Scriptable），点号取值不保证走同一条路。
    var v = fields['conditions'];
    return (v === undefined || v === null) ? null : v;
}

/** @return {ok:boolean, arr:?, err:string} —— 用 Java 侧同一个 codec 做校验（fail-closed）。 */
function shanhaiOvrValidateConditions(raw) {
    var arr = null;
    try {
        arr = JsonIO.of(raw);
    } catch (e0) {
        return { ok: false, arr: null, err: 'JsonIO.of(fields.conditions) 失败: ' + e0 };
    }
    try {
        var err = ShanhaiConditions.validate(arr);
        var s = (err === null || err === undefined) ? '' : String(err);
        if (s.length > 0) {
            return { ok: false, arr: null, err: s };
        }
        return { ok: true, arr: arr, err: '' };
    } catch (e1) {
        // 🔴 绑定没挂上 / Java 侧抛了：这是【降级但必须响】的情形（同 FINGERPRINT BINDING FAILED）
        return { ok: false, arr: null, err: 'CONDITIONS BINDING FAILED (fail-closed): ' + e1 };
    }
}

/**
 * 开机自检：正对照 ＋ 负对照各一条，证明"校验器是活的、而且它真的会拒"。
 *
 * 判据（可 grep）：
 *   overlay_condcheck POSITIVE   … ok=true  expected=true  PASS=true
 *   overlay_condcheck NEGATIVE_… … ok=false expected=false PASS=true
 * 任一条 PASS=false ⇒ 本轮的条件重放等于没做（这时【必须】看日志，不许当成"没有条件要装"）。
 */
function shanhaiOvrConditionsSelfcheck() {
    var pos = shanhaiOvrValidateConditions(
        JsonIO.parseRaw('[{"type":"cleanroom","cleanroom":"cleanroom"}]'));
    var neg = shanhaiOvrValidateConditions(
        JsonIO.parseRaw('[{"type":"definitely_not_a_condition_zzz"}]'));
    var neg2 = shanhaiOvrValidateConditions(JsonIO.parseRaw('"cleanroom"'));
    shanhaiOvrInfo('overlay_condcheck POSITIVE input=[cleanroom] ok=' + pos.ok
        + ' expected=true PASS=' + (pos.ok === true) + ' err="' + pos.err + '"'
        + ' (这一条同时证明 ShanhaiConditions 这个 Java 绑定是通的)');
    shanhaiOvrInfo('overlay_condcheck NEGATIVE_CONTROL input=[unknown_type] ok=' + neg.ok
        + ' expected=false PASS=' + (neg.ok === false) + ' err="' + neg.err + '"'
        + ' (未知类型必须被拒 ⇒ 否则"fail-closed"是一句空话)');
    shanhaiOvrInfo('overlay_condcheck NEGATIVE_CONTROL_2 input=<not-an-array> ok=' + neg2.ok
        + ' expected=false PASS=' + (neg2.ok === false) + ' err="' + neg2.err + '"');
    if (pos.ok !== true || neg.ok !== false || neg2.ok !== false) {
        shanhaiOvrError('overlay_condcheck FAILED —— 条件校验器行为不对，本局的条件重放不可信'
            + '（条件类 entry 会按 fail-closed 全部拒收，其它 entry 不受影响）');
    }
}

// --- 【2026-10-05 新增】自检：靶子是 shanhai: 命名空间的真配方 + 一个故意拼错的负对照
//   判据（两行都可机器判）：
//     POSITIVE 那一行必须 found=1（否则就是"查不到真配方"，本轮的功能等于没修）
//     NEGATIVE 那一行必须 found=0（否则这条断言没有判别力 = 假绿）
//   负对照的构造方式 = 复现旧症：在真实 id 的类型路径前再插一段类型名
//     （真实 `shanhai:<类型>/<path>` ⇒ 故意写成 `shanhai:<类型>/<类型>/<path>`）
//   ⚠️ 用户实例里当年那两条错条目长的就是这个形状，见文件头的 MISSING 原文。
// --- 【2026-10-05 新增】"KubeJS 加的配方"在这一相位拿不到完整 json ------------------
//   实测（本工程无头专服 20261005-121547 那一轮的原始行）：
//     e-cut4-idlookup-0001 lookup … source=addedRecipes targets=1                    ← 找得到了（本轮修的）
//     e-cut4-idlookup-0001   actual   base_fp=v3.sorted:json:{"type":"unknown"}       ← 🔴 指纹底本是个空壳
//   `addedRecipes` 里 RecipeJS 的 `.json` 在这一相位只有 {"type":"unknown"}（18 字符）⇒
//   "按指纹核验"这条路对【KubeJS 自己加的配方】天然算不出真值，会从 MISSING 变成 STALE
//   （= 仍然不套用，用户看到的还是"重启就丢"）。
//
// 🔴 对这一条通道改用【id 逐字相等 + 该 id 确实在 addedRecipes 里】作为完整性判据；并且
//    【每一条只打一行 FP_STUB】如实交代，绝不静默降级。
//    依据：编辑器写进覆盖文件的就是这条配方自己的真 id（Java 侧 [SHANHAI-EDIT] idcheck 的
//    POSITIVE 断言量过：editor_lookup=true is_recipe_manager_key=true），而 op=set 的语义就是
//    "把这条 id 配方的这几个字段改成这样" —— 这正是用户点保存的意思。
//    代价（如实写下来）：这一条通道上**失去"底本被人动过就不套用"那层保护**。
function shanhaiOvrJsonIsStub(recipe) {
    try {
        var s = JsonIO.toString(recipe.json);
        if (s === null || s === undefined) { return true; }
        var str = String(s);
        return str.length < 40 || str.indexOf('"unknown"') >= 0;
    } catch (e) {
        return true;
    }
}

function shanhaiOvrSelfcheck(event, added) {
    var posId = null;
    var k;
    for (k in added.map) {
        if (k.indexOf('shanhai:') === 0 && k.indexOf('/test/') > 0) {
            posId = k;
            break;
        }
    }
    if (posId === null) {
        for (k in added.map) {
            if (k.indexOf('shanhai:') === 0) {
                posId = k;
                break;
            }
        }
    }
    if (posId === null) {
        // 🔴 2026-10-05：降 ERROR → WARN。这是"自检这一项没法判"（包里一条 shanhai: 都没有），
        //    不是"哪里坏了" —— 它不是用户需要动手的信号。
        shanhaiOvrWarn('overlay_idcheck SKIPPED：addedRecipes 里一条 shanhai: 都没有 ⇒ 本项无法判定（不算通过）');
        return;
    }
    var negId = null;
    var colon = posId.indexOf(':');
    var slash = posId.indexOf('/', colon);
    if (slash > 0) {
        var ns = posId.substring(0, colon);
        var typePath = posId.substring(colon + 1, slash);
        negId = ns + ':' + typePath + '/' + posId.substring(colon + 1);
    } else {
        negId = posId + '/typo_negative_control';
    }
    var posFound = (added.map[posId] !== undefined) ? 1 : 0;
    var negFound = (added.map[negId] !== undefined) ? 1 : 0;
    var cut = '';
    try {
        var rj = added.map[posId];
        cut = String(rj.json).length;
    } catch (e) {
        cut = -1;
    }
    shanhaiOvrInfo('overlay_idcheck POSITIVE id=' + posId + ' found=' + posFound
        + ' expected=1 PASS=' + (posFound === 1) + ' json_len=' + cut
        + ' (这一条就是编辑器写进覆盖文件的同一个 id 口径)');
    shanhaiOvrInfo('overlay_idcheck NEGATIVE_CONTROL id=' + negId + ' found=' + negFound
        + ' expected=0 PASS=' + (negFound === 0)
        + ' (故意多插一段类型名 ⇒ 复现用户实例里那两条 MISSING 的形状；必须查不到，否则这条断言没有判别力)');
}

// --- read + validate the override file ---------------------------------------
// returns { ok:true, entries:[...] } or { ok:false, reason:'...' }
function shanhaiOvrLoad() {
    var raw;
    try {
        raw = JsonIO.read(SHANHAI_OVR_PATH);
    } catch (e) {
        return { ok: false, reason: 'JSON parse/read failed: ' + e };
    }
    if (raw === null || raw === undefined) {
        return { ok: false, reason: 'file not present or not readable at gameDir/' + SHANHAI_OVR_PATH };
    }

    var schema = raw.schema_version;
    if (schema === undefined || schema === null) {
        return { ok: false, reason: 'missing required key schema_version' };
    }
    if (Number(schema) !== SHANHAI_OVR_SUPPORTED_SCHEMA) {
        return {
            ok: false,
            reason: 'unsupported schema_version=' + schema +
                    ' (this build supports ' + SHANHAI_OVR_SUPPORTED_SCHEMA + ')'
        };
    }

    var entries = raw.entries;
    if (entries === null || entries === undefined) {
        return { ok: false, reason: 'missing required key entries' };
    }

    // Normalise to a plain array; log which shape we got, for evidence.
    var arr = [];
    if (typeof entries.length === 'number') {
        var i;
        for (i = 0; i < entries.length; i++) { arr.push(entries[i]); }
        shanhaiOvrInfo('entries container: array-like (.length=' + entries.length + ')');
    } else if (typeof entries.size === 'function') {
        var n = entries.size();
        var k = 0;
        var it = entries.iterator();
        while (it.hasNext()) {
            arr.push(it.next());
            k++;
            if (k > n) { break; }
        }
        shanhaiOvrInfo('entries container: java.util.List (.size()=' + n + ')');
    } else {
        return { ok: false, reason: 'entries is neither array-like nor a java List' };
    }

    return { ok: true, entries: arr };
}

// =============================================================================
// 🆕 2026-10-05（阶段 2）：非 GT 配方的【输入侧】字段
// =============================================================================
// 编辑器现在可以改非 GT 配方的输入（增 / 删 / 改），落盘时写进同一条 op=set 的 fields 里：
//     有形状合成   crafting_shaped     => pattern:[…] + key:{…}
//     无形状合成   crafting_shapeless  => ingredients:[…]
//     烧炼那一族 / 切石机               => ingredient:{…}
//
// 🔴 这一支是【纯新增】：fields 里没有这四个键时，"拆分"的结果与改动之前逐字相同
//    （names 原样，shapeNames 为空），已有条目的行为一个字节都没变。
//
// 🔴 为什么不能直接把它们丢给通用的 recipe.set 循环：
//    那一条路对**半截/自相矛盾**的形状是【照写不误】的。原版 ShapedRecipe 的反序列化器
//    在读 pattern 时会因为"这一行的宽度和上一行不一样"或者"pattern 里引用了一个 key 里
//    没有的字符"直接抛 JsonParseException ⇒ 那一条配方在数据包里变成一条读不出来的坏配方。
//    ⇒ 本文件在这里加一道【fail-closed】的闸：先整条校验，不过就【整条 entry 不套用】
//      （与 conditions 那一支同一条纪律：宁可一条都不装，也不装一半）。
//
// 判据行（可 grep）：
//     通过  => '… APPLIED op=set id=… fields[SHAPE:pattern …]'
//     被拒  => '… SHAPE REJECTED op=set id=… -> <理由>'
//    负对照 => 本文件顶部的 shanhaiOvrShapeSelfcheck() 会故意喂 7 种写坏的形状，
//             每一种都必须被拒（它自己打 SHAPE_CHECK 行）。
var SHANHAI_OVR_SHAPE_KEYS = { pattern: 1, key: 1, ingredients: 1, ingredient: 1 };

/** 这个值是"数组"吗（Rhino 里可能是 JS Array、JsonArray、或 java.util.List）。 */
function shanhaiOvrIsArrayLike(v) {
    if (v === null || v === undefined) {
        return false;
    }
    if (typeof v.length === 'number') {
        return true;
    }
    if (typeof v.size !== 'function') {
        return false;
    }
    // 🔴 2026-10-05（本机端到端实测抓到的假判定）：
    //    `java.util.Map` **也有** `size()`，所以"有 size() 就当数组"会把一个正经的对象
    //    判成数组 —— 后果是 `key` 表被判"不是一个对象" ⇒ 整条 op=set 被 SHAPE REJECTED、
    //    用户的形状改动静默不生效（读数：`key 不是一个对象（它应当是 "字符 → 物品" 的映射）`）。
    //    凡是"有键值访问器"的一律【不是】数组（List 没有 put/keySet/entrySet，不会被误伤）。
    if (typeof v.put === 'function' || typeof v.keySet === 'function'
        || typeof v.entrySet === 'function') {
        return false;
    }
    return true;
}

/** 数组/列表 → 普通 JS 数组（拿不到就返回 null）。 */
function shanhaiOvrToArray(v) {
    if (!shanhaiOvrIsArrayLike(v)) {
        return null;
    }
    var out = [];
    var i;
    if (typeof v.length === 'number') {
        for (i = 0; i < v.length; i++) {
            out.push(v[i]);
        }
        return out;
    }
    var n = v.size();
    var it = v.iterator();
    var k = 0;
    while (it.hasNext()) {
        out.push(it.next());
        k++;
        if (k > n) { break; }
    }
    return out;
}

/** 这个值是"对象"吗（JsonObject / Map / Scriptable 都算；数组与标量不算）。 */
function shanhaiOvrIsObjectLike(v) {
    if (v === null || v === undefined) {
        return false;
    }
    if (typeof v !== 'object' && typeof v !== 'function') {
        return false;
    }
    if (shanhaiOvrIsArrayLike(v)) {
        return false;
    }
    return true;
}

/** 对象里有没有这个键（KubeJS 的 MapJS 上 hasOwnProperty 不可靠 ⇒ 直接枚举）。 */
function shanhaiOvrHasKey(o, key) {
    var k;
    for (k in o) {
        if (k === key) {
            return true;
        }
    }
    return o[key] !== undefined;
}

/**
 * 把 fields 里的键拆成"输入侧那几个"与"其余"，并对输入侧做【整条】校验。
 *
 * @return { ok:true, shapeNames:[…], shapeVals:{…}, plainNames:[…] }
 *         或 { ok:false, err:'…' }
 */
function shanhaiOvrPlanShape(fields, names) {
    var shapeNames = [];
    var plainNames = [];
    var shapeVals = {};
    var i;
    for (i = 0; i < names.length; i++) {
        if (SHANHAI_OVR_SHAPE_KEYS[names[i]] === 1) {
            shapeNames.push(names[i]);
            shapeVals[names[i]] = fields[names[i]];
        } else {
            plainNames.push(names[i]);
        }
    }
    var out = { ok: true, shapeNames: shapeNames, shapeVals: shapeVals, plainNames: plainNames };
    if (shapeNames.length === 0) {
        return out;              // 没动输入 ⇒ 与改动之前逐字相同
    }
    var hasPattern = shanhaiOvrHasKey(shapeVals, 'pattern');
    var hasKey = shanhaiOvrHasKey(shapeVals, 'key');
    var hasIngredients = shanhaiOvrHasKey(shapeVals, 'ingredients');
    var hasIngredient = shanhaiOvrHasKey(shapeVals, 'ingredient');

    // ① 三种形态只能出现一种
    var groups = 0;
    if (hasPattern || hasKey) { groups++; }
    if (hasIngredients) { groups++; }
    if (hasIngredient) { groups++; }
    if (groups > 1) {
        return { ok: false, err: '同时出现了两种形状（pattern/key、ingredients、ingredient 只能有一种）' };
    }
    // ② pattern 与 key 必须成对
    if (hasPattern !== hasKey) {
        return { ok: false, err: 'pattern 与 key 必须成对出现（有形状合成的 key 表就是"字符→物品"的映射，少一个就配不出形状）' };
    }
    // ③ 有形状合成
    if (hasPattern) {
        var rows = shanhaiOvrToArray(shapeVals.pattern);
        if (rows === null) {
            return { ok: false, err: 'pattern 不是数组' };
        }
        if (rows.length < 1 || rows.length > 3) {
            return { ok: false, err: 'pattern 的行数必须在 1..3 之间（现在 ' + rows.length + '）' };
        }
        var w = -1;
        var r;
        for (r = 0; r < rows.length; r++) {
            var row = String(rows[r]);
            if (row.length < 1 || row.length > 3) {
                return { ok: false, err: 'pattern 第 ' + (r + 1) + ' 行宽度必须在 1..3 之间（现在 ' + row.length + '）' };
            }
            if (w < 0) {
                w = row.length;
            } else if (w !== row.length) {
                return { ok: false, err: 'pattern 各行的宽度必须一样（第 1 行 ' + w + ' 个字符，第 ' + (r + 1) + ' 行 ' + row.length + ' 个）' };
            }
        }
        if (!shanhaiOvrIsObjectLike(shapeVals.key)) {
            return { ok: false, err: 'key 不是一个对象（它应当是 "字符 → 物品" 的映射）' };
        }
        var used = {};
        for (r = 0; r < rows.length; r++) {
            var row2 = String(rows[r]);
            var c;
            for (c = 0; c < row2.length; c++) {
                var ch = row2.charAt(c);
                if (ch === ' ') { continue; }
                used[ch] = 1;
                if (!shanhaiOvrHasKey(shapeVals.key, ch)) {
                    return { ok: false, err: 'pattern 里用到了字符 "' + ch + '"，但 key 表里没有它（这样的配方原版读不出来）' };
                }
                if (!shanhaiOvrIsObjectLike(shapeVals.key[ch])) {
                    return { ok: false, err: 'key["' + ch + '"] 不是一个物品（原版要求每个字符对应一个 ingredient 对象）' };
                }
            }
        }
        var any = false;
        var u;
        for (u in used) {
            if (used[u] === 1) { any = true; }
        }
        if (!any) {
            return { ok: false, err: 'pattern 全是空格（一条没有任何材料的合成配方）' };
        }
    }
    // ④ 无形状合成
    if (hasIngredients) {
        var list = shanhaiOvrToArray(shapeVals.ingredients);
        if (list === null) {
            return { ok: false, err: 'ingredients 不是数组' };
        }
        if (list.length < 1 || list.length > 9) {
            return { ok: false, err: 'ingredients 的条数必须在 1..9 之间（现在 ' + list.length + '）' };
        }
        for (i = 0; i < list.length; i++) {
            if (!shanhaiOvrIsObjectLike(list[i])) {
                return { ok: false, err: 'ingredients 第 ' + (i + 1) + ' 项不是一个物品' };
            }
        }
    }
    // ⑤ 单格输入
    if (hasIngredient) {
        if (!shanhaiOvrIsObjectLike(shapeVals.ingredient)) {
            return { ok: false, err: 'ingredient 不是一个物品对象' };
        }
    }
    return out;
}

/**
 * 🔴 这一段形状校验自己的判据（放这里跑 = 每次开机都跑一次，与 conditions 那一支同一个做法）。
 * 7 种"故意写坏"的形状【必须】全部被拒，1 种正常形状【必须】通过。
 * 没有这一条，"拒收逻辑"可能是个恒真的空壳而没人发现。
 */
function shanhaiOvrShapeSelfcheck() {
    var good = { pattern: ['aa', 'a '], key: { a: { item: 'minecraft:stone' } } };
    var bad = [
        ['pattern_without_key', { pattern: ['aa'] }],
        ['key_without_pattern', { key: { a: { item: 'minecraft:stone' } } }],
        ['ragged_rows', { pattern: ['aa', 'a'], key: { a: { item: 'minecraft:stone' } } }],
        ['unknown_symbol', { pattern: ['ab'], key: { a: { item: 'minecraft:stone' } } }],
        ['all_spaces', { pattern: ['  ', '  '], key: { a: { item: 'minecraft:stone' } } }],
        ['too_tall', { pattern: ['a', 'a', 'a', 'a'], key: { a: { item: 'minecraft:stone' } } }],
        ['empty_ingredients', { ingredients: [] }]
    ];
    var ok = 0;
    var total = 0;
    var plan;
    var i;
    // 正对照
    total++;
    plan = shanhaiOvrPlanShape(good, ['pattern', 'key']);
    if (plan.ok && plan.shapeNames.length === 2 && plan.plainNames.length === 0) {
        ok++;
    } else {
        shanhaiOvrError('SHAPE_CHECK 正对照居然被拒了：' + (plan.ok ? '(形状数量不对)' : plan.err));
    }
    // 负对照
    for (i = 0; i < bad.length; i++) {
        total++;
        plan = shanhaiOvrPlanShape(bad[i][1], ['pattern', 'key', 'ingredients', 'ingredient']);
        if (!plan.ok) {
            ok++;
        } else {
            shanhaiOvrError('SHAPE_CHECK 负对照 ' + bad[i][0] + ' 居然通过了 —— 这道闸门是坏的');
        }
    }
    // 纯新增的证明：fields 里没有形状键时，拆分结果必须与"一个都不拆"逐字相同
    total++;
    var plain = shanhaiOvrPlanShape({ result: { item: 'minecraft:stone' }, cookingtime: 200 },
            ['result', 'cookingtime']);
    if (plain.ok && plain.shapeNames.length === 0 && plain.plainNames.length === 2) {
        ok++;
    } else {
        shanhaiOvrError('SHAPE_CHECK 无形状键的那条路被改动了（这会破坏向后兼容）');
    }
    shanhaiOvrInfo('SHAPE_CHECK ' + ok + '/' + total + ' PASS=' + (ok === total)
        + ' （正对照 1 条必须过；负对照 ' + bad.length + ' 条必须全被拒；'
        + '外加 1 条"字段里没有形状键时行为不变"的向后兼容判据）');
}

// =============================================================================
// 🆕 2026-10-05（P0）：「新建配方」那条 op=add 通道 —— 根因与修法
// =============================================================================
// 现场（用户实例 logs\latest.log 逐字）：
//   shanhai_recipe_overrides.js#77: e-editor-261005-091831 SKIPPED op=add
//     id=shanhai:worldline_probability_cracking/new_recipe_1
//     -> TypeError: Cannot call property id in object
//        gtceu:kjs/d913v1n7xhhewltiz66imxmzl[gtceu:worldline_probability_cracking].
//        It is not a function, it is "object".
//   ⇒ 然后 **Failed to load level data or datapacks, can't proceed with server load** ⇒ 世界进不去。
//
// 🔴 根因（不是猜的：javap 的字节码 + 本机无头专服一次真跑，原始读数都留在报告里）
//   ① `RecipeJS` 上 `id` **既是 public 字段又是方法**：
//        public net.minecraft.resources.ResourceLocation id;              ← 字段
//        public RecipeJS id(net.minecraft.resources.ResourceLocation);    ← 方法（参数是 ResourceLocation，不是 String）
//   ② 但 JS 侧看到的 `id` 是不是可调用，**取决于具体的 RecipeJS 子类**：
//        实测 KubeJS 自己的 ShapelessRecipeJS  → typeof(r.id) == 'function'  ⇒ `.id(...)` 能调
//        实测 GT 的 GTRecipeSchema$GTRecipeJS  → typeof(r.id) == 'object'    ⇒ `.id(...)` **抛 TypeError**
//      （本机探针原始行：`E0 typeof(r.id)=object class=...GTRecipeSchema$GTRecipeJS`）
//      ⇒ 用户在 GT 配方上用 `.id(...)` **必然**抛；而 `dgy.js` / `shanhai_recipes.js` 里那些
//        `.id(...)` 之所以没事，是因为它们作用在 KubeJS 自己的原版 schema 上。
//   ③ 抛了以后那条配方**已经**被加进 `event.addedRecipes` 了 ⇒ 它没有合法 id。
//   ④ 🔴 真正致命的一步在 GTCEu 里（javap 原文）：
//        GTRecipeJS 里那个真正的 id 存取器是【另一个私有字段】：
//            private net.minecraft.resources.ResourceLocation idWithoutType;
//            public GTRecipeJS id(ResourceLocation id) { ...; this.idWithoutType = ...; this.id = type.path + "/" + idWithoutType.path; }
//            public ResourceLocation idWithoutType() { return this.idWithoutType; }   ← 只读那个字段
//        而 GT 收配方时走的是：
//            GTRecipeType.recipeBuilder(gtRecipeJS.idWithoutType(), new Object[0])   ← GrechTechKubeJSPlugin$injectRuntimeRecipes
//        ⇒ `idWithoutType` 是 null 时 builder 的 id 就是 null ⇒ GTRecipeBuilder 里
//          `FinishedRecipe.getID()` 读 `this.this$0.id` ⇒ **NPE ⇒ 整份数据包加载失败 ⇒ 世界进不去**。
//        ⚠️ 所以 **光把字段 `r.id` 赋值是不够的**（探针实测：字段赋了值，getId() 也对，
//           但 `idWithoutType` 仍然是 null ⇒ 照样 NPE）。
//          **唯一会同时设上这两个字段的，是 `GTRecipeJS.id(ResourceLocation)` 本人**，
//          而它只会被 **类型函数（`event.recipes.gtceu.<类型>(id字符串)`）的构造器** 调到。
//
// ✅ 修法（纯新增：只在 op=add 这一支里按"是不是 GT 类型"分流，别的分支一行没动）
//   GT   ⇒ `event.recipes.<ns>.<path>(id字符串)`（id 走构造器 ⇒ id 与 idWithoutType 都设上），
//          再把 json 里除 type 之外的每个键用 `recipe.set(k, JsonIO.of(v))` 灌进去
//          —— 与 op=set 那一段【同一段口径、同一个 JsonIO.of 转换】。
//   非 GT ⇒ `event.custom(json)` 之后 **给字段赋值** `r.id = id字符串`
//          （实测可用：Rhino 会把字符串转成 ResourceLocation；注意**不要**写 `.id(...)`）。
//   🔴 两道闸门（用户点名要的「失败必须拦在世界加载之前」）：
//     闸门 A：造完立刻核对 `String(r.getId()) === id`，不等就**当场把这条从 addedRecipes 里摘掉**；
//     闸门 B（兜底，覆盖"别的脚本也可能这么干"）：本脚本是最后跑的（priority -1000），
//            收尾时扫一遍 `event.addedRecipes`，凡是 **GT 配方且 `idWithoutType()` 为 null**
//            的一律摘掉并打一行 ERROR —— 那一条【一定】会让整份数据包加载失败。
//
// 判据行（可 grep）：
//     成功 => '… APPLIED op=add id=… type=… via=gt_type_function|custom_field …'
//     摘除 => '… ADD ORPHAN REMOVED id=…（它没有合法 id，留下会让 GT 的 save() NPE 把世界带崩）'
//     汇总 => 末尾的 'add_orphan_sweep …'
var SHANHAI_OVR_ADD_ORPHANS = 0;

/** 这个 json 的 type 对应的 KubeJS 类型函数（`event.recipes.<ns>.<path>`）；拿不到返回 null。 */
function shanhaiOvrTypeFunction(event, typeId) {
    var i = String(typeId).indexOf(':');
    if (i <= 0) {
        return null;
    }
    var ns = String(typeId).substring(0, i);
    var path = String(typeId).substring(i + 1);
    try {
        var root = event.recipes;
        if (root === null || root === undefined) {
            return null;
        }
        var byNs = root[ns];
        if (byNs === null || byNs === undefined) {
            return null;
        }
        var fn = byNs[path];
        if (fn === null || fn === undefined) {
            return null;
        }
        return fn;
    } catch (e) {
        return null;
    }
}

/**
 * 这条配方会不会让 GT 的 injectRuntimeRecipes 抛 NPE（= 世界进不去）。
 * 判据 = 它是 GTRecipeJS 且 `idWithoutType()` 为 null/空。
 * @return true = 必须摘掉；`null` = **判不了**（拿不到那个方法）——如实返回，别当成"没问题"。
 */
function shanhaiOvrIsGtOrphan(recipe) {
    var cn = '';
    try {
        cn = String(recipe.getClass().getName());
    } catch (e0) {
        return null;
    }
    if (cn.indexOf('GTRecipeSchema') < 0) {
        return false;                 // 不是 GT 的 RecipeJS ⇒ 这条闸门管不着它
    }
    try {
        var v = recipe.idWithoutType();
        return (v === null || v === undefined || String(v).length === 0);
    } catch (e1) {
        return null;                  // 判不了（绑定/方法名变了）⇒ 调用方如实记账
    }
}

/** 把一条配方从 KubeJS 的"待保存队列"里摘掉（并打一行可 grep 的 ERROR）。 */
function shanhaiOvrDropRecipe(event, recipe, id, why) {
    var removedQ = false;
    try {
        removedQ = event.addedRecipes.remove(recipe);
    } catch (e0) {
        // 摘不掉也要继续试 r.remove()，并且如实记账
    }
    try {
        recipe.remove();
    } catch (e1) {
        // 同上
    }
    SHANHAI_OVR_ADD_ORPHANS++;
    shanhaiOvrError('ADD ORPHAN REMOVED id=' + id + ' queue_removed=' + removedQ
        + ' -> ' + why
        + '（它没有合法 id：留在保存队列里会让 GT 的 GTRecipeBuilder.getID() 抛 NPE，'
        + '进而让【整份数据包加载失败】= 世界进不去。宁可这一条不生效，也不许把世界带崩）');
}

/**
 * 一个 {@code JsonObject} 的**键名列表**。
 *
 * <p>🔴 为什么不能用 {@code for (var k in json)}：JsonObject 是 Java 对象，
 * Rhino 的 for..in 枚举的是它的**成员方法名**（getClass / getAsString / size …），
 * 不是 JSON 键。本机实测的后果见 {@code shanhaiOvrAddOne} 里的注释。
 */
function shanhaiOvrJsonKeys(json) {
    var out = [];
    try {
        var ks = json.keySet();
        var it = ks.iterator();
        while (it.hasNext()) {
            out.push(String(it.next()));
        }
        return out;
    } catch (e0) {
        // 拿不到 keySet（不是 JsonObject / 绑定变了）⇒ 退回 for..in，但要滤掉 Java 成员名
        var k;
        for (k in json) {
            if (k.indexOf('$') >= 0 || (k.length > 1 && k.charAt(0) === k.charAt(0).toLowerCase()
                    && (k === 'class' || k === 'size' || k === 'wait' || k === 'notify'
                        || k === 'equals' || k === 'hashCode' || k === 'toString'))) {
                continue;
            }
            out.push(k);
        }
        return out;
    }
}

/**
 * 🔴 <b>GT 类型函数要的是"去掉类型路径之后"的 id</b>。
 *
 * 字节码：{@code GTRecipeJS.id(ResourceLocation id)} 里
 * <pre>
 *   this.idWithoutType = &lt;把传进来的那个 id 原样记下&gt;;
 *   this.id = new ResourceLocation(namespace, "%s/%s".formatted(type.id.getPath(), idWithoutType.getPath()));
 * </pre>
 * ⇒ 传 {@code shanhai:assembler/foo} 会得到 {@code shanhai:assembler/assembler/foo}（**双前缀**）。
 * 本机端到端实测就是这个读数：
 * <pre>
 *   造出来的那条 id 不对（shanhai:assembler/assembler/editor_probe_add_replay，
 *                       期望 shanhai:assembler/editor_probe_add_replay）
 * </pre>
 * 而编辑器存进覆盖文件的 {@code e.id} 是**完整 id**（带类型路径）⇒ 这里必须先剥掉那一段。
 */
function shanhaiOvrStripTypePath(id, typeId) {
    var s = String(id);
    var ti = String(typeId).indexOf(':');
    if (ti <= 0) {
        return s;
    }
    var typePath = String(typeId).substring(ti + 1);
    var i = s.indexOf(':');
    if (i <= 0) {
        return s;
    }
    var ns = s.substring(0, i);
    var p = s.substring(i + 1);
    var pre = typePath + '/';
    if (p.indexOf(pre) === 0) {
        p = p.substring(pre.length);
    }
    return ns + ':' + p;
}

// =============================================================================
// 🆕 2026-10-07：op=add 的额外条件（现场读数：新建（op=add）的配方【重启后条件不生效】）
//
// 症状（用户两局的读数，逐字）：
//   第一局（编辑器那一侧，条件还在内存里）：JEI 配方页有「物质重组模块」催化剂槽 ✓
//   第二局（重启后由本脚本重放）：那 9 条 op=add 【一条催化剂行都没有】✗，且汇总行是
//       overlay source=config/shanhai/recipe_overrides.json schema=1 entries=15 applied=15
//               stale=0 missing=0 skipped=0 needs_resave=0 fp_stub=0 fp_stub_suppressed=0
//               cond_applied=0 cond_failed=0 add_orphans=0
//
// 根因：`shanhaiOvrAddOne` 当时只做了 `r.merge(json)` —— 整个函数体里【没有任何 conditions 处理】。
//   op=add 的条件长在 `entry.recipe.conditions`（op=set 那份长在 `entry.fields.conditions`，
//   见 shanhaiOvrConditionsOf），merge 只是把它当成一个【普通键】塞进配方 json；
//   而 GT 的加载器读的是另一个键 `recipeConditions`（RecipeCondition.CODEC 的平铺形状）
//   ⇒ 条件从来没被 GT 看见 ⇒ 重启后条件凭空消失。
//
// 🔴 落法 = 与 op=set 【完全同一套】，这里没有第二份实现：
//     · 取值：shanhaiOvrAddConditionsOf（差别只是"条件在 json 的哪个位置"）
//     · 校验：shanhaiOvrValidateConditions   ← op=set 调的也是它（内部 = Java ShanhaiConditions.validate）
//     · 落盘：ShanhaiConditions.apply(recipe, conds) ← op=set 调的也是它（同一个 Java 入口）
//   以后要改条件的形状/写法，只需要改 Java 那一个入口，两条路不会漂。
//
// 🔴 时机：必须在 `r.merge(json)`【之后】。merge 内部会 deserialize(true)，把 recipeConditions
//   那个 component 重新标成"要写回"；Java 侧 apply() 的第二步才是把这个 write 标志压掉
//   （不压的话 RecipeJS.serialize() 会拿 component 的 {"type":k,"data":{…}} 形状把刚写对的
//   平铺 json 覆盖回去 —— 见 ShanhaiRecipeConditionReplay 的类注释 §1.2）。
// =============================================================================

/**
 * op=add 的条件在 `entry.recipe.conditions`（与 op=set 的 `entry.fields.conditions` 不是同一个位置）。
 *
 * @return {present:boolean, el:?, err:string} —— err 非空 = "读不到"（不是"没有"），由调用方如实报。
 */
function shanhaiOvrAddConditionsOf(json) {
    var v;
    try {
        // ⚠️ 必须走【方法调用】：JsonObject 不是 java.util.Map（本文件 1076-1080 行有 javap 取证），
        //    写成 `json['conditions']` 那种属性访问只会拿到 undefined
        //    ＝"静默当成没有条件"，那正是这个 bug 的形态。
        v = json.get('conditions');
    } catch (e0) {
        return { present: false, el: null,
            err: 'recipe 不是 JsonObject（json.get("conditions") 抛了: ' + e0 + '）' };
    }
    if (v === null || v === undefined) {
        return { present: false, el: null, err: '' };
    }
    return { present: true, el: v, err: '' };
}

/**
 * 把这条 op=add entry 声明的条件装到【刚造出来】的那条配方上（与 op=set 同一套落法，见本节注释）。
 *
 * 判据行（可 grep；用的词与 op=set 汇总行同一口径）：
 *   overlay_add_conditions id=… n=<条数> cond_applied=1 cond_failed=0   ← 条件装上了
 *   overlay_add_conditions id=… n=0 cond_applied=0 cond_failed=0        ← 本条 entry 本来就没写 conditions
 *   overlay_add_conditions id=… n=<条数> cond_applied=0 cond_failed=1   ← 写了但装失败（配方本身已加上）
 *
 * @return {applied:boolean, failed:boolean, note:string}
 */
function shanhaiOvrAddApplyConditions(recipe, conds, id) {
    if (conds === null || conds === undefined) {
        // 🔴 这一行就是【"没条件"与"没处理"的判据】：
        //    修之前（2026-10-07 前）整条 add 路一行条件日志都没有 ⇒ 两者在日志上长得一样；
        //    现在没有条件也明说一句，且句子本身交代了 add 路走的是同一套落法。
        shanhaiOvrInfo('overlay_add_conditions id=' + id + ' n=0 cond_applied=0 cond_failed=0'
            + ' note=本条 entry 没有写 conditions（不是"没处理"：add 路走的是与 op=set 同一套落法）');
        return { applied: false, failed: false, note: ' conditions n=0' };
    }
    var n = -1;
    try { n = conds.size(); } catch (eN) { n = -1; }
    var cerr = '';
    try {
        cerr = ShanhaiConditions.apply(recipe, conds);
    } catch (eCond) {
        cerr = 'CONDITIONS APPLY THREW: ' + eCond;
    }
    if (cerr === null || cerr === undefined || String(cerr).length === 0) {
        shanhaiOvrInfo('overlay_add_conditions id=' + id + ' n=' + n
            + ' cond_applied=1 cond_failed=0');
        return { applied: true, failed: false, note: ' conditions n=' + n };
    }
    // 与 op=set 的 CONDITIONS APPLY FAILED 同口径：配方【已经加上了】，只有条件没装上 ——
    // 不是"没加"，是"少了一样"（重新在编辑器里保存一次这条配方即可）。
    shanhaiOvrError('overlay_add_conditions id=' + id + ' n=' + n + ' cond_applied=0 cond_failed=1'
        + ' -> ' + cerr
        + '（注意：这条配方本身【已经加上了】，只有条件没装上 ——'
        + ' 不是"没加"，是"少了一样"。在编辑器里对这条配方再「保存」一次即可）');
    return { applied: false, failed: true, note: ' conditions n=' + n + ' cond_failed=1' };
}

/**
 * 造一条 op=add 的配方，并保证"它要么有合法 id、要么被摘掉"。
 *
 * @return { ok:true, via:'…', note:'…', condApplied:boolean, condFailed:boolean } 或 { ok:false, why:'…' }
 *         （condApplied / condFailed 是 🆕 2026-10-07 加的：调用方把它们并进同一个
 *           cond_applied / cond_failed 汇总口径，op=set 的数也在那两个里）
 */
function shanhaiOvrAddOne(event, id, type, rawRecipe) {
    var json;
    try {
        json = JsonIO.of(rawRecipe);
    } catch (e0) {
        return { ok: false, why: 'recipe 字段转不成 JsonObject: ' + e0 };
    }
    if (json === null || json === undefined) {
        return { ok: false, why: 'entry 里没有 recipe 对象' };
    }
    var declaredType = '';
    try {
        declaredType = (json.type === undefined || json.type === null) ? '' : String(json.type);
    } catch (e1) {
        declaredType = '';
    }
    var gt = (String(type).indexOf('gtceu:') === 0) || (declaredType.indexOf('gtceu:') === 0);

    // 🆕 2026-10-07（op=add 的条件）：先把本条 entry 声明的条件取出来并【整条校验】，
    //    再决定造不造这条配方 —— 与 op=set 的 fail-closed 同口径（那一段在 op=set 分支里，
    //    注释原文：「🔴 校验在【写任何字段之前】做：不过就整条不套用（fail-closed，绝不半套用）」）。
    //    🔴 位置在 `fn(id)` / `event.custom` 【之前】⇒ 校验不过时那条配方根本不会被造出来，
    //       不需要"造了再摘"（摘的动作只留给下面那道 id 闸门）。
    //    ⚠️ 这里把 JsonElement 直接交给共用的校验函数是安全的：JsonIO.of(Object) 对 JsonElement 是
    //       【恒等】（javap 实测：`instanceof JsonElement → checkcast → areturn`），
    //       所以走进 shanhaiOvrValidateConditions 之后拿到的还是这个 JsonArray。
    var addConds = null;
    var acRead = shanhaiOvrAddConditionsOf(json);
    if (acRead.err !== '') {
        // "读不到" ≠ "没有"：如实报一行 WARN，但不因此判死整条 entry ——
        // 那种形状下 GT 那条路本来也走不通，真正的原因由下面的 id 闸门给出，别让两条错误互相盖住。
        shanhaiOvrWarn('overlay_add_conditions id=' + id + ' READ FAILED -> ' + acRead.err
            + '（按"没有条件"继续；这条 entry 能不能装，由下面的 id 闸门给结论）');
    } else if (acRead.present) {
        var acv = shanhaiOvrValidateConditions(acRead.el);
        if (!acv.ok) {
            return { ok: false, why: 'CONDITIONS REJECTED op=add id=' + id + ' -> ' + acv.err
                + '（整条 entry 不装：宁可一条都不加，也不加一条条件读不出来的配方）' };
        }
        addConds = acv.arr;
    }

    if (gt) {
        // ① GT：id 必须走类型函数的构造器（只有它会调 GTRecipeJS.id(ResourceLocation)，
        //    而那个方法会同时设上 id 与私有的 idWithoutType —— 少了后者 GT 会 NPE）。
        var fn = shanhaiOvrTypeFunction(event, declaredType === '' ? type : declaredType);
        if (fn === null) {
            return { ok: false, why: '找不到类型函数 event.recipes.<ns>.<path>（type=' + type + '）'
                + '⇒ 不敢用 event.custom()：那条路拿不到 idWithoutType，会把世界带崩' };
        }
        var r;
        try {
            // 🔴 必须传"剥掉类型路径"的那个 id（见 shanhaiOvrStripTypePath 的字节码取证）
            r = fn(shanhaiOvrStripTypePath(id, declaredType === '' ? type : declaredType));
        } catch (e2) {
            return { ok: false, why: '类型函数调用抛了：' + e2 };
        }
        if (r === null || r === undefined) {
            return { ok: false, why: '类型函数没有返回配方对象' };
        }
        // ══════════════════════════════════════════════════════════════════════════
        // 🔴 2026-10-05 第 11 刀：把整份配方 JSON 原样灌回去 —— 走 KubeJS 自己的 merge()
        //
        // 前两版都栽在"逐键 set()"上，两次都是**静默错**，读数分别如下：
        //   第 1 版 `for (var k in json)`      ⇒ 枚举的是 Java 成员方法名，sets=0
        //   第 2 版 `r.set(k, JsonIO.of(json[k]))` ⇒ sets=2 但 set_errors=[duration -> …]，
        //        而且下一局读回来 `dur=100 eut=0`（＝一个键都没真正生效）
        //
        // 第 2 版为什么错（这一版是查清了才改的，不是再试一次）：
        //   ① `com.google.gson.JsonObject` **不是 java.util.Map**（javap 实测：
        //      `public final class JsonObject extends JsonElement`，只有自己的 keySet()/get()）
        //      ⇒ Rhino 不会把它当 Map 包装 ⇒ `json['duration']` 走的是"找 Java 成员"那条路
        //      ⇒ **所有键都取到 undefined**（不是只有 duration）。
        //   ② 为什么 duration 报错、data/tickInputs 却"成功"：
        //      · duration 组件拿到 undefined ⇒ parseInt('undefined') ⇒ 抛 ⇒ 被我们记成 errors ✓
        //      · data     组件（RecipeKey<CompoundTag>，read=NBTUtils.toTagCompound）
        //        与 tickInputs 组件（CapabilityMap）对"读不出来"的形状**不报错、直接给空**
        //        ⇒ 它们**假成功**（这正是本文件 op=set 那段注释里记过的同一个坑）
        //      ⇒ 结果就是"三个键、两个假成功、一个报错"——与实测读数逐字对上。
        //   ③ 正确入口是 `RecipeJS.merge(JsonObject)`（javap -c 逐条读出来的）：
        //        for (Map.Entry<String,JsonElement> e : json.entrySet())
        //            this.json.add(e.getKey(), e.getValue());   // 原样写进配方 json
        //        save(); deserialize(true);                     // 再落一次 + 从 json 反序列化回组件
        //      ⇒ 它吃的就是**真正的 JsonObject**（`JsonIO.of(rawRecipe)` 产出的那个），
        //        不需要我们手写"哪个键该转成什么形状"—— 那正是上次出错的地方。
        //      ⇒ 判据：下一局 case=add_replay 必须读到 dur=4242（写进去的那个值）。
        // ══════════════════════════════════════════════════════════════════════════
        var setCount = 0;
        var setErr = '';
        var via = 'merge';
        var jsonCls = '';
        try { jsonCls = String(json.getClass().getName()); } catch (eCls) { jsonCls = '(?)'; }
        try {
            r.merge(json);
            setCount = -1;                       // -1 = 走了 merge（不做逐键计数）
        } catch (eMerge) {
            // 退路：逐键 set，但**键值必须走 json.get(k) 方法调用**（不是 json[k] 属性访问）——
            // 见上面 ①：JsonObject 不是 Map，属性访问只会拿到 undefined。
            via = 'per_key_set(after_merge_threw)';
            setErr += ' [merge -> ' + eMerge + ']';
            var keys = shanhaiOvrJsonKeys(json);
            var ki;
            for (ki = 0; ki < keys.length; ki++) {
                var k = keys[ki];
                if (k === 'type') {
                    continue;
                }
                try {
                    r.set(k, json.get(k));
                    setCount++;
                } catch (e3) {
                    setErr += ' [' + k + ' -> ' + e3 + ']';
                }
            }
        }
        // 闸门 A：id 必须逐字对上（对上了就说明构造器那条路真的走到了 id()）
        var got = '';
        try {
            got = String(r.getId());
        } catch (e4) {
            got = '(getId threw: ' + e4 + ')';
        }
        var orphan = shanhaiOvrIsGtOrphan(r);
        if (orphan !== false || got !== String(id)) {
            shanhaiOvrDropRecipe(event, r, id,
                'gt_id_check got=' + got + ' want=' + id + ' idWithoutType_null_or_unknown=' + orphan);
            return { ok: false, why: '造出来的那条 id 不对（' + got + '，期望 ' + id
                + '）或 idWithoutType 没设上 ⇒ 已把它从保存队列里摘掉' };
        }
        // 🆕 2026-10-07：条件（与 op=set 同一套落法）。放在 id 闸门【之后】——
        //    闸门没过的那条会被 shanhaiOvrDropRecipe 摘掉，没必要再给它装条件。
        var condRes = shanhaiOvrAddApplyConditions(r, addConds, id);
        return { ok: true, via: 'gt_type_function',
            condApplied: condRes.applied, condFailed: condRes.failed,
            note: 'via=' + via + ' sets=' + setCount + ' json_cls=' + jsonCls
                + (setErr === '' ? '' : ' set_errors=' + setErr) + condRes.note };
    }

    // ② 非 GT：event.custom + 设 id。
    //    🔴 第 12 刀把这一支改成【两条路都试、都读回来核对】—— 理由：
    //       · KubeJS 的 RecipeJS 上 `id` 既是 public 字段（ResourceLocation）又是方法
    //         `id(ResourceLocation)`；JS 侧 `typeof v.id` 在不同子类上可能是 field 也可能是 function
    //         （GT 的 GTRecipeJS 上实测是 object ⇒ 调它抛 TypeError，那就是第 10 刀 B 那次 P0）；
    //       · 而且"赋值没抛"不等于"真的写进了那个 Java 字段" ⇒ 必须【读回来核对】，
    //         不对就换另一条路再核对；两条都不行才 SKIPPED（绝不静默放一条 id 不对的配方过去）。
    //       这一支在本刀之前【从来没被真的跑过】（第 10 刀 B 只做了 GT 那一半）。
    var v;
    try {
        v = event.custom(json);
    } catch (e5) {
        return { ok: false, why: 'event.custom 抛了：' + e5 };
    }
    if (v === null || v === undefined) {
        return { ok: false, why: 'event.custom 没有返回配方对象' };
    }
    var how = '';
    var errs = '';
    var got2 = '';
    try {
        v.id = id;                                  // 路 ①：给字段赋值
        how = 'field';
    } catch (e6) {
        errs += ' [field -> ' + e6 + ']';
    }
    try {
        got2 = String(v.getId());
    } catch (e7) {
        got2 = '(getId threw: ' + e7 + ')';
    }
    if (got2 !== String(id)) {
        try {
            v.id(id);                               // 路 ②：调方法（字段没生效时才走）
            how = (how === '' ? 'method' : how + '+method');
        } catch (e8) {
            errs += ' [method -> ' + e8 + ']';
        }
        try {
            got2 = String(v.getId());
        } catch (e9) {
            got2 = '(getId threw: ' + e9 + ')';
        }
    }
    if (got2 !== String(id)) {
        shanhaiOvrDropRecipe(event, v, id, 'custom_id_check got=' + got2 + ' want=' + id);
        return { ok: false, why: '造出来的那条 id 不对（' + got2 + '，期望 ' + id + '）'
            + '⇒ 已摘掉（设 id 的两条路都试过）' + errs };
    }
    // 🆕 2026-10-07：非 GT 那一支也走同一套条件落法（op=set 也是不分配方类型一律装）。
    var condResC = shanhaiOvrAddApplyConditions(v, addConds, id);
    return { ok: true, via: 'custom_field',
        condApplied: condResC.applied, condFailed: condResC.failed,
        note: '(event.custom + 设 id via=' + how + ' 读回=' + got2 + ')' + condResC.note };
}

/**
 * 🔴 收尾闸门：扫一遍保存队列，把**一定会让世界加载失败**的那几条摘掉。
 *
 * 判据只有一条：它是 GT 的 RecipeJS，且 `idWithoutType()` 为 null。
 * 为什么必须做：GT 收配方时是 `GTRecipeType.recipeBuilder(gtRecipeJS.idWithoutType(), …)`，
 * 那个 null 会一路变成 `GTRecipeBuilder.id == null` ⇒ `FinishedRecipe.getID()` 抛 NPE
 * ⇒ **整份数据包加载失败 ⇒ 世界进不去**（用户 2026-10-05 那次就是这样）。
 * 本脚本 priority=-1000（最后跑），所以这个时间点上每一层脚本的配方都已经在队列里了。
 *
 * @return 摘掉的条数
 */
function shanhaiOvrSweepGtOrphans(event) {
    var dropped = 0;
    var unknown = 0;
    var scanned = 0;
    var q = null;
    try {
        q = event.addedRecipes;
    } catch (e0) {
        return -1;
    }
    if (q === null || q === undefined) {
        return -1;
    }
    try {
        var snapshot = [];
        var it = q.iterator();
        while (it.hasNext()) {
            snapshot.push(it.next());
        }
        var i;
        for (i = 0; i < snapshot.length; i++) {
            var x = snapshot[i];
            scanned++;
            var bad = shanhaiOvrIsGtOrphan(x);
            if (bad === null) {
                unknown++;
                continue;
            }
            if (bad) {
                var xid = '(no-id)';
                try { xid = String(x.getId()); } catch (e1) { }
                shanhaiOvrDropRecipe(event, x, xid, 'sweep: idWithoutType() == null');
                dropped++;
            }
        }
    } catch (e2) {
        shanhaiOvrError('add_orphan_sweep THREW: ' + e2);
        return -1;
    }
    if (dropped > 0 || unknown > 0) {
        shanhaiOvrError('add_orphan_sweep scanned=' + scanned + ' dropped=' + dropped
            + ' undecidable=' + unknown
            + '（dropped>0 = 有人往保存队列里塞了没有合法 id 的 GT 配方；'
            + 'undecidable>0 = 拿不到 idWithoutType()，这一条闸门没起作用 —— 如实报出来）');
    } else {
        shanhaiOvrInfo('add_orphan_sweep scanned=' + scanned + ' dropped=0 undecidable=0');
    }
    return dropped;
}

function shanhaiOvrRun(event) {
    shanhaiOvrInfo('> reading overlay source=gameDir/' + SHANHAI_OVR_PATH);

    var loaded = shanhaiOvrLoad();
    if (!loaded.ok) {
        shanhaiOvrError('OVERLAY DISABLED (degraded, nothing applied): ' + loaded.reason);
        shanhaiOvrError('overlay source=' + SHANHAI_OVR_PATH + ' schema=? entries=0 applied=0 stale=0 missing=0 skipped=0 needs_resave=0');
        return;
    }

    var entries = loaded.entries;

    // 🔴 2026-10-05：先把"KubeJS 自己加的配方"这条通道接上（见上面 shanhaiOvrAddedIndex 的取证），
    //    并当场跑一次带负对照的自检 —— 判据行是 overlay_idcheck POSITIVE/NEGATIVE_CONTROL。
    var added = shanhaiOvrAddedIndex(event);
    shanhaiOvrInfo('overlay_added_index size=' + added.size + ' parsed=' + added.parsed
        + ' entries=' + entries.length + ' (旧口径只查得到数据包那一份，shanhai: 的配方在里面一条都没有)');
    shanhaiOvrSelfcheck(event, added);
    shanhaiOvrConditionsSelfcheck();
    // 🆕 阶段 2：非 GT 输入侧那道闸门自己的判据（1 条正对照 + 7 条负对照 + 1 条向后兼容）。
    shanhaiOvrShapeSelfcheck();

    var applied = 0;
    var stale = 0;
    var missing = 0;
    var skipped = 0;
    var needsResave = 0;        // 🆕 旧版指纹的条目（明确提示"重新保存一次"，而不是静默 STALE）
    var condApplied = 0;        // 🆕 B 组：这一局真的把条件装回去的 entry 条数
    var condFailed = 0;         // 🆕 B 组：校验通过但装的时候失败（同 id 每局只报一次明细）

    var i;
    for (i = 0; i < entries.length; i++) {
        var e = entries[i];
        var uid = (e && e.uid !== undefined) ? String(e.uid) : ('#' + i);
        var op = (e && e.op !== undefined) ? String(e.op) : '';
        var id = (e && e.id !== undefined) ? String(e.id) : '';
        var type = (e && e.type !== undefined) ? String(e.type) : '(none)';
        var wantFp = (e && e.base_fp !== undefined) ? String(e.base_fp) : '';

        if (id === '' || op === '') {
            shanhaiOvrError(uid + ' SKIPPED malformed entry (op="' + op + '" id="' + id + '")');
            skipped++;
            continue;
        }

        // Validate the op BEFORE anything else.  Otherwise an unknown op on a
        // fingerprint mismatch would be reported as STALE ("the target moved on")
        // when the real problem is that the editor wrote an op this build does not
        // implement - two completely different repairs.
        if (op !== 'add' && op !== 'set' && op !== 'remove' && op !== 'disable') {
            shanhaiOvrError(uid + ' SKIPPED unknown op="' + op + '" id=' + id +
                            ' (supported: add/set/remove/disable)');
            skipped++;
            continue;
        }

        var cnt;
        // 🔴 两条通道：先看 KubeJS 自己加的那一份（addedRecipes），再退回数据包那一份。
        //    两边给的都是【同一个 id 口径】= 真实 recipe id，不需要任何拼 id 的代码。
        var viaAdded = added.map[id];
        try {
            cnt = (viaAdded !== undefined) ? 1 : event.countRecipes({ id: id });
        } catch (ex) {
            shanhaiOvrError(uid + ' SKIPPED countRecipes threw for id=' + id + ': ' + ex);
            skipped++;
            continue;
        }

        // ---------- add -----------------------------------------------------
        if (op === 'add') {
            if (cnt > 0) {
                shanhaiOvrError(uid + ' STALE op=add id=' + id + ' type=' + type +
                                ' -> id already exists (' + cnt + ' recipe(s)); refusing to add');
                stale++;
                continue;
            }
            var addRes = shanhaiOvrAddOne(event, id, type, e.recipe);
            if (!addRes.ok) {
                shanhaiOvrError(uid + ' SKIPPED op=add id=' + id + ' type=' + type
                    + ' -> ' + addRes.why);
                skipped++;
            } else {
                shanhaiOvrInfo(uid + ' APPLIED op=add id=' + id + ' type=' + type
                    + ' via=' + addRes.via + ' ' + addRes.note);
                applied++;
                // 🆕 2026-10-07：add 路的条件计数并进【同一个】汇总口径 ——
                //    末尾那行 `… cond_applied=… cond_failed=…` 现在同时含 op=set 与 op=add 的数。
                if (addRes.condApplied === true) {
                    condApplied++;
                } else if (addRes.condFailed === true) {
                    condFailed++;
                }
            }
            continue;
        }

        // ---------- set / remove / disable all need the target to exist ------
        if (cnt <= 0) {
            shanhaiOvrError(uid + ' MISSING op=' + op + ' id=' + id + ' type=' + type +
                            ' -> no such recipe id in the recipe manager');
            missing++;
            continue;
        }

        // ---------- 指纹版本闸（🔴 2026-10-05 新增）--------------------------
        // 改了指纹算法（v2 → v3.sorted）⇒ 老条目【一定】对不上新算法。
        // 那种情况下判 STALE 是【错的读数】：STALE 的含义是"配方被人动过"，
        // 而真实情况是"这条 entry 是旧版本写的、需要重新保存一次"。
        // ⇒ 单列一类 needs_resave，并且【逐条】把 id 与修复办法打出来（不许静默）。
        // ⚠️ 放在 add 分支之后：op=add 的条目本来就不用 base_fp。
        if (!shanhaiOvrIsCurrentFpVersion(wantFp)) {
            // 修复动作按 op 给，别让使用者对着"删除"的条目去找"保存"按钮
            var howTo = (op === 'remove' || op === 'disable')
                ? '在游戏里对这条配方再点一次「删除这条」（或跑 /shanhai edit remove ' + id + '）'
                : '在游戏里对这条配方点一次「保存这条」（或跑 /shanhai edit duration ' + id + ' <值>）';
            shanhaiOvrError(uid + ' NEEDS_RESAVE op=' + op + ' id=' + id + ' type=' + type
                + ' -> 这条 entry 的 base_fp 是旧版指纹 version=' + shanhaiOvrFpVersion(wantFp)
                + '，本版算法（' + shanhaiOvrFpPrefix() + '）无法直接核验 ⇒【本次不套用】。'
                + '修复：' + howTo + '；保存后指纹会写成新版。'
                + '（你的编辑没有丢：它仍在本文件里，只是需要一个"用新算法重新写一次"的动作）');
            needsResave++;
            continue;
        }

        // fingerprint of every matching recipe (should be exactly one)
        var targets = shanhaiOvrTargets(event, added, id);
        shanhaiOvrInfo(uid + ' lookup id=' + id + ' source='
            + ((viaAdded !== undefined) ? 'addedRecipes' : 'datapack') + ' targets=' + targets.length);
        var actualFp = null;
        var actualDump = '';
        var multi = 0;
        var fpFailed = false;
        try {
            for (var ti = 0; ti < targets.length; ti++) {
                multi++;
                if (actualFp === null && !fpFailed) {
                    actualFp = shanhaiOvrFingerprint(targets[ti]);
                    if (actualFp === null) {
                        fpFailed = true;
                    }
                    actualDump = String(targets[ti]);
                }
            }
        } catch (ex3) {
            shanhaiOvrError(uid + ' SKIPPED cannot fingerprint id=' + id + ': ' + ex3);
            skipped++;
            continue;
        }

        if (fpFailed) {
            // ⚠️ 与下面的 MISSING 分成两支：混在一起会把"KubeJS 绑定没挂上"读成"配方不见了"。
            shanhaiOvrError(uid + ' SKIPPED op=' + op + ' id=' + id +
                            ' -> 算不出本版指纹（KubeJS 绑定 ShanhaiFingerprint 不可用？见上面的 FINGERPRINT BINDING FAILED）');
            skipped++;
            continue;
        }

        if (actualFp === null) {
            shanhaiOvrError(uid + ' MISSING op=' + op + ' id=' + id +
                            ' -> countRecipes said ' + cnt + ' but forEachRecipe matched nothing');
            missing++;
            continue;
        }

        if (wantFp !== actualFp) {
            // 🔴 KubeJS 自己加的配方在这一相位 `.json` 是空壳（见 shanhaiOvrJsonIsStub 的取证）⇒
            //    指纹这条路对它算不出真值。改判据：id 逐字相等 + 来自 addedRecipes，并如实打一行 FP_STUB。
            if (viaAdded !== undefined && targets.length === 1 && shanhaiOvrJsonIsStub(targets[0])) {
                // 🔴 级别 = WARN（不是 ERROR）：这一条【已经套用】，只是"底本被人动过就不套用"
                //    那层保护在这条通道上用不出来。用户把它读成"重启不丢没做成"，是我们报错级别用错了。
                //    同一个 id 每局只打一行（原来每条 entry 都会重打，重存几次就刷屏）。
                SHANHAI_OVR_STUB_COUNT++;
                if (shanhaiOvrWarnOnce(id + '|FP_STUB')) {
                    shanhaiOvrWarn(uid + ' FP_STUB op=' + op + ' id=' + id + ' type=' + type
                        + ' -> 本条【已套用】，但走的是降级判据（id 逐字相等 + 来自 addedRecipes）：'
                        + 'KubeJS 加的配方在事件相位拿不到完整 json（实测只有 ' + actualFp + '），指纹算不出真值。'
                        + '代价：这条通道上失去了"底本被人动过就不套用"那层保护。'
                        + '（同一个 id 本局只报这一行；本局累计条数见末尾 fp_stub= 汇总）');
                } else {
                    SHANHAI_OVR_STUB_SUPPRESSED++;
                    shanhaiOvrInfo(uid + ' FP_STUB op=' + op + ' id=' + id
                        + ' -> 同上（本局已报过这个 id，按"每局每 id 只报一次"压掉）');
                }
            } else {
                shanhaiOvrError(uid + ' STALE op=' + op + ' id=' + id + ' type=' + type +
                                ' -> fingerprint mismatch, NOT applied');
                shanhaiOvrError(uid + '   expected base_fp=' + wantFp);
                shanhaiOvrError(uid + '   actual   base_fp=' + actualFp);
                shanhaiOvrError(uid + '   actual recipe now = ' + actualDump);
                stale++;
                continue;
            }
        }

        // ---------- apply ---------------------------------------------------
        try {
            if (op === 'set') {
                var fields = e.fields;
                if (!fields) {
                    shanhaiOvrError(uid + ' SKIPPED op=set id=' + id + ' -> no fields object');
                    skipped++;
                    continue;
                }
                // NOTE: fields is a KubeJS MapJS (Java-backed Scriptable), so
                // hasOwnProperty is NOT reliable here - enumerate directly.
                //
                // 🆕 2026-10-05（B 组）：conditions 这个键【不进】下面那个 recipe.set 循环 ——
                //    它不是 KubeJS 的 GT recipe schema 里的键（schema 里的叫 recipeConditions，
                //    而且那个键的形状是上游半坏的），硬 set 会抛 MissingComponentException。
                //    它走 ShanhaiConditions.apply（Java 侧，直接写 GT 认的平铺 JSON）。
                var names = [];
                for (var k in fields) {
                    if (k !== 'conditions') {
                        names.push(k);
                    }
                }
                var rawConds = shanhaiOvrConditionsOf(fields);
                if (names.length === 0 && rawConds === null) {
                    shanhaiOvrError(uid + ' SKIPPED op=set id=' + id + ' -> fields object is empty');
                    skipped++;
                    continue;
                }
                // 🆕 阶段 2：先把输入侧那几个键摘出来并整条校验（fail-closed）。
                //    🔴 不过 ⇒ 整条 entry 不套用（连产物/时间也不写）——
                //       半套用的形状会让那条配方在数据包里变成一条读不出来的坏配方。
                var shapePlan = shanhaiOvrPlanShape(fields, names);
                if (!shapePlan.ok) {
                    shanhaiOvrError(uid + ' SHAPE REJECTED op=set id=' + id + ' type=' + type
                        + ' -> ' + shapePlan.err
                        + '（整条 entry 不套用：宁可一条都不装，也不装一条原版读不出来的配方）');
                    skipped++;
                    continue;
                }
                if (shapePlan.shapeNames.length > 0) {
                    shanhaiOvrInfo(uid + ' shape_plan id=' + id + ' shape=[' + shapePlan.shapeNames.join(',')
                        + '] plain=[' + shapePlan.plainNames.join(',') + ']（形状字段走单独那一段）');
                }
                names = shapePlan.plainNames;
                // 🔴 校验在【写任何字段之前】做：不过就整条不套用（fail-closed，绝不半套用）
                var conds = null;
                if (rawConds !== null) {
                    var cv = shanhaiOvrValidateConditions(rawConds);
                    if (!cv.ok) {
                        shanhaiOvrError(uid + ' CONDITIONS REJECTED op=set id=' + id + ' type=' + type
                            + ' -> ' + cv.err
                            + '（整条 entry 不套用：宁可一条都不装，也不装一半 —— 半套用的条件会让机器'
                            + '按一个用户没写过的门槛去拦配方）');
                        skipped++;
                        continue;
                    }
                    conds = cv.arr;
                }
                var appliedNames = [];
                for (var ti2 = 0; ti2 < targets.length; ti2++) {
                    var recipe = targets[ti2];
                    for (var j = 0; j < names.length; j++) {
                        var was;
                        try { was = String(recipe.get(names[j])); }
                        catch (exGet) { was = '<unreadable:' + exGet + '>'; }
                        // 🔴🔴 2026-10-05 运行期取证（冒烟装置，原始读数在
                        //    temp\recipe-editor-forensics\runs\20261005-104557\ 里）：
                        //    JsonIO.read() 交给我们的值【不是】JsonObject —— 嵌套对象是
                        //    java.util.LinkedHashMap，而 CapabilityMapComponent.read 只认
                        //    com.google.gson.JsonObject，别的形状【不报错】、直接返回空表：
                        //      COERCE entries[0].fields.inputs=java.util.LinkedHashMap
                        //             => fields.inputs IS NOT a JsonObject
                        //      COERCE-READ-RAW  read(recipeJS, <原样>)      -> size=0
                        //      COERCE-SET-RAW   set("inputs", <原样>)       -> get("inputs").size=0
                        //      COERCE-READ-FIXED read(recipeJS, JsonIO.of(...)) -> size=1 itemArrLen=6
                        //      COERCE-SET-FIXED  set("inputs", JsonIO.of(...))  -> itemArrLen=6 (6/6 全保住)
                        //    ⇒ 不转一次 = 每一条写 inputs/outputs 的编辑都会【静默清空那条配方的输入】，
                        //      而日志上只有一行 APPLIED。这就是"静默毁数据"。
                        //    JsonIO.of(Object) 的字节码就是 `if (o instanceof Map) return MapJS.json(o)`
                        //    ⇒ 对 Map 一定产出真正的 JsonObject；对标量(duration)产出 JsonPrimitive，同样安全。
                        var value = JsonIO.of(fields[names[j]]);
                        recipe.set(names[j], value);
                        appliedNames.push(names[j] + ' was=' + was + ' now=' + JsonIO.toString(value));
                    }
                    // 🆕 阶段 2：输入侧那几个键（已在上面整条校验过，这里只负责写）。
                    //    ⚠️ 用 JsonIO.of 转一次的理由与上面那段通用循环完全相同
                    //       （JsonIO.read 给出来的嵌套对象是 java.util.LinkedHashMap，
                    //        直接交给 set 会被静默读成空 —— 那是"静默毁数据"级的坑）。
                    for (var sj = 0; sj < shapePlan.shapeNames.length; sj++) {
                        var sn = shapePlan.shapeNames[sj];
                        var sval = JsonIO.of(shapePlan.shapeVals[sn]);
                        recipe.set(sn, sval);
                        appliedNames.push('SHAPE:' + sn + '=' + JsonIO.toString(sval));
                    }
                    // 🆕 B 组：条件走 Java 绑定（见本文件顶部那一节）。放在 set 循环【之后】：
                    //    它要把 recipeConditions 那个键的 write 标志压掉，而 set 循环只动别的键。
                    if (conds !== null) {
                        var cerr = '';
                        try {
                            cerr = ShanhaiConditions.apply(recipe, conds);
                        } catch (eCond) {
                            cerr = 'CONDITIONS APPLY THREW: ' + eCond;
                        }
                        if (cerr === null || cerr === undefined || String(cerr).length === 0) {
                            condApplied++;
                            appliedNames.push('conditions n=' + conds.size());
                        } else {
                            condFailed++;
                            shanhaiOvrError(uid + ' CONDITIONS APPLY FAILED id=' + id
                                + ' -> ' + cerr
                                + '（注意：这条 entry 的其它字段【已经写了】，只有条件没装上 ——'
                                + ' 不是半套用，是"少了一样"。重新保存这条配方即可）');
                        }
                    }
                }
                shanhaiOvrInfo(uid + ' APPLIED op=set id=' + id + ' type=' + type +
                               ' fields[' + appliedNames.join(',') + ']');
                applied++;
            } else if (op === 'remove' || op === 'disable') {
                event.remove({ id: id });
                shanhaiOvrInfo(uid + ' APPLIED op=' + op + ' id=' + id + ' type=' + type);
                applied++;
            } else {
                shanhaiOvrError(uid + ' SKIPPED unknown op="' + op + '" id=' + id);
                skipped++;
            }
        } catch (ex4) {
            shanhaiOvrError(uid + ' SKIPPED op=' + op + ' id=' + id + ' -> apply threw: ' + ex4);
            skipped++;
        }
    }

    // 🆕 2026-10-05（P0）：收尾闸门 —— 见 shanhaiOvrSweepGtOrphans 的注释
    //    （放在汇总行【之前】，这样汇总里的 add_orphans 是这一局的最终读数）。
    shanhaiOvrSweepGtOrphans(event);

    var sum = applied + stale + missing + skipped + needsResave;
    shanhaiOvrInfo('overlay source=' + SHANHAI_OVR_PATH + ' schema=' + SHANHAI_OVR_SUPPORTED_SCHEMA +
                   ' entries=' + entries.length + ' applied=' + applied + ' stale=' + stale +
                   ' missing=' + missing + ' skipped=' + skipped + ' needs_resave=' + needsResave +
                   ' fp_stub=' + SHANHAI_OVR_STUB_COUNT
                   + ' fp_stub_suppressed=' + SHANHAI_OVR_STUB_SUPPRESSED
                   + ' cond_applied=' + condApplied + ' cond_failed=' + condFailed
                   + ' add_orphans=' + SHANHAI_OVR_ADD_ORPHANS);
    if (condApplied > 0 || condFailed > 0) {
        shanhaiOvrInfo('overlay_conditions_summary cond_applied=' + condApplied
            + ' cond_failed=' + condFailed
            + ' (逐条的读数在 APPLIED 行的 fields[...] 里，带 "conditions n=")');
    }
    if (SHANHAI_OVR_STUB_COUNT > 0) {
        // 汇总行本身只放 INFO —— 上面那条 WARN 已经把"降级判据"交代清楚了，
        // 再打一条 WARN 就等于同一个 id 报两次。这里只补一条"影响面"读数。
        shanhaiOvrWarn('本局有 ' + SHANHAI_OVR_STUB_COUNT + ' 条 entry 走了【降级判据】（id 逐字相等 + addedRecipes）'
            + '并已套用；其中 ' + SHANHAI_OVR_STUB_SUPPRESSED + ' 条因为"同一个 id 本局已报过一次"被压掉。'
            + '想看逐条 uid ⇒ 搜 "FP_STUB"（WARN 行带 uid）。'
            + '这不影响"重启不丢"：这些条目本来就打的是 APPLIED。');
    }
    if (needsResave > 0) {
        shanhaiOvrError('OVERLAY MIGRATION REQUIRED: ' + needsResave
            + ' 条 entry 是【旧版指纹】⇒ 本次一条都没套用。逐条 id 见上面的 NEEDS_RESAVE 行；'
            + '按每条给出的动作重做一次即可（set ⇒ 重新「保存这条」；remove ⇒ 重新「删除这条」），'
            + '重做后指纹写成 ' + shanhaiOvrFpPrefix() + '。'
            + '用户的编辑【没有被删掉】，它还在 config/shanhai/recipe_overrides.json 里。');
    }
    if (sum !== entries.length) {
        shanhaiOvrError('ACCOUNTING MISMATCH: entries=' + entries.length
            + ' but applied+stale+missing+skipped+needs_resave=' + sum);
    }
}

ServerEvents.recipes(function (event) {
    try {
        shanhaiOvrRun(event);
    } catch (err) {
        shanhaiOvrError('OVERLAY CRASHED (degraded, server keeps running): ' + err);
    }
});
