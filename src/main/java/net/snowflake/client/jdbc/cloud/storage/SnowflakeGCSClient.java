package net.snowflake.client.jdbc.cloud.storage;

import static net.snowflake.client.jdbc.SnowflakeUtil.convertSystemPropertyToBooleanValue;
import static net.snowflake.client.jdbc.SnowflakeUtil.createCaseInsensitiveMap;
import static net.snowflake.client.jdbc.SnowflakeUtil.getRootCause;
import static net.snowflake.client.jdbc.SnowflakeUtil.isBlank;
import static net.snowflake.client.jdbc.SnowflakeUtil.isNullOrEmpty;
import static net.snowflake.client.jdbc.SnowflakeUtil.systemGetProperty;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.SocketTimeoutException;
import java.net.URISyntaxException;
import java.security.InvalidKeyException;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import net.snowflake.client.core.ExecTimeTelemetryData;
import net.snowflake.client.core.HttpClientSettingsKey;
import net.snowflake.client.core.HttpResponseContextDto;
import net.snowflake.client.core.HttpUtil;
import net.snowflake.client.core.ObjectMapperFactory;
import net.snowflake.client.core.SFSession;
import net.snowflake.client.core.SFSessionProperty;
import net.snowflake.client.core.SnowflakeJdbcInternalApi;
import net.snowflake.client.jdbc.ErrorCode;
import net.snowflake.client.jdbc.FileBackedOutputStream;
import net.snowflake.client.jdbc.MatDesc;
import net.snowflake.client.jdbc.RestRequest;
import net.snowflake.client.jdbc.SnowflakeFileTransferAgent;
import net.snowflake.client.jdbc.SnowflakeSQLException;
import net.snowflake.client.jdbc.SnowflakeSQLLoggedException;
import net.snowflake.client.jdbc.SnowflakeUtil;
import net.snowflake.client.log.ArgSupplier;
import net.snowflake.client.log.SFLogger;
import net.snowflake.client.log.SFLoggerFactory;
import net.snowflake.client.util.SFPair;
import net.snowflake.client.util.Stopwatch;
import net.snowflake.common.core.RemoteStoreFileEncryptionMaterial;
import net.snowflake.common.core.SqlState;
import org.apache.commons.io.IOUtils;
import org.apache.http.HttpResponse;
import org.apache.http.client.HttpResponseException;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPut;
import org.apache.http.client.utils.URIBuilder;
import org.apache.http.entity.InputStreamEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.util.EntityUtils;

/** Encapsulates the GCS Storage client and all GCS operations and logic */
public class SnowflakeGCSClient implements SnowflakeStorageClient {
  @SnowflakeJdbcInternalApi
  public static final String DISABLE_GCS_DEFAULT_CREDENTIALS_PROPERTY_NAME =
      "net.snowflake.jdbc.disableGcsDefaultCredentials";

  private static final String GCS_ENCRYPTIONDATAPROP = "encryptiondata";
  private static final String localFileSep = systemGetProperty("file.separator");
  private static final String GCS_METADATA_PREFIX = "x-goog-meta-";
  private static final String GCS_STREAMING_INGEST_CLIENT_NAME = "ingestclientname";
  private static final String GCS_STREAMING_INGEST_CLIENT_KEY = "ingestclientkey";

  private int encryptionKeySize = 0; // used for PUTs
  private StageInfo stageInfo;
  private RemoteStoreFileEncryptionMaterial encMat;
  private SFSession session = null;
  private GCSAccessStrategy gcsAccessStrategy = null;

  private static final SFLogger logger = SFLoggerFactory.getLogger(SnowflakeGCSClient.class);

  private SnowflakeGCSClient() {}

  /*
   * Factory method for a SnowflakeGCSClient object
   * @param stage   The stage information that the client will operate on
   * @param encMat  The encryption material
   *                required to decrypt/encrypt content in stage
   */
  public static SnowflakeGCSClient createSnowflakeGCSClient(
      StageInfo stage, RemoteStoreFileEncryptionMaterial encMat, SFSession session)
      throws SnowflakeSQLException {
    logger.debug(
        "Initializing Snowflake GCS client with encryption: {}", encMat != null ? "true" : "false");
    SnowflakeGCSClient sfGcsClient = new SnowflakeGCSClient();
    sfGcsClient.setupGCSClient(stage, encMat, session);

    return sfGcsClient;
  }

  // Returns the Max number of retry attempts
  @Override
  public int getMaxRetries() {
    if (session != null
        && session
            .getConnectionPropertiesMap()
            .containsKey(SFSessionProperty.PUT_GET_MAX_RETRIES)) {
      return (int) session.getConnectionPropertiesMap().get(SFSessionProperty.PUT_GET_MAX_RETRIES);
    }
    return 25;
  }

  // Returns the max exponent for multiplying backoff with the power of 2, the value
  // of 4 will give us 16secs as the max number of time to sleep before retry
  @Override
  public int getRetryBackoffMaxExponent() {
    return 4;
  }

  // Returns the min number of milliseconds to sleep before retry
  @Override
  public int getRetryBackoffMin() {
    return 1000;
  }

  /**
   * @return Returns true if encryption is enabled
   */
  @Override
  public boolean isEncrypting() {
    return encryptionKeySize > 0 && this.stageInfo.getIsClientSideEncrypted();
  }

  /**
   * @return Returns the size of the encryption key
   */
  @Override
  public int getEncryptionKeySize() {
    return encryptionKeySize;
  }

  /**
   * @return Whether this client requires the use of presigned URLs for upload and download instead
   *     of credentials that work for all files uploaded/ downloaded to a stage path. True for GCS.
   */
  @Override
  public boolean requirePresignedUrl() {
    Map<?, ?> credentialsMap = stageInfo.getCredentials();
    return !(credentialsMap != null && credentialsMap.containsKey("GCS_ACCESS_TOKEN"));
  }

