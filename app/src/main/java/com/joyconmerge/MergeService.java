package com.joyconmerge;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import androidx.core.app.NotificationCompat;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import rikka.shizuku.Shizuku;
import rikka.shizuku.ShizukuBinderWrapper;
import rikka.shizuku.SystemServiceHelper;

/**
 * MergeService versi Shizuku — tidak memakai su/root.
 *
 * Semua operasi privileged (buka /dev/input/event*, /dev/uinput, EVIOCGRAB)
 * dilakukan oleh UserService yang berjalan di dalam proses privileged Shizuku
 * (shell UID = 2000).
 *
 * Alur:
 *   1. bind UserService ke Shizuku
 *   2. panggil userService.startMerge(args) — binary berjalan tanpa root
 *   3. poll status dari UserService via Handler setiap 100 ms
 */
public class MergeService extends Service {

    public static final String ACTION_START        = "com.joyconmerge.START";
    public static final String ACTION_START_MANUAL = "com.joyconmerge.START_MANUAL";
    public static final String ACTION_STOP         = "com.joyconmerge.STOP";
    public static final String EXTRA_LEFT_PATH     = "left_path";
    public static final String EXTRA_RIGHT_PATH    = "right_path";

    private static final String CHANNEL_ID = "joycon_merge";
    private static final int    NOTIF_ID   = 1;
    private static final int    POLL_MS    = 100;

    public interface StatusCallback {
        void onStatus(String msg);
        void onEvent(String event);
        void onDevices(String left, String right);
        void onScanResult(List<String> allPaths, String autoLeft, String autoRight);
    }

    public class LocalBinder extends android.os.Binder {
        MergeService getService() { return MergeService.this; }
    }

    private final android.os.IBinder binder = new LocalBinder();
    private StatusCallback callback;
    private boolean merging = false;

    // Config
    private int fuzz=256, flat=4096;
    private int invLX=0, invLY=0, invRX=0, invRY=0;
    private int mapA=0x130,mapB=0x131,mapX=0x133,mapY=0x134;
    private int mapR=0x137,mapZR=0x139,mapPlus=0x13b,mapR3=0x13e;
    private int mapL=0x136,mapZL=0x138,mapMinus=0x13a,mapL3=0x13d;
    private int mapHome=0x13c, mapCapture=0xa7;

    // Shizuku UserService
    private IUserService userService = null;

    // Handler untuk polling status dari UserService
    private final Handler pollHandler = new Handler(Looper.getMainLooper());
    private final Runnable pollRunnable = this::doPoll;

    // ─── Shizuku UserService binding ────────────────────────────────────────

    private final Shizuku.UserServiceArgs userServiceArgs =
        new Shizuku.UserServiceArgs(
            new android.content.ComponentName(
                BuildConfig.APPLICATION_ID,
                UserService.class.getName()))
        .daemon(false)
        .processNameSuffix("user_service")
        .debuggable(false)
        .version(BuildConfig.VERSION_CODE);

