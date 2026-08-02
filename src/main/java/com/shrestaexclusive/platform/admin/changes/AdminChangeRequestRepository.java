package com.shrestaexclusive.platform.admin.changes;

import java.util.List;
import java.util.Optional;

interface AdminChangeRequestRepository {

    AdminChangeRequestResponse create(String requestKey, String submittedByRole, AdminChangeRequestCreateRequest request);

    /** Replace an existing PENDING_REVIEW request for (entityKey, requestType) if one exists; otherwise create. */
    AdminChangeRequestResponse upsertPending(String newRequestKey, String submittedByRole, AdminChangeRequestCreateRequest request);

    List<AdminChangeRequestResponse> list(String status);

    Optional<AdminChangeRequestResponse> findByRequestKey(String requestKey);

    Optional<AdminChangeRequestResponse> findFirstPending(String entityKey, String requestType);

    AdminChangeRequestResponse approve(String requestKey, String reviewerRole, AdminChangeRequestDecisionRequest request);

    AdminChangeRequestResponse reject(String requestKey, String reviewerRole, AdminChangeRequestDecisionRequest request);
}
