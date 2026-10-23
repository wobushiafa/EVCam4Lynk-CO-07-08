package com.kooo.evcam;

import android.content.Context;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 老款领克07/08 ECARX AdaptAPI 1.x 反射封装（转向灯、车速等共用）
 *
 * 接口来源：EVCC v3.4.4 逆向分析（docs-vehicle-api/ 转向灯/车速接口分析 §2）
 * - 框架 jar 由车机系统提供（ecarx.adaptapi.boot.jar / impl.jar），全部反射调用
 * - 双实现树：com.ecarx.xui.adaptapi.* / com.geely.platformapi.*，按可用性回退
 * - Car.create(Context) → [IConnectable 等待 onConnected] → getSensorManager() / getICarFunction()
 *
 * 非线程安全：同一实例应在单一线程（或外部同步）下使用。
 */
public class LynkAdaptApi {

    private static final String TAG = "LynkAdaptApi";

    private static final long CONNECT_TIMEOUT_MS = 5000;

    // 实现树前缀（ECARX 优先，Geely 备用）
    private static final String[] API_PREFIXES = {
            "com.ecarx.xui.adaptapi.",
            "com.geely.platformapi.",
    };

    // 框架 jar 候选路径（Class.forName 失败时手动加载）
    private static final String[] FRAMEWORK_JARS = {
            "/system_ext/framework/ecarx.adaptapi.boot.jar",
            "/system_ext/framework/ecarx.adaptapi.impl.jar",
            "/system_ext/framework/ecarx.adaptapi.jar",
            "/system/framework/ecarx.adaptapi.boot.jar",
            "/system/framework/ecarx.adaptapi.impl.jar",
            "/system/framework/ecarx.adaptapi.jar",
            "/vendor/framework/ecarx.adaptapi.boot.jar",
            "/vendor/framework/ecarx.adaptapi.impl.jar",
            "/vendor/framework/ecarx.adaptapi.jar",
            "/product/framework/ecarx.adaptapi.impl.jar",
    };

    private ClassLoader loader;
    private String prefix;
    private Object car;
    private Object sensor;
    private Object function;
    private Object connectWatcher;
    private Class<?> sensorIface;
    private Class<?> sensorListenerClass;
    private Class<?> functionIface;
    private Class<?> functionWatcherClass;

    private LynkAdaptApi() {
    }

    /**
     * 连接 AdaptAPI，返回 null 表示车机无可用实现（会阻塞最多 CONNECT_TIMEOUT_MS，勿在主线程调用）
     */
    public static LynkAdaptApi connect(Context context) throws Exception {
        List<ClassLoader> loaders = new ArrayList<>();
        loaders.add(LynkAdaptApi.class.getClassLoader());
        ClassLoader frameworkLoader = createFrameworkLoader(context);
        if (frameworkLoader != null) loaders.add(frameworkLoader);

        Exception lastError = null;
        for (ClassLoader loader : loaders) {
            for (String prefix : API_PREFIXES) {
                Class<?> carClass;
                try {
                    carClass = Class.forName(prefix + "car.Car", true, loader);
                } catch (Throwable t) {
                    continue;
                }
                LynkAdaptApi api = new LynkAdaptApi();
                api.loader = loader;
                api.prefix = prefix;
                try {
                    if (api.open(context, carClass)) return api;
                } catch (Exception e) {
                    lastError = e;
                    AppLog.w(TAG, prefix + " 不可用: " + rootMessage(e));
                }
                api.disconnect();
            }
        }
        if (lastError != null) throw lastError;
        AppLog.w(TAG, "未找到 AdaptAPI Car 类（ecarx/geely 均缺失）");
        return null;
    }

    private static ClassLoader createFrameworkLoader(Context context) {
        StringBuilder path = new StringBuilder();
        for (String jar : FRAMEWORK_JARS) {
            if (new File(jar).exists()) {
                if (path.length() > 0) path.append(File.pathSeparator);
                path.append(jar);
            }
        }
        if (path.length() == 0) return null;
        AppLog.d(TAG, "加载框架 jar: " + path);
        return new dalvik.system.PathClassLoader(path.toString(), context.getClassLoader());
    }

    private boolean open(Context context, Class<?> carClass) throws Exception {
        car = createCar(context, carClass);
        if (car == null) {
            AppLog.w(TAG, prefix + "Car 工厂返回 null");
            return false;
        }
        waitForConnected();

        sensor = invokeNoArg(car, "getSensorManager");
        function = invokeNoArg(car, "getICarFunction");
        if (sensor == null && function == null) {
            AppLog.w(TAG, prefix + " sensor/function manager 均不可用");
            return false;
        }

        sensorIface = loadOptional(prefix + "car.sensor.ISensor");
        sensorListenerClass = loadOptional(prefix + "car.sensor.ISensor$ISensorListener");
        functionIface = loadOptional(prefix + "car.base.ICarFunction");
        functionWatcherClass = loadOptional(prefix + "car.base.ICarFunction$IFunctionValueWatcher");
        return true;
    }

