package com.reactor.asl.spring.boot;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("asl.async.mapdb")
public class AslAsyncMapDbProperties {
    private boolean enabled;
    private String path = "./data/asl-queue.db";
    private String codec = "java-object-stream";
    private long workerShutdownAwaitMillis = 10_000L;
    private long registrationIdleSleepMillis = 100L;
    private long emptyQueueSleepMillis = 50L;
    private long requeueDelayMillis = 75L;
    private String recoveredInProgressMessage = "Recovered stale in-progress invocation after restart";
    private boolean transactionsEnabled = true;
    private boolean memoryMappedEnabled;
    private boolean resetIfCorrupt;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }

    public String getCodec() {
        return codec;
    }

    public void setCodec(String codec) {
        this.codec = codec;
    }

    public long getWorkerShutdownAwaitMillis() {
        return workerShutdownAwaitMillis;
    }

    public void setWorkerShutdownAwaitMillis(long workerShutdownAwaitMillis) {
        this.workerShutdownAwaitMillis = workerShutdownAwaitMillis;
    }

    public long getRegistrationIdleSleepMillis() {
        return registrationIdleSleepMillis;
    }

    public void setRegistrationIdleSleepMillis(long registrationIdleSleepMillis) {
        this.registrationIdleSleepMillis = registrationIdleSleepMillis;
    }

    public long getEmptyQueueSleepMillis() {
        return emptyQueueSleepMillis;
    }

    public void setEmptyQueueSleepMillis(long emptyQueueSleepMillis) {
        this.emptyQueueSleepMillis = emptyQueueSleepMillis;
    }

    public long getRequeueDelayMillis() {
        return requeueDelayMillis;
    }

    public void setRequeueDelayMillis(long requeueDelayMillis) {
        this.requeueDelayMillis = requeueDelayMillis;
    }

    public String getRecoveredInProgressMessage() {
        return recoveredInProgressMessage;
    }

    public void setRecoveredInProgressMessage(String recoveredInProgressMessage) {
        this.recoveredInProgressMessage = recoveredInProgressMessage;
    }

    public boolean isTransactionsEnabled() {
        return transactionsEnabled;
    }

    public void setTransactionsEnabled(boolean transactionsEnabled) {
        this.transactionsEnabled = transactionsEnabled;
    }

    public boolean isMemoryMappedEnabled() {
        return memoryMappedEnabled;
    }

    public void setMemoryMappedEnabled(boolean memoryMappedEnabled) {
        this.memoryMappedEnabled = memoryMappedEnabled;
    }

    public boolean isResetIfCorrupt() {
        return resetIfCorrupt;
    }

    public void setResetIfCorrupt(boolean resetIfCorrupt) {
        this.resetIfCorrupt = resetIfCorrupt;
    }
}
