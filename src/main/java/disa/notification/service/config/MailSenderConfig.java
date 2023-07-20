package disa.notification.service.config;

import java.util.Collections;
import java.util.Properties;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.EnvironmentAware;
import org.springframework.context.MessageSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.spring5.SpringTemplateEngine;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;
import org.thymeleaf.templateresolver.ITemplateResolver;

import disa.notification.service.service.impl.FileSystemMailService;
import disa.notification.service.service.impl.MailServiceImpl;
import disa.notification.service.service.interfaces.MailService;
import lombok.Setter;

@Configuration
@Setter
public class MailSenderConfig implements EnvironmentAware {
    private Environment environment;

    @Bean
    public JavaMailSender mailSender() {
        final JavaMailSenderImpl mailSender = new JavaMailSenderImpl();
        mailSender.setHost(this.environment.getProperty("spring.mail.host"));
        mailSender.setPort(Integer.parseInt(this.environment.getProperty("spring.mail.port")));
        mailSender.setProtocol(this.environment.getProperty("spring.mail.transport.protocol"));
        mailSender.setUsername(this.environment.getProperty("spring.mail.username"));
        mailSender.setPassword(this.environment.getProperty("spring.mail.password"));

        // JavaMail-specific mail sender configuration, based on javamail.properties
        final Properties javaMailProperties = new Properties();
        javaMailProperties.put("mail.smtp.auth", true);
        javaMailProperties.put("mail.smtp.starttls.enable", true);
        mailSender.setJavaMailProperties(javaMailProperties);

        return mailSender;

    }

    @Bean
    public TemplateEngine emailTemplateEngine() {
        final SpringTemplateEngine templateEngine = new SpringTemplateEngine();
        templateEngine.addTemplateResolver(htmlTemplateResolver());
        return templateEngine;
    }

    private ITemplateResolver htmlTemplateResolver() {
        final ClassLoaderTemplateResolver templateResolver = new ClassLoaderTemplateResolver();
        templateResolver.setOrder(Integer.valueOf(2));
        templateResolver.setResolvablePatterns(Collections.singleton("html/*"));
        templateResolver.setPrefix("/mail/");
        templateResolver.setSuffix(".html");
        templateResolver.setTemplateMode(TemplateMode.HTML);
        templateResolver.setCharacterEncoding("spring.mail.encoding");
        templateResolver.setCacheable(false);
        return templateResolver;
    }

    @Bean
    @ConditionalOnProperty(name = "app.mailservice", havingValue = "javaMail")
    public MailService mailServiceImpl(JavaMailSender mailSender, TemplateEngine templateEngine,
            MessageSource messageSource) {
        return new MailServiceImpl(mailSender, templateEngine, messageSource);
    }

    @Bean
    @ConditionalOnProperty(name = "app.mailservice", havingValue = "fileSystem")
    MailService fileSystemMailService(MessageSource messageSource) {
        return new FileSystemMailService(messageSource);
    }
}