# ODCM 0.3.6 voice conversation

Tap the companion mic once. Android sends the final recognized sentence automatically, ODCM speaks the reply with the bundled Michael male voice (or the optional Android offline engine), and listening resumes after the task and playback finish. Tap the mic again to end the voice session. The square Stop button stops both the task and voice. Closing the companion or switching the screen off ends voice.

There is no recording dialog covering the avatar or chat dropdown. Android still shows its microphone permission prompt (first use), privacy indicator, and a voice notification with a Stop action. Recognition prefers the on-device engine; if unavailable, the installed Android recognizer may use a network connection. The default TTS engine and model are bundled inside the APK; no separate voice app or runtime download is required. The cloud model still needs internet.

The microphone is paused during replies. This is automatic turn-taking, not simultaneous speaking and listening. Three consecutive empty recognition attempts end the session without model calls. Recognition, initialization, playback, and task waits are bounded. Errors appear in the chat drawer and a toast. A mic indicator distinguishes listening, waiting, and speaking without opening the drawer.

## Phone acceptance check

1. In ODCM leave **Built-in male voice · Michael (offline)** checked. Turn on airplane mode, make sure Wi-Fi is off, then tap **Test voice**. It should speak without an API request. Restore networking for the cloud agent. To use Next-gen Kaldi instead, uncheck built-in voice and select the engine in Android settings.
2. In ODCM tap the mic, grant permission if needed, and say a short sentence. Stop talking. Verify one message is sent automatically and the reply is audible.
3. After the reply, speak a second sentence without tapping anything. Verify it sends and replies again.
4. Open/close the chat dropdown during the conversation. Verify avatar, resizing and drag placement remain available.
5. Tap the mic to stop, then speak. Verify no transcript is sent. Repeat with the square Stop button and screen-off.
6. Test an error (missing provider key or unavailable voice) and verify there is a useful message rather than a silently stuck microphone.

Unit tests cover queue completion, cancellation, initialization errors and the hands-free controller. An Android emulator test loads the actual packaged native runtime/model, synthesizes Michael, checks PCM output and plays it with Wi-Fi/mobile data disabled. Real Samsung voice latency, microphone recognition and audio quality still need a phone check.
