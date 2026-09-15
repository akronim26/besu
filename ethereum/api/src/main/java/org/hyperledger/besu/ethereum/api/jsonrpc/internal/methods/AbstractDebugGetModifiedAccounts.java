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

import org.hyperledger.besu.datatypes.AccountValue;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.JsonRpcRequestContext;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.response.JsonRpcError;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.response.JsonRpcErrorResponse;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.response.JsonRpcResponse;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.response.JsonRpcSuccessResponse;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.response.RpcErrorType;
import org.hyperledger.besu.ethereum.api.query.BlockchainQueries;
import org.hyperledger.besu.ethereum.chain.Blockchain;
import org.hyperledger.besu.ethereum.core.BlockHeader;
import org.hyperledger.besu.ethereum.trie.pathbased.common.provider.PathBasedWorldStateProvider;
import org.hyperledger.besu.ethereum.trie.pathbased.common.trielog.TrieLogManager;
import org.hyperledger.besu.ethereum.trie.pathbased.common.worldview.accumulator.PathBasedValue;
import org.hyperledger.besu.plugin.services.trielogs.TrieLog;
import org.hyperledger.besu.plugin.services.trielogs.TrieLog.LogTuple;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public abstract class AbstractDebugGetModifiedAccounts implements JsonRpcMethod {
  private static final int SERVER_ERROR_CODE = -32000;

  protected final BlockchainQueries blockchainQueries;
  protected final TrieLogManager trieLogManager;

  protected AbstractDebugGetModifiedAccounts(
      final BlockchainQueries blockchainQueries, final TrieLogManager trieLogManager) {
    this.blockchainQueries = blockchainQueries;
    this.trieLogManager = trieLogManager;
  }

  @Override
  public abstract String getName();

  protected abstract Optional<BlockHeader> findHeader(
      final JsonRpcRequestContext request, final int index);

  protected abstract String blockId(final JsonRpcRequestContext request, final int index);

  @Override
  public final JsonRpcResponse response(final JsonRpcRequestContext request) {
    final int paramLength = request.getRequest().getParamLength();
    if (paramLength != 1 && paramLength != 2) {
      return new JsonRpcErrorResponse(
          request.getRequest().getId(), RpcErrorType.INVALID_PARAM_COUNT);
    }

    if (blockchainQueries.getWorldStateArchive() instanceof PathBasedWorldStateProvider provider
        && provider.getWorldStateSharedSpec().isTrieDisabled()) {
      return error(request, "modified accounts unavailable: world state trie is disabled");
    }

    final Optional<BlockHeader> maybeFirstHeader = findHeader(request, 0);
    if (maybeFirstHeader.isEmpty()) {
      return error(request, "start block " + blockId(request, 0) + " not found");
    }

    final Blockchain blockchain = blockchainQueries.getBlockchain();
    final BlockHeader startHeader;
    final BlockHeader endHeader;
    if (paramLength == 1) {
      endHeader = maybeFirstHeader.get();
      final Optional<BlockHeader> maybeParent =
          blockchain.getBlockHeader(endHeader.getParentHash());
      if (maybeParent.isEmpty()) {
        return error(
            request, "block " + Long.toHexString(endHeader.getNumber()) + " has no parent");
      }
      startHeader = maybeParent.get();
    } else {
      startHeader = maybeFirstHeader.get();
      final Optional<BlockHeader> maybeEndHeader = findHeader(request, 1);
      if (maybeEndHeader.isEmpty()) {
        return error(request, "end block " + blockId(request, 1) + " not found");
      }
      endHeader = maybeEndHeader.get();
    }

    if (startHeader.getNumber() >= endHeader.getNumber()) {
      return error(
          request,
          String.format(
              "start block height (%d) must be less than end block height (%d)",
              startHeader.getNumber(), endHeader.getNumber()));
    }

    if (endHeader.getNumber() - startHeader.getNumber() > trieLogManager.getMaxLayersToLoad()) {
      return new JsonRpcErrorResponse(
          request.getRequest().getId(), RpcErrorType.EXCEEDS_RPC_MAX_BLOCK_RANGE);
    }

    return collectModifiedAccounts(request, blockchain, startHeader, endHeader);
  }

  private JsonRpcResponse collectModifiedAccounts(
      final JsonRpcRequestContext request,
      final Blockchain blockchain,
      final BlockHeader startHeader,
      final BlockHeader endHeader) {
    final Map<Address, PathBasedValue<AccountValue>> accounts = new HashMap<>();

    BlockHeader header = endHeader;
    while (header.getNumber() > startHeader.getNumber()) {
      final Optional<TrieLog> maybeTrieLog = trieLogManager.getTrieLogLayer(header.getBlockHash());
      if (maybeTrieLog.isEmpty()) {
        return new JsonRpcErrorResponse(
            request.getRequest().getId(), RpcErrorType.WORLD_STATE_UNAVAILABLE);
      }

      final Map<Address, LogTuple<AccountValue>> accountChanges =
          maybeTrieLog.get().getAccountChanges();
      accountChanges.forEach(
          (address, change) ->
              accounts
                  .computeIfAbsent(address, __ -> new PathBasedValue<>(null, change.getUpdated()))
                  .setPrior(change.getPrior()));

      final Optional<BlockHeader> maybeParent = blockchain.getBlockHeader(header.getParentHash());
      if (maybeParent.isEmpty()) {
        return new JsonRpcErrorResponse(
            request.getRequest().getId(), RpcErrorType.WORLD_STATE_UNAVAILABLE);
      }
      header = maybeParent.get();
    }

    if (!header.getBlockHash().equals(startHeader.getBlockHash())) {
      return error(request, "start block is not an ancestor of end block");
    }

    final List<String> modified =
        accounts.entrySet().stream()
            .filter(entry -> !entry.getValue().isUnchanged())
            .map(entry -> entry.getKey().toString())
            .sorted()
            .toList();

    return new JsonRpcSuccessResponse(request.getRequest().getId(), modified);
  }

  private static JsonRpcResponse error(final JsonRpcRequestContext request, final String message) {
    return new JsonRpcErrorResponse(
        request.getRequest().getId(), new JsonRpcError(SERVER_ERROR_CODE, message, null));
  }
}
