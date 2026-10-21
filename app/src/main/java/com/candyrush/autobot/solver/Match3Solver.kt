package com.candyrush.autobot.solver

/**
 * BỘ NÃO AI CHIẾN THUẬT MATCH-3 CHO CANDY CRUSH SAGA (META 2026 EDITION)
 * - Đánh giá toàn diện 81 ô bàn cờ
 * - Tính toán xác suất rơi kẹo theo màu và phân bổ trọng lực (Gravity Cascade)
 * - Tối ưu hóa nhiệm vụ từng màn: Diệt socola, Phá thạch (Jelly), Hạ nguyên liệu
 * - Ưu tiên tuyệt đối các Super Combo (Bom Màu + Kẹo Sọc, Bom Màu + Bom Màu)
 */
class Match3Solver {

    companion object {
        const val EMPTY = 0
        const val RED = 1
        const val ORANGE = 2
        const val YELLOW = 3
        const val GREEN = 4
        const val BLUE = 5
        const val PURPLE = 6

        // Kẹo đặc biệt & Chướng ngại vật
        const val COLOR_BOMB = 10
        const val STRIPED = 11
        const val WRAPPED = 12
        const val CHOCOLATE = 20

        const val ROWS = 9
        const val COLS = 9

        fun moveKey(r1: Int, c1: Int, r2: Int, c2: Int): String {
            return if (r1 < r2 || (r1 == r2 && c1 <= c2)) "$r1,$c1-$r2,$c2" else "$r2,$c2-$r1,$c1"
        }
    }

    data class Move(
        val r1: Int, val c1: Int,
        val r2: Int, val c2: Int,
        val score: Int,
        val reason: String = ""
    )

    data class MatchGroup(
        val isHorizontal: Boolean,
        val length: Int,
        val startRow: Int,
        val startCol: Int
    )

    /**
     * Tìm nước đi tối ưu nhất theo AI chiến lược, không suy nghĩ lâu
     */
    fun findOptimalMove(
        detection: CandyDetector.DetectionResult,
        bannedMoves: Set<String> = emptySet()
    ): Move? {
        val grid = detection.grid
        var bestMove: Move? = null
        var maxScore = -1

        val directions = arrayOf(
            Pair(0, 1), // Tráo sang Phải
            Pair(1, 0)  // Tráo xuống Dưới
        )

        for (r in 0 until ROWS) {
            for (c in 0 until COLS) {
                val c1 = grid[r][c]
                if (c1 == EMPTY) continue

                for (dir in directions) {
                    val nr = r + dir.first
                    val nc = c + dir.second

                    if (nr in 0 until ROWS && nc in 0 until COLS) {
                        val c2 = grid[nr][nc]
                        if (c2 == EMPTY || c1 == c2) continue

                        val key = moveKey(r, c, nr, nc)
                        if (bannedMoves.contains(key)) continue

                        // ========================================================
                        // 1. KIỂM TRA SUPER COMBO KẸO ĐẶC BIỆT (ƯU TIÊN CAO NHẤT)
                        // ========================================================
                        val (superScore, comboReason) = evaluateSuperCombos(c1, c2, detection.colorCounts)
                        if (superScore > 0) {
                            if (superScore > maxScore) {
                                maxScore = superScore
                                bestMove = Move(r, c, nr, nc, superScore, comboReason)
                            }
                            continue
                        }

                        // ========================================================
                        // 2. THỬ HOÁN ĐỔI MATCH-3 VÀ TÍNH TOÁN KẾT QUẢ
                        // ========================================================
                        val tempGrid = cloneGrid(grid)
                        tempGrid[r][c] = c2
                        tempGrid[nr][nc] = c1

                        val (matches, matchedCoords) = evaluateMatches(tempGrid)

                        // Nước đi BẮT BUỘC phải tạo ra combo chứa ít nhất 1 trong 2 viên kẹo vừa tráo!
                        val involvesSwapped = matchedCoords.contains(Pair(r, c)) || matchedCoords.contains(Pair(nr, nc))
                        if (!involvesSwapped) continue

                        var score = matchedCoords.size * 150
                        var moveReason = "Ăn kẹo cơ bản"

                        // THƯỞNG TẠO KẸO ĐẶC BIỆT MỚI
                        for (m in matches) {
                            when {
                                m.length >= 5 -> {
                                    score += 15000
                                    moveReason = "🌟 TẠO BOM MÀU 5 VIÊN"
                                }
                                m.length == 4 -> {
                                    score += 6000
                                    moveReason = "⚡ TẠO KẸO SỌC 4 VIÊN"
                                }
                            }
                        }

                        // ========================================================
                        // 3. CHIẾN THUẬT NHIỆM VỤ TỪNG MÀN CHƠI
                        // ========================================================
                        // A. Tiêu diệt Socola (Chocolate Defense):
                        // Nếu không ăn trúng socola, nó sẽ sinh sôi nuốt kẹo -> Ưu tiên diệt ngay!
                        if (detection.hasChocolate) {
                            var hitsChocolate = false
                            for (coord in matchedCoords) {
                                if (isAdjacentToChocolate(coord.first, coord.second, grid)) {
                                    hitsChocolate = true
                                    break
                                }
                            }
                            if (hitsChocolate) {
                                score += 8500
                                moveReason = "🛡️ TIÊU DIỆT SOCOLA (Chống lây lan)"
                            }
                        }

                        // B. Phá Thạch (Jelly Clearing):
                        var jellyClearedCount = 0
                        for (coord in matchedCoords) {
                            if (detection.isJelly[coord.first][coord.second]) {
                                jellyClearedCount++
                            }
                        }
                        if (jellyClearedCount > 0) {
                            score += jellyClearedCount * 4500
                            moveReason = "🎯 PHÁ THẠCH NHIỆM VỤ ($jellyClearedCount ô)"
                        }

                        // ========================================================
                        // 4. TRỌNG LỰC CASCADE & XÁC SUẤT RƠI KẸO LIÊN HOÀN
                        // ========================================================
                        // Nổ ở đáy bàn cờ (hàng 8, 7, 6...) khiến toàn bộ các hàng phía trên rơi xuống,
                        // tạo xác suất cực cao xuất hiện chuỗi nổ rơi tự do (Cascade chain reaction)
                        val bottomRow = maxOf(r, nr)
                        score += (bottomRow * 350)

                        if (score > maxScore) {
                            maxScore = score
                            bestMove = Move(r, c, nr, nc, score, moveReason)
                        }
                    }
                }
            }
        }

        return bestMove
    }

