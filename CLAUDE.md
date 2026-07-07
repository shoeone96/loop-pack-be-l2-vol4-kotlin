# CLAUDE Working Guide

> 이 파일은 `AGENTS.md`를 Claude가 빠르게 파싱하도록 재작성한 본입니다.
> 충돌 시 `AGENTS.md`가 정답입니다 (이 파일이 누락/오역일 수 있음).
> 섹션 순서가 우선순위 — 위 섹션이 아래 섹션을 이깁니다.

## 0. Triggers — 이 상황이면 이 섹션부터

| 상황 | 섹션 |
|---|---|
| 비즈니스/애플리케이션 실패를 던지려 할 때 | §2 Error Handling |
| 새 도메인 에러 코드 정의 | §2 Error Handling |
| 컨트롤러/API 슬라이스 구현 시작 | §12 Account Notes — Implementation Direction |
| 컨트롤러 테스트 작성 | §12 Account Notes — Controller Test Strategy |
| 리포지토리/JPA 테스트 작성 | §12 Account Notes — Repository Test Strategy |
| 응답 DTO/래핑 결정 | §12 Account Notes — API Response Wrapping |
| 새 레이어/Facade/추상화 도입 충동 | §3 Design Principles, §12 |
| 버전/라이브러리 사용법 확인 | §10 Versions |
| 커밋/PR | §9 Git / PR |
| 도메인 어휘 / 인증 / URL / 사용자 호칭 / DTO 표기 / 시나리오 문구 | `docs/ubiquitous-language.md` (산출물 작성 전 **반드시** 참조) |
| 클래스/컴포넌트/시퀀스 다이어그램 명명 (약어 사용 충동) | `docs/ubiquitous-language.md` §9 약어 금지 |
| 행위자 호칭 (사용자/관리자) | `docs/ubiquitous-language.md` §1 행위자 |

---

## 1. Hard Rules

### MUST

- **사용자에게는 항상 존댓말로 응대한다. 반말 절대 금지.** (~야 / ~지 / ~네 / ~잖아 같은 어미 사용 금지, 모든 응답은 ~습니다 / ~합니다 / ~입니다 어미)
- Kotlin + Java 21
- 패키지는 `com.loopers` 아래에 둔다
- `.editorconfig` 준수: IntelliJ Kotlin style, 130자 줄 제한, trailing commas, no wildcard imports
- 비즈니스/애플리케이션 실패는 §2의 `CoreException` 서브클래스 패턴으로만 던진다
- 도메인 에러 코드는 owning 도메인 모듈의 enum으로 정의하고 `com.loopers.support.error.ErrorCode`를 구현한다
- 코딩 전에 `gradle.properties`, `build.gradle.kts`로 버전을 확인하고, version-sensitive 동작은 공식 vendor docs로 검증한다
- 아키텍처 경계(`interfaces` / `application` / `domain` / `infrastructure`)를 보존한다 — 편의를 위해 도메인 규칙을 다른 레이어로 옮기지 않는다
- `./gradlew :apps:<app>:bootRun` 실행 시 **항상 `--args='--spring.profiles.active=<profile>'`을 명시한다**. 앱 `application.yaml`에 `spring.profiles.active` default를 두지 않음 — 프로파일 미지정 시 datasource 등이 비어 기동 실패. IDE는 Run Configuration의 프로파일 필드를 직접 설정한다.

### MUST NOT

