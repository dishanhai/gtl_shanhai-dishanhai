// =============================================================================
// gen_deconstruct.js -- 生成「原初物质解构」全量配方
//   产物: kubejs/server_scripts/[server_scripts]shanhai_deconstruct_recipes.js   （勿手改【生成部分】；文件末尾有手写区，生成器会原样保留）
//
//   口径（用户 2026-09-26 裁定）：
//     输入  1x 粉 或 1000mB 流体（形态各一条）
//     输出  各元素按其化学式原子数（系数 1）；有粉出粉、没粉出流体
//     token -> 元素 的匹配顺序：
//        ① 整 token 对【完整符号表】精确匹配（表 = materials.json 里 isElement=true 的材质）
//        ② 剥 "-质量数" 后缀再匹配（解决 U / Pu：gtceu:uranium 的 elementSymbol 是 "U-238"）
//        ③ 仍未命中 -> 计入未映射清单
//     🔴 "Au?" 视为【一个整体元素】-> gtceu:infused_gold_dust
//        用户原话：「Au？是一个完整的元素，它的id是gtceu:infused_gold_dust」
//        （尽管注册表里 gtceu:infused_gold 的 isElement=false —— 用户设计意图优先）
//     🔴 上标【质量数】不参与计数（U²³⁸ 算 U:1）。判据：只有式子里真的出现
//        <符号><上标数字> 且数字 > 100 时才收敛成 1，普通 "238x" 不受影响。
//
//   配方 id 规则（用户 2026-09-26 裁定：全带 namespace）：
//        'shanhai:deconstruct/' + <namespace>_<本地名> + '_dust' | '_fluid'
//        => gtceu:ruridit        -> shanhai:deconstruct/gtceu_ruridit_dust
//        => gtladditions:ruridit -> shanhai:deconstruct/gtladditions_ruridit_dust
//      （旧规则丢掉了 namespace，导致这两个撞成同一个 id —— 就是线上那 1 条 Duplicate）
//
//   已跳过 5 个有手写配方的材质（用户裁定：保留手写的）：
//        proto_halkonite / phonon_crystal_solution / star_gate_crystal_slurry
//        / phonon_medium / proto_halkonite_base
//
//   🔴 2026-09-30（本轮）新增跳过规则【自环】：输入物品 == 输出物品 ⇒ 不生成这条配方。
//        用户拍板（选择题）：「A. 删掉（推荐）」
//        判据与理由写在下面 isSelfLoop() 的注释块里；跳过条数【逐条】打在构建日志与产物注释里。
//        ⚠️ 流体形态【不参与】本判据 —— `1000mB <材质> → 1x <材质>_dust` 是"液体变回粉"，不是自环。
//
//   🔴 生成期自检：id 唯一性（Set 去重），不唯一就把重复组全部打印出来。
// =============================================================================
const fs=require('fs');
const path=require('path');
// ═══════════════════════════════════════════════════════════════════════════════
// 🔴 路径来源纪律（上传前清理）：本脚本用到的路径【全部在仓库内】⇒ 一律按【脚本自身位置】
//    (__dirname) 推；不写任何机器绝对路径，也不依赖"从哪个目录运行"（故不用 process.cwd()）。
// ═══════════════════════════════════════════════════════════════════════════════
const REPO=path.join(__dirname,'..','..');                    // kubejs\_generators → 仓库根
const TMP=path.join(REPO,'temp')+path.sep;
const EV=path.join(REPO,'originals','matdump','evidence')+path.sep;
const OUT=path.join(REPO,'kubejs','server_scripts','[server_scripts]shanhai_deconstruct_recipes.js');
const mats=JSON.parse(fs.readFileSync(EV+'materials.json','utf8'));
const pr=JSON.parse(fs.readFileSync(EV+'parse_results.json','utf8'));
const LANG=JSON.parse(fs.readFileSync(EV+'lang_zh_cn.json','utf8'));
const prById={}; pr.forEach(x=>prById[x.id]=x); const mById={}; mats.forEach(m=>mById[m.id]=m);
const cn=id=>LANG['material.gtceu.'+id.split(':')[1]]||LANG['material.'+id.replace(':','.')]||'(无名)';
const SYM={}; mats.forEach(m=>{ if(m.isElement && m.elementSymbol) SYM[m.elementSymbol]=m; });
const ISO=/^([A-Z][a-z]?)-(\d+)$/; const ISOFALL={};
for(const k in SYM){ const g=k.match(ISO); if(g && !ISOFALL[g[1]]) ISOFALL[g[1]]=SYM[k]; }
// 🔴 Au? 的【规范归属】= infused_gold（用户裁定）；各材质自己式子就是 Au? 时会在下面覆盖成自己的粉
SYM['Au?'] = mats.find(x=>x.id==='gtceu:infused_gold');
// 🔴 7 组同符号冲突（T / M / §9Sl / §ketime / §kestar_matter / §6§kestar_matter / §ke§r(u₂);…）
//    SYM 只能留一个 => 靠下面「自己的式子就是自己 => 出自己的粉」统一兜住
const SUPS='\u2070\u00b9\u00b2\u00b3\u2074\u2075\u2076\u2077\u2078\u2079';
const supNum=s=>s.split('').map(c=>String(SUPS.indexOf(c))).join('');
function massFix(m){ const fix={}; const re=new RegExp('([A-Z][a-z]?)(['+SUPS+']{2,})','g'); let mm;
  while((mm=re.exec(String(m.formula||'')))){ if(parseInt(supNum(mm[2]),10)>100) fix[mm[1]]=1; } return fix; }
const SKIP5={'gtladditions:proto_halkonite':1,'gtladditions:phonon_crystal_solution':1,
  'gtladditions:star_gate_crystal_slurry':1,'gtladditions:phonon_medium':1,'gtladditions:proto_halkonite_base':1};
