package com.psddev.dari.db;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.joda.time.DateTime;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.psddev.dari.util.UuidUtils;

//import org.slf4j.Logger;
//import org.slf4j.LoggerFactory;

class MetricDatabase {

    //private static final Logger LOGGER = LoggerFactory.getLogger(MetricDatabase.class);

    public static final String METRIC_TABLE = "Metric";
    public static final String METRIC_ID_FIELD = "id";
    public static final String METRIC_TYPE_FIELD = "typeId";
    public static final String METRIC_SYMBOL_FIELD = "symbolId";
    public static final String METRIC_DIMENSION_FIELD = "dimensionId";
    public static final String METRIC_DIMENSION_TABLE = "MetricDimension";
    public static final String METRIC_DIMENSION_VALUE_FIELD = "value";
    public static final String METRIC_DATA_FIELD = "data";
    public static final String METRIC_CACHE_EXTRA_PREFIX = "dari.metric.cache.";

    public static final int AMOUNT_DECIMAL_PLACES = 6;
    public static final long AMOUNT_DECIMAL_SHIFT = (long) Math.pow(10, AMOUNT_DECIMAL_PLACES);
    public static final long DATE_DECIMAL_SHIFT = 60000L;
    public static final int CUMULATIVEAMOUNT_POSITION = 1;
    public static final int AMOUNT_POSITION = 2;
    public static final int DATE_BYTE_SIZE = 4;
    public static final int AMOUNT_BYTE_SIZE = 8;

    private static final int QUERY_TIMEOUT = 3;
    private static final int DIMENSION_CACHE_SIZE = 1000;

    private static final transient Cache<String, UUID> dimensionCache = CacheBuilder.newBuilder().maximumSize(DIMENSION_CACHE_SIZE).build();

    private static final ConcurrentMap<String, MetricDatabase> metricDatabases = new ConcurrentHashMap<String, MetricDatabase>();

    private final String symbol;
    private final SqlDatabase db;
    private final UUID typeId;

    private MetricInterval eventDateProcessor;

    public MetricDatabase(SqlDatabase database, UUID typeId, String symbol, MetricInterval interval) {
        this.db = database;
        this.typeId = typeId;
        this.symbol = symbol;
        this.eventDateProcessor = interval;
    }

    public UUID getTypeId() {
        return typeId;
    }

    public MetricInterval getEventDateProcessor() {
        if (eventDateProcessor == null) {
            eventDateProcessor = new MetricInterval.Hourly();
        }
        return eventDateProcessor;
    }

    public SqlDatabase getDatabase() {
        return db;
    }

    public int getSymbolId() {
        return db.getSymbolId(symbol);
    }

    // This method should strip the minutes and seconds off of a timestamp, or otherwise process it
    public long getEventDate(DateTime time) {
        long eventDate;
        if (time == null) {
            time = new DateTime(db.now());
        }
        if (time.getMillis() > db.now()) {
            throw new RuntimeException("Metric.eventDate may not be a date in the future.");
        }
        eventDate = getEventDateProcessor().process(time);
        return eventDate;
    }

    public DateTime getLastUpdate(UUID id, String dimensionValue) throws SQLException {
        byte[] data = getMaxData(id, getDimensionId(dimensionValue), null);
        return data != null ? new DateTime(Static.timestampFromBytes(data)) : null;
    }

    public Double getMetric(UUID id, String dimensionValue, Long startTimestamp, Long endTimestamp) throws SQLException {
        if (startTimestamp == null) {
            byte[] data = getMaxData(id, getDimensionId(dimensionValue), endTimestamp);
            if (data == null) {
                return null;
            }
            return Static.amountFromBytes(data, CUMULATIVEAMOUNT_POSITION);
        } else {
            List<byte[]> datas = getMaxMinData(id, getDimensionId(dimensionValue), startTimestamp, endTimestamp);
            if (datas.get(0) == null) {
                return null;
            }
            double maxCumulativeAmount = Static.amountFromBytes(datas.get(0), CUMULATIVEAMOUNT_POSITION);
            double minCumulativeAmount = Static.amountFromBytes(datas.get(1), CUMULATIVEAMOUNT_POSITION);
            double minAmount = Static.amountFromBytes(datas.get(1), AMOUNT_POSITION);
            return maxCumulativeAmount - (minCumulativeAmount - minAmount);
        }
    }

    /**
     * Cached in CachingDatabase (if available) on id, dimensionId, and endTimestamp
     * @param id Can't be {@code null}.
     * @param dimensionId Can't be {@code null}.
     */
    private byte[] getMaxData(UUID id, UUID dimensionId, Long endTimestamp) throws SQLException {
        CachingDatabase cachingDb = Static.getCachingDatabase();
        byte[] data;
        if (hasCachedData(cachingDb, id, dimensionId, endTimestamp)) {
            data = getCachedData(cachingDb, id, dimensionId, endTimestamp);
        } else {
            data = Static.getDataByIdAndDimension(getDatabase(), id, getTypeId(), getSymbolId(), dimensionId, null, endTimestamp);
            putCachedData(cachingDb, id, dimensionId, endTimestamp, data);
        }
        return data;
    }

    /**
     * Cached in CachingDatabase (if available) on id, dimensionId, startTimestamp, and endTimestamp
     * @param dimensionId Can't be {@code null}.
     * @return List of two elements, maxData and minData
     */
    private List<byte[]> getMaxMinData(UUID id, UUID dimensionId, Long startTimestamp, Long endTimestamp) throws SQLException {
        CachingDatabase cachingDb = Static.getCachingDatabase();
        List<byte[]> result;
        if (hasCachedData(cachingDb, id, dimensionId, startTimestamp) && hasCachedData(cachingDb, id, dimensionId, endTimestamp)) {
            result = new ArrayList<byte[]>();
            result.add(getCachedData(cachingDb, id, dimensionId, endTimestamp)); // MAX
            result.add(getCachedData(cachingDb, id, dimensionId, startTimestamp)); // MIN
        } else {
            result = Static.getMaxMinDataByIdAndDimension(getDatabase(), id, getTypeId(), getSymbolId(), dimensionId, startTimestamp, endTimestamp);
            if (result.isEmpty()) {
                result.add(null);
                result.add(null);
            }
            putCachedData(cachingDb, id, dimensionId, endTimestamp, result.get(0)); // MAX
            putCachedData(cachingDb, id, dimensionId, startTimestamp, result.get(1)); // MIN
        }
        return result;
    }

    private boolean hasCachedData(CachingDatabase cachingDb, UUID id, UUID dimensionId, Long timestamp) {
        Map<String, Object> extras = getCachedStateExtras(cachingDb, id);
        if (extras == null) return false;
        synchronized(extras) {
            return (extras.containsKey(METRIC_CACHE_EXTRA_PREFIX + getSymbolId() + '.' + dimensionId + '.' + timestamp));
        }
    }

    private byte[] getCachedData(CachingDatabase cachingDb, UUID id, UUID dimensionId, Long timestamp) {
        Map<String, Object> extras = getCachedStateExtras(cachingDb, id);
        if (extras != null) {
            synchronized(extras) {
                return (byte[]) extras.get(METRIC_CACHE_EXTRA_PREFIX + getSymbolId() + '.' + dimensionId + '.' + timestamp);
            }
        }
        return null;
    }

    private void putCachedData(CachingDatabase cachingDb, UUID id, UUID dimensionId, Long timestamp, byte[] data) {
        Map<String, Object> extras = getCachedStateExtras(cachingDb, id);
        if (extras != null) {
            synchronized(extras) {
                extras.put(METRIC_CACHE_EXTRA_PREFIX + getSymbolId() + '.' + dimensionId + '.' + timestamp, data);
            }
        }
    }

