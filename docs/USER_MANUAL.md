# VarioAppli - Pilot User Manual (Manuel du Pilote)

Welcome to **VarioAppli**, a high-precision Android variometer application engineered specifically for paragliding and hang-gliding pilots. This guide explains how to connect your hardware sensor dongle, configure system permissions, interpret cockpit instruments, and utilize in-flight tactical navigation tools.

---

## 1. Hardware Requirements & Setup

To deliver ultra-low acoustic latency (< 30 ms) and barometric precision, VarioAppli connects directly to an external sensor dongle via USB-OTG:

- **Microcontroller:** SAMD21 ARM Cortex-M0+ (CDC-ACM serial class).
- **Barometric Sensor:** Bosch Sensortec BMP390 (ultra-low noise, 24-bit resolution).
- **Cabling:** High-quality USB-C to USB-C (or Micro-USB to USB-C) **OTG data cable**.
- **Smartphone:** Android 8.0 (Oreo) or higher with USB-Host (OTG) support.

### Hardware Connection
1. Power on your Android smartphone.
2. Connect the sensor dongle to the phone's USB port using the OTG cable.
3. The dongle's LED indicator will illuminate, signaling successful bus power delivery.

---

## 2. First-Time Setup & Permissions

For uninterrupted flight logging and audio synthesis—even when your phone screen is switched off—the following permissions must be granted:

### 1. USB Device Permission
- When plugging in the dongle, Android displays: *"Open VarioAppli to handle this USB device?"*
- Check **"Always open VarioAppli when this device is connected"** and tap **OK**.
- *Note:* If disconnected during flight, tap the **USB Reconnect** icon in the cockpit header to re-establish communication without restarting the application.

### 2. Location (GNSS / GPS) Permission
- Select **"While using the app"** or **"Allow all the time"** and ensure **"Use precise location"** is enabled.
- GPS provides ground altitude for automatic QNH barometric calibration at takeoff, ground speed, glide ratio, and GPX track recording.

### 3. Battery Optimization Exemption (Crucial)
Android's aggressive Doze mode will terminate background audio if battery optimization is active:
1. Open the **Diagnostics Modal** (tap the **Terminal / Diagnostic** icon `[>_]` in the top bar).
2. Look at the **Battery Optimization** card. If highlighted in orange/red:
3. Tap **"Request Exemption"** or **"Open Battery Settings"**.
4. Set VarioAppli's battery usage to **"Unrestricted"** (or disable battery optimization).

---

## 3. Cockpit Overview & Instruments

The main interface is divided into two primary tabs: **VARIO** (Flight Instruments) and **MAP** (Tactical Navigation).

```
+-----------------------------------------------------------+
| [GPS Fix: OK]   [USB: 115200]   [00:42:15]   [Mute] [Diag]|
+-----------------------------------------------------------+
|                                                           |
|             +2.4 m/s            [======|======]           |
|          Vertical Speed          Analog Ladder            |
|                                                           |
|                 1 845 m                                   |
|            Barometric Altitude (MSL)                      |
|                                                           |
|   +-----------------------+   +-----------------------+   |
|   |    Plafond Atteint    |   |    Distance au Déco   |   |
|   |        2 150 m        |   |         4.2 km        |   |
|   +-----------------------+   +-----------------------+   |
|                                                           |
|   [================ DÉMARRER / ARRÊTER LE VOL ================]   |
+-----------------------------------------------------------+
|                 [ VARIO ]       [ CARTE ]                 |
+-----------------------------------------------------------+
```

### Top Status Header
- **GPS Status Chip:** Indicates GNSS satellite fix quality (`GPS Fix` in green, `No Fix` in yellow/grey).
- **Sensor / USB Chip:** Shows active baud rate and USB connection status (`USB OK` in green, `Disconnected` in red). Tapping this re-triggers the USB probe.
- **Flight Timer:** Elapsed flight duration formatted in `HH:MM:SS`.
- **Mute Toggle Button:** Instantly mutes or unmutes the audio variometer.
- **Diagnostics Button (`[>_]`):** Opens the real-time engineering and sensor modal.

