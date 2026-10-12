// 渲染管线：阴影 -> 世界 -> 第一人称 -> 泛光 -> 屏幕特效（移植 render.js + env.js + 三.js 光照）
package com.cf.transportship.engine

import android.graphics.Bitmap
import android.util.Log
import android.opengl.GLES30.*
import com.cf.transportship.game.MatDef
import com.cf.transportship.game.MapMesh
import com.cf.transportship.gfx.Geo
import com.cf.transportship.gfx.Prims
import kotlin.math.cos
import kotlin.math.sin

class Camera {
    var px = 0f; var py = 0f; var pz = 0f
    var pitch = 0f; var yaw = 0f; var roll = 0f
    var fov = 78f
    var aspect = 16f / 9f
    var near = 0.05f
    var far = 3000f
    val pos = Vec3()
    val viewProj = Mat4()
    val view = Mat4()
    val proj = Mat4()

    fun update() {
        pos.set(px, py, pz)
        proj.perspective(Math.toRadians(fov.toDouble()).toFloat(), aspect, near, far)
        val q = Quat().setFromEulerYXZ(pitch, yaw, roll)
        // 相机旋转矩阵（世界->相机 = R^T）
        val r = Mat4().compose(0f, 0f, 0f, q, 1f, 1f, 1f)
        view.identity()
        for (c in 0..2) for (rw in 0..2) view.m[c * 4 + rw] = r.m[rw * 4 + c]
        view.m[12] = -(view.m[0] * px + view.m[4] * py + view.m[8] * pz)
        view.m[13] = -(view.m[1] * px + view.m[5] * py + view.m[9] * pz)
        view.m[14] = -(view.m[2] * px + view.m[6] * py + view.m[10] * pz)
        viewProj.set(proj).multiply(view)
    }

    fun forward(out: Vec3): Vec3 {
        val cp = cos(pitch)
        out.set(-sin(yaw) * cp, sin(pitch), -cos(yaw) * cp)
        return out
    }

    fun right(out: Vec3): Vec3 {
        out.set(cos(yaw), 0f, -sin(yaw))
        return out
    }
}

class PointLight {
    val pos = Vec3()
    var r = 0f; var g = 0f; var b = 0f
    var radius = 9f
    var intensity = 0f
}

class Renderer {
    companion object {
        const val MAX_POINT_LIGHTS = 8
    }

    lateinit var cam: Camera
    lateinit var vmCam: Camera
    var quality = "high"
    var surfaceW = 1; var surfaceH = 1
    var renderW = 1; var renderH = 1

    // 程序
    private lateinit var pMap: Prg
    private lateinit var pSkin: Prg
    private lateinit var pDepth: Prg
    private lateinit var pDepthSkin: Prg
    private lateinit var pParticle: Prg
    private lateinit var pUnlit: Prg
    private lateinit var pSky: Prg
    private lateinit var pClouds: Prg
    private lateinit var pOcean: Prg
    private lateinit var pPost: Prg
    private lateinit var pBright: Prg
    private lateinit var pBlur: Prg
    private var quad: FullscreenQuad? = null

    // FBO
    private var sceneFbo: Fbo? = null
    private var bloomA: Fbo? = null
    private var bloomB: Fbo? = null
    private var shadowFbo: Fbo? = null
    private var shadowTex = 0
    private var shadowSize = 2048

    // 点光源
    val pointLights = Array(MAX_POINT_LIGHTS) { PointLight() }
    var pointCount = 0

    // 太阳 / 雾 / 环境（由 Env 每帧写入）
    val sunDir = Vec3(0.5f, 0.8f, 0.3f)
    val sunColor = FloatArray(3)
    val hemiSky = FloatArray(3)
    val hemiGround = FloatArray(3)
    val fogColor = FloatArray(3)
    var fogDensity = 0.0011f
    var exposure = 0.6f
    val lightMat = Mat4()
    val lightProj = Mat4()

    // 屏幕特效
    var fxTime = 0f; var fxDamage = 0f; var fxLowHP = 0f; var fxVignette = 0.3f
    var fxDeath = 0f; var fxScope = 0f; var fxProtect = 0f; var fxFlash = 0f
    var bloomOn = true

    // 粒子缓冲（由 Effects 填充）
    var addParticles: FloatArray? = null
    var addParticleCount = 0
    var smokeParticles: FloatArray? = null
    var smokeParticleCount = 0
    var particleScale = 800f
    var texGlow = 0; var texPuff = 0; var texSpark = 0
    var texFlash: IntArray = IntArray(3); var texFlashSide = 0
    var texHoleMetal = 0; var texHoleWood = 0; var texScorch = 0; var texBlood = 0
    var texTracer = 0
    var whiteTex = 0

