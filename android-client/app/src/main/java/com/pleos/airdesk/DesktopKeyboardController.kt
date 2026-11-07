package com.pleos.airdesk

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue

interface DesktopClipboard {
    fun readText(): String?
    fun writeText(text: String)
}

class InMemoryDesktopClipboard : DesktopClipboard {
    private var content: String? = null
    override fun readText(): String? = content
    override fun writeText(text: String) { content = text }
}

enum class DesktopLanguage { KOREAN, ENGLISH }

/** App-owned, focused editing only. No platform keystrokes, network or input-content logs. */
class DesktopKeyboardController(
    private val clipboard: DesktopClipboard = InMemoryDesktopClipboard(),
    private val onTab: (reverse: Boolean) -> Unit = {},
    private val onEscape: () -> Unit = {},
    private val onHelp: () -> Unit = {},
) {
    private class Editor(var value: TextFieldValue) {
        val undo = ArrayDeque<TextFieldValue>()
        val redo = ArrayDeque<TextFieldValue>()
    }
    private val editors = linkedMapOf<String, Editor>()
    private val composer = DesktopHangulComposer()
    private var ownCompositionStart: Int? = null
    private var revision by mutableStateOf(0)
    var focusedEditorId by mutableStateOf<String?>(null); private set
    var language by mutableStateOf(DesktopLanguage.KOREAN); private set
    var shift by mutableStateOf(false); private set
    var ctrl by mutableStateOf(false); private set
    var alt by mutableStateOf(false); private set
    var capsLock by mutableStateOf(false); private set
    var visible by mutableStateOf(true); private set
    var status by mutableStateOf("DEMO · 편집기를 선택하세요"); private set
    var pageLines: Int = 8

    fun registerEditor(id: String, initial: TextFieldValue = TextFieldValue()) {
        require(id.isNotBlank())
        require(validUnicode(initial.text) && initial.text.length <= MAX_EDITOR_LENGTH)
        if (id !in editors) { editors[id] = Editor(clean(initial)); revision++ }
    }
    fun unregisterEditor(id: String) {
        if (id == focusedEditorId) resetTransient()
        editors.remove(id); revision++
    }
    fun value(id: String): TextFieldValue {
        @Suppress("UNUSED_VARIABLE") val observe = revision
        return editors[id]?.value ?: TextFieldValue()
    }
    fun updateEditorValue(id: String, value: TextFieldValue) {
        val editor = editors[id] ?: return
        if (!validUnicode(value.text) || value.text.length > MAX_EDITOR_LENGTH) return
        val next = clean(value)
        if (editor.value == next) return
        val customCompositionWasActive = id == focusedEditorId && ownCompositionStart != null
        if (id == focusedEditorId) finishComposition()
        if (editor.value.text != next.text) record(editor)
        editor.value = if (customCompositionWasActive) next.copy(composition = null) else next
        revision++
    }
    fun focusEditor(id: String?) {
        if (id != null && id !in editors) return
        if (focusedEditorId == id) return
        finishComposition(); releaseModifiers(); focusedEditorId = id
        status = if (id == null) "DEMO · 편집기를 선택하세요" else "DEMO · 앱 내부 편집기 선택됨"
        revision++
    }
    fun show() { visible = true }
    fun hide() { resetTransient(); visible = false }
    fun resetTransient(clearFocus: Boolean = true) {
        finishComposition(); releaseModifiers()
        if (clearFocus) focusedEditorId = null
        status = "DEMO · 입력 상태 해제"
        revision++
    }
    fun selectAll() { editor()?.let { shortcut(it, "KeyA") } }
    fun copySelection() { editor()?.let { shortcut(it, "KeyC") } }
    fun cutSelection() { editor()?.let { shortcut(it, "KeyX") } }
    fun paste() { editor()?.let { shortcut(it, "KeyV") } }
    fun undoEdit(redo: Boolean = false) { finishComposition(); editor()?.let { undo(it, redo) } }
    private fun releaseModifiers() { shift = false; ctrl = false; alt = false; capsLock = false }
    private fun editor(): Editor? = focusedEditorId?.let(editors::get)
    private fun finishComposition() {
        composer.reset(); ownCompositionStart = null
        editor()?.let { it.value = it.value.copy(composition = null) }
        revision++
    }
    private fun record(editor: Editor) {
        if (editor.undo.size >= 100) editor.undo.removeFirst()
        editor.undo.addLast(editor.value.copy(composition = null)); editor.redo.clear()
    }
    private fun change(editor: Editor, value: TextFieldValue) { editor.value = clean(value); revision++ }

    fun enabled(code: String): Boolean = when {
        code.matches(Regex("F(?:[2-9]|1[0-2])")) -> false
        code in setOf("F1", "Escape", "Tab", "Lang") -> true
        else -> editor() != null && (code in NAVIGATION || code in EDIT_KEYS || code in PUNCTUATION ||
            (code.length == 4 && code.startsWith("Key") && code.last() in 'A'..'Z') ||
            (code.length == 6 && code.startsWith("Digit") && code.last() in '0'..'9'))
    }
    fun latched(code: String): Boolean = when (code) {
        "ShiftLeft", "ShiftRight" -> shift
        "ControlLeft", "ControlRight" -> ctrl
        "AltLeft", "AltRight" -> alt
        "CapsLock" -> capsLock
        else -> false
    }

    /** A validated AIR Down uses physically held modifiers for this action only.
     * Touch/Pinch latches stay separate; AIR loss cannot leave a modifier latched. */
    fun pressAir(code: String, heldShift: Boolean = false, heldCtrl: Boolean = false, heldAlt: Boolean = false) {
        if (code in setOf("ShiftLeft", "ShiftRight", "ControlLeft", "ControlRight", "AltLeft", "AltRight")) return
        val previousShift = shift; val previousCtrl = ctrl; val previousAlt = alt
        val previousFocus = focusedEditorId
        shift = heldShift; ctrl = heldCtrl; alt = heldAlt
        try { press(code) }
        finally {
            // Focus-changing actions intentionally release all transient state.
            if (focusedEditorId == previousFocus && visible) {
                shift = previousShift; ctrl = previousCtrl; alt = previousAlt
            } else { shift = false; ctrl = false; alt = false }
        }
    }

    fun press(code: String) {
        if (!visible || !enabled(code)) { status = "지원하지 않거나 선택된 편집기가 없는 키입니다"; return }
        when (code) {
            "F1" -> { finishComposition(); onHelp(); return }
            "Escape" -> { resetTransient(); onEscape(); return }
            "Tab" -> { finishComposition(); val reverse = shift; releaseModifiers(); onTab(reverse); return }
            "Lang" -> { finishComposition(); language = if (language == DesktopLanguage.KOREAN) DesktopLanguage.ENGLISH else DesktopLanguage.KOREAN; return }
            "ShiftLeft", "ShiftRight" -> { shift = !shift; return }
            "ControlLeft", "ControlRight" -> { finishComposition(); ctrl = !ctrl; return }
            "AltLeft", "AltRight" -> { finishComposition(); alt = !alt; return }
            "CapsLock" -> { capsLock = !capsLock; return }
        }
        val editor = editor() ?: return
        if (alt) { status = "Alt 조합은 이 DEMO 편집기에서 지원하지 않습니다"; return }
        if (code in NAVIGATION) { navigate(editor, code); return }
        if (ctrl) { shortcut(editor, code); return }
        when (code) {
            "Backspace" -> backspace(editor)
            "Delete" -> { finishComposition(); delete(editor, backwards = false) }
            "Enter" -> { finishComposition(); insert(editor, "\n") }
            "Space" -> { finishComposition(); insert(editor, " ") }
            else -> {
                val jamo = if (language == DesktopLanguage.KOREAN) DesktopHangulComposer.fromKey(code, shift) else null
                if (jamo != null) compose(editor, jamo)
                else character(code)?.let { finishComposition(); insert(editor, it) }
            }
        }
    }

    private fun compose(editor: Editor, jamo: Char) {
        val before = editor.value
        val start = ownCompositionStart ?: before.selection.min
        val end = if (ownCompositionStart != null) before.selection.end else before.selection.max
        val previous = composer.checkpoint()
        val step = composer.input(jamo)
        val replacement = step.committed + step.composing
        val text = before.text.replaceRange(start, end, replacement)
        if (text.length > MAX_EDITOR_LENGTH) {
            composer.restore(previous); status = "DEMO 편집기 길이 제한에 도달했습니다"; return
        }
        record(editor)
        val cursor = start + replacement.length
        ownCompositionStart = if (step.composing.isEmpty()) null else start + step.committed.length
        change(editor, TextFieldValue(text, TextRange(cursor), ownCompositionStart?.let { TextRange(it, cursor) }))
    }

    private fun backspace(editor: Editor) {
        val start = ownCompositionStart
        if (start != null && editor.value.selection.collapsed && editor.value.composition != null) {
            record(editor)
            val active = composer.backspace()
            val text = editor.value.text.replaceRange(start, editor.value.selection.end, active)
            val cursor = start + active.length
            if (active.isEmpty()) ownCompositionStart = null
            change(editor, TextFieldValue(text, TextRange(cursor), if (active.isEmpty()) null else TextRange(start, cursor)))
        } else { finishComposition(); delete(editor, backwards = true) }
    }

    private fun insert(editor: Editor, text: String) {
        if (!validUnicode(text)) return
        val value = editor.value
        if (value.text.length - (value.selection.max - value.selection.min) + text.length > MAX_EDITOR_LENGTH) { status = "DEMO 편집기 길이 제한에 도달했습니다"; return }
        record(editor)
        val start = value.selection.min
        change(editor, TextFieldValue(value.text.replaceRange(start, value.selection.max, text), TextRange(start + text.length)))
    }
    private fun delete(editor: Editor, backwards: Boolean) {
        val value = editor.value
        var start = value.selection.min; var end = value.selection.max
        if (start == end) {
            if (backwards) start = previousPoint(value.text, start) else end = nextPoint(value.text, end)
        }
        if (start == end) return
        record(editor)
        change(editor, TextFieldValue(value.text.removeRange(start, end), TextRange(start)))
    }

    private fun shortcut(editor: Editor, code: String) {
        finishComposition()
        try {
            when (code) {
                "KeyA" -> change(editor, editor.value.copy(selection = TextRange(0, editor.value.text.length)))
                "KeyC", "KeyX" -> {
                    val value = editor.value
                    if (!value.selection.collapsed) {
                        clipboard.writeText(value.text.substring(value.selection.min, value.selection.max))
                        if (code == "KeyX") delete(editor, backwards = false)
                        status = "선택 영역을 앱의 클립보드 동작으로 처리했습니다"
                    }
                }
                "KeyV" -> clipboard.readText()?.let { if (validUnicode(it)) insert(editor, it) }
                "KeyZ" -> undo(editor, redo = shift)
                "KeyY" -> undo(editor, redo = true)
                else -> status = "이 Ctrl 조합은 앱 내부 편집기에서 지원하지 않습니다"
            }
        } catch (_: Exception) { status = "클립보드 작업을 완료하지 못했습니다" }
    }
    private fun undo(editor: Editor, redo: Boolean) {
        val source = if (redo) editor.redo else editor.undo
        val target = if (redo) editor.undo else editor.redo
        if (source.isEmpty()) return
        target.addLast(editor.value.copy(composition = null))
        change(editor, source.removeLast().copy(composition = null))
    }
    private fun navigate(editor: Editor, code: String) {
        finishComposition()
        val value = editor.value
        val text = value.text
        val caret = value.selection.end
        val next = when (code) {
            "ArrowLeft" -> if (!shift && !value.selection.collapsed) value.selection.min else if (ctrl) wordLeft(text, caret) else previousPoint(text, caret)
            "ArrowRight" -> if (!shift && !value.selection.collapsed) value.selection.max else if (ctrl) wordRight(text, caret) else nextPoint(text, caret)
            "Home" -> if (ctrl) 0 else lineStart(text, caret)
            "End" -> if (ctrl) text.length else lineEnd(text, caret)
            "ArrowUp" -> vertical(text, caret, -1)
            "ArrowDown" -> vertical(text, caret, 1)
            "PageUp" -> vertical(text, caret, -pageLines.coerceIn(1, 100))
            "PageDown" -> vertical(text, caret, pageLines.coerceIn(1, 100))
            else -> caret
        }
        change(editor, value.copy(selection = if (shift) TextRange(value.selection.start, next) else TextRange(next)))
    }

    private fun character(code: String): String? {
        if (code.startsWith("Key") && code.length == 4 && code.last() in 'A'..'Z')
            return if (shift.xor(capsLock)) code.last().toString() else code.last().lowercase()
        if (code.startsWith("Digit") && code.length == 6 && code.last().isDigit()) {
            val digit = code.last()
            return if (shift) SHIFT_DIGITS[digit - '0'].toString() else digit.toString()
        }
        val pair = PUNCTUATION[code] ?: return null
        return if (shift) pair.second else pair.first
    }

    companion object {
        const val MAX_EDITOR_LENGTH = 32768
        private val NAVIGATION = setOf("ArrowLeft", "ArrowRight", "ArrowUp", "ArrowDown", "Home", "End", "PageUp", "PageDown")
        private val EDIT_KEYS = setOf("Enter", "Space", "Backspace", "Delete", "ShiftLeft", "ShiftRight", "ControlLeft", "ControlRight", "AltLeft", "AltRight", "CapsLock")
        private const val SHIFT_DIGITS = ")!@#$%^&*("
        private val PUNCTUATION = mapOf("Backquote" to ("`" to "~"), "Minus" to ("-" to "_"), "Equal" to ("=" to "+"),
            "BracketLeft" to ("[" to "{"), "BracketRight" to ("]" to "}"), "Backslash" to ("\\" to "|"),
            "Semicolon" to (";" to ":"), "Quote" to ("'" to "\""), "Comma" to ("," to "<"), "Period" to ("." to ">"), "Slash" to ("/" to "?"))
        private fun previousPoint(text: String, at: Int): Int = if (at <= 0) 0 else text.offsetByCodePoints(at, -1)
        private fun nextPoint(text: String, at: Int): Int = if (at >= text.length) text.length else text.offsetByCodePoints(at, 1)
        private fun lineStart(text: String, at: Int): Int = if (at == 0) 0 else text.lastIndexOf('\n', at - 1) + 1
        private fun lineEnd(text: String, at: Int): Int = text.indexOf('\n', at).let { if (it < 0) text.length else it }
        private fun vertical(text: String, at: Int, lines: Int): Int {
            val column = at - lineStart(text, at)
            var start = lineStart(text, at)
            repeat(kotlin.math.abs(lines)) {
                start = if (lines < 0) { if (start == 0) 0 else lineStart(text, start - 1) }
                    else { val end = lineEnd(text, start); if (end == text.length) start else end + 1 }
            }
            return boundary(text, (start + column).coerceAtMost(lineEnd(text, start)))
        }
        private fun wordLeft(text: String, at: Int): Int { var p = at; while (p > 0 && text[p-1].isWhitespace()) p=previousPoint(text,p); while(p>0 && !text[p-1].isWhitespace()) p=previousPoint(text,p); return p }
        private fun wordRight(text: String, at: Int): Int { var p = at; while(p<text.length && !text[p].isWhitespace()) p=nextPoint(text,p); while(p<text.length && text[p].isWhitespace()) p=nextPoint(text,p); return p }
        private fun boundary(text: String, at: Int): Int {
            val p = at.coerceIn(0,text.length)
            return if (p in 1 until text.length && text[p].isLowSurrogate() && text[p-1].isHighSurrogate()) p-1 else p
        }
        private fun clean(value: TextFieldValue): TextFieldValue = value.copy(selection = TextRange(boundary(value.text,value.selection.start), boundary(value.text,value.selection.end)),
            composition = value.composition?.let { TextRange(boundary(value.text,it.start),boundary(value.text,it.end)) }?.takeUnless { it.collapsed })
        private fun validUnicode(text: String): Boolean {
            var i=0
            while(i<text.length) { val c=text[i++]; if(c.isHighSurrogate()) { if(i>=text.length || !text[i++].isLowSurrogate()) return false } else if(c.isLowSurrogate()) return false }
            return true
        }
    }
}
