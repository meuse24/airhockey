# AirHockey P2P

Fast two‑player air hockey over Wi‑Fi Direct (Android), built with LibGDX + Box2D. Each player runs the game on their own phone; the field is mirrored so both see their goal at the bottom.

## Features
- Wi‑Fi Direct P2P networking with reliable critical events and low‑latency UDP game data
- Host‑authoritative puck simulation with periodic sync
- Best‑of‑5 scoring, goal overlay, and game‑over flow
- Start/Retry ready‑handshake (both players must confirm)
- Box2D physics for puck + kinematic pushers

## Requirements
- Android API 29+
- Two Android devices on the same Wi‑Fi Direct network

## Quick Start
1. Open the app on both devices.
2. On one device, tap **Search Players** and connect.
3. Tap **Start Game** (both must confirm in‑game).

## Project Structure
- `app/` – Android launcher (Compose UI, permissions)
- `core/` – Game logic, screens, Box2D, networking protocol
- `gdx-android/` – LibGDX Android backend

## Notes
- Network test tools are available from the Lobby via **Network Test** when connected.
- The keystore is intentionally excluded from Git.

## License
TBD