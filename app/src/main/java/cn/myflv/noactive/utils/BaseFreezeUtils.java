package cn.myflv.noactive.utils;

import android.os.Process;
import android.util.Log;

import com.topjohnwu.superuser.Shell;
import com.topjohnwu.superuser.io.SuFile;
import com.topjohnwu.superuser.io.SuFileOutputStream;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.PrintWriter;

import cn.myflv.noactive.constant.ClassConstants;
import cn.myflv.noactive.constant.MethodConstants;
import de.robv.android.xposed.XposedHelpers;


public class BaseFreezeUtils {

    public final static int SIG_CONT = 18;
    public final static int SIG_STOP = 19;
    public final static int SIG_TSTP = 20;
    public final static int SIG_KILL = 9;
    private final static String TAG = "NoActive";
    private static final int FREEZE_ACTION = 1;
    private static final int UNFREEZE_ACTION = 0;

    private static final String V1_FREEZER_FROZEN_PORCS = "/sys/fs/cgroup/freezer/perf/frozen/cgroup.procs";
    private static final String V1_FREEZER_THAWED_PORCS = "/sys/fs/cgroup/freezer/perf/thawed/cgroup.procs";

    private static final String UID_ROOT = "/uid_0";
    private static final String UID_SYSTEM = "/uid_1000";
    private static final String FROZEN_PATH = "/frozen/cgroup.procs";
    private static final String UNFROZEN_PATH = "/unfrozen/cgroup.procs";
    private static final String[] FREEZER_PATH_ENUM = {"/sys/fs/cgroup", "/dev/freezer", "/dev/cg2_bpf"};
    private static Boolean commonV2 = null;
    private static String freezerPath = null;

    /**
     * v0.9.10 port: 静态持有 classLoader 用于 fallbackToApi.
     * <p>
     * 当 V2 cgroup 路径写入失败时（如 HyperOS 3.0 路径变更或权限问题），
     * 回退到系统 API Process.setProcessFrozen 来完成冻结/解冻。
     */
    private static ClassLoader sClassLoader = null;

    /**
     * v0.9.10 port: 初始化 classLoader，应在模块启动早期由 FreezeUtils 构造器调用.
     */
    public static void setClassLoader(ClassLoader classLoader) {
        sClassLoader = classLoader;
    }

    private synchronized static boolean isCommonV2(boolean su) {
        if (commonV2 != null) {
            return commonV2;
        }
        for (String path : FREEZER_PATH_ENUM) {
            if (pathExist(su, path + UID_ROOT) || pathExist(su, path + UID_SYSTEM)) {
                commonV2 = true;
                freezerPath = path;
                return commonV2;
            }
            if (pathExist(su, path + FROZEN_PATH) && pathExist(su, path + UNFROZEN_PATH)) {
                commonV2 = false;
                freezerPath = path;
                return commonV2;
            }
        }
        commonV2 = true;
        freezerPath = FREEZER_PATH_ENUM[0];
        return false;
    }

    private static boolean pathExist(boolean su, String path) {
        if (su) {
            return SuFile.open(path).exists();
        } else {
            return new File(path).exists();
        }
    }

    private static boolean writeNode(boolean su, String path, int val) {
        try {
            PrintWriter writer = getWriter(su, path);
            writer.write(Integer.toString(val));
            writer.close();
            return true;
        } catch (Exception e) {
            if (val == FREEZE_ACTION) {
                Log.e(TAG, "Freezer V1 failed: " + e.getMessage());
            }
        }
        return false;
    }

    public static boolean freezePid(boolean su, int pid) {
        return writeNode(su, V1_FREEZER_FROZEN_PORCS, pid);
    }

    public static boolean thawPid(boolean su, int pid) {
        return writeNode(su, V1_FREEZER_THAWED_PORCS, pid);
    }

    private static boolean setFreezeAction(boolean su, int pid, int uid, boolean action) {
        String path = freezerPath + "/uid_" + uid + "/pid_" + pid + "/cgroup.freeze";
        try {
            PrintWriter writer = getWriter(su, path);
            if (action) {
                writer.write(Integer.toString(FREEZE_ACTION));
            } else {
                writer.write(Integer.toString(UNFREEZE_ACTION));
            }
            writer.close();
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Freezer V2 failed: " + e.getMessage());
            // v0.9.10 port: V2 cgroup 写入失败时回退到系统 API Process.setProcessFrozen
            return fallbackToApi(pid, uid, action);
        }
    }

    /**
     * v0.9.10 port: V2 写入失败时回退到系统 API (Process.setProcessFrozen).
     * <p>
     * 该方法通过反射调用 android.os.Process.setProcessFrozen，
     * 需要 classLoader 已通过 {@link #setClassLoader(ClassLoader)} 初始化。
     *
     * @return true 表示 API 调用成功；false 表示 classLoader 未初始化或 API 调用失败
     */
    private static boolean fallbackToApi(int pid, int uid, boolean frozen) {
        if (sClassLoader == null) {
            Log.e(TAG, "Freezer API fallback skipped: classLoader not initialized");
            return false;
        }
        try {
            Class<?> Process = XposedHelpers.findClass(ClassConstants.Process, sClassLoader);
            XposedHelpers.callStaticMethod(Process, MethodConstants.setProcessFrozen, pid, uid, frozen);
            Log.i(TAG, "Freezer V2 fallback to API succeeded: pid=" + pid + " uid=" + uid + " frozen=" + frozen);
            return true;
        } catch (Throwable e) {
            Log.e(TAG, "Freezer API fallback failed: " + e.getMessage());
            return false;
        }
    }

    public static boolean thawPid(boolean su, int pid, int uid) {
        if (isCommonV2(su)) {
            return setFreezeAction(su, pid, uid, false);
        }
        return writeNode(su, freezerPath + UNFROZEN_PATH, pid);
    }

    public static boolean freezePid(boolean su, int pid, int uid) {
        if (isCommonV2(su)) {
            return setFreezeAction(su, pid, uid, true);
        }
        return writeNode(su, freezerPath + FROZEN_PATH, pid);
    }

    public static boolean kill(boolean su, int sig, int pid) {
        try {
            if (su) {
                Shell.cmd("kill -" + sig + " " + pid).exec();
            } else {
                Process.sendSignal(pid, sig);
            }
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Kill " + sig + " failed: " + e.getMessage());
        }
        return false;
    }

    public static PrintWriter getWriter(boolean su, String path) throws FileNotFoundException {
        if (su) {
            return new PrintWriter(SuFileOutputStream.open(path));
        } else {
            return new PrintWriter(path);
        }
    }
}
