package com.joyconmerge;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import androidx.core.app.NotificationCompat;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;

/**
 * MergeService v6 — root binary handles everything directly.
 *
 * Architecture:
 *   1. Extract uinput_setup binary from assets
 *   2. Scan Joy-Con paths via root
 *   3. Launch binary as root with all config as args
 *   4. Binary opens /dev/uinput + Joy-Con devices, grabs them exclusively,
 *      runs two threads (left/right reader), writes merged events to uinput
 *   5. Java reads stdout for status ("UINPUT_READY", "STATUS:...", "ERROR:...")
 *   6. To stop: write "STOP\n" to binary's stdin
 *
 * No JNI, no event pipes, no race conditions.
 */
public class MergeService extends Service {

    public static final String ACTION_START = "com.joyconmerge.START";
    public static final String ACTION_STOP  = "com.joyconmerge.STOP";
    private static final String CHANNEL_ID  = "joycon_merge";
    private static final int    NOTIF_ID    = 1;

    public interface StatusCallback {
        void onStatus(String msg);
        void onEvent(String event);
        void onDevices(String left, String right);
    }

    public class LocalBinder extends android.os.Binder {
        MergeService getService() { return MergeService.this; }
    }

    private final IBinder binder = new LocalBinder();
    private StatusCallback callback;
    private boolean merging = false;
    private Process rootProcess = null;
    private DataOutputStream rootStdin = null;

