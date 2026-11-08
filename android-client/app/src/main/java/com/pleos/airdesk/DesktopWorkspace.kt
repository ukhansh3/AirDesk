package com.pleos.airdesk

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalTextToolbar
import androidx.compose.ui.platform.TextToolbar
import androidx.compose.ui.platform.TextToolbarStatus
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private val DeskBackground = Color(0xFF081628)
private val DeskSurface = Color(0xFF14263D)
private val DeskBorder = Color(0xFF385477)
private val DeskAccent = Color(0xFFC7BCFF)
private class MeasuredDesktopTarget { var bounds: UiBounds? = null }
private class AirSuspendHook { var cancel: () -> Unit = {} }

private class AndroidDesktopClipboard(context: Context) : DesktopClipboard {
    private val service = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    override fun readText(): String? = service.primaryClip?.getItemAt(0)?.text?.toString()
    override fun writeText(text: String) { service.setPrimaryClip(ClipData.newPlainText("AirDesk selection", text)) }
}

/** Each visible action has one app-owned callback shared by touch and the gesture router. */
private fun Modifier.desktopTarget(
    router: GestureUiRouter, id: String, enabled: Boolean = true, layer: Int = 1,
    modeControl: Boolean = false, hovered: String? = null, touchClick: Boolean = true, onClick: (() -> Unit)? = null,
    onContext: (() -> Unit)? = null, onDragStart: ((UiPoint) -> Unit)? = null,
    onDrag: ((UiPoint) -> Unit)? = null, onDragEnd: ((Boolean) -> Unit)? = null,
    onScroll: ((Float) -> Unit)? = null, parentId: String? = null,
): Modifier = composed {
    val currentClick by rememberUpdatedState(onClick)
    val currentContext by rememberUpdatedState(onContext)
    val currentStart by rememberUpdatedState(onDragStart)
    val currentDrag by rememberUpdatedState(onDrag)
    val currentEnd by rememberUpdatedState(onDragEnd)
    val currentScroll by rememberUpdatedState(onScroll)
    val measured = remember(router, id) { MeasuredDesktopTarget() }
    DisposableEffect(router, id) { onDispose { router.unregister(id) } }
    fun register(bounds: UiBounds) {
        router.register(GestureUiTarget(id, bounds, layer,
            enabled, modeControl,
            onClick = if (onClick == null) null else ({ currentClick?.invoke() }),
            onContext = if (onContext == null) null else ({ currentContext?.invoke() }),
            onDragStart = if (onDragStart == null) null else ({ currentStart?.invoke(it) }),
            onDrag = if (onDrag == null) null else ({ currentDrag?.invoke(it) }),
            onDragEnd = if (onDragEnd == null) null else ({ currentEnd?.invoke(it) }),
            onScroll = if (onScroll == null) null else ({ currentScroll?.invoke(it) }), parentId = parentId))
    }
    SideEffect { measured.bounds?.let(::register) }
    var result = this.onGloballyPositioned { coordinates ->
        val b = coordinates.boundsInRoot()
        val bounds = UiBounds(b.left, b.top, b.right, b.bottom)
        measured.bounds = bounds
        register(bounds)
    }
    if (hovered == id) result = result.border(2.dp, DeskAccent, RoundedCornerShape(5.dp))
    if (onClick != null && touchClick) result = result.focusProperties { canFocus = false }.clickable(enabled = enabled) {
        if (router.snapshot.phase in setOf(UiPointerPhase.PRESSED, UiPointerPhase.DRAGGING,
                UiPointerPhase.SCROLLING, UiPointerPhase.PENDING_RELEASE)) router.cancel("touch")
        currentClick?.invoke()
    }
    result
}

@Composable
private fun DeskButton(label: String, modifier: Modifier, selected: Boolean = false, enabled: Boolean = true) {
    Box(modifier.heightIn(min = 44.dp).background(if (selected) Color(0xFF624B8F) else Color(0xFF233B57),
        RoundedCornerShape(6.dp)).padding(horizontal = 12.dp, vertical = 8.dp), contentAlignment = Alignment.Center) {
        Text(label, color = if (enabled) Color.White else Color(0xFF71859D), fontSize = 13.sp,
            lineHeight = 18.sp, maxLines = 1)
    }
}

