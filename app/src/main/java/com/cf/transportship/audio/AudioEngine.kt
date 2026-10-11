// 程序化合成音效引擎（移植 audio.js 到 AudioTrack 流式合成）
package com.cf.transportship.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

private fun jit(p: Float = 0.05f) = 1f + (Random.nextFloat() * 2f - 1f) * p
private fun rand(a: Float, b: Float) = a + Random.nextFloat() * (b - a)

/** 单个发声层 */
class Layer {
    var noise: FloatArray? = null      // 噪声库引用
    var noiseOff = 0f                  // 起始偏移（秒）
    var oscWave = ""                   // sine/triangle/saw/square
    var isOsc = false
    var f0 = 0f; var f1 = 0f; var sw = 0f
    var fmRate = 0f; var fmDepth = 0f
    var det = 0f                       // 半音失谐
    val env = Env()
    var filters: List<FilterSpec> = listOf()
    var drivePre = 0f; var drivePost = 1f; var hasDrive = false
    var delay = 0f                     // 相对 voice 开始的延迟（秒）
    var wet = 0f
    var age = 0f
    var phase = 0f
    var fmPhase = 0f
    var lfoGainRate = 0f; var lfoGainDepth = 0f   // 环境层用
    var lfoFRate = 0f; var lfoFDepth = 0f
    var baseGain = 1f                  // 环境层基础增益
    var loop = false                   // 环境循环
}

/** 一次发声（组） */
class Voice {
    val layers = ArrayList<Layer>()
    var gain = 1f
    var spatial = false
    var sGain = 1f; var sPan = 0f; var sLP = 20000f
    var wetScale = 0f
    var bus = 0  // 0=world(受闷音影响) 1=ui 2=ambient 3=voice
    var dead = false
    var dist = 0f
}

class AudioEngine(private val audioMgr: AudioManager?) {
    val sr: Int = try {
        AudioTrack.getNativeOutputSampleRate(AudioManager.STREAM_MUSIC)
    } catch (e: Exception) { 48000 }

    private val bank = NoiseBank(sr)
    private val reverb = Reverb(sr)
    private var track: AudioTrack? = null
    private var thread: Thread? = null
    @Volatile private var running = false

    private val voices = ArrayList<Voice>()
    private val ambient = ArrayList<Voice>()

    // 音量
    @Volatile var volMaster = 0.8f; @Volatile var volSfx = 0.9f
    @Volatile var volAmbient = 0.5f; @Volatile var volVoice = 0.9f

    // 听者
    private var lx = 0f; private var ly = 0f; private var lz = 0f
    private var fwdX = 0f; private var fwdZ = -1f
    private var rightX = 1f; private var rightZ = 0f

    // 闷音（世界总线低通）
    private var muffleY = 0f
    private var muffleC = 1f  // 系数（1=直通）
    private var muffleTarget = 1f
    private var muffleTau = 0.05f
    private var time = 0.0

    // 心跳
    private var hbOn = false
    private var hbNext = 0.0

    // 环境事件
    private var ambOn = false
    private var nGust = 0.0; private var nGull = 0.0; private var nCreak = 0.0
    private var nSplash = 0.0; private var nHorn = 0.0
    private var windVoice: Voice? = null
    private var windBase = 0.06f; private var windF = 520f
    private val maxVoices = 48

    private val chunk = 512
    private val mixL = FloatArray(chunk)
    private val mixR = FloatArray(chunk)

    fun start() {
        if (running) return
        val minBuf = AudioTrack.getMinBufferSize(sr, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_FLOAT)
        val bufSize = max(minBuf, chunk * 8)
        track = AudioTrack(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build(),
            AudioFormat.Builder().setSampleRate(sr).setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build(),
            bufSize, AudioTrack.MODE_STREAM, AudioManager.AUDIO_SESSION_ID_GENERATE,
        )
        running = true
        track?.play()
        thread = Thread({
            val out = FloatArray(chunk * 2)
            while (running) {
                renderChunk()
                for (i in 0 until chunk) {
                    out[i * 2] = mixL[i]
                    out[i * 2 + 1] = mixR[i]
                }
                track?.write(out, 0, out.size, AudioTrack.WRITE_BLOCKING)
            }
        }, "cf-audio")
        thread?.priority = Thread.MAX_PRIORITY - 1
        thread?.start()
    }

    fun stop() {
        running = false
        try { thread?.join(500) } catch (e: Exception) {}
        try { track?.stop(); track?.release() } catch (e: Exception) {}
        track = null
    }

    fun setListener(pos: FloatArray, fwd: FloatArray?) {
        lx = pos[0]; ly = pos[1]; lz = pos[2]
        if (fwd != null) {
            val l = sqrt(fwd[0] * fwd[0] + fwd[2] * fwd[2])
            if (l > 1e-6f) {
                fwdX = fwd[0] / l; fwdZ = fwd[2] / l
                rightX = -fwdZ; rightZ = fwdX
            }
        }
    }

    private fun dist(p: FloatArray): Float {
        val dx = p[0] - lx; val dy = p[1] - ly; val dz = p[2] - lz
        return sqrt(dx * dx + dy * dy + dz * dz)
    }

    private fun newVoice(pos: FloatArray?, g: Float, ref: Float, roll: Float, maxD: Float, wet: Float, bus: Int, distIn: Float = -1f): Voice {
        val v = Voice()
        v.gain = g
        v.bus = bus
        if (pos != null) {
            val d = if (distIn >= 0) distIn else dist(pos)
            v.dist = d
            val dEff = max(d, ref)
            v.sGain = (ref / (ref + roll * (dEff - ref))).coerceIn(0f, 1f)
            // 相对听者的方位
            val rx = pos[0] - lx; val rz = pos[2] - lz
            val dot = rx * rightX + rz * rightZ
            val rl = sqrt(rx * rx + rz * rz)
            v.sPan = (dot / max(rl, 0.5f)).coerceIn(-1f, 1f)
            v.sLP = (20000f / (1f + d / 14f)).coerceIn(1600f, 20000f)
            v.spatial = true
        }
        v.wetScale = wet
        synchronized(voices) {
            // 超出上限：丢最远的
            while (voices.size >= maxVoices) {
                var fi = -1; var far = -1f
                for (i in voices.indices) if (voices[i].dist > far) { far = voices[i].dist; fi = i }
                voices.removeAt(if (far > v.dist) fi else 0)
            }
            voices.add(v)
        }
        return v
    }

    // ---------- 层构造 ----------
    private fun addLayer(v: Voice, l: Layer) {
        synchronized(v.layers) { v.layers.add(l) }
    }
    private fun nz(v: Voice, delay: Float, n: String, a: Float, dur: Float, g: Float,
                   flt: List<FilterSpec> = listOf(), drive: Pair<Float, Float>? = null, hold: Float = 0f) {
        val l = Layer()
        l.noise = when (n) { "pink" -> bank.pink; "brown" -> bank.brown; else -> bank.white }
        l.noiseOff = Random.nextFloat() * 4f
        l.delay = delay
        l.env.setup(a, dur, g, hold)
        l.filters = flt
        if (drive != null) { l.hasDrive = true; l.drivePre = drive.first; l.drivePost = drive.second }
        addLayer(v, l)
    }

    private fun tn(v: Voice, delay: Float, w: String, f: Float, f1: Float = 0f, sw: Float = 0f, a: Float = 0.002f,
                   dur: Float = 0.1f, g: Float = 1f, hold: Float = 0f, fm: FloatArray? = null, det: Float = 0f,
                   flt: List<FilterSpec> = listOf(), drive: Pair<Float, Float>? = null) {
        val l = Layer()
        l.isOsc = true
        l.oscWave = w
        l.f0 = f; l.f1 = f1; l.sw = sw
        if (fm != null) { l.fmRate = fm[0]; l.fmDepth = fm[1] }
        l.det = det
        l.delay = delay
        l.env.setup(a, dur, g, hold)
        l.filters = flt
        if (drive != null) { l.hasDrive = true; l.drivePre = drive.first; l.drivePost = drive.second }
        addLayer(v, l)
    }

    private fun flt(type: String, f: Float, q: Float = 0f, f1: Float = 0f, sw: Float = 0.1f) =
        FilterSpec(type, f, f1, sw, q)

