package com.votivo.user_service.service;

import com.votivo.user_service.dto.keycloak.RemoteUser;
import com.votivo.user_service.entity.UserRole;
import com.votivo.user_service.image.ImageFormat;
import com.votivo.user_service.image.validation.exception.*;
import com.votivo.user_service.repository.UserPhotoRepository;
import com.votivo.user_service.service.exception.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.Set;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:photos;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa", "spring.datasource.password=",
        "spring.rabbitmq.listener.simple.auto-startup=false",
        "spring.rabbitmq.listener.direct.auto-startup=false",
        "spring.rabbitmq.dynamic=false", "app.identity.sync-enabled=false",
        "app.photo.max-size=1KB", "app.photo.max-width-pixels=4", "app.photo.max-height-pixels=4"
})
class PhotoIntegrationTest {
    @Autowired PhotoService service;
    @Autowired PhotoStore store;
    @Autowired ProfileWriter writer;
    @Autowired UserPhotoRepository photos;
    @Autowired com.votivo.user_service.repository.UserProfileRepository profiles;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactions;

    @Test
    void storesReplacesAndDeletesAcrossTransactionsWithoutChangingAnotherOwner() throws Exception {
        UUID first = createUser(), second = createUser();
        byte[] png = image("png", 2), jpeg = image("jpg", 3);
        service.replaceOwn(first, file(png));
        service.replaceOwn(second, file(jpeg));
        assertThat(service.getOwn(first).bytes()).isEqualTo(png);
        assertThat(service.getOwn(first).format()).isEqualTo(ImageFormat.PNG);
        service.replaceOwn(first, file(jpeg));
        assertThat(service.getOwn(first).bytes()).isEqualTo(jpeg);
        assertThat(photos.count()).isGreaterThanOrEqualTo(2);
        service.deleteOwn(first);
        service.deleteOwn(first);
        assertThatThrownBy(() -> service.getOwn(first)).isInstanceOf(PhotoNotFoundException.class);
        assertThat(service.getOwn(second).bytes()).isEqualTo(jpeg);
    }

    @Test
    void rejectedReplacementsPreservePreviousPhoto() throws Exception {
        UUID id = createUser();
        byte[] png = image("png", 2);
        service.replaceOwn(id, file(png));
        assertThatThrownBy(() -> service.replaceOwn(id, file(new byte[]{1})))
                .isInstanceOf(InvalidImageException.class);
        assertThatThrownBy(() -> service.replaceOwn(id, file(new byte[1025])))
                .isInstanceOf(ImageTooLargeException.class);
        byte[] tooWide = image("png", 5);
        assertThatThrownBy(() -> service.replaceOwn(id, file(tooWide)))
                .isInstanceOf(ImageDimensionsExceededException.class);
        byte[] gif = image("gif", 2);
        assertThatThrownBy(() -> service.replaceOwn(id, file(gif)))
                .isInstanceOf(UnsupportedImageFormatException.class);
        assertThat(service.getOwn(id).bytes()).isEqualTo(png);
    }

    @Test
    void synchronizationPreservesPhotoAndAccountDeletionRemovesItPermanently() throws Exception {
        UUID id = createUser();
        byte[] png = image("png", 2);
        service.replaceOwn(id, file(png));
        save(id, true);
        assertThat(service.getOwn(id).bytes()).isEqualTo(png);
        writer.softDelete(id);
        assertThat(photos.existsById(id)).isFalse();
        assertThatThrownBy(() -> store.replace(id, new com.votivo.user_service.image.ImageData(png, ImageFormat.PNG)))
                .isInstanceOf(UserNotFoundException.class);
        save(id, true);
        assertThatThrownBy(() -> service.getOwn(id)).isInstanceOf(UserNotFoundException.class);
    }

    @Test
    void blockedAccountCannotReadReplaceOrDeletePhoto() throws Exception {
        UUID id = createUser();
        byte[] png = image("png", 2);
        service.replaceOwn(id, file(png));
        save(id, false);
        assertThatThrownBy(() -> service.getOwn(id)).isInstanceOf(ProfileAccessDeniedException.class);
        assertThatThrownBy(() -> service.replaceOwn(id, file(png))).isInstanceOf(ProfileAccessDeniedException.class);
        assertThatThrownBy(() -> service.deleteOwn(id)).isInstanceOf(ProfileAccessDeniedException.class);
        assertThatThrownBy(() -> store.delete(id)).isInstanceOf(ProfileAccessDeniedException.class);
    }

    @Test
    void concurrentUploadWaitsForAccountDeletionAndCannotRestorePhoto() throws Exception {
        UUID id = createUser();
        byte[] png = image("png", 2);
        service.replaceOwn(id, file(png));
        var transaction = new org.springframework.transaction.support.TransactionTemplate(transactions);
        var started = new java.util.concurrent.CountDownLatch(1);
        try (var executor = java.util.concurrent.Executors.newSingleThreadExecutor()) {
            java.util.concurrent.Future<?> pending = transaction.execute(status -> {
                profiles.findByIdForUpdate(id).orElseThrow();
                var future = executor.submit(() -> {
                    started.countDown();
                    store.replace(id, new com.votivo.user_service.image.ImageData(png, ImageFormat.PNG));
                });
                try {
                    assertThat(started.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                    assertThatThrownBy(() -> future.get(150, java.util.concurrent.TimeUnit.MILLISECONDS))
                            .isInstanceOf(java.util.concurrent.TimeoutException.class);
                    writer.softDelete(id);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(exception);
                }
                return future;
            });
            assertThatThrownBy(() -> pending.get(5, java.util.concurrent.TimeUnit.SECONDS))
                    .isInstanceOf(java.util.concurrent.ExecutionException.class)
                    .hasCauseInstanceOf(UserNotFoundException.class);
        }
        assertThat(photos.existsById(id)).isFalse();
    }

    private UUID createUser() {
        UUID id = UUID.randomUUID();
        save(id, true);
        return id;
    }

    private void save(UUID id, boolean enabled) {
        writer.upsert(RemoteUser.builder().id(id).enabled(enabled).emailVerified(true).build(), Set.of(UserRole.user));
    }

    private MockMultipartFile file(byte[] data) {
        // Deliberately misleading filename and MIME type: trust the actual image bytes.
        return new MockMultipartFile("file", "untrusted.txt", "text/plain", data);
    }

    private byte[] image(String format, int size) throws Exception {
        var output = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB), format, output);
        return output.toByteArray();
    }
}