// 🔴 用户 2026-09-26 裁定：式子【含 ?】的材质不生成配方（用户 2026-09-26 扩展裁定）—— 但先把 Au? 整体剔掉，因为它不是 instability（那个 ? 更像"未知成分"，不是元素）
//    但【instability 自己】保留 —— 它的式子就是 ?，而它是真元素
var QDROP={}; mats.forEach(m=>{ if(String(m.formula).split('Au?').join('').indexOf('?')>=0 && m.id!=='gtceu:instability') QDROP[m.id]=1; });
var QDROPPED=[];
function build(id){
  const m=mById[id], p=prById[id]; if(!m||!p) return null;
  const cnt=Object.assign({},p.counts||{});
  const mf=massFix(m); for(const s in mf) if(cnt[s]!==undefined) cnt[s]=1;
  const items=[],fluids=[],drop=[];
  // 🔴 Au? 是【两字符拼出来的一个符号】：配对回去，别让它被拆成 Au + ?
  //    自己式子就是 Au? 的材质(infused_gold/thaumium) => 出自己的粉（上面的自我优先规则会接管）
  //    其它材质(astral_silver 的 Ag₂Au?) => 归到规范材质 infused_gold
  if(String(m.formula).indexOf('Au?')>=0 && cnt['?']>0){
    const nq=cnt['?'], na=cnt['Au']||0, take=Math.min(nq,na);
    cnt['Au']=na-take; cnt['?']=nq-take;
    if(!cnt['Au']) delete cnt['Au'];
    if(!cnt['?']) delete cnt['?'];
    cnt['\u0000AUQ']=take;
  }
  for(const raw of Object.keys(cnt)){
    if(raw==='\u0000AUQ'){ const self2=(String(m.formula)==='Au?')?m:mats.find(x=>x.id==='gtceu:infused_gold'); items.push(cnt['\u0000AUQ']+'x '+self2.id+'_dust'); continue; }
    let t=SYM[raw]?{m:SYM[raw],f:ISO.test(raw)}:(ISOFALL[raw]?{m:ISOFALL[raw],f:false}:null);
    // 🔴 统一规则：本材质的化学式【就是】这个 token => 出它自己的粉（解决 Au?/T/M/§9Sl… 等同符号多材质）
    if(t && String(m.formula)===raw && t.m.id !== m.id){ t={m:m, f:false}; }
    if(!t){ drop.push(raw); continue; }
    const n=t.f?1:cnt[raw];
    if(t.m.dustItem) items.push(n+'x '+t.m.id+'_dust');
    else if(t.m.propFluid) fluids.push(t.m.id + ' ' + (n * 1000));   // 🔴 单位是 mB：输入 1000mB = 1 个化学式单位 => 每个原子 = 1000 mB
    else drop.push(raw);
  }
  return {items,fluids,drop};
}
// =============================================================================
// 🔴 流体 id 修正：从【运行期实测的证据文件】读取（用户 2026-09-26 裁定）。
//    originals/matdump/evidence/fluid_ids.json  ← 专服探针实测产物，可复用，不用再跑探针。
//    全表 958 个 propFluid=true：944 个的 <材质id> 就是真流体；14 个不是（查出来是 minecraft:empty）。
//    fluidFix  = 改用它给的真流体 id   fluidDrop = 找不到真流体 => 不生成流体配方
//    ⚠️ 不能用「有 liquid_ 兄弟就当假」那条规则：hydrogen/air/nether_air/ender_air/starlight
//       都有 liquid_ 兄弟，但气体与液体是两个真流体，那条规则会误删 5 个。
var FLUID_EV = JSON.parse(fs.readFileSync(EV+'fluid_ids.json','utf8'));
var FLUID_MAP = FLUID_EV.fluidFix;
var DROP_FLUID = FLUID_EV.fluidDrop;
var FLUID_DROPPED = [];
const mkId=(id,k)=>'shanhai:deconstruct/'+id.replace(':','_')+'_'+k;   // 🔴 全带 namespace
const jobs=[],empties=[],allIds=[],toks={};
// ═══════════════════════════════════════════════════════════════════════════════
// 🔴 2026-09-30（本轮）自环判据：**输入物品 == 输出物品 ⇒ 不生成这条配方**
//   用户拍板（选择题）：**「A. 删掉（推荐）」**
//
//   ── 判据（isSelfLoop()，【五条全要满足】才算自环；任一不满足 ⇒ 照旧生成）──
//     ① 这条作业的输入是【物品】形态（f.inItem != null）
//     ② 输出物品【恰好只有一项】（o.items.length === 1）
//     ③ 那一项的【物品 id】== 输入物品的 id
//     ④ 那一项的【数量】  == 输入物品的数量
//     ⑤ 没有流体输出（o.fluids.length === 0）
//
//   ── 判据为什么这么定（逐条给理由，含"为什么不用更宽的/更窄的"）──
//     · 满足①②③④⑤ = 「恒等配方」：放进去什么就出来什么。解构机白跑 200t / 32EUt，
//       对玩家零信息量，却会在 JEI 里占一条、并且和别的真配方抢同一个输入格 ⇒ 该删。
//     · 为什么【不】用「输出清单里【包含】输入就删」这条更宽的判据：
//         那种写法会把 `1x A → 1x A + 1x B` 也删掉，而**它仍然产出了 B**，是有用的配方。
//       测算：宽判据在本产物上多出来的条数 = **0**（两种判据今天结果完全相同），
//         所以今天选哪个数字都一样；但失败模式不同 —— 窄判据只会"漏删一条没用的"，
//         宽判据会"删掉一条有用的" ⇒ **取窄的那个**。
//     · 为什么【要】加④数量相同：`1x A → 2x A` 不是恒等，是【复制器】—— 那是另一类问题
//         （是设计意图就不该删；不是设计意图就是另一个 bug），本判据不越界替用户决定。
//       实测本轮 177 条【全部】是 `1x → 1x`，所以④在现有产物上**未被触发过**
//         （= 它只是"按定义不会删复制器"，并没有真的拦下过复制器的证据）。
//     · 为什么【要】加⑤无流体输出：`1x A → 1x A + 1000mB X` 虽然输出者也是自己，
//         但它多了流体产出 ⇒ 不是恒等 ⇒ 不该由本判据删。
//     · 🔴 流体形态【永不参与】本判据（由①保证）：`gtceu:actinium 1000`（**流体**）
//         → `1x gtceu:actinium_dust`（**粉**）的输入与输出是**两个不同的东西**
//         （1000mB 液体 → 1 个粉），这是有意义的"液体变回粉"⇒ 一条都不许删。
//         本轮实测这类共 FLUID_SELF_KEEP 条，逐条计数并打进证据行。
// ═══════════════════════════════════════════════════════════════════════════════
const SELFLOOP = [];            // 被跳过的逐条证据
const FLUID_KEEP = [];          // 流体形态 → 它自己的粉（必须保留的），逐条留证
const parseItemStr = s => { const g = /^(\d+)x\s+(.+)$/.exec(String(s)); return { n: g ? parseInt(g[1], 10) : 1, id: (g ? g[2] : String(s)).trim() }; };
const showCn = mid => { const t = cn(mid); return (t && t !== '(无名)') ? t : mid; };
// 物品 id（如 gtceu:coal_dust）-> 材质中文名（煤炭）；查不到就回落成裸名
const cnOfItem = itemId => { const p = String(itemId).split(':')[1] || String(itemId); const bare = p.replace(/_(dust|fluid)$/, ''); const t = cn('x:' + bare); return (t && t !== '(无名)') ? t : bare; };
function isSelfLoop(f, o) {                     // 返回 null = 不是自环（照旧生成）
  if (!f.inItem) return null;                   // ① 只看【物品】输入形态；流体形态直接放行
  if (o.items.length !== 1) return null;        // ② 输出物品恰好一项
  if (o.fluids.length !== 0) return null;       // ⑤ 无流体输出
  const ii = parseItemStr(f.inItem), oo = parseItemStr(o.items[0]);
  if (oo.id !== ii.id) return null;             // ③ 物品 id 相同
  if (oo.n !== ii.n) return null;               // ④ 数量相同
  return { inId: ii.id, inItem: f.inItem, outItem: o.items[0] };
}
function isFluidToOwnDust(f, o) {               // 流体 → 它自己的粉（判据外，专用来出"保留证据"）
  if (!f.inFluid) return false;
  if (o.items.length !== 1 || o.fluids.length !== 0) return false;
  const fid = String(f.inFluid).split(/\s+/)[0];
  return parseItemStr(o.items[0]).id === fid + '_dust';
}
for(const m of mats){
  if(!m.formula||!m.formula.length) continue;
  const o=build(m.id); if(!o) continue;
  o.drop.forEach(d=>{ if(SKIP5[m.id]) return; if(!toks[d]) toks[d]={n:0,ids:[]}; toks[d].n++; if(toks[d].ids.length<4) toks[d].ids.push(m.id); });
  if(!o.items.length&&!o.fluids.length){ empties.push(m.id); continue; }
  if(QDROP[m.id]){ QDROPPED.push(m.id); continue; }
  if(SKIP5[m.id]) continue;
  const forms=[];
  if(m.dustItem)  forms.push({k:'dust', inItem:'1x '+m.id+'_dust', inFluid:null});
  if(m.propFluid){
    var _fid = FLUID_MAP[m.id] || m.id;
    if(DROP_FLUID[m.id]){ if(!SKIP5[m.id]){ FLUID_DROPPED.push(m.id); } }
    else { forms.push({k:'fluid', inItem:null, inFluid:_fid+' 1000'}); }
  }
  for(const f of forms){
    // 🔴 自环【不在这里判】—— 判据必须落在【覆盖表 OV 应用之后】的那一份数据上！
    //    理由（本轮实测碰到的坑）：`gtceu:coal_dust` / `gtceu:graphite_dust` 这两条的
    //    build() 原始产出就是它自己的粉（看似自环），但覆盖表把它们改成了
    //    `1x 煤炭粉 → 2x 碳粉`、`1x 石墨粉 → 4x 碳粉` —— 是两条**真配方**。
    //    若在 push 这一步就按原始产出判自环，这两条会被**静默删掉**。
    //    ⇒ 所以这里只留证（流体→自己粉），过滤放到下面 OV 段之后。
    if(isFluidToOwnDust(f, o)) FLUID_KEEP.push({rid:mkId(m.id,f.k), cn:showCn(m.id), inFluid:f.inFluid, outItem:o.items[0]});
    const rid=mkId(m.id,f.k); allIds.push(rid);
    jobs.push({id:rid,inItem:f.inItem,inFluid:f.inFluid,items:o.items,fluids:o.fluids}); }
}
// ═══════════════════════════════════════════════════════════════════════════════
// 🔴 覆盖表（矿物粉全链产率定值）解析 + **自环过滤** —— 顺序不能颠倒
//    先把 OV 应用上，得到"真正会写进产物的那一条"，**再**判自环。
//    数据源 kubejs/_generators/data/ore_yield_overrides.json 的 overrides 表：
//         recipeId -> { inItem:'Nx <dust>', outItems:[...], outFluids:[...], cn, src, ev }
//    只覆盖【通过闸门】的那些；未通过的一律保持原值（绝不默默写怪数）。
//    🔴 2026-09-30（本轮口径，生成方 = temp/pmd-ore2/build-decision.mjs）：
//      · G3 = 「必须精确整数配比」；N ≤ 64 优先，找不到就用【精确 N，不设上限】（删掉了旧的 N≤64 闸门）
//      · 深度化学扭曲仪（gtceu:distort）里若有【直接吃该矿粉且直接产元素】的配方 ⇒ 抄它的配比
//        （仅当其 N 严格更小；用户口径：「参考那里的输入和输出配比，而且那里的更便宜」）
//      · G4 保留，但旧值里【等于该材质自己的粉】的项（自环伪元素）不计入"组成元素"
const OV_DATA = path.join(REPO, 'kubejs', '_generators', 'data', 'ore_yield_overrides.json')
if (!fs.existsSync(OV_DATA)) throw new Error('缺少矿物粉产率覆盖表：' + OV_DATA + ' —— 先跑 node temp\\pmd-ore2\\build-decision.mjs')
const OVFILE = JSON.parse(fs.readFileSync(OV_DATA, 'utf8'))
const OV_AUTO = OVFILE.overrides || {}
// ═══════════════════════════════════════════════════════════════════════════════
// 🔴 用户指定覆盖 —— 【生成器自己的专用表】（2026-10-04 本轮新增）
//   语义：用户【点名指定】的覆盖，**不来自成分推算**（既不是"全链产率"、也不是"扭曲仪配比"）；
//         数值来自用户自己写的口径 ⇒ **不许被自动生成覆盖**。
//   为什么必须放【生成器里】，而不能放上面那张自动表里：
//     ore_yield_overrides.json 由 temp/pmd-ore2/build-decision.mjs 用 writeFileSync
//     **整体重写**（全量覆盖、不合并磁盘现值）⇒ 放在那张表里的用户条目会在下次重跑时
//     **被静默丢弃**（输入退回 1×、产出退回化学式口径），而且丢得没有任何报错。
//   优先级：**本表赢** —— 合并时若自动表也出现同名键，一律以本表为准（用户指定 > 自动推算）。
//   顺序：合并结果仍按 **id 升序** 摆放（与自动表"严格升序"的约定一致）⇒
//         产物的 OV_EV 段顺序与并入前逐字节相同（这就是"搬家不改结果"）。
// ═══════════════════════════════════════════════════════════════════════════════
const USER_SPECIFIED_OVERRIDES = {
  'shanhai:deconstruct/gtceu_uraninite_dust': {
    inItem: '10x gtceu:uraninite_dust',
    outItems: ['1x gtceu:uranium_235_dust', '9x gtceu:uranium_dust'],
    outFluids: ['gtceu:oxygen 20000'],
    cn: '晶质铀矿',
    src: '用户指定',
    ev: '晶质铀矿粉 输入=10x gtceu:uraninite_dust 产出=铀-235粉×1、铀粉×9、氧 20000mB | 来源=用户指定（用户在游戏里手写的 AE 处理样板，样板纸名「原初物质解构配方修改」） | 每1粉=铀-235粉 0.1、铀粉 0.9、氧 2000',
  },
}
// 合并：自动表 + 用户指定表；用户指定优先；结果保持 id 升序
function mergeOverrides(auto, user) {
  const out = {}
  const pending = Object.keys(user).sort()
  for (const k of Object.keys(auto)) {
    while (pending.length && pending[0] < k) { const uk = pending.shift(); out[uk] = user[uk] }
    const pi = pending.indexOf(k)
    if (pi >= 0) { pending.splice(pi, 1); out[k] = user[k] } else { out[k] = auto[k] }
  }
  while (pending.length) { const uk = pending.shift(); out[uk] = user[uk] }
  return out
}
const OV = mergeOverrides(OV_AUTO, USER_SPECIFIED_OVERRIDES)
{
  const uks = Object.keys(USER_SPECIFIED_OVERRIDES)
  console.log('=== 🔴 用户指定覆盖（生成器专用表；不受 ore_yield_overrides.json 重写影响）===')
  console.log('  条数 = ' + uks.length + ' : ' + JSON.stringify(uks))
  for (const k of uks) {
    if (Object.prototype.hasOwnProperty.call(OV_AUTO, k)) console.log('  ⚠️ 自动表里也出现了同名键，已按【用户指定优先】覆盖：' + k)
    const uu = USER_SPECIFIED_OVERRIDES[k]
    console.log('  [SHANHAI-USEROV] ' + (uu.cn || '') + '\t' + k + '\t' + uu.inItem + ' -> ' + (uu.outItems || []).concat(uu.outFluids || []).join(' , '))
  }
  console.log('')
}
let ovApplied = 0
const RESCUED = []          // 🔴 原始产出像自环、但被覆盖表救成真配方的（必须留证，否则没人知道差点删了什么）
{
  const keep = []
  for (const j of jobs) {
    const rawSl = isSelfLoop(j, j)                       // 覆盖【前】的原始产出是不是恒等
    let useJ = j, fromOv = false
    if (Object.prototype.hasOwnProperty.call(OV, j.id)) {
      const o = OV[j.id]
      useJ = { id: j.id, inItem: o.inItem, inFluid: null, items: o.outItems, fluids: o.outFluids }
      fromOv = true; ovApplied++
    }
    // 🔴 自环判据（五条见上面 isSelfLoop 注释块）——判的是【覆盖表之后】的 useJ
    const sl = isSelfLoop(useJ, useJ)
    if (rawSl && fromOv && !sl) {
      RESCUED.push({ rid: j.id, cn: cnOfItem(rawSl.inId), rawIn: rawSl.inItem, rawOut: rawSl.outItem,
        nowIn: String(useJ.inItem), nowOut: (useJ.items || []).concat(useJ.fluids || []).join(' , ') })
    }
    if (sl) { SELFLOOP.push({ rid: useJ.id, cn: cnOfItem(sl.inId), inId: sl.inId, inItem: sl.inItem, outItem: sl.outItem, fromOv }); continue }
    keep.push(useJ)
  }
  jobs.length = 0; keep.forEach(j => jobs.push(j))
}
allIds.length = 0; jobs.forEach(j => allIds.push(j.id));   // id 自检只统计【真正会写出去】的
// ---------- 🔴 生成期自检：id 唯一性 ----------
const cntId={}; allIds.forEach(i=>cntId[i]=(cntId[i]||0)+1);
const dupIds=Object.keys(cntId).filter(i=>cntId[i]>1);
console.log('=== 生成期自检：id 唯一性 ===');
console.log('  id 条数        = '+allIds.length);
console.log('  唯一 id 数(Set)= '+new Set(allIds).size);
console.log('  重复组数       = '+dupIds.length+(dupIds.length?'  ->  '+dupIds.map(i=>i+' x'+cntId[i]).join(' , '):'  ✅'));
if(dupIds.length){ console.log('  !! 有重复 id，产物仍然会写，但请在部署前修掉'); }
console.log('');
console.log('=== 其他统计 ===');
console.log('  生成条数 = '+jobs.length+'   输出为空 = '+empties.length);
console.log('  max itemOut = '+jobs.reduce((a,j)=>Math.max(a,j.items.length),0)+'   max fluidOut = '+jobs.reduce((a,j)=>Math.max(a,j.fluids.length),0));
const keys=Object.keys(toks);
console.log('  未映射 token（已排除被跳过的材质） = '+keys.length+' 种 / '+keys.reduce((a,k)=>a+toks[k].n,0)+' 次  '+JSON.stringify(keys));
fs.writeFileSync(path.join(TMP, 'evidence', 'unmapped-tokens.txt'),
  ['token\t出现次数\t涉及材质(中文名)'].concat(keys.map(k=>[k,toks[k].n,toks[k].ids.map(i=>i+'('+cn(i)+')').join(' ')].join('\t'))).join('\n')+'\n','utf8');
