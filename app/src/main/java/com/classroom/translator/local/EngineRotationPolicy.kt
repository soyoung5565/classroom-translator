package com.classroom.translator.local

/**
 * 引擎轮换策略 —— **纯逻辑，无 Android 依赖**。
 *
 * 【2026-11-03】用户需求原文：
 *   「我感觉重启引擎可能比较好，定时重启，如果可以双引擎并发就更好了，
 *     比如五分钟重启一次，四分半的时候开始启动 b 引擎，启动完毕后接管 a 引擎，五分钟循环。」
 *
 * 抽成独立类的原因：真实一轮要 5 分钟，单元测试没法等。
 * 把时序判定抽成纯函数 → JVM 上可以跑完整的多轮循环，把边界条件全测掉。
 * ⚠️ 铁律：**这份逻辑只能有一份**（本类）——
 *    曾经把同样的判定分别写在测试和实现里，结果两边对不上，测试反而没测到真东西。
 */
object EngineRotationPolicy {

    /** 轮换周期：5 分钟 */
    const val ROTATE_MS = 5 * 60 * 1000L

    /** 提前多久启动备用引擎：4 分 30 秒（= ROTATE_MS - 30 秒） */
    const val PREPARE_LEAD_MS = 30 * 1000L

    /**
     * 一个引擎槽位 = ASR + 翻译两个 llama-server 进程。
     * A/B 两个槽位交替当「对外服务的那一个」。
     */
    open class Slot(val name: String, val asrPort: Int, val transPort: Int) {
        @Volatile var asrProc: Process? = null
        @Volatile var transProc: Process? = null
        @Volatile var asrStartedAt: Long = 0L
        @Volatile var transStartedAt: Long = 0L
    }

    /** 轮换状态机 */
    enum class Action {
        /** 该启动备用引擎了（4 分 30 秒） */
        START_STANDBY,

        /** 备用引擎已就绪且到点 → 接管 */
        CUT_OVER,

        /** 备用引擎迟迟不就绪 → 放弃本轮，继续用当前引擎 */
        GIVE_UP,

        /** 继续等待 */
        WAIT
    }

    /**
     * 轮换计时器。
     *
     * ⚠️ 关键：计时是**相对上一次动作**的（now + 周期），不是对齐到整点。
     *    对齐整点会导致「上一轮超时放弃后，下一轮的准备时刻已经过去了」→
     *    立刻又准备，永远轮不过来（这是实测中发现的真 bug）。
     */
    class Timers(
        @Volatile var prepareAt: Long = 0L,
        @Volatile var rotateAt: Long = 0L
    ) {
        /** 开启一轮：准备时刻 = now + 周期 - 提前量；接管时刻 = now + 周期 */
        fun beginRound(now: Long) {
            prepareAt = now + ROTATE_MS - PREPARE_LEAD_MS
            rotateAt = now + ROTATE_MS
        }

        /** 本轮放弃后重置（从现在起算新的一轮） */
        fun reset(now: Long) = beginRound(now)
    }

    /**
     * 决策：给定当前状态，该做什么。
     *
     * @param nowMs           当前时间
     * @param timers          轮换计时器
     * @param standbyPreparing 备用引擎是否已在启动中
     * @param standbyProcAlive  备用引擎的进程是否已经拉起
     * @param standbyHealthy   备用引擎是否 /health 就绪
     * @param graceMs         备用引擎的加载宽限期
     */
    fun decide(
        nowMs: Long,
        timers: Timers,
        standbyPreparing: Boolean,
        standbyProcAlive: Boolean,
        standbyHealthy: Boolean,
        graceMs: Long
    ): Action {
        // 1) 到点启动备用引擎（4 分 30 秒），且本轮还没启动过
        if (!standbyPreparing && !standbyProcAlive && nowMs >= timers.prepareAt) {
            return Action.START_STANDBY
        }
        // 2) 备用引擎就绪 且 已到接管时刻 → 接管
        if (standbyPreparing && standbyHealthy && nowMs >= timers.rotateAt) {
            return Action.CUT_OVER
        }
        // 3) 备用引擎过了宽限期仍不就绪 → 放弃本轮
        //    （绝不为了「守时」把用户正在用的服务搞挂）
        if (standbyPreparing && !standbyHealthy && nowMs >= timers.rotateAt + graceMs) {
            return Action.GIVE_UP
        }
        return Action.WAIT
    }
}
