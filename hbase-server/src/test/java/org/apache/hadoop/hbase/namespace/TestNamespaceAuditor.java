/**
 *
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.hadoop.hbase.namespace;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.apache.commons.lang.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hbase.CategoryBasedTimeout;
import org.apache.hadoop.hbase.Coprocessor;
import org.apache.hadoop.hbase.CoprocessorEnvironment;
import org.apache.hadoop.hbase.HBaseTestingUtility;
import org.apache.hadoop.hbase.HColumnDescriptor;
import org.apache.hadoop.hbase.HConstants;
import org.apache.hadoop.hbase.HRegionInfo;
import org.apache.hadoop.hbase.HTableDescriptor;
import org.apache.hadoop.hbase.MiniHBaseCluster;
import org.apache.hadoop.hbase.NamespaceDescriptor;
import org.apache.hadoop.hbase.TableName;
import org.apache.hadoop.hbase.Waiter;
import org.apache.hadoop.hbase.client.Admin;
import org.apache.hadoop.hbase.client.Connection;
import org.apache.hadoop.hbase.client.ConnectionFactory;
import org.apache.hadoop.hbase.client.RegionLocator;
import org.apache.hadoop.hbase.client.Table;
import org.apache.hadoop.hbase.client.TableDescriptor;
import org.apache.hadoop.hbase.coprocessor.CoprocessorHost;
import org.apache.hadoop.hbase.coprocessor.MasterCoprocessorEnvironment;
import org.apache.hadoop.hbase.coprocessor.MasterObserver;
import org.apache.hadoop.hbase.coprocessor.ObserverContext;
import org.apache.hadoop.hbase.coprocessor.RegionCoprocessorEnvironment;
import org.apache.hadoop.hbase.coprocessor.RegionObserver;
import org.apache.hadoop.hbase.coprocessor.RegionServerCoprocessorEnvironment;
import org.apache.hadoop.hbase.coprocessor.RegionServerObserver;
import org.apache.hadoop.hbase.master.HMaster;
import org.apache.hadoop.hbase.master.MasterCoprocessorHost;
import org.apache.hadoop.hbase.master.TableNamespaceManager;
import org.apache.hadoop.hbase.quotas.MasterQuotaManager;
import org.apache.hadoop.hbase.quotas.QuotaExceededException;
import org.apache.hadoop.hbase.quotas.QuotaUtil;
import org.apache.hadoop.hbase.regionserver.Region;
import org.apache.hadoop.hbase.regionserver.Store;
import org.apache.hadoop.hbase.regionserver.StoreFile;
import org.apache.hadoop.hbase.snapshot.RestoreSnapshotException;
import org.apache.hadoop.hbase.testclassification.MediumTests;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.hadoop.hbase.util.FSUtils;
import org.apache.hadoop.hbase.util.Threads;
import org.apache.zookeeper.KeeperException;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.junit.rules.TestRule;

@Category(MediumTests.class)
public class TestNamespaceAuditor {
  @Rule public final TestRule timeout = CategoryBasedTimeout.builder().
      withTimeout(this.getClass()).withLookingForStuckThread(true).build();
  private static final Log LOG = LogFactory.getLog(TestNamespaceAuditor.class);
  private static final HBaseTestingUtility UTIL = new HBaseTestingUtility();
  private static Admin ADMIN;
  private String prefix = "TestNamespaceAuditor";

  @BeforeClass
  public static void before() throws Exception {
    Configuration conf = UTIL.getConfiguration();
    conf.set(CoprocessorHost.REGION_COPROCESSOR_CONF_KEY, CustomObserver.class.getName());
    conf.setStrings(
      CoprocessorHost.MASTER_COPROCESSOR_CONF_KEY,
      MasterSyncObserver.class.getName(), CPMasterObserver.class.getName());
    conf.setInt(HConstants.HBASE_CLIENT_RETRIES_NUMBER, 5);
    conf.setBoolean(QuotaUtil.QUOTA_CONF_KEY, true);
    conf.setClass("hbase.coprocessor.regionserver.classes", CPRegionServerObserver.class,
      RegionServerObserver.class);
    UTIL.startMiniCluster(1, 1);
    waitForQuotaInitialize(UTIL);
    ADMIN = UTIL.getAdmin();
  }

  @AfterClass
  public static void tearDown() throws Exception {
    UTIL.shutdownMiniCluster();
  }

  @After
  public void cleanup() throws Exception, KeeperException {
    for (HTableDescriptor table : ADMIN.listTables()) {
      ADMIN.disableTable(table.getTableName());
      deleteTable(table.getTableName());
    }
    for (NamespaceDescriptor ns : ADMIN.listNamespaceDescriptors()) {
      if (ns.getName().startsWith(prefix)) {
        ADMIN.deleteNamespace(ns.getName());
      }
    }
    assertTrue("Quota manager not initialized", UTIL.getHBaseCluster().getMaster()
        .getMasterQuotaManager().isQuotaInitialized());
  }

  @Test
  public void testTableOperations() throws Exception {
    String nsp = prefix + "_np2";
    NamespaceDescriptor nspDesc =
        NamespaceDescriptor.create(nsp).addConfiguration(TableNamespaceManager.KEY_MAX_REGIONS, "5")
            .addConfiguration(TableNamespaceManager.KEY_MAX_TABLES, "2").build();
    ADMIN.createNamespace(nspDesc);
    assertNotNull("Namespace descriptor found null.", ADMIN.getNamespaceDescriptor(nsp));
    assertEquals(ADMIN.listNamespaceDescriptors().length, 3);
    HColumnDescriptor fam1 = new HColumnDescriptor("fam1");

    HTableDescriptor tableDescOne =
        new HTableDescriptor(TableName.valueOf(nsp + TableName.NAMESPACE_DELIM + "table1"));
    tableDescOne.addFamily(fam1);
    HTableDescriptor tableDescTwo =
        new HTableDescriptor(TableName.valueOf(nsp + TableName.NAMESPACE_DELIM + "table2"));
    tableDescTwo.addFamily(fam1);
    HTableDescriptor tableDescThree =
        new HTableDescriptor(TableName.valueOf(nsp + TableName.NAMESPACE_DELIM + "table3"));
    tableDescThree.addFamily(fam1);
    ADMIN.createTable(tableDescOne);
    boolean constraintViolated = false;
    try {
      ADMIN.createTable(tableDescTwo, Bytes.toBytes("AAA"), Bytes.toBytes("ZZZ"), 5);
    } catch (Exception exp) {
      assertTrue(exp instanceof IOException);
      constraintViolated = true;
    } finally {
      assertTrue("Constraint not violated for table " + tableDescTwo.getTableName(),
        constraintViolated);
    }
    ADMIN.createTable(tableDescTwo, Bytes.toBytes("AAA"), Bytes.toBytes("ZZZ"), 4);
    NamespaceTableAndRegionInfo nspState = getQuotaManager().getState(nsp);
    assertNotNull(nspState);
    assertTrue(nspState.getTables().size() == 2);
    assertTrue(nspState.getRegionCount() == 5);
    constraintViolated = false;
    try {
      ADMIN.createTable(tableDescThree);
    } catch (Exception exp) {
      assertTrue(exp instanceof IOException);
      constraintViolated = true;
    } finally {
      assertTrue("Constraint not violated for table " + tableDescThree.getTableName(),
        constraintViolated);
    }
  }

  @Test
  public void testValidQuotas() throws Exception {
    boolean exceptionCaught = false;
    FileSystem fs = UTIL.getHBaseCluster().getMaster().getMasterFileSystem().getFileSystem();
    Path rootDir = UTIL.getHBaseCluster().getMaster().getMasterFileSystem().getRootDir();
    NamespaceDescriptor nspDesc =
        NamespaceDescriptor.create(prefix + "vq1")
            .addConfiguration(TableNamespaceManager.KEY_MAX_REGIONS, "hihdufh")
            .addConfiguration(TableNamespaceManager.KEY_MAX_TABLES, "2").build();
    try {
      ADMIN.createNamespace(nspDesc);
    } catch (Exception exp) {
      LOG.warn(exp);
      exceptionCaught = true;
    } finally {
      assertTrue(exceptionCaught);
      assertFalse(fs.exists(FSUtils.getNamespaceDir(rootDir, nspDesc.getName())));
    }
    nspDesc =
        NamespaceDescriptor.create(prefix + "vq2")
            .addConfiguration(TableNamespaceManager.KEY_MAX_REGIONS, "-456")
            .addConfiguration(TableNamespaceManager.KEY_MAX_TABLES, "2").build();
    try {
      ADMIN.createNamespace(nspDesc);
    } catch (Exception exp) {
      LOG.warn(exp);
      exceptionCaught = true;
    } finally {
      assertTrue(exceptionCaught);
      assertFalse(fs.exists(FSUtils.getNamespaceDir(rootDir, nspDesc.getName())));
    }
    nspDesc =
        NamespaceDescriptor.create(prefix + "vq3")
            .addConfiguration(TableNamespaceManager.KEY_MAX_REGIONS, "10")
            .addConfiguration(TableNamespaceManager.KEY_MAX_TABLES, "sciigd").build();
    try {
      ADMIN.createNamespace(nspDesc);
    } catch (Exception exp) {
      LOG.warn(exp);
      exceptionCaught = true;
    } finally {
      assertTrue(exceptionCaught);
      assertFalse(fs.exists(FSUtils.getNamespaceDir(rootDir, nspDesc.getName())));
    }
    nspDesc =
        NamespaceDescriptor.create(prefix + "vq4")
            .addConfiguration(TableNamespaceManager.KEY_MAX_REGIONS, "10")
            .addConfiguration(TableNamespaceManager.KEY_MAX_TABLES, "-1500").build();
    try {
      ADMIN.createNamespace(nspDesc);
    } catch (Exception exp) {
      LOG.warn(exp);
      exceptionCaught = true;
    } finally {
      assertTrue(exceptionCaught);
      assertFalse(fs.exists(FSUtils.getNamespaceDir(rootDir, nspDesc.getName())));
    }
  }

  @Test
  public void testDeleteTable() throws Exception {
    String namespace = prefix + "_dummy";
    NamespaceDescriptor nspDesc =
        NamespaceDescriptor.create(namespace)
            .addConfiguration(TableNamespaceManager.KEY_MAX_REGIONS, "100")
            .addConfiguration(TableNamespaceManager.KEY_MAX_TABLES, "3").build();
    ADMIN.createNamespace(nspDesc);
    assertNotNull("Namespace descriptor found null.", ADMIN.getNamespaceDescriptor(namespace));
    NamespaceTableAndRegionInfo stateInfo = getNamespaceState(nspDesc.getName());
    assertNotNull("Namespace state found null for " + namespace, stateInfo);
    HColumnDescriptor fam1 = new HColumnDescriptor("fam1");
    HTableDescriptor tableDescOne =
        new HTableDescriptor(TableName.valueOf(namespace + TableName.NAMESPACE_DELIM + "table1"));
    tableDescOne.addFamily(fam1);
    HTableDescriptor tableDescTwo =
        new HTableDescriptor(TableName.valueOf(namespace + TableName.NAMESPACE_DELIM + "table2"));
    tableDescTwo.addFamily(fam1);
    ADMIN.createTable(tableDescOne);
    ADMIN.createTable(tableDescTwo, Bytes.toBytes("AAA"), Bytes.toBytes("ZZZ"), 5);
    stateInfo = getNamespaceState(nspDesc.getName());
    assertNotNull("Namespace state found to be null.", stateInfo);
    assertEquals(2, stateInfo.getTables().size());
    assertEquals(5, stateInfo.getRegionCountOfTable(tableDescTwo.getTableName()));
    assertEquals(6, stateInfo.getRegionCount());
    ADMIN.disableTable(tableDescOne.getTableName());
    deleteTable(tableDescOne.getTableName());
    stateInfo = getNamespaceState(nspDesc.getName());
    assertNotNull("Namespace state found to be null.", stateInfo);
    assertEquals(5, stateInfo.getRegionCount());
    assertEquals(1, stateInfo.getTables().size());
    ADMIN.disableTable(tableDescTwo.getTableName());
    deleteTable(tableDescTwo.getTableName());
    ADMIN.deleteNamespace(namespace);
    stateInfo = getNamespaceState(namespace);
    assertNull("Namespace state not found to be null.", stateInfo);
  }

  public static class CPRegionServerObserver implements RegionServerObserver {
    private volatile boolean shouldFailMerge = false;

    public void failMerge(boolean fail) {
      shouldFailMerge = fail;
    }

    private boolean triggered = false;

    public synchronized void waitUtilTriggered() throws InterruptedException {
      while (!triggered) {
        wait();
      }
    }

    @Override
    public synchronized void preMerge(ObserverContext<RegionServerCoprocessorEnvironment> ctx,
        Region regionA, Region regionB) throws IOException {
      triggered = true;
      notifyAll();
      if (shouldFailMerge) {
        throw new IOException("fail merge");
      }
    }
  }

  public static class CPMasterObserver implements MasterObserver {
    private volatile boolean shouldFailMerge = false;

    public void failMerge(boolean fail) {
      shouldFailMerge = fail;
    }

    @Override
    public synchronized void preMergeRegionsAction(
        final ObserverContext<MasterCoprocessorEnvironment> ctx,
        final HRegionInfo[] regionsToMerge) throws IOException {
      notifyAll();
      if (shouldFailMerge) {
        throw new IOException("fail merge");
      }
    }
  }

  @Test
  public void testRegionMerge() throws Exception {
    String nsp1 = prefix + "_regiontest";
    final int initialRegions = 3;
    NamespaceDescriptor nspDesc =
        NamespaceDescriptor.create(nsp1)
            .addConfiguration(TableNamespaceManager.KEY_MAX_REGIONS, "" + initialRegions)
            .addConfiguration(TableNamespaceManager.KEY_MAX_TABLES, "2").build();
    ADMIN.createNamespace(nspDesc);
    final TableName tableTwo = TableName.valueOf(nsp1 + TableName.NAMESPACE_DELIM + "table2");
    byte[] columnFamily = Bytes.toBytes("info");
    HTableDescriptor tableDescOne = new HTableDescriptor(tableTwo);
    tableDescOne.addFamily(new HColumnDescriptor(columnFamily));
    ADMIN.createTable(tableDescOne, Bytes.toBytes("0"), Bytes.toBytes("9"), initialRegions);
    Connection connection = ConnectionFactory.createConnection(UTIL.getConfiguration());
    try (Table table = connection.getTable(tableTwo)) {
      UTIL.loadNumericRows(table, Bytes.toBytes("info"), 1000, 1999);
    }
    ADMIN.flush(tableTwo);
    List<HRegionInfo> hris = ADMIN.getTableRegions(tableTwo);
    assertEquals(initialRegions, hris.size());
    Collections.sort(hris);
    Future<?> f = ADMIN.mergeRegionsAsync(
      hris.get(0).getEncodedNameAsBytes(),
      hris.get(1).getEncodedNameAsBytes(),
      false);
    f.get(10, TimeUnit.SECONDS);

    hris = ADMIN.getTableRegions(tableTwo);
    assertEquals(initialRegions - 1, hris.size());
    Collections.sort(hris);
    ADMIN.split(tableTwo, Bytes.toBytes("3"));
    // Not much we can do here until we have split return a Future.
    Threads.sleep(5000);
    hris = ADMIN.getTableRegions(tableTwo);
    assertEquals(initialRegions, hris.size());
    Collections.sort(hris);

    // Fail region merge through Coprocessor hook
    MiniHBaseCluster cluster = UTIL.getHBaseCluster();
    MasterCoprocessorHost cpHost = cluster.getMaster().getMasterCoprocessorHost();
    Coprocessor coprocessor = cpHost.findCoprocessor(CPMasterObserver.class.getName());
    CPMasterObserver masterObserver = (CPMasterObserver) coprocessor;
    masterObserver.failMerge(true);

    f = ADMIN.mergeRegionsAsync(
      hris.get(1).getEncodedNameAsBytes(),
      hris.get(2).getEncodedNameAsBytes(),
      false);
    try {
      f.get(10, TimeUnit.SECONDS);
      fail("Merge was supposed to fail!");
    } catch (ExecutionException ee) {
      // Expected.
    }
    hris = ADMIN.getTableRegions(tableTwo);
    assertEquals(initialRegions, hris.size());
    Collections.sort(hris);
    // verify that we cannot split
    HRegionInfo hriToSplit2 = hris.get(1);
    ADMIN.split(tableTwo, Bytes.toBytes("6"));
    Thread.sleep(2000);
    assertEquals(initialRegions, ADMIN.getTableRegions(tableTwo).size());
  }

  /*
   * Create a table and make sure that the table creation fails after adding this table entry into
   * namespace quota cache. Now correct the failure and recreate the table with same name.
   * HBASE-13394
   */
  @Test
  public void testRecreateTableWithSameNameAfterFirstTimeFailure() throws Exception {
    String nsp1 = prefix + "_testRecreateTable";
    NamespaceDescriptor nspDesc =
        NamespaceDescriptor.create(nsp1)
            .addConfiguration(TableNamespaceManager.KEY_MAX_REGIONS, "20")
            .addConfiguration(TableNamespaceManager.KEY_MAX_TABLES, "1").build();
    ADMIN.createNamespace(nspDesc);
    final TableName tableOne = TableName.valueOf(nsp1 + TableName.NAMESPACE_DELIM + "table1");
    byte[] columnFamily = Bytes.toBytes("info");
    HTableDescriptor tableDescOne = new HTableDescriptor(tableOne);
    tableDescOne.addFamily(new HColumnDescriptor(columnFamily));
    MasterSyncObserver.throwExceptionInPreCreateTableAction = true;
    try {
      try {
        ADMIN.createTable(tableDescOne);
        fail("Table " + tableOne.toString() + "creation should fail.");
      } catch (Exception exp) {
        LOG.error(exp);
      }
      assertFalse(ADMIN.tableExists(tableOne));

      NamespaceTableAndRegionInfo nstate = getNamespaceState(nsp1);
      assertEquals("First table creation failed in namespace so number of tables in namespace "
          + "should be 0.", 0, nstate.getTables().size());

      MasterSyncObserver.throwExceptionInPreCreateTableAction = false;
      try {
        ADMIN.createTable(tableDescOne);
      } catch (Exception e) {
        fail("Table " + tableOne.toString() + "creation should succeed.");
        LOG.error(e);
      }
      assertTrue(ADMIN.tableExists(tableOne));
      nstate = getNamespaceState(nsp1);
      assertEquals("First table was created successfully so table size in namespace should "
          + "be one now.", 1, nstate.getTables().size());
    } finally {
      MasterSyncObserver.throwExceptionInPreCreateTableAction = false;
      if (ADMIN.tableExists(tableOne)) {
        ADMIN.disableTable(tableOne);
        deleteTable(tableOne);
      }
      ADMIN.deleteNamespace(nsp1);
    }
  }

  private NamespaceTableAndRegionInfo getNamespaceState(String namespace) throws KeeperException,
      IOException {
    return getQuotaManager().getState(namespace);
  }

  byte[] getSplitKey(byte[] startKey, byte[] endKey) {
    String skey = Bytes.toString(startKey);
    int key;
    if (StringUtils.isBlank(skey)) {
      key = Integer.parseInt(Bytes.toString(endKey))/2 ;
    } else {
      key = (int) (Integer.parseInt(skey) * 1.5);
    }
    return Bytes.toBytes("" + key);
  }

  public static class CustomObserver implements RegionObserver {
    volatile CountDownLatch postCompact;

    @Override
    public void postCompact(ObserverContext<RegionCoprocessorEnvironment> e,
                            Store store, StoreFile resultFile) throws IOException {
      postCompact.countDown();
    }

    @Override
    public void start(CoprocessorEnvironment e) throws IOException {
      postCompact = new CountDownLatch(1);
    }
  }

  @Test
  public void testStatePreserve() throws Exception {
    final String nsp1 = prefix + "_testStatePreserve";
    NamespaceDescriptor nspDesc = NamespaceDescriptor.create(nsp1)
        .addConfiguration(TableNamespaceManager.KEY_MAX_REGIONS, "20")
        .addConfiguration(TableNamespaceManager.KEY_MAX_TABLES, "10").build();
    ADMIN.createNamespace(nspDesc);
    TableName tableOne = TableName.valueOf(nsp1 + TableName.NAMESPACE_DELIM + "table1");
    TableName tableTwo = TableName.valueOf(nsp1 + TableName.NAMESPACE_DELIM + "table2");
    TableName tableThree = TableName.valueOf(nsp1 + TableName.NAMESPACE_DELIM + "table3");
    HColumnDescriptor fam1 = new HColumnDescriptor("fam1");
    HTableDescriptor tableDescOne = new HTableDescriptor(tableOne);
    tableDescOne.addFamily(fam1);
    HTableDescriptor tableDescTwo = new HTableDescriptor(tableTwo);
    tableDescTwo.addFamily(fam1);
    HTableDescriptor tableDescThree = new HTableDescriptor(tableThree);
    tableDescThree.addFamily(fam1);
    ADMIN.createTable(tableDescOne, Bytes.toBytes("1"), Bytes.toBytes("1000"), 3);
    ADMIN.createTable(tableDescTwo, Bytes.toBytes("1"), Bytes.toBytes("1000"), 3);
    ADMIN.createTable(tableDescThree, Bytes.toBytes("1"), Bytes.toBytes("1000"), 4);
    ADMIN.disableTable(tableThree);
    deleteTable(tableThree);
    // wait for chore to complete
    UTIL.waitFor(1000, new Waiter.Predicate<Exception>() {
      @Override
      public boolean evaluate() throws Exception {
       return (getNamespaceState(nsp1).getTables().size() == 2);
      }
    });
    NamespaceTableAndRegionInfo before = getNamespaceState(nsp1);
    restartMaster();
    NamespaceTableAndRegionInfo after = getNamespaceState(nsp1);
    assertEquals("Expected: " + before.getTables() + " Found: " + after.getTables(), before
        .getTables().size(), after.getTables().size());
  }

  public static void waitForQuotaInitialize(final HBaseTestingUtility util) throws Exception {
    util.waitFor(60000, new Waiter.Predicate<Exception>() {
      @Override
      public boolean evaluate() throws Exception {
        HMaster master = util.getHBaseCluster().getMaster();
        if (master == null) {
          return false;
        }
        MasterQuotaManager quotaManager = master.getMasterQuotaManager();
        return quotaManager != null && quotaManager.isQuotaInitialized();
      }
    });
  }

  private void restartMaster() throws Exception {
    UTIL.getHBaseCluster().getMaster(0).stop("Stopping to start again");
    UTIL.getHBaseCluster().waitOnMaster(0);
    UTIL.getHBaseCluster().startMaster();
    waitForQuotaInitialize(UTIL);
  }

  private NamespaceAuditor getQuotaManager() {
    return UTIL.getHBaseCluster().getMaster()
        .getMasterQuotaManager().getNamespaceQuotaManager();
  }

  public static class MasterSyncObserver implements MasterObserver {
    volatile CountDownLatch tableDeletionLatch;
    static boolean throwExceptionInPreCreateTableAction;

    @Override
    public void preDeleteTable(ObserverContext<MasterCoprocessorEnvironment> ctx,
        TableName tableName) throws IOException {
      tableDeletionLatch = new CountDownLatch(1);
    }

    @Override
    public void postCompletedDeleteTableAction(
        final ObserverContext<MasterCoprocessorEnvironment> ctx,
        final TableName tableName) throws IOException {
      tableDeletionLatch.countDown();
    }

    @Override
    public void preCreateTableAction(ObserverContext<MasterCoprocessorEnvironment> ctx,
        TableDescriptor desc, HRegionInfo[] regions) throws IOException {
      if (throwExceptionInPreCreateTableAction) {
        throw new IOException("Throw exception as it is demanded.");
      }
    }
  }

  private void deleteTable(final TableName tableName) throws Exception {
    // NOTE: We need a latch because admin is not sync,
    // so the postOp coprocessor method may be called after the admin operation returned.
    MasterSyncObserver observer = (MasterSyncObserver)UTIL.getHBaseCluster().getMaster()
      .getMasterCoprocessorHost().findCoprocessor(MasterSyncObserver.class.getName());
    ADMIN.deleteTable(tableName);
    observer.tableDeletionLatch.await();
  }

  @Test(expected = QuotaExceededException.class)
  public void testExceedTableQuotaInNamespace() throws Exception {
    String nsp = prefix + "_testExceedTableQuotaInNamespace";
    NamespaceDescriptor nspDesc =
        NamespaceDescriptor.create(nsp).addConfiguration(TableNamespaceManager.KEY_MAX_TABLES, "1")
            .build();
    ADMIN.createNamespace(nspDesc);
    assertNotNull("Namespace descriptor found null.", ADMIN.getNamespaceDescriptor(nsp));
    assertEquals(ADMIN.listNamespaceDescriptors().length, 3);
    HColumnDescriptor fam1 = new HColumnDescriptor("fam1");
    HTableDescriptor tableDescOne =
        new HTableDescriptor(TableName.valueOf(nsp + TableName.NAMESPACE_DELIM + "table1"));
    tableDescOne.addFamily(fam1);
    HTableDescriptor tableDescTwo =
        new HTableDescriptor(TableName.valueOf(nsp + TableName.NAMESPACE_DELIM + "table2"));
    tableDescTwo.addFamily(fam1);
    ADMIN.createTable(tableDescOne);
    ADMIN.createTable(tableDescTwo, Bytes.toBytes("AAA"), Bytes.toBytes("ZZZ"), 4);
  }

  @Test(expected = QuotaExceededException.class)
  public void testCloneSnapshotQuotaExceed() throws Exception {
    String nsp = prefix + "_testTableQuotaExceedWithCloneSnapshot";
    NamespaceDescriptor nspDesc =
        NamespaceDescriptor.create(nsp).addConfiguration(TableNamespaceManager.KEY_MAX_TABLES, "1")
            .build();
    ADMIN.createNamespace(nspDesc);
    assertNotNull("Namespace descriptor found null.", ADMIN.getNamespaceDescriptor(nsp));
    TableName tableName = TableName.valueOf(nsp + TableName.NAMESPACE_DELIM + "table1");
    TableName cloneTableName = TableName.valueOf(nsp + TableName.NAMESPACE_DELIM + "table2");
    HColumnDescriptor fam1 = new HColumnDescriptor("fam1");
    HTableDescriptor tableDescOne = new HTableDescriptor(tableName);
    tableDescOne.addFamily(fam1);
    ADMIN.createTable(tableDescOne);
    String snapshot = "snapshot_testTableQuotaExceedWithCloneSnapshot";
    ADMIN.snapshot(snapshot, tableName);
    ADMIN.cloneSnapshot(snapshot, cloneTableName);
    ADMIN.deleteSnapshot(snapshot);
  }

  @Test
  public void testCloneSnapshot() throws Exception {
    String nsp = prefix + "_testCloneSnapshot";
    NamespaceDescriptor nspDesc =
        NamespaceDescriptor.create(nsp).addConfiguration(TableNamespaceManager.KEY_MAX_TABLES, "2")
            .addConfiguration(TableNamespaceManager.KEY_MAX_REGIONS, "20").build();
    ADMIN.createNamespace(nspDesc);
    assertNotNull("Namespace descriptor found null.", ADMIN.getNamespaceDescriptor(nsp));
    TableName tableName = TableName.valueOf(nsp + TableName.NAMESPACE_DELIM + "table1");
    TableName cloneTableName = TableName.valueOf(nsp + TableName.NAMESPACE_DELIM + "table2");

    HColumnDescriptor fam1 = new HColumnDescriptor("fam1");
    HTableDescriptor tableDescOne = new HTableDescriptor(tableName);
    tableDescOne.addFamily(fam1);

    ADMIN.createTable(tableDescOne, Bytes.toBytes("AAA"), Bytes.toBytes("ZZZ"), 4);
    String snapshot = "snapshot_testCloneSnapshot";
    ADMIN.snapshot(snapshot, tableName);
    ADMIN.cloneSnapshot(snapshot, cloneTableName);

    int tableLength;
    try (RegionLocator locator = ADMIN.getConnection().getRegionLocator(tableName)) {
      tableLength = locator.getStartKeys().length;
    }
    assertEquals(tableName.getNameAsString() + " should have four regions.", 4, tableLength);

    try (RegionLocator locator = ADMIN.getConnection().getRegionLocator(cloneTableName)) {
      tableLength = locator.getStartKeys().length;
    }
    assertEquals(cloneTableName.getNameAsString() + " should have four regions.", 4, tableLength);

    NamespaceTableAndRegionInfo nstate = getNamespaceState(nsp);
    assertEquals("Total tables count should be 2.", 2, nstate.getTables().size());
    assertEquals("Total regions count should be.", 8, nstate.getRegionCount());

    ADMIN.deleteSnapshot(snapshot);
  }

  @Test
  public void testRestoreSnapshot() throws Exception {
    String nsp = prefix + "_testRestoreSnapshot";
    NamespaceDescriptor nspDesc =
        NamespaceDescriptor.create(nsp)
            .addConfiguration(TableNamespaceManager.KEY_MAX_REGIONS, "10").build();
    ADMIN.createNamespace(nspDesc);
    assertNotNull("Namespace descriptor found null.", ADMIN.getNamespaceDescriptor(nsp));
    TableName tableName1 = TableName.valueOf(nsp + TableName.NAMESPACE_DELIM + "table1");
    HTableDescriptor tableDescOne = new HTableDescriptor(tableName1);
    HColumnDescriptor fam1 = new HColumnDescriptor("fam1");
    tableDescOne.addFamily(fam1);
    ADMIN.createTable(tableDescOne, Bytes.toBytes("AAA"), Bytes.toBytes("ZZZ"), 4);

    NamespaceTableAndRegionInfo nstate = getNamespaceState(nsp);
    assertEquals("Intial region count should be 4.", 4, nstate.getRegionCount());

    String snapshot = "snapshot_testRestoreSnapshot";
    ADMIN.snapshot(snapshot, tableName1);

    List<HRegionInfo> regions = ADMIN.getTableRegions(tableName1);
    Collections.sort(regions);

    ADMIN.split(tableName1, Bytes.toBytes("JJJ"));
    Thread.sleep(2000);
    assertEquals("Total regions count should be 5.", 5, nstate.getRegionCount());

    ADMIN.disableTable(tableName1);
    ADMIN.restoreSnapshot(snapshot);

    assertEquals("Total regions count should be 4 after restore.", 4, nstate.getRegionCount());

    ADMIN.enableTable(tableName1);
    ADMIN.deleteSnapshot(snapshot);
  }

  @Test
  public void testRestoreSnapshotQuotaExceed() throws Exception {
    String nsp = prefix + "_testRestoreSnapshotQuotaExceed";
    NamespaceDescriptor nspDesc =
        NamespaceDescriptor.create(nsp)
            .addConfiguration(TableNamespaceManager.KEY_MAX_REGIONS, "10").build();
    ADMIN.createNamespace(nspDesc);
    NamespaceDescriptor ndesc = ADMIN.getNamespaceDescriptor(nsp);
    assertNotNull("Namespace descriptor found null.", ndesc);
    TableName tableName1 = TableName.valueOf(nsp + TableName.NAMESPACE_DELIM + "table1");
    HTableDescriptor tableDescOne = new HTableDescriptor(tableName1);
    HColumnDescriptor fam1 = new HColumnDescriptor("fam1");
    tableDescOne.addFamily(fam1);

    ADMIN.createTable(tableDescOne, Bytes.toBytes("AAA"), Bytes.toBytes("ZZZ"), 4);

    NamespaceTableAndRegionInfo nstate = getNamespaceState(nsp);
    assertEquals("Intial region count should be 4.", 4, nstate.getRegionCount());

    String snapshot = "snapshot_testRestoreSnapshotQuotaExceed";
    // snapshot has 4 regions
    ADMIN.snapshot(snapshot, tableName1);
    // recreate table with 1 region and set max regions to 3 for namespace
    ADMIN.disableTable(tableName1);
    ADMIN.deleteTable(tableName1);
    ADMIN.createTable(tableDescOne);
    ndesc.setConfiguration(TableNamespaceManager.KEY_MAX_REGIONS, "3");
    ADMIN.modifyNamespace(ndesc);

    ADMIN.disableTable(tableName1);
    try {
      ADMIN.restoreSnapshot(snapshot);
      fail("Region quota is exceeded so QuotaExceededException should be thrown but HBaseAdmin"
          + " wraps IOException into RestoreSnapshotException");
    } catch (RestoreSnapshotException ignore) {
      assertTrue(ignore.getCause() instanceof QuotaExceededException);
    }
    assertEquals(1, getNamespaceState(nsp).getRegionCount());
    ADMIN.enableTable(tableName1);
    ADMIN.deleteSnapshot(snapshot);
  }
}
