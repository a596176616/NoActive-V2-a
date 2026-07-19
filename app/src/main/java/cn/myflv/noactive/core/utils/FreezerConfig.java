package cn.myflv.noactive.core.utils;

import android.os.Build;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import cn.myflv.noactive.constant.ClassConstants;
import cn.myflv.noactive.constant.MethodConstants;
import de.robv.android.xposed.XposedHelpers;

public class FreezerConfig {

    public final static String ConfigDir = "/data/system/NoActive";
    public final static String LogDir = ConfigDir + "/log";
    public final static String whiteAppConfig = "whiteApp.conf";
    public final static String topAppConfig = "topApp.conf";
    public final static String directAppConfig = "directApp.conf";
    public final static String socketAppConfig = "socketApp.conf";
    public final static String idleAppConfig = "idleApp.conf";
    public final static String blackSystemAppConfig = "blackSystemApp.conf";
    public final static String whiteProcessConfig = "whiteProcess.conf";
    public final static String killProcessConfig = "killProcess.conf";
    public final static String kill19 = "kill.19";
    public final static String kill20 = "kill.20";
    public final static String freezerV1 = "freezer.v1";
    public final static String freezerV2 = "freezer.v2";
    public final static String freezerApi = "freezer.api";
    public final static String API = "Api";
    public final static String V2 = "V2";
    public final static String V1 = "V1";
    public final static String lastLog = "last.log";
    public final static String currentLog = "current.log";
    public final static String Debug = "debug";
    public final static String IntervalUnfreeze = "interval.unfreeze";
    public final static String IntervalUnfreezeDelay = "interval.unfreeze.delay";
    public final static String IntervalFreeze = "interval.freeze";
    public final static String IntervalFreezeDelay = "interval.freeze.delay";
    public final static String BootFreeze = "boot.freeze";
    public final static String BootFreezeDelay = "boot.freeze.delay";
    public final static String SuExcute = "su.excute";
    // v0.9.10 port: 后台应用持久化文件，记录已冻结的应用 key（userId:packageName 格式）
    public final static String backgroundConf = "background.conf";
    public final static String[] listenConfig = {whiteAppConfig, whiteProcessConfig,
            killProcessConfig, blackSystemAppConfig, directAppConfig, topAppConfig, socketAppConfig, idleAppConfig};

    public static boolean isScheduledOn() {
        return isConfigOn(IntervalUnfreeze);
    }

    public static boolean isConfigOn(String configName) {
        File config = new File(ConfigDir, configName);
        return config.exists();
    }

    public static int getKillSignal() {
        if (isConfigOn(kill19)) {
            return 19;
        }
        if (isConfigOn(kill20)) {
            return 20;
        }
        return 19;
    }


    public static String getFreezerVersion(ClassLoader classLoader) {
        if (isConfigOn(freezerV2)) {
            return V2;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (isConfigOn(freezerApi)) {
                return API;
            }

        }
        if (isConfigOn(freezerV1)) {
            return V1;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (isAndroidApi(classLoader)) {
                return V2;
            }
        }
        return V1;
    }

    public static boolean isAndroidApi(ClassLoader classLoader) {
        // v0.9.10 port: SDK 34+ 上 isFreezerSupported 可能不存在或抛异常，失败时默认 V2 (cgroup v2 freezer)
        try {
            Class<?> CachedAppOptimizer = XposedHelpers.findClass(ClassConstants.CachedAppOptimizer, classLoader);
            return (boolean) XposedHelpers.callStaticMethod(CachedAppOptimizer, MethodConstants.isFreezerSupported);
        } catch (Throwable e) {
            Log.i("isFreezerSupported not available on SDK=" + Build.VERSION.SDK_INT + ", default to V2 (cgroup v2 freezer)");
            return true;
        }
    }

    public static boolean isXiaoMiV1(ClassLoader classLoader) {
        try {
            return XposedHelpers.findClassIfExists(ClassConstants.GreezeManagerService, classLoader) != null;
        } catch (Throwable ignored) {
        }
        return false;
    }


    public static boolean isUseKill() {
        return isConfigOn(kill19) || isConfigOn(kill20);
    }


    public static void checkAndInit() {
        File configDir = new File(ConfigDir);
        if (!configDir.exists()) {
            boolean mkdir = configDir.mkdir();
            if (!mkdir) {
                Log.xposedLog("NoActive(error) -> Config dir init failed");
                return;
            }
        }
        File logDir = new File(LogDir);
        if (!logDir.exists()) {
            boolean mkdir = logDir.mkdir();
            if (!mkdir) {
                Log.xposedLog("NoActive(error) -> Log dir init failed");
                return;
            }
        }
        for (String configName : listenConfig) {
            File config = new File(configDir, configName);
            if (!config.exists()) {
                createFile(config);
                Log.i("Init " + configName);
            }
        }
    }

    public static void cleanLog() {
        File source = new File(LogDir, currentLog);
        File dest = new File(LogDir, lastLog);
        moveFile(source, dest);
    }

    public static void moveFile(File source, File dest) {
        try {
            boolean delete = dest.delete();
            boolean renameTo = source.renameTo(dest);
        } catch (Exception ignored) {

        }
    }


