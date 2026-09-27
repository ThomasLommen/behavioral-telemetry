package com.behavioral.telemetry.sensors

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper

object AmbientLightHelper {

    /**
     * Captures a single 1-shot ambient lux reading when screen turns on.
     * Automatically unregisters immediately after the first reading or after an 800ms safety timeout.
     * Battery cost: virtually zero.
     */
    fun captureOneShotLux(context: Context, callback: (Float?) -> Unit) {
        val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        if (sensorManager == null) {
            callback(null)
            return
        }

        val lightSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT)
        if (lightSensor == null) {
            callback(null)
            return
        }

        val handler = Handler(Looper.getMainLooper())
        var hasReported = false

        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent?) {
                if (hasReported) return
                hasReported = true
                try {
                    sensorManager.unregisterListener(this)
                } catch (ignored: Exception) {}

                val lux = event?.values?.getOrNull(0)
                callback(lux)
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }

        // Safety timeout to avoid leaving sensor active if phone remains in pocket or sensor hangs
        handler.postDelayed({
            if (!hasReported) {
                hasReported = true
                try {
                    sensorManager.unregisterListener(listener)
                } catch (ignored: Exception) {}
                callback(null)
            }
        }, 800)

        try {
            sensorManager.registerListener(listener, lightSensor, SensorManager.SENSOR_DELAY_NORMAL, handler)
        } catch (e: Exception) {
            callback(null)
        }
    }
}
