/*
 * Copyright 2020 ConsenSys AG.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 */

package tech.pegasys.teku.storage.server.rocksdb;

import static java.util.stream.Collectors.toList;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static tech.pegasys.teku.infrastructure.async.SafeFutureAssert.assertThatSafeFuture;

import com.google.common.collect.Streams;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.apache.tuweni.bytes.Bytes32;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tech.pegasys.teku.core.ChainBuilder;
import tech.pegasys.teku.core.lookup.BlockProvider;
import tech.pegasys.teku.core.lookup.StateAndBlockProvider;
import tech.pegasys.teku.datastructures.blocks.SignedBeaconBlock;
import tech.pegasys.teku.datastructures.blocks.SignedBlockAndState;
import tech.pegasys.teku.datastructures.state.BeaconState;
import tech.pegasys.teku.datastructures.state.Checkpoint;
import tech.pegasys.teku.datastructures.util.DataStructureUtil;
import tech.pegasys.teku.infrastructure.async.AsyncRunner;
import tech.pegasys.teku.infrastructure.async.SafeFuture;
import tech.pegasys.teku.infrastructure.unsigned.UInt64;
import tech.pegasys.teku.pow.event.MinGenesisTimeBlockEvent;
import tech.pegasys.teku.storage.server.AbstractStorageBackedDatabaseTest;
import tech.pegasys.teku.storage.server.ShuttingDownException;
import tech.pegasys.teku.storage.server.rocksdb.dataaccess.RocksDbEth1Dao;
import tech.pegasys.teku.storage.server.rocksdb.dataaccess.RocksDbFinalizedDao;
import tech.pegasys.teku.storage.server.rocksdb.dataaccess.RocksDbHotDao;
import tech.pegasys.teku.storage.store.StoreBuilder;
import tech.pegasys.teku.storage.store.UpdatableStore;
import tech.pegasys.teku.storage.store.UpdatableStore.StoreTransaction;
import tech.pegasys.teku.util.config.StateStorageMode;

public abstract class AbstractRocksDbDatabaseTest extends AbstractStorageBackedDatabaseTest {

  @Test
  public void shouldThrowIfClosedDatabaseIsModified_setGenesis() throws Exception {
    database.close();
    assertThatThrownBy(() -> database.storeAnchorPoint(genesisAnchor))
        .isInstanceOf(ShuttingDownException.class);
  }

  @Test
  public void shouldThrowIfClosedDatabaseIsModified_update() throws Exception {
    database.storeAnchorPoint(genesisAnchor);
    database.close();

    final SignedBlockAndState newValue = chainBuilder.generateBlockAtSlot(1);
    // Sanity check
    assertThatSafeFuture(store.retrieveBlockState(newValue.getRoot()))
        .isCompletedWithEmptyOptional();
    final StoreTransaction transaction = recentChainData.startStoreTransaction();
    transaction.putBlockAndState(newValue);

    final SafeFuture<Void> result = transaction.commit();
    assertThatThrownBy(result::get).hasCauseInstanceOf(ShuttingDownException.class);
  }

  @Test
  public void createMemoryStore_priorToGenesisTime() {
    database.storeAnchorPoint(genesisAnchor);

    final Optional<StoreBuilder> storeBuilder =
        ((RocksDbDatabase) database).createMemoryStore(() -> 0L);
    assertThat(storeBuilder).isNotEmpty();

    final UpdatableStore store =
        storeBuilder
            .get()
            .asyncRunner(mock(AsyncRunner.class))
            .blockProvider(mock(BlockProvider.class))
            .stateProvider(mock(StateAndBlockProvider.class))
            .build();

    assertThat(store.getTime()).isEqualTo(genesisTime);
  }

  @Test
  public void shouldThrowIfClosedDatabaseIsRead_createMemoryStore() throws Exception {
    database.storeAnchorPoint(genesisAnchor);
    database.close();

    assertThatThrownBy(database::createMemoryStore).isInstanceOf(ShuttingDownException.class);
  }

  @Test
  public void shouldThrowIfClosedDatabaseIsRead_getSlotForFinalizedBlockRoot() throws Exception {
    database.storeAnchorPoint(genesisAnchor);
    database.close();

    assertThatThrownBy(() -> database.getSlotForFinalizedBlockRoot(Bytes32.ZERO))
        .isInstanceOf(ShuttingDownException.class);
  }

  @Test
  public void shouldThrowIfClosedDatabaseIsRead_getSignedBlock() throws Exception {
    database.storeAnchorPoint(genesisAnchor);
    database.close();

    assertThatThrownBy(() -> database.getSignedBlock(genesisCheckpoint.getRoot()))
        .isInstanceOf(ShuttingDownException.class);
  }

