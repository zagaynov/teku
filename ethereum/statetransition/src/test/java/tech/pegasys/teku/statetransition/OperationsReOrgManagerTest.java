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

package tech.pegasys.teku.statetransition;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.NavigableMap;
import java.util.Optional;
import java.util.TreeMap;
import java.util.stream.Collectors;
import org.apache.tuweni.bytes.Bytes32;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tech.pegasys.teku.datastructures.attestation.ValidateableAttestation;
import tech.pegasys.teku.datastructures.blocks.BeaconBlock;
import tech.pegasys.teku.datastructures.operations.AttesterSlashing;
import tech.pegasys.teku.datastructures.operations.ProposerSlashing;
import tech.pegasys.teku.datastructures.operations.SignedVoluntaryExit;
import tech.pegasys.teku.datastructures.util.AttestationProcessingResult;
import tech.pegasys.teku.datastructures.util.DataStructureUtil;
import tech.pegasys.teku.infrastructure.async.SafeFuture;
import tech.pegasys.teku.infrastructure.unsigned.UInt64;
import tech.pegasys.teku.statetransition.attestation.AggregatingAttestationPool;
import tech.pegasys.teku.statetransition.attestation.AttestationManager;
import tech.pegasys.teku.storage.api.ReorgContext;
import tech.pegasys.teku.storage.client.RecentChainData;

@SuppressWarnings("unchecked")
public class OperationsReOrgManagerTest {

  private DataStructureUtil dataStructureUtil = new DataStructureUtil();

  private OperationPool<ProposerSlashing> proposerSlashingOperationPool = mock(OperationPool.class);
  private OperationPool<AttesterSlashing> attesterSlashingOperationPool = mock(OperationPool.class);
  private OperationPool<SignedVoluntaryExit> exitOperationPool = mock(OperationPool.class);
  private AggregatingAttestationPool attestationPool = mock(AggregatingAttestationPool.class);
  private AttestationManager attestationManager = mock(AttestationManager.class);

  private RecentChainData recentChainData = mock(RecentChainData.class);

  private OperationsReOrgManager operationsReOrgManager =
      new OperationsReOrgManager(
          proposerSlashingOperationPool,
          attesterSlashingOperationPool,
          exitOperationPool,
          attestationPool,
          attestationManager,
          recentChainData);

  @Test
  void shouldRequeueAndRemoveOperations() {
    BeaconBlock fork1Block1 = dataStructureUtil.randomBeaconBlock(10);
    BeaconBlock fork1Block2 = dataStructureUtil.randomBeaconBlock(11);

    BeaconBlock fork2Block1 = dataStructureUtil.randomBeaconBlock(12);
    BeaconBlock fork2Block2 = dataStructureUtil.randomBeaconBlock(13);

    UInt64 commonAncestorSlot = UInt64.valueOf(9);

    NavigableMap<UInt64, Bytes32> nowNotCanonicalBlockRoots = new TreeMap<>();
    nowNotCanonicalBlockRoots.put(UInt64.valueOf(10), fork1Block1.hash_tree_root());
    nowNotCanonicalBlockRoots.put(UInt64.valueOf(11), fork1Block2.hash_tree_root());
    when(recentChainData.getAncestorsOnFork(commonAncestorSlot, fork1Block2.hash_tree_root()))
        .thenReturn(nowNotCanonicalBlockRoots);

    NavigableMap<UInt64, Bytes32> nowCanonicalBlockRoots = new TreeMap<>();
    nowCanonicalBlockRoots.put(UInt64.valueOf(12), fork2Block1.hash_tree_root());
    nowCanonicalBlockRoots.put(UInt64.valueOf(13), fork2Block2.hash_tree_root());
    when(recentChainData.getAncestorsOnFork(commonAncestorSlot, fork2Block2.hash_tree_root()))
        .thenReturn(nowCanonicalBlockRoots);

    when(recentChainData.retrieveBlockByRoot(fork1Block1.hash_tree_root()))
        .thenReturn(SafeFuture.completedFuture(Optional.of(fork1Block1)));
    when(recentChainData.retrieveBlockByRoot(fork1Block2.hash_tree_root()))
        .thenReturn(SafeFuture.completedFuture(Optional.of(fork1Block2)));

    when(recentChainData.retrieveBlockByRoot(fork2Block1.hash_tree_root()))
        .thenReturn(SafeFuture.completedFuture(Optional.of(fork2Block1)));
    when(recentChainData.retrieveBlockByRoot(fork2Block2.hash_tree_root()))
        .thenReturn(SafeFuture.completedFuture(Optional.of(fork2Block2)));

    when(attestationManager.onAttestation(any()))
        .thenReturn(SafeFuture.completedFuture(AttestationProcessingResult.SUCCESSFUL));

    operationsReOrgManager.chainHeadUpdated(
        UInt64.valueOf(13),
        fork2Block2.getState_root(),
        fork2Block2.hash_tree_root(),
        false,
        ReorgContext.of(
            fork1Block2.hash_tree_root(), fork1Block2.getState_root(), commonAncestorSlot));

    verify(recentChainData).getAncestorsOnFork(commonAncestorSlot, fork1Block2.hash_tree_root());

    verify(proposerSlashingOperationPool).addAll(fork1Block1.getBody().getProposer_slashings());
    verify(attesterSlashingOperationPool).addAll(fork1Block1.getBody().getAttester_slashings());
    verify(exitOperationPool).addAll(fork1Block1.getBody().getVoluntary_exits());

    verify(proposerSlashingOperationPool).addAll(fork1Block2.getBody().getProposer_slashings());
    verify(attesterSlashingOperationPool).addAll(fork1Block2.getBody().getAttester_slashings());
    verify(exitOperationPool).addAll(fork1Block2.getBody().getVoluntary_exits());

    ArgumentCaptor<ValidateableAttestation> argument =
        ArgumentCaptor.forClass(ValidateableAttestation.class);
    verify(attestationManager, atLeastOnce()).onAttestation(argument.capture());

    List<ValidateableAttestation> attestationList = new ArrayList<>();
    attestationList.addAll(
        fork1Block1.getBody().getAttestations().stream()
            .map(ValidateableAttestation::from)
            .collect(Collectors.toList()));
    attestationList.addAll(
        fork1Block2.getBody().getAttestations().stream()
            .map(ValidateableAttestation::from)
            .collect(Collectors.toList()));
    assertThat(argument.getAllValues()).containsExactlyInAnyOrderElementsOf(attestationList);

    verify(proposerSlashingOperationPool).removeAll(fork2Block1.getBody().getProposer_slashings());
    verify(attesterSlashingOperationPool).removeAll(fork2Block1.getBody().getAttester_slashings());
    verify(exitOperationPool).removeAll(fork2Block1.getBody().getVoluntary_exits());
    verify(attestationPool).removeAll(fork2Block1.getBody().getAttestations());

    verify(proposerSlashingOperationPool).removeAll(fork2Block2.getBody().getProposer_slashings());
    verify(attesterSlashingOperationPool).removeAll(fork2Block2.getBody().getAttester_slashings());
    verify(exitOperationPool).removeAll(fork2Block2.getBody().getVoluntary_exits());
    verify(attestationPool).removeAll(fork2Block2.getBody().getAttestations());
  }

