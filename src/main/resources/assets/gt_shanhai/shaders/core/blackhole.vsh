#version 150 core

uniform float u_Time;
uniform float u_Stability;
uniform float u_Scale;
uniform vec3 u_CameraPosition;
uniform mat4 u_ModelViewProjectionMatrix;

in vec3 Position;
in vec2 TexCoord0;

out vec2 v_TexCoord;

const float PI = 3.14159265358979323846;
const float HORIZON_EDGE = 2.6;

mat4 rotateMatrix(float angle, vec3 axis){
    float x = axis.x, y = axis.y, z = axis.z;
    float c = cos(angle), s = sin(angle), t = 1.0 - c;
    return mat4(
        c+x*x*t,     t*x*y - s*z, t*x*z + s*y, 0.0,
        t*x*y + s*z,   t*y*y + c,   t*y*z - s*x, 0.0,
        t*x*z - s*y,   t*y*z + s*x, t*z*z + c,   0.0,
        0.0,           0.0,         0.0,         1.0
    );
}

void main() {
    v_TexCoord = TexCoord0;

    bool isDisk  = (abs(v_TexCoord.y - 0.5) > 0.245);
    bool isBack  = (abs(v_TexCoord.x - 0.5) > 0.245) && isDisk;
    bool isFront = (abs(v_TexCoord.x - 0.5) < 0.255) && isDisk;
    bool isBot   = (v_TexCoord.y < 0.5) && isBack;

    float yAngle = atan(u_CameraPosition.z, u_CameraPosition.x) - PI/2.0;
    float c = cos(yAngle), s = sin(yAngle);
    mat4 yRotate = mat4(
        vec4(c, 0.0, s, 0.0),
        vec4(0.0, 1.0, 0.0, 0.0),
        vec4(-s, 0.0, c, 0.0),
        vec4(0.0, 0.0, 0.0, 1.0)
    );

    float base = length(Position);
    float stab = (base > HORIZON_EDGE) ? u_Stability : 1.0;
    float scale = ((base - HORIZON_EDGE) * stab + HORIZON_EDGE) / base;
    scale = max(scale, 0.1) * u_Scale;

    vec4 rotated = yRotate * vec4(Position * scale, 1.0);

    vec3 cameraDir = normalize(u_CameraPosition);
    cameraDir = isBot ? -cameraDir : cameraDir;
    vec3 rotateAxis = cross(cameraDir, vec3(0.0, 1.0, 0.0));
    float angle = acos(dot(cameraDir, vec3(0.0, 1.0, 0.0)));
    if (isFront) angle = 0.0;

    float instabilityRot = (u_Stability <= 0.0) ? u_Time / 10.0 : 0.0;

    mat4 rotate = rotateMatrix(angle, normalize(rotateAxis));
    mat4 rotateB = rotateMatrix(instabilityRot, normalize(u_CameraPosition));

    gl_Position = u_ModelViewProjectionMatrix * (rotateB * (rotate * rotated));
}