  @Test
  public void shouldThrowIfClosedDatabaseIsRead_streamFinalizedBlocks() throws Exception {
    database.storeAnchorPoint(genesisAnchor);
    database.close();

    assertThatThrownBy(() -> database.streamFinalizedBlocks(UInt64.ZERO, UInt64.ONE))
        .isInstanceOf(ShuttingDownException.class);
  }

  @Test
  public void shouldThrowIfClosedDatabaseIsRead_streamFinalizedBlocksShuttingDown()
      throws Exception {
    database.storeAnchorPoint(genesisAnchor);
    try (final Stream<SignedBeaconBlock> stream =
        database.streamFinalizedBlocks(UInt64.ZERO, UInt64.valueOf(1000L))) {
      database.close();
      assertThatThrownBy(stream::findAny).isInstanceOf(ShuttingDownException.class);
    }
  }

  @Test
  public void shouldThrowIfTransactionModifiedAfterDatabaseIsClosed_updateHotDao()
      throws Exception {
    database.storeAnchorPoint(genesisAnchor);

    try (final RocksDbHotDao.HotUpdater updater =
        ((RocksDbDatabase) database).hotDao.hotUpdater()) {
      SignedBlockAndState newBlock = chainBuilder.generateNextBlock();
      database.close();
      assertThatThrownBy(() -> updater.addHotBlock(newBlock.getBlock()))
          .isInstanceOf(ShuttingDownException.class);
    }
  }

  @Test
  public void shouldThrowIfTransactionModifiedAfterDatabaseIsClosed_updateFinalizedDao()
      throws Exception {
    database.storeAnchorPoint(genesisAnchor);

    try (final RocksDbFinalizedDao.FinalizedUpdater updater =
        ((RocksDbDatabase) database).finalizedDao.finalizedUpdater()) {
      SignedBlockAndState newBlock = chainBuilder.generateNextBlock();
      database.close();
      assertThatThrownBy(() -> updater.addFinalizedBlock(newBlock.getBlock()))
          .isInstanceOf(ShuttingDownException.class);
    }
  }

  @Test
  public void shouldThrowIfTransactionModifiedAfterDatabaseIsClosed_updateEth1Dao()
      throws Exception {
    database.storeAnchorPoint(genesisAnchor);

    final DataStructureUtil dataStructureUtil = new DataStructureUtil();
    try (final RocksDbEth1Dao.Eth1Updater updater =
        ((RocksDbDatabase) database).eth1Dao.eth1Updater()) {
      final MinGenesisTimeBlockEvent genesisTimeBlockEvent =
          dataStructureUtil.randomMinGenesisTimeBlockEvent(1);
      database.close();
      assertThatThrownBy(() -> updater.addMinGenesisTimeBlock(genesisTimeBlockEvent))
          .isInstanceOf(ShuttingDownException.class);
    }
  }

  @Test
  public void shouldThrowIfClosedDatabaseIsRead_getHistoricalState() throws Exception {
    // Store genesis
    database.storeAnchorPoint(genesisAnchor);
    // Add a new finalized block to supersede genesis
    final SignedBlockAndState newBlock = chainBuilder.generateBlockAtSlot(1);
    final Checkpoint newCheckpoint = getCheckpointForBlock(newBlock.getBlock());
    final StoreTransaction transaction = recentChainData.startStoreTransaction();
    transaction.putBlockAndState(newBlock);
    transaction.setFinalizedCheckpoint(newCheckpoint);
    transaction.commit().reportExceptions();
    // Close db
    database.close();

    assertThatThrownBy(
            () -> database.getLatestAvailableFinalizedState(genesisCheckpoint.getEpochStartSlot()))
        .isInstanceOf(ShuttingDownException.class);
  }

  @Test
  public void shouldPruneHotBlocksOlderThanFinalizedSlotAfterRestart__archive(
      @TempDir final Path tempDir) throws Exception {
    testShouldPruneHotBlocksOlderThanFinalizedSlotAfterRestart(tempDir, StateStorageMode.ARCHIVE);
  }

  @Test
  public void shouldPruneHotBlocksOlderThanFinalizedSlotAfterRestart__prune(
      @TempDir final Path tempDir) throws Exception {
    testShouldPruneHotBlocksOlderThanFinalizedSlotAfterRestart(tempDir, StateStorageMode.PRUNE);
  }

