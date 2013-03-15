package com.psddev.dari.db;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

//import org.slf4j.Logger;
//import org.slf4j.LoggerFactory;

import com.psddev.dari.util.UuidUtils;

public class CountRecord {

    //static final Logger LOGGER = LoggerFactory.getLogger(CountRecord.class);

    static final String COUNTRECORD_TABLE = "CountRecord";
    static final String COUNTRECORD_STRINGINDEX_TABLE = "CountRecordString";
    static final String COUNTRECORD_NUMBERINDEX_TABLE = "CountRecordNumber";
    static final String COUNTRECORD_UUIDINDEX_TABLE = "CountRecordUuid";
    static final String COUNTRECORD_LOCATIONINDEX_TABLE = "CountRecordLocation";
    static final String COUNTRECORD_COUNTID_FIELD = "countId";
    static final String COUNTRECORD_DATA_FIELD = "data";
    static final int QUERY_TIMEOUT = 3;
    static final int AMOUNT_DECIMAL_PLACES = 6;
    static final long AMOUNT_DECIMAL_SHIFT = (long) Math.pow(10, AMOUNT_DECIMAL_PLACES);
    static final long DATE_DECIMAL_SHIFT = 60000L;
    static final int DATE_BYTE_SIZE = 4;
    static final int AMOUNT_BYTE_SIZE = 8;
    static final int CUMULATIVEAMOUNT_POSITION = 1;
    static final int AMOUNT_POSITION = 2;

    public static enum EventDatePrecision {
        HOUR,
        DAY,
        WEEK,
        WEEK_SUNDAY,
        WEEK_MONDAY,
        MONTH,
        YEAR,
        NONE
    }

    private final DimensionSet dimensions;
    private final String dimensionsSymbol;
    private final SqlDatabase db; 
    private final CountRecordQuery query;
    private final Record record;

    private EventDatePrecision eventDatePrecision = EventDatePrecision.NONE;
    private UUID countId;

    private Long updateDate;
    private Long eventDate;
    private Boolean dimensionsSaved;
    private ObjectField countField;

    public CountRecord(SqlDatabase database, Record record, String actionSymbol, Set<ObjectField> dimensions) {
        this.dimensions = DimensionSet.createDimensionSet(dimensions, record);
        this.dimensionsSymbol = this.getDimensionsSymbol(); // requires this.dimensions
        this.db = database; 
        this.query = new CountRecordQuery(dimensionsSymbol, actionSymbol, record, this.dimensions);
        this.record = record;
    }

    public CountRecord(Record record, String actionSymbol, Set<ObjectField> dimensions) {
        this(Database.Static.getFirst(SqlDatabase.class), record, actionSymbol, dimensions);
    }

    public void setSummaryField(ObjectField countField) {
        this.countField = countField;
    }

    public void setEventDatePrecision(EventDatePrecision precision) {
        this.eventDatePrecision = precision;
    }

    public Record getRecord() {
        return record;
    }

    public EventDatePrecision getEventDatePrecision() {
        return this.eventDatePrecision;
    }

    public SqlDatabase getDatabase() {
        return db;
    }

    public CountRecordQuery getQuery() {
        return query;
    }

    public void setUpdateDate(long timestampMillis) {
        this.updateDate = timestampMillis;
    }

    public long getUpdateDate() {
        if (this.updateDate == null) {
            this.updateDate = System.currentTimeMillis();
        }
        return this.updateDate;
    }

    // This method will strip the minutes and seconds off of a timestamp
    public void setEventDate(long timestampMillis) {
        Calendar c = Calendar.getInstance();
        c.clear();
        c.setTimeInMillis(timestampMillis);
        c.set(Calendar.MILLISECOND, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MINUTE, 0);
        switch (getEventDatePrecision()) {
            case HOUR: // leave hour/day/month/year
                break;
            case DAY: // leave day/month/year, set hour to 0
                c.set(Calendar.HOUR_OF_DAY, 0);
                break;
            case WEEK_SUNDAY: // leave month/year, set day to sunday of this week
                c.set(Calendar.HOUR_OF_DAY, 0);
                while (c.get(Calendar.DAY_OF_WEEK) != Calendar.SUNDAY)
                  c.add(Calendar.DATE, -1);
                break;
            case WEEK: // same as WEEK_MONDAY
            case WEEK_MONDAY: // leave month/year, set day to monday of this week
                c.set(Calendar.HOUR_OF_DAY, 0);
                while (c.get(Calendar.DAY_OF_WEEK) != Calendar.MONDAY)
                  c.add(Calendar.DATE, -1);
                break;
            case MONTH: // leave month/year, set day to 1st day of this month
                c.set(Calendar.HOUR_OF_DAY, 0);
                c.set(Calendar.DAY_OF_MONTH, 1);
                break;
            case YEAR: // leave year, set month to 1st month of this year
                c.set(Calendar.HOUR_OF_DAY, 0);
                c.set(Calendar.DAY_OF_MONTH, 1);
                c.set(Calendar.MONTH, Calendar.JANUARY);
                break;
            case NONE: // not tracking dates at all - set to 0
                c.setTimeInMillis(0);
                break;
        }
        this.eventDate = (c.getTimeInMillis());
    }

    public long getEventDate() {
        if (this.eventDate == null) {
            setEventDate((System.currentTimeMillis()));
        }
        return this.eventDate;
    }

    public void setIncludeSelfDimension(boolean includeSelfDimension) {
        getQuery().setIncludeSelfDimension(includeSelfDimension);
    }

    public void setQueryDateRange(Long startTimestamp, Long endTimestamp) {
        getQuery().setDateRange(startTimestamp, endTimestamp);
    }

    public Double getCount() throws SQLException {
        if (dimensionsSaved != null && dimensionsSaved == true) {
            // We already know we're dealing with exactly 1 countId, so look it up directly.
            return Static.getCountByCountId(getDatabase(), getCountId(), getQuery().getActionSymbol(), getQuery().getStartTimestamp(), getQuery().getEndTimestamp());
        } else {
            return Static.getCountByDimensions(getDatabase(), getQuery());
        }
    }

    public void incrementCount(Double amount) throws SQLException {
        // find the countId, it might be null
        if (amount == 0) return; // This actually causes some problems if it's not here
        UUID countId = getCountId();
        if (dimensionsSaved) {
            Static.doIncrementUpdateOrInsert(getDatabase(), countId, this.getRecordIdForInsert(), this.record.getState().getTypeId(), getQuery().getActionSymbol(), getDimensionsSymbol(), amount, getUpdateDate(), getEventDate());
        } else {
            Static.doInserts(getDatabase(), countId, this.getRecordIdForInsert(), this.record.getState().getTypeId(), getQuery().getActionSymbol(), getDimensionsSymbol(), dimensions, amount, getUpdateDate(), getEventDate());
            dimensionsSaved = true;
        }
        if (isSummaryPossible()) {
            Static.doIncrementCountSummaryUpdateOrInsert(getDatabase(), amount, this.countField, this.getRecordIdForInsert());
        }
    }

    public void setCount(Double amount) throws SQLException {
        // This only works if we're not tracking eventDate
        if (getEventDatePrecision() != EventDatePrecision.NONE) {
            throw new RuntimeException("CountRecord.setCount() can only be used if EventDatePrecision is NONE");
        }
        // find the countId, it might be null
        UUID countId = getCountId();
        if (dimensionsSaved) {
            Static.doSetUpdateOrInsert(getDatabase(), countId, this.getRecordIdForInsert(), this.record.getState().getTypeId(), getQuery().getActionSymbol(),
                    getDimensionsSymbol(), amount, getUpdateDate(),
                    getEventDate());
        } else {
            Static.doInserts(getDatabase(), countId, this.getRecordIdForInsert(), this.record.getState().getTypeId(), getQuery().getActionSymbol(), getDimensionsSymbol(),
                    dimensions, amount, getUpdateDate(), getEventDate());
            dimensionsSaved = true;
        }
        if (isSummaryPossible()) {
            Static.doIncrementCountSummaryUpdateOrInsert(getDatabase(), amount, this.countField, this.getRecordIdForInsert());
        }
    }