    private void clearCachedData(CachingDatabase cachingDb, UUID id) {
        Map<String, Object> extras = getCachedStateExtras(cachingDb, id);
        if (extras != null) {
            synchronized(extras) {
                Set<String> toRemove = new HashSet<String>();
                for (Map.Entry<String, Object> entry : extras.entrySet()) {
                    if (entry.getKey().startsWith(METRIC_CACHE_EXTRA_PREFIX)) {
                        toRemove.add(entry.getKey());
                    }
                }
                for (String key : toRemove) {
                    extras.remove(key);
                }
            }
        }
    }

    private Map<String,Object> getCachedStateExtras(CachingDatabase cachingDb, UUID id) {
        if (cachingDb != null && cachingDb.getObjectCache().containsKey(id)) {
            Object obj = cachingDb.getObjectCache().get(id);
            if (obj != null && obj instanceof Recordable) {
                return ((Recordable) obj).getState().getExtras();
            }
        }
        return null;
    }

    public Double getMetricSum(UUID id, Long startTimestamp, Long endTimestamp) throws SQLException {
        return getMetric(id, null, null, null);
    }

    public Map<String, Double> getMetricValues(UUID id, Long startTimestamp, Long endTimestamp) throws SQLException {
        return Static.getMetricDimensionsById(getDatabase(), id, getTypeId(), getSymbolId(), startTimestamp, endTimestamp);
    }

    public Map<DateTime, Double> getMetricTimeline(UUID id, String dimensionValue, Long startTimestamp, Long endTimestamp, MetricInterval metricInterval) throws SQLException {
        if (metricInterval == null) {
            metricInterval = getEventDateProcessor();
        }
        return Static.getMetricTimelineByIdAndDimension(getDatabase(), id, getTypeId(), getSymbolId(), getDimensionId(dimensionValue), startTimestamp, endTimestamp, metricInterval);
    }

    public void incrementMetric(UUID id, DateTime time, String dimensionValue, Double amount) throws SQLException {
        incrementMetricByDimensionId(id, time, getDimensionId(dimensionValue), amount);
    }

    public void incrementMetricByDimensionId(UUID id, DateTime time, UUID dimensionId, Double amount) throws SQLException {
        // This actually causes some problems if it's not here
        if (amount == 0) {
            return;
        }
        boolean isImplicitEventDate = (time == null);
        long eventDate = getEventDate(time);
        Static.doIncrementUpdateOrInsert(getDatabase(), id, getTypeId(), getSymbolId(), dimensionId, amount, eventDate, isImplicitEventDate);
        if (! dimensionId.equals(UuidUtils.ZERO_UUID)) {
            // Do an additional increment for the null dimension to maintain the sum
            Static.doIncrementUpdateOrInsert(getDatabase(), id, getTypeId(), getSymbolId(), UuidUtils.ZERO_UUID, amount, eventDate, isImplicitEventDate);
        }
        clearCachedData(Static.getCachingDatabase(), id);
    }

    public void setMetric(UUID id, DateTime time, String dimensionValue, Double amount) throws SQLException {
        setMetricByDimensionId(id, time, getDimensionId(dimensionValue), amount);
        clearCachedData(Static.getCachingDatabase(), id);
    }

    public void setMetricByDimensionId(UUID id, DateTime time, UUID dimensionId, Double amount) throws SQLException {
        // This only works if we're not tracking eventDate
        if (getEventDate(time) != 0L) {
            throw new RuntimeException("MetricDatabase.setMetric() can only be used if EventDateProcessor is None");
        }
        Static.doSetUpdateOrInsert(getDatabase(), id, getTypeId(), getSymbolId(), dimensionId, amount, 0L);
        if (! dimensionId.equals(UuidUtils.ZERO_UUID)) {
            // Do an additional increment for the null dimension to maintain the sum
            Double allDimensionsAmount = Static.calculateMetricSumById(getDatabase(), id, getTypeId(), getSymbolId(), null, null);
            Static.doSetUpdateOrInsert(getDatabase(), id, getTypeId(), getSymbolId(), UuidUtils.ZERO_UUID, allDimensionsAmount, 0L);
        }
        clearCachedData(Static.getCachingDatabase(), id);
    }

    public void deleteMetric(UUID id) throws SQLException {
        Static.doMetricDelete(getDatabase(), id, getTypeId(), getSymbolId());
        clearCachedData(Static.getCachingDatabase(), id);
    }

    public void reconstructCumulativeAmounts(UUID id) throws SQLException {
        Static.doReconstructCumulativeAmounts(getDatabase(), id, getTypeId(), getSymbolId(), null);
        clearCachedData(Static.getCachingDatabase(), id);
    }

    public static UUID getDimensionIdByValue(SqlDatabase db, String dimensionValue) {
        if (dimensionValue == null || "".equals(dimensionValue)) {
            return UuidUtils.ZERO_UUID;
        }
        UUID dimensionId = dimensionCache.getIfPresent(dimensionValue);
        if (dimensionId == null) {
            try {
                dimensionId = Static.getDimensionIdByValue(db, dimensionValue);
                if (dimensionId == null) {
                    dimensionId = UuidUtils.createSequentialUuid();
                    Static.doInsertDimensionValue(db, dimensionId, dimensionValue);
                }
                dimensionCache.put(dimensionValue, dimensionId);
            } catch (SQLException e) {
                throw new DatabaseException(db, "Error in MetricDatabase.getDimensionIdByValue() : " + e.getLocalizedMessage());
            }
        }
        return dimensionId;
    }

    public UUID getDimensionId(String dimensionValue) throws SQLException {
        if (dimensionValue == null || "".equals(dimensionValue)) {
            return UuidUtils.ZERO_UUID;
        }
        UUID dimensionId = dimensionCache.getIfPresent(dimensionValue);
        if (dimensionId == null) {
            dimensionId = Static.getDimensionIdByValue(db, dimensionValue);
            if (dimensionId == null) {
                dimensionId = UuidUtils.createSequentialUuid();
                Static.doInsertDimensionValue(db, dimensionId, dimensionValue);
            }
            dimensionCache.put(dimensionValue, dimensionId);
        }
        return dimensionId;
    }

    /** {@link MetricDatabase} utility methods. */
    public static final class Static {

        // Methods that generate SQL statements

