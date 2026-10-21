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
import kotlinx.coroutines.*
import kotlin.math.abs

/**
 * Service Nút Home Ảo - Động cơ Tự Vuốt Quét Thông Minh (Cascade Smart-Sweeper Meta 2026)
 * - HOÀN TOÀN KHÔNG CẦN GHI MÀN HÌNH (Không MediaProjection, không hỏi quyền nhạy cảm)
 * - Tự động quét và hoán đổi kẹo tuần hoàn khắp 9 hàng x 9 cột
 * - Ưu tiên nổ từ đáy bàn cờ (Bottom-Up) tạo chuỗi sụt đổ dây chuyền (Cascade Multiplier)
 * - Tuyệt đối không bao giờ bị kẹt lại 1 chỗ
 * - Nút ✕ màu đỏ để tắt hoàn toàn ngay lập tức
 */
class FloatingHomeService : Service() {

    companion object {
        const val ACTION_STOP_FLOATING = "com.candyrush.autobot.STOP_FLOATING"
        var isServiceRunning = false
            private set
    }

    private lateinit var windowManager: WindowManager
    private lateinit var floatingView: FrameLayout
    private lateinit var btnInner: FrameLayout
    private lateinit var iconText: TextView
    private lateinit var statusBadge: View
    private lateinit var btnExit: FrameLayout
    private lateinit var layoutParams: WindowManager.LayoutParams

