package cn.myflv.noactive.core.hook.base;

import io.github.libxposed.api.XposedInterface;

import cn.myflv.noactive.core.utils.ThreadUtils;

/**
 * API 102 before/after Hook 基类.
 * <p>
 * 实现 {@link XposedInterface.Hooker} 拦截模型：
 * <ul>
 *   <li>{@link #beforeMethod} 在原方法执行前调用</li>
 *   <li>{@link #afterMethod} 在原方法执行后调用，可读取或替换返回值</li>
 * </ul>
 * 替代旧 API 的 {@code XC_MethodHook.beforeHookedMethod/afterHookedMethod}.
 */
public abstract class AbstractMethodHook implements XposedInterface.Hooker {

    protected void beforeMethod(XposedInterface.Chain chain) throws Throwable {

    }

    protected Object afterMethod(XposedInterface.Chain chain, Object result) throws Throwable {
        return result;
    }

    @Override
    public Object intercept(XposedInterface.Chain chain) throws Throwable {
        try {
            beforeMethod(chain);
        } catch (Throwable throwable) {
            ThreadUtils.printStackTrace(throwable);
        }
        Object result = chain.proceed();
        try {
            result = afterMethod(chain, result);
        } catch (Throwable throwable) {
            ThreadUtils.printStackTrace(throwable);
        }
        return result;
    }
}
