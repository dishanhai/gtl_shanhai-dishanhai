// Outgoing Cartesian Kerr–Schild, M=1 and signature (-+++). Past-directed rays cross
// r+ on the regular branch. Spatial canonical momentum and p_t are positive-frequency
// covectors with respect to the past-directed convention used by this integrator.
uniform mat4 uInvProj;
uniform vec3 uBhView;
uniform vec3 uDiskNormal;
uniform vec3 uEquatorialX;
uniform vec2 uScreenSize;
uniform float uRs;
uniform vec2 uTime;           // residual KS time and count of 64 M coherence intervals
uniform float uDiskGain;
uniform float uDiskHeat;
uniform vec4 uKerrParameters; // a*, r+, ISCO, sign of disk rotation
uniform vec4 uDiskPhysics;    // relative accretion rate, H/r, temperature in K, emissivity
uniform float uOuterDisk;     // Boyer–Lindquist disk radius in GM/c²
uniform float uDiskSqrtFlux[128]; // Page–Thorne steady thin disk, sampled from ISCO to uOuterDisk
uniform vec2 uIntegration;    // relative tolerance and accepted/rejected step budget
uniform vec4 uWhiteHole;      // white-hole weight, radial beta, radiance gain, 16-bit seed
uniform vec4 uWhiteTime;      // simulation seconds, burst age (-1 when inactive), jet gain, burst gain
uniform vec4 uWhiteStyle;     // disk density, thickness, spectrum index, core gain
uniform vec4 uWhiteExtraBursts0; // alternate (age, gain), two waves per vector
uniform vec4 uWhiteExtraBursts1;
uniform sampler2D WhiteParticleGrid;
uniform float uWhiteParticleGain;
uniform float uWhiteObserverBeta;
uniform float uWhitePrimary; // attached host resolves direct emission in the world compositor

// Inner and outer extent of the contour strip in screen pixels.
uniform vec2 uCriticalArc;

vec2 criticalExtent(float span) {
    return max(vec2(.5 * span), uCriticalArc);
}

vec2 criticalParam(vec2 offset) {
    if (uCriticalArc.y <= 0.0) return sqrt(offset);
    // Keep subpixel samples at the shadow. Give the first arcs uniform
    // radial spacing, then spread samples over the broader outer emission.
    return sqrt(min(offset, vec2(4.0))) + .25 * clamp(offset - 4.0, 0.0, 24.0)
         + 2.0 * (sqrt(1.0 + .25 * max(vec2(0.0), offset - 28.0)) - 1.0);
}

float criticalOffset(float row, float span) {
    vec2 extent = criticalParam(criticalExtent(span));
    float radial = row * (extent.x + extent.y) - extent.x;
    float magnitude = abs(radial);
    if (uCriticalArc.y <= 0.0 || magnitude <= 2.0) return radial * magnitude;
    float tail = max(0.0, magnitude - 8.0);
    return sign(radial) * (4.0 * magnitude - 4.0 + tail * tail);
}

float criticalRow(float offset, vec2 extent) {
    float radial = criticalParam(vec2(abs(offset), 0.0)).x;
    return (sign(offset) * radial + extent.x) / (extent.x + extent.y);
}

#define SPIN uKerrParameters.x
#define HORIZON uKerrParameters.y
#define ISCO uKerrParameters.z
#define ROTATION uKerrParameters.w

struct Metric {
    float r;
    float h;
    vec3 l;
    vec3 dr;
    vec3 dh;
};

float radius(vec3 x) {
    float a2 = SPIN * SPIN;
    float b = dot(x, x) - a2;
    float d = length(vec2(b, 2.0 * SPIN * x.z));
    float r2 = b >= 0.0 ? 0.5 * (d + b) : 2.0 * a2 * x.z * x.z / (d - b);
    return sqrt(r2);
}

Metric metric(vec3 x) {
    // Trial stages can pass r+; the ring singularity is outside the integration domain.
    float r = max(radius(x), 0.45);
    float inverseR = 1.0 / r;
    float c = x.z * inverseR;
    float a2 = SPIN * SPIN;
    float sigma = r * r + a2 * c * c;
    float inverseSigma = 1.0 / sigma;
    float h = r * inverseSigma;
    float d = r * r + a2;
    vec3 l = vec3((-r * x.xy + SPIN * vec2(x.y, -x.x)) / d, -c);
    vec3 dr = vec3(r * x.xy, d * c) * inverseSigma;
    vec3 dc = (vec3(0.0, 0.0, 1.0) - c * dr) * inverseR;
    vec3 dh = (dr - 2.0 * h * (r * dr + a2 * c * dc)) * inverseSigma;
    return Metric(r, h, l, dr, dh);
}

struct State {
    vec3 x;
    vec3 p;
    float t;
};

State addScaled(State y, State dy, float stepSize) {
    return State(y.x + stepSize * dy.x, y.p + stepSize * dy.p, y.t + stepSize * dy.t);
}

State flow(State y, Metric m, float pt) {
    // H_null = (|p|² - p_t²)/2 - H (-p_t + l_i p_i)².
    float q = dot(m.l, y.p) - pt;
    float inverseD = 1.0 / (m.r * m.r + SPIN * SPIN);
    float invR = 1.0 / m.r;
    vec2 gradXY = (-m.r * y.p.xy + SPIN * vec2(-y.p.y, y.p.x)) * inverseD;
    float radial = (-dot(y.x.xy, y.p.xy) - 2.0 * m.r * dot(m.l.xy, y.p.xy)) * inverseD
                 + y.x.z * y.p.z * invR * invR;
    vec3 dlp = vec3(gradXY, -y.p.z * invR) + radial * m.dr;
    float coupling = 2.0 * m.h * q;
    return State(y.p - coupling * m.l, q * q * m.dh + coupling * dlp, -pt + coupling);
}