fs.writeFileSync(path.join(TMP, 'evidence', 'empty-output-materials.txt'),
  empties.map(i=>i+'\t'+cn(i)+'\t'+(mById[i]?mById[i].formula:'')).join('\n')+'\n','utf8');
console.log('  空输出清单 -> _evidence\\empty-output-materials.txt ('+empties.length+' 行)');
console.log('  🔴 因【找不到真流体】而不生成流体配方的材质 = '+FLUID_DROPPED.length+' 条: '+JSON.stringify(FLUID_DROPPED));
console.log('  🔴 因【式子是 ?】而不生成配方的材质 = '+QDROPPED.length+' 条: '+JSON.stringify(QDROPPED));
console.log('');
// ═══════════════════════════════════════════════════════════════════════════════
// 🔴 2026-09-30（本轮）自环判据的证据行 —— 全部以 [SHANHAI-SELFLOOP] 开头，可 grep
// ═══════════════════════════════════════════════════════════════════════════════
console.log('=== 🔴 自环判据：输入物品 == 输出物品 ⇒ 不生成 ===');
console.log('  [SHANHAI-SELFLOOP] 跳过 ' + SELFLOOP.length + ' 条：'
  + (SELFLOOP.length ? SELFLOOP.map(s => s.cn + '粉').join('、') : '（无）'));
