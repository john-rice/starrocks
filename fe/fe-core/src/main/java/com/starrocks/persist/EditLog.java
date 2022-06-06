// This file is made available under Elastic License 2.0.
// This file is based on code available under the Apache license here:
//   https://github.com/apache/incubator-doris/blob/master/fe/fe-core/src/main/java/org/apache/doris/persist/EditLog.java

// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

package com.starrocks.persist;

import com.google.common.base.Preconditions;
import com.starrocks.alter.AlterJobV2;
import com.starrocks.alter.BatchAlterJobPersistInfo;
import com.starrocks.alter.DecommissionBackendJob;
import com.starrocks.alter.RollupJob;
import com.starrocks.alter.SchemaChangeJob;
import com.starrocks.analysis.UserIdentity;
import com.starrocks.backup.BackupJob;
import com.starrocks.backup.Repository;
import com.starrocks.backup.RestoreJob;
import com.starrocks.catalog.BrokerMgr;
import com.starrocks.catalog.Catalog;
import com.starrocks.catalog.Database;
import com.starrocks.catalog.Function;
import com.starrocks.catalog.FunctionSearchDesc;
import com.starrocks.catalog.MetaVersion;
import com.starrocks.catalog.Resource;
import com.starrocks.cluster.BaseParam;
import com.starrocks.cluster.Cluster;
import com.starrocks.common.Config;
import com.starrocks.common.FeConstants;
import com.starrocks.common.io.Text;
import com.starrocks.common.io.Writable;
import com.starrocks.common.util.SmallFileMgr.SmallFile;
import com.starrocks.ha.MasterInfo;
import com.starrocks.journal.Journal;
import com.starrocks.journal.JournalCursor;
import com.starrocks.journal.JournalEntity;
import com.starrocks.journal.JournalFactory;
import com.starrocks.journal.bdbje.Timestamp;
import com.starrocks.load.DeleteHandler;
import com.starrocks.load.DeleteInfo;
import com.starrocks.load.ExportJob;
import com.starrocks.load.ExportMgr;
import com.starrocks.load.LoadErrorHub;
import com.starrocks.load.MultiDeleteInfo;
import com.starrocks.load.loadv2.LoadJob.LoadJobStateUpdateInfo;
import com.starrocks.load.loadv2.LoadJobFinalOperation;
import com.starrocks.load.routineload.RoutineLoadJob;
import com.starrocks.meta.MetaContext;
import com.starrocks.metric.MetricRepo;
import com.starrocks.mysql.privilege.UserPropertyInfo;
import com.starrocks.plugin.PluginInfo;
import com.starrocks.qe.SessionVariable;
import com.starrocks.scheduler.Task;
import com.starrocks.scheduler.persist.DropTaskRunsLog;
import com.starrocks.scheduler.persist.DropTasksLog;
import com.starrocks.scheduler.persist.TaskRunStatus;
import com.starrocks.scheduler.persist.TaskRunStatusChange;
import com.starrocks.server.GlobalStateMgr;
import com.starrocks.statistic.AnalyzeJob;
import com.starrocks.system.Backend;
import com.starrocks.system.Frontend;
import com.starrocks.transaction.TransactionState;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.util.List;

/**
 * EditLog maintains a log of the memory modifications.
 * Current we support only file editLog.
 */
public class EditLog {
    public static final Logger LOG = LogManager.getLogger(EditLog.class);

    private final EditLogOutputStream editStream = null;

    private long txId = 0;

    private long numTransactions;
    private long totalTimeTransactions;

    private final Journal journal;

    public EditLog(String nodeName) {
        journal = JournalFactory.create(nodeName);
    }

    public long getMaxJournalId() {
        return journal.getMaxJournalId();
    }

    public JournalCursor read(long fromId, long toId) {
        return journal.read(fromId, toId);
    }

    public long getFinalizedJournalId() {
        return journal.getFinalizedJournalId();
    }

    public void deleteJournals(long deleteToJournalId) {
        journal.deleteJournals(deleteToJournalId);
    }

    public List<Long> getDatabaseNames() {
        return journal.getDatabaseNames();
    }

    public synchronized int getNumEditStreams() {
        return journal == null ? 0 : 1;
    }

    public Journal getJournal() {
        return journal;
    }

