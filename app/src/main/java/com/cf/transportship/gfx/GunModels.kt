// 枪械模型：程序化构建。局部坐标：枪口朝 -Z，上 +Y，右 +X，原点在握把上方（移植 guns.js）
package com.cf.transportship.gfx

import android.graphics.Bitmap
import com.cf.transportship.engine.Mat4
import com.cf.transportship.engine.Quat
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

class GunPart(
    val name: String?,                       // null = 静态
    val pieces: List<Pair<GunMat, Geo>>,     // 相对部件原点
    val restPos: FloatArray,                 // 枪身空间原点
    val restRot: FloatArray,
)

class GunModel(val parts: List<GunPart>, val anchors: Map<String, FloatArray>)

class GunMat(
    val key: String,
    val color: FloatArray,   // 线性 RGB
    val roughness: Float,
    val metalness: Float,
    val map: Bitmap? = null,
) {
    var glTex = 0
}

class GunMaterials {
    val metal = GunMat("metal", linear3(0x2b2d30), 0.42f, 0.85f)
    val black = GunMat("black", linear3(0x16171a), 0.6f, 0.35f)
    val steel = GunMat("steel", linear3(0x8e939a), 0.28f, 0.95f)
    val wood: GunMat
    val bakelite = GunMat("bakelite", linear3(0x6a2e14), 0.45f, 0.1f)
    val olive = GunMat("olive", linear3(0x4d5638), 0.62f, 0.2f)
    val tan = GunMat("tan", linear3(0x8f7a55), 0.6f, 0.15f)
    val rubber = GunMat("rubber", linear3(0x121212), 0.85f, 0f)
    val brass = GunMat("brass", linear3(0xc8a04a), 0.3f, 1f)
    val glass = GunMat("glass", floatArrayOf(0.002f, 0.01f, 0.02f), 0.05f, 0.9f)
    val blade = GunMat("blade", linear3(0xc9ced4), 0.18f, 1f)
    val red = GunMat("red", linear3(0x8a1a14), 0.5f, 0.2f)

    companion object {
        fun linear3(hex: Int): FloatArray {
            val r = ((hex shr 16) and 0xff) / 255f
            val g = ((hex shr 8) and 0xff) / 255f
            val b = (hex and 0xff) / 255f
            return floatArrayOf(srgbToLinearF(r), srgbToLinearF(g), srgbToLinearF(b))
        }
        fun srgbToLinearF(v: Float): Float =
            if (v <= 0.04045f) v / 12.92f else Math.pow(((v + 0.055) / 1.055).toDouble(), 2.4).toFloat()
    }

    init {
        // 木纹
        val S = 128
        val g = fbm(S, S, 1, 24, 4, 777)
        val ctx = Ctx(S, S)
        val px = IntArray(S * S)
        for (i in 0 until S * S) {
            val v = 0.65f + g[i] * 0.55f
            px[i] = -0x1000000 or (((120 * v).toInt().coerceIn(0, 255)) shl 16) or
                (((62 * v).toInt().coerceIn(0, 255)) shl 8) or (30 * v).toInt().coerceIn(0, 255)
        }
        ctx.setPx(px)
        wood = GunMat("wood", floatArrayOf(1f, 1f, 1f), 0.55f, 0.05f, ctx.bmp)
    }
}

/** 构建辅助：把几何变换后加入列表 */
class GunBuilder(val M: GunMaterials) {
    private val parts = ArrayList<GunPart>()
    private val anchors = HashMap<String, FloatArray>()

    /** 静态件：烘焙进枪身空间 */
    fun add(mat: GunMat, geo: Geo, x: Float, y: Float, z: Float, rx: Float = 0f, ry: Float = 0f, rz: Float = 0f, sx: Float = 1f, sy: Float = 1f, sz: Float = 1f) {
        val m = trs(x, y, z, rx, ry, rz, sx, sy, sz)
        parts.add(GunPart(null, listOf(Pair(mat, geo.clone().applyMatrix(m))), floatArrayOf(0f, 0f, 0f), floatArrayOf(0f, 0f, 0f)))
    }

