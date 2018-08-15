package com.alibaba.otter.canal.example;

import java.net.InetSocketAddress;

import com.alibaba.otter.canal.client.CanalConnector;
import com.alibaba.otter.canal.client.CanalConnectors;
import com.alibaba.otter.canal.common.utils.AddressUtils;

import io.netty.util.internal.StringUtil;

/**
 * 单机模式的测试例子
 * 
 * @author jianghang 2013-4-15 下午04:19:20
 * @version 1.0.4
 */
public class SimpleCanalClientTest extends AbstractCanalClientTest {

    public SimpleCanalClientTest(String destination){
        super(destination);
    }

    public static void main(String args[]) {
        // 根据ip，直接创建链接，无HA的功能
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
        CanalConnector connector = CanalConnectors.newSingleConnector(new InetSocketAddress(ip, port),
            destination,
            "",
            "");

        final SimpleCanalClientTest clientTest = new SimpleCanalClientTest(destination);
        clientTest.setConnector(connector);
        clientTest.start();
        Runtime.getRuntime().addShutdownHook(new Thread() {

            public void run() {
                try {
                    logger.info("## stop the canal client");
                    clientTest.stop();
                } catch (Throwable e) {
                    logger.warn("##something goes wrong when stopping canal:", e);
                } finally {
                    logger.info("## canal client is down.");
                }
            }

        });
    }

}
