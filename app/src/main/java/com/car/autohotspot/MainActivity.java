package com.car.autohotspot;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity {

    private SharedPreferences prefs;
    public static final String PREF_TARGET_PKG = "target_pkg";
    // 默认直接绑定你的 CarPlay 软件 com.shihab.diplay
    public static final String DEFAULT_CARPLAY_PKG = "com.shihab.diplay";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView statusTv;
    // 防止从设置页返回时重复触发
    private static long lastTriggerTime = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("config", MODE_PRIVATE);

        // 如果还没有保存过包名，默认写入 com.shihab.diplay
        if (prefs.getString(PREF_TARGET_PKG, "").isEmpty()) {
            prefs.edit().putString(PREF_TARGET_PKG, DEFAULT_CARPLAY_PKG).apply();
        }

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(50, 40, 50, 40);

        statusTv = new TextView(this);
        statusTv.setTextSize(16);
        layout.addView(statusTv);

        Button accBtn = new Button(this);
        accBtn.setText("第一步：开启【无障碍服务】权限");
        accBtn.setOnClickListener(v -> {
            HotspotAccessibilityService.stopTask();
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
        });
        layout.addView(accBtn);

        Button selectBtn = new Button(this);
        selectBtn.setText("第二步：更换绑定的目标软件（已默认绑定 com.shihab.diplay）");
        selectBtn.setOnClickListener(v -> {
            HotspotAccessibilityService.stopTask();
            showAppPicker();
        });
        layout.addView(selectBtn);

        Button testBtn = new Button(this);
        testBtn.setText("第三步：立即执行（开热点 -> 成功后开 CarPlay）");
        testBtn.setOnClickListener(v -> {
            lastTriggerTime = 0;
            executeImmediately();
        });
        layout.addView(testBtn);

        setContentView(layout);
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateStatusText();

        // 只要无障碍已开启，且距离上次触发超过 8 秒，打开软件瞬间立即执行命令！
        if (isAccessibilitySettingsOn()) {
            long now = System.currentTimeMillis();
            if (now - lastTriggerTime > 8000) {
                lastTriggerTime = now;
                // 延迟 200ms 待界面稳定后立即执行，无需等 2 秒
                handler.postDelayed(this::executeImmediately, 200);
            }
        }
    }

    private void updateStatusText() {
        String savedPkg = prefs.getString(PREF_TARGET_PKG, DEFAULT_CARPLAY_PKG);
        boolean accEnabled = isAccessibilitySettingsOn();
        statusTv.setText("1. 无障碍服务状态：" + (accEnabled ? "【已开启】" : "【未开启 - 请点击下方按钮开启】")
                + "\n2. 绑定的目标软件：" + savedPkg
                + "\n\n当前模式：打开本软件即刻执行，常驻后台不销毁。");
    }

    private void executeImmediately() {
        String targetPkg = prefs.getString(PREF_TARGET_PKG, DEFAULT_CARPLAY_PKG);

        if (!isAccessibilitySettingsOn()) {
            Toast.makeText(this, "请先开启【车机热点自启】无障碍权限！", Toast.LENGTH_LONG).show();
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            return;
        }

        // 如果检测到热点本来就已经开着，直接秒开 CarPlay
        if (isWifiApEnabled(this)) {
            launchApp(this, targetPkg);
            return;
        }

        // 启动无障碍后台状态机（由无障碍服务全权负责：点热点 -> 等开启 -> 拉起 CarPlay）
        HotspotAccessibilityService.startTask(this, targetPkg);

        // 直接跳转到热点设置页
        try {
            Intent intent = new Intent();
            intent.setComponent(new ComponentName("com.android.settings", "com.android.settings.TetherSettings"));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            startActivity(intent);
        } catch (Exception e) {
            startActivity(new Intent(Settings.ACTION_WIRELESS_SETTINGS));
        }
    }

    public static boolean isWifiApEnabled(Context context) {
        try {
            WifiManager wm = (WifiManager) context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            Method method = wm.getClass().getDeclaredMethod("isWifiApEnabled");
            method.setAccessible(true);
            int state = (Integer) wm.getClass().getDeclaredMethod("getWifiApState").invoke(wm);
            // Android WifiManager.WIFI_AP_STATE_ENABLED 的值是 13 (或 3)
            if (state == 13 || state == 3) return true;
            return (Boolean) method.invoke(wm);
        } catch (Exception e) {
            return false;
        }
    }

    public static void launchApp(Context context, String pkg) {
        if (TextUtils.isEmpty(pkg)) return;
        Intent launchIntent = context.getPackageManager().getLaunchIntentForPackage(pkg);
        if (launchIntent != null) {
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(launchIntent);
            // 注意：这里去掉了 finish()，让本软件常驻在车机后台随时待命！
        } else {
            Toast.makeText(context, "找不到目标软件: " + pkg, Toast.LENGTH_SHORT).show();
        }
    }

    private boolean isAccessibilitySettingsOn() {
        String service = getPackageName() + "/" + HotspotAccessibilityService.class.getCanonicalName();
        String enabledServices = Settings.Secure.getString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        return !TextUtils.isEmpty(enabledServices) && enabledServices.contains(service);
    }

    private void showAppPicker() {
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
                    updateStatusText();
                })
                .show();
    }
}