State observerRay(vec3 position, vec3 direction, vec3 velocity, Metric m, out float pt) {
    // Boost the past-directed ray (-1, direction) from the camera tetrad
    // to the Eulerian tetrad before constructing its canonical momentum.
    float energy = 1.0;
    vec3 momentum = direction;
    float beta2 = dot(velocity, velocity);
    if (beta2 > 0.0) {
        float gamma = inversesqrt(1.0 - beta2);
        float along = dot(velocity, direction);
        energy = gamma * (1.0 - along);
        momentum += (gamma * (gamma * along / (gamma + 1.0) - 1.0)) * velocity;
    }
    float root = sqrt(1.0 + 2.0 * m.h);
    vec3 p = momentum + (root - 1.0) * dot(m.l, momentum) * m.l;
    pt = energy / root + (2.0 * m.h / (root * root)) * dot(m.l, p);
    return State(position, p, 0.0);
}

vec2 angularConstants(State y, Metric m, float pt) {
    float lz = y.x.x * y.p.y - y.x.y * y.p.x;
    float cylindrical = length(y.x.xy);
    vec2 radial = cylindrical > 1e-6 ? y.x.xy / cylindrical : vec2(1.0, 0.0);
    float d = sqrt(m.r * m.r + SPIN * SPIN);
    float sinTheta = cylindrical / d;
    float pTheta = d * (y.x.z / m.r) * dot(radial, y.p.xy) - m.r * sinTheta * y.p.z;
    float projectedLz = d * (radial.x * y.p.y - radial.y * y.p.x);
    float term = projectedLz + SPIN * pt * sinTheta;
    return vec2(lz, pTheta * pTheta + term * term);
}

#ifdef KERR_CHECK_INVARIANTS
vec3 constants(State y, Metric m, float pt) {
    float q = dot(m.l, y.p) - pt;
    return vec3(0.5 * (dot(y.p, y.p) - pt * pt) - m.h * q * q, angularConstants(y, m, pt));
}
#endif

// Outside the horizon, radial turning points are zeros of the Carter quartic.
// Positive Bernstein coefficients certify R>0 everywhere on [r+,r]. An inward
// ray already below ISCO then reaches the horizon without encountering any gas.
bool reachesHorizon(float r, float pt, vec2 angular) {
    float k = angular.y, e2 = pt * pt;
    float d = SPIN * (SPIN * pt + angular.x);
    float b = 2.0 * pt * d - k;
    float h = r - HORIZON, r0 = HORIZON, r02 = r0 * r0, r2 = r * r;
    float p0 = pt * r02 + d, p1 = pt * r2 + d;
    float v0 = p0*p0, v4 = p1*p1 - (r-r0)*(r-(2.0-r0))*k;
    float slope0 = 4.0*e2*r02*r0 + 2.0*b*r0 + 2.0*k;
    float slope1 = 4.0*e2*r2*r + 2.0*b*r + 2.0*k;
    float v1 = v0 + .25*h*slope0;
    float v2 = v0 + .5*h*slope0 + h*h*(e2*r02 + b/6.0);
    float v3 = v4 - .25*h*slope1;
    float scale = 1.0 + e2*r2*r2 + abs(b)*r2 + 2.0*k*r + d*d;
    return min(min(v0,v1), min(v2,min(v3,v4))) > .0004 * scale;
}

float errorRatio(State y, State k1, State k2, State k3, State k4, float h, float tolerance) {
    // Bogacki–Shampine 3(2) pair. Stage four is reused on the next accepted step.
    vec3 xError = h * (-5.0 * k1.x + 6.0 * k2.x + 8.0 * k3.x - 9.0 * k4.x) / 72.0;
    vec3 pError = h * (-5.0 * k1.p + 6.0 * k2.p + 8.0 * k3.p - 9.0 * k4.p) / 72.0;
    float relativeX2 = dot(xError, xError) / max(1.0, dot(y.x, y.x));
    float relativeP2 = dot(pError, pError) / max(1.0, dot(y.p, y.p));
    return sqrt(max(relativeX2, relativeP2)) / tolerance;
}

// Antiderivative of the Gaussian vertical density used by the disk transfer.
float erfApprox(float x) {
    float a = abs(x);
    float inverse = 1.0 / (1.0 + a * (0.278393 + a * (0.230389 + a * (0.000972 + a * 0.078108))));
    return sign(x) * (1.0 - inverse * inverse * inverse * inverse);
}

// Smooth material-space fluctuations. Wrap the angular lattice across atan's seam.
float diskNoise(float phase, float logRadius, float sectors, float seed) {
    vec2 p = vec2(phase * (.15915494 * sectors), logRadius);
    vec2 cell = floor(p), f = fract(p);
    vec2 az = mod(cell.x + vec2(0.0, 1.0), sectors);
    vec4 h = fract((az.xyxy * .1031 + (cell.y + vec4(0,0,1,1)) * .11369 + seed) * .917);
    h *= h + 19.19;
    h = fract(h * (h + 2.73));
    f = f * f * (3.0 - 2.0 * f);
    return mix(mix(h.x, h.y, f.x), mix(h.z, h.w, f.x), f.y) - .5;
}

float emissivityPattern(float phase, float logRadius, float epoch, float filamentDetail) {
    float seed = fract(sin(0.917 * epoch + 4.12) * 15487.317) * 6.2831853;
    float eddy = sin(3.0 * phase - 5.4 * logRadius + seed);
    float lane = sin(2.0 * phase + 10.1 * logRadius + seed + 1.1 * eddy);
    float plasma = diskNoise(phase, 8.0 * logRadius + eddy, 10.0, seed);
    // Broad samples already suppress these frequencies entirely.
    if (filamentDetail <= 0.0) return 1.0 + .19 * lane + .09 * eddy + .52 * plasma;
    float filament = .5 + .5 * sin(5.0 * phase + 24.0 * logRadius - 1.7 * seed + 1.6 * eddy);
    float threads = diskNoise(phase + .09 * eddy, 26.0 * logRadius + 2.4 * plasma, 22.0, seed + .39);
    return 1.0 + .19 * lane + .09 * eddy + .52 * plasma
        + filamentDetail * (.24 * threads + (.11 + .06 * lane) * (2.0 * filament * filament - .75));
}

