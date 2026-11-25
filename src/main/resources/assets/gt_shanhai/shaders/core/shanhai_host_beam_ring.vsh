#version 150
// Author: dishanhai
in vec3 Position;
in vec2 UV0;
uniform mat4 ModelViewMat;
uniform mat4 ProjMat;
uniform float WarpPhase;
out vec2 flowCoord;
out vec3 localPosition;
void main() {
    flowCoord = UV0;
    localPosition = Position;
    float s = UV0.x;
    // Use identical positions and wave phases at both ends of the UV seam.
    float angle = UV0.y == 6.283185307179586 ? 0.0 : UV0.y;
    localPosition.y += .45 * sin(3.141592653589793 * s)
            * (.7 * sin(2.0 * angle - 4.0 * WarpPhase) + .3 * sin(5.0 * angle + 3.0 * WarpPhase));
    gl_Position = ProjMat * ModelViewMat * vec4(localPosition, 1.0);
}