    /** 金属非谐波泛音组 */
    private fun metal(v: Voice, t: Float, f: Float, g: Float, parts: Array<FloatArray>) {
        for (p in parts) tn(v, t, "sine", f * p[0], a = 0.0006f, dur = p[2], g = g * p[1])
    }

    private fun click(v: Voice, t: Float, f: Float, g: Float, q: Float = 2.5f, dur: Float = 0.01f) {
        nz(v, t, "white", 0.0003f, dur, g, listOf(flt("bandpass", f, q)))
    }

    private fun whoosh(v: Voice, t: Float, n: String, a: Float, dur: Float, g: Float, f0: Float, fp: Float, f1: Float, q: Float) {
        val l = Layer()
        l.noise = if (n == "white") bank.white else bank.pink
        l.noiseOff = Random.nextFloat() * 4f
        l.delay = t
        l.env.setup(a, dur, g, 0f)
        // 双段路径：a 时刻到 fp，再降到 f1 —— 用两个滤波器近似
        l.filters = listOf(FilterSpec("bandpass", f0, fp, a, q), FilterSpec("bandpass", fp, f1, dur, q))
        addLayer(v, l)
    }

    private fun mech(v: Voice, t: Float, f: Float, g: Float) {
        nz(v, t, "white", 0.0003f, 0.01f, g * 1.4f, listOf(flt("bandpass", f * 1.25f, 2.5f)))
        metal(v, t, f, g, arrayOf(floatArrayOf(1f, 1f, 0.045f), floatArrayOf(1.53f, 0.6f, 0.032f), floatArrayOf(2.41f, 0.35f, 0.022f)))
        nz(v, t + 0.03f * jit(), "white", 0.0003f, 0.008f, g * 0.9f, listOf(flt("bandpass", f * 0.85f, 3f)))
    }

    // =========================================================================
    // 公开 API
    // =========================================================================
    fun playShot(weaponId: String, pos: FloatArray?) {
        if (weaponId == "knife") { playKnife("light", "miss", pos); return }
        if (weaponId == "grenade") { throwWhoosh(pos); return }
        val P = GUNS[weaponId] ?: GUNS["m4a1"]!!
        val local = pos == null
        val d = if (local) 0f else dist(pos)
        val far = (d / 80f).coerceIn(0f, 1f)
        val r = jit(0.05f); val k = jit(0.05f)
        val v = newVoice(pos, P.gain * jit(0.06f) * (if (local) 1.8f else 1.25f), 5f, 0.6f, 400f,
            if (local) P.wet else P.wet * 1.2f, 0)
        val drv = Pair(P.drive / 3f, 0.7f)
        val C = P; val U = P
        // (a) 极短高频瞬态爆裂
        nz(v, 0f, "white", 0.0002f, C.crackDur * k, C.crackG * (if (local) 1f else 1 - 0.6f * far), listOf(flt("highpass", C.crackHp * r)))
        // (b) 主体：低通噪声 + 中频带通
        nz(v, 0f, "pink", 0.0006f, C.bodyDur * k, C.bodyG, listOf(flt("lowpass", C.bodyF0 * r, 1f, C.bodyF1 * r, C.bodyDur * k * 0.6f)), drv)
        nz(v, 0f, "white", 0.0004f, C.midDur * k, C.midG, listOf(flt("bandpass", C.midF * r, C.midQ)), drv)
        // (c) 低频冲击
        tn(v, 0f, "sine", U.punchF0 * r, U.punchF1 * r, U.punchSw * k, 0.0015f, U.punchDur * k, U.punchG, drive = drv)
        P.sub?.let { tn(v, 0f, "triangle", it[0] * r, it[1] * r, it[2] * 0.5f, 0.003f, it[2] * k, it[3], drive = drv) }
        // 尾音隆隆
        nz(v, 0.006f, "brown", 0.015f, C.tailDur * k * (1 + 0.6f * far), C.tailG * (1 + 0.5f * far),
            listOf(flt("lowpass", C.tailF * r, 0f, C.tailF * 0.45f * r, C.tailDur)))
        // 集装箱/海面回声
        val echoes = P.echo?.map { it.copyOf() }?.toMutableList() ?: mutableListOf()
        if (!local && d > 30) echoes.add(floatArrayOf(0.08f + d / 600f, 0.1f + 0.15f * far))
        for (e in echoes) {
            nz(v, e[0] * jit(0.08f), "pink", 0.008f, 0.22f * k, e[1],
                listOf(flt("lowpass", 1500f * r), flt("highpass", 160f)))
        }
        // 本地枪机
        if (local) mech(v, C.mechDelay * jit(0.12f), C.mechF * r, C.mechG)
    }

    fun playReload(weaponId: String, stage: String) {
        val fm = (WEIGHT[weaponId] ?: 1f) * jit(0.04f)
        val v = newVoice(null, 0.42f, 2f, 1f, 100f, 0.06f, 1)
        when (stage) {
            "magout" -> {
                click(v, 0f, 3000 * fm, 0.45f)
                metal(v, 0f, 2500 * fm, 0.08f, arrayOf(floatArrayOf(1f, 1f, 0.04f), floatArrayOf(1.6f, 0.5f, 0.03f)))
                nz(v, 0.02f, "white", 0.015f, 0.08f, 0.16f, listOf(flt("bandpass", 1400 * fm, 1.6f, 2600 * fm, 0.09f)))
                tn(v, 0.11f, "sine", 190 * fm, 110f, 0f, 0.002f, 0.06f, 0.2f)
                click(v, 0.11f, 1600 * fm, 0.25f, 1.5f, 0.02f)
            }
            "magin" -> {
                nz(v, 0f, "white", 0.02f, 0.07f, 0.14f, listOf(flt("bandpass", 1200 * fm, 1.5f, 2000 * fm, 0.08f)))
                val tc = 0.09f
                click(v, tc, 1900 * fm, 0.6f, 1.2f, 0.03f)
                tn(v, tc, "sine", 230 * fm, 120f, 0f, 0.0015f, 0.05f, 0.35f)
                metal(v, tc, 2100 * fm, 0.12f, arrayOf(floatArrayOf(1f, 1f, 0.07f), floatArrayOf(1.47f, 0.6f, 0.05f), floatArrayOf(2.3f, 0.35f, 0.03f)))
                click(v, tc + 0.012f, 3600 * fm, 0.3f)
            }
            "bolt" -> {
                click(v, 0f, 2600 * fm, 0.35f)
                nz(v, 0.01f, "white", 0.01f, 0.07f, 0.18f, listOf(flt("bandpass", 1500 * fm, 2f, 3000 * fm, 0.07f)))
                click(v, 0.085f, 2200 * fm, 0.3f)
                val tr = 0.2f * jit(0.08f)
                nz(v, tr, "white", 0.004f, 0.035f, 0.15f, listOf(flt("bandpass", 3000 * fm, 2f, 1600 * fm, 0.03f)))
                click(v, tr + 0.03f, 2000 * fm, 0.7f, 1.2f, 0.03f)
                tn(v, tr + 0.03f, "sine", 170 * fm, 95f, 0f, 0.0015f, 0.06f, 0.35f)
                metal(v, tr + 0.03f, 2600 * fm, 0.12f, arrayOf(floatArrayOf(1f, 1f, 0.08f), floatArrayOf(1.53f, 0.6f, 0.06f), floatArrayOf(2.41f, 0.35f, 0.04f)))
            }
            "boltback" -> {
                click(v, 0f, 2400 * fm, 0.4f)
                tn(v, 0f, "sine", 300 * fm, 200f, 0f, 0.001f, 0.03f, 0.15f)
                nz(v, 0.045f, "white", 0.02f, 0.12f, 0.2f, listOf(flt("bandpass", 1000 * fm, 1.8f, 2400 * fm, 0.14f)))
                click(v, 0.18f, 1800 * fm, 0.45f, 1.5f, 0.02f)
                metal(v, 0.18f, 1900 * fm, 0.08f, arrayOf(floatArrayOf(1f, 1f, 0.08f), floatArrayOf(2.2f, 0.5f, 0.05f)))
            }
            "boltforward" -> {
                nz(v, 0f, "white", 0.015f, 0.1f, 0.2f, listOf(flt("bandpass", 2400 * fm, 1.8f, 1100 * fm, 0.11f)))
                click(v, 0.11f, 1700 * fm, 0.6f, 1.3f, 0.03f)
                tn(v, 0.11f, "sine", 200 * fm, 110f, 0f, 0.0015f, 0.05f, 0.3f)
                metal(v, 0.11f, 2300 * fm, 0.1f, arrayOf(floatArrayOf(1f, 1f, 0.07f), floatArrayOf(1.6f, 0.5f, 0.05f)))
                click(v, 0.19f, 2900 * fm, 0.4f)
            }
            else -> click(v, 0f, 2500 * fm, 0.4f)
        }
    }

