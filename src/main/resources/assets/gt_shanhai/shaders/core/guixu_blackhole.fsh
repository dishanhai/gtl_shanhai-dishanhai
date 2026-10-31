#version 150

// ============================================================================================
// 山海重构 · 黑洞的【真引力透镜】· 一级渲染的片元着色器
//
// 它做什么（三件，全部在相机空间里算）：
//   ① 把【同一帧已经渲染好的背景】（方块 + 天空 + 云）当成一张屏幕空间纹理，
//      按引力偏折角把采样点搬到别处 ⇒ 背景被真的扭曲（不是贴一张旋涡图）。
//   ② 程序化算吸积盘：被弯折后的射线与过黑洞的薄盘求交，用噪声 + 开普勒转速 +
//      多普勒集束（迎面更亮）着色 ⇒ 盘的"背面"会被透镜抬到黑影上方，形成经典弧。
//   ③ 光子环 / 黑影：碰撞参数 b < b_crit（= 3√3/2·Rs）⇒ 光子被吞 ⇒ 纯黑；
//      b ≈ b_crit 的一薄层是绕光子球转了几圈的强光 ⇒ 解析式高斯亮环。
//
// 与 Java 侧的契约（改这里必须同步改 BlackHoleLensMath 并在报告里标注）：
//   * 偏折角多项式 deflectAngle() 与 BlackHoleLensMath.deflectAngle() 逐字同款；
//   * B_CRIT 与 BlackHoleLensMath.B_CRIT 同值 2.5980762（= 3√3/2）；
//   * 所有 uniform 名与 blackhole.json 的 uniforms 列表逐字一致，
//     否则 ShaderInstance 找不到 location ⇒ Java 侧的安全检查会降级到二级。
//   * 边缘淡出常数 EDGE_FADE_INNER 与 BlackHoleLensSpec.EDGE_FADE_INNER 同值，
//     且 Java 侧 BlackHoleShaderProbe 的逐像素复算必须逐行同款（离线出图与游戏同一份）。
//
// 坐标系：像素 = gl_FragCoord（左下原点）；相机空间 = 相机在原点、朝 -z；
//         黑洞位置 uBhView 已经由 Java 减过 cameraPos 并乘过相机旋转。
// ============================================================================================

uniform sampler2D Sampler0;      // 背景：同一帧主渲染目标的一块颜色拷贝（1:1 像素，左下原点）
uniform sampler2D Sampler1;      // 🔴 深度：同一块矩形的【窗口深度】拷贝（NEAREST；uDepthGate=1 时才有意义）

uniform mat4  uProjMat;          // 事件给的投影矩阵（只用来把"弯折后的方向"投回屏幕坐标）
uniform vec3  uBhView;           // 黑洞中心（相机空间，方块）
uniform vec3  uDiskNormal;       // 吸积盘法线（相机空间；由 Java 侧按固定倾角算）
uniform vec2  uScreenSize;       // 主渲染目标像素尺寸 (w, h)
uniform vec4  uBgRect;           // 背景拷贝区域 x0,y0,w,h（像素，左下原点）
uniform vec2  uBgTexel;          // 1/贴图边长（把像素换算成 uv）
uniform vec2  uHolePx;           // 黑洞中心在屏幕上的像素坐标
uniform float uLensRadiusPx;     // 透镜半径（像素）= 采样区域的半边长
uniform float uRs;               // 史瓦西半径（方块）
uniform float uTanHalfFov;       // tan(fovY/2)，从投影矩阵反解
uniform float uAspect;           // w/h
uniform float uTime;             // 秒（渲染刻 + partialTick）
uniform float uDiskGain;         // 盘亮度总增益
uniform float uRingGain;         // 光子环亮度总增益
uniform float uStrength;         // 偏折强度倍率（/shanhai bh strength；0 = 不弯背景但盘/环还在）

