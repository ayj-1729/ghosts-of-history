uniform mat4 u_ModelViewProjection;

attribute vec4 a_Position;
attribute vec2 a_TexCoord;

varying vec2 v_TexCoord;

void main() {
   gl_Position = u_ModelViewProjection * vec4(a_Position.xyz, 1.0);
   v_TexCoord = a_TexCoord;
}