    fun playDryFire() {
        val v = newVoice(null, 0.45f, 2f, 1f, 100f, 0.04f, 1)
        nz(v, 0f, "white", 0.0002f, 0.006f, 0.6f, listOf(flt("highpass", 4000f)))
        metal(v, 0f, 3100 * jit(0.03f), 0.18f, arrayOf(floatArrayOf(1f, 1f, 0.03f), floatArrayOf(1.73f, 0.6f, 0.02f)))
        tn(v, 0f, "sine", 900f, 600f, 0f, 0.0005f, 0.015f, 0.2f)
    }

    private fun surf(v: Voice, t: Float, s: String, g: Float, r: Float, heavy: Float, br: Float) {
        val hd = 1 + heavy * 1.5f
        when (s) {
            "container" -> {
                tn(v, t, "sine", 74 * r, 62 * r, 0f, 0.004f, 0.26f * hd, 0.55f * g)
                tn(v, t, "sine", 121 * r, 110 * r, 0f, 0.003f, 0.18f * hd, 0.32f * g)
                tn(v, t, "triangle", 173 * r, 0f, 0f, 0.002f, 0.12f * hd, 0.14f * g)
                nz(v, t, "pink", 0.002f, 0.12f * hd, 0.55f * g, listOf(flt("bandpass", 380 * r, 1.6f)))
                nz(v, t, "white", 0.0008f, 0.03f, 0.22f * g * br, listOf(flt("bandpass", 1900 * r, 1.2f)))
                metal(v, t, 690 * r, 0.05f * g * br, arrayOf(floatArrayOf(1f, 1f, 0.18f * hd), floatArrayOf(2.2f, 0.6f, 0.12f)))
            }
            "wood" -> {
                tn(v, t, "sine", 160 * r, 95 * r, 0f, 0.002f, 0.05f * hd, 0.4f * g)
                nz(v, t, "pink", 0.0015f, 0.055f * hd, 0.55f * g, listOf(flt("lowpass", 1100 * r * (0.6f + 0.4f * br))))
                nz(v, t, "white", 0.0006f, 0.014f, 0.12f * g * br, listOf(flt("bandpass", 2600 * r, 2f)))
                if (Random.nextFloat() < 0.15f) {
                    tn(v, t + 0.02f, "saw", rand(28f, 40f), 0f, 0f, 0.02f, 0.12f, 0.1f * g, flt = listOf(flt("bandpass", rand(600f, 900f), 8f)))
                }
            }
            "grate" -> {
                tn(v, t, "sine", 135 * r, 85 * r, 0f, 0.002f, 0.045f * hd, 0.3f * g)
                nz(v, t, "white", 0.0008f, 0.03f * hd, 0.35f * g * br, listOf(flt("bandpass", 2600 * r, 1f)))
                var tt = t
                val n = 3 + Random.nextInt(2) + (heavy * 3).toInt()
                for (i in 0 until n) {
                    tt += rand(0.007f, 0.02f)
                    click(v, tt, rand(3000f, 5600f), 0.2f * g * br * Math.pow(0.72f.toDouble(), i.toFloat().toDouble()).toFloat(), 5f, 0.012f)
                }
                metal(v, t, 910 * r, 0.05f * g * br, arrayOf(floatArrayOf(1f, 1f, 0.08f * hd), floatArrayOf(2.6f, 0.5f, 0.05f)))
            }
            else -> { // metal
                tn(v, t, "sine", 115 * r, 70 * r, 0f, 0.002f, 0.055f * hd, 0.5f * g)
                nz(v, t, "pink", 0.001f, 0.045f * hd, 0.45f * g, listOf(flt("bandpass", 1500 * r * (0.7f + 0.3f * br), 1.2f)))
                nz(v, t, "white", 0.0005f, 0.012f, 0.15f * g * br, listOf(flt("highpass", 3500f)))
                metal(v, t, 530 * r, 0.1f * g * br, arrayOf(floatArrayOf(1f, 1f, 0.13f * hd), floatArrayOf(2.31f, 0.7f, 0.09f * hd), floatArrayOf(3.93f, 0.45f, 0.06f)))
            }
        }
    }

    fun playFootstep(pos: FloatArray?, surface: String, run: Boolean, crouch: Boolean) {
        val local = pos == null
        val lvl = (if (crouch) 0.32f else if (run) 1f else 0.6f) * jit(0.12f)
        val v = newVoice(pos, (if (local) 0.45f else 1.8f) * lvl, 2.5f, 1.25f, 60f, if (local) 0.04f else 0.1f, 0)
        val t = 0f; val r = jit(0.06f); val br = if (crouch) 0.45f else 1f
        surf(v, t, surface, 1f, r, 0f, br)
        val gap = if (crouch) 0.09f else if (run) 0.045f else 0.07f
        surf(v, t + gap * jit(0.15f), surface, 0.45f, r * 1.06f, 0f, br * 0.8f)
        if (run) nz(v, t, "white", 0.02f, 0.08f, 0.06f, listOf(flt("bandpass", 2200 * r, 0.8f)))
    }

    fun playLand(pos: FloatArray?, surface: String, strength: Float) {
        val s = strength.coerceIn(0f, 1f)
        val local = pos == null
        val v = newVoice(pos, (if (local) 0.5f else 1.6f) * (0.45f + 0.65f * s), 3f, 1.2f, 70f, 0.08f, 0)
        val r = jit(0.06f)
        tn(v, 0f, "sine", 95 * r, 45f, 0.1f, 0.002f, 0.1f + 0.12f * s, 0.55f + 0.3f * s)
        surf(v, 0f, surface, 0.9f, r, s, 1f)
        surf(v, 0.03f * jit(0.2f), surface, 0.5f, r * 1.05f, s * 0.5f, 0.8f)
        nz(v, 0.01f, "white", 0.01f, 0.1f, 0.08f + 0.08f * s, listOf(flt("bandpass", 2200f, 0.8f)))
        if (s > 0.4f) metal(v, rand(0.03f, 0.09f), rand(2200f, 3400f), 0.04f * s, arrayOf(floatArrayOf(1f, 1f, 0.06f), floatArrayOf(1.9f, 0.5f, 0.04f)))
    }

    fun playJump(pos: FloatArray?) {
        val local = pos == null
        val v = newVoice(pos, if (local) 0.4f else 0.8f, 2.5f, 1.25f, 50f, 0.05f, 0)
        val r = jit(0.06f)
        nz(v, 0f, "pink", 0.004f, 0.06f, 0.35f, listOf(flt("bandpass", 900 * r, 1f)))
        tn(v, 0f, "sine", 120 * r, 80f, 0f, 0.002f, 0.05f, 0.25f)
        nz(v, 0.01f, "white", 0.03f, 0.12f, 0.1f, listOf(flt("bandpass", 2400 * r, 0.8f)))
        metal(v, rand(0.03f, 0.08f), rand(2600f, 3600f), 0.035f, arrayOf(floatArrayOf(1f, 1f, 0.05f), floatArrayOf(1.7f, 0.5f, 0.03f)))
    }

