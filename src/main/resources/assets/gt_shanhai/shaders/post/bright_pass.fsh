#version 150

uniform sampler2D uLight;
uniform sampler2D uMeterLight;
uniform float uExposure;
uniform int uMeter;
out vec4 outBright;

vec4 brightSample(ivec2 at) {
    vec3 raw = max(vec3(0.0), texelFetch(uLight, at, 0).rgb);
    vec3 exposed = raw * uExposure;
    float brightness = max(exposed.r, max(exposed.g, exposed.b));
    const float threshold = 1.0;
    const float knee = .55;
    float soft = clamp(brightness - threshold + knee, 0.0, 2.0 * knee);
    soft = soft * soft / (4.0 * knee);
    float logLuminance = 1.0;
    if (uMeter != 0) {
        vec3 meter = max(vec3(0.0), texelFetch(uMeterLight, at, 0).rgb);
        logLuminance = log2(max(.055, dot(meter, vec3(.2126, .7152, .0722))));
    }
    return vec4(exposed * (max(brightness - threshold, soft) / max(brightness, .00001)), logLuminance);
}

void main() {
    ivec2 base = 2 * ivec2(gl_FragCoord.xy);
    ivec2 limit = textureSize(uLight, 0) - ivec2(1);
    // Threshold the input pixels before averaging. A bright subpixel then
    // contributes to the pyramid without turning neighboring dim pixels into bloom.
    vec4 sum = brightSample(min(base, limit))
             + brightSample(min(base + ivec2(1, 0), limit))
             + brightSample(min(base + ivec2(0, 1), limit))
             + brightSample(min(base + ivec2(1, 1), limit));
    outBright = .25 * sum;
}
