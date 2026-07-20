package cn.myflv.noactive.core.hook;

import android.os.Build;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

import cn.myflv.noactive.constant.ClassConstants;
import cn.myflv.noactive.constant.FieldConstants;
import cn.myflv.noactive.constant.MethodConstants;
import cn.myflv.noactive.core.HandleHook;
import cn.myflv.noactive.core.entity.AppInfo;
import cn.myflv.noactive.core.entity.MemData;
import cn.myflv.noactive.core.handler.FreezerHandler;
import cn.myflv.noactive.core.hook.base.AbstractMethodHook;
import cn.myflv.noactive.core.hook.base.MethodHook;
import cn.myflv.noactive.core.server.BroadcastFilter;
import cn.myflv.noactive.core.server.BroadcastRecord;
import cn.myflv.noactive.core.server.ProcessRecord;
import cn.myflv.noactive.core.server.ReceiverList;
import cn.myflv.noactive.core.utils.Log;
import cn.myflv.noactive.utils.ReflectionUtils;
import io.github.libxposed.api.XposedInterface;

/**
 * 广播分发Hook.
 * <p>
 * v0.9.10 port: SDK 34+ BroadcastQueueImpl 使用 dispatchReceivers 替代 deliverToRegisteredReceiverLocked，
 * 通过重写 hook() 实现多候选签名依次尝试。
 * <p>
 * API 102: 用 Java 反射 + {@code HandleHook.getInstance().hook(method).intercept(hooker)}
 * 替代 {@code XposedHelpers.findAndHookMethod}；
 * 用 {@link ReflectionUtils} 替代 {@code XposedHelpers.getObjectField/setObjectField}；
 * 用 {@link ThreadLocal} extraMap 替代 {@code MethodHookParam.setObjectExtra/getObjectExtra}；
 * 用 {@code chain.getArg(N)} 替代 {@code param.args[N]}.
 */
public class BroadcastDeliverHook extends MethodHook {
    /**
     * 旧模式：SDK < 34 使用 BroadcastQueue.deliverToRegisteredReceiverLocked.
     */
    public static final int MODE_LEGACY = 0;
    /**
     * 新模式：SDK 34+ 使用 BroadcastQueueImpl.dispatchReceivers.
     */
    public static final int MODE_DISPATCH = 1;

    /**
     * 内存数据.
     */
    private final MemData memData;
    /**
     * 当前生效的模式（hook 成功后确定）.
     */
    private int mode = MODE_LEGACY;

    /**
     * API 102: 替代 MethodHookParam.setObjectExtra/getObjectExtra.
     * <p>
     * 每个线程独立存储 extra，beforeMethod 写入，afterMethod 读取后清理。
     * 在 beforeMethod 开始时先 clear，防止 chain.proceed() 抛异常后残留。
     */
    private final ThreadLocal<Map<String, Object>> extraMap = ThreadLocal.withInitial(HashMap::new);

    public BroadcastDeliverHook(ClassLoader classLoader, MemData memData) {
        super(classLoader);
        this.memData = memData;
    }

    @Override
    public String getTargetClass() {
        return ClassConstants.BroadcastQueue;
    }

    @Override
    public String getTargetMethod() {
        return MethodConstants.deliverToRegisteredReceiverLocked;
    }

    @Override
    public Object[] getTargetParam() {
        return new Object[]{ClassConstants.BroadcastRecord, ClassConstants.BroadcastFilter, boolean.class, int.class};
    }

