/*
 * Copyright 1999-2011 Alibaba Group Holding Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.alibaba.druid.wall;

import static com.alibaba.druid.util.JdbcSqlStatUtils.get;

import java.security.PrivilegedAction;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import com.alibaba.druid.sql.SQLUtils;
import com.alibaba.druid.sql.ast.SQLStatement;
import com.alibaba.druid.sql.parser.Lexer;
import com.alibaba.druid.sql.parser.NotAllowCommentException;
import com.alibaba.druid.sql.parser.ParserException;
import com.alibaba.druid.sql.parser.SQLStatementParser;
import com.alibaba.druid.sql.parser.Token;
import com.alibaba.druid.sql.visitor.ExportParameterVisitor;
import com.alibaba.druid.sql.visitor.ParameterizedOutputVisitorUtils;
import com.alibaba.druid.util.LRUCache;
import com.alibaba.druid.wall.spi.WallVisitorUtils;
import com.alibaba.druid.wall.violation.ErrorCode;
import com.alibaba.druid.wall.violation.IllegalSQLObjectViolation;
import com.alibaba.druid.wall.violation.SyntaxErrorViolation;

public abstract class WallProvider {

    private final Map<String, Object>                     attributes              = new ConcurrentHashMap<String, Object>(
                                                                                                                          1,
                                                                                                                          0.75f,
                                                                                                                          1);

    private LRUCache<String, WallSqlStat>                 whiteList;
    private LRUCache<String, WallSqlStat>                 whiteMergedList;

    private int                                           MAX_SQL_LENGTH          = 2048;                                              // 1k

    private int                                           whiteSqlMaxSize         = 500;                                               // 1k

    private LRUCache<String, WallSqlStat>                 blackList;
    private LRUCache<String, WallSqlStat>                 blackMergedList;

    private int                                           blackSqlMaxSize         = 100;                                               // 1k

    protected final WallConfig                            config;

    private final ReentrantReadWriteLock                  lock                    = new ReentrantReadWriteLock();

    private static final ThreadLocal<Boolean>             privileged              = new ThreadLocal<Boolean>();

    private final ConcurrentMap<String, WallFunctionStat> functionStats           = new ConcurrentHashMap<String, WallFunctionStat>(
                                                                                                                                    16,
                                                                                                                                    0.75f,
                                                                                                                                    1);
    private final ConcurrentMap<String, WallTableStat>    tableStats              = new ConcurrentHashMap<String, WallTableStat>(
                                                                                                                                 16,
                                                                                                                                 0.75f,
                                                                                                                                 1);

    public final WallDenyStat                             commentDeniedStat       = new WallDenyStat();

    protected String                                      dbType                  = null;
    protected final AtomicLong                            checkCount              = new AtomicLong();
    protected final AtomicLong                            hardCheckCount          = new AtomicLong();
    protected final AtomicLong                            whiteListHitCount       = new AtomicLong();
    protected final AtomicLong                            blackListHitCount       = new AtomicLong();
    protected final AtomicLong                            syntaxErrrorCount       = new AtomicLong();
    protected final AtomicLong                            violationCount          = new AtomicLong();
    protected final AtomicLong                            violationEffectRowCount = new AtomicLong();

    public WallProvider(WallConfig config){
        this.config = config;
    }

    public WallProvider(WallConfig config, String dbType){
        this.config = config;
        this.dbType = dbType;
    }

    public Map<String, Object> getAttributes() {
        return attributes;
    }

    public void reset() {
        this.checkCount.set(0);
        this.hardCheckCount.set(0);
        this.violationCount.set(0);
        this.whiteListHitCount.set(0);
        this.blackListHitCount.set(0);
        this.clearWhiteList();
        this.clearBlackList();
        this.functionStats.clear();
        this.tableStats.clear();
    }

    public ConcurrentMap<String, WallTableStat> getTableStats() {
        return this.tableStats;
    }

    public ConcurrentMap<String, WallFunctionStat> getFunctionStats() {
        return this.functionStats;
    }

    public WallSqlStat getSqlStat(String sql) {
        WallSqlStat sqlStat = this.getWhiteSql(sql);

        if (sqlStat == null) {
            sqlStat = this.getBlackSql(sql);
        }

        return sqlStat;
    }

    public WallTableStat getTableStat(String tableName) {
        String lowerCaseName = tableName.toLowerCase();
        if (lowerCaseName.startsWith("`") && lowerCaseName.endsWith("`")) {
            lowerCaseName = lowerCaseName.substring(1, lowerCaseName.length() - 1);
        }

        return getTableStatWithLowerName(lowerCaseName);
    }

    public void addUpdateCount(WallSqlStat sqlStat, long updateCount) {
        sqlStat.addUpdateCount(updateCount);
        
        Map<String, WallSqlTableStat> sqlTableStats = sqlStat.getTableStats();
        if (sqlTableStats == null) {
            return;
        }

        for (Map.Entry<String, WallSqlTableStat> entry : sqlTableStats.entrySet()) {
            String tableName = entry.getKey();
            WallTableStat tableStat = this.getTableStat(tableName);
            if (tableStat == null) {
                continue;
            }

            WallSqlTableStat sqlTableStat = entry.getValue();

            if (sqlTableStat.getDeleteCount() > 0) {
                tableStat.addDeleteDataCount(updateCount);
            } else if (sqlTableStat.getUpdateCount() > 0) {
                tableStat.addUpdateDataCount(updateCount);
            } else if (sqlTableStat.getInsertCount() > 0) {
                tableStat.addInsertDataCount(updateCount);
            }
        }
    }

    public void addFetchRowCount(WallSqlStat sqlStat, long fetchRowCount) {
        sqlStat.addAndFetchRowCount(fetchRowCount);

        Map<String, WallSqlTableStat> sqlTableStats = sqlStat.getTableStats();
        if (sqlTableStats == null) {
            return;
        }

        for (Map.Entry<String, WallSqlTableStat> entry : sqlTableStats.entrySet()) {
            String tableName = entry.getKey();
            WallTableStat tableStat = this.getTableStat(tableName);
            if (tableStat == null) {
                continue;
            }

            WallSqlTableStat sqlTableStat = entry.getValue();

            if (sqlTableStat.getSelectCount() > 0) {
                tableStat.addFetchRowCount(fetchRowCount);
            }
        }
    }

    public WallTableStat getTableStatWithLowerName(String lowerCaseName) {
        WallTableStat stat = tableStats.get(lowerCaseName);
        if (stat == null) {
            if (tableStats.size() > 10000) {
                return null;
            }

            tableStats.putIfAbsent(lowerCaseName, new WallTableStat());
            stat = tableStats.get(lowerCaseName);
        }
        return stat;
    }

    public WallFunctionStat getFunctionStat(String functionName) {
        String lowerCaseName = functionName.toLowerCase();
        return getFunctionStatWithLowerName(lowerCaseName);
    }

    public WallFunctionStat getFunctionStatWithLowerName(String lowerCaseName) {
        WallFunctionStat stat = functionStats.get(lowerCaseName);
        if (stat == null) {
            if (functionStats.size() > 10000) {
                return null;
            }

            functionStats.putIfAbsent(lowerCaseName, new WallFunctionStat());
            stat = functionStats.get(lowerCaseName);
        }
        return stat;
    }

    public WallConfig getConfig() {
        return config;
    }

    public WallSqlStat addWhiteSql(String sql, Map<String, WallSqlTableStat> tableStats,
                                   Map<String, WallSqlFunctionStat> functionStats, boolean syntaxError) {
        lock.writeLock().lock();
        try {
            if (whiteList == null) {
                whiteList = new LRUCache<String, WallSqlStat>(whiteSqlMaxSize);
            }

            if (whiteMergedList == null) {
                whiteMergedList = new LRUCache<String, WallSqlStat>(whiteSqlMaxSize);
            }

            WallSqlStat wallStat = whiteList.get(sql);
            if (wallStat == null) {
                String mergedSql;
                try {
                    mergedSql = ParameterizedOutputVisitorUtils.parameterize(sql, dbType);
                } catch (Exception ex) {
                    mergedSql = sql;
                }
                wallStat = whiteMergedList.get(mergedSql);
                if (wallStat == null) {
                    wallStat = new WallSqlStat(tableStats, functionStats, syntaxError);
                    whiteMergedList.put(mergedSql, wallStat);
                    wallStat.setSample(sql);
                }

                wallStat.incrementAndGetExecuteCount();
                whiteList.put(sql, wallStat);
            }

            return wallStat;
        } finally {
            lock.writeLock().unlock();
        }
    }

    public WallSqlStat addBlackSql(String sql, Map<String, WallSqlTableStat> tableStats,
                                   Map<String, WallSqlFunctionStat> functionStats, List<Violation> violations,
                                   boolean syntaxError) {
        lock.writeLock().lock();
        try {
            if (blackList == null) {
                blackList = new LRUCache<String, WallSqlStat>(blackSqlMaxSize);
            }

            if (blackMergedList == null) {
                blackMergedList = new LRUCache<String, WallSqlStat>(blackSqlMaxSize);
            }

            WallSqlStat wallStat = blackList.get(sql);
            if (wallStat == null) {
                String mergedSql;
                try {
                    mergedSql = ParameterizedOutputVisitorUtils.parameterize(sql, dbType);
                } catch (Exception ex) {
                    mergedSql = sql;
                }
                wallStat = blackMergedList.get(mergedSql);
                if (wallStat == null) {
                    wallStat = new WallSqlStat(tableStats, functionStats, violations, syntaxError);
                    blackMergedList.put(mergedSql, wallStat);
                    wallStat.setSample(sql);
                }

                wallStat.incrementAndGetExecuteCount();
                blackList.put(sql, wallStat);
            }

            return wallStat;
        } finally {
            lock.writeLock().unlock();
        }
    }

    public Set<String> getWhiteList() {
        Set<String> hashSet = new HashSet<String>();
        lock.readLock().lock();
        try {
            if (whiteList != null) {
                hashSet.addAll(whiteList.keySet());
            }
        } finally {
            lock.readLock().unlock();
        }

        return Collections.<String> unmodifiableSet(hashSet);
    }

    public Set<String> getSqlList() {
        Set<String> hashSet = new HashSet<String>();
        lock.readLock().lock();
        try {
            if (whiteMergedList != null) {
                hashSet.addAll(whiteMergedList.keySet());
            }
            if (blackMergedList != null) {
                hashSet.addAll(blackMergedList.keySet());
            }
        } finally {
            lock.readLock().unlock();
        }

        return Collections.<String> unmodifiableSet(hashSet);
    }

    public Set<String> getBlackList() {
        Set<String> hashSet = new HashSet<String>();
        lock.readLock().lock();
        try {
            if (blackList != null) {
                hashSet.addAll(blackList.keySet());
            }
        } finally {
            lock.readLock().unlock();
        }

        return Collections.<String> unmodifiableSet(hashSet);
    }

    public void clearCache() {
        lock.writeLock().lock();
        try {
            if (whiteList != null) {
                whiteList = null;
            }
            if (whiteMergedList != null) {
                whiteMergedList = null;
            }

            if (blackList != null) {
                blackList = null;
            }
            if (blackMergedList != null) {
                blackMergedList = null;
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    public void clearWhiteList() {
        lock.writeLock().lock();
        try {
            if (whiteList != null) {
                whiteList = null;
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    public void clearBlackList() {
        lock.writeLock().lock();
        try {
            if (blackList != null) {
                blackList = null;
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    public WallSqlStat getWhiteSql(String sql) {
        lock.readLock().lock();
        try {
            if (whiteList == null) {
                return null;
            }

            return whiteList.get(sql);
        } finally {
            lock.readLock().unlock();
        }
    }

    public WallSqlStat getBlackSql(String sql) {
        lock.readLock().lock();
        try {
            if (blackList == null) {
                return null;
            }

            return blackList.get(sql);
        } finally {
            lock.readLock().unlock();
        }
    }

    public boolean whiteContains(String sql) {
        return getWhiteSql(sql) != null;
    }

    public abstract SQLStatementParser createParser(String sql);

    public abstract WallVisitor createWallVisitor();

    public abstract ExportParameterVisitor createExportParameterVisitor();

    public boolean checkValid(String sql) {
        WallContext originalContext = WallContext.current();

        try {
            WallContext.create(dbType);
            WallCheckResult result = checkInternal(sql);
            return result.getViolations().isEmpty();
        } finally {

            if (originalContext == null) {
                WallContext.clearContext();
            }
        }
    }

    public void incrementCommentDeniedCount() {
        this.commentDeniedStat.incrementAndGetDenyCount();
    }

    public boolean checkDenyFunction(String functionName) {
        if (functionName == null) {
            return true;
        }

        functionName = functionName.toLowerCase();

        if (getConfig().getDenyFunctions().contains(functionName)) {
            return false;
        }

        return true;
    }

    public boolean checkDenySchema(String schemaName) {
        if (schemaName == null) {
            return true;
        }

        if (!this.config.isSchemaCheck()) {
            return true;
        }

        schemaName = schemaName.toLowerCase();
        if (getConfig().getDenySchemas().contains(schemaName)) {
            return false;
        }

        return true;
    }

    public boolean checkDenyTable(String tableName) {
        if (tableName == null) {
            return true;
        }

        tableName = WallVisitorUtils.form(tableName);
        if (getConfig().getDenyTables().contains(tableName)) {
            return false;
        }

        return true;
    }

    public boolean checkReadOnlyTable(String tableName) {
        if (tableName == null) {
            return true;
        }

        tableName = WallVisitorUtils.form(tableName);
        if (getConfig().isReadOnly(tableName)) {
            return false;
        }

        return true;
    }

    public WallDenyStat getCommentDenyStat() {
        return this.commentDeniedStat;
    }

    public WallCheckResult check(String sql) {
        WallContext originalContext = WallContext.current();

        try {
            WallContext.createIfNotExists(dbType);
            return checkInternal(sql);
        } finally {
            if (originalContext == null) {
                WallContext.clearContext();
            }
        }
    }

    private WallCheckResult checkInternal(String sql) {
        checkCount.incrementAndGet();

        WallContext context = WallContext.current();

        if (config.isDoPrivilegedAllow() && ispPivileged()) {
            WallCheckResult checkResult = new WallCheckResult();
            checkResult.setSql(sql);
            return checkResult;
        }

        // first step, check whiteList
        boolean mulltiTenant = config.getTenantTablePattern() != null && config.getTenantTablePattern().length() > 0;
        if (!mulltiTenant) {
            WallCheckResult checkResult = checkWhiteAndBlackList(sql);
            if (checkResult != null) {
                checkResult.setSql(sql);
                return checkResult;
            }
        }

        hardCheckCount.incrementAndGet();
        final List<Violation> violations = new ArrayList<Violation>();
        List<SQLStatement> statementList = new ArrayList<SQLStatement>();
        boolean syntaxError = false;
        try {
            SQLStatementParser parser = createParser(sql);
            parser.getLexer().setCommentHandler(WallCommentHandler.instance);

            if (!config.isCommentAllow()) {
                parser.getLexer().setAllowComment(false); // deny comment
            }

            parser.parseStatementList(statementList);

            final Token lastToken = parser.getLexer().token();
            if (lastToken != Token.EOF) {
                violations.add(new IllegalSQLObjectViolation(ErrorCode.SYNTAX_ERROR, "not terminal sql, token "
                                                                                     + lastToken, sql));
            }
        } catch (NotAllowCommentException e) {
            violations.add(new SyntaxErrorViolation(e, sql));
            incrementCommentDeniedCount();
        } catch (ParserException e) {
            syntaxErrrorCount.incrementAndGet();
            syntaxError = true;
            if (config.isStrictSyntaxCheck()) {
                violations.add(new SyntaxErrorViolation(e, sql));
            }
        } catch (Exception e) {
            violations.add(new SyntaxErrorViolation(e, sql));
        }

        if (statementList.size() > 1 && !config.isMultiStatementAllow()) {
            violations.add(new IllegalSQLObjectViolation(ErrorCode.MULTI_STATEMENT, "multi-statement not allow", sql));
        }

        WallVisitor visitor = createWallVisitor();

        if (statementList.size() > 0) {
            SQLStatement stmt = statementList.get(0);
            try {
                stmt.accept(visitor);
            } catch (ParserException e) {
                violations.add(new SyntaxErrorViolation(e, sql));
            }
        }

        if (visitor.getViolations().size() > 0) {
            violations.addAll(visitor.getViolations());
        }

        if (visitor.getViolations().size() == 0 && context != null && context.getWarnnings() >= 2) {
            if (context.getDeleteNoneConditionWarnnings() > 0) {
                violations.add(new IllegalSQLObjectViolation(ErrorCode.NONE_CONDITION, "delete none condition", sql));
            } else if (context.getUpdateNoneConditionWarnnings() > 0) {
                violations.add(new IllegalSQLObjectViolation(ErrorCode.NONE_CONDITION, "update none condition", sql));
            } else if (context.getCommentCount() > 0) {
                violations.add(new IllegalSQLObjectViolation(ErrorCode.COMMIT_NOT_ALLOW, "comment not allow", sql));
            } else if (context.getLikeNumberWarnnings() > 0) {
                violations.add(new IllegalSQLObjectViolation(ErrorCode.COMMIT_NOT_ALLOW, "like number", sql));
            } else {
                violations.add(new IllegalSQLObjectViolation(ErrorCode.COMPOUND, "multi-warnnings", sql));
            }
        }

        WallSqlStat sqlStat = null;
        if (violations.size() > 0) {
            violationCount.incrementAndGet();

            if (sql.length() < MAX_SQL_LENGTH) {
                sqlStat = addBlackSql(sql, context.getTableStats(), context.getFunctionStats(), violations, syntaxError);
            }
        } else {
            if (sql.length() < MAX_SQL_LENGTH) {
                sqlStat = addWhiteSql(sql, context.getTableStats(), context.getFunctionStats(), syntaxError);
            }
        }

        Map<String, WallSqlTableStat> tableStats = null;
        Map<String, WallSqlFunctionStat> functionStats = null;
        if (context != null) {
            tableStats = context.getTableStats();
            functionStats = context.getFunctionStats();
            recordStats(tableStats, functionStats);
        }

        WallCheckResult result;
        if (sqlStat != null) {
            context.setSqlStat(sqlStat);
            result = new WallCheckResult(sqlStat, statementList);
        } else {
            result = new WallCheckResult(sqlStat, violations, tableStats, functionStats, statementList, syntaxError);
        }

        String resultSql;
        if (visitor.isSqlModified()) {
            resultSql = SQLUtils.toSQLString(statementList, dbType);
        } else {
            resultSql = sql;
        }
        result.setSql(resultSql);

        return result;
    }

    private WallCheckResult checkWhiteAndBlackList(String sql) {
        {
            WallSqlStat sqlStat = getWhiteSql(sql);
            if (sqlStat != null) {
                whiteListHitCount.incrementAndGet();
                sqlStat.incrementAndGetExecuteCount();

                if (sqlStat.isSyntaxError()) {
                    syntaxErrrorCount.incrementAndGet();
                }

                recordStats(sqlStat.getTableStats(), sqlStat.getFunctionStats());
                WallContext context = WallContext.current();
                if (context != null) {
                    context.setSqlStat(sqlStat);
                }
                return new WallCheckResult(sqlStat);
            }
        }
        // check black list
        {
            WallSqlStat sqlStat = getBlackSql(sql);
            if (sqlStat != null) {
                blackListHitCount.incrementAndGet();
                violationCount.incrementAndGet();

                if (sqlStat.isSyntaxError()) {
                    syntaxErrrorCount.incrementAndGet();
                }

                sqlStat.incrementAndGetExecuteCount();
                recordStats(sqlStat.getTableStats(), sqlStat.getFunctionStats());

                return new WallCheckResult(sqlStat);
            }
        }

        return null;
    }

    void recordStats(Map<String, WallSqlTableStat> tableStats, Map<String, WallSqlFunctionStat> functionStats) {
        if (tableStats != null) {
            for (Map.Entry<String, WallSqlTableStat> entry : tableStats.entrySet()) {
                String tableName = entry.getKey();
                WallSqlTableStat sqlTableStat = entry.getValue();
                WallTableStat tableStat = getTableStat(tableName);
                if (tableStat != null) {
                    tableStat.addSqlTableStat(sqlTableStat);
                }
            }
        }
        if (functionStats != null) {
            for (Map.Entry<String, WallSqlFunctionStat> entry : functionStats.entrySet()) {
                String tableName = entry.getKey();
                WallSqlFunctionStat sqlTableStat = entry.getValue();
                WallFunctionStat functionStat = getFunctionStatWithLowerName(tableName);
                if (functionStat != null) {
                    functionStat.addSqlFunctionStat(sqlTableStat);
                }
            }
        }
    }

    public static boolean ispPivileged() {
        Boolean value = privileged.get();
        if (value == null) {
            return false;
        }

        return value.booleanValue();
    }

    public static <T> T doPrivileged(PrivilegedAction<T> action) {
        final Boolean original = privileged.get();
        privileged.set(Boolean.TRUE);
        try {
            return action.run();
        } finally {
            privileged.set(original);
        }
    }

    private static final ThreadLocal<Object> tenantValueLocal = new ThreadLocal<Object>();

    public static void setTenantValue(Object value) {
        tenantValueLocal.set(value);
    }

    public static Object getTenantValue() {
        return tenantValueLocal.get();
    }

    public long getWhiteListHitCount() {
        return whiteListHitCount.get();
    }

    public long getBlackListHitCount() {
        return blackListHitCount.get();
    }

    public long getSyntaxErrorCount() {
        return syntaxErrrorCount.get();
    }

    public long getCheckCount() {
        return checkCount.get();
    }

    public long getViolationCount() {
        return violationCount.get();
    }

    public long getHardCheckCount() {
        return hardCheckCount.get();
    }

    public long getViolationEffectRowCount() {
        return violationEffectRowCount.get();
    }

    public void addViolationEffectRowCount(long rowCount) {
        violationEffectRowCount.addAndGet(rowCount);
    }

    public static class WallCommentHandler implements Lexer.CommentHandler {

        public final static WallCommentHandler instance = new WallCommentHandler();

        @Override
        public boolean handle(Token lastToken, String comment) {
            if (lastToken == null) {
                return false;
            }

            switch (lastToken) {
                case SELECT:
                case INSERT:
                case DELETE:
                case UPDATE:
                case TRUNCATE:
                case SET:
                case CREATE:
                case ALTER:
                case DROP:
                case SHOW:
                case REPLACE:
                    return true;
                default:
                    break;
            }

            WallContext context = WallContext.current();
            if (context != null) {
                context.incrementCommentCount();
            }

            return false;
        }
    }

    public WallProviderStatValue getStatValue(boolean reset) {
        WallProviderStatValue statValue = new WallProviderStatValue();

        statValue.setCheckCount(get(checkCount, reset));
        statValue.setHardCheckCount(get(hardCheckCount, reset));
        statValue.setViolationCount(get(violationCount, reset));
        statValue.setViolationEffectRowCount(get(violationEffectRowCount, reset));
        statValue.setBlackListHitCount(get(blackListHitCount, reset));
        statValue.setWhiteListHitCount(get(whiteListHitCount, reset));
        statValue.setSyntaxErrorCount(get(syntaxErrrorCount, reset));

        for (Map.Entry<String, WallTableStat> entry : this.tableStats.entrySet()) {
            String tableName = entry.getKey();
            WallTableStat tableStat = entry.getValue();

            WallTableStatValue tableStatValue = tableStat.getStatValue(reset);
            tableStatValue.setName(tableName);

            statValue.getTables().add(tableStatValue);
        }

        for (Map.Entry<String, WallFunctionStat> entry : this.functionStats.entrySet()) {
            String functionName = entry.getKey();
            WallFunctionStat functionStat = entry.getValue();

            WallFunctionStatValue functionStatValue = functionStat.getStatValue(reset);
            functionStatValue.setName(functionName);

            statValue.getFunctions().add(functionStatValue);
        }

        final Lock lock = reset ? this.lock.writeLock() : this.lock.readLock();
        lock.lock();
        try {
            if (this.whiteMergedList != null) {
                for (Map.Entry<String, WallSqlStat> entry : whiteMergedList.entrySet()) {
                    String sql = entry.getKey();
                    WallSqlStat sqlStat = entry.getValue();
                    WallSqlStatValue sqlStatValue = sqlStat.getStatValue(reset);
                    sqlStatValue.setSql(sql);
                    statValue.getWhiteList().add(sqlStatValue);
                }
            }

            if (this.blackMergedList != null) {
                for (Map.Entry<String, WallSqlStat> entry : blackMergedList.entrySet()) {
                    String sql = entry.getKey();
                    WallSqlStat sqlStat = entry.getValue();
                    WallSqlStatValue sqlStatValue = sqlStat.getStatValue(reset);
                    sqlStatValue.setSql(sql);
                    statValue.getBlackList().add(sqlStatValue);
                }
            }
        } finally {
            lock.unlock();
        }

        return statValue;
    }

    public Map<String, Object> getStatsMap() {
        return getStatValue(false).toMap();
    }
}
