package org.radarcns.phoneSensors;

import android.content.BroadcastReceiver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.Cursor;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.BatteryManager;
import android.os.Bundle;
import android.provider.CallLog;
import android.provider.Telephony;
import android.support.annotation.NonNull;
import android.telephony.TelephonyManager;

import org.apache.commons.codec.binary.Hex;
import org.apache.commons.codec.digest.DigestUtils;

import org.radarcns.android.DeviceManager;
import org.radarcns.android.DeviceStatusListener;
import org.radarcns.android.MeasurementTable;
import org.radarcns.android.TableDataHandler;
import org.radarcns.kafka.AvroTopic;
import org.radarcns.key.MeasurementKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Iterator;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Manages Phone sensors */
class PhoneSensorsDeviceManager implements DeviceManager, SensorEventListener {
    private final static Logger logger = LoggerFactory.getLogger(PhoneSensorsDeviceManager.class);

    private final TableDataHandler dataHandler;
    private final Context context;

    private final DeviceStatusListener phoneService;

    private Sensor accelerometer;
    private Sensor lightSensor;
    private Intent batteryStatus;

    private final MeasurementTable<PhoneSensorAcceleration> accelerationTable;
    private final MeasurementTable<PhoneSensorLight> lightTable;
    private final AvroTopic<MeasurementKey, PhoneSensorBatteryLevel> batteryTopic;

    private final PhoneSensorsDeviceStatus deviceStatus;

    private String deviceName;
    private boolean isRegistered = false;
    private SensorManager sensorManager;
    private final Runnable runnableReadCallLog;
    private final ScheduledExecutorService executor;
    private final long CALL_LOG_INTERVAL_MS = 60 * 60 * 24 * 1000; //60*60*1000; // an hour in milliseconds

    public PhoneSensorsDeviceManager(Context contextIn, DeviceStatusListener phoneService, String groupId, String sourceId, TableDataHandler dataHandler, PhoneSensorsTopics topics) {
        this.dataHandler = dataHandler;
        this.accelerationTable = dataHandler.getCache(topics.getAccelerationTopic());
        this.lightTable = dataHandler.getCache(topics.getLightTopic());
        this.batteryTopic = topics.getBatteryLevelTopic();

        this.phoneService = phoneService;

        this.context = contextIn;
        sensorManager = null;

        this.deviceStatus = new PhoneSensorsDeviceStatus();
        this.deviceStatus.getId().setUserId(groupId);
        this.deviceStatus.getId().setSourceId(sourceId);

        this.deviceName = android.os.Build.MODEL;
        updateStatus(DeviceStatusListener.Status.READY);

        // Call log
        executor = Executors.newSingleThreadScheduledExecutor();
        runnableReadCallLog = new Runnable() {
            @Override
            public void run() {
                Cursor c = context.getContentResolver().query(CallLog.Calls.CONTENT_URI, null, null, null, null);
                if (!c.moveToLast()) {
                    c.close();
                    return;
                }
                long now = System.currentTimeMillis();
                long timeStamp = c.getLong(c.getColumnIndex(CallLog.Calls.DATE));
                while ((now - timeStamp) <= CALL_LOG_INTERVAL_MS) {
                    String num = c.getString(c.getColumnIndex(CallLog.Calls.NUMBER));// for  number
                    String salt = deviceStatus.getId().getSourceId();
                    String shaNum = new String(Hex.encodeHex(DigestUtils.sha256(num + salt)));
                    String duration = c.getString(c.getColumnIndex(CallLog.Calls.DURATION));// for duration
                    int type = c.getInt(c.getColumnIndex(CallLog.Calls.TYPE));// for call type, Incoming or out going.
                    String date = c.getString(c.getColumnIndex(CallLog.Calls.DATE));
                    logger.info(String.format("%s, %s, %s, %s, %d, %d, %s, %d", num, salt, shaNum, duration, type, timeStamp, date, now));

                    if (!c.moveToPrevious()) {
                        c.close();
                        return;
                    }
                    timeStamp = c.getLong(c.getColumnIndex(CallLog.Calls.DATE));
                }
                c.close();
            }
        };
    }