void transferSlice(State start, State end, float pt, float stepSize, inout vec4 light) {
    // Weight the source position by the vertical density. The nearest-endpoint
    // rule jumps by half a step when a grazing ray reverses vertical velocity.
    float hMid = max(.014, length(start.x.xy + end.x.xy) * .5 * uDiskPhysics.y);
    float za = start.x.z / hMid, zb = end.x.z / hMid;
    float dz = zb - za;
    if (min(abs(za), abs(zb)) > 5.0 && za * zb > 0.0) return;
    // Two-node Gaussian quadrature has a finite, symmetric grazing limit.
    float q0 = mix(za, zb, .21132487), q1 = mix(za, zb, .78867513);
    float mid = .21132487 + .57735026 / (1.0 + exp(clamp(q1*q1 - q0*q0, -60.0, 60.0)));
    if (abs(dz) > 1.0) {
        float integral = .88622693 * (erfApprox(zb) - erfApprox(za));
        if (abs(integral) > .00001) {
            float weightedZ = .5 * (exp(-za * za) - exp(-zb * zb)) / integral;
            mid = mix(mid, clamp((weightedZ - za) / dz, 0.0, 1.0), smoothstep(1.0, 2.0, abs(dz)));
        }
    }
    vec2 atXY = mix(start.x.xy, end.x.xy, mid);
    float r2 = dot(atXY, atXY) - SPIN * SPIN;
    if (r2 <= ISCO * ISCO || r2 >= uOuterDisk * uOuterDisk) return;
    float r = sqrt(r2);
    float height = max(0.014, r * uDiskPhysics.y);
    float a = start.x.z / height, b = end.x.z / height;
    if (min(abs(a), abs(b)) > 3.8 && a * b > 0.0) return;
    float vertical = abs(b - a) > 0.06
        ? abs(erfApprox(b) - erfApprox(a)) * 0.88622693 / abs(b - a)
        : exp(-0.25 * (a + b) * (a + b));

    float signedSpin = SPIN * ROTATION;
    float root = sqrt(r), r32 = r * root;
    float omega = ROTATION / (r32 + signedSpin);
    float rootOffset = root - 1.0;
    float circularNorm = rootOffset * rootOffset * (root + 2.0) + 2.0 * (signedSpin - 1.0);
    if (circularNorm <= 0.0) return;
    float timeComponent = (r32 + signedSpin) / sqrt(r32 * circularNorm);
    vec2 atMomentum = mix(start.p.xy, end.p.xy, mid);
    float axialMomentum = atXY.x * atMomentum.y - atXY.y * atMomentum.x;
    float frequency = timeComponent * (pt + omega * axialMomentum);
    if (!(frequency > 0.0)) return;

    // A zero-torque thin-disk temperature law with a Gaussian atmosphere and
    // circular equatorial rest velocities. The shear advects a bounded emissivity field.
    float angle = atan(atXY.y, atXY.x);
    float timeWithinBlock = uTime.x + mix(start.t, end.t, mid);
    float blockOffset = floor(timeWithinBlock / 64.0);
    float epoch = uTime.y + blockOffset;
    float age = timeWithinBlock - 64.0 * blockOffset;
    float logR = log(r);
    // Advect the texture inward while circular Kerr velocities set the frequency shift.
    // Fade narrow filaments when one transfer slice spans a substantial fraction of a lane.
    float inflow = .012 / r;
    float filamentDetail = 1.0 - smoothstep(.10, .28, length(end.x.xy - start.x.xy) / r);
    float turbulence = mix(emissivityPattern(angle - omega * (age + 64.0), logR + inflow * (age + 64.0), epoch - 1.0, filamentDetail),
        emissivityPattern(angle - omega * age, logR + inflow * age, epoch, filamentDetail), smoothstep(0.0, 64.0, age));
    float edge = (1.0 - smoothstep(.8 * uOuterDisk, uOuterDisk, r)) * smoothstep(ISCO, ISCO + 0.20, r);
    float innerEdge = sqrt(ISCO / r);
    float index = 127.0 * sqrt(clamp((r - ISCO) / (uOuterDisk - ISCO), 0.0, 1.0));
    int i = min(126, int(index));
    float sqrtFlux = mix(uDiskSqrtFlux[i], uDiskSqrtFlux[i + 1], index - float(i));
    float flux = uDiskPhysics.x * sqrtFlux * sqrtFlux;
    float temperature = uDiskPhysics.z * sqrt(sqrt(1800.0 * flux))
        * (1.0 + uDiskHeat * .42 * innerEdge) * (.70 + .30 * turbulence);
    // Invariance of I_nu/nu³ yields B_nu(gT) for thermal emission. Three display
    // bands sample the Planck spectrum; this is a display false-color approximation.
    vec3 exponent = min(vec3(80.0), vec3(22100.0, 26650.0, 31950.0) * (frequency / max(temperature, 1.0)));
    vec3 source = vec3(1.0, 1.58, 2.35) / (exp(max(exponent, vec3(0.0001))) - 1.0);
    float radialDensity = 6.0 / r;
    float density = uDiskPhysics.w * uDiskPhysics.x * turbulence * edge
        * radialDensity * (0.44 + 0.56 * radialDensity);
    float tau = density * vertical * frequency * stepSize;
    float absorbed = tau < .01 ? tau * (1.0 - .5 * tau) : 1.0 - exp(-tau);
    light.rgb += light.a * absorbed * source * (uDiskGain * 15.0);
    light.a *= 1.0 - absorbed;
}

