#version 150

#moj_import <fog.glsl>

in vec3 Position;
in vec2 UV0;
in vec4 Color;

uniform mat4 ModelViewMat;
uniform mat4 ProjMat;
uniform int FogShape;

out vec3 viewPosition;
out float vertexDistance;
out vec2 uv;
out vec4 tint;

void main() {
    viewPosition = (ModelViewMat * vec4(Position, 1.0)).xyz;
    gl_Position = ProjMat * vec4(viewPosition, 1.0);
    vertexDistance = fog_distance(ModelViewMat, Position, FogShape);
    uv = UV0;
    tint = Color;
}
