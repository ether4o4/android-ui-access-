# Music Producer Skill

Generate, edit, and produce professional music with voice cloning, beat creation, vocal editing, and sound design.

---

## Capabilities

### 1. Generate Music from Prompts
Create original instrumental tracks from text descriptions:
- Beats (lo-fi, trap, house, ambient, etc.)
- Genre-specific compositions
- Mood-based generation (chill, energetic, cinematic, etc.)
- Custom duration (3-600 seconds)

**Example:**
```
"Generate a 30-second lo-fi hip hop beat with jazz samples and rainfall"
```

### 2. Clone Your Voice
Create a personalized voice model from your own audio:
- Clone from 1-3 audio samples
- Higher quality with more samples
- Use cloned voice in any generation
- Perfect for personalized voiceovers

**Example:**
```
"Clone my voice from these 3 recordings and save as 'my_voice'"
```

### 3. Text-to-Speech with Voice Cloning
Generate speech using your cloned voice:
- Use cloned voice for narration
- Multi-speaker dialogue with different voices
- Custom voice selection per speaker

**Example:**
```
"Using my cloned voice, generate: 'Welcome to my podcast'"
```

### 4. Sound Design & Effects
Generate realistic sound effects from text:
- Cinematic effects (whoosh, impact, transitions)
- Environmental sounds (rain, thunder, wind)
- Mechanical sounds (beeps, clicks, glitches)
- Loopable background textures

**Example:**
```
"Generate a cinematic whoosh effect, 2 seconds, then save it"
```

### 5. Music Composition
Create full compositions:
- Instrumental tracks
- Layered soundscapes
- Background music for videos
- Custom melodies from descriptions

**Example:**
```
"Compose a 60-second ambient electronic track with evolving synths"
```

### 6. Vocal Editing & Processing
Edit and enhance vocal tracks with advanced effects:
- Volume normalization & loudness matching
- Fade in/out effects
- Speed adjustment (0.5x - 2x)
- Pitch shifting (-12 to +12 semitones)
- Audio cleanup (noise removal)
- **Voice character effects:**
  - Dark Demon voice (lower pitch, heavy distortion, reverb)
  - Ethereal/Angelic (raised pitch, lush reverb, chorus)
  - Robotic (bit-crushing, formant modification)
  - Clear/Pristine (EQ enhancement, compression)
  - Raspy/Gritty (saturation, slight distortion)
  - Hollow/Distant (reverb, delay, reduced lows)

**Example:**
```
"Load vocal_track.mp3, apply dark demon effect, add reverb"
"Make this vocal sound ethereal and angelic"
"Apply clear vocal processing with enhancement"
"Create a robotic voice effect"
```

### 7. Beat Creation & Arrangement
Build custom beats:
- Drum pattern generation
- BPM control
- Loop creation
- Layering instruments

**Example:**
```
"Create a 120 BPM trap beat with 808 bass and hi-hat patterns"
```

### 8. Voice Dubbing
Dub audio/video into different languages:
- Preserve original tone and emotion
- Multiple language support
- Maintains timing and sync

**Example:**
```
"Dub my podcast into Spanish, keep English audio track"
```

### 9. Audio Mixing & Mastering
Professional audio processing:
- EQ adjustments
- Compression
- Reverb/delay
- Level balancing
- Master limiting

**Example:**
```
"Mix 3 vocal takes, auto-level, add light reverb, master to -3dB"
```

### 10. Combine Multiple Audio Sources
Layer and blend tracks:
- Merge beats with vocals
- Layer multiple instruments
- Create stems
- Sequence compositions

**Example:**
```
"Combine beat.mp3 + vocal.mp3 with 2dB vocal boost"
```

---

## Tools Available

| Tool | Purpose |
|------|---------|
| `generate_music` | Create music from text prompt |
| `clone_voice` | Clone voice from audio samples |
| `text_to_speech` | Generate speech with voice |
| `generate_sound_effects` | Create sound effects |
| `edit_vocals` | Process and edit vocal tracks |
| `create_beat` | Generate drum beats |
| `dub_audio` | Translate audio to another language |
| `process_audio` | EQ, compress, effects |
| `mix_tracks` | Combine multiple audio files |
| `extract_vocals` | Isolate vocals from mix |
| `apply_effects` | Add reverb, delay, chorus, etc. |

---

## Workflow Examples

### Example 1: Create a Podcast Intro

```
1. "Generate a 10-second upbeat electronic beat"
   → Generates energetic.mp3

2. "Add sound effects: swoosh, then impact"
   → Adds effects to intro

3. "Using my cloned voice, say 'Welcome to my podcast'"
   → Adds personalized voiceover

4. "Mix beat + effects + voice, normalize levels"
   → Final intro.mp3
```

### Example 2: Remixing a Song