    /**
     * v0.9.10 port: 按 SDK 版本选择对应的 Hook 签名.
     * <p>
     * API 102 现代模块下：
     * <ul>
     *   <li>SDK &lt; 34: 仅使用 legacy 模式（BroadcastQueue.deliverToRegisteredReceiverLocked 4 args）</li>
     *   <li>SDK &gt;= 34: 仅使用 dispatch 模式（BroadcastQueueImpl.dispatchReceivers 3 args），
     *       不回退 legacy——SDK 34+ 上 deliverToRegisteredReceiverLocked 签名已变，
     *       回退必然导致 extractBroadcastFilter 拿到错误类型对象（Bug 4 根因）</li>
     * </ul>
     */
    @Override
    public void hook() {
        int minVersion = getMinVersion();
        if (minVersion != ANY_VERSION && Build.VERSION.SDK_INT < minVersion) {
            return;
        }

        XposedInterface.Hooker targetHook = getTargetHook();

        // SDK 34+：仅尝试 dispatch 模式
        if (Build.VERSION.SDK_INT >= 34) {
            String[] candidateClasses = {
                ClassConstants.BroadcastQueueImpl,
                ClassConstants.BroadcastQueueModernImpl,
                ClassConstants.BroadcastQueue
            };
            for (String className : candidateClasses) {
                try {
                    Class<?>[] paramTypes = new Class<?>[]{
                            Class.forName(ClassConstants.BroadcastProcessQueue, false, classLoader),
                            Class.forName(ClassConstants.BroadcastRecord, false, classLoader),
                            int.class
                    };
                    Class<?> clazz = Class.forName(className, false, classLoader);
                    Method method = clazz.getDeclaredMethod(MethodConstants.dispatchReceivers, paramTypes);
                    method.setAccessible(true);
                    HandleHook.getInstance().hook(method).intercept(targetHook);
                    mode = MODE_DISPATCH;
                    Log.i("Hooked " + className + "." + MethodConstants.dispatchReceivers + " (SDK >= 34)");
                    onSuccess();
                    return;
                } catch (Throwable ignored) {
                    // 此候选类不可用，尝试下一个
                }
            }
            onError(new NoSuchMethodError("dispatchReceivers not available on any candidate class (SDK >= 34)"));
            return;
        }

        // SDK < 34：仅使用 legacy 模式
        try {
            Class<?> clazz = Class.forName(getTargetClass(), false, classLoader);
            Class<?>[] paramTypes = resolveParamTypes(getTargetParam());
            Method method = clazz.getDeclaredMethod(getTargetMethod(), paramTypes);
            method.setAccessible(true);
            HandleHook.getInstance().hook(method).intercept(targetHook);
            mode = MODE_LEGACY;
            onSuccess();
        } catch (Throwable throwable) {
            onError(throwable);
        }
    }

    /**
     * 把 getTargetParam() 返回的 Object[]（String 类名或 Class<?>）解析为 Class<?>[].
     */
    private Class<?>[] resolveParamTypes(Object[] paramTypesObj) throws ClassNotFoundException {
        Class<?>[] paramTypes = new Class<?>[paramTypesObj.length];
        for (int i = 0; i < paramTypesObj.length; i++) {
            Object paramType = paramTypesObj[i];
            if (paramType instanceof Class) {
                paramTypes[i] = (Class<?>) paramType;
            } else if (paramType instanceof String) {
                paramTypes[i] = Class.forName((String) paramType, false, classLoader);
            } else {
                throw new IllegalArgumentException("Unsupported param type: " + paramType);
            }
        }
        return paramTypes;
    }

    @Override
    public XposedInterface.Hooker getTargetHook() {
        return new AbstractMethodHook() {
            @Override
            protected void beforeMethod(XposedInterface.Chain chain) throws Throwable {
                // 清理上次的 extra（防止 chain.proceed() 抛异常后 extraMap 残留）
                extraMap.get().clear();

                Object filterObj = extractBroadcastFilter(chain);
                if (filterObj == null) {
                    return;
                }
                BroadcastFilter broadcastFilter = new BroadcastFilter(filterObj);
                ReceiverList receiverList = broadcastFilter.getReceiverList();
                // 如果广播为空就不处理
                if (receiverList == null) {
                    return;
                }
                ProcessRecord processRecord = receiverList.getProcessRecord();
                // 如果进程或者应用信息为空就不处理
                if (processRecord == null) {
                    return;
                }

                // 不是目标进程就不处理
                if (!memData.isTargetProcess(processRecord.getUserId(), processRecord)) {
                    return;
                }

                AppInfo appInfo = AppInfo.getInstance(processRecord.getUserId(), processRecord.getPackageName());

                // 不是冻结APP就不处理
                if (!memData.getFreezerAppSet().contains(appInfo.getKey())) {
                    // 意味着广播执行
                    broadcastStart(chain, appInfo);
                    return;
                }

                // v0.9.10 port fix (MAJOR-10/12): R4 窗口期守护
                // 启动后 refreezeAll 完成前，freezerAppSet 已加载 background.conf 的 key
                // 但实际进程尚未物理冻结，此时不应清空广播（避免开机后首批广播丢失）
                if (!FreezerHandler.isR4Completed()) {
                    broadcastStart(chain, appInfo);
                    return;
                }

                // 暂存
                Object app = processRecord.getProcessRecord();
                setObjectExtra(FieldConstants.app, app);
                Log.d(processRecord.getProcessNameWithUser() + " clear broadcast");
                // 清楚广播
                receiverList.clear();
            }

            @Override
            protected Object afterMethod(XposedInterface.Chain chain, Object result) throws Throwable {
                // 恢复被修改的参数
                restore(chain);
                // 广播结束
                broadcastFinish(chain);
                return result;
            }
        };
    }

