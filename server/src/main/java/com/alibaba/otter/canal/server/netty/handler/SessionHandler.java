package com.alibaba.otter.canal.server.netty.handler;

import java.util.List;
import java.util.concurrent.TimeUnit;

import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang.exception.ExceptionUtils;
import org.jboss.netty.buffer.ChannelBuffer;
import org.jboss.netty.channel.ChannelHandlerContext;
import org.jboss.netty.channel.ChannelStateEvent;
import org.jboss.netty.channel.ExceptionEvent;
import org.jboss.netty.channel.MessageEvent;
import org.jboss.netty.channel.SimpleChannelHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.slf4j.helpers.MessageFormatter;
import org.springframework.util.CollectionUtils;

import com.alibaba.otter.canal.common.zookeeper.running.ServerRunningMonitor;
import com.alibaba.otter.canal.common.zookeeper.running.ServerRunningMonitors;
import com.alibaba.otter.canal.protocol.CanalEntry.Entry;
import com.alibaba.otter.canal.protocol.CanalEntry.EntryType;
import com.alibaba.otter.canal.protocol.CanalEntry.EventType;
import com.alibaba.otter.canal.protocol.CanalEntry.RowChange;
import com.alibaba.otter.canal.protocol.CanalEntry.RowData;
import com.alibaba.otter.canal.protocol.CanalEntry;
import com.alibaba.otter.canal.protocol.CanalPacket;
import com.alibaba.otter.canal.protocol.CanalPacket.ClientAck;
import com.alibaba.otter.canal.protocol.CanalPacket.ClientRollback;
import com.alibaba.otter.canal.protocol.CanalPacket.Get;
import com.alibaba.otter.canal.protocol.CanalPacket.Messages;
import com.alibaba.otter.canal.protocol.CanalPacket.Packet;
import com.alibaba.otter.canal.protocol.CanalPacket.PacketType;
import com.alibaba.otter.canal.protocol.CanalPacket.Sub;
import com.alibaba.otter.canal.protocol.CanalPacket.Unsub;
import com.alibaba.otter.canal.protocol.ClientIdentity;
import com.alibaba.otter.canal.protocol.Message;
import com.alibaba.otter.canal.server.embedded.CanalServerWithEmbedded;
import com.alibaba.otter.canal.server.netty.NettyUtils;
import com.codahale.metrics.MetricsHolder;
import com.codahale.metrics.Timer;
import com.codahale.metrics.Timer.Context;
import com.google.protobuf.ByteString;
import com.google.protobuf.CodedOutputStream;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.WireFormat;

public class SessionHandler extends SimpleChannelHandler {

    private static final Logger     logger = LoggerFactory.getLogger(SessionHandler.class);
    private CanalServerWithEmbedded embeddedServer;
    private final Timer etimer = MetricsHolder.timer("Client-Entry-Timer");
    private final Timer rtimer = MetricsHolder.timer("Client-Row-Timer");
    public SessionHandler(){
    }

    public SessionHandler(CanalServerWithEmbedded embeddedServer){
        this.embeddedServer = embeddedServer;
    }

