package com.car.autohotspot;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
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

                if (MainActivity.isWifiApEnabled(ctx)) {
                    finishAndLaunch(ctx);
                    return;
                }

                if (instance != null) {
                    instance.scanAndAct(ctx);
                }

                if (taskActive && stepCount < 15) {
                    handler.postDelayed(this, 1200);
                } else {
                    taskActive = false;
                }
            }
        };
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

        Toast.makeText(ctx, "热点已开启！正在打开 CarPlay...", Toast.LENGTH_SHORT).show();
        handler.postDelayed(() -> MainActivity.launchApp(ctx, targetPkg), 600);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
    }

    private void scanAndAct(Context ctx) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;

        boolean isInHotspotDetailPage = hasText(root, "热点名称") || hasText(root, "安全性") || hasText(root, "热点密码");

        if (isInHotspotDetailPage) {
            if (hasText(root, "开启") && !hasExactCloseBar(root)) {
                finishAndLaunch(ctx);
                return;
            }

            Toast.makeText(ctx, "正在打开热点开关...", Toast.LENGTH_SHORT).show();

            // 1. 点击“关闭”文字所在的 SwitchBar 整条横幅及其所有子控件（包括右侧圆点 Switch）
            List<AccessibilityNodeInfo> closeNodes = root.findAccessibilityNodeInfosByText("关闭");
            if (closeNodes != null) {
                for (AccessibilityNodeInfo node : closeNodes) {
                    if (node.getText() != null && node.getText().toString().trim().equals("关闭")) {
                        // 不仅点它自己和父容器，还把父容器（那条深灰色横幅）里面的所有兄弟控件全部点一遍！
                        clickNodeParentsAndSiblings(node);
                    }
                }
            }

            // 2. 遍历整棵树，只要处于屏幕上方（Y < 300）的控件或开关，全部触发 ACTION_CLICK
            clickTopBarNodes(root);
            return;
        }

        // 如果在一级列表页，点进「WLAN 热点」
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

    /**
     * 关键突破：找到“关闭”文字后，顺着它的父容器（深灰色 SwitchBar），把父容器自己以及里面的右侧圆点开关全部触发点击！
     */
    private void clickNodeParentsAndSiblings(AccessibilityNodeInfo node) {
        AccessibilityNodeInfo cur = node;
        for (int i = 0; i < 4 && cur != null; i++) {
            cur.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            // 把同属这一横条的右侧开关子控件也全部点一遍
            for (int j = 0; j < cur.getChildCount(); j++) {
                AccessibilityNodeInfo child = cur.getChild(j);
                if (child != null) {
                    child.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                }
            }
            cur = cur.getParent();
        }
    }

    /**
     * 针对联发科 SwitchBar（位于屏幕顶部 Y: 100~220 区域）的所有开关和容器执行点击
     */
    private void clickTopBarNodes(AccessibilityNodeInfo node) {
        if (node == null) return;
        Rect r = new Rect();
        node.getBoundsInScreen(r);
        // 你的照片中，“关闭”灰色横条位于顶部标题栏正下方（Y 坐标在 110 到 220 之间），且排除掉左上角的返回箭头（X > 150）
        if (r.top >= 100 && r.bottom <= 240 && r.left > 150) {
            node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        }
        CharSequence cls = node.getClassName();
        if (cls != null && cls.toString().contains("Switch")) {
            if (!node.isChecked()) {
                node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                clickNodeAndParents(node);
            }
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            clickTopBarNodes(node.getChild(i));
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

    @Override
    public void onInterrupt() {}
}