    /**
     * v0.9.10 port: 按当前模式从参数中提取 BroadcastFilter 对象.
     * <p>
     * MODE_LEGACY: arg(1) 直接是 BroadcastFilter.
     * MODE_DISPATCH: arg(1) 是 BroadcastRecord，arg(2) 是 receiverIndex，需用 wrapper 取出 receiver.
     * <p>
     * Bug 4 fix: SDK 34+ 上即便回退到 legacy 模式，deliverToRegisteredReceiverLocked
     * 第 2 个参数可能已不是 BroadcastFilter（OEM/SDK 改签名）。这里对返回对象做严格类型校验，
     * 防止把 BroadcastRecord 等其他类型误当 BroadcastFilter 处理导致 receiverList 反射失败刷屏。
     */
    private Object extractBroadcastFilter(XposedInterface.Chain chain) {
        if (mode != MODE_DISPATCH) {
            // legacy 模式：args = [BroadcastRecord, BroadcastFilter, boolean, int]
            Object filterObj = chain.getArg(1);
            if (filterObj == null) {
                return null;
            }
            // 类型校验：必须是 BroadcastFilter 或其子类，否则跳过
            if (!ClassConstants.BroadcastFilter.equals(filterObj.getClass().getName())) {
                return null;
            }
            return filterObj;
        }
        // dispatch 模式：args = [BroadcastProcessQueue, BroadcastRecord, int receiverIndex]
        Object recordObj = chain.getArg(1);
        if (recordObj == null) {
            return null;
        }
        Object indexObj = chain.getArg(2);
        if (!(indexObj instanceof Integer)) {
            return null;
        }
        int receiverIndex = (Integer) indexObj;
        Object receiver = new BroadcastRecord(recordObj).getReceiver(receiverIndex);
        if (receiver == null) {
            return null;
        }
        // dispatch 模式下 receiver 可能是 BroadcastFilter 或 ResolveInfo，仅处理 BroadcastFilter
        if (!ClassConstants.BroadcastFilter.equals(receiver.getClass().getName())) {
            return null;
        }
        return receiver;
    }

    /**
     * 广播开始执行
     *
     * @param appInfo 包名
     */
    private void broadcastStart(XposedInterface.Chain chain, AppInfo appInfo) {
        memData.setBroadcastApp(appInfo);
        setObjectExtra(FieldConstants.packageName, appInfo.getPackageName());
        // Log.d(packageName + " broadcast executing start");
    }

    /**
     * 广播结束执行
     */
    private void broadcastFinish(XposedInterface.Chain chain) {
        Object obj = getObjectExtra(FieldConstants.packageName);
        if (obj == null) {
            return;
        }
        memData.setBroadcastApp(null);
    }

    /**
     * 恢复被修改的参数
     */
    private void restore(XposedInterface.Chain chain) {
        // 获取进程
        Object app = getObjectExtra(FieldConstants.app);
        if (app == null) {
            return;
        }

        Object filterObj = extractBroadcastFilter(chain);
        if (filterObj == null) {
            return;
        }
        Object receiverList = ReflectionUtils.getObjectField(filterObj, FieldConstants.receiverList);
        if (receiverList == null) {
            return;
        }
        // 还原修改
        ReflectionUtils.setObjectField(receiverList, FieldConstants.app, app);
    }

    /**
     * API 102: 替代 MethodHookParam.setObjectExtra.
     */
    private void setObjectExtra(String key, Object value) {
        extraMap.get().put(key, value);
    }

    /**
     * API 102: 替代 MethodHookParam.getObjectExtra.
     */
    private Object getObjectExtra(String key) {
        return extraMap.get().get(key);
    }

    @Override
    public int getMinVersion() {
        return ANY_VERSION;
    }

    @Override
    public String successLog() {
        return "Listen broadcast deliver (" + (mode == MODE_DISPATCH ? "dispatch" : "legacy") + ")";
    }

}