SELFLOOP.forEach(s => console.log('  [SHANHAI-SELFLOOP-LIST] ' + s.cn + '粉\t' + s.rid + '\t' + s.inItem + ' -> ' + s.outItem));
console.log('  [SHANHAI-SELFLOOP-KEEP] 流体形态（1000mB <材质> → 1x <材质>_dust）保留 ' + FLUID_KEEP.length
  + ' 条 —— 它【不是自环】（流体与物品是两个东西），一条都没删');
FLUID_KEEP.slice(0, 3).forEach(s => console.log('  [SHANHAI-SELFLOOP-KEEP-LIST] ' + s.cn + '\t' + s.rid + '\t' + s.inFluid + ' -> ' + s.outItem));
console.log('  [SHANHAI-SELFLOOP-COUNT] before=' + (jobs.length + SELFLOOP.length) + ' skipped=' + SELFLOOP.length + ' after=' + jobs.length + ' (JOBS 段)');
console.log('  [SHANHAI-SELFLOOP-RESCUED] 原始产出像自环、但被覆盖表救成【真配方】而保留的 = ' + RESCUED.length + ' 条'
  + (RESCUED.length ? '：' + RESCUED.map(r => r.cn + '(' + r.rawIn + ' -> ' + r.rawOut + '  ⇒ 覆盖后 ' + r.nowIn + ' -> ' + r.nowOut + ')').join('；') : ''));
RESCUED.forEach(r => console.log('  [SHANHAI-SELFLOOP-RESCUED-LIST] ' + r.cn + '\t' + r.rid + '\t原始=' + r.rawIn + ' -> ' + r.rawOut + '\t覆盖后=' + r.nowIn + ' -> ' + r.nowOut));
try {
  fs.writeFileSync(path.join(TMP, 'selfloop', 'selfloop-skipped.tsv'),
    ['配方id\t中文名\t输入\t输出'].concat(SELFLOOP.map(s => [s.rid, s.cn + '粉', s.inItem, s.outItem].join('\t'))).join('\n') + '\n', 'utf8');
  fs.writeFileSync(path.join(TMP, 'selfloop', 'fluid-kept.tsv'),
    ['配方id\t中文名\t输入(流体)\t输出'].concat(FLUID_KEEP.map(s => [s.rid, s.cn + '粉', s.inFluid, s.outItem].join('\t'))).join('\n') + '\n', 'utf8');
  console.log('  [SHANHAI-SELFLOOP] 逐条证据 -> temp\\selfloop\\selfloop-skipped.tsv (' + SELFLOOP.length + ' 行) / fluid-kept.tsv (' + FLUID_KEEP.length + ' 行)');
} catch (eW) { console.log('  [SHANHAI-SELFLOOP] 证据文件写入失败（不影响产物）：' + eW); }
console.log('');
console.log('=== 举例（namespace 进 id）===');
['gtceu:ruridit','gtladditions:ruridit','gtladditions:liquid_ruridit'].forEach(id=>{
  const m=mById[id]; const f=[]; if(m.dustItem)f.push('dust'); if(m.propFluid)f.push('fluid');
  console.log('   '+id.padEnd(32)+' -> '+f.map(k=>mkId(id,k)).join('  ,  ')); });
