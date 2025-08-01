package net.snowflake.client.jdbc;

import static net.snowflake.client.jdbc.DBMetadataResultSetMetadata.GET_CATALOGS;
import static net.snowflake.client.jdbc.DBMetadataResultSetMetadata.GET_COLUMNS;
import static net.snowflake.client.jdbc.DBMetadataResultSetMetadata.GET_COLUMNS_EXTENDED_SET;
import static net.snowflake.client.jdbc.DBMetadataResultSetMetadata.GET_FOREIGN_KEYS;
import static net.snowflake.client.jdbc.DBMetadataResultSetMetadata.GET_FUNCTIONS;
import static net.snowflake.client.jdbc.DBMetadataResultSetMetadata.GET_FUNCTION_COLUMNS;
import static net.snowflake.client.jdbc.DBMetadataResultSetMetadata.GET_PRIMARY_KEYS;
import static net.snowflake.client.jdbc.DBMetadataResultSetMetadata.GET_PROCEDURES;
import static net.snowflake.client.jdbc.DBMetadataResultSetMetadata.GET_PROCEDURE_COLUMNS;
import static net.snowflake.client.jdbc.DBMetadataResultSetMetadata.GET_SCHEMAS;
import static net.snowflake.client.jdbc.DBMetadataResultSetMetadata.GET_STREAMS;
import static net.snowflake.client.jdbc.DBMetadataResultSetMetadata.GET_TABLES;
import static net.snowflake.client.jdbc.DBMetadataResultSetMetadata.GET_TABLE_PRIVILEGES;
import static net.snowflake.client.jdbc.SnowflakeType.convertStringToType;
import static net.snowflake.client.jdbc.SnowflakeUtil.isNullOrEmpty;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.RowIdLifetime;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import net.snowflake.client.core.ObjectMapperFactory;
import net.snowflake.client.core.SFBaseSession;
import net.snowflake.client.jdbc.telemetry.Telemetry;
import net.snowflake.client.jdbc.telemetry.TelemetryData;
import net.snowflake.client.jdbc.telemetry.TelemetryField;
import net.snowflake.client.jdbc.telemetry.TelemetryUtil;
import net.snowflake.client.log.ArgSupplier;
import net.snowflake.client.log.SFLogger;
import net.snowflake.client.log.SFLoggerFactory;
import net.snowflake.common.core.SqlState;
import net.snowflake.common.util.Wildcard;

public class SnowflakeDatabaseMetaData implements DatabaseMetaData {

  private static final SFLogger logger = SFLoggerFactory.getLogger(SnowflakeDatabaseMetaData.class);

  private static final ObjectMapper mapper = ObjectMapperFactory.getObjectMapper();

  private static final String DatabaseProductName = "Snowflake";

  private static final String DriverName = "Snowflake";

  private static final char SEARCH_STRING_ESCAPE = '\\';

  private static final String JDBCVersion = "4.2";
  // Open Group CLI Functions
  // LOG10 is not supported
  public static final String NumericFunctionsSupported =
      "ABS,ACOS,ASIN,ATAN,ATAN2,CBRT,CEILING,COS,COT,DEGREES,EXP,FACTORIAL,"
          + "FLOOR,HAVERSINE,LN,LOG,MOD,PI,POWER,RADIANS,RAND,"
          + "ROUND,SIGN,SIN,SQRT,SQUARE,TAN,TRUNCATE";
  // DIFFERENCE and SOUNDEX are not supported
  public static final String StringFunctionsSupported =
      "ASCII,BIT_LENGTH,CHAR,CONCAT,INSERT,LCASE,LEFT,LENGTH,LPAD,"
          + "LOCATE,LTRIM,OCTET_LENGTH,PARSE_IP,PARSE_URL,REPEAT,REVERSE,"
          + "REPLACE,RPAD,RTRIMMED_LENGTH,SPACE,SPLIT,SPLIT_PART,"
          + "SPLIT_TO_TABLE,STRTOK,STRTOK_TO_ARRAY,STRTOK_SPLIT_TO_TABLE,"
          + "TRANSLATE,TRIM,UNICODE,UUID_STRING,INITCAP,LOWER,UPPER,REGEXP,"
          + "REGEXP_COUNT,REGEXP_INSTR,REGEXP_LIKE,REGEXP_REPLACE,"
          + "REGEXP_SUBSTR,RLIKE,CHARINDEX,CONTAINS,EDITDISTANCE,ENDSWITH,"
          + "ILIKE,ILIKE ANY,LIKE,LIKE ALL,LIKE ANY,POSITION,REPLACE,RIGHT,"
          + "STARTSWITH,SUBSTRING,COMPRESS,DECOMPRESS_BINARY,DECOMPRESS_STRING,"
          + "BASE64_DECODE_BINARY,BASE64_DECODE_STRING,BASE64_ENCODE,"
          + "HEX_DECODE_BINARY,HEX_DECODE_STRING,HEX_ENCODE,"
          + "TRY_BASE64_DECODE_BINARY,TRY_BASE64_DECODE_STRING,"
          + "TRY_HEX_DECODE_BINARY,TRY_HEX_DECODE_STRING,MD_5,MD5_HEX,"
          + "MD5_BINARY,SHA1,SHA1_HEX,SHA2,SHA1_BINARY,SHA2_HEX,SHA2_BINARY,"
          + " HASH,HASH_AGG,COLLATE,COLLATION";
  private static final String DateAndTimeFunctionsSupported =
      "CURDATE,"
          + "CURTIME,DAYNAME,DAYOFMONTH,DAYOFWEEK,DAYOFYEAR,HOUR,MINUTE,MONTH,"
          + "MONTHNAME,NOW,QUARTER,SECOND,TIMESTAMPADD,TIMESTAMPDIFF,WEEK,YEAR";
  public static final String SystemFunctionsSupported = "DATABASE,IFNULL,USER";

  // These are keywords not in SQL2003 standard
  private static final String notSQL2003Keywords =
      String.join(
          ",",
          "ACCOUNT",
          "ASOF",
          "BIT",
          "BYTEINT",
          "CONNECTION",
          "DATABASE",
          "DATETIME",
          "DATE_PART",
          "FIXED",
          "FOLLOWING",
          "GSCLUSTER",
          "GSPACKAGE",
          "IDENTIFIER",
          "ILIKE",
          "INCREMENT",
          "ISSUE",
          "LONG",
          "MAP",
          "MATCH_CONDITION",
          "MINUS",
          "NUMBER",
          "OBJECT",
          "ORGANIZATION",
          "QUALIFY",
          "REFERENCE",
          "REGEXP",
          "RLIKE",
          "SAMPLE",
          "SCHEMA",
          "STRING",
          "TEXT",
          "TIMESTAMPLTZ",
          "TIMESTAMPNTZ",
          "TIMESTAMPTZ",
          "TIMESTAMP_LTZ",
          "TIMESTAMP_NTZ",
          "TIMESTAMP_TZ",
          "TINYINT",
          "TRANSIT",
          "TRY_CAST",
          "VARIANT",
          "VECTOR",
          "VIEW");

  private static final String MAX_VARCHAR_BINARY_SIZE_PARAM_NAME =
      "VARCHAR_AND_BINARY_MAX_SIZE_IN_RESULT";

  // Defaults to 16MB
  private static final int DEFAULT_MAX_LOB_SIZE = 16777216;

  private final Connection connection;

  private final SFBaseSession session;

  private Telemetry ibInstance;

  private final boolean metadataRequestUseConnectionCtx;

  private boolean useSessionSchema = false;

  private final boolean metadataRequestUseSessionDatabase;

  private boolean stringsQuoted = false;

  // The number of columns for the result set returned from the current procedure. A value of -1
  // means the procedure doesn't return a result set
  private int procedureResultsetColumnNum;

  // Indicates if pattern matching is allowed for all parameters.
  private boolean isPatternMatchingEnabled = true;
  private boolean exactSchemaSearchEnabled;
  private boolean enableWildcardsInShowMetadataCommands;

  SnowflakeDatabaseMetaData(Connection connection) throws SQLException {
    logger.trace("SnowflakeDatabaseMetaData(SnowflakeConnection connection)", false);

    this.connection = connection;
    this.session = connection.unwrap(SnowflakeConnectionV1.class).getSFBaseSession();
    this.metadataRequestUseConnectionCtx = session.getMetadataRequestUseConnectionCtx();
    this.metadataRequestUseSessionDatabase = session.getMetadataRequestUseSessionDatabase();
    this.stringsQuoted = session.isStringQuoted();
    this.ibInstance = session.getTelemetryClient();
    this.procedureResultsetColumnNum = -1;
    this.isPatternMatchingEnabled = session.getEnablePatternSearch();
    this.exactSchemaSearchEnabled = session.getEnableExactSchemaSearch();
    this.enableWildcardsInShowMetadataCommands = session.getEnableWildcardsInShowMetadataCommands();
  }

  private void raiseSQLExceptionIfConnectionIsClosed() throws SQLException {
    if (connection.isClosed()) {
      throw new SnowflakeSQLException(ErrorCode.CONNECTION_CLOSED);
    }
  }

  /**
   * Function to send in-band telemetry data about DatabaseMetadata get API calls and their
   * associated SHOW commands
   *
   * @param resultSet The ResultSet generated from the SHOW command in the function call. Can be of
   *     type SnowflakeResultSet or SnowflakeDatabaseMetaDataResultSet
   * @param functionName name of DatabaseMetadata API function call
   * @param catalog database
   * @param schema schema
   * @param generalNamePattern name of table, function, etc
   * @param specificNamePattern name of table column, function parameter name, etc
   */
  private void sendInBandTelemetryMetadataMetrics(
      ResultSet resultSet,
      String functionName,
      String catalog,
      String schema,
      String generalNamePattern,
      String specificNamePattern) {
    String queryId = "";
    try {
      if (resultSet.isWrapperFor(SnowflakeResultSet.class)) {
        queryId = resultSet.unwrap(SnowflakeResultSet.class).getQueryID();
      } else if (resultSet.isWrapperFor(SnowflakeDatabaseMetaDataResultSet.class)) {
        queryId = resultSet.unwrap(SnowflakeDatabaseMetaDataResultSet.class).getQueryID();
      }
    } catch (SQLException e) {
      // This should never be reached because resultSet should always be one of the 2 types
      // unwrapped above.
      // In case we get here, do nothing; just don't include query ID
    }
    ObjectNode ibValue = mapper.createObjectNode();
    ibValue.put("type", TelemetryField.METADATA_METRICS.toString());
    ibValue.put("query_id", queryId);
    ibValue.put("function_name", functionName);
    ibValue.with("function_parameters").put("catalog", catalog);
    ibValue.with("function_parameters").put("schema", schema);
    ibValue.with("function_parameters").put("general_name_pattern", generalNamePattern);
    ibValue.with("function_parameters").put("specific_name_pattern", specificNamePattern);
    ibValue.put("use_connection_context", metadataRequestUseConnectionCtx ? "true" : "false");
    ibValue.put("session_database_name", session.getDatabase());
    ibValue.put("session_schema_name", session.getSchema());
    TelemetryData data = TelemetryUtil.buildJobData(ibValue);
    ibInstance.addLogToBatch(data);
  }

  // used to get convert string back to normal after its special characters have been escaped to
  // send it through Wildcard regex
  private String unescapeChars(String escapedString) {
    String unescapedString = escapedString.replace("\\_", "_");
    unescapedString = unescapedString.replace("\\%", "%");
    unescapedString = unescapedString.replace("\\\\", "\\");
    unescapedString = escapeSqlQuotes(unescapedString);
    return unescapedString;
  }

  // In SQL, double quotes must be escaped with an additional pair of double quotes. Add additional
  // quotes to avoid syntax errors with SQL queries.
  private String escapeSqlQuotes(String originalString) {
    return originalString.replace("\"", "\"\"");
  }

  /**
   * This guards against SQL injections by ensuring that any single quote is escaped properly.
   *
   * @param arg the original schema
   * @return
   */
  private String escapeSingleQuoteForLikeCommand(String arg) {
    if (arg == null) {
      return null;
    }
    int i = 0;
    int index = arg.indexOf("'", i);
    while (index != -1) {
      if (index == 0 || (index > 0 && arg.charAt(index - 1) != '\\')) {
        arg = arg.replace("'", "\\'");
        i = index + 2;
      } else {
        i = index + 1;
      }
      index = i < arg.length() ? arg.indexOf("'", i) : -1;
    }
    return arg;
  }

  private boolean isSchemaNameWildcardPattern(String inputString) {
    // if schema contains wildcard, don't treat it as wildcard; treat as just a schema name if
    // session schema or wildcards in identifiers in show metadata queries disabled
    return (useSessionSchema || !enableWildcardsInShowMetadataCommands)
        ? false
        : Wildcard.isWildcardPatternStr(inputString);
  }

  @Override
  public boolean allProceduresAreCallable() throws SQLException {
    logger.trace("boolean allProceduresAreCallable()", false);
    raiseSQLExceptionIfConnectionIsClosed();
    return false;
  }

  @Override
  public boolean allTablesAreSelectable() throws SQLException {
    logger.trace("boolean allTablesAreSelectable()", false);
    raiseSQLExceptionIfConnectionIsClosed();
    return true;
  }

  @Override
  public String getURL() throws SQLException {
    logger.trace("String getURL()", false);
    raiseSQLExceptionIfConnectionIsClosed();
    String url = session.getUrl();
    return url.startsWith("http://")
        ? url.replace("http://", "jdbc:snowflake://")
        : url.replace("https://", "jdbc:snowflake://");
  }

  @Override
  public String getUserName() throws SQLException {
    logger.trace("String getUserName()", false);
    raiseSQLExceptionIfConnectionIsClosed();
    return session.getUser();
  }

  @Override
  public boolean isReadOnly() throws SQLException {
    logger.trace("boolean isReadOnly()", false);
    raiseSQLExceptionIfConnectionIsClosed();
    // no read only mode is supported.
    return false;
  }

  @Override
  public boolean nullsAreSortedHigh() throws SQLException {
    logger.trace("boolean nullsAreSortedHigh()", false);
    raiseSQLExceptionIfConnectionIsClosed();
    return true;
  }

  @Override
  public boolean nullsAreSortedLow() throws SQLException {
    logger.trace("boolean nullsAreSortedLow()", false);
    raiseSQLExceptionIfConnectionIsClosed();
    return false;
  }

  @Override
  public boolean nullsAreSortedAtStart() throws SQLException {
    logger.trace("boolean nullsAreSortedAtStart()", false);
    raiseSQLExceptionIfConnectionIsClosed();
    return false;
  }

  @Override
  public boolean nullsAreSortedAtEnd() throws SQLException {
    logger.trace("boolean nullsAreSortedAtEnd()", false);
    raiseSQLExceptionIfConnectionIsClosed();
    return false;
  }

  @Override
  public String getDatabaseProductName() throws SQLException {
    logger.trace("String getDatabaseProductName()", false);
    raiseSQLExceptionIfConnectionIsClosed();
    return DatabaseProductName;
  }

  @Override
  public String getDatabaseProductVersion() throws SQLException {
    logger.trace("String getDatabaseProductVersion()", false);
    raiseSQLExceptionIfConnectionIsClosed();
    return connection.unwrap(SnowflakeConnectionV1.class).getDatabaseVersion();
  }

  @Override
  public String getDriverName() throws SQLException {
    logger.trace("String getDriverName()", false);
    raiseSQLExceptionIfConnectionIsClosed();
    return DriverName;
  }

  @Override
  public String getDriverVersion() throws SQLException {
    logger.trace("String getDriverVersion()", false);
    raiseSQLExceptionIfConnectionIsClosed();
    return SnowflakeDriver.majorVersion
        + "."
        + SnowflakeDriver.minorVersion
        + "."
        + SnowflakeDriver.patchVersion;
  }

  @Override
  public int getDriverMajorVersion() {
    logger.trace("int getDriverMajorVersion()", false);
    return SnowflakeDriver.majorVersion;
  }

  @Override
  public int getDriverMinorVersion() {
    logger.trace("int getDriverMinorVersion()", false);
    return SnowflakeDriver.minorVersion;
  }

  @Override
  public boolean usesLocalFiles() throws SQLException {
    logger.trace("boolean usesLocalFiles()", false);
    raiseSQLExceptionIfConnectionIsClosed();
    return false;
  }

  @Override
  public boolean usesLocalFilePerTable() throws SQLException {
    logger.trace("boolean usesLocalFilePerTable()", false);
    raiseSQLExceptionIfConnectionIsClosed();
    return false;
  }

