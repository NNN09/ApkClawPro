package com.apk.claw.android.agent

/**
 * 任务被拒绝/中止且从未真正执行（Agent 忙弹回、executor 未初始化或已关闭、
 * 配置更新丢弃了已排队任务）。
 *
 * 类型化目的：TaskOrchestrator 收到该类错误时不写任务历史、不计失败熔断——
 * 真机实证（2026-09-09）：手动结束任务后 Agent 线程仍卡在途 LLM 调用（最长
 * read-timeout 300s），窗口期内重发的任务每次被弹回都记一次 FAILED，连弹 5 次
 * 即把 C3 熔断顶开，用户重试本身制造了熔断。
 */
class TaskRejectedException(message: String) : IllegalStateException(message)
