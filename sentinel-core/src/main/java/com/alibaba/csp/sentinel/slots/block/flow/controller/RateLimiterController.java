/*
 * Copyright 1999-2018 Alibaba Group Holding Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.alibaba.csp.sentinel.slots.block.flow.controller;

import java.util.concurrent.atomic.AtomicLong;

import com.alibaba.csp.sentinel.slots.block.flow.TrafficShapingController;

import com.alibaba.csp.sentinel.util.TimeUtil;
import com.alibaba.csp.sentinel.node.Node;

/**
 * 匀速排队（漏桶算法实现）
 * @author jialiang.linjl
 */
public class RateLimiterController implements TrafficShapingController {

    //排队等待的最大超时时间，如果等待时间超过该时间，就会抛出FlowException
    private final int maxQueueingTimeMs;
    //设置的阈值，以QPS为例，即1s中可以通过的数量
    private final double count;

    //上一次成功通过的时间戳
    private final AtomicLong latestPassedTime = new AtomicLong(-1);

    public RateLimiterController(int timeOut, double count) {
        this.maxQueueingTimeMs = timeOut;
        this.count = count;
    }

    @Override
    public boolean canPass(Node node, int acquireCount) {
        return canPass(node, acquireCount, false);
    }

    @Override
    public boolean canPass(Node node, int acquireCount, boolean prioritized) {
        // Pass when acquire count is less or equal than 0.
        if (acquireCount <= 0) {
            return true;
        }
        // Reject when count is less or equal than 0.
        // Otherwise,the costTime will be max of long and waitTime will overflow in some cases.
        if (count <= 0) {
            return false;
        }

        long currentTime = TimeUtil.currentTimeMillis();
        // Calculate the interval between every two requests.
        // 根据本次请求的令牌数计算两个请求的间隔时间
        // 1.0 / count * 10000 即一个令牌消耗的时间（毫秒）， 再乘以所需的令牌数，得到本次请求要间隔的时间
        long costTime = Math.round(1.0 * (acquireCount) / count * 1000);

        // Expected pass time of this request.
        // 计算该请求所期望到达时间 = 上一次通过的时间 + 获取令牌的时间
        long expectedTime = costTime + latestPassedTime.get();

        //如果小于当前时间，没有超出阈值，
        if (expectedTime <= currentTime) {
            // Contention may exist here, but it's okay.
            // 此处可能存在争论，但是没关系。更新上次通过的时间为当前时间
            latestPassedTime.set(currentTime);
            return true;
        } else {
            // Calculate the time to wait.
            // 如果expectedTime大于当前时间，说明还没到令牌发放时间，当前请求需等待, 因为每隔一个时间产生一个令牌，超过了当前时间，说明期间产生的令牌不够
            long waitTime = costTime + latestPassedTime.get() - TimeUtil.currentTimeMillis();
            // 计算需要等待的时间超过了最大排队等待时间，就拒绝通过，并抛出FlowException异常
            if (waitTime > maxQueueingTimeMs) {
                return false;
            } else {
                // 更新时间，CAS操作，可能会出现争抢，导致多次重试
                long oldTime = latestPassedTime.addAndGet(costTime);
                try {
                    // 所以还要判断等待时间
                    waitTime = oldTime - TimeUtil.currentTimeMillis();
                    if (waitTime > maxQueueingTimeMs) {
                        latestPassedTime.addAndGet(-costTime);
                        return false;
                    }
                    // in race condition waitTime may <= 0
                    if (waitTime > 0) {
                        Thread.sleep(waitTime);
                    }
                    return true;
                } catch (InterruptedException e) {
                }
            }
        }
        return false;
    }

}
