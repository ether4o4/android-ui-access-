package com.ether4o4.odcm.agent

import com.ether4o4.odcm.core.*
import com.ether4o4.odcm.guide.GuideStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TaskRunnerTest {
    @get:Rule val temp = TemporaryFolder()

    private fun screen(text: String = "Continue", checked: Boolean? = null, pkg: String = "com.example") =
        ScreenSnapshot(pkg, 100, 200, listOf(
            ScreenElement(0, "button", text, "com.example:id/control", 0, 0, 80, 40,
                true, false, false, checked, false),
        ), false)

    private class Device(var screen: ScreenSnapshot) : TaskControl {
        val actions = mutableListOf<AgentAction>()
        var afterAction: (() -> Unit)? = null
        override fun readScreen() = screen
        override suspend fun perform(action: AgentAction): Boolean {
            actions += action
            afterAction?.invoke()
            return true
        }
    }

    private class Apps(private vararg val entries: AppCatalog.App) : AppAccess {
        val launches = mutableListOf<String>()
        override fun catalog() = AppCatalog(entries.toList())
        override fun launch(packageName: String): Boolean { launches += packageName; return true }
    }

    private class Model(vararg decisions: Pair<String, Map<String, Any?>>) : TaskModel {
        val pending = ArrayDeque(decisions.toList())
        val inputs = mutableListOf<String>()
        val results = mutableListOf<String>()
        var beforeDecision: suspend () -> Unit = {}
        override fun addUserText(text: String) { inputs += text }
        override fun addToolResult(toolUseId: String, result: String, isError: Boolean) { results += result }
        override suspend fun next(): ModelDecision {
            beforeDecision()
            val decision = if (pending.isEmpty()) "done" to mapOf("summary" to "Finished") else pending.removeFirst()
            return ModelDecision("tool", decision.first, decision.second, "", "tool_use")
        }
    }

    private class Callbacks : TaskRunner.Callbacks {
        val statuses = mutableListOf<String>()
        var approve: suspend () -> Boolean = { true }
        override fun status(text: String) { statuses += text }
        override fun reply(text: String) {}
        override suspend fun requestApproval(reason: String) = approve()
        override fun settleDelayMs() = 0L
    }

    private fun runner(device: Device, model: Model, callbacks: Callbacks = Callbacks(),
        apps: Apps = Apps(), policy: Policy = Policy(AutonomyLevel.FULL, emptyList()),
        guide: GuideStore? = null, limits: StepLimits = StepLimits()) =
        TaskRunner(device, apps, model, policy, callbacks, limits, guide)

    @Test fun userMessageDuringDecisionPreventsTheOldAction() = runBlocking {
        val device = Device(screen())
        val model = Model("tap" to mapOf("index" to 0))
        val task = runner(device, model)
        var first = true
        model.beforeDecision = { if (first) { first = false; assertTrue(task.addMessage("Do not tap; answer my question instead")) } }
        task.run("Continue")
        assertTrue(device.actions.isEmpty())
        assertTrue(model.inputs.any { "User follow-up: Do not tap" in it })
        assertTrue(model.results.any { "Not executed" in it })
    }

    @Test fun userMessageDuringApprovalInvalidatesTheOldAction() = runBlocking {
        val device = Device(screen("Send"))
        val model = Model("tap" to mapOf("index" to 0))
        val callbacks = Callbacks()
        val task = runner(device, model, callbacks, policy = Policy(AutonomyLevel.BALANCED, emptyList()))
        callbacks.approve = { task.addMessage("Cancel that send"); true }
        task.run("Send")
        assertTrue(device.actions.isEmpty())
        assertTrue(model.results.any { "new user guidance" in it })
    }

    @Test fun switchedAppDuringModelCallNeverReceivesTheOldTap() = runBlocking {
        val device = Device(screen())
        val model = Model("tap" to mapOf("index" to 0))
        model.beforeDecision = { device.screen = screen(pkg = "com.other") }
        runner(device, model).run("Continue")
        assertTrue(device.actions.isEmpty())
        assertTrue(model.results.first().contains("screen changed"))
    }

    @Test fun changedScreenDuringApprovalInvalidatesApproval() = runBlocking {
        val device = Device(screen("Send"))
        val model = Model("tap" to mapOf("index" to 0))
        val callbacks = Callbacks().apply { approve = { device.screen = screen("Delete"); true } }
        runner(device, model, callbacks, policy = Policy(AutonomyLevel.BALANCED, emptyList())).run("Send")
        assertTrue(device.actions.isEmpty())
        assertTrue(model.results.first().contains("screen changed"))
    }

    @Test fun pauseDuringModelResponsePreventsDispatchUntilResume() = runBlocking {
        val device = Device(screen())
        val model = Model("tap" to mapOf("index" to 0))
        val response = CompletableDeferred<Unit>()
        model.beforeDecision = { response.await() }
        val task = runner(device, model)
        val job = launch(start = CoroutineStart.UNDISPATCHED) { task.run("Continue") }
        task.pause()
        response.complete(Unit)
        yield()
        assertTrue(device.actions.isEmpty())
        task.resume()
        job.join()
        assertEquals(1, device.actions.size)
    }

    @Test fun stopDuringModelResponsePreventsLateAction() = runBlocking {
        val device = Device(screen())
        val model = Model("tap" to mapOf("index" to 0))
        val response = CompletableDeferred<Unit>()
        model.beforeDecision = { response.await() }
        val task = runner(device, model)
        val job = launch(start = CoroutineStart.UNDISPATCHED) { task.run("Continue") }
        task.stop()
        response.complete(Unit)
        job.join()
        assertTrue(device.actions.isEmpty())
    }

    @Test fun acceptedTapWithoutObservedEffectDoesNotTeach() = runBlocking {
        val device = Device(screen())
        val model = Model("tap" to mapOf("index" to 0))
        val store = GuideStore(temp.root)
        runner(device, model, guide = store).run("Continue")
        assertTrue(store.allNotes().isEmpty())
        assertTrue(model.results.first().contains("unverified"))
    }

    @Test fun manualToggleWhilePausedIsNeverLearnedAsAgentSuccess() = runBlocking {
        val device = Device(screen("Wi-Fi", checked = false))
        val model = Model("tap" to mapOf("index" to 0))
        val store = GuideStore(temp.root)
        val task = runner(device, model, guide = store)
        val dispatched = CompletableDeferred<Unit>()
        device.afterAction = { task.pause(); dispatched.complete(Unit) }
        val job = launch(start = CoroutineStart.UNDISPATCHED) { task.run("Turn on Wi-Fi") }
        dispatched.await()
        device.screen = screen("Wi-Fi", checked = true)
        task.resume()
        job.join()
        assertTrue(store.allNotes().isEmpty())
        assertTrue(model.results.first().contains("may be manual"))
    }

    @Test fun observedToggleTeachesButNavigationAloneDoesNot() = runBlocking {
        val device = Device(screen("Wi-Fi", checked = false))
        device.afterAction = { device.screen = screen("Wi-Fi", checked = true) }
        val store = GuideStore(temp.root)
        runner(device, Model("tap" to mapOf("index" to 0)), guide = store).run("Turn on Wi-Fi")
        assertEquals(1, store.allNotes().single().successes)
        device.screen = screen("Next")
        device.afterAction = { device.screen = screen("Destination") }
        runner(device, Model("tap" to mapOf("index" to 0)), guide = store).run("Continue")
        assertEquals(1, store.allNotes().size)
    }

    @Test fun ambiguousAppNeverLaunchesAndBlockedAppsAreNotInContext() = runBlocking {
        val apps = Apps(AppCatalog.App("com.one", "Mail"), AppCatalog.App("com.two", "Mail"),
            AppCatalog.App("com.blocked", "Private Mail"))
        val model = Model("open_app" to mapOf("name" to "Mail"))
        runner(Device(screen()), model, apps = apps,
            policy = Policy(AutonomyLevel.FULL, listOf("com.blocked"))).run("Open Mail")
        assertTrue(apps.launches.isEmpty())
        assertTrue(model.results.first().contains("ambiguous"))
        assertFalse(model.inputs.joinToString().contains("com.blocked"))
    }

    @Test fun exactPackageUsesExistingPolicyBeforeLaunching() = runBlocking {
        val apps = Apps(AppCatalog.App("com.blocked", "Bank"))
        val model = Model("open_app" to mapOf("name" to "com.blocked"))
        runner(Device(screen()), model, apps = apps,
            policy = Policy(AutonomyLevel.FULL, listOf("com.blocked"))).run("Open Bank")
        assertTrue(apps.launches.isEmpty())
        assertTrue(model.results.first().contains("Blocked"))
    }

    @Test fun approvalWaitIsBoundedByTaskDeadline() = runBlocking {
        val device = Device(screen("Send"))
        val callbacks = Callbacks().apply { approve = { CompletableDeferred<Boolean>().await() } }
        runner(device, Model("tap" to mapOf("index" to 0)), callbacks,
            policy = Policy(AutonomyLevel.BALANCED, emptyList()),
            limits = StepLimits(maxMillis = 100)).run("Send")
        assertTrue(device.actions.isEmpty())
        assertTrue(callbacks.statuses.last().contains("time limit"))
    }

    @Test fun repeatedInvalidActionsHitFailureLimit() = runBlocking {
        val model = Model(*Array(5) { "tap" to mapOf<String, Any?>("index" to 99) })
        val device = Device(screen())
        val callbacks = Callbacks()
        runner(device, model, callbacks).run("Continue")
        assertEquals(3, model.results.size)
        assertTrue(callbacks.statuses.last().contains("3 actions"))
        assertTrue(device.actions.isEmpty())
    }
}
