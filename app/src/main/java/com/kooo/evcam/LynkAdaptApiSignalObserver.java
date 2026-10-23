package com.kooo.evcam;

import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;

import java.lang.reflect.InvocationHandler;

/**
 * 老款领克07/08 转向灯观察者（ECARX AdaptAPI 1.x，见 {@link LynkAdaptApi}）
 *
 * - 首选 ISensor 通道：SENSOR_TYPE_EXTR_LTG_STS_TURN_INDR_LE / RI
 * - 备用 ICarFunction 通道：SETTING_FUNC_TURN_LEFT_LIGHT / RIGHT_LIGHT
 *
 * 读取方式：注册监听（事件推送）+ 定时轮询（兜底），两路汇总到同一状态机。
 * 转向灯为闪烁信号，熄灭需持续 OFF_DEBOUNCE_MS 才判定为关闭。
 * 左右同时亮视为双闪，不触发补盲。
 */
public class LynkAdaptApiSignalObserver {

    private static final String TAG = "LynkAdaptApiObserver";

    private static final long POLL_INTERVAL_MS = 200;
    private static final long OFF_DEBOUNCE_MS = 1500; // 闪烁周期约 0.7s，1.5s 无亮灯视为关闭
    private static final long INIT_RETRY_DELAY_MS = 5000;
    private static final int MAX_INIT_RETRIES = 5;

    // 信号名 → 默认 ID（E171 目录采样；运行时优先从接口常量解析）
    private static final String SENSOR_LEFT_NAME = "SENSOR_TYPE_EXTR_LTG_STS_TURN_INDR_LE";
    private static final String SENSOR_RIGHT_NAME = "SENSOR_TYPE_EXTR_LTG_STS_TURN_INDR_RI";
    private static final int SENSOR_LEFT_DEFAULT = 2106880;   // 0x202600
    private static final int SENSOR_RIGHT_DEFAULT = 2107136;  // 0x202700

    private static final String FUNC_LEFT_NAME = "SETTING_FUNC_TURN_LEFT_LIGHT";
    private static final String FUNC_RIGHT_NAME = "SETTING_FUNC_TURN_RIGHT_LIGHT";
    private static final int FUNC_LEFT_DEFAULT = 587295744;   // 0x23016C00
    private static final int FUNC_RIGHT_DEFAULT = 587296000;  // 0x23016D00

    /**
     * 转向灯信号回调接口
     */
    public interface TurnSignalListener {
        /** 转向灯状态变化 */
        void onTurnSignal(String direction, boolean on);
        /** 连接状态变化 */
        void onConnectionStateChanged(boolean connected);
    }

    private final Context context;
    private final TurnSignalListener listener;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private HandlerThread workerThread;
    private volatile Handler workerHandler;

    private volatile boolean running = false;
    private volatile boolean connected = false;
    private volatile boolean initializing = false;

    private volatile Channels channels;

    // 状态机（仅在 workerHandler 线程访问）
    private long leftLastOnTime = 0;
    private long rightLastOnTime = 0;
    private int lastRawLeft = Integer.MIN_VALUE;
    private int lastRawRight = Integer.MIN_VALUE;
    private String activeDirection = null; // "left" / "right" / null

    private final Runnable pollRunnable = new Runnable() {
        @Override
        public void run() {
            Handler h = workerHandler;
            if (!running || channels == null || h == null) return;
            pollOnceSafe();
            if (running) h.postDelayed(this, POLL_INTERVAL_MS);
        }
    };

    public LynkAdaptApiSignalObserver(Context context, TurnSignalListener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
    }

    /**
     * 启动监听
     */
    public void start() {
        if (running) return;
        running = true;
        workerThread = new HandlerThread("LynkAdaptApiObserver");
        workerThread.start();
        workerHandler = new Handler(workerThread.getLooper());
        attemptInit(0);
    }

    /**
     * 停止监听
     */
    public void stop() {
        running = false;
        connected = false;
        initializing = false;
        Handler h = workerHandler;
        if (h != null) {
            h.removeCallbacksAndMessages(null);
            h.post(this::releaseChannels);
        }
        if (workerThread != null) {
            workerThread.quitSafely();
            workerThread = null;
        }
        workerHandler = null;
    }

    public boolean isConnected() {
        return connected;
    }

    /**
     * 观察者是否存活（已连接，或正在初始化/重试中）
     */
    public boolean isAlive() {
        return running && (connected || initializing);
    }

