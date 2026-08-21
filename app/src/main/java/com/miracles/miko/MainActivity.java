package com.miracles.miko; // 替换成你的包名

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

public class MainActivity extends AppCompatActivity implements SensorEventListener {

    private static final int REQUEST_NOTIFICATION_PERMISSION = 1001;
    private static final float GRAVITY = 9.81f;
    private static final float DISPLAY_FILTER_ALPHA = 0.25f;

    private TextView statusTextView;
    private TextView tiltAngleTextView;
    private TextView sensitivityLabelTextView;
    private SeekBar sensitivitySeekBar;
    private TextView debounceLabelTextView;
    private SeekBar debounceSeekBar;
    private CheckBox reverseCheckBox;
    private Button startServiceButton;
    private Button stopServiceButton;

    // 仅用于实时角度显示的传感器监听（不影响前台服务里的切歌逻辑）
    private SensorManager sensorManager;
    private Sensor accelerometer;
    private float smoothedTiltDegrees = 0f;
    private boolean isFirstDisplayRead = true;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // ---------- 全屏设置 ----------
        // 隐藏状态栏（顶部时间、电量等）
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);
        // 隐藏 ActionBar（标题栏）
        if (getSupportActionBar() != null) {
            getSupportActionBar().hide();
        }
        // -----------------------------

        // 绑定UI组件
        statusTextView = findViewById(R.id.statusTextView);
        tiltAngleTextView = findViewById(R.id.tiltAngleTextView);
        sensitivityLabelTextView = findViewById(R.id.sensitivityLabelTextView);
        sensitivitySeekBar = findViewById(R.id.sensitivitySeekBar);
        debounceLabelTextView = findViewById(R.id.debounceLabelTextView);
        debounceSeekBar = findViewById(R.id.debounceSeekBar);
        reverseCheckBox = findViewById(R.id.reverseCheckBox);
        startServiceButton = findViewById(R.id.startServiceButton);
        stopServiceButton = findViewById(R.id.stopServiceButton);

        // 获取加速度传感器（仅用于实时显示倾斜角度）
        sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        if (sensorManager != null) {
            accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        }

        // 灵敏度 SeekBar 监听（参数实时同步给前台服务）
        sensitivitySeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                MusicControlService.threshold = (float) (progress * 0.1 + 0.5);
                updateSensitivityLabel();
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        // 防抖间隔 SeekBar 监听（参数实时同步给前台服务）
        debounceSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                float seconds = (float) (progress * 0.1 + 0.5);
                MusicControlService.debounceTimeMillis = (long) (seconds * 1000);
                debounceLabelTextView.setText(String.format("当前间隔: %.1f 秒", seconds));
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        // 方向切换监听（参数实时同步给前台服务）
        reverseCheckBox.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                MusicControlService.isReversed = isChecked;
            }
        });

        // 开启服务按钮
        startServiceButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                requestNotificationPermissionAndStart();
            }
        });

        // 停止服务按钮
        stopServiceButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                stopMusicService();
            }
        });

        // 设置初始显示
        updateSensitivityLabel();
        float initSeconds = (float) (debounceSeekBar.getProgress() * 0.1 + 0.5);
        MusicControlService.debounceTimeMillis = (long) (initSeconds * 1000);
        debounceLabelTextView.setText(String.format("当前间隔: %.1f 秒", initSeconds));

        // 默认关闭服务，需用户手动点击“开启服务”
        updateServiceUi();
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateServiceUi();
        // 注册传感器用于实时显示（即使服务未开启也能看到角度，方便调参）
        if (sensorManager != null && accelerometer != null) {
            isFirstDisplayRead = true;
            smoothedTiltDegrees = 0f;
            sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_UI);
        } else if (tiltAngleTextView != null) {
            tiltAngleTextView.setText("实时倾斜: 无加速度传感器");
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        // 界面不可见时注销显示用监听（前台服务里的监听不受影响）
        if (sensorManager != null) {
            sensorManager.unregisterListener(this);
        }
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() != Sensor.TYPE_ACCELEROMETER) {
            return;
        }
        float rawX = event.values[0];
        // X 轴倾斜角（度）：左倾为负，右倾为正
        float clamped = Math.max(-GRAVITY, Math.min(GRAVITY, rawX));
        float tiltDegrees = (float) Math.toDegrees(Math.asin(clamped / GRAVITY));

        if (isFirstDisplayRead) {
            smoothedTiltDegrees = tiltDegrees;
            isFirstDisplayRead = false;
        } else {
            smoothedTiltDegrees = DISPLAY_FILTER_ALPHA * tiltDegrees
                    + (1 - DISPLAY_FILTER_ALPHA) * smoothedTiltDegrees;
        }
        tiltAngleTextView.setText(String.format("实时倾斜: %+.1f°", smoothedTiltDegrees));
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
    }

    /**
     * 更新灵敏度标签，同时显示阈值对应的倾斜角度，方便用户对照调整
     */
    private void updateSensitivityLabel() {
        float threshold = MusicControlService.threshold;
        float clamped = Math.max(-GRAVITY, Math.min(GRAVITY, threshold));
        double thresholdDegrees = Math.toDegrees(Math.asin(clamped / GRAVITY));
        sensitivityLabelTextView.setText(String.format("当前阈值: %.1f (倾斜约 %.1f° 触发)", threshold, thresholdDegrees));
    }

    /**
     * Android 13+ 需要运行时申请通知权限，否则通知栏常驻通知不会显示
     */
    private void requestNotificationPermissionAndStart() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},
                    REQUEST_NOTIFICATION_PERMISSION);
            return;
        }
        startMusicService();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_NOTIFICATION_PERMISSION) {
            // 无论用户是否允许通知，都启动前台服务（服务仍会运行，只是通知可能不显示）
            startMusicService();
        }
    }

    /**
     * 开启前台服务：通知栏常驻保活，后台持续监听传感器
     */
    private void startMusicService() {
        Intent intent = new Intent(this, MusicControlService.class);
        ContextCompat.startForegroundService(this, intent);
        updateServiceUi();
    }

    /**
     * 停止前台服务
     */
    private void stopMusicService() {
        stopService(new Intent(this, MusicControlService.class));
        updateServiceUi();
    }

    /**
     * 根据服务运行状态刷新界面
     */
    private void updateServiceUi() {
        boolean running = MusicControlService.isRunning;
        startServiceButton.setEnabled(!running);
        stopServiceButton.setEnabled(running);
        if (running) {
            statusTextView.setText("服务运行中（通知栏常驻），倾斜切换音乐...");
        } else {
            statusTextView.setText("服务已停止");
        }
    }
}
