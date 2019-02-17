/*
 * Copyright 2016 Aerospike, Inc.
 *
 * Portions may be licensed to Aerospike, Inc. under one or more contributor
 * license agreements WHICH ARE COMPATIBLE WITH THE APACHE LICENSE, VERSION 2.0.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package com.aerospike.kafka.connect.sink;

import com.aerospike.client.AerospikeClient;
import com.aerospike.client.AerospikeException;
import com.aerospike.client.AerospikeException.AsyncQueueFull;
import com.aerospike.client.AerospikeException.Connection;
import com.aerospike.client.AerospikeException.Timeout;
import com.aerospike.client.Bin;
import com.aerospike.client.Host;
import com.aerospike.client.Key;
import com.aerospike.client.async.EventLoop;
import com.aerospike.client.async.EventLoops;
import com.aerospike.client.async.EventPolicy;
import com.aerospike.client.async.NioEventLoops;
import com.aerospike.client.listener.WriteListener;
import com.aerospike.client.policy.ClientPolicy;
import com.aerospike.client.policy.RecordExistsAction;
import com.aerospike.client.policy.WritePolicy;
import com.aerospike.kafka.connect.data.AerospikeRecord;
import org.apache.kafka.connect.errors.ConnectException;
import org.apache.kafka.connect.errors.RetriableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The AsyncWriter handles connections to the Aerospike cluster, sending data
 * and flush. The write sends individual request to write each record using the
 * async client. The flush method waits until all in-flight request have been
 * completed.
 */
public class AsyncWriter {

    private static final Logger log = LoggerFactory.getLogger(AsyncWriter.class);

    private final AerospikeClient client;
    private final EventLoops eventLoops;
    private final WritePolicy writePolicy;
    private final Counter inFlight;
    private final ResultListener listener;

    public AsyncWriter(ConnectorConfig config) {
        try {
            Host[] hosts = config.getHosts();
            ClientPolicy policy = createClientPolicy(config);
            eventLoops = createEventLoops();
            policy.eventLoops = eventLoops;
            client = new AerospikeClient(policy, hosts);
            inFlight = new Counter();
            listener = new ResultListener(inFlight);
        } catch (AerospikeException e) {
            throw new ConnectException("Error connecting to Aerospike cluster", e);
        }
        writePolicy = createWritePolicy(config);
    }

    private NioEventLoops createEventLoops() {
        NioEventLoops eventLoops;
        EventPolicy eventPolicy = new EventPolicy();
//        eventPolicy.maxCommandsInProcess = config.getMaxAsyncCommands();
        int eventLoopSize = 4;
        // Direct NIO
        eventLoops = new NioEventLoops(eventPolicy, eventLoopSize);

        // Netty NIO
        // EventLoopGroup group = new NioEventLoopGroup(eventLoopSize);
        // eventLoops = new NettyEventLoops(eventPolicy, group);

        // Netty epoll (Linux only)
        // EventLoopGroup group = new EpollEventLoopGroup(eventLoopSize);
        // eventLoops = new NettyEventLoops(eventPolicy, group);
        return eventLoops;
    }

    public void write(AerospikeRecord record) {
        listener.raiseErrors();
        Key key = record.key();
        Bin[] bins = record.bins();
        inFlight.increment();
        EventLoop eventLoop = eventLoops.next();
        client.put(eventLoop, listener, writePolicy, key, bins);
    }

    public void flush() {
        listener.raiseErrors();
        inFlight.waitUntilZero();
    }

    public void close() {
        client.close();
    }

    private ClientPolicy createClientPolicy(ConnectorConfig config) {
//        AsyncClientPolicy policy = new AsyncClientPolicy();
        ClientPolicy policy = new ClientPolicy();
//        policy.asyncMaxCommands = config.getMaxAsyncCommands();
//        policy.asyncMaxCommandAction = config.getMaxCommandAction();
        return policy;
    }

    private WritePolicy createWritePolicy(ConnectorConfig config) {
        WritePolicy policy = new WritePolicy();
        RecordExistsAction action = config.getPolicyRecordExistsAction();
        if (action != null) {
            policy.recordExistsAction = action;
        }
        log.trace("Write Policy: recordExistsAction={}", policy.recordExistsAction);
        return policy;
    }

    /*
     * Write listener implementation to track when asynchronous DB commands have
     * been completed and to record any errors raised by the commands.
     */
    class ResultListener implements WriteListener {

        private final Counter counter;
        private final AtomicBoolean retry = new AtomicBoolean(true);
        private final AtomicInteger exceptions = new AtomicInteger(0);
        private final AtomicReference<Throwable> exception = new AtomicReference<>();

        public ResultListener(Counter counter) {
            this.counter = counter;
        }

        public void raiseErrors() throws ConnectException {
            Throwable error = exception.get();
            if (error == null) {
                return;
            }
            String message = "Error writing records: " + exceptions.get() + " exception(s) occurred while asynchronously writing records";
            if (retry.get()) {
                throw new RetriableException(message, error);
            } else {
                throw new ConnectException(message, error);
            }
        }

        @Override
        public void onFailure(AerospikeException e) {
            log.error("Error writing record", e);
            exception.compareAndSet(null, e);
            retry.compareAndSet(true, retriable(e));
            exceptions.incrementAndGet();
            counter.decrement();
        }

        @Override
        public void onSuccess(Key key) {
            log.trace("Successfully put key {}", key);
            counter.decrement();
        }

        private boolean retriable(AerospikeException e) {
            return e instanceof AsyncQueueFull
                    || e instanceof Timeout
                    || e instanceof Connection;
        }
    }

    /*
     * Atomic counter to keep track of number of asynchronous, in-flight
     * requests
     */
    class Counter {
        private static final long DEFAULT_SLEEP_INTERVAL_MS = 1;

        private AtomicInteger counter;
        private final long sleepMs;

        public Counter() {
            this(DEFAULT_SLEEP_INTERVAL_MS);
        }

        public Counter(long sleepMs) {
            this.sleepMs = sleepMs;
            counter = new AtomicInteger(0);
        }

        public void increment() {
            counter.incrementAndGet();
        }

        public void decrement() {
            counter.decrementAndGet();
        }

        public void waitUntilZero() {
            try {
                int count;
                while ((count = counter.get()) > 0) {
                    log.trace("Waiting " + sleepMs + "ms for counter to reach zero - current: " + count);
                    Thread.sleep(sleepMs);
                }
            } catch (InterruptedException e) {
                throw new ConnectException("Interrupted while waiting to complete in-flight requests", e);
            }
        }
    }
}