/** Explicit local DEMO only. No Windows Host, production Work callback or global input dispatcher. */
@OptIn(ExperimentalLayoutApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun DesktopWorkspace(
    diagnostic: DiagnosticWebSocketClient, diagnosticState: DiagnosticUiState,
    air: AirTypingClient, airState: AirTransportState, foreground: Boolean,
    registerSuspend: ((() -> Unit)?) -> Unit,
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val focusManager = LocalFocusManager.current
    val systemKeyboard = LocalSoftwareKeyboardController.current
    val coroutineScope = rememberCoroutineScope()
    val workspaceScroll = rememberScrollState()
    val toolbarScroll = rememberScrollState()
    var activeEditorId by remember { mutableStateOf("note") }
    var focusRequestNumber by remember { mutableStateOf(0) }
    var chromeHeightPx by remember { mutableStateOf(0) }
    var footerHeightPx by remember { mutableStateOf(0) }
    var showHelp by remember { mutableStateOf(false) }
    var contextEditor by remember { mutableStateOf<String?>(null) }
    var viewport by remember { mutableStateOf(UiBounds(0f, 0f, 0f, 0f)) }
    var gestureUi by remember { mutableStateOf(UiGestureSnapshot()) }
    var airMode by remember { mutableStateOf(true) }
    var automaticCalibration by remember { mutableStateOf(false) }
    var calibrationPreviousEpoch by remember { mutableStateOf<String?>(null) }
    var showAirDetails by remember { mutableStateOf(false) }
    val latestAirMode by rememberUpdatedState(airMode)
    val airHook = remember { AirSuspendHook() }
    val editorIds = remember { listOf("document", "note") }
    val focusRequests = remember { editorIds.associateWith { FocusRequester() } }
    lateinit var keyboard: DesktopKeyboardController
    keyboard = remember {
        DesktopKeyboardController(AndroidDesktopClipboard(context),
            onTab = { reverse ->
                val index = editorIds.indexOf(keyboard.focusedEditorId).coerceAtLeast(0)
                val next = editorIds[(index + if (reverse) editorIds.size - 1 else 1) % editorIds.size]
                activeEditorId = next; keyboard.focusEditor(next); keyboard.show(); focusRequestNumber++
            },
            onEscape = { contextEditor = null; keyboard.hide(); focusManager.clearFocus() },
            onHelp = { if (latestAirMode) airHook.cancel(); showHelp = true })
    }
    val airController = remember { AirTypingController(keyboard) }
    val airMouse = remember { AirMouseBridge() }
    val router = remember {
        var instance: GestureUiRouter? = null
        GestureUiRouter(onChanged = { instance?.let { gestureUi = it.snapshot } },
            onCancelled = { reason ->
                if (reason != "touch") {
                    if (!latestAirMode) keyboard.resetTransient(clearFocus = false)
                    contextEditor = null
                }
            }).also { instance = it }
    }
    val actualSafety = UnavailableVehicleSafetyGateway().current
    val demoAllowed = BuildConfig.DEBUG && foreground &&
        actualSafety in setOf(VehicleSafety.UNKNOWN, VehicleSafety.PARKED_CONFIRMED)
    val airAllowed = demoAllowed && airMode && !showHelp && contextEditor == null
    val latestAirAllowed by rememberUpdatedState(airAllowed)
    airHook.cancel = { airController.cancel("화면 동작 전환"); router.setDemoEnabled(false); airMouse.reset() }
    SideEffect { airController.setAllowed(airAllowed) }
    val suspendInput: () -> Unit = {
        airController.setAllowed(false); airController.cancel("화면 중지"); airMouse.reset()
        air.command("disarm"); air.disconnect()
        router.setDemoEnabled(false); router.setForeground(false)
        keyboard.resetTransient(clearFocus = true); contextEditor = null; showHelp = false
        focusManager.clearFocus(); systemKeyboard?.hide()
    }
    val latestSuspend by rememberUpdatedState(suspendInput)
    // Replace the platform selection popup with the same registered, app-owned context menu.
    val textToolbar = remember {
        object : TextToolbar {
            override val status: TextToolbarStatus
                get() = if (contextEditor == null) TextToolbarStatus.Hidden else TextToolbarStatus.Shown
            override fun hide() { contextEditor = null }
            override fun showMenu(rect: Rect, onCopyRequested: (() -> Unit)?, onPasteRequested: (() -> Unit)?,
                onCutRequested: (() -> Unit)?, onSelectAllRequested: (() -> Unit)?) {
                if (latestAirMode) airHook.cancel()
                router.cancel("touch")
                contextEditor = keyboard.focusedEditorId
            }
            override fun showMenu(rect: Rect, onCopyRequested: (() -> Unit)?, onPasteRequested: (() -> Unit)?,
                onCutRequested: (() -> Unit)?, onSelectAllRequested: (() -> Unit)?, onAutofillRequested: (() -> Unit)?) {
                showMenu(rect, onCopyRequested, onPasteRequested, onCutRequested, onSelectAllRequested)
            }
        }
    }
    DisposableEffect(router, diagnostic) {
        keyboard.registerEditor("document", TextFieldValue("AirDesk 작업 문서\n한/영 키와 화면의 PC 키보드로 입력하세요.\nCtrl+A/C/V/X/Z · 방향키 · Home/End"))
        keyboard.registerEditor("note", TextFieldValue(""))
        val stopObserving = diagnostic.observeGestures { signal ->
            when (signal) {
                is DiagnosticGestureSignal.Hint -> if (!latestAirMode) router.accept(signal.value, SystemClock.elapsedRealtime())
                is DiagnosticGestureSignal.Cancel -> {
                    if (signal.reason.startsWith("diagnostic_")) router.setDemoEnabled(false)
                    router.cancel(signal.reason)
                }
            }
        }
        registerSuspend { latestSuspend() }
        systemKeyboard?.hide()
        onDispose { latestSuspend(); stopObserving(); registerSuspend(null) }
    }
    DisposableEffect(air, airController, router) {
        val stopAir = air.observe { signal ->
            when (signal) {
                is AirSignal.Frame -> if (latestAirMode) {
                    if (signal.value.stage == "READY" && signal.value.epoch != calibrationPreviousEpoch)
                        automaticCalibration = false
                    val now = SystemClock.elapsedRealtime()
                    airController.setAllowed(latestAirAllowed)
                    airController.accept(signal.value, now)
                    val enabled = latestAirAllowed && airController.snapshot.enabled && signal.value.armed
                    router.setDemoEnabled(enabled && signal.value.mouse.active && signal.value.mouse.valid)
                    airMouse.accept(signal.value, enabled, now).forEach { router.accept(it, now) }
                }
                is AirSignal.Cancel -> {
                    if (!signal.retainCalibration) automaticCalibration = false
                    airController.cancel(signal.reason); keyboard.resetTransient(clearFocus = false)
                    airMouse.reset(); router.setDemoEnabled(false)
                }
            }
        }
        onDispose { stopAir(); airController.cancel("화면 종료") }
    }
    LaunchedEffect(foreground, actualSafety) {
        router.setForeground(foreground); router.setVehicleSafety(actualSafety)
        if (!foreground) latestSuspend()
    }
    LaunchedEffect(router) { while (true) { delay(50); val now = SystemClock.elapsedRealtime(); router.tick(now); airController.tick(now) } }

    fun focus(id: String) {
        activeEditorId = id; keyboard.focusEditor(id); keyboard.show(); focusRequestNumber++; systemKeyboard?.hide()
    }
    // A compact workspace composes one editor at a time. Acquire focus after the chosen editor exists.
    LaunchedEffect(focusRequestNumber) {
        if (focusRequestNumber > 0 && foreground) focusRequests.getValue(activeEditorId).requestFocus()
    }
    fun openContext(id: String) { if (airMode) airHook.cancel(); focus(id); contextEditor = id }
    fun shortcut(code: String) {
        when (code) {
            "KeyA" -> keyboard.selectAll()
            "KeyC" -> keyboard.copySelection()
            "KeyX" -> keyboard.cutSelection()
            "KeyV" -> keyboard.paste()
        }
        contextEditor = null
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(DeskBackground).onGloballyPositioned {
        val b = it.boundsInRoot(); val bounds = UiBounds(b.left, b.top, b.right, b.bottom)
        viewport = bounds; router.setViewport(bounds, density.density)
    }) {
        val chromeDp = with(density) { (chromeHeightPx + footerHeightPx).toDp().value } +
            if (keyboard.visible) 21f else 14f
        val plan = DesktopWorkspaceLayout.calculate(maxWidth.value, maxHeight.value,
            chromeDp, keyboard.visible, density.fontScale)
        val tools: @Composable () -> Unit = {
          if (airMode) {
            DeskButton("연결", Modifier.desktopTarget(router, "air.connect", demoAllowed, modeControl = true,
                onClick = { airHook.cancel(); keyboard.resetTransient(clearFocus = false); diagnostic.disconnect(); air.connect() }))
            DeskButton(if (automaticCalibration) "보정 다시 시작" else "자동 보정 시작",
                Modifier.desktopTarget(router, "air.calibrate", demoAllowed && airState.authorized, modeControl = true,
                    onClick = {
                        val previous = airState.frame?.epoch
                        airHook.cancel(); air.command("start_calibration")
                        calibrationPreviousEpoch = previous; automaticCalibration = true
                    }), enabled = airState.authorized)
            if (automaticCalibration) DeskButton("보정 취소", Modifier.desktopTarget(router, "air.cancelCalibration",
                demoAllowed && airState.authorized, modeControl = true,
                onClick = { air.command("disarm"); automaticCalibration = false }))
            val calibrated = airState.frame?.stage == "READY"
            if (!automaticCalibration) DeskButton(if (airController.snapshot.enabled) "타건 끄기" else "타건 켜기",
                Modifier.desktopTarget(router, "air.enable", demoAllowed && calibrated && airState.authorized, modeControl = true,
                    onClick = {
                        if (airController.snapshot.enabled) { airHook.cancel(); air.command("disarm") }
                        else {
                            keyboard.resetTransient(clearFocus = false)
                            if (keyboard.focusedEditorId == null) focus(activeEditorId)
                            keyboard.show(); airController.setAllowed(airAllowed); airController.setEnabled(true); air.command("arm")
                        }
                    }), selected = airController.snapshot.enabled, enabled = calibrated && airState.authorized)
          } else {
            DeskButton(if (gestureUi.enabled) "손동작 ON" else "손동작 켜기",
                Modifier.desktopTarget(router, "toolbar.gestures", demoAllowed && diagnosticState.authorized, modeControl = true,
                    hovered = gestureUi.hoveredId, onClick = { router.setDemoEnabled(!gestureUi.enabled) }), gestureUi.enabled,
                enabled = demoAllowed && diagnosticState.authorized)
            DeskButton("카메라 연결", Modifier.desktopTarget(router, "toolbar.connect", demoAllowed,
                modeControl = true, hovered = gestureUi.hoveredId,
                onClick = { diagnostic.connect(BuildConfig.DEV_TOKEN) }))
            DeskButton("연결 해제", Modifier.desktopTarget(router, "toolbar.disconnect", demoAllowed,
                modeControl = true, hovered = gestureUi.hoveredId, onClick = { diagnostic.disconnect() }))
            for ((mode, label) in listOf(GestureMouseMode.LEFT to "왼쪽 클릭", GestureMouseMode.RIGHT_ONCE to "우클릭 1회", GestureMouseMode.SCROLL to "스크롤 모드")) {
                val selected = gestureUi.mode == mode
                DeskButton(label, Modifier.desktopTarget(router, "toolbar.mode.${mode.name}", demoAllowed,
                    modeControl = true, hovered = gestureUi.hoveredId,
                    onClick = { router.setMode(if (selected && mode != GestureMouseMode.LEFT) GestureMouseMode.LEFT else mode) }), selected)
            }
            DeskButton(if (keyboard.visible) "키보드 접기" else "키보드 펼치기",
                Modifier.desktopTarget(router, "toolbar.keyboard", demoAllowed, modeControl = true,
                    hovered = gestureUi.hoveredId, onClick = {
                        if (keyboard.visible) { keyboard.hide(); focusManager.clearFocus() } else keyboard.show()
                    }))
          }
        }
        Column(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Column(Modifier.fillMaxWidth().onGloballyPositioned { chromeHeightPx = it.size.height },
                verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text("AirDesk DESK · ${if (airMode) "AIR 실험" else "Pinch"} DEMO", color = DeskAccent, fontSize = 20.sp, maxLines = 1)
                Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    for ((useAir, label) in listOf(true to "양손 열손가락", false to "검지·Pinch")) {
                        DeskButton(label, Modifier.desktopTarget(router, "profile.$useAir", demoAllowed, modeControl = true,
                            onClick = {
                                airHook.cancel(); keyboard.resetTransient(clearFocus = false)
                                air.disconnect(); diagnostic.disconnect(); airMode = useAir
                            }), selected = airMode == useAir)
                    }
                }
                if (plan.compactControls) {
                    Row(Modifier.fillMaxWidth().horizontalScroll(toolbarScroll),
                        horizontalArrangement = Arrangement.spacedBy(7.dp)) { tools() }
                } else {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(7.dp),
                        verticalArrangement = Arrangement.spacedBy(5.dp)) { tools() }
                }
                if (airMode) {
                    val prompt = AirCalibrationGuide.prompt(airState.frame, automaticCalibration, calibrationPreviousEpoch,
                        airState.retainedCalibrationStage, airState.calibrationPaused)
                    Text(if (airState.authorized) prompt.title else airState.status,
                        color = DeskAccent, fontSize = 13.sp, lineHeight = 18.sp, maxLines = 1)
                    Text(prompt.detail, color = Color(0xFFAFC3DF), fontSize = 12.sp, lineHeight = 17.sp, maxLines = 2)
                    airState.disconnectReason?.let { reason ->
                        Text(reason, color = Color(0xFFFFCB7D), fontSize = 12.sp, lineHeight = 17.sp, maxLines = 2)
                    }
                    if (airState.frame?.stage == "READY") {
                        Text(airController.snapshot.status, color = if (airController.snapshot.enabled) Color(0xFF9FE3C1)
                            else Color(0xFFFFCB7D), fontSize = 12.sp, lineHeight = 17.sp, maxLines = 2)
                        Text("손끝 ${airController.snapshot.observedFingerCount} · 누름 ${airController.snapshot.detectedPressedCount} · 입력 키 ${airController.snapshot.pressedKeyCodes.size} · 상세",
                            modifier = Modifier.heightIn(min = 30.dp).desktopTarget(router, "air.details", demoAllowed,
                                modeControl = true, onClick = { showAirDetails = !showAirDetails }),
                            color = Color(0xFFAFC3DF), fontSize = 12.sp, lineHeight = 17.sp, maxLines = 1)
                        if (showAirDetails) for (side in listOf("left", "right")) Text(airFingerFeedback(airController.snapshot, side),
                            color = Color(0xFFAFC3DF), fontSize = 11.sp, lineHeight = 16.sp, maxLines = 2)
                    }
                } else Text("${diagnosticState.status} · ${gestureUi.status}", color = Color(0xFFAFC3DF), fontSize = 12.sp, maxLines = 2)
                Row(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically) {
                    for ((id, label) in listOf("note" to "메모 입력", "document" to "작업 문서")) {
                        DeskButton(label, Modifier.desktopTarget(router, "editor.select.$id", demoAllowed,
                            modeControl = true, hovered = gestureUi.hoveredId, onClick = { focus(id) }),
                            selected = keyboard.focusedEditorId == id)
                    }
                    Text(if (keyboard.focusedEditorId == null) "← 눌러서 입력 시작" else "입력 중",
                        color = DeskAccent, fontSize = 12.sp, maxLines = 1)
                }
            }
            val bodyModifier = if (plan.needsWorkspaceScroll) Modifier.weight(1f).verticalScroll(workspaceScroll)
                else Modifier.weight(1f)
            Column(bodyModifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Row(Modifier.height(plan.editorAreaHeightDp.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Column(Modifier.weight(if (plan.showExamples) 2f else 1f).fillMaxHeight(),
                        verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        CompositionLocalProvider(LocalTextToolbar provides textToolbar) {
                            InterceptPlatformTextInput(interceptor = { _, _ -> awaitCancellation() }) {
                                val visibleEditors = if (plan.showSecondaryEditor) editorIds else listOf(activeEditorId)
                                for (id in visibleEditors) DesktopEditor(if (id == "note") "메모" else "작업 문서", id,
                                    keyboard, focusRequests.getValue(id), router, gestureUi.hoveredId, demoAllowed,
                                    Modifier.weight(1f).heightIn(min = plan.editorMinHeightDp.dp),
                                    { focus(id) }, { openContext(id) })
                            }
                        }
                    }
                    if (plan.showExamples) Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        DesktopDragBoard(router, gestureUi.hoveredId, demoAllowed, Modifier.weight(1f))
                        DesktopTaskList(router, gestureUi.hoveredId, demoAllowed, Modifier.weight(1f))
                    }
                }
                if (keyboard.visible) DesktopKeyboardDock(keyboard,
                    modifier = Modifier.fillMaxWidth().height(plan.keyboardHeightDp.dp),
                    targetModifier = { modifier, id, enabled, action -> modifier.desktopTarget(router, id,
                        enabled && demoAllowed && !(airMode && airController.snapshot.enabled), layer = 10,
                        hovered = gestureUi.hoveredId, onClick = { if (airMode) airHook.cancel(); action() }) },
                    hoveredTargetId = gestureUi.hoveredId,
                    fingers = if (airMode) airState.frame?.fingers.orEmpty() else emptyList(),
                    pressedKeys = if (airMode) airController.snapshot.pressedKeyCodes else emptySet(),
                    mousePanel = { panel ->
                        Column(panel.background(DeskSurface, RoundedCornerShape(8.dp)).padding(8.dp),
                            verticalArrangement = Arrangement.spacedBy(7.dp)) {
                            Text("마우스 · DEMO", color = DeskAccent, fontSize = 13.sp)
                            for ((mode, label) in listOf(GestureMouseMode.LEFT to "왼쪽 클릭", GestureMouseMode.RIGHT_ONCE to "우클릭 1회", GestureMouseMode.SCROLL to "스크롤 모드")) {
                                DeskButton(label, Modifier.fillMaxWidth().desktopTarget(router, "mouse.mode.${mode.name}",
                                    demoAllowed, layer = 10, modeControl = true, hovered = gestureUi.hoveredId,
                                    onClick = { router.setMode(mode) }), selected = gestureUi.mode == mode)
                            }
                            Text(if (airMode) "오른손을 이 영역으로\n펴서 준비 → 검지 이동\nPinch 클릭 / 유지·이동 드래그\n돌아오면 다시 펴서 준비"
                                else "검지 커서·Pinch", color = Color(0xFFAFC3DF), fontSize = 12.sp)
                        }
                    })
            }
            Text(if (demoAllowed) "앱 내부 실험 DEMO · 차량 UNKNOWN / 운영 잠금 · 실제 타건 정확도 미검증"
                else "입력 잠금 · DEBUG 빌드와 전경 화면에서만 DEMO를 사용할 수 있습니다",
                modifier = Modifier.fillMaxWidth().onGloballyPositioned { footerHeightPx = it.size.height },
                color = Color(0xFF8EA9CA), fontSize = 11.sp, maxLines = 2)
        }
        contextEditor?.let { id ->
            Surface(Modifier.align(Alignment.TopEnd).padding(top = 95.dp, end = 16.dp).width(220.dp),
                shape = RoundedCornerShape(10.dp), color = Color(0xFF293B57), tonalElevation = 8.dp) {
                Column(Modifier.desktopTarget(router, "context.panel", demoAllowed, layer = 99,
                    modeControl = true, onClick = {}).padding(10.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text("앱 메뉴 · $id", color = DeskAccent)
                    for ((code, label) in listOf("KeyA" to "전체 선택", "KeyC" to "복사", "KeyX" to "잘라내기", "KeyV" to "붙여넣기")) {
                        DeskButton(label, Modifier.fillMaxWidth().desktopTarget(router, "context.$code", demoAllowed,
                            layer = 100, modeControl = true, hovered = gestureUi.hoveredId, onClick = { shortcut(code) }))
                    }
                    DeskButton("닫기 · Esc", Modifier.fillMaxWidth().desktopTarget(router, "context.close", demoAllowed,
                        layer = 100, modeControl = true, hovered = gestureUi.hoveredId, onClick = { contextEditor = null }))
                }
            }
        }
        if (showHelp) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .7f)).desktopTarget(router,
                "help.shield", demoAllowed, layer = 200, modeControl = true, onClick = {}), contentAlignment = Alignment.Center) {
                Column(Modifier.widthIn(max = 560.dp).padding(22.dp).background(DeskSurface, RoundedCornerShape(12.dp))
                    .padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text("AirDesk PC 작업공간 · DEMO", color = DeskAccent, fontSize = 21.sp)
                    Text("Pinch를 유지했다 놓으면 한 번 클릭합니다. 다음 정상 손 프레임에서 확정하므로 손을 잃으면 취소됩니다.\n\n우클릭 1회 → 타깃 클릭: 앱 메뉴\n스크롤 모드 → 목록에서 Pinch 드래그\n작업 카드 → 보관함으로 드래그\n\nCtrl+A/C/V/X/Z는 선택한 앱 편집기에서만 동작합니다. F2–F12는 지원하지 않아 비활성화되어 있습니다.", color = Color.White)
                    DeskButton("도움말 닫기", Modifier.fillMaxWidth().desktopTarget(router, "help.close", demoAllowed,
                        layer = 201, modeControl = true, hovered = gestureUi.hoveredId, onClick = { showHelp = false }))
                }
            }
        }
        DesktopCursor(gestureUi, viewport)
    }
}