    public static void loadJournal(GlobalStateMgr globalStateMgr, JournalEntity journal) {
        short opCode = journal.getOpCode();
        if (opCode != OperationType.OP_SAVE_NEXTID && opCode != OperationType.OP_TIMESTAMP) {
            LOG.debug("replay journal op code: {}", opCode);
        }
        try {
            switch (opCode) {
                case OperationType.OP_SAVE_NEXTID: {
                    String idString = journal.getData().toString();
                    long id = Long.parseLong(idString);
                    globalStateMgr.setNextId(id + 1);
                    break;
                }
                case OperationType.OP_SAVE_TRANSACTION_ID: {
                    String idString = journal.getData().toString();
                    long id = Long.parseLong(idString);
                    GlobalStateMgr.getCurrentGlobalTransactionMgr().getTransactionIDGenerator()
                            .initTransactionId(id + 1);
                    break;
                }
                case OperationType.OP_CREATE_DB: {
                    Database db = (Database) journal.getData();
                    globalStateMgr.replayCreateDb(db);
                    break;
                }
                case OperationType.OP_DROP_DB: {
                    DropDbInfo dropDbInfo = (DropDbInfo) journal.getData();
                    globalStateMgr.replayDropDb(dropDbInfo.getDbName(), dropDbInfo.isForceDrop());
                    break;
                }
                case OperationType.OP_ALTER_DB: {
                    DatabaseInfo dbInfo = (DatabaseInfo) journal.getData();
                    String dbName = dbInfo.getDbName();
                    LOG.info("Begin to unprotect alter db info {}", dbName);
                    globalStateMgr.replayAlterDatabaseQuota(dbName, dbInfo.getQuota(), dbInfo.getQuotaType());
                    break;
                }
                case OperationType.OP_ERASE_DB: {
                    Text dbId = (Text) journal.getData();
                    globalStateMgr.replayEraseDatabase(Long.parseLong(dbId.toString()));
                    break;
                }
                case OperationType.OP_RECOVER_DB: {
                    RecoverInfo info = (RecoverInfo) journal.getData();
                    globalStateMgr.replayRecoverDatabase(info);
                    break;
                }
                case OperationType.OP_RENAME_DB: {
                    DatabaseInfo dbInfo = (DatabaseInfo) journal.getData();
                    String dbName = dbInfo.getDbName();
                    LOG.info("Begin to unprotect rename db {}", dbName);
                    globalStateMgr.replayRenameDatabase(dbName, dbInfo.getNewDbName());
                    break;
                }
                case OperationType.OP_CREATE_TABLE: {
                    CreateTableInfo info = (CreateTableInfo) journal.getData();
                    LOG.info("Begin to unprotect create table. db = "
                            + info.getDbName() + " table = " + info.getTable().getId());
                    globalStateMgr.replayCreateTable(info.getDbName(), info.getTable());
                    break;
                }
                case OperationType.OP_DROP_TABLE: {
                    DropInfo info = (DropInfo) journal.getData();
                    Database db = globalStateMgr.getDb(info.getDbId());
                    if (db == null) {
                        LOG.warn("failed to get db[{}]", info.getDbId());
                        break;
                    }
                    LOG.info("Begin to unprotect drop table. db = "
                            + db.getFullName() + " table = " + info.getTableId());
                    globalStateMgr.replayDropTable(db, info.getTableId(), info.isForceDrop());
                    break;
                }
                case OperationType.OP_ADD_PARTITION: {
                    PartitionPersistInfo info = (PartitionPersistInfo) journal.getData();
                    LOG.info("Begin to unprotect add partition. db = " + info.getDbId()
                            + " table = " + info.getTableId()
                            + " partitionName = " + info.getPartition().getName());
                    globalStateMgr.replayAddPartition(info);
                    break;
                }
                case OperationType.OP_ADD_PARTITIONS: {
                    AddPartitionsInfo infos = (AddPartitionsInfo) journal.getData();
                    for (PartitionPersistInfo info : infos.getAddPartitionInfos()) {
                        globalStateMgr.replayAddPartition(info);
                    }
                    break;
                }
                case OperationType.OP_DROP_PARTITION: {
                    DropPartitionInfo info = (DropPartitionInfo) journal.getData();
                    LOG.info("Begin to unprotect drop partition. db = " + info.getDbId()
                            + " table = " + info.getTableId()
                            + " partitionName = " + info.getPartitionName());
                    globalStateMgr.replayDropPartition(info);
                    break;
                }
                case OperationType.OP_MODIFY_PARTITION: {
                    ModifyPartitionInfo info = (ModifyPartitionInfo) journal.getData();
                    LOG.info("Begin to unprotect modify partition. db = " + info.getDbId()
                            + " table = " + info.getTableId() + " partitionId = " + info.getPartitionId());
                    globalStateMgr.getAlterInstance().replayModifyPartition(info);
                    break;
                }
                case OperationType.OP_BATCH_MODIFY_PARTITION: {
                    BatchModifyPartitionsInfo info = (BatchModifyPartitionsInfo) journal.getData();
                    for (ModifyPartitionInfo modifyPartitionInfo : info.getModifyPartitionInfos()) {
                        globalStateMgr.getAlterInstance().replayModifyPartition(modifyPartitionInfo);
                    }
                    break;
                }
                case OperationType.OP_ERASE_TABLE: {
                    Text tableId = (Text) journal.getData();
                    globalStateMgr.replayEraseTable(Long.parseLong(tableId.toString()));
                    break;
                }
                case OperationType.OP_ERASE_MULTI_TABLES: {
                    MultiEraseTableInfo multiEraseTableInfo = (MultiEraseTableInfo) journal.getData();
                    globalStateMgr.replayEraseMultiTables(multiEraseTableInfo);
                    break;
                }
                case OperationType.OP_ERASE_PARTITION: {
                    Text partitionId = (Text) journal.getData();
                    globalStateMgr.replayErasePartition(Long.parseLong(partitionId.toString()));
                    break;
                }
                case OperationType.OP_RECOVER_TABLE: {
                    RecoverInfo info = (RecoverInfo) journal.getData();
                    globalStateMgr.replayRecoverTable(info);
                    break;
                }
                case OperationType.OP_RECOVER_PARTITION: {
                    RecoverInfo info = (RecoverInfo) journal.getData();
                    globalStateMgr.replayRecoverPartition(info);
                    break;
                }
                case OperationType.OP_RENAME_TABLE: {
                    TableInfo info = (TableInfo) journal.getData();
                    globalStateMgr.replayRenameTable(info);
                    break;
                }
                case OperationType.OP_MODIFY_VIEW_DEF: {
                    AlterViewInfo info = (AlterViewInfo) journal.getData();
                    globalStateMgr.getAlterInstance().replayModifyViewDef(info);
                    break;
                }
                case OperationType.OP_RENAME_PARTITION: {
                    TableInfo info = (TableInfo) journal.getData();
                    globalStateMgr.replayRenamePartition(info);
                    break;
                }
                case OperationType.OP_BACKUP_JOB: {
                    BackupJob job = (BackupJob) journal.getData();
                    globalStateMgr.getBackupHandler().replayAddJob(job);
                    break;
                }
                case OperationType.OP_RESTORE_JOB: {
                    RestoreJob job = (RestoreJob) journal.getData();
                    job.setGlobalStateMgr(globalStateMgr);
                    globalStateMgr.getBackupHandler().replayAddJob(job);
                    break;
                }
                case OperationType.OP_START_ROLLUP: {
                    RollupJob job = (RollupJob) journal.getData();
                    globalStateMgr.getRollupHandler().replayInitJob(job, globalStateMgr);
                    break;
                }
                case OperationType.OP_FINISHING_ROLLUP: {
                    RollupJob job = (RollupJob) journal.getData();
                    globalStateMgr.getRollupHandler().replayFinishing(job, globalStateMgr);
                    break;
                }
                case OperationType.OP_FINISH_ROLLUP: {
                    RollupJob job = (RollupJob) journal.getData();
                    globalStateMgr.getRollupHandler().replayFinish(job, globalStateMgr);
                    break;
                }
                case OperationType.OP_CANCEL_ROLLUP: {
                    RollupJob job = (RollupJob) journal.getData();
                    globalStateMgr.getRollupHandler().replayCancel(job, globalStateMgr);
                    break;
                }
                case OperationType.OP_DROP_ROLLUP: {
                    DropInfo info = (DropInfo) journal.getData();
                    globalStateMgr.getRollupHandler().replayDropRollup(info, globalStateMgr);
                    break;
                }
                case OperationType.OP_BATCH_DROP_ROLLUP: {
                    BatchDropInfo batchDropInfo = (BatchDropInfo) journal.getData();
                    for (long indexId : batchDropInfo.getIndexIdSet()) {
                        globalStateMgr.getRollupHandler().replayDropRollup(
                                new DropInfo(batchDropInfo.getDbId(), batchDropInfo.getTableId(), indexId, false),
                                globalStateMgr);
                    }
                    break;
                }
                case OperationType.OP_START_SCHEMA_CHANGE: {
                    SchemaChangeJob job = (SchemaChangeJob) journal.getData();
                    LOG.info("Begin to unprotect create schema change job. db = " + job.getDbId()
                            + " table = " + job.getTableId());
                    globalStateMgr.getSchemaChangeHandler().replayInitJob(job, globalStateMgr);
                    break;
                }
                case OperationType.OP_FINISHING_SCHEMA_CHANGE: {
                    SchemaChangeJob job = (SchemaChangeJob) journal.getData();
                    LOG.info("Begin to unprotect replay finishing schema change job. db = " + job.getDbId()
                            + " table = " + job.getTableId());
                    globalStateMgr.getSchemaChangeHandler().replayFinishing(job, globalStateMgr);
                    break;
                }
                case OperationType.OP_FINISH_SCHEMA_CHANGE: {
                    SchemaChangeJob job = (SchemaChangeJob) journal.getData();
                    globalStateMgr.getSchemaChangeHandler().replayFinish(job, globalStateMgr);
                    break;
                }
                case OperationType.OP_CANCEL_SCHEMA_CHANGE: {
                    SchemaChangeJob job = (SchemaChangeJob) journal.getData();
                    LOG.debug("Begin to unprotect cancel schema change. db = " + job.getDbId()
                            + " table = " + job.getTableId());
                    globalStateMgr.getSchemaChangeHandler().replayCancel(job, globalStateMgr);
                    break;
                }
                case OperationType.OP_FINISH_CONSISTENCY_CHECK: {
                    ConsistencyCheckInfo info = (ConsistencyCheckInfo) journal.getData();
                    globalStateMgr.getConsistencyChecker().replayFinishConsistencyCheck(info, globalStateMgr);
                    break;
                }
                case OperationType.OP_CLEAR_ROLLUP_INFO: {
                    ReplicaPersistInfo info = (ReplicaPersistInfo) journal.getData();
                    globalStateMgr.getLoadInstance().replayClearRollupInfo(info, globalStateMgr);
                    break;
                }
                case OperationType.OP_RENAME_ROLLUP: {
                    TableInfo info = (TableInfo) journal.getData();
                    globalStateMgr.replayRenameRollup(info);
                    break;
                }
                case OperationType.OP_EXPORT_CREATE: {
                    ExportJob job = (ExportJob) journal.getData();
                    ExportMgr exportMgr = globalStateMgr.getExportMgr();
                    exportMgr.replayCreateExportJob(job);
                    break;
                }
                case OperationType.OP_EXPORT_UPDATE_STATE:
                    ExportJob.StateTransfer op = (ExportJob.StateTransfer) journal.getData();
                    ExportMgr exportMgr = globalStateMgr.getExportMgr();
                    exportMgr.replayUpdateJobState(op.getJobId(), op.getState());
                    break;
                case OperationType.OP_FINISH_DELETE: {
                    DeleteInfo info = (DeleteInfo) journal.getData();
                    DeleteHandler deleteHandler = globalStateMgr.getDeleteHandler();
                    deleteHandler.replayDelete(info, globalStateMgr);
                    break;
                }
                case OperationType.OP_FINISH_MULTI_DELETE: {
                    MultiDeleteInfo info = (MultiDeleteInfo) journal.getData();
                    DeleteHandler deleteHandler = globalStateMgr.getDeleteHandler();
                    deleteHandler.replayMultiDelete(info, globalStateMgr);
                    break;
                }
                case OperationType.OP_ADD_REPLICA: {
                    ReplicaPersistInfo info = (ReplicaPersistInfo) journal.getData();
                    globalStateMgr.replayAddReplica(info);
                    break;
                }
                case OperationType.OP_UPDATE_REPLICA: {
                    ReplicaPersistInfo info = (ReplicaPersistInfo) journal.getData();
                    globalStateMgr.replayUpdateReplica(info);
                    break;
                }
                case OperationType.OP_DELETE_REPLICA: {
                    ReplicaPersistInfo info = (ReplicaPersistInfo) journal.getData();
                    globalStateMgr.replayDeleteReplica(info);
                    break;
                }
                case OperationType.OP_ADD_BACKEND: {
                    Backend be = (Backend) journal.getData();
                    GlobalStateMgr.getCurrentSystemInfo().replayAddBackend(be);
                    break;
                }
                case OperationType.OP_DROP_BACKEND: {
                    Backend be = (Backend) journal.getData();
                    GlobalStateMgr.getCurrentSystemInfo().replayDropBackend(be);
                    break;
                }
                case OperationType.OP_BACKEND_STATE_CHANGE: {
                    Backend be = (Backend) journal.getData();
                    GlobalStateMgr.getCurrentSystemInfo().updateBackendState(be);
                    break;
                }
                case OperationType.OP_START_DECOMMISSION_BACKEND: {
                    DecommissionBackendJob job = (DecommissionBackendJob) journal.getData();
                    LOG.debug("{}: {}", opCode, job.getTableId());
                    globalStateMgr.getClusterHandler().replayInitJob(job, globalStateMgr);
                    break;
                }
                case OperationType.OP_FINISH_DECOMMISSION_BACKEND: {
                    DecommissionBackendJob job = (DecommissionBackendJob) journal.getData();
                    LOG.debug("{}: {}", opCode, job.getTableId());
                    globalStateMgr.getClusterHandler().replayFinish(job, globalStateMgr);
                    break;
                }
                case OperationType.OP_ADD_FIRST_FRONTEND:
                case OperationType.OP_ADD_FRONTEND: {
                    Frontend fe = (Frontend) journal.getData();
                    globalStateMgr.replayAddFrontend(fe);
                    break;
                }
                case OperationType.OP_REMOVE_FRONTEND: {
                    Frontend fe = (Frontend) journal.getData();
                    globalStateMgr.replayDropFrontend(fe);
                    if (fe.getNodeName().equals(GlobalStateMgr.getCurrentState().getNodeName())) {
                        System.out.println("current fe " + fe + " is removed. will exit");
                        LOG.info("current fe " + fe + " is removed. will exit");
                        System.exit(-1);
                    }
                    break;
                }
                case OperationType.OP_UPDATE_FRONTEND: {
                    Frontend fe = (Frontend) journal.getData();
                    globalStateMgr.replayUpdateFrontend(fe);
                    break;
                }
                case OperationType.OP_CREATE_USER: {
                    PrivInfo privInfo = (PrivInfo) journal.getData();
                    globalStateMgr.getAuth().replayCreateUser(privInfo);
                    break;
                }
                case OperationType.OP_NEW_DROP_USER: {
                    UserIdentity userIdent = (UserIdentity) journal.getData();
                    globalStateMgr.getAuth().replayDropUser(userIdent);
                    break;
                }
                case OperationType.OP_GRANT_PRIV: {
                    PrivInfo privInfo = (PrivInfo) journal.getData();
                    globalStateMgr.getAuth().replayGrant(privInfo);
                    break;
                }
                case OperationType.OP_REVOKE_PRIV: {
                    PrivInfo privInfo = (PrivInfo) journal.getData();
                    globalStateMgr.getAuth().replayRevoke(privInfo);
                    break;
                }
                case OperationType.OP_SET_PASSWORD: {
                    PrivInfo privInfo = (PrivInfo) journal.getData();
                    globalStateMgr.getAuth().replaySetPassword(privInfo);
                    break;
                }
                case OperationType.OP_CREATE_ROLE: {
                    PrivInfo privInfo = (PrivInfo) journal.getData();
                    globalStateMgr.getAuth().replayCreateRole(privInfo);
                    break;
                }
                case OperationType.OP_DROP_ROLE: {
                    PrivInfo privInfo = (PrivInfo) journal.getData();
                    globalStateMgr.getAuth().replayDropRole(privInfo);
                    break;
                }
                case OperationType.OP_UPDATE_USER_PROPERTY: {
                    UserPropertyInfo propertyInfo = (UserPropertyInfo) journal.getData();
                    globalStateMgr.getAuth().replayUpdateUserProperty(propertyInfo);
                    break;
                }
                case OperationType.OP_TIMESTAMP: {
                    Timestamp stamp = (Timestamp) journal.getData();
                    globalStateMgr.setSynchronizedTime(stamp.getTimestamp());
                    break;
                }
                case OperationType.OP_MASTER_INFO_CHANGE: {
                    MasterInfo info = (MasterInfo) journal.getData();
                    globalStateMgr.setMaster(info);
                    break;
                }
                //compatible with old community meta, newly added log using OP_META_VERSION_V2
                case OperationType.OP_META_VERSION: {
                    String versionString = ((Text) journal.getData()).toString();
                    int version = Integer.parseInt(versionString);
                    if (version > FeConstants.meta_version) {
                        LOG.error("invalid meta data version found, cat not bigger than FeConstants.meta_version."
                                        + "please update FeConstants.meta_version bigger or equal to {} and restart.",
                                version);
                        System.exit(-1);
                    }
                    MetaContext.get().setMetaVersion(version);
                    break;
                }
                case OperationType.OP_META_VERSION_V2: {
                    MetaVersion metaVersion = (MetaVersion) journal.getData();
                    if (metaVersion.getCommunityVersion() > FeConstants.meta_version) {
                        LOG.error("invalid meta data version found, cat not bigger than FeConstants.meta_version."
                                        + "please update FeConstants.meta_version bigger or equal to {} and restart.",
                                metaVersion.getCommunityVersion());
                        System.exit(-1);
                    }
                    if (metaVersion.getStarRocksVersion() > FeConstants.starrocks_meta_version) {
                        LOG.error(
                                "invalid meta data version found, cat not bigger than FeConstants.starrocks_meta_version."
                                        +
                                        "please update FeConstants.starrocks_meta_version bigger or equal to {} and restart.",
                                metaVersion.getStarRocksVersion());
                        System.exit(-1);
                    }
                    MetaContext.get().setMetaVersion(metaVersion.getCommunityVersion());
                    MetaContext.get().setStarRocksMetaVersion(metaVersion.getStarRocksVersion());
                    break;
                }
                case OperationType.OP_GLOBAL_VARIABLE: {
                    SessionVariable variable = (SessionVariable) journal.getData();
                    globalStateMgr.replayGlobalVariable(variable);
                    break;
                }
                case OperationType.OP_CREATE_CLUSTER: {
                    final Cluster value = (Cluster) journal.getData();
                    globalStateMgr.replayCreateCluster(value);
                    break;
                }
                case OperationType.OP_DROP_CLUSTER:
                case OperationType.OP_EXPAND_CLUSTER: {
                    break;
                }
                // for compatibility
                case OperationType.OP_LINK_CLUSTER:
                case OperationType.OP_MIGRATE_CLUSTER:
                    break;
                case OperationType.OP_UPDATE_DB: {
                    final DatabaseInfo param = (DatabaseInfo) journal.getData();
                    globalStateMgr.replayUpdateDb(param);
                    break;
                }
                case OperationType.OP_DROP_LINKDB: {
                    final DropLinkDbAndUpdateDbInfo param = (DropLinkDbAndUpdateDbInfo) journal.getData();
                    globalStateMgr.replayDropLinkDb(param);
                    break;
                }
                case OperationType.OP_ADD_BROKER: {
                    final BrokerMgr.ModifyBrokerInfo param = (BrokerMgr.ModifyBrokerInfo) journal.getData();
                    globalStateMgr.getBrokerMgr().replayAddBrokers(param.brokerName, param.brokerAddresses);
                    break;
                }
                case OperationType.OP_DROP_BROKER: {
                    final BrokerMgr.ModifyBrokerInfo param = (BrokerMgr.ModifyBrokerInfo) journal.getData();
                    globalStateMgr.getBrokerMgr().replayDropBrokers(param.brokerName, param.brokerAddresses);
                    break;
                }
                case OperationType.OP_DROP_ALL_BROKER: {
                    final String param = journal.getData().toString();
                    globalStateMgr.getBrokerMgr().replayDropAllBroker(param);
                    break;
                }
                case OperationType.OP_SET_LOAD_ERROR_HUB: {
                    final LoadErrorHub.Param param = (LoadErrorHub.Param) journal.getData();
                    globalStateMgr.getLoadInstance().setLoadErrorHubInfo(param);
                    break;
                }
                case OperationType.OP_UPDATE_CLUSTER_AND_BACKENDS: {
                    final BackendIdsUpdateInfo info = (BackendIdsUpdateInfo) journal.getData();
                    globalStateMgr.replayUpdateClusterAndBackends(info);
                    break;
                }
                case OperationType.OP_UPSERT_TRANSACTION_STATE: {
                    final TransactionState state = (TransactionState) journal.getData();
                    GlobalStateMgr.getCurrentGlobalTransactionMgr().replayUpsertTransactionState(state);
                    LOG.debug("opcode: {}, tid: {}", opCode, state.getTransactionId());
                    break;
                }
                case OperationType.OP_DELETE_TRANSACTION_STATE: {
                    final TransactionState state = (TransactionState) journal.getData();
                    GlobalStateMgr.getCurrentGlobalTransactionMgr().replayDeleteTransactionState(state);
                    LOG.debug("opcode: {}, tid: {}", opCode, state.getTransactionId());
                    break;
                }
                case OperationType.OP_CREATE_REPOSITORY: {
                    Repository repository = (Repository) journal.getData();
                    globalStateMgr.getBackupHandler().getRepoMgr().addAndInitRepoIfNotExist(repository, true);
                    break;
                }
                case OperationType.OP_DROP_REPOSITORY: {
                    String repoName = ((Text) journal.getData()).toString();
                    globalStateMgr.getBackupHandler().getRepoMgr().removeRepo(repoName, true);
                    break;
                }
                case OperationType.OP_TRUNCATE_TABLE: {
                    TruncateTableInfo info = (TruncateTableInfo) journal.getData();
                    globalStateMgr.replayTruncateTable(info);
                    break;
                }
                case OperationType.OP_COLOCATE_ADD_TABLE: {
                    final ColocatePersistInfo info = (ColocatePersistInfo) journal.getData();
                    globalStateMgr.getColocateTableIndex().replayAddTableToGroup(info);
                    break;
                }
                case OperationType.OP_COLOCATE_REMOVE_TABLE: {
                    final ColocatePersistInfo info = (ColocatePersistInfo) journal.getData();
                    globalStateMgr.getColocateTableIndex().replayRemoveTable(info);
                    break;
                }
                case OperationType.OP_COLOCATE_BACKENDS_PER_BUCKETSEQ: {
                    final ColocatePersistInfo info = (ColocatePersistInfo) journal.getData();
                    globalStateMgr.getColocateTableIndex().replayAddBackendsPerBucketSeq(info);
                    break;
                }
                case OperationType.OP_COLOCATE_MARK_UNSTABLE: {
                    final ColocatePersistInfo info = (ColocatePersistInfo) journal.getData();
                    globalStateMgr.getColocateTableIndex().replayMarkGroupUnstable(info);
                    break;
                }
                case OperationType.OP_COLOCATE_MARK_STABLE: {
                    final ColocatePersistInfo info = (ColocatePersistInfo) journal.getData();
                    globalStateMgr.getColocateTableIndex().replayMarkGroupStable(info);
                    break;
                }
                case OperationType.OP_MODIFY_TABLE_COLOCATE: {
                    final TablePropertyInfo info = (TablePropertyInfo) journal.getData();
                    globalStateMgr.replayModifyTableColocate(info);
                    break;
                }
                case OperationType.OP_HEARTBEAT_V2:
                case OperationType.OP_HEARTBEAT: {
                    final HbPackage hbPackage = (HbPackage) journal.getData();
                    GlobalStateMgr.getCurrentHeartbeatMgr().replayHearbeat(hbPackage);
                    break;
                }
                case OperationType.OP_ADD_FUNCTION: {
                    final Function function = (Function) journal.getData();
                    GlobalStateMgr.getCurrentState().replayCreateFunction(function);
                    break;
                }
                case OperationType.OP_DROP_FUNCTION: {
                    FunctionSearchDesc function = (FunctionSearchDesc) journal.getData();
                    GlobalStateMgr.getCurrentState().replayDropFunction(function);
                    break;
                }
                case OperationType.OP_BACKEND_TABLETS_INFO: {
                    BackendTabletsInfo backendTabletsInfo = (BackendTabletsInfo) journal.getData();
                    GlobalStateMgr.getCurrentState().replayBackendTabletsInfo(backendTabletsInfo);
                    break;
                }
                case OperationType.OP_CREATE_ROUTINE_LOAD_JOB: {
                    RoutineLoadJob routineLoadJob = (RoutineLoadJob) journal.getData();
                    GlobalStateMgr.getCurrentState().getRoutineLoadManager().replayCreateRoutineLoadJob(routineLoadJob);
                    break;
                }
                case OperationType.OP_CHANGE_ROUTINE_LOAD_JOB: {
                    RoutineLoadOperation operation = (RoutineLoadOperation) journal.getData();
                    GlobalStateMgr.getCurrentState().getRoutineLoadManager().replayChangeRoutineLoadJob(operation);
                    break;
                }
                case OperationType.OP_REMOVE_ROUTINE_LOAD_JOB: {
                    RoutineLoadOperation operation = (RoutineLoadOperation) journal.getData();
                    globalStateMgr.getRoutineLoadManager().replayRemoveOldRoutineLoad(operation);
                    break;
                }
                case OperationType.OP_CREATE_LOAD_JOB: {
                    com.starrocks.load.loadv2.LoadJob loadJob =
                            (com.starrocks.load.loadv2.LoadJob) journal.getData();
                    globalStateMgr.getLoadManager().replayCreateLoadJob(loadJob);
                    break;
                }
                case OperationType.OP_END_LOAD_JOB: {
                    LoadJobFinalOperation operation = (LoadJobFinalOperation) journal.getData();
                    globalStateMgr.getLoadManager().replayEndLoadJob(operation);
                    break;
                }
                case OperationType.OP_UPDATE_LOAD_JOB: {
                    LoadJobStateUpdateInfo info = (LoadJobStateUpdateInfo) journal.getData();
                    globalStateMgr.getLoadManager().replayUpdateLoadJobStateInfo(info);
                    break;
                }
                case OperationType.OP_CREATE_RESOURCE: {
                    final Resource resource = (Resource) journal.getData();
                    globalStateMgr.getResourceMgr().replayCreateResource(resource);
                    break;
                }
                case OperationType.OP_DROP_RESOURCE: {
                    final DropResourceOperationLog operationLog = (DropResourceOperationLog) journal.getData();
                    globalStateMgr.getResourceMgr().replayDropResource(operationLog);
                    break;
                }
                case OperationType.OP_WORKGROUP: {
                    final WorkGroupOpEntry entry = (WorkGroupOpEntry) journal.getData();
                    globalStateMgr.getWorkGroupMgr().replayWorkGroupOp(entry);
                    break;
                }
                case OperationType.OP_CREATE_TASK: {
                    final Task task = (Task) journal.getData();
                    globalStateMgr.getTaskManager().replayCreateTask(task);
                    break;
                }
                case OperationType.OP_DROP_TASKS: {
                    DropTasksLog dropTasksLog = (DropTasksLog) journal.getData();
                    globalStateMgr.getTaskManager().replayDropTasks(dropTasksLog.getTaskIdList());
                    break;
                }
                case OperationType.OP_CREATE_TASK_RUN: {
                    final TaskRunStatus status = (TaskRunStatus) journal.getData();
                    globalStateMgr.getTaskManager().replayCreateTaskRun(status);
                    break;
                }
                case OperationType.OP_UPDATE_TASK_RUN: {
                    final TaskRunStatusChange statusChange =
                            (TaskRunStatusChange) journal.getData();
                    globalStateMgr.getTaskManager().replayUpdateTaskRun(statusChange);
                    break;
                }
                case OperationType.OP_DROP_TASK_RUNS: {
                    DropTaskRunsLog dropTaskRunsLog = (DropTaskRunsLog) journal.getData();
                    globalStateMgr.getTaskManager().replayDropTaskRuns(dropTaskRunsLog.getQueryIdList());
                    break;
                }
                case OperationType.OP_CREATE_SMALL_FILE: {
                    SmallFile smallFile = (SmallFile) journal.getData();
                    globalStateMgr.getSmallFileMgr().replayCreateFile(smallFile);
                    break;
                }
                case OperationType.OP_DROP_SMALL_FILE: {
                    SmallFile smallFile = (SmallFile) journal.getData();
                    globalStateMgr.getSmallFileMgr().replayRemoveFile(smallFile);
                    break;
                }
                case OperationType.OP_ALTER_JOB_V2: {
                    AlterJobV2 alterJob = (AlterJobV2) journal.getData();
                    switch (alterJob.getType()) {
                        case ROLLUP:
                            globalStateMgr.getRollupHandler().replayAlterJobV2(alterJob);
                            break;
                        case SCHEMA_CHANGE:
                            globalStateMgr.getSchemaChangeHandler().replayAlterJobV2(alterJob);
                            break;
                        default:
                            break;
                    }
                    break;
                }
                case OperationType.OP_BATCH_ADD_ROLLUP: {
                    BatchAlterJobPersistInfo batchAlterJobV2 = (BatchAlterJobPersistInfo) journal.getData();
                    for (AlterJobV2 alterJobV2 : batchAlterJobV2.getAlterJobV2List()) {
                        globalStateMgr.getRollupHandler().replayAlterJobV2(alterJobV2);
                    }
                    break;
                }
                case OperationType.OP_MODIFY_DISTRIBUTION_TYPE: {
                    TableInfo tableInfo = (TableInfo) journal.getData();
                    globalStateMgr.replayConvertDistributionType(tableInfo);
                    break;
                }
                case OperationType.OP_DYNAMIC_PARTITION:
                case OperationType.OP_MODIFY_IN_MEMORY:
                case OperationType.OP_SET_FORBIT_GLOBAL_DICT:
                case OperationType.OP_MODIFY_REPLICATION_NUM:
                case OperationType.OP_MODIFY_ENABLE_PERSISTENT_INDEX: {
                    ModifyTablePropertyOperationLog modifyTablePropertyOperationLog =
                            (ModifyTablePropertyOperationLog) journal.getData();
                    globalStateMgr.replayModifyTableProperty(opCode, modifyTablePropertyOperationLog);
                    break;
                }
                case OperationType.OP_REPLACE_TEMP_PARTITION: {
                    ReplacePartitionOperationLog replaceTempPartitionLog =
                            (ReplacePartitionOperationLog) journal.getData();
                    globalStateMgr.replayReplaceTempPartition(replaceTempPartitionLog);
                    break;
                }
                case OperationType.OP_INSTALL_PLUGIN: {
                    PluginInfo pluginInfo = (PluginInfo) journal.getData();
                    globalStateMgr.replayInstallPlugin(pluginInfo);
                    break;
                }
                case OperationType.OP_UNINSTALL_PLUGIN: {
                    PluginInfo pluginInfo = (PluginInfo) journal.getData();
                    globalStateMgr.replayUninstallPlugin(pluginInfo);
                    break;
                }
                case OperationType.OP_SET_REPLICA_STATUS: {
                    SetReplicaStatusOperationLog log = (SetReplicaStatusOperationLog) journal.getData();
                    globalStateMgr.replaySetReplicaStatus(log);
                    break;
                }
                case OperationType.OP_REMOVE_ALTER_JOB_V2: {
                    RemoveAlterJobV2OperationLog log = (RemoveAlterJobV2OperationLog) journal.getData();
                    switch (log.getType()) {
                        case ROLLUP:
                            globalStateMgr.getRollupHandler().replayRemoveAlterJobV2(log);
                            break;
                        case SCHEMA_CHANGE:
                            globalStateMgr.getSchemaChangeHandler().replayRemoveAlterJobV2(log);
                            break;
                        default:
                            break;
                    }
                    break;
                }
                case OperationType.OP_ALTER_ROUTINE_LOAD_JOB: {
                    AlterRoutineLoadJobOperationLog log = (AlterRoutineLoadJobOperationLog) journal.getData();
                    globalStateMgr.getRoutineLoadManager().replayAlterRoutineLoadJob(log);
                    break;
                }
                case OperationType.OP_GLOBAL_VARIABLE_V2: {
                    GlobalVarPersistInfo info = (GlobalVarPersistInfo) journal.getData();
                    globalStateMgr.replayGlobalVariableV2(info);
                    break;
                }
                case OperationType.OP_SWAP_TABLE: {
                    SwapTableOperationLog log = (SwapTableOperationLog) journal.getData();
                    globalStateMgr.getAlterInstance().replaySwapTable(log);
                    break;
                }
                case OperationType.OP_ADD_ANALYZER_JOB: {
                    AnalyzeJob analyzeJob = (AnalyzeJob) journal.getData();
                    globalStateMgr.getAnalyzeManager().replayAddAnalyzeJob(analyzeJob);
                    break;
                }
                case OperationType.OP_REMOVE_ANALYZER_JOB: {
                    AnalyzeJob analyzeJob = (AnalyzeJob) journal.getData();
                    globalStateMgr.getAnalyzeManager().replayRemoveAnalyzeJob(analyzeJob);
                    break;
                }
                case OperationType.OP_MODIFY_HIVE_TABLE_COLUMN: {
                    ModifyTableColumnOperationLog modifyTableColumnOperationLog =
                            (ModifyTableColumnOperationLog) journal.getData();
                    globalStateMgr.replayModifyHiveTableColumn(opCode, modifyTableColumnOperationLog);
                    break;
                }
                case OperationType.OP_CREATE_CATALOG: {
                    Catalog catalog = (Catalog) journal.getData();
                    globalStateMgr.getCatalogMgr().replayCreateCatalog(catalog);
                    break;
                }
                case OperationType.OP_DROP_CATALOG: {
                    DropCatalogLog dropCatalogLog =
                            (DropCatalogLog) journal.getData();
                    globalStateMgr.getCatalogMgr().replayDropCatalog(dropCatalogLog);
                    break;
                }
                case OperationType.OP_GRANT_IMPERSONATE: {
                    ImpersonatePrivInfo info = (ImpersonatePrivInfo) journal.getData();
                    globalStateMgr.getAuth().replayGrantImpersonate(info);
                    break;
                }
                case OperationType.OP_REVOKE_IMPERSONATE: {
                    ImpersonatePrivInfo info = (ImpersonatePrivInfo) journal.getData();
                    globalStateMgr.getAuth().replayRevokeImpersonate(info);
                    break;
                }
                default: {
                    if (Config.ignore_unknown_log_id) {
                        LOG.warn("UNKNOWN Operation Type {}", opCode);
                    } else {
                        throw new IOException("UNKNOWN Operation Type " + opCode);
                    }
                }
            }
        } catch (Exception e) {
            LOG.error("Operation Type {}", opCode, e);
            System.exit(-1);
        }
    }

