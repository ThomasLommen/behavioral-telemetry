"""
Behavioral Telemetry Analyzer & Socratic Agent Runner.
Processes synthesized episodes and generates the Socratic Behavioral Review.
"""

import os
import sys
from datetime import datetime
from typing import List
from dotenv import load_dotenv

if sys.platform == "win32":
    try:
        sys.stdout.reconfigure(encoding="utf-8")
    except Exception:
        pass

load_dotenv()

from models import BehavioralSession, DailyCognitiveMetrics, SessionType
from prompts import EXECUTIVE_DIGEST_SYSTEM_PROMPT, USER_EXECUTIVE_PROMPT_TEMPLATE
from synthesizer import BehavioralSynthesizer


def format_daily_metrics_table(metrics: List[DailyCognitiveMetrics]) -> str:
    lines = [
        "| Date | Total Screen (min) | Unlocks | Phantom Checks (<15s) | Reactive % | AFI (Fragmentation 0-100) | Churn Episodes | Deep Sessions |",
        "| :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- |"
    ]
    for m in metrics:
        lines.append(
            f"| {m.date} | {m.total_screen_time_minutes}m | {m.total_unlocks} | "
            f"{m.phantom_checks_count} | {int(m.reactive_ratio * 100)}% | **{m.attentional_fragmentation_index}** | "
            f"{m.app_churn_episodes_count} | {m.deep_focus_sessions_count} |"
        )
    return "\n".join(lines)


def format_episodes_summary(sessions: List[BehavioralSession]) -> str:
    # Filter for interesting episodes: Churn, Notification Traps, Bedtime Scrolls
    key_episodes = [
        s for s in sessions
        if s.session_type in (SessionType.APP_CHURN, SessionType.NOTIFICATION_TRAP, SessionType.BEDTIME_SCROLL)
    ]

    lines = []
    for s in key_episodes[:15]: # Show top 15 significant episodes
        apps_str = " -> ".join([f"{seg.package_name.split('.')[-1]} ({int(seg.duration_seconds)}s)" for seg in s.app_segments])
        latency_str = f", Prompt Latency: {s.prompt_to_action_latency_sec}s" if s.prompt_to_action_latency_sec else ""
        lines.append(
            f"- **[{s.session_type.value}]** {s.start_time.strftime('%a %H:%M')} ({int(s.total_duration_seconds / 60)} min)\n"
            f"  - Trigger: `{s.trigger}`{latency_str}\n"
            f"  - App Flow: {apps_str or 'Home screen / Settings'}\n"
            f"  - Velocity: {s.switching_velocity} switches/min\n"
        )
    return "\n".join(lines) if lines else "No abnormal fragmentation episodes detected."


def run_socratic_agent(telemetry_file: str):
    print(f"Loading telemetry from {telemetry_file}...")
    synthesizer = BehavioralSynthesizer.from_jsonl(telemetry_file)
    sessions = synthesizer.synthesize_sessions()
    daily_metrics = synthesizer.compute_daily_metrics(sessions)

    print(f"Synthesized {len(sessions)} behavioral sessions across {len(daily_metrics)} days.")

    metrics_table = format_daily_metrics_table(daily_metrics)
    episodes_summary = format_episodes_summary(sessions)

    prompt = USER_EXECUTIVE_PROMPT_TEMPLATE.format(
        daily_metrics_table=metrics_table,
        episodes_summary=episodes_summary
    )

    api_key = os.environ.get("GEMINI_API_KEY")

    if api_key:
        print("\nConnecting to Gemini API (google-genai) with Executive Digest persona...\n")
        try:
            from google import genai
            client = genai.Client()
            response = client.models.generate_content(
                model="gemini-3.8-flash",
                contents=prompt,
                config=dict(
                    system_instruction=EXECUTIVE_DIGEST_SYSTEM_PROMPT,
                    temperature=0.4
                )
            )
            print("=" * 80)
            print("PERSONAL BEHAVIORAL DIGEST")
            print("=" * 80)
            print(response.text)
            print("=" * 80)
            return response.text
        except Exception as e:
            print(f"Gemini API call failed: {e}. Falling back to structured data summary.\n")

    # Fallback to local report if API key is not configured
    print("\n" + "=" * 80)
    print("BEHAVIORAL TELEMETRY SYNTHESIS REPORT (Local Extraction)")
    print("=" * 80)
    print("\n### Daily Cognitive Metrics:\n")
    print(metrics_table)
    print("\n### Notable Subconscious Episodes (Attentional Traps & Churn):\n")
    print(episodes_summary)
    print("\n" + "=" * 80)
    print("[TIP] To generate full qualitative Socratic feedback via LLM, set GEMINI_API_KEY in your environment.")
    print("=" * 80)


if __name__ == "__main__":
    data_path = sys.argv[1] if len(sys.argv) > 1 else "sample_telemetry.jsonl"
    run_socratic_agent(data_path)
