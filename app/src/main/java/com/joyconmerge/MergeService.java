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
    private Process pipingProcess = null;

    /* JNI — three fds: left input, right input, uinput output */
    public native int  startMergeWithFds(int leftFd, int rightFd, int uinputFd);
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

    // Legacy — not used
    public native int startMerge(String leftPath, String rightPath);

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
            if (pipingProcess != null) { pipingProcess.destroy(); pipingProcess = null; }
            merging = false;
            stopForeground(true);
            stopSelf();
        }
        return START_STICKY;
    }

    /**
     * THE FIX (v2):
     *
     * Both /dev/input/event* AND /dev/uinput are blocked by SELinux for app UIDs,
     * even after chmod. The solution for all three devices is the same:
     *
     *   - Input devices (read): root does `cat /dev/input/eventN > pipe_write_end`
     *     We read events from pipe_read_end — identical binary format.
     *
     *   - /dev/uinput (write): root does `cat pipe_read_end > /dev/uinput`
     *     JNI writes uinput commands to pipe_write_end — forwarded to uinput by root.
     *
     * Root does all the open() calls. We never touch /dev/* directly.
     */
    private int openAndMerge() {
        try {
            // --- Step 1: Scan for Joy-Con device paths ---
            Process scanProc = Runtime.getRuntime().exec("su");
            DataOutputStream scanOs = new DataOutputStream(scanProc.getOutputStream());
            BufferedReader scanBr = new BufferedReader(new InputStreamReader(scanProc.getInputStream()));
            BufferedReader scanEr = new BufferedReader(new InputStreamReader(scanProc.getErrorStream()));
            new Thread(() -> { try { while (scanEr.readLine() != null) {} } catch (IOException e) {} }).start();

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

            // --- Step 2: Create pipes for all three devices ---
            //
            // leftPipe  [0]=read(JNI),  [1]=write(root cat)   — Left Joy-Con events
            // rightPipe [0]=read(JNI),  [1]=write(root cat)   — Right Joy-Con events
            // uinputPipe[0]=read(root), [1]=write(JNI)        — uinput commands
            //
            ParcelFileDescriptor[] leftPipe   = ParcelFileDescriptor.createPipe();
            ParcelFileDescriptor[] rightPipe  = ParcelFileDescriptor.createPipe();
            ParcelFileDescriptor[] uinputPipe = ParcelFileDescriptor.createPipe();

            int myPid        = android.os.Process.myPid();
            int leftWriteFd  = leftPipe[1].getFd();
            int rightWriteFd = rightPipe[1].getFd();
            int uinputReadFd = uinputPipe[0].getFd();

            // --- Step 3: Single root shell sets up all three pipes ---
            Process pipeProc = Runtime.getRuntime().exec("su");
            pipingProcess = pipeProc;
            DataOutputStream pipeOs = new DataOutputStream(pipeProc.getOutputStream());
            BufferedReader pipeBr = new BufferedReader(new InputStreamReader(pipeProc.getInputStream()));
            BufferedReader pipeEr = new BufferedReader(new InputStreamReader(pipeProc.getErrorStream()));
            new Thread(() -> { try { while (pipeEr.readLine() != null) {} } catch (IOException e) {} }).start();

            // Root streams Left Joy-Con events into our leftPipe read end
            pipeOs.writeBytes("cat " + resolvedLeftPath
                + " > /proc/" + myPid + "/fd/" + leftWriteFd + " &\n");
            // Root streams Right Joy-Con events into our rightPipe read end
            pipeOs.writeBytes("cat " + resolvedRightPath
                + " > /proc/" + myPid + "/fd/" + rightWriteFd + " &\n");
            // Root forwards our JNI uinput writes → /dev/uinput
            pipeOs.writeBytes("cat /proc/" + myPid + "/fd/" + uinputReadFd
                + " > /dev/uinput &\n");
            pipeOs.writeBytes("echo PIPES_READY\n");
            pipeOs.flush();

            while ((line = pipeBr.readLine()) != null) {
                if (line.equals("PIPES_READY")) break;
            }

            // Close the ends that root's cat processes now own
            leftPipe[1].close();
            rightPipe[1].close();
            uinputPipe[0].close();

            // --- Step 4: Hand all three fds to JNI ---
            // JNI reads events from leftPipe[0] and rightPipe[0]
            // JNI writes uinput commands to uinputPipe[1] (root forwards to /dev/uinput)
            int result = startMergeWithFds(
                leftPipe[0].getFd(),
                rightPipe[0].getFd(),
                uinputPipe[1].getFd()
            );
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
        PendingIntent pi = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE);
        Intent stopIntent = new Intent(this, MergeService.class);
        stopIntent.setAction(ACTION_STOP);
        PendingIntent stopPi = PendingIntent.getService(this, 0, stopIntent, PendingIntent.FLAG_IMMUTABLE);
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
