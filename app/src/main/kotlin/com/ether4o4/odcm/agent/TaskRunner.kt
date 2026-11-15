package com.ether4o4.odcm.agent

import com.ether4o4.odcm.control.ControlService
import com.ether4o4.odcm.core.AgentAction
import com.ether4o4.odcm.core.AutonomyLevel
import com.ether4o4.odcm.core.Policy
import com.ether4o4.odcm.core.ScreenFormatter
import com.ether4o4.odcm.core.StepLimits
import com.ether4o4.odcm.core.Verdict
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay

/**
 * The agent loop: observe the screen, ask the model, gate the action, act, repeat.
 * Pure orchestration; all Android touchpoints come in through the interfaces below so it stays testable.
 */
class TaskRunner(
    private val control: ControlService,
    private val launcher: AppLauncher,
    private val model: ModelClient,
    private val policy: Policy,
    private val callbacks: Callbacks,
    private val limits: StepLimits = StepLimits(),
) {
    interface Callbacks {
        fun status(text: String)
        fun reply(text: String)
        /** Suspend until the user answers an approval on the overlay or over Telegram. */
        suspend fun requestApproval(reason: String): Boolean
        fun settleDelayMs(): Long = 400
    }

    @Volatile private var stopped = false
    @Volatile private var paused = false
    private var resume: CompletableDeferred<Unit>? = null

    fun stop() { stopped = true; resume?.complete(Unit) }
    fun pause() { paused = true }
    fun resume() { paused = false; resume?.complete(Unit); resume = null }

    suspend fun run(task: String) {
        model.addUserText("Task: $task")
        while (!stopped) {
            limits.exceeded()?.let { callbacks.status("Stopped: $it"); return }
            awaitResumeIfPaused()
            if (stopped) break

            callbacks.status("Reading the screen…")
            val screen = control.readScreen()
            model.addUserText("Current screen:\n" + ScreenFormatter.format(screen))

            callbacks.status("Thinking…")
            val decision = runCatching { model.next() }.getOrElse {
                callbacks.status("Model error: ${it.message}")
                return
            }
            limits.onStep()

            val parsed = AgentAction.parse(decision.toolName ?: "", decision.toolArgs)
            if (parsed.isFailure) {
                fail(decision.toolUseId, "Error: ${parsed.exceptionOrNull()?.message}")
                continue
            }
            val action = parsed.getOrThrow()

            when (action) {
                is AgentAction.Reply -> {
                    callbacks.reply(action.text)
                    decision.toolUseId?.let { model.addToolResult(it, "Sent.", isError = false) }
                    continue
                }
                is AgentAction.Done -> { callbacks.status("Done: ${action.summary}"); return }
                is AgentAction.Wait -> { delay(action.millis); ok(decision.toolUseId, "Waited."); continue }
                else -> {}
            }

            val launchTarget = (action as? AgentAction.OpenApp)?.let { launcher.find(it.name) }
            val verdict = policy.check(action, screen, launchTarget?.packageName)
            when (verdict) {
                is Verdict.Block -> { fail(decision.toolUseId, "Blocked: ${verdict.reason}"); continue }
                is Verdict.Ask -> {
                    callbacks.status("Waiting for approval…")
                    if (!callbacks.requestApproval(verdict.reason)) {
                        fail(decision.toolUseId, "User declined: ${verdict.reason}")
                        continue
                    }
                }
                Verdict.Allow -> {}
            }

            callbacks.status(Policy.describe(action, screen.elements.getOrNull((action as? AgentAction.Tap)?.index ?: -1)))
            val success = if (action is AgentAction.OpenApp) {
                launchTarget?.let { launcher.launch(it.packageName) } ?: false
            } else {
                control.perform(action)
            }
            limits.onResult(success)
            ok(decision.toolUseId, if (success) "Action performed." else "Action failed; look at the screen and try another way.", !success)
            delay(callbacks.settleDelayMs())
        }
    }

    private fun ok(id: String?, msg: String, isError: Boolean = false) {
        id?.let { model.addToolResult(it, msg, isError) }
    }

    private fun fail(id: String?, msg: String) = ok(id, msg, isError = true)

    private suspend fun awaitResumeIfPaused() {
        if (paused && !stopped) {
            callbacks.status("Paused")
            val d = CompletableDeferred<Unit>()
            resume = d
            d.await()
        }
    }

    companion object {
        fun policyFor(level: AutonomyLevel, blocked: List<String>) = Policy(level, blocked)
    }
}
