package com.ms.silverking.cloud.dht.daemon;

import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;

import com.google.common.collect.ImmutableSet;
import com.ms.silverking.cloud.dht.SecondaryTarget;
import com.ms.silverking.cloud.dht.common.OpResult;
import com.ms.silverking.cloud.dht.daemon.storage.convergence.ConvergencePoint;
import com.ms.silverking.cloud.dht.daemon.storage.convergence.InvalidTransitionException;
import com.ms.silverking.cloud.dht.daemon.storage.convergence.RingID;
import com.ms.silverking.cloud.dht.daemon.storage.convergence.RingIDAndVersionPair;
import com.ms.silverking.cloud.dht.daemon.storage.convergence.RingState;
import com.ms.silverking.cloud.dht.meta.DHTMetaUpdate;
import com.ms.silverking.cloud.dht.meta.RingHealthZK;
import com.ms.silverking.cloud.dht.net.ExclusionSetAddressStatusProvider;
import com.ms.silverking.cloud.dht.net.SecondaryTargetSerializer;
import com.ms.silverking.cloud.meta.ExclusionSet;
import com.ms.silverking.cloud.meta.ExclusionZK;
import com.ms.silverking.cloud.meta.MetaClient;
import com.ms.silverking.cloud.meta.ServerSetExtensionZK;
import com.ms.silverking.cloud.meta.ValueListener;
import com.ms.silverking.cloud.meta.ValueWatcher;
import com.ms.silverking.cloud.meta.VersionListener;
import com.ms.silverking.cloud.meta.VersionWatcher;
import com.ms.silverking.cloud.storagepolicy.StoragePolicyGroup;
import com.ms.silverking.cloud.topology.Node;
import com.ms.silverking.cloud.topology.NodeClass;
import com.ms.silverking.cloud.toporing.InstantiatedRingTree;
import com.ms.silverking.cloud.toporing.ResolvedReplicaMap;
import com.ms.silverking.cloud.toporing.RingTree;
import com.ms.silverking.cloud.toporing.RingTreeBuilder;
import com.ms.silverking.cloud.toporing.meta.RingConfiguration;
import org.apache.zookeeper.KeeperException;
import com.ms.silverking.collection.Triple;
import com.ms.silverking.log.Log;
import com.ms.silverking.net.IPAndPort;
import com.ms.silverking.thread.lwt.util.Broadcaster;
import org.apache.zookeeper.data.Stat;

/**
 * Tracks state used during transition from one ring to another,
 * and coordinates the transition across replicas.
 */
public class RingMapState2 {
  private final IPAndPort nodeID;
  private final long dhtConfigVersion;
  private final RingIDAndVersionPair ringIDAndVersionPair;
  private final Triple<String, Long, Long> ringNameAndVersionPair;
  private final InstantiatedRingTree rawRingTree;
  private RingTree ringTreeMinusExclusions;
  private ResolvedReplicaMap rawResolvedReplicaMap;
  private ResolvedReplicaMap resolvedReplicaMapMinusExclusions;
  private final ExclusionWatcher exclusionWatcher;
  private final HealthWatcher healthWatcher;
  private final ConvergencePoint cp;
  private volatile ExclusionSet curInstanceExclusionSet;
  private volatile ExclusionSet curExclusionSet;
  private volatile ExclusionSet curUnionExclusionSet;
  private volatile RingState ringState;
  private TransitionReplicaSources writeTargets;
  private TransitionReplicaSources readTargets;
  private final RingConfiguration ringConfig;
  private final com.ms.silverking.cloud.dht.meta.MetaClient dhtMC;
  private ExclusionSetAddressStatusProvider exclusionSetAddressStatusProvider;
  private SelfExclusionResponder selfExclusionResponder;
  private Broadcaster<Triple<Set<IPAndPort>,Set<IPAndPort>,Set<IPAndPort>>>  exclusionChangeBroadcaster;