    /** 命名件：几何相对原点，原点为 restPos */
    fun named(name: String, origin: FloatArray, rot: FloatArray = floatArrayOf(0f, 0f, 0f), build: (GunBuilder) -> Unit) {
        val before = parts.size
        build(this)
        val pieces = ArrayList<Pair<GunMat, Geo>>(parts.size - before)
        for (i in before until parts.size) pieces.addAll(parts[i].pieces)
        while (parts.size > before) parts.removeAt(parts.size - 1)
        parts.add(GunPart(name, pieces, origin, rot))
    }

    fun anchor(name: String, x: Float, y: Float, z: Float) { anchors[name] = floatArrayOf(x, y, z) }

    fun build(): GunModel = GunModel(parts.toList(), anchors)

    companion object {
        fun trs(x: Float, y: Float, z: Float, rx: Float, ry: Float, rz: Float, sx: Float = 1f, sy: Float = 1f, sz: Float = 1f): Mat4 {
            val qx = Quat().setFromAxisAngle(1f, 0f, 0f, rx)
            val qy = Quat().setFromAxisAngle(0f, 1f, 0f, ry)
            val qz = Quat().setFromAxisAngle(0f, 0f, 1f, rz)
            val q = qx.copy().multiply(qy).multiply(qz)
            return Mat4().compose(x, y, z, q, sx, sy, sz)
        }

        /** 弯弹匣 */
        fun curvedMag(out: GunBuilder, mat: GunMat, segs: Int, x0: Float, y0: Float, z0: Float, w: Float, segH: Float, segD: Float, curve: Float) {
            out.named("mag", floatArrayOf(x0, y0, z0)) { b ->
                var y = 0f; var z = 0f; var a = 0f
                for (i in 0 until segs) {
                    b.add(mat, Prims.box(w, segH + 0.004f, segD), 0f, y - segH / 2, z, -a, 0f, 0f)
                    y -= cos(a) * segH; z -= sin(a) * segH
                    a += curve
                }
                b.add(mat, Prims.box(w + 0.004f, 0.012f, segD + 0.006f), 0f, y - 0.004f, z, -a + curve, 0f, 0f)
            }
        }
    }
}

private fun rounded(w: Float, h: Float, d: Float): Geo = Prims.box(w, h, d)
private fun rbox(w: Float, h: Float, d: Float): Geo = Prims.box(w, h, d)

/** CZ: 圆柱轴沿 Z */
private fun cz(r: Float, len: Float, seg: Int = 14): Geo {
    val g = Prims.cylinder(r, r, len, seg)
    g.applyMatrix(GunBuilder.trs(0f, 0f, 0f, (PI / 2).toFloat(), 0f, 0f))
    return g
}

/** CX: 圆柱轴沿 X */
private fun cx(r: Float, len: Float, seg: Int = 10): Geo {
    val g = Prims.cylinder(r, r, len, seg)
    g.applyMatrix(GunBuilder.trs(0f, 0f, 0f, 0f, 0f, (PI / 2).toFloat()))
    return g
}

private fun cy(r: Float, len: Float, seg: Int = 10): Geo = Prims.cylinder(r, r, len, seg)

// ---------- AK-47 ----------
private fun buildAk47(b: GunBuilder) {
    val m = b.M
    b.add(m.metal, rbox(0.046f, 0.058f, 0.25f), 0f, 0.035f, -0.05f)
    b.add(m.metal, rbox(0.043f, 0.022f, 0.21f), 0f, 0.07f, -0.03f)
    b.add(m.metal, Prims.box(0.03f, 0.02f, 0.05f), 0f, 0.08f, -0.175f)
    b.add(m.wood, rbox(0.052f, 0.046f, 0.2f), 0f, 0.022f, -0.275f)
    b.add(m.wood, cz(0.019f, 0.17f), 0f, 0.066f, -0.26f)
    b.add(m.metal, cz(0.0085f, 0.3f), 0f, 0.042f, -0.47f)
    b.add(m.metal, cz(0.006f, 0.26f), 0f, 0.066f, -0.43f)
    b.add(m.metal, Prims.box(0.02f, 0.038f, 0.022f), 0f, 0.066f, -0.55f)
    b.add(m.metal, Prims.box(0.004f, 0.02f, 0.004f), 0f, 0.093f, -0.55f)
    b.add(m.metal, cz(0.013f, 0.05f), 0f, 0.042f, -0.63f)
    b.add(m.metal, Prims.box(0.03f, 0.012f, 0.03f), 0f, 0.018f, -0.39f)
    GunBuilder.curvedMag(b, m.bakelite, 5, 0f, 0.005f, -0.1f, 0.03f, 0.045f, 0.07f, 0.11f)
    b.add(m.bakelite, rbox(0.03f, 0.1f, 0.042f), 0f, -0.045f, 0.035f, 0.32f, 0f, 0f)
    b.add(m.metal, Prims.box(0.006f, 0.006f, 0.06f), 0f, -0.012f, -0.015f)
    b.add(m.metal, Prims.box(0.004f, 0.03f, 0.004f), 0f, -0.018f, -0.03f)
    // 木托
    b.add(m.wood, rbox(0.042f, 0.068f, 0.27f), 0f, 0.004f, 0.2f, -0.1f, 0f, 0f)
    b.add(m.metal, Prims.box(0.044f, 0.1f, 0.012f), 0f, -0.008f, 0.335f, -0.1f, 0f, 0f)
    b.named("bolt", floatArrayOf(0.034f, 0.06f, -0.08f)) { bb -> bb.add(m.steel, cx(0.006f, 0.03f), 0f, 0f, 0f) }
    b.anchor("grip", 0f, -0.035f, 0.03f)
    b.anchor("fore", 0f, 0f, -0.29f)
    b.anchor("muzzle", 0f, 0.042f, -0.66f)
    b.anchor("eject", 0.03f, 0.068f, -0.04f)
    b.anchor("magwell", 0f, -0.02f, -0.12f)
}

