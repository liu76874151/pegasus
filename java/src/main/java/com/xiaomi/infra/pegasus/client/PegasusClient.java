// Copyright (c) 2017, Xiaomi, Inc.  All rights reserved.
// This source code is licensed under the Apache License Version 2.0, which
// can be found in the LICENSE file in the root directory of this source tree.

package com.xiaomi.infra.pegasus.client;

import dsn.api.Cluster;
import dsn.api.KeyHasher;
import dsn.utils.tools;

import org.apache.commons.lang3.Validate;
import org.apache.commons.lang3.tuple.ImmutablePair;
import org.apache.commons.lang3.tuple.Pair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * @author qinzuoyan
 *
 * Implementation of {@link PegasusClientInterface}.
 */
public class PegasusClient implements PegasusClientInterface {
    private static final Logger LOGGER = LoggerFactory.getLogger(PegasusClient.class);

    private final Properties config;
    private final Cluster cluster;
    private final ConcurrentHashMap<String, PegasusTable> tableMap;
    private final Object tableMapLock;

    private static class PegasusHasher implements KeyHasher {
        @Override
        public long hash(byte[] key) {
            Validate.isTrue(key != null && key.length >= 2);
            ByteBuffer buf = ByteBuffer.wrap(key);
            int hashKeyLen = 0xFFFF & buf.getShort();
            Validate.isTrue(hashKeyLen != 0xFFFF && (2 + hashKeyLen <= key.length));
            return hashKeyLen == 0 ? tools.dsn_crc64(key, 2, key.length - 2) :
                    tools.dsn_crc64(key, 2, hashKeyLen);
        }
    }

    private PegasusTable getTable(String tableName) throws PException {
        PegasusTable table = tableMap.get(tableName);
        if (table == null) {
            synchronized (tableMapLock) {
                table = tableMap.get(tableName);
                if (table == null) {
                    try {
                        table = new PegasusTable(this, cluster.openTable(tableName, new PegasusHasher()));
                    } catch (Throwable e) {
                        throw new PException(e);
                    }
                    tableMap.put(tableName, table);
                }
            }
        }
        return table;
    }

    // pegasus client configuration keys
    public static final String[] PEGASUS_CLIENT_CONFIG_KEYS = new String[] {
            Cluster.PEGASUS_META_SERVERS_KEY,
            Cluster.PEGASUS_OPERATION_TIMEOUT_KEY,
            Cluster.PEGASUS_ASYNC_WORKERS_KEY,
            Cluster.PEGASUS_ENABLE_PERF_COUNTER_KEY,
            Cluster.PEGASUS_PERF_COUNTER_TAGS_KEY
    };

    // configPath could be:
    // - zk path: zk://host1:port1,host2:port2,host3:port3/path/to/config
    // - local file path: file:///path/to/config
    // - resource path: resource:///path/to/config
    public PegasusClient(String configPath) throws PException {
        this(PConfigUtil.loadConfiguration(configPath));
    }

    public PegasusClient(Properties config) throws PException {
        this.config = config;
        this.cluster = Cluster.createCluster(config);
        this.tableMap = new ConcurrentHashMap<String, PegasusTable>();
        this.tableMapLock = new Object();
        LOGGER.info(getConfigurationString());
    }

    public static byte[] generateKey(byte[] hashKey, byte[] sortKey) {
        int hashKeyLen = (hashKey == null ? 0 : hashKey.length);
        Validate.isTrue(hashKeyLen < 0xFFFF, 
            "length of hash key must be less than UINT16_MAX");
        int sortKeyLen = (sortKey == null ? 0 : sortKey.length);
        // default byte order of ByteBuffer is BIG_ENDIAN
        ByteBuffer buf = ByteBuffer.allocate(2 + hashKeyLen + sortKeyLen);
        buf.putShort((short)hashKeyLen);
        if (hashKeyLen > 0) {
            buf.put(hashKey);
        }
        if (sortKeyLen > 0) {
            buf.put(sortKey);
        }
        return buf.array();
    }
    
    public static byte[] generateNextBytes(byte[] hashKey) {
        int hashKeyLen = hashKey == null ? 0 : hashKey.length;
        Validate.isTrue(hashKeyLen <= 0xFFFF, 
            "length of hash key must be less than UINT16_MAX");
        ByteBuffer buf = ByteBuffer.allocate(2 + hashKeyLen);
        buf.putShort((short)hashKeyLen);
        if (hashKeyLen > 0) {
            buf.put(hashKey);
        }
        byte[] array = buf.array();
        int i = array.length - 1;
        for (; i >= 0; i--) {
            // 0xFF will look like -1
            if (array[i] != -1) {
                array[i]++;
                break;
            }
        }
        
        return Arrays.copyOf(array, i + 1);
    }
    
