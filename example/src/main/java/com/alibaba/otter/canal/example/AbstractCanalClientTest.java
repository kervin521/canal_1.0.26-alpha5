package com.alibaba.otter.canal.example;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang.SystemUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.util.Assert;
import org.springframework.util.CollectionUtils;

import com.alibaba.otter.canal.client.CanalConnector;
import com.alibaba.otter.canal.protocol.CanalEntry.Column;
import com.alibaba.otter.canal.protocol.CanalEntry.Entry;
import com.alibaba.otter.canal.protocol.CanalEntry.EntryType;
import com.alibaba.otter.canal.protocol.CanalEntry.EventType;
import com.alibaba.otter.canal.protocol.CanalEntry.Pair;
import com.alibaba.otter.canal.protocol.CanalEntry.RowChange;
import com.alibaba.otter.canal.protocol.CanalEntry.RowData;
import com.alibaba.otter.canal.protocol.CanalEntry.TransactionBegin;
import com.alibaba.otter.canal.protocol.CanalEntry.TransactionEnd;
import com.codahale.metrics.ConsoleReporter;
import com.codahale.metrics.MetricsHolder;
import com.codahale.metrics.Timer;
import com.codahale.metrics.Timer.Context;
import com.alibaba.otter.canal.protocol.CanalEntry;
import com.alibaba.otter.canal.protocol.Message;
import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;

import io.netty.util.internal.StringUtil;

/**
 * 测试基类
 * 
 * @author jianghang 2013-4-15 下午04:17:12
 * @version 1.0.4
 */
public class AbstractCanalClientTest {

    protected final static Logger             logger             = LoggerFactory.getLogger(AbstractCanalClientTest.class);
    protected static final String             SEP                = SystemUtils.LINE_SEPARATOR;
    protected static final String             DATE_FORMAT        = "yyyy-MM-dd HH:mm:ss";
    protected volatile boolean                running            = false;
    protected Thread.UncaughtExceptionHandler handler            = new Thread.UncaughtExceptionHandler() {

                                                                     public void uncaughtException(Thread t, Throwable e) {
                                                                         logger.error("parse events has an error", e);
                                                                     }
                                                                 };
    protected Thread                          thread             = null;
    protected CanalConnector                  connector;
    protected static String                   context_format     = null;
    protected static String                   row_format         = null;
    protected static String                   transaction_format = null;
    protected String                          destination;
    protected static volatile ConsoleReporter console = MetricsHolder.console();
    private static final Timer mtimer = MetricsHolder.timer("Canal-Client-Message-Timer");
    private static final Timer etimer = MetricsHolder.timer("Canal-Client-Entry-Timer");
    private static final Timer rtimer = MetricsHolder.timer("Canal-Client-Row-Timer");
    static {
        context_format = SEP + "****************************************************" + SEP;
        context_format += "* Batch Id: [{}] ,count : [{}] , memsize : [{}] , Time : {}" + SEP;
        context_format += "* Start : [{}] " + SEP;
        context_format += "* End : [{}] " + SEP;
        context_format += "****************************************************" + SEP;

        row_format = SEP
                     + "----------------> binlog[{}:{}] , name[{},{}] , eventType : {} , executeTime : {}({}) , gtid : ({}) , delay : {} ms"
                     + SEP;

        transaction_format = SEP
                             + "================> binlog[{}:{}] , executeTime : {}({}) , gtid : ({}) , delay : {}ms"
                             + SEP;

    }

    public AbstractCanalClientTest(String destination){
        this(destination, null);
    }

    public AbstractCanalClientTest(String destination, CanalConnector connector){
        this.destination = destination;
        this.connector = connector;
    }

    protected void start() {
        Assert.notNull(connector, "connector is null");
        console.start(5, TimeUnit.SECONDS);
        thread = new Thread(new Runnable() {

            public void run() {
                process();
            }
        });
        thread.setUncaughtExceptionHandler(handler);
        running = true;
        thread.start();
    }

    protected void stop() {
        if (!running) {
            return;
        }
        console.close();
        connector.stopRunning();
        running = false;
        if (thread != null) {
            try {
                thread.join();
            } catch (InterruptedException e) {
                // ignore
            }
        }

        MDC.remove("destination");
    }