  @Override
  public void renew(Map<?, ?> stageCredentials) throws SnowflakeSQLException {
    logger.debug("Renewing the Snowflake GCS client");
    stageInfo.setCredentials(stageCredentials);
    setupGCSClient(stageInfo, encMat, session);
  }

  @Override
  public void shutdown() {
    if (this.gcsAccessStrategy != null) {
      this.gcsAccessStrategy.shutdown();
    }
  }

  /**
   * listObjects gets all the objects in a path
   *
   * @param remoteStorageLocation bucket name
   * @param prefix Path
   * @return a collection of storage summary objects
   * @throws StorageProviderException cloud storage provider error
   */
  @Override
  public StorageObjectSummaryCollection listObjects(String remoteStorageLocation, String prefix)
      throws StorageProviderException {
    return this.gcsAccessStrategy.listObjects(remoteStorageLocation, prefix);
  }

  @Override
  public StorageObjectMetadata getObjectMetadata(String remoteStorageLocation, String prefix)
      throws StorageProviderException {
    return this.gcsAccessStrategy.getObjectMetadata(remoteStorageLocation, prefix);
  }

  /**
   * Download a file from remote storage.
   *
   * @param session session object
   * @param command command to download file
   * @param localLocation local file path
   * @param destFileName destination file name
   * @param parallelism [ not used by the GCP implementation ]
   * @param remoteStorageLocation remote storage location, i.e. bucket for S3
   * @param stageFilePath stage file path
   * @param stageRegion region name where the stage persists
   * @param presignedUrl Credential to use for download
   * @param queryId last query id
   * @throws SnowflakeSQLException download failure
   */
  @Override
  public void download(
      SFSession session,
      String command,
      String localLocation,
      String destFileName,
      int parallelism,
      String remoteStorageLocation,
      String stageFilePath,
      String stageRegion,
      String presignedUrl,
      String queryId)
      throws SnowflakeSQLException {
    String localFilePath = localLocation + localFileSep + destFileName;
    logger.debug(
        "Staring download of file from GCS stage path: {} to {}", stageFilePath, localFilePath);
    int retryCount = 0;
    Stopwatch stopwatch = new Stopwatch();
    stopwatch.start();
    File localFile = new File(localFilePath);
    do {
      try {
        String key = null;
        String iv = null;
        long downloadMillis = 0;
        if (!isNullOrEmpty(presignedUrl)) {
          logger.debug("Starting download with presigned URL", false);
          URIBuilder uriBuilder = new URIBuilder(presignedUrl);

          HttpGet httpRequest = new HttpGet(uriBuilder.build());
          httpRequest.addHeader("accept-encoding", "GZIP");

          logger.debug("Fetching result: {}", scrubPresignedUrl(presignedUrl));

          CloseableHttpClient httpClient =
              HttpUtil.getHttpClientWithoutDecompression(
                  session.getHttpClientKey(), session.getHttpHeadersCustomizers());

          // Get the file on storage using the presigned url
          HttpResponseContextDto responseDto =
              RestRequest.executeWithRetries(
                  httpClient,
                  httpRequest,
                  session.getNetworkTimeoutInMilli() / 1000, // retry timeout
                  0,
                  session.getHttpClientSocketTimeout(),
                  getMaxRetries(),
                  0, // no socket timeout injection
                  null, // no canceling
                  false, // no cookie
                  false, // no retry
                  false, // no request_guid
                  true, // retry on HTTP 403
                  false,
                  new ExecTimeTelemetryData(),
                  session);
          HttpResponse response = responseDto.getHttpResponse();

          logger.debug(
              "Call returned for URL: {}",
              (ArgSupplier) () -> scrubPresignedUrl(this.stageInfo.getPresignedUrl()));
          if (isSuccessStatusCode(response.getStatusLine().getStatusCode())) {
            try {
              InputStream bodyStream = response.getEntity().getContent();
              byte[] buffer = new byte[8 * 1024];
              int bytesRead;
              OutputStream outStream = new FileOutputStream(localFile);
              while ((bytesRead = bodyStream.read(buffer)) != -1) {
                outStream.write(buffer, 0, bytesRead);
              }
              outStream.flush();
              outStream.close();
              bodyStream.close();
              SnowflakeUtil.assureOnlyUserAccessibleFilePermissions(
                  localFile, session.isOwnerOnlyStageFilePermissionsEnabled());
              if (isEncrypting()) {
                Map<String, String> userDefinedHeaders =
                    createCaseInsensitiveMap(response.getAllHeaders());
                AbstractMap.SimpleEntry<String, String> encryptionData =
                    parseEncryptionData(
                        userDefinedHeaders.get(GCS_METADATA_PREFIX + GCS_ENCRYPTIONDATAPROP),
                        queryId);
                key = encryptionData.getKey();
                iv = encryptionData.getValue();
              }
              stopwatch.stop();
              downloadMillis = stopwatch.elapsedMillis();
              logger.debug("Download successful", false);
            } catch (IOException ex) {
              logger.debug("Download unsuccessful {}", ex);
              handleStorageException(
                  ex, ++retryCount, StorageHelper.DOWNLOAD, session, command, queryId);
            }
          } else {
            Exception ex =
                new HttpResponseException(
                    response.getStatusLine().getStatusCode(),
                    EntityUtils.toString(response.getEntity()));
            handleStorageException(
                ex, ++retryCount, StorageHelper.DOWNLOAD, session, command, queryId);
          }
        } else {
          Map<String, String> userDefinedMetadata =
              this.gcsAccessStrategy.download(
                  parallelism, remoteStorageLocation, stageFilePath, localFile);
          SnowflakeUtil.assureOnlyUserAccessibleFilePermissions(
              localFile, session.isOwnerOnlyStageFilePermissionsEnabled());
          stopwatch.stop();
          downloadMillis = stopwatch.elapsedMillis();
          logger.debug("Download successful", false);

          if (isEncrypting()) {
            if (!userDefinedMetadata.isEmpty()) {
              AbstractMap.SimpleEntry<String, String> encryptionData =
                  parseEncryptionData(userDefinedMetadata.get(GCS_ENCRYPTIONDATAPROP), queryId);

              key = encryptionData.getKey();
              iv = encryptionData.getValue();
            }
          }
        }

        if (!isNullOrEmpty(iv)
            && !isNullOrEmpty(key)
            && this.isEncrypting()
            && this.getEncryptionKeySize() <= 256) {
          if (key == null || iv == null) {
            throw new SnowflakeSQLLoggedException(
                queryId,
                session,
                StorageHelper.getOperationException(StorageHelper.DOWNLOAD).getMessageCode(),
                SqlState.INTERNAL_ERROR,
                "File metadata incomplete");
          }

          // Decrypt file
          try {
            stopwatch.start();
            EncryptionProvider.decrypt(localFile, key, iv, this.encMat);
            stopwatch.stop();
            long decryptMillis = stopwatch.elapsedMillis();
            logger.info(
                "GCS file {} downloaded to {}. It took {} ms (download: {} ms, decryption: {} ms) with {} retries",
                stageFilePath,
                localFile.getAbsolutePath(),
                downloadMillis + decryptMillis,
                downloadMillis,
                decryptMillis,
                retryCount);
          } catch (Exception ex) {
            logger.error("Error decrypting file", ex);
            throw new SnowflakeSQLLoggedException(
                queryId,
                session,
                StorageHelper.getOperationException(StorageHelper.DOWNLOAD).getMessageCode(),
                SqlState.INTERNAL_ERROR,
                "Cannot decrypt file");
          }
        } else {
          logger.info(
              "GCS file {} downloaded to {}. It took {} ms with {} retries",
              stageFilePath,
              localFile.getAbsolutePath(),
              downloadMillis,
              retryCount);
        }
        return;
      } catch (Exception ex) {
        logger.debug("Download unsuccessful {}", ex);
        handleStorageException(ex, ++retryCount, StorageHelper.DOWNLOAD, session, command, queryId);
      }
    } while (retryCount <= getMaxRetries());

    throw new SnowflakeSQLLoggedException(
        queryId,
        session,
        StorageHelper.getOperationException(StorageHelper.DOWNLOAD).getMessageCode(),
        SqlState.INTERNAL_ERROR,
        "Unexpected: download unsuccessful without exception!");
  }

