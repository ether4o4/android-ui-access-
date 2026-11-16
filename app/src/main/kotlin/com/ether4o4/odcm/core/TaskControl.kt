package com.ether4o4.odcm.core

/** The runner depends on observations and actions, not the Android service lifecycle. */
interface TaskControl {
    fun readScreen(): ScreenSnapshot
    suspend fun perform(action: AgentAction): Boolean
}
