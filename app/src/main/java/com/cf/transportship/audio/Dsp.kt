// 音频 DSP 基元：双二阶滤波器、包络、噪声库、混响、软限幅
package com.cf.transportship.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh

class Biquad {
    private var b0 = 1f; private var b1 = 0f; private var b2 = 0f
    private var a1 = 0f; private var a2 = 0f
    private var x1 = 0f; private var x2 = 0f
    private var y1 = 0f; private var y2 = 0f

    fun set(type: String, f: Float, q: Float, sr: Float) {
        val w0 = 2f * PI.toFloat() * f.coerceIn(10f, sr * 0.45f) / sr
        val cw = cos(w0)
        // RBJ: alpha = sin(w0) / (2Q)（对所有类型一致；带通为 0dB 峰增益形式）
        val alpha = sin(w0) / (2f * q.coerceAtLeast(0.01f))
        val a0: Float
        when (type) {
            "lowpass" -> {
                a0 = 1 + alpha
                b0 = (1 - cw) / 2 / a0
                b1 = (1 - cw) / a0
                b2 = (1 - cw) / 2 / a0
                a1 = -2 * cw / a0
                a2 = (1 - alpha) / a0
            }
            "highpass" -> {
                a0 = 1 + alpha
                b0 = (1 + cw) / 2 / a0
                b1 = -(1 + cw) / a0
                b2 = (1 + cw) / 2 / a0
                a1 = -2 * cw / a0
                a2 = (1 - alpha) / a0
            }
            else -> { // bandpass（常数峰增益 0dB）
                a0 = 1 + alpha
                b0 = alpha / a0
                b1 = 0f
                b2 = -alpha / a0
                a1 = -2 * cw / a0
                a2 = (1 - alpha) / a0
            }
        }
    }

    fun process(x: Float): Float {
        val y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
        x2 = x1; x1 = x
        y2 = y1; y1 = y
        return y
    }
}

class FilterSpec(val type: String, val f0: Float, val f1: Float, val sw: Float, val q: Float) {
    val bq = Biquad()
    var inited = false
}

/** 包络：线性起音 -> (保持) -> 指数衰减到 -60dB */
class Env {
    var t = 0.0   // 双精度：长时间对局中 Float 累积 1/sr 会量化停滞
    var attack = 0.001f
    var dur = 0.1f
    var hold = 0f
    var peak = 1f
    var done = false
    private var tau = 0.01f

    fun setup(a: Float, d: Float, peak: Float, hold: Float) {
        attack = a.coerceAtLeast(0.0002f)
        dur = kotlin.math.max(d, 0.002f)
        this.peak = kotlin.math.max(peak, 1e-5f)
        this.hold = hold
        tau = dur / 6.907755f
        t = 0.0; done = false
    }

    fun step(dt: Double): Float {
        t += dt
        val total = attack + hold + dur + 0.004f
        if (t >= total) { done = true; return 0f }
        return when {
            t < attack -> peak * (t / attack).toFloat()
            t < attack + hold -> peak
            else -> {
                val td = (t - attack - hold).toFloat()
                if (td > dur) 0f else peak * exp(-td / tau)
            }
        }
    }
}

/** 噪声库：白/粉/褐（RMS 归一 0.3） */
class NoiseBank(val sr: Int) {
    val white = gen("white")
    val pink = gen("pink")
    val brown = gen("brown")

    private fun gen(kind: String): FloatArray {
        val n = sr * 5
        val d = FloatArray(n)
        var b0 = 0f; var b1 = 0f; var b2 = 0f; var b3 = 0f; var b4 = 0f; var b5 = 0f; var b6 = 0f
        var last = 0f
        var seed = 12345
        fun rnd(): Float {
            seed = seed * 1103515245 + 12345
            return ((seed shr 16) and 0x7fff) / 16383.5f - 1f
        }
        for (i in 0 until n) {
            val w = rnd()
            when (kind) {
                "pink" -> {
                    b0 = 0.99886f * b0 + w * 0.0555179f
                    b1 = 0.99332f * b1 + w * 0.0750759f
                    b2 = 0.969f * b2 + w * 0.153852f
                    b3 = 0.8665f * b3 + w * 0.3104856f
                    b4 = 0.55f * b4 + w * 0.5329522f
                    b5 = -0.7616f * b5 - w * 0.016898f
                    d[i] = b0 + b1 + b2 + b3 + b4 + b5 + b6 + w * 0.5362f
                    b6 = w * 0.115926f
                }
                "brown" -> {
                    last = (last + 0.02f * w) / 1.02f
                    d[i] = last
                }
                else -> d[i] = w
            }
        }
        // 去直流与漂移
        var mean = 0f
        for (v in d) mean += v
        mean /= n
        val drift = d[n - 1] - d[0]
        var e = 0f
        for (i in 0 until n) {
            d[i] -= mean + drift * (i.toFloat() / (n - 1) - 0.5f)
            e += d[i] * d[i]
        }
        val k = 0.3f / sqrt(e / n + 1e-9f)
        for (i in 0 until n) d[i] *= k
        return d
    }
}

