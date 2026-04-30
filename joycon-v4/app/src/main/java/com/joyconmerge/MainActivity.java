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
import android.view.View;
import android.widget.*;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import com.google.android.material.tabs.TabLayout;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity {

    private MergeService service;
    private boolean bound = false;
    private Config config;

    /* Views - Status tab */
    private TextView tvStatus, tvDevices;
    private Button btnToggle;

    /* Views - Remap tab */
    private Spinner spA, spB, spX, spY, spR, spZR, spPlus, spR3;
    private Spinner spL, spZL, spMinus, spL3;

    /* Views - Calibrate tab */
    private SeekBar sbFlat, sbFuzz;
    private TextView tvFlat, tvFuzz;
    private CheckBox cbInvLX, cbInvLY, cbInvRX, cbInvRY;

    /* Views - Test tab */
    private TextView tvTestOutput;

    /* Tab content views */
    private View tabStatus, tabRemap, tabCalib, tabTest;

    private static final String[] BTN_NAMES = {
        "BTN_SOUTH (A)", "BTN_EAST (B)", "BTN_NORTH (X)", "BTN_WEST (Y)",
        "BTN_TL (L)", "BTN_TR (R)", "BTN_TL2 (ZL)", "BTN_TR2 (ZR)",
        "BTN_START (+)", "BTN_SELECT (-)", "BTN_THUMBL (L3)", "BTN_THUMBR (R3)"
    };
    private static final int[] BTN_CODES = {
        0x130, 0x131, 0x133, 0x134,
        0x135, 0x136, 0x139, 0x137,
        0x13b, 0x13a, 0x13c, 0x13d
    };

    // Permission launcher
    private ActivityResultLauncher<String[]> permissionLauncher;

    private final ServiceConnection conn = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            service = ((MergeService.LocalBinder) binder).getService();
            bound = true;
            service.setStatusCallback(msg -> runOnUiThread(() -> handleStatus(msg)));
            updateToggleButton();
        }
        @Override
        public void onServiceDisconnected(ComponentName name) { bound = false; }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        config = new Config(this);
        findViews();
        setupTabs();
        setupRemap();
        setupCalibrate();

        // Register permission launcher
        permissionLauncher = registerForActivityResult(
            new ActivityResultContracts.RequestMultiplePermissions(),
            results -> {
                boolean allGranted = true;
                for (Boolean granted : results.values()) {
                    if (!granted) { allGranted = false; break; }
                }
                if (allGranted) {
                    doStartMerge();
                } else {
                    Toast.makeText(this,
                        "Bluetooth permissions required to start merge",
                        Toast.LENGTH_LONG).show();
                }
            }
        );

        bindService(new Intent(this, MergeService.class), conn, Context.BIND_AUTO_CREATE);
    }

    private void findViews() {
        tabStatus = findViewById(R.id.tab_status);
        tabRemap  = findViewById(R.id.tab_remap);
        tabCalib  = findViewById(R.id.tab_calib);
        tabTest   = findViewById(R.id.tab_test);

        tvStatus  = findViewById(R.id.tv_status);
        tvDevices = findViewById(R.id.tv_devices);
        btnToggle = findViewById(R.id.btn_toggle);

        spA = findViewById(R.id.sp_a); spB = findViewById(R.id.sp_b);
        spX = findViewById(R.id.sp_x); spY = findViewById(R.id.sp_y);
        spR = findViewById(R.id.sp_r); spZR = findViewById(R.id.sp_zr);
        spPlus = findViewById(R.id.sp_plus); spR3 = findViewById(R.id.sp_r3);
        spL = findViewById(R.id.sp_l); spZL = findViewById(R.id.sp_zl);
        spMinus = findViewById(R.id.sp_minus); spL3 = findViewById(R.id.sp_l3);

        sbFlat = findViewById(R.id.sb_flat); sbFuzz = findViewById(R.id.sb_fuzz);
        tvFlat = findViewById(R.id.tv_flat); tvFuzz = findViewById(R.id.tv_fuzz);
        cbInvLX = findViewById(R.id.cb_inv_lx); cbInvLY = findViewById(R.id.cb_inv_ly);
        cbInvRX = findViewById(R.id.cb_inv_rx); cbInvRY = findViewById(R.id.cb_inv_ry);

        tvTestOutput = findViewById(R.id.tv_test_output);

        btnToggle.setOnClickListener(v -> toggleMerge());
        findViewById(R.id.btn_save_remap).setOnClickListener(v -> saveRemap());
        findViewById(R.id.btn_save_calib).setOnClickListener(v -> saveCalib());
    }

    private void setupTabs() {
        TabLayout tabs = findViewById(R.id.tabs);
        tabs.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override public void onTabSelected(TabLayout.Tab tab) {
                tabStatus.setVisibility(View.GONE);
                tabRemap.setVisibility(View.GONE);
                tabCalib.setVisibility(View.GONE);
                tabTest.setVisibility(View.GONE);
                switch (tab.getPosition()) {
                    case 0: tabStatus.setVisibility(View.VISIBLE); break;
                    case 1: tabRemap.setVisibility(View.VISIBLE); break;
                    case 2: tabCalib.setVisibility(View.VISIBLE); break;
                    case 3: tabTest.setVisibility(View.VISIBLE); break;
                }
            }
            @Override public void onTabUnselected(TabLayout.Tab tab) {}
            @Override public void onTabReselected(TabLayout.Tab tab) {}
        });
        tabStatus.setVisibility(View.VISIBLE);
    }

    private ArrayAdapter<String> makeAdapter() {
        return new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, BTN_NAMES);
    }

    private void setupRemap() {
        Spinner[] spinners = {spA,spB,spX,spY,spR,spZR,spPlus,spR3,spL,spZL,spMinus,spL3};
        for (Spinner sp : spinners) {
            ArrayAdapter<String> ad = makeAdapter();
            ad.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            sp.setAdapter(ad);
        }
        spA.setSelection(codeToIdx(config.getMapA()));
        spB.setSelection(codeToIdx(config.getMapB()));
        spX.setSelection(codeToIdx(config.getMapX()));
        spY.setSelection(codeToIdx(config.getMapY()));
        spR.setSelection(codeToIdx(config.getMapR()));
        spZR.setSelection(codeToIdx(config.getMapZR()));
        spPlus.setSelection(codeToIdx(config.getMapPlus()));
        spR3.setSelection(codeToIdx(config.getMapR3()));
        spL.setSelection(codeToIdx(config.getMapL()));
        spZL.setSelection(codeToIdx(config.getMapZL()));
        spMinus.setSelection(codeToIdx(config.getMapMinus()));
        spL3.setSelection(codeToIdx(config.getMapL3()));
    }

    private void setupCalibrate() {
        sbFlat.setMax(16384); sbFlat.setProgress(config.getFlat());
        sbFuzz.setMax(2048);  sbFuzz.setProgress(config.getFuzz());
        tvFlat.setText(String.valueOf(config.getFlat()));
        tvFuzz.setText(String.valueOf(config.getFuzz()));
        cbInvLX.setChecked(config.getInvLX()); cbInvLY.setChecked(config.getInvLY());
        cbInvRX.setChecked(config.getInvRX()); cbInvRY.setChecked(config.getInvRY());
        sbFlat.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar sb, int p, boolean u) { tvFlat.setText(String.valueOf(p)); }
            public void onStartTrackingTouch(SeekBar sb) {}
            public void onStopTrackingTouch(SeekBar sb) {}
        });
        sbFuzz.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar sb, int p, boolean u) { tvFuzz.setText(String.valueOf(p)); }
            public void onStartTrackingTouch(SeekBar sb) {}
            public void onStopTrackingTouch(SeekBar sb) {}
        });
    }

    private void toggleMerge() {
        if (bound && service.isMerging()) {
            // Stop
            Intent intent = new Intent(this, MergeService.class);
            intent.setAction(MergeService.ACTION_STOP);
            startForegroundService(intent);
        } else {
            // Check & request Bluetooth permissions before starting
            checkPermissionsAndStart();
        }
    }

    private void checkPermissionsAndStart() {
        List<String> needed = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                needed.add(Manifest.permission.POST_NOTIFICATIONS);
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN)
                    != PackageManager.PERMISSION_GRANTED) {
                needed.add(Manifest.permission.BLUETOOTH_SCAN);
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                    != PackageManager.PERMISSION_GRANTED) {
                needed.add(Manifest.permission.BLUETOOTH_CONNECT);
            }
        }
        if (needed.isEmpty()) {
            doStartMerge();
        } else {
            permissionLauncher.launch(needed.toArray(new String[0]));
        }
    }

    private void doStartMerge() {
        applyConfigToService();
        Intent intent = new Intent(this, MergeService.class);
        intent.setAction(MergeService.ACTION_START);
        startForegroundService(intent);
    }

    private void applyConfigToService() {
        if (!bound) return;
        service.setConfig(
            config.getFuzz(), config.getFlat(),
            config.getInvLX()?1:0, config.getInvLY()?1:0,
            config.getInvRX()?1:0, config.getInvRY()?1:0,
            0, 1, 3, 4,
            config.getCodeA(), config.getMapA(),
            config.getCodeB(), config.getMapB(),
            config.getCodeX(), config.getMapX(),
            config.getCodeY(), config.getMapY(),
            0x136, config.getMapR(),
            0x137, config.getMapZR(),
            0x13b, config.getMapPlus(),
            0x13d, config.getMapR3(),
            0x135, config.getMapL(),
            0x139, config.getMapZL(),
            0x13a, config.getMapMinus(),
            0x13c, config.getMapL3(),
            544, 545, 546, 547
        );
    }

    private void saveRemap() {
        config.save(
            config.getFuzz(), config.getFlat(),
            config.getInvLX(), config.getInvLY(),
            config.getInvRX(), config.getInvRY(),
            BTN_CODES[spA.getSelectedItemPosition()],
            BTN_CODES[spB.getSelectedItemPosition()],
            BTN_CODES[spX.getSelectedItemPosition()],
            BTN_CODES[spY.getSelectedItemPosition()],
            BTN_CODES[spR.getSelectedItemPosition()],
            BTN_CODES[spZR.getSelectedItemPosition()],
            BTN_CODES[spPlus.getSelectedItemPosition()],
            BTN_CODES[spR3.getSelectedItemPosition()],
            BTN_CODES[spL.getSelectedItemPosition()],
            BTN_CODES[spZL.getSelectedItemPosition()],
            BTN_CODES[spMinus.getSelectedItemPosition()],
            BTN_CODES[spL3.getSelectedItemPosition()]
        );
        Toast.makeText(this, "Button mapping saved!", Toast.LENGTH_SHORT).show();
        applyConfigToService();
    }

    private void saveCalib() {
        config.save(
            sbFuzz.getProgress(), sbFlat.getProgress(),
            cbInvLX.isChecked(), cbInvLY.isChecked(),
            cbInvRX.isChecked(), cbInvRY.isChecked(),
            config.getMapA(), config.getMapB(),
            config.getMapX(), config.getMapY(),
            config.getMapR(), config.getMapZR(),
            config.getMapPlus(), config.getMapR3(),
            config.getMapL(), config.getMapZL(),
            config.getMapMinus(), config.getMapL3()
        );
        Toast.makeText(this, "Calibration saved!", Toast.LENGTH_SHORT).show();
        applyConfigToService();
    }

    private void handleStatus(String msg) {
        tvStatus.setText(msg);
        if (bound) {
            String devs = service.getFoundDevices();
            if (devs != null && !devs.equals("|")) tvDevices.setText(devs.replace("|", "\n"));
        }
        updateToggleButton();
        tvTestOutput.setText(tvTestOutput.getText() + "\n" + msg);
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
        for (int i=0;i<BTN_CODES.length;i++)
            if (BTN_CODES[i]==code) return i;
        return 0;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (bound) unbindService(conn);
    }
}
