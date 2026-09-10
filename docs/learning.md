# FastAPI 경험에서 Spring 코드 읽기

먼저 ops/smoke.ps1의 JSON 요청 하나를 보고 다음 순서로 같은 요청을 따라간다.

1. PurchaseController: @PostMapping이 라우팅, @RequestHeader가 헤더, @RequestBody가 JSON 역직렬화다.
2. PurchaseRequest: Java record가 DTO다. @Valid와 @NotNull/@Positive가 Pydantic의 입력 검증에 대응한다.
3. PurchaseService: 생성자 인자로 받은 객체를 Spring이 주입한다. @Service로 등록된 bean을 재사용한다.
4. PurchaseTransactionService: 별도 bean의 @Transactional 프록시가 시작/커밋/롤백한다.
5. OrderRepository/SaleRepository: EntityManager로 조회·저장·락을 실행한다.
6. ReservationService: 점유의 생성·확정·반환 규칙을 묶는다.
7. OrderView: entity 대신 응답 record를 만들고 Jackson이 JSON으로 바꾼다.
8. Errors: @RestControllerAdvice가 도메인 오류·검증 실패를 HTTP 상태와 code로 바꾼다.

## FastAPI와 대응시키기

| 익숙한 개념 | 여기서 찾을 것 |
|---|---|
| APIRouter | @RestController / @RequestMapping |
| Pydantic schema | record DTO + Jakarta Validation + Jackson |
| Depends | 생성자 주입. bean 등록은 @Service/@Repository/@Configuration의 @Bean |
| 요청 DB Session | 트랜잭션에 연결되는 EntityManager 프록시 |
| SQLAlchemy model | @Entity, @Id, 컬럼과 테이블 |
| session.begin/commit | 다른 Spring bean을 통한 @Transactional 호출 |
| DB pool | HikariCP, application.yml의 maximum-pool-size |
| background worker loop | 기본 4개 슬롯이 DB lease 작업을 연속 처리. @Scheduled는 빈 큐 재확인만 담당 |

DI가 매 요청마다 모든 객체를 생성한다는 뜻은 아니다. 기본 bean은 singleton이다.
그래서 Service 필드에 특정 사용자의 요청 상태를 보관하면 안 된다.
현재 entity 필드는 코드 탐색을 간단히 하기 위해 직접 접근하지만, 변경은 트랜잭션 Service 안에서 수행한다.

## 먼저 이해해야 할 Java/Spring 개념

- Java record, class, 생성자, 접근 제어, enum, List/Map, 제네릭과 예외.
- 객체 참조와 동등성: UUID/String은 == 대신 equals.
- JVM heap/stack, GC, 플랫폼 스레드, synchronized의 프로세스 내부 범위.
- Bean 생명주기와 생성자 주입. 테스트의 @Primary Clock으로 시간만 교체하는 이유.
- @Transactional self-invocation 문제: 같은 객체의 메서드 호출은 프록시를 거치지 않는다.
- JPA 영속성 컨텍스트와 dirty checking: 필드 변경이 왜 커밋 때 UPDATE가 되는가.
- flush와 commit 차이, READ COMMITTED snapshot, 이전에 로드한 entity의 stale state.
- open-in-view=false인 이유, DTO 변환 시점, N+1 조회와 SQL 확인.
- MVC 처리 스레드, Hikari 커넥션, Worker 슬롯은 각각 다른 자원이다.

## 이 프로젝트로 이어지는 CS 질문

ContractTest의 동시 요청 테스트 하나를 설명하고 SQL/락 대기까지 따라간다.
그 뒤 비관적 락·조건부 UPDATE·낙관적 버전 비교의 충돌 비용과 재시도 범위를 비교한다.
클라이언트가 timeout을 봐도 서버 커밋은 끝났을 수 있다는 점을 PG 응답 유실 테스트와 연결한다.
Worker를 늘려도 hot-row가 빨라지지 않는 이유, 커넥션풀을 무작정 늘리면 생기는 대기를 설명한다.
TCP 종료/TIME_WAIT/keep-alive/파일 디스크립터와 HTTP 커넥션풀을 구분한다.
면접용 개념을 위해 기술을 추가하지 않고, 실제 실행 경로의 경계를 설명하는 데 집중한다.

## 과거 archive와 차이

이전 Java의 단일 product 예약·Redis 재고 감소 흐름을 그대로 복구하지 않았다.
새 코드는 다중 상품, 주문+점유+여러 결제 시도, 기한, 실패/UNKNOWN, 별도 Worker를 가진다.
이전 Python의 snake_case JSON은 새 API에서 camelCase로 변경했다.
구매 입력은 saleId/items[].saleItemId/quantity, 결제 접수는 202와 attempt 요약으로 바뀌었다.
복잡한 전략 선택 계층 없이 각 기능의 실제 실행 경로를 먼저 읽을 수 있게 했다.
