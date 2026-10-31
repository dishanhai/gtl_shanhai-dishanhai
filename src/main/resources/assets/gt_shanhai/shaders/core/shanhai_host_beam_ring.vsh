#version 150
// Author: dishanhai
in vec3 Position;
in vec2 UV0;
in ivec2 UV1;
uniform mat4 ModelViewMat;
uniform mat4 ProjMat;
out vec2 flowCoord;
out vec3 localPosition;
out float transparency;
void main() {
    flowCoord = UV0;
    localPosition = Position;
    transparency = float(UV1.x) / 10000.0;
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);
}