    // 动态网格
    lateinit var decalMesh: DynamicMesh      // UNLIT 布局
    lateinit var tracerMesh: DynamicMesh     // UNLIT 布局
    lateinit var flashMesh: DynamicMesh      // UNLIT 布局（第三人称枪口火焰板）
    lateinit var vmFlashMesh: DynamicMesh    // UNLIT 布局（第一人称）
    lateinit var particleVbo: IntArray

    private val tmpF = FloatArray(16)

    fun init(q: String, w: Int, h: Int) {
        quality = q
        surfaceW = w; surfaceH = h
        if (quad == null) quad = FullscreenQuad()
        pMap = Prg(Shaders.MAP_VERT, Shaders.MAP_FRAG)
        pSkin = Prg(Shaders.SKIN_VERT, Shaders.SKIN_FRAG)
        pDepth = Prg(Shaders.DEPTH_VERT, Shaders.DEPTH_FRAG)
        pDepthSkin = Prg(Shaders.DEPTH_SKIN_VERT, Shaders.DEPTH_FRAG)
        pParticle = Prg(Shaders.PARTICLE_VERT, Shaders.PARTICLE_FRAG)
        pUnlit = Prg(Shaders.UNLIT_VERT, Shaders.UNLIT_FRAG)
        pSky = Prg(Shaders.SKY_VERT, Shaders.SKY_FRAG)
        pClouds = Prg(Shaders.CLOUD_VERT, Shaders.CLOUD_FRAG)
        pOcean = Prg(Shaders.OCEAN_VERT, Shaders.OCEAN_FRAG)
        pPost = Prg(Shaders.POST_VERT, Shaders.POST_FRAG)
        pBright = Prg(Shaders.POST_VERT, Shaders.BRIGHT_FRAG)
        pBlur = Prg(Shaders.POST_VERT, Shaders.BLUR_FRAG)
        decalMesh = DynamicMesh(Layouts.UNLIT, 4096 * 4)
        tracerMesh = DynamicMesh(Layouts.UNLIT, 512 * 4)
        flashMesh = DynamicMesh(Layouts.UNLIT, 128 * 4)
        vmFlashMesh = DynamicMesh(Layouts.UNLIT, 128 * 4)
        val particleVboArr = IntArray(2)
        glGenBuffers(2, particleVboArr, 0)
        particleVbo = particleVboArr
        glEnable(GL_DEPTH_TEST)
        glDepthFunc(GL_LEQUAL)
        glEnable(GL_CULL_FACE)
        glCullFace(GL_BACK)
        glFrontFace(GL_CCW)
        resize(w, h)
    }

    fun resize(w: Int, h: Int) {
        surfaceW = w; surfaceH = h
        // w/h 已是物理像素：按「有效 dpr 上限 / 设备 dpr」折算到物理像素的比例，
        // 高密度屏（如 dpr=3）上 medium 档实际渲染约 0.38 倍边长，避免超原生分辨率
        val dpr = android.content.res.Resources.getSystem().displayMetrics.density
        val cap = when (quality) {
            "high" -> 1.5f
            "medium" -> 1.15f
            else -> 1.0f
        }
        val scale = (cap / kotlin.math.max(dpr, 1f)).coerceAtMost(1f)
        renderW = (w * scale).toInt().coerceAtLeast(64)
        renderH = (h * scale).toInt().coerceAtLeast(64)
        sceneFbo?.dispose()
        bloomA?.dispose(); bloomB?.dispose()
        sceneFbo = try {
            Fbo.create(renderW, renderH, true, true)
        } catch (e: Exception) {
            Fbo.create(renderW, renderH, false, true)
        }
        val bw = renderW / 2; val bh = renderH / 2
        bloomA = try { Fbo.create(bw, bh, true, false) } catch (e: Exception) { Fbo.create(bw, bh, false, false) }
        bloomB = try { Fbo.create(bw, bh, true, false) } catch (e: Exception) { Fbo.create(bw, bh, false, false) }
    }

    fun uploadTex(bmp: Bitmap, srgb: Boolean, repeat: Boolean, mipmap: Boolean = true, aniso: Int = 4): Int =
        GLU.uploadBitmap(bmp, srgb, repeat, mipmap, aniso)

    fun uploadMatTextures(def: MatDef) {
        def.glMap = def.map?.let { GLU.uploadBitmap(it, true, true, aniso = 8) } ?: whiteTex
        def.glNormal = def.normalMap?.let { GLU.uploadBitmap(it, false, true, aniso = 4) } ?: 0
        def.glRough = def.roughnessMap?.let { GLU.uploadBitmap(it, false, true, aniso = 4) } ?: 0
        def.glEmissive = def.emissiveMap?.let { GLU.uploadBitmap(it, true, true) } ?: 0
        def.drawNormalMap = def.normalMap != null
    }

