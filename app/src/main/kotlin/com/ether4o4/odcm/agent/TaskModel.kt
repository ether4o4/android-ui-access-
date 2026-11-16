package com.ether4o4.odcm.agent

/** A task's model conversation. Suspending decisions must respect task cancellation. */
interface TaskModel {
    fun addUserText(text: String)
    fun addToolResult(toolUseId: String, result: String, isError: Boolean)
    suspend fun next(): ModelDecision
}
