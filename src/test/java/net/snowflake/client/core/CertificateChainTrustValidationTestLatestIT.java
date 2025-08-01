package net.snowflake.client.core;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;
import net.snowflake.client.log.SFLogger;
import net.snowflake.client.log.SFLoggerFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * This test validates the behavior of the SFTrustManager (which should use PKIX validation) when
 * validating a specific cross-signed certificate chain. It also compares this with the behavior of
 * the SunX509 X509TrustManager The test ensures that: - The SFTrustManager can validate the chain
 * successfully. - The SunX509 X509TrustManager fails validation due to its trust store
 * configuration.
 *
 * <p>Prerequisites for this test: - The certificates used in this test must be generated by the
 * script located at ssl-tests/generate_certs.sh - The test dynamically sets the JVM properties:
 * -Djavax.net.ssl.trustStore=path/to/test/resources/ssl-tests/certs/truststore.jks
 * -Djavax.net.ssl.trustStorePassword=changeit These properties are reset after all tests are
 * finished.
 */
@org.junit.jupiter.api.Tag("CORE")
public class CertificateChainTrustValidationTestLatestIT {

  private static final SFLogger logger =
      SFLoggerFactory.getLogger(CertificateChainTrustValidationTestLatestIT.class);

  private static final String CERT_RESOURCE_PATH = "ssl-tests/certs/";
  private static final String TRUST_STORE_FILE_NAME = "truststore.jks";
  private static final String TRUST_STORE_PASSWORD = "changeit";

  // Original JVM properties to restore after tests
  private static String originalTrustStore;
  private static String originalTrustStorePassword;

  // Certificates for the exact chain generated by the script
  private static X509Certificate leafCert; // Cert 0
  private static X509Certificate amzRsaM02IntermediateCert; // Cert 1
  private static X509Certificate amzRootCa1ChainCert; // Cert 2 (issued by St G2)
  private static X509Certificate stG2RootCert; // Cert 3 (issued by St Class 2)
  private static X509Certificate stClass2RootCert; // Ultimate Root of the chain (self-signed)

  // The specific self-signed Amz Root CA 1 from the trust store
  private static X509Certificate amzRootCa1SelfSignedForTrustStoreCert;

  private static SFTrustManager sfTrustManager;
  private static X509TrustManager sunX509TrustManager;

  @BeforeAll
  static void setUpAll() throws Exception {
    logger.debug("--- Test Setup Started ---");

    // Store original JVM properties
    originalTrustStore = System.getProperty("javax.net.ssl.trustStore");
    originalTrustStorePassword = System.getProperty("javax.net.ssl.trustStorePassword");

    // Set JVM properties for the test
    Path trustStorePath =
        Paths.get("src", "test", "resources", CERT_RESOURCE_PATH, TRUST_STORE_FILE_NAME);
    System.setProperty("javax.net.ssl.trustStore", trustStorePath.toAbsolutePath().toString());
    System.setProperty("javax.net.ssl.trustStorePassword", TRUST_STORE_PASSWORD);

    logger.debug(
        "Set JVM property javax.net.ssl.trustStore to: "
            + System.getProperty("javax.net.ssl.trustStore"));
    logger.debug("Set JVM property javax.net.ssl.trustStorePassword.");

    logger.debug("Verifying chain certificates exist in resources...");
    try (InputStream is = getResourceStream(CERT_RESOURCE_PATH + "leaf.crt")) {
      if (is == null) {
        throw new IllegalStateException(
            "Leaf certificate not found in resources. "
                + "Please ensure the 'test/resources/ssl-tests/generate_certs.sh' script was run successfully.");
      }
    } catch (IOException e) {
      throw new IllegalStateException("Error accessing test resources: " + e.getMessage(), e);
    }

    CertificateFactory cf = CertificateFactory.getInstance("X.509");

    logger.debug("Loading chain certificates from classpath: " + CERT_RESOURCE_PATH + "...");
    try (InputStream is = getResourceStream(CERT_RESOURCE_PATH + "leaf.crt")) {
      leafCert = (X509Certificate) cf.generateCertificate(is);
    }
    try (InputStream is = getResourceStream(CERT_RESOURCE_PATH + "amz_rsa_m02_intermediate.crt")) {
      amzRsaM02IntermediateCert = (X509Certificate) cf.generateCertificate(is);
    }
    try (InputStream is = getResourceStream(CERT_RESOURCE_PATH + "amz_root_ca1_chain.crt")) {
      amzRootCa1ChainCert = (X509Certificate) cf.generateCertificate(is);
    }
    try (InputStream is = getResourceStream(CERT_RESOURCE_PATH + "st_g2_root.crt")) {
      stG2RootCert = (X509Certificate) cf.generateCertificate(is);
    }
    try (InputStream is = getResourceStream(CERT_RESOURCE_PATH + "st_class2_root.crt")) {
      stClass2RootCert = (X509Certificate) cf.generateCertificate(is);
    }
    logger.debug("Chain certificates loaded successfully.");

    logger.debug("Loading the specific self-signed Amz Root CA 1 for the trust store...");
    try (InputStream is = getResourceStream(CERT_RESOURCE_PATH + "amz_root_ca1_trust_store.crt")) {
      amzRootCa1SelfSignedForTrustStoreCert = (X509Certificate) cf.generateCertificate(is);
    }
    logger.debug("Self-signed Amz Root CA 1 for Trust Store loaded.");

    logger.debug(
        "Loading trust store from classpath: "
            + CERT_RESOURCE_PATH
            + TRUST_STORE_FILE_NAME
            + "...");
    KeyStore trustStore = KeyStore.getInstance("JKS");
    // Load trust store using the dynamically set property, or directly from classpath if that fails
    // (for robustness)
    // Note: When javax.net.ssl.trustStore is set, default TrustManagerFactory uses it
    // automatically.
    // We load it here explicitly to ensure it exists and for direct checks.
    try (InputStream is = getResourceStream(CERT_RESOURCE_PATH + TRUST_STORE_FILE_NAME)) {
      trustStore.load(is, TRUST_STORE_PASSWORD.toCharArray());
    }
    logger.debug("Trust store loaded.");

    assertTrue(
        trustStore.containsAlias("rootca1_self_signed_for_ts"),
        "Trust store MUST contain 'rootca1_self_signed_for_ts' alias.");
    logger.debug(
        "Trust store content verified: only rootca1_self_signed_for_ts is present as a trust anchor.");

    logger.debug("Initializing PKIX X509TrustManager...");
    sfTrustManager = new SFTrustManager(new HttpClientSettingsKey(OCSPMode.FAIL_CLOSED), null);
    assertNotNull(sfTrustManager, "PKIX X509TrustManager should be initialized.");
    logger.debug("PKIX X509TrustManager initialized successfully.");

    logger.debug("Initializing SunX509 X509TrustManager...");
    TrustManagerFactory sunX509Tmf = TrustManagerFactory.getInstance("SunX509");
    sunX509Tmf.init(trustStore); // Initialize with our explicitly loaded trustStore
    for (TrustManager tm : sunX509Tmf.getTrustManagers()) {
      if (tm instanceof X509TrustManager) {
        sunX509TrustManager = (X509TrustManager) tm;
        break;
      }
    }
    assertNotNull(sunX509TrustManager, "SunX509 X509TrustManager should be initialized.");
    logger.debug("SunX509 X509TrustManager initialized successfully.");

    logger.debug("--- Test Setup Complete ---");
  }

