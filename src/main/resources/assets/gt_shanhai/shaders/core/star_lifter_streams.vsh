#version 150

#moj_import <fog.glsl>

in vec3 Position;
in vec2 UV0;
in vec4 Color;

uniform mat4 ModelViewMat;
uniform mat4 ProjMat;
uniform mat4 Ring0;
uniform mat4 Ring1;
uniform mat4 Ring2;
uniform mat4 Ring3;
uniform mat4 Heading;
uniform vec4 RingRadius;
uniform vec4 RingWidth;
uniform vec4 RingHeight;
uniform vec4 ControlAnchor;
uniform vec4 Activity;   // ignition, high load, fault, live Guixu resonance
uniform vec4 Visibility; // visible ring bits, stellar detail, fixed control, collection
uniform vec2 CollectorAngle;
uniform float CollectorRadius;
uniform float StarRadius;
uniform float PlasmaPhase;
uniform int FogShape;

out vec4 vertexColor;
out float vertexDistance;

const float PI = 3.141592653589793;
const float TAU = 2.0 * PI;
const float GOLDEN_ANGLE = 2.39996323;
const vec3 GOLD = vec3(1.0, .81, .43);
const vec3 WHITE = vec3(1.0, .96, .82);
const vec3 CYAN = vec3(.23, .80, 1.0);
const vec3 VIOLET = vec3(.62, .34, 1.0);
const vec3 BLUE_WHITE = vec3(.67, .83, 1.0);
const vec3 UP = vec3(0.0, 1.0, 0.0);

mat3 ringRotation(int ring) {
    if (ring == 0) return mat3(Ring0);
    if (ring == 1) return mat3(Ring1);
    if (ring == 2) return mat3(Ring2);
    return mat3(Ring3);
}

bool ringVisible(int ring) {
    return (int(Visibility.x) & (1 << ring)) != 0;
}

vec3 radial(float angle) {
    return vec3(cos(angle), 0.0, sin(angle));
}

float peak(float phase) {
    return .5 + .5 * cos(phase);
}

float trackPhase() {
    return 4.0 * PlasmaPhase + Activity.y * .27 * sin(8.0 * PlasmaPhase);
}

float ringWake(int ring) {
    return smoothstep(.13 + .12 * float(ring), .56 + .09 * float(ring), Activity.x);
}

float captureWake(float index) {
    float wake = smoothstep(.47 + .045 * index, .80 + .035 * index, Activity.x);
    float allowed = index < 3.0 ? 1.0 : Activity.y;
    float dropout = smoothstep(.91, .995, peak(17.0 * PlasmaPhase + index * 1.57));
    return wake * allowed + Activity.z * dropout * (index == 0.0 ? .38 : 0.0);
}

float portAngle(float index) {
    // Three leading collectors and three evenly interleaved high-load collectors.
    return CollectorAngle.x + CollectorAngle.y * (4.0 * mod(index, 3.0) + 2.0 * step(3.0, index));
}

vec3 prominence(float t, float index, float edge, float axis, out float alpha) {
    float azimuth = PlasmaPhase + index * GOLDEN_ANGLE;
    float hemisphere = mod(index, 2.0) == 0.0 ? 1.0 : -1.0;
    float latitude = hemisphere * (.20 + .14 * floor(index * .5));
    vec3 ray = radial(azimuth) * cos(latitude) + UP * sin(latitude);
    vec3 east = radial(azimuth + PI * .5);
    vec3 across = .80 * east + (.60 * hemisphere) * cross(east, ray);
    vec3 side = cross(across, ray);
    float arch = max(0.0, sin(PI * t));
    float lift = .073 + .026 * sin(4.0 * PlasmaPhase + index * 1.72) + .037 * Activity.y;
    float angle = (t - .5) * (.34 + .045 * index);
    vec3 direction = cos(angle) * ray + sin(angle) * across;
    vec3 center = direction * (StarRadius - .003 + lift * arch)
            + side * (.018 * arch * sin(TAU * t + 3.0 * PlasmaPhase + index));
    alpha = arch * (.16 + .31 * peak(2.0 * TAU * t - 7.0 * PlasmaPhase + index));
    return center + edge * (.002 + .007 * arch) * (axis == 0.0 ? side : direction);
}

