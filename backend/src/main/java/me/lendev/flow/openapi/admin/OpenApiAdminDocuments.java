package me.lendev.flow.openapi.admin;

import java.util.Locale;
import java.util.Optional;

import jakarta.servlet.http.HttpServletRequest;
import me.lendev.flow.openapi.ManagedReadRegistry;
import me.lendev.flow.openapi.OpenApiEndpoint;
import me.lendev.flow.openapi.OpenApiProperties;
import org.springdoc.core.customizers.SpringDocCustomizers;
import org.springdoc.core.properties.SpringDocConfigProperties;
import org.springdoc.core.providers.SpringDocProviders;
import org.springdoc.core.service.AbstractRequestService;
import org.springdoc.core.service.GenericResponseService;
import org.springdoc.core.service.OpenAPIService;
import org.springdoc.core.service.OperationService;
import org.springdoc.webmvc.api.OpenApiResource;
import org.springframework.beans.BeansException;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;
import org.springframework.util.function.SingletonSupplier;
import org.springframework.web.method.HandlerMethod;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * 使用独立的 SpringDoc v1 文档组生成契约，并在每次下载时按接口发布状态裁剪。
 */
@Component
class OpenApiAdminDocuments {

    private static final String TOKEN_PATH = "/oauth2/token";
    private static final String RESPONSES = "responses";
    private static final String AUTHENTICATE = "WWW-Authenticate";
    private static final String RETRY_AFTER = "Retry-After";
    private static final String STRING_TYPE = "string";
    private static final String BUSINESS_ERROR = "OpenApiBusinessError";
    private static final String OAUTH_ERROR = "OpenApiOAuthError";
    private static final String SCHEMA = "schema";
    private static final String DESCRIPTION = "description";
    private static final String REQUIRED = "required";
    private static final String SCHEMA_PROPERTIES = "properties";
    private static final String ITEMS = "items";
    private static final String OBJECT_TYPE = "object";
    private static final String INTEGER_TYPE = "integer";

    private final OpenApiAdminService admin;
    private final OpenApiProperties properties;
    private final ManagedReadRegistry managed;
    private final ObjectMapper json;
    private final SingletonSupplier<V1Resource> renderer;

    OpenApiAdminDocuments(OpenApiAdminService admin, OpenApiProperties properties, ManagedReadRegistry managed, ObjectMapper json,
                          ApplicationContext applicationContext) {
        this.admin = admin;
        this.properties = properties;
        this.managed = managed;
        this.json = json;
        this.renderer = SingletonSupplier.of(() -> new V1Resource(applicationContext, properties.publicBaseUrl()));
    }

    JsonNode document(Locale locale) {
        ObjectNode generated = generate(locale);
        ObjectNode published = json.createObjectNode();
        JsonNode paths = generated.path("paths");
        JsonNode token = paths.path(TOKEN_PATH).path("post");
        if (!(token instanceof ObjectNode tokenPost)) {
            throw OpenApiAdminFailure.docsUnavailable();
        }
        describeToken(tokenPost);
        published.putObject(TOKEN_PATH).set("post", tokenPost);
        for (var endpoint : admin.publishedEndpointsV1()) {
            String method = endpoint.httpMethod().toLowerCase(Locale.ROOT);
            JsonNode operation = endpoint.sourcePath() == null ? paths.path(endpoint.pathTemplate()).path(method)
                    : managedOperation(endpoint.displayName(), managed.readContract(endpoint.publicContractJson()),
                    generated.path("openapi").asString().startsWith("3.1"));
            if (!(operation instanceof ObjectNode publishedOperation)) {
                throw OpenApiAdminFailure.docsUnavailable();
            }
            publishedOperation.put("x-required-scope", endpoint.endpointCode());
            describeBusiness(publishedOperation);
            published.withObjectProperty(endpoint.pathTemplate()).set(method, publishedOperation);
        }
        generated.set("paths", published);
        generated.remove("security");
        generated.putObject("info").put("title", "Flow Open API").put("version", "v1");
        generated.putArray("servers").addObject().put("url", properties.publicBaseUrl());
        describeComponents(generated);
        return generated;
    }