  @Override
  public boolean supportsMixedCaseIdentifiers() throws SQLException {
    logger.trace("boolean supportsMixedCaseIdentifiers()", false);
    raiseSQLExceptionIfConnectionIsClosed();
    return false;
  }

  @Override
  public boolean storesUpperCaseIdentifiers() throws SQLException {
    logger.trace("boolean storesUpperCaseIdentifiers()", false);
    raiseSQLExceptionIfConnectionIsClosed();
    return true;
  }

  @Override
  public boolean storesLowerCaseIdentifiers() throws SQLException {
    logger.trace("boolean storesLowerCaseIdentifiers()", false);
    raiseSQLExceptionIfConnectionIsClosed();
    return false;
  }

  @Override
  public boolean storesMixedCaseIdentifiers() throws SQLException {
    logger.trace("boolean storesMixedCaseIdentifiers()", false);
    raiseSQLExceptionIfConnectionIsClosed();
    return false;
  }

  @Override
  public boolean supportsMixedCaseQuotedIdentifiers() throws SQLException {
    logger.trace("boolean supportsMixedCaseQuotedIdentifiers()", false);
    raiseSQLExceptionIfConnectionIsClosed();
    return true;
  }

  @Override
  public boolean storesUpperCaseQuotedIdentifiers() throws SQLException {
    logger.trace("boolean storesUpperCaseQuotedIdentifiers()", false);
    raiseSQLExceptionIfConnectionIsClosed();
    return false;
  }

  @Override
  public boolean storesLowerCaseQuotedIdentifiers() throws SQLException {
    logger.trace("boolean storesLowerCaseQuotedIdentifiers()", false);
    raiseSQLExceptionIfConnectionIsClosed();
    return false;
  }

  @Override
  public boolean storesMixedCaseQuotedIdentifiers() throws SQLException {
    logger.trace("boolean storesMixedCaseQuotedIdentifiers()", false);
    raiseSQLExceptionIfConnectionIsClosed();
    return true;
  }

  @Override
  public String getIdentifierQuoteString() throws SQLException {
    logger.trace("String getIdentifierQuoteString()", false);
    raiseSQLExceptionIfConnectionIsClosed();
    return "\"";
  }

  @Override
  public String getSQLKeywords() throws SQLException {
    logger.trace("String getSQLKeywords()", false);
    raiseSQLExceptionIfConnectionIsClosed();
    return notSQL2003Keywords;
  }

  @Override
  public String getNumericFunctions() throws SQLException {
    logger.trace("String getNumericFunctions()", false);
    raiseSQLExceptionIfConnectionIsClosed();
    return NumericFunctionsSupported;
  }

  @Override
  public String getStringFunctions() throws SQLException {
    logger.trace("String getStringFunctions()", false);
    raiseSQLExceptionIfConnectionIsClosed();
    return StringFunctionsSupported;
  }

  @Override
  public String getSystemFunctions() throws SQLException {
    logger.trace("String getSystemFunctions()", false);
    raiseSQLExceptionIfConnectionIsClosed();
    return SystemFunctionsSupported;
  }

  @Override
  public String getTimeDateFunctions() throws SQLException {
    logger.trace("String getTimeDateFunctions()", false);
    raiseSQLExceptionIfConnectionIsClosed();
    return DateAndTimeFunctionsSupported;
  }

  @Override
  public String getSearchStringEscape() throws SQLException {
    logger.trace("String getSearchStringEscape()", false);
    raiseSQLExceptionIfConnectionIsClosed();
    return Character.toString(SEARCH_STRING_ESCAPE);
  }

  @Override
  public String getExtraNameCharacters() throws SQLException {
    logger.trace("String getExtraNameCharacters()", false);
    raiseSQLExceptionIfConnectionIsClosed();
    return "$";
  }

  @Override
  public boolean supportsAlterTableWithAddColumn() throws SQLException {
    logger.trace("boolean supportsAlterTableWithAddColumn()", false);
    raiseSQLExceptionIfConnectionIsClosed();
    return true;
  }

  @Override
  public boolean supportsAlterTableWithDropColumn() throws SQLException {
    logger.trace("boolean supportsAlterTableWithDropColumn()", false);
    raiseSQLExceptionIfConnectionIsClosed();
    return true;
  }

  @Override
  public boolean supportsColumnAliasing() throws SQLException {
    logger.trace("boolean supportsColumnAliasing()", false);
    raiseSQLExceptionIfConnectionIsClosed();
    return true;
  }

  @Override
  public boolean nullPlusNonNullIsNull() throws SQLException {
    logger.trace("boolean nullPlusNonNullIsNull()", false);
    raiseSQLExceptionIfConnectionIsClosed();
    return true;
  }

  @Override
  public boolean supportsConvert() throws SQLException {
    logger.trace("boolean supportsConvert()", false);
    raiseSQLExceptionIfConnectionIsClosed();
    return false;
  }

  @Override
  public boolean supportsConvert(int fromType, int toType) throws SQLException {
    logger.trace("boolean supportsConvert(int fromType, int toType)", false);
    raiseSQLExceptionIfConnectionIsClosed();
    return false;
  }

  @Override
  public boolean supportsTableCorrelationNames() throws SQLException {
    logger.trace("boolean supportsTableCorrelationNames()", false);
    raiseSQLExceptionIfConnectionIsClosed();
    return true;
  }

  @Override
  public boolean supportsDifferentTableCorrelationNames() throws SQLException {
    logger.trace("boolean supportsDifferentTableCorrelationNames()", false);
    raiseSQLExceptionIfConnectionIsClosed();
    return false;
  }

  @Override
  public boolean supportsExpressionsInOrderBy() throws SQLException {
    logger.trace("boolean supportsExpressionsInOrderBy()", false);
    raiseSQLExceptionIfConnectionIsClosed();
    return true;
  }

  @Override
  public boolean supportsOrderByUnrelated() throws SQLException {
    logger.trace("boolean supportsOrderByUnrelated()", false);
    raiseSQLExceptionIfConnectionIsClosed();
    return true;
  }

  @Override
  public boolean supportsGroupBy() throws SQLException {
    logger.trace("boolean supportsGroupBy()", false);
    raiseSQLExceptionIfConnectionIsClosed();
    return true;
  }

  @Override
  public boolean supportsGroupByUnrelated() throws SQLException {
    logger.trace("boolean supportsGroupByUnrelated()", false);
    raiseSQLExceptionIfConnectionIsClosed();
    return false;
  }

  @Override
  public boolean supportsGroupByBeyondSelect() throws SQLException {
    logger.trace("boolean supportsGroupByBeyondSelect()", false);
    raiseSQLExceptionIfConnectionIsClosed();
    return true;
  }

  @Override
  public boolean supportsLikeEscapeClause() throws SQLException {
    logger.trace("boolean supportsLikeEscapeClause()", false);
    raiseSQLExceptionIfConnectionIsClosed();
    return false;
  }

  @Override
  public boolean supportsMultipleResultSets() throws SQLException {
    logger.trace("boolean supportsMultipleResultSets()", false);
    raiseSQLExceptionIfConnectionIsClosed();
    return false;
  }

  @Override
  public boolean supportsMultipleTransactions() throws SQLException {
    logger.trace("boolean supportsMultipleTransactions()", false);
    raiseSQLExceptionIfConnectionIsClosed();
    return true;
  }

  @Override
  public boolean supportsNonNullableColumns() throws SQLException {
    logger.trace("boolean supportsNonNullableColumns()", false);
    raiseSQLExceptionIfConnectionIsClosed();
    return true;
  }

  @Override
  public boolean supportsMinimumSQLGrammar() throws SQLException {
    logger.trace("boolean supportsMinimumSQLGrammar()", false);
    raiseSQLExceptionIfConnectionIsClosed();
    return false;
  }

  @Override
  public boolean supportsCoreSQLGrammar() throws SQLException {
    logger.trace("boolean supportsCoreSQLGrammar()", false);
    raiseSQLExceptionIfConnectionIsClosed();
    return false;
  }

  @Override
  public boolean supportsExtendedSQLGrammar() throws SQLException {
    logger.trace("boolean supportsExtendedSQLGrammar()", false);
    raiseSQLExceptionIfConnectionIsClosed();
    return false;
  }

  @Override
  public boolean supportsANSI92EntryLevelSQL() throws SQLException {
    logger.trace("boolean supportsANSI92EntryLevelSQL()", false);
    raiseSQLExceptionIfConnectionIsClosed();
    return true;
  }

  @Override
  public boolean supportsANSI92IntermediateSQL() throws SQLException {
    logger.trace("boolean supportsANSI92IntermediateSQL()", false);
    raiseSQLExceptionIfConnectionIsClosed();
    return false;
  }

  @Override
  public boolean supportsANSI92FullSQL() throws SQLException {
    logger.trace("boolean supportsANSI92FullSQL()", false);
    raiseSQLExceptionIfConnectionIsClosed();
    return false;
  }

  @Override
  public boolean supportsIntegrityEnhancementFacility() throws SQLException {
    logger.trace("boolean supportsIntegrityEnhancementFacility()", false);
    raiseSQLExceptionIfConnectionIsClosed();
    return false;
  }

  @Override
  public boolean supportsOuterJoins() throws SQLException {
    logger.trace("boolean supportsOuterJoins()", false);
    raiseSQLExceptionIfConnectionIsClosed();
    return true;
  }

  @Override
  public boolean supportsFullOuterJoins() throws SQLException {
    logger.trace("boolean supportsFullOuterJoins()", false);
    raiseSQLExceptionIfConnectionIsClosed();
    return true;
  }

  @Override
  public boolean supportsLimitedOuterJoins() throws SQLException {
    logger.trace("boolean supportsLimitedOuterJoins()", false);
    raiseSQLExceptionIfConnectionIsClosed();
    return true;
  }

  @Override
  public String getSchemaTerm() throws SQLException {
    logger.trace("String getSchemaTerm()", false);
    raiseSQLExceptionIfConnectionIsClosed();
    return "schema";
  }

  @Override
  public String getProcedureTerm() throws SQLException {
    logger.trace("String getProcedureTerm()", false);
    raiseSQLExceptionIfConnectionIsClosed();
    return "procedure";
  }

  @Override
  public String getCatalogTerm() throws SQLException {
    logger.trace("String getCatalogTerm()", false);
    raiseSQLExceptionIfConnectionIsClosed();
    return "database";
  }

  @Override
  public boolean isCatalogAtStart() throws SQLException {
    logger.trace("boolean isCatalogAtStart()", false);
    raiseSQLExceptionIfConnectionIsClosed();
    return true;
  }

  @Override
  public String getCatalogSeparator() throws SQLException {
    logger.trace("String getCatalogSeparator()", false);
    raiseSQLExceptionIfConnectionIsClosed();
    return ".";
  }

  @Override
  public boolean supportsSchemasInDataManipulation() throws SQLException {
    logger.trace("boolean supportsSchemasInDataManipulation()", false);
    raiseSQLExceptionIfConnectionIsClosed();
    return true;
  }

  @Override
  public boolean supportsSchemasInProcedureCalls() throws SQLException {
    logger.trace("boolean supportsSchemasInProcedureCalls()", false);
    raiseSQLExceptionIfConnectionIsClosed();
    return false;
  }

  @Override
  public boolean supportsSchemasInTableDefinitions() throws SQLException {
    logger.trace("boolean supportsSchemasInTableDefinitions()", false);
    raiseSQLExceptionIfConnectionIsClosed();
    return true;
  }

  @Override
  public boolean supportsSchemasInIndexDefinitions() throws SQLException {
    logger.trace("boolean supportsSchemasInIndexDefinitions()", false);
    raiseSQLExceptionIfConnectionIsClosed();
    return false;
  }

  @Override
  public boolean supportsSchemasInPrivilegeDefinitions() throws SQLException {
    logger.trace("boolean supportsSchemasInPrivilegeDefinitions()", false);
    raiseSQLExceptionIfConnectionIsClosed();
    return false;
  }

  @Override
  public boolean supportsCatalogsInDataManipulation() throws SQLException {
    logger.trace("boolean supportsCatalogsInDataManipulation()", false);
    raiseSQLExceptionIfConnectionIsClosed();
    return true;
  }

  @Override
  public boolean supportsCatalogsInProcedureCalls() throws SQLException {
    logger.trace("boolean supportsCatalogsInProcedureCalls()", false);
    raiseSQLExceptionIfConnectionIsClosed();
    return false;
  }

  @Override
  public boolean supportsCatalogsInTableDefinitions() throws SQLException {
    logger.trace("boolean supportsCatalogsInTableDefinitions()", false);
    raiseSQLExceptionIfConnectionIsClosed();
    return true;
  }

  @Override
  public boolean supportsCatalogsInIndexDefinitions() throws SQLException {
    logger.trace("boolean supportsCatalogsInIndexDefinitions()", false);
    raiseSQLExceptionIfConnectionIsClosed();
    return false;
  }

  @Override
  public boolean supportsCatalogsInPrivilegeDefinitions() throws SQLException {
    logger.trace("boolean supportsCatalogsInPrivilegeDefinitions()", false);
    raiseSQLExceptionIfConnectionIsClosed();
    return false;
  }

  @Override
  public boolean supportsPositionedDelete() throws SQLException {
    logger.trace("boolean supportsPositionedDelete()", false);
    raiseSQLExceptionIfConnectionIsClosed();
    return false;
  }

  @Override
  public boolean supportsPositionedUpdate() throws SQLException {
    logger.trace("boolean supportsPositionedUpdate()", false);
    raiseSQLExceptionIfConnectionIsClosed();
    return false;
  }

  @Override
  public boolean supportsSelectForUpdate() throws SQLException {
    logger.trace("boolean supportsSelectForUpdate()", false);
    raiseSQLExceptionIfConnectionIsClosed();
    return false;
  }

  @Override
  public boolean supportsStoredProcedures() throws SQLException {
    logger.trace("boolean supportsStoredProcedures()", false);
    raiseSQLExceptionIfConnectionIsClosed();
    return true;
  }

  @Override
  public boolean supportsSubqueriesInComparisons() throws SQLException {
    logger.trace("boolean supportsSubqueriesInComparisons()", false);
    raiseSQLExceptionIfConnectionIsClosed();
    return true;
  }

  @Override
  public boolean supportsSubqueriesInExists() throws SQLException {
    logger.trace("boolean supportsSubqueriesInExists()", false);
    raiseSQLExceptionIfConnectionIsClosed();
    return true;
  }

  @Override
  public boolean supportsSubqueriesInIns() throws SQLException {
    logger.trace("boolean supportsSubqueriesInIns()", false);
    raiseSQLExceptionIfConnectionIsClosed();
    return true;
  }

  @Override
  public boolean supportsSubqueriesInQuantifieds() throws SQLException {
    logger.trace("boolean supportsSubqueriesInQuantifieds()", false);
    raiseSQLExceptionIfConnectionIsClosed();
    return false;
  }

  @Override
  public boolean supportsCorrelatedSubqueries() throws SQLException {
    logger.trace("boolean supportsCorrelatedSubqueries()", false);
    raiseSQLExceptionIfConnectionIsClosed();
    return true;
  }

  @Override
  public boolean supportsUnion() throws SQLException {
    logger.trace("boolean supportsUnion()", false);
    raiseSQLExceptionIfConnectionIsClosed();
    return true;
  }

  @Override
  public boolean supportsUnionAll() throws SQLException {
    logger.trace("boolean supportsUnionAll()", false);
    raiseSQLExceptionIfConnectionIsClosed();
    return true;
  }

  @Override
  public boolean supportsOpenCursorsAcrossCommit() throws SQLException {
    logger.trace("boolean supportsOpenCursorsAcrossCommit()", false);
    raiseSQLExceptionIfConnectionIsClosed();
    return false;
  }

  @Override
  public boolean supportsOpenCursorsAcrossRollback() throws SQLException {
    logger.trace("boolean supportsOpenCursorsAcrossRollback()", false);
    raiseSQLExceptionIfConnectionIsClosed();
    return false;
  }

  @Override
  public boolean supportsOpenStatementsAcrossCommit() throws SQLException {
    logger.trace("boolean supportsOpenStatementsAcrossCommit()", false);
    raiseSQLExceptionIfConnectionIsClosed();
    return false;
  }

  @Override
  public boolean supportsOpenStatementsAcrossRollback() throws SQLException {
    logger.trace("boolean supportsOpenStatementsAcrossRollback()", false);
    raiseSQLExceptionIfConnectionIsClosed();
    return false;
  }