    private final ServiceConnection userServiceConn =
        new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName name,
                                           android.os.IBinder service) {
                userService = IUserService.Stub.asInterface(service);
                notifyStatus("Shizuku service connected");
            }
            @Override
            public void onServiceDisconnected(ComponentName name) {
                userService = null;
                if (merging) {
                    merging = false;
                    notifyStatus("STOPPED");
                }
            }
        };

    // ─── Lifecycle ───────────────────────────────────────────────────────────

    // ─── Shizuku binder listeners ──────────────────────────────────────────────

    private final Shizuku.OnBinderReceivedListener binderReceivedListener = () -> {
        if (Shizuku.pingBinder()) {
            Shizuku.bindUserService(userServiceArgs, userServiceConn);
        }
    };

    private final Shizuku.OnBinderDeadListener binderDeadListener = () -> {
        userService = null;
        if (merging) {
            merging = false;
            notifyStatus("STOPPED");
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        Shizuku.addBinderReceivedListenerSticky(binderReceivedListener);
        Shizuku.addBinderDeadListener(binderDeadListener);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_STICKY;
        String action = intent.getAction();
        if (ACTION_START.equals(action)) {
            startForeground(NOTIF_ID, buildNotification("Running — Joy-Cons merged"));
            new Thread(this::openAndMerge).start();
        } else if (ACTION_START_MANUAL.equals(action)) {
            String lp = intent.getStringExtra(EXTRA_LEFT_PATH);
            String rp = intent.getStringExtra(EXTRA_RIGHT_PATH);
            startForeground(NOTIF_ID, buildNotification("Running — Joy-Cons merged (manual)"));
            new Thread(() -> openAndMergeWithPaths(lp, rp)).start();
        } else if (ACTION_STOP.equals(action)) {
            doStop();
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        doStop();
        Shizuku.removeBinderReceivedListener(binderReceivedListener);
        Shizuku.removeBinderDeadListener(binderDeadListener);
        Shizuku.unbindUserService(userServiceArgs, userServiceConn, true);
    }

    @Override
    public android.os.IBinder onBind(Intent intent) { return binder; }

    // ─── Config setters ──────────────────────────────────────────────────────

    public void setConfig(int fuzz, int flat,
            int invLX, int invLY, int invRX, int invRY,
            int mapA, int mapB, int mapX, int mapY,
            int mapR, int mapZR, int mapPlus, int mapR3,
            int mapL, int mapZL, int mapMinus, int mapL3,
            int mapHome, int mapCapture) {
        this.fuzz=fuzz; this.flat=flat;
        this.invLX=invLX; this.invLY=invLY; this.invRX=invRX; this.invRY=invRY;
        this.mapA=mapA; this.mapB=mapB; this.mapX=mapX; this.mapY=mapY;
        this.mapR=mapR; this.mapZR=mapZR; this.mapPlus=mapPlus; this.mapR3=mapR3;
        this.mapL=mapL; this.mapZL=mapZL; this.mapMinus=mapMinus; this.mapL3=mapL3;
        this.mapHome=mapHome; this.mapCapture=mapCapture;
    }

    // ─── Stop ────────────────────────────────────────────────────────────────

    private void doStop() {
        pollHandler.removeCallbacks(pollRunnable);
        if (userService != null) {
            try { userService.stopMerge(); } catch (RemoteException ignored) {}
        }
        merging = false;
        stopForeground(true);
        stopSelf();
        notifyStatus("STOPPED");
    }

    // ─── Scan ────────────────────────────────────────────────────────────────

    public void scanJoyConPathsAsync() {
        new Thread(() -> {
            if (userService == null) {
                notifyStatus("ERROR: Shizuku not ready");
                return;
            }
            try {
                String raw = userService.scanDevices();
                ScanResult result = parseScanResult(raw);
                if (callback != null) {
                    callback.onScanResult(result.allPaths, result.autoLeft, result.autoRight);
                }
            } catch (RemoteException e) {
                notifyStatus("ERROR: scan failed: " + e.getMessage());
            }
        }).start();
    }

    private static class ScanResult {
        String autoLeft = "", autoRight = "";
        List<String> allPaths = new ArrayList<>();
    }

    private ScanResult parseScanResult(String raw) {
        ScanResult result = new ScanResult();
        if (raw == null || raw.isEmpty()) return result;
        for (String line : raw.split("\n")) {
            String[] parts = line.split("\\|", 2);
            if (parts.length < 1) continue;
            String path = parts[0].trim();
            String name = parts.length > 1 ? parts[1].trim() : "";
            if (path.isEmpty()) continue;
            if (!result.allPaths.contains(path)) result.allPaths.add(path);
            String lname = name.toLowerCase();
            if (lname.contains("left joy-con") && result.autoLeft.isEmpty())
                result.autoLeft = path;
            else if (lname.contains("right joy-con") && result.autoRight.isEmpty())
                result.autoRight = path;
        }
        return result;
    }

    // ─── Open & merge ────────────────────────────────────────────────────────

    private void openAndMergeWithPaths(String leftPath, String rightPath) {
        File binary;
        try { binary = extractBinary(); }
        catch (IOException e) { notifyStatus("ERROR: extract binary: " + e.getMessage()); return; }
        if (leftPath == null || leftPath.isEmpty())  { notifyStatus("ERROR: Left path not set");  return; }
        if (rightPath == null || rightPath.isEmpty()) { notifyStatus("ERROR: Right path not set"); return; }
        notifyStatus("Manual: L=" + leftPath + " R=" + rightPath);
        if (callback != null) callback.onDevices(leftPath, rightPath);
        launchBinary(binary, leftPath, rightPath);
    }

    private void openAndMerge() {
        File binary;
        try { binary = extractBinary(); }
        catch (IOException e) { notifyStatus("ERROR: extract binary: " + e.getMessage()); return; }

        // Auto-scan via Shizuku UserService
        if (userService == null) {
            notifyStatus("ERROR: Shizuku service not ready — coba lagi");
            return;
        }

        ScanResult result;
        try {
            String raw = userService.scanDevices();
            result = parseScanResult(raw);
            if (callback != null) {
                final ScanResult fr = result;
                callback.onScanResult(fr.allPaths, fr.autoLeft, fr.autoRight);
            }
        } catch (RemoteException e) {
            notifyStatus("ERROR: scan failed: " + e.getMessage());
            return;
        }

        if (result.autoLeft.isEmpty())  { notifyStatus("ERROR: Left Joy-Con not found — gunakan Manual Override"); return; }
        if (result.autoRight.isEmpty()) { notifyStatus("ERROR: Right Joy-Con not found"); return; }

        notifyStatus("Found: L=" + result.autoLeft + " R=" + result.autoRight);
        if (callback != null) callback.onDevices(result.autoLeft, result.autoRight);
        launchBinary(binary, result.autoLeft, result.autoRight);
    }

    private void launchBinary(File binary, String leftPath, String rightPath) {
        if (userService == null) {
            notifyStatus("ERROR: Shizuku service not connected");
            return;
        }

        String[] args = new String[]{
            binary.getAbsolutePath(),
            leftPath, rightPath,
            String.valueOf(fuzz), String.valueOf(flat),
            String.valueOf(invLX), String.valueOf(invLY),
            String.valueOf(invRX), String.valueOf(invRY),
            String.valueOf(mapA),  String.valueOf(mapB),
            String.valueOf(mapX),  String.valueOf(mapY),
            String.valueOf(mapR),  String.valueOf(mapZR),
            String.valueOf(mapPlus), String.valueOf(mapR3),
            String.valueOf(mapL),  String.valueOf(mapZL),
            String.valueOf(mapMinus), String.valueOf(mapL3),
            String.valueOf(mapHome), String.valueOf(mapCapture)
        };

        try {
            userService.startMerge(args);
            merging = true;
            // Mulai poll status
            pollHandler.postDelayed(pollRunnable, POLL_MS);
        } catch (RemoteException e) {
            notifyStatus("ERROR: launchBinary: " + e.getMessage());
        }
    }

    // ─── Status polling ──────────────────────────────────────────────────────

    private void doPoll() {
        if (!merging || userService == null) return;
        try {
            String msg;
            while ((msg = userService.pollStatus()) != null) {
                handleBinaryOutput(msg);
            }
        } catch (RemoteException e) {
            notifyStatus("ERROR: poll: " + e.getMessage());
        }
        if (merging) pollHandler.postDelayed(pollRunnable, POLL_MS);
    }

    private void handleBinaryOutput(String line) {
        if (line.startsWith("STATUS:")) {
            String s = line.substring(7);
            notifyStatus(s);
            if ("STOPPED".equals(s)) { merging = false; pollHandler.removeCallbacks(pollRunnable); }
        } else if (line.equals("UINPUT_READY")) {
            notifyStatus("RUNNING");
        } else if (line.startsWith("EVENT:")) {
            notifyEvent(line.substring(6));
        } else if (line.startsWith("ERROR:")) {
            notifyStatus(line);
            merging = false;
            pollHandler.removeCallbacks(pollRunnable);
        }
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private File extractBinary() throws IOException {
        String abi = Build.SUPPORTED_ABIS[0];
        if (!abi.equals("arm64-v8a") && !abi.equals("armeabi-v7a")) abi = "arm64-v8a";
        String assetName = "uinput_setup_" + abi;
        File outFile = new File(getFilesDir(), "uinput_setup");
        File tmpFile = new File(getFilesDir(), "uinput_setup.tmp");
        try (InputStream in = getAssets().open(assetName);
             FileOutputStream out = new FileOutputStream(tmpFile)) {
            byte[] buf = new byte[8192]; int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        }
        tmpFile.setExecutable(true, false);
        if (!tmpFile.renameTo(outFile)) { outFile.delete(); tmpFile.renameTo(outFile); }
        outFile.setExecutable(true, false);
        return outFile;
    }

    private void notifyStatus(String msg) {
        if (callback != null) callback.onStatus(msg);
    }
    private void notifyEvent(String event) {
        if (callback != null) callback.onEvent(event);
    }
    public void setStatusCallback(StatusCallback cb) { this.callback = cb; }
    public boolean isMerging() { return merging; }
    public boolean isUserServiceReady() { return userService != null; }

    private void createNotificationChannel() {
        NotificationChannel ch = new NotificationChannel(
            CHANNEL_ID, "Joy-Con Merge", NotificationManager.IMPORTANCE_LOW);
        getSystemService(NotificationManager.class).createNotificationChannel(ch);
    }

    private Notification buildNotification(String text) {
        Intent intent = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(
            this, 0, intent, PendingIntent.FLAG_IMMUTABLE);
        Intent stopIntent = new Intent(this, MergeService.class);
        stopIntent.setAction(ACTION_STOP);
        PendingIntent stopPi = PendingIntent.getService(
            this, 0, stopIntent, PendingIntent.FLAG_IMMUTABLE);
        return new NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Joy-Con Merge")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(pi)
            .addAction(android.R.drawable.ic_media_pause, "Stop", stopPi)
            .setOngoing(true)
            .build();
    }
}
