package com.loopers.queue.infrastructure.redis

import com.loopers.utils.RedisCleanUp
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles

@SpringBootTest
@ActiveProfiles("test")
class OrderQueueRepositoryIntegrationTest @Autowired constructor(
    private val orderQueueRepository: OrderQueueRepository,
    private val redisCleanUp: RedisCleanUp,
) {
    @BeforeEach
    fun setUp() {
        redisCleanUp.truncateAll()
    }

    @DisplayName("재진입해도 최초 진입 시각이 유지되어 순번이 밀리지 않는다. (ZADD NX)")
    @Test
    fun keepsOriginalScore_whenReEntering() {
        orderQueueRepository.enter(userId = 1L, enteredAtMillis = 1_000)
        orderQueueRepository.enter(userId = 2L, enteredAtMillis = 2_000)

        val reEntered = orderQueueRepository.enter(userId = 1L, enteredAtMillis = 9_999)

        assertAll(
            { assertThat(reEntered).isFalse() },
            { assertThat(orderQueueRepository.rank(1L)).isEqualTo(0L) },
            { assertThat(orderQueueRepository.totalWaiting()).isEqualTo(2L) },
        )
    }

    @DisplayName("배치 입장은 진입 순서대로 꺼내 토큰을 발급하고, 꺼낸 명단을 반환한다.")
    @Test
    fun admitsInEntryOrder_andIssuesTokens() {
        (1L..5L).forEach { orderQueueRepository.enter(userId = it, enteredAtMillis = it) }

        val admitted = orderQueueRepository.admitNextBatch(
            batchSize = 3,
            tokenTtlSeconds = 300,
            tokens = listOf("tk-1", "tk-2", "tk-3"),
        )

        assertAll(
            { assertThat(admitted).containsExactly(1L, 2L, 3L) },
            { assertThat(orderQueueRepository.findToken(1L)).isEqualTo("tk-1") },
            { assertThat(orderQueueRepository.findToken(3L)).isEqualTo("tk-3") },
            { assertThat(orderQueueRepository.findToken(4L)).isNull() },
            { assertThat(orderQueueRepository.rank(4L)).isEqualTo(0L) },
            { assertThat(orderQueueRepository.totalWaiting()).isEqualTo(2L) },
        )
    }

    @DisplayName("대기 인원이 배치 크기보다 적으면 있는 만큼만 입장시킨다.")
    @Test
    fun admitsOnlyExisting_whenQueueSmallerThanBatch() {
        orderQueueRepository.enter(userId = 1L, enteredAtMillis = 1)
        orderQueueRepository.enter(userId = 2L, enteredAtMillis = 2)

        val admitted = orderQueueRepository.admitNextBatch(
            batchSize = 5,
            tokenTtlSeconds = 300,
            tokens = listOf("tk-1", "tk-2", "tk-3", "tk-4", "tk-5"),
        )

        assertAll(
            { assertThat(admitted).containsExactly(1L, 2L) },
            { assertThat(orderQueueRepository.totalWaiting()).isEqualTo(0L) },
        )
    }

    @DisplayName("입장 토큰은 TTL이 지나면 무효화된다.")
    @Test
    fun expiresToken_afterTtl() {
        orderQueueRepository.enter(userId = 1L, enteredAtMillis = 1)
        orderQueueRepository.admitNextBatch(batchSize = 1, tokenTtlSeconds = 1, tokens = listOf("tk-1"))
        assertThat(orderQueueRepository.findToken(1L)).isEqualTo("tk-1")

        Thread.sleep(1_100)

        assertThat(orderQueueRepository.findToken(1L)).isNull()
    }
}
