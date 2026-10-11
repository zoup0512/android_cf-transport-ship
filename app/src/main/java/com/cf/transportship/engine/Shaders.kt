// 全部 GLSL 着色器源码
package com.cf.transportship.engine

object Shaders {

    // ---------- 公共光照片段 ----------
    const val LIGHTING_FRAG = """
        uniform sampler2D uMap;
        uniform bool uHasNormalMap;
        uniform sampler2D uNormalMap;
        uniform highp sampler2DShadow uShadowMap;
        uniform vec3 uColor;
        uniform float uRoughness;
        uniform float uMetalness;
        uniform vec3 uEmissive;
        uniform float uAlphaTest;
        uniform float uUseAO;
        uniform sampler2D uAOMap;
        uniform vec3 uSunDir;
        uniform vec3 uSunColor;
        uniform vec3 uHemiSky;
        uniform vec3 uHemiGround;
        uniform vec3 uCamPos;
        uniform vec3 uFogColor;
        uniform float uFogDensity;
        uniform vec4 uPointPos[8];
        uniform vec4 uPointCol[8];
        uniform int uPointCount;

        vec3 applyFog(vec3 col, vec3 wp) {
            float d = length(wp - uCamPos);
            float f = 1.0 - exp(-uFogDensity * uFogDensity * d * d);
            return mix(col, uFogColor, f);
        }

        vec3 shade(vec3 albedo, vec3 N, vec3 wp, float rough, float metal) {
            vec3 V = normalize(uCamPos - wp);
            vec3 L = normalize(uSunDir);
            float ndl = max(dot(N, L), 0.0);
            // 简化 GGX 高光
            vec3 H = normalize(V + L);
            float specPow = max(4.0, exp2(2.0 + 11.0 * (1.0 - rough)));
            float spec = pow(max(dot(N, H), 0.0), specPow) * (specPow + 8.0) / 25.0;
            float fres = pow(1.0 - max(dot(N, V), 0.0), 5.0);
            vec3 specCol = mix(vec3(1.0), albedo, metal) * (0.04 + 0.96 * metal);
            vec3 direct = albedo * ndl * uSunColor + specCol * spec * uSunColor * (ndl > 0.0 ? 1.0 : 0.0);
            direct += specCol * fres * 0.15 * uSunColor * max(uSunDir.y, 0.0);
            // 半球环境
            vec3 amb = mix(uHemiGround, uHemiSky, N.y * 0.5 + 0.5) * albedo * (1.0 - metal * 0.6);
            // 点光源
            for (int i = 0; i < 8; i++) {
                if (i >= uPointCount) break;
                vec3 pd = uPointPos[i].xyz - wp;
                float dist = length(pd);
                float radius = uPointPos[i].w;
                if (dist > radius) continue;
                vec3 pl = pd / max(dist, 0.001);
                float att = pow(max(1.0 - dist / radius, 0.0), 1.8);
                float pndl = max(dot(N, pl), 0.0);
                vec3 pH = normalize(V + pl);
                float pSpec = pow(max(dot(N, pH), 0.0), specPow) * (specPow + 8.0) / 25.0;
                direct += (albedo * pndl + specCol * pSpec) * uPointCol[i].rgb * att;
            }
            return direct + amb;
        }

        float shadowFactor(vec4 sc, vec3 N) {
            vec3 p = sc.xyz / sc.w;
            if (p.x < 0.001 || p.x > 0.999 || p.y < 0.001 || p.y > 0.999 || p.z >= 1.0) return 1.0;
            float bias = max(0.0006 * (1.0 - dot(N, normalize(uSunDir))), 0.0001);
            float s = 0.0;
            s += texture(uShadowMap, vec3(p.xy, p.z - bias));
            s += texture(uShadowMap, vec3(p.xy + vec2(1.0 / 2048.0, 0.0), p.z - bias));
            s += texture(uShadowMap, vec3(p.xy + vec2(0.0, 1.0 / 2048.0), p.z - bias));
            s += texture(uShadowMap, vec3(p.xy + vec2(1.0 / 2048.0), p.z - bias));
            return s * 0.25;
        }

        vec3 normalFromMap(vec2 uv, vec3 N, vec3 wp) {
            vec3 nm = texture(uNormalMap, uv).xyz * 2.0 - 1.0;
            // 用导数构建 TBN
            vec3 dp1 = dFdx(wp), dp2 = dFdy(wp);
            vec2 duv1 = dFdx(uv), duv2 = dFdy(uv);
            vec3 dp2perp = cross(dp2, N);
            vec3 dp1perp = cross(N, dp1);
            vec3 T = dp2perp * duv1.x + dp1perp * duv2.x;
            vec3 B = dp2perp * duv1.y + dp1perp * duv2.y;
            float invmax = inversesqrt(max(dot(T, T), dot(B, B)));
            return normalize(T * (invmax * nm.x) + B * (invmax * nm.y) + N * (invmax * nm.z));
        }
    """