    /** This only needs to be executed if the summary has fallen out of sync due to data model change or some other operation */
    public void syncCountSummary() throws SQLException {
        if (isSummaryPossible()) {
            Static.doSetCountSummaryUpdateOrInsert(getDatabase(), getCount(), this.countField, this.getRecordIdForInsert());
        }
    }

    public void deleteCount() throws SQLException {
        Static.doCountDelete(getDatabase(), this.getRecordIdForInsert(), getQuery().getDimensions(), getQuery().getActionSymbol());
        if (isSummaryPossible()) {
            Static.doCountSummaryDelete(getDatabase(), this.getRecordIdForInsert(), this.countField);
        }
    }

    private UUID getRecordIdForInsert() {
        return this.getQuery().getRecordIdForInsert();
    }

    private boolean isSummaryPossible() {
        SqlDatabase.FieldData fieldData = countField.as(SqlDatabase.FieldData.class);
        String indexTable = fieldData.getIndexTable();
        //Boolean isReadOnly = fieldData.isIndexTableReadOnly();
        Boolean isSource = fieldData.isIndexTableSource();
        if (indexTable != null &&
                query.isIncludeSelfDimension() &&
                /*isReadOnly &&*/
                isSource) {
            return true;
        } else {
            return false;
        }
    }

    public UUID getCountId() throws SQLException {
        if (countId == null) {
            countId = Static.getCountIdByDimensions(getDatabase(), getQuery());
            if (countId == null) {
                // create a new countId
                dimensionsSaved = false;
                countId = UuidUtils.createSequentialUuid();
            } else {
                // this countId came from the DB
                dimensionsSaved = true;
            }
        }
        return countId;
    }

    public String getDimensionsSymbol() {
        if (this.dimensionsSymbol != null) {
            return dimensionsSymbol;
        } else {
            return this.dimensions.getSymbol();
        }
    }

    /** {@link CountRecord} utility methods. */
    public static final class Static {

        private Static() {
        }

        static Set<String> getIndexTables(DimensionSet... dimensionSets) {
            LinkedHashSet<String> tables = new LinkedHashSet<String>();
            for (DimensionSet dimensions : dimensionSets) {
                if (dimensions != null) {
                    for (Dimension dimension : dimensions) {
                        tables.add(dimension.getIndexTable());
                    }
                }
            }
            return tables;
        }

        static String getIndexTable(ObjectField field) {
            String fieldType = field.getInternalItemType();
            if (fieldType.equals(ObjectField.UUID_TYPE)) {
                return CountRecord.COUNTRECORD_UUIDINDEX_TABLE;
            } else if (fieldType.equals(ObjectField.LOCATION_TYPE)) {
                return CountRecord.COUNTRECORD_LOCATIONINDEX_TABLE;
            } else if (fieldType.equals(ObjectField.NUMBER_TYPE) || 
                    fieldType.equals(ObjectField.DATE_TYPE)) {
                return CountRecord.COUNTRECORD_NUMBERINDEX_TABLE;
            } else {
                return CountRecord.COUNTRECORD_STRINGINDEX_TABLE;
            }
        }

        static DimensionSet getDimensionsByIndexTable(String table, DimensionSet dimensions) {
            //HashMap<String, Object> dims = new HashMap<String, Object>();
            DimensionSet dims = new DimensionSet();
            for (Dimension dimension : dimensions) {
                if (table.equals(dimension.getIndexTable())) {
                    dims.add(dimension);
                }
            }
            return dims;
        }

        static List<String> getInsertSqls(SqlDatabase db, List<List<Object>> parametersList, UUID countId, UUID recordId, UUID typeId, String actionSymbol, String dimensionsSymbol, DimensionSet dimensions, double amount, long createDate, long eventDate) {
            ArrayList<String> sqls = new ArrayList<String>();
            // insert countrecord
            List<Object> parameters = new ArrayList<Object>();
            sqls.add(getCountRecordInsertSql(db, parameters, countId, recordId, typeId, actionSymbol, dimensionsSymbol, amount, amount, createDate, eventDate));
            parametersList.add(parameters);
            // insert indexes
            for (Dimension dimension : dimensions) {
                Set<Object> values = dimension.getValues();
                String table = dimension.getIndexTable();
                for (Object value : values) {
                    parameters = new ArrayList<Object>();
                    sqls.add(getDimensionInsertRowSql(db, parameters, countId, recordId, dimensionsSymbol, dimension, value, table));
                    parametersList.add(parameters);
                }
            }
            return sqls;
        }

        // methods that generate SQL

        static String getDataByCountIdSql(SqlDatabase db, UUID countId, String actionSymbol, Long minEventDate, Long maxEventDate, boolean selectMinData) {
            StringBuilder sqlBuilder = new StringBuilder();
            SqlVendor vendor = db.getVendor();

            sqlBuilder.append("SELECT ");

            sqlBuilder.append("MAX(");
            vendor.appendIdentifier(sqlBuilder, COUNTRECORD_DATA_FIELD);
            sqlBuilder.append(") ");
            vendor.appendIdentifier(sqlBuilder, "maxData");

            if (selectMinData) {
                sqlBuilder.append(", MIN(");
                vendor.appendIdentifier(sqlBuilder, COUNTRECORD_DATA_FIELD);
                sqlBuilder.append(") ");
                vendor.appendIdentifier(sqlBuilder, "minData");
            }

            sqlBuilder.append(" FROM ");
            vendor.appendIdentifier(sqlBuilder, COUNTRECORD_TABLE);
            sqlBuilder.append(" WHERE ");
            vendor.appendIdentifier(sqlBuilder, COUNTRECORD_COUNTID_FIELD);
            sqlBuilder.append(" = ");
            vendor.appendValue(sqlBuilder, countId);

            sqlBuilder.append(" AND ");
            vendor.appendIdentifier(sqlBuilder, "actionSymbolId");
            sqlBuilder.append(" = ");
            vendor.appendValue(sqlBuilder, db.getSymbolId(actionSymbol));

            if (maxEventDate != null) {
                sqlBuilder.append(" AND ");
                vendor.appendIdentifier(sqlBuilder, COUNTRECORD_DATA_FIELD);
                sqlBuilder.append(" <= ");
                sqlBuilder.append(" UNHEX(");
                appendHexEncodeTimestampSql(sqlBuilder, null, vendor, maxEventDate, 'F');
                sqlBuilder.append(")");
            }

            if (minEventDate != null) {
                sqlBuilder.append(" AND ");
                vendor.appendIdentifier(sqlBuilder, COUNTRECORD_DATA_FIELD);
                sqlBuilder.append(" >= ");
                sqlBuilder.append(" UNHEX(");
                appendHexEncodeTimestampSql(sqlBuilder, null, vendor, minEventDate, '0');
                sqlBuilder.append(")");
            }

            return sqlBuilder.toString();
        }

        private static void appendSelectAmountSql(StringBuilder str, SqlVendor vendor, String columnIdentifier, int position) {
            // This does NOT shift the decimal place or round to 6 places. Do it yourself AFTER any other arithmetic.
            // position is 1 or 2
            // columnIdentifier is "`data`" or "MAX(`data`)" - already escaped
            str.append("CONV(");
                str.append("HEX(");
                    str.append("SUBSTR(");
                        str.append(columnIdentifier);
                        str.append(",");
                        vendor.appendValue(str, 1+DATE_BYTE_SIZE + ((position-1)*AMOUNT_BYTE_SIZE));
                        str.append(",");
                        vendor.appendValue(str, AMOUNT_BYTE_SIZE);
                    str.append(")");
                str.append(")");
            str.append(", 16, 10)");
        }