// ---------- M4A1 ----------
private fun buildM4a1(b: GunBuilder) {
    val m = b.M
    b.add(m.black, rbox(0.042f, 0.05f, 0.23f), 0f, 0.05f, -0.04f)
    b.add(m.metal, Prims.box(0.022f, 0.01f, 0.22f), 0f, 0.08f, -0.05f)
    for (i in 0 until 11) b.add(m.metal, Prims.box(0.026f, 0.004f, 0.008f), 0f, 0.087f, -0.15f + i * 0.02f)
    b.add(m.black, rbox(0.03f, 0.03f, 0.045f), 0f, 0.1f, 0.03f)
    b.add(m.metal, Prims.box(0.004f, 0.012f, 0.004f), 0f, 0.12f, 0.035f)
    b.add(m.black, rbox(0.036f, 0.05f, 0.17f), 0f, 0.005f, -0.02f)
    b.add(m.black, rbox(0.036f, 0.05f, 0.06f), 0f, -0.03f, -0.085f)
    b.add(m.black, cz(0.026f, 0.21f, 12), 0f, 0.046f, -0.28f)
    for (i in 0 until 6) b.add(m.metal, cz(0.0265f, 0.006f, 12), 0f, 0.046f, -0.2f - i * 0.032f)
    b.add(m.metal, cz(0.008f, 0.22f), 0f, 0.046f, -0.48f)
    b.add(m.black, Prims.box(0.018f, 0.055f, 0.018f), 0f, 0.068f, -0.41f)
    b.add(m.metal, Prims.box(0.004f, 0.02f, 0.004f), 0f, 0.105f, -0.41f)
    b.add(m.metal, cz(0.011f, 0.05f, 8), 0f, 0.046f, -0.6f)
    GunBuilder.curvedMag(b, m.metal, 4, 0f, -0.04f, -0.085f, 0.028f, 0.045f, 0.062f, 0.06f)
    b.add(m.black, rbox(0.028f, 0.09f, 0.036f), 0f, -0.04f, 0.035f, 0.38f, 0f, 0f)
    b.add(m.black, cz(0.016f, 0.17f), 0f, 0.046f, 0.145f)
    b.add(m.black, rbox(0.042f, 0.075f, 0.1f), 0f, 0.03f, 0.235f)
    b.add(m.black, Prims.box(0.03f, 0.01f, 0.02f), 0f, 0.078f, 0.07f)
    b.add(m.black, Prims.box(0.002f, 0.03f, 0.05f), 0.022f, 0.05f, -0.04f)
    b.anchor("grip", 0f, -0.035f, 0.035f)
    b.anchor("fore", 0f, 0.02f, -0.28f)
    b.anchor("muzzle", 0f, 0.046f, -0.63f)
    b.anchor("eject", 0.026f, 0.055f, -0.03f)
    b.anchor("magwell", 0f, -0.06f, -0.085f)
}

