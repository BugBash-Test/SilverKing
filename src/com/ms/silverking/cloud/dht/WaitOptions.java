package com.ms.silverking.cloud.dht;

import java.util.Set;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableSet;
import com.ms.silverking.cloud.dht.client.OpTimeoutController;
import com.ms.silverking.cloud.dht.common.DHTConstants;
import com.ms.silverking.cloud.dht.trace.TraceIDProvider;
import com.ms.silverking.text.ObjectDefParser2;

/**
 * <p>Options for WaitFor operations. Specifies how long to wait, what percentage of
 * values to wait for before returning (default 100), and whether or not
 * to generate an exception if a timeout occurs.</p>
 * <p>
 * Note that for waitFor operations, OpTimeoutController controls the *internal* retries.
 * The timeoutSeconds controls the user-visible timeout.
 * Care should be take so that a retries continue indefinitely.
 * Most users should not override the OpTimeoutController
 * for this case.
 * </p>
 */
public final class WaitOptions extends RetrievalOptions {
  private final int timeoutSeconds;
  private final int threshold;
  private final TimeoutResponse timeoutResponse;

  /*
   * For waitFor operations, OpTimeoutController controls the *internal* retries.
   * as discussed in the class notes.
   */

  public static final int THRESHOLD_MIN = 0;
  public static final int THRESHOLD_MAX = 100;
  public static final int NO_TIMEOUT = Integer.MAX_VALUE;

  private static final WaitOptions template = new WaitOptions();

  private static final Set<String> exclusionFields = ImmutableSet.of("waitMode", "forwardingMode");

  static {
    ObjectDefParser2.addParserWithExclusions(template, exclusionFields);
  }

  private WaitOptions() {
    this(
        DHTConstants.standardWaitForTimeoutController,
        DHTConstants.noSecondaryTargets,
        RetrievalType.VALUE,
        VersionConstraint.defaultConstraint,
        NonExistenceResponse.defaultResponse,
        true,
        false,
        false,
        Integer.MAX_VALUE,
        WaitOptions.THRESHOLD_MAX,
        TimeoutResponse.defaultResponse);
  }

  ///
  /// FIXME - this is C++ only.
  /// This should be removed once C++ SKWaitOptions.cpp is using the other constructor below properly.
  ///

  /**
   * Construct fully-specified WaitOptions
   * Usage should be avoided; an instance should be obtained and modified from an enclosing environment.
   *
   * @param opTimeoutController     opTimeoutController to use for *internal* retries. See class notes.
   *                                of the requested values could be retrieved
   * @param secondaryTargets        constrains queried secondary replicas
   * @param retrievalType           what to retrieve (data, meta data, etc.)
   * @param versionConstraint       filter on the allowed versions
   * @param nonExistenceResponse    TODO
   * @param verifyChecksums         TODO
   * @param returnInvalidations     TODO
   * @param updateSecondariesOnMiss when true, secondary replicas queried in this operation will be updated on a miss
   * @param timeoutSeconds          return after timeoutSeconds if the values cannot be retrieved
   * @param threshold               return after a percentage of requested values are available
   * @param timeoutResponse         specifies whether or not to throw an exception when a timeout occurs before all
   */
  public WaitOptions(OpTimeoutController opTimeoutController,
                     Set<SecondaryTarget> secondaryTargets,
                     RetrievalType retrievalType,
                     VersionConstraint versionConstraint,
                     NonExistenceResponse nonExistenceResponse,
                     boolean verifyChecksums,
                     boolean returnInvalidations,
                     boolean updateSecondariesOnMiss,
                     int timeoutSeconds,
                     int threshold,
                     TimeoutResponse timeoutResponse) {
    super(
        opTimeoutController,
        secondaryTargets,
        retrievalType,
        WaitMode.WAIT_FOR,
        versionConstraint,
        nonExistenceResponse,
        verifyChecksums,
        returnInvalidations,
        ForwardingMode.FORWARD,
        updateSecondariesOnMiss,
        RetrievalOptions.noUserOptions,
        RetrievalOptions.noAuthorizationUser);
    Preconditions.checkArgument(timeoutSeconds >= 0);
    Preconditions.checkArgument(threshold >= 0);
    this.timeoutSeconds = timeoutSeconds;
    this.threshold = threshold;
    this.timeoutResponse = timeoutResponse;
  }

