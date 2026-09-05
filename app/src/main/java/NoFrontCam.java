package ac.zjkkzk.nofrontcamera66;

import android.hardware.Camera;
import android.hardware.Camera.CameraInfo;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.os.Handler;
import android.os.Looper;

import java.io.IOException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.LinkedHashSet;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

/**
 * Xposed 隐私保护模块 —— 全局禁用前置摄像头（模拟硬件损坏/不存在）
 * 
 * 日志开关：
 *   LOG_INJECTION = true  → 应用启动时输出一次注入成功日志
 *   LOG_INTERCEPT = true  → 每次拦截前置调用时输出简短日志
 * 策略开关：
 *   ENABLE_FAKE_ID = true → 启用“伪装ID”策略（修改摄像头列表，让应用认为无前置）
 *   ENABLE_FAKE_ID = false→ 仅“直接拦截”（保留摄像头列表，但打开前置时报错）
 * 如需静默运行，将对应值改为 false 即可。
 */
public class NoFrontCam implements IXposedHookLoadPackage {

    // ====================== 日志开关（手动修改） ======================
    private static final boolean LOG_INJECTION = true;  // 是否输出注入成功日志
    private static final boolean LOG_INTERCEPT = true;  // 是否输出每次拦截日志

    // ====================== 策略开关（手动修改） ======================
    private static final boolean ENABLE_FAKE_ID = true; // true=伪装ID, false=仅直接拦截
    // =================================================================

    private static final String TAG = "NoFrontCam";

    // 防重复 Hook
    private static volatile boolean hooksInstalled = false;
    private static final Object HOOK_LOCK = new Object();

    // 线程局部标志，区分内部判断与外部调用，避免递归
    private static final ThreadLocal<Boolean> INTERNAL_CALL = ThreadLocal.withInitial(() -> false);

    // 方向缓存
    private final ConcurrentHashMap<String, Boolean> camera2FrontCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, Boolean> camera1FrontCache = new ConcurrentHashMap<>();

    // ========== 核心判定 ==========

    private boolean isFrontCameraLegacy(int cameraId) {
        // 防止无效 ID 引发异常
        if (cameraId < 0) return false;

        Boolean cached = camera1FrontCache.get(cameraId);
        if (cached != null) return cached;

        boolean isFront = false;
        INTERNAL_CALL.set(true);
        try {
            CameraInfo info = new CameraInfo();
            Camera.getCameraInfo(cameraId, info);
            isFront = (info.facing == CameraInfo.CAMERA_FACING_FRONT);
        } catch (Exception ignored) {
            // 忽略异常，保持 isFront = false
        } finally {
            INTERNAL_CALL.set(false);
        }
        camera1FrontCache.put(cameraId, isFront);
        return isFront;
    }

