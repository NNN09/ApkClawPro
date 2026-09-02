package com.apk.claw.android.agent

interface AgentService {
    fun initialize(config: AgentConfig)
    fun updateConfig(config: AgentConfig)
    fun executeTask(request: TaskRequest, callback: AgentCallback)
    fun cancel()
    fun shutdown()
    fun isRunning(): Boolean
}
