package com.endlessdrive.render

/** GLSL ES 3.00 sources. */
object Shaders {
    const val LIT_VS = """#version 300 es
layout(location=0) in vec3 aPos;
layout(location=1) in vec3 aNormal;
layout(location=2) in vec4 aColor;
uniform mat4 uViewProj;
uniform mat4 uModel;
uniform vec4 uTint;
out vec3 vColor;
out vec3 vNormal;
out vec3 vRel;
out float vEmissive;
void main() {
    vec4 w = uModel * vec4(aPos, 1.0);
    vRel = w.xyz;
    vNormal = mat3(uModel) * aNormal;
    vColor = aColor.rgb * uTint.rgb;
    vEmissive = aColor.a;
    gl_Position = uViewProj * w;
}
"""

    const val LIT_FS = """#version 300 es
precision mediump float;
uniform vec3 uSunDir;
uniform vec3 uSunColor;
uniform vec3 uAmbient;
uniform vec3 uFogColor;
uniform float uFogDensity;
uniform float uNight;
uniform float uWet;
in vec3 vColor;
in vec3 vNormal;
in vec3 vRel;
in float vEmissive;
out vec4 fragColor;
void main() {
    vec3 n = normalize(vNormal);
    float diff = max(dot(n, uSunDir), 0.0);
    vec3 hemi = uAmbient * (0.65 + 0.35 * n.y);
    vec3 c = vColor * (hemi + uSunColor * diff);
    // wet sheen on upward faces in rain
    c *= 1.0 - uWet * 0.25 * max(n.y, 0.0);
    float glow = vEmissive * (0.3 + 0.9 * uNight);
    c = mix(c, vColor * (1.0 + 0.6 * uNight), clamp(glow, 0.0, 1.0));
    float d = length(vRel) * uFogDensity;
    float fog = 1.0 - exp(-d * d);
    fragColor = vec4(mix(c, uFogColor, clamp(fog, 0.0, 1.0)), 1.0);
}
"""

    const val SKY_VS = """#version 300 es
layout(location=0) in vec3 aPos;
uniform mat4 uViewProj;
out vec3 vDir;
void main() {
    vDir = aPos;
    vec4 p = uViewProj * vec4(aPos * 1000.0, 1.0);
    gl_Position = p.xyww; // at the far plane
}
"""

    const val SKY_FS = """#version 300 es
precision mediump float;
uniform vec3 uZenith;
uniform vec3 uHorizon;
uniform vec3 uFogColor;
uniform vec3 uSunDir;
uniform vec3 uSunColor;
uniform float uNight;
in vec3 vDir;
out vec4 fragColor;
void main() {
    vec3 d = normalize(vDir);
    float h = clamp(d.y, 0.0, 1.0);
    vec3 c = mix(uHorizon, uZenith, pow(h, 0.55));
    if (d.y < 0.0) c = uFogColor;
    float sd = max(dot(d, uSunDir), 0.0);
    c += uSunColor * (pow(sd, 400.0) * 1.5 + pow(sd, 12.0) * 0.25) * (1.0 - 0.5 * uNight);
    // stars at night
    float st = fract(sin(dot(floor(d * 300.0), vec3(12.9898, 78.233, 37.719))) * 43758.5453);
    c += vec3(step(0.9985, st) * uNight * h);
    fragColor = vec4(c, 1.0);
}
"""

    const val FX_VS = """#version 300 es
layout(location=0) in vec3 aPos;
layout(location=1) in vec4 aColor;
layout(location=2) in float aSize;
uniform mat4 uViewProj;
uniform float uPointScale;
out vec4 vColor;
void main() {
    vec4 p = uViewProj * vec4(aPos, 1.0);
    gl_Position = p;
    gl_PointSize = clamp(aSize * uPointScale / max(p.w, 0.1), 1.0, 256.0);
    vColor = aColor;
}
"""

    const val FX_FS = """#version 300 es
precision mediump float;
uniform int uRound;
in vec4 vColor;
out vec4 fragColor;
void main() {
    float a = 1.0;
    if (uRound == 1) {
        vec2 c = gl_PointCoord * 2.0 - 1.0;
        float r = dot(c, c);
        if (r > 1.0) discard;
        a = (1.0 - r) * (1.0 - r);
    }
    fragColor = vec4(vColor.rgb, vColor.a * a);
}
"""
}

class Program(val id: Int) {
    private val locs = HashMap<String, Int>()
    fun loc(gl: GL, name: String): Int = locs.getOrPut(name) { gl.getUniformLocation(id, name) }

    companion object {
        fun build(gl: GL, vs: String, fs: String, attribs: Array<String>): Program {
            fun compile(type: Int, src: String): Int {
                val s = gl.createShader(type)
                gl.shaderSource(s, src)
                gl.compileShader(s)
                if (!gl.getShaderCompileStatus(s)) throw IllegalStateException("shader compile: " + gl.getShaderInfoLog(s))
                return s
            }
            val p = gl.createProgram()
            gl.attachShader(p, compile(GLC.VERTEX_SHADER, vs))
            gl.attachShader(p, compile(GLC.FRAGMENT_SHADER, fs))
            for (i in attribs.indices) gl.bindAttribLocation(p, i, attribs[i])
            gl.linkProgram(p)
            if (!gl.getProgramLinkStatus(p)) throw IllegalStateException("link: " + gl.getProgramInfoLog(p))
            return Program(p)
        }
    }
}
