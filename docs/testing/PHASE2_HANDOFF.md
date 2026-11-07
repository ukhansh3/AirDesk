# AirDesk Phase 2 구현·검증 인계

기준: `dev` `e46aec44c9129efe028e15f07c8bd4c0edcd36a6`.
승인된 설계: [ADR-003](../decisions/ADR-003-PHASE2-SECURE-CONTROL.md).

코드 기반과 자동 검증 결과를 인계한다. **Phase 2 전체 통합 완료로 보고하지 않는다.**
실제 WSS 테스트가 실패했고 Android↔Windows 연결 및 차량 안전 검증은 남아 있다.
`NOT_RUN`/차단 항목은 성공이 아니며, 아래 결과와 한계를 구분한다.

## 범위와 현재 안전 상태

- Phase 1 Python diagnostic WS는 `127.0.0.1:8765`에서 읽기 전용 커서를 유지한다.
- Phase 2 Windows Host는 별도 loopback WSS `127.0.0.1:8766`을 사용한다. emulator 주소는 `wss://10.0.2.2:8766`이다.
- `/control`은 pairing/session, `/input`은 검증된 실제 입력 권한 경계, `/mock-input`은 명시적 Mock 입력 경로다. JSON Schema는 그대로 유지하며 승인된 endpoint 역할을 분리한다.
- Mock 입력도 인증·paired identity·session·host consent·capability·heartbeat·revoke 검사를 거친다. Android는 resumed lifecycle과 화면 이탈을 로컬에서 검사한다. Host가 독립적으로 foreground/차량 상태를 증명하는 경로는 아직 없다. Mock authority는 native permit을 만들 수 없고 `/input`으로 승격되지 않는다.
- 실제 차량 상태/권한/신선도와 Windows가 검증할 안전 증거가 검증되지 않았으므로 기본 gateway는 `UNKNOWN`이다. 실제 Windows 입력 활성화는 차단 상태로 남긴다.
- 화면 스트리밍은 없다. `VIEW_VIDEO`는 계약상 예약 capability이며 Phase 2에서 `STREAM_UNAVAILABLE`로 거부한다.
- 컴퓨터 유즈, 카메라·에뮬레이터·WPF 앱 자동 실행 및 실제 OS 입력은 검증 과정에서 수행하지 않는다.

## 구현·검증 상태

| 항목 | 상태 | 증거/한계 |
| --- | --- | --- |
| Phase 1 Python 회귀 | PASSED | 정상 dev 체크아웃에서 50개, 실패/skip 없음 |
| 공통 wire conformance | PASSED | Python 5개 테스트, 합성 wire fixture 17개; 인증 동작 증명은 아님 |
| Windows pairing/session/mock/native gate | 코드 구현 / 정책 테스트 PASSED | 22개 정책·계약 사례 통과. 실제 네트워크 mTLS는 아래 실패와 분리 |
| Windows WPF 빌드 | PASSED | Release 빌드, 경고 0·오류 0; WPF 창은 실행하지 않음 |
| Android pairing/session/touch/keyboard | 코드 구현 / JVM 테스트 PASSED | 공개 키 등록·승인 UI, touch/drag/scroll/text/modifier/gesture, receipt queue; 실제 UI는 미실행 |
| Android JVM 테스트/assembleDebug | PASSED | 40개, 실패/오류 0; 기존 Phase 1 11개 포함. UI/Keystore 실기 실행 증명은 아님 |
| 실제 loopback WSS 양성 테스트 | FAILED | `0x8009030E / SEC_E_NO_CREDENTIALS`로 TLS handshake 실패 |
| 실제 TLS 인증 거부 테스트 | NOT_RUN | 양성 TLS가 먼저 실패하여 거부 원인을 인증 정책으로 입증할 수 없음 |
| 실제 WSS Host↔Android 상호 인증/승인 | NOT_RUN | 아래 사용자 수동 확인 필요; 컴파일·정책 사례로 대체하지 않음 |
| 실제 PLEOS SDK·권한·정차 검증 | NOT_VERIFIED | 일반 Android/임의 PARKED 값으로 대체하지 않음 |
| 실제 Windows OS 입력 | NOT_RUN / BLOCKED | UNKNOWN safety 환경에서 실행하면 안 됨 |

최종 순서 실행: Python 50 → 공통 계약 5 → Windows harness → WPF build → Android JVM/build.
전체 하네스 종료 코드는 **1 (실패 포함)**이다. Windows 콘솔 원문 집계는
`{"passed":22,"failed":2,"nativeCalls":0}`이며, 미통과 두 사례는 실제 TLS 양성 실패 1개와
그 전제가 충족되지 않아 수행되지 않은 TLS 거부 그룹 1개다. 실패를 제거·우회하지 않았다.
Android 40개 중 기존 Phase 1 테스트 11개를 유지한다. 17개 공통 wire fixture는 Python/C#/Kotlin에서 검사한다.

