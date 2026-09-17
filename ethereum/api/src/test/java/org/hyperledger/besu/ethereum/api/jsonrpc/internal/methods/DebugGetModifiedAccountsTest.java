/*
 * Copyright contributors to Besu.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package org.hyperledger.besu.ethereum.api.jsonrpc.internal.methods;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.hyperledger.besu.datatypes.AccountValue;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.datatypes.Hash;
import org.hyperledger.besu.datatypes.Wei;
import org.hyperledger.besu.ethereum.api.jsonrpc.RpcMethod;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.JsonRpcRequest;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.JsonRpcRequestContext;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.response.JsonRpcErrorResponse;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.response.JsonRpcResponse;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.response.JsonRpcSuccessResponse;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.response.RpcErrorType;
import org.hyperledger.besu.ethereum.api.query.BlockchainQueries;
import org.hyperledger.besu.ethereum.chain.Blockchain;
import org.hyperledger.besu.ethereum.core.BlockHeader;
import org.hyperledger.besu.ethereum.core.BlockHeaderTestFixture;
import org.hyperledger.besu.ethereum.trie.common.PmtStateTrieAccountValue;
import org.hyperledger.besu.ethereum.trie.pathbased.common.trielog.TrieLogLayer;
import org.hyperledger.besu.ethereum.trie.pathbased.common.trielog.TrieLogManager;
import org.hyperledger.besu.ethereum.trie.pathbased.common.worldview.accumulator.PathBasedValue;
import org.hyperledger.besu.plugin.services.trielogs.TrieLog;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.apache.tuweni.bytes.Bytes;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class DebugGetModifiedAccountsTest {

  // the hex strings are the expected wire format: lower case, 0x prefixed, not truncated
  private static final String A_HEX = "0x000000000000000000000000000000000000000a";
  private static final String B_HEX = "0x000000000000000000000000000000000000000b";
  private static final String C_HEX = "0x000000000000000000000000000000000000000c";
  private static final String D_HEX = "0x000000000000000000000000000000000000000d";
  private static final Address A = Address.fromHexString(A_HEX);
  private static final Address B = Address.fromHexString(B_HEX);
  private static final Address C = Address.fromHexString(C_HEX);
  private static final Address D = Address.fromHexString(D_HEX);
  private static final Hash OTHER_STORAGE_ROOT = Hash.hash(Bytes.of(1));
  private static final Hash OTHER_CODE_HASH = Hash.hash(Bytes.of(2));
  private static final int CHAIN_HEAD = 5;

  private final Blockchain blockchain = mock(Blockchain.class);
  private final BlockchainQueries blockchainQueries = mock(BlockchainQueries.class);
  private final TrieLogManager trieLogManager = mock(TrieLogManager.class);
  private final List<BlockHeader> headers = new ArrayList<>();
  private final Map<Integer, TrieLogLayer> layers = new HashMap<>();

  private DebugGetModifiedAccountsByNumber byNumber;
  private DebugGetModifiedAccountsByHash byHash;

  @BeforeEach
  public void setup() {
    when(blockchainQueries.getBlockchain()).thenReturn(blockchain);
    when(trieLogManager.getMaxLayersToLoad()).thenReturn(512L);

    Hash parent = Hash.ZERO;
    for (long n = 0; n <= CHAIN_HEAD; n++) {
      final BlockHeader header =
          new BlockHeaderTestFixture().number(n).parentHash(parent).buildHeader();
      when(blockchain.getBlockHeader(n)).thenReturn(Optional.of(header));
      when(blockchain.getBlockHeader(header.getBlockHash())).thenReturn(Optional.of(header));
      headers.add(header);
      parent = header.getBlockHash();
    }

    byHash = new DebugGetModifiedAccountsByHash(blockchainQueries, trieLogManager);
    byNumber = new DebugGetModifiedAccountsByNumber(blockchainQueries, trieLogManager);
  }

  @Test
  public void methodNames() {
    assertThat(byNumber.getName())
        .isEqualTo(RpcMethod.DEBUG_GET_MODIFIED_ACCOUNTS_BY_NUMBER.getMethodName());
    assertThat(byHash.getName())
        .isEqualTo(RpcMethod.DEBUG_GET_MODIFIED_ACCOUNTS_BY_HASH.getMethodName());
  }

  @Test
  public void byNumberReturnsAccountsModifiedAcrossTheRange() {
    trieLog(2, layer().addAccountChange(A, account(0, 0), account(1, 0)));
    trieLog(3, layer().addAccountChange(B, account(0, 0), account(0, 7)));

    assertThat(modified(byNumber, number(1), number(3))).containsExactly(A_HEX, B_HEX);

    // and each reported account really does hold a different value at the end of the range
    assertThat(prior(2, A).getNonce()).isZero();
    assertThat(updated(2, A).getNonce()).isEqualTo(1);
    assertThat(updated(2, A).getBalance()).isEqualTo(Wei.ZERO);
    assertThat(prior(3, B).getBalance()).isEqualTo(Wei.ZERO);
    assertThat(updated(3, B).getBalance()).isEqualTo(Wei.of(7));
    assertThat(updated(3, B).getNonce()).isZero();
  }

  @Test
  public void byHashReturnsAccountsModifiedAcrossTheRange() {
    trieLog(2, layer().addAccountChange(A, account(0, 0), account(1, 0)));
    trieLog(3, layer().addAccountChange(B, account(0, 0), account(0, 7)));

    assertThat(modified(byHash, hash(1), hash(3))).containsExactly(A_HEX, B_HEX);

    assertThat(updated(2, A).getNonce()).isEqualTo(1);
    assertThat(updated(3, B).getBalance()).isEqualTo(Wei.of(7));
  }

  @Test
  public void changeToAnyPartOfTheAccountIsReported() {
    trieLog(
        2,
        layer()
            .addAccountChange(A, account(0, 0), account(1, 0))
            .addAccountChange(B, account(0, 0), account(0, 7))
            .addAccountChange(
                C, account(0, 0), account(0, 0, Hash.EMPTY_TRIE_HASH, OTHER_CODE_HASH))
            .addAccountChange(D, account(0, 0), account(0, 0, OTHER_STORAGE_ROOT, Hash.EMPTY)));

    // nonce, balance, code hash and storage root respectively
    assertThat(modified(byNumber, number(2))).containsExactly(A_HEX, B_HEX, C_HEX, D_HEX);

    // each account differs from its prior in exactly the one field named above
    assertThat(updated(2, A).getNonce()).isEqualTo(1);
    assertThat(updated(2, A).getBalance()).isEqualTo(prior(2, A).getBalance());
    assertThat(updated(2, B).getBalance()).isEqualTo(Wei.of(7));
    assertThat(updated(2, B).getNonce()).isEqualTo(prior(2, B).getNonce());
    assertThat(updated(2, C).getCodeHash()).isEqualTo(OTHER_CODE_HASH);
    assertThat(updated(2, C).getStorageRoot()).isEqualTo(prior(2, C).getStorageRoot());
    assertThat(updated(2, D).getStorageRoot()).isEqualTo(OTHER_STORAGE_ROOT);
    assertThat(updated(2, D).getCodeHash()).isEqualTo(prior(2, D).getCodeHash());
  }

  @Test
  public void accountTouchedWithoutChangingIsNotReported() {
    // a trie log can hold a touched-but-identical account, e.g. a zero value self transfer
    trieLog(2, layer().addAccountChange(A, account(3, 5), account(3, 5)));
    trieLog(3, layer().addAccountChange(B, account(0, 0), account(0, 7)));

    assertThat(modified(byNumber, number(1), number(3))).containsExactly(B_HEX);

    // A was in the trie log, but with nonce 3 and balance 5 on both sides of the change
    assertThat(prior(2, A).getNonce()).isEqualTo(3);
    assertThat(updated(2, A).getNonce()).isEqualTo(3);
    assertThat(updated(2, A).getBalance()).isEqualTo(Wei.of(5));
  }

  @Test
  public void singleBlockIsDiffedAgainstItsParent() {
    trieLog(2, layer().addAccountChange(A, account(0, 0), account(1, 0)));
    trieLog(3, layer().addAccountChange(B, account(0, 0), account(0, 7)));

    assertThat(modified(byNumber, number(3))).containsExactly(B_HEX);
    assertThat(modified(byHash, hash(3))).containsExactly(B_HEX);

    // B's balance moved in block 3; A's nonce moved a block earlier, outside the diff
    assertThat(updated(3, B).getBalance()).isEqualTo(Wei.of(7));
    assertThat(updated(2, A).getNonce()).isEqualTo(1);
  }

  @Test
  public void accountModifiedInSeveralBlocksIsReportedOnce() {
    trieLog(2, layer().addAccountChange(A, account(0, 0), account(1, 0)));
    trieLog(3, layer().addAccountChange(A, account(1, 0), account(2, 0)));

    assertThat(modified(byNumber, number(1), number(3))).containsExactly(A_HEX);

    // the net change over the range is nonce 0 -> 2; the intermediate nonce of 1 is irrelevant
    assertThat(prior(2, A).getNonce()).isZero();
    assertThat(updated(2, A).getNonce()).isEqualTo(1);
    assertThat(prior(3, A).getNonce()).isEqualTo(1);
    assertThat(updated(3, A).getNonce()).isEqualTo(2);
  }

  @Test
  public void accountRevertedToItsStartingValueIsNotReported() {
    trieLog(2, layer().addAccountChange(A, account(0, 0), account(1, 0)));
    trieLog(
        3,
        layer()
            .addAccountChange(A, account(1, 0), account(0, 0))
            .addAccountChange(B, account(0, 0), account(0, 7)));

    assertThat(modified(byNumber, number(1), number(3))).containsExactly(B_HEX);

    // A ends the range holding exactly what it held at the start: nonce 0, balance 0
    assertThat(updated(3, A).getNonce()).isEqualTo(prior(2, A).getNonce());
    assertThat(updated(3, A).getBalance()).isEqualTo(prior(2, A).getBalance());
    assertThat(updated(3, A).getCodeHash()).isEqualTo(prior(2, A).getCodeHash());
    assertThat(updated(3, A).getStorageRoot()).isEqualTo(prior(2, A).getStorageRoot());
    assertThat(updated(3, B).getBalance()).isEqualTo(Wei.of(7));
  }

  @Test
  public void emptyResultWhenNothingChanged() {
    trieLog(2, layer());
    trieLog(3, layer());

    assertThat(modified(byNumber, number(1), number(3))).isEmpty();
  }

  @Test
  public void startBlockMustBeBeforeEndBlock() {
    assertThat(errorMessage(byNumber.response(request(number(3), number(3)))))
        .isEqualTo("start block height (3) must be less than end block height (3)");
    assertThat(errorMessage(byNumber.response(request(number(4), number(2)))))
        .isEqualTo("start block height (4) must be less than end block height (2)");
    assertThat(errorMessage(byHash.response(request(hash(4), hash(2)))))
        .isEqualTo("start block height (4) must be less than end block height (2)");
  }

  @Test
  public void unknownStartBlockIsAnError() {
    assertThat(errorMessage(byNumber.response(request(number(99), number(3)))))
        .isEqualTo("start block 63 not found");
    assertThat(errorMessage(byHash.response(request(Hash.ZERO.toString(), hash(3)))))
        .isEqualTo(
            "start block 0000000000000000000000000000000000000000000000000000000000000000 not found");
  }

  @Test
  public void unknownEndBlockIsAnError() {
    assertThat(errorMessage(byNumber.response(request(number(1), number(99)))))
        .isEqualTo("end block 99 not found");
  }

  @Test
  public void genesisHasNoParentToDiffAgainst() {
    assertThat(errorMessage(byNumber.response(request(number(0)))))
        .isEqualTo("block 0 has no parent");
  }

  @Test
  public void startBlockMustBeAnAncestorOfEndBlock() {
    final BlockHeader forked =
        new BlockHeaderTestFixture().number(1).parentHash(Hash.ZERO).buildHeader();
    when(blockchain.getBlockHeader(forked.getBlockHash())).thenReturn(Optional.of(forked));
    trieLog(2, layer());
    trieLog(3, layer());

    assertThat(errorMessage(byHash.response(request(forked.getBlockHash().toString(), hash(3)))))
        .isEqualTo("start block is not an ancestor of end block");
  }

  @Test
  public void missingTrieLogIsReportedAsUnavailableWorldState() {
    trieLog(3, layer().addAccountChange(A, account(0, 0), account(1, 0)));

    assertThat(errorType(byNumber.response(request(number(1), number(3)))))
        .isEqualTo(RpcErrorType.WORLD_STATE_UNAVAILABLE);
  }

  @Test
  public void rangeLongerThanTheTrieLogWindowIsRejected() {
    when(trieLogManager.getMaxLayersToLoad()).thenReturn(1L);

    assertThat(errorType(byNumber.response(request(number(1), number(3)))))
        .isEqualTo(RpcErrorType.EXCEEDS_RPC_MAX_BLOCK_RANGE);
  }

  @Test
  public void wrongNumberOfParamsIsRejected() {
    assertThat(errorType(byNumber.response(request()))).isEqualTo(RpcErrorType.INVALID_PARAM_COUNT);
    assertThat(errorType(byNumber.response(request(number(1), number(2), number(3)))))
        .isEqualTo(RpcErrorType.INVALID_PARAM_COUNT);
    assertThat(errorType(byHash.response(request()))).isEqualTo(RpcErrorType.INVALID_PARAM_COUNT);
  }

  @SuppressWarnings("unchecked")
  private List<String> modified(final JsonRpcMethod method, final Object... params) {
    final JsonRpcResponse response = method.response(request(params));
    assertThat(response).isInstanceOf(JsonRpcSuccessResponse.class);
    return (List<String>) ((JsonRpcSuccessResponse) response).getResult();
  }

  private JsonRpcRequestContext request(final Object... params) {
    return new JsonRpcRequestContext(
        new JsonRpcRequest("2.0", "debug_getModifiedAccounts", params));
  }

  private void trieLog(final int block, final TrieLogLayer layer) {
    layers.put(block, layer);
    when(trieLogManager.getTrieLogLayer(headers.get(block).getBlockHash()))
        .thenReturn(Optional.<TrieLog>of(layer));
  }

  /**
   * The account as it stood before the given block, as the trie log the method reads records it.
   */
  private AccountValue prior(final int block, final Address address) {
    return accountChange(block, address).getPrior();
  }

  /** The account as it stands after the given block. */
  private AccountValue updated(final int block, final Address address) {
    return accountChange(block, address).getUpdated();
  }

  private PathBasedValue<AccountValue> accountChange(final int block, final Address address) {
    final PathBasedValue<AccountValue> change = layers.get(block).getAccountChanges().get(address);
    assertThat(change).describedAs("account change for %s in block %d", address, block).isNotNull();
    return change;
  }

  private TrieLogLayer layer() {
    return new TrieLogLayer();
  }

  private String number(final long block) {
    return "0x" + Long.toHexString(block);
  }

  private String hash(final int block) {
    return headers.get(block).getBlockHash().toString();
  }

  private static AccountValue account(final long nonce, final long balance) {
    return account(nonce, balance, Hash.EMPTY_TRIE_HASH, Hash.EMPTY);
  }

  private static AccountValue account(
      final long nonce, final long balance, final Hash storageRoot, final Hash codeHash) {
    return new PmtStateTrieAccountValue(nonce, Wei.of(balance), storageRoot, codeHash);
  }

  private static String errorMessage(final JsonRpcResponse response) {
    assertThat(response).isInstanceOf(JsonRpcErrorResponse.class);
    final JsonRpcErrorResponse error = (JsonRpcErrorResponse) response;
    assertThat(error.getError().getCode()).isEqualTo(-32000);
    return error.getError().getMessage();
  }

  private static RpcErrorType errorType(final JsonRpcResponse response) {
    assertThat(response).isInstanceOf(JsonRpcErrorResponse.class);
    return ((JsonRpcErrorResponse) response).getErrorType();
  }
}