  @Override
  public int getMaxBinaryLiteralLength() throws SQLException {
    logger.trace("int getMaxBinaryLiteralLength()", false);
    raiseSQLExceptionIfConnectionIsClosed();
    return getMaxCharLiteralLength() / 2; // hex instead of octal, thus divided by 2
  }

  @Override
  public int getMaxCharLiteralLength() throws SQLException {
    logger.trace("int getMaxCharLiteralLength()", false);
    raiseSQLExceptionIfConnectionIsClosed();
    Optional<Integer> maxLiteralLengthFromSession =
        Optional.ofNullable(
            (Integer) session.getOtherParameter(MAX_VARCHAR_BINARY_SIZE_PARAM_NAME));
    return maxLiteralLengthFromSession.orElse(DEFAULT_MAX_LOB_SIZE);
  }

  @Override
  public int getMaxColumnNameLength() throws SQLException {
    logger.trace("int getMaxColumnNameLength()", false);
    raiseSQLExceptionIfConnectionIsClosed();
    return 255;
  }

  @Override
  public int getMaxColumnsInGroupBy() throws SQLException {
    logger.trace("int getMaxColumnsInGroupBy()", false);
    raiseSQLExceptionIfConnectionIsClosed();
    return 0;
  }

  @Override
  public int getMaxColumnsInIndex() throws SQLException {
    logger.trace("int getMaxColumnsInIndex()", false);
    raiseSQLExceptionIfConnectionIsClosed();
    return 0;
  }

  @Override
  public int getMaxColumnsInOrderBy() throws SQLException {
    logger.trace("int getMaxColumnsInOrderBy()", false);
    raiseSQLExceptionIfConnectionIsClosed();
    return 0;
  }

  @Override
  public int getMaxColumnsInSelect() throws SQLException {
    logger.trace("int getMaxColumnsInSelect()", false);
    raiseSQLExceptionIfConnectionIsClosed();
    return 0;
  }

  @Override
  public int getMaxColumnsInTable() throws SQLException {
    logger.trace("int getMaxColumnsInTable()", false);
    raiseSQLExceptionIfConnectionIsClosed();
    return 0;
  }

  @Override
  public int getMaxConnections() throws SQLException {
    logger.trace("int getMaxConnections()", false);
    raiseSQLExceptionIfConnectionIsClosed();
    return 0;
  }

  @Override
  public int getMaxCursorNameLength() throws SQLException {
    logger.trace("int getMaxCursorNameLength()", false);
    raiseSQLExceptionIfConnectionIsClosed();
    return 0;
  }

  @Override
  public int getMaxIndexLength() throws SQLException {
    logger.trace("int getMaxIndexLength()", false);
    raiseSQLExceptionIfConnectionIsClosed();
    return 0;
  }

  @Override
  public int getMaxSchemaNameLength() throws SQLException {
    logger.trace("int getMaxSchemaNameLength()", false);
    raiseSQLExceptionIfConnectionIsClosed();
    return 255;
  }

  @Override
  public int getMaxProcedureNameLength() throws SQLException {
    logger.trace("int getMaxProcedureNameLength()", false);
    raiseSQLExceptionIfConnectionIsClosed();
    return 0;
  }

  @Override
  public int getMaxCatalogNameLength() throws SQLException {
    logger.trace("int getMaxCatalogNameLength()", false);
    raiseSQLExceptionIfConnectionIsClosed();
    return 255;
  }

  @Override
  public int getMaxRowSize() throws SQLException {
    logger.trace("int getMaxRowSize()", false);
    raiseSQLExceptionIfConnectionIsClosed();
    return 0;
  }

  @Override
  public boolean doesMaxRowSizeIncludeBlobs() throws SQLException {
    logger.trace("boolean doesMaxRowSizeIncludeBlobs()", false);
    raiseSQLExceptionIfConnectionIsClosed();
    return true;
  }

  @Override
  public int getMaxStatementLength() throws SQLException {
    logger.trace("int getMaxStatementLength()", false);
    raiseSQLExceptionIfConnectionIsClosed();
    return 0;
  }

  @Override
  public int getMaxStatements() throws SQLException {
    logger.trace("int getMaxStatements()", false);
    raiseSQLExceptionIfConnectionIsClosed();
    return 0;
  }

  @Override
  public int getMaxTableNameLength() throws SQLException {
    logger.trace("int getMaxTableNameLength()", false);
    raiseSQLExceptionIfConnectionIsClosed();
    return 255;
  }

  @Override
  public int getMaxTablesInSelect() throws SQLException {
    logger.trace("int getMaxTablesInSelect()", false);
    raiseSQLExceptionIfConnectionIsClosed();
    return 0;
  }

  @Override
  public int getMaxUserNameLength() throws SQLException {
    logger.trace("int getMaxUserNameLength()", false);
    raiseSQLExceptionIfConnectionIsClosed();
    return 255;
  }

  @Override
  public int getDefaultTransactionIsolation() throws SQLException {
    logger.trace("int getDefaultTransactionIsolation()", false);
    raiseSQLExceptionIfConnectionIsClosed();
    return Connection.TRANSACTION_READ_COMMITTED;
  }

  @Override
  public boolean supportsTransactions() throws SQLException {
    logger.trace("boolean supportsTransactions()", false);
    raiseSQLExceptionIfConnectionIsClosed();
    return true;
  }

  @Override
  public boolean supportsTransactionIsolationLevel(int level) throws SQLException {
    logger.trace("boolean supportsTransactionIsolationLevel(int level)", false);
    raiseSQLExceptionIfConnectionIsClosed();
    return (level == Connection.TRANSACTION_NONE)
        || (level == Connection.TRANSACTION_READ_COMMITTED);
  }

  @Override
  public boolean supportsDataDefinitionAndDataManipulationTransactions() throws SQLException {
    logger.trace("boolean supportsDataDefinitionAndDataManipulationTransactions()", false);
    raiseSQLExceptionIfConnectionIsClosed();
    return true;
  }

  @Override
  public boolean supportsDataManipulationTransactionsOnly() throws SQLException {
    logger.trace("boolean supportsDataManipulationTransactionsOnly()", false);
    raiseSQLExceptionIfConnectionIsClosed();
    return false;
  }

  @Override
  public boolean dataDefinitionCausesTransactionCommit() throws SQLException {
    logger.trace("boolean dataDefinitionCausesTransactionCommit()", false);
    raiseSQLExceptionIfConnectionIsClosed();
    return true;
  }

  @Override
  public boolean dataDefinitionIgnoredInTransactions() throws SQLException {
    logger.trace("boolean dataDefinitionIgnoredInTransactions()", false);
    raiseSQLExceptionIfConnectionIsClosed();
    return false;
  }

  @Override
  public ResultSet getProcedures(
      final String originalCatalog,
      final String originalSchemaPattern,
      final String procedureNamePattern)
      throws SQLException {
    raiseSQLExceptionIfConnectionIsClosed();
    Statement statement = connection.createStatement();
    logger.trace(
        "public ResultSet getProcedures(String originalCatalog, "
            + "String originalSchemaPattern,String procedureNamePattern)",
        false);

    String showProcedureCommand =
        getFirstResultSetCommand(
            originalCatalog, originalSchemaPattern, procedureNamePattern, "procedures");

    if (showProcedureCommand.isEmpty()) {
      return SnowflakeDatabaseMetaDataResultSet.getEmptyResultSet(GET_PROCEDURES, statement);
    }

    ContextAwareMetadataSearch result = applySessionContext(originalCatalog, originalSchemaPattern);
    String catalog = result.database();
    String schemaPattern = result.schema();
    boolean isExactSchema = result.isExactSchema();

    final Pattern compiledSchemaPattern = Wildcard.toRegexPattern(schemaPattern, true);
    final Pattern compiledProcedurePattern = Wildcard.toRegexPattern(procedureNamePattern, true);

    ResultSet resultSet =
        executeAndReturnEmptyResultIfNotFound(statement, showProcedureCommand, GET_PROCEDURES);
    sendInBandTelemetryMetadataMetrics(
        resultSet, "getProcedures", catalog, schemaPattern, procedureNamePattern, "none");

    return new SnowflakeDatabaseMetaDataQueryResultSet(GET_PROCEDURES, resultSet, statement) {
      public boolean next() throws SQLException {
        logger.trace("boolean next()", false);
        incrementRow();

        // iterate throw the show table result until we find an entry
        // that matches the table name
        while (showObjectResultSet.next()) {
          String catalogName = showObjectResultSet.getString("catalog_name");
          String schemaName = showObjectResultSet.getString("schema_name");
          String procedureName = showObjectResultSet.getString("name");
          String remarks = showObjectResultSet.getString("description");
          String specificName = showObjectResultSet.getString("arguments");
          short procedureType = procedureReturnsResult;
          if ((compiledProcedurePattern == null
                  || compiledProcedurePattern.matcher(procedureName).matches())
              && (compiledSchemaPattern == null
                  || compiledSchemaPattern.matcher(schemaName).matches()
                  || isExactSchema && schemaPattern.equals(schemaPattern))) {
            logger.trace("Found a matched function:" + schemaName + "." + procedureName);

            nextRow[0] = catalogName;
            nextRow[1] = schemaName;
            nextRow[2] = procedureName;
            nextRow[3] = remarks;
            nextRow[4] = procedureType;
            nextRow[5] = specificName;
            return true;
          }
        }
        close();
        return false;
      }
    };
  }

  @Override
  public ResultSet getProcedureColumns(
      final String catalog,
      final String schemaPattern,
      final String procedureNamePattern,
      final String columnNamePattern)
      throws SQLException {
    logger.trace(
        "public ResultSet getProcedureColumns(String catalog, "
            + "String schemaPattern,String procedureNamePattern,"
            + "String columnNamePattern)",
        false);
    raiseSQLExceptionIfConnectionIsClosed();
    Statement statement = connection.createStatement();
    boolean addAllRows = false;

    String showProcedureCommand =
        getFirstResultSetCommand(catalog, schemaPattern, procedureNamePattern, "procedures");
    if (showProcedureCommand.isEmpty()) {
      return SnowflakeDatabaseMetaDataResultSet.getEmptyResultSet(GET_PROCEDURE_COLUMNS, statement);
    }

    final Pattern compiledSchemaPattern = Wildcard.toRegexPattern(schemaPattern, true);
    final Pattern compiledProcedurePattern = Wildcard.toRegexPattern(procedureNamePattern, true);

    if (columnNamePattern == null
        || columnNamePattern.isEmpty()
        || columnNamePattern.trim().equals("%")
        || columnNamePattern.trim().equals(".*")) {
      addAllRows = true;
    }

    ResultSet resultSetStepOne =
        executeAndReturnEmptyResultIfNotFound(
            statement, showProcedureCommand, GET_PROCEDURE_COLUMNS);
    sendInBandTelemetryMetadataMetrics(
        resultSetStepOne,
        "getProcedureColumns",
        catalog,
        schemaPattern,
        procedureNamePattern,
        columnNamePattern);
    ArrayList<Object[]> rows = new ArrayList<Object[]>();
    while (resultSetStepOne.next()) {
      String procedureNameUnparsed = resultSetStepOne.getString("arguments").trim();
      String procedureNameNoArgs = resultSetStepOne.getString("name");
      String schemaName = resultSetStepOne.getString("schema_name");
      // Check that schema name match the original input
      // And check special case - schema with special name in quotes
      boolean isSchemaNameMatch =
          compiledSchemaPattern != null
              && (compiledSchemaPattern.matcher(schemaName).matches()
                  || (schemaName.startsWith("\"")
                      && schemaName.endsWith("\"")
                      && compiledSchemaPattern
                          .matcher(schemaName)
                          .region(1, schemaName.length() - 1)
                          .matches()));

      // Check that procedure name and schema name match the original input in case wildcards have
      // been used.
      // Procedure name column check must occur later when columns are parsed.
      if ((compiledProcedurePattern != null
              && !compiledProcedurePattern.matcher(procedureNameNoArgs).matches())
          || (compiledSchemaPattern != null && !isSchemaNameMatch)) {
        continue;
      }
      String catalogName = resultSetStepOne.getString("catalog_name");
      String showProcedureColCommand =
          getSecondResultSetCommand(catalogName, schemaName, procedureNameUnparsed, "procedure");

      ResultSet resultSetStepTwo =
          executeAndReturnEmptyResultIfNotFound(
              statement, showProcedureColCommand, GET_PROCEDURE_COLUMNS);
      if (resultSetStepTwo.next() == false) {
        continue;
      }
      // Retrieve the procedure arguments and procedure return values.
      String args = resultSetStepTwo.getString("value");
      resultSetStepTwo.next();
      String res = resultSetStepTwo.getString("value");
      // parse procedure arguments and return values into a list of columns
      // result value(s) will be at the top of the list, followed by any arguments
      List<String> procedureCols = parseColumns(res, args);
      String paramNames[] = new String[procedureCols.size() / 2];
      String paramTypes[] = new String[procedureCols.size() / 2];
      if (procedureCols.size() > 1) {
        for (int i = 0; i < procedureCols.size(); i++) {
          if (i % 2 == 0) {
            paramNames[i / 2] = procedureCols.get(i);
          } else {
            paramTypes[i / 2] = procedureCols.get(i);
          }
        }
      }
      for (int i = 0; i < paramNames.length; i++) {
        // if it's the 1st in for loop, it's the result
        if (i == 0 || paramNames[i].equalsIgnoreCase(columnNamePattern) || addAllRows) {
          Object[] nextRow = new Object[20];
          // add a row to resultSet
          nextRow[0] = catalog; // catalog. Can be null.
          nextRow[1] = schemaName; // schema. Can be null.
          nextRow[2] = procedureNameNoArgs; // procedure name
          nextRow[3] = paramNames[i]; // column/parameter name
          // column type
          if (i == 0 && procedureResultsetColumnNum < 0) {
            nextRow[4] = procedureColumnReturn;
          } else if (procedureResultsetColumnNum >= 0 && i < procedureResultsetColumnNum) {
            nextRow[4] = procedureColumnResult;
          } else {
            nextRow[4] = procedureColumnIn; // kind of column/parameter
          }
          String typeName = paramTypes[i];
          String typeNameTrimmed = typeName;
          // don't include nullability in type name, such as NUMBER NOT NULL. Just include NUMBER.
          if (typeName.contains(" NOT NULL")) {
            typeNameTrimmed = typeName.substring(0, typeName.indexOf(' '));
          }
          // don't include column size in type name
          if (typeNameTrimmed.contains("(") && typeNameTrimmed.contains(")")) {
            typeNameTrimmed = typeNameTrimmed.substring(0, typeNameTrimmed.indexOf('('));
          }
          int type = convertStringToType(typeName);
          nextRow[5] = type; // data type
          nextRow[6] = typeNameTrimmed; // type name
          // precision and scale. Values only exist for numbers
          int precision = 38;
          short scale = 0;
          if (type < 10) {
            if (typeName.contains("(") && typeName.contains(")")) {
              precision =
                  Integer.parseInt(
                      typeName.substring(typeName.indexOf('(') + 1, typeName.indexOf(',')));
              scale =
                  Short.parseShort(
                      typeName.substring(typeName.indexOf(',') + 1, typeName.indexOf(')')));
              nextRow[7] = precision;
              nextRow[9] = scale;
            } else {
              nextRow[7] = precision;
              nextRow[9] = scale;
            }
          } else {
            nextRow[7] = 0;
            nextRow[9] = null;
          }
          nextRow[8] = 0; // length in bytes. not supported
          nextRow[10] = 10; // radix. Probably 10 is default, but unknown.
          // if type specifies "not null", no null values are allowed.
          if (typeName.toLowerCase().contains("not null")) {
            nextRow[11] = procedureNoNulls;
            nextRow[18] = "NO";
          }
          // if the type is a return value (only when i = 0), it can always be specified as "not
          // null." The fact that
          // this isn't specified means it has nullable return values.
          else if (i == 0) {
            nextRow[11] = procedureNullable;
            nextRow[18] = "YES";
          }
          // if the row is for an input parameter, it's impossible to know from the description
          // whether the values
          // are allowed to be null or not. Nullability is unknown.
          else {
            nextRow[11] =
                procedureNullableUnknown; // nullable. We don't know from current function info.
            nextRow[18] = "";
          }
          nextRow[12] = resultSetStepOne.getString("description").trim(); // remarks
          nextRow[13] = null; // default value for column. Not supported
          nextRow[14] = 0; // Sql data type: reserved for future use
          nextRow[15] = 0; // sql datetime sub: reserved for future use
          // char octet length
          if (type == Types.BINARY
              || type == Types.VARBINARY
              || type == Types.CHAR
              || type == Types.VARCHAR) {
            if (typeName.contains("(") && typeName.contains(")")) {
              int char_octet_len =
                  Integer.parseInt(
                      typeName.substring(typeName.indexOf('(') + 1, typeName.indexOf(')')));
              nextRow[16] = char_octet_len;
            } else if (type == Types.CHAR || type == Types.VARCHAR) {
              nextRow[16] = getMaxCharLiteralLength();
            } else if (type == Types.BINARY || type == Types.VARBINARY) {
              nextRow[16] = getMaxBinaryLiteralLength();
            }
          } else {
            nextRow[16] = null;
          }
          // the ordinal position is 0 for a return value.
          // for result set columns, the ordinal position is of the column in the result set
          // starting at 1
          if (procedureResultsetColumnNum >= 0) {
            if (i < procedureResultsetColumnNum) {
              nextRow[17] = i + 1;
            } else {
              nextRow[17] = i - procedureResultsetColumnNum + 1;
            }
          } else {
            nextRow[17] = i; // ordinal position.
          }
          nextRow[19] = procedureNameUnparsed; // specific name
          rows.add(nextRow);
        }
      }
    }
    Object[][] resultRows = new Object[rows.size()][20];
    for (int i = 0; i < resultRows.length; i++) {
      resultRows[i] = rows.get(i);
    }
    return new SnowflakeDatabaseMetaDataResultSet(GET_PROCEDURE_COLUMNS, resultRows, statement);
  }