        static String getCountRecordInsertSql(SqlDatabase db, List<Object> parameters, UUID countId, UUID recordId, UUID typeId, String actionSymbol, String dimensionsSymbol, double amount, double cumulativeAmount, long createDate, long eventDate) {
            SqlVendor vendor = db.getVendor();
            StringBuilder insertBuilder = new StringBuilder("INSERT INTO ");
            vendor.appendIdentifier(insertBuilder, COUNTRECORD_TABLE);
            insertBuilder.append(" (");
            LinkedHashMap<String, Object> cols = new LinkedHashMap<String, Object>();
            cols.put(COUNTRECORD_COUNTID_FIELD, countId);
            cols.put("id", recordId);
            cols.put("typeId", typeId);
            cols.put("actionSymbolId", db.getSymbolId(actionSymbol));
            cols.put("dimensionsSymbolId", db.getSymbolId(dimensionsSymbol));
            cols.put("createDate", createDate);
            cols.put("updateDate", createDate);
            cols.put(COUNTRECORD_DATA_FIELD, toBytes(eventDate, cumulativeAmount, amount));
            for (Map.Entry<String, Object> entry : cols.entrySet()) {
                vendor.appendIdentifier(insertBuilder, entry.getKey());
                insertBuilder.append(", ");
            }
            insertBuilder.setLength(insertBuilder.length()-2);
            insertBuilder.append(") VALUES (");
            for (Map.Entry<String, Object> entry : cols.entrySet()) {
                vendor.appendBindValue(insertBuilder, entry.getValue(), parameters);
                insertBuilder.append(", ");
            }
            insertBuilder.setLength(insertBuilder.length()-2);
            insertBuilder.append(")");
            return insertBuilder.toString();
        }

        static byte[] toBytes(long eventDate, double cumulativeAmount, double amount) {

            Long cumulativeAmountLong = (long) (cumulativeAmount * AMOUNT_DECIMAL_SHIFT);
            Long amountLong = (long) (amount * AMOUNT_DECIMAL_SHIFT);
            Integer eventDateInt = (int) (eventDate / DATE_DECIMAL_SHIFT);

            int size, offset = 0;
            byte[] bytes = new byte[DATE_BYTE_SIZE+AMOUNT_BYTE_SIZE+AMOUNT_BYTE_SIZE];

            // first 4 bytes: timestamp
            size = DATE_BYTE_SIZE;
            for (int i = 0; i < size; ++i) {
                bytes[i+offset] = (byte) (eventDateInt >> (size - i - 1 << 3));
            }
            offset += size;

            // second 8 bytes: cumulativeAmount
            size = AMOUNT_BYTE_SIZE;
            for (int i = 0; i < size; ++i) {
                bytes[i+offset] = (byte) (cumulativeAmountLong >> (size - i - 1 << 3));
            }
            offset += size;

            // last 8 bytes: amount
            size = AMOUNT_BYTE_SIZE;
            for (int i = 0; i < 8; ++i) {
                bytes[i+offset] = (byte) (amountLong >> (size - i - 1 << 3));
            }

            return bytes;
        }

        static long timestampFromBytes(byte[] bytes) {
            long timestamp = 0;

            for (int i = 0; i < DATE_BYTE_SIZE; ++i) {
                timestamp = (timestamp << 8) | (bytes[i] & 0xff);
            }

            return timestamp * DATE_DECIMAL_SHIFT;
        }

        static double amountFromBytes(byte[] bytes, int position) {
            long amountLong = 0;

            int offset = DATE_BYTE_SIZE + ((position-1)*AMOUNT_BYTE_SIZE);

            for (int i = 0; i < AMOUNT_BYTE_SIZE; ++i) {
                amountLong = (amountLong << 8) | (bytes[i+offset] & 0xff);
            }

            return (double) amountLong / AMOUNT_DECIMAL_SHIFT;
        }

        static String getDimensionInsertRowSql(SqlDatabase db, List<Object> parameters, UUID countId, UUID recordId, String dimensionsSymbol, Dimension dimension, Object value, String table) {
            SqlVendor vendor = db.getVendor();
            StringBuilder insertBuilder = new StringBuilder("INSERT INTO ");
            vendor.appendIdentifier(insertBuilder, table);
            insertBuilder.append(" (");
            LinkedHashMap<String, Object> cols = new LinkedHashMap<String, Object>();
            cols.put(COUNTRECORD_COUNTID_FIELD, countId);
            cols.put("id", recordId);
            cols.put("dimensionsSymbolId", db.getSymbolId(dimensionsSymbol));
            cols.put("symbolId", db.getSymbolId(dimension.getSymbol()));
            cols.put("value", value);
            for (Map.Entry<String, Object> entry : cols.entrySet()) {
                vendor.appendIdentifier(insertBuilder, entry.getKey());
                insertBuilder.append(", ");
            }
            insertBuilder.setLength(insertBuilder.length()-2);
            insertBuilder.append(") VALUES (");
            for (Map.Entry<String, Object> entry : cols.entrySet()) {
                vendor.appendBindValue(insertBuilder, entry.getValue(), parameters);
                insertBuilder.append(", ");
            }
            insertBuilder.setLength(insertBuilder.length()-2);
            insertBuilder.append(")");
            return insertBuilder.toString();
        }

        static String getUpdateSql(SqlDatabase db, List<Object> parameters, UUID countId, String actionSymbol, double amount, long updateDate, long eventDate, boolean increment, boolean updateFuture) {
            StringBuilder updateBuilder = new StringBuilder("UPDATE ");
            SqlVendor vendor = db.getVendor();
            vendor.appendIdentifier(updateBuilder, COUNTRECORD_TABLE);
            updateBuilder.append(" SET ");

            vendor.appendIdentifier(updateBuilder, COUNTRECORD_DATA_FIELD);
            updateBuilder.append(" = ");
            updateBuilder.append(" UNHEX(");
                updateBuilder.append("CONCAT(");
                    // timestamp
                    appendHexEncodeExistingTimestampSql(updateBuilder, vendor, COUNTRECORD_DATA_FIELD);
                    updateBuilder.append(',');
                    // cumulativeAmount and amount
                    if (increment) {
                        appendHexEncodeIncrementAmountSql(updateBuilder, parameters, vendor, COUNTRECORD_DATA_FIELD, CUMULATIVEAMOUNT_POSITION, amount);
                        updateBuilder.append(',');
                        if (updateFuture) {
                            updateBuilder.append("IF (");
                                vendor.appendIdentifier(updateBuilder, COUNTRECORD_DATA_FIELD);
                                updateBuilder.append(" LIKE ");
                                    updateBuilder.append(" CONCAT(");
                                        updateBuilder.append(" UNHEX(");
                                            appendHexEncodeTimestampSql(updateBuilder, parameters, vendor, eventDate, null);
                                        updateBuilder.append(")");
                                    updateBuilder.append(", '%')");
                                    updateBuilder.append(","); // if it's the exact date, then update the amount
                                    appendHexEncodeIncrementAmountSql(updateBuilder, parameters, vendor, COUNTRECORD_DATA_FIELD, AMOUNT_POSITION, amount);
                                    updateBuilder.append(","); // if it's a date in the future, leave the date alone
                                    appendHexEncodeIncrementAmountSql(updateBuilder, parameters, vendor, COUNTRECORD_DATA_FIELD, AMOUNT_POSITION, 0);
                            updateBuilder.append(")");
                        } else {
                            appendHexEncodeIncrementAmountSql(updateBuilder, parameters, vendor, COUNTRECORD_DATA_FIELD, CUMULATIVEAMOUNT_POSITION, amount);
                        }
                    } else {
                        appendHexEncodeSetAmountSql(updateBuilder, parameters, vendor, amount);
                        updateBuilder.append(',');
                        appendHexEncodeSetAmountSql(updateBuilder, parameters, vendor, amount);
                    }
                updateBuilder.append(" )");
            updateBuilder.append(" )");

            updateBuilder.append(", ");
            vendor.appendIdentifier(updateBuilder, "updateDate");
            updateBuilder.append(" = ");
            vendor.appendBindValue(updateBuilder, updateDate, parameters);
            updateBuilder.append(" WHERE ");
            vendor.appendIdentifier(updateBuilder, COUNTRECORD_COUNTID_FIELD);
            updateBuilder.append(" = ");
            vendor.appendBindValue(updateBuilder, countId, parameters);
            updateBuilder.append(" AND ");
            vendor.appendIdentifier(updateBuilder, "actionSymbolId");
            updateBuilder.append(" = ");
            vendor.appendBindValue(updateBuilder, db.getSymbolId(actionSymbol), parameters);
            updateBuilder.append(" AND ");

            vendor.appendIdentifier(updateBuilder, COUNTRECORD_DATA_FIELD);
            if (updateFuture) {
                // Note that this is a >= : we are updating the cumulativeAmount for every date AFTER this date, too, while leaving their amounts alone.
                updateBuilder.append(" >= ");
                updateBuilder.append(" UNHEX(");
                appendHexEncodeTimestampSql(updateBuilder, parameters, vendor, eventDate, '0');
                updateBuilder.append(")");
            } else {
                updateBuilder.append(" LIKE ");
                updateBuilder.append(" CONCAT(");
                updateBuilder.append(" UNHEX(");
                appendHexEncodeTimestampSql(updateBuilder, parameters, vendor, eventDate, null);
                updateBuilder.append(")");
                updateBuilder.append(", '%')");
            }

            return updateBuilder.toString();
        }

