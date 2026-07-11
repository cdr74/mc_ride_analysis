package dev.cdr74.ridelogger

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Hands-free guided calibration (ADR 0003). The rider presses one button while
 * stationary; every later phase transition is detected from GPS speed, so no input
 * is needed while the bike is moving. Emits the same calib_start/calib_end markers
 * as the old manual stepper — exact segment extraction still happens offline.
 *
 * This is threshold logic on GPS speed for UI guidance only: logged data is never
 * touched and no sensor fusion happens on-device.
 *
 * Single-threaded: [onFix], [start] and [cancel] are all called on the main looper.
 */
class CalibrationGuide(private val insertMarker: (tNs: Long, kind: String, note: String) -> Unit) {

    enum class Phase(val instruction: String) {
        IDLE(""),
        WAIT_STATIC("Stop the bike. Bars dead straight. Hold still."),
        STATIC("Hold still — measuring for 10 s…"),
        WAIT_ACCEL("Static done ✓ — when safe, accelerate briskly in a straight line (moderate is enough)."),
        ACCEL("Keep accelerating, dead straight…"),
        WAIT_BRAKE("Acceleration captured ✓ — when safe, brake firmly in a straight line, release while still rolling."),
        BRAKE("Keep braking, dead straight — release while still rolling…"),
        DONE("Calibration complete ✓"),
    }

    private val _phase = MutableStateFlow(Phase.IDLE)
    val phase: StateFlow<Phase> = _phase.asStateFlow()

    /** True while a calibration run is in progress (start pressed, not yet done). */
    val active: Boolean
        get() = _phase.value != Phase.IDLE && _phase.value != Phase.DONE

    private var stillFixes = 0
    private var flatFixes = 0
    private var easeFixes = 0
    private var phaseStartNs = 0L
    private var prevSpeed = 0.0
    private var prevTNs = 0L

    fun start() {
        resetCounters()
        _phase.value = Phase.WAIT_STATIC
    }

    /** Aborts the run; closes any open segment so calib markers stay balanced. */
    fun cancel() {
        val now = prevTNs
        when (_phase.value) {
            Phase.STATIC -> insertMarker(now, "calib_end", NOTE_STATIC)
            Phase.ACCEL -> insertMarker(now, "calib_end", NOTE_ACCEL)
            Phase.BRAKE -> insertMarker(now, "calib_end", NOTE_BRAKE)
            else -> Unit
        }
        resetCounters()
        _phase.value = Phase.IDLE
    }

    private fun resetCounters() {
        stillFixes = 0
        flatFixes = 0
        easeFixes = 0
        prevTNs = 0L
        prevSpeed = 0.0
    }

    /** Feed one GPS fix (~1 Hz). Fixes without speed are ignored. */
    fun onFix(tNs: Long, speedMps: Double?) {
        val speed = speedMps ?: return
        val dtS = if (prevTNs != 0L) (tNs - prevTNs) / 1e9 else 0.0
        val accel = if (dtS > 0) (speed - prevSpeed) / dtS else 0.0
        prevSpeed = speed
        prevTNs = tNs

        when (_phase.value) {
            Phase.WAIT_STATIC -> {
                stillFixes = if (speed < Config.CALIB_STATIC_MAX_MPS) stillFixes + 1 else 0
                if (stillFixes >= Config.CALIB_STATIC_CONFIRM_FIXES) {
                    insertMarker(tNs, "calib_start", NOTE_STATIC)
                    phaseStartNs = tNs
                    _phase.value = Phase.STATIC
                }
            }
            Phase.STATIC -> {
                if (speed >= Config.CALIB_STATIC_MAX_MPS) {
                    // Moved during the hold: close the (short) segment and retry.
                    // Offline solver discards segments below the required duration.
                    insertMarker(tNs, "calib_end", NOTE_STATIC)
                    stillFixes = 0
                    _phase.value = Phase.WAIT_STATIC
                } else if (tNs - phaseStartNs >= Config.CALIB_STATIC_DURATION_NS) {
                    insertMarker(tNs, "calib_end", NOTE_STATIC)
                    _phase.value = Phase.WAIT_ACCEL
                }
            }
            Phase.WAIT_ACCEL -> {
                if (accel > Config.CALIB_ACCEL_START_MPS2 && speed > Config.CALIB_MOVE_MIN_MPS) {
                    insertMarker(tNs - Config.CALIB_LEAD_IN_NS, "calib_start", NOTE_ACCEL)
                    phaseStartNs = tNs
                    flatFixes = 0
                    _phase.value = Phase.ACCEL
                }
            }
            Phase.ACCEL -> {
                val elapsed = tNs - phaseStartNs
                if (speed < Config.CALIB_MOVE_MIN_MPS && elapsed < Config.CALIB_ACCEL_MIN_NS) {
                    // False start (positioning creep, aborted launch): retry.
                    insertMarker(tNs, "calib_end", NOTE_ACCEL)
                    _phase.value = Phase.WAIT_ACCEL
                } else {
                    flatFixes = if (accel < Config.CALIB_ACCEL_PLATEAU_MPS2) flatFixes + 1 else 0
                    if ((flatFixes >= 2 && elapsed >= Config.CALIB_ACCEL_MIN_NS) ||
                        elapsed >= Config.CALIB_ACCEL_MAX_NS
                    ) {
                        insertMarker(tNs, "calib_end", NOTE_ACCEL)
                        _phase.value = Phase.WAIT_BRAKE
                    }
                }
            }
            Phase.WAIT_BRAKE -> {
                if (accel < Config.CALIB_BRAKE_START_MPS2 && speed > Config.CALIB_MOVE_MIN_MPS) {
                    insertMarker(tNs - Config.CALIB_LEAD_IN_NS, "calib_start", NOTE_BRAKE)
                    phaseStartNs = tNs
                    easeFixes = 0
                    _phase.value = Phase.BRAKE
                }
            }
            Phase.BRAKE -> {
                val elapsed = tNs - phaseStartNs
                easeFixes = if (accel > Config.CALIB_BRAKE_EASE_MPS2) easeFixes + 1 else 0
                if (speed < Config.CALIB_STATIC_MAX_MPS ||
                    (easeFixes >= 2 && elapsed >= Config.CALIB_BRAKE_MIN_NS) ||
                    elapsed >= Config.CALIB_BRAKE_MAX_NS
                ) {
                    insertMarker(tNs, "calib_end", NOTE_BRAKE)
                    _phase.value = Phase.DONE
                }
            }
            Phase.IDLE, Phase.DONE -> Unit
        }
    }

    companion object {
        const val NOTE_STATIC = "static_level"
        const val NOTE_ACCEL = "accel"
        const val NOTE_BRAKE = "brake"
    }
}