    protected void process() {
        int batchSize = 5 * 1024;
        if(!StringUtil.isNullOrEmpty(System.getProperty("canal.batch.size"))){
        	batchSize = Integer.valueOf(System.getProperty("canal.batch.size"));
        	logger.warn("batchSize:"+batchSize);
        }
        int sleepTime = 0;
        if(!StringUtil.isNullOrEmpty(System.getProperty("canal.sleep.time"))){
        	sleepTime = Integer.valueOf(System.getProperty("canal.sleep.time"));
        	logger.warn("sleepTime:"+sleepTime);
        }
        while (running) {
            try {
                MDC.put("destination", destination);
                connector.connect();
                connector.subscribe();
                while (running) {
                    Message message = connector.getWithoutAck(batchSize); // 获取指定数量的数据
                    long batchId = message.getId();
                    int size = message.getEntries().size();
                    if (batchId == -1 || size == 0) {
                    	if(sleepTime>0){
                    		try {
                           	 Thread.sleep(sleepTime*1000);
                            } catch (InterruptedException e) {
                            }	
                    	}
                    } else {
                    	Context ctx = mtimer.time();
                    	try{
	                        printSummary(message, batchId, size);
	                        printEntry(message.getEntries());
                    	}finally{
                    		ctx.close();
                    	}
                    }

                    connector.ack(batchId); // 提交确认
                    // connector.rollback(batchId); // 处理失败, 回滚数据
                }
            } catch (Exception e) {
                logger.error("process error!", e);
            } finally {
                connector.disconnect();
                MDC.remove("destination");
            }
        }
    }

    private void printSummary(Message message, long batchId, int size) {
        long memsize = 0;
        for (Entry entry : message.getEntries()) {
            memsize += entry.getHeader().getEventLength();
        }

        String startPosition = null;
        String endPosition = null;
        if (!CollectionUtils.isEmpty(message.getEntries())) {
            startPosition = buildPositionForDump(message.getEntries().get(0));
            endPosition = buildPositionForDump(message.getEntries().get(message.getEntries().size() - 1));
        }

        SimpleDateFormat format = new SimpleDateFormat(DATE_FORMAT);
        logger.info(context_format, new Object[] { batchId, size, memsize, format.format(new Date()), startPosition,
                endPosition });
    }

    protected String buildPositionForDump(Entry entry) {
        long time = entry.getHeader().getExecuteTime();
        Date date = new Date(time);
        SimpleDateFormat format = new SimpleDateFormat(DATE_FORMAT);
        String position = entry.getHeader().getLogfileName() + ":" + entry.getHeader().getLogfileOffset() + ":"
                          + entry.getHeader().getExecuteTime() + "(" + format.format(date) + ")";
        if (StringUtils.isNotEmpty(entry.getHeader().getGtid())) {
            position += " gtid(" + entry.getHeader().getGtid() + ")";
        }
        return position;
    }

    protected void printEntry(List<Entry> entrys) {
        for (Entry entry : entrys) {
            long executeTime = entry.getHeader().getExecuteTime();
            long delayTime = new Date().getTime() - executeTime;
            Date date = new Date(entry.getHeader().getExecuteTime());
            SimpleDateFormat simpleDateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

            if (entry.getEntryType() == EntryType.TRANSACTIONBEGIN || entry.getEntryType() == EntryType.TRANSACTIONEND) {
                if (entry.getEntryType() == EntryType.TRANSACTIONBEGIN) {
                    TransactionBegin begin = null;
                    try {
                        begin = TransactionBegin.parseFrom(entry.getStoreValue());
                    } catch (InvalidProtocolBufferException e) {
                        throw new RuntimeException("parse event has an error , data:" + entry.toString(), e);
                    }
                    // 打印事务头信息，执行的线程id，事务耗时
                    logger.info(transaction_format,
                        new Object[] { entry.getHeader().getLogfileName(),
                                String.valueOf(entry.getHeader().getLogfileOffset()),
                                String.valueOf(entry.getHeader().getExecuteTime()), simpleDateFormat.format(date),
                                entry.getHeader().getGtid(), String.valueOf(delayTime) });
                    logger.info(" BEGIN ----> Thread id: {}", begin.getThreadId());
                    printXAInfo(begin.getPropsList());
                } else if (entry.getEntryType() == EntryType.TRANSACTIONEND) {
                    TransactionEnd end = null;
                    try {
                        end = TransactionEnd.parseFrom(entry.getStoreValue());
                    } catch (InvalidProtocolBufferException e) {
                        throw new RuntimeException("parse event has an error , data:" + entry.toString(), e);
                    }
                    // 打印事务提交信息，事务id
                    logger.info("----------------\n");
                    logger.info(" END ----> transaction id: {}", end.getTransactionId());
                    printXAInfo(end.getPropsList());
                    logger.info(transaction_format,
                        new Object[] { entry.getHeader().getLogfileName(),
                                String.valueOf(entry.getHeader().getLogfileOffset()),
                                String.valueOf(entry.getHeader().getExecuteTime()), simpleDateFormat.format(date),
                                entry.getHeader().getGtid(), String.valueOf(delayTime) });
                }

                continue;
            }

            if (entry.getEntryType() == EntryType.ROWDATA) {
                RowChange rowChage = null;
                try {
                    rowChage = RowChange.parseFrom(entry.getStoreValue());
                } catch (Exception e) {
                    throw new RuntimeException("parse event has an error , data:" + entry.toString(), e);
                }

                EventType eventType = rowChage.getEventType();

                logger.info(row_format,
                    new Object[] { entry.getHeader().getLogfileName(),
                            String.valueOf(entry.getHeader().getLogfileOffset()), entry.getHeader().getSchemaName(),
                            entry.getHeader().getTableName(), eventType,
                            String.valueOf(entry.getHeader().getExecuteTime()), simpleDateFormat.format(date),
                            entry.getHeader().getGtid(), String.valueOf(delayTime) });

                if (eventType == EventType.QUERY || rowChage.getIsDdl()) {
                    logger.info(" sql ----> " + rowChage.getSql() + SEP);
                    continue;
                }

                printXAInfo(rowChage.getPropsList());
                for (RowData rowData : rowChage.getRowDatasList()) {
                    if (eventType == EventType.DELETE) {
                        printColumn(rowData.getBeforeColumnsList());
                    } else if (eventType == EventType.INSERT) {
                        printColumn(rowData.getAfterColumnsList());
                    } else {
                        printColumn(rowData.getAfterColumnsList());
                    }
                }
            }
            monitor(entry);
        }
    }