vec3 extraction(float t, float index, float edge, float axis, float filament, out float alpha, out vec3 tint) {
    float phase = index * GOLDEN_ANGLE;
    float bow = max(0.0, sin(PI * t));
    float angle = portAngle(index) + bow * (.56 + .18 * sin(8.0 * PlasmaPhase + phase - t * 3.0))
            - .11 * (1.0 - t) + filament * .33 * bow;
    float side = mod(index, 2.0) == 0.0 ? 1.0 : -1.0;
    float y = bow * side * (.138 + .050 * sin(11.0 * PlasmaPhase + phase - t * 3.4) - filament * .084);
    vec3 ray = radial(angle);
    float wave = peak(t * (TAU * 1.8) - 37.0 * PlasmaPhase + phase);
    float packet = wave * wave * wave;
    float width = (.008 + .018 * bow + .009 * packet) * (1.0 + .32 * Activity.y);
    if (filament > 0.0) width *= .26;
    alpha = captureWake(index) * (.74 + .26 * bow) * (.61 + .36 * packet);
    alpha *= 1.0 - Activity.z * smoothstep(.17, .60, t);
    if (filament > 0.0) alpha *= .40;
    tint = filament > 0.0 ? BLUE_WHITE : mix(GOLD, WHITE, .08 + .73 * packet);
    if (abs(edge) > .5) tint = CYAN;
    vec3 across = axis == 0.0 ? UP : cross(UP, ray);
    float tip = 1.0 - .24 * Activity.z;
    return mat3(Ring0) * (ray * mix(StarRadius - .002, CollectorRadius + .002, t * tip)
            + UP * y + edge * width * across);
}

vec3 transport(float t, int index, float edge, float axis, out float alpha, out vec3 tint) {
    int ring = index / 4;
    float lane = float(ring);
    // Pulse phase rolls outward from the collectors, with the fixed outer pickup at positive X.
    float phase = trackPhase();
    float angle = phase - .63 * lane + TAU * float(index % 4) * .25 - (.43 + .20 * ringWake(ring)) * t;
    float front = smoothstep(0.0, .14, t);
    float tail = 1.0 - t;
    float packet = mix(peak(PI * t - 9.0 * PlasmaPhase + lane * .6),
            peak(2.0 * PI * t - 17.0 * PlasmaPhase + lane * .6), Activity.y);
    float width = RingWidth[ring] * (.052 + .011 * Activity.y);
    vec3 point = radial(angle) * (RingRadius[ring] + edge * width)
            + UP * ((axis * 2.0 - 1.0) * RingHeight[ring] * .159);
    alpha = front * tail * (.22 + .63 * ringWake(ring)) * (ring == 3 ? .88 : ring == 0 ? 1.0 : .53);
    alpha *= 1.0 - .45 * Activity.z * peak(22.0 * PlasmaPhase + lane * 2.0);
    tint = mix(CYAN, GOLD, ringWake(ring) * packet * (ring == 3 ? .82 : ring == 0 ? .73 : .18));
    return ringRotation(ring) * point;
}

vec3 collectorSeal(float t, float index, float edge, out float alpha, out vec3 tint) {
    float angle = portAngle(index);
    vec3 ray = radial(angle);
    vec3 side = cross(UP, ray);
    float sweep = t * TAU;
    float charge = peak(4.0 * PlasmaPhase - angle + .7);
    float lock = smoothstep(.13, .64, Activity.x);
    float radius = .011 + lock * (.003 + .005 * peak(10.0 * PlasmaPhase - index));
    float pulse = mix(pow(peak(sweep - 11.0 * PlasmaPhase + index), 2.0),
            pow(peak(sweep - 19.0 * PlasmaPhase + index), 2.0), Activity.y);
    float wake = captureWake(index);
    alpha = (index < 3.0 ? .16 + lock * .12 : .13 * Activity.y) + wake * (.16 + .43 * pulse);
    alpha *= 1.0 - .66 * Activity.z * peak(27.0 * PlasmaPhase + index * 2.0);
    tint = mix(CYAN, mix(VIOLET, WHITE, charge), .43 + wake * .45);
    return mat3(Ring0) * (ray * (CollectorRadius - .002) + (UP * cos(sweep) + side * sin(sweep))
            * (radius + edge * (.0011 + wake * .0011)));
}

vec3 controlFlow(float t, int index, float edge, out float alpha, out vec3 tint) {
    bool resonance = index == 2 || index == 3;
    float side = index % 2 == 0 ? 1.0 : -1.0;
    float block = ControlAnchor.w;
    float intake = smoothstep(0.0, .37, Activity.x);
    float packet = pow(peak(TAU * t - (resonance ? 12.0 : 8.0) * PlasmaPhase + side), 3.0);
    vec3 point;
    if (index < 2) {
        // A thin lit face follows the spine above and below the unobstructed rim.
        point = vec3(mix(-18.0, .52, t), side * mix(7.51, 3.51, smoothstep(.73, 1.0, t)),
                side * 11.0 - ControlAnchor.z / block);
    } else if (resonance) {
        // This short local return path describes the paired endpoint, including across dimensions.
        float arch = sin(PI * t);
        point = vec3(.86 + 4.1 * arch, side * (1.0 + 2.6 * arch), side * mix(0.0, 7.0, t));
    } else if (index < 6) {
        point = vec3(.515, side * (.58 + edge * .09), mix(side * 8.0, 0.0, t));
    } else {
        point = vec3(.53, side * mix(3.52, .92, t), .75 * sin(TAU * t) + edge * .18);
    }
    point.y += edge * (resonance ? .30 : .12);
    alpha = sin(PI * t) * (resonance ? .29 + .58 * packet : .12 + .52 * packet);
    alpha *= resonance ? Activity.w : (.22 + .70 * intake);
    alpha *= 1.0 - .52 * Activity.z * peak(31.0 * PlasmaPhase + float(index));
    tint = resonance ? mix(vec3(.77, .47, 1.0), vec3(.89, .75, 1.0), packet)
            : mix(CYAN, GOLD, intake * packet * .80);
    return mat3(Heading) * (ControlAnchor.xyz + point * block);
}

