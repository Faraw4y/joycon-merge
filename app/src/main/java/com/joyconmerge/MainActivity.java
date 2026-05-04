package com.joyconmerge;

import android.Manifest;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.widget.*;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import com.google.android.material.tabs.TabLayout;

import java.util.ArrayList;
import java.util.List;

import rikka.shizuku.Shizuku;

public class MainActivity extends AppCompatActivity {

    private MergeService service;
    private boolean bound = false;
    private Config config;

    // Status Shizuku
    private View shizukuBanner;
    private TextView tvShizukuStatus;
    private Button btnGrantShizuku;

    private TextView tvStatus;
    private TextView tvDevices;
    private Button btnToggle;
    private Spinner spA, spB, spX, spY, spR, spZR, spPlus, spR3;
    private Spinner spL, spZL, spMinus, spL3;
    private Spinner spLeftPath, spRightPath;
    private CheckBox cbUseManual;
    private List<String> eventPaths = new ArrayList<>();

    private SeekBar sbFlat, sbFuzz;
    private TextView tvFlat, tvFuzz;
    private CheckBox cbInvLX, cbInvLY, cbInvRX, cbInvRY;
    private GamepadView gamepadView;
    private TextView tvLastBtn;
    private View tabStatus, tabRemap, tabCalib, tabTest;

    private static final String[] BTN_NAMES = {
        "BTN_SOUTH (B/Cross)", "BTN_EAST (A/Circle)", "BTN_NORTH (Y/Triangle)",
        "BTN_WEST (X/Square)", "BTN_TL (L)", "BTN_TR (R)", "BTN_TL2 (ZL)", "BTN_TR2 (ZR)",
        "BTN_SELECT (−)", "BTN_START (+)", "BTN_THUMBL (L3)", "BTN_THUMBR (R3)"
    };
    private static final int[] BTN_CODES = {
        0x130,0x131,0x133,0x134, 0x136,0x137,0x138,0x139, 0x13a,0x13b,0x13d,0x13e
    };

    private static final int SHIZUKU_REQUEST_CODE = 100;

    private ActivityResultLauncher<String[]> permissionLauncher;

    // ─── Shizuku permission listener ─────────────────────────────────────────

    private final Shizuku.OnRequestPermissionResultListener shizukuPermResult =
        (requestCode, grantResult) -> {
            if (requestCode == SHIZUKU_REQUEST_CODE) {
                runOnUiThread(this::updateShizukuBanner);
                if (grantResult == PackageManager.PERMISSION_GRANTED) {
                    runOnUiThread(() -> Toast.makeText(this,
                        "Shizuku granted! Bisa mulai merge.", Toast.LENGTH_SHORT).show());
                }
            }
        };

    // ─── ServiceConnection ────────────────────────────────────────────────────

