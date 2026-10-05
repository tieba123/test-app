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

        // 后台常驻定时器：每 700 毫秒主動扫描一次屏幕并推进状态，不依赖界面焦点
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

                // 最多循环尝试 20 次（约 14 秒）
                if (taskActive && stepCount < 20) {
                    handler.postDelayed(this, 700);
                } else {
                    taskActive = false;
                }
            }
        };
        handler.postDelayed(loopRunnable, 600);
    }

    public static void stopTask() {
        taskActive = false;
        if (loopRunnable != null) handler.removeCallbacks(loopRunnable);
    }

    private static void finishAndLaunch(final Context ctx) {
        if (!taskActive) return;
        taskActive = false;
        if (loopRunnable != null) handler.removeCallbacks(loopRunnable);

        Toast.makeText(ctx, "热点已开启，正在打开 CarPlay...", Toast.LENGTH_SHORT).show();
        // 热点刚打开时等 800ms 让网络接口就绪，随后立即启动 CarPlay
        handler.postDelayed(() -> MainActivity.launchApp(ctx, targetPkg), 800);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // 已有 700ms 主动轮询定时器驱动，这里无需重复触发，防止连点
    }

    private void scanAndAct(Context ctx) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;

        // 看看当前是不是已经进到了第二张图的「WLAN 热点」详情页（有“热点名称”或“安全性”字样）
        boolean isInHotspotDetailPage = hasText(root, "热点名称") || hasText(root, "安全性") || hasText(root, "热点密码");

        if (isInHotspotDetailPage) {
            // 如果页面上已经显示“开启”或“已开启”，说明热点已经打开了！立即跳去 CarPlay！
            if (hasText(root, "开启") && !hasText(root, "关闭")) {
                finishAndLaunch(ctx);
                return;
            }

            // 如果页面上显示“关闭”（如你第二张照片所示），立刻点击“关闭”所在的 SwitchBar 横条！
            List<AccessibilityNodeInfo> closeNodes = root.findAccessibilityNodeInfosByText("关闭");
            if (closeNodes != null && !closeNodes.isEmpty()) {
                for (AccessibilityNodeInfo node : closeNodes) {
                    // 排除最底部高级说明里的“自动关闭热点”那行小字
                    CharSequence txt = node.getText();
                    if (txt != null && txt.toString().trim().equals("关闭")) {
                        // 动作 A：尝试节点树点击（点它自身、父容器以及同级的 Switch）
                        boolean clicked = clickNodeAndParents(node);
                        // 动作 B：同时获取“关闭”这一栏在屏幕上的真实坐标，直接模拟手指点击右侧开关位置！
                        Rect rect = new Rect();
                        node.getBoundsInScreen(rect);
                        int clickY = rect.centerY() > 0 ? rect.centerY() : 155;
                        // 点击横条正中间偏右的位置（SwitchBar 整条任何位置都能点开）
                        tapScreen(640, clickY);
                        return;
                    }
                }
            }

            // 保底：如果没找到“关闭”文字节点，直接点击未勾选的 Switch 控件或固定坐标 (1080, 155)
            if (!clickUncheckedSwitch(root)) {
                tapScreen(1080, 155);
            }
            return;
        }

        // 如果还没进到「WLAN 热点」详情页（在一级目录列表），找到「WLAN 热点」点进去
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

    private boolean hasText(AccessibilityNodeInfo root, String text) {
        List<AccessibilityNodeInfo> list = root.findAccessibilityNodeInfosByText(text);
        if (list == null || list.isEmpty()) return false;
        for (AccessibilityNodeInfo n : list) {
            if (n.getText() != null) {
                String t = n.getText().toString().trim();
                // 避免把“自动关闭热点”误判为“关闭”状态
                if (text.equals("关闭") && t.contains("自动关闭")) continue;
                return true;
            }
        }
        return false;
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

    private boolean clickUncheckedSwitch(AccessibilityNodeInfo node) {
        if (node == null) return false;
        if (node.isCheckable() && !node.isChecked()) {
            Rect r = new Rect();
            node.getBoundsInScreen(r);
            if (r.centerX() > 0 && r.centerY() > 0) {
                tapScreen(r.centerX(), r.centerY());
                return true;
            }
            return clickNodeAndParents(node);
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            if (clickUncheckedSwitch(node.getChild(i))) return true;
        }
        return false;
    }

    /**
     * 使用 Android 7.0+ 原生无障碍手势直接模拟手指点击屏幕坐标
     */
    private void tapScreen(int x, int y) {
        try {
            Path path = new Path();
            path.moveTo(x, y);
            GestureDescription.Builder builder = new GestureDescription.Builder();
            GestureDescription gesture = builder
                    .addStroke(new GestureDescription.StrokeDescription(path, 0, 80))
                    .build();
            dispatchGesture(gesture, null, null);
        } catch (Exception ignored) {}
    }

    @Override
    public void onInterrupt() {}
}