## TLS 실패 분석과 구현 한계

- 보호된 임시 키 재로딩 후 RSA/ECDSA 모두 `HasPrivateKey=true`, `IsEphemeral=false`임을 확인했다.
  그래도 최소 SslStream 대조 연결에서 오류가 재현됐다. 클라이언트 인증서를 전혀 쓰지 않는 경우도
  실패했고, 별도 Python 표준 SSL 클라이언트 → 최소 .NET TLS 서버 대조에서도 서버가 같은 오류를 냈다.
  따라서 AirDesk JSON/페어링 처리 전에 실패하는 실행 환경/Schannel 문제로 추정한다.
  제한 환경 밖에서 재현·확인하기 전까지 구현 결함을 완전히 배제하거나 실제 mTLS 성공을 주장하지 않는다.
- 테스트 전후 사용자 보호 키 파일 수는 4개로 동일했다. 테스트는 기존 키 내용을 읽거나 변경하지 않았고,
  운영 Host CNG 생성·Android Keystore 실행을 하지 않았다. 진단 mode의 종료 코드/출력은 정상 TLS 증명이 아니다.
- 모의 입력을 끄거나 탭을 떠나면 세션을 종료하며 다시 PC 승인이 필요하다. 이전 mock 소켓의 늦은 정리가
  아주 빠르게 재요청한 같은 장치의 새 세션도 종료할 수 있다. 안전하게 잠기지만 재연결/재승인이 필요할 수 있다.
- 장치 철회 저장이 실패하면 메모리 권한·held state는 즉시 해제하지만 재시작 뒤 철회 지속성은 보장하지 못한다.
  Host의 오류를 해결하고 다시 철회해야 한다. 이 실패를 성공 상태로 표시하지 않는다.
- Native 코드는 포인터·버튼·스크롤·Unicode·키 up/down을 포함하지만 발급 가능한 LiveInputPermit이 없다.
  W3C 이름의 물리 키는 현재 VK 매핑이므로 비-US 배열의 물리 위치 의미는 미완성·미검증이다.
  실제 한글/IME/앱 호환성, DPI/다중 모니터 OS 결과, 실제 해제 동작도 실행 검증하지 않았다.
- receipt queue는 미전송 pointer.move만 합치고 버튼/키 순서를 유지한다. 지연/FPS/오인식률은 측정하지 않았다.
  Android 전송 요청 수와 Host Mock 실행 집계는 서로 다른 값이다.

## 변경 범위와 계약

Windows 담당, Android 담당, Security/Protocol 검토 및 Integration/QA 담당을 분리해 구현·교차 검토했다.
공통 `contracts/**/*.schema.json`은 **변경하지 않았다**. Phase 1 Python, streaming, Stay+도 변경하지 않았다.
새 Android 권한·외부 패키지를 추가하지 않았다. .NET 기존 ASP.NET Core shared framework를 사용한다.
새 loopback WSS 경로와 공개 bootstrap 형식은 승인 ADR-003과 API 구현 문서에 기록했다.
API 문서의 오래된 미구현 설명과 비정규 오류명 `UNSUPPORTED`를 정정했다.

주요 수정·추가 파일:

- `windows-host/AirDesk.Core/{Protocol,DeviceRegistry,HostCoordinator,Input,SecureHostService}.cs`, project 파일.
- `windows-host/AirDesk.Host/{MainWindow.xaml,MainWindow.xaml.cs,WindowsIdentity.cs,DisplayCatalog.cs,app.manifest,AirDesk.Host.csproj}`.
- `windows-host/AirDesk.Host.Tests/{Program.cs,AirDesk.Host.Tests.csproj}`, `windows-host/README.md`, `Run-AirDeskHost.cmd`.
- Android `MainActivity.kt`, `DeviceIdentity.kt`, `TrustBootstrap.kt`, `ScopedTls.kt`, `SecureClient.kt`, `SecureSession.kt`,
  `Phase2Screen.kt`, `InputCandidates.kt`, `ReceiptBarrier.kt`, `StrictJson.kt`, `Phase2Schema.kt`.
- Android 신규 JVM 테스트 7개 파일, `app/build.gradle.kts` 테스트 resource, `android-client/README.md`.
- `scripts/verify-phase2.ps1`, `integration-tests/test_phase2_contract_harness.py`, `fixtures/phase2-wire-cases.json`, integration README.
- ADR-003, Phase 2 API 구현 문서, 보안 검토, API/기능/설계/가이드/README, Phase 1·2 handoff, Windows 수동 CI.