    /**
     * Shutdown the file store.
     */
    public synchronized void close() throws IOException {
        journal.close();
    }

    public void open() {
        journal.open();
    }

    /**
     * Close current journal and start a new journal
     */
    public void rollEditLog() {
        journal.rollJournal();
    }

    /**
     * Write an operation to the edit log. Do not sync to persistent store yet.
     */
    private synchronized void logEdit(short op, Writable writable) {
        if (this.getNumEditStreams() == 0) {
            LOG.error("Fatal Error : no editLog stream", new Exception());
            throw new Error("Fatal Error : no editLog stream");
        }

        long start = System.currentTimeMillis();

        Preconditions.checkState(GlobalStateMgr.getCurrentState().isMaster(),
                "non-master fe can not write bdb log");

        try {
            journal.write(op, writable);
        } catch (Exception e) {
            LOG.error("Fatal Error : write stream Exception", e);
            System.exit(-1);
        }

        // get a new transactionId
        txId++;

        // update statistics
        long end = System.currentTimeMillis();
        numTransactions++;
        totalTimeTransactions += (end - start);
        if (MetricRepo.isInit) {
            MetricRepo.HISTO_EDIT_LOG_WRITE_LATENCY.update((end - start));
        }

        if (LOG.isDebugEnabled()) {
            LOG.debug("nextId = {}, numTransactions = {}, totalTimeTransactions = {}, op = {}",
                    txId, numTransactions, totalTimeTransactions, op);
        }

        if (txId >= Config.edit_log_roll_num) {
            LOG.info("txId {} is equal to or larger than edit_log_roll_num {}, will roll edit.",
                    txId, Config.edit_log_roll_num);
            rollEditLog();
            txId = 0;
        }

        if (MetricRepo.isInit) {
            MetricRepo.COUNTER_EDIT_LOG_WRITE.increase(1L);
        }
    }

