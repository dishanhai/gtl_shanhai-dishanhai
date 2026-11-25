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
uniform vec2 uIntegration;    // relative tolerance and accepted/rejected step budget

#define SPIN uKerrParameters.x
#define HORIZON uKerrParameters.y
#define ISCO uKerrParameters.z
#define ROTATION uKerrParameters.w
const float OUTER_DISK = 18.0;

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

State observerRay(vec3 position, vec3 direction, Metric m, out float pt) {
    // Eulerian observer of the outgoing KS slices, using the symmetric spatial
    // orthonormal tetrad. This launch includes the observer's relativistic aberration.
    float root = sqrt(1.0 + 2.0 * m.h);
    vec3 p = direction + (root - 1.0) * dot(m.l, direction) * m.l;
    pt = 1.0 / root + (2.0 * m.h / (root * root)) * dot(m.l, p);
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

float emissivityPattern(float phase, float logRadius, float epoch) {
    float seed = fract(sin(0.917 * epoch + 4.12) * 15487.317) * 6.2831853;
    float ripple = sin(4.1 * logRadius + 2.0 * phase + seed);
    float ridge = sin(3.0 * phase + 6.2 * logRadius + seed + 0.73 * ripple);
    float filament = sin(7.0 * phase - 13.1 * logRadius - seed * 1.7 + 1.1 * ripple);
    return 1.0 + .38 * ridge + .23 * filament + .12 * ridge * filament
        + .13 * sin(11.0 * phase + 18.7 * logRadius + seed * 2.1);
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
    if (r2 <= ISCO * ISCO || r2 >= OUTER_DISK * OUTER_DISK) return;
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
    float turbulence = mix(emissivityPattern(angle - omega * (age + 64.0), logR, epoch - 1.0),
        emissivityPattern(angle - omega * age, logR, epoch), smoothstep(0.0, 64.0, age));
    float edge = (1.0 - smoothstep(11.5, OUTER_DISK, r)) * smoothstep(ISCO, ISCO + 0.20, r);
    float innerEdge = sqrt(ISCO / r);
    float flux = uDiskPhysics.x / (r * r * r) * max(0.0, 1.0 - innerEdge);
    float temperature = uDiskPhysics.z * sqrt(sqrt(1800.0 * flux))
        * (1.0 + uDiskHeat * .42 * innerEdge) * mix(.78, 1.22, .5 * turbulence);
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
    float reach = length(end.x - start.x);
    float startR2 = dot(start.x.xy, start.x.xy);
    float startR = sqrt(startR2);
    if (startR - reach > OUTER_DISK + 1.0 ||
        abs(start.x.z) - reach > 4.0 * uDiskPhysics.y * OUTER_DISK) return;
    // A cubic Hermite segment lies inside its four Bezier control points'
    // convex hull. Reject segments wholly inside the disk's inner edge or above its atmosphere.
    vec3 b1 = start.x + (h / 3.0) * first.x, b2 = end.x - (h / 3.0) * last.x;
    float maxR2 = max(max(startR2, dot(end.x.xy, end.x.xy)), max(dot(b1.xy, b1.xy), dot(b2.xy, b2.xy)));
    if (maxR2 < ISCO * ISCO + SPIN * SPIN - .001) return;
    float lowZ = min(min(start.x.z, end.x.z), min(b1.z, b2.z));
    float highZ = max(max(start.x.z, end.x.z), max(b1.z, b2.z));
    float maxR = min(OUTER_DISK, sqrt(maxR2));
    if (max(lowZ, -highZ) > 4.0 * max(.014, maxR * uDiskPhysics.y)) return;
    // Hermite dense output retains curved grazing intersections. Transfer has
    // its own quadrature scale; a small geodesic error alone cannot resolve ISCO.
    int slices = int(clamp(ceil(reach / max(.12, .06 * startR)), 1.0, 12.0));
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

vec4 trace(vec3 position, vec3 direction, bool transferOnly, out vec4 light) {
    Metric m = metric(position);
    float pt;
    State y = observerRay(position, direction, m, pt);
    State k1 = flow(y, m, pt);
    light = vec4(0.0, 0.0, 0.0, 1.0);
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
    if (transferOnly) escapeRadius = 1.0 + OUTER_DISK * length(vec2(1.0, 5.0 * uDiskPhysics.y));
    float stepSize = 0.20 * m.r;
    // Close observers magnify errors in the location of the first few disk
    // samples. Match step control continuously to that observing distance.
    float tolerance = mix(min(uIntegration.x, .000002), uIntegration.x, smoothstep(6.0, 16.0, m.r));
    int budget = int(uIntegration.y);
    for (int i = 0; i < 768; ++i) {
        if (m.r <= captureRadius) return vec4(0.0, 0.0, 0.0, 2.0);
        float radialSpeed = dot(m.dr, k1.x);
#if !defined(KERR_CHECK_INVARIANTS) && !defined(KERR_FULL_CAPTURE)
        if (m.r < ISCO && radialSpeed < -.05 && reachesHorizon(m.r, pt, angular))
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
            transferDisk(y, next, k1, k4, pt, stepSize, light);
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
float shadowMargin(vec2 px) {
    mat3 basis;
    vec3 observer, direction;
    cameraRay(px, basis, observer, direction);
    Metric m = metric(observer);
    float pt;
    State y = observerRay(observer, direction, m, pt);
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

float capturedAt(vec2 px) {
    return float(shadowMargin(px) < 0.0);
}

vec4 tracePixel(vec2 px, bool transferOnly, out vec4 light) {
    mat3 basis;
    vec3 observer, direction;
    cameraRay(px, basis, observer, direction);
    vec4 ray = trace(observer, direction, transferOnly, light);
    ray.xyz = basis * ray.xyz;
    return ray;
}