        private static void appendHexEncodeTimestampSql(StringBuilder str, List<Object> parameters, SqlVendor vendor, long timestamp, Character rpadChar) {
            if (rpadChar != null) {
                str.append("RPAD(");
            }
            str.append("LPAD(");
                str.append("HEX(");
                    if (parameters == null) {
                        vendor.appendValue(str, (int) (timestamp / DATE_DECIMAL_SHIFT));
                    } else {
                        vendor.appendBindValue(str, (int) (timestamp / DATE_DECIMAL_SHIFT), parameters);
                    }
                str.append(")");
            str.append(", "+(DATE_BYTE_SIZE*2)+", '0')");
            if (rpadChar != null) {
                str.append(",");
                vendor.appendValue(str, DATE_BYTE_SIZE*2+AMOUNT_BYTE_SIZE*2+AMOUNT_BYTE_SIZE*2);
                str.append(", '");
                str.append(rpadChar);
                str.append("')");
            }
        }

        private static void appendHexEncodeExistingTimestampSql(StringBuilder str, SqlVendor vendor, String columnIdentifier) {
            // columnName is "data" or "max(`data`)"
            str.append("HEX(");
                str.append("SUBSTR(");
                    str.append(columnIdentifier);
                    str.append(",");
                    vendor.appendValue(str, 1);
                    str.append(",");
                    vendor.appendValue(str, DATE_BYTE_SIZE);
                str.append(")");
            str.append(")");
        }

        private static void appendHexEncodeSetAmountSql(StringBuilder str, List<Object> parameters, SqlVendor vendor, double amount) {
            str.append("LPAD(");
                str.append("HEX(");
                    vendor.appendBindValue(str, (int) (amount * AMOUNT_DECIMAL_SHIFT), parameters);
                str.append(" )");
            str.append(", "+(AMOUNT_BYTE_SIZE*2)+", '0')");
        }

        private static void appendHexEncodeIncrementAmountSql(StringBuilder str, List<Object> parameters, SqlVendor vendor, String columnName, int position, double amount) {
            // position is 1 or 2
            // columnName is "data" unless it is aliased
            str.append("LPAD(");
                str.append("HEX(");
                    // conv(hex(substr(data, 1+4, 8)), 16, 10)
                    appendSelectAmountSql(str, vendor, columnName, position);
                    str.append("+");
                    vendor.appendBindValue(str, (long)(amount * AMOUNT_DECIMAL_SHIFT), parameters);
                str.append(" )");
            str.append(", "+(AMOUNT_BYTE_SIZE*2)+", '0')");
        }

        static String getUpdateSummarySql(SqlDatabase db, List<Object> parameters, double amount, UUID summaryRecordId, int summaryFieldSymbolId, String tableName, String valueColumnName, boolean increment) {
            /* TO DO: this is going to have to change once countperformance is merged in - this table does not have typeId */
            SqlVendor vendor = db.getVendor();
            StringBuilder sqlBuilder = new StringBuilder();
            sqlBuilder.append("UPDATE ");
            vendor.appendIdentifier(sqlBuilder, tableName);
            sqlBuilder.append(" SET ");
            vendor.appendIdentifier(sqlBuilder, valueColumnName);
            sqlBuilder.append(" = ");
            if (increment) {
                vendor.appendIdentifier(sqlBuilder, valueColumnName);
                sqlBuilder.append(" + ");
            }
            vendor.appendBindValue(sqlBuilder, amount, parameters);
            sqlBuilder.append(" WHERE ");
            vendor.appendIdentifier(sqlBuilder, "id");
            sqlBuilder.append(" = ");
            vendor.appendBindValue(sqlBuilder, summaryRecordId, parameters);
            sqlBuilder.append(" AND ");
            vendor.appendIdentifier(sqlBuilder, "symbolId");
            sqlBuilder.append(" = ");
            vendor.appendBindValue(sqlBuilder, summaryFieldSymbolId, parameters);
            return sqlBuilder.toString();
        }

        static String getInsertSummarySql(SqlDatabase db, List<Object> parameters, double amount, UUID summaryRecordId, int summaryFieldSymbolId, String tableName, String valueColumnName) {
            /* TO DO: this is going to have to change once countperformance is merged in - this table does not have typeId */
            SqlVendor vendor = db.getVendor();
            StringBuilder sqlBuilder = new StringBuilder();
            sqlBuilder.append("INSERT INTO ");
            vendor.appendIdentifier(sqlBuilder, tableName);
            sqlBuilder.append(" (");
            vendor.appendIdentifier(sqlBuilder, "id");
            sqlBuilder.append(", ");
            vendor.appendIdentifier(sqlBuilder, "symbolId");
            sqlBuilder.append(", ");
            vendor.appendIdentifier(sqlBuilder, valueColumnName);
            sqlBuilder.append(") VALUES (");
            vendor.appendBindValue(sqlBuilder, summaryRecordId, parameters);
            sqlBuilder.append(", ");
            vendor.appendBindValue(sqlBuilder, summaryFieldSymbolId, parameters);
            sqlBuilder.append(", ");
            vendor.appendBindValue(sqlBuilder, amount, parameters);
            sqlBuilder.append(")");
            return sqlBuilder.toString();
        }

        static String getDeleteDimensionSql(SqlDatabase db, UUID recordId, String table, int actionSymbolId) {
            SqlVendor vendor = db.getVendor();
            StringBuilder sqlBuilder = new StringBuilder();
            sqlBuilder.append("DELETE FROM ");
            vendor.appendIdentifier(sqlBuilder, table);
            sqlBuilder.append(" WHERE ");
            vendor.appendIdentifier(sqlBuilder, "id");
            if (recordId == null) {
                sqlBuilder.append(" IS NULL ");
            } else {
                sqlBuilder.append(" = ");
                vendor.appendValue(sqlBuilder, recordId);
            }
            sqlBuilder.append(" AND ");
            vendor.appendIdentifier(sqlBuilder, COUNTRECORD_COUNTID_FIELD);
            sqlBuilder.append(" IN (");
            sqlBuilder.append(" SELECT DISTINCT ");
            vendor.appendIdentifier(sqlBuilder, COUNTRECORD_COUNTID_FIELD);
            sqlBuilder.append(" FROM ");
            vendor.appendIdentifier(sqlBuilder, COUNTRECORD_TABLE);
            sqlBuilder.append(" WHERE ");
            vendor.appendIdentifier(sqlBuilder, "id");
            if (recordId == null) {
                sqlBuilder.append(" IS NULL ");
            } else {
                sqlBuilder.append(" = ");
                vendor.appendValue(sqlBuilder, recordId);
            }
            sqlBuilder.append(" AND ");
            vendor.appendIdentifier(sqlBuilder, "actionSymbolId");
            sqlBuilder.append(" = ");
            vendor.appendValue(sqlBuilder, actionSymbolId);
            sqlBuilder.append(") ");
            return sqlBuilder.toString();
        }

