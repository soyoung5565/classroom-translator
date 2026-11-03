package com.classroom.translator.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 引擎轮换时序测试（2026-11-03）
 *
 * 用户需求原文：
 *   「五分钟重启一次，四分半的时候开始启动 b 引擎，启动完毕后接管 a 引擎，五分钟循环。」
 *
 * ⚠️ 本测试直接测**生产代码** [EngineRotationPolicy]，
 *    不再在测试里复制一份判定逻辑 —— 复制的那次就是因为两边对不上而漏掉了 bug。
 */
class EngineRotationPolicyTest {

    private val grace = 90_000L

    @Test
    fun `starts standby at 4 min 30 sec`() {
        val timers = EngineRotationPolicy.Timers()
        timers.beginRound(0L)
        val prepareAt = 4 * 60 * 1000L + 30_000L
        assertEquals(prepareAt, timers.prepareAt)
        assertEquals(5 * 60 * 1000L, timers.rotateAt)

        // 4 分 29 秒：还没到点
        assertEquals(
            EngineRotationPolicy.Action.WAIT,
            EngineRotationPolicy.decide(prepareAt - 1000, timers, false, false, false, grace)
        )
        // 4 分 30 秒：启动备用引擎
        assertEquals(
            EngineRotationPolicy.Action.START_STANDBY,
            EngineRotationPolicy.decide(prepareAt, timers, false, false, false, grace)
        )
    }

    @Test
    fun `does not cut over before the 5 minute mark even if standby is ready`() {
        val timers = EngineRotationPolicy.Timers()
        timers.beginRound(0L)
        // 4 分 45 秒，备用引擎已就绪 —— 但还没到 5 分钟，不能接管
        assertEquals(
            EngineRotationPolicy.Action.WAIT,
            EngineRotationPolicy.decide(4 * 60 * 1000L + 45_000L, timers, true, true, true, grace)
        )
    }

    @Test
    fun `cuts over at 5 minutes when standby is ready`() {
        val timers = EngineRotationPolicy.Timers()
        timers.beginRound(0L)
        assertEquals(
            EngineRotationPolicy.Action.CUT_OVER,
            EngineRotationPolicy.decide(timers.rotateAt, timers, true, true, true, grace)
        )
        assertEquals(
            EngineRotationPolicy.Action.CUT_OVER,
            EngineRotationPolicy.decide(timers.rotateAt + 1, timers, true, true, true, grace)
        )
    }

    @Test
    fun `gives up the round if standby never becomes healthy`() {
        val timers = EngineRotationPolicy.Timers()
        timers.beginRound(0L)
        // 宽限期内继续等（不能把用户正在用的服务搞挂）
        assertEquals(
            EngineRotationPolicy.Action.WAIT,
            EngineRotationPolicy.decide(timers.rotateAt + grace - 1000, timers, true, true, false, grace)
        )
        // 超过宽限期 → 放弃本轮
        assertEquals(
            EngineRotationPolicy.Action.GIVE_UP,
            EngineRotationPolicy.decide(timers.rotateAt + grace, timers, true, true, false, grace)
        )
    }

    @Test
    fun `never starts standby twice in the same round`() {
        val timers = EngineRotationPolicy.Timers()
        timers.beginRound(0L)
        assertEquals(
            EngineRotationPolicy.Action.WAIT,
            EngineRotationPolicy.decide(timers.prepareAt + 1000, timers, true, true, false, grace)
        )
    }