        private static String getDataSql(SqlDatabase db, UUID id, UUID typeId, Integer symbolId, UUID dimensionId, Long minEventDate, Long maxEventDate, boolean selectMinData, boolean doDecodeToBytes, String extraSelectSql, String extraGroupBySql, String extraWhereSql) {
            StringBuilder sqlBuilder = new StringBuilder();
            SqlVendor vendor = db.getVendor();

            sqlBuilder.append("SELECT ");

            if (dimensionId == null) {
                vendor.appendIdentifier(sqlBuilder, METRIC_DIMENSION_FIELD);
                sqlBuilder.append(", ");
            }

            StringBuilder maxDataBuilder = new StringBuilder("MAX(");
            vendor.appendIdentifier(maxDataBuilder, METRIC_DATA_FIELD);
            maxDataBuilder.append(')');
            if (doDecodeToBytes) {
                vendor.appendMetricDataBytes(sqlBuilder, maxDataBuilder.toString());
            } else {
                sqlBuilder.append(maxDataBuilder);
            }
            sqlBuilder.append(' ');
            vendor.appendIdentifier(sqlBuilder, "maxData");

            if (selectMinData) {
                sqlBuilder.append(", ");
                StringBuilder minDataBuilder = new StringBuilder("MIN(");
                vendor.appendIdentifier(minDataBuilder, METRIC_DATA_FIELD);
                minDataBuilder.append(')');
                if (doDecodeToBytes) {
                    vendor.appendMetricDataBytes(sqlBuilder, minDataBuilder.toString());
                } else {
                sqlBuilder.append(minDataBuilder);
                }
                sqlBuilder.append(' ');
                vendor.appendIdentifier(sqlBuilder, "minData");
            }

            if (extraSelectSql != null && !"".equals(extraSelectSql)) {
                sqlBuilder.append(", ");
                sqlBuilder.append(extraSelectSql);
            }

            sqlBuilder.append(" FROM ");
            vendor.appendIdentifier(sqlBuilder, METRIC_TABLE);
            sqlBuilder.append(" WHERE ");
            vendor.appendIdentifier(sqlBuilder, METRIC_ID_FIELD);
            sqlBuilder.append(" = ");
            vendor.appendValue(sqlBuilder, id);

            if (symbolId != null) {
                sqlBuilder.append(" AND ");
                vendor.appendIdentifier(sqlBuilder, METRIC_SYMBOL_FIELD);
                sqlBuilder.append(" = ");
                vendor.appendValue(sqlBuilder, symbolId);
            }

            sqlBuilder.append(" AND ");
            vendor.appendIdentifier(sqlBuilder, METRIC_TYPE_FIELD);
            sqlBuilder.append(" = ");
            vendor.appendValue(sqlBuilder, typeId);

            if (dimensionId != null) {
                sqlBuilder.append(" AND ");
                vendor.appendIdentifier(sqlBuilder, METRIC_DIMENSION_FIELD);
                sqlBuilder.append(" = ");
                vendor.appendValue(sqlBuilder, dimensionId);
            } else {
                sqlBuilder.append(" AND ");
                vendor.appendIdentifier(sqlBuilder, METRIC_DIMENSION_FIELD);
                sqlBuilder.append(" != ");
                vendor.appendValue(sqlBuilder, UuidUtils.ZERO_UUID);
            }

            if (maxEventDate != null) {
                sqlBuilder.append(" AND ");
                vendor.appendIdentifier(sqlBuilder, METRIC_DATA_FIELD);
                sqlBuilder.append(" < ");
                vendor.appendMetricEncodeTimestampSql(sqlBuilder, null, maxEventDate, '0');
            }

            if (minEventDate != null) {
                sqlBuilder.append(" AND ");
                vendor.appendIdentifier(sqlBuilder, METRIC_DATA_FIELD);
                sqlBuilder.append(" >= ");
                vendor.appendMetricEncodeTimestampSql(sqlBuilder, null, minEventDate, '0');
            }

            if (extraWhereSql != null) {
                sqlBuilder.append(" AND ");
                sqlBuilder.append(extraWhereSql);
            }

            if (dimensionId == null) {
                sqlBuilder.append(" GROUP BY ");
                vendor.appendIdentifier(sqlBuilder, METRIC_DIMENSION_FIELD);
                if (extraGroupBySql != null && !"".equals(extraGroupBySql)) {
                    sqlBuilder.append(", ");
                    sqlBuilder.append(extraGroupBySql);
                }
            } else if (extraGroupBySql != null && !"".equals(extraGroupBySql)) {
                sqlBuilder.append(" GROUP BY ");
                sqlBuilder.append(extraGroupBySql);
            }

            return sqlBuilder.toString();
        }

        private static String getAllDataSql(SqlDatabase db, UUID id, UUID typeId, int symbolId, UUID dimensionId, Long minEventDate, Long maxEventDate, boolean doDecodeToBytes) {
            StringBuilder sqlBuilder = new StringBuilder();
            SqlVendor vendor = db.getVendor();

            sqlBuilder.append("SELECT ");

            if (dimensionId == null) {
                vendor.appendIdentifier(sqlBuilder, METRIC_DIMENSION_FIELD);
                sqlBuilder.append(", ");
            }

            if (doDecodeToBytes) {
                vendor.appendMetricDataBytes(sqlBuilder, METRIC_DATA_FIELD);
            } else {
                sqlBuilder.append(METRIC_DATA_FIELD);
            }

            sqlBuilder.append(" FROM ");
            vendor.appendIdentifier(sqlBuilder, METRIC_TABLE);
            sqlBuilder.append(" WHERE ");
            vendor.appendIdentifier(sqlBuilder, METRIC_ID_FIELD);
            sqlBuilder.append(" = ");
            vendor.appendValue(sqlBuilder, id);

            sqlBuilder.append(" AND ");
            vendor.appendIdentifier(sqlBuilder, METRIC_SYMBOL_FIELD);
            sqlBuilder.append(" = ");
            vendor.appendValue(sqlBuilder, symbolId);

            sqlBuilder.append(" AND ");
            vendor.appendIdentifier(sqlBuilder, METRIC_TYPE_FIELD);
            sqlBuilder.append(" = ");
            vendor.appendValue(sqlBuilder, typeId);

            if (dimensionId != null) {
                sqlBuilder.append(" AND ");
                vendor.appendIdentifier(sqlBuilder, METRIC_DIMENSION_FIELD);
                sqlBuilder.append(" = ");
                vendor.appendValue(sqlBuilder, dimensionId);
            }

            if (maxEventDate != null) {
                sqlBuilder.append(" AND ");
                vendor.appendIdentifier(sqlBuilder, METRIC_DATA_FIELD);
                sqlBuilder.append(" < ");
                vendor.appendMetricEncodeTimestampSql(sqlBuilder, null, maxEventDate, '0');
            }

            if (minEventDate != null) {
                sqlBuilder.append(" AND ");
                vendor.appendIdentifier(sqlBuilder, METRIC_DATA_FIELD);
                sqlBuilder.append(" >= ");
                vendor.appendMetricEncodeTimestampSql(sqlBuilder, null, minEventDate, '0');
            }

            sqlBuilder.append(" ORDER BY ");
            if (dimensionId == null) {
                vendor.appendIdentifier(sqlBuilder, METRIC_DIMENSION_FIELD);
                sqlBuilder.append(", ");
            }
            vendor.appendIdentifier(sqlBuilder, METRIC_DATA_FIELD);

            return sqlBuilder.toString();
        }

        private static String getSumSql(SqlDatabase db, UUID id, UUID typeId, int symbolId, Long minEventDate, Long maxEventDate) {
            StringBuilder sqlBuilder = new StringBuilder();
            SqlVendor vendor = db.getVendor();
            String innerSql = getDataSql(db, id, typeId, symbolId, null, minEventDate, maxEventDate, true, false, null, null, null);

            sqlBuilder.append("SELECT ");
            appendSelectCalculatedAmountSql(sqlBuilder, vendor, "minData", "maxData", true);
            sqlBuilder.append(" FROM (");
            sqlBuilder.append(innerSql);
            sqlBuilder.append(") x");

            return sqlBuilder.toString();
        }

