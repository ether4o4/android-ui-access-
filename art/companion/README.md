# Companion scenes

The user supplied the original writing video and resting character artwork for
this app. `working-source.mp4` is the supplied video unchanged. The resting JPEG
is preserved in `app/src/main/res/drawable-nodpi/companion_resting.jpg`.
The supplied `8964.mp4` sleeping clip is preserved as `sleeping-source.mp4`.
Other supplied poses are not bundled. No third-party application assets were extracted.

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

`tools/prepare-sleeping-loop.sh` converts the sleeping clip to
`companion_sleeping.webp`: 576x324, 478 frames at 24 fps, approximately 19.9 seconds,
infinite repeat, no audio. The 10-second source moves the camera closer, so the
loop plays forward then backward, omitting duplicate endpoint frames. This
avoids the large framing jump and double-image dissolve of a direct restart.
Physical-device playback still needs checking.

Idle and paused states now loop the sleeping scene, or the user's existing
custom avatar. Working uses the writing scene. Both are packaged local resources:
no avatar API, key, network access, or provider quota is involved. The displayed
animation is retained across conversation updates and stopped when screen-off,
hidden by the keyboard, unbound, or closed. Talking footage and voice playback
are not yet integrated; there is no simulated speaking based on arriving text.

References:
- https://developer.android.com/reference/android/graphics/drawable/AnimatedImageDrawable
- https://platform.claude.com/docs/en/build-with-claude/streaming
- https://developers.openai.com/api/reference/resources/chat/subresources/completions/streaming-events