    protected void printColumn(List<Column> columns) {
        for (Column column : columns) {
            StringBuilder builder = new StringBuilder();
            builder.append(column.getName() + " : " + column.getValue());
            builder.append("    type=" + column.getMysqlType());
            if (column.getUpdated()) {
                builder.append("    update=" + column.getUpdated());
            }
            builder.append(SEP);
            logger.info(builder.toString());
        }
    }

    protected void printXAInfo(List<Pair> pairs) {
        if (pairs == null) {
            return;
        }

        String xaType = null;
        String xaXid = null;
        for (Pair pair : pairs) {
            String key = pair.getKey();
            if (StringUtils.endsWithIgnoreCase(key, "XA_TYPE")) {
                xaType = pair.getValue();
            } else if (StringUtils.endsWithIgnoreCase(key, "XA_XID")) {
                xaXid = pair.getValue();
            }
        }

        if (xaType != null && xaXid != null) {
            logger.info(" ------> " + xaType + " " + xaXid);
        }
    }

    public void setConnector(CanalConnector connector) {
        this.connector = connector;
    }
    /**
     * Canal Client 监控
     * @author yi.zhang
     * @param entry		entry
     */
    public void monitor(CanalEntry.Entry entry){
    	if(entry==null)return;
    	try {
			Context ctx = etimer.time();
			try{
				if(entry.getEntryType()==EntryType.ROWDATA){
					ByteString data = entry.getStoreValue();
					RowChange store;
					try {
						store = RowChange.parseFrom(data);
					} catch (InvalidProtocolBufferException e) {
						logger.error("[Canal]parse event has error,data:"+entry.toString(),e);
						return;
					}
					if(store.getEventType()==EventType.QUERY||store.hasIsDdl()){
						logger.warn("[Canal]sql:"+store.getSql());
						return;
					}
					String database = entry.getHeader().getSchemaName();
					String table = entry.getHeader().getTableName();
					List<RowData> rows = store.getRowDatasList();
					if(rows!=null&&!rows.isEmpty()){
						for(RowData row:rows){
							long size = rtimer.getCount();
							Context rctx = rtimer.time();
					    	try{
					    		int bcc = row.getBeforeColumnsCount();
					    		int acc = row.getAfterColumnsCount();
					    		String opt = "";
					    		if(bcc>0&&acc>0){
					    			opt = "Update";
					    		}else{
					    			if(acc>0){
					    				opt = "Insert";
					    			}else{
					    				opt = "Delete";
					    			}
					    		}
					    		if(size>0&&size%10000==0){
					    			logger.info("[Canal]Client Monitor{database:"+database+",table:"+table+",opt:"+opt+"}");
					    		}
					    	}finally{
					    		rctx.close();
					    	}
						}
					}
				}
			}finally{
				ctx.close();
			}
		} catch (Exception e) {
			logger.error("[Canal]Dump Monitor Error",e);
		}
    }
}
