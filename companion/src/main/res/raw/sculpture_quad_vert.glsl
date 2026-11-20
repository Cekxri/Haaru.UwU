#version 300 es
precision highp float;
layout(location = 0) in vec2 aParameter;
out vec2 vUv;
void main() {
    vUv = aParameter;
    gl_Position = vec4(aParameter * 2.0 - 1.0, 0.0, 1.0);
}
