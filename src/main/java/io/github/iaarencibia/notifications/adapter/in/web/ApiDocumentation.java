package io.github.iaarencibia.notifications.adapter.in.web;

import io.swagger.v3.core.converter.AnnotatedType;
import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.core.converter.ResolvedSchema;
import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeIn;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;

/**
 * The service-wide part of the OpenAPI document: its title, version, credential and the problem
 * document every error is answered with.
 */
@Configuration(proxyBeanMethods = false)
@OpenAPIDefinition(
        info = @Info(title = "Notification Service API", version = "v1"),
        security = @SecurityRequirement(name = ApiDocumentation.API_KEY))
@SecurityScheme(
        name = ApiDocumentation.API_KEY,
        type = SecuritySchemeType.APIKEY,
        in = SecuritySchemeIn.HEADER,
        paramName = "Authorization",
        description = "Enter the whole header value: ApiKey <key>")
class ApiDocumentation {

    static final String API_KEY = "apiKey";

    private static final String API_PATH_PREFIX = "/api/";
    private static final String PROBLEM_SCHEMA = "ProblemDetail";

    @Bean
    OpenApiCustomizer errorsAreProblemDocuments() {
        return openApi -> {
            ResolvedSchema problem =
                    ModelConverters.getInstance()
                            .resolveAsResolvedSchema(new AnnotatedType(ProblemDetail.class));
            if (openApi.getComponents() == null) {
                openApi.components(new Components());
            }
            problem.referencedSchemas.forEach(openApi.getComponents()::addSchemas);

            Content problemContent =
                    new Content()
                            .addMediaType(
                                    MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                                    new io.swagger.v3.oas.models.media.MediaType()
                                            .schema(
                                                    new Schema<>()
                                                            .$ref(
                                                                    "#/components/schemas/"
                                                                            + PROBLEM_SCHEMA)));

            openApi.getPaths().entrySet().stream()
                    .filter(path -> path.getKey().startsWith(API_PATH_PREFIX))
                    .flatMap(path -> path.getValue().readOperations().stream())
                    .forEach(
                            operation -> {
                                ApiResponses responses = operation.getResponses();
                                responses.addApiResponse(
                                        "401",
                                        new ApiResponse()
                                                .description("The credential is missing or wrong"));
                                responses.forEach(
                                        (code, response) -> {
                                            if (code.startsWith("4")) {
                                                response.content(problemContent);
                                            }
                                        });
                            });
        };
    }
}
