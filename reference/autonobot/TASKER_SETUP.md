# Tasker + AutoInput Setup Guide

This enables **full screen autonomy** — tapping buttons, typing text, swiping — all bypassing Samsung Knox restrictions via Android's Accessibility Service.

## Prerequisites

1. **Tasker** — Play Store ($3.49) or [F-Droid](https://f-droid.org/packages/net.dinglisch.android.taskerm/)
2. **AutoInput** — Play Store (Tasker plugin by joaomgcd)
3. **Termux:Tasker** — F-Droid (bridge between Termux and Tasker)

## Why This Works

Samsung Knox blocks ADB input commands from Termux (self-device ADB). But Knox does **NOT** block Accessibility Services — they're a legitimate Android API. AutoInput registers as an accessibility service and can tap/type/swipe anything.

```
Termux → writes JSON command file
Tasker → monitors file → reads command
AutoInput → executes tap/type/swipe as Accessibility Service
Knox → allows it ✅
```

## One-Time Setup (5 minutes)

### Step 1: Enable AutoInput Accessibility Service

1. Open **Settings → Accessibility → Installed Services**
2. Find **AutoInput** → Enable it
3. Confirm the permission dialog

### Step 2: Grant Termux:Tasker Permission

1. Open **Termux** and run:
   ```bash
   mkdir -p ~/.termux/tasker
   ```
2. Open **Tasker → Preferences → Misc → Allow External Access** → ON

### Step 3: Create the Tasker Profile

**Profile: AutoNoBot Command Listener**

1. Open Tasker → **Profiles** tab → **+** → **Event**
2. Choose **File → File Modified**
3. Set path: `/data/data/com.termux/files/home/.autonobot/tasker_cmd.json`
4. Link to new Task → name it **"AutoNoBot Execute"**

### Step 4: Create the Task

In the **AutoNoBot Execute** task, add these actions:

**Action 1: Read File**
- Action: File → Read File
- File: `~/.autonobot/tasker_cmd.json`
- To Variable: `%cmd_json`

**Action 2: JavaScriptlet (parse JSON)**
```javascript
var cmd = JSON.parse(cmd_json);
var action = cmd.action;
var params = cmd.params;
setLocal('action', action);
setLocal('params_json', JSON.stringify(params));
setLocal('param_x', params.x || '');
setLocal('param_y', params.y || '');
setLocal('param_text', params.text || '');
setLocal('param_key', params.key || '');
setLocal('param_startX', params.startX || '');
setLocal('param_startY', params.startY || '');
setLocal('param_endX', params.endX || '');
setLocal('param_endY', params.endY || '');
setLocal('param_duration', params.duration || '300');
setLocal('param_timeout', params.timeout || '5000');
```

**Action 3: If %action = tap**
- Plugin → AutoInput → Action
- Type: Click
- Click Type: Point
- X: `%param_x`
- Y: `%param_y`

**Action 4: Else If %action = type**
- Plugin → AutoInput → Action
- Type: Text
- Text: `%param_text`

**Action 5: Else If %action = swipe**
- Plugin → AutoInput → Gesture
- Start X: `%param_startX`
- Start Y: `%param_startY`
- End X: `%param_endX`
- End Y: `%param_endY`
- Duration: `%param_duration`

**Action 6: Else If %action = key**
- Plugin → AutoInput → Action
- Type: Key
- Key: `%param_key`

**Action 7: Else If %action = click_text**
- Plugin → AutoInput → UI Query
- Search Text: `%param_text`
- → Then: AutoInput → Action → Click (on found element)

**Action 8: Else If %action = wait_click**
- Wait: `%param_timeout` ms for text to appear
- Plugin → AutoInput → UI Query → `%param_text`
- → Click found element

**Action 9: End If**

**Action 10: Write Result File**
- File → Write File
- File: `~/.autonobot/tasker_result.json`
- Content: `{"success": true, "action": "%action"}`

### Step 5: Test It

In Termux:
```bash
# Test a tap at center of screen
echo '{"action":"tap","params":{"x":540,"y":1200},"timestamp":0}' > ~/.autonobot/tasker_cmd.json

# Test typing
echo '{"action":"type","params":{"text":"hello world"},"timestamp":0}' > ~/.autonobot/tasker_cmd.json

# Test clicking text on screen
echo '{"action":"click_text","params":{"text":"Send"},"timestamp":0}' > ~/.autonobot/tasker_cmd.json
```

If Tasker picks it up and AutoInput executes → you're done.

## How AutoNoBot Uses It

When you tell the bot "open gmail and send john an email":

```
1. send_email("john@gmail.com", "Subject", "Body")
   → SMTP sends directly (no screen needed) ✅
   
OR if SMTP not configured:

1. open_app("gmail")                          → intent opens Gmail
2. screen_wait_and_click("Compose")           → AutoInput clicks Compose
3. screen_type("john@gmail.com")              → AutoInput types address
4. screen_click_text("Subject")               → AutoInput clicks Subject field
5. screen_type("Subject text")                → AutoInput types subject
6. screen_click_text("Compose email")         → AutoInput clicks body field
7. screen_type("Body text")                   → AutoInput types body
8. screen_click_text("Send")                  → AutoInput taps Send ✅
```

The system is smart enough to try SMTP first (zero screen interaction needed). It only falls back to screen automation if SMTP isn't configured.

## Troubleshooting

**AutoInput not clicking:**
- Settings → Accessibility → AutoInput → make sure it's ON
- Some Samsung One UI versions need: Settings → Apps → AutoInput → Appear on top → Allow

**Tasker not detecting file changes:**
- Make sure Tasker has storage permission
- Check the file path matches exactly
- Try: Tasker → Preferences → Monitor → File Check Interval → 1 second

**"Restricted setting" when enabling accessibility:**
- Settings → Apps → AutoInput → ⋮ → Allow restricted settings
- This is a Samsung-specific security dialog, just approve it once

**Tasker killed in background:**
- Settings → Battery → Unrestricted for Tasker, AutoInput, and Termux
- Settings → Apps → Tasker → Battery → Unrestricted
- Disable battery optimization for all three apps

## Security Note

AutoInput has full screen access. The bot only accepts commands from your Telegram user ID (set in `telegram_config.json`). Nobody else can control your phone.