        static String getDeleteCountRecordSql(SqlDatabase db, UUID recordId, int actionSymbolId) {
            SqlVendor vendor = db.getVendor();
            StringBuilder sqlBuilder = new StringBuilder();
            sqlBuilder.append("DELETE FROM ");
            vendor.appendIdentifier(sqlBuilder, COUNTRECORD_TABLE);
            sqlBuilder.append(" WHERE ");
            vendor.appendIdentifier(sqlBuilder, "id");
            if (recordId == null) {
                sqlBuilder.append(" IS NULL ");
            } else {
                sqlBuilder.append(" = ");
                vendor.appendValue(sqlBuilder, recordId);
            }
            sqlBuilder.append(" AND ");
            vendor.appendIdentifier(sqlBuilder, "actionSymbolId");
            sqlBuilder.append(" = ");
            vendor.appendValue(sqlBuilder, actionSymbolId);
            return sqlBuilder.toString();
        }

        static String getDeleteSummarySql(SqlDatabase db, UUID recordId, String table, int summaryFieldSymbolId) {
            SqlVendor vendor = db.getVendor();
            StringBuilder sqlBuilder = new StringBuilder();
            sqlBuilder.append("DELETE FROM ");
            vendor.appendIdentifier(sqlBuilder, table);
            sqlBuilder.append(" WHERE ");
            vendor.appendIdentifier(sqlBuilder, "id");
            if (recordId == null) {
                sqlBuilder.append(" IS NULL ");
            } else {
                sqlBuilder.append(" = ");
                vendor.appendValue(sqlBuilder, recordId);
            }
            sqlBuilder.append(" AND ");
            vendor.appendIdentifier(sqlBuilder, "symbolId");
            sqlBuilder.append(" = ");
            vendor.appendValue(sqlBuilder, summaryFieldSymbolId);
            return sqlBuilder.toString();
        }

        static String getCountIdsByDimensionsSelectSql(SqlDatabase db, CountRecordQuery query, boolean preciseMatch) {
            SqlVendor vendor = db.getVendor();
            StringBuilder selectBuilder = new StringBuilder();
            StringBuilder fromBuilder = new StringBuilder();
            StringBuilder whereBuilder = new StringBuilder();
            StringBuilder groupByBuilder = new StringBuilder();
            StringBuilder orderByBuilder = new StringBuilder();

            int i = 0;
            int count = 1;
            String alias;
            selectBuilder.append("SELECT ");
            vendor.appendIdentifier(selectBuilder, "cr0");
            selectBuilder.append(".");
            vendor.appendIdentifier(selectBuilder, COUNTRECORD_COUNTID_FIELD);

            for (String table : Static.getIndexTables(query.getDimensions(),
                    query.getGroupByDimensions())) {
                alias = "cr" + i;
                if (i == 0) {
                    fromBuilder.append(" \nFROM ");
                    vendor.appendIdentifier(fromBuilder, table);
                    fromBuilder.append(" ");
                    vendor.appendIdentifier(fromBuilder, alias);
                    whereBuilder.append(" \nWHERE ");
                    if (preciseMatch) {
                        vendor.appendIdentifier(whereBuilder, alias);
                        whereBuilder.append(".");
                        vendor.appendIdentifier(whereBuilder, "dimensionsSymbolId");
                        whereBuilder.append(" = ");
                        whereBuilder.append(db.getSymbolId(query.getSymbol()));
                    } else {
                        whereBuilder.append("1 = 1");
                    }
                    if (query.getRecordIdForInsert() != null) {
                        whereBuilder.append(" AND ");
                        vendor.appendIdentifier(whereBuilder, alias);
                        whereBuilder.append(".");
                        vendor.appendIdentifier(whereBuilder, "id");
                        whereBuilder.append(" = ");
                        vendor.appendValue(whereBuilder, query.getRecordIdForInsert());
                    }
                } else {
                    fromBuilder.append(" \nJOIN ");
                    vendor.appendIdentifier(fromBuilder, table);
                    fromBuilder.append(" ");
                    vendor.appendIdentifier(fromBuilder, alias);
                    fromBuilder.append(" ON (");
                    vendor.appendIdentifier(fromBuilder, "cr0");
                    fromBuilder.append(".");
                    vendor.appendIdentifier(fromBuilder, "dimensionsSymbolId");
                    fromBuilder.append(" = ");
                    vendor.appendIdentifier(fromBuilder, alias);
                    fromBuilder.append(".");
                    vendor.appendIdentifier(fromBuilder, "dimensionsSymbolId");
                    fromBuilder.append(" AND ");
                    vendor.appendIdentifier(fromBuilder, "cr0");
                    fromBuilder.append(".");
                    vendor.appendIdentifier(fromBuilder, COUNTRECORD_COUNTID_FIELD);
                    fromBuilder.append(" = ");
                    vendor.appendIdentifier(fromBuilder, alias);
                    fromBuilder.append(".");
                    vendor.appendIdentifier(fromBuilder, COUNTRECORD_COUNTID_FIELD);
                    if (query.getRecordIdForInsert() != null) {
                        fromBuilder.append(" AND ");
                        vendor.appendIdentifier(fromBuilder, alias);
                        fromBuilder.append(".");
                        vendor.appendIdentifier(fromBuilder, "id");
                        fromBuilder.append(" = ");
                        vendor.appendValue(fromBuilder, query.getRecordIdForInsert());
                    }
                    fromBuilder.append(")");
                }

                int numFilters = 0;
                // append to where statement
                whereBuilder.append(" \nAND (");
                for (Dimension dimension : Static.getDimensionsByIndexTable(
                        table, query.getDimensions())) {
                    Set<Object> values = dimension.getValues();
                    whereBuilder.append("(");
                    vendor.appendIdentifier(whereBuilder, alias);
                    whereBuilder.append(".");
                    vendor.appendIdentifier(whereBuilder, "symbolId");
                    whereBuilder.append(" = ");
                    whereBuilder.append(db.getSymbolId(dimension.getSymbol()));
                    whereBuilder.append(" AND ");
                    vendor.appendIdentifier(whereBuilder, alias);
                    whereBuilder.append(".");
                    vendor.appendIdentifier(whereBuilder, "value");
                    whereBuilder.append(" IN (");
                    for (Object v : values) {
                        vendor.appendValue(whereBuilder, v);
                        whereBuilder.append(',');
                        ++numFilters;
                    }
                    whereBuilder.setLength(whereBuilder.length() - 1);
                    whereBuilder.append("))");
                    whereBuilder.append(" \n  OR "); // 7 chars below
                }
                whereBuilder.setLength(whereBuilder.length() - 7);
                whereBuilder.append(") ");
                count = count * numFilters;
                ++i;
            }
            groupByBuilder.append("\nGROUP BY ");
            vendor.appendIdentifier(groupByBuilder, "cr0");
            groupByBuilder.append(".");
            vendor.appendIdentifier(groupByBuilder, COUNTRECORD_COUNTID_FIELD);
            //orderByBuilder.append("\nORDER BY ");
            //vendor.appendIdentifier(orderByBuilder, "cr0");
            //orderByBuilder.append(".");
            //vendor.appendIdentifier(orderByBuilder, COUNTRECORD_COUNTID_FIELD);
            groupByBuilder.append(" HAVING COUNT(*) = ");
            groupByBuilder.append(count);

            return selectBuilder.toString() + 
                " " + fromBuilder.toString() + 
                " " + whereBuilder.toString() + 
                " " + groupByBuilder.toString() + 
                " " + orderByBuilder.toString();
        }

