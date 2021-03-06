package com.ms.silverking.cloud.dht.daemon;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.google.common.collect.ImmutableList;
import com.ms.silverking.cloud.common.OwnerQueryMode;
import com.ms.silverking.cloud.dht.ForwardingMode;
import com.ms.silverking.cloud.dht.SecondaryTarget;
import com.ms.silverking.cloud.dht.ValueCreator;
import com.ms.silverking.cloud.dht.WaitMode;
import com.ms.silverking.cloud.dht.common.DHTConstants;
import com.ms.silverking.cloud.dht.common.DHTKey;
import com.ms.silverking.cloud.dht.common.InternalRetrievalOptions;
import com.ms.silverking.cloud.dht.common.MetaDataUtil;
import com.ms.silverking.cloud.dht.common.OpResult;
import com.ms.silverking.cloud.dht.common.ValueUtil;
import com.ms.silverking.cloud.dht.daemon.storage.StorageModule;
import com.ms.silverking.cloud.dht.daemon.storage.convergence.ConvergenceController2;
import com.ms.silverking.cloud.dht.daemon.storage.protocol.OpCommunicator;
import com.ms.silverking.cloud.dht.daemon.storage.protocol.RetrievalCommunicator;
import com.ms.silverking.cloud.dht.daemon.storage.protocol.RetrievalOperation;
import com.ms.silverking.cloud.dht.daemon.storage.protocol.RetrievalOperationContainer;
import com.ms.silverking.cloud.dht.daemon.storage.protocol.RetrievalProtocol;
import com.ms.silverking.cloud.dht.daemon.storage.protocol.RetrievalResult;
import com.ms.silverking.cloud.dht.daemon.storage.protocol.SecondaryReplicasUpdate;
import com.ms.silverking.cloud.dht.net.MessageGroup;
import com.ms.silverking.cloud.dht.net.MessageGroupRetrievalResponseEntry;
import com.ms.silverking.cloud.dht.net.ProtoKeyedMessageGroup;
import com.ms.silverking.cloud.dht.net.ProtoMessageGroup;
import com.ms.silverking.cloud.dht.net.ProtoRetrievalMessageGroup;
import com.ms.silverking.cloud.dht.net.ProtoValueMessageGroup;
import com.ms.silverking.cloud.dht.serverside.RetrieveCallback;
import com.ms.silverking.cloud.dht.trace.TracerFactory;
import com.ms.silverking.collection.CollectionUtil;
import com.ms.silverking.collection.Pair;
import com.ms.silverking.id.UUIDBase;
import com.ms.silverking.net.IPAndPort;
import com.ms.silverking.text.StringUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Retrieval executed on behalf of a client.
 * The internal RetrievalOperation implements the StorageProtocol specific behavior.
 */