vec3 fieldCoupling(float t, float index, float edge, out float alpha, out vec3 tint) {
    int lane = int(index) / 2;
    bool outer = (int(index) % 2) == 1;
    int ring = lane + 1;
    float angle = portAngle(float(lane)) + .22;
    vec3 root = mat3(Ring0) * radial(angle) * (outer ? RingRadius.x + RingWidth.x * .59 : StarRadius + .010);
    mat3 target = ringRotation(ring);
    vec3 localRoot = transpose(target) * normalize(root);
    vec3 lateral = vec3(localRoot.x, 0.0, localRoot.z) + .08 * radial(angle);
    vec3 tip = target * radial(atan(lateral.z, lateral.x) + (outer ? .13 : -.29))
            * (RingRadius[ring] - RingWidth[ring] * .20);
    vec3 normal = target * UP;
    float bow = sin(PI * t);
    float signal = pow(peak(TAU * t * 1.4 - 11.0 * PlasmaPhase + index), 3.0);
    float open = smoothstep(.55, .93, Activity.x) * (outer ? 1.0 : .48 + Activity.y * .52);
    if (Activity.z > 0.0) {
        open = smoothstep(.89, .995, peak(19.0 * PlasmaPhase + index)) * .61
                * smoothstep(.20, .65, peak(TAU * t + index));
    }
    alpha = bow * (.13 + .40 * signal) * open;
    tint = mix(CYAN, VIOLET, outer ? .72 : .24);
    return mix(root, tip, t) + normal * (bow * (outer ? .10 : -.13) + edge * (.003 + .002 * signal));
}

vec3 mote(float t, float index, float edge, float axis, out float alpha) {
    float phase = index * GOLDEN_ANGLE;
    float age = fract(5.0 * PlasmaPhase / TAU + index * .137);
    float angle = portAngle(mod(index, 3.0)) - .14 + .36 * age;
    vec3 ray = radial(angle);
    float radius = StarRadius + .025 + age * .095 + (t - .5) * (.005 + .004 * Activity.y);
    float width = .0022;
    alpha = smoothstep(.02, .15, age) * (1.0 - smoothstep(.49, .88, age))
            * (.13 + .24 * Activity.y) * smoothstep(.53, .86, Activity.x);
    vec3 across = axis == 0.0 ? UP : cross(UP, ray);
    return mat3(Ring0) * (ray * radius + UP * (.016 + .039 * sin(phase) + .047 * age) + edge * width * across);
}

void main() {
    int family = int(UV0.x);
    float t = Position.x, index = Position.y, edge = Position.z, axis = UV0.y;
    float alpha = 0.0;
    vec3 tint = BLUE_WHITE;
    vec3 point = vec3(0.0);
    if (family == 0 && Visibility.y > 0.0) {
        point = prominence(t, index, edge, axis, alpha);
    } else if (Visibility.w > 0.0) {
        if (family == 1 && ringVisible(0) && captureWake(index) > 0.0) {
            point = extraction(t, index, edge, axis, 0.0, alpha, tint);
        } else if (family == 2 && ringVisible(int(index) / 4)) {
            point = transport(t, int(index), edge, axis, alpha, tint);
        } else if (family == 3 && ringVisible(0) && captureWake(index) > 0.0) {
            point = extraction(t, index, edge, axis, 1.0, alpha, tint);
        } else if (family == 4 && ringVisible(0)) {
            point = collectorSeal(t, index, edge, alpha, tint);
        } else if (family == 5 && Visibility.z > 0.0 && (index < 2.0 || index > 3.0 || Activity.w > 0.0)) {
            point = controlFlow(t, int(index), edge, alpha, tint);
        } else if (family == 6 && (Activity.x > .55 || Activity.z > 0.0)
                && ringVisible(0) && ringVisible(int(index) / 2 + 1)) {
            point = fieldCoupling(t, index, edge, alpha, tint);
        } else if (family == 7 && Visibility.y > 0.0) {
            point = mote(t, index, edge, axis, alpha);
        }
    }
    // Keep zero-gain vertices on their path, including the ends of a feathered strip.
    gl_Position = ProjMat * ModelViewMat * vec4(point, 1.0);
    vertexColor = vec4(tint, Color.a * clamp(alpha, 0.0, 1.0));
    vertexDistance = fog_distance(ModelViewMat, point, FogShape);
}
