package cn.myflv.noactive.core;

import androidx.annotation.NonNull;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam;
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam;

import cn.myflv.noactive.core.app.AndroidHook;
import cn.myflv.noactive.core.app.PowerKeeperHook;

/**
 * LSPosed API 102 模块入口.
 * <p>
 * 框架在目标进程加载时回调 {@link #onModuleLoaded} 完成模块初始化，
 * 之后每次目标包加载时回调 {@link #onPackageLoaded} 分发到具体业务 Hook。
 * <p>
 * 模块入口由 {@code META-INF/xposed/java_init.list} 注册，
 * 模块元信息由 {@code META-INF/xposed/module.prop} 提供，
 * 作用域由 {@code META-INF/xposed/scope.list} 声明。
 */
public class HandleHook extends XposedModule {

    private static volatile HandleHook instance;

    public HandleHook() {
        super();
    }

    public static HandleHook getInstance() {
        return instance;
    }

    @Override
    public void onModuleLoaded(@NonNull ModuleLoadedParam param) {
        instance = this;
        log(android.util.Log.INFO, "NoActive", "module loaded in proc=" + param.getProcessName());
    }

    @Override
    public void onPackageLoaded(@NonNull PackageLoadedParam param) {
        new AndroidHook(param);
        new PowerKeeperHook(param);
    }
}
