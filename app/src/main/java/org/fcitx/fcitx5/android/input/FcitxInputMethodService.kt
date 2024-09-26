/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.text.InputType
import android.util.LruCache
import android.util.Size
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.inputmethod.CursorAnchorInfo
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InlineSuggestionsRequest
import android.view.inputmethod.InlineSuggestionsResponse
import android.view.inputmethod.InputMethodSubtype
import android.widget.FrameLayout
import android.widget.inline.InlinePresentationSpec
import androidx.annotation.Keep
import androidx.annotation.RequiresApi
import androidx.autofill.inline.UiVersions
import androidx.autofill.inline.common.ImageViewStyle
import androidx.autofill.inline.common.TextViewStyle
import androidx.autofill.inline.common.ViewStyle
import androidx.autofill.inline.v1.InlineSuggestionUi
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.launch
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.CapabilityFlags
import org.fcitx.fcitx5.android.core.FcitxAPI
import org.fcitx.fcitx5.android.core.FcitxEvent
import org.fcitx.fcitx5.android.core.FormattedText
import org.fcitx.fcitx5.android.core.KeyStates
import org.fcitx.fcitx5.android.core.KeySym
import org.fcitx.fcitx5.android.core.SubtypeManager
import org.fcitx.fcitx5.android.daemon.FcitxConnection
import org.fcitx.fcitx5.android.daemon.FcitxDaemon
import org.fcitx.fcitx5.android.data.InputFeedbacks
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.prefs.ManagedPreference
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.input.cursor.CursorRange
import org.fcitx.fcitx5.android.input.cursor.CursorTracker
import org.fcitx.fcitx5.android.utils.InputMethodUtil
import org.fcitx.fcitx5.android.utils.alpha
import org.fcitx.fcitx5.android.utils.inputMethodManager
import org.fcitx.fcitx5.android.utils.withBatchEdit
import splitties.bitflags.hasFlag
import splitties.dimensions.dp
import splitties.resources.styledColor
import timber.log.Timber
import kotlin.math.max


class FcitxInputMethodService : LifecycleInputMethodService() {

    private var bridge: FcitxInputMethodServiceBridge? = null;
    private val cachedKeyEvents = LruCache<Int, KeyEvent>(78)
    private var cachedKeyEventIndex = 0
    private val inlineSuggestions by AppPrefs.getInstance().keyboard.inlineSuggestions

    /**
     * true: Put inputView to inputWindow created by Android system
     * false: Put inputView to virtual display created by XRContainer
     */
    var enableSystemInput:Boolean = false

    fun getCurrentInputSelection(): CursorRange {
        return bridge!!.currentInputSelection
    }
    /**
     * Post a fcitx operation to [jobs] to be executed
     *
     * Unlike `fcitx.runOnReady` or `fcitx.launchOnReady` where
     * subsequent operations can start if the prior operation is not finished (suspended),
     * [postFcitxJob] ensures that operations are executed sequentially.
     */
    fun postFcitxJob(block: suspend FcitxAPI.() -> Unit) {
        bridge?.postFcitxJob(block)
    }

    override fun onCreate() {
        Timber.tag(TAG).d("onCreate")
        bridge = FcitxInputMethodServiceBridge(this)
        super.onCreate()
        bridge!!.handleOnCrate();
    }

    fun commitText(text: String, cursor: Int = -1) {
        bridge?.commitText(text, cursor)
    }

    fun deleteSelection() {
        bridge?.deleteSelection()
    }

    fun sendCombinationKeyEvents(
        keyEventCode: Int,
        alt: Boolean = false,
        ctrl: Boolean = false,
        shift: Boolean = false
    ) {
        bridge?.sendCombinationKeyEvents(keyEventCode, alt, ctrl, shift)
    }

    fun applySelectionOffset(offsetStart: Int, offsetEnd: Int = 0) {
        bridge?.applySelectionOffset(offsetStart, offsetEnd)
    }

    fun cancelSelection() {
        bridge?.cancelSelection()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        Timber.tag(TAG).d("onConfigurationChanged")
        super.onConfigurationChanged(newConfig)
        postFcitxJob { reset() }
    }

    override fun onWindowShown() {
        Timber.tag(TAG).d("onWindowShown")
        super.onWindowShown()
        InputFeedbacks.syncSystemPrefs()
    }

    override fun onCreateInputView(): View {
        Timber.tag(TAG).d("onCreateInputView")
        // onCreateInputView will be called once, when the input area is first displayed,
        // during each onConfigurationChanged period.
        // That is, onCreateInputView would be called again, after system dark mode changes,
        // or screen orientation changes.
        return if (enableSystemInput) {
            bridge!!.handleOnCreateInputView()
        } else {
            View(applicationContext)
        }
    }

