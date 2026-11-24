#version 150

// ============================================================================================
// 山海重构 · 黑洞的【真引力透镜】· 一级渲染的片元着色器
//
// 近光环追踪克尔零测地线，远场用解析偏折近似采样同帧背景。
// 度规、薄盘与频移用 G=c=M=1；场景和回退路径仍按 Java 的 Rs=2M 定标。
// BlackHoleShaderProbe 的软件出图保留史瓦西参考模型，正式效果用这份 GLSL 离屏验证。
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
uniform float uDiskHeat;         // 主机吸积热度 0..1
uniform vec3  uKerrParameters = vec3(0.65, 1.7599342, 3.6159372); // a/M、视界半径、顺行 ISCO
uniform float uRingGain;         // 光子环亮度总增益
uniform float uStrength;         // 偏折强度倍率（/shanhai bh strength；0 = 不弯背景但盘/环还在）
uniform float uSampleBackground; // 关透镜时只输出发光和黑影覆盖率。

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
// 黑洞中心平面的窗口深度（0..1），在 sampleBackground 中作为参考深度的下界。
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

const float B_CRIT = 2.5980762;        // 史瓦西参考尺度 / Rs
#define KERR_SPIN (uKerrParameters.x)
#define HORIZON_R (uKerrParameters.y)
#define DISK_INNER (uKerrParameters.z)
const float DISK_OUTER = 22.0;         // 11 Rs，保持原有场景范围

// ---- 数值（不许改太离谱；报告 §3 有推导）----
const float RING_WIDTH  = 0.075;       // 光子环高斯宽度（单位 Rs）
const float RING_GAIN   = 2.90;
const float HALO_WIDTH  = 0.42;
const float HALO_GAIN   = 0.48;

// 边缘淡出：dHole ∈ [EDGE_FADE_INNER, 1.0] 内把 warp / 盘 / 光子环 / 光晕一起衰减到 0。
// 与 BlackHoleLensSpec.EDGE_FADE_INNER 同值。为什么要它：见 §④ 的 maskFade 注释。
const float EDGE_FADE_INNER = 0.86;

// ============================================================================================
// 吸积盘湍流使用按像素足迹过滤的 value noise。
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
    if (footprint >= 1.20) {
        return 0.5;
    }
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
//   用于强场积分带以外的屏幕空间近似。
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
         + 0.65 * sin(angle * 5.0 + uTime * 0.2) - uTime * 2.50;
}

// 近环等离子体的宽亮弧和较窄的亮团沿同一方向公转；外层光晕略微拖尾。
float photonFlow(float phase) {
    float arc = smoothstep(-0.55, 1.0, cos(phase));
    float knot = smoothstep(0.10, 1.0, cos(2.0 * phase + 1.4));
    return 0.40 + 0.94 * arc + 0.25 * knot;
}

struct KerrRay {
    float inverseRadius;
    float radialSpeed;
    vec3 direction;
    vec3 angularSpeed;
    float lambda;
    float energy;
    float angularMomentum2;
    vec3 radialPotential;
};

// 在有限距离的局部零角动量观测者（ZAMO）标架中发出反向射线。
// lambda=Lz/E，eta=Q/E²。角向状态用球面笛卡儿向量，避免在旋转轴除以 sin(theta)。
KerrRay cameraRay(vec3 observer, vec3 dir, vec3 axis, float radius) {
    float a = KERR_SPIN;
    float r2 = radius * radius;
    float mu = dot(observer, axis);
    float sigma = r2 + a * a * mu * mu;
    float delta = r2 - 2.0 * radius + a * a;
    float area = (r2 + a * a) * (r2 + a * a) - a * a * delta * (1.0 - mu * mu);
    vec3 azimuth = cross(axis, observer);
    float tangentScale = sqrt(area / sigma);
    float momentum = -tangentScale * dot(dir, azimuth);
    float energy = sqrt(sigma * delta / area) + 2.0 * a * radius / area * momentum;
    float lambda = momentum / energy;
    float inverseRadius = 1.0 / radius;
    float radial = dot(dir, observer);
    vec3 angular = (sqrt(sigma) * (dir - radial * observer)
                  + (tangentScale - sqrt(sigma)) * dot(dir, azimuth) * azimuth
                    / max(dot(azimuth, azimuth), 1e-7)) / energy;
    float angularMomentum2 = dot(angular, angular) - a * a * mu * mu;
    float k = angularMomentum2 + a * a - 2.0 * a * lambda;
    float p = a * (a - lambda);
    return KerrRay(inverseRadius, -radial * sqrt(delta * sigma) / (energy * r2),
                   observer, angular, lambda, energy, angularMomentum2,
                   vec3(2.0 * p - k, 2.0 * k, p * p - a * a * k));
}

