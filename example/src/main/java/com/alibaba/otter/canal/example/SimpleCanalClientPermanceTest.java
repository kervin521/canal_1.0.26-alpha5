package com.alibaba.otter.canal.example;
import java.net.InetSocketAddress;
import java.util.concurrent.ArrayBlockingQueue;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.alibaba.otter.canal.client.CanalConnector;
import com.alibaba.otter.canal.client.CanalConnectors;
import com.alibaba.otter.canal.client.impl.SimpleCanalConnector;
import com.alibaba.otter.canal.common.utils.AddressUtils;
import com.alibaba.otter.canal.protocol.Message;

import io.netty.util.internal.StringUtil;

public class SimpleCanalClientPermanceTest {
	protected final static Logger             logger             = LoggerFactory.getLogger(SimpleCanalClientPermanceTest.class);
    public static void main(String args[]) {
    	String destination = "example";
        if(!StringUtil.isNullOrEmpty(System.getProperty("canal.destination"))){
        	destination = System.getProperty("canal.destination");
        	logger.warn("destination:"+destination);
        }
        String ip = AddressUtils.getHostIp();
        if(!StringUtil.isNullOrEmpty(System.getProperty("canal.ip"))){
        	ip = System.getProperty("canal.ip");
        	logger.warn("ip:"+ip);
        }
        int port = 11111;
        if(!StringUtil.isNullOrEmpty(System.getProperty("canal.port"))){
        	port = Integer.valueOf(System.getProperty("canal.port"));
        	logger.warn("port:"+port);
        }
        int batchSize = 1024;
        if(!StringUtil.isNullOrEmpty(System.getProperty("canal.batch.size"))){
        	batchSize = Integer.valueOf(System.getProperty("canal.batch.size"));
        	logger.warn("batchSize:"+batchSize);
        }
        int count = 0;
        int sum = 0;
        int perSum = 0;
        long start = System.currentTimeMillis();
        long end = 0;
        final ArrayBlockingQueue<Long> queue = new ArrayBlockingQueue<Long>(100);
        try {
            final CanalConnector connector = CanalConnectors.newSingleConnector(new InetSocketAddress(ip, port),
                destination,
                "",
                "");

            Thread ackThread = new Thread(new Runnable() {

                @Override
                public void run() {
                    while (true) {
                        try {
                            long batchId = queue.take();
                            connector.ack(batchId);
                        } catch (InterruptedException e) {
                        }
                    }
                }
            });
            ackThread.start();

            ((SimpleCanalConnector) connector).setLazyParseEntry(true);
            connector.connect();
            connector.subscribe();
            while (true) {
                Message message = connector.getWithoutAck(batchSize);
                long batchId = message.getId();
                int size = message.getRawEntries().size();
                sum += size;
                perSum += size;
                count++;
                queue.add(batchId);
                if (count % 10 == 0) {
                    end = System.currentTimeMillis();
                    long tps = (perSum * 1000) / (end - start);
                    System.out.println(" total : " + sum + " , current : " + perSum + " , cost : " + (end - start)
                                       + " , tps : " + tps);
                    start = end;
                    perSum = 0;
                }
            }
        } catch (Throwable e) {
            e.printStackTrace();
        }
    }

}