    /**
     * 一次性连接测试（用于 UI 状态检查）
     */
    public static boolean testConnection(Context context) {
        Channels test = null;
        try {
            test = Channels.open(context.getApplicationContext());
            if (test == null) return false;
            AppLog.d(TAG, "✅ AdaptAPI 可用 (" + test.describe() + ") 当前左=" + test.readLeft()
                    + " 右=" + test.readRight());
            return true;
        } catch (Throwable t) {
            AppLog.e(TAG, "AdaptAPI 连接测试失败: " + LynkAdaptApi.rootMessage(t));
            return false;
        } finally {
            if (test != null) test.api.disconnect();
        }
    }

    // ==================== Internal ====================

    /**
     * 初始化在独立线程执行（不占用 worker Looper，避免 AdaptAPI 连接回调投递到同一 Looper 时死锁）
     */
    private void attemptInit(int attempt) {
        initializing = true;
        new Thread(() -> {
            if (!running) return;
            boolean success = initChannels();
            Handler h = workerHandler;
            if (!running || h == null) {
                releaseChannels();
                return;
            }
            initializing = false;
            notifyConnection(success);

            if (success) {
                h.post(pollRunnable);
            } else if (attempt < MAX_INIT_RETRIES) {
                initializing = true;
                AppLog.w(TAG, "初始化失败，" + INIT_RETRY_DELAY_MS + "ms 后重试 "
                        + (attempt + 1) + "/" + MAX_INIT_RETRIES);
                h.postDelayed(() -> {
                    if (running) attemptInit(attempt + 1);
                }, INIT_RETRY_DELAY_MS);
            } else {
                AppLog.e(TAG, "初始化失败已达 " + MAX_INIT_RETRIES + " 次，观察者停止");
            }
        }, "LynkAdaptApiInit").start();
    }

    private boolean initChannels() {
        try {
            AppLog.d(TAG, "🔍 开始初始化 AdaptAPI 转向灯通道...");
            Channels c = Channels.open(context);
            if (c == null) {
                AppLog.e(TAG, "❌ AdaptAPI 转向灯通道不可用");
                return false;
            }
            channels = c;
            AppLog.i(TAG, "✅ AdaptAPI 已连接: " + c.describe());
            c.registerCallbacks(callbackHandler());
            connected = true;
            return true;
        } catch (Throwable t) {
            AppLog.e(TAG, "❌ AdaptAPI 初始化异常: " + LynkAdaptApi.rootMessage(t));
            releaseChannels();
            return false;
        }
    }

    private void releaseChannels() {
        Channels c = channels;
        channels = null;
        if (c != null) c.release();
    }

    /**
     * 监听回调：任意 onXxxChanged(int id, ...)，按 id 匹配左右后立即刷新状态
     */
    private InvocationHandler callbackHandler() {
        return (proxy, method, args) -> {
            String name = method.getName();
            if (args != null && args.length >= 2 && args[0] instanceof Integer && name.endsWith("Changed")) {
                Channels c = channels;
                if (c != null && c.isTurnSignalId((Integer) args[0])) {
                    AppLog.d(TAG, "📡 " + name + " args=" + java.util.Arrays.deepToString(args));
                    Handler h = workerHandler;
                    if (h != null) h.post(this::pollOnceSafe);
                }
            }
            return LynkAdaptApi.defaultReturn(method.getReturnType());
        };
    }

    private void pollOnceSafe() {
        try {
            pollOnce();
        } catch (Throwable t) {
            AppLog.e(TAG, "读取转向灯失败: " + t.getMessage());
        }
    }

    /**
     * 读取左右转向灯原始值并推进状态机
     */
    private void pollOnce() {
        Channels c = channels;
        if (c == null) return;
        int left = c.readLeft();
        int right = c.readRight();

        if (left != lastRawLeft || right != lastRawRight) {
            AppLog.d(TAG, "🔄 原始值 左=" + left + " 右=" + right);
            lastRawLeft = left;
            lastRawRight = right;
        }

        long now = System.currentTimeMillis();
        if (isOn(left)) leftLastOnTime = now;
        if (isOn(right)) rightLastOnTime = now;

        boolean leftActive = now - leftLastOnTime < OFF_DEBOUNCE_MS;
        boolean rightActive = now - rightLastOnTime < OFF_DEBOUNCE_MS;

        String newDirection;
        if (leftActive && rightActive) {
            newDirection = null; // 双闪，不触发补盲
        } else if (leftActive) {
            newDirection = "left";
        } else if (rightActive) {
            newDirection = "right";
        } else {
            newDirection = null;
        }

        if (newDirection == null ? activeDirection == null : newDirection.equals(activeDirection)) return;

        String old = activeDirection;
        activeDirection = newDirection;
        AppLog.i(TAG, "🚦 转向灯状态: " + (old == null ? "关闭" : old) + " → "
                + (newDirection == null ? (leftActive && rightActive ? "双闪" : "关闭") : newDirection));

        if (listener == null) return;
        mainHandler.post(() -> {
            if (!running) return;
            if (newDirection != null) {
                listener.onTurnSignal(newDirection, true);
            } else if (old != null) {
                listener.onTurnSignal(old, false);
            }
        });
    }