    fun createShadowMap(size: Int) {
        shadowSize = size
        shadowFbo?.dispose()
        if (shadowTex != 0) GLU.deleteTex(shadowTex)
        shadowTex = GLU.createDepthTex(size, size)
        val fbo = IntArray(1)
        glGenFramebuffers(1, fbo, 0)
        glBindFramebuffer(GL_FRAMEBUFFER, fbo[0])
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_TEXTURE_2D, shadowTex, 0)
        glDrawBuffers(1, intArrayOf(GL_NONE), 0)
        glReadBuffer(GL_NONE)
        if (glCheckFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE) {
            throw RuntimeException("shadow FBO incomplete")
        }
        glBindFramebuffer(GL_FRAMEBUFFER, 0)
        shadowFbo = Fbo(size, size, 0, fbo[0])
    }

    /** 太阳阴影矩阵：包住整艘可见船体 */
    fun updateLightMat(target: Vec3) {
        val sunPos = Vec3(target.x + sunDir.x * 120f, target.y + sunDir.y * 120f, target.z + sunDir.z * 120f)
        val lview = Mat4().lookAt(sunPos, target, Vec3(0f, 1f, 0f))
        // 船体包围盒 8 角转到光空间
        var minX = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE
        var minY = Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        var minZ = Float.MAX_VALUE; var maxZ = -Float.MAX_VALUE
        for (x in floatArrayOf(-58f, 40f)) for (y in floatArrayOf(-1f, 26f)) for (z in floatArrayOf(-15f, 15f)) {
            val lp = lview.transformPoint(Vec3(x, y, z))
            minX = kotlin.math.min(minX, lp.x); maxX = kotlin.math.max(maxX, lp.x)
            minY = kotlin.math.min(minY, lp.y); maxY = kotlin.math.max(maxY, lp.y)
            minZ = kotlin.math.min(minZ, lp.z); maxZ = kotlin.math.max(maxZ, lp.z)
        }
        lightProj.identity()
        // lookAt 后点在 -z 方向；ortho 直接用变换坐标
        val proj = Mat4().ortho(minX, maxX, minY, maxY, -maxZ + 10f, -minZ + 10f)
        lightProj.set(proj).multiply(lview)
        // 裁剪空间 z: [-1,1] -> [0,1]
        val bias = Mat4().identity()
        bias.m[0] = 0.5f; bias.m[5] = 0.5f; bias.m[10] = 0.5f
        bias.m[12] = 0.5f; bias.m[13] = 0.5f; bias.m[14] = 0.5f
        lightMat.set(bias).multiply(lightProj)
    }

    fun staticMeshOf(geo: Geo, layout: Layout): StaticMesh {
        // 展开为交错数组
        val n = geo.vertCount
        val stride = layout.stride / 4
        val verts = FloatArray(n * stride)
        for (i in 0 until n) {
            var o = i * stride
            verts[o] = geo.pos[i * 3]; verts[o + 1] = geo.pos[i * 3 + 1]; verts[o + 2] = geo.pos[i * 3 + 2]
            if (layout === Layouts.MAP) {
                verts[o + 3] = geo.nrm[i * 3]; verts[o + 4] = geo.nrm[i * 3 + 1]; verts[o + 5] = geo.nrm[i * 3 + 2]
                verts[o + 6] = geo.uv[i * 2]; verts[o + 7] = geo.uv[i * 2 + 1]
                verts[o + 8] = 1f; verts[o + 9] = 1f
            } else if (layout === Layouts.SKIN) {
                // 调用者负责填充 color/skin —— 此处只填 pos/normal/uv
                verts[o + 3] = geo.nrm[i * 3]; verts[o + 4] = geo.nrm[i * 3 + 1]; verts[o + 5] = geo.nrm[i * 3 + 2]
                verts[o + 6] = geo.uv[i * 2]; verts[o + 7] = geo.uv[i * 2 + 1]
            } else if (layout === Layouts.UNLIT) {
                verts[o + 3] = geo.uv[i * 2]; verts[o + 4] = geo.uv[i * 2 + 1]
            } else if (layout === Layouts.POS_UV) {
                verts[o + 3] = geo.nrm[i * 3]; verts[o + 4] = geo.nrm[i * 3 + 1]; verts[o + 5] = geo.nrm[i * 3 + 2]
                verts[o + 6] = geo.uv[i * 2]; verts[o + 7] = geo.uv[i * 2 + 1]
            }
        }
        return StaticMesh(verts, geo.idx, layout)
    }

    // ---------- 帧渲染 ----------
    fun beginScene() {
        val sf = sceneFbo!!
        sf.bind()
        glClearColor(0f, 0f, 0f, 1f)
        glClear(GL_COLOR_BUFFER_BIT or GL_DEPTH_BUFFER_BIT)
        glEnable(GL_DEPTH_TEST)
        glDisable(GL_BLEND)
        glEnable(GL_CULL_FACE)
    }

    // 预分配：避免每帧在 GL 线程产生垃圾
    private val ppBuf = FloatArray(MAX_POINT_LIGHTS * 4)
    private val pcBuf = FloatArray(MAX_POINT_LIGHTS * 4)
    private val identityM = Mat4()

    fun setCameraUniforms(p: Prg, viewProj: Mat4, camPos: Vec3) {
        p.use()
        p.mat4("uViewProj", viewProj.m)
        p.mat4("uLightMat", lightMat.m)
        p.vec3("uCamPos", camPos)
        p.vec3("uSunDir", sunDir)
        p.vec3v("uSunColor", sunColor)
        p.vec3v("uHemiSky", hemiSky)
        p.vec3v("uHemiGround", hemiGround)
        p.vec3v("uFogColor", fogColor)
        p.f("uFogDensity", fogDensity)
        // 点光源
        val pp = ppBuf
        val pc = pcBuf
        for (i in 0 until MAX_POINT_LIGHTS) {
            val L = pointLights[i]
            pp[i * 4] = L.pos.x; pp[i * 4 + 1] = L.pos.y; pp[i * 4 + 2] = L.pos.z
            pp[i * 4 + 3] = L.radius
            pc[i * 4] = L.r * L.intensity; pc[i * 4 + 1] = L.g * L.intensity; pc[i * 4 + 2] = L.b * L.intensity; pc[i * 4 + 3] = 1f
        }
        p.vec4vArray("uPointPos", pp, MAX_POINT_LIGHTS)
        p.vec4vArray("uPointCol", pc, MAX_POINT_LIGHTS)
        p.i("uPointCount", pointCount)
        p.f("uShadowOn", if (shadowFbo != null) 1f else 0f)
        glActiveTexture(GL_TEXTURE3)
        glBindTexture(GL_TEXTURE_2D, shadowTex)
        p.i("uShadowMap", 3)
    }

    fun setMatUniforms(p: Prg, def: MatDef, aoTex: Int) {
        glActiveTexture(GL_TEXTURE0)
        glBindTexture(GL_TEXTURE_2D, def.glMap)
        p.i("uMap", 0)
        glActiveTexture(GL_TEXTURE1)
        glBindTexture(GL_TEXTURE_2D, def.glNormal)
        p.bool("uHasNormalMap", def.drawNormalMap)
        p.i("uNormalMap", 1)
        glActiveTexture(GL_TEXTURE2)
        glBindTexture(GL_TEXTURE_2D, aoTex)
        p.i("uAOMap", 2)
        p.bool("uUseAO", aoTex != 0)
        p.vec3v("uColor", def.color)
        p.f("uRoughness", def.roughness)
        p.f("uMetalness", def.metalness)
        p.vec3v("uEmissive", def.emissive)
        p.f("uAlphaTest", def.alphaTest)
        if (def.twoSided) glDisable(GL_CULL_FACE) else glEnable(GL_CULL_FACE)
    }

    fun drawStaticBatches(batches: List<Triple<MatDef, StaticMesh, Int>>, aoTex: Int) {
        setCameraUniforms(pMap, cam.viewProj, cam.pos)
        pMap.mat4("uModel", identityM.m)
        for ((def, mesh, _) in batches) {
            setMatUniforms(pMap, def, if (def.key == "deck") aoTex else 0)
            mesh.draw()
        }
        glEnable(GL_CULL_FACE)
    }

    fun drawMapMesh(mesh: MapMesh, meshes: Map<String, StaticMesh>, viewProj: Mat4, camPos: Vec3, p: Prg? = null) {
        val prog = p ?: pMap
        setCameraUniforms(prog, viewProj, camPos)
        prog.mat4("uModel", mesh.model.m)
        for ((def, _) in mesh.parts) {
            val sm = meshes[def.key] ?: continue
            setMatUniforms(prog, def, 0)
            sm.draw()
        }
        glEnable(GL_CULL_FACE)
    }

    fun endScene() {}

    fun bindScreen() {
        glBindFramebuffer(GL_FRAMEBUFFER, 0)
        glViewport(0, 0, surfaceW, surfaceH)
    }

    fun drawSky(camPos: Vec3) {
        glDepthMask(false)
        glDisable(GL_CULL_FACE)
        pSky.use()
        pSky.mat4("uViewProj", cam.viewProj.m)
        pSky.vec3("uCamPos", camPos)
        pSky.vec3("uSunDir", sunDir)
        pSky.f("uTurbidity", skyTurbidity)
        pSky.f("uRayleigh", skyRayleigh)
        pSky.f("uMieCoefficient", skyMie)
        pSky.f("uMieDirectionalG", skyMieG)
        skyCube.draw()
        glEnable(GL_CULL_FACE)
        glDepthMask(true)
    }

    lateinit var skyCube: StaticMesh
    lateinit var cloudMesh: StaticMesh
    lateinit var oceanMesh: StaticMesh

    fun initEnvMeshes() {
        // 天空盒
        val s = 1f
        val g = Prims.box(s * 2, s * 2, s * 2)
        skyCube = staticMeshOf(g, Layouts.MAP)
        // 云层平面
        val cg = Prims.box(60000f, 0.01f, 60000f)
        cloudMesh = staticMeshOf(cg, Layouts.MAP)
        // 海洋极坐标网格
        val rings = 110; val segs = 160
        val posA = ArrayList<Float>(rings * segs * 3)
        val idxA = ArrayList<Int>(rings * segs * 6)
        for (r in 0..rings) {
            val rad = if (r == 0) 0f else 6f * Math.pow(1.058, r.toDouble()).toFloat() - 5f
            for (sg in 0 until segs) {
                val a = sg.toFloat() / segs * 2f * Math.PI.toFloat()
                posA.add(cos(a) * rad); posA.add(0f); posA.add(sin(a) * rad)
            }
        }
        for (r in 0 until rings) for (sg in 0 until segs) {
            val a = r * segs + sg
            val b = r * segs + (sg + 1) % segs
            val c2 = a + segs
            val d = b + segs
            idxA.add(a); idxA.add(b); idxA.add(c2)
            idxA.add(b); idxA.add(d); idxA.add(c2)
        }
        val n = posA.size / 3
        val nrm = FloatArray(n * 3)
        for (i in 0 until n) nrm[i * 3 + 1] = 1f
        val uvz = FloatArray(n * 2)
        oceanMesh = StaticMesh(interleave(posA.toFloatArray(), nrm, uvz, Layouts.MAP), idxA.toIntArray(), Layouts.MAP)
    }

    private fun interleave(pos: FloatArray, nrm: FloatArray, uv: FloatArray, layout: Layout): FloatArray {
        val n = pos.size / 3
        val stride = layout.stride / 4
        val out = FloatArray(n * stride)
        for (i in 0 until n) {
            val o = i * stride
            out[o] = pos[i * 3]; out[o + 1] = pos[i * 3 + 1]; out[o + 2] = pos[i * 3 + 2]
            out[o + 3] = nrm[i * 3]; out[o + 4] = nrm[i * 3 + 1]; out[o + 5] = nrm[i * 3 + 2]
            out[o + 6] = uv[i * 2]; out[o + 7] = uv[i * 2 + 1]
            out[o + 8] = 1f; out[o + 9] = 1f
        }
        return out
    }

    // 云/海洋 uniform 状态
    var skyTurbidity = 3f; var skyRayleigh = 1.8f; var skyMie = 0.0022f; var skyMieG = 0.76f
    val oceanSunColor = FloatArray(3)
    val oceanDeep = FloatArray(3); val oceanShallow = FloatArray(3)
    val oceanZen = FloatArray(3); val oceanHor = FloatArray(3)
    val cloudLit = FloatArray(3); val cloudShade = FloatArray(3)
    var cloudCover = 0.47f
    var shipDist = 0f

    private val waveData = FloatArray(24)
    fun setWaves(waves: List<FloatArray>) {
        java.util.Arrays.fill(waveData, 0f)
        for (i in waves.indices) {
            val w = waves[i]
            val l = kotlin.math.sqrt(w[0] * w[0] + w[1] * w[1])
            waveData[i * 4] = w[0] / l; waveData[i * 4 + 1] = w[1] / l; waveData[i * 4 + 2] = w[2]; waveData[i * 4 + 3] = w[3]
        }
        // 空槽填入长波长的零陡度波，避免 shader 里 k=6.28/wavelength 出现 inf/NaN
        for (i in waves.size until 6) {
            waveData[i * 4] = 1f; waveData[i * 4 + 1] = 0f; waveData[i * 4 + 2] = 0f; waveData[i * 4 + 3] = 1000f
        }
    }

    fun drawOcean(t: Float) {
        pOcean.use()
        pOcean.f("uTime", t)
        pOcean.f("uShip", shipDist)
        pOcean.vec4vArray("uWaves", waveData, 6)
        pOcean.vec3("uSunDir", sunDir)
        pOcean.vec3v("uSunColor", oceanSunColor)
        pOcean.vec3v("uDeep", oceanDeep)
        pOcean.vec3v("uShallow", oceanShallow)
        pOcean.vec3v("uZen", oceanZen)
        pOcean.vec3v("uHor", oceanHor)
        pOcean.vec3v("uFog", fogColor)
        pOcean.f("uFogDensity", fogDensity)
        pOcean.vec3("uCamPos", cam.pos)
        // 海洋网格顶点在 XZ 平面（y=0），平移到 SEA_Y
        val model = Mat4().identity()
        model.m[13] = -7.5f
        val vp = Mat4().set(cam.viewProj).multiply(model)
        pOcean.mat4("uViewProj", vp.m)
        oceanMesh.draw()
    }

    fun drawClouds(t: Float) {
        glDisable(GL_CULL_FACE)
        glDepthMask(false)
        glEnable(GL_BLEND)
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA)
        pClouds.use()
        val model = Mat4().identity()
        model.m[13] = 1800f
        pClouds.mat4("uViewProj", cam.viewProj.m)
        pClouds.mat4("uModel", model.m)
        pClouds.f("uTime", t)
        pClouds.f("uCover", cloudCover)
        pClouds.vec3("uSunDir", sunDir)
        pClouds.vec3v("uLit", cloudLit)
        pClouds.vec3v("uShade", cloudShade)
        pClouds.vec3v("uFog", fogColor)
        pClouds.vec3("uCamPos", cam.pos)
        cloudMesh.draw()
        glDepthMask(true)
        glDisable(GL_BLEND)
        glEnable(GL_CULL_FACE)
    }

    // 粒子上传缓冲（按 add/smoke 两个槽复用，避免每帧分配直接内存）
    private val particleBuf = arrayOfNulls<java.nio.FloatBuffer>(2)

    fun drawParticles(tex: Int, data: FloatArray?, count: Int, additive: Boolean) {
        if (data == null || count == 0) return
        pParticle.use()
        pParticle.mat4("uViewProj", cam.viewProj.m)
        pParticle.f("uScale", particleScale)
        glActiveTexture(GL_TEXTURE0)
        glBindTexture(GL_TEXTURE_2D, tex)
        pParticle.i("uMap", 0)
        glEnable(GL_BLEND)
        if (additive) glBlendFunc(GL_SRC_ALPHA, GL_ONE) else glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA)
        glDepthMask(false)
        glDisable(GL_CULL_FACE)
        val slot = if (additive) 0 else 1
        var fb = particleBuf[slot]
        if (fb == null || fb.capacity() < data.size) {
            fb = java.nio.ByteBuffer.allocateDirect(data.size * 4).order(java.nio.ByteOrder.nativeOrder()).asFloatBuffer()
            particleBuf[slot] = fb
        }
        fb!!.clear()
        fb.put(data, 0, count * 8)
        fb.position(0)
        val vbo = particleVbo[slot]
        glBindBuffer(GL_ARRAY_BUFFER, vbo)
        glBufferData(GL_ARRAY_BUFFER, count * 32, fb, GL_DYNAMIC_DRAW)
        glEnableVertexAttribArray(0)
        glVertexAttribPointer(0, 3, GL_FLOAT, false, 32, 0)
        glEnableVertexAttribArray(1)
        glVertexAttribPointer(1, 4, GL_FLOAT, false, 32, 12)
        glEnableVertexAttribArray(2)
        glVertexAttribPointer(2, 1, GL_FLOAT, false, 32, 28)
        glDrawArrays(GL_POINTS, 0, count)
        glBindBuffer(GL_ARRAY_BUFFER, 0)
        glDepthMask(true)
        glDisable(GL_BLEND)
        glEnable(GL_CULL_FACE)
    }

    fun drawUnlit(mesh: DynamicMesh, tex: Int, tint: FloatArray, additive: Boolean, fogOn: Boolean) {
        if (mesh.vertCount == 0) return
        pUnlit.use()
        pUnlit.mat4("uViewProj", cam.viewProj.m)
        glActiveTexture(GL_TEXTURE0)
        glBindTexture(GL_TEXTURE_2D, tex)
        pUnlit.i("uMap", 0)
        pUnlit.vec4("uTint", tint)
        pUnlit.vec3v("uFogColor", fogColor)
        pUnlit.f("uFogDensity", fogDensity)
        pUnlit.vec3("uCamPos", cam.pos)
        pUnlit.bool("uFogOn", fogOn)
        glEnable(GL_BLEND)
        if (additive) glBlendFunc(GL_SRC_ALPHA, GL_ONE) else glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA)
        glDepthMask(false)
        glDisable(GL_CULL_FACE)
        mesh.uploadAndDraw()
        glDepthMask(true)
        glDisable(GL_BLEND)
        glEnable(GL_CULL_FACE)
    }

    /** 阴影通道 */
    fun shadowPass(drawMap: () -> Unit, drawSkin: (Prg) -> Unit) {
        val sf = shadowFbo ?: return
        sf.bind()
        glClearColor(1f, 1f, 1f, 1f)
        glClear(GL_DEPTH_BUFFER_BIT)
        glEnable(GL_DEPTH_TEST)
        glColorMask(false, false, false, false)
        pDepth.use()
        pDepth.mat4("uLightProj", lightProj.m)
        drawMap()
        pDepthSkin.use()
        pDepthSkin.mat4("uLightProj", lightProj.m)
        drawSkin(pDepthSkin)
        glColorMask(true, true, true, true)
        glBindFramebuffer(GL_FRAMEBUFFER, 0)
    }

    /** 后期：bloom + 合成 */
    fun composite() {
        val sc = sceneFbo!!
        if (bloomOn) {
            bloomA!!.bind()
            glDisable(GL_DEPTH_TEST)
            pBright.use()
            glActiveTexture(GL_TEXTURE0)
            glBindTexture(GL_TEXTURE_2D, sc.colorTex)
            pBright.i("tDiffuse", 0)
            quad?.draw()
            // 横向模糊
            bloomB!!.bind()
            pBlur.use()
            glActiveTexture(GL_TEXTURE0)
            glBindTexture(GL_TEXTURE_2D, bloomA!!.colorTex)
            pBlur.i("tDiffuse", 0)
            pBlur.vec2("uDir", 1f / bloomA!!.w, 0f)
            quad?.draw()
            // 纵向模糊（回到 bloomA）
            bloomA!!.bind()
            glActiveTexture(GL_TEXTURE0)
            glBindTexture(GL_TEXTURE_2D, bloomB!!.colorTex)
            pBlur.i("tDiffuse", 0)
            pBlur.vec2("uDir", 0f, 1f / bloomA!!.h)
            quad?.draw()
        }
        bindScreen()
        glDisable(GL_DEPTH_TEST)
        pPost.use()
        glActiveTexture(GL_TEXTURE0)
        glBindTexture(GL_TEXTURE_2D, sc.colorTex)
        pPost.i("tDiffuse", 0)
        glActiveTexture(GL_TEXTURE1)
        glBindTexture(GL_TEXTURE_2D, if (bloomOn) bloomA!!.colorTex else blackTex)
        pPost.i("tBloom", 1)
        pPost.f("uTime", fxTime)
        pPost.f("uDamage", fxDamage)
        pPost.f("uLowHP", fxLowHP)
        pPost.f("uVignette", fxVignette)
        pPost.f("uDeath", fxDeath)
        pPost.f("uScope", fxScope)
        pPost.f("uProtect", fxProtect)
        pPost.f("uFlash", fxFlash)
        pPost.f("uExposure", exposure)
        quad?.draw()
        glEnable(GL_DEPTH_TEST)
    }

    private var blackTex = 0
    fun makeBlackTex() {
        val b = android.graphics.Bitmap.createBitmap(1, 1, android.graphics.Bitmap.Config.ARGB_8888)
        b.eraseColor(0)
        blackTex = GLU.uploadBitmap(b, false, false, mipmap = false)
        whiteTex = GLU.uploadBitmap(android.graphics.Bitmap.createBitmap(1, 1, android.graphics.Bitmap.Config.ARGB_8888).apply {
            eraseColor(-1)
        }, true, false, mipmap = false)
    }

    fun dispose() {
        sceneFbo?.dispose(); bloomA?.dispose(); bloomB?.dispose()
    }

    // ---------- Game 使用的便捷 API ----------
    fun pDepthPrg(): Prg = pDepth
    fun pSkinPrg(): Prg = pSkin
    fun sceneFbo(): Fbo? = sceneFbo

    /** 使用 MAP 程序绘制带模型矩阵的网格 */
    fun drawMatMesh(def: MatDef, mesh: StaticMesh, model: Mat4) {
        setCameraUniforms(pMap, cam.viewProj, cam.pos)
        pMap.mat4("uModel", model.m)
        setMatUniforms(pMap, def, 0)
        mesh.draw()
        glEnable(GL_CULL_FACE)
    }

    private val gunModelM = Mat4()
    private var gunLightSaved = FloatArray(0)

    /** 世界空间枪械网格（带贴图材质） */
    fun drawGunMesh(mat: com.cf.transportship.gfx.GunMat, mesh: StaticMesh, model: Mat4, vp: Mat4, camPos: Vec3) {
        setCameraUniforms(pMap, vp, camPos)
        pMap.mat4("uModel", model.m)
        glActiveTexture(GL_TEXTURE0)
        glBindTexture(GL_TEXTURE_2D, if (mat.glTex != 0) mat.glTex else whiteTex)
        pMap.i("uMap", 0)
        pMap.bool("uHasNormalMap", false)
        pMap.i("uNormalMap", 1)
        glActiveTexture(GL_TEXTURE2)
        glBindTexture(GL_TEXTURE_2D, 0)
        pMap.i("uAOMap", 2)
        pMap.bool("uUseAO", false)
        pMap.vec3v("uColor", mat.color)
        pMap.f("uRoughness", mat.roughness)
        pMap.f("uMetalness", mat.metalness)
        pMap.vec3v("uEmissive", floatArrayOf(0f, 0f, 0f))
        pMap.f("uAlphaTest", 0f)
        mesh.draw()
    }

    /** vm 空间（使用外部 viewProj） */
    fun drawGunMeshVm(mat: com.cf.transportship.gfx.GunMat, mesh: StaticMesh, model: Mat4, vp: Mat4) {
        drawGunMesh(mat, mesh, model, vp, Vec3(0f, 0f, 0f))
    }

    fun drawGunMeshVmColor(mesh: StaticMesh, model: Mat4, vp: Mat4, color: FloatArray, rough: Float, metal: Float) {
        val m = com.cf.transportship.gfx.GunMat("tmp", color, rough, metal)
        drawGunMesh(m, mesh, model, vp, Vec3(0f, 0f, 0f))
    }

    fun drawGunMeshVmColorBrass(mesh: StaticMesh, model: Mat4, vp: Mat4) {
        val m = com.cf.transportship.gfx.GunMat("brass", floatArrayOf(0.58f, 0.36f, 0.07f), 0.3f, 1f)
        drawGunMesh(m, mesh, model, vp, Vec3(0f, 0f, 0f))
    }

    fun drawUnlitVm(mesh: DynamicMesh, tex: Int, vp: Mat4) {
        if (mesh.vertCount == 0) return
        pUnlit.use()
        pUnlit.mat4("uViewProj", vp.m)
        glActiveTexture(GL_TEXTURE0)
        glBindTexture(GL_TEXTURE_2D, tex)
        pUnlit.i("uMap", 0)
        pUnlit.vec4("uTint", floatArrayOf(1f, 0.85f, 0.63f, 1f))
        pUnlit.vec3v("uFogColor", fogColor)
        pUnlit.f("uFogDensity", 0f)
        pUnlit.vec3("uCamPos", Vec3(0f, 0f, 0f))
        pUnlit.bool("uFogOn", false)
        glEnable(GL_BLEND)
        glBlendFunc(GL_SRC_ALPHA, GL_ONE)
        glDepthMask(false)
        glDisable(GL_CULL_FACE)
        mesh.uploadAndDraw()
        glDepthMask(true)
        glDisable(GL_BLEND)
        glEnable(GL_CULL_FACE)
    }

    fun clearDepth() {
        glClear(GL_DEPTH_BUFFER_BIT)
    }
}


