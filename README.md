# Limited Goods

Limited Goods는 한정 수량의 스트리머 굿즈를 구매하는 핵심 흐름을 다루는 contract-first 백엔드 프로젝트입니다. 여러 구매자가 한정 재고를 동시에 요청할 때 여러 상품을 원자적으로 점유하고, 결제를 확정하며, 구매가 끝나지 않으면 재고를 복구하는 과정에 집중합니다.

현재 브랜치에는 초기 비즈니스 계약을 실행하는 첫 번째 수직 흐름이 구현되어 있습니다. 판매 등록과 조회, 다중 상품의 원자적 점유, 동일 주문의 결제 재시도, 성공·실패·지연·중복·미확정 Mock PG 응답, 점유 만료와 재고 복구를 하나의 로컬 컨테이너 환경에서 확인할 수 있습니다.

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

## 실행

Docker가 실행 중인 환경에서 다음 명령으로 API, Worker, PostgreSQL, Mock PG, Prometheus를 시작합니다. Compose 프로젝트 이름은 과거 실험 리소스와 겹치지 않도록 `limited-goods-next`로 고정되어 있습니다.

```powershell
docker compose up -d --build
docker compose ps
```

| 용도 | 주소 |
|---|---|
| API 문서와 직접 호출 | http://localhost:8000/docs |
| API 준비 상태 | http://localhost:8000/health/ready |
| API 메트릭 | http://localhost:8000/metrics |
| Mock PG | http://localhost:8080 |
| Prometheus | http://localhost:9090 |
| PostgreSQL | `localhost:5434` |

실행을 멈출 때는 `docker compose down`을 사용합니다. 이 명령은 데이터 볼륨을 보존합니다.

## 테스트

테스트도 동일한 PostgreSQL 이미지와 별도의 `limited_goods_test` 데이터베이스를 사용합니다.

```powershell
docker compose --profile test run --rm test
```

현재 자동화 검증은 마지막 재고에 대한 동시 요청, 같은 사용자의 구매 제한 경쟁, 다중 상품 전체 실패, 구매·결제 멱등성, 점유 만료, 실패 후 동일 주문 재시도, 결과 미확정 차단, 중복 결제 성공의 단일 반영을 포함합니다.

## 구현 구조

```text
src/limited_goods/
├─ sales/          판매와 상품·재고
├─ purchases/      주문과 구매 요청 조정
├─ reservations/   점유·확정·만료
├─ payments/       결제 시도와 결과 정합성
├─ main.py         HTTP API
├─ worker.py       만료·결제 확인 작업
└─ mock_pg.py      모의 결제사
```

DB 스키마 변경은 Alembic으로 관리하고, 구조화 JSON 로그와 Prometheus 메트릭을 제공합니다.

## 현재 경계

- `X-User-Id` 헤더는 확정된 인증 방식이 아니라 초기 구매자 경계를 표현하는 임시 수단이다.
- 판매 등록 API는 테스트 준비용이며 운영자 인증·UI를 포함하지 않는다.
- 실제 결제·배송·취소·환불은 범위 밖이고 Mock PG만 사용한다.
- 성능 목표와 Redis·대기열 같은 최적화는 기준 구현을 부하 측정한 뒤 결정한다.

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