  /*
   * secondarySets are used to specify subsets of secondary nodes within
   * a topology. This is useful for clients to specify a set of secondary replicas
   * that should be eagerly written to.
   *
   * PutOptions.eagerSecondaryTargets contains a list of definitions.
   * Current types for inclusion in PutOptions.eagerSecondaryTargets list are:
   *  "NodeID=10.198.1.1:7777" - specifies a single node
   *  "NodeID="Rack:zz123" - specifies all servers within a rack
   *  "AncestorClass=Region" - specifies all servers within a region
   *
   *  The secondarySets map maps definitions to sets. We don't bother
   *  to combine equivalent entries for now. Just naively map the
   *  definition to the corresponding set.
   */
  private AtomicReference<ConcurrentMap<String, Set<IPAndPort>>> secondarySetsRef;
  private static final String nodeIDSpec = "NodeID";
  private static final String ancestorClassSpec = "AncestorClass";

  private static final int exclusionCheckInitialIntervalMillis = 0;
  private static final int exclusionCheckIntervalMillis = 1 * 60 * 1000;
  private static final int ringHealthCheckIntervalMillis = 20 * 1000;

  private static final boolean verboseStateTransition = true;
  static final boolean debug = true;

  private static final boolean ignoreReaddition = false;    // Previously, we ignored all re-addition.
  // Leave capability to go back to that, but turn off.

  private static volatile boolean localNodeIsExcluded = false;

  private static void setLocalNodeIsExcluded(boolean _localNodeIsExcluded) {
    localNodeIsExcluded = _localNodeIsExcluded;
    Log.warningAsyncf("localNodeIsExcluded %s", localNodeIsExcluded);
  }

  public static boolean localNodeIsExcluded() {
    return localNodeIsExcluded;
  }

  RingMapState2(IPAndPort nodeID, DHTMetaUpdate dhtMetaUpdate, RingID ringID, StoragePolicyGroup storagePolicyGroup,
      com.ms.silverking.cloud.toporing.meta.MetaClient ringMC, ExclusionSet exclusionSet,
      com.ms.silverking.cloud.dht.meta.MetaClient dhtMC, SelfExclusionResponder responder,
      Broadcaster<Triple<Set<IPAndPort>,Set<IPAndPort>,Set<IPAndPort>>> exclusionChangeBroadcaster) {
    this.nodeID = nodeID;
    dhtConfigVersion = dhtMetaUpdate.getDHTConfig().getZKID();
    ringConfig = dhtMetaUpdate.getNamedRingConfiguration().getRingConfiguration();
    this.rawRingTree = dhtMetaUpdate.getRingTree();
    this.ringIDAndVersionPair = new RingIDAndVersionPair(ringID, rawRingTree.getRingVersionPair());
    ringNameAndVersionPair = Triple.of(dhtMetaUpdate.getDHTConfig().getRingName(), rawRingTree.getRingVersionPair());
    this.dhtMC = dhtMC;
    this.selfExclusionResponder = responder;
    this.exclusionChangeBroadcaster = exclusionChangeBroadcaster;

    curInstanceExclusionSet = ExclusionSet.emptyExclusionSet(0);
    curExclusionSet = ExclusionSet.emptyExclusionSet(0);

    secondarySetsRef = new AtomicReference();

    cp = new ConvergencePoint(dhtConfigVersion, ringIDAndVersionPair, rawRingTree.getRingCreationTime());

    writeTargets = TransitionReplicaSources.OLD;
    readTargets = TransitionReplicaSources.OLD;

    rawResolvedReplicaMap = rawRingTree.getResolvedMap(ringConfig.getRingParentName(),
        ReplicaPrioritizerHolder.getInstance(nodeID));
    try {
      readInitialExclusions(ringMC.createCloudMC());
    } catch (KeeperException | IOException e) {
      Log.logErrorWarning(e);
      throw new RuntimeException(e);
    }

    try {
      exclusionWatcher = new ExclusionWatcher(storagePolicyGroup, ringConfig, ringMC.createCloudMC());
    } catch (Exception e) {
      throw new RuntimeException("Exception creating ExclusionWatcher", e);
    }
    try {
      healthWatcher = new HealthWatcher(dhtMC);
    } catch (Exception e) {
      throw new RuntimeException("Exception creating ExclusionWatcher", e);
    }
  }
  
