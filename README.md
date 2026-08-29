# Limited Goods

Limited Goods는 한정 수량의 스트리머 굿즈를 구매하는 핵심 흐름을 다루는 contract-first 백엔드 프로젝트입니다. 여러 구매자가 한정 재고를 동시에 요청할 때 여러 상품을 원자적으로 점유하고, 결제를 확정하며, 구매가 끝나지 않으면 재고를 복구하는 과정에 집중합니다.

현재 브랜치에는 초기 비즈니스 계약과 아키텍처 기준이 정리되어 있으며, 애플리케이션 코드는 아직 구현되지 않았습니다.

## English Summary

Limited Goods is a contract-first backend project for purchasing scarce streamer merchandise. It focuses on atomic multi-item reservations, payment consistency, expiry recovery, and evidence-driven performance improvements.

![시스템 컨텍스트](docs/diagrams/system-context.svg)

## 초기 버전

- 구매자는 판매 시작 전에도 상품을 조회할 수 있지만, 재고 점유는 시작 시각 이후에만 가능하다.
- 여러 상품을 요청하면 전부 점유하거나 아무것도 점유하지 않는다.
- 결제를 시도하는 동안 재고를 제한된 시간 동안 점유한다.
- PostgreSQL을 재고·주문·점유·결제 시도의 영속적인 기준으로 사용한다.
- 실제 결제 없이 성공·실패·지연·중복 콜백을 재현하는 Mock PG를 사용한다.
- Python 기반 기능 중심 모듈러 모놀리스로 시작하며, 로컬에서도 컨테이너로 실행한다.

## 문서

- [PROJECT.md](PROJECT.md): 비즈니스 범위, 규칙, 아직 확정하지 않은 정책
- [DESIGN.md](DESIGN.md): 아키텍처 방향과 품질 검증 전략
- [시스템 개요](docs/architecture/system-overview.md): 시스템 경계와 실행 책임
- [도메인 모델](docs/architecture/domain-model.md): 핵심 데이터 관계와 불변식
- [구매 흐름](docs/architecture/purchase-flow.md): 상태 전이와 실패 경로
- [아키텍처 결정 기록](docs/decisions/README.md): 주요 기술 선택의 이유
- [AGENTS.md](AGENTS.md): 현재 상태와 코딩 에이전트 작업 규칙

## 이전 프로젝트

완료된 Java/Spring v0~v3.2 이력은 `archive/java-spring-v3.2` 브랜치에 보존되어 있습니다. 버전 태그와 실험 기록은 참고 근거이며, 새 서비스의 구현 청사진으로 사용하지 않습니다.
