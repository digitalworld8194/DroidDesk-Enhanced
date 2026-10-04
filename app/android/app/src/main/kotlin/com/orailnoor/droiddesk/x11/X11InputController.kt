package com.orailnoor.droiddesk.x11

import android.content.Context
import android.view.MotionEvent
import android.view.View
import com.termux.x11.LorieView
import com.termux.x11.MainActivity
import com.termux.x11.input.InputEventSender
import com.termux.x11.input.InputModes
import com.termux.x11.input.TouchInputHandler
import com.termux.x11.input.TrackpadSensitivity

/** Connects LorieView to the gesture/input implementation imported from Termux:X11. */
class X11InputController(
    private val lorieView: LorieView,
    private val onPrimaryActivation: (() -> Unit)? = null,
) {
    private val inputHandler = TouchInputHandler(
        MainActivity.getInstance(),
        InputEventSender(lorieView),
    )

    private val modePrefs = lorieView.context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Restored from the last session so the button state survives activity and app restarts. */
    var mode: Int =
        InputModes.fromStored(modePrefs.getString(KEY_TOUCH_MODE, null))
        private set

    /**
     * Trackpad-mode pointer sensitivity in percent, persisted like the mode. Loaded here, before
     * init attaches the touch listeners, so the stored value drives the very first cursor move.
     */
    private val sensitivity = TrackpadSensitivity(object : TrackpadSensitivity.Store {
        override fun read(fallback: Int) = modePrefs.getInt(KEY_TRACKPAD_SENSITIVITY, fallback)
        override fun write(percent: Int) {
            modePrefs.edit().putInt(KEY_TRACKPAD_SENSITIVITY, percent).apply()
        }
    })

    val sensitivityPercent: Int
        get() = sensitivity.get()

    init {
        setMode(mode)
        MainActivity.getInstance().setKeyHandler(inputHandler::sendKeyEvent)
        lorieView.setCallback { width, height, transform ->
            inputHandler.handleInputTransformChanged(width, height, transform)
        }
        lorieView.setOnTouchListener(::handleMotionEvent)
        lorieView.setOnGenericMotionListener(::handleMotionEvent)
    }

    /** Switches Trackpad <-> Touch live, without restarting the X session. */
    fun nextMode(): Int {
        val next = InputModes.toggle(mode)
        setMode(next)
        modePrefs.edit()
            .putString(KEY_TOUCH_MODE, InputModes.toStored(next))
            .apply()
        return next
    }

    /** Sensitivity only affects Trackpad mode; Touch mode keeps direct pointing. */
    val isTrackpadMode: Boolean
        get() = mode == InputModes.TRACKPAD

    /**
     * Applies a new pointer sensitivity live (clamped to 25..300 %) and persists it. Only the
     * trackpad ballistics change: no X, session or mode restart. Returns the value in effect.
     */
    fun setSensitivity(percent: Int): Int {
        val value = sensitivity.set(percent)
        MainActivity.getPrefs().trackpadSensitivity.put(value)
        inputHandler.setTrackpadSensitivity(value)
        return value
    }

    fun resetSensitivity(): Int = setSensitivity(TrackpadSensitivity.DEFAULT)

    fun dispose() {
        // Never leave a drag's button pressed in X when the view goes away.
        inputHandler.releaseButtons()
        MainActivity.getInstance().setKeyHandler(null)
        lorieView.setOnTouchListener(null)
        lorieView.setOnGenericMotionListener(null)
        lorieView.setCallback(null)
    }

    private fun setMode(newMode: Int) {
        mode = newMode
        val prefs = MainActivity.getPrefs()
        prefs.touchMode.put(newMode.toString())
        prefs.trackpadSensitivity.put(sensitivityPercent)
        inputHandler.reloadPreferences(prefs)
    }

    private var fingerDownX = 0f
    private var fingerDownY = 0f
    private var fingerDownAt = 0L

    private fun handleMotionEvent(view: View, event: MotionEvent): Boolean {
        val action = event.actionMasked
        val toolType = event.getToolType(event.actionIndex)

        if (toolType == MotionEvent.TOOL_TYPE_FINGER &&
            action == MotionEvent.ACTION_DOWN
        ) {
            fingerDownX = event.x
            fingerDownY = event.y
            fingerDownAt = event.eventTime
        }

        val handled = inputHandler.handleTouchEvent(lorieView, view, event)

        val fingerTap =
            toolType == MotionEvent.TOOL_TYPE_FINGER &&
                action == MotionEvent.ACTION_UP &&
                event.eventTime - fingerDownAt <= TAP_MAX_DURATION_MS &&
                kotlin.math.abs(event.x - fingerDownX) <= tapSlopPx &&
                kotlin.math.abs(event.y - fingerDownY) <= tapSlopPx

        val primaryMouseRelease =
            toolType == MotionEvent.TOOL_TYPE_MOUSE &&
                action == MotionEvent.ACTION_BUTTON_RELEASE &&
                event.actionButton == MotionEvent.BUTTON_PRIMARY

        if (fingerTap || primaryMouseRelease) {
            /*
             * Let X11 process the click/touch and update its cursor first.
             * Hover alone never reaches this path.
             */
            lorieView.postDelayed(
                { onPrimaryActivation?.invoke() },
                TEXT_CURSOR_SETTLE_MS,
            )
        }

        return handled
    }

    private val tapSlopPx: Float
        get() = TAP_SLOP_DP * lorieView.resources.displayMetrics.density

    companion object {
        const val DISPLAY_SCALE_PERCENT = 200
        private const val PREFS_NAME = "droiddesk_input"
        private const val KEY_TOUCH_MODE = "touch_mode"
        private const val KEY_TRACKPAD_SENSITIVITY = "trackpad_sensitivity"

        private const val TAP_SLOP_DP = 18f
        private const val TAP_MAX_DURATION_MS = 700L
        private const val TEXT_CURSOR_SETTLE_MS = 90L

        /** Must run before LorieView is measured so Xwayland starts at the scaled resolution. */
        fun configureDisplayScale() {
            MainActivity.getPrefs().apply {
                displayResolutionMode.put("scaled")
                displayScale.put(DISPLAY_SCALE_PERCENT)
                displayStretch.put(true)
                scaleTouchpad.put(true)
            }
        }
    }
}
