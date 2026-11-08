package com.roam.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Root business record. Identity is stable across Submission Versions.
 *
 * <p>The aggregate owns two kinds of data: the current working copy and immutable submitted
 * versions. A core itinerary change creates a new Submission Version; a Trip Reason-only change is
 * applied to the current version because it does not trigger re-approval.
 */
public final class TripRequest {

    private final String id;
    private final String employeeId;
    private final Instant createdAt;
    private final List<TripRequestVersion> versions = new ArrayList<>();
    private final List<ApprovalRecord> approvalRecords = new ArrayList<>();
    private TripRequestData workingData;
    private TripRequestStatus status;
    private String pendingReviewId;
    private boolean versionLocked;
    private boolean rejectedVersion;

    public TripRequest(String id, String employeeId, TripRequestData initialData, Instant createdAt) {
        this.id = Objects.requireNonNull(id, "id");
        this.employeeId = Objects.requireNonNull(employeeId, "employeeId");
        this.workingData = Objects.requireNonNull(initialData, "initialData");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.status = TripRequestStatus.DRAFT;
    }

    public String id() {
        return id;
    }

    public String employeeId() {
        return employeeId;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public TripRequestStatus status() {
        return status;
    }

    public boolean versionLocked() {
        return versionLocked;
    }

    public String pendingReviewId() {
        return pendingReviewId;
    }

    public List<TripRequestVersion> versions() {
        return List.copyOf(versions);
    }

    public List<ApprovalRecord> approvalRecords() {
        return List.copyOf(approvalRecords);
    }

    public int currentVersionNumber() {
        return versions.isEmpty() ? 0 : versions.get(versions.size() - 1).version();
    }

    public TripRequestVersion currentVersion() {
        if (versions.isEmpty()) {
            throw new IllegalStateException("Trip Request has no submitted version");
        }
        return versions.get(versions.size() - 1);
    }

    public TripRequestData currentData() {
        return versions.isEmpty() ? workingData : currentVersion().data();
    }

    /** Applies employee input before submission. */
    public void edit(TripRequestData updated) {
        requireNotLocked();
        if (status == TripRequestStatus.AWAITING_REVIEW) {
            throw new IllegalStateException("cancel the pending review before editing");
        }
        TripRequestData previous = currentData();
        workingData = updated;
        if (versions.isEmpty()) {
            return;
        }
        boolean coreChanged = !previous.coreItineraryEquals(updated);
        if (coreChanged) {
            status = TripRequestStatus.DRAFT;
        } else if (updated.tripReason() != null) {
            currentVersion().replaceReason(updated.tripReason());
        }
    }

    /**
     * Submits the current working copy. A new Submission Version is created when there is no prior
     * version, when a rejected/approved version receives core changes, or when the employee
     * explicitly asks for a new version after rejection. Completing information for the same
     * in-flight version updates that version in place.
     */
    public TripRequestVersion submit(String submittedBy, Instant now, boolean forceNewVersion) {
        requireNotLocked();
        TripRequestData data = workingData;
        int current = currentVersionNumber();
        boolean coreChanged =
                versions.isEmpty() || !currentVersion().data().coreItineraryEquals(data);
        boolean createVersion = versions.isEmpty()
                || forceNewVersion
                || rejectedVersion
                || status == TripRequestStatus.REJECTED
                || (coreChanged && hasApprovalRecord());
        if (createVersion) {
            int nextVersion = current + 1;
            TripRequestVersion version =
                    new TripRequestVersion(nextVersion, data, now, submittedBy, coreChanged);
            versions.add(version);
            status = TripRequestStatus.SUBMITTED;
            rejectedVersion = false;
            return version;
        }
        // Same in-flight version, completed information or reason-only edit.
        if (coreChanged) {
            currentVersion().replaceData(data);
        } else if (data.tripReason() != null) {
            currentVersion().replaceReason(data.tripReason());
        }
        status = TripRequestStatus.SUBMITTED;
        return currentVersion();
    }

    public boolean hasApprovalRecord() {
        return !approvalRecords.isEmpty();
    }

    public boolean isApprovedForCurrentVersion() {
        int current = currentVersionNumber();
        return (status == TripRequestStatus.APPROVED
                        || status == TripRequestStatus.EXECUTION_IN_PROGRESS
                        || status == TripRequestStatus.PARTIALLY_FULFILLED
                        || status == TripRequestStatus.FULFILLED
                        || status == TripRequestStatus.FAILED)
                && approvalRecords.stream().anyMatch(record -> record.version() == current);
    }

    public ApprovalRecord approvalForCurrentVersion() {
        int current = currentVersionNumber();
        return approvalRecords.stream()
                .filter(record -> record.version() == current)
                .reduce((first, second) -> second)
                .orElse(null);
    }

    public void markAwaitingInfo() {
        requireNotLocked();
        status = TripRequestStatus.AWAITING_INFO;
    }

    public void markAwaitingReview(String reviewId) {
        status = TripRequestStatus.AWAITING_REVIEW;
        pendingReviewId = Objects.requireNonNull(reviewId, "reviewId");
        versionLocked = true;
    }

    public void clearReview() {
        pendingReviewId = null;
        versionLocked = false;
    }

    public void markRejected() {
        status = TripRequestStatus.REJECTED;
        rejectedVersion = true;
        pendingReviewId = null;
        versionLocked = false;
    }

    public void markApproved(ApprovalRecord record) {
        if (record.version() != currentVersionNumber()) {
            throw new IllegalArgumentException("approval record must target the current version");
        }
        approvalRecords.add(record);
        status = TripRequestStatus.APPROVED;
        rejectedVersion = false;
        pendingReviewId = null;
        versionLocked = false;
    }

    public void markExecution(TripRequestStatus executionStatus) {
        if (!isApprovedForCurrentVersion()) {
            throw new IllegalStateException("cannot project execution state for an unapproved version");
        }
        this.status = executionStatus;
    }

    private void requireNotLocked() {
        if (versionLocked) {
            throw new IllegalStateException("the reviewed Trip Request version is locked");
        }
    }
}
