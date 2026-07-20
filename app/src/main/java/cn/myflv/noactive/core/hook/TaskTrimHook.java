package cn.myflv.noactive.core.hook;

import cn.myflv.noactive.constant.ClassConstants;
import cn.myflv.noactive.constant.MethodConstants;
import cn.myflv.noactive.core.hook.base.AbstractReplaceHook;
import cn.myflv.noactive.core.hook.base.MethodHook;
import cn.myflv.noactive.core.utils.Log;
import io.github.libxposed.api.XposedInterface;

public class TaskTrimHook extends MethodHook {

    public TaskTrimHook(ClassLoader classLoader) {
        super(classLoader);
    }

    @Override
    public String getTargetClass() {
        return ClassConstants.RecentTasks;
    }

    @Override
    public String getTargetMethod() {
        return MethodConstants.trimInactiveRecentTasks;
    }

    @Override
    public Object[] getTargetParam() {
        return new Object[0];
    }

    @Override
    public XposedInterface.Hooker getTargetHook() {
        return new AbstractReplaceHook() {
            @Override
            protected Object replaceMethod(XposedInterface.Chain chain) throws Throwable {
                Log.d("avoid trim inactive recent tasks");
                return null;
            }
        };
    }

    @Override
    public int getMinVersion() {
        return ANY_VERSION;
    }

    @Override
    public String successLog() {
        return "Disable task trim";
    }

}
