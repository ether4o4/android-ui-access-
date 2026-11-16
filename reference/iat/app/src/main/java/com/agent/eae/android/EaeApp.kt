package com.agent.eae.android

import android.app.Application
import com.agent.eae.android.core.orchestrator.AgentEngine

class EaeApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AgentEngine.init(this)
    }
}
