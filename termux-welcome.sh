#!/usr/bin/env bash
# ╔══════════════════════════════════════════════════════════════╗
# ║  STARPATH WELCOME  —  rainbow intro + floating stars +       ║
# ║  matrix rain + boot log, then auto-types & runs `starpath`   ║
# ║  Press any key at any time to skip straight to the launch.   ║
# ╚══════════════════════════════════════════════════════════════╝
#
# Tweaks (env vars):
#   WELCOME_DELAY=0.035   frame delay (bigger = slower / longer show)
#   MATRIX_ASCII=1        use ASCII instead of katakana in the rain
#   STARPATH_CMD=starpath command launched at the end

# Guard so `bash -ic starpath` (fallback below) can't loop if you put this in .bashrc
[[ -n "${STARPATH_WELCOME_DONE:-}" ]] && exit 0
export STARPATH_WELCOME_DONE=1

DELAY="${WELCOME_DELAY:-0.035}"
LAUNCH="${STARPATH_CMD:-starpath}"

E=$'\e'
RST="${E}[0m"
RB=(196 202 208 214 220 226 190 154 118 82 46 48 50 51 45 39 33 27 57 93 129 165 201 199 197)
NRB=${#RB[@]}
SKIP=0

# ─────────────────────────── terminal helpers ───────────────────────────
get_size() {
  local s
  s=$(stty size 2>/dev/null)
  ROWS=${s% *}; COLS=${s#* }
  [[ $ROWS =~ ^[0-9]+$ ]] || ROWS=$(tput lines 2>/dev/null || echo 24)
  [[ $COLS =~ ^[0-9]+$ ]] || COLS=$(tput cols 2>/dev/null || echo 80)
  (( ROWS < 10 )) && ROWS=24
  (( COLS < 20 )) && COLS=80
}
cls()     { printf '%s' "${E}[0m${E}[2J${E}[H"; }
cleanup() { printf '%s' "${E}[0m${E}[?25h"; stty echo 2>/dev/null; }
trap 'cleanup; printf "\n"; exit 130' INT TERM
trap 'get_size' WINCH

# Frame delay that doubles as "press any key to skip"
wait_frame() {
  local k
  (( SKIP )) && return
  if read -rsn1 -t "${1:-$DELAY}" k 2>/dev/null; then SKIP=1; fi
}

# ─────────────────────────── block font ───────────────────────────
declare -A G=(
  [W0]="#   #" [W1]="#   #" [W2]="# # #" [W3]="## ##" [W4]="#   #"
  [E0]="#####" [E1]="#    " [E2]="#### " [E3]="#    " [E4]="#####"
  [L0]="#    " [L1]="#    " [L2]="#    " [L3]="#    " [L4]="#####"
  [C0]=" ####" [C1]="#    " [C2]="#    " [C3]="#    " [C4]=" ####"
  [O0]=" ### " [O1]="#   #" [O2]="#   #" [O3]="#   #" [O4]=" ### "
  [M0]="#   #" [M1]="## ##" [M2]="# # #" [M3]="#   #" [M4]="#   #"
  [S0]=" ####" [S1]="#    " [S2]=" ### " [S3]="    #" [S4]="#### "
  [T0]="#####" [T1]="  #  " [T2]="  #  " [T3]="  #  " [T4]="  #  "
  [A0]=" ### " [A1]="#   #" [A2]="#####" [A3]="#   #" [A4]="#   #"
  [R0]="#### " [R1]="#   #" [R2]="#### " [R3]="#  # " [R4]="#   #"
  [P0]="#### " [P1]="#   #" [P2]="#### " [P3]="#    " [P4]="#    "
  [H0]="#   #" [H1]="#   #" [H2]="#####" [H3]="#   #" [H4]="#   #"
)
render_word() {
  local w=$1 r i
  BL=()
  for r in 0 1 2 3 4; do
    local line=""
    for (( i=0; i<${#w}; i++ )); do line+="${G[${w:i:1}$r]} "; done
    BL[r]="${line% }"
  done
  BW=${#BL[0]}
}
render_word WELCOME;  WEL=("${BL[@]}"); WELW=$BW
render_word STARPATH; STP=("${BL[@]}"); STPW=$BW

# draw_banner <array-name> <width> <top-row> <color-offset> <fallback-word>  -> BBUF
draw_banner() {
  local -n L=$1
  local w=$2 row=$3 off=$4 word=$5 r i x ch line out=""
  if (( w > COLS - 2 )); then                       # narrow screen: spaced single line
    local s="" ; for (( i=0; i<${#word}; i++ )); do s+="${word:i:1} "; done
    s="${s% }"; x=$(( (COLS - ${#s}) / 2 + 1 )); (( x < 1 )) && x=1
    out+="${E}[$((row+2));${x}H"
    for (( i=0; i<${#s}; i++ )); do
      out+="${E}[1;38;5;${RB[( (off - i) % NRB + NRB ) % NRB]}m${s:i:1}"
    done
    BBUF=$out; return
  fi
  x=$(( (COLS - w) / 2 + 1 ))
  for r in 0 1 2 3 4; do
    line=${L[r]}
    out+="${E}[$((row+r));${x}H"
    for (( i=0; i<w; i++ )); do
      ch=${line:i:1}
      if [[ $ch == "#" ]]; then
        out+="${E}[1;38;5;${RB[( (off - i - r) % NRB + NRB ) % NRB]}m█"
      else
        out+=" "
      fi
    done
  done
  BBUF=$out
}

# rainbow text centered on a row -> TBUF
rainbow_line() {
  local s=$1 row=$2 off=$3 i x out
  x=$(( (COLS - ${#s}) / 2 + 1 )); (( x < 1 )) && x=1
  out="${E}[${row};${x}H"
  for (( i=0; i<${#s}; i++ )); do
    out+="${E}[1;38;5;${RB[( (off + i) % NRB )]}m${s:i:1}"
  done
  TBUF=$out
}

# ─────────────────────────── phase 0: rainbow curtain ───────────────────────────
curtain() {
  (( SKIP )) && return
  local r bar blk
  printf -v bar '%*s' "$COLS" ''
  for blk in "░" "▒" "▓" "█"; do
    local b=${bar// /$blk}
    for (( r=1; r<=ROWS; r++ )); do
      printf '%s' "${E}[${r};1H${E}[38;5;${RB[(r + ${#blk}*3) % NRB]}m${b}"
      wait_frame 0.006; (( SKIP )) && return
    done
  done
  for (( r=ROWS; r>=1; r-- )); do
    printf '%s' "${E}[0m${E}[${r};1H${E}[2K"
    wait_frame 0.008; (( SKIP )) && return
  done
}

# ─────────────────────────── particles (floating + falling symbols) ───────────────────────────
SYMS=(✦ ✧ ★ ☆ ✶ ✷ ✸ ✹ ✺ ❄ ◆ ◇ ◈ ❖ ⋆ ∗ ⟡ ✪ ✯ ✵ • ° · ☾ ♦ ✚ ❂ ☼ ⊹ ✱)
NS=${#SYMS[@]}
PCOL=(51 45 39 87 123 159 195 231 226 220 214 201 207 213 219 129 165 171 177 118 154 190)
NPC=${#PCOL[@]}
px=(); py=(); pv=(); pd=(); ps=(); pk=()

spawn() {   # spawn <i> <at_edge 0|1>
  local i=$1
  px[i]=$(( RANDOM % (COLS - 1) + 1 ))
  if (( RANDOM % 4 == 0 )); then pd[i]=-1; else pd[i]=1; fi     # 25% float upward
  if (( $2 )); then
    if (( pd[i] == 1 )); then py[i]=10; else py[i]=$(( ROWS * 10 )); fi
  else
    py[i]=$(( (RANDOM % ROWS + 1) * 10 ))
  fi
  pv[i]=$(( RANDOM % 9 + 2 ))
  ps[i]=$(( RANDOM % NS ))
  pk[i]=${PCOL[RANDOM % NPC]}
}
init_particles() {
  NP=$1; local i
  for (( i=0; i<NP; i++ )); do spawn "$i" 0; done
}
step_particles() {   # -> PBUF
  local i y out=""
  for (( i=0; i<NP; i++ )); do
    y=$(( py[i] / 10 )); out+="${E}[${y};${px[i]}H "
  done
  for (( i=0; i<NP; i++ )); do
    (( py[i] += pv[i] * pd[i] ))
    if (( RANDOM % 5 == 0 )); then
      (( px[i] += RANDOM % 3 - 1 ))
      (( px[i] < 1 )) && px[i]=1
      (( px[i] > COLS - 1 )) && px[i]=$(( COLS - 1 ))
    fi
    y=$(( py[i] / 10 ))
    if (( y < 1 || y > ROWS )); then spawn "$i" 1; y=$(( py[i] / 10 )); fi
    if (( RANDOM % 12 == 0 )); then ps[i]=$(( RANDOM % NS )); pk[i]=${PCOL[RANDOM % NPC]}; fi
    out+="${E}[${y};${px[i]}H${E}[1;38;5;${pk[i]}m${SYMS[ps[i]]}"
  done
  PBUF=$out
}

starfield() {          # just symbols drifting
  (( SKIP )) && return
  local f
  for (( f=0; f<$1; f++ )); do
    step_particles
    printf '%s' "$PBUF"
    wait_frame; (( SKIP )) && return
  done
}

welcome_show() {       # symbols + giant rainbow WELCOME + subtitle
  (( SKIP )) && return
  local f row sub info typed n
  row=$(( ROWS / 2 - 4 )); (( row < 2 )) && row=2
  sub="~ welcome to the starpath ~"
  info="$(whoami 2>/dev/null)@termux  ::  $(date '+%a %b %d  %H:%M')"
  for (( f=0; f<$1; f++ )); do
    step_particles
    draw_banner WEL "$WELW" "$row" "$f" WELCOME
    n=$(( f / 2 )); typed=${sub:0:n}
    rainbow_line "$typed" $(( row + 7 )) $(( f * 2 ))
    local t1=$TBUF
    local t2=""
    if (( f > ${#sub} * 2 + 10 )); then
      local x=$(( (COLS - ${#info}) / 2 + 1 )); (( x < 1 )) && x=1
      t2="${E}[$(( row + 9 ));${x}H${E}[0;38;5;$(( 244 + (f / 3) % 12 ))m${info}"
    fi
    printf '%s' "$PBUF$BBUF$t1$t2"
    wait_frame; (( SKIP )) && return
  done
}

# ─────────────────────────── matrix rain ───────────────────────────
if [[ -n "${MATRIX_ASCII:-}" ]]; then
  MCH=(0 1 2 3 4 5 6 7 8 9 A B C D E F X Z @ '#' '$' % '&' '*' + = '<' '>' '|' : .)
else
  MCH=(ｱ ｲ ｳ ｴ ｵ ｶ ｷ ｸ ｹ ｺ ｻ ｼ ｽ ｾ ｿ ﾀ ﾁ ﾂ ﾃ ﾄ ﾅ ﾆ ﾇ ﾈ ﾉ ﾊ ﾋ ﾌ ﾍ ﾎ ﾏ ﾐ ﾑ ﾒ ﾓ ﾔ ﾕ ﾖ ﾗ ﾘ ﾙ ﾚ ﾛ ﾜ ﾝ 0 1 2 3 4 5 7 8 9 Z : = '*' + '<' '>' '|')
fi
NM=${#MCH[@]}
MSGS=("wake up..." "the grid has you..." "follow the white stars..." "knock, knock." "entering the starpath.")

matrix() {
  (( SKIP )) && return
  local frames=$1 f c x y d t NC buf
  local -a my ml mv
  NC=$(( (COLS - 1) / 2 ))
  for (( c=0; c<NC; c++ )); do
    my[c]=$(( -(RANDOM % ROWS) ))
    ml[c]=$(( RANDOM % (ROWS / 2 + 1) + 5 ))
    mv[c]=$(( RANDOM % 2 + 1 ))
  done
  local maxl=0 m; for m in "${MSGS[@]}"; do (( ${#m} > maxl )) && maxl=${#m}; done
  local per=$(( frames / ${#MSGS[@]} ))
  cls
  for (( f=0; f<frames; f++ )); do
    buf=""
    for (( c=0; c<NC; c++ )); do
      (( f % mv[c] )) && continue
      x=$(( c * 2 + 1 )); y=${my[c]}
      (( y - 1 >= 1 && y - 1 <= ROWS )) && buf+="${E}[$((y-1));${x}H${E}[0;1;38;5;46m${MCH[RANDOM % NM]}"
      (( y >= 1 && y <= ROWS ))         && buf+="${E}[${y};${x}H${E}[0;1;38;5;231m${MCH[RANDOM % NM]}"
      d=$(( y - ml[c] / 2 ))
      (( d >= 1 && d <= ROWS ))         && buf+="${E}[${d};${x}H${E}[0;38;5;34m${MCH[RANDOM % NM]}"
      d=$(( y - ml[c] + 2 ))
      (( d >= 1 && d <= ROWS ))         && buf+="${E}[${d};${x}H${E}[0;38;5;22m${MCH[RANDOM % NM]}"
      t=$(( y - ml[c] ))
      (( t >= 1 && t <= ROWS ))         && buf+="${E}[${t};${x}H "
      my[c]=$(( y + 1 ))
      if (( t > ROWS )); then
        my[c]=$(( -(RANDOM % (ROWS / 2 + 1)) ))
        ml[c]=$(( RANDOM % (ROWS / 2 + 1) + 5 ))
        mv[c]=$(( RANDOM % 2 + 1 ))
      fi
    done
    # typed message overlay, after the rain has built up a bit
    if (( f > 25 )); then
      local idx=$(( (f - 25) / per ))
      if (( idx < ${#MSGS[@]} )); then
        local s=${MSGS[idx]} n=$(( ((f - 25) % per) / 2 + 1 ))
        local shown=${s:0:n} box
        local cur=" "; (( (f / 6) % 2 )) && cur="█"
        printf -v box '%-*s' $(( maxl + 2 )) " ${shown}${cur}"
        local bx=$(( (COLS - maxl - 2) / 2 + 1 )); (( bx < 1 )) && bx=1
        buf+="${E}[$(( ROWS / 2 ));${bx}H${E}[0;1;38;5;118;48;5;16m${box}${RST}"
      fi
    fi
    printf '%s' "$buf"
    wait_frame 0.03; (( SKIP )) && return
  done
}

# ─────────────────────────── boot log + progress bar ───────────────────────────
boot() {
  (( SKIP )) && return
  cls
  local spin=(⠋ ⠙ ⠹ ⠸ ⠼ ⠴ ⠦ ⠧ ⠇ ⠏) it k
  local items=(
    "Booting stellar kernel"
    "Mounting /starpath/core"
    "Charting constellations"
    "Linking X11 socket :0"
    "Spinning up warp drive"
    "Bending light around gravity wells"
    "Decrypting nebula keys"
    "Handshaking with the void"
  )
  printf '\n  %s\n\n' "${E}[1;38;5;201m✦ ${E}[38;5;51mSTARPATH ${E}[38;5;226mSYSTEM ${E}[38;5;46mINIT ${E}[38;5;201m✦${RST}"
  for it in "${items[@]}"; do
    for (( k=0; k<10; k++ )); do
      printf '\r  %s %s' "${E}[1;38;5;${RB[(k*2) % NRB]}m${spin[k]}" "${E}[0;38;5;250m${it}..."
      wait_frame 0.05; (( SKIP )) && return
    done
    printf '\r  %s %s %s\n' "${E}[1;38;5;46m✔" "${E}[0;38;5;250m${it}" "${E}[1;38;5;46m[ OK ]${RST}"
  done
  printf '\n'
  local w=$(( COLS - 14 )); (( w < 10 )) && w=10
  local p i fill bar
  for (( p=0; p<=100; p+=2 )); do
    fill=$(( p * w / 100 )); bar=""
    for (( i=0; i<w; i++ )); do
      if (( i < fill )); then bar+="${E}[38;5;${RB[(i * NRB / w) % NRB]}m█"
      else bar+="${E}[38;5;236m░"; fi
    done
    printf '\r  %s %s' "$bar" "${E}[1;38;5;231m$(printf '%3d' "$p")%${RST}"
    wait_frame 0.03; (( SKIP )) && return
  done
  printf '\n'
  wait_frame 0.4
}

# ─────────────────────────── finale: type & run starpath ───────────────────────────
finale() {
  SKIP=0
  cls
  local row=$(( ROWS / 2 - 5 )); (( row < 1 )) && row=1
  local f
  init_particles 12
  for (( f=0; f<45; f++ )); do
    step_particles
    draw_banner STP "$STPW" "$row" $(( f * 2 )) STARPATH
    rainbow_line "✦  all systems nominal  ✦" $(( row + 7 )) "$f"
    printf '%s' "$PBUF$BBUF$TBUF"
    wait_frame 0.04; (( SKIP )) && break
  done
  # fake prompt at the bottom, auto-typed
  printf '%s' "${E}[${ROWS};1H${E}[2K${E}[0;1;38;5;46m~ ${E}[0;38;5;231m\$ ${E}[?25h"
  local i
  for (( i=0; i<${#LAUNCH}; i++ )); do
    printf '%s' "${E}[1;38;5;51m${LAUNCH:i:1}"
    sleep "0.$(( RANDOM % 12 + 6 ))"
  done
  sleep 0.45
  printf '%s\n' "${RST}"
}

# ─────────────────────────── run it ───────────────────────────
get_size
stty -echo 2>/dev/null
printf '%s' "${E}[?25l"
cls

curtain
init_particles $(( COLS * ROWS / 60 + 15 ))
starfield 110
welcome_show 220
matrix 340
boot
finale

cleanup
# exec the real command; fall back to an interactive shell so .bashrc aliases/functions work
if type -P "$LAUNCH" >/dev/null 2>&1; then
  exec "$LAUNCH"
else
  exec bash -ic "$LAUNCH"
fi