    private var isRunning = false
    private var job: Job? = null
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        isServiceRunning = true
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        createFloatingHomeButton()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_FLOATING) {
            stopAutoAndExit()
            return START_NOT_STICKY
        }
        return START_STICKY
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
            x = 30
            y = 500
        }

        val containerSizePx = dpToPx(76)
        floatingView = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(containerSizePx, containerSizePx)
        }

        // Bong bóng chính (60dp)
        val bubbleSize = dpToPx(60)
        btnInner = FrameLayout(this).apply {
            val lp = FrameLayout.LayoutParams(bubbleSize, bubbleSize).apply {
                gravity = Gravity.CENTER
            }
            layoutParams = lp
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#3F51B5")) // Indigo mặc định
                setStroke(dpToPx(2), Color.WHITE)
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
        val dotSize = dpToPx(14)
        statusBadge = View(this).apply {
            val lp = FrameLayout.LayoutParams(dotSize, dotSize).apply {
                gravity = Gravity.TOP or Gravity.END
                setMargins(0, dpToPx(8), dpToPx(8), 0)
            }
            layoutParams = lp
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#9E9E9E")) // Xám
                setStroke(dpToPx(1), Color.WHITE)
            }
        }
        floatingView.addView(statusBadge)

        // NÚT ĐÓNG / TẮT BOT (✕ MÀU ĐỎ)
        val exitSize = dpToPx(24)
        btnExit = FrameLayout(this).apply {
            val lp = FrameLayout.LayoutParams(exitSize, exitSize).apply {
                gravity = Gravity.TOP or Gravity.START
                setMargins(dpToPx(2), dpToPx(2), 0, 0)
            }
            layoutParams = lp
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#D32F2F")) // Đỏ
                setStroke(dpToPx(1), Color.WHITE)
            }
            elevation = 10f

            val exitText = TextView(this@FloatingHomeService).apply {
                text = "✕"
                textSize = 13f
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
            }
            addView(exitText)

            setOnClickListener {
                stopAutoAndExit()
            }
        }
        floatingView.addView(btnExit)

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
                    val dx = abs(event.rawX - initialTouchX)
                    val dy = abs(event.rawY - initialTouchY)

                    // Chạm nhẹ: Bật / Tắt Auto
                    if (touchDuration < 350 && dx < 15 && dy < 15) {
                        toggleAuto()
                    } else if (touchDuration >= 700 && dx < 15 && dy < 15) {
                        // Nhấn giữ: Tắt bot
                        stopAutoAndExit()
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
                bg.setColor(Color.parseColor("#E91E63")) // Hồng nổi bật
                iconText.text = "⏸"
                badgeBg.setColor(Color.parseColor("#00E676")) // Xanh lá
            } else {
                bg.setColor(Color.parseColor("#3F51B5")) // Indigo
                iconText.text = "🍬"
                badgeBg.setColor(Color.parseColor("#9E9E9E")) // Xám
            }
        }
    }

    /**
     * VÒNG LẶP VUỐT QUÉT THÔNG MINH (CASCADE SMART-SWEEPER)
     * Quét tuần hoàn toàn bộ 9 hàng x 9 cột từ dưới lên trên
     */
    private fun startAutoLoop() {
        job?.cancel()
        job = scope.launch {
            val accessService = CandyAccessibilityService.instance
            if (accessService == null) {
                mainHandler.post {
                    Toast.makeText(this@FloatingHomeService, "⚠️ Vui lòng bật Dịch vụ Trợ Năng trong Cài đặt!", Toast.LENGTH_SHORT).show()
                }
                isRunning = false
                updateButtonState()
                return@launch
            }

            val metrics = resources.displayMetrics
            val screenW = metrics.widthPixels
            val screenH = metrics.heightPixels

            // Kích thước chuẩn vùng bàn cờ Candy Crush trên Samsung Note 10+
            val boardLeft = screenW * 0.05f
            val boardRight = screenW * 0.95f
            val boardTop = screenH * 0.23f
            val boardBottom = screenH * 0.77f

            val rows = 9
            val cols = 9
            val cellW = (boardRight - boardLeft) / cols
            val cellH = (boardBottom - boardTop) / rows

            // Danh sách các nước hoán đổi: Ưu tiên quét từ hàng đáy (hàng 8, 7, 6...) lên trên
            val swipeList = mutableListOf<Pair<Pair<Float, Float>, Pair<Float, Float>>>()

            for (r in (rows - 1) downTo 0) {
                // 1. Quét hoán đổi ngang (Trái sang Phải)
                for (c in 0 until (cols - 1)) {
                    val x1 = boardLeft + (c + 0.5f) * cellW
                    val y1 = boardTop + (r + 0.5f) * cellH
                    val x2 = boardLeft + (c + 1.5f) * cellW
                    val y2 = y1
                    swipeList.add(Pair(Pair(x1, y1), Pair(x2, y2)))
                }

                // 2. Quét hoán đổi dọc (Dưới lên Trên)
                if (r > 0) {
                    for (c in 0 until cols) {
                        val x1 = boardLeft + (c + 0.5f) * cellW
                        val y1 = boardTop + (r + 0.5f) * cellH
                        val x2 = x1
                        val y2 = boardTop + (r - 0.5f) * cellH
                        swipeList.add(Pair(Pair(x1, y1), Pair(x2, y2)))
                    }
                }
            }

            var moveIndex = 0

            while (isRunning && isActive) {
                val currentService = CandyAccessibilityService.instance ?: break

                val move = swipeList[moveIndex % swipeList.size]
                val (start, end) = move

                // Thực hiện vuốt dứt khoát 140ms
                currentService.performSwipe(start.first, start.second, end.first, end.second, 140L)

                // Nhịp nghỉ giữa các nước đi: 280ms
                // Thời gian này vừa đủ để game kiểm tra match hoặc lắc nhẹ trở lại nếu không ăn
                delay(280L)

                moveIndex++

                // Sau mỗi chu kỳ quét xong 1 lượt bàn cờ (khoảng 35 nước), dừng nhẹ 600ms cho kẹo cascade rơi xong
                if (moveIndex % 35 == 0) {
                    delay(600L)
                }
            }
        }
    }

    private fun stopAutoLoop() {
        job?.cancel()
        job = null
    }

    fun stopAutoAndExit() {
        stopAutoLoop()
        isRunning = false
        isServiceRunning = false

        mainHandler.post {
            Toast.makeText(this, "🛑 Đã tắt Candy Crush AutoBot hoàn toàn!", Toast.LENGTH_SHORT).show()
            try {
                if (::floatingView.isInitialized) {
                    windowManager.removeView(floatingView)
                }
            } catch (e: Exception) {
            }
            stopSelf()
        }
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }

    override fun onDestroy() {
        super.onDestroy()
        isServiceRunning = false
        stopAutoLoop()
        try {
            if (::floatingView.isInitialized) {
                windowManager.removeView(floatingView)
            }
        } catch (e: Exception) {
        }
    }
}