        static String getSelectCountSql(SqlDatabase db, CountRecordQuery query) {
            SqlVendor vendor = db.getVendor();
            boolean selectMinData = false;

            StringBuilder selectBuilder = new StringBuilder();
            StringBuilder fromBuilder = new StringBuilder();

            selectBuilder.append("SELECT ");
            selectBuilder.append("ROUND(SUM(");

            // Always select maxData.cumulativeAmount
            appendSelectAmountSql(selectBuilder, vendor, "maxData", CUMULATIVEAMOUNT_POSITION);

            if (query.getStartTimestamp() != null) {
                // maxData.cumulativeAmount - minData.cumulativeAmount + minData.amount
                selectMinData = true;
                selectBuilder.append(" - ");
                appendSelectAmountSql(selectBuilder, vendor, "minData", CUMULATIVEAMOUNT_POSITION);
                selectBuilder.append(" + ");
                appendSelectAmountSql(selectBuilder, vendor, "minData", AMOUNT_POSITION);
            }

            selectBuilder.append(") / ");
            vendor.appendValue(selectBuilder, AMOUNT_DECIMAL_SHIFT);
            selectBuilder.append(",");
            vendor.appendValue(selectBuilder, AMOUNT_DECIMAL_PLACES);
            selectBuilder.append(")");
            fromBuilder.append(" FROM (");
            fromBuilder.append(getSelectCountIdDataSql(db, query, selectMinData));
            fromBuilder.append(") x2");

            return selectBuilder.toString() + fromBuilder.toString();
        }

        static String getSelectCountIdDataSql(SqlDatabase db, CountRecordQuery query, boolean selectMinData) {
            StringBuilder selectBuilder = new StringBuilder();
            StringBuilder fromBuilder = new StringBuilder();
            StringBuilder whereBuilder = new StringBuilder();
            SqlVendor vendor = db.getVendor();

            selectBuilder.append("SELECT ");

            selectBuilder.append("MAX(");
            vendor.appendIdentifier(selectBuilder, "cr");
            selectBuilder.append(".");
            vendor.appendIdentifier(selectBuilder, COUNTRECORD_DATA_FIELD);
            selectBuilder.append(") ");
            vendor.appendIdentifier(selectBuilder, "maxData");

            if (selectMinData) {
                selectBuilder.append(", ");
                selectBuilder.append("MIN(");
                vendor.appendIdentifier(selectBuilder, "cr");
                selectBuilder.append(".");
                vendor.appendIdentifier(selectBuilder, COUNTRECORD_DATA_FIELD);
                selectBuilder.append(") ");
                vendor.appendIdentifier(selectBuilder, "minData");
            }

            fromBuilder.append(" \nFROM ");
            vendor.appendIdentifier(fromBuilder, COUNTRECORD_TABLE);
            fromBuilder.append(" ");
            vendor.appendIdentifier(fromBuilder, "cr");
            whereBuilder.append(" \nWHERE ");
            if (query.getRecordIdForInsert() != null) {
                vendor.appendIdentifier(whereBuilder, "cr");
                whereBuilder.append(".");
                vendor.appendIdentifier(whereBuilder, "id");
                whereBuilder.append(" = ");
                vendor.appendValue(whereBuilder, query.getRecordIdForInsert());
            } else {
                whereBuilder.append(" 1 = 1 ");
            }
            whereBuilder.append(" AND ");
            vendor.appendIdentifier(whereBuilder, "cr");
            whereBuilder.append(".");
            vendor.appendIdentifier(whereBuilder, "actionSymbolId");
            whereBuilder.append(" = ");
            vendor.appendValue(whereBuilder, db.getSymbolId(query.getActionSymbol()));

            // for dates
            StringBuilder dateDataBuilder = new StringBuilder();
            vendor.appendIdentifier(dateDataBuilder, "cr");
            dateDataBuilder.append(".");
            vendor.appendIdentifier(dateDataBuilder, COUNTRECORD_DATA_FIELD);

            if (query.getStartTimestamp() != null) {
                whereBuilder.append(" AND ");
                whereBuilder.append(dateDataBuilder);
                whereBuilder.append(" > ");
                whereBuilder.append(" UNHEX(");
                appendHexEncodeTimestampSql(whereBuilder, null, vendor, query.getStartTimestamp(), 'F');
                whereBuilder.append(")");
            }

            if (query.getEndTimestamp() != null) {
                whereBuilder.append(" AND ");
                whereBuilder.append(dateDataBuilder);
                whereBuilder.append(" <= ");
                whereBuilder.append(" UNHEX(");
                appendHexEncodeTimestampSql(whereBuilder, null, vendor, query.getEndTimestamp(), 'F');
                whereBuilder.append(")");
            }

            fromBuilder.append(" JOIN (");
            fromBuilder.append(getCountIdsByDimensionsSelectSql(db, query, false));
            fromBuilder.append(") x");
            fromBuilder.append(" ON (");
            vendor.appendIdentifier(fromBuilder, "x");
            fromBuilder.append(".");
            vendor.appendIdentifier(fromBuilder, "countId");
            fromBuilder.append(" = ");
            vendor.appendIdentifier(fromBuilder, "cr");
            fromBuilder.append(".");
            vendor.appendIdentifier(fromBuilder, "countId");
            fromBuilder.append(")");

            return selectBuilder.toString() + fromBuilder.toString() + whereBuilder.toString();
        }

        static String getSelectCountIdSql(SqlDatabase db, CountRecordQuery query) {
            return getCountIdsByDimensionsSelectSql(db, query, true);
        }

        // methods that actually touch the database

        // SUMMARY INSERT/UPDATE
        static void doCountSummaryUpdateOrInsert(SqlDatabase db, double amount, ObjectField countField, UUID summaryRecordId, boolean increment) throws SQLException {
            Connection connection = db.openConnection();
            StringBuilder symbolBuilder = new StringBuilder();
            symbolBuilder.append(countField.getJavaDeclaringClassName());
            symbolBuilder.append("/");
            symbolBuilder.append(countField.getInternalName());
            int summaryFieldSymbolId = db.getSymbolId(symbolBuilder.toString());
            String summaryTable = countField.as(SqlDatabase.FieldData.class).getIndexTable();
            String columnName = "value";
            if (countField.as(SqlDatabase.FieldData.class).isIndexTableSameColumnNames()) {
                columnName = countField.getInternalName();
                int dotAt = columnName.lastIndexOf(".");
                if (dotAt > -1) {
                    columnName = columnName.substring(dotAt+1);
                }
            }
            try {
                List<Object> parameters = new ArrayList<Object>();
                String sql = getUpdateSummarySql(db, parameters, amount, summaryRecordId, summaryFieldSymbolId, summaryTable, columnName, increment);
                int rowsAffected = SqlDatabase.Static.executeUpdateWithList(connection, sql, parameters);
                if (rowsAffected == 0) {
                    parameters = new ArrayList<Object>();
                    sql = getInsertSummarySql(db, parameters, amount, summaryRecordId, summaryFieldSymbolId, summaryTable, columnName);
                    SqlDatabase.Static.executeUpdateWithList(connection, sql, parameters);
                }
            } finally {
                db.closeConnection(connection);
            }
            
        }

        static void doIncrementCountSummaryUpdateOrInsert(SqlDatabase db, double amount, ObjectField countField, UUID summaryRecordId) throws SQLException {
            doCountSummaryUpdateOrInsert(db, amount, countField, summaryRecordId, true);
        }

        static void doSetCountSummaryUpdateOrInsert(SqlDatabase db, double amount, ObjectField countField, UUID summaryRecordId) throws SQLException {
            doCountSummaryUpdateOrInsert(db, amount, countField, summaryRecordId, false);
        }

