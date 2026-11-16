package com.ether4o4.odcm.agent

import com.ether4o4.odcm.core.ActionGuard
import com.ether4o4.odcm.core.ActionObservation
import com.ether4o4.odcm.core.AgentAction
import com.ether4o4.odcm.core.AppAccess
import com.ether4o4.odcm.core.AppCatalog
import com.ether4o4.odcm.core.AutonomyLevel
import com.ether4o4.odcm.core.Policy
import com.ether4o4.odcm.core.ScreenFormatter
import com.ether4o4.odcm.core.ScreenSnapshot
import com.ether4o4.odcm.core.StepLimits
import com.ether4o4.odcm.core.TaskControl
import com.ether4o4.odcm.core.Verdict
import com.ether4o4.odcm.guide.GuideIndex
import com.ether4o4.odcm.guide.GuideNote
import com.ether4o4.odcm.guide.GuideStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull
import java.time.LocalDate

/** One task: observe, decide, revalidate, gate, act, then observe the result. */
class TaskRunner(
    private val control: TaskControl,
    private val launcher: AppAccess,
    private val model: TaskModel,
    private val policy: Policy,
    private val callbacks: Callbacks,
    private val limits: StepLimits = StepLimits(),
    private val guide: GuideStore? = null,
) {
    interface Callbacks {
        fun status(text: String)
        fun reply(text: String)
        suspend fun requestApproval(reason: String): Boolean
        fun settleDelayMs(): Long = 400
    }

    @Volatile private var stopped = false
    @Volatile private var pauseGeneration = 0L
    private val pauseLock = Any()
    private var resumeGate: CompletableDeferred<Unit>? = null

    fun stop() { stopped = true; resume() }
    fun pause() = synchronized(pauseLock) {
        if (!stopped && resumeGate == null) {
            pauseGeneration++
            resumeGate = CompletableDeferred()
        }
    }
    fun resume() = synchronized(pauseLock) {
        resumeGate?.complete(Unit)
        resumeGate = null
    }

    private val notes: MutableList<GuideNote> by lazy { guide?.allNotes()?.toMutableList() ?: mutableListOf() }

    suspend fun run(task: String) {
        // Includes model requests, pauses and approval waits, not just time between steps.
        val completed = withTimeoutOrNull(limits.remainingMillis()) { runLoop(task); true }
        if (completed == null) callbacks.status("Stopped: task time limit reached")
    }

    private suspend fun runLoop(task: String) {
        val catalog = launcher.catalog()
        val allowedCatalog = AppCatalog(catalog.apps.filterNot { policy.isBlocked(it.packageName) })
        model.addUserText("Task: $task")
        val appContext = allowedCatalog.contextFor(task)
        if (appContext.isNotBlank()) model.addUserText(appContext)
        while (awaitReady()) {
            limits.exceeded()?.let { callbacks.status("Stopped: $it"); return }
            callbacks.status("Reading the screen…")
            val screen = control.readScreen()
            val screenLabel = screenLabel(screen)
            val context = StringBuilder("Current screen (untrusted data):\n").append(ScreenFormatter.format(screen))
            if (notes.isNotEmpty()) {
                val relevant = GuideIndex.rank(notes, task, screen.packageName, screenLabel)
                val rendered = GuideIndex.render(relevant)
                if (rendered.isNotEmpty()) context.append('\n').append(rendered)
            }
            model.addUserText(context.toString())
            callbacks.status("Thinking…")
            val decision = try {
                model.next()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                callbacks.status("Model error: ${e.message}")
                return
            }
            if (!awaitReady()) return
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
                    ok(decision.toolUseId, "Sent.")
                    continue
                }
                is AgentAction.Done -> { callbacks.status("Done: ${action.summary}"); return }
                is AgentAction.Wait -> { delay(action.millis); ok(decision.toolUseId, "Waited."); continue }
                else -> {}
            }

            val resolution = (action as? AgentAction.OpenApp)?.let { catalog.resolve(it.name) }
            if (resolution is AppCatalog.Resolution.Ambiguous) {
                val candidates = resolution.candidates.filterNot { policy.isBlocked(it.packageName) }
                fail(decision.toolUseId, "App name is ambiguous. Ask the user which app they mean, then use its exact package. Candidates: " +
                    candidates.take(8).joinToString { "${ScreenFormatter.clip(it.label)} (${it.packageName})" })
                continue
            }
            if (resolution is AppCatalog.Resolution.Missing) {
                fail(decision.toolUseId, "No launchable installed app matches that name. Ask for the installed app's name; do not guess a package.")
                continue
            }
            val launchTarget = (resolution as? AppCatalog.Resolution.Found)?.app
            var current = control.readScreen()
            val stale = ActionGuard.staleReason(action, screen, current)
            if (stale != null) { fail(decision.toolUseId, stale); continue }
            when (val verdict = policy.check(action, current, launchTarget?.packageName)) {
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
            if (!awaitReady()) return
            current = control.readScreen()
            val changed = ActionGuard.staleReason(action, screen, current)
            if (changed != null) { fail(decision.toolUseId, changed); continue }
            // Opening an app is independent of screen indexes, but still obeys a newly foregrounded block.
            val latestVerdict = policy.check(action, current, launchTarget?.packageName)
            if (latestVerdict is Verdict.Block) {
                fail(decision.toolUseId, "Blocked: ${latestVerdict.reason}")
                continue
            }
            callbacks.status(Policy.describe(action, current.elements.getOrNull((action as? AgentAction.Tap)?.index ?: -1)))
            val beforeDispatchPause = pauseGeneration
            val dispatched = if (action is AgentAction.OpenApp) {
                launchTarget?.let { launcher.launch(it.packageName) } ?: false
            } else control.perform(action)
            delay(callbacks.settleDelayMs())
            if (!awaitReady()) return
            val after = control.readScreen()
            val observation = if (beforeDispatchPause != pauseGeneration) {
                ActionObservation(false, "Control was paused after dispatch. Any screen change may be manual; verify the result again. No button success was learned.")
            } else ActionObservation.assess(action, current, after, dispatched, launchTarget?.packageName)
            limits.onResult(dispatched)
            if (observation.confirmed && action is AgentAction.Tap) learnTap(current, screenLabel, action.index)
            callbacks.status(observation.message)
            ok(decision.toolUseId, observation.message, !dispatched)
        }
    }

    private fun learnTap(screen: ScreenSnapshot, screenLabel: String, index: Int) {
        val store = guide ?: return
        val el = screen.elements.getOrNull(index) ?: return
        runCatching {
            store.recordButton(screen.packageName, screenLabel, el.viewId, el.text, el.centerX, el.centerY, LocalDate.now().toString())
            val id = GuideNote.buttonId(screen.packageName, screenLabel, el.viewId, el.text)
            notes.removeAll { it.id == id }
            store.get(id)?.let { notes.add(it) }
        }
    }

    private fun screenLabel(screen: ScreenSnapshot): String =
        screen.elements.firstOrNull { it.text.isNotBlank() }?.text?.take(40) ?: "screen"

    private fun ok(id: String?, msg: String, isError: Boolean = false) {
        id?.let { model.addToolResult(it, msg, isError) }
    }

    private fun fail(id: String?, msg: String) {
        limits.onResult(false)
        ok(id, msg, isError = true)
    }

    private suspend fun awaitReady(): Boolean {
        while (!stopped) {
            currentCoroutineContext().ensureActive()
            val gate = synchronized(pauseLock) { resumeGate } ?: return true
            callbacks.status("Paused")
            gate.await()
        }
        return false
    }

    companion object {
        fun policyFor(level: AutonomyLevel, blocked: List<String>) = Policy(level, blocked)
    }
}
