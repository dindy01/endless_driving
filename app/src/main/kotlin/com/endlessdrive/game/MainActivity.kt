package com.endlessdrive.game

import android.content.Intent
import android.net.Uri
import android.os.Bundle
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
import com.endlessdrive.game.ads.AdService

/**
 * Screen flow: MENU <-> SETTINGS, MENU -> PLAYING <-> PAUSED, PLAYING -> CRASHED -> GAME_OVER
 * -> (continue) PLAYING | (retry) PLAYING | MENU.
 * Interstitials are only considered when leaving GAME_OVER (never during gameplay).
 */
class MainActivity : ComponentActivity(), GameView.Listener {

    enum class Screen { MENU, SETTINGS, PLAYING, PAUSED, CRASHED, GAME_OVER }

    lateinit var gameView: GameView
        private set
    var screen = Screen.MENU
        private set

    private lateinit var prefs: Prefs
    private lateinit var sfx: Sfx
    private lateinit var haptics: Haptics
    private lateinit var ads: AdService
    private lateinit var adGate: AdGate

    private lateinit var panelMenu: View
    private lateinit var panelSettings: View
    private lateinit var panelPause: View
    private lateinit var panelGameOver: View
    private lateinit var scrim: View
    private lateinit var btnPause: View
    private lateinit var btnContinue: Button
    private lateinit var btnSound: Button
    private lateinit var btnVibration: Button
    private lateinit var btnPrivacyOptions: Button

    private var adShowing = false
    private var lastScore = 0L

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
        gameView.listener = this
        panelMenu = findViewById(R.id.panelMenu)
        panelSettings = findViewById(R.id.panelSettings)
        panelPause = findViewById(R.id.panelPause)
        panelGameOver = findViewById(R.id.panelGameOver)
        scrim = findViewById(R.id.scrim)
        btnPause = findViewById(R.id.btnPause)
        btnContinue = findViewById(R.id.btnContinue)
        btnSound = findViewById(R.id.btnSound)
        btnVibration = findViewById(R.id.btnVibration)
        btnPrivacyOptions = findViewById(R.id.btnPrivacyOptions)

