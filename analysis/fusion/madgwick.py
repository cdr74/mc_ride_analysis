"""Madgwick IMU (gyro+accel) orientation filter — plain-float implementation.

Offline reference port (DESIGN.md §10). No magnetometer term: mag is unusable near the
engine and yaw is not needed for roll/pitch. Pure Python floats on purpose — ~10× faster
than per-sample numpy scalars for the sequential update loop.

Convention: quaternion q = (w, x, y, z) rotates the sensor frame into the world frame
(ENU, z up). Gravity direction in the sensor frame is derivable from q alone.
"""

from __future__ import annotations

from math import sqrt


class Madgwick:
    def __init__(self, beta: float = 0.05):
        self.beta = beta
        self.w, self.x, self.y, self.z = 1.0, 0.0, 0.0, 0.0

    def init_from_accel(self, ax: float, ay: float, az: float) -> None:
        """Set the initial attitude from a (quasi-)static accel sample."""
        # find q that maps sensor 'up' (accel direction) to world +z
        n = sqrt(ax * ax + ay * ay + az * az)
        ux, uy, uz = ax / n, ay / n, az / n
        # axis = u × z_world_in_sensor? Simpler: quaternion from shortest arc u -> (0,0,1)
        cx, cy, cz = uy, -ux, 0.0          # u × (0,0,1)
        d = uz                              # u · (0,0,1)
        s = sqrt((1.0 + d) * 2.0) if d > -0.9999 else 1e-6
        self.w, self.x, self.y, self.z = s / 2.0, cx / s, cy / s, cz / s
        self._normalize()

    def _normalize(self) -> None:
        n = sqrt(self.w**2 + self.x**2 + self.y**2 + self.z**2)
        self.w /= n; self.x /= n; self.y /= n; self.z /= n

    def update(self, gx: float, gy: float, gz: float,
               ax: float, ay: float, az: float, dt: float) -> None:
        q0, q1, q2, q3 = self.w, self.x, self.y, self.z

        # quaternion rate from gyro
        qDot0 = 0.5 * (-q1 * gx - q2 * gy - q3 * gz)
        qDot1 = 0.5 * (q0 * gx + q2 * gz - q3 * gy)
        qDot2 = 0.5 * (q0 * gy - q1 * gz + q3 * gx)
        qDot3 = 0.5 * (q0 * gz + q1 * gy - q2 * gx)

        n = sqrt(ax * ax + ay * ay + az * az)
        if n > 1e-9:
            ax /= n; ay /= n; az /= n
            # gradient-descent corrective step (objective: sensor gravity vs accel)
            _2q0, _2q1, _2q2, _2q3 = 2 * q0, 2 * q1, 2 * q2, 2 * q3
            _4q0, _4q1, _4q2 = 4 * q0, 4 * q1, 4 * q2
            _8q1, _8q2 = 8 * q1, 8 * q2
            q0q0, q1q1, q2q2, q3q3 = q0 * q0, q1 * q1, q2 * q2, q3 * q3

            s0 = _4q0 * q2q2 + _2q2 * ax + _4q0 * q1q1 - _2q1 * ay
            s1 = (_4q1 * q3q3 - _2q3 * ax + 4 * q0q0 * q1 - _2q0 * ay
                  - _4q1 + _8q1 * q1q1 + _8q1 * q2q2 + _4q1 * az)
            s2 = (4 * q0q0 * q2 + _2q0 * ax + _4q2 * q3q3 - _2q3 * ay
                  - _4q2 + _8q2 * q1q1 + _8q2 * q2q2 + _4q2 * az)
            s3 = 4 * q1q1 * q3 - _2q1 * ax + 4 * q2q2 * q3 - _2q2 * ay
            sn = sqrt(s0 * s0 + s1 * s1 + s2 * s2 + s3 * s3)
            if sn > 1e-12:
                b = self.beta / sn
                qDot0 -= b * s0; qDot1 -= b * s1; qDot2 -= b * s2; qDot3 -= b * s3

        self.w = q0 + qDot0 * dt
        self.x = q1 + qDot1 * dt
        self.y = q2 + qDot2 * dt
        self.z = q3 + qDot3 * dt
        self._normalize()

    def down_in_sensor(self) -> tuple[float, float, float]:
        """World 'down' (0,0,-1) expressed in the sensor frame."""
        w, x, y, z = self.w, self.x, self.y, self.z
        # R_world_from_sensor^T @ (0,0,-1) = third row of R, negated
        return (-(2 * (x * z - w * y)),
                -(2 * (y * z + w * x)),
                -(1 - 2 * (x * x + y * y)))
