package org.fcitx.fcitx5.android.input

import android.app.Dialog
import android.content.Context
import android.hardware.display.DisplayManager
import android.inputmethodservice.InputMethodService
import android.inputmethodservice.InputMethodService.Insets
import android.os.Build
import android.os.SystemClock
import android.text.InputType
import android.view.Gravity
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InlineSuggestionsResponse
import android.view.inputmethod.InputMethodSubtype
import androidx.annotation.Keep
import androidx.annotation.RequiresApi
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.setViewTreeLifecycleOwner
import com.xros.container.os.input.ime.IMXRImeBinder
import com.xros.container.os.input.ime.MXRImeClientManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.launch
import org.fcitx.fcitx5.android.core.CapabilityFlags
import org.fcitx.fcitx5.android.core.FcitxAPI
import org.fcitx.fcitx5.android.core.FcitxEvent
import org.fcitx.fcitx5.android.core.FormattedText
import org.fcitx.fcitx5.android.core.SubtypeManager
import org.fcitx.fcitx5.android.daemon.FcitxConnection
import org.fcitx.fcitx5.android.daemon.FcitxDaemon
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.prefs.ManagedPreference
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.input.cursor.CursorRange
import org.fcitx.fcitx5.android.input.cursor.CursorTracker
import org.fcitx.fcitx5.android.utils.InputMethodUtil
import org.fcitx.fcitx5.android.utils.inputMethodManager
import org.fcitx.fcitx5.android.utils.withBatchEdit
import splitties.bitflags.hasFlag
import timber.log.Timber
import java.sql.Time
import kotlin.math.max

class FcitxInputMethodServiceBridge (val service: FcitxInputMethodService){
    private lateinit var mxrImeClientManager: MXRImeClientManager
    private lateinit var fcitx: FcitxConnection

    private var jobs = Channel<Job>(capacity = Channel.UNLIMITED)

    private lateinit var pkgNameCache: PackageNameCache

    private var inputView: InputView? = null

    private var inputViewHelper : InputViewHelper? = null

    private var capabilityFlags = CapabilityFlags.DefaultFlags


    private val selection = CursorTracker()
    val currentInputSelection: CursorRange
        get() = selection.latest
    private val composing = CursorRange()
    private var composingText = FormattedText.Empty

    private var cursorUpdateIndex: Int = 0

    private var highlightColor: Int = 0x66008577

    private val ignoreSystemCursor by AppPrefs.getInstance().advanced.ignoreSystemCursor

    private fun resetComposingState() {
        composing.clear()
        composingText = FormattedText.Empty
    }

    var currentVirtualDisplayId : Int = IMXRImeBinder.MXR_IME_INVALID_DISPLAY_ID
    var imeDisplayShowing: Boolean = false
    var imeWindowShowing: Boolean = false

    @Keep
    private val recreateInputViewListener = ManagedPreference.OnChangeListener<Any> { _, _ ->
        recreateInputView(ThemeManager.activeTheme)
    }

    @Keep
    private val onThemeChangeListener = ThemeManager.OnThemeChangeListener {
        recreateInputView(it)
    }

    private fun recreateInputView(theme: Theme) {
        // InputView should be created first in onCreateInputView
        // setInputView should be used to 'replace' current InputView only
        InputView(service, fcitx, theme).also {
            inputView = it
        }
    }

    private fun postJob(scope: CoroutineScope, block: suspend () -> Unit): Job {
        val job = scope.launch(start = CoroutineStart.LAZY) { block() }
        jobs.trySend(job)
        return job
    }

    /**
     * Post a fcitx operation to [jobs] to be executed
     *
     * Unlike `fcitx.runOnReady` or `fcitx.launchOnReady` where
     * subsequent operations can start if the prior operation is not finished (suspended),
     * [postFcitxJob] ensures that operations are executed sequentially.
     */
    fun postFcitxJob(block: suspend FcitxAPI.() -> Unit) =
        postJob(fcitx.lifecycleScope) { fcitx.runOnReady(block) }

