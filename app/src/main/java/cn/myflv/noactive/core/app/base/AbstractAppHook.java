package cn.myflv.noactive.core.app.base;

import android.util.Log;

import androidx.annotation.NonNull;

import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam;

import cn.myflv.noactive.core.HandleHook;

/**
 * APP 抽象 Hook（API 102 版本）.
 * <p>
 * 框架通过 {@link HandleHook#onPackageLoaded} 将 {@link PackageLoadedParam} 传入，
 * 当包名匹配 {@link #getTargetPackageName()} 时调用子类实现的 {@link #hook()}.
 */
public abstract class AbstractAppHook {

    /**
     * 载入应用参数.
     */
    public final PackageLoadedParam packageParam;

    /**
     * 执行 Hook.
     *
     * @param packageParam 载入应用参数
     */
    public AbstractAppHook(@NonNull PackageLoadedParam packageParam) {
        String targetPackageName = getTargetPackageName();
        if (targetPackageName == null) {
            log("Target packageName is null");
        }
        this.packageParam = packageParam;
        String packageName = packageParam.getPackageName();
        if (packageName.equals(targetPackageName)) {
            // 尝试 Hook 可以防止软重启
            try {
                hook();
            } catch (Throwable throwable) {
                log("Hook failed: " + throwable.getMessage());
            }
        }
    }

    /**
     * @return 目标包名
     */
    public abstract String getTargetPackageName();

    /**
     * @return 目标应用名称
     */
    public abstract String getTargetAppName();

    /**
     * 真正的 Hook 实现
     */
    public abstract void hook();

    /**
     * 打印日志.
     *
     * @param msg 消息
     */
    public void log(String msg) {
        String targetAppName = getTargetAppName();
        if (targetAppName == null) {
            targetAppName = "null";
        }
        HandleHook instance = HandleHook.getInstance();
        if (instance != null) {
            instance.log(Log.INFO, "NoActive", "NoActive(" + targetAppName + ") -> " + msg);
        }
    }
}
