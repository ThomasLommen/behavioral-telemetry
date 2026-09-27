from __future__ import annotations

from datetime import datetime
from enum import Enum
from typing import Any, Dict, List, Optional
from pydantic import BaseModel, Field


class EventType(str, Enum):
    SCREEN_ON = "SCREEN_ON"
    SCREEN_OFF = "SCREEN_OFF"
    UNLOCK = "UNLOCK"
    APP_FOREGROUND = "APP_FOREGROUND"
    NOTIFICATION_POSTED = "NOTIFICATION_POSTED"
    NOTIFICATION_DISMISSED = "NOTIFICATION_DISMISSED"
    POWER_CONNECTED = "POWER_CONNECTED"
    POWER_DISCONNECTED = "POWER_DISCONNECTED"


class SessionType(str, Enum):
    DEEP_FOCUS = "DEEP_FOCUS"               # > 15m in single intentional app (reading, utility)
    APP_CHURN = "APP_CHURN"                 # Rapid switching (>3 apps in <3 mins), restlessness
    PHANTOM_CHECK = "PHANTOM_CHECK"         # < 15s unlock, no substantive action
    NOTIFICATION_TRAP = "NOTIFICATION_TRAP" # Unlocked due to ping, stayed far longer than needed
    CASUAL_LEISURE = "CASUAL_LEISURE"       # Moderate single-app session (e.g. video, social)
    BEDTIME_SCROLL = "BEDTIME_SCROLL"       # Usage in bed after charging connected


class TelemetryEvent(BaseModel):
    timestamp: datetime
    event_type: EventType
    package_name: Optional[str] = None
    duration_ms: int = 0
    trigger_source: Optional[str] = None    # "USER_INITIATED", "NOTIFICATION", "EXTERNAL_ALARM"
    metadata: Dict[str, Any] = Field(default_factory=dict)


class AppSegment(BaseModel):
    package_name: str
    duration_seconds: float


class BehavioralSession(BaseModel):
    session_id: str
    start_time: datetime
    end_time: datetime
    total_duration_seconds: float
    session_type: SessionType
    trigger: str                            # "PROACTIVE_USER" or "REACTIVE_NOTIFICATION"
    prompt_to_action_latency_sec: Optional[float] = None
    app_segments: List[AppSegment] = Field(default_factory=list)
    unique_apps_count: int = 0
    switching_velocity: float = 0.0          # switches per minute
    tags: List[str] = Field(default_factory=list)


class DailyCognitiveMetrics(BaseModel):
    date: str
    total_screen_time_minutes: float
    total_unlocks: int
    phantom_checks_count: int
    reactive_unlocks_count: int
    proactive_unlocks_count: int
    reactive_ratio: float                   # reactive / total
    attentional_fragmentation_index: float  # Scale 0 - 100
    bedtime_latency_minutes: Optional[float] = None # Gap between plug-in and final screen off
    morning_screen_latency_minutes: Optional[float] = None # Wake to first unlock
    app_churn_episodes_count: int
    deep_focus_sessions_count: int
