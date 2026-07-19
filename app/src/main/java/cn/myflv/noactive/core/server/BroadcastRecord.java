package cn.myflv.noactive.core.server;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import de.robv.android.xposed.XposedHelpers;
import lombok.Data;

/**
 * BroadcastRecord 包装类.
 * <p>
 * v0.9.10 port: SDK 34+ BroadcastQueueImpl.dispatchReceivers 通过 receiverIndex 索引接收者，
 * 需要从此对象中按索引取出 receiver（可能是 BroadcastFilter 或 ResolveInfo）。
 */
@Data
public class BroadcastRecord {
    private final Object broadcastRecord;
    private final List<Object> receivers;

    public BroadcastRecord(Object broadcastRecord) {
        this.broadcastRecord = broadcastRecord;
        this.receivers = new ArrayList<>();
        if (broadcastRecord == null) return;
        try {
            Object fieldReceivers = XposedHelpers.getObjectField(broadcastRecord, "receivers");
            if (fieldReceivers instanceof List) {
                Iterator<Object> iterator = ((List<Object>) fieldReceivers).iterator();
                while (iterator.hasNext()) {
                    this.receivers.add(iterator.next());
                }
            }
        } catch (Throwable ignored) {
        }
    }

    public Object getReceiver(int index) {
        if (index >= 0 && index < receivers.size()) {
            return receivers.get(index);
        }
        return null;
    }
}
