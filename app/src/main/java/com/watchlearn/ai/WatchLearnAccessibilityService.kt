package com.watchlearn.ai

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent

class WatchLearnAccessibilityService : AccessibilityService() {
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Execute only typed, user-approved actions from the supported workflow
        // registry. Do not turn this into unrestricted autonomous UI control.
    }

    override fun onInterrupt() = Unit
}
