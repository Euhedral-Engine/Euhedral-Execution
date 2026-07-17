package io.euhedral_execution.spring.core.transport.kafka;

import io.euhedral_execution.core.control_plane.ControlPlaneLattice;
import io.euhedral_execution.hashing.HasherApi;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArraySet;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import jakarta.annotation.PreDestroy;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.kafka.autoconfigure.KafkaProperties;
import org.springframework.cloud.context.scope.refresh.RefreshScopeRefreshedEvent;
import org.springframework.cloud.stream.binder.AbstractMessageChannelBinder;
import org.springframework.cloud.stream.binder.ExtendedConsumerProperties;
import org.springframework.cloud.stream.binder.ExtendedProducerProperties;
import org.springframework.cloud.stream.binder.kafka.KafkaMessageChannelBinder;
import org.springframework.cloud.stream.binder.kafka.properties.KafkaBinderConfigurationProperties;
import org.springframework.cloud.stream.binder.kafka.properties.KafkaConsumerProperties;
import org.springframework.cloud.stream.binder.kafka.properties.KafkaExtendedBindingProperties;
import org.springframework.cloud.stream.binder.kafka.properties.KafkaProducerProperties;
import org.springframework.cloud.stream.binder.kafka.provisioning.KafkaTopicProvisioner;
import org.springframework.cloud.stream.binding.BindingService;
import org.springframework.cloud.stream.config.BindingProperties;
import org.springframework.cloud.stream.config.BindingServiceProperties;
import org.springframework.cloud.stream.provisioning.ConsumerDestination;
import org.springframework.cloud.stream.provisioning.ProducerDestination;
import org.springframework.context.event.EventListener;
import org.springframework.integration.channel.DirectChannel;
import org.springframework.integration.core.MessageProducer;
import org.springframework.integration.endpoint.MessageProducerSupport;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageHandler;
import org.springframework.stereotype.Service;