### Instrument Cards
- **Digital Vertical Speed ($V_z$):** Large, high-contrast readout in meters per second (m/s). Positive values indicate lift; negative values indicate sink.
- **Climb / Sink Ladder Gauge:** Dynamic visual scale ($ -5.0\text{ m/s}$ to $+5.0\text{ m/s}$). In thermals, a vibrant green column rises above zero; in sinking air, an amber/red column drops below zero.
- **Barometric Altitude:** High-precision altitude above mean sea level (MSL) calibrated automatically using the takeoff reference.
- **Plafond Atteint (Peak Ceiling):** Highest altitude achieved during the current flight session.
- **Distance au Déco (Takeoff Distance):** Straight-line horizontal distance between the takeoff launch coordinates and current position.

---

## 4. Acoustic Variometer Response

The audio engine produces distinct acoustic signatures for climbs, sinking air, and calm glides:

| Flight Condition | Vertical Speed ($V_z$) | Acoustic Tone | Description |
|---|---|---|---|
| **Strong Thermal** | $+3.0\text{ to }+5.0\text{ m/s}$ | Fast, high-pitched beeps (800–1200 Hz, 5–6 beeps/sec) | Short, crisp pulses with 85% duty cycle signaling strong lift core. |
| **Moderate Lift** | $+1.0\text{ to }+3.0\text{ m/s}$ | Medium beeps (550–800 Hz, 3–5 beeps/sec) | Comfortable cadence for centering thermals. |
| **Weak Lift** | $+0.3\text{ to }+1.0\text{ m/s}$ | Slow, lower beeps (400–550 Hz, 1.5–3 beeps/sec) | Alerts pilot to thermal entry or buoyant air. |
| **Deadband (Glide)**| $-2.0\text{ to }+0.3\text{ m/s}$ | **Silence** | Dead zone to minimize acoustic fatigue during normal transitions. |
| **Sink Alarm** | $\le -2.0\text{ m/s}$ | Continuous low tone (400 Hz down to 200 Hz) | Steady alarm indicating strong sink or downdraft. |

> [!TIP]
> You can test all acoustic tones on the ground before launching by opening the **Diagnostics Modal** and tapping the tone test buttons.

---

## 5. Tactical Map & Flight Planning

Switch to the **CARTE (MAP)** tab to view real-time situational navigation:

### 1. Moving Map & Glider Orientation
- The map centers on your position, with a glider icon pointing along your current ground track.
- Tap the **Center Glider** floating button to lock the map camera to your glider.

### 2. Thermal Breadcrumb Trail
- Your flight path is traced with color-coded points updated at 1 Hz:
  - **Green:** Climb ($V_z \ge +0.5\text{ m/s}$) — helps visualize thermal cores while circling.
  - **Grey / Blue:** Neutral glide ($-1.5\text{ to }+0.5\text{ m/s}$).
  - **Orange / Red:** Sinking air ($V_z < -1.5\text{ m/s}$).

### 3. Terrain Elevation & AGL Clearance
- The tactical engine calculates your height **Above Ground Level (AGL)** in real time:
  $$\text{AGL} = \text{Altitude MSL} - \text{Terrain Elevation}$$
- Digital Elevation data is cached locally so AGL readouts function reliably even when outside cellular coverage.

### 4. Conical Glide Reach (Glide Cone Overlay)
- A dynamic polygon on the map depicts your reachable landing zone based on your current AGL and estimated glide ratio ($L/D$).
- If a designated landing zone or field lies within the cone, you can reach it safely without needing additional lift.

### 5. Flight Itinerary Planning
- Long-press on the map to define:
  - **Takeoff Point (Déco)**
  - **Turnpoints / Waypoints**
  - **Landing Zone (Atterrissage)**
- The route engine automatically computes the required glide ratio, leg distances, and warns against terrain obstacles or power lines along the trajectory.

---

## 6. Flight Operations Walkthrough (Standard Procedure)

