package com.car.autohotspot;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.Build;
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
    public static volatile boolean isTaskRunning = false;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private Runnable autoLaunchTask;
    private Runnable checkApStatusRunnable;
    private int waitCount = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("config", MODE_PRIVATE);

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(50, 40, 50, 40);

        final TextView statusTv = new TextView(this);
        String savedPkg = prefs.getString(PREF_TARGET_PKG, "");
        boolean accEnabled = isAccessibilitySettingsOn();
        statusTv.setText("1. 无障碍服务状态：" + (accEnabled ? "【已开启】" : "【未开启 - 请点击下方按钮开启】")
                + "\n2. 绑定的目标软件：" + (savedPkg.isEmpty() ? "【未选择】" : savedPkg)
                + "\n\n执行顺序：先打开热点 -> 确认热点开启成功后 -> 自动打开目标软件");
        statusTv.setTextSize(16);
        layout.addView(statusTv);

        Button accBtn = new Button(this);
        accBtn.setText("第一步：开启【无障碍服务】权限");
        accBtn.setOnClickListener(v -> {
            cancelAllTasks();
            Toast.makeText(this, "请找到【车机热点自启】并开启", Toast.LENGTH_LONG).show();
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
        });
        layout.addView(accBtn);

        Button selectBtn = new Button(this);
        selectBtn.setText("第二步：选择要自动打开的软件 (CarPlay)");
        selectBtn.setOnClickListener(v -> {
            cancelAllTasks();
            showAppPicker(statusTv);
        });
        layout.addView(selectBtn);

        Button testBtn = new Button(this);
        testBtn.setText("第三步：立即测试（先开热点 -> 成功后开软件）");
        testBtn.setOnClickListener(v -> {
            cancelAllTasks();
            startSequentialWorkflow();
        });
        layout.addView(testBtn);

        setContentView(layout);

        // 如果无障碍已开启且软件已绑定，启动本软件 2 秒后自动开始流程
        if (!savedPkg.isEmpty() && accEnabled) {
            Toast.makeText(this, "2秒后自动执行（点击任意按钮可中断修改设置）", Toast.LENGTH_SHORT).show();
            autoLaunchTask = this::startSequentialWorkflow;
            handler.postDelayed(autoLaunchTask, 2000);
        }
    }

    private void cancelAllTasks() {
        isTaskRunning = false;
        if (autoLaunchTask != null) handler.removeCallbacks(autoLaunchTask);
        if (checkApStatusRunnable != null) handler.removeCallbacks(checkApStatusRunnable);
    }

    /**
     * 严格顺序工作流：步骤1 检查/开启热点 -> 步骤2 循环等待热点真正开启 -> 步骤3 打开目标软件
     */
    private void startSequentialWorkflow() {
        // 如果热点已经开着了，直接打开目标软件
        if (isWifiApEnabled(this)) {
            Toast.makeText(this, "热点已处于开启状态，直接启动目标软件...", Toast.LENGTH_SHORT).show();
            launchTargetApp();
            return;
        }

        if (!isAccessibilitySettingsOn()) {
            Toast.makeText(this, "请先点击第一步开启【无障碍服务】权限！", Toast.LENGTH_LONG).show();
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            return;
        }

        // 标记任务开始，激活无障碍自动点击状态机
        isTaskRunning = true;
        HotspotAccessibilityService.resetState();

        // 跳转到系统热点设置页面
        try {
            Intent intent = new Intent();
            intent.setComponent(new ComponentName("com.android.settings", "com.android.settings.TetherSettings"));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            startActivity(intent);
        } catch (Exception e) {
            startActivity(new Intent(Settings.ACTION_WIRELESS_SETTINGS));
        }

        // 开始循环检测热点是否真正开启成功（每 500ms 检查一次，最多等待 12 秒）
        waitCount = 0;
        checkApStatusRunnable = new Runnable() {
            @Override
            public void run() {
                if (!isTaskRunning) return;
                waitCount++;

                if (isWifiApEnabled(MainActivity.this)) {
                    // 检测到热点已经真正开启！停止无障碍点击，等待 1 秒稳定后打开 CarPlay
                    isTaskRunning = false;
                    Toast.makeText(MainActivity.this, "热点开启成功！正在打开目标软件...", Toast.LENGTH_SHORT).show();
                    handler.postDelayed(() -> launchTargetApp(), 1000);
                } else if (waitCount < 24) {
                    // 还没开好，继续等无障碍服务点击并每 0.5 秒复查一次
                    handler.postDelayed(this, 500);
                } else {
                    isTaskRunning = false;
                    Toast.makeText(MainActivity.this, "开启热点超时，请检查热点页面结构", Toast.LENGTH_SHORT).show();
                }
            }
        };
        handler.postDelayed(checkApStatusRunnable, 800);
    }

    public static boolean isWifiApEnabled(Context context) {
        try {
            WifiManager wm = (WifiManager) context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            Method method = wm.getClass().getDeclaredMethod("isWifiApEnabled");
            method.setAccessible(true);
            return (Boolean) method.invoke(wm);
        } catch (Exception e) {
            return false;
        }
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

    private boolean isAccessibilitySettingsOn() {
        String service = getPackageName() + "/" + HotspotAccessibilityService.class.getCanonicalName();
        String enabledServices = Settings.Secure.getString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        return !TextUtils.isEmpty(enabledServices) && enabledServices.contains(service);
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
                    statusTv.setText("1. 无障碍服务状态：" + (isAccessibilitySettingsOn() ? "【已开启】" : "【未开启】")
                            + "\n2. 绑定的目标软件：" + selected);
                })
                .show();
    }
}
