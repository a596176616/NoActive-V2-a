package cn.myflv.noactive.core.hook;

import android.app.AlarmManager;
import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.SystemClock;
import android.view.Display;

import java.lang.reflect.Method;

import cn.myflv.noactive.constant.ClassConstants;
import cn.myflv.noactive.constant.MethodConstants;
import cn.myflv.noactive.core.HandleHook;
import cn.myflv.noactive.core.entity.AppInfo;
import cn.myflv.noactive.core.entity.MemData;
import cn.myflv.noactive.core.handler.FreezerHandler;
import cn.myflv.noactive.core.hook.base.AbstractMethodHook;
import cn.myflv.noactive.core.hook.base.MethodHook;
import cn.myflv.noactive.core.utils.Log;
import io.github.libxposed.api.XposedInterface;

public class ScreenStateHook extends MethodHook {

    private final Handler handler = new Handler();

    private final MemData memData;

    private final FreezerHandler freezerHandler;

    private final AlarmManager.OnAlarmListener deepDozeListener;
    private final AlarmManager.OnAlarmListener freezerListener;
    private boolean isDozeNow = false;
    private boolean isFreezeNow = false;

    public ScreenStateHook(ClassLoader classLoader, MemData memData, FreezerHandler freezerHandler) {
        super(classLoader);
        this.memData = memData;
        this.freezerHandler = freezerHandler;
        freezerListener = () -> {
            synchronized (ScreenStateHook.this) {
                freezerHandler.onPause(true, memData.getLastAppInfo());
                isFreezeNow = false;
            }
        };
        deepDozeListener = () -> {
            synchronized (ScreenStateHook.this) {
                memData.getDeviceIdleController().deepDoze();
                isDozeNow = false;
            }
        };
    }

    @Override
    public String getTargetClass() {
        return ClassConstants.DisplayPowerController;
    }

    @Override
    public String getTargetMethod() {
        return MethodConstants.setScreenState;
    }

    @Override
    public Object[] getTargetParam() {
        // SDK <= 33: [int, boolean]
        // SDK 34+:  [int, int reason]
        return new Object[]{int.class, boolean.class};
    }

    @Override
    public XposedInterface.Hooker getTargetHook() {
        return new AbstractMethodHook() {
            @Override
            protected Object afterMethod(XposedInterface.Chain chain, Object result) throws Throwable {
                // 方法是否执行成功
                boolean isChange = (boolean) result;
                if (!isChange) {
                    return result;
                }
                // 显示状态
                int state = (int) chain.getArg(0);
                if (state != Display.STATE_OFF && state != Display.STATE_ON) {
                    return result;
                }
                // 是否关闭
                boolean isOn = (state == Display.STATE_ON);
                // 存储状态
                if (!memData.setScreenOn(isOn)) {
                    return result;
                }
                screenChange(isOn);
                return result;
            }
        };
    }

    /**
     * v0.9.10 port: 重写 hook() 实现多候选签名依次尝试.
     * <p>
     * SDK 34+ 上 setScreenState 第二个参数从 boolean 变成 @Display.StateReason int，
     * 按 SDK 版本选择对应签名。失败时回退到 getTargetParam() 默认签名。
     */
    @Override
    public void hook() {
        int minVersion = getMinVersion();
        if (minVersion != ANY_VERSION && Build.VERSION.SDK_INT < minVersion) {
            return;
        }

        XposedInterface.Hooker targetHook = getTargetHook();
        boolean hooked = false;

        try {
            Class<?> clazz = Class.forName(getTargetClass(), false, classLoader);

            // 候选 1: SDK 34+ - [int, int reason]
            if (Build.VERSION.SDK_INT >= 34) {
                try {
                    Method method = clazz.getDeclaredMethod(getTargetMethod(),
                            int.class, int.class);
                    method.setAccessible(true);
                    HandleHook.getInstance().hook(method).intercept(targetHook);
                    hooked = true;
                    Log.i("Hooked setScreenState (2 args + int reason, SDK >= 34)");
                } catch (Throwable ignored) {
                    // 失败回退
                }
            }

            // 候选 2: 默认签名 - [int, boolean]
            if (!hooked) {
                try {
                    Object[] paramTypesObj = getTargetParam();
                    Class<?>[] paramTypes = new Class<?>[paramTypesObj.length];
                    for (int i = 0; i < paramTypesObj.length; i++) {
                        paramTypes[i] = (Class<?>) paramTypesObj[i];
                    }
                    Method method = clazz.getDeclaredMethod(getTargetMethod(), paramTypes);
                    method.setAccessible(true);
                    HandleHook.getInstance().hook(method).intercept(targetHook);
                    hooked = true;
                    Log.i("Hooked setScreenState (2 args + boolean, legacy)");
                } catch (Throwable throwable) {
                    onError(throwable);
                    return;
                }
            }

            if (hooked) {
                onSuccess();
            }
        } catch (Throwable throwable) {
            onError(throwable);
        }
    }

    public void screenChange(boolean isOn) {
        synchronized (ScreenStateHook.this) {
            Log.d("screen " + (isOn ? "on" : "off"));
            AppInfo lastAppInfo = memData.getLastAppInfo();
            boolean targetApp = memData.isTargetApp(lastAppInfo.getPackageName());
            if (isOn) {
                cancelAll();
                freezerHandler.onResume(targetApp, lastAppInfo);
                memData.getDeviceIdleController().exitDeepDoze();
            } else {
                if (targetApp) {
                    isFreezeNow = true;
                    run(30 * 1000, "Freezer", freezerListener);
                }
                isDozeNow = true;
                run(60 * 1000, "Doze", deepDozeListener);
            }
        }
    }

    public void cancelAll() {
        Context context = memData.getContext();
        if (context == null) {
            return;
        }
        // 获取定时器
        AlarmManager alarmManager = context.getSystemService(AlarmManager.class);
        // 取消冻结定时器
        if (isFreezeNow) {
            alarmManager.cancel(freezerListener);
            Log.d("give up to freeze");
        }
        if (isDozeNow) {
            // 取消Doze定时器
            alarmManager.cancel(deepDozeListener);
            Log.d("give up to doze");
        }
    }


    public void run(long delay, String tag, AlarmManager.OnAlarmListener listener) {
        Context context = memData.getContext();
        if (context == null) {
            return;
        }
        // 获取定时器
        AlarmManager alarmManager = context.getSystemService(AlarmManager.class);
        // 设置指定时间后唤醒
        alarmManager.set(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + delay,
                "NoActive." + tag, listener, handler);
    }


    @Override
    public int getMinVersion() {
        return ANY_VERSION;
    }

    @Override
    public String successLog() {
        return "Listen screen state";
    }
}
