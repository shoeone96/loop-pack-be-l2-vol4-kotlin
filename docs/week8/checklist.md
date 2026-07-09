# Round 8 구현 체크리스트 — 주문 대기열 (Redis 기반)

> **마감: 2026-07-10 (금) 18:00** — 최종 제출 PR = upstream base `shoeone96`
> 원문 요구사항: `00-requirements.html`
> 브랜치: `feature/week08-waiting-queue` (origin/shoeone96 에서 분기 — week7 최종 머지 #108 포함)
> 매핑: 과제 Step 1 = 대기열 진입/순번 · Step 2 = 입장 토큰 & 스케줄러 · Step 3 = 실시간 순번 조회
> 대기열은 **주문 API 앞단의 관문**이고, 주문 이후 흐름(이벤트 발행 → Outbox → Kafka → streamer 집계)은 **R7 자산을 그대로 재사용**한다.

## 재사용 자산 (R7에서 이미 확보 — 새로 만들지 말 것)

| 필요 기능 | 재사용할 기존 자산 | 경로 |
|---|---|---|
| 주문 생성 진입점(대기열 부착 지점) | `OrderController.order` / `OrderFacade.place` (단일 `@Transactional`, `PENDING_PAYMENT`로 종료) | `order/interfaces`, `order/application` |
| Redis 원자 연산(Sorted Set 큐) | `masterRedisTemplate`(`ReadFrom.MASTER`) + `DefaultRedisScript` Lua 패턴 | `modules/redis` · `coupon/infrastructure/redis/CouponIssueGatekeeper.kt` |
| 비동기 접수 + 요청ID 폴링 조회 흐름 | 선착순 쿠폰 FCFS 전체 흐름(게이트 → 발행 → 결과 폴링) | `coupon/**` |
| 배치 드레인 스케줄러 | `@Scheduled(fixedDelayString=...)` + `@ConditionalOnProperty` on/off | `outbox/application/OutboxRelay.kt` · `payment/application/PaymentReconciler.kt` |
| 테스트 인프라 | Redis Testcontainers 픽스처 + `RedisCleanUp` | `modules/redis/src/testFixtures` |

> 가장 강력한 청사진은 **선착순 쿠폰(`coupon/**`)** — "Redis 게이트 + 비동기 처리 + 결과 폴링"이 대기열과 동형이다. 대기열은 이 패턴을 **주문 도메인 앞단**에 재적용하는 형태.

---

## Step 1 — Redis Sorted Set 기반 대기열

- [ ] **1. 대기열 진입 API** (`POST /api/v1/queue/enter`) — `ZADD queue:orders {timestamp} {userId}`. score = 진입 시각(순서 보장), member = userId(중복 진입 자동 방지). userId는 body 아닌 **인증 컨텍스트**(`@RequestAttribute(ACCOUNT_ID)`)에서 — cf. R7 결정 4(자칭 식별자 금지)
- [ ] **2. 순번 조회 API** (`GET /api/v1/queue/position`) — `ZRANK queue:orders {userId}`(0-based) → 사람이 읽는 순번(+1). 미진입/이미입장 상태 구분
- [ ] **3. userId 기반 중복 진입 방지** — Sorted Set member 유일성으로 자연 방지. 재진입 시 기존 score 유지할지 갱신할지 결정(공정성 = 최초 진입 시각 유지 쪽이 맞음 — 근거 WRITING-LOG)
- [ ] **4. 전체 대기 인원 조회** — `ZCARD queue:orders` (운영 지표 Queue Depth 겸용)
- [ ] **원자성/장애 정책** — 게이트키퍼처럼 `masterRedisTemplate` 주입 + Lua 필요 여부 판단(단순 ZADD/ZRANK는 단일 명령이라 Lua 불필요할 수 있음 — 과설계 경계). Redis 장애 시 fail-open/close 결정은 Step 3의 Graceful Degradation과 함께

## Step 2 — 입장 토큰 & 스케줄러

- [ ] **5. 배치 드레인 스케줄러** — `@Scheduled(fixedDelayString="\${queue.scheduler.fixed-delay:100}")` 로 `ZPOPMIN queue:orders {N}` → N명 꺼내 입장 토큰 발급. `@ConditionalOnProperty("queue.scheduler.enabled")` on/off. 빈 배치면 조용히 return + 처리량 로깅 (OutboxRelay 컨벤션 답습)
- [ ] **6. 입장 토큰 발급** — `SET entry-token:{userId} {token} EX {ttl}` (TTL 예: 5분). 토큰 발급 = 순번 조회 응답에 포함되어 유저에게 전달
- [ ] **7. 주문 API 진입 시 토큰 검증** — `OrderController.order` 앞단(또는 진입 필터)에서 `GET entry-token:{userId}` 확인 → 없으면 거부(403/409). FCFS `Gatekeeper.tryPass()`를 컨트롤러 진입부에서 호출하는 패턴 참고
- [ ] **8. 주문 완료 후 토큰 삭제** — `DEL entry-token:{userId}` (재사용 차단). 실패/보상 시 처리 정책 결정
- [ ] **9. 스케줄러 배치 크기 산정 근거 문서화** — 처리량 기준 계산. DB 커넥션 풀·주문 1건 평균 처리 시간 → 이론 최대 TPS × 안전 마진(70%) → 100ms당 배치 크기. **현 프로젝트 실제 Hikari 풀 크기·주문 처리 시간으로 산정**(요구사항 예시 수치 그대로 베끼지 말 것) — WRITING-LOG에 근거

## Step 3 — 실시간 순번 조회 (Polling)

- [ ] **10. 예상 대기 시간 계산** — `내 순번 / 초당 처리량`. 추정값이므로 "약 N분/초" 표현. 초당 처리량 = 스케줄러 배치 크기 × (1초 / fixedDelay)
- [ ] **11. Polling 응답 통합** — `GET /queue/position` 응답에 `{ position, estimatedWaitSeconds, token? }`. 내 차례(순번 0 도달)면 토큰 포함해 반환
- [ ] **12. Polling 부하 고려** — 대기 인원 × 조회 주기 = 초당 조회량. Redis라 감당 가능하나 부하 근거 명시. (Nice: 순번 구간별 동적 주기)

## 검증 (과제 필수)

- [ ] **동시 진입 테스트** — 동시 N명 진입 → 대기열 순서가 진입 시각 순으로 정확히 보장되는지 (`runConcurrently` 선례: `CouponIssueGatekeeperIntegrationTest`)
- [ ] **토큰 만료 테스트** — TTL 초과 시 토큰 무효화 + 만료분만큼 다음 유저에게 재발급되는지
- [ ] **처리량 초과 테스트** — 스케줄러 배치 크기 이상 요청이 들어와도 하류(주문 API)가 안정적인지(Thundering Herd 완화 검증)

## Nice-To-Have (Must 완료 후에만)

- [ ] SSE 기반 실시간 순번 Push (Polling 부하가 문제 될 때 전환)
- [ ] Polling 주기 동적 조절 (순번 구간별: 1~100=1s / ~1000=3s / 1000+=5s)
- [ ] Thundering Herd 완화 — 발급 간격 분산(100ms당 소량) / 토큰 Jitter / 주문 API 자체 Rate Limit
- [ ] Graceful Degradation — Redis 장애 시 Fallback 전략 사전 정의(전면 차단 / 우회 / Fallback 큐 중 택1 + 근거)

## 구현 완료 후 — Technical Writing (제출 필수 산출물)

- [ ] GitHub Issue 4포맷 중 1개 (Design Doc / Retrospective / Challenge Story / Benchmark Report)
- [ ] 블로그 글 — TL;DR 필수, "무엇을 했다"가 아니라 **"왜 그렇게 판단했나"** 중심
  - 주제 후보: Rate Limiting vs Queuing 선택 근거 / 스케줄러 배치 크기 산정 / Thundering Herd 완화 / Redis 장애 시 서비스 동작 / Polling vs SSE / 토큰 TTL 기준

---

## 결정 현황 (확정된 것 / 열린 것 — 근거는 WRITING-LOG)

### 확정 (2026-07-07)

1. ✅ **보호 대상 = `POST /orders` 단독** — 요구사항 명시 흐름 그대로. 결제(`POST /payments`)는 요구사항에 미등장 → 토큰은 **주문 생성 성공까지만** 커버, `place()` 성공 후 DEL. 결제 보호는 "주문 API 자체 Rate Limit"(Nice) 축.
2. ✅ **재진입 시 score = 최초 진입 시각 유지** — `ZADD NX` (기본 ZADD는 score 덮어쓰기 → 재접속 유저가 뒤로 밀림). 공식 근거: Redis ZADD 문서 NX 플래그, Cloudflare FIFO도 최초 도착 timestamp 보존.
3. ✅ **Redis 장애 = fail-close** (대기열 우회 금지 — 주문은 쿠폰과 달리 정합성 민감). Graceful Degradation 구현은 Nice, 문서화만.
4. ✅ **부하 실측 = 로컬 검증까지만** — Testcontainers 동시성·정확성 검증. EC2 SUT+k6는 스킵(금 7/10 18:00 마감 시간 예산).
5. ✅ **만료 유저 처리 = 재진입(맨 뒤)** — "기회를 줬는데 안 쓴 것"이라 공정성 논리로 방어 가능. 우선권 부여는 복잡도만 추가.
6. ✅ **토큰 TTL = 기본 5분, 프로퍼티(`queue.token.ttl-seconds`)로 외부화** — 커버 행위(주문서 확인→접수)가 1~2분이라 2~3배 여유. Cloudflare session duration 기본값(5분, 1~30분 범위)과 일치. TTL↑ = 동시 유효 토큰 상한(발급 속도×TTL)↑ 라 함부로 못 늘림 — 근거 WRITING-LOG.

### 열린 결정 (구현 중 확정)

7. **대기열 도메인 패키지 위치** — 신규 `com.loopers.queue.{domain,application,infrastructure,interfaces}` 유력 (FCFS `coupon` 독립 도메인 선례, 관심사·의존방향 분리). 코드 착수 시 확정.
8. **토큰 검증 위치** — 컨트롤러 진입부 직접 호출 유력 (FCFS `Gatekeeper.tryPass()` 선례, 관문 1개라 인터셉터는 투기적).
9. **전체 대기 인원 노출 방식** — `GET /queue/position` 응답에 포함 vs 별도 엔드포인트 (`ZCARD`).
10. **ZPOPMIN 원자성** — 꺼냄→토큰 발급 사이 장애 시 유저 증발(대기열에도 토큰에도 없음). Lua로 묶기 vs 복구 경로 정의 (01-deep-dive §10).
11. **동일 score(같은 ms 진입) 순서** — Redis는 사전순 정렬이라 엄밀 FIFO 아님. 과제 규모에선 수용+문서화 유력 (01-deep-dive §6).
12. **토큰 보유 중 재진입(파이프라이닝) 차단** — 입장된 유저는 ZSET에 없어 NX가 못 막음 → 주문서 작성과 다음 줄서기를 겹칠 수 있음. **enter에서 `GET entry-token` 선확인 → 보유자 거부** 한 줄로 닫는 것 권장 (2026-07-07 예외 전수 점검).
13. **검증(GET)–소모(DEL) 분리 race** — 더블클릭 동시 2발이 둘 다 검증 통과 가능. **(a) 수용+문서화 권장**: 대기열 책임은 유량 제어지 exactly-once가 아니고 중복 주문 방지는 주문 도메인 몫(관심사 분리). (b) GETDEL 원자화는 "실패=토큰 유지" 정책과 충돌해 복원 보상만 추가됨.