  /**
   * Download a file from remote storage
   *
   * @param session session object
   * @param command command to download file
   * @param parallelism number of threads for parallel downloading
   * @param remoteStorageLocation remote storage location, i.e. bucket for s3
   * @param stageFilePath stage file path
   * @param stageRegion region name where the stage persists
   * @param presignedUrl Signed credential for download
   * @param queryId last query id
   * @return input file stream
   * @throws SnowflakeSQLException when download failure
   */
  @Override
  public InputStream downloadToStream(
      SFSession session,
      String command,
      int parallelism,
      String remoteStorageLocation,
      String stageFilePath,
      String stageRegion,
      String presignedUrl,
      String queryId)
      throws SnowflakeSQLException {
    logger.debug("Staring download of file from GCS stage path: {} to input stream", stageFilePath);
    int retryCount = 0;
    Stopwatch stopwatch = new Stopwatch();
    stopwatch.start();
    InputStream inputStream = null;
    long downloadMillis = 0;
    do {
      try {
        String key = null;
        String iv = null;

        if (!isNullOrEmpty(presignedUrl)) {
          logger.debug("Starting download with presigned URL", false);
          URIBuilder uriBuilder = new URIBuilder(presignedUrl);

          HttpGet httpRequest = new HttpGet(uriBuilder.build());
          httpRequest.addHeader("accept-encoding", "GZIP");

          logger.debug("Fetching result: {}", scrubPresignedUrl(presignedUrl));

          CloseableHttpClient httpClient =
              HttpUtil.getHttpClientWithoutDecompression(
                  session.getHttpClientKey(), session.getHttpHeadersCustomizers());

          // Put the file on storage using the presigned url
          HttpResponse response =
              RestRequest.executeWithRetries(
                      httpClient,
                      httpRequest,
                      session.getNetworkTimeoutInMilli() / 1000, // retry timeout
                      0,
                      session.getHttpClientSocketTimeout(),
                      getMaxRetries(),
                      0, // no socket timeout injection
                      null, // no canceling
                      false, // no cookie
                      false, // no retry
                      false, // no request_guid
                      true, // retry on HTTP 403
                      false,
                      new ExecTimeTelemetryData(),
                      session)
                  .getHttpResponse();

          logger.debug(
              "Call returned for URL: {}",
              (ArgSupplier) () -> scrubPresignedUrl(this.stageInfo.getPresignedUrl()));
          if (isSuccessStatusCode(response.getStatusLine().getStatusCode())) {
            try {
              inputStream = response.getEntity().getContent();

              if (isEncrypting()) {
                Map<String, String> userDefinedHeaders =
                    createCaseInsensitiveMap(response.getAllHeaders());
                AbstractMap.SimpleEntry<String, String> encryptionData =
                    parseEncryptionData(
                        userDefinedHeaders.get(GCS_METADATA_PREFIX + GCS_ENCRYPTIONDATAPROP),
                        queryId);
                key = encryptionData.getKey();
                iv = encryptionData.getValue();
              }
              stopwatch.stop();
              downloadMillis = stopwatch.elapsedMillis();
              logger.debug("Download successful", false);
            } catch (IOException ex) {
              logger.debug("Download unsuccessful {}", ex);
              handleStorageException(
                  ex, ++retryCount, StorageHelper.DOWNLOAD, session, command, queryId);
            }
          } else {
            Exception ex =
                new HttpResponseException(
                    response.getStatusLine().getStatusCode(),
                    EntityUtils.toString(response.getEntity()));
            handleStorageException(
                ex, ++retryCount, StorageHelper.DOWNLOAD, session, command, queryId);
          }
        } else {
          SFPair<InputStream, Map<String, String>> pair =
              this.gcsAccessStrategy.downloadToStream(
                  remoteStorageLocation, stageFilePath, isEncrypting());
          inputStream = pair.left;
          if (isEncrypting()) {
            // Get the user-defined BLOB metadata
            Map<String, String> userDefinedMetadata = pair.right;
            AbstractMap.SimpleEntry<String, String> encryptionData =
                parseEncryptionData(userDefinedMetadata.get(GCS_ENCRYPTIONDATAPROP), queryId);

            key = encryptionData.getKey();
            iv = encryptionData.getValue();
          }
          stopwatch.stop();
          downloadMillis = stopwatch.elapsedMillis();
        }

        if (this.isEncrypting() && this.getEncryptionKeySize() <= 256) {
          stopwatch.restart();
          if (key == null || iv == null) {
            throw new SnowflakeSQLException(
                queryId,
                SqlState.INTERNAL_ERROR,
                ErrorCode.INTERNAL_ERROR.getMessageCode(),
                "File metadata incomplete");
          }

          // Decrypt file
          try {
            if (inputStream != null) {

              inputStream = EncryptionProvider.decryptStream(inputStream, key, iv, this.encMat);
              stopwatch.stop();
              long decryptMillis = stopwatch.elapsedMillis();
              logger.info(
                  "GCS file {} downloaded to stream. It took {} ms (download: {} ms, decryption: {} ms) with {} retries",
                  stageFilePath,
                  downloadMillis + decryptMillis,
                  downloadMillis,
                  decryptMillis,
                  retryCount);
              return inputStream;
            }
          } catch (Exception ex) {
            logger.error("Error decrypting file", ex);
            throw new SnowflakeSQLLoggedException(
                queryId,
                session,
                StorageHelper.getOperationException(StorageHelper.DOWNLOAD).getMessageCode(),
                SqlState.INTERNAL_ERROR,
                "Cannot decrypt file");
          }
        } else {
          logger.info(
              "GCS file {} downloaded to stream. Download took {} ms with {} retries",
              stageFilePath,
              downloadMillis,
              retryCount);
        }

        return inputStream;
      } catch (Exception ex) {
        logger.debug("Download unsuccessful {}", ex);
        handleStorageException(ex, ++retryCount, StorageHelper.DOWNLOAD, session, command, queryId);
      }
    } while (retryCount <= getMaxRetries());

    throw new SnowflakeSQLLoggedException(
        queryId,
        session,
        StorageHelper.getOperationException(StorageHelper.DOWNLOAD).getMessageCode(),
        SqlState.INTERNAL_ERROR,
        "Unexpected: download unsuccessful without exception!");
  }

