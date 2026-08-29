package com.kindlerss.config;

import com.kindlerss.service.DisplayPreferencesService;
import com.kindlerss.web.EditionInterceptor;
import com.kindlerss.web.EditionResolver;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Registers the interceptor that puts each request into an edition. */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    private final ObjectProvider<EditionResolver> editionResolver;
    private final ObjectProvider<DisplayPreferencesService> preferencesService;

    /**
     * Looked up lazily because a {@code @WebMvcTest} slice contains the web layer
     * and nothing else: a test that has not asked for the edition beans gets no
     * edition interceptor, rather than a context that refuses to start.
     */
    public WebMvcConfig(ObjectProvider<EditionResolver> editionResolver,
                        ObjectProvider<DisplayPreferencesService> preferencesService) {
        this.editionResolver = editionResolver;
        this.preferencesService = preferencesService;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        EditionResolver resolver = editionResolver.getIfAvailable();
        DisplayPreferencesService preferences = preferencesService.getIfAvailable();
        if (resolver == null || preferences == null) {
            return;
        }
        registry.addInterceptor(new EditionInterceptor(resolver, preferences))
                .excludePathPatterns("/css/**", "/js/**", "/actuator/**", "/inbound/**");
    }
}
