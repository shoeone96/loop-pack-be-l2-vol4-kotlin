package com.loopers.queue.application

import com.loopers.queue.domain.QueueErrorCode
import com.loopers.queue.infrastructure.redis.OrderQueueRepository
import com.loopers.support.error.ConflictException
import com.loopers.support.runConcurrently
import com.loopers.utils.RedisCleanUp
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles

@SpringBootTest
@ActiveProfiles("test")
class OrderQueueServiceIntegrationTest @Autowired constructor(
    private val orderQueueService: OrderQueueService,
    private val orderQueueRepository: OrderQueueRepository,
    private val redisCleanUp: RedisCleanUp,
) {
    @BeforeEach
    fun setUp() {
        redisCleanUp.truncateAll()
    }

    @DisplayName("동시에 진입한 사용자 전원이 누락·중복 없이 고유한 순번을 받는다.")
    @Test
    fun assignsDistinctPositions_whenEnteringConcurrently() {
        val failures = runConcurrently(threadCount = 20) { index ->
            orderQueueService.enter(userId = index + 1L)
        }

        val positions = (1L..20L).map { orderQueueService.position(it).position }
        assertAll(
            { assertThat(failures).isEmpty() },
            { assertThat(positions).doesNotContainNull() },
            { assertThat(positions).containsExactlyInAnyOrderElementsOf((1L..20L).toList()) },
            { assertThat(orderQueueRepository.totalWaiting()).isEqualTo(20L) },
        )
    }

    @DisplayName("대기 중 순번 조회는 순번·전체 대기 인원·예상 대기 시간·다음 폴링 주기를 함께 준다.")
    @Test
    fun givesWaitingDetails_whileWaiting() {
        (1L..3L).forEach { orderQueueService.enter(userId = it) }

        val info = orderQueueService.position(3L)

        assertAll(
            { assertThat(info.status).isEqualTo(QueueEntryStatus.WAITING) },
            { assertThat(info.position).isEqualTo(3L) },
            { assertThat(info.totalWaiting).isEqualTo(3L) },
            { assertThat(info.estimatedWaitSeconds!!).isGreaterThan(0L) },
            { assertThat(info.nextPollSeconds!!).isBetween(1L, 10L) },
            { assertThat(info.token).isNull() },
        )
    }

    @DisplayName("차례가 오면 순번 조회 응답에 입장 토큰이 실리고, 주문 검증을 통과한다.")
    @Test
    fun deliversToken_whenAdmitted() {
        orderQueueService.enter(userId = 1L)
        orderQueueRepository.admitNextBatch(batchSize = 1, tokenTtlSeconds = 300, tokens = listOf("tk-1"))

        val info = orderQueueService.position(1L)

        assertAll(
            { assertThat(info.status).isEqualTo(QueueEntryStatus.ADMITTED) },
            { assertThat(info.token).isEqualTo("tk-1") },
            { assertThatCode { orderQueueService.verifyAdmission(1L) }.doesNotThrowAnyException() },
        )
    }

    @DisplayName("대기열을 거치지 않은 주문 시도는 CONFLICT(ENTRY_TOKEN_REQUIRED)로 거부된다.")
    @Test
    fun rejectsOrder_withoutAdmission() {
        val result = assertThrows<ConflictException> { orderQueueService.verifyAdmission(1L) }

        assertThat(result.errorCode).isEqualTo(QueueErrorCode.ENTRY_TOKEN_REQUIRED)
    }

    @DisplayName("입장 토큰 보유 중에는 다시 줄을 설 수 없다. (대기 파이프라이닝 차단)")
    @Test
    fun rejectsReEntry_whileHoldingToken() {
        orderQueueService.enter(userId = 1L)
        orderQueueRepository.admitNextBatch(batchSize = 1, tokenTtlSeconds = 300, tokens = listOf("tk-1"))

        val result = assertThrows<ConflictException> { orderQueueService.enter(userId = 1L) }

        assertThat(result.errorCode).isEqualTo(QueueErrorCode.ALREADY_ADMITTED)
    }

    @DisplayName("주문 완료가 토큰을 소모해 같은 토큰의 재사용을 차단한다.")
    @Test
    fun consumesToken_onOrderCompletion() {
        orderQueueService.enter(userId = 1L)
        orderQueueRepository.admitNextBatch(batchSize = 1, tokenTtlSeconds = 300, tokens = listOf("tk-1"))
        orderQueueService.verifyAdmission(1L)

        orderQueueService.completeOrder(1L)

        val result = assertThrows<ConflictException> { orderQueueService.verifyAdmission(1L) }
        assertThat(result.errorCode).isEqualTo(QueueErrorCode.ENTRY_TOKEN_REQUIRED)
    }

    @DisplayName("토큰이 만료된 사용자는 우선권 없이 다시 줄의 맨 뒤로 들어간다.")
    @Test
    fun reEntersAtBack_afterTokenExpiry() {
        orderQueueService.enter(userId = 1L)
        orderQueueRepository.admitNextBatch(batchSize = 1, tokenTtlSeconds = 1, tokens = listOf("tk-1"))
        Thread.sleep(1_100)
        orderQueueService.enter(userId = 2L)
        orderQueueService.enter(userId = 3L)

        val info = orderQueueService.enter(userId = 1L)

        assertAll(
            { assertThat(info.status).isEqualTo(QueueEntryStatus.WAITING) },
            { assertThat(info.position).isEqualTo(3L) },
        )
    }

    @DisplayName("스케줄러 한 주기는 배치 크기까지만 입장시킨다. (처리량 초과분은 다음 주기로)")
    @Test
    fun capsAdmissionsPerTick_atBatchSize() {
        (1L..10L).forEach { orderQueueService.enter(userId = it) }
        val scheduler = OrderQueueAdmissionScheduler(
            orderQueueRepository,
            OrderQueueProperties(batchSize = 3, fixedDelayMillis = 100, tokenTtlSeconds = 300),
        )

        scheduler.admit()

        assertAll(
            { assertThat(orderQueueRepository.totalWaiting()).isEqualTo(7L) },
            { assertThat((1L..10L).mapNotNull { orderQueueRepository.findToken(it) }).hasSize(3) },
        )
    }
}
