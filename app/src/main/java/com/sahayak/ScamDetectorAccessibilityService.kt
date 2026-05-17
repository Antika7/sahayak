package com.sahayak

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.text.InputType
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

// Accessibility Service that reads the current window's view hierarchy on demand
class ScamDetectorAccessibilityService : AccessibilityService() {

    companion object {
        var instance: ScamDetectorAccessibilityService? = null

        private val INTERACTIVE_CLASSES = setOf(
            "android.widget.Button",
            "android.widget.ImageButton",
            "android.widget.CheckBox",
            "android.widget.Switch",
            "android.widget.ToggleButton"
        )
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        serviceInfo = serviceInfo.apply {
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        instance = null
    }

    fun getScreenText(): String = getScreenContext().visibleText

    // Builds a ScreenContext from the active window's view hierarchy.
    fun getScreenContext(): ScreenContext {
        val root = rootInActiveWindow ?: return ScreenContext("", null, false, false, emptyList())
        val appPackage = root.packageName?.toString()
        val collector = NodeCollector()
        try {
            collectNodes(root, collector)
        } finally {
            root.recycle()
        }

        val fullText = collector.text.toString().trim()
        return ScreenContext(
            visibleText = fullText,
            appPackage = appPackage,
            hasPasswordField = collector.hasPassword,
            hasPaymentContext = PrivacyFilter.containsPaymentPattern(fullText),
            interactiveElements = collector.interactiveLabels
        )
    }

    private class NodeCollector {
        val text = StringBuilder()
        val interactiveLabels = mutableListOf<String>()
        var hasPassword = false
    }

    private fun collectNodes(node: AccessibilityNodeInfo?, collector: NodeCollector) {
        if (node == null) return

        val className = node.className?.toString().orEmpty()
        val inputType = node.inputType

        if (isPasswordField(inputType)) {
            collector.hasPassword = true
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                collectNodes(child, collector)
                child.recycle()
            }
            return
        }

        val text = node.text?.toString()
        val desc = node.contentDescription?.toString()
        val label = text ?: desc

        if (!text.isNullOrBlank()) {
            collector.text.appendLine(text)
        } else if (!desc.isNullOrBlank()) {
            collector.text.appendLine(desc)
        }

        if (!label.isNullOrBlank() && collector.interactiveLabels.size < 20 && isInteractiveElement(className, node.isClickable)) {
            collector.interactiveLabels.add(label.take(60))
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectNodes(child, collector)
            child.recycle()
        }
    }

    private fun isPasswordField(inputType: Int): Boolean {
        if (inputType == 0) return false
        val masked = inputType and InputType.TYPE_MASK_VARIATION
        return masked == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                masked == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
                masked == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD ||
                masked == InputType.TYPE_NUMBER_VARIATION_PASSWORD
    }

    private fun isInteractiveElement(className: String, isClickable: Boolean): Boolean =
        className in INTERACTIVE_CLASSES || (isClickable && className.contains("TextView"))
}
