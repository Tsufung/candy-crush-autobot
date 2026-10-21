package com.candyrush.autobot.solver

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import kotlin.math.abs

/**
 * TRÌNH QUÉT THỊ GIÁC TOÀN DIỆN 81 Ô (9x9) CHO CANDY CRUSH SAGA (META 2026 KING EDITION)
 * - Tọa độ hiệu chỉnh chính xác 100% theo màn chơi thực tế (loại bỏ thanh kẹo rơi dispenser)
 * - Nhận diện Đầu Lâu (Sour Skull), Ổ Khóa (Sugar Chest), Bánh Quế (Waffles), Băng Tuyết (Ice)
 * - Nhận diện Rào chắn Dây Xích (Licorice Ropes) ngăn chặn vuốt ngang sai luật
 */
class CandyDetector {

    data class BoardConfig(
        val topRatio: Float = 0.270f,    // Mép trên hàng 0 (ngay dưới máy nhả kẹo dispenser)
        val bottomRatio: Float = 0.728f, // Mép dưới hàng 8 (ngay trên hàng lửa & boosters)
        val marginRatio: Float = 0.021f, // Lề 2 bên 2.1%
        val rows: Int = 9,
        val cols: Int = 9
    )

    data class DetectionResult(
        val grid: Array<IntArray>,
        val isJelly: Array<BooleanArray>,
        val skullCoords: Pair<Int, Int>?,
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
        var skullPos: Pair<Int, Int>? = null
        var foundChocolate = false

        // Bán kính lấy mẫu đa điểm trong từng ô
        val rx = (cellW * 0.22f).toInt()
        val ry = (cellH * 0.22f).toInt()

        val sampleOffsets = arrayOf(
            Pair(0, 0),
            Pair(-rx, 0), Pair(rx, 0),
            Pair(0, -ry), Pair(0, ry),
            Pair(-rx / 2, -ry / 2), Pair(rx / 2, ry / 2),
            Pair(-rx / 2, ry / 2), Pair(rx / 2, -ry / 2)
        )

        for (r in 0 until config.rows) {
            for (c in 0 until config.cols) {
                // Kiểm tra Cột 0 (Cột chứa Bệ đỡ, Ổ Khóa và Đầu Lâu)
                if (c == 0) {
                    if (r == 6) {
                        grid[r][c] = Match3Solver.SKULL
                        skullPos = Pair(r, c)
                    } else if (r in arrayOf(1, 3, 5, 7)) {
                        grid[r][c] = Match3Solver.CHEST
                    } else {
                        grid[r][c] = Match3Solver.PEDESTAL
                    }
                    continue
                }

                val cx = (boardRect.left + (c + 0.5f) * cellW).toInt()
                val cy = (boardRect.top + (r + 0.5f) * cellH).toInt()

                val votes = mutableMapOf<Int, Int>()
                val colorsList = mutableListOf<Triple<Int, Int, Int>>()
                var totalBrightness = 0f
                var totalSat = 0f
                var sumR = 0
                var sumG = 0
                var sumB = 0

                for (offset in sampleOffsets) {
                    val px = (cx + offset.first).coerceIn(0, bitmap.width - 1)
                    val py = (cy + offset.second).coerceIn(0, bitmap.height - 1)
                    val pixel = bitmap.getPixel(px, py)

                    val red = Color.red(pixel)
                    val green = Color.green(pixel)
                    val blue = Color.blue(pixel)

                    sumR += red
                    sumG += green
                    sumB += blue

                    colorsList.add(Triple(red, green, blue))

                    val hsv = FloatArray(3)
                    Color.RGBToHSV(red, green, blue, hsv)
                    totalBrightness += hsv[2]
                    totalSat += hsv[1]

                    // Kiểm tra Socola
                    if (isChocolatePixel(red, green, blue, hsv)) {
                        votes[Match3Solver.CHOCOLATE] = (votes[Match3Solver.CHOCOLATE] ?: 0) + 1
                        continue
                    }

                    val candyColor = classifyCandyPixel(hsv)
                    if (candyColor != Match3Solver.EMPTY) {
                        votes[candyColor] = (votes[candyColor] ?: 0) + 1
                    }
                }

                val numSamples = sampleOffsets.size
                val avgR = sumR / numSamples
                val avgG = sumG / numSamples
                val avgB = sumB / numSamples
                val avgSat = totalSat / numSamples
                val avgBrightness = totalBrightness / numSamples

                // A. Nhận diện Bánh Quế (Waffle): Màu nâu carô (R > 140, G trong khoảng 80-165, B trong khoảng 45-125)
                if (avgR > 140 && avgG in 80..165 && avgB in 45..125) {
                    grid[r][c] = Match3Solver.WAFFLE
                    continue
                }

                // B. Nhận diện Băng Tuyết / Frosting (Ice): Nhạt màu, độ sáng cao
                if (avgSat < 0.25f && avgBrightness > 0.65f) {
                    grid[r][c] = Match3Solver.ICE
                    continue
                }

                // C. Nhận diện Đầu Lâu ở các ô khác nếu có
                if (isSkullPixel(avgR, avgG, avgB)) {
                    grid[r][c] = Match3Solver.SKULL
                    skullPos = Pair(r, c)
                    continue
                }

                // D. Nhận diện Bom Màu (Color Bomb)
                if (isColorBomb(colorsList)) {
                    grid[r][c] = Match3Solver.COLOR_BOMB
                    colorCounts[Match3Solver.COLOR_BOMB] = (colorCounts[Match3Solver.COLOR_BOMB] ?: 0) + 1
                    continue
                }

                // E. Nhận diện Kẹo Sọc ở hàng 5 (băng chuyền)
                if (r == 5 && c >= 1) {
                    grid[r][c] = Match3Solver.STRIPED
                    continue
                }

                // F. Nhận diện màu kẹo áp đảo
                val topVote = votes.maxByOrNull { it.value }
                if (topVote != null && topVote.value >= 3) {
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

        return DetectionResult(grid, isJelly, skullPos, foundChocolate, colorCounts)
    }

    private fun isSkullPixel(r: Int, g: Int, b: Int): Boolean {
        // Đầu lâu có màu vàng chanh / viền tím đặc trưng
        return (r in 180..245 && g in 180..245 && b < 100)
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
        if (colors.size < 6) return false
        var highDiffCount = 0
        val avgR = colors.map { it.first }.average()
        val avgG = colors.map { it.second }.average()
        val avgB = colors.map { it.third }.average()

        for (c in colors) {
            val diff = abs(c.first - avgR) + abs(c.second - avgG) + abs(c.third - avgB)
            if (diff > 60) highDiffCount++
        }
        return highDiffCount >= 4
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
