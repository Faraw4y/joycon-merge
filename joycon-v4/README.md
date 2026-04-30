# Joy-Con Merge APK

Merges Left + Right Joy-Con into one virtual gamepad on Android (root required).

## Features
- Auto-detects Joy-Con by name (no hardcoded event numbers)
- Auto-starts on boot
- Button remapping
- Stick calibration (deadzone + fuzz + axis inversion)
- Live event log

## How to build via GitHub (no PC needed)

### Step 1 — Create GitHub account
Go to https://github.com and sign up (free).

### Step 2 — Create a new repository
1. Click the "+" icon top right → "New repository"
2. Name it: `joycon-merge`
3. Set to **Public**
4. Click "Create repository"

### Step 3 — Upload all files
1. Click "uploading an existing file" link on the empty repo page
2. Drag ALL files and folders from this project into the upload area
   - Make sure to keep the folder structure intact
3. Click "Commit changes"

### Step 4 — Wait for build
1. Click the "Actions" tab in your repository
2. You will see "Build APK" workflow running (takes ~5 minutes)
3. Wait for the green checkmark

### Step 5 — Download APK
1. Click on the completed workflow run
2. Scroll down to "Artifacts"
3. Click "JoyConMerge-APK" to download
4. Unzip — you get `app-release.apk`

### Step 6 — Install on phone
1. Transfer APK to your phone (WhatsApp, Telegram, USB, etc.)
2. Open it — Android will ask to allow unknown sources → Allow
3. Install
4. Open app → KernelSU will ask for root → Grant
5. Done!

## Requirements
- Android 8.0+
- KernelSU or Magisk (root)
- Both Joy-Cons connected via Bluetooth before starting
