#version 450
layout(location=0) in vec3 vWorld;
layout(location=1) in vec3 vNormal;
layout(location=2) in vec3 vColor;
layout(location=3) flat in float vMaterial;
layout(location=4) flat in float vEmission;
layout(set=0,binding=0) uniform GlobalUBO {
    mat4 viewProj;
    vec4 cameraPosTime;
    vec4 lightDirExposure;
} globalUbo;
layout(location=0) out vec4 outColor;

vec3 tonemap(vec3 x){
    x=max(x,vec3(0.0));
    vec3 a=x*(2.51*x+0.03);
    vec3 b=x*(2.43*x+0.59)+0.14;
    return pow(clamp(a/b,0.0,1.0),vec3(1.0/2.2));
}

void main(){
    vec3 N=normalize(vNormal);
    vec3 V=normalize(globalUbo.cameraPosTime.xyz-vWorld);
    vec3 L=normalize(-globalUbo.lightDirExposure.xyz);
    float exposure=globalUbo.lightDirExposure.w;

    if(vMaterial>3.5){
        float h=clamp(normalize(vWorld).y*.5+.5,0.0,1.0);
        vec3 horizon=vec3(.030,.082,.135);
        vec3 zenith=vec3(.0025,.009,.026);
        vec3 c=mix(horizon,zenith,smoothstep(.35,.95,h));
        outColor=vec4(tonemap(c*1.25),1.0);return;
    }
    if(vMaterial>2.5){
        vec3 c=vColor*max(1.0,vEmission);
        outColor=vec4(tonemap(c*1.7),1.0);return;
    }
    if(vMaterial>1.5){
        float ndv=max(dot(N,V),0.0);
        float fres=.035+.965*pow(1.0-ndv,5.0);
        vec3 R=reflect(-V,N);
        float skyFactor=clamp(R.y*.5+.5,0.0,1.0);
        vec3 deep=vec3(.010,.055,.095);
        vec3 refl=mix(vec3(.035,.10,.16),vec3(.16,.31,.44),skyFactor);
        vec3 H=normalize(L+V);
        float sun=pow(max(dot(N,H),0.0),220.0);
        float glint=pow(max(dot(reflect(-L,N),V),0.0),520.0);
        float micro=.5+.5*sin(vWorld.x*1.7+vWorld.z*.9+globalUbo.cameraPosTime.w*1.7);
        vec3 c=mix(deep,refl,fres)+vec3(.55,.73,.90)*sun*.50+vec3(1.0,.95,.82)*glint*(.45+.55*micro);
        outColor=vec4(tonemap(c*exposure),1.0);return;
    }

    float ndl=max(dot(N,L),0.0);
    float ndv=max(dot(N,V),0.0);
    vec3 H=normalize(L+V);
    float lum=dot(vColor,vec3(.2126,.7152,.0722));
    bool darkPaint=lum<.12;
    bool deckLike=(vColor.r>.25 && vColor.r>.95*vColor.g && vColor.g<.38 && vColor.b<.23);
    bool pale=(lum>.55);
    float rough=deckLike?.72:(darkPaint?.18:(pale?.30:.38));
    float shininess=mix(180.0,24.0,rough);
    float spec=pow(max(dot(N,H),0.0),shininess);
    float fres=.025+.35*pow(1.0-ndv,5.0);
    vec3 skyFill=mix(vec3(.025,.055,.09),vec3(.12,.18,.23),clamp(N.y*.5+.5,0.0,1.0));
    vec3 diffuse=vColor*(skyFill+ndl*.78);
    vec3 specTint=darkPaint?vec3(.82,.88,.94):vec3(.66,.72,.78);
    vec3 c=diffuse+specTint*spec*(darkPaint?.95:.48)+specTint*fres*.18;
    c+=vColor*vEmission*1.65;
    c*=.86+.14*clamp(N.y*.5+.5,0.0,1.0);
    outColor=vec4(tonemap(c*exposure),1.0);
}