public class ActiveProxyRetrieval extends ActiveProxyOperation<DHTKey, RetrievalResult>
    implements RetrievalOperationContainer, Waiter {
  private final InternalRetrievalOptions retrievalOptions;
  private final RetrievalOperation retrievalOperation;
  private final Set<SecondaryTarget> secondaryTargets;
  private RetrievalCommunicator rComm;
  private static Logger log = LoggerFactory.getLogger(ActiveProxyOperation.class);

  // FUTURE - combine common functionality between this class
  // and ActiveProxyPut

  private static final boolean debugWaitFor = false;

  private static final int resultListInitialSize = 10;

  ActiveProxyRetrieval(MessageGroup message,
                       ByteBuffer optionsByteBuffer,
                       MessageGroupConnectionProxy connection,
                       MessageModule messageModule,
                       StorageModule storage,
                       InternalRetrievalOptions retrievalOptions,
                       RetrievalProtocol retrievalProtocol,
                       long absDeadlineMillis) {
    super(connection, message, optionsByteBuffer, messageModule, absDeadlineMillis, true);
    this.retrievalOptions = getRetrievalOptions(message, retrievalOptions, getMaybeTraceID());
    this.retrievalOperation = retrievalProtocol.createRetrievalOperation(
        message.getDeadlineAbsMillis(messageModule.getAbsMillisTimeSource()),
        this,
        getForwardingMode(message));
    secondaryTargets = retrievalOptions.getRetrievalOptions().getSecondaryTargets();
    super.setOperation(retrievalOperation);
  }

  private static InternalRetrievalOptions getRetrievalOptions(MessageGroup message,
                                                              InternalRetrievalOptions retrievalOptions,
                                                              byte[] maybeTraceID) {
    return StorageModule.isDynamicNamespace(message.getContext())
           ? retrievalOptions.retrievalOptions(retrievalOptions.getRetrievalOptions()
                                                               .forwardingMode(ForwardingMode.DO_NOT_FORWARD))
                             .maybeTraceID(maybeTraceID)
           : retrievalOptions.maybeTraceID(maybeTraceID);
  }

  //protected OpVirtualCommunicator<DHTKey,RetrievalResult> createCommunicator() {
  //    return new RetrievalCommunicator();
  //}

  void startOperation() {
    if (!getOpResult().isComplete()) {
      if (forwardingMode.forwards()) {
        messageModule.addActiveRetrieval(uuid, this);
      } else {
        if (retrievalOptions.getWaitMode() == WaitMode.WAIT_FOR) {
          messageModule.addActiveRetrieval(uuid, this);
        }
      }
    }
    rComm = new RetrievalCommunicator();
    super.startOperation(rComm, message.getKeyIterator(), new RetrievalForwardCreator());
    //message = null; // free payload for GC
  }

  protected void processInitialMessageGroupEntry(DHTKey entry,
                                                 List<IPAndPort> primaryReplicas,
                                                 List<IPAndPort> secondaryReplicas,
                                                 OpCommunicator<DHTKey, RetrievalResult> comm) {
    List<IPAndPort> filteredSecondaryReplicas;

    filteredSecondaryReplicas = getFilteredSecondaryReplicas(entry,
                                                             primaryReplicas,
                                                             secondaryReplicas,
                                                             comm,
        secondaryTargets);
    super.processInitialMessageGroupEntry(entry, primaryReplicas, filteredSecondaryReplicas, comm);
  }

  protected <L extends DHTKey> void forwardGroupedEntries(Map<IPAndPort, List<L>> destEntryMap,
                                                          ByteBuffer optionsByteBuffer,
                                                          ForwardCreator<L> forwardCreator,
                                                          OpCommunicator<DHTKey, RetrievalResult> comm) {
    super.forwardGroupedEntries(destEntryMap, optionsByteBuffer, forwardCreator, comm);
  }

  private class RetrievalForwardCreator implements ForwardCreator<DHTKey> {
    @Override
    public MessageGroup createForward(List<DHTKey> destEntries,
                                      ByteBuffer optionsByteBuffer,
        byte[] traceIDInFinalReplica) {
      ProtoKeyedMessageGroup protoMG;

      protoMG = new ProtoRetrievalMessageGroup(uuid,
                                               namespace,
                                               retrievalOptions,
                                               originator,
                                               destEntries,
                                               messageModule.getAbsMillisTimeSource()
                                                            .relMillisRemaining(absDeadlineMillis),
                                               traceIDInFinalReplica);
      return protoMG.toMessageGroup();
    }
  }

  private void processResult(DHTKey entry, ByteBuffer result) {
    RetrievalResult retrievalResult;

    if (debug) {
      log.debug("localRetrieval: {}", entry);
      System.out.printf("result %s %s\n", result, StringUtil.byteBufferToHexString(result));
    }

    // FUTURE - THIS NEEDS TO GO THROUGH THE PROTOCOL
    // INSTEAD OF PROTOCOL SEMANTICS BEING HANDLED HERE

    log.debug("{}",entry);
    if (result != null && result != ValueUtil.corruptValue) {
      retrievalResult = new RetrievalResult(entry, OpResult.SUCCEEDED, result);
    } else {
      if (result != ValueUtil.corruptValue) {
        if (retrievalOptions.getWaitMode() != WaitMode.WAIT_FOR || messageModule.getReplicaList(getContext(),
                                                                                                entry,
                                                                                                OwnerQueryMode.Secondary,
                                                                                                RingOwnerQueryOpType.Read)
                                                                                .contains(localIPAndPort())) {
          retrievalResult = new RetrievalResult(entry, OpResult.NO_SUCH_VALUE, null);
        } else {
          retrievalResult = null;
        }
      } else {
        if (debug) {
          System.out.println("Returning corrupt result");
        }
        retrievalResult = new RetrievalResult(entry, OpResult.CORRUPT, null);
      }
    }
    // Complete operations are removed in bulk by MessageModule.Cleaner
    if (messageModule.getEnableMsgGroupTrace() && hasTraceID) {
      TracerFactory.getTracer().onLocalEnqueueRetrievalResult(maybeTraceID);
    }
    if (retrievalResult != null) {
      // retrievalOperation.update((DHTKey)entry, localIPAndPort(), retrievalResult, rComm);
      rComm.sendResult(retrievalResult);
    }
  }

  @Override
  protected void localOp(List<? extends DHTKey> destEntries, OpCommunicator<DHTKey, RetrievalResult> comm) {
    RetrieveCallback<Pair<DHTKey, ByteBuffer>, Void> callback;

    if (messageModule.getEnableMsgGroupTrace() && hasTraceID) {
      TracerFactory.getTracer().onLocalHandleRetrievalRequest(maybeTraceID);
    }

    callback = (Pair<DHTKey, ByteBuffer> data) -> {
      ByteBuffer result;
      DHTKey entry;

      entry = data.getV1();
      result = data.getV2();

      processResult(entry, result);
      return null;
    };

    getStorage().retrieve(getContext(), destEntries, // entry can act as a key
        getRetrievalOptions(), uuid, callback);

  }

  public void waitForTriggered(DHTKey key, ByteBuffer result) {
    RetrievalResult retrievalResult;

    if (result.position() > 0) {
      System.err.println(result);
      Thread.dumpStack();
      System.exit(-1);
    }
    //debugString.append(result.toString() +"\n");
    if (debugWaitFor) {
      System.out.println("waitForTriggered " + key);
    }
    retrievalResult = new RetrievalResult(key, OpResult.SUCCEEDED, result);
    retrievalOperation.update((DHTKey) key, localIPAndPort(), retrievalResult, rComm);
    // Complete operations are removed in bulk by MessageModule.Cleaner
  }

  public void relayWaitForResults() {
    if (debugWaitFor) {
      System.out.println("relayWaitForResults");
    }
    sendResults(rComm);
  }

  /////////////////////
  // Handle responses

  public OpResult handleRetrievalResponse(MessageGroup message, MessageGroupConnectionProxy connection) {
    RetrievalCommunicator rComm;
    Map<IPAndPort, List<DHTKey>> destEntryMap;

    rComm = new RetrievalCommunicator();
    if (debug) {
      System.out.println("handleRetrievalResponse");
    }
    for (MessageGroupRetrievalResponseEntry entry : message.getRetrievalResponseValueKeyIterator()) {
      IPAndPort replica;

      replica = new IPAndPort(message.getOriginator(), DHTNode.getDhtPort());
      if (debug) {
        System.out.println("replica: " + replica);
      }
      retrievalOperation.update(entry,
                                replica,
                                new RetrievalResult(entry, entry.getOpResult(), entry.getValue()),
          rComm);
    }
    sendResults(rComm);
    destEntryMap = rComm.takeReplicaMessageLists();
    if (destEntryMap != null) {
      forwardGroupedEntries(destEntryMap, optionsByteBuffer, new RetrievalForwardCreator(), rComm);
    }
    if (retrievalOptions.getRetrievalOptions().getUpdateSecondariesOnMiss()) {
      if (debug) {
        log.debug("ActiveProxyRetrieval calling sendSecondaryReplicasUpdates");
      }
      sendSecondaryReplicasUpdates(rComm);
    } else {
      if (debug) {
        log.debug("ActiveProxyRetrieval *no* sendSecondaryReplicasUpdates");
      }
    }
    return retrievalOperation.getOpResult();
  }
  
  @Override
  public OpResult exclusionsChanged(Set<IPAndPort> excludedReplicas, Set<IPAndPort> includedReplicas) {
    OpResult  exclusionResult;
    
    log.info("ActiveProxyRetrieval.exclusionsChanged");
    exclusionResult = replicasExcluded(excludedReplicas);
    if (exclusionResult.isComplete()) {
      return exclusionResult;
    } else {
      OpResult  inclusionResult;
      
      inclusionResult = replicasIncluded(includedReplicas);
      return inclusionResult;
    }
  }

  public OpResult replicasIncluded(Set<IPAndPort> includedReplicas) {
    RetrievalCommunicator rComm;
    Map<IPAndPort, List<DHTKey>> destEntryMap;
    
    if (log.isInfoEnabled()) {
      log.info("ActiveProxyRetrieval.replicasIncluded {}", CollectionUtil.toString(includedReplicas));
    }
    rComm = new RetrievalCommunicator();
    for (IPAndPort replica : includedReplicas) {
      replicaIncluded(replica, rComm);
    }
    sendResults(rComm);
    destEntryMap = rComm.takeReplicaMessageLists();
    if (destEntryMap != null) {
      forwardGroupedEntries(destEntryMap, optionsByteBuffer, new RetrievalForwardCreator(), rComm);
    }
    return retrievalOperation.getOpResult();
  }
  
  public void replicaIncluded(IPAndPort replica, RetrievalCommunicator rComm) {
    if (log.isInfoEnabled()) {
      log.info("ActiveProxyRetrieval.replicaIncluded {}", replica);
    }
    retrievalOperation.replicaIncluded(replica, rComm);
  }
  
  public OpResult replicasExcluded(Set<IPAndPort> excludedReplicas) {
    RetrievalCommunicator rComm;
    Map<IPAndPort, List<DHTKey>> destEntryMap;
    
    if (log.isInfoEnabled()) {
      log.info("ActiveProxyRetrieval.replicasExcluded {}", CollectionUtil.toString(excludedReplicas));
    }

    rComm = new RetrievalCommunicator();
    for (IPAndPort replica : excludedReplicas) {
      replicaExcluded(replica, rComm);
    }
    sendResults(rComm);
    destEntryMap = rComm.takeReplicaMessageLists();
    if (destEntryMap != null) {
      forwardGroupedEntries(destEntryMap, optionsByteBuffer, new RetrievalForwardCreator(), rComm);
    }
    return retrievalOperation.getOpResult();
  }
  
  public void replicaExcluded(IPAndPort replica, RetrievalCommunicator rComm) {
    if(log.isInfoEnabled()) {
      log.info("ActiveProxyRetrieval.replicaExcluded {}", replica);
	}

    retrievalOperation.replicaExcluded(replica, rComm);
  }
  
  private List<List<RetrievalResult>> createResultGroups(List<RetrievalResult> results) {
    if (results.size() == 1) {
      return ImmutableList.of(results);
    } else {
      List<List<RetrievalResult>> resultGroups;
      List<RetrievalResult> curGroup;
      int curGroupSize;

      resultGroups = new ArrayList<>(Math.min(results.size(), resultListInitialSize));
      curGroup = new ArrayList<>(Math.min(results.size(), resultListInitialSize));
      resultGroups.add(curGroup);
      curGroupSize = 0;
      for (int i = 0; i < results.size(); i++) {
        RetrievalResult result;

        result = results.get(i);
        if (curGroupSize != 0) {
          if (curGroupSize + result.getResultLength() > ProtoValueMessageGroup.maxValueBytesPerMessage) {
            curGroup = new ArrayList<>(Math.min(results.size() - i, resultListInitialSize));
            resultGroups.add(curGroup);
            curGroupSize = 0;
          }
        }
        curGroup.add(result);
        curGroupSize += result.getResultLength();
      }
      return resultGroups;
    }
  }

  /**
   * Send responses for all locally completed operations
   *
   * @param results
   */
  protected void sendResults(List<RetrievalResult> results) {
    if (debug) {
      log.debug("ActiveProxyRetrieval.sendresults() {}\n", results.size());
    }
        /*
        debugString.append("***\n");
        for (RetrievalResult result : results) {
            debugString.append(result.getValue() +"\n");
        }
        debugString.append("+++\n");
        */
    try {
      if (results.size() > 0) {
        ProtoValueMessageGroup pmg;
        byte[] _originator;
        List<List<RetrievalResult>> resultGroups;

        _originator = ConvergenceController2.isChecksumVersionConstraint(retrievalOptions.getVersionConstraint())
                      ? originator
                      : messageModule.getMessageGroupBase().getMyID();

        resultGroups = createResultGroups(results);
        for (List<RetrievalResult> resultGroup : resultGroups) {
          MessageGroup messageGroup;
          int groupLength;

          groupLength = RetrievalResult.totalResultLength(resultGroup);
          pmg = new ProtoValueMessageGroup(uuid,
                                           namespace,
                                           results.size(),
                                           groupLength,
                                           _originator,
                                           messageModule.getAbsMillisTimeSource().relMillisRemaining(absDeadlineMillis),
                                           maybeTraceID);
          for (RetrievalResult result : resultGroup) {
            DHTKey key;
            ByteBuffer value;

            if (debug) {
              log.debug("{}",result);
            }
            key = result.getKey();
            if (retrievalOptions.getWaitMode() != WaitMode.WAIT_FOR || result.getValue() == null) {
              value = result.getValue();
            } else {
              value = result.getValue().duplicate();
            }
            if (value == null) {
              pmg.addErrorCode(key, result.getResult());
            } else {
              pmg.addValue(key, value, result.getResultLength(), true);
            }
          }
          messageGroup = pmg.toMessageGroup();
          if (messageModule.getEnableMsgGroupTrace() && hasTraceID) {
            TracerFactory.getTracer().onBothDequeueAndAsyncSendRetrievalResult(maybeTraceID);
          }
          connection.sendAsynchronous(messageGroup,
              messageGroup.getDeadlineAbsMillis(messageModule.getAbsMillisTimeSource()));
        }
      }
            /*
        } catch (RuntimeException re) {
            // for debugging only
            re.printStackTrace();
            System.out.println("results.size() "+ results.size());
            for (RetrievalResult result : results) {
                System.out.println(result);
            }
            //rComm.displayDebug();
            System.exit(-1);
            */
    } catch (IOException ioe) {
      ioe.printStackTrace();
    }
  }

  protected void sendErrorResults() {
    List<RetrievalResult> results = new ArrayList<>();
    this.message.getKeyIterator().forEach(k -> {
      results.add(new RetrievalResult(k, OpResult.ERROR, null));
    });
    sendResults(results);
  }

  //////////////////////

  public Set<IPAndPort> checkForReplicaTimeouts(long curTimeMillis) {
    RetrievalCommunicator rComm;
    Map<IPAndPort, List<DHTKey>> destEntryMap;
    Set<IPAndPort> timedOutReplicas;

    if (debug) {
      log.debug("checkForReplicaTimeouts {} {}", uuid, getOpResult());
    }
    timedOutReplicas = new HashSet<>();
    rComm = new RetrievalCommunicator();
    timedOutReplicas.addAll(retrievalOperation.checkForInternalTimeouts(curTimeMillis, rComm));
    destEntryMap = rComm.takeReplicaMessageLists();
    if (destEntryMap != null) {
      if (debug) {
        log.debug("forwardGroupedEntries");
      }
      forwardGroupedEntries(destEntryMap, optionsByteBuffer, new RetrievalForwardCreator(), rComm);
    }
    return timedOutReplicas;
  }

  //////////////////////

  public InternalRetrievalOptions getRetrievalOptions() {
    return retrievalOptions;
  }

  public OpResult getOpResult() {
    return retrievalOperation.getOpResult();
  }

  /////////////////////////

  private ProtoValueMessageGroup createValueMessageForSecondaryReplicas(RetrievalResult result) {
    ProtoValueMessageGroup pmg;
    ByteBuffer buf;
    ValueCreator creator;
    int valueBytes;
    ByteBuffer value;
    int valueLength;

    buf = result.getValue();
    creator = MetaDataUtil.getCreator(buf, 0);
    valueBytes = MetaDataUtil.getStoredLength(buf, 0);
    valueLength = MetaDataUtil.getCompressedLength(buf, 0);

    value = (ByteBuffer) buf.duplicate().flip();
    if (debug) {
      log.debug("buf   {}\n", buf);
      log.debug("value {}\n", value);
      log.debug("valueBytes {} valueLength {}\n", valueBytes, valueLength);
    }
    // TODO: consider using a child of traceID for replica update
    pmg = new ProtoValueMessageGroup(new UUIDBase(),
                                     message.getContext(),
                                     1,
                                     valueBytes,
                                     creator.getBytes(),
                                     DHTConstants.defaultSecondaryReplicaUpdateTimeoutMillis,
                                     maybeTraceID);
    pmg.addValue(result.getKey(), value, valueLength, true);
    return pmg;
  }

  /////////////////////////

  private void sendSecondaryReplicasUpdates(RetrievalCommunicator rComm) {
    List<SecondaryReplicasUpdate> secondaryReplicasUpdates;

    secondaryReplicasUpdates = rComm.getSecondaryReplicasUpdates();
    if (secondaryReplicasUpdates != null && secondaryReplicasUpdates.size() > 0) {
      for (SecondaryReplicasUpdate secondaryReplicasUpdate : secondaryReplicasUpdates) {
        sendSecondaryReplicasUpdate(secondaryReplicasUpdate);
      }
    }
  }

  private void sendSecondaryReplicasUpdate(SecondaryReplicasUpdate secondaryReplicasUpdate) {
    RetrievalResult result;

    result = secondaryReplicasUpdate.getResult();
    for (IPAndPort replica : secondaryReplicasUpdate.getReplicas()) {
      ProtoMessageGroup pmg;

      if (debug) {
        log.debug("ActiveProxyRetrieval sending secondary replicas update to {}", replica);
      }
      //pmg = createPutForSecondaryReplicas(result);
      pmg = createValueMessageForSecondaryReplicas(result);
      messageModule.getMessageGroupBase().send(pmg.toMessageGroup(), replica);
    }
  }
}