// ---------- 写产物 ----------
const L=[],w=s=>L.push(s===undefined?'':s);
w('// priority: 1');
w('// [server_scripts]shanhai_deconstruct_recipes.js -- 「原初物质解构」全量配方（生成部分勿手改；文件末尾手写区由生成器原样保留）｜上限 (1,103,1,16)');
w('//   生成器 kubejs/_generators/gen_deconstruct.js');
w('//   输入 1x 粉 / 1000mB 流体；输出 = 各元素按化学式原子数（系数1），有粉出粉、没粉出流体');
w('//   token 匹配顺序：① 完整符号表精确匹配 ② 剥 "-质量数" 后缀再匹配');
w('//   🔴 用户 2026-09-26：化学式里的 "Au?" 视为【一个整体元素】-> gtceu:infused_gold_dust');
w('//      （尽管注册表 gtceu:infused_gold 的 isElement=false；用户设计意图优先）');
w('//   🔴 用户 2026-09-26：上标【质量数】不参与计数（U²³⁸ 算 U:1，不是 U:238）');
w('//   🔴 用户 2026-09-26：配方 id 全带 namespace（旧规则丢 namespace，导致 ruridit 撞车）');
w('//   已跳过 5 个有手写配方的材质；注册前读 global.SD_EXTRA（表不存在则 {}，不报错）');
w('//   🔴 2026-09-30（本轮）自环判据：输入物品 == 输出物品 ⇒ 不生成（用户拍板「A. 删掉」）');
w('//      判据：①物品输入 ②输出物品恰好一项 ③其 id == 输入 id ④其数量 == 输入数量 ⑤无流体输出');
w('//      ⚠️ 流体形态（1000mB <材质> → 1x <材质>_dust）不参与本判据 ⇒ 保留');
w('//   🔴 2026-09-30 新增批：矿物粉（集成矿石处理厂产物）→ 元素单质 —— 见下面 ORE_JOBS 段');
w('//      数据源 kubejs/_generators/data/ore_deconstruct_jobs.json；只加现有批次没覆盖的矿物粉 ⇒ 零冲突');
w('//   🔴 2026-09-30（本轮）口径：G3 = 必须精确整数配比（N ≤ 64 优先，否则用【精确 N，不设上限】）');
w('//      深度化学扭曲仪（gtceu:distort）配比优先（更省时以它为准）；覆盖条目的证据行见 OV_EV 段，可 grep');
w('ServerEvents.recipes(function (event) {');
w('    var gtr = event.recipes.gtceu');
w('    // 🔴 2026-09-27 接进配方统计（用户：「以后配方添加之后都检查一下」）')
w('    //    本脚本【不调 reset()】—— 它跑在三个脚本的最前，累加器本来就是 0；')
w('    //    而且不 reset 更安全：万一以后顺序变了，也不会把别人已经报过的数清零。')
w('    //    ⚠️ 变量名用 ShanhaiStats：Rhino 里 `Stats` 会回落到原版 net.minecraft.stats.Stats。')
w('    var ShanhaiStats = null')
w('    try { ShanhaiStats = Java.loadClass(\'com.shanhai.common.recipe.ShanhaiRecipeStats\') } catch (eS) { ShanhaiStats = null }')
w('    var T = \'primordial_matter_deconstruction\'');
w('    var EXTRA = (typeof SD_EXTRA !== \'undefined\' && SD_EXTRA) ? SD_EXTRA : {}');
  w('    // 🔴 元素符号 -> 材质 id（生成期固化，供 set 覆盖输出时定位）');
  w('    var SYM2MAT = {"Ac":"gtceu:actinium","Al":"gtceu:aluminium","Am":"gtceu:americium","Sb":"gtceu:antimony","Ar":"gtceu:argon","As":"gtceu:arsenic","At":"gtceu:astatine","Ba":"gtceu:barium","Bk":"gtceu:berkelium","Be":"gtceu:beryllium","Bi":"gtceu:bismuth","Bh":"gtceu:bohrium","B":"gtceu:boron","Br":"gtceu:bromine","Cs":"gtceu:caesium","Ca":"gtceu:calcium","Cf":"gtceu:californium","C":"gtceu:carbon","Cd":"gtceu:cadmium","Ce":"gtceu:cerium","Cl":"gtceu:chlorine","Cr":"gtceu:chromium","Co":"gtceu:cobalt","Cn":"gtceu:copernicium","Cu":"gtceu:copper","Cm":"gtceu:curium","Ds":"gtceu:darmstadtium","D":"gtceu:deuterium","Db":"gtceu:dubnium","Dy":"gtceu:dysprosium","Es":"gtceu:einsteinium","Er":"gtceu:erbium","Eu":"gtceu:europium","Fm":"gtceu:fermium","Fl":"gtceu:flerovium","F":"gtceu:fluorine","Fr":"gtceu:francium","Gd":"gtceu:gadolinium","Ga":"gtceu:gallium","Ge":"gtceu:germanium","Au":"gtceu:gold","Hf":"gtceu:hafnium","Hs":"gtceu:hassium","Ho":"gtceu:holmium","H":"gtceu:hydrogen","He":"gtceu:helium","He-3":"gtceu:helium_3","In":"gtceu:indium","I":"gtceu:iodine","Ir":"gtceu:iridium","Fe":"gtceu:iron","Kr":"gtceu:krypton","La":"gtceu:lanthanum","Lr":"gtceu:lawrencium","Pb":"gtceu:lead","Li":"gtceu:lithium","Lv":"gtceu:livermorium","Lu":"gtceu:lutetium","Mg":"gtceu:magnesium","Md":"gtceu:mendelevium","Mn":"gtceu:manganese","Mt":"gtceu:meitnerium","Hg":"gtceu:mercury","Mo":"gtceu:molybdenum","Mc":"gtceu:moscovium","Nd":"gtceu:neodymium","Ne":"gtceu:neon","Np":"gtceu:neptunium","Ni":"gtceu:nickel","Nh":"gtceu:nihonium","Nb":"gtceu:niobium","N":"gtceu:nitrogen","No":"gtceu:nobelium","Og":"gtceu:oganesson","Os":"gtceu:osmium","O":"gtceu:oxygen","Pd":"gtceu:palladium","P":"gtceu:phosphorus","Po":"gtceu:polonium","Pt":"gtceu:platinum","Pu-239":"gtceu:plutonium","Pu-241":"gtceu:plutonium_241","K":"gtceu:potassium","Pr":"gtceu:praseodymium","Pm":"gtceu:promethium","Pa":"gtceu:protactinium","Rn":"gtceu:radon","Ra":"gtceu:radium","Re":"gtceu:rhenium","Rh":"gtceu:rhodium","Rg":"gtceu:roentgenium","Rb":"gtceu:rubidium","Ru":"gtceu:ruthenium","Rf":"gtceu:rutherfordium","Sm":"gtceu:samarium","Sc":"gtceu:scandium","Sg":"gtceu:seaborgium","Se":"gtceu:selenium","Si":"gtceu:silicon","Ag":"gtceu:silver","Na":"gtceu:sodium","Sr":"gtceu:strontium","S":"gtceu:sulfur","Ta":"gtceu:tantalum","Tc":"gtceu:technetium","Te":"gtceu:tellurium","Ts":"gtceu:tennessine","Tb":"gtceu:terbium","Th":"gtceu:thorium","Tl":"gtceu:thallium","Tm":"gtceu:thulium","Sn":"gtceu:tin","Ti":"gtceu:titanium","T":"gtceu:tear","W":"gtceu:tungsten","U-238":"gtceu:uranium","U-235":"gtceu:uranium_235","V":"gtceu:vanadium","Xe":"gtceu:xenon","Yb":"gtceu:ytterbium","Y":"gtceu:yttrium","Zn":"gtceu:zinc","Zr":"gtceu:zirconium","Nq":"gtceu:naquadah","Nq+":"gtceu:enriched_naquadah","*Nq*":"gtceu:naquadria","Nt":"gtceu:neutronium","Tr":"gtceu:tritanium","Dr":"gtceu:duranium","Ke":"gtceu:trinium","An":"gtceu:adamantium","Qt":"gtceu:quantanium","Vi":"gtceu:vibranium","Dc":"gtceu:draconium","§8§kchaos":"gtceu:chaos","Hy⚶":"gtceu:hypogen","Sh⏧":"gtceu:shirabon","Mi":"gtceu:mithril","Tn":"gtceu:taranium","§b§ke§r§b✧§ke":"gtceu:crystalmatrix","Cnt":"gtceu:cosmicneutronium","Ec":"gtceu:echoite","Le":"gtceu:legendarium","✵Dc✵":"gtceu:draconiumawakened","Ad":"gtceu:adamantine","St":"gtceu:starmetal","Or":"gtceu:orichalcum","If":"gtceu:infuscolium","En":"gtceu:enderium","Et❃":"gtceu:eternity","M⎋":"gtceu:magmatter","§bRe":"gtceu:degenerate_rhenium","§b§ke§r§b(u₂);d§ke":"gtceu:heavy_quark_degenerate_matter","§b§ke§r§b(u₂);d(c₂);s(t₂);bg§ke":"gtceu:quantumchromodynamically_confined_matter","§kmetal":"gtceu:transcendentmetal","Ur":"gtceu:uruium","§6§kestar_matter":"gtceu:raw_star_matter","§kestar_matter":"gtceu:black_dwarf_mtter","✧◇✧":"gtceu:astraltitanium","✦◆✦":"gtceu:celestialtungsten","M":"gtceu:attuned_tengam","Yb¹⁷⁸":"gtceu:ytterbium_178","§5§kemana":"gtceu:mana","§ke§re§ke":"gtceu:free_electron_gas","§ke§rα§ke":"gtceu:free_alpha_gas","§ke§rp§ke":"gtceu:free_proton_gas","§ke§r(u2);d(c2);s(t2);bg§ke":"gtceu:quark_gluon","§ke§r(u₂);d§ke":"gtceu:heavy_quarks","§ke§r(c₂);(t₂);b§ke":"gtceu:light_quarks","§ke§rg§ke":"gtceu:gluons","Ti⁵⁰":"gtceu:titanium_50","§ke§r(t₂);u§ke":"gtceu:heavy_lepton_mixture","§ke§r(u₂);d(c₂);s(t₂);b§ke":"gtceu:high_energy_quark_gluon","§9Sl":"gtceu:starlight","§ke§rn§ke":"gtceu:dense_neutron","§ketime":"gtceu:temporalfluid","§kcm":"gtceu:cosmic_mesh","Fs⚶":"gtceu:rhugnor","Cu⁷⁶":"gtceu:copper76","§7熔炼为流体的时空":"gtceu:spacetime","∞":"gtceu:infinity","?":"gtceu:instability","Ct":"gtceu:celestial_secret","⸎":"gtladditions:creon","U":"gtceu:uranium","Pu":"gtceu:plutonium","§ke§r(u₂);d(c₂);s(t₂);bg§ke":"gtceu:high_energy_quark_gluon"}');
