package de.hakenadu.piant

import android.app.ActivityManager
import android.content.ContentValues
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.MediaStore
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : ComponentActivity(), PaintView.Listener {

    private lateinit var paintView: PaintView
    private lateinit var overlay: ComposeView
    private lateinit var sound: SoundBox
    private lateinit var prefs: SharedPreferences
    private var vibrator: Vibrator? = null
    private val io: ExecutorService = Executors.newSingleThreadExecutor()

    // UI state for the parent menu (Compose)
    private var menuOpen by mutableStateOf(false)
    private var introVisible by mutableStateOf(true)
    private var soundOn by mutableStateOf(true)
    private var hapticsOn by mutableStateOf(true)
    private var paletteOn by mutableStateOf(true)
    private var pinned by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        prefs = getSharedPreferences("piant", Context.MODE_PRIVATE)
        soundOn = prefs.getBoolean(KEY_SOUND, true)
        hapticsOn = prefs.getBoolean(KEY_HAPTICS, true)
        paletteOn = prefs.getBoolean(KEY_PALETTE, true)

        sound = SoundBox(this).also { it.enabled = soundOn }
        vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }

        paintView = PaintView(this).apply {
            listener = this@MainActivity
            paletteVisible = paletteOn
        }
        overlay = ComposeView(this).apply {
            setContent {
                PiantTheme {
                    PiantOverlay(
                        introVisible = introVisible,
                        menuOpen = menuOpen,
                        menu = MenuState(soundOn, hapticsOn, paletteOn, pinned),
                        actions = menuActions,
                        onIntroDone = {
                            introVisible = false
                            updateOverlayVisibility()
                        },
                        onMenuHidden = { updateOverlayVisibility() },
                    )
                }
            }
        }

        val match = ViewGroup.LayoutParams.MATCH_PARENT
        setContentView(
            FrameLayout(this).apply {
                addView(paintView, FrameLayout.LayoutParams(match, match))
                addView(this@MainActivity.overlay, FrameLayout.LayoutParams(match, match))
            },
        )

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        goImmersive()

        // Back button / back swipe never leaves the app; it only closes the parent menu.
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (menuOpen) closeMenu()
                }
            },
        )

        io.execute {
            val file = File(filesDir, CANVAS_FILE)
            val saved = if (file.exists()) BitmapFactory.decodeFile(file.path) else null
            if (saved != null) runOnUiThread { paintView.restore(saved) }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshPinned()
        goImmersive()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) goImmersive()
    }

    override fun onStop() {
        super.onStop()
        // Keep the picture when the app goes to the background or gets closed.
        val snapshot = paintView.snapshot() ?: return
        io.execute {
            runCatching {
                FileOutputStream(File(filesDir, CANVAS_FILE)).use {
                    snapshot.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
            }
            snapshot.recycle()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        sound.release()
        io.shutdown()
    }

    private fun goImmersive() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    // ---- PaintView.Listener ---------------------------------------------------------------

    override fun onPaintStart(xFraction: Float, yFraction: Float) {
        sound.note(xFraction)
    }

    override fun onColorPicked(slot: Int) {
        sound.pick(slot)
    }

    override fun onParentGateOpened() {
        openMenu()
    }

    override fun onHaptic() {
        if (!hapticsOn) return
        val v = vibrator ?: return
        if (!v.hasVibrator()) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            v.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK))
        } else {
            v.vibrate(VibrationEffect.createOneShot(12, VibrationEffect.DEFAULT_AMPLITUDE))
        }
    }

    // ---- Parent menu ----------------------------------------------------------------------

    private fun openMenu() {
        paintView.isEnabled = false
        refreshPinned()
        overlay.visibility = View.VISIBLE
        menuOpen = true
    }

    private fun closeMenu() {
        menuOpen = false
        paintView.isEnabled = true
        goImmersive()
    }

    private fun updateOverlayVisibility() {
        overlay.visibility = if (menuOpen || introVisible) View.VISIBLE else View.GONE
    }

    private fun setPref(key: String, value: Boolean) {
        prefs.edit().putBoolean(key, value).apply()
    }

    private val menuActions = MenuActions(
        onClose = { closeMenu() },
        onNewPicture = {
            closeMenu()
            paintView.clear()
            sound.note(0.5f)
        },
        onSave = { saveToGallery() },
        onSoundChange = {
            soundOn = it
            sound.enabled = it
            setPref(KEY_SOUND, it)
        },
        onHapticsChange = {
            hapticsOn = it
            setPref(KEY_HAPTICS, it)
        },
        onPaletteChange = {
            paletteOn = it
            paintView.paletteVisible = it
            setPref(KEY_PALETTE, it)
        },
        onTogglePin = { togglePin() },
        onExit = {
            if (isPinned()) runCatching { stopLockTask() }
            finishAndRemoveTask()
        },
    )

    private fun isPinned(): Boolean {
        val am = getSystemService(ActivityManager::class.java) ?: return false
        return am.lockTaskModeState != ActivityManager.LOCK_TASK_MODE_NONE
    }

    private fun refreshPinned() {
        pinned = isPinned()
    }

    private fun togglePin() {
        if (isPinned()) {
            runCatching { stopLockTask() }
        } else {
            closeMenu()
            val ok = runCatching { startLockTask() }.isSuccess
            if (!ok) toast(getString(R.string.pin_failed))
        }
        paintView.postDelayed({ refreshPinned() }, 800)
    }

    private fun saveToGallery() {
        val snapshot = paintView.snapshot() ?: return
        io.execute {
            val ok = runCatching {
                val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.GERMANY).format(Date())
                val name = "Piant_$stamp.png"
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val values = ContentValues().apply {
                        put(MediaStore.Images.Media.DISPLAY_NAME, name)
                        put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                        put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Piant")
                        put(MediaStore.Images.Media.IS_PENDING, 1)
                    }
                    val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                        ?: error("insert failed")
                    contentResolver.openOutputStream(uri)?.use {
                        snapshot.compress(Bitmap.CompressFormat.PNG, 100, it)
                    } ?: error("no stream")
                    values.clear()
                    values.put(MediaStore.Images.Media.IS_PENDING, 0)
                    contentResolver.update(uri, values, null, null)
                } else {
                    val dir = File(getExternalFilesDir(Environment.DIRECTORY_PICTURES), "Piant").apply { mkdirs() }
                    FileOutputStream(File(dir, name)).use {
                        snapshot.compress(Bitmap.CompressFormat.PNG, 100, it)
                    }
                }
            }.isSuccess
            snapshot.recycle()
            runOnUiThread { toast(getString(if (ok) R.string.saved else R.string.save_failed)) }
        }
    }

    private fun toast(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    }

    private companion object {
        const val CANVAS_FILE = "canvas.png"
        const val KEY_SOUND = "sound"
        const val KEY_HAPTICS = "haptics"
        const val KEY_PALETTE = "palette"
    }
}
