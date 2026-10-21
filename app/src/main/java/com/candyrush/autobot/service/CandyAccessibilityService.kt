package com.candyrush.autobot.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Path
import android.os.Build
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Dịch vụ Trợ Năng (Accessibility Service) Meta 2026
 * Đảm nhiệm:
 * 1. Chụp ảnh màn hình trực tiếp bằng Accessibility API (Android 11+)
 *    - HOÀN TOÀN KHÔNG CẦN MediaProjection / ghi màn hình
 *    - KHÔNG hiện thông báo cảnh báo phiền toái
 * 2. Thực hiện thao tác vuốt tráo kẹo mô phỏng ngón tay người dùng
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
    }

    override fun onInterrupt() {
        instance = null
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
    }

    /**
     * Chụp ảnh màn hình 1 lần bằng Accessibility API (Nhanh, im lặng, không popup)
     */
    fun takeScreenCapture(callback: (Bitmap?) -> Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                takeScreenshot(
                    Display.DEFAULT_DISPLAY,
                    mainExecutor,
                    object : TakeScreenshotCallback {
                        override fun onSuccess(screenshotResult: ScreenshotResult) {
                            try {
                                val hardwareBuffer = screenshotResult.hardwareBuffer
                                val colorSpace = screenshotResult.colorSpace
                                val hwBitmap = Bitmap.wrapHardwareBuffer(hardwareBuffer, colorSpace)
                                // Copy sang software bitmap để getPixel hoạt động trơn tru
                                val softBitmap = hwBitmap?.copy(Bitmap.Config.ARGB_8888, false)
                                hardwareBuffer.close()
                                callback(softBitmap)
                            } catch (e: Exception) {
                                callback(null)
                            }
                        }

                        override fun onFailure(errorCode: Int) {
                            callback(null)
                        }
                    }
                )
            } catch (e: Exception) {
                callback(null)
            }
        } else {
            callback(null)
        }
    }

    /**
     * Hàm suspend chụp màn hình dành cho Coroutines
     */
    suspend fun captureScreenSuspend(): Bitmap? = suspendCancellableCoroutine { cont ->
        takeScreenCapture { bmp ->
            if (cont.isActive) {
                cont.resume(bmp)
            }
        }
    }

    /**
     * Thực hiện thao tác vuốt tráo kẹo từ (startX, startY) đến (endX, endY)
     */
    fun performSwipe(
        startX: Float, startY: Float,
        endX: Float, endY: Float,
        durationMs: Long = 160L,
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

    /**
     * Thực hiện thao tác chạm (Tap) vào tọa độ (x, y)
     * Dùng để tự động kích hoạt Booster (Búa, Đĩa bay, Bàn tay) và đập Đầu Lâu (Sour Skull)
     */
    fun performTap(
        x: Float, y: Float,
        durationMs: Long = 80L,
        onComplete: (() -> Unit)? = null
    ) {
        val tapPath = Path().apply {
            moveTo(x, y)
        }
        val stroke = GestureDescription.StrokeDescription(tapPath, 0, durationMs)
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