  /**
   * Upload a file (-stream) to remote storage with Pre-signed URL without JDBC session.
   *
   * @param networkTimeoutInMilli Network timeout for the upload
   * @param ocspModeAndProxyKey OCSP mode and proxy settings for the upload.
   * @param parallelism number of threads do parallel uploading
   * @param uploadFromStream true if upload source is stream
   * @param remoteStorageLocation s3 bucket name
   * @param srcFile source file if not uploading from a stream
   * @param destFileName file name on remote storage after upload
   * @param inputStream stream used for uploading if fileBackedOutputStream is null
   * @param fileBackedOutputStream stream used for uploading if not null
   * @param meta object meta data
   * @param stageRegion region name where the stage persists
   * @param presignedUrl presigned URL for upload. Used by GCP.
   * @param queryId last query id
   * @throws SnowflakeSQLException if upload failed
   */
  @Override
  public void uploadWithPresignedUrlWithoutConnection(
      int networkTimeoutInMilli,
      HttpClientSettingsKey ocspModeAndProxyKey,
      int parallelism,
      boolean uploadFromStream,
      String remoteStorageLocation,
      File srcFile,
      String destFileName,
      InputStream inputStream,
      FileBackedOutputStream fileBackedOutputStream,
      StorageObjectMetadata meta,
      String stageRegion,
      String presignedUrl,
      String queryId)
      throws SnowflakeSQLException {
    logger.info(
        StorageHelper.getStartUploadLog(
            "GCS", uploadFromStream, inputStream, fileBackedOutputStream, srcFile, destFileName));
    final List<FileInputStream> toClose = new ArrayList<>();
    long originalContentLength = meta.getContentLength();

    SFPair<InputStream, Boolean> uploadStreamInfo =
        createUploadStream(
            srcFile,
            uploadFromStream,
            inputStream,
            meta,
            originalContentLength,
            fileBackedOutputStream,
            toClose,
            queryId);

    if (!(meta instanceof CommonObjectMetadata)) {
      throw new IllegalArgumentException("Unexpected metadata object type");
    }
    Stopwatch stopwatch = new Stopwatch();
    stopwatch.start();
    if (isNullOrEmpty(presignedUrl) || "null".equalsIgnoreCase(presignedUrl)) {
      logger.debug("Starting upload with downscoped token");
      uploadWithDownScopedToken(
          parallelism,
          remoteStorageLocation,
          destFileName,
          meta.getContentEncoding(),
          meta.getUserMetadata(),
          meta.getContentLength(),
          uploadStreamInfo.left,
          queryId);
      logger.debug("Upload successful with downscoped token");
    } else {
      logger.debug("Starting upload with presigned url");

      uploadWithPresignedUrl(
          networkTimeoutInMilli,
          (int) HttpUtil.getSocketTimeout().toMillis(),
          meta.getContentEncoding(),
          meta.getUserMetadata(),
          uploadStreamInfo.left,
          presignedUrl,
          ocspModeAndProxyKey,
          queryId);
      logger.debug("Upload successfully with presigned url");
    }
    stopwatch.stop();

    if (uploadFromStream) {
      logger.info(
          "Uploaded data from input stream to GCS location: {}. It took {} ms",
          remoteStorageLocation,
          stopwatch.elapsedMillis());
    } else {
      logger.info(
          "Uploaded file {} to GCS location: {}. It took {} ms",
          srcFile.getAbsolutePath(),
          remoteStorageLocation,
          stopwatch.elapsedMillis());
    }

    // close any open streams in the "toClose" list and return
    for (FileInputStream is : toClose) {
      IOUtils.closeQuietly(is);
    }
  }

