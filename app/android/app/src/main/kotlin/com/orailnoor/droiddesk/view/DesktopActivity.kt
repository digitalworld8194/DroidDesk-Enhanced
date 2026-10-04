package com.orailnoor.droiddesk.view

import com.orailnoor.droiddesk.R
import android.app.Activity
import android.content.ContentValues
import android.os.Build
import android.os.Environment
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.graphics.Color
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.GradientDrawable
import android.view.Window
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.view.KeyEvent
import android.view.PixelCopy
import android.view.SurfaceHolder
import android.view.ViewGroup
import android.view.View
import android.view.MotionEvent
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.util.Log
import android.widget.Toast
import android.widget.Button
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import android.view.Gravity
import android.content.res.ColorStateList
import com.termux.x11.MainActivity as TermuxMainActivity
import com.termux.x11.LorieView
import com.orailnoor.droiddesk.runtime.LinuxRuntime
import com.orailnoor.droiddesk.runtime.ChrootRuntime
import com.orailnoor.droiddesk.runtime.ClipboardSync
import com.orailnoor.droiddesk.x11.X11ServiceClient
import com.orailnoor.droiddesk.x11.X11InputController
import com.orailnoor.droiddesk.x11.X11TextCursorProbe
import com.termux.x11.input.TrackpadSensitivity
import android.provider.MediaStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class DesktopActivity : Activity() {
    private var lorieView: LorieView? = null
    private val textCursorProbe by lazy { X11TextCursorProbe(this) }
    private var connectionRequested = false
    private var isSetupDone = false
    private var shouldStartSession = false
    private var sessionMode = "termux"
    private var desktopEnv = "xfce4"
    private lateinit var linuxRuntime: LinuxRuntime
    private lateinit var chrootRuntime: ChrootRuntime
    private var clipboardSync: ClipboardSync? = null
    private lateinit var placeholder: FrameLayout
    private var x11ServiceClient: X11ServiceClient? = null
    private var inputController: X11InputController? = null
    private var inputModeButton: Button? = null
    private var sensitivityButton: Button? = null
    private var sensitivityPanel: LinearLayout? = null
    private var controlOverlay: LinearLayout? = null
    private var collapsedControl: Button? = null
    private var surfaceCallback: SurfaceHolder.Callback? = null
    private var loadingOverlay: FrameLayout? = null
    private var loadingStatus: TextView? = null
    private var loadingEstimate: TextView? = null
    private var desktopRevealed = false
    private val loadingMessageHandler = Handler(Looper.getMainLooper())
    private var loadingMessageIndex = 0
    private var loadingStartedAt = 0L
    private var estimatedLoadingSeconds = 30
    private val loadingMessages by lazy {
        resources.getStringArray(R.array.desktop_loading_messages).toList()
    }
    private val loadingMessageTicker = object : Runnable {
        override fun run() {
            val status = loadingStatus ?: return
            val ticker = this
            status.animate().alpha(0f).setDuration(180).withEndAction {
                loadingMessageIndex = (loadingMessageIndex + 1) % loadingMessages.size
                status.text = loadingMessages[loadingMessageIndex]
                status.animate().alpha(1f).setDuration(260).withEndAction {
                    if (!desktopRevealed) {
                        loadingMessageHandler.postDelayed(ticker, 5_000)
                    }
                }.start()
            }.start()
        }
    }
    private val loadingEstimateTicker = object : Runnable {
        override fun run() {
            val estimate = loadingEstimate ?: return
            val elapsedSeconds = ((android.os.SystemClock.elapsedRealtime() - loadingStartedAt) / 1_000).toInt()
            val remaining = (estimatedLoadingSeconds - elapsedSeconds).coerceAtLeast(0)
            estimate.text = if (remaining > 0) {
                loadingRemainingText(remaining)
            } else {
                getString(R.string.desktop_loading_finishing)
            }
            estimate.contentDescription = estimate.text
            if (!desktopRevealed) loadingMessageHandler.postDelayed(this, 1_000)
        }
    }

    companion object {
        private const val TAG = "DesktopActivity"

        @Volatile private var active: java.lang.ref.WeakReference<DesktopActivity>? = null

        /** Closes the visible desktop, e.g. after Termux stopped the session (droiddeskctl). */
        fun finishActive() {
            val activity = active?.get() ?: return
            activity.runOnUiThread { if (!activity.isFinishing) activity.finish() }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        active = java.lang.ref.WeakReference(this)
        linuxRuntime = LinuxRuntime(this)
        chrootRuntime = ChrootRuntime(this)
        shouldStartSession = intent.getBooleanExtra("startSession", false)
        sessionMode = intent.getStringExtra("mode") ?: if (chrootRuntime.hasRoot()) "chroot" else "termux"
        desktopEnv = intent.getStringExtra("de") ?: "xfce4"
        estimatedLoadingSeconds = if (shouldStartSession) 30 else 10

        requestWindowFeature(Window.FEATURE_NO_TITLE)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)


        // Perfil laptop: el teclado Android se superpone al escritorio
        // sin cambiar permanentemente la geometría de XFCE.
        window.setSoftInputMode(
            WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
        )

placeholder = FrameLayout(this)
        placeholder.setBackgroundColor(Color.BLACK)
        setContentView(placeholder)
        showLoadingOverlay()
        // Some Android/LineageOS builds throw from PhoneWindow.getInsetsController
        // until a decor view has been created by setContentView().
        enableImmersiveMode()

        Log.i(TAG, "DesktopActivity created mode=$sessionMode startSession=$shouldStartSession")
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) enableImmersiveMode()
        if (hasFocus) startClipboardSync() else clipboardSync?.stop()
        if (hasFocus && !isSetupDone) {
            isSetupDone = true
            Log.i(TAG, "Window focused — setting up LorieView")
            setupLorieView()
        }
    }

    override fun onResume() {
        super.onResume()
        enableImmersiveMode()
    }

    private fun startClipboardSync() {
        if (clipboardSync == null) {
            clipboardSync = if (sessionMode == "chroot") {
                ClipboardSync(this, chrootRuntime::readLinuxClipboard, chrootRuntime::writeLinuxClipboard)
            } else {
                ClipboardSync(this, linuxRuntime::readLinuxClipboard, linuxRuntime::writeLinuxClipboard)
            }
        }
        clipboardSync?.start()
    }

    override fun onPause() {
        clipboardSync?.stop()
        super.onPause()
    }

    @Suppress("DEPRECATION")
    private fun enableImmersiveMode() {
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        window.attributes = window.attributes.apply {
            layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
            window.decorView.windowInsetsController?.apply {
                hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                systemBarsBehavior =
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            window.setFlags(
                WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN,
            )
            window.decorView.systemUiVisibility =
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        }
    }

    private fun setupLorieView() {
        Log.i(TAG, "Setting up LorieView")
        X11InputController.configureDisplayScale()
        TermuxMainActivity.getInstance().initLorieView(this)
        lorieView = TermuxMainActivity.getInstance().lorieView

        // Keep Android overlay controls above the X11 SurfaceView.
        lorieView!!.setZOrderOnTop(false)
        placeholder.setBackgroundColor(Color.BLACK)

        val params = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        )
        // TermuxMainActivity retains its LorieView singleton across activity
        // recreation. Detach it from the previous activity's container before
        // attaching it here, otherwise Android throws "child already has a parent".
        (lorieView!!.parent as? ViewGroup)?.removeView(lorieView)
        placeholder.addView(lorieView, params)
        loadingOverlay?.bringToFront()
        Log.i(TAG, "LorieView added to placeholder")

        // Start X server only after the Surface is actually created/changed.
        surfaceCallback = object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                Log.i(TAG, "LorieView surfaceCreated")
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                Log.i(TAG, "LorieView surfaceChanged ${width}x${height}")
                synchronized(this@DesktopActivity) {
                    if (!connectionRequested) {
                        connectionRequested = true
                        connectToX11Service()
                    }
                }
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                Log.i(TAG, "LorieView surfaceDestroyed")
            }
        }.also { lorieView!!.holder.addCallback(it) }
    }

    private fun connectToX11Service() {
        if (LorieView.connected()) {
            attachDesktopInput()
            return
        }

        x11ServiceClient = X11ServiceClient(
            context = this,
            onConnected = { connectionFd, logcatFd ->
                try {
                    LorieView.connect(connectionFd.detachFd())
                    logcatFd?.let { LorieView.startLogcat(it.detachFd()) }
                    Log.i(TAG, "LorieView connected to the :x11 service process")

                    attachDesktopInput()
                } catch (error: Throwable) {
                    connectionFd.close()
                    logcatFd?.close()
                    showX11Error("Failed to attach LorieView to the X11 service", error)
                }
            },
            onError = ::showX11Error,
        ).also { it.connect() }
    }

    private fun attachDesktopInput() {
        if (inputController == null) {
            inputController = X11InputController(lorieView!!) {
                maybeShowKeyboardForTextCursor()
            }
        }
        addDesktopControls()
        lorieView?.requestFocus()
        if (shouldStartSession) {
            startDesktopSessionIfRequested()
        } else {
            waitForDesktopAndReveal()
        }
    }

    private fun addDesktopControls() {
        if (controlOverlay != null) return
        val density = resources.displayMetrics.density

        fun controlButton(label: String) = Button(this).apply {
            isAllCaps = false
            minWidth = 0
            minHeight = 0
            textSize = 12f
            setTextColor(Color.WHITE)
            setPadding((12 * density).toInt(), 0, (12 * density).toInt(), 0)
            backgroundTintList = ColorStateList.valueOf(Color.argb(220, 28, 38, 52))
            elevation = 6 * density
            text = label
        }

        val dragHandle = controlButton("⋮").apply {
            contentDescription = getString(R.string.desktop_controls_drag)
            setPadding((8 * density).toInt(), 0, (8 * density).toInt(), 0)
        }
        val keyboardButton = controlButton(getString(R.string.desktop_control_keyboard)).apply {
            setOnClickListener { showKeyboard() }
        }
        // DroidDesk inicia como teléfono/tablet.
        // El usuario puede activar Puntero desde el menú cuando lo necesite.
        inputModeButton = controlButton(inputModeLabel()).apply {
            contentDescription = inputModeLabel()

            setOnClickListener {
                inputController?.nextMode()
                text = inputModeLabel()
                contentDescription = text
                updateSensitivityVisibility()

                Toast.makeText(
                    this@DesktopActivity,
                    getString(R.string.input_mode_changed, text),
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }

        val screenshotButton =
            controlButton(getString(R.string.desktop_control_screenshot)).apply {
                setOnClickListener { takeDesktopScreenshot() }
            }
        sensitivityButton = controlButton(sensitivityButtonLabel()).apply {
            contentDescription = getString(R.string.trackpad_sensitivity)
            setPadding((10 * density).toInt(), 0, (10 * density).toInt(), 0)
            setOnClickListener { toggleSensitivityPanel() }
        }
        sensitivityPanel = buildSensitivityPanel(density)
        val hideButton = controlButton("−").apply {
            contentDescription = getString(R.string.desktop_controls_hide)
            setOnClickListener { setControlsCollapsed(true) }
            setPadding((9 * density).toInt(), 0, (9 * density).toInt(), 0)
        }

        val controlRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(dragHandle, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, (42 * density).toInt(),
            ))
            addView(inputModeButton, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                (42 * density).toInt(),
            ))
            addView(keyboardButton, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                (42 * density).toInt(),
            ))
            addView(screenshotButton, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                (42 * density).toInt(),
            ))
            addView(sensitivityButton, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                (42 * density).toInt(),
            ))
            addView(hideButton, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, (42 * density).toInt(),
            ))
        }

        // El panel de sensibilidad solo pertenece al modo Puntero y no cubre los controles.
        controlOverlay = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.END
            addView(controlRow, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT,
            ))
            addView(sensitivityPanel, LinearLayout.LayoutParams(
                sensitivityPanelWidth(density), LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = (6 * density).toInt() })
            // A dragged overlay that grows (panel opened, rotation) must stay inside the screen.
            addOnLayoutChangeListener { view, _, _, _, _, _, _, _, _ ->
                view.x = view.x.coerceIn(0f, (placeholder.width - view.width).coerceAtLeast(0).toFloat())
                view.y = view.y.coerceIn(0f, (placeholder.height - view.height).coerceAtLeast(0).toFloat())
            }
        }
        updateSensitivityVisibility()

        collapsedControl = controlButton("☰").apply {
            contentDescription = getString(R.string.desktop_controls_show)
            // Keep this measured so switching from a dragged full overlay can
            // copy absolute coordinates without placing the restore handle off-screen.
            visibility = View.INVISIBLE
        }

        val overlayParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.END,
        ).apply {
            rightMargin = (8 * density).toInt()
            topMargin = (52 * density).toInt()
        }
        val collapsedParams = FrameLayout.LayoutParams(
            (48 * density).toInt(),
            (42 * density).toInt(),
            Gravity.TOP or Gravity.END,
        ).apply {
            rightMargin = overlayParams.rightMargin
            topMargin = overlayParams.topMargin
        }

        placeholder.addView(controlOverlay, overlayParams)
        placeholder.addView(collapsedControl, collapsedParams)
        controlOverlay?.visibility = View.INVISIBLE
        collapsedControl?.visibility =
            if (desktopRevealed) View.VISIBLE else View.INVISIBLE
        dragHandle.setOnTouchListener(dragListener(controlOverlay!!))
        collapsedControl?.setOnTouchListener(dragListener(collapsedControl!!) {
            setControlsCollapsed(false)
        })
        controlOverlay?.bringToFront()
    }

    private fun sensitivityButtonLabel(): String =
        "${inputController?.sensitivityPercent ?: TrackpadSensitivity.DEFAULT}%"

    /** Fits a phone in portrait: at most 300dp, never wider than the screen minus margins. */
    private fun sensitivityPanelWidth(density: Float): Int =
        minOf((300 * density).toInt(), resources.displayMetrics.widthPixels - (16 * density).toInt())

    private fun buildSensitivityPanel(density: Float): LinearLayout {
        val pad = (12 * density).toInt()
        val title = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 13f
            text = sensitivityLabel(inputController?.sensitivityPercent ?: TrackpadSensitivity.DEFAULT)
        }
        val slider = SeekBar(this).apply {
            contentDescription = getString(R.string.trackpad_sensitivity)
            max = TrackpadSensitivity.stopCount() - 1
            progress = TrackpadSensitivity.stopIndex(inputController?.sensitivityPercent ?: TrackpadSensitivity.DEFAULT)
            progressTintList = ColorStateList.valueOf(Color.rgb(96, 165, 250))
            thumbTintList = ColorStateList.valueOf(Color.WHITE)
        }
        val ends = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(TextView(this@DesktopActivity).apply {
                text = "${TrackpadSensitivity.MIN}%"
                setTextColor(Color.LTGRAY)
                textSize = 11f
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(TextView(this@DesktopActivity).apply {
                text = "${TrackpadSensitivity.MAX}%"
                setTextColor(Color.LTGRAY)
                textSize = 11f
                gravity = Gravity.END
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
        fun show(percent: Int) {
            title.text = sensitivityLabel(percent)
            sensitivityButton?.text = "$percent%"
        }
        slider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            // Applied and saved on every step, while the thumb moves: no restart of anything.
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                val controller = inputController ?: return
                show(controller.setSensitivity(TrackpadSensitivity.percentAt(progress)))
            }
            override fun onStartTrackingTouch(bar: SeekBar) {}
            override fun onStopTrackingTouch(bar: SeekBar) {}
        })
        val reset = Button(this).apply {
            isAllCaps = false
            minWidth = 0
            minHeight = 0
            textSize = 12f
            setTextColor(Color.WHITE)
            backgroundTintList = ColorStateList.valueOf(Color.argb(255, 45, 60, 80))
            text = "Reset to ${TrackpadSensitivity.DEFAULT}%"
            setOnClickListener {
                val controller = inputController ?: return@setOnClickListener
                val value = controller.resetSensitivity()
                slider.progress = TrackpadSensitivity.stopIndex(value)
                show(value)
            }
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            background = GradientDrawable().apply {
                cornerRadius = 12 * density
                setColor(Color.argb(235, 28, 38, 52))
            }
            elevation = 6 * density
            visibility = View.GONE
            addView(title)
            addView(slider, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, (40 * density).toInt(),
            ))
            addView(ends)
            addView(reset, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, (38 * density).toInt(),
            ).apply {
                gravity = Gravity.END
                topMargin = (6 * density).toInt()
            })
        }
    }

    private fun toggleSensitivityPanel() {
        val panel = sensitivityPanel ?: return
        panel.visibility = if (panel.visibility == View.VISIBLE) View.GONE else View.VISIBLE
    }

    /** Sensitivity only makes sense in Trackpad mode: hide the button and panel in Touch mode. */
    private fun updateSensitivityVisibility() {
        val trackpad = inputController?.isTrackpadMode ?: true
        sensitivityButton?.visibility = if (trackpad) View.VISIBLE else View.GONE
        if (!trackpad) sensitivityPanel?.visibility = View.GONE
    }

    private fun dragListener(target: View, onTap: (() -> Unit)? = null): View.OnTouchListener {
        var downRawX = 0f
        var downRawY = 0f
        var startX = 0f
        var startY = 0f
        var dragged = false
        val threshold = resources.displayMetrics.density * 6

        return View.OnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    startX = target.x
                    startY = target.y
                    dragged = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downRawX
                    val dy = event.rawY - downRawY
                    if (kotlin.math.abs(dx) > threshold || kotlin.math.abs(dy) > threshold) {
                        dragged = true
                    }
                    target.x = (startX + dx).coerceIn(0f, (placeholder.width - target.width).coerceAtLeast(0).toFloat())
                    target.y = (startY + dy).coerceIn(0f, (placeholder.height - target.height).coerceAtLeast(0).toFloat())
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!dragged) onTap?.invoke()
                    true
                }
                else -> false
            }
        }
    }

    private fun setControlsCollapsed(collapsed: Boolean) {
        if (collapsed) sensitivityPanel?.visibility = View.GONE
        val from = if (collapsed) controlOverlay else collapsedControl
        val to = if (collapsed) collapsedControl else controlOverlay
        to?.x = from?.x ?: 0f
        to?.y = from?.y ?: 0f
        from?.visibility = View.INVISIBLE
        to?.visibility = View.VISIBLE
        to?.bringToFront()
    }

    private fun maybeShowKeyboardForTextCursor() {
        textCursorProbe.probe { isText ->
            if (!isText) return@probe

            runOnUiThread {
                if (!isFinishing && !isDestroyed && hasWindowFocus()) {
                    showKeyboard()
                }
            }
        }
    }

    private fun showKeyboard() {
        val view = lorieView ?: return
        val inputMethod = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        view.requestFocus()
        inputMethod.restartInput(view)
        view.post {
            inputMethod.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
        }
    }


    /**
     * Flujo de teclado tipo PC.
     *
     * Print Screen pertenece a DroidDesk.
     * Las demás teclas físicas se ofrecen primero al motor X11.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_SYSRQ) {
            if (event.action == KeyEvent.ACTION_UP) {
                takeDesktopScreenshot()
            }
            return true
        }

        when (event.keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP,
            KeyEvent.KEYCODE_VOLUME_DOWN,
            KeyEvent.KEYCODE_VOLUME_MUTE,
            KeyEvent.KEYCODE_POWER,
            KeyEvent.KEYCODE_CAMERA ->
                return super.dispatchKeyEvent(event)
        }

        if (TermuxMainActivity.getInstance().handleKey(event)) {
            return true
        }

        return super.dispatchKeyEvent(event)
    }

    /**
     * Captura solamente el escritorio X11.
     * Los controles flotantes Android de DroidDesk no aparecen en la imagen.
     */
    private fun takeDesktopScreenshot() {
        val view = lorieView ?: return

        val width = view.width
        val height = view.height

        if (width <= 0 || height <= 0) {
            Toast.makeText(
                this,
                R.string.desktop_screenshot_failed,
                Toast.LENGTH_SHORT,
            ).show()
            return
        }

        val bitmap = Bitmap.createBitmap(
            width,
            height,
            Bitmap.Config.ARGB_8888,
        )

        PixelCopy.request(
            view,
            bitmap,
            { result ->
                if (result != PixelCopy.SUCCESS) {
                    bitmap.recycle()
                    Log.e(TAG, "PixelCopy failed result=$result")
                    Toast.makeText(
                        this,
                        R.string.desktop_screenshot_failed,
                        Toast.LENGTH_SHORT,
                    ).show()
                    return@request
                }

                Thread({
                    val saved = runCatching {
                        saveDesktopScreenshot(bitmap)
                    }.onFailure {
                        Log.e(TAG, "Failed to save desktop screenshot", it)
                    }.getOrDefault(false)

                    bitmap.recycle()

                    runOnUiThread {
                        Toast.makeText(
                            this,
                            if (saved) {
                                R.string.desktop_screenshot_saved
                            } else {
                                R.string.desktop_screenshot_failed
                            },
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                }, "DroidDeskScreenshot").start()
            },
            Handler(Looper.getMainLooper()),
        )
    }

    private fun saveDesktopScreenshot(bitmap: Bitmap): Boolean {
        val stamp = SimpleDateFormat(
            "yyyyMMdd-HHmmss",
            Locale.US,
        ).format(Date())

        val name = "DroidDesk-$stamp.png"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(
                    MediaStore.Images.Media.RELATIVE_PATH,
                    "${Environment.DIRECTORY_PICTURES}/DroidDesk",
                )
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }

            val uri = contentResolver.insert(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                values,
            ) ?: return false

            val written = runCatching {
                contentResolver.openOutputStream(uri)?.use { stream ->
                    bitmap.compress(
                        Bitmap.CompressFormat.PNG,
                        100,
                        stream,
                    )
                } ?: false
            }.getOrDefault(false)

            if (!written) {
                contentResolver.delete(uri, null, null)
                return false
            }

            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            contentResolver.update(uri, values, null, null)

            Log.i(
                TAG,
                "Screenshot saved Pictures/DroidDesk/$name",
            )

            return true
        }

        val root = getExternalFilesDir(
            Environment.DIRECTORY_PICTURES
        ) ?: return false

        val directory = java.io.File(
            root,
            "DroidDesk",
        ).apply {
            mkdirs()
        }

        val output = java.io.File(directory, name)

        return runCatching {
            java.io.FileOutputStream(output).use { stream ->
                bitmap.compress(
                    Bitmap.CompressFormat.PNG,
                    100,
                    stream,
                )
            }
        }.getOrDefault(false)
    }

    private fun startDesktopSessionIfRequested() {
        if (!shouldStartSession) return
        shouldStartSession = false
        Thread({
            Log.i(TAG, "Starting Linux desktop session after X server connection")
            try {
                if (sessionMode == "chroot") {
                    chrootRuntime.startSession(desktopEnv)
                } else {
                    linuxRuntime.startSession(desktopEnv, "x11")
                }
                val ready = if (sessionMode == "chroot") {
                    chrootRuntime.waitForDesktopReady(desktopEnv)
                } else {
                    linuxRuntime.waitForDesktopReady(desktopEnv)
                }
                revealDesktop(ready)
            } catch (error: Throwable) {
                Log.e(TAG, "Desktop session failed", error)
                revealDesktop(false)
            }
        }, "LinuxDesktopSession").start()
    }

    private fun waitForDesktopAndReveal() {
        Thread({
            val ready = if (sessionMode == "chroot") {
                chrootRuntime.waitForDesktopReady(desktopEnv)
            } else {
                linuxRuntime.waitForDesktopReady(desktopEnv)
            }
            revealDesktop(ready)
        }, "LinuxDesktopReady").start()
    }

    private fun showLoadingOverlay() {
        val density = resources.displayMetrics.density
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }

        runCatching {
            assets.open("flutter_assets/assets/icons/logo.png").use { stream ->
                ImageView(this).apply {
                    setImageBitmap(BitmapFactory.decodeStream(stream))
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    content.addView(
                        this,
                        LinearLayout.LayoutParams((112 * density).toInt(), (112 * density).toInt()).apply {
                            bottomMargin = (26 * density).toInt()
                        },
                    )
                }
            }
        }.onFailure { Log.w(TAG, "Loading logo unavailable", it) }

        content.addView(TextView(this).apply {
            text = "DroidDesk"
            textSize = 28f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        })
        content.addView(ProgressBar(this).apply {
            isIndeterminate = true
            indeterminateTintList = ColorStateList.valueOf(Color.WHITE)
            contentDescription = getString(R.string.desktop_loading_description)
        }, LinearLayout.LayoutParams(
            (42 * density).toInt(),
            (42 * density).toInt(),
        ).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            topMargin = (20 * density).toInt()
        })
        loadingStatus = TextView(this).apply {
            text = loadingMessages.first()
            textSize = 14f
            setTextColor(Color.rgb(190, 198, 215))
            gravity = Gravity.CENTER
        }.also { status ->
            content.addView(status, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = (14 * density).toInt() })
        }
        loadingEstimate = TextView(this).apply {
            text = loadingRemainingText(estimatedLoadingSeconds)
            textSize = 12f
            setTextColor(Color.rgb(130, 143, 164))
            gravity = Gravity.CENTER
            contentDescription = text
        }.also { estimate ->
            content.addView(estimate, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = (8 * density).toInt() })
        }

        loadingOverlay = FrameLayout(this).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.rgb(12, 18, 32), Color.rgb(4, 7, 14)),
            )
            addView(content, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER,
            ))
        }.also { overlay ->
            placeholder.addView(overlay, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ))
        }
        loadingStartedAt = android.os.SystemClock.elapsedRealtime()
        loadingMessageHandler.postDelayed(loadingMessageTicker, 5_000)
        loadingMessageHandler.postDelayed(loadingEstimateTicker, 1_000)
    }

    private fun revealDesktop(ready: Boolean) {
        runOnUiThread {
            if (desktopRevealed || isFinishing || isDestroyed) return@runOnUiThread
            desktopRevealed = true
            loadingMessageHandler.removeCallbacks(loadingMessageTicker)
            loadingMessageHandler.removeCallbacks(loadingEstimateTicker)
            if (!ready) Log.w(TAG, "Revealing desktop after readiness timeout")
            Log.i(TAG, "Desktop revealed ready=$ready")
            loadingOverlay?.animate()
                ?.alpha(0f)
                ?.setDuration(350)
                ?.withEndAction {
                    loadingOverlay?.visibility = View.GONE

                    controlOverlay?.visibility = View.INVISIBLE

                    collapsedControl?.visibility = View.VISIBLE

                    collapsedControl?.bringToFront()

                    lorieView?.requestFocus()
                }
                ?.start()
        }
    }

    private fun showX11Error(message: String, error: Throwable?) {
        Log.e(TAG, message, error)
        Toast.makeText(this, getString(R.string.x11_error, message), Toast.LENGTH_LONG).show()
    }

    private fun loadingRemainingText(seconds: Int): String =
        resources.getQuantityString(R.plurals.desktop_loading_remaining, seconds, seconds)

    private fun inputModeLabel(): String = getString(
        if (inputController?.isTrackpadMode == false) R.string.input_mode_touch else R.string.input_mode_trackpad,
    )

    private fun sensitivityLabel(percent: Int): String =
        getString(R.string.trackpad_sensitivity_value, percent)

    override fun onDestroy() {
        clipboardSync?.stop()
        clipboardSync = null
        loadingMessageHandler.removeCallbacks(loadingMessageTicker)
        loadingMessageHandler.removeCallbacks(loadingEstimateTicker)
        surfaceCallback?.let { callback -> lorieView?.holder?.removeCallback(callback) }
        surfaceCallback = null
        inputController?.dispose()
        textCursorProbe.dispose()
        inputController = null
        x11ServiceClient?.disconnect()
        x11ServiceClient = null
        if (active?.get() === this) active = null
        super.onDestroy()
    }
}
