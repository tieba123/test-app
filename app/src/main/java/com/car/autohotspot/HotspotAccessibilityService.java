package com.car.autohotspot;

import android.accessibilityservice.AccessibilityService;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import java.util.List;

public class HotspotAccessibilityService extends AccessibilityService {

    private static long lastActionTime = 0;
    private static boolean enteredSubPage = false;

    public static void resetState() {
        lastActionTime = 0;
        enteredSubPage = false;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (!MainActivity.isTaskRunning) return;

        // 如果热点已经开启成功，立刻停止任何点击动作
        if (MainActivity.isWifiApEnabled(this)) {
            MainActivity.isTaskRunning = false;
            return;
        }

        // 限制点击频率（每次动作间隔至少 800ms，防止连点把刚打开的热点又关掉）
        long now = System.currentTimeMillis();
        if (now - lastActionTime < 800) return;

        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;

        // 【策略 1】如果当前页面直接有未勾选的 Switch 开关控件（且属于热点开关或主开关），直接点它或它的整行父布局
        if (findAndClickSwitch(root)) {
            lastActionTime = now;
            return;
        }

        // 【策略 2】如果当前在「热点和网络共享」一级列表页（没有直接的开关，需要先点进「WLAN 热点」子页面）
        if (!enteredSubPage) {
            String[] entryWords = {"WLAN 热点", "Wi-Fi 热点", "便携式", "热点设置", "Hotspot"};
            for (String word : entryWords) {
                List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByText(word);
                if (nodes != null) {
                    for (AccessibilityNodeInfo node : nodes) {
                        if (clickClickableParent(node)) {
                            enteredSubPage = true;
                            lastActionTime = now;
                            return;
                        }
                    }
                }
            }
        }
    }

    /**
     * 遍历整棵 UI 树，寻找未开启的 Switch / CheckBox 开关控件并点击
     */
    private boolean findAndClickSwitch(AccessibilityNodeInfo node) {
        if (node == null) return false;

        CharSequence className = node.getClassName();
        if (className != null) {
            String cls = className.toString();
            // 匹配安卓标准开关控件 Switch / SwitchCompat / CheckBox
            if (cls.contains("Switch") || cls.contains("CompoundButton") || (node.isCheckable() && !cls.contains("Radio"))) {
                // 如果开关当前处于“关闭（未选中）”状态
                if (!node.isChecked()) {
                    // 先排除掉“USB 共享网络”和“蓝牙共享网络”的开关
                    AccessibilityNodeInfo rowParent = node.getParent();
                    if (rowParent != null && rowParent.getParent() != null) {
                        String rowText = getNodeAllText(rowParent.getParent());
                        if (rowText.contains("USB") || rowText.contains("蓝牙") || rowText.contains("Bluetooth")) {
                            return false;
                        }
                    }
                    // 优先点击开关自身，如果开关自身不可直接点击，则点击它所在的整行条目
                    if (node.isClickable()) {
                        node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                        return true;
                    } else if (clickClickableParent(node)) {
                        return true;
                    }
                }
            }
        }

        for (int i = 0; i < node.getChildCount(); i++) {
            if (findAndClickSwitch(node.getChild(i))) {
                return true;
            }
        }
        return false;
    }

    private String getNodeAllText(AccessibilityNodeInfo node) {
        if (node == null) return "";
        StringBuilder sb = new StringBuilder();
        if (node.getText() != null) sb.append(node.getText().toString()).append(" ");
        for (int i = 0; i < node.getChildCount(); i++) {
            sb.append(getNodeAllText(node.getChild(i)));
        }
        return sb.toString();
    }

    private boolean clickClickableParent(AccessibilityNodeInfo node) {
        AccessibilityNodeInfo cur = node;
        for (int i = 0; i < 5 && cur != null; i++) {
            if (cur.isClickable()) {
                cur.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                return true;
            }
            cur = cur.getParent();
        }
        return false;
    }

    @Override
    public void onInterrupt() {}
}
