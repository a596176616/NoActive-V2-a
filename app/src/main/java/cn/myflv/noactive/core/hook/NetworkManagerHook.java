package cn.myflv.noactive.core.hook;

import cn.myflv.noactive.constant.ClassConstants;
import cn.myflv.noactive.constant.MethodConstants;
import cn.myflv.noactive.core.entity.MemData;
import cn.myflv.noactive.core.hook.base.AbstractMethodHook;
import cn.myflv.noactive.core.hook.base.MethodHook;
import cn.myflv.noactive.core.server.NetworkManagementService;
import io.github.libxposed.api.XposedInterface;

public class NetworkManagerHook extends MethodHook {
    private final MemData memData;

    public NetworkManagerHook(ClassLoader classLoader, MemData memData) {
        super(classLoader);
        this.memData = memData;
    }

    @Override
    public String getTargetClass() {
        return ClassConstants.NetworkManagementService;
    }

    @Override
    public String getTargetMethod() {
        return MethodConstants.systemReady;
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
                NetworkManagementService networkManagementService = new NetworkManagementService(classLoader, chain.getThisObject());
                memData.setNetworkManagementService(networkManagementService);
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
        return "Auto block network";
    }

    /**
     * SDK 34+（Android 14+）已移除 com.android.server.NetworkManagementService，
     * socketDestroy API 也已被废弃（main 分支 commit "Deprecate netd API 'socketDestroy'
     * for UID range"）。在新版本上类找不到属于预期行为，不应输出错误日志。
     * <p>
     * 后续如需在新版本上恢复 socket 断网功能，需要走 BpfNetMaps 或 INetd binder，
     * 单独设计实现。
     */
    @Override
    public boolean isIgnoreError() {
        return true;
    }
}
