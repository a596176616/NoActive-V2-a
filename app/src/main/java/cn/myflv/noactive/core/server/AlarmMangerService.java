package cn.myflv.noactive.core.server;

import android.os.Build;

import cn.myflv.noactive.constant.FieldConstants;
import cn.myflv.noactive.constant.MethodConstants;
import cn.myflv.noactive.core.entity.AppInfo;
import cn.myflv.noactive.core.utils.Log;
import cn.myflv.noactive.utils.ReflectionUtils;
import lombok.Data;

@Data
public class AlarmMangerService {
    private final Object instance;
    private final Object lock;

    public AlarmMangerService(Object instance) {
        this.instance = instance;
        lock = ReflectionUtils.getObjectField(instance, FieldConstants.mLock);
    }


    public void remove(AppInfo appInfo, int uid) {
        synchronized (lock) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                ReflectionUtils.callMethod(instance, MethodConstants.removeLocked, uid);
            } else {
                ReflectionUtils.callMethod(instance, MethodConstants.removeLocked, uid, 0);
            }
            Log.d(appInfo.getKey() + " alarm removed");
        }

    }

}