- 시크릿 커밋 (`http/http-client.env.json`은 비민감하게 유지)
- ad hoc `RuntimeException`
- 앱 로컬 `CoreException` 재정의
- `ErrorType` 기반 모델 도입
- 새 `ErrorCode` 인터페이스 도입 / 공통 에러 코드 추상화 중복
- 단일 구현체를 위한 인터페이스
- 투기적 레이어/추상화/확장 포인트
- **설명성 코드 주석** — 코드 주석은 최소화(원칙적으로 0). 구현 결정의 "왜"는 코드 주석이 아니라 **PR 본문과 docs/weekN 설계 문서**에 기록한다 (사용자 확정 2026-07-07)
- **약어/축약형 클래스/컴포넌트명 사용** (`Ctrl`, `Ctl`, `Svc`, `Repo`, `Mgr`, `Cfg`, `Auth` 단독, `Resp`, `Req`, `Adv`, `Cmd`, `Ex` 단독). 코드/시나리오/시퀀스 다이어그램/PR 본문/주석 모두 풀네임만. 정정 매핑은 `docs/ubiquitous-language.md` §9 참조
- **Mermaid 시퀀스 다이어그램의 단일 문자 / 축약형 participant alias** (`participant F as XxxFilter`, `participant Ctl as XxxController`). 풀네임을 그대로 participant 명으로 사용
- **행위자 호칭으로 "대고객" / "어드민" 사용** (한국어 자연 표현이 아님). 시나리오/문서/PR 본문 모두 **사용자 / 로그인 사용자 / 관리자** 사용 (`docs/ubiquitous-language.md` §1 참조)

---

## 2. Error Handling Pattern

**흐름:**
`CoreException` 서브클래스 throw → `ApiControllerAdvice`가 subclass별 HTTP 상태 매핑 → `ApiResponse.fail`이 `exception.errorCode.code` + `exception.message` 직렬화.

**HTTP-semantic wrapper (사용 가능한 것):**
- `BadRequestException`
- `UnauthorizedException`
- `ForbiddenException`
- `NotFoundException`
- `ConflictException`
- `InternalServerException`

**핵심 파일 위치:**
- 예외 베이스: `supports/error/src/main/kotlin/com/loopers/support/error/CoreException.kt`
- 매핑 어드바이스: `supports/web/src/main/kotlin/com/loopers/interfaces/api/ApiControllerAdvice.kt`
- 도메인 에러 코드 레퍼런스: `apps/commerce-api/src/main/kotlin/com/loopers/account/domain/error/AccountErrorCode.kt`

**도메인 에러 코드 정의 패턴:**

```kotlin
enum class XxxErrorCode(override val message: String) : ErrorCode {
    SOME_FAILURE("사람이 읽는 메시지"),
    ;
    override val code: String = "XXX:$name"   // 도메인 prefix + enum 이름
}
```

**사용 패턴:**

```kotlin
throw ConflictException(AccountErrorCode.DUPLICATE_EMAIL)
```

**프레임워크가 자기 예외 타입을 요구할 때 (예: Spring Security):**
`CoreException`을 감싼다. 참고: `AccountAuthenticationException(coreException: CoreException)`.

**금지 예시:**
- ❌ `throw RuntimeException("이메일 중복")`
- ❌ 앱 모듈마다 `CoreException` 새로 정의
- ❌ `ErrorType` enum 도입
- ❌ 새로운 공통 `ErrorCode` 인터페이스 만들기

