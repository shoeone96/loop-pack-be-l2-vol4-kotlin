package com.loopers.queue.interfaces

import com.loopers.account.infrastructure.security.AccountAuthenticationAttributes.ACCOUNT_ID
import com.loopers.queue.application.OrderQueueService
import com.loopers.queue.application.QueueEntryStatus
import com.loopers.queue.application.QueuePositionInfo
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestAttribute
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/queue")
class OrderQueueController(
    private val orderQueueService: OrderQueueService,
) {
    @PostMapping("/enter")
    fun enter(@RequestAttribute(ACCOUNT_ID) userId: Long): QueuePositionResponse =
        QueuePositionResponse.from(orderQueueService.enter(userId))

    @GetMapping("/position")
    fun position(@RequestAttribute(ACCOUNT_ID) userId: Long): QueuePositionResponse =
        QueuePositionResponse.from(orderQueueService.position(userId))
}

data class QueuePositionResponse(
    val status: QueueEntryStatus,
    val position: Long?,
    val totalWaiting: Long?,
    val estimatedWaitSeconds: Long?,
    val nextPollSeconds: Long?,
    val token: String?,
) {
    companion object {
        fun from(info: QueuePositionInfo): QueuePositionResponse = QueuePositionResponse(
            status = info.status,
            position = info.position,
            totalWaiting = info.totalWaiting,
            estimatedWaitSeconds = info.estimatedWaitSeconds,
            nextPollSeconds = info.nextPollSeconds,
            token = info.token,
        )
    }
}