// R(r)/r^4 = 1 + B u² + D u³ + C u⁴. 势阱的极小值为零时即克尔临界曲线。
float shadowDistance(KerrRay ray) {
    vec3 p = ray.radialPotential;
    float slope = 1.5 * p.y;
    float root = sqrt(max(slope * slope - 8.0 * p.x * p.z, 0.0));
    float u = clamp(-2.0 * p.x / max(slope + root, 1e-5), ray.inverseRadius, 1.0 / HORIZON_R);
    return (-1.0 - u * u * (p.x + u * (p.y + u * p.z))) * (0.5 * B_CRIT);
}

float radialAccel(float u, vec3 potential) {
    return u * (potential.x + u * (1.5 * potential.y + 2.0 * potential.z * u));
}

vec3 angularAccel(vec3 direction, vec3 axis, float angularMomentum2) {
    float mu = dot(direction, axis);
    float a2 = KERR_SPIN * KERR_SPIN;
    return axis * (a2 * mu) - direction * (angularMomentum2 + 2.0 * a2 * mu * mu);
}

float dragging(float u, float lambda) {
    return -KERR_SPIN * u * (2.0 - KERR_SPIN * lambda * u)
         / max(1.0 - 2.0 * u + KERR_SPIN * KERR_SPIN * u * u, 0.035);
}

vec3 rotateAboutAxis(vec3 direction, vec3 axis, float angle) {
    vec3 pole = axis * dot(axis, direction);
    return pole + cos(angle) * (direction - pole) + sin(angle) * cross(axis, direction);
}

// 分离的 Mino 时间方程。速度 Verlet 允许在径向／极向转向点连续反弹。
// 一个像素至多保留两次可见赤道穿越，逃逸方向供背景采样使用。
void traceKerr(KerrRay ray, vec3 axis, out vec3 firstHit, out vec3 secondHit, out vec3 escaped) {
    firstHit = vec3(0.0);
    secondHit = vec3(0.0);
    escaped = vec3(0.0);
    float dt = 0.118 * inversesqrt(max(ray.angularMomentum2, 9.0));
    float u = ray.inverseRadius;
    float speed = ray.radialSpeed;
    vec3 point = ray.direction;
    vec3 velocity = ray.angularSpeed;
    float height = dot(point, axis);
    float twist = 0.0;
    float rate = dragging(u, ray.lambda);
    for (int i = 0; i < 64; ++i) {
        speed += 0.5 * dt * radialAccel(u, ray.radialPotential);
        velocity += 0.5 * dt * angularAccel(point, axis, ray.angularMomentum2);
        float nextU = u + dt * speed;
        vec3 nextPoint = point + dt * velocity;
        if (nextU >= 1.0 / HORIZON_R) {
            break;
        }
        float nextRate = dragging(max(nextU, 0.0), ray.lambda);
        float nextTwist = twist + 0.5 * dt * (rate + nextRate);
        float nextHeight = dot(nextPoint, axis);
        if (secondHit == vec3(0.0) && height * nextHeight < 0.0) {
            float t = height / (height - nextHeight);
            float hitU = mix(u, nextU, t);
            if (hitU > 1.0 / DISK_OUTER && hitU < 1.0 / DISK_INNER) {
                vec3 hit = rotateAboutAxis(normalize(mix(point, nextPoint, t)), axis, mix(twist, nextTwist, t)) / hitU;
                if (firstHit == vec3(0.0)) {
                    firstHit = hit;
                } else {
                    secondHit = hit;
                }
            }
        }
        if (nextU <= 0.0) {
            float t = u / (u - nextU);
            escaped = rotateAboutAxis(normalize(mix(point, nextPoint, t)), axis, mix(twist, nextTwist, t));
            break;
        }
        speed += 0.5 * dt * radialAccel(nextU, ray.radialPotential);
        velocity += 0.5 * dt * angularAccel(nextPoint, axis, ray.angularMomentum2);
        u = nextU;
        point = nextPoint;
        height = nextHeight;
        twist = nextTwist;
        rate = nextRate;
    }
}

