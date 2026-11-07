package com.pleos.airdesk

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import org.junit.Assert.*
import org.junit.Test

class DesktopKeyboardControllerTest {
    private fun ready(text: String = ""): DesktopKeyboardController = DesktopKeyboardController().apply {
        registerEditor("document", TextFieldValue(text, TextRange(text.length)))
        registerEditor("note")
        focusEditor("document")
    }
    private fun DesktopKeyboardController.keys(keys: String) { keys.forEach { press("Key${it.uppercaseChar()}") } }
    @Test fun typingWithoutRegisteredFocusNeverChangesAnEditor() {
        val controller = DesktopKeyboardController()
        controller.registerEditor("document")
        controller.press("KeyA")
        controller.focusEditor("unknown")
        assertEquals("", controller.value("document").text)
        assertNull(controller.focusedEditorId)
    }
    @Test fun koreanTextExposesComposingRegionAndBackspaceSteps() {
        val c = ready()
        c.keys("gks")
        assertEquals("한", c.value("document").text)
        assertEquals(TextRange(0,1), c.value("document").composition)
        c.press("Backspace"); assertEquals("하", c.value("document").text)
        c.press("Backspace"); assertEquals("ㅎ", c.value("document").text)
        c.press("Backspace"); assertEquals("", c.value("document").text)
    }
    @Test fun koreanCompositionSwitchesSyllablesAndCommitsOnLanguageChange() {
        val c = ready(); c.keys("gksrmf")
        assertEquals("한글", c.value("document").text)
        assertEquals(TextRange(1,2), c.value("document").composition)
        c.press("Lang")
        assertNull(c.value("document").composition)
        c.keys("abc"); assertEquals("한글abc", c.value("document").text)
    }
    @Test fun koreanSelectionReplacementAndCaretMoveDoNotComposeIntoOldText() {
        val c = ready("앞뒤")
        c.updateEditorValue("document", TextFieldValue("앞뒤", TextRange(1)))
        c.keys("rk"); assertEquals("앞가뒤", c.value("document").text)
        c.updateEditorValue("document", c.value("document").copy(selection=TextRange(0,1)))
        assertNull(c.value("document").composition)
        c.keys("sk"); assertEquals("나가뒤", c.value("document").text)
    }
    @Test fun englishCapsShiftNumbersAndPunctuationUseDesktopSemantics() {
        val c=ready(); c.press("Lang"); c.keys("a"); c.press("CapsLock"); c.keys("b")
        c.press("ShiftLeft"); c.keys("c"); c.press("Digit1"); c.press("BracketLeft")
        assertEquals("aBc!{", c.value("document").text)
    }
    @Test fun shortcutsCopyCutPasteUndoRedoStayInsideFocusedEditor() {
        val c=ready("hello")
        c.press("ControlLeft"); c.press("KeyA"); c.press("KeyC"); c.press("KeyX")
        assertEquals("", c.value("document").text)
        c.press("KeyV"); assertEquals("hello", c.value("document").text)
        c.press("KeyZ"); assertEquals("", c.value("document").text)
        c.press("KeyY"); assertEquals("hello", c.value("document").text)
        assertEquals("", c.value("note").text)
    }
    @Test fun switchingFocusReleasesEveryModifierAndCommitsComposition() {
        val c=ready(); c.keys("rk"); c.press("ShiftLeft"); c.press("ControlLeft"); c.press("AltLeft"); c.press("CapsLock")
        c.focusEditor("note")
        assertFalse(c.shift); assertFalse(c.ctrl); assertFalse(c.alt); assertFalse(c.capsLock)
        assertNull(c.value("document").composition)
        c.keys("sk"); assertEquals("나",c.value("note").text)
        assertEquals("가", c.value("document").text)
    }
    @Test fun unicodeNavigationAndDeletionNeverSplitSurrogatePairs() {
        val c=ready("A😀B")
        c.press("ArrowLeft"); c.press("Backspace")
        assertEquals("AB", c.value("document").text)
        c.updateEditorValue("document", TextFieldValue("A😀B",TextRange(1)))
        c.press("Delete"); assertEquals("AB", c.value("document").text)
        c.updateEditorValue("document", TextFieldValue("A😀B",TextRange(2)))
        assertEquals(TextRange(1),c.value("document").selection)
    }
    @Test fun shiftArrowsHomeEndAndPageNavigationSelectAndMoveWithinDocument() {
        val c=ready("one\ntwo\nthree")
        c.press("Home"); assertEquals(TextRange(8),c.value("document").selection)
        c.press("ShiftLeft"); c.press("ArrowRight")
        assertEquals(TextRange(8,9),c.value("document").selection)
        c.press("ShiftLeft"); c.press("PageUp")
        assertEquals(TextRange(1),c.value("document").selection)
        c.press("ControlLeft"); c.press("End")
        assertEquals(TextRange(13),c.value("document").selection)
    }
    @Test fun enterIsNewlineAndTabEscapeHelpAreExplicitAppCallbacks() {
        var tabReverse:Boolean?=null; var escaped=0;var help=0
        val c=DesktopKeyboardController(onTab={tabReverse=it},onEscape={escaped++},onHelp={help++})
        c.registerEditor("document");c.focusEditor("document")
        c.press("Enter");assertEquals("\n",c.value("document").text)
        c.press("ShiftLeft");c.press("Tab");assertEquals(true,tabReverse)
        c.press("F1");assertEquals(1,help)
        c.press("F2");assertEquals(1,help)
        c.press("Escape");assertEquals(1,escaped);assertNull(c.focusedEditorId)
    }
    @Test fun hideAndLifecycleResetReleaseStateWithoutDeletingText() {
        val c=ready();c.keys("rk");c.press("ShiftLeft");c.hide()
        assertFalse(c.visible);assertFalse(c.shift);assertNull(c.focusedEditorId)
        assertEquals("가",c.value("document").text);assertNull(c.value("document").composition)
        c.show();c.press("KeyS");assertEquals("가",c.value("document").text)
        c.focusEditor("document");c.keys("sk");assertEquals("가나",c.value("document").text)
    }
    @Test fun clipboardFailureDoesNotDeleteSelectionOrExposeContent() {
        val c=DesktopKeyboardController(object:DesktopClipboard{
            override fun readText():String?=throw IllegalStateException("sensitive")
            override fun writeText(text:String){throw IllegalStateException(text)}
        })
        c.registerEditor("document",TextFieldValue("private",TextRange(0,7)));c.focusEditor("document")
        c.press("ControlLeft");c.press("KeyX")
        assertEquals("private",c.value("document").text)
        assertFalse(c.status.contains("private"));assertFalse(c.status.contains("sensitive"))
    }
    @Test fun layoutRetainsDesktopGroupsAndUnsupportedFunctionKeysAreDisabled() {
        val codes=DesktopKeyboardLayout.allKeys.map {it.code}.toSet()
        assertTrue((1..12).all {"F$it" in codes})
        assertTrue(setOf("Escape","Tab","CapsLock","ShiftLeft","ControlLeft","AltLeft","Delete","Backspace","Home","End","PageUp","PageDown","ArrowUp","ArrowDown","ArrowLeft","ArrowRight").all {it in codes})
        assertEquals(26,codes.count {it.startsWith("Key")})
        val c=ready();assertTrue(c.enabled("F1"));assertTrue((2..12).all {!c.enabled("F$it")})
    }
    @Test fun editorLimitRejectsOverflowWithoutCorruptingActiveKoreanComposition() {
        val c=ready("x".repeat(DesktopKeyboardController.MAX_EDITOR_LENGTH-1))
        c.keys("rks");assertTrue(c.value("document").text.endsWith("간"))
        c.keys("s");assertEquals(DesktopKeyboardController.MAX_EDITOR_LENGTH,c.value("document").text.length)
        c.press("Backspace");assertTrue(c.value("document").text.endsWith("가"))
        val valid=c.value("document")
        c.updateEditorValue("document",TextFieldValue("x".repeat(DesktopKeyboardController.MAX_EDITOR_LENGTH+1)))
        assertEquals(valid,c.value("document"))
        assertFalse(c.enabled("Windows"))
    }
    @Test fun localContextActionsPreserveModifierStateAndUnregisterClearsFocus() {
        val c=ready("hello");c.press("ControlLeft");c.press("AltLeft");c.press("ShiftLeft")
        c.selectAll();c.copySelection();c.cutSelection();assertEquals("",c.value("document").text)
        c.paste();assertEquals("hello",c.value("document").text)
        assertTrue(c.ctrl);assertTrue(c.alt);assertTrue(c.shift)
        c.undoEdit();assertEquals("",c.value("document").text)
        c.undoEdit(redo=true);assertEquals("hello",c.value("document").text)
        c.unregisterEditor("document");assertNull(c.focusedEditorId)
        assertFalse(c.ctrl);assertFalse(c.alt);assertFalse(c.shift)
    }
}
