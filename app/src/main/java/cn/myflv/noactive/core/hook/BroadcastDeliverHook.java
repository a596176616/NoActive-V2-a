package cn.myflv.noactive.core.hook;

import android.os.Build;

import java.util.ArrayList;
import java.util.Arrays;

import cn.myflv.noactive.constant.ClassConstants;
import cn.myflv.noactive.constant.FieldConstants;
import cn.myflv.noactive.constant.MethodConstants;
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
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;

/**
 * 广播分发Hook.
 * <p>
 * v0.9.10 port: SDK 34+ BroadcastQueueImpl 使用 dispatchReceivers 替代 deliverToRegisteredReceiverLocked，
 * 通过重写 hook() 实现多候选签名依次尝试。
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
     * v0.9.10 port: 重写 hook() 实现"先 legacy 后 dispatch"双候选签名依次尝试.
     * <p>
     * SDK 34+ 上 BroadcastQueueImpl.dispatchReceivers 替代了 deliverToRegisteredReceiverLocked，
     * 但部分 OEM 可能仍保留旧方法。先尝试 legacy，失败再尝试 dispatch。
     */
    @Override
    public void hook() {
        int minVersion = getMinVersion();
        if (minVersion != ANY_VERSION && Build.VERSION.SDK_INT < minVersion) {
            return;
        }

        // 1. 先尝试 legacy 模式（BroadcastQueue.deliverToRegisteredReceiverLocked 4 args）
        try {
            ArrayList<Object> param = new ArrayList<>(Arrays.asList(getTargetParam()));
            param.add(getTargetHook());
            XposedHelpers.findAndHookMethod(getTargetClass(), classLoader, getTargetMethod(), param.toArray());
            mode = MODE_LEGACY;
            onSuccess();
            return;
        } catch (Throwable ignored) {
            // legacy 模式不可用，尝试 dispatch 模式
        }

        // 2. SDK 34+ 尝试 dispatch 模式（BroadcastQueueImpl.dispatchReceivers 3 args）
        if (Build.VERSION.SDK_INT >= 34) {
            // 候选类数组，依次尝试
            String[] candidateClasses = {
                ClassConstants.BroadcastQueueImpl,
                ClassConstants.BroadcastQueueModernImpl,
                ClassConstants.BroadcastQueue
            };
            for (String className : candidateClasses) {
                try {
                    ArrayList<Object> param = new ArrayList<>();
                    param.add(ClassConstants.BroadcastProcessQueue);
                    param.add(ClassConstants.BroadcastRecord);
                    param.add(int.class);
                    param.add(getTargetHook());
                    XposedHelpers.findAndHookMethod(className, classLoader, MethodConstants.dispatchReceivers, param.toArray());
                    mode = MODE_DISPATCH;
                    Log.i("Hooked " + className + "." + MethodConstants.dispatchReceivers + " (SDK >= 34)");
                    onSuccess();
                    return;
                } catch (Throwable ignored) {
                    // 此候选类不可用，尝试下一个
                }
            }
        }

        // 3. 全部失败
        onError(new NoSuchMethodError("Neither deliverToRegisteredReceiverLocked nor dispatchReceivers available"));
    }

    @Override
    public XC_MethodHook getTargetHook() {
        return new AbstractMethodHook() {
            @Override
            protected void beforeMethod(MethodHookParam param) throws Throwable {
                Object[] args = param.args;
                Object filterObj = extractBroadcastFilter(args);
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
                    broadcastStart(param, appInfo);
                    return;
                }

                // v0.9.10 port fix (MAJOR-10/12): R4 窗口期守护
                // 启动后 refreezeAll 完成前，freezerAppSet 已加载 background.conf 的 key
                // 但实际进程尚未物理冻结，此时不应清空广播（避免开机后首批广播丢失）
                if (!FreezerHandler.isR4Completed()) {
                    broadcastStart(param, appInfo);
                    return;
                }

                // 暂存
                Object app = processRecord.getProcessRecord();
                param.setObjectExtra(FieldConstants.app, app);
                Log.d(processRecord.getProcessNameWithUser() + " clear broadcast");
                // 清楚广播
                receiverList.clear();
            }

            @Override
            protected void afterMethod(MethodHookParam param) throws Throwable {
                // 恢复被修改的参数
                restore(param);
                // 广播结束
                broadcastFinish(param);
            }
        };
    }

    /**
     * v0.9.10 port: 按当前模式从参数中提取 BroadcastFilter 对象.
     * <p>
     * MODE_LEGACY: args[1] 直接是 BroadcastFilter.
     * MODE_DISPATCH: args[1] 是 BroadcastRecord，args[2] 是 receiverIndex，需用 wrapper 取出 receiver.
     */
    private Object extractBroadcastFilter(Object[] args) {
        if (args == null || args.length < 2 || args[1] == null) {
            return null;
        }
        if (mode != MODE_DISPATCH) {
            // legacy 模式：args = [BroadcastRecord, BroadcastFilter, boolean, int]
            return args[1];
        }
        // dispatch 模式：args = [BroadcastProcessQueue, BroadcastRecord, int receiverIndex]
        if (args.length < 3 || !(args[2] instanceof Integer)) {
            return null;
        }
        int receiverIndex = (Integer) args[2];
        Object receiver = new BroadcastRecord(args[1]).getReceiver(receiverIndex);
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
    private void broadcastStart(XC_MethodHook.MethodHookParam param, AppInfo appInfo) {
        memData.setBroadcastApp(appInfo);
        param.setObjectExtra(FieldConstants.packageName, appInfo.getPackageName());
        // Log.d(packageName + " broadcast executing start");
    }

    /**
     * 广播结束执行
     */
    private void broadcastFinish(XC_MethodHook.MethodHookParam param) {
        Object obj = param.getObjectExtra(FieldConstants.packageName);
        if (obj == null) {
            return;
        }
        memData.setBroadcastApp(null);
    }

    /**
     * 恢复被修改的参数
     */
    private void restore(XC_MethodHook.MethodHookParam param) {
        // 获取进程
        Object app = param.getObjectExtra(FieldConstants.app);
        if (app == null) {
            return;
        }

        Object[] args = param.args;
        Object filterObj = extractBroadcastFilter(args);
        if (filterObj == null) {
            return;
        }
        Object receiverList = XposedHelpers.getObjectField(filterObj, FieldConstants.receiverList);
        if (receiverList == null) {
            return;
        }
        // 还原修改
        XposedHelpers.setObjectField(receiverList, FieldConstants.app, app);
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
