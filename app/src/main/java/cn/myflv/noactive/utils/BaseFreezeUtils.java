package cn.myflv.noactive.utils;

import android.os.Process;
import android.util.Log;

import com.topjohnwu.superuser.Shell;
import com.topjohnwu.superuser.io.SuFile;
import com.topjohnwu.superuser.io.SuFileInputStream;
import com.topjohnwu.superuser.io.SuFileOutputStream;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.lang.reflect.Method;

import cn.myflv.noactive.constant.ClassConstants;
import cn.myflv.noactive.constant.MethodConstants;


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
    /**
     * v0.9.10 port fix (CRITICAL-02): HyperOS cgroup v2 路径适配.
     * <p>
     * HyperOS 把 AOSP 标准的 /sys/fs/cgroup/apps/uid_X/pid_X/cgroup.freeze
     * 改成了 /sys/fs/cgroup/system/uid_X/pid_X/cgroup.freeze。
     * v0.9.10 反编译代码硬编码 apps 路径，在 HyperOS 上全部 ENOENT 失败。
     * V2 移植版补上 system 路径，并优先探测。
     */
    private static final String[] FREEZER_PATH_ENUM = {
            "/sys/fs/cgroup/system",  // HyperOS 路径（优先探测）
            "/sys/fs/cgroup/apps",    // AOSP 标准路径
            "/sys/fs/cgroup",         // 通用 fallback（让策略1 /proc 解析子路径）
            "/dev/freezer",
            "/dev/cg2_bpf"
    };
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
        // v0.9.10 port fix (CRITICAL-02): HyperOS fallback.
        // 所有探测路径都未命中时，默认走 V2 + /sys/fs/cgroup 根路径，
        // 让策略1（/proc/<pid>/cgroup）解析真实子路径。
        // 原 bug: 此处 commonV2=true 但 return false，导致走 V1 路径，V1 路径在 HyperOS 上也不存在。
        commonV2 = true;
        freezerPath = FREEZER_PATH_ENUM[2]; // "/sys/fs/cgroup"
        return commonV2;
    }

    private static boolean pathExist(boolean su, String path) {
        if (su) {
            return SuFile.open(path).exists();
        } else {
            return new File(path).exists();
        }
    }

    private static boolean writeNode(boolean su, String path, int val) {
        // F3A-055 fix: PrintWriter 必须在 finally 中 close，避免异常时 FD 泄露
        PrintWriter writer = null;
        try {
            writer = getWriter(su, path);
            writer.write(Integer.toString(val));
            return true;
        } catch (Exception e) {
            if (val == FREEZE_ACTION) {
                Log.e(TAG, "Freezer V1 failed: " + e.getMessage());
            }
        } finally {
            if (writer != null) {
                writer.close();
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
        // v6 fix: App Zygote 机制下 cgroup 路径不匹配，三策略解析真实路径
        // 策略1: 读 /proc/<pid>/cgroup 拿真实路径
        // 策略2: 扫描 uid_<uid>/pid_*/cgroup.procs 找匹配 pid
        // 策略3: 兜底硬编码（原直接拼路径）
        String path = resolveCgroupFreezePath(su, pid, uid);
        // F3A-055 fix: PrintWriter 必须在 finally 中 close，避免异常时 FD 泄露
        PrintWriter writer = null;
        try {
            writer = getWriter(su, path);
            if (action) {
                writer.write(Integer.toString(FREEZE_ACTION));
            } else {
                writer.write(Integer.toString(UNFREEZE_ACTION));
            }
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Freezer V2 failed: " + e.getMessage());
            // v0.9.10 port: V2 cgroup 写入失败时回退到系统 API Process.setProcessFrozen
            if (fallbackToApi(pid, uid, action)) {
                return true;
            }
            // v0.9.10 port fix (CRITICAL-03): API 也失败时最终 fallback 到 SIGSTOP/SIGCONT.
            // HyperOS 上 PowerMillet 开启会导致 Framework CachedAppOptimizer useFreezer=false，
            // Process.setProcessFrozen 抛 IllegalArgumentException("Invalid argument").
            // v0.9.10 反编译代码的 fallback 链：V2 写节点 → API → SIGSTOP，
            // 移植版之前丢了最后一环，本补丁恢复 SIGSTOP 兜底。
            return fallbackToSignal(pid, action);
        } finally {
            if (writer != null) {
                writer.close();
            }
        }
    }

    /**
     * v0.9.10 port fix (CRITICAL-03): SIGSTOP/SIGCONT 信号兜底冻结.
     * <p>
     * 当 V2 cgroup 写入和 Process.setProcessFrozen API 都失败时，
     * 用 Process.sendSignal(pid, SIG_STOP) 停止进程，
     * 用 Process.sendSignal(pid, SIG_CONT) 恢复进程。
     * 这是 v0.9.10 反编译代码 FreezeUtils.java 的最终 fallback，
     * 也是最不依赖厂商路径的方式。
     */
    private static boolean fallbackToSignal(int pid, boolean frozen) {
        try {
            int sig = frozen ? SIG_STOP : SIG_CONT;
            Process.sendSignal(pid, sig);
            Log.i(TAG, "Freezer fallback to SIG" + (frozen ? "STOP" : "CONT")
                    + " succeeded: pid=" + pid);
            return true;
        } catch (Throwable e) {
            Log.e(TAG, "Freezer SIG fallback failed: " + e.getMessage());
            return false;
        }
    }

    /**
     * v6: 三策略解析 cgroup.freeze 真实路径.
     * <p>
     * App Zygote 机制下，子进程的真实 cgroup 路径可能与父进程不一致，
     * 直接拼 {@code /sys/fs/cgroup/apps/uid_<uid>/pid_<pid>/cgroup.freeze} 常找不到节点。
     * 按以下顺序解析：
     * <ol>
     *   <li>策略1: 读 /proc/&lt;pid&gt;/cgroup 拿真实路径（最准确）</li>
     *   <li>策略2: 扫描 freezerPath/uid_xxx/pid_xxx/cgroup.procs 找匹配 pid</li>
     *   <li>策略3: 兜底硬编码（原直接拼路径，保留向后兼容）</li>
     * </ol>
     *
     * @return cgroup.freeze 完整路径
     */
    private static String resolveCgroupFreezePath(boolean su, int pid, int uid) {
        // 策略1: 读 /proc/<pid>/cgroup
        String path = resolveCgroupFreezePathByProc(su, pid, uid);
        if (path != null) {
            return path;
        }

        // 策略2: 扫描 pid_*/cgroup.procs
        path = resolveCgroupFreezePathByScan(su, pid, uid);
        if (path != null) {
            return path;
        }

        // 策略3: 兜底硬编码
        path = freezerPath + "/uid_" + uid + "/pid_" + pid + "/cgroup.freeze";
        Log.w(TAG, "Freezer V2 path resolution fallback to hardcoded: " + path);
        return path;
    }

    /**
     * v6 策略1: 读 /proc/&lt;pid&gt;/cgroup 拿真实路径.
     * <p>
     * cgroup v2 下 /proc/&lt;pid&gt;/cgroup 文件格式:
     * <pre>0::/uid_10356/pid_25139</pre>
     * 提取 :: 之后的子路径，拼接到 freezerPath + 子路径 + /cgroup.freeze.
     *
     * @return 成功返回完整路径；失败返回 null
     */
    private static String resolveCgroupFreezePathByProc(boolean su, int pid, int uid) {
        String procCgroupPath = "/proc/" + pid + "/cgroup";
        BufferedReader reader = getReader(su, procCgroupPath);
        if (reader == null) {
            return null;
        }
        try {
            String line;
            String subPath = null;
            while ((line = reader.readLine()) != null) {
                // cgroup v2 格式: 0::/uid_xxx/pid_xxx
                // cgroup v1 格式: 2:freezer:/path（含冒号，但不是 ::）
                int idx = line.indexOf("::");
                if (idx >= 0 && idx + 2 < line.length()) {
                    subPath = line.substring(idx + 2).trim();
                    break;
                }
            }
            reader.close();
            if (subPath == null || subPath.isEmpty() || "/".equals(subPath)) {
                return null;
            }
            // 去除开头的 /
            if (subPath.startsWith("/")) {
                subPath = subPath.substring(1);
            }
            String freezePath = freezerPath + "/" + subPath + "/cgroup.freeze";
            if (pathExist(su, freezePath)) {
                Log.d(TAG, "Freezer V2 path resolved via /proc: " + freezePath);
                return freezePath;
            }
            // /proc 拿到的路径在 freezerPath 下不存在，可能 freezerPath 不是真正的根。
            // 尝试直接用 subPath 拼接到 /sys/fs/cgroup
            String altPath = "/sys/fs/cgroup/" + subPath + "/cgroup.freeze";
            if (pathExist(su, altPath)) {
                Log.d(TAG, "Freezer V2 path resolved via /proc (alt root): " + altPath);
                return altPath;
            }
            return null;
        } catch (Exception e) {
            Log.d(TAG, "Read /proc/" + pid + "/cgroup failed: " + e.getMessage());
            return null;
        }
    }

    /**
     * v6 策略2: 扫描 uid_xxx/pid_xxx/cgroup.procs 找匹配 pid.
     * <p>
     * 遍历 freezerPath/uid_xxx/ 下所有 pid_xxx 目录，
     * 读取每个 pid_xxx/cgroup.procs 文件，找哪个文件包含目标 pid.
     *
     * @return 成功返回完整路径；失败返回 null
     */
    private static String resolveCgroupFreezePathByScan(boolean su, int pid, int uid) {
        String uidDir = freezerPath + "/uid_" + uid;
        if (!pathExist(su, uidDir)) {
            return null;
        }
        File uidDirFile = su ? SuFile.open(uidDir) : new File(uidDir);
        File[] pidDirs = uidDirFile.listFiles();
        if (pidDirs == null || pidDirs.length == 0) {
            return null;
        }
        String matchPath = null;
        for (File pidDir : pidDirs) {
            if (pidDir == null) {
                continue;
            }
            String name = pidDir.getName();
            if (!name.startsWith("pid_")) {
                continue;
            }
            String procsPath = pidDir.getAbsolutePath() + "/cgroup.procs";
            if (!pathExist(su, procsPath)) {
                continue;
            }
            BufferedReader reader = getReader(su, procsPath);
            if (reader == null) {
                continue;
            }
            try {
                String line;
                while ((line = reader.readLine()) != null) {
                    try {
                        if (Integer.parseInt(line.trim()) == pid) {
                            matchPath = pidDir.getAbsolutePath() + "/cgroup.freeze";
                            break;
                        }
                    } catch (NumberFormatException ignored) {
                    }
                }
            } catch (Exception e) {
                // 此 pid_* 目录读取失败，跳过
            } finally {
                try {
                    reader.close();
                } catch (Exception ignored) {
                }
            }
            if (matchPath != null) {
                break;
            }
        }
        if (matchPath != null) {
            Log.d(TAG, "Freezer V2 path resolved via scan: " + matchPath + " (pid in pid_*/cgroup.procs)");
        }
        return matchPath;
    }

    /**
     * 获取 BufferedReader（支持 su 和非 su 模式）.
     */
    private static BufferedReader getReader(boolean su, String path) {
        try {
            if (su) {
                return new BufferedReader(new InputStreamReader(SuFileInputStream.open(path)));
            } else {
                return new BufferedReader(new FileReader(path));
            }
        } catch (Exception e) {
            return null;
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
            // API 102: XposedHelpers.findClass + callStaticMethod → Class.forName + Method.invoke
            // 注意：局部变量重命名为 ProcessClass 避免遮蔽已 import 的 android.os.Process
            Class<?> ProcessClass = Class.forName(ClassConstants.Process, false, sClassLoader);
            Method setProcessFrozen = ProcessClass.getDeclaredMethod(MethodConstants.setProcessFrozen, int.class, int.class, boolean.class);
            setProcessFrozen.setAccessible(true);
            setProcessFrozen.invoke(null, pid, uid, frozen);
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
