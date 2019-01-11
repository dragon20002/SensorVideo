package kr.ac.hansung.sensorvideo;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;

public class SensorValuesManager implements SensorEventListener {
    private static SensorValuesManager instance;
    private final SensorManager sensorManager;
    private Sensor accelSensor, magSensor, gyroSensor;
    private boolean isSensorOn;
    private float[] acc, gyro, mag;

    public static SensorValuesManager getInstance(Context context) {
        if (instance == null)
            instance = new SensorValuesManager(context);
        return instance;
    }

    private SensorValuesManager(Context context) {
        sensorManager = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
        if (sensorManager != null) {
            accelSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
            gyroSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
            magSensor = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD);
        }
        isSensorOn = false;
        acc = new float[3];
        gyro = new float[3];
        mag = new float[3];
        for (int i = 0; i < 3; i++) { acc[i] = 0f;gyro[i] = 0f;mag[i] = 0f; }
    }

    public void registerListener() {
        if (isSensorOn) return;
        int rate = SensorManager.SENSOR_DELAY_FASTEST;
        if (accelSensor != null)
            isSensorOn = sensorManager.registerListener(this, accelSensor, rate);
        if (magSensor != null)
            isSensorOn |= sensorManager.registerListener(this, magSensor, rate);
        if (gyroSensor != null)
            isSensorOn |= sensorManager.registerListener(this, gyroSensor, rate);
    }

    public void unregisterListener() {
        if (!isSensorOn) return;
        if (sensorManager != null)
            sensorManager.unregisterListener(this);
        isSensorOn = false;
    }

    public float[] getAcc() {
        return acc.clone();
    }

    public float[] getMag() {
        return mag.clone();
    }

    public float[] getGyro() {
        return gyro.clone();
    }

    @Override
    public void onSensorChanged(SensorEvent sensorEvent) {
        switch (sensorEvent.sensor.getType()) {
            case Sensor.TYPE_ACCELEROMETER:
                acc = sensorEvent.values.clone();
                break;
            case Sensor.TYPE_GYROSCOPE:
                gyro = sensorEvent.values.clone();
                break;
            case Sensor.TYPE_MAGNETIC_FIELD:
                mag = sensorEvent.values.clone();
                break;
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int i) {
    }
}
