package me.lendev.flow.docgen.render;

/**
 * 各层子模板共享本次展开与媒体预算。
 */
final class RenderBudget {
    private int expanded;
    private int images;
    private int loaded;
    private long pixels;
    private long intermediate;
    private long markup;

    void markup(long bytes, String key) {
        markup += bytes;
        if (markup > 48L * 1024 * 1024)
            throw RenderFailure.data(key, "展开后的文档内容过大");
    }

    void loop(int size, String key) {
        expanded += size;
        if (size > 500 || expanded > 2000)
            throw RenderFailure.data(key, "明细展开数量超过上限");
    }

    void picture(long count, String key) {
        pixels += count;
        if (++images > 10 || pixels > 24_000_000)
            throw RenderFailure.data(key, "图片数量或像素超过上限");
    }

    void loaded(int size) {
        loaded += size;
        if (loaded > 20 * 1024 * 1024)
            throw RenderFailure.template("依赖模板累计体积过大");
    }

    void intermediate(int size) {
        intermediate += size;
        if (intermediate > 32L * 1024 * 1024)
            throw RenderFailure.data("$", "子文档累计体积过大");
    }
}