w('    var ok = 0, bad = 0, errList = \'\'');
// ═══ 🔴 自环判据的产物侧证据区块（可 grep）═══════════════════════════════════════
w('    // ══════ 🔴 自环判据（输入物品 == 输出物品 ⇒ 不生成）══════')
w('    //   用户 2026-09-30 拍板（选择题）：「A. 删掉（推荐）」')
w('    //   判据：①物品输入 ②输出物品恰好一项 ③其 id == 输入 id ④其数量 == 输入数量 ⑤无流体输出 ⇒ 恒等配方 ⇒ 跳过')
w('    //   本轮跳过 ' + SELFLOOP.length + ' 条（全部是元素单质自身的粉 1x→1x）；逐条如下，可 grep [SHANHAI-SELFLOOP]')
w('    //   ⚠️ 流体形态（1000mB <材质> → 1x <材质>_dust）【不参与】本判据 ⇒ 本轮 ' + FLUID_KEEP.length + ' 条全部保留')
for (const s of SELFLOOP) w('    //   [SHANHAI-SELFLOOP] ' + s.cn + '粉  ' + s.rid + '   ' + s.inItem + ' -> ' + s.outItem)
w('    //   [SHANHAI-SELFLOOP-KEEP] 流体→自己粉 保留 ' + FLUID_KEEP.length + ' 条（上面那批的反面对照）')
w('    //   ⚠️ 判据【判在覆盖表 OV 之后】：有 ' + RESCUED.length + ' 条原始产出像自环、但被覆盖表改成了真配方'
  + (RESCUED.length ? '（' + RESCUED.map(r => r.cn + '：' + r.rawIn + ' -> ' + r.rawOut + ' ⇒ 覆盖后 ' + r.nowIn + ' -> ' + r.nowOut).join('；') + '）' : ''))
w('    var JOBS = [');
// 🔴 2026-09-30 用户拍板 B：已在解构里的【矿物粉】改用【全链产率】定值（替换化学式口径）。
//    ⚠️ 覆盖表的解析与应用已经【提到前面】了（见 jobs 构建之后那段），
//       因为【自环判据必须在覆盖表之后判】—— 否则会把 coal_dust / graphite_dust
//       这两条被覆盖表救成真配方的条目一起删掉。这里只负责把 jobs 写成产物。
jobs.forEach((useJ,i)=>{
  w('        '+(i>0?',':'')+'{');
  w('            id: \''+useJ.id+'\', inItem: '+(useJ.inItem?'\''+useJ.inItem+'\'':'null')+', inFluid: '+(useJ.inFluid?'\''+useJ.inFluid+'\'':'null')+',');
  w('            outItems: ['+useJ.items.map(v=>'\''+v+'\'').join(', ')+'],');
  w('            outFluids: ['+useJ.fluids.map(v=>'\''+v+'\'').join(', ')+']');
  w('        }'); });
