package cn.myflv.noactive.core.app;

import android.content.Context;

import cn.myflv.noactive.constant.ClassConstants;
import cn.myflv.noactive.constant.MethodConstants;
import cn.myflv.noactive.core.app.base.AbstractAppHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 电量和性能Hook.
 * <p>
 * v0.9.10 port fix (CRITICAL-01): 补回反编译时漏掉的 3 个 PowerStateMachine hook。
 * 这 3 个 hook 是 v0.9.10 在 HyperOS 上"防杀"的核心机制 ——
 * 通过禁用 MIUI PowerKeeper 的清后台逻辑（息屏超时、夜间息屏、清非活跃应用），
 * 让系统不再主动 kill 后台进程。日志特征："Disable MIUI clearApp"。
 */
public class PowerKeeperHook extends AbstractAppHook {

    public PowerKeeperHook(XC_LoadPackage.LoadPackageParam packageParam) {
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
        // 引用通过 ClassConstants/MethodConstants，与 V2 代码风格保持一致。
        runNoThrow(() -> {
            XposedHelpers.findAndHookMethod(
                    ClassConstants.PowerStateMachine, packageParam.classLoader,
                    MethodConstants.clearAppWhenScreenOffTimeOut,
                    XC_MethodReplacement.DO_NOTHING);
        }, "Disable clearAppWhenScreenOffTimeOut");

        runNoThrow(() -> {
            XposedHelpers.findAndHookMethod(
                    ClassConstants.PowerStateMachine, packageParam.classLoader,
                    MethodConstants.clearAppWhenScreenOffTimeOutInNight,
                    XC_MethodReplacement.DO_NOTHING);
        }, "Disable clearAppWhenScreenOffTimeOutInNight");

        runNoThrow(() -> {
            XposedHelpers.findAndHookMethod(
                    ClassConstants.PowerStateMachine, packageParam.classLoader,
                    MethodConstants.clearUnactiveApps, Context.class,
                    XC_MethodReplacement.DO_NOTHING);
        }, "Disable MIUI clearApp");

        // 禁用Millet
        runNoThrow(() -> {
            XposedHelpers.findAndHookMethod(
                    ClassConstants.MilletConfig, packageParam.classLoader,
                    MethodConstants.getEnable, Context.class,
                    XC_MethodReplacement.returnConstant(false));
        }, "Disable Millet");
    }

    public void runNoThrow(Runnable runnable, String msg) {
        try {
            runnable.run();
            log(msg);
        } catch (XposedHelpers.ClassNotFoundError | NoSuchMethodError ignored) {
        } catch (Throwable throwable) {
            log(msg + " failed: " + throwable.getMessage());
        }
    }
}