/** 简易 Schroeder 混响（4 组合并联 + 2 全通串联） */
class Reverb(sr: Int) {
    private val combLens = intArrayOf((0.0297f * sr).toInt(), (0.0371f * sr).toInt(), (0.0411f * sr).toInt(), (0.0437f * sr).toInt())
    private val combBufs = combLens.map { FloatArray(it) }
    private val combIdx = IntArray(4)
    private val combFb = 0.77f
    private val apLens = intArrayOf((0.005f * sr).toInt(), (0.0017f * sr).toInt())
    private val apBufs = apLens.map { FloatArray(it) }
    private val apIdx = IntArray(2)
    private val apG = 0.7f

    fun process(x: Float): Float {
        var acc = 0f
        for (i in 0 until 4) {
            val buf = combBufs[i]
            val idx = combIdx[i]
            val y = buf[idx]
            acc += y
            buf[idx] = x + y * combFb
            combIdx[i] = (idx + 1) % buf.size
        }
        acc *= 0.25f
        // 全通
        for (i in 0 until 2) {
            val buf = apBufs[i]
            val idx = apIdx[i]
            val bufOut = buf[idx]
            val y = -acc + bufOut
            buf[idx] = acc + bufOut * apG
            acc = y
            apIdx[i] = (idx + 1) % buf.size
        }
        return acc
    }
}

/** 末级软限幅 */
fun softClip(x: Float): Float {
    val ax = kotlin.math.abs(x)
    val s = if (ax <= 0.7f) ax else 0.7f + 0.28f * tanh((ax - 0.7f) / 0.28f)
    return if (x < 0) -s else s
}

fun tanhDrive(x: Float): Float = tanh(x * 3f)

/** 枪声参数（频率 Hz / 时长 s / 增益）——移植 audio.js GUNS */
class GunSound(
    val gain: Float, val drive: Float, val wet: Float,
    val crackHp: Float, val crackDur: Float, val crackG: Float,
    val bodyF0: Float, val bodyF1: Float, val bodyDur: Float, val bodyG: Float,
    val midF: Float, val midQ: Float, val midDur: Float, val midG: Float,
    val punchF0: Float, val punchF1: Float, val punchSw: Float, val punchDur: Float, val punchG: Float,
    val sub: FloatArray?, // f0, f1, dur, g
    val tailF: Float, val tailDur: Float, val tailG: Float,
    val mechF: Float, val mechG: Float, val mechDelay: Float,
    val echo: Array<FloatArray>?, // [dt, g]
)

val GUNS = mapOf(
    "ak47" to GunSound(1.0f, 2.6f, 0.16f, 2200f, 0.0025f, 0.55f, 4200f, 650f, 0.16f, 0.9f,
        1000f, 1.1f, 0.09f, 0.5f, 150f, 42f, 0.07f, 0.17f, 0.8f, null, 900f, 0.55f, 0.28f,
        2800f, 0.09f, 0.045f, arrayOf(floatArrayOf(0.16f, 0.12f))),
    "m4a1" to GunSound(1.2f, 2.2f, 0.15f, 3200f, 0.002f, 0.6f, 6500f, 1100f, 0.12f, 0.85f,
        1900f, 1.3f, 0.07f, 0.5f, 175f, 52f, 0.05f, 0.12f, 0.65f, null, 1300f, 0.42f, 0.24f,
        3400f, 0.09f, 0.038f, arrayOf(floatArrayOf(0.15f, 0.09f))),
    "awm" to GunSound(0.85f, 3.0f, 0.28f, 1800f, 0.003f, 0.7f, 3800f, 420f, 0.3f, 1.0f,
        780f, 0.9f, 0.16f, 0.6f, 135f, 32f, 0.13f, 0.4f, 1.0f, floatArrayOf(70f, 28f, 0.5f, 0.5f),
        700f, 1.4f, 0.45f, 2200f, 0.06f, 0.06f,
        arrayOf(floatArrayOf(0.19f, 0.3f), floatArrayOf(0.44f, 0.18f), floatArrayOf(0.82f, 0.09f))),
    "mp5" to GunSound(1.5f, 1.9f, 0.12f, 3800f, 0.0015f, 0.45f, 3600f, 850f, 0.075f, 0.8f,
        850f, 1.0f, 0.05f, 0.55f, 185f, 68f, 0.035f, 0.08f, 0.5f, null, 1100f, 0.25f, 0.16f,
        3800f, 0.08f, 0.03f, null),
    "deagle" to GunSound(1.05f, 2.9f, 0.2f, 2600f, 0.003f, 0.7f, 5500f, 600f, 0.2f, 1.0f,
        1200f, 1.0f, 0.11f, 0.65f, 165f, 40f, 0.06f, 0.21f, 0.95f, null, 1000f, 0.7f, 0.35f,
        2500f, 0.11f, 0.028f, arrayOf(floatArrayOf(0.17f, 0.16f), floatArrayOf(0.4f, 0.07f))),
)

val WEIGHT = mapOf(
    "ak47" to 0.85f, "m4a1" to 1f, "awm" to 0.8f, "mp5" to 1.12f,
    "deagle" to 1.05f, "knife" to 1.2f, "grenade" to 1.1f,
)
