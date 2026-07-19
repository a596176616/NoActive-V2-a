package cn.myflv.noactive.core.hook;

import android.app.usage.UsageEvents;
import android.content.ComponentName;
import android.content.Intent;
import android.os.Build;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;

import cn.myflv.noactive.constant.ClassConstants;
import cn.myflv.noactive.constant.MethodConstants;
import cn.myflv.noactive.core.entity.AppInfo;
import cn.myflv.noactive.core.entity.MemData;
import cn.myflv.noactive.core.handler.FreezerHandler;
import cn.myflv.noactive.core.hook.base.AbstractMethodHook;
import cn.myflv.noactive.core.hook.base.MethodHook;
import cn.myflv.noactive.core.utils.Log;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;

/**
 * Activity切换Hook
 * <p>
 * v0.9.10 port:
 * <ul>
 *   <li>SDK 34+ updateActivityUsageStats 新增 ActivityId 参数，通过重写 hook() 实现多候选签名依次尝试</li>
 *   <li>启动后首次 Activity 切换时触发 R4 一次性重新冻结（sRefrozen 控制）</li>
 * </ul>
 */
public class ActivitySwitchHook extends MethodHook {

    /**
     * 进入前台.
     */
    private final int ACTIVITY_RESUMED = UsageEvents.Event.ACTIVITY_RESUMED;
    /**
     * 进入后台.
     */
    private final int ACTIVITY_PAUSED = UsageEvents.Event.ACTIVITY_PAUSED;
    /**
     * 内存数据.
     */
    private final MemData memData;

    private final FreezerHandler freezerHandler;

    /**
     * v0.9.10 port: R4 refreeze 一次性触发标志.
     * <p>
     * 启动后第一次 Activity 切换时通过 CAS 将其置为 true，并触发 refreezeAll。
     * 后续 Activity 切换不再触发 R4。
     */
    private static final AtomicBoolean sRefrozen = new AtomicBoolean(false);

    public ActivitySwitchHook(ClassLoader classLoader, MemData memData, FreezerHandler freezerHandler) {
        super(classLoader);
        this.memData = memData;
        this.freezerHandler = freezerHandler;
    }


    @Override
    public String getTargetClass() {
        return ClassConstants.ActivityManagerService;
    }

    @Override
    public String getTargetMethod() {
        return MethodConstants.updateActivityUsageStats;
    }

    @Override
    public Object[] getTargetParam() {
        // SDK <= 32 默认签名：5 args
        return new Object[]{ClassConstants.ComponentName, int.class, int.class,
                ClassConstants.IBinder, ClassConstants.ComponentName};
    }


    @Override
    public int getMinVersion() {
        return Build.VERSION_CODES.Q;
    }

    @Override
    public String successLog() {
        return "Listen app switch";
    }

