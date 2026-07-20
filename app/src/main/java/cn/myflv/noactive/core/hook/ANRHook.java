package cn.myflv.noactive.core.hook;

import android.os.Build;

import java.lang.reflect.Method;

import cn.myflv.noactive.constant.ClassConstants;
import cn.myflv.noactive.constant.MethodConstants;
import cn.myflv.noactive.core.HandleHook;
import cn.myflv.noactive.core.entity.MemData;
import cn.myflv.noactive.core.hook.base.AbstractReplaceHook;
import cn.myflv.noactive.core.hook.base.MethodHook;
import cn.myflv.noactive.core.server.ProcessRecord;
import cn.myflv.noactive.core.utils.Log;
import io.github.libxposed.api.XposedInterface;

/**
 * ANR相关Hook.
 * <p>
 * v0.9.10 port: SDK 34+ AnrHelper.appNotResponding 签名变更，通过重写 hook() 实现三级候选签名依次尝试：
 * <ul>
 *   <li>SDK 36: 9 args + ExecutorService + TimeoutRecord</li>
 *   <li>SDK 34-35: 8 args + TimeoutRecord</li>
 *   <li>SDK 30-33: 7 args + String (原有签名)</li>
 * </ul>
 * <p>
 * API 102: 用 Java 反射 + {@code HandleHook.getInstance().hook(method).intercept(hooker)}
 * 替代 {@code XposedHelpers.findAndHookMethod}；
 * 用 {@link AbstractReplaceHook#DO_NOTHING} 替代 {@code XC_MethodReplacement.DO_NOTHING}；
 * 用 {@code chain.proceed()} 替代 {@code invokeOriginalMethod(param)}.
 */
public class ANRHook extends MethodHook {

    /**
     * 内存数据
     */
    private final MemData memData;

    /**
     * 当前生效的 ANR 处理模式（hook 成功后确定）。
     * <ul>
     *   <li>{@link #MODE_DO_NOTHING}: SDK <= Q 直接吞掉 ANR，不调用原方法</li>
     *   <li>{@link #MODE_REPLACE}: SDK > Q 替换原方法，按需保留 ANR 记录</li>
     * </ul>
     */
    private int mode = MODE_DO_NOTHING;

    private static final int MODE_DO_NOTHING = 0;
    private static final int MODE_REPLACE = 1;

    public ANRHook(ClassLoader classLoader, MemData memData) {
        super(classLoader);
        this.memData = memData;
    }

    @Override
    public String getTargetClass() {
        if (Build.VERSION.SDK_INT > Build.VERSION_CODES.Q) {
            return ClassConstants.AnrHelper;
        } else if (Build.VERSION.SDK_INT == Build.VERSION_CODES.Q) {
            return ClassConstants.ProcessRecord;
        } else {
            return ClassConstants.AppErrors;
        }
    }

    @Override
    public String getTargetMethod() {
        return MethodConstants.appNotResponding;
    }

    @Override
    public Object[] getTargetParam() {
        if (Build.VERSION.SDK_INT > Build.VERSION_CODES.Q) {
            // SDK 30-33: 7 args + String
            return new Object[]{
                    ClassConstants.ProcessRecord, String.class, ClassConstants.ApplicationInfo,
                    String.class, ClassConstants.WindowProcessController,
                    boolean.class, String.class};
        } else if (Build.VERSION.SDK_INT == Build.VERSION_CODES.Q) {
            return new Object[]{
                    String.class, ClassConstants.ApplicationInfo, String.class,
                    ClassConstants.WindowProcessController, boolean.class, String.class};
        } else {
            return new Object[]{
                    ClassConstants.ProcessRecord, ClassConstants.ActivityRecord_P,
                    ClassConstants.ActivityRecord_P, boolean.class, String.class};
        }
    }

    /**
     * v0.9.10 port: 重写 hook() 实现多候选签名依次尝试.
     * <p>
     * SDK > Q 时先尝试 SDK 36 签名（带 ExecutorService + TimeoutRecord），
     * 失败再尝试 SDK 34-35 签名（带 TimeoutRecord），最后回退到 SDK 30-33 签名（带 String）。
     * SDK <= Q 仍走 DO_NOTHING 模式。
     */
    @Override
    public void hook() {
        int minVersion = getMinVersion();
        if (minVersion != ANY_VERSION && Build.VERSION.SDK_INT < minVersion) {
            return;
        }

        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q) {
            // SDK <= Q: 走 DO_NOTHING 模式
            mode = MODE_DO_NOTHING;
            try {
                Class<?> clazz = Class.forName(getTargetClass(), false, classLoader);
                Class<?>[] paramTypes = resolveParamTypes(getTargetParam());
                Method method = clazz.getDeclaredMethod(getTargetMethod(), paramTypes);
                method.setAccessible(true);
                HandleHook.getInstance().hook(method).intercept(AbstractReplaceHook.DO_NOTHING);
                onSuccess();
            } catch (Throwable throwable) {
                onError(throwable);
            }
            return;
        }

