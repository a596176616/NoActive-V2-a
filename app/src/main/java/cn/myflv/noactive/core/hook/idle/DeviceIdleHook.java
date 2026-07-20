package cn.myflv.noactive.core.hook.idle;

import cn.myflv.noactive.constant.ClassConstants;
import cn.myflv.noactive.constant.MethodConstants;
import cn.myflv.noactive.core.entity.MemData;
import cn.myflv.noactive.core.hook.base.AbstractMethodHook;
import cn.myflv.noactive.core.hook.base.MethodHook;
import cn.myflv.noactive.core.server.DeviceIdleController;
import io.github.libxposed.api.XposedInterface;

public class DeviceIdleHook extends MethodHook {

    private final MemData memData;

    public DeviceIdleHook(ClassLoader classLoader, MemData memData) {
        super(classLoader);
        this.memData = memData;
    }

    @Override
    public String getTargetClass() {
        return ClassConstants.DeviceIdleController;
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
                DeviceIdleController deviceIdleController = new DeviceIdleController(chain.getThisObject());
                memData.setDeviceIdleController(deviceIdleController);
                synchronized (memData) {
                    memData.notifyConfigChanged();
                }
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
        return "Auto deep doze";
    }
}