카메라 원본, 개인키, 실제 토큰, 실제 입력 기록은 커밋하지 않는다. 변경 대상 파일을 기존 로컬 dev 토큰과
개인키 PEM 표식으로 검사해 유출 항목 0개를 확인했다. 빌드·테스트 결과 파일은 ignored `out/`에 둔다.

## 자동 하네스 실행

프로젝트 루트의 PowerShell에서 실행한다. 하네스는 앱/에뮬레이터를 실행하지 않으며 local.properties의 실제 비밀값을 읽지 않는다.

```powershell
.\scripts\verify-phase2.ps1 -PythonExe '.\gesture-engine\.venv\Scripts\python.exe' -GradleExe 'gradle'
```

기존 외부 Python 가상환경을 재사용할 수도 있다. 하네스가 `PYTHONPATH`를 이 체크아웃의 `gesture-engine/src`로 지정하므로 설치된 다른 AirDesk 코드가 테스트되는 것을 막는다.

선택 매개변수:

- `-JavaHome '<JDK 경로>'`: Gradle용 JDK. Android Studio 번들 JBR 사용 가능.
- `-AndroidSdk '<SDK 경로>'`: 현재 compileSdk 및 필요한 build-tools가 있는 SDK.
- `-GradleUserHome '<캐시 경로>'`: 기존 검증 캐시 재사용. 기본은 ignored `out/phase2-verification/gradle-home`.
- `-ReportPath '<JSON 결과 경로>'`: 기본 `out/phase2-verification/verification.json`.
- `-SkipWindows`, `-SkipAndroid`: 부분 검증. 건너뛴 항목은 `NOT_RUN`이며 전체 성공으로 표시하지 않는다.

하네스 단계:

1. Python Phase 1 단위·계약·격리 loopback 테스트.
2. `integration-tests/test_phase2_contract_harness.py`: 공통 스키마와 엄격한 JSON wire fixture.
3. `dotnet run --project windows-host/AirDesk.Host.Tests/AirDesk.Host.Tests.csproj --configuration Release`: 안전한 Mock/security console harness.
4. `dotnet build windows-host/AirDesk.Host/AirDesk.Host.csproj --configuration Release`.
5. Android `:app:testDebugUnitTest :app:assembleDebug`.

Windows console은 `AIRDESK_HOST_TESTS {"passed":N,"failed":0,"nativeCalls":0}` 요약을 내야 한다. native call이 있거나 요약이 없으면 하네스가 실패 처리한다.
종료 코드는 전체 통과 `0`, 실패 `1`, 미실행 포함 `2`다. 보고서에는 단계별 상태·종료 코드·시간·검사 집계만 저장하며 전체 콘솔 로그·텍스트 입력·토큰을 저장하지 않는다.

