package com.candyrush.autobot.solver

import kotlin.math.abs

/**
 * BỘ NÃO AI CHIẾN THUẬT MATCH-3 CHO CANDY CRUSH SAGA (META 2026 KING EDITION)
 * - Tối ưu hóa tiêu diệt Đầu Lâu (Sour Skull) để đoạt Chìa khóa & giải phóng kẹo vàng/xanh
 * - Phá Bánh Quế (Waffles) & Băng Tuyết (Ice) hoàn thành mục tiêu màn chơi
 * - Nhận diện Rào chắn Dây Xích (Licorice Ropes) ngăn chặn vuốt ngang sai luật
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

        // Chướng ngại vật cao cấp King
        const val SKULL = 30     // Đầu lâu Sour Skull (cần gõ 5 lần để ra chìa khóa)
        const val CHEST = 31     // Ổ khóa Sugar Chest
        const val WAFFLE = 32    // Bánh quế Waffle
        const val ICE = 33       // Băng tuyết Frosting
        const val FISH = 34      // Kẹo cá
        const val PEDESTAL = 35 // Bệ đỡ pha lê cố định

        const val ROWS = 9
        const val COLS = 9

        fun moveKey(r1: Int, c1: Int, r2: Int, c2: Int): String {
            return if (r1 < r2 || (r1 == r2 && c1 <= c2)) "$r1,$c1-$r2,$c2" else "$r2,$c2-$r1,$c1"
        }

        fun isSwappable(value: Int): Boolean {
            return value in arrayOf(RED, ORANGE, YELLOW, GREEN, BLUE, PURPLE, COLOR_BOMB, STRIPED, WRAPPED, FISH)
        }

        /**
         * Kiểm tra rào chắn Dây Xích (Licorice Ropes) thẳng đứng giữa các cột
         * Không cho phép tráo kẹo theo phương ngang xuyên qua dây xích!
         */
        fun isBlockedByRope(row: Int, c1: Int, c2: Int): Boolean {
            val minC = minOf(c1, c2)
            val maxC = maxOf(c1, c2)
            if (minC == 1 && maxC == 2 && row in 5..7) return true
            if (minC == 3 && maxC == 4 && row in 5..7) return true
            if (minC == 5 && maxC == 6 && row in 3..7) return true
            if (minC == 7 && maxC == 8 && row in 3..7) return true
            return false
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
                if (!isSwappable(c1)) continue

                for (dir in directions) {
                    val nr = r + dir.first
                    val nc = c + dir.second

                    if (nr in 0 until ROWS && nc in 0 until COLS) {
                        val c2 = grid[nr][nc]
                        if (!isSwappable(c2)) continue
                        if (c1 == c2 && c1 !in arrayOf(COLOR_BOMB, STRIPED, WRAPPED, FISH)) continue

                        // Chặn tráo ngang xuyên qua Rào Chắn Dây Xích
                        if (dir.second != 0 && isBlockedByRope(r, c, nc)) continue

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

                        // ========================================================
                        // 3. CHIẾN LƯỢC TẤN CÔNG ĐẦU LÂU (SOUR SKULL - KING MECHANIC)
                        // ========================================================
                        val skullPos = detection.skullCoords
                        if (skullPos != null) {
                            var hitsSkull = false
                            for (coord in matchedCoords) {
                                if (abs(coord.first - skullPos.first) <= 1 && abs(coord.second - skullPos.second) <= 1) {
                                    hitsSkull = true
                                    break
                                }
                            }
                            if (hitsSkull) {
                                score += 25000
                                moveReason = "💀 TẤN CÔNG ĐẦU LÂU (SOUR SKULL) -> LẤY CHÌA KHÓA"
                            }
                        }

                        // ========================================================
                        // 4. PHÁ BÁNH QUẾ (WAFFLES) & BĂNG TUYẾT (ICE) NHIỆM VỤ
                        // ========================================================
                        var wafflesHit = 0
                        var iceHit = 0
                        for (coord in matchedCoords) {
                            val neighbors = arrayOf(
                                Pair(coord.first - 1, coord.second),
                                Pair(coord.first + 1, coord.second),
                                Pair(coord.first, coord.second - 1),
                                Pair(coord.first, coord.second + 1)
                            )
                            for (n in neighbors) {
                                if (n.first in 0 until ROWS && n.second in 0 until COLS) {
                                    when (grid[n.first][n.second]) {
                                        WAFFLE -> wafflesHit++
                                        ICE -> iceHit++
                                    }
                                }
                            }
                        }
                        if (wafflesHit > 0) {
                            score += wafflesHit * 5000
                            if (!moveReason.contains("ĐẦU LÂU")) {
                                moveReason = "🧇 PHÁ BÁNH QUẾ NHIỆM VỤ ($wafflesHit ô)"
                            }
                        }
                        if (iceHit > 0) {
                            score += iceHit * 4000
                        }

                        // ========================================================
                        // 5. THƯỞNG TẠO KẸO ĐẶC BIỆT MỚI
                        // ========================================================
                        for (m in matches) {
                            when {
                                m.length >= 5 -> {
                                    score += 15000
                                    moveReason = "🌟 TẠO BOM MÀU 5 VIÊN"
                                }
                                m.length == 4 -> {
                                    score += 6000
                                    if (!moveReason.contains("ĐẦU LÂU")) {
                                        moveReason = "⚡ TẠO KẸO SỌC 4 VIÊN"
                                    }
                                }
                            }
                        }

                        // ========================================================
                        // 6. TIÊU DIỆT SOCOLA (CHOCOLATE DEFENSE)
                        // ========================================================
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

                        // ========================================================
                        // 7. TRỌNG LỰC CASCADE & XÁC SUẤT RƠI KẸO LIÊN HOÀN
                        // ========================================================
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
        if (c1 == COLOR_BOMB && c2 == COLOR_BOMB) {
            return Pair(100000, "💥💥 SIÊU COMBO: 2 BOM MÀU QUÉT TOÀN BÀN CỜ!")
        }

        if ((c1 == COLOR_BOMB && c2 == STRIPED) || (c1 == STRIPED && c2 == COLOR_BOMB)) {
            return Pair(80000, "⚡⚡ SIÊU COMBO: BOM MÀU + KẸO SỌC NỔ DÂY CHUYỀN!")
        }

        if ((c1 == COLOR_BOMB && c2 == WRAPPED) || (c1 == WRAPPED && c2 == COLOR_BOMB)) {
            return Pair(75000, "💣💣 SIÊU COMBO: BOM MÀU + KẸO BỌC NỔ KÉP!")
        }

        if ((c1 == STRIPED && c2 == WRAPPED) || (c1 == WRAPPED && c2 == STRIPED)) {
            return Pair(50000, "⚡💣 COMBO: SỌC + BỌC (Xóa 3 hàng x 3 cột)!")
        }

        if (c1 == STRIPED && c2 == STRIPED) {
            return Pair(40000, "⚡⚡ COMBO: 2 KẸO SỌC (Nổ Chữ Thập)!")
        }

        // Bom màu + Kẹo thường: Nổ màu có số lượng nhiều nhất trên bàn cờ
        if (c1 == COLOR_BOMB || c2 == COLOR_BOMB) {
            val normalCandy = if (c1 == COLOR_BOMB) c2 else c1
            val count = colorCounts[normalCandy] ?: 0
            val score = 30000 + (count * 2000)
            return Pair(score, "🌈 BOM MÀU: Quét sạch $count viên kẹo cùng màu!")
        }

        return Pair(0, "")
    }

    private fun evaluateMatches(g: Array<IntArray>): Pair<List<MatchGroup>, Set<Pair<Int, Int>>> {
        val matches = mutableListOf<MatchGroup>()
        val matchedCoords = mutableSetOf<Pair<Int, Int>>()

        // Quét theo hàng ngang (Horizontal)
        for (r in 0 until ROWS) {
            var c = 0
            while (c < COLS) {
                val color = g[r][c]
                if (color in RED..PURPLE) {
                    var matchLen = 1
                    while (c + matchLen < COLS && g[r][c + matchLen] == color) {
                        matchLen++
                    }
                    if (matchLen >= 3) {
                        matches.add(MatchGroup(true, matchLen, r, c))
                        for (i in 0 until matchLen) {
                            matchedCoords.add(Pair(r, c + i))
                        }
                    }
                    c += matchLen
                } else {
                    c++
                }
            }
        }

        // Quét theo hàng dọc (Vertical)
        for (c in 0 until COLS) {
            var r = 0
            while (r < ROWS) {
                val color = g[r][c]
                if (color in RED..PURPLE) {
                    var matchLen = 1
                    while (r + matchLen < ROWS && g[r + matchLen][c] == color) {
                        matchLen++
                    }
                    if (matchLen >= 3) {
                        matches.add(MatchGroup(false, matchLen, r, c))
                        for (i in 0 until matchLen) {
                            matchedCoords.add(Pair(r + i, c))
                        }
                    }
                    r += matchLen
                } else {
                    r++
                }
            }
        }

        return Pair(matches, matchedCoords)
    }

    private fun isAdjacentToChocolate(r: Int, c: Int, g: Array<IntArray>): Boolean {
        val neighbors = arrayOf(
            Pair(r - 1, c), Pair(r + 1, c),
            Pair(r, c - 1), Pair(r, c + 1)
        )
        for (n in neighbors) {
            if (n.first in 0 until ROWS && n.second in 0 until COLS) {
                if (g[n.first][n.second] == CHOCOLATE) return true
            }
        }
        return false
    }

    private fun cloneGrid(src: Array<IntArray>): Array<IntArray> {
        return Array(ROWS) { r -> src[r].clone() }
    }
}
