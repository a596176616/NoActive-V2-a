package cn.myflv.noactive.core.handler;

import android.content.pm.ApplicationInfo;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import cn.myflv.noactive.constant.ClassConstants;
import cn.myflv.noactive.constant.MethodConstants;
import cn.myflv.noactive.core.entity.AppInfo;
import cn.myflv.noactive.core.entity.MemData;
import cn.myflv.noactive.core.server.ProcessRecord;
import cn.myflv.noactive.core.utils.FreezeUtils;
import cn.myflv.noactive.core.utils.FreezerConfig;
import cn.myflv.noactive.core.utils.Log;
import cn.myflv.noactive.core.utils.ThreadUtils;
import de.robv.android.xposed.XposedHelpers;

public class FreezerHandler {
    /**
     * Binder休眠.
     */
    private final static int BINDER_IDLE = 0;
    private final ClassLoader classLoader;
    private final MemData memData;
    private final FreezeUtils freezeUtils;

    /**
     * v0.9.10 port fix (MAJOR-10/12): R4 完成标志.
     * <p>
     * 启动后 refreezeAll() 执行完毕置 true。在 r4Completed=false 期间：
     * - freezerAppSet 已加载 background.conf 的 key
     * - 但实际进程尚未物理冻结
     * - BroadcastDeliverHook 应跳过 receiverList.clear()，避免误清空开机后首批广播
     */
    private static final AtomicBoolean r4Completed = new AtomicBoolean(false);

    /**
     * R4 是否已完成（供 BroadcastDeliverHook 在清空广播前守护判断）.
     */
    public static boolean isR4Completed() {
        return r4Completed.get();
    }

    public FreezerHandler(ClassLoader classLoader, MemData memData, FreezeUtils freezeUtils) {
        this.classLoader = classLoader;
        this.memData = memData;
        this.freezeUtils = freezeUtils;
        if (FreezerConfig.isConfigOn(FreezerConfig.BootFreeze)) {
            enableBootFreeze();
        }
        if (FreezerConfig.isConfigOn(FreezerConfig.IntervalUnfreeze)) {
            enableIntervalUnfreeze();
        }
        if (FreezerConfig.isConfigOn(FreezerConfig.IntervalFreeze)) {
            enableIntervalFreeze();
        }
    }

    /**
     * 开启开机冻结
     */
    public void enableBootFreeze() {
        ThreadUtils.scheduleDelay(() -> {
            // v0.9.10 port fix (MINOR-23): 防御 activityManagerService 未就绪时 NPE
            if (memData.getActivityManagerService() == null) {
                Log.w("Boot freeze: activityManagerService not ready, skip");
                return;
            }
            Log.i("Boot freeze start");
            // 获取包名分组进程
            Map<String, List<ProcessRecord>> processMap = memData.getActivityManagerService().getProcessList().getProcessMap();
            // 冻结的APP
            Set<String> frozenApps = new HashSet<>();
            // 遍历正在运行的进程
            for (String key : processMap.keySet()) {
                AppInfo appInfo = AppInfo.getInstance(key);
                ThreadUtils.runWithLock(appInfo.getKey(), () -> {
                    // 获取应用进程
                    List<ProcessRecord> processRecords = processMap.get(appInfo.getPackageName());
                    if (processRecords == null) {
                        return;
                    }
                    // 冻结
                    for (ProcessRecord processRecord : processRecords) {
                        if (memData.isTargetProcess(appInfo.getUserId(), processRecord)) {
                            freezeUtils.freezer(processRecord);
                            frozenApps.add(appInfo.getKey());
                        }
                    }
                });
            }
            Log.d("Frozen app list: " + frozenApps);
            // v0.9.10 port fix (MINOR-13): 逐 key 添加并判断是否新增，避免 background.conf 重复行
            for (String key : frozenApps) {
                if (memData.getFreezerAppSet().add(key)) {
                    FreezerConfig.appendBackground(key);
                }
            }
        }, Integer.parseInt(FreezerConfig.getString(FreezerConfig.BootFreezeDelay, "1")));
        Log.i("Boot freeze");
    }