// ---------- AWM ----------
private fun buildAwm(b: GunBuilder) {
    val m = b.M
    b.add(m.olive, rbox(0.055f, 0.075f, 0.46f), 0f, 0.004f, -0.02f)
    b.add(m.olive, rbox(0.05f, 0.05f, 0.22f), 0f, -0.005f, -0.33f)
    b.add(m.metal, cz(0.022f, 0.24f), 0f, 0.05f, -0.08f)
    b.add(m.metal, cz(0.013f, 0.56f, 12), 0f, 0.05f, -0.5f)
    b.add(m.metal, rbox(0.036f, 0.032f, 0.07f), 0f, 0.05f, -0.8f)
    // 瞄准镜
    b.add(m.black, cz(0.018f, 0.3f), 0f, 0.118f, -0.07f)
    b.add(m.black, cz2(0.019f, 0.03f, 0.09f, 16), 0f, 0.118f, -0.25f)
    b.add(m.black, cz(0.025f, 0.06f, 16), 0f, 0.118f, 0.1f)
    b.add(m.glass, cz(0.027f, 0.002f, 16), 0f, 0.118f, -0.296f)
    b.add(m.black, cy(0.011f, 0.035f), 0f, 0.145f, -0.07f)
    b.add(m.black, cx(0.011f, 0.035f), 0.028f, 0.118f, -0.07f)
    for (z in floatArrayOf(-0.14f, 0f)) b.add(m.metal, Prims.box(0.03f, 0.04f, 0.022f), 0f, 0.088f, z)
    b.named("bolt", floatArrayOf(0.024f, 0.05f, 0.02f)) { bb ->
        bb.add(m.steel, cx(0.005f, 0.045f), 0.02f, 0f, 0f)
        bb.add(m.black, Prims.sphere(0.011f, 10, 8), 0.045f, -0.006f, 0f)
    }
    b.named("mag", floatArrayOf(0f, -0.035f, -0.07f)) { bb ->
        bb.add(m.metal, rbox(0.034f, 0.06f, 0.085f), 0f, -0.03f, 0f)
    }
    b.add(m.olive, rbox(0.035f, 0.1f, 0.045f), 0f, -0.07f, 0.08f, 0.3f, 0f, 0f)
    b.add(m.olive, rbox(0.05f, 0.16f, 0.045f), 0f, -0.02f, 0.3f)
    b.add(m.olive, rbox(0.04f, 0.04f, 0.18f), 0f, 0.05f, 0.22f)
    b.add(m.rubber, Prims.box(0.052f, 0.17f, 0.014f), 0f, -0.02f, 0.33f)
    for (x in floatArrayOf(-0.012f, 0.012f)) b.add(m.black, cz(0.004f, 0.2f), x, -0.035f, -0.4f)
    b.anchor("grip", 0f, -0.05f, 0.075f)
    b.anchor("fore", 0f, -0.02f, -0.3f)
    b.anchor("muzzle", 0f, 0.05f, -0.84f)
    b.anchor("eject", 0.03f, 0.06f, -0.08f)
    b.anchor("magwell", 0f, -0.05f, -0.07f)
    b.anchor("scope", 0f, 0.118f, 0.13f)
}

/** 锥形圆柱（Z 轴） */
private fun cz2(rFront: Float, rBack: Float, len: Float, seg: Int): Geo {
    val g = Prims.cylinder(rFront, rBack, len, seg)
    g.applyMatrix(GunBuilder.trs(0f, 0f, 0f, (PI / 2).toFloat(), 0f, 0f))
    return g
}