    // ---------- 静态地图 ----------
    const val MAP_VERT = """#version 300 es
        layout(location=0) in vec3 aPos;
        layout(location=1) in vec3 aNormal;
        layout(location=2) in vec2 aUV;
        layout(location=3) in vec2 aUV2;
        uniform mat4 uViewProj;
        uniform mat4 uLightMat;
        out vec3 vWorld;
        out vec3 vNormal;
        out vec2 vUV;
        out vec2 vUV2;
        out vec4 vShadow;
        void main() {
            vWorld = aPos;
            vNormal = aNormal;
            vUV = aUV;
            vUV2 = aUV2;
            vShadow = uLightMat * vec4(aPos, 1.0);
            gl_Position = uViewProj * vec4(aPos, 1.0);
        }"""

    const val MAP_FRAG = """#version 300 es
        precision highp float;
        in vec3 vWorld;
        in vec3 vNormal;
        in vec2 vUV;
        in vec2 vUV2;
        in vec4 vShadow;
        out vec4 fragColor;
        ${LIGHTING_FRAG}
        void main() {
            vec4 texel = texture(uMap, vUV);
            vec3 albedo = pow(texel.rgb, vec3(2.2)) * uColor;
            if (uAlphaTest > 0.0 && texel.a < uAlphaTest) discard;
            vec3 N = normalize(vNormal);
            if (uHasNormalMap) N = normalFromMap(vUV, N, vWorld);
            float ao = uUseAO > 0.5 ? texture(uAOMap, vUV2).r : 1.0;
            float sh = shadowFactor(vShadow, N);
            vec3 col = shade(albedo, N, vWorld, uRoughness, uMetalness) * sh;
            col += uEmissive * ao;
            col = applyFog(col, vWorld);
            fragColor = vec4(col, 1.0);
        }"""

    // ---------- 骨骼士兵 ----------
    const val SKIN_VERT = """#version 300 es
        layout(location=0) in vec3 aPos;
        layout(location=1) in vec3 aNormal;
        layout(location=2) in vec2 aUV;
        layout(location=3) in vec3 aColor;
        layout(location=4) in float aSkin;
        uniform mat4 uViewProj;
        uniform mat4 uLightMat;
        uniform mat4 uBones[19];
        uniform mat4 uModel;
        uniform bool uSkin;
        out vec3 vWorld;
        out vec3 vNormal;
        out vec2 vUV;
        out vec3 vColor;
        out vec4 vShadow;
        void main() {
            vec4 pos = vec4(aPos, 1.0);
            vec4 nrm = vec4(aNormal, 0.0);
            if (uSkin) {
                int bi = int(aSkin);
                pos = uBones[bi] * pos;
                nrm = uBones[bi] * nrm;
            }
            vec4 wp = uModel * pos;
            vWorld = wp.xyz;
            vNormal = normalize((uModel * nrm).xyz);
            vUV = aUV;
            vColor = aColor;
            vShadow = uLightMat * wp;
            gl_Position = uViewProj * wp;
        }"""

    const val SKIN_FRAG = """#version 300 es
        precision highp float;
        in vec3 vWorld;
        in vec3 vNormal;
        in vec2 vUV;
        in vec3 vColor;
        in vec4 vShadow;
        out vec4 fragColor;
        ${LIGHTING_FRAG}
        uniform float uOpacity;
        void main() {
            vec3 albedo = pow(texture(uMap, vUV).rgb, vec3(2.2)) * vColor;
            vec3 N = normalize(vNormal);
            float sh = shadowFactor(vShadow, N);
            vec3 col = shade(albedo, N, vWorld, 0.82, 0.05) * sh;
            col = applyFog(col, vWorld);
            fragColor = vec4(col, uOpacity);
        }"""