  // apply session context when catalog is unspecified
  private ContextAwareMetadataSearch applySessionContext(String catalog, String schemaPattern) {
    if (metadataRequestUseConnectionCtx) {
      // CLIENT_METADATA_USE_SESSION_DATABASE = TRUE
      if (catalog == null) {
        catalog = session.getDatabase();
      }
      if (schemaPattern == null) {
        schemaPattern = session.getSchema();
        useSessionSchema = true;
      }
    } else {
      if (metadataRequestUseSessionDatabase) {
        if (catalog == null) {
          catalog = session.getDatabase();
        }
      }
    }
    return new ContextAwareMetadataSearch(
        catalog,
        schemaPattern,
        (exactSchemaSearchEnabled && useSessionSchema) || !enableWildcardsInShowMetadataCommands);
  }

  /* helper function for getProcedures, getFunctionColumns, etc. Returns sql command to show some type of result such
  as procedures or udfs */
  private String getFirstResultSetCommand(
      String catalog, String schemaPattern, String name, String type) {
    // apply session context when catalog is unspecified
    ContextAwareMetadataSearch result = applySessionContext(catalog, schemaPattern);
    catalog = result.database();
    schemaPattern = result.schema();
    boolean isExactSchema = result.isExactSchema();

    String showProcedureCommand = "show /* JDBC:DatabaseMetaData.getProcedures() */ " + type;

    if (name != null && !name.isEmpty() && !name.trim().equals("%") && !name.trim().equals(".*")) {
      showProcedureCommand += " like '" + escapeSingleQuoteForLikeCommand(name) + "'";
    }

    if (catalog == null) {
      showProcedureCommand += " in account";
    } else if (catalog.isEmpty()) {
      return "";
    } else {
      String catalogEscaped = escapeSqlQuotes(catalog);
      if (!isExactSchema && (schemaPattern == null || isSchemaNameWildcardPattern(schemaPattern))) {
        showProcedureCommand += " in database \"" + catalogEscaped + "\"";
      } else if (schemaPattern.isEmpty()) {
        return "";
      } else {
        schemaPattern = unescapeChars(schemaPattern);
        showProcedureCommand += " in schema \"" + catalogEscaped + "\".\"" + schemaPattern + "\"";
      }
    }
    logger.debug("Sql command to get column metadata: {}", showProcedureCommand);

    return showProcedureCommand;
  }

  /* another helper function for getProcedures, getFunctionColumns, etc. Returns sql command that describes
  procedures or functions */
  private String getSecondResultSetCommand(
      String catalog, String schemaPattern, String name, String type) {
    if (isNullOrEmpty(name)) {
      return "";
    }
    String procedureCols = name.substring(name.indexOf("("), name.indexOf(" RETURN"));
    String quotedName = "\"" + name.substring(0, name.indexOf("(")) + "\"";
    String procedureName = quotedName + procedureCols;
    String showProcedureColCommand;
    if (!isNullOrEmpty(catalog) && !isNullOrEmpty(schemaPattern)) {
      showProcedureColCommand =
          "desc " + type + " " + catalog + "." + schemaPattern + "." + procedureName;
    } else if (!isNullOrEmpty(schemaPattern)) {
      showProcedureColCommand = "desc " + type + " " + schemaPattern + "." + procedureName;
    } else {
      showProcedureColCommand = "desc " + type + " " + procedureName;
    }
    return showProcedureColCommand;
  }

  @Override
  public ResultSet getTables(
      String originalCatalog,
      String originalSchemaPattern,
      final String tableNamePattern,
      final String[] types)
      throws SQLException {
    logger.trace(
        "public ResultSet getTables(String catalog={}, String "
            + "schemaPattern={}, String tableNamePattern={}, String[] types={})",
        originalCatalog,
        originalSchemaPattern,
        tableNamePattern,
        (ArgSupplier) () -> Arrays.toString(types));

    raiseSQLExceptionIfConnectionIsClosed();

    Set<String> supportedTableTypes = new HashSet<>();
    ResultSet resultSet = getTableTypes();
    while (resultSet.next()) {
      supportedTableTypes.add(resultSet.getString("TABLE_TYPE"));
    }
    resultSet.close();

    List<String> inputValidTableTypes = new ArrayList<>();
    // then filter on the input table types;
    if (types != null) {
      for (String t : types) {
        if (supportedTableTypes.contains(t)) {
          inputValidTableTypes.add(t);
        }
      }
    } else {
      inputValidTableTypes = new ArrayList<String>(supportedTableTypes);
    }

    // if the input table types don't have types supported by Snowflake,
    // then return an empty result set directly
    Statement statement = connection.createStatement();
    if (inputValidTableTypes.size() == 0) {
      return SnowflakeDatabaseMetaDataResultSet.getEmptyResultSet(GET_TABLES, statement);
    }

    ContextAwareMetadataSearch result = applySessionContext(originalCatalog, originalSchemaPattern);
    String catalog = result.database();
    String schemaPattern = result.schema();
    boolean isExactSchema = result.isExactSchema();

    final Pattern compiledSchemaPattern = Wildcard.toRegexPattern(schemaPattern, true);
    final Pattern compiledTablePattern = Wildcard.toRegexPattern(tableNamePattern, true);

    String showTablesCommand = null;
    final boolean viewOnly =
        inputValidTableTypes.size() == 1 && "VIEW".equalsIgnoreCase(inputValidTableTypes.get(0));
    final boolean tableOnly =
        inputValidTableTypes.size() == 1 && "TABLE".equalsIgnoreCase(inputValidTableTypes.get(0));
    if (viewOnly) {
      showTablesCommand = "show /* JDBC:DatabaseMetaData.getTables() */ views";
    } else if (tableOnly) {
      showTablesCommand = "show /* JDBC:DatabaseMetaData.getTables() */ tables";
    } else {
      showTablesCommand = "show /* JDBC:DatabaseMetaData.getTables() */ objects";
    }

    // only add pattern if it is not empty and not matching all character.
    if (tableNamePattern != null
        && !tableNamePattern.isEmpty()
        && !tableNamePattern.trim().equals("%")
        && !tableNamePattern.trim().equals(".*")) {
      showTablesCommand += " like '" + escapeSingleQuoteForLikeCommand(tableNamePattern) + "'";
    }

    if (catalog == null) {
      showTablesCommand += " in account";
    } else if (catalog.isEmpty()) {
      return SnowflakeDatabaseMetaDataResultSet.getEmptyResultSet(GET_TABLES, statement);
    } else {
      String catalogEscaped = escapeSqlQuotes(catalog);
      // if the schema pattern is a deterministic identifier, specify schema
      // in the show command. This is necessary for us to see any tables in
      // a schema if the current schema a user is connected to is different
      // given that we don't support show tables without a known schema.
      if (schemaPattern == null || isSchemaNameWildcardPattern(schemaPattern)) {
        showTablesCommand += " in database \"" + catalogEscaped + "\"";
      } else if (schemaPattern.isEmpty()) {
        return SnowflakeDatabaseMetaDataResultSet.getEmptyResultSet(GET_TABLES, statement);
      } else {
        String schemaUnescaped = isExactSchema ? schemaPattern : unescapeChars(schemaPattern);
        showTablesCommand += " in schema \"" + catalogEscaped + "\".\"" + schemaUnescaped + "\"";
      }
    }

    logger.debug("Sql command to get table metadata: {}", showTablesCommand);

    resultSet = executeAndReturnEmptyResultIfNotFound(statement, showTablesCommand, GET_TABLES);
    sendInBandTelemetryMetadataMetrics(
        resultSet,
        "getTables",
        originalCatalog,
        originalSchemaPattern,
        tableNamePattern,
        Arrays.toString(types));

    return new SnowflakeDatabaseMetaDataQueryResultSet(GET_TABLES, resultSet, statement) {
      @Override
      public boolean next() throws SQLException {
        logger.trace("boolean next()", false);
        incrementRow();

        // iterate throw the show table result until we find an entry
        // that matches the table name
        while (showObjectResultSet.next()) {
          String tableName = showObjectResultSet.getString(2);

          String dbName;
          String schemaName;
          String kind;
          String comment;

          if (viewOnly) {
            dbName = showObjectResultSet.getString(4);
            schemaName = showObjectResultSet.getString(5);
            kind = "VIEW";
            comment = showObjectResultSet.getString(7);
          } else {
            dbName = showObjectResultSet.getString(3);
            schemaName = showObjectResultSet.getString(4);
            kind = showObjectResultSet.getString(5);
            comment = showObjectResultSet.getString(6);
          }

          if ((compiledTablePattern == null || compiledTablePattern.matcher(tableName).matches())
              && (compiledSchemaPattern == null
                  || compiledSchemaPattern.matcher(schemaName).matches())) {
            nextRow[0] = dbName;
            nextRow[1] = schemaName;
            nextRow[2] = tableName;
            nextRow[3] = kind;
            nextRow[4] = comment;
            nextRow[5] = null;
            nextRow[6] = null;
            nextRow[7] = null;
            nextRow[8] = null;
            nextRow[9] = null;
            return true;
          }
        }

        close();
        return false;
      }
    };
  }

  @Override
  public ResultSet getSchemas() throws SQLException {
    logger.trace("ResultSet getSchemas()", false);

    return getSchemas(null, null);
  }

  @Override
  public ResultSet getCatalogs() throws SQLException {
    logger.trace("ResultSet getCatalogs()", false);
    raiseSQLExceptionIfConnectionIsClosed();

    String showDB = "show /* JDBC:DatabaseMetaData.getCatalogs() */ databases in account";

    Statement statement = connection.createStatement();
    return new SnowflakeDatabaseMetaDataQueryResultSet(
        GET_CATALOGS, statement.executeQuery(showDB), statement) {
      @Override
      public boolean next() throws SQLException {
        logger.trace("boolean next()", false);
        incrementRow();

        // iterate throw the show databases result
        if (showObjectResultSet.next()) {
          String dbName = showObjectResultSet.getString(2);

          nextRow[0] = dbName;
          return true;
        }
        close();
        return false;
      }
    };
  }

  @Override
  public ResultSet getTableTypes() throws SQLException {
    logger.trace("ResultSet getTableTypes()", false);
    raiseSQLExceptionIfConnectionIsClosed();
    Statement statement = connection.createStatement();

    // TODO: We should really get the list of table types from GS
    return new SnowflakeDatabaseMetaDataResultSet(
        Collections.singletonList("TABLE_TYPE"),
        Collections.singletonList("TEXT"),
        Collections.singletonList(Types.VARCHAR),
        new Object[][] {{"TABLE"}, {"VIEW"}},
        statement);
  }

  @Override
  public ResultSet getColumns(
      String catalog,
      String schemaPattern,
      final String tableNamePattern,
      final String columnNamePattern)
      throws SQLException {
    return getColumns(catalog, schemaPattern, tableNamePattern, columnNamePattern, false);
  }

