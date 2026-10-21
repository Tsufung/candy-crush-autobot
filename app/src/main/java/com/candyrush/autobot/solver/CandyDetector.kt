package com.candyrush.autobot.solver

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import kotlin.math.abs

/**
 * Trình phân tích hình ảnh 81 ô (9x9) thông minh cho Candy Crush Saga (Meta 2026 Edition)
 * - Quét 1 lần duy nhất toàn bộ 81 ô bàn cờ
 * - Nhận diện màu sắc kẹo, kẹo đặc biệt (Bom màu, sọc, bọc) và chướng ngại vật (Socola, Thạch)
 * - Tính toán tần suất và xác suất xuất hiện của các màu kẹo trên bàn cờ
 */
class CandyDetector {

    data class BoardConfig(
        val topRatio: Float = 0.235f,   // Mép trên bàn cờ (dưới thanh điểm số & nhiệm vụ)
        val bottomRatio: Float = 0.765f, // Mép dưới bàn cờ (trên hàng công cụ boosters)
        val marginRatio: Float = 0.04f, // Lề 2 bên 4%
        val rows: Int = 9,
        val cols: Int = 9
    )

    data class DetectionResult(
        val grid: Array<IntArray>,
        val isJelly: Array<BooleanArray>,
        val hasChocolate: Boolean,
        val colorCounts: Map<Int, Int>
    )

    private val config = BoardConfig()

    fun calculateBoardRect(screenWidth: Int, screenHeight: Int): Rect {
        val left = (screenWidth * config.marginRatio).toInt()
        val right = (screenWidth * (1f - config.marginRatio)).toInt()
        val top = (screenHeight * config.topRatio).toInt()
        val bottom = (screenHeight * config.bottomRatio).toInt()
        return Rect(left, top, right, bottom)
    }

    /**
     * Quét 1 lần 81 ô bàn cờ từ ảnh chụp màn hình Bitmap
     */
    fun scanBoard(bitmap: Bitmap): DetectionResult {
        val boardRect = calculateBoardRect(bitmap.width, bitmap.height)
        val cellW = boardRect.width().toFloat() / config.cols
        val cellH = boardRect.height().toFloat() / config.rows

        val grid = Array(config.rows) { IntArray(config.cols) }
        val isJelly = Array(config.rows) { BooleanArray(config.cols) }
        val colorCounts = mutableMapOf<Int, Int>()
        var foundChocolate = false

        // Bán kính lấy mẫu đa điểm
        val rx = (cellW * 0.22f).toInt()
        val ry = (cellH * 0.22f).toInt()

        // 17 điểm lấy mẫu trong mỗi ô kẹo
        val sampleOffsets = arrayOf(
            Pair(0, 0),
            Pair(-rx, 0), Pair(rx, 0),
            Pair(0, -ry), Pair(0, ry),
            Pair(-rx / 2, -ry / 2), Pair(rx / 2, ry / 2),
            Pair(-rx / 2, ry / 2), Pair(rx / 2, -ry / 2),
            Pair(-rx * 3 / 4, 0), Pair(rx * 3 / 4, 0),
            Pair(0, -ry * 3 / 4), Pair(0, ry * 3 / 4),
            Pair(-rx / 3, -ry / 3), Pair(rx / 3, ry / 3),
            Pair(-rx / 3, ry / 3), Pair(rx / 3, -ry / 3)
        )

        for (r in 0 until config.rows) {
            for (c in 0 until config.cols) {
                val cx = (boardRect.left + (c + 0.5f) * cellW).toInt()
                val cy = (boardRect.top + (r + 0.5f) * cellH).toInt()

                val votes = mutableMapOf<Int, Int>()
                val colorsList = mutableListOf<Triple<Int, Int, Int>>()
                var totalBrightness = 0f

                for (offset in sampleOffsets) {
                    val px = (cx + offset.first).coerceIn(0, bitmap.width - 1)
                    val py = (cy + offset.second).coerceIn(0, bitmap.height - 1)
                    val pixel = bitmap.getPixel(px, py)

                    val red = Color.red(pixel)
                    val green = Color.green(pixel)
                    val blue = Color.blue(pixel)

                    colorsList.add(Triple(red, green, blue))

                    val hsv = FloatArray(3)
                    Color.RGBToHSV(red, green, blue, hsv)
                    totalBrightness += hsv[2]

                    // Kiểm tra Socola (nâu đậm)
                    if (isChocolatePixel(red, green, blue, hsv)) {
                        votes[Match3Solver.CHOCOLATE] = (votes[Match3Solver.CHOCOLATE] ?: 0) + 1
                        continue
                    }

                    val candyColor = classifyCandyPixel(hsv)
                    if (candyColor != Match3Solver.EMPTY) {
                        votes[candyColor] = (votes[candyColor] ?: 0) + 1
                    }
                }

                // 1. Kiểm tra Thạch (Jelly): Nền trắng đục lót dưới kẹo
                val avgBrightness = totalBrightness / sampleOffsets.size
                if (avgBrightness > 0.68f) {
                    isJelly[r][c] = true
                }

                // 2. Kiểm tra Bom Màu (Color Bomb): Nền socola rắc cốm nhiều màu
                if (isColorBomb(colorsList)) {
                    grid[r][c] = Match3Solver.COLOR_BOMB
                    colorCounts[Match3Solver.COLOR_BOMB] = (colorCounts[Match3Solver.COLOR_BOMB] ?: 0) + 1
                    continue
                }

                // 3. Lấy màu có số phiếu áp đảo
                val topVote = votes.maxByOrNull { it.value }
                if (topVote != null && topVote.value >= 4) {
                    val chosen = topVote.key
                    grid[r][c] = chosen
                    if (chosen == Match3Solver.CHOCOLATE) {
                        foundChocolate = true
                    } else if (chosen != Match3Solver.EMPTY) {
                        colorCounts[chosen] = (colorCounts[chosen] ?: 0) + 1
                    }
                } else {
                    grid[r][c] = Match3Solver.EMPTY
                }
            }
        }

        return DetectionResult(grid, isJelly, foundChocolate, colorCounts)
    }