// Page–Thorne 零内边界力矩通量。根、系数及 sqrt(ISCO) 对应 a=0.65。
float diskFlux(float r) {
    const float x0 = 1.9015618;
    const vec3 roots = vec3(1.4504155, 0.4673614, -1.9177769);
    const vec3 weights = vec3(0.4002077, -0.0913193, -1.2838884);
    float x = sqrt(r);
    float orbit = r * x;
    float f = x - x0 - 1.5 * KERR_SPIN * log(x / x0)
            - dot(weights, log((vec3(x) - roots) / (vec3(x0) - roots)));
    return max(f, 0.0) / (r * r * (orbit - 3.0 * x + 2.0 * KERR_SPIN));
}

// g = nu_obs/nu_em；圆轨道流体满足 nu_em/E = u^t (1 - lambda * Omega)。
float diskShift(float r, KerrRay ray) {
    float invOrbit = inversesqrt(r) / r;
    float orbitA = KERR_SPIN * invOrbit;
    return sqrt(max(1.0 - 3.0 / r + 2.0 * orbitA, 0.0))
         / (ray.energy * max(1.0 + orbitA - ray.lambda * invOrbit, 0.2));
}

vec4 diskMaterial(vec3 hit, vec3 basisX, vec3 basisZ, float r) {
    float azimuth = atan(dot(hit, basisZ), dot(hit, basisX) + 1e-6);
    float winding = log(r / DISK_INNER);
    // 对数螺旋配合有界剪切，保留远景和长时间运行下的纹理宽度。
    float phase = azimuth + 3.35 * winding - uTime * 0.88
                + 0.48 * sin(uTime * 0.77 - 2.2 * winding);
    vec2 arm = vec2(cos(phase), sin(phase));
    // 沿径向向内输运密度结；方位坐标取周期量，越过 atan 接缝时连续。
    vec2 grain = vec2(r * 1.14 + uTime * 1.70 + 0.61 * arm.y,
                      3.6 * arm.y + 1.8 * sin(2.0 * phase + r * 0.49));
    return vec4(grain, arm);
}

vec2 diskFootprint(vec4 material) {
    return vec2(noiseFootprint(material.xy), noiseFootprint(material.zw));
}

vec3 diskLight(float r, vec4 material, vec2 footprint, KerrRay ray) {
    if (r <= DISK_INNER || r >= DISK_OUTER) {
        return vec3(0.0);
    }
    float flux = diskFlux(r) * 2800.0;
    float shift = diskShift(r, ray);
    float density = fbm(material.xy, footprint.x);
    float x = material.z;
    float spiral = x * (4.0 * x * x - 3.0);
    float arm = smoothstep(0.20, 0.96, spiral + 0.42 * (density - 0.5));
    float resolved = 1.0 - smoothstep(0.056, 0.27, footprint.y);
    float filament = resolved * arm * arm * (0.65 + 0.70 * density);
    float stream = mix(1.02, 0.46 + 0.52 * density, resolved) + 1.62 * filament;

    float temperature = sqrt(sqrt(flux)) * shift * (1.0 + 0.25 * filament + 0.47 * uDiskHeat);
    vec3 color = mix(vec3(0.78, 0.12, 0.027), vec3(1.0, 0.60, 0.23), smoothstep(0.25, 0.85, temperature));
    color = mix(color, vec3(0.91, 0.96, 1.0), smoothstep(0.81, 1.58, temperature));
    float emission = sqrt(flux) * shift * shift * shift * stream * (1.0 + 2.1 * uDiskHeat);
    return color * emission * smoothstep(0.0, 0.5, r - DISK_INNER)
                            * (1.0 - smoothstep(16.0, DISK_OUTER, r));
}