        // SDK > Q: 多候选签名依次尝试，最终回退到基类默认签名
        mode = MODE_REPLACE;
        XposedInterface.Hooker targetHook = getTargetHook();

        // 候选 1: SDK 36 - 9 args + ExecutorService + TimeoutRecord
        if (Build.VERSION.SDK_INT >= 34) {
            try {
                Class<?>[] paramTypes = new Class<?>[]{
                        Class.forName(ClassConstants.ProcessRecord, false, classLoader),
                        String.class,
                        Class.forName(ClassConstants.ApplicationInfo, false, classLoader),
                        String.class,
                        Class.forName(ClassConstants.WindowProcessController, false, classLoader),
                        boolean.class,
                        Class.forName(ClassConstants.ExecutorService, false, classLoader),
                        Class.forName(ClassConstants.TimeoutRecord, false, classLoader),
                        boolean.class
                };
                Class<?> clazz = Class.forName(ClassConstants.AnrHelper, false, classLoader);
                Method method = clazz.getDeclaredMethod(getTargetMethod(), paramTypes);
                method.setAccessible(true);
                HandleHook.getInstance().hook(method).intercept(targetHook);
                Log.i("Auto keep ANR (9 args + ExecutorService + TimeoutRecord, SDK 36)");
                onSuccess();
                return;
            } catch (Throwable ignored) {
                // SDK 36 签名不可用，尝试 SDK 34-35
            }
        }

        // 候选 2: SDK 34-35 - 8 args + TimeoutRecord
        if (Build.VERSION.SDK_INT >= 34) {
            try {
                Class<?>[] paramTypes = new Class<?>[]{
                        Class.forName(ClassConstants.ProcessRecord, false, classLoader),
                        String.class,
                        Class.forName(ClassConstants.ApplicationInfo, false, classLoader),
                        String.class,
                        Class.forName(ClassConstants.WindowProcessController, false, classLoader),
                        boolean.class,
                        Class.forName(ClassConstants.TimeoutRecord, false, classLoader),
                        boolean.class
                };
                Class<?> clazz = Class.forName(ClassConstants.AnrHelper, false, classLoader);
                Method method = clazz.getDeclaredMethod(getTargetMethod(), paramTypes);
                method.setAccessible(true);
                HandleHook.getInstance().hook(method).intercept(targetHook);
                Log.i("Auto keep ANR (8 args + TimeoutRecord, SDK 34-35 fallback)");
                onSuccess();
                return;
            } catch (Throwable ignored) {
                // SDK 34-35 签名不可用，回退到 SDK 30-33
            }
        }

        // 候选 3: SDK 30-33 - 7 args + String（基类默认签名）
        try {
            Class<?>[] paramTypes = resolveParamTypes(getTargetParam());
            Class<?> clazz = Class.forName(ClassConstants.AnrHelper, false, classLoader);
            Method method = clazz.getDeclaredMethod(getTargetMethod(), paramTypes);
            method.setAccessible(true);
            HandleHook.getInstance().hook(method).intercept(targetHook);
            Log.i("Auto keep ANR (7 args + String, SDK 30-33 fallback)");
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
        if (Build.VERSION.SDK_INT > Build.VERSION_CODES.Q) {
            return new AbstractReplaceHook() {
                @Override
                protected Object replaceMethod(XposedInterface.Chain chain) throws Throwable {
                    // ANR进程为空就不处理
                    if (chain.getArg(0) == null) return null;
                    // ANR进程
                    ProcessRecord processRecord = new ProcessRecord(chain.getArg(0));
                    // 进程对应包名
                    String packageName = processRecord.getPackageName();
                    // 不是目标APP就调用原方法
                    if (!memData.isTargetApp(packageName)) {
                        return chain.proceed();
                    }
                    String processNameWithUser = processRecord.getProcessNameWithUser();
                    String packageNameWithUser = processRecord.getPackageNameWithUser();
                    Log.d("keep " + (processNameWithUser != null ? processNameWithUser : packageNameWithUser));
                    // 不处理
                    return null;
                }
            };
        } else {
            return AbstractReplaceHook.DO_NOTHING;
        }
    }

    @Override
    public int getMinVersion() {
        return Build.VERSION_CODES.P;
    }

    @Override
    public String successLog() {
        if (Build.VERSION.SDK_INT > Build.VERSION_CODES.Q) {
            return "Auto keep ANR";
        } else {
            return "Force keep ANR";
        }
    }


}
