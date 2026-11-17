package com.ether4o4.odcm.agent

/** A task's model conversation. Suspending decisions must respect task cancellation. */
interface TaskModel {
    fun addUserText(text: String)
    fun addToolResult(toolUseId: String, result: String, isError: Boolean)
    fun setCurrentScreen(text: String) = addUserText(text)
    fun readHistory(offset: Int): String = "No earlier task history."
    suspend fun next(): ModelDecision
}