  private void testShouldPruneHotBlocksOlderThanFinalizedSlotAfterRestart(
      @TempDir final Path tempDir, final StateStorageMode storageMode) throws Exception {
    final long finalizedSlot = 7;
    final int hotBlockCount = 3;
    // Setup chains
    chainBuilder.generateBlocksUpToSlot(finalizedSlot);
    SignedBlockAndState finalizedBlock = chainBuilder.getBlockAndStateAtSlot(finalizedSlot);
    final Checkpoint finalizedCheckpoint = getCheckpointForBlock(finalizedBlock.getBlock());
    final long firstHotBlockSlot =
        finalizedCheckpoint.getEpochStartSlot().plus(UInt64.ONE).longValue();
    for (int i = 0; i < hotBlockCount; i++) {
      chainBuilder.generateBlockAtSlot(firstHotBlockSlot + i);
    }
    final long lastSlot = chainBuilder.getLatestSlot().longValue();

    // Setup database
    createStorage(tempDir.toFile(), storageMode);
    initGenesis();

    add(chainBuilder.streamBlocksAndStates().collect(Collectors.toSet()));

    // Close database and rebuild from disk
    restartStorage();

    justifyAndFinalizeEpoch(finalizedCheckpoint.getEpoch(), finalizedBlock);

    // We should be able to access hot blocks and state
    final List<SignedBlockAndState> expectedHotBlocksAndStates =
        chainBuilder.streamBlocksAndStates(finalizedSlot, lastSlot).collect(toList());
    assertHotBlocksAndStatesInclude(expectedHotBlocksAndStates);

    final Map<Bytes32, BeaconState> historicalStates =
        chainBuilder
            .streamBlocksAndStates(0, 6)
            .collect(Collectors.toMap(SignedBlockAndState::getRoot, SignedBlockAndState::getState));

    switch (storageMode) {
      case ARCHIVE:
        assertFinalizedStatesAvailable(historicalStates);
        break;
      case PRUNE:
        assertStatesUnavailable(
            historicalStates.values().stream().map(BeaconState::getSlot).collect(toList()));
        break;
    }
  }

  @Test
  public void shouldHandleRestartWithUnrecoverableForkBlocks_archive(@TempDir final Path tempDir)
      throws Exception {
    testShouldHandleRestartWithUnrecoverableForkBlocks(tempDir, StateStorageMode.ARCHIVE);
  }

  @Test
  public void shouldHandleRestartWithUnrecoverableForkBlocks_prune(@TempDir final Path tempDir)
      throws Exception {
    testShouldHandleRestartWithUnrecoverableForkBlocks(tempDir, StateStorageMode.PRUNE);
  }

  private void testShouldHandleRestartWithUnrecoverableForkBlocks(
      @TempDir final Path tempDir, final StateStorageMode storageMode) throws Exception {
    // Setup chains
    // Both chains share block up to slot 3
    final ChainBuilder primaryChain = ChainBuilder.create(VALIDATOR_KEYS);
    primaryChain.generateGenesis(genesisTime, true);
    primaryChain.generateBlocksUpToSlot(3);
    final ChainBuilder forkChain = primaryChain.fork();
    // Primary chain's next block is at 7
    final SignedBlockAndState finalizedBlock = primaryChain.generateBlockAtSlot(7);
    final Checkpoint finalizedCheckpoint = getCheckpointForBlock(primaryChain.getBlockAtSlot(7));
    final UInt64 firstHotBlockSlot = finalizedCheckpoint.getEpochStartSlot().plus(UInt64.ONE);
    primaryChain.generateBlockAtSlot(firstHotBlockSlot);
    // Fork chain's next block is at 6
    forkChain.generateBlockAtSlot(6);
    forkChain.generateBlockAtSlot(firstHotBlockSlot);

    // Setup database
    createStorage(tempDir.toFile(), storageMode);
    initGenesis();

    final Set<SignedBlockAndState> allBlocksAndStates =
        Streams.concat(primaryChain.streamBlocksAndStates(), forkChain.streamBlocksAndStates())
            .collect(Collectors.toSet());

    // Finalize at block 7, making the fork blocks unavailable
    add(allBlocksAndStates);
    justifyAndFinalizeEpoch(finalizedCheckpoint.getEpoch(), finalizedBlock);

    // Close database and rebuild from disk
    restartStorage();

    // We should be able to access primary hot blocks and state
    final List<SignedBlockAndState> expectedHotBlocksAndStates =
        primaryChain
            .streamBlocksAndStates(finalizedBlock.getSlot(), chainBuilder.getLatestSlot())
            .collect(toList());
    assertHotBlocksAndStatesInclude(expectedHotBlocksAndStates);

    // Fork states should be unavailable
    final List<Bytes32> unavailableBlockRoots =
        forkChain
            .streamBlocksAndStates(4, firstHotBlockSlot.longValue())
            .map(SignedBlockAndState::getRoot)
            .collect(Collectors.toList());
    final List<UInt64> unavailableBlockSlots =
        forkChain
            .streamBlocksAndStates(4, firstHotBlockSlot.longValue())
            .map(SignedBlockAndState::getSlot)
            .collect(Collectors.toList());
    assertStatesUnavailable(unavailableBlockSlots);
    assertBlocksUnavailable(unavailableBlockRoots);
  }
}
