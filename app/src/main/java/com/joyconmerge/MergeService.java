package com.joyconmerge;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;
import androidx.core.app.NotificationCompat;
import java.io.DataOutputStream;
import java.io.IOException;

public class MergeService extends Service {

    static { System.loadLibrary("joyconmerge"); }

    public static final String ACTION_START = "com.joyconmerge.START";
    public static final String ACTION_STOP  = "com.joyconmerge.STOP";
    private static final String CHANNEL_ID  = "joycon_merge";
    private static final int    NOTIF_ID    = 1;

    public interface StatusCallback {
        void onStatus(String msg);
    }

    public class LocalBinder extends Binder {
        MergeService getService() { return MergeService.this; }
    }

    private final IBinder binder = new LocalBinder();
    private StatusCallback callback;
    private boolean merging = false;

    /* JNI */
    public native int  startMerge(String leftPath, String rightPath);
    public native void stopMerge();
    public native void setCallback(StatusCallback cb);
    public native void setConfig(
        int fuzz, int flat,
        int invLX, int invLY, int invRX, int invRY,
        int laxX, int laxY, int raxX, int raxY,
        int cA, int mA, int cB, int mB,
        int cX, int mX, int cY, int mY,
        int cR, int mR, int cZR, int mZR,
        int cPlus, int mPlus, int cR3, int mR3,
        int cL, int mL, int cZL, int mZL,
        int cMinus, int mMinus, int cL3, int mL3,
        int dpUp, int dpDown, int dpLeft, int dpRight
    );
    public native String getFoundDevices();

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        setCallback(msg -> {
            if (callback != null) callback.onStatus(msg);
        });
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_STICKY;
        String action = intent.getAction();
        if (ACTION_START.equals(action)) {
            startForeground(NOTIF_ID, buildNotification("Running — Joy-Cons merged"));
            new Thread(() -> {
                // Use root shell to open up /dev/input and /dev/uinput
                // so our process can then access them directly
                boolean rootOk = grantDevicePermissions();
                if (!rootOk) {
                    notifyStatus("ERROR: Root scan failed — grant root in KernelSU and ensure Joy-Cons are paired");
                    return;
                }
                // Pass root-resolved paths directly into JNI — avoids SELinux opendir block
                int result = startMerge(resolvedLeftPath, resolvedRightPath);
                merging = result == 0;
                if (!merging)
                    notifyStatus("ERROR: startMerge failed — check device paths");
            }).start();
        } else if (ACTION_STOP.equals(action)) {
            stopMerge();
            merging = false;
            stopForeground(true);
            stopSelf();
        }
        return START_STICKY;
    }

    // Paths resolved by root shell scan; passed into JNI directly.
    private String resolvedLeftPath  = "";
    private String resolvedRightPath = "";

    /**
     * Open a root shell to:
     *  1. chmod /dev/input/* and /dev/uinput so the app process can open them.
     *  2. Scan /dev/input/event* for Joy-Con names (root can read them even
     *     when SELinux blocks the app UID from doing opendir).
     *  Results are stored in resolvedLeftPath / resolvedRightPath.
     */
    private boolean grantDevicePermissions() {
        try {
            Process su = Runtime.getRuntime().exec("su");
            DataOutputStream os = new DataOutputStream(su.getOutputStream());
            java.io.BufferedReader br = new java.io.BufferedReader(
                new java.io.InputStreamReader(su.getInputStream()));

            // chmod so app process can open the nodes directly after this
            os.writeBytes("chmod 755 /dev/input\n");
            os.writeBytes("chmod 644 /dev/input/event*\n");
            os.writeBytes("chmod 666 /dev/uinput\n");

            // Scan event nodes for Joy-Con names entirely inside root shell.
            // Output format: "LEFT:/dev/input/eventN" or "RIGHT:/dev/input/eventN"
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

            // Read scan results line-by-line before waitFor
            String line;
            while ((line = br.readLine()) != null) {
                if (line.startsWith("LEFT:"))  resolvedLeftPath  = line.substring(5).trim();
                if (line.startsWith("RIGHT:")) resolvedRightPath = line.substring(6).trim();
                if (line.equals("SCAN_DONE")) break;
            }

            int exit = su.waitFor();
            notifyStatus("Root shell exit code: " + exit);
            if (exit != 0) return false;

            if (resolvedLeftPath.isEmpty())
                notifyStatus("ERROR: Left Joy-Con not found via root scan — is it paired?");
            if (resolvedRightPath.isEmpty())
                notifyStatus("ERROR: Right Joy-Con not found via root scan — is it paired?");

            return !resolvedLeftPath.isEmpty() && !resolvedRightPath.isEmpty();
        } catch (IOException | InterruptedException e) {
            notifyStatus("ERROR: su failed: " + e.getMessage());
            return false;
        }
    }

    private void notifyStatus(String msg) {
        if (callback != null) callback.onStatus(msg);
    }

    public void setStatusCallback(StatusCallback cb) { this.callback = cb; }
    public boolean isMerging() { return merging; }

    @Override
    public IBinder onBind(Intent intent) { return binder; }

    @Override
    public void onDestroy() {
        super.onDestroy();
        stopMerge();
    }

    private void createNotificationChannel() {
        NotificationChannel ch = new NotificationChannel(
            CHANNEL_ID, "Joy-Con Merge", NotificationManager.IMPORTANCE_LOW);
        ch.setDescription("Keeps Joy-Cons merged in background");
        getSystemService(NotificationManager.class).createNotificationChannel(ch);
    }

    private Notification buildNotification(String text) {
        Intent intent = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE);
        Intent stopIntent = new Intent(this, MergeService.class);
        stopIntent.setAction(ACTION_STOP);
        PendingIntent stopPi = PendingIntent.getService(this, 0, stopIntent,
            PendingIntent.FLAG_IMMUTABLE);
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
