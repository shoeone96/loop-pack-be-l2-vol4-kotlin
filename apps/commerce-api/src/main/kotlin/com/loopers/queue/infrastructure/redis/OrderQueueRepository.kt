package com.loopers.queue.infrastructure.redis

import com.loopers.config.redis.RedisConfig
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.data.redis.core.RedisTemplate
import org.springframework.data.redis.core.script.DefaultRedisScript
import org.springframework.stereotype.Component

@Component
class OrderQueueRepository(
    @Qualifier(RedisConfig.REDIS_TEMPLATE_MASTER) private val redisTemplate: RedisTemplate<String, String>,
) {
    fun enter(userId: Long, enteredAtMillis: Long): Boolean =
        redisTemplate.opsForZSet().addIfAbsent(QUEUE_KEY, userId.toString(), enteredAtMillis.toDouble()) ?: false

    fun rank(userId: Long): Long? = redisTemplate.opsForZSet().rank(QUEUE_KEY, userId.toString())

    fun totalWaiting(): Long = redisTemplate.opsForZSet().size(QUEUE_KEY) ?: 0

    fun findToken(userId: Long): String? = redisTemplate.opsForValue().get(tokenKey(userId))

    fun deleteToken(userId: Long) {
        redisTemplate.delete(tokenKey(userId))
    }

    fun admitNextBatch(batchSize: Int, tokenTtlSeconds: Long, tokens: List<String>): List<Long> {
        require(tokens.size == batchSize) { "토큰 개수(${tokens.size})와 배치 크기($batchSize)가 일치해야 합니다." }
        val admitted = redisTemplate.execute(
            ADMIT_SCRIPT,
            listOf(QUEUE_KEY),
            batchSize.toString(),
            tokenTtlSeconds.toString(),
            TOKEN_KEY_PREFIX,
            *tokens.toTypedArray(),
        )
        return admitted.orEmpty().map { it.toString().toLong() }
    }

    private fun tokenKey(userId: Long): String = "$TOKEN_KEY_PREFIX$userId"

    companion object {
        const val QUEUE_KEY = "queue:orders"
        const val TOKEN_KEY_PREFIX = "entry-token:"

        // ZPOPMIN(꺼냄)과 SET(토큰 발급) 사이에 장애가 끼면 대기열에도 토큰에도 없는 유저가 생기므로
        // 두 동작을 한 스크립트로 묶는다. 토큰 키를 스크립트 안에서 조립하므로 비클러스터 전제.
        private val ADMIT_SCRIPT: DefaultRedisScript<List<*>> = DefaultRedisScript(
            """
            local popped = redis.call('ZPOPMIN', KEYS[1], tonumber(ARGV[1]))
            local admitted = {}
            for i = 1, #popped, 2 do
                redis.call('SET', ARGV[3] .. popped[i], ARGV[3 + (i + 1) / 2], 'EX', tonumber(ARGV[2]))
                admitted[#admitted + 1] = popped[i]
            end
            return admitted
            """.trimIndent(),
            List::class.java,
        )
    }
}