    fun handleOnCrate() {
        if (!service.enableSystemInput) {
            Timber.tag(TAG).d("connect mxrImeClientManager")
            mxrImeClientManager = MXRImeClientManager(service, this)
            mxrImeClientManager.connectXRContainer()
        }
        fcitx = FcitxDaemon.connect(javaClass.name)
        service.lifecycleScope.launch {
            jobs.consumeEach { it.join() }
        }
        service.lifecycleScope.launch {
            fcitx.runImmediately { eventFlow }.collect {
                handleFcitxEvent(it)
            }
        }
        pkgNameCache = PackageNameCache(service)
        AppPrefs.getInstance().apply {
            keyboard.expandKeypressArea.registerOnChangeListener(recreateInputViewListener)
            advanced.disableAnimation.registerOnChangeListener(recreateInputViewListener)
        }
        ThemeManager.addOnChangedListener(onThemeChangeListener)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            postFcitxJob {
                SubtypeManager.syncWith(enabledIme())
            }
        }

        inputView = InputView(service, fcitx, ThemeManager.activeTheme)

        if (!service.enableSystemInput) {
            inputView!!.setViewTreeLifecycleOwner(service)
        }
    }

    private fun handleFcitxEvent(event: FcitxEvent<*>) {
        when (event) {
            is FcitxEvent.CommitStringEvent -> {
                commitText(event.data.text, event.data.cursor)
            }
            is FcitxEvent.KeyEvent -> event.data.let event@{
                if (it.states.virtual) {
                    // KeyEvent from virtual keyboard
                    when (it.unicode) {
                        '\b'.code -> handleBackspaceKey()
                        '\r'.code -> handleReturnKey()
                        else -> commitText(Char(it.unicode).toString())
                    }
                } else {
                    // KeyEvent from physical keyboard (or input method engine forwardKey)
                    // use cached event if available
//                    cachedKeyEvents.remove(it.timestamp)?.let { keyEvent ->
//                        currentInputConnection?.sendKeyEvent(keyEvent)
//                        return@event
//                    }
                    // simulate key event
                    val keyCode = it.sym.keyCode
                    if (keyCode != KeyEvent.KEYCODE_UNKNOWN) {
                        // recognized keyCode
                        val eventTime = SystemClock.uptimeMillis()
                        if (it.up) {
                            sendUpKeyEvent(eventTime, keyCode, it.states.metaState)
                        } else {
                            sendDownKeyEvent(eventTime, keyCode, it.states.metaState)
                        }
                    } else {
                        // no matching keyCode, commit character once on key down
                        if (!it.up && it.unicode > 0) {
                            commitText(Char(it.unicode).toString())
                        } else {
                            Timber.w("Unhandled Fcitx KeyEvent: $it")
                        }
                    }
                }
            }
            is FcitxEvent.ClientPreeditEvent -> {
                updateComposingText(event.data)
            }
            is FcitxEvent.DeleteSurroundingEvent -> {
                val (before, after) = event.data
                handleDeleteSurrounding(before, after)
            }
            is FcitxEvent.IMChangeEvent -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    val im = event.data.uniqueName
                    val subtype = SubtypeManager.subtypeOf(im) ?: return
                    skipNextSubtypeChange = im
                    // [^1]: notify system that input method subtype has changed
                    service.switchInputMethod(InputMethodUtil.componentName, subtype)
                }
            }
            else -> {}
        }
    }


    private fun handleDeleteSurrounding(before: Int, after: Int) {
        val ic = service.currentInputConnection ?: return
        if (before > 0) {
            selection.predictOffset(-before)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            ic.deleteSurroundingTextInCodePoints(before, after)
        } else {
            ic.deleteSurroundingText(before, after)
        }
    }

    private fun handleBackspaceKey() {
        val lastSelection = selection.latest
        if (lastSelection.isNotEmpty()) {
            selection.predict(lastSelection.start)
        } else if (lastSelection.start > 0) {
            selection.predictOffset(-1)
        }
        var currentInputEditorInfo = service.currentInputEditorInfo
        var currentInputConnection = service.currentInputConnection
        // In practice nobody (apart form ourselves) would set `privateImeOptions` to our
        // `DeleteSurroundingFlag`, leading to a behavior of simulating backspace key pressing
        // in almost every EditText.
        if (currentInputEditorInfo.privateImeOptions != DeleteSurroundingFlag ||
            currentInputEditorInfo.inputType and InputType.TYPE_MASK_CLASS == InputType.TYPE_NULL
        ) {
            service.sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
            return
        }
        if (lastSelection.isEmpty()) {
            if (lastSelection.start <= 0) {
                service.sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
                return
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                currentInputConnection.deleteSurroundingTextInCodePoints(1, 0)
            } else {
                currentInputConnection.deleteSurroundingText(1, 0)
            }
        } else {
            currentInputConnection.commitText("", 0)
        }
    }

    private fun handleReturnKey() {
        service.currentInputEditorInfo.run {
            if (inputType and InputType.TYPE_MASK_CLASS == InputType.TYPE_NULL) {
                service.sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER)
                return
            }
            if (imeOptions.hasFlag(EditorInfo.IME_FLAG_NO_ENTER_ACTION)) {
                commitText("\n")
                return
            }
            if (actionLabel?.isNotEmpty() == true && actionId != EditorInfo.IME_ACTION_UNSPECIFIED) {
                service.currentInputConnection.performEditorAction(actionId)
                return
            }
            when (val action = imeOptions and EditorInfo.IME_MASK_ACTION) {
                EditorInfo.IME_ACTION_UNSPECIFIED,
                EditorInfo.IME_ACTION_NONE -> commitText("\n")
                else -> service.currentInputConnection.performEditorAction(action)
            }
        }
    }

    fun commitText(text: String, cursor: Int = -1) {
        val ic = service.currentInputConnection ?: return
        // when composing text equals commit content, finish composing text as-is
        if (composing.isNotEmpty() && composingText.toString() == text) {
            val c = if (cursor == -1) text.length else cursor
            val target = composing.start + c
            resetComposingState()
            ic.withBatchEdit {
                if (selection.current.start != target) {
                    selection.predict(target)
                    ic.setSelection(target, target)
                }
                ic.finishComposingText()
            }
            return
        }
        // committed text should replace composing (if any), replace selected range (if any),
        // or simply prepend before cursor
        val start = if (composing.isEmpty()) selection.latest.start else composing.start
        resetComposingState()
        if (cursor == -1) {
            selection.predict(start + text.length)
            ic.commitText(text, 1)
        } else {
            val target = start + cursor
            selection.predict(target)
            ic.withBatchEdit {
                commitText(text, 1)
                setSelection(target, target)
            }
        }
    }

    private fun sendDownKeyEvent(eventTime: Long, keyEventCode: Int, metaState: Int = 0) {
        service.currentInputConnection?.sendKeyEvent(
            KeyEvent(
                eventTime,
                eventTime,
                KeyEvent.ACTION_DOWN,
                keyEventCode,
                0,
                metaState,
                KeyCharacterMap.VIRTUAL_KEYBOARD,
                0,
                KeyEvent.FLAG_SOFT_KEYBOARD or KeyEvent.FLAG_KEEP_TOUCH_MODE
            )
        )
    }

    private fun sendUpKeyEvent(eventTime: Long, keyEventCode: Int, metaState: Int = 0) {
        service.currentInputConnection?.sendKeyEvent(
            KeyEvent(
                eventTime,
                SystemClock.uptimeMillis(),
                KeyEvent.ACTION_UP,
                keyEventCode,
                0,
                metaState,
                KeyCharacterMap.VIRTUAL_KEYBOARD,
                0,
                KeyEvent.FLAG_SOFT_KEYBOARD or KeyEvent.FLAG_KEEP_TOUCH_MODE
            )
        )
    }

    fun deleteSelection() {
        val lastSelection = selection.latest
        if (lastSelection.isEmpty()) return
        selection.predict(lastSelection.start)
        service.currentInputConnection?.commitText("", 1)
    }

    fun sendCombinationKeyEvents(
        keyEventCode: Int,
        alt: Boolean = false,
        ctrl: Boolean = false,
        shift: Boolean = false
    ) {
        var metaState = 0
        if (alt) metaState = KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON
        if (ctrl) metaState = metaState or KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
        if (shift) metaState = metaState or KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON
        val eventTime = SystemClock.uptimeMillis()
        if (alt) sendDownKeyEvent(eventTime, KeyEvent.KEYCODE_ALT_LEFT)
        if (ctrl) sendDownKeyEvent(eventTime, KeyEvent.KEYCODE_CTRL_LEFT)
        if (shift) sendDownKeyEvent(eventTime, KeyEvent.KEYCODE_SHIFT_LEFT)
        sendDownKeyEvent(eventTime, keyEventCode, metaState)
        sendUpKeyEvent(eventTime, keyEventCode, metaState)
        if (shift) sendUpKeyEvent(eventTime, KeyEvent.KEYCODE_SHIFT_LEFT)
        if (ctrl) sendUpKeyEvent(eventTime, KeyEvent.KEYCODE_CTRL_LEFT)
        if (alt) sendUpKeyEvent(eventTime, KeyEvent.KEYCODE_ALT_LEFT)
    }

    fun applySelectionOffset(offsetStart: Int, offsetEnd: Int = 0) {
        val lastSelection = selection.latest
        service.currentInputConnection?.also {
            val start = max(lastSelection.start + offsetStart, 0)
            val end = max(lastSelection.end + offsetEnd, 0)
            if (start > end) return
            selection.predict(start, end)
            it.setSelection(start, end)
        }
    }

    fun cancelSelection() {
        val lastSelection = selection.latest
        if (lastSelection.isEmpty()) return
        val end = lastSelection.end
        selection.predict(end)
        service.currentInputConnection?.setSelection(end, end)
    }

    fun handleOnComputeInsets(outInsets: InputMethodService.Insets) {
        val (_, y) = intArrayOf(0, 0).also { inputView?.keyboardView?.getLocationInWindow(it) }
        outInsets.apply {
            contentTopInsets = y
            touchableInsets = Insets.TOUCHABLE_INSETS_CONTENT
            touchableRegion.setEmpty()
            visibleTopInsets = y
        }
    }

    private var firstBindInput = true
    fun handleOnBindInput() {
        val uid = service.currentInputBinding.uid
        val pkgName = pkgNameCache.forUid(uid)
        Timber.tag(TAG).d("onBindInput: uid=$uid pkg=$pkgName")
        postFcitxJob {
            // ensure InputContext has been created before focusing it
            activate(uid, pkgName)
        }
        if (firstBindInput) {
            firstBindInput = false
            // only use input method from subtype for the first `onBindInput`, because
            // 1. fcitx has `ShareInputState` option, thus reading input method from subtype
            //    everytime would ruin `ShareInputState=Program`
            // 2. im from subtype should be read once, when user changes input method from other
            //    app to a subtype of ours via system input method picker (on 34+)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                val subtype = service.inputMethodManager.currentInputMethodSubtype ?: return
                val im = SubtypeManager.inputMethodOf(subtype)
                postFcitxJob {
                    activateIme(im)
                }
            }
        }
    }


    private var skipNextSubtypeChange: String? = null
    fun handleOnCurrentInputMethodSubtypeChanged(newSubtype: InputMethodSubtype) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val im = SubtypeManager.inputMethodOf(newSubtype)
            Timber.tag(TAG).d("onCurrentInputMethodSubtypeChanged: im=$im")
            // don't change input method if this "subtype change" was our notify to system
            // see [^1]
            if (skipNextSubtypeChange == im) {
                skipNextSubtypeChange = null
                return
            }
            postFcitxJob {
                activateIme(im)
            }
        }
    }

     fun handleOnStartInput(attribute: EditorInfo, restarting: Boolean) {
        // update selection as soon as possible
        // sometimes when restarting input, onUpdateSelection happens before onStartInput, and
        // initialSel{Start,End} is outdated. but it's the client app's responsibility to send
        // right cursor position, try to workaround this would simply introduce more bugs.
        selection.resetTo(attribute.initialSelStart, attribute.initialSelEnd)
        resetComposingState()
        val flags = CapabilityFlags.fromEditorInfo(attribute)
        capabilityFlags = flags
        Timber.d("onStartInput: initialSel=${selection.current}, restarting=$restarting")
        // wait until InputContext created/activated
        postFcitxJob {
            if (restarting) {
                // when input restarts in the same editor, focus out to clear previous state
                focus(false)
                // try focus out before changing CapabilityFlags,
                // to avoid confusing state of different text fields
            }
            // EditorInfo can be different in onStartInput and onStartInputView,
            // especially in browsers
            setCapFlags(flags)
        }

    }

    private class InputViewHelper(
        val service: FcitxInputMethodService,
        val bridge: FcitxInputMethodServiceBridge) {

        private var displayContext: Context? = null
        private var view: View? = null
        private lateinit var mDialog: Dialog
        private lateinit var mRootView: View

        fun showInputView(displayId :Int) {
            val dm = service.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
            val targetDisplay = dm.getDisplay(displayId)
            Timber.tag(TAG).d("showInputView current display $displayId")
            if (targetDisplay == null) {
                Timber.e("showInputView: current Ime display is null")
                return
            }
            displayContext = service.createDisplayContext(targetDisplay)
            removeInputView()
            addInputView(false)
        }


        fun removeInputView() {
            if (view != null && displayContext != null) {
                Timber.tag(TAG).d("removeInputView")
                val wm = displayContext?.getSystemService(Context.WINDOW_SERVICE) as WindowManager
                wm.removeView(view)
                view = null
                displayContext = null
            }
        }

        fun addInputView(focusable: Boolean) {
            view = bridge.inputView
            val lp = WindowManager.LayoutParams()
            lp.type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            lp.width = WindowManager.LayoutParams.MATCH_PARENT;
            lp.height = WindowManager.LayoutParams.WRAP_CONTENT;
            lp.gravity = Gravity.BOTTOM;
            lp.flags = if (focusable) VIEW_FLAG_FOCUSABLE else VIEW_FLAG_NOT_FOCUSABLE
            val wm = displayContext?.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            wm.addView(bridge.inputView, lp)

            if (focusable) {
                bridge.inputView?.requestFocus()
            }
        }

        companion object {
            @Suppress("DEPRECATION")
            private const val VIEW_FLAG_FOCUSABLE = WindowManager.LayoutParams.FLAG_FULLSCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_LAYOUT_INSET_DECOR or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL

            @Suppress("DEPRECATION")
            private const val VIEW_FLAG_NOT_FOCUSABLE =
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_FULLSCREEN or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                        WindowManager.LayoutParams.FLAG_LAYOUT_INSET_DECOR or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
        }
    }






    @RequiresApi(Build.VERSION_CODES.Q)
    fun handleOnStartInputView(info: EditorInfo, restarting: Boolean) {
        Timber.d("onStartInputView: restarting=$restarting")
        postFcitxJob {
            focus(true)
        }
        // because onStartInputView will always be called after onStartInput,
        // editorInfo and capFlags should be up-to-date
        inputView?.startInput(info, capabilityFlags, restarting)


        if (!service.enableSystemInput) {
            if(currentVirtualDisplayId == IMXRImeBinder.MXR_IME_INVALID_DISPLAY_ID) {
                currentVirtualDisplayId = mxrImeClientManager.getImeDisplay();
                if (currentVirtualDisplayId == IMXRImeBinder.MXR_IME_INVALID_DISPLAY_ID) return
            }

            Timber.tag(TAG).d("Will show inputView id: $currentVirtualDisplayId")
            inputViewHelper  = InputViewHelper(service, this)
            inputViewHelper!!.showInputView(currentVirtualDisplayId)
            inputView!!.visibility  = View.VISIBLE
            imeWindowShowing = true
            mxrImeClientManager.sendClientWindowStatus(imeWindowShowing)

        }
    }

    fun handleOnUpdateSelection(
        oldSelStart: Int,
        oldSelEnd: Int,
        newSelStart: Int,
        newSelEnd: Int,
        candidatesStart: Int,
        candidatesEnd: Int
    ) {
        // onUpdateSelection can left behind when user types quickly enough, eg. long press backspace
        cursorUpdateIndex += 1
        Timber.d("onUpdateSelection: old=[$oldSelStart,$oldSelEnd] new=[$newSelStart,$newSelEnd]")
        handleCursorUpdate(newSelStart, newSelEnd, cursorUpdateIndex)
        inputView?.updateSelection(newSelStart, newSelEnd)
    }

    private fun handleCursorUpdate(
        newSelStart: Int, newSelEnd: Int, updateIndex: Int) {
        if (selection.consume(newSelStart, newSelEnd)) {
            return // do nothing if prediction matches
        } else {
            // cursor update can't match any prediction: it's treated as a user input
            selection.resetTo(newSelStart, newSelEnd)
        }
        // skip selection range update, we only care about selection cursor (zero width) here
        if (newSelStart != newSelEnd) return
        // do reset if composing is empty && input panel is not empty
        if (composing.isEmpty()) {
            postFcitxJob {
                if (!isEmpty()) {
                    Timber.d("handleCursorUpdate: reset")
                    reset()
                }
            }
            return
        }
        // check if cursor inside composing text
        if (composing.contains(newSelStart)) {
            if (ignoreSystemCursor) return
            // fcitx cursor position is relative to client preedit (composing text)
            val position = newSelStart - composing.start
            // move fcitx cursor when cursor position changed
            if (position != composingText.cursor) {
                // cursor in InvokeActionEvent counts by "UTF-8 characters"
                val codePointPosition = composingText.codePointCountUntil(position)
                postFcitxJob {
                    if (updateIndex != cursorUpdateIndex) return@postFcitxJob
                    Timber.d("handleCursorUpdate: move fcitx cursor to $codePointPosition")
                    moveCursor(codePointPosition)
                }
            }
        } else {
            Timber.d("handleCursorUpdate: focus out/in")
            resetComposingState()
            // cursor outside composing range, finish composing as-is
            service.currentInputConnection?.finishComposingText()
            // `fcitx.reset()` here would commit preedit after new cursor position
            // since we have `ClientUnfocusCommit`, focus out and in would do the trick
            postFcitxJob {
                focus(false)
                focus(true)
            }
        }
    }

    private fun updateComposingText(text: FormattedText) {
        val ic = service.currentInputConnection ?: return
        val lastSelection = selection.latest
        ic.beginBatchEdit()
        if (!composingText.spanEquals(text)) {
            // composing text content changed
            Timber.d("updateComposingText: '$text' lastSelection=$lastSelection")
            if (text.isEmpty()) {
                if (composing.isEmpty()) {
                    // do not reset saved selection range when incoming composing
                    // and saved composing range are both empty:
                    // composing.start is invalid when it's empty.
                    selection.predict(lastSelection.start)
                } else {
                    // clear composing text, put cursor at start of original composing
                    selection.predict(composing.start)
                    composing.clear()
                }
                ic.setComposingText("", 1)
            } else {
                val start = if (composing.isEmpty()) lastSelection.start else composing.start
                composing.update(start, start + text.length)
                // skip cursor reposition when:
                // - preedit cursor is at the end
                // - cursor position is invalid
                if (text.cursor == text.length || text.cursor < 0) {
                    selection.predict(composing.end)
                    ic.setComposingText(text.toSpannedString(highlightColor), 1)
                } else {
                    val p = text.cursor + composing.start
                    selection.predict(p)
                    ic.setComposingText(text.toSpannedString(highlightColor), 1)
                    ic.setSelection(p, p)
                }
            }
            Timber.d("updateComposingText: composing=$composing")
        } else {
            // composing text content is up-to-date
            // update cursor only when it's not empty AND cursor position is valid
            if (text.length > 0 && text.cursor >= 0) {
                val p = text.cursor + composing.start
                if (p != lastSelection.start) {
                    Timber.d("updateComposingText: set Android selection ($p, $p)")
                    ic.setSelection(p, p)
                    selection.predict(p)
                }
            }
        }
        composingText = text
        ic.endBatchEdit()
    }

    fun finishComposing() {
        val ic = service.currentInputConnection ?: return
        if (composing.isEmpty()) return
        composing.clear()
        composingText = FormattedText.Empty
        ic.finishComposingText()
    }

    @RequiresApi(Build.VERSION_CODES.R)
    fun handlOnInlineSuggestionsResponse(response: InlineSuggestionsResponse): Boolean{
        return inputView?.handleInlineSuggestions(response) ?: false
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    fun handleOnFinishInputView(finishingInput: Boolean) {
        Timber.d("onFinishInputView: finishingInput=$finishingInput")
        service.currentInputConnection?.finishComposingText()
        resetComposingState()
        postFcitxJob {
            focus(false)
        }
        inputView?.finishInput()
        if (!service.enableSystemInput) {
            if (imeWindowShowing) {
                Timber.d("onFinishInputView, will remove")
                inputView?.visibility = View.GONE
                inputViewHelper?.removeInputView()
                imeWindowShowing = false
                mxrImeClientManager.sendClientWindowStatus(imeWindowShowing)
            }
        }
    }

     fun handleOnFinishInput() {
        capabilityFlags = CapabilityFlags.DefaultFlags
    }

    fun handleOnUnbindInput(uid: Int) {
        cursorUpdateIndex = 0
        // currentInputBinding can be null on some devices under some special Multi-screen mode
        postFcitxJob {
            deactivate(uid)
        }
    }


    fun handleOnDestroy() {
        if (!service.enableSystemInput) {
            mxrImeClientManager.destroy()
        }
        AppPrefs.getInstance().apply {
            keyboard.expandKeypressArea.unregisterOnChangeListener(recreateInputViewListener)
            advanced.disableAnimation.unregisterOnChangeListener(recreateInputViewListener)
        }
        ThemeManager.removeOnChangedListener(onThemeChangeListener)
        // Fcitx might be used in super.onDestroy()
        FcitxDaemon.disconnect(javaClass.name)
    }

    fun handleOnCreateInputView() :InputView{
        return inputView!!
    }

    companion object {
        const val DeleteSurroundingFlag = "org.fcitx.fcitx5.android.DELETE_SURROUNDING"
        private const val TAG = "XRFcitxInputMethodServiceBridge: "
    }
}