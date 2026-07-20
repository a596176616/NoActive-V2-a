package cn.myflv.noactive.core.hook.base;

import io.github.libxposed.api.XposedInterface;

import cn.myflv.noactive.core.utils.ThreadUtils;

/**
 * API 102 replace Hook 基类.
 * <p>
 * 实现 {@link XposedInterface.Hooker} 拦截模型，完全替换原方法实现。
 * 子类重写 {@link #replaceMethod} 返回自定义结果；若抛异常则回退到原方法。
 * 替代旧 API 的 {@code XC_MethodReplacement.replaceHookedMethod}.
 */
public abstract class AbstractReplaceHook implements XposedInterface.Hooker {

    /**
     * 静默吞掉原方法（不调用，返回 null）.
     * 等价于旧 API 的 {@code XC_MethodReplacement.DO_NOTHING}.
     */
    public static final XposedInterface.Hooker DO_NOTHING = chain -> null;

    protected Object replaceMethod(XposedInterface.Chain chain) throws Throwable {
        return null;
    }

    @Override
    public Object intercept(XposedInterface.Chain chain) throws Throwable {
        try {
            return replaceMethod(chain);
        } catch (Throwable throwable) {
            ThreadUtils.printStackTrace(throwable);
        }
        return chain.proceed();
    }
}
