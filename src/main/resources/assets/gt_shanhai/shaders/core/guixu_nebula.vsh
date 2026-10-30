#version 150
// 山海：位置／UV／色彩與共享截面的解析法線；輪廓插值淡出不暴露直立薄片。
in vec3 Position;
in vec2 UV0;
in vec4 Color;
in vec3 Normal;
uniform mat4 ModelViewMat;
uniform mat4 ProjMat;
out vec2 texCoord;
out vec4 vertexColor;
out vec3 viewPosition;
out vec3 viewNormal;
void main() {
    vec4 view = ModelViewMat * vec4(Position, 1.0);
    gl_Position = ProjMat * view;
    viewPosition = view.xyz;
    viewNormal = mat3(ModelViewMat) * Normal;
    texCoord = UV0;
    vertexColor = Color;
}