// ---------- MP5 ----------
private fun buildMp5(b: GunBuilder) {
    val m = b.M
    b.add(m.black, rbox(0.042f, 0.06f, 0.32f), 0f, 0.042f, -0.09f)
    b.add(m.black, rbox(0.046f, 0.05f, 0.15f), 0f, 0.02f, -0.31f)
    b.add(m.metal, cz(0.009f, 0.08f), 0f, 0.046f, -0.41f)
    b.add(m.metal, cz(0.012f, 0.02f), 0f, 0.046f, -0.45f)
    b.add(m.black, cy(0.014f, 0.024f), 0f, 0.085f, 0.02f)
    b.add(m.black, Prims.box(0.024f, 0.03f, 0.02f), 0f, 0.083f, -0.38f)
    b.add(m.black, cz(0.009f, 0.14f), -0.024f, 0.066f, -0.3f)
    GunBuilder.curvedMag(b, m.metal, 4, 0f, 0f, -0.13f, 0.026f, 0.042f, 0.052f, 0.14f)
    b.add(m.black, rbox(0.03f, 0.095f, 0.04f), 0f, -0.04f, 0.03f, 0.3f, 0f, 0f)
    for (x in floatArrayOf(-0.016f, 0.016f)) b.add(m.metal, cz(0.005f, 0.2f), x, 0.035f, 0.15f)
    b.add(m.black, rbox(0.045f, 0.07f, 0.018f), 0f, 0.03f, 0.25f)
    b.named("bolt", floatArrayOf(-0.02f, 0.07f, -0.2f)) { bb -> bb.add(m.black, Prims.box(0.012f, 0.012f, 0.03f), 0f, 0f, 0f) }
    b.anchor("grip", 0f, -0.035f, 0.03f)
    b.anchor("fore", 0f, 0f, -0.3f)
    b.anchor("muzzle", 0f, 0.046f, -0.46f)
    b.anchor("eject", 0.025f, 0.055f, -0.07f)
    b.anchor("magwell", 0f, -0.02f, -0.13f)
}

// ---------- 沙漠之鹰 ----------
private fun buildDeagle(b: GunBuilder) {
    val m = b.M
    b.named("slide", floatArrayOf(0f, 0f, 0f)) { bb ->
        bb.add(m.steel, rbox(0.032f, 0.042f, 0.255f), 0f, 0.062f, -0.075f)
        bb.add(m.metal, Prims.box(0.012f, 0.006f, 0.23f), 0f, 0.086f, -0.075f)
        bb.add(m.metal, Prims.box(0.004f, 0.01f, 0.006f), 0f, 0.094f, -0.19f)
        for (i in 0 until 6) bb.add(m.metal, Prims.box(0.034f, 0.03f, 0.003f), 0f, 0.062f, 0.02f + i * 0.008f)
    }
    b.add(m.metal, rbox(0.03f, 0.032f, 0.18f), 0f, 0.026f, -0.05f)
    b.add(m.rubber, rbox(0.034f, 0.115f, 0.055f), 0f, -0.04f, 0.025f, 0.24f, 0f, 0f)
    b.add(m.metal, Prims.box(0.006f, 0.006f, 0.07f), 0f, -0.003f, -0.035f)
    b.add(m.metal, Prims.box(0.006f, 0.03f, 0.006f), 0f, -0.018f, -0.07f)
    b.add(m.metal, Prims.box(0.008f, 0.02f, 0.012f), 0f, 0.075f, 0.06f)
    b.named("mag", floatArrayOf(0f, -0.03f, 0.02f), floatArrayOf(0.24f, 0f, 0f)) { bb ->
        bb.add(m.metal, Prims.box(0.022f, 0.1f, 0.04f), 0f, -0.04f, 0f)
    }
    b.anchor("grip", 0f, -0.04f, 0.03f)
    b.anchor("fore", -0.02f, -0.05f, 0.02f)
    b.anchor("muzzle", 0f, 0.062f, -0.21f)
    b.anchor("eject", 0.02f, 0.07f, -0.03f)
    b.anchor("magwell", 0f, -0.1f, 0.04f)
}

// ---------- 军刀 ----------
private fun buildKnife(b: GunBuilder) {
    val m = b.M
    // 刀刃：楔形（简化 Extrude）
    val blade = knifeBlade()
    b.add(m.blade, blade, 0f, 0f, -0.035f)
    b.add(m.metal, rbox(0.018f, 0.05f, 0.012f), 0f, 0f, -0.03f)
    b.add(m.rubber, rbox(0.024f, 0.03f, 0.11f), 0f, 0f, 0.03f)
    for (i in 0 until 4) b.add(m.black, Prims.box(0.026f, 0.032f, 0.004f), 0f, 0f, -0.005f + i * 0.022f)
    b.add(m.metal, rbox(0.026f, 0.032f, 0.018f), 0f, 0f, 0.09f)
    b.anchor("grip", 0f, 0f, 0.03f)
    b.anchor("muzzle", 0f, 0f, -0.23f)
}