  public WaitOptions(OpTimeoutController opTimeoutController,
                     Set<SecondaryTarget> secondaryTargets,
                     RetrievalType retrievalType,
                     VersionConstraint versionConstraint,
                     NonExistenceResponse nonExistenceResponse,
                     boolean verifyChecksums,
                     boolean returnInvalidations,
                     boolean updateSecondariesOnMiss,
                     byte[] userOptions,
                     byte[] authorizationUser,
                     int timeoutSeconds,
                     int threshold,
                     TimeoutResponse timeoutResponse) {
    this(
        opTimeoutController,
        secondaryTargets,
        DHTConstants.defaultTraceIDProvider,
        AllReplicasExcludedResponse.defaultResponse,
        retrievalType,
        versionConstraint,
        nonExistenceResponse,
        verifyChecksums,
        returnInvalidations,
        updateSecondariesOnMiss,
        userOptions,
        authorizationUser,
        timeoutSeconds,
        threshold,
        timeoutResponse);
  }

  /**
   * Construct fully-specified WaitOptions (for reflection)
   * Usage should be avoided; an instance should be obtained and modified from an enclosing environment.
   *
   * @param opTimeoutController     opTimeoutController to use for *internal* retries. See class notes.
   *                                of the requested values could be retrieved
   * @param secondaryTargets        constrains queried secondary replicas
   * @param traceIDProvider         trace provider for message group
   * @param allReplicasExcludedResponse TODO
   * @param retrievalType           what to retrieve (data, meta data, etc.)
   * @param versionConstraint       filter on the allowed versions
   * @param verifyChecksums
   * @param updateSecondariesOnMiss when true, secondary replicas queried in this operation will be updated on a miss
   * @param userOptions             specifies additional custom options from user
   * @param authorizationUser       specifies a user to be used for authorization plugins
   * @param timeoutSeconds          return after timeoutSeconds if the values cannot be retrieved
   * @param threshold               return after a percentage of requested values are available
   * @param timeoutResponse         specifies whether or not to throw an exception when a timeout occurs before all
   */
  public WaitOptions(OpTimeoutController opTimeoutController,
                     Set<SecondaryTarget> secondaryTargets,
                     TraceIDProvider traceIDProvider,
                     AllReplicasExcludedResponse allReplicasExcludedResponse,
                     RetrievalType retrievalType,
                     VersionConstraint versionConstraint,
                     NonExistenceResponse nonExistenceResponse,
                     boolean verifyChecksums,
                     boolean returnInvalidations,
                     boolean updateSecondariesOnMiss,
                     byte[] userOptions,
                     byte[] authorizationUser,
                     int timeoutSeconds,
                     int threshold,
                     TimeoutResponse timeoutResponse) {
    super(
        opTimeoutController,
        secondaryTargets,
        traceIDProvider,
        allReplicasExcludedResponse,
        retrievalType,
        WaitMode.WAIT_FOR,
        versionConstraint,
        nonExistenceResponse,
        verifyChecksums,
        returnInvalidations,
        ForwardingMode.FORWARD,
        updateSecondariesOnMiss,
        userOptions,
        authorizationUser);
    Preconditions.checkArgument(timeoutSeconds >= 0);
    Preconditions.checkArgument(threshold >= 0);
    this.timeoutSeconds = timeoutSeconds;
    this.threshold = threshold;
    this.timeoutResponse = timeoutResponse;
  }

  /**
   * Return a WaitOptions instance like this instance, but with a new OpTimeoutController.
   * For waitFor operations, OpTimeoutController controls the *internal* retries.
   * The timeoutSeconds controls the user-visible timeout.
   * Care should be take so that a retries continue indefinitely.
   * Most users should not override the OpTimeoutController
   * for this case.
   *
   * @param opTimeoutController the new field value
   * @return the modified WaitOptions
   */
  public WaitOptions opTimeoutController(OpTimeoutController opTimeoutController) {
    return new WaitOptions(
        opTimeoutController,
        getSecondaryTargets(),
        getTraceIDProvider(),
        getAllReplicasExcludedResponse(),
        getRetrievalType(),
        getVersionConstraint(),
        getNonExistenceResponse(),
        getVerifyChecksums(),
        getReturnInvalidations(),
        getUpdateSecondariesOnMiss(),
        getUserOptions(),
        getAuthorizationUser(),
        timeoutSeconds,
        threshold,
        timeoutResponse);
  }