  @Test
  void shouldOnlyRemoveOperations() {
    BeaconBlock block1 = dataStructureUtil.randomBeaconBlock(10);
    BeaconBlock block2 = dataStructureUtil.randomBeaconBlock(11);

    UInt64 commonAncestorSlot = UInt64.valueOf(9);

    NavigableMap<UInt64, Bytes32> nowCanonicalBlockRoots = new TreeMap<>();
    nowCanonicalBlockRoots.put(UInt64.valueOf(12), block1.hash_tree_root());
    nowCanonicalBlockRoots.put(UInt64.valueOf(13), block2.hash_tree_root());
    when(recentChainData.getAncestorsOnFork(commonAncestorSlot, block2.hash_tree_root()))
        .thenReturn(nowCanonicalBlockRoots);

    // reOrged old chain
    when(recentChainData.getAncestorsOnFork(commonAncestorSlot, Bytes32.ZERO))
        .thenReturn(new TreeMap<>());

    when(recentChainData.retrieveBlockByRoot(block1.hash_tree_root()))
        .thenReturn(SafeFuture.completedFuture(Optional.of(block1)));
    when(recentChainData.retrieveBlockByRoot(block2.hash_tree_root()))
        .thenReturn(SafeFuture.completedFuture(Optional.of(block2)));

    when(attestationManager.onAttestation(any()))
        .thenReturn(SafeFuture.completedFuture(AttestationProcessingResult.SUCCESSFUL));

    operationsReOrgManager.chainHeadUpdated(
        UInt64.valueOf(13),
        block2.getState_root(),
        block2.hash_tree_root(),
        false,
        ReorgContext.of(Bytes32.ZERO, Bytes32.ZERO, commonAncestorSlot));

    verify(recentChainData).getAncestorsOnFork(commonAncestorSlot, block2.hash_tree_root());

    verify(exitOperationPool, never()).addAll(any());
    verify(proposerSlashingOperationPool, never()).addAll(any());
    verify(attesterSlashingOperationPool, never()).addAll(any());
    verify(attestationManager, never()).onAttestation(any());

    verify(proposerSlashingOperationPool).removeAll(block2.getBody().getProposer_slashings());
    verify(attesterSlashingOperationPool).removeAll(block2.getBody().getAttester_slashings());
    verify(exitOperationPool).removeAll(block2.getBody().getVoluntary_exits());
    verify(attestationPool).removeAll(block2.getBody().getAttestations());

    verify(proposerSlashingOperationPool).removeAll(block1.getBody().getProposer_slashings());
    verify(attesterSlashingOperationPool).removeAll(block1.getBody().getAttester_slashings());
    verify(exitOperationPool).removeAll(block1.getBody().getVoluntary_exits());
    verify(attestationPool).removeAll(block1.getBody().getAttestations());
  }
}