    private fun isChocolatePixel(r: Int, g: Int, b: Int, hsv: FloatArray): Boolean {
        val hue = hsv[0]
        val sat = hsv[1]
        val valB = hsv[2]
        return (hue in 15f..38f && sat in 0.35f..0.75f && valB in 0.20f..0.52f && r > g && g > b)
    }

    private fun classifyCandyPixel(hsv: FloatArray): Int {
        val hue = hsv[0]
        val sat = hsv[1]
        val brightness = hsv[2]

        // Bỏ qua bóng tối hoặc điểm chói sáng (Specular highlight)
        if (brightness < 0.22f || (brightness > 0.93f && sat < 0.20f) || sat < 0.24f) {
            return Match3Solver.EMPTY
        }

        return when {
            hue >= 345f || hue <= 14f -> Match3Solver.RED
            hue in 15f..44f -> Match3Solver.ORANGE
            hue in 45f..74f -> Match3Solver.YELLOW
            hue in 75f..168f -> Match3Solver.GREEN
            hue in 169f..248f -> Match3Solver.BLUE
            hue in 249f..336f -> Match3Solver.PURPLE
            else -> Match3Solver.EMPTY
        }
    }

    private fun isColorBomb(colors: List<Triple<Int, Int, Int>>): Boolean {
        if (colors.size < 8) return false
        var highDiffCount = 0
        val avgR = colors.map { it.first }.average()
        val avgG = colors.map { it.second }.average()
        val avgB = colors.map { it.third }.average()

        for (c in colors) {
            val diff = abs(c.first - avgR) + abs(c.second - avgG) + abs(c.third - avgB)
            if (diff > 60) highDiffCount++
        }
        return highDiffCount >= 5
    }

    /**
     * Chuyển đổi tọa độ ô (row, col) sang tọa độ pixel màn hình thực tế
     */
    fun cellToScreenCoords(row: Int, col: Int, screenWidth: Int, screenHeight: Int): Pair<Float, Float> {
        val board = calculateBoardRect(screenWidth, screenHeight)
        val cellW = board.width().toFloat() / config.cols
        val cellH = board.height().toFloat() / config.rows

        val x = board.left + (col + 0.5f) * cellW
        val y = board.top + (row + 0.5f) * cellH
        return Pair(x, y)
    }
}