    // Config — set by MainActivity before starting
    private int fuzz=256, flat=4096;
    private int invLX=0, invLY=0, invRX=0, invRY=0;
    private int mapA=0x130,mapB=0x131,mapX=0x133,mapY=0x134;
    private int mapR=0x137,mapZR=0x139,mapPlus=0x13b,mapR3=0x13e;
    private int mapL=0x136,mapZL=0x138,mapMinus=0x13a,mapL3=0x13d;
    private int mapHome=0x13c;
    private int mapCapture=0xa7;

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

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_STICKY;
        String action = intent.getAction();
        if (ACTION_START.equals(action)) {
            startForeground(NOTIF_ID, buildNotification("Running — Joy-Cons merged"));
            new Thread(this::openAndMerge).start();
        } else if (ACTION_STOP.equals(action)) {
            doStop();
        }
        return START_STICKY;
    }

    private void doStop() {
        // Send STOP command to binary's stdin
        if (rootStdin != null) {
            try {
                rootStdin.writeBytes("STOP\n");
                rootStdin.flush();
                Thread.sleep(400);
            } catch (Exception ignored) {}
        }
        killRootProcess();
        merging = false;
        stopForeground(true);
        stopSelf();
        notifyStatus("STOPPED");
    }

    private void killRootProcess() {
        if (rootProcess != null) {
            try {
                rootProcess.getOutputStream().write("exit\n".getBytes());
                rootProcess.getOutputStream().flush();
                Thread.sleep(300);
            } catch (Exception ignored) {}
            rootProcess.destroy();
            rootProcess = null;
            rootStdin = null;
        }
    }

    private File extractBinary() throws IOException {
        String abi = Build.SUPPORTED_ABIS[0];
        if (!abi.equals("arm64-v8a") && !abi.equals("armeabi-v7a"))
            abi = "arm64-v8a";
        String assetName = "uinput_setup_" + abi;
        File outFile = new File(getFilesDir(), "uinput_setup");
        try (InputStream in = getAssets().open(assetName);
             FileOutputStream out = new FileOutputStream(outFile)) {
            byte[] buf = new byte[8192]; int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        }
        outFile.setExecutable(true, false);
        return outFile;
    }

    private String[] scanJoyConPaths() throws IOException, InterruptedException {
        Process proc = Runtime.getRuntime().exec("su");
        DataOutputStream os = new DataOutputStream(proc.getOutputStream());
        BufferedReader br = new BufferedReader(new InputStreamReader(proc.getInputStream()));
        new Thread(() -> {
            try (BufferedReader er = new BufferedReader(
                    new InputStreamReader(proc.getErrorStream()))) {
                while (er.readLine() != null) {}
            } catch (IOException ignored) {}
        }).start();

        os.writeBytes(
            "for f in /dev/input/event*; do\n" +
            "  name=$(cat /sys/class/input/$(basename $f)/device/name 2>/dev/null)\n" +
            "  case \"$name\" in\n" +
            "    *'Left Joy-Con'*)  echo \"LEFT:$f\" ;;\n" +
            "    *'Right Joy-Con'*) echo \"RIGHT:$f\" ;;\n" +
            "  esac\n" +
            "done\n" +
            "echo SCAN_DONE\n" +
            "exit\n");
        os.flush();

        String left="", right="", line;
        while ((line = br.readLine()) != null) {
            if (line.startsWith("LEFT:"))  left  = line.substring(5).trim();
            if (line.startsWith("RIGHT:")) right = line.substring(6).trim();
            if (line.equals("SCAN_DONE")) break;
        }
        proc.waitFor();
        return new String[]{left, right};
    }

    private void openAndMerge() {
        File binary;
        try {
            binary = extractBinary();
            notifyStatus("Binary extracted");
        } catch (IOException e) {
            notifyStatus("ERROR: extract binary: " + e.getMessage()); return;
        }

        String leftPath, rightPath;
        try {
            String[] paths = scanJoyConPaths();
            leftPath = paths[0]; rightPath = paths[1];
        } catch (Exception e) {
            notifyStatus("ERROR: scan failed: " + e.getMessage()); return;
        }

        if (leftPath.isEmpty())  { notifyStatus("ERROR: Left Joy-Con not found");  return; }
        if (rightPath.isEmpty()) { notifyStatus("ERROR: Right Joy-Con not found"); return; }
        notifyStatus("Found: L=" + leftPath + " R=" + rightPath);
        if (callback != null) callback.onDevices(leftPath, rightPath);

        // Build command: binary path + all config as args
        String cmd = binary.getAbsolutePath()
            + " " + leftPath
            + " " + rightPath
            + " " + fuzz
            + " " + flat
            + " " + invLX
            + " " + invLY
            + " " + invRX
            + " " + invRY
            + " " + mapA
            + " " + mapB
            + " " + mapX
            + " " + mapY
            + " " + mapR
            + " " + mapZR
            + " " + mapPlus
            + " " + mapR3
            + " " + mapL
            + " " + mapZL
            + " " + mapMinus
            + " " + mapL3
            + " " + mapHome
            + " " + mapCapture
            + "\n";

        try {
            Process proc = Runtime.getRuntime().exec("su");
            rootProcess = proc;
            rootStdin   = new DataOutputStream(proc.getOutputStream());
            BufferedReader br = new BufferedReader(
                new InputStreamReader(proc.getInputStream()));

            // Drain stderr
            new Thread(() -> {
                try (BufferedReader er = new BufferedReader(
                        new InputStreamReader(proc.getErrorStream()))) {
                    String l;
                    while ((l = er.readLine()) != null)
                        notifyStatus("root: " + l);
                } catch (IOException ignored) {}
            }).start();

            // Send command to root shell
            rootStdin.writeBytes(cmd);
            rootStdin.flush();

            // Read status lines
            boolean ready = false;
            String line;
            while ((line = br.readLine()) != null) {
                if (line.startsWith("STATUS:")) {
                    notifyStatus(line.substring(7));
                } else if (line.equals("UINPUT_READY")) {
                    ready = true;
                    merging = true;
                    notifyStatus("RUNNING");
                } else if (line.startsWith("EVENT:")) {
                    notifyEvent(line.substring(6));
                } else if (line.startsWith("ERROR:")) {
                    notifyStatus(line);
                    break;
                }
                if (!ready && line.startsWith("ERROR:")) break;
            }

            if (!ready) {
                notifyStatus("ERROR: binary failed to start");
                killRootProcess();
            }

        } catch (IOException e) {
            notifyStatus("ERROR: su failed: " + e.getMessage());
        }
    }

    private void notifyStatus(String msg) {
        if (callback != null) callback.onStatus(msg);
    }

    private void notifyEvent(String event) {
        if (callback != null) callback.onEvent(event);
    }

    public void setStatusCallback(StatusCallback cb) { this.callback = cb; }
    public boolean isMerging() { return merging; }

    @Override
    public IBinder onBind(Intent intent) { return binder; }

    @Override
    public void onDestroy() {
        super.onDestroy();
        killRootProcess();
    }

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
