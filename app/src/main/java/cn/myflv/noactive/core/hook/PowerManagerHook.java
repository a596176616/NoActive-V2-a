package cn.myflv.noactive.core.hook;

import cn.myflv.noactive.constant.ClassConstants;
import cn.myflv.noactive.constant.MethodConstants;
import cn.myflv.noactive.core.entity.MemData;
import cn.myflv.noactive.core.hook.base.AbstractMethodHook;
import cn.myflv.noactive.core.hook.base.MethodHook;
import cn.myflv.noactive.core.server.PowerManagerService;
import io.github.libxposed.api.XposedInterface;

/**
 * PMS启动Hook.
 */
public class PowerManagerHook extends MethodHook {

    /**
     * 数据类.
     */
    private final MemData memData;

    public PowerManagerHook(ClassLoader classLoader, MemData memData) {
        super(classLoader);
        this.memData = memData;
    }

    @Override
    public String getTargetClass() {
        return ClassConstants.PowerManagerService;
    }

    @Override
    public String getTargetMethod() {
        return MethodConstants.onStart;
    }

    @Override
    public Object[] getTargetParam() {
        return new Object[0];
    }

    @Override
    public XposedInterface.Hooker getTargetHook() {
        return new AbstractMethodHook() {
            @Override
            protected Object afterMethod(XposedInterface.Chain chain, Object result) throws Throwable {
                PowerManagerService powerManagerService = new PowerManagerService(chain.getThisObject());
                // 存进数据类
                memData.setPowerManagerService(powerManagerService);
                return result;
            }
        };
    }

    @Override
    public int getMinVersion() {
        return ANY_VERSION;
    }

    @Override
    public String successLog() {
        return "Auto Wakelock";
    }

}