    /**
     * 开启定时冻结
     */
    public void enableIntervalFreeze() {
        ThreadUtils.scheduleInterval(() -> {
            // v0.9.10 port fix (MINOR-23): 防御 activityManagerService 未就绪时 NPE
            if (memData.getActivityManagerService() == null) {
                Log.w("Interval freeze: activityManagerService not ready, skip");
                return;
            }
            Log.i("Interval freeze start");
            // 获取包名分组进程
            Map<String, List<ProcessRecord>> processMap = memData.getActivityManagerService().getProcessList().getProcessMap();
            // 冻结的APP
            Set<String> frozenApps = new HashSet<>();
            // 遍历正在运行的进程
            for (String key : processMap.keySet()) {
                AppInfo appInfo = AppInfo.getInstance(key);
                ThreadUtils.runWithLock(appInfo.getKey(), () -> {
                    // 再次检查是否被冻结
                    if (!memData.getFreezerAppSet().contains(key)) {
                        return;
                    }
                    // 获取应用进程
                    List<ProcessRecord> processRecords = processMap.get(appInfo.getPackageName());
                    if (processRecords == null) {
                        return;
                    }
                    // 冻结
                    for (ProcessRecord processRecord : processRecords) {
                        if (memData.isTargetProcess(appInfo.getUserId(), processRecord)) {
                            freezeUtils.freezer(processRecord);
                            frozenApps.add(appInfo.getKey());
                        }
                    }
                });
            }
            Log.d("Frozen app list: " + frozenApps);
            // v0.9.10 port fix (MINOR-13): 逐 key 添加并判断是否新增，避免 background.conf 重复行
            for (String key : frozenApps) {
                if (memData.getFreezerAppSet().add(key)) {
                    FreezerConfig.appendBackground(key);
                }
            }
        }, Integer.parseInt(FreezerConfig.getString(FreezerConfig.IntervalFreezeDelay, "1")));
        Log.i("Interval freeze");
    }

    /**
     * 定时轮番解冻
     */
    public void enableIntervalUnfreeze() {
        ThreadUtils.scheduleInterval(() -> {
            Log.i("Interval unfreeze start");
            // 遍历被冻结的进程
            for (String key : memData.getFreezerAppSet()) {
                AppInfo appInfo = AppInfo.getInstance(key);
                Log.d(appInfo.getKey() + " interval unfreeze start");
                // 解冻
                onResume(true, appInfo, true, () -> {
                    // 冻结
                    onPause(true, appInfo, 3000, () -> {
                        Log.d(appInfo.getKey() + " interval unfreeze finish");
                    });
                });
                // 结束循环
                // 相当于只解冻没有最久没有打开的 APP
                break;
            }
        }, Integer.parseInt(FreezerConfig.getString(FreezerConfig.IntervalUnfreezeDelay, "1")));
        Log.i("Interval unfreeze");
    }


    public void onResume(boolean handle, AppInfo appInfo) {
        onResume(handle, appInfo, false, null);
    }

    /**
     * APP切换至前台.
     *
     * @param appInfo 事件信息
     */
    public void onResume(boolean handle, AppInfo appInfo, boolean temporary, Runnable runnable) {
        // 不处理就跳过
        if (!handle) {
            return;
        }
        ThreadUtils.thawThread(appInfo.getKey(), () -> {
            ThreadUtils.safeRun(() -> {
                if (temporary) {
                    return;
                }
                // 获取包名
                String packageName = appInfo.getPackageName();
                // 白名单主进程跳过
                if (memData.getWhiteProcessList().contains(packageName)) {
                    return;
                }
                if (!memData.getSocketApps().contains(packageName)) {
                    // 恢复StandBy
                    memData.getAppStandbyController().forceIdleState(appInfo, false);
                }
            });
            // 获取目标进程
            List<ProcessRecord> targetProcessRecords = memData.getTargetProcessRecords(appInfo);
            // 解冻
            freezeUtils.unFreezer(targetProcessRecords);
            // 移除被冻结APP
            if (memData.getFreezerAppSet().remove(appInfo.getKey())) {
                // v0.9.10 port: 同步移除 background.conf 持久化记录
                FreezerConfig.removeBackground(appInfo.getKey());
            }
            if (Thread.currentThread().isInterrupted()) {
                Log.d(appInfo.getKey() + " event updated");
                return;
            }

            if (runnable != null) {
                runnable.run();
            }
        });
    }

    public void onPause(boolean handle, AppInfo appInfo) {
        onPause(handle, appInfo, 0, null);
    }

    public void onPause(boolean handle, AppInfo appInfo, long delay) {
        onPause(handle, appInfo, delay, null);
    }