  /**
   * Return a WaitOptions instance like this instance, but with a new SecondaryTargets.
   *
   * @param secondaryTargets the new field value
   * @return the modified WaitOptions
   */
  public WaitOptions secondaryTargets(Set<SecondaryTarget> secondaryTargets) {
    return new WaitOptions(
        getOpTimeoutController(),
        secondaryTargets,
        getTraceIDProvider(),
        getAllReplicasExcludedResponse(),
        getRetrievalType(),
        getVersionConstraint(),
        getNonExistenceResponse(),
        getVerifyChecksums(),
        getReturnInvalidations(),
        getUpdateSecondariesOnMiss(),
        getUserOptions(),
        getAuthorizationUser(),
        timeoutSeconds,
        threshold,
        timeoutResponse);
  }

  /**
   * Return a WaitOptions instance like this instance, but with a new SecondaryTargets.
   *
   * @param secondaryTarget the new field value
   * @return the modified WaitOptions
   */
  public WaitOptions secondaryTargets(SecondaryTarget secondaryTarget) {
    Preconditions.checkNotNull(secondaryTarget);
    return new WaitOptions(
        getOpTimeoutController(),
        ImmutableSet.of(secondaryTarget),
        getTraceIDProvider(),
        getAllReplicasExcludedResponse(),
        getRetrievalType(),
        getVersionConstraint(),
        getNonExistenceResponse(),
        getVerifyChecksums(),
        getReturnInvalidations(),
        getUpdateSecondariesOnMiss(),
        getUserOptions(),
        getAuthorizationUser(),
        timeoutSeconds,
        threshold,
        timeoutResponse);
  }

  /**
   * Return a WaitOptions instance like this instance, but with a new traceIDProvider.
   *
   * @param traceIDProvider the new field value
   * @return the modified WaitOptions
   */
  public WaitOptions traceIDProvider(TraceIDProvider traceIDProvider) {
    Preconditions.checkNotNull(traceIDProvider);
    return new WaitOptions(
        getOpTimeoutController(),
        getSecondaryTargets(),
        traceIDProvider,
        getAllReplicasExcludedResponse(),
        getRetrievalType(),
        getVersionConstraint(),
        getNonExistenceResponse(),
        getVerifyChecksums(),
        getReturnInvalidations(),
        getUpdateSecondariesOnMiss(),
        getUserOptions(),
        getAuthorizationUser(),
        timeoutSeconds,
        threshold,
        timeoutResponse);
  }

  /**
   * Return a WaitOptions instance like this instance, but with a new RetrievalType.
   *
   * @param retrievalType the new field value
   * @return the modified WaitOptions
   */
  public WaitOptions retrievalType(RetrievalType retrievalType) {
    return new WaitOptions(
        getOpTimeoutController(),
        getSecondaryTargets(),
        getTraceIDProvider(),
        getAllReplicasExcludedResponse(),
        retrievalType,
        getVersionConstraint(),
        getNonExistenceResponse(),
        getVerifyChecksums(),
        getReturnInvalidations(),
        getUpdateSecondariesOnMiss(),
        getUserOptions(),
        getAuthorizationUser(),
        timeoutSeconds,
        threshold,
        timeoutResponse);
  }

  /**
   * Return a WaitOptions instance like this instance, but with a new VersionConstraint.
   *
   * @param versionConstraint the new field value
   * @return the modified WaitOptions
   */
  public WaitOptions versionConstraint(VersionConstraint versionConstraint) {
    return new WaitOptions(
        getOpTimeoutController(),
        getSecondaryTargets(),
        getTraceIDProvider(),
        getAllReplicasExcludedResponse(),
        getRetrievalType(),
        versionConstraint,
        getNonExistenceResponse(),
        getVerifyChecksums(),
        getReturnInvalidations(),
        getUpdateSecondariesOnMiss(),
        getUserOptions(),
        getAuthorizationUser(),
        timeoutSeconds,
        threshold,
        timeoutResponse);
  }

