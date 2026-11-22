package me.lendev.flow.docgen.render;

/**
 * 渲染器对外报告的稳定错误码及字段路径。
 */
public final class RenderFailure extends RuntimeException {
    private final String code;
    private final String path;

    RenderFailure(String code, String path, String message) {
        super(message);
        this.code = code;
        this.path = path;
    }

    public String code() {
        return code;
    }

    public String path() {
        return path;
    }

    static RenderFailure template(String message) {
        return new RenderFailure("INVALID_TEMPLATE", "$", message);
    }

    static RenderFailure input(String path, String message) {
        return new RenderFailure("INVALID_INPUT", path, message);
    }

    static RenderFailure data(String path, String message) {
        return new RenderFailure("INVALID_RENDER_DATA", path, message);
    }

    static void require(boolean condition, String message) {
        if (!condition)
            throw template(message);
    }
}