```
1. Load original song
2. Extract vocals → vocal_stems.mp3
3. Generate new beat matching original tempo/key
4. Clone your voice for new vocals
5. Mix extracted beat + new beat + your vocals
6. Add effects and master
```

### Example 3: Music Production Pipeline

```
1. "Generate a 2-minute ambient track"
2. "Add subtle sound textures"
3. "Create complementary pad layer"
4. "Mix all layers"
5. "Master to streaming quality"
```

---

## Voice Cloning Details

### How It Works
1. Provide 1-3 audio samples of your voice (30sec-2min each)
2. System analyzes voice characteristics
3. Creates personalized voice model
4. Use ID in any text-to-speech generation

### Quality Levels
- **1 sample:** Basic quality, good for demos
- **2 samples:** Good quality, recommended
- **3 samples:** Professional quality, best results

### Usage
```bash
# Clone voice
voice_id=$(clone-voice -n "my_voice" sample1.mp3 sample2.mp3)

# Use cloned voice
say -v "$voice_id" "Your text here" -o output.mp3
```

---

## Beat Creation

### Supported Genres
- Lo-fi hip hop
- Trap
- House
- Ambient
- Electronic
- Jazz
- Rock
- Pop
- Funk
- Reggae
- Classical

### Customization
- BPM (60-200)
- Time signature
- Instrument selection
- Loop length
- Swing/groove

---

## Sound Design

### Effect Categories
- **Cinematic:** whoosh, impact, transition, swell
- **Environmental:** rain, wind, thunder, ocean
- **Mechanical:** beep, click, glitch, servo
- **Atmospheric:** drone, pad, texture, shimmer
- **Vocal:** breath, whisper, laugh, cry

### Options
- Duration: 0.5-30 seconds
- Loopable: seamless loop generation
- Layerable: combine multiple effects

---

## Audio Processing

### Available Effects
- Reverb (small room to large hall)
- Delay (echo, slapback, ambient)
- Chorus (lush, detuned)
- Distortion (mild to extreme)
- Compression (dynamic control)
- EQ (full graphic EQ)
- Pitch shift (-12 to +12 semitones)
- Time stretch (0.5x to 2x)
- Noise gate (remove silence/noise)
- Limiter (prevent clipping)

---

## Dubbing & Localization

### Supported Languages
- Spanish (es)
- French (fr)
- German (de)
- Italian (it)
- Portuguese (pt)
- Russian (ru)
- Japanese (ja)
- Chinese (zh)
- Hindi (hi)
- Korean (ko)
- And 20+ more

### Features
- Preserves emotion and tone
- Maintains timing/sync
- Supports video files
- Multi-track dubbing

---

## Output Formats

- **Audio:** MP3, WAV, FLAC, OGG, AAC
- **Video:** MP4, MKV, MOV (with dubbed audio)
- **Quality:** 128kbps to 320kbps (configurable)

---

## Workflow Tips

### For Podcasters
1. Clone your voice
2. Generate intro/outro music
3. Use cloned voice for automated segments
4. Mix with background music
5. Export final episode

### For Music Producers
1. Generate beat patterns
2. Layer sound effects
3. Add synthesized elements
4. Use voice cloning for vocals
5. Mix and master to perfection

### For Content Creators
1. Generate background music
2. Create sound effects library
3. Dub content to multiple languages
4. Automate voiceovers
5. Professional audio finishing

---

## Integration with Other Skills

**Works with:**
- **Video Producer** — add music/sound to videos
- **Frontend Engineer** — generate audio assets for apps
- **Technical Writer** — add voiceovers to tutorials
- **Software Engineer** — generate sound effects for apps

---

## System Prompts & Behaviors

This skill:
- Generates high-quality audio
- Preserves artistic intent
- Maintains technical standards
- Supports professional workflows
- Optimizes for different use cases

---

## Limitations & Considerations

- Generation takes 10-60 seconds per track
- Maximum 600 seconds (10 minutes) per generation
- Voice cloning improves with more samples
- Some effects may require tuning
- Output quality depends on input quality

---

## Examples

### Simple
```
"Make a 10-second chill beat"
→ chillbeat.mp3
```

### Intermediate
```
"Clone my voice from voice_sample.mp3, then generate a podcast intro using it"
→ intro_with_my_voice.mp3
```

### Advanced
```
"Generate a 2-minute lo-fi beat, extract the vocal from this acapella, add my cloned voice saying the chorus, mix everything with -3dB vocal reduction on the original, add subtle reverb, and master to -14 LUFS"
→ final_song.mp3
```

---

## Next Steps

1. **Prepare voice samples** (30sec-2min each)
2. **Clone your voice**
3. **Generate first beat**
4. **Mix with vocals**
5. **Apply effects**
6. **Export final track**

Your music production toolkit is ready. Go create.
