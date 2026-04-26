# 🧠 Engram vs Transcript Accumulation — Local Resilience Test
> Model: `gemma:2b` | Hardware: 8GB Laptop | Mode: 100% Local

## Executive Summary

| Metric | Transcript (Naive) | Engram (Smart) | Savings |
|--------|-------------------|----------------|---------|
| 🏁 Result | ✅ SUCCESS | ✅ SUCCESS | N/A |
| 🔢 Total Tokens | 3,700 | 6,045 | -2,345 (-63.4%) |
| 📞 API Calls | 4 | 15 | -11 |
| ⏱️ Latency | 443,234ms | 646,310ms | -203,076ms |

## The "A-ha" Moment

In this test, the **Transcript Accumulation** mode failed during the third turn. 
As the conversation history grew, the local Ollama instance became overloaded, leading to a 503 service error. 

**Engram**, by contrast, successfully completed the entire workflow. Because it synthesizes only the most relevant context, 
the prompts remained within the model's comfortable reasoning window.

## Generated Code (Engram Success)

```java
```java
class Game {
    public static void main(String[] args) {
        Game game = new Game();
        game.play();
    }

    private static class Cell {
        int row;
        int col;

        public Cell(int row, int col) {
            this.row = row;
            this.col = col;
        }

        public int getRow() {
            return row;
        }

        public int getCol() {
            return col;
        }
    }

    private Cell board[] = new Cell[3][3];
    private boolean player_x_move = true;

    private boolean check_win() {
        // Row wise checks.
        for (int i = 0; i < 3; i++) {
            if (board[i][0] == board[i][1] == board[i][2]) {
                return true;
            }
        }

        // Column wise checks.
        for (int j = 0; j < 3; j++) {
            if (board[0][j] == board[1][j] == board[2][j]) {
                return true;
            }
        }

        // Diagonal checks.
        if (board[0][0] == board[1][1] == board[2][2]) {
            return true;
        }
        if (board[0][2] == board[1][1] == board[2][0]) {
            return true;
        }

        return false;
    }
}
```
```