  /**
   * Upload a file/stream to remote storage
   *
   * @param session session object
   * @param command upload command
   * @param parallelism [ not used by the GCP implementation ]
   * @param uploadFromStream true if upload source is stream
   * @param remoteStorageLocation storage container name
   * @param srcFile source file if not uploading from a stream
   * @param destFileName file name on remote storage after upload
   * @param inputStream stream used for uploading if fileBackedOutputStream is null
   * @param fileBackedOutputStream stream used for uploading if not null
   * @param meta object meta data
   * @param stageRegion region name where the stage persists
   * @param presignedUrl Credential used for upload of a file
   * @param queryId last query id
   * @throws SnowflakeSQLException if upload failed even after retry
   */
  @Override
  public void upload(
      SFSession session,
      String command,
      int parallelism,
      boolean uploadFromStream,
      String remoteStorageLocation,
      File srcFile,
      String destFileName,
      InputStream inputStream,
      FileBackedOutputStream fileBackedOutputStream,
      StorageObjectMetadata meta,
      String stageRegion,
      String presignedUrl,
      String queryId)
      throws SnowflakeSQLException {
    logger.info(
        StorageHelper.getStartUploadLog(
            "GCS", uploadFromStream, inputStream, fileBackedOutputStream, srcFile, destFileName));
    final List<FileInputStream> toClose = new ArrayList<>();
    long originalContentLength = meta.getContentLength();

    SFPair<InputStream, Boolean> uploadStreamInfo =
        createUploadStream(
            srcFile,
            uploadFromStream,
            inputStream,
            meta,
            originalContentLength,
            fileBackedOutputStream,
            toClose,
            queryId);

    if (!(meta instanceof CommonObjectMetadata)) {
      throw new IllegalArgumentException("Unexpected metadata object type");
    }

    Stopwatch stopwatch = new Stopwatch();
    stopwatch.start();
    if (!isNullOrEmpty(presignedUrl)) {
      logger.debug("Starting upload with downscope token", false);
      uploadWithPresignedUrl(
          session.getNetworkTimeoutInMilli(),
          session.getHttpClientSocketTimeout(),
          meta.getContentEncoding(),
          meta.getUserMetadata(),
          uploadStreamInfo.left,
          presignedUrl,
          session.getHttpClientKey(),
          queryId);
      stopwatch.stop();
      logger.debug("Upload successful", false);
      if (uploadFromStream) {
        logger.info(
            "Uploaded data from input stream to GCS location: {}. It took {} ms",
            remoteStorageLocation,
            stopwatch.elapsedMillis());
      } else {
        logger.info(
            "Uploaded file {} to GCS location: {}. It took {} ms",
            srcFile.getAbsolutePath(),
            remoteStorageLocation,
            stopwatch.elapsedMillis());
      }

      // close any open streams in the "toClose" list and return
      for (FileInputStream is : toClose) {
        IOUtils.closeQuietly(is);
      }

      return;
    }

    // No presigned URL. This codepath is for when we have a token instead.
    int retryCount = 0;
    do {
      try {
        logger.debug("Starting upload", false);

        uploadWithDownScopedToken(
            parallelism,
            remoteStorageLocation,
            destFileName,
            meta.getContentEncoding(),
            meta.getUserMetadata(),
            meta.getContentLength(),
            uploadStreamInfo.left,
            queryId);

        stopwatch.stop();
        logger.debug("Upload successful", false);
        if (uploadFromStream) {
          logger.info(
              "Uploaded data from input stream to GCS location: {}. It took {} ms",
              remoteStorageLocation,
              stopwatch.elapsedMillis());
        } else {
          logger.info(
              "Uploaded file {} to GCS location: {}. It took {} ms",
              srcFile.getAbsolutePath(),
              remoteStorageLocation,
              stopwatch.elapsedMillis());
        }

        // close any open streams in the "toClose" list and return
        for (FileInputStream is : toClose) {
          IOUtils.closeQuietly(is);
        }

        return;
      } catch (Exception ex) {
        handleStorageException(ex, ++retryCount, StorageHelper.UPLOAD, session, command, queryId);

        if (uploadFromStream && fileBackedOutputStream == null) {
          throw new SnowflakeSQLLoggedException(
              queryId,
              session,
              SqlState.SYSTEM_ERROR,
              StorageHelper.getOperationException(StorageHelper.UPLOAD).getMessageCode(),
              ex,
              "Encountered exception during upload: "
                  + ex.getMessage()
                  + "\nCannot retry upload from stream.");
        }
        uploadStreamInfo =
            createUploadStream(
                srcFile,
                uploadFromStream,
                inputStream,
                meta,
                originalContentLength,
                fileBackedOutputStream,
                toClose,
                queryId);
      }

    } while (retryCount <= getMaxRetries());

    for (FileInputStream is : toClose) {
      IOUtils.closeQuietly(is);
    }

    throw new SnowflakeSQLLoggedException(
        queryId,
        session,
        StorageHelper.getOperationException(StorageHelper.UPLOAD).getMessageCode(),
        SqlState.INTERNAL_ERROR,
        "Unexpected: upload unsuccessful without exception!");
  }