  /**
   * Return a WaitOptions instance like this instance, but with a new NonExistenceResponse.
   *
   * @param nonExistenceResponse the new field value
   * @return the modified WaitOptions
   */
  public WaitOptions nonExistenceResponse(NonExistenceResponse nonExistenceResponse) {
    return new WaitOptions(
        getOpTimeoutController(),
        getSecondaryTargets(),
        getTraceIDProvider(),
        getAllReplicasExcludedResponse(),
        getRetrievalType(),
        getVersionConstraint(),
        nonExistenceResponse,
        getVerifyChecksums(),
        getReturnInvalidations(),
        getUpdateSecondariesOnMiss(),
        getUserOptions(),
        getAuthorizationUser(),
        timeoutSeconds,
        threshold,
        timeoutResponse);
  }

  /**
   * Return a WaitOptions instance like this instance, but with a new verifyChecksums.
   *
   * @param verifyChecksums the new field value
   * @return the modified WaitOptions
   */
  public WaitOptions verifyChecksums(boolean verifyChecksums) {
    return new WaitOptions(
        getOpTimeoutController(),
        getSecondaryTargets(),
        getTraceIDProvider(),
        getAllReplicasExcludedResponse(),
        getRetrievalType(),
        getVersionConstraint(),
        getNonExistenceResponse(),
        verifyChecksums,
        getReturnInvalidations(),
        getUpdateSecondariesOnMiss(),
        getUserOptions(),
        getAuthorizationUser(),
        timeoutSeconds,
        threshold,
        timeoutResponse);
  }

  /**
   * Return a WaitOptions instance like this instance, but with a new returnInvalidations.
   *
   * @param returnInvalidations the new field value
   * @return the modified WaitOptions
   */
  public WaitOptions returnInvalidations(boolean returnInvalidations) {
    return new WaitOptions(
        getOpTimeoutController(),
        getSecondaryTargets(),
        getTraceIDProvider(),
        getAllReplicasExcludedResponse(),
        getRetrievalType(),
        getVersionConstraint(),
        getNonExistenceResponse(),
        getVerifyChecksums(),
        returnInvalidations,
        getUpdateSecondariesOnMiss(),
        getUserOptions(),
        getAuthorizationUser(),
        timeoutSeconds,
        threshold,
        timeoutResponse);
  }

  /**
   * Return a WaitOptions instance like this instance, but with a new updateSecondariesOnMiss.
   *
   * @param updateSecondariesOnMiss the new field value
   * @return the modified WaitOptions
   */
  public WaitOptions updateSecondariesOnMiss(boolean updateSecondariesOnMiss) {
    return new WaitOptions(
        getOpTimeoutController(),
        getSecondaryTargets(),
        getTraceIDProvider(),
        getAllReplicasExcludedResponse(),
        getRetrievalType(),
        getVersionConstraint(),
        getNonExistenceResponse(),
        getVerifyChecksums(),
        getReturnInvalidations(),
        updateSecondariesOnMiss,
        getUserOptions(),
        getAuthorizationUser(),
        timeoutSeconds,
        threshold,
        timeoutResponse);
  }

  /**
   * Return a WaitOptions instance like this instance, but with a new timeoutSeconds.
   *
   * @param timeoutSeconds the new field value
   * @return the modified WaitOptions
   */
  public WaitOptions timeoutSeconds(int timeoutSeconds) {
    return new WaitOptions(
        getOpTimeoutController(),
        getSecondaryTargets(),
        getTraceIDProvider(),
        getAllReplicasExcludedResponse(),
        getRetrievalType(),
        getVersionConstraint(),
        getNonExistenceResponse(),
        getVerifyChecksums(),
        getReturnInvalidations(),
        getUpdateSecondariesOnMiss(),
        getUserOptions(),
        getAuthorizationUser(),
        timeoutSeconds,
        threshold,
        timeoutResponse);
  }

  /**
   * Return a WaitOptions instance like this instance, but with a new threshold.
   *
   * @param threshold the new field value
   * @return the modified WaitOptions
   */
  public WaitOptions threshold(int threshold) {
    return new WaitOptions(
        getOpTimeoutController(),
        getSecondaryTargets(),
        getTraceIDProvider(),
        getAllReplicasExcludedResponse(),
        getRetrievalType(),
        getVersionConstraint(),
        getNonExistenceResponse(),
        getVerifyChecksums(),
        getReturnInvalidations(),
        getUpdateSecondariesOnMiss(),
        getUserOptions(),
        getAuthorizationUser(),
        timeoutSeconds,
        threshold,
        timeoutResponse);
  }