private fun knifeBlade(): Geo {
    // 刀形：沿 -Z 延伸的扁平楔形
    val posA = ArrayList<Float>()
    val nrmA = ArrayList<Float>()
    val uvA = ArrayList<Float>()
    val idxA = ArrayList<Int>()
    // 侧面轮廓（YZ 平面），厚度 X
    val outline = arrayOf(
        floatArrayOf(0f, -0.014f), floatArrayOf(0.15f, -0.012f), floatArrayOf(0.2f, 0.012f),
        floatArrayOf(0.13f, 0.016f), floatArrayOf(0.03f, 0.016f), floatArrayOf(0f, 0.016f),
    )
    val t = 0.004f
    val n = outline.size
    // 两个侧面
    for (side in 0 until 2) {
        val x = if (side == 0) -t / 2 else t / 2
        val base = posA.size / 3
        for (p in outline) {
            posA.add(x); posA.add(p[1]); posA.add(-p[0])
            nrmA.add(if (side == 0) -1f else 1f); nrmA.add(0f); nrmA.add(0f)
            uvA.add(p[0] / 0.2f); uvA.add(0.5f)
        }
        for (i in 1 until n - 1) {
            if (side == 0) { idxA.add(base); idxA.add(base + i + 1); idxA.add(base + i) }
            else { idxA.add(base); idxA.add(base + i); idxA.add(base + i + 1) }
        }
    }
    // 顶/底/刀尖连接面（简化：只做上下两条带）
    for (i in 0 until n) {
        val j = (i + 1) % n
        val y0 = outline[i][1]; val z0 = -outline[i][0]
        val y1 = outline[j][1]; val z1 = -outline[j][0]
        val base = posA.size / 3
        posA.add(-t / 2); posA.add(y0); posA.add(z0)
        posA.add(t / 2); posA.add(y0); posA.add(z0)
        posA.add(t / 2); posA.add(y1); posA.add(z1)
        posA.add(-t / 2); posA.add(y1); posA.add(z1)
        val nx = 0f; val ny = -(z1 - z0); val nz = y1 - y0
        val l = kotlin.math.sqrt(ny * ny + nz * nz)
        for (k in 0 until 4) { nrmA.add(nx); nrmA.add(ny / l); nrmA.add(nz / l); uvA.add(0.5f); uvA.add(0.5f) }
        idxA.add(base); idxA.add(base + 1); idxA.add(base + 2)
        idxA.add(base); idxA.add(base + 2); idxA.add(base + 3)
    }
    return Geo(posA.toFloatArray(), nrmA.toFloatArray(), uvA.toFloatArray(), idxA.toIntArray())
}

// ---------- 手雷 ----------
private fun buildHe(b: GunBuilder) {
    val m = b.M
    val body = Prims.sphere(0.034f, 16, 12)
    body.applyMatrix(GunBuilder.trs(0f, 0f, 0f, 0f, 0f, 0f, 1f, 1.25f, 1f))
    b.add(m.olive, body, 0f, 0f, 0f)
    b.add(m.metal, cy(0.014f, 0.03f), 0f, 0.047f, 0f)
    b.named("lever", floatArrayOf(0f, 0.03f, 0.03f), floatArrayOf(-0.35f, 0f, 0f)) { bb ->
        bb.add(m.metal, Prims.box(0.012f, 0.07f, 0.006f), 0f, 0f, 0f)
    }
    b.named("pin", floatArrayOf(0.022f, 0.055f, 0f), floatArrayOf(0f, (PI / 2).toFloat(), 0f)) { bb ->
        bb.add(m.steel, Prims.torus(0.012f, 0.0022f, 6, 14), 0f, 0f, 0f)
    }
    b.add(m.tan, Prims.box(0.07f, 0.012f, 0.004f), 0f, -0.005f, 0.034f)
    b.anchor("grip", 0f, 0f, 0f)
    b.anchor("muzzle", 0f, 0f, -0.04f)
}

object GunModels {
    private val cache = HashMap<String, GunModel>()

    fun build(id: String, M: GunMaterials): GunModel {
        synchronized(cache) {
            cache[id]?.let { return it }
            val b = GunBuilder(M)
            when (id) {
                "ak47" -> buildAk47(b)
                "m4a1" -> buildM4a1(b)
                "awm" -> buildAwm(b)
                "mp5" -> buildMp5(b)
                "deagle" -> buildDeagle(b)
                "knife" -> buildKnife(b)
                "he" -> buildHe(b)
            }
            val m = b.build()
            cache[id] = m
            return m
        }
    }
}