Windows Schannel은 메모리 임시 키 인증서에서 `0x8009030E`가 발생할 수 있다.
따라서 Windows 테스트는 합성 인증서만 메모리 안의 암호화 PFX로 왕복하여 `UserKeySet`으로
임시 로드하고 Dispose 시 정리한다. 개인키 파일을 출력하거나 신뢰 저장소에 인증서를 추가하지 않는다.
Codex의 제한 환경에서는 사용자 보호 `Microsoft/Crypto/Keys` 경로 쓰기 권한이 별도로 필요할 수 있다.
이 테스트 키는 실제 Host CNG/Android Keystore 키와 별개다.
[Microsoft 원인 설명](https://learn.microsoft.com/en-us/dotnet/core/extensions/sslstream-troubleshooting#handshake-failed-with-ephemeral-keys).

환경 참고: 최초 .NET SDK 빌드에서 SDK가 자체 개발용 HTTPS 인증서 설치 메시지를 출력했다.
이후 하네스·런처·CI는 `DOTNET_GENERATE_ASPNET_CERTIFICATE=false`를 설정한다. AirDesk가
기존 인증서를 신뢰 처리하거나 내보내거나 삭제한 것은 아니며, 실제 Host 키 생성은 사용자 시작 버튼에서만 수행한다.

## 사용자 수동 검증 순서

아래는 구현된 버튼을 사용하는 사용자 검증 흐름이다. 이 절차를 에이전트가 실제 실행한 것은 아니다.

1. 먼저 Codex 제한 환경 밖의 일반 PowerShell에서 위 자동 하네스를 실행한다. TLS 양성·거부 그룹이 통과하는지 확인한다.
   이어 프로젝트 루트의 `Run-AirDeskHost.cmd`를 직접 실행하고 **보안 Host 시작**을 누른다.
   Stay+가 아닌 이 저장소의 별도 WPF 앱이며 기본 입력은 잠금 상태여야 한다.
2. Android AirDesk에서 장치 키를 생성한다. 개인키가 아닌 **공개 장치 정보**만 Host에 전달한다.
3. Host에서 장치 연결을 명시적으로 시작하고 일회용 8자리 코드를 PC 화면에서만 확인한다.
4. 두 화면에서 공개 인증서의 전체 SHA-256 지문을 확인하고, Host에 Android 공개 장치를 등록한다. 공개 host 연결 정보에는 주소·공개 인증서·공개 pairingId만 들어가며 코드/개인키/Grant는 포함하지 않는다.
5. Android에서 host 공개 연결 정보를 가져와 지문을 확인한 후 상호 TLS 연결한다. 알 수 없는 인증서를 무조건 허용하지 않는다.
6. PC 화면의 코드를 Android에 입력하고 Android에서 명시적으로 승인한다. Host에서도 요청한 장치 신원을 확인해 승인한다. 한쪽만 승인한 상태는 paired/권한 허용으로 표시하면 안 된다.
7. Android에서 POINTER/KEYBOARD 세션을 요청하고 PC에서 해당 세션을 승인한다. pairing 완료만으로 세션이나 입력 권한이 생기면 안 된다.
8. 화면에 명확히 표시된 Mock 입력을 선택한다. Android 터치·텍스트·특수 키·제스처 후보가 Windows Mock backend의 상태/집계에 반영되는지 확인한다. 실제 Windows 포인터/키보드는 움직이면 안 된다.
9. Mock 버튼/키를 누른 상태에서 연결 종료·앱 background·Host 권한 회수·세션 만료·heartbeat 중단을 각각 확인한다. held state는 해제되고 재연결 후 입력 권한이 자동 복원되면 안 된다.
10. UNKNOWN/DRIVING/UX_RESTRICTED/UNSUPPORTED 및 오래된 안전 신호에서 실제 입력 경로가 잠기는지 확인한다. Mock PARKED로 실제 입력을 열 수 없어야 한다.
11. 잘못된 코드·만료 코드·5회 실패 후 5분 cooldown·이미 사용한 코드·폐기한 장치 재접속을 확인한다. 동일 pairing code를 재사용할 수 없어야 한다.
12. Phase 1 PC 실행 화면과 diagnostic cursor를 다시 사용해 기존 기능이 유지되는지 확인한다.

Android Studio에서는 `android-client/`를 열어 Sync 후 `app`을 사용자 PLEOS 에뮬레이터에 직접 실행한다.
이번 작업은 기존 실행 중인 에뮬레이터나 Stay+ 앱을 조작하지 않았다. CNG/Keystore 실제 생성과 양방향 TLS의
첫 수동 결과를 확인한 후, 이 문서의 NOT_RUN 항목을 실제 결과로 갱신한다.

코드 TTL은 120초, 양측 승인 대기 60초, session/grant 최대 10분, heartbeat 1초/host watchdog 3초다. 시간이 지난 연결을 heartbeat만으로 연장하거나 새 consent 없이 재활성화하지 않는다.

## 거부·장애 테스트 체크리스트

- 인증서 누락/다른 pin/미등록·폐기 peer, valid JSON의 `side=HOST` 사칭.
- 코드 만료 경계/잘못된 코드/동시 claimant/재사용/취소/재시작 후 복구.
- 임의·다른 peer/session·만료·폐기 Grant, capability 부족, VIEW_VIDEO 요청.
- 중복/낮은 sequence·이전 session 재생·stale packet·clock 역행·재접속 시 이전 권한 재사용.
- Mock 경로 정상 처리, UNKNOWN 실제 경로 거부, Mock/native authority 혼동 방지.
- revoke/watchdog와 dispatch 경쟁, grant 만료 후에도 소유 held state cleanup 가능.
- 잘못된 display/좌표/음수 virtual desktop 원점/DPI 차이/모니터 제거.
- 허용하지 않은 key code/중복 down/Unicode 잘못된 surrogate/IME composition 미전송.
- lifecycle/연결/프로세스 종료 시 held key/button 해제 및 재연결 시 fresh consent.
- 큰 JSON/unknown field/version/duplicate property/폭주, 실제 입력 문자열·비밀값 로그 없음.

## Phase 3 전 남은 검증

실제 PLEOS SDK·권한·freshness와 Host가 신뢰할 수 있는 차량 안전 증거, 사용자 기기에서의 인증서/키 저장·폐기·재설치 동작,
실물 WSS 연결, DPI/다중 모니터 및 Unicode application 동작은 별도 검증한다.
이것이 충족되기 전에는 native 입력을 활성화하지 않는다. 스트리밍 프로토콜/라이선스/권한/지연은 Phase 3의 독립 gate다.
