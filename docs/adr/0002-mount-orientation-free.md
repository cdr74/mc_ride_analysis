# ADR 0002 — Mount orientation is unconstrained

Date: 2026-07-11 · Status: accepted

Early doc text implied vertical portrait ("portrait, USB down") mounting. The actual mount
holds the phone top-forward, screen tilted up at roughly the MT-07 TFT display angle, because
vertical mounting is impractical on the bar mount.

This is fine by design: the per-ride calibration (DESIGN.md §7) solves the full
R_phone→bike rotation from the static-level (gravity) and straight-line-acceleration
(forward) segments, which stay well-conditioned at any fixed tilt. A sky-facing tilt also
improves GNSS reception. Requirements that remain: rigid damped mount, phone must not shift
mid-ride, bars dead straight during calibration, and the `mount` meta string must describe
the real setup. Docs and `Config.MOUNT_DESCRIPTION` updated accordingly (v0.1.1).