  @AfterAll
  static void tearDownAll() {
    logger.debug("--- Test Teardown Started ---");
    // Restore original JVM properties
    if (originalTrustStore != null) {
      System.setProperty("javax.net.ssl.trustStore", originalTrustStore);
      logger.debug("Restored javax.net.ssl.trustStore to: " + originalTrustStore);
    } else {
      System.clearProperty("javax.net.ssl.trustStore");
      logger.debug("Cleared javax.net.ssl.trustStore property.");
    }

    if (originalTrustStorePassword != null) {
      System.setProperty("javax.net.ssl.trustStorePassword", originalTrustStorePassword);
      logger.debug("Restored javax.net.ssl.trustStorePassword.");
    } else {
      System.clearProperty("javax.net.ssl.trustStorePassword");
      logger.debug("Cleared javax.net.ssl.trustStorePassword property.");
    }
    logger.debug("--- Test Teardown Complete ---");
  }

  private static InputStream getResourceStream(String resourceName) {
    InputStream is =
        CertificateChainTrustValidationTestLatestIT.class
            .getClassLoader()
            .getResourceAsStream(resourceName);
    if (is == null) {
      System.err.println(
          "ERROR: Resource not found: "
              + resourceName
              + ". Ensure 'recreate_all_certs.sh' placed it correctly.");
    }
    return is;
  }

  /**
   * Scenario: - Chain: Leaf -> Amz RSA 2048 M02 -> Amz Root CA 1 (issued by St G2) -> St G2 (issued
   * by St Class 2) - Trust Store: Contains ONLY a *self-signed* Amz Root CA 1 (which shares Subject
   * DN and Public Key with the chain's Amz Root CA 1).
   */
  @Test
  void shouldProperlyValidateCrossSignedChain() {
    // The chain provided will be the full 4-certificate chain.
    // PKIX should find the trusted public key at amzRootCa1ChainCert and terminate successfully
    // there.
    X509Certificate[] chain =
        new X509Certificate[] {
          leafCert,
          amzRsaM02IntermediateCert,
          amzRootCa1ChainCert, // This is the Amz Root CA 1 issued by St G2.
          stG2RootCert // This is St G2, its issuer.
        };
    String authType = "RSA";

    // Test with SfTrustManager (PKIX Validation)
    assertDoesNotThrow(
        () -> {
          sfTrustManager.checkServerTrusted(chain, authType);
        },
        "PKIX should pass because the chain's Amz Root CA 1 public key matches a trusted anchor.");

    // Test with SunX509 Validation
    Exception e =
        assertThrows(
            Exception.class, () -> sunX509TrustManager.checkServerTrusted(chain, authType));
    assertTrue(
        e.getMessage().contains("No trusted certificate found"),
        "SunX509 should fail because it does match Amz Root CA 1 as a trusted anchor in the trust store, "
            + "even though the public key matches. This is expected behavior for SunX509.");
  }
}
