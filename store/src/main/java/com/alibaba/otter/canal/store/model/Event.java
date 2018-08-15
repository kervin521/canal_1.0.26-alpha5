package com.alibaba.otter.canal.store.model;

import java.io.Serializable;

import org.apache.commons.lang.builder.ToStringBuilder;

import com.alibaba.otter.canal.common.utils.CanalToStringStyle;
import com.alibaba.otter.canal.protocol.CanalEntry;
import com.alibaba.otter.canal.protocol.CanalEntry.EntryType;
import com.alibaba.otter.canal.protocol.CanalEntry.EventType;
import com.alibaba.otter.canal.protocol.position.LogIdentity;
import com.google.protobuf.ByteString;

/**
 * store存储数据对象
 * 
 * @author jianghang 2012-7-13 下午03:03:03
 */
public class Event implements Serializable {

    private static final long serialVersionUID = 1333330351758762739L;

    private LogIdentity       logIdentity;                            // 记录数据产生的来源
    private ByteString        rawEntry;

    private long              executeTime;
    private EntryType         entryType;
    private String            journalName;
    private long              position;
    private long              serverId;
    private EventType         eventType;
    private String            gtid;
    private long              rawLength;

    public Event(){
    }

    public Event(LogIdentity logIdentity, CanalEntry.Entry entry){
        this.logIdentity = logIdentity;
        this.entryType = entry.getEntryType();
        this.executeTime = entry.getHeader().getExecuteTime();
        this.journalName = entry.getHeader().getLogfileName();
        this.position = entry.getHeader().getLogfileOffset();
        this.serverId = entry.getHeader().getServerId();
        this.gtid = entry.getHeader().getGtid();
        this.eventType = entry.getHeader().getEventType();
        // build raw
        this.rawEntry = entry.toByteString();
        this.rawLength = rawEntry.size();
    }

    public LogIdentity getLogIdentity() {
        return logIdentity;
    }

    public void setLogIdentity(LogIdentity logIdentity) {
        this.logIdentity = logIdentity;
    }

    public ByteString getRawEntry() {
        return rawEntry;
    }

    public void setRawEntry(ByteString rawEntry) {
        this.rawEntry = rawEntry;
    }

    public long getExecuteTime() {
        return executeTime;
    }

    public void setExecuteTime(long executeTime) {
        this.executeTime = executeTime;
    }

    public EntryType getEntryType() {
        return entryType;
    }

    public void setEntryType(EntryType entryType) {
        this.entryType = entryType;
    }

    public String getJournalName() {
        return journalName;
    }

    public void setJournalName(String journalName) {
        this.journalName = journalName;
    }

    public long getPosition() {
        return position;
    }

    public void setPosition(long position) {
        this.position = position;
    }

    public long getServerId() {
        return serverId;
    }

    public void setServerId(long serverId) {
        this.serverId = serverId;
    }

    public String getGtid() {
        return gtid;
    }

    public void setGtid(String gtid) {
        this.gtid = gtid;
    }

    public long getRawLength() {
        return rawLength;
    }

    public void setRawLength(long rawLength) {
        this.rawLength = rawLength;
    }

    public EventType getEventType() {
        return eventType;
    }

    public void setEventType(EventType eventType) {
        this.eventType = eventType;
    }

    public String toString() {
        return ToStringBuilder.reflectionToString(this, CanalToStringStyle.DEFAULT_STYLE);
    }
}
