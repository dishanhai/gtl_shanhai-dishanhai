#version 150

uniform sampler2D Sampler0;
uniform vec4 ColorModulator;
uniform float ObservationLight;
uniform float RingSweep;
uniform float MaterialSweep;
uniform float SelectionSweep;
uniform float Engineering;

in vec2 texCoord;
in vec3 viewNormal;
in vec3 localPosition;
in float faceTone;

out vec4 fragColor;

void main() {
    vec3 n = normalize(viewNormal);
    float key = max(dot(n, normalize(vec3(-.42, .58, .70))), 0.0);
    float fill = max(dot(n, normalize(vec3(.72, -.29, .63))), 0.0);
    float rim = 1.0 - clamp(n.z, 0.0, 1.0);
    float light = mix(.84, 1.52, ObservationLight) + .62 * key + .29 * fill + .14 * rim;
    vec4 material = texture(Sampler0, texCoord);
    vec3 linear = pow(material.rgb, vec3(2.2));
    linear *= light * mix(.88, 1.0, faceTone) * pow(ColorModulator.rgb, vec3(2.2));
    vec3 display = pow(clamp(linear, 0.0, 1.0), vec3(1.0 / 2.2));
    float azimuth = fract(atan(localPosition.z, localPosition.x) / 6.2831853 + .5);
    float edge = pow(rim, 3.0);
    if (MaterialSweep < 1.0) {
        vec3 grid = localPosition * 68.0;
        vec3 distanceToEdge = abs(fract(grid + .5) - .5);
        vec3 footprint = max(fwidth(grid), vec3(.001));
        vec3 lines = (1.0 - smoothstep(vec3(.03), clamp(footprint, vec3(.035), vec3(.48)), distanceToEdge))
                * (1.0 - smoothstep(vec3(.35), vec3(1.10), footprint));
        float gridLine = max(min(lines.x, lines.y), max(min(lines.x, lines.z), min(lines.y, lines.z)));
        float front = MaterialSweep * 1.23;
        float solid = smoothstep(.02, .22, front - azimuth);
        float pulse = (1.0 - smoothstep(.015, .08, abs(front - azimuth))) * smoothstep(0.0, .08, MaterialSweep);
        vec3 survey = vec3(.025, .082, .137) + (max(.68 * gridLine, edge) + .16 * key) * vec3(.19, .45, .57);
        display = mix(survey, display, solid);
        display = mix(display, vec3(.42, .78, .97), .60 * pulse);
    }
    float luminance = dot(display, vec3(.2126, .7152, .0722));
    display = mix(display, luminance * vec3(.62, .89, 1.12) + vec3(.09, .20, .27) * edge, Engineering * .72);
    if (SelectionSweep < 1.0) {
        float separation = abs(fract(azimuth - SelectionSweep + .5) - .5);
        float pulse = (1.0 - smoothstep(.016, .10, separation)) * sin(SelectionSweep * 3.14159265);
        display = mix(display, vec3(.60, .90, 1.0), pulse * (.23 + .19 * edge));
    }
    if (RingSweep < 1.0) {
        float front = RingSweep * 1.30;
        float arrived = 1.0 - smoothstep(front - .055, front + .025, azimuth);
        float pulse = 1.0 - smoothstep(.015, .09, abs(front - azimuth));
        float alpha = arrived * smoothstep(0.0, .18, RingSweep);
        if (alpha < .005) discard;
        display = mix(display, vec3(.36, .83, .96), pulse * .62);
        fragColor = vec4(display * alpha, alpha);
    } else {
        fragColor = vec4(display, 1.0);
    }
}