    // ---------- 深度通道（阴影） ----------
    const val DEPTH_VERT = """#version 300 es
        layout(location=0) in vec3 aPos;
        uniform mat4 uLightProj;
        void main() { gl_Position = uLightProj * vec4(aPos, 1.0); }"""

    const val DEPTH_FRAG = """#version 300 es
        precision mediump float;
        out vec4 fragColor;
        void main() { fragColor = vec4(1.0); }"""

    const val DEPTH_SKIN_VERT = """#version 300 es
        layout(location=0) in vec3 aPos;
        layout(location=4) in float aSkin;
        uniform mat4 uLightProj;
        uniform mat4 uModel;
        uniform mat4 uBones[19];
        uniform bool uSkin;
        void main() {
            vec4 pos = vec4(aPos, 1.0);
            if (uSkin) pos = uBones[int(aSkin)] * pos;
            gl_Position = uLightProj * uModel * pos;
        }"""

    // ---------- 粒子（点精灵） ----------
    const val PARTICLE_VERT = """#version 300 es
        layout(location=0) in vec3 aPos;
        layout(location=1) in vec4 aColor;
        layout(location=2) in float aSize;
        uniform mat4 uViewProj;
        uniform float uScale;
        out vec4 vColor;
        void main() {
            vColor = aColor;
            vec4 mv = uViewProj * vec4(aPos, 1.0);
            gl_PointSize = clamp(aSize * uScale / max(0.1, -mv.z), 1.0, 512.0);
            gl_Position = mv;
        }"""

    const val PARTICLE_FRAG = """#version 300 es
        precision mediump float;
        uniform sampler2D uMap;
        in vec4 vColor;
        out vec4 fragColor;
        void main() {
            vec4 t = texture(uMap, gl_PointCoord);
            fragColor = vec4(vColor.rgb * t.rgb, vColor.a * t.a);
            if (fragColor.a < 0.003) discard;
        }"""

    // ---------- 无光照贴花/曳光/火焰板 ----------
    const val UNLIT_VERT = """#version 300 es
        layout(location=0) in vec3 aPos;
        layout(location=1) in vec2 aUV;
        uniform mat4 uViewProj;
        out vec2 vUV;
        out vec3 vWorld;
        void main() { vUV = aUV; vWorld = aPos; gl_Position = uViewProj * vec4(aPos, 1.0); }"""

    const val UNLIT_FRAG = """#version 300 es
        precision mediump float;
        uniform sampler2D uMap;
        uniform vec4 uTint;
        uniform vec3 uFogColor;
        uniform float uFogDensity;
        uniform vec3 uCamPos;
        uniform float uFogOn;
        in vec2 vUV;
        in vec3 vWorld;
        out vec4 fragColor;
        void main() {
            vec4 t = texture(uMap, vUV) * uTint;
            if (t.a < 0.003) discard;
            if (uFogOn > 0.5) {
                float d = length(vWorld - uCamPos);
                float f = 1.0 - exp(-uFogDensity * uFogDensity * d * d);
                t.rgb = mix(t.rgb, uFogColor, f);
            }
            fragColor = t;
        }"""

    // ---------- 天空（Preetham，移植 three Sky） ----------
    const val SKY_VERT = """#version 300 es
        layout(location=0) in vec3 aPos;
        uniform mat4 uViewProj;
        uniform vec3 uCamPos;
        out vec3 vWorldDir;
        void main() {
            vec3 wp = aPos * 8000.0 + uCamPos;
            vWorldDir = aPos;
            vec4 p = uViewProj * vec4(wp, 1.0);
            gl_Position = p.xyww;
        }"""