private fun airFingerFeedback(snapshot: AirTypingSnapshot, side: String): String =
    (if (side == "left") "왼손 " else "오른손 ") + listOf("thumb" to "엄지", "index" to "검지", "middle" to "중지",
        "ring" to "약지", "little" to "새끼").joinToString(" · ") { (finger, label) ->
        val owner = "$side.$finger"
        val key = snapshot.hoveredKeys[owner]?.removePrefix("Key") ?: ""
        val state = when (snapshot.fingerStates[owner]) {
            AirFingerState.WAIT_NEUTRAL -> "펴기"; AirFingerState.HOVER -> key
            AirFingerState.KEYDOWN -> "$key↓"; AirFingerState.NO_TARGET -> "키 밖"
            AirFingerState.UNSUPPORTED -> "미지원"; AirFingerState.MOUSE -> "마우스"
            AirFingerState.BLOCKED -> "꺼짐"; else -> "없음"
        }
        "$label:$state"
    }

@Composable
private fun DesktopCursor(state: UiGestureSnapshot, viewport: UiBounds) {
    Canvas(Modifier.fillMaxSize()) {
        val point = state.cursor ?: return@Canvas
        val x = point.x - viewport.left; val y = point.y - viewport.top
        val arrow = Path().apply { moveTo(x, y); lineTo(x + 5.dp.toPx(), y + 26.dp.toPx());
            lineTo(x + 11.dp.toPx(), y + 17.dp.toPx()); lineTo(x + 22.dp.toPx(), y + 16.dp.toPx()); close() }
        drawPath(arrow, if (state.phase in setOf(UiPointerPhase.PRESSED, UiPointerPhase.DRAGGING, UiPointerPhase.SCROLLING)) Color(0xFFFFCA7B) else Color.White)
        drawPath(arrow, Color(0xFF16243B), style = androidx.compose.ui.graphics.drawscope.Stroke(1.5.dp.toPx()))
    }
}