        setupWindow()
        wireButtons()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = onBack()
        })

        // Any run in progress before process death / recreation cannot be restored faithfully;
        // we deliberately return to the menu (scores are persisted at crash time).
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
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            ui.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            gameView.setInsets(bars.top, bars.bottom)
            insets
        }
    }

    private fun wireButtons() {
        findViewById<View>(R.id.btnPlay).setOnClickListener { click(); startRun() }
        findViewById<View>(R.id.btnSettings).setOnClickListener { click(); showSettings() }
        findViewById<View>(R.id.btnSettingsBack).setOnClickListener { click(); showMenu() }
        btnSound.setOnClickListener {
            prefs.soundOn = !prefs.soundOn
            sfx.enabled = prefs.soundOn
            click()
            refreshSettings()
        }
        btnVibration.setOnClickListener {
            prefs.vibrationOn = !prefs.vibrationOn
            haptics.enabled = prefs.vibrationOn
            haptics.tick()
            refreshSettings()
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

    // ------------------------------------------------------------------ screens

    private fun setScreen(s: Screen) {
        screen = s
        show(panelMenu, s == Screen.MENU)
        show(panelSettings, s == Screen.SETTINGS)
        show(panelPause, s == Screen.PAUSED)
        show(panelGameOver, s == Screen.GAME_OVER)
        scrim.visibility = if (s == Screen.PAUSED || s == Screen.GAME_OVER || s == Screen.SETTINGS) View.VISIBLE else View.GONE
        btnPause.visibility = if (s == Screen.PLAYING) View.VISIBLE else View.GONE
        if (s == Screen.PLAYING) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            sfx.startEngine()
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            sfx.stopEngine()
        }
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
        gameView.showAttract()
        setScreen(Screen.MENU)
    }

    private fun showSettings() {
        refreshSettings()
        setScreen(Screen.SETTINGS)
    }

    private fun refreshSettings() {
        btnSound.setText(if (prefs.soundOn) R.string.sound_on else R.string.sound_off)
        btnVibration.setText(if (prefs.vibrationOn) R.string.vibration_on else R.string.vibration_off)
        btnPrivacyOptions.visibility = if (ads.isPrivacyOptionsRequired()) View.VISIBLE else View.GONE
    }

    // ------------------------------------------------------------------ run flow

    private fun startRun() {
        adGate.onRunStarted()
        gameView.startRun(SystemClock.elapsedRealtimeNanos())
        setScreen(Screen.PLAYING)
    }

    private fun pause() {
        if (screen != Screen.PLAYING) return
        gameView.pauseGame()
        setScreen(Screen.PAUSED)
    }

    private fun resume() {
        if (screen != Screen.PAUSED) return
        gameView.resumeGame()
        setScreen(Screen.PLAYING)
    }

    /** Quitting from the pause menu still counts as a completed run. */
    private fun abandonRunToMenu() {
        completeRun(gameView.world.score)
        maybeInterstitialThen { showMenu() }
    }

    override fun onCrash() {
        sfx.play(Sfx.CRASH)
        haptics.crash()
        lastScore = gameView.world.score
        setScreen(Screen.CRASHED)
    }

    override fun onCrashSequenceDone() {
        if (screen != Screen.CRASHED) return
        showGameOver()
    }

    private fun showGameOver() {
        val score = gameView.world.score
        val prevBest = prefs.best
        val isBest = prefs.submitScore(score)
        findViewById<TextView>(R.id.txtScore).text = formatScore(score)
        findViewById<TextView>(R.id.txtBest).text = getString(R.string.best_fmt, formatScore(maxOf(prevBest, score)))
        findViewById<View>(R.id.txtNewBest).visibility = if (isBest) View.VISIBLE else View.GONE
        findViewById<TextView>(R.id.txtRunStats).text =
            getString(R.string.run_stats_fmt, gameView.world.coins, gameView.world.nearMisses)
        btnContinue.visibility =
            if (adGate.canOfferContinue() && ads.isRewardedReady()) View.VISIBLE else View.GONE
        setScreen(Screen.GAME_OVER)
    }

    private fun tryContinue() {
        if (!adGate.canOfferContinue()) return
        adShowing = true
        val shown = ads.showRewarded(this) { earned ->
            adShowing = false
            if (earned && screen == Screen.GAME_OVER) {
                adGate.onContinueUsed()
                gameView.revive()
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
        completeRun(gameView.world.score)
        maybeInterstitialThen { if (retry) startRun() else showMenu() }
    }

    private fun completeRun(score: Long) {
        prefs.submitScore(score)
        adGate.onRunCompleted()
        prefs.totalRuns = prefs.totalRuns + 1
    }

    /** Shows an interstitial only if AdGate allows it AND one is loaded; otherwise continues instantly. */
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

    // ------------------------------------------------------------------ feedback

    override fun onFeedback(event: Int, combo: Int) {
        when (event) {
            GameView.FB_LANE -> sfx.play(Sfx.LANE, 0.35f)
            GameView.FB_COIN -> sfx.play(Sfx.COIN, 0.55f)
            GameView.FB_SHIELD -> sfx.play(Sfx.SHIELD, 0.7f)
            GameView.FB_SHIELD_BREAK -> { sfx.play(Sfx.SHIELD_BREAK); haptics.bump() }
            GameView.FB_OIL -> { sfx.play(Sfx.OIL, 0.8f); haptics.bump() }
            GameView.FB_NEAR_MISS -> { sfx.play(Sfx.NEAR_MISS, 0.7f, 1f + 0.08f * combo); haptics.tick() }
        }
    }

    override fun onTick(speedNorm: Float) = sfx.updateEngine(speedNorm)

    // ------------------------------------------------------------------ lifecycle

    override fun onResume() {
        super.onResume()
        sfx.resumeAll()
        gameView.startLoop()
        if (screen == Screen.PLAYING) sfx.startEngine()
    }

    override fun onPause() {
        // Screen off, home, incoming call, ad overlay, multi-window focus loss...
        if (screen == Screen.PLAYING) pause()
        gameView.stopLoop()
        sfx.stopEngine()
        sfx.pauseAll()
        super.onPause()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus && screen == Screen.PLAYING && !adShowing) pause()
        if (hasFocus) {
            WindowInsetsControllerCompat(window, window.decorView).hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    override fun onDestroy() {
        gameView.listener = null
        sfx.release()
        super.onDestroy()
    }

    private fun formatScore(v: Long): String = "%,d".format(v)

    /** Exposed for tests. */
    internal val speedKmh: Float get() = gameView.world.speed * GameConfig.KMH_PER_UNIT
}