    const val SKY_FRAG = """#version 300 es
        precision highp float;
        in vec3 vWorldDir;
        out vec4 fragColor;
        uniform vec3 uSunDir;
        uniform float uTurbidity;
        uniform float uRayleigh;
        uniform float uMieCoefficient;
        uniform float uMieDirectionalG;
        const float PI = 3.141592653589793;

        const vec3 up = vec3(0.0, 1.0, 0.0);
        const float e = 2.71828182845904523536028747135266249775724709369995957;
        const float pi = PI;

        // wavelength of used primaries, according to preetham
        const vec3 lambda = vec3(680E-9, 550E-9, 450E-9);
        const vec3 totalRayleigh = vec3(5.804542996261093E-6, 1.3562911419845635E-5, 2.2403387416040894E-5);
        const vec3 MieConst = vec3(1.8399918514433978E14, 2.7798023919660528E14, 4.0790479543861094E14);
        const float rayleighZenithLength = 8.4E3;
        const float mieZenithLength = 1.25E3;
        const vec3 sunE = vec3(1000.0) * vec3(0.2, 0.16, 0.12) * 20.0;
        const float sunAngularDiameterCos = 0.9999566769464484435535746199069764789268486928739008596024736;

        float rayleighPhase(float cosTheta) { return (3.0 / (16.0 * PI)) * (1.0 + pow(cosTheta, 2.0)); }
        float hgPhase(float cosTheta, float g) {
            float g2 = pow(g, 2.0);
            float inv = 1.0 / max(1E-4, pow(1.0 - 2.0 * g * cosTheta + g2, 1.5));
            return (1.0 / (4.0 * PI)) * ((1.0 - g2) * inv);
        }
        vec3 totalMie(float T) {
            float c = (0.2 * T) * 10E-18;
            return 0.434 * c * MieConst;
        }

        void main() {
            vec3 vDir = normalize(vWorldDir);
            float sunfade = 1.0 - clamp(1.0 - exp(-(uSunDir.y / 450000.0)), 0.0, 1.0);
            float rayleighCoefficient = uRayleigh - (1.0 * (1.0 - sunfade));
            vec3 betaR = totalRayleigh * rayleighCoefficient;
            float mieCoefficient = 0.0055 * uMieCoefficient * 200.0;
            vec3 betaM = totalMie(uTurbidity) * mieCoefficient;

            float zenithAngle = acos(max(0.0, dot(up, vDir)));
            float inv = 1.0 / (cos(zenithAngle) + 0.15 * pow(93.885 - ((zenithAngle * 180.0) / PI), -1.253));
            float sR = rayleighZenithLength * inv;
            float sM = mieZenithLength * inv;

            vec3 Fex = exp(-(betaR * sR + betaM * sM));

            float cosTheta = dot(vDir, uSunDir);
            float rPhase = rayleighPhase(cosTheta * 0.5 + 0.5);
            vec3 betaRTheta = betaR * rPhase;
            float mPhase = hgPhase(cosTheta, uMieDirectionalG);
            vec3 betaMTheta = betaM * mPhase;

            vec3 Lin = pow(sunE * ((betaRTheta + betaMTheta) / (betaR + betaM)) * (1.0 - Fex), vec3(1.5));
            Lin *= mix(vec3(1.0), pow(sunE * ((betaRTheta + betaMTheta) / (betaR + betaM)) * Fex, vec3(1.0 / 2.0)),
                clamp(pow(1.0 - dot(up, uSunDir), 5.0), 0.0, 1.0));

            float theta = acos(uSunDir.y);
            float phi = atan(uSunDir.z, uSunDir.x);
            vec2 uv = vec2(phi, theta) / vec2(2.0 * PI, PI) + vec2(0.5, 0.0);

            vec3 L0 = vec3(0.1) * Fex;
            float sundisk = smoothstep(sunAngularDiameterCos, sunAngularDiameterCos + 0.00002, cosTheta);
            L0 += (sunE * 19000.0 * Fex) * sundisk;

            vec3 texColor = (Lin + L0) * 0.04 + vec3(0.0, 0.0003, 0.00075);
            vec3 retColor = pow(texColor, vec3(1.0 / (1.2 + (1.2 * sunfade))));
            fragColor = vec4(retColor, 1.0);
        }"""

    // ---------- 云层 ----------
    const val CLOUD_VERT = """#version 300 es
        layout(location=0) in vec3 aPos;
        uniform mat4 uViewProj;
        uniform mat4 uModel;
        out vec3 vW;
        void main() { vec4 wp = uModel * vec4(aPos, 1.0); vW = wp.xyz; gl_Position = uViewProj * wp; }"""

