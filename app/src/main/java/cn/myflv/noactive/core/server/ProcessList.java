package cn.myflv.noactive.core.server;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import cn.myflv.noactive.constant.ClassConstants;
import cn.myflv.noactive.constant.FieldConstants;
import cn.myflv.noactive.constant.MethodConstants;
import cn.myflv.noactive.utils.ReflectionUtils;
import lombok.Data;

@Data
public class ProcessList {
    private final Object processList;

    public ProcessList(Object processList) {
        this.processList = processList;
    }

    public static void setOomAdj(ClassLoader classLoader, int pid, int uid, int oomAdj) {
        Class<?> ProcessList = ReflectionUtils.findClass(ClassConstants.ProcessList, classLoader);
        ReflectionUtils.callStaticMethod(ProcessList, MethodConstants.setOomAdj, pid, uid, oomAdj);
    }

    public Map<String, List<ProcessRecord>> getProcessMap() {
        Map<String, List<ProcessRecord>> processMap = new HashMap<>();
        synchronized (processList) {
            try {
                List<?> processRecordList = (List<?>) ReflectionUtils.getObjectField(processList, FieldConstants.mLruProcesses);
                for (Object proc : processRecordList) {
                    ProcessRecord processRecord = new ProcessRecord(proc);
                    String packageName = processRecord.getPackageName();
                    List<ProcessRecord> list = processMap.computeIfAbsent(packageName, k -> new ArrayList<>());
                    list.add(processRecord);
                }
            } catch (Exception ignored) {

            }
        }
        return processMap;
    }

    public List<ProcessRecord> getProcessList(String packageName) {
        List<ProcessRecord> processRecords = getProcessMap().get(packageName);
        if (processRecords == null) {
            return new ArrayList<>();
        }
        return processRecords;
    }
}
