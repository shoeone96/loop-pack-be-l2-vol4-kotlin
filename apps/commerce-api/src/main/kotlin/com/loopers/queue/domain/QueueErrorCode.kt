package com.loopers.queue.domain

import com.loopers.support.error.ErrorCode

enum class QueueErrorCode(override val message: String) : ErrorCode {
    ENTRY_TOKEN_REQUIRED("대기열을 통과해야 주문할 수 있습니다. 대기열에 먼저 진입해 주세요."),
    ALREADY_ADMITTED("이미 입장 상태입니다. 발급된 입장 토큰이 유효한 동안에는 다시 줄을 설 수 없습니다."),
    ;

    override val code: String = "QUEUE:$name"
}
