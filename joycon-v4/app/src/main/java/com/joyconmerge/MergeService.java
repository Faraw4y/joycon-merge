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
import android.os.ParcelFileDescriptor;
import androidx.core.app.NotificationCompat;

import java.io.DataOutputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;

/**
 * MergeService v4 — fully self-contained, zero external dependencies.
 *
 * ╔══════════════════════════════════════════════════════════════════╗
 * ║  ARSITEKTUR                                                      ║
 * ╠══════════════════════════════════════════════════════════════════╣
 * ║  APK membawa binary C "uinput_setup_<abi>" sebagai asset.       ║
 * ║  Binary ini dikompilasi static (tidak butuh .so di device).     ║
 * ║                                                                  ║
 * ║  Flow:                                                           ║
 * ║  1. Extract binary ke /data/data/<pkg>/files/uinput_setup       ║
 * ║  2. chmod 755                                                    ║
 * ║  3. Root shell jalankan binary dengan args:                     ║
 * ║       uinput_setup <leftPath> <rightPath> <pid>                 ║
 * ║                    <leftWriteFd> <rightWriteFd> <uinputReadFd>  ║
 * ║  4. Binary C:                                                    ║
 * ║     a. open(/dev/uinput) — root bisa                            ║
 * ║     b. Semua ioctl setup di fd asli — tidak ada pipe trick      ║
 * ║     c. write(uinput_user_dev) — di fd asli ✓                   ║
 * ║     d. UI_DEV_CREATE — di fd asli ✓                            ║
 * ║     e. Print "UINPUT_READY"                                     ║
 * ║     f. fork: cat /dev/input/eventL → leftWritePipe             ║
 * ║     g. fork: cat /dev/input/eventR → rightWritePipe            ║
 * ║     h. loop: baca uinputReadPipe → tulis ke uinput_fd          ║
 * ║  5. Java baca "UINPUT_READY", lalu call startMergeWithFds      ║
 * ║  6. JNI baca event dari pipe, map, tulis ke uinputWritePipe    ║
 * ╚══════════════════════════════════════════════════════════════════╝
 */
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
    private Process rootProcess = null;

    /* JNI */
    public native int    startMergeWithFds(int leftFd, int rightFd, int uinputFd);
    public native void   stopMerge();
    public native void   setCallback(StatusCallback cb);
    public native void   setConfig(
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
                int result = openAndMerge();
                merging = (result == 0);
                if (!merging)
                    notifyStatus("ERROR: startMerge failed — check device paths");
            }).start();
        } else if (ACTION_STOP.equals(action)) {
            doStop();
        }
        return START_STICKY;
    }

    private void doStop() {
        stopMerge();
        killRootProcess();
        merging = false;
        stopForeground(true);
        stopSelf();
    }

    private void killRootProcess() {
        if (rootProcess != null) {
            try {
                rootProcess.getOutputStream().close();
            } catch (IOException ignored) {}
            rootProcess.destroy();
            rootProcess = null;
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // STEP 0: Extract binary uinput_setup dari assets ke storage app
    // ─────────────────────────────────────────────────────────────────
    private File extractBinary() throws IOException {
        String abi = Build.SUPPORTED_ABIS[0]; // "arm64-v8a" atau "armeabi-v7a"
        // Normalise: kita hanya bundle dua ABI ini
        if (!abi.equals("arm64-v8a") && !abi.equals("armeabi-v7a")) {
            // Fallback ke arm64 jika ABI tidak dikenal (x86 emulator, dll)
            abi = "arm64-v8a";
        }
        String assetName = "uinput_setup_" + abi;
        File outFile = new File(getFilesDir(), "uinput_setup");

        // Re-extract setiap kali untuk memastikan binary selalu up-to-date
        try (InputStream in  = getAssets().open(assetName);
             FileOutputStream out = new FileOutputStream(outFile)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        }
        outFile.setExecutable(true, false);
        return outFile;
    }

    // ─────────────────────────────────────────────────────────────────
    // STEP 1: Scan Joy-Con paths via root
    // ─────────────────────────────────────────────────────────────────
    private String[] scanJoyConPaths() throws IOException, InterruptedException {
        Process scanProc = Runtime.getRuntime().exec("su");
        DataOutputStream scanOs =
            new DataOutputStream(scanProc.getOutputStream());
        BufferedReader scanBr =
            new BufferedReader(new InputStreamReader(scanProc.getInputStream()));
        drainStderr(scanProc);

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

        String left = "", right = "", line;
        while ((line = scanBr.readLine()) != null) {
            if (line.startsWith("LEFT:"))  left  = line.substring(5).trim();
            if (line.startsWith("RIGHT:")) right = line.substring(6).trim();
            if (line.equals("SCAN_DONE")) break;
        }
        scanProc.waitFor();
        return new String[]{left, right};
    }

    // ─────────────────────────────────────────────────────────────────
    // MAIN: openAndMerge
    // ─────────────────────────────────────────────────────────────────
    private int openAndMerge() {

        // Extract binary
        File binary;
        try {
            binary = extractBinary();
            notifyStatus("Binary extracted: " + binary.getAbsolutePath());
        } catch (IOException e) {
            notifyStatus("ERROR: extract binary: " + e.getMessage());
            return -1;
        }

        // Scan Joy-Con paths
        String leftPath, rightPath;
        try {
            String[] paths = scanJoyConPaths();
            leftPath  = paths[0];
            rightPath = paths[1];
        } catch (IOException | InterruptedException e) {
            notifyStatus("ERROR: scan failed: " + e.getMessage());
            return -1;
        }

        if (leftPath.isEmpty()) {
            notifyStatus("ERROR: Left Joy-Con not found — paired & connected?");
            return -1;
        }
        if (rightPath.isEmpty()) {
            notifyStatus("ERROR: Right Joy-Con not found — paired & connected?");
            return -1;
        }
        notifyStatus("Using: L=" + leftPath + "  R=" + rightPath);

        // Buat 3 pipe
        //   leftPipe   [0]=read(JNI)   [1]=write(binary child)
        //   rightPipe  [0]=read(JNI)   [1]=write(binary child)
        //   uinputPipe [0]=read(binary) [1]=write(JNI)
        ParcelFileDescriptor[] leftPipe, rightPipe, uinputPipe;
        try {
            leftPipe   = ParcelFileDescriptor.createPipe();
            rightPipe  = ParcelFileDescriptor.createPipe();
            uinputPipe = ParcelFileDescriptor.createPipe();
        } catch (IOException e) {
            notifyStatus("ERROR: createPipe: " + e.getMessage());
            return -1;
        }

        int myPid        = android.os.Process.myPid();
        int leftWriteFd  = leftPipe[1].getFd();
        int rightWriteFd = rightPipe[1].getFd();
        int uinputReadFd = uinputPipe[0].getFd();

        // Jalankan binary via root shell
        // Binary akan berjalan sebagai root, membuka /dev/uinput,
        // melakukan setup, lalu print "UINPUT_READY"
        try {
            Process proc = Runtime.getRuntime().exec("su");
            rootProcess = proc;
            DataOutputStream os =
                new DataOutputStream(proc.getOutputStream());
            BufferedReader br =
                new BufferedReader(new InputStreamReader(proc.getInputStream()));

            // Drain stderr ke log
            new Thread(() -> {
                try (BufferedReader er = new BufferedReader(
                        new InputStreamReader(proc.getErrorStream()))) {
                    String l;
                    while ((l = er.readLine()) != null)
                        notifyStatus("root: " + l);
                } catch (IOException ignored) {}
            }).start();

            notifyStatus("Setting up /dev/uinput...");

            // Jalankan binary dengan args
            os.writeBytes(
                binary.getAbsolutePath() + " " +
                leftPath  + " " +
                rightPath + " " +
                myPid     + " " +
                leftWriteFd  + " " +
                rightWriteFd + " " +
                uinputReadFd + "\n");
            os.flush();

            // Tunggu "UINPUT_READY"
            boolean ready = false;
            String line;
            while ((line = br.readLine()) != null) {
                notifyStatus(line);
                if (line.equals("UINPUT_READY")) { ready = true; break; }
                if (line.startsWith("ERROR:"))   break;
            }

            if (!ready) {
                notifyStatus("ERROR: uinput setup failed");
                killRootProcess();
                return -1;
            }

            // Tutup ujung pipe yang dipegang binary
            leftPipe[1].close();
            rightPipe[1].close();
            uinputPipe[0].close();

            // Pass fd ke JNI — selesai!
            return startMergeWithFds(
                leftPipe[0].getFd(),
                rightPipe[0].getFd(),
                uinputPipe[1].getFd()
            );

        } catch (IOException e) {
            notifyStatus("ERROR: su failed: " + e.getMessage());
            return -1;
        }
    }

    private void drainStderr(Process proc) {
        new Thread(() -> {
            try (BufferedReader er = new BufferedReader(
                    new InputStreamReader(proc.getErrorStream()))) {
                while (er.readLine() != null) {}
            } catch (IOException ignored) {}
        }).start();
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
        killRootProcess();
    }

    private void createNotificationChannel() {
        NotificationChannel ch = new NotificationChannel(
            CHANNEL_ID, "Joy-Con Merge", NotificationManager.IMPORTANCE_LOW);
        ch.setDescription("Keeps Joy-Cons merged in background");
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