@Service
public class EuhedralKafkaBinder extends
        AbstractMessageChannelBinder<ExtendedConsumerProperties<KafkaConsumerProperties>, ExtendedProducerProperties<KafkaProducerProperties>, KafkaTopicProvisioner> {

    public static final String EUHEDRAL_BINDER = "euhedral-kafka";
    private static final String BINDER_NAMES = "binder-names";
    private static final String TOPIC_NAMES = "topic-names";

    private final Logger log = LoggerFactory.getLogger(EuhedralKafkaBinder.class);

    private final ControlPlaneLattice controlPlane;

    private final KafkaMessageChannelBinder kafkaBinder;
    private final KafkaTopicProvisioner kafkaProvisioner;

    private final ObjectOpenHashSet<String> provisionedConsumers = new ObjectOpenHashSet<>();

    private final AtomicBoolean wip = new AtomicBoolean(false);
    private final Long2ObjectOpenHashMap<KafkaIngestSource> sources = new Long2ObjectOpenHashMap<>();
    private final ObjectProvider<BindingService> bindingService;
    private final ObjectProvider<KafkaProperties> kafkaProperties;
    private final ObjectProvider<KafkaBinderConfigurationProperties> binderConfig;
    private final ObjectProvider<KafkaExtendedBindingProperties> extendedBindingProperties;

    public EuhedralKafkaBinder(ControlPlaneLattice controlPlane,
            KafkaMessageChannelBinder kafkaBinder, KafkaTopicProvisioner kafkaProvisioner,
            ObjectProvider<BindingService> bindingService,
            ObjectProvider<KafkaProperties> kafkaProperties,
            ObjectProvider<KafkaBinderConfigurationProperties> binderConfig,
            ObjectProvider<KafkaExtendedBindingProperties> extendedBindingProperties) {
        super(null, kafkaProvisioner);
        this.controlPlane = controlPlane;
        this.kafkaBinder = kafkaBinder;
        this.kafkaProvisioner = kafkaProvisioner;
        this.bindingService = bindingService;
        this.kafkaProperties = kafkaProperties;
        this.binderConfig = binderConfig;
        this.extendedBindingProperties = extendedBindingProperties;
    }

    @PreDestroy
    public void stopAllConsumers() {
        acquireLock();
        try {
            for (KafkaIngestSource consumer : this.sources.values()) {
                consumer.complete();
            }
            this.sources.clear();
        } finally {
            releaseLock();
        }
    }

    @Override
    protected MessageHandler createProducerMessageHandler(ProducerDestination destination,
            ExtendedProducerProperties<KafkaProducerProperties> properties,
            MessageChannel errorChannel) {

        destination =
                kafkaProvisioner.provisionProducerDestination(destination.getName(), properties);

        DirectChannel channel = new DirectChannel();
        kafkaBinder.bindProducer(destination.getName(), channel, properties);

        return channel::send;
    }

    @Override
    protected MessageProducer createConsumerEndpoint(ConsumerDestination destination, String group,
            ExtendedConsumerProperties<KafkaConsumerProperties> properties) {
        this.log.debug("Adding group: {}", group);

        Map<String, Object> props = buildConsumerProps(group, properties.getExtension());
        if (props.isEmpty()) {
            return null;
        }

        acquireLock();
        try {
            String rawName = destination.getName();

            this.provisionedConsumers.add(rawName);

            destination =
                    this.kafkaProvisioner.provisionConsumerDestination(destination.getName(), group,
                            properties);

            String topic = destination.getName();
            long groupHash = HasherApi.getHash(group);

            acquireLock();
            KafkaIngestSource source = this.sources.get(groupHash);

            if (source == null) {
                source = this.sources.put(groupHash, new KafkaIngestSource(group, props));
                source.addTopic(topic);
                this.controlPlane.addUpstream(source);
            } else {
                source.addTopic(topic);
                source.update(props);
            }

            final KafkaIngestSource finalSource = source;
            return new MessageProducerSupport() {
                @Override
                protected void doStop() {
                    finalSource.removeTopic(topic);
                    provisionedConsumers.remove(rawName);
                    acquireLock();
                    try {
                        if (finalSource.isComplete()) {
                            sources.remove(groupHash);
                        }
                    } finally {
                        releaseLock();
                    }
                }
            };
        } finally {
            releaseLock();
        }

    }

    private Map<String, Object> buildConsumerProps(String group,
            KafkaConsumerProperties properties) {
        Map<String, Object> props = getBaseKafkaConsumerProperties();

        if (properties != null && properties.getConfiguration() != null) {
            props.putAll(properties.getConfiguration());
        }

        props.put(ConsumerConfig.GROUP_ID_CONFIG, group);
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);

        return props;
    }

    private Map<String, Object> getBaseKafkaConsumerProperties() {
        Map<String, Object> props = new HashMap<>();

        KafkaBinderConfigurationProperties binderConfig = this.binderConfig.getIfAvailable();

        KafkaProperties kafkaProperties = this.kafkaProperties.getIfAvailable();
        if (kafkaProperties == null) {
            if (binderConfig == null) {
                return props;
            }
            kafkaProperties = binderConfig.getKafkaProperties();
            props.putAll(binderConfig.getConfiguration());
        }
        props.putAll(kafkaProperties.buildConsumerProperties());

        return props;
    }

    private void acquireLock() {
        while (!this.wip.compareAndSet(false, true)) {
            Thread.onSpinWait();
        }
    }

    private void releaseLock() {
        this.wip.set(false);
    }

    @SuppressWarnings("unchecked")
    @EventListener(RefreshScopeRefreshedEvent.class)
    public void refresh() {
        Map<String, Map<String, Object>> merged = getMergedGroupBindings();

        acquireLock();
        try {
            KafkaExtendedBindingProperties extendedProps =
                    this.extendedBindingProperties.getIfAvailable();
            LongArraySet active = new LongArraySet(merged.size());

            for (var entry : merged.entrySet()) {

                Map<String, Object> props = entry.getValue();
                Set<String> topics = (Set<String>) props.get(TOPIC_NAMES);
                if (topics == null || topics.isEmpty()) {
                    continue;
                }

                String group = entry.getKey();
                long groupHash = HasherApi.getHash(group);

                active.add(groupHash);
                createOrUpdateSource(group, groupHash, props);

                if (extendedProps == null) {
                    continue;
                }

                Set<String> binders = (Set<String>) props.get(BINDER_NAMES);
                for (String name : binders) {
                    KafkaConsumerProperties consumerProps =
                            extendedProps.getExtendedConsumerProperties(name);
                    if (consumerProps != null && !this.provisionedConsumers.contains(name)) {
                        this.provisionedConsumers.add(name);
                        this.kafkaProvisioner.provisionConsumerDestination(name, entry.getKey(),
                                new ExtendedConsumerProperties<>(consumerProps));
                    }

                }
            }

            this.sources.long2ObjectEntrySet().removeIf(entry -> {
                boolean isActive = active.contains(entry.getLongKey());
                if (!isActive) {
                    entry.getValue().complete();
                }
                return !isActive;
            });

        } finally {
            releaseLock();
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Map<String, Object>> getMergedGroupBindings() {
        Map<String, Map<String, Object>> merged = new HashMap<>();

        BindingService bindingService = this.bindingService.getIfAvailable();
        if (bindingService == null) {
            return merged;
        }

        Map<String, Object> base = getBaseKafkaConsumerProperties();
        if (base.isEmpty()) {
            return merged;
        }

        KafkaExtendedBindingProperties extendedBindingProperties =
                this.extendedBindingProperties.getIfAvailable();
        BindingServiceProperties bindingServiceProperties =
                bindingService.getBindingServiceProperties();

        String defaultBinder = bindingServiceProperties.getDefaultBinder();
        Map<String, BindingProperties> bindings = bindingServiceProperties.getBindings();
        for (var entry : bindings.entrySet()) {
            BindingProperties bindingProps = entry.getValue();

            String topic = bindingProps.getDestination();
            String group = bindingProps.getGroup();
            if (group == null || group.isBlank() || topic == null || topic.isBlank()) {
                continue;
            }

            String binder = bindingProps.getBinder();
            binder = binder == null || binder.isBlank() ? defaultBinder : binder;

            if (!EUHEDRAL_BINDER.equals(binder)) {
                continue;
            }

            String bindingName = entry.getKey();

            Map<String, Object> groupProps =
                    merged.computeIfAbsent(group, k -> new HashMap<>(base));

            if (extendedBindingProperties != null) {
                Map<String, String> props = Optional.ofNullable(
                        extendedBindingProperties.getExtendedConsumerProperties(bindingName)
                                .getConfiguration()).orElse(Map.of());
                groupProps.putAll(props);
            }

            Set<String> binderNames = (Set<String>) groupProps.computeIfAbsent(BINDER_NAMES,
                    ignored -> new HashSet<String>());
            binderNames.add(bindingName);

            Set<String> topicNames = (Set<String>) groupProps.computeIfAbsent(TOPIC_NAMES,
                    ignored -> new HashSet<String>());
            topicNames.add(topic);
        }

        merged.entrySet().removeIf(entry -> entry.getValue().isEmpty());

        return merged;
    }

    private void createOrUpdateSource(String group, long groupHash, Map<String, Object> props) {
        KafkaIngestSource source = this.sources.get(groupHash);
        if (source != null) {
            source.update(props);
            return;
        }

        final KafkaIngestSource finalSource = new KafkaIngestSource(group, props);
        if (this.sources.computeIfAbsent(groupHash, ignored -> finalSource) != finalSource) {
            finalSource.complete();
        } else {
            this.controlPlane.addUpstream(finalSource);
        }
    }
}
