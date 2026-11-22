package me.lendev.flow.docgen;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Type;

import me.lendev.flow.openapi.OpenApiException;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.RequestBodyAdviceAdapter;

/**
 * 在 Jackson 解析前限制文档生成的 JSON 请求体，包括分块传输。
 */
@RestControllerAdvice(assignableTypes = {DocumentGenerationController.class, OpenDocGenerationController.class})
class DocGenerationRequestBody extends RequestBodyAdviceAdapter {
    private static final int INPUT_LIMIT = 4 * 1024 * 1024;
    private static final int CONFIG_LIMIT = INPUT_LIMIT + 256 * 1024;

    @Override
    public boolean supports(MethodParameter method, Type target, Class<? extends HttpMessageConverter<?>> converter) {
        return method.hasParameterAnnotation(RequestBody.class);
    }

    @Override
    public HttpInputMessage beforeBodyRead(HttpInputMessage message, MethodParameter method, Type target,
                                           Class<? extends HttpMessageConverter<?>> converter) throws IOException {
        int limit = method.getParameterType() == DocGenerationModels.VersionInput.class ? CONFIG_LIMIT : INPUT_LIMIT;
        long length = message.getHeaders().getContentLength();
        if (length > limit) throw oversized(method);
        byte[] bytes = message.getBody().readNBytes(limit + 1);
        if (bytes.length > limit) throw oversized(method);
        return new HttpInputMessage() {
            @Override
            public HttpHeaders getHeaders() {
                return message.getHeaders();
            }

            @Override
            public InputStream getBody() {
                return new ByteArrayInputStream(bytes);
            }
        };
    }

    private RuntimeException oversized(MethodParameter method) {
        String message = "文档生成 JSON 请求超过大小限制";
        if (OpenDocGenerationController.class.isAssignableFrom(method.getContainingClass())) {
            return new OpenApiException(413, "FILE_TOO_LARGE", message);
        }
        return new DocGenerationFailure(HttpStatus.CONTENT_TOO_LARGE, "FILE_TOO_LARGE", message);
    }
}
