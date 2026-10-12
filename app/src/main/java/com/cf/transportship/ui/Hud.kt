// HUD、菜单与触屏控制（Canvas 原生绘制，移植 hud.js + touch.js 的视觉与交互）
package com.cf.transportship.ui

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import com.cf.transportship.game.Actor
import com.cf.transportship.game.Game
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

const val ST_LOADING = 0
const val ST_MENU = 1
const val ST_PLAY = 2
const val ST_PAUSE = 3
const val ST_LOADOUT = 4
const val ST_END = 5

class FeedEntry(var text: String, var color: Int, var t: Float)
class DmgMark(var ang: Float, var t: Float)
class Badge(var text: String, var sub: String, var hs: Boolean, var t: Float)

class Hud(val game: () -> Game?, val d: Float) {
    var state = ST_LOADING
    var loadProgress = 0f
    var loadText = "LOADING"
    var host: android.view.View? = null
    /** 把会改动对局状态的操作投递到 GL 线程执行（触摸在 UI 线程触发，避免与渲染循环竞态） */
    var glPost: (() -> Unit) -> Unit = { it() }
    val joyIdPub: Int get() = joyId
    val lookIdPub: Int get() = lookId
    fun hasBtn(id: Int): Boolean = buttonDown.containsKey(id)

    // 局内数据（Game 每帧写入）
    var fps = 0
    var spreadPx = 0f
    var aimName = ""
    var aimTeam = ""
    var hitT = 0f
    var hitKind = 0 // 0 普通 1 爆头 2 击杀
    var toastText = ""
    var toastT = 0f
    var boardShown = false

    private val feed = ArrayList<FeedEntry>()
    private val dmgDirs = ArrayList<DmgMark>()
    private var badge: Badge? = null
    var radarBmp: android.graphics.Bitmap? = null

    // 触屏状态
    private var joyId = -1
    private var lookId = -1
    private var joyCX = 0f; private var joyCY = 0f
    private var knobX = 0f; private var knobY = 0f
    private var sliderDrag: String? = null
    private var lastTouchX = 0f; private var lastTouchY = 0f
    var touchMode = true

    // 菜单点击区域
    private class Btn(val id: String, val x: Float, val y: Float, val w: Float, val h: Float)
    private val btns = ArrayList<Btn>()

    // ---------- 事件推送 ----------
    /** GL 线程写入、UI 线程绘制读取的列表统一同步，避免 ConcurrentModificationException */
    fun killFeed(killer: Actor?, victim: Actor, wid: String, hs: Boolean, wall: Boolean, mine: Boolean) {
        val kn = killer?.name ?: ""
        val wn = com.cf.transportship.game.WEAPONS[wid]?.name ?: wid
        val s = buildString {
            if (killer != null) append(kn)
            append("  [")
            append(wn)
            append("]")
            if (wall) append(" ✦穿墙")
            if (hs) append(" ✛爆头")
            append("  ")
            append(victim.name)
        }
        synchronized(feed) {
            feed.add(0, FeedEntry(s, if (mine) Color.argb(255, 255, 210, 90) else Color.argb(230, 210, 216, 222), 0f))
            while (feed.size > 6) feed.removeAt(feed.size - 1)
        }
    }

    fun hitmarker(hs: Boolean, kill: Boolean) {
        hitT = if (kill) 0.45f else 0.22f
        hitKind = if (kill) 2 else if (hs) 1 else 0
    }

    fun damageFrom(ang: Float) {
        synchronized(dmgDirs) { dmgDirs.add(DmgMark(ang, 1.4f)) }
    }

    fun badge(text: String, sub: String, hs: Boolean) {
        badge = Badge(text, sub, hs, 2.2f)
    }

    fun toast(text: String, dur: Float = 2.5f) {
        toastText = text
        toastT = dur
    }

    fun update(dt: Float) {
        if (hitT > 0) hitT -= dt
        if (toastT > 0) toastT -= dt
        badge?.let { b -> b.t -= dt; if (b.t <= 0) badge = null }
        synchronized(dmgDirs) {
            val it = dmgDirs.iterator()
            while (it.hasNext()) { val m = it.next(); m.t -= dt; if (m.t <= 0) it.remove() }
        }
    }

    // ---------- 触摸 ----------
    fun onTouch(action: Int, x: Float, y: Float, id: Int): Boolean {
        val g = game() ?: return false
        val r = 70 * d
        when (action) {
            0 -> { // DOWN
                // 菜单优先
                if (state != ST_PLAY) {
                    lastTouchX = x; lastTouchY = y
                    handleMenuDown(g, x, y)
                    return true
                }
                val p = g.player ?: return true
                // 摇杆
                joyCX = 82 * d; joyCY = g.h - 110 * d
                if (hypot(x - joyCX, y - joyCY) < r * 1.4f) {
                    joyId = id
                    updateJoy(x, y, r)
                    return true
                }
                // 按钮
                val b = hitButton(g, x, y)
                if (b != null) { pressButton(g, b); buttonDown[id] = b; return true }
                // 其余：视角
                lookId = id
                lastTouchX = x; lastTouchY = y
                return true
            }
            1 -> { // MOVE
                if (state != ST_PLAY) { handleMenuMove(g, x, y); lastTouchX = x; lastTouchY = y; return true }
                if (id == joyId) { updateJoy(x, y, r); return true }
                if (id == lookId) {
                    val p = g.player
                    if (p != null) {
                        p.touchLookX += (x - lastTouchX) * 1.5f
                        p.touchLookY += (y - lastTouchY) * 1.5f
                    }
                    lastTouchX = x; lastTouchY = y
                    return true
                }
                return true
            }
            2, 3 -> { // UP / CANCEL
                if (state != ST_PLAY) { handleMenuUp(g, x, y); return true }
                if (id == joyId) {
                    joyId = -1; knobX = 0f; knobY = 0f
                    g.player?.touch?.let { it.mx = 0f; it.mz = 0f }
                }
                if (id == lookId) lookId = -1
                buttonDown.remove(id)?.let { releaseButton(g, it) }
                return true
            }
        }
        return true
    }

