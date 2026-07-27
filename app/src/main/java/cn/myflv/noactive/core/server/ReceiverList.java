package cn.myflv.noactive.core.server;

import cn.myflv.noactive.constant.FieldConstants;
import cn.myflv.noactive.utils.ReflectionUtils;
import lombok.Data;

@Data
public class ReceiverList {
    private final Object receiverList;
    private ProcessRecord processRecord;

    public ReceiverList(Object receiverList) {
        this.receiverList = receiverList;
        try {
            this.processRecord = new ProcessRecord(ReflectionUtils.getObjectField(receiverList, FieldConstants.app));
        } catch (Exception ignored) {
        }
    }


    public void clear() {
        ReflectionUtils.setObjectField(receiverList, FieldConstants.app, null);
    }


}
