# Behavioral Telemetry & Socratic AI Agent System

A privacy-first, zero-cloud behavioral telemetry pipeline and cognitive insight engine.

---

## Architecture Overview

```
                                [ Android Device ]
┌─────────────────────────────────────────────────────────────────────────────────┐
│ • ScreenStateReceiver (Screen on/off, unlock)                                   │
│ • PowerReceiver (Power connected/disconnected)                                  │
│ • CollectorService (Low-overhead persistent foreground service)                 │
│ • Room Database (Local SQLite - completely private)                             │
│ • OnDeviceSynthesizer (Clusters raw timestamps into structured summaries)       │
│ • GeminiClient (Calls Gemini 3.8 Flash API directly from phone over HTTPS)      │
│ • Socratic Review rendered live in-app on phone screen (No PC required!)        │
│ • (Optional) Export to Downloads for PC analysis                                │
└─────────────────────────────────────────────────────────────────────────────────┘
```

---

## Directory Structure

```
behavioral-telemetry/
├── agent/                         # Python-based Socratic Behavioral Agent & Synthesizer
│   ├── analyzer.py                # Runs the cognitive analysis pipeline & Gemini Socratic agent
│   ├── synthesizer.py             # Transforms raw timestamp events into behavioral episodes
│   ├── models.py                  # Pydantic data schemas & metric definitions
│   ├── prompts.py                 # Socratic Behavioral Scientist prompt templates
│   ├── generate_sample_data.py    # Generates 7-day realistic behavioral telemetry
│   └── sample_telemetry.jsonl     # Sample dataset demonstrating app churn, bedtime traps, etc.
│
├── android/                       # Native Android Project (Kotlin + Jetpack Room)
│   ├── app/
│   │   ├── src/main/java/com/behavioral/telemetry/
│   │   │   ├── data/              # Room Database, DAO, Entity
│   │   │   ├── receivers/         # ScreenReceiver, PowerReceiver, BootReceiver
│   │   │   ├── service/           # CollectorService (Foreground Service)
│   │   │   ├── export/            # DataExporter (JSONL export to Downloads)
│   │   │   └── ui/                # MainActivity (Jetpack Compose)
│   │   └── src/main/AndroidManifest.xml
│   ├── build.gradle.kts
│   └── settings.gradle.kts
│
└── README.md
```

---

## Quickstart: Running the Analysis Engine (PC)

You can run the Socratic analysis engine right now with the generated 7-day sample telemetry:

```bash
cd behavioral-telemetry/agent

# Generate fresh sample telemetry (or use sample_telemetry.jsonl)
python generate_sample_data.py

# Run the analyzer (Outputs cognitive metrics table & episode breakdown)
python analyzer.py sample_telemetry.jsonl

# To enable the full qualitative Socratic Behavioral Scientist review via Gemini:
# Set your Gemini API key:
$env:GEMINI_API_KEY="your-api-key-here"
python analyzer.py sample_telemetry.jsonl
```

---

## Building the Android App

1. Open **Android Studio**.
2. Select **Open** and choose the `behavioral-telemetry/android` directory.
3. Allow Gradle to sync.
4. Connect your Android phone with **USB Debugging** enabled in Developer Options.
5. Click **Run** (`Shift + F10`) to install the app on your phone.
6. In the app:
   - Tap **Disable Battery Optimization** so the Android OS does not kill the collector in deep sleep.
   - The app immediately begins logging screen locks, unlocks, and charging states locally.
   - When you are ready for an analysis session, tap **Export Telemetry (.jsonl)**.
   - Copy the exported file from your phone's `Downloads` folder to your PC and run `python analyzer.py your_exported_file.jsonl`.
