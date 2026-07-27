package cn.myflv.noactive.core.hook;

import android.os.Build;

import cn.myflv.noactive.constant.ClassConstants;
import cn.myflv.noactive.constant.MethodConstants;
import cn.myflv.noactive.core.entity.MemData;
import cn.myflv.noactive.core.hook.base.AbstractMethodHook;
import cn.myflv.noactive.core.hook.base.MethodHook;
import cn.myflv.noactive.core.server.AppStandbyController;
import io.github.libxposed.api.XposedInterface;

public class AppStandbyHook extends MethodHook {
    private final MemData memData;

    public AppStandbyHook(ClassLoader classLoader, MemData memData) {
        super(classLoader);
        this.memData = memData;
    }

    @Override
    public String getTargetClass() {
        return ClassConstants.AppStandbyController;
    }

    @Override
    public String getTargetMethod() {
        return MethodConstants.onBootPhase;
    }

    @Override
    public Object[] getTargetParam() {
        return new Object[]{int.class};
    }

    @Override
    public XposedInterface.Hooker getTargetHook() {
        return new AbstractMethodHook() {
            @Override
            protected Object afterMethod(XposedInterface.Chain chain, Object result) throws Throwable {
                AppStandbyController appStandbyController = new AppStandbyController(chain.getThisObject());
                memData.setAppStandbyController(appStandbyController);
                return result;
            }
        };
    }

    @Override
    public int getMinVersion() {
        return Build.VERSION_CODES.P;
    }

    @Override
    public String successLog() {
        return "Auto Standby";
    }
}
