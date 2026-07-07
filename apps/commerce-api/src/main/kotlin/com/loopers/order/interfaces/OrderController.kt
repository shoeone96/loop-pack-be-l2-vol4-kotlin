package com.loopers.order.interfaces

import com.loopers.account.infrastructure.security.AccountAuthenticationAttributes.ACCOUNT_ID
import com.loopers.order.application.OrderCreateCommand
import com.loopers.order.application.OrderFacade
import com.loopers.order.application.OrderInfo
import com.loopers.order.application.OrderLineCommand
import com.loopers.order.domain.OrderStatus
import com.loopers.queue.application.OrderQueueService
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestAttribute
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/orders")
class OrderController(
    private val orderFacade: OrderFacade,
    private val orderQueueService: OrderQueueService,
) {
    @PostMapping
    fun order(
        @RequestAttribute(ACCOUNT_ID) userId: Long,
        @RequestBody request: OrderCreateRequest,
    ): OrderCreateResponse {
        orderQueueService.verifyAdmission(userId)
        val info = orderFacade.place(request.toCommand(userId))
        orderQueueService.completeOrder(userId)
        return OrderCreateResponse.from(info)
    }
}

data class OrderCreateRequest(
    val items: List<OrderLineRequest>,
    val couponId: Long?,
    val expectedOriginalAmount: Long,
    val expectedDiscountAmount: Long,
) {
    fun toCommand(userId: Long): OrderCreateCommand = OrderCreateCommand(
        userId = userId,
        items = items.map { OrderLineCommand(productId = it.productId, quantity = it.quantity, price = it.price) },
        couponId = couponId,
        expectedOriginalAmount = expectedOriginalAmount,
        expectedDiscountAmount = expectedDiscountAmount,
    )
}

data class OrderLineRequest(
    val productId: Long,
    val quantity: Int,
    val price: Long,
)

data class OrderCreateResponse(
    val orderKey: String,
    val status: OrderStatus,
    val originalAmount: Long,
    val discountAmount: Long,
    val totalAmount: Long,
) {
    companion object {
        fun from(info: OrderInfo): OrderCreateResponse = OrderCreateResponse(
            orderKey = info.orderKey,
            status = info.status,
            originalAmount = info.originalAmount,
            discountAmount = info.discountAmount,
            totalAmount = info.totalAmount,
        )
    }
}
