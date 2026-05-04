package com.joyconmerge;

import android.content.Context;
import android.os.IBinder;
import android.util.Log;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * UserService — berjalan di dalam proses privileged Shizuku (shell UID = 2000).
 *
 * Dengan UID shell kita bisa:
 *   - Baca /dev/input/event* (group=input, shell adalah anggotanya)
 *   - Buka /dev/uinput (group=uhid / bisa diakses shell di AOSP)
 *   - EVIOCGRAB pada event device
 *
 * Tidak perlu su/root sama sekali.
 *
 * Cara kerja:
 *   1. MainActivity bind ke Shizuku → Shizuku spawn proses ini
 *   2. Service ini menjalankan native binary uinput_setup langsung (tanpa su)
 *   3. Komunikasi binary ↔ service via stdin/stdout pipe
 *   4. Service meneruskan status ke MainActivity via polling (pollStatus)
 */
public class UserService extends IUserService.Stub {

    private static final String TAG = "JoyConUserService";

    private Process mergeProcess = null;
    private DataOutputStream procStdin = null;
    private final LinkedBlockingQueue<String> statusQueue = new LinkedBlockingQueue<>(200);
    private volatile boolean running = false;

    // Context disuntikkan oleh Shizuku saat konstruksi
    public UserService(Context context) {
        // Context di sini adalah context app kita — dibutuhkan untuk getFilesDir()
    }

    // Constructor tanpa arg juga dibutuhkan oleh Shizuku
    public UserService() {}

    // ─────────────────────────────────────────────────────────────────────────
    // scanDevices — baca /sys/class/input tanpa shell script tambahan
    // ─────────────────────────────────────────────────────────────────────────
    @Override
    public String scanDevices() {
        StringBuilder sb = new StringBuilder();
        File inputDir = new File("/dev/input");
        File[] events = inputDir.listFiles();
        if (events == null) return "";

        for (File evFile : events) {
            if (!evFile.getName().startsWith("event")) continue;
            String path = evFile.getAbsolutePath();
            String base = evFile.getName(); // e.g. "event3"
            String namePath = "/sys/class/input/" + base + "/device/name";
            String name = readSysFile(namePath).trim();
            sb.append(path).append("|").append(name).append("\n");
        }
        return sb.toString();
    }

    private String readSysFile(String path) {
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(new FileInputStream(path)))) {
            String line = br.readLine();
            return line != null ? line : "";
        } catch (IOException e) {
            return "";
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // startMerge — jalankan uinput_setup TANPA su
    // args[0] = binary path, args[1..n] = argumen (left path, right path, dll.)
    // ─────────────────────────────────────────────────────────────────────────
    @Override
    public void startMerge(String[] args) {
        if (running) {
            pushStatus("STATUS:Already running");
            return;
        }
        statusQueue.clear();
        try {
            // Pastikan binary executable
            File binary = new File(args[0]);
            if (!binary.canExecute()) {
                binary.setExecutable(true, false);
            }

            ProcessBuilder pb = new ProcessBuilder(args);
            pb.redirectErrorStream(false);
            mergeProcess = pb.start();
            procStdin = new DataOutputStream(mergeProcess.getOutputStream());
            running = true;

            // Thread baca stdout
            Process proc = mergeProcess;
            new Thread(() -> {
                try (BufferedReader br = new BufferedReader(
                        new InputStreamReader(proc.getInputStream()))) {
                    String line;
                    while ((line = br.readLine()) != null) {
                        pushStatus(line);
                    }
                } catch (IOException ignored) {}
                running = false;
                pushStatus("STATUS:STOPPED");
            }, "joycon-stdout").start();

            // Thread baca stderr (log saja)
            new Thread(() -> {
                try (BufferedReader br = new BufferedReader(
                        new InputStreamReader(proc.getErrorStream()))) {
                    String line;
                    while ((line = br.readLine()) != null) {
                        Log.w(TAG, "stderr: " + line);
                        pushStatus("STATUS:err:" + line);
                    }
                } catch (IOException ignored) {}
            }, "joycon-stderr").start();

        } catch (IOException e) {
            running = false;
            pushStatus("ERROR:startMerge: " + e.getMessage());
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // stopMerge
    // ─────────────────────────────────────────────────────────────────────────
    @Override
    public void stopMerge() {
        if (procStdin != null) {
            try {
                procStdin.writeBytes("STOP\n");
                procStdin.flush();
            } catch (IOException ignored) {}
        }
        if (mergeProcess != null) {
            // Beri waktu binary untuk cleanup, lalu paksa kill kalau perlu
            new Thread(() -> {
                try { Thread.sleep(600); } catch (InterruptedException ignored) {}
                if (mergeProcess != null) {
                    mergeProcess.destroy();
                    mergeProcess = null;
                }
                running = false;
            }).start();
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // pollStatus — ambil satu pesan dari queue (non-blocking, return null jika kosong)
    // ─────────────────────────────────────────────────────────────────────────
    @Override
    public String pollStatus() {
        return statusQueue.poll();
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public void destroy() {
        stopMerge();
    }

    @Override
    public IBinder asBinder() {
        return this;
    }

    private void pushStatus(String msg) {
        // offer() tidak block — kalau queue penuh, pesan lama dibuang
        if (!statusQueue.offer(msg)) {
            statusQueue.poll();
            statusQueue.offer(msg);
        }
    }
}