// 覆盖率写进注释头，便于交付时核对
w('    // 🔴 本批 ' + String(jobs.length) + ' 条里，有 ' + String(ovApplied) + ' 条的数值来自【矿物粉全链产率】（用户 2026-09-30 拍板 B）')
w('    //    其余保持化学式口径（要么闸门未通过、要么就是元素单质自身 1:1）')
w('    ]');
  w('    var applySet = function (sym, v) {');
  w('        var mid = SYM2MAT[sym] || (\'gtceu:\' + sym);');
  w('        var isF = false, num = v;');
  w('        if (typeof v === \'string\') {');
  w('            if (v.indexOf(\'mB\') >= 0) { isF = true; num = parseInt(v.replace(/[^0-9]/g, \'\'), 10) }');
  w('            else { num = parseInt(v, 10) }');
  w('        }');
  w('        var src = isF ? fluids : items, out = [], q;');
  w('        for (q = 0; q < src.length; q++) { if (src[q].indexOf(mid + \' \') !== 0) { out.push(src[q]) } }');
  w('        out.push(isF ? (mid + \' \' + num) : (num + \'x \' + mid + \'_dust\'));');
  w('        if (isF) { fluids = out } else { items = out }');
  w('    };');
w('    var j, k');
w('    for (j = 0; j < JOBS.length; j++) {');
w('        var J = JOBS[j], items = J.outItems.slice(), fluids = J.outFluids.slice()');
w('        var ex = EXTRA[J.id] || EXTRA[J.id.replace(/_dust$/, \'\').replace(/_fluid$/, \'\')]');
w('        if (ex) {');
  w('            if (ex.add) { for (k = 0; k < ex.add.length; k++) { if (ex.add[k].indexOf(\'mB\') >= 0) { fluids.push(ex.add[k].replace(/\\s*mB\\s*/, \'\')) } else { items.push(ex.add[k]) } } }');
  w('            if (ex.set) { for (k in ex.set) { applySet(k, ex.set[k]) } }');
  w('            if (ex.setFluid) { for (k in ex.setFluid) { applySet(k, ex.setFluid[k] + \' mB\') } }');
