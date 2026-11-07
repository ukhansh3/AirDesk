package com.pleos.airdesk

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

class MainActivity : ComponentActivity() {
    private val diagnostic by lazy { DiagnosticWebSocketClient(applicationContext) }
    private val air by lazy { AirTypingClient(applicationContext) }
    private val secureInstance = lazy { SecureClient(applicationContext) }
    private val secure by secureInstance
    private var appForeground by mutableStateOf(false)
    private var appWindowFocused by mutableStateOf(false)
    private var suspendWorkspace: (() -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val state by diagnostic.state.collectAsState()
            val airState by air.state.collectAsState()
            var screen by remember { mutableStateOf(0) }
            LaunchedEffect(screen, appForeground) {
                if (screen == 2) {
                    secure.foreground(appForeground)
                    secure.safetyChanged(UnavailableVehicleSafetyGateway().current)
                } else if (secureInstance.isInitialized()) secure.foreground(false)
            }
            MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xFFC7BCFF),
                background = Color(0xFF081628), surface = Color(0xFF162842))) {
                Column(Modifier.fillMaxSize().background(Color(0xFF081628)).windowInsetsPadding(WindowInsets.safeDrawing)) {
                    Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { screen = 0 }) { Text("데스크톱 DEMO") }
                        Button(onClick = { screen = 1 }) { Text("Phase 1 진단") }
                        Button(onClick = { screen = 2 }) { Text("기존 Host 연구") }
                    }
                    Box(Modifier.weight(1f)) {
                        when (screen) {
                            0 -> DesktopWorkspace(diagnostic, state, air, airState, appForeground && appWindowFocused) { suspendWorkspace = it }
                            1 -> DiagnosticScreen(state, onConnect = { diagnostic.connect(BuildConfig.DEV_TOKEN) },
                                onDisconnect = { diagnostic.disconnect() })
                            else -> Phase2Screen(secure, state) { diagnostic.connect(BuildConfig.DEV_TOKEN) }
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        appForeground = true
        appWindowFocused = hasWindowFocus()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus) suspendWorkspace?.invoke()
        appWindowFocused = hasFocus
    }

    override fun onPause() {
        suspendWorkspace?.invoke()
        appForeground = false
        if (secureInstance.isInitialized()) secure.foreground(false)
        diagnostic.disconnect()
        air.disconnect()
        super.onPause()
    }

    override fun onDestroy() {
        if (secureInstance.isInitialized()) secure.close()
        diagnostic.close()
        air.close()
        super.onDestroy()
    }
}

@Composable
private fun DiagnosticScreen(state: DiagnosticUiState, onConnect: () -> Unit, onDisconnect: () -> Unit) {
    val bg = Color(0xFF081628)
    val surface = Color(0xFF162842)
    Column(
        Modifier.fillMaxSize().background(bg).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("AirDesk", fontSize = 32.sp, color = Color(0xFFC7BCFF))
        Text("PLEOS · 손동작 진단 화면", color = Color.White, fontSize = 20.sp)
        Text("정차 상태: 확인되지 않음  ·  실제 Windows 제어: 잠금", color = Color(0xFFFFCA7B), fontSize = 16.sp)
        Text(state.status, color = Color(0xFFB9CCE6))
        Text(
            "손: ${if (state.handDetected) "인식됨" else "없음"}  ·  제스처: ${state.gesturePhase.name}",
            color = if (state.handDetected) Color(0xFF75E6A4) else Color(0xFF96ACC7),
            fontSize = 14.sp,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = onConnect, enabled = BuildConfig.DEBUG) { Text("진단 연결") }
            Button(onClick = onDisconnect) { Text("연결 해제") }
        }
        Box(
            modifier = Modifier.weight(1f).fillMaxWidth().background(surface, RoundedCornerShape(20.dp))
                .border(1.dp, Color(0xFF5069A0), RoundedCornerShape(20.dp))
        ) {
            Text("WEB CAM → MediaPipe → Gesture Events (DEMO)", color = Color(0xFF9EB1D7),
                modifier = Modifier.align(Alignment.TopStart).padding(16.dp))
            Canvas(Modifier.fillMaxSize()) {
                if (state.cursorVisible && state.authorized) {
                    val radius = 15.dp.toPx()
                    val inset = radius * 1.6f
                    val center = Offset(
                        inset + (size.width - inset * 2).coerceAtLeast(0f) * state.x,
                        inset + (size.height - inset * 2).coerceAtLeast(0f) * state.y,
                    )
                    drawCircle(
                        color = when (state.gesturePhase) {
                            GesturePhase.PINCHED -> Color(0xFFFFCA7B)
                            GesturePhase.DRAGGING -> Color(0xFFFF7A90)
                            else -> Color(0xFFA78BFA)
                        },
                        radius = radius,
                        center = center,
                    )
                    if (state.gesturePhase == GesturePhase.PINCHED || state.gesturePhase == GesturePhase.DRAGGING) {
                        drawCircle(Color.White, radius * 1.45f, center, style = androidx.compose.ui.graphics.drawscope.Stroke(3.dp.toPx()))
                    }
                }
            }
            Text(
                "READ-ONLY PREVIEW • NO REMOTE INPUT",
                color = Color(0xFFFFCB7D),
                modifier = Modifier.align(Alignment.BottomStart).padding(18.dp)
            )
        }
        Text("실제 차량 안전 게이트와 인증 연결 전에는 PC 입력이 절대 활성화되지 않습니다.",
            color = Color(0xFF96ACC7), fontSize = 14.sp)
    }
}