    fun playImpact(pos: FloatArray?, material: String) {
        val v = newVoice(pos, 1.8f, 3f, 1.2f, 90f, 0.1f, 0)
        val r = jit(0.06f)
        when (material) {
            "metal" -> {
                nz(v, 0f, "white", 0.0002f, 0.003f, 0.6f, listOf(flt("highpass", 3000 * r)))
                nz(v, 0f, "pink", 0.0005f, 0.025f, 0.35f, listOf(flt("bandpass", 900 * r, 1f)))
                metal(v, 0f, rand(1700f, 3000f), 0.16f, arrayOf(floatArrayOf(1f, 1f, 0.14f), floatArrayOf(1.47f, 0.8f, 0.1f), floatArrayOf(2.09f, 0.6f, 0.08f), floatArrayOf(2.93f, 0.4f, 0.05f), floatArrayOf(4.1f, 0.25f, 0.035f)))
                nz(v, 0.004f, "white", 0.002f, 0.07f, 0.1f, listOf(flt("highpass", 6000f)))
            }
            "wood" -> {
                nz(v, 0f, "white", 0.0003f, 0.004f, 0.35f, listOf(flt("highpass", 2000 * r)))
                nz(v, 0f, "pink", 0.0008f, 0.06f, 0.7f, listOf(flt("bandpass", 700 * r, 1.2f)))
                tn(v, 0f, "sine", 190 * r, 110f, 0f, 0.001f, 0.05f, 0.35f)
                for (i in 0 until 3) click(v, rand(0.005f, 0.04f), rand(2500f, 4500f), 0.12f, 3f, 0.008f)
            }
            "flesh" -> {
                nz(v, 0f, "pink", 0.001f, 0.07f, 0.8f, listOf(flt("lowpass", 700 * r, 0f, 250f, 0.06f)))
                tn(v, 0f, "sine", 120 * r, 55f, 0f, 0.001f, 0.08f, 0.5f)
                nz(v, 0.003f, "white", 0.004f, 0.04f, 0.25f, listOf(flt("bandpass", 1400 * r, 2f)))
            }
            "mesh" -> {
                nz(v, 0f, "white", 0.0002f, 0.01f, 0.35f, listOf(flt("highpass", 2000f)))
                var tt = 0f; var g = 0.12f
                val n = 6 + Random.nextInt(4)
                for (i in 0 until n) {
                    metal(v, tt, rand(2500f, 5200f), g, arrayOf(floatArrayOf(1f, 1f, 0.05f), floatArrayOf(1.8f, 0.5f, 0.04f)))
                    tt += rand(0.008f, 0.03f); g *= 0.8f
                }
                nz(v, 0f, "white", 0.005f, 0.15f, 0.08f, listOf(flt("bandpass", 900f, 1f)))
            }
            else -> {
                nz(v, 0f, "white", 0.0002f, 0.004f, 0.6f, listOf(flt("highpass", 1800 * r)))
                nz(v, 0f, "pink", 0.0006f, 0.05f, 0.5f, listOf(flt("bandpass", 1300 * r, 0.9f)))
                tn(v, 0f, "sine", 160 * r, 90f, 0f, 0.001f, 0.04f, 0.25f)
                val n = 4 + Random.nextInt(3)
                for (i in 0 until n) click(v, rand(0.02f, 0.2f), rand(2000f, 5000f), 0.08f, 3f, 0.006f)
            }
        }
    }

    fun playRicochet(pos: FloatArray?) {
        val v = newVoice(pos, 1.2f, 3f, 1f, 100f, 0.15f, 0)
        val f = rand(2600f, 4200f); val d = rand(0.25f, 0.5f)
        nz(v, 0f, "white", 0.0002f, 0.004f, 0.45f, listOf(flt("highpass", 3500f)))
        tn(v, 0.004f, "sine", f, f * rand(0.35f, 0.55f), d, 0.004f, d * 0.9f, 0.3f, hold = d * 0.1f, fm = floatArrayOf(rand(45f, 90f), f * 0.025f))
        tn(v, 0.004f, "triangle", f * 1.5f, f * 0.7f, d * 0.8f, 0.003f, d * 0.6f, 0.06f)
        nz(v, 0.004f, "white", 0.01f, d * 0.8f, 0.06f, listOf(flt("bandpass", f, 6f, f * 0.45f, d)))
    }

    fun playBulletWhiz(pos: FloatArray?) {
        val v = newVoice(pos, 1.8f, 1.5f, 1f, 40f, 0.04f, 0)
        val d = rand(0.12f, 0.2f)
        nz(v, 0f, "white", 0.0002f, 0.002f, 0.35f, listOf(flt("highpass", 4000f)))
        nz(v, 0f, "white", d * 0.35f, d * 0.65f, 0.45f, listOf(flt("bandpass", 5200f, 2.5f, 900f, d)))
        tn(v, 0f, "sine", 2000f, 600f, d, d * 0.3f, d * 0.7f, 0.07f)
    }

    fun playHitmarker(headshot: Boolean) {
        val v = newVoice(null, 1.5f, 2f, 1f, 100f, 0f, 1)
        if (headshot) {
            val f = 2450 * jit(0.015f)
            nz(v, 0f, "white", 0.0002f, 0.004f, 0.35f, listOf(flt("highpass", 5000f)))
            metal(v, 0f, f, 0.3f, arrayOf(floatArrayOf(1f, 1f, 0.5f), floatArrayOf(1.004f, 0.5f, 0.45f), floatArrayOf(2.76f, 0.45f, 0.28f), floatArrayOf(5.4f, 0.22f, 0.14f)))
            click(v, 0f, 2200f, 0.25f, 1.5f, 0.012f)
        } else {
            click(v, 0f, 2400 * jit(0.03f), 1.0f, 1.5f, 0.016f)
            tn(v, 0f, "sine", 1500f, 1100f, 0.03f, 0.0005f, 0.03f, 0.4f)
            nz(v, 0f, "white", 0.0002f, 0.003f, 0.35f, listOf(flt("highpass", 6000f)))
        }
    }

    fun playKillConfirm(headshot: Boolean) {
        val v = newVoice(null, 0.7f, 2f, 1f, 100f, 0.06f, 1)
        tn(v, 0f, "sine", 150f, 60f, 0.08f, 0.002f, 0.12f, 0.45f)
        click(v, 0f, 1800f, 0.35f, 1.2f, 0.015f)
        tn(v, 0f, "triangle", 1318.5f, 0f, 0f, 0.003f, 0.16f, 0.22f)
        tn(v, 0.07f, "triangle", 1975.5f, 0f, 0f, 0.003f, 0.26f, 0.22f, hold = 0.03f)
        if (headshot) metal(v, 0.12f, 2637f, 0.2f, arrayOf(floatArrayOf(1f, 1f, 0.55f), floatArrayOf(1.005f, 0.5f, 0.5f), floatArrayOf(2.76f, 0.4f, 0.3f), floatArrayOf(5.4f, 0.18f, 0.15f)))
    }

    fun playHurt(amount: Float) {
        val a = (amount / 100f).coerceIn(0.03f, 1f)
        val v = newVoice(null, 0.35f + 0.5f * a, 2f, 1f, 100f, 0.03f, 1)
        val r = jit(0.05f)
        nz(v, 0f, "pink", 0.002f, 0.15f, 0.7f, listOf(flt("lowpass", 400 * r, 0f, 150f, 0.12f)))
        tn(v, 0f, "sine", 85 * r, 42f, 0.12f, 0.002f, 0.18f, 0.6f)
        nz(v, 0f, "white", 0.001f, 0.03f, 0.25f, listOf(flt("bandpass", 1100 * r, 1.2f)))
        if (a >= 0.12f) ring(a * 0.8f, 0.6f + 1.4f * a)
        if (a >= 0.25f) muffle(0.35f + 0.5f * a, 0.5f + a)
    }

    private fun grunt(v: Voice, t: Float, f0: Float, f1: Float, dur: Float, g: Float) {
        val l = Layer()
        l.isOsc = true; l.oscWave = "saw"
        l.f0 = f0; l.f1 = f1; l.sw = dur
        l.delay = t
        l.env.setup(0.02f, dur * 0.8f, 1f, dur * 0.2f)
        // 共振峰带通（3 个）
        l.filters = listOf(
            FilterSpec("bandpass", 620f, 620f, 0f, 6f), FilterSpec("bandpass", 1050f, 1050f, 0f, 8f), FilterSpec("bandpass", 2500f, 2500f, 0f, 10f),
        )
        addLayer(v, l)
        nz(v, t, "white", 0.02f, dur, g * 0.15f, listOf(flt("bandpass", 1200f, 1f)))
    }

