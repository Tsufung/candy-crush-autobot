package com.candyrush.autobot.solver

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect

/**
 * Trình phân tích hình ảnh và nhận diện màu sắc kẹo từ ảnh chụp màn hình Bitmap
 */
class CandyDetector {

    data class BoardConfig(
        val topRatio: Float = 0.24f,    // Bắt đầu khoảng 24% từ trên xuống
        val bottomRatio: Float = 0.76f, // Kết thúc khoảng 76%
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
     * Nhận diện ma trận màu sắc các viên kẹo từ Bitmap
     */
    fun detectGrid(bitmap: Bitmap): Array<IntArray> {
        val boardRect = calculateBoardRect(bitmap.width, bitmap.height)
        val cellW = boardRect.width().toFloat() / config.cols
        val cellH = boardRect.height().toFloat() / config.rows

        val grid = Array(config.rows) { IntArray(config.cols) }

        for (r in 0 until config.rows) {
            for (c in 0 until config.cols) {
                val centerX = (boardRect.left + (c + 0.5f) * cellW).toInt()
                val centerY = (boardRect.top + (r + 0.5f) * cellH).toInt()

                // Lấy mẫu trung bình 5 điểm quanh tâm ô kẹo
                var totalR = 0
                var totalG = 0
                var totalB = 0
                val sampleOffsets = arrayOf(
                    Pair(0, 0), Pair(-4, 0), Pair(4, 0), Pair(0, -4), Pair(0, 4)
                )

                for (offset in sampleOffsets) {
                    val px = (centerX + offset.first).coerceIn(0, bitmap.width - 1)
                    val py = (centerY + offset.second).coerceIn(0, bitmap.height - 1)
                    val pixel = bitmap.getPixel(px, py)

                    totalR += Color.red(pixel)
                    totalG += Color.green(pixel)
                    totalB += Color.blue(pixel)
                }

                val avgR = totalR / sampleOffsets.size
                val avgG = totalG / sampleOffsets.size
                val avgB = totalB / sampleOffsets.size

                grid[r][c] = classifyCandy(avgR, avgG, avgB)
            }
        }

        return grid
    }

    /**
     * Phân loại màu theo dải màu HSV chuẩn của game Candy Crush
     */
    private fun classifyCandy(r: Int, g: Int, b: Int): Int {
        val hsv = FloatArray(3)
        Color.RGBToHSV(r, g, b, hsv)
        val hue = hsv[0]
        val sat = hsv[1]
        val valBrightness = hsv[2]

        // Bỏ qua màu quá tối hoặc màu xám nền
        if (valBrightness < 0.20f || sat < 0.28f) {
            return Match3Solver.EMPTY
        }

        return when {
            hue >= 345f || hue <= 15f -> Match3Solver.RED
            hue in 16f..45f -> Match3Solver.ORANGE
            hue in 46f..75f -> Match3Solver.YELLOW
            hue in 76f..170f -> Match3Solver.GREEN
            hue in 171f..250f -> Match3Solver.BLUE
            hue in 251f..335f -> Match3Solver.PURPLE
            else -> Match3Solver.EMPTY
        }
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
