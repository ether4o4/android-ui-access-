#!/usr/bin/env bash
# Build-time media conversion only; FFmpeg is not shipped in the Android app.
set -euo pipefail
repo_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
source_clip="${1:-$repo_root/art/companion/working-source.mp4}"
target_clip="$repo_root/app/src/main/res/drawable-nodpi/companion_working.webp"
# Select a forward-writing segment with similar endpoint framing. Blend the last
# 12 frames into the first 12; begin at frame 97 so the loop has no duplicate hold.
ffmpeg -y -v error -i "$source_clip" -filter_complex \
 '[0:v]fps=24,scale=576:324,setsar=1,split=3[body][tail][head];[body]trim=start_frame=97:end_frame=217,setpts=PTS-STARTPTS[b];[tail]trim=start_frame=217:end_frame=229,setpts=PTS-STARTPTS[t];[head]trim=start_frame=85:end_frame=97,setpts=PTS-STARTPTS[h];[t][h]blend=all_expr=A*(1-min(1\,T/0.458333333))+B*min(1\,T/0.458333333)[join];[b][join]concat=n=2:v=1:a=0,format=yuv420p[v]' \
 -map '[v]' -an -c:v libwebp_anim -lossless 0 -quality 74 -compression_level 4 -loop 0 "$target_clip"