  private void readInitialExclusions(MetaClient mc) throws KeeperException {
    ExclusionZK exclusionZK;
    ExclusionSet instanceExclusionSet;
    ExclusionSet exclusionSet;
    RingTree newRingTree;
    ResolvedReplicaMap newResolvedReplicaMap;

    Log.warning("RingMapState reading initial exclusions");
    exclusionZK = new ExclusionZK(mc);
    try {
      exclusionSet = exclusionZK.readLatestFromZK();
    } catch (Exception e) {
      Log.logErrorWarning(e);
      Log.warning("No ExclusionSet found. Using empty set.");
      exclusionSet = ExclusionSet.emptyExclusionSet(0);
    }
    curExclusionSet = exclusionSet;
    Log.warning("curExclusionSet initialized:\n", curExclusionSet);

    try {
      instanceExclusionSet = new ExclusionSet(
          new ServerSetExtensionZK(dhtMC, dhtMC.getMetaPaths().getInstanceExclusionsPath()).readLatestFromZK());
    } catch (Exception e) {
      Log.logErrorWarning(e);
      Log.warning("No instance ExclusionSet found. Using empty set.");
      instanceExclusionSet = ExclusionSet.emptyExclusionSet(0);
    }
    curInstanceExclusionSet = instanceExclusionSet;
    Log.warning("curInstanceExclusionSet initialized:\n", curInstanceExclusionSet);

    curUnionExclusionSet = ExclusionSet.union(curExclusionSet, curInstanceExclusionSet);
    Log.warningf("curUnionExclusionSet initialized: %s", curUnionExclusionSet);

    try {
      newRingTree = RingTreeBuilder.removeExcludedNodes(rawRingTree, curUnionExclusionSet);
    } catch (Exception e) {
      Log.logErrorWarning(e);
      throw new RuntimeException(e);
    }
    newResolvedReplicaMap = newRingTree.getResolvedMap(ringConfig.getRingParentName(),
        ReplicaPrioritizerHolder.getInstance(nodeID));
    ringTreeMinusExclusions = newRingTree;
    resolvedReplicaMapMinusExclusions = newResolvedReplicaMap;
    if (Log.levelMet(Level.INFO)) {
      System.out.println("\tResolved Map");
      resolvedReplicaMapMinusExclusions.display();
    }

    Log.warning("RingMapState done reading initial exclusions");
  }

  public void discard() {
    synchronized (this) {
      if (exclusionWatcher != null) {
        exclusionWatcher.stop();
      }
      if (healthWatcher != null) {
        healthWatcher.stop();
      }
    }
  }

  public void abandonConvergence() {
    synchronized (this) {
      Log.warningf("RingMapState.abandonConvergence() %s", ringIDAndVersionPair);
      if (!ringState.isFinal()) {
        Log.warning("Notifying abandoned ", ringIDAndVersionPair);
        transitionToState(RingState.ABANDONED);
        this.notifyAll();
      }
    }
  }

  public OpResult setState(RingState state) {
    try {
      transitionToState(state);
      return OpResult.SUCCEEDED;
    } catch (InvalidTransitionException ite) {
      Log.logErrorWarning(ite);
      return OpResult.ERROR;
    }
  }

  private void transitionToState(RingState newRingState) {
    if (verboseStateTransition) {
      Log.warning("transitionToState: " + newRingState);
    }
    if (ringState == null || ringState.isValidTransition(newRingState)) {
      ringState = newRingState;
      localStateActions(newRingState);
    } else {
      throw new InvalidTransitionException(ringState, newRingState);
    }
  }

  private void localStateActions(RingState state) {
    switch (state) {
    case INITIAL:
      /*
       * Write to both old/new replicas
       * Read from old
       */
      writeTargets = TransitionReplicaSources.OLD_AND_NEW;
      break;
    case READY_FOR_CONVERGENCE_1:
      break;
    case READY_FOR_CONVERGENCE_2:
      break;
    case LOCAL_CONVERGENCE_COMPLETE_1:
      break;
    case ALL_CONVERGENCE_COMPLETE_1:
      /*
       * When all have converged
       * Quit reading from old
       */
      readTargets = TransitionReplicaSources.NEW;
      break;
    case ALL_CONVERGENCE_COMPLETE_2:
      /*
       * When all have quit reading from old (including passive)
       * Quit writing to old
       */
      writeTargets = TransitionReplicaSources.NEW;
      break;
    case CLOSED:
      break;
    case ABANDONED:
      break;
    default:
      throw new RuntimeException("panic");
    }
  }
    
