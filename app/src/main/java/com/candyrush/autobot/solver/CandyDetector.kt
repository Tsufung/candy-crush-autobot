package com.candyrush.autobot.solver

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import kotlin.math.abs

/**
 * Trình phân tích hình ảnh và nhận diện màu sắc kẹo từ ảnh chụp màn hình (Meta 2026 Edition)
 * Áp dụng thuật toán Lấy mẫu đa điểm (Multi-point sampling) & Bỏ phiếu đa số (Majority Voting)
 * Loại bỏ hoàn toàn điểm chói sáng (Specular highlight), đổ bóng và hiệu ứng nhấp nháy.
 */
class CandyDetector {

    data class BoardConfig(
        val topRatio: Float = 0.24f,    // Bắt đầu khoảng 24% từ trên xuống (dưới thanh điểm)
        val bottomRatio: Float = 0.76f, // Kết thúc khoảng 76% (trên hàng booster)
        val marginRatio: Float = 0.04f, // Lề 2 bên 4%
        val rows: Int = 9,
        val cols: Int = 9
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
     * Nhận diện ma trận màu sắc các viên kẹo từ Bitmap bằng thuật toán Bầu chọn đa số
     */
    fun detectGrid(bitmap: Bitmap): Array<IntArray> {
        val boardRect = calculateBoardRect(bitmap.width, bitmap.height)
        val cellW = boardRect.width().toFloat() / config.cols
        val cellH = boardRect.height().toFloat() / config.rows

        val grid = Array(config.rows) { IntArray(config.cols) }

        // Bán kính lấy mẫu tỷ lệ theo kích thước ô kẹo
        val radiusX = (cellW * 0.22f).toInt()
        val radiusY = (cellH * 0.22f).toInt()

        val sampleOffsets = arrayOf(
            Pair(0, 0),
            Pair(-radiusX, 0), Pair(radiusX, 0),
            Pair(0, -radiusY), Pair(0, radiusY),
            Pair(-radiusX / 2, -radiusY / 2), Pair(radiusX / 2, radiusY / 2),
            Pair(-radiusX / 2, radiusY / 2), Pair(radiusX / 2, -radiusY / 2)
        )

        for (r in 0 until config.rows) {
            for (c in 0 until config.cols) {
                val centerX = (boardRect.left + (c + 0.5f) * cellW).toInt()
                val centerY = (boardRect.top + (r + 0.5f) * cellH).toInt()

                val votes = mutableMapOf<Int, Int>()
                val colorsList = mutableListOf<Triple<Int, Int, Int>>()

                for (offset in sampleOffsets) {
                    val px = (centerX + offset.first).coerceIn(0, bitmap.width - 1)
                    val py = (centerY + offset.second).coerceIn(0, bitmap.height - 1)
                    val pixel = bitmap.getPixel(px, py)

                    val red = Color.red(pixel)
                    val green = Color.green(pixel)
                    val blue = Color.blue(pixel)

                    colorsList.add(Triple(red, green, blue))

                    val candyType = classifyPixel(red, green, blue)
                    if (candyType != Match3Solver.EMPTY) {
                        votes[candyType] = (votes[candyType] ?: 0) + 1
                    }
                }

                // Kiểm tra Bom Màu (Color Bomb) có các hạt cốm đủ màu sắc trên nền sô-cô-la
                if (isColorBomb(colorsList)) {
                    grid[r][c] = Match3Solver.COLOR_BOMB
                    continue
                }

                // Chọn màu có số phiếu cao nhất (Majority Vote)
                val bestCandidate = votes.maxByOrNull { it.value }
                if (bestCandidate != null && bestCandidate.value >= 3) {
                    grid[r][c] = bestCandidate.key
                } else {
                    grid[r][c] = Match3Solver.EMPTY
                }
            }
        }

        return grid
    }

    /**
     * Phân loại từng pixel riêng biệt qua không gian màu HSV
     */
    private fun classifyPixel(r: Int, g: Int, b: Int): Int {
        val max = maxOf(r, g, b)
        val min = minOf(r, g, b)
        val delta = max - min

        // Bỏ qua bóng tối hoặc nền xám
        if (max < 45) return Match3Solver.EMPTY

        val hsv = FloatArray(3)
        Color.RGBToHSV(r, g, b, hsv)
        val hue = hsv[0]
        val sat = hsv[1]
        val brightness = hsv[2]

        // Bỏ qua điểm chói sáng phản chiếu (Specular Glare) màu trắng
        if (brightness > 0.92f && sat < 0.20f) {
            return Match3Solver.EMPTY
        }

        // Bỏ qua màu nhờ nhạt không bão hòa
        if (sat < 0.25f || brightness < 0.22f) {
            return Match3Solver.EMPTY
        }

        // Phân dải theo 6 màu chuẩn của kẹo Candy Crush
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

    /**
     * Nhận diện Bom Màu (Color Bomb) qua độ biến thiên đa màu
     */
    private fun isColorBomb(colors: List<Triple<Int, Int, Int>>): Boolean {
        if (colors.size < 5) return false
        var highVarianceCount = 0
        val avgR = colors.map { it.first }.average()
        val avgG = colors.map { it.second }.average()
        val avgB = colors.map { it.third }.average()

        for (c in colors) {
            val diff = abs(c.first - avgR) + abs(c.second - avgG) + abs(c.third - avgB)
            if (diff > 55) highVarianceCount++
        }
        return highVarianceCount >= 4
    }

    /**
     * Chuyển đổi tọa độ ô ma trận (row, col) sang tọa độ màn hình thực tế (pixel X, Y)
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
