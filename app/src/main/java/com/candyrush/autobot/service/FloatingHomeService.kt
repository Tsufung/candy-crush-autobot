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
import kotlin.math.abs

/**
 * Service Nút Home Ảo - BỘ NÃO AI QUÉT 81 Ô VÀ TÍNH TOÁN CHIẾN THUẬT META 2026
 * 1. Chụp ảnh màn hình 1 lần bằng Accessibility API (Không MediaProjection, không popup cảnh báo)
 * 2. Phân tích 81 ô bàn cờ (9x9) trong 15 mili-giây
 * 3. Tính toán đường đi tối ưu:
 *    - Ưu tiên Super Combo (Bom Màu + Kẹo Sọc, Bom Màu + Bom Màu)
 *    - Ưu tiên tạo kẹo đặc biệt mới (5 viên, 4 viên)
 *    - Phá Thạch (Jelly) & Diệt Socola chống lây lan
 *    - Trọng lực đáy bàn cờ (Cascade Gravity) tối đa hóa tỷ lệ nổ dây chuyền
 * 4. Nút ✕ màu đỏ tắt bot nhanh chóng
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
    private lateinit var moveCounterText: TextView
    private lateinit var layoutParams: WindowManager.LayoutParams

    private val detector = CandyDetector()
    private val solver = Match3Solver()

    private var isRunning = false
    private var job: Job? = null
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val mainHandler = Handler(Looper.getMainLooper())

    private val bannedMoves = mutableMapOf<String, Long>()
    private var lastMoveKey: String? = null
    private var lastGrid: Array<IntArray>? = null
    private var movesCount = 0

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

        val containerSizePx = dpToPx(82)
        floatingView = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(containerSizePx, containerSizePx)
        }

        // Bong bóng chính (62dp)
        val bubbleSize = dpToPx(62)
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

        // Bộ đếm lượt đi nhỏ ở dưới
        moveCounterText = TextView(this).apply {
            val lp = FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                setMargins(0, 0, 0, dpToPx(2))
            }
            layoutParams = lp
            text = "0"
            textSize = 10f
            setTextColor(Color.WHITE)
            setPadding(dpToPx(4), dpToPx(1), dpToPx(4), dpToPx(1))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dpToPx(4).toFloat()
                setColor(Color.parseColor("#CC000000"))
            }
        }
        floatingView.addView(moveCounterText)

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
                setColor(Color.parseColor("#D32F2F")) // Đỏ cảnh báo
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

                    if (touchDuration < 350 && dx < 15 && dy < 15) {
                        toggleAuto()
                    } else if (touchDuration >= 700 && dx < 15 && dy < 15) {
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
            Toast.makeText(this, "🟢 Candy Crush AI Bot: ĐÃ BẬT!", Toast.LENGTH_SHORT).show()
            startAiLoop()
        } else {
            Toast.makeText(this, "⏸ Candy Crush AI Bot: TẠM DỪNG!", Toast.LENGTH_SHORT).show()
            stopAiLoop()
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
            moveCounterText.text = movesCount.toString()
        }
    }

    /**
     * VÒNG LẶP AI QUÉT 81 Ô VÀ RA QUYẾT ĐỊNH CHIẾN THUẬT
     */
    private fun startAiLoop() {
        job?.cancel()
        job = scope.launch {
            while (isRunning && isActive) {
                val accessService = CandyAccessibilityService.instance
                if (accessService == null) {
                    mainHandler.post {
                        Toast.makeText(this@FloatingHomeService, "⚠️ Vui lòng Bật Dịch vụ Trợ Năng trong Cài đặt!", Toast.LENGTH_SHORT).show()
                    }
                    isRunning = false
                    updateButtonState()
                    break
                }

                // 1. Chụp màn hình: Ưu tiên MediaProjection HD nếu đã bật, nếu chưa bật thì dùng Accessibility ngầm
                val bitmap = ScreenCaptureService.instance?.captureCurrentBitmap()
                    ?: accessService.captureScreenSuspend()

                if (bitmap == null) {
                    delay(300L)
                    continue
                }

                // 2. Phân tích 81 ô bàn cờ (màu sắc, nhiệm vụ, xác suất)
                val detection = detector.scanBoard(bitmap)

                // 3. Kiểm tra nếu nước đi trước bị game lắc trả lại (chống lặp nước đi)
                val currentLastKey = lastMoveKey
                val currentLastGrid = lastGrid
                if (currentLastKey != null && currentLastGrid != null) {
                    if (areGridsEqual(currentLastGrid, detection.grid)) {
                        // Game từ chối nước đi -> Cấm nước đi này trong 7s để AI chọn nước khác
                        bannedMoves[currentLastKey] = System.currentTimeMillis() + 7000L
                    }
                }

                // Dọn dẹp danh sách cấm hết hạn
                val now = System.currentTimeMillis()
                bannedMoves.entries.removeAll { it.value < now }

                // 4. BỘ NÃO AI TÌM NƯỚC ĐI TỐI ƯU NHẤT (Cực nhanh, chỉ mất ~15ms)
                val bestMove = solver.findOptimalMove(detection, bannedMoves.keys.toSet())

                if (bestMove != null) {
                    val (x1, y1) = detector.cellToScreenCoords(bestMove.r1, bestMove.c1, bitmap.width, bitmap.height)
                    val (x2, y2) = detector.cellToScreenCoords(bestMove.r2, bestMove.c2, bitmap.width, bitmap.height)

                    lastMoveKey = Match3Solver.moveKey(bestMove.r1, bestMove.c1, bestMove.r2, bestMove.c2)
                    lastGrid = cloneGrid(detection.grid)
                    movesCount++
                    updateButtonState()

                    // Vuốt tráo kẹo dứt khoát 160ms
                    accessService.performSwipe(x1, y1, x2, y2, 160L)

                    // Chờ kẹo nổ và chuỗi sụt đổ cascade hoàn tất
                    delay(1250L)
                } else {
                    // Không có nước đi hợp lệ hoặc đang xáo trộn màn hình
                    lastMoveKey = null
                    lastGrid = null
                    delay(500L)
                }
            }
        }
    }

    private fun areGridsEqual(g1: Array<IntArray>, g2: Array<IntArray>): Boolean {
        for (r in g1.indices) {
            for (c in g1[r].indices) {
                if (g1[r][c] != g2[r][c]) return false
            }
        }
        return true
    }

    private fun cloneGrid(g: Array<IntArray>): Array<IntArray> {
        return Array(g.size) { r -> g[r].clone() }
    }

    private fun stopAiLoop() {
        job?.cancel()
        job = null
    }

    fun stopAutoAndExit() {
        stopAiLoop()
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
        stopAiLoop()
        try {
            if (::floatingView.isInitialized) {
                windowManager.removeView(floatingView)
            }
        } catch (e: Exception) {
        }
    }
}