    const val CLOUD_FRAG = """#version 300 es
        precision highp float;
        uniform float uTime;
        uniform float uCover;
        uniform vec3 uSunDir;
        uniform vec3 uLit;
        uniform vec3 uShade;
        uniform vec3 uFog;
        uniform vec3 uCamPos;
        in vec3 vW;
        out vec4 fragColor;

        float hash12(vec2 p) { vec3 p3 = fract(vec3(p.xyx) * .1031); p3 += dot(p3, p3.yzx + 33.33); return fract((p3.x + p3.y) * p3.z); }
        float vnoise(vec2 p) {
            vec2 i = floor(p), f = fract(p);
            vec2 u = f * f * (3.0 - 2.0 * f);
            return mix(mix(hash12(i), hash12(i + vec2(1, 0)), u.x), mix(hash12(i + vec2(0, 1)), hash12(i + vec2(1, 1)), u.x), u.y);
        }
        float fbm4(vec2 p) { float s = 0.0, a = 0.5; for (int i = 0; i < 4; i++) { s += a * vnoise(p); p = p * 2.03 + vec2(17.1, 9.2); a *= 0.5; } return s; }
        float fbm6(vec2 p) { float s = 0.0, a = 0.5; for (int i = 0; i < 6; i++) { s += a * vnoise(p); p = p * 2.03 + vec2(17.1, 9.2); a *= 0.5; } return s; }

        void main() {
            vec2 p = (vW.xz + vec2(uTime * 6.0, uTime * 2.0)) / 2600.0;
            float n = fbm6(p);
            float d = smoothstep(uCover, uCover + 0.28, n);
            float n2 = fbm4(p * 1.8 + vec2(3.1, 1.7) + uSunDir.xz * 0.05);
            float lit = clamp(0.55 + (n - n2) * 1.6, 0.0, 1.0);
            vec3 col = mix(uShade, uLit, lit);
            float distXZ = length(vW.xz - uCamPos.xz);
            col = mix(uFog, col, smoothstep(24000.0, 4000.0, distXZ));
            fragColor = vec4(col, d * 0.95);
        }"""

    // ---------- 海洋 ----------
    const val OCEAN_VERT = """#version 300 es
        layout(location=0) in vec3 aPos;
        uniform mat4 uViewProj;
        uniform float uTime;
        uniform float uShip;
        uniform vec4 uWaves[6];
        out vec3 vW;
        out vec3 vN;
        out float vH;
        void main() {
            vec3 p = aPos;
            vec2 xz = p.xz + vec2(uShip, 0.0);
            float dist = length(p.xz);
            float fade = smoothstep(1400.0, 250.0, dist);
            vec3 disp = vec3(0.0);
            vec3 T = vec3(1.0, 0.0, 0.0), B = vec3(0.0, 0.0, 1.0);
            for (int i = 0; i < 6; i++) {
                vec4 w = uWaves[i];
                float k = 6.2831853 / w.w;
                float c = sqrt(9.8 / k);
                vec2 d = w.xy;
                float st = w.z * fade;
                float f = k * (dot(d, xz) - c * uTime);
                float a = st / k;
                float sf = sin(f), cf = cos(f);
                disp += vec3(d.x * a * cf, a * sf, d.y * a * cf);
                T += vec3(-d.x * d.x * st * sf, d.x * st * cf, -d.x * d.y * st * sf);
                B += vec3(-d.x * d.y * st * sf, d.y * st * cf, -d.y * d.y * st * sf);
            }
            float hullD = max(abs(p.z) - 12.5, 0.0) + max(-66.0 - p.x, 0.0) + max(p.x - 80.0, 0.0);
            float calm = smoothstep(0.0, 18.0, hullD) * 0.75 + 0.25;
            disp *= calm;
            p += disp;
            p.y += 0.0; // 网格自身已在 SEA_Y 平面（网格局部 y=0，modelMatrix 由外部上移）
            vH = disp.y;
            vN = normalize(cross(B, T));
            vW = p;
            gl_Position = uViewProj * vec4(p, 1.0);
        }"""