        private static String getDimensionsSql(SqlDatabase db, UUID id, UUID typeId, int symbolId, Long minEventDate, Long maxEventDate) {
            StringBuilder sqlBuilder = new StringBuilder();
            SqlVendor vendor = db.getVendor();
            String innerSql = getDataSql(db, id, typeId, symbolId, null, minEventDate, maxEventDate, true, false, null, null, null);

            sqlBuilder.append("SELECT ");
            StringBuilder dimValField = new StringBuilder();
            vendor.appendIdentifier(dimValField, "d");
            dimValField.append('.');
            vendor.appendIdentifier(dimValField, METRIC_DIMENSION_VALUE_FIELD);
            sqlBuilder.append(vendor.convertRawToStringSql(METRIC_DIMENSION_VALUE_FIELD));
            sqlBuilder.append(", ");
            appendSelectCalculatedAmountSql(sqlBuilder, vendor, "minData", "maxData", true);
            sqlBuilder.append(" FROM (");
            sqlBuilder.append(innerSql);
            sqlBuilder.append(") x ");
            sqlBuilder.append(" JOIN "); // This could be a left join if we want to include NULL dimension values in this query.
            vendor.appendIdentifier(sqlBuilder, METRIC_DIMENSION_TABLE);
            sqlBuilder.append(' ');
            vendor.appendIdentifier(sqlBuilder, "d");
            sqlBuilder.append(" ON (");
            vendor.appendIdentifier(sqlBuilder, "x");
            sqlBuilder.append('.');
            vendor.appendIdentifier(sqlBuilder, METRIC_DIMENSION_FIELD);
            sqlBuilder.append(" = ");
            vendor.appendIdentifier(sqlBuilder, "d");
            sqlBuilder.append('.');
            vendor.appendIdentifier(sqlBuilder, METRIC_DIMENSION_FIELD);
            sqlBuilder.append(')');
            sqlBuilder.append(" GROUP BY ");
            vendor.appendIdentifier(sqlBuilder, "d");
            sqlBuilder.append('.');
            vendor.appendIdentifier(sqlBuilder, METRIC_DIMENSION_VALUE_FIELD);

            return sqlBuilder.toString();
        }

        private static String getTimelineSql(SqlDatabase db, UUID id, UUID typeId, int symbolId, UUID dimensionId, Long minEventDate, Long maxEventDate, MetricInterval metricInterval, boolean doDecodeToBytes) {

            SqlVendor vendor = db.getVendor();

            StringBuilder extraSelectSqlBuilder = new StringBuilder("MIN(");
            vendor.appendMetricSelectTimestampSql(extraSelectSqlBuilder, METRIC_DATA_FIELD);
            extraSelectSqlBuilder.append(") * ");
            vendor.appendValue(extraSelectSqlBuilder, DATE_DECIMAL_SHIFT);
            extraSelectSqlBuilder.append(' ');
            vendor.appendIdentifier(extraSelectSqlBuilder, "eventDate");

            StringBuilder extraGroupBySqlBuilder = new StringBuilder();
            vendor.appendMetricDateFormatTimestampSql(extraGroupBySqlBuilder, METRIC_DATA_FIELD, metricInterval);

            StringBuilder sqlBuilder = new StringBuilder();
            String innerSql = getDataSql(db, id, typeId, symbolId, dimensionId, minEventDate, maxEventDate, true, doDecodeToBytes, extraSelectSqlBuilder.toString(), extraGroupBySqlBuilder.toString(), null);
            sqlBuilder.append(innerSql);
            sqlBuilder.append(" ORDER BY ");
            if (dimensionId == null) {
                vendor.appendIdentifier(sqlBuilder, "dimensionId");
                sqlBuilder.append(", ");
            }
            vendor.appendIdentifier(sqlBuilder, "eventDate");

            return sqlBuilder.toString();
        }

        private static String getUpdateSql(SqlDatabase db, List<Object> parameters, UUID id, UUID typeId, int symbolId, UUID dimensionId, double amount, long eventDate, boolean increment, boolean updateFuture) {
            StringBuilder updateBuilder = new StringBuilder("UPDATE ");
            SqlVendor vendor = db.getVendor();
            vendor.appendIdentifier(updateBuilder, METRIC_TABLE);
            updateBuilder.append(" SET ");

            vendor.appendIdentifier(updateBuilder, METRIC_DATA_FIELD);
            updateBuilder.append(" = ");

            vendor.appendMetricUpdateDataSql(updateBuilder, METRIC_DATA_FIELD, parameters, amount, eventDate, increment, updateFuture);

            updateBuilder.append(" WHERE ");
            vendor.appendIdentifier(updateBuilder, METRIC_ID_FIELD);
            updateBuilder.append(" = ");
            vendor.appendBindValue(updateBuilder, id, parameters);

            updateBuilder.append(" AND ");
            vendor.appendIdentifier(updateBuilder, METRIC_TYPE_FIELD);
            updateBuilder.append(" = ");
            vendor.appendBindValue(updateBuilder, typeId, parameters);

            updateBuilder.append(" AND ");
            vendor.appendIdentifier(updateBuilder, METRIC_SYMBOL_FIELD);
            updateBuilder.append(" = ");
            vendor.appendBindValue(updateBuilder, symbolId, parameters);

            updateBuilder.append(" AND ");
            vendor.appendIdentifier(updateBuilder, METRIC_DIMENSION_FIELD);
            updateBuilder.append(" = ");
            vendor.appendBindValue(updateBuilder, dimensionId, parameters);

            updateBuilder.append(" AND ");
            vendor.appendIdentifier(updateBuilder, METRIC_DATA_FIELD);

            updateBuilder.append(" >= ");
            vendor.appendMetricEncodeTimestampSql(updateBuilder, parameters, eventDate, '0');

            if (!updateFuture) {
                updateBuilder.append(" AND ");
                vendor.appendIdentifier(updateBuilder, METRIC_DATA_FIELD);
                updateBuilder.append(" <= ");
                vendor.appendMetricEncodeTimestampSql(updateBuilder, parameters, eventDate, 'F');
            }

            return updateBuilder.toString();
        }

        private static String getFixDataRowSql(SqlDatabase db, List<Object> parameters, UUID id, UUID typeId, int symbolId, UUID dimensionId, long eventDate, double cumulativeAmount, double amount) {
            StringBuilder updateBuilder = new StringBuilder("UPDATE ");
            SqlVendor vendor = db.getVendor();
            vendor.appendIdentifier(updateBuilder, METRIC_TABLE);
            updateBuilder.append(" SET ");

            vendor.appendIdentifier(updateBuilder, METRIC_DATA_FIELD);
            updateBuilder.append(" = ");

            vendor.appendMetricFixDataSql(updateBuilder, METRIC_DATA_FIELD, parameters, eventDate, cumulativeAmount, amount);

            updateBuilder.append(" WHERE ");
            vendor.appendIdentifier(updateBuilder, METRIC_ID_FIELD);
            updateBuilder.append(" = ");
            vendor.appendBindValue(updateBuilder, id, parameters);

            updateBuilder.append(" AND ");
            vendor.appendIdentifier(updateBuilder, METRIC_TYPE_FIELD);
            updateBuilder.append(" = ");
            vendor.appendBindValue(updateBuilder, typeId, parameters);

            updateBuilder.append(" AND ");
            vendor.appendIdentifier(updateBuilder, METRIC_SYMBOL_FIELD);
            updateBuilder.append(" = ");
            vendor.appendBindValue(updateBuilder, symbolId, parameters);

            updateBuilder.append(" AND ");
            vendor.appendIdentifier(updateBuilder, METRIC_DIMENSION_FIELD);
            updateBuilder.append(" = ");
            vendor.appendBindValue(updateBuilder, dimensionId, parameters);

            updateBuilder.append(" AND ");
            vendor.appendIdentifier(updateBuilder, METRIC_DATA_FIELD);
            updateBuilder.append(" >= ");
            vendor.appendMetricEncodeTimestampSql(updateBuilder, null, eventDate, '0');

            updateBuilder.append(" AND ");
            vendor.appendIdentifier(updateBuilder, METRIC_DATA_FIELD);
            updateBuilder.append(" <= ");
            vendor.appendMetricEncodeTimestampSql(updateBuilder, null, eventDate, 'F');

            return updateBuilder.toString();
        }

