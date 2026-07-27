package cn.myflv.noactive.core.hook.base;

import android.os.Build;

import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Method;

import cn.myflv.noactive.core.HandleHook;
import cn.myflv.noactive.core.utils.Log;
import io.github.libxposed.api.XposedInterface;

/**
 * 方法 Hook 抽象类（API 102 版本）.
 * <p>
 * 使用 Java 标准反射定位目标 {@link Executable}，
 * 通过 {@link HandleHook#getInstance()} 取得框架注入的 {@link XposedInterface}
 * 并调用 {@code hook(executable).intercept(hooker)} 完成注册。
 * <p>
 * 替代旧 API 的 {@code XposedHelpers.findAndHookMethod/findAndHookConstructor}.
 */
public abstract class MethodHook {
    /**
     * 任何版本
     */
    public final int ANY_VERSION = -1;
    /**
     * 类加载器
     */
    public final ClassLoader classLoader;

    public MethodHook(ClassLoader classLoader) {
        this.classLoader = classLoader;
        if (isToHook()) {
            try {
                hook();
            } catch (Throwable throwable) {
                onError(throwable);
            }
        }
    }

    /**
     * @return 目标类
     */
    public abstract String getTargetClass();

    /**
     * @return 目标方法名；返回 null 表示 hook 构造方法
     */
    public abstract String getTargetMethod();

    /**
     * @return 目标方法/构造方法的参数类型数组
     */
    public abstract Object[] getTargetParam();

    /**
     * @return Hook 实现（{@link AbstractMethodHook} / {@link AbstractReplaceHook} 或自定义 {@link XposedInterface.Hooker}）
     */
    public abstract XposedInterface.Hooker getTargetHook();

    /**
     * @return 最低支持版本
     */
    public abstract int getMinVersion();

    /**
     * @return 成功日志
     */
    public abstract String successLog();

    /**
     * @return 忽略错误
     */
    public boolean isIgnoreError() {
        return false;
    }

    /**
     * Hook 包装：通过反射定位 {@link Executable} 并注册 {@link XposedInterface.Hooker}.
     * <p>
     * 当 {@link #getTargetMethod()} 返回 null 时 hook 构造方法，否则 hook 普通方法。
     *
     * @throws Throwable 反射或 hook 注册失败
     */
    public void hook() throws Throwable {
        int minVersion = getMinVersion();
        if (minVersion == ANY_VERSION || Build.VERSION.SDK_INT >= minVersion) {
            Class<?> clazz = Class.forName(getTargetClass(), false, classLoader);
            Object[] paramTypes = getTargetParam();
            Class<?>[] paramClasses = new Class<?>[paramTypes.length];
            for (int i = 0; i < paramTypes.length; i++) {
                paramClasses[i] = (Class<?>) paramTypes[i];
            }
            Executable executable;
            if (getTargetMethod() == null) {
                Constructor<?> constructor = clazz.getDeclaredConstructor(paramClasses);
                constructor.setAccessible(true);
                executable = constructor;
            } else {
                Method method = clazz.getDeclaredMethod(getTargetMethod(), paramClasses);
                method.setAccessible(true);
                executable = method;
            }
            HandleHook.getInstance().hook(executable).intercept(getTargetHook());
            onSuccess();
        }
    }

    /**
     * @return 是否 Hook
     */
    public boolean isToHook() {
        return true;
    }

    /**
     * 打印成功日志包装.
     */
    public void logSuccess() {
        String log = successLog();
        if (log == null) {
            return;
        }
        Log.i(log);

    }

    /**
     * 成功后执行方法.
     */
    public void onSuccess() {
        logSuccess();
    }

    /**
     * 打印错误.
     *
     * @param throwable 异常
     */
    public void logError(Throwable throwable) {
        if (isIgnoreError()) {
            return;
        }
        Log.e(getTargetClass() + "." + getTargetMethod() + " failed: " + throwable.getMessage());
    }

    /**
     * 错误后执行方法.
     *
     * @param throwable 异常
     */
    public void onError(Throwable throwable) {
        logError(throwable);
    }

    /**
     * 返回常量 Hooker.
     *
     * @param result 结果
     * @return 返回常量的 {@link XposedInterface.Hooker}
     */
    public XposedInterface.Hooker constantResult(final Object result) {
        return chain -> result;
    }

    public boolean runNoThrow(Runnable runnable) {
        try {
            runnable.run();
            return true;
        } catch (Throwable throwable) {
            return false;
        }
    }
}
