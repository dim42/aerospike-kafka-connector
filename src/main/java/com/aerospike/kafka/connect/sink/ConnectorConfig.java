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

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.kafka.common.config.AbstractConfig;
import org.apache.kafka.common.config.ConfigDef;
import org.apache.kafka.common.config.ConfigDef.Importance;
import org.apache.kafka.common.config.ConfigDef.Type;
import org.apache.kafka.common.config.ConfigDef.ValidString;
import org.apache.kafka.common.config.ConfigDef.Validator;
import org.apache.kafka.common.config.ConfigException;

import com.aerospike.client.Host;
import com.aerospike.client.policy.RecordExistsAction;

public class ConnectorConfig extends AbstractConfig {

    private static final String TOPIC_CONFIG_PREFIX = "topic.";

    public static final String TOPICS_CONFIG = AerospikeSinkConnector.TOPICS_CONFIG;
    private static final String TOPICS_DOC = "List of Kafka topics";

    public static final String HOSTS_CONFIG = "cluster.hosts";
    private static final String HOSTS_DOC = "Comma separated list of one or more Aerospike cluster hosts;"
            + "each host can be specified as a valid IP address or hostname followed by an optional port number (default is 3000)";
    private static final String HOSTS_DEFAULT = "127.0.0.1";
    private static final Validator HOSTS_VALIDATOR = new HostsValidator();

    public static final String POLICY_RECORD_EXISTS_ACTION_CONFIG = "policy.record_exists_action";
    private static final String POLICY_RECORD_EXISTS_ACTION_DOC = "Write Policy: How to handle writes when the record already exists";
    private static final String POLICY_RECORD_EXISTS_ACTION_DEFAULT = "update";
    private static final Validator POLICY_RECORD_EXISTS_ACTION_VALIDATOR = ValidString.in("create_only", "update",
            "update_only", "replace", "replace_only");

    public static final String MAX_ASYNC_COMMANDS_CONFIG = "max_async_commands";
    private static final String MAX_ASYNC_COMMANDS_DOC = "Maximum number of concurrent asynchronous client requests to the Aerospike cluster";
    private static final int MAX_ASYNC_COMMANDS_DEFAULT = 300;

    public static final String MAX_COMMAND_ACTION_CONFIG = "max_command_action";
    private static final String MAX_COMMAND_ACTION_DOC = "How to handle cases when the asynchronous maximum number of concurrent connections have been reached";
    private static final String MAX_COMMAND_ACTION_DEFAULT = "block";
    private static final Validator MAX_COMMAND_ACTION_VALIDATOR = ValidString.in("accept", "block", "reject");

    public static ConfigDef baseConfigDef() {
        return new ConfigDef().define(TOPICS_CONFIG, Type.LIST, Importance.HIGH, TOPICS_DOC)
                .define(HOSTS_CONFIG, Type.STRING, HOSTS_DEFAULT, HOSTS_VALIDATOR, Importance.HIGH, HOSTS_DOC)
                .define(POLICY_RECORD_EXISTS_ACTION_CONFIG, Type.STRING, POLICY_RECORD_EXISTS_ACTION_DEFAULT,
                        POLICY_RECORD_EXISTS_ACTION_VALIDATOR, Importance.LOW, POLICY_RECORD_EXISTS_ACTION_DOC)
                .define(MAX_ASYNC_COMMANDS_CONFIG, Type.INT, MAX_ASYNC_COMMANDS_DEFAULT, Importance.LOW,
                        MAX_ASYNC_COMMANDS_DOC)
                .define(MAX_COMMAND_ACTION_CONFIG, Type.STRING, MAX_COMMAND_ACTION_DEFAULT,
                        MAX_COMMAND_ACTION_VALIDATOR, Importance.LOW, MAX_COMMAND_ACTION_DOC);
    }

    static ConfigDef config = baseConfigDef();

    public ConnectorConfig(Map<String, String> props) {
        super(config, props);
    }

    public Host[] getHosts() {
        String hostsString = getString(HOSTS_CONFIG);
        return HostsParser.parseHostsString(hostsString);
    }

    public RecordExistsAction getPolicyRecordExistsAction() {
        String action = getString(POLICY_RECORD_EXISTS_ACTION_CONFIG);
        switch (action) {
        case "create_only":
            return RecordExistsAction.CREATE_ONLY;
        case "replace":
            return RecordExistsAction.REPLACE;
        case "replace_only":
            return RecordExistsAction.REPLACE_ONLY;
        case "update":
            return RecordExistsAction.UPDATE;
        case "update_only":
            return RecordExistsAction.UPDATE_ONLY;
        default:
            // This should never happen if the configuration passes validation!
            throw new ConfigException(POLICY_RECORD_EXISTS_ACTION_CONFIG, action, "Unsupported policy value.");
        }
    }

    public int getMaxAsyncCommands() {
        return getInt(MAX_ASYNC_COMMANDS_CONFIG);
    }

//    public MaxCommandAction getMaxCommandAction() {
//        String action = getString(MAX_COMMAND_ACTION_CONFIG);
//        switch (action) {
//        case "accept":
//            return MaxCommandAction.ACCEPT;
//        case "block":
//            return MaxCommandAction.BLOCK;
//        case "reject":
//            return MaxCommandAction.REJECT;
//        default:
//            // This should never happen if the configuration passes validation!
//            throw new ConfigException(MAX_COMMAND_ACTION_CONFIG, action, "Unsupported policy value.");
//        }
//    }

    public Map<String, TopicConfig> getTopicConfigs() {
        Map<String, TopicConfig> topicConfigs = new HashMap<>();
        Map<String, Object> defaultTopicConfig = originalsWithPrefix(TOPIC_CONFIG_PREFIX);
        List<String> topicNames = getList(TOPICS_CONFIG);
        for (String topic : topicNames) {
            String prefix = TOPIC_CONFIG_PREFIX + topic + ".";
            Map<String, Object> config = new HashMap<>(defaultTopicConfig);
            config.putAll(originalsWithPrefix(prefix));
            topicConfigs.put(topic, new TopicConfig(config));
        }
        return topicConfigs;
    }

    public static void main(String[] args) {
        System.out.println(config.toRst());
    }

}

class HostsValidator implements Validator {
    public HostsValidator() {
    };

    @Override
    public void ensureValid(String name, Object value) {
        try {
            String hosts = (String) value;
            HostsParser.parseHostsString(hosts);
        } catch (Exception e) {
            throw new ConfigException(name, value, "Invalid hosts string: " + e.getMessage());
        }
    }
}
