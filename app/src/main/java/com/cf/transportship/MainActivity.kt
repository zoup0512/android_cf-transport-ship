// 主活动：全屏沉浸 + GL/HUD 布局 + 返回键
package com.cf.transportship

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.media.AudioManager
import android.speech.tts.TextToSpeech
import java.util.Locale
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import com.cf.transportship.game.Game
import com.cf.transportship.ui.GameView
import com.cf.transportship.ui.HudOverlay
import com.cf.transportship.ui.ST_PAUSE
import com.cf.transportship.ui.ST_PLAY

class MainActivity : Activity() {
    lateinit var game: Game
    lateinit var glView: GameView
    lateinit var hudView: HudOverlay

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        volumeControlStream = AudioManager.STREAM_MUSIC
        game = Game(this)
        game.loadOpts()
        game.autoStart = intent.getBooleanExtra("autostart", false)
        val root = FrameLayout(this)
        glView = GameView(this, game)
        hudView = HudOverlay(this, game)
        root.addView(glView, FrameLayout.LayoutParams(-1, -1))
        root.addView(hudView, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)
        hideSystemUI()
        game.hudSetHud = { dur, text -> game.hud?.toast(text, dur) }
        // TTS 播报（优先英文语音）
        tts = TextToSpeech(this) { st ->
            if (st == TextToSpeech.SUCCESS) {
                try {
                    val v = tts?.voices?.firstOrNull { it.locale.language.startsWith("en") }
                    tts?.language = v?.locale ?: Locale.US
                    game.audio.tts = { text ->
                        try { tts?.speak(text, TextToSpeech.QUEUE_ADD, null, "cf") } catch (e: Exception) {}
                    }
                } catch (e: Exception) {}
            }
        }
    }

    private var tts: TextToSpeech? = null

    private fun hideSystemUI() {
        val decor = window.decorView
        decor.systemUiVisibility = View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
            View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemUI()
    }

    override fun onResume() {
        super.onResume()
        glView.onResume()
    }

    override fun onPause() {
        super.onPause()
        glView.onPause()
        if (game.playing && !game.ended && !game.paused) game.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        game.audio.stop()
        try { tts?.shutdown() } catch (e: Exception) {}
    }

    // 物理键盘（模拟器/带键盘设备）
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (forwardKey(keyCode, true)) return true
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        if (forwardKey(keyCode, false)) return true
        return super.onKeyUp(keyCode, event)
    }

    private fun forwardKey(keyCode: Int, down: Boolean): Boolean {
        val g = game
        val p = g.player ?: return false
        if (!g.playing || g.paused || g.inLoadout) return false
        val code = when (keyCode) {
            KeyEvent.KEYCODE_W -> "KeyW"
            KeyEvent.KEYCODE_A -> "KeyA"
            KeyEvent.KEYCODE_S -> "KeyS"
            KeyEvent.KEYCODE_D -> "KeyD"
            KeyEvent.KEYCODE_SPACE -> "Space"
            KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_SHIFT_RIGHT -> "ShiftLeft"
            KeyEvent.KEYCODE_C -> "KeyC"
            KeyEvent.KEYCODE_R -> "KeyR"
            KeyEvent.KEYCODE_Q -> "KeyQ"
            KeyEvent.KEYCODE_B -> "KeyB"
            KeyEvent.KEYCODE_F -> "KeyF"
            KeyEvent.KEYCODE_1 -> "Digit1"
            KeyEvent.KEYCODE_2 -> "Digit2"
            KeyEvent.KEYCODE_3 -> "Digit3"
            KeyEvent.KEYCODE_4 -> "Digit4"
            KeyEvent.KEYCODE_TAB -> "Tab"
            KeyEvent.KEYCODE_ESCAPE -> { if (down) g.pause(); return true }
            else -> return false
        }
        if (down) { p.keys.add(code); p.pressed.add(code) }
        else p.keys.remove(code)
        return true
    }
}
