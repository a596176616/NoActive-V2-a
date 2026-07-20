package cn.myflv.noactive.core;

import androidx.annotation.NonNull;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam;
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam;
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam;

import cn.myflv.noactive.core.app.AndroidHook;
import cn.myflv.noactive.core.app.PowerKeeperHook;

/**
 * LSPosed API 102 模块入口.
 * <p>
 * 框架在目标进程加载时回调 {@link #onModuleLoaded} 完成模块初始化，
 * 之后每次目标包加载时回调 {@link #onPackageLoaded} 分发到具体业务 Hook。
 * <p>
 * 对于 system_server：开机早期加载时 LSPosed Manager 尚未启动，
 * {@link #onPackageLoaded} 不会被调用，因此通过 {@link #onSystemServerStarting}
 * 直接执行 AndroidHook（核心 Hook 注册：ANRHook、BroadcastDeliverHook 等）。
 * <p>
 * 模块入口由 {@code META-INF/xposed/java_init.list} 注册，
 * 模块元信息由 {@code META-INF/xposed/module.prop} 提供，
 * 作用域由 {@code META-INF/xposed/scope.list} 声明。
 */
public class HandleHook extends XposedModule {

    private static volatile HandleHook instance;
    private static volatile boolean systemServerHooked = false;

    public HandleHook() {
        super();
    }

    public static HandleHook getInstance() {
        return instance;
    }

    @Override
    public void onModuleLoaded(@NonNull ModuleLoadedParam param) {
        instance = this;
        log(android.util.Log.INFO, "NoActive", "module loaded in proc=" + param.getProcessName()
                + ", isSystemServer=" + param.isSystemServer());
    }

    /**
     * system_server 启动时回调.
     * <p>
     * 在 system_server 中，{@link #onPackageLoaded} 不会被调用（开机早期 LSPosed Manager
     * 尚未启动），因此必须通过本回调直接执行 AndroidHook，否则所有核心 Hook
     * （ANRHook、BroadcastDeliverHook、ActivitySwitchHook 等）都不会注册。
     * <p>
     * 通过 {@link SystemServerStartingParam#getClassLoader()} 获取系统类加载器，
     * 包装成 {@link PackageLoadedParam} 传给 {@link AndroidHook}。
     */
    @Override
    public void onSystemServerStarting(@NonNull SystemServerStartingParam param) {
        if (systemServerHooked) {
            log(android.util.Log.INFO, "NoActive", "system_server already hooked, skip");
            return;
        }
        systemServerHooked = true;
        log(android.util.Log.INFO, "NoActive", "onSystemServerStarting: hook system_server");

        ClassLoader classLoader = param.getClassLoader();
        // 包装为 PackageLoadedParam 传给 AndroidHook
        PackageLoadedParam fakeParam = new PackageLoadedParam() {
            @Override
            public String getPackageName() {
                return "system";
            }

            @Override
            public android.content.pm.ApplicationInfo getApplicationInfo() {
                return null;
            }

            @Override
            public boolean isFirstPackage() {
                return true;
            }

            @Override
            public ClassLoader getDefaultClassLoader() {
                return classLoader;
            }
        };
        new AndroidHook(fakeParam);
    }

    @Override
    public void onPackageLoaded(@NonNull PackageLoadedParam param) {
        // system_server 由 onSystemServerStarting 处理，这里跳过避免重复
        String packageName = param.getPackageName();
        if ("system".equals(packageName)) {
            log(android.util.Log.INFO, "NoActive", "onPackageLoaded: system_server already hooked by onSystemServerStarting, skip");
            return;
        }
        new AndroidHook(param);
        new PowerKeeperHook(param);
    }
}