    /**
     * APP切换至后台.
     *
     * @param appInfo 包名
     */
    public void onPause(boolean handle, AppInfo appInfo, long delay, Runnable runnable) {
        // 不处理就跳过
        if (!handle) {
            return;
        }
        ThreadUtils.newThread(appInfo.getKey(), () -> {
            // 如果是前台应用就不处理
            if (isAppForeground(appInfo)) {
                Log.d(appInfo.getKey() + " is in foreground");
                return;
            }
            // 获取目标进程
            List<ProcessRecord> targetProcessRecords = memData.getTargetProcessRecords(appInfo);
            // 如果目标进程为空就不处理
            if (targetProcessRecords.isEmpty()) {
                return;
            }
            // 后台应用添加包名
            if (memData.getFreezerAppSet().add(appInfo.getKey())) {
                // v0.9.10 port: 同步追加 background.conf 持久化记录
                FreezerConfig.appendBackground(appInfo.getKey());
            }
            // 等待应用未执行广播
            boolean broadcastIdle = memData.waitBroadcastIdle(appInfo);
            if (!broadcastIdle) {
                return;
            }
            // 等待 Binder 休眠
            boolean binderIdle = waitBinderIdle(appInfo);
            if (!binderIdle) {
                return;
            }
            ApplicationInfo applicationInfo = memData.getActivityManagerService().getApplicationInfo(appInfo);
            // 存放杀死进程
            List<ProcessRecord> killProcessList = new ArrayList<>();
            // 遍历目标进程
            for (ProcessRecord targetProcessRecord : targetProcessRecords) {
                if (Thread.currentThread().isInterrupted()) {
                    Log.d(appInfo.getKey() + " event updated");
                    return;
                }
                // 目标进程名
                String processName = targetProcessRecord.getProcessName();
                if (memData.getKillProcessList().contains(processName)) {
                    killProcessList.add(targetProcessRecord);
                } else {
                    // 冻结
                    freezeUtils.freezer(targetProcessRecord);
                }
            }
            if (Thread.currentThread().isInterrupted()) {
                Log.d(appInfo.getKey() + " event updated");
                return;
            }
            ThreadUtils.safeRun(() -> {
                // 如果白名单进程不包含主进程就释放唤醒锁
                if (memData.getWhiteProcessList().contains(appInfo.getPackageName())) {
                    return;
                }
                // 是否唤醒锁
                memData.getPowerManagerService().releaseWakeLocks(appInfo, applicationInfo.uid);
                // memData.getAlarmMangerService().remove(appInfo, applicationInfo.uid);
                if (!memData.getSocketApps().contains(appInfo.getPackageName())) {
                    memData.getAppStandbyController().forceIdleState(appInfo, true);
                    memData.getNetworkManagementService().socketDestroy(appInfo, applicationInfo);
                }
            });
            if (Thread.currentThread().isInterrupted()) {
                Log.d(appInfo.getKey() + " event updated");
                return;
            }
            ThreadUtils.safeRun(() -> {
                freezeUtils.kill(killProcessList);
            });
            if (runnable != null) {
                runnable.run();
            }
        }, delay);
    }

    /**
     * 应用是否前台.
     */
    public boolean isAppForeground(AppInfo appInfo) {
        // 获取包名
        String packageName = appInfo.getPackageName();
        if (memData.getTopApps().contains(packageName)) { // 如果设置后台级别为可见窗口
            // 判断是否可见窗口
            return memData.getActivityManagerService().isTopApp(appInfo);
        } else if (memData.getDirectApps().contains(packageName)) { // 如果设置了强制冻结
            // 直接认为不在前台
            return false;
        } else {
            // 默认判断是否有前台服务
            return memData.getActivityManagerService().isForegroundApp(appInfo);
        }
    }

    /**
     * 临时解冻.
     *
     * @param uid 应用ID
     */
    public void temporaryUnfreezeIfNeed(int uid, String reason) {
        if (uid < 10000) {
            return;
        }
        String key = memData.getActivityManagerService().getNameForUid(uid);
        if (key == null) {
            Log.w("uid  " + uid + "  not found");
            return;
        }
        if (!memData.getFreezerAppSet().contains(key)) {
            return;
        }
        AppInfo appInfo = AppInfo.getInstance(key);
        Log.i(appInfo.getKey() + " " + reason);
        onResume(true, appInfo, true, () -> {
            onPause(true, appInfo, 3000);
        });
    }