    const val OCEAN_FRAG = """#version 300 es
        precision highp float;
        uniform float uTime;
        uniform float uShip;
        uniform float uFogDensity;
        uniform vec3 uSunDir, uSunColor, uDeep, uShallow, uZen, uHor, uFog, uCam;
        uniform vec3 uCamPos;
        in vec3 vW;
        in vec3 vN;
        in float vH;
        out vec4 fragColor;

        float hash12(vec2 p) { vec3 p3 = fract(vec3(p.xyx) * .1031); p3 += dot(p3, p3.yzx + 33.33); return fract((p3.x + p3.y) * p3.z); }
        float vnoise(vec2 p) {
            vec2 i = floor(p), f = fract(p);
            vec2 u = f * f * (3.0 - 2.0 * f);
            return mix(mix(hash12(i), hash12(i + vec2(1, 0)), u.x), mix(hash12(i + vec2(0, 1)), hash12(i + vec2(1, 1)), u.x), u.y);
        }
        float fbm4(vec2 p) { float s = 0.0, a = 0.5; for (int i = 0; i < 4; i++) { s += a * vnoise(p); p = p * 2.03 + vec2(17.1, 9.2); a *= 0.5; } return s; }

        vec2 ripple(vec2 p, float t) {
            vec2 g = vec2(0.0);
            for (int i = 0; i < 8; i++) {
                float fi = float(i);
                float ang = fi * 2.399 + 0.3;
                vec2 d = vec2(cos(ang), sin(ang));
                float fr = 0.9 + fi * 0.55;
                float amp = 0.08 / (1.0 + fi * 0.6);
                float ph = dot(d, p) * fr + t * (1.2 + fi * 0.37);
                g += d * cos(ph) * fr * amp;
            }
            return g;
        }

        void main() {
            vec3 V = normalize(uCamPos - vW);
            float dist = length(uCamPos - vW);
            vec2 p = vW.xz + vec2(uShip, 0.0);
            vec2 g = ripple(p * 1.3, uTime) * smoothstep(400.0, 30.0, dist);
            vec3 N = normalize(vN + vec3(-g.x, 0.0, -g.y) * 0.9);
            float nv = max(dot(N, V), 0.0);
            float F = 0.02 + 0.98 * pow(1.0 - nv, 5.0);
            vec3 R = reflect(-V, N); R.y = abs(R.y);
            vec3 sky = mix(uHor, uZen, pow(clamp(R.y, 0.0, 1.0), 0.45));
            vec2 cuv = R.xz / max(R.y, 0.05) * 0.6 + vec2(uTime * 0.01, 0.0);
            float cl = smoothstep(0.5, 0.8, fbm4(cuv));
            sky = mix(sky, uHor * 1.15 + 0.1, cl * 0.5 * smoothstep(0.02, 0.2, R.y));
            float spec = pow(max(dot(R, uSunDir), 0.0), 900.0) * 60.0 + pow(max(dot(R, uSunDir), 0.0), 60.0) * 0.6;
            float sss = pow(max(dot(V, -uSunDir) * 0.5 + 0.5, 0.0), 3.0) * clamp(vH * 0.6 + 0.3, 0.0, 1.0);
            vec3 body = mix(uDeep, uShallow, clamp(vH * 0.35 + 0.25, 0.0, 1.0) * 0.6 + sss * 0.5);
            body *= (0.55 + 0.45 * max(uSunDir.y, 0.1));
            vec3 col = mix(body, sky, F) + uSunColor * spec;
            float ax = abs(vW.z);
            float bowT = clamp((vW.x - 58.0) / 28.0, 0.0, 1.0);
            float hullW = vW.x > 58.0 ? 12.25 * cos(bowT * 1.5707) : 12.25;
            float dh = ax - hullW;
            float n1 = fbm4(p * 0.35 + vec2(0.0, uTime * 0.3));
            float n2 = fbm4(p * 1.4 - vec2(uTime * 0.5, 0.0));
            float hullFoam = smoothstep(2.2, 0.0, dh) * step(-66.0, vW.x) * step(vW.x, 88.0) * (0.35 + 0.65 * smoothstep(-40.0, 60.0, vW.x));
            float bowFoam = smoothstep(45.0, 75.0, vW.x) * smoothstep(7.0, 0.0, dh - (86.0 - vW.x) * 0.1);
            float behind = -66.0 - vW.x;
            float wakeW = 9.0 + behind * 0.18;
            float wake = step(0.0, behind) * smoothstep(wakeW, wakeW * 0.1, ax) * exp(-behind / 300.0);
            float kel = -vW.x + 60.0;
            float kz = ax - 12.0 - kel * 0.354;
            float kelvin = step(0.0, kel) * exp(-abs(kz) * 0.9) * exp(-kel / 200.0) * 0.35;
            float foam = clamp(hullFoam * 0.8 + bowFoam + wake * 0.9 + kelvin, 0.0, 1.5);
            foam *= smoothstep(0.45, 0.85, n1 * 0.6 + n2 * 0.55 + foam * 0.2);
            float crest = smoothstep(0.9, 1.6, vH) * smoothstep(0.55, 0.8, n2) * 0.5;
            foam = clamp(foam + crest, 0.0, 1.0);
            vec3 foamCol = vec3(0.92, 0.95, 0.97) * (0.6 + 0.4 * max(uSunDir.y, 0.2)) * (uSunColor * 0.4 + 0.6);
            col = mix(col, foamCol, foam * 0.9);
            float fogF = 1.0 - exp(-uFogDensity * uFogDensity * dist * dist);
            col = mix(col, uFog, fogF);
            fragColor = vec4(col, 1.0);
        }"""

