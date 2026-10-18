package edu.cwru.openbench.config;

import edu.cwru.openbench.common.TypedId;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.core.convert.converter.ConverterFactory;
import org.springframework.core.io.Resource;
import org.springframework.format.FormatterRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.PathResourceResolver;

/** Spring MVC wiring: typed ids as path variables, and the web client with its single-page fallback. */
@Configuration(proxyBeanMethods = false)
class WebConfig implements WebMvcConfigurer {

    @Override
    public void addFormatters(FormatterRegistry registry) {
        registry.addConverterFactory(new StringToTypedIdConverterFactory());
    }

    /**
     * Serves the built client from {@code classpath:/static/}. A GET to a path outside {@code /api}
     * that is not a static file serves {@code index.html}, so the client's routes survive a reload;
     * an unknown path under {@code /api} stays a 404 problem.
     */
    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/**")
                .addResourceLocations("classpath:/static/")
                .resourceChain(true)
                .addResolver(new SinglePageAppResourceResolver());
    }

    /** The root path is a client route too; the resource handler never serves an empty path. */
    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        registry.addViewController("/").setViewName("forward:/index.html");
    }

    static final class SinglePageAppResourceResolver extends PathResourceResolver {

        @Override
        protected Resource getResource(String resourcePath, Resource location) throws IOException {
            Resource resource = super.getResource(resourcePath, location);
            if (resource != null || resourcePath.equals("api") || resourcePath.startsWith("api/")) {
                return resource;
            }
            return super.getResource("index.html", location);
        }
    }

    /** Converts {@code "42"} to any typed id through its {@code (long)} canonical constructor. */
    static final class StringToTypedIdConverterFactory implements ConverterFactory<String, TypedId> {

        @Override
        public <T extends TypedId> Converter<String, T> getConverter(Class<T> targetType) {
            Constructor<T> constructor;
            try {
                constructor = targetType.getDeclaredConstructor(long.class);
            } catch (NoSuchMethodException e) {
                throw new IllegalStateException(targetType + " has no (long) constructor", e);
            }
            return source -> {
                try {
                    return constructor.newInstance(Long.parseLong(source.trim()));
                } catch (InstantiationException | IllegalAccessException | InvocationTargetException e) {
                    throw new IllegalArgumentException("Cannot create " + targetType.getSimpleName(), e);
                }
            };
        }
    }
}