        private static String getMetricInsertSql(SqlDatabase db, List<Object> parameters, UUID id, UUID typeId, int symbolId, UUID dimensionId, double amount, double cumulativeAmount, long eventDate) {
            SqlVendor vendor = db.getVendor();
            StringBuilder insertBuilder = new StringBuilder("INSERT INTO ");
            vendor.appendIdentifier(insertBuilder, METRIC_TABLE);
            insertBuilder.append(" (");
            LinkedHashMap<String, Object> cols = new LinkedHashMap<String, Object>();
            cols.put(METRIC_ID_FIELD, id);
            cols.put(METRIC_TYPE_FIELD, typeId);
            cols.put(METRIC_SYMBOL_FIELD, symbolId);
            cols.put(METRIC_DIMENSION_FIELD, dimensionId);
            for (Map.Entry<String, Object> entry : cols.entrySet()) {
                vendor.appendIdentifier(insertBuilder, entry.getKey());
                insertBuilder.append(", ");
            }
            vendor.appendIdentifier(insertBuilder, METRIC_DATA_FIELD);
            insertBuilder.append(") VALUES (");
            for (Map.Entry<String, Object> entry : cols.entrySet()) {
                vendor.appendBindValue(insertBuilder, entry.getValue(), parameters);
                insertBuilder.append(", ");
            }
            vendor.appendBindMetricBytes(insertBuilder, toBytes(eventDate, cumulativeAmount, amount), parameters);
            insertBuilder.append(')');
            return insertBuilder.toString();
        }

        private static String getDeleteMetricSql(SqlDatabase db, UUID id, UUID typeId, int symbolId) {
            SqlVendor vendor = db.getVendor();
            StringBuilder sqlBuilder = new StringBuilder();
            sqlBuilder.append("DELETE FROM ");
            vendor.appendIdentifier(sqlBuilder, METRIC_TABLE);
            sqlBuilder.append(" WHERE ");
            vendor.appendIdentifier(sqlBuilder, METRIC_SYMBOL_FIELD);
            sqlBuilder.append(" = ");
            vendor.appendValue(sqlBuilder, symbolId);
            sqlBuilder.append(" AND ");
            vendor.appendIdentifier(sqlBuilder, METRIC_ID_FIELD);
            sqlBuilder.append(" = ");
            vendor.appendValue(sqlBuilder, id);
            sqlBuilder.append(" AND ");
            vendor.appendIdentifier(sqlBuilder, METRIC_TYPE_FIELD);
            sqlBuilder.append(" = ");
            vendor.appendValue(sqlBuilder, typeId);
            return sqlBuilder.toString();
        }

        private static String getDimensionIdByValueSql(SqlDatabase db, String dimensionValue) {
            SqlVendor vendor = db.getVendor();
            StringBuilder sqlBuilder = new StringBuilder();
            sqlBuilder.append("SELECT ");
            vendor.appendIdentifier(sqlBuilder, METRIC_DIMENSION_FIELD);
            sqlBuilder.append(" FROM ");
            vendor.appendIdentifier(sqlBuilder, METRIC_DIMENSION_TABLE);
            sqlBuilder.append(" WHERE ");
            vendor.appendIdentifier(sqlBuilder, METRIC_DIMENSION_VALUE_FIELD);
            sqlBuilder.append(" = ");
            vendor.appendValue(sqlBuilder, dimensionValue);
            return sqlBuilder.toString();
        }

        private static String getInsertDimensionValueSql(SqlDatabase db, List<Object> parameters, UUID dimensionId, String dimensionValue) {
            SqlVendor vendor = db.getVendor();
            StringBuilder insertBuilder = new StringBuilder("INSERT INTO ");
            vendor.appendIdentifier(insertBuilder, METRIC_DIMENSION_TABLE);
            insertBuilder.append(" (");
            vendor.appendIdentifier(insertBuilder, METRIC_DIMENSION_FIELD);
            insertBuilder.append(", ");
            vendor.appendIdentifier(insertBuilder, METRIC_DIMENSION_VALUE_FIELD);
            insertBuilder.append(") VALUES (");
            vendor.appendBindValue(insertBuilder, dimensionId, parameters);
            insertBuilder.append(", ");
            vendor.appendBindValue(insertBuilder, dimensionValue, parameters);
            insertBuilder.append(')');
            return insertBuilder.toString();
        }

        // Methods that generate complicated bits of SQL

        public static void appendSelectCalculatedAmountSql(StringBuilder str, SqlVendor vendor, String minDataColumnIdentifier, String maxDataColumnIdentifier, boolean includeSum) {

            str.append("ROUND(");
            if (includeSum) {
                str.append("SUM");
            }
            str.append('(');
            vendor.appendMetricSelectAmountSql(str, maxDataColumnIdentifier, CUMULATIVEAMOUNT_POSITION);
            str.append(" - (");
            vendor.appendMetricSelectAmountSql(str, minDataColumnIdentifier, CUMULATIVEAMOUNT_POSITION);
            str.append(" - ");
            vendor.appendMetricSelectAmountSql(str, minDataColumnIdentifier, AMOUNT_POSITION);
            str.append(") ");

            str.append(')');
            str.append(" / ");
            vendor.appendValue(str, AMOUNT_DECIMAL_SHIFT);
            str.append(',');
            vendor.appendValue(str, AMOUNT_DECIMAL_PLACES);
            str.append(") ");

        }

        // methods that convert bytes into values and back again

        private static byte[] toBytes(long eventDate, double cumulativeAmount, double amount) {

            Long cumulativeAmountLong = (long) (cumulativeAmount * AMOUNT_DECIMAL_SHIFT);
            Long amountLong = (long) (amount * AMOUNT_DECIMAL_SHIFT);
            Integer eventDateInt = (int) (eventDate / DATE_DECIMAL_SHIFT);

            int size, offset = 0;
            byte[] bytes = new byte[DATE_BYTE_SIZE + AMOUNT_BYTE_SIZE + AMOUNT_BYTE_SIZE];

            // first 4 bytes: timestamp
            size = DATE_BYTE_SIZE;
            for (int i = 0; i < size; ++i) {
                bytes[i + offset] = (byte) (eventDateInt >> (size - i - 1 << 3));
            }
            offset += size;

            // second 8 bytes: cumulativeAmount
            size = AMOUNT_BYTE_SIZE;
            for (int i = 0; i < size; ++i) {
                bytes[i + offset] = (byte) (cumulativeAmountLong >> (size - i - 1 << 3));
            }
            offset += size;

            // last 8 bytes: amount
            size = AMOUNT_BYTE_SIZE;
            for (int i = 0; i < 8; ++i) {
                bytes[i + offset] = (byte) (amountLong >> (size - i - 1 << 3));
            }

            return bytes;
        }

        private static double amountFromBytes(byte[] bytes, int position) {
            long amountLong = 0;

            int offset = DATE_BYTE_SIZE + ((position - 1) * AMOUNT_BYTE_SIZE);

            for (int i = 0; i < AMOUNT_BYTE_SIZE; ++i) {
                amountLong = (amountLong << 8) | (bytes[i + offset] & 0xff);
            }

            return (double) amountLong / AMOUNT_DECIMAL_SHIFT;
        }

        private static long timestampFromBytes(byte[] bytes) {
            long timestamp = 0;

            for (int i = 0; i < DATE_BYTE_SIZE; ++i) {
                timestamp = (timestamp << 8) | (bytes[i] & 0xff);
            }

            return timestamp * DATE_DECIMAL_SHIFT;
        }