    fun playDeath(pos: FloatArray?) {
        val local = pos == null
        val v = newVoice(pos, if (local) 0.7f else 1f, 3f, 1.1f, 80f, 0.12f, 0)
        val r = jit(0.06f)
        grunt(v, 0f, 150 * r, 90 * r, 0.24f, 0.16f)
        val tf = rand(0.28f, 0.4f)
        tn(v, tf, "sine", 80 * r, 40f, 0.12f, 0.003f, 0.24f, 0.7f)
        nz(v, tf, "pink", 0.002f, 0.18f, 0.55f, listOf(flt("lowpass", 450 * r)))
        nz(v, tf, "white", 0.001f, 0.035f, 0.2f, listOf(flt("bandpass", 1500 * r, 1.2f)))
        val tg = tf + rand(0.08f, 0.15f)
        tn(v, tg, "sine", 110 * r, 70f, 0f, 0.002f, 0.07f, 0.35f)
        metal(v, tg + 0.01f, rand(1400f, 2200f), 0.1f, arrayOf(floatArrayOf(1f, 1f, 0.09f), floatArrayOf(2.3f, 0.6f, 0.06f), floatArrayOf(3.7f, 0.3f, 0.04f)))
        metal(v, tg + rand(0.08f, 0.16f), rand(2400f, 3400f), 0.05f, arrayOf(floatArrayOf(1f, 1f, 0.05f), floatArrayOf(1.8f, 0.5f, 0.03f)))
    }

    fun playKnife(kind: String, result: String, pos: FloatArray?) {
        val heavy = kind == "heavy"
        val local = pos == null
        val v = newVoice(pos, if (local) 0.6f else 0.9f, 2f, 1.2f, 50f, 0.08f, 0)
        val r = jit(0.06f)
        val a = if (heavy) 0.11f else 0.06f
        val dur = if (heavy) 0.26f else 0.15f
        whoosh(v, 0f, "pink", a, dur, if (heavy) 0.55f else 0.45f, 450 * r, (if (heavy) 2200f else 3000f) * r, 800 * r, 1.4f)
        whoosh(v, 0f, "white", a * 0.9f, dur * 0.7f, 0.12f, 2500 * r, 6000 * r, 3000 * r, 2.5f)
        val th = a * (if (heavy) 1.1f else 0.9f)
        val hv = if (heavy) 1.3f else 1f
        if (result == "flesh") {
            nz(v, th, "pink", 0.002f, 0.08f, 0.7f * hv, listOf(flt("lowpass", 800 * r, 0f, 250f, 0.07f)))
            tn(v, th, "sine", 130 * r, 60f, 0f, 0.002f, 0.09f, 0.5f * hv)
            nz(v, th + 0.004f, "white", 0.005f, 0.05f, 0.25f, listOf(flt("bandpass", 1600 * r, 3f)))
        } else if (result == "wall") {
            nz(v, th, "white", 0.0002f, 0.004f, 0.5f * hv, listOf(flt("highpass", 2500f)))
            metal(v, th, rand(1300f, 1800f), 0.2f * hv, arrayOf(floatArrayOf(1f, 1f, 0.25f), floatArrayOf(2.41f, 0.7f, 0.18f), floatArrayOf(3.87f, 0.5f, 0.12f), floatArrayOf(5.3f, 0.3f, 0.08f)))
            nz(v, th + 0.003f, "white", 0.003f, 0.06f, 0.15f, listOf(flt("bandpass", 3500f, 2f)))
        }
    }

    fun playGrenadePin() {
        val v = newVoice(null, 0.55f, 2f, 1f, 100f, 0.05f, 1)
        click(v, 0f, 4200f, 0.3f)
        metal(v, 0f, rand(3400f, 3900f), 0.1f, arrayOf(floatArrayOf(1f, 1f, 0.09f), floatArrayOf(1.62f, 0.6f, 0.06f), floatArrayOf(2.5f, 0.3f, 0.04f)))
        nz(v, 0.015f, "white", 0.01f, 0.05f, 0.1f, listOf(flt("bandpass", 4500f, 3f)))
        metal(v, 0.08f, rand(2300f, 2700f), 0.08f, arrayOf(floatArrayOf(1f, 1f, 0.12f), floatArrayOf(2.1f, 0.5f, 0.07f)))
        metal(v, 0.13f, rand(2500f, 2900f), 0.04f, arrayOf(floatArrayOf(1f, 1f, 0.08f)))
    }

    fun playGrenadeThrow() { throwWhoosh(null) }

    private fun throwWhoosh(p: FloatArray?) {
        val v = newVoice(p, if (p != null) 0.8f else 0.55f, 2f, 1.2f, 40f, 0.05f, 0)
        nz(v, 0f, "white", 0.03f, 0.12f, 0.1f, listOf(flt("bandpass", 2200f, 0.8f)))
        whoosh(v, 0.04f, "pink", 0.1f, 0.22f, 0.45f, 350f, 1700f, 500f, 1.3f)
        metal(v, 0.12f, rand(4000f, 4600f), 0.05f, arrayOf(floatArrayOf(1f, 1f, 0.1f), floatArrayOf(1.5f, 0.4f, 0.06f)))
    }

    fun playGrenadeBounce(pos: FloatArray?) {
        val v = newVoice(pos, 0.8f, 3f, 1.1f, 80f, 0.12f, 0)
        val f = rand(750f, 1050f)
        nz(v, 0f, "white", 0.0003f, 0.004f, 0.4f, listOf(flt("highpass", 2500f)))
        tn(v, 0f, "sine", 210f, 120f, 0f, 0.001f, 0.05f, 0.35f)
        metal(v, 0f, f, 0.22f, arrayOf(floatArrayOf(1f, 1f, 0.16f), floatArrayOf(2.14f, 0.7f, 0.11f), floatArrayOf(3.63f, 0.45f, 0.07f), floatArrayOf(5.1f, 0.25f, 0.05f)))
        nz(v, 0f, "pink", 0.001f, 0.05f, 0.3f, listOf(flt("bandpass", 1200f, 1f)))
    }

    fun playExplosion(pos: FloatArray?) {
        val d = pos?.let { dist(it) } ?: 0f
        val v = newVoice(pos, 1.25f, 10f, 0.8f, 500f, 0.55f, 0)
        val r = jit(0.05f); val k = jit(0.05f)
        val drv = Pair(2.2f / 3f, 0.75f)
        nz(v, 0f, "white", 0.0004f, 0.012f, 0.7f, listOf(flt("highpass", 1500 * r)))
        tn(v, 0f, "sine", 95 * r, 28f, 0.45f * k, 0.003f, 0.8f * k, 1.0f, drive = drv)
        tn(v, 0f, "triangle", 62 * r, 30f, 0.3f, 0.004f, 0.55f, 0.45f, drive = drv)
        nz(v, 0f, "pink", 0.002f, 0.95f * k, 1.0f, listOf(flt("lowpass", 5200 * r, 0f, 260f, 0.7f)), drv)
        nz(v, 0f, "white", 0.001f, 0.35f * k, 0.45f, listOf(flt("bandpass", 950 * r, 0.8f)), drv)
        nz(v, 0.02f, "brown", 0.06f, 2.6f * k, 0.55f, listOf(flt("lowpass", 240f, 0f, 80f, 2.2f)))
        for (e in arrayOf(floatArrayOf(0.21f, 0.22f), floatArrayOf(0.47f, 0.13f), floatArrayOf(0.9f, 0.06f))) {
            nz(v, e[0] * jit(0.1f), "pink", 0.01f, 0.35f, e[1], listOf(flt("lowpass", 1100f), flt("highpass", 120f)))
        }
        val n = 16 + Random.nextInt(8)
        for (i in 0 until n) {
            val dt2 = 0.08f + 1.6f * Math.pow(Random.nextFloat().toDouble(), 1.7).toFloat()
            val gg = 0.12f * (1 - dt2 / 2) * rand(0.4f, 1f)
            if (Random.nextFloat() < 0.55f) metal(v, dt2, rand(1600f, 5200f), gg, arrayOf(floatArrayOf(1f, 1f, rand(0.03f, 0.08f)), floatArrayOf(1.71f, 0.5f, 0.03f)))
            else click(v, dt2, rand(1200f, 6000f), gg * 1.5f, 3f, rand(0.008f, 0.03f))
        }
        if (pos != null && d < 12) {
            val s = 1 - d / 12
            muffle(0.5f + 0.5f * s, 1f + 2f * s)
            ring(0.3f + 0.7f * s, 1.5f + 2f * s)
        }
    }