    /**
     * Return the size of the current EditLog
     */
    synchronized long getEditLogSize() throws IOException {
        return editStream.length();
    }

    public synchronized long getTxId() {
        return txId;
    }

    public void logSaveNextId(long nextId) {
        logEdit(OperationType.OP_SAVE_NEXTID, new Text(Long.toString(nextId)));
    }

    public void logSaveTransactionId(long transactionId) {
        logEdit(OperationType.OP_SAVE_TRANSACTION_ID, new Text(Long.toString(transactionId)));
    }

    public void logCreateDb(Database db) {
        logEdit(OperationType.OP_CREATE_DB, db);
    }

    public void logDropDb(DropDbInfo dropDbInfo) {
        logEdit(OperationType.OP_DROP_DB, dropDbInfo);
    }

    public void logEraseDb(long dbId) {
        logEdit(OperationType.OP_ERASE_DB, new Text(Long.toString(dbId)));
    }

    public void logRecoverDb(RecoverInfo info) {
        logEdit(OperationType.OP_RECOVER_DB, info);
    }

    public void logAlterDb(DatabaseInfo dbInfo) {
        logEdit(OperationType.OP_ALTER_DB, dbInfo);
    }

    public void logCreateTable(CreateTableInfo info) {
        logEdit(OperationType.OP_CREATE_TABLE, info);
    }