// 🔴 2026-10-25 新增：<b>"只有比黑洞更远的像素才参与弯折"</b>（用户原话：
//   「AR 字…为什么会受到黑洞引力透镜的影响，它在黑洞的前面啊，而且我发现黑洞前面的物体
//     也会被黑洞的引力透镜影响，这不对，应该是只有黑洞后面的物体才会受到影响」）
//
// 改前只有<b>写入门</b>（quad 的 z = 洞心平面 + GL depth test LEQUAL）：前景像素自己确实
// 不会被覆盖，但**采样侧没有门** —— 一个背景像素的弯折采样点可能落在前景物体的像素上，
// 于是"前景的颜色"被搬到别处（绿字/方块在黑洞周围多出一层鬼影）。那就是用户看到的现象。
// 现在两道门：
//   ① 写入门：GL depth test（不变）；
//   ② 采样门：采样点若落在"比洞心平面更近"的像素上 ⇒ 一律退回<b>本像素自己</b>（warp 归 0，
//      颜色与已经画好的背景逐像素相同），前景的像素一个都不许被搬走。
uniform float uDepthGate;        // 1 = 深度可用（已拷进 Sampler1）⇒ 启用采样门；0 = 退回改前行为
// 黑洞【中心平面】的窗口深度（0..1）。🔴 2026-10-25 第四改：它<b>必须被真的读</b>
//   （读它的地方是下面 ④b 的 `refDepth = max(dSelf, uHoleDepth)`），原因有两条，缺一不可：
//
//   ① 语义：背景不可能比洞面更近 ⇒ 采样点比「max(本像素深度, 洞面深度)」还近就一定是前景。
//      纯用 dSelf 时，若某像素的深度没数据/被 clamp 到洞面之前，门就整体失效；用 max 兜住它。
//   ② 🔴 结构：**GLSL 编译器会把"只声明、从不读取"的 uniform 整条删掉** —— 链接后的程序里没有它的
//      location（glGetUniformLocation 返回 -1），vanilla 的 ShaderInstance#updateLocations 会打一行
//      `Shader gt_shanhai:guixu_blackhole could not find uniform named uHoleDepth in the specified shader program.`
//      并且**不把它放进 uniformMap** ⇒ 我们自己的「必需 uniform 缺失 ⇒ 降二级」检查判定着色器坏了 ⇒
//      **整条一级引力透镜永久降成二级静态贴图**。实机日志就是这么死的（用户原话
//      「现在引力透镜完全不显示了」）：一行 `could not find uniform named uHoleDepth` 之后 2 毫秒
//      就是 `fallback=2 reason=uniform_missing:uHoleDepth`，此后 422 行全是 `path=fallback-quad level=2`。
//      ⇒ 本工程同类事故**第二次**（前一次是 uScreenSize 声明未读 ⇒ 洞内一帧都不渲染）。
//   ⇒ 改这一段时只有两条路：① 保留对它的读取；② 把它登记进 BlackHoleLensSpec.OPTIONAL_UNIFORMS
//      （那一侧**已经登记**，是双保险：就算哪天有人又把这个 uniform 删掉，也只丢一行日志，不会掉级）。
uniform float uHoleDepth;
// 🔴 2026-10-25 新增：采样门容差（窗口深度单位）= Java 侧"沿视线轴 0.75 格"换算值，下限 2e-4。
//   取法规矩见 BlackHoleLensRenderer#windowDepth / Frame#gateEps。JSON 里必须登记默认值，
//   否则 safeGetUniform 拿到的是 DUMMY（本工程有先例：缺 uniform 不抛异常但也不生效）。
uniform float uGateEps;

out vec4 fragColor;

// ---- 物理常数（与 Java 侧同值）----
const float B_CRIT      = 2.5980762;   // 临界碰撞参数 / Rs  (= 3*sqrt(3)/2)
const float DISK_INNER  = 3.0;         // 吸积盘内缘 / Rs (= 史瓦西 ISCO = 3 Rs)
const float DISK_OUTER  = 11.0;        // 吸积盘外缘 / Rs