    /**
     * v0.9.10 port: R4 一次性重新冻结.
     * <p>
     * 启动后第一次发生 Activity 切换时触发：
     * 遍历 freezerAppSet 中的"应已冻结"应用，
     * <ul>
     *   <li>前台应用：从集合移除 + 移除 background.conf 记录</li>
     *   <li>后台应用：重新执行冻结，确保进程真正进入 cgroup freezer</li>
     * </ul>
     * 该方法只执行一次（由 ActivitySwitchHook.sRefrozen 控制）。
     */
    public void refreezeAll() {
        ThreadUtils.newThread(() -> {
            try {
                if (memData.getActivityManagerService() == null) {
                    Log.w("R4: activityManagerService not ready, skip");
                    return;
                }
                Map<String, List<ProcessRecord>> processMap = memData.getActivityManagerService().getProcessList().getProcessMap();
                // 拷贝快照避免并发修改
                List<String> keys = new ArrayList<>(memData.getFreezerAppSet());
                if (keys.isEmpty()) {
                    Log.i("R4: no background apps to refreeze");
                    return;
                }
                Log.i("R4: start refreeze " + keys.size() + " background apps");
                int refrozen = 0;
                int skipped = 0;
                int removed = 0;
                for (String key : keys) {
                    try {
                        AppInfo appInfo = AppInfo.getInstance(key);
                        // 前台应用：从冻结集合移除 + 同步 background.conf
                        if (isAppForeground(appInfo)) {
                            if (memData.getFreezerAppSet().remove(key)) {
                                FreezerConfig.removeBackground(key);
                                removed++;
                            }
                            Log.d("R4: " + key + " is foreground, removed from background set");
                            continue;
                        }
                        // 后台应用：重新冻结
                        List<ProcessRecord> processRecords = processMap.get(appInfo.getPackageName());
                        if (processRecords == null || processRecords.isEmpty()) {
                            Log.d("R4: " + key + " has no running processes, skip");
                            skipped++;
                            continue;
                        }
                        boolean refrozenThis = false;
                        for (ProcessRecord processRecord : processRecords) {
                            if (!memData.isTargetProcess(appInfo.getUserId(), processRecord)) {
                                continue;
                            }
                            try {
                                freezeUtils.freezer(processRecord);
                                Log.d("R4: refrozen " + processRecord.getProcessName() + " (pid=" + processRecord.getPid() + ")");
                                refrozenThis = true;
                            } catch (Throwable throwable) {
                                Log.e("R4: refreeze " + processRecord.getProcessName() + " failed", throwable);
                            }
                        }
                        if (refrozenThis) {
                            refrozen++;
                        } else {
                            skipped++;
                        }
                    } catch (Throwable throwable) {
                        Log.e("R4: refreeze " + key + " failed", throwable);
                    }
                }
                Log.i("R4: refreeze complete, refrozen=" + refrozen + " skipped=" + skipped + " removed=" + removed);
            } catch (Throwable throwable) {
                Log.e("R4: refreeze failed", throwable);
            } finally {
                // v0.9.10 port fix (MAJOR-10/12): 无论 R4 成功或失败都标记完成，结束窗口期
                // 避免BroadcastDeliverHook 永久跳过 clear() 导致冻结应用永久接收广播
                r4Completed.set(true);
                Log.i("R4: window period ended, broadcast guard released");
            }
        });
    }


    /**
     * Binder状态.
     *
     * @param uid 应用ID
     * @return [IDLE|BUSY]
     */
    public int binderState(int uid) {
        try {
            Class<?> GreezeManagerService = XposedHelpers.findClass(ClassConstants.GreezeManagerService, classLoader);
            return (int) XposedHelpers.callStaticMethod(GreezeManagerService, MethodConstants.nQueryBinder, uid);
        } catch (Throwable ignored) {
        }
        // 报错就返回已休眠，相当于这个功能不存在
        return BINDER_IDLE;
    }


    /**
     * 等待Binder休眠
     *
     * @param appInfo 包名
     */
    public boolean waitBinderIdle(AppInfo appInfo) {
        // 获取应用信息
        ApplicationInfo applicationInfo = memData.getActivityManagerService().getApplicationInfo(appInfo);
        if (applicationInfo == null) {
            return true;
        }
        // 重试次数
        int retry = 0;
        // 3次重试，如果不进休眠就直接冻结了
        while (binderState(applicationInfo.uid) != BINDER_IDLE && retry < 3) {
            Log.w(appInfo.getKey() + " binder busy");
            boolean sleep = ThreadUtils.sleep(1000);
            if (!sleep) {
                Log.d(appInfo.getKey() + " binder idle wait canceled");
                return false;
            }
            retry++;
        }
        Log.d(appInfo.getKey() + " binder idle");
        return true;
    }

}
