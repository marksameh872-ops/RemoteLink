package com.remotelink

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class RemoteAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: RemoteAccessibilityService? = null
    }

    override fun onServiceConnected() {
        instance = this
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    fun tap(x: Float, y: Float, durationMs: Long) {
        val path = Path().apply { moveTo(x, y) }
        dispatch(path, durationMs)
    }

    fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long) {
        val path = Path().apply {
            moveTo(x1, y1)
            lineTo(x2, y2)
        }
        dispatch(path, durationMs)
    }

    private fun dispatch(path: Path, durationMs: Long) {
        val stroke = GestureDescription.StrokeDescription(path, 0, maxOf(durationMs, 1L))
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        dispatchGesture(gesture, null, null)
    }

    private fun currentText(node: AccessibilityNodeInfo): String {
        if (node.isShowingHintText) return ""
        return node.text?.toString() ?: ""
    }

    private fun setText(node: AccessibilityNodeInfo, value: String) {
        val args = Bundle()
        args.putCharSequence(
            AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
            value
        )
        node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    fun typeText(text: String) {
        val node = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return
        setText(node, currentText(node) + text)
    }

    fun backspace() {
        val node = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return
        setText(node, currentText(node).dropLast(1))
    }
}