        // methods that actually touch the database

        private static void doIncrementUpdateOrInsert(SqlDatabase db, UUID id, UUID typeId, int symbolId, UUID dimensionId, double incrementAmount, long eventDate, boolean isImplicitEventDate) throws SQLException {
            Connection connection = db.openConnection();
            try {

                if (isImplicitEventDate) {
                    // If they have not passed in an eventDate, we can assume a couple of things:
                    // 1) The event date is the CURRENT date
                    // 2) There is NOT any FUTURE data
                    // 3) We CANNOT assume the row exists
                    List<Object> updateParameters = new ArrayList<Object>();

                    // Try to do an update. This is the best case scenario, and does not require any reads.
                    String updateSql = getUpdateSql(db, updateParameters, id, typeId, symbolId, dimensionId, incrementAmount, eventDate, true, false);
                    int rowsAffected = SqlDatabase.Static.executeUpdateWithList(connection, updateSql, updateParameters);
                    if (0 == rowsAffected) {
                        // There is no data for the current date. Now we have to read
                        // the previous cumulative amount so we can insert a new row.
                        byte[] data = getDataByIdAndDimension(db, id, typeId, symbolId, dimensionId, null, null);
                        double previousCumulativeAmount = 0.0d;
                        if (data != null) {
                            previousCumulativeAmount = amountFromBytes(data, CUMULATIVEAMOUNT_POSITION);
                        }
                        // Try to insert, if that fails then try the update again
                        List<Object> insertParameters = new ArrayList<Object>();
                        String insertSql = getMetricInsertSql(db, insertParameters, id, typeId, symbolId, dimensionId, incrementAmount, previousCumulativeAmount + incrementAmount, eventDate);
                        tryInsertThenUpdate(db, connection, insertSql, insertParameters, updateSql, updateParameters);
                    }

                } else {

                    // First, find the max eventDate. Under normal circumstances, this will either be null (INSERT), before our eventDate (INSERT) or equal to our eventDate (UPDATE).
                    byte[] data = getDataByIdAndDimension(db, id, typeId, symbolId, dimensionId, null, null);

                    if (data == null || timestampFromBytes(data) < eventDate) {
                        // No data for this eventDate; insert.
                        double previousCumulativeAmount = 0.0d;
                        if (data != null) {
                            previousCumulativeAmount = amountFromBytes(data, CUMULATIVEAMOUNT_POSITION);
                        }

                        List<Object> insertParameters = new ArrayList<Object>();
                        String insertSql = getMetricInsertSql(db, insertParameters, id, typeId, symbolId, dimensionId, incrementAmount, previousCumulativeAmount + incrementAmount, eventDate);

                        List<Object> updateParameters = new ArrayList<Object>();
                        String updateSql = getUpdateSql(db, updateParameters, id, typeId, symbolId, dimensionId, incrementAmount, eventDate, true, false);

                        tryInsertThenUpdate(db, connection, insertSql, insertParameters, updateSql, updateParameters);
                    } else if (timestampFromBytes(data) == eventDate) {
                        // There is data for this eventDate; update it.
                        List<Object> updateParameters = new ArrayList<Object>();
                        String updateSql = getUpdateSql(db, updateParameters, id, typeId, symbolId, dimensionId, incrementAmount, eventDate, true, false);
                        SqlDatabase.Static.executeUpdateWithList(connection, updateSql, updateParameters);
                    } else { // if (timestampFromBytes(data) > eventDate)
                        // The max(eventDate) in the table is greater than our
                        // event date. If there exists a row in the past, UPDATE it
                        // or if not, INSERT. Either way we will be updating future
                        // data, so just INSERT with a value of 0 if necessary, then
                        // UPDATE all rows.
                        byte[] oldData = getDataByIdAndDimension(db, id, typeId, symbolId, dimensionId, null, eventDate);
                        if (oldData == null || timestampFromBytes(oldData) < eventDate) {
                            double previousCumulativeAmount = 0.0d;
                            if (oldData != null) {
                                previousCumulativeAmount = amountFromBytes(oldData, CUMULATIVEAMOUNT_POSITION);
                            }
                            List<Object> insertParameters = new ArrayList<Object>();
                            String insertSql = getMetricInsertSql(db, insertParameters, id, typeId, symbolId, dimensionId, 0, previousCumulativeAmount, eventDate);

                            tryInsertThenUpdate(db, connection, insertSql, insertParameters, null, null); // the UPDATE is going to be executed regardless of whether this fails - it's only inserting 0 anyway.
                        }
                        // Now update all the future rows.
                        List<Object> updateParameters = new ArrayList<Object>();
                        String updateSql = getUpdateSql(db, updateParameters, id, typeId, symbolId, dimensionId, incrementAmount, eventDate, true, true);
                        SqlDatabase.Static.executeUpdateWithList(connection, updateSql, updateParameters);
                    }
                }

            } finally {
                db.closeConnection(connection);
            }
        }

        // This is for the occasional race condition when we check for the existence of a row, it does not exist, then two threads try to insert at (almost) the same time.
        private static void tryInsertThenUpdate(SqlDatabase db, Connection connection, String insertSql, List<Object> insertParameters, String updateSql, List<Object> updateParameters) throws SQLException {
            try {
                SqlDatabase.Static.executeUpdateWithList(connection, insertSql, insertParameters);
            } catch (SQLException ex) {
                if (db.getVendor().isDuplicateKeyException(ex)) {
                    // Try the update again, maybe we lost a race condition.
                    if (updateSql != null) {
                        int rowsAffected = SqlDatabase.Static.executeUpdateWithList(connection, updateSql, updateParameters);
                        if (1 != rowsAffected) {
                            // If THAT didn't work, of we somehow updated more than one row, just throw the original exception again; it is a legitimate unique key violation
                            throw ex;
                        }
                    }
                } else {
                    throw ex;
                }
            }
        }

        private static void doSetUpdateOrInsert(SqlDatabase db, UUID id, UUID typeId, int symbolId, UUID dimensionId, double amount, long eventDate) throws SQLException {
            Connection connection = db.openConnection();
            if (eventDate != 0L) {
                throw new RuntimeException("MetricDatabase.Static.doSetUpdateOrInsert() can only be used if EventDatePrecision is NONE; eventDate is " + eventDate + ", should be 0L.");
            }
            try {
                List<Object> parameters = new ArrayList<Object>();
                String sql = getUpdateSql(db, parameters, id, typeId, symbolId, dimensionId, amount, eventDate, false, false);
                int rowsAffected = SqlDatabase.Static.executeUpdateWithList(connection, sql, parameters);
                if (rowsAffected == 0) {
                    parameters = new ArrayList<Object>();
                    sql = getMetricInsertSql(db, parameters, id, typeId, symbolId, dimensionId, amount, amount, eventDate);
                    SqlDatabase.Static.executeUpdateWithList(connection, sql, parameters);
                }
            } finally {
                db.closeConnection(connection);
            }
        }

        static void doMetricDelete(SqlDatabase db, UUID id, UUID typeId, int symbolId) throws SQLException {
            Connection connection = db.openConnection();
            List<Object> parameters = new ArrayList<Object>();
            try {
                String sql = getDeleteMetricSql(db, id, typeId, symbolId);
                SqlDatabase.Static.executeUpdateWithList(connection, sql, parameters);
            } finally {
                db.closeConnection(connection);
            }
        }