    override fun setInputView(view: View) {
        Timber.tag(TAG).d("setInputView")
        if (enableSystemInput) {
//            try {
//                highlightColor = view.styledColor(android.R.attr.colorAccent).alpha(0.4f)
//            } catch (e: Exception) {
//                Timber.w("Device does not support android.R.attr.colorAccent which it should have.")
//            }
            window.window!!.decorView
                .findViewById<FrameLayout>(android.R.id.inputArea)
                .updateLayoutParams<ViewGroup.LayoutParams> {
                    height = ViewGroup.LayoutParams.MATCH_PARENT
                }
            super.setInputView(view)
            view.updateLayoutParams<ViewGroup.LayoutParams> {
                height = ViewGroup.LayoutParams.MATCH_PARENT
            }
        } else {
            super.setInputView(view)
        }
    }

    override fun onConfigureWindow(win: Window, isFullscreen: Boolean, isCandidatesOnly: Boolean) {
        Timber.tag(TAG).d("onConfigureWindow")
        val inflater = this.getSystemService(Context.LAYOUT_INFLATER_SERVICE) as LayoutInflater
        if (enableSystemInput) {
            win.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
    }

    override fun onComputeInsets(outInsets: Insets) {
        Timber.tag(TAG).d("onComputeInsets")
        bridge?.handleOnComputeInsets(outInsets)
    }

    // TODO: candidate view for physical keyboard input
    // always show InputView since we do not support physical keyboard input without it yet
    @SuppressLint("MissingSuperCall")
    override fun onEvaluateInputViewShown() = true

    override fun onEvaluateFullscreenMode() = false

    private fun forwardKeyEvent(event: KeyEvent): Boolean {
        // reason to use a self increment index rather than timestamp:
        // KeyUp and KeyDown events actually can happen on the same time
        val timestamp = cachedKeyEventIndex++
        cachedKeyEvents.put(timestamp, event)
        val up = event.action == KeyEvent.ACTION_UP
        val states = KeyStates.fromKeyEvent(event)
        val charCode = event.unicodeChar
        // try send charCode first, allow upper case and lower case character generating different KeySym
        // skip \t, because it's charCode is different from KeySym
        // skip \n, because fcitx wants \r for return
        if (charCode > 0 && charCode != '\t'.code && charCode != '\n'.code) {
            postFcitxJob {
                sendKey(charCode, states.states, up, timestamp)
            }
            return true
        }
        val keySym = KeySym.fromKeyEvent(event)
        if (keySym != null) {
            postFcitxJob {
                sendKey(keySym, states, up, timestamp)
            }
            return true
        }
        Timber.d("Skipped KeyEvent: $event")
        return false
    }

    fun dealCachedKeyEvent(timestamp: Int) : Boolean {
        cachedKeyEvents.remove(timestamp)?.let { keyEvent ->
            currentInputConnection?.sendKeyEvent(keyEvent)
            return true
        }
        return false
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        Timber.tag(TAG).d("onKeyDown")
        return forwardKeyEvent(event) || super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        Timber.tag(TAG).d("onKeyUp")
        return forwardKeyEvent(event) || super.onKeyUp(keyCode, event)
    }


    override fun onBindInput() {
        Timber.tag(TAG).d("onBindInput")
        bridge?.handleOnBindInput()
    }

    /**
     * When input method changes internally (eg. via language switch key or keyboard shortcut),
     * we want to notify system that subtype has changed (see [^1]), then ignore the incoming
     * [onCurrentInputMethodSubtypeChanged] callback.
     * Input method should only be changed when user changes subtype in system input method picker
     * manually.
     */
    override fun onCurrentInputMethodSubtypeChanged(newSubtype: InputMethodSubtype) {
        Timber.tag(TAG).d("onCurrentInputMethodSubtypeChanged")
        super.onCurrentInputMethodSubtypeChanged(newSubtype)
        bridge?.handleOnCurrentInputMethodSubtypeChanged(newSubtype)
    }

    override fun onStartInput(attribute: EditorInfo, restarting: Boolean) {
        Timber.tag(TAG).d("onStartInput")
        bridge?.handleOnStartInput(attribute, restarting)
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    override fun onStartInputView(info: EditorInfo, restarting: Boolean) {
        Timber.tag(TAG).d("onStartInputView: restarting=$restarting")
        bridge?.handleOnStartInputView(info, restarting)
    }


    override fun onUpdateSelection(
        oldSelStart: Int,
        oldSelEnd: Int,
        newSelStart: Int,
        newSelEnd: Int,
        candidatesStart: Int,
        candidatesEnd: Int
    ) {
        Timber.tag(TAG).d("onUpdateSelection")
        bridge?.handleOnUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
    }

    override fun onUpdateCursorAnchorInfo(info: CursorAnchorInfo) {
        Timber.tag(TAG).d("onUpdateCursorAnchorInfo")
        // CursorAnchorInfo focus more on screen coordinates rather than selection
    }

    /**
     * Finish composing text and leave cursor position as-is.
     * Also updates internal composing state of [FcitxInputMethodService].
     */
    fun finishComposing() {
        Timber.tag(TAG).d("finishComposing")
        bridge?.finishComposing()
    }

    @SuppressLint("RestrictedApi")
    @RequiresApi(Build.VERSION_CODES.R)
    override fun onCreateInlineSuggestionsRequest(uiExtras: Bundle): InlineSuggestionsRequest? {
        if (!inlineSuggestions) return null
        val theme = ThemeManager.activeTheme
        val chipDrawable =
            if (theme.isDark) R.drawable.bkg_inline_suggestion_dark else R.drawable.bkg_inline_suggestion_light
        val chipBg = Icon.createWithResource(this, chipDrawable).setTint(theme.keyTextColor)
        val style = InlineSuggestionUi.newStyleBuilder()
            .setSingleIconChipStyle(
                ViewStyle.Builder()
                    .setBackgroundColor(Color.TRANSPARENT)
                    .setPadding(0, 0, 0, 0)
                    .build()
            )
            .setChipStyle(
                ViewStyle.Builder()
                    .setBackground(chipBg)
                    .setPadding(dp(10), 0, dp(10), 0)
                    .build()
            )
            .setTitleStyle(
                TextViewStyle.Builder()
                    .setLayoutMargin(dp(4), 0, dp(4), 0)
                    .setTextColor(theme.keyTextColor)
                    .setTextSize(14f)
                    .build()
            )
            .setSubtitleStyle(
                TextViewStyle.Builder()
                    .setTextColor(theme.altKeyTextColor)
                    .setTextSize(12f)
                    .build()
            )
            .setStartIconStyle(
                ImageViewStyle.Builder()
                    .setTintList(ColorStateList.valueOf(theme.altKeyTextColor))
                    .build()
            )
            .setEndIconStyle(
                ImageViewStyle.Builder()
                    .setTintList(ColorStateList.valueOf(theme.altKeyTextColor))
                    .build()
            )
            .build()
        val styleBundle = UiVersions.newStylesBuilder()
            .addStyle(style)
            .build()
        val spec = InlinePresentationSpec
            .Builder(Size(0, 0), Size(Int.MAX_VALUE, Int.MAX_VALUE))
            .setStyle(styleBundle)
            .build()
        return InlineSuggestionsRequest.Builder(listOf(spec))
            .setMaxSuggestionCount(InlineSuggestionsRequest.SUGGESTION_COUNT_UNLIMITED)
            .build()
    }

    @RequiresApi(Build.VERSION_CODES.R)
    override fun onInlineSuggestionsResponse(response: InlineSuggestionsResponse): Boolean {
        if (!inlineSuggestions) return false
        return bridge?.handlOnInlineSuggestionsResponse(response) ?: false
    }

    fun nextInputMethodApp() {
        Timber.tag(TAG).d("switch nextInputMethodApp")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            switchToNextInputMethod(false)
        } else {
            @Suppress("DEPRECATION")
            inputMethodManager.switchToNextInputMethod(window.window!!.attributes.token, false)
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    override fun onFinishInputView(finishingInput: Boolean) {
        Timber.tag(TAG).d("onFinishInputView: finishingInput=$finishingInput")
        bridge?.handleOnFinishInputView(finishingInput)
    }

    override fun onFinishInput() {
        Timber.tag(TAG).d("onFinishInput")
        bridge?.handleOnFinishInput()
    }

    override fun onUnbindInput() {
        cachedKeyEvents.evictAll()
        cachedKeyEventIndex = 0
        val uid = currentInputBinding?.uid ?: return
        Timber.tag(TAG).d("onUnbindInput: uid=$uid")
        bridge?.handleOnUnbindInput(uid)
    }

    override fun onDestroy() {
        Timber.tag(TAG).d("onDestroy")
        super.onDestroy()
        bridge?.handleOnDestroy()
    }

    companion object {
        const val DeleteSurroundingFlag = "org.fcitx.fcitx5.android.DELETE_SURROUNDING"
        private const val TAG = "XRFcitxInputMethodService: "
    }

}