package io.github.iaarencibia.notifications.adapter.in.web;

import io.swagger.v3.core.converter.AnnotatedType;
import io.swagger.v3.oas.models.media.Schema;
import java.lang.annotation.Annotation;
import org.springdoc.core.customizers.PropertyCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Reports {@link CharacterLength} in the OpenAPI document as {@code maxLength}. */
@Configuration(proxyBeanMethods = false)
class CharacterLengthCustomizer {

    @Bean
    PropertyCustomizer characterLengthToMaxLength() {
        return (Schema property, AnnotatedType type) -> {
            Annotation[] annotations = type.getCtxAnnotations();
            if (annotations != null) {
                for (Annotation annotation : annotations) {
                    if (annotation instanceof CharacterLength length) {
                        property.setMaxLength(length.max());
                    }
                }
            }
            return property;
        };
    }
}
