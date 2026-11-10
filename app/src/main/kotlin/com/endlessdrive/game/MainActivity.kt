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
import com.endlessdrive.core.GameConfig
import com.endlessdrive.core.GameSession
import com.endlessdrive.core.SessionListener
import com.endlessdrive.game.ads.AdService
import com.endlessdrive.render.Quality

/**
 * Screen flow: MENU <-> SETTINGS, MENU -> PLAYING <-> PAUSED, PLAYING -> CRASHED (slow-mo)
 * -> GAME_OVER -> continue | retry | menu. The game itself runs on the GL thread inside
 * GameSurfaceView; this activity only sends requests to the GameSession and shows panels.
 * Interstitials are considered only when leaving GAME_OVER / quitting from pause.
 */
class MainActivity : ComponentActivity(), SessionListener {

    enum class Screen { MENU, SETTINGS, PLAYING, PAUSED, CRASHED, GAME_OVER }

    lateinit var gameView: GameSurfaceView
        private set
    lateinit var hud: HudView
        private set
    val session: GameSession get() = gameView.session
    var screen = Screen.MENU
        private set

    private lateinit var prefs: Prefs
    private lateinit var sfx: Sfx
    private lateinit var haptics: Haptics
    private lateinit var ads: AdService
    private lateinit var adGate: AdGate
    private lateinit var tilt: TiltSteering

    private lateinit var panelMenu: View
    private lateinit var panelSettings: View
    private lateinit var panelPause: View
    private lateinit var panelGameOver: View
    private lateinit var scrim: View
    private lateinit var btnPause: View
    private lateinit var btnContinue: Button
    private lateinit var btnSound: Button
    private lateinit var btnVibration: Button
    private lateinit var btnControls: Button
    private lateinit var btnQuality: Button
    private lateinit var btnPrivacyOptions: Button

