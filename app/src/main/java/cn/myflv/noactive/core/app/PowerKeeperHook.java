package cn.myflv.noactive.core.app;

import android.content.Context;

import java.lang.reflect.Method;

import cn.myflv.noactive.constant.ClassConstants;
import cn.myflv.noactive.constant.MethodConstants;
import cn.myflv.noactive.core.HandleHook;
import cn.myflv.noactive.core.app.base.AbstractAppHook;
import cn.myflv.noactive.core.hook.base.AbstractReplaceHook;
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam;

/**
 * 电量和性能 Hook（API 102 版本）.
 * <p>
 * v0.9.10 port fix (CRITICAL-01): 补回反编译时漏掉的 3 个 PowerStateMachine hook。
 * 这 3 个 hook 是 v0.9.10 在 HyperOS 上"防杀"的核心机制 ——
 * 通过禁用 MIUI PowerKeeper 的清后台逻辑（息屏超时、夜间息屏、清非活跃应用），
 * 让系统不再主动 kill 后台进程。日志特征："Disable MIUI clearApp"。
 * <p>
 * 使用 Java 反射 + {@link HandleHook#getInstance()} 的 hook().intercept() 注册，
 * 替代旧 API 的 {@code XposedHelpers.findAndHookMethod} + {@code XC_MethodReplacement.DO_NOTHING/returnConstant}.
 */
public class PowerKeeperHook extends AbstractAppHook {

    public PowerKeeperHook(PackageLoadedParam packageParam) {
        super(packageParam);
    }

    @Override
    public String getTargetPackageName() {
        return "com.miui.powerkeeper";
    }

    @Override
    public String getTargetAppName() {
        return "PowerKeeper";
    }

    @Override
    public void hook() {
        // v0.9.10 port fix (CRITICAL-01): 禁用 MIUI PowerKeeper 清后台逻辑（3 个 PowerStateMachine hook）.
        // 反编译漏掉这 3 个会导致 MIUI 在息屏超时/夜间/清非活跃应用时主动 kill 后台进程，
        // 与 NoActive 的"冻结保留"目标冲突。

        // 候选 1: clearAppWhenScreenOffTimeOut()（无参）
        runNoThrow(() -> {
            ClassLoader cl = packageParam.getDefaultClassLoader();
            Class<?> powerStateMachineClass = Class.forName(ClassConstants.PowerStateMachine, false, cl);
            Method clearAppWhenScreenOffTimeOut = powerStateMachineClass.getDeclaredMethod(MethodConstants.clearAppWhenScreenOffTimeOut);
            clearAppWhenScreenOffTimeOut.setAccessible(true);
            HandleHook.getInstance().hook(clearAppWhenScreenOffTimeOut).intercept(AbstractReplaceHook.DO_NOTHING);
        }, "Disable clearAppWhenScreenOffTimeOut");

        // 候选 2: clearAppWhenScreenOffTimeOutInNight()（无参）
        runNoThrow(() -> {
            ClassLoader cl = packageParam.getDefaultClassLoader();
            Class<?> powerStateMachineClass = Class.forName(ClassConstants.PowerStateMachine, false, cl);
            Method clearAppWhenScreenOffTimeOutInNight = powerStateMachineClass.getDeclaredMethod(MethodConstants.clearAppWhenScreenOffTimeOutInNight);
            clearAppWhenScreenOffTimeOutInNight.setAccessible(true);
            HandleHook.getInstance().hook(clearAppWhenScreenOffTimeOutInNight).intercept(AbstractReplaceHook.DO_NOTHING);
        }, "Disable clearAppWhenScreenOffTimeOutInNight");

        // 候选 3: clearUnactiveApps(Context)（单参）
        runNoThrow(() -> {
            ClassLoader cl = packageParam.getDefaultClassLoader();
            Class<?> powerStateMachineClass = Class.forName(ClassConstants.PowerStateMachine, false, cl);
            Method clearUnactiveApps = powerStateMachineClass.getDeclaredMethod(MethodConstants.clearUnactiveApps, Context.class);
            clearUnactiveApps.setAccessible(true);
            HandleHook.getInstance().hook(clearUnactiveApps).intercept(AbstractReplaceHook.DO_NOTHING);
        }, "Disable MIUI clearApp");

        // 禁用 Millet: getEnable(Context) -> returnConstant(false)
        runNoThrow(() -> {
            ClassLoader cl = packageParam.getDefaultClassLoader();
            Class<?> milletConfigClass = Class.forName(ClassConstants.MilletConfig, false, cl);
            Method getEnable = milletConfigClass.getDeclaredMethod(MethodConstants.getEnable, Context.class);
            getEnable.setAccessible(true);
            HandleHook.getInstance().hook(getEnable).intercept(chain -> false);
        }, "Disable Millet");
    }

    public void runNoThrow(Runnable runnable, String msg) {
        try {
            runnable.run();
            log(msg);
        } catch (Throwable throwable) {
            log(msg + " failed: " + throwable.getMessage());
        }
    }
}