    private val buttonDown = HashMap<Int, String>()

    private fun updateJoy(x: Float, y: Float, r: Float) {
        val g = game() ?: return
        var dx = x - joyCX; var dy = y - joyCY
        val L = hypot(dx, dy)
        val m = 55 * d
        if (L > m) { dx *= m / L; dy *= m / L }
        knobX = dx; knobY = dy
        g.player?.touch?.let {
            it.mx = dx / m
            it.mz = -dy / m
        }
    }

    private fun hitButton(g: Game, x: Float, y: Float): String? {
        val w = g.w; val h = g.h
        val defs = buttonDefs(w, h)
        for (b in defs) {
            if (hypot(x - b.x, y - b.y) < b.r) return b.id
        }
        return null
    }

    private class BtnDef(val id: String, val x: Float, val y: Float, val r: Float)

    private fun buttonDefs(w: Float, h: Float): List<BtnDef> = listOf(
        BtnDef("fire", w - 105 * d, h - 170 * d, 48 * d),
        BtnDef("alt", w - 200 * d, h - 210 * d, 34 * d),
        BtnDef("jump", w - 70 * d, h - 85 * d, 32 * d),
        BtnDef("crouch", w - 145 * d, h - 65 * d, 28 * d),
        BtnDef("reload", w - 210 * d, h - 130 * d, 26 * d),
        // 原先在 (w-140d,h-150d)，与 fire 命中圆重叠（中心距 40d < fire 半径 48d），
        // 移到 reload 下方、crouch 左侧的空位
        BtnDef("switch", w - 215 * d, h - 75 * d, 26 * d),
        BtnDef("inspect", w - 265 * d, h - 130 * d, 22 * d),
        BtnDef("pause", w - 40 * d, 44 * d, 22 * d),
        BtnDef("board", w - 100 * d, 44 * d, 22 * d),
        BtnDef("loadout", w - 160 * d, 44 * d, 22 * d),
    )

    private fun pressButton(g: Game, b: String) {
        val p = g.player ?: return
        when (b) {
            "fire" -> { p.mouseL = true; p.mouseLP = true; p.touch.fire = true; p.touch.firePressed = true }
            "alt" -> { p.mouseR = true; p.mouseRP = true }
            "jump" -> p.touch.jump = true
            "crouch" -> p.touch.crouch = !p.touch.crouch
            "reload" -> p.pressed.add("KeyR")
            "switch" -> p.pressed.add("KeyQ")
            "inspect" -> p.pressed.add("KeyF")
            "pause" -> glPost { g.pause(); playClick() }
            "board" -> boardShown = true
            "loadout" -> glPost { g.toggleLoadout(); playClick() }
        }
    }

    private fun releaseButton(g: Game, b: String) {
        val p = g.player ?: return
        when (b) {
            "fire" -> { p.mouseL = false; p.touch.fire = false }
            "alt" -> p.mouseR = false
            "board" -> boardShown = false
        }
    }

    private fun playClick() {
        game()?.audio?.playUI("click")
    }

    // ---------- 菜单交互 ----------
    private fun handleMenuDown(g: Game, x: Float, y: Float) {
        for (b in btns) {
            if (x >= b.x && x <= b.x + b.w && y >= b.y && y <= b.y + b.h) {
                when {
                    b.id.startsWith("seg:") -> {
                        val parts = b.id.split(":")
                        val key = parts[1]
                        var v: Any = parts[2]
                        val n = parts[2].toIntOrNull()
                        if (n != null && (key == "size" || key == "goal")) v = n
                        g.opts[key] = v
                        g.onOption(key, v)
                        g.saveOpts()
                        playClick()
                    }
                    b.id.startsWith("slider:") -> {
                        sliderDrag = b.id.removePrefix("slider:")
                        applySlider(g, sliderDrag!!, x)
                    }
                    b.id == "start" -> { playClick(); glPost { g.startMatch() } }
                    b.id == "resume" -> { playClick(); glPost { g.resume() } }
                    b.id == "quit" -> { playClick(); glPost { g.quitToMenu() } }
                    b.id == "again" -> { playClick(); glPost { g.startMatch() } }
                    b.id == "menu" -> { playClick(); glPost { g.quitToMenu() } }
                    b.id.startsWith("load:") -> { playClick(); val id = b.id.removePrefix("load:"); glPost { g.chooseLoadout(id) } }
                    b.id == "loadclose" -> { playClick(); g.closeLoadout() }
                }
                return
            }
        }
    }

    private fun handleMenuMove(g: Game, x: Float, y: Float) {
        val k = sliderDrag ?: return
        applySlider(g, k, x)
    }

    private fun handleMenuUp(g: Game, x: Float, y: Float) {
        sliderDrag = null
    }

    private fun applySlider(g: Game, key: String, x: Float) {
        val s = sliderRect(g, key) ?: return
        val t = ((x - s.first) / s.second).coerceIn(0f, 1f)
        val v = when (key) {
            "sens" -> 0.2f + t * 2.8f
            "fov" -> (65f + t * 35f).toInt().toFloat()
            "vol" -> t
            else -> return
        }
        g.opts[key] = if (key == "fov") v.toInt() else Math.round(v * 20f) / 20f
        g.onOption(key, g.opts[key] as Any)
        g.saveOpts()
    }

    private fun sliderRect(g: Game, key: String): Pair<Float, Float>? {
        for (b in btns) if (b.id == "slider:$key") return Pair(b.x, b.w)
        return null
    }

    // ---------- 绘制 ----------
    fun draw(c: Canvas, w: Float, h: Float) {
        btns.clear()
        when (state) {
            ST_LOADING -> drawLoading(c, w, h)
            ST_MENU -> drawMenu(c, w, h)
            ST_PLAY -> drawHud(c, w, h)
            ST_PAUSE -> { drawHudDim(c, w, h); drawPause(c, w, h) }
            ST_LOADOUT -> { drawHudDim(c, w, h); drawLoadout(c, w, h) }
            ST_END -> drawEnd(c, w, h)
        }
        if (toastT > 0 && state != ST_MENU && state != ST_LOADING) drawToast(c, w, h)
    }

