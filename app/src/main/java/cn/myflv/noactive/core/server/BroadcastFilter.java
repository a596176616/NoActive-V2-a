package cn.myflv.noactive.core.server;

import cn.myflv.noactive.constant.FieldConstants;
import cn.myflv.noactive.utils.ReflectionUtils;
import lombok.Data;

@Data
public class BroadcastFilter {
    private final Object broadcastFilter;
    private final ReceiverList receiverList;


    public BroadcastFilter(Object broadcastFilter) {
        this.broadcastFilter = broadcastFilter;
        this.receiverList = new ReceiverList(ReflectionUtils.getObjectField(broadcastFilter, FieldConstants.receiverList));
    }

}