void transferDisk(State start, State end, State first, State last, float pt, float h, inout vec4 light) {
    if (uDiskGain <= 0.0 || uDiskPhysics.x <= 0.0 || uDiskPhysics.w <= 0.0 || light.a < .00005) return;
    float startR2 = dot(start.x.xy, start.x.xy);
    // A cubic Hermite segment lies inside its four Bezier control points'
    // convex hull. Bound its atmosphere and both radial edges before subdividing.
    vec3 b1 = start.x + (h / 3.0) * first.x, b2 = end.x - (h / 3.0) * last.x;
    vec3 lo = min(min(start.x, end.x), min(b1, b2));
    vec3 hi = max(max(start.x, end.x), max(b1, b2));
    float minHeight = max(lo.z, -hi.z);
    if (minHeight > 4.0 * uOuterDisk * uDiskPhysics.y) return;
    float spin2 = SPIN * SPIN;
    vec2 nearest = max(vec2(0.0), max(lo.xy, -hi.xy));
    if (dot(nearest, nearest) >= uOuterDisk * uOuterDisk + spin2) return;
    float maxR2 = max(max(startR2, dot(end.x.xy, end.x.xy)), max(dot(b1.xy, b1.xy), dot(b2.xy, b2.xy)));
    if (maxR2 <= ISCO * ISCO + spin2) return;
    float maxR = min(uOuterDisk, sqrt(maxR2));
    if (minHeight > 4.0 * max(.014, maxR * uDiskPhysics.y)) return;
    // Hermite dense output retains curved grazing intersections. Transfer has
    // its own quadrature scale; a small geodesic error alone cannot resolve ISCO.
    float reach = length(end.x - start.x);
    int slices = int(clamp(ceil(reach / max(.12, .06 * sqrt(startR2))), 1.0, 12.0));
    float sliceStep = h / float(slices);
    State prev = start;
    for (int i = 1; i <= slices; i++) {
        State next = end;
        if (i < slices) {
            float t = float(i) / float(slices);
            float w = t * t * (3.0 - 2.0 * t);
            float wa = h * t * (1.0 - t) * (1.0 - t), wb = -h * t * t * (1.0 - t);
            next = State(mix(start.x, end.x, w) + wa * first.x + wb * last.x,
                         mix(start.p, end.p, w) + wa * first.p + wb * last.p,
                         mix(start.t, end.t, w) + wa * first.t + wb * last.t);
        }
        transferSlice(prev, next, pt, sliceStep, light);
        prev = next;
    }
}

const float WHITE_DISK_MAX = 25.0;
const float WHITE_JET_MAX = 38.0;
const float WHITE_SHELL_MAX = 39.0;

struct WhiteEmission {
    vec4 style;
    vec4 olderRadii;
    vec4 olderGains;
    float latestRadius;
    float latestGain;
    float bound;
    bool shells;
};

float whiteDiskThickness(float r, float style) {
    return max(.10, clamp(style, .10, 5.0) * (.20 + .030 * r + .0026 * r * r));
}

float whiteJetWidth(float z) {
    return .21 + .018 * z + .0012 * z * z;
}

WhiteEmission whiteEmission() {
    vec4 style = all(equal(uWhiteStyle, vec4(0.0))) ? vec4(1.0, 1.0, 0.0, 1.0) : uWhiteStyle;
    float radius = HORIZON + 1.7 + 10.0 * max(0.0, uWhiteTime.y);
    float latestGain = clamp(uWhiteTime.w, 0.0, 4.0) * smoothstep(0.0, .20, uWhiteTime.y)
                     * (1.0 - smoothstep(26.0, 36.0, radius));
    vec4 ages = vec4(uWhiteExtraBursts0.xz, uWhiteExtraBursts1.xz);
    vec4 olderRadii = HORIZON + 1.7 + 10.0 * max(vec4(0.0), ages);
    vec4 olderGains = clamp(vec4(uWhiteExtraBursts0.yw, uWhiteExtraBursts1.yw), 0.0, 4.0)
                    * smoothstep(vec4(0.0), vec4(.20), ages)
                    * (1.0 - smoothstep(vec4(26.0), vec4(36.0), olderRadii));
    bool shells = latestGain > 0.0 || any(greaterThan(olderGains, vec4(0.0)));
    float bound = shells ? WHITE_SHELL_MAX : uWhiteTime.z > 0.0 ? WHITE_JET_MAX : WHITE_DISK_MAX;
    if (uWhiteParticleGain > 0.0) bound = max(bound, 30.0);
    return WhiteEmission(style, olderRadii, olderGains, radius, latestGain, bound, shells);
}

vec3 whitePalette(float index) {
    if (index < 0.5) return vec3(.12, .70, .91);   // platinum
    if (index < 1.5) return vec3(.14, .40, 1.0);   // blue
    if (index < 2.5) return vec3(1.0, .52, .14);   // gold
    if (index < 3.5) return vec3(.55, .24, 1.0);   // violet
    return vec3(1.0);
}

float whiteHash(vec3 cell) {
    vec3 h = fract(cell * vec3(.1031, .11369, .13787));
    h += dot(h, h.yxz + 19.19);
    return fract((h.x + h.y) * h.z);
}

float whiteNoise(vec3 p) {
    vec3 cell = floor(p), f = fract(p);
    f = f * f * (3.0 - 2.0 * f);
    vec4 front = vec4(whiteHash(cell), whiteHash(cell + vec3(1,0,0)),
                      whiteHash(cell + vec3(0,1,0)), whiteHash(cell + vec3(1,1,0)));
    cell.z += 1.0;
    vec4 back = vec4(whiteHash(cell), whiteHash(cell + vec3(1,0,0)),
                     whiteHash(cell + vec3(0,1,0)), whiteHash(cell + vec3(1,1,0)));
    vec4 layer = mix(front, back, f.z);
    return mix(mix(layer.x, layer.y, f.x), mix(layer.z, layer.w, f.x), f.y);
}

vec3 whiteThermalBands(float frequency, float temperature) {
    vec3 exponent = clamp(vec3(22100.0, 26650.0, 31950.0) * frequency / temperature, .0001, 80.0);
    vec3 bands = vec3(1.0, 1.58, 2.35) / (exp(exponent) - 1.0);
    return bands / max(1e-25, max(bands.r, max(bands.g, bands.b)));
}

float whiteShellProfile(float shapeRadius, float shellRadius) {
    float d = (shapeRadius - shellRadius) / (.28 + .030 * shellRadius);
    float second = d + 1.9;
    return exp(-d * d) + .25 * exp(-6.0 * second * second);
}

vec2 whiteParticleUv(vec2 voxel, float slice) {
    vec2 tile = vec2(mod(slice, 8.0), floor(slice / 8.0));
    return (tile * 64.0 + voxel + .5) / 512.0;
}

