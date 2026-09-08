# 구매와 결제 상태

Order: PAYMENT_PENDING → PAYMENT_PROCESSING → CONFIRMED.
결제 미시도 또는 실패가 확인된 주문은 기한이 지나면 EXPIRED가 된다.
Reservation: ACTIVE → CONFIRMED 또는 EXPIRED. 한 번 끝난 점유는 다시 변경하지 않는다.

PaymentAttempt는 CREATED로 영속 접수하고 Worker가 PG에 제출한다.
현재 Worker는 실행 중 표시를 lease에 기록하고, 결과에 따라 SUCCEEDED/FAILED/UNKNOWN으로 바꾼다.
PROCESSING 상태는 스키마에서 허용하지만 이번 Worker는 별도 전이하지 않는다.

정상 성공: held → sold.
확정 실패: 기한 안이면 PAYMENT_PENDING으로 돌아가 새 결제 시도가 가능하다.
기한 밖이면 주문 만료와 함께 held → available.
UNKNOWN: 재고를 보유하고 새 시도를 막는다. 1초 간격으로 같은 PG 멱등키를 다시 확인한다.
PG가 이미 성공했다면 확인 시각이 기한 뒤여도 재고를 유지한 상태에서 확정한다.

콜백은 테스트용 공유 비밀과 금액을 검사한다. 이미 반영한 같은 결과는 no-op이다.
상충하는 최종 결과는 409다. 실제 결제사의 서명 검증/웹훅 규약은 범위 밖이다.