    private ObjectNode managedOperation(String name, ManagedReadRegistry.Contract contract, boolean openApi31) {
        var operation = json.createObjectNode().put("summary", name);
        operation.putArray("security").addObject().putArray("appBearer");
        var parameters = operation.putArray("parameters");
        for (var parameter : contract.parameters()) {
            var item = parameters.addObject().put("name", parameter.name()).put("in", "query").put(REQUIRED, parameter.required());
            scalarSchema(item.putObject(SCHEMA), parameter.type(), parameter.format());
        }
        var body = operation.putObject(RESPONSES).putObject("200").put(DESCRIPTION, "OK")
                .putObject("content").putObject("application/json");
        if (contract.wholeResponse()) {
            JsonNode schema = contract.responseSchema().deepCopy();
            if (!openApi31 && schema instanceof ObjectNode object) {
                useOpenApi30Nullable(object);
            }
            body.set(SCHEMA, schema);
            return operation;
        }
        var schema = body.putObject(SCHEMA);
        var object = managedResponseItem(schema, contract);
        object.put("type", OBJECT_TYPE).put("additionalProperties", false);
        var required = object.putArray(REQUIRED);
        var fields = object.putObject(SCHEMA_PROPERTIES);
        for (var field : contract.fields()) {
            required.add(field.name());
            var fieldSchema = fields.putObject(field.name());
            scalarSchema(fieldSchema, field.type(), field.format());
            if (field.nullable()) {
                if (openApi31) {
                    fieldSchema.putArray("type").add(field.type()).add("null");
                } else {
                    fieldSchema.put("nullable", true);
                }
            }
        }
        return operation;
    }

    private static void useOpenApi30Nullable(ObjectNode schema) {
        var type = schema.path("type");
        if (type.isArray()) {
            schema.put("type", type.get(0).asString()).put("nullable", true);
        }
        if (schema.path(ITEMS) instanceof ObjectNode items) {
            useOpenApi30Nullable(items);
        }
        for (var value : schema.path(SCHEMA_PROPERTIES)) {
            if (value instanceof ObjectNode property) {
                useOpenApi30Nullable(property);
            }
        }
    }

    private static ObjectNode managedResponseItem(ObjectNode schema, ManagedReadRegistry.Contract contract) {
        if (contract.page()) {
            schema.put("type", OBJECT_TYPE).put("additionalProperties", false);
            schema.putArray(REQUIRED).add("pageData").add("total");
            var properties = schema.putObject(SCHEMA_PROPERTIES);
            scalarSchema(properties.putObject("total"), INTEGER_TYPE, "int64");
            return properties.putObject("pageData").put("type", "array").putObject(ITEMS);
        }
        return contract.collection() ? schema.put("type", "array").putObject(ITEMS) : schema;
    }

    private static void scalarSchema(ObjectNode schema, String type, String format) {
        schema.put("type", type);
        if (format != null) {
            schema.put("format", format);
        }
    }

    private ObjectNode generate(Locale locale) {
        try {
            return (ObjectNode) json.readTree(renderer.obtain().render(locale));
        } catch (BeansException | JacksonException failure) {
            throw OpenApiAdminFailure.docsUnavailable();
        }
    }

    private static void describeComponents(ObjectNode document) {
        ObjectNode components = document.withObjectProperty("components");
        ObjectNode schemes = components.putObject("securitySchemes");
        schemes.putObject("clientBasic").put("type", "http").put("scheme", "basic");
        schemes.putObject("appBearer").put("type", "http").put("scheme", "bearer");
        ObjectNode schemas = components.withObjectProperty("schemas");
        // Jackson 3 的 JsonNode 表示任意 JSON 值，不公开树节点的 Java 访问器属性。
        if (schemas.has("JsonNode")) {
            schemas.putObject("JsonNode").put(DESCRIPTION, "Any JSON value, including null");
        }
        errorSchema(schemas.putObject(BUSINESS_ERROR), "code", "message", "requestId");
        errorSchema(schemas.putObject(OAUTH_ERROR), "error", "error_description");
    }

    private static void errorSchema(ObjectNode schema, String... fields) {
        schema.put("type", OBJECT_TYPE);
        var required = schema.putArray(REQUIRED);
        var properties = schema.putObject(SCHEMA_PROPERTIES);
        for (String field : fields) {
            required.add(field);
            properties.putObject(field).put("type", STRING_TYPE);
        }
    }