    private boolean isFrontCamera2(CameraManager manager, String cameraId) {
        Boolean cached = camera2FrontCache.get(cameraId);
        if (cached != null) return cached;

        boolean isFront = false;
        INTERNAL_CALL.set(true);
        try {
            CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraId);
            Integer facing = characteristics.get(CameraCharacteristics.LENS_FACING);
            isFront = (facing != null && facing == CameraCharacteristics.LENS_FACING_FRONT);
        } catch (Exception ignored) {
        } finally {
            INTERNAL_CALL.set(false);
        }
        camera2FrontCache.put(cameraId, isFront);
        return isFront;
    }

    private Integer getCameraId(Camera camera) {
        try {
            Method method = camera.getClass().getMethod("getCameraId");
            return (Integer) method.invoke(camera);
        } catch (Exception e) {
            return null;
        }
    }

    // ========== Camera1 拦截 ==========

    private void hookCamera1API() {
        // ----- 伪装ID（修改摄像头数量/信息） -----
        if (ENABLE_FAKE_ID) {
            // 1. getNumberOfCameras —— 修改总数，减少前置数量
            XposedHelpers.findAndHookMethod(Camera.class, "getNumberOfCameras", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    // 防止递归：如果当前已在内部调用中，则直接返回原始结果
                    if (Boolean.TRUE.equals(INTERNAL_CALL.get())) {
                        return;
                    }
                    Object result = param.getResult();
                    if (result == null) return;
                    int total = (int) result;
                    int frontCount = 0;
                    for (int i = 0; i < total; i++) {
                        if (isFrontCameraLegacy(i)) frontCount++;
                    }
                    param.setResult(total - frontCount);
                }
            });

            // 8. getCameraInfo(int, CameraInfo) —— 抹除前置信息（查询前置时抛异常）
            XposedHelpers.findAndHookMethod(Camera.class, "getCameraInfo", int.class, CameraInfo.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    if (Boolean.TRUE.equals(INTERNAL_CALL.get())) return;
                    int cameraId = (int) param.args[0];
                    if (isFrontCameraLegacy(cameraId)) {
                        if (LOG_INTERCEPT) XposedBridge.log(TAG + " 拦截 getCameraInfo(" + cameraId + ")");
                        throw new RuntimeException("Invalid camera ID " + cameraId);
                    }
                }
            });
        }

        // ----- 直接拦截（打开/初始化摄像头时抛异常） -----
        // 2. open(int)
        XposedHelpers.findAndHookMethod(Camera.class, "open", int.class, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                int id = (int) param.args[0];
                if (isFrontCameraLegacy(id)) {
                    if (LOG_INTERCEPT) XposedBridge.log(TAG + " 拦截 open(" + id + ")");
                    param.setThrowable(new RuntimeException("相机硬件故障，无法连接 (SIMULATED)"));
                }
            }
        });

        // 3. open() 无参
        XposedHelpers.findAndHookMethod(Camera.class, "open", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                if (isFrontCameraLegacy(0)) {
                    if (LOG_INTERCEPT) XposedBridge.log(TAG + " 拦截 open()");
                    param.setThrowable(new RuntimeException("相机硬件故障，无法连接 (SIMULATED)"));
                }
            }
        });

        // 4. openLegacy(int, int)
        XposedHelpers.findAndHookMethod(Camera.class, "openLegacy", int.class, int.class, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                int id = (int) param.args[0];
                if (isFrontCameraLegacy(id)) {
                    if (LOG_INTERCEPT) XposedBridge.log(TAG + " 拦截 openLegacy(" + id + ")");
                    param.setThrowable(new RuntimeException("相机硬件故障，无法连接 (SIMULATED)"));
                }
            }
        });

        // 5. reconnect()
        XposedHelpers.findAndHookMethod(Camera.class, "reconnect", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                Camera camera = (Camera) param.thisObject;
                Integer cameraId = getCameraId(camera);
                if (cameraId != null && isFrontCameraLegacy(cameraId)) {
                    if (LOG_INTERCEPT) XposedBridge.log(TAG + " 拦截 reconnect()");
                    throw new IOException("相机硬件故障，无法重新连接 (SIMULATED)");
                }
            }
        });

        // 6. cameraInitNormal(int) —— 隐藏方法
        try {
            XposedHelpers.findAndHookMethod(Camera.class, "cameraInitNormal", int.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    int cameraId = (int) param.args[0];
                    if (isFrontCameraLegacy(cameraId)) {
                        if (LOG_INTERCEPT) XposedBridge.log(TAG + " 拦截 cameraInitNormal(" + cameraId + ")");
                        param.setThrowable(new RuntimeException("相机硬件故障，无法初始化 (SIMULATED)"));
                    }
                }
            });
        } catch (Throwable ignored) {}

        // 7. cameraInitVersion(int, int) —— 隐藏方法
        try {
            XposedHelpers.findAndHookMethod(Camera.class, "cameraInitVersion", int.class, int.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    int cameraId = (int) param.args[0];
                    if (isFrontCameraLegacy(cameraId)) {
                        if (LOG_INTERCEPT) XposedBridge.log(TAG + " 拦截 cameraInitVersion(" + cameraId + ")");
                        param.setThrowable(new RuntimeException("相机硬件故障，无法初始化 (SIMULATED)"));
                    }
                }
            });
        } catch (Throwable ignored) {}
    }

    // ========== Camera2 拦截 ==========

    private void hookCamera2API() {
        // ----- 伪装ID（修改摄像头列表/信息） -----
        if (ENABLE_FAKE_ID) {
            // 1. getCameraIdList —— 修改列表，移除前置ID
            XposedHelpers.findAndHookMethod(CameraManager.class, "getCameraIdList", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    String[] original = (String[]) param.getResult();
                    if (original == null || original.length == 0) return;
                    CameraManager manager = (CameraManager) param.thisObject;
                    List<String> filtered = new ArrayList<>();
                    for (String id : original) {
                        if (!isFrontCamera2(manager, id)) {
                            filtered.add(id);
                        }
                    }
                    param.setResult(filtered.toArray(new String[0]));
                }
            });

            // 5. getConcurrentCameraIds —— 移除包含前置的组合（新建集合避免不可变）
            try {
                XposedHelpers.findAndHookMethod(CameraManager.class, "getConcurrentCameraIds", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        Object result = param.getResult();
                        if (result == null) return;
                        CameraManager manager = (CameraManager) param.thisObject;
                        if (result instanceof java.util.Collection) {
                            java.util.Collection<?> outer = (java.util.Collection<?>) result;
                            java.util.Collection<Object> filteredOuter = new ArrayList<>();
                            for (Object item : outer) {
                                boolean containsFront = false;
                                if (item instanceof java.util.Collection) {
                                    for (Object idObj : (java.util.Collection<?>) item) {
                                        if (idObj instanceof String && isFrontCamera2(manager, (String) idObj)) {
                                            containsFront = true;
                                            break;
                                        }
                                    }
                                } else if (item instanceof String[]) {
                                    for (String id : (String[]) item) {
                                        if (isFrontCamera2(manager, id)) {
                                            containsFront = true;
                                            break;
                                        }
                                    }
                                }
                                if (!containsFront) {
                                    filteredOuter.add(item);
                                }
                            }
                            param.setResult(filteredOuter);
                        }
                    }
                });
            } catch (Throwable ignored) {}

            // 6. getPhysicalCameraIds —— 过滤物理摄像头ID（新建Set避免removeIf）
            XposedHelpers.findAndHookMethod(CameraCharacteristics.class, "getPhysicalCameraIds", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    Object result = param.getResult();
                    if (result instanceof Set) {
                        @SuppressWarnings("unchecked")
                        Set<String> ids = (Set<String>) result;
                        Set<String> filtered = new LinkedHashSet<>();
                        for (String id : ids) {
                            if (!camera2FrontCache.getOrDefault(id, false)) {
                                filtered.add(id);
                            }
                        }
                        param.setResult(filtered);
                    }
                }
            });

            // 7. getCameraCharacteristics —— 抹除前置信息（查询前置时抛异常）
            XposedHelpers.findAndHookMethod(CameraManager.class, "getCameraCharacteristics", String.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    if (Boolean.TRUE.equals(INTERNAL_CALL.get())) return;
                    String id = (String) param.args[0];
                    CameraManager manager = (CameraManager) param.thisObject;
                    if (isFrontCamera2(manager, id)) {
                        if (LOG_INTERCEPT) XposedBridge.log(TAG + " 拦截 getCameraCharacteristics(ID:" + id + ")");
                        throw new IllegalArgumentException("Unknown camera ID: " + id);
                    }
                }
            });

            // 8. getCameraExtensionCharacteristics (Android 12+) —— 抹除前置信息
            try {
                XposedHelpers.findAndHookMethod(CameraManager.class, "getCameraExtensionCharacteristics", String.class, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        if (Boolean.TRUE.equals(INTERNAL_CALL.get())) return;
                        String id = (String) param.args[0];
                        CameraManager manager = (CameraManager) param.thisObject;
                        if (isFrontCamera2(manager, id)) {
                            if (LOG_INTERCEPT) XposedBridge.log(TAG + " 拦截 getCameraExtensionCharacteristics(ID:" + id + ")");
                            throw new IllegalArgumentException("Unknown camera ID: " + id);
                        }
                    }
                });
            } catch (Throwable ignored) {}
        }

        // ----- 直接拦截（打开摄像头时抛异常） -----
        // 2. openCamera (Executor)
        XposedHelpers.findAndHookMethod(CameraManager.class, "openCamera",
                String.class, Executor.class, CameraDevice.StateCallback.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        String id = (String) param.args[0];
                        CameraManager manager = (CameraManager) param.thisObject;
                        if (isFrontCamera2(manager, id)) {
                            if (LOG_INTERCEPT) XposedBridge.log(TAG + " 拦截 openCamera(ID:" + id + ")");
                            param.setResult(null);
                            CameraDevice.StateCallback callback = (CameraDevice.StateCallback) param.args[2];
                            Executor executor = (Executor) param.args[1];
                            if (callback != null) {
                                Runnable errorTask = () -> {
                                    try { callback.onError(null, CameraDevice.StateCallback.ERROR_CAMERA_DEVICE); } catch (Exception ignored) {}
                                };
                                if (executor != null) executor.execute(errorTask);
                                else new Handler(Looper.getMainLooper()).post(errorTask);
                            }
                        }
                    }
                });

        // 3. openCamera (Handler)
        XposedHelpers.findAndHookMethod(CameraManager.class, "openCamera",
                String.class, CameraDevice.StateCallback.class, Handler.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        String id = (String) param.args[0];
                        CameraManager manager = (CameraManager) param.thisObject;
                        if (isFrontCamera2(manager, id)) {
                            if (LOG_INTERCEPT) XposedBridge.log(TAG + " 拦截 openCamera(ID:" + id + ")");
                            param.setResult(null);
                            CameraDevice.StateCallback callback = (CameraDevice.StateCallback) param.args[1];
                            Handler handler = (Handler) param.args[2];
                            if (callback != null) {
                                Runnable errorTask = () -> {
                                    try { callback.onError(null, CameraDevice.StateCallback.ERROR_CAMERA_DEVICE); } catch (Exception ignored) {}
                                };
                                if (handler != null) handler.post(errorTask);
                                else new Handler(Looper.getMainLooper()).post(errorTask);
                            }
                        }
                    }
                });

        // 4. openCamera (Android 13+ 四参)
        try {
            XposedHelpers.findAndHookMethod(CameraManager.class, "openCamera",
                    String.class, Executor.class, int.class, CameraDevice.StateCallback.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            String id = (String) param.args[0];
                            CameraManager manager = (CameraManager) param.thisObject;
                            if (isFrontCamera2(manager, id)) {
                                if (LOG_INTERCEPT) XposedBridge.log(TAG + " 拦截 openCamera13(ID:" + id + ")");
                                param.setResult(null);
                                CameraDevice.StateCallback callback = (CameraDevice.StateCallback) param.args[3];
                                Executor executor = (Executor) param.args[1];
                                if (callback != null) {
                                    Runnable errorTask = () -> {
                                        try { callback.onError(null, CameraDevice.StateCallback.ERROR_CAMERA_DEVICE); } catch (Exception ignored) {}
                                    };
                                    if (executor != null) executor.execute(errorTask);
                                    else new Handler(Looper.getMainLooper()).post(errorTask);
                                }
                            }
                        }
                    });
        } catch (Throwable ignored) {}
    }

    // ========== Xposed 入口 ==========

    @Override
    public void handleLoadPackage(LoadPackageParam loadPackageParam) throws Throwable {
        if (hooksInstalled) return;
        synchronized (HOOK_LOCK) {
            if (hooksInstalled) return;
            hooksInstalled = true;
        }

        if (LOG_INJECTION) {
            XposedBridge.log(TAG + " 已注入/Injected " + loadPackageParam.packageName);
        }

        hookCamera1API();
        hookCamera2API();
    }
}