    public static Pair<byte[], byte[]> restoreKey(byte[] key) {
        Validate.isTrue(key != null && key.length >= 2);
        ByteBuffer buf = ByteBuffer.wrap(key);
        int hashKeyLen = 0xFFFF & buf.getShort();
        Validate.isTrue(hashKeyLen != 0xFFFF && (2 + hashKeyLen <= key.length));
        return new ImmutablePair<byte[], byte[]>(
            Arrays.copyOfRange(key, 2, 2 + hashKeyLen),
            Arrays.copyOfRange(key, 2 + hashKeyLen, key.length)
        );
    }
    
    public static int bytesCompare(byte[] left, byte[] right) {
        int len = Math.min(left.length, right.length);
        for (int i = 0; i < len; i++) {
            int ret = (0xFF & left[i]) - (0xFF & right[i]);
            if (ret != 0)
                return ret;
        }
        return left.length - right.length;
    }

    public String getConfigurationString() {
        String configString = "PegasusClient Configuration:\n";
        if (this.config == null) {
            return configString;
        }
        for (int i = 0; i < PEGASUS_CLIENT_CONFIG_KEYS.length; ++i) {
            configString += (PEGASUS_CLIENT_CONFIG_KEYS[i] + "="
                    + this.config.getProperty(PEGASUS_CLIENT_CONFIG_KEYS[i], "") + "\n");
        }
        return configString;
    }

    @Override
    public void close() {
        cluster.close();
    }

    @Override
    public PegasusTableInterface openTable(String tableName) throws PException {
        return getTable(tableName);
    }

    @Override
    public Properties getConfiguration() {
        return config;
    }

    @Override
    public boolean exist(String tableName, byte[] hashKey, byte[] sortKey) throws PException {
        PegasusTable tb = getTable(tableName);
        return tb.exist(hashKey, sortKey, 0);
    }

    @Override
    public long sortKeyCount(String tableName, byte[] hashKey) throws PException {
        PegasusTable tb = getTable(tableName);
        return tb.sortKeyCount(hashKey, 0);
    }

    @Override
    public byte[] get(String tableName, byte[] hashKey, byte[] sortKey) throws PException {
        PegasusTable tb = getTable(tableName);
        return tb.get(hashKey, sortKey, 0);
    }

    @Override
    public void batchGet(String tableName, List<Pair<byte[], byte[]>> keys, List<byte[]> values) throws PException {
        PegasusTable tb = getTable(tableName);
        tb.batchGet(keys, values, 0);
    }

    @Override
    public int batchGet2(String tableName, List<Pair<byte[], byte[]>> keys,
                         List<Pair<PException, byte[]>> values) throws PException {
        PegasusTable tb = getTable(tableName);
        return tb.batchGet2(keys, values, 0);
    }

    @Override
    public boolean multiGet(String tableName, byte[] hashKey, List<byte[]> sortKeys, int maxFetchCount, int maxFetchSize,
                            List<Pair<byte[], byte[]>> values) throws PException {
        if (values == null) {
            throw new PException("Invalid parameter: values should not be null");
        }
        PegasusTable tb = getTable(tableName);
        PegasusTableInterface.MultiGetResult res = tb.multiGet(hashKey, sortKeys, maxFetchCount, maxFetchSize, 0);
        for (Pair<byte[], byte[]> kv : res.values) {
            values.add(kv);
        }
        return res.allFetched;
    }

    @Override
    public boolean multiGet(String tableName, byte[] hashKey, List<byte[]> sortKeys,
                            List<Pair<byte[], byte[]>> values) throws PException {
        return multiGet(tableName, hashKey, sortKeys, 100, 1000000, values);
    }

    @Override
    public void batchMultiGet(String tableName, List<Pair<byte[], List<byte[]>>> keys,
                              List<HashKeyData> values) throws PException {
        PegasusTable tb = getTable(tableName);
        tb.batchMultiGet(keys, values, 0);
    }

    @Override
    public int batchMultiGet2(String tableName, List<Pair<byte[], List<byte[]>>> keys,
                              List<Pair<PException, HashKeyData>> results) throws PException {
        PegasusTable tb = getTable(tableName);
        return tb.batchMultiGet2(keys, results, 0);
    }

    @Override
    public boolean multiGetSortKeys(String tableName, byte[] hashKey, int maxFetchCount, int maxFetchSize,
                                    List<byte[]> sortKeys) throws PException {
        if (sortKeys == null) {
            throw new PException("Invalid parameter: sortKeys should not be null");
        }
        PegasusTable table = getTable(tableName);
        PegasusTableInterface.MultiGetSortKeysResult result = table.multiGetSortKeys(hashKey, maxFetchCount, maxFetchSize, 0);
        for (byte[] key: result.keys) {
            sortKeys.add(key);
        }
        return result.allFetched;
    }

