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

        // 1. 申请修改系统设置权限（首次打开引导授权，永久有效）
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

        TextView statusTv = new TextView(this);
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
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
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

        // 如果已配置好目标软件，启动后留 2.5 秒缓冲（方便随时点界面修改设置），随后自动执行
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
            // 热点本来就是开着的，直接进 CarPlay
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

        // 如果静默接口被车机拦截，或者勾选了无障碍模式，则自动跳热点界面由无障碍服务点开
        needAutoClickHotspot = true;
        try {
            Intent intent = new Intent();
            intent.setComponent(new ComponentName("com.android.settings", "com.android.settings.TetherSettings"));
            startActivity(intent);
        } catch (Exception e) {
            startActivity(new Intent(Settings.ACTION_WIRELESS_SETTINGS));
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
        if (wm != null && wm.isWifiEnabled()) {
            wm.setWifiEnabled(false);
        }

        // 方式 1：反射 ConnectivityManager.startTethering
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

        // 方式 2：反射 WifiManager.setWifiApEnabled (联发科 MT8321 常用底层接口)
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

    private void showAppPicker(TextView statusTv) {
        PackageManager pm = getPackageManager();
        Intent mainIntent = new Intent(Intent.ACTION_MAIN, null);
        mainIntent.addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> apps = pm.queryIntentActivities(mainIntent, 0);

        List<String> names = new ArrayList<>();
        List<String> pkgs = new ArrayList<>();
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