    fun playWeaponSwitch(weaponId: String) {
        val v = newVoice(null, 0.42f, 2f, 1f, 100f, 0.05f, 1)
        val fm = (WEIGHT[weaponId] ?: 1f) * jit(0.04f)
        nz(v, 0f, "pink", 0.03f, 0.12f, 0.18f, listOf(flt("bandpass", 1600f, 0.7f)))
        when (weaponId) {
            "knife" -> {
                nz(v, 0.03f, "white", 0.06f, 0.14f, 0.18f, listOf(flt("bandpass", 2800f, 4f, 7500f, 0.18f)))
                metal(v, 0.17f, rand(4800f, 5600f), 0.07f, arrayOf(floatArrayOf(1f, 1f, 0.35f), floatArrayOf(1.43f, 0.6f, 0.25f), floatArrayOf(2.2f, 0.3f, 0.15f)))
            }
            "grenade" -> {
                metal(v, 0.06f, 3000f, 0.08f, arrayOf(floatArrayOf(1f, 1f, 0.06f), floatArrayOf(1.6f, 0.5f, 0.04f)))
                tn(v, 0.06f, "sine", 220f, 140f, 0f, 0.002f, 0.04f, 0.15f)
            }
            "awm" -> {
                tn(v, 0.07f, "sine", 150f, 90f, 0f, 0.002f, 0.08f, 0.35f)
                click(v, 0.07f, 1700f, 0.45f, 1.3f, 0.025f)
                metal(v, 0.07f, 1600f, 0.09f, arrayOf(floatArrayOf(1f, 1f, 0.09f), floatArrayOf(2.2f, 0.5f, 0.06f)))
                click(v, 0.2f, 2600f, 0.3f)
            }
            "deagle" -> {
                click(v, 0.05f, 2600f, 0.45f, 2f, 0.015f)
                metal(v, 0.05f, 2400f, 0.1f, arrayOf(floatArrayOf(1f, 1f, 0.06f), floatArrayOf(1.55f, 0.5f, 0.04f)))
                click(v, 0.13f, 3400f, 0.3f)
            }
            else -> {
                click(v, 0.05f, 2200 * fm, 0.45f, 2f, 0.012f)
                tn(v, 0.05f, "sine", 180 * fm, 110f, 0f, 0.002f, 0.045f, 0.2f)
                click(v, 0.14f, 3000 * fm, 0.35f)
                metal(v, 0.14f, 2600 * fm, 0.07f, arrayOf(floatArrayOf(1f, 1f, 0.06f), floatArrayOf(1.5f, 0.5f, 0.04f)))
            }
        }
    }

    fun playScope(zoomIn: Boolean) {
        val up = zoomIn
        val v = newVoice(null, 0.45f, 2f, 1f, 100f, 0f, 1)
        nz(v, 0f, "white", 0.0003f, 0.006f, 0.4f, listOf(flt("highpass", 3500f)))
        nz(v, 0.005f, "white", 0.012f, 0.08f, 0.12f, listOf(flt("bandpass", if (up) 1200f else 3400f, 3f, if (up) 3400f else 1200f, 0.09f)))
        metal(v, if (up) 0.07f else 0.04f, if (up) 4600f else 3800f, 0.04f, arrayOf(floatArrayOf(1f, 1f, 0.05f), floatArrayOf(1.6f, 0.5f, 0.03f)))
        tn(v, 0f, "sine", if (up) 180f else 140f, if (up) 120f else 90f, 0f, 0.002f, 0.05f, 0.12f)
    }

    fun playShellDrop(pos: FloatArray?) {
        val local = pos == null
        val v = newVoice(pos, if (local) 0.28f else 0.6f, 1.5f, 1.4f, 30f, 0.06f, 0)
        var t = rand(0.3f, 0.6f)
        val f = rand(3300f, 4600f); val n = 2 + Random.nextInt(3)
        var gap = rand(0.07f, 0.11f); var amp = 1f
        for (i in 0 until n) {
            nz(v, t, "white", 0.0002f, 0.003f, 0.3f * amp, listOf(flt("highpass", 5000f)))
            metal(v, t, f * jit(0.02f), 0.35f * amp, arrayOf(floatArrayOf(1f, 1f, 0.1f * amp + 0.03f), floatArrayOf(1.58f, 0.6f, 0.07f), floatArrayOf(2.43f, 0.35f, 0.05f), floatArrayOf(3.66f, 0.2f, 0.03f)))
            t += gap
            gap *= rand(0.55f, 0.7f)
            amp *= rand(0.45f, 0.6f)
        }
        nz(v, t, "white", 0.02f, 0.12f, 0.03f, listOf(flt("bandpass", 5200f, 4f)))
    }

    fun playUI(kind: String) {
        val v = newVoice(null, 1.1f, 2f, 1f, 100f, if (kind == "roundEnd" || kind == "start") 0.12f else 0f, 1)
        when (kind) {
            "hover" -> tn(v, 0f, "sine", 2300f, 0f, 0f, 0.002f, 0.03f, 0.12f)
            "buy" -> {
                nz(v, 0f, "white", 0.0005f, 0.01f, 0.25f, listOf(flt("highpass", 3000f)))
                tn(v, 0f, "triangle", 1046.5f, 0f, 0f, 0.003f, 0.09f, 0.3f)
                tn(v, 0.07f, "triangle", 1568f, 0f, 0f, 0.003f, 0.22f, 0.3f)
                metal(v, 0.07f, 3136f, 0.06f, arrayOf(floatArrayOf(1f, 1f, 0.2f), floatArrayOf(2.7f, 0.4f, 0.1f)))
            }
            "start" -> {
                val notes = floatArrayOf(523.25f, 659.25f, 783.99f, 1046.5f)
                for ((i, f) in notes.withIndex()) {
                    val last = i == notes.size - 1
                    tn(v, i * 0.075f, "saw", f, 0f, 0f, 0.005f, if (last) 0.5f else 0.14f, 0.12f, hold = if (last) 0.15f else 0.03f, flt = listOf(flt("lowpass", 1500f, 2f, 4500f, 0.3f)))
                    tn(v, i * 0.075f, "square", f * 0.5f, 0f, 0f, 0.005f, if (last) 0.5f else 0.14f, 0.04f, hold = if (last) 0.15f else 0.03f, flt = listOf(flt("lowpass", 1800f)))
                }
                tn(v, 0f, "sine", 110f, 55f, 0f, 0.003f, 0.2f, 0.35f)
            }
            "countdown" -> {
                tn(v, 0f, "square", 880f, 0f, 0f, 0.002f, 0.1f, 0.12f, hold = 0.06f, flt = listOf(flt("lowpass", 3000f)))
                tn(v, 0f, "sine", 1760f, 0f, 0f, 0.002f, 0.08f, 0.06f, hold = 0.04f)
            }
            "roundEnd" -> {
                for (f in floatArrayOf(659.25f, 783.99f, 987.77f)) tn(v, 0f, "triangle", f, 0f, 0f, 0.004f, 0.25f, 0.12f)
                for (f in floatArrayOf(523.25f, 659.25f, 783.99f, 1046.5f)) {
                    tn(v, 0.22f, "triangle", f, 0f, 0f, 0.006f, 0.9f, 0.12f, hold = 0.35f)
                    tn(v, 0.22f, "saw", f * 0.5f, 0f, 0f, 0.01f, 0.8f, 0.03f, hold = 0.3f, flt = listOf(flt("lowpass", 1400f)))
                }
            }
            else -> { // click
                tn(v, 0f, "sine", 1500f, 1000f, 0.03f, 0.001f, 0.035f, 0.45f)
                nz(v, 0f, "white", 0.0003f, 0.004f, 0.3f, listOf(flt("highpass", 4000f)))
            }
        }
    }

    /** 播报前的徽章音 */
    fun badge(text: String) {
        val s = text.lowercase()
        val lvl = when {
            Regex("multi|ultra|mega|monster").containsMatchIn(s) -> 3
            s.contains("triple") -> 2
            s.contains("double") || s.contains("headshot") -> 1
            else -> 0
        }
        val v = newVoice(null, 1.1f, 2f, 1f, 100f, 0.12f, 3)
        val base = 392f * Math.pow(2.0, (lvl * 2) / 12.0).toFloat()
        val parts = arrayOf(floatArrayOf(1f, -6f), floatArrayOf(1.26f, 5f), floatArrayOf(1.5f, -3f), floatArrayOf(2f, 4f))
        for (p in parts) {
            val f = base * p[0]
            tn(v, 0f, "saw", f * 0.84f, f, 0.09f, 0.008f, 0.24f, 0.07f, hold = 0.05f, det = p[1], flt = listOf(flt("lowpass", 900f, 3f, 6000f, 0.12f)))
        }
        tn(v, 0f, "sine", 110f, 70f, 0f, 0.002f, 0.12f, 0.25f)
        tn(v, 0.06f, "sine", base * 8, 0f, 0f, 0.002f, 0.18f, 0.04f)
    }