vec3 sampleBackground(vec2 uvSelf, vec3 dirBent, float warp) {
    vec2 uv = uvSelf;
    if (warp > 0.0) {
        vec4 clipB = uProjMat * vec4(dirBent, 0.0);
        if (clipB.w > 1e-4) {
            vec2 bentPx = (clipB.xy / clipB.w * 0.5 + 0.5) * uScreenSize;
            vec2 localPx = bentPx - uBgRect.xy;
            vec2 border = min(localPx, uBgRect.zw - localPx);
            float valid = smoothstep(0.0, max(2.0, 0.06 * uLensRadiusPx), min(border.x, border.y));
            vec2 uvBent = clamp(localPx, vec2(0.5), uBgRect.zw - 0.5) * uBgTexel;
            uv = mix(uvSelf, uvBent, warp * valid);
        }
    }
    // 拷贝贴图只有第 0 层。在片元分支里显式指定层级，不依赖隐式导数。
    if (uDepthGate > 0.5 && uv != uvSelf) {
        float dSamp = textureLod(Sampler1, uv, 0.0).r;
        if (dSamp > 1e-6 && dSamp < 0.999999) {
            float dSelf = textureLod(Sampler1, uvSelf, 0.0).r;
            if (dSamp < max(dSelf, uHoleDepth) - uGateEps) {
                uv = uvSelf;
            }
        }
    }
    return textureLod(Sampler0, uv, 0.0).rgb;
}