  public ResultSet getColumns(
      String originalCatalog,
      String originalSchemaPattern,
      final String tableNamePattern,
      final String columnNamePattern,
      final boolean extendedSet)
      throws SQLException {
    logger.trace(
        "public ResultSet getColumns(String catalog={}, String schemaPattern={}, "
            + "String tableNamePattern={}, String columnNamePattern={}, boolean extendedSet={}",
        originalCatalog,
        originalSchemaPattern,
        tableNamePattern,
        columnNamePattern,
        extendedSet);
    raiseSQLExceptionIfConnectionIsClosed();
    Statement statement = connection.createStatement();

    // apply session context when catalog is unspecified
    ContextAwareMetadataSearch result = applySessionContext(originalCatalog, originalSchemaPattern);
    String catalog = result.database();
    String schemaPattern = result.schema();
    boolean isExactSchema = result.isExactSchema();

    final Pattern compiledSchemaPattern = Wildcard.toRegexPattern(schemaPattern, true);
    final Pattern compiledTablePattern = Wildcard.toRegexPattern(tableNamePattern, true);
    final Pattern compiledColumnPattern = Wildcard.toRegexPattern(columnNamePattern, true);

    String showColumnsCommand = "show /* JDBC:DatabaseMetaData.getColumns() */ columns";

    if (columnNamePattern != null
        && !columnNamePattern.isEmpty()
        && !columnNamePattern.trim().equals("%")
        && !columnNamePattern.trim().equals(".*")) {
      showColumnsCommand += " like '" + escapeSingleQuoteForLikeCommand(columnNamePattern) + "'";
    }

    if (catalog == null) {
      showColumnsCommand += " in account";
    } else if (catalog.isEmpty()) {
      return SnowflakeDatabaseMetaDataResultSet.getEmptyResultSet(
          extendedSet ? GET_COLUMNS_EXTENDED_SET : GET_COLUMNS, statement);
    } else {
      String catalogEscaped = escapeSqlQuotes(catalog);
      if (schemaPattern == null || isSchemaNameWildcardPattern(schemaPattern)) {
        showColumnsCommand += " in database \"" + catalogEscaped + "\"";
      } else if (schemaPattern.isEmpty()) {
        return SnowflakeDatabaseMetaDataResultSet.getEmptyResultSet(
            extendedSet ? GET_COLUMNS_EXTENDED_SET : GET_COLUMNS, statement);
      } else {
        String schemaUnescaped = isExactSchema ? schemaPattern : unescapeChars(schemaPattern);
        if (tableNamePattern == null
            || (Wildcard.isWildcardPatternStr(tableNamePattern)
                && enableWildcardsInShowMetadataCommands)) {
          showColumnsCommand += " in schema \"" + catalogEscaped + "\".\"" + schemaUnescaped + "\"";
        } else if (tableNamePattern.isEmpty()) {
          return SnowflakeDatabaseMetaDataResultSet.getEmptyResultSet(
              extendedSet ? GET_COLUMNS_EXTENDED_SET : GET_COLUMNS, statement);
        } else {
          String tableNameUnescaped = unescapeChars(tableNamePattern);
          showColumnsCommand +=
              " in table \""
                  + catalogEscaped
                  + "\".\""
                  + schemaUnescaped
                  + "\".\""
                  + tableNameUnescaped
                  + "\"";
        }
      }
    }

    logger.debug("Sql command to get column metadata: {}", showColumnsCommand);

    ResultSet resultSet =
        executeAndReturnEmptyResultIfNotFound(
            statement, showColumnsCommand, extendedSet ? GET_COLUMNS_EXTENDED_SET : GET_COLUMNS);
    sendInBandTelemetryMetadataMetrics(
        resultSet,
        "getColumns",
        originalCatalog,
        originalSchemaPattern,
        tableNamePattern,
        columnNamePattern);

    return new SnowflakeDatabaseMetaDataQueryResultSet(
        extendedSet ? GET_COLUMNS_EXTENDED_SET : GET_COLUMNS, resultSet, statement) {
      int ordinalPosition = 0;

      String currentTableName = null;

      public boolean next() throws SQLException {
        logger.trace("boolean next()", false);
        incrementRow();

        // iterate throw the show table result until we find an entry
        // that matches the table name
        while (showObjectResultSet.next()) {
          String tableName = showObjectResultSet.getString(1);
          String schemaName = showObjectResultSet.getString(2);
          String columnName = showObjectResultSet.getString(3);
          String dataTypeStr = showObjectResultSet.getString(4);
          String defaultValue = showObjectResultSet.getString(6);
          defaultValue.trim();
          if (defaultValue.isEmpty()) {
            defaultValue = null;
          } else if (!stringsQuoted) {
            if (defaultValue.startsWith("\'") && defaultValue.endsWith("\'")) {
              // remove extra set of single quotes
              defaultValue = defaultValue.substring(1, defaultValue.length() - 1);
              // scan for 2 single quotes in a row and remove one of them
              defaultValue = defaultValue.replace("''", "'");
            }
          }
          String comment = showObjectResultSet.getString(9);
          String catalogName = showObjectResultSet.getString(10);
          String autoIncrement = showObjectResultSet.getString(11);

          if ((compiledTablePattern == null || compiledTablePattern.matcher(tableName).matches())
              && (compiledSchemaPattern == null
                  || compiledSchemaPattern.matcher(schemaName).matches())
              && (compiledColumnPattern == null
                  || compiledColumnPattern.matcher(columnName).matches())) {
            logger.debug("Found a matched column:" + tableName + "." + columnName);

            // reset ordinal position for new table
            if (!tableName.equals(currentTableName)) {
              ordinalPosition = 1;
              currentTableName = tableName;
            } else {
              ordinalPosition++;
            }

            JsonNode jsonNode;
            try {
              jsonNode = mapper.readTree(dataTypeStr);
            } catch (Exception ex) {
              logger.error("Exception when parsing column" + " result", ex);

              throw new SnowflakeSQLException(
                  SqlState.INTERNAL_ERROR,
                  ErrorCode.INTERNAL_ERROR.getMessageCode(),
                  "error parsing data type: " + dataTypeStr);
            }

            logger.debug("Data type string: {}", dataTypeStr);

            SnowflakeColumnMetadata columnMetadata =
                new SnowflakeColumnMetadata(jsonNode, session.isJdbcTreatDecimalAsInt(), session);

            logger.debug("Nullable: {}", columnMetadata.isNullable());

            // SNOW-16881: add catalog name
            nextRow[0] = catalogName;
            nextRow[1] = schemaName;
            nextRow[2] = tableName;
            nextRow[3] = columnName;

            int internalColumnType = columnMetadata.getType();
            int externalColumnType = internalColumnType;

            if (internalColumnType == SnowflakeUtil.EXTRA_TYPES_TIMESTAMP_LTZ) {
              externalColumnType = Types.TIMESTAMP;
            }
            if (internalColumnType == SnowflakeUtil.EXTRA_TYPES_TIMESTAMP_TZ) {

              externalColumnType =
                  session == null
                      ? Types.TIMESTAMP_WITH_TIMEZONE
                      : session.getEnableReturnTimestampWithTimeZone()
                          ? Types.TIMESTAMP_WITH_TIMEZONE
                          : Types.TIMESTAMP;
            }

            nextRow[4] = externalColumnType;
            nextRow[5] = columnMetadata.getTypeName();

            int columnSize = 0;

            // The COLUMN_SIZE column specifies the column size for the given
            // column. For numeric data, this is the maximum precision. For
            // character data, this is the length in characters. For datetime
            // datatypes, this is the length in characters of the String
            // representation (assuming the maximum allowed precision of the
            // fractional seconds component). For binary data, this is the
            // length in bytes. For the ROWID datatype, this is the length in
            // bytes. Null is returned for data types where the column size
            // is not applicable.
            if (columnMetadata.getType() == Types.VARCHAR
                || columnMetadata.getType() == Types.CHAR
                || columnMetadata.getType() == Types.BINARY) {
              columnSize = columnMetadata.getLength();
            } else if (columnMetadata.getType() == Types.DECIMAL
                || columnMetadata.getType() == Types.BIGINT
                || columnMetadata.getType() == Types.TIME
                || columnMetadata.getType() == Types.TIMESTAMP) {
              columnSize = columnMetadata.getPrecision();
            } else if (columnMetadata.getType() == SnowflakeUtil.EXTRA_TYPES_VECTOR) {
              // For VECTOR Snowflake type we consider dimension as the column size
              columnSize = columnMetadata.getDimension();
            }

            nextRow[6] = columnSize;
            nextRow[7] = null;
            nextRow[8] = columnMetadata.getScale();
            nextRow[9] = null;
            nextRow[10] = (columnMetadata.isNullable() ? columnNullable : columnNoNulls);

            logger.debug("Returning nullable: {}", nextRow[10]);

            nextRow[11] = comment;
            nextRow[12] = defaultValue;
            // snow-10597: sql data type is integer instead of string
            nextRow[13] = externalColumnType;
            nextRow[14] = null;
            nextRow[15] =
                (columnMetadata.getType() == Types.VARCHAR
                        || columnMetadata.getType() == Types.CHAR)
                    ? columnMetadata.getLength()
                    : null;
            nextRow[16] = ordinalPosition;

            nextRow[17] = (columnMetadata.isNullable() ? "YES" : "NO");
            nextRow[18] = null;
            nextRow[19] = null;
            nextRow[20] = null;
            nextRow[21] = null;
            nextRow[22] = "".equals(autoIncrement) ? "NO" : "YES";
            nextRow[23] = "NO";
            if (extendedSet) {
              nextRow[24] = columnMetadata.getBase().name();
            }
            return true;
          }
        }
        close();
        return false;
      }
    };
  }

  @Override
  public ResultSet getColumnPrivileges(
      String catalog, String schema, String table, String columnNamePattern) throws SQLException {
    logger.trace(
        "public ResultSet getColumnPrivileges(String catalog, "
            + "String schema,String table, String columnNamePattern)",
        false);
    raiseSQLExceptionIfConnectionIsClosed();

    Statement statement = connection.createStatement();
    return new SnowflakeDatabaseMetaDataResultSet(
        Arrays.asList(
            "TABLE_CAT",
            "TABLE_SCHEM",
            "TABLE_NAME",
            "COLUMN_NAME",
            "GRANTOR",
            "GRANTEE",
            "PRIVILEGE",
            "IS_GRANTABLE"),
        Arrays.asList("TEXT", "TEXT", "TEXT", "TEXT", "TEXT", "TEXT", "TEXT", "TEXT"),
        Arrays.asList(
            Types.VARCHAR,
            Types.VARCHAR,
            Types.VARCHAR,
            Types.VARCHAR,
            Types.VARCHAR,
            Types.VARCHAR,
            Types.VARCHAR,
            Types.VARCHAR),
        new Object[][] {},
        statement);
  }

  @Override
  public ResultSet getTablePrivileges(
      String originalCatalog, String originalSchemaPattern, final String tableNamePattern)
      throws SQLException {
    logger.trace(
        "public ResultSet getTablePrivileges(String catalog, "
            + "String schemaPattern,String tableNamePattern)",
        false);
    raiseSQLExceptionIfConnectionIsClosed();

    Statement statement = connection.createStatement();

    if (tableNamePattern == null) {
      return SnowflakeDatabaseMetaDataResultSet.getEmptyResultSet(GET_TABLE_PRIVILEGES, statement);
    }
    // apply session context when catalog is unspecified
    ContextAwareMetadataSearch result = applySessionContext(originalCatalog, originalSchemaPattern);
    String catalog = result.database();
    String schemaPattern = result.schema();
    boolean isExactSchema = result.isExactSchema();

    String showView = "select * from ";

    if (catalog != null
        && !catalog.isEmpty()
        && !catalog.trim().equals("%")
        && !catalog.trim().equals(".*")) {
      showView += "\"" + escapeSqlQuotes(catalog) + "\".";
    }
    showView += "information_schema.table_privileges";

    if (tableNamePattern != null
        && !tableNamePattern.isEmpty()
        && !tableNamePattern.trim().equals("%")
        && !tableNamePattern.trim().equals(".*")) {
      showView += " where table_name = '" + tableNamePattern + "'";
    }

    if (schemaPattern != null
        && !schemaPattern.isEmpty()
        && !schemaPattern.trim().equals("%")
        && !schemaPattern.trim().equals(".*")) {
      String unescapedSchema = isExactSchema ? schemaPattern : unescapeChars(schemaPattern);
      if (showView.contains("where table_name")) {
        showView += " and table_schema = '" + unescapedSchema + "'";
      } else {
        showView += " where table_schema = '" + unescapedSchema + "'";
      }
    }
    showView += " order by table_catalog, table_schema, table_name, privilege_type";

    final String catalogIn = catalog;
    final String schemaIn = schemaPattern;
    final String tableIn = tableNamePattern;

    ResultSet resultSet =
        executeAndReturnEmptyResultIfNotFound(statement, showView, GET_TABLE_PRIVILEGES);
    sendInBandTelemetryMetadataMetrics(
        resultSet,
        "getTablePrivileges",
        originalCatalog,
        originalSchemaPattern,
        tableNamePattern,
        "none");
    return new SnowflakeDatabaseMetaDataQueryResultSet(GET_TABLE_PRIVILEGES, resultSet, statement) {
      @Override
      public boolean next() throws SQLException {
        logger.trace("boolean next()", false);
        incrementRow();

        while (showObjectResultSet.next()) {
          String table_cat = showObjectResultSet.getString("TABLE_CATALOG");
          String table_schema = showObjectResultSet.getString("TABLE_SCHEMA");
          String table_name = showObjectResultSet.getString("TABLE_NAME");
          String grantor = showObjectResultSet.getString("GRANTOR");
          String grantee = showObjectResultSet.getString("GRANTEE");
          String privilege = showObjectResultSet.getString("PRIVILEGE_TYPE");
          String is_grantable = showObjectResultSet.getString("IS_GRANTABLE");

          if ((catalogIn == null
                  || catalogIn.trim().equals("%")
                  || catalogIn.trim().equals(table_cat))
              && (schemaIn == null
                  || schemaIn.trim().equals("%")
                  || schemaIn.trim().equals(table_schema))
              && (tableIn.trim().equals(table_name) || tableIn.trim().equals("%"))) {
            nextRow[0] = table_cat;
            nextRow[1] = table_schema;
            nextRow[2] = table_name;
            nextRow[3] = grantor;
            nextRow[4] = grantee;
            nextRow[5] = privilege;
            nextRow[6] = is_grantable;
            return true;
          }
        }
        close();
        return false;
      }
    };
  }

  @Override
  public ResultSet getBestRowIdentifier(
      String catalog, String schema, String table, int scope, boolean nullable)
      throws SQLException {
    logger.trace(
        "public ResultSet getBestRowIdentifier(String catalog, "
            + "String schema,String table, int scope,boolean nullable)",
        false);
    throw new SnowflakeLoggedFeatureNotSupportedException(session);
  }

  @Override
  public ResultSet getVersionColumns(String catalog, String schema, String table)
      throws SQLException {
    logger.trace(
        "public ResultSet getVersionColumns(String catalog, " + "String schema, String table)",
        false);

    throw new SnowflakeLoggedFeatureNotSupportedException(session);
  }

  @Override
  public ResultSet getPrimaryKeys(String originalCatalog, String originalSchema, final String table)
      throws SQLException {
    logger.trace(
        "public ResultSet getPrimaryKeys(String catalog={}, "
            + "String schema={}, String table={})",
        originalCatalog,
        originalSchema,
        table);
    raiseSQLExceptionIfConnectionIsClosed();
    Statement statement = connection.createStatement();
    String showPKCommand = "show /* JDBC:DatabaseMetaData.getPrimaryKeys() */ primary keys in ";

    // apply session context when catalog is unspecified
    ContextAwareMetadataSearch result = applySessionContext(originalCatalog, originalSchema);
    String catalog = result.database();
    String schema = result.schema();
    boolean isExactSchema = result.isExactSchema();

    // These Patterns will only be used if the connection property enablePatternSearch=true
    final Pattern compiledSchemaPattern = Wildcard.toRegexPattern(schema, true);
    final Pattern compiledTablePattern = Wildcard.toRegexPattern(table, true);

    if (catalog == null) {
      showPKCommand += "account";
    } else if (catalog.isEmpty()) {
      return SnowflakeDatabaseMetaDataResultSet.getEmptyResultSet(GET_PRIMARY_KEYS, statement);
    } else {
      String catalogUnescaped = escapeSqlQuotes(catalog);
      if (schema == null || (isPatternMatchingEnabled && isSchemaNameWildcardPattern(schema))) {
        showPKCommand += "database \"" + catalogUnescaped + "\"";
      } else if (schema.isEmpty()) {
        return SnowflakeDatabaseMetaDataResultSet.getEmptyResultSet(GET_PRIMARY_KEYS, statement);
      } else {
        String schemaUnescaped = isExactSchema ? schema : unescapeChars(schema);
        if (table == null) {
          showPKCommand += "schema \"" + catalogUnescaped + "\".\"" + schemaUnescaped + "\"";
        } else if (table.isEmpty()) {
          return SnowflakeDatabaseMetaDataResultSet.getEmptyResultSet(GET_PRIMARY_KEYS, statement);
        } else {
          String tableUnescaped = unescapeChars(table);
          showPKCommand +=
              "table \""
                  + catalogUnescaped
                  + "\".\""
                  + schemaUnescaped
                  + "\".\""
                  + tableUnescaped
                  + "\"";
        }
      }
    }

    final String catalogIn = catalog;
    // These values for Schema and Table will only be used to filter results if the connection
    // property
    // enablePatternSearch=false
    final String schemaIn = schema;
    final String tableIn = table;

    logger.debug("Sql command to get primary key metadata: {}", showPKCommand);
    ResultSet resultSet =
        executeAndReturnEmptyResultIfNotFound(statement, showPKCommand, GET_PRIMARY_KEYS);
    sendInBandTelemetryMetadataMetrics(
        resultSet, "getPrimaryKeys", originalCatalog, originalSchema, table, "none");
    // Return empty result set since we don't have primary keys yet
    return new SnowflakeDatabaseMetaDataQueryResultSet(GET_PRIMARY_KEYS, resultSet, statement) {
      @Override
      public boolean next() throws SQLException {
        logger.trace("boolean next()", false);
        incrementRow();

        while (showObjectResultSet.next()) {
          // Get the values for each field to display
          String table_cat = showObjectResultSet.getString(2);
          String table_schem = showObjectResultSet.getString(3);
          String table_name = showObjectResultSet.getString(4);
          String column_name = showObjectResultSet.getString(5);
          int key_seq = showObjectResultSet.getInt(6);
          String pk_name = showObjectResultSet.getString(7);
          boolean isMatch = false;

          // Post filter based on the input
          if (isPatternMatchingEnabled) {
            isMatch =
                isPrimaryKeyPatternSearch(
                    table_cat, table_schem, table_name, column_name, key_seq, pk_name);
          } else {
            isMatch =
                isPrimaryKeyExactSearch(
                    table_cat, table_schem, table_name, column_name, key_seq, pk_name);
          }
          if (isMatch) {
            createPrimaryKeyRow(table_cat, table_schem, table_name, column_name, key_seq, pk_name);
            return true;
          }
        }
        close();
        return false;
      }

      private boolean isPrimaryKeyExactSearch(
          String table_cat,
          String table_schem,
          String table_name,
          String column_name,
          int key_seq,
          String pk_name) {
        if ((catalogIn == null || catalogIn.equals(table_cat))
            && (schemaIn == null || schemaIn.equals(table_schem))
            && (tableIn == null || tableIn.equals(table_name))) {
          return true;
        }
        return false;
      }

      private boolean isPrimaryKeyPatternSearch(
          String table_cat,
          String table_schem,
          String table_name,
          String column_name,
          int key_seq,
          String pk_name) {
        if ((catalogIn == null || catalogIn.equals(table_cat))
            && (compiledSchemaPattern == null
                || compiledSchemaPattern.equals(table_schem)
                || compiledSchemaPattern.matcher(table_schem).matches())
            && (compiledTablePattern == null
                || compiledTablePattern.equals(table_name)
                || compiledTablePattern.matcher(table_name).matches())) {
          return true;
        }
        return false;
      }

      private void createPrimaryKeyRow(
          String table_cat,
          String table_schem,
          String table_name,
          String column_name,
          int key_seq,
          String pk_name) {
        nextRow[0] = table_cat;
        nextRow[1] = table_schem;
        nextRow[2] = table_name;
        nextRow[3] = column_name;
        nextRow[4] = key_seq;
        nextRow[5] = pk_name;
      }
    };
  }

