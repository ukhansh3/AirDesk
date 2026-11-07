package com.pleos.airdesk

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun Phase2Screen(client: SecureClient, diagnostic: DiagnosticUiState, onDiagnosticConnect: () -> Unit) {
    val state by client.state.collectAsState()
    val publicInfo by client.publicInfo.collectAsState()
    val note by client.note.collectAsState()
    val context = LocalContext.current
    var bootstrap by remember { mutableStateOf("") }
    var inspectedFingerprint by remember { mutableStateOf<String?>(null) }
    var confirmed by remember { mutableStateOf(false) }
    var code by remember { mutableStateOf("") }
    var text by remember { mutableStateOf(TextFieldValue("")) }
    var useGestures by remember { mutableStateOf(false) }
    var modifiers by remember { mutableStateOf(emptySet<String>()) }
    DisposableEffect(client) {
        onDispose { client.gestureBridge(false); client.enableMock(false) }
    }
    LaunchedEffect(diagnostic) { client.gesture(diagnostic) }
    LaunchedEffect(state.foreground, state.grant) {
        if (!state.foreground || state.grant == null) { code = ""; text = TextFieldValue(""); useGestures = false; modifiers = emptySet() }
    }
    LaunchedEffect(state.mockEnabled) { if (!state.mockEnabled) { modifiers = emptySet(); useGestures = false } }
    val mockAllowed = state.mockAllowed("POINTER", SystemClock.elapsedRealtime())
    MaterialTheme {
        Column(Modifier.fillMaxSize().background(Color(0xFF081628)).verticalScroll(rememberScrollState()).padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("AirDesk · 보안 페어링 / 세션", color = Color(0xFFC7BCFF), fontSize = 24.sp)
            Text("${state.phase} · ${state.status}", color = Color.White)
            Text("차량 안전: ${state.safety} · 실제 PC 제어: 잠금", color = Color(0xFFFFCA7B))
            Text(note, color = Color(0xFFB9CCE6))
            Text("1. Android 장치 공개 정보", color = Color.White)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = client::createIdentity) { Text("장치 키 생성 / 공개 정보") }
                Button(onClick = {
                    (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                        .setPrimaryClip(ClipData.newPlainText("AirDesk public device enrollment", publicInfo))
                }, enabled = publicInfo.isNotBlank()) { Text("공개 정보 복사") }
            }
            if (publicInfo.isNotBlank()) OutlinedTextField(publicInfo, {}, readOnly = true,
                label = { Text("이 공개 JSON을 PC에 등록 · 개인키 없음") }, maxLines = 4, modifier = Modifier.fillMaxWidth())
            Text("2. PC 공개 연결 정보와 전체 SHA-256 지문 대조", color = Color.White)
            OutlinedTextField(bootstrap, {
                bootstrap = it; inspectedFingerprint = null; confirmed = false
            }, label = { Text("PC 공개 연결 JSON 붙여넣기") }, maxLines = 4, modifier = Modifier.fillMaxWidth())
            Button(onClick = { inspectedFingerprint = client.inspectBootstrap(bootstrap); confirmed = false }) { Text("인증서 지문 확인") }
            inspectedFingerprint?.let { Text("PC 인증서 SHA-256\n$it", color = Color(0xFF75E6A4)) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(confirmed, { confirmed = it }, enabled = inspectedFingerprint != null)
                Text("PC 화면의 전체 지문과 일치함을 직접 확인했습니다", color = Color.White)
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { client.trustBootstrap(bootstrap, confirmed) }, enabled = confirmed) { Text("호스트 등록") }
                Button(onClick = client::connect) { Text("보안 연결") }
                Button(onClick = { client.disconnect() }) { Text("연결 해제") }
            }
            Text("3. PC의 8자리 코드와 양쪽 장치 승인", color = Color.White)
            OutlinedTextField(code, { code = it.filter(Char::isDigit).take(8) }, label = { Text("일회용 8자리 코드") },
                visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth())
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { client.claim(code); code = "" }, enabled = code.length == 8) { Text("코드 제출") }
                Button(onClick = { client.approvePairing(true) }) { Text("장치 확인 · 승인") }
                Button(onClick = { client.approvePairing(false) }) { Text("거부") }
            }
            Text("4. 세션과 권한 (PC 사용자 승인 필요)", color = Color.White)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = client::requestSession, enabled = state.pairedConnectionId != null && state.phase != SecurePhase.DISCONNECTED && state.phase != SecurePhase.WAITING_APPROVAL && state.grant == null) { Text("포인터·키보드 세션 요청") }
                Button(onClick = client::revokeSession) { Text("세션 종료") }
                Button(onClick = client::forgetPeer) { Text("장치 연결 해제") }
            }
            Text("영상 전송 권한은 Phase 3 예정입니다. 현재 화면 스트리밍 없음.", color = Color(0xFF96ACC7))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(state.mockEnabled, client::enableMock, enabled = state.grant != null && state.foreground)
                Text("모의 입력 테스트 · 실제 PC 제어 잠금", color = Color(0xFFFFCA7B))
            }
            Text("Windows Mock Backend 전송 요청: ${state.mockEventCount} · 누른 상태: ${state.heldCount}", color = Color.White)
            Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(useGestures, { useGestures = it; client.gestureBridge(it) }, enabled = mockAllowed)
                Text("Phase 1 손동작을 모의 입력 후보로 사용", color = Color.White)
                Button(onClick = onDiagnosticConnect) { Text("손동작 진단 연결") }
            }
            Text("손 추적: ${if (diagnostic.handDetected) diagnostic.gesturePhase else "없음"}", color = Color(0xFFB9CCE6))
            MockTouchPad(client, mockAllowed && !useGestures)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { client.down("right"); client.up("right") }, enabled = mockAllowed) { Text("오른쪽 클릭 후보") }
                Button(onClick = { client.releaseHeld("USER_REQUEST"); modifiers = emptySet() }) { Text("모든 입력 해제") }
            }
            OutlinedTextField(text, { text = it }, label = { Text("가상 키보드 · 확정된 한글/영문 텍스트") },
                modifier = Modifier.fillMaxWidth(), enabled = state.mockEnabled)
            Button(onClick = {
                if (text.composition == null) { client.committedText(text.text); text = TextFieldValue("") }
            }, enabled = state.mockEnabled && text.text.isNotEmpty() && text.composition == null) { Text("확정 텍스트 모의 전송") }
            Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                listOf("ShiftLeft", "ControlLeft", "AltLeft").forEach { key ->
                    Checkbox(key in modifiers, { held ->
                        client.key(key, held)
                        modifiers = if (held) modifiers + key else modifiers - key
                    }, enabled = state.mockEnabled)
                    Text(key, color = Color.White)
                }
            }
            for (codes in InputCandidates.PHYSICAL_KEYS.filterNot { it.startsWith("Shift") || it.startsWith("Control") || it.startsWith("Alt") }.chunked(4)) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                codes.forEach { key -> Button(onClick = { client.key(key, true); client.key(key, false) }, enabled = state.mockEnabled) { Text(key, fontSize = 11.sp) } }
            }
            Text("Mock 입력은 /mock-input으로만 전송됩니다. 공식 차량 안전 검증 전 /input 및 Windows 실제 입력은 활성화되지 않습니다.",
                color = Color(0xFFFFCA7B))
        }
    }
}

