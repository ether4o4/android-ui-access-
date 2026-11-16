package com.cellclaw.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cellclaw.agent.AgentEvent
import com.cellclaw.agent.AgentLoop
import com.cellclaw.agent.AgentState
import com.cellclaw.agent.HeartbeatResult
import com.cellclaw.approval.ApprovalQueue
import com.cellclaw.approval.ApprovalRequest
import com.cellclaw.approval.ApprovalResult
import com.cellclaw.config.AppConfig
import com.cellclaw.config.SecureKeyStore
import com.cellclaw.provider.AnthropicProvider
import com.cellclaw.provider.ProviderManager
import com.cellclaw.tools.ToolRegistry
import com.cellclaw.ui.screens.ChatMessage
import com.cellclaw.voice.VoiceManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ChatViewModel @Inject constructor(
    private val agentLoop: AgentLoop,
    private val approvalQueue: ApprovalQueue,
    private val appConfig: AppConfig,
    private val secureKeyStore: SecureKeyStore,
    private val anthropicProvider: AnthropicProvider,
    private val providerManager: ProviderManager,
    private val toolRegistry: ToolRegistry,
    private val voiceManager: VoiceManager
) : ViewModel() {

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _agentState = MutableStateFlow("idle")
    val agentState: StateFlow<String> = _agentState.asStateFlow()

    private val _thinkingText = MutableStateFlow<String?>(null)
    val thinkingText: StateFlow<String?> = _thinkingText.asStateFlow()

    val pendingApprovals: StateFlow<List<ApprovalRequest>> = approvalQueue.requests

    private var messageCounter = 0L

    val isListening: StateFlow<Boolean> = voiceManager.isListening
    val voiceEnabled: Boolean get() = appConfig.voiceEnabled

    init {
        configureProvider()
        observeAgentEvents()
        observeAgentState()
        observeVoiceInput()
        if (appConfig.voiceEnabled) {
            voiceManager.initialize()
        }
    }

    private fun configureProvider() {
        val apiKey = secureKeyStore.getApiKey("anthropic") ?: return
        anthropicProvider.configure(apiKey, appConfig.model)
    }

    private fun observeAgentEvents() {
        viewModelScope.launch {
            agentLoop.events.collect { event ->
                when (event) {
                    is AgentEvent.UserMessage -> addMessage("user", event.text)
                    is AgentEvent.AssistantTextDelta -> {
                        // Live token stream from local models — shown in the
                        // thinking indicator until the turn's text is committed.
                        _thinkingText.value = (_thinkingText.value ?: "") + event.text
                    }
                    is AgentEvent.AssistantText -> {
                        _thinkingText.value = null
                        addMessage("assistant", event.text)
                    }
                    is AgentEvent.ToolCallStart -> addMessage(
                        "tool", "Calling ${event.name}...",
                        toolName = event.name
                    )
                    is AgentEvent.ToolCallResult -> addMessage(
                        "tool",
                        if (event.result.success) "Done" else "Error: ${event.result.error}",
                        toolName = event.name
                    )
                    is AgentEvent.ToolCallDenied -> addMessage(
                        "tool", "Denied by user",
                        toolName = event.name
                    )
                    is AgentEvent.ThinkingText -> _thinkingText.value = event.text
                    is AgentEvent.Error -> addMessage("error", event.message)
                    is AgentEvent.HeartbeatStart -> {} // Silent — don't show in chat
                    is AgentEvent.HeartbeatComplete -> {
                        val note = event.detection.statusNote
                        if (note != null && event.detection.heartbeatResult != HeartbeatResult.OK_NOTHING_TO_DO) {
                            addMessage("assistant", note)
                        }
                    }
                }
            }
        }
    }

    private fun observeAgentState() {
        viewModelScope.launch {
            agentLoop.state.collect { state ->
                _agentState.value = when (state) {
                    AgentState.IDLE -> "idle"
                    AgentState.THINKING -> "thinking"
                    AgentState.EXECUTING_TOOLS -> "executing_tools"
                    AgentState.WAITING_APPROVAL -> "waiting_approval"
                    AgentState.PAUSED -> "paused"
                    AgentState.ERROR -> "error"
                }
                if (state == AgentState.IDLE || state == AgentState.ERROR) {
                    _thinkingText.value = null
                }
            }
        }
    }

    fun sendMessage(text: String) {
        val trimmed = text.trim()
        if (trimmed.startsWith("/")) {
            if (handleSlashCommand(trimmed)) return
        }
        agentLoop.submitMessage(text)
    }

    /** Returns true if the input was a recognized local command (not sent to the model). */
    private fun handleSlashCommand(input: String): Boolean {
        val command = input.removePrefix("/").substringBefore(' ').lowercase()
        return when (command) {
            "status" -> {
                addMessage("user", input)
                addMessage("assistant", buildStatusReport())
                true
            }
            "help" -> {
                addMessage("user", input)
                addMessage("assistant",
                    "Local commands (handled on-device, not sent to the model):\n" +
                        "• /status — provider, model, endpoint, heartbeat, tools\n" +
                        "• /help — this message")
                true
            }
            else -> false
        }
    }

    private fun buildStatusReport(): String {
        val type = providerManager.activeType()
        val info = providerManager.availableProviders().find { it.type == type }
        val displayName = info?.displayName ?: type
        val isLocal = type == "local"

        val toolCount = if (isLocal && appConfig.leanToolsForLocal) {
            "${toolRegistry.all().count { it.name in AgentLoop.LEAN_TOOLS }} of ${toolRegistry.all().size} (lean set)"
        } else {
            "${toolRegistry.all().size}"
        }

        return buildString {
            appendLine("CellClaw status")
            appendLine("• Provider: $displayName")
            appendLine("• Model: ${appConfig.model}")
            if (isLocal) {
                appendLine("• Endpoint: ${appConfig.localEndpoint}")
                appendLine("• Lean tools: ${if (appConfig.leanToolsForLocal) "on" else "off"}")
            }
            appendLine("• Tools offered: $toolCount")
            appendLine("• Autonomy: ${appConfig.permissionProfile}")
            appendLine("• Heartbeat: ${if (appConfig.heartbeatEnabled) "enabled" else "disabled"}")
            append("• Agent state: ${_agentState.value}")
        }
    }

    fun stopAgent() {
        agentLoop.stop()
        _thinkingText.value = null
    }

    fun respondToApproval(requestId: String, result: ApprovalResult) {
        approvalQueue.respond(requestId, result)
    }

    private fun observeVoiceInput() {
        viewModelScope.launch {
            voiceManager.recognizedText.collect { text ->
                if (text.isNotBlank()) {
                    sendMessage(text)
                }
            }
        }
    }

    fun startVoiceInput() {
        if (!appConfig.voiceEnabled) return
        voiceManager.startListening()
    }

    fun stopVoiceInput() {
        voiceManager.stopListening()
    }

    private fun addMessage(role: String, content: String, toolName: String? = null) {
        messageCounter++
        _messages.value = _messages.value + ChatMessage(
            id = messageCounter,
            role = role,
            content = content,
            toolName = toolName
        )
        // Auto-speak assistant responses if enabled
        if (role == "assistant" && appConfig.autoSpeakResponses) {
            voiceManager.speak(content)
        }
    }

    override fun onCleared() {
        super.onCleared()
        voiceManager.destroy()
    }
}