  public TransitionReplicaSources getReplicaSources(RingOwnerQueryOpType opType) {
    switch (opType) {
    case Write:
      return writeTargets;
    case Read:
      return readTargets;
    default:
      throw new RuntimeException("panic");
    }
  }

  public TransitionReplicaSources getWriteTargets() {
    return writeTargets;
  }

  public TransitionReplicaSources getReadTargets() {
    return readTargets;
  }

  RingTree getRingTreeMinusExclusions() {
    return ringTreeMinusExclusions;
  }

  public ResolvedReplicaMap getResolvedReplicaMap() {
    return resolvedReplicaMapMinusExclusions;
  }

  public ResolvedReplicaMap getResolvedReplicaMap(boolean includeExcludedNodes) {
    return includeExcludedNodes ? rawResolvedReplicaMap : resolvedReplicaMapMinusExclusions;
  }

  ConvergencePoint getConvergencePoint() {
    return cp;
  }

  ExclusionSet getCurrentExclusionSet() {
    return ExclusionSet.union(curExclusionSet, curInstanceExclusionSet);
  }

  ExclusionSet getInstanceExclusionSet() {
    return curInstanceExclusionSet;
  }

  RingHealth getRingHealth() {
    return healthWatcher.getRingHealth();
  }

  class HealthWatcher implements ValueListener {
    private final ValueWatcher valueWatcher;
    private RingHealth ringHealth;

    HealthWatcher(com.ms.silverking.cloud.dht.meta.MetaClient dhtMC) throws KeeperException {
      RingHealthZK ringHealthZK;

      ringHealthZK = new RingHealthZK(dhtMC, ringNameAndVersionPair);
      valueWatcher = new ValueWatcher(dhtMC, ringHealthZK.getRingInstanceHealthPath(), this,
          ringHealthCheckIntervalMillis);
    }

    public void stop() { // FIXME - ensure that this is used
      valueWatcher.stop();
    }

    RingHealth getRingHealth() {
      Log.warningf("Getting ring health %s", ringHealth);
      return ringHealth;
    }

    @Override
    public void newValue(String basePath, byte[] value, Stat stat) {
      RingHealth _ringHealth;

      _ringHealth = RingHealth.valueOf(new String(value));
      if (_ringHealth != null) {
        if (ringHealth != _ringHealth) {
          Log.warningf("New ring health %s", ringHealth);
        }
        ringHealth = _ringHealth;
      }
    }
  }

  class ExclusionWatcher implements VersionListener {
    private final StoragePolicyGroup storagePolicyGroup;
    private final RingConfiguration ringConfig;
    private final ExclusionZK exclusionZK;
    private final VersionWatcher versionWatcher;
    private final VersionWatcher dhtVersionWatcher;

    ExclusionWatcher(StoragePolicyGroup storagePolicyGroup, RingConfiguration ringConfig, MetaClient mc) {
      this.storagePolicyGroup = storagePolicyGroup;
      this.ringConfig = ringConfig;
      try {
        exclusionZK = new ExclusionZK(mc);
      } catch (KeeperException ke) {
        throw new RuntimeException(ke);
      }
      versionWatcher = new VersionWatcher(mc, mc.getMetaPaths().getExclusionsPath(), this, exclusionCheckIntervalMillis,
          exclusionCheckInitialIntervalMillis);
      dhtVersionWatcher = new VersionWatcher(dhtMC, dhtMC.getMetaPaths().getInstanceExclusionsPath(), this,
          exclusionCheckIntervalMillis, exclusionCheckInitialIntervalMillis);
    }

    public void stop() { // FIXME - ensure that this is used
      versionWatcher.stop();
      dhtVersionWatcher.stop();
    }

