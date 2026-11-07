package com.kindlerss.config;

import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.core.env.Environment;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

/**
 * Puts {@link RecipientGuardMailSender} in front of every {@link JavaMailSender}
 * bean, so account, billing and Kindle mail all pass the same check without
 * each service having to remember it.
 */
@Component
public class RecipientGuardPostProcessor implements BeanPostProcessor {

    private final Environment environment;

    public RecipientGuardPostProcessor(Environment environment) {
        this.environment = environment;
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) {
        if (bean instanceof JavaMailSender sender && !(bean instanceof RecipientGuardMailSender)) {
            return new RecipientGuardMailSender(sender,
                    environment.getProperty("app.mail-allowed-recipient-domains", ""));
        }
        return bean;
    }
}