    // 耳鸣
    private fun ring(level: Float, dur: Float) {
        val v = newVoice(null, 1f, 2f, 1f, 100f, 0f, 1)
        val f = rand(3700f, 4300f); val g = 0.012f + 0.035f * level
        tn(v, 0f, "sine", f, 0f, 0f, 0.05f, dur * 0.75f, g, hold = dur * 0.25f)
        tn(v, 0f, "sine", f * 1.006f, 0f, 0f, 0.08f, dur * 0.7f, g * 0.5f, hold = dur * 0.2f)
    }

    // 世界音短暂发闷
    private fun muffle(depth: Float, dur: Float) {
        val lo = (20000f * Math.pow(0.05.toDouble(), depth.coerceIn(0f, 1f).toDouble()).toFloat()).coerceIn(600f, 20000f)
        muffleTarget = lo / 20000f
        muffleTau = 0.04f
        muffleRecoverAt = time + dur * 0.3
        muffleRecoverTau = max(0.05, dur * 0.3)
    }
    private var muffleRecoverAt = -1.0
    private var muffleRecoverTau = 0.1

    fun setLowHealth(on: Boolean) {
        hbOn = on
        if (on && hbNext < time) hbNext = time + 0.05
    }

    // =========================================================================
    // 环境音
    // =========================================================================
    fun startAmbient() {
        if (ambOn) return
        ambOn = true
        val out = Voice().apply { bus = 2; gain = 1f }
        synchronized(ambient) { ambient.add(out) }
        // 海浪：褐噪声 + 慢速 LFO 起伏（左右两层）
        for (side in floatArrayOf(-0.65f, 0.65f)) {
            val l = Layer()
            l.noise = bank.brown
            l.loop = true
            l.noiseOff = Random.nextFloat() * 4f
            l.env.setup(0.001f, 1e9f, 0.1f, 0f)
            l.filters = listOf(FilterSpec("lowpass", rand(550f, 750f), rand(550f, 750f), 0f, 0f))
            l.lfoGainRate = rand(0.055f, 0.1f); l.lfoGainDepth = 0.065f
            l.lfoFRate = rand(0.04f, 0.08f); l.lfoFDepth = 300f
            l.baseGain = 0.1f
            l.wet = 0.1f
            out.layers.add(l)
        }
        // 浪花泡沫
        run {
            val l = Layer()
            l.noise = bank.pink
            l.loop = true
            l.noiseOff = Random.nextFloat() * 4f
            l.env.setup(0.001f, 1e9f, 0.03f, 0f)
            l.filters = listOf(FilterSpec("bandpass", 1300f, 1300f, 0f, 0.6f))
            l.lfoGainRate = 0.083f; l.lfoGainDepth = 0.025f
            out.layers.add(l)
        }
        // 海风
        run {
            val l = Layer()
            l.noise = bank.pink
            l.loop = true
            l.noiseOff = Random.nextFloat() * 4f
            l.env.setup(0.001f, 1e9f, 0.06f, 0f)
            l.filters = listOf(FilterSpec("bandpass", 520f, 520f, 0f, 1.1f))
            l.lfoFRate = 0.047f; l.lfoFDepth = 110f
            l.lfoGainRate = 0.031f; l.lfoGainDepth = 0.02f
            out.layers.add(l)
            windVoice = out
        }
        // 柴油主机
        run {
            val f0 = rand(46f, 52f)
            val harmonics = arrayOf(
                floatArrayOf(1f, 0.55f, 0f), floatArrayOf(2.003f, 0.35f, 0f), floatArrayOf(3f, 0.16f, 0f),
                floatArrayOf(4.01f, 0.08f, 0f), floatArrayOf(0.5f, 0.1f, 0f),
            )
            for (h in harmonics) {
                val l = Layer()
                l.isOsc = true
                l.oscWave = when (h[0]) {
                    3f -> "triangle"; 0.5f -> "saw"; else -> "sine"
                }
                l.f0 = f0 * h[0]
                l.loop = true
                l.env.setup(0.001f, 1e9f, h[1] * 0.05f, 0f)
                l.lfoFRate = 0.17f; l.lfoFDepth = f0 * 0.35f
                l.lfoFRate = 0.43f // 二重 LFO 简化为一个
                l.baseGain = h[1] * 0.05f
                out.layers.add(l)
            }
            // 船体低频隆隆
            val l = Layer()
            l.noise = bank.brown
            l.loop = true
            l.noiseOff = Random.nextFloat() * 4f
            l.env.setup(0.001f, 1e9f, 0.5f * 0.05f, 0f)
            l.filters = listOf(FilterSpec("lowpass", 90f, 90f, 0f, 0f))
            out.layers.add(l)
        }
        nGust = time + rand(3f, 8f)
        nGull = time + rand(3f, 9f)
        nCreak = time + rand(6f, 15f)
        nSplash = time + rand(2f, 6f)
        nHorn = time + rand(30f, 70f)
    }

    fun stopAmbient() {
        ambOn = false
        synchronized(ambient) {
            for (v in ambient) synchronized(v.layers) { v.layers.clear() }
            ambient.clear()
        }
        windVoice = null
    }

    private fun around(dMin: Float, dMax: Float, yMin: Float, yMax: Float): FloatArray {
        val ang = rand(0f, (2 * PI).toFloat())
        val d = rand(dMin, dMax)
        return floatArrayOf(lx + cos(ang) * d, ly + rand(yMin, yMax), lz + sin(ang) * d)
    }

    private fun gust() {
        if (windVoice == null) return
        // 简化：短促一阵风噪声
        val v = newVoice(null, 0.5f, 2f, 1f, 100f, 0.1f, 2)
        val f = rand(900f, 1700f)
        val up = rand(0.8f, 2.2f); val hold = rand(0.4f, 2f); val down = rand(1.5f, 3.5f)
        val l = Layer()
        l.noise = bank.pink
        l.noiseOff = Random.nextFloat() * 4f
        l.delay = 0.01f
        l.env.setup(up, down, 0.05f, hold)
        l.filters = listOf(FilterSpec("bandpass", f, f * 0.9f, up + hold + down, 14f))
        addLayer(v, l)
    }

    private fun gulls() {
        val v = newVoice(around(20f, 60f, 8f, 25f), rand(1.6f, 2.6f), 15f, 1f, 400f, 0.3f, 2)
        var t = 0f
        val n = 1 + Random.nextInt(4)
        val base = rand(1350f, 1900f)
        for (i in 0 until n) {
            val d = rand(0.18f, 0.38f)
            val f = base * jit(0.04f)
            tn(v, t, "saw", f * 0.75f, f * 0.62f, d, 0.025f, d * 0.65f, 0.22f, hold = d * 0.35f,
                fm = floatArrayOf(rand(26f, 42f), f * 0.05f), flt = listOf(flt("bandpass", 2100f, 1.6f), flt("highpass", 900f)))
            nz(v, t, "white", 0.03f, d * 0.6f, 0.035f, listOf(flt("bandpass", 2800f, 2.5f)), hold = d * 0.3f)
            t += d + rand(0.1f, 0.3f)
        }
    }

    private fun creak() {
        val v = newVoice(around(5f, 22f, -3f, 4f), rand(0.8f, 1.3f), 5f, 1f, 120f, 0.35f, 2)
        val dur = rand(0.7f, 2f)
        val l = Layer()
        l.isOsc = true; l.oscWave = "saw"
        l.f0 = rand(14f, 30f); l.f1 = l.f0 * rand(0.75f, 1.35f); l.sw = dur
        l.delay = 0f
        l.env.setup(dur * 0.2f, dur * 0.8f, 1f, 0f)
        l.filters = listOf(
            FilterSpec("bandpass", rand(320f, 450f), rand(320f, 450f), dur, 22f),
            FilterSpec("bandpass", rand(800f, 1000f), rand(800f, 1000f), dur, 28f),
        )
        addLayer(v, l)
    }