    private val pFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val pText = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pBold = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
    }

    private fun rr(c: Canvas, x: Float, y: Float, w: Float, h: Float, r: Int, argb: Int) {
        pFill.color = argb
        c.drawRoundRect(RectF(x, y, x + w, y + h), r * d, r * d, pFill)
    }

    private fun txt(c: Canvas, s: String, x: Float, y: Float, size: Float, argb: Int, bold: Boolean = false, align: Paint.Align = Paint.Align.LEFT) {
        val p = if (bold) pBold else pText
        p.color = argb
        p.textSize = size * d
        p.textAlign = align
        c.drawText(s, x, y, p)
    }

    // ---------- 加载 ----------
    private fun drawLoading(c: Canvas, w: Float, h: Float) {
        c.drawColor(Color.argb(255, 16, 20, 24))
        txt(c, "运 输 船", w / 2, h * 0.36f, 46f, Color.argb(255, 245, 179, 33), true, Paint.Align.CENTER)
        txt(c, loadText, w / 2, h * 0.36f + 40 * d, 14f, Color.argb(200, 180, 188, 196), false, Paint.Align.CENTER)
        val bw = 260 * d
        rr(c, w / 2 - bw / 2, h * 0.55f, bw, 8 * d, 4, Color.argb(60, 255, 255, 255))
        if (loadProgress > 0.01f) rr(c, w / 2 - bw / 2, h * 0.55f, bw * loadProgress.coerceIn(0f, 1f), 8 * d, 4, Color.argb(255, 245, 179, 33))
        txt(c, "小提示：蹲下再跳（蹲跳）可以跳得更高，踩着木箱就能爬上对面集装箱的二楼。",
            w / 2, h * 0.72f, 12f, Color.argb(160, 170, 178, 186), false, Paint.Align.CENTER)
    }

    // ---------- 主菜单 ----------
    private fun drawMenu(c: Canvas, w: Float, h: Float) {
        val g = game() ?: return
        c.drawColor(Color.argb(215, 10, 14, 18))
        // 左侧标题区
        txt(c, "CROSSFIRE · 团队竞技", 26 * d, 34 * d, 13f, Color.argb(255, 245, 179, 33), true)
        txt(c, "运输船", 26 * d, 84 * d, 44f, Color.argb(255, 235, 238, 240), true)
        txt(c, "TRANSPORT SHIP", 26 * d, 108 * d, 13f, Color.argb(150, 150, 158, 166))
        val desc = "保卫者（Global Risk）奉命登船突击检查，却遭到潜伏者（Black List）伏击。"
        txt(c, desc, 26 * d, 134 * d, 11f, Color.argb(170, 170, 178, 186))
        val keys = listOf(
            "左侧摇杆移动 · 右侧滑动视角",
            "开火 / 开镜 · 跳 · 蹲 · 换弹 · 切枪",
            "Tab 计分板 · Esc 暂停 / 设置",
        )
        var ky = 168 * d
        for (k in keys) { txt(c, k, 26 * d, ky, 11f, Color.argb(140, 150, 158, 166)); ky += 20 * d }
        // 右侧选项面板
        val pw = min(360 * d, w * 0.46f)
        val px = w - pw - 24 * d
        rr(c, px, 24 * d, pw, h - 48 * d, 12, Color.argb(235, 18, 26, 34))
        // 灵敏度/FOV/音量在暂停菜单调整；主菜单只留核心选项，避免小屏溢出
        var y = 34 * d
        y = seg(c, g, px + 16 * d, y, pw - 32 * d, "阵营", "team", listOf(Pair("BL", "潜伏者"), Pair("GR", "保卫者")))
        y = seg(c, g, px + 16 * d, y, pw - 32 * d, "主武器", "primary", listOf(Pair("ak47", "AK-47"), Pair("m4a1", "M4A1"), Pair("awm", "AWM"), Pair("mp5", "MP5")))
        y = seg(c, g, px + 16 * d, y, pw - 32 * d, "对战规模", "size", listOf(Pair("4", "4v4"), Pair("6", "6v6"), Pair("8", "8v8")))
        y = seg(c, g, px + 16 * d, y, pw - 32 * d, "目标击杀", "goal", listOf(Pair("30", "30"), Pair("50", "50"), Pair("100", "100")))
        y = seg(c, g, px + 16 * d, y, pw - 32 * d, "电脑难度", "diff", listOf(Pair("easy", "简单"), Pair("normal", "普通"), Pair("hard", "困难"), Pair("hell", "地狱")))
        y = seg(c, g, px + 16 * d, y, pw - 32 * d, "时间", "tod", listOf(Pair("day", "白天"), Pair("dusk", "黄昏")))
        y = seg(c, g, px + 16 * d, y, pw - 32 * d, "画质", "quality", listOf(Pair("low", "流畅"), Pair("medium", "均衡"), Pair("high", "极致")))
        // 开始按钮：跟随内容流式布局，保证与任何行不重叠
        val by = y + 6 * d
        rr(c, px + 16 * d, by, pw - 32 * d, 40 * d, 10, Color.argb(255, 245, 179, 33))
        txt(c, "开 始 游 戏", px + pw / 2, by + 27 * d, 16f, Color.argb(255, 20, 24, 28), true, Paint.Align.CENTER)
        btns.add(Btn("start", px + 16 * d, by, pw - 32 * d, 40 * d))
    }

    private fun seg(c: Canvas, g: Game, x: Float, y: Float, w: Float, label: String, key: String, opts: List<Pair<String, String>>): Float {
        txt(c, label, x, y + 12 * d, 12f, Color.argb(200, 190, 198, 206))
        val bw = (w - (opts.size - 1) * 6 * d) / opts.size
        val cur = g.opts[key]?.toString()
        for ((i, o) in opts.withIndex()) {
            val bx = x + i * (bw + 6 * d)
            val on = cur == o.first
            rr(c, bx, y + 14 * d, bw, 22 * d, 8, if (on) Color.argb(255, 245, 179, 33) else Color.argb(50, 255, 255, 255))
            txt(c, o.second, bx + bw / 2, y + 30 * d, 11f, if (on) Color.argb(255, 24, 28, 32) else Color.argb(220, 210, 216, 222), on, Paint.Align.CENTER)
            btns.add(Btn("seg:$key:${o.first}", bx, y + 14 * d, bw, 22 * d))
        }
        return y + 38 * d
    }

    private fun slider(c: Canvas, g: Game, x: Float, y: Float, w: Float, key: String, label: String): Float {
        txt(c, label, x, y + 11 * d, 12f, Color.argb(200, 190, 198, 206))
        val v = when (key) {
            "sens" -> (g.opts["sens"] as? Float) ?: 1f
            "fov" -> ((g.opts["fov"] as? Int) ?: 78).toFloat()
            else -> (g.opts["vol"] as? Float) ?: 0.8f
        }
        val t = when (key) {
            "sens" -> (v - 0.2f) / 2.8f
            "fov" -> (v - 65f) / 35f
            else -> v
        }
        rr(c, x, y + 20 * d, w, 5 * d, 2, Color.argb(50, 255, 255, 255))
        rr(c, x, y + 20 * d, w * t.coerceIn(0f, 1f), 5 * d, 2, Color.argb(255, 245, 179, 33))
        c.drawCircle(x + w * t.coerceIn(0f, 1f), y + 22.5f * d, 8 * d, pFill.apply { color = Color.argb(255, 235, 238, 240) })
        val disp = when (key) {
            "sens" -> String.format("%.2f", v)
            "fov" -> "${v.toInt()}"
            else -> String.format("%.0f%%", v * 100)
        }
        txt(c, disp, x + w, y + 12 * d, 12f, Color.argb(220, 245, 179, 33), false, Paint.Align.RIGHT)
        btns.add(Btn("slider:$key", x - 10 * d, y + 2 * d, w + 20 * d, 30 * d))
        return y + 36 * d
    }

    // ---------- 局内 HUD ----------
    private fun drawHud(c: Canvas, w: Float, h: Float) {
        val g = game() ?: return
        val p = g.player ?: return
        // 比分条
        val sw = 330 * d
        val sx = (w - sw) / 2
        rr(c, sx, 10 * d, sw, 40 * d, 10, Color.argb(150, 10, 14, 18))
        val myBL = p.team == "BL"
        val blC = if (myBL) Color.argb(255, 255, 155, 112) else Color.argb(220, 255, 155, 112)
        val grC = if (!myBL) Color.argb(255, 140, 200, 255) else Color.argb(220, 140, 200, 255)
        txt(c, "潜伏者 ${g.score["BL"] ?: 0}", sx + 16 * d, 32 * d, 15f, blC, true)
        txt(c, "${g.score["GR"] ?: 0} 保卫者", sx + sw - 16 * d, 32 * d, 15f, grC, true, Paint.Align.RIGHT)
        val tl = (g.timeLeft.coerceAtLeast(0f)).toInt()
        txt(c, String.format("%d:%02d", tl / 60, tl % 60), sx + sw / 2, 30 * d, 16f, Color.argb(255, 235, 238, 240), true, Paint.Align.CENTER)
        txt(c, "目标 ${g.goal}", sx + sw / 2, 44 * d, 10f, Color.argb(180, 200, 205, 210), false, Paint.Align.CENTER)
        // 雷达
        radarBmp?.let { bmp ->
            val rw = 150 * d; val rh = 74 * d
            val rx = 12 * d; val ry = 12 * d
            rr(c, rx, ry, rw, rh, 6, Color.argb(120, 10, 14, 18))
            c.save()
            val clip = RectF(rx, ry, rx + rw, ry + rh)
            c.clipRect(clip)
            val zoom = 0.55f * (rw / (294 * d))
            val S = 8f * d
            c.translate(rx + rw / 2, ry + rh / 2)
            c.rotate(Math.toDegrees(p.yaw.toDouble()).toFloat())
            c.scale(zoom, zoom)
            c.translate(-(p.pos.x + 37) * S, -(p.pos.z + 13) * S)
            c.drawBitmap(bmp, 0f, 0f, null)
            synchronized(g.actors) {
                for (a in g.actors) {
                    if (a === p) continue
                    val seen = a.team == p.team || a.radarT > 0
                    if (!seen) continue
                    val ax = (a.pos.x + 37) * S
                    val az = (a.pos.z + 13) * S
                    if (!a.alive) {
                        if (a.team != p.team || a.deadT > 5) continue
                        pStroke.color = Color.argb(200, 150, 160, 168)
                        pStroke.strokeWidth = 2.5f * d
                        c.drawLine(ax - 5, az - 5, ax + 5, az + 5, pStroke)
                        c.drawLine(ax + 5, az - 5, ax - 5, az + 5, pStroke)
                        continue
                    }
                    if (a.team == p.team) {
                        c.save()
                        c.translate(ax, az); c.rotate(Math.toDegrees(-a.yaw.toDouble()).toFloat())
                        pFill.color = Color.argb(255, 79, 176, 255)
                        val tri = android.graphics.Path()
                        tri.moveTo(0f, -7f); tri.lineTo(5f, 5f); tri.lineTo(0f, 2.5f); tri.lineTo(-5f, 5f); tri.close()
                        c.drawPath(tri, pFill)
                        c.restore()
                    } else {
                        pFill.color = Color.argb((a.radarT.coerceIn(0f, 1f) * 255).toInt(), 255, 74, 58)
                        c.drawCircle(ax, az, 5 * d, pFill)
                    }
                }
            }
            c.restore()
            // 自己
            pFill.color = Color.argb(255, 255, 210, 74)
            val tri = android.graphics.Path()
            val cx = rx + rw / 2; val cy = ry + rh / 2
            tri.moveTo(cx, cy - 6 * d); tri.lineTo(cx + 4.5f * d, cy + 5 * d); tri.lineTo(cx, cy + 2.5f * d); tri.lineTo(cx - 4.5f * d, cy + 5 * d); tri.close()
            c.drawPath(tri, pFill)
            txt(c, "运输船 · $fps FPS", rx, ry + rh + 14 * d, 10f, Color.argb(170, 190, 198, 206))
        }
        // 生命护甲
        val vx = 12 * d; val vy = h - 64 * d
        rr(c, vx, vy, 84 * d, 44 * d, 8, if (p.hp <= 30 && p.alive) Color.argb(200, 120, 20, 16) else Color.argb(150, 10, 14, 18))
        txt(c, "✚", vx + 12 * d, vy + 28 * d, 16f, Color.argb(255, 120, 220, 130))
        txt(c, "${kotlin.math.ceil(kotlin.math.max(0f, p.hp)).toInt()}", vx + 74 * d, vy + 30 * d, 19f, Color.argb(255, 235, 238, 240), true, Paint.Align.RIGHT)
        rr(c, vx + 92 * d, vy, 84 * d, 44 * d, 8, Color.argb(150, 10, 14, 18))
        txt(c, "⛨", vx + 104 * d, vy + 28 * d, 16f, Color.argb(255, 140, 180, 255))
        txt(c, "${kotlin.math.ceil(kotlin.math.max(0f, p.armor)).toInt()}", vx + 166 * d, vy + 30 * d, 19f, Color.argb(255, 235, 238, 240), true, Paint.Align.RIGHT)
        // 武器槽
        var slx = vx
        val sly = vy - 52 * d
        for (i in p.inv.indices) {
            val wst = p.inv[i]
            val on = i == p.slot
            rr(c, slx, sly, 88 * d, 44 * d, 8, if (on) Color.argb(200, 245, 179, 33) else Color.argb(110, 10, 14, 18))
            txt(c, wst.def.name, slx + 8 * d, sly + 20 * d, 11f, if (on) Color.argb(255, 24, 28, 32) else Color.argb(210, 210, 216, 222), on)
            txt(c, "${i + 1}", slx + 80 * d, sly + 36 * d, 10f, if (on) Color.argb(200, 24, 28, 32) else Color.argb(150, 190, 198, 206), false, Paint.Align.RIGHT)
            slx += 94 * d
        }
        // 弹药
        val wst = p.weapon
        val ax = w - 16 * d
        txt(c, wst.def.hudName, ax, h - 96 * d, 13f, Color.argb(220, 235, 238, 240), false, Paint.Align.RIGHT)
        txt(c, "${wst.mag}", ax, h - 54 * d, 34f, if (wst.mag <= kotlin.math.ceil(wst.def.mag * 0.2f) && wst.def.mag > 1) Color.argb(255, 255, 90, 60) else Color.argb(255, 245, 248, 250), true, Paint.Align.RIGHT)
        if (wst.def.type != "melee" && wst.def.type != "grenade") {
            txt(c, "/ ${wst.reserve}", ax, h - 36 * d, 13f, Color.argb(170, 190, 198, 206), false, Paint.Align.RIGHT)
        }
        val hint = when {
            wst.reloading -> "换弹中…"
            wst.def.mag > 1 && wst.mag == 0 && wst.reserve == 0 -> "弹药耗尽"
            wst.def.mag > 1 && wst.mag == 0 -> "换弹（R）"
            else -> ""
        }
        if (hint.isNotEmpty()) txt(c, hint, ax, h - 118 * d, 12f, Color.argb(220, 255, 179, 33), false, Paint.Align.RIGHT)
        // 准星
        val scopedNow = p.scoped > 0 && wst.def.type == "sniper"
        if (p.alive && !scopedNow && wst.def.type != "sniper") {
            val cx = w / 2; val cy = h / 2
            val gap = 4 * d + spreadPx
            val len = 9 * d
            pStroke.color = Color.argb(235, 255, 255, 255)
            pStroke.strokeWidth = 2 * d
            c.drawLine(cx, cy - gap - len, cx, cy - gap, pStroke)
            c.drawLine(cx, cy + gap, cx, cy + gap + len, pStroke)
            c.drawLine(cx - gap - len, cy, cx - gap, cy, pStroke)
            c.drawLine(cx + gap, cy, cx + gap + len, cy, pStroke)
            pFill.color = Color.argb(255, 255, 48, 32)
            c.drawCircle(cx, cy, 2 * d, pFill)
        }
        // 狙击镜
        if (scopedNow && p.alive) drawScope(c, w, h)
        // 命中标记
        if (hitT > 0) {
            val a = (hitT * 5).coerceIn(0f, 1f)
            val cx = w / 2; val cy = h / 2
            pStroke.color = when (hitKind) {
                2 -> Color.argb((a * 255).toInt(), 255, 90, 60)
                1 -> Color.argb((a * 255).toInt(), 255, 210, 74)
                else -> Color.argb((a * 255).toInt(), 255, 255, 255)
            }
            pStroke.strokeWidth = 2.4f * d
            val g2 = 6 * d; val l2 = 7 * d
            for (sgn in arrayOf(floatArrayOf(1f, 1f), floatArrayOf(-1f, 1f), floatArrayOf(1f, -1f), floatArrayOf(-1f, -1f))) {
                c.drawLine(cx + sgn[0] * g2, cy + sgn[1] * g2, cx + sgn[0] * (g2 + l2), cy + sgn[1] * (g2 + l2), pStroke)
            }
        }
        // 受击方向
        synchronized(dmgDirs) {
            for (m in dmgDirs) {
                val rel = m.ang - p.yaw
                val cx = w / 2; val cy = h / 2
                c.save()
                c.translate(cx, cy)
                c.rotate(Math.toDegrees(-rel.toDouble()).toFloat())
                pStroke.color = Color.argb((m.t.coerceIn(0f, 1f) * 220).toInt(), 255, 70, 50)
                pStroke.strokeWidth = 5 * d
                val r0 = 64 * d
                val arc = RectF(-r0, -r0, r0, r0)
                c.drawArc(arc, -30f, 60f, false, pStroke)
                c.restore()
            }
        }
        // 击杀信息
        var fy = 66 * d
        synchronized(feed) {
            for (f in feed) {
                txt(c, f.text, w - 16 * d, fy, 12f, f.color, false, Paint.Align.RIGHT)
                fy += 19 * d
            }
        }
        // 连杀徽章
        badge?.let { b ->
            val a = (b.t * 2).coerceIn(0f, 1f)
            val col = if (b.hs) Color.argb((a * 255).toInt(), 220, 60, 40) else Color.argb((a * 255).toInt(), 220, 150, 30)
            txt(c, b.text, w / 2, h * 0.26f, 26f, col, true, Paint.Align.CENTER)
            if (b.sub.isNotEmpty()) txt(c, b.sub, w / 2, h * 0.26f + 24 * d, 13f, Color.argb((a * 230).toInt(), 235, 238, 240), false, Paint.Align.CENTER)
        }
        // 中央死亡信息
        if (!p.alive && p.respawnT > 0) {
            val kb = g.killedBy.ifEmpty { "你阵亡了" }
            txt(c, kb, w / 2, h * 0.4f, 20f, Color.argb(255, 240, 242, 244), true, Paint.Align.CENTER)
            txt(c, String.format("%.1f 秒后复活 · 可更换武器", p.respawnT), w / 2, h * 0.4f + 26 * d, 13f, Color.argb(210, 200, 206, 212), false, Paint.Align.CENTER)
        }
        // 出生保护
        if (p.protectT > 0 && p.alive) {
            txt(c, String.format("出生保护 %.1fs（开火即解除）", p.protectT), w / 2, 66 * d, 12f, Color.argb(220, 140, 190, 255), false, Paint.Align.CENTER)
        }
        // 准星下角色名
        if (aimName.isNotEmpty()) {
            val col = if (aimTeam == "BL") Color.argb(255, 255, 155, 112) else Color.argb(255, 140, 200, 255)
            txt(c, aimName, w / 2, h / 2 + 34 * d, 13f, col, false, Paint.Align.CENTER)
        }
        // 队友头顶名牌（Game 每帧发布的屏幕坐标快照）
        if (p.alive) {
            for (nt in g.nameTagsSnapshot) {
                if (!nt.visible) continue
                if (nt.x < 0 || nt.x > w || nt.y < 0 || nt.y > h) continue
                txt(c, nt.name, nt.x, nt.y, 12f, nt.color, false, Paint.Align.CENTER)
            }
        }
        // 计分板
        if (boardShown) drawBoard(c, w, h)
        // 触屏按钮
        drawButtons(c, w, h)
        drawJoy(c, h)
    }

    private fun drawHudDim(c: Canvas, w: Float, h: Float) {
        val g = game() ?: return
        drawHud(c, w, h)
        pFill.color = Color.argb(140, 6, 10, 14)
        c.drawRect(0f, 0f, w, h, pFill)
    }

    private fun drawScope(c: Canvas, w: Float, h: Float) {
        pFill.color = Color.argb(255, 4, 6, 8)
        val r = min(w, h) * 0.42f
        val cx = w / 2; val cy = h / 2
        // 全屏黑 + 中间圆洞
        val path = android.graphics.Path()
        path.addRect(0f, 0f, w, h, android.graphics.Path.Direction.CW)
        path.addCircle(cx, cy, r, android.graphics.Path.Direction.CW)
        path.fillType = android.graphics.Path.FillType.EVEN_ODD
        c.drawPath(path, pFill)
        pStroke.color = Color.argb(255, 10, 12, 14)
        pStroke.strokeWidth = 3 * d
        c.drawCircle(cx, cy, r, pStroke)
        pStroke.color = Color.argb(200, 16, 18, 20)
        pStroke.strokeWidth = 1.2f * d
        c.drawLine(0f, cy, w, cy, pStroke)
        c.drawLine(cx, 0f, cx, h, pStroke)
        // 刻度
        for (i in -4..4) {
            if (i == 0) continue
            val yy = cy + i * r / 5
            c.drawLine(cx - 8 * d, yy, cx + 8 * d, yy, pStroke)
        }
    }

    private fun drawButtons(c: Canvas, w: Float, h: Float) {
        val g = game() ?: return
        val p2 = g.player
        val defs = buttonDefs(w, h)
        val labels = mapOf(
            "fire" to "开火", "alt" to "镜", "jump" to "跳", "crouch" to "蹲",
            "reload" to "换弹", "switch" to "切", "inspect" to "检视",
            "pause" to "Ⅱ", "board" to "Tab", "loadout" to "B",
        )
        for (b in defs) {
            val down = buttonDown.containsValue(b.id)
            val big = b.id == "fire"
            pFill.color = when {
                down -> Color.argb(120, 255, 210, 90)
                big -> Color.argb(60, 255, 255, 255)
                else -> Color.argb(42, 255, 255, 255)
            }
            c.drawCircle(b.x, b.y, b.r, pFill)
            pStroke.color = Color.argb(90, 255, 255, 255)
            pStroke.strokeWidth = 1.5f * d
            c.drawCircle(b.x, b.y, b.r, pStroke)
            txt(c, labels[b.id] ?: b.id, b.x, b.y + b.r * 0.34f, if (big) 14f else 10f, Color.argb(235, 255, 255, 255), big, Paint.Align.CENTER)
        }
        if (p2?.touch?.crouch == true) {
            for (b in defs) if (b.id == "crouch") {
                pFill.color = Color.argb(130, 120, 190, 255)
                c.drawCircle(b.x, b.y, b.r, pFill)
            }
        }
    }


    private fun drawJoy(c: Canvas, h: Float) {
        val g = game() ?: return
        joyCX = 82 * d; joyCY = g.h - 110 * d
        pFill.color = Color.argb(36, 255, 255, 255)
        c.drawCircle(joyCX, joyCY, 62 * d, pFill)
        pStroke.color = Color.argb(80, 255, 255, 255)
        pStroke.strokeWidth = 1.5f * d
        c.drawCircle(joyCX, joyCY, 62 * d, pStroke)
        pFill.color = Color.argb(70, 255, 255, 255)
        c.drawCircle(joyCX + knobX, joyCY + knobY, 26 * d, pFill)
    }

    // ---------- 计分板 ----------
    private fun drawBoard(c: Canvas, w: Float, h: Float) {
        val g = game() ?: return
        val bw = min(640 * d, w * 0.9f)
        val bh = min(380 * d, h * 0.7f)
        val bx = (w - bw) / 2
        val by = (h - bh) / 2
        rr(c, bx, by, bw, bh, 12, Color.argb(235, 12, 18, 24))
        txt(c, "运输船 · 团队竞技", bx + bw / 2, by + 30 * d, 15f, Color.argb(255, 245, 179, 33), true, Paint.Align.CENTER)
        val colW = bw / 2 - 20 * d
        drawTeamBoard(c, g, "BL", bx + 16 * d, by + 48 * d, colW)
        drawTeamBoard(c, g, "GR", bx + bw / 2 + 4 * d, by + 48 * d, colW)
    }

    private fun drawTeamBoard(c: Canvas, g: Game, team: String, x: Float, y: Float, w: Float) {
        val name = if (team == "BL") "潜伏者" else "保卫者"
        val col = if (team == "BL") Color.argb(255, 255, 155, 112) else Color.argb(255, 140, 200, 255)
        txt(c, "$name · ${g.score[team] ?: 0}", x + w / 2, y + 12 * d, 13f, col, true, Paint.Align.CENTER)
        txt(c, "击杀  死亡  爆头  延迟", x + w - 8 * d, y + 32 * d, 10f, Color.argb(160, 170, 178, 186), false, Paint.Align.RIGHT)
        var yy = y + 50 * d
        val rows = synchronized(g.actors) { g.actors.filter { it.team == team }.sortedByDescending { it.stats.k } }
        val me = g.player
        for (a in rows) {
            val mine = a === me
            if (mine) rr(c, x, yy - 13 * d, w, 19 * d, 4, Color.argb(60, 245, 179, 33))
            txt(c, a.name, x + 8 * d, yy, 11f, if (a.alive) Color.argb(240, 220, 226, 232) else Color.argb(130, 150, 156, 162), mine)
            val s = String.format("%d      %d      %d      %d", a.stats.k, a.stats.d, a.stats.hs, a.ping)
            txt(c, s, x + w - 8 * d, yy, 11f, Color.argb(220, 200, 206, 212), false, Paint.Align.RIGHT)
            yy += 21 * d
        }
    }

    // ---------- 暂停 ----------
    private fun drawPause(c: Canvas, w: Float, h: Float) {
        val g = game() ?: return
        val pw = min(340 * d, w * 0.5f)
        val px = (w - pw) / 2
        // 流式布局：面板高度按内容计算并垂直居中；
        // 极小屏放不下时丢弃「时间」行（主菜单仍可调），杜绝按钮重叠
        val avail = h - 24 * d
        var need = 50 * d + 3 * 36 * d + 38 * d + 12 * d + 40 * d + 20 * d
        val showTod = need <= avail
        if (!showTod) need -= 38 * d
        val ph = min(need, avail)
        val py = ((h - ph) / 2).coerceAtLeast(12 * d)
        rr(c, px, py, pw, ph, 12, Color.argb(240, 14, 20, 26))
        txt(c, "暂停", px + pw / 2, py + 36 * d, 22f, Color.argb(255, 235, 238, 240), true, Paint.Align.CENTER)
        var y = py + 50 * d
        y = slider(c, g, px + 20 * d, y, pw - 40 * d, "sens", "灵敏度")
        y = slider(c, g, px + 20 * d, y, pw - 40 * d, "fov", "视野 FOV")
        y = slider(c, g, px + 20 * d, y, pw - 40 * d, "vol", "音量")
        if (showTod) y = seg(c, g, px + 20 * d, y, pw - 40 * d, "时间", "tod", listOf(Pair("day", "白天"), Pair("dusk", "黄昏")))
        // 按钮跟随内容流式排列，左右并排
        val by = y + 10 * d
        val bwAll = pw - 40 * d
        val rw = bwAll * 0.56f
        val qw = bwAll - rw - 8 * d
        rr(c, px + 20 * d, by, rw, 40 * d, 10, Color.argb(255, 245, 179, 33))
        txt(c, "继 续", px + 20 * d + rw / 2, by + 26 * d, 16f, Color.argb(255, 20, 24, 28), true, Paint.Align.CENTER)
        btns.add(Btn("resume", px + 20 * d, by, rw, 40 * d))
        rr(c, px + 20 * d + rw + 8 * d, by, qw, 40 * d, 10, Color.argb(70, 255, 255, 255))
        txt(c, "退出", px + 20 * d + rw + 8 * d + qw / 2, by + 26 * d, 14f, Color.argb(240, 220, 226, 232), false, Paint.Align.CENTER)
        btns.add(Btn("quit", px + 20 * d + rw + 8 * d, by, qw, 40 * d))
    }

    // ---------- 换装 ----------
    private fun drawLoadout(c: Canvas, w: Float, h: Float) {
        val g = game() ?: return
        val cards = listOf(
            Triple("ak47", "AK-47", "潜伏者经典 · 伤害高"),
            Triple("m4a1", "M4A1", "保卫者经典 · 稳定"),
            Triple("awm", "AWM", "一枪致命 · 需开镜"),
            Triple("mp5", "MP5", "射速快 · 移动灵活"),
        )
        val pw = min(520 * d, w * 0.86f)
        val px = (w - pw) / 2
        val py = h * 0.16f
        val ph = h * 0.68f
        rr(c, px, py, pw, ph, 12, Color.argb(240, 14, 20, 26))
        txt(c, "更换主武器", px + pw / 2, py + 36 * d, 20f, Color.argb(255, 235, 238, 240), true, Paint.Align.CENTER)
        txt(c, "复活时生效；在出生点内立即生效。副武器沙漠之鹰、军刀、手雷自动配备。", px + pw / 2, py + 58 * d, 11f, Color.argb(180, 180, 188, 196), false, Paint.Align.CENTER)
        val cw = (pw - 48 * d - 3 * 10 * d) / 4
        val cur = g.player?.nextPrimary ?: g.opts["primary"]?.toString() ?: "ak47"
        var cx = px + 24 * d
        for (card in cards) {
            val on = card.first == cur
            rr(c, cx, py + 76 * d, cw, 92 * d, 10, if (on) Color.argb(255, 245, 179, 33) else Color.argb(60, 255, 255, 255))
            txt(c, card.second, cx + cw / 2, py + 112 * d, 14f, if (on) Color.argb(255, 24, 28, 32) else Color.argb(240, 235, 238, 240), true, Paint.Align.CENTER)
            txt(c, card.third, cx + cw / 2, py + 134 * d, 9f, if (on) Color.argb(220, 40, 46, 52) else Color.argb(170, 170, 178, 186), false, Paint.Align.CENTER)
            btns.add(Btn("load:${card.first}", cx, py + 76 * d, cw, 92 * d))
            cx += cw + 10 * d
        }
        rr(c, px + pw / 2 - 80 * d, py + ph - 56 * d, 160 * d, 38 * d, 10, Color.argb(70, 255, 255, 255))
        txt(c, "确 定", px + pw / 2, py + ph - 31 * d, 14f, Color.argb(240, 235, 238, 240), false, Paint.Align.CENTER)
        btns.add(Btn("loadclose", px + pw / 2 - 80 * d, py + ph - 56 * d, 160 * d, 38 * d))
    }

    // ---------- 结算 ----------
    private fun drawEnd(c: Canvas, w: Float, h: Float) {
        val g = game() ?: return
        c.drawColor(Color.argb(230, 10, 14, 18))
        val p = g.player ?: return
        val my = p.team
        val other = if (my == "BL") "GR" else "BL"
        val win = when {
            g.score[my] == g.score[other] -> null
            else -> (g.score[my] ?: 0) > (g.score[other] ?: 0)
        }
        val resText = when (win) { null -> "平局"; true -> "胜利"; else -> "失败" }
        val resCol = when (win) { null -> Color.argb(255, 220, 226, 232); true -> Color.argb(255, 110, 220, 130); else -> Color.argb(255, 240, 80, 60) }
        txt(c, resText, w / 2, h * 0.2f, 40f, resCol, true, Paint.Align.CENTER)
        txt(c, "潜伏者 ${g.score["BL"] ?: 0} : ${g.score["GR"] ?: 0} 保卫者", w / 2, h * 0.2f + 34 * d, 15f, Color.argb(255, 235, 238, 240), false, Paint.Align.CENTER)
        // MVP
        val mvp = g.actors.maxByOrNull { it.stats.k * 2 - it.stats.d + it.stats.hs }
        mvp?.let { txt(c, "MVP：${it.name}（${it.stats.k} 杀 / ${it.stats.hs} 爆头）", w / 2, h * 0.2f + 58 * d, 12f, Color.argb(220, 245, 179, 33), false, Paint.Align.CENTER) }
        val me = p
        val acc = if (me.stats.shots > 0) String.format("%.1f", me.stats.hits.toFloat() / me.stats.shots * 100) else "0"
        txt(c, "你的战绩：${me.stats.k} 击杀 · ${me.stats.d} 死亡 · ${me.stats.hs} 爆头 · 命中率 $acc%", w / 2, h * 0.2f + 78 * d, 12f, Color.argb(220, 200, 206, 212), false, Paint.Align.CENTER)
        // 表
        val bw = min(560 * d, w * 0.9f)
        val bx = (w - bw) / 2
        drawTeamBoard(c, g, "BL", bx, h * 0.34f, bw / 2 - 10 * d)
        drawTeamBoard(c, g, "GR", bx + bw / 2 + 10 * d, h * 0.34f, bw / 2 - 10 * d)
        // 按钮
        val by = h - 74 * d
        rr(c, w / 2 - 150 * d, by, 140 * d, 44 * d, 10, Color.argb(255, 245, 179, 33))
        txt(c, "再 来 一 局", w / 2 - 80 * d, by + 28 * d, 15f, Color.argb(255, 20, 24, 28), true, Paint.Align.CENTER)
        btns.add(Btn("again", w / 2 - 150 * d, by, 140 * d, 44 * d))
        rr(c, w / 2 + 10 * d, by, 140 * d, 44 * d, 10, Color.argb(70, 255, 255, 255))
        txt(c, "主菜单", w / 2 + 80 * d, by + 28 * d, 15f, Color.argb(240, 235, 238, 240), false, Paint.Align.CENTER)
        btns.add(Btn("menu", w / 2 + 10 * d, by, 140 * d, 44 * d))
    }

    private fun drawToast(c: Canvas, w: Float, h: Float) {
        val a = (toastT * 2).coerceIn(0f, 1f)
        val t = toastText
        pText.textSize = 13 * d
        val tw = pText.measureText(t)
        val bw = tw + 32 * d
        rr(c, w / 2 - bw / 2, h * 0.18f, bw, 34 * d, 8, Color.argb((a * 200).toInt(), 12, 18, 24))
        txt(c, t, w / 2, h * 0.18f + 22 * d, 13f, Color.argb((a * 240).toInt(), 235, 238, 240), false, Paint.Align.CENTER)
    }
}
