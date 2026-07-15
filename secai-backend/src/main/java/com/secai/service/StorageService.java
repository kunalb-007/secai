package com.secai.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;

@Slf4j
@Service
public class StorageService {

    private final S3Client s3;
    private final String bucket;

    public StorageService(
            @Value("${app.storage.endpoint}") String endpoint,
            @Value("${app.storage.access-key}") String accessKey,
            @Value("${app.storage.secret-key}") String secretKey,
            @Value("${app.storage.bucket}") String bucket,
            @Value("${app.storage.region}") String region
    ) {
        this.bucket = bucket;
        this.s3 = S3Client.builder()
                .endpointOverride(URI.create(endpoint))  // for MinIO; remove for real S3
                .region(Region.of(region))
                .credentialsProvider(
                        StaticCredentialsProvider.create(
                                AwsBasicCredentials.create(accessKey, secretKey)
                        )
                )
                .forcePathStyle(true)                    // required for MinIO
                .build();
    }

    /**
     * Ensure bucket exists on startup.
     */
    @PostConstruct
    public void ensureBucketExists() {
        try {
            s3.headBucket(HeadBucketRequest.builder().bucket(bucket).build());
            log.info("Storage bucket '{}' is available",
                    bucket);
        } catch (NoSuchBucketException e) {
            s3.createBucket(CreateBucketRequest.builder().bucket(bucket).build());
            log.info("Created storage bucket '{}'",
                    bucket);
        }
    }

    /**
     * Upload a file and return the S3 storage path.
     * Path: /{orgId}/raw/{docId}/{filename}
     */
    public String upload(MultipartFile file, String orgId, String docId) {
        String key = String.format("%s/raw/%s/%s", orgId, docId, file.getOriginalFilename());
        try {
            s3.putObject(
                    PutObjectRequest.builder()
                            .bucket(bucket)
                            .key(key)
                            .contentType(file.getContentType())
                            .build(),
                    RequestBody.fromBytes(file.getBytes())
            );
        } catch (IOException e) {
            throw new RuntimeException("Failed to upload file: " + e.getMessage(), e);
        }

        log.info("Uploaded file to storage: {}",
                key);

        return key;
    }

    /**
     * Delete a file from storage.
     */
    public void delete(String storagePath) {
        s3.deleteObject(DeleteObjectRequest.builder()
                .bucket(bucket)
                .key(storagePath)
                .build());

        log.info("Deleted file from storage: {}",
                storagePath);
    }

    // ── ADD: Download file from S3 to a local temp file ──────────────────────────

    /**
     * Downloads a file from S3 to a local temporary file.
     * Caller is responsible for deleting the temp file after use.
     *
     * @param storagePath  the S3 key (e.g. "/{orgId}/raw/{docId}/file.pdf")
     * @param filename     used to set the correct file extension on the temp file
     * @return Path to the local temp file
     */
    public Path downloadToTemp(String storagePath, String filename) {
        try {
            String suffix = filename.contains(".")
                    ? filename.substring(filename.lastIndexOf('.'))
                    : ".tmp";

            Path tempDir = Files.createTempDirectory("secai-");
            Path tempFile = tempDir.resolve("document" + suffix);

            GetObjectRequest request = GetObjectRequest.builder()
                    .bucket(bucket)
                    .key(storagePath)
                    .build();

            s3.getObject(request, tempFile);

            return tempFile;

        } catch (Exception e) {
            throw new RuntimeException("Failed to download from S3: " + storagePath, e);
        }
    }

// ── ADD: Upload extracted text string to S3 ────────────────────────────────

    /**
     * Stores extracted document text to S3 for debugging retrieval issues.
     * Path: /{orgId}/extracted/{docId}/text.txt
     */
    public String uploadText(String text, String orgId, String docId) {
        String key = String.format("%s/extracted/%s/text.txt", orgId, docId);
        try {
            byte[] bytes = text.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            s3.putObject(
                    PutObjectRequest.builder()
                            .bucket(bucket)
                            .key(key)
                            .contentType("text/plain; charset=utf-8")
                            .build(),
                    RequestBody.fromBytes(bytes)
            );

            log.info("Stored extracted text: {}",
                    key);

            return key;
        } catch (Exception e) {
            // Non-fatal: extracted text storage is for debugging only
            // Log the error but don't fail the pipeline
            log.warn("Failed to store extracted text to S3 (non-fatal): {}", e.getMessage());
            return null;
        }
    }
}