void main() {
    vec2 px = gl_FragCoord.xy;
    vec2 ndc = px / uScreenSize * 2.0 - 1.0;
    vec3 dir = normalize(vec3(ndc.x * uTanHalfFov * uAspect, ndc.y * uTanHalfFov, -1.0));
    vec3 bh = uBhView;
    vec3 n = normalize(uDiskNormal);
    float rs = max(uRs, 1e-4);
    float distanceToHole = length(bh);
    float tca = dot(bh, dir);
    float bn = length(cross(bh, dir)) / rs;
    float dHole = length(px - uHolePx) / max(uLensRadiusPx, 1.0);
    float maskFade = 1.0 - smoothstep(EDGE_FADE_INNER, 1.0, dHole);
    KerrRay ray = cameraRay(-bh / distanceToHole, dir, n, distanceToHole * 2.0 / rs);

    float edge = shadowDistance(ray);
    float edgeWidth = fwidth(edge);
    float aa = max(edgeWidth, 1e-4) * 1.25;
    float surfaceDetail = 1.0 - smoothstep(0.014, 0.055, edgeWidth / B_CRIT);
    vec2 surfaceOffset = px - uHolePx;
    float surfaceAngle = atan(surfaceOffset.y, surfaceOffset.x + 1e-6);
    edge -= surfaceRadius(surfaceAngle, surfaceDetail) - B_CRIT;
    float shadowMask = 1.0 - smoothstep(-aa, aa, edge);
    float surfaceRho = sqrt(max(0.0, 1.0 + 2.0 * edge / B_CRIT));
    float wavePhase = surfacePhase(surfaceRho, surfaceAngle);
    float waveWidth = max(0.075, fwidth(wavePhase) * 0.55);

    // 远场薄透镜在最近接近点拼接入射与出射。强场带平滑切到克尔积分。
    float strength = max(uStrength, 0.0);
    float alpha = deflectAngle(bn);
    vec3 toward = (bh - dir * tca) / max(bn * rs, 1e-6);
    vec3 outgoing = dir * cos(alpha) + toward * sin(alpha);
    float strong = 1.0 - smoothstep(3.25, 4.85, bn);
    vec3 primary = vec3(0.0), secondary = vec3(0.0), escaped = vec3(0.0);
    vec3 thinHit = vec3(0.0);
    if (shadowMask < 1.0 && maskFade > 0.0) {
        if (strong > 0.0 && (uDiskGain > 0.0 || (uSampleBackground > 0.5 && strength > 0.0))) {
            traceKerr(ray, n, primary, secondary, escaped);
        }
        if (strong < 1.0 && uDiskGain > 0.0) {
            float heightToPlane = dot(bh, n);
            float incomingSlope = dot(dir, n);
            float outgoingSlope = dot(outgoing, n);
            if (incomingSlope * heightToPlane > 0.0 && abs(heightToPlane) <= abs(tca * incomingSlope)) {
                thinHit = (dir * (heightToPlane / incomingSlope) - bh) * (2.0 / rs);
            } else {
                float afterLens = heightToPlane - tca * incomingSlope;
                if (abs(outgoingSlope) > 1e-5 && outgoingSlope * afterLens > 0.0) {
                    thinHit = (tca * dir + outgoing * (afterLens / outgoingSlope) - bh) * (2.0 / rs);
                }
            }
        }
    }
    // 在一致控制流量测足迹，跨越相邻像素的盘面边界时自动淡掉细纹。
    vec3 basisX = normalize(cross(n, abs(n.y) < 0.9 ? vec3(0.0, 1.0, 0.0) : vec3(1.0, 0.0, 0.0)));
    vec3 basisZ = cross(n, basisX);
    float r0 = length(primary), r1 = length(secondary), rFar = length(thinHit);
    vec4 material0 = diskMaterial(primary, basisX, basisZ, clamp(r0, DISK_INNER, DISK_OUTER));
    vec4 material1 = diskMaterial(secondary, basisX, basisZ, clamp(r1, DISK_INNER, DISK_OUTER));
    vec4 materialFar = diskMaterial(thinHit, basisX, basisZ, clamp(rFar, DISK_INNER, DISK_OUTER));
    vec2 footprint0 = diskFootprint(material0), footprint1 = diskFootprint(material1);
    vec2 footprintFar = diskFootprint(materialFar);
    // 侧面看到的是有厚度的光球。靠近盘中面更亮，向上下两侧过渡到稀薄的热气层。
    vec3 tangentPoint = (dir * tca - bh) * (2.0 / rs);
    float height = dot(tangentPoint, n);
    vec3 grazingHit = tangentPoint - n * height;
    float rGrazing = length(grazingHit);
    float scaleHeight = 0.54 + 0.118 * clamp(rGrazing, DISK_INNER, DISK_OUTER);
    float grazingHeight = abs(height) / scaleHeight;
    float sideView = 1.0 - smoothstep(0.04, 0.29, abs(dot(ray.direction, n)));
    float grazingCore = 1.0 - smoothstep(0.17, 0.82, grazingHeight);
    float grazingAtmosphere = 1.0 - smoothstep(0.48, 1.36, grazingHeight);
    float grazing = sideView * (0.69 * grazingCore + 0.31 * grazingAtmosphere);
    vec4 materialGrazing = diskMaterial(grazingHit, basisX, basisZ, clamp(rGrazing, DISK_INNER, DISK_OUTER));
    vec2 footprintGrazing = diskFootprint(materialGrazing);

    float surfaceRipple = 0.0;
    if (surfaceDetail > 0.0 && shadowMask > 0.0 && surfaceRho > 0.60 && surfaceRho < 0.98) {
        float crest = sin(0.5 * wavePhase) / waveWidth;
        surfaceRipple = 0.64 * exp(-crest * crest) * surfaceDetail * shadowMask
                      * smoothstep(0.60, 0.67, surfaceRho)
                      * (1.0 - smoothstep(0.92, 0.98, surfaceRho));
    }
    if (shadowMask >= 1.0) {
        fragColor = vec4(vec3(surfaceRipple * maskFade), 1.0);
        return;
    }
    vec3 background = vec3(0.0);
    float coverage = shadowMask;
    float backgroundCoverage = 0.0;
    if (uSampleBackground > 0.5) {
        float maskRadius = 1.0 - smoothstep(0.70, 1.0, dHole);
        vec2 edgePx = min(px - uBgRect.xy, uBgRect.xy + uBgRect.zw - px);
        float edgeDistance = min(edgePx.x, edgePx.y);
        backgroundCoverage = smoothstep(0.0, 4.0, edgeDistance);
        if (backgroundCoverage > 0.0) {
            float maskEdge = smoothstep(0.0, max(4.0, uLensRadiusPx * 0.10), edgeDistance);
            vec2 uvSelf = clamp(px - uBgRect.xy, vec2(0.5), uBgRect.zw - 0.5) * uBgTexel;
            float bend = alpha * strength;
            vec3 dirBent = dir * cos(bend) + toward * sin(bend);
            // 屏幕缓存只能采样镜头前方的逃逸光线，过渡带内混合解析方向。
            float tracedWarp = strong * smoothstep(0.10, 0.65, dot(escaped, dir));
            vec3 scaledEscape = mix(dir, escaped, min(strength, 1.0));
            dirBent = mix(dirBent, scaledEscape, tracedWarp);
            float warp = strength > 0.0 ? maskRadius * maskEdge * maskFade : 0.0;
            background = sampleBackground(uvSelf, dirBent, warp) * (1.0 - shadowMask) * backgroundCoverage;
            coverage = mix(shadowMask, 1.0, backgroundCoverage);
        }
    }
    if (maskFade <= 0.0) {
        fragColor = vec4(background, coverage);
        return;
    }

    float ringWidth = max(RING_WIDTH, aa);
    float coreOffset = edge / ringWidth;
    float haloOffset = edge / HALO_WIDTH;
    float ringCore = exp(-(coreOffset * coreOffset)) * RING_WIDTH / ringWidth;
    float ringHalo = exp(-(haloOffset * haloOffset)) * HALO_GAIN;
    float ringShift = diskShift(5.8, ray);
    vec3 ringTint = mix(vec3(1.0, 0.37, 0.12), vec3(0.90, 0.94, 1.0), smoothstep(0.47, 1.14, ringShift));
    float phase = surfaceAngle - uTime * 1.25663706;
    float beam = clamp(ringShift * ringShift, 0.25, 1.35);
    // 先压缩亮环峰值，让亮弧经过趋近侧时也保留亮度变化。
    float coreLight = (1.0 - exp(-ringCore * RING_GAIN * beam)) * photonFlow(phase);
    float haloLight = ringHalo * beam * photonFlow(phase + 0.58);
    vec3 ringCol = ringTint * (coreLight + haloLight) * max(uRingGain, 0.0);

    vec3 diskCol = vec3(0.0);
    if (uDiskGain > 0.0) {
        vec3 first = diskLight(r0, material0, footprint0, ray);
        // 第二次盘面穿越的光先经过近侧盘层，取低透射率表现光学厚度。
        vec3 second = diskLight(r1, material1, footprint1, ray);
        float transmission = 0.86 - 0.46 * smoothstep(0.0, 0.48, max(first.r, first.g));
        vec3 farDisk = diskLight(rFar, materialFar, footprintFar, ray);
        diskCol = mix(farDisk, first + second * transmission, strong);
        if (grazing > 0.0) {
            vec3 photosphere = diskLight(rGrazing, materialGrazing, footprintGrazing, ray);
            photosphere *= mix(vec3(0.88, 0.55, 0.38), vec3(1.0), grazingCore);
            diskCol = max(diskCol, photosphere * (0.96 * grazing));
        }
        diskCol *= uDiskGain;
    }

    float outsideShadow = (1.0 - shadowMask) * maskFade;
    vec3 emission = ringCol * outsideShadow + vec3(surfaceRipple) * maskFade;
    float diskEnergy = max(diskCol.r, max(diskCol.g, diskCol.b));
    // 同一层薄盘同时吸收背景光并叠加发射，拷贝范围内外的盘面曝光一致。
    float diskOpacity = 0.62 * diskEnergy / (diskEnergy + 0.72) * maskFade;
    background *= 1.0 - diskOpacity;
    coverage += diskOpacity * (1.0 - shadowMask) * (1.0 - backgroundCoverage);
    float diskExposure = 1.30 / (1.0 + 1.44 * diskEnergy);
    emission += diskCol * diskExposure * outsideShadow;
    fragColor = vec4(background + emission, coverage);
}