/** 着色器程序封装（缓存 uniform 位置） */
class Prg(val id: Int) {
    private val cache = HashMap<String, Int>()

    fun use() = glUseProgram(id)

    private fun loc(name: String): Int = cache.getOrPut(name) { glGetUniformLocation(id, name) }

    fun mat4(name: String, m: FloatArray) {
        glUniformMatrix4fv(loc(name), 1, false, m, 0)
    }

    fun mat4fv(name: String, m: FloatArray, count: Int) {
        glUniformMatrix4fv(loc(name), count, false, m, 0)
    }

    fun vec3(name: String, v: Vec3) {
        glUniform3f(loc(name), v.x, v.y, v.z)
    }

    fun vec3v(name: String, v: FloatArray) {
        glUniform3f(loc(name), v[0], v[1], v[2])
    }

    fun vec4(name: String, v: FloatArray) {
        glUniform4f(loc(name), v[0], v[1], v[2], v[3])
    }

    fun vec4vArray(name: String, v: FloatArray, count: Int) {
        glUniform4fv(loc(name), count, v, 0)
    }

    fun vec2(name: String, x: Float, y: Float) {
        glUniform2f(loc(name), x, y)
    }

    fun f(name: String, v: Float) {
        glUniform1f(loc(name), v)
    }

    fun i(name: String, v: Int) {
        glUniform1i(loc(name), v)
    }

    fun bool(name: String, v: Boolean) {
        glUniform1i(loc(name), if (v) 1 else 0)
    }

    companion object {
        operator fun invoke(vsrc: String, fsrc: String): Prg = Prg(GLU.buildProgram(vsrc, fsrc))
    }
}