  /**
   * Upload file with down scoped token.
   *
   * @param remoteStorageLocation storage container name
   * @param destFileName file name on remote storage after upload
   * @param contentEncoding Object's content encoding. We do special things for "gzip"
   * @param metadata Custom metadata to be uploaded with the object
   * @param content File content
   */
  private void uploadWithDownScopedToken(
      int parallelism,
      String remoteStorageLocation,
      String destFileName,
      String contentEncoding,
      Map<String, String> metadata,
      long contentLength,
      InputStream content,
      String queryId)
      throws SnowflakeSQLException {
    logger.debug("Uploading file {} to bucket {}", destFileName, remoteStorageLocation);
    try {
      this.gcsAccessStrategy.uploadWithDownScopedToken(
          parallelism,
          remoteStorageLocation,
          destFileName,
          contentEncoding,
          metadata,
          contentLength,
          content,
          queryId);
    } catch (Exception e) {
      handleStorageException(e, 0, StorageHelper.UPLOAD, session, queryId);
      SnowflakeSQLException wrappedException;
      if (e instanceof SnowflakeSQLException) {
        wrappedException = (SnowflakeSQLException) e;

      } else {
        wrappedException =
            new SnowflakeSQLLoggedException(
                queryId,
                session,
                SqlState.SYSTEM_ERROR,
                StorageHelper.getOperationException(StorageHelper.UPLOAD).getMessageCode(),
                e,
                "Encountered exception during " + StorageHelper.UPLOAD + ": " + e.getMessage());
      }
      throw wrappedException;
    }
  }

  /**
   * Performs upload using a presigned URL
   *
   * @param networkTimeoutInMilli Network timeout
   * @param contentEncoding Object's content encoding. We do special things for "gzip"
   * @param metadata Custom metadata to be uploaded with the object
   * @param content File content
   * @param presignedUrl Credential to upload the object
   * @param ocspAndProxyKey OCSP mode and proxy settings for httpclient
   * @throws SnowflakeSQLException
   */
  private void uploadWithPresignedUrl(
      int networkTimeoutInMilli,
      int httpClientSocketTimeout,
      String contentEncoding,
      Map<String, String> metadata,
      InputStream content,
      String presignedUrl,
      HttpClientSettingsKey ocspAndProxyKey,
      String queryId)
      throws SnowflakeSQLException {
    try {
      URIBuilder uriBuilder = new URIBuilder(presignedUrl);

      HttpPut httpRequest = new HttpPut(uriBuilder.build());

      logger.debug("Fetching result: {}", scrubPresignedUrl(presignedUrl));

      // We set the contentEncoding to blank for GZIP files. We don't want GCS to think
      // our gzip files are gzips because it makes them download uncompressed, and
      // none of the other providers do that. There's essentially no way for us
      // to prevent that behavior. Bad Google.
      if ("gzip".equals(contentEncoding)) {
        contentEncoding = "";
      }
      httpRequest.addHeader("content-encoding", contentEncoding);

      for (Entry<String, String> entry : metadata.entrySet()) {
        httpRequest.addHeader(GCS_METADATA_PREFIX + entry.getKey(), entry.getValue());
      }

      InputStreamEntity contentEntity = new InputStreamEntity(content, -1);
      httpRequest.setEntity(contentEntity);

      CloseableHttpClient httpClient =
          HttpUtil.getHttpClient(ocspAndProxyKey, session.getHttpHeadersCustomizers());

      // Put the file on storage using the presigned url
      HttpResponse response =
          RestRequest.executeWithRetries(
                  httpClient,
                  httpRequest,
                  networkTimeoutInMilli / 1000, // retry timeout
                  0,
                  httpClientSocketTimeout, // socket timeout in ms
                  getMaxRetries(),
                  0, // no socket timeout injection
                  null, // no canceling
                  false, // no cookie
                  false, // no url retry query parameters
                  false, // no request_guid
                  true, // retry on HTTP 403
                  true, // disable retry
                  new ExecTimeTelemetryData(),
                  session)
              .getHttpResponse();

      logger.debug(
          "Call returned for URL: {}",
          (ArgSupplier) () -> scrubPresignedUrl(this.stageInfo.getPresignedUrl()));

      if (!isSuccessStatusCode(response.getStatusLine().getStatusCode())) {
        Exception ex =
            new HttpResponseException(
                response.getStatusLine().getStatusCode(),
                EntityUtils.toString(response.getEntity()));
        handleStorageException(ex, 0, StorageHelper.UPLOAD, session, null, queryId);
      }
    } catch (URISyntaxException e) {
      throw new SnowflakeSQLLoggedException(
          queryId,
          session,
          StorageHelper.getOperationException(StorageHelper.UPLOAD).getMessageCode(),
          SqlState.INTERNAL_ERROR,
          "Unexpected: upload presigned URL invalid");
    } catch (Exception e) {
      throw new SnowflakeSQLLoggedException(
          queryId,
          session,
          StorageHelper.getOperationException(StorageHelper.UPLOAD).getMessageCode(),
          SqlState.INTERNAL_ERROR,
          "Unexpected: upload with presigned url failed");
    }
  }