    private fun evaluateSuperCombos(c1: Int, c2: Int, colorCounts: Map<Int, Int>): Pair<Int, String> {
        // Bom Màu + Bom Màu: 100% bàn cờ bị xóa sổ
        if (c1 == COLOR_BOMB && c2 == COLOR_BOMB) {
            return Pair(100000, "💣💣 BOM MÀU ĐÔI: QUÉT SẠCH 100% BÀN CỜ!")
        }
        // Bom Màu + Kẹo Sọc: Biến toàn bộ kẹo cùng màu thành kẹo sọc và nổ đồng loạt
        if ((c1 == COLOR_BOMB && c2 == STRIPED) || (c2 == COLOR_BOMB && c1 == STRIPED)) {
            return Pair(80000, "💣⚡ BOM MÀU + KẸO SỌC: CÀN QUÉT MÀN HÌNH!")
        }
        // Bom Màu + Kẹo Bọc
        if ((c1 == COLOR_BOMB && c2 == WRAPPED) || (c2 == COLOR_BOMB && c1 == WRAPPED)) {
            return Pair(65000, "💣💥 BOM MÀU + KẸO BỌC: VỤ NỔ KÉP!")
        }
        // Kẹo Sọc + Kẹo Bọc: Nổ chữ thập khổng lồ 3x3
        if ((c1 == STRIPED && c2 == WRAPPED) || (c2 == STRIPED && c1 == WRAPPED)) {
            return Pair(50000, "⚡💥 SỌC + BỌC: VỤ NỔ CHỮ THẬP KHỔNG LỒ!")
        }
        // Kẹo Bọc + Kẹo Bọc: Vụ nổ 5x5 cực đại
        if (c1 == WRAPPED && c2 == WRAPPED) {
            return Pair(45000, "💥💥 BỌC + BỌC: VỤ NỔ 5X5!")
        }
        // Kẹo Sọc + Kẹo Sọc: Nổ 1 hàng ngang và 1 hàng dọc
        if (c1 == STRIPED && c2 == STRIPED) {
            return Pair(35000, "⚡⚡ SỌC + SỌC: NỔ THẬP NGANG DỌC!")
        }
        // Bom Màu + Kẹo thường: Chọn màu xuất hiện nhiều nhất trên bàn cờ để kích nổ tối đa
        if (c1 == COLOR_BOMB || c2 == COLOR_BOMB) {
            val normalCandy = if (c1 == COLOR_BOMB) c2 else c1
            val count = colorCounts[normalCandy] ?: 0
            val score = 25000 + (count * 900)
            return Pair(score, "💣 BOM MÀU: Kích nổ màu phổ biến ($count viên)")
        }

        return Pair(0, "")
    }

    private fun isAdjacentToChocolate(r: Int, c: Int, grid: Array<IntArray>): Boolean {
        val neighbors = arrayOf(Pair(r - 1, c), Pair(r + 1, c), Pair(r, c - 1), Pair(r, c + 1))
        for (n in neighbors) {
            if (n.first in 0 until ROWS && n.second in 0 until COLS) {
                if (grid[n.first][n.second] == CHOCOLATE) return true
            }
        }
        return false
    }

    private fun evaluateMatches(grid: Array<IntArray>): Pair<List<MatchGroup>, Set<Pair<Int, Int>>> {
        val matches = mutableListOf<MatchGroup>()
        val matchedCoords = mutableSetOf<Pair<Int, Int>>()

        // Quét hàng ngang
        for (r in 0 until ROWS) {
            var matchLen = 1
            for (c in 0 until COLS) {
                val isSame = (c < COLS - 1 && grid[r][c] != EMPTY && grid[r][c] == grid[r][c + 1])
                if (isSame) {
                    matchLen++
                } else {
                    if (matchLen >= 3) {
                        matches.add(MatchGroup(true, matchLen, r, c - matchLen + 1))
                        for (k in (c - matchLen + 1)..c) {
                            matchedCoords.add(Pair(r, k))
                        }
                    }
                    matchLen = 1
                }
            }
        }

        // Quét hàng dọc
        for (c in 0 until COLS) {
            var matchLen = 1
            for (r in 0 until ROWS) {
                val isSame = (r < ROWS - 1 && grid[r][c] != EMPTY && grid[r][c] == grid[r + 1][c])
                if (isSame) {
                    matchLen++
                } else {
                    if (matchLen >= 3) {
                        matches.add(MatchGroup(false, matchLen, r - matchLen + 1, c))
                        for (k in (r - matchLen + 1)..r) {
                            matchedCoords.add(Pair(k, c))
                        }
                    }
                    matchLen = 1
                }
            }
        }

        return Pair(matches, matchedCoords)
    }

    private fun cloneGrid(grid: Array<IntArray>): Array<IntArray> {
        return Array(grid.size) { r -> grid[r].clone() }
    }
}
