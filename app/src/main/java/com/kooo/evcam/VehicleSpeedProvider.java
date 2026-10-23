package com.kooo.evcam;

import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;

import java.util.Locale;

/**
 * 车速提供者（老款领克07/08，ECARX AdaptAPI 1.x，见 {@link LynkAdaptApi}）
 *
 * 接口来源：docs-vehicle-api/ 车速接口分析 §2.2
 * - 主信号：SENSOR_TYPE_CAR_SPEED（Float）
 * - 备用：SENSOR_TYPE_CAR_SPEED_FROM_IPK（仪表车速）
 *
 * 录制期间由 MultiCameraManager 启停，后台线程轮询，供视频角标读取最新车速。
 * 单位按 km/h 处理（目录未标注单位，见分析文档 §7.3），日志保留原始值便于校准。
 */
public class VehicleSpeedProvider {

    private static final String TAG = "VehicleSpeedProvider";

    private static final long POLL_INTERVAL_MS = 500;
    private static final long RETRY_DELAY_MS = 10000;
    private static final long STALE_MS = 3000; // 超过 3 秒未读到有效值视为无效

    private static final String SENSOR_SPEED_NAME = "SENSOR_TYPE_CAR_SPEED";
    private static final String SENSOR_SPEED_IPK_NAME = "SENSOR_TYPE_CAR_SPEED_FROM_IPK";
    private static final int SENSOR_SPEED_DEFAULT = 1048832;      // 0x100100
    private static final int SENSOR_SPEED_IPK_DEFAULT = 1070336;  // 0x105500

    private static volatile VehicleSpeedProvider instance;

    private final Context context;
    private HandlerThread workerThread;
    private volatile Handler workerHandler;
    private volatile boolean running = false;

    // 仅在 worker 线程访问
    private LynkAdaptApi api;
    private int speedSensorId;
    private int ipkSensorId;
    private int lastLoggedSpeed = Integer.MIN_VALUE;

    private volatile float speedKmh = Float.NaN;
    private volatile long lastValidTime = 0;
    private volatile boolean everConnected = false;

    public static VehicleSpeedProvider getInstance(Context context) {
        if (instance == null) {
            synchronized (VehicleSpeedProvider.class) {
                if (instance == null) {
                    instance = new VehicleSpeedProvider(context.getApplicationContext());
                }
            }
        }
        return instance;
    }

    /**
     * 供无 Context 的调用方（如编码器线程）读取角标文本，未创建实例时返回空串
     */
    public static String currentDisplayText() {
        VehicleSpeedProvider p = instance;
        return p == null ? "" : p.getDisplayText();
    }

    private VehicleSpeedProvider(Context context) {
        this.context = context;
    }

    /**
     * 启动轮询（重复调用无副作用）
     */
    public synchronized void start() {
        if (running) return;
        running = true;
        workerThread = new HandlerThread("VehicleSpeedProvider");
        workerThread.start();
        workerHandler = new Handler(workerThread.getLooper());
        workerHandler.post(this::connectAndPoll);
        AppLog.d(TAG, "车速轮询已启动");
    }

    /**
     * 停止轮询并释放连接
     */
    public synchronized void stop() {
        if (!running) return;
        running = false;
        Handler h = workerHandler;
        if (h != null) {
            h.removeCallbacksAndMessages(null);
            h.post(this::releaseApi);
        }
        if (workerThread != null) {
            workerThread.quitSafely();
            workerThread = null;
        }
        workerHandler = null;
        speedKmh = Float.NaN;
        AppLog.d(TAG, "车速轮询已停止");
    }

    /**
     * 最新车速（km/h），无效时返回 NaN
     */
    public float getSpeedKmh() {
        if (System.currentTimeMillis() - lastValidTime > STALE_MS) return Float.NaN;
        return speedKmh;
    }

    /**
     * 角标显示文本：有效 "56km/h"，暂无数据 "--km/h"，车机不支持（从未连上）返回空串
     */
    public String getDisplayText() {
        if (!running || !everConnected) return "";
        float v = getSpeedKmh();
        if (Float.isNaN(v)) return "--km/h";
        return String.format(Locale.US, "%dkm/h", Math.round(v));
    }

    // ==================== Internal ====================

    private void connectAndPoll() {
        if (!running) return;
        if (api == null && !connect()) {
            Handler h = workerHandler;
            if (running && h != null) h.postDelayed(this::connectAndPoll, RETRY_DELAY_MS);
            return;
        }
        pollOnce();
        Handler h = workerHandler;
        if (running && h != null) h.postDelayed(this::connectAndPoll, POLL_INTERVAL_MS);
    }

    private boolean connect() {
        try {
            LynkAdaptApi a = LynkAdaptApi.connect(context);
            if (a == null || !a.hasSensor()) {
                AppLog.w(TAG, "AdaptAPI sensor 不可用，" + RETRY_DELAY_MS + "ms 后重试");
                if (a != null) a.disconnect();
                return false;
            }
            api = a;
            speedSensorId = a.resolveSensorId(SENSOR_SPEED_NAME, SENSOR_SPEED_DEFAULT);
            ipkSensorId = a.resolveSensorId(SENSOR_SPEED_IPK_NAME, SENSOR_SPEED_IPK_DEFAULT);
            everConnected = true;
            AppLog.i(TAG, "✅ 车速通道已连接: " + a.getPrefix() + " sensor=" + speedSensorId + "/" + ipkSensorId);
            return true;
        } catch (Throwable t) {
            AppLog.e(TAG, "车速通道连接失败: " + LynkAdaptApi.rootMessage(t));
            return false;
        }
    }

    private void pollOnce() {
        Float v = api.readSensorFloat(speedSensorId);
        if (!isValid(v)) v = api.readSensorFloat(ipkSensorId);
        if (!isValid(v)) return;

        speedKmh = Math.max(0f, v);
        lastValidTime = System.currentTimeMillis();

        int rounded = Math.round(v);
        if (rounded != lastLoggedSpeed && (lastLoggedSpeed == Integer.MIN_VALUE || Math.abs(rounded - lastLoggedSpeed) >= 10)) {
            AppLog.d(TAG, "🚗 车速原始值: " + v);
            lastLoggedSpeed = rounded;
        }
    }

    private static boolean isValid(Float v) {
        // 负值/超大值视为无效（未就绪或错误码）
        return v != null && !v.isNaN() && !v.isInfinite() && v > -1f && v < 400f;
    }

    private void releaseApi() {
        if (api != null) {
            api.disconnect();
            api = null;
        }
    }
}