        static void doCountSummaryDelete(SqlDatabase db, UUID recordId, ObjectField countField) throws SQLException {
            Connection connection = db.openConnection();
            List<Object> parameters = new ArrayList<Object>();
            StringBuilder symbolBuilder = new StringBuilder();
            symbolBuilder.append(countField.getJavaDeclaringClassName());
            symbolBuilder.append("/");
            symbolBuilder.append(countField.getInternalName());
            int summaryFieldSymbolId = db.getSymbolId(symbolBuilder.toString());
            try {
                String summaryTable = countField.as(SqlDatabase.FieldData.class).getIndexTable();
                String sql = getDeleteSummarySql(db, recordId, summaryTable, summaryFieldSymbolId);
                SqlDatabase.Static.executeUpdateWithList(connection, sql, parameters);
            } finally {
                db.closeConnection(connection);
            }
        }

        // COUNTRECORD INSERT/UPDATE/DELETE
        static void doInserts(SqlDatabase db, UUID countId, UUID recordId, UUID typeId, String actionSymbol, String dimensionsSymbol, DimensionSet dimensions, double amount, long updateDate, long eventDate) throws SQLException {
            Connection connection = db.openConnection();
            try {
                List<List<Object>> parametersList = new ArrayList<List<Object>>();
                List<String> sqls = getInsertSqls(db, parametersList, countId, recordId, typeId, actionSymbol, dimensionsSymbol, dimensions, amount, updateDate, eventDate);
                Iterator<List<Object>> parametersIterator = parametersList.iterator();
                for (String sql : sqls) {
                    SqlDatabase.Static.executeUpdateWithList(connection, sql, parametersIterator.next());
                }
            } finally {
                db.closeConnection(connection);
            }
        }

        static void doIncrementUpdateOrInsert(SqlDatabase db, UUID countId, UUID recordId, UUID typeId, String actionSymbol, String dimensionsSymbol, double incrementAmount, long updateDate, long eventDate) throws SQLException {
            Connection connection = db.openConnection();
            try {
                List<Object> parameters = new ArrayList<Object>();

                // First, find the max eventDate. Under normal circumstances, this will either be null (INSERT), before our eventDate (INSERT) or equal to our eventDate (UPDATE).
                byte[] data = getDataByCountId(db, countId, actionSymbol, null, null);
                String sql;

                if (data == null || timestampFromBytes(data) < eventDate) {
                    // No data for this eventDate; insert.
                    double previousCumulativeAmount = amountFromBytes(data, CUMULATIVEAMOUNT_POSITION);
                    parameters = new ArrayList<Object>();
                    sql = getCountRecordInsertSql(db, parameters, countId, recordId, typeId, actionSymbol, dimensionsSymbol, incrementAmount, previousCumulativeAmount+incrementAmount, updateDate, eventDate);
                } else if (timestampFromBytes(data) == eventDate) {
                    // There is data for this eventDate; update it.
                    sql = getUpdateSql(db, parameters, countId, actionSymbol, incrementAmount, updateDate, eventDate, true, false);
                } else { // if (timestampFromBytes(data) > eventDate) {
                    // We are updating a row in the past, so we need to tell updateSql to update the cumulativeAmount for all rows in the future.
                    sql = getUpdateSql(db, parameters, countId, actionSymbol, incrementAmount, updateDate, eventDate, true, true);
                }
                SqlDatabase.Static.executeUpdateWithList( connection, sql, parameters);

            } finally {
                db.closeConnection(connection);
            }
        }

        static void doSetUpdateOrInsert(SqlDatabase db, UUID countId, UUID recordId, UUID typeId, String actionSymbol, String dimensionsSymbol, double amount, long updateDate, long eventDate) throws SQLException {
            Connection connection = db.openConnection();
            if (eventDate != 0L) {
                throw new RuntimeException("CountRecord.Static.doSetUpdateOrInsert() can only be used if EventDatePrecision is NONE; eventDate is " + eventDate + ", should be 0L.");
            }
            try {
                List<Object> parameters = new ArrayList<Object>();
                String sql = getUpdateSql(db, parameters, countId, actionSymbol, amount, updateDate, eventDate, false, false);
                int rowsAffected = SqlDatabase.Static.executeUpdateWithList( connection, sql, parameters);
                if (rowsAffected == 0) {
                    parameters = new ArrayList<Object>();
                    sql = getCountRecordInsertSql(db, parameters, countId, recordId, typeId, actionSymbol, dimensionsSymbol, amount, amount, updateDate, eventDate);
                    SqlDatabase.Static.executeUpdateWithList(connection, sql, parameters);
                }
            } finally {
                db.closeConnection(connection);
            }
        }

        static void doCountDelete(SqlDatabase db, UUID recordId, DimensionSet dimensions, String actionSymbol) throws SQLException {
            Connection connection = db.openConnection();
            List<Object> parameters = new ArrayList<Object>();
            int actionSymbolId = db.getSymbolId(actionSymbol);
            try {
                Set<String> tables = new HashSet<String>();
                for (Dimension dimension : dimensions) {
                    tables.add(dimension.getIndexTable());
                }
                // This needs to be executed BEFORE DeleteCountRecordSql
                for (String table : tables) {
                    String sql = getDeleteDimensionSql(db, recordId, table, actionSymbolId);
                    SqlDatabase.Static.executeUpdateWithList(connection, sql, parameters);
                }
                String sql = getDeleteCountRecordSql(db, recordId, actionSymbolId);
                SqlDatabase.Static.executeUpdateWithList(connection, sql, parameters);
            } finally {
                db.closeConnection(connection);
            }
        }

        // COUNTRECORD SELECT
        static Double getCountByDimensions(SqlDatabase db, CountRecordQuery query) throws SQLException {
            String sql = null;
            //UUID countId = Static.getCountIdByDimensions(db, query);
            sql = getSelectCountSql(db, query);
            Connection connection = db.openReadConnection();
            Double count = 0.0;
            try {
                Statement statement = connection.createStatement();
                ResultSet result = db.executeQueryBeforeTimeout(statement, sql, QUERY_TIMEOUT);
                if (result.next()) {
                    count = result.getDouble(1);
                }
                result.close();
                statement.close();
                return count;
            } finally {
                db.closeConnection(connection);
            }
        }

        static Double getCountByCountId(SqlDatabase db, UUID countId, String actionSymbol, Long minEventDate, Long maxEventDate) throws SQLException {
            if (minEventDate == null) {
                byte[] data = getDataByCountId(db, countId, actionSymbol, minEventDate, maxEventDate);
                if (data == null) return null;
                return amountFromBytes(data, CUMULATIVEAMOUNT_POSITION);
            } else {
                List<byte[]> datas = getMinMaxDataByCountId(db, countId, actionSymbol, minEventDate, maxEventDate);
                if (datas.size() == 0) return null;
                double maxCumulativeAmount = amountFromBytes(datas.get(0), CUMULATIVEAMOUNT_POSITION);
                double minCumulativeAmount = amountFromBytes(datas.get(1), CUMULATIVEAMOUNT_POSITION);
                double minAmount = amountFromBytes(datas.get(1), AMOUNT_POSITION);
                return maxCumulativeAmount - minCumulativeAmount + minAmount;
            }
        }

        static Long getMaxEventDateByCountId(SqlDatabase db, UUID countId, String actionSymbol, Long minEventDate, Long maxEventDate) throws SQLException {
            byte[] data = getDataByCountId(db, countId, actionSymbol, minEventDate, maxEventDate);
            if (data == null) return null;
            return timestampFromBytes(data);
        }

        static byte[] getDataByCountId(SqlDatabase db, UUID countId, String actionSymbol, Long minEventDate, Long maxEventDate) throws SQLException {
            String sql = Static.getDataByCountIdSql(db, countId, actionSymbol, minEventDate, maxEventDate, false);
            byte[] data = null;
            Connection connection = db.openReadConnection();
            try {
                Statement statement = connection.createStatement();
                ResultSet result = db.executeQueryBeforeTimeout(statement, sql, QUERY_TIMEOUT);
                if (result.next()) {
                    data = result.getBytes(1);
                }
            } finally {
                db.closeConnection(connection);
            }
            return data;
        }