  /**
   * Retrieves the foreign keys
   *
   * @param client type of foreign key
   * @param originalParentCatalog database name
   * @param originalParentSchema schema name
   * @param parentTable table name
   * @param foreignCatalog other database name
   * @param foreignSchema other schema name
   * @param foreignTable other table name
   * @return foreign key columns in result set
   */
  private ResultSet getForeignKeys(
      final String client,
      String originalParentCatalog,
      String originalParentSchema,
      final String parentTable,
      final String foreignCatalog,
      final String foreignSchema,
      final String foreignTable)
      throws SQLException {
    raiseSQLExceptionIfConnectionIsClosed();
    Statement statement = connection.createStatement();
    StringBuilder commandBuilder = new StringBuilder();

    // apply session context when catalog is unspecified
    ContextAwareMetadataSearch result =
        applySessionContext(originalParentCatalog, originalParentSchema);
    String parentCatalog = result.database();
    String parentSchema = result.schema();
    boolean isExactSchema = result.isExactSchema();

    // These Patterns will only be used to filter results if the connection property
    // enablePatternSearch=true
    final Pattern compiledSchemaPattern = Wildcard.toRegexPattern(parentSchema, true);
    final Pattern compiledParentTablePattern = Wildcard.toRegexPattern(parentTable, true);
    final Pattern compiledForeignSchemaPattern = Wildcard.toRegexPattern(foreignSchema, true);
    final Pattern compiledForeignTablePattern = Wildcard.toRegexPattern(foreignTable, true);

    if (client.equals("export") || client.equals("cross")) {
      commandBuilder.append(
          "show /* JDBC:DatabaseMetaData.getForeignKeys() */ " + "exported keys in ");
    } else if (client.equals("import")) {
      commandBuilder.append(
          "show /* JDBC:DatabaseMetaData.getForeignKeys() */ " + "imported keys in ");
    }

    if (parentCatalog == null) {
      commandBuilder.append("account");
    } else if (parentCatalog.isEmpty()) {
      return SnowflakeDatabaseMetaDataResultSet.getEmptyResultSet(GET_FOREIGN_KEYS, statement);
    } else {
      String unescapedParentCatalog = escapeSqlQuotes(parentCatalog);
      if (parentSchema == null
          || (isPatternMatchingEnabled && isSchemaNameWildcardPattern(parentSchema))) {
        commandBuilder.append("database \"" + unescapedParentCatalog + "\"");
      } else if (parentSchema.isEmpty()) {
        return SnowflakeDatabaseMetaDataResultSet.getEmptyResultSet(GET_FOREIGN_KEYS, statement);
      } else {
        String unescapedParentSchema = isExactSchema ? parentSchema : unescapeChars(parentSchema);
        if (parentTable == null) {
          commandBuilder.append(
              "schema \"" + unescapedParentCatalog + "\".\"" + unescapedParentSchema + "\"");
        } else if (parentTable.isEmpty()) {
          return SnowflakeDatabaseMetaDataResultSet.getEmptyResultSet(GET_FOREIGN_KEYS, statement);
        } else {
          String unescapedParentTable = unescapeChars(parentTable);
          commandBuilder.append(
              "table \""
                  + unescapedParentCatalog
                  + "\".\""
                  + unescapedParentSchema
                  + "\".\""
                  + unescapedParentTable
                  + "\"");
        }
      }
    }

    final String finalParentCatalog = parentCatalog;
    final String finalForeignCatalog = foreignCatalog;
    // These values will only be used to filter results if the connection property
    // enablePatternSearch=true
    final String finalParentSchema = parentSchema;
    final String finalParentTable = parentTable;
    final String finalForeignSchema = foreignSchema;
    final String finalForeignTable = foreignTable;

    String command = commandBuilder.toString();

    ResultSet resultSet =
        executeAndReturnEmptyResultIfNotFound(statement, command, GET_FOREIGN_KEYS);
    sendInBandTelemetryMetadataMetrics(
        resultSet,
        "getForeignKeys",
        originalParentCatalog,
        originalParentSchema,
        parentTable,
        "none");

    return new SnowflakeDatabaseMetaDataQueryResultSet(GET_FOREIGN_KEYS, resultSet, statement) {
      @Override
      public boolean next() throws SQLException {
        logger.trace("boolean next()", false);
        incrementRow();

        while (showObjectResultSet.next()) {
          // Get the value for each field to display
          String pktable_cat = showObjectResultSet.getString(2);
          String pktable_schem = showObjectResultSet.getString(3);
          String pktable_name = showObjectResultSet.getString(4);
          String pkcolumn_name = showObjectResultSet.getString(5);
          String fktable_cat = showObjectResultSet.getString(6);
          String fktable_schem = showObjectResultSet.getString(7);
          String fktable_name = showObjectResultSet.getString(8);
          String fkcolumn_name = showObjectResultSet.getString(9);
          int key_seq = showObjectResultSet.getInt(10);
          short updateRule =
              getForeignKeyConstraintProperty("update", showObjectResultSet.getString(11));
          short deleteRule =
              getForeignKeyConstraintProperty("delete", showObjectResultSet.getString(12));
          String fk_name = showObjectResultSet.getString(13);
          String pk_name = showObjectResultSet.getString(14);
          short deferrability =
              getForeignKeyConstraintProperty("deferrability", showObjectResultSet.getString(15));

          boolean passedFilter = false;

          if (isPatternMatchingEnabled) {
            passedFilter =
                isForeignKeyPatternMatch(
                    fktable_cat,
                    fktable_schem,
                    fktable_name,
                    passedFilter,
                    pktable_cat,
                    pktable_schem,
                    pktable_name);
          } else {
            passedFilter =
                isForeignKeyExactMatch(
                    fktable_cat,
                    fktable_schem,
                    fktable_name,
                    passedFilter,
                    pktable_cat,
                    pktable_schem,
                    pktable_name);
          }

          if (passedFilter) {
            createForeinKeyRow(
                pktable_cat,
                pktable_schem,
                pktable_name,
                pkcolumn_name,
                fktable_cat,
                fktable_schem,
                fktable_name,
                fkcolumn_name,
                key_seq,
                updateRule,
                deleteRule,
                fk_name,
                pk_name,
                deferrability);
            return true;
          }
        }
        close();
        return false;
      }

      private void createForeinKeyRow(
          String pktable_cat,
          String pktable_schem,
          String pktable_name,
          String pkcolumn_name,
          String fktable_cat,
          String fktable_schem,
          String fktable_name,
          String fkcolumn_name,
          int key_seq,
          short updateRule,
          short deleteRule,
          String fk_name,
          String pk_name,
          short deferrability) {
        nextRow[0] = pktable_cat;
        nextRow[1] = pktable_schem;
        nextRow[2] = pktable_name;
        nextRow[3] = pkcolumn_name;
        nextRow[4] = fktable_cat;
        nextRow[5] = fktable_schem;
        nextRow[6] = fktable_name;
        nextRow[7] = fkcolumn_name;
        nextRow[8] = key_seq;
        nextRow[9] = updateRule;
        nextRow[10] = deleteRule;
        nextRow[11] = fk_name;
        nextRow[12] = pk_name;
        nextRow[13] = deferrability;
      }

      private boolean isForeignKeyExactMatch(
          String fktable_cat,
          String fktable_schem,
          String fktable_name,
          boolean passedFilter,
          String pktable_cat,
          String pktable_schem,
          String pktable_name) {
        // Post filter the results based on the client type
        if (client.equals("import")) {
          // For imported keys, filter on the foreign key table
          if ((finalParentCatalog == null || finalParentCatalog.equals(fktable_cat))
              && (finalParentSchema == null || finalParentSchema.equals(fktable_schem))
              && (finalParentTable == null || finalParentTable.equals(fktable_name))) {
            passedFilter = true;
          }
        } else if (client.equals("export")) {
          // For exported keys, filter on the primary key table
          if ((finalParentCatalog == null || finalParentCatalog.equals(pktable_cat))
              && (finalParentSchema == null || finalParentSchema.equals(pktable_schem))
              && (finalParentTable == null || finalParentTable.equals(pktable_name))) {
            passedFilter = true;
          }
        } else if (client.equals("cross")) {
          // For cross references, filter on both the primary key and foreign
          // key table
          if ((finalParentCatalog == null || finalParentCatalog.equals(pktable_cat))
              && (finalParentSchema == null || finalParentSchema.equals(pktable_schem))
              && (finalParentTable == null || finalParentTable.equals(pktable_name))
              && (finalForeignCatalog == null || finalForeignCatalog.equals(fktable_cat))
              && (finalForeignSchema == null || finalForeignSchema.equals(fktable_schem))
              && (finalForeignTable == null || finalForeignTable.equals(fktable_name))) {
            passedFilter = true;
          }
        }
        return passedFilter;
      }

      private boolean isForeignKeyPatternMatch(
          String fktable_cat,
          String fktable_schem,
          String fktable_name,
          boolean passedFilter,
          String pktable_cat,
          String pktable_schem,
          String pktable_name) {
        // Post filter the results based on the client type
        if (client.equals("import")) {
          // For imported keys, filter on the foreign key table
          if ((finalParentCatalog == null || finalParentCatalog.equals(fktable_cat))
              && (compiledSchemaPattern == null
                  || compiledSchemaPattern.equals(fktable_schem)
                  || compiledSchemaPattern.matcher(fktable_schem).matches())
              && (compiledParentTablePattern == null
                  || compiledParentTablePattern.equals(fktable_name)
                  || compiledParentTablePattern.matcher(fktable_name).matches())) {
            passedFilter = true;
          }
        } else if (client.equals("export")) {
          // For exported keys, filter on the primary key table
          if ((finalParentCatalog == null || finalParentCatalog.equals(pktable_cat))
              && (compiledSchemaPattern == null
                  || compiledSchemaPattern.equals(pktable_schem)
                  || compiledSchemaPattern.matcher(pktable_schem).matches())
              && (compiledParentTablePattern == null
                  || compiledParentTablePattern.equals(pktable_name)
                  || compiledParentTablePattern.matcher(pktable_name).matches())) {
            passedFilter = true;
          }
        } else if (client.equals("cross")) {
          // For cross references, filter on both the primary key and foreign
          // key table
          if ((finalParentCatalog == null || finalParentCatalog.equals(pktable_cat))
              && (compiledSchemaPattern == null
                  || compiledSchemaPattern.equals(pktable_schem)
                  || compiledSchemaPattern.matcher(pktable_schem).matches())
              && (compiledParentTablePattern == null
                  || compiledParentTablePattern.equals(pktable_name)
                  || compiledParentTablePattern.matcher(pktable_name).matches())
              && (foreignCatalog == null || foreignCatalog.equals(fktable_cat))
              && (compiledForeignSchemaPattern == null
                  || compiledForeignSchemaPattern.equals(fktable_schem)
                  || compiledForeignSchemaPattern.matcher(fktable_schem).matches())
              && (compiledForeignTablePattern == null
                  || compiledForeignTablePattern.equals(fktable_name)
                  || compiledForeignTablePattern.matcher(fktable_name).matches())) {
            passedFilter = true;
          }
        }
        return passedFilter;
      }
    };
  }

  /**
   * Returns the JDBC standard property string for the property string used in our show constraint
   * commands
   *
   * @param property_name operation type
   * @param property property value
   * @return metadata property value
   */
  private short getForeignKeyConstraintProperty(String property_name, String property) {
    short result = 0;
    switch (property_name) {
      case "update":
      case "delete":
        switch (property) {
          case "NO ACTION":
            result = importedKeyNoAction;
            break;
          case "CASCADE":
            result = importedKeyCascade;
            break;
          case "SET NULL":
            result = importedKeySetNull;
            break;
          case "SET DEFAULT":
            result = importedKeySetDefault;
            break;
          case "RESTRICT":
            result = importedKeyRestrict;
            break;
        }
        break;
      case "deferrability":
        switch (property) {
          case "INITIALLY DEFERRED":
            result = importedKeyInitiallyDeferred;
            break;
          case "INITIALLY IMMEDIATE":
            result = importedKeyInitiallyImmediate;
            break;
          case "NOT DEFERRABLE":
            result = importedKeyNotDeferrable;
            break;
        }
        break;
    }
    return result;
  }

  @Override
  public ResultSet getImportedKeys(String originalCatalog, String originalSchema, String table)
      throws SQLException {
    logger.trace(
        "public ResultSet getImportedKeys(String catalog={}, "
            + "String schema={}, String table={})",
        originalCatalog,
        originalSchema,
        table);

    return getForeignKeys("import", originalCatalog, originalSchema, table, null, null, null);
  }

  @Override
  public ResultSet getExportedKeys(String catalog, String schema, String table)
      throws SQLException {
    logger.trace(
        "public ResultSet getExportedKeys(String catalog={}, "
            + "String schema={}, String table={})",
        catalog,
        schema,
        table);

    return getForeignKeys("export", catalog, schema, table, null, null, null);
  }

  @Override
  public ResultSet getCrossReference(
      String parentCatalog,
      String parentSchema,
      String parentTable,
      String foreignCatalog,
      String foreignSchema,
      String foreignTable)
      throws SQLException {
    logger.trace(
        "public ResultSet getCrossReference(String parentCatalog={}, "
            + "String parentSchema={}, String parentTable={}, "
            + "String foreignCatalog={}, String foreignSchema={}, "
            + "String foreignTable={})",
        parentCatalog,
        parentSchema,
        parentTable,
        foreignCatalog,
        foreignSchema,
        foreignTable);
    return getForeignKeys(
        "cross",
        parentCatalog,
        parentSchema,
        parentTable,
        foreignCatalog,
        foreignSchema,
        foreignTable);
  }

  @Override
  public ResultSet getTypeInfo() throws SQLException {
    logger.trace("ResultSet getTypeInfo()", false);
    raiseSQLExceptionIfConnectionIsClosed();

    Statement statement = connection.createStatement();

    // Return empty result set since we don't have primary keys yet
    return new SnowflakeDatabaseMetaDataResultSet(
        Arrays.asList(
            "TYPE_NAME",
            "DATA_TYPE",
            "PRECISION",
            "LITERAL_PREFIX",
            "LITERAL_SUFFIX",
            "CREATE_PARAMS",
            "NULLABLE",
            "CASE_SENSITIVE",
            "SEARCHABLE",
            "UNSIGNED_ATTRIBUTE",
            "FIXED_PREC_SCALE",
            "AUTO_INCREMENT",
            "LOCAL_TYPE_NAME",
            "MINIMUM_SCALE",
            "MAXIMUM_SCALE",
            "SQL_DATA_TYPE",
            "SQL_DATETIME_SUB",
            "NUM_PREC_RADIX"),
        Arrays.asList(
            "TEXT", "INTEGER", "INTEGER", "TEXT", "TEXT", "TEXT", "SHORT", "BOOLEAN", "SHORT",
            "BOOLEAN", "BOOLEAN", "BOOLEAN", "TEXT", "SHORT", "SHORT", "INTEGER", "INTEGER",
            "INTEGER"),
        Arrays.asList(
            Types.VARCHAR,
            Types.INTEGER,
            Types.INTEGER,
            Types.VARCHAR,
            Types.VARCHAR,
            Types.VARCHAR,
            Types.SMALLINT,
            Types.BOOLEAN,
            Types.SMALLINT,
            Types.BOOLEAN,
            Types.BOOLEAN,
            Types.BOOLEAN,
            Types.VARCHAR,
            Types.SMALLINT,
            Types.SMALLINT,
            Types.INTEGER,
            Types.INTEGER,
            Types.INTEGER),
        new Object[][] {
          {
            "NUMBER",
            Types.DECIMAL,
            38,
            null,
            null,
            null,
            typeNullable,
            false,
            typeSearchable,
            false,
            true,
            true,
            null,
            0,
            37,
            -1,
            -1,
            -1
          },
          {
            "INTEGER",
            Types.INTEGER,
            38,
            null,
            null,
            null,
            typeNullable,
            false,
            typeSearchable,
            false,
            true,
            true,
            null,
            0,
            0,
            -1,
            -1,
            -1
          },
          {
            "DOUBLE",
            Types.DOUBLE,
            38,
            null,
            null,
            null,
            typeNullable,
            false,
            typeSearchable,
            false,
            true,
            true,
            null,
            0,
            37,
            -1,
            -1,
            -1
          },
          {
            "VARCHAR",
            Types.VARCHAR,
            -1,
            null,
            null,
            null,
            typeNullable,
            false,
            typeSearchable,
            false,
            true,
            true,
            null,
            -1,
            -1,
            -1,
            -1,
            -1
          },
          {
            "DATE",
            Types.DATE,
            -1,
            null,
            null,
            null,
            typeNullable,
            false,
            typeSearchable,
            false,
            true,
            true,
            null,
            -1,
            -1,
            -1,
            -1,
            -1
          },
          {
            "TIME",
            Types.TIME,
            -1,
            null,
            null,
            null,
            typeNullable,
            false,
            typeSearchable,
            false,
            true,
            true,
            null,
            -1,
            -1,
            -1,
            -1,
            -1
          },
          {
            "TIMESTAMP",
            Types.TIMESTAMP,
            -1,
            null,
            null,
            null,
            typeNullable,
            false,
            typeSearchable,
            false,
            true,
            true,
            null,
            -1,
            -1,
            -1,
            -1,
            -1
          },
          {
            "BOOLEAN",
            Types.BOOLEAN,
            -1,
            null,
            null,
            null,
            typeNullable,
            false,
            typeSearchable,
            false,
            true,
            true,
            null,
            -1,
            -1,
            -1,
            -1,
            -1
          }
        },
        statement);
  }

