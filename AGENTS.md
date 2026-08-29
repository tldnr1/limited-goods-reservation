# AGENTS.md

새 Limited Goods 프로젝트에서 코딩 에이전트가 처음 읽는 문서입니다.

## 현재 상태

```text
phase: design baseline complete
current_goal: implement the initial end-to-end purchase flow
implementation: none
language_direction: Python
framework: undecided
persistence: PostgreSQL
architecture: feature-oriented modular monolith
legacy_reference: archive/java-spring-v3.2 and version tags
```

## 다음으로 읽을 문서

```text
프로젝트 목적이나 열려 있는 비즈니스 질문이 필요한가?
-> PROJECT.md

설계 원칙이나 열려 있는 기술 질문이 필요한가?
-> DESIGN.md

상태, 데이터 관계, 결정 근거가 필요한가?
-> docs/architecture 와 docs/decisions
```

## 작업 규칙

- `PROJECT.md`와 `DESIGN.md`에서 확정하지 않은 항목을 암묵적인 요구사항으로 취급하지 않는다.
- 구현 세부사항을 선택할 때 문서화된 비즈니스 흐름, 상태, 불변식, 실패 동작을 보존한다.
- 보관된 Java/Spring 구현을 이식하거나 이어서 개발하지 않는다.
- 새 결정에 비교 근거가 필요할 때만 과거 실험을 참고한다.
- 변경을 최소화하고 되돌리기 비싼 결정은 구현 전에 기록한다.
- 소유자와 목적이 명확할 때만 문서나 디렉터리를 추가한다.
