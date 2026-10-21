package com.candyrush.autobot.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.view.accessibility.AccessibilityEvent

/**
 * Dịch vụ Trợ Năng (Accessibility Service)
 * Đảm nhiệm việc mô phỏng cử chỉ vuốt tay (gesture swipe) tự động trên màn hình Android
 * mà không cần root thiết bị.
 */
class CandyAccessibilityService : AccessibilityService() {

    companion object {
        var instance: CandyAccessibilityService? = null
            private set

        fun isReady(): Boolean = instance != null
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Không cần xử lý sự kiện giao diện thông thường
    }

    override fun onInterrupt() {
        instance = null
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
    }

    /**
     * Thực hiện thao tác vuốt tráo kẹo từ (startX, startY) đến (endX, endY)
     */
    fun performSwipe(
        startX: Float, startY: Float,
        endX: Float, endY: Float,
        durationMs: Long = 180L,
        onComplete: (() -> Unit)? = null
    ) {
        val swipePath = Path().apply {
            moveTo(startX, startY)
            lineTo(endX, endY)
        }

        val stroke = GestureDescription.StrokeDescription(swipePath, 0, durationMs)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()

        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                super.onCompleted(gestureDescription)
                onComplete?.invoke()
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                super.onCancelled(gestureDescription)
                onComplete?.invoke()
            }
        }, null)
    }
}