float whiteParticleDensity(vec3 position) {
    if (uWhiteParticleGain <= 0.0) return 0.0;
    float edge = length(position);
    if (edge >= 30.0) return 0.0;
    vec3 voxel = clamp((position / 60.0 + .5) * 63.0, 0.0, 63.0);
    float lower = floor(voxel.z);
    float nearSlice = texture(WhiteParticleGrid, whiteParticleUv(voxel.xy, lower)).r;
    float farSlice = texture(WhiteParticleGrid, whiteParticleUv(voxel.xy, min(63.0, lower + 1.0))).r;
    return mix(nearSlice, farSlice, fract(voxel.z)) * (1.0 - smoothstep(26.0, 30.0, edge)) * uWhiteParticleGain;
}

void transferWhiteSample(State sampleState, float pt, float affineLength, WhiteEmission emission,
                         int field, inout vec4 light) {
    vec3 x = sampleState.x;
    float r = radius(x);
    // The past horizon is the endpoint of the backwards ray. Emission belongs
    // to a regular exterior volume whose density tapers to zero there.
    if (r <= HORIZON || r >= WHITE_SHELL_MAX) return;
    float z = abs(x.z), rho = length(x.xy), fromHorizon = r - HORIZON;
    float nearEdge = smoothstep(.015, .60, fromHorizon);
    float seed = 6.2831853 * (uWhiteHole.w / 65536.0);
    float time = uWhiteTime.x;
    float beta = clamp(uWhiteHole.y, 0.0, .995);
    float flowSpeed = .38 + 3.10 * beta; // display-scale r_g / simulation second
    vec4 style = emission.style;
    bool primary = field >= 0;
    bool secondary = uWhitePrimary > .5 && !primary;
    float core = 0.0, disk = 0.0, jet = 0.0, shell = 0.0;
    if (field <= 0 && fromHorizon < 3.8 && style.w > 0.0) {
        float wave = sin(5.1 * fromHorizon - 2.6 * time + seed + 2.8 * x.z);
        vec3 coreCoordinate = 1.58 * x - normalize(x) * (.97 * time) + seed;
        float veins = .59 + .17 * wave + .24 * whiteNoise(coreCoordinate);
        float bright = exp(-pow(fromHorizon / (primary ? .78 : 1.02), primary ? 1.85 : 1.60));
        float shoulder = primary ? (fromHorizon - 1.31) / .56 : (fromHorizon - 1.63) / .96;
        float ionHalo = (primary ? .070 : .15) * exp(-shoulder * shoulder);
        float coreTaper = primary ? smoothstep(.015, .39, fromHorizon) : nearEdge;
        core = style.w * veins * (bright + ionHalo) * coreTaper
             * (1.0 - smoothstep(2.5, 3.8, fromHorizon));
        if (primary) {
            float facing = max(0.0, -dot(sampleState.p, normalize(x)));
            core *= 1.0 + facing * facing;
        }
    }
    if ((!primary || field == 1) && r < WHITE_DISK_MAX && style.x > 0.0) {
        float thickness = whiteDiskThickness(r, style.y);
        if (z < 4.0 * thickness && (!primary || z / thickness < 2.95)) {
            float angle = rho > .0001 ? atan(x.y, x.x) : 0.0;
            // Trace the density pattern back to its launch phase. The flow speed
            // changes the retarded time while integrated angular shear winds the plume.
            float travel = fromHorizon / flowSpeed;
            float launch = time - travel;
            float winding = ROTATION * (6.4 + 2.5 * SPIN * ROTATION)
                          * (inversesqrt(HORIZON + .5) - inversesqrt(r + .5)) / flowSpeed;
            float phi = angle - winding - ROTATION * .56 * launch + seed;
            vec2 azimuth = vec2(cos(phi), sin(phi));
            vec3 advected = vec3(azimuth * (1.5 + .24 * r), .82 * x.z / thickness + .46 * launch);
            float clump = whiteNoise(advected + vec3(seed, 1.9 * seed, 5.1 * seed));
            float granule = whiteNoise(advected * 2.81 + vec3(4.4, 5.8, 7.2) * clump);
            float warp = sin(3.0 * phi + .33 * r + x.z / thickness) + 1.55 * (clump - .5);
            float plume = .5 + .5 * sin(5.0 * phi + .92 * warp + .60 * clump);
            float threads = .5 + .5 * sin(13.0 * phi + 1.37 * r + 1.2 * warp + 3.2 * granule);
            float strata = .5 + .5 * sin(26.0 * phi + 2.85 * r - 2.2 * threads + x.z / thickness);
            if (primary) {
                // Filter unresolved longitudinal variation while preserving
                // filaments perpendicular to the ray, even at grazing angles.
                float angularRate = (x.x * sampleState.p.y - x.y * sampleState.p.x) / max(.2, rho * rho);
                float radialRate = dot(x, sampleState.p) / r;
                float threadSpan = abs(13.0 * angularRate + 1.37 * radialRate) * affineLength;
                float stratumSpan = abs(26.0 * angularRate + 2.85 * radialRate + sampleState.p.z / thickness) * affineLength;
                threads = mix(.5, threads, exp(-.07 * threadSpan * threadSpan));
                strata = mix(.5, strata, exp(-.12 * stratumSpan * stratumSpan));
            }
            float density = primary
                ? .032 + .52 * plume * plume * clump + .74 * pow(threads, 4.0) * granule + .22 * strata * strata * granule
                : .07 + .69 * plume * plume * clump + .44 * threads * threads * granule + .08 * strata * granule;
            density = mix(.32, density, smoothstep(0.0, .55, rho));
            float height = (x.z - .30 * thickness * warp) / thickness;
            float boundary = primary ? 1.0 - smoothstep(2.3, 2.95, z / thickness) : 1.0;
            disk = style.x * density * exp(-height * height) * boundary * smoothstep(.72, 2.35, fromHorizon)
                 * exp(-fromHorizon / 10.0) * (1.0 - smoothstep(16.0, WHITE_DISK_MAX, r));
        }
    }
    if (!secondary && (!primary || field == 2 || field == 3)
            && uWhiteTime.z > 0.0 && z > HORIZON + .20 && z < WHITE_JET_MAX) {
        float width = whiteJetWidth(z);
        if (rho < 4.0 * width) {
            float angle = rho > .0001 ? atan(x.y, x.x) : 0.0;
            float winding = sign(x.z) * (.82 * z - 1.8 * time) + seed;
            float rootFlex = smoothstep(HORIZON + 1.5, HORIZON + 8.0, z);
            vec2 spine = width * .22 * rootFlex * vec2(cos(winding), sin(winding));
            vec2 p = (x.xy - spine) / width;
            float d2 = dot(p, p);
            float azimuthalKnots = .05 * sin(3.0 * angle - winding + 1.2 * z) * smoothstep(0.0, width, rho);
            float axialKnots = .87 + .08 * sin(2.6 * z - 5.4 * time + seed);
            // The host's bright spine follows the machine axis. Its sheath
            // can twist and flicker without moving the launch axis.
            float spineD2 = primary ? rho * rho / (width * width) : d2;
            float jetDensity = 1.50 * exp(-(primary ? 6.8 : 3.80) * spineD2)
                             * (axialKnots + (primary ? 0.0 : azimuthalKnots))
                             + (primary ? .039 : .09) * exp(-(primary ? .94 : .51) * d2)
                             * (axialKnots + azimuthalKnots);
            jet = uWhiteTime.z * jetDensity
                * smoothstep(HORIZON + .20, HORIZON + 1.5, z) * exp(-z / 19.0)
                * (1.0 - smoothstep(28.0, WHITE_JET_MAX, z));
        }
    }
    if ((!primary || field == 4) && emission.shells) {
        float angle = rho > .0001 ? atan(x.y, x.x) : 0.0;
        float ripple = whiteNoise(.29 * x + vec3(seed, .3 * seed, .7 * seed));
        float shapeRadius = r + .17 * x.z * x.z / r - .14 * x.x + .76 * (ripple - .50);
        float pattern = .56 + .30 * sin(4.0 * angle + .8 * x.z + seed) * sin(.44 * x.x - .31 * x.y) + .14 * ripple;
        float fronts = emission.latestGain * whiteShellProfile(shapeRadius, emission.latestRadius);
        for (int i = 0; i < 4; ++i) {
            if (emission.olderGains[i] > 0.0) {
                fronts += emission.olderGains[i] * whiteShellProfile(shapeRadius, emission.olderRadii[i]);
            }
        }
        shell = fronts * pattern * (1.0 - smoothstep(32.0, WHITE_SHELL_MAX, r));
    }
    float particles = !secondary && (!primary || field == 4) ? whiteParticleDensity(x) * nearEdge : 0.0;
    float density = core + disk + jet + shell + particles;
    if (density < .00004) return;

    // Direct world rays use a local lapse and Doppler approximation. The
    // curved image uses p_mu U^mu from its integrated photon momentum.
    float eulerFrequency = sqrt(1.0 + 2.0 / r);
    vec3 localMomentum = sampleState.p;
    vec3 radial = normalize(x);
    if (!primary) {
        Metric m = metric(x);
        float root = sqrt(1.0 + 2.0 * m.h);
        float lDotP = dot(m.l, sampleState.p);
        eulerFrequency = root * pt - 2.0 * m.h * lDotP / root;
        localMomentum += (1.0 / root - 1.0) * lDotP * m.l;
        radial = normalize(m.dr + (1.0 / root - 1.0) * dot(m.l, m.dr) * m.l);
    }
    vec3 tangent = vec3(-x.y, x.x, 0.0) / max(rho, .0001);
    float swirl = ROTATION * .40 * inversesqrt(1.0 + .18 * fromHorizon);
    vec3 spiral = normalize(radial + tangent * swirl);
    vec3 outflow = beta * mix(radial, spiral, (disk + particles) / density);
    vec3 polar = vec3(0.0, 0.0, sign(x.z)) * (.50 + .48 * beta);
    vec3 velocity = mix(outflow, polar, jet / density);
    float frequency = (eulerFrequency + dot(velocity, localMomentum))
                    * inversesqrt(max(.005, 1.0 - dot(velocity, velocity)));
    if (frequency <= .0) return;
    float g = 1.0 / frequency;

    float absorption = .41 * core + .068 * disk + .18 * jet + .007 * shell + .015 * particles;
    float properLength = primary ? (frequency / eulerFrequency) * affineLength : frequency * affineLength;
    float tau = absorption * properLength * clamp(uWhiteHole.x, 0.0, 1.0);
    float absorbed = tau < .01 ? tau * (1.0 - .5 * tau) : 1.0 - exp(-tau);
    float outer = smoothstep(1.0, 15.0, fromHorizon);
    float spectrum = clamp(style.z, 0.0, 5.0);
    vec3 palette = mix(whitePalette(floor(spectrum)), whitePalette(ceil(spectrum)), fract(spectrum));
    float temperatureScale = clamp(uDiskPhysics.z / 9400.0, .20, 7.0);
    float coreEdgeHue = smoothstep(.65, 1.9, fromHorizon);
    vec3 coreColor = mix(vec3(1.0, .98, .93), vec3(.49, .85, 1.0), .70 * coreEdgeHue);
    vec3 diskColor = mix(vec3(1.0, .91, .77), palette, outer * .93);
    vec3 jetColor = mix(vec3(1.0, .96, .93), palette, .24 + .31 * outer);
    vec3 shellColor = mix(vec3(1.0), palette, .66);
    float grayscale = max(0.0, 1.0 - abs(spectrum - 4.0));
    if (grayscale > 0.0) {
        coreColor = mix(coreColor, vec3(1.0), grayscale);
        diskColor = mix(diskColor, vec3(1.0), grayscale);
        jetColor = mix(jetColor, vec3(1.0), grayscale);
        shellColor = mix(shellColor, vec3(1.0), grayscale);
    }
    if (spectrum > 4.0) {
        // Approximate the band ratios only. The chosen emissivity controls
        // the integrated power independently of this display spectrum.
        float physical = spectrum - 4.0;
        if (core > .0) coreColor = mix(coreColor, whiteThermalBands(frequency, mix(32000.0, 14000.0, outer) * temperatureScale), physical);
        if (disk > .0) diskColor = mix(diskColor, whiteThermalBands(frequency, mix(16500.0, 4100.0, outer) * temperatureScale), physical);
        if (jet > .0) jetColor = mix(jetColor, whiteThermalBands(frequency, mix(28000.0, 10500.0, outer) * temperatureScale), physical);
        if (shell > .0) shellColor = mix(shellColor, whiteThermalBands(frequency, mix(18500.0, 5000.0, outer) * temperatureScale), physical);
    }
    float hotDisk = disk > 0.0 ? exp(-.16 * (fromHorizon - 2.85) * (fromHorizon - 2.85)) : 0.0;
    vec3 emissivity = (core * 1.37 * coreColor + (disk * (.16 + 1.32 * hotDisk) + particles * .31) * diskColor
                    + jet * 2.8 * jetColor + shell * .033 * shellColor) * sqrt(temperatureScale);
    float shifted = g * g * g;
    light.rgb += light.a * min(vec3(64.0), emissivity * (shifted * uWhiteHole.z * absorbed / absorption));
    light.a *= 1.0 - absorbed;
}

