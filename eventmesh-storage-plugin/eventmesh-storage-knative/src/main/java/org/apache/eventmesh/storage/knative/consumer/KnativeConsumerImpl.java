/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.eventmesh.storage.knative.consumer;

import org.apache.eventmesh.api.AbstractContext;
import org.apache.eventmesh.api.EventListener;
import org.apache.eventmesh.api.consumer.Consumer;
import org.apache.eventmesh.common.config.Config;
import org.apache.eventmesh.storage.knative.config.ClientConfiguration;

import java.util.List;
import java.util.Properties;

import io.cloudevents.CloudEvent;

import lombok.extern.slf4j.Slf4j;

@Config(field = "clientConfiguration")
@Slf4j
public class KnativeConsumerImpl implements Consumer {

    private transient PullConsumerImpl pullConsumer;

    /**
     * Unified configuration class corresponding to knative-client.properties
     */
    private ClientConfiguration clientConfiguration;

    @Override
    public synchronized void init(Properties properties) throws Exception {
        // Load parameters from properties file:
        properties.put("emUrl", clientConfiguration.getEmurl());
        properties.put("serviceAddr", clientConfiguration.getServiceAddr());

        pullConsumer = new PullConsumerImpl(properties);
    }

    @Override
    public void subscribe(String topic) {
        pullConsumer.subscribe(topic);
    }

    @Override
    public void unsubscribe(String topic) {
        try {
            pullConsumer.unsubscribe(topic);
        } catch (Exception e) {
            log.error("unsubscribe error", e);
        }
    }

    @Override
    public void registerEventListener(EventListener listener) {
        pullConsumer.registerEventListener(listener);
    }

    @Override
    public void updateOffset(List<CloudEvent> cloudEvents, AbstractContext context) {
        pullConsumer.updateOffset(cloudEvents, context);
    }

    @Override
    public boolean isStarted() {
        return pullConsumer.isStarted();
    }

    @Override
    public boolean isClosed() {
        return pullConsumer.isClosed();
    }

    @Override
    public void start() {
        pullConsumer.start();
    }

    @Override
    public void shutdown() {
        pullConsumer.shutdown();
    }

    public ClientConfiguration getClientConfiguration() {
        return this.clientConfiguration;
    }
}
