package io.euhedral_execution.spring.core.protocols.kafka;

import io.euhedral_execution.core.control_plane.ControlPlaneLattice;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.kafka.autoconfigure.KafkaProperties;
import org.springframework.cloud.stream.binder.kafka.KafkaMessageChannelBinder;
import org.springframework.cloud.stream.binder.kafka.properties.KafkaBinderConfigurationProperties;
import org.springframework.cloud.stream.binder.kafka.properties.KafkaExtendedBindingProperties;
import org.springframework.cloud.stream.binder.kafka.provisioning.KafkaTopicProvisioner;
import org.springframework.cloud.stream.binding.BindingService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class EuhedralKafkaConfiguration {

    @Bean
    public EuhedralKafkaBinder euhedralKafkaBinder(
            ControlPlaneLattice controlPlane,
            KafkaMessageChannelBinder kafkaBinder,
            KafkaTopicProvisioner kafkaProvisioner,
            ObjectProvider<BindingService> bindingService,
            ObjectProvider<KafkaProperties> kafkaProperties,
            ObjectProvider<KafkaBinderConfigurationProperties> binderConfig,
            ObjectProvider<KafkaExtendedBindingProperties> extendedBindingProperties
    ) {
        return new EuhedralKafkaBinder(
                controlPlane,
                kafkaBinder,
                kafkaProvisioner,
                bindingService,
                kafkaProperties,
                binderConfig,
                extendedBindingProperties
        );
    }
}