**DB unique race 처리 (JPA + Hibernate):**
- 회원가입처럼 사전 `existsBy` 체크가 race를 못 막는 경우, **service 레이어에서 `try-catch` + `saveAndFlush`를 하지 말고** `ApiControllerAdvice`의 `DataIntegrityViolationException` 핸들러에서 일괄 변환한다. service에 영속성 디테일(`flush`) 누수 회피 + unique 위반 외의 충돌(FK/NOT NULL/CHECK)도 한 곳에서 처리.
- JPA + Hibernate 환경에서는 Spring이 `DuplicateKeyException`까지 변환하지 않고 `DataIntegrityViolationException`에서 멈춘다 ([SPR-11669](https://github.com/spring-projects/spring-framework/issues/16292)). 따라서 `e.message`에 `"Duplicate"` 키워드가 있는지로 unique 위반(`ConflictException(CommonErrorCode.CONFLICT)`, 409)과 그 외(`InternalServerException`, 500)를 분기한다.
- 로깅: 운영 PII 노출 방지를 위해 advice에선 `e.cause?.javaClass?.simpleName`만 로깅 (`e.message` 직접 출력 금지). Hibernate 자체 `SqlExceptionHelper`는 `dev/qa/prd` 프로파일에서 OFF.

**인증 흐름 status 분포:**
- 로그인(`authenticate`)에서 loginId 형식 위반은 **400 BadRequest로 자연 전파** (`createPasswordIdentifier` 같은 401 wrapping 헬퍼 만들지 않는다). 의미 정합성 우선 — 입력 형식 검증은 인증 단계 이전이라 401 통일(OWASP의 ID enumeration 차단)보다 의미 정합성이 더 가치 있다고 결정 (관대한 형식 규칙 + 학습 컨텍스트).
- 존재 안 함 / 비밀번호 불일치 → 401 (`UnauthorizedException()` default = `CommonErrorCode.UNAUTHORIZED`)
- 회원가입에서 같은 형식 위반은 그대로 400 — 같은 검증 규칙이지만 컨텍스트별로 status가 갈리는 것이 의도된 설계

---

## 3. Design Principles

우선순위: **YAGNI → SOLID → Patterns**

- **YAGNI**: 지금 필요한 것만 빌드. 투기적 레이어/추상화 금지. 확장이 필요하다는 명확한 근거가 있으면 트레이드오프를 설명한 뒤 도입.
- **SOLID**: 모든 변경에서 고려. 단일 구현체용 인터페이스는 만들지 않음.
- **패턴**: 현재 코드를 단순화하거나 책임을 명확히 하거나 진짜 중복을 줄일 때만. 도입 전 benefit/cost 먼저 제시.
- **정당화**: 프로덕션 코드는 활성 테스트나 구체적 use case로 뒷받침되어야 한다.

---

## 4. Code Organization

**도메인 에러 코드 enum** → owning 도메인 모듈에 둔다 (application/interface 레이어 금지).

**한 owner의 cohesive 타입** → 같은 `.kt` 파일에 동거시킨다.
- 예시 1: `apps/commerce-api/src/main/kotlin/com/loopers/account/application/AccountService.kt`
  → `AccountService` 아래에 `AccountCreateCommand`, `AccountAuthenticateCommand`, account info DTO들이 함께 있음
- 예시 2: `apps/commerce-api/src/main/kotlin/com/loopers/account/infrastructure/security/AccountHeaderAuthenticationFilter.kt`
  → 필터 옆에 header/attribute 객체와 `AccountPrincipal`이 함께 있음

**별도 파일로 분리해야 할 때:**
- 여러 owner가 재사용
- 안정적 도메인/인프라 개념 (예: `AccountErrorCode.kt`, `domain/vo/*`, Redis configuration properties)

**도메인 VO (`@Embeddable`) 검증 패턴:**
- 컬럼 길이 상한은 `@Column.length`와 `init` 검증 양쪽에 **직접 숫자**로 둔다. `MAX_LENGTH` 같은 companion 상수는 도입하지 않는다 — 한 클래스에서만 쓰이는 값이고 `@Column` 바로 옆에 `init`이 위치하면 literal repetition이 매직 넘버보다 명확하다. 상수는 사용처가 진짜 여러 곳일 때만.
- `init`에서 길이 + 형식 모두 검증해 도메인 invariant를 fail-fast로 강제 — 컬럼 상한 초과가 DB 단계까지 가서 500으로 떨어지지 않도록.
- `toString()`은 원문 `value`를 그대로 반환. `"[PROTECTED]"` 등 마스킹을 도메인 객체에 박지 않는다 — 도메인 레이어에 로깅 정책을 누수시키지 않음. 운영 PII 노출은 인프라 레이어에서 처리 (예: `org.hibernate.engine.jdbc.spi.SqlExceptionHelper`를 `dev/qa/prd`에서 OFF, `supports/logging/logging.yml` 참조). 단 앱 코드에서 VO를 직접 문자열 보간(`"$identifier"`)이나 `logger.debug("{}", value)`로 로깅하지 말 것 — toString이 호출되어 마스킹 우회됨.
- JPA naming: Spring Boot 기본 `SpringPhysicalNamingStrategy` 가정 (`AccountCredential` → `account_credential`). `@Table.name` 미명시도 같은 결과. Hibernate 단독 default(변환 없음)와 다르다.

**도메인 throw 시 PII 노출 회피:**
- `BadRequestException(AccountErrorCode.XXX)`처럼 `errorCode`만 사용. `customMessage`에 사용자 입력값(이메일/loginId 등)을 끼워넣지 않는다 — `ApiControllerAdvice`가 `e.message`를 로깅하면 PII가 로그에 남는다.

---

## 5. Project Structure

```
apps/
  commerce-api/         # active — 모든 도메인 코드 (bounded context 우선 패키지)
  commerce-streamer/    # active
  commerce-batch/       # active
modules/                # 공유 인프라 (jpa·redis·kafka·persistence-core) — 베이스 템플릿, 수정 금지
supports/               # add-ons (error, web 등) — 베이스 템플릿, 수정 금지
http/                   # HTTP 예시 (비민감 env.json)
docker/                 # local infra
```

commerce-api 내부는 `com.loopers.<context>.{domain,application,infrastructure,interfaces}`
(context = account·brand·coupon·inventory·like·order·product, 공유 VO 는 `shared.domain`, 앱 전역 설정은 `config`).

Gradle 표준 경로: `src/main/kotlin`, `src/main/resources`, `src/test/kotlin`, test fixtures.

---

## 6. Testing

**스택:**
- JUnit 5
- Spring profile: `test`
- Timezone: `Asia/Seoul`
- Spring Boot Test, MockK/Mockito, Instancio, Testcontainers

**네이밍:**
| 종류 | 파일명 |
|---|---|
| Unit | `*Test.kt` |
| Integration | `*IntegrationTest.kt` |
| E2E | `*E2ETest.kt` |

**focused 실행:**
```bash
./gradlew :apps:commerce-api:test --tests '*ExampleServiceIntegrationTest'
```

**통합 테스트 격리:**
- `@SpringBootTest` 통합 테스트는 `DatabaseCleanup` 유틸 + `@BeforeEach`로 cleanup 호출 (예: `apps/commerce-api/src/test/kotlin/com/loopers/support/DatabaseCleanup.kt`). `@Transactional`을 테스트 클래스에 일괄 부착하지 않는다 — propagation 경계 충돌(`REQUIRES_NEW`/`@Async`/`@TransactionalEventListener` 롤백 누락), JPA dirty checking flush 타이밍 가림, lazy loading 가림, race 검증 봉쇄, MockMvc 트랜잭션 경계 불확실성 등 함정이 누적된다.
- `@BeforeEach`로 호출한다 (`@AfterEach` 단독은 crash 시 다음 테스트가 오염된 DB에서 시작). `DatabaseCleanup`은 `@Component @Profile("test")`로 운영 노출 차단, JPA 메타모델 기반 자동 테이블 추출, MySQL syntax(`SET FOREIGN_KEY_CHECKS=0`, `TRUNCATE`, `ALTER TABLE … AUTO_INCREMENT = 1`)로 H2 `MODE=MySQL` + Testcontainers MySQL 모두 호환.
- 다른 앱에서 같은 패턴이 필요해지면 `supports`의 testFixtures로 promote (현재는 commerce-api 한정 — YAGNI).

> 컨트롤러/리포지토리별 구체 전략은 §12 Account Notes 참고.

---

## 7. Development Workflow

**TDD** → `$kent-beck-tdd` 스킬 사용:
1. JUnit 테스트 리스트 초안 작성
2. 사용자 confirmation
3. 실패하는 테스트 1개 작성
4. 통과시키는 최소 코드 구현
5. green 후 refactor

버전 가정이 구현 선택에 영향을 주면 PR 또는 짧은 주석에 기록.

---

## 8. Command Reference

| 명령 | 용도 |
|---|---|
| `make init` | hooks 설치 (pre-commit이 `./gradlew ktlintCheck` 실행) |
| `./gradlew build` | 모듈 컴파일 + 테스트 |
| `./gradlew test` | 테스트만 |
| `./gradlew ktlintCheck` | Kotlin 포맷 체크 |
| `./gradlew ktlintFormat` | Kotlin 포맷 적용 |
| `./gradlew :apps:<app>:bootRun --args='--spring.profiles.active=local'` | API 기동 (`--args` 누락 시 프로파일 미설정으로 기동 실패. §1 MUST 참고) |
| `docker-compose -f ./docker/infra-compose.yml up` | 의존성 기동 |

---

## 9. Git / PR

스킬: `$loopers-pr-workflow`

- 작업 베이스: 사용자 fork (`origin`)
- 최종 리뷰 PR target: `loopers-labs/loop-pack-be-l2-vol4-kotlin`, base `shoeone96`
- 커밋: Conventional Commit 스타일 prefix (예: `chore: PR 템플릿 통일 및 개선`), 짧고 scoped
- PR: `.github/pull_request_template.md` 따르기, 관련 시 테스트 결과 포함

---

## 10. Versions

코딩 전 체크리스트:
1. `gradle.properties`, `build.gradle.kts`에서 Java/Kotlin/Spring/Gradle 플러그인/라이브러리 버전 확인
2. version-sensitive 동작은 공식 vendor docs 우선
3. 자료 우선순위: **공식 docs > 메이저 테크 회사 엔지니어링 레퍼런스 > 개인 블로그**

---

## 11. Configuration & Security Files

런타임 config, 로컬 인프라, HTTP client 예시는 비즈니스 로직 변경과 분리한다.
태스크가 명시적으로 요구하지 않는 한 함께 수정하지 않는다.

---

## 12. Account 작업 노트 (Project-Specific)

### Account API Implementation Direction

- 현재 요구사항부터 바깥쪽으로 구현. 컨트롤러/API 계약을 먼저 만들고, 필요해진 다음 의존성만 추가한다.
- 외부 라우트는 `/api/v1/users` 컨벤션을 따른다 (평가 스펙). 내부 도메인 명칭은 `account` 유지 — `user`는 MySQL `mysql.user` 시스템 테이블 / Spring Security `User` 클래스와 충돌하므로 내부 코드에 도입하지 않는다.
- 결과적으로 `AccountController`가 `/api/v1/users`를 매핑하고, 내부 모듈/패키지/클래스/DB 테이블은 `account_*` 명칭을 유지한다. 응답 JSON 필드는 도메인 필드명(`loginId`, `email`, ...) 그대로라 외부에 `account`라는 단어는 노출되지 않는다.
- 요청 DTO는 단일 컨트롤러에서만 쓰이면 컨트롤러 파일에 함께 둔다. 재사용/크기 때문에 필요할 때만 별도 파일로 분리.
- 테스트/요구사항이 필요로 하기 전에 `Facade`, security, 추가 어댑터 레이어를 만들지 않는다.
- 실제 모듈 경계를 넘을 때만 `Command`를 사용한다. `AccountCreateCommand`가 API → application 입력 모델로 합의됨.
- 레이어가 필요해지면 focused test + 명확한 책임 경계와 함께 도입한다.

### Controller Test Strategy

| 도구 | 사용 시점 |
|---|---|
| `@WebMvcTest` | 컨트롤러 매핑/JSON 바인딩/요청·응답 shape만 필요할 때 (기본 선택) |
| `@SpringBootTest` + `@AutoConfigureMockMvc` | 전체 Spring context + `ControllerAdvice` + `ResponseBodyAdvice` + security가 실제 포트 없이 참여해야 할 때 |
| `@SpringBootTest(webEnvironment = RANDOM_PORT)` | 임베디드 서버로 진짜 HTTP/E2E 검증이 필요할 때만 |

현재 Account API thin slice에서는 진짜 HTTP 동작이 필요하지 않으면 `RANDOM_PORT`를 피한다.

### Repository Test Strategy

- 서비스 레이어 테스트는 도메인 리포지토리 port와 `PasswordEncryptor`를 mock 처리한다.
- 리포지토리 동작 검증은 기본적으로 `@DataJpaTest` + 임베디드 DB.
- MySQL-specific 동작을 검증해야 하는 경우가 아니면 MySQL/Testcontainers 설정을 import하지 않는다.
- `commerce-api`는 `src/test/resources/application-test.yaml`의 H2 임베디드 testdatasource를 사용한다.
- account 영속성 동작은 `com.loopers.account.infrastructure`의 Spring Data JPA 리포지토리와 어댑터 와이어링으로 테스트한다 (`AccountDataRepositoryTest`).
- `modules:jpa` `JpaConfig` 같은 베이스 템플릿 파일은 수정하지 않는다. 도메인-우선 패키지의 repo 스캔은 앱 소유 `com.loopers.config.CommerceJpaRepositoriesConfig`가 담당한다.

### Package Boundaries (bounded context 우선)

이 프로젝트는 **layered architecture**를 채택하되, 도메인 코드는 전부 `commerce-api` 단일 앱 안에 **bounded context-우선 패키지**로 둔다. 진짜 공통 인프라(`supports/*`, `modules/{jpa,redis,kafka,persistence-core}`)만 모듈로 유지하고, 베이스 템플릿 모듈의 파일은 수정하지 않는다. hexagonal의 port-adapter 패턴은 도입하지 않으며, application이 infrastructure의 repository 인터페이스에 직접 의존하는 게 의도된 구조다. 나중에 서버 분리가 필요해지면 컨텍스트 패키지를 그대로 모듈로 추출한다.

```
com.loopers.<context>.{domain, application, infrastructure, interfaces}
  · context = account · brand · coupon · inventory · like · order · product
  · shared.domain = Money·Cursor 등 공유 VO / config = 앱 전역 설정(security FilterChain, JPA repo 스캔)
```

| 패키지 (account 예시) | 소유 |
|---|---|
| `account.domain` | entities, VO, validators, `PasswordEncryptor` 인터페이스 |
| `account.application` | use case, command, 트랜잭션 경계. `account.infrastructure`의 repository 인터페이스에 직접 의존 |
| `account.infrastructure` | repository 인터페이스 + Spring Data JPA 구현체 (+`security/` 인증 어댑터) |
| `account.interfaces` | REST 컨트롤러 + 요청/응답 DTO |
| `config.security` | 앱 전역 SecurityFilterChain (swagger·actuator·admin 경로 횡단 정책) |
| `supports:error` | error code / exception (Spring MVC 상태 매핑 없음) |
| `supports:web` | 예외 → HTTP 응답 매핑, 성공 응답 래핑 |

### API Response Wrapping

- 컨트롤러에서 `ApiResponse`를 직접 반환하지 않는다.
- 컨트롤러는 일반 응답 body 또는 no body를 반환 → `ResponseBodyAdvice`가 성공 응답을 래핑.
- `ApiResponse`는 공통 web 인프라(예외 처리, 응답 advice, filter/security failure writer)에서만 사용.
- payload가 필요하면 도메인 전용 response DTO를 반환하고 advice가 래핑하게 한다.
- 아직 payload가 없으면 구조 충족 목적의 placeholder response DTO를 만들지 말고 body 없는 메서드로 둔다.
- 공유 Jackson `NON_NULL` 정책 준수: nullable 응답 필드(예: `data`)는 null일 때 생략 가능.

### TDD Flow Reminder

1. failing test 먼저 작성
2. 통과시키는 최소 코드 추가
3. green 후에만 refactor
4. 테스트 리스트는 MECE + 경계 중심으로 유지. 지금 필요하지 않은 동작에 대한 투기적 테스트는 추가하지 않는다.
