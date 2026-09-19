# VarioAppli - Paragliding Variometer & Flight Navigation System

[![Kotlin](https://img.shields.io/badge/Kotlin-2.0.0-blue.svg)](https://kotlinlang.org)
[![Platform](https://img.shields.io/badge/Platform-Android-green.svg)](https://developer.android.com)
[![License](https://img.shields.io/badge/License-MIT-purple.svg)](LICENSE)

**VarioAppli** is an Android flight instrument and acoustic variometer designed for paragliding, hang-gliding, and ultralight aviation. It interfaces with an external SAMD21 + BMP390 hardware sensor over USB-OTG using the **LK8EX1** serial protocol, providing ultra-low latency acoustic thermal feedback, real-time AGL terrain clearance, dynamic glide reach cones, obstacle avoidance, and GPX flight logging.

---

## 📚 Documentation

Detailed documentation is organized in the [`docs/`](file:///C:/Users/Maurice/Documents/Dev/repo/VarioAppli/docs) directory:

- 📖 **[Pilot User Manual (docs/USER_MANUAL.md)](file:///C:/Users/Maurice/Documents/Dev/repo/VarioAppli/docs/USER_MANUAL.md)**: Hardware connection guide, Android permissions & Doze mode battery optimization setup, cockpit interface walkthrough, acoustic response definitions, tactical map usage, and troubleshooting.
- 🏗️ **[System Architecture & Technical Specification (docs/ARCHITECTURE.md)](file:///C:/Users/Maurice/Documents/Dev/repo/VarioAppli/docs/ARCHITECTURE.md)**: End-to-end architecture breakdown, zero-allocation fast path guarantees, LK8EX1 protocol & checksum engine, low-latency PCM audio synthesis formulas, foreground service lifecycle, and automated testing strategy.

---

## ⚡ Key Highlights

- **Ultra-Low Latency Audio (< 30 ms):** Direct PCM audio synthesis using `AudioTrack` configured with `PERFORMANCE_MODE_LOW_LATENCY` and `THREAD_PRIORITY_URGENT_AUDIO`.
- **Zero Dynamic Allocation on Fast Path:** Strict 0 heap allocation on the USB byte-parsing and audio synthesis loop, eliminating Android Garbage Collection (GC) pauses and audio clicks.
- **Uninterrupted Background Operation:** Runs as a persistent `ForegroundService` with `PARTIAL_WAKE_LOCK`, allowing continuous flight recording and audio even when the smartphone screen is turned off in a flight deck pocket.
- **Automatic QNH Ground Calibration:** Fuses 1 Hz internal GNSS ground truth at launch to calibrate barometric altitude.
- **Tactical Navigation & Relief:** Offline-capable digital elevation cache (Open-Meteo API / local models), Above Ground Level (AGL) readout, obstacle routing, and dynamic glide cone reach overlay.
- **Diagnostic Center:** Built-in engineering modal for inspecting raw LK8EX1 frames, testing acoustic climb/sink tones, and switching USB baud rates (9600 to 115200).

---

## 🛠️ Project Structure

```text
VarioAppli/
├── .antigravity/                  # Antigravity multi-agent orchestration
│   ├── config.json               # Agent roles and model mappings
│   └── prompts/                  # Specialized agent system prompts
│       ├── orchestrator.md       # Master coordinator & execution flow
│       ├── architect.md          # Architecture & real-time guarantees
│       ├── coder.md              # Kotlin/Compose implementation
│       ├── tester.md             # JUnit5 & memory allocation assertions
│       └── doc_writer.md         # Documentation writer & continuous sync
├── app/
│   └── src/
│       ├── main/java/com/vario/app/
│       │   ├── MainActivity.kt               # Cockpit UI & Tab controller
│       │   ├── VarioService.kt               # Foreground service & USB pipeline
│       │   ├── Lk8ex1Parser.kt               # Fast-path zero-allocation parser
│       │   ├── VarioAudioEngine.kt           # Real-time PCM audio synthesis
│       │   ├── VarioMath.kt                  # Baro formulas & audio curves
│       │   ├── MapScreen.kt                  # OSMDroid map, glider, trails
│       │   ├── ObstacleRoutingEngine.kt      # Terrain/airspace hazard engine
│       │   ├── TerrainElevationProvider.kt   # AGL elevation & LRU cache
│       │   ├── GlideOverlay.kt               # Conical glide reach calculation
│       │   ├── GpxTrackManager.kt            # GPX track recording & export
│       │   └── DebugModal.kt                 # Diagnostic engineering modal
│       └── test/java/com/vario/app/          # Unit test suites & alloc checks
├── docs/
│   ├── ARCHITECTURE.md           # Architecture & technical specification
│   └── USER_MANUAL.md            # Pilot user manual & flight operations
├── GEMINI.md                     # Project rules & autonomous agent directives
└── simulator.html                # Web-based hardware & LK8EX1 simulator
```

---

## 🚀 Building & Running

### Prerequisites
- Android Studio Ladybug (or newer) / Android SDK 34+
- Java 17+
- A physical Android device with USB-OTG support (emulators lack USB-Host hardware)

### Compilation
Build the debug APK using the Gradle wrapper:
```bash
./gradlew assembleDebug
```

Run the unit test suite:
```bash
./gradlew testDebugUnitTest
```

Deploy to a connected Android device:
```powershell
powershell -ExecutionPolicy Bypass -File scripts/deploy_app.ps1
```