```mermaid
flowchart LR
    A["1. Pre-Flight Standby<br/>(Connect USB, verify GPS Fix)"] --> B["2. Takeoff<br/>(Tap Start Flight / Auto-Trigger)"]
    B --> C["3. In Flight<br/>(Thermal beeps, Screen on/off)"]
    C --> D["4. Landing<br/>(Tap Stop Flight)"]
    D --> E["5. Export Track<br/>(Save GPX log)"]
```

1. **Pre-Flight Standby:**
   - Plug in the sensor dongle and launch VarioAppli.
   - Confirm the status chip shows **GPS Fix OK** and **USB OK**.
   - Audio remains silent during standby to prevent unnecessary beeping while walking on the launchpad.
2. **Takeoff:**
   - Tap **"Démarrer le vol" (Start Flight)** or launch into the air (automatic takeoff detection engages when ground speed and climb exceed threshold).
   - The app records the takeoff coordinates and locks the takeoff barometric pressure reference to calibrate QNH.
   - The variometer audio activates.
3. **In-Flight:**
   - Mount your phone onto your flight deck or slip it into your harness pocket. With `PARTIAL_WAKE_LOCK`, audio continues playing uninterrupted even when the screen is turned off.
4. **Landing & Session Wrap-up:**
   - After landing, tap **"Arrêter le vol" (Stop Flight)**.
   - Audio is muted immediately.
   - **Save Confirmation Dialog:** A confirmation dialog appears presenting your session summary (flight duration, peak ceiling, and total distance). Choose:
     - **"💾 Enregistrer" (Save Track):** Saves the track to device storage in standard GPX format (`/Documents/VarioAppli/Tracks/`).
     - **"🗑️ Ne pas enregistrer" (Discard):** Discards the flight track without saving to storage (preventing short tests or unwanted logs from cluttering your files).
     - **"Annuler" (Cancel):** Resumes flight recording if tapped accidentally.

---

## 7. Hike & Fly Operations Mode

VarioAppli includes a specialized **Hike & Fly** mode tailored for pilots who hike, ski-tour, or climb to their takeoff spot.

### 1. Selecting Hike & Fly Mode
- In standby (before starting a recording), look at the top of the **VARIO** cockpit.
- Tap **"🥾 Hike & Fly"** on the mode selector tab.
- The main action button transitions to **"DÉMARRER LA MONTÉE (HIKE)"**.

### 2. Ascent Phase (Montée)
- Tap the button when starting your ascent on foot, skis, or snowshoes.
- **Automatic Waypoint:** An initial waypoint **"Départ Rando"** is marked at your starting position with current altitude.
- **Muted Variometer:** Audio beeping is automatically silenced so you don't get false climb/sink chirps while walking.
- **Elevation Gain ($D^+$) Display:** The primary central instrument replaces instantaneous $V_z$ with your cumulative elevation gain ($D^+$ in meters) since the start of the hike.
- **Reference Altitudes:** Displays starting altitude, current barometric altitude, and total ground distance covered.
- Continuous GPX recording logs your exact hiking trail tagged with hiking phase metadata.

### 3. Transitioning to Flight Mode (Passage au Vol)
When you reach the summit / takeoff launch site and prepare your wing:
1. Tap the main action button (now labeled **"TERMINER MONTÉE / VOLER"**).
2. A transition dialog appears with three choices:
   - **"Passer en mode Vol" (Proceed to Flight):**
     - Places a distinct summit waypoint **"Décollage / Vol"** on the track and map.
     - Un-mutes the acoustic variometer.
     - Locks the current summit coordinates as your official takeoff location.
     - Switches the primary display back to the $V_z$ climb/sink ladder.
     - Continues recording your flight in the **same continuous GPX track**.
   - **"Terminer l'enregistrement" (End Session):** Prompts you to confirm whether or not to save the hike track (e.g. if conditions are unsuitable to fly and you walk down).
   - **"Continuer la montée" (Continue Hike):** Dismisses the dialog and resumes ascent tracking.