    private final ServiceConnection conn = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            service = ((MergeService.LocalBinder) binder).getService();
            bound = true;
            applyConfigToService();
            service.setStatusCallback(new MergeService.StatusCallback() {
                @Override public void onStatus(String msg) {
                    runOnUiThread(() -> handleStatus(msg));
                }
                @Override public void onEvent(String event) {
                    runOnUiThread(() -> handleEvent(event));
                }
                @Override public void onDevices(String left, String right) {
                    runOnUiThread(() -> tvDevices.setText(
                        "L Joy-Con: " + left + "\nR Joy-Con: " + right));
                }
                @Override public void onScanResult(List<String> allPaths, String autoLeft, String autoRight) {
                    runOnUiThread(() -> {
                        // auto mode — tidak perlu populate spinner, sudah via auto-scan
                    });
                }
            });
            updateToggleButton();
        }
        @Override
        public void onServiceDisconnected(ComponentName name) { bound = false; }
    };

    // ─── onCreate ─────────────────────────────────────────────────────────────

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        config = new Config(this);
        findViews();
        setupTabs();
        setupRemap();
        setupCalibrate();

        Shizuku.addRequestPermissionResultListener(shizukuPermResult);

        permissionLauncher = registerForActivityResult(
            new ActivityResultContracts.RequestMultiplePermissions(),
            results -> {
                boolean allGranted = true;
                for (Boolean g : results.values()) if (!g) { allGranted = false; break; }
                if (allGranted) doStartMerge();
                else Toast.makeText(this, "Bluetooth permissions required", Toast.LENGTH_LONG).show();
            });

        bindService(new Intent(this, MergeService.class), conn, Context.BIND_AUTO_CREATE);
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateShizukuBanner();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        Shizuku.removeRequestPermissionResultListener(shizukuPermResult);
        if (bound) unbindService(conn);
    }

    // ─── Views ────────────────────────────────────────────────────────────────

    private void findViews() {
        shizukuBanner    = findViewById(R.id.shizuku_banner);
        tvShizukuStatus  = findViewById(R.id.tv_shizuku_status);
        btnGrantShizuku  = findViewById(R.id.btn_grant_shizuku);
        tabStatus = findViewById(R.id.tab_status);
        tabRemap  = findViewById(R.id.tab_remap);
        tabCalib  = findViewById(R.id.tab_calib);
        tabTest   = findViewById(R.id.tab_test);
        tvStatus  = findViewById(R.id.tv_status);
        tvDevices = findViewById(R.id.tv_devices);
        btnToggle = findViewById(R.id.btn_toggle);
        spA=findViewById(R.id.sp_a); spB=findViewById(R.id.sp_b);
        spX=findViewById(R.id.sp_x); spY=findViewById(R.id.sp_y);
        spR=findViewById(R.id.sp_r); spZR=findViewById(R.id.sp_zr);
        spPlus=findViewById(R.id.sp_plus); spR3=findViewById(R.id.sp_r3);
        spL=findViewById(R.id.sp_l); spZL=findViewById(R.id.sp_zl);
        spMinus=findViewById(R.id.sp_minus); spL3=findViewById(R.id.sp_l3);
        sbFlat=findViewById(R.id.sb_flat); sbFuzz=findViewById(R.id.sb_fuzz);
        tvFlat=findViewById(R.id.tv_flat); tvFuzz=findViewById(R.id.tv_fuzz);
        cbInvLX=findViewById(R.id.cb_inv_lx); cbInvLY=findViewById(R.id.cb_inv_ly);
        cbInvRX=findViewById(R.id.cb_inv_rx); cbInvRY=findViewById(R.id.cb_inv_ry);
        gamepadView = findViewById(R.id.gamepad_view);
        tvLastBtn   = findViewById(R.id.tv_last_btn);
        spLeftPath  = findViewById(R.id.sp_left_path);
        spRightPath = findViewById(R.id.sp_right_path);
        cbUseManual = findViewById(R.id.cb_use_manual);

        btnGrantShizuku.setOnClickListener(v -> requestShizukuPermission());
        findViewById(R.id.btn_scan_paths).setOnClickListener(v -> scanEventPaths());
        btnToggle.setOnClickListener(v -> toggleMerge());
        findViewById(R.id.btn_save_remap).setOnClickListener(v -> saveRemap());
        findViewById(R.id.btn_save_calib).setOnClickListener(v -> saveCalib());
        findViewById(R.id.btn_reset_test).setOnClickListener(v -> {
            gamepadView.resetAll();
            tvLastBtn.setText("—");
        });
    }

    // ─── Shizuku ─────────────────────────────────────────────────────────────

    private boolean isShizukuPermissionGranted() {
        try {
            if (Shizuku.isPreV11()) return false;
            return Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
        } catch (IllegalStateException e) {
            // Shizuku belum berjalan
            return false;
        }
    }

    private void updateShizukuBanner() {
        boolean running = isShizukuRunning();
        boolean granted = isShizukuPermissionGranted();

        if (granted) {
            shizukuBanner.setVisibility(View.GONE);
        } else {
            shizukuBanner.setVisibility(View.VISIBLE);
            if (!running) {
                tvShizukuStatus.setText("⚠ Shizuku belum berjalan. Install & aktifkan Shizuku terlebih dulu.");
                btnGrantShizuku.setVisibility(View.GONE);
            } else {
                tvShizukuStatus.setText("⚠ Izin Shizuku belum diberikan. Tap untuk mengizinkan.");
                btnGrantShizuku.setVisibility(View.VISIBLE);
            }
        }
    }

    private boolean isShizukuRunning() {
        try {
            return Shizuku.pingBinder();
        } catch (Exception e) {
            return false;
        }
    }

    private void requestShizukuPermission() {
        try {
            if (Shizuku.isPreV11()) {
                Toast.makeText(this, "Shizuku terlalu lama, upgrade ke v11+", Toast.LENGTH_LONG).show();
                return;
            }
            Shizuku.requestPermission(SHIZUKU_REQUEST_CODE);
        } catch (IllegalStateException e) {
            Toast.makeText(this, "Shizuku tidak berjalan", Toast.LENGTH_SHORT).show();
        }
    }

    // ─── Tabs ─────────────────────────────────────────────────────────────────

    private void setupTabs() {
        TabLayout tabs = findViewById(R.id.tabs);
        tabs.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override public void onTabSelected(TabLayout.Tab tab) {
                tabStatus.setVisibility(View.GONE); tabRemap.setVisibility(View.GONE);
                tabCalib.setVisibility(View.GONE);  tabTest.setVisibility(View.GONE);
                switch (tab.getPosition()) {
                    case 0: tabStatus.setVisibility(View.VISIBLE); break;
                    case 1: tabRemap.setVisibility(View.VISIBLE);  break;
                    case 2: tabCalib.setVisibility(View.VISIBLE);  break;
                    case 3: tabTest.setVisibility(View.VISIBLE);   break;
                }
            }
            @Override public void onTabUnselected(TabLayout.Tab tab) {}
            @Override public void onTabReselected(TabLayout.Tab tab) {}
        });
        tabStatus.setVisibility(View.VISIBLE);
    }

    // ─── Scan event paths ─────────────────────────────────────────────────────

    private void scanEventPaths() {
        if (!isShizukuPermissionGranted()) {
            Toast.makeText(this, "Berikan izin Shizuku dulu!", Toast.LENGTH_LONG).show();
            return;
        }
        Toast.makeText(this, "Scanning...", Toast.LENGTH_SHORT).show();
        if (!bound) { Toast.makeText(this, "Service belum siap", Toast.LENGTH_SHORT).show(); return; }
        service.scanJoyConPathsAsync();
        // hasil akan datang via onScanResult callback
        // untuk manual scan, kita gunakan cara langsung di bawah:
        new Thread(() -> {
            if (service == null) return;
            // Trigger scan dan ambil hasilnya via callback di MergeService
            // (sudah di-handle di ServiceConnection callback onScanResult)
            // Untuk populate spinner, kita re-scan lewat service
            scanPathsForSpinners();
        }).start();
    }

    private void scanPathsForSpinners() {
        // Panggil UserService langsung via bound service
        if (!bound || service == null) return;
        // Kita minta service scan dan hasilnya via callback
        // MainActivity daftarkan callback extra untuk scan manual
        service.setStatusCallback(new MergeService.StatusCallback() {
            @Override public void onStatus(String msg) {
                runOnUiThread(() -> handleStatus(msg));
            }
            @Override public void onEvent(String event) {
                runOnUiThread(() -> handleEvent(event));
            }
            @Override public void onDevices(String left, String right) {
                runOnUiThread(() -> tvDevices.setText("L Joy-Con: " + left + "\nR Joy-Con: " + right));
            }
            @Override public void onScanResult(List<String> allPaths, String autoLeft, String autoRight) {
                runOnUiThread(() -> updatePathSpinners(allPaths));
            }
        });
        service.scanJoyConPathsAsync();
    }

    private void updatePathSpinners(List<String> paths) {
        eventPaths.clear();
        eventPaths.addAll(paths);
        List<String> labels = new ArrayList<>(paths);
        if (labels.isEmpty()) {
            Toast.makeText(this, "Tidak ada Joy-Con event device ditemukan", Toast.LENGTH_LONG).show();
            return;
        }
        ArrayAdapter<String> adL = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, new ArrayList<>(labels));
        adL.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spLeftPath.setAdapter(adL);
        ArrayAdapter<String> adR = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, new ArrayList<>(labels));
        adR.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spRightPath.setAdapter(adR);
        for (int i = 0; i < labels.size(); i++) {
            if (labels.get(i).toLowerCase().contains("left")) { spLeftPath.setSelection(i); break; }
        }
        for (int i = labels.size()-1; i >= 0; i--) {
            if (labels.get(i).toLowerCase().contains("right")) { spRightPath.setSelection(i); break; }
        }
        Toast.makeText(this, "✓ " + paths.size() + " device. Pilih L dan R, lalu Start.", Toast.LENGTH_LONG).show();
    }

    // ─── Merge control ────────────────────────────────────────────────────────

    private void toggleMerge() {
        if (bound && service.isMerging()) {
            Intent intent = new Intent(this, MergeService.class);
            intent.setAction(MergeService.ACTION_STOP);
            startForegroundService(intent);
        } else {
            if (!isShizukuPermissionGranted()) {
                Toast.makeText(this, "Berikan izin Shizuku dulu!", Toast.LENGTH_LONG).show();
                updateShizukuBanner();
                return;
            }
            checkPermissionsAndStart();
        }
    }

    private void checkPermissionsAndStart() {
        List<String> needed = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED)
                needed.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN)
                    != PackageManager.PERMISSION_GRANTED)
                needed.add(Manifest.permission.BLUETOOTH_SCAN);
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                    != PackageManager.PERMISSION_GRANTED)
                needed.add(Manifest.permission.BLUETOOTH_CONNECT);
        }
        if (needed.isEmpty()) doStartMerge();
        else permissionLauncher.launch(needed.toArray(new String[0]));
    }

    private void doStartMerge() {
        applyConfigToService();
        if (cbUseManual != null && cbUseManual.isChecked()) {
            if (eventPaths.isEmpty()) {
                Toast.makeText(this, "Scan dulu path-nya pakai tombol 🔍", Toast.LENGTH_LONG).show();
                return;
            }
            String lp = eventPaths.get(spLeftPath.getSelectedItemPosition());
            String rp = eventPaths.get(spRightPath.getSelectedItemPosition());
            if (lp.equals(rp)) {
                Toast.makeText(this, "Left dan Right path tidak boleh sama!", Toast.LENGTH_LONG).show();
                return;
            }
            Intent intent = new Intent(this, MergeService.class);
            intent.setAction(MergeService.ACTION_START_MANUAL);
            intent.putExtra(MergeService.EXTRA_LEFT_PATH,  lp);
            intent.putExtra(MergeService.EXTRA_RIGHT_PATH, rp);
            startForegroundService(intent);
        } else {
            Intent intent = new Intent(this, MergeService.class);
            intent.setAction(MergeService.ACTION_START);
            startForegroundService(intent);
        }
    }

    private void applyConfigToService() {
        if (!bound) return;
        service.setConfig(
            config.getFuzz(), config.getFlat(),
            config.getInvLX()?1:0, config.getInvLY()?1:0,
            config.getInvRX()?1:0, config.getInvRY()?1:0,
            config.getMapA(),  config.getMapB(),
            config.getMapX(),  config.getMapY(),
            config.getMapR(),  config.getMapZR(),
            config.getMapPlus(),config.getMapR3(),
            config.getMapL(),  config.getMapZL(),
            config.getMapMinus(),config.getMapL3(),
            config.getMapHome(), config.getMapCapture());
    }

    // ─── Remap & calib setup ──────────────────────────────────────────────────

    private void setupRemap() {
        ArrayAdapter<String> ad = new ArrayAdapter<>(this,
            android.R.layout.simple_spinner_item, BTN_NAMES);
        ad.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        Spinner[] spinners = {spA,spB,spX,spY,spR,spZR,spPlus,spR3,spL,spZL,spMinus,spL3};
        int[] defaults = {
            config.getMapA(), config.getMapB(), config.getMapX(), config.getMapY(),
            config.getMapR(), config.getMapZR(), config.getMapPlus(), config.getMapR3(),
            config.getMapL(), config.getMapZL(), config.getMapMinus(), config.getMapL3()
        };
        for (int i = 0; i < spinners.length; i++) {
            spinners[i].setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, BTN_NAMES));
            spinners[i].getAdapter();
            spinners[i].setSelection(codeToIdx(defaults[i]));
        }
    }

    private void setupCalibrate() {
        sbFuzz.setMax(1024); sbFuzz.setProgress(config.getFuzz());
        sbFlat.setMax(16383); sbFlat.setProgress(config.getFlat());
        tvFuzz.setText("Fuzz: " + config.getFuzz());
        tvFlat.setText("Flat: " + config.getFlat());
        sbFuzz.setOnSeekBarChangeListener(new SimpleSeekListener() {
            @Override public void onProgressChanged(SeekBar sb, int p, boolean f) { tvFuzz.setText("Fuzz: "+p); }
        });
        sbFlat.setOnSeekBarChangeListener(new SimpleSeekListener() {
            @Override public void onProgressChanged(SeekBar sb, int p, boolean f) { tvFlat.setText("Flat: "+p); }
        });
        cbInvLX.setChecked(config.getInvLX()); cbInvLY.setChecked(config.getInvLY());
        cbInvRX.setChecked(config.getInvRX()); cbInvRY.setChecked(config.getInvRY());
    }

    private void saveRemap() {
        config.save(config.getFuzz(),config.getFlat(),
            config.getInvLX(),config.getInvLY(),config.getInvRX(),config.getInvRY(),
            BTN_CODES[spA.getSelectedItemPosition()],BTN_CODES[spB.getSelectedItemPosition()],
            BTN_CODES[spX.getSelectedItemPosition()],BTN_CODES[spY.getSelectedItemPosition()],
            BTN_CODES[spR.getSelectedItemPosition()],BTN_CODES[spZR.getSelectedItemPosition()],
            BTN_CODES[spPlus.getSelectedItemPosition()],BTN_CODES[spR3.getSelectedItemPosition()],
            BTN_CODES[spL.getSelectedItemPosition()],BTN_CODES[spZL.getSelectedItemPosition()],
            BTN_CODES[spMinus.getSelectedItemPosition()],BTN_CODES[spL3.getSelectedItemPosition()],
            config.getMapHome(), config.getMapCapture());
        if (bound && service.isMerging()) {
            Toast.makeText(this, "Mapping saved — restarting…", Toast.LENGTH_SHORT).show();
            Intent stop = new Intent(this, MergeService.class);
            stop.setAction(MergeService.ACTION_STOP);
            startForegroundService(stop);
            btnToggle.postDelayed(() -> { applyConfigToService(); doStartMerge(); }, 600);
        } else {
            Toast.makeText(this, "Button mapping saved!", Toast.LENGTH_SHORT).show();
            applyConfigToService();
        }
    }

    private void saveCalib() {
        config.save(sbFuzz.getProgress(),sbFlat.getProgress(),
            cbInvLX.isChecked(),cbInvLY.isChecked(),cbInvRX.isChecked(),cbInvRY.isChecked(),
            config.getMapA(),config.getMapB(),config.getMapX(),config.getMapY(),
            config.getMapR(),config.getMapZR(),config.getMapPlus(),config.getMapR3(),
            config.getMapL(),config.getMapZL(),config.getMapMinus(),config.getMapL3(),
            config.getMapHome(), config.getMapCapture());
        Toast.makeText(this,"Calibration saved!",Toast.LENGTH_SHORT).show();
        applyConfigToService();
    }

    // ─── Event handling ────────────────────────────────────────────────────────

    private void handleEvent(String event) {
        if (event == null) return;
        String[] parts = event.split(" ");
        if (parts.length < 3) return;
        try {
            if ("KEY".equals(parts[0])) {
                int code  = Integer.parseInt(parts[1]);
                int value = Integer.parseInt(parts[2]);
                String btnName = keyCodeToName(code);
                if (btnName != null) {
                    gamepadView.setButtonPressed(btnName, value != 0);
                    if (value != 0) tvLastBtn.setText(btnName);
                }
            } else if ("ABS".equals(parts[0])) {
                int axis  = Integer.parseInt(parts[1]);
                float val = Float.parseFloat(parts[2]) / 32767f;
                switch (axis) {
                    case 0: gamepadView.setStick(true,  val, gamepadView.getLY()); break;
                    case 1: gamepadView.setStick(true,  gamepadView.getLX(), val); break;
                    case 3: gamepadView.setStick(false, val, gamepadView.getRY()); break;
                    case 4: gamepadView.setStick(false, gamepadView.getRX(), val); break;
                }
            } else if ("DPAD".equals(parts[0])) {
                String dir = parts[1]; int value = Integer.parseInt(parts[2]);
                switch (dir) {
                    case "up":    gamepadView.setButtonPressed(GamepadView.DPAD_UP,    value!=0); break;
                    case "down":  gamepadView.setButtonPressed(GamepadView.DPAD_DOWN,  value!=0); break;
                    case "left":  gamepadView.setButtonPressed(GamepadView.DPAD_LEFT,  value!=0); break;
                    case "right": gamepadView.setButtonPressed(GamepadView.DPAD_RIGHT, value!=0); break;
                }
            }
        } catch (NumberFormatException ignored) {}
    }

    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent ev) {
        if ((ev.getSource() & InputDevice.SOURCE_JOYSTICK) != 0) {
            gamepadView.setStick(true, ev.getAxisValue(MotionEvent.AXIS_X), ev.getAxisValue(MotionEvent.AXIS_Y));
            gamepadView.setStick(false, ev.getAxisValue(MotionEvent.AXIS_RX), ev.getAxisValue(MotionEvent.AXIS_RY));
            float hx = ev.getAxisValue(MotionEvent.AXIS_HAT_X);
            float hy = ev.getAxisValue(MotionEvent.AXIS_HAT_Y);
            gamepadView.setButtonPressed(GamepadView.DPAD_LEFT,  hx < -0.5f);
            gamepadView.setButtonPressed(GamepadView.DPAD_RIGHT, hx >  0.5f);
            gamepadView.setButtonPressed(GamepadView.DPAD_UP,    hy < -0.5f);
            gamepadView.setButtonPressed(GamepadView.DPAD_DOWN,  hy >  0.5f);
            return true;
        }
        return super.dispatchGenericMotionEvent(ev);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent ev) {
        if (ev.getSource() == InputDevice.SOURCE_GAMEPAD ||
            (ev.getSource() & InputDevice.SOURCE_JOYSTICK) != 0) {
            String name = androidKeyToName(ev.getKeyCode());
            if (name != null) {
                gamepadView.setButtonPressed(name, ev.getAction() == KeyEvent.ACTION_DOWN);
                if (ev.getAction() == KeyEvent.ACTION_DOWN) tvLastBtn.setText(name);
                return true;
            }
        }
        return super.dispatchKeyEvent(ev);
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private void handleStatus(String msg) {
        tvStatus.setText(msg);
        if (msg.equals("RUNNING"))  updateToggleButton();
        if (msg.equals("STOPPED"))  { updateToggleButton(); tvDevices.setText("—"); }
    }

    private void updateToggleButton() {
        if (bound && service.isMerging()) {
            btnToggle.setText("Stop");
            btnToggle.setBackgroundColor(0xFFE53935);
        } else {
            btnToggle.setText("Start");
            btnToggle.setBackgroundColor(0xFF43A047);
        }
    }

    private int codeToIdx(int code) {
        for (int i=0;i<BTN_CODES.length;i++) if (BTN_CODES[i]==code) return i;
        return 0;
    }

    private static String keyCodeToName(int code) {
        switch (code) {
            case 0x131: return GamepadView.BTN_A;
            case 0x130: return GamepadView.BTN_B;
            case 0x133: return GamepadView.BTN_X;
            case 0x134: return GamepadView.BTN_Y;
            case 0x136: return GamepadView.BTN_L;
            case 0x137: return GamepadView.BTN_R;
            case 0x138: return GamepadView.BTN_ZL;
            case 0x139: return GamepadView.BTN_ZR;
            case 0x13a: return GamepadView.BTN_MINUS;
            case 0x13b: return GamepadView.BTN_PLUS;
            case 0x13c: return GamepadView.BTN_HOME;
            case 0x13d: return GamepadView.BTN_L3;
            case 0x13e: return GamepadView.BTN_R3;
            case 0xa7:  return GamepadView.BTN_CAP;
            default:    return null;
        }
    }

    private static String androidKeyToName(int kc) {
        switch (kc) {
            case KeyEvent.KEYCODE_BUTTON_A:      return GamepadView.BTN_A;
            case KeyEvent.KEYCODE_BUTTON_B:      return GamepadView.BTN_B;
            case KeyEvent.KEYCODE_BUTTON_X:      return GamepadView.BTN_X;
            case KeyEvent.KEYCODE_BUTTON_Y:      return GamepadView.BTN_Y;
            case KeyEvent.KEYCODE_BUTTON_L1:     return GamepadView.BTN_L;
            case KeyEvent.KEYCODE_BUTTON_R1:     return GamepadView.BTN_R;
            case KeyEvent.KEYCODE_BUTTON_L2:     return GamepadView.BTN_ZL;
            case KeyEvent.KEYCODE_BUTTON_R2:     return GamepadView.BTN_ZR;
            case KeyEvent.KEYCODE_BUTTON_START:  return GamepadView.BTN_PLUS;
            case KeyEvent.KEYCODE_BUTTON_SELECT: return GamepadView.BTN_MINUS;
            case KeyEvent.KEYCODE_BUTTON_THUMBL: return GamepadView.BTN_L3;
            case KeyEvent.KEYCODE_BUTTON_THUMBR: return GamepadView.BTN_R3;
            case KeyEvent.KEYCODE_DPAD_UP:       return GamepadView.DPAD_UP;
            case KeyEvent.KEYCODE_DPAD_DOWN:     return GamepadView.DPAD_DOWN;
            case KeyEvent.KEYCODE_DPAD_LEFT:     return GamepadView.DPAD_LEFT;
            case KeyEvent.KEYCODE_DPAD_RIGHT:    return GamepadView.DPAD_RIGHT;
            default: return null;
        }
    }

    // Helper class
    private static abstract class SimpleSeekListener implements SeekBar.OnSeekBarChangeListener {
        @Override public void onStartTrackingTouch(SeekBar sb) {}
        @Override public void onStopTrackingTouch(SeekBar sb) {}
    }
}
