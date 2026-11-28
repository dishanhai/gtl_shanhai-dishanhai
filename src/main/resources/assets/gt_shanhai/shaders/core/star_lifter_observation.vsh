#version 150

in vec3 Position;
in vec4 Color;
in vec2 UV0;
in vec3 Normal;

uniform mat4 ModelViewMat;
uniform mat4 ProjMat;

out vec2 texCoord;
out vec3 viewNormal;
out vec3 localPosition;
out float faceTone;

void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);
    texCoord = UV0;
    viewNormal = mat3(ModelViewMat) * Normal;
    localPosition = Position;
    faceTone = Color.r;
}