// ---- 数值（不许改太离谱；报告 §3 有推导）----
const float RING_WIDTH  = 0.075;       // 光子环高斯宽度（单位 Rs）
const float RING_GAIN   = 2.90;
const float HALO_WIDTH  = 0.42;
const float HALO_GAIN   = 0.48;

// 边缘淡出：dHole ∈ [EDGE_FADE_INNER, 1.0] 内把 warp / 盘 / 光子环 / 光晕一起衰减到 0。
// 与 BlackHoleLensSpec.EDGE_FADE_INNER 同值。为什么要它：见 §④ 的 maskFade 注释。
const float EDGE_FADE_INNER = 0.86;

// ============================================================================================
// 噪声：便宜的 value noise + 3 抽样 fbm（吸积盘湍流）。不要换成 simplex：
// 这里每像素只跑一次求交，但整块 rect 是逐帧重画的，能省则省。
// ============================================================================================
float hash21(vec2 p) {
    p = fract(p * vec2(123.34, 456.21));
    p += dot(p, p + 45.32);
    return fract(p.x * p.y);
}

float noiseFootprint(vec2 p) {
    // 山海：先量測未折返座標的像素足跡；次像素雜訊收斂到均值，避免摩爾紋。
    return max(length(dFdx(p)), length(dFdy(p)));
}

float vnoise(vec2 p, float footprint) {
    vec2 i = floor(p);
    vec2 f = fract(p);
    vec2 u = f * f * (3.0 - 2.0 * f);
    float a = hash21(i);
    float b = hash21(i + vec2(1.0, 0.0));
    float c = hash21(i + vec2(0.0, 1.0));
    float d = hash21(i + vec2(1.0, 1.0));
    return mix(mix(mix(a, b, u.x), mix(c, d, u.x), u.y), 0.5,
               smoothstep(0.35, 1.20, footprint));
}

float fbm(vec2 p, float footprint) {
    return 0.55 * vnoise(p, footprint)
         + 0.30 * vnoise(p * 2.03 + 11.3, footprint * 2.03)
         + 0.15 * vnoise(p * 4.07 + 27.1, footprint * 4.07);
}

// ============================================================================================
// 偏折角（弧度）：α = 2t + 2.2t² + 1.6t³,  t = Rs/b
//   * 首项 2Rs/b = 弱场爱因斯坦偏折（4GM/(c²b)）；
//   * 后两项是近场经验高阶项：真解在 b→b_crit 发散，多项式做不到，所以把奇点交给
//     "b < b_crit 直接画黑"来接住。
//   ⚠️ 这是解析近似偏折律，不是逐像素积分零测地线（报告 §7 已标注）。
// ============================================================================================
float deflectAngle(float bn) {
    float t = 1.0 / max(bn, 0.35);
    return 2.0 * t + 2.2 * t * t + 1.6 * t * t * t;
}

// 山海：低頻黑色表面起伏，白邊只描繪波峰；不重取樣背景、不新增 uniform。
float surfaceRadius(float angle, float detail) {
    return B_CRIT * (1.0 + detail * (0.012 * sin(angle * 3.0 + uTime * 0.5)
                                  + 0.006 * sin(angle * 5.0 - uTime * 0.37)));
}

float surfacePhase(float radius, float angle) {
    return (radius - 0.62) * 39.2699082 + 1.50 * sin(angle * 3.0 - uTime * 0.3)
         + 0.65 * sin(angle * 5.0 + uTime * 0.2) - uTime * 0.85;
}

