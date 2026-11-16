#!/usr/bin/env bash
# Build-time conversion; playback is entirely local and silent on Android.
set -euo pipefail
repo_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
source_clip="${1:-$repo_root/art/companion/sleeping-source.mp4}"
target_clip="$repo_root/app/src/main/res/drawable-nodpi/companion_sleeping.webp"
# The source camera moves closer throughout the clip. Return through the frames
# in reverse to avoid a jump or a double-image dissolve. Omit both duplicate
# endpoints: forward 0..239, reverse 238..1, then forward 0 again.
ffmpeg -y -v error -i "$source_clip" -filter_complex \
 '[0:v]fps=24,scale=576:324,setsar=1,trim=end_frame=240,split=2[forward][back];[forward]setpts=PTS-STARTPTS[f];[back]trim=start_frame=1:end_frame=239,reverse,setpts=PTS-STARTPTS[r];[f][r]concat=n=2:v=1:a=0,format=yuv420p[v]' \
 -map '[v]' -an -c:v libwebp_anim -lossless 0 -quality 74 -compression_level 4 -loop 0 "$target_clip"