void transferWhite(State start, State end, State first, State last, float pt, float h,
                   WhiteEmission emission, inout vec4 light) {
    if (uWhiteHole.z <= 0.0 || light.a < .00005) return;
    float reach = length(end.x - start.x);
    float startRadius = length(start.x);
    if (startRadius - reach > emission.bound + 1.0) return;
    // Curved-segment quadrature follows the same dense trajectory that makes
    // disk crossings and high-order images possible in black-hole mode.
    float spacing = max(.26, .095 * startRadius);
    if (uIntegration.y >= 480.0) spacing *= .70;
    int count = int(clamp(ceil(reach / spacing), 1.0, 8.0));
    float affineLength = h / float(count);
    for (int i = 0; i < 8; i++) {
        if (i >= count) break;
        float t = (float(i) + .5) / float(count);
        float w = t * t * (3.0 - 2.0 * t);
        float wa = h * t * (1.0 - t) * (1.0 - t), wb = -h * t * t * (1.0 - t);
        State at = State(mix(start.x, end.x, w) + wa * first.x + wb * last.x,
                         mix(start.p, end.p, w) + wa * first.p + wb * last.p,
                         mix(start.t, end.t, w) + wa * first.t + wb * last.t);
        transferWhiteSample(at, pt, affineLength, emission, -1, light);
    }
}