  /**
   * Function to return a list of streams
   *
   * @param originalCatalog catalog name
   * @param originalSchemaPattern schema name pattern
   * @param streamName stream name
   * @return a result set
   * @throws SQLException if any SQL error occurs.
   */
  public ResultSet getStreams(
      String originalCatalog, String originalSchemaPattern, String streamName) throws SQLException {
    logger.trace(
        "public ResultSet getStreams(String catalog={}, String schemaPattern={}"
            + "String streamName={}",
        originalCatalog,
        originalSchemaPattern,
        streamName);
    raiseSQLExceptionIfConnectionIsClosed();
    Statement statement = connection.createStatement();

    // apply session context when catalog is unspecified
    ContextAwareMetadataSearch result = applySessionContext(originalCatalog, originalSchemaPattern);
    String catalog = result.database();
    String schemaPattern = result.schema();
    boolean isExactSchema = result.isExactSchema();

    final Pattern compiledSchemaPattern = Wildcard.toRegexPattern(schemaPattern, true);
    final Pattern compiledStreamNamePattern = Wildcard.toRegexPattern(streamName, true);

    String showStreamsCommand = "show streams";

    if (streamName != null
        && !streamName.isEmpty()
        && !streamName.trim().equals("%")
        && !streamName.trim().equals(".*")) {
      showStreamsCommand += " like '" + escapeSingleQuoteForLikeCommand(streamName) + "'";
    }

    if (catalog == null) {
      showStreamsCommand += " in account";
    } else if (catalog.isEmpty()) {
      return SnowflakeDatabaseMetaDataResultSet.getEmptyResultSet(GET_STREAMS, statement);
    } else {
      String catalogEscaped = escapeSqlQuotes(catalog);
      if (schemaPattern == null || isSchemaNameWildcardPattern(schemaPattern)) {
        showStreamsCommand += " in database \"" + catalogEscaped + "\"";
      } else if (schemaPattern.isEmpty()) {
        return SnowflakeDatabaseMetaDataResultSet.getEmptyResultSet(GET_STREAMS, statement);
      } else {
        String schemaUnescaped = isExactSchema ? schemaPattern : unescapeChars(schemaPattern);
        if (streamName == null || Wildcard.isWildcardPatternStr(streamName)) {
          showStreamsCommand += " in schema \"" + catalogEscaped + "\".\"" + schemaUnescaped + "\"";
        }
      }
    }

    logger.debug("Sql command to get stream metadata: {}", showStreamsCommand);

    ResultSet resultSet =
        executeAndReturnEmptyResultIfNotFound(statement, showStreamsCommand, GET_STREAMS);
    sendInBandTelemetryMetadataMetrics(
        resultSet, "getStreams", originalCatalog, originalSchemaPattern, streamName, "none");

    return new SnowflakeDatabaseMetaDataQueryResultSet(GET_STREAMS, resultSet, statement) {
      @Override
      public boolean next() throws SQLException {
        logger.trace("boolean next()");
        incrementRow();

        // iterate throw the show streams result until we find an entry
        // that matches the stream name
        while (showObjectResultSet.next()) {
          String name = showObjectResultSet.getString(2);
          String databaseName = showObjectResultSet.getString(3);
          String schemaName = showObjectResultSet.getString(4);
          String owner = showObjectResultSet.getString(5);
          String comment = showObjectResultSet.getString(6);
          String tableName = showObjectResultSet.getString(7);
          String sourceType = showObjectResultSet.getString(8);
          String baseTables = showObjectResultSet.getString(9);
          String type = showObjectResultSet.getString(10);
          String stale = showObjectResultSet.getString(11);
          String mode = showObjectResultSet.getString(12);

          if ((compiledStreamNamePattern == null
                  || compiledStreamNamePattern.matcher(streamName).matches())
              && (compiledSchemaPattern == null
                  || compiledSchemaPattern.matcher(schemaName).matches())
              && (compiledStreamNamePattern == null
                  || compiledStreamNamePattern.matcher(streamName).matches())) {
            logger.debug("Found a matched column:" + tableName + "." + streamName);
            nextRow[0] = name;
            nextRow[1] = databaseName;
            nextRow[2] = schemaName;
            nextRow[3] = owner;
            nextRow[4] = comment;
            nextRow[5] = tableName;
            nextRow[6] = sourceType;
            nextRow[7] = baseTables;
            nextRow[8] = type;
            nextRow[9] = stale;
            nextRow[10] = mode;
            return true;
          }
        }
        close();
        return false;
      }
    };
  }

  @Override
  public ResultSet getIndexInfo(
      String catalog, String schema, String table, boolean unique, boolean approximate)
      throws SQLException {
    logger.trace(
        "public ResultSet getIndexInfo(String catalog, String schema, "
            + "String table,boolean unique, boolean approximate)",
        false);
    raiseSQLExceptionIfConnectionIsClosed();

    Statement statement = connection.createStatement();

    // Return empty result set since we don't have primary keys yet
    return new SnowflakeDatabaseMetaDataResultSet(
        Arrays.asList(
            "TABLE_CAT",
            "TABLE_SCHEM",
            "TABLE_NAME",
            "NON_UNIQUE",
            "INDEX_QUALIFIER",
            "INDEX_NAME",
            "TYPE",
            "ORDINAL_POSITION",
            "COLUMN_NAME",
            "ASC_OR_DESC",
            "CARDINALITY",
            "PAGES",
            "FILTER_CONDITION"),
        Arrays.asList(
            "TEXT", "TEXT", "TEXT", "BOOLEAN", "TEXT", "TEXT", "SHORT", "SHORT", "TEXT", "TEXT",
            "INTEGER", "INTEGER", "TEXT"),
        Arrays.asList(
            Types.VARCHAR,
            Types.VARCHAR,
            Types.VARCHAR,
            Types.BOOLEAN,
            Types.VARCHAR,
            Types.VARCHAR,
            Types.SMALLINT,
            Types.SMALLINT,
            Types.VARCHAR,
            Types.VARCHAR,
            Types.INTEGER,
            Types.INTEGER,
            Types.VARCHAR),
        new Object[][] {},
        statement);
  }

  @Override
  public boolean supportsResultSetType(int type) throws SQLException {
    logger.trace("boolean supportsResultSetType(int type)", false);
    raiseSQLExceptionIfConnectionIsClosed();
    return (type == ResultSet.TYPE_FORWARD_ONLY);
  }

  @Override
  public boolean supportsResultSetConcurrency(int type, int concurrency) throws SQLException {
    logger.trace(
        "public boolean supportsResultSetConcurrency(int type, " + "int concurrency)", false);
    raiseSQLExceptionIfConnectionIsClosed();
    return (type == ResultSet.TYPE_FORWARD_ONLY && concurrency == ResultSet.CONCUR_READ_ONLY);
  }

  @Override
  public boolean ownUpdatesAreVisible(int type) throws SQLException {
    logger.trace("boolean ownUpdatesAreVisible(int type)", false);
    raiseSQLExceptionIfConnectionIsClosed();
    return false;
  }

  @Override
  public boolean ownDeletesAreVisible(int type) throws SQLException {
    logger.trace("boolean ownDeletesAreVisible(int type)", false);
    raiseSQLExceptionIfConnectionIsClosed();
    return false;
  }

  @Override
  public boolean ownInsertsAreVisible(int type) throws SQLException {
    logger.trace("boolean ownInsertsAreVisible(int type)", false);
    raiseSQLExceptionIfConnectionIsClosed();
    return false;
  }

  @Override
  public boolean othersUpdatesAreVisible(int type) throws SQLException {
    logger.trace("boolean othersUpdatesAreVisible(int type)", false);
    raiseSQLExceptionIfConnectionIsClosed();
    return false;
  }

  @Override
  public boolean othersDeletesAreVisible(int type) throws SQLException {
    logger.trace("boolean othersDeletesAreVisible(int type)", false);
    raiseSQLExceptionIfConnectionIsClosed();
    return false;
  }

  @Override
  public boolean othersInsertsAreVisible(int type) throws SQLException {
    logger.trace("boolean othersInsertsAreVisible(int type)", false);
    raiseSQLExceptionIfConnectionIsClosed();
    return false;
  }

  @Override
  public boolean updatesAreDetected(int type) throws SQLException {
    logger.trace("boolean updatesAreDetected(int type)", false);
    raiseSQLExceptionIfConnectionIsClosed();
    return false;
  }

  @Override
  public boolean deletesAreDetected(int type) throws SQLException {
    logger.trace("boolean deletesAreDetected(int type)", false);
    raiseSQLExceptionIfConnectionIsClosed();
    return false;
  }

  @Override
  public boolean insertsAreDetected(int type) throws SQLException {
    logger.trace("boolean insertsAreDetected(int type)", false);
    raiseSQLExceptionIfConnectionIsClosed();
    return false;
  }

  @Override
  public boolean supportsBatchUpdates() throws SQLException {
    logger.trace("boolean supportsBatchUpdates()", false);
    raiseSQLExceptionIfConnectionIsClosed();
    return true;
  }

  @Override
  public ResultSet getUDTs(
      String catalog, String schemaPattern, String typeNamePattern, int[] types)
      throws SQLException {
    logger.trace(
        "public ResultSet getUDTs(String catalog, "
            + "String schemaPattern,String typeNamePattern, int[] types)",
        false);
    raiseSQLExceptionIfConnectionIsClosed();
    // We don't user-defined types, so return an empty result set
    Statement statement = connection.createStatement();
    return new SnowflakeDatabaseMetaDataResultSet(
        Arrays.asList(
            "TYPE_CAT",
            "TYPE_SCHEM",
            "TYPE_NAME",
            "CLASS_NAME",
            "DATA_TYPE",
            "REMARKS",
            "BASE_TYPE"),
        Arrays.asList("TEXT", "TEXT", "TEXT", "TEXT", "INTEGER", "TEXT", "SHORT"),
        Arrays.asList(
            Types.VARCHAR,
            Types.VARCHAR,
            Types.VARCHAR,
            Types.VARCHAR,
            Types.INTEGER,
            Types.VARCHAR,
            Types.SMALLINT),
        new Object[][] {},
        statement);
  }

  @Override
  public Connection getConnection() throws SQLException {
    logger.trace("Connection getConnection()", false);
    raiseSQLExceptionIfConnectionIsClosed();
    return connection;
  }

  @Override
  public boolean supportsSavepoints() throws SQLException {
    logger.trace("boolean supportsSavepoints()", false);
    raiseSQLExceptionIfConnectionIsClosed();
    return false;
  }

  @Override
  public boolean supportsNamedParameters() throws SQLException {
    logger.trace("boolean supportsNamedParameters()", false);
    raiseSQLExceptionIfConnectionIsClosed();
    return false;
  }

  @Override
  public boolean supportsMultipleOpenResults() throws SQLException {
    logger.trace("boolean supportsMultipleOpenResults()", false);
    raiseSQLExceptionIfConnectionIsClosed();
    return false;
  }

  @Override
  public boolean supportsGetGeneratedKeys() throws SQLException {
    logger.trace("boolean supportsGetGeneratedKeys()", false);
    raiseSQLExceptionIfConnectionIsClosed();
    return false;
  }

  @Override
  public ResultSet getSuperTypes(String catalog, String schemaPattern, String typeNamePattern)
      throws SQLException {
    logger.trace(
        "public ResultSet getSuperTypes(String catalog, "
            + "String schemaPattern,String typeNamePattern)",
        false);

    throw new SnowflakeLoggedFeatureNotSupportedException(session);
  }

  @Override
  public ResultSet getSuperTables(String catalog, String schemaPattern, String tableNamePattern)
      throws SQLException {
    logger.trace(
        "public ResultSet getSuperTables(String catalog, "
            + "String schemaPattern,String tableNamePattern)",
        false);

    throw new SnowflakeLoggedFeatureNotSupportedException(session);
  }

  @Override
  public ResultSet getAttributes(
      String catalog, String schemaPattern, String typeNamePattern, String attributeNamePattern)
      throws SQLException {
    logger.trace(
        "public ResultSet getAttributes(String catalog, String "
            + "schemaPattern,"
            + "String typeNamePattern,String attributeNamePattern)",
        false);

    throw new SnowflakeLoggedFeatureNotSupportedException(session);
  }

  @Override
  public boolean supportsResultSetHoldability(int holdability) throws SQLException {
    logger.trace("boolean supportsResultSetHoldability(int holdability)", false);
    raiseSQLExceptionIfConnectionIsClosed();
    return holdability == ResultSet.CLOSE_CURSORS_AT_COMMIT;
  }

  @Override
  public int getResultSetHoldability() throws SQLException {
    logger.trace("int getResultSetHoldability()", false);
    return ResultSet.CLOSE_CURSORS_AT_COMMIT;
  }

  @Override
  public int getDatabaseMajorVersion() throws SQLException {
    logger.trace("int getDatabaseMajorVersion()", false);
    raiseSQLExceptionIfConnectionIsClosed();
    return connection.unwrap(SnowflakeConnectionV1.class).getDatabaseMajorVersion();
  }

  @Override
  public int getDatabaseMinorVersion() throws SQLException {
    logger.trace("int getDatabaseMinorVersion()", false);
    raiseSQLExceptionIfConnectionIsClosed();
    return connection.unwrap(SnowflakeConnectionV1.class).getDatabaseMinorVersion();
  }

  @Override
  public int getJDBCMajorVersion() throws SQLException {
    logger.trace("int getJDBCMajorVersion()", false);
    raiseSQLExceptionIfConnectionIsClosed();
    return Integer.parseInt(JDBCVersion.split("\\.", 2)[0]);
  }

  @Override
  public int getJDBCMinorVersion() throws SQLException {
    logger.trace("int getJDBCMinorVersion()", false);
    raiseSQLExceptionIfConnectionIsClosed();
    return Integer.parseInt(JDBCVersion.split("\\.", 2)[1]);
  }

  @Override
  public int getSQLStateType() throws SQLException {
    logger.trace("int getSQLStateType()", false);
    return sqlStateSQL;
  }

  @Override
  public boolean locatorsUpdateCopy() {
    logger.trace("boolean locatorsUpdateCopy()", false);

    return false;
  }

  @Override
  public boolean supportsStatementPooling() throws SQLException {
    logger.trace("boolean supportsStatementPooling()", false);
    raiseSQLExceptionIfConnectionIsClosed();
    return false;
  }

  @Override
  public RowIdLifetime getRowIdLifetime() throws SQLException {
    logger.trace("RowIdLifetime getRowIdLifetime()", false);

    throw new SnowflakeLoggedFeatureNotSupportedException(session);
  }