@Composable
private fun DesktopEditor(
    title: String, id: String, keyboard: DesktopKeyboardController, requester: FocusRequester,
    router: GestureUiRouter, hovered: String?, enabled: Boolean, modifier: Modifier,
    onFocus: () -> Unit, onContext: () -> Unit,
) {
    var textLayout by remember { mutableStateOf<TextLayoutResult?>(null) }
    var textBounds by remember { mutableStateOf(UiBounds(0f, 0f, 0f, 0f)) }
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    Column(modifier.background(DeskSurface, RoundedCornerShape(9.dp)).border(1.dp,
        if (keyboard.focusedEditorId == id) DeskAccent else DeskBorder, RoundedCornerShape(9.dp)).padding(10.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(title, color = DeskAccent, fontSize = 13.sp, lineHeight = 18.sp, maxLines = 1)
            DeskButton("입력 시작", Modifier.desktopTarget(router, "editor.$id.focus", enabled, layer = 4,
                hovered = hovered, onClick = onFocus))
            DeskButton("메뉴", Modifier.desktopTarget(router, "editor.$id.menu", enabled, layer = 4,
                hovered = hovered, onClick = onContext))
        }
        BasicTextField(value = keyboard.value(id), onValueChange = { keyboard.updateEditorValue(id, it) }, enabled = enabled,
            textStyle = TextStyle(color = Color(0xFFE7EEFC), fontSize = 16.sp, fontFamily = FontFamily.Monospace),
            cursorBrush = SolidColor(DeskAccent), onTextLayout = { textLayout = it },
            decorationBox = { inner ->
                Box(Modifier.fillMaxSize()) {
                    if (keyboard.value(id).text.isEmpty()) Text("여기에 입력됩니다. 입력 시작 → 화면의 키를 눌러 보세요.",
                        color = Color(0xFF8EA9CA), fontSize = 14.sp)
                    inner()
                }
            },
            modifier = Modifier.weight(1f).fillMaxWidth().heightIn(min = 72.dp).padding(top = 8.dp).verticalScroll(scroll).focusRequester(requester)
                .onFocusChanged { if (it.isFocused) keyboard.focusEditor(id) }
                .onGloballyPositioned { val b = it.boundsInRoot(); textBounds = UiBounds(b.left, b.top, b.right, b.bottom) }
                .desktopTarget(router, "editor.$id", enabled, hovered = hovered, touchClick = false,
                    onClick = {
                        onFocus()
                        val point = router.snapshot.cursor
                        val offset = if (point != null) textLayout?.getOffsetForPosition(Offset(point.x - textBounds.left, point.y - textBounds.top + scroll.value)) else null
                        if (offset != null) keyboard.updateEditorValue(id, keyboard.value(id).copy(selection = TextRange(offset), composition = null))
                    }, onContext = onContext, onScroll = { delta -> scope.launch { scroll.scrollBy(delta) } }))
    }
}

