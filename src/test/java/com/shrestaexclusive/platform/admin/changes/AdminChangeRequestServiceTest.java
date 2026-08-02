package com.shrestaexclusive.platform.admin.changes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.shrestaexclusive.platform.asset.AssetService;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class AdminChangeRequestServiceTest {

    private final AdminChangeRequestRepository repository = mock(AdminChangeRequestRepository.class);
    private final AdminChangeRequestApplier applier = mock(AdminChangeRequestApplier.class);
    private final AssetService assetService = mock(AssetService.class);
    private final AdminChangeRequestService service = new AdminChangeRequestService(repository, applier, assetService);

    @Test
    void appliesPendingRequestBeforeApproving() {
        AdminChangeRequestResponse pending = response("PENDING_REVIEW");
        AdminChangeRequestResponse approved = response("APPROVED");
        AdminChangeRequestDecisionRequest decision = new AdminChangeRequestDecisionRequest("reviewer@shresta.local", "Approved");
        when(repository.findByRequestKey("acr-1")).thenReturn(Optional.of(pending));
        when(repository.approve("acr-1", "CHANGE_REVIEWER", decision)).thenReturn(approved);

        AdminChangeRequestResponse actual = service.approve("acr-1", "CHANGE_REVIEWER", decision);

        assertThat(actual.status()).isEqualTo("APPROVED");
        verify(applier).apply(pending);
        verify(repository).approve("acr-1", "CHANGE_REVIEWER", decision);
    }

    @Test
    void doesNotReapplyAlreadyReviewedRequest() {
        AdminChangeRequestResponse approved = response("APPROVED");
        AdminChangeRequestDecisionRequest decision = new AdminChangeRequestDecisionRequest("reviewer@shresta.local", "Approved again");
        when(repository.findByRequestKey("acr-1")).thenReturn(Optional.of(approved));

        AdminChangeRequestResponse actual = service.approve("acr-1", "CHANGE_REVIEWER", decision);

        assertThat(actual.status()).isEqualTo("APPROVED");
        verify(applier, never()).apply(approved);
        verify(repository, never()).approve("acr-1", "CHANGE_REVIEWER", decision);
    }

    private AdminChangeRequestResponse response(String status) {
        return new AdminChangeRequestResponse(
                "acr-1",
                "asset-removal",
                "media_asset",
                "hero-silk-saree-maroon-gold",
                "ARCHIVE",
                status,
                "CHANGE_SUBMITTER",
                "SHRESTA asset admin",
                null,
                null,
                null,
                Map.of("assetKey", "hero-silk-saree-maroon-gold"),
                Instant.parse("2026-07-05T00:00:00Z"),
                Instant.parse("2026-07-05T00:00:00Z"),
                null
        );
    }
}