    private val main = Handler(Looper.getMainLooper())
    private var adShowing = false
    private val crashRunnable = Runnable { if (screen == Screen.PLAYING) setScreen(Screen.CRASHED) }
    private val gameOverRunnable = Runnable { if (screen == Screen.CRASHED || screen == Screen.PLAYING) showGameOver() }
    private val engineTick = Runnable { sfx.updateEngine(speedNorm()) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        val app = application as EndlessDriveApp
        prefs = Prefs(this)
        sfx = Sfx(this)
        haptics = Haptics(this)
        ads = app.ads
        adGate = app.adGate
        sfx.enabled = prefs.soundOn
        haptics.enabled = prefs.vibrationOn

        gameView = findViewById(R.id.gameView)
        hud = findViewById(R.id.hud)
        hud.session = session
        hud.onFrame = engineTick
        session.listener = this
        tilt = TiltSteering(this, session.input)
        gameView.qualityLevel = prefs.quality

        panelMenu = findViewById(R.id.panelMenu)
        panelSettings = findViewById(R.id.panelSettings)
        panelPause = findViewById(R.id.panelPause)
        panelGameOver = findViewById(R.id.panelGameOver)
        scrim = findViewById(R.id.scrim)
        btnPause = findViewById(R.id.btnPause)
        btnContinue = findViewById(R.id.btnContinue)
        btnSound = findViewById(R.id.btnSound)
        btnVibration = findViewById(R.id.btnVibration)
        btnControls = findViewById(R.id.btnControls)
        btnQuality = findViewById(R.id.btnQuality)
        btnPrivacyOptions = findViewById(R.id.btnPrivacyOptions)

        setupWindow()
        wireButtons()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = onBack()
        })
        // A run cannot be restored after process death/recreation; return to the menu.
        showMenu()
        ads.start(this)
    }

    private fun setupWindow() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val root = findViewById<View>(R.id.root)
        WindowInsetsControllerCompat(window, root).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        val ui = findViewById<View>(R.id.uiLayer)
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            ui.setPadding(b.left, b.top, b.right, b.bottom)
            hud.setInsets(b.left, b.top, b.right, b.bottom)
            insets
        }
        gameView.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> gameView.applyResolution(prefs.quality) }
    }

    private fun wireButtons() {
        findViewById<View>(R.id.btnPlay).setOnClickListener { click(); startRun() }
        findViewById<View>(R.id.btnSettings).setOnClickListener { click(); showSettings() }
        findViewById<View>(R.id.btnSettingsBack).setOnClickListener { click(); showMenu() }
        btnSound.setOnClickListener {
            prefs.soundOn = !prefs.soundOn
            sfx.enabled = prefs.soundOn
            click(); refreshSettings()
        }
        btnVibration.setOnClickListener {
            prefs.vibrationOn = !prefs.vibrationOn
            haptics.enabled = prefs.vibrationOn
            haptics.tick(); refreshSettings()
        }
        btnControls.setOnClickListener {
            if (prefs.controlScheme == 0 && !tilt.available) {
                Toast.makeText(this, R.string.tilt_unavailable, Toast.LENGTH_SHORT).show()
            } else {
                prefs.controlScheme = 1 - prefs.controlScheme
            }
            click(); refreshSettings()
        }
        btnQuality.setOnClickListener {
            prefs.quality = (prefs.quality + 1) % 3
            gameView.qualityLevel = prefs.quality
            gameView.applyResolution(prefs.quality)
            click(); refreshSettings()
        }
        findViewById<View>(R.id.btnPrivacyPolicy).setOnClickListener {
            click()
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(BuildConfig.PRIVACY_POLICY_URL)))
            } catch (_: Exception) {
                Toast.makeText(this, R.string.no_browser, Toast.LENGTH_SHORT).show()
            }
        }
        btnPrivacyOptions.setOnClickListener { click(); ads.showPrivacyOptions(this) { refreshSettings() } }
        btnPause.setOnClickListener { click(); pause() }
        findViewById<View>(R.id.btnResume).setOnClickListener { click(); resume() }
        findViewById<View>(R.id.btnPauseMenu).setOnClickListener { click(); abandonRunToMenu() }
        findViewById<View>(R.id.btnRetry).setOnClickListener { click(); leaveGameOver(retry = true) }
        findViewById<View>(R.id.btnGameOverMenu).setOnClickListener { click(); leaveGameOver(retry = false) }
        btnContinue.setOnClickListener { click(); tryContinue() }
    }

    private fun click() = sfx.play(Sfx.CLICK, 0.5f)

    private fun speedNorm(): Float =
        if (screen != Screen.PLAYING) 0f
        else ((session.hud.speedKmh / GameConfig.KMH_PER_MS - GameConfig.V0) / (GameConfig.V_MAX_PLAYER - GameConfig.V0)).coerceIn(0f, 1f)

    // ------------------------------------------------------------------ screens

    private fun setScreen(s: Screen) {
        screen = s
        show(panelMenu, s == Screen.MENU)
        show(panelSettings, s == Screen.SETTINGS)
        show(panelPause, s == Screen.PAUSED)
        show(panelGameOver, s == Screen.GAME_OVER)
        scrim.visibility = if (s == Screen.PAUSED || s == Screen.GAME_OVER || s == Screen.SETTINGS) View.VISIBLE else View.GONE
        btnPause.visibility = if (s == Screen.PLAYING) View.VISIBLE else View.GONE
        hud.tiltMode = prefs.controlScheme == 1 && tilt.available
        if (s == Screen.PLAYING) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            sfx.startEngine()
            if (hud.tiltMode) tilt.start() else tilt.stop()
            gameView.resumeRendering()
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            sfx.stopEngine()
            tilt.stop()
            hud.releaseAll(session)
        }
        hud.invalidate()
    }

    private fun show(v: View, visible: Boolean) {
        if (visible && v.visibility != View.VISIBLE) {
            v.visibility = View.VISIBLE
            v.alpha = 0f
            v.scaleX = 0.94f
            v.scaleY = 0.94f
            v.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(180).start()
        } else if (!visible) {
            v.animate().cancel()
            v.visibility = View.GONE
        }
    }

    private fun showMenu() {
        findViewById<TextView>(R.id.txtMenuBest).text = getString(R.string.best_fmt, formatScore(prefs.best))
        session.requestAttract()
        gameView.resumeRendering()
        setScreen(Screen.MENU)
    }

    private fun showSettings() {
        refreshSettings()
        setScreen(Screen.SETTINGS)
    }

    private fun refreshSettings() {
        btnSound.setText(if (prefs.soundOn) R.string.sound_on else R.string.sound_off)
        btnVibration.setText(if (prefs.vibrationOn) R.string.vibration_on else R.string.vibration_off)
        btnControls.setText(if (prefs.controlScheme == 1) R.string.controls_tilt else R.string.controls_touch)
        btnQuality.setText(
            when (prefs.quality) {
                Quality.LOW -> R.string.quality_low
                Quality.HIGH -> R.string.quality_high
                else -> R.string.quality_medium
            },
        )
        btnPrivacyOptions.visibility = if (ads.isPrivacyOptionsRequired()) View.VISIBLE else View.GONE
    }

    // ------------------------------------------------------------------ run flow

    private fun startRun() {
        adGate.onRunStarted()
        session.input.steer = 0f
        session.input.brake = false
        session.requestStart(SystemClock.elapsedRealtimeNanos())
        setScreen(Screen.PLAYING)
    }

    private fun pause() {
        if (screen != Screen.PLAYING) return
        session.requestPause()
        setScreen(Screen.PAUSED)
    }

    private fun resume() {
        if (screen != Screen.PAUSED) return
        session.requestResume()
        setScreen(Screen.PLAYING)
    }

    private fun abandonRunToMenu() {
        completeRun(session.world.score)
        maybeInterstitialThen { showMenu() }
    }

    // SessionListener (GL thread) -> UI thread
    override fun onCrash() {
        main.post(crashRunnable)
    }

    override fun onGameOver() {
        main.post(gameOverRunnable)
    }

    override fun onFeedback(event: Int, value: Int) {
        when (event) {
            GameSession.FB_COIN -> sfx.play(Sfx.COIN, 0.55f)
            GameSession.FB_SHIELD -> sfx.play(Sfx.SHIELD, 0.7f)
            GameSession.FB_SHIELD_BREAK -> { sfx.play(Sfx.SHIELD_BREAK); haptics.bump() }
            GameSession.FB_NEAR_MISS -> { sfx.play(Sfx.NEAR_MISS, 0.7f, 1f + 0.06f * value); haptics.tick() }
            GameSession.FB_BOOST -> sfx.play(Sfx.BOOST, 0.8f)
            GameSession.FB_CRASH -> { sfx.play(Sfx.CRASH); haptics.crash() }
        }
    }

    private fun showGameOver() {
        val w = session.world
        val score = w.score
        val prevBest = prefs.best
        val isBest = prefs.submitScore(score)
        findViewById<TextView>(R.id.txtScore).text = formatScore(score)
        findViewById<TextView>(R.id.txtBest).text = getString(R.string.best_fmt, formatScore(maxOf(prevBest, score)))
        findViewById<View>(R.id.txtNewBest).visibility = if (isBest) View.VISIBLE else View.GONE
        findViewById<TextView>(R.id.txtRunStats).text =
            getString(R.string.run_stats_fmt, formatScore(w.distance.toLong()), w.coins, w.nearMisses)
        btnContinue.visibility = if (adGate.canOfferContinue() && ads.isRewardedReady()) View.VISIBLE else View.GONE
        setScreen(Screen.GAME_OVER)
    }

    private fun tryContinue() {
        if (!adGate.canOfferContinue()) return
        adShowing = true
        val shown = ads.showRewarded(this) { earned ->
            adShowing = false
            if (earned && screen == Screen.GAME_OVER) {
                adGate.onContinueUsed()
                session.requestRevive()
                setScreen(Screen.PLAYING)
            } else {
                btnContinue.visibility = if (ads.isRewardedReady()) View.VISIBLE else View.GONE
            }
        }
        if (!shown) {
            adShowing = false
            btnContinue.visibility = View.GONE
        }
    }

    private fun leaveGameOver(retry: Boolean) {
        if (screen != Screen.GAME_OVER) return
        completeRun(session.world.score)
        maybeInterstitialThen { if (retry) startRun() else showMenu() }
    }

    private fun completeRun(score: Long) {
        prefs.submitScore(score)
        adGate.onRunCompleted()
        prefs.totalRuns = prefs.totalRuns + 1
    }

    private fun maybeInterstitialThen(next: () -> Unit) {
        val now = SystemClock.elapsedRealtime()
        if (adGate.shouldShowInterstitial(now, inGameplay = false) && ads.isInterstitialReady()) {
            adShowing = true
            val shown = ads.showInterstitial(this) {
                adShowing = false
                next()
            }
            if (shown) {
                adGate.onInterstitialShown(now)
                return
            }
            adShowing = false
        }
        next()
    }

    private fun onBack() {
        when (screen) {
            Screen.PLAYING -> pause()
            Screen.PAUSED -> resume()
            Screen.SETTINGS -> showMenu()
            Screen.GAME_OVER -> leaveGameOver(retry = false)
            Screen.CRASHED -> Unit
            Screen.MENU -> finish()
        }
    }

    // ------------------------------------------------------------------ lifecycle

    override fun onResume() {
        super.onResume()
        gameView.onResume()
        sfx.resumeAll()
        if (screen == Screen.PLAYING) {
            sfx.startEngine()
            if (hud.tiltMode) tilt.start()
        }
        hud.invalidate()
    }

    override fun onPause() {
        // Screen off, home, call, ad overlay...: never keep a run going in the background.
        if (screen == Screen.PLAYING) pause()
        tilt.stop()
        sfx.stopEngine()
        sfx.pauseAll()
        gameView.onPause()
        super.onPause()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus && screen == Screen.PLAYING && !adShowing) pause()
        if (hasFocus) WindowInsetsControllerCompat(window, window.decorView).hide(WindowInsetsCompat.Type.systemBars())
    }

    override fun onDestroy() {
        session.listener = null
        hud.onFrame = null
        main.removeCallbacksAndMessages(null)
        tilt.stop()
        sfx.release()
        super.onDestroy()
    }

    private fun formatScore(v: Long): String = "%,d".format(v)

    /** Tests: advance the game deterministically (Robolectric has no GL thread). */
    internal fun stepForTest(frames: Int, dt: Float = 1f / 60f) {
        repeat(frames) { session.frame(dt) }
    }
}
