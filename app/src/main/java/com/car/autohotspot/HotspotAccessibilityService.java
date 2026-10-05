package com.car.autohotspot;

import android.accessibilityservice.AccessibilityService;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import java.util.List;

public class HotspotAccessibilityService extends AccessibilityService {

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (!MainActivity.needAutoClickHotspot) return;

        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;

        // 如果已经在热点总控页或子页，自动寻找关闭状态的开关并点击
        String[] keywords = {"便携式", "WLAN 热点", "Wi-Fi 热点", "热点", "关闭", "OFF"};
        for (String kw : keywords) {
            List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByText(kw);
            if (nodes != null) {
                for (AccessibilityNodeInfo node : nodes) {
                    if (clickNodeOrParent(node)) {
                        MainActivity.needAutoClickHotspot = false;
                        return;
                    }
                }
            }
        }
    }

    private boolean clickNodeOrParent(AccessibilityNodeInfo node) {
        AccessibilityNodeInfo cur = node;
        for (int i = 0; i < 4 && cur != null; i++) {
            if (cur.isCheckable() && !cur.isChecked()) {
                cur.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                return true;
            }
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
