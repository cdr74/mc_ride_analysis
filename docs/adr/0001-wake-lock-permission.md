# ADR 0001 — Add WAKE_LOCK to the manifest

Date: 2026-07-11 · Status: accepted

CLAUDE.md limits requested permissions to `ACCESS_FINE_LOCATION`, `POST_NOTIFICATIONS`,
`FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_LOCATION`, `HIGH_SAMPLING_RATE_SENSORS`.
DESIGN.md §6 mandates a partial wakelock to keep sensor delivery alive with the screen off,
which requires the `WAKE_LOCK` manifest permission.

DESIGN.md wins per the stated precedence rule. `WAKE_LOCK` is a normal (install-time)
permission with no user-facing prompt; it is added to the manifest and this ADR records the
divergence from the CLAUDE.md list.