    @Override
    public void start(@NonNull final Set<String> acceptableIds) {
        sensorManager = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);

        // Accelerometer
        if (sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) != null) {
            // success! we have an accelerometer
            accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
            sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_NORMAL);
        } else {
            logger.warn("Phone Accelerometer not found");
        }

        // Light
        if (sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT) != null) {
            lightSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT);
            sensorManager.registerListener(this, lightSensor, SensorManager.SENSOR_DELAY_NORMAL);
        } else {
            logger.warn("Phone Light sensor not found");
        }

        // Battery
        IntentFilter intentBattery = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
        batteryStatus = context.registerReceiver(null, intentBattery);

        // Calls, in and outgoing
        executor.scheduleAtFixedRate(runnableReadCallLog, 0, CALL_LOG_INTERVAL_MS, TimeUnit.MILLISECONDS);

        isRegistered = true;
        updateStatus(DeviceStatusListener.Status.CONNECTED);
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if ( event.sensor.getType() == Sensor.TYPE_ACCELEROMETER ) {
            processAcceleration(event);
        } else if ( event.sensor.getType() == Sensor.TYPE_LIGHT ) {
            processLight(event);
        } else {
            logger.info("Phone registered other sensor change: '{}'", event.sensor.getType());
        }

        // Get new battery status
        processBattery();
    }

    public void processAcceleration(SensorEvent event) {
        // x,y,z are in m/s2
        Float x = event.values[0] / 9.81f;
        Float y = event.values[1] / 9.81f;
        Float z = event.values[2] / 9.81f;
        deviceStatus.setAcceleration(x, y, z);

        float[] latestAcceleration = deviceStatus.getAcceleration();
        PhoneSensorAcceleration value = new PhoneSensorAcceleration(
                (double) event.timestamp, System.currentTimeMillis() / 1000d,
                latestAcceleration[0], latestAcceleration[1], latestAcceleration[2]);

        dataHandler.addMeasurement(accelerationTable, deviceStatus.getId(), value);
    }

    public void processLight(SensorEvent event) {
        Float lightValue = event.values[0];
        deviceStatus.setLight(lightValue);

        PhoneSensorLight value = new PhoneSensorLight(
                (double) event.timestamp, System.currentTimeMillis() / 1000d,
                lightValue);

        dataHandler.addMeasurement(lightTable, deviceStatus.getId(), value);
    }

    public void processBattery() {
        int level = batteryStatus.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = batteryStatus.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
        float batteryPct = level / (float)scale;

        deviceStatus.setBatteryLevel(batteryPct);

        double timestamp = System.currentTimeMillis() / 1000d;
        PhoneSensorBatteryLevel value = new PhoneSensorBatteryLevel(timestamp, timestamp, batteryPct);
        dataHandler.trySend(batteryTopic, 0L, deviceStatus.getId(), value);
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {

    }

    @Override
    public boolean isClosed() {
        return !isRegistered;
    }


    @Override
    public void close() {
        sensorManager.unregisterListener(this);
        isRegistered = false;
        updateStatus(DeviceStatusListener.Status.DISCONNECTED);
    }

    @Override
    public String getName() {
        return deviceName;
    }

    @Override
    public PhoneSensorsDeviceStatus getState() {
        return deviceStatus;
    }

    private synchronized void updateStatus(DeviceStatusListener.Status status) {
        this.deviceStatus.setStatus(status);
        this.phoneService.deviceStatusUpdated(this, status);
    }

    @Override
    public boolean equals(Object other) {
        return other == this
                || other != null && getClass().equals(other.getClass())
                && deviceStatus.getId().getSourceId() != null
                && deviceStatus.getId().equals(((PhoneSensorsDeviceManager) other).deviceStatus.getId());
    }

}