vec4 trace(vec3 position, vec3 direction, vec3 velocity, bool transferOnly, float toleranceCap, out vec4 light) {
    Metric m = metric(position);
    float pt;
    State y = observerRay(position, direction, velocity, m, pt);
    State k1 = flow(y, m, pt);
    light = vec4(0.0, 0.0, 0.0, 1.0);
#ifdef KERR_BLACK_ONLY
    // Pure Kerr traces can compile out white-hole emission and its ray state.
    const float white = 0.0;
#else
    float white = clamp(uWhiteHole.x, 0.0, 1.0);
#endif
    // The white-hole scene uses exterior observers only.
    if (white > 0.0 && m.r <= HORIZON) return vec4(direction, 1.0);
    WhiteEmission emission;
    bool secondary = white > 0.0 && uWhitePrimary > .5;
    if (white > 0.0) emission = whiteEmission();
    if (secondary) {
        emission.style.w = 0.0;
        emission.shells = false;
        emission.bound = 10.0;
    }
    float sweep = 0.0;
#ifdef KERR_CHECK_INVARIANTS
    vec3 initial = constants(y, m, pt);
#else
    vec2 angular = angularConstants(y, m, pt);
#endif
    // Extremal r+ and r- coincide. The regular chart never divides by Delta;
    // a wider termination tolerance controls float error in its near-critical throat.
    float captureRadius = HORIZON + (abs(SPIN) >= 0.9995 ? 0.00036 : 0.00006);
    float escapeRadius = max(70.0, min(160.0, m.r));
    // Footprint baking needs disk transfer and capture only. Beyond the
    // emitting atmosphere an outward ray has also cleared the photon region.
    float emissionRadius = white > 0.0 ? emission.bound + 1.0
        : 1.0 + uOuterDisk * length(vec2(1.0, 5.0 * uDiskPhysics.y));
    escapeRadius = transferOnly ? emissionRadius : max(escapeRadius, emissionRadius);
#ifndef KERR_CHECK_INVARIANTS
    // A negative Carter radial potential at the emission bound forbids any crossing.
    // The transfer cache can use this result directly because it never samples the outgoing direction.
    if (transferOnly && white == 0.0 && m.r > escapeRadius) {
        float p = pt * (escapeRadius * escapeRadius + SPIN * SPIN) + SPIN * angular.x;
        float barrier = (escapeRadius - HORIZON) * (escapeRadius - (2.0 - HORIZON)) * angular.y;
        if (p * p < .999 * barrier) return vec4(0.0, 0.0, 0.0, 1.0);
    }
#endif
    float stepSize = 0.20 * m.r;
    // Close observers magnify errors in the location of the first few disk
    // samples. Match step control continuously to that observing distance.
    float tolerance = min(toleranceCap, mix(min(uIntegration.x, .000002), uIntegration.x, smoothstep(6.0, 16.0, m.r)));
    int budget = int(uIntegration.y);
    for (int i = 0; i < 768; ++i) {
        if (m.r <= captureRadius) return vec4(0.0, 0.0, 0.0, 2.0);
        float radialSpeed = dot(m.dr, k1.x);
#if !defined(KERR_CHECK_INVARIANTS) && !defined(KERR_FULL_CAPTURE)
        if (white == 0.0 && m.r < ISCO && radialSpeed < -.05 && reachesHorizon(m.r, pt, angular))
            return vec4(0.0, 0.0, 0.0, 2.0);
#endif
        if (m.r >= escapeRadius && radialSpeed > 0.0) return vec4(normalize(k1.x), 1.0);
        if (i >= budget || any(isnan(y.p)) || any(isinf(y.p))) break;

        float spatialLimit = 0.36 * m.r / max(0.75, length(k1.x));
        float radialLimit = 0.55 * max(0.10, m.r - HORIZON) / max(0.25, abs(radialSpeed));
        float stepLimit = min(spatialLimit, radialLimit);
        stepSize = clamp(stepSize, 0.00008, max(0.00008, stepLimit));
        State midpoint = addScaled(y, k1, 0.5 * stepSize);
        State k2 = flow(midpoint, metric(midpoint.x), pt);
        State stage = addScaled(y, k2, 0.75 * stepSize);
        State k3 = flow(stage, metric(stage.x), pt);
        State average = State((2.0 * k1.x + 3.0 * k2.x + 4.0 * k3.x) / 9.0,
                              (2.0 * k1.p + 3.0 * k2.p + 4.0 * k3.p) / 9.0,
                              (2.0 * k1.t + 3.0 * k2.t + 4.0 * k3.t) / 9.0);
        State next = addScaled(y, average, stepSize);
        Metric nextMetric = metric(next.x);
        State k4 = flow(next, nextMetric, pt);
        float error = errorRatio(next, k1, k2, k3, k4, stepSize, tolerance);
        float factor = clamp(0.90 * pow(max(error, 1e-7), -0.33333333), 0.15, 2.5);
        if (error <= 1.0) {
#ifdef KERR_CHECK_INVARIANTS
            vec3 drift = abs(constants(next, nextMetric, pt) - initial) / max(abs(initial), vec3(1.0));
            light.rgb = max(light.rgb, drift);
#else
            if (white == 0.0) {
                transferDisk(y, next, k1, k4, pt, stepSize, light);
            } else if (!secondary) {
                transferWhite(y, next, k1, k4, pt, stepSize, emission, light);
            } else if (uWhiteHole.z > 0.0 && emission.style.x > 0.0 && min(m.r, nextMetric.r) < 10.0) {
                // Only the inner disk image after a substantial turn belongs
                // to the bent pass. It cannot draw a second projected spine.
                sweep += atan(length(cross(y.x, next.x)), dot(y.x, next.x));
                if (sweep > 2.1) {
                    vec4 indirect = vec4(0.0, 0.0, 0.0, 1.0);
                    transferWhite(y, next, k1, k4, pt, stepSize, emission, indirect);
                    light.rgb += light.a * indirect.rgb * smoothstep(2.1, 2.8, sweep);
                    light.a *= indirect.a;
                }
            }
#endif
            y = next;
            k1 = k4;
            m = nextMetric;
        }
        stepSize *= factor;
    }
    return vec4(0.0);
}

