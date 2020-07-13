/*
 * Copyright 2012 Netflix, Inc.
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */
package com.netflix.eureka.util;

import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Utility class for getting a count in last X milliseconds.
 *
 * @author Karthik Ranganathan,Greg Kim
 */
public class MeasuredRate {
    private static final Logger logger = LoggerFactory.getLogger(MeasuredRate.class);
    /**
     * 上一个间隔次数
     */
    private final AtomicLong lastBucket = new AtomicLong(0);
    /**
     * 当前间隔次数
     */
    private final AtomicLong currentBucket = new AtomicLong(0);
    /**
     * 间隔
     */
    private final long sampleInterval;
    /**
     * 定时器
     */
    private final Timer timer;

    private volatile boolean isActive;

    /**
     * @param sampleInterval in milliseconds
     */
    public MeasuredRate(long sampleInterval) {
        this.sampleInterval = sampleInterval;
        this.timer = new Timer("Eureka-MeasureRateTimer", true);
        this.isActive = false;
    }

    public synchronized void start() {
        if (!isActive) {
            timer.schedule(new TimerTask() {

                @Override
                public void run() {
                    try {
                        // Zero out the current bucket.
                        //lastBucket: 记录上一分钟总心跳次数
                        // currentBucket: 记录当前最近一分钟总心跳次数
                        // renewsLastMin 为1分钟
                        // 每分钟调度一次，将当前的88次总心跳设置到lastBucket中去，然后将当前的currentBucket 设置为0 秒啊
                        // 每分钟调度一次，把当前一分钟总心跳时间赋值给上一分钟总心跳时间，然后将当前一分钟总心跳时间置为0.
                        lastBucket.set(currentBucket.getAndSet(0));
                    } catch (Throwable e) {
                        logger.error("Cannot reset the Measured Rate", e);
                    }
                }
            }, sampleInterval, sampleInterval);

            isActive = true;
        }
    }

    public synchronized void stop() {
        if (isActive) {
            timer.cancel();
            isActive = false;
        }
    }

    /**
     * Returns the count in the last sample interval.
     */
    public long getCount() {
        return lastBucket.get();
    }

    /**
     * Increments the count in the current sample interval.
     */
    public void increment() {
        currentBucket.incrementAndGet();
    }
}
