# Joy-Con Merge — Shizuku Edition

Versi ini **tidak memerlukan root**. Sebagai gantinya menggunakan [Shizuku](https://shizuku.rikka.app/) untuk mendapatkan akses ke `/dev/input/event*` dan `/dev/uinput` dengan privilege shell (UID 2000), tanpa men-trigger deteksi root oleh app lain (seperti banking apps, game anti-cheat, dll.).

## Perbandingan dengan versi root

| Fitur | Versi Root | Versi Shizuku |
|---|---|---|
| Akses `/dev/uinput` | ✅ via `su` | ✅ via Shizuku UserService |
| Akses `/dev/input/event*` | ✅ via `su` | ✅ via Shizuku (shell group=input) |
| Terdeteksi root | ❌ Ya (SafetyNet/Play Integrity) | ✅ Tidak |
| Perlu ADB satu kali | ✅ Tidak | ⚠ Ya (untuk start Shizuku via ADB) |
| App banking tetap jalan | ❌ Tidak selalu | ✅ Ya |

## Cara pakai

### 1. Install & aktifkan Shizuku

**Cara termudah — pakai Wireless ADB (Android 11+):**
1. Install Shizuku dari Play Store
2. Di HP: Pengaturan → Opsi Pengembang → Debugging Wireless
3. Buka Shizuku → ikuti instruksi "Start via ADB"
4. Atau via PC: `adb shell sh /sdcard/Android/data/moe.shizuku.privileged.api/files/start.sh`

**Cara lain — via ADB dari PC:**
```bash
adb shell sh /sdcard/Android/data/moe.shizuku.privileged.api/files/start.sh
```

### 2. Beri izin ke Joy-Con Merge

1. Buka app Joy-Con Merge
2. Kalau banner merah muncul → tap "Izinkan Shizuku"
3. Konfirmasi di dialog Shizuku

### 3. Sambungkan Joy-Con & mulai merge

1. Sambungkan Left dan Right Joy-Con via Bluetooth
2. Tap **Start** (auto-scan akan mendeteksi Joy-Con)
3. Atau aktifkan **Manual Override** → Scan → pilih device → Start

## Kenapa Shizuku bisa akses `/dev/uinput`?

Shell (UID 2000) adalah anggota group `uhid` di AOSP. Di kebanyakan device:
- `/dev/uinput` → `crw-rw----  root  uhid` → shell bisa buka
- `/dev/input/event*` → `crw-rw----  root  input` → shell adalah anggota group `input`

Jadi semua operasi yang sebelumnya butuh `su`, sekarang cukup dengan shell privilege dari Shizuku.

## Struktur proyek

```
app/src/main/
├── aidl/com/joyconmerge/
│   └── IUserService.aidl       ← Interface AIDL untuk komunikasi dengan Shizuku
├── java/com/joyconmerge/
│   ├── UserService.java         ← Berjalan di proses privileged Shizuku (shell UID)
│   ├── MergeService.java        ← Android Service biasa; komunikasi via IUserService
│   ├── MainActivity.java        ← UI + Shizuku permission check
│   ├── Config.java              ← SharedPreferences wrapper (tidak berubah)
│   ├── GamepadView.java         ← Custom view visualisasi gamepad (tidak berubah)
│   └── BootReceiver.java        ← Auto-start on boot (tidak berubah)
└── jni/
    └── uinput_setup.c           ← Binary C yang sama (tidak berubah)
```

## Build

```bash
./gradlew assembleRelease
```

Binary NDK (`uinput_setup`) akan otomatis di-copy ke `assets/` saat build.

## Dependency tambahan

```gradle
implementation 'dev.rikka.shizuku:api:13.1.5'
implementation 'dev.rikka.shizuku:provider:13.1.5'
```

## Catatan

- Shizuku harus di-restart setiap kali HP reboot (kecuali pakai Magisk module Shizuku, tapi itu butuh root 😅)
- Untuk menghindari restart manual, gunakan fitur "Wireless ADB" di Android 11+ yang lebih persisten
- Binary `uinput_setup.c` tidak diubah sama sekali — hanya cara memanggil (via Shizuku bukan via su) yang berubah
