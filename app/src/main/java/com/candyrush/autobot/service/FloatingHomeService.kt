package com.candyrush.autobot.service

import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import com.candyrush.autobot.solver.CandyDetector
import com.candyrush.autobot.solver.Match3Solver
import kotlinx.coroutines.*

/**
 * Service hiển thị Nút Home Ảo (Floating Bubble / AssistiveTouch)
 * Nổi trên màn hình game, cho phép người dùng kéo thả tự do,
 * chạm một chạm để Bật / Tắt Auto chơi tự động.
 */
class FloatingHomeService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var floatingView: FrameLayout
    private lateinit var btnInner: FrameLayout
    private lateinit var iconText: TextView
    private lateinit var statusBadge: View
    private lateinit var layoutParams: WindowManager.LayoutParams

    private val detector = CandyDetector()
    private val solver = Match3Solver()

    private var isRunning = false
    private var job: Job? = null
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        createFloatingHomeButton()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun createFloatingHomeButton() {
        val layoutFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutFlag,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 20
            y = 500
        }

        // Tạo khung nút Home ảo
        val sizePx = dpToPx(64)
        floatingView = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(sizePx, sizePx)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#B31A1A24")) // Nền mờ kính tối
                setStroke(dpToPx(2), Color.parseColor("#66FFFFFF"))
            }
        }

        // Lớp nút bên trong
        val innerSize = dpToPx(50)
        btnInner = FrameLayout(this).apply {
            val lp = FrameLayout.LayoutParams(innerSize, innerSize).apply {
                gravity = Gravity.CENTER
            }
            layoutParams = lp
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#3F51B5")) // Màu tím xanh mặc định
            }
        }

        // Icon kẹo / nút play
        iconText = TextView(this).apply {
            text = "🍬"
            textSize = 24f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
        }
        btnInner.addView(iconText)
        floatingView.addView(btnInner)

        // Chấm trạng thái nhỏ (Active/Idle)
        val dotSize = dpToPx(12)
        statusBadge = View(this).apply {
            val lp = FrameLayout.LayoutParams(dotSize, dotSize).apply {
                gravity = Gravity.TOP or Gravity.END
                setMargins(dpToPx(4), dpToPx(4), dpToPx(4), dpToPx(4))
            }
            layoutParams = lp
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#9E9E9E")) // Chấm xám
            }
        }
        floatingView.addView(statusBadge)

        // Bắt sự kiện chạm và kéo thả
        setupTouchListener()

        windowManager.addView(floatingView, layoutParams)
    }

    private fun setupTouchListener() {
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var touchStartTime = 0L

        floatingView.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = layoutParams.x
                    initialY = layoutParams.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    touchStartTime = System.currentTimeMillis()
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()
                    layoutParams.x = initialX + dx
                    layoutParams.y = initialY + dy
                    windowManager.updateViewLayout(floatingView, layoutParams)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val touchDuration = System.currentTimeMillis() - touchStartTime
                    val dx = Math.abs(event.rawX - initialTouchX)
                    val dy = Math.abs(event.rawY - initialTouchY)

                    // Nếu là click nhẹ (không phải kéo)
                    if (touchDuration < 300 && dx < 15 && dy < 15) {
                        toggleAuto()
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun toggleAuto() {
        isRunning = !isRunning
        updateButtonState()

        if (isRunning) {
            Toast.makeText(this, "🟢 Candy Crush Auto: ĐÃ BẬT!", Toast.LENGTH_SHORT).show()
            startAutoLoop()
        } else {
            Toast.makeText(this, "⏸ Candy Crush Auto: TẠM DỪNG!", Toast.LENGTH_SHORT).show()
            stopAutoLoop()
        }
    }

    private fun updateButtonState() {
        mainHandler.post {
            val bg = btnInner.background as GradientDrawable
            val badgeBg = statusBadge.background as GradientDrawable

            if (isRunning) {
                bg.setColor(Color.parseColor("#E91E63")) // Hồng đậm nổi bật
                iconText.text = "⏸"
                badgeBg.setColor(Color.parseColor("#00E676")) // Xanh lá báo hiệu đang chạy
            } else {
                bg.setColor(Color.parseColor("#3F51B5")) // Indigo
                iconText.text = "▶"
                badgeBg.setColor(Color.parseColor("#9E9E9E")) // Xám
            }
        }
    }

    private fun startAutoLoop() {
        job?.cancel()
        job = scope.launch {
            while (isRunning && isActive) {
                val screenCapService = ScreenCaptureService.instance
                val accessService = CandyAccessibilityService.instance

                if (screenCapService == null || accessService == null) {
                    mainHandler.post {
                        Toast.makeText(this@FloatingHomeService, "⚠️ Vui lòng cấp quyền Trợ Năng và Chụp Màn Hình!", Toast.LENGTH_SHORT).show()
                    }
                    isRunning = false
                    updateButtonState()
                    break
                }

                val bitmap = screenCapService.captureCurrentBitmap()
                if (bitmap != null) {
                    val grid = detector.detectGrid(bitmap)
                    val bestMove = solver.findBestMove(grid)

                    if (bestMove != null) {
                        val (x1, y1) = detector.cellToScreenCoords(bestMove.r1, bestMove.c1, bitmap.width, bitmap.height)
                        val (x2, y2) = detector.cellToScreenCoords(bestMove.r2, bestMove.c2, bitmap.width, bitmap.height)

                        accessService.performSwipe(x1, y1, x2, y2, 200L)
                        // Chờ vuốt xong, kẹo nổ và rơi kẹo mới (1.4s)
                        delay(1400L)
                    } else {
                        // Không có nước đi hoặc đang xáo trộn
                        delay(700L)
                    }
                } else {
                    delay(500L)
                }
            }
        }
    }

    private fun stopAutoLoop() {
        job?.cancel()
        job = null
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopAutoLoop()
        if (::floatingView.isInitialized) {
            windowManager.removeView(floatingView)
        }
    }
}
