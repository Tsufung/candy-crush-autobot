package com.candyrush.autobot.service

import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
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
 * Service hiển thị Nút Home Ảo (Floating Bubble) Meta 2026
 * Hỗ trợ:
 * - Kéo thả tự do trên màn hình
 * - 1 chạm để Bật / Tạm dừng
 * - Nút ✕ màu đỏ để TẮT HOÀN TOÀN ứng dụng bất cứ lúc nào
 * - Nhấn giữ 0.5s để tắt nhanh
 * - Cơ chế CHỐNG LẶP NƯỚC ĐI (Anti-loop Blacklist): nếu game không nhận nước đi, tự động cấm nước đi đó và tìm nước khác
 * - Chờ bàn cờ ổn định trước khi tính nước (tránh chụp lúc kẹo đang rơi)
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

    private val detector = CandyDetector()
    private val solver = Match3Solver()

    private var isRunning = false
    private var job: Job? = null
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val mainHandler = Handler(Looper.getMainLooper())

    // Danh sách các nước đi bị cấm tạm thời vì game từ chối (bị bật lại)
    private val bannedMoves = mutableMapOf<String, Long>()
    private var lastMoveKey: String? = null
    private var lastGrid: Array<IntArray>? = null

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

        // Tạo khung chứa nút Home ảo + nút tắt X
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
                setStroke(dpToPx(2), Color.parseColor("#FFFFFF"))
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

        // Bắt sự kiện chạm và kéo thả cho khung chính
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

                    // Nếu là click nhẹ (dưới 350ms, dịch chuyển nhỏ)
                    if (touchDuration < 350 && dx < 15 && dy < 15) {
                        toggleAuto()
                    } else if (touchDuration >= 700 && dx < 15 && dy < 15) {
                        // Nhấn giữ 0.7s: Tắt bot
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

    private fun startAutoLoop() {
        job?.cancel()
        job = scope.launch {
            while (isRunning && isActive) {
                val screenCapService = ScreenCaptureService.instance
                val accessService = CandyAccessibilityService.instance

                if (screenCapService == null || accessService == null) {
                    mainHandler.post {
                        Toast.makeText(this@FloatingHomeService, "⚠️ Cần cấp quyền Trợ Năng và Chụp Màn Hình!", Toast.LENGTH_SHORT).show()
                    }
                    isRunning = false
                    updateButtonState()
                    break
                }

                // 1. Chờ bàn cờ ổn định (không còn kẹo rơi hay nổ dở)
                val bitmap = waitForStableScreen(screenCapService)
                if (bitmap == null) {
                    delay(400L)
                    continue
                }

                // 2. Nhận diện ma trận kẹo
                val grid = detector.detectGrid(bitmap)

                // 3. Kiểm tra xem nước đi vừa rồi có bị game từ chối hay không (Anti-loop)
                val currentLastKey = lastMoveKey
                val currentLastGrid = lastGrid
                if (currentLastKey != null && currentLastGrid != null) {
                    if (areGridsEqual(currentLastGrid, grid)) {
                        // Bàn cờ hoàn toàn không thay đổi -> Nước đi trước đã bị game lắc trả về!
                        // Cấm nước đi này trong 7 giây để bot thử nước khác
                        bannedMoves[currentLastKey] = System.currentTimeMillis() + 7000L
                    }
                }

                // Dọn dẹp các nước đi đã hết hạn cấm
                val now = System.currentTimeMillis()
                bannedMoves.entries.removeAll { it.value < now }

                // 4. Tìm nước đi tốt nhất không nằm trong danh sách bị cấm
                val activeBannedSet = bannedMoves.keys.toSet()
                val bestMove = solver.findBestMove(grid, activeBannedSet)

                if (bestMove != null) {
                    val (x1, y1) = detector.cellToScreenCoords(bestMove.r1, bestMove.c1, bitmap.width, bitmap.height)
                    val (x2, y2) = detector.cellToScreenCoords(bestMove.r2, bestMove.c2, bitmap.width, bitmap.height)

                    lastMoveKey = Match3Solver.moveKey(bestMove.r1, bestMove.c1, bestMove.r2, bestMove.c2)
                    lastGrid = cloneGrid(grid)

                    // Thực hiện vuốt tráo kẹo (220ms vuốt dứt khoát)
                    accessService.performSwipe(x1, y1, x2, y2, 220L)

                    // Chờ animation tráo + kẹo nổ + cascade sụt đổ (1.5s)
                    delay(1500L)
                } else {
                    // Không tìm thấy nước đi hợp lệ hoặc đang xáo trộn bàn cờ
                    lastMoveKey = null
                    lastGrid = null
                    delay(600L)
                }
            }
        }
    }

    /**
     * Chờ màn hình ổn định: chụp 2 frame cách nhau 200ms, nếu không chênh lệch lớn thì lấy
     */
    private suspend fun waitForStableScreen(service: ScreenCaptureService): Bitmap? {
        var prevBitmap: Bitmap? = null
        for (i in 0..5) {
            val current = service.captureCurrentBitmap() ?: return null
            if (prevBitmap != null) {
                // Kiểm tra sơ bộ pixel ở giữa bàn cờ
                if (isImageStill(prevBitmap, current)) {
                    return current
                }
            }
            prevBitmap = current
            delay(200L)
        }
        return prevBitmap
    }

    private fun isImageStill(b1: Bitmap, b2: Bitmap): Boolean {
        val w = b1.width
        val h = b1.height
        val testPoints = arrayOf(
            Pair(w / 2, h / 2),
            Pair(w / 3, h / 2),
            Pair(2 * w / 3, h / 2),
            Pair(w / 2, h / 3),
            Pair(w / 2, 2 * h / 3)
        )
        for (p in testPoints) {
            val c1 = b1.getPixel(p.first, p.second)
            val c2 = b2.getPixel(p.first, p.second)
            val diff = abs(Color.red(c1) - Color.red(c2)) +
                       abs(Color.green(c1) - Color.green(c2)) +
                       abs(Color.blue(c1) - Color.blue(c2))
            if (diff > 40) return false
        }
        return true
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

    private fun stopAutoLoop() {
        job?.cancel()
        job = null
    }

    /**
     * DỪNG BOT VÀ ĐÓNG HOÀN TOÀN KHỎI MÀN HÌNH
     */
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