    public static Set<String> get(String name) {
        Set<String> set = new HashSet<>();
        try {
            File file = new File(ConfigDir, name);
            BufferedReader bufferedReader = new BufferedReader(new FileReader(file));
            String line;
            while ((line = bufferedReader.readLine()) != null) {
                String finalLine = line.trim();
                if ("".equals(finalLine) || line.startsWith("#")) {
                    continue;
                }
                set.add(line.trim());
            }
            bufferedReader.close();
        } catch (FileNotFoundException fileNotFoundException) {
            Log.e(name + " file not found");
        } catch (IOException ioException) {
            Log.e(name + " file read filed");
        }
        return set;
    }

    public static String getString(String name) {
        Set<String> set = get(name);
        if (set.isEmpty()) {
            return "";
        }
        return set.iterator().next();
    }

    public static String getString(String name, String defaultValue) {
        Set<String> set = get(name);
        if (set.isEmpty()) {
            return defaultValue;
        }
        return set.iterator().next();
    }

    public static void createFile(File file) {
        try {
            boolean newFile = file.createNewFile();
            if (!newFile) {
                throw new IOException();
            }
        } catch (IOException e) {
            Log.e(file.getName() + " file create filed");
        }
    }

    // v0.9.10 port: 后台应用持久化（background.conf 读写）
    // freezerAppSet 的 key 是 "userId:packageName" 格式，原样持久化，启动时恢复

    /**
     * 追加已冻结应用 key 到 background.conf.
     */
    public static synchronized void appendBackground(String key) {
        appendBackground(ConfigDir, key);
    }

    /**
     * 追加已冻结应用 key 到指定目录的 background.conf.
     */
    public static synchronized void appendBackground(String dir, String key) {
        if (key == null || key.isEmpty()) {
            return;
        }
        File configDir = new File(dir);
        File backgroundFile = new File(dir, backgroundConf);
        PrintWriter writer = null;
        try {
            if (!configDir.exists()) {
                // v0.9.10 port fix (MINOR-21): 检查 mkdir 返回值，失败时显式日志
                if (!configDir.mkdir()) {
                    Log.e("background.conf append failed: config dir create failed: " + dir);
                    return;
                }
            }
            if (!backgroundFile.exists()) {
                backgroundFile.createNewFile();
            }
            writer = new PrintWriter(new FileWriter(backgroundFile, true));
            writer.println(key);
        } catch (IOException e) {
            Log.e("background.conf append failed: " + e.getMessage());
        } finally {
            if (writer != null) {
                writer.close();
            }
        }
    }

    /**
     * 从 background.conf 移除指定已冻结应用 key（应用切回前台时调用）.
     */
    public static synchronized void removeBackground(String key) {
        removeBackground(ConfigDir, key);
    }

    /**
     * 从指定目录的 background.conf 移除指定已冻结应用 key.
     */
    public static synchronized void removeBackground(String dir, String key) {
        if (key == null || key.isEmpty()) {
            return;
        }
        File backgroundFile = new File(dir, backgroundConf);
        if (!backgroundFile.exists()) {
            return;
        }
        // v0.9.10 port fix (MINOR-14): 临时文件 + rename 原子写入，避免进程崩溃损坏文件
        File tempFile = new File(dir, backgroundConf + ".tmp");
        List<String> remaining = new ArrayList<>();
        BufferedReader reader = null;
        PrintWriter writer = null;
        try {
            reader = new BufferedReader(new FileReader(backgroundFile));
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.trim().equals(key)) {
                    remaining.add(line);
                }
            }
            reader.close();
            reader = null;
            writer = new PrintWriter(new FileWriter(tempFile, false));
            for (String entry : remaining) {
                writer.println(entry);
            }
            writer.close();
            writer = null;
            // 同分区 rename 是原子操作，确保崩溃时不会留下截断的 background.conf
            if (!tempFile.renameTo(backgroundFile)) {
                // fallback：先删除原文件再 rename
                if (backgroundFile.delete()) {
                    if (!tempFile.renameTo(backgroundFile)) {
                        Log.e("background.conf remove failed: rename fallback failed");
                    }
                } else {
                    Log.e("background.conf remove failed: delete original failed");
                }
            }
        } catch (IOException e) {
            Log.e("background.conf remove failed: " + e.getMessage());
        } finally {
            if (reader != null) {
                try {
                    reader.close();
                } catch (IOException ignored) {
                }
            }
            if (writer != null) {
                writer.close();
            }
            // 清理可能的残留临时文件
            if (tempFile.exists()) {
                tempFile.delete();
            }
        }
    }

    /**
     * 加载已冻结应用 key 集合（启动时恢复持久化后台列表）.
     */
    public static Set<String> loadBackground() {
        return loadBackground(ConfigDir);
    }

    /**
     * 从指定目录的 background.conf 加载已冻结应用 key 集合（保留插入顺序）.
     */
    public static Set<String> loadBackground(String dir) {
        Set<String> backgroundSet = new LinkedHashSet<>();
        File backgroundFile = new File(dir, backgroundConf);
        if (!backgroundFile.exists()) {
            return backgroundSet;
        }
        BufferedReader reader = null;
        try {
            reader = new BufferedReader(new FileReader(backgroundFile));
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    continue;
                }
                backgroundSet.add(trimmed);
            }
        } catch (IOException e) {
            Log.e("background.conf read failed: " + e.getMessage());
        } finally {
            if (reader != null) {
                try {
                    reader.close();
                } catch (IOException ignored) {
                }
            }
        }
        return backgroundSet;
    }
}