    // ---------- 后期合成（含 FX） ----------
    const val POST_VERT = """#version 300 es
        layout(location=0) in vec2 aPos;
        layout(location=1) in vec2 aUV;
        out vec2 vUV;
        void main() { vUV = aUV; gl_Position = vec4(aPos, 0.0, 1.0); }"""

    const val POST_FRAG = """#version 300 es
        precision highp float;
        uniform sampler2D tDiffuse;
        uniform sampler2D tBloom;
        uniform float uTime, uDamage, uLowHP, uVignette, uDeath, uScope, uProtect, uFlash, uExposure;
        in vec2 vUV;
        out vec4 fragColor;

        vec3 acesFilm(vec3 x) {
            const float a = 2.51, b = 0.03, c = 2.43, d = 0.59, e = 0.14;
            return clamp((x * (a * x + b)) / (x * (c * x + d) + e), 0.0, 1.0);
        }
        float h(vec2 p) { return fract(sin(dot(p, vec2(12.9898, 78.233))) * 43758.5453); }

        void main() {
            vec2 uv = vUV;
            vec2 c = uv - 0.5;
            float ca = uDamage * 0.004 + uDeath * 0.006;
            vec3 col;
            col.r = texture(tDiffuse, uv + c * ca).r;
            col.g = texture(tDiffuse, uv).g;
            col.b = texture(tDiffuse, uv - c * ca).b;
            col += texture(tBloom, uv).rgb * 0.55;
            // 曝光 + ACES
            col *= uExposure;
            col = acesFilm(col);
            // 线性 -> sRGB
            col = pow(max(col, vec3(0.0)), vec3(1.0 / 2.2));
            float r = length(c * vec2(1.0, 0.8));
            col *= 1.0 - smoothstep(0.35, 0.95, r) * uVignette;
            float edge = smoothstep(0.25, 0.8, r);
            col = mix(col, vec3(0.55, 0.02, 0.0), edge * clamp(uDamage, 0.0, 1.0) * 0.75);
            float g = dot(col, vec3(0.299, 0.587, 0.114));
            float pulse = 0.5 + 0.5 * sin(uTime * 7.0);
            col = mix(col, vec3(g) * vec3(1.08, 0.92, 0.9), uLowHP * 0.55);
            col = mix(col, vec3(0.5, 0.0, 0.0), uLowHP * edge * (0.25 + 0.2 * pulse));
            col = mix(col, vec3(g) * 0.6, uDeath * 0.8);
            col = mix(col, vec3(0.35, 0.6, 1.0), uProtect * edge * 0.18);
            col += uFlash + uScope * 0.001;
            col += (h(uv * 1000.0 + uTime) - 0.5) * 0.018;
            fragColor = vec4(col, 1.0);
        }"""

    const val BRIGHT_FRAG = """#version 300 es
        precision mediump float;
        uniform sampler2D tDiffuse;
        in vec2 vUV;
        out vec4 fragColor;
        void main() {
            vec3 c = texture(tDiffuse, vUV).rgb;
            float l = dot(c, vec3(0.299, 0.587, 0.114));
            float k = max(l - 1.0, 0.0) / max(l, 0.0001);
            fragColor = vec4(c * k, 1.0);
        }"""

    const val BLUR_FRAG = """#version 300 es
        precision mediump float;
        uniform sampler2D tDiffuse;
        uniform vec2 uDir;
        in vec2 vUV;
        out vec4 fragColor;
        void main() {
            vec3 c = texture(tDiffuse, vUV).rgb * 0.227;
            c += (texture(tDiffuse, vUV + uDir * 1.3846).rgb + texture(tDiffuse, vUV - uDir * 1.3846).rgb) * 0.316;
            c += (texture(tDiffuse, vUV + uDir * 3.2308).rgb + texture(tDiffuse, vUV - uDir * 3.2308).rgb) * 0.070;
            fragColor = vec4(c, 1.0);
        }"""
}
