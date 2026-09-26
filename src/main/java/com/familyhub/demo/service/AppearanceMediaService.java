package com.familyhub.demo.service;

import com.familyhub.demo.exception.BadRequestException;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Iterator;
import java.util.UUID;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.MemoryCacheImageInputStream;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import org.springframework.beans.factory.annotation.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

@Service
public class AppearanceMediaService {
    private static final Logger log = LoggerFactory.getLogger(AppearanceMediaService.class);
    public static final long MAX_UPLOAD_BYTES = 12L * 1024 * 1024;
    private static final long MAX_PIXELS = 16_000_000;
    private final Path root;

    public AppearanceMediaService(@Value("${familyhub.media-dir:./media}") String mediaDir) {
        root = Path.of(mediaDir).toAbsolutePath().normalize();
    }

    public byte[] process(MultipartFile file) {
        if (file.isEmpty() || file.getSize() > MAX_UPLOAD_BYTES) {
            throw new BadRequestException("Choose an image smaller than 12 MiB");
        }
        String contentType = file.getContentType();
        if (!"image/jpeg".equals(contentType) && !"image/png".equals(contentType)) {
            throw new BadRequestException("Only JPEG and PNG images are supported");
        }
        try {
            byte[] input = file.getBytes();
            if (input.length > MAX_UPLOAD_BYTES) throw new BadRequestException("Image is too large");
            try (var stream = new MemoryCacheImageInputStream(new ByteArrayInputStream(input))) {
                Iterator<ImageReader> readers = ImageIO.getImageReaders(stream);
                if (!readers.hasNext()) throw new BadRequestException("Image content is invalid");
                ImageReader reader = readers.next();
                try {
                    reader.setInput(stream, true, true);
                    String format = reader.getFormatName();
                    if (!("image/jpeg".equals(contentType) && "JPEG".equalsIgnoreCase(format))
                            && !("image/png".equals(contentType) && "PNG".equalsIgnoreCase(format))) {
                        throw new BadRequestException("Image content does not match its type");
                    }
                    int width = reader.getWidth(0);
                    int height = reader.getHeight(0);
                    if (width < 640 || height < 360 || width > 6000 || height > 6000
                            || (long) width * height > MAX_PIXELS) {
                        throw new BadRequestException("Image dimensions must be at least 640×360 and at most 16 megapixels");
                    }
                    BufferedImage source = reader.read(0);
                    if (source == null) throw new BadRequestException("Image content is invalid");
                    double scale = Math.min(1.0, Math.min(1920.0 / width, 1200.0 / height));
                    int targetWidth = Math.max(1, (int) Math.round(width * scale));
                    int targetHeight = Math.max(1, (int) Math.round(height * scale));
                    BufferedImage output = new BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_RGB);
                    Graphics2D graphics = output.createGraphics();
                    try {
                        graphics.setColor(Color.WHITE);
                        graphics.fillRect(0, 0, targetWidth, targetHeight);
                        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                        graphics.drawImage(source, 0, 0, targetWidth, targetHeight, null);
                    } finally {
                        graphics.dispose();
                    }
                    Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("JPEG");
                    if (!writers.hasNext()) throw new IllegalStateException("JPEG encoder unavailable");
                    ImageWriter writer = writers.next();
                    try (ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                         var destination = new MemoryCacheImageOutputStream(bytes)) {
                        writer.setOutput(destination);
                        ImageWriteParam params = writer.getDefaultWriteParam();
                        params.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                        params.setCompressionQuality(0.82f);
                        writer.write(null, new IIOImage(output, null, null), params);
                        destination.flush();
                        return bytes.toByteArray();
                    } finally {
                        writer.dispose();
                    }
                } finally {
                    reader.dispose();
                }
            }
        } catch (BadRequestException ex) {
            throw ex;
        } catch (IOException | RuntimeException ex) {
            throw new BadRequestException("Image could not be decoded");
        }
    }

    private Path path(UUID familyId, UUID key) {
        return root.resolve(familyId.toString()).resolve(key + ".jpg");
    }

    public void write(UUID familyId, UUID key, byte[] bytes) {
        Path directory = root.resolve(familyId.toString());
        Path temporary = null;
        try {
            Files.createDirectories(directory);
            temporary = Files.createTempFile(directory, ".upload-", ".tmp");
            Files.write(temporary, bytes);
            Files.move(temporary, path(familyId, key), StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException ex) {
            throw new IllegalStateException("Could not store household image", ex);
        } finally {
            if (temporary != null) {
                try { Files.deleteIfExists(temporary); } catch (IOException ignored) { }
            }
        }
    }

    public byte[] read(UUID familyId, UUID key) {
        try {
            return Files.readAllBytes(path(familyId, key));
        } catch (IOException ex) {
            throw new IllegalStateException("Household image is unavailable", ex);
        }
    }

    public void delete(UUID familyId, UUID key) {
        try {
            Files.deleteIfExists(path(familyId, key));
        } catch (IOException ex) {
            throw new IllegalStateException("Could not remove household image", ex);
        }
    }

    public void deleteFamily(UUID familyId) {
        Path directory = root.resolve(familyId.toString());
        if (!Files.isDirectory(directory)) return;
        try (var paths = Files.list(directory)) {
            for (Path path : paths.toList()) Files.deleteIfExists(path);
            Files.deleteIfExists(directory);
        } catch (IOException ex) {
            throw new IllegalStateException("Could not remove household media", ex);
        }
    }

    /** Never remove a referenced file before the database transaction commits. */
    public void deleteAfterCommit(UUID familyId, UUID key) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    delete(familyId, key);
                } catch (RuntimeException ex) {
                    log.error("Could not remove replaced family photo after commit", ex);
                }
            }
        });
    }

    public void deleteFamilyAfterCommit(UUID familyId) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    deleteFamily(familyId);
                } catch (RuntimeException ex) {
                    log.error("Could not remove deleted family media after commit", ex);
                }
            }
        });
    }

    /** A new file is unreferenced if its database transaction rolls back. */
    public void deleteOnRollback(UUID familyId, UUID key) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status == STATUS_COMMITTED) return;
                try {
                    delete(familyId, key);
                } catch (RuntimeException ex) {
                    log.error("Could not remove uncommitted family photo", ex);
                }
            }
        });
    }
}
