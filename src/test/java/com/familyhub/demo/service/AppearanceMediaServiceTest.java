package com.familyhub.demo.service;

import com.familyhub.demo.exception.BadRequestException;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

import static org.assertj.core.api.Assertions.*;

class AppearanceMediaServiceTest {
    @TempDir java.nio.file.Path directory;

    private byte[] image(String format, int width, int height) throws Exception {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        var graphics = image.createGraphics();
        graphics.setColor(Color.RED);
        graphics.fillRect(0, 0, width, height);
        graphics.dispose();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ImageIO.write(image, format, bytes);
        return bytes.toByteArray();
    }

    @Test void processesJpegAndPngWithoutRetainingOriginalFormat() throws Exception {
        AppearanceMediaService service = new AppearanceMediaService(directory.toString());
        for (String format : new String[]{"png", "jpg"}) {
            String type = format.equals("png") ? "image/png" : "image/jpeg";
            byte[] result = service.process(new MockMultipartFile("file", "untrusted.heic", type,
                    image(format, 800, 500)));
            assertThat(result).startsWith((byte) 0xff, (byte) 0xd8);
            assertThat(ImageIO.read(new java.io.ByteArrayInputStream(result)).getWidth()).isEqualTo(800);
        }
    }

    @Test void rejectsNonImageMalformedOversizedAndMismatchedContent() throws Exception {
        AppearanceMediaService service = new AppearanceMediaService(directory.toString());
        assertThatThrownBy(() -> service.process(new MockMultipartFile("file", "x.txt", "text/plain", "hello".getBytes())))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> service.process(new MockMultipartFile("file", "x.png", "image/png", "not image".getBytes())))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> service.process(new MockMultipartFile("file", "x.png", "image/jpeg", image("png", 800, 500))))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> service.process(new MockMultipartFile("file", "x.jpg", "image/jpeg", new byte[12 * 1024 * 1024 + 1])))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> service.process(new MockMultipartFile("file", "x.png", "image/png", image("png", 100, 100))))
                .isInstanceOf(BadRequestException.class);
    }

    @Test void writesReadsAndDeletesOnlyFamilyScopedFile() throws Exception {
        AppearanceMediaService service = new AppearanceMediaService(directory.toString());
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        UUID key = UUID.randomUUID();
        byte[] bytes = image("jpg", 800, 500);
        service.write(first, key, bytes);
        assertThat(service.read(first, key)).isEqualTo(bytes);
        assertThatThrownBy(() -> service.read(second, key)).isInstanceOf(IllegalStateException.class);
        service.delete(first, key);
        assertThat(Files.exists(directory.resolve(first.toString()).resolve(key + ".jpg"))).isFalse();
    }
}