        static void doInsertDimensionValue(SqlDatabase db, UUID dimensionId, String dimensionValue) throws SQLException {
            Connection connection = db.openConnection();
            List<Object> parameters = new ArrayList<Object>();
            try {
                String sql = getInsertDimensionValueSql(db, parameters, dimensionId, dimensionValue);
                SqlDatabase.Static.executeUpdateWithList(connection, sql, parameters);
            } finally {
                db.closeConnection(connection);
            }
        }

        static void doFixDataRow(SqlDatabase db, UUID id, UUID typeId, int symbolId, UUID dimensionId, long eventDate, double cumulativeAmount, double amount) throws SQLException {
            Connection connection = db.openConnection();
            List<Object> parameters = new ArrayList<Object>();
            try {
                String updateSql = getFixDataRowSql(db, parameters, id, typeId, symbolId, dimensionId, eventDate, cumulativeAmount, amount);
                SqlDatabase.Static.executeUpdateWithList(connection, updateSql, parameters);
            } finally {
                db.closeConnection(connection);
            }
        }

        static void doReconstructCumulativeAmounts(SqlDatabase db, UUID id, UUID typeId, int symbolId, Long minEventDate) throws SQLException {

            // for each row, ordered by date, keep a running total of amount and update it into cumulativeAmount
            String selectSql = getAllDataSql(db, id, typeId, symbolId, null, minEventDate, null, true);
            Connection connection = db.openConnection();
            try {
                Statement statement = connection.createStatement();
                try {
                    ResultSet result = db.executeQueryBeforeTimeout(statement, selectSql, QUERY_TIMEOUT);
                    try {
                        UUID lastDimensionId = null;
                        double correctCumAmt = 0, calcAmt = 0, amt = 0, cumAmt = 0, lastCorrectCumAmt = 0;
                        long timestamp = 0;
                        while (result.next()) {
                            UUID dimensionId = UuidUtils.fromBytes(result.getBytes(1));
                            if (lastDimensionId == null || !dimensionId.equals(lastDimensionId)) {
                                // new dimension, reset the correctCumAmt. This depends
                                // on getAllDataSql ordering by dimensionId, data.
                                correctCumAmt = 0;
                                lastCorrectCumAmt = 0;
                            }
                            lastDimensionId = dimensionId;

                            byte[] data = result.getBytes(2);
                            amt = amountFromBytes(data, AMOUNT_POSITION);
                            cumAmt = amountFromBytes(data, CUMULATIVEAMOUNT_POSITION);
                            timestamp = timestampFromBytes(data);

                            // if this amount is not equal to this cumulative amount
                            // minus the previous CORRECT cumulative amount, adjust
                            // this cumulative amount UPWARDS OR DOWNWARDS to match it.
                            calcAmt = cumAmt - lastCorrectCumAmt;
                            if (calcAmt != amt) {
                                correctCumAmt = lastCorrectCumAmt + amt;
                            } else {
                                correctCumAmt = cumAmt;
                            }

                            if (correctCumAmt != cumAmt) {
                                doFixDataRow(db, id, typeId, symbolId, dimensionId, timestamp, correctCumAmt, amt);
                            }

                            lastCorrectCumAmt = correctCumAmt;
                        }
                    } finally {
                        result.close();
                    }
                } finally {
                    statement.close();
                }
            } finally {
                db.closeConnection(connection);
            }

        }

        // METRIC SELECT
        private static Double calculateMetricSumById(SqlDatabase db, UUID id, UUID typeId, int symbolId, Long minEventDate, Long maxEventDate) throws SQLException {
            // This method actually calculates the sum rather than just pulling the null dimension
            String sql = getSumSql(db, id, typeId, symbolId, minEventDate, maxEventDate);
            Double amount = null;
            Connection connection = db.openReadConnection();
            try {
                Statement statement = connection.createStatement();
                try {
                    ResultSet result = db.executeQueryBeforeTimeout(statement, sql, QUERY_TIMEOUT);
                    try {
                        if (result.next()) {
                            amount = result.getDouble(1);
                        }
                    } finally {
                        result.close();
                    }
                } finally {
                    statement.close();
                }
            } finally {
                db.closeConnection(connection);
            }
            return amount;
        }

        private static Map<String, Double> getMetricDimensionsById(SqlDatabase db, UUID id, UUID typeId, int symbolId, Long minEventDate, Long maxEventDate) throws SQLException {
            String sql = getDimensionsSql(db, id, typeId, symbolId, minEventDate, maxEventDate);
            Map<String, Double> values = new HashMap<String, Double>();
            Connection connection = db.openReadConnection();
            try {
                Statement statement = connection.createStatement();
                try {
                    ResultSet result = db.executeQueryBeforeTimeout(statement, sql, QUERY_TIMEOUT);
                    try {
                        while (result.next()) {
                            values.put(result.getString(1), result.getDouble(2));
                        }
                    } finally {
                        result.close();
                    }
                } finally {
                    statement.close();
                }
            } finally {
                db.closeConnection(connection);
            }
            return values;
        }

        private static Map<DateTime, Double> getMetricTimelineByIdAndDimension(SqlDatabase db, UUID id, UUID typeId, int symbolId, UUID dimensionId, Long minEventDate, Long maxEventDate, MetricInterval metricInterval) throws SQLException {
            String sql = getTimelineSql(db, id, typeId, symbolId, dimensionId, minEventDate, maxEventDate, metricInterval, true);
            Map<DateTime, Double> values = new LinkedHashMap<DateTime, Double>();
            Connection connection = db.openReadConnection();
            try {
                Statement statement = connection.createStatement();
                try {
                    ResultSet result = db.executeQueryBeforeTimeout(statement, sql, QUERY_TIMEOUT);
                    try {
                        while (result.next()) {
                            byte[] maxData = result.getBytes(1);
                            byte[] minData = result.getBytes(2);
                            long timestamp = result.getLong(3);
                            timestamp = metricInterval.process(new DateTime(timestamp));
                            double maxCumulativeAmount = amountFromBytes(maxData, CUMULATIVEAMOUNT_POSITION);
                            double minCumulativeAmount = amountFromBytes(minData, CUMULATIVEAMOUNT_POSITION);
                            double minAmount = amountFromBytes(minData, AMOUNT_POSITION);
                            double intervalAmount = maxCumulativeAmount - (minCumulativeAmount - minAmount);
                            values.put(new DateTime(timestamp), intervalAmount);
                        }
                    } finally {
                        result.close();
                    }
                } finally {
                    statement.close();
                }
            } finally {
                db.closeConnection(connection);
            }
            return values;
        }

        private static byte[] getDataByIdAndDimension(SqlDatabase db, UUID id, UUID typeId, int symbolId, UUID dimensionId, Long minEventDate, Long maxEventDate) throws SQLException {
            String sql = getDataSql(db, id, typeId, symbolId, dimensionId, minEventDate, maxEventDate, false, true, null, null, null);
            byte[] data = null;
            Connection connection = db.openReadConnection();
            try {
                Statement statement = connection.createStatement();
                try {
                    ResultSet result = db.executeQueryBeforeTimeout(statement, sql, QUERY_TIMEOUT);
                    try {
                        if (result.next()) {
                            data = result.getBytes(1);
                        }
                    } finally {
                        result.close();
                    }
                } finally {
                    statement.close();
                }
            } finally {
                db.closeConnection(connection);
            }
            return data;
        }