void main() {
    vec2 px = gl_FragCoord.xy;
    vec2 ndc = px / uScreenSize * 2.0 - 1.0;

    // ---------------------------------------------------------------------------------------
    // ① 相机空间射线方向（不靠投影矩阵求逆：标准透视下解析式就是它，报告 §3 有离线交叉验证）
    // ---------------------------------------------------------------------------------------
    vec3 dir = normalize(vec3(ndc.x * uTanHalfFov * uAspect,
                              ndc.y * uTanHalfFov,
                              -1.0));

    // ---------------------------------------------------------------------------------------
    // ② 射线 ↔ 黑洞几何：tca = 最近接近点在线上的参数，b = 碰撞参数
    // ---------------------------------------------------------------------------------------
    vec3  bh  = uBhView;
    float tca = dot(bh, dir);
    float b   = length(cross(bh, dir)); // 避免洞心方向兩個近等大數相減失去精度。
    float rs  = max(uRs, 1e-4);
    float bn  = b / rs;

    // ③ 把射线朝黑洞方向弯 α 角：在 (dir, 垂线) 张成的平面里转
    //    α 再乘运行期"偏折强度"（/shanhai bh strength）：0 = 背景不弯（盘/环照旧），1 = 物理弱场，2 = 夸张
    float alpha = deflectAngle(bn) * max(uStrength, 0.0);
    vec3  perp  = bh - dir * tca;
    float perpLen = length(perp);
    vec3  dirBent = dir;
    if (perpLen > 1e-6) {
        dirBent = normalize(dir * cos(alpha) + (perp / perpLen) * sin(alpha));
    }

    // ---------------------------------------------------------------------------------------
    // ④ 背景：把"弯折后的方向"投回屏幕，采样同一帧的颜色拷贝
    //    在 rect 边缘把 warp 收到 0 ⇒ 采样点回到"自己这个像素"⇒ 与已经画好的背景逐像素
    //    相同（无缝），同时保证永远不会采到 rect 之外（那里没有拷贝，采了就是脏数据）。
    // ---------------------------------------------------------------------------------------
    float dHole = length(px - uHolePx) / max(uLensRadiusPx, 1.0);
    float maskRadius = 1.0 - smoothstep(0.70, 1.0, dHole);

    vec2  edgePx   = min(px - uBgRect.xy, uBgRect.xy + uBgRect.zw - px);
    float edgeDist = min(edgePx.x, edgePx.y);
    float maskEdge = smoothstep(0.0, max(4.0, uLensRadiusPx * 0.10), edgeDist);

    // 🔴 边缘淡出（2026-10-25，用户「左右有明显的分割线」）：
    //    改前 warp 有两道掩码（maskRadius/maskEdge）⇒ 边界处已经是 0，
    //    但 **盘 / 光子环 / 光晕完全没有掩码**（下面 col += diskCol; col += ringCol; 直接加），
    //    只要矩形边界切到盘或环，就是一条硬边。像素预算一收矩形（近距常见）就切在 6~7 Rs 处。
    //    ⇒ 现在用同一个 fade 乘【全部四项】，dHole 按"到矩形中心的最大半径"归一化，四边一致，
    //      最后 14%（0.86..1.0）用 smoothstep 淡出。
    float maskFade = 1.0 - smoothstep(EDGE_FADE_INNER, 1.0, dHole);
    float warp     = maskRadius * maskEdge * maskFade;

    vec2 uvSelf = (px - uBgRect.xy) * uBgTexel;
    vec2 uvBent = uvSelf;
    vec4 clipB  = uProjMat * vec4(dirBent, 0.0);
    if (clipB.w > 1e-4) {
        vec2 bentPx = (clipB.xy / clipB.w * 0.5 + 0.5) * uScreenSize;
        uvBent = (bentPx - uBgRect.xy) * uBgTexel;
    }
    vec2 uvMax  = uBgRect.zw * uBgTexel;
    vec2 uvBentC = clamp(uvBent, vec2(0.0), uvMax);
    vec2 uvSelfC = clamp(uvSelf, vec2(0.0), uvMax);
    vec2 uv     = clamp(mix(uvSelfC, uvBentC, warp), vec2(0.0), uvMax);

    // ---------------------------------------------------------------------------------------
    // ④b 🔴 采样侧深度门（2026-10-25，2026-10-25 深夜【判据改过一次】，看下面的"为什么不再和洞心比"）
    //    · dSamp：**实际采样点**（已经过 mix 的那个点）的深度；
    //    · dSelf：**这个像素自己**的深度（uvSelfC 就是它自己的 texel）；
    //    · 判据：采样点比"我要替换掉的那个像素"更近（差超过 uGateEps）⇒ 它采到了前景 ⇒ 退回 uvSelfC
    //      （= 这个像素原本的颜色，warp 归 0）⇒ 前景的颜色绝不会被搬到别处。
    //
    //    🔴 为什么**不**和洞心平面常数比（改前写法 `dSamp < uHoleDepth - 1e-6` 的三个毛病）：
    //      ① 语义不对：鬼影的定义是"我采到的那个点比我要替换的像素更近"，与洞心在哪个深度无关；
    //      ② 整条门押在一个常数上：uHoleDepth ≈ 0.9999（远处黑洞），它偏一丁点就【全拦】或【全放】——
    //         并且全拦的表现是"整屏一点弯折都没有"，正是用户报的那句
    //         「现在它连黑洞后面的方块也不影响了」；
    //      ③ 贴图没数据（dSamp 恒 0）时，`0 < 0.9999 - 1e-6` 恒成立 ⇒ 每个像素都被判成前景 ⇒
    //         **失败形态是"完全不弯折"**。现在换成自比之后，dSamp=0 ⇒ 条件不成立 ⇒ 退回"全都弯折"
    //         （= 2026-10-25 之前的行为）。**门的失败形态从此变成"少拦"，不可能再变成"一点都不弯"**。
    //
    //    ④ 🔴 2026-10-25 第四改：参照深度 = **max(dSelf, uHoleDepth)**，不是纯 dSelf。
    //       · 为什么不是纯 dSelf：dSelf 只是"这一个像素的深度"，它可能没有数据（0）、也可能被 clamp
    //         到洞面之前。这时门就整体失效（或反过来整体收紧）。而"背景不可能比洞面更近"是硬约束
    //         ⇒ 用洞面深度给 dSelf 一个**下界**：dSelf 正常（≈ 洞面之后）时 max 就等于 dSelf，
    //         与改前逐像素等价；dSelf 落到洞面之前时用洞面兜住，门退化成"比洞面近就拦"，仍比全放安全。
    //         （**门不再"整条押在一个常数上"**：常数只当下界参与 max，正常情形由 dSelf 决定。）
    //       · 为什么**必须**在这里保留对 uHoleDepth 的读取（结构原因，比语义原因更要命）：
    //         GLSL 编译器会删掉"只声明、从不读取"的 uniform ⇒ 程序里没有 location ⇒
    //         Java 侧 getUniform 返回 null ⇒ 「必需 uniform 缺失 ⇒ 降二级」把整条一级透镜永久降成
    //         静态贴图（实机：`fallback=2 reason=uniform_missing:uHoleDepth`，透镜完全消失）。
    //         本工程第二次踩这个坑（前一次 uScreenSize）；另一侧的双保险是
    //         BlackHoleLensSpec.OPTIONAL_UNIFORMS 里登记了 uHoleDepth —— 但**别指望它**：
    //         一个 uniform 只该在"缺了画面逐像素不变"时才登记成可选，而这里它本来就该被读到。
    //    · `dSamp > 1e-6 && dSamp < 0.999999`：这张贴图这一格**确实有深度数据**（0 = 近平面/没数据，
    //      1 = 远平面/天空），挡住 0/1 两种坏值把门变成"全拦"。
    //    · `uGateEps` 由 Java 侧按"沿视线轴 0.75 格"换算成窗口深度差（下限 1e-5 ⇒ 近距离时物理项胜出，
    //      约 60 格以外才由下限接手），见 Frame#gateEps。⚠️ 别把"下限"写成 2e-4：那个数在 55 格处
    //      ≈ **12 格**容差，门会形同虚设（2026-10-25 实机回滚过一次，就是这条）。
    //    ⚠️ 这里【故意不做 discard】：写入门交给 GL depth test（2026-10-24 起就在、实机 447/447 帧验过）。
    //      原因：万一 Sampler1 没绑上（取到的是 0 号纹理 = 黑），discard 会把整张 pass 吞掉 → 黑洞整块消失；
    //      而只做采样门的最坏结果是"背景不弯折"，黑洞本体/光子环/盘照旧在 ⇒ 失败形态是"退化"，不是"消失"。
    //    uDepthGate = 0（光影生效 / 深度拷不出来 / /shanhai bh depthgate off）时整段跳过
    //    ⇒ 行为与改前逐字一致。
    // ---------------------------------------------------------------------------------------
    if (uDepthGate > 0.5) {
        float dSamp = texture(Sampler1, uv).r;          // 采样点深度
        float dSelf = texture(Sampler1, uvSelfC).r;     // 本像素深度（写入门已保证它在洞面之后）
        // 🔴 参照深度 = max(本像素深度, 洞心平面深度)：正常情形 = dSelf（与改前逐像素等价），
        //    dSelf 落在洞面之前时由洞面兜住 —— 语义上"背景不可能比洞面更近"，结构上保证
        //    uHoleDepth 被真的读到（读不到 = 被 GLSL 优化掉 = 一级透镜整条降级，见 ④b ④ 与声明处注释）。
        float refDepth = max(dSelf, uHoleDepth);
        if (dSamp > 1e-6 && dSamp < 0.999999 && dSamp < refDepth - uGateEps) {
            uv = uvSelfC;                               // 采样点压在前景上 ⇒ 不弯折（采自己）
        }
    }
    vec3 bg     = texture(Sampler0, uv).rgb;

    // ---------------------------------------------------------------------------------------
    // ⑤ 吸积盘：弯折后的射线与"过黑洞、法线 uDiskNormal"的薄盘求交（解析解，一次求交，零步进）
    // ---------------------------------------------------------------------------------------
    vec3  n      = normalize(uDiskNormal);
    vec3  basisX = normalize(cross(n, abs(n.y) < 0.9 ? vec3(0.0, 1.0, 0.0) : vec3(1.0, 0.0, 0.0)));
    vec3  basisZ = cross(n, basisX);

    vec3  diskCol = vec3(0.0);
    float denom   = dot(dirBent, n);
    // 導數必須在一致控制流程中求值，不放在盤面求交的分支內。
    float safeDenom = abs(denom) < 1e-4 ? (denom < 0.0 ? -1e-4 : 1e-4) : denom;
    float tDisk = dot(bh, n) / safeDenom;
    vec3 diskRel = dirBent * tDisk - bh;
    float diskRadius = length(diskRel) / rs;
    float diskAngle = atan(dot(diskRel, basisZ), dot(diskRel, basisX));
    float spin = uTime * 2.6 / pow(max(diskRadius, 1.0), 1.5);
    // sin 方位座標消除 atan 在 -pi/pi 的雜訊接縫；徑向相位向內推進。
    vec2 noiseCoord = vec2(min(diskRadius, 64.0) * 1.35 + uTime * 0.45, sin(diskAngle + spin) * 2.6);
    float footprint = noiseFootprint(noiseCoord);
    if (abs(denom) > 1e-4) {
        float tHit = dot(bh, n) / denom;
        if (tHit > 0.0) {
            vec3  hit = dirBent * tHit;
            vec3  rel = hit - bh;
            float rn  = length(rel) / rs;
            if (rn > DISK_INNER && rn < DISK_OUTER) {
                // 内/外缘软过渡
                float band = smoothstep(DISK_INNER, DISK_INNER * 1.12, rn)
                           * (1.0 - smoothstep(DISK_OUTER * 0.72, DISK_OUTER, rn));

                // 开普勒湍流：方位角 + 半径，按 ω ∝ r^-1.5 平移 ⇒ 内圈转得快（真吸积盘的样子）
                float turb = fbm(noiseCoord, footprint);
                float shade = band * (0.35 + 1.30 * turb) * 1.15;

                // 多普勒集束：盘沿 +φ 方向转，速度朝相机的一侧更亮（1/(1-βcosθ)³）
                vec3  velDir = normalize(cross(n, rel));
                float beta   = clamp(0.62 * inversesqrt(max(rn, 1.0)), 0.0, 0.80);
                float cosT   = dot(velDir, -dirBent);
                float beam   = 1.0 / pow(max(0.35, 1.0 - beta * cosT), 3.0);

                // 温度梯度：内白热 → 中橙 → 外暗红
                vec3 cHot = vec3(1.00, 0.97, 0.92);
                vec3 cMid = vec3(1.00, 0.62, 0.24);
                vec3 cLow = vec3(0.62, 0.13, 0.05);
                vec3 dc   = mix(cHot, cMid, smoothstep(DISK_INNER * 1.05, DISK_INNER * 3.0, rn));
                dc        = mix(dc,   cLow, smoothstep(DISK_INNER * 3.0,  DISK_OUTER,      rn));

                diskCol = dc * (shade * beam) * max(uDiskGain, 0.0);
            }
        }
    }

    // ---------------------------------------------------------------------------------------
    // ⑥ 黑影 + 光子环
    //    b < b_crit ⇒ 光子逃不出来 ⇒ 背景被完全遮住；
    //    b ≈ b_crit 的一薄层是"绕光子球转了几圈、最后擦边逃出来"的光 ⇒ 强亮环（解析高斯）。
    // ---------------------------------------------------------------------------------------
    float aa         = max(fwidth(bn), 1e-4) * 1.25;
    float surfaceDetail = 1.0 - smoothstep(0.014, 0.055, fwidth(bn) / B_CRIT);
    vec2 surfaceOffset = px - uHolePx;
    float surfaceAngle = atan(surfaceOffset.y, surfaceOffset.x + 1e-6);
    float waveRadius = surfaceRadius(surfaceAngle, surfaceDetail);
    float shadowMask = 1.0 - smoothstep(waveRadius - aa, waveRadius + aa, bn);

    float surfaceRho = bn / waveRadius;
    float wavePhase = surfacePhase(surfaceRho, surfaceAngle);
    float waveWidth = max(0.075, fwidth(wavePhase) * 0.55);
    float waveCrest = exp(-pow(sin(wavePhase * 0.5) / waveWidth, 2.0));
    float surfaceRipple = 0.80 * waveCrest * surfaceDetail * shadowMask
                        * smoothstep(0.60, 0.67, surfaceRho)
                        * (1.0 - smoothstep(0.92, 0.98, surfaceRho));

    float ringWidth = max(RING_WIDTH, aa);
    float ringCore = exp(-pow((bn - waveRadius) / ringWidth, 2.0)) * RING_WIDTH / ringWidth;
    float ringHalo = exp(-pow((bn - waveRadius) / HALO_WIDTH, 2.0)) * HALO_GAIN;
    vec3  ringCol  = vec3((ringCore * RING_GAIN + ringHalo) * max(uRingGain, 0.0));

    // ---------------------------------------------------------------------------------------
    // ⑦ 合成：背景 → 被黑影吃掉 → 盘与环叠在上面（盘/环在黑洞前面，应该压在黑影上）
    // ---------------------------------------------------------------------------------------
    vec3 col = mix(bg, vec3(0.0), shadowMask);
    // 盘 / 环 / 光晕一起乘边缘淡出（改前是直接加 ⇒ 矩形边界处一条硬边）
    col += diskCol * (1.0 - shadowMask) * maskFade;
    col += ringCol * (1.0 - shadowMask) * maskFade;
    col += vec3(surfaceRipple) * maskFade;

    fragColor = vec4(col, 1.0);
}
