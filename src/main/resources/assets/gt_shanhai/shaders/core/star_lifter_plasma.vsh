#version 150

#moj_import <fog.glsl>

in vec3 Position;
in vec2 UV0;
in vec4 Color;

uniform mat4 ModelViewMat;
uniform mat4 ProjMat;
uniform mat4 CoronaPose;
uniform float CoronaAlpha;
uniform float StellarScale;
uniform int FogShape;

out vec3 localPosition;
out vec2 uv;
out vec4 tint;
out float vertexDistance;

void main() {
    bool billboard = UV0.x >= 2.0;
    vec3 position = billboard ? (CoronaPose * vec4(Position, 1.0)).xyz : Position * StellarScale;
    gl_Position = ProjMat * ModelViewMat * vec4(position, 1.0);
    localPosition = position;
    uv = UV0;
    tint = billboard ? vec4(Color.rgb, Color.a * CoronaAlpha) : Color;
    vertexDistance = fog_distance(ModelViewMat, position, FogShape);
}
