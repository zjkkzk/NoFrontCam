# NoFrontCam

> **Xposed 模块 · 禁止APP访问前置摄像头（模拟硬件损坏/不存在）**

适用于需要强制隐藏前置摄像头的场景，如**隐私保护、恶意网站静默拍摄前照**等。通过 Hook Android 相机 API（Camera1 & Camera2），让app认为设备没有前置摄像头或前置摄像头已损坏。

😅扫码时读取前置摄像头何意味？

---

## ✨ 特性

### · 双重策略

* **伪装ID**

  * 修改摄像头列表、数量、信息，从源头隐藏前置（默认开启）。
* **直接拦截**

  * 保留列表，但打开前置时抛出异常（作为备用）。

### · 全面覆盖

* 支持 **Camera（旧版 API）** 和 **Camera2（CameraManager）** 所有常用入口。
* Hook 了 `open`、`getCameraInfo`、`getCameraIdList`、`openCamera` 等关键方法。

### · 低侵入 · 可静默

* 提供日志开关（`LOG_INJECTION` / `LOG_INTERCEPT`），可关闭所有输出。
* 内置缓存和线程隔离，避免递归和性能损耗。

---

## 🛠 使用方法

1. 安装 Xposed  或 LSPosed/LSPatch(无Root自行测试)
2. 将项目编译为 APK 并安装。
3. 在 Xposed/LSPosed 中激活模块，并选择需要生效的应用
4. 重启目标应用，前置摄像头即被屏蔽不会闪退

### 自定义配置

编辑 `NoFrontCam.java` 中的常量：

```java
// 日志开关
private static final boolean LOG_INJECTION = true;   // 注入成功日志
private static final boolean LOG_INTERCEPT = true;   // 每次拦截日志

// 策略开关
private static final boolean ENABLE_FAKE_ID = true;  // true=伪装ID, false=仅直接拦截
```

修改后重新编译即可。

---

## 🔧 工作原理

### Camera1

* 重写 `getNumberOfCameras`，减去前置数量。
* Hook `getCameraInfo`，前置查询直接抛异常。
* 拦截所有 `open` / `reconnect` / 内部初始化方法，前置打开时抛出 `RuntimeException`。

### Camera2

* 过滤 `getCameraIdList` 移除前置 ID。
* 过滤 `getConcurrentCameraIds` / `getPhysicalCameraIds`。
* Hook `getCameraCharacteristics`，前置查询抛 `IllegalArgumentException`。
* 拦截 `openCamera` 重载，提前返回并回调 `onError`。

### 缓存机制

* 使用 `ConcurrentHashMap` 缓存摄像头朝向，避免重复调用系统 API。

---

## 📋 注意事项
**不要Hook系统框架相关的！！未测试**
* **本模块仅修改 API 返回结果，不实际操作硬件，不会损坏设备。**
* 部分应用可能直接通过 JNI 或硬件抽象层访问摄像头，此类场景无法拦截。
* 建议不要hook系统
* 兼容 Android 4.4 – 13+，但部分 API（如 `getCameraExtensionCharacteristics`）仅在特定版本生效。



## 🛡️ 隐私/声明

本模块**不收集任何个人信息**，不联网，不读写存储。所有逻辑均在本地 Hook完成，无痕守护你的前置摄像头，**不要Hook系统未经测试**。对目标应用Hook导致的后续**账号封禁**等问题概不负责。合理使用，勿用于违法用途。后果自负

---

## 📄 许可证

**MIT License**