@Composable
private fun DesktopTaskList(router: GestureUiRouter, hovered: String?, enabled: Boolean, modifier: Modifier) {
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    var selected by remember { mutableStateOf(1) }
    Column(modifier.background(DeskSurface, RoundedCornerShape(9.dp)).padding(10.dp)) {
        Text("프로젝트 목록 · 선택 $selected", color = DeskAccent, fontSize = 13.sp)
        Column(Modifier.weight(1f).fillMaxWidth().desktopTarget(router, "tasks.scroll", enabled, layer = 0,
            hovered = hovered, onScroll = { delta -> scope.launch { scroll.scrollBy(delta) } }).verticalScroll(scroll)) {
            for (number in 1..30) DeskButton("문서 ${number.toString().padStart(2, '0')}", Modifier.fillMaxWidth().padding(vertical = 3.dp)
                .desktopTarget(router, "tasks.$number", enabled, hovered = hovered, parentId = "tasks.scroll", onClick = { selected = number }), selected == number)
        }
    }
}

@Composable
private fun DesktopDragBoard(router: GestureUiRouter, hovered: String?, enabled: Boolean, modifier: Modifier) {
    var board by remember { mutableStateOf(UiBounds(0f, 0f, 0f, 0f)) }
    var card by remember { mutableStateOf(Offset(12f, 28f)) }
    var cardSize by remember { mutableStateOf(Offset(120f, 52f)) }
    var grab by remember { mutableStateOf(Offset.Zero) }
    var last by remember { mutableStateOf(UiPoint(0f, 0f)) }
    var folder by remember { mutableStateOf(UiBounds(0f, 0f, 0f, 0f)) }
    var drops by remember { mutableStateOf(0) }
    var clicks by remember { mutableStateOf(0) }
    fun move(point: UiPoint) {
        last = point
        card = Offset((point.x - board.left - grab.x).coerceIn(0f, (board.width - cardSize.x).coerceAtLeast(0f)),
            (point.y - board.top - grab.y).coerceIn(0f, (board.height - cardSize.y).coerceAtLeast(0f)))
    }
    fun finish(cancelled: Boolean) { if (!cancelled && folder.contains(last)) drops++ }
    Column(modifier.background(DeskSurface, RoundedCornerShape(9.dp)).padding(10.dp)) {
        Text("작업 카드 · 클릭 $clicks / 놓기 $drops", color = DeskAccent, fontSize = 13.sp)
        Box(Modifier.weight(1f).fillMaxWidth().onGloballyPositioned { val b = it.boundsInRoot(); board = UiBounds(b.left, b.top, b.right, b.bottom) }) {
            Box(Modifier.align(Alignment.BottomEnd).size(105.dp, 65.dp).background(Color(0xFF234D47), RoundedCornerShape(7.dp))
                .onGloballyPositioned { val b = it.boundsInRoot(); folder = UiBounds(b.left, b.top, b.right, b.bottom) }, contentAlignment = Alignment.Center) {
                Text("보관함", color = Color(0xFF9FE3C1))
            }
            Box(Modifier.offset { IntOffset(card.x.roundToInt(), card.y.roundToInt()) }.size(125.dp, 54.dp)
                .background(Color(0xFF59447D), RoundedCornerShape(7.dp))
                .onGloballyPositioned { cardSize = Offset(it.size.width.toFloat(), it.size.height.toFloat()) }
                .desktopTarget(router, "card", enabled, layer = 5, hovered = hovered,
                    onClick = { clicks++ }, onDragStart = { point -> grab = Offset(point.x - board.left - card.x, point.y - board.top - card.y); last = point },
                    onDrag = ::move, onDragEnd = ::finish)
                .pointerInput(board, enabled) {
                    if (enabled) detectDragGestures(onDragStart = { p -> router.cancel("touch"); grab = p },
                        onDragEnd = { finish(false) }, onDragCancel = { finish(true) }) { change, delta ->
                        change.consume(); move(UiPoint(board.left + card.x + grab.x + delta.x, board.top + card.y + grab.y + delta.y))
                    }
                }, contentAlignment = Alignment.Center) { Text("잡아서 이동", color = Color.White, fontSize = 13.sp) }
        }
    }
}