  /**
   * When we log the URL, make sure we don't log the credential
   *
   * @param presignedUrl Presigned URL with full signature
   * @return Just the object path
   */
  private String scrubPresignedUrl(String presignedUrl) {
    if (isNullOrEmpty(presignedUrl)) {
      return "";
    }
    int indexOfQueryString = presignedUrl.lastIndexOf("?");
    indexOfQueryString = indexOfQueryString > 0 ? indexOfQueryString : presignedUrl.length() - 1;
    return presignedUrl.substring(0, indexOfQueryString);
  }

  private SFPair<InputStream, Boolean> createUploadStream(
      File srcFile,
      boolean uploadFromStream,
      InputStream inputStream,
      StorageObjectMetadata meta,
      long originalContentLength,
      FileBackedOutputStream fileBackedOutputStream,
      List<FileInputStream> toClose,
      String queryId)
      throws SnowflakeSQLException {
    logger.debug(
        "createUploadStream({}, {}, {}, {}, {}, {})",
        this,
        srcFile,
        uploadFromStream,
        inputStream,
        fileBackedOutputStream,
        toClose);

    final InputStream stream;
    FileInputStream srcFileStream = null;
    try {
      if (isEncrypting() && getEncryptionKeySize() <= 256) {
        try {
          final InputStream uploadStream =
              uploadFromStream
                  ? (fileBackedOutputStream != null
                      ? fileBackedOutputStream.asByteSource().openStream()
                      : inputStream)
                  : (srcFileStream = new FileInputStream(srcFile));
          toClose.add(srcFileStream);

          // Encrypt
          stream =
              EncryptionProvider.encrypt(
                  meta, originalContentLength, uploadStream, this.encMat, this);
          uploadFromStream = true;
        } catch (Exception ex) {
          logger.error("Failed to encrypt input", ex);
          throw new SnowflakeSQLLoggedException(
              queryId,
              session,
              SqlState.INTERNAL_ERROR,
              StorageHelper.getOperationException(StorageHelper.UPLOAD).getMessageCode(),
              ex,
              "Failed to encrypt input",
              ex.getMessage());
        }
      } else {
        if (uploadFromStream) {
          if (fileBackedOutputStream != null) {
            stream = fileBackedOutputStream.asByteSource().openStream();
          } else {
            stream = inputStream;
          }
        } else {
          srcFileStream = new FileInputStream(srcFile);
          toClose.add(srcFileStream);
          stream = srcFileStream;
        }
      }
    } catch (FileNotFoundException ex) {
      logger.error("Failed to open input file", ex);
      throw new SnowflakeSQLLoggedException(
          queryId,
          session,
          SqlState.INTERNAL_ERROR,
          StorageHelper.getOperationException(StorageHelper.UPLOAD).getMessageCode(),
          ex,
          "Failed to open input file",
          ex.getMessage());
    } catch (IOException ex) {
      logger.error("Failed to open input stream", ex);
      throw new SnowflakeSQLLoggedException(
          queryId,
          session,
          SqlState.INTERNAL_ERROR,
          StorageHelper.getOperationException(StorageHelper.UPLOAD).getMessageCode(),
          ex,
          "Failed to open input stream",
          ex.getMessage());
    }

    return SFPair.of(stream, uploadFromStream);
  }

  @Override
  public void handleStorageException(
      Exception ex,
      int retryCount,
      String operation,
      SFSession session,
      String command,
      String queryId)
      throws SnowflakeSQLException {
    // no need to retry if it is invalid key exception
    if (ex.getCause() instanceof InvalidKeyException) {
      // Most likely cause is that the unlimited strength policy files are not installed
      // Log the error and throw a message that explains the cause
      SnowflakeFileTransferAgent.throwJCEMissingError(operation, ex, queryId);
    }

    // If there is no space left in the download location, java.io.IOException is thrown.
    // Don't retry.
    if (getRootCause(ex) instanceof IOException) {
      SnowflakeFileTransferAgent.throwNoSpaceLeftError(session, operation, ex, queryId);
    }

    if (this.gcsAccessStrategy.handleStorageException(
        ex, retryCount, operation, session, command, queryId, this)) {
      // exception is handled in gcsAccessStrategy.handleStorageException
    } else if (ex instanceof InterruptedException
        || getRootCause(ex) instanceof SocketTimeoutException) {
      if (retryCount > getMaxRetries()) {
        throw new SnowflakeSQLLoggedException(
            queryId,
            session,
            SqlState.SYSTEM_ERROR,
            StorageHelper.getOperationException(operation).getMessageCode(),
            ex,
            "Encountered exception during " + operation + ": " + ex.getMessage());
      } else {
        logger.debug(
            "Encountered exception ({}) during {}, retry count: {}",
            ex.getMessage(),
            operation,
            retryCount);
      }
    } else {
      throw new SnowflakeSQLLoggedException(
          queryId,
          session,
          SqlState.SYSTEM_ERROR,
          StorageHelper.getOperationException(operation).getMessageCode(),
          ex,
          "Encountered exception during " + operation + ": " + ex.getMessage());
    }
  }

  /** Returns the material descriptor key */
  @Override
  public String getMatdescKey() {
    return "matdesc";
  }

  /** Adds encryption metadata to the StorageObjectMetadata object */
  @Override
  public void addEncryptionMetadata(
      StorageObjectMetadata meta,
      MatDesc matDesc,
      byte[] ivData,
      byte[] encryptedKey,
      long contentLength) {
    meta.addUserMetadata(getMatdescKey(), matDesc.toString());
    meta.addUserMetadata(
        GCS_ENCRYPTIONDATAPROP,
        buildEncryptionMetadataJSON(
            Base64.getEncoder().encodeToString(ivData),
            Base64.getEncoder().encodeToString(encryptedKey)));
    meta.setContentLength(contentLength);
  }