    public void logWorkGroupOp(WorkGroupOpEntry op) {
        logEdit(OperationType.OP_WORKGROUP, op);
    }

    public void logCreateTask(Task info) {
        logEdit(OperationType.OP_CREATE_TASK, info);
    }

    public void logDropTasks(List<Long> taskIdList) {
        logEdit(OperationType.OP_DROP_TASKS, new DropTasksLog(taskIdList));
    }

    public void logTaskRunCreateStatus(TaskRunStatus status) {
        logEdit(OperationType.OP_CREATE_TASK_RUN, status);
    }

    public void logUpdateTaskRun(TaskRunStatusChange statusChange) {
        logEdit(OperationType.OP_UPDATE_TASK_RUN, statusChange);
    }

    public void logDropTaskRuns(List<String> queryIdList) {
        logEdit(OperationType.OP_DROP_TASK_RUNS, new DropTaskRunsLog(queryIdList));
    }

    public void logAddPartition(PartitionPersistInfo info) {
        logEdit(OperationType.OP_ADD_PARTITION, info);
    }

    public void logAddPartitions(AddPartitionsInfo info) {
        logEdit(OperationType.OP_ADD_PARTITIONS, info);
    }

    public void logDropPartition(DropPartitionInfo info) {
        logEdit(OperationType.OP_DROP_PARTITION, info);
    }

