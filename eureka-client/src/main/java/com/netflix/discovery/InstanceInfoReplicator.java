package com.netflix.discovery;

import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.netflix.appinfo.InstanceInfo;
import com.netflix.discovery.util.RateLimiter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A task for updating and replicating the local instanceinfo to the remote server. Properties of this task are:
 * - configured with a single update thread to guarantee sequential update to the remote server
 * - update tasks can be scheduled on-demand via onDemandUpdate()
 * - task processing is rate limited by burstSize
 * - a new update task is always scheduled automatically after an earlier update task. However if an on-demand task
 *   is started, the scheduled automatic update task is discarded (and a new one will be scheduled after the new
 *   on-demand update).
 *
 *   @author dliu
 *
 *   用于将本地instanceinfo更新和复制到远程服务器的任务。此任务的属性是：
 *   -配置有单个更新线程以保证对远程服务器的顺序更新
 *   -可以通过onDemandUpdate（）按需调度更新任务
 *   -任务处理的速率受burstSize的限制
 *   -新更新总是在较早的更新任务之后自动计划任务。但是，如果启动了按需任务*，则计划的自动更新任务将被丢弃（并且将在新的按需更新之后安排新的任务）
 */
class InstanceInfoReplicator implements Runnable {
    private static final Logger logger = LoggerFactory.getLogger(InstanceInfoReplicator.class);

    private final DiscoveryClient discoveryClient;
    /**
     * 应用实例信息
     */
    private final InstanceInfo instanceInfo;
    /**
     * 定时执行频率，单位：秒
     */
    private final int replicationIntervalSeconds;
    /**
     * 定时执行器
     */
    private final ScheduledExecutorService scheduler;
    /**
     * 定时执行任务的 Future
     */
    private final AtomicReference<Future> scheduledPeriodicRef;
    /**
     * 是否开启调度
     */
    private final AtomicBoolean started;
    /**
     * // 限流相关，跳过
     */
    private final RateLimiter rateLimiter;
    /**
     * // 限流相关，跳过
     */
    private final int burstSize;
    /**
     * // 限流相关，跳过
     */
    private final int allowedRatePerMinute;

    InstanceInfoReplicator(DiscoveryClient discoveryClient, InstanceInfo instanceInfo, int replicationIntervalSeconds, int burstSize) {
        this.discoveryClient = discoveryClient;
        this.instanceInfo = instanceInfo;
        this.scheduler = Executors.newScheduledThreadPool(1,
                new ThreadFactoryBuilder()
                        .setNameFormat("DiscoveryClient-InstanceInfoReplicator-%d")
                        .setDaemon(true)
                        .build());

        this.scheduledPeriodicRef = new AtomicReference<Future>();

        this.started = new AtomicBoolean(false);
        this.rateLimiter = new RateLimiter(TimeUnit.MINUTES);
        this.replicationIntervalSeconds = replicationIntervalSeconds;
        this.burstSize = burstSize;

        this.allowedRatePerMinute = 60 * this.burstSize / this.replicationIntervalSeconds;
        logger.info("InstanceInfoReplicator onDemand update allowed rate per min is {}", allowedRatePerMinute);
    }

    public void start(int initialDelayMs) {
        if (started.compareAndSet(false, true)) {
            // 设置 应用实例信息 数据不一致
            instanceInfo.setIsDirty();  // for initial register
            //调度任务线程池
            Future next = scheduler.schedule(this, initialDelayMs, TimeUnit.SECONDS);
            //将this线程放入到线程池中
            scheduledPeriodicRef.set(next);
        }
    }

    public void stop() {
        scheduler.shutdownNow();
        started.set(false);
    }

    public boolean onDemandUpdate() {
        // 限流相关，跳过
        if (rateLimiter.acquire(burstSize, allowedRatePerMinute)) {
            if (!scheduler.isShutdown()) {
                scheduler.submit(new Runnable() {
                    @Override
                    public void run() {
                        logger.debug("Executing on-demand update of local InstanceInfo");
                        // 取消任务
                        Future latestPeriodic = scheduledPeriodicRef.get();
                        if (latestPeriodic != null && !latestPeriodic.isDone()) {
                            logger.debug("Canceling the latest scheduled update, it will be rescheduled at the end of on demand update");
                            //取消定时任务，避免无用的注册。
                            latestPeriodic.cancel(false);
                        }

                        // 再次调用
                        InstanceInfoReplicator.this.run();
                    }
                });
                return true;
            } else {
                logger.warn("Ignoring onDemand update due to stopped scheduler");
                return false;
            }
        } else {
            logger.warn("Ignoring onDemand update due to rate limiter");
            return false;
        }
    }

    public void run() {
        try {
            // 刷新一下服务实例信息
            discoveryClient.refreshInstanceInfo();
            // 判断 应用实例信息 是否数据不一致
            Long dirtyTimestamp = instanceInfo.isDirtyWithTime();
            if (dirtyTimestamp != null) {
                // 发起注册
                discoveryClient.register();
                // 设置 应用实例信息 数据一致
                instanceInfo.unsetIsDirty(dirtyTimestamp);
            }
        } catch (Throwable t) {
            logger.warn("There was a problem with the instance info replicator", t);
        } finally {
            // 提交任务，并设置该任务的 Future
            Future next = scheduler.schedule(this, replicationIntervalSeconds, TimeUnit.SECONDS);
            scheduledPeriodicRef.set(next);
        }
    }

}