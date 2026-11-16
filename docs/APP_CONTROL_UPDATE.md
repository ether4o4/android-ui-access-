# ODCM app context and task control

This update stays within ODCM's existing on-device agent: identify the installed
app, inspect its screen, carry out the user's task, and keep the Guide useful.
JARVIS 1.1.5 was used only as a feature reference. This implementation contains
no code, prompts, assets, or branding from its installers, and adds no runtime
dependencies or permissions.

## Implemented

- A task-local installed-app catalog supplies at most eight task-relevant,
  unblocked app names/packages to the model. Exact package matches win;
  equally matched app names return candidates instead of silently launching one.
- Screen actions are checked against a fresh accessibility snapshot after the
  model responds and again after approval or pause. A changed screen invalidates
  the pending action; the model must observe and decide again.
- Pause is checked after model responses and approvals. Stop cancels the active
  HTTP request and retry delay. The task deadline includes model requests,
  pauses, and approval waits. Cancelled task cleanup cannot stop a later task.
- Action results distinguish Android accepting an action from an observed
  result. A launch is confirmed only when the requested package is foregrounded.
  Button notes gain success only for an observed change of a uniquely identified
  checkable control. Navigation and unrelated screen changes remain unverified
  for learning; the model still receives the new screen to assess the task.

## Verification

Run `./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug` with
JDK 17 and Android SDK 35. Tests cover app identity and ambiguity, bounded app
context, changed screens during decisions and approval, pause/resume, late Stop,
unverified learning, task deadlines, and cancellation of HTTP requests/retries.

Phone checks still required on the Galaxy S24 Ultra:

1. Request an installed app by name and package; check the correct app opens.
2. With similarly named apps, confirm ODCM reports ambiguity before launching.
3. Change screens while ODCM is thinking or awaiting approval; confirm no old
   tap runs and the next decision uses the new screen.
4. Pause while thinking, then resume; stop during a model request and confirm no
   late action occurs. Start a new task after stopping.
5. Tap a control that produces no readable change and confirm its Guide success
   count does not increase. Toggle a uniquely identified switch and inspect its
   button note.

The current snapshot comparison is conservative: even an unrelated accessibility
content change can cause a retry. Observation proves only the stated UI result,
not that a business operation completed. Voice capture, learned-flow replay,
remote desktop control, and continuous background listening are not added here.
