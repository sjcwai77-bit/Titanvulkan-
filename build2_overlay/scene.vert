#version 450
layout(location=0) in vec3 inPosition;
layout(location=1) in vec3 inNormal;
layout(location=2) in vec3 inColor;

layout(set=0,binding=0) uniform GlobalUBO {
    mat4 viewProj;
    vec4 cameraPosTime;
    vec4 lightDirExposure;
} globalUbo;

layout(push_constant) uniform Push {
    mat4 model;
    vec4 params;
} pushData;

layout(location=0) out vec3 vWorld;
layout(location=1) out vec3 vNormal;
layout(location=2) out vec3 vColor;
layout(location=3) flat out float vMaterial;
layout(location=4) flat out float vEmission;

void main() {
    vec3 p=inPosition;
    vec3 n=inNormal;
    float material=pushData.params.x;

    if(material>1.5 && material<2.5){
        float t=globalUbo.cameraPosTime.w;
        float a=p.x*.070+t*.42;
        float b=p.z*.092-t*.35;
        float c=(p.x+p.z)*.142+t*.73;
        float d=(p.x*.21-p.z*.13)-t*.61;
        p.y += .075*sin(a)+.052*sin(b)+.026*sin(c)+.014*sin(d);
        float dx=.075*.070*cos(a)+.026*.142*cos(c)+.014*.21*cos(d);
        float dz=.052*.092*cos(b)+.026*.142*cos(c)-.014*.13*cos(d);
        n=normalize(vec3(-dx,1.0,-dz));
    }

    vec4 world=pushData.model*vec4(p,1.0);
    vWorld=world.xyz;
    vNormal=normalize(mat3(pushData.model)*n);
    vColor=inColor;
    vMaterial=material;
    vEmission=pushData.params.y;
    gl_Position=globalUbo.viewProj*world;
}