    /*
     * Temporary approach for handling server failure:
     * simply remove failed servers and recompute the map.
     *
     * Not using this temporary approach currently. New approach is to wait
     * for the real map.
     */
    @Override
    public void newVersion(String basePath, long version) {
      try {
        RingTree newRingTree;
        ResolvedReplicaMap newResolvedReplicaMap;

        // FIXME - think about whether we want to use this
        // or just wait for a changed ring

        // Current is die only logic, allow for other

        // ExclusionSet has changed
        Log.warningf("ExclusionSet change detected: %s", ringIDAndVersionPair);
        // Read new exclusion set
        try {
          ExclusionSet candidateExclusionSet;
          boolean localNodeIsExcludedInCandidateSet;
          ExclusionSet newlyExcludedServers;
          ExclusionSet newlyIncludedServers;

          if (!basePath.contains(dhtMC.getMetaPaths().getInstanceExclusionsPath())) {
            ExclusionSet exclusionSet;

            // This is a server exclusion set change
            exclusionSet = exclusionZK.readFromZK(version, null);
            if (ignoreReaddition) {
              curExclusionSet = ExclusionSet.union(exclusionSet, curExclusionSet);
            } else {
              curExclusionSet = exclusionSet;
            }
            Log.warning("ExclusionSet change detected/merged with old:\n", exclusionSet);
          } else {
            ExclusionSet instanceExclusionSet;

            // This is an instance exclusion set change
            try {
              instanceExclusionSet = new ExclusionSet(
                  new ServerSetExtensionZK(dhtMC, dhtMC.getMetaPaths().getInstanceExclusionsPath()).readFromZK(version,
                      null));
              if (ignoreReaddition) {
                curInstanceExclusionSet = ExclusionSet.union(instanceExclusionSet, curInstanceExclusionSet);
              } else {
                curInstanceExclusionSet = instanceExclusionSet;
              }
              Log.warning("Instance ExclusionSet change detected/merged with old:\n", instanceExclusionSet);
            } catch (Exception e) {
              Log.warning("Failed to read ExclusionSet due to exception ", e);
            }
          }

          candidateExclusionSet = ExclusionSet.union(curExclusionSet, curInstanceExclusionSet);

          if (curUnionExclusionSet.equals(candidateExclusionSet)) {
            Log.warning("Ignoring update due to curUnionExclusionSet.equals(candidateExclusionSet)");
            return;
          }
          newlyExcludedServers = ExclusionSet.difference(candidateExclusionSet, curUnionExclusionSet);
          newlyIncludedServers = ExclusionSet.difference(curUnionExclusionSet, candidateExclusionSet);
          curUnionExclusionSet = candidateExclusionSet;
          if (exclusionChangeBroadcaster != null) {
            exclusionChangeBroadcaster.notifyListeners(
                new Triple<>(curUnionExclusionSet.asIPAndPortSet(DHTNode.getDhtPort()),
                newlyExcludedServers.asIPAndPortSet(DHTNode.getDhtPort()),
                newlyIncludedServers.asIPAndPortSet(DHTNode.getDhtPort())));
          }

          localNodeIsExcludedInCandidateSet = candidateExclusionSet.contains(nodeID.getIPAsString());
          if (selfExclusionResponder != null) {
            //Node wasn't excluded but it is now
            if (!localNodeIsExcluded() && localNodeIsExcludedInCandidateSet) {
              selfExclusionResponder.onExclusion();
            } else {
              Log.warningf("SelfExclusionResponder ignored event as excluded before: %s now: %s", localNodeIsExcluded(),
                  localNodeIsExcludedInCandidateSet);
            }
          } else {
            Log.warning("SelfExclusionResponder is disabled");
          }

          // Small race here between the value of curUnionExclusionSet and setLocalNodeIsExcluded()
          // and in StorageModule.liveReap() between the state of the same two. We live with this
          // for now as the impact should be rare and limited.
          setLocalNodeIsExcluded(localNodeIsExcludedInCandidateSet);
          if (exclusionSetAddressStatusProvider != null) {
            exclusionSetAddressStatusProvider.setExclusionSet(curUnionExclusionSet);
          }
          Log.warningf("curUnionExclusionSet updated: %s", curUnionExclusionSet);

          // Compute the new ringTree
          newRingTree = RingTreeBuilder.removeExcludedNodes(rawRingTree, curUnionExclusionSet);
        } catch (Exception e) {
          Log.logErrorWarning(e);
          throw new RuntimeException(e);
        }

        newResolvedReplicaMap = newRingTree.getResolvedMap(ringConfig.getRingParentName(),
            ReplicaPrioritizerHolder.getInstance(nodeID));
        ringTreeMinusExclusions = newRingTree;
        resolvedReplicaMapMinusExclusions = newResolvedReplicaMap;
        if (Log.levelMet(Level.INFO)) {
          System.out.println("\tResolved Map");
          resolvedReplicaMapMinusExclusions.display();
        }
      } finally {
        Log.warningf("Signaling exclusionSetInitialized: %s", ringIDAndVersionPair);
      }
    }
  }