    @Override
    public boolean multiGetSortKeys(String tableName, byte[] hashKey, List<byte[]> sortKeys) throws PException {
        return multiGetSortKeys(tableName, hashKey, 100, 1000000, sortKeys);
    }

    @Override
    public void set(String tableName, byte[] hashKey, byte[] sortKey, byte[] value, int ttl_seconds) throws PException {
        PegasusTable tb = getTable(tableName);
        tb.set(hashKey, sortKey, value, ttl_seconds, 0);
    }

    @Override
    public void set(String tableName, byte[] hashKey, byte[] sortKey, byte[] value) throws PException {
        set(tableName, hashKey, sortKey, value, 0);
    }

    @Override
    public void batchSet(String tableName, List<SetItem> items) throws PException {
        PegasusTable tb = getTable(tableName);
        tb.batchSet(items, 0);
    }

    @Override
    public int batchSet2(String tableName, List<SetItem> items, List<PException> results) throws PException {
        PegasusTable tb = getTable(tableName);
        return tb.batchSet2(items, results, 0);
    }

    @Override
    public void multiSet(String tableName, byte[] hashKey, List<Pair<byte[], byte[]>> values, int ttl_seconds) throws PException {
        PegasusTable tb = getTable(tableName);
        tb.multiSet(hashKey, values, ttl_seconds, 0);
    }

    @Override
    public void multiSet(String tableName, byte[] hashKey, List<Pair<byte[], byte[]>> values) throws PException {
        multiSet(tableName, hashKey, values, 0);
    }

    @Override
    public void batchMultiSet(String tableName, List<HashKeyData> items, int ttl_seconds) throws PException {
        PegasusTable tb = getTable(tableName);
        tb.batchMultiSet(items, ttl_seconds, 0);
    }

    @Override
    public void batchMultiSet(String tableName, List<HashKeyData> items) throws PException {
        batchMultiSet(tableName, items, 0);
    }

    @Override
    public int batchMultiSet2(String tableName, List<HashKeyData> items,
                              int ttl_seconds, List<PException> results) throws PException {
        PegasusTable tb = getTable(tableName);
        return tb.batchMultiSet2(items, ttl_seconds, results, 0);
    }

    @Override
    public int batchMultiSet2(String tableName, List<HashKeyData> items,
                              List<PException> results) throws PException {
        return batchMultiSet2(tableName, items, 0, results);
    }

    @Override
    public void del(String tableName, byte[] hashKey, byte[] sortKey) throws PException {
        PegasusTable tb = getTable(tableName);
        tb.del(hashKey, sortKey, 0);
    }

    @Override
    public void batchDel(String tableName, List<Pair<byte[], byte[]>> keys) throws PException {
        PegasusTable tb = getTable(tableName);
        tb.batchDel(keys, 0);
    }

    @Override
    public int batchDel2(String tableName, List<Pair<byte[], byte[]>> keys,
                         List<PException> results) throws PException {
        PegasusTable tb = getTable(tableName);
        return tb.batchDel2(keys, results, 0);
    }

    @Override
    public void multiDel(String tableName, byte[] hashKey, List<byte[]> sortKeys) throws PException {
        PegasusTable tb = getTable(tableName);
        tb.multiDel(hashKey, sortKeys, 0);
    }

    @Override
    public void batchMultiDel(String tableName, List<Pair<byte[], List<byte[]>>> keys) throws PException {
        PegasusTable tb = getTable(tableName);
        tb.batchMultiDel(keys, 0);
    }

    @Override
    public int batchMultiDel2(String tableName, List<Pair<byte[], List<byte[]>>> keys,
                              List<PException> results) throws PException {
        PegasusTable tb = getTable(tableName);
        return tb.batchMultiDel2(keys, results, 0);
    }

    @Override
    public int ttl(String tableName, byte[] hashKey, byte[] sortKey) throws PException {
        PegasusTable tb = getTable(tableName);
        return tb.ttl(hashKey, sortKey, 0);
    }

    @Override
    public PegasusScannerInterface getScanner(String tableName, byte[] hashKey,
            byte[] startSortKey, byte[] stopSortKey, ScanOptions options)
            throws PException {
        PegasusTable tb = getTable(tableName);
        return tb.getScanner(hashKey, startSortKey, stopSortKey, options);
    }

    @Override
    public List<PegasusScannerInterface> getUnorderedScanners(String tableName,
            int maxSplitCount, ScanOptions options) throws PException {
        PegasusTable tb = getTable(tableName);
        return tb.getUnorderedScanners(maxSplitCount, options);
    }
}