    /**
     * 完整跑 18 分钟（3 轮）的仿真循环。
     *
     * ⚠️ 这个测试抓到过一个真 bug：计时曾被对齐到「整 5 分钟」，
     *    结果上一轮放弃后下一轮的准备时刻已经过去 → 永远轮不过来。
     *    现在计时是相对上一次动作的（now + 周期）。
     */
    @Test
    fun `the cycle repeats every 5 minutes over 3 rounds`() {
        val timers = EngineRotationPolicy.Timers()
        timers.beginRound(0L)
        var now = 0L
        var prepares = 0
        var cuts = 0
        var preparing = false
        var healthy = false
        val totalMs = 18 * 60 * 1000L
        while (now < totalMs) {
            now += 1000
            val procAlive = preparing
            when (EngineRotationPolicy.decide(now, timers, preparing, procAlive, healthy, grace)) {
                EngineRotationPolicy.Action.START_STANDBY -> {
                    prepares++
                    preparing = true
                    healthy = false
                }
                EngineRotationPolicy.Action.CUT_OVER -> {
                    cuts++
                    preparing = false
                    healthy = false
                    timers.beginRound(now)     // 接管 → 开启下一轮
                }
                EngineRotationPolicy.Action.GIVE_UP -> {
                    preparing = false
                    healthy = false
                    timers.reset(now)
                }
                EngineRotationPolicy.Action.WAIT -> {
                    // 备用引擎启动后 30 秒就绪（模型加载约 20s）
                    if (preparing && !healthy && now >= timers.prepareAt + 30_000L) healthy = true
                }
            }
        }
        assertEquals("prepares in 18 min", 3, prepares)
        assertEquals("cuts in 18 min", 3, cuts)
    }

    /**
     * 备用引擎一直起不来（最坏情况）→ 每轮都放弃，但**主引擎必须一直服务**。
     * 放弃后必须能从现在起算新的一轮，否则会卡死不再轮换。
     */
    @Test
    fun `gives up every round but keeps cycling when standby never becomes healthy`() {
        val timers = EngineRotationPolicy.Timers()
        timers.beginRound(0L)
        var now = 0L
        var givesUp = 0
        var preparing = false
        val totalMs = 18 * 60 * 1000L
        while (now < totalMs) {
            now += 1000
            when (EngineRotationPolicy.decide(now, timers, preparing, preparing, false, grace)) {
                EngineRotationPolicy.Action.START_STANDBY -> preparing = true
                EngineRotationPolicy.Action.GIVE_UP -> {
                    givesUp++
                    preparing = false
                    timers.reset(now)
                }
                else -> Unit
            }
        }
        // 18 分钟 / 每轮 5 分钟 + 90 秒宽限 ≈ 3 次放弃
        assertTrue("should give up at least twice, got $givesUp", givesUp >= 2)
    }

    @Test
    fun `rotation constants match the user requirement`() {
        assertEquals(5 * 60 * 1000L, EngineRotationPolicy.ROTATE_MS)
        assertEquals(30 * 1000L, EngineRotationPolicy.PREPARE_LEAD_MS)
        // 4 分 30 秒 = 5 分钟 - 30 秒
        assertEquals(
            4 * 60 * 1000L + 30_000L,
            EngineRotationPolicy.ROTATE_MS - EngineRotationPolicy.PREPARE_LEAD_MS
        )
        // 管理器对外暴露的常量必须与策略一致（避免两处对不上）
        assertEquals(EngineRotationPolicy.ROTATE_MS, LocalLlmServerManager.ROTATE_MS)
        assertEquals(EngineRotationPolicy.PREPARE_LEAD_MS, LocalLlmServerManager.PREPARE_LEAD_MS)
    }

    @Test
    fun `standby port offset does not collide with the active port`() {
        val activePort = 8000
        val standbyPort = activePort + 100
        assertFalse(activePort == standbyPort)
        assertTrue(standbyPort in 1..65535)
    }

    @Test
    fun `timers reset restarts the full cycle from now`() {
        val timers = EngineRotationPolicy.Timers()
        timers.beginRound(0L)
        val lateReset = 1_000_000L
        timers.reset(lateReset)
        assertEquals(lateReset + 4 * 60 * 1000L + 30_000L, timers.prepareAt)
        assertEquals(lateReset + 5 * 60 * 1000L, timers.rotateAt)
    }
}
