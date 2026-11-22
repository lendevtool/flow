package me.lendev.flow.common.i18n;

import java.util.List;
import java.util.Locale;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.i18n.AcceptHeaderLocaleResolver;

/**
 * 为 MVC 控制器、异常处理和 Bean Validation 统一协商请求语言。
 */
@Configuration(proxyBeanMethods = false)
public class ApiLocaleConfiguration {

    @Bean
    public AcceptHeaderLocaleResolver localeResolver() {
        var resolver = new AcceptHeaderLocaleResolver();
        resolver.setSupportedLocales(List.of(Locale.SIMPLIFIED_CHINESE, Locale.CHINESE, Locale.US, Locale.ENGLISH));
        resolver.setDefaultLocale(Locale.SIMPLIFIED_CHINESE);
        return resolver;
    }
}
