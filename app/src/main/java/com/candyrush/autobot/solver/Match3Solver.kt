package com.candyrush.autobot.solver

/**
 * Thuật toán AI giải Match-3 cho Candy Crush Saga (Meta 2026 Edition)
 * Đã sửa lỗi:
 * 1. BẮT BUỘC nước đi hoán đổi phải trực tiếp tạo ra Match-3 tại vị trí tráo kẹo.
 * 2. Hỗ trợ Super Combo kẹo đặc biệt (Bom màu + Bom màu, Bom màu + Sọc, Sọc + Bọc).
 * 3. Hỗ trợ danh sách nước đi bị cấm (Banned Moves) chống kẹt vòng lặp vô tận.
 * 4. Ưu tiên nổ đáy bàn cờ để kích hoạt chuỗi sụt đổ liên hoàn (Cascade).
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

        // Kẹo đặc biệt
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
        val score: Int
    )

    data class MatchGroup(
        val isHorizontal: Boolean,
        val length: Int,
        val startRow: Int,
        val startCol: Int
    )

    /**
     * Tìm nước đi tối ưu nhất, loại trừ các nước đi đang bị khóa do vừa thất bại
     */
    fun findBestMove(grid: Array<IntArray>, bannedMoves: Set<String> = emptySet()): Move? {
        var bestMove: Move? = null
        var maxScore = -1

        // 2 hướng hoán đổi cơ bản: sang phải và xuống dưới
        val directions = arrayOf(
            Pair(0, 1), // Phải
            Pair(1, 0)  // Dưới
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

                        // 1. Kiểm tra SUPER COMBO giữa các kẹo đặc biệt
                        val superScore = evaluateSuperCombos(c1, c2)
                        if (superScore > 0) {
                            if (superScore > maxScore) {
                                maxScore = superScore
                                bestMove = Move(r, c, nr, nc, superScore)
                            }
                            continue
                        }

                        // 2. Thử hoán đổi Match-3
                        val tempBoard = cloneGrid(grid)
                        tempBoard[r][c] = c2
                        tempBoard[nr][nc] = c1

                        val (matches, matchedCoords) = evaluateMatches(tempBoard)

                        // ĐIỀU KIỆN TIÊN QUYẾT: Nước đi PHẢI tạo ra match chứa ít nhất 1 trong 2 viên kẹo vừa tráo!
                        // Nếu không, Candy Crush sẽ từ chối nước đi và bật ngược lại!
                        val involvesSwapped = matchedCoords.contains(Pair(r, c)) || matchedCoords.contains(Pair(nr, nc))
                        if (!involvesSwapped) continue

                        var score = matchedCoords.size * 150

                        // Thưởng điểm cho combo tạo kẹo đặc biệt
                        for (m in matches) {
                            when {
                                m.length >= 5 -> score += 3500 // Bom màu (Color Bomb)
                                m.length == 4 -> score += 1200 // Kẹo sọc (Striped Candy)
                            }
                        }

                        // Ưu tiên nổ ở đáy bàn cờ để tạo chuỗi sụt đổ dây chuyền (Cascade)
                        val bottomRow = maxOf(r, nr)
                        score += bottomRow * 40

                        if (score > maxScore) {
                            maxScore = score
                            bestMove = Move(r, c, nr, nc, score)
                        }
                    }
                }
            }
        }

        return bestMove
    }

    private fun evaluateSuperCombos(c1: Int, c2: Int): Int {
        if (c1 == COLOR_BOMB && c2 == COLOR_BOMB) return 60000 // Quét sạch 100% bàn cờ
        if ((c1 == COLOR_BOMB && c2 == STRIPED) || (c2 == COLOR_BOMB && c1 == STRIPED)) return 45000 // Biến kẹo thành sọc
        if ((c1 == COLOR_BOMB && c2 == WRAPPED) || (c2 == COLOR_BOMB && c1 == WRAPPED)) return 35000
        if ((c1 == STRIPED && c2 == WRAPPED) || (c2 == STRIPED && c1 == WRAPPED)) return 28000 // Chữ thập 3x3
        if (c1 == WRAPPED && c2 == WRAPPED) return 22000 // Nổ 5x5 kép
        if (c1 == STRIPED && c2 == STRIPED) return 16000 // Nổ ngang + dọc
        if (c1 == COLOR_BOMB || c2 == COLOR_BOMB) return 12000 // Bom màu ăn bất kỳ kẹo thường nào
        return 0
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
