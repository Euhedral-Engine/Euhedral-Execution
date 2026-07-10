package io.euhedral_execution.spring.core.transport.kafka;

import io.euhedral_execution.core.control_plane.ControlPlaneLattice;
import io.euhedral_execution.hashing.HasherApi;
import it.unimi.dsi.fastutil.longs.LongArraySet;
import jakarta.annotation.PreDestroy;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.jctools.maps.NonBlockingHashMapLong;
import org.jctools.maps.NonBlockingHashSet;
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

    private final Logger logger = LoggerFactory.getLogger(EuhedralKafkaBinder.class);

    private final ControlPlaneLattice controlPlane;

    private final KafkaMessageChannelBinder kafkaBinder;
    private final KafkaTopicProvisioner kafkaProvisioner;

    private final NonBlockingHashSet<String> provisionedConsumers = new NonBlockingHashSet<>();

    private final AtomicBoolean wip = new AtomicBoolean(false);
    private final NonBlockingHashMapLong<KafkaIngestSource> sources = new NonBlockingHashMapLong<>();
    private final ObjectProvider<BindingService> bindingService;
    private final ObjectProvider<KafkaProperties> kafkaProperties;
    private final ObjectProvider<KafkaBinderConfigurationProperties> binderConfig;
    private final ObjectProvider<KafkaExtendedBindingProperties> extendedBindingProperties;

    public EuhedralKafkaBinder(ControlPlaneLattice controlPlane,
            KafkaMessageChannelBinder kafkaBinder,
            KafkaTopicProvisioner kafkaProvisioner,
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
        for (KafkaIngestSource consumer : sources.values()) {
            consumer.complete();
        }
        sources.clear();
    }

    @Override
    protected MessageHandler createProducerMessageHandler(ProducerDestination destination,
            ExtendedProducerProperties<KafkaProducerProperties> properties,
            MessageChannel errorChannel) {

        destination = kafkaProvisioner.provisionProducerDestination(destination.getName(),
                properties);

        DirectChannel channel = new DirectChannel();
        kafkaBinder.bindProducer(destination.getName(), channel, properties);

        return channel::send;
    }

    @Override
    protected MessageProducer createConsumerEndpoint(ConsumerDestination destination, String group,
            ExtendedConsumerProperties<KafkaConsumerProperties> properties) {
        logger.debug("Adding group: {}", group);

        Map<String, Object> props = buildConsumerProps(group, properties.getExtension());
        if (props.isEmpty()) {
            return null;
        }

        String rawName = destination.getName();
        provisionedConsumers.add(rawName);

        destination =
                kafkaProvisioner.provisionConsumerDestination(
                        destination.getName(),
                        group,
                        properties
                );

        String topic = destination.getName();
        long groupHash = HasherApi.getHash(group);

        acquireLock();
        boolean newSource = false;
        KafkaIngestSource source = this.sources.get(groupHash);
        if (source == null) {
            source = this.sources.put(groupHash,
                    new KafkaIngestSource(group, props));
            newSource = true;
        }
        source.addTopic(topic);

        if (!newSource) {
            source.update(props);
            this.controlPlane.addUpstream(source);
        }
        releaselock();

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
                    releaselock();
                }
            }
        };
    }

    private Map<String, Object> buildConsumerProps(
            String group,
            KafkaConsumerProperties properties
    ) {
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
        while (!wip.compareAndSet(false, true)) {
            Thread.onSpinWait();
        }
    }

    private void releaselock() {
        wip.set(false);
    }

    @SuppressWarnings("unchecked")
    @EventListener(RefreshScopeRefreshedEvent.class)
    public void refresh() {
        Map<String, Map<String, Object>> merged = getMergedGroupBindings();

        acquireLock();
        try {
            KafkaExtendedBindingProperties extendedProps = extendedBindingProperties.getIfAvailable();
            LongArraySet active = new LongArraySet(merged.size());

            for (var entry : merged.entrySet()) {
                long groupHash = HasherApi.getHash(entry.getKey());

                Map<String, Object> props = entry.getValue();
                Set<String> binders = (Set<String>) props.remove(BINDER_NAMES);
                Set<String> topics = (Set<String>) props.remove(TOPIC_NAMES);
                if (topics == null || topics.isEmpty()) {
                    continue;
                }
                active.add(groupHash);

                KafkaIngestSource source = this.sources.get(groupHash);
                if(source == null) {
                    source = new KafkaIngestSource(entry.getKey(), props);
                    final KafkaIngestSource finalSource = source;
                    if(this.sources.computeIfAbsent(groupHash, ignored -> finalSource) != source) {
                        finalSource.complete();
                    }
                } else {
                    source.update(props);
                }

                if (extendedProps != null) {
                    for (String name : binders) {
                        KafkaConsumerProperties consumerProps = extendedProps.getExtendedConsumerProperties(
                                name);
                        if (consumerProps != null && !provisionedConsumers.contains(name)) {
                            provisionedConsumers.add(name);
                            kafkaProvisioner.provisionConsumerDestination(name, entry.getKey(),
                                    new ExtendedConsumerProperties<>(consumerProps));
                        }

                    }
                }
            }

            this.sources.entrySet().removeIf(entry -> {
                boolean isActive = active.contains(entry.getKey().longValue());
                if (!isActive) {
                    entry.getValue().complete();
                }
                return !isActive;
            });

        } finally {
            releaselock();
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

        KafkaExtendedBindingProperties extendedBindingProperties = this.extendedBindingProperties.getIfAvailable();
        BindingServiceProperties bindingServiceProperties = bindingService.getBindingServiceProperties();

        String defaultBinder = bindingServiceProperties.getDefaultBinder();
        Map<String, BindingProperties> bindings = bindingServiceProperties.getBindings();
        for (var entry : bindings.entrySet()) {
            BindingProperties bindingProps = entry.getValue();
            String binder = bindingProps.getBinder();
            binder = binder == null || binder.isBlank()
                    ? defaultBinder
                    : binder;

            if (EUHEDRAL_BINDER.equals(binder)) {
                String bindingName = entry.getKey();
                Map<String, String> props;

                if (extendedBindingProperties != null) {
                    props = Optional.ofNullable(
                            extendedBindingProperties.getExtendedConsumerProperties(
                                    bindingName).getConfiguration()).orElse(Map.of());
                } else {
                    props = Map.of();
                }

                String group = entry.getValue().getGroup();
                if (group == null || group.isBlank()) {
                    continue;
                }

                String topic = bindingProps.getDestination();
                if (topic != null && !topic.isBlank()) {
                    Map<String, Object> groupProps = merged.computeIfAbsent(group,
                            k -> new HashMap<>(base));
                    Set<String> binderNames = (Set<String>) groupProps.computeIfAbsent(BINDER_NAMES,
                            ignored -> new HashSet<String>());
                    binderNames.add(bindingName);

                    Set<String> topicNames = (Set<String>) groupProps.computeIfAbsent(TOPIC_NAMES,
                            ignored -> new HashSet<String>());
                    topicNames.add(topic);
                    groupProps.putAll(props);
                }
            }
        }

        merged.entrySet().removeIf(entry -> entry.getValue().isEmpty());

        return merged;
    }
}
