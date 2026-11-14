# 문서 목차

| 읽는 목적 | 문서 |
|---|---|
| 처음 받은 담당자의 실행·코드 탐색 | [검토 가이드](REVIEW_GUIDE.md) |
| 이번 빌드·테스트·미검증 범위 | [2026-11-14 전달 검증](testing/2026-11-14-review-handoff.md) |
| 현재 제품 방향 | [ADR-004 앱 내부 입력](decisions/ADR-004-INPUT-FIRST.md) |
| desktop DEMO 구현 결정 | [ADR-005](decisions/ADR-005-DESKTOP-DEMO-INPUT.md) |
| AIR 타건 실험 | [AIR_TYPING_SPEC](features/AIR_TYPING_SPEC.md) · [검증·시연 안내](testing/AIR_TYPING_HANDOFF.md) |
| 한 대의 웹캠·키보드 참조 | [ADR-007](decisions/ADR-007-SINGLE-WEBCAM-KEYBOARD-OVERLAY.md) · [ADR-008 TKL](decisions/ADR-008-TKL-CAMERA-REFERENCE.md) |
| PC형 키보드 | [VIRTUAL_KEYBOARD_SPEC](features/VIRTUAL_KEYBOARD_SPEC.md) |
| 마우스형 앱 내부 조작 | [GESTURE_UI_INTERACTION_SPEC](features/GESTURE_UI_INTERACTION_SPEC.md) |
| 현재 DEMO 인수 체크리스트 | [PHASE2_REVISED_HANDOFF](testing/PHASE2_REVISED_HANDOFF.md) |
| 과거 Windows Host 연구 결과 | [PHASE2_HANDOFF](testing/PHASE2_HANDOFF.md) |

현재 방향은 앱 소유 Compose 화면 안의 입력입니다. 과거 원격 데스크톱 설계·TLS 실패 기록은 보존하지만, 현재 필수 실행 경로나 완료된 기능으로 읽지 않습니다. 실제 차량 SDK·카메라 지원은 별도 검증 대상입니다.
