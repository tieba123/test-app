package com.car.autohotspot;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.ConnectivityManager;
import android.net.Uri;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity {

    private SharedPreferences prefs;
    public static final String PREF_TARGET_PKG = "target_pkg";
    public static final String PREF_USE_ACCESSIBILITY = "use_acc";
    public static boolean needAutoClickHotspot = false;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private Runnable autoLaunchTask;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("config", MODE_PRIVATE);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.System.canWrite(this)) {
            Toast.makeText(this, "请授予【允许修改系统设置】权限", Toast.LENGTH_LONG).show();
            try {
                Intent intent = new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS,
                        Uri.parse("package:" + getPackageName()));
                startActivity(intent);
            } catch (Exception ignored) {}
        }

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(50, 40, 50, 40);

        final TextView statusTv = new TextView(this);
        String savedPkg = prefs.getString(PREF_TARGET_PKG, "");
        statusTv.setText("当前绑定的 CarPlay 软件：\n" + (savedPkg.isEmpty() ? "【未选择】" : savedPkg));
        statusTv.setTextSize(18);
        layout.addView(statusTv);

        Button selectBtn = new Button(this);
        selectBtn.setText("1. 选择要开机自动打开的软件 (CarPlay)");
        selectBtn.setOnClickListener(v -> {
            cancelAutoTask();
            showAppPicker(statusTv);
        });
        layout.addView(selectBtn);

        CheckBox accCheck = new CheckBox(this);
        accCheck.setText("使用无障碍模拟点击开热点（仅当默认方式打不开热点时勾选）");
        accCheck.setChecked(prefs.getBoolean(PREF_USE_ACCESSIBILITY, false));
        accCheck.setOnCheckedChangeListener((btn, isChecked) -> {
            cancelAutoTask();
            prefs.edit().putBoolean(PREF_USE_ACCESSIBILITY, isChecked).apply();
            if (isChecked) {
                Toast.makeText(this, "请在无障碍设置中开启【车机热点自启】", Toast.LENGTH_LONG).show();
                try {
                    startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
                } catch (Exception ignored) {}
            }
        });
        layout.addView(accCheck);

        Button testBtn = new Button(this);
        testBtn.setText("2. 立即测试（开热点 -> 跳 CarPlay）");
        testBtn.setOnClickListener(v -> {
            cancelAutoTask();
            runWorkflow();
        });
        layout.addView(testBtn);

        setContentView(layout);

        if (!savedPkg.isEmpty()) {
            Toast.makeText(this, "2秒后自动开启热点并跳转（点击任意按钮可中断）", Toast.LENGTH_SHORT).show();
            autoLaunchTask = this::runWorkflow;
            handler.postDelayed(autoLaunchTask, 2200);
        }
    }

    private void cancelAutoTask() {
        if (autoLaunchTask != null) {
            handler.removeCallbacks(autoLaunchTask);
        }
    }

    private void runWorkflow() {
        if (isApEnabled()) {
            launchTargetApp();
            return;
        }

        boolean useAcc = prefs.getBoolean(PREF_USE_ACCESSIBILITY, false);
        if (!useAcc) {
            boolean ok = enableHotspotSilently();
            if (ok || isApEnabled()) {
                handler.postDelayed(this::launchTargetApp, 1500);
                return;
            }
        }

        needAutoClickHotspot = true;
        try {
            Intent intent = new Intent();
            intent.setComponent(new ComponentName("com.android.settings", "com.android.settings.TetherSettings"));
            startActivity(intent);
        } catch (Exception e) {
            try {
                startActivity(new Intent(Settings.ACTION_WIRELESS_SETTINGS));
            } catch (Exception ignored) {}
        }
        handler.postDelayed(this::launchTargetApp, 3000);
    }

    private boolean isApEnabled() {
        try {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            Method method = wm.getClass().getDeclaredMethod("isWifiApEnabled");
            method.setAccessible(true);
            return (Boolean) method.invoke(wm);
        } catch (Exception e) {
            return false;
        }
    }

    private boolean enableHotspotSilently() {
        WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        try {
            if (wm != null && wm.isWifiEnabled()) {
                wm.setWifiEnabled(false);
            }
        } catch (Exception ignored) {}

        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            for (Method m : cm.getClass().getDeclaredMethods()) {
                if (m.getName().equals("startTethering")) {
                    Class<?> cbClass = Class.forName("android.net.ConnectivityManager$OnStartTetheringCallback");
                    Method startM = cm.getClass().getDeclaredMethod("startTethering", int.class, boolean.class, cbClass);
                    startM.invoke(cm, 0, false, null);
                    return true;
                }
            }
        } catch (Exception ignored) {}

        try {
            if (wm != null) {
                Method m = wm.getClass().getMethod("setWifiApEnabled", WifiConfiguration.class, boolean.class);
                return (Boolean) m.invoke(wm, null, true);
            }
        } catch (Exception ignored) {}

        return false;
    }

    private void launchTargetApp() {
        String pkg = prefs.getString(PREF_TARGET_PKG, "");
        if (pkg.isEmpty()) return;
        Intent launchIntent = getPackageManager().getLaunchIntentForPackage(pkg);
        if (launchIntent != null) {
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(launchIntent);
            finish();
        }
    }

    private void showAppPicker(final TextView statusTv) {
        PackageManager pm = getPackageManager();
        Intent mainIntent = new Intent(Intent.ACTION_MAIN, null);
        mainIntent.addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> apps = pm.queryIntentActivities(mainIntent, 0);

        final List<String> names = new ArrayList<>();
        final List<String> pkgs = new ArrayList<>();
        for (ResolveInfo info : apps) {
            String p = info.activityInfo.packageName;
            if (!p.equals(getPackageName())) {
                names.add(info.loadLabel(pm).toString() + " (" + p + ")");
                pkgs.add(p);
            }
        }

        new AlertDialog.Builder(this)
                .setTitle("选择 CarPlay 软件")
                .setItems(names.toArray(new String[0]), (d, which) -> {
                    String selected = pkgs.get(which);
                    prefs.edit().putString(PREF_TARGET_PKG, selected).apply();
                    statusTv.setText("当前绑定的 CarPlay 软件：\n" + selected);
                })
                .show();
    }
}
