package ru.yanis.videoaudiobot.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import org.springframework.stereotype.Service;
import ru.yanis.videoaudiobot.config.AppProperties;
import ru.yanis.videoaudiobot.service.*;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

@Service
class S3StorageService implements StorageService {
  private final S3Client s3;
  private final S3Presigner signer;
  private final AppProperties.S3 config;
  private final ObjectMapper json;

  S3StorageService(S3Client s3, S3Presigner signer, AppProperties p, ObjectMapper json) {
    this.s3 = s3;
    this.signer = signer;
    this.config = p.s3();
    this.json = json;
  }

  public String prefix(UUID id) {
    return config.prefix().replaceAll("/+$", "") + "/" + id + "/";
  }

  public String key(UUID id, UUID attempt, String name) {
    return prefix(id) + attempt + "/" + name;
  }

  public void put(String key, Path path, String contentType) {
    s3.putObject(
        b -> b.bucket(config.bucket()).key(key).contentType(contentType),
        RequestBody.fromFile(path));
  }

  public void get(String key, Path path) {
    try {
      Files.deleteIfExists(path);
    } catch (Exception e) {
      throw new ProcessingException("LOCAL_IO", true);
    }
    s3.getObject(b -> b.bucket(config.bucket()).key(key), path);
  }

  public void putJson(String key, Object value) {
    try {
      s3.putObject(
          b -> b.bucket(config.bucket()).key(key).contentType("application/json"),
          RequestBody.fromBytes(json.writeValueAsBytes(value)));
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new ProcessingException("JSON_WRITE", false);
    }
  }

  public <T> T getJson(String key, Class<T> type) {
    try {
      return json.readValue(
          s3.getObjectAsBytes(b -> b.bucket(config.bucket()).key(key)).asByteArray(), type);
    } catch (java.io.IOException e) {
      throw new ProcessingException("JSON_READ", false);
    }
  }

  public String signedUrl(String key, Duration ttl) {
    return signer
        .presignGetObject(
            b ->
                b.signatureDuration(ttl)
                    .getObjectRequest(
                        g ->
                            g.bucket(config.bucket())
                                .key(key)
                                .responseContentDisposition("attachment; filename=transcript.txt")))
        .url()
        .toString();
  }

  private void delete(List<ObjectIdentifier> objects) {
    if (objects.isEmpty()) return;
    var result =
        s3.deleteObjects(
            b -> b.bucket(config.bucket()).delete(d -> d.objects(objects).quiet(true)));
    if (!result.errors().isEmpty()) throw new ProcessingException("S3_DELETE_INCOMPLETE", true);
  }

  public void deleteJob(UUID id) {
    String prefix = prefix(id);
    // A versioned bucket needs version IDs purged, not just new delete markers.
    if (config.purgeVersions()) {
      for (var page :
          s3.listObjectVersionsPaginator(b -> b.bucket(config.bucket()).prefix(prefix))) {
        List<ObjectIdentifier> objects = new ArrayList<>();
        page.versions()
            .forEach(
                v ->
                    objects.add(
                        ObjectIdentifier.builder().key(v.key()).versionId(v.versionId()).build()));
        page.deleteMarkers()
            .forEach(
                v ->
                    objects.add(
                        ObjectIdentifier.builder().key(v.key()).versionId(v.versionId()).build()));
        for (int i = 0; i < objects.size(); i += 1000)
          delete(objects.subList(i, Math.min(objects.size(), i + 1000)));
      }
    } else {
      for (var page : s3.listObjectsV2Paginator(b -> b.bucket(config.bucket()).prefix(prefix)))
        delete(
            page.contents().stream()
                .map(o -> ObjectIdentifier.builder().key(o.key()).build())
                .toList());
    }
    for (var page : s3.listMultipartUploadsPaginator(b -> b.bucket(config.bucket()).prefix(prefix)))
      for (var upload : page.uploads())
        s3.abortMultipartUpload(
            b -> b.bucket(config.bucket()).key(upload.key()).uploadId(upload.uploadId()));
  }
}
