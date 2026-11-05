package com.pleos.airdesk

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

class MainActivity : ComponentActivity() {
    private val diagnostic = DiagnosticWebSocketClient()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val state by diagnostic.state.collectAsState()
            DiagnosticScreen(state, onConnect = { diagnostic.connect(BuildConfig.DEV_TOKEN) },
                onDisconnect = { diagnostic.disconnect() })
        }
    }

    override fun onStop() {
        diagnostic.disconnect()
        super.onStop()
    }
}

@Composable
private fun DiagnosticScreen(state: DiagnosticUiState, onConnect: () -> Unit, onDisconnect: () -> Unit) {
    val bg = Color(0xFF081628)
    val surface = Color(0xFF162842)
    Column(Modifier.fillMaxSize().background(bg).padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("AirDesk", fontSize = 32.sp, color = Color(0xFFC7BCFF))
        Text("PLEOS · 손동작 진단 화면", color = Color.White, fontSize = 20.sp)
        Text("정차 상태: 확인되지 않음  ·  실제 Windows 제어: 잠금", color = Color(0xFFFFCA7B), fontSize = 16.sp)
        Text(state.status, color = Color(0xFFB9CCE6))
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
                    drawCircle(
                        color = Color(0xFFA78BFA), radius = 15.dp.toPx(),
                        center = Offset(size.width * state.x, size.height * state.y)
                    )
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
