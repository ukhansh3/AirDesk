package com.pleos.airdesk

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

data class DesktopKeySpec(val code: String, val label: String, val units: Float = 1f)

/** Layout metadata is shared with tests so mandatory desktop groups cannot disappear in a compact mode. */
object DesktopKeyboardLayout {
    val functionRow = listOf(DesktopKeySpec("Escape", "Esc", 1.5f)) + (1..12).map { DesktopKeySpec("F$it", "F$it") }
    val mainRows: List<List<DesktopKeySpec>> = listOf(
        listOf(DesktopKeySpec("Backquote", "` ~")) + (1..9).map { DesktopKeySpec("Digit$it", "$it ${"!@#$%^&*("[it-1]}") } +
            listOf(DesktopKeySpec("Digit0", "0 )"), DesktopKeySpec("Minus", "- _"), DesktopKeySpec("Equal", "= +"), DesktopKeySpec("Backspace", "Backspace", 2f)),
        listOf(DesktopKeySpec("Tab", "Tab", 1.5f)) + letters("QWERTYUIOP") +
            listOf(DesktopKeySpec("BracketLeft", "[ {"), DesktopKeySpec("BracketRight", "] }"), DesktopKeySpec("Backslash", "\\ |", 1.5f)),
        listOf(DesktopKeySpec("CapsLock", "Caps Lock", 1.75f)) + letters("ASDFGHJKL") +
            listOf(DesktopKeySpec("Semicolon", "; :"), DesktopKeySpec("Quote", "' \""), DesktopKeySpec("Enter", "Enter", 2.25f)),
        listOf(DesktopKeySpec("ShiftLeft", "Shift", 2.25f)) + letters("ZXCVBNM") +
            listOf(DesktopKeySpec("Comma", ", <"), DesktopKeySpec("Period", ". >"), DesktopKeySpec("Slash", "/ ?"), DesktopKeySpec("ShiftRight", "Shift", 2.75f)),
        listOf(DesktopKeySpec("ControlLeft", "Ctrl", 1.5f), DesktopKeySpec("AltLeft", "Alt", 1.5f), DesktopKeySpec("Lang", "한 / 영", 1.5f),
            DesktopKeySpec("Space", "Space", 7.5f), DesktopKeySpec("AltRight", "Alt", 1.5f), DesktopKeySpec("ControlRight", "Ctrl", 1.5f)),
    )
    val navigationRows = listOf(
        listOf(DesktopKeySpec("Home", "Home"), DesktopKeySpec("End", "End"), DesktopKeySpec("Delete", "Del")),
        listOf(DesktopKeySpec("PageUp", "PgUp", 1.5f), DesktopKeySpec("PageDown", "PgDn", 1.5f)),
        listOf(DesktopKeySpec("ArrowUp", "↑")),
        listOf(DesktopKeySpec("ArrowLeft", "←"), DesktopKeySpec("ArrowDown", "↓"), DesktopKeySpec("ArrowRight", "→")),
    )
    val allKeys: List<DesktopKeySpec> get() = functionRow + mainRows.flatten() + navigationRows.flatten()
    private fun letters(keys: String) = keys.map { DesktopKeySpec("Key$it", it.toString()) }
}

/** Root supplies both scoped gesture registration and ordinary touch clicks in targetModifier. */
@Composable
fun DesktopKeyboardDock(
    controller: DesktopKeyboardController,
    modifier: Modifier = Modifier,
    targetModifier: (Modifier, String, Boolean, () -> Unit) -> Modifier,
    hoveredTargetId: String? = null,
) {
    if (!controller.visible) return
    BoxWithConstraints(modifier.background(Color(0xFF12223B), RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp)).padding(8.dp)) {
        val scroll = rememberScrollState()
        val scope = rememberCoroutineScope()
        val pagePixels = with(LocalDensity.current) { maxWidth.toPx() * .75f }.toInt().coerceAtLeast(1)
        val narrow = maxWidth < 960.dp
        val keyboardWidth = maxOf(maxWidth, 960.dp)
        Column {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(if (narrow) 4.dp else 14.dp), verticalAlignment = Alignment.CenterVertically) {
                if (narrow) {
                    Text("PC 키보드 · DEMO\n${if (controller.language == DesktopLanguage.KOREAN) "한글" else "English"} · F1 도움말", modifier = Modifier.weight(1f),
                        color = Color(0xFFC7BCFF), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    PanButton("keyboard.pan.left", "◀ 키보드", scroll.value > 0, targetModifier, hoveredTargetId) {
                        scope.launch { scroll.animateScrollTo((scroll.value - pagePixels).coerceAtLeast(0)) }
                    }
                    PanButton("keyboard.pan.right", "키보드 ▶", scroll.value < scroll.maxValue, targetModifier, hoveredTargetId) {
                        scope.launch { scroll.animateScrollTo((scroll.value + pagePixels).coerceAtMost(scroll.maxValue)) }
                    }
                } else {
                    Text("DESKTOP KEYBOARD · DEMO", color = Color(0xFFC7BCFF), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Text(if (controller.language == DesktopLanguage.KOREAN) "한글 두벌식" else "English", color = Color(0xFF8DE0B2), fontSize = 12.sp)
                    Text("F1 도움말 · F2–F12 미지원 · 앱 내부 단축키", color = Color(0xFFA2B4CF), fontSize = 12.sp)
                }
            }
            val navWidth = 164.dp
            val unit = (keyboardWidth - navWidth - 12.dp) / 15f
            Column(Modifier.padding(top = 6.dp).horizontalScroll(scroll).width(keyboardWidth), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    DesktopKeyboardLayout.functionRow.forEach { key -> KeyCap(key, unit, controller, targetModifier, hoveredTargetId) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        DesktopKeyboardLayout.mainRows.forEach { row ->
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                row.forEach { key -> KeyCap(key, unit, controller, targetModifier, hoveredTargetId) }
                            }
                        }
                    }
                    Column(Modifier.width(navWidth), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        DesktopKeyboardLayout.navigationRows.take(2).forEach { row ->
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                row.forEach { key -> KeyCap(key, (navWidth + 4.dp) / 3f, controller, targetModifier, hoveredTargetId) }
                            }
                        }
                        Spacer(Modifier.height(48.dp))
                        Row(Modifier.width(navWidth), horizontalArrangement = Arrangement.Center) {
                            KeyCap(DesktopKeyboardLayout.navigationRows[2].single(), (navWidth + 4.dp) / 3f, controller, targetModifier, hoveredTargetId)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            DesktopKeyboardLayout.navigationRows[3].forEach { key -> KeyCap(key, (navWidth + 4.dp) / 3f, controller, targetModifier, hoveredTargetId) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PanButton(id: String, label: String, enabled: Boolean,
    targetModifier: (Modifier, String, Boolean, () -> Unit) -> Modifier, hoveredTargetId: String?, onClick: () -> Unit,
) {
    val bg = if (hoveredTargetId == id && enabled) Color(0xFF405679) else Color(0xFF243956)
    val base = Modifier.width(88.dp).height(48.dp).focusProperties { canFocus = false }
        .background(bg, RoundedCornerShape(6.dp)).border(1.dp, Color(0xFF637796), RoundedCornerShape(6.dp))
        .semantics { contentDescription = label + if (enabled) "" else " · 이동 끝" }
    Box(targetModifier(base, id, enabled, onClick), contentAlignment = Alignment.Center) {
        Text(label, color = if (enabled) Color.White else Color(0xFF718199), fontSize = 12.sp)
    }
}

@Composable
private fun KeyCap(
    key: DesktopKeySpec,
    unit: Dp,
    controller: DesktopKeyboardController,
    targetModifier: (Modifier, String, Boolean, () -> Unit) -> Modifier,
    hoveredTargetId: String?,
) {
    val id = "keyboard.${key.code}"
    val enabled = controller.enabled(key.code)
    val latched = controller.latched(key.code)
    val hovered = hoveredTargetId == id
    val secondary = if (controller.language == DesktopLanguage.KOREAN) DesktopHangulComposer.fromKey(key.code, controller.shift)?.toString() else null
    val bg = when { !enabled -> Color(0xFF1B2739); latched -> Color(0xFF604FA5); hovered -> Color(0xFF344C73); else -> Color(0xFF243956) }
    val outline = if (hovered || latched) Color(0xFFA99CEC) else Color(0xFF415977)
    val base = Modifier.width(unit * key.units - 4.dp).height(48.dp).focusProperties { canFocus = false }
        .background(bg, RoundedCornerShape(6.dp)).border(1.dp, outline, RoundedCornerShape(6.dp))
        .semantics { contentDescription = key.label + if (enabled) "" else " · 지원하지 않음" }
    Box(targetModifier(base, id, enabled) { controller.press(key.code) }, contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(key.label, color = if (enabled) Color(0xFFE3EBF9) else Color(0xFF6F8097), fontSize = if (key.label.length > 6) 11.sp else 13.sp, textAlign = TextAlign.Center)
            if (secondary != null) Text(secondary, color = Color(0xFFAEC6EC), fontSize = 10.sp)
        }
    }
}