        private static List<byte[]> getMaxMinDataByIdAndDimension(SqlDatabase db, UUID id, UUID typeId, int symbolId, UUID dimensionId, Long minEventDate, Long maxEventDate) throws SQLException {
            List<byte[]> datas = new ArrayList<byte[]>();
            String sql = getDataSql(db, id, typeId, symbolId, dimensionId, minEventDate, maxEventDate, true, true, null, null, null);
            Connection connection = db.openReadConnection();
            try {
                Statement statement = connection.createStatement();
                try {
                    ResultSet result = db.executeQueryBeforeTimeout(statement, sql, QUERY_TIMEOUT);
                    try {
                        if (result.next()) {
                            datas.add(result.getBytes(1));
                            datas.add(result.getBytes(2));
                        }
                    } finally {
                        result.close();
                    }
                } finally {
                    statement.close();
                }
            } finally {
                db.closeConnection(connection);
            }
            return datas;
        }

        private static UUID getDimensionIdByValue(SqlDatabase db, String dimensionValue) throws SQLException {
            String sql = getDimensionIdByValueSql(db, dimensionValue);
            Connection connection = db.openReadConnection();
            try {
                Statement statement = connection.createStatement();
                try {
                    ResultSet result = db.executeQueryBeforeTimeout(statement, sql, QUERY_TIMEOUT);
                    try {
                        if (result.next()) {
                            return db.getVendor().getUuid(result, 1);
                        }
                    } finally {
                        result.close();
                    }
                } finally {
                    statement.close();
                }
            } finally {
                db.closeConnection(connection);
            }
            return null;
        }

        public static void preFetchMetricSums(UUID id, UUID dimensionId, Long startTimestamp, Long endTimestamp, Collection<MetricDatabase> metricDatabases) throws SQLException {
            if (metricDatabases.isEmpty()) return;
            CachingDatabase cachingDb = getCachingDatabase();
            if (cachingDb == null) return;
            Iterator<MetricDatabase> iter = metricDatabases.iterator();
            MetricDatabase mdb = iter.next();
            UUID typeId = mdb.getTypeId();
            SqlDatabase db = mdb.getDatabase();
            Map<Integer, MetricDatabase> mdbBySymbolId = new HashMap<Integer, MetricDatabase>();
            StringBuilder symbolIdsString = new StringBuilder();
            do {
                symbolIdsString.append(mdb.getSymbolId());
                symbolIdsString.append(',');
                mdbBySymbolId.put(mdb.getSymbolId(), mdb);
            } while (iter.hasNext() && (mdb = iter.next()) != null);
            symbolIdsString.setLength(symbolIdsString.length()-1);

            boolean selectMinData = true;
            if (startTimestamp == null) {
                selectMinData = false;
            }
            String extraSelectSql = METRIC_SYMBOL_FIELD;
            String extraGroupBySql = METRIC_SYMBOL_FIELD;
            String extraWhereSql = METRIC_SYMBOL_FIELD + " IN (" + symbolIdsString.toString() + ")";

            String sql = getDataSql(db, id, typeId, null, dimensionId, startTimestamp, endTimestamp, selectMinData, true, extraSelectSql, extraGroupBySql, extraWhereSql);

            Connection connection = db.openReadConnection();
            try {
                Statement statement = connection.createStatement();
                try {
                    ResultSet result = db.executeQueryBeforeTimeout(statement, sql, QUERY_TIMEOUT);
                    try {
                        while (result.next()) {

                            byte[] maxData = result.getBytes(1);
                            byte[] minData = null;
                            int symbolId;
                            if (selectMinData) {
                                minData = result.getBytes(2);
                                symbolId = result.getInt(3);
                            } else {
                                minData = null;
                                symbolId = result.getInt(2);
                            }
                            MetricDatabase metricDb = mdbBySymbolId.get(symbolId);
                            if (selectMinData) {
                                metricDb.putCachedData(cachingDb, id, dimensionId, startTimestamp, minData);
                            }
                            metricDb.putCachedData(cachingDb, id, dimensionId, endTimestamp, maxData);
                            metricDatabases.remove(metricDb);
                        }
                    } finally {
                        result.close();
                    }
                } finally {
                    statement.close();
                }
            } finally {
                db.closeConnection(connection);
            }

            // If we did not find data, we still need to cache that fact.
            iter = metricDatabases.iterator();
            while (iter.hasNext()) {
                MetricDatabase metricDb = iter.next();
                if (selectMinData) {
                    metricDb.putCachedData(cachingDb, id, dimensionId, startTimestamp, null);
                }
                metricDb.putCachedData(cachingDb, id, dimensionId, endTimestamp, null);
            }

        }

        public static MetricDatabase getMetricDatabase(State state, ObjectField field) {
            if (state == null || field == null) return null;
            Database db = state.getDatabase();
            if (db == null) return null;
            StringBuilder keyBuilder = new StringBuilder(db.getName());
            keyBuilder.append(':');
            keyBuilder.append(field.getUniqueName());
            keyBuilder.append(':');
            keyBuilder.append(field.as(MetricDatabase.FieldData.class).getEventDateProcessorClassName());
            String mdbKey = keyBuilder.toString();
            MetricDatabase metricDb = metricDatabases.get(mdbKey);
            if (metricDb == null) {
                SqlDatabase sqlDb = null;
                while (db instanceof ForwardingDatabase) {
                    db = ((ForwardingDatabase) db).getDelegate();
                }
                if (db instanceof SqlDatabase) {
                    sqlDb = (SqlDatabase) db;
                } else if (db instanceof AggregateDatabase) {
                    if (((AggregateDatabase) db).getDefaultDelegate() instanceof SqlDatabase) {
                        sqlDb = (SqlDatabase) ((AggregateDatabase) db).getDefaultDelegate();
                    } else {
                        sqlDb = (SqlDatabase) ((AggregateDatabase) db).getFirstDelegateByClass(SqlDatabase.class);
                    }
                }
                if (sqlDb != null) {
                    metricDb = new MetricDatabase(sqlDb, state.getTypeId(), field.getUniqueName(), field.as(MetricDatabase.FieldData.class).getEventDateProcessor());
                    metricDatabases.put(mdbKey, metricDb);
                }
            }
            return metricDb;
        }

        public static CachingDatabase getCachingDatabase() {
            Database db = Database.Static.getDefault();
            while (db instanceof ForwardingDatabase) {
                if (db instanceof CachingDatabase) {
                    return (CachingDatabase) db;
                }
                db = ((ForwardingDatabase) db).getDelegate();
            }
            return null;
        }


    }

    // MODIFICATIONS

    @Record.FieldInternalNamePrefix("dari.metric.")
    public static class FieldData extends Modification<ObjectField> {

        private transient MetricInterval eventDateProcessor;

        private boolean metricValue;
        private String eventDateProcessorClassName;

        public boolean isMetricValue() {
            return metricValue;
        }

        public void setMetricValue(boolean metricValue) {
            this.metricValue = metricValue;
        }

        public String getEventDateProcessorClassName() {
            return eventDateProcessorClassName;
        }

        @SuppressWarnings("unchecked")
        public MetricInterval getEventDateProcessor() {
            if (eventDateProcessor == null) {
                if (eventDateProcessorClassName == null) {
                    return null;
                } else {
                    try {
                        Class<MetricInterval> cls = (Class<MetricInterval>) Class.forName(eventDateProcessorClassName);
                        eventDateProcessor = cls.newInstance();
                    } catch (ClassNotFoundException e) {
                        throw new RuntimeException(e);
                    } catch (InstantiationException e) {
                        throw new RuntimeException(e);
                    } catch (IllegalAccessException e) {
                        throw new RuntimeException(e);
                    }
                }
            }
            return eventDateProcessor;
        }

        public void setEventDateProcessorClass(Class<? extends MetricInterval> eventDateProcessorClass) {
            this.eventDateProcessorClassName = eventDateProcessorClass.getName();
        }

    }
}