    /**
     * 原始值 → 是否亮灯。0 为关闭，负数为无效/不支持。
     * 实车取值枚举未确认（见分析文档 §11.3），日志中保留原始值便于校准。
     */
    private static boolean isOn(int raw) {
        return raw > 0;
    }

    private void notifyConnection(boolean ok) {
        if (listener != null) mainHandler.post(() -> listener.onConnectionStateChanged(ok));
    }

    /**
     * 左右转向灯读取通道（sensor 优先，function 兜底）
     */
    private static class Channels {
        final LynkAdaptApi api;
        final int sensorLeftId;
        final int sensorRightId;
        final int funcLeftId;
        final int funcRightId;
        boolean sensorReadable;
        boolean functionReadable;
        Object sensorListener;
        Object functionWatcher;

        private Channels(LynkAdaptApi api) {
            this.api = api;
            sensorLeftId = api.resolveSensorId(SENSOR_LEFT_NAME, SENSOR_LEFT_DEFAULT);
            sensorRightId = api.resolveSensorId(SENSOR_RIGHT_NAME, SENSOR_RIGHT_DEFAULT);
            funcLeftId = api.resolveFunctionId(FUNC_LEFT_NAME, FUNC_LEFT_DEFAULT);
            funcRightId = api.resolveFunctionId(FUNC_RIGHT_NAME, FUNC_RIGHT_DEFAULT);
            sensorReadable = api.readSensorInt(sensorLeftId) != null;
            functionReadable = api.readFunctionInt(funcLeftId) != null;
        }

        /**
         * 连接并验证至少一个转向灯通道可读，不可用返回 null
         */
        static Channels open(Context context) throws Exception {
            LynkAdaptApi api = LynkAdaptApi.connect(context);
            if (api == null) return null;
            Channels c = new Channels(api);
            if (!c.sensorReadable && !c.functionReadable) {
                AppLog.w(TAG, api.getPrefix() + " 转向灯信号读取失败（sensor/function 均无返回）");
                api.disconnect();
                return null;
            }
            return c;
        }

        /**
         * 注册事件监听（失败不影响轮询兜底）
         */
        void registerCallbacks(InvocationHandler handler) {
            sensorListener = api.newSensorListener(handler);
            if (sensorListener != null) {
                for (int type : new int[]{sensorLeftId, sensorRightId}) {
                    if (!api.registerSensorListener(sensorListener, type)) {
                        AppLog.w(TAG, "ISensor.registerListener 失败 type=" + type + "，依赖轮询");
                    }
                }
            }
            functionWatcher = api.newFunctionWatcher(handler);
            if (functionWatcher != null) {
                for (int id : new int[]{funcLeftId, funcRightId}) {
                    if (!api.registerFunctionWatcher(functionWatcher, id)) {
                        AppLog.w(TAG, "ICarFunction.registerFunctionValueWatcher 失败 id=" + id);
                    }
                }
            }
        }

        void release() {
            try {
                api.unregisterSensorListener(sensorListener);
                api.unregisterFunctionWatcher(functionWatcher);
            } catch (Throwable ignored) {
            }
            api.disconnect();
        }

        boolean isTurnSignalId(int id) {
            return id == sensorLeftId || id == sensorRightId || id == funcLeftId || id == funcRightId;
        }

        int readLeft() {
            return read(sensorLeftId, funcLeftId);
        }

        int readRight() {
            return read(sensorRightId, funcRightId);
        }

        /**
         * 优先 sensor 通道，失败回退 function 通道；均失败返回 -1
         */
        private int read(int sensorId, int funcId) {
            if (sensorReadable) {
                Integer v = api.readSensorInt(sensorId);
                if (v != null) return v;
            }
            if (functionReadable) {
                Integer v = api.readFunctionInt(funcId);
                if (v != null) return v;
            }
            return -1;
        }

        String describe() {
            return api.getPrefix() + " sensor=" + (sensorReadable ? sensorLeftId + "/" + sensorRightId : "n/a")
                    + " function=" + (functionReadable ? funcLeftId + "/" + funcRightId : "n/a");
        }
    }
}
