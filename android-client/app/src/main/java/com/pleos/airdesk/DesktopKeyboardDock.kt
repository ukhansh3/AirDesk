package com.pleos.airdesk

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

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
    mousePanel: @Composable (Modifier) -> Unit = { InactiveMousePanel(it) },
    fingers: List<AirFinger> = emptyList(),
    pressedKeys: Set<String> = emptySet(),
) {
    if (!controller.visible) return
    BoxWithConstraints(modifier.fillMaxWidth().background(Color(0xFF12223B), RoundedCornerShape(12.dp))) {
        val baseDensity = LocalDensity.current
        val availableHeight = if (maxHeight.value.isFinite()) maxHeight.value else
            maxWidth.value * DesktopKeyboardGeometry.HEIGHT / DesktopKeyboardGeometry.WIDTH
        if (maxWidth.value > 0f && availableHeight > 0f) {
            val fit = DesktopKeyboardGeometry.fit(maxWidth.value, availableHeight)
            Box(Modifier.size(fit.width.dp, fit.height.dp).align(Alignment.Center)) {
                // Fit the complete desktop surface in the allocated rectangle. Density scaling
                // also scales measured targets; no graphics-only transform or hidden paging.
                CompositionLocalProvider(LocalDensity provides Density(baseDensity.density * fit.scale, fontScale = 1f)) {
                    Box(Modifier.requiredSize(DesktopKeyboardGeometry.WIDTH.dp, DesktopKeyboardGeometry.HEIGHT.dp)) {
                        Text("PC 키보드 · DEMO  |  ${if (controller.language == DesktopLanguage.KOREAN) "한글 두벌식" else "English"}  |  F1 도움말 · F2–F12 미지원",
                            modifier = Modifier.offset(8.dp, 8.dp).width(952.dp).height(28.dp), color = Color(0xFFC7BCFF),
                            fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Clip)
                        for (placement in DesktopKeyboardGeometry.keys) {
                            KeyCap(placement.key,
                                Modifier.offset(placement.bounds.left.dp, placement.bounds.top.dp)
                                    .size(placement.bounds.width.dp, placement.bounds.height.dp),
                                controller, targetModifier, hoveredTargetId, placement.key.code in pressedKeys)
                        }
                        val mouse = DesktopKeyboardGeometry.mouseBounds
                        mousePanel(Modifier.offset(mouse.left.dp, mouse.top.dp).size(mouse.width.dp, mouse.height.dp))
                        for (finger in fingers.filter { it.valid }) {
                            val x = (finger.x * DesktopKeyboardGeometry.WIDTH).coerceIn(8f, DesktopKeyboardGeometry.WIDTH - 8f)
                            val y = (finger.y * DesktopKeyboardGeometry.HEIGHT).coerceIn(8f, DesktopKeyboardGeometry.HEIGHT - 8f)
                            Box(Modifier.offset((x - 7f).dp, (y - 7f).dp).size(14.dp)
                                .background(if (finger.pressed) Color(0xFFFFBA66) else if (finger.hand == "left")
                                    Color(0xFF76DDFF) else Color(0xFFFF93CA), RoundedCornerShape(7.dp)),
                                contentAlignment = Alignment.Center) {
                                Text(finger.finger.first().uppercase(), color = Color(0xFF102039), fontSize = 8.sp, lineHeight = 8.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun InactiveMousePanel(modifier: Modifier) {
    Column(modifier.background(Color(0xFF1B2F49), RoundedCornerShape(9.dp))
        .border(1.dp, Color(0xFF415977), RoundedCornerShape(9.dp)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("마우스 영역", color = Color(0xFFC7BCFF), fontSize = 15.sp)
        Text("별도 입력 준비 중", color = Color(0xFFFFCB7D), fontSize = 12.sp)
        Text("공중 타이핑과 전용 마우스 동작은 아직 연결되지 않았습니다.", color = Color(0xFFA2B4CF), fontSize = 12.sp)
    }
}

@Composable
private fun KeyCap(
    key: DesktopKeySpec,
    modifier: Modifier,
    controller: DesktopKeyboardController,
    targetModifier: (Modifier, String, Boolean, () -> Unit) -> Modifier,
    hoveredTargetId: String?,
    airPressed: Boolean = false,
) {
    val id = "keyboard.${key.code}"
    val enabled = controller.enabled(key.code)
    val latched = controller.latched(key.code)
    val hovered = hoveredTargetId == id
    val secondary = if (controller.language == DesktopLanguage.KOREAN) DesktopHangulComposer.fromKey(key.code, controller.shift)?.toString() else null
    val bg = when { airPressed -> Color(0xFFA56632); !enabled -> Color(0xFF1B2739); latched -> Color(0xFF604FA5); hovered -> Color(0xFF344C73); else -> Color(0xFF243956) }
    val outline = if (hovered || latched) Color(0xFFA99CEC) else Color(0xFF415977)
    val base = modifier.focusProperties { canFocus = false }
        .background(bg, RoundedCornerShape(6.dp)).border(1.dp, outline, RoundedCornerShape(6.dp))
        .semantics { contentDescription = key.label + if (enabled) "" else " · 지원하지 않음" }
    Box(targetModifier(base, id, enabled) { controller.press(key.code) }, contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(key.label, color = if (enabled) Color(0xFFE3EBF9) else Color(0xFF6F8097), fontSize = if (key.label.length > 6) 11.sp else 13.sp,
                textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Clip)
            if (secondary != null) Text(secondary, color = Color(0xFFAEC6EC), fontSize = 10.sp)
        }
    }
}