@Composable
private fun MockTouchPad(client: SecureClient, enabled: Boolean) {
    Box(Modifier.fillMaxWidth().height(250.dp).background(Color(0xFF162842)).pointerInput(enabled) {
        if (!enabled) return@pointerInput
        awaitEachGesture {
            val first = awaitFirstDown(requireUnconsumed = false)
            first.consume()
            InputCandidates.normalized(first.position.x, first.position.y, size.width.toFloat(), size.height.toFloat())?.let { client.move(it.first, it.second) }
            val start = SystemClock.elapsedRealtime()
            var dragging = false
            var scrolling = false
            var scrollPixels = 0f
            try {
                while (true) {
                    val remaining = (viewConfiguration.longPressTimeoutMillis - (SystemClock.elapsedRealtime() - start)).coerceAtLeast(1)
                    val event = if (!dragging && !scrolling) withTimeoutOrNull(remaining) { awaitPointerEvent() } else awaitPointerEvent()
                    if (event == null) { client.down(); dragging = true; continue }
                    val pressed = event.changes.filter { it.pressed }
                    if (pressed.size >= 2) {
                        if (dragging) { client.up(); dragging = false }
                        scrolling = true
                        scrollPixels += pressed.map { it.position.y - it.previousPosition.y }.average().toFloat()
                        val lines = InputCandidates.scrollLines(scrollPixels)
                        if (lines != 0) { client.scroll(lines); scrollPixels -= lines * 24f }
                    } else if (!scrolling && pressed.isNotEmpty()) {
                        val position = pressed.first().position
                        InputCandidates.normalized(position.x, position.y, size.width.toFloat(), size.height.toFloat())?.let { client.move(it.first, it.second) }
                    }
                    event.changes.forEach { it.consume() }
                    if (pressed.isEmpty()) {
                        if (dragging) client.up() else if (!scrolling) { client.down(); client.up() }
                        break
                    }
                }
            } finally {
                if (dragging) client.releaseHeld("USER_REQUEST")
            }
        }
    }, contentAlignment = Alignment.Center) {
        Text(if (enabled) "터치 이동 · 탭 클릭 · 길게 누른 뒤 드래그 · 두 손가락 스크롤\nWindows Mock Backend 전용"
            else "모의 입력을 켜고 손동작 모드를 끄면 터치 후보를 테스트할 수 있습니다", color = Color(0xFFB9CCE6), modifier = Modifier.padding(16.dp))
    }
}
