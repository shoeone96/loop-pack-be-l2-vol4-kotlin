package com.loopers.queue.application

import com.loopers.queue.domain.QueueErrorCode
import com.loopers.queue.infrastructure.redis.OrderQueueRepository
import com.loopers.support.error.ConflictException
import kotlin.math.ceil
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

@Component
class OrderQueueService(
    private val orderQueueRepository: OrderQueueRepository,
    private val properties: OrderQueueProperties,
) {
    fun enter(userId: Long): QueuePositionInfo {
        if (orderQueueRepository.findToken(userId) != null) {
            throw ConflictException(QueueErrorCode.ALREADY_ADMITTED)
        }
        orderQueueRepository.enter(userId, System.currentTimeMillis())
        return position(userId)
    }

    fun position(userId: Long): QueuePositionInfo {
        val token = orderQueueRepository.findToken(userId)
        if (token != null) {
            return QueuePositionInfo.admitted(token)
        }
        val rank = orderQueueRepository.rank(userId) ?: return QueuePositionInfo.notInQueue()
        val estimatedWaitSeconds = ceil((rank + 1) / properties.admissionsPerSecond).toLong()
        return QueuePositionInfo(
            status = QueueEntryStatus.WAITING,
            position = rank + 1,
            totalWaiting = orderQueueRepository.totalWaiting(),
            estimatedWaitSeconds = estimatedWaitSeconds,
            nextPollSeconds = nextPollSeconds(estimatedWaitSeconds),
            token = null,
        )
    }

    fun verifyAdmission(userId: Long) {
        orderQueueRepository.findToken(userId) ?: throw ConflictException(QueueErrorCode.ENTRY_TOKEN_REQUIRED)
    }

    fun completeOrder(userId: Long) {
        orderQueueRepository.deleteToken(userId)
    }

    // 차례가 가까울수록 촘촘하게 폴링하도록 서버가 다음 조회 시점을 지정한다 (대기 인원 전체의 폴링 총량 제어)
    private fun nextPollSeconds(estimatedWaitSeconds: Long): Long = (estimatedWaitSeconds / 10).coerceIn(1, 10)
}

@Component
class OrderQueueProperties(
    @Value("\${queue.scheduler.batch-size:10}") val batchSize: Int,
    @Value("\${queue.scheduler.fixed-delay:100}") val fixedDelayMillis: Long,
    @Value("\${queue.token.ttl-seconds:300}") val tokenTtlSeconds: Long,
) {
    val admissionsPerSecond: Double get() = batchSize * 1000.0 / fixedDelayMillis
}

enum class QueueEntryStatus { WAITING, ADMITTED, NOT_IN_QUEUE }

data class QueuePositionInfo(
    val status: QueueEntryStatus,
    val position: Long?,
    val totalWaiting: Long?,
    val estimatedWaitSeconds: Long?,
    val nextPollSeconds: Long?,
    val token: String?,
) {
    companion object {
        fun admitted(token: String): QueuePositionInfo =
            QueuePositionInfo(QueueEntryStatus.ADMITTED, null, null, null, null, token)

        fun notInQueue(): QueuePositionInfo =
            QueuePositionInfo(QueueEntryStatus.NOT_IN_QUEUE, null, null, null, null, null)
    }
}
