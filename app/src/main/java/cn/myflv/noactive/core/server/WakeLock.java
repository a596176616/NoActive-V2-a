package cn.myflv.noactive.core.server;

import android.os.IBinder;

import cn.myflv.noactive.constant.FieldConstants;
import cn.myflv.noactive.utils.ReflectionUtils;
import lombok.Data;

@Data
public class WakeLock {
    private final Object instance;
    private final IBinder lock;
    private final String tag;
    private String packageName;
    private int flags;
    private int uid;

    public WakeLock(Object wakeLock) {
        instance = wakeLock;
        this.packageName = (String) ReflectionUtils.getObjectField(wakeLock, FieldConstants.mPackageName);
        this.tag = (String) ReflectionUtils.getObjectField(wakeLock, FieldConstants.mTag);
        this.flags = ReflectionUtils.getIntField(wakeLock, FieldConstants.mFlags);
        this.lock = (IBinder) ReflectionUtils.getObjectField(wakeLock, FieldConstants.mLock);
        this.uid = ReflectionUtils.getIntField(wakeLock, FieldConstants.mOwnerUid);
    }

    public void setDisabled(boolean disabled) {
        ReflectionUtils.setObjectField(instance, FieldConstants.mDisabled, disabled);
    }
}