  ///////////////

  private Set<IPAndPort> nodeListToIPAndPortSet(List<Node> replicaNodes) {
    ImmutableSet.Builder<IPAndPort> replicaSet;

    replicaSet = ImmutableSet.builder();
    for (Node replicaNode : replicaNodes) {
      if (!replicaNode.getNodeClass().equals(NodeClass.server)) {
        throw new RuntimeException("Unexpected non-server node class: " + replicaNode);
      }
      replicaSet.add(new IPAndPort(replicaNode.getIDString(), nodeID.getPort()));
    }
    return replicaSet.build();
  }

  public Set<IPAndPort> getSecondarySet(Set<SecondaryTarget> secondaryTargets) {
    String secondarySetID;
    Set<IPAndPort> secondarySet;
    ConcurrentMap<String, Set<IPAndPort>> secondarySets;

    secondarySets = secondarySetsRef.get();
    if (secondarySets == null) {
      secondarySetsRef.compareAndSet(null, new ConcurrentHashMap<String, Set<IPAndPort>>());
      secondarySets = secondarySetsRef.get();
    }
    // FUTURE - we already did this earlier, hold on to that copy rather than creating it again
    secondarySetID = new String(SecondaryTargetSerializer.serialize(secondaryTargets));
    secondarySet = secondarySets.get(secondarySetID);
    if (secondarySet == null) {
      secondarySet = createSecondarySet(secondaryTargets);
      secondarySets.putIfAbsent(secondarySetID, secondarySet);
    }
    return secondarySet;
  }

  private Set<IPAndPort> createSecondarySet(Set<SecondaryTarget> secondaryTargets) {
    ImmutableSet.Builder<IPAndPort> members;

    members = ImmutableSet.builder();
    for (SecondaryTarget target : secondaryTargets) {
      Set<IPAndPort> targetReplicas;

      switch (target.getType()) {
      case NodeID:
        targetReplicas = getTargetsByNodeID(target.getTarget());
        break;
      case AncestorClass:
        targetReplicas = getTargetsByAncestorClass(target.getTarget());
        break;
      default:
        throw new RuntimeException("Type not handled " + target.getType());
      }
      members.addAll(targetReplicas);
    }
    return members.build();
  }

  private Set<IPAndPort> getTargetsByAncestorClass(String nodeClassName) {
    Node ancestor;

    ancestor = rawRingTree.getTopology().getAncestor(nodeID.getIPAsString(), NodeClass.forName(nodeClassName));
    return nodeListToIPAndPortSet(ancestor.getAllDescendants(NodeClass.server));
  }

  private Set<IPAndPort> getTargetsByNodeID(String target) {
    Node node;

    node = rawRingTree.getTopology().getNodeByID(nodeID.getIPAsString());
    return nodeListToIPAndPortSet(node.getAllDescendants(NodeClass.server));
  }

  public void setExclusionSetAddressStatusProvider(
      ExclusionSetAddressStatusProvider exclusionSetAddressStatusProvider) {
    this.exclusionSetAddressStatusProvider = exclusionSetAddressStatusProvider;
  }

  public void setSelfExclusionResponder(SelfExclusionResponder responder) {
    this.selfExclusionResponder = responder;
  }
}