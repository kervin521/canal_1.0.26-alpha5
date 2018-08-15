package com.alibaba.otter.canal.example;

import com.alibaba.otter.canal.client.CanalConnector;
import com.alibaba.otter.canal.client.CanalConnectors;

import io.netty.util.internal.StringUtil;

/**
 * 集群模式的测试例子
 * 
 * @author jianghang 2013-4-15 下午04:19:20
 * @version 1.0.4
 */
public class ClusterCanalClientTest extends AbstractCanalClientTest {

    public ClusterCanalClientTest(String destination){
        super(destination);
    }

    public static void main(String args[]) {
        String destination = "example";
        if(!StringUtil.isNullOrEmpty(System.getProperty("canal.destination"))){
        	destination = System.getProperty("canal.destination");
        	logger.warn("destination:"+destination);
        }
        String zookeeper = "127.0.0.1:2181";
        if(!StringUtil.isNullOrEmpty(System.getProperty("canal.zookeeper"))){
        	zookeeper = System.getProperty("canal.zookeeper");
        	logger.warn("zookeeper:"+zookeeper);
        }
        // 基于固定canal server的地址，建立链接，其中一台server发生crash，可以支持failover
        // CanalConnector connector = CanalConnectors.newClusterConnector(
        // Arrays.asList(new InetSocketAddress(
        // AddressUtils.getHostIp(),
        // 11111)),
        // "stability_test", "", "");

        // 基于zookeeper动态获取canal server的地址，建立链接，其中一台server发生crash，可以支持failover
        CanalConnector connector = CanalConnectors.newClusterConnector(zookeeper, destination, "", "");

        final ClusterCanalClientTest clientTest = new ClusterCanalClientTest(destination);
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
