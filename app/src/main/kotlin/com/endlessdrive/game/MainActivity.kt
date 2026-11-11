package com.endlessdrive.game

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.endlessdrive.core.AdGate
import com.endlessdrive.core.Ev
import com.endlessdrive.core.World
import com.endlessdrive.engine.Quality
import com.endlessdrive.game.ads.Ads

/**
 * Screens and flow:
 *   MENU <-> SETTINGS,  MENU -> PLAYING <-> PAUSED,  PLAYING -> GAME_OVER -> (continue | retry | menu)
 * Interstitials are only ever considered when leaving a finished run, never while PLAYING.
 * After recreation or process death the activity comes back at MENU; scores are already saved.
 */
class MainActivity : ComponentActivity(), GameController.Listener, GameSurface.FrameHook {
    enum class Screen { MENU, SETTINGS, PLAYING, PAUSED, GAME_OVER }

    var screen = Screen.MENU
        private set
    val controller = GameController()
    lateinit var settings: Settings
        private set

    private lateinit var surface: GameSurface
    private lateinit var hud: HudView
    private lateinit var audio: Audio
    private lateinit var haptics: Haptics
    private lateinit var tilt: TiltInput
    private lateinit var ads: Ads
    private lateinit var gate: AdGate
    private val main = Handler(Looper.getMainLooper())
    private val gameOverTask = Runnable { onGameOver() }
    private var adOpen = false
    private var resumed = false
    private var runSerial = 0L

    private lateinit var panelMenu: View
    private lateinit var panelSettings: View
    private lateinit var panelPause: View
    private lateinit var panelOver: View
    private lateinit var scrim: View
    private lateinit var btnContinue: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        val app = application as DriveApp
        ads = app.ads
        gate = app.adGate
        settings = Settings(this)
        audio = Audio().apply { enabled = settings.sound }
        haptics = Haptics(this).apply { enabled = settings.vibration }

        surface = findViewById(R.id.surface)
        hud = findViewById(R.id.hud)
        panelMenu = findViewById(R.id.panelMenu)
        panelSettings = findViewById(R.id.panelSettings)
        panelPause = findViewById(R.id.panelPause)
        panelOver = findViewById(R.id.panelOver)
        scrim = findViewById(R.id.scrim)
        btnContinue = findViewById(R.id.btnContinue)

        controller.listener = this
        surface.setup(controller, Quality.of(settings.quality))
        surface.hud = hud
        surface.frameHook = this
        hud.controller = controller
        hud.onPauseTapped = { pause() }
        @Suppress("DEPRECATION")
        tilt = TiltInput(this, { windowManager.defaultDisplay.rotation }) { v -> if (screen == Screen.PLAYING) controller.steer = v }