    /**
     * v0.9.10 port: 重写 hook() 实现多候选签名依次尝试.
     * <p>
     * SDK 34+ updateActivityUsageStats 新增 ActivityId 参数，SDK 33 新增 Intent 参数。
     * 按优先级尝试：SDK 34+ (ActivityId) → SDK 33 (Intent) → SDK <= 32 (5 args)。
     */
    @Override
    public void hook() {
        int minVersion = getMinVersion();
        if (minVersion != ANY_VERSION && Build.VERSION.SDK_INT < minVersion) {
            return;
        }

        XC_MethodHook targetHook = getTargetHook();
        boolean hooked = false;

        // 候选 1: SDK 34+ - 6 args + ActivityId
        if (Build.VERSION.SDK_INT >= 34) {
            try {
                ArrayList<Object> param = new ArrayList<>();
                param.add(ClassConstants.ComponentName);
                param.add(int.class);
                param.add(int.class);
                param.add(ClassConstants.IBinder);
                param.add(ClassConstants.ComponentName);
                param.add(ClassConstants.ActivityId);
                param.add(targetHook);
                XposedHelpers.findAndHookMethod(getTargetClass(), classLoader, getTargetMethod(), param.toArray());
                hooked = true;
                Log.i("Hooked updateActivityUsageStats (6 args + ActivityId, SDK >= 34)");
            } catch (Throwable ignored) {
                // ActivityId 签名不可用，尝试 Intent 签名
            }
        }

        // 候选 2: SDK 33 - 6 args + Intent
        if (!hooked && Build.VERSION.SDK_INT >= 33) {
            try {
                ArrayList<Object> param = new ArrayList<>();
                param.add(ClassConstants.ComponentName);
                param.add(int.class);
                param.add(int.class);
                param.add(ClassConstants.IBinder);
                param.add(ClassConstants.ComponentName);
                param.add(Intent.class);
                param.add(targetHook);
                XposedHelpers.findAndHookMethod(getTargetClass(), classLoader, getTargetMethod(), param.toArray());
                hooked = true;
                Log.i("Hooked updateActivityUsageStats (6 args + Intent, SDK >= 33)");
            } catch (Throwable ignored) {
                // Intent 签名不可用，回退到 5 args
            }
        }

        // 候选 3: SDK <= 32 - 5 args（默认签名）
        if (!hooked) {
            try {
                ArrayList<Object> param = new ArrayList<>(Arrays.asList(getTargetParam()));
                param.add(targetHook);
                XposedHelpers.findAndHookMethod(getTargetClass(), classLoader, getTargetMethod(), param.toArray());
                hooked = true;
                Log.i("Hooked updateActivityUsageStats (5 args, SDK <= 32)");
            } catch (Throwable throwable) {
                onError(throwable);
                return;
            }
        }

        if (hooked) {
            onSuccess();
        }
    }

    @Override
    public XC_MethodHook getTargetHook() {
        return new AbstractMethodHook() {
            @Override
            protected void beforeMethod(MethodHookParam param) throws Throwable {
                // v0.9.10 port: 启动后首次 Activity 切换触发 R4 refreeze
                if (sRefrozen.compareAndSet(false, true)) {
                    freezerHandler.refreezeAll();
                }

                // 获取方法参数
                Object[] args = param.args;

                // 获取切换事件
                int event = (int) args[2];
                if (event != ACTIVITY_PAUSED && event != ACTIVITY_RESUMED) {
                    return;
                }

                // 本次事件用户
                int userId = (int) args[1];
                // 本次事件包名
                String packageName = ((ComponentName) args[0]).getPackageName();
                if (packageName == null) {
                    return;
                }

                // 忽略系统框架
                if (packageName.equals("android")) {
                    Log.d("android(" + memData.getLastAppInfo().getPackageName() + ") -> ignored");
                    return;
                }

                // 当前事件应用
                AppInfo eventTo = AppInfo.getInstance(userId, packageName);

                // 本次等于上次 即无变化 不处理
                if (eventTo.equals(memData.getLastAppInfo())) {
                    // Log.d(eventTo.getKey() + " activity changed");
                    return;
                }


                // 切换前的包名等于上次包名
                AppInfo eventFrom = memData.getLastAppInfo();
                // 重新设置上次包名为切换后的包名 下次用
                memData.setLastAppInfo(eventTo);

                // 是否解冻
                boolean handleTo = memData.isTargetApp(eventTo.getPackageName()) || memData.getFreezerAppSet().contains(eventTo.getKey());
                // 是否冻结
                boolean handleFrom = memData.isTargetApp(eventFrom.getPackageName());
                Log.d(eventFrom.getKey() + covertHandle(handleFrom) + " -> " + eventTo.getKey() + covertHandle(handleTo));
                // 执行进入前台
                freezerHandler.onResume(handleTo, eventTo);
                if (memData.getDirectApps().contains(eventFrom.getPackageName())) {
                    // 执行进入后台
                    freezerHandler.onPause(handleFrom, eventFrom);
                } else {
                    freezerHandler.onPause(handleFrom, eventFrom, 3000);
                }

            }
        };
    }


    public String covertHandle(boolean handle) {
        return "(" + (handle ? "handle" : "ignore") + ")";
    }


}
