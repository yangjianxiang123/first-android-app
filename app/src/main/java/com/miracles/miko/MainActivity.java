package com.miracles.miko; // 替换成你的包名

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.media.AudioManager;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

public class MainActivity extends AppCompatActivity implements SensorEventListener {

    private TextView statusTextView;
    private TextView sensitivityLabelTextView;
    private SeekBar sensitivitySeekBar;
    private TextView debounceLabelTextView;
    private SeekBar debounceSeekBar;
    private CheckBox reverseCheckBox;
    private Button startServiceButton;
    private Button stopServiceButton;

    private SensorManager sensorManager;
    private Sensor accelerometer;

    private float threshold = 2.0f;
    private boolean isReversed = false;
    private long debounceTimeMillis = 1200;
    // 服务是否处于运行状态
    private boolean isServiceRunning = false;

    private AudioManager audioManager;

    private long lastTriggerTime = 0;

    // 滤波相关
    private float smoothedX = 0f;
    private boolean isFirstSensorRead = true;
    private static final float FILTER_ALPHA = 0.15f;

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
        sensitivityLabelTextView = findViewById(R.id.sensitivityLabelTextView);
        sensitivitySeekBar = findViewById(R.id.sensitivitySeekBar);
        debounceLabelTextView = findViewById(R.id.debounceLabelTextView);
        debounceSeekBar = findViewById(R.id.debounceSeekBar);
        reverseCheckBox = findViewById(R.id.reverseCheckBox);
        startServiceButton = findViewById(R.id.startServiceButton);
        stopServiceButton = findViewById(R.id.stopServiceButton);

        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);

        sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        if (sensorManager != null) {
            accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
            if (accelerometer == null) {
                statusTextView.setText("设备不支持加速度传感器");
            }
        } else {
            statusTextView.setText("无法获取传感器服务");
        }

        // 灵敏度 SeekBar 监听
        sensitivitySeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                threshold = (float) (progress * 0.1 + 0.5);
                sensitivityLabelTextView.setText(String.format("当前阈值: %.1f", threshold));
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        // 防抖间隔 SeekBar 监听
        debounceSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                float seconds = (float) (progress * 0.1 + 0.5);
                debounceTimeMillis = (long) (seconds * 1000);
                debounceLabelTextView.setText(String.format("当前间隔: %.1f 秒", seconds));
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        // 方向切换监听
        reverseCheckBox.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                isReversed = isChecked;
            }
        });

        // 开启服务按钮
        startServiceButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startService();
            }
        });

        // 停止服务按钮
        stopServiceButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                stopService();
            }
        });

        // 设置初始显示
        sensitivityLabelTextView.setText(String.format("当前阈值: %.1f", threshold));
        float initSeconds = (float) (debounceSeekBar.getProgress() * 0.1 + 0.5);
        debounceTimeMillis = (long) (initSeconds * 1000);
        debounceLabelTextView.setText(String.format("当前间隔: %.1f 秒", initSeconds));

        // 默认关闭服务，需用户手动点击“开启服务”
        stopService();
    }

    /**
     * 开启服务：注册加速度传感器监听
     */
    private void startService() {
        if (accelerometer == null) {
            statusTextView.setText("无法获取加速度传感器");
            return;
        }
        if (isServiceRunning) {
            statusTextView.setText("服务已在运行中");
            return;
        }
        if (sensorManager != null) {
            sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_GAME);
            isServiceRunning = true;
            isFirstSensorRead = true;
            smoothedX = 0f;
            lastTriggerTime = 0;
            statusTextView.setText("服务已开启，倾斜切换到音乐...");
        }
    }

    /**
     * 停止服务：注销加速度传感器监听
     */
    private void stopService() {
        if (!isServiceRunning) {
            statusTextView.setText("服务已停止");
            return;
        }
        if (sensorManager != null) {
            sensorManager.unregisterListener(this);
        }
        isServiceRunning = false;
        statusTextView.setText("服务已停止");
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 服务由“开启服务 / 停止服务”按钮控制。
        // 若离开了界面，回来时保持原状态：是停止就保持停止，是运行就重新注册。
        if (isServiceRunning && sensorManager != null && accelerometer != null) {
            sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_GAME);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        // 离开界面时注销监听（释放资源），但保留 isServiceRunning 状态以便返回时恢复
        if (sensorManager != null && isServiceRunning) {
            sensorManager.unregisterListener(this);
        }
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() == Sensor.TYPE_ACCELEROMETER) {
            float rawX = event.values[0];

            if (isFirstSensorRead) {
                smoothedX = rawX;
                isFirstSensorRead = false;
            } else {
                smoothedX = FILTER_ALPHA * rawX + (1 - FILTER_ALPHA) * smoothedX;
            }

            statusTextView.setText(String.format("平滑X轴: %.2f", smoothedX));

            long currentTime = System.currentTimeMillis();
            if (currentTime - lastTriggerTime < debounceTimeMillis) {
                return;
            }

            if (isReversed) {
                if (smoothedX > threshold) {
                    lastTriggerTime = currentTime;
                    sendMediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS);
                    statusTextView.setText("← 上一曲 (右倾)");
                } else if (smoothedX < -threshold) {
                    lastTriggerTime = currentTime;
                    sendMediaKey(KeyEvent.KEYCODE_MEDIA_NEXT);
                    statusTextView.setText("→ 下一曲 (左倾)");
                }
            } else {
                if (smoothedX > threshold) {
                    lastTriggerTime = currentTime;
                    sendMediaKey(KeyEvent.KEYCODE_MEDIA_NEXT);
                    statusTextView.setText("→ 下一曲");
                } else if (smoothedX < -threshold) {
                    lastTriggerTime = currentTime;
                    sendMediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS);
                    statusTextView.setText("← 上一曲");
                }
            }
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {}

    private void sendMediaKey(int keyCode) {
        if (audioManager == null) return;
        KeyEvent downEvent = new KeyEvent(KeyEvent.ACTION_DOWN, keyCode);
        audioManager.dispatchMediaKeyEvent(downEvent);
        KeyEvent upEvent = new KeyEvent(KeyEvent.ACTION_UP, keyCode);
        audioManager.dispatchMediaKeyEvent(upEvent);
    }
}