# CanalAndWell

[![](https://jitpack.io/v/iamsoNewBee/Canal-and-Well.svg)](https://jitpack.io/#iamsoNewBee/Canal-and-Well)

A water canal system mod for Minecraft 1.7.10. Build irrigation networks with automatic water propagation, dynamic block connections, and three material variants.

## Features

- **Directional state machine** — A single `BlockCanal` + `TileEntityCanal` replaces the 18+ block types used in the original Canal mod. Connection shapes auto-update when neighboring canals are placed or destroyed.
- **Three material variants** — Stone, Dirt, and Sand canals, each with unique textures, sounds, and harvest tools. All share the same connection logic.
- **Auto-connecting shapes** — Straight, T-junction (4 orientations), and Cross (4-way). Shapes transition automatically based on adjacent canal placement.
- **Closed canal** — Sneak + right-click to lock a canal into a straight pipe. Closed canals don't participate in auto-connection on their off-axis sides.
- **Water propagation** — Wet canals spread water to adjacent dry canals and spawn flowing water blocks in adjacent air spaces.
- **Glass bottle filling** — Right-click a wet canal with a glass bottle to fill it into a water bottle (configurable).
- **Configurable** — Tick rate, water flow range, bottle filling, debug logging — all adjustable via Forge config.

## Canal Shapes

| Shape | Connections | Description |
|-------|-------------|-------------|
| **Straight NS** | North + South | Default placement along NS axis |
| **Straight EW** | East + West | Default placement along EW axis |
| **T-NW** | North + South + West | T-junction, NS trunk with W branch |
| **T-NE** | North + South + East | T-junction, NS trunk with E branch |
| **T-EN** | East + West + North | T-junction, EW trunk with N branch |
| **T-ES** | East + West + South | T-junction, EW trunk with S branch |
| **Cross** | All 4 directions | Four-way intersection |
| **Closed** | Axis-locked | Straight pipe, off-axis connections blocked |

## Variants

| Variant | Material | Hardness | Tool | Sound |
|---------|----------|----------|------|-------|
| **Stone** | Rock | 1.5 | Pickaxe | Stone |
| **Dirt** | Ground | 1.0 | Shovel | Gravel |
| **Sand** | Sand | 0.8 | Shovel | Sand |

## Usage

1. **Place** a canal block — it aligns to your facing direction (NS or EW).
2. **Connect** by placing another canal adjacent — shapes auto-update into T-junctions or Crosses.
3. **Bring water** adjacent to a canal — it detects the source and becomes wet.
4. **Sneak + Right-click** to toggle closed mode (locks shape, blocks off-axis connections).
5. **Right-click with glass bottle** on a wet canal to fill water bottles.

## Configuration

All settings are in `config/CanalAndWell.cfg`:

| Setting | Default | Description |
|---------|---------|-------------|
| `tickRate` | 10 | Ticks between water propagation checks |
| `waterFlowRange` | 64 | Max distance water can flow through canals |
| `cleanupFlowingWater` | true | Remove adjacent flowing water when canal breaks |
| `enableBottleFill` | true | Allow glass bottle filling from wet canals |
| `debugLogging` | false | Enable detailed console logging |

## Building

Requires **ForgeGradle** for Minecraft 1.7.10 (GTNH buildscript).

```bash
# Setup workspace
./gradlew setupDecompWorkspace

# Build the mod
./gradlew build

# Output: build/libs/CanalAndWell-<version>.jar
```

## Project Structure

```
src/main/java/com/catfish/canalandwell/
├── CanalAndWell.java          # Main mod class
├── ClientProxy.java           # Client-side proxy
├── CommonProxy.java           # Server-side proxy
├── Config.java                # Configuration handler
├── block/
│   └── BlockCanal.java        # Canal block — state machine + 3 variants
└── tileentity/
    └── TileEntityCanal.java   # Water detection + propagation

src/main/resources/assets/canalandwell/
├── lang/
│   ├── en_US.lang             # English localization
│   └── zh_CN.lang             # Chinese localization
└── textures/blocks/           # 51 variant textures + shared assets
```

## Credits

- Original Canal mod scripting reference (`data_dump.txt`)
- Built on the [GTNH ExampleMod](https://github.com/GTNewHorizons/ExampleMod1.7.10) build system

## License

This project is licensed under the MIT License — see the LICENSE file for details.
