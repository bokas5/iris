package id.global.iris.messaging.runtime.channel;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;
import javax.inject.Named;

import id.global.iris.messaging.runtime.configuration.AmqpConfiguration;
import id.global.iris.messaging.runtime.connection.ConsumerConnectionProvider;

@Named("consumerChannelService")
@ApplicationScoped()
public class ConsumerChannelService extends AbstractChannelService {
    @Inject
    public ConsumerChannelService(ConsumerConnectionProvider connectionProvider, AmqpConfiguration configuration) {
        super(connectionProvider, configuration);
    }
}