3. Once in Flight mode, land as normal and tap **"Arrêter le vol"**. The confirmation dialog appears before saving the multi-phase GPX track with all transition waypoints included.

---

## 8. GPX Track Details & Interactive Elevation Profile

When viewing previously saved tracks or loading external GPX tracks on the **CARTE (MAP)** tab:

### 1. Automatic Map Centering on Track Start
- As soon as a track is selected from the file picker, the map **automatically centers and zooms (level 15)** onto the track's starting point (trailhead or takeoff fix).
- Live pilot auto-tracking is temporarily suspended while you inspect the track, preventing the map from snapping back to your current location.
- Embedded transition waypoints (e.g., *Départ Rando*, *Décollage / Vol*, *Atterrissage*) are displayed with distinct visual markers on the map.

### 2. Track Summary Banner & Details Button
- A floating header card displays the track filename, point count, and waypoint count.
- Tap the **"[📈 Détails]"** toggle button on this card to open the **Flight & Track Profile Card**.
- Tap **"[✕]"** to close the track view and restore normal map centering on your live position.

### 3. Track Summary Statistics
The profile card displays essential statistics calculated from the track:
- **Total Distance:** Total 3D ground distance covered (km).
- **Duration:** Total elapsed time (`HH:MM:SS`).
- **Elevation Gain ($D^+$) & Loss ($D^-$):** Cumulative ascent and descent.
- **Altitude Range:** Minimum and maximum altitude attained ($Alt_{\min} - Alt_{\max}$).
- **Max Speed & Average Speed:** Peak ground speed and overall speed.
- **Thermal Lift & Sink Extremes:** Maximum positive climb rate and deepest sink.

### 4. Interactive Elevation Profile & Finger Scrubber
- The card renders an elevation vs. distance profile graph with a smooth gradient fill and min/max altitude bounds.
- **Finger Scrubbing:** Touch or slide your finger horizontally anywhere across the profile graph:
  - A vertical crosshair and floating indicator badge display the exact altitude, distance, and $V_z$ at that location.
  - An animated **pulsing beacon target** on the map moves synchronously to the exact geographical position along the flight track.
  - The map smoothly pans to follow your finger scrubbing, allowing you to review specific thermals, ridges, or transition glides effortlessly.

---

## 9. Diagnostics Terminal & Troubleshooting

Tap the **Terminal icon `[>_]`** in the top bar to open the diagnostic window.

### Built-in Diagnostic Tools
- **Live LK8EX1 Sentence Stream:** Displays incoming ASCII sentences in real time. Verify that sentences begin with `$LK8EX1` and end with a valid checksum.
- **Frame & Error Counters:** Shows total valid frames processed versus corrupted/dropped frames.
- **Baud Rate Switcher:** If your sensor dongle uses a custom firmware baud rate, choose between `115200`, `57600`, `38400`, `19200`, or `9600`.
- **Audio Tone Simulator:** Tap `Climb +1.5 m/s`, `Climb +3.0 m/s`, or `Sink -3.0 m/s` to confirm smartphone speakers are functioning properly.

### Troubleshooting Common Issues

#### Issue: "USB Disconnected" or no data stream
- **Check Cable:** Ensure your cable supports data transfer (many charging cables only carry power). An OTG-compatible cable is required.
- **Reconnect:** Tap the **USB Reconnect** icon in the header.
- **Baud Rate Mismatch:** Open the Diagnostics Modal and verify the baud rate matches your hardware firmware (default is `115200`).

#### Issue: Audio stops after 1–2 minutes with screen off
- **Cause:** Android Doze mode or manufacturer power management is suspending the background service.
- **Remedy:** Open the Diagnostics Modal, tap **Request Battery Exemption**, and set VarioAppli to **Unrestricted**. Ensure no third-party battery saver applications are targeting VarioAppli.

#### Issue: Altitude reads 0 m or appears incorrect
- **Cause:** GPS fix was not established before takeoff, preventing ground QNH calibration.
- **Remedy:** Wait for the GPS status chip to turn green (`GPS Fix: OK`) on the launchpad before tapping **Start Flight**.