w('        }');
w('        try {');
w('            var b = gtr[T](J.id)');
w('            if (J.inItem !== null) { b = b.itemInputs(J.inItem) }');
w('            if (J.inFluid !== null) { b = b.inputFluids(J.inFluid) }');
w('            if (items.length) { b = b.itemOutputs(items) }');
w('            if (fluids.length) { b = b.outputFluids(fluids) }');
w('            b = b.duration(200).EUt(32)');
w('            ok = ok + 1');
w('            if (ShanhaiStats) ShanhaiStats.addResult(true)')
w('        } catch (e) { bad = bad + 1; if (errList.length < 1500) { errList = errList + J.id + \' => \' + e + \' | \' } ; if (ShanhaiStats) ShanhaiStats.addResult(false) }');
w('    }');
// =============================================================================
// 🔴 新增批（2026-09-30 用户点单）：【矿物粉 → 元素单质】
//    矿物粉 = 集成矿石处理厂（gtceu:integrated_ore_processor / gtceu:space_ore_processor）
//             把原矿处理出来的 _dust 产物；产率 = 全链期望产率（损耗/副产/概率/循环都摊进去）。
//    只加【现有 1330 条没覆盖】的那些矿物粉 ⇒ 同一输入不重复注册 ⇒ 与既有 1330 条零冲突。
//    作业单由 temp/pmd-ore2/build-decision.mjs 产出（闸门：不撞 / ≥1 元素 / 精确整数配比 / Σ∈[0.01,4] / 链非空）
// =============================================================================
const ORE_DATA = path.join(REPO, 'kubejs', '_generators', 'data', 'ore_deconstruct_jobs.json')
if (!fs.existsSync(ORE_DATA)) throw new Error('缺少矿物粉作业单：' + ORE_DATA + ' —— 先跑 node temp\\pmd-ore2\\build-decision.mjs')
const ORE = JSON.parse(fs.readFileSync(ORE_DATA, 'utf8'))
if (!ORE.jobs || !ORE.jobs.length) throw new Error('矿物粉作业单为空：' + ORE_DATA)
const jq = (s) => "'" + String(s).split('\\').join('\\\\').split("'").join("\\'") + "'"
const jarr = (a) => '[' + a.map(jq).join(', ') + ']'
// ═══════════════════════════════════════════════════════════════════════════════
// 🔴 2026-09-30（本轮）：把【覆盖条目】的证据行固化进产物 —— 可 grep，打清
//    「哪条矿粉 / N / 输入 / 输出 / 来源（全链产率 or 深度化学扭曲仪） / 每1粉产率」。
//    ⚠️ 它【只加一个新块 OV_EV + 一个打印循环】，不改上面 JOBS 段的任何一行 ——
//       这样"逐行 diff 只对应本轮新放开的条目"这条判据才仍然干净。
// ═══════════════════════════════════════════════════════════════════════════════
w('    // 🔴 可 grep 的证据行：每条被覆盖（数值来自全链产率 / 深度化学扭曲仪配比）的解构配方')
const OV_KEYS = Object.keys(OV)
w('    var OV_EV = {');
OV_KEYS.forEach((k, i) => { w('        ' + (i > 0 ? ',' : '') + jq(k) + ': ' + jq(OV[k].ev || (k + ' (无证据串)'))) });
w('    }');
w('    var ovEvN = 0');
w('    for (var ke in OV_EV) { ovEvN = ovEvN + 1; console.info(\'[SHANHAI-PMD-ORE] \' + OV_EV[ke]) }');
w('    console.info(\'[SHANHAI-PMD-ORE] 覆盖条目(数值来自产率/扭曲仪) 合计=\' + ovEvN)');
w('    // ═══ 🔴 新增批：矿物粉（集成矿石处理厂产物）→ 元素单质 ═══')
w('    //    ' + ORE.jobs.length + ' 条；输入为 N 份矿物粉，产出为【全链期望产率 × N】的精确整数配比（无四舍五入）')
w('    //    与上面 ' + String(jobs.length) + ' 条【不共用任何输入】⇒ 不冲突')
w('    var ORE_JOBS = [');
ORE.jobs.forEach((j, i) => {
  w('        ' + (i > 0 ? ',' : '') + '{')
  w('            id: ' + jq(j.id) + ', inItem: ' + jq(j.inItem) + ',')
  w('            cn: ' + jq(j.cnFull || (j.cnName + '粉')) + ', src: ' + jq(j.srcOres.join('/')) + ', route: ' + jq(j.route || '') + ',')
  w('            outs: ' + jarr(j.outItems) + ',')
  w('            fluids: ' + jarr(j.outFluids) + ',')
  w('            outsChn: ' + jq(j.outsChn) + ', fluidsChn: ' + jq(j.fluidsChn) + ', perChn: ' + jq(j.perCn) + (j.fallback ? ',' : ''))
  // 🔴 兜底：若 inItem 用的是「形式不确定」的写法（如 '#tag' 或 '9x #tag'），
  //    一旦注册抛异常就【自动换用裸 id 再试一次】—— 这样这条交付不会因为写法不被支持而全盘失效。
  //    两条 id 不同 ⇒ 同时只会存在一条（主写法成功时兜底根本不执行）。
  if (j.fallback) {
    w('            fbId: ' + jq(j.fallback.id) + ', fbIn: ' + jq(j.fallback.inItem) + ', fbNote: ' + jq(j.fallback.note || ''))
  }
  w('        }');
});
w('    ]');
w('    var okOre = 0, badOre = 0');
w('    for (var oj = 0; oj < ORE_JOBS.length; oj++) {');
w('        var OJ = ORE_JOBS[oj]');
w('        try {');
w('            var bo = gtr[T](OJ.id).itemInputs(OJ.inItem)');
w('            if (OJ.outs.length) { bo = bo.itemOutputs(OJ.outs) }');
w('            if (OJ.fluids.length) { bo = bo.outputFluids(OJ.fluids) }');
w('            bo = bo.duration(200).EUt(32)');
w('            okOre = okOre + 1');
w('            if (ShanhaiStats) ShanhaiStats.addResult(true)');
w('            // 🔴 可 grep 的证据行：哪条矿物粉 / 出哪几种元素各多少 / 全链产率 / 配方的输入输出');
w('            console.info(\'[SHANHAI-PMD-ORE] \' + OJ.cn + \' 输入=\' + OJ.inItem + \' 产出=\' + OJ.outsChn + (OJ.fluidsChn ? \' +\' + OJ.fluidsChn : \'\') + \' | 全链产率(每1粉)=\' + OJ.perChn + \' | 来自矿=\' + OJ.src + \' | 链=\' + OJ.route)');
w('        } catch (eo) {');
w('            // 🔴 兜底重试：主写法（可能是 #tag 形式）不被支持时，换裸 id 再来一次');
w('            var fbOk = false');
w('            if (OJ.fbIn) {');
w('                try {');
w('                    var bf = gtr[T](OJ.fbId).itemInputs(OJ.fbIn)');
w('                    if (OJ.outs.length) { bf = bf.itemOutputs(OJ.outs) }');
w('                    if (OJ.fluids.length) { bf = bf.outputFluids(OJ.fluids) }');
w('                    bf = bf.duration(200).EUt(32)');
w('                    fbOk = true');
w('                    console.info(\'[SHANHAI-PMD-ORE] 兜底成功 —— 主写法注册失败，已改用裸 id：\' + OJ.fbId + \' 输入=\' + OJ.fbIn + \' 产出=\' + OJ.outsChn + \' | 主写法的报错=\' + eo)');
w('                } catch (e2) { console.info(\'[SHANHAI-PMD-ORE-FAIL] 兜底也失败：\' + OJ.fbId + \' 输入=\' + OJ.fbIn + \' => \' + e2) }');
w('            }');
w('            if (fbOk) { okOre = okOre + 1; if (ShanhaiStats) ShanhaiStats.addResult(true) }');
w('            else {');
w('                badOre = badOre + 1');
w('                if (ShanhaiStats) ShanhaiStats.addResult(false)');
w('                console.info(\'[SHANHAI-PMD-ORE-FAIL] \' + OJ.id + \' 输入=\' + OJ.inItem + \' => \' + eo)');
w('            }');
w('        }');
w('    }');
w('    console.info(\'[SHANHAI-PMD-ORE] 矿物粉→元素 合计=\' + ORE_JOBS.length + \' 成功=\' + okOre + \' 失败=\' + badOre)');
w('    if (badOre > 0) { console.info(\'[SHANHAI-PMD-ORE] 有失败项 —— 上述 [SHANHAI-PMD-ORE-FAIL] 行逐条给出了输入原文名称\') }');
w('    console.info(\'[SHANHAI-DECON] jobs=\' + JOBS.length + \' oreJobs=\' + ORE_JOBS.length + \' => ok=\' + ok + \' failed=\' + bad + \' EUt=32\')');
w('    // 🔴 2026-09-27：给本批留一次 summary —— 横幅【一行一批】需要它进 BY_SCOPE。')
w('    //    本脚本【不 reset()】（跑最前，累加器本来就是 0）⇒ 这里的 total 就是本批的条数。')
w('    if (ShanhaiStats) {')
w('        try { ShanhaiStats.reportSummary(' + String.fromCharCode(39) + 'shanhai_deconstruct' + String.fromCharCode(39) + ') } catch (eR3) { console.info(' + String.fromCharCode(39) + '[SHANHAI-DECON] reportSummary FAILED: ' + String.fromCharCode(39) + ' + eR3) }')
w('    }')
w('    if (bad > 0) { console.info(\'[SHANHAI-DECON] fail: \' + errList) }');
w('})');
w('    // 本脚本【不】在这里报 summary —— 汇总由 [server_scripts]shanhai_recipes.js 的 ServerEvents.loaded 打，')
w('    //    那时三批都已上报完（靠 lifetime 字段，与调用时机无关）。')
// 🔴 2026-09-27 用户点单第 3 条：deconstruct 三合一 => 产物 = 【生成部分】+【手写区】。
//    · 手写区 = 两个标记之间的内容，【生成器原样带过去】，绝不改写。
//    · 首次生成（产物不存在）=> 写入默认手写区 `global.SD_EXTRA = {}`。
//    · 手写区放在文件【末尾】且是【顶层】：回调是稍后触发的，SD_EXTRA 一定已经赋值。
const M_BEGIN = '// ═══ 手写区 开始（生成器不会动这一段）═══'
const M_END   = '// ═══ 手写区 结束 ═══'
let HANDS = ['global.SD_EXTRA = {}']
let handNote = '(第 '+1+' 次生成：产物不存在 => 写入默认空表 global.SD_EXTRA = {})'
if (fs.existsSync(OUT)) {
    const prev = fs.readFileSync(OUT, 'utf8').split(/\r?\n/)
    const a = prev.findIndex(l => l.indexOf(M_BEGIN) >= 0)
    const b = prev.findIndex(l => l.indexOf(M_END) >= 0)
    if (a >= 0 && b > a) {
        HANDS = prev.slice(a + 1, b)
        handNote = '(保留自旧产物：' + HANDS.length + ' 行)'
    } else {
        handNote = '(旧产物里【没有】手写区标记 => 写入默认空表；原文件已备份到 .no-handreg-bak)'
        fs.writeFileSync(OUT + '.no-handreg-bak', prev.join('\r\n'), 'utf8')
    }
}
const OUT_TXT = L.join('\r\n') + '\r\n\r\n' + M_BEGIN + '\r\n' + HANDS.join('\r\n') + '\r\n' + M_END + '\r\n'
fs.writeFileSync(OUT, OUT_TXT, 'utf8')
console.log('  手写区 ' + handNote)
console.log('');
console.log('=== 🔴 新增批：矿物粉（集成矿石处理厂产物）→ 元素单质 ===');
console.log('  条数 = ' + ORE.jobs.length + '（数据源 ' + ORE_DATA + '）');
for (const j of ORE.jobs) {
  console.log('  [SHANHAI-PMD-ORE] 生成: ' + j.cnName + '粉 输入=' + j.inItem + ' 产出=' + j.outsChn
    + (j.fluidsChn ? ' +' + j.fluidsChn : '') + ' | 全链产率(每1粉)=' + j.perCn + ' | 来自矿=' + j.srcOres.join('/') + ' | 链=' + j.route);
}
console.log('');
console.log('  产物 = '+fs.statSync(OUT).size+' B  -> '+OUT);
