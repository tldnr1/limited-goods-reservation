# 아키텍처 결정 기록

ADR은 되돌리기 비싸거나 시스템 구조와 품질에 큰 영향을 주는 선택을 기록합니다. 비즈니스 규칙은 [PROJECT.md](../../PROJECT.md)에, 상태와 데이터 관계는 아키텍처 문서에 둡니다.

| ADR | 상태 | 결정 |
|---|---|---|
| [0001](0001-feature-oriented-modular-monolith.md) | 승인 | 기능 중심 모듈러 모놀리스로 시작한다. |
| [0002](0002-postgresql-as-source-of-truth.md) | 승인 | PostgreSQL을 영속 상태의 기준으로 사용한다. |
| [0003](0003-separate-payment-attempts.md) | 승인 | 주문과 결제 시도 기록을 분리한다. |

결정이 바뀌면 기존 기록을 삭제하거나 다시 쓰지 않고 `Superseded`로 표시한 뒤 대체 ADR을 연결합니다.