  /*
   * buildEncryptionMetadataJSON
   * Takes the base64-encoded iv and key and creates the JSON block to be
   * used as the encryptiondata metadata field on the blob.
   */
  private String buildEncryptionMetadataJSON(String iv64, String key64) {
    return String.format(
        "{\"EncryptionMode\":\"FullBlob\",\"WrappedContentKey\""
            + ":{\"KeyId\":\"symmKey1\",\"EncryptedKey\":\"%s\""
            + ",\"Algorithm\":\"AES_CBC_256\"},\"EncryptionAgent\":"
            + "{\"Protocol\":\"1.0\",\"EncryptionAlgorithm\":"
            + "\"AES_CBC_256\"},\"ContentEncryptionIV\":\"%s\""
            + ",\"KeyWrappingMetadata\":{\"EncryptionLibrary\":"
            + "\"Java 5.3.0\"}}",
        key64, iv64);
  }

  /*
   * parseEncryptionData
   * Takes the json string in the encryptiondata metadata field of the encrypted
   * blob and parses out the key and iv. Returns the pair as key = key, iv = value.
   */
  private AbstractMap.SimpleEntry<String, String> parseEncryptionData(
      String jsonEncryptionData, String queryId) throws SnowflakeSQLException {
    ObjectMapper mapper = ObjectMapperFactory.getObjectMapper();
    JsonFactory factory = mapper.getFactory();
    try {
      JsonParser parser = factory.createParser(jsonEncryptionData);
      JsonNode encryptionDataNode = mapper.readTree(parser);

      String iv = encryptionDataNode.get("ContentEncryptionIV").asText();
      String key = encryptionDataNode.get("WrappedContentKey").get("EncryptedKey").asText();

      return new AbstractMap.SimpleEntry<>(key, iv);
    } catch (Exception ex) {
      throw new SnowflakeSQLException(
          queryId,
          ex,
          SqlState.SYSTEM_ERROR,
          ErrorCode.FILE_TRANSFER_ERROR.getMessageCode(),
          "Error parsing encryption data as json" + ": " + ex.getMessage());
    }
  }

  /** Adds digest metadata to the StorageObjectMetadata object */
  @Override
  public void addDigestMetadata(StorageObjectMetadata meta, String digest) {
    if (!isBlank(digest)) {
      meta.addUserMetadata("sfc-digest", digest);
    }
  }

  /** Gets digest metadata to the StorageObjectMetadata object */
  @Override
  public String getDigestMetadata(StorageObjectMetadata meta) {
    return meta.getUserMetadata().get("sfc-digest");
  }

  /*
   * Initializes the GCS client
   * This method is used during the object construction, but also to
   * reset/recreate the encapsulated CloudBlobClient object with new
   * credentials (after token expiration)
   * @param stage   The stage information that the client will operate on
   * @param encMat  The encryption material
   *                required to decrypt/encrypt content in stage
   * @throws IllegalArgumentException when invalid credentials are used
   */
  private void setupGCSClient(
      StageInfo stage, RemoteStoreFileEncryptionMaterial encMat, SFSession session)
      throws IllegalArgumentException, SnowflakeSQLException {
    // Save the client creation parameters so that we can reuse them,
    // to reset the GCS client.
    this.stageInfo = stage;
    this.encMat = encMat;
    this.session = session;

    logger.debug("Setting up the GCS client ", false);

    try {
      boolean overrideAwsAccessStrategy =
          Boolean.valueOf(System.getenv("SNOWFLAKE_GCS_FORCE_VIRTUAL_STYLE_DOMAINS"));
      if (stage.getUseVirtualUrl() || overrideAwsAccessStrategy) {
        this.gcsAccessStrategy = new GCSAccessStrategyAwsSdk(stage, session);
      } else {
        this.gcsAccessStrategy = new GCSDefaultAccessStrategy(stage, session);
      }

      if (encMat != null) {
        byte[] decodedKey = Base64.getDecoder().decode(encMat.getQueryStageMasterKey());
        encryptionKeySize = decodedKey.length * 8;

        if (encryptionKeySize != 128 && encryptionKeySize != 192 && encryptionKeySize != 256) {
          throw new SnowflakeSQLException(
              QueryIdHelper.queryIdFromEncMatOr(encMat, null),
              SqlState.INTERNAL_ERROR,
              ErrorCode.INTERNAL_ERROR.getMessageCode(),
              "unsupported key size",
              encryptionKeySize);
        }
      }
    } catch (Exception ex) {
      throw new IllegalArgumentException("invalid_gcs_credentials");
    }
  }

  protected static boolean areDisabledGcsDefaultCredentials(SFSession session) {
    return session != null && session.getDisableGcsDefaultCredentials()
        || convertSystemPropertyToBooleanValue(DISABLE_GCS_DEFAULT_CREDENTIALS_PROPERTY_NAME, true);
  }

  private static boolean isSuccessStatusCode(int code) {
    return code < 300 && code >= 200;
  }

  /**
   * Adds streaming ingest metadata to the StorageObjectMetadata object, used for streaming ingest
   * per client billing calculation
   */
  @Override
  public void addStreamingIngestMetadata(
      StorageObjectMetadata meta, String clientName, String clientKey) {
    meta.addUserMetadata(GCS_STREAMING_INGEST_CLIENT_NAME, clientName);
    meta.addUserMetadata(GCS_STREAMING_INGEST_CLIENT_KEY, clientKey);
  }

  @Override
  public String getStreamingIngestClientName(StorageObjectMetadata meta) {
    return meta.getUserMetadata().get(GCS_STREAMING_INGEST_CLIENT_NAME);
  }

  @Override
  public String getStreamingIngestClientKey(StorageObjectMetadata meta) {
    return meta.getUserMetadata().get(GCS_STREAMING_INGEST_CLIENT_KEY);
  }
}