    public void logErasePartition(long partitionId) {
        logEdit(OperationType.OP_ERASE_PARTITION, new Text(Long.toString(partitionId)));
    }

    public void logRecoverPartition(RecoverInfo info) {
        logEdit(OperationType.OP_RECOVER_PARTITION, info);
    }

    public void logModifyPartition(ModifyPartitionInfo info) {
        logEdit(OperationType.OP_MODIFY_PARTITION, info);
    }

    public void logBatchModifyPartition(BatchModifyPartitionsInfo info) {
        logEdit(OperationType.OP_BATCH_MODIFY_PARTITION, info);
    }

    public void logDropTable(DropInfo info) {
        logEdit(OperationType.OP_DROP_TABLE, info);
    }

    public void logEraseMultiTables(List<Long> tableIds) {
        logEdit(OperationType.OP_ERASE_MULTI_TABLES, new MultiEraseTableInfo(tableIds));
    }

    public void logRecoverTable(RecoverInfo info) {
        logEdit(OperationType.OP_RECOVER_TABLE, info);
    }

    public void logStartRollup(RollupJob rollupJob) {
        logEdit(OperationType.OP_START_ROLLUP, rollupJob);
    }

    public void logFinishingRollup(RollupJob rollupJob) {
        logEdit(OperationType.OP_FINISHING_ROLLUP, rollupJob);
    }

    public void logFinishRollup(RollupJob rollupJob) {
        logEdit(OperationType.OP_FINISH_ROLLUP, rollupJob);
    }

    public void logCancelRollup(RollupJob rollupJob) {
        logEdit(OperationType.OP_CANCEL_ROLLUP, rollupJob);
    }

    public void logDropRollup(DropInfo info) {
        logEdit(OperationType.OP_DROP_ROLLUP, info);
    }

    public void logBatchDropRollup(BatchDropInfo batchDropInfo) {
        logEdit(OperationType.OP_BATCH_DROP_ROLLUP, batchDropInfo);
    }

    public void logStartSchemaChange(SchemaChangeJob schemaChangeJob) {
        logEdit(OperationType.OP_START_SCHEMA_CHANGE, schemaChangeJob);
    }

