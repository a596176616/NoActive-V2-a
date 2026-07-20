package cn.myflv.noactive.core.server;


import android.app.ActivityManager;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Build;

import cn.myflv.noactive.constant.FieldConstants;
import cn.myflv.noactive.constant.MethodConstants;
import cn.myflv.noactive.core.entity.AppInfo;
import cn.myflv.noactive.core.utils.Log;
import cn.myflv.noactive.utils.ReflectionUtils;
import lombok.Data;

@Data
public class ActivityManagerService {
    public final static int MAIN_USER = 0;
    private static final int STANDBY_BUCKET_RARE = 40;
    private static final int STANDBY_BUCKET_NEVER = 50;
    private final Object activityManagerService;
    private final ProcessList processList;
    private final Context context;

    public ActivityManagerService(Object activityManagerService) {
        this.activityManagerService = activityManagerService;
        this.processList = new ProcessList(ReflectionUtils.getObjectField(activityManagerService, FieldConstants.mProcessList));
        this.context = (Context) ReflectionUtils.getObjectField(activityManagerService, FieldConstants.mContext);
    }

    public boolean isForegroundApp(AppInfo appInfo) {
        String packageName = appInfo.getPackageName();
        Integer userId = appInfo.getUserId();
        ApplicationInfo applicationInfo = getApplicationInfo(userId, packageName);
        if (applicationInfo == null) {
            return true;
        }
        int uid = applicationInfo.uid;
        // Bug 8d 真因：Android 16 (SDK 36) + HyperOS 上反射调用 private
        // ActivityManagerService.isAppForeground(int) 会被拦截：
        //   - setAccessible(true) 不抛异常但 invoke 仍抛
        //     IllegalAccessException("cannot access private method...")
        //   - 推测由 MIUI android.hardware.Cheeck 加固机制拦截
        //     (与 DeviceIdleController.removeWhiteList 失败同源)
        // 修复：改为直接走 mActiveUids 路径，等价于 AOSP isAppForeground(int) 实现：
        //   private boolean isAppForeground(int uid) {
        //     UidRecord uidRec = mProcessList.mActiveUids.get(uid);
        //     if (uidRec == null || uidRec.idle) return false;
        //     return uidRec.getCurProcState() <= PROCESS_STATE_IMPORTANT_FOREGROUND;
        //   }
        // 这条路径在 isTopApp 中已验证可用（同样用 mActiveUids + isIdle + getCurProcState）。
        try {
            synchronized (getLock()) {
                Object mProcessList = ReflectionUtils.getObjectField(activityManagerService, FieldConstants.mProcessList);
                Object mActiveUids = ReflectionUtils.getObjectField(mProcessList, FieldConstants.mActiveUids);
                Object uidRec = ReflectionUtils.callMethod(mActiveUids, MethodConstants.get, uid);
                if (uidRec == null) {
                    return false;
                }
                boolean idle;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    idle = (boolean) ReflectionUtils.callMethod(uidRec, MethodConstants.isIdle);
                } else {
                    idle = ReflectionUtils.getBooleanField(uidRec, FieldConstants.idle);
                }
                if (idle) {
                    return false;
                }
                int curProcState = (int) ReflectionUtils.callMethod(uidRec, MethodConstants.getCurProcState);
                int PROCESS_STATE_IMPORTANT_FOREGROUND = ReflectionUtils.getStaticIntField(ActivityManager.class, FieldConstants.PROCESS_STATE_IMPORTANT_FOREGROUND);
                return curProcState <= PROCESS_STATE_IMPORTANT_FOREGROUND;
            }
        } catch (Throwable throwable) {
            Log.e("isForegroundApp fallback failed: " + throwable.getMessage());
        }
        return true;
    }


    public boolean isTopApp(AppInfo appInfo) {
        int userId = appInfo.getUserId();
        String packageName = appInfo.getPackageName();
        try {
            ApplicationInfo applicationInfo = getApplicationInfo(userId, packageName);
            if (applicationInfo == null) {
                return true;
            }
            int uid = applicationInfo.uid;
            synchronized (getLock()) {
                Object mProcessList = ReflectionUtils.getObjectField(activityManagerService, FieldConstants.mProcessList);
                Object mActiveUids = ReflectionUtils.getObjectField(mProcessList, FieldConstants.mActiveUids);
                Object uidRec = ReflectionUtils.callMethod(mActiveUids, MethodConstants.get, uid);
                if (uidRec == null) {
                    return false;
                }
                boolean idle;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    idle = (boolean) ReflectionUtils.callMethod(uidRec, MethodConstants.isIdle);
                } else {
                    idle = ReflectionUtils.getBooleanField(uidRec, FieldConstants.idle);
                }
                if (idle) {
                    return false;
                }
                int curProcState = (int) ReflectionUtils.callMethod(uidRec, MethodConstants.getCurProcState);
                int PROCESS_STATE_BOUND_TOP = ReflectionUtils.getStaticIntField(ActivityManager.class, FieldConstants.PROCESS_STATE_BOUND_TOP);
                return curProcState <= PROCESS_STATE_BOUND_TOP;
            }
        } catch (Throwable throwable) {
            Log.e("isAppTop", throwable);
        }
        return true;
    }

    public Object getLock() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return ReflectionUtils.getObjectField(activityManagerService, FieldConstants.mProcLock);
        } else {
            return activityManagerService;
        }
    }


    public boolean isSystem(String packageName) {
        ApplicationInfo applicationInfo = getApplicationInfo(MAIN_USER, packageName);
        if (applicationInfo == null) {
            return true;
        }
        return (applicationInfo.flags & (ApplicationInfo.FLAG_SYSTEM | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0;
    }

    public boolean isImportantSystemApp(String packageName) {
        ApplicationInfo applicationInfo = getApplicationInfo(MAIN_USER, packageName);
        if (applicationInfo == null) {
            return true;
        }
        return applicationInfo.uid < 10000;
    }

    public ApplicationInfo getApplicationInfo(AppInfo appInfo) {
        return getApplicationInfo(appInfo.getUserId(), appInfo.getPackageName());
    }

    public ApplicationInfo getApplicationInfo(int userId, String packageName) {
        try {
            PackageManager packageManager = context.getPackageManager();
            Object applicationInfoAsUser = ReflectionUtils.callMethod(packageManager, MethodConstants.getApplicationInfoAsUser, packageName, PackageManager.MATCH_UNINSTALLED_PACKAGES, userId);
            if (applicationInfoAsUser == null) {
                return null;
            }
            return (ApplicationInfo) applicationInfoAsUser;
        } catch (Throwable throwable) {
            Log.w(packageName + " getApplicationInfo", throwable);
        }
        return null;
    }

    public void killApp(String packageName) {
        ReflectionUtils.callMethod(activityManagerService, MethodConstants.forceStopPackage, packageName, MAIN_USER);
        Log.d(packageName + " was killed");
    }

    public String getNameForUid(int uid) {
        PackageManager packageManager = context.getPackageManager();
        return packageManager.getNameForUid(uid);
    }

}