    private static void describeToken(ObjectNode operation) {
        ObjectNode responses = operation.withObjectProperty(RESPONSES);
        errorResponse(responses, "400", "Invalid request, grant type or scope", OAUTH_ERROR);
        header(errorResponse(responses, "401", "Invalid application credentials", OAUTH_ERROR), AUTHENTICATE, STRING_TYPE);
        errorResponse(responses, "406", "Only JSON responses are available", OAUTH_ERROR);
        header(errorResponse(responses, "429", "Token issuance rate limit reached", OAUTH_ERROR), RETRY_AFTER, INTEGER_TYPE);
        errorResponse(responses, "503", "Token issuance dependency unavailable", OAUTH_ERROR);
        for (var response : responses.properties()) {
            if (response.getValue() instanceof ObjectNode node) {
                header(node, "Pragma", STRING_TYPE);
                standardHeaders(node);
            }
        }
    }

    private static void describeBusiness(ObjectNode operation) {
        ObjectNode responses = operation.withObjectProperty(RESPONSES);
        errorResponse(responses, "400", "Invalid argument or business query", BUSINESS_ERROR);
        header(errorResponse(responses, "401", "Missing or invalid bearer token; missing credentials produce an empty response",
                BUSINESS_ERROR), AUTHENTICATE, STRING_TYPE);
        header(errorResponse(responses, "403", "Insufficient interface scope", BUSINESS_ERROR),
                AUTHENTICATE, STRING_TYPE);
        errorResponse(responses, "404", "Endpoint unavailable or resource not found", BUSINESS_ERROR);
        errorResponse(responses, "406", "Only JSON responses are available", BUSINESS_ERROR);
        header(errorResponse(responses, "429", "Request rate limit reached", BUSINESS_ERROR), RETRY_AFTER, INTEGER_TYPE);
        errorResponse(responses, "503", "Authorization or rate limit dependency unavailable", BUSINESS_ERROR);
        for (var response : responses.properties()) {
            if (response.getValue() instanceof ObjectNode node) {
                standardHeaders(node);
            }
        }
    }

    private static ObjectNode errorResponse(ObjectNode responses, String status, String description, String schema) {
        ObjectNode response = responses.putObject(status).put(DESCRIPTION, description);
        response.putObject("content").putObject("application/json").putObject(SCHEMA)
                .put("$ref", "#/components/schemas/" + schema);
        return response;
    }

    private static void standardHeaders(ObjectNode response) {
        header(response, "X-Request-ID", STRING_TYPE);
        header(response, "Cache-Control", STRING_TYPE);
    }

    private static void header(ObjectNode response, String name, String type) {
        response.withObjectProperty("headers").putObject(name).putObject(SCHEMA).put("type", type);
    }

    /**
     * 这个文档组不注册 URL，由管理端独占。独立 OpenAPIService 避免复用整站文档缓存。
     */
    private static final class V1Resource extends OpenApiResource {
        private final String baseUrl;

        V1Resource(ApplicationContext context, String baseUrl) {
            super("flow-open-api-v1", () -> context.getAutowireCapableBeanFactory().createBean(OpenAPIService.class),
                    context.getBean(AbstractRequestService.class), context.getBean(GenericResponseService.class),
                    context.getBean(OperationService.class), context.getBean(SpringDocConfigProperties.class),
                    context.getBean(SpringDocProviders.class), customizers());
            this.baseUrl = baseUrl;
        }

        byte[] render(Locale locale) {
            return writeJsonValue(getOpenApi(baseUrl, locale));
        }

        @Override
        protected boolean isFilterCondition(HandlerMethod handler, String path, String[] produces, String[] consumes,
                                            String[] headers) {
            return TOKEN_PATH.equals(path) || (path.startsWith("/open/v1/")
                    && handler.hasMethodAnnotation(OpenApiEndpoint.class));
        }

        @Override
        protected String getServerUrl(HttpServletRequest request, String docsPath) {
            return baseUrl;
        }

        private static SpringDocCustomizers customizers() {
            return new SpringDocCustomizers(Optional.empty(), Optional.empty(), Optional.empty(),
                    Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                    Optional.empty(), Optional.empty());
        }
    }
}
