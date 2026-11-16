package com.malefashion.catalog.service;

import com.malefashion.catalog.config.MinioConfig;
import io.minio.BucketExistsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.InputStream;

@Service
@RequiredArgsConstructor
@Slf4j
public class MinioStorageService {

    private final MinioClient minioClient;
    private final MinioConfig minioConfig;

    public void ensureBucketExists() {
        try {
            String bucket = minioConfig.getBucketName();
            boolean exists = minioClient.bucketExists(BucketExistsArgs.builder().bucket(bucket).build());
            if (!exists) {
                minioClient.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
                log.info("Created MinIO bucket: {}", bucket);
            }
        } catch (Exception e) {
            log.error("Failed to check or create MinIO bucket: {}", e.getMessage(), e);
        }
    }

    public String uploadImage(String objectName, InputStream stream, long size, String contentType) {
        try {
            ensureBucketExists();
            String bucket = minioConfig.getBucketName();
            minioClient.putObject(
                    PutObjectArgs.builder()
                            .bucket(bucket)
                            .object(objectName)
                            .stream(stream, size, -1)
                            .contentType(contentType != null ? contentType : "image/jpeg")
                            .build()
            );
            return getImageUrl(objectName);
        } catch (Exception e) {
            log.error("Failed to upload image {}: {}", objectName, e.getMessage(), e);
            throw new RuntimeException("Error uploading image to MinIO", e);
        }
    }

    public String getImageUrl(String objectName) {
        String endpoint = minioConfig.getEndpoint();
        if (endpoint.endsWith("/")) {
            endpoint = endpoint.substring(0, endpoint.length() - 1);
        }
        return endpoint + "/" + minioConfig.getBucketName() + "/" + objectName;
    }
}
