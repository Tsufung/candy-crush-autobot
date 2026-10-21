package com.candyrush.autobot.solver

/**
 * Thuật toán AI giải Match-3 cho Candy Crush Saga
 * Tính toán tất cả các nước đi hoán đổi (swap) hợp lệ, chấm điểm theo các combo đặc biệt
 * và ưu tiên kích hoạt chuỗi nổ liên hoàn (cascade) ở đáy bàn cờ.
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
        const val SPECIAL = 7

        const val ROWS = 9
        const val COLS = 9
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
     * Tìm nước đi tối ưu nhất từ ma trận kẹo 9x9
     */
    fun findBestMove(grid: Array<IntArray>): Move? {
        var bestMove: Move? = null
        var maxScore = -1

        // 2 hướng hoán đổi cơ bản: sang phải và xuống dưới
        val directions = arrayOf(
            Pair(0, 1), // Phải
            Pair(1, 0)  // Dưới
        )

        for (r in 0 until ROWS) {
            for (c in 0 until COLS) {
                if (grid[r][c] == EMPTY) continue

                for (dir in directions) {
                    val nr = r + dir.first
                    val nc = c + dir.second

                    if (nr in 0 until ROWS && nc in 0 until COLS && grid[nr][nc] != EMPTY) {
                        if (grid[r][c] == grid[nr][nc]) continue // Cùng màu không tráo

                        // Thử hoán đổi
                        val tempBoard = cloneGrid(grid)
                        val tmp = tempBoard[r][c]
                        tempBoard[r][c] = tempBoard[nr][nc]
                        tempBoard[nr][nc] = tmp

                        val (matches, matchedCoords) = evaluateMatches(tempBoard)
                        if (matchedCoords.isNotEmpty()) {
                            var score = matchedCoords.size * 100

                            // Điểm thưởng cho combo tạo kẹo đặc biệt
                            for (m in matches) {
                                when {
                                    m.length >= 5 -> score += 2500 // Bom màu (Color Bomb)
                                    m.length == 4 -> score += 800  // Kẹo sọc (Striped Candy)
                                }
                            }

                            // Ưu tiên nổ ở đáy bàn cờ để tạo chuỗi rơi cascade
                            val bottomRow = maxOf(r, nr)
                            score += bottomRow * 25

                            if (score > maxScore) {
                                maxScore = score
                                bestMove = Move(r, c, nr, nc, score)
                            }
                        }
                    }
                }
            }
        }

        return bestMove
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
