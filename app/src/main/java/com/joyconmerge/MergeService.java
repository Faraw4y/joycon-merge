package com.joyconmerge;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import androidx.core.app.NotificationCompat;
import java.io.DataOutputStream;
import java.io.BufferedReader;
import java.io.InputStreamReader;
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
    private Process pipingProcess = null; // root shell piping events to us

    /* JNI */
    public native int  startMergeWithFds(int leftFd, int rightFd);
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

    // Kept for display purposes
    private String resolvedLeftPath  = "";
    private String resolvedRightPath = "";

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
                int result = openAndMerge();
                merging = result == 0;
                if (!merging)
                    notifyStatus("ERROR: startMerge failed — check device paths");
            }).start();
        } else if (ACTION_STOP.equals(action)) {
            stopMerge();
            if (pipingProcess != null) {
                pipingProcess.destroy();
                pipingProcess = null;
            }
            merging = false;
            stopForeground(true);
            stopSelf();
        }
        return START_STICKY;
    }

    /**
     * THE FIX:
     *
     * The old code did: chmod /dev/input/event* → then app opens it directly.
     * This fails because SELinux on KernelSU/Magisk blocks untrusted app UIDs
     * from opening /dev/input/event* regardless of DAC (chmod) permissions.
     *
     * The fix: open the event devices INSIDE root shell using `cat` piped into
     * a ParcelFileDescriptor pipe. Root does the open() → SELinux allows it.
     * We receive events through the read end of the pipe → no SELinux check on us.
     */
    private int openAndMerge() {
        try {
            // --- Step 1: Scan for Joy-Con device paths using root ---
            Process scanProc = Runtime.getRuntime().exec("su");
            DataOutputStream scanOs = new DataOutputStream(scanProc.getOutputStream());
            BufferedReader scanBr = new BufferedReader(
                new InputStreamReader(scanProc.getInputStream()));
            BufferedReader scanEr = new BufferedReader(
                new InputStreamReader(scanProc.getErrorStream()));

            // Drain stderr in background to prevent blocking
            new Thread(() -> {
                try { while (scanEr.readLine() != null) {} } catch (IOException ignored) {}
            }).start();

            scanOs.writeBytes(
                "for f in /dev/input/event*; do\n" +
                "  name=$(cat /sys/class/input/$(basename $f)/device/name 2>/dev/null)\n" +
                "  case \"$name\" in\n" +
                "    *'Left Joy-Con'*)  echo \"LEFT:$f\" ;;\n" +
                "    *'Right Joy-Con'*) echo \"RIGHT:$f\" ;;\n" +
                "  esac\n" +
                "done\n" +
                "echo SCAN_DONE\n" +
                "exit\n");
            scanOs.flush();

            String line;
            while ((line = scanBr.readLine()) != null) {
                if (line.startsWith("LEFT:"))  resolvedLeftPath  = line.substring(5).trim();
                if (line.startsWith("RIGHT:")) resolvedRightPath = line.substring(6).trim();
                if (line.equals("SCAN_DONE")) break;
            }
            int exitCode = scanProc.waitFor();
            notifyStatus("Root shell exit code: " + exitCode);

            if (resolvedLeftPath.isEmpty()) {
                notifyStatus("ERROR: Left Joy-Con not found — is it paired and connected?");
                return -1;
            }
            if (resolvedRightPath.isEmpty()) {
                notifyStatus("ERROR: Right Joy-Con not found — is it paired and connected?");
                return -1;
            }
            notifyStatus("Using: L=" + resolvedLeftPath + "  R=" + resolvedRightPath);

            // --- Step 2: Create pipes; have root cat device data into write ends ---
            // pipe[0] = read end (our JNI reads events from here)
            // pipe[1] = write end (root's `cat /dev/input/eventN` writes here)
            ParcelFileDescriptor[] leftPipe  = ParcelFileDescriptor.createPipe();
            ParcelFileDescriptor[] rightPipe = ParcelFileDescriptor.createPipe();

            int myPid        = android.os.Process.myPid();
            int leftWriteFd  = leftPipe[1].getFd();
            int rightWriteFd = rightPipe[1].getFd();

            // Single root shell: chmod uinput + start both cat pipes in background
            Process pipeProc = Runtime.getRuntime().exec("su");
            pipingProcess = pipeProc;
            DataOutputStream pipeOs = new DataOutputStream(pipeProc.getOutputStream());
            BufferedReader pipeBr = new BufferedReader(
                new InputStreamReader(pipeProc.getInputStream()));
            BufferedReader pipeEr = new BufferedReader(
                new InputStreamReader(pipeProc.getErrorStream()));

            new Thread(() -> {
                try { while (pipeEr.readLine() != null) {} } catch (IOException ignored) {}
            }).start();

            pipeOs.writeBytes("chmod 666 /dev/uinput\n");
            // Write event stream into our pipe write-ends via /proc/<pid>/fd/<fd>
            pipeOs.writeBytes("cat " + resolvedLeftPath
                + " > /proc/" + myPid + "/fd/" + leftWriteFd + " &\n");
            pipeOs.writeBytes("cat " + resolvedRightPath
                + " > /proc/" + myPid + "/fd/" + rightWriteFd + " &\n");
            pipeOs.writeBytes("echo PIPES_READY\n");
            pipeOs.flush();

            // Wait until pipes are set up
            while ((line = pipeBr.readLine()) != null) {
                if (line.equals("PIPES_READY")) break;
            }

            // Close write ends in OUR process — only root's cat processes hold them now.
            // When root closes them (on stopMerge), JNI read() will get EOF cleanly.
            leftPipe[1].close();
            rightPipe[1].close();

            // --- Step 3: Pass read-end fds to JNI ---
            // JNI now reads input_event structs from these pipe fds just like it
            // would from /dev/input/eventN directly — same binary format.
            int result = startMergeWithFds(leftPipe[0].getFd(), rightPipe[0].getFd());

            // Don't close leftPipe[0]/rightPipe[0] — JNI owns them now.
            return result;

        } catch (IOException | InterruptedException e) {
            notifyStatus("ERROR: su failed: " + e.getMessage());
            return -1;
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
        if (pipingProcess != null) { pipingProcess.destroy(); pipingProcess = null; }
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
