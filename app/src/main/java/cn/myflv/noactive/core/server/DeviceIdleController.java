package cn.myflv.noactive.core.server;

import android.os.Build;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import cn.myflv.noactive.constant.CommonConstants;
import cn.myflv.noactive.constant.FieldConstants;
import cn.myflv.noactive.constant.MethodConstants;
import cn.myflv.noactive.core.utils.Log;
import cn.myflv.noactive.utils.ReflectionUtils;
import lombok.Data;

@Data
public class DeviceIdleController {
    private final Object instance;
    private final int STATE_IDLE;
    private final static String KEY = "NoActive-DeviceIdleController";

    private boolean idle = false;

    public DeviceIdleController(Object instance) {
        this.instance = instance;
        STATE_IDLE = ReflectionUtils.getStaticIntField(instance.getClass(), FieldConstants.STATE_IDLE);
    }

    public void deepDoze() {
        if (idle) {
            return;
        }
        setDeepEnabled(true);
        setForceIdle(true);
        becomeInactiveIfAppropriateLocked();
        int curState = getCurState();
        while (curState != STATE_IDLE) {
            stepIdleStateLocked();
            if (curState == getCurState()) {
                Log.w("deep doze failed");
                return;
            }
            curState = getCurState();
        }
        idle = true;
        Log.d("deep doze success");
    }


    public void exitDeepDoze() {
        if (!idle) {
            return;
        }
        ReflectionUtils.callMethod(instance, MethodConstants.exitForceIdleLocked);
        idle = false;
        Log.d("exit deep doze");
    }

    public void stepIdleStateLocked() {
        ReflectionUtils.callMethod(instance, MethodConstants.stepIdleStateLocked, CommonConstants.NOACTIVE_PACKAGE_NAME);
    }

    public void setDeepEnabled(boolean enabled) {
        ReflectionUtils.setBooleanField(instance, FieldConstants.mDeepEnabled, enabled);
    }

    public void setForceIdle(boolean forceIdle) {
        ReflectionUtils.setBooleanField(instance, FieldConstants.mForceIdle, forceIdle);
    }

    public void becomeInactiveIfAppropriateLocked() {
        ReflectionUtils.callMethod(instance, MethodConstants.becomeInactiveIfAppropriateLocked);
    }

    public int getCurState() {
        return ReflectionUtils.getIntField(instance, FieldConstants.mState);
    }


    public Set<String> getWhiteList() {
        synchronized (instance) {
            Object mPowerSaveWhitelistUserApps = ReflectionUtils.getObjectField(instance, FieldConstants.mPowerSaveWhitelistUserApps);
            Set<?> whiteSet = (Set<?>) ReflectionUtils.callMethod(mPowerSaveWhitelistUserApps, MethodConstants.keySet);
            Set<String> result = new HashSet<>();
            for (Object o : whiteSet) {
                if (o == null) {
                    continue;
                }
                String pkg = (String) o;
                result.add(pkg);
            }
            return result;
        }
    }

    public void addWhiteList(List<String> pkgNames) {
        pkgNames.forEach(pkgName -> Log.d("power white list add " + pkgName));
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            for (String pkgName : pkgNames) {
                ReflectionUtils.callMethod(instance, MethodConstants.addPowerSaveWhitelistAppInternal, pkgName);
            }
        } else {
            ReflectionUtils.callMethod(instance, MethodConstants.addPowerSaveWhitelistAppsInternal, pkgNames);
        }
    }

    public void removeWhiteList(String pkgName) {
        Log.d("power white list remove " + pkgName);
        try {
            ReflectionUtils.callMethod(instance, MethodConstants.removePowerSaveWhitelistAppInternal, pkgName);
        } catch (Throwable e) {
            // 诊断: 上层 catch 会把 InvocationTargetException 包装成 RuntimeException 并丢弃 cause.
            // 这里直接输出 cause 类名 + message，便于从日志判断根因
            // (例如 MIUI/HyperOS Cheeck 拦截 / 方法签名变化 / 权限不足).
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            Throwable rootCause = cause.getCause() != null ? cause.getCause() : cause;
            String causeInfo = rootCause.getClass().getName() + ": " + rootCause.getMessage();
            Log.w("removeWhiteList " + pkgName + " failed [" + e.getClass().getSimpleName() + "] root=" + causeInfo);
        }
    }

}
