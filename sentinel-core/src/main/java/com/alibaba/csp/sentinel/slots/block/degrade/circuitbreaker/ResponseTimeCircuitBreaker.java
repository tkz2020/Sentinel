/*
 * Copyright 1999-2019 Alibaba Group Holding Ltd.
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
package com.alibaba.csp.sentinel.slots.block.degrade.circuitbreaker;

import java.util.List;

import com.alibaba.csp.sentinel.Entry;
import com.alibaba.csp.sentinel.context.Context;
import com.alibaba.csp.sentinel.slots.block.RuleConstant;
import com.alibaba.csp.sentinel.slots.block.degrade.DegradeRule;
import com.alibaba.csp.sentinel.slots.statistic.base.LeapArray;
import com.alibaba.csp.sentinel.slots.statistic.base.LongAdder;
import com.alibaba.csp.sentinel.slots.statistic.base.WindowWrap;
import com.alibaba.csp.sentinel.util.AssertUtil;
import com.alibaba.csp.sentinel.util.TimeUtil;

/**
 * @author Eric Zhao
 * @since 1.8.0
 */
public class ResponseTimeCircuitBreaker extends AbstractCircuitBreaker {

    private static final double SLOW_REQUEST_RATIO_MAX_VALUE = 1.0d;

    private final long maxAllowedRt;
    private final double maxSlowRequestRatio;
    private final int minRequestAmount;

    private final LeapArray<SlowRequestCounter> slidingCounter;

    public ResponseTimeCircuitBreaker(DegradeRule rule) {
        this(rule, new SlowRequestLeapArray(1, rule.getStatIntervalMs()));
    }

    ResponseTimeCircuitBreaker(DegradeRule rule, LeapArray<SlowRequestCounter> stat) {
        super(rule);
        AssertUtil.isTrue(rule.getGrade() == RuleConstant.DEGRADE_GRADE_RT, "rule metric type should be RT");
        AssertUtil.notNull(stat, "stat cannot be null");
        this.maxAllowedRt = Math.round(rule.getCount());
        this.maxSlowRequestRatio = rule.getSlowRatioThreshold();
        this.minRequestAmount = rule.getMinRequestAmount();
        this.slidingCounter = stat;
    }

    @Override
    public void resetStat() {
        // Reset current bucket (bucket count = 1).
        slidingCounter.currentWindow().value().reset();
    }

    @Override
    public void onRequestComplete(Context context) {
        SlowRequestCounter counter = slidingCounter.currentWindow().value();
        Entry entry = context.getCurEntry();
        if (entry == null) {
            return;
        }
        long completeTime = entry.getCompleteTimestamp();
        if (completeTime <= 0) {
            completeTime = TimeUtil.currentTimeMillis();
        }
        long rt = completeTime - entry.getCreateTimestamp();
        if (rt > maxAllowedRt) {
            counter.slowCount.add(1);
        }
        counter.totalCount.add(1);

        handleStateChangeWhenThresholdExceeded(rt);
    }

    private void handleStateChangeWhenThresholdExceeded(long rt) {
        //如果熔断器开启，拦截所有请求
        if (currentState.get() == State.OPEN) {
            return;
        }
        
        if (currentState.get() == State.HALF_OPEN) {
            //如果是半开启状态，根据接下来的一个请求判断
            // In detecting request
            // TODO: improve logic for half-open recovery
            if (rt > maxAllowedRt) {
                //请求RT大于设置的阈值，熔断状态由半开启转为开启状态
                fromHalfOpenToOpen(1.0d);
            } else {
                //请求RT小于设置的阈值，熔断状态由半开启转为关闭状态
                fromHalfOpenToClose();
            }
            return;
        }

        //下面熔断器状态为关闭状态
        List<SlowRequestCounter> counters = slidingCounter.values();
        long slowCount = 0;
        long totalCount = 0;
        for (SlowRequestCounter counter : counters) {
            //统计慢调用数量和总调用数量
            slowCount += counter.slowCount.sum();
            totalCount += counter.totalCount.sum();
        }

        //总调用数量小于最小请求阈值，不做熔断。
        if (totalCount < minRequestAmount) {
            return;
        }

        //慢调用比例大于阈值，熔断状态由关闭变为开启状态
        double currentRatio = slowCount * 1.0d / totalCount;
        if (currentRatio > maxSlowRequestRatio) {
            transformToOpen(currentRatio);
        }
        if (Double.compare(currentRatio, maxSlowRequestRatio) == 0 &&
                Double.compare(maxSlowRequestRatio, SLOW_REQUEST_RATIO_MAX_VALUE) == 0) {
            transformToOpen(currentRatio);
        }
    }

    static class SlowRequestCounter {
        private LongAdder slowCount;
        private LongAdder totalCount;

        public SlowRequestCounter() {
            this.slowCount = new LongAdder();
            this.totalCount = new LongAdder();
        }

        public LongAdder getSlowCount() {
            return slowCount;
        }

        public LongAdder getTotalCount() {
            return totalCount;
        }

        public SlowRequestCounter reset() {
            slowCount.reset();
            totalCount.reset();
            return this;
        }

        @Override
        public String toString() {
            return "SlowRequestCounter{" +
                "slowCount=" + slowCount +
                ", totalCount=" + totalCount +
                '}';
        }
    }

    static class SlowRequestLeapArray extends LeapArray<SlowRequestCounter> {

        public SlowRequestLeapArray(int sampleCount, int intervalInMs) {
            super(sampleCount, intervalInMs);
        }

        @Override
        public SlowRequestCounter newEmptyBucket(long timeMillis) {
            return new SlowRequestCounter();
        }

        @Override
        protected WindowWrap<SlowRequestCounter> resetWindowTo(WindowWrap<SlowRequestCounter> w, long startTime) {
            w.resetTo(startTime);
            w.value().reset();
            return w;
        }
    }
}