    private fun splash() {
        val v = newVoice(around(6f, 20f, -5f, -3f), rand(0.35f, 0.7f), 5f, 1f, 100f, 0.2f, 2)
        val a = rand(0.25f, 0.6f); val dur = rand(0.8f, 1.8f)
        nz(v, 0f, "pink", a, dur, 0.6f, listOf(flt("bandpass", 900f, 0.7f, 380f, a + dur)))
        nz(v, a * 0.8f, "white", 0.1f, dur * 0.7f, 0.08f, listOf(flt("highpass", 2500f)))
        nz(v, 0f, "brown", a * 1.2f, dur, 0.4f, listOf(flt("lowpass", 250f)))
    }

    private fun horn() {
        val v = newVoice(around(250f, 420f, 10f, 20f), 0.22f, 150f, 1f, 3000f, 0.7f, 2)
        val f = rand(78f, 98f)
        val n = if (Random.nextFloat() < 0.35f) 2 else 1
        for (i in 0 until n) {
            val t = i * 4.2f
            val hold = if (i == 0) rand(1.8f, 3f) else rand(1f, 1.6f)
            val parts = arrayOf(floatArrayOf(1f, 0.5f, -4f), floatArrayOf(1f, 0.4f, 5f), floatArrayOf(1.5f, 0.3f, 0f), floatArrayOf(2f, 0.18f, 3f))
            for (p in parts) {
                tn(v, t, "saw", f * p[0] * 0.97f, f * p[0], 0.35f, 0.35f, 1.3f, p[1], hold = hold, det = p[2], flt = listOf(flt("lowpass", 650f, 1f)))
            }
        }
    }

    private fun beat(t: Float) {
        val v = newVoice(null, 0.36f, 2f, 1f, 100f, 0f, 1)
        tn(v, t, "triangle", 64f, 44f, 0.08f, 0.01f, 0.14f, 0.9f, flt = listOf(flt("lowpass", 260f)))
        nz(v, t, "brown", 0.008f, 0.09f, 0.35f, listOf(flt("lowpass", 140f)))
        val t2 = t + 0.27f
        tn(v, t2, "triangle", 56f, 40f, 0.09f, 0.012f, 0.16f, 0.7f, flt = listOf(flt("lowpass", 240f)))
        nz(v, t2, "brown", 0.01f, 0.1f, 0.25f, listOf(flt("lowpass", 120f)))
    }

    // =========================================================================
    // 渲染循环
    // =========================================================================
    private fun renderChunk() {
        val dtS = chunk.toDouble() / sr
        val dt = 1f / sr
        for (i in mixL.indices) { mixL[i] = 0f; mixR[i] = 0f }
        var wetAcc = 0f
        // 事件调度
        if (hbOn) {
            if (hbNext < time) hbNext = time + 0.02
            while (hbNext < time + 0.3) {
                beat((hbNext - time).toFloat())
                hbNext += 0.68 * jit(0.02f)
            }
        }
        if (ambOn) {
            if (time >= nGust) { gust(); nGust = time + rand(5f, 14f) }
            if (time >= nGull) { gulls(); nGull = time + rand(7f, 22f) }
            if (time >= nCreak) { creak(); nCreak = time + rand(9f, 26f) }
            if (time >= nSplash) { splash(); nSplash = time + rand(3f, 9f) }
            if (time >= nHorn) { horn(); nHorn = time + rand(80f, 180f) }
        }
        // 收集活动 voice（快照 layers，防并发修改）
        val active = ArrayList<Pair<Voice, List<Layer>>>(voices.size + ambient.size)
        synchronized(voices) {
            val it = voices.iterator()
            while (it.hasNext()) {
                val v = it.next()
                val alive = synchronized(v.layers) {
                    v.layers.removeAll { l -> l.env.done }
                    v.layers.isNotEmpty()
                }
                if (!alive) { it.remove(); continue }
                active.add(Pair(v, synchronized(v.layers) { ArrayList(v.layers) }))
            }
        }
        synchronized(ambient) {
            for (v in ambient) active.add(Pair(v, synchronized(v.layers) { ArrayList(v.layers) }))
        }
        // 逐层渲染
        for ((v, layers) in active) {
            val busVol = when (v.bus) {
                1 -> volSfx; 2 -> volAmbient; 3 -> volVoice; else -> volSfx
            }
            for (l in layers) {
                var o = l.delay
                var gl = 0f; var gr = 0f
                if (v.spatial) {
                    val pan = (v.sPan + 1f) * 0.25f * PI.toFloat() // 0..π/2
                    gl = cos(pan); gr = sin(pan)
                } else { gl = 0.7071f; gr = 0.7071f }
                val spGain = if (v.spatial) v.sGain else 1f
                val vg = v.gain * spGain * busVol
                val wetV = l.wet * v.wetScale * busVol
                for (s in 0 until chunk) {
                    if (o > 0f) { o -= dt; continue }
                    val ev = l.env.step(dt)
                    if (ev <= 0f && l.env.done) break
                    var x: Float
                    if (l.isOsc) {
                        // 频率扫描
                        val tt = l.env.t - l.delay
                        var f = l.f0
                        if (l.f1 > 0 && l.sw > 0) {
                            val k = if (tt < l.sw) tt / l.sw else 1f
                            f = l.f0 * Math.pow((l.f1 / kotlin.math.max(l.f0, 0.01f)).toDouble(), k.toDouble()).toFloat()
                        }
                        if (l.lfoFDepth > 0) f *= 1f + sin(2f * PI.toFloat() * l.lfoFRate * (l.age + tt).toFloat()) * 0.3f
                        if (l.det != 0f) f *= Math.pow(2.0, (l.det / 12f).toDouble()).toFloat()
                        l.fmPhase += 2f * PI.toFloat() * l.fmRate * dt
                        val fmK = if (l.fmDepth > 0) 1f + sin(l.fmPhase) * (l.fmDepth / kotlin.math.max(f, 1f)) else 1f
                        l.phase += 2f * PI.toFloat() * f * fmK * dt
                        val ph = (l.phase / (2f * PI.toFloat())) % 1f
                        x = when (l.oscWave) {
                            "triangle" -> 2f / PI.toFloat() * kotlin.math.asin(sin(2f * PI.toFloat() * ph))
                            "saw" -> 2f * ph - 1f
                            "square" -> if (ph < 0.5f) 1f else -1f
                            else -> sin(2f * PI.toFloat() * ph)
                        }
                    } else {
                        val bankArr = l.noise!!
                        val idx = ((l.noiseOff + l.age) * sr).toInt() % bankArr.size
                        x = bankArr[idx]
                    }
                    // 滤波链
                    for (fs in l.filters) {
                        if (!fs.inited) { fs.bq.set(fs.type, fs.f0, fs.q, sr.toFloat()); fs.inited = true }
                        x = fs.bq.process(x)
                    }
                    var out = x * ev
                    if (l.hasDrive) out = tanhDrive(out * l.drivePre) * l.drivePost
                    l.age += dt
                    val g2 = if (l.lfoGainDepth > 0) 1f + sin(2f * PI.toFloat() * l.lfoGainRate * l.age) * (l.lfoGainDepth / kotlin.math.max(l.baseGain, 0.001f)) else 1f
                    out *= vg * g2 * 0.5f
                    mixL[s] += out * gl
                    mixR[s] += out * gr
                    wetAcc += out * wetV
                }
            }
        }
        // 主链：闷音 + 混响 + 软限幅
        val muffleBusVol = volSfx * volMaster * 0.5f
        val target = muffleTarget
        for (s in 0 until chunk) {
            // 闷音恢复
            if (muffleRecoverAt > 0 && time + s.toDouble() / sr >= muffleRecoverAt) {
                muffleTarget = 1f
                muffleRecoverAt = -1.0
            }
            val cur = if (muffleTarget != target) target else muffleTarget
            val fc = 20000f * cur
            muffleC = (1.0 - exp(-2.0 * PI * fc / sr)).toFloat()
            // 世界总线闷音（把整体都过低通——简化）
            var l = mixL[s]
            var r = mixR[s]
            muffleY += ((l + r) * 0.5f - muffleY) * muffleC.toFloat()
            val wet = reverb.process(wetAcc)
            val wl = wet * 0.6f
            var ol = (l * 0.85f + muffleY * 0.15f + wl) * volMaster * 0.5f
            var or2 = (r * 0.85f + muffleY * 0.15f + wl) * volMaster * 0.5f
            mixL[s] = softClip(ol)
            mixR[s] = softClip(or2)
        }
        time += dtS
    }

    var tts: ((String) -> Unit)? = null

    fun announce(text: String) {
        badge(text)
        tts?.invoke(text)
    }
}
