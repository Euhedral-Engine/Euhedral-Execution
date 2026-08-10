package io.euhedral_execution.spring.core.configuration;

import io.euhedral_execution.core.config.LatticeConfig;
import io.euhedral_execution.core.control_plane.ControlPlaneLattice;
import io.euhedral_execution.core.generics.AbstractExecutor;
import io.euhedral_execution.core.impl.BaseCloneableObject;
import io.euhedral_execution.core.impl.DefaultExecutor;
import io.euhedral_execution.reactor.EuhedralOperator;
import io.euhedral_execution.reactor.EuhedralScheduler;
import io.euhedral_execution.spring.core.transport.kafka.EuhedralKafkaBinder;
import io.micrometer.core.instrument.MeterRegistry;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.kafka.autoconfigure.KafkaProperties;
import org.springframework.cloud.stream.binder.kafka.KafkaMessageChannelBinder;
import org.springframework.cloud.stream.binder.kafka.properties.KafkaBinderConfigurationProperties;
import org.springframework.cloud.stream.binder.kafka.properties.KafkaExtendedBindingProperties;
import org.springframework.cloud.stream.binder.kafka.provisioning.KafkaTopicProvisioner;
import org.springframework.cloud.stream.binding.BindingService;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
public class EuhedralConfiguration {

    @Bean
    @ConditionalOnMissingBean(AbstractExecutor.class)
    public AbstractExecutor euhedralExecutor() {
        return new DefaultExecutor();
    }

    @Bean
    @ConditionalOnMissingBean(ControlPlaneLattice.class)
    public ControlPlaneLattice controlPlaneLattice(AbstractExecutor executor, @Nullable MeterRegistry registry) {
        LatticeConfig config = LatticeConfig.ofDefaults(new BaseCloneableObject("euhedral", registry, executor));
        ControlPlaneLattice controlPlane = ControlPlaneLattice.getOrCreate(config);
        controlPlane.start();
        return controlPlane;
    }

    @Bean
    @ConditionalOnMissingBean(EuhedralScheduler.class)
    public EuhedralScheduler euhedralScheduler(ControlPlaneLattice controlPlane) {
        return EuhedralScheduler.getOrCreate(controlPlane);
    }

    @Bean
    @ConditionalOnMissingBean(EuhedralOperator.class)
    public EuhedralOperator euhedralOperator(EuhedralScheduler scheduler) {
        return new EuhedralOperator(scheduler);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnClass(
            name = {
                "org.springframework.cloud.stream.binder.BinderFactory",
                "org.springframework.cloud.stream.binder.kafka.KafkaMessageChannelBinder"
            })
    @ConditionalOnBean(
            type = {
                "org.springframework.cloud.stream.binder.BinderFactory",
                "org.springframework.cloud.stream.binder.kafka.KafkaMessageChannelBinder"
            })
    public EuhedralKafkaBinder euhedralKafkaBinder(
            ControlPlaneLattice controlPlane,
            KafkaMessageChannelBinder kafkaBinder,
            KafkaTopicProvisioner kafkaProvisioner,
            ObjectProvider<BindingService> bindingService,
            ObjectProvider<KafkaProperties> kafkaProperties,
            ObjectProvider<KafkaBinderConfigurationProperties> binderConfig,
            ObjectProvider<KafkaExtendedBindingProperties> extendedBindingProperties) {
        return new EuhedralKafkaBinder(
                controlPlane,
                kafkaBinder,
                kafkaProvisioner,
                bindingService,
                kafkaProperties,
                binderConfig,
                extendedBindingProperties);
    }
}