        immersive()
        wire()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = back()
        })
        showMenu()
        ads.start(this)
    }

    private fun immersive() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val root = findViewById<View>(R.id.root)
        WindowInsetsControllerCompat(window, root).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val i = insets.getInsets(WindowInsetsCompat.Type.displayCutout() or WindowInsetsCompat.Type.systemBars())
            hud.setInsets(i.left, i.top, i.right, i.bottom)
            insets
        }
    }

    private fun on(id: Int, action: () -> Unit) = findViewById<View>(id).setOnClickListener {
        audio.play(Audio.CLICK)
        action()
    }

    private fun wire() {
        on(R.id.btnPlay) { startRun() }
        on(R.id.btnSettings) { showSettings() }
        on(R.id.btnBack) { showMenu() }
        on(R.id.btnControl) {
            settings.tilt = !settings.tilt && tilt.available
            if (!tilt.available) Toast.makeText(this, R.string.no_tilt, Toast.LENGTH_SHORT).show()
            refreshSettings()
        }
        on(R.id.btnSound) { settings.sound = !settings.sound; audio.enabled = settings.sound; refreshSettings() }
        on(R.id.btnVibration) { settings.vibration = !settings.vibration; haptics.enabled = settings.vibration; haptics.light(); refreshSettings() }
        on(R.id.btnQuality) {
            settings.quality = (settings.quality + 1) % 3
            surface.applyQuality(Quality.of(settings.quality))
            refreshSettings()
        }
        on(R.id.btnPrivacy) {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(BuildConfig.PRIVACY_URL)))
            } catch (_: Exception) {
                Toast.makeText(this, R.string.no_browser, Toast.LENGTH_SHORT).show()
            }
        }
        on(R.id.btnAdPrivacy) { ads.showPrivacyOptions(this) { refreshSettings() } }
        on(R.id.btnResume) { resume() }
        on(R.id.btnQuit) { finishRun { showMenu() } }
        on(R.id.btnRetry) { if (screen == Screen.GAME_OVER) finishRun { startRun() } }
        on(R.id.btnOverMenu) { if (screen == Screen.GAME_OVER) finishRun { showMenu() } }
        on(R.id.btnContinue) { continueWithAd() }
    }

    // ------------------------------------------------------------------ screens

    private fun show(s: Screen) {
        screen = s
        panelMenu.visibility = vis(s == Screen.MENU)
        panelSettings.visibility = vis(s == Screen.SETTINGS)
        panelPause.visibility = vis(s == Screen.PAUSED)
        panelOver.visibility = vis(s == Screen.GAME_OVER)
        scrim.visibility = vis(s == Screen.SETTINGS || s == Screen.PAUSED || s == Screen.GAME_OVER)
        hud.tiltMode = settings.tilt
        hud.active = s == Screen.PLAYING
        val playing = s == Screen.PLAYING
        audio.engineOn = playing
        if (playing) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (playing && settings.tilt && resumed) tilt.start() else tilt.stop()
    }

    private fun vis(on: Boolean) = if (on) View.VISIBLE else View.GONE

    private fun showMenu() {
        findViewById<TextView>(R.id.txtMenuBest).text = getString(R.string.best_fmt, fmt(settings.best))
        controller.showAttract()
        show(Screen.MENU)
    }

    private fun showSettings() {
        refreshSettings()
        show(Screen.SETTINGS)
    }

    private fun refreshSettings() {
        findViewById<Button>(R.id.btnControl).setText(if (settings.tilt) R.string.control_tilt else R.string.control_touch)
        findViewById<Button>(R.id.btnSound).setText(if (settings.sound) R.string.sound_on else R.string.sound_off)
        findViewById<Button>(R.id.btnVibration).setText(if (settings.vibration) R.string.vibration_on else R.string.vibration_off)
        findViewById<Button>(R.id.btnQuality).setText(
            when (settings.quality) { 0 -> R.string.quality_low; 2 -> R.string.quality_high; else -> R.string.quality_medium },
        )
        findViewById<View>(R.id.btnAdPrivacy).visibility = vis(ads.privacyOptionsRequired)
    }

    // ------------------------------------------------------------------ run flow

    private fun startRun() {
        gate.runStarted()
        controller.steer = 0f; controller.brake = false; controller.nitro = false
        controller.startRun(System.nanoTime() xor (++runSerial * 0x9E3779B9L))
        show(Screen.PLAYING)
    }

    private fun pause() {
        if (screen != Screen.PLAYING) return
        controller.paused = true
        show(Screen.PAUSED)
    }

    private fun resume() {
        if (screen != Screen.PAUSED) return
        show(Screen.PLAYING)
        controller.paused = false
    }

    /** Game thread -> here, once the crash sequence has finished. */
    private fun onGameOver() {
        if (screen != Screen.PLAYING && screen != Screen.PAUSED) return
        val w = controller.world
        val score = w.score
        val previous = settings.best
        val newBest = settings.submit(score)
        findViewById<TextView>(R.id.txtScore).text = fmt(score)
        findViewById<TextView>(R.id.txtBest).text = getString(R.string.best_fmt, fmt(maxOf(previous, score)))
        findViewById<View>(R.id.txtNewBest).visibility = vis(newBest)
        findViewById<TextView>(R.id.txtStats).text = getString(R.string.stats_fmt, w.distance.toInt(), w.coins, w.nearMisses, w.combo.best)
        btnContinue.visibility = vis(gate.continueAvailable() && ads.rewardedReady)
        controller.paused = false
        show(Screen.GAME_OVER)
    }

    /** Optional rewarded "continue once". */
    private fun continueWithAd() {
        if (screen != Screen.GAME_OVER || !gate.continueAvailable()) return
        adOpen = true
        val shown = ads.showRewarded(this) { earned ->
            adOpen = false
            if (earned && screen == Screen.GAME_OVER) {
                gate.continueUsed()
                controller.revive()
                show(Screen.PLAYING)
            } else {
                btnContinue.visibility = vis(gate.continueAvailable() && ads.rewardedReady)
            }
        }
        if (!shown) {
            adOpen = false
            btnContinue.visibility = View.GONE
        }
    }

    /** A run is over for good (retry, menu, or quit from pause): count it, maybe show an interstitial, then go on. */
    private fun finishRun(next: () -> Unit) {
        settings.submit(controller.world.score)
        settings.totalRuns = settings.totalRuns + 1
        gate.runCompleted()
        val now = SystemClock.elapsedRealtime()
        if (gate.interstitialAllowed(now, playing = false) && ads.interstitialReady) {
            adOpen = true
            val shown = ads.showInterstitial(this) {
                adOpen = false
                next()
            }
            if (shown) {
                gate.interstitialShown(now)
                return
            }
            adOpen = false
        }
        next()
    }

    private fun back() {
        when (screen) {
            Screen.PLAYING -> pause()
            Screen.PAUSED -> resume()
            Screen.SETTINGS -> showMenu()
            Screen.GAME_OVER -> finishRun { showMenu() }
            Screen.MENU -> finish()
        }
    }

    // ------------------------------------------------------------------ game thread callbacks

    override fun onGameEvents(events: Int, world: World) {
        if (events and Ev.COIN != 0) audio.play(Audio.COIN, 0.8f)
        if (events and Ev.SHIELD != 0) audio.play(Audio.SHIELD)
        if (events and Ev.SHIELD_BREAK != 0) { audio.play(Audio.SHATTER); haptics.medium() }
        if (events and Ev.NEAR_MISS != 0) { audio.play(Audio.WHOOSH); haptics.light() }
        if (events and Ev.NITRO != 0) { audio.play(Audio.NITRO); haptics.light() }
        if (events and Ev.CRASH != 0) { audio.play(Audio.CRASH); haptics.heavy() }
        if (events and Ev.GAME_OVER != 0) main.post(gameOverTask)
    }

    override fun onFrame() {
        val w = controller.world
        audio.engineSpeed = w.speedNorm()
        audio.boosting = w.boosting
        if (w.state != World.RUNNING) audio.engineOn = false
        else if (screen == Screen.PLAYING && !controller.paused) audio.engineOn = true
    }

    // ------------------------------------------------------------------ lifecycle

    override fun onResume() {
        super.onResume()
        resumed = true
        surface.onResume()
        audio.start()
        if (screen == Screen.PLAYING && settings.tilt) tilt.start()
    }

    override fun onPause() {
        // screen off, home, phone call, an ad opening on top...
        resumed = false
        if (screen == Screen.PLAYING) pause()
        tilt.stop()
        audio.stop()
        surface.onPause()
        super.onPause()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus && screen == Screen.PLAYING && !adOpen) pause()
        if (hasFocus) WindowInsetsControllerCompat(window, window.decorView).hide(WindowInsetsCompat.Type.systemBars())
    }

    override fun onDestroy() {
        controller.listener = null
        main.removeCallbacks(gameOverTask)
        audio.stop()
        super.onDestroy()
    }

    private fun fmt(v: Int): String = "%,d".format(v)

    /** Test hook: step the game exactly as the GL thread would, without a GPU. */
    internal fun stepFrames(n: Int, dt: Float = 1f / 60f) {
        repeat(n) { controller.frame(dt); onFrame() }
    }
}
