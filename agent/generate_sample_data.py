"""
Generates a realistic 7-day Android telemetry dataset (.jsonl)
reflecting authentic human cognitive patterns, work blocks, fatigue slumps, and bedtime traps.
"""

import json
from datetime import datetime, timedelta
import random

def generate_telemetry_stream(output_path: str, days: int = 7):
    events = []
    base_date = datetime(2026, 9, 20, 7, 0, 0) # Start on a Sunday/Monday
    
    apps_social = ["com.instagram.android", "com.twitter.android", "com.reddit.frontpage", "com.zhiliaoapp.musically"]
    apps_messaging = ["com.whatsapp", "com.discord", "org.telegram.messenger"]
    apps_work = ["com.slack", "com.google.android.gm", "com.notion.id"]
    apps_media = ["com.google.android.youtube", "com.spotify.music", "com.netflix.mediaclient"]
    apps_reading = ["com.amazon.kindle", "com.pocket.read"]

    current_time = base_date

    for day in range(days):
        day_start = base_date + timedelta(days=day)
        
        # 07:15 - Morning wake up: First unlock & check messages
        current_time = day_start.replace(hour=7, minute=random.randint(10, 25))
        
        # Power disconnected (unplugged from overnight charge)
        events.append({
            "timestamp": current_time.isoformat(),
            "event_type": "POWER_DISCONNECTED",
            "metadata": {"battery_pct": 100}
        })
        current_time += timedelta(seconds=2)

        # Morning check: WhatsApp for 4 minutes
        events.append({"timestamp": current_time.isoformat(), "event_type": "SCREEN_ON"})
        events.append({"timestamp": (current_time + timedelta(seconds=1)).isoformat(), "event_type": "UNLOCK"})
        current_time += timedelta(seconds=2)
        events.append({"timestamp": current_time.isoformat(), "event_type": "APP_FOREGROUND", "package_name": "com.whatsapp"})
        current_time += timedelta(minutes=4)
        events.append({"timestamp": current_time.isoformat(), "event_type": "SCREEN_OFF"})

        # Morning Commute / Prep: Music or Podcast (Deep focus / single app)
        current_time = day_start.replace(hour=8, minute=15)
        events.append({"timestamp": current_time.isoformat(), "event_type": "SCREEN_ON"})
        events.append({"timestamp": (current_time + timedelta(seconds=1)).isoformat(), "event_type": "UNLOCK"})
        events.append({"timestamp": (current_time + timedelta(seconds=2)).isoformat(), "event_type": "APP_FOREGROUND", "package_name": "com.spotify.music"})
        current_time += timedelta(minutes=25)
        events.append({"timestamp": current_time.isoformat(), "event_type": "SCREEN_OFF"})

        # 09:00 - 12:00: Work block.
        # Interspersed with 3-5 "Phantom Checks" (reflexive 5-10s unlocks)
        for _ in range(4):
            current_time = day_start.replace(hour=random.randint(9, 11), minute=random.randint(5, 55))
            events.append({"timestamp": current_time.isoformat(), "event_type": "SCREEN_ON"})
            events.append({"timestamp": (current_time + timedelta(seconds=1)).isoformat(), "event_type": "UNLOCK"})
            # Look at home screen, maybe tap 1 app for 4 seconds then lock
            current_time += timedelta(seconds=7)
            events.append({"timestamp": current_time.isoformat(), "event_type": "SCREEN_OFF"})

        # 12:30 - Lunch break: Casual leisure (YouTube / Reddit for 20 mins)
        current_time = day_start.replace(hour=12, minute=30)
        events.append({"timestamp": current_time.isoformat(), "event_type": "SCREEN_ON"})
        events.append({"timestamp": (current_time + timedelta(seconds=1)).isoformat(), "event_type": "UNLOCK"})
        events.append({"timestamp": (current_time + timedelta(seconds=2)).isoformat(), "event_type": "APP_FOREGROUND", "package_name": "com.google.android.youtube"})
        current_time += timedelta(minutes=18)
        events.append({"timestamp": current_time.isoformat(), "event_type": "SCREEN_OFF"})

        # 15:30 - The Afternoon Slump (App Churn: restless switching between 4 apps in 2 minutes)
        current_time = day_start.replace(hour=15, minute=35)
        events.append({"timestamp": current_time.isoformat(), "event_type": "SCREEN_ON"})
        events.append({"timestamp": (current_time + timedelta(seconds=1)).isoformat(), "event_type": "UNLOCK"})
        current_time += timedelta(seconds=2)
        events.append({"timestamp": current_time.isoformat(), "event_type": "APP_FOREGROUND", "package_name": "com.google.android.gm"})
        current_time += timedelta(seconds=22)
        events.append({"timestamp": current_time.isoformat(), "event_type": "APP_FOREGROUND", "package_name": "com.slack"})
        current_time += timedelta(seconds=35)
        events.append({"timestamp": current_time.isoformat(), "event_type": "APP_FOREGROUND", "package_name": "com.twitter.android"})
        current_time += timedelta(seconds=40)
        events.append({"timestamp": current_time.isoformat(), "event_type": "APP_FOREGROUND", "package_name": "com.reddit.frontpage"})
        current_time += timedelta(seconds=45)
        events.append({"timestamp": current_time.isoformat(), "event_type": "SCREEN_OFF"})

        # 18:00 - Notification Trap (Reddit notification arrives -> instant unlock -> 25 min doomscroll)
        current_time = day_start.replace(hour=18, minute=10)
        events.append({
            "timestamp": current_time.isoformat(),
            "event_type": "NOTIFICATION_POSTED",
            "package_name": "com.reddit.frontpage"
        })
        current_time += timedelta(seconds=4) # 4 second prompt-to-action latency!
        events.append({"timestamp": current_time.isoformat(), "event_type": "SCREEN_ON"})
        events.append({"timestamp": (current_time + timedelta(seconds=1)).isoformat(), "event_type": "UNLOCK"})
        current_time += timedelta(seconds=2)
        events.append({"timestamp": current_time.isoformat(), "event_type": "APP_FOREGROUND", "package_name": "com.reddit.frontpage"})
        current_time += timedelta(minutes=24) # Hooked!
        events.append({"timestamp": current_time.isoformat(), "event_type": "SCREEN_OFF"})

        # 22:30 - Bedtime & Charging connected
        current_time = day_start.replace(hour=22, minute=30)
        events.append({
            "timestamp": current_time.isoformat(),
            "event_type": "POWER_CONNECTED",
            "metadata": {"battery_pct": 28}
        })

        # On days 2, 4, 6: Bedtime Scrolling Trap in bed while charging
        if day in (1, 3, 5):
            current_time += timedelta(minutes=5)
            events.append({"timestamp": current_time.isoformat(), "event_type": "SCREEN_ON"})
            events.append({"timestamp": (current_time + timedelta(seconds=1)).isoformat(), "event_type": "UNLOCK"})
            current_time += timedelta(seconds=2)
            events.append({"timestamp": current_time.isoformat(), "event_type": "APP_FOREGROUND", "package_name": "com.instagram.android"})
            current_time += timedelta(minutes=42) # Delayed sleep by 42 minutes!
            events.append({"timestamp": current_time.isoformat(), "event_type": "SCREEN_OFF"})
        else:
            # Good sleep hygiene night: screen off shortly after plug-in
            current_time += timedelta(minutes=3)
            events.append({"timestamp": current_time.isoformat(), "event_type": "SCREEN_ON"})
            events.append({"timestamp": (current_time + timedelta(seconds=1)).isoformat(), "event_type": "UNLOCK"})
            events.append({"timestamp": (current_time + timedelta(seconds=2)).isoformat(), "event_type": "APP_FOREGROUND", "package_name": "com.amazon.kindle"})
            current_time += timedelta(minutes=12)
            events.append({"timestamp": current_time.isoformat(), "event_type": "SCREEN_OFF"})

    # Sort all events chronologically
    events.sort(key=lambda e: e["timestamp"])

    with open(output_path, "w", encoding="utf-8") as f:
        for ev in events:
            f.write(json.dumps(ev) + "\n")

    print(f"Generated {len(events)} telemetry events across {days} days to {output_path}")

if __name__ == "__main__":
    generate_telemetry_stream("sample_telemetry.jsonl", days=7)