    public void logFinishingSchemaChange(SchemaChangeJob schemaChangeJob) {
        logEdit(OperationType.OP_FINISHING_SCHEMA_CHANGE, schemaChangeJob);
    }

    public void logFinishSchemaChange(SchemaChangeJob schemaChangeJob) {
        logEdit(OperationType.OP_FINISH_SCHEMA_CHANGE, schemaChangeJob);
    }

    public void logCancelSchemaChange(SchemaChangeJob schemaChangeJob) {
        logEdit(OperationType.OP_CANCEL_SCHEMA_CHANGE, schemaChangeJob);
    }

    public void logFinishConsistencyCheck(ConsistencyCheckInfo info) {
        logEdit(OperationType.OP_FINISH_CONSISTENCY_CHECK, info);
    }

    public void logAddBackend(Backend be) {
        logEdit(OperationType.OP_ADD_BACKEND, be);
    }

    public void logDropBackend(Backend be) {
        logEdit(OperationType.OP_DROP_BACKEND, be);
    }

    public void logAddFrontend(Frontend fe) {
        logEdit(OperationType.OP_ADD_FRONTEND, fe);
    }

    public void logAddFirstFrontend(Frontend fe) {
        logEdit(OperationType.OP_ADD_FIRST_FRONTEND, fe);
    }

    public void logRemoveFrontend(Frontend fe) {
        logEdit(OperationType.OP_REMOVE_FRONTEND, fe);
    }

    public void logUpdateFrontend(Frontend fe) {
        logEdit(OperationType.OP_UPDATE_FRONTEND, fe);
    }

    public void logFinishDelete(DeleteInfo info) {
        logEdit(OperationType.OP_FINISH_DELETE, info);
    }

    public void logFinishMultiDelete(MultiDeleteInfo info) {
        logEdit(OperationType.OP_FINISH_MULTI_DELETE, info);
    }

    public void logAddReplica(ReplicaPersistInfo info) {
        logEdit(OperationType.OP_ADD_REPLICA, info);
    }

    public void logUpdateReplica(ReplicaPersistInfo info) {
        logEdit(OperationType.OP_UPDATE_REPLICA, info);
    }

    public void logDeleteReplica(ReplicaPersistInfo info) {
        logEdit(OperationType.OP_DELETE_REPLICA, info);
    }

    public void logTimestamp(Timestamp stamp) {
        logEdit(OperationType.OP_TIMESTAMP, stamp);
    }

    public void logMasterInfo(MasterInfo info) {
        logEdit(OperationType.OP_MASTER_INFO_CHANGE, info);
    }

    public void logMetaVersion(int version) {
        logEdit(OperationType.OP_META_VERSION, new Text(Integer.toString(version)));
    }

    public void logMetaVersion(MetaVersion metaVersion) {
        logEdit(OperationType.OP_META_VERSION_V2, metaVersion);
    }

    public void logBackendStateChange(Backend be) {
        logEdit(OperationType.OP_BACKEND_STATE_CHANGE, be);
    }

    public void logCreateUser(PrivInfo info) {
        logEdit(OperationType.OP_CREATE_USER, info);
    }

    public void logNewDropUser(UserIdentity userIdent) {
        logEdit(OperationType.OP_NEW_DROP_USER, userIdent);
    }

    public void logGrantPriv(PrivInfo info) {
        logEdit(OperationType.OP_GRANT_PRIV, info);
    }

    public void logRevokePriv(PrivInfo info) {
        logEdit(OperationType.OP_REVOKE_PRIV, info);
    }

    public void logGrantImpersonate(ImpersonatePrivInfo info) {
        logEdit(OperationType.OP_GRANT_IMPERSONATE, info);
    }

    public void logRevokeImpersonate(ImpersonatePrivInfo info) {
        logEdit(OperationType.OP_REVOKE_IMPERSONATE, info);
    }

    public void logSetPassword(PrivInfo info) {
        logEdit(OperationType.OP_SET_PASSWORD, info);
    }

    public void logCreateRole(PrivInfo info) {
        logEdit(OperationType.OP_CREATE_ROLE, info);
    }

    public void logDropRole(PrivInfo info) {
        logEdit(OperationType.OP_DROP_ROLE, info);
    }

    public void logStartDecommissionBackend(DecommissionBackendJob job) {
        logEdit(OperationType.OP_START_DECOMMISSION_BACKEND, job);
    }

    public void logFinishDecommissionBackend(DecommissionBackendJob job) {
        logEdit(OperationType.OP_FINISH_DECOMMISSION_BACKEND, job);
    }

    public void logDatabaseRename(DatabaseInfo databaseInfo) {
        logEdit(OperationType.OP_RENAME_DB, databaseInfo);
    }

    public void logUpdateDatabase(DatabaseInfo databaseInfo) {
        logEdit(OperationType.OP_UPDATE_DB, databaseInfo);
    }

    public void logTableRename(TableInfo tableInfo) {
        logEdit(OperationType.OP_RENAME_TABLE, tableInfo);
    }

    public void logModifyViewDef(AlterViewInfo alterViewInfo) {
        logEdit(OperationType.OP_MODIFY_VIEW_DEF, alterViewInfo);
    }

    public void logRollupRename(TableInfo tableInfo) {
        logEdit(OperationType.OP_RENAME_ROLLUP, tableInfo);
    }

    public void logPartitionRename(TableInfo tableInfo) {
        logEdit(OperationType.OP_RENAME_PARTITION, tableInfo);
    }

    public void logGlobalVariable(SessionVariable variable) {
        logEdit(OperationType.OP_GLOBAL_VARIABLE, variable);
    }

    public void logCreateCluster(Cluster cluster) {
        logEdit(OperationType.OP_CREATE_CLUSTER, cluster);
    }

    public void logDropCluster(ClusterInfo info) {
        logEdit(OperationType.OP_DROP_CLUSTER, info);
    }

    public void logExpandCluster(ClusterInfo ci) {
        logEdit(OperationType.OP_EXPAND_CLUSTER, ci);
    }

    public void logLinkCluster(BaseParam param) {
        logEdit(OperationType.OP_LINK_CLUSTER, param);
    }

    public void logMigrateCluster(BaseParam param) {
        logEdit(OperationType.OP_MIGRATE_CLUSTER, param);
    }

    public void logDropLinkDb(DropLinkDbAndUpdateDbInfo info) {
        logEdit(OperationType.OP_DROP_LINKDB, info);
    }

    public void logAddBroker(BrokerMgr.ModifyBrokerInfo info) {
        logEdit(OperationType.OP_ADD_BROKER, info);
    }

    public void logDropBroker(BrokerMgr.ModifyBrokerInfo info) {
        logEdit(OperationType.OP_DROP_BROKER, info);
    }

    public void logDropAllBroker(String brokerName) {
        logEdit(OperationType.OP_DROP_ALL_BROKER, new Text(brokerName));
    }

    public void logSetLoadErrorHub(LoadErrorHub.Param param) {
        logEdit(OperationType.OP_SET_LOAD_ERROR_HUB, param);
    }

    public void logExportCreate(ExportJob job) {
        logEdit(OperationType.OP_EXPORT_CREATE, job);
    }

    public void logExportUpdateState(long jobId, ExportJob.JobState newState) {
        ExportJob.StateTransfer transfer = new ExportJob.StateTransfer(jobId, newState);
        logEdit(OperationType.OP_EXPORT_UPDATE_STATE, transfer);
    }

    public void logUpdateClusterAndBackendState(BackendIdsUpdateInfo info) {
        logEdit(OperationType.OP_UPDATE_CLUSTER_AND_BACKENDS, info);
    }

    // for TransactionState
    public void logInsertTransactionState(TransactionState transactionState) {
        logEdit(OperationType.OP_UPSERT_TRANSACTION_STATE, transactionState);
    }