  public WaitOptions authorizationUser(byte[] authorizationUser) {
    return new WaitOptions(
        getOpTimeoutController(),
        getSecondaryTargets(),
        getTraceIDProvider(),
        getAllReplicasExcludedResponse(),
        getRetrievalType(),
        getVersionConstraint(),
        getNonExistenceResponse(),
        getVerifyChecksums(),
        getReturnInvalidations(),
        getUpdateSecondariesOnMiss(),
        getUserOptions(),
        authorizationUser,
        timeoutSeconds,
        threshold,
        getTimeoutResponse());
  }
  
  public WaitOptions allReplicasExcludedResponse(AllReplicasExcludedResponse allReplicasExcludedResponse) {
    return new WaitOptions(
        getOpTimeoutController(),
        getSecondaryTargets(),
        getTraceIDProvider(),
        allReplicasExcludedResponse,
        getRetrievalType(),
        getVersionConstraint(),
        getNonExistenceResponse(),
        getVerifyChecksums(),
        getReturnInvalidations(),
        getUpdateSecondariesOnMiss(),
        getUserOptions(),
        getAuthorizationUser(),
        timeoutSeconds,
        threshold,
        getTimeoutResponse());
  }
  
  /**
   * Return a WaitOptions instance like this instance, but with a new timeoutResponse.
   *
   * @param timeoutResponse the new field value
   * @return the modified WaitOptions
   */
  public WaitOptions timeoutResponse(TimeoutResponse timeoutResponse) {
    return new WaitOptions(
        getOpTimeoutController(),
        getSecondaryTargets(),
        getTraceIDProvider(),
        getAllReplicasExcludedResponse(),
        getRetrievalType(),
        getVersionConstraint(),
        getNonExistenceResponse(),
        getVerifyChecksums(),
        getReturnInvalidations(),
        getUpdateSecondariesOnMiss(),
        getUserOptions(),
        getAuthorizationUser(),
        timeoutSeconds,
        threshold,
        timeoutResponse);
  }

  public WaitOptions userOptions(byte[] userOptions) {
    return new WaitOptions(
        getOpTimeoutController(),
        getSecondaryTargets(),
        getTraceIDProvider(),
        getAllReplicasExcludedResponse(),
        getRetrievalType(),
        getVersionConstraint(),
        getNonExistenceResponse(),
        getVerifyChecksums(),
        getReturnInvalidations(),
        getUpdateSecondariesOnMiss(),
        userOptions,
        getAuthorizationUser(),
        timeoutSeconds,
        threshold,
        getTimeoutResponse());
  }

  /**
   * timeoutSeconds getter
   *
   * @return timeoutSeconds
   */
  public int getTimeoutSeconds() {
    return timeoutSeconds;
  }

  /**
   * threshold getter
   *
   * @return threshold
   */
  public int getThreshold() {
    return threshold;
  }

  /**
   * timeoutResponse getter
   *
   * @return timeoutResponse
   */
  public TimeoutResponse getTimeoutResponse() {
    return timeoutResponse;
  }

  /**
   * true if timeoutSeconds set, false otherwise
   *
   * @return true if timeoutSeconds set, false otherwise
   */
  public boolean hasTimeout() {
    return timeoutSeconds != NO_TIMEOUT;
  }

  @Override
  public String toString() {
    return ObjectDefParser2.objectToString(this);
  }

  /**
   * Parse a definition
   *
   * @param def object definition
   * @return a parsed WaitOptions instance
   */
  public static WaitOptions parse(String def) {
    return ObjectDefParser2.parse(WaitOptions.class, def);
  }

  @Override
  public int hashCode() {
    return super.hashCode() ^ timeoutSeconds ^ threshold ^ timeoutResponse.hashCode();
  }

  @Override
  public boolean equals(Object other) {
    if (this == other) {
      return true;
    } else {
      if (super.equals(other)) {
        WaitOptions oOptions;

        oOptions = (WaitOptions) other;
        return timeoutSeconds == oOptions.timeoutSeconds &&
               threshold == oOptions.threshold &&
               timeoutResponse == oOptions.timeoutResponse;
      } else {
        return false;
      }
    }
  }
}