    public void messageReceived(ChannelHandlerContext ctx, MessageEvent e) throws Exception {
        logger.info("message receives in session handler...");
        ChannelBuffer buffer = (ChannelBuffer) e.getMessage();
        Packet packet = Packet.parseFrom(buffer.readBytes(buffer.readableBytes()).array());
        ClientIdentity clientIdentity = null;
        try {
            switch (packet.getType()) {
                case SUBSCRIPTION:
                    Sub sub = Sub.parseFrom(packet.getBody());
                    if (StringUtils.isNotEmpty(sub.getDestination()) && StringUtils.isNotEmpty(sub.getClientId())) {
                        clientIdentity = new ClientIdentity(sub.getDestination(),
                            Short.valueOf(sub.getClientId()),
                            sub.getFilter());
                        MDC.put("destination", clientIdentity.getDestination());

                        // 尝试启动，如果已经启动，忽略
                        if (!embeddedServer.isStart(clientIdentity.getDestination())) {
                            ServerRunningMonitor runningMonitor = ServerRunningMonitors.getRunningMonitor(clientIdentity.getDestination());
                            if (!runningMonitor.isStart()) {
                                runningMonitor.start();
                            }
                        }

                        embeddedServer.subscribe(clientIdentity);
                        // ctx.setAttachment(clientIdentity);// 设置状态数据
                        NettyUtils.ack(ctx.getChannel(), null);
                    } else {
                        NettyUtils.error(401,
                            MessageFormatter.format("destination or clientId is null", sub.toString()).getMessage(),
                            ctx.getChannel(),
                            null);
                    }
                    break;
                case UNSUBSCRIPTION:
                    Unsub unsub = Unsub.parseFrom(packet.getBody());
                    if (StringUtils.isNotEmpty(unsub.getDestination()) && StringUtils.isNotEmpty(unsub.getClientId())) {
                        clientIdentity = new ClientIdentity(unsub.getDestination(),
                            Short.valueOf(unsub.getClientId()),
                            unsub.getFilter());
                        MDC.put("destination", clientIdentity.getDestination());
                        embeddedServer.unsubscribe(clientIdentity);
                        stopCanalInstanceIfNecessary(clientIdentity);// 尝试关闭
                        NettyUtils.ack(ctx.getChannel(), null);
                    } else {
                        NettyUtils.error(401,
                            MessageFormatter.format("destination or clientId is null", unsub.toString()).getMessage(),
                            ctx.getChannel(),
                            null);
                    }
                    break;
                case GET:
                    Get get = CanalPacket.Get.parseFrom(packet.getBody());
                    if (StringUtils.isNotEmpty(get.getDestination()) && StringUtils.isNotEmpty(get.getClientId())) {
                        clientIdentity = new ClientIdentity(get.getDestination(), Short.valueOf(get.getClientId()));
                        MDC.put("destination", clientIdentity.getDestination());
                        Message message = null;

                        // if (get.getAutoAck()) {
                        // if (get.getTimeout() == -1) {//是否是初始值
                        // message = embeddedServer.get(clientIdentity,
                        // get.getFetchSize());
                        // } else {
                        // TimeUnit unit = convertTimeUnit(get.getUnit());
                        // message = embeddedServer.get(clientIdentity,
                        // get.getFetchSize(), get.getTimeout(), unit);
                        // }
                        // } else {
                        if (get.getTimeout() == -1) {// 是否是初始值
                            message = embeddedServer.getWithoutAck(clientIdentity, get.getFetchSize());
                        } else {
                            TimeUnit unit = convertTimeUnit(get.getUnit());
                            message = embeddedServer.getWithoutAck(clientIdentity,
                                get.getFetchSize(),
                                get.getTimeout(),
                                unit);
                        }
                        // }

                        if (message.getId() != -1 && message.isRaw()) {
                            List<ByteString> rowEntries = message.getRawEntries();
                            // message size
                            int messageSize = 0;
                            messageSize += com.google.protobuf.CodedOutputStream.computeInt64Size(1, message.getId());

                            int dataSize = 0;
                            for (int i = 0; i < rowEntries.size(); i++) {
                                dataSize += com.google.protobuf.CodedOutputStream.computeBytesSizeNoTag(rowEntries.get(i));
                            }
                            messageSize += dataSize;
                            messageSize += 1 * rowEntries.size();
                            // packet size
                            int size = 0;
                            size += com.google.protobuf.CodedOutputStream.computeEnumSize(3,
                                PacketType.MESSAGES.getNumber());
                            size += com.google.protobuf.CodedOutputStream.computeTagSize(5)
                                    + com.google.protobuf.CodedOutputStream.computeRawVarint32Size(messageSize)
                                    + messageSize;
                            // recyle bytes
                            // ByteBuffer byteBuffer = (ByteBuffer)
                            // ctx.getAttachment();
                            // if (byteBuffer != null && size <=
                            // byteBuffer.capacity()) {
                            // byteBuffer.clear();
                            // } else {
                            // byteBuffer =
                            // ByteBuffer.allocate(size).order(ByteOrder.BIG_ENDIAN);
                            // ctx.setAttachment(byteBuffer);
                            // }
                            // CodedOutputStream output =
                            // CodedOutputStream.newInstance(byteBuffer);
                            byte[] body = new byte[size];
                            CodedOutputStream output = CodedOutputStream.newInstance(body);
                            output.writeEnum(3, PacketType.MESSAGES.getNumber());

                            output.writeTag(5, WireFormat.WIRETYPE_LENGTH_DELIMITED);
                            output.writeRawVarint32(messageSize);
                            // message
                            output.writeInt64(1, message.getId());
                            for (int i = 0; i < rowEntries.size(); i++) {
                            	ByteString row = rowEntries.get(i);
                                output.writeBytes(2, row);
                                Entry entry = CanalEntry.Entry.parseFrom(row);
                                monitor(entry);
                            }
                            output.checkNoSpaceLeft();
                            NettyUtils.write(ctx.getChannel(), body, null);
                            
                            // output.flush();
                            // byteBuffer.flip();
                            // NettyUtils.write(ctx.getChannel(), byteBuffer,
                            // null);
                        } else {
                            Packet.Builder packetBuilder = CanalPacket.Packet.newBuilder();
                            packetBuilder.setType(PacketType.MESSAGES);

                            Messages.Builder messageBuilder = CanalPacket.Messages.newBuilder();
                            messageBuilder.setBatchId(message.getId());
                            if (message.getId() != -1) {
                                if (message.isRaw() && !CollectionUtils.isEmpty(message.getRawEntries())) {
                                    messageBuilder.addAllMessages(message.getRawEntries());
                                } else if (!CollectionUtils.isEmpty(message.getEntries())) {
                                    for (Entry entry : message.getEntries()) {
                                        messageBuilder.addMessages(entry.toByteString());
                                        monitor(entry);
                                    }
                                }
                            }
                            packetBuilder.setBody(messageBuilder.build().toByteString());
                            NettyUtils.write(ctx.getChannel(), packetBuilder.build().toByteArray(), null);// 输出数据
                        }
                    } else {
                        NettyUtils.error(401,
                            MessageFormatter.format("destination or clientId is null", get.toString()).getMessage(),
                            ctx.getChannel(),
                            null);
                    }
                    break;
                case CLIENTACK:
                    ClientAck ack = CanalPacket.ClientAck.parseFrom(packet.getBody());
                    MDC.put("destination", ack.getDestination());
                    if (StringUtils.isNotEmpty(ack.getDestination()) && StringUtils.isNotEmpty(ack.getClientId())) {
                        if (ack.getBatchId() == 0L) {
                            NettyUtils.error(402,
                                MessageFormatter.format("batchId should assign value", ack.toString()).getMessage(),
                                ctx.getChannel(),
                                null);
                        } else if (ack.getBatchId() == -1L) { // -1代表上一次get没有数据，直接忽略之
                            // donothing
                        } else {
                            clientIdentity = new ClientIdentity(ack.getDestination(), Short.valueOf(ack.getClientId()));
                            embeddedServer.ack(clientIdentity, ack.getBatchId());
                        }
                    } else {
                        NettyUtils.error(401,
                            MessageFormatter.format("destination or clientId is null", ack.toString()).getMessage(),
                            ctx.getChannel(),
                            null);
                    }
                    break;
                case CLIENTROLLBACK:
                    ClientRollback rollback = CanalPacket.ClientRollback.parseFrom(packet.getBody());
                    MDC.put("destination", rollback.getDestination());
                    if (StringUtils.isNotEmpty(rollback.getDestination())
                        && StringUtils.isNotEmpty(rollback.getClientId())) {
                        clientIdentity = new ClientIdentity(rollback.getDestination(),
                            Short.valueOf(rollback.getClientId()));
                        if (rollback.getBatchId() == 0L) {
                            embeddedServer.rollback(clientIdentity);// 回滚所有批次
                        } else {
                            embeddedServer.rollback(clientIdentity, rollback.getBatchId()); // 只回滚单个批次
                        }
                    } else {
                        NettyUtils.error(401,
                            MessageFormatter.format("destination or clientId is null", rollback.toString())
                                .getMessage(),
                            ctx.getChannel(),
                            null);
                    }
                    break;
                default:
                    NettyUtils.error(400, MessageFormatter.format("packet type={} is NOT supported!", packet.getType())
                        .getMessage(), ctx.getChannel(), null);
                    break;
            }
        } catch (Throwable exception) {
            NettyUtils.error(400,
                MessageFormatter.format("something goes wrong with channel:{}, exception={}",
                    ctx.getChannel(),
                    ExceptionUtils.getStackTrace(exception)).getMessage(),
                ctx.getChannel(),
                null);
        } finally {
            MDC.remove("destination");
        }
    }