    /**
     * Car 工厂：create(Context) / createCar(Context) / getInstance(Context)
     */
    private static Object createCar(Context context, Class<?> carClass) throws Exception {
        for (String name : new String[]{"create", "createCar", "getInstance"}) {
            for (Method m : carClass.getMethods()) {
                if (!m.getName().equals(name) || !Modifier.isStatic(m.getModifiers())) continue;
                Class<?>[] p = m.getParameterTypes();
                if (p.length == 1 && p[0].isAssignableFrom(Context.class)) {
                    Object c = m.invoke(null, context);
                    if (c != null) return c;
                }
            }
        }
        return null;
    }

    /**
     * 若 Car 实现 IConnectable，注册 IConnectWatcher 等待 onConnected
     */
    private void waitForConnected() {
        Method isConnected = findMethod(car, "isConnected", 0);
        if (isConnected != null && Boolean.TRUE.equals(safeInvoke(car, isConnected))) return;

        Class<?> watcherClass = loadOptional(prefix + "binder.IConnectable$IConnectWatcher");
        Method register = findMethod(car, "registerConnectWatcher", 1);
        if (watcherClass == null || register == null) return;

        CountDownLatch latch = new CountDownLatch(1);
        connectWatcher = newProxy(watcherClass, "ConnectWatcher", (proxy, method, args) -> {
            AppLog.d(TAG, "IConnectWatcher." + method.getName());
            if ("onConnected".equals(method.getName())) latch.countDown();
            return defaultReturn(method.getReturnType());
        });
        safeInvoke(car, register, connectWatcher);
        if (isConnected != null && Boolean.TRUE.equals(safeInvoke(car, isConnected))) return;
        try {
            if (!latch.await(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                AppLog.w(TAG, "等待 AdaptAPI 车辆服务连接超时，继续尝试读取");
            }
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    public void disconnect() {
        if (car == null) return;
        if (connectWatcher != null) {
            Method unregister = findMethod(car, "unregisterConnectWatcher", 1);
            if (unregister != null) safeInvoke(car, unregister, connectWatcher);
            connectWatcher = null;
        }
        car = null;
        sensor = null;
        function = null;
    }

    public String getPrefix() {
        return prefix;
    }

    public boolean hasSensor() {
        return sensor != null;
    }

    public boolean hasFunction() {
        return function != null;
    }

    // ==================== 常量解析 ====================

    /**
     * 从 ISensor 接口常量解析 sensor 类型 ID（固件差异时以运行时值为准），失败返回 fallback
     */
    public int resolveSensorId(String name, int fallback) {
        return resolveConstant(name, fallback, sensorIface);
    }

    /**
     * 从 ICarFunction 及常见函数定义接口解析 function ID，失败返回 fallback
     */
    public int resolveFunctionId(String name, int fallback) {
        return resolveConstant(name, fallback, functionIface,
                loadOptional(prefix + "car.vehicle.IVehicle"),
                loadOptional(prefix + "car.base.ICarSetting"));
    }

    private static int resolveConstant(String name, int fallback, Class<?>... candidates) {
        for (Class<?> c : candidates) {
            if (c == null) continue;
            try {
                Field f = c.getField(name);
                int v = f.getInt(null);
                if (v != fallback) {
                    AppLog.i(TAG, name + " 运行时常量=" + v + "（默认 " + fallback + "）");
                }
                return v;
            } catch (Throwable ignored) {
            }
        }
        return fallback;
    }

    // ==================== 读取 ====================

    /**
     * 读取 Int32 状态量 sensor（getSensorEvent 优先，失败回退 getSensorLatestValue），失败返回 null
     */
    public Integer readSensorInt(int type) {
        if (sensor == null) return null;
        Object v = invokeByArgs(sensor, "getSensorEvent", type);
        if (v == null) v = invokeByArgs(sensor, "getSensorLatestValue", type);
        return toInt(v);
    }

    /**
     * 读取 Float 连续量 sensor（getSensorLatestValue），失败返回 null
     */
    public Float readSensorFloat(int type) {
        if (sensor == null) return null;
        Object v = invokeByArgs(sensor, "getSensorLatestValue", type);
        return v instanceof Number ? ((Number) v).floatValue() : null;
    }

    /**
     * 读取 Int32 function 值，失败返回 null
     */
    public Integer readFunctionInt(int id) {
        if (function == null) return null;
        return toInt(invokeByArgs(function, "getFunctionValue", id));
    }

    // ==================== 监听 ====================

    /**
     * 创建 ISensorListener 代理，null 表示接口不可用
     */
    public Object newSensorListener(InvocationHandler handler) {
        return sensorListenerClass == null ? null : newProxy(sensorListenerClass, "SensorListener", handler);
    }

    /**
     * 创建 IFunctionValueWatcher 代理，null 表示接口不可用
     */
    public Object newFunctionWatcher(InvocationHandler handler) {
        return functionWatcherClass == null ? null : newProxy(functionWatcherClass, "FunctionWatcher", handler);
    }

    public boolean registerSensorListener(Object listener, int type) {
        return sensor != null && listener != null && invokeRegister(sensor, "registerListener", listener, type);
    }

    public boolean registerFunctionWatcher(Object watcher, int id) {
        return function != null && watcher != null
                && invokeRegister(function, "registerFunctionValueWatcher", watcher, id);
    }

    public void unregisterSensorListener(Object listener) {
        if (sensor != null && listener != null) invokeByArgs(sensor, "unregisterListener", listener);
    }

    public void unregisterFunctionWatcher(Object watcher) {
        if (function != null && watcher != null) invokeByArgs(function, "unregisterFunctionValueWatcher", watcher);
    }

    /**
     * 创建接口代理，统一处理 Object 方法，业务方法交给 handler
     */
    private Object newProxy(Class<?> iface, String name, InvocationHandler handler) {
        return Proxy.newProxyInstance(loader, new Class<?>[]{iface}, (proxy, method, args) -> {
            if (method.getDeclaringClass() == Object.class) {
                switch (method.getName()) {
                    case "hashCode": return System.identityHashCode(proxy);
                    case "equals": return proxy == args[0];
                    default: return "LynkAdaptApi" + name;
                }
            }
            return handler.invoke(proxy, method, args);
        });
    }

    private Class<?> loadOptional(String name) {
        try {
            return Class.forName(name, false, loader);
        } catch (Throwable t) {
            return null;
        }
    }

    // ==================== 反射工具 ====================

    private static Object invokeNoArg(Object target, String name) {
        Method m = findMethod(target, name, 0);
        return m == null ? null : safeInvoke(target, m);
    }

    /**
     * 按方法名和实参类型匹配调用（int 参数匹配 int/Integer）
     */
    private static Object invokeByArgs(Object target, String name, Object... args) {
        for (Method m : target.getClass().getMethods()) {
            if (!m.getName().equals(name) || m.getParameterTypes().length != args.length) continue;
            if (!argsMatch(m.getParameterTypes(), args)) continue;
            return safeInvoke(target, m, args);
        }
        return null;
    }

    /**
     * 注册监听：兼容 (listener, int) / (int, listener) 两种参数顺序
     */
    private static boolean invokeRegister(Object target, String name, Object callback, int id) {
        for (Method m : target.getClass().getMethods()) {
            if (!m.getName().equals(name) || m.getParameterTypes().length != 2) continue;
            Class<?>[] p = m.getParameterTypes();
            Object[] args;
            if (p[0] == int.class && p[1].isInstance(callback)) {
                args = new Object[]{id, callback};
            } else if (p[1] == int.class && p[0].isInstance(callback)) {
                args = new Object[]{callback, id};
            } else {
                continue;
            }
            try {
                Object r = m.invoke(target, args);
                return !(r instanceof Boolean) || (Boolean) r;
            } catch (Throwable t) {
                AppLog.w(TAG, name + " 调用失败: " + rootMessage(t));
                return false;
            }
        }
        return false;
    }

    private static boolean argsMatch(Class<?>[] params, Object[] args) {
        for (int i = 0; i < params.length; i++) {
            Object a = args[i];
            Class<?> p = params[i];
            if (a instanceof Integer) {
                if (p != int.class && p != Integer.class) return false;
            } else if (a != null && !p.isInstance(a)) {
                return false;
            }
        }
        return true;
    }

    private static Method findMethod(Object target, String name, int paramCount) {
        for (Method m : target.getClass().getMethods()) {
            if (m.getName().equals(name) && m.getParameterTypes().length == paramCount) return m;
        }
        return null;
    }

    private static Object safeInvoke(Object target, Method m, Object... args) {
        try {
            return m.invoke(target, args);
        } catch (Throwable t) {
            AppLog.d(TAG, m.getName() + " 调用失败: " + rootMessage(t));
            return null;
        }
    }

    private static Integer toInt(Object v) {
        if (v instanceof Number) return ((Number) v).intValue();
        if (v instanceof Boolean) return (Boolean) v ? 1 : 0;
        return null;
    }

    /**
     * 代理方法的默认返回值（基本类型必须返回对应包装类型）
     */
    public static Object defaultReturn(Class<?> type) {
        if (type == boolean.class) return false;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0f;
        if (type == double.class) return 0d;
        if (type == short.class) return (short) 0;
        if (type == byte.class) return (byte) 0;
        if (type == char.class) return (char) 0;
        return null;
    }

    public static String rootMessage(Throwable t) {
        while (t.getCause() != null) t = t.getCause();
        return t.getClass().getSimpleName() + ": " + t.getMessage();
    }
}