void cameraRay(vec2 px, out mat3 basis, out vec3 observer, out vec3 localDirection) {
    vec2 ndc = px / uScreenSize * 2.0 - 1.0;
    // Minecraft includes walking translation in the projection. Unproject the
    // eye (the common point of the pixel rays) and a point on the near plane.
    vec3 eye = uInvProj[2].xyz / uInvProj[2].w;
    vec4 nearPoint = uInvProj * vec4(ndc, -1.0, 1.0);
    vec3 direction = normalize(nearPoint.xyz - eye * nearPoint.w);
    vec3 z = normalize(uDiskNormal);
    vec3 x = normalize(uEquatorialX - z * dot(z, uEquatorialX));
    basis = mat3(x, cross(z, x), z);
    observer = transpose(basis) * ((eye - uBhView) * (2.0 / uRs));
    localDirection = transpose(basis) * direction;
}

// The exterior allowed interval of the radial Carter potential connects to
// infinity; the interior interval connects to the horizon. Its only possible
// exterior barrier contains the local minimum, the larger positive root of R'.
// Negative inside the shadow. Factor R(r) - R(rMin) at the stationary point
// so the sign stays continuous for observers on either side of the photon orbit.
float shadowMargin(vec3 observer, vec3 direction, vec3 velocity) {
    Metric m = metric(observer);
    float pt;
    State y = observerRay(observer, direction, velocity, m, pt);
    if (pt <= 0.0) return -1.0;
    float dr = dot(m.dr, flow(y, m, pt).x);
    vec2 angular = angularConstants(y, m, pt);
    float k = angular.y, d = SPIN * (SPIN * pt + angular.x);
    float e2 = pt * pt, b = 2.0 * pt * d - k;
    if (b < 0.0) {
        float s = sqrt(-b / (6.0 * e2));
        float denom = 4.0 * e2 * s * s * s;
        if (denom > k && denom > 0.0) {
            float rMin = 2.0 * s * cos(acos(clamp(-k / denom, -1.0, 1.0)) / 3.0);
            if (rMin > HORIZON) {
                float p = pt * rMin * rMin + d;
                float delta = (rMin - HORIZON) * (rMin - (2.0 - HORIZON));
                float radial = dr * (m.r / m.h); // Sigma dr/dlambda
                float barrier = (m.r - rMin) * sqrt(max(0.0,
                    e2 * (m.r*m.r + 2.0*m.r*rMin + 3.0*rMin*rMin) + b));
                float scale = max(1.0, p*p + delta*k);
                // Rationalize the sum when the terms oppose. Direct addition
                // loses the silhouette to cancellation for distant observers.
                if (radial * barrier < 0.0)
                    return sign(radial) * (p*p - delta*k) / scale;
                return (radial + barrier) * max(abs(radial) + abs(barrier), .0001) / scale;
            }
        }
    }
    return dr < 0.0 ? -1.0 : 1.0;
}

float shadowMargin(vec2 px) {
    mat3 basis;
    vec3 observer, direction;
    cameraRay(px, basis, observer, direction);
    return shadowMargin(observer, direction, transpose(basis) * vec3(0.0, 0.0, -uWhiteObserverBeta));
}

float capturedAt(vec2 px) {
    return float(shadowMargin(px) < 0.0);
}

vec4 tracePixel(vec2 px, bool transferOnly, float toleranceCap, out vec4 light) {
    mat3 basis;
    vec3 observer, direction;
    cameraRay(px, basis, observer, direction);
    vec3 velocity = transpose(basis) * vec3(0.0, 0.0, -uWhiteObserverBeta);
    vec4 ray = trace(observer, direction, velocity, transferOnly, toleranceCap, light);
    ray.xyz = basis * ray.xyz;
    return ray;
}

vec4 tracePixel(vec2 px, bool transferOnly, bool critical, out vec4 light) {
    return tracePixel(px, transferOnly, critical ? .000006 : uIntegration.x, light);
}

vec4 tracePixel(vec2 px, bool transferOnly, out vec4 light) {
    return tracePixel(px, transferOnly, false, light);
}
