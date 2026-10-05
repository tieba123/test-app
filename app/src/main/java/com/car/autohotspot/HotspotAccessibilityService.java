package com.car.autohotspot;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Context;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Toast;

import java.util.List;

public class HotspotAccessibilityService extends AccessibilityService {

    private static HotspotAccessibilityService instance;
    private static volatile boolean taskActive = false;
    private static String targetPkg = MainActivity.DEFAULT_CARPLAY_PKG;
    private static final Handler handler = new Handler(Looper.getMainLooper());
    private static Runnable loopRunnable;
    private static int stepCount = 0;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
    }

    public static void startTask(final Context ctx, final String pkg) {
        targetPkg = pkg;
        taskActive = true;
        stepCount = 0;

        if (loopRunnable != null) handler.removeCallbacks(loopRunnable);

        loopRunnable = new Runnable() {
            @Override
            public void run() {
                if (!taskActive) return;
                stepCount++;

                // 1. 先检查系统底层热点是否已经开启成功
                if (MainActivity.isWifiApEnabled(ctx)) {
                    finishAndLaunch(ctx);
                    return;
                }

                // 2. 主动扫描当前屏幕执行点击
                if (instance != null) {
                    instance.scanAndAct(ctx);
                }

                // 最多循环尝试 15 次（每次间隔 1.2 秒，留足热点启动反应时间）
                if (taskActive && stepCount < 15) {
                    handler.postDelayed(this, 1200);
                } else {
                    taskActive = false;
                }
            }
        };
        // 跳转页面后等 800ms 让页面加载出来再开始第 1 次扫描
        handler.postDelayed(loopRunnable, 800);
    }

    public static void stopTask() {
        taskActive = false;
        if (loopRunnable != null) handler.removeCallbacks(loopRunnable);
    }

    private static void finishAndLaunch(final Context ctx) {
        if (!taskActive) return;
        taskActive = false;
        if (loopRunnable != null) handler.removeCallbacks(loopRunnable);

        Toast.makeText(ctx, "✅ 热点已开启！正在打开 CarPlay...", Toast.LENGTH_SHORT).show();
        handler.postDelayed(() -> MainActivity.launchApp(ctx, targetPkg), 600);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
    }

    private void scanAndAct(Context ctx) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;

        // 判断是否已经在「WLAN 热点」详情页（有“热点名称”或“安全性”或“热点密码”字样）
        boolean isInHotspotDetailPage = hasText(root, "热点名称") || hasText(root, "安全性") || hasText(root, "热点密码");

        if (isInHotspotDetailPage) {
            // 如果页面上已经变成“开启”且没有“关闭”横条，说明热点已经开了！
            if (hasText(root, "开启") && !hasExactCloseBar(root)) {
                finishAndLaunch(ctx);
                return;
            }

            Toast.makeText(ctx, "正在打开热点开关...", Toast.LENGTH_SHORT).show();

            // 【第 1 重保险】直接寻找页面上所有 Switch 开关控件以及可点击的整行横条进行触发
            clickAllSwitchesAndBars(root);

            // 【第 2 重保险】找到“关闭”文字节点，点击它及上层父容器，并提取真实 Y 坐标进行双点触摸
            int clickY = 155; // 默认高度（适配 1280x720 车机屏）
            List<AccessibilityNodeInfo> closeNodes = root.findAccessibilityNodeInfosByText("关闭");
            if (closeNodes != null) {
                for (AccessibilityNodeInfo node : closeNodes) {
                    if (node.getText() != null && node.getText().toString().trim().equals("关闭")) {
                        clickNodeAndParents(node);
                        Rect rect = new Rect();
                        node.getBoundsInScreen(rect);
                        if (rect.centerY() > 50 && rect.centerY() < 400) {
                            clickY = rect.centerY();
                        }
                    }
                }
            }

            // 【第 3 重保险】无障碍手势直接触摸点击右侧圆点开关 (1070, clickY) 及横条中部
            tapScreen(1070, clickY);
            handler.postDelayed(() -> tapScreen(640, clickY), 150);

            return;
        }

        // 如果在一级列表页（「热点和网络共享」），找到「WLAN 热点」点进去
        String[] entryWords = {"WLAN 热点", "Wi-Fi 热点", "便携式"};
        for (String w : entryWords) {
            List<AccessibilityNodeInfo> list = root.findAccessibilityNodeInfosByText(w);
            if (list != null) {
                for (AccessibilityNodeInfo n : list) {
                    if (clickNodeAndParents(n)) {
                        return;
                    }
                }
            }
        }
    }

    private boolean hasExactCloseBar(AccessibilityNodeInfo root) {
        List<AccessibilityNodeInfo> list = root.findAccessibilityNodeInfosByText("关闭");
        if (list == null) return false;
        for (AccessibilityNodeInfo n : list) {
            if (n.getText() != null && n.getText().toString().trim().equals("关闭")) {
                return true;
            }
        }
        return false;
    }

    private boolean hasText(AccessibilityNodeInfo root, String text) {
        List<AccessibilityNodeInfo> list = root.findAccessibilityNodeInfosByText(text);
        return list != null && !list.isEmpty();
    }

    private void clickAllSwitchesAndBars(AccessibilityNodeInfo node) {
        if (node == null) return;
        CharSequence cls = node.getClassName();
        if (cls != null) {
            String className = cls.toString();
            // 只要是 Switch 控件或 SwitchBar 容器，且未处于开启状态，直接点它和它的父布局
            if (className.contains("Switch") || (node.isCheckable() && !node.isChecked())) {
                node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                clickNodeAndParents(node);
                Rect r = new Rect();
                node.getBoundsInScreen(r);
                if (r.centerX() > 0 && r.centerY() > 0) {
                    tapScreen(r.centerX(), r.centerY());
                }
            }
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            clickAllSwitchesAndBars(node.getChild(i));
        }
    }

    private boolean clickNodeAndParents(AccessibilityNodeInfo node) {
        boolean anySuccess = false;
        AccessibilityNodeInfo cur = node;
        for (int i = 0; i < 4 && cur != null; i++) {
            if (cur.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                anySuccess = true;
            }
            cur = cur.getParent();
        }
        return anySuccess;
    }

    private void tapScreen(int x, int y) {
        try {
            Path path = new Path();
            path.moveTo(x, y);
            GestureDescription.Builder builder = new GestureDescription.Builder();
            GestureDescription gesture = builder
                    .addStroke(new GestureDescription.StrokeDescription(path, 0, 100))
                    .build();
            dispatchGesture(gesture, null, null);
        } catch (Exception ignored) {}
    }

    @Override
    public void onInterrupt() {}
}
