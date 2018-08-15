package com.alibaba.otter.canal.deployer;

import java.io.FileInputStream;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

import org.apache.commons.lang.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.codahale.metrics.ConsoleReporter;
import com.codahale.metrics.MetricsHolder;

/**
 * canal独立版本启动的入口类
 * 
 * @author jianghang 2012-11-6 下午05:20:49
 * @version 1.0.0
 */
public class CanalLauncher {

    private static final String CLASSPATH_URL_PREFIX = "classpath:";
    private static final Logger logger               = LoggerFactory.getLogger(CanalLauncher.class);

    public static void main(String[] args) throws Throwable {
        try {
            logger.info("## set default uncaught exception handler");
            setGlobalUncaughtExceptionHandler();

            logger.info("## load canal configurations");
            String conf = System.getProperty("canal.conf", "classpath:canal.properties");
            Properties properties = new Properties();
            if (conf.startsWith(CLASSPATH_URL_PREFIX)) {
                conf = StringUtils.substringAfter(conf, CLASSPATH_URL_PREFIX);
                properties.load(CanalLauncher.class.getClassLoader().getResourceAsStream(conf));
            } else {
                properties.load(new FileInputStream(conf));
            }
            final ConsoleReporter console = MetricsHolder.console();
            console.start(5, TimeUnit.SECONDS);
            logger.info("## start the canal server.");
            final CanalController controller = new CanalController(properties);
            controller.start();
            logger.info("## the canal server is running now ......");
            Runtime.getRuntime().addShutdownHook(new Thread() {

                public void run() {
                    try {
                        logger.info("## stop the canal server");
                        controller.stop();
                        console.stop();
                    } catch (Throwable e) {
                        logger.warn("##something goes wrong when stopping canal Server:", e);
                    } finally {
                        logger.info("## canal server is down.");
                    }
                }

            });
        } catch (Throwable e) {
            logger.error("## Something goes wrong when starting up the canal Server:", e);
            System.exit(0);
        }
    }

    private static void setGlobalUncaughtExceptionHandler() {
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {

            @Override
            public void uncaughtException(Thread t, Throwable e) {
                logger.error("UnCaughtException", e);
            }
        });
    }
}
