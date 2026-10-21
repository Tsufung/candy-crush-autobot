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
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.candyrush.autobot.solver.CandyDetector
import com.candyrush.autobot.solver.Match3Solver
import kotlinx.coroutines.*
import kotlin.math.abs

/**
 * DỊCH VỤ NÚT NỔI THÔNG MINH CANDY CRUSH (V1.0.5 SMART BOOSTER 999)
 * - Tự động quét 81 ô và tính toán combo
 * - Khắc phục hoàn toàn lỗi kẹo cá
 * - Tự động bấm Booster 999 lần (Búa kẹo mút gõ Đầu Lâu, Đĩa bay UFO)
 * - Nút bấm Búa 🔨 và Đĩa bay 🛸 tiện lợi trên menu nổi
 */
class FloatingHomeService : Service() {

    companion object {
        const val ACTION_STOP_FLOATING = "action_stop_floating"
        var isServiceRunning: Boolean = false
            private set
    }

    private lateinit var windowManager: WindowManager
    private lateinit var floatingView: FrameLayout
    private lateinit var layoutParams: WindowManager.LayoutParams

    private lateinit var btnInner: FrameLayout
    private lateinit var iconText: TextView
    private lateinit var statusBadge: View
    private lateinit var moveCounterText: TextView
    private lateinit var btnExit: FrameLayout
    private lateinit var btnHammerQuick: FrameLayout
    private lateinit var btnUfoQuick: FrameLayout

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
    private var latestDetection: CandyDetector.DetectionResult? = null

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
            y = 450
        }

        // Khung chứa toàn bộ (125dp x 82dp)
        val containerWPx = dpToPx(130)
        val containerHPx = dpToPx(82)
        floatingView = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(containerWPx, containerHPx)
        }

        val mainRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
        }

        // 1. Bong bóng tròn chính (60dp)
        val bubbleSize = dpToPx(60)
        btnInner = FrameLayout(this).apply {
            val lp = LinearLayout.LayoutParams(bubbleSize, bubbleSize).apply {
                setMargins(dpToPx(4), dpToPx(4), dpToPx(6), dpToPx(4))
            }
            layoutParams = lp
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#3F51B5")) // Indigo mặc định
                setStroke(dpToPx(2), Color.WHITE)
            }
        }

        iconText = TextView(this).apply {
            text = "🍬"
            textSize = 24f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
        }
        btnInner.addView(iconText)

        // Chấm trạng thái nhỏ (Active/Idle)
        val dotSize = dpToPx(13)
        statusBadge = View(this).apply {
            val lp = FrameLayout.LayoutParams(dotSize, dotSize).apply {
                gravity = Gravity.TOP or Gravity.END
                setMargins(0, dpToPx(4), dpToPx(4), 0)
            }
            layoutParams = lp
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#9E9E9E"))
                setStroke(dpToPx(1), Color.WHITE)
            }
        }
        btnInner.addView(statusBadge)

        // Bộ đếm lượt đi nhỏ ở đáy nút
        moveCounterText = TextView(this).apply {
            val lp = FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                setMargins(0, 0, 0, dpToPx(2))
            }
            layoutParams = lp
            text = "0"
            textSize = 9.5f
            setTextColor(Color.WHITE)
            setPadding(dpToPx(4), dpToPx(1), dpToPx(4), dpToPx(1))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dpToPx(4).toFloat()
                setColor(Color.parseColor("#CC000000"))
            }
        }
        btnInner.addView(moveCounterText)
        mainRow.addView(btnInner)

        // 2. Cột công cụ Booster nhanh bên cạnh (Búa 🔨 & Đĩa bay 🛸)
        val boosterColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }

        val actionBtnSize = dpToPx(34)

        // Nút Búa thần kỳ (Lollipop Hammer)
        btnHammerQuick = FrameLayout(this).apply {
            val lp = LinearLayout.LayoutParams(actionBtnSize, actionBtnSize).apply {
                setMargins(0, 0, 0, dpToPx(4))
            }
            layoutParams = lp
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#FF9800")) // Cam rực rỡ
                setStroke(dpToPx(1.5f), Color.WHITE)
            }
            val t = TextView(this@FloatingHomeService).apply {
                text = "🔨"
                textSize = 15f
                gravity = Gravity.CENTER
            }
            addView(t)
            setOnClickListener {
                triggerAutoHammerOnSkullOrStuck(5)
            }
        }
        boosterColumn.addView(btnHammerQuick)

        // Nút Đĩa bay UFO (UFO Booster)
        btnUfoQuick = FrameLayout(this).apply {
            val lp = LinearLayout.LayoutParams(actionBtnSize, actionBtnSize)
            layoutParams = lp
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#9C27B0")) // Tím
                setStroke(dpToPx(1.5f), Color.WHITE)
            }
            val t = TextView(this@FloatingHomeService).apply {
                text = "🛸"
                textSize = 15f
                gravity = Gravity.CENTER
            }
            addView(t)
            setOnClickListener {
                triggerAutoUfo()
            }
        }
        boosterColumn.addView(btnUfoQuick)

        mainRow.addView(boosterColumn)
        floatingView.addView(mainRow)

        // 3. Nút Đóng / Tắt bot (✕ màu đỏ)
        val exitSize = dpToPx(22)
        btnExit = FrameLayout(this).apply {
            val lp = FrameLayout.LayoutParams(exitSize, exitSize).apply {
                gravity = Gravity.TOP or Gravity.START
                setMargins(0, 0, 0, 0)
            }
            layoutParams = lp
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#D32F2F"))
                setStroke(dpToPx(1), Color.WHITE)
            }
            val exitText = TextView(this@FloatingHomeService).apply {
                text = "✕"
                textSize = 12f
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
            }
            addView(exitText)
            setOnClickListener {
                stopAutoAndExit()
            }
        }
        floatingView.addView(btnExit)

        setupTouchListener()
        windowManager.addView(floatingView, layoutParams)
    }

    private fun setupTouchListener() {
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var touchStartTime = 0L

        btnInner.setOnTouchListener { _, event ->
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
     * VÒNG LẶP AI QUÉT 81 Ô VÀ TỰ ĐỘNG THỰC HIỆN NƯỚC ĐI HOẶC BOOSTER
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

                // 1. Chụp màn hình
                val bitmap = ScreenCaptureService.instance?.captureCurrentBitmap()
                    ?: accessService.captureScreenSuspend()

                if (bitmap == null) {
                    delay(300L)
                    continue
                }

                // 2. Phân tích 81 ô bàn cờ
                val detection = detector.scanBoard(bitmap)
                latestDetection = detection

                // 3. Kiểm tra chống kẹt lặp nước đi
                val currentLastKey = lastMoveKey
                val currentLastGrid = lastGrid
                if (currentLastKey != null && currentLastGrid != null) {
                    if (areGridsEqual(currentLastGrid, detection.grid)) {
                        bannedMoves[currentLastKey] = System.currentTimeMillis() + 8000L
                    }
                }

                val now = System.currentTimeMillis()
                bannedMoves.entries.removeAll { it.value < now }

                // 4. Tìm nước đi tối ưu
                val bestMove = solver.findOptimalMove(detection, bannedMoves.keys.toSet())

                if (bestMove != null) {
                    val (x1, y1) = detector.cellToScreenCoords(bestMove.r1, bestMove.c1, bitmap.width, bitmap.height)
                    val (x2, y2) = detector.cellToScreenCoords(bestMove.r2, bestMove.c2, bitmap.width, bitmap.height)

                    lastMoveKey = Match3Solver.moveKey(bestMove.r1, bestMove.c1, bestMove.r2, bestMove.c2)
                    lastGrid = cloneGrid(detection.grid)
                    movesCount++
                    updateButtonState()

                    // Vuốt tráo kẹo 160ms
                    accessService.performSwipe(x1, y1, x2, y2, 160L)
                    delay(1250L)
                } else {
                    // KẸT BÀN CỜ / KHÔNG CÒN NƯỚC ĂN: TỰ ĐỘNG BẬT BOOSTER PHÁ THẾ KẸT!
                    lastMoveKey = null
                    lastGrid = null

                    if (detection.skullCoords != null) {
                        mainHandler.post {
                            Toast.makeText(this@FloatingHomeService, "🔨 Bàn cờ kẹt! Tự động gõ Búa vào Đầu Lâu!", Toast.LENGTH_SHORT).show()
                        }
                        triggerAutoHammerOnSkullOrStuck(1)
                        delay(1200L)
                    } else {
                        delay(600L)
                    }
                }
            }
        }
    }

    /**
     * TỰ ĐỘNG GÕ BÚA KẸO MÚT VÀO ĐẦU LÂU HOẶC Ô KẸT (HỖ TRỢ 999 LẦN)
     */
    fun triggerAutoHammerOnSkullOrStuck(hits: Int = 5) {
        val accessService = CandyAccessibilityService.instance ?: return
        val metrics = resources.displayMetrics
        val screenW = metrics.widthPixels
        val screenH = metrics.heightPixels

        val (hammerX, hammerY) = detector.getBoosterScreenCoords("HAMMER", screenW, screenH)

        val skullRow = latestDetection?.skullCoords?.first ?: 6
        val skullCol = latestDetection?.skullCoords?.second ?: 0
        val (targetX, targetY) = detector.cellToScreenCoords(skullRow, skullCol, screenW, screenH)

        scope.launch {
            mainHandler.post {
                Toast.makeText(this@FloatingHomeService, "🔨 Đang gõ Búa $hits phát vào Đầu Lâu!", Toast.LENGTH_SHORT).show()
            }
            for (i in 1..hits) {
                // 1. Chạm vào biểu tượng Búa ở thanh đáy
                accessService.performTap(hammerX, hammerY, 80L)
                delay(400L)

                // 2. Chạm vào vị trí Đầu Lâu trên bàn cờ
                accessService.performTap(targetX, targetY, 80L)
                delay(800L)
            }
        }
    }

    /**
     * TỰ ĐỘNG THẢ ĐĨA BAY UFO
     */
    fun triggerAutoUfo() {
        val accessService = CandyAccessibilityService.instance ?: return
        val metrics = resources.displayMetrics
        val screenW = metrics.widthPixels
        val screenH = metrics.heightPixels

        val (ufoX, ufoY) = detector.getBoosterScreenCoords("UFO", screenW, screenH)

        scope.launch {
            mainHandler.post {
                Toast.makeText(this@FloatingHomeService, "🛸 Đang kích hoạt Đĩa Bay UFO!", Toast.LENGTH_SHORT).show()
            }
            accessService.performTap(ufoX, ufoY, 80L)
            delay(1200L)
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

    private fun stopAutoAndExit() {
        isRunning = false
        stopAiLoop()
        isServiceRunning = false
        if (::floatingView.isInitialized) {
            windowManager.removeView(floatingView)
        }
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopAutoAndExit()
        scope.cancel()
    }

    private fun dpToPx(dp: Int): Int {
        val density = resources.displayMetrics.density
        return (dp * density).toInt()
    }
}
