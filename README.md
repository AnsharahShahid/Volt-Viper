# ⚡ Volt Viper

A glowing, arcade-style snake game written in pure Java (Swing) — no external libraries.
Play solo or head-to-head, with synthesized sound effects, particle effects and power-ups.

## Features

- **1 or 2 players** on the same keyboard (last snake alive wins)
- **Solid or wrap-around walls**
- **3 difficulties:** Easy, Normal, Hard (speed ramps up as you eat)
- **Power-ups:** gold bonus points, slow-motion, and shield
- **Persistent high scores** per difficulty / wall mode
- Neon glow graphics, particles, floating score text and generated sound (no audio files needed)

## Requirements

- Java 17 or newer (JDK) — [download](https://adoptium.net/)

## Run

```bash
# Linux / macOS
./run.sh

# Windows
run.bat
```

Or manually:

```bash
mkdir -p out
javac -d out src/VoltViper.java
java -cp out VoltViper
```

## Controls

| Where | Keys |
|-------|------|
| Menu | Up/Down select option, Left/Right change it, Space/Enter start |
| 1 player | Arrow keys or WASD |
| 2 players | P1 = WASD, P2 = Arrow keys |
| In game | Space / P pause, M mute, Esc back to menu |

## Project structure

```
VoltViper/
├── src/VoltViper.java   # the whole game
├── run.sh / run.bat     # build + launch scripts
├── LICENSE
└── README.md
```

High scores are saved to `~/.volt_viper_scores.properties`.

## License

MIT — see [LICENSE](LICENSE).
