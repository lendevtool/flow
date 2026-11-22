package me.lendev.flow.docgen.render;

import com.deepoove.poi.data.PictureType;
import com.deepoove.poi.data.Pictures;
import com.deepoove.poi.data.Rows;
import com.deepoove.poi.data.Tables;

import java.io.ByteArrayInputStream;
import java.util.Base64;
import java.util.function.LongConsumer;
import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageInputStream;

import tools.jackson.databind.JsonNode;

final class Media {
    private Media() {
    }

    static void validatePicture(byte[] bytes, String type, LongConsumer checkBudget) {
        try (var stream = new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))) {
            var readers = ImageIO.getImageReaders(stream);
            if (!readers.hasNext())
                throw new IllegalArgumentException();
            var reader = readers.next();
            try {
                reader.setInput(stream);
                var expected = switch (type) {
                    case "image/png" -> "png";
                    case "image/jpeg" -> "jpeg";
                    default -> throw new IllegalArgumentException();
                };
                if (!expected.equalsIgnoreCase(reader.getFormatName()))
                    throw new IllegalArgumentException();
                long pixels = Math.multiplyExact((long) reader.getWidth(0), reader.getHeight(0));
                if (pixels < 1 || pixels > 12_000_000)
                    throw new IllegalArgumentException();
                checkBudget.accept(pixels);
                var image = reader.read(0);
                image.flush();
            } finally {
                reader.dispose();
            }
        } catch (RenderFailure e) {
            throw e;
        } catch (Exception e) {
            throw RenderFailure.template("图片格式或尺寸无效");
        }
    }

    static Object picture(String key, Object raw, RenderBudget budget) {
        if (raw == null)
            return null;
        JsonNode value = InputSchema.JSON.valueToTree(raw);
        try {
            String type = value.path("mediaType").asString("");
            String encoded = value.path("base64").asString("");
            if (encoded.length() > 1_398_104)
                throw new IllegalArgumentException();
            byte[] bytes = Base64.getDecoder().decode(encoded);
            int width = value.path("width").asInt(0);
            int height = value.path("height").asInt(0);
            if (bytes.length > 1024 * 1024 || width < 1 || height < 1 || width > 4096 || height > 4096)
                throw new IllegalArgumentException();
            validatePicture(bytes, type, pixels -> budget.picture(pixels, key));
            return Pictures.ofBytes(bytes, type.equals("image/png") ? PictureType.PNG : PictureType.JPEG)
                    .size(width, height)
                    .altMeta(value.path("alt").asString(""))
                    .create();
        } catch (RuntimeException e) {
            throw RenderFailure.data(key, "图片格式、数量或尺寸越界");
        }
    }

    static Object table(String key, Object raw, RenderBudget budget) {
        if (raw == null)
            return null;
        JsonNode value = InputSchema.JSON.valueToTree(raw);
        JsonNode headers = value.path("headers");
        JsonNode rows = value.path("rows");
        if (!headers.isArray() || headers.isEmpty() || headers.size() > 100 || !rows.isArray())
            throw RenderFailure.data(key, "统计表需要表头及明细数组");
        budget.loop(rows.size(), key);
        budget.markup((rows.size() + 1L) * headers.size() * 320L, key);
        var table = Tables.ofPercentWidth("100%");
        table.addRow(
                Rows.of(strings(headers)).bgColor("E8EBEF").textColor("1D2731").textBold().repeatedHeader().create());
        for (var row : rows) {
            if (!row.isArray() || row.size() != headers.size())
                throw RenderFailure.data(key, "表格各行列数应一致");
            table.addRow(Rows.create(strings(row)));
        }
        return table.create();
    }

    private static String[] strings(JsonNode row) {
        var result = new String[row.size()];
        for (int i = 0; i < result.length; i++) {
            var cell = row.get(i);
            if (cell.isContainer())
                throw RenderFailure.data("$", "统计表单元格必须是标量");
            result[i] = cell.isNull() ? "" : cell.asString();
        }
        return result;
    }
}
