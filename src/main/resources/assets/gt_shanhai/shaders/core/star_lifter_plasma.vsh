#version 150

#moj_import <fog.glsl>

in vec3 Position;
in vec2 UV0;
in vec4 Color;

uniform mat4 ModelViewMat;
uniform mat4 ProjMat;
uniform int FogShape;

out vec3 localPosition;
out vec2 uv;
out vec4 tint;
out float vertexDistance;

void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);
    localPosition = Position;
    uv = UV0;
    tint = Color;
    vertexDistance = fog_distance(ModelViewMat, Position, FogShape);
}