  @Override
  public ResultSet getSchemas(String originalCatalog, String originalSchema) throws SQLException {
    logger.trace(
        "public ResultSet getSchemas(String catalog={}, String " + "schemaPattern={})",
        originalCatalog,
        originalSchema);
    raiseSQLExceptionIfConnectionIsClosed();

    // apply session context when catalog is unspecified
    ContextAwareMetadataSearch result = applySessionContext(originalCatalog, originalSchema);
    final String catalog = result.database();
    final String schemaPattern = result.schema();
    boolean isExactSchema = result.isExactSchema();

    final Pattern compiledSchemaPattern = Wildcard.toRegexPattern(schemaPattern, true);

    StringBuilder showSchemas =
        new StringBuilder("show /* JDBC:DatabaseMetaData.getSchemas() */ schemas");

    Statement statement = connection.createStatement();
    if (isExactSchema && enableWildcardsInShowMetadataCommands) {
      String escapedSchema =
          schemaPattern.replaceAll("_", "\\\\\\\\_").replaceAll("%", "\\\\\\\\%");
      showSchemas.append(" like '").append(escapedSchema).append("'");
    } else if (schemaPattern != null
        && !schemaPattern.isEmpty()
        && !schemaPattern.trim().equals("%")
        && !schemaPattern.trim().equals(".*")) {
      // only add pattern if it is not empty and not matching all character.
      showSchemas
          .append(" like '")
          .append(escapeSingleQuoteForLikeCommand(schemaPattern))
          .append("'");
    }

    if (catalog == null) {
      showSchemas.append(" in account");
    } else if (catalog.isEmpty()) {
      return SnowflakeDatabaseMetaDataResultSet.getEmptyResultSet(GET_SCHEMAS, statement);
    } else {
      showSchemas.append(" in database \"").append(escapeSqlQuotes(catalog)).append("\"");
    }

    String sqlQuery = showSchemas.toString();
    logger.debug("Sql command to get schemas metadata: {}", sqlQuery);

    ResultSet resultSet = executeAndReturnEmptyResultIfNotFound(statement, sqlQuery, GET_SCHEMAS);
    sendInBandTelemetryMetadataMetrics(
        resultSet, "getSchemas", originalCatalog, originalSchema, "none", "none");
    return new SnowflakeDatabaseMetaDataQueryResultSet(GET_SCHEMAS, resultSet, statement) {
      public boolean next() throws SQLException {
        logger.trace("boolean next()", false);
        incrementRow();

        // iterate throw the show table result until we find an entry
        // that matches the table name
        while (showObjectResultSet.next()) {
          String schemaName = showObjectResultSet.getString(2);
          String dbName = showObjectResultSet.getString(5);

          if (compiledSchemaPattern == null
              || compiledSchemaPattern.matcher(schemaName).matches()
              || isExactSchema && schemaPattern.equals(schemaPattern)) {
            nextRow[0] = schemaName;
            nextRow[1] = dbName;
            return true;
          }
        }
        close();
        return false;
      }
    };
  }

  @Override
  public boolean supportsStoredFunctionsUsingCallSyntax() throws SQLException {
    logger.trace("boolean supportsStoredFunctionsUsingCallSyntax()", false);
    raiseSQLExceptionIfConnectionIsClosed();
    return true;
  }

  @Override
  public boolean autoCommitFailureClosesAllResultSets() throws SQLException {
    logger.trace("boolean autoCommitFailureClosesAllResultSets()", false);

    throw new SnowflakeLoggedFeatureNotSupportedException(session);
  }

  @Override
  public ResultSet getClientInfoProperties() throws SQLException {
    logger.trace("ResultSet getClientInfoProperties()", false);

    throw new SnowflakeLoggedFeatureNotSupportedException(session);
  }

  @Override
  public ResultSet getFunctions(
      final String originalCatalog,
      final String originalSchemaPattern,
      final String functionNamePattern)
      throws SQLException {
    raiseSQLExceptionIfConnectionIsClosed();
    Statement statement = connection.createStatement();
    logger.trace(
        "public ResultSet getFunctions(String catalog={}, String schemaPattern={}, "
            + "String functionNamePattern={}",
        originalCatalog,
        originalSchemaPattern,
        functionNamePattern);

    String showFunctionCommand =
        getFirstResultSetCommand(
            originalCatalog, originalSchemaPattern, functionNamePattern, "functions");
    if (showFunctionCommand.isEmpty()) {
      return SnowflakeDatabaseMetaDataResultSet.getEmptyResultSet(GET_FUNCTIONS, statement);
    }
    ContextAwareMetadataSearch result = applySessionContext(originalCatalog, originalSchemaPattern);
    String catalog = result.database();
    String schemaPattern = result.schema();
    boolean isExactSchema = result.isExactSchema();

    final Pattern compiledSchemaPattern = Wildcard.toRegexPattern(schemaPattern, true);
    final Pattern compiledFunctionPattern = Wildcard.toRegexPattern(functionNamePattern, true);

    ResultSet resultSet =
        executeAndReturnEmptyResultIfNotFound(statement, showFunctionCommand, GET_FUNCTIONS);
    sendInBandTelemetryMetadataMetrics(
        resultSet, "getFunctions", catalog, schemaPattern, functionNamePattern, "none");

    return new SnowflakeDatabaseMetaDataQueryResultSet(GET_FUNCTIONS, resultSet, statement) {
      public boolean next() throws SQLException {
        logger.trace("boolean next()", false);
        incrementRow();

        // iterate throw the show table result until we find an entry
        // that matches the table name
        while (showObjectResultSet.next()) {
          String catalogName = showObjectResultSet.getString(11);
          String schemaName = showObjectResultSet.getString(3);
          String functionName = showObjectResultSet.getString(2);
          String remarks = showObjectResultSet.getString(10);
          int functionType =
              ("Y".equals(showObjectResultSet.getString(12))
                  ? functionReturnsTable
                  : functionNoTable);
          String specificName = functionName;
          if ((compiledFunctionPattern == null
                  || compiledFunctionPattern.matcher(functionName).matches())
              && (compiledSchemaPattern == null
                  || compiledSchemaPattern.matcher(schemaName).matches()
                  || isExactSchema && schemaPattern.equals(schemaPattern))) {
            logger.debug("Found a matched function:" + schemaName + "." + functionName);

            nextRow[0] = catalogName;
            nextRow[1] = schemaName;
            nextRow[2] = functionName;
            nextRow[3] = remarks;
            nextRow[4] = functionType;
            nextRow[5] = specificName;
            return true;
          }
        }
        close();
        return false;
      }
    };
  }

  /**
   * This is a function that takes in a string of return types and a string of parameter names and
   * types. It splits both strings in a list of column names and column types. The names will be
   * every odd index and the types will be every even index.
   */
  private List<String> parseColumns(String retType, String args) {
    List<String> columns = new ArrayList<>();
    if (retType.substring(0, 5).equalsIgnoreCase("table")) {
      // if return type is a table there will be a result set
      String typeStr = retType.substring(retType.indexOf('(') + 1, retType.lastIndexOf(')'));
      String[] types = typeStr.split("\\s+|, ");
      if (types.length != 1) {
        for (int i = 0; i < types.length; i++) {
          columns.add(types[i]);
        }
        procedureResultsetColumnNum = columns.size() / 2;
      }
    } else {
      // otherwise it will be a return value
      columns.add(""); // there is no name for this column
      columns.add(retType);
      procedureResultsetColumnNum = -1;
    }
    String argStr = args.substring(args.indexOf('(') + 1, args.lastIndexOf(')'));
    String arguments[] = argStr.split("\\s+|, ");
    if (arguments.length != 1) {
      for (int i = 0; i < arguments.length; i++) {
        columns.add(arguments[i]);
      }
    }
    return columns;
  }

  @Override
  public ResultSet getFunctionColumns(
      String catalog, String schemaPattern, String functionNamePattern, String columnNamePattern)
      throws SQLException {
    logger.trace(
        "public ResultSet getFunctionColumns(String catalog, "
            + "String schemaPattern,String functionNamePattern,"
            + "String columnNamePattern)",
        false);
    raiseSQLExceptionIfConnectionIsClosed();
    Statement statement = connection.createStatement();
    boolean addAllRows = false;
    String showFunctionCommand =
        getFirstResultSetCommand(catalog, schemaPattern, functionNamePattern, "functions");

    if (showFunctionCommand.isEmpty()) {
      return SnowflakeDatabaseMetaDataResultSet.getEmptyResultSet(GET_FUNCTION_COLUMNS, statement);
    }

    if (columnNamePattern == null
        || columnNamePattern.isEmpty()
        || columnNamePattern.trim().equals("%")
        || columnNamePattern.trim().equals(".*")) {
      addAllRows = true;
    }

    ResultSet resultSetStepOne =
        executeAndReturnEmptyResultIfNotFound(statement, showFunctionCommand, GET_FUNCTION_COLUMNS);
    sendInBandTelemetryMetadataMetrics(
        resultSetStepOne,
        "getFunctionColumns",
        catalog,
        schemaPattern,
        functionNamePattern,
        columnNamePattern);
    ArrayList<Object[]> rows = new ArrayList<Object[]>();
    while (resultSetStepOne.next()) {
      String functionNameUnparsed = resultSetStepOne.getString("arguments").trim();
      String functionNameNoArgs = resultSetStepOne.getString("name");
      String realSchema = resultSetStepOne.getString("schema_name");
      String realDatabase = resultSetStepOne.getString("catalog_name");
      String showFunctionColCommand =
          getSecondResultSetCommand(realDatabase, realSchema, functionNameUnparsed, "function");
      ResultSet resultSetStepTwo =
          executeAndReturnEmptyResultIfNotFound(
              statement, showFunctionColCommand, GET_FUNCTION_COLUMNS);
      if (resultSetStepTwo.next() == false) {
        continue;
      }
      // Retrieve the function arguments and function return values.
      String args = resultSetStepTwo.getString("value");
      resultSetStepTwo.next();
      String res = resultSetStepTwo.getString("value");
      // parse function arguments and return values into a list of columns
      // result value(s) will be at the top of the list, followed by any arguments
      List<String> functionCols = parseColumns(res, args);
      String paramNames[] = new String[functionCols.size() / 2];
      String paramTypes[] = new String[functionCols.size() / 2];
      if (functionCols.size() > 1) {
        for (int i = 0; i < functionCols.size(); i++) {
          if (i % 2 == 0) {
            paramNames[i / 2] = functionCols.get(i);
          } else {
            paramTypes[i / 2] = functionCols.get(i);
          }
        }
      }
      for (int i = 0; i < paramNames.length; i++) {
        // if it's the 1st in for loop, it's the result
        if (i == 0 || paramNames[i].equalsIgnoreCase(columnNamePattern) || addAllRows) {
          Object[] nextRow = new Object[17];
          // add a row to resultSet
          nextRow[0] = catalog; // function catalog. Can be null.
          nextRow[1] = schemaPattern; // function schema. Can be null.
          nextRow[2] = functionNameNoArgs; // function name
          nextRow[3] = paramNames[i]; // column/parameter name
          if (i == 0 && procedureResultsetColumnNum < 0) {
            nextRow[4] = functionReturn;
          } else if (procedureResultsetColumnNum >= 0 && i < procedureResultsetColumnNum) {
            nextRow[4] = functionColumnResult;
          } else {
            nextRow[4] = functionColumnIn; // kind of column/parameter
          }
          String typeName = paramTypes[i];
          int type = convertStringToType(typeName);
          nextRow[5] = type; // data type
          nextRow[6] = typeName; // type name
          // precision and scale. Values only exist for numbers
          int precision = 38;
          short scale = 0;
          if (type < 10) {
            if (typeName.contains("(") && typeName.contains(")")) {
              precision =
                  Integer.parseInt(
                      typeName.substring(typeName.indexOf('(') + 1, typeName.indexOf(',')));
              scale =
                  Short.parseShort(
                      typeName.substring(typeName.indexOf(',') + 1, typeName.indexOf(')')));
              nextRow[7] = precision;
              nextRow[9] = scale;
            } else if (type == Types.FLOAT) {
              nextRow[7] = 0;
              nextRow[9] = null;
            } else {
              nextRow[7] = precision;
              nextRow[9] = scale;
            }
          } else {
            nextRow[7] = 0;
            nextRow[9] = null;
          }
          nextRow[8] = 0; // length in bytes. not supported
          nextRow[10] = 10; // radix. Probably 10 is default, but unknown.
          nextRow[11] =
              functionNullableUnknown; // nullable. We don't know from current function info.
          nextRow[12] = resultSetStepOne.getString("description").trim(); // remarks
          if (type == Types.BINARY
              || type == Types.VARBINARY
              || type == Types.CHAR
              || type == Types.VARCHAR) {
            if (typeName.contains("(") && typeName.contains(")")) {
              int char_octet_len =
                  Integer.parseInt(
                      typeName.substring(typeName.indexOf('(') + 1, typeName.indexOf(')')));
              nextRow[13] = char_octet_len;
            } else if (type == Types.CHAR || type == Types.VARCHAR) {
              nextRow[13] = getMaxCharLiteralLength();
            } else if (type == Types.BINARY || type == Types.VARBINARY) {
              nextRow[13] = getMaxBinaryLiteralLength();
            }
          } else {
            nextRow[13] = null;
          }
          // the ordinal position is 0 for a return value.
          // for result set columns, the ordinal position is of the column in the result set
          // starting at 1
          if (procedureResultsetColumnNum >= 0) {
            if (i < procedureResultsetColumnNum) {
              nextRow[14] = i + 1;
            } else {
              nextRow[14] = i - procedureResultsetColumnNum + 1;
            }
          } else {
            nextRow[14] = i; // ordinal position.
          }
          nextRow[15] = ""; // nullability again. Not supported.
          nextRow[16] = functionNameUnparsed;
          rows.add(nextRow);
        }
      }
    }
    Object[][] resultRows = new Object[rows.size()][17];
    for (int i = 0; i < resultRows.length; i++) {
      resultRows[i] = rows.get(i);
    }
    return new SnowflakeDatabaseMetaDataResultSet(GET_FUNCTION_COLUMNS, resultRows, statement);
  }

  // @Override
  public ResultSet getPseudoColumns(
      String catalog, String schemaPattern, String tableNamePattern, String columnNamePattern)
      throws SQLException {
    logger.trace(
        "public ResultSet getPseudoColumns(String catalog, "
            + "String schemaPattern,String tableNamePattern,"
            + "String columnNamePattern)",
        false);

    throw new SnowflakeLoggedFeatureNotSupportedException(session);
  }

  // @Override
  public boolean generatedKeyAlwaysReturned() throws SQLException {
    logger.trace("boolean generatedKeyAlwaysReturned()", false);

    throw new SnowflakeLoggedFeatureNotSupportedException(session);
  }

  // unchecked
  @Override
  public <T> T unwrap(Class<T> iface) throws SQLException {
    logger.trace("<T> T unwrap(Class<T> iface)", false);

    if (!iface.isInstance(this)) {
      throw new SQLException(
          this.getClass().getName() + " not unwrappable from " + iface.getName());
    }
    return (T) this;
  }

  @Override
  public boolean isWrapperFor(Class<?> iface) throws SQLException {
    logger.trace("boolean isWrapperFor(Class<?> iface)", false);

    throw new SnowflakeLoggedFeatureNotSupportedException(session);
  }

  /**
   * A small helper function to execute show command to get metadata, And if object does not exist,
   * return an empty result set instead of throwing a SnowflakeSQLException
   */
  private ResultSet executeAndReturnEmptyResultIfNotFound(
      Statement statement, String sql, DBMetadataResultSetMetadata metadataType)
      throws SQLException {
    ResultSet resultSet;
    if (isNullOrEmpty(sql)) {
      return SnowflakeDatabaseMetaDataResultSet.getEmptyResultSet(metadataType, statement);
    }
    try {
      resultSet = statement.executeQuery(sql);
    } catch (SnowflakeSQLException e) {
      if (e.getSQLState().equals(SqlState.NO_DATA)
          || e.getSQLState().equals(SqlState.BASE_TABLE_OR_VIEW_NOT_FOUND)
          || e.getMessage().contains("Operation is not supported in reader account")) {
        return SnowflakeDatabaseMetaDataResultSet.getEmptyResult(
            metadataType, statement, e.getQueryId());
      }
      // When using this helper function for "desc function" calls, there are some built-in
      // functions with unusual argument syntax that throw an error when attempting to call
      // desc function on them. For example, AS_TIMESTAMP_LTZ([,VARIANT]) throws an exception.
      // Skip these built-in functions.
      else if (sql.contains("desc function")
          && e.getSQLState().equals(SqlState.SYNTAX_ERROR_OR_ACCESS_RULE_VIOLATION)) {
        return SnowflakeDatabaseMetaDataResultSet.getEmptyResult(
            metadataType, statement, e.getQueryId());
      } else {
        throw e;
      }
    }
    return resultSet;
  }

  private static class ContextAwareMetadataSearch {
    private final String database;
    private final String schema;
    private final boolean isExactSchema;

    public ContextAwareMetadataSearch(String database, String schema, boolean isExactSchema) {
      this.database = database;
      this.schema = schema;
      this.isExactSchema = isExactSchema;
    }

    public String database() {
      return database;
    }

    public String schema() {
      return schema;
    }

    public boolean isExactSchema() {
      return isExactSchema;
    }
  }
}