    public void logDeleteTransactionState(TransactionState transactionState) {
        logEdit(OperationType.OP_DELETE_TRANSACTION_STATE, transactionState);
    }

    public void logBackupJob(BackupJob job) {
        logEdit(OperationType.OP_BACKUP_JOB, job);
    }

    public void logCreateRepository(Repository repo) {
        logEdit(OperationType.OP_CREATE_REPOSITORY, repo);
    }

    public void logDropRepository(String repoName) {
        logEdit(OperationType.OP_DROP_REPOSITORY, new Text(repoName));
    }

    public void logRestoreJob(RestoreJob job) {
        logEdit(OperationType.OP_RESTORE_JOB, job);
    }

    public void logUpdateUserProperty(UserPropertyInfo propertyInfo) {
        logEdit(OperationType.OP_UPDATE_USER_PROPERTY, propertyInfo);
    }

    public void logTruncateTable(TruncateTableInfo info) {
        logEdit(OperationType.OP_TRUNCATE_TABLE, info);
    }

    public void logColocateAddTable(ColocatePersistInfo info) {
        logEdit(OperationType.OP_COLOCATE_ADD_TABLE, info);
    }

    public void logColocateRemoveTable(ColocatePersistInfo info) {
        logEdit(OperationType.OP_COLOCATE_REMOVE_TABLE, info);
    }

    public void logColocateBackendsPerBucketSeq(ColocatePersistInfo info) {
        logEdit(OperationType.OP_COLOCATE_BACKENDS_PER_BUCKETSEQ, info);
    }

    public void logColocateMarkUnstable(ColocatePersistInfo info) {
        logEdit(OperationType.OP_COLOCATE_MARK_UNSTABLE, info);
    }

    public void logColocateMarkStable(ColocatePersistInfo info) {
        logEdit(OperationType.OP_COLOCATE_MARK_STABLE, info);
    }

    public void logModifyTableColocate(TablePropertyInfo info) {
        logEdit(OperationType.OP_MODIFY_TABLE_COLOCATE, info);
    }

    public void logHeartbeat(HbPackage hbPackage) {
        logEdit(OperationType.OP_HEARTBEAT_V2, hbPackage);
    }

    public void logAddFunction(Function function) {
        logEdit(OperationType.OP_ADD_FUNCTION, function);
    }

    public void logDropFunction(FunctionSearchDesc function) {
        logEdit(OperationType.OP_DROP_FUNCTION, function);
    }

    public void logSetHasForbitGlobalDict(ModifyTablePropertyOperationLog info) {
        logEdit(OperationType.OP_SET_FORBIT_GLOBAL_DICT, info);
    }

    public void logBackendTabletsInfo(BackendTabletsInfo backendTabletsInfo) {
        logEdit(OperationType.OP_BACKEND_TABLETS_INFO, backendTabletsInfo);
    }

    public void logCreateRoutineLoadJob(RoutineLoadJob routineLoadJob) {
        logEdit(OperationType.OP_CREATE_ROUTINE_LOAD_JOB, routineLoadJob);
    }

    public void logOpRoutineLoadJob(RoutineLoadOperation routineLoadOperation) {
        logEdit(OperationType.OP_CHANGE_ROUTINE_LOAD_JOB, routineLoadOperation);
    }

    public void logRemoveRoutineLoadJob(RoutineLoadOperation operation) {
        logEdit(OperationType.OP_REMOVE_ROUTINE_LOAD_JOB, operation);
    }

    public void logCreateLoadJob(com.starrocks.load.loadv2.LoadJob loadJob) {
        logEdit(OperationType.OP_CREATE_LOAD_JOB, loadJob);
    }

    public void logEndLoadJob(LoadJobFinalOperation loadJobFinalOperation) {
        logEdit(OperationType.OP_END_LOAD_JOB, loadJobFinalOperation);
    }

    public void logUpdateLoadJob(LoadJobStateUpdateInfo info) {
        logEdit(OperationType.OP_UPDATE_LOAD_JOB, info);
    }

    public void logCreateResource(Resource resource) {
        logEdit(OperationType.OP_CREATE_RESOURCE, resource);
    }

    public void logDropResource(DropResourceOperationLog operationLog) {
        logEdit(OperationType.OP_DROP_RESOURCE, operationLog);
    }

    public void logCreateSmallFile(SmallFile info) {
        logEdit(OperationType.OP_CREATE_SMALL_FILE, info);
    }

    public void logDropSmallFile(SmallFile info) {
        logEdit(OperationType.OP_DROP_SMALL_FILE, info);
    }

    public void logAlterJob(AlterJobV2 alterJob) {
        logEdit(OperationType.OP_ALTER_JOB_V2, alterJob);
    }

    public void logBatchAlterJob(BatchAlterJobPersistInfo batchAlterJobV2) {
        logEdit(OperationType.OP_BATCH_ADD_ROLLUP, batchAlterJobV2);
    }

    public void logModifyDistributionType(TableInfo tableInfo) {
        logEdit(OperationType.OP_MODIFY_DISTRIBUTION_TYPE, tableInfo);
    }

    public void logDynamicPartition(ModifyTablePropertyOperationLog info) {
        logEdit(OperationType.OP_DYNAMIC_PARTITION, info);
    }

    public void logModifyReplicationNum(ModifyTablePropertyOperationLog info) {
        logEdit(OperationType.OP_MODIFY_REPLICATION_NUM, info);
    }

    public void logModifyInMemory(ModifyTablePropertyOperationLog info) {
        logEdit(OperationType.OP_MODIFY_IN_MEMORY, info);
    }

    public void logModifyEnablePersistentIndex(ModifyTablePropertyOperationLog info) {
        logEdit(OperationType.OP_MODIFY_ENABLE_PERSISTENT_INDEX, info);
    }

    public void logReplaceTempPartition(ReplacePartitionOperationLog info) {
        logEdit(OperationType.OP_REPLACE_TEMP_PARTITION, info);
    }

    public void logInstallPlugin(PluginInfo plugin) {
        logEdit(OperationType.OP_INSTALL_PLUGIN, plugin);
    }

    public void logUninstallPlugin(PluginInfo plugin) {
        logEdit(OperationType.OP_UNINSTALL_PLUGIN, plugin);
    }

    public void logSetReplicaStatus(SetReplicaStatusOperationLog log) {
        logEdit(OperationType.OP_SET_REPLICA_STATUS, log);
    }

    public void logRemoveExpiredAlterJobV2(RemoveAlterJobV2OperationLog log) {
        logEdit(OperationType.OP_REMOVE_ALTER_JOB_V2, log);
    }

    public void logAlterRoutineLoadJob(AlterRoutineLoadJobOperationLog log) {
        logEdit(OperationType.OP_ALTER_ROUTINE_LOAD_JOB, log);
    }

    public void logGlobalVariableV2(GlobalVarPersistInfo info) {
        logEdit(OperationType.OP_GLOBAL_VARIABLE_V2, info);
    }

    public void logSwapTable(SwapTableOperationLog log) {
        logEdit(OperationType.OP_SWAP_TABLE, log);
    }

    public void logAddAnalyzeJob(AnalyzeJob job) {
        logEdit(OperationType.OP_ADD_ANALYZER_JOB, job);
    }

    public void logRemoveAnalyzeJob(AnalyzeJob job) {
        logEdit(OperationType.OP_REMOVE_ANALYZER_JOB, job);
    }

    public void logModifyTableColumn(ModifyTableColumnOperationLog log) {
        logEdit(OperationType.OP_MODIFY_HIVE_TABLE_COLUMN, log);
    }

    public void logCreateCatalog(Catalog log) {
        logEdit(OperationType.OP_CREATE_CATALOG, log);
    }

    public void logDropCatalog(DropCatalogLog log) {
        logEdit(OperationType.OP_DROP_CATALOG, log);
    }
}
