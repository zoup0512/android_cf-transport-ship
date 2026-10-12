// GL 视图 + HUD 覆盖层
package com.cf.transportship.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.opengl.GLSurfaceView
import android.view.MotionEvent
import android.view.View
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import com.cf.transportship.game.Game

class GameView(context: Context, val game: Game) : GLSurfaceView(context) {
    init {
        setEGLContextClientVersion(3)
        setEGLConfigChooser(8, 8, 8, 8, 24, 0)
        setRenderer(GameRenderer(game, this))
        renderMode = RENDERMODE_CONTINUOUSLY
        preserveEGLContextOnPause = true
    }
}

class GameRenderer(private val game: Game, private val view: GameView) : GLSurfaceView.Renderer {
    private var built = false

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        game.w = width.toFloat()
        game.h = height.toFloat()
        if (built) {
            game.renderer.resize(width, height)
            return
        }
        built = true
        val hud = game.hud
        try {
            game.buildAssets { p, t ->
                android.util.Log.i("CF", "load $p $t")
                view.post {
                    hud?.loadProgress = p
                    hud?.loadText = t
                    (hud?.host as? View)?.invalidate()
                }
            }
            // startMatch 改动 actors/inv 等大量状态，必须与渲染循环同线程（GL 线程）
            view.post { hud?.state = ST_MENU }
            view.queueEvent { if (game.autoStart) game.startMatch() }
        } catch (e: Throwable) {
            android.util.Log.e("CF", "build failed", e)
            view.post {
                hud?.loadText = "加载失败：" + (e.message ?: e.toString())
                (hud?.host as? View)?.invalidate()
            }
        }
    }

    override fun onDrawFrame(gl: GL10?) {
        val now = android.os.SystemClock.uptimeMillis()
        try {
            game.frameUpdate(now)
            errCount = 0
        } catch (e: Throwable) {
            android.util.Log.e("CF", "frame error", e)
            if (errCount++ > 60) throw e
        }
        // HUD 帧驱动
        val hud = game.hud
        val hv = hud?.host as? View
        if (hv != null) hv.postInvalidateOnAnimation()
    }

    private var errCount = 0
}

/** HUD 覆盖层：接收全部触摸并绘制 HUD/菜单 */
@SuppressLint("ViewConstructor")
class HudOverlay(context: Context, val game: Game) : View(context) {
    init {
        game.hud = Hud({ game }, resources.displayMetrics.density)
        game.hud?.host = this
        game.hud?.state = ST_LOADING
    }

    override fun onDraw(c: Canvas) {
        game.hud?.draw(c, width.toFloat(), height.toFloat())
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val hud = game.hud ?: return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = e.actionIndex
                hud.onTouch(0, e.getX(i), e.getY(i), e.getPointerId(i))
            }
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until e.pointerCount) {
                    hud.onTouch(1, e.getX(i), e.getY(i), e.getPointerId(i))
                }
            }
            MotionEvent.ACTION_UP -> hud.onTouch(2, e.getX(), e.getY(), e.getPointerId(e.actionIndex))
            MotionEvent.ACTION_POINTER_UP -> hud.onTouch(2, e.getX(e.actionIndex), e.getY(e.actionIndex), e.getPointerId(e.actionIndex))
            MotionEvent.ACTION_CANCEL -> {
                for (i in 0 until e.pointerCount) {
                    hud.onTouch(3, e.getX(i), e.getY(i), e.getPointerId(i))
                }
            }
        }
        return true
    }
}
