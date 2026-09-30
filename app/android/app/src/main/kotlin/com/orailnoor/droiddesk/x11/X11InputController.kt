package com.orailnoor.droiddesk.x11

import android.content.Context
import android.view.MotionEvent
import android.view.View
import com.termux.x11.LorieView
import com.termux.x11.MainActivity
import com.termux.x11.input.InputEventSender
import com.termux.x11.input.InputModes
import com.termux.x11.input.PointerAcceleration
import com.termux.x11.input.TouchInputHandler

/** Connects LorieView to the gesture/input implementation imported from Termux:X11. */
class X11InputController(private val lorieView: LorieView) {
    private val inputHandler = TouchInputHandler(
        MainActivity.getInstance(),
        InputEventSender(lorieView),
    )

    private val modePrefs = lorieView.context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Restored from the last session so the button state survives activity and app restarts. */
    var mode: Int = InputModes.fromStored(modePrefs.getString(KEY_TOUCH_MODE, null))
        private set

    /** Trackpad-mode pointer sensitivity in percent, persisted like the mode. */
    var sensitivityPercent: Int = modePrefs.getInt(
        KEY_TRACKPAD_SENSITIVITY, PointerAcceleration.DEFAULT_SENSITIVITY_PERCENT,
    ).coerceIn(PointerAcceleration.MIN_SENSITIVITY_PERCENT, PointerAcceleration.MAX_SENSITIVITY_PERCENT)
        private set

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
        modePrefs.edit().putString(KEY_TOUCH_MODE, InputModes.toStored(next)).apply()
        return next
    }

    fun modeLabel(): String = InputModes.label(mode)

    /** Applies a new pointer sensitivity live (clamped to 25..300 %) and persists it. */
    fun setSensitivity(percent: Int) {
        sensitivityPercent = percent.coerceIn(
            PointerAcceleration.MIN_SENSITIVITY_PERCENT, PointerAcceleration.MAX_SENSITIVITY_PERCENT,
        )
        modePrefs.edit().putInt(KEY_TRACKPAD_SENSITIVITY, sensitivityPercent).apply()
        setMode(mode)
    }

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

    private fun handleMotionEvent(view: View, event: MotionEvent): Boolean =
        inputHandler.handleTouchEvent(lorieView, view, event)

    companion object {
        const val DISPLAY_SCALE_PERCENT = 200
        private const val PREFS_NAME = "droiddesk_input"
        private const val KEY_TOUCH_MODE = "touch_mode"
        private const val KEY_TRACKPAD_SENSITIVITY = "trackpad_sensitivity"

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