    public void exceptionCaught(ChannelHandlerContext ctx, ExceptionEvent e) throws Exception {
        logger.error("something goes wrong with channel:{}, exception={}",
            ctx.getChannel(),
            ExceptionUtils.getStackTrace(e.getCause()));

        ctx.getChannel().close();
    }

    public void channelClosed(ChannelHandlerContext ctx, ChannelStateEvent e) throws Exception {
        // logger.info("remove binding subscription value object if any...");
        // ClientIdentity clientIdentity = (ClientIdentity) ctx.getAttachment();
        // // 如果唯一的订阅者都取消了订阅，直接关闭服务，针对内部版本模式下可以减少资源浪费
        // if (clientIdentity != null) {
        // stopCanalInstanceIfNecessary(clientIdentity);
        // }
    }

    private void stopCanalInstanceIfNecessary(ClientIdentity clientIdentity) {
        List<ClientIdentity> clientIdentitys = embeddedServer.listAllSubscribe(clientIdentity.getDestination());
        if (clientIdentitys != null && clientIdentitys.size() == 1 && clientIdentitys.contains(clientIdentity)) {
            ServerRunningMonitor runningMonitor = ServerRunningMonitors.getRunningMonitor(clientIdentity.getDestination());
            if (runningMonitor.isStart()) {
                runningMonitor.release();
            }
        }
    }

    private TimeUnit convertTimeUnit(int unit) {
        switch (unit) {
            case 0:
                return TimeUnit.NANOSECONDS;
            case 1:
                return TimeUnit.MICROSECONDS;
            case 2:
                return TimeUnit.MILLISECONDS;
            case 3:
                return TimeUnit.SECONDS;
            case 4:
                return TimeUnit.MINUTES;
            case 5:
                return TimeUnit.HOURS;
            case 6:
                return TimeUnit.DAYS;
            default:
                return TimeUnit.MILLISECONDS;
        }
    }

    public void setEmbeddedServer(CanalServerWithEmbedded embeddedServer) {
        this.embeddedServer = embeddedServer;
    }
    /**
     * Canal Client 监控
     * @author yi.zhang
     * @param binlog	binlog
     * @param postion	postion
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
