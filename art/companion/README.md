# Companion scenes

The user supplied the original writing video and resting character artwork for
this app. `working-source.mp4` is the supplied video unchanged. The resting JPEG
is preserved in `app/src/main/res/drawable-nodpi/companion_resting.jpg`.
Other supplied poses are not bundled because this update only uses the writing
and resting scenes. No third-party application assets were extracted.

`tools/prepare-companion-loop.sh` converts the video into the bundled
`companion_working.webp`. Run it with an installed FFmpeg that supports
`libwebp_anim`. FFmpeg is a build-time tool; its binary is not shipped in the app.

Output: 576x324, 132 frames, 5.499 seconds, 41/42 ms frame durations, infinite
repeat. Source frames 85..228 form the forward-motion segment. A 12-frame
crossfade joins the end into the start without a duplicate hold frame. The
source has camera motion; the transition is a blend, not a claim that the
original footage was naturally periodic. Audio is omitted for the working loop.

Android's AnimatedImageDrawable retains the decoder through chat/log updates.
This removes the per-cycle video-seek path. Actual pacing and thermal behavior
must be measured on the phone; encoded frame timing alone does not prove them.

The writing state has its own packaged scene. Idle uses the supplied resting
still, or the user's existing custom avatar. A custom animated idle avatar is
held still. Additional talking/sleeping videos have not yet been supplied.

References:
- https://developer.android.com/reference/android/graphics/drawable/AnimatedImageDrawable
- https://platform.claude.com/docs/en/build-with-claude/streaming
- https://developers.openai.com/api/reference/resources/chat/subresources/completions/streaming-events
