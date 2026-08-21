package com.miracles.miko;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.media.AudioManager;
import android.media.session.MediaSession;
import android.os.Build;
import android.os.IBinder;
import android.view.KeyEvent;

import androidx.core.app.NotificationCompat;

/**
 * 前台服务：通知栏常驻保活，后台持续监听加速度传感器，倾斜切换音乐。
 *
 * 采用 mediaPlayback 前台服务类型（Android 14+ 强制要求前台服务必须声明类型），
 * 并创建 MediaSession 以满足 Android 15+ 对 mediaPlayback 类型需有活跃媒体会话的要求。
 */
public class MusicControlService extends Service implements SensorEventListener {

    private static final String CHANNEL_ID = "music_control_channel";
    private static final String CHANNEL_NAME = "音乐倾斜控制服务";
    private static final String CHANNEL_DESC = "左右倾斜切换音乐的前台服务（通知栏常驻）";
    private static final int NOTIFICATION_ID = 1001;

    private static final int FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK_VALUE =
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK;

    // 可调参数：由 MainActivity 实时更新（静态字段，方便跨组件同步）
    public static float threshold = 3.0f;
    public static boolean isReversed = false;
    public static long debounceTimeMillis = 1000;

    // 服务是否在运行（供 Activity 查询状态）
    public static volatile boolean isRunning = false;

    private SensorManager sensorManager;
    private Sensor accelerometer;
    private AudioManager audioManager;
    private MediaSession mediaSession;

    private long lastTriggerTime = 0;

    // 滤波相关
    private float smoothedX = 0f;
    private boolean isFirstSensorRead = true;
    private static final float FILTER_ALPHA = 0.15f;

    @Override
    public void onCreate() {
        super.onCreate();
        isRunning = true;
        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);

        createNotificationChannel();
        startForegroundCompat();

        // 启动传感器监听
        sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        if (sensorManager == null) {
            stopSelf();
            return;
        }
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        if (accelerometer == null) {
            stopSelf();
            return;
        }
        sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_GAME);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // START_STICKY：即使被系统回收，也会尽量重建服务（配合前台服务实现保活）
        return START_STICKY;
    }

    /**
     * 创建通知渠道（Android 8.0+ 必须）
     */
    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW);
            channel.setDescription(CHANNEL_DESC);
            channel.setShowBadge(false);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) {
                nm.createNotificationChannel(channel);
            }
        }
    }

    /**
     * 启动为前台服务，通知栏常驻。mediaPlayback 类型需有活跃的 MediaSession。
     */
    private void startForegroundCompat() {
        // 激活媒体会话（Android 15+ mediaPlayback 类型的前台服务必须有活跃会话）
        createAndActivateMediaSession();

        PendingIntent contentIntent = PendingIntent.getActivity(
                this, 0,
                new Intent(this, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("音乐倾斜控制服务运行中")
                .setContentText("左右倾斜手机切换歌曲")
                .setContentIntent(contentIntent)
                .setOngoing(true) // 常驻通知，不可被用户滑动清除
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .build();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Android 13+ 前台服务必须指定类型，且类型需在 manifest 中声明
            startForeground(NOTIFICATION_ID, notification,
                    FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK_VALUE);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    /**
     * 创建并激活 MediaSession，满足 Android 15+ 对 mediaPlayback 前台服务的要求。
     */
    private void createAndActivateMediaSession() {
        if (mediaSession == null) {
            mediaSession = new MediaSession(this, "MusicControlService");
        }
        mediaSession.setActive(true);
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() != Sensor.TYPE_ACCELEROMETER) {
            return;
        }
        float rawX = event.values[0];

        if (isFirstSensorRead) {
            smoothedX = rawX;
            isFirstSensorRead = false;
        } else {
            smoothedX = FILTER_ALPHA * rawX + (1 - FILTER_ALPHA) * smoothedX;
        }

        long currentTime = System.currentTimeMillis();
        if (currentTime - lastTriggerTime < debounceTimeMillis) {
            return;
        }

        if (isReversed) {
            if (smoothedX > threshold) {
                lastTriggerTime = currentTime;
                sendMediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS);
            } else if (smoothedX < -threshold) {
                lastTriggerTime = currentTime;
                sendMediaKey(KeyEvent.KEYCODE_MEDIA_NEXT);
            }
        } else {
            if (smoothedX > threshold) {
                lastTriggerTime = currentTime;
                sendMediaKey(KeyEvent.KEYCODE_MEDIA_NEXT);
            } else if (smoothedX < -threshold) {
                lastTriggerTime = currentTime;
                sendMediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS);
            }
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
    }

    private void sendMediaKey(int keyCode) {
        if (audioManager == null) {
            return;
        }
        audioManager.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, keyCode));
        audioManager.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, keyCode));
    }

    @Override
    public void onDestroy() {
        isRunning = false;
        if (sensorManager != null) {
            sensorManager.unregisterListener(this);
        }
        if (mediaSession != null) {
            mediaSession.setActive(false);
            mediaSession.release();
            mediaSession = null;
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
