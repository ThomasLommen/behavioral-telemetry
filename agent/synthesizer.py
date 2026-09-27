from __future__ import annotations

import json
from collections import defaultdict
from datetime import datetime, time, timedelta
from typing import Dict, List, Optional, Tuple

from models import (
    AppSegment,
    BehavioralSession,
    DailyCognitiveMetrics,
    EventType,
    SessionType,
    TelemetryEvent,
)


class BehavioralSynthesizer:
    """
    Transforms raw chronological Android telemetry streams into
    psychologically annotated behavioral sessions and daily cognitive metrics.
    """

    NOTIFICATION_ATTRIBUTION_WINDOW_SEC = 15.0

    def __init__(self, raw_events: List[TelemetryEvent]):
        # Ensure chronological ordering
        self.events = sorted(raw_events, key=lambda e: e.timestamp)

    @classmethod
    def from_jsonl(cls, filepath: str) -> BehavioralSynthesizer:
        events = []
        with open(filepath, "r", encoding="utf-8") as f:
            for line in f:
                line = line.strip()
                if not line:
                    continue
                data = json.loads(line)
                events.append(
                    TelemetryEvent(
                        timestamp=datetime.fromisoformat(data["timestamp"]),
                        event_type=EventType(data["event_type"]),
                        package_name=data.get("package_name"),
                        duration_ms=data.get("duration_ms", 0),
                        trigger_source=data.get("trigger_source"),
                        metadata=data.get("metadata", {}),
                    )
                )
        return cls(events)

    def synthesize_sessions(self) -> List[BehavioralSession]:
        sessions: List[BehavioralSession] = []
        
        current_unlock: Optional[datetime] = None
        current_trigger: str = "PROACTIVE_USER"
        current_latency: Optional[float] = None
        recent_notifications: List[Tuple[datetime, str]] = []
        current_apps: List[AppSegment] = []
        last_app_start: Optional[datetime] = None
        last_app_pkg: Optional[str] = None
        is_charging: bool = False

        for event in self.events:
            # Track charging state
            if event.event_type == EventType.POWER_CONNECTED:
                is_charging = True
            elif event.event_type == EventType.POWER_DISCONNECTED:
                is_charging = False

            # Keep sliding window of incoming notifications
            elif event.event_type == EventType.NOTIFICATION_POSTED:
                pkg = event.package_name or "unknown"
                recent_notifications.append((event.timestamp, pkg))
                # Prune older than 60s
                cutoff = event.timestamp - timedelta(seconds=60)
                recent_notifications = [n for n in recent_notifications if n[0] >= cutoff]

            elif event.event_type == EventType.UNLOCK:
                current_unlock = event.timestamp
                current_apps = []
                last_app_start = event.timestamp
                last_app_pkg = None

                # Check if unlock was reactive to recent notification
                reactive = False
                for notif_time, notif_pkg in reversed(recent_notifications):
                    diff = (event.timestamp - notif_time).total_seconds()
                    if 0 <= diff <= self.NOTIFICATION_ATTRIBUTION_WINDOW_SEC:
                        current_trigger = "REACTIVE_NOTIFICATION"
                        current_latency = diff
                        reactive = True
                        break

                if not reactive:
                    current_trigger = "PROACTIVE_USER"
                    current_latency = None

            elif event.event_type == EventType.APP_FOREGROUND and current_unlock:
                now = event.timestamp
                if last_app_pkg and last_app_start:
                    dur = (now - last_app_start).total_seconds()
                    if dur > 0.5:
                        current_apps.append(AppSegment(package_name=last_app_pkg, duration_seconds=dur))
                last_app_pkg = event.package_name
                last_app_start = now

            elif event.event_type in (EventType.SCREEN_OFF,) and current_unlock:
                end_time = event.timestamp
                total_duration = max(0.0, (end_time - current_unlock).total_seconds())

                # Finalize last app
                if last_app_pkg and last_app_start:
                    dur = (end_time - last_app_start).total_seconds()
                    if dur > 0.5:
                        current_apps.append(AppSegment(package_name=last_app_pkg, duration_seconds=dur))

                # Determine session classification
                session_type, tags = self._classify_session(
                    start_time=current_unlock,
                    duration_sec=total_duration,
                    trigger=current_trigger,
                    apps=current_apps,
                    is_charging=is_charging,
                )

                switches = max(0, len(current_apps) - 1)
                duration_mins = max(0.1, total_duration / 60.0)
                velocity = switches / duration_mins

                sessions.append(
                    BehavioralSession(
                        session_id=f"sess_{current_unlock.strftime('%Y%m%d_%H%M%S')}",
                        start_time=current_unlock,
                        end_time=end_time,
                        total_duration_seconds=round(total_duration, 1),
                        session_type=session_type,
                        trigger=current_trigger,
                        prompt_to_action_latency_sec=round(current_latency, 2) if current_latency else None,
                        app_segments=current_apps,
                        unique_apps_count=len(set(a.package_name for a in current_apps)),
                        switching_velocity=round(velocity, 2),
                        tags=tags,
                    )
                )

                # Reset
                current_unlock = None
                last_app_pkg = None
                last_app_start = None

        return sessions

    def _classify_session(
        self,
        start_time: datetime,
        duration_sec: float,
        trigger: str,
        apps: List[AppSegment],
        is_charging: bool,
    ) -> Tuple[SessionType, List[str]]:
        tags = []
        hour = start_time.hour

        # Check for bedtime scrolling
        if is_charging and (hour >= 21 or hour < 5) and duration_sec > 120:
            tags.append("bedtime_usage")
            return SessionType.BEDTIME_SCROLL, tags

        # Check for Phantom Check (< 15 seconds)
        if duration_sec < 15:
            tags.append("reflexive_check")
            return SessionType.PHANTOM_CHECK, tags

        unique_apps = len(set(a.package_name for a in apps))

        # Check for App Churn (high restlessness)
        if len(apps) >= 3 and (duration_sec / max(1, len(apps))) < 45.0:
            tags.append("restless_switching")
            tags.append("attention_fragmentation")
            return SessionType.APP_CHURN, tags

        # Check for Notification Trap (triggered reactively, stayed > 5 mins)
        if trigger == "REACTIVE_NOTIFICATION" and duration_sec > 300:
            tags.append("hooked_by_prompt")
            return SessionType.NOTIFICATION_TRAP, tags

        # Check for Deep Focus
        if duration_sec >= 900 and unique_apps <= 2:
            tags.append("sustained_attention")
            return SessionType.DEEP_FOCUS, tags

        tags.append("casual_use")
        return SessionType.CASUAL_LEISURE, tags

    def compute_daily_metrics(self, sessions: List[BehavioralSession]) -> List[DailyCognitiveMetrics]:
        by_day: Dict[str, List[BehavioralSession]] = defaultdict(list)
        for s in sessions:
            day_key = s.start_time.strftime("%Y-%m-%d")
            by_day[day_key].append(s)

        daily_summaries = []
        for day, day_sessions in sorted(by_day.items()):
            total_time = sum(s.total_duration_seconds for s in day_sessions) / 60.0
            unlocks = len(day_sessions)
            phantoms = sum(1 for s in day_sessions if s.session_type == SessionType.PHANTOM_CHECK)
            reactive = sum(1 for s in day_sessions if s.trigger == "REACTIVE_NOTIFICATION")
            proactive = unlocks - reactive
            churns = sum(1 for s in day_sessions if s.session_type == SessionType.APP_CHURN)
            deep_focus = sum(1 for s in day_sessions if s.session_type == SessionType.DEEP_FOCUS)

            reactive_ratio = round(reactive / max(1, unlocks), 3)

            # Attentional Fragmentation Index (0 to 100)
            afi = (churns * 12.0) + (phantoms * 1.5) + (reactive_ratio * 40.0)
            afi = round(min(100.0, max(0.0, afi)), 1)

            daily_summaries.append(
                DailyCognitiveMetrics(
                    date=day,
                    total_screen_time_minutes=round(total_time, 1),
                    total_unlocks=unlocks,
                    phantom_checks_count=phantoms,
                    reactive_unlocks_count=reactive,
                    proactive_unlocks_count=proactive,
                    reactive_ratio=reactive_ratio,
                    attentional_fragmentation_index=afi,
                    app_churn_episodes_count=churns,
                    deep_focus_sessions_count=deep_focus,
                )
            )

        return daily_summaries
