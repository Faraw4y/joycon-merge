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
    public native int  startMerge();
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
                    notifyStatus("ERROR: Could not get root shell — grant root in KernelSU");
                    return;
                }
                int result = startMerge();
                merging = result == 0;
                if (!merging)
                    notifyStatus("ERROR: startMerge failed after root grant");
            }).start();
        } else if (ACTION_STOP.equals(action)) {
            stopMerge();
            merging = false;
            stopForeground(true);
            stopSelf();
        }
        return START_STICKY;
    }

    /**
     * Open a root shell and chmod /dev/input + /dev/uinput so the
     * app process (which runs as app UID) can read/write them.
     */
    private boolean grantDevicePermissions() {
        try {
            Process su = Runtime.getRuntime().exec("su");
            DataOutputStream os = new DataOutputStream(su.getOutputStream());

            // Make /dev/input directory and all event nodes readable
            os.writeBytes("chmod 755 /dev/input\n");
            os.writeBytes("chmod 644 /dev/input/event*\n");
            // Make uinput writable
            os.writeBytes("chmod 666 /dev/uinput\n");
            os.writeBytes("exit\n");
            os.flush();

            int exit = su.waitFor();
            notifyStatus("Root shell exit code: " + exit);
            return exit == 0;
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