        static List<byte[]> getMinMaxDataByCountId(SqlDatabase db, UUID countId, String actionSymbol, Long minEventDate, Long maxEventDate) throws SQLException {
            List<byte[]> datas = new ArrayList<byte[]>();
            String sql = Static.getDataByCountIdSql(db, countId, actionSymbol, minEventDate, maxEventDate, true);
            Connection connection = db.openReadConnection();
            try {
                Statement statement = connection.createStatement();
                ResultSet result = db.executeQueryBeforeTimeout(statement, sql, QUERY_TIMEOUT);
                if (result.next()) {
                    datas.add(result.getBytes(1));
                    datas.add(result.getBytes(2));
                }
            } finally {
                db.closeConnection(connection);
            }
            return datas;
        }

        static UUID getCountIdByDimensions(SqlDatabase db, CountRecordQuery query) throws SQLException {
            UUID countId = null;
            // find the countId, it might be null
            String sql = Static.getSelectCountIdSql(db, query);
            Connection connection = db.openReadConnection();
            try {
                Statement statement = connection.createStatement();
                ResultSet result = db.executeQueryBeforeTimeout(statement, sql, QUERY_TIMEOUT);
                if (result.next()) {
                    countId = UuidUtils.fromBytes(result.getBytes(1));
                }
                result.close();
                statement.close();
            } finally {
                db.closeConnection(connection);
            }
            return countId;
        }

    }

}

class CountRecordQuery {
    private final String symbol;
    private final String actionSymbol;
    private final DimensionSet dimensions;
    private final Record record;
    private Long startTimestamp;
    private Long endTimestamp;
    private DimensionSet groupByDimensions;
    private String[] orderByDimensions;
    public boolean includeSelfDimension;

    public CountRecordQuery(String symbol, String actionSymbol, DimensionSet dimensions) {
        this.symbol = symbol;
        this.actionSymbol = actionSymbol;
        this.dimensions = dimensions;
        this.record = null;
    }

    public CountRecordQuery(String symbol, String actionSymbol, Record record,
            DimensionSet dimensions) {
        this.symbol = symbol;
        this.actionSymbol = actionSymbol;
        this.dimensions = dimensions;
        this.record = record;
    }

    public UUID getRecordIdForInsert() {
        if (isIncludeSelfDimension()) {
            return this.record.getId();
        } else {
            return null;
        }
    }

    public boolean isIncludeSelfDimension() {
        return includeSelfDimension;
    }

    public void setIncludeSelfDimension(boolean includeSelfDimension) {
        this.includeSelfDimension = includeSelfDimension;
    }

    public void setOrderByDimensions(String[] orderByDimensions) {
        this.orderByDimensions = orderByDimensions;
    }

    public void setGroupByDimensions(DimensionSet groupByDimensions) {
        this.groupByDimensions = groupByDimensions;
    }

    public String getSymbol() {
        return symbol;
    }

    public String getActionSymbol() {
        return actionSymbol;
    }

    public DimensionSet getDimensions() {
        return dimensions;
    }

    public Record getRecord() {
        return record;
    }

    public Long getStartTimestamp() {
        return startTimestamp;
    }

    public Long getEndTimestamp() {
        return endTimestamp;
    }

    public void setDateRange(Long startTimestamp, Long endTimestamp) {
        this.startTimestamp = startTimestamp;
        this.endTimestamp = endTimestamp;
    }

    public DimensionSet getGroupByDimensions() {
        return groupByDimensions;
    }

    public String[] getOrderByDimensions() {
        return orderByDimensions;
    }

    public String toString() {
        return "action: " + getActionSymbol() + " recordId: " + getRecordIdForInsert() + " date range: " + startTimestamp + " - " + endTimestamp + " dimensions: " + dimensions;
    }
}

class Dimension implements Comparable<Dimension> {

    private final ObjectField objectField;
    private Set<Object> values = new HashSet<Object>();

    public Dimension(ObjectField objectField) {
        this.objectField = objectField;
    }

    public String getSymbol() {
        return getKey();
    }

    public String getKey() {
        return objectField.getUniqueName();
    }

    public ObjectField getObjectField() {
        return objectField;
    }

    public Set<Object> getValues() {
        return values;
    }

    public void addValue(UUID value) {
        this.values.add(value);
    }

    public void addValue(String value) {
        this.values.add(value);
    }

    public void addValue(Number value) {
        this.values.add(value);
    }

    public void addValue(Object value) {
        this.values.add(value.toString());
    }

    public String getIndexTable () {
        return CountRecord.Static.getIndexTable(this.getObjectField());
    }

    public String toString() {
        StringBuilder str = new StringBuilder(getSymbol());
        if (values.size() > 1) {
            str.append("[");
            str.append(values.size());
            str.append("]");
        }
        return str.toString();
    }

    @Override
    public int compareTo(Dimension arg0) {
        return this.getSymbol().compareTo(arg0.getSymbol());
    }

}

class DimensionSet extends LinkedHashSet<Dimension> {
    private static final long serialVersionUID = 1L;

    public DimensionSet(Set<Dimension> dimensions) {
        super(dimensions);
    }

    public DimensionSet() {
        super();
    }

    public Set<String> keySet() {
        LinkedHashSet<String> keys = new LinkedHashSet<String>();
        for (Dimension d : this) {
            keys.add(d.getKey());
        }
        return keys;
    }

    public static DimensionSet createDimensionSet(Set<ObjectField> dimensions, Record record) {
        LinkedHashSet<Dimension> dimensionSet = new LinkedHashSet<Dimension>();
        for (ObjectField field : dimensions) {
            LinkedHashSet<Object> values = new LinkedHashSet<Object>();
            Object value = record.getState().get(field.getInternalName());
            if (value == null) continue;
            if (value instanceof Set) {
                if (((Set<?>)value).size() == 0) continue;
                values.addAll((Set<?>)value);
            } else {
                values.add(value);
            }
            Dimension dim = new Dimension(field);
            for (Object val : values) {
                if (val instanceof UUID) {
                    dim.addValue((UUID) val);
                } else if (value instanceof Number) {
                    dim.addValue((Number) val);
                } else {
                    dim.addValue(val.toString());
                }
            }
            dimensionSet.add(dim);
        }
        return new DimensionSet(dimensionSet);
    }

    public String getSymbol() {
        StringBuilder symbolBuilder = new StringBuilder();
        // if there is ever a prefix, put it here.
        //StringBuilder symbolBuilder = new StringBuilder(this.objectClass.getName());
        //symbolBuilder.append("/");

        boolean usedThisPrefix = false;
        String thisPrefix = "";
        for (Dimension d : getSortedDimensions()) {
            String dimSymbol = d.getSymbol();
            String prefix = dimSymbol.split("/")[0];
            if (! prefix.equals(thisPrefix)) {
                usedThisPrefix = false;
                thisPrefix = prefix;
            }
            if (!usedThisPrefix) {
                symbolBuilder.append(thisPrefix);
                symbolBuilder.append("/");
                usedThisPrefix = true;
            }
            if (dimSymbol.indexOf('/') > -1) {
                dimSymbol = dimSymbol.split("/")[1];
            }

            symbolBuilder.append(dimSymbol);
            if (d.getValues().size() > 1) {
                symbolBuilder.append("[");
                symbolBuilder.append(d.getValues().size());
                symbolBuilder.append("]");
            }
            symbolBuilder.append(',');
        }
        if (symbolBuilder.length() > 0) {
            symbolBuilder.setLength(symbolBuilder.length()-1);
        }
        symbolBuilder.append("#count");
        return symbolBuilder.toString();
    }

    public String toString() {
        StringBuilder str = new StringBuilder(getSymbol());
        str.append(": ");
        for (Dimension dimension : this) {
            str.append(dimension.toString());
            str.append("=");
            str.append(dimension.getValues().toString());
            str.append(",");
        }
        str.setLength(str.length()-1);
        return str.toString();
    }

    private List<Dimension> getSortedDimensions() {
        ArrayList<Dimension> dims = new ArrayList<Dimension>(size());
        for (Dimension d : this) {
            dims.add(d);
        }
        Collections.sort(dims);
        return dims;
    }